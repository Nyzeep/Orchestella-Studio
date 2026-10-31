#!/bin/sh
# T3 burn loop: continuous CPU+IO on the ext4 rootfs (kill -9 target workload)
echo "T3_BURN_START uptime=$(cut -d' ' -f1 /proc/uptime)"
N=0
while :; do
  sha256sum /work/data32m > /dev/null
  N=$((N+1))
  if [ $((N % 5)) -eq 0 ]; then
    echo "T3_BURN_TICK n=$N uptime=$(cut -d' ' -f1 /proc/uptime)"
  fi
done
