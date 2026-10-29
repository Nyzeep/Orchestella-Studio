#!/usr/bin/env bash
# run_bench.sh — D10 交错基准驱动（native vs UML guest，7 轮交错取中位数）
#
# 方法学对齐 fork harness（README.md:223-229,269-278）：
#   - 同一轮内 native → UML 相邻执行，跨轮重复，避免热漂移；
#   - 取中位数，不取均值；
#   - compute 作为校验器：跨条件偏差 >5% 或缺采 → 整表作废。
#
# 时间源：两侧统一 /proc/uptime（10ms 分辨率），由 bench.sh 内部采样。
#
# 用法：./run_bench.sh [轮数]   结果同时打印并写 out/bench-<UTC>.txt
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROUNDS=${1:-7}
NATIVE_CMD='BB_LOADER=/data/local/tmp/ld-musl BB_BIN=/data/local/tmp/busybox BENCH_ROOT=/data/local/tmp/bench BENCH_FILES=500 REPS=6 SREPS=25 sh /data/local/tmp/bench.sh'
PKG=${PKG:-dev.orchestella.umlrunner}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT_DIR=${OUT_DIR:-$HERE/../out}
mkdir -p "$OUT_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$OUT_DIR/bench-$STAMP.txt"
: > "$OUT"

log() { printf '%s\n' "$*" | tee -a "$OUT"; }

log "# D10 interleaved benchmark  rounds=$ROUNDS  utc=$STAMP"
log "# device: $(adb shell getprop ro.product.model | tr -d '\r')  android=$(adb shell getprop ro.build.version.release | tr -d '\r')  host_pagesize=$(adb shell getconf PAGESIZE | tr -d '\r')"

for r in $(seq 1 "$ROUNDS"); do
  log ""
  log "## round $r"

  # ---- native（同一 busybox 二进制 + musl loader，与 guest 侧等价）----
  adb shell "$NATIVE_CMD" 2>/dev/null \
    | tr -d '\r' | grep '^BENCH' | sed 's/^BENCH /BENCH native./' | tee -a "$OUT"

  # ---- UML ----
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb shell "am start -n $PKG/.RunnerActivity" >/dev/null 2>&1

  # 轮询等待 BENCH:DONE（上限 60s，每 1s 一次）
  done_marker=0
  for _ in $(seq 1 60); do
    sleep 1
    if adb shell "run-as $PKG cat files/uml-console.log" 2>/dev/null | grep -q 'BENCH:DONE'; then
      done_marker=1; break
    fi
  done

  if [ "$done_marker" = "1" ]; then
    adb shell "run-as $PKG cat files/uml-console.log" 2>/dev/null | tr -d '\r' | grep '^BENCH' \
      | sed 's/^BENCH /BENCH uml./' | tee -a "$OUT"
    adb shell "run-as $PKG cat files/uml-exit.txt" 2>/dev/null | tr -d '\r' | grep -E '^(exit_code|duration_ms)=' \
      | sed 's/^/BENCH uml.boot./' | tee -a "$OUT"
  else
    log "BENCH uml.TIMEOUT=1"
    adb shell "run-as $PKG sh -c 'tail -20 files/uml-console.log'" 2>/dev/null | tr -d '\r' | sed 's/^/# /' | tee -a "$OUT"
  fi

  sleep 2
done

log ""
log "# done -> $OUT"
