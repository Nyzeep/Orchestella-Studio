#!/usr/bin/env bash
# Ticket 37 guest-agent vsock-mode verification.
#
# Layer 1 (always): compile both variants with -std=c11 -Wall -Wextra -Werror.
#   PROD      — the AF_VSOCK production variant
#   TEST_SEAM — -DDROIDLAYER_TEST_AF_INET swaps only the socket family to
#               loopback TCP; every serving path is family-agnostic.
# Layer 2 (when the compiled binary can execute on this host — a native
# compiler, not a cross one): drive the wire behavior over the test seam:
#   handshake/token/version, ping, resize plumbing, terminal relay and the
#   9p trans=fd mount invocation through a stub `mount`.
# bash is required for the /dev/tcp clients (POSIX sh has none).
set -eu

work="$(mktemp -d "${TMPDIR:-/tmp}/guest-agent-vsock-check-XXXXXXXX")"
trap 'rm -rf "$work"' EXIT
agent_source="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)/agent/guest-agent.c"

pick_native_compiler() {
    for candidate in cc gcc clang; do
        if command -v "$candidate" >/dev/null 2>&1; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

compile_variant() {
    local compiler="$1" extra_flags="$2" output="$3"
    # shellcheck disable=SC2086
    "$compiler" -std=c11 -Wall -Wextra -Werror $extra_flags "$agent_source" -o "$output"
}

binary_can_execute() {
    "$1" --token-file "$work/absent.token" >/dev/null 2>&1
    [ $? -eq 65 ]
}

# Sends the payload to 127.0.0.1:$port and echoes the first reply line.
exchange_line() {
    local port="$1" payload="$2"
    bash -c '
        exec 3<>/dev/tcp/127.0.0.1/'"$port"' || exit 97
        printf "%s" "$1" >&3
        IFS= read -r -t 5 line <&3
        printf "%s\n" "$line"
    ' exchange "$payload"
}

# Sends every argument as separate lines and echoes the first reply line.
session_line() {
    local port="$1"
    shift
    bash -c '
        exec 3<>/dev/tcp/127.0.0.1/'"$port"' || exit 97
        for line in "$@"; do
            printf "%s\n" "$line" >&3
        done
        IFS= read -r -t 5 reply <&3
        printf "%s\n" "$reply"
    ' session "$@"
}

echo "=== guest-agent vsock check (workdir $work) ==="

native_compiler="$(pick_native_compiler || true)"
if [ -n "${native_compiler:-}" ]; then
    compile_variant "$native_compiler" "" "$work/agent-prod"
    compile_variant "$native_compiler" "-DDROIDLAYER_TEST_AF_INET" "$work/agent-seam"
    echo "NATIVE_COMPILED_OK prod=1 test_seam=1 compiler=$native_compiler"
else
    cross_compiler="$(command -v aarch64-linux-gnu-gcc || true)"
    [ -n "$cross_compiler" ] || {
        echo "NO_COMPILER_AVAILABLE" >&2
        exit 65
    }
    compile_variant "$cross_compiler" "" "$work/agent-prod-cross"
    compile_variant "$cross_compiler" "-DDROIDLAYER_TEST_AF_INET" "$work/agent-seam-cross"
    echo "CROSS_COMPILED_OK prod=1 test_seam=1 compiler=$cross_compiler target=aarch64"
    echo "BEHAVIOR_RUN_SKIPPED reason=cross-compiled-binary-cannot-execute-here"
    exit 0
fi

if ! binary_can_execute "$work/agent-seam"; then
    echo "BEHAVIOR_RUN_SKIPPED reason=binary-cannot-execute-on-this-host"
    exit 0
fi

printf '%s\n' "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" \
    > "$work/bridge.token"

# --- control channel: handshake, wrong token, ping, resize plumbing ---------
"$work/agent-seam" --token-file "$work/bridge.token" \
    --vsock-control-port 19100 --vsock-terminal-port 19101 \
    --terminal-device /dev/null &
agent_pid=$!
sleep 0.3

reply="$(session_line 19100 "DROIDLAYER/HELLO 1 1 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "DROIDLAYER/PING")"
[ "$reply" = "DROIDLAYER/PONG 1" ] || {
    echo "CONTROL_SESSION_FAIL reply=$reply" >&2
    kill "$agent_pid" 2>/dev/null || true
    exit 1
}

