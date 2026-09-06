#!/usr/bin/env bash
set -euo pipefail

GUEST_ASSETS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
ACTION="${GUEST_ASSETS_ACTION:-verify}"
export GUEST_ASSETS_WORK_DIR="${GUEST_ASSETS_WORK_DIR:-/tmp/droidlayer-guest-assets.build}"
export GUEST_ASSETS_DOWNLOAD_CACHE="${GUEST_ASSETS_DOWNLOAD_CACHE:-/tmp/droidlayer-guest-assets.downloads}"
export GUEST_ASSETS_SMOKE_WORK="${GUEST_ASSETS_SMOKE_WORK:-/tmp/droidlayer-guest-assets.smoke}"

case "$ACTION" in
  build)
    "$GUEST_ASSETS_DIR/build.sh"
    ;;
  test)
    "$GUEST_ASSETS_DIR/scripts/test-local.sh"
    ;;
  negative-config)
    "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh"
    ;;
  reproducibility)
    "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh"
    ;;
  smoke)
    "$GUEST_ASSETS_DIR/build.sh"
    "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh"
    ;;
  verify)
    "$GUEST_ASSETS_DIR/scripts/test-local.sh"
    "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh"
    "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh"
    "$GUEST_ASSETS_DIR/build.sh"
    "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh"
    ;;
  *)
    printf 'unsupported guest-assets action: %s\n' "$ACTION" >&2
    exit 64
    ;;
esac
