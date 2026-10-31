#!/bin/sh
# orchestella-guest-runtime-version=2
# Mandatory guest baseline. This runs inside the VM, never on the Android host.
set -u
umask 077
ROOT="${ORCHESTELLA_PROVISION_ROOT:-}"
if [ -n "$ROOT" ] && [ ! -f "$ROOT/.orchestella-provision-fixture" ]; then
    printf 'Refusing a non-guest root without the synthetic fixture sentinel.\n' >&2
    exit 64
fi
# Recovery uses an independent static BusyBox when an older init damaged /bin/busybox.
if [ -z "$ROOT" ] && [ -d /usr/lib/orchestella/bin ]; then
    PATH=/usr/lib/orchestella/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export PATH
fi
export LANG=C LC_ALL=C
STATE_DIR="$ROOT/var/lib/orchestella/provision"
STATE_FILE="$STATE_DIR/state"
DONE="$ROOT/opt/orchestella/.firstboot-done"
LOG="$ROOT/var/log/orchestella-provision.log.txt"
LOCK="$ROOT/run/orchestella-provision.lock"
MODE="${1:---native}"
case "$MODE" in --native|--bootstrap|--retry|--status) ;; *) exit 64 ;; esac
if [ "$MODE" = --status ]; then
    if [ -f "$STATE_FILE" ]; then cat "$STATE_FILE"; else printf 'status=pending\nstage=not-started\n'; fi
    exit 0
fi
mkdir -p "$STATE_DIR" "$ROOT/var/log" "$ROOT/opt/orchestella" "$ROOT/run" || exit 1
if ! mkdir "$LOCK" 2>/dev/null; then
    owner="$(cat "$LOCK/pid" 2>/dev/null || true)"
    case "$owner" in ''|*[!0-9]*) owner=0 ;; esac
    if [ "$owner" -gt 0 ] && kill -0 "$owner" 2>/dev/null; then
        printf 'ORCHESTELLA_GUEST_PROVISION status=busy stage=locked\n'
        exit 75
    fi
    rm -rf "$LOCK" || exit 1
    mkdir "$LOCK" || exit 75
fi
printf '%s\n' "$$" > "$LOCK/pid"
status=running
stage=start
reason=none
operation_exit_code=0
operation_timeout_seconds=0
last_step_stage=
last_step_exit_code=0
last_operation_stage=not-started
last_operation_exit_code=not-run
attempt=0
if [ -f "$STATE_FILE" ]; then
    attempt="$(sed -n 's/^attempt=//p' "$STATE_FILE")"
fi
case "$attempt" in ''|*[!0-9]*) attempt=0 ;; esac
attempt=$((attempt + 1))
write_state() {
    printf 'version=2\nstatus=%s\nstage=%s\nattempt=%s\nreason=%s\noperation_exit_code=%s\noperation_timeout_seconds=%s\nlast_operation_stage=%s\nlast_operation_exit_code=%s\n' \
        "$status" "$stage" "$attempt" "$reason" "$operation_exit_code" "$operation_timeout_seconds" \
        "$last_operation_stage" "$last_operation_exit_code" > "$STATE_FILE.tmp" \
        && mv -f "$STATE_FILE.tmp" "$STATE_FILE"
}
report() {
    line="ORCHESTELLA_GUEST_PROVISION status=$status stage=$stage reason=$reason attempt=$attempt operation_exit_code=$operation_exit_code last_operation_stage=$last_operation_stage last_operation_exit_code=$last_operation_exit_code"
    printf '%s\n' "$line" >> "$LOG"
    if [ "$status" = failed ]; then
        # Atomic rename publishes the state, but does not flush ext4's journal or log data.
        # Request a guest flush before the driver can observe failure and stop the VM.
        if ! sync; then
            printf 'ORCHESTELLA_GUEST_PROVISION_DURABILITY_ERROR reason=sync-failed\n' >&2
        fi
    fi
    # The owned native unit journals progress; recovery/interactive callers inherit
    # their visible stdout. Never send the same report through both routes.
    printf '%s\n' "$line"
    if [ "${ORCHESTELLA_PROVISION_REPORT:-stdout}" = journal ] && [ "$status" = failed ] \
        && [ -z "$ROOT" ] && [ -w /dev/console ]; then
        printf '%s\n' "$line" > /dev/console
    fi
}
cleanup() {
    code="$?"
    trap - EXIT INT TERM HUP
    if [ "$status" = running ]; then
        status=failed
        reason=interrupted-or-command-error
        [ "$code" -ne 0 ] || code=1
        operation_exit_code="$code"
        rm -f "$DONE"
        write_state || true
        report
    fi
    if [ "${created_policy:-0}" -eq 1 ]; then rm -f "${policy:-}"; fi
    rm -f "$LOCK/pid"
    rmdir "$LOCK" 2>/dev/null || true
    exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
fail_stage() {
    failure_return=$?
    stage="$1"; reason="$2"; status=failed
    # A negated `if ! run_step` loses its shell return value; keep the original command
    # status from run_step instead of replacing it with this function's generic exit 1.
    if [ "$last_step_stage" = "$stage" ] && [ "$last_step_exit_code" -ne 0 ]; then
        operation_exit_code="$last_step_exit_code"
        case "$last_step_exit_code" in 124|137|143) reason=operation-timeout ;; esac
    elif [ "$failure_return" -ne 0 ]; then
        operation_exit_code="$failure_return"
    else
        operation_exit_code=not-run
    fi
    if [ "${created_policy:-0}" -eq 1 ]; then rm -f "${policy:-}"; created_policy=0; fi
    rm -f "$DONE"
    write_state || true
    report
    exit 1
}
run_step() {
    stage="$1"; shift
    status=running; reason=none
    case "$stage" in
        repository-refresh|certificate-bootstrap-refresh|certificate-bootstrap-install|package-install|package-recovery|init-repair|optional-upgrade|locale)
            operation_timeout_seconds=1800 ;;
        *) operation_timeout_seconds=600 ;;
    esac
    operation_exit_code=0; last_step_stage="$stage"; last_step_exit_code=0
    write_state || fail_stage state-write persistent-state-unavailable
    report
    command -v timeout >/dev/null 2>&1 || fail_stage "$stage" timeout-tool-missing
    # The transaction includes CPU-heavy metadata/solver/package work under TCG.
    # Individual network requests retain the package manager's short timeout/retry policy.
    if timeout "$operation_timeout_seconds" "$@" >> "$LOG" 2>&1; then
        last_operation_stage="$stage"; last_operation_exit_code=0
        return 0
    else
        operation_exit_code=$?
        last_step_exit_code="$operation_exit_code"
        last_operation_stage="$stage"; last_operation_exit_code="$operation_exit_code"
        case "$operation_exit_code" in 124|137|143) reason=operation-timeout ;; *) reason=command-failed ;; esac
        write_state || fail_stage state-write persistent-state-unavailable
        return "$operation_exit_code"
    fi
}
read_os_field() {
    file="$ROOT/etc/os-release"
    [ -f "$file" ] || file="$ROOT/usr/lib/os-release"
    sed -n "s/^$1=//p" "$file" 2>/dev/null | tr -d '\"\047\r' | head -n 1
}
distro="$(read_os_field ID)"
case "$distro" in
    debian|ubuntu|alpine|fedora|almalinux|rocky|arch) ;;
    archarm) distro=arch ;;
    opensuse*) distro=opensuse ;;
    *) fail_stage distro unsupported-distro ;;
