#!/usr/bin/env bash
# Ticket 26 A6 four-member consumer (section-7 sync batch, user ruling 2026-09-29).
#
# Runs INSIDE the pinned builder container (same digest as the producer; the
# current-sources extract is mounted at /workspace exactly like the producer's
# repository mount, so compiled paths stay identical). Deterministically turns
# a verified producer BASE initramfs into the Ticket 26 four-member initramfs:
#
#   1. rebuild the musl sysroot from the SAME pinned musl/linux inputs and the
#      SAME recipe as guest-assets/build.sh (lines 107-160: headers_install,
#      musl configure/build, musl-gcc wrapper) — musl objects are -g0 and the
#      agent compile has no -g, so neither embeds paths;
#   2. compile the CURRENT guest-assets/agent/guest-agent.c (build.sh lines
#      273-283 flags: -Os -static, prefix-maps, -fno-ident, build-id=none);
#   3. call tools/qemu-android/scripts/repack-initramfs-four-members.py with
#      the four contract members and their computed digests;
#   4. assemble the final out/ (base's other 11 items byte-identical + the new
#      initramfs) and regenerate SHA256SUMS with the guest-assets JVM tool in
#      build.sh's exact 12-file order.
#
# All validation gates run BEFORE any build action. Fail-closed: any missing
# input, digest mismatch, missing command, or non-reproducible step exits
# non-zero and produces nothing. Two clean runs of this script over the same
# inputs must produce byte-identical out/ trees (determinism acceptance).
set -euo pipefail

CURRENT_REPO=""
BASE_OUT=""
BASE_INITRAMFS=""
BASE_INITRAMFS_SHA256=""
CACHE_DIR=""
OUT_DIR=""
WORK_DIR=""

usage() { printf 'usage: %s --current-repo DIR --base-out DIR --base-initramfs FILE --base-initramfs-sha256 HEX --cache-dir DIR --out-dir DIR --work-dir DIR\n' "$0" >&2; exit 64; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --current-repo) CURRENT_REPO="${2:-}"; shift 2 ;;
    --base-out) BASE_OUT="${2:-}"; shift 2 ;;
    --base-initramfs) BASE_INITRAMFS="${2:-}"; shift 2 ;;
    --base-initramfs-sha256) BASE_INITRAMFS_SHA256="${2:-}"; shift 2 ;;
    --cache-dir) CACHE_DIR="${2:-}"; shift 2 ;;
    --out-dir) OUT_DIR="${2:-}"; shift 2 ;;
    --work-dir) WORK_DIR="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[[ $# -eq 0 ]] || usage

die() { printf 'GA_CONSUMER_REJECTED: %s\n' "$*" >&2; exit 65; }
[[ -n "$CURRENT_REPO" && -n "$BASE_OUT" && -n "$BASE_INITRAMFS" && -n "$BASE_INITRAMFS_SHA256" \
  && -n "$CACHE_DIR" && -n "$OUT_DIR" && -n "$WORK_DIR" ]] || die 'missing required argument'
regular_file() { [[ -f "$1" && ! -L "$1" ]]; }
regular_dir() { [[ -d "$1" && ! -L "$1" ]]; }
regular_file "$BASE_INITRAMFS" || die "base initramfs missing or indirect: $BASE_INITRAMFS"
regular_dir "$BASE_OUT" || die "base out missing or indirect: $BASE_OUT"
regular_dir "$CURRENT_REPO" || die "current repo missing or indirect: $CURRENT_REPO"
regular_dir "$CACHE_DIR" || die "cache dir missing or indirect: $CACHE_DIR"
[[ ! -e "$OUT_DIR" && ! -L "$OUT_DIR" ]] || die "out dir already exists: $OUT_DIR"
[[ ! -e "$WORK_DIR" && ! -L "$WORK_DIR" ]] || die "work dir already exists: $WORK_DIR"
[[ "$BASE_INITRAMFS_SHA256" =~ ^[0-9a-f]{64}$ ]] || die 'base initramfs sha256 pin is not 64 hex'

for required in \
  guest-assets/agent/guest-agent.c \
  guest-assets/initramfs/init \
  guest-assets/initramfs/openrc/droidlayer-guest-agent \
  guest-assets/initramfs/openrc/droidlayer-guest-agent.conf \
  guest-assets/inputs/inputs.lock \
  guest-assets/scripts/common.sh \
  guest-assets/build.gradle.kts \
  gradlew \
  tools/qemu-android/scripts/repack-initramfs-four-members.py; do
  regular_file "$CURRENT_REPO/$required" || die "current repo missing: $required"
done
for required in \
  Image initramfs.cpio.gz SHA256SUMS kernel.config busybox.config GPL-SOURCES.md \
  SOURCE-OFFER.txt LICENSE.GPL-2.0 LICENSE.e2fsprogs-libuuid LICENSE.musl \
  NOTICE.e2fsprogs ATTRIBUTION.e2fsprogs-libcom_err BUILD-INPUTS; do
  regular_file "$BASE_OUT/$required" || die "base out missing: $required"
