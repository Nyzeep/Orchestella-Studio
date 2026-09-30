#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
OFFICIAL_ALPINE_ROOTFS="${TICKET26_OFFICIAL_ALPINE_ROOTFS:-}"
OFFICIAL_ALPINE_FILE="alpine-minirootfs-3.24.1-aarch64.tar.gz"
OFFICIAL_ALPINE_SHA256="f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259"
OFFICIAL_ALPINE_SIZE_BYTES=4023732
OFFICIAL_ALPINE_EXPECTATION="${TICKET26_OFFICIAL_ALPINE_EXPECTATION:-provisioning}"
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
    printf 'required official-Alpine smoke command is unavailable: %s\n' "$command_name" >&2
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

# file_size_or_zero is called only from trap-invoked cleanup(); shellcheck cannot see trap reachability
# shellcheck disable=SC2329
file_size_or_zero() {
  local path="$1"
  if [[ -f "$path" ]]; then
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

# cleanup is invoked via `trap cleanup EXIT INT TERM`; shellcheck cannot see trap reachability
# shellcheck disable=SC2329
cleanup() {
  local status="$?"
  local provisioning=0
  local provisioning_error="none"
  local agent_service=0
  local bridge_stream_error=0
  local terminal_device_error=0
  local bridge_open_error=0
  local openrc_default_complete=0
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    if [[ -f "$TERMINAL_TRANSCRIPT" ]] && contains_normalized_line "$TERMINAL_TRANSCRIPT" \
      'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then
      provisioning=1
    fi
    if [[ -f "$TERMINAL_TRANSCRIPT" ]]; then
      provisioning_error="$(tr -d '\r' < "$TERMINAL_TRANSCRIPT" \
        | grep --extended-regexp '^DROIDLAYER_PROVISIONING_ERROR stage=[a-z0-9-]+$' \
        | tail -n 1 || true)"
      provisioning_error="${provisioning_error#DROIDLAYER_PROVISIONING_ERROR stage=}"
      [[ -n "$provisioning_error" ]] || provisioning_error="none"
      if grep --fixed-strings 'DroidLayer Guest Agent' "$TERMINAL_TRANSCRIPT" >/dev/null; then
        agent_service=1
      fi
      if grep --fixed-strings 'bridge_stream is not provisioned by the execution strategy' \
        "$TERMINAL_TRANSCRIPT" >/dev/null; then
        bridge_stream_error=1
      fi
      if grep --fixed-strings 'terminal_device is not provisioned by the execution strategy' \
        "$TERMINAL_TRANSCRIPT" >/dev/null; then
        terminal_device_error=1
      fi
      if grep --fixed-strings 'cannot open bridge_stream read-write' \
        "$TERMINAL_TRANSCRIPT" >/dev/null; then
        bridge_open_error=1
      fi
      if grep --fixed-strings 'All runlevel services started' "$TERMINAL_TRANSCRIPT" >/dev/null; then
        openrc_default_complete=1
      fi
    fi
    printf 'TICKET26_OFFICIAL_ALPINE_DIAGNOSTIC status=%s provisioning=%s provisioning_error=%s agent_service=%s bridge_stream_error=%s terminal_device_error=%s bridge_open_error=%s openrc_default_complete=%s control_bytes=%s terminal_bytes=%s\n' \
      "$status" "$provisioning" "$provisioning_error" "$agent_service" "$bridge_stream_error" \
      "$terminal_device_error" "$bridge_open_error" "$openrc_default_complete" \
      "$(file_size_or_zero "$CONTROL_TRANSCRIPT")" "$(file_size_or_zero "$TERMINAL_TRANSCRIPT")" >&2
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
      "$WORK_ROOT"/droidlayer-t26-implementation-official-alpine.*)
        if [[ "$status" -eq 0 ]]; then
          rm -rf -- "$WORK_DIR"
        else
          printf 'official Alpine failure fixture retained: %s\n' "$WORK_DIR" >&2
        fi
        ;;
      *)
        printf 'refusing to clean unsafe official-Alpine smoke directory: %s\n' "$WORK_DIR" >&2
        status=70
        ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for required_command in \
  aarch64-linux-gnu-gcc blkid cpio gzip qemu-system-aarch64 setpriv sha256sum socat stat timeout; do
  require_command "$required_command"
done

verify_ticket26_ticket25_asset_root "$ASSET_ROOT" || exit "$?"
verify_official_alpine_rootfs || exit "$?"
case "$OFFICIAL_ALPINE_EXPECTATION" in
  provisioning|checksum-rejection|unauthorized|unsupported-version) ;;
  *)
    printf 'TICKET26_OFFICIAL_ALPINE_EXPECTATION must be provisioning, checksum-rejection, unauthorized, or unsupported-version\n' >&2
    exit 64
    ;;
