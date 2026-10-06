#!/usr/bin/env bash
#
# Copyright (C) 2026 Orchestella Studio
#
# Permission is hereby granted, free of charge, to any person obtaining a
# copy of this software and associated documentation files (the "Software"),
# to deal in the Software without restriction, including without limitation
# the rights to use, copy, modify, merge, publish, distribute, sublicense,
# and/or sell copies of the Software, and to permit persons to whom the
# Software is furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
# THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
# FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
# IN THE SOFTWARE.
#
# Ticket 57 / ADR-0019:
# Compiles purified libvirglrenderer.so for Android ARM64-v8a with 16KB page alignment.
# Completely eliminates libepoxy and libdrm dependencies.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

log() { printf '[virgl-ndk-build %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[virgl-ndk-build ERROR] %s\n' "$*" >&2; exit 1; }

log "Starting NDK cross-compilation of libvirglrenderer.so (ARM64-v8a, 16KB aligned)..."

# 1. Resolve Android NDK location
NDK_CANDIDATES=(
  "${ANDROID_NDK_HOME:-}"
  "${ANDROID_NDK_ROOT:-}"
  "S:/ndk/27.0.12077973"
  "/s/ndk/27.0.12077973"
  "D:/Development project(开发项目)/DroidLayer开发目录/.toolchains/ndk"
  "C:/Users/${USER:-Nyzee}/AppData/Local/Android/Sdk/ndk/27.0.12077973"
)

NDK_DIR=""
for candidate in "${NDK_CANDIDATES[@]}"; do
  if [ -n "$candidate" ] && [ -d "$candidate" ]; then
    NDK_DIR="$candidate"
    break
  fi
done

[ -n "$NDK_DIR" ] || die "Android NDK r27+ not found in environment or default paths."
log "Using Android NDK: $NDK_DIR"

# 2. Resolve LLVM toolchain binaries
TOOLCHAIN_BIN=""
for sub in "toolchains/llvm/prebuilt/windows-x86_64/bin" "toolchains/llvm/prebuilt/linux-x86_64/bin" "toolchains/llvm/prebuilt/darwin-x86_64/bin"; do
  if [ -d "$NDK_DIR/$sub" ]; then
    TOOLCHAIN_BIN="$NDK_DIR/$sub"
    break
  fi
done

[ -n "$TOOLCHAIN_BIN" ] || die "Could not find llvm prebuilt bin directory in $NDK_DIR"

CLANG="$TOOLCHAIN_BIN/clang"
CLANGXX="$TOOLCHAIN_BIN/clang++"
READELF="$TOOLCHAIN_BIN/llvm-readelf"

[ -x "$CLANG" ] || [ -f "${CLANG}.exe" ] || die "clang executable not found at $CLANG"
[ -x "$READELF" ] || [ -f "${READELF}.exe" ] || die "llvm-readelf executable not found at $READELF"

# On Windows / MSYS2, append .exe if needed
if [ -f "${CLANG}.exe" ] && [ ! -x "$CLANG" ]; then
  CLANG="${CLANG}.exe"
fi
if [ -f "${CLANGXX}.exe" ] && [ ! -x "$CLANGXX" ]; then
  CLANGXX="${CLANGXX}.exe"
fi
if [ -f "${READELF}.exe" ] && [ ! -x "$READELF" ]; then
  READELF="${READELF}.exe"
fi

BUILD_DIR="${SCRIPT_DIR}/build"
mkdir -p "$BUILD_DIR"

OUTPUT_SO="${BUILD_DIR}/libvirglrenderer.so"

log "Compiling libvirglrenderer.so for target aarch64-linux-android26..."

# 3. Compilation flags:
# - Target aarch64-linux-android26
# - 16KB page alignment: -Wl,-z,max-page-size=16384
# - Only standard Android libraries: -lEGL -lGLESv3 -llog -lm
# - NO libepoxy, NO libdrm
"$CLANGXX" \
  --target=aarch64-linux-android26 \
  -fPIC \
  -shared \
  -O2 \
  -std=c++17 \
  -static-libstdc++ \
  -Wl,-z,max-page-size=16384 \
  -Wl,-soname,libvirglrenderer.so \
  -I"${SCRIPT_DIR}/include" \
  -I"${SCRIPT_DIR}/include/drm" \
  -x c "${SCRIPT_DIR}/src/virgl_android_gl_loader.c" \
  -x c "${SCRIPT_DIR}/src/virgl_memory_quota.c" \
  -x c "${SCRIPT_DIR}/src/virglrenderer.c" \
  -x c++ "${SCRIPT_DIR}/src/virgl_jni_bridge.cpp" \
  -lEGL \
  -lGLESv3 \
  -llog \
  -lm \
  -o "$OUTPUT_SO"

log "Compiled successfully: $OUTPUT_SO"

# 4. Acceptance Criteria Verification

# Check A: File size < 1.5 MB
SO_SIZE=$(wc -c < "$OUTPUT_SO" | tr -d ' \r\n')
log "Binary size: $SO_SIZE bytes ($(( SO_SIZE / 1024 )) KB)"
if [ "$SO_SIZE" -ge 1572864 ]; then
  die "Binary size exceeds 1.5MB threshold: $SO_SIZE bytes"
fi
log "AC-1 PASSED: Binary size < 1.5 MB"

# Check B: 16KB page alignment verification
ELF_L_OUT=$("$READELF" -l "$OUTPUT_SO")
if echo "$ELF_L_OUT" | grep -qE "LOAD.*0x4000"; then
  LOAD_ALIGN="0x4000"
elif echo "$ELF_L_OUT" | grep -A 2 "LOAD" | grep -qE "Align.*(0x4000|16384)"; then
  LOAD_ALIGN="0x4000"
else
  LOAD_ALIGN="UNKNOWN"
fi
log "ELF LOAD segment alignment: $LOAD_ALIGN"
if [ "$LOAD_ALIGN" != "0x4000" ]; then
  die "16KB page alignment check FAILED: expected 0x4000 / 16384"
fi
log "AC-2 PASSED: Strict 16KB page alignment (Align = 0x4000) verified."

# Check C: Dynamic symbol dependency verification (readelf -d)
log "Inspecting NEEDED shared library dependencies..."
NEEDED_DEPS=$("$READELF" -d "$OUTPUT_SO" | grep "NEEDED" || true)
printf '%s\n' "$NEEDED_DEPS"

if echo "$NEEDED_DEPS" | grep -qiE "epoxy|drm"; then
  die "Illegal external dependency detected! Found libepoxy or libdrm in NEEDED table."
fi
log "AC-3 PASSED: Zero libepoxy and zero libdrm dependencies verified. Pure Android Bionic supply chain."

# 5. Stage artifact into application jniLibs and intermediate paths
JNILIBS_TARGET="${WORKSPACE_ROOT}/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_TARGET"
cp -f "$OUTPUT_SO" "${JNILIBS_TARGET}/libvirglrenderer.so"
log "Staged libvirglrenderer.so -> ${JNILIBS_TARGET}/libvirglrenderer.so"

MERGED_TARGET1="${WORKSPACE_ROOT}/app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a"
mkdir -p "$MERGED_TARGET1"
cp -f "$OUTPUT_SO" "${MERGED_TARGET1}/libvirglrenderer.so"

MERGED_TARGET2="${WORKSPACE_ROOT}/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"
mkdir -p "$MERGED_TARGET2"
cp -f "$OUTPUT_SO" "${MERGED_TARGET2}/libvirglrenderer.so"

log "libvirglrenderer.so build, validation, and staging complete!"
