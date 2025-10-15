#!/bin/bash
set -euo pipefail

# Main build script for Stoic
# Runs environment setup then delegates to Gradle

stoic_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"

# Parse arguments and pass to setup.sh via environment
export AUTO_YES=0
for arg in "$@"; do
    case $arg in
        --yes) AUTO_YES=1 ;;
        *)
            >&2 echo "Unrecognized arg: $arg"
            exit 1
            ;;
    esac
done

# Source setup (checks submodules, Android SDK, GraalVM)
# Note: We source it so environment variables are available in this shell
source "$stoic_dir/setup.sh"

# Build everything and assemble distribution
cd "$stoic_dir"
./gradlew buildDistribution --parallel

# Verify stoic is in PATH
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
    >&2 echo "    echo export PATH=\$PATH:$stoic_dir/build/distributions/bin/darwin-arm64 >> $config_file && source $config_file"
elif [ "$stoic_path" != "$stoic_dir/build/distributions/bin/darwin-arm64/stoic" ]; then
    >&2 echo "WARNING: Your PATH is currently including stoic from: $stoic_path"
    >&2 echo "The version you just built is in \`$stoic_dir/build/distributions/bin/darwin-arm64\`"
else
    >&2 echo "stoic correctly resolves to the version you just built."
fi
>&2 echo