esac
expected="${ORCHESTELLA_EXPECTED_DISTRO:-$distro}"
[ "$expected" = "$distro" ] || fail_stage distro manifest-distro-mismatch
case "$distro" in
    debian|ubuntu)
        manager=apt
        packages='systemd systemd-sysv udev dbus openssh-server ca-certificates locales bash coreutils util-linux iproute2 iputils-ping procps curl wget git tar gzip xz-utils sudo nano less tmux ncurses-term'
        ;;
    alpine)
        manager=apk
        packages='alpine-base openrc busybox dbus openssh ca-certificates musl-locales bash coreutils util-linux iproute2 iputils procps curl wget git tar xz sudo nano less tmux ncurses-terminfo'
        ;;
    fedora|almalinux|rocky)
        manager=dnf
        packages='systemd systemd-udev dbus dbus-tools openssh-server openssh-clients ca-certificates glibc-langpack-en bash coreutils util-linux iproute iputils procps-ng curl wget git tar gzip xz sudo dnf nano less tmux ncurses-term'
        ;;
    arch)
        manager=pacman
        packages='base systemd dbus openssh ca-certificates glibc bash coreutils util-linux iproute2 iputils procps-ng curl wget git tar gzip xz sudo nano less tmux ncurses'
        ;;
    opensuse)
        manager=zypper
        packages='systemd udev dbus-1 dbus-1-tools openssh-server openssh-clients ca-certificates ca-certificates-mozilla glibc-locale bash coreutils util-linux iproute2 iputils procps curl wget git tar gzip xz sudo nano less tmux ncurses-utils terminfo'
        ;;
esac
if [ "$distro" = fedora ]; then
    packages="$(printf '%s\n' "$packages" | sed 's/ dnf / dnf5 /; s/ wget / wget2-wget /')"
    if rpm -q wget1-wget >/dev/null 2>&1; then
        packages="$(printf '%s\n' "$packages" | sed 's/ wget2-wget / wget1-wget /')"
    fi
fi
if [ "$manager" = dnf ]; then
    # Official minimal RPM roots can ship the same CLI via minimal/single packages.
    # Keep their installed, signed variants instead of requiring conflicting full packages.
    if rpm -q curl-minimal >/dev/null 2>&1; then
        packages="$(printf '%s\n' "$packages" | sed 's/ curl / curl-minimal /')"
    fi
    if rpm -q coreutils-single >/dev/null 2>&1; then
        packages="$(printf '%s\n' "$packages" | sed 's/ coreutils / coreutils-single /')"
    fi
