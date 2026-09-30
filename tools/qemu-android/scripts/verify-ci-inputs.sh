#!/usr/bin/env bash
set -euo pipefail

inputs="${CI_INPUT_ROOT:-/inputs}"
deps="${CI_DEPS_DIR:-$inputs/qemu-android-deps-20260924}"
repo="${CI_REPO:-/workspace}"
lock="${CI_LOCK:-$repo/tools/qemu-android/qemu-jni.lock}"
source_root="${CI_QEMU_SOURCE_ROOT:-/qemu-src-ro}"
die() { printf 'CI_INPUT_FAIL %s\n' "$*" >&2; exit 1; }
[[ -f "$lock" && ! -L "$lock" && -d "$inputs" && ! -L "$inputs" && -d "$deps" && ! -L "$deps" ]] || die 'missing lock or input directory'

value() {
  local line
  line="$(grep -E "^$1=" "$lock" || true)"
  [[ -n "$line" && "$line" != *$'\n'* ]] || die "missing or duplicate lock key: $1"
  line="${line#*=}"
  [[ -n "$line" && "$line" != absent ]] || die "empty lock value: $1"
  printf '%s' "$line"
}
verify() {
  local name="$1" key="$2" file="$3" sha
  sha="$(value "$key")"
  [[ "$sha" =~ ^[[:xdigit:]]{64}$ && -f "$file" && ! -L "$file" ]] || die "missing or invalid $name"
  printf '%s  %s\n' "$sha" "$file" | sha256sum --check --status || die "$name digest mismatch"
}

archive="$inputs/qemu-11.1.1.tar.xz"
verify 'QEMU archive' qemu_archive_sha256 "$archive"
verify 'NDK archive' android_ndk_archive_sha256 "$inputs/ndk.zip"
ndk_sha1="$(value android_ndk_metadata_sha1)"
[[ "$ndk_sha1" =~ ^[[:xdigit:]]{40}$ ]] || die 'invalid NDK metadata SHA-1'
printf '%s  %s\n' "$ndk_sha1" "$inputs/ndk.zip" | sha1sum --check --status || die 'NDK metadata SHA-1 mismatch'
verify glib glib_source_sha256 "$deps/glib-2.80.5.tar.xz"
verify pixman pixman_source_sha256 "$deps/pixman-0.42.2.tar.gz"
verify zlib zlib_source_sha256 "$deps/zlib-1.3.2.tar.gz"
verify pcre2 pcre2_source_sha256 "$deps/pcre2-10.48.tar.gz"
verify libffi libffi_source_sha256 "$deps/libffi-3.8.0.tar.gz"
verify libiconv libiconv_source_sha256 "$deps/libiconv-1.19.tar.gz"
verify proxy-libintl proxy_libintl_git_archive_sha256 "$deps/proxy-libintl-0.4.tar"
verify dtc dtc_git_archive_sha256 "$deps/dtc-b6910bec.tar"

member="$(value meson_tool_member)"
[[ "$member" == qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl ]] || die 'unexpected meson wheel member'
wheel_sha="$(value meson_tool_sha256)"
[[ "$wheel_sha" =~ ^[[:xdigit:]]{64}$ ]] || die 'invalid meson wheel digest'
case "${1:-}" in
  --verify-only|--extract-qemu) ;;
  *) die 'unknown mode' ;;
esac
wheel_actual="$(tar -xOf "$archive" "$member" | sha256sum | cut -d' ' -f1)" || die 'missing meson wheel in QEMU archive'
[[ "$wheel_actual" == "$wheel_sha" ]] || die 'meson wheel digest mismatch'
if [[ "$1" == --verify-only ]]; then
  printf 'CI_INPUTS_VERIFIED deps=8 qemu=1 ndk=1 wheel=1 mode=read-only\n'
  exit 0
fi
[[ ! -e "$source_root" && ! -L "$source_root" ]] || die 'QEMU extraction target exists'
mkdir -p "$source_root"
tar -xf "$archive" -C "$source_root" --exclude='qemu-11.1.1/roms/edk2/EmulatorPkg/Unix/Host/X11IncludeHack' || die 'QEMU extraction failed'
wheel="$source_root/$member"
verify 'meson wheel' meson_tool_sha256 "$wheel"
[[ -f "$source_root/qemu-11.1.1/configure" ]] || die 'QEMU source missing configure'
printf 'CI_INPUTS_VERIFIED deps=8 qemu=1 ndk=1 wheel=1 mode=extracted\n'
