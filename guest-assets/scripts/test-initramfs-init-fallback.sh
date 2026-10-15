#!/bin/sh
set -eu

[ -n "${TICKET26_WORK_ROOT:-}" ] && [ -d "$TICKET26_WORK_ROOT" ] || exit 64
assets="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
export GUEST_RUNTIME_TEST_ROOT="$TICKET26_WORK_ROOT"
command -v python3 >/dev/null 2>&1 || {
    printf 'python3 is required for guest runtime tests\n' >&2
    exit 69
}
exec python3 "$assets/scripts/test-guest-runtime-provision.py"
