#!/bin/bash
set -euo pipefail
script_dir="$(dirname "$(readlink -f "$0")")"

# Ensure we're using the correct stoic binary
source "$script_dir/setup-stoic-path.sh"

# Build the test plugin
cd "$script_dir/.."
./gradlew :test-plugin:apk --quiet --console=plain
test_plugin="test-plugin/build/libs/test-plugin-$(cat prebuilt/STOIC_VERSION | tr -d '\n').apk"
cd - > /dev/null

stoic "$test_plugin" testsuite
