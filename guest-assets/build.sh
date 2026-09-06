#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/scripts/common.sh"

export LC_ALL=C
export TZ=UTC
umask 022

WORK_DIR="${GUEST_ASSETS_WORK_DIR:-$GUEST_ASSETS_DIR/.work/build}"
OUT_DIR="${GUEST_ASSETS_OUT_DIR:-$GUEST_ASSETS_DIR/out}"
DOWNLOAD_CACHE="${GUEST_ASSETS_DOWNLOAD_CACHE:-$GUEST_ASSETS_DIR/.work/downloads}"
MAKE_JOBS="${GUEST_ASSETS_MAKE_JOBS:-$(nproc)}"

for command in aarch64-linux-gnu-gcc aarch64-linux-gnu-readelf aarch64-linux-gnu-strip \
    bison cpio curl flex gzip java make realpath sha256sum tar xz; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'required build command is unavailable: %s\n' "$command" >&2
    exit 69
  }
done

SOURCE_DATE_EPOCH="$(lock_value SOURCE_DATE_EPOCH)"
LINUX_VERSION="$(lock_value LINUX_VERSION)"
LINUX_URL="$(lock_value LINUX_URL)"
LINUX_SHA256="$(lock_value LINUX_SHA256)"
BUSYBOX_VERSION="$(lock_value BUSYBOX_VERSION)"
BUSYBOX_URL="$(lock_value BUSYBOX_URL)"
BUSYBOX_SHA256="$(lock_value BUSYBOX_SHA256)"
MUSL_VERSION="$(lock_value MUSL_VERSION)"
MUSL_URL="$(lock_value MUSL_URL)"
MUSL_SHA256="$(lock_value MUSL_SHA256)"
E2FSPROGS_VERSION="$(lock_value E2FSPROGS_VERSION)"
E2FSPROGS_URL="$(lock_value E2FSPROGS_URL)"
E2FSPROGS_SHA256="$(lock_value E2FSPROGS_SHA256)"
ALPINE_VERSION="$(lock_value ALPINE_VERSION)"
ALPINE_ROOTFS_FILE="$(lock_value ALPINE_ROOTFS_FILE)"
ALPINE_ROOTFS_SHA256="$(lock_value ALPINE_ROOTFS_SHA256)"
BUILDER_IMAGE="$(lock_value BUILDER_IMAGE)"
BUILDER_IMAGE_PLATFORM="$(lock_value BUILDER_IMAGE_PLATFORM)"

export SOURCE_DATE_EPOCH
export KBUILD_BUILD_USER=droidlayer
export KBUILD_BUILD_HOST=guest-assets-builder
export KBUILD_BUILD_VERSION=1
export KBUILD_BUILD_TIMESTAMP
KBUILD_BUILD_TIMESTAMP="$(date --utc --date="@$SOURCE_DATE_EPOCH" '+%a %b %d %H:%M:%S UTC %Y')"

safe_reset_directory "$WORK_DIR"
safe_reset_directory "$OUT_DIR"
mkdir -p -- "$DOWNLOAD_CACHE"

LINUX_ARCHIVE="$DOWNLOAD_CACHE/linux-$LINUX_VERSION.tar.xz"
BUSYBOX_ARCHIVE="$DOWNLOAD_CACHE/busybox-$BUSYBOX_VERSION.tar.bz2"
MUSL_ARCHIVE="$DOWNLOAD_CACHE/musl-$MUSL_VERSION.tar.gz"
E2FSPROGS_ARCHIVE="$DOWNLOAD_CACHE/e2fsprogs-$E2FSPROGS_VERSION.tar.xz"
download_checked "$LINUX_URL" "$LINUX_SHA256" "$LINUX_ARCHIVE"
download_checked "$BUSYBOX_URL" "$BUSYBOX_SHA256" "$BUSYBOX_ARCHIVE"
download_checked "$MUSL_URL" "$MUSL_SHA256" "$MUSL_ARCHIVE"
download_checked "$E2FSPROGS_URL" "$E2FSPROGS_SHA256" "$E2FSPROGS_ARCHIVE"

"$REPOSITORY_DIR/gradlew" --no-daemon -p "$GUEST_ASSETS_DIR" jar
TOOLS_JAR="$GUEST_ASSETS_DIR/build/libs/guest-assets-tools.jar"

