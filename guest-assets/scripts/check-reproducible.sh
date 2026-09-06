#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

REPRO_ROOT="$(mktemp -d /tmp/droidlayer-guest-assets.XXXXXX)"
cleanup() {
  rm -rf -- "$REPRO_ROOT"
}
trap cleanup EXIT INT TERM

for run in first second; do
  GUEST_ASSETS_WORK_DIR="$REPRO_ROOT/$run/work" \
  GUEST_ASSETS_OUT_DIR="$REPRO_ROOT/$run/out" \
  GUEST_ASSETS_DOWNLOAD_CACHE="${GUEST_ASSETS_DOWNLOAD_CACHE:-$GUEST_ASSETS_DIR/.work/downloads}" \
    "$GUEST_ASSETS_DIR/build.sh"
done

diff --unified \
  "$REPRO_ROOT/first/out/SHA256SUMS" \
  "$REPRO_ROOT/second/out/SHA256SUMS"

while IFS= read -r artifact; do
  cmp --silent \
    "$REPRO_ROOT/first/out/$artifact" \
    "$REPRO_ROOT/second/out/$artifact" || {
      printf 'non-reproducible artifact: %s\n' "$artifact" >&2
      exit 1
    }
done < <(sed 's/^[0-9a-f]\{64\}  //' "$REPRO_ROOT/first/out/SHA256SUMS")

printf 'DROIDLAYER_REPRODUCIBILITY_OK\n'
