#!/bin/bash
#set -x
set -euo pipefail

export STOIC_CONFIG="$(mktemp -d)"
echo "STOIC_CONFIG=$STOIC_CONFIG"
cd "$STOIC_CONFIG"
stoic init-config

# TODO: verify that the template is copied over

stoic scratch
