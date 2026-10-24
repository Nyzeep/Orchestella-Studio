#!/usr/bin/env bash
# PVM-04: cross-build LLVM+clang shared libraries for Android arm64-v8a.
#
# Spike scope (PVM-04): produce libLLVM.so + libclang-cpp.so (+ the NDK's
# libc++_shared.so), measure sizes and dependencies via
# tools/llvm-android/report_size.py. Nothing here is linked into the payload;
# productization is gated by DV-PVM-01.
#
# Strategy: host-native tablegen tools (MinGW g++ on Windows / system g++ on
# Linux) + NDK clang cross build driven by Ninja. Build trees live on a short
# ASCII path by default because the NDK's CMake crashes on non-ASCII paths.
#
# Usage:
#   bash tools/llvm-android/build.sh \
#     [--llvm-version 22.1.8] [--ndk <ndk-root>] \
#     [--abi arm64-v8a] [--build-type Release|MinSizeRel] [--targets AArch64] \
#     [--api 34] [--jobs 10] [--out <dir>] [--cache <dir>] [--src <dir>] \
#     [--build-tree <dir>] [--cmake <path>] [--force]
#
# NDK/CMake resolution: ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_SDK_ROOT
# / ANDROID_HOME first, then a plain D-drive AndroidSDK layout when present.
# Machine-specific absolute paths stay out of this script (sensitive-data
# gate); pass --ndk/--cmake explicitly for anything else.
#
# Artifacts: $OUT/lib/$ABI/*.so (stripped), $OUT/unstripped/$ABI/*.so,
# $OUT/build-meta.json, $OUT/logs/*.log.txt

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"

LLVM_VERSION="22.1.8"
ABI="arm64-v8a"
BUILD_TYPE="Release"
TARGETS="AArch64"
API="34"
JOBS=""
OUT="$REPO_ROOT/build/llvm-android"
CMAKE_BIN=""
FORCE=0

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_OS=windows ;;
  *) HOST_OS=linux ;;
esac

if [ "$HOST_OS" = windows ]; then
  CACHE_DEFAULT="/d/t/llvm-android/cache"
  SRC_DEFAULT="/d/t/llvm-android/src"
  BUILDTREE_DEFAULT="/d/t/llvm-android/build"
  NDK_DEFAULT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
  if [ -z "$NDK_DEFAULT" ] && [ -d /d/AndroidSDK/ndk ]; then
    NDK_DEFAULT="$(find /d/AndroidSDK/ndk -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  fi
else
  CACHE_DEFAULT="${XDG_CACHE_HOME:-$HOME/.cache}/llvm-android"
  SRC_DEFAULT="$CACHE_DEFAULT/src"
  BUILDTREE_DEFAULT="$CACHE_DEFAULT/build"
  NDK_DEFAULT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
fi
CACHE="$CACHE_DEFAULT"
SRC_ROOT="$SRC_DEFAULT"
BUILD_TREE="$BUILDTREE_DEFAULT"
NDK="$NDK_DEFAULT"

while [ $# -gt 0 ]; do
  case "$1" in
    --llvm-version) LLVM_VERSION="$2"; shift 2 ;;
    --ndk) NDK="$2"; shift 2 ;;
    --abi) ABI="$2"; shift 2 ;;
    --build-type) BUILD_TYPE="$2"; shift 2 ;;
    --targets) TARGETS="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --cache) CACHE="$2"; shift 2 ;;
    --src) SRC_ROOT="$2"; shift 2 ;;
    --build-tree) BUILD_TREE="$2"; shift 2 ;;
    --cmake) CMAKE_BIN="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    *) echo "FAIL unknown option: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Pinned source tarball. Upstream releases ship a sigstore bundle instead of a
# checksum file, so the SHA-256 here is cross-checked against a distribution
# that ingests the same official artifact: Arch Linux packaging repo,
# tag 22.1.8-1 of archlinux/packaging/packages/llvm (PKGBUILD sha256sums[0]).
# ---------------------------------------------------------------------------
sha256_for_version() {
  case "$1" in
    22.1.8) printf '%s' "922f1817a0df7b1489272d18134ee0087a8b068828f87ac63b9861b1a9965888" ;;
    *) printf '' ;;
  esac
}
PINNED_SHA256="$(sha256_for_version "$LLVM_VERSION")"
if [ -z "$PINNED_SHA256" ]; then
  echo "FAIL no pinned SHA-256 for LLVM $LLVM_VERSION." >&2
  echo "     Add one to sha256_for_version() after verifying the tarball from" >&2
  echo "     a second source (e.g. a distribution package repository). Refusing to build unpinned." >&2
  exit 1
fi

