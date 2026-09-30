#!/usr/bin/env bash
set -euo pipefail

repo="${CI_REPO:?CI_REPO must identify the checked-out repository}"
inputs="${CI_INPUT_ROOT:?CI_INPUT_ROOT must identify the input directory}"
lock="$repo/tools/qemu-android/qemu-jni.lock"
[[ -f "$lock" && ! -L "$lock" && -d "$inputs" && ! -L "$inputs" ]] || { printf 'CI_FETCH_FAIL missing lock or input root\n' >&2; exit 1; }

value() {
  local found
  found="$(grep -E "^$1=" "$lock" || true)"
  [[ -n "$found" && "$found" != *$'\n'* ]] || { printf 'CI_FETCH_FAIL missing or duplicate pin: %s\n' "$1" >&2; exit 1; }
  found="${found#*=}"
  [[ -n "$found" && "$found" != absent ]] || { printf 'CI_FETCH_FAIL empty pin: %s\n' "$1" >&2; exit 1; }
  printf '%s' "$found"
}

fetch() {
  local key="$1" hashkey="$2" target="$3" url sha
  url="$(value "$key")"; sha="$(value "$hashkey")"
  [[ "$url" == https://* && "$sha" =~ ^[[:xdigit:]]{64}$ ]] || { printf 'CI_FETCH_FAIL invalid pin: %s\n' "$key" >&2; exit 1; }
  [[ ! -e "$target" && ! -L "$target" && ! -e "$target.partial" && ! -L "$target.partial" ]] || { printf 'CI_FETCH_FAIL existing output: %s\n' "$target" >&2; exit 1; }
  curl --proto '=https' --tlsv1.2 --fail --retry 0 --location -o "$target.partial" "$url"
  printf '%s  %s\n' "$sha" "$target.partial" | sha256sum --check --status || { printf 'CI_FETCH_FAIL checksum: %s\n' "$key" >&2; exit 1; }
  mv -- "$target.partial" "$target"
}

copy_vendored() {
  local pathkey="$1" hashkey="$2" target="$3" relative sha source
  relative="$(value "$pathkey")"; sha="$(value "$hashkey")"
  [[ "$relative" == tools/qemu-android/vendor/* && "$relative" != *..* && "$sha" =~ ^[[:xdigit:]]{64}$ ]] || { printf 'CI_FETCH_FAIL vendored pin: %s\n' "$pathkey" >&2; exit 1; }
  source="$repo/$relative"
  [[ -f "$source" && ! -L "$source" && ! -e "$target" && ! -L "$target" ]] || { printf 'CI_FETCH_FAIL vendored source or target: %s\n' "$pathkey" >&2; exit 1; }
  printf '%s  %s\n' "$sha" "$source" | sha256sum --check --status || { printf 'CI_FETCH_FAIL vendored checksum: %s\n' "$pathkey" >&2; exit 1; }
  cp -- "$source" "$target"
  printf '%s  %s\n' "$sha" "$target" | sha256sum --check --status || { printf 'CI_FETCH_FAIL vendored copy: %s\n' "$pathkey" >&2; exit 1; }
}

native_mode() {
  mkdir -p "$inputs/qemu-android-deps-20260924"
  deps="$inputs/qemu-android-deps-20260924"
  fetch qemu_archive_url qemu_archive_sha256 "$inputs/qemu-11.1.1.tar.xz"
  fetch android_ndk_archive_url android_ndk_archive_sha256 "$inputs/ndk.zip"
  fetch glib_source_url glib_source_sha256 "$deps/glib-2.80.5.tar.xz"
  fetch pixman_source_url pixman_source_sha256 "$deps/pixman-0.42.2.tar.gz"
  fetch zlib_source_url zlib_source_sha256 "$deps/zlib-1.3.2.tar.gz"
  fetch pcre2_source_url pcre2_source_sha256 "$deps/pcre2-10.48.tar.gz"
  fetch libffi_source_url libffi_source_sha256 "$deps/libffi-3.8.0.tar.gz"
  fetch libiconv_source_url libiconv_source_sha256 "$deps/libiconv-1.19.tar.gz"
  copy_vendored proxy_libintl_vendored_path proxy_libintl_git_archive_sha256 "$deps/proxy-libintl-0.4.tar"
  copy_vendored dtc_vendored_path dtc_git_archive_sha256 "$deps/dtc-b6910bec.tar"
  printf 'CI_FETCH_INPUTS_OK deps=8 qemu=1 ndk=1\n'
}

# Android APK build provisioning (section-7 lock-sync batch, user ruling
# 2026-09-29). Approved endpoints ONLY: dl.google.com (android repository),
# services.gradle.org plus its approved redirect chain (downloads.gradle.org,
# github.com/gradle/gradle-distributions) for the pinned Gradle distribution,
# and snapshot.ubuntu.com fixed path for the pinned JDK 41-deb closure. Any
# other host, a redirect on a non-approved-chain URL, or any checksum/size
# mismatch fails closed. Existing verified targets are reused after re-verify
# (REUSED_VERIFIED); a .partial residue is never accepted.
fetch_checked() {
  local label="$1" url="$2" target="$3" expected_size="$4" expected_sha256="$5" allow_redirects="$6" actual_size
  [[ "$url" == https://* && "$expected_sha256" =~ ^[[:xdigit:]]{64}$ && "$expected_size" =~ ^[0-9]+$ ]] \
    || { printf 'CI_FETCH_FAIL invalid android pin: %s\n' "$label" >&2; exit 1; }
  if [[ -e "$target" && ! -L "$target" ]]; then
    actual_size="$(stat -c '%s' "$target")"
    [[ "$actual_size" == "$expected_size" ]] || { printf 'CI_FETCH_FAIL reused size: %s\n' "$label" >&2; exit 1; }
    printf '%s  %s\n' "$expected_sha256" "$target" | sha256sum --check --status \
      || { printf 'CI_FETCH_FAIL reused checksum: %s\n' "$label" >&2; exit 1; }
    printf 'CI_FETCH_ANDROID_REUSED_VERIFIED %s\n' "$label"
    return 0
  fi
  [[ ! -e "$target" && ! -L "$target" && ! -e "$target.partial" && ! -L "$target.partial" ]] \
    || { printf 'CI_FETCH_FAIL existing output: %s\n' "$target" >&2; exit 1; }
  if [[ "$allow_redirects" == redirects-ok ]]; then
    curl --proto '=https' --tlsv1.2 --fail --retry 0 --location --max-redirs 8 -o "$target.partial" "$url"
  else
    curl --proto '=https' --tlsv1.2 --fail --retry 0 --max-redirs 0 -o "$target.partial" "$url"
  fi
  actual_size="$(stat -c '%s' "$target.partial")"
  [[ "$actual_size" == "$expected_size" ]] || { printf 'CI_FETCH_FAIL size: %s\n' "$label" >&2; exit 1; }
  printf '%s  %s\n' "$expected_sha256" "$target.partial" | sha256sum --check --status \
    || { printf 'CI_FETCH_FAIL checksum: %s\n' "$label" >&2; exit 1; }
  mv -- "$target.partial" "$target"
  printf 'CI_FETCH_ANDROID_FETCHED %s\n' "$label"
}

android_mode() {
  local debs="$inputs/jdk-debs"
  mkdir -p "$debs"
  # The 41-deb JDK closure is pinned by the in-repo manifest's digest.
  local manifest_rel manifest
  manifest_rel="$(value ci_jdk_closure_manifest_path)"
  manifest="$repo/$manifest_rel"
  [[ "$manifest_rel" == tools/qemu-android/ci/* && -f "$manifest" && ! -L "$manifest" ]] \
    || { printf 'CI_FETCH_FAIL jdk closure manifest unavailable\n' >&2; exit 1; }
  printf '%s  %s\n' "$(value ci_jdk_closure_manifest_sha256)" "$manifest" | sha256sum --check --status \
    || { printf 'CI_FETCH_FAIL jdk closure manifest digest\n' >&2; exit 1; }
  local count=0 package version origin url size sha256field depends license
  while IFS=$'\t' read -r package version origin url size sha256field depends license; do
    [[ "$package" == "package" ]] && continue
    [[ -n "$package" ]] || continue
    case "$url" in
      https://snapshot.ubuntu.com/ubuntu/20251017T000000Z/*) ;;
      *) printf 'CI_FETCH_FAIL endpoint outside approved set: %s\n' "$url" >&2; exit 1 ;;
    esac
    fetch_checked "jdk-deb:$package" "$url" "$debs/$(basename "$url")" "$size" "$sha256field" no-redirects
    count=$((count + 1))
  done < "$manifest"
  [[ "$count" -eq "$(value ci_jdk_closure_packages)" ]] \
    || { printf 'CI_FETCH_FAIL jdk closure package count: %s\n' "$count" >&2; exit 1; }
  # Gradle distribution: the redirect chain services.gradle.org ->
  # downloads.gradle.org / github.com/gradle/gradle-distributions is part of the
  # approved endpoint set; the wrapper pins the same distributionSha256Sum.
  [[ "$(value ci_gradle_url)" == https://services.gradle.org/distributions/* ]] \
    || { printf 'CI_FETCH_FAIL gradle endpoint outside approved set\n' >&2; exit 1; }
  fetch_checked "gradle-dist" "$(value ci_gradle_url)" "$inputs/gradle-8.13-bin.zip" \
    "$(value ci_gradle_size)" "$(value ci_gradle_distribution_sha256)" redirects-ok
  # Android SDK archives: dl.google.com android repository, direct 200 only.
  [[ "$(value ci_platform_url)" == https://dl.google.com/android/repository/* ]] \
    || { printf 'CI_FETCH_FAIL platform endpoint outside approved set\n' >&2; exit 1; }
  fetch_checked "sdk-platform" "$(value ci_platform_url)" "$inputs/platform-36_r02.zip" \
    "$(value ci_platform_size)" "$(value ci_platform_sha256)" no-redirects
  [[ "$(value ci_build_tools_url)" == https://dl.google.com/android/repository/* ]] \
    || { printf 'CI_FETCH_FAIL build-tools endpoint outside approved set\n' >&2; exit 1; }
  fetch_checked "sdk-build-tools" "$(value ci_build_tools_url)" "$inputs/build-tools_r36.1_linux.zip" \
    "$(value ci_build_tools_size)" "$(value ci_build_tools_sha256)" no-redirects
  # Official SHA-1 cross-check for both SDK archives (repository2-3.xml stanzas).
  printf '%s  %s\n' "$(value ci_platform_official_sha1)" "$inputs/platform-36_r02.zip" | sha1sum --check --status \
    || { printf 'CI_FETCH_FAIL platform official sha1\n' >&2; exit 1; }
  printf '%s  %s\n' "$(value ci_build_tools_official_sha1)" "$inputs/build-tools_r36.1_linux.zip" | sha1sum --check --status \
    || { printf 'CI_FETCH_FAIL build-tools official sha1\n' >&2; exit 1; }
  printf 'CI_FETCH_ANDROID_OK debs=%s gradle=1 platform=1 build_tools=1\n' "$count"
}

case "${1:-native}" in
  android) android_mode ;;
  native|"") native_mode ;;
  *) printf 'CI_FETCH_FAIL unknown mode: %s\n' "$1" >&2; exit 1 ;;
esac
