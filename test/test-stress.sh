#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(dirname "$(readlink -f "$0")")"

# Ensure we're using the correct stoic binary
source "$script_dir/setup-stoic-path.sh"

cd $script_dir

# In the past we'd occasionally fail when due to strange pidof behavior -
# sometimes pidof would fail even though the process was still running. This
# happened shortly after the process started, but a previous invocation of
# pidof succeeded. This test runs 100 iterations of that scenario
for i in $(seq 1 100); do
    echo iteration: $i
    adb shell am force-stop com.squareup.stoic.demoapp.withoutsdk && stoic helloworld
    if [ $? -ne 0 ]; then
        echo "Command failed on iteration $i: $?"
        exit 1
    fi
done
