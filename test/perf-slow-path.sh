#!/bin/bash
#set -x
set -euo pipefail

stoic_dir="$(dirname "$(readlink -f "$0")")/../"
source "$stoic_dir/prebuilt/script/android_serial.sh"

# Pick a device
_android_serial

# We cd so that we can run ./stoic in a subshell without any env vars
cd "$stoic_dir/build/distributions/bin"

# warm
./stoic helloworld >/dev/null

echo "From laptop:"
time sh << 'EOF'
for i in {0..9}; do
  ./stoic --restart helloworld
done
EOF
