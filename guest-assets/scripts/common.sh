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
