#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
OFFICIAL_ALPINE_ROOTFS="${TICKET26_OFFICIAL_ALPINE_ROOTFS:-}"
OFFICIAL_ALPINE_FILE="alpine-minirootfs-3.24.1-aarch64.tar.gz"
OFFICIAL_ALPINE_SHA256="f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"
OFFICIAL_ALPINE_SIZE_BYTES=4023732
WORK_ROOT="${TICKET26_WORK_ROOT:-}"
WORK_DIR=""
QEMU_PID=""
CONTROL_RELAY_PID=""
TERMINAL_RELAY_PID=""
CONTROL_READER_PID=""
TERMINAL_READER_PID=""
CONTROL_TRANSCRIPT=""
TERMINAL_TRANSCRIPT=""
FIRST_CONTROL_TRANSCRIPT=""
FIRST_TERMINAL_TRANSCRIPT=""
SECOND_CONTROL_TRANSCRIPT=""
SECOND_TERMINAL_TRANSCRIPT=""

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'required official-Alpine restart smoke command is unavailable: %s\n' "$command_name" >&2
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

file_size_or_zero() {
  local path="$1"
  if [[ -n "$path" && -f "$path" ]]; then
    wc -c < "$path"
  else
    printf '0\n'
  fi
}

wait_for_line() {
  local path="$1"
  local expected_line="$2"
  for ((attempt = 0; attempt < 600; attempt += 1)); do
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

verify_official_alpine_rootfs() {
  local actual_hash
  local actual_size

  [[ -n "$OFFICIAL_ALPINE_ROOTFS" ]] || {
    printf 'TICKET26_OFFICIAL_ALPINE_ROOTFS must name the approved official Alpine archive\n' >&2
    return 64
  }
  [[ -f "$OFFICIAL_ALPINE_ROOTFS" && ! -L "$OFFICIAL_ALPINE_ROOTFS" ]] || {
    printf 'approved official Alpine archive is unavailable or not a direct regular file\n' >&2
    return 66
  }
  [[ "$(basename -- "$OFFICIAL_ALPINE_ROOTFS")" = "$OFFICIAL_ALPINE_FILE" ]] || {
    printf 'approved official Alpine archive has an unexpected file name\n' >&2
    return 66
  }
  actual_size="$(stat --format='%s' -- "$OFFICIAL_ALPINE_ROOTFS")"
  [[ "$actual_size" = "$OFFICIAL_ALPINE_SIZE_BYTES" ]] || {
    printf 'approved official Alpine archive has an unexpected size\n' >&2
    return 66
  }
  actual_hash="$(sha256sum -- "$OFFICIAL_ALPINE_ROOTFS")"
  actual_hash="${actual_hash%% *}"
  [[ "${actual_hash,,}" = "$OFFICIAL_ALPINE_SHA256" ]] || {
    printf 'approved official Alpine archive checksum mismatch\n' >&2
    return 66
  }
}

close_boot_channels() {
  exec 3>&- || true
  exec 4>&- || true
  stop_process "$CONTROL_READER_PID"
  stop_process "$TERMINAL_READER_PID"
  stop_process "$CONTROL_RELAY_PID"
  stop_process "$TERMINAL_RELAY_PID"
  CONTROL_READER_PID=""
  TERMINAL_READER_PID=""
  CONTROL_RELAY_PID=""
  TERMINAL_RELAY_PID=""
}

wait_for_qemu_exit() {
  local qemu_exit

  for ((attempt = 0; attempt < 300; attempt += 1)); do
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'official Alpine restart QEMU did not exit after terminal poweroff\n' >&2
    return 1
  fi
  set +e
  wait "$QEMU_PID"
  qemu_exit="$?"
  set -e
  QEMU_PID=""
  [[ "$qemu_exit" -eq 0 ]] || {
    printf 'official Alpine restart QEMU exited with %s\n' "$qemu_exit" >&2
    return 1
  }
}

launch_boot() {
  local boot_name="$1"
  local control_socket
  local terminal_socket
  control_socket="$(basename -- "$WORK_DIR")-$boot_name-control"
  terminal_socket="$(basename -- "$WORK_DIR")-$boot_name-terminal"
  local control_pty="$WORK_DIR/$boot_name-control.pty"
  local terminal_pty="$WORK_DIR/$boot_name-terminal.pty"
  local qemu_log="$WORK_DIR/$boot_name-qemu.log"
  local control_relay_log="$WORK_DIR/$boot_name-control-relay.log"
  local terminal_relay_log="$WORK_DIR/$boot_name-terminal-relay.log"

  CONTROL_TRANSCRIPT="$WORK_DIR/$boot_name-control-transcript"
  TERMINAL_TRANSCRIPT="$WORK_DIR/$boot_name-terminal-transcript"
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
      -chardev "socket,id=terminal,path=$terminal_socket,abstract=on,server=on,wait=off" \
      -serial chardev:terminal \
      -kernel "$ASSET_ROOT/Image" \
      -initrd "$INITRAMFS" \
      -append 'console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0' \
      -drive "if=none,id=root,file=$ROOT_VOLUME,format=raw,cache=unsafe" \
      -device virtio-blk-device,drive=root \
      -fsdev "local,id=provision,path=$INPUT_DIRECTORY,security_model=none,readonly=on,multidevs=remap" \
      -device virtio-9p-device,fsdev=provision,mount_tag=droidlayer-input \
      -device virtio-serial-device \
      -chardev "socket,id=control,path=$control_socket,abstract=on,server=on,wait=off" \
      -device virtserialport,chardev=control,name=droidlayer.bridge \
      >"$qemu_log" 2>&1 &
  QEMU_PID="$!"

  setpriv --reuid=1000 --regid=1000 --clear-groups -- \
    socat "PTY,raw,echo=0,link=$control_pty" "ABSTRACT-CONNECT:$control_socket,retry=100,interval=0.1" \
    >"$control_relay_log" 2>&1 &
  CONTROL_RELAY_PID="$!"
  setpriv --reuid=1000 --regid=1000 --clear-groups -- \
    socat "PTY,raw,echo=0,link=$terminal_pty" "ABSTRACT-CONNECT:$terminal_socket,retry=100,interval=0.1" \
    >"$terminal_relay_log" 2>&1 &
  TERMINAL_RELAY_PID="$!"
  for pty_path in "$control_pty" "$terminal_pty"; do
    for ((attempt = 0; attempt < 100; attempt += 1)); do
      [[ -e "$pty_path" ]] && break
      sleep 0.1
    done
    [[ -e "$pty_path" ]] || {
      printf 'socat did not create an official-Alpine restart PTY relay\n' >&2
      return 1
    }
  done

  exec 3<> "$control_pty"
  exec 4<> "$terminal_pty"
  timeout --signal=TERM --kill-after=1s 75s cat <&3 > "$CONTROL_TRANSCRIPT" 2>/dev/null &
  CONTROL_READER_PID="$!"
  timeout --signal=TERM --kill-after=1s 75s cat <&4 > "$TERMINAL_TRANSCRIPT" 2>/dev/null &
  TERMINAL_READER_PID="$!"
}

cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    printf 'TICKET26_OFFICIAL_ALPINE_RESTART_DIAGNOSTIC status=%s first_control_bytes=%s first_terminal_bytes=%s second_control_bytes=%s second_terminal_bytes=%s\n' \
      "$status" \
      "$(file_size_or_zero "$FIRST_CONTROL_TRANSCRIPT")" \
      "$(file_size_or_zero "$FIRST_TERMINAL_TRANSCRIPT")" \
      "$(file_size_or_zero "$SECOND_CONTROL_TRANSCRIPT")" \
      "$(file_size_or_zero "$SECOND_TERMINAL_TRANSCRIPT")" >&2
  fi
  close_boot_channels
  stop_process "$QEMU_PID"
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    case "$(realpath -- "$WORK_DIR")" in
      "$WORK_ROOT"/droidlayer-t26-implementation-official-alpine-restart.*)
        if [[ "$status" -eq 0 ]]; then
          rm -rf -- "$WORK_DIR"
        else
          printf 'official Alpine restart failure fixture retained: %s\n' "$WORK_DIR" >&2
        fi
        ;;
      *)
        printf 'refusing to clean unsafe official-Alpine restart smoke directory: %s\n' "$WORK_DIR" >&2
        status=70
        ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for required_command in \
  aarch64-linux-gnu-gcc cpio gzip qemu-system-aarch64 setpriv sha256sum socat stat timeout; do
  require_command "$required_command"
