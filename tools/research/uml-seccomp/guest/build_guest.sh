#!/bin/sh
# Build the U3 guest init (static) plus a hostfs tree and a ubd0 ext4 image.
# Usage: build_guest.sh [OUTDIR] [KERNEL_IMAGE_SIZE_MB]
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT=${1:-/tmp/uml-seccomp-guest}
IMG_MB=${2:-32}

mkdir -p "$OUT/rootfs"
cc -static -O2 -Wall -Wextra -o "$OUT/rootfs/init" "$HERE/init.c"
chmod 0755 "$OUT/rootfs/init"

rm -f "$OUT/rootfs.img"
mkfs.ext4 -q -F -b 4096 -d "$OUT/rootfs" "$OUT/rootfs.img" "${IMG_MB}M"

echo "GUEST_BUILD_OK rootfs=$OUT/rootfs image=$OUT/rootfs.img"