done

GUEST_ASSETS_DIR="$CURRENT_REPO/guest-assets"
REPOSITORY_DIR="$(cd -- "$CURRENT_REPO" && pwd -P)"
# shellcheck source=/dev/null
source "$GUEST_ASSETS_DIR/scripts/common.sh"

BASE_ACTUAL="$(sha256sum -- "$BASE_INITRAMFS")"; BASE_ACTUAL="${BASE_ACTUAL%% *}"
[[ "$BASE_ACTUAL" == "$BASE_INITRAMFS_SHA256" ]] || die "base initramfs sha mismatch: $BASE_ACTUAL"

for command in aarch64-linux-gnu-gcc aarch64-linux-gnu-readelf aarch64-linux-gnu-strip \
    java make realpath sha256sum tar xz gzip curl python3; do
  command -v "$command" >/dev/null 2>&1 || die "required command unavailable: $command"
done

SOURCE_DATE_EPOCH="$(lock_value SOURCE_DATE_EPOCH)"
LINUX_VERSION="$(lock_value LINUX_VERSION)"
LINUX_URL="$(lock_value LINUX_URL)"
LINUX_SHA256="$(lock_value LINUX_SHA256)"
MUSL_VERSION="$(lock_value MUSL_VERSION)"
MUSL_URL="$(lock_value MUSL_URL)"
MUSL_SHA256="$(lock_value MUSL_SHA256)"
export SOURCE_DATE_EPOCH
export LC_ALL=C TZ=UTC
umask 022

mkdir -p -- "$WORK_DIR" "$CACHE_DIR"
STAGE="$WORK_DIR/stage"
LINUX_SRC="$WORK_DIR/linux-$LINUX_VERSION"
KERNEL_BUILD="$WORK_DIR/kernel-build"
MUSL_SYSROOT="$WORK_DIR/musl-sysroot"
MUSL_TOOLS="$WORK_DIR/musl-tools"

LINUX_ARCHIVE="$CACHE_DIR/linux-$LINUX_VERSION.tar.xz"
MUSL_ARCHIVE="$CACHE_DIR/musl-$MUSL_VERSION.tar.gz"
download_checked "$LINUX_URL" "$LINUX_SHA256" "$LINUX_ARCHIVE"
download_checked "$MUSL_URL" "$MUSL_SHA256" "$MUSL_ARCHIVE"

( cd "$CURRENT_REPO" && ./gradlew --no-daemon -p guest-assets jar ) \
  || die "guest-assets tools jar build failed"
TOOLS_JAR="$GUEST_ASSETS_DIR/build/libs/guest-assets-tools.jar"
regular_file "$TOOLS_JAR" || die "tools jar missing after build"

tar -xJf "$LINUX_ARCHIVE" -C "$WORK_DIR"
regular_dir "$LINUX_SRC" || die "linux source extraction failed"
mkdir -p -- "$KERNEL_BUILD" "$MUSL_SYSROOT" "$MUSL_TOOLS" "$STAGE"
make -C "$LINUX_SRC" O="$KERNEL_BUILD" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- defconfig >/dev/null
make -C "$LINUX_SRC" O="$KERNEL_BUILD" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- headers_install INSTALL_HDR_PATH="$MUSL_SYSROOT/usr" >/dev/null

tar -xzf "$MUSL_ARCHIVE" -C "$WORK_DIR"
MUSL_SOURCE="$WORK_DIR/musl-$MUSL_VERSION"
regular_dir "$MUSL_SOURCE" || die "musl source extraction failed"
(
  cd "$WORK_DIR" && mkdir -p musl-build
  cd musl-build
  CC=aarch64-linux-gnu-gcc \
  AR=aarch64-linux-gnu-ar \
  RANLIB=aarch64-linux-gnu-ranlib \
  CFLAGS="-O2 -g0 -fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fno-ident" \
    "$MUSL_SOURCE/configure" \
      --target=aarch64-linux-musl \
      --prefix=/usr \
      --syslibdir=/lib >/dev/null
  make -j "$(nproc)" >/dev/null
  make DESTDIR="$MUSL_SYSROOT" install >/dev/null
)
sh "$MUSL_SOURCE/tools/musl-gcc.specs.sh" \
  "$MUSL_SYSROOT/usr/include" \
  "$MUSL_SYSROOT/usr/lib" \
  "$MUSL_SYSROOT/lib/ld-musl-aarch64.so.1" \
  > "$MUSL_TOOLS/musl-gcc.specs"
cat > "$MUSL_TOOLS/aarch64-linux-musl-gcc" <<EOF
#!/bin/sh
exec aarch64-linux-gnu-gcc --sysroot="$MUSL_SYSROOT" \
  -specs "$MUSL_TOOLS/musl-gcc.specs" "\$@"
