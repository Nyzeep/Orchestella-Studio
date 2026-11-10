#!/bin/busybox sh
# Orchestella Studio — U1 fast-shell T3 probe.
#
# Runs as the first user command after the fast init.  It proves that the
# guest can execute a real binary (busybox true) and prints the T3 marker with
# the guest uptime.  Then it either drops to a shell (um.shell=1) or powers
# the guest off after um.hold_ms (default 200 ms) so a batch harness can reap
# a clean exit.
#
# Markers (parsed by bench-fast.py):
#   U1_INFO kernel=<release> ncpus=<n>
#   U1_T2_READY uptime=<seconds>   (root + pseudo-fs ready; this script is the
#                                  first user command, after any switch_root)
#   U1_READY uptime=<seconds>
#   U1_CMD_ERROR ...              (a real command failed; run is failed)
#   UM_*                          (init contract; see initramfs/init)

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
printf 'U1_INFO kernel=%s ncpus=%s\n' "$kernel" "$ncpus"
# T2 is printed here, after the root (initramfs or switch_root target) and
# pseudo-filesystems are ready.  The init no longer prints it: for the
# umroot/switch_root path that would have been before the root switch.
printf 'U1_T2_READY uptime=%s\n' "$uptime"

if [ "$(param um.shell || :)" = 1 ]; then
    exec /bin/sh
fi

# T3 = a real command, not just a shell builtin, completed successfully.
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
