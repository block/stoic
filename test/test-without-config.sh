#!/bin/bash
#set -x
set -euo pipefail

# Ensure we're using the correct stoic binary
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/setup-stoic-path.sh"

export STOIC_CONFIG="$(mktemp -d)"
rmdir "$STOIC_CONFIG"

echo "STOIC_CONFIG=$STOIC_CONFIG"
stoic helloworld
stoic --restart helloworld