esac

[[ -n "$WORK_ROOT" && -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || {
  printf 'TICKET26_WORK_ROOT must be one approved ordinary directory\n' >&2
  exit 64
}
WORK_ROOT="$(realpath -e -- "$WORK_ROOT")"
WORK_DIR="$(mktemp -d "$WORK_ROOT/droidlayer-t26-implementation-official-alpine.XXXXXX")"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/official-alpine-initramfs.cpio.gz"
INPUT_DIRECTORY="$WORK_DIR/input"
EMBEDDED_INPUT_DIR="$WORK_DIR/embedded-input"
EMBEDDED_CPIO="$WORK_DIR/embedded-input.cpio"
INITRAMFS_EMBEDDED="$WORK_DIR/official-alpine-initramfs-embedded.cpio.gz"
INPUT_ROOTFS="$INPUT_DIRECTORY/$OFFICIAL_ALPINE_FILE"
AGENT="$WORK_DIR/guest-agent"
ROOT_VOLUME="$WORK_DIR/root-volume.raw"
CONTROL_SOCKET_NAME="$(basename -- "$WORK_DIR")-control"
TERMINAL_SOCKET_NAME="$(basename -- "$WORK_DIR")-terminal"
CONTROL_PTY="$WORK_DIR/control.pty"
TERMINAL_PTY="$WORK_DIR/terminal.pty"
CONTROL_TRANSCRIPT="$WORK_DIR/control-transcript"
TERMINAL_TRANSCRIPT="$WORK_DIR/terminal-transcript"
QEMU_LOG="$WORK_DIR/qemu.log"
CONTROL_RELAY_LOG="$WORK_DIR/control-relay.log"
TERMINAL_RELAY_LOG="$WORK_DIR/terminal-relay.log"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
TERMINAL_SENTINEL="DROIDLAYER_OFFICIAL_ALPINE_TERMINAL_SENTINEL"

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
if [[ "$OFFICIAL_ALPINE_EXPECTATION" = checksum-rejection ]]; then
  printf '%064d\n' 0 > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
else
  printf '%s\n' "$OFFICIAL_ALPINE_SHA256" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
fi
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

# Embedded-input channel (for builders shipped without virtio-9p): append the
# same verified input files as a second, GZIPPED newc cpio segment; the
# kernel unpacks it into /opt/droidlayer/embedded-input and the guest init
# falls back to that directory when the 9p mount is unavailable.
EMBEDDED_STAGING="$WORK_DIR/embedded-root"
mkdir -p "$EMBEDDED_STAGING/opt/droidlayer/embedded-input"
cp -- "$INPUT_DIRECTORY/$OFFICIAL_ALPINE_FILE" "$EMBEDDED_STAGING/opt/droidlayer/embedded-input/$OFFICIAL_ALPINE_FILE"
printf '%s\n' "$SYNTHETIC_TOKEN" > "$EMBEDDED_STAGING/opt/droidlayer/embedded-input/bridge.token"
: > "$EMBEDDED_STAGING/opt/droidlayer/embedded-input/.active"
( cd "$EMBEDDED_STAGING" && find . -print0 | LC_ALL=C sort -z | cpio -0 -o -H newc --quiet ) > "$EMBEDDED_CPIO"
gzip -n -9 -c "$EMBEDDED_CPIO" > "$EMBEDDED_CPIO.gz"
cat -- "$INITRAMFS" "$EMBEDDED_CPIO.gz" > "$INITRAMFS_EMBEDDED"

truncate --size 192M "$ROOT_VOLUME"
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
    -chardev "socket,id=terminal,path=$TERMINAL_SOCKET_NAME,abstract=on,server=on,wait=off" \
    -serial chardev:terminal \
    -kernel "$ASSET_ROOT/Image" \
    -initrd "$INITRAMFS_EMBEDDED" \
    -append 'console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0' \
    -drive "if=none,id=root,file=$ROOT_VOLUME,format=raw,cache=unsafe" \
    -device virtio-blk-device,drive=root \
    -device virtio-serial-device \
    -chardev "socket,id=control,path=$CONTROL_SOCKET_NAME,abstract=on,server=on,wait=off" \
    -device virtserialport,chardev=control,name=droidlayer.bridge \
    >"$QEMU_LOG" 2>&1 &
QEMU_PID="$!"

setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$CONTROL_PTY" "ABSTRACT-CONNECT:$CONTROL_SOCKET_NAME,retry=100,interval=0.1" \
  >"$CONTROL_RELAY_LOG" 2>&1 &
CONTROL_RELAY_PID="$!"
setpriv --reuid=1000 --regid=1000 --clear-groups -- \
  socat "PTY,raw,echo=0,link=$TERMINAL_PTY" "ABSTRACT-CONNECT:$TERMINAL_SOCKET_NAME,retry=100,interval=0.1" \
  >"$TERMINAL_RELAY_LOG" 2>&1 &
TERMINAL_RELAY_PID="$!"

for pty_path in "$CONTROL_PTY" "$TERMINAL_PTY"; do
  for ((attempt = 0; attempt < 100; attempt += 1)); do
    [[ -e "$pty_path" ]] && break
    sleep 0.1
  done
  [[ -e "$pty_path" ]] || {
    printf 'socat did not create an official-Alpine PTY relay\n' >&2
    exit 1
  }
done

exec 3<> "$CONTROL_PTY"
exec 4<> "$TERMINAL_PTY"
timeout --signal=TERM --kill-after=1s 75s cat <&3 > "$CONTROL_TRANSCRIPT" 2>/dev/null &
CONTROL_READER_PID="$!"
timeout --signal=TERM --kill-after=1s 75s cat <&4 > "$TERMINAL_TRANSCRIPT" 2>/dev/null &
TERMINAL_READER_PID="$!"

if [[ "$OFFICIAL_ALPINE_EXPECTATION" = checksum-rejection ]]; then
  for ((attempt = 0; attempt < 300; attempt += 1)); do
    contains_normalized_line "$TERMINAL_TRANSCRIPT" \
      'DROIDLAYER_PROVISIONING_ERROR stage=rootfs-checksum' && break
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'official Alpine checksum-rejection QEMU did not exit\n' >&2
    exit 1
  fi
  set +e
  wait "$QEMU_PID"
  QEMU_EXIT="$?"
  set -e
  QEMU_PID=""
  contains_normalized_line "$TERMINAL_TRANSCRIPT" \
    'DROIDLAYER_PROVISIONING_ERROR stage=rootfs-checksum' || {
    printf 'official Alpine checksum-rejection did not report the expected failure\n' >&2
    exit 1
  }
  if contains_normalized_line "$TERMINAL_TRANSCRIPT" \
    'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then
    printf 'official Alpine checksum-rejection unexpectedly completed Provisioning\n' >&2
    exit 1
  fi
  ROOT_FILESYSTEM_TYPE="$(blkid -o value -s TYPE "$ROOT_VOLUME" 2>/dev/null || true)"
  [[ -z "$ROOT_FILESYSTEM_TYPE" ]] || {
    printf 'official Alpine checksum-rejection unexpectedly formatted the Root Volume\n' >&2
    exit 1
  }
  [[ "$QEMU_EXIT" -eq 0 ]] || {
    printf 'official Alpine checksum-rejection QEMU exited with %s\n' "$QEMU_EXIT" >&2
    exit 1
  }
  printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_CHECKSUM_REJECTION_OK rootfs_checksum=0 provisioning=0 root_volume_formatted=0\n'
  exit 0
fi

if [[ "$OFFICIAL_ALPINE_EXPECTATION" = unauthorized ]]; then
  INCORRECT_TOKEN="1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  printf '%s\n' "DROIDLAYER/HELLO 1 1 $INCORRECT_TOKEN" >&3
  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/ERROR unauthorized' || {
    printf 'official Alpine Guest Agent did not reject the incorrect token\n' >&2
    exit 1
  }
  if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1'; then
    printf 'official Alpine Guest Agent accepted the incorrect token\n' >&2
    exit 1
  fi
  wait_for_line "$TERMINAL_TRANSCRIPT" \
    'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1' || {
    printf 'official Alpine unauthorized-token boot did not complete Provisioning\n' >&2
    exit 1
  }
  exec 3>&-
  stop_process "$CONTROL_READER_PID"
  CONTROL_READER_PID=""
  stop_process "$CONTROL_RELAY_PID"
  CONTROL_RELAY_PID=""
  printf '%s\n' 'poweroff' >&4
  for ((attempt = 0; attempt < 300; attempt += 1)); do
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'official Alpine unauthorized-token QEMU did not exit after terminal poweroff\n' >&2
    exit 1
  fi
  set +e
  wait "$QEMU_PID"
  QEMU_EXIT="$?"
  set -e
  QEMU_PID=""
  [[ "$QEMU_EXIT" -eq 0 ]] || {
    printf 'official Alpine unauthorized-token QEMU exited with %s\n' "$QEMU_EXIT" >&2
    exit 1
  }
  printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_TOKEN_REJECTION_OK unauthorized=1 ready=0 provisioning=1\n'
  exit 0
fi

if [[ "$OFFICIAL_ALPINE_EXPECTATION" = unsupported-version ]]; then
  printf '%s\n' "DROIDLAYER/HELLO 2 3 $SYNTHETIC_TOKEN" >&3
  wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/ERROR unsupported-version' || {
    printf 'official Alpine Guest Agent did not reject the unsupported version range\n' >&2
    exit 1
  }
  if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1'; then
    printf 'official Alpine Guest Agent accepted the unsupported version range\n' >&2
    exit 1
  fi
  wait_for_line "$TERMINAL_TRANSCRIPT" \
    'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1' || {
    printf 'official Alpine unsupported-version boot did not complete Provisioning\n' >&2
    exit 1
  }
  exec 3>&-
  stop_process "$CONTROL_READER_PID"
  CONTROL_READER_PID=""
  stop_process "$CONTROL_RELAY_PID"
  CONTROL_RELAY_PID=""
  printf '%s\n' 'poweroff' >&4
  for ((attempt = 0; attempt < 300; attempt += 1)); do
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'official Alpine unsupported-version QEMU did not exit after terminal poweroff\n' >&2
    exit 1
  fi
  set +e
  wait "$QEMU_PID"
  QEMU_EXIT="$?"
  set -e
  QEMU_PID=""
  [[ "$QEMU_EXIT" -eq 0 ]] || {
    printf 'official Alpine unsupported-version QEMU exited with %s\n' "$QEMU_EXIT" >&2
    exit 1
  }
  printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_VERSION_REJECTION_OK unsupported_version=1 ready=0 provisioning=1\n'
  exit 0