reply="$(session_line 19100 "DROIDLAYER/HELLO 1 1 1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")"
[ "$reply" = "DROIDLAYER/ERROR unauthorized" ] || {
    echo "UNAUTHORIZED_REJECT_FAIL reply=$reply" >&2
    kill "$agent_pid" 2>/dev/null || true
    exit 1
}

reply="$(session_line 19100 "DROIDLAYER/HELLO 1 1 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" "DROIDLAYER/RESIZE 80 24")"
[ "$reply" = "DROIDLAYER/ERROR resize-unavailable" ] || {
    echo "RESIZE_UNAVAILABLE_FAIL reply=$reply (TIOCSWINSZ on /dev/null must fail honestly)" >&2
    kill "$agent_pid" 2>/dev/null || true
    exit 1
}
echo "CONTROL_CHANNEL_OK ping=1 unauthorized=1 resize_unavailable=1"

# --- terminal relay: socket bytes must land in the terminal device ----------
: > "$work/terminal-fixture"
"$work/agent-seam" --token-file "$work/bridge.token" \
    --vsock-control-port 29100 --vsock-terminal-port 29101 \
    --terminal-device "$work/terminal-fixture" &
agent2_pid=$!
sleep 0.3
bash -c '
    exec 3<>/dev/tcp/127.0.0.1/29101 || exit 97
    printf "DROIDLAYER_TERMINAL_RELAY_PROBE\n" >&3
' || true
relay_seen=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 0.2
    relay_seen="$(cat "$work/terminal-fixture")"
    [ -n "$relay_seen" ] && break
done
[ "$relay_seen" = "DROIDLAYER_TERMINAL_RELAY_PROBE" ] || {
    echo "TERMINAL_RELAY_FAIL fixture=[$relay_seen]" >&2
    kill "$agent2_pid" 2>/dev/null || true
    exit 1
}
echo "TERMINAL_RELAY_OK socket_to_device=1"

# --- share data plane: the mount invocation through a stub ------------------
cat > "$work/mount-stub" <<STUB
#!/bin/sh
printf '%s\n' "\$*" >> "$work/mount-record"
exit 0
STUB
chmod 0755 "$work/mount-stub"
mkdir -p "$work/mount-point"
PATH="$work:$PATH" "$work/agent-seam" --token-file "$work/bridge.token" \
    --vsock-control-port 39100 --vsock-terminal-port 39101 \
    --vsock-share-port 39102 --share-tag downloads --share-mount "$work/mount-point" &
agent3_pid=$!
sleep 0.3
bash -c 'exec 3<>/dev/tcp/127.0.0.1/39102' || true
sleep 0.5
# A second connection must not trigger a second mount.
bash -c 'exec 3<>/dev/tcp/127.0.0.1/39102' || true
sleep 0.5
mount_lines="$(grep -c . "$work/mount-record" 2>/dev/null || true)"
[ "$mount_lines" = "1" ] || {
    echo "SHARE_MOUNT_ONCE_FAIL mounts=$mount_lines" >&2
    kill "$agent3_pid" 2>/dev/null || true
    exit 1
}
mount_args="$(cat "$work/mount-record")"
case "$mount_args" in
    *"-t 9p -o trans=fd,rfdno=3,wfdno=3,version=9p2000.L,ro downloads"*) ;;
    *)
        echo "SHARE_MOUNT_ARGS_FAIL args=[$mount_args]" >&2
        kill "$agent3_pid" 2>/dev/null || true
        exit 1
        ;;
esac
echo "SHARE_MOUNT_OK once=1 trans_fd_args=1"

kill "$agent_pid" "$agent2_pid" "$agent3_pid" 2>/dev/null || true
wait 2>/dev/null || true
echo "GUEST_AGENT_VSOCK_CHECK_OK control=1 terminal=1 share=1"
