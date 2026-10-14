# Guest boot and service installation functions embedded into /init by sync-guest-init.py.
stage_guest_package_repositories() {
    repo_root="$1"; repo_input="$2"
    repo_os_release="$(guest_root_path "$repo_root" /etc/os-release || true)"
    [ -f "$repo_os_release" ] || repo_os_release="$repo_root/usr/lib/os-release"
    repo_distro="$(sed -n 's/^ID=//p' "$repo_os_release" 2>/dev/null | tr -d '\"\047' | head -n 1)"
    case "$repo_distro" in
        fedora) repo_file=fedora.repo; repo_ids='fedora updates' ;;
        almalinux) repo_file=almalinux.repo; repo_ids='baseos appstream' ;;
        rocky) repo_file=rocky.repo; repo_ids='baseos appstream' ;;
        opensuse*) repo_distro=opensuse; repo_file=opensuse.repo; repo_ids=repo-oss ;;
        *) return 0 ;;
    esac
    # Only an explicit existing provisioning payload is managed. Never adopt vendor/user files.
    [ -f "$repo_input/$repo_file" ] || return 0
    [ ! -L "$repo_input/$repo_file" ] || fail package-repository-input-link
    repo_sections="$(sed -n 's/^\[\([^]]*\)\]$/\1/p' "$repo_input/$repo_file" | tr '\n' ' ' | sed 's/ $//')"
    [ "$repo_sections" = "$repo_ids" ] || fail package-repository-ids-invalid
    repo_count=0
    for repo_id in $repo_ids; do repo_count=$((repo_count + 1)); done
    [ "$(grep -c '^gpgcheck=1$' "$repo_input/$repo_file")" = "$repo_count" ] || fail package-repository-signatures-required
    [ "$(grep -c '^gpgkey=.' "$repo_input/$repo_file")" = "$repo_count" ] || fail package-repository-key-missing
    if grep -Eiq '^[[:space:]]*(gpgcheck|sslverify)[[:space:]]*=[[:space:]]*(0|false|no)([[:space:]]|$)' "$repo_input/$repo_file"; then
        fail package-repository-verification-disabled
    fi
    repo_directory="$repo_root/etc/orchestella/provision-repos/$repo_distro"
    mkdir -p "$repo_directory" || fail package-repository-directory
    [ ! -L "$repo_directory" ] && [ ! -L "$repo_directory/$repo_file" ] || fail package-repository-output-link
    cp "$repo_input/$repo_file" "$repo_directory/$repo_file.tmp" || fail package-repository-copy
    chmod 0644 "$repo_directory/$repo_file.tmp"
    mv "$repo_directory/$repo_file.tmp" "$repo_directory/$repo_file" || fail package-repository-install
    repo_digest="$(sha256sum "$repo_directory/$repo_file" | cut -d ' ' -f 1)"
    printf 'managed_repositories_version=1\ndistro=%s\nfile=%s\nids=%s\nsha256=%s\n' \
        "$repo_distro" "$repo_file" "$repo_ids" "$repo_digest" > "$repo_directory/owned-v1.tmp"
    chmod 0644 "$repo_directory/owned-v1.tmp"
    mv "$repo_directory/owned-v1.tmp" "$repo_directory/owned-v1" || fail package-repository-marker
}
configure_fstab_mounts() {
    target_root="$1"
    mkdir -p "$target_root/mnt/downloads" "$target_root/media/downloads" "$target_root/mnt/shared"
    fstab="$target_root/etc/fstab"
    [ -f "$fstab" ] || return 0
    # Retire only the exact legacy project-generated rows and our bounded block. User rows remain.
    sed '/^# ORCHESTELLA_SHARES_BEGIN$/,/^# ORCHESTELLA_SHARES_END$/d; /^downloads[[:space:]]\+\/mnt\/downloads[[:space:]]\+9p[[:space:]]\+trans=virtio,version=9p2000.L,ro,nofail[[:space:]]\+0[[:space:]]\+0$/d; /^shared[[:space:]]\+\/mnt\/shared[[:space:]]\+9p[[:space:]]\+trans=virtio,version=9p2000.L,ro,nofail[[:space:]]\+0[[:space:]]\+0$/d' \
        "$fstab" > "$fstab.tmp" || fail shared-fstab-read
    printf '# ORCHESTELLA_SHARES_BEGIN\n' >> "$fstab.tmp"
    # A tag is physically present only when the host explicitly supplied the data plane.
    # AVF vsock shares are mounted by the existing authenticated agent launcher, not fstab.
    virtio_devices="${ORCHESTELLA_VIRTIO_FIXTURE:-/sys/bus/virtio/devices}"
    if [ -n "${ORCHESTELLA_VIRTIO_FIXTURE:-}" ] && [ ! -f "$virtio_devices/.orchestella-virtio-fixture" ]; then
        fail shared-fixture-invalid
    fi
    for share_tag in downloads shared; do
        share_present=0
        for tag_file in "$virtio_devices"/*/mount_tag; do
            if [ -f "$tag_file" ] && [ "$(cat "$tag_file")" = "$share_tag" ]; then share_present=1; break; fi
        done
        if [ "$share_present" -eq 1 ] && ! grep -Eq "^$share_tag[[:space:]]+/mnt/$share_tag[[:space:]]" "$fstab.tmp"; then
            printf '%s\t/mnt/%s\t9p\ttrans=virtio,version=9p2000.L,ro,nofail\t0\t0\n' "$share_tag" "$share_tag" >> "$fstab.tmp"
        fi
    done
    printf '# ORCHESTELLA_SHARES_END\n' >> "$fstab.tmp"
    chmod 0644 "$fstab.tmp"
    mv "$fstab.tmp" "$fstab" || fail shared-fstab-write
}
# Never write through /sbin/init: it commonly links to the distro's systemd or BusyBox.
verify_rootfs_input() {
    input_dir="$1"
    metadata_dir="$input_dir"
    if [ ! -f "$metadata_dir/rootfs.file" ]; then metadata_dir=/etc/droidlayer; fi
    ROOTFS_FILE="$(cat "$metadata_dir/rootfs.file" 2>/dev/null || true)"
    EXPECTED_ROOTFS_SHA256="$(cat "$metadata_dir/rootfs.sha256" 2>/dev/null || true)"
    EXPECTED_ROOTFS_SIZE="$(cat "$metadata_dir/rootfs.size" 2>/dev/null || true)"
    case "$ROOTFS_FILE" in ''|.*|*[!A-Za-z0-9._+-]*|*/*) fail rootfs-filename-invalid ;; esac
    case "$ROOTFS_FILE" in *.tar|*.tar.gz|*.tar.xz|*.tgz|*.txz) ;; *) fail unsupported-rootfs-archive ;; esac
    [ "${#EXPECTED_ROOTFS_SHA256}" -eq 64 ] || fail rootfs-checksum-missing
    case "$EXPECTED_ROOTFS_SHA256" in *[!0-9a-f]*) fail rootfs-checksum-invalid ;; esac
    case "$EXPECTED_ROOTFS_SIZE" in ''|0|0*|*[!0-9]*) fail rootfs-size-missing ;; esac
    ROOTFS_PATH="$input_dir/$ROOTFS_FILE"
    [ -f "$ROOTFS_PATH" ] || fail rootfs-missing
    actual_size="$(wc -c < "$ROOTFS_PATH" | tr -d ' \n')"
    [ "$actual_size" = "$EXPECTED_ROOTFS_SIZE" ] || fail rootfs-size-mismatch
    (
        checksum_sleep=
        trap 'if [ -n "$checksum_sleep" ]; then kill "$checksum_sleep" 2>/dev/null || true; wait "$checksum_sleep" 2>/dev/null || true; fi; exit 0' TERM INT
        while :; do
            sleep 30 &
            checksum_sleep=$!
            wait "$checksum_sleep" || true
            checksum_sleep=
            log 'ORCHESTELLA_GUEST_ASSEMBLY phase=input-verification status=running heartbeat=1 timeout_seconds=1800'
        done
    ) &
    checksum_heartbeat=$!
    checksum_exit=0
    checksum_output="$(timeout 1800 sha256sum "$ROOTFS_PATH")" || checksum_exit=$?
    kill "$checksum_heartbeat" 2>/dev/null || true
    wait "$checksum_heartbeat" 2>/dev/null || true
    case "$checksum_exit" in
        0) ;;
        124|137|143) fail rootfs-checksum-timeout ;;
        *) fail rootfs-checksum-unavailable ;;
    esac
    actual_digest="${checksum_output%% *}"
    [ "$actual_digest" = "$EXPECTED_ROOTFS_SHA256" ] || fail rootfs-checksum
}
stage_guest_profile_identity() {
    identity_root="$1"; identity_input="$2"
    # Persist only an explicit validated identity. Missing legacy input must remain unknown.
    [ -f "$identity_input/guest.user" ] || return 0
    [ ! -L "$identity_input/guest.user" ] || fail guest-user-invalid
    identity_user="$(cat "$identity_input/guest.user")"
    case "$identity_user" in ''|[!a-z_]*|*[!a-z0-9_-]*) fail guest-user-invalid ;; esac
    [ "${#identity_user}" -ge 2 ] && [ "${#identity_user}" -le 16 ] || fail guest-user-invalid
    [ ! -L "$identity_root/etc/orchestella" ] && [ ! -L "$identity_root/etc/orchestella/instance-user" ] || fail guest-identity-unsafe
    mkdir -p "$identity_root/etc/orchestella" || fail guest-identity-write
    chmod 0700 "$identity_root/etc/orchestella" || fail guest-identity-write
    identity_next="$(mktemp "$identity_root/etc/orchestella/.instance-user.XXXXXX")" || fail guest-identity-write
    if ! printf '%s\n' "$identity_user" > "$identity_next" \
        || ! chmod 0600 "$identity_next" || ! chown 0:0 "$identity_next" \
        || ! mv -f "$identity_next" "$identity_root/etc/orchestella/instance-user"; then
        rm -f "$identity_next"
        fail guest-identity-write
    fi
}
stage_pending_guest_credentials() {
    target_root="$1"; input_dir="$2"
    stage_guest_profile_identity "$target_root" "$input_dir"
    [ -f "$input_dir/guest.password" ] || return 0
    mkdir -p "$target_root/etc/orchestella/provision-input" "$target_root/var/lib/orchestella/provision"
    chmod 0700 "$target_root/etc/orchestella" "$target_root/etc/orchestella/provision-input"
    guest_pass="$(cat "$input_dir/guest.password")"
    case "$guest_pass" in ''|*:*|*[[:space:]]*) fail guest-password-invalid ;; esac
    if [ "${#guest_pass}" -lt 8 ]; then
        printf 'legacy-credential-action-required\n' > "$target_root/var/lib/orchestella/provision/legacy-weak-credentials"
        log 'ORCHESTELLA_GUEST_CREDENTIALS status=action-required reason=legacy-weak-password ssh=password-disabled'
        unset guest_pass
        return 0
    fi
    guest_user=root; guest_sudo=0
    if [ -f "$input_dir/guest.user" ]; then guest_user="$(cat "$input_dir/guest.user")"; fi
    case "$guest_user" in ''|[!a-z_]*|*[!a-z0-9_-]*) fail guest-user-invalid ;; esac
    if grep -Fqx '1' "$input_dir/guest.sudo" 2>/dev/null; then guest_sudo=1; fi
    input_fingerprint="$(printf '%s\n%s\n%s\n' "$guest_pass" "$guest_user" "$guest_sudo" | sha256sum | cut -d ' ' -f 1)"
    rm -f "$target_root/var/lib/orchestella/provision/legacy-weak-credentials"
    # A private, unlogged change fingerprint avoids resetting a password changed inside the guest.
    if grep -Fqx "input_sha256=$input_fingerprint" "$target_root/var/lib/orchestella/provision/credentials-v2" 2>/dev/null; then
        unset guest_pass
        return 0
    fi
    previous_umask="$(umask)"
    umask 077
    printf '%s\n' "$guest_pass" > "$target_root/etc/orchestella/provision-input/guest.password"
    printf '%s\n' "$guest_user" > "$target_root/etc/orchestella/provision-input/guest.user"
    printf '%s\n' "$guest_sudo" > "$target_root/etc/orchestella/provision-input/guest.sudo"
    chmod 0600 "$target_root/etc/orchestella/provision-input/guest.password" \
        "$target_root/etc/orchestella/provision-input/guest.user" "$target_root/etc/orchestella/provision-input/guest.sudo"
    umask "$previous_umask"
    unset guest_pass
}
provision_guest_identity_and_credentials() {
    target_root="$1"; input_dir="$2"
    target_hostname="$(kernel_argument orchestella.hostname || true)"
    if [ -z "$target_hostname" ] && [ -f "$input_dir/hostname" ]; then
        target_hostname="$(cat "$input_dir/hostname")"
    fi
    target_hostname="${target_hostname:-orchestella}"
    case "$target_hostname" in *[!A-Za-z0-9-]*|-*|*-) fail hostname-invalid ;; esac
    [ "${#target_hostname}" -le 63 ] || fail hostname-invalid
    mkdir -p "$target_root/etc/orchestella/provision-input"
    chmod 0700 "$target_root/etc/orchestella" "$target_root/etc/orchestella/provision-input"
    printf '%s\n' "$target_hostname" > "$target_root/etc/hostname"
    printf '127.0.0.1\tlocalhost %s\n::1\tlocalhost ip6-localhost ip6-loopback %s\n' \
        "$target_hostname" "$target_hostname" > "$target_root/etc/hosts"
    chmod 0644 "$target_root/etc/hostname" "$target_root/etc/hosts"
    # Defer account tools until the real package baseline exists. Never use a public default.
    # An absent password keeps the root account locked; SSH is always key-only by default.
    if [ -f "$target_root/etc/shadow" ]; then
        sed -i 's/^root:[^:]*:/root:!:/' "$target_root/etc/shadow" || fail credentials-lock-root
    fi
    stage_pending_guest_credentials "$target_root" "$input_dir"
}
is_alpine_root() {
    grep -Eq '^ID="?alpine"?$' "$1/etc/os-release" "$1/usr/lib/os-release" 2>/dev/null
}
guest_root_path() {
    path_root="$1"; path_name="$2"; path_hops=0
    while [ -L "$path_root$path_name" ]; do
        path_hops=$((path_hops + 1)); [ "$path_hops" -le 16 ] || return 1
        path_link="$(readlink "$path_root$path_name")" || return 1
        case "$path_link" in
            /*) path_name="$path_link" ;;
            *) path_name="${path_name%/*}/$path_link" ;;
        esac
    done
    printf '%s\n' "$path_root$path_name"
}
is_guest_elf() {
    [ -f "$1" ] && [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = 7f454c46 ]
}
select_native_guest_init() {
    target_root="$1"
    if is_alpine_root "$target_root"; then
        resolved="$(guest_root_path "$target_root" /bin/busybox)" || return 1
        is_guest_elf "$resolved" && [ -x "$target_root/sbin/openrc" ] || return 1
        chroot "$target_root" /bin/busybox true >/dev/null 2>&1 || return 1
        # Use the native BusyBox applet explicitly; an old regular /sbin/init shell is not native init.
        printf '/bin/busybox\n'
        return 0
    fi
    for candidate in /usr/lib/systemd/systemd /lib/systemd/systemd; do
        resolved="$(guest_root_path "$target_root" "$candidate")" || continue
        is_guest_elf "$resolved" || continue
        chroot "$target_root" "$candidate" --version >/dev/null 2>&1 || continue
        printf '%s\n' "$candidate"
        return 0
    done
    return 1
}
install_alpine_native_services() {
    target_root="$1"
    is_alpine_root "$target_root" || return 0
    mkdir -p "$target_root/etc/init.d" "$target_root/etc/conf.d"
    # Keep the distro/user inittab. Only our bounded block is regenerated on restart.
    inittab="$target_root/etc/inittab"
    [ -f "$inittab" ] || touch "$inittab"
    sed '/^# ORCHESTELLA_OPENRC_BEGIN$/,/^# ORCHESTELLA_OPENRC_END$/d; /^::respawn:\/usr\/sbin\/orchestella-proxy-relay$/d; /^::respawn:\/usr\/sbin\/droidlayer-proxy-relay$/d' "$inittab" > "$inittab.tmp" \
        || fail alpine-inittab-read
    if grep -Fqx '::respawn:/usr/sbin/droidlayer-guest-agent-launcher' "$inittab.tmp"; then
        # Migrate only entries emitted by the legacy project fallback, preserving all custom entries.
        sed '/^::respawn:\/usr\/sbin\/droidlayer-guest-agent-launcher$/d; /^::respawn:\/bin\/sh$/d; /^::shutdown:\/sbin\/poweroff$/d' \
            "$inittab.tmp" > "$inittab.legacy-migration" || fail alpine-inittab-migration
        mv "$inittab.legacy-migration" "$inittab.tmp"
    fi
    if [ -s "$inittab.tmp" ] && [ -n "$(tail -c 1 "$inittab.tmp")" ]; then printf '\n' >> "$inittab.tmp"; fi
    printf '# ORCHESTELLA_OPENRC_BEGIN\n' >> "$inittab.tmp"
    for runlevel in sysinit boot; do
        if ! grep -Fq "/sbin/openrc $runlevel" "$inittab.tmp"; then
            printf '::sysinit:/sbin/openrc %s\n' "$runlevel" >> "$inittab.tmp"
        fi
    done
    if ! grep -Fq '/sbin/openrc default' "$inittab.tmp"; then
        printf '::wait:/sbin/openrc default\n' >> "$inittab.tmp"
    fi
    for tty in ttyAMA0 hvc0; do
        if ! grep -Eq "^$tty:|agetty.*$tty" "$inittab.tmp"; then
            printf '%s::respawn:/sbin/agetty --autologin root --noclear %s 115200 vt220\n' "$tty" "$tty" >> "$inittab.tmp"
        fi
    done
    if ! grep -Fq '::ctrlaltdel:' "$inittab.tmp"; then printf '::ctrlaltdel:/sbin/reboot\n' >> "$inittab.tmp"; fi
    if ! grep -Fq '/sbin/openrc shutdown' "$inittab.tmp"; then printf '::shutdown:/sbin/openrc shutdown\n' >> "$inittab.tmp"; fi
    printf '# ORCHESTELLA_OPENRC_END\n' >> "$inittab.tmp"
    chmod 0644 "$inittab.tmp"
    mv "$inittab.tmp" "$inittab" || fail alpine-inittab-write
    cat > "$target_root/etc/init.d/droidlayer-guest-agent" <<'EOF'
#!/sbin/openrc-run
name="Orchestella Guest Agent"
command="/usr/sbin/droidlayer-guest-agent-launcher"
supervisor="supervise-daemon"
respawn_delay=2
respawn_max=5
respawn_period=30
depend() { need localmount; }
EOF
    chmod 0755 "$target_root/etc/init.d/droidlayer-guest-agent"
    cat > "$target_root/etc/init.d/orchestella-firstboot" <<'EOF'
#!/sbin/openrc-run
name="Orchestella Guest Baseline"
depend() { need localmount; after dbus sshd; }
start() {
    ebegin "Verifying complete Linux baseline"
    /opt/orchestella/firstboot.sh --native
    result=$?
    eend "$result" "Baseline incomplete; use orchestella-guest-provision --status or --retry"
    return "$result"
}
EOF
    chmod 0755 "$target_root/etc/init.d/orchestella-firstboot"
}
install_guest_firstboot_service() {
    target_root="$1"
    mkdir -p "$target_root/opt/orchestella" "$target_root/etc/orchestella" "$target_root/var/lib/orchestella/provision"
    # Legacy scripts wrote success before executing and backgrounded failures. Never run them again.
    if ! grep -Fqx '# orchestella-guest-runtime-version=2' "$target_root/opt/orchestella/firstboot.sh" 2>/dev/null; then
        cat > "$target_root/opt/orchestella/firstboot.sh" <<'EOF'
#!/bin/sh
# orchestella-guest-runtime-version=2
export ORCHESTELLA_AUTO_UPDATE=0
exec /usr/lib/orchestella/busybox sh /usr/sbin/orchestella-guest-provision "$@"
EOF
        chmod 0755 "$target_root/opt/orchestella/firstboot.sh"
    fi
    if is_alpine_root "$target_root"; then
        install_alpine_native_services "$target_root"
        # The bootstrap has to install OpenRC before these services can be started.
    else
        mkdir -p "$target_root/etc/systemd/system/multi-user.target.wants"
        cat > "$target_root/etc/systemd/system/orchestella-firstboot.service" <<'EOF'
[Unit]
Description=Orchestella Linux Baseline and Service Verification
After=network.target
Wants=network.target

[Service]
Type=oneshot
ExecStart=/opt/orchestella/firstboot.sh --native
TimeoutStartSec=2h
RemainAfterExit=yes
StandardOutput=journal+console
StandardError=journal+console

[Install]
WantedBy=multi-user.target
EOF
        chmod 0644 "$target_root/etc/systemd/system/orchestella-firstboot.service"
        ln -sf /etc/systemd/system/orchestella-firstboot.service \
            "$target_root/etc/systemd/system/multi-user.target.wants/orchestella-firstboot.service"
    fi
    # Surface failed/offline/pending state on login, without printing credentials or raw package output.
    mkdir -p "$target_root/etc/profile.d"
    cat > "$target_root/etc/profile.d/02-orchestella-provision-status.sh" <<'EOF'
#!/bin/sh
if [ -f /var/lib/orchestella/provision/state ]; then
    _os_status="$(sed -n 's/^status=//p' /var/lib/orchestella/provision/state)"
    if [ "$_os_status" != ready ]; then
        printf '\nOrchestella Linux baseline: %s\n' "$_os_status"
        sed -n '/^stage=/p; /^reason=/p' /var/lib/orchestella/provision/state
        printf 'Inspect: orchestella-guest-provision --status; retry: orchestella-guest-provision --retry\n\n'
    fi
    unset _os_status
fi
EOF
    chmod 0644 "$target_root/etc/profile.d/02-orchestella-provision-status.sh"
}
install_generic_linux_init_fallback() {
    target_root="$1"
    mkdir -p "$target_root/usr/lib/orchestella/bin" "$target_root/usr/sbin" "$target_root/etc/orchestella"
    # This private recovery binary is independent of a potentially corrupted /bin/busybox.
    cp /bin/busybox "$target_root/usr/lib/orchestella/busybox" || fail recovery-busybox-copy
    chmod 0755 "$target_root/usr/lib/orchestella/busybox"
    for applet in sh mount mkdir cat sed grep head tr dd od readlink cp mv rm rmdir chmod ln sleep \
        kill setsid sync reboot poweroff timeout date touch; do
        ln -sf /usr/lib/orchestella/busybox "$target_root/usr/lib/orchestella/bin/$applet"
    done
    cat > "$target_root/usr/sbin/orchestella-boot" <<'EOF'
#!/usr/lib/orchestella/busybox sh
export PATH=/usr/lib/orchestella/bin:/usr/sbin:/usr/bin:/sbin:/bin
ORCHESTELLA_RECOVERY_MANUAL=0
for boot_argument in $(cat /proc/cmdline); do
    if [ "$boot_argument" = orchestella.recovery=1 ]; then
        export ORCHESTELLA_RECOVERY_MANUAL=1
        exec /usr/sbin/orchestella-recovery-init
    fi
done
native="$(cat /etc/orchestella/native-init 2>/dev/null || true)"
case "$native" in
    /bin/busybox) exec /bin/busybox init ;;
    /usr/lib/systemd/systemd|/lib/systemd/systemd) exec "$native" ;;
    *) exec /usr/sbin/orchestella-recovery-init ;;
esac
EOF
    chmod 0755 "$target_root/usr/sbin/orchestella-boot"
    cat > "$target_root/usr/sbin/orchestella-recovery-init" <<'EOF'
#!/usr/lib/orchestella/busybox sh
# Recovery only. Real distro init remains PID1 after a successful bootstrap handoff.
export PATH=/usr/lib/orchestella/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root TERM=xterm-256color
mkdir -p /run /dev/pts /dev/shm /var/lib/orchestella/provision
mount -t tmpfs tmpfs /run 2>/dev/null || true
mount -t devpts devpts /dev/pts 2>/dev/null || true
mount -t tmpfs tmpfs /dev/shm 2>/dev/null || true
if [ "${ORCHESTELLA_RECOVERY_MANUAL:-0}" = 1 ]; then
    printf 'ORCHESTELLA_GUEST_RECOVERY reason=owner-requested mode=manual baseline=not-ready automatic_provision=disabled\n' > /dev/console
else
    printf 'ORCHESTELLA_GUEST_RECOVERY reason=native-init-missing-or-failed baseline=not-ready\n' > /dev/console
fi
select_recovery_login_shell() {
    # Bash-specific distro profiles must run under Bash. Probe once before the respawn loop;
    # private BusyBox remains independent of a damaged distro /bin/sh or /bin/busybox.
    for shell_candidate in /bin/bash /usr/bin/bash; do
        [ -x "$shell_candidate" ] || continue
        [ "$(dd if="$shell_candidate" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = 7f454c46 ] || continue
        if /usr/lib/orchestella/busybox timeout 5 "$shell_candidate" --noprofile --norc --version >/dev/null 2>&1; then
            printf '%s\n' "$shell_candidate"
            return 0
        fi
    done
    printf '/usr/lib/orchestella/busybox\n'
}
recovery_shell="$(select_recovery_login_shell)"
printf 'ORCHESTELLA_GUEST_RECOVERY shell=%s baseline=not-ready\n' "$recovery_shell" > /dev/console
# Only the existing authenticated bridge and owner console are available during recovery.
(
    worker_pid=
    trap 'if [ -n "$worker_pid" ]; then kill -TERM -- "-$worker_pid" 2>/dev/null || true; kill -KILL -- "-$worker_pid" 2>/dev/null || true; wait "$worker_pid" 2>/dev/null || true; fi; exit 0' TERM INT
    while :; do
        /usr/lib/orchestella/busybox setsid /usr/lib/orchestella/busybox sh /usr/sbin/droidlayer-guest-agent-launcher &
        worker_pid=$!
        wait "$worker_pid" || true
        worker_pid=
        sleep 2
    done
) &
agent_pid=$!
console=/dev/console
if [ -c /dev/droidlayer-terminal ]; then
    console=/dev/droidlayer-terminal
elif [ -c /dev/ttyAMA0 ]; then
    console=/dev/ttyAMA0
elif [ -c /dev/hvc0 ]; then
    console=/dev/hvc0
fi
(
    worker_pid=
    trap 'if [ -n "$worker_pid" ]; then kill -TERM -- "-$worker_pid" 2>/dev/null || true; kill -KILL -- "-$worker_pid" 2>/dev/null || true; wait "$worker_pid" 2>/dev/null || true; fi; exit 0' TERM INT
    if [ "$recovery_shell" = /usr/lib/orchestella/busybox ]; then
        set -- /usr/lib/orchestella/busybox sh -l
    else
        set -- "$recovery_shell" -l
    fi
    while :; do
        /usr/lib/orchestella/busybox setsid -c "$@" < "$console" > "$console" 2>&1 &
        worker_pid=$!
        wait "$worker_pid" || true
        worker_pid=
        sleep 1
    done
) &
console_pid=$!
stop_recovery() {
    kill "$agent_pid" "$console_pid" 2>/dev/null || true
    wait "$agent_pid" "$console_pid" 2>/dev/null || true
}
trap 'stop_recovery; sync; poweroff -f' TERM INT
# One automatic attempt per ordinary recovery boot; explicit owner diagnostics preserve state.
if [ "${ORCHESTELLA_RECOVERY_MANUAL:-0}" != 1 ]; then
    /usr/lib/orchestella/busybox sh /opt/orchestella/firstboot.sh --bootstrap || true
fi
while :; do
    if [ "${ORCHESTELLA_RECOVERY_MANUAL:-0}" != 1 ] && grep -Fqx 'status=awaiting-init' /var/lib/orchestella/provision/state 2>/dev/null; then
        native=""
        if grep -Eq '^ID="?alpine"?$' /etc/os-release /usr/lib/os-release 2>/dev/null \
            && /bin/busybox true >/dev/null 2>&1 && [ -x /sbin/openrc ]; then
            native=/bin/busybox
        else
            for candidate in /usr/lib/systemd/systemd /lib/systemd/systemd; do
                if "$candidate" --version >/dev/null 2>&1; then native="$candidate"; break; fi
            done
        fi
        if [ -n "$native" ]; then
            stop_recovery
            sync
            case "$native" in /bin/busybox) exec /bin/busybox init ;; *) exec "$native" ;; esac
        fi
        printf 'ORCHESTELLA_GUEST_RECOVERY reason=native-init-probe-failed baseline=not-ready\n' > /dev/console
        printf 'version=2\nstatus=failed\nstage=native-handoff\nreason=native-init-probe-failed\n' \
            > /var/lib/orchestella/provision/state.tmp
        mv -f /var/lib/orchestella/provision/state.tmp /var/lib/orchestella/provision/state
    fi
    sleep 2 &
    wait "$!" || true
 done
EOF
    chmod 0755 "$target_root/usr/sbin/orchestella-recovery-init"
}
install_native_guest_runtime() {
    target_root="$1"
    install_generic_linux_init_fallback "$target_root"
    install_guest_runtime_provisioner "$target_root"
    # The systemd installer also provides the strategy-neutral launcher, used by OpenRC/recovery.
    install_systemd_guest_agent_service "$target_root"
    if is_official_debian_systemd_init "$target_root"; then
        install_official_debian_systemd_flavor "$target_root"
    fi
    if ! is_alpine_root "$target_root"; then
        install_serial_autologin_getty "$target_root"
        if ! is_official_debian_systemd_init "$target_root"; then
            configure_systemd_network_dhcp "$target_root"
        fi
    fi
    install_guest_firstboot_service "$target_root"
    mkdir -p "$target_root/run"
    selected="$(select_native_guest_init "$target_root" || true)"
    if [ -n "$selected" ]; then
        printf '%s\n' "$selected" > "$target_root/etc/orchestella/native-init"
        rm -f "$target_root/var/lib/orchestella/provision/init-repair-needed"
    else
        printf 'recovery\n' > "$target_root/etc/orchestella/native-init"
        printf 'native-init-missing-or-invalid\n' > "$target_root/var/lib/orchestella/provision/init-repair-needed"
        log 'ORCHESTELLA_GUEST_RECOVERY reason=native-init-missing-or-invalid baseline=not-ready'
    fi
}
