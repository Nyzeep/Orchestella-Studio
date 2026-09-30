#!/bin/sh
set -eu

[ -n "${TICKET26_WORK_ROOT:-}" ] && [ -d "$TICKET26_WORK_ROOT" ] || exit 64
init_source="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)/initramfs/init"
functions="$(sed -n '/^is_official_alpine_busybox_init() {/,/^mount -t devtmpfs/{ /^mount -t devtmpfs/!p; }' "$init_source")"
[ -n "$functions" ] || exit 65
eval "$functions"

fixture="$(mktemp -d "$TICKET26_WORK_ROOT/init-fallback-unit-XXXXXXXX")"
root="$fixture/alpine"
mkdir -p "$root/bin" "$root/sbin" "$root/usr/lib" "$root/usr/sbin" "$root/etc"
printf '#!/bin/sh\nexit 0\n' > "$root/bin/busybox"
chmod 0755 "$root/bin/busybox"
ln -s ../bin/busybox "$root/sbin/init"
printf 'ID=alpine\nVERSION_ID=3.24.1\n' > "$root/usr/lib/os-release"

install_official_alpine_busybox_init_fallback "$root"
if [ ! -x "$root/usr/sbin/droidlayer-guest-agent-launcher" ]; then
    printf 'Expected Alpine fallback launcher absent from fixture root\n' >&2
    exit 1
fi
grep -Fqx '::respawn:/usr/sbin/droidlayer-guest-agent-launcher' "$root/etc/inittab"
grep -Fqx '::respawn:/bin/sh' "$root/etc/inittab"

other="$fixture/other"
mkdir -p "$other/bin" "$other/sbin" "$other/usr/lib" "$other/etc"
printf '#!/bin/sh\nexit 0\n' > "$other/bin/busybox"
chmod 0755 "$other/bin/busybox"
ln -s ../bin/busybox "$other/sbin/init"
printf 'ID=other\nVERSION_ID=3.24.1\n' > "$other/usr/lib/os-release"
install_official_alpine_busybox_init_fallback "$other"
[ ! -e "$other/etc/inittab" ]
printf 'ALPINE_BUSYBOX_INIT_FALLBACK_OK\n'
