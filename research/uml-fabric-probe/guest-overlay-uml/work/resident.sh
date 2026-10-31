#!/bin/sh
# T5 gate2 resident: heartbeat every 30s, RES_TICKS from cmdline (default 120 = 60min)
echo "RESIDENT_START ticks=$RES_TICKS uptime=$(cut -d' ' -f1 /proc/uptime)"
i=0
while [ $i -lt $RES_TICKS ]; do
  MT=$(awk '/MemTotal/{print $2}' /proc/meminfo)
  MF=$(awk '/MemFree/{print $2}' /proc/meminfo)
  LB=$(awk '{print $1}' /proc/loadavg)
  echo "RESIDENT_TICK i=$i uptime=$(cut -d' ' -f1 /proc/uptime) memtotal_kb=$MT memfree_kb=$MF load=$LB"
  sleep 30
  i=$((i+1))
done
echo "RESIDENT_DONE uptime=$(cut -d' ' -f1 /proc/uptime)"
