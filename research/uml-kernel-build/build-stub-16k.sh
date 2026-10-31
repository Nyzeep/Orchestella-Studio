#!/usr/bin/env bash
# stub_exe 16KB 重链变体构建（工单 01 S3 / 坑③修法一：重链）
# ---------------------------------------------------------------------------
# 背景：release stub_exe（2,000 B）原生 PT_LOAD 对齐 = 0x1000，不过
# check-apk-16kb.sh（对「全部 *.so」的口径）——stub 以 libuml_stub.so 名义进
# APK lib/arm64-v8a/ 会被 16K gate 拦截。exec 装载本身不要求 16KB LOAD 对齐
# （4K 宿主上 release 未重链 stub 的 exec 路径实测可用），重链是 gate 兼容的
# 二选一方案之一（另一方案 = check-elf-16kb.sh 加 exec-only 豁免口径）。
#
# 做法：与 build-bionic.sh 同源码同 defconfig 同工具链先做一次常规构建，
# 然后删除 stub 中间产物、用 make 命令行覆盖 STUB_EXE_LDFLAGS 重链——
# 链接配方（cmd_stub_exe）不变，仅调整链接变量，由同一 kbuild 规则产出。
#   原链接变量（arch/um/kernel/skas/Makefile）：
#     STUB_EXE_LDFLAGS = -Wl,-n -Wl,--no-rosegment -static
#   重链变量（本脚本）：
#     STUB_EXE_LDFLAGS = -Wl,--no-rosegment -static -Wl,-z,max-page-size=16384
#
# 实测发现（2026-10-31，NDK r29 ld.lld）：原配方里只「追加」max-page-size 无效
# ——`-Wl,-n`（nmagic）把 PT_LOAD p_align 钉死在最大段对齐（0x8），链接命令里
# 带不带 -z,max-page-size 产出逐字节相同（sha256 实证）。而 -n 的唯一目的是配合
# --no-rosegment 保证**单 LOAD 段**（Makefile 注释：双段共享页会让内核
# MAP_FIXED_NOREPLACE 二次 mmap 报 -EEXIST）——该目的由 --no-rosegment 独立
# 成立，故重链时去掉 -n：单 LOAD（R E）保持，p_align=0x4000，stub 仍 ~2KB。
#   验证口径：check-16kb-kernel.sh（llvm-readelf PT_LOAD 对齐 >= 0x4000）。
#
# 需在 x86_64 Linux 容器内运行（NDK r29 仅发布 linux-x86_64 host 工具链）。
# 用法（容器内，/work = 本目录，/src = 内核源码 tag um-arm64-v0.3.0）：
#   SRC=/src OUT=/out/build-stub16k NDK=/ndk/.../linux-x86_64 bash build-stub-16k.sh
# 产物：<OUT>/artifacts/stub_exe-16k + 复制到 release-assets/v0.3.0/stub_exe-16k
# 验证：check-16kb-kernel.sh（llvm-readelf PT_LOAD 对齐 >= 0x4000）
# 行为等价性：not-tested，留设备窗口（16K 通道）——4K 宿主 exec 不验等价性，
# 只验启动；对照基线 = 同轮 release stub（原版保留于 release-assets/v0.3.0/stub_exe）。
set -euo pipefail

SRC=${SRC:-/src}
OUT=${OUT:-/out/build-stub16k}
NDK=${NDK:-/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64}
API=${API:-30}
JOBS=${JOBS:-$(nproc)}
HOSTCC=${HOSTCC:-/usr/bin/gcc}
HOSTCXX=${HOSTCXX:-/usr/bin/g++}
ASSETS=${ASSETS:-}   # 缺省 = <脚本目录>/release-assets/v0.3.0

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
[ -n "$ASSETS" ] || ASSETS="$SCRIPT_DIR/release-assets/v0.3.0"
mkdir -p "$ASSETS"

