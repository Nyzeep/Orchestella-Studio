#!/bin/sh
# QEMU guest smoke probe (no data32m needed)
echo "QPROBE uname=$(uname -sr) uptime=$(cut -d' ' -f1 /proc/uptime)"
echo "QPROBE page=$(getconf PAGESIZE 2>/dev/null) nproc=$(nproc 2>/dev/null)"
clang --version | head -1
make --version | head -1
echo "QPROBE_DONE uptime=$(cut -d' ' -f1 /proc/uptime)"
