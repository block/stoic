#!/bin/bash
set -euo pipefail

COMMIT_SHA="${1:-}"
if [ -z "$COMMIT_SHA" ]; then
  echo "Usage: $0 <commit-sha>"
  exit 1
fi

echo "Fetching build for commit $COMMIT_SHA"
BUILD_RUN_ID=$(gh run list --workflow build --json headSha,databaseId,status,conclusion \
  | jq -r '.[] | select(.headSha=="'$COMMIT_SHA'" and .conclusion=="success") | .databaseId' | head -n1)

if [ -z "$BUILD_RUN_ID" ]; then
  echo "No successful 'build' run found for commit $COMMIT_SHA"
  exit 1
fi

echo "Using build $BUILD_RUN_ID for commit $COMMIT_SHA"
echo "$BUILD_RUN_ID"
