#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
PROVISIONED_BRIDGE_EXPECTATION="${TICKET26_PROVISIONED_BRIDGE_EXPECTATION:-valid}"
WORK_DIR=""
QEMU_PID=""
CONTROL_RELAY_PID=""
TERMINAL_RELAY_PID=""
CONTROL_READER_PID=""
TERMINAL_READER_PID=""

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'required provisioned-bridge smoke command is unavailable: %s\n' "$command_name" >&2
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
    if [[ -n "$QEMU_PID" ]] && ! kill -0 "$QEMU_PID" 2>/dev/null; then
      return 1
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
  exec 3>&- || true
  exec 4>&- || true
  stop_process "$CONTROL_READER_PID"
  stop_process "$TERMINAL_READER_PID"
  stop_process "$CONTROL_RELAY_PID"
  stop_process "$TERMINAL_RELAY_PID"
  stop_process "$QEMU_PID"
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    case "$(realpath -- "$WORK_DIR")" in
      /tmp/droidlayer-t26-implementation-provisioned-bridge.*)
        rm -rf -- "$WORK_DIR"
        ;;
      *)
        printf 'refusing to clean unsafe provisioned-bridge directory: %s\n' "$WORK_DIR" >&2
        status=70
        ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for required_command in \
  aarch64-linux-gnu-gcc blkid cpio gzip qemu-system-aarch64 setpriv sha256sum socat timeout; do
  require_command "$required_command"
done

verify_ticket26_ticket25_asset_root "$ASSET_ROOT" || exit "$?"

case "$PROVISIONED_BRIDGE_EXPECTATION" in
  valid|shared-device-rejection) ;;
  *)
    printf 'TICKET26_PROVISIONED_BRIDGE_EXPECTATION must be valid or shared-device-rejection\n' >&2
    exit 64
    ;;
esac

WORK_DIR="$(mktemp -d /tmp/droidlayer-t26-implementation-provisioned-bridge.XXXXXX)"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/provisioned-bridge-initramfs.cpio.gz"
INPUT_DIRECTORY="$WORK_DIR/input"
SYNTHETIC_ROOTFS="$INPUT_DIRECTORY/ticket26-provisioned-bridge-rootfs.tar.gz"
SYNTHETIC_ROOTFS_DIRECTORY="$WORK_DIR/synthetic-rootfs"
AGENT="$WORK_DIR/guest-agent"
ROOT_VOLUME="$WORK_DIR/root-volume.raw"
CONTROL_SOCKET="$WORK_DIR/control.sock"
TERMINAL_SOCKET="$WORK_DIR/terminal.sock"
CONTROL_PTY="$WORK_DIR/control.pty"
TERMINAL_PTY="$WORK_DIR/terminal.pty"
CONTROL_TRANSCRIPT="$WORK_DIR/control-transcript"
TERMINAL_TRANSCRIPT="$WORK_DIR/terminal-transcript"
QEMU_LOG="$WORK_DIR/qemu.log"
CONTROL_RELAY_LOG="$WORK_DIR/control-relay.log"
TERMINAL_RELAY_LOG="$WORK_DIR/terminal-relay.log"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
TERMINAL_SENTINEL="DROIDLAYER_PROVISIONED_BRIDGE_TERMINAL_SENTINEL"
PROVISIONED_GUEST_STARTED="DROIDLAYER_PROVISIONED_BRIDGE_GUEST_STARTED"
PROVISIONED_CONFIG_OK="DROIDLAYER_PROVISIONED_BRIDGE_CONFIG_OK"
BRIDGE_DEVICE_ARGUMENT=/dev/vport0p1
TERMINAL_DEVICE_ARGUMENT=/dev/ttyAMA0
if [[ "$PROVISIONED_BRIDGE_EXPECTATION" = shared-device-rejection ]]; then
  TERMINAL_DEVICE_ARGUMENT="$BRIDGE_DEVICE_ARGUMENT"
fi

chmod 0700 "$WORK_DIR"
chown 1000:1000 "$WORK_DIR"
mkdir -p -- "$INITRAMFS_ROOT" "$INPUT_DIRECTORY" "$SYNTHETIC_ROOTFS_DIRECTORY"

gzip -dc -- "$ASSET_ROOT/initramfs.cpio.gz" \
  | (cd -- "$INITRAMFS_ROOT" && cpio --extract --make-directories --unconditional \
      --no-absolute-filenames --quiet)