fi

printf '%s\n' \
  "DROIDLAYER/HELLO 1 1 $SYNTHETIC_TOKEN" \
  'DROIDLAYER/PING' \
  'DROIDLAYER/RESIZE 80 24' >&3
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1' || {
  printf 'official Alpine Guest Agent did not return READY\n' >&2
  exit 1
}
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1' || {
  printf 'official Alpine Guest Agent did not return PONG\n' >&2
  exit 1
}
wait_for_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize' || {
  printf 'official Alpine Guest Agent did not acknowledge resize\n' >&2
  exit 1
}
wait_for_line "$TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1' || {
  printf 'official Alpine Provisioning did not complete\n' >&2
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
  'poweroff' >&4

for ((attempt = 0; attempt < 150; attempt += 1)); do
  contains_normalized_line "$TERMINAL_TRANSCRIPT" "$TERMINAL_SENTINEL" && break
  sleep 0.1
done

PROVISIONING=0
READY=0
PONG=0
RESIZE=0
TERMINAL_SIZE=0
SENTINEL=0
if contains_normalized_line "$TERMINAL_TRANSCRIPT" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then PROVISIONING=1; fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/READY 1'; then READY=1; fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/PONG 1'; then PONG=1; fi
if contains_normalized_line "$CONTROL_TRANSCRIPT" 'DROIDLAYER/OK resize'; then RESIZE=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" '24 80'; then TERMINAL_SIZE=1; fi
if contains_normalized_line "$TERMINAL_TRANSCRIPT" "$TERMINAL_SENTINEL"; then SENTINEL=1; fi

