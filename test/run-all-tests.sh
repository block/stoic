#!/usr/bin/env bash
set -euo pipefail

# Script to run all Stoic tests
# This is meant to be invoked by with-emulator.sh

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "========================================"
echo "Running all Stoic tests on API $API_LEVEL"
echo "Device: $ADB_SERIAL"
echo "========================================"
echo ""

# Track failures
FAILED_TESTS=()

# Run each test suite
TESTS=(
    "test-demo-app-without-sdk.sh"
    "test-plugin-new.sh"
    "test-without-config.sh"
)

for test in "${TESTS[@]}"; do
    echo "----------------------------------------"
    echo "Running: $test"
    echo "----------------------------------------"

    if "$script_dir/$test"; then
        echo "✓ $test PASSED"
    else
        echo "✗ $test FAILED"
        FAILED_TESTS+=("$test")
    fi
    echo ""
done

echo "========================================"
echo "Test Summary"
echo "========================================"
echo "Total tests: ${#TESTS[@]}"
echo "Passed: $((${#TESTS[@]} - ${#FAILED_TESTS[@]}))"
echo "Failed: ${#FAILED_TESTS[@]}"

if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
    echo ""
    echo "Failed tests:"
    for test in "${FAILED_TESTS[@]}"; do
        echo "  - $test"
    done
    exit 1
fi

echo ""
echo "All tests passed!"
exit 0
