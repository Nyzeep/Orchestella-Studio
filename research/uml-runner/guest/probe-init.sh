#!/bin/busybox sh
# hostfs 诊断 init：验证脚本 exec、绝对符号链接解析、文件可见性
echo "PROBE_INIT_OK"
echo "--- cmdline ---"
cat /proc/cmdline
echo "--- ls / ---"
ls /
echo "--- ls -l /bin/sh ---"
ls -l /bin/sh
echo "--- readlink /bin/sh ---"
readlink /bin/sh
echo "--- test -x /bin/sh ---"
if [ -x /bin/sh ]; then echo "bin_sh_exec_yes"; else echo "bin_sh_exec_no"; fi
echo "--- test -r /um-init ---"
if [ -r /um-init ]; then echo "um_init_readable_yes"; else echo "um_init_readable_no"; fi
echo "--- /bin/busybox present ---"
ls -l /bin/busybox
echo "--- applet via busybox multi-call ---"
/bin/busybox uname -a
echo "PROBE_DONE"
poweroff -f
while :; do sleep 3600; done
