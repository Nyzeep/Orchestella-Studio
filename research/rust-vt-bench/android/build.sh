#!/usr/bin/env bash
# Build the arm64-v8a measurement shim and check the R01 size / alignment gates.
#
#   ./android/build.sh            # build + strip + report
#   CHECK_REPRO=1 ./android/build.sh   # additionally rebuild clean and compare sha256
#
# Requirements: rustup toolchain 1.91.0 (see rust-toolchain.toml), cargo-ndk,
# Android NDK (ANDROID_NDK_HOME or /opt/android-sdk/ndk/<version>).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$BENCH_DIR/../.." && pwd)"
export PATH="$HOME/.cargo/bin:$PATH"

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort | tail -1)}"
if [ -z "${NDK_HOME:-}" ] || [ ! -d "$NDK_HOME" ]; then
  echo "FAIL: Android NDK not found (set ANDROID_NDK_HOME)" >&2
  exit 2
fi
export ANDROID_NDK_HOME="$NDK_HOME"
export ANDROID_HOME="$ANDROID_HOME"
TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$BENCH_DIR/android/jniLibs/arm64-v8a"
LIB="$OUT/libvt_jni.so"

echo "==> NDK: $NDK_HOME"
echo "==> rustc: $(rustc --version)"
start=$(date +%s)
( cd "$BENCH_DIR/vt-jni" && cargo ndk -t arm64-v8a -o ../android/jniLibs build --release )
echo "==> clean-build-seconds: $(( $(date +%s) - start ))"

"$TOOLCHAIN/llvm-strip" --strip-all "$LIB"
size=$(stat -c %s "$LIB")
sha=$(sha256sum "$LIB" | cut -d' ' -f1)
echo "==> stripped size: $size bytes"
echo "==> sha256: $sha"

echo "==> 16 KB PT_LOAD check"
READELF="$TOOLCHAIN/llvm-readelf" bash "$REPO_ROOT/tools/ci/check-elf-16kb.sh" "$LIB"

echo "==> PT_LOAD alignment"
"$TOOLCHAIN/llvm-readelf" -lW "$LIB" | awk '$1 == "LOAD" { print "    LOAD align=" $NF }'

if [ "${CHECK_REPRO:-0}" = "1" ]; then
  echo "==> reproducibility: clean rebuild"
  ( cd "$BENCH_DIR/vt-jni" && cargo clean >/dev/null 2>&1 )
  ( cd "$BENCH_DIR/vt-jni" && cargo ndk -t arm64-v8a -o ../android/jniLibs build --release >/dev/null )
  "$TOOLCHAIN/llvm-strip" --strip-all "$LIB"
  sha2=$(sha256sum "$LIB" | cut -d' ' -f1)
  if [ "$sha" = "$sha2" ]; then
    echo "REPRODUCIBLE $sha"
  else
    echo "FAIL not reproducible: $sha != $sha2" >&2
    exit 3
  fi
fi

echo "OK"
