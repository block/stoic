#!/bin/bash
#set -x
set -euo pipefail

# Ensure we're using the correct stoic binary
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/setup-stoic-path.sh"

export STOIC_CONFIG="$(mktemp -d)"
echo "STOIC_CONFIG=$STOIC_CONFIG"
cd "$STOIC_CONFIG"

# Verify we can create and run a new plugin
stoic plugin --new a-new-plugin
stoic a-new-plugin
