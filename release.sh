#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"

cd "$script_dir"/kotlin
./gradlew clean :internal:tool:release:run --args "$script_dir"

echo
echo
echo 'Release prepared locally.'
echo 'To actually complete the release, see "Next Steps" above'
echo
echo