fi
package_installed() {
    case "$manager" in
        apt) [ "$(dpkg-query -W -f='${db:Status-Status}' "$1" 2>/dev/null)" = installed ] ;;
        apk) apk info -e "$1" >/dev/null 2>&1 ;;
        dnf|zypper) rpm -q "$1" >/dev/null 2>&1 ;;
        pacman) pacman -Q "$1" >/dev/null 2>&1 ;;
    esac
}
verify_packages() {
    for package in $packages; do package_installed "$package" || return 1; done
}
# Resolve links within the guest root (absolute links must never resolve in a fixture host).
guest_path() {
    relative="$1"
    hops=0
    while [ -L "$ROOT$relative" ]; do
        hops=$((hops + 1)); [ "$hops" -le 16 ] || return 1
        link="$(readlink "$ROOT$relative")" || return 1
        case "$link" in
            /*) relative="$link" ;;
            *) relative="${relative%/*}/$link" ;;
        esac
    done
    printf '%s\n' "$ROOT$relative"
}
is_elf() {
    [ -f "$1" ] && [ "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" = 7f454c46 ]
}
native_init_valid() {
    if [ "$distro" = alpine ]; then
        native="$(guest_path /bin/busybox)" || return 1
        is_elf "$native" && [ -x "$ROOT/sbin/openrc" ]
    else
        for candidate in /usr/lib/systemd/systemd /lib/systemd/systemd; do
            native="$(guest_path "$candidate")" || continue
            is_elf "$native" && return 0
        done
        return 1
    fi
}
native_pid1() {
    comm="$(cat "$ROOT/proc/1/comm" 2>/dev/null || true)"
    if [ "$distro" = alpine ]; then
        [ "$comm" = init ] && [ -d "$ROOT/run/openrc" ]
    else
        [ "$comm" = systemd ]
    fi
}
services_active() {
    if [ "$distro" = alpine ]; then
        rc-service dbus status >/dev/null 2>&1 && rc-service sshd status >/dev/null 2>&1
    else
        systemctl is-active --quiet dbus.service && systemctl is-active --quiet "$ssh_unit"
    fi
}
ssh_unit=sshd.service
[ "$manager" != apt ] || ssh_unit=ssh.service
# A previous marker is advisory: validate the actual packages/PID1/services on each boot.
if [ ! -f "$ROOT/etc/orchestella/provision-input/guest.password" ] \
    && [ ! -f "$ROOT/etc/orchestella/provision-input/guest.user" ] \
    && [ ! -f "$ROOT/etc/orchestella/provision-input/guest.sudo" ] \
    && [ ! -f "$STATE_DIR/legacy-weak-credentials" ] \
    && [ -f "$DONE" ] && grep -Fqx 'runtime_version=2' "$DONE" \
    && verify_packages && native_init_valid && native_pid1 \
    && sshd -t -f "$ROOT/etc/ssh/sshd_config.orchestella" >> "$LOG" 2>&1 && services_active; then
    status=ready; stage=verified; reason=already-complete
    write_state || fail_stage state-write persistent-state-unavailable
    report
    exit 0
fi
rm -f "$DONE"
write_state || fail_stage state-write persistent-state-unavailable
init_repair=0
native_init_valid || init_repair=1
[ ! -f "$STATE_DIR/init-repair-needed" ] || init_repair=1
if [ "$distro" = alpine ]; then
    busybox_path="$(guest_path /bin/busybox)" || fail_stage init-repair busybox-link-invalid
    if ! is_elf "$busybox_path"; then
        # Old /sbin/init writes followed the link and corrupted /bin/sh too. Recover only
        # the recognized project script from the verified static initramfs copy, then apk
        # reinstalls the distro-owned package. Unknown binary damage stays a visible failure.
        if grep -Fq '# Orchestella minimal Linux init supervisor' "$busybox_path" \
            && is_elf "$ROOT/usr/lib/orchestella/busybox"; then
            cp "$ROOT/usr/lib/orchestella/busybox" "$busybox_path.orchestella-restore" \
                && chmod 0755 "$busybox_path.orchestella-restore" \
                && mv -f "$busybox_path.orchestella-restore" "$busybox_path" \
                || fail_stage init-repair busybox-bootstrap-restore-failed
            init_repair=1
        else
            fail_stage init-repair unknown-busybox-damage
        fi
    fi
fi
# Rescue tools are needed above while the distro BusyBox may still be damaged.
# Package maintainer scripts need native tools first (GNU chmod --reference, mv -Z).
if [ -z "$ROOT" ] && [ -d /usr/lib/orchestella/bin ]; then
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/orchestella/bin
    export PATH
fi
# APT must not silently accept an incomplete refresh. Retain signatures and TLS verification.
refresh_apt() {
    run_step repository-refresh apt-get -o APT::Update::Error-Mode=any -o Acquire::Retries=0 \
        -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 update
}
# Multi-tier APT configs are tried one tier at a time, never as simultaneous repositories.
refresh_apt_tiers() {
    sources="$ROOT/etc/apt/sources.list"
    backup="$ROOT/etc/orchestella/apt-multi-tier.sources"
    active="$ROOT/etc/orchestella/apt-managed-active.sources"
    mkdir -p "$ROOT/etc/orchestella" || return 1
    if grep -Fq '# orchestella-mirror-policy=multi-tier' "$sources" 2>/dev/null; then
        cp "$sources" "$backup" || return 1
    elif [ ! -f "$active" ] || [ "$(cat "$sources")" != "$(cat "$active")" ]; then
        # A user-selected single mirror or edited sources must not be replaced by stale tiers.
        refresh_apt
        return "$?"
    fi
    if [ -f "$backup" ]; then
        for tier in 1 2 3; do
            selected_tier=0
            while IFS= read -r repo_line; do
                case "$repo_line" in
                    "# Tier $tier:"*) selected_tier=1 ;;
                    '# Tier '*) selected_tier=0 ;;
                    deb\ *) [ "$selected_tier" -ne 1 ] || printf '%s\n' "$repo_line" ;;
                esac
            done < "$backup" > "$sources.tmp"
            [ -s "$sources.tmp" ] || continue
            mv -f "$sources.tmp" "$sources" || return 1
            if refresh_apt; then return 0; fi
            printf 'APT mirror tier %s failed; trying next configured tier.\n' "$tier"
        done
        return 1
    fi
    refresh_apt
}
managed_repos_directory=
managed_repo_ids=
prepare_package_repositories() {
    case "$distro" in
        fedora) managed_repo_file=fedora.repo; expected_repo_ids='fedora updates' ;;
        almalinux) managed_repo_file=almalinux.repo; expected_repo_ids='baseos appstream' ;;
        rocky) managed_repo_file=rocky.repo; expected_repo_ids='baseos appstream' ;;
        opensuse) managed_repo_file=opensuse.repo; expected_repo_ids=repo-oss ;;
        *) return 0 ;;
    esac
    owned_directory="$ROOT/etc/orchestella/provision-repos/$distro"
    owned_marker="$owned_directory/owned-v1"
    if [ ! -f "$owned_marker" ]; then
        printf 'Package baseline repository scope: original vendor configuration (no injected managed input).\n' >> "$LOG"
        return 0
    fi
    [ ! -L "$owned_directory" ] && [ ! -L "$owned_marker" ] && [ ! -L "$owned_directory/$managed_repo_file" ] \
        || fail_stage repository-configuration managed-repository-link-invalid
    grep -Fqx 'managed_repositories_version=1' "$owned_marker" \
        && grep -Fqx "distro=$distro" "$owned_marker" \
        && grep -Fqx "file=$managed_repo_file" "$owned_marker" \
        && grep -Fqx "ids=$expected_repo_ids" "$owned_marker" \
        || fail_stage repository-configuration managed-repository-marker-invalid
    for owned_repo in "$owned_directory"/*.repo; do
        [ "$owned_repo" = "$owned_directory/$managed_repo_file" ] \
            || fail_stage repository-configuration unexpected-managed-repository
    done
    [ -f "$owned_directory/$managed_repo_file" ] || fail_stage repository-configuration managed-repository-missing
    owned_digest="$(sha256sum "$owned_directory/$managed_repo_file" | cut -d ' ' -f 1)"
    grep -Fqx "sha256=$owned_digest" "$owned_marker" \
        || fail_stage repository-configuration managed-repository-checksum-mismatch
    managed_repos_directory="$owned_directory"
    managed_repo_ids="$expected_repo_ids"
    printf 'Package baseline repository scope: managed IDs=%s; vendor files and cache retained.\n' "$managed_repo_ids" >> "$LOG"
}
dnf_step() {
    dnf_stage="$1"; shift
    if [ -n "$managed_repos_directory" ]; then
        set -- --setopt="reposdir=$managed_repos_directory" "$@"
    fi
    run_step "$dnf_stage" "$rpm_manager" -y --setopt=timeout=20 --setopt=retries=0 "$@"
}
zypper_step() {
    zypper_stage="$1"; zypper_action="$2"; shift 2
    if [ -n "$managed_repos_directory" ]; then
        case "$zypper_action" in
            refresh) set -- $managed_repo_ids "$@" ;;
            install)
                for managed_id in $managed_repo_ids; do set -- --repo "$managed_id" "$@"; done ;;
        esac
        set -- --no-refresh "$zypper_action" "$@"
        set -- --reposd-dir "$managed_repos_directory" --servicesd-dir "$STATE_DIR/empty-zypp-services" "$@"
    else
        set -- "$zypper_action" "$@"
    fi
    run_step "$zypper_stage" env "ZYPP_CONF=$STATE_DIR/zypp-baseline.conf" zypper --non-interactive "$@"
}
prepare_package_repositories
if [ "$manager" = zypper ]; then
    mkdir -p "$STATE_DIR/empty-zypp-services" || fail_stage repository-configuration services-directory-failed
    # Copy vendor policy for this transaction only; update its network limits, never TLS/GPG.
    if [ -f "$ROOT/etc/zypp/zypp.conf" ]; then
        sed '/^[[:space:]]*download[.]\(connect_timeout\|transfer_timeout\|max_silent_tries\)[[:space:]]*=/d' \
            "$ROOT/etc/zypp/zypp.conf" > "$STATE_DIR/zypp-baseline.conf.tmp" \
            || fail_stage repository-configuration zypp-config-copy-failed
    else
        printf '[main]\n' > "$STATE_DIR/zypp-baseline.conf.tmp"
    fi
    # libzypp's max_silent_tries=0 means unlimited; 1 means one try with no retry.
    printf '\n[main]\ndownload.connect_timeout = 20\ndownload.transfer_timeout = 20\ndownload.max_silent_tries = 1\n' \
        >> "$STATE_DIR/zypp-baseline.conf.tmp"
    mv "$STATE_DIR/zypp-baseline.conf.tmp" "$STATE_DIR/zypp-baseline.conf" \
        || fail_stage repository-configuration zypp-config-install-failed
fi
baseline_verified=0
if [ "$init_repair" -eq 0 ] \
    && grep -Fqx 'packages_version=2' "$STATE_DIR/packages-v2" 2>/dev/null \
    && grep -Fqx "distro=$distro" "$STATE_DIR/packages-v2" 2>/dev/null \
    && verify_packages && native_init_valid; then
    baseline_verified=1
    # A CA store deleted after bootstrap still requires repair; never trust only the checkpoint.
    if [ "$manager" = apt ] && [ ! -s "$ROOT/etc/ssl/certs/ca-certificates.crt" ]; then baseline_verified=0; fi
fi
if [ "$baseline_verified" -eq 0 ]; then
case "$manager" in
    apt)
        command -v apt-get >/dev/null 2>&1 || fail_stage package-manager apt-missing
        export DEBIAN_FRONTEND=noninteractive
        # Repair interrupted dpkg transactions without starting services before configuration.
        policy="$ROOT/usr/sbin/policy-rc.d"
        created_policy=0
        if [ ! -e "$policy" ]; then
            printf '#!/bin/sh\nexit 101\n' > "$policy" && chmod 0755 "$policy" || fail_stage package-policy write-failed
            created_policy=1
        fi
        if ! run_step package-recovery dpkg --configure -a; then
            [ "$created_policy" -eq 0 ] || rm -f "$policy"
            fail_stage package-recovery interrupted-package-transaction
        fi
        if [ ! -s "$ROOT/etc/ssl/certs/ca-certificates.crt" ]; then
            codename="$(read_os_field VERSION_CODENAME)"
            version="$(read_os_field VERSION_ID)"
            case "$distro:$codename:$version" in
                debian:trixie:*|debian::13*) codename=trixie ;;
                debian:bookworm:*|debian::12*) codename=bookworm ;;
                debian:bullseye:*|debian::11*) codename=bullseye ;;
                ubuntu:noble:*|ubuntu::24.04*) codename=noble ;;
                ubuntu:jammy:*|ubuntu::22.04*) codename=jammy ;;
                *) fail_stage certificate-bootstrap unsupported-release ;;
            esac
            ca_sources="$STATE_DIR/ca-bootstrap.list"
            if [ "$distro" = ubuntu ]; then
                printf 'deb http://ports.ubuntu.com/ubuntu-ports/ %s main\n' "$codename" > "$ca_sources"
            else
                printf 'deb http://deb.debian.org/debian/ %s main\n' "$codename" > "$ca_sources"
            fi
            mkdir -p "$STATE_DIR/ca-apt-lists/partial" || fail_stage certificate-bootstrap state-directory-failed
            # Only signed APT metadata/packages bootstrap the CA store. TLS/signature checks are
            # never weakened, and the user's selected sources remain byte-identical.
            run_step certificate-bootstrap-refresh apt-get -o APT::Update::Error-Mode=any \
                -o Dir::Etc::sourcelist="$ca_sources" -o Dir::Etc::sourceparts=- \
                -o Dir::State::lists="$STATE_DIR/ca-apt-lists" -o Acquire::Retries=0 \
                -o Acquire::http::Timeout=20 update || fail_stage certificate-bootstrap-refresh offline-or-signature-error
            run_step certificate-bootstrap-install apt-get \
                -o Dir::Etc::sourcelist="$ca_sources" -o Dir::Etc::sourceparts=- \
                -o Dir::State::lists="$STATE_DIR/ca-apt-lists" -o Acquire::Retries=0 \
                -o Acquire::http::Timeout=20 install -y --no-install-recommends ca-certificates \
                || fail_stage certificate-bootstrap-install ca-install-failed
            [ -s "$ROOT/etc/ssl/certs/ca-certificates.crt" ] || fail_stage certificate-bootstrap-verification ca-store-missing
        fi
        stage=repository-refresh
        write_state || fail_stage state-write persistent-state-unavailable
        report
        # The function runs under the already bounded package-manager network timeouts.
        if ! refresh_apt_tiers >> "$LOG" 2>&1; then
            [ "$created_policy" -eq 0 ] || rm -f "$policy"
            fail_stage repository-refresh offline-or-repository-error
        fi
        if ! run_step package-install apt-get -o Acquire::Retries=0 -o Acquire::http::Timeout=20 \
            -o Acquire::https::Timeout=20 install -y --no-install-recommends $packages; then
            [ "$created_policy" -eq 0 ] || rm -f "$policy"
            fail_stage package-install package-install-failed
        fi
        if [ "$init_repair" -eq 1 ]; then
            if ! run_step init-repair apt-get -o Acquire::Retries=0 install -y --reinstall systemd systemd-sysv; then
                [ "$created_policy" -eq 0 ] || rm -f "$policy"
                fail_stage init-repair native-init-reinstall-failed
            fi
        fi
        [ "$created_policy" -eq 0 ] || rm -f "$policy"
        ;;
    apk)
        command -v apk >/dev/null 2>&1 || fail_stage package-manager apk-missing
        run_step repository-refresh apk update || fail_stage repository-refresh offline-or-repository-error
        if [ "$init_repair" -eq 1 ]; then
            run_step init-repair apk fix --no-cache --reinstall busybox || fail_stage init-repair native-init-reinstall-failed
        fi
        run_step package-install apk add --no-cache $packages || fail_stage package-install package-install-failed
        ;;
    dnf)
        rpm_manager=dnf
        command -v dnf >/dev/null 2>&1 || rpm_manager=microdnf
        command -v "$rpm_manager" >/dev/null 2>&1 || fail_stage package-manager dnf-and-microdnf-missing
        dnf_step repository-refresh makecache || fail_stage repository-refresh offline-or-repository-error
        dnf_step package-install --setopt=install_weak_deps=False install $packages \
            || fail_stage package-install package-install-failed
        if [ "$init_repair" -eq 1 ]; then
            rpm_manager=dnf
            dnf_step init-repair reinstall systemd || fail_stage init-repair native-init-reinstall-failed
        fi
        ;;
    pacman)
        command -v pacman >/dev/null 2>&1 || fail_stage package-manager pacman-missing
        if [ ! -f "$ROOT/etc/pacman.d/gnupg/pubring.gpg" ]; then
            run_step package-signing pacman-key --init || fail_stage package-signing keyring-init-failed
            run_step package-signing pacman-key --populate archlinuxarm || fail_stage package-signing keyring-populate-failed
        fi
        run_step package-install pacman -Sy --noconfirm --needed $packages || fail_stage package-install offline-or-package-install-failed
        if [ "$init_repair" -eq 1 ]; then
            run_step init-repair pacman -S --noconfirm systemd || fail_stage init-repair native-init-reinstall-failed
        fi
        ;;
    zypper)
        command -v zypper >/dev/null 2>&1 || fail_stage package-manager zypper-missing
        zypper_step repository-refresh refresh || fail_stage repository-refresh offline-or-repository-error
        zypper_step package-install install --no-recommends $packages || fail_stage package-install package-install-failed
        if [ "$init_repair" -eq 1 ]; then
            zypper_step init-repair install --force systemd || fail_stage init-repair native-init-reinstall-failed
        fi
        ;;
esac
else
    stage=baseline-checkpoint; reason=packages-already-verified
    write_state || fail_stage state-write persistent-state-unavailable
    report
fi
stage=package-verification
verify_packages || fail_stage "$stage" installed-package-inventory-incomplete
native_init_valid || fail_stage init-verification native-init-invalid
rm -f "$STATE_DIR/init-repair-needed"
# Credentials arrive from the host only through the existing verified input channel.
identity="$ROOT/etc/orchestella/provision-input"
if [ -f "$identity/guest.password" ]; then
    password="$(cat "$identity/guest.password")"
    case "$password" in ''|*:*|*[[:space:]]*) fail_stage credentials invalid-password ;; esac
    [ "${#password}" -ge 8 ] || fail_stage credentials weak-password
    if ! printf 'root:%s\n' "$password" | chpasswd >> "$LOG" 2>&1; then fail_stage credentials password-application-failed; fi
    if [ -f "$identity/guest.user" ]; then
        user="$(cat "$identity/guest.user")"
        case "$user" in ''|[!a-z_]*|*[!a-z0-9_-]*) fail_stage credentials invalid-user ;; esac
        [ "${#user}" -ge 2 ] && [ "${#user}" -le 16 ] || fail_stage credentials invalid-user
        if [ "$user" != root ]; then
            if ! id -u "$user" >/dev/null 2>&1; then
                if [ "$distro" = alpine ]; then
                    adduser -D -s /bin/bash "$user" >> "$LOG" 2>&1 || fail_stage credentials user-creation-failed
                else
                    useradd -m -s /bin/bash "$user" >> "$LOG" 2>&1 || fail_stage credentials user-creation-failed
                fi
            fi
            printf '%s:%s\n' "$user" "$password" | chpasswd >> "$LOG" 2>&1 || fail_stage credentials password-application-failed
            managed_sudo="$ROOT/etc/sudoers.d/99-orchestella-user"
            [ ! -L "$managed_sudo" ] || fail_stage credentials sudo-configuration-unsafe
            if [ -f "$managed_sudo" ] \
                && ! grep -Fqx '# Orchestella owned sudo schema=1' "$managed_sudo" \
                && ! { [ "$(wc -l < "$managed_sudo")" -eq 1 ] && grep -Eq '^[a-z_][a-z0-9_-]{1,15} ALL=\(ALL\) NOPASSWD:ALL$' "$managed_sudo"; }; then
                fail_stage credentials sudo-configuration-user-owned
            fi
            if grep -Fqx '1' "$identity/guest.sudo" 2>/dev/null; then
                mkdir -p "$ROOT/etc/sudoers.d" || fail_stage credentials sudo-configuration-failed
                printf '# Orchestella owned sudo schema=1\n%s ALL=(ALL) NOPASSWD:ALL\n' "$user" > "$managed_sudo"
                chmod 0440 "$managed_sudo" || fail_stage credentials sudo-configuration-failed
                visudo -cf "$managed_sudo" >> "$LOG" 2>&1 || fail_stage credentials sudo-configuration-invalid
            else
                # Revoke only this exact managed grant; preserve all user-authored sudo configuration.
                rm -f "$managed_sudo" || fail_stage credentials sudo-configuration-failed
            fi
        fi
    fi
    credential_user="$(cat "$identity/guest.user" 2>/dev/null || printf root)"
    credential_sudo=0
    if grep -Fqx '1' "$identity/guest.sudo" 2>/dev/null; then credential_sudo=1; fi
    input_fingerprint="$(printf '%s\n%s\n%s\n' "$password" "$credential_user" "$credential_sudo" | sha256sum | cut -d ' ' -f 1)"
    unset password
    printf 'credentials_version=2\npassword_applied=1\ninput_sha256=%s\n' "$input_fingerprint" > "$STATE_DIR/credentials-v2.tmp" \
        && mv -f "$STATE_DIR/credentials-v2.tmp" "$STATE_DIR/credentials-v2" \
        || fail_stage credentials credential-state-write-failed
    rm -f "$identity/guest.password" "$identity/guest.user" "$identity/guest.sudo"
fi
if [ "$baseline_verified" -eq 0 ]; then
    case "$manager" in
        dnf|pacman) run_step certificates update-ca-trust extract || fail_stage certificates certificate-store-failed ;;
        *) run_step certificates update-ca-certificates || fail_stage certificates certificate-store-failed ;;
    esac
fi
if [ ! -s "$ROOT/etc/machine-id" ]; then
    run_step machine-identity dbus-uuidgen --ensure="$ROOT/etc/machine-id" || fail_stage machine-identity machine-id-failed
fi
mkdir -p "$ROOT/etc/default" "$ROOT/etc/profile.d" "$ROOT/etc/ssh" || fail_stage configuration write-failed
printf 'LANG=C.UTF-8\n' > "$ROOT/etc/default/locale"
printf 'LANG=C.UTF-8\n' > "$ROOT/etc/locale.conf"
printf 'export LANG=C.UTF-8\n' > "$ROOT/etc/profile.d/01-orchestella-locale.sh"
if [ "$manager" = apt ] || [ "$manager" = pacman ]; then
    if [ -f "$ROOT/etc/locale.gen" ] && ! grep -Fqx 'en_US.UTF-8 UTF-8' "$ROOT/etc/locale.gen"; then
        printf '\nen_US.UTF-8 UTF-8\n' >> "$ROOT/etc/locale.gen"
    fi
    if ! locale -a 2>/dev/null | grep -Eiq '^en_US[.]utf-?8$'; then
        run_step locale locale-gen || fail_stage locale utf8-locale-generation-failed
    fi
fi
# A dedicated config prevents distro defaults enabling empty auth. Random host credentials
# become usable only after their application succeeds; no host listener is created here.
password_auth=no
root_login=prohibit-password
if [ ! -f "$STATE_DIR/legacy-weak-credentials" ] \
    && grep -Fqx 'password_applied=1' "$STATE_DIR/credentials-v2" 2>/dev/null; then
    password_auth=yes
    root_login=yes
fi
printf 'HostKey /etc/ssh/ssh_host_ed25519_key\nPermitRootLogin %s\nPasswordAuthentication %s\nKbdInteractiveAuthentication no\nPermitEmptyPasswords no\nPubkeyAuthentication yes\nUsePAM no\nX11Forwarding no\nSubsystem sftp internal-sftp\n' "$root_login" "$password_auth" \
    > "$ROOT/etc/ssh/sshd_config.orchestella" || fail_stage ssh-configuration write-failed
if [ ! -f "$ROOT/etc/ssh/ssh_host_ed25519_key" ]; then
    run_step ssh-host-keys ssh-keygen -q -t ed25519 -N '' -f "$ROOT/etc/ssh/ssh_host_ed25519_key" \
        || fail_stage ssh-host-keys key-generation-failed
fi
mkdir -p "$ROOT/run/sshd" || fail_stage ssh-configuration write-failed
run_step ssh-configuration sshd -t -f "$ROOT/etc/ssh/sshd_config.orchestella" || fail_stage ssh-configuration config-validation-failed
# Only guest-internal services. No host port-forwarding or LAN binding is created here.
if [ "$distro" = alpine ]; then
    mkdir -p "$ROOT/etc/conf.d" "$ROOT/etc/runlevels/sysinit" "$ROOT/etc/runlevels/boot" "$ROOT/etc/runlevels/default"
    printf 'sshd_opts="-f /etc/ssh/sshd_config.orchestella"\n' > "$ROOT/etc/conf.d/sshd"
    for service in devfs dmesg mdev; do
        [ ! -f "$ROOT/etc/init.d/$service" ] || ln -sf "/etc/init.d/$service" "$ROOT/etc/runlevels/sysinit/$service"
    done
    for service in bootmisc hostname sysctl syslog; do
        [ ! -f "$ROOT/etc/init.d/$service" ] || ln -sf "/etc/init.d/$service" "$ROOT/etc/runlevels/boot/$service"
    done
    for service in dbus sshd droidlayer-guest-agent orchestella-firstboot; do
        [ -f "$ROOT/etc/init.d/$service" ] || fail_stage services required-openrc-service-missing
        ln -sf "/etc/init.d/$service" "$ROOT/etc/runlevels/default/$service" || fail_stage services enable-failed
    done
else
    sshd_program="$(command -v sshd)"
    if [ -n "$ROOT" ]; then
        sshd_program=/usr/sbin/sshd # synthetic backend, never a host path
        [ "$distro" != fedora ] || sshd_program=/usr/bin/sshd
    fi
    case "$sshd_program" in /usr/bin/sshd|/usr/sbin/sshd|/sbin/sshd) ;; *) fail_stage ssh-configuration unsupported-daemon-path ;; esac
    mkdir -p "$ROOT/etc/systemd/system/$ssh_unit.d" "$ROOT/etc/systemd/system/multi-user.target.wants"
    printf '[Service]\nExecStartPre=\nExecStartPre=%s -t -f /etc/ssh/sshd_config.orchestella\nExecStart=\nExecStart=%s -D -f /etc/ssh/sshd_config.orchestella\n' "$sshd_program" "$sshd_program" \
        > "$ROOT/etc/systemd/system/$ssh_unit.d/orchestella.conf"
    # Broker implementations expose dbus.service as an alias; enable the real installed unit.
    dbus_enable_unit=dbus.service
    if [ -f "$ROOT/usr/lib/systemd/system/dbus-broker.service" ]; then dbus_enable_unit=dbus-broker.service; fi
    # --root configures enablement without requiring a running manager during bootstrap.
    run_step service-enablement systemctl --root="${ROOT:-/}" enable "$dbus_enable_unit" "$ssh_unit" \
        || fail_stage service-enablement service-enable-failed
fi
if [ "${ORCHESTELLA_AUTO_UPDATE:-0}" = 1 ] && [ ! -f "$STATE_DIR/update-v2" ]; then
    case "$manager" in
        apt) run_step optional-upgrade apt-get -o Acquire::Retries=0 upgrade -y || fail_stage optional-upgrade upgrade-failed ;;
        apk) run_step optional-upgrade apk upgrade || fail_stage optional-upgrade upgrade-failed ;;
        dnf) run_step optional-upgrade dnf -y --setopt=timeout=20 --setopt=retries=0 upgrade || fail_stage optional-upgrade upgrade-failed ;;
        pacman) : ;; # The baseline transaction already performed a full supported upgrade.
        zypper) run_step optional-upgrade zypper --non-interactive update || fail_stage optional-upgrade upgrade-failed ;;
    esac
    printf 'update_version=2\n' > "$STATE_DIR/update-v2.tmp" && mv -f "$STATE_DIR/update-v2.tmp" "$STATE_DIR/update-v2" \
        || fail_stage state-write persistent-state-unavailable
fi
printf 'packages_version=2\ndistro=%s\n' "$distro" > "$STATE_DIR/packages-v2.tmp" \
    && mv -f "$STATE_DIR/packages-v2.tmp" "$STATE_DIR/packages-v2" || fail_stage state-write persistent-state-unavailable
if [ "$MODE" = --bootstrap ] || ! native_pid1; then
    status=awaiting-init; stage=native-handoff; reason=services-not-yet-verified
    write_state || fail_stage state-write persistent-state-unavailable
    report
    exit 0
fi
if [ "$distro" = alpine ]; then
    run_step dbus-start rc-service dbus start || fail_stage dbus-start service-start-failed
    run_step ssh-start rc-service sshd restart || fail_stage ssh-start service-start-failed
else
    run_step manager-reload systemctl daemon-reload || fail_stage manager-reload manager-unavailable
    run_step dbus-start systemctl start dbus.service || fail_stage dbus-start service-start-failed
    run_step ssh-start systemctl restart "$ssh_unit" || fail_stage ssh-start service-start-failed
fi
native_pid1 && services_active || fail_stage service-verification native-init-or-service-not-active
if [ -f "$STATE_DIR/legacy-weak-credentials" ]; then
    status=action-required; stage=credentials; reason=legacy-credential-action-required
    write_state || fail_stage state-write persistent-state-unavailable
    report
    exit 0
fi
printf 'runtime_version=2\ndistro=%s\n' "$distro" > "$DONE.tmp" \
    && mv -f "$DONE.tmp" "$DONE" || fail_stage state-write persistent-state-unavailable
status=ready; stage=verified; reason=none
write_state || fail_stage state-write persistent-state-unavailable
report
