#!/bin/busybox sh
# Orchestella Studio — full-smp-arm64 UML initramfs fallback command.

set -u
PATH=/bin:/sbin:/usr/bin:/usr/sbin
export PATH

param() {
    for word in $(cat /proc/cmdline 2>/dev/null); do
        case "$word" in
            "$1"=*) printf '%s' "${word#*=}"; return 0 ;;
        esac
    done
    return 1
}

hold_ms=$(param um.hold_ms || printf 200)
case "$hold_ms" in
    ''|*[!0-9]*) hold_ms=200 ;;
esac

ncpus=$(grep -c '^processor' /proc/cpuinfo 2>/dev/null || :)
[ -n "$ncpus" ] || ncpus=1
kernel=$(uname -r 2>/dev/null || printf unknown)
uptime=$(cut -d' ' -f1 /proc/uptime 2>/dev/null || printf unknown)
printf 'U1_INFO kernel=%s ncpus=%s profile=full-smp-arm64\n' "$kernel" "$ncpus"
printf 'U1_T2_READY uptime=%s\n' "$uptime"

if [ "$(param um.shell || :)" = 1 ]; then
    exec /bin/sh
fi

if /bin/busybox true; then
    :
else
    printf 'U1_CMD_ERROR cmd=busybox_true\n' >&2
    poweroff -f 2>/dev/null || :
    while :; do sleep 3600; done
fi

printf 'U1_READY uptime=%s\n' "$uptime"

if [ "$hold_ms" -ge 1000 ]; then
    sleep $((hold_ms / 1000)) 2>/dev/null || sleep 1
else
    sleep "0.$(printf '%03d' "$hold_ms")" 2>/dev/null || sleep 1
fi
sync 2>/dev/null || :
poweroff -f 2>/dev/null || :
while :; do sleep 3600; done
