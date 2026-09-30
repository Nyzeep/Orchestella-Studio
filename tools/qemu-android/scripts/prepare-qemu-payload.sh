#!/usr/bin/env bash
# Ticket 26 CI: QEMU payload preparation (in-container adapter).
#
# Runs inside the digest-pinned builder container. It stages the NDK from the
# mounted zip, verifies the pinned QEMU archive against the lock, and delegates
# the actual native build to tools/qemu-android/ci/build-in-container.sh (the
# canonical build entrypoint; see QEMU-BUILD-INPUTS). The QEMU main ELF is then
# staged under its APK entry name.
#
# Input contract (fail-closed on any absence):
#   /inputs/qemu-11.1.1.tar.xz        pinned QEMU release archive
#   /inputs/ndk.zip                   pinned Android NDK archive
#   /inputs/qemu-android-deps-20260924/  pinned dependency archives
#   /workspace                        repository (read-only)
#   /pkgbuild                         tools/qemu-android (read-only)
#   /io                               output directory (writable)
# Output:
#   /io/libdroidlayer_qemu.so         QEMU main ELF under the APK entry name
#   /io/PAYLOAD-QEMU.sha256           digest record of the staged product
#
# This script performs no network fetches of its own (the delegate's endpoint
# discipline: apt ONLY the pinned Ubuntu snapshot; meson --wrap-mode=nodownload).
set -euo pipefail

LOCK=/pkgbuild/qemu-jni.lock
BUILD_ENTRYPOINT=/pkgbuild/ci/build-in-container.sh
QEMU_CANONICAL=/inputs/qemu-11.1.1.tar.xz

log() { printf '[prepare-payload %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf 'PREPARE_PAYLOAD_DIE: %s\n' "$*" >&2; exit 1; }

verify_prebuild() {
  CI_REPO="${CI_REPO:-/workspace}" bash "$(dirname -- "${BASH_SOURCE[0]}")/verify-native-prebuild.sh"
}

select_product() {
  local output_root="$1" run="$2"
  [[ "$run" =~ ^[A-Za-z0-9_-]+$ ]] || die 'invalid QOS_RUN'
  shopt -s nullglob
  local products=("$output_root"/qemu-android-build1-"$run"-*/qemu-system-aarch64)
  [[ "${#products[@]}" -eq 1 && -f "${products[0]}" && ! -L "${products[0]}" ]] \
    || die "expected exactly one qemu-system-aarch64 product, found ${#products[@]}"
  printf '%s\n' "${products[0]}"
}

case "${1:-}" in
  --select-product) select_product "${CI_OUTPUT_ROOT:?CI_OUTPUT_ROOT required}" "${QOS_RUN:-payload}"; exit 0 ;;
  --verify-only) bash "$(dirname -- "${BASH_SOURCE[0]}")/verify-ci-inputs.sh" --verify-only; exit $? ;;
  --verify-build-record) python3 "$(dirname -- "${BASH_SOURCE[0]}")/verify-build-record.py" --repo "${CI_REPO:?CI_REPO required}"; exit $? ;;
  --verify-prebuild) [[ -n "${CI_REPO:-}" ]] || die 'CI_REPO required'; verify_prebuild; exit $? ;;
  '') ;;
  *) die 'unknown mode' ;;
esac

# --- fail-closed input checks -------------------------------------------------
verify_prebuild || die "current full lock and build-input record are not ready; historical payload records must remain unchanged"
[[ -f "$LOCK" ]] || die "lock missing at $LOCK"
[[ -f "$BUILD_ENTRYPOINT" ]] || die "build entrypoint missing at $BUILD_ENTRYPOINT"
[[ -f "$QEMU_CANONICAL" ]] || die "pinned QEMU archive missing"
[[ -d /workspace/tools/qemu-android/android-shim ]] || die "workspace shim sources missing"
[[ -d /io && ! -L /io ]] || die "/io must be an ordinary writable directory"
CI_REPO=/workspace CI_INPUT_ROOT=/inputs CI_LOCK="$LOCK" \
  bash /pkgbuild/scripts/verify-ci-inputs.sh --extract-qemu \
  || die "pinned input verification or QEMU source extraction failed"
log "pinned QEMU, NDK, dependency and meson wheel bytes verified"

# --- NDK staging -------------------------------------------------------------
[[ -d /ndk/android-ndk-r28c ]] || {
  mkdir -p /ndk
  unzip -q /inputs/ndk.zip -d /ndk
}
[[ -d /ndk/android-ndk-r28c ]] || die "NDK extraction did not produce /ndk/android-ndk-r28c"

# --- canonical build ---------------------------------------------------------
export QOS_RUN="${QOS_RUN:-payload}"
[[ "$QOS_RUN" =~ ^[A-Za-z0-9_-]+$ ]] || die "invalid QOS_RUN"
bash "$BUILD_ENTRYPOINT" all

# --- stage the product under its APK entry name ------------------------------
product="$(select_product /io/out "$QOS_RUN")"
cp -- "$product" /io/libdroidlayer_qemu.so
( cd /io && sha256sum libdroidlayer_qemu.so ) > /io/PAYLOAD-QEMU.sha256
log "payload product staged: $(cat /io/PAYLOAD-QEMU.sha256)"
