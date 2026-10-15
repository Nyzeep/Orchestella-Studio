#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

if [[ -z "${TICKET26_WORK_ROOT:-}" || ! -d "$TICKET26_WORK_ROOT" ]]; then
  export TICKET26_WORK_ROOT="${GUEST_ASSETS_WORK_DIR:-/tmp}/ticket26-work"
  mkdir -p "$TICKET26_WORK_ROOT"
fi

command -v python3 >/dev/null 2>&1 || {
  printf 'python3 is required for guest runtime tests\n' >&2
  exit 69
}

bash "$GUEST_ASSETS_DIR/scripts/test-builder-python.sh"

if [[ "${1:-}" == "--init-fallback-only" ]]; then
  TICKET26_WORK_ROOT="$TICKET26_WORK_ROOT" sh "$GUEST_ASSETS_DIR/scripts/test-initramfs-init-fallback.sh"
  exit 0
fi

"$REPOSITORY_DIR/gradlew" --no-daemon -p "$GUEST_ASSETS_DIR" test

TICKET26_WORK_ROOT="$TICKET26_WORK_ROOT" sh "$GUEST_ASSETS_DIR/scripts/test-initramfs-init-fallback.sh"

bash -n "$GUEST_ASSETS_DIR/build.sh" \
  "$GUEST_ASSETS_DIR/run-in-builder.sh" \
  "$GUEST_ASSETS_DIR/ci/install-builder-dependencies.sh" \
  "$GUEST_ASSETS_DIR/ci/run.sh" \
  "$GUEST_ASSETS_DIR/scripts/common.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-local.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-initramfs-init-fallback.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-builder-python.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-official-alpine-provisioning-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-official-alpine-restart-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-terminal-relay-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-bridge-control-terminal-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioned-bridge-terminal-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-restart-smoke.sh" \
  "$GUEST_ASSETS_DIR/scripts/orchestella-desktop-setup.sh" \
  "$GUEST_ASSETS_DIR/scripts/orchestella-desktop-launch.sh" \
  "$GUEST_ASSETS_DIR/smoke/desktop-smoke.sh"
sh -n "$GUEST_ASSETS_DIR/initramfs/init" \
  "$GUEST_ASSETS_DIR/initramfs/openrc/orchestella-guest-agent" \
  "$GUEST_ASSETS_DIR/scripts/desktop/labwc/autostart" \
  "$GUEST_ASSETS_DIR/shell/orchestella-prompt.sh" \
  "$GUEST_ASSETS_DIR/shell/aliases.sh" \
  "$GUEST_ASSETS_DIR/shell/00-orchestella-env.sh" \
  "$GUEST_ASSETS_DIR/shell/10-orchestella-graphics.sh"


while IFS= read -r -d '' source_file; do
  case "$source_file" in
    */.gradle/*|*/.work/*|*/build/*|*/out/*) continue ;;
  esac
  if LC_ALL=C grep -Iq . "$source_file" && LC_ALL=C grep -q $'\r' "$source_file"; then
    printf 'tracked guest-assets text must use LF only: %s\n' "$source_file" >&2
    exit 1
  fi
done < <(find "$GUEST_ASSETS_DIR" -type f -print0)

shellcheck --severity=warning --external-sources --source-path=SCRIPTDIR \
  "$GUEST_ASSETS_DIR/build.sh" \
  "$GUEST_ASSETS_DIR/run-in-builder.sh" \
  "$GUEST_ASSETS_DIR/ci/install-builder-dependencies.sh" \
  "$GUEST_ASSETS_DIR/ci/run.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-config-rejection.sh" \
  "$GUEST_ASSETS_DIR/scripts/check-reproducible.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-local.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-initramfs-init-fallback.sh" \
  "$GUEST_ASSETS_DIR/scripts/test-builder-python.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-official-alpine-provisioning-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-official-alpine-restart-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-terminal-relay-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-bridge-control-terminal-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioned-bridge-terminal-smoke.sh" \
  "$GUEST_ASSETS_DIR/smoke/qemu-provisioning-restart-smoke.sh" \
  "$GUEST_ASSETS_DIR/scripts/orchestella-desktop-setup.sh" \
  "$GUEST_ASSETS_DIR/scripts/orchestella-desktop-launch.sh" \
  "$GUEST_ASSETS_DIR/smoke/desktop-smoke.sh" \
  "$GUEST_ASSETS_DIR/scripts/desktop/labwc/autostart" \
  "$GUEST_ASSETS_DIR/initramfs/init" \
  "$GUEST_ASSETS_DIR/shell/orchestella-prompt.sh" \
  "$GUEST_ASSETS_DIR/shell/aliases.sh" \
  "$GUEST_ASSETS_DIR/shell/00-orchestella-env.sh" \
  "$GUEST_ASSETS_DIR/shell/10-orchestella-graphics.sh"
