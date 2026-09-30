#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

ASSET_ROOT="${TICKET26_ASSET_ROOT:-}"
PROVISIONING_EXPECTATION="${TICKET26_PROVISIONING_EXPECTATION:-restart}"
WORK_DIR=""

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || {
    printf 'required provisioning-restart smoke command is unavailable: %s\n' "$command_name" >&2
    exit 69
  }
}

contains_normalized_line() {
  local path="$1"
  local expected_line="$2"
  tr -d '\r' < "$path" | grep --fixed-strings --line-regexp "$expected_line" >/dev/null
}

cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    printf 'provisioning-restart smoke diagnostic status=%s first_console_bytes=%s second_console_bytes=%s\n' \
      "$status" \
      "$( [[ -f "$FIRST_CONSOLE_LOG" ]] && wc -c < "$FIRST_CONSOLE_LOG" || printf 0 )" \
      "$( [[ -f "$SECOND_CONSOLE_LOG" ]] && wc -c < "$SECOND_CONSOLE_LOG" || printf 0 )" >&2
    for console_log in "$FIRST_CONSOLE_LOG" "$SECOND_CONSOLE_LOG"; do
      if [[ -f "$console_log" ]]; then
        tr -d '\r' < "$console_log" \
          | grep --extended-regexp '^DROIDLAYER_(PROVISIONING|ROOT_VOLUME|SYNTHETIC)_' \
          | head -n 12 >&2 || true
        tr -d '\r' < "$console_log" \
          | grep --ignore-case --extended-regexp '(^/init:|^sh:|switch_root|mount:|failed|failure|panic|not found|cannot|error)' \
          | sed "s|$WORK_DIR|<temp>|g" \
          | head -n 8 >&2 || true
      fi
    done
  fi
  if [[ -n "$WORK_DIR" && -d "$WORK_DIR" ]]; then
    case "$(realpath -- "$WORK_DIR")" in
      /tmp/droidlayer-t26-implementation-provisioning-restart.*)
        rm -rf -- "$WORK_DIR"
        ;;
      *)
        printf 'refusing to clean unsafe provisioning-restart smoke directory: %s\n' "$WORK_DIR" >&2
        status=70
        ;;
    esac
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for required_command in \
  aarch64-linux-gnu-gcc cpio debugfs gzip qemu-system-aarch64 setpriv sha256sum timeout; do
  require_command "$required_command"
done

verify_ticket26_ticket25_asset_root "$ASSET_ROOT" || exit "$?"

WORK_DIR="$(mktemp -d /tmp/droidlayer-t26-implementation-provisioning-restart.XXXXXX)"
INITRAMFS_ROOT="$WORK_DIR/initramfs-root"
INITRAMFS="$WORK_DIR/provisioning-restart-initramfs.cpio.gz"
INPUT_DIRECTORY="$WORK_DIR/input"
SYNTHETIC_ROOTFS="$INPUT_DIRECTORY/ticket26-synthetic-rootfs.tar.gz"
SYNTHETIC_ROOTFS_DIRECTORY="$WORK_DIR/synthetic-rootfs"
AGENT="$WORK_DIR/guest-agent"
ROOT_VOLUME="$WORK_DIR/root-volume.raw"
FIRST_CONSOLE_LOG="$WORK_DIR/first-console.log"
SECOND_CONSOLE_LOG="$WORK_DIR/second-console.log"
FIRST_QEMU_LOG="$WORK_DIR/first-qemu.log"
SECOND_QEMU_LOG="$WORK_DIR/second-qemu.log"
PERSISTED_SENTINEL="$WORK_DIR/persisted-sentinel"
SYNTHETIC_TOKEN="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

chmod 0700 "$WORK_DIR"
chown 1000:1000 "$WORK_DIR"
mkdir -p -- "$INITRAMFS_ROOT" "$INPUT_DIRECTORY" "$SYNTHETIC_ROOTFS_DIRECTORY"

case "$PROVISIONING_EXPECTATION" in
  restart|checksum-rejection) ;;
  *)
    printf 'TICKET26_PROVISIONING_EXPECTATION must be restart or checksum-rejection\n' >&2
    exit 64
    ;;
esac

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
mkdir -p -- \
  "$SYNTHETIC_ROOTFS_DIRECTORY/dev" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/etc" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/proc" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/sbin" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/sys" \
  "$SYNTHETIC_ROOTFS_DIRECTORY/var/lib/droidlayer"
cat > "$SYNTHETIC_ROOTFS_DIRECTORY/sbin/init" <<'INIT'
#!/bin/sh
set -eu

sentinel=/var/lib/droidlayer/ticket26-restart-sentinel
if [ -f "$sentinel" ]; then
    printf '%s\n' 'DROIDLAYER_SYNTHETIC_SECOND_BOOT_PERSISTED' >/dev/console
