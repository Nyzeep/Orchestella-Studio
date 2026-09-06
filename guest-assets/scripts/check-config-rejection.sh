#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

REJECTION_ROOT="$(mktemp -d /tmp/droidlayer-guest-assets.negative.XXXXXX)"
cleanup() {
  rm -rf -- "$REJECTION_ROOT"
}
trap cleanup EXIT INT TERM

TEST_FRAGMENT="$REJECTION_ROOT/disable-ext4.fragment"
BUILD_LOG="$REJECTION_ROOT/build.log"
printf '# CONFIG_EXT4_FS is not set\n' > "$TEST_FRAGMENT"

set +e
GUEST_ASSETS_WORK_DIR="$REJECTION_ROOT/work" \
GUEST_ASSETS_OUT_DIR="$REJECTION_ROOT/out" \
GUEST_ASSETS_DOWNLOAD_CACHE="${GUEST_ASSETS_DOWNLOAD_CACHE:-$GUEST_ASSETS_DIR/.work/downloads}" \
GUEST_ASSETS_TEST_KERNEL_FRAGMENT="$TEST_FRAGMENT" \
  "$GUEST_ASSETS_DIR/build.sh" >"$BUILD_LOG" 2>&1
BUILD_EXIT=$?
set -e

if [[ "$BUILD_EXIT" -ne 1 ]]; then
  printf 'expected config-rejection build exit 1, got %s\n' "$BUILD_EXIT" >&2
  cat -- "$BUILD_LOG" >&2
  exit 1
fi
if ! grep --fixed-strings --line-regexp \
    'CONFIG_EXT4_FS: expected y but was n' "$BUILD_LOG" >/dev/null; then
  printf 'config-rejection build did not identify CONFIG_EXT4_FS\n' >&2
  cat -- "$BUILD_LOG" >&2
  exit 1
fi
if [[ -e "$REJECTION_ROOT/out/Image" ]]; then
  printf 'config-rejection build unexpectedly produced Image\n' >&2
  exit 1
fi

printf 'DROIDLAYER_CONFIG_REJECTION_OK symbol=CONFIG_EXT4_FS\n'