tar -xJf "$LINUX_ARCHIVE" -C "$WORK_DIR"
KERNEL_SOURCE="$WORK_DIR/linux-$LINUX_VERSION"
KERNEL_BUILD="$WORK_DIR/kernel-build"
mkdir -p -- "$KERNEL_BUILD"
make -C "$KERNEL_SOURCE" O="$KERNEL_BUILD" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- defconfig
KERNEL_FRAGMENTS=("$GUEST_ASSETS_DIR/kernel/droidlayer.fragment")
if [[ -n "${GUEST_ASSETS_TEST_KERNEL_FRAGMENT:-}" ]]; then
  [[ -f "$GUEST_ASSETS_TEST_KERNEL_FRAGMENT" ]] || {
    printf 'test kernel fragment does not exist: %s\n' \
      "$GUEST_ASSETS_TEST_KERNEL_FRAGMENT" >&2
    exit 64
  }
  TEST_KERNEL_FRAGMENT="$(realpath -- "$GUEST_ASSETS_TEST_KERNEL_FRAGMENT")"
  case "$TEST_KERNEL_FRAGMENT" in
    /tmp/droidlayer-guest-assets.*/*) ;;
    *)
      printf 'test kernel fragment must be under a droidlayer temp directory: %s\n' \
        "$TEST_KERNEL_FRAGMENT" >&2
      exit 64
      ;;
  esac
  KERNEL_FRAGMENTS+=("$TEST_KERNEL_FRAGMENT")
fi
(
  cd -- "$KERNEL_SOURCE"
  ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
    scripts/kconfig/merge_config.sh -m -O "$KERNEL_BUILD" \
      "$KERNEL_BUILD/.config" "${KERNEL_FRAGMENTS[@]}"
)
make -C "$KERNEL_SOURCE" O="$KERNEL_BUILD" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- olddefconfig
java -jar "$TOOLS_JAR" verify-config \
  "$KERNEL_BUILD/.config" "$GUEST_ASSETS_DIR/kernel/required.config"
make -C "$KERNEL_SOURCE" O="$KERNEL_BUILD" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- \
  KBUILD_ABS_SRCTREE=0 \
  KCFLAGS="-fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fdebug-prefix-map=$REPOSITORY_DIR=. -ffile-prefix-map=$REPOSITORY_DIR=." \
  KCPPFLAGS="-fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fdebug-prefix-map=$REPOSITORY_DIR=. -ffile-prefix-map=$REPOSITORY_DIR=." \
  -j "$MAKE_JOBS" Image

MUSL_SYSROOT="$WORK_DIR/musl-sysroot"
MUSL_TOOLS="$WORK_DIR/musl-tools"
make -C "$KERNEL_SOURCE" O="$KERNEL_BUILD" ARCH=arm64 \
  headers_install INSTALL_HDR_PATH="$MUSL_SYSROOT/usr"
tar -xzf "$MUSL_ARCHIVE" -C "$WORK_DIR"
MUSL_SOURCE="$WORK_DIR/musl-$MUSL_VERSION"
MUSL_BUILD="$WORK_DIR/musl-build"
mkdir -p -- "$MUSL_BUILD" "$MUSL_TOOLS"
(
  cd -- "$MUSL_BUILD"
  CC=aarch64-linux-gnu-gcc \
  AR=aarch64-linux-gnu-ar \
  RANLIB=aarch64-linux-gnu-ranlib \
  CFLAGS="-O2 -g0 -fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fno-ident" \
    "$MUSL_SOURCE/configure" \
      --target=aarch64-linux-musl \
      --prefix=/usr \
      --syslibdir=/lib
  make -j "$MAKE_JOBS"
  make DESTDIR="$MUSL_SYSROOT" install
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
MUSL_BINUTILS=(
  addr2line ar as c++filt elfedit gprof ld ld.bfd nm objcopy objdump
  ranlib readelf size strings strip
)
for tool in "${MUSL_BINUTILS[@]}"; do
  GNU_TOOL="$(command -v "aarch64-linux-gnu-$tool")" || {
    printf 'required cross-binutils command is unavailable: aarch64-linux-gnu-%s\n' \
      "$tool" >&2
    exit 69
  }
  ln -s "$GNU_TOOL" \
    "$MUSL_TOOLS/aarch64-linux-musl-$tool"
done
for tool in gcc "${MUSL_BINUTILS[@]}"; do
  [[ -x "$MUSL_TOOLS/aarch64-linux-musl-$tool" ]] || {
    printf 'musl-prefixed tool is unavailable: aarch64-linux-musl-%s\n' \
      "$tool" >&2
    exit 69
  }
done
MUSL_CROSS_COMPILE="$MUSL_TOOLS/aarch64-linux-musl-"
MUSL_CC="${MUSL_CROSS_COMPILE}gcc"

tar -xjf "$BUSYBOX_ARCHIVE" -C "$WORK_DIR"
BUSYBOX_SOURCE="$WORK_DIR/busybox-$BUSYBOX_VERSION"
BUSYBOX_BUILD="$WORK_DIR/busybox-build"
mkdir -p -- "$BUSYBOX_BUILD"
make -C "$BUSYBOX_SOURCE" O="$BUSYBOX_BUILD" ARCH=arm64 \
  CROSS_COMPILE="$MUSL_CROSS_COMPILE" defconfig
BUSYBOX_REQUIRED_SYMBOLS=(
  CONFIG_STATIC CONFIG_INSTALL_APPLET_SYMLINKS CONFIG_ASH CONFIG_SH_IS_ASH
  CONFIG_MOUNT CONFIG_UMOUNT CONFIG_TAR CONFIG_FEATURE_TAR_GNU_EXTENSIONS
  CONFIG_FEATURE_SEAMLESS_GZ CONFIG_GZIP CONFIG_SHA256SUM CONFIG_CAT CONFIG_CUT
  CONFIG_TR CONFIG_CP CONFIG_CHMOD CONFIG_MKDIR CONFIG_SLEEP CONFIG_SYNC
  CONFIG_POWEROFF
)
BUSYBOX_DISABLED_SYMBOLS=(
  CONFIG_TC CONFIG_SHA1_HWACCEL CONFIG_SHA256_HWACCEL
)
for symbol in "${BUSYBOX_REQUIRED_SYMBOLS[@]}"; do
  if grep -q "^# $symbol is not set$" "$BUSYBOX_BUILD/.config"; then
    sed -i "s/^# $symbol is not set$/$symbol=y/" "$BUSYBOX_BUILD/.config"
  elif grep -q "^$symbol=" "$BUSYBOX_BUILD/.config"; then
    sed -i "s/^$symbol=.*/$symbol=y/" "$BUSYBOX_BUILD/.config"
  else
    printf 'required BusyBox config symbol is unknown: %s\n' "$symbol" >&2
    exit 1
  fi
