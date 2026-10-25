#!/usr/bin/env bash
# PVM-03 host-side interop: the real Java bridge against the real payload
# service, without distcc and without Gradle.
#
# Chain: golden distcc v1 request bytes -> DistccTcpVsockBridge (loopback TCP,
# the production entry point) -> Axp1DistccChannelFactory (real AXP1 handshake,
# token, capability negotiation) -> pvm_compiler_payload_host -> FakeEngine.
#
# PvmCompilerPayloadE2eTest covers the same path with a real distcc client, but
# it needs /usr/bin/distcc. This driver needs only a JDK, so the bridge path
# can be verified anywhere; it plays the client's part by writing the captured
# request bytes into the bridge's port, which is what distcc would send.
#
# Output: PVM_JAVA_BRIDGE_OK cases=N

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REQUEST_HEX="$ROOT/runtime/fastlane-distcc/src/test/resources/distcc/request-v1.hex"
EXPECTED="$ROOT/tools/pvm-compiler/fixtures/fake_engine_object.o"

fail() {
    echo "PVM_JAVA_BRIDGE_FAIL: $*" >&2
    exit 1
}

command -v javac >/dev/null 2>&1 || fail "javac not found"
command -v java >/dev/null 2>&1 || fail "java not found"

BIN="${PVM_COMPILER_HOST_BIN:-}"
if [ -z "$BIN" ]; then
    for candidate in \
        "$ROOT/build/pvm-service/pvm_compiler_payload_host" \
        "$ROOT/build/pvm-service/pvm_compiler_payload_host.exe" \
        "$ROOT/build/payload-svc/pvm_compiler_payload_host"; do
        if [ -x "$candidate" ]; then
            BIN="$candidate"
            break
        fi
    done
fi
[ -n "$BIN" ] || fail "pvm_compiler_payload_host not found"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# javac needs native paths and a ';' classpath separator on Windows; the JVM
# handles both spellings, so keep POSIX paths for bash and let javac resolve.
SOURCEPATH="$ROOT/runtime/fastlane-distcc/src/main/java:$ROOT/runtime/engine-fastlane/src/main/java:$ROOT/runtime/contract/src/main/java"

javac -encoding UTF-8 --release 17 -nowarn \
    -sourcepath "$SOURCEPATH" \
    -d "$WORK/classes" \
    "$ROOT/runtime/fastlane-distcc/src/test/java/io/github/nyzeep/orchestella/runtime/fastlane/distcc/PvmCompilerBridgeInterop.java" \
    >>"$WORK/javac.log" 2>&1 || { cat "$WORK/javac.log" >&2; fail "javac failed"; }

# The service reports the triple it was built for; the host factory refuses a
# mismatch, so pass the same one the server derives on this host.
TRIPLE="${PVM_COMPILER_TRIPLE:-}"
if [ -z "$TRIPLE" ]; then
    case "$(uname -m)" in
        aarch64|arm64) TRIPLE="aarch64-unknown-linux-android" ;;
        *) TRIPLE="x86_64-unknown-linux-gnu" ;;
    esac
fi

java -Dfile.encoding=UTF-8 -cp "$WORK/classes" \
    io.github.nyzeep.orchestella.runtime.fastlane.distcc.PvmCompilerBridgeInterop \
    "$BIN" "$REQUEST_HEX" "$EXPECTED" "$TRIPLE" 2>&1 | sed 's/^/  /' || fail "interop exchange failed"

echo "PVM_JAVA_BRIDGE_OK"
