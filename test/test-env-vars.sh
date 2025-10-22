#!/bin/bash
set -euo pipefail

# Test STOIC_REQUIRED_VERSION and STOIC_ATTACH_VIA environment variables

echo "Testing STOIC_REQUIRED_VERSION..."

# Test 1: Lower required version should succeed
echo -n "  Test 1 (lower version): "
if STOIC_REQUIRED_VERSION="0.5.0" stoic --version &>/dev/null; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

# Test 2: Equal major.minor.patch should succeed
echo -n "  Test 2 (equal version): "
current_version=$(stoic --version | awk '{print $3}')
if STOIC_REQUIRED_VERSION="$current_version" stoic --version &>/dev/null; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

# Test 3: Higher required version should fail
echo -n "  Test 3 (higher version): "
set +e
output=$(STOIC_REQUIRED_VERSION="99.0.0" stoic --version 2>&1)
exit_code=$?
set -e
if [ $exit_code -ne 0 ] && echo "$output" | grep -q "is lower than required"; then
    echo "✓ PASS"
else
    echo "✗ FAIL - Expected 'is lower than required' in output: $output"
    exit 1
fi

# Test 4: Invalid version format should fail with appropriate message
echo -n "  Test 4 (invalid format): "
set +e
output=$(STOIC_REQUIRED_VERSION="invalid" stoic --version 2>&1)
exit_code=$?
set -e
if [ $exit_code -ne 0 ] && echo "$output" | grep -q "Could not parse"; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

echo ""
echo "Testing STOIC_ATTACH_VIA..."

# Test 5: Valid sdk value should not cause errors
echo -n "  Test 5 (sdk value): "
if STOIC_ATTACH_VIA="sdk" stoic --help &>/dev/null; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

# Test 6: Valid jvmti-root value should not cause errors
echo -n "  Test 6 (jvmti-root value): "
if STOIC_ATTACH_VIA="jvmti-root" stoic --help &>/dev/null; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

# Test 7: Valid jvmti value should not cause errors
echo -n "  Test 7 (jvmti value): "
if STOIC_ATTACH_VIA="jvmti" stoic --help &>/dev/null; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

# Test 8: Invalid value should show error
echo -n "  Test 8 (invalid value): "
set +e
output=$(STOIC_ATTACH_VIA="invalid-mode" stoic --help 2>&1)
exit_code=$?
set -e
if [ $exit_code -ne 0 ] && echo "$output" | grep -q "Invalid STOIC_ATTACH_VIA"; then
    echo "✓ PASS"
else
    echo "✗ FAIL"
    exit 1
fi

echo ""
echo "✓ All environment variable tests passed!"
