#!/bin/bash
set -euxo pipefail

# Run a minimal test/tests quickly to find problems
# We expect that there is exactly one connected device

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parse args - we expect 0 or 1 arg
if [ $# -eq 0 ]; then
  test_script="$script_dir/run-all-tests-on-connected-device.sh"
elif [ $# -eq 1 ]; then
  test_script="$1"
  # If the script name doesn't contain / and doesn't exist, look for it in script_dir
  if [[ "$test_script" != */* ]] && [ ! -f "$test_script" ]; then
    test_script="$script_dir/$test_script"
  fi
  # Verify the test script exists
  if [ ! -f "$test_script" ]; then
    echo "Error: Test script not found: $test_script" >&2
    exit 1
  fi
else
  echo "Usage: $0 [test-script]" >&2
  exit 1
fi

# Make sure stoic is built and up-to-date
# This only builds the jvm version (not graal) because building graal is slow
"$script_dir"/../bnr.sh --version

# Make sure we're using the version we just built
export PATH="$script_dir/../build/distributions/bin/jvm":$PATH

# Invoke the test script
"$test_script"
