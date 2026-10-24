#!/usr/bin/env bash
# PVM-02: assert the in-process compiler performs no exec/fork/vfork.
#
# Runs pvm_compiler_host (which only compiles, never links) under strace and
# inspects the process trace. The first trace line is strace launching the
# traced binary itself and is ignored. Thread creation (CLONE_THREAD clones)
# is allowed; anything that could start a program is a violation.
#
# When strace is unavailable (or on non-Linux hosts), this check prints SKIP
# and exits 0: the mechanism is simply not asserted on that platform, it is
# never assumed.
set -uo pipefail

HOST_BIN="${1:-}"
if [ -z "$HOST_BIN" ] || [ ! -x "$HOST_BIN" ]; then
  echo "FAIL: pvm_compiler_host is missing or not executable: ${HOST_BIN:-<none>}" >&2
  exit 1
fi

if ! command -v strace >/dev/null 2>&1; then
  echo "SKIP no-exec assertion: strace is not available on this platform; the no-exec mechanism is not asserted here"
  exit 0
fi

LOG="$(mktemp "${TMPDIR:-/tmp}/fl-inprocess-noexec.XXXXXX")"
trap 'rm -f "$LOG"' EXIT

# LeakSanitizer uses ptrace itself and aborts fatally under strace; leak
# safety is enforced by the LSan-enabled ctest runs, while this test only
# traces syscalls. ASan instrumentation stays active.
if ! LSAN_OPTIONS=detect_leaks=0 strace -f -e trace=execve,fork,vfork,clone3,clone -o "$LOG" "$HOST_BIN" 3; then
  echo "FAIL: pvm_compiler_host itself failed under strace; trace kept at $LOG" >&2
  trap - EXIT
  exit 1
fi

# Skip line 1 (strace's own launch execve of the traced binary).
VIOLATIONS="$(tail -n +2 "$LOG" | grep -E 'execve|\bfork|vfork|posix_spawn' || true)"
# CLONE_THREAD = ordinary threads; CLONE_UNTRACED = sanitizer stop-the-world
# helpers (never a program start). Anything else, in particular CLONE_VFORK
# without CLONE_THREAD (posix_spawn style), is a violation.
BAD_CLONES="$(tail -n +2 "$LOG" | grep -E 'clone(3)?\(' | grep -vE 'CLONE_THREAD|CLONE_UNTRACED' || true)"
THREAD_CLONES="$(tail -n +2 "$LOG" | grep -cE 'clone(3)?\(.*CLONE_THREAD' || true)"

if [ -n "$VIOLATIONS" ] || [ -n "$BAD_CLONES" ]; then
  echo "FAIL: exec/fork/vfork observed during in-process compilation:" >&2
  printf '%s\n' "$VIOLATIONS" "$BAD_CLONES" | head -10 >&2
  echo "trace kept at $LOG" >&2
  trap - EXIT
  exit 1
fi

echo "NOEXEC_OK exec/fork/vfork=0 thread_clones=${THREAD_CLONES:-0}"
