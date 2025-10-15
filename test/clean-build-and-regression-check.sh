#!/bin/bash
set -euxo pipefail

# This script should be run before pushing to main - all errors must be addressed

script_dir="$(dirname "$(readlink -f "$0")")"

# To ensure stability, we always build clean
(cd "$script_dir/.." && ./gradlew clean)
"$script_dir/../build.sh"

if [ "$(realpath "$(which stoic)")" != "$(realpath "$script_dir/../build/distributions/bin/darwin-arm64/stoic")" ]; then
  echo stoic resolves to "$(which stoic)" - not the one we just built
  exit 1
fi

"$script_dir"/shellcheck.sh
"$script_dir"/test-demo-app-without-sdk.sh
"$script_dir"/test-plugin-new.sh
"$script_dir"/test-without-config.sh

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