else
    printf '%s\n' 'DROIDLAYER_SYNTHETIC_FIRST_BOOT' >/dev/console
    printf '%s\n' 'persisted=1' > "$sentinel"
fi
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
if [[ "$PROVISIONING_EXPECTATION" = restart ]]; then
  sha256sum -- "$SYNTHETIC_ROOTFS" | awk '{print $1}' > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
else
  printf '%064d\n' 0 > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
fi
printf '%s\n' "$SYNTHETIC_TOKEN" > "$INPUT_DIRECTORY/bridge.token"
# QEMU/9p executes as the unprivileged test UID, so the synthetic input must be readable through
# the read-only guest mount. Provisioning immediately copies it into the Root Volume as 0600.
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

run_qemu() {
  local console_log="$1"
  local qemu_log="$2"
  set +e
  timeout --signal=TERM --kill-after=2s 45s \
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
        -serial "file:$console_log" \
        -no-reboot \
        -kernel "$ASSET_ROOT/Image" \
        -initrd "$INITRAMFS" \
        -append 'console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input' \
        -drive "if=none,id=root,file=$ROOT_VOLUME,format=raw,cache=unsafe" \
        -device virtio-blk-device,drive=root \
        -fsdev "local,id=provision,path=$INPUT_DIRECTORY,security_model=none,readonly=on,multidevs=remap" \
        -device virtio-9p-device,fsdev=provision,mount_tag=droidlayer-input \
      >"$qemu_log" 2>&1
  local qemu_exit="$?"
  set -e
  [[ "$qemu_exit" -eq 0 ]] || {
    printf 'QEMU provisioning-restart boot failed with exit code %s\n' "$qemu_exit" >&2
    return "$qemu_exit"
  }
}

run_qemu "$FIRST_CONSOLE_LOG" "$FIRST_QEMU_LOG"
if [[ "$PROVISIONING_EXPECTATION" = checksum-rejection ]]; then
  contains_normalized_line "$FIRST_CONSOLE_LOG" \
    'DROIDLAYER_PROVISIONING_ERROR stage=rootfs-checksum'
  if contains_normalized_line "$FIRST_CONSOLE_LOG" \
    'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then
    printf 'checksum-rejected input unexpectedly completed Provisioning\n' >&2
    exit 1
  fi
  ROOT_FILESYSTEM_TYPE="$(/sbin/blkid -o value -s TYPE "$ROOT_VOLUME" 2>/dev/null || true)"
  [[ -z "$ROOT_FILESYSTEM_TYPE" ]] || {
    printf 'checksum-rejected input unexpectedly formatted the Root Volume\n' >&2
    exit 1
  }
  printf 'DROIDLAYER_QEMU_PROVISIONING_CHECKSUM_REJECTION_OK rootfs_checksum=0 provisioning=0 root_volume_formatted=0\n'
  exit 0
fi
contains_normalized_line "$FIRST_CONSOLE_LOG" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'
contains_normalized_line "$FIRST_CONSOLE_LOG" 'DROIDLAYER_SYNTHETIC_FIRST_BOOT'
debugfs -R "dump -p /var/lib/droidlayer/ticket26-restart-sentinel $PERSISTED_SENTINEL" \
  "$ROOT_VOLUME" >/dev/null 2>&1
grep --fixed-strings --line-regexp 'persisted=1' "$PERSISTED_SENTINEL" >/dev/null
TOKEN_MODE="$(debugfs -R 'stat /etc/droidlayer/bridge.token' "$ROOT_VOLUME" 2>/dev/null \
  | sed -n 's/.*Mode: *0*\([0-7]\{4\}\).*/\1/p')"
[[ "$TOKEN_MODE" = 0600 ]] || {
  printf 'provisioned Root Volume token mode is not 0600\n' >&2
  exit 1
}

run_qemu "$SECOND_CONSOLE_LOG" "$SECOND_QEMU_LOG"
contains_normalized_line "$SECOND_CONSOLE_LOG" 'DROIDLAYER_SYNTHETIC_SECOND_BOOT_PERSISTED'
if contains_normalized_line "$SECOND_CONSOLE_LOG" \
  'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1'; then
  printf 'second boot unexpectedly reprovisioned the Root Volume\n' >&2
  exit 1
fi
debugfs -R "dump -p /var/lib/droidlayer/ticket26-restart-sentinel $PERSISTED_SENTINEL" \
  "$ROOT_VOLUME" >/dev/null 2>&1
grep --fixed-strings --line-regexp 'persisted=1' "$PERSISTED_SENTINEL" >/dev/null

printf 'DROIDLAYER_QEMU_PROVISIONING_RESTART_OK first_provisioning=1 second_boot=1 persisted=1\n'
