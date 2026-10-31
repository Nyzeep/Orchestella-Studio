#!/bin/sh
# T2 (G1 multicore gate): nproc + parallel vs sequential sha256(32MB), uptime timing
# walls: SINGLE=1 hash; PARALLEL=4 workers x 3 hashes; SEQ=12 hashes sequential
# gate: wall(PARALLEL)/wall(SEQ) ~ 0.25 (ideal), allowed <= 0.3375 (1.35x sched loss)
D=/work/data32m
REPS=3
echo "T2_BEGIN nproc=$(nproc) timesrc=uptime10ms uptime=$(cut -d' ' -f1 /proc/uptime)"
mkdir -p /tmp
T0=$(cut -d' ' -f1 /proc/uptime)
sha256sum $D > /dev/null
T1=$(cut -d' ' -f1 /proc/uptime)
echo "T2_SINGLE  t0=$T0 t1=$T1 wall=$(echo "$T0 $T1" | awk '{printf "%.4f", $2-$1}')"
T0=$(cut -d' ' -f1 /proc/uptime)
for i in 1 2 3 4; do
  ( k=0; while [ $k -lt $REPS ]; do sha256sum $D > /dev/null; k=$((k+1)); done ) &
done
wait
T1=$(cut -d' ' -f1 /proc/uptime)
echo "T2_PARALLEL t0=$T0 t1=$T1 wall=$(echo "$T0 $T1" | awk '{printf "%.4f", $2-$1}')"
T0=$(cut -d' ' -f1 /proc/uptime)
k=0
while [ $k -lt 12 ]; do sha256sum $D > /dev/null; k=$((k+1)); done
T1=$(cut -d' ' -f1 /proc/uptime)
echo "T2_SEQ12   t0=$T0 t1=$T1 wall=$(echo "$T0 $T1" | awk '{printf "%.4f", $2-$1}')"
echo "T2_ALIVE yes uptime=$(cut -d' ' -f1 /proc/uptime)"
echo "T2_ALIVE yes uptime=$(cut -d' ' -f1 /proc/uptime)"
