#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
BRIDGE_EXPECTATION="${TICKET26_BRIDGE_EXPECTATION:-valid}"
WORK_ROOT="${TICKET26_WORK_ROOT:-}"
WORK_DIR=""
QEMU_PID=""
CONTROL_RELAY_PID=""
TERMINAL_RELAY_PID=""
CONTROL_READER_PID=""
TERMINAL_READER_PID=""

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'required bridge-control smoke command is unavailable: %s\n' "$command_name" >&2
    exit 69
  }
}

stop_process() {
  local process_id="$1"
  if [[ -n "$process_id" ]] && kill -0 "$process_id" 2>/dev/null; then
    kill -TERM "$process_id" 2>/dev/null || true
    for ((attempt = 0; attempt < 20; attempt += 1)); do
      kill -0 "$process_id" 2>/dev/null || return 0
      sleep 0.1
    done
    kill -KILL "$process_id" 2>/dev/null || true
  fi
}

contains_normalized_line() {
  local path="$1"
  local expected_line="$2"
  tr -d '\r' < "$path" | grep --fixed-strings --line-regexp "$expected_line" >/dev/null
}

wait_for_line() {
  local path="$1"
  local expected_line="$2"
  for ((attempt = 0; attempt < 200; attempt += 1)); do
    if contains_normalized_line "$path" "$expected_line"; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

# cleanup is invoked via `trap cleanup EXIT INT TERM`; shellcheck cannot see trap reachability
# shellcheck disable=SC2329
cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    printf 'bridge-control smoke diagnostic status=%s control_socket=%s terminal_socket=%s control_bytes=%s terminal_bytes=%s\n' \
      "$status" \
      "$( [[ -n "${CONTROL_SOCKET:-}" ]] && printf 1 || printf 0 )" \
      "$( [[ -n "${TERMINAL_SOCKET:-}" ]] && printf 1 || printf 0 )" \
      "$( [[ -f "$CONTROL_TRANSCRIPT" ]] && wc -c < "$CONTROL_TRANSCRIPT" || printf 0 )" \
      "$( [[ -f "$TERMINAL_TRANSCRIPT" ]] && wc -c < "$TERMINAL_TRANSCRIPT" || printf 0 )" >&2
    if [[ -f "$QEMU_LOG" ]]; then
      tr -d '\r' < "$QEMU_LOG" \
        | grep --extended-regexp '^(qemu-system-aarch64:|qemu:|Error:|error:)' \
        | sed "s|$WORK_DIR|<temp>|g" \
        | head -n 5 >&2 || true
    fi
  fi
  exec 3>&- || true
  exec 4>&- || true
  stop_process "$CONTROL_READER_PID"
  stop_process "$TERMINAL_READER_PID"
  stop_process "$CONTROL_RELAY_PID"
  stop_process "$TERMINAL_RELAY_PID"
  stop_process "$QEMU_PID"
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    case "$(realpath -- "$WORK_DIR")" in
      "$WORK_ROOT"/droidlayer-t26-implementation-bridge-control.*)
        if [[ "$status" -eq 0 ]]; then
          rm -rf -- "$WORK_DIR"
        else
          printf 'bridge-control failure fixture retained: %s\n' "$WORK_DIR" >&2
        fi
        ;;
      *)
        printf 'refusing to clean unsafe bridge-control smoke directory: %s\n' "$WORK_DIR" >&2
        status=70
        ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for required_command in \
  aarch64-linux-gnu-gcc cpio gzip qemu-system-aarch64 setpriv sha256sum socat timeout; do
  require_command "$required_command"
done