aarch64-linux-gnu-gcc -static -std=c11 -Wall -Wextra -Werror \
  "$GUEST_ASSETS_DIR/agent/guest-agent.c" \
  -o "$AGENT"
install -D -m 0755 "$AGENT" "$INITRAMFS_ROOT/opt/droidlayer/guest-agent"
install -D -m 0755 "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent"
install -D -m 0644 "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent.conf" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent.conf"
install -m 0755 "$GUEST_ASSETS_DIR/initramfs/init" "$INITRAMFS_ROOT/init"

install -D -m 0755 "$INITRAMFS_ROOT/bin/busybox" "$SYNTHETIC_ROOTFS_DIRECTORY/bin/busybox"
ln -s busybox "$SYNTHETIC_ROOTFS_DIRECTORY/bin/sh"
ln -s busybox "$SYNTHETIC_ROOTFS_DIRECTORY/bin/stty"
mkdir -p -- \
  "$SYNTHETIC_ROOTFS_DIRECTORY/dev" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/etc/conf.d" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/etc/init.d" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/proc" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/sbin" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/sys" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/usr/sbin" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/var/lib/droidlayer"
cat > "$SYNTHETIC_ROOTFS_DIRECTORY/sbin/init" <<'INIT'
#!/bin/sh
set -eu

. /etc/conf.d/droidlayer-guest-agent
if [ "$bridge_stream" != /dev/droidlayer-bridge ] \
    || [ "$terminal_device" != /dev/droidlayer-terminal ] \
    || [ ! -c "$bridge_stream" ] \
    || [ ! -c "$terminal_device" ] \
    || [ ! -L /etc/runlevels/default/droidlayer-guest-agent ]; then
    printf '%s\n' 'DROIDLAYER_PROVISIONED_BRIDGE_CONFIG_INVALID' >/dev/console
    /bin/busybox poweroff -f || true
    while :; do /bin/busybox sleep 3600; done
fi

printf '%s\n' 'DROIDLAYER_PROVISIONED_BRIDGE_CONFIG_OK' > "$terminal_device"
exec 3<> "$bridge_stream"
/usr/sbin/droidlayer-guest-agent --token-file /etc/droidlayer/bridge.token \
    --terminal-device "$terminal_device" <&3 >&3 2>&1 &
agent_pid="$!"
printf '%s\n' 'DROIDLAYER_PROVISIONED_BRIDGE_GUEST_STARTED' > "$terminal_device"
set +e
/bin/sh < "$terminal_device" > "$terminal_device" 2>&1
shell_status="$?"
wait "$agent_pid"
agent_status="$?"
set -e
printf 'DROIDLAYER_PROVISIONED_BRIDGE_GUEST_AGENT_EXIT_%s\n' "$agent_status" > "$terminal_device"
printf 'DROIDLAYER_PROVISIONED_BRIDGE_GUEST_SHELL_EXIT_%s\n' "$shell_status" > "$terminal_device"
/bin/busybox sync
/bin/busybox poweroff -f || true
while :; do /bin/busybox sleep 3600; done
INIT
chmod 0755 "$SYNTHETIC_ROOTFS_DIRECTORY/sbin/init"
(
  cd -- "$SYNTHETIC_ROOTFS_DIRECTORY"
  find . -xdev -print0 \
    | LC_ALL=C sort -z \
    | tar --null --files-from=- --numeric-owner --owner=0 --group=0 --mtime='@0' \
      --format=posix -czf "$SYNTHETIC_ROOTFS"
)

printf '%s\n' "$(basename -- "$SYNTHETIC_ROOTFS")" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.file"
sha256sum -- "$SYNTHETIC_ROOTFS" | awk '{print $1}' > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
printf '%s\n' "$SYNTHETIC_TOKEN" > "$INPUT_DIRECTORY/bridge.token"
chmod 0444 "$INPUT_DIRECTORY/bridge.token"
(
  cd -- "$INITRAMFS_ROOT"
  find . -xdev -print0 \
    | LC_ALL=C sort -z \
    | cpio --null --create --format=newc --owner=0:0 --reproducible --quiet \
    | gzip -n -9 > "$INITRAMFS"
)
chmod 0644 "$INITRAMFS"
truncate --size 128M "$ROOT_VOLUME"
chown 1000:1000 "$ROOT_VOLUME"
chmod 0600 "$ROOT_VOLUME"

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
    -chardev "socket,id=terminal,path=$TERMINAL_SOCKET,server=on,wait=off" \
    -serial chardev:terminal \
    -kernel "$ASSET_ROOT/Image" \
    -initrd "$INITRAMFS" \
    -append "console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input droidlayer.bridge_device=$BRIDGE_DEVICE_ARGUMENT droidlayer.terminal_device=$TERMINAL_DEVICE_ARGUMENT" \
    -drive "if=none,id=root,file=$ROOT_VOLUME,format=raw,cache=unsafe" \
    -device virtio-blk-device,drive=root \
    -fsdev "local,id=provision,path=$INPUT_DIRECTORY,security_model=none,readonly=on,multidevs=remap" \
    -device virtio-9p-device,fsdev=provision,mount_tag=droidlayer-input \
    -device virtio-serial-device \
    -chardev "socket,id=control,path=$CONTROL_SOCKET,server=on,wait=off" \
    -device virtserialport,chardev=control,name=droidlayer.bridge \
    >"$QEMU_LOG" 2>&1 &
