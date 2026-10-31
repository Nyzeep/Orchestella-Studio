#!/system/bin/sh
# device-side QEMU runner for T4 matrix (avoid adb quoting hell)
# usage: qrun.sh <smp> <logfile>
SMP=$1
LOG=$2
cd /data/local/tmp/qemu-bin || exit 3
exec ./qemu-system-aarch64 -machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp "$SMP" -m 6371M -nodefaults -display none -monitor none -no-reboot -kernel assets/Image -append "root=/dev/vda rw init=/init work=makebench j=$SMP console=ttyAMA0" -drive file=/data/local/tmp/c200-qemu.ext4,format=raw,if=virtio -serial "file:$LOG"
