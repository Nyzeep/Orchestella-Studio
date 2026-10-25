#!/usr/bin/env bash
# DV-PVM-01 QA probe build: host smoke + arm64 payload .so + staging.
#
# QA-only; never part of CI or the release payload path. Runs in an ASCII
# worktree (NDK CMake cannot handle non-ASCII paths). Inputs beyond the repo:
#   ANDROID_NDK_HOME / ANDROID_SDK_ROOT (NDK 27.0.12077973 pinned)
#   FL_PROBE_LLVM_SRC   llvm-project source root (clang/LLVM headers)
#   FL_PROBE_LLVM_BUILD arm64 cross-build tree (generated LLVM/clang headers)
#   FL_PROBE_CLANG_LIBS dir with the staged stripped libLLVM.so/libclang-cpp.so
#   FL_PROBE_SKIP_HOST  1 = skip the host smoke build/run
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SRC="$ROOT/fastlane-payload"
ANDROID_BUILD="$ROOT/build/fastlane-payload-probe"
HOST_BUILD="$ROOT/build/fastlane-payload-probe-host"
CACHE="$ROOT/build/pvm-probe-cache"
GEN="$CACHE/gen"
STAGE_PAYLOAD="$ROOT/build/payload-stage/arm64-v8a"
STAGE_COMPILER="${FL_PROBE_CLANG_LIBS:-$ROOT/build/pvm-compiler/staging/arm64-v8a}"
LLVM_SRC="${FL_PROBE_LLVM_SRC:-$ROOT/build/llvm-android/src/llvm-project-22.1.8.src}"
LLVM_BUILD="${FL_PROBE_LLVM_BUILD:-$ROOT/build/llvm-android/build/android-arm64-v8a-release}"

if [ ! -f "$STAGE_COMPILER/libclang-cpp.so" ] || [ ! -f "$STAGE_COMPILER/libLLVM.so" ]; then
  echo "FAIL staged compiler libs missing under $STAGE_COMPILER" >&2
  exit 1
fi
if [ ! -d "$LLVM_SRC/llvm/include" ] || [ ! -d "$LLVM_SRC/clang/include" ]; then
  echo "FAIL llvm-project source missing at $LLVM_SRC (set FL_PROBE_LLVM_SRC)" >&2
  exit 1
fi
if [ ! -f "$LLVM_BUILD/include/llvm/Config/llvm-config.h" ]; then
  echo "FAIL llvm cross-build tree missing at $LLVM_BUILD (set FL_PROBE_LLVM_BUILD)" >&2
  exit 1
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
if [ "$NDK_REVISION" != "27.0.12077973" ]; then
  echo "FAIL NDK revision is '$NDK_REVISION', expected 27.0.12077973" >&2
  exit 1
fi

CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"

# --- 1) TinyCC predefs: bake tccdefs.h into string literals ------------------
# The payload must not consult any include path at run time; CONFIG_TCC_PREDEFS
# makes TinyCC use the converted literals instead of "#include <tccdefs.h>".
# Source comes from the hash-pinned FetchContent cache when present.
TINYCC_SRC=""
for candidate in "$ROOT/build/fastlane-payload/_deps/tinycc-src" "$CACHE/tinycc-src"; do
  if [ -f "$candidate/include/tccdefs.h" ] && [ -f "$candidate/conftest.c" ]; then
    TINYCC_SRC="$candidate"
    break
  fi
done
if [ -z "$TINYCC_SRC" ]; then
  # Fallback: fetch the pinned tarball (same URL/SHA as CMakeLists.txt).
  TINYCC_URL="https://github.com/TinyCC/tinycc/archive/e23dabed154a8495f3ac1ff3cc9d04fa1c207893.tar.gz"
  TINYCC_SHA256="d2470fdeed862ddb18f4ec4e6a405fa984580c3d85c38492516749c61931c30e"
  mkdir -p "$CACHE"
  curl -fsSL "$TINYCC_URL" -o "$CACHE/tinycc.tar.gz"
  echo "$TINYCC_SHA256  $CACHE/tinycc.tar.gz" | sha256sum -c - >/dev/null
  rm -rf "$CACHE/tinycc-src"
  mkdir -p "$CACHE/tinycc-src"
  tar -xzf "$CACHE/tinycc.tar.gz" -C "$CACHE/tinycc-src" --strip-components=1
  TINYCC_SRC="$CACHE/tinycc-src"