done
for symbol in "${BUSYBOX_DISABLED_SYMBOLS[@]}"; do
  if grep -q "^$symbol=" "$BUSYBOX_BUILD/.config"; then
    sed -i "s/^$symbol=.*/# $symbol is not set/" "$BUSYBOX_BUILD/.config"
  elif ! grep -q "^# $symbol is not set$" "$BUSYBOX_BUILD/.config"; then
    printf 'disabled BusyBox config symbol is unknown: %s\n' "$symbol" >&2
    exit 1
  fi
done
make -C "$BUSYBOX_SOURCE" O="$BUSYBOX_BUILD" ARCH=arm64 \
  CROSS_COMPILE="$MUSL_CROSS_COMPILE" oldconfig </dev/null
for symbol in "${BUSYBOX_REQUIRED_SYMBOLS[@]}"; do
  grep -qx "$symbol=y" "$BUSYBOX_BUILD/.config" || {
    printf 'required BusyBox config was not retained: %s\n' "$symbol" >&2
    exit 1
  }
done
for symbol in "${BUSYBOX_DISABLED_SYMBOLS[@]}"; do
  grep -qx "# $symbol is not set" "$BUSYBOX_BUILD/.config" || {
    printf 'disabled BusyBox config was not retained: %s\n' "$symbol" >&2
    exit 1
  }
done
make -C "$BUSYBOX_SOURCE" O="$BUSYBOX_BUILD" ARCH=arm64 \
  CROSS_COMPILE="$MUSL_CROSS_COMPILE" \
  KBUILD_BUILD_TIMESTAMP="$KBUILD_BUILD_TIMESTAMP" \
  EXTRA_CFLAGS="-fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fdebug-prefix-map=$REPOSITORY_DIR=. -ffile-prefix-map=$REPOSITORY_DIR=. -fno-ident" \
  -j "$MAKE_JOBS"
make -C "$BUSYBOX_SOURCE" O="$BUSYBOX_BUILD" ARCH=arm64 \
  CROSS_COMPILE="$MUSL_CROSS_COMPILE" CONFIG_PREFIX="$WORK_DIR/initramfs" install
for applet in \
    bin/sh bin/mount bin/umount bin/tar bin/gzip usr/bin/sha256sum bin/cat \
    usr/bin/cut usr/bin/tr bin/cp bin/chmod bin/mkdir bin/sleep bin/sync \
    sbin/poweroff; do
  [[ -L "$WORK_DIR/initramfs/$applet" ]] || {
    printf 'required BusyBox applet symlink was not installed: /%s\n' "$applet" >&2
    exit 1
  }
done

