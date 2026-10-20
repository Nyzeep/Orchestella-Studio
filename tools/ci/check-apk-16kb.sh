#!/usr/bin/env bash
# Lightweight PR/release gate: APK zipalign -P 16 and arm64-v8a ELF 16 KB checks.
# The heavyweight QEMU payload build stays in qemu-android.yml; this script only
# inspects the APK that the normal Android build produced.
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"

if [ ! -f "$APK" ]; then
  echo "FAIL APK missing: $APK"
  exit 1
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
  echo "FAIL zipalign not found (set ANDROID_HOME or ZIPALIGN)"
  exit 1
fi
"$ZIPALIGN" -c -P 16 -v 4 "$APK" >/dev/null || { echo "FAIL zipalign -P 16"; exit 1; }

if [ -z "${READELF:-}" ] && [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
  READELF="$(find "$SDK/ndk" -path '*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf' \( -type f -o -type l \) 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${READELF:-}" ]; then
  READELF="$(command -v llvm-readelf || command -v readelf || true)"
fi
if [ -z "${READELF:-}" ] || ! command -v "$READELF" >/dev/null 2>&1; then
  echo "FAIL readelf not found (set READELF)"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -qq "$APK" 'lib/arm64-v8a/*.so' -d "$TMP" \
  || { echo "FAIL cannot extract arm64-v8a native libraries"; exit 1; }
mapfile -t LIBS < <(find "$TMP/lib/arm64-v8a" -type f -name '*.so' | sort)
if [ "${#LIBS[@]}" -eq 0 ]; then
  echo "FAIL no arm64-v8a native libraries in $APK"
  exit 1
fi

READELF="$READELF" REQUIRE_BUNDLED_CLOSURE=1 bash tools/ci/check-elf-16kb.sh "${LIBS[@]}"
echo "APK_16KB_GATE_OK apk=$APK libs=${#LIBS[@]}"