done

verify_ticket26_ticket25_asset_root "$ASSET_ROOT" || exit "$?"
verify_official_alpine_rootfs || exit "$?"

[[ -n "$WORK_ROOT" && -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || {
  printf 'TICKET26_WORK_ROOT must be one approved ordinary directory\n' >&2
  exit 64
}
WORK_ROOT="$(realpath -e -- "$WORK_ROOT")"
WORK_DIR="$(mktemp -d "$WORK_ROOT/droidlayer-t26-implementation-official-alpine-restart.XXXXXX")"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/official-alpine-restart-initramfs.cpio.gz"
INPUT_DIRECTORY="$WORK_DIR/input"
INPUT_ROOTFS="$INPUT_DIRECTORY/$OFFICIAL_ALPINE_FILE"
AGENT="$WORK_DIR/guest-agent"
ROOT_VOLUME="$WORK_DIR/root-volume.raw"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
FIRST_DATA_SENTINEL="DROIDLAYER_OFFICIAL_ALPINE_FIRST_DATA_WRITTEN"
SECOND_DATA_SENTINEL="DROIDLAYER_OFFICIAL_ALPINE_SECOND_BOOT_DATA_PRESERVED"

chmod 0700 "$WORK_DIR"
chown 1000:1000 "$WORK_DIR"
mkdir -p -- "$INITRAMFS_ROOT" "$INPUT_DIRECTORY"
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
install -D -m 0755 "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent"
install -D -m 0644 "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent.conf" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent.conf"
install -m 0755 "$GUEST_ASSETS_DIR/initramfs/init" "$INITRAMFS_ROOT/init"

cp -- "$OFFICIAL_ALPINE_ROOTFS" "$INPUT_ROOTFS"
input_hash="$(sha256sum -- "$INPUT_ROOTFS")"
input_hash="${input_hash%% *}"
[[ "${input_hash,,}" = "$OFFICIAL_ALPINE_SHA256" ]] || {
  printf 'temporary official Alpine input checksum mismatch\n' >&2
  exit 66
}
chmod 0444 "$INPUT_ROOTFS"
printf '%s\n' "$OFFICIAL_ALPINE_FILE" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.file"
printf '%s\n' "$OFFICIAL_ALPINE_SHA256" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
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
truncate --size 192M "$ROOT_VOLUME"
chown 1000:1000 "$ROOT_VOLUME"
chmod 0600 "$ROOT_VOLUME"

launch_boot first
FIRST_CONTROL_TRANSCRIPT="$CONTROL_TRANSCRIPT"
FIRST_TERMINAL_TRANSCRIPT="$TERMINAL_TRANSCRIPT"
printf '%s\n' \
  "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
  'DROIDLAYER/PING' \
  'DROIDLAYER/RESIZE 80 24' >&3
wait_for_line "$FIRST_CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1' || {
  printf 'official Alpine first boot Guest Agent did not return READY\n' >&2
  exit 1
}
wait_for_line "$FIRST_CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1' || {
  printf 'official Alpine first boot Guest Agent did not return PONG\n' >&2
  exit 1
}
wait_for_line "$FIRST_CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize' || {
  printf 'official Alpine first boot Guest Agent did not acknowledge resize\n' >&2
  exit 1
}
wait_for_line "$FIRST_TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1' || {
  printf 'official Alpine first boot Provisioning did not complete\n' >&2
  exit 1
}
printf '%s\n' \
  "printf 'persisted=1\\n' > /var/lib/droidlayer/ticket26-official-restart-sentinel" \
  "printf '$FIRST_DATA_SENTINEL\\n'" \
  'sync' \
  'poweroff' >&4
