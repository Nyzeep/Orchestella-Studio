#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/scripts/common.sh"

ACTION="${1:-verify}"
case "$ACTION" in build|test|negative-config|reproducibility|smoke|verify) ;; *)
  printf 'usage: %s [build|test|negative-config|reproducibility|smoke|verify]\n' "$0" >&2
  exit 64
esac

if [[ -n "${CONTAINER_RUNTIME:-}" ]]; then
  RUNTIME="$CONTAINER_RUNTIME"
elif command -v docker >/dev/null 2>&1; then
  RUNTIME=docker
elif command -v podman >/dev/null 2>&1; then
  RUNTIME=podman
else
  printf 'Docker or Podman is required to run the pinned Linux builder\n' >&2
  exit 69
fi

case "$(basename -- "$RUNTIME")" in docker|podman|docker.exe|podman.exe) ;; *)
  printf 'CONTAINER_RUNTIME must be Docker or Podman; got: %s\n' "$RUNTIME" >&2
  exit 64
esac

BUILDER_IMAGE="$(lock_value BUILDER_IMAGE)"
BUILDER_IMAGE_PLATFORM="$(lock_value BUILDER_IMAGE_PLATFORM)"
exec "$RUNTIME" run --rm --user 0:0 --platform "$BUILDER_IMAGE_PLATFORM" \
  --env "GUEST_ASSETS_ACTION=$ACTION" \
  --env GUEST_ASSETS_WORK_DIR=/tmp/droidlayer-guest-assets.build \
  --env GUEST_ASSETS_DOWNLOAD_CACHE=/tmp/droidlayer-guest-assets.downloads \
  --env GUEST_ASSETS_SMOKE_WORK=/tmp/droidlayer-guest-assets.smoke \
  --volume "$REPOSITORY_DIR:/workspace" \
  --workdir /workspace \
  "$BUILDER_IMAGE" \
  bash -lc './guest-assets/ci/install-builder-dependencies.sh && ./guest-assets/ci/run.sh'
