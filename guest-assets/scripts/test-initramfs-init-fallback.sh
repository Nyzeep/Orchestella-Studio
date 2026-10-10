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
debian="$fixture/debian"
mkdir -p "$debian/lib/systemd" "$debian/usr/lib" "$debian/usr/sbin" "$debian/etc/systemd/system"
printf '#!/bin/sh\nexit 0\n' > "$debian/lib/systemd/systemd"
chmod 0755 "$debian/lib/systemd/systemd"
printf 'ID=debian\nVERSION_ID=13\n' > "$debian/usr/lib/os-release"

install_official_debian_systemd_flavor "$debian"
if [ ! -x "$debian/usr/sbin/droidlayer-guest-agent-launcher" ]; then
    printf 'Expected Debian launcher absent from fixture root\n' >&2
    exit 1
fi
grep -Fq 'ExecStart=/usr/sbin/droidlayer-guest-agent-launcher' \
    "$debian/etc/systemd/system/droidlayer-guest-agent.service"
grep -Fq 'WantedBy=multi-user.target' \
    "$debian/etc/systemd/system/droidlayer-guest-agent.service"
[ -L "$debian/etc/systemd/system/multi-user.target.wants/droidlayer-guest-agent.service" ]
grep -Fq 'nameserver 10.0.2.3' "$debian/etc/resolv.conf"
grep -Fq 'nameserver 8.8.8.8' "$debian/etc/resolv.conf"

install_official_debian_systemd_flavor "$other"
[ ! -e "$other/etc/systemd/system/droidlayer-guest-agent.service" ]

# Identity and credentials branding tests (Ticket 03)
default_root="$fixture/default_root"
default_input="$fixture/default_input"
mkdir -p "$default_root" "$default_input"
provision_guest_identity_and_credentials "$default_root" "$default_input"
grep -Fqx 'orchestella' "$default_root/etc/hostname"
grep -Fq '127.0.0.1	localhost orchestella' "$default_root/etc/hosts"
grep -Fq '::1	localhost ip6-localhost ip6-loopback orchestella' "$default_root/etc/hosts"

custom_root="$fixture/custom_root"
custom_input="$fixture/custom_input"
mkdir -p "$custom_root" "$custom_input"
printf 'workstation\n' > "$custom_input/hostname"
printf 'SecretPass2026\n' > "$custom_input/guest.password"
printf 'developer\n' > "$custom_input/guest.user"
printf '1\n' > "$custom_input/guest.sudo"
provision_guest_identity_and_credentials "$custom_root" "$custom_input"
grep -Fqx 'workstation' "$custom_root/etc/hostname"
grep -Fq '127.0.0.1	localhost workstation' "$custom_root/etc/hosts"
grep -Fq 'developer ALL=(ALL) NOPASSWD:ALL' "$custom_root/etc/sudoers.d/99-orchestella-user"

uuid_root="$fixture/uuid_root"
uuid_input="$fixture/uuid_input"
mkdir -p "$uuid_root" "$uuid_input"
printf '1c283e7c-daa9-4192-974c-41f4cc139ea7\n' > "$uuid_input/hostname"
provision_guest_identity_and_credentials "$uuid_root" "$uuid_input"
grep -Fqx 'orchestella' "$uuid_root/etc/hostname"

printf 'ALPINE_BUSYBOX_INIT_FALLBACK_OK\n'
printf 'DEBIAN_SYSTEMD_FLAVOR_OK\n'
printf 'GUEST_IDENTITY_CREDENTIALS_OK\n'
