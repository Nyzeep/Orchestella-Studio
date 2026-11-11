#!/bin/sh
# ready.sh — the first command of the pre-baked fast-shell lane.
#
# Prints the T3 marker and READY, then either hands over a login shell or (for
# automated evidence runs) powers the guest off. Behaviour is selected with
# the kernel command line:
#
#   orchestella.ready=shell      (default) exec /bin/sh -l
#   orchestella.ready=poweroff   sync and power off after READY
#
# No package manager, no network configuration, no service manager.

set -u

PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH
LANG="${LANG:-C.UTF-8}"
LC_ALL="${LC_ALL:-C.UTF-8}"
export LANG LC_ALL

if read -r up _ < /proc/uptime 2>/dev/null; then :; else up=unknown; fi

action=shell
if [ -r /proc/cmdline ]; then
    for arg in $(cat /proc/cmdline); do
        case "$arg" in
            orchestella.ready=*) action="${arg#orchestella.ready=}" ;;
        esac
    done
fi

printf 'T3_READY uptime=%s action=%s\n' "$up" "$action"
printf 'READY\n'

case "$action" in
    poweroff)
        sync
        if command -v poweroff >/dev/null 2>&1; then
            exec poweroff -f
        fi
        # Last resort for initramfs-only testing.
        exec /bin/sh -c 'echo o > /proc/sysrq-trigger' 2>/dev/null || exit 0
        ;;
    shell|*)
        exec /bin/sh -l
        ;;
esac