tar -xJf "$E2FSPROGS_ARCHIVE" -C "$WORK_DIR"
E2FSPROGS_SOURCE="$WORK_DIR/e2fsprogs-$E2FSPROGS_VERSION"
E2FSPROGS_BUILD="$WORK_DIR/e2fsprogs-build"
mkdir -p -- "$E2FSPROGS_BUILD"
(
  cd -- "$E2FSPROGS_BUILD"
  PATH="$MUSL_TOOLS:$PATH" \
  CC="$MUSL_CC" \
  AR="${MUSL_CROSS_COMPILE}ar" \
  LD="${MUSL_CROSS_COMPILE}ld" \
  NM="${MUSL_CROSS_COMPILE}nm" \
  OBJCOPY="${MUSL_CROSS_COMPILE}objcopy" \
  RANLIB="${MUSL_CROSS_COMPILE}ranlib" \
  READELF="${MUSL_CROSS_COMPILE}readelf" \
  STRIP="${MUSL_CROSS_COMPILE}strip" \
  BUILD_CC=gcc \
  PKG_CONFIG=/bin/false \
  ac_cv_lib_magic_magic_file=no \
  CFLAGS="-O2 -g0 -fdebug-prefix-map=$WORK_DIR=. -ffile-prefix-map=$WORK_DIR=. -fdebug-prefix-map=$REPOSITORY_DIR=. -ffile-prefix-map=$REPOSITORY_DIR=. -fno-ident" \
  LDFLAGS_STATIC='-static -Wl,--build-id=none' \
    "$E2FSPROGS_SOURCE/configure" \
      --build="$("$E2FSPROGS_SOURCE/config/config.guess")" \
      --host=aarch64-linux-musl \
      --disable-nls \
      --disable-imager \
      --disable-resizer \
      --disable-defrag \
      --disable-uuidd \
      --without-libarchive
  make -j "$MAKE_JOBS" libs
  make -C misc -j "$MAKE_JOBS" mke2fs.static
)

INITRAMFS_ROOT="$WORK_DIR/initramfs"
mkdir -p -- "$INITRAMFS_ROOT/sbin" "$INITRAMFS_ROOT/etc/droidlayer" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc" "$INITRAMFS_ROOT/dev" \
  "$INITRAMFS_ROOT/proc" "$INITRAMFS_ROOT/sys" "$INITRAMFS_ROOT/run" \
  "$INITRAMFS_ROOT/newroot"
cp -- "$E2FSPROGS_BUILD/misc/mke2fs.static" "$INITRAMFS_ROOT/sbin/mkfs.ext4"
cp -- "$E2FSPROGS_BUILD/misc/mke2fs.conf" "$INITRAMFS_ROOT/etc/mke2fs.conf"
aarch64-linux-gnu-strip --strip-all "$INITRAMFS_ROOT/sbin/mkfs.ext4"
if aarch64-linux-gnu-readelf -l "$INITRAMFS_ROOT/sbin/mkfs.ext4" | grep -q 'INTERP'; then
  printf 'mke2fs is not static\n' >&2
  exit 1
fi

"$MUSL_CC" -std=c11 -Os -Wall -Wextra -Werror -static \
  -fdebug-prefix-map="$WORK_DIR"=. -ffile-prefix-map="$WORK_DIR"=. \
  -fdebug-prefix-map="$REPOSITORY_DIR"=. -ffile-prefix-map="$REPOSITORY_DIR"=. \
  -fno-ident -Wl,--build-id=none \
  "$GUEST_ASSETS_DIR/agent/guest-agent.c" \
  -o "$INITRAMFS_ROOT/opt/droidlayer/guest-agent"
aarch64-linux-gnu-strip --strip-all "$INITRAMFS_ROOT/opt/droidlayer/guest-agent"
if aarch64-linux-gnu-readelf -l "$INITRAMFS_ROOT/opt/droidlayer/guest-agent" | grep -q 'INTERP'; then
  printf 'guest agent is not static\n' >&2
  exit 1
fi

cp -- "$GUEST_ASSETS_DIR/initramfs/init" "$INITRAMFS_ROOT/init"
cp -- "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent"
cp -- "$GUEST_ASSETS_DIR/initramfs/openrc/droidlayer-guest-agent.conf" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent.conf"
printf '%s\n' "$ALPINE_ROOTFS_FILE" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.file"
printf '%s\n' "$ALPINE_ROOTFS_SHA256" > "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
chmod 0755 "$INITRAMFS_ROOT/init" "$INITRAMFS_ROOT/sbin/mkfs.ext4" \
  "$INITRAMFS_ROOT/opt/droidlayer/guest-agent" \
  "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent"
