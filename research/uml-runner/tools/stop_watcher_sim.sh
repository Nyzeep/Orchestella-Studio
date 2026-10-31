#!/bin/sh
# ---------------------------------------------------------------------------
# tools/stop_watcher_sim.sh — RunnerActivity stop-file watcher 状态机的 shell 级回归
#
# 背景（T5 门1 cycle1 根因，2026-11-07 设备轮 §5.1）：
#   修复前 watchStop 的循环条件 `while (p.isAlive())` 先退出，进程死后遗留的
#   files/uml-stop 不被消费/清理；下一次启动的新实例把它当停止信号消费，
#   产生非正常退出（exit_code=1, reason=stop-file）。
#
# 修复后的 Java 状态机（RunnerActivity，与本脚本一一对应）：
#   A. 启动前 startup sweep：删除上一实例遗留的 uml-stop（权威修法——App 被杀时
#      watcher 线程随之消亡，死后兜底够不到，只有下一实例能清）。
#   B. 运行中 watcher：每 500ms 轮询，见 stop 文件 → SIGTERM → 3s 不退 SIGKILL
#      → 删除 stop 文件（消费路径，原有逻辑）。
#   C. waitFor 返回后 post-exit sweep：进程死后兜底删除（与 A 互补——关闭
#      「本轮生成、watcher 消费窗口已关」的竞态遗留）。
#
# 本脚本用后台 sh 进程模拟 UML 内核进程、文件模拟 uml-stop，断言：
#   S1  运行中 stop 文件被 watcher 消费并删除（原有行为不回归）；
#   S2  进程死后生成的 stop 文件被 post-exit sweep 清理（修复点，FIX=0 时会遗留）；
#   S3  上一实例陈旧 stop 文件被 startup sweep 清理，新实例不误消费（修复点）；
#   S4  正常自然退出路径无 stop 文件残留。
#
# 运行：sh tools/stop_watcher_sim.sh   （本机可跑，无设备依赖；POSIX sh）
# ---------------------------------------------------------------------------

set -u

WORK=$(mktemp -d)
STOP="$WORK/uml-stop"
TICK=0.1
pass=0
fail=0

cleanup() { [ -n "${PID:-}" ] && kill -9 "$PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

ok()   { pass=$((pass + 1)); echo "  PASS: $1"; }
bad()  { fail=$((fail + 1)); echo "  FAIL: $1"; }
assert_no_stop() { [ ! -f "$STOP" ] && ok "$1" || bad "$1（stop 文件仍存在）"; }
assert_stop()    { [ -f "$STOP" ]    && ok "$1" || bad "$1（stop 文件意外消失）"; }

# --- 被测状态机（shell 镜像） ----------------------------------------------

# startup sweep：对应 run() 开头的 stopFile.delete()
startup_sweep() {
  [ -f "$STOP" ] && { echo "  [runner] 启动期清理陈旧 stop 文件"; rm -f "$STOP"; }
  return 0
}

# watcher：对应 watchStop(Process p) 的 while (p.isAlive()) 循环
watch_loop() { # $1=PID  $2=FIX(1=修复版含消费后删除)
  _pid=$1; _fix=$2; _stopping=0
  while kill -0 "$_pid" 2>/dev/null; do
    sleep "$TICK"
    if [ -f "$STOP" ] && [ "$_stopping" -eq 0 ]; then
      _stopping=1
      echo "  [watcher] 检测到 stop 文件 -> SIGTERM"
      kill -TERM "$_pid" 2>/dev/null
      _i=0
      while kill -0 "$_pid" 2>/dev/null && [ "$_i" -lt 30 ]; do sleep 0.1; _i=$((_i + 1)); done
      if kill -0 "$_pid" 2>/dev/null; then
        echo "  [watcher] 3s 未退 -> SIGKILL"
        kill -KILL "$_pid" 2>/dev/null
      fi
      [ "$_fix" -eq 1 ] && rm -f "$STOP"   # 消费后删除（原逻辑即有）
    fi
  done
  return 0
}

# post-exit sweep：对应 waitFor 返回后的 stopFile.delete()（本轮新增）
post_exit_sweep() { # $1=FIX
  [ "$1" -eq 1 ] && [ -f "$STOP" ] && {
    echo "  [runner] 进程已退出，post-exit 清理遗留 stop 文件"
    rm -f "$STOP"
  }
  return 0
}

spawn_long()   { sh -c 'while :; do sleep 0.2; done' & PID=$!; }
spawn_short()  { sh -c 'sleep 0.4'                    & PID=$!; }
wait_dead()    { _i=0; while kill -0 "$PID" 2>/dev/null && [ "$_i" -lt 50 ]; do sleep 0.1; _i=$((_i + 1)); done; }

echo "== S1 运行中 stop：watcher 消费路径（原有行为，不回归） =="
startup_sweep
spawn_long
touch "$STOP"
watch_loop "$PID" 1
wait_dead
assert_no_stop "S1: stop 文件被 watcher 消费并删除"

echo "== S2 进程死亡竞态：死后生成的 stop 文件（修复点） =="
run_s2() { # $1=FIX
  rm -f "$STOP"; startup_sweep
  spawn_short                    # 模拟 UML 进程先自行死亡
  watch_loop "$PID" "$1"         # watcher 随 isAlive() 退出，期间无 stop 文件
  touch "$STOP"                  # watcher 退出后才出现的 stop（T3/T5 轮次交接竞态）
  post_exit_sweep "$1"
  wait_dead
}
run_s2 0
assert_stop   "S2[FIX=0 旧逻辑复现]: 死后 stop 文件遗留（即 T5 门1 cycle1 根因）"
run_s2 1
assert_no_stop "S2[FIX=1 修复版]: post-exit sweep 清理了死后遗留"

echo "== S3 陈旧遗留 + 新实例：startup sweep（修复点） =="
rm -f "$STOP"
touch "$STOP"                  # 上一实例留下的陈旧 stop（App 被杀路径，死后兜底够不到）
startup_sweep
spawn_long                     # 新实例：startup sweep 已清，watcher 无 stop 可消费
watch_loop "$PID" 1 & WPID=$!  # 新实例的 watcher 并行运行
sleep 1
if kill -0 "$PID" 2>/dev/null; then
  ok "S3: 新实例未被陈旧 stop 误杀（watcher 并行下存活 >1s）"
else
  bad "S3: 新实例被陈旧 stop 误杀"
fi
kill -9 "$PID" 2>/dev/null; wait "$PID" 2>/dev/null
kill "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
assert_no_stop "S3: 陈旧 stop 已被启动期清理"

echo "== S4 自然退出路径无残留 =="
rm -f "$STOP"; startup_sweep
spawn_short
watch_loop "$PID" 1
wait_dead
post_exit_sweep 1
assert_no_stop "S4: 自然退出无 stop 残留"

echo
echo "结果: pass=$pass fail=$fail"
[ "$fail" -eq 0 ] && echo "STOP_WATCHER_SIM_OK" && exit 0
exit 1