QEMU_PID="$!"

for socket_path in "$CONTROL_SOCKET" "$TERMINAL_SOCKET"; do
  for ((attempt = 0; attempt < 100; attempt += 1)); do
    [[ -S "$socket_path" ]] && break
    kill -0 "$QEMU_PID" 2>/dev/null || {
      printf 'QEMU exited before creating a provisioned-bridge socket\n' >&2
      exit 1
    }
    sleep 0.1
  done
  [[ -S "$socket_path" ]] || {
    printf 'QEMU did not create a provisioned-bridge socket\n' >&2
    exit 1
  }
done

setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$CONTROL_PTY" "UNIX-CONNECT:$CONTROL_SOCKET" \
  >"$CONTROL_RELAY_LOG" 2>&1 &
CONTROL_RELAY_PID="$!"
setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$TERMINAL_PTY" "UNIX-CONNECT:$TERMINAL_SOCKET" \
  >"$TERMINAL_RELAY_LOG" 2>&1 &
TERMINAL_RELAY_PID="$!"

for pty_path in "$CONTROL_PTY" "$TERMINAL_PTY"; do
  for ((attempt = 0; attempt < 100; attempt += 1)); do
    [[ -e "$pty_path" ]] && break
    sleep 0.1
  done
  [[ -e "$pty_path" ]] || {
    printf 'socat did not create a provisioned-bridge PTY relay\n' >&2
    exit 1
  }
done

exec 3<> "$CONTROL_PTY"
exec 4<> "$TERMINAL_PTY"
timeout --signal=TERM --kill-after=1s 25s cat <&3 > "$CONTROL_TRANSCRIPT" 2>/dev/null &
CONTROL_READER_PID="$!"
timeout --signal=TERM --kill-after=1s 25s cat <&4 > "$TERMINAL_TRANSCRIPT" 2>/dev/null &
TERMINAL_READER_PID="$!"

if [[ "$PROVISIONED_BRIDGE_EXPECTATION" = shared-device-rejection ]]; then
  for ((attempt = 0; attempt < 300; attempt += 1)); do
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'QEMU did not exit after shared bridge/terminal device rejection\n' >&2
    exit 1
  fi
  set +e
  wait "$QEMU_PID"
  QEMU_EXIT="$?"
  set -e
  QEMU_PID=""
  exec 3>&-
  exec 4>&-
  stop_process "$CONTROL_READER_PID"
  CONTROL_READER_PID=""
  stop_process "$TERMINAL_READER_PID"
  TERMINAL_READER_PID=""
  stop_process "$CONTROL_RELAY_PID"
  CONTROL_RELAY_PID=""
  stop_process "$TERMINAL_RELAY_PID"
  TERMINAL_RELAY_PID=""
  SHARED_DEVICE_REJECTED=0
  if contains_normalized_line "$TERMINAL_TRANSCRIPT" \
    'DROIDLAYER_PROVISIONING_ERROR stage=shared-bridge-terminal-device'; then
    SHARED_DEVICE_REJECTED=1
  fi
  ROOT_FILESYSTEM_TYPE="$(blkid -o value -s TYPE "$ROOT_VOLUME" 2>/dev/null || true)"
  if [[ "$QEMU_EXIT" -eq 0 && "$SHARED_DEVICE_REJECTED" -eq 1 \
    && -z "$ROOT_FILESYSTEM_TYPE" ]]; then
    printf 'DROIDLAYER_QEMU_PROVISIONED_BRIDGE_SHARED_DEVICE_REJECTION_OK rejected=1 root_volume_formatted=0\n'
    exit 0
  fi
  printf 'DROIDLAYER_QEMU_PROVISIONED_BRIDGE_SHARED_DEVICE_REJECTION_FAILURE rejected=%s root_filesystem=%s qemu_exit=%s\n' \
    "$SHARED_DEVICE_REJECTED" "${ROOT_FILESYSTEM_TYPE:-none}" "$QEMU_EXIT" >&2
  exit 1
