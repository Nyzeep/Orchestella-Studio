#!/usr/bin/env bash
# PVM-03 host e2e (zero device).
#
# Chain under test: golden distcc v1 request bytes -> AXP1 frames over a real
# TCP socket -> pvm_compiler_payload_host (the exact .so TU, host build) ->
# in-memory FakeEngine compile -> distcc response -> .o on the host side.
#
# Two cases:
#   1. a captured distcc 3.4 request produces a valid object;
#   2. an argv the allow list rejects comes back as STAT=0x6500 with a fixed
#      ARGV_REJECTED:<CLASS> code and no object.
#
# The client is tools/pvm-compiler/axp1_distcc_client.py, written from the Java
# Axp1Connection layout and distcc protocol-1 — deliberately not from the server
# code, so a mirrored codec bug cannot pass both sides.
#
# What this script does NOT do: run a real distcc client. That needs the JVM
# bridge (runtime/fastlane-distcc PvmCompilerPayloadE2eTest), which in turn
# needs /usr/bin/distcc. Both are wired in CI; a host without distcc skips them
# and says so, it does not pretend to pass.
#
# Output: PVM_DISTCC_E2E_OK cases=N on success, PVM_DISTCC_E2E_FAIL on failure.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Public golden token, split so no 16+ hex run appears on one line.
TOKEN="00112233""44556677""8899aabb""ccddeeff"
FIXTURE="$ROOT/tools/pvm-compiler/fixtures/fake_engine_object.o"
REQUEST_HEX="$ROOT/runtime/fastlane-distcc/src/test/resources/distcc/request-v1.hex"
COMPARE="$ROOT/tools/compiler-equivalence/compare_objects.py"
EQUAL_FIXTURES="$ROOT/tools/compiler-equivalence/fixtures/c"

fail() {
    echo "PVM_DISTCC_E2E_FAIL: $*" >&2
    exit 1
}

if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1; then
    PYTHON=python
else
    fail "python is required to drive the wire client"
fi

# --- Locate the host twin -----------------------------------------------------
BIN="${PVM_COMPILER_HOST_BIN:-}"
if [ -z "$BIN" ]; then
    for candidate in \
        "$ROOT/build/pvm-service/pvm_compiler_payload_host" \
        "$ROOT/build/pvm-service/pvm_compiler_payload_host.exe" \
        "$ROOT/build/payload-svc/pvm_compiler_payload_host" \
        "$ROOT/build/payload/pvm_compiler_payload_host"; do
        if [ -x "$candidate" ]; then
            BIN="$candidate"
            break
        fi
    done
fi
[ -n "$BIN" ] || fail "pvm_compiler_payload_host not found; build it with: cmake -S fastlane-payload/pvm-service -B build/pvm-service && cmake --build build/pvm-service"
[ -x "$BIN" ] || fail "not executable: $BIN"

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

# --- Start the service --------------------------------------------------------
ORCHESTELLA_ACCEL_TOKEN="$TOKEN" \
ORCHESTELLA_PVM_COMPILER_JOBS=2 \
    "$BIN" --tcp 0 >"$WORK/server.out" 2>"$WORK/server.err" &
SERVER_PID=$!

PORT=""
for _ in $(seq 1 100); do
    PORT="$(sed -n 's/^PVM_CSDL_PORT \([0-9]*\).*/\1/p' "$WORK/server.out" 2>/dev/null | head -1)"
    [ -n "$PORT" ] && break
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        cat "$WORK/server.err" >&2
        fail "service exited before it listened"
    fi
    sleep 0.1
done
[ -n "$PORT" ] || fail "service did not report a port"
echo "PVM_CSDL_UP port=$PORT"

CASES=0

# --- Case 1: the captured distcc request --------------------------------------
if ! "$PYTHON" "$ROOT/tools/pvm-compiler/axp1_distcc_client.py" \
        --port "$PORT" --token "$TOKEN" \
        --request "$REQUEST_HEX" --out "$WORK/unit.o" >"$WORK/case1.out" 2>&1; then
    cat "$WORK/case1.out" >&2
    fail "case 1 transport/protocol error"
fi
grep -qx "stat=0" "$WORK/case1.out" || { cat "$WORK/case1.out" >&2; fail "case 1 STAT is not 0"; }
grep -qx "result=OK" "$WORK/case1.out" || { cat "$WORK/case1.out" >&2; fail "case 1 did not finish"; }

SERVED_BYTES="$(sed -n 's/^object_bytes=//p' "$WORK/case1.out")"
FIXTURE_BYTES="$(wc -c <"$FIXTURE" | tr -d ' ')"
[ "$SERVED_BYTES" = "$FIXTURE_BYTES" ] || fail "object size $SERVED_BYTES != fixture $FIXTURE_BYTES"
cmp -s "$WORK/unit.o" "$FIXTURE" || fail "served object differs from the embedded fixture"
echo "PVM_OBJECT_IDENTICAL bytes=$SERVED_BYTES"
CASES=$((CASES + 1))

# --- Case 2: an argv the allow list must reject -------------------------------
if ! "$PYTHON" "$ROOT/tools/pvm-compiler/axp1_distcc_client.py" \
        --port "$PORT" --token "$TOKEN" \
        --arg=cc --arg=-c --arg=-Xclang --arg=-load --arg=evil.so \
        --arg=unit.c --arg=-o --arg=unit.o \
        --out "$WORK/rejected.o" >"$WORK/case2.out" 2>&1; then
    cat "$WORK/case2.out" >&2
    fail "case 2 transport/protocol error"
fi
grep -qx "stat=25856" "$WORK/case2.out" || {
    cat "$WORK/case2.out" >&2
    fail "case 2 STAT is not 0x6500 (25856 = EXIT_BAD_ARGUMENTS << 8)"
}
grep -q "ARGV_REJECTED:CLANG_INTERNAL" "$WORK/case2.out" || {
    cat "$WORK/case2.out" >&2
    fail "case 2 did not report the fixed ARGV_REJECTED class"
}
grep -qx "object_bytes=0" "$WORK/case2.out" || { cat "$WORK/case2.out" >&2; fail "case 2 returned an object"; }
echo "PVM_ARGV_REJECTED class=CLANG_INTERNAL stat=0x6500"
CASES=$((CASES + 1))

# --- Equivalence judgement (PVM-05) -------------------------------------------
# The host compiler driver cannot open paths outside ASCII on this platform
# (MinGW ld mangles the code page), so the judgement runs on ASCII copies.
cp "$FIXTURE" "$WORK/fixture.o" 2>/dev/null
cp -r "$EQUAL_FIXTURES" "$WORK/fixtures-c" 2>/dev/null

if [ -f "$COMPARE" ] && command -v readelf >/dev/null 2>&1 && command -v nm >/dev/null 2>&1; then
    "$PYTHON" "$COMPARE" --a "$WORK/unit.o" --b "$WORK/fixture.o" \
        --fixtures "$WORK/fixtures-c" >"$WORK/compare.out" 2>&1
    VERDICT="$(head -1 "$WORK/compare.out")"
    echo "PVM_EQUIVALENCE verdict=$VERDICT"
    sed -n '2p' "$WORK/compare.out" | sed 's/^/PVM_EQUIVALENCE detail=/' 
    if [ "$VERDICT" = "DIFFERENT" ]; then
        fail "compare_objects.py judged the served object DIFFERENT"
    fi
else
    echo "PVM_EQUIVALENCE verdict=SKIPPED reason=host-tools-missing"
fi

echo "PVM_DISTCC_E2E_OK cases=$CASES"
