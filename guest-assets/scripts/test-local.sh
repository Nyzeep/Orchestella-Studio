#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

"$REPOSITORY_DIR/gradlew" --no-daemon -p "$GUEST_ASSETS_DIR" test

bash -n "$GUEST_ASSETS_DIR/build.sh" \
  "$GUEST_ASSETS_DIR/run-in-builder.sh" \
  "$GUEST_ASSETS_DIR/ci/install-builder-dependencies.sh" \
  "$GUEST_ASSETS_DIR/ci/run.sh" \
  "$GUEST_ASSETS_DIR/scripts/common.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-local.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh"
sh -n "$GUEST_ASSETS_DIR/initramfs/init" \
  "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent"

while IFS= read -r -d '' source_file; do
  case "$source_file" in
    */.gradle/*|*/.work/*|*/build/*|*/out/*) continue ;;
  esac
  if LC_ALL=C grep -Iq . "$source_file" && LC_ALL=C grep -q $'\r' "$source_file"; then
    printf 'tracked guest-assets text must use LF only: %s\n' "$source_file" >&2
    exit 1
  fi
done < <(find "$GUEST_ASSETS_DIR" -type f -print0)

shellcheck --external-sources --source-path=SCRIPTDIR \
  "$GUEST_ASSETS_DIR/build.sh" \
  "$GUEST_ASSETS_DIR/run-in-builder.sh" \
  "$GUEST_ASSETS_DIR/ci/install-builder-dependencies.sh" \
  "$GUEST_ASSETS_DIR/ci/run.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-local.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh" \
  "$GUEST_ASSETS_DIR/initramfs/init"
