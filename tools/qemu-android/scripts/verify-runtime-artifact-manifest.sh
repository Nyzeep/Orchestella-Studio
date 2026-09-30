#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_DIRECTORY="${1:-}"
MANIFEST_FILE_NAME="runtime-artifacts-v1.sha256"

[[ -n "$ARTIFACT_DIRECTORY" ]] || {
  printf 'usage: %s <external-artifact-directory>\n' "$0" >&2
  exit 64
}
[[ -d "$ARTIFACT_DIRECTORY" && ! -L "$ARTIFACT_DIRECTORY" ]] || {
  printf 'runtime artifact directory is unavailable or not a direct directory\n' >&2
  exit 66
}
ARTIFACT_DIRECTORY="$(realpath -- "$ARTIFACT_DIRECTORY")"
MANIFEST="$ARTIFACT_DIRECTORY/$MANIFEST_FILE_NAME"
[[ -f "$MANIFEST" && ! -L "$MANIFEST" ]] || {
  printf 'runtime artifact manifest is unavailable or not a direct file\n' >&2
  exit 66
}

is_safe_relative_path() {
  local relative_path="$1"
  local segment
  [[ -n "$relative_path" && "$relative_path" != /* && "$relative_path" != *\\* ]] || return 1
  IFS=/ read -r -a segments <<< "$relative_path"
  for segment in "${segments[@]}"; do
    [[ -n "$segment" && "$segment" != . && "$segment" != .. ]] || return 1
  done
}

is_direct_regular_artifact() {
  local relative_path="$1"
  local candidate="$ARTIFACT_DIRECTORY"
  local segment
  IFS=/ read -r -a segments <<< "$relative_path"
  for segment in "${segments[@]}"; do
    candidate="$candidate/$segment"
    [[ ! -L "$candidate" ]] || return 1
  done
  [[ -f "$candidate" && "$(realpath -- "$candidate")" == "$ARTIFACT_DIRECTORY/"* ]]
}

declare -A manifest_hashes=()
entry_count=0
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ ! "$line" =~ ^([0-9a-f]{64})\ \ ([^[:cntrl:]]+)$ ]]; then
    printf 'runtime artifact manifest has an unsafe format\n' >&2
    exit 65
  fi
  hash="${BASH_REMATCH[1]}"
  relative_path="${BASH_REMATCH[2]}"
  if ! is_safe_relative_path "$relative_path" \
    || [[ "$relative_path" = "$MANIFEST_FILE_NAME" ]] \
    || [[ -n "${manifest_hashes[$relative_path]+set}" ]] \
    || ! is_direct_regular_artifact "$relative_path"; then
    printf 'runtime artifact manifest has an unsafe or missing entry\n' >&2
    exit 65
  fi
  actual_hash="$(sha256sum -- "$ARTIFACT_DIRECTORY/$relative_path")"
  actual_hash="${actual_hash%% *}"
  [[ "$actual_hash" = "$hash" ]] || {
    printf 'runtime artifact checksum mismatch: %s\n' "$relative_path" >&2
    exit 65
  }
  manifest_hashes["$relative_path"]="$hash"
  entry_count=$((entry_count + 1))
done < "$MANIFEST"

for required_artifact in \
  Image \
  initramfs.cpio.gz \
  QEMU-BUILD-INPUTS \
  QEMU-GPL-SOURCES.md \
  LICENSE.GPL-2.0 \
  NOTICE.QEMU \
  lib/arm64-v8a/libdroidlayer_qemu.so; do
  [[ -n "${manifest_hashes[$required_artifact]+set}" ]] || {
    printf 'runtime artifact manifest is missing required artifact: %s\n' "$required_artifact" >&2
    exit 65
  }
done

printf 'DROIDLAYER_RUNTIME_ARTIFACT_MANIFEST_OK entries=%s qemu=1 kernel=1 initramfs=1 gpl=1\n' \
  "$entry_count"
