#!/bin/bash
set -euxo pipefail

script_dir="$(dirname "$(readlink -f "$0")")"

cd $script_dir/..
shellcheck -x build.sh
shellcheck -x release.sh