wait_for_line "$FIRST_TERMINAL_TRANSCRIPT" "$FIRST_DATA_SENTINEL" || {
  printf 'official Alpine first boot did not confirm persistent data write\n' >&2
  exit 1
}
wait_for_qemu_exit
close_boot_channels

launch_boot second
SECOND_CONTROL_TRANSCRIPT="$CONTROL_TRANSCRIPT"
SECOND_TERMINAL_TRANSCRIPT="$TERMINAL_TRANSCRIPT"
printf '%s\n' \
  "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
  'DROIDLAYER/PING' \
  'DROIDLAYER/RESIZE 80 24' >&3
wait_for_line "$SECOND_CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1' || {
  printf 'official Alpine second boot Guest Agent did not return READY\n' >&2
  exit 1
}
wait_for_line "$SECOND_CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1' || {
  printf 'official Alpine second boot Guest Agent did not return PONG\n' >&2
  exit 1
}
wait_for_line "$SECOND_CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize' || {
  printf 'official Alpine second boot Guest Agent did not acknowledge resize\n' >&2
  exit 1
}
wait_for_line "$SECOND_TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_ROOT_VOLUME_RESTART version=1 distro=alpine-3.24.1' || {
  printf 'official Alpine second boot did not reuse the Root Volume\n' >&2
  exit 1
}
printf '%s\n' \
  'cat /var/lib/droidlayer/ticket26-official-restart-sentinel' \
  "printf '$SECOND_DATA_SENTINEL\\n'" \
  'poweroff' >&4
wait_for_line "$SECOND_TERMINAL_TRANSCRIPT" 'persisted=1' || {
  printf 'official Alpine second boot did not expose persisted data\n' >&2
  exit 1
}
wait_for_line "$SECOND_TERMINAL_TRANSCRIPT" "$SECOND_DATA_SENTINEL" || {
  printf 'official Alpine second boot did not reach the terminal sentinel\n' >&2
  exit 1
}
wait_for_qemu_exit
close_boot_channels

if contains_normalized_line "$SECOND_TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then
  printf 'official Alpine second boot unexpectedly reprovisioned the Root Volume\n' >&2
  exit 1
fi
printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_RESTART_OK first_provisioning=1 second_boot=1 persisted=1 bridge=1 terminal=1\n'
