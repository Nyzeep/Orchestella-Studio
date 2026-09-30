#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
VERIFY_SCRIPT="$TOOLS_DIRECTORY/scripts/verify-runtime-artifact-manifest.sh"
WORK_ROOT="${TICKET26_WORK_ROOT:-}"
[[ -n "$WORK_ROOT" && -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || {
  printf 'TICKET26_WORK_ROOT must be an approved ordinary directory\n' >&2
  exit 64
}
WORK_ROOT="$(realpath -e -- "$WORK_ROOT")"
WORK_DIRECTORY="$(mktemp -d "$WORK_ROOT/droidlayer-t26-runtime-artifact-manifest-test.XXXXXX")"
ARTIFACT_DIRECTORY="$WORK_DIRECTORY/artifacts"

cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  case "$(realpath -- "$WORK_DIRECTORY")" in
    "$WORK_ROOT"/droidlayer-t26-runtime-artifact-manifest-test.*)
      if [[ "$status" -eq 0 ]]; then
        rm -rf -- "$WORK_DIRECTORY"
      else
        printf 'runtime-artifact failure fixture retained: %s\n' "$WORK_DIRECTORY" >&2
      fi
      ;;
    *)
      printf 'refusing to clean unsafe runtime-artifact test directory\n' >&2
      status=70
      ;;
  esac
  exit "$status"
}
trap cleanup EXIT INT TERM

mkdir -p -- "$ARTIFACT_DIRECTORY/lib/arm64-v8a"
for artifact in \
  Image \
  initramfs.cpio.gz \
  QEMU-BUILD-INPUTS \
  QEMU-GPL-SOURCES.md \
  LICENSE.GPL-2.0 \
  NOTICE.QEMU \
  lib/arm64-v8a/libdroidlayer_qemu.so \
  lib/arm64-v8a/libsynthetic_dependency.so; do
  printf 'synthetic-%s\n' "${artifact//\//-}" > "$ARTIFACT_DIRECTORY/$artifact"
done
(
  cd -- "$ARTIFACT_DIRECTORY"
  sha256sum \
    Image \
    initramfs.cpio.gz \
    QEMU-BUILD-INPUTS \
    QEMU-GPL-SOURCES.md \
    LICENSE.GPL-2.0 \
    NOTICE.QEMU \
    lib/arm64-v8a/libdroidlayer_qemu.so \
    lib/arm64-v8a/libsynthetic_dependency.so > runtime-artifacts-v1.sha256
)
"$VERIFY_SCRIPT" "$ARTIFACT_DIRECTORY" >/dev/null

rejected=0
for artifact in \
  lib/arm64-v8a/libdroidlayer_qemu.so \
  Image \
  initramfs.cpio.gz \
  lib/arm64-v8a/libsynthetic_dependency.so; do
  printf '%s\n' 'altered-artifact' >> "$ARTIFACT_DIRECTORY/$artifact"
  if "$VERIFY_SCRIPT" "$ARTIFACT_DIRECTORY" >/dev/null 2>&1; then
    printf 'runtime artifact verifier accepted tampered input: %s\n' "$artifact" >&2
    exit 1
  fi
  printf 'synthetic-%s\n' "${artifact//\//-}" > "$ARTIFACT_DIRECTORY/$artifact"
  "$VERIFY_SCRIPT" "$ARTIFACT_DIRECTORY" >/dev/null
  rejected=$((rejected + 1))
done

printf 'DROIDLAYER_RUNTIME_ARTIFACT_MANIFEST_TEST_OK tamper_rejected=%s\n' "$rejected"