fi
mkdir -p "$GEN"
HOST_CC="${FL_PROBE_HOST_CC:-gcc}"
"$HOST_CC" -DC2STR "$TINYCC_SRC/conftest.c" -o "$GEN/c2str.exe"
"$GEN/c2str.exe" "$TINYCC_SRC/include/tccdefs.h" "$GEN/tccdefs_.h"
test -s "$GEN/tccdefs_.h"

# Decision-map §11 pitfall: TinyCC's source root carries an extensionless
# "version" file that libc++'s <version> include resolves to first (user -I
# paths precede the sysroot C++ headers), breaking every C++ TU that pulls in
# <chrono>. Point FetchContent at a sanitized copy without that file; the
# copy inherits the hash-pinned cache, and libtcc never reads "version"
# (TCC_VERSION comes from the generated config.h).
SANITIZED="$CACHE/tinycc-src-noversion"
if [ ! -f "$SANITIZED/libtcc.c" ]; then
  rm -rf "$SANITIZED"
  cp -r "$TINYCC_SRC" "$SANITIZED"
  rm -f "$SANITIZED/version"
fi
# QA-only sink patch (see tools/pvm-compiler/patch_tccelf_fd_sentinel.py):
# the Microdroid guest denies every path-based write, so tcc_write_elf_file
# learns an fd sentinel and the payload hands over a memfd descriptor.
python tools/pvm-compiler/patch_tccelf_fd_sentinel.py "$SANITIZED/tccelf.c"
FETCH_ARGS=(-DFETCHCONTENT_SOURCE_DIR_TINYCC="$SANITIZED")

# --- 2) Host smoke (same probe source, PREDEFS path exercised) ---------------
if [ "${FL_PROBE_SKIP_HOST:-0}" != "1" ]; then
  "$CMAKE" -S "$SRC" -B "$HOST_BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER="$HOST_CC" -DCMAKE_CXX_COMPILER="${FL_PROBE_HOST_CXX:-g++}" \
    -DFL_BUILD_TESTS=OFF -DFL_BUILD_HOST=OFF \
    -DFL_BUILD_PVM_PROBE=ON \
    -DFL_TCC_FD_SENTINEL=ON \
    -DFL_PVM_PROBE_TCCDEFS_DIR="$GEN" \
    "${FETCH_ARGS[@]}"
  "$CMAKE" --build "$HOST_BUILD" --target pvm_probe_host --parallel
  echo "=== host smoke: pvm_probe_host ==="
  "$HOST_BUILD/pvm_probe_host"; smoke=$?
  if [ "$smoke" -ne 0 ]; then
    echo "FAIL host smoke exited $smoke" >&2
    exit 1
  fi
fi

# --- 3) Android probe payload build (c++_shared to match the staged libclang) -
rm -rf "$ANDROID_BUILD"
"$CMAKE" -S "$SRC" -B "$ANDROID_BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared \
  -DFL_BUILD_TESTS=OFF -DFL_BUILD_HOST=OFF \
  -DFL_BUILD_PVM_PROBE=ON \
  -DFL_TCC_FD_SENTINEL=ON \
  -DFL_PVM_PROBE_TCCDEFS_DIR="$GEN" \
  -DFL_PVM_PROBE_CLANG_LIBS="$STAGE_COMPILER" \
  -DFL_PVM_PROBE_LLVM_SRC="$LLVM_SRC" \
  -DFL_PVM_PROBE_LLVM_BUILD="$LLVM_BUILD" \
  "${FETCH_ARGS[@]}"
"$CMAKE" --build "$ANDROID_BUILD" --target fastlane_payload pvm_probe pvm_probe_clangload pvm_probe_clang --parallel

# --- 4) Stage everything the QA APK's jniLibs need ----------------------------
mkdir -p "$STAGE_PAYLOAD"
cp "$ANDROID_BUILD/libfastlane_payload.so" \
   "$ANDROID_BUILD/libpvm_probe.so" \
   "$ANDROID_BUILD/libpvm_probe_clangload.so" \
   "$ANDROID_BUILD/libpvm_probe_clang.so" \
   "$STAGE_PAYLOAD/"

# --- 5) DT_NEEDED record for the report (path facts, not device data) ---------
READELF="$(find "$NDK/toolchains/llvm" -name llvm-readelf.exe -o -name llvm-readelf 2>/dev/null | head -1)"
if [ -n "$READELF" ]; then
  echo "=== DT_NEEDED of staged payloads ==="
  for so in "$STAGE_PAYLOAD"/*.so; do
    echo "--- $(basename "$so")"
    "$READELF" -d "$so" | grep NEEDED || echo "  (none)"
  done
fi

echo "OK probe build staged under $STAGE_PAYLOAD"