EOF
chmod 0755 "$MUSL_TOOLS/aarch64-linux-musl-gcc"
for tool in ar as ld nm objcopy objdump ranlib readelf strip; do
  ln -sf "$(command -v "aarch64-linux-gnu-$tool")" "$MUSL_TOOLS/aarch64-linux-musl-$tool"
done
MUSL_CC="$MUSL_TOOLS/aarch64-linux-musl-gcc"

"$MUSL_CC" -std=c11 -Os -Wall -Wextra -Werror -static \
  -fdebug-prefix-map="$WORK_DIR"=. -ffile-prefix-map="$WORK_DIR"=. \
  -fdebug-prefix-map="$REPOSITORY_DIR"=. -ffile-prefix-map="$REPOSITORY_DIR"=. \
  -fno-ident -Wl,--build-id=none \
  "$GUEST_ASSETS_DIR/agent/guest-agent.c" \
  -o "$STAGE/guest-agent" || die "guest-agent compile failed"
aarch64-linux-gnu-strip --strip-all "$STAGE/guest-agent"
if aarch64-linux-gnu-readelf -l "$STAGE/guest-agent" | grep -q 'INTERP'; then
  die 'guest agent is not static'
fi
cp -- "$GUEST_ASSETS_DIR/initramfs/init" "$STAGE/init"
cp -- "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent" "$STAGE/droidlayer-guest-agent"
cp -- "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent.conf" "$STAGE/droidlayer-guest-agent.conf"

REPACK="$CURRENT_REPO/tools/qemu-android/scripts/repack-initramfs-four-members.py"
member_sha() { local h; h="$(sha256sum -- "$1")"; printf '%s' "${h%% *}"; }
INIT_SHA="$(member_sha "$STAGE/init")"
AGENT_SHA="$(member_sha "$STAGE/guest-agent")"
OPENRC_SHA="$(member_sha "$STAGE/droidlayer-guest-agent")"
OPENRC_CONF_SHA="$(member_sha "$STAGE/droidlayer-guest-agent.conf")"
NEW_INITRAMFS="$WORK_DIR/initramfs-four-member.cpio.gz"
python3 "$REPACK" \
  --base "$BASE_INITRAMFS" \
  --output "$NEW_INITRAMFS" \
  --member "init=$STAGE/init" \
  --member "opt/droidlayer/guest-agent=$STAGE/guest-agent" \
  --member "opt/droidlayer/openrc/droidlayer-guest-agent=$STAGE/droidlayer-guest-agent" \
  --member "opt/droidlayer/openrc/droidlayer-guest-agent.conf=$STAGE/droidlayer-guest-agent.conf" \
  --member-sha256 "init=$INIT_SHA" \
  --member-sha256 "opt/droidlayer/guest-agent=$AGENT_SHA" \
  --member-sha256 "opt/droidlayer/openrc/droidlayer-guest-agent=$OPENRC_SHA" \
  --member-sha256 "opt/droidlayer/openrc/droidlayer-guest-agent.conf=$OPENRC_CONF_SHA" \
  || die "four-member repack rejected"

mkdir -p -- "$OUT_DIR"
for item in Image kernel.config busybox.config GPL-SOURCES.md SOURCE-OFFER.txt \
    LICENSE.GPL-2.0 LICENSE.e2fsprogs-libuuid LICENSE.musl NOTICE.e2fsprogs \
    ATTRIBUTION.e2fsprogs-libcom_err BUILD-INPUTS; do
  cp -- "$BASE_OUT/$item" "$OUT_DIR/$item"
done
cp -- "$NEW_INITRAMFS" "$OUT_DIR/initramfs.cpio.gz"
java -jar "$TOOLS_JAR" generate-manifest "$OUT_DIR" "$OUT_DIR/SHA256SUMS" \
  "$OUT_DIR/ATTRIBUTION.e2fsprogs-libcom_err" "$OUT_DIR/BUILD-INPUTS" \
  "$OUT_DIR/GPL-SOURCES.md" "$OUT_DIR/Image" \
  "$OUT_DIR/LICENSE.GPL-2.0" "$OUT_DIR/LICENSE.e2fsprogs-libuuid" \
  "$OUT_DIR/LICENSE.musl" "$OUT_DIR/NOTICE.e2fsprogs" \
  "$OUT_DIR/SOURCE-OFFER.txt" \
  "$OUT_DIR/busybox.config" \
  "$OUT_DIR/initramfs.cpio.gz" "$OUT_DIR/kernel.config" \
  || die "SHA256SUMS regeneration failed"
( cd "$OUT_DIR" && sha256sum -c SHA256SUMS --status ) || die "out SHA256SUMS self-check failed"
FINAL_SHA="$(member_sha "$OUT_DIR/initramfs.cpio.gz")"
printf 'GA_CONSUMER_OK base_initramfs_sha256=%s final_initramfs_sha256=%s guest_agent_member_sha256=%s out=%s\n' \
  "$BASE_ACTUAL" "$FINAL_SHA" "$AGENT_SHA" "$OUT_DIR"