verify_ticket26_ticket25_asset_root "$ASSET_ROOT" || exit "$?"
[[ -n "$WORK_ROOT" && -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || {
  printf 'TICKET26_WORK_ROOT must be one approved ordinary directory\n' >&2
  exit 64
}
WORK_ROOT="$(realpath -e -- "$WORK_ROOT")"

WORK_DIR="$(mktemp -d "$WORK_ROOT/droidlayer-t26-implementation-bridge-control.XXXXXX")"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/bridge-control-initramfs.cpio.gz"
AGENT="$WORK_DIR/guest-agent"
CONTROL_SOCKET="$(basename -- "$WORK_DIR")-control"
TERMINAL_SOCKET="$(basename -- "$WORK_DIR")-terminal"
CONTROL_PTY="$WORK_DIR/control.pty"
TERMINAL_PTY="$WORK_DIR/terminal.pty"
CONTROL_TRANSCRIPT="$WORK_DIR/control-transcript"
TERMINAL_TRANSCRIPT="$WORK_DIR/terminal-transcript"
QEMU_LOG="$WORK_DIR/qemu.log"
CONTROL_RELAY_LOG="$WORK_DIR/control-relay.log"
TERMINAL_RELAY_LOG="$WORK_DIR/terminal-relay.log"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
INCORRECT_TOKEN="1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
TERMINAL_SENTINEL="DROIDLAYER_BRIDGE_CONTROL_TERMINAL_SENTINEL"
GUEST_TERMINAL_STARTED="DROIDLAYER_BRIDGE_CONTROL_GUEST_TERMINAL_STARTED"

chmod 0700 "$WORK_DIR"
chown 1000:1000 "$WORK_DIR"

case "$BRIDGE_EXPECTATION" in
  valid|unauthorized) ;;
  *)
    printf 'TICKET26_BRIDGE_EXPECTATION must be valid or unauthorized\n' >&2
    exit 64
    ;;
esac

mkdir -p -- "$INITRAMFS_ROOT"
gzip -dc -- "$ASSET_ROOT/initramfs.cpio.gz" \
  | (cd -- "$INITRAMFS_ROOT" && cpio --extract --make-directories --unconditional \
      --no-absolute-filenames --no-preserve-owner --quiet \
      --nonmatching dev/console dev/null)
[[ -d "$INITRAMFS_ROOT/dev" && ! -e "$INITRAMFS_ROOT/dev/console" \
  && ! -e "$INITRAMFS_ROOT/dev/null" ]] || exit 1

aarch64-linux-gnu-gcc -static -std=c11 -Wall -Wextra -Werror \
  "$GUEST_ASSETS_DIR/agent/guest-agent.c" \
  -o "$AGENT"
install -D -m 0755 "$AGENT" "$INITRAMFS_ROOT/opt/droidlayer/guest-agent"
install -D -m 0600 /dev/null "$INITRAMFS_ROOT/etc/droidlayer/bridge.token"
printf '%s\n' "$SYNTHETIC_TOKEN" > "$INITRAMFS_ROOT/etc/droidlayer/bridge.token"

cat > "$INITRAMFS_ROOT/init" <<'INIT'
#!/bin/sh
set -eu

mount -t devtmpfs devtmpfs /dev
mount -t proc proc /proc
mount -t sysfs sysfs /sys

for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -c /dev/vport0p1 ] && break
    sleep 1
done
[ -c /dev/vport0p1 ]
[ -c /dev/ttyAMA0 ]

exec 3<>/dev/vport0p1
/opt/droidlayer/guest-agent --token-file /etc/droidlayer/bridge.token \
    --terminal-device /dev/ttyAMA0 <&3 >&3 2>&1 &
agent_pid="$!"

printf '%s\n' 'DROIDLAYER_BRIDGE_CONTROL_GUEST_TERMINAL_STARTED' >/dev/ttyAMA0
set +e
/bin/sh </dev/ttyAMA0 >/dev/ttyAMA0 2>&1
shell_status="$?"
wait "$agent_pid"
agent_status="$?"
set -e
printf 'DROIDLAYER_BRIDGE_CONTROL_GUEST_AGENT_EXIT_%s\n' "$agent_status" >/dev/ttyAMA0
printf 'DROIDLAYER_BRIDGE_CONTROL_GUEST_SHELL_EXIT_%s\n' "$shell_status" >/dev/ttyAMA0
sync
poweroff -f || true
while :; do sleep 3600; done
INIT
chmod 0755 "$INITRAMFS_ROOT/init"

(
  cd -- "$INITRAMFS_ROOT"
  find . -xdev -print0 \
    | LC_ALL=C sort -z \
    | cpio --null --create --format=newc --owner=0:0 --reproducible --quiet \
    | gzip -n -9 > "$INITRAMFS"
)
chmod 0644 "$INITRAMFS"

setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  qemu-system-aarch64 \
    -machine virt,gic-version=3 \
    -accel tcg,thread=multi \
    -cpu cortex-a57 \
    -smp 2 \
    -m 512 \
    -nodefaults \
    -display none \
    -monitor none \
    -no-reboot \
    -chardev "socket,id=terminal,path=$TERMINAL_SOCKET,abstract=on,server=on,wait=off" \
    -serial chardev:terminal \
    -kernel "$ASSET_ROOT/Image" \
    -initrd "$INITRAMFS" \
    -append 'console=ttyAMA0 rdinit=/init panic=-1' \
    -device virtio-serial-device \
    -chardev "socket,id=control,path=$CONTROL_SOCKET,abstract=on,server=on,wait=off" \
    -device virtserialport,chardev=control,name=droidlayer.bridge \
    >"$QEMU_LOG" 2>&1 &
QEMU_PID="$!"

setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$CONTROL_PTY" "ABSTRACT-CONNECT:$CONTROL_SOCKET,retry=100,interval=0.1" \
  >"$CONTROL_RELAY_LOG" 2>&1 &
CONTROL_RELAY_PID="$!"
setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$TERMINAL_PTY" "ABSTRACT-CONNECT:$TERMINAL_SOCKET,retry=100,interval=0.1" \
  >"$TERMINAL_RELAY_LOG" 2>&1 &
TERMINAL_RELAY_PID="$!"

for pty_path in "$CONTROL_PTY" "$TERMINAL_PTY"; do
  for ((attempt = 0; attempt < 100; attempt += 1)); do
    [[ -e "$pty_path" ]] && break
    sleep 0.1
  done
  [[ -e "$pty_path" ]] || {
    printf 'socat did not create a required bridge-control PTY relay\n' >&2
    exit 1
  }
done

exec 3<> "$CONTROL_PTY"
exec 4<> "$TERMINAL_PTY"
timeout --signal=TERM --kill-after=1s 25s cat <&3 > "$CONTROL_TRANSCRIPT" 2>/dev/null &
CONTROL_READER_PID="$!"
timeout --signal=TERM --kill-after=1s 25s cat <&4 > "$TERMINAL_TRANSCRIPT" 2>/dev/null &
TERMINAL_READER_PID="$!"

if [[ "$BRIDGE_EXPECTATION" = valid ]]; then
  printf '%s\n' \
    "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
    'DROIDLAYER/PING' \
    'DROIDLAYER/RESIZE 80 24' >&3

  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1' || {
    printf 'bridge control did not return READY\n' >&2
    exit 1
  }
  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1' || {
    printf 'bridge control did not return PONG\n' >&2
    exit 1
  }
  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize' || {
    printf 'bridge control did not acknowledge resize\n' >&2
    exit 1
  }
else
  printf '%s\n' "DROIDLAYER/HELLO 1 1 $INCORRECT_TOKEN" >&3
  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/ERROR unauthorized' || {
    printf 'bridge control did not reject the incorrect token\n' >&2
    exit 1
  }
fi
wait_for_line "$TERMINAL_TRANSCRIPT" "$GUEST_TERMINAL_STARTED" || {
  printf 'guest terminal did not start\n' >&2
  exit 1
}

# Closing the control adapter after its responses are observed lets the guest agent
# finish its control loop while the independent terminal channel remains usable.
exec 3>&-
stop_process "$CONTROL_READER_PID"
CONTROL_READER_PID=""
stop_process "$CONTROL_RELAY_PID"
CONTROL_RELAY_PID=""

if [[ "$BRIDGE_EXPECTATION" = valid ]]; then
  printf '%s\n' \
    'stty size' \
    "printf '$TERMINAL_SENTINEL\\n'" \
    'exit' >&4
else
  printf '%s\n' 'exit' >&4
fi

for ((attempt = 0; attempt < 300; attempt += 1)); do
  kill -0 "$QEMU_PID" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$QEMU_PID" 2>/dev/null; then
  printf 'QEMU did not exit after bridge-control and terminal interaction\n' >&2
  exit 1
fi
set +e
wait "$QEMU_PID"
QEMU_EXIT="$?"
set -e
QEMU_PID=""

