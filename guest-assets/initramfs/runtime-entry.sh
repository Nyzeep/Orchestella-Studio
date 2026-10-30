mount -t devtmpfs devtmpfs /dev || fail mount-devtmpfs
mount -t proc proc /proc || fail mount-proc
mount -t sysfs sysfs /sys || fail mount-sysfs

ROOT_DEVICE="$(kernel_argument droidlayer.root_device || true)"
case "$ROOT_DEVICE" in /dev/vd[a-z]) ;; *) fail invalid-root-device ;; esac
for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -b "$ROOT_DEVICE" ] && break
    sleep 1
done
[ -b "$ROOT_DEVICE" ] || fail root-device-missing
install_strategy_neutral_bridge_devices

boot_guest_root() {
    configure_fstab_mounts /newroot
    mkdir -p /newroot/dev /newroot/proc /newroot/sys /newroot/run
    if [ -x /sbin/resize2fs ]; then /sbin/resize2fs "$ROOT_DEVICE" 2>/dev/null || true; fi
    mkdir -p /newroot/usr/sbin
    cp /opt/droidlayer/guest-agent /newroot/usr/sbin/droidlayer-guest-agent || fail guest-agent-install
    chmod 0755 /newroot/usr/sbin/droidlayer-guest-agent
    if [ -f /opt/orchestella/proxy-relay ]; then
        cp /opt/orchestella/proxy-relay /newroot/usr/sbin/orchestella-proxy-relay || fail proxy-relay-install
        chmod 0755 /newroot/usr/sbin/orchestella-proxy-relay
    fi
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=runtime-services status=running'
    install_native_guest_runtime /newroot
    install_guest_shell_profile /newroot
    install_guest_terminal_integration /newroot
    if ! install_guest_optional_features /newroot; then
        log 'ORCHESTELLA_OPTIONAL_FEATURES phase=payload-install status=failed base-linux=preserved'
    fi
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=runtime-services status=complete'
    if [ "$(kernel_argument droidlayer.poweroff || true)" = 1 ]; then
        umount /newroot || fail unmount-provisioned-root
        poweroff -f || true
        while :; do sleep 3600; done
    fi
    # Flavor service setup may replace resolv.conf. Reapply the proven host-supplied DNS
    # and BusyBox eth0/route configuration immediately before the native-init handoff.
    configure_guest_network /newroot
    # systemd and OpenRC need volatile runtime state and a real cgroup hierarchy.
    mount -t tmpfs -o mode=0755 tmpfs /newroot/run || fail mount-guest-run
    mkdir -p /sys/fs/cgroup
    mount -t cgroup2 cgroup2 /sys/fs/cgroup || fail mount-guest-cgroup2
    # No package/SSH/dbus readiness is implied by an assembled root volume.
    log 'ORCHESTELLA_GUEST_BOOT phase=native-init runtime=unverified'
    mount --move /dev /newroot/dev || fail move-dev
    mount --move /proc /newroot/proc || fail move-proc
    mount --move /sys /newroot/sys || fail move-sys
    exec /sbin/switch_root /newroot /usr/sbin/orchestella-boot
}

mkdir -p /newroot
RESUME_ASSEMBLY=0
if mount -t ext4 "$ROOT_DEVICE" /newroot 2>/dev/null; then
    if grep -Fqx 'provisioning_version=1' /newroot/var/lib/droidlayer/provisioned-v1 2>/dev/null; then
        log 'DROIDLAYER_ROOT_VOLUME_RESTART version=1 runtime=unverified'
        if [ -f /opt/droidlayer/embedded-input/.active ]; then
            stage_pending_guest_credentials /newroot /opt/droidlayer/embedded-input
            stage_guest_package_repositories /newroot /opt/droidlayer/embedded-input
        else
            restart_input_tag="$(kernel_argument droidlayer.input_tag || true)"
            case "$restart_input_tag" in ''|*[!A-Za-z0-9._-]*) fail invalid-input-tag ;; esac
            mkdir -p /run/droidlayer-input
            if mount -t 9p -o trans=virtio,version=9p2000.L,ro "$restart_input_tag" /run/droidlayer-input 2>/dev/null; then
                stage_pending_guest_credentials /newroot /run/droidlayer-input
                stage_guest_package_repositories /newroot /run/droidlayer-input
            fi
        fi
        boot_guest_root
        fail switch-root
    elif grep -Fqx 'assembly_version=2' /newroot/var/lib/droidlayer/assembly-in-progress 2>/dev/null; then
        # Only our explicit interrupted-assembly marker permits re-extraction, never mkfs.
        RESUME_ASSEMBLY=1
        log 'DROIDLAYER_ROOT_VOLUME_RESUME phase=assembly preserving-filesystem'
    else
        log 'DROIDLAYER_ROOT_VOLUME_REFUSED reason=unrecognized-existing-filesystem data=preserved'
        fail existing-root-volume-unrecognized
    fi
