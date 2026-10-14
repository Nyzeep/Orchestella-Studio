#!/bin/sh
# Bootstrap only Debian 13/Ubuntu 24.04 ARM64, after the mandatory baseline.
# No init changes, host operations, service auto-enable, or unknown downloads.
set -u
umask 077
ROOT=''
RUNNER=''
if [ "${1:-}" = --test-root ]; then
    [ "$#" -ge 6 ] && [ "${3:-}" = --test-runner ] || exit 1
    ROOT="$2"; RUNNER="$4"; shift 4
    [ -f "$ROOT/.orchestella-profile-test-root" ] && [ -x "$RUNNER" ] || exit 1
else
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export PATH
fi
DIR="$ROOT/var/lib/orchestella/features"
LOCK="$DIR/python-bootstrap.lock"
PAYLOAD=/usr/local/lib/orchestella
[ -z "$ROOT" ] || PAYLOAD="${0%/*}"
[ "$#" -eq 2 ] || exit 1
profile="${2:-}"
command_run() {
    if [ -n "$ROOT" ]; then "$RUNNER" "$ROOT" "$@"; else "$@"; fi
}
case "$profile" in desktop-labwc|podman|code-server|docker-cli) ;; *) printf '%s\n' '{"version":1,"profile":"none","state":"failed","installed":false,"reason":"unknown-profile"}'; exit 1 ;; esac
state="$DIR/bootstrap-$profile.json"
response() {
    printf '{"version":1,"profile":"%s","state":"%s","installed":false,"reason":"%s","phase":"%s","generation":"none","input":{"keyboard":false,"unicodeText":false,"pointer":false},"authenticationConfigured":false}\n' "$profile" "$1" "$2" "$3"
}
write_state() {
    response "$1" "$2" "$3" > "$state.tmp" && mv -f "$state.tmp" "$state"
}
case "${1:-}" in
    status) if [ -f "$state" ]; then cat "$state"; else response planned python3-unavailable none; fi; exit 0 ;;
    cancel)
        if [ -d "$LOCK" ]; then
            [ "$(cat "$LOCK/profile" 2>/dev/null || true)" = "$profile" ] || { response failed busy bootstrap; exit 1; }
            : > "$LOCK/cancel"
        fi
        response cancelled cancelled cancelling; exit 0 ;;
    start)
        [ -n "$ROOT" ] || [ "$(id -u)" = 0 ] || { response failed root-required none; exit 1; }
        mkdir -p "$DIR" || { response failed disk-full bootstrap; exit 1; }
        if ! mkdir "$LOCK" 2>/dev/null; then
            owner="$(cat "$LOCK/pid" 2>/dev/null || true)"
            case "$owner" in ''|*[!0-9]*) owner=0 ;; esac
            if [ "$owner" -gt 0 ] && kill -0 "$owner" 2>/dev/null; then
                if [ "$(cat "$LOCK/profile" 2>/dev/null || true)" = "$profile" ]; then response installing none waiting-for-base; exit 0; fi
                response failed busy bootstrap; exit 1
            fi
            group="$(cat "$LOCK/pgrp" 2>/dev/null || true)"
            case "$group" in ''|*[!0-9]*) group=0 ;; esac
            if [ "$group" -gt 0 ] && ps -o stat= -g "$group" 2>/dev/null | grep -Eq '^[[:space:]]*[^Z[:space:]]'; then response failed busy bootstrap; exit 1; fi
            rm -f "$LOCK/pid" "$LOCK/profile" "$LOCK/cancel" "$LOCK/pgrp"
            rmdir "$LOCK" && mkdir "$LOCK" || { response failed busy bootstrap; exit 1; }
        fi
        printf '%s\n' "$profile" > "$LOCK/profile"
        write_state installing none waiting-for-base || { rmdir "$LOCK" 2>/dev/null || true; response failed disk-full bootstrap; exit 1; }
        # Every inherited control descriptor is closed by these redirects.
        if [ -n "$ROOT" ]; then
            setsid /bin/sh "$PAYLOAD/bootstrap-python.sh" --test-root "$ROOT" --test-runner "$RUNNER" worker "$profile" </dev/null >/dev/null 2>&1 &
        else
            setsid /bin/sh "$PAYLOAD/bootstrap-python.sh" worker "$profile" </dev/null >/dev/null 2>&1 &
        fi
        printf '%s\n' "$!" > "$LOCK/pid"
        response installing none waiting-for-base
        exit 0 ;;
    worker) ;;
    *) response failed malformed-command none; exit 1 ;;
