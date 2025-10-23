#!/bin/bash
set -euo pipefail

# Ensure we're using the correct stoic binary
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/setup-stoic-path.sh"

# This test exists to ensure that the stoic server properly enforces the protocol version

# Keep in sync with protocol-version-client source
exit_accept=0
exit_reject=5
exit_error=10

stoic_protocol_version=5
invalid_protocol_version=999
repo_dir="$(realpath "$script_dir/..")"
stoic_version="$(cat "$repo_dir/prebuilt/STOIC_VERSION")"

cd "$repo_dir"

# Test that protocol version handshake works correctly by modifying options.json on device
./gradlew :internal:test:protocol-version-client:assemble
protocol_client_jar="$repo_dir/internal/test/protocol-version-client/build/libs/protocol-version-client-$stoic_version.jar"

test-attach-via() {
  attach_via="$1"
  package="$2"

  stoic --restart --attach-via "$attach_via" --package "$package" __stoic-noop
  jvmti_port="$(adb forward tcp:0 "localabstract:/stoic/$package/server")"

  set +e
  java -jar "$protocol_client_jar" localhost "$jvmti_port" "$stoic_protocol_version"
  exit_code=$?
  set -e
  if [ $exit_code -ne "$exit_accept" ]; then
    echo "FAIL: $attach_via: Connection failed with protocol version=$stoic_protocol_version: exit_code=$exit_code" >&2
    exit 1
  else
    echo "PASS: $attach_via: Connection succeeded with protocol version=$stoic_protocol_version: exit_code=$exit_code" >&2
  fi

  set +e
  java -jar "$protocol_client_jar" localhost "$jvmti_port" "$invalid_protocol_version"
  exit_code=$?
  set -e
  if [ $exit_code -ne "$exit_reject" ]; then
    echo "FAIL: $attach_via: Connection succeeded with protocol version=$invalid_protocol_version: exit_code=$exit_code" >&2
    exit 1
  else
    echo "PASS: $attach_via: Connection failed with protocol version=$invalid_protocol_version: exit_code=$exit_code" >&2
  fi
}

# Sanity check: we want to make sure we can distinguish between actual
# protocol-version-client errors and server rejections
set +e
java -jar "$protocol_client_jar" 2>/dev/null
exit_code=$?
set -e
if [ $exit_code -ne "$exit_error" ]; then
  echo "Exit code sanity check failed" >&2
fi

test-attach-via jvmti com.squareup.stoic.demoapp.withoutsdk
test-attach-via sdk com.squareup.stoic.demoapp.withsdk
