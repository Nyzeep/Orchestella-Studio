#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

for command in cmp dd debugfs grep od qemu-system-aarch64 sha256sum timeout tr truncate wc; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'required smoke command is unavailable: %s\n' "$command" >&2
    exit 69
  }
done

OUT_DIR="${GUEST_ASSETS_OUT_DIR:-$GUEST_ASSETS_DIR/out}"
DOWNLOAD_CACHE="${GUEST_ASSETS_DOWNLOAD_CACHE:-$GUEST_ASSETS_DIR/.work/downloads}"
SMOKE_TIMEOUT="${GUEST_ASSETS_SMOKE_TIMEOUT:-300}"
SMOKE_WORK="${GUEST_ASSETS_SMOKE_WORK:-$GUEST_ASSETS_DIR/.work/smoke}"
ALPINE_ROOTFS_FILE="$(lock_value ALPINE_ROOTFS_FILE)"
ALPINE_ROOTFS_URL="$(lock_value ALPINE_ROOTFS_URL)"
ALPINE_ROOTFS_SHA256="$(lock_value ALPINE_ROOTFS_SHA256)"

[[ "$SMOKE_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
  printf 'invalid smoke timeout: %s\n' "$SMOKE_TIMEOUT" >&2
  exit 64
}

safe_reset_directory "$SMOKE_WORK"
cleanup() {
  rm -rf -- "$SMOKE_WORK"
}
trap cleanup EXIT INT TERM

[[ -f "$OUT_DIR/Image" && -f "$OUT_DIR/initramfs.cpio.gz" \
   && -f "$OUT_DIR/SHA256SUMS" ]] || {
  printf 'build outputs are missing; run guest-assets/build.sh first\n' >&2
  exit 66
}
(
  cd -- "$OUT_DIR"
  sha256sum --check --strict SHA256SUMS
)

mkdir -p -- "$DOWNLOAD_CACHE" "$SMOKE_WORK/input"
ROOTFS_ARCHIVE="$DOWNLOAD_CACHE/$ALPINE_ROOTFS_FILE"
download_checked "$ALPINE_ROOTFS_URL" "$ALPINE_ROOTFS_SHA256" "$ROOTFS_ARCHIVE"
cp -- "$ROOTFS_ARCHIVE" "$SMOKE_WORK/input/$ALPINE_ROOTFS_FILE"

umask 077
TOKEN="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
[[ "${#TOKEN}" -eq 64 ]] || {
  printf 'failed to generate the provisioning token\n' >&2
  exit 1
}
printf '%s\n' "$TOKEN" > "$SMOKE_WORK/input/bridge.token"

# init reads these three trust files from the same 9p input directory. Production
# EmbeddedProvisionInput writes them alongside the archive; the smoke must match.
ROOTFS_SIZE="$(wc -c < "$ROOTFS_ARCHIVE" | tr -d ' ')"
printf '%s\n' "$ALPINE_ROOTFS_FILE" > "$SMOKE_WORK/input/rootfs.file"
printf '%s\n' "$ALPINE_ROOTFS_SHA256" > "$SMOKE_WORK/input/rootfs.sha256"
printf '%s\n' "$ROOTFS_SIZE" > "$SMOKE_WORK/input/rootfs.size"

ROOT_VOLUME="$SMOKE_WORK/root-volume.raw"
CONSOLE_LOG="$SMOKE_WORK/console.log"
truncate --size 2G "$ROOT_VOLUME"
# Production marks a fresh volume with this 32-byte signature before it becomes
# eligible for provisioning; the smoke must create the same initial state.
printf 'ORCHESTELLA_EMPTY_ROOT_VOLUME_V2' | dd of="$ROOT_VOLUME" bs=1 conv=notrunc status=none

set +e
timeout --signal=TERM --kill-after=10s "$SMOKE_TIMEOUT" \
  qemu-system-aarch64 \
    -machine virt,gic-version=3 \
    -accel tcg,thread=multi \
    -cpu cortex-a57 \
    -smp 2 \
    -m 1024 \
    -nodefaults \
    -display none \
    -monitor none \
    -serial stdio \
    -no-reboot \
    -kernel "$OUT_DIR/Image" \
    -initrd "$OUT_DIR/initramfs.cpio.gz" \
    -append 'console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input' \
    -drive "if=none,id=root,file=$ROOT_VOLUME,format=raw,cache=unsafe" \
    -device virtio-blk-device,drive=root \
    -fsdev "local,id=provision,path=$SMOKE_WORK/input,security_model=none,readonly=on,multidevs=remap" \
    -device virtio-9p-device,fsdev=provision,mount_tag=droidlayer-input \
  2>&1 | tee "$CONSOLE_LOG"
QEMU_EXIT="${PIPESTATUS[0]}"
set -e

if [[ "$QEMU_EXIT" -ne 0 ]]; then
  printf 'QEMU provisioning failed with exit code %s\n' "$QEMU_EXIT" >&2
  exit "$QEMU_EXIT"
fi
tr -d '\r' < "$CONSOLE_LOG" \
  | grep --fixed-strings --line-regexp \
      'DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine-3.24.1' \
      >/dev/null

debugfs_dump() {
  local guest_path="$1"
  local host_path="$2"
  debugfs -R "dump -p $guest_path $host_path" "$ROOT_VOLUME" >/dev/null 2>&1
}

debugfs_dump /etc/alpine-release "$SMOKE_WORK/alpine-release"
grep --fixed-strings --line-regexp '3.24.1' "$SMOKE_WORK/alpine-release" >/dev/null
debugfs_dump /etc/droidlayer/bridge.token "$SMOKE_WORK/provisioned-token"
cmp --silent "$SMOKE_WORK/input/bridge.token" "$SMOKE_WORK/provisioned-token"
debugfs_dump /usr/sbin/droidlayer-guest-agent "$SMOKE_WORK/installed-agent"
[[ -s "$SMOKE_WORK/installed-agent" ]]
debugfs_dump /var/lib/droidlayer/provisioned-v1 "$SMOKE_WORK/provisioned-v1"
grep --fixed-strings --line-regexp 'provisioning_version=1' \
  "$SMOKE_WORK/provisioned-v1" >/dev/null
grep --fixed-strings --line-regexp 'distro=alpine-3.24.1' \
  "$SMOKE_WORK/provisioned-v1" >/dev/null

TOKEN_MODE="$(debugfs -R 'stat /etc/droidlayer/bridge.token' "$ROOT_VOLUME" 2>/dev/null \
  | sed -n 's/.*Mode: *0*\([0-7]\{4\}\).*/\1/p')"
[[ "$TOKEN_MODE" = 0600 ]]
FILESYSTEM_FEATURES="$(debugfs -R stats "$ROOT_VOLUME" 2>/dev/null \
  | sed -n 's/^Filesystem features: *//p')"
[[ " $FILESYSTEM_FEATURES " == *' has_journal '* ]]
[[ " $FILESYSTEM_FEATURES " == *' extent '* ]]

cp -- "$CONSOLE_LOG" "$OUT_DIR/qemu-smoke.log"
printf 'DROIDLAYER_QEMU_SMOKE_OK\n' | tee "$OUT_DIR/qemu-smoke-result.txt"
