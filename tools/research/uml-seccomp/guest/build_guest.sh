#!/bin/sh
# Build the U3 guest init (static) plus a hostfs tree and a ubd0 ext4 image.
# Usage: build_guest.sh [OUTDIR] [KERNEL_IMAGE_SIZE_MB]
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT=${1:-/tmp/uml-seccomp-guest}
IMG_MB=${2:-32}

mkdir -p "$OUT/rootfs"
# -static aarch64 init for the UML guest; 4K-max-page-size keeps PT_LOAD alignment
# safe on 4K-page hosts. Compiler order is deliberate (device-b 2026-11-12 evidence):
#   1. aarch64-linux-gnu-gcc (glibc static, zero GNU_PROPERTY notes)  → boots
#   2. NDK clang (bionic static) → UML binfmt rejects it:
#      "Requested init /init failed (error -22)" — the bionic ELF carries
#      BTI/PAC GNU_PROPERTY notes the UML 7.2-rc4 arm64 loader refuses. Even
#      with BTI suppressed it is unsafe to assume; keep glibc/musl first.
#   3. bare cc → wrong arch (x86_64) on a x86_64 build host.
if command -v aarch64-linux-gnu-gcc >/dev/null 2>&1; then
    CC=aarch64-linux-gnu-gcc
elif [ -n "${NDK_CLANG:-}" ]; then
    CC=$NDK_CLANG
else
    CC=cc
fi
$CC -static -O2 -Wall -Wextra -Wl,-z,max-page-size=4096 -o "$OUT/rootfs/init" "$HERE/init.c"
chmod 0755 "$OUT/rootfs/init"

# hostfs tarball packed HERE (POSIX host) — the exec bit is real. Packing the same
# tree from a Windows/DrvFs checkout yields 0644 entries and the guest init then
# dies with "Requested init /init failed (error -13)" (EACCES) on device.
rm -f "$OUT/rootfs.tar.gz"
tar -czf "$OUT/rootfs.tar.gz" -C "$OUT/rootfs" .

rm -f "$OUT/rootfs.img"
mkfs.ext4 -q -F -b 4096 -d "$OUT/rootfs" "$OUT/rootfs.img" "${IMG_MB}M"

echo "GUEST_BUILD_OK rootfs=$OUT/rootfs tar=$OUT/rootfs.tar.gz image=$OUT/rootfs.img"