exec 4>&-
stop_process "$TERMINAL_RELAY_PID"
TERMINAL_RELAY_PID=""
for ((attempt = 0; attempt < 50; attempt += 1)); do
  kill -0 "$TERMINAL_READER_PID" 2>/dev/null || break
  sleep 0.1
done
stop_process "$TERMINAL_READER_PID"
set +e
wait "$TERMINAL_READER_PID"
TERMINAL_READER_EXIT="$?"
set -e
TERMINAL_READER_PID=""
CONTROL_READER_EXIT=0
TERMINAL_READER_PID=""

READY=0
PONG=0
RESIZE=0
UNAUTHORIZED=0
TERMINAL_STARTED=0
TERMINAL_SIZE=0
SENTINEL=0
AGENT_EXIT="unknown"
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1'; then
  READY=1
fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1'; then
  PONG=1
fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize'; then
  RESIZE=1
fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/ERROR unauthorized'; then
  UNAUTHORIZED=1
fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$GUEST_TERMINAL_STARTED"; then
  TERMINAL_STARTED=1
fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" '24 80'; then
  TERMINAL_SIZE=1
fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$TERMINAL_SENTINEL"; then
  SENTINEL=1
fi
agent_exit_line="$(tr -d '\r' < "$TERMINAL_TRANSCRIPT" | grep --extended-regexp \
  '^DROIDLAYER_BRIDGE_CONTROL_GUEST_AGENT_EXIT_[0-9]+$' | tail -n 1 || true)"
if [[ -n "$agent_exit_line" ]]; then
  AGENT_EXIT="${agent_exit_line##*_}"
fi

if [[ "$BRIDGE_EXPECTATION" = valid ]]; then
  if [[ "$QEMU_EXIT" -eq 0 && "$READY" -eq 1 && "$PONG" -eq 1 && "$RESIZE" -eq 1 \
    && "$TERMINAL_STARTED" -eq 1 && "$TERMINAL_SIZE" -eq 1 && "$SENTINEL" -eq 1 \
    && "$AGENT_EXIT" = 0 \
    && ( "$CONTROL_READER_EXIT" -eq 0 || "$CONTROL_READER_EXIT" -eq 1 ) \
    && ( "$TERMINAL_READER_EXIT" -eq 0 || "$TERMINAL_READER_EXIT" -eq 1 ) ]]; then
    printf 'DROIDLAYER_QEMU_BRIDGE_CONTROL_TERMINAL_OK ready=1 pong=1 resize=1 terminal=1 size=24x80 sentinel=1 agent_exit=0\n'
    exit 0
  fi
else
  if [[ "$QEMU_EXIT" -eq 0 && "$UNAUTHORIZED" -eq 1 && "$READY" -eq 0 && "$PONG" -eq 0 \
    && "$RESIZE" -eq 0 && "$TERMINAL_STARTED" -eq 1 && "$AGENT_EXIT" = 3 \
    && ( "$CONTROL_READER_EXIT" -eq 0 || "$CONTROL_READER_EXIT" -eq 1 ) \
    && ( "$TERMINAL_READER_EXIT" -eq 0 || "$TERMINAL_READER_EXIT" -eq 1 ) ]]; then
    printf 'DROIDLAYER_QEMU_BRIDGE_TOKEN_REJECTION_OK unauthorized=1 ready=0 pong=0 resize=0 terminal=1 agent_exit=3\n'
    exit 0
  fi
fi

printf 'DROIDLAYER_QEMU_BRIDGE_CONTROL_TERMINAL_FAILURE mode=%s unauthorized=%s ready=%s pong=%s resize=%s terminal=%s size=%s sentinel=%s agent_exit=%s qemu_exit=%s control_reader_exit=%s terminal_reader_exit=%s\n' \
  "$BRIDGE_EXPECTATION" "$UNAUTHORIZED" "$READY" "$PONG" "$RESIZE" "$TERMINAL_STARTED" "$TERMINAL_SIZE" "$SENTINEL" \
  "$AGENT_EXIT" "$QEMU_EXIT" "$CONTROL_READER_EXIT" "$TERMINAL_READER_EXIT" >&2
exit 1
