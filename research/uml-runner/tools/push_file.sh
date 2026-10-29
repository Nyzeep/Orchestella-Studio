#!/usr/bin/env bash
# push_file.sh — 把本地文件可靠地送进 run-as 可写区（Windows Git Bash 环境）
#
# 为什么需要它：本机 adb(1.0.32)+Git Bash 下
#   1) `cmd < local` 与 `local | adb shell` 都会让 adb 进程收到 SIGTERM（stdin 转发失效）；
#   2) `adb shell` 单条命令总长有硬上限（实测 ~1KB，超过报 bad service name length）。
# 因此改为：本地 gzip+base64 → 按 800 字符分块 echo 追加 → 设备侧 base64 -d | gzip -dc 还原。
#
# 用法：./push_file.sh <本地路径> <设备侧相对 filesDir 的路径> [包名]
set -euo pipefail
export MSYS_NO_PATHCONV=1

SRC=${1:?源文件}
DST=${2:?目标相对路径，例如 files/rootfs/um-init}
PKG=${3:-dev.orchestella.umlrunner}
CHUNK=${CHUNK:-800}

[ -f "$SRC" ] || { echo "源文件不存在: $SRC" >&2; exit 1; }

TMPB64=$(mktemp)
gzip -9 -c "$SRC" | base64 -w0 > "$TMPB64"
N=$(wc -c < "$TMPB64")
TMPNAME=".push.$RANDOM.b64"

adb shell "run-as $PKG sh -c 'rm -f $TMPNAME'" >/dev/null 2>&1 || true

off=0
while [ "$off" -lt "$N" ]; do
  C=$(dd if="$TMPB64" bs=1 skip="$off" count="$CHUNK" 2>/dev/null)
  adb shell "run-as $PKG sh -c 'echo -n $C >> $TMPNAME'" >/dev/null 2>&1 || true
  off=$((off + CHUNK))
done

adb shell "run-as $PKG sh -c 'base64 -d $TMPNAME | gzip -dc > $DST; chmod 755 $DST; rm -f $TMPNAME; wc -c $DST'"
rm -f "$TMPB64"
