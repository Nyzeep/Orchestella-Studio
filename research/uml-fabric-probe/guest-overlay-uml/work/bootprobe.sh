#!/bin/sh
# T1/T5-gate1 boot probe: marker + sanity sha256, then clean exit via init
echo "BOOTPROBE uname=$(uname -sr) uptime=$(cut -d' ' -f1 /proc/uptime)"
sha256sum /work/data32m > /tmp/bp.out 2>/dev/null && echo "BOOTPROBE_SHA_OK $(cat /tmp/bp.out)"
echo "BOOTPROBE_DONE uptime=$(cut -d' ' -f1 /proc/uptime)"
