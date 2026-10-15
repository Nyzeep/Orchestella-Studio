#!/usr/bin/env bash
set -euo pipefail

[[ -n "${TICKET26_WORK_ROOT:-}" && -d "$TICKET26_WORK_ROOT" ]] || exit 64
assets="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
shell_bin="$(command -v sh)"
bash_bin="$(command -v bash)"
dirname_bin="$(command -v dirname)"
fixture="$(mktemp -d "$TICKET26_WORK_ROOT/builder-python-XXXXXX")"
mkdir -p "$fixture/bin" "$fixture/work with spaces" "$fixture/repository/scripts"
export BUILDER_PYTHON_CALLS="$fixture/calls"

printf '#!%s\nexec "%s" "$@"\n' "$shell_bin" "$dirname_bin" > "$fixture/bin/dirname"
printf '#!%s\nprintf "python-called\\n" >> "$BUILDER_PYTHON_CALLS"\nexit 97\n' "$shell_bin" > "$fixture/bin/python"
chmod +x "$fixture/bin/dirname" "$fixture/bin/python"

run_entry() {
    local status=0
    PATH="$fixture/bin" TICKET26_WORK_ROOT="$fixture/work with spaces" \
        "$shell_bin" "$assets/scripts/test-initramfs-init-fallback.sh" \
        > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
    [[ "$status" = "$1" ]] || {
        printf 'entry status: expected %s, got %s; evidence=%s\n' "$1" "$status" "$fixture" >&2
        exit 1
    }
}

# A Python 2 executable must never be used for this Python 3 test suite.
run_entry 69
[[ ! -f "$BUILDER_PYTHON_CALLS" ]] || exit 1
grep -q 'python3 is required' "$fixture/stderr"
printf 'PASS python-only PATH fails explicitly without invoking python\n'

rm "$fixture/bin/python"
run_entry 69
grep -q 'python3 is required' "$fixture/stderr"
printf 'PASS empty Python PATH fails explicitly\n'

printf '#!%s\nprintf "%%s\\n" "$1" "$GUEST_RUNTIME_TEST_ROOT" > "$BUILDER_PYTHON_CALLS"\nexit "${BUILDER_PYTHON_EXIT:-0}"\n' "$shell_bin" > "$fixture/bin/python3"
chmod +x "$fixture/bin/python3"
run_entry 0
mapfile -t calls < "$BUILDER_PYTHON_CALLS"
[[ "${calls[0]}" = "$assets/scripts/test-guest-runtime-provision.py" ]]
[[ "${calls[1]}" = "$fixture/work with spaces" ]]
printf 'PASS python3 receives the exact test script and approved work directory\n'

BUILDER_PYTHON_EXIT=23 run_entry 23
printf 'PASS Python test failure status propagates\n'

rm "$BUILDER_PYTHON_CALLS"
status=0
PATH="$fixture/bin" TICKET26_WORK_ROOT="$fixture/missing" \
    "$shell_bin" "$assets/scripts/test-initramfs-init-fallback.sh" \
    > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
[[ "$status" = 64 && ! -f "$BUILDER_PYTHON_CALLS" ]]
printf 'PASS unavailable work root fails before Python invocation\n'

# Execute an unchanged copy of the orchestrator with only its repository boundary
# replaced, so an accidental Gradle launch is recorded without running Gradle.
cp "$assets/scripts/test-local.sh" "$fixture/repository/scripts/test-local.sh"
printf 'GUEST_ASSETS_DIR=%q\nREPOSITORY_DIR=%q\n' "$assets" "$fixture/repository" \
    > "$fixture/repository/scripts/common.sh"
printf '#!%s\nprintf "gradle-called\\n" >> "$BUILDER_PYTHON_CALLS"\nexit 98\n' "$shell_bin" \
    > "$fixture/repository/gradlew"
chmod +x "$fixture/repository/gradlew"
rm "$fixture/bin/python3"
status=0
PATH="$fixture/bin" TICKET26_WORK_ROOT="$fixture/work with spaces" \
    "$bash_bin" "$fixture/repository/scripts/test-local.sh" \
    > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
[[ "$status" = 69 && ! -f "$BUILDER_PYTHON_CALLS" ]]
grep -q 'python3 is required' "$fixture/stderr"
printf 'PASS missing Python fails before JVM tooling or Gradle\n'
printf 'BUILDER_PYTHON_TEST_OK checks=6 evidence=%s\n' "$fixture"