fi

printf '%s\n' \
  "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
  'DROIDLAYER/PING' \
  'DROIDLAYER/RESIZE 80 24' >&3
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1' || {
  printf 'provisioned bridge did not return READY\n' >&2
  exit 1
}
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1' || {
  printf 'provisioned bridge did not return PONG\n' >&2
  exit 1
}
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize' || {
  printf 'provisioned bridge did not acknowledge resize\n' >&2
  exit 1
}
wait_for_line "$TERMINAL_TRANSCRIPT" "$PROVISIONED_CONFIG_OK" || {
  printf 'provisioned root did not expose the generic bridge configuration\n' >&2
  exit 1
}
wait_for_line "$TERMINAL_TRANSCRIPT" "$PROVISIONED_GUEST_STARTED" || {
  printf 'provisioned root terminal did not start\n' >&2
  exit 1
}

exec 3>&-
stop_process "$CONTROL_READER_PID"
CONTROL_READER_PID=""
stop_process "$CONTROL_RELAY_PID"
CONTROL_RELAY_PID=""
printf '%s\n' \
  'stty size' \
  "printf '$TERMINAL_SENTINEL\\n'" \
  'exit' >&4

for ((attempt = 0; attempt < 300; attempt += 1)); do
  kill -0 "$QEMU_PID" 2>/dev/null || break
  sleep 0.1
done
if kill -0 "$QEMU_PID" 2>/dev/null; then
  printf 'QEMU did not exit after provisioned-bridge interaction\n' >&2
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

READY=0
PONG=0
RESIZE=0
PROVISIONED=0
CONFIGURED=0
TERMINAL_STARTED=0
TERMINAL_SIZE=0
SENTINEL=0
AGENT_EXIT="unknown"
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1'; then READY=1; fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1'; then PONG=1; fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize'; then RESIZE=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=linux phase=rootfs runtime=pending'; then PROVISIONED=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$PROVISIONED_CONFIG_OK"; then CONFIGURED=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$PROVISIONED_GUEST_STARTED"; then TERMINAL_STARTED=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" '24 80'; then TERMINAL_SIZE=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$TERMINAL_SENTINEL"; then SENTINEL=1; fi
agent_exit_line="$(tr -d '\r' < "$TERMINAL_TRANSCRIPT" | grep --extended-regexp \
  '^DROIDLAYER_PROVISIONED_BRIDGE_GUEST_AGENT_EXIT_[0-9]+$' | tail -n 1 || true)"
if [[ -n "$agent_exit_line" ]]; then
  AGENT_EXIT="${agent_exit_line##*_}"
fi

if [[ "$QEMU_EXIT" -eq 0 && "$READY" -eq 1 && "$PONG" -eq 1 && "$RESIZE" -eq 1 \
  && "$PROVISIONED" -eq 1 && "$CONFIGURED" -eq 1 && "$TERMINAL_STARTED" -eq 1 \
  && "$TERMINAL_SIZE" -eq 1 && "$SENTINEL" -eq 1 && "$AGENT_EXIT" = 0 \
  && ( "$TERMINAL_READER_EXIT" -eq 0 || "$TERMINAL_READER_EXIT" -eq 1 ) ]]; then
  printf 'DROIDLAYER_QEMU_PROVISIONED_BRIDGE_TERMINAL_OK provisioning=1 bridge=1 terminal=1 resize=24x80 sentinel=1 agent_exit=0\n'
  exit 0
fi

printf 'DROIDLAYER_QEMU_PROVISIONED_BRIDGE_TERMINAL_FAILURE ready=%s pong=%s resize=%s provisioning=%s configured=%s terminal=%s size=%s sentinel=%s agent_exit=%s qemu_exit=%s terminal_reader_exit=%s\n' \
  "$READY" "$PONG" "$RESIZE" "$PROVISIONED" "$CONFIGURED" "$TERMINAL_STARTED" "$TERMINAL_SIZE" \
  "$SENTINEL" "$AGENT_EXIT" "$QEMU_EXIT" "$TERMINAL_READER_EXIT" >&2
exit 1
