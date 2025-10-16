#!/bin/bash
#set -x

# When executed (not sourced), enable strict error handling
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    set -euo pipefail
fi

# One-time setup script for Stoic development environment
# This checks and installs required dependencies:
# - Git submodules
# - Android SDK packages
# - GraalVM

# Determine script directory, handling both sourced and executed cases in bash and zsh
if [ -n "${ZSH_VERSION:-}" ]; then
    # For Zsh
    # shellcheck disable=SC2296
    stoic_dir="$(cd "$(dirname "${(%):-%N}")" && pwd)"
elif [ -n "${BASH_VERSION:-}" ]; then
    # For Bash
    stoic_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
    # Fallback for other shells
    stoic_dir="$(cd "$(dirname "$0")" && pwd)"
fi

# Read Android SDK versions from gradle.properties
android_build_tools_version=$(grep "^android.buildToolsVersion=" "$stoic_dir/gradle.properties" | cut -d'=' -f2)
android_target_sdk=$(grep "^android.targetSdk=" "$stoic_dir/gradle.properties" | cut -d'=' -f2)
android_ndk_version=$(grep "^android.ndkVersion=" "$stoic_dir/gradle.properties" | cut -d'=' -f2)

# AUTO_YES can be set by the calling script or passed as --yes argument
# Default to 0 if not set
if [ -z "${AUTO_YES+x}" ]; then
    AUTO_YES=0
    # Only parse arguments if not already set by caller
    for arg in "$@"; do
        case $arg in
            --yes) AUTO_YES=1 ;;
            *)
                >&2 echo "Unrecognized arg: $arg"
                exit 1
                ;;
        esac
    done
fi

#
# Git submodules have been removed - dependencies are now included locally
# (kept this comment as documentation of the change)
#

#
# Install required Android SDK packages
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

#
# Setup GraalVM
#

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

echo "Setup completed successfully!"
echo "ANDROID_NDK=$ANDROID_NDK"
echo "GRAALVM_HOME=$GRAALVM_HOME"

# Export for use by calling scripts
export ANDROID_NDK
export GRAALVM_HOME
