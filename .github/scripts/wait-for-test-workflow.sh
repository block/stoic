#!/bin/bash
set -euo pipefail

COMMIT_SHA="${1:-}"
if [ -z "$COMMIT_SHA" ]; then
  echo "Usage: $0 <commit-sha>"
  exit 1
fi

echo "Waiting for test workflow to complete for commit $COMMIT_SHA" >&2

# Wait up to 30 minutes for the test workflow to complete
timeout=1800
elapsed=0
while [ $elapsed -lt $timeout ]; do
  TEST_STATUS=$(gh run list --workflow test --json headSha,conclusion,status \
    | jq -r '.[] | select(.headSha=="'$COMMIT_SHA'") | "\(.status):\(.conclusion)"' | head -n1)

  if [ -z "$TEST_STATUS" ]; then
    echo "No 'test' run found yet for commit $COMMIT_SHA, waiting..." >&2
    sleep 10
    elapsed=$((elapsed + 10))
    continue
  fi

  status="${TEST_STATUS%%:*}"
  conclusion="${TEST_STATUS##*:}"

  if [ "$status" = "completed" ]; then
    if [ "$conclusion" = "success" ]; then
      echo "✅ Test workflow passed for commit $COMMIT_SHA" >&2
      exit 0
    else
      echo "❌ Test workflow failed for commit $COMMIT_SHA (conclusion: $conclusion)" >&2
      exit 1
    fi
  else
    echo "Test workflow is still running (status: $status), waiting..." >&2
    sleep 10
    elapsed=$((elapsed + 10))
  fi
done

echo "❌ Timeout waiting for test workflow to complete" >&2
exit 1
