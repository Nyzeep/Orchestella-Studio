#!/bin/busybox sh
# UML guest 自证 + D10 基准入口（hostfs 根下的 /um-command.sh）
# 注意：hostfs 下脚本 shebang 不能用 `#!/bin/sh`（绝对符号链接解析会 ENOENT），必须直呼 busybox。

echo "===== UML GUEST ALIVE ====="
echo "BENCH boot_uptime_ms=$(/bin/busybox awk "{printf \"%d\", \$1*1000}" /proc/uptime)"
echo "--- uname ---"
uname -a
echo "--- id ---"
id
echo "--- pagesize ---"
getconf PAGESIZE 2>/dev/null || echo "getconf-unavailable"
echo "--- cpu ---"
grep -c '^processor' /proc/cpuinfo 2>/dev/null
echo "--- mounts ---"
cat /proc/mounts 2>/dev/null | head -8
echo "--- write test ---"
echo hello-from-guest > /tmp/uml-write-test.txt 2>&1 && cat /tmp/uml-write-test.txt || echo "write-failed"
echo "--- exec symlink check (3x /bin/true) ---"
n=0
while [ "$n" -lt 3 ]; do /bin/true && echo "true_ok" || echo "true_fail"; n=$((n+1)); done
echo "===== BENCH BEGIN ====="
if [ -f /bench.sh ]; then
  REPS=6 SREPS=25 BENCH_FILES=500 BENCH_ROOT=/bench /bin/busybox sh /bench.sh
else
  echo "BENCH:NO_SCRIPT"
fi
echo "===== UML GUEST DONE ====="
exit 0
