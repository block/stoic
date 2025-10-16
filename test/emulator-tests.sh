#!/usr/bin/env bash
set -euo pipefail

# Script to run Stoic tests on multiple emulator API levels
# This script tests on a subset of API levels for speed (29 and 34)

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "================================================"
echo "Stoic Emulator Test Suite"
echo "================================================"
echo "This will run all tests on API 29 and API 34"
echo ""

# API levels to test
API_LEVELS=(29 30 35)

# Track failures
FAILED_API_LEVELS=()

for api in "${API_LEVELS[@]}"; do
    echo ""
    echo "================================================"
    echo "Testing on API $api"
    echo "================================================"
    echo ""

    if "$script_dir/with-emulator.sh" "$api" "$script_dir/run-all-tests-on-connected-device.sh"; then
        echo ""
        echo "✓ All tests PASSED on API $api"
    else
        echo ""
        echo "✗ Tests FAILED on API $api"
        FAILED_API_LEVELS+=("$api")
    fi
done

echo ""
echo "================================================"
echo "Final Summary"
echo "================================================"
echo "API levels tested: ${API_LEVELS[*]}"
echo "Passed: $((${#API_LEVELS[@]} - ${#FAILED_API_LEVELS[@]}))"
echo "Failed: ${#FAILED_API_LEVELS[@]}"

if [ ${#FAILED_API_LEVELS[@]} -gt 0 ]; then
    echo ""
    echo "Failed on API levels:"
    for api in "${FAILED_API_LEVELS[@]}"; do
        echo "  - API $api"
    done
    echo ""
    exit 1
fi

echo ""
echo "All tests passed on all API levels!"
exit 0