for tool in curl tar sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL missing tool: $tool" >&2; exit 1; }
done
if [ -z "$CMAKE_BIN" ]; then
  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [ -n "$SDK_ROOT" ] && [ -f "$SDK_ROOT/cmake/3.22.1/bin/cmake.exe" ]; then
    CMAKE_BIN="$SDK_ROOT/cmake/3.22.1/bin/cmake.exe"
  elif [ "$HOST_OS" = windows ] && [ -f "/d/AndroidSDK/cmake/3.22.1/bin/cmake.exe" ]; then
    CMAKE_BIN="/d/AndroidSDK/cmake/3.22.1/bin/cmake.exe"
  else
    CMAKE_BIN="$(command -v cmake || true)"
  fi
fi
[ -n "$CMAKE_BIN" ] && [ -x "$CMAKE_BIN" ] || { echo "FAIL cmake not found (use --cmake)" >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "FAIL ninja not found in PATH" >&2; exit 1; }
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || { echo "FAIL NDK toolchain file missing under $NDK (use --ndk)" >&2; exit 1; }
# report_size.py resolves llvm-readelf/llvm-nm from the same NDK.
export LLVM_ANDROID_NDK="$NDK"

if [ -z "$JOBS" ]; then
  # 16 GB host RAM: LLVM translation units and the lld link both need several
  # GB; 10 keeps peak memory inside the box while still saturating cores.
  JOBS=10
fi
HOST_CORES="$(nproc 2>/dev/null || echo "${NUMBER_OF_PROCESSORS:-?}")"

mkdir -p "$CACHE" "$OUT/logs"
LOG="$OUT/logs"

# --- 1. tarball: download (resume) + verify ---------------------------------
TARBALL="$CACHE/llvm-project-$LLVM_VERSION.src.tar.xz"
TARBALL_URL="https://github.com/llvm/llvm-project/releases/download/llvmorg-$LLVM_VERSION/llvm-project-$LLVM_VERSION.src.tar.xz"
VERIFIED_MARKER="$TARBALL.verified"

tarball_ok() {
  [ -f "$TARBALL" ] && [ "$(sha256sum "$TARBALL" | awk '{print $1}')" = "$PINNED_SHA256" ]
}

if [ ! -f "$VERIFIED_MARKER" ] || [ "$(cat "$VERIFIED_MARKER" 2>/dev/null)" != "$PINNED_SHA256" ]; then
  if [ -f "$TARBALL" ]; then
    if tarball_ok; then
      echo "== existing tarball matches the pin"
    else
      echo "== resuming interrupted download"
      # A completed download returns HTTP 416 with -C -; the verification
      # below decides whether the bytes are good either way.
      curl -fL -C - --retry 8 --retry-delay 5 --retry-all-errors -o "$TARBALL" "$TARBALL_URL" || true
    fi
  else
    echo "== downloading $TARBALL_URL"
    curl -fL -C - --retry 8 --retry-delay 5 --retry-all-errors -o "$TARBALL" "$TARBALL_URL"
  fi
  if ! tarball_ok; then
    echo "FAIL tarball SHA-256 mismatch (expected $PINNED_SHA256)" >&2
    exit 1
  fi
  printf '%s' "$PINNED_SHA256" > "$VERIFIED_MARKER"
  echo "== tarball verified: $PINNED_SHA256"
else
  echo "== tarball already verified: $PINNED_SHA256"
fi

# --- 2. source tree ----------------------------------------------------------
SRC="$SRC_ROOT/llvm-project-$LLVM_VERSION.src"
if [ "$FORCE" = 1 ] && [ -d "$SRC" ]; then
  rm -rf "$SRC"
fi
if [ ! -d "$SRC/llvm" ] || [ ! -d "$SRC/clang" ]; then
  echo "== extracting source to $SRC_ROOT"
  mkdir -p "$SRC_ROOT"
  # GNU tar on Windows cannot create the symlink fixtures under clang/test;
  # tolerate those errors and validate the two project dirs afterwards.
  if ! tar -xJf "$TARBALL" -C "$SRC_ROOT"; then
    echo "WARN tar exited nonzero (expected: symlink fixtures on Windows); validating extraction" >&2
  fi
fi
[ -d "$SRC/llvm" ] && [ -d "$SRC/clang" ] || { echo "FAIL source extraction failed under $SRC_ROOT" >&2; exit 1; }