esac
[ -n "$ROOT" ] || [ "$(id -u)" = 0 ] || exit 1
child=0
cancel_requested=0
cleanup() {
    trap - EXIT INT TERM HUP
    if [ "$child" -gt 0 ]; then
        /bin/kill -TERM -- "-$child" 2>/dev/null || true
        attempts=0
        while /bin/kill -0 -- "-$child" 2>/dev/null && [ "$attempts" -lt 20 ]; do
            sleep 0.1; attempts=$((attempts + 1))
        done
        if /bin/kill -0 -- "-$child" 2>/dev/null; then /bin/kill -KILL -- "-$child" 2>/dev/null || true; fi
        wait "$child" 2>/dev/null || true
        # A remaining live descendant means cancellation has not closed the transaction.
        if ps -o stat= -g "$child" 2>/dev/null | grep -Eq '^[[:space:]]*[^Z[:space:]]'; then
            write_state failed bootstrap-cancel-incomplete cancelling
            return 1
        fi
        child=0
    fi
    rm -f "$LOCK/pid" "$LOCK/profile" "$LOCK/cancel" "$LOCK/pgrp"
    rmdir "$LOCK" 2>/dev/null || true
    if [ "$cancel_requested" -eq 1 ]; then write_state cancelled cancelled stopped; fi
}
trap cleanup EXIT
trap 'cancel_requested=1; exit 1' INT TERM HUP
check_cancel() {
    if [ -f "$LOCK/cancel" ]; then cancel_requested=1; write_state installing cancelled cancelling; exit 1; fi
}
failure() { write_state failed "$1" "$2"; exit 1; }
# No source of os-release; it is data, never shell code.
distro="$(sed -n 's/^ID=//p' "$ROOT/etc/os-release" | tr -d '"\047\r')"
version="$(sed -n 's/^VERSION_ID=//p' "$ROOT/etc/os-release" | tr -d '"\047\r')"
case "$distro:$version:$profile" in
    debian:13:*) ;;
    ubuntu:24.04:podman|ubuntu:24.04:code-server) ;;
    *) failure unsupported-distro bootstrap ;;
esac
[ -n "$ROOT" ] || [ "$(uname -m)" = aarch64 ] || failure unsupported-architecture bootstrap
command -v setsid >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 || failure bootstrap-tools-missing bootstrap
elapsed=0
while :; do
    check_cancel
    if grep -Fqx 'status=failed' "$ROOT/var/lib/orchestella/provision/state" 2>/dev/null; then failure base-provision-failed waiting-for-base; fi
    if grep -Fqx 'version=2' "$ROOT/var/lib/orchestella/provision/state" 2>/dev/null \
        && grep -Fqx 'status=ready' "$ROOT/var/lib/orchestella/provision/state" \
        && grep -Fqx systemd "$ROOT/proc/1/comm" \
        && [ ! -d "$ROOT/run/orchestella-provision.lock" ] \
        && command_run systemctl is-active --quiet dbus.service ssh.service; then break; fi
    elapsed=$((elapsed + 1)); [ "$elapsed" -lt 600 ] || failure base-wait-timeout waiting-for-base
    sleep 1
done
# Reject known trust bypasses before even refreshing indexes.
for source in "$ROOT/etc/apt/sources.list" "$ROOT"/etc/apt/sources.list.d/*.list "$ROOT"/etc/apt/sources.list.d/*.sources; do
    [ -f "$source" ] || continue
    [ ! -L "$source" ] || failure unsafe-package-source bootstrap
    if grep -Eiq '(trusted|allow-insecure|allow-weak|allow-downgrade-to-insecure)[[:space:]]*(=|:)[[:space:]]*(yes|true|1)' "$source"; then failure unsafe-package-source bootstrap; fi
done
run_step() {
    check_cancel
    if [ -n "$ROOT" ]; then
        setsid timeout 300 "$RUNNER" "$ROOT" "$@" </dev/null >/dev/null 2>&1 &
    else
        setsid timeout 300 "$@" </dev/null >/dev/null 2>&1 &
    fi
    child="$!"
    printf '%s\n' "$child" > "$LOCK/pgrp"
    while kill -0 "$child" 2>/dev/null; do check_cancel; sleep 1; done
    wait "$child"; code="$?"; child=0
    [ "$code" -eq 0 ] || failure signed-python-install-failed bootstrap-python
}
write_state installing none bootstrap-python || failure disk-full bootstrap
run_step apt-get -o Acquire::Retries=0 -o Acquire::AllowInsecureRepositories=false -o Acquire::AllowDowngradeToInsecureRepositories=false -o APT::Get::AllowUnauthenticated=false -o APT::Update::Error-Mode=any update
run_step apt-get -o DPkg::Lock::Timeout=30 -o Acquire::Retries=0 -o APT::Get::AllowUnauthenticated=false --yes --no-install-recommends install python3
check_cancel
command -v python3 >/dev/null 2>&1 || failure python3-unavailable bootstrap
cleanup
trap - EXIT INT TERM HUP
if [ -n "$ROOT" ]; then
    exec python3 -B "$PAYLOAD/profilectl.py" --test-root "$ROOT" --test-runner "$RUNNER" install "$profile"
fi
exec python3 -B "$PAYLOAD/profilectl.py" install "$profile"
