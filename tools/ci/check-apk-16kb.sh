#!/usr/bin/env bash
# Lightweight PR/release gate: APK zipalign -P 16 and arm64-v8a ELF 16 KB checks.
# The heavyweight QEMU payload build stays in qemu-android.yml; this script only
# inspects the APK that the normal Android build produced.
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  echo "FAIL $*"
  # Surface the first failure as a check annotation so the cause is visible
  # without downloading the full job log.
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    echo "::error::16KB APK gate: $*"
  fi
  exit 1
}

if [ ! -f "$APK" ]; then
  fail "APK missing: $APK"
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
fi
BUILD_TOOLS=""
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BUILD_TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
fi

ZIPALIGN="${ZIPALIGN:-}"
if [ -z "$ZIPALIGN" ] && [ -n "$BUILD_TOOLS" ]; then
  ZIPALIGN="$BUILD_TOOLS/zipalign"
fi
if [ -z "$ZIPALIGN" ] || [ ! -x "$ZIPALIGN" ]; then
  ZIPALIGN="$(command -v zipalign || true)"
fi
if [ -z "$ZIPALIGN" ] || [ ! -x "$ZIPALIGN" ]; then
  fail "zipalign not found (set ANDROID_HOME or ZIPALIGN)"
fi
ZIPALIGN_LOG="$TMP/zipalign.log"
if ! "$ZIPALIGN" -c -P 16 -v 4 "$APK" >"$ZIPALIGN_LOG" 2>&1; then
  echo "zipalign=$ZIPALIGN"
  tail -5 "$ZIPALIGN_LOG" >&2
  fail "zipalign -P 16 failed ($ZIPALIGN)"
fi

if [ -z "${READELF:-}" ] && [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
  READELF="$(find "$SDK/ndk" -path '*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf' \( -type f -o -type l \) 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${READELF:-}" ]; then
  READELF="$(command -v llvm-readelf || command -v readelf || true)"
fi
if [ -z "${READELF:-}" ] || ! command -v "$READELF" >/dev/null 2>&1; then
  fail "readelf not found (set READELF)"
fi

echo "16KB_APK_GATE_TOOLS apk=$APK zipalign=$ZIPALIGN readelf=$READELF"
unzip -qq "$APK" 'lib/arm64-v8a/*.so' -d "$TMP" \
  || fail "cannot extract arm64-v8a native libraries"
mapfile -t LIBS < <(find "$TMP/lib/arm64-v8a" -type f -name '*.so' | sort)
if [ "${#LIBS[@]}" -eq 0 ]; then
  fail "no arm64-v8a native libraries in $APK"
fi

ELF_LOG="$TMP/elf.log"
if ! READELF="$READELF" REQUIRE_BUNDLED_CLOSURE=1 \
  bash tools/ci/check-elf-16kb.sh "${LIBS[@]}" 2>&1 | tee "$ELF_LOG"; then
  reason="$(grep -m1 '^FAIL' "$ELF_LOG" || true)"
  fail "${reason:-ELF 16KB check failed}"
fi
echo "APK_16KB_GATE_OK apk=$APK libs=${#LIBS[@]}"
