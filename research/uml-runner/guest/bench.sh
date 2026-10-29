#!/bin/busybox sh
# bench.sh — D10 基准（UML guest 与宿主 native 共用同一 busybox 二进制）
#
# 为什么必须同一二进制：宿主 toybox 与 guest busybox 的 sha256/find 实现不同，
# 混用测的是"工具实现差异"而不是"UML 开销"（首轮 3.75x 即由此污染）。
#
# 时间源：/proc/uptime（10ms 粒度）。为压住量化误差，每项负载重复多次后取平均。
# 输出：BENCH <key>=<ms> 单行一项，便于宿主侧解析合并。
#
# 可覆盖变量：
#   BB_LOADER   非空时用 `<loader> <busybox>` 调用（宿主态跑 Alpine 动态 busybox 需 musl loader）
#   BB_BIN      busybox 路径
#   BENCH_ROOT  夹具根（含 data32m 与 tree/）
#   BENCH_FILES forkexec 迭代数
#   REPS        compute 重复次数
#   SREPS       syscall 重复次数

LOADER=${BB_LOADER:-}
BBIN=${BB_BIN:-/bin/busybox}
ROOT=${BENCH_ROOT:-/bench}
FILES=${BENCH_FILES:-500}
REPS=${REPS:-6}
SREPS=${SREPS:-25}

bb() { if [ -n "$LOADER" ]; then "$LOADER" "$BBIN" "$@"; else "$BBIN" "$@"; fi; }

now_ms() { bb awk '{printf "%d", $1*1000}' /proc/uptime; }

avg() { echo $(( $1 / $2 )); }

# --- 1) compute：sha256 x REPS（CPU + 顺序读） ---
if [ -f "$ROOT/data32m" ]; then
  t0=$(now_ms)
  i=0
  while [ "$i" -lt "$REPS" ]; do bb sha256sum "$ROOT/data32m" >/dev/null 2>&1; i=$((i+1)); done
  t1=$(now_ms)
  echo "BENCH compute=$(avg $((t1-t0)) "$REPS")"
else
  echo "BENCH compute=-1"
fi

# --- 2) syscall：find 2000 文件 x SREPS（openat/getdents/stat） ---
if [ -d "$ROOT/tree" ]; then
  t0=$(now_ms)
  i=0
  while [ "$i" -lt "$SREPS" ]; do bb find "$ROOT/tree" -type f >/dev/null 2>&1; i=$((i+1)); done
  t1=$(now_ms)
  echo "BENCH syscall=$(avg $((t1-t0)) "$SREPS")"
  echo "BENCH syscall_count=$(bb find "$ROOT/tree" -type f 2>/dev/null | bb wc -l)"
else
  echo "BENCH syscall=-1"
fi

# --- 3) loopbase：纯 shell 循环，无 exec（剥离循环自身开销） ---
t0=$(now_ms)
i=0
while [ "$i" -lt "$FILES" ]; do :; i=$((i+1)); done
t1=$(now_ms)
echo "BENCH loopbase=$((t1-t0))"

# --- 4) forkexec：N 次 fork+exec busybox true ---
t0=$(now_ms)
i=0
while [ "$i" -lt "$FILES" ]; do bb true 2>/dev/null; i=$((i+1)); done
t1=$(now_ms)
echo "BENCH forkexec=$((t1-t0))"
echo "BENCH forkexec_n=$FILES"
echo "BENCH:DONE"
