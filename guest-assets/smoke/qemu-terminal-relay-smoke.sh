#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
WORK_ROOT="${TICKET26_WORK_ROOT:-}"
WORK_DIR=""
QEMU_PID=""
RELAY_PID=""
READER_PID=""

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'required terminal relay command is unavailable: %s\n' "$command_name" >&2
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

# cleanup is invoked via `trap cleanup EXIT INT TERM`; shellcheck cannot see trap reachability
# shellcheck disable=SC2329
cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  exec 3>&- || true
  stop_process "$READER_PID"
  stop_process "$RELAY_PID"
  stop_process "$QEMU_PID"
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    case "$(realpath -- "$WORK_DIR")" in
      "$WORK_ROOT"/droidlayer-t26-implementation-terminal-relay.*)
        if [[ "$status" -eq 0 ]]; then
          rm -rf -- "$WORK_DIR"
        else
          printf 'terminal relay failure fixture retained: %s\n' "$WORK_DIR" >&2
        fi
        ;;
      *)
        printf 'refusing to clean unsafe terminal relay directory: %s\n' "$WORK_DIR" >&2
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

WORK_DIR="$(mktemp -d "$WORK_ROOT/droidlayer-t26-implementation-terminal-relay.XXXXXX")"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/terminal-relay-initramfs.cpio.gz"
AGENT="$WORK_DIR/guest-agent"
SOCKET_NAME="$(basename -- "$WORK_DIR")-bridge"
PTY_PATH="$WORK_DIR/bridge.pty"
TRANSCRIPT="$WORK_DIR/transcript"
QEMU_LOG="$WORK_DIR/qemu.log"
RELAY_LOG="$WORK_DIR/relay.log"
CONSOLE_LOG="$WORK_DIR/console.log"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
TERMINAL_SENTINEL="DROIDLAYER_TERMINAL_SENTINEL"
GUEST_VIRTIO_READY="DROIDLAYER_TERMINAL_RELAY_GUEST_VIRTIO_READY"
GUEST_VIRTIO_OPENED="DROIDLAYER_TERMINAL_RELAY_GUEST_VIRTIO_OPENED"
GUEST_SHELL_LAUNCHING="DROIDLAYER_TERMINAL_RELAY_GUEST_SHELL_LAUNCHING"
GUEST_SHELL_STARTED="DROIDLAYER_TERMINAL_RELAY_GUEST_SHELL_STARTED"
GUEST_INIT_STARTED="DROIDLAYER_TERMINAL_RELAY_GUEST_INIT_STARTED"

chmod 0700 "$WORK_DIR"
chown 1000:1000 "$WORK_DIR"

mkdir -p -- "$INITRAMFS_ROOT"
gzip -dc -- "$ASSET_ROOT/initramfs.cpio.gz" \
  | (cd -- "$INITRAMFS_ROOT" && cpio --extract --make-directories --unconditional \
      --no-absolute-filenames --no-preserve-owner --quiet \
      --nonmatching dev/console dev/null)
[[ -d "$INITRAMFS_ROOT/dev" && ! -e "$INITRAMFS_ROOT/dev/console" \
  && ! -e "$INITRAMFS_ROOT/dev/null" ]] || {
  printf 'host initramfs device-node exclusion failed\n' >&2
  exit 1
}

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
printf '%s\n' 'DROIDLAYER_TERMINAL_RELAY_GUEST_INIT_STARTED' >/dev/console
mount -t proc proc /proc
mount -t sysfs sysfs /sys

for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -c /dev/vport0p1 ] && break
    sleep 1
done
[ -c /dev/vport0p1 ]
printf '%s\n' 'DROIDLAYER_TERMINAL_RELAY_GUEST_VIRTIO_READY' >/dev/console
set +e
exec 3<>/dev/vport0p1
bridge_open_status="$?"
set -e
if [ "$bridge_open_status" -ne 0 ]; then
    printf 'DROIDLAYER_TERMINAL_RELAY_GUEST_VIRTIO_OPEN_FAILED_%s\n' "$bridge_open_status" \
        >/dev/console
    poweroff -f || true
    while :; do sleep 3600; done
fi
printf '%s\n' 'DROIDLAYER_TERMINAL_RELAY_GUEST_VIRTIO_OPENED' >/dev/console
printf '%s\n' 'DROIDLAYER_TERMINAL_RELAY_GUEST_SHELL_LAUNCHING' >/dev/console

set +e
/bin/sh -c '
    printf "%s\n" "DROIDLAYER_TERMINAL_RELAY_GUEST_SHELL_STARTED" >/dev/console
    exec /opt/droidlayer/guest-agent --token-file /etc/droidlayer/bridge.token
' <&3 >&3 2>&1
agent_status="$?"
set -e
printf 'DROIDLAYER_TERMINAL_RELAY_GUEST_AGENT_EXIT_%s\n' "$agent_status" >/dev/console
sync
poweroff -f || true
while :; do sleep 3600; done
exit "$agent_status"
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
    -serial "file:$CONSOLE_LOG" \
    -no-reboot \
    -kernel "$ASSET_ROOT/Image" \
    -initrd "$INITRAMFS" \
    -append 'console=ttyAMA0 rdinit=/init panic=-1' \
    -device virtio-serial-device \
    -chardev "socket,id=bridge,path=$SOCKET_NAME,abstract=on,server=on,wait=on" \
    -device virtserialport,chardev=bridge,name=droidlayer.bridge \
    >"$QEMU_LOG" 2>&1 &
QEMU_PID="$!"

setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$PTY_PATH" "ABSTRACT-CONNECT:$SOCKET_NAME,retry=100,interval=0.1" \
  >"$RELAY_LOG" 2>&1 &
RELAY_PID="$!"

for ((attempt = 0; attempt < 100; attempt += 1)); do
  [[ -e "$PTY_PATH" ]] && break
  kill -0 "$RELAY_PID" 2>/dev/null || {
    printf 'socat exited before creating the terminal PTY relay\n' >&2
    exit 1
  }
  sleep 0.1
done
[[ -e "$PTY_PATH" ]] || {
  printf 'socat did not create the terminal PTY relay\n' >&2
  exit 1
}

exec 3<> "$PTY_PATH"
timeout --signal=TERM --kill-after=1s 20s cat <&3 > "$TRANSCRIPT" 2>/dev/null &
READER_PID="$!"
printf '%s\n' \
  "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
  "printf '$TERMINAL_SENTINEL\\n'" \
  'exit' >&3

for ((attempt = 0; attempt < 300; attempt += 1)); do
  kill -0 "$QEMU_PID" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$QEMU_PID" 2>/dev/null; then
  printf 'QEMU did not exit after the terminal relay interaction\n' >&2
  exit 1
fi
set +e
wait "$QEMU_PID"
QEMU_EXIT="$?"
set -e
QEMU_PID=""

stop_process "$RELAY_PID"
RELAY_PID=""
exec 3>&-
for ((attempt = 0; attempt < 50; attempt += 1)); do
  kill -0 "$READER_PID" 2>/dev/null || break
  sleep 0.1
done
stop_process "$READER_PID"
set +e
wait "$READER_PID"
READER_EXIT="$?"
set -e
READER_PID=""

INIT_STARTED=0
VIRTIO_READY=0
VIRTIO_OPENED=0
SHELL_LAUNCHING=0
SHELL_STARTED=0
READY=0
SENTINEL=0
AGENT_EXIT="unknown"
READER_CLOSED=0
if [[ "$READER_EXIT" -eq 0 || "$READER_EXIT" -eq 1 ]]; then
  READER_CLOSED=1
fi
if contains_normalized_line "$CONSOLE_LOG" "$GUEST_INIT_STARTED"; then
  INIT_STARTED=1
fi
if contains_normalized_line "$CONSOLE_LOG" "$GUEST_VIRTIO_READY"; then
  VIRTIO_READY=1
fi
if contains_normalized_line "$CONSOLE_LOG" "$GUEST_VIRTIO_OPENED"; then
  VIRTIO_OPENED=1
fi
if contains_normalized_line "$CONSOLE_LOG" "$GUEST_SHELL_LAUNCHING"; then
  SHELL_LAUNCHING=1
fi
if contains_normalized_line "$CONSOLE_LOG" "$GUEST_SHELL_STARTED"; then
  SHELL_STARTED=1
fi
agent_exit_line="$(tr -d '\r' < "$CONSOLE_LOG" | grep --extended-regexp \
  '^DROIDLAYER_TERMINAL_RELAY_GUEST_AGENT_EXIT_[0-9]+$' | tail -n 1 || true)"
if [[ -n "$agent_exit_line" ]]; then
  AGENT_EXIT="${agent_exit_line##*_}"
fi
if contains_normalized_line "$TRANSCRIPT" 'DROIDLAYER/READY 1'; then
  READY=1
fi
if contains_normalized_line "$TRANSCRIPT" "$TERMINAL_SENTINEL"; then
  SENTINEL=1
fi

if [[ "$QEMU_EXIT" -eq 0 && "$INIT_STARTED" -eq 1 && "$VIRTIO_READY" -eq 1 && "$VIRTIO_OPENED" -eq 1 && "$SHELL_LAUNCHING" -eq 1 && "$SHELL_STARTED" -eq 1 && "$READY" -eq 1 && "$SENTINEL" -eq 1 \
  && "$READER_CLOSED" -eq 1 ]]; then
  printf 'DROIDLAYER_QEMU_TERMINAL_RELAY_OK init=1 virtio=1 stream=1 shell_launch=1 shell=1 agent_exit=%s ready=1 sentinel=1\n' \
    "$AGENT_EXIT"
  exit 0
fi

if [[ "$QEMU_EXIT" -eq 0 && "$INIT_STARTED" -eq 1 && "$VIRTIO_READY" -eq 1 && "$VIRTIO_OPENED" -eq 1 && "$SHELL_LAUNCHING" -eq 1 && "$SHELL_STARTED" -eq 1 && "$READY" -eq 1 && "$SENTINEL" -eq 0 ]]; then
  printf 'DROIDLAYER_QEMU_TERMINAL_RELAY_RED init=1 virtio=1 stream=1 shell_launch=1 shell=1 agent_exit=%s ready=1 sentinel=0\n' \
    "$AGENT_EXIT" >&2
else
  printf 'DROIDLAYER_QEMU_TERMINAL_RELAY_HARNESS_FAILURE init=%s virtio=%s stream=%s shell_launch=%s shell=%s agent_exit=%s ready=%s sentinel=%s qemu_exit=%s reader_closed=%s reader_exit=%s\n' \
    "$INIT_STARTED" "$VIRTIO_READY" "$VIRTIO_OPENED" "$SHELL_LAUNCHING" "$SHELL_STARTED" "$AGENT_EXIT" "$READY" "$SENTINEL" "$QEMU_EXIT" "$READER_CLOSED" "$READER_EXIT" >&2
fi
exit 1