# 1) 常规构建（与 build-bionic.sh 完全一致；stub_exe.o 等中间产物在此产生）
cp "$SCRIPT_DIR/defconfigs/linux-bionic.config" "$OUT/.config"
make "${common[@]}" olddefconfig
make "${common[@]}" -j"$JOBS"

# 留一份原始 stub 作对照
cp "$OUT/arch/um/kernel/skas/stub_exe" "$OUT/artifacts-stub-orig"
sha256sum "$OUT/artifacts-stub-orig"

# 2) 16KB 重链：命令行覆盖 STUB_EXE_LDFLAGS（去 -n + 加 max-page-size，理由见头部）。
#    注意：不能 `make arch/um/kernel/skas/stub_exe` 单目标触发——kbuild 单目标
#    构建只支持 obj 目标，stub_exe 是 `targets +=` 注册的自定义目标（No rule to
#    make target）。做法：删除 stub 中间产物后跑同一 OUT 的增量默认构建，kbuild
#    沿同一条 cmd_stub_exe 规则重链（stub_exe_embed.o 与 vmlinux 会连带刷新，
#    但本产物只取 stub_exe，内核本体以 build-bionic.sh 产物为准）。
STUB16K_LDFLAGS="-Wl,--no-rosegment -static -Wl,-z,max-page-size=16384"
rm -f "$OUT/arch/um/kernel/skas/stub_exe" "$OUT/arch/um/kernel/skas/stub_exe.dbg"
make "${common[@]}" -j"$JOBS" STUB_EXE_LDFLAGS="$STUB16K_LDFLAGS"

# 3) 产物落位 + 验证
mkdir -p "$OUT/artifacts"
cp "$OUT/arch/um/kernel/skas/stub_exe" "$OUT/artifacts/stub_exe-16k"
chmod 755 "$OUT/artifacts/stub_exe-16k"

echo "--- PT_LOAD 原始数据（llvm-readelf -l，诊断用） ---"
for f in "$OUT/artifacts/stub_exe-16k" "$OUT/artifacts-stub-orig"; do
  echo "== $(basename "$f") =="
  "$NDK/bin/llvm-readelf" -l "$f" | sed -n '/^Program Headers:/,/^ Section to Segment mapping:/p' \
    | awk '$1=="LOAD" || NR<=2 {print}'
done

echo "--- 16KB PT_LOAD 对齐验证（check-16kb-kernel.sh 同口径） ---"
# gate 只针对重链产物；原版 stub（artifacts-stub-orig）作为对照打印，预期不过。
READELF="$NDK/bin/llvm-readelf" bash "$SCRIPT_DIR/check-16kb-kernel.sh" \
  "$OUT/artifacts/stub_exe-16k" || {
    echo "16KB 对齐验证失败，不落 release-assets；产物暂存 <script-dir>/out/ 供诊断" >&2
    mkdir -p "$SCRIPT_DIR/out/stub16k-debug"
    cp "$OUT/artifacts/stub_exe-16k" "$OUT/artifacts-stub-orig" "$SCRIPT_DIR/out/stub16k-debug/"
    exit 1; }

echo "--- 产物对照 ---"
ls -l "$OUT/artifacts/stub_exe-16k" "$OUT/artifacts-stub-orig"
sha256sum "$OUT/artifacts/stub_exe-16k" "$OUT/artifacts-stub-orig"
file "$OUT/artifacts/stub_exe-16k" || true

# 4) release-assets：新 stub 仅 ~2KB 可入库；原版 stub_exe 保留对照（勿覆盖）
if [ -f "$ASSETS/stub_exe-16k" ]; then
  echo "release-assets 已有 stub_exe-16k，跳过覆盖（如需刷新请手动删除后重跑）"
else
  cp "$OUT/artifacts/stub_exe-16k" "$ASSETS/stub_exe-16k"
  sha256sum "$ASSETS/stub_exe-16k"
fi
echo "STUB_16K_BUILD_OK"
