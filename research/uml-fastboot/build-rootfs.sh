#!/usr/bin/env bash
# Orchestella Studio — U1 fast-profile rootfs fixtures.
#
# Builds, for one architecture:
#   out/rootfs-<arch>/            hostfs directory (Alpine minirootfs + /um-command.sh)
#   out/rootfs-<arch>.ext4        ext4 image for ubd0=/dev/ubda
#
# The rootfs is deliberately a stock Alpine minirootfs: U1 measures the boot
# path, not the prebake.  U2 (research/uml-rootfs-prebake) owns the product
# prebaked rootfs.  Network and package-manager use are build-time only.
#
# Usage: ./build-rootfs.sh [aarch64|x86_64]
# Requires: curl, tar, mkfs.ext4 (e2fsprogs), truncate.
set -euo pipefail
LC_ALL=C
export LC_ALL

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OUT=${OUT:-"$HERE/out"}
ARCH=${1:-x86_64}
ALPINE_VERSION=${ALPINE_VERSION:-3.22.2}
ALPINE_BASE=${ALPINE_BASE:-"https://dl-cdn.alpinelinux.org/alpine/v3.22/releases"}

case "$ARCH" in
    aarch64) TARBALL_SHA="6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489" ;;
    x86_64)  TARBALL_SHA="412454ed98025ad9cd910d13f3d448b184e81502baa83180e89f98d0f13674be" ;;
    *) echo "unsupported arch: $ARCH (aarch64|x86_64)" >&2; exit 2 ;;
esac

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1" >&2; exit 1; }; }
need curl; need tar; need mkfs.ext4; need truncate

mkdir -p "$OUT/dl"
tarball="$OUT/dl/alpine-minirootfs-$ALPINE_VERSION-$ARCH.tar.gz"
if [ ! -f "$tarball" ]; then
    curl -fsSL -o "$tarball.tmp" \
        "$ALPINE_BASE/$ARCH/alpine-minirootfs-$ALPINE_VERSION-$ARCH.tar.gz"
    mv "$tarball.tmp" "$tarball"
fi
actual=$(sha256sum "$tarball" | awk '{print $1}')
[ "$actual" = "$TARBALL_SHA" ] || {
    echo "Alpine tarball sha256 mismatch: $actual != $TARBALL_SHA" >&2
    exit 1
}

rootfs="$OUT/rootfs-$ARCH"
rm -rf "$rootfs"
mkdir -p "$rootfs"
tar xzf "$tarball" -C "$rootfs"
install -m 0755 "$HERE/initramfs/um-command.sh" "$rootfs/um-command.sh"
# /init is the same mount contract as the initramfs init; when this rootfs is
# booted directly (no built-in initramfs) it mounts proc/sys/dev/cgroup2 and
# then execs /um-command.sh.
install -m 0755 "$HERE/initramfs/init" "$rootfs/init"
mkdir -p "$rootfs/proc" "$rootfs/sys" "$rootfs/dev" "$rootfs/tmp" "$rootfs/newroot"
chmod 1777 "$rootfs/tmp"

image="$OUT/rootfs-$ARCH.ext4"
rm -f "$image"
truncate -s 64M "$image"
mkfs.ext4 -q -F -b 4096 -L u1-fastroot -d "$rootfs" "$image"

{
    printf 'arch=%s\n' "$ARCH"
    printf 'alpine_version=%s\n' "$ALPINE_VERSION"
    printf 'alpine_tarball_sha256=%s\n' "$TARBALL_SHA"
    printf 'rootfs_tree_sha256=%s\n' "$(find "$rootfs" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')"
    printf 'rootfs_ext4_sha256=%s\n' "$(sha256sum "$image" | awk '{print $1}')"
    printf 'rootfs_ext4_size=%s\n' "$(stat -c %s "$image")"
} > "$OUT/rootfs-$ARCH.env"

cat "$OUT/rootfs-$ARCH.env"
echo "ROOTFS_OK $ARCH"
