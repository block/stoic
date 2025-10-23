#!/bin/bash
#set -x
set -euo pipefail

# Ensure we're using the correct stoic binary
script_dir="$(dirname "$(readlink -f "$0")")"
source "$script_dir/setup-stoic-path.sh"

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

verify_stderr() {
    expected="$1"
    lineno="$2"
    shift
    shift
    >&2 echo verify_stderr "$@"
    output="$("$@" 3>&1 1>/dev/null 2>&3)"
    return_code="$?"
    if [ "$output" != "$expected" ]; then
        echo "expected: '$expected'"
        echo "actual  : '$output'"
        abort "$lineno" Failed
    elif [ "$return_code" != "0" ]; then
      echo "Failed \"$@\" - returned $return_code"
    fi
}

cd "$script_dir"

# Verify we can install/run the demo app automatically.
# NOTE: When we don't specify a package, we default to the demo app, and the
# demo app implies --start-if-needed
if [ -n "$(adb shell pm list package com.squareup.stoic.demoapp.withoutsdk)" ]; then
    adb uninstall com.squareup.stoic.demoapp.withoutsdk
fi
verify_output 'Hello world []'                                                             $LINENO stoic helloworld
adb shell am force-stop com.squareup.stoic.demoapp.withoutsdk
verify_output 'Hello world []'                                                             $LINENO stoic helloworld

# Verify we can uninstall the demo app (guard against https://github.com/square/stoic/issues/2) 
adb uninstall com.squareup.stoic.demoapp.withoutsdk

# Verify no stderr logs when reinstalling the demo app
verify_stderr '' $LINENO stoic --no-status helloworld

# Verify no stderr logs when starting a previously stopped demo app
adb shell am force-stop com.squareup.stoic.demoapp.withoutsdk
verify_stderr '' $LINENO stoic --no-status helloworld

# Verify no stderr logs when restarting the demo app
verify_stderr '' $LINENO stoic --no-status --restart helloworld