# --- 3. host-native tablegen tools -------------------------------------------
HOST_BUILD="$BUILD_TREE/host"
if [ "$HOST_OS" = windows ]; then
  # MinGW g++ builds llvm-tblgen/clang-tblgen/llvm-config; static linking keeps
  # them runnable without libstdc++/libwinpthread DLLs on PATH. Prepending
  # mingw64/bin also fixes the MSYS2 quirk where /usr/bin/ld shadows the
  # mingw64 linker.
  MINGW_BIN="/d/MSYS2/mingw64/bin"
  if [ -f "$MINGW_BIN/g++.exe" ]; then
    export PATH="$MINGW_BIN:$PATH"
    HOST_CC="$MINGW_BIN/gcc.exe"
    HOST_CXX="$MINGW_BIN/g++.exe"
    HOST_STATIC_LINK='-DCMAKE_EXE_LINKER_FLAGS=-static'
  else
    HOST_CC="$(command -v gcc || true)"
    HOST_CXX="$(command -v g++ || true)"
    HOST_STATIC_LINK=''
  fi
  [ -n "$HOST_CC" ] && [ -n "$HOST_CXX" ] || { echo "FAIL host gcc/g++ not found" >&2; exit 1; }
else
  HOST_CC="${CC:-gcc}"
  HOST_CXX="${CXX:-g++}"
  HOST_STATIC_LINK=''
fi

HOST_COMMON_FLAGS=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_C_COMPILER="$HOST_CC"
  -DCMAKE_CXX_COMPILER="$HOST_CXX"
  -DLLVM_TARGETS_TO_BUILD="$TARGETS"
  -DLLVM_ENABLE_PROJECTS=clang
  -DLLVM_BUILD_TOOLS=OFF
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF
  -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_INCLUDE_DOCS=OFF
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF
)
if [ ! -x "$HOST_BUILD/bin/llvm-tblgen" ] && [ ! -x "$HOST_BUILD/bin/llvm-tblgen.exe" ]; then
  echo "== configuring host tablegen build"
  # shellcheck disable=SC2086
  "$CMAKE_BIN" -S "$SRC/llvm" -B "$HOST_BUILD" "${HOST_COMMON_FLAGS[@]}" $HOST_STATIC_LINK \
    > "$LOG/host-configure.log.txt" 2>&1 \
    || { tail -40 "$LOG/host-configure.log.txt" >&2; exit 1; }
fi
echo "== building host tools (llvm-tblgen clang-tblgen llvm-config)"
SECONDS_HOST_START=$SECONDS
ninja -C "$HOST_BUILD" -j "$JOBS" llvm-tblgen clang-tblgen llvm-config \
  > "$LOG/host-build.log.txt" 2>&1 \
  || { tail -60 "$LOG/host-build.log.txt" >&2; exit 1; }
WALL_HOST=$((SECONDS - SECONDS_HOST_START))
HOST_TOOLS="$HOST_BUILD/bin"
for tool in llvm-tblgen clang-tblgen llvm-config; do
  [ -x "$HOST_TOOLS/$tool" ] || [ -x "$HOST_TOOLS/$tool.exe" ] \
    || { echo "FAIL host tool missing: $tool (see $LOG/host-build.log.txt)" >&2; exit 1; }
done
LLVM_CONFIG_VERSION="$("$HOST_TOOLS/llvm-config" --version 2>/dev/null || "$HOST_TOOLS/llvm-config.exe" --version)"
echo "== llvm-config --version: $LLVM_CONFIG_VERSION"

# --- 4. cross build for Android ----------------------------------------------
# 16 KB page compatibility: every PT_LOAD must be 16 KB aligned.
# RTTI on: PVM-03 consumes the clang C++ API in-process; without typeinfo in
# libLLVM, downstream isa<>/cast<> on derived classes would be unusable.
CROSS_BUILD="$BUILD_TREE/android-$ABI-$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
TABLEGEN_EXE_SUFFIX="$([ "$HOST_OS" = windows ] && echo .exe || echo '')"
CROSS_FLAGS=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="android-$API"
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
  -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
  -DLLVM_TARGETS_TO_BUILD="$TARGETS"
  -DLLVM_ENABLE_PROJECTS=clang
  -DLLVM_BUILD_TOOLS=OFF
  -DLLVM_BUILD_UTILS=OFF
  -DLLVM_INCLUDE_TESTS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF
  -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_INCLUDE_DOCS=OFF
  -DLLVM_ENABLE_TERMINFO=OFF -DLLVM_ENABLE_ZLIB=OFF -DLLVM_ENABLE_ZSTD=OFF
  -DLLVM_ENABLE_LIBXML2=OFF
  -DLLVM_BUILD_LLVM_DYLIB=ON
  -DLLVM_LINK_LLVM_DYLIB=ON
  -DLLVM_ENABLE_RTTI=ON
  "-DLLVM_TABLEGEN=$HOST_TOOLS/llvm-tblgen$TABLEGEN_EXE_SUFFIX"
  "-DCLANG_TABLEGEN=$HOST_TOOLS/clang-tblgen$TABLEGEN_EXE_SUFFIX"
  -DLLVM_HOST_TRIPLE="aarch64-unknown-linux-android"
  -DLLVM_DEFAULT_TARGET_TRIPLE="aarch64-unknown-linux-gnu"
)

