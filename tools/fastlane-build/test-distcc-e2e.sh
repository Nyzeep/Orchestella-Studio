#!/usr/bin/env bash
# AX-06 host-only end-to-end: real distcc -> TCP bridge -> AXP1 -> real distccd.
# This never touches adb, a device, QEMU or vsock. Missing host tools are
# reported as DISTCC_E2E_BLOCKED; set ALLOW_DISTCC_E2E_SKIP=1 to turn that into
# a skip in an environment that intentionally omits distcc.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

for tool in distcc distccd cc; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        if [[ "${ALLOW_DISTCC_E2E_SKIP:-0}" == "1" ]]; then
            echo "DISTCC_E2E_SKIP: missing $tool"
            exit 0
        fi
        echo "DISTCC_E2E_BLOCKED: missing $tool" >&2
        exit 2
    fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
GRADLE_ARGS=(--console=plain --rerun-tasks)
if [[ "${ORCHESTELLA_USE_ALIYUN_MIRROR:-0}" == "1" ]]; then
    GRADLE_ARGS+=("-Porchestella.repositories.useAliyunMirror=true")
fi

./gradlew :runtime:fastlane-distcc:test \
    --tests '*Axp1DistccHostIntegrationTest*' \
    "${GRADLE_ARGS[@]}" \
    "-Pfastlane.distcc.report=$WORK/report.json"

python3 tools/fastlane-distcc/verify_report.py "$WORK/report.json"
echo "DISTCC_E2E_COMPLETE"
