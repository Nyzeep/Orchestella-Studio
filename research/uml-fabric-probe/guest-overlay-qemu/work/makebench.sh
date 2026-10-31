#!/bin/sh
# T4 makebench: clean build of C200 fixture with make -j $RES_J inside guest
cd /work/c200 || { echo "MAKEBENCH_FAIL no fixture dir"; exit 3; }
J=${RES_J:-1}
echo "MAKEBENCH_START j=$J files=$(ls f*.c | wc -l) uptime=$(cut -d' ' -f1 /proc/uptime)"
rm -f *.o
T0=$(date +%s.%N)
make -j "$J" CC=clang CFLAGS="-c -O1"
RC=$?
T1=$(date +%s.%N)
echo "MAKEBENCH_END rc=$RC t0=$T0 t1=$T1 wall=$(echo "$T0 $T1" | awk '{printf "%.3f", $2-$1}')"
NOBJ=$(ls *.o 2>/dev/null | wc -l)
DIG=$(ls *.o 2>/dev/null | sort | xargs sha256sum 2>/dev/null | sha256sum | awk '{print $1}')
echo "MAKEBENCH_OBJ n=$NOBJ digest=$DIG"
echo "MAKEBENCH_DONE rc=$RC uptime=$(cut -d' ' -f1 /proc/uptime)"