echo "== configuring cross build ($CROSS_BUILD)"
"$CMAKE_BIN" -S "$SRC/llvm" -B "$CROSS_BUILD" "${CROSS_FLAGS[@]}" \
  > "$LOG/cross-configure.log.txt" 2>&1 \
  || { tail -60 "$LOG/cross-configure.log.txt" >&2; exit 1; }
echo "== building LLVM and clang-cpp (long step; log: $LOG/cross-build.log.txt)"
SECONDS_CROSS_START=$SECONDS
ninja -C "$CROSS_BUILD" -j "$JOBS" LLVM clang-cpp \
  > "$LOG/cross-build.log.txt" 2>&1 \
  || { tail -80 "$LOG/cross-build.log.txt" >&2; exit 1; }
WALL_CROSS=$((SECONDS - SECONDS_CROSS_START))

# --- 5. dist: copy, unstripped copies, strip ---------------------------------
HOST_TAG="$([ "$HOST_OS" = windows ] && echo windows-x86_64 || echo linux-x86_64)"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
LIBCXX_SHARED="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"

OUT_LIB="$OUT/lib/$ABI"
OUT_RAW="$OUT/unstripped/$ABI"
mkdir -p "$OUT_LIB" "$OUT_RAW"
cp "$CROSS_BUILD/lib/libLLVM.so" "$OUT_RAW/libLLVM.so"
cp "$CROSS_BUILD/lib/libclang-cpp.so" "$OUT_RAW/libclang-cpp.so"
if [ -f "$LIBCXX_SHARED" ]; then
  cp "$LIBCXX_SHARED" "$OUT_RAW/libc++_shared.so"
else
  echo "WARN libc++_shared.so not found under NDK sysroot" >&2
fi
STRIP="$NDK_BIN/llvm-strip$([ "$HOST_OS" = windows ] && echo .exe || echo '')"
for so in "$OUT_RAW"/*.so; do
  "$STRIP" --strip-unneeded -o "$OUT_LIB/$(basename "$so")" "$so"
done

# --- 6. repo gate: 16 KB ELF alignment ----------------------------------------
if [ -f "$REPO_ROOT/tools/ci/check-elf-16kb.sh" ]; then
  echo "== tools/ci/check-elf-16kb.sh on the stripped artifacts"
  READELF="$NDK_BIN/llvm-readelf$([ "$HOST_OS" = windows ] && echo .exe || echo '')"
  READELF="$READELF" REQUIRE_BUNDLED_CLOSURE=0 bash "$REPO_ROOT/tools/ci/check-elf-16kb.sh" "$OUT_LIB"/*.so
fi

# --- 7. metadata ---------------------------------------------------------------
DISK_HOST_MB="$(du -sm "$HOST_BUILD" 2>/dev/null | awk '{print $1}')"
DISK_CROSS_MB="$(du -sm "$CROSS_BUILD" 2>/dev/null | awk '{print $1}')"
NDK_REVISION="$(sed -nE 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK/source.properties" | head -1)"
CMAKE_VERSION="$("$CMAKE_BIN" --version 2>/dev/null | head -1 | awk '{print $NF}')"
WALL_TOTAL=$((WALL_HOST + WALL_CROSS))
cat > "$OUT/build-meta.json" <<META
{
  "generated_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "llvm_version": "$LLVM_CONFIG_VERSION",
  "llvm_source_version": "$LLVM_VERSION",
  "llvm_source_sha256": "$PINNED_SHA256",
  "abi": "$ABI",
  "api_level": $API,
  "targets": "$TARGETS",
  "build_type": "$BUILD_TYPE",
  "llvm_host_triple": "aarch64-unknown-linux-android",
  "default_triple": "aarch64-unknown-linux-gnu",
  "ndk_version": "$NDK_REVISION",
  "host_cores": "$HOST_CORES",
  "jobs": $JOBS,
  "wall_host_s": $WALL_HOST,
  "wall_cross_s": $WALL_CROSS,
  "wall_total_s": $WALL_TOTAL,
  "disk_host_mb": ${DISK_HOST_MB:-0},
  "disk_cross_mb": ${DISK_CROSS_MB:-0},
  "cmake": "$CMAKE_VERSION",
  "generator": "Ninja"
}
META

echo
echo "== build finished"
echo "   stripped:   $OUT_LIB"
echo "   unstripped: $OUT_RAW"
echo "   wall: host ${WALL_HOST}s + cross ${WALL_CROSS}s = ${WALL_TOTAL}s"
echo "   next: python tools/llvm-android/report_size.py --out tools/llvm-android/SIZE_REPORT.md"