else
    ROOT_FILESYSTEM_TYPE="$(/sbin/blkid -o value -s TYPE "$ROOT_DEVICE" 2>/dev/null || true)"
    [ -z "$ROOT_FILESYSTEM_TYPE" ] || fail existing-root-volume-unmountable
    # Only a volume marked at creation by the host may be formatted. Unknown/legacy data is preserved.
    creation_signature="$(dd if="$ROOT_DEVICE" bs=1 count=32 2>/dev/null)"
    [ "$creation_signature" = ORCHESTELLA_EMPTY_ROOT_VOLUME_V2 ] || fail root-volume-not-created-for-provisioning
fi

INPUT_TAG="$(kernel_argument droidlayer.input_tag || true)"
case "$INPUT_TAG" in *[!A-Za-z0-9._-]*|'') fail invalid-input-tag ;; esac
mkdir -p /run/droidlayer-input
INPUT_DIR=""
if mount -t 9p -o trans=virtio,version=9p2000.L,ro "$INPUT_TAG" /run/droidlayer-input 2>/dev/null; then
    INPUT_DIR=/run/droidlayer-input
elif [ -f /opt/droidlayer/embedded-input/.active ]; then
    INPUT_DIR=/opt/droidlayer/embedded-input
    log 'using embedded provision input'
else
    fail mount-input
fi
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=input-verification status=running'
verify_rootfs_input "$INPUT_DIR"
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=input-verification status=verified'
TOKEN_PATH="$INPUT_DIR/bridge.token"
[ -f "$TOKEN_PATH" ] || fail token-missing
TOKEN="$(tr -d '\r\n' < "$TOKEN_PATH")"
[ "${#TOKEN}" -eq 64 ] || fail token-format
case "$TOKEN" in *[!0-9a-f]*) fail token-format ;; esac

if [ "$RESUME_ASSEMBLY" -eq 1 ]; then
    grep -Fqx "rootfs_sha256=$EXPECTED_ROOTFS_SHA256" /newroot/var/lib/droidlayer/assembly-in-progress \
        || fail resumed-rootfs-checksum-mismatch
else
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=format status=running'
    /sbin/mkfs.ext4 -F -L droidlayer-root "$ROOT_DEVICE" || fail format-root
    mount -t ext4 "$ROOT_DEVICE" /newroot || fail mount-root
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=format status=mounted'
    mkdir -p /newroot/var/lib/droidlayer
    printf 'assembly_version=2\nrootfs_sha256=%s\n' "$EXPECTED_ROOTFS_SHA256" \
        > /newroot/var/lib/droidlayer/assembly-in-progress
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=marker-sync status=running'
    sync
    log 'ORCHESTELLA_GUEST_ASSEMBLY phase=marker-sync status=complete'
fi
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=unpack status=running'
unpack_rootfs_archive() {
    # init runs under BusyBox ash, which supports local.
    # shellcheck disable=SC3043
    local magic=""
    if [ -r "$ROOTFS_PATH" ]; then
        magic="$(head -c 6 "$ROOTFS_PATH" 2>/dev/null | od -A n -t x1 2>/dev/null | tr -d ' \n\r' || true)"
    fi
    case "$magic" in
        fd377a585a00*) timeout 1800 tar -xJpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        1f8b*)         timeout 1800 tar -xzpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        425a68*)       timeout 1800 tar -xjpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        28b52ffd*)
            if command -v zstd >/dev/null 2>&1; then
                timeout 1800 zstd -dc "$ROOTFS_PATH" | tar -xpf - -C /newroot && return 0
            fi
            ;;
    esac
    case "$ROOTFS_PATH" in
        *.tar.xz|*.txz)   timeout 1800 tar -xJpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        *.tar.gz|*.tgz)   timeout 1800 tar -xzpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        *.tar.bz2|*.tbz2) timeout 1800 tar -xjpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
        *.tar)            timeout 1800 tar -xpf "$ROOTFS_PATH" -C /newroot && return 0 ;;
    esac
    timeout 1800 tar -xJpf "$ROOTFS_PATH" -C /newroot 2>/dev/null && return 0
    timeout 1800 tar -xzpf "$ROOTFS_PATH" -C /newroot 2>/dev/null && return 0
    timeout 1800 tar -xpf "$ROOTFS_PATH" -C /newroot 2>/dev/null && return 0
    return 64
}
unpack_rootfs_archive &
unpack_pid=$!
(
    heartbeat_sleep=
    trap 'if [ -n "$heartbeat_sleep" ]; then kill "$heartbeat_sleep" 2>/dev/null || true; wait "$heartbeat_sleep" 2>/dev/null || true; fi; exit 0' TERM INT
    while kill -0 "$unpack_pid" 2>/dev/null; do
        sleep 15 &
        heartbeat_sleep=$!
        wait "$heartbeat_sleep" || true
        heartbeat_sleep=
        if kill -0 "$unpack_pid" 2>/dev/null; then
            log 'ORCHESTELLA_GUEST_ASSEMBLY phase=unpack status=running heartbeat=1'
        fi
    done
) &
heartbeat_pid=$!
unpack_status=0
wait "$unpack_pid" || unpack_status=$?
kill "$heartbeat_pid" 2>/dev/null || true
wait "$heartbeat_pid" 2>/dev/null || true
[ "$unpack_status" -eq 0 ] || fail unpack-rootfs
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=unpack status=complete'

