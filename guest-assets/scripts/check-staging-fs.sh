#!/usr/bin/env bash
# Thin wrapper around staging_preserves_modes for out-of-repo rootfs recipes:
# run it on the staging directory right before "mkfs.ext4 -F -d <staging>",
# because mkfs records the mode bits stat reports and never repairs them.
set -uo pipefail

# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"

usage() {
  cat <<'USAGE'
usage: check-staging-fs.sh <dir>...

Verify that staging directories can store Unix mode bits before they are packed
into an archive or copied into an ext4 image with "mkfs.ext4 -d".

exit 0  every directory preserves mode bits
exit 1  at least one directory drops them (for example WSL DrvFs mounted
        without metadata, where the Windows ACL is projected as 0777 and chmod
        only toggles the read-only attribute); staging must live on a native
        Linux filesystem such as /root/staging inside WSL, never under /mnt/*
exit 2  the probe could not decide (path is not a directory, or mkdir/chmod/stat
        failed), or no directory was given
USAGE
}

if [[ $# -eq 0 ]]; then
  usage >&2
  exit 64
fi

status=0
for dir in "$@"; do
  case "$dir" in
    -h|--help) usage; exit 0 ;;
  esac
  probe_status=0
  staging_preserves_modes "$dir" || probe_status=$?
  case "$probe_status" in
    0)
      printf 'ok: staging filesystem preserves Unix mode bits: %s\n' "$dir"
      ;;
    1)
      printf 'FAIL: staging filesystem drops Unix mode bits (WSL DrvFs without metadata?): %s\n' \
        "$dir" >&2
      status=1
      ;;
    *)
      printf 'UNDECIDED: cannot verify Unix mode support: %s\n' "$dir" >&2
      [[ "$status" -eq 1 ]] || status=2
      ;;
  esac
done

exit "$status"
