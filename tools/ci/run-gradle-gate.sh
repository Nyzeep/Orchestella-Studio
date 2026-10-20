#!/usr/bin/env bash
# One Gradle invocation for the PR/release gate. Keeps the full console log and
# a short failed-task summary so a JavaExec harness failure names itself.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <gradle task>..." >&2
  exit 2
fi

OUT="${GRADLE_GATE_OUTPUT_DIR:-build/ci}"
mkdir -p "$OUT"
LOG="$OUT/gradle-gate.log"
SUMMARY="$OUT/gradle-gate-summary.txt"

./gradlew "$@" --no-daemon --stacktrace --continue 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}

{
  echo "GRADLE_GATE_EXIT=$status"
  echo "GRADLE_TASKS=$*"
  echo "FAILED_TASKS:"
  if grep -E '^> Task .* FAILED' "$LOG"; then
    :
  else
    echo "  (none reported)"
  fi
} > "$SUMMARY"
cat "$SUMMARY"
exit "$status"