chmod 0644 "$INITRAMFS_ROOT/opt/droidlayer/openrc/droidlayer-guest-agent.conf" \
  "$INITRAMFS_ROOT/etc/droidlayer/rootfs.file" \
  "$INITRAMFS_ROOT/etc/droidlayer/rootfs.sha256"
mknod -m 0600 "$INITRAMFS_ROOT/dev/console" c 5 1
mknod -m 0666 "$INITRAMFS_ROOT/dev/null" c 1 3

find "$INITRAMFS_ROOT" -xdev -exec touch -h --date="@$SOURCE_DATE_EPOCH" {} +
(
  cd -- "$INITRAMFS_ROOT"
  find . -xdev -print0 \
    | LC_ALL=C sort -z \
    | cpio --null --create --format=newc --owner=0:0 --reproducible --quiet \
    | gzip -n -9 > "$OUT_DIR/initramfs.cpio.gz"
)

cp -- "$KERNEL_BUILD/arch/arm64/boot/Image" "$OUT_DIR/Image"
cp -- "$KERNEL_BUILD/.config" "$OUT_DIR/kernel.config"
cp -- "$BUSYBOX_BUILD/.config" "$OUT_DIR/busybox.config"
cp -- "$GUEST_ASSETS_DIR/GPL-SOURCES.md" "$OUT_DIR/GPL-SOURCES.md"
cp -- "$GUEST_ASSETS_DIR/SOURCE-OFFER.txt" "$OUT_DIR/SOURCE-OFFER.txt"
cp -- "$BUSYBOX_SOURCE/LICENSE" "$OUT_DIR/LICENSE.GPL-2.0"
cp -- "$E2FSPROGS_SOURCE/NOTICE" "$OUT_DIR/NOTICE.e2fsprogs"
cp -- "$E2FSPROGS_SOURCE/lib/uuid/COPYING" \
  "$OUT_DIR/LICENSE.e2fsprogs-libuuid"
{
  printf '%s\n' \
    'e2fsprogs 1.47.2 libcom_err source-header attributions' \
    'Copied verbatim from the checksum-verified corresponding source.'
  for source in error_message.c et_name.c init_et.c com_err.c com_right.c; do
    printf '\n===== lib/et/%s =====\n' "$source"
    sed '/^#include/,$d' "$E2FSPROGS_SOURCE/lib/et/$source"
  done
} > "$OUT_DIR/ATTRIBUTION.e2fsprogs-libcom_err"
cp -- "$MUSL_SOURCE/COPYRIGHT" "$OUT_DIR/LICENSE.musl"
cat > "$OUT_DIR/BUILD-INPUTS" <<EOF
builder.image=$BUILDER_IMAGE
builder.platform=$BUILDER_IMAGE_PLATFORM
ubuntu.snapshot=$(lock_value UBUNTU_SNAPSHOT)
source_date_epoch=$SOURCE_DATE_EPOCH
linux.version=$LINUX_VERSION
linux.sha256=$LINUX_SHA256
busybox.version=$BUSYBOX_VERSION
busybox.sha256=$BUSYBOX_SHA256
musl.version=$MUSL_VERSION
musl.sha256=$MUSL_SHA256
e2fsprogs.version=$E2FSPROGS_VERSION
e2fsprogs.sha256=$E2FSPROGS_SHA256
alpine.version=$ALPINE_VERSION
alpine.rootfs.sha256=$ALPINE_ROOTFS_SHA256
EOF
if [[ -f /tmp/droidlayer-builder-packages.txt ]]; then
  sed 's/^/builder.package./' /tmp/droidlayer-builder-packages.txt \
    >> "$OUT_DIR/BUILD-INPUTS"
fi

java -jar "$TOOLS_JAR" generate-manifest "$OUT_DIR" "$OUT_DIR/SHA256SUMS" \
  "$OUT_DIR/ATTRIBUTION.e2fsprogs-libcom_err" "$OUT_DIR/BUILD-INPUTS" \
  "$OUT_DIR/GPL-SOURCES.md" "$OUT_DIR/Image" \
  "$OUT_DIR/LICENSE.GPL-2.0" "$OUT_DIR/LICENSE.e2fsprogs-libuuid" \
  "$OUT_DIR/LICENSE.musl" "$OUT_DIR/NOTICE.e2fsprogs" \
  "$OUT_DIR/SOURCE-OFFER.txt" \
  "$OUT_DIR/busybox.config" \
  "$OUT_DIR/initramfs.cpio.gz" "$OUT_DIR/kernel.config"

printf 'DROIDLAYER_GUEST_ASSETS_BUILD_OK output=%s\n' "$OUT_DIR"
