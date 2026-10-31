#!/usr/bin/env bash
# SMP（linux-bionic-smp）profile 复现构建 —— 对应 release um-arm64-v0.3.0 的 linux-bionic-smp
# 需在 x86_64 Linux 容器内运行：NDK r29 仅发布 linux-x86_64 host 工具链，
# 见 repository2-3.xml（访问 2026-10-31）：android-ndk-r29-{darwin,linux,windows}.zip，无 linux-arm64 变体。
# 用法：
#   SRC=/src OUT=/out/up NDK=/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 bash build-bionic.sh
set -euo pipefail

SRC=${SRC:-/src}
OUT=${OUT:-/out/build-smp}
NDK=${NDK:-/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64}
API=${API:-30}
JOBS=${JOBS:-$(nproc)}
HOSTCC=${HOSTCC:-/usr/bin/gcc}
HOSTCXX=${HOSTCXX:-/usr/bin/g++}

[ -x "$NDK/bin/clang" ] || { echo "missing NDK clang: $NDK/bin/clang" >&2; exit 1; }
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
cd "$SRC"

common=(
  ARCH=um SUBARCH=arm64 O="$OUT"
  HOSTCC="$HOSTCC" HOSTCXX="$HOSTCXX"
  "CC=$NDK/bin/clang --target=aarch64-linux-android$API"
  "LD=$NDK/bin/ld.lld" "AR=$NDK/bin/llvm-ar" "NM=$NDK/bin/llvm-nm"
  "OBJCOPY=$NDK/bin/llvm-objcopy" "OBJDUMP=$NDK/bin/llvm-objdump"
  "READELF=$NDK/bin/llvm-readelf" "STRIP=$NDK/bin/llvm-strip"
  LLVM_IAS=1 "CLANG_TARGET_FLAGS=aarch64-linux-android$API"
)

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# pin：以 release 二进制 IKCONFIG 提取的 config 为起点（defconfigs/linux-bionic-smp.config）
cp "$SCRIPT_DIR/defconfigs/linux-bionic-smp.config" "$OUT/.config"
make "${common[@]}" olddefconfig
make "${common[@]}" -j"$JOBS"

mkdir -p "$OUT/artifacts"
cp "$OUT/linux" "$OUT/artifacts/linux-bionic-smp"
cp "$OUT/arch/um/kernel/skas/stub_exe" "$OUT/artifacts/stub_exe"
"$NDK/bin/llvm-strip" --strip-debug "$OUT/artifacts/linux-bionic-smp"
chmod 755 "$OUT/artifacts/linux-bionic-smp" "$OUT/artifacts/stub_exe"
sha256sum "$OUT/artifacts/linux-bionic-smp" "$OUT/artifacts/stub_exe"
file "$OUT/artifacts/linux-bionic-smp" "$OUT/artifacts/stub_exe"