mkdir -p /newroot/etc/droidlayer
chmod 0700 /newroot/etc/droidlayer
umask 077
printf '%s\n' "$TOKEN" > /newroot/etc/droidlayer/bridge.token
chmod 0600 /newroot/etc/droidlayer/bridge.token
unset TOKEN
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=identity status=running'
provision_guest_identity_and_credentials /newroot "$INPUT_DIR"
log 'ORCHESTELLA_GUEST_ASSEMBLY phase=identity status=complete'
umask 022
# Per-instance identities must not inherit a shared cloud/container image's machine id or SSH keys.
rm -f /newroot/etc/machine-id /newroot/var/lib/dbus/machine-id
for host_key in /newroot/etc/ssh/ssh_host_*; do
    [ ! -f "$host_key" ] || rm -f "$host_key"
done

if [ -f "$INPUT_DIR/sources.list" ]; then
    mkdir -p /newroot/etc/apt
    cp "$INPUT_DIR/sources.list" /newroot/etc/apt/sources.list || fail apt-source-install
    # Avoid duplicate cloud-image deb822 repositories bypassing the selected mirror policy.
    # The backup must live OUTSIDE sources.list.d: apt reads every file in that directory
    # and printed `N: Ignoring file 'ubuntu.sources.orchestella-original' ... invalid
    # filename extension` on every single apt call, which reads as a broken package
    # manager to the user.
    if [ -f /newroot/etc/apt/sources.list.d/ubuntu.sources ]; then
        mkdir -p /newroot/var/lib/orchestella/apt-backup
        mv /newroot/etc/apt/sources.list.d/ubuntu.sources /newroot/var/lib/orchestella/apt-backup/ubuntu.sources
    fi
    if [ -f /newroot/etc/apt/sources.list.d/debian.sources ]; then
        mkdir -p /newroot/var/lib/orchestella/apt-backup
        mv /newroot/etc/apt/sources.list.d/debian.sources /newroot/var/lib/orchestella/apt-backup/debian.sources
    fi
    chmod 0644 /newroot/etc/apt/sources.list
fi
if [ -f "$INPUT_DIR/repositories" ]; then
    mkdir -p /newroot/etc/apk
    cp "$INPUT_DIR/repositories" /newroot/etc/apk/repositories || fail apk-source-install
    chmod 0644 /newroot/etc/apk/repositories
fi
if [ -f "$INPUT_DIR/mirrorlist" ]; then
    mkdir -p /newroot/etc/pacman.d
    cp "$INPUT_DIR/mirrorlist" /newroot/etc/pacman.d/mirrorlist || fail pacman-source-install
    chmod 0644 /newroot/etc/pacman.d/mirrorlist
fi
# Mandatory baseline loads only this explicit owned copy; preserve all vendor/user .repo files.
stage_guest_package_repositories /newroot "$INPUT_DIR"
if grep -Fqx '# orchestella-guest-runtime-version=2' "$INPUT_DIR/firstboot.sh" 2>/dev/null; then
    mkdir -p /newroot/opt/orchestella
    cp "$INPUT_DIR/firstboot.sh" /newroot/opt/orchestella/firstboot.sh || fail firstboot-script-install
    chmod 0755 /newroot/opt/orchestella/firstboot.sh
fi

mkdir -p /newroot/var/lib/droidlayer
DISTRO_TAG="$(sed -n 's/^ID=//p' /newroot/etc/os-release 2>/dev/null | tr -d '\"\047' | head -n 1)"
[ -n "$DISTRO_TAG" ] || DISTRO_TAG=linux
printf 'provisioning_version=1\nassembly_version=2\ndistro=%s\nrootfs_sha256=%s\n' "$DISTRO_TAG" "$EXPECTED_ROOTFS_SHA256" \
    > /newroot/var/lib/droidlayer/provisioned-v1.tmp
mv /newroot/var/lib/droidlayer/provisioned-v1.tmp /newroot/var/lib/droidlayer/provisioned-v1
rm -f /newroot/var/lib/droidlayer/assembly-in-progress
sync
log "DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=$DISTRO_TAG phase=rootfs runtime=pending"
boot_guest_root
fail switch-root
