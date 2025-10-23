#!/bin/bash
set -euxo pipefail

# This script should be run before pushing to main - all errors must be addressed

script_dir="$(dirname "$(readlink -f "$0")")"

# To ensure stability, we always build clean
(cd "$script_dir/.." && ./gradlew clean)
"$script_dir/../build.sh"

# Use the native build we just created
export STOIC_BIN="$script_dir/../build/distributions/bin/darwin-arm64/stoic"
source "$script_dir/setup-stoic-path.sh"

"$script_dir"/shellcheck.sh
"$script_dir"/emulator-tests.sh

# TODO: these tests require functionality not available on older versions of
#   Android
# "$script_dir/../build/distributions/bin/stoic" testsuite

# TODO
#"$script_dir"/test-shebang.sh


set +x
echo
echo
echo All checks completed successfully
echo
echo
