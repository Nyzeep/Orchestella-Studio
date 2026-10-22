#!/usr/bin/env bash
# Zero-device Fast Lane payload build.
#
# This script builds only the payload owned by P02. It never edits the payload
# sources and never touches a device. If fastlane-payload/ is absent (P02 has
# not delivered in this worktree), it exits 2 with BLOCKED so the caller can
# distinguish "not available" from "build failed".
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SRC="${FASTLANE_PAYLOAD_DIR:-$ROOT/fastlane-payload}"
BUILD="${FASTLANE_PAYLOAD_BUILD_DIR:-$ROOT/build/fastlane-payload}"
SYMBOL="${FASTLANE_PAYLOAD_SYMBOL:-AVmPayload_main}"

if [ ! -f "$SRC/CMakeLists.txt" ]; then
  echo "BLOCKED fastlane-payload/CMakeLists.txt is missing; P11 payload build waits for P02 (source dir: $SRC)"
  exit 2
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
    NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
  fi
fi
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "FAIL Android NDK not found (set ANDROID_NDK_HOME/ANDROID_NDK_ROOT)" >&2
  exit 1
fi
NDK_REVISION="$(sed -nE 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK/source.properties" | head -1)"
if [ "${FASTLANE_ALLOW_ANY_NDK:-0}" != "1" ] && [ "$NDK_REVISION" != "27.0.12077973" ]; then
  echo "FAIL NDK revision is '$NDK_REVISION', expected 27.0.12077973 (set FASTLANE_ALLOW_ANY_NDK=1 only for local experiments)" >&2
  exit 1
fi

CMAKE="${CMAKE:-cmake}"
if ! command -v "$CMAKE" >/dev/null 2>&1; then
  echo "FAIL cmake not found (set CMAKE)" >&2
  exit 1
fi

rm -rf "$BUILD"
"$CMAKE" \
  -S "$SRC" \
  -B "$BUILD" \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26
"$CMAKE" --build "$BUILD" --parallel

mapfile -t SOFILES < <(find "$BUILD" -type f -name '*.so' | sort)
if [ "${#SOFILES[@]}" -eq 0 ]; then
  echo "FAIL payload build produced no .so under $BUILD" >&2
  exit 1
fi

# The NDK ships some LLVM host tools as symlinks, so match regular files and
# symlinks alike. tools/ci/check-apk-16kb.sh resolves llvm-readelf the same way.
# Only llvm-readelf is acceptable here: it prints one program header per line,
# which is what tools/ci/check-elf-16kb.sh parses. GNU readelf wraps them onto
# two lines and would make the 16 KB alignment check read the wrong column.
find_ndk_tool() {
  find "$NDK/toolchains/llvm/prebuilt" -path "*/bin/$1" \( -type f -o -type l \) 2>/dev/null \
    | sort -V | tail -1 || true
}

READELF="${READELF:-}"
if [ -z "$READELF" ]; then
  READELF="$(find_ndk_tool llvm-readelf)"
fi
if [ -z "$READELF" ]; then
  READELF="$(find_ndk_tool llvm-readelf.exe)"
fi
if [ -z "$READELF" ]; then
  READELF="$(command -v llvm-readelf || true)"
fi
if [ -z "$READELF" ] || [ ! -x "$READELF" ]; then
  echo "FAIL llvm-readelf not found under the NDK" >&2
  exit 1
fi

READELF="$READELF" REQUIRE_BUNDLED_CLOSURE=0 bash "$ROOT/tools/ci/check-elf-16kb.sh" "${SOFILES[@]}"

NM="${NM:-}"
if [ -z "$NM" ]; then
  NM="$(find_ndk_tool llvm-nm)"
fi
if [ -z "$NM" ]; then
  NM="$(find_ndk_tool llvm-nm.exe)"
fi
if [ -z "$NM" ]; then
  NM="$(command -v llvm-nm || true)"
fi
if [ -z "$NM" ] || [ ! -x "$NM" ]; then
  echo "FAIL llvm-nm not found under the NDK" >&2
  exit 1
fi
NM_OUTPUT="$(mktemp)"
trap 'rm -f "$NM_OUTPUT"' EXIT
if ! "$NM" -D --defined-only "${SOFILES[@]}" > "$NM_OUTPUT"; then
  echo "FAIL llvm-nm could not read ${SOFILES[*]}" >&2
  exit 1
fi
if ! grep -qw "$SYMBOL" "$NM_OUTPUT"; then
  echo "FAIL payload symbol $SYMBOL is not exported by ${SOFILES[*]}" >&2
  exit 1
fi

echo "FASTLANE_PAYLOAD_OK files=${#SOFILES[@]} symbol=$SYMBOL"
