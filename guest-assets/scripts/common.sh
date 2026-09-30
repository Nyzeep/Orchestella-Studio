#!/usr/bin/env bash
set -euo pipefail

GUEST_ASSETS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPOSITORY_DIR="$(cd -- "$GUEST_ASSETS_DIR/.." && pwd -P)"
INPUT_LOCK="$GUEST_ASSETS_DIR/inputs/inputs.lock"

lock_value() {
  local key="$1"
  local value
  value="$(sed -n "s/^${key}=//p" "$INPUT_LOCK")"
  if [[ -z "$value" || "$value" == *$'\n'* ]]; then
    printf 'invalid or missing lock key: %s\n' "$key" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

verify_sha256() {
  local path="$1"
  local expected="$2"
  printf '%s  %s\n' "$expected" "$path" | sha256sum --check --status
}

download_checked() {
  local url="$1"
  local expected="$2"
  local destination="$3"
  local partial="${destination}.partial"

  mkdir -p -- "$(dirname -- "$destination")"
  if [[ -f "$destination" ]] && verify_sha256 "$destination" "$expected"; then
    return 0
  fi

  rm -f -- "$partial"
  curl --fail --location --proto '=https' --tlsv1.2 \
    --retry 3 --retry-delay 2 --output "$partial" "$url"
  if ! verify_sha256 "$partial" "$expected"; then
    rm -f -- "$partial"
    printf 'checksum mismatch for %s\n' "$url" >&2
    return 1
  fi
  mv -f -- "$partial" "$destination"
}

safe_reset_directory() {
  local requested="$1"
  local resolved
  resolved="$(realpath --canonicalize-missing -- "$requested")"
  case "$resolved" in
    "$GUEST_ASSETS_DIR"/.work|"$GUEST_ASSETS_DIR"/.work/*|"$GUEST_ASSETS_DIR"/out|"$GUEST_ASSETS_DIR"/out/*|/tmp/droidlayer-guest-assets.*|/tmp/droidlayer-guest-assets.*/*)
      ;;
    *)
      printf 'refusing to reset unsafe directory: %s\n' "$resolved" >&2
      return 1
      ;;
  esac
  rm -rf -- "$resolved"
  mkdir -p -- "$resolved"
}

verify_ticket26_ticket25_asset_root() {
  local asset_root="$1"
  local checksum_line
  local filename
  local actual_hash
  declare -A manifest_hashes=()

  [[ -n "$asset_root" ]] || {
    printf 'TICKET26_ASSET_ROOT must name the approved read-only Ticket 25 asset directory\n' >&2
    return 64
  }
  [[ -d "$asset_root" && ! -L "$asset_root" ]] || {
    printf 'approved Ticket 25 asset directory is unavailable or not a direct directory\n' >&2
    return 66
  }
  [[ -f "$asset_root/SHA256SUMS" && ! -L "$asset_root/SHA256SUMS" ]] || {
    printf 'approved Ticket 25 SHA256SUMS is unavailable or not a direct file\n' >&2
    return 66
  }

  while IFS= read -r checksum_line || [[ -n "$checksum_line" ]]; do
    if [[ ! "$checksum_line" =~ ^([[:xdigit:]]{64})\ ([\ \*])([^/\\]+)$ ]]; then
      printf 'approved Ticket 25 SHA256SUMS has an unsafe format\n' >&2
      return 66
    fi
    filename="${BASH_REMATCH[3]}"
    if [[ "$filename" == '.' || "$filename" == '..' || "$filename" == *'..'* \
      || -n "${manifest_hashes[$filename]+set}" ]]; then
      printf 'approved Ticket 25 SHA256SUMS has an unsafe or duplicate entry\n' >&2
      return 66
    fi
    manifest_hashes["$filename"]="${BASH_REMATCH[1],,}"
  done < "$asset_root/SHA256SUMS"

  for filename in Image initramfs.cpio.gz; do
    [[ -n "${manifest_hashes[$filename]+set}" \
      && -f "$asset_root/$filename" && ! -L "$asset_root/$filename" ]] || {
      printf 'approved Ticket 25 input is missing or is not a direct regular file: %s\n' "$filename" >&2
      return 66
    }
    actual_hash="$(sha256sum -- "$asset_root/$filename")"
    actual_hash="${actual_hash%% *}"
    if [[ "${actual_hash,,}" != "${manifest_hashes[$filename]}" ]]; then
      printf 'approved Ticket 25 input checksum mismatch: %s\n' "$filename" >&2
      return 66
    fi
  done
}
