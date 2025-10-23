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

# Function to exit with line number
abort() {
    >&2 echo "line $1: $2"
    exit 1
}

verify_output() {
    expected="$1"
    lineno="$2"
    shift
    shift
    >&2 echo verify_output "$@"
    output="$("$@")"
    if [ "$output" != "$expected" ]; then
        echo "expected: '$expected'"
        echo "actual  : '$output'"
        abort "$lineno" Failed
    fi
}

pkg=com.squareup.stoic.demoapp.withoutsdk

# Test single env var
verify_output "TEST_VALUE" "$LINENO" \
    stoic --package="$pkg" --restart --env TEST_VAR=TEST_VALUE \
    "$test_plugin" getenv TEST_VAR

# Test multiple env vars
verify_output $'bar\nqux' "$LINENO" \
    stoic --package="$pkg" --env FOO=bar --env BAZ=qux \
    "$test_plugin" getenv FOO BAZ

# Test env var with spaces
verify_output "hello world" "$LINENO" \
    stoic --package="$pkg" --env "SPACE_VAR=hello world" \
    "$test_plugin" getenv SPACE_VAR

# Test env var with special characters
verify_output "a=b&c" "$LINENO" \
    stoic --package="$pkg" --env "SPECIAL=a=b&c" \
    "$test_plugin" getenv SPECIAL

# Test env var with multiple equals signs
verify_output "bar=baz" "$LINENO" \
    stoic --package="$pkg" --env "MULTI_EQUALS=bar=baz" \
    "$test_plugin" getenv MULTI_EQUALS

# Test missing env var returns empty string
verify_output "" "$LINENO" \
    stoic --package="$pkg" "$test_plugin" getenv NONEXISTENT_VAR

echo "✓ All --env flag tests passed"
