#!/bin/bash
#set -x
set -euo pipefail

stoic_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"
stoic_release_dir="$stoic_dir/out/rel"
stoic_core_sync_dir="$stoic_release_dir/sync"

source "$stoic_dir/prebuilt/stoic.properties"

mkdir -p "$stoic_release_dir"/jar
mkdir -p "$stoic_release_dir"/sdk
mkdir -p "$stoic_release_dir"/bin/darwin-arm64
rsync --archive "$stoic_dir"/prebuilt/ "$stoic_release_dir"/

AUTO_YES=0
for arg in "$@"; do
    case $arg in
        --yes) AUTO_YES=1 ;;
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
    >&2 echo "Will run \`git submodule update --init --recursive\`"
    if [[ "$AUTO_YES" -eq 1 ]]; then
        >/dev/null pushd "$stoic_dir"
        git submodule update --init --recursive
        >/dev/null popd
    else
        read -r -p "Okay? (Y/n) " choice
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
fi

mkdir -p "$stoic_core_sync_dir"/{plugins,stoic,bin,apk}


#
# Install required SDK packages
#

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ANDROID_HOME env variable not defined. This should be the path to your Android SDK."
    echo "e.g."
    echo "    export ANDROID_HOME=~/Library/Android/sdk"
    exit 1
fi

# Find sdkmanager script (falling back to the old location if the new one
# is missing)
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -e "$sdkmanager" ]; then
    >&2 echo "Failed to find sdkmanager in its usual location."
    >&2 echo "Please update Android SDK Command-line Tools to the latest version."
    exit 1
fi

sdk_packages="$("$sdkmanager" --list_installed 2>/dev/null | awk '{print $1}')"
required_packages=(
  "build-tools;$android_build_tools_version"
  "platforms;android-$android_target_sdk"
  "ndk;$android_ndk_version"
)
missing=()

for required in "${required_packages[@]}"; do
    if ! echo "$sdk_packages" | grep "$required" >/dev/null; then
        missing+=("$required")
    fi
done

if [ ${#missing[@]} -gt 0 ]; then
    >&2 echo "stoic requires Android SDK packages: ${missing[*]}"
    >&2 echo "Will run \`$sdkmanager ${missing[*]}\`"
    if [[ "$AUTO_YES" -eq 1 ]]; then
        for x in "${missing[@]}"; do
            $sdkmanager "$x"
        done
    else
        read -r -p "Okay? (Y/n) " choice
        case "$(echo "$choice" | tr '[:upper:]' '[:lower:]')" in
          n*)
            exit 1
            ;;
          *)
            for x in "${missing[@]}"; do
                $sdkmanager "$x"
            done
            ;;
        esac
    fi
fi


# Used by native/Makefile.inc
export ANDROID_NDK="$ANDROID_HOME/ndk/$android_ndk_version"

stoic_version="$(cat "$stoic_dir/prebuilt/STOIC_VERSION")"

cd "$stoic_dir"

setup_graalvm() {
  if [ -n "${GRAALVM_HOME:-}" ] && [ -d "$GRAALVM_HOME" ]; then
    return 0
  fi
  if  { brew list --cask  --versions graalvm-ce-java17 >/dev/null 2>&1; }; then
    GRAALVM_HOME="$(brew info graalvm-ce-java17 | grep 'export JAVA_HOME' | sed -E 's/^ *export JAVA_HOME="(.*)"/\1/')"
    export GRAALVM_HOME
    return 0
  fi
  # macOS – install via Homebrew if necessary
  if [[ "$(uname)" == "Darwin" ]]; then
    if ! command -v brew >/dev/null 2>&1; then
      >&2 echo "Homebrew is required to automatically install GraalVM. Either install Homebrew, install GraalVM manually, or export GRAALVM_HOME before running this script."
      exit 1
    fi
    
    brew install graalvm/tap/graalvm-ce-java17
    GRAALVM_HOME="$(brew info graalvm-ce-java17 | grep 'export JAVA_HOME' | sed -E 's/^ *export JAVA_HOME="(.*)"/\1/')"
    export GRAALVM_HOME
    # macOS Gatekeeper marks downloaded binaries as quarantined; unquarantine.
    # The quarantine attribute lives on the GraalVM bundle root (…/graalvm-ce-java17-xx). Remove it from there too.
    xattr -r -d com.apple.quarantine "$(dirname "$(dirname "$GRAALVM_HOME")")" 2>/dev/null || true
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
  :target:plugin-sdk:assemble \
  :target:runtime:attached:apk \
  :demo-plugin:helloworld:apk \
  :demo-plugin:appexitinfo:apk \
  :demo-plugin:breakpoint:apk \
  :demo-plugin:crasher:apk \
  :demo-plugin:testsuite:apk \
  :demo-app:without-sdk:assembleDebug \
  :demo-app:with-sdk:assembleRelease

cp "host/main/build/libs/main-$stoic_version.jar" "$stoic_release_dir"/jar/stoic-host-main.jar
cp host/main/build/native/nativeCompile/stoic "$stoic_release_dir"/bin/darwin-arm64/
cp "target/plugin-sdk/build/libs/plugin-sdk-$stoic_version.jar" "$stoic_release_dir"/sdk/stoic-plugin-sdk.jar
cp "target/plugin-sdk/build/libs/plugin-sdk-$stoic_version-sources.jar" "$stoic_release_dir"/sdk/stoic-plugin-sdk-sources.jar
cp "target/runtime/attached/build/libs/attached-$stoic_version.apk" "$stoic_core_sync_dir/stoic/stoic-runtime-attached.apk"
cp demo-app/without-sdk/build/outputs/apk/debug/without-sdk-debug.apk "$stoic_core_sync_dir/apk/stoic-demo-app-without-sdk-debug.apk"
cp demo-app/with-sdk/build/outputs/apk/release/with-sdk-release.apk "$stoic_core_sync_dir/apk/stoic-demo-app-with-sdk-release.apk"


# TODO: Find a better location fo demo plugins - we don't need to sync them to the device anymore
demo_plugins_dir="$stoic_release_dir/demo-plugins"
mkdir -p "$demo_plugins_dir"
cp "demo-plugin/appexitinfo/build/libs/appexitinfo-$stoic_version.apk" "$demo_plugins_dir"/appexitinfo.apk
cp "demo-plugin/breakpoint/build/libs/breakpoint-$stoic_version.apk" "$demo_plugins_dir"/breakpoint.apk
cp "demo-plugin/crasher/build/libs/crasher-$stoic_version.apk" "$demo_plugins_dir"/crasher.apk
cp "demo-plugin/helloworld/build/libs/helloworld-$stoic_version.apk" "$demo_plugins_dir"/helloworld.apk
cp "demo-plugin/testsuite/build/libs/testsuite-$stoic_version.apk" "$demo_plugins_dir"/testsuite.apk

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
    >&2 echo "    echo export PATH=\$PATH:$stoic_dir/out/rel/bin/darwin-arm64 >> $config_file && source $config_file"
elif [ "$stoic_path" != "$stoic_dir/out/rel/bin/darwin-arm64/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just built is in \`$stoic_dir/out/rel/bin/darwin-arm64\`"
else
    >&2 echo "stoic correctly resolves to the version you just built."
fi
>&2 echo
