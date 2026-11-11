#!/bin/sh
# On-device entry point for the U3 "must verify on device" cases.
#
# Runs inside an arm64 Android shell (adb shell or Termux) with the repo
# directory pushed to the device.  It reuses run_uml_seccomp.sh with the
# arm64 AOSP profile and the downstream ARM64 UML kernel; the x86_64 host
# shim is intentionally NOT used (bionic does not issue those syscalls).
#
# Required environment:
#   UML_KERNEL   path to the ARM64 UML kernel (Raymer fork build)
#   GUEST_ROOT   hostfs root directory containing /init
#   GUEST_IMAGE  ubd0 ext4 image (needed for ubd0-boot)
#   LAUNCHER     optional prebuilt aarch64 seccomp_launch; if unset, this
#                script builds one with cc (Termux: pkg install clang)
#
# Example (host side):
#   adb shell 'cd /data/local/tmp/uml-seccomp && \
#     UML_KERNEL=/data/local/tmp/uml/vmlinux \
#     GUEST_ROOT=/data/local/tmp/uml/rootfs \
#     GUEST_IMAGE=/data/local/tmp/uml/rootfs.img \
#     ./device/run_device.sh --case ncpus-2'

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/.." && pwd)
CASE=""
OUTDIR=${OUTDIR:-/data/local/tmp/uml-seccomp-runs}
TIMEOUT=${TIMEOUT:-60}

while [ $# -gt 0 ]; do
    case "$1" in
        --case) CASE=${2:-}; shift 2 ;;
        --outdir) OUTDIR=${2:-}; shift 2 ;;
        --timeout) TIMEOUT=${2:-}; shift 2 ;;
        *) echo "run_device: unknown argument $1" >&2; exit 2 ;;
    esac
done

[ -n "$CASE" ] || { echo "run_device: --case is required" >&2; exit 2; }
[ "$(uname -m)" = "aarch64" ] || { echo "run_device: requires aarch64" >&2; exit 2; }
[ -n "${UML_KERNEL:-}" ] || { echo "run_device: UML_KERNEL is required" >&2; exit 2; }
[ -n "${GUEST_ROOT:-}" ] || { echo "run_device: GUEST_ROOT is required" >&2; exit 2; }

LAUNCHER_ARG=""
if [ -n "${LAUNCHER:-}" ]; then
    LAUNCHER_ARG="--launcher $LAUNCHER"
elif command -v cc >/dev/null 2>&1; then
    LAUNCHER_ARG=""
else
    echo "run_device: no LAUNCHER and no cc; build seccomp_launch elsewhere" >&2
    exit 2
fi

exec "$ROOT/run_uml_seccomp.sh" \
    --case "$CASE" \
    --profile "$ROOT/profile/generated/aosp-app-android17-arm64.json" \
    --kernel "$UML_KERNEL" \
    --outdir "$OUTDIR/$CASE" \
    --mode aosp \
    --guest-root "$GUEST_ROOT" \
    --guest-image "${GUEST_IMAGE:-}" \
    --timeout "$TIMEOUT" \
    $LAUNCHER_ARG
