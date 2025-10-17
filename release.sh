#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"

cd "$script_dir"
./gradlew --console=plain clean :internal:tool:release:run --args "$script_dir"
