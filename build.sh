#!/bin/bash
#set -x
set -euo pipefail

stoic_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"
stoic_kotlin_dir="$stoic_dir/kotlin"
stoic_release_dir="$stoic_dir/out/rel"
stoic_core_sync_dir="$stoic_release_dir/sync"

source "$stoic_dir/prebuilt/stoic.properties"

mkdir -p "$stoic_release_dir"/jar
mkdir -p "$stoic_release_dir"/sdk
mkdir -p "$stoic_release_dir"/bin
rsync --archive "$stoic_dir"/prebuilt/ "$stoic_release_dir"/

for arg in "$@"; do
    case $arg in
        *)
            >&2 echo "Unrecognized arg: $arg"
            exit 1
            ;;
    esac
done

verify_submodules() {
    for x in "$@"; do
        if [ -z "$(2>/dev/null ls "$stoic_dir/$x/")" ]; then
            return 1
        fi
    done

    return 0
}

if ! verify_submodules native/libbase/ native/fmtlib/ native/libnativehelper/; then
    >&2 echo "Submodules are missing. Likely your ran git clone without --recurse-submodules."
    >&2 echo "Okay to update? (Will run \`git submodule update --init --recursive\`)"
    read -r -p "Y/n? " choice
    case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
      n*)
        exit 1
        ;;
      *)
        >/dev/null pushd "$stoic_dir"
        git submodule update --init --recursive
        >/dev/null popd
        ;;
    esac
fi

mkdir -p "$stoic_core_sync_dir"/{plugins,stoic,bin,apk}
"$stoic_dir"/prebuilt/script/check_required_sdk_packages.sh "build-tools;$android_build_tools_version" "platforms;android-$android_target_sdk" "ndk;$android_ndk_version"

# Used by native/Makefile.inc
export ANDROID_NDK="$ANDROID_HOME/ndk/$android_ndk_version"

cd "$stoic_kotlin_dir"

setup_graalvm() {
  if [ -n "${GRAALVM_HOME:-}" ] && [ -d "$GRAALVM_HOME" ]; then
    return 0
  fi
  if  { brew list --cask  --versions graalvm-ce-java17 >/dev/null 2>&1; }; then
    export GRAALVM_HOME="$(brew info graalvm-ce-java17 | grep 'export JAVA_HOME' | sed -E 's/^ *export JAVA_HOME="(.*)"/\1/')"
    return 0
  fi
  # macOS – install via Homebrew if necessary
  if [[ "$(uname)" == "Darwin" ]]; then
    if ! command -v brew >/dev/null 2>&1; then
      >&2 echo "Homebrew is required to automatically install GraalVM. Either install Homebrew, install GraalVM manually, or export GRAALVM_HOME before running this script."
      exit 1
    fi
    
    brew install graalvm/tap/graalvm-ce-java17
    export GRAALVM_HOME="$(brew info graalvm-ce-java17 | grep 'export JAVA_HOME' | sed -E 's/^ *export JAVA_HOME="(.*)"/\1/')"
    # macOS Gatekeeper marks downloaded binaries as quarantined; unquarantine.
    xattr -d -r com.apple.quarantine "$GRAALVM_HOME" 2>/dev/null || true
  else
    # Other platforms – ask the user to install GraalVM manually.
    >&2 echo "Automatic GraalVM installation is only supported on macOS. Please install GraalVM CE Java 17 and set GRAALVM_HOME."
    exit 1
  fi
}

setup_graalvm

# :demo-app:without-sdk is the debug app that's used by default. It needs to be debug so
# that stoic can attach to it.
./gradlew --parallel \
  :host:main:assemble \
  :host:main:nativeCompile \
  :android:plugin-sdk:assemble \
  :android:server:attached:dexJar \
  :demo-plugin:helloworld:dexJar \
  :demo-plugin:appexitinfo:dexJar \
  :demo-plugin:breakpoint:dexJar \
  :demo-plugin:crasher:dexJar \
  :demo-plugin:testsuite:dexJar \
  :demo-app:without-sdk:assembleDebug

cp host/main/build/libs/main.jar "$stoic_release_dir"/jar/stoic-host-main.jar
cp host/main/build/native/nativeCompile/stoic "$stoic_release_dir"/bin/
cp android/plugin-sdk/build/libs/plugin-sdk.jar "$stoic_release_dir"/sdk/stoic-android-plugin-sdk.jar
cp android/plugin-sdk/build/libs/plugin-sdk-sources.jar "$stoic_release_dir"/sdk/stoic-android-plugin-sdk-sources.jar
cp android/server/attached/build/libs/attached.dex.jar "$stoic_core_sync_dir/stoic/stoic-server-attached.dex.jar"
cp demo-app/without-sdk/build/outputs/apk/debug/without-sdk-debug.apk "$stoic_core_sync_dir/apk/stoic-demo-app-without-sdk-debug.apk"

# TODO: Find a better location fo demo plugins - we don't need to sync them to the device anymore
demo_plugins_dir="$stoic_release_dir/demo-plugins"
mkdir -p "$demo_plugins_dir"
cp demo-plugin/appexitinfo/build/libs/appexitinfo.dex.jar "$demo_plugins_dir"/
cp demo-plugin/breakpoint/build/libs/breakpoint.dex.jar "$demo_plugins_dir"/
cp demo-plugin/crasher/build/libs/crasher.dex.jar "$demo_plugins_dir"/
cp demo-plugin/helloworld/build/libs/helloworld.dex.jar "$demo_plugins_dir"/
cp demo-plugin/testsuite/build/libs/testsuite.dex.jar "$demo_plugins_dir"/

cd "$stoic_dir/native"
make -j16 all

chmod -R a+rw "$stoic_core_sync_dir"

echo
echo
echo "----- Stoic build completed -----"
echo
echo

set +e
stoic_path="$(readlink -f "$(which stoic)")"
set -e

if [ -z "$stoic_path" ]; then
    case "$SHELL" in
      */bash)
        config_file='~''/.bashrc'
        ;;
      */zsh)
        config_file='~''/.zshrc'
        ;;
      *)
        config_file="<path-to-your-config-file>"
        ;;
    esac

    >&2 echo "WARNING: stoic is missing from your PATH. Next, please run:"
    >&2 echo
    >&2 echo "    echo export PATH=\$PATH:$stoic_dir/out/rel/bin >> $config_file && source $config_file"
    >&2 echo "    stoic init-config"
elif [ "$stoic_path" != "$stoic_dir/out/rel/bin/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just built is in \`$stoic_dir/out/rel/bin\`"
else
    >&2 echo "stoic correctly resolves to the version you just built."
fi
>&2 echo