if [[ "$PROVISIONING" -eq 1 && "$READY" -eq 1 && "$PONG" -eq 1 && "$RESIZE" -eq 1 \
  && "$SENTINEL" -eq 0 ]]; then
  printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_PROVISIONING_RED provisioning=1 ready=1 pong=1 resize=1 terminal_size=%s sentinel=0\n' \
    "$TERMINAL_SIZE" >&2
  exit 1
fi

if [[ "$SENTINEL" -eq 1 ]]; then
  for ((attempt = 0; attempt < 300; attempt += 1)); do
    kill -0 "$QEMU_PID" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$QEMU_PID" 2>/dev/null; then
    printf 'official Alpine QEMU did not exit after terminal poweroff\n' >&2
    exit 1
  fi
  set +e
  wait "$QEMU_PID"
  QEMU_EXIT="$?"
  set -e
  QEMU_PID=""
  if [[ "$QEMU_EXIT" -eq 0 && "$PROVISIONING" -eq 1 && "$READY" -eq 1 \
    && "$PONG" -eq 1 && "$RESIZE" -eq 1 && "$TERMINAL_SIZE" -eq 1 ]]; then
    printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_PROVISIONING_OK provisioning=1 bridge=1 terminal=1 resize=24x80 sentinel=1\n'
    exit 0
  fi
fi

printf 'DROIDLAYER_QEMU_OFFICIAL_ALPINE_PROVISIONING_FAILURE provisioning=%s ready=%s pong=%s resize=%s terminal_size=%s sentinel=%s\n' \
  "$PROVISIONING" "$READY" "$PONG" "$RESIZE" "$TERMINAL_SIZE" "$SENTINEL" >&2
exit 1
