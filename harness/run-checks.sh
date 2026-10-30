#!/usr/bin/env bash
# Run every offline harness check. Logs go to /tmp/msf-ai-<check>.log. Exits non-zero on the first failure.
set -euo pipefail

HARNESS="$(cd "$(dirname "$0")" && pwd)"

for check in check-tools check-mule check-connectors check-render check-history check-raw-log check-auto check-attach check-secrets check-html check-updates; do
  if ! "$HARNESS/run.sh" "$check" > "/tmp/msf-ai-$check.log" 2>&1; then
    grep -E '^FAIL' "/tmp/msf-ai-$check.log" >&2 || tail -20 "/tmp/msf-ai-$check.log" >&2
    echo "$check failed. Full log: /tmp/msf-ai-$check.log" >&2
    exit 1
  fi
  echo "  $check: $(grep -c '^PASS' "/tmp/msf-ai-$check.log") passed"
done
