#!/usr/bin/env bash
# Orchestella Studio — reproducible ARM64 full-smp-arm64 UML kernel, 16 KB stub,
# and XZ-capable provisioning initramfs builder (W01-02).
#
# Usage:
#   SRC=/path/to/linux-um-arm64 NDK=/path/to/ndk/toolchains/llvm/prebuilt/linux-x86_64 \
#     [OUT=/path/to/out] [BUSYBOX_BIN=/path/to/busybox] [FAST_INITRAMFS_GZ=/path/to/initramfs.cpio.gz] \
#     bash research/uml-full/build-full.sh [--regen-config]
set -euo pipefail

LC_ALL=C
TZ=UTC
ZERO_AR_DATE=1
export LC_ALL TZ ZERO_AR_DATE

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
BASE_CONFIG="$REPO/research/uml-kernel-build/defconfigs/linux-bionic-smp.config"
FRAGMENT="$HERE/defconfig-full.fragment"
DEFCONFIG_FULL="$HERE/defconfig-full"

KERNEL_TAG="um-arm64-v0.3.0"
KERNEL_COMMIT="169b17ff3f66d507cb137b770ceb1fd9f4547a32"
KERNEL_VERSION="7.2.0-rc4"
NDK_API=${NDK_API:-30}
JOBS=${JOBS:-$(nproc)}
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-0}
BUILD_STAMP=${BUILD_STAMP:-"Thu Jan 1 00:00:00 UTC 1970"}
export SOURCE_DATE_EPOCH

BUSYBOX_VERSION="1.37.0-r20"
BUSYBOX_BASE=${BUSYBOX_BASE:-"https://dl-cdn.alpinelinux.org/alpine/v3.22/main"}
BUSYBOX_APK_SHA_aarch64="ee469aee2958feffd7f64dd96655704025ff60af614f77a1d7323dc237c34da2"
BUSYBOX_BIN_SHA_aarch64="a6bb1e6e349cbdfa690a78cd0017413c5446bdae02ee003ceb682d4ae1a004ff"

INITRAMFS_REL_NAME="orchestella-initramfs-full.cpio.gz"
STUB16K_LDFLAGS="-Wl,--no-rosegment -static -Wl,-z,max-page-size=16384"

die() { printf 'build-full: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }
sha256() { sha256sum "$1" | awk '{print $1}'; }

usage() {
  cat <<'EOF'
usage: build-full.sh [--regen-config] [--help]

Environment:
  SRC                linux-um-arm64 source tree (tag um-arm64-v0.3.0)
  NDK                NDK r29 prebuilt dir (.../toolchains/llvm/prebuilt/linux-x86_64)
  OUT                output dir (default: research/uml-full/out)
  JOBS               parallel jobs (default: nproc)
  BUSYBOX_BIN        optional path to pinned aarch64 busybox-static binary
  BUSYBOX_APK        optional path to pinned busybox-static-1.37.0-r20.apk
  FAST_INITRAMFS_GZ  optional path to fastboot initramfs.cpio.gz containing pinned bin/busybox
EOF
}

regen=0
while [ $# -gt 0 ]; do
  case "$1" in
    --regen-config) regen=1 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
  shift
done

SRC=${SRC:-/root/linux-um-arm64-um-arm64-v0.3.0}
NDK=${NDK:-/root/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64}
OUT=${OUT:-"$HERE/out"}

[ -d "$SRC" ] || die "SRC directory not found: $SRC"
[ -f "$SRC/Makefile" ] || die "SRC does not look like a kernel tree: $SRC"
[ -x "$NDK/bin/clang" ] || die "missing NDK clang: $NDK/bin/clang"
[ -f "$BASE_CONFIG" ] || die "missing base config: $BASE_CONFIG"
[ -f "$FRAGMENT" ] || die "missing fragment: $FRAGMENT"
need gcc
need g++
need make
need gzip
need cpio

ver=$(make -s -C "$SRC" kernelversion 2>/dev/null || true)
[ "$ver" = "$KERNEL_VERSION" ] || die "kernel version mismatch: got '$ver', expected $KERNEL_VERSION"
if [ -d "$SRC/.git" ] && command -v git >/dev/null 2>&1; then
  actual_commit=$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)
  [ "$actual_commit" = "$KERNEL_COMMIT" ] \
    || die "kernel commit mismatch: got '$actual_commit', expected $KERNEL_COMMIT ($KERNEL_TAG)"
fi

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SRC=$(cd "$SRC" && pwd)

make_args() {
  local out_dir=$1
  local prefix_flags="-ffile-prefix-map=$SRC=. -ffile-prefix-map=$out_dir=."
  MAKE_ARGS=(
    ARCH=um
    SUBARCH=arm64
    "O=$out_dir"
    HOSTCC=gcc
    HOSTCXX=g++
    "KBUILD_BUILD_TIMESTAMP=$BUILD_STAMP"
    "KBUILD_BUILD_USER=orchestella"
    "KBUILD_BUILD_HOST=uml-full-build"
    "KBUILD_BUILD_VERSION=1"
    "KCFLAGS=$prefix_flags"
    "KAFLAGS=$prefix_flags"
    "CC=$NDK/bin/clang --target=aarch64-linux-android$NDK_API"
    "LD=$NDK/bin/ld.lld"
    "AR=$NDK/bin/llvm-ar"
    "NM=$NDK/bin/llvm-nm"
    "OBJCOPY=$NDK/bin/llvm-objcopy"
    "OBJDUMP=$NDK/bin/llvm-objdump"
    "READELF=$NDK/bin/llvm-readelf"
    "STRIP=$NDK/bin/llvm-strip"
    LLVM_IAS=1
    "CLANG_TARGET_FLAGS=aarch64-linux-android$NDK_API"
  )
}

generate_config() {
  local out_dir=$1
  mkdir -p "$out_dir"
  cp "$BASE_CONFIG" "$out_dir/base.config"
  cp "$FRAGMENT" "$out_dir/full.fragment"
  cp "$out_dir/base.config" "$out_dir/.config"
  (
    cd "$out_dir" &&
    KCONFIG_CONFIG="$out_dir/.config" \
      "$SRC/scripts/kconfig/merge_config.sh" -m -Q "$out_dir/base.config" "$out_dir/full.fragment"
  )
  make_args "$out_dir"
  make -C "$SRC" "${MAKE_ARGS[@]}" olddefconfig
  "$SRC/scripts/config" --file "$out_dir/.config" --set-str INITRAMFS_SOURCE ""
  make -C "$SRC" "${MAKE_ARGS[@]}" olddefconfig
}

if [ "$regen" = 1 ]; then
  tmp_cfg="$OUT/regen-config"
  rm -rf "$tmp_cfg"
  generate_config "$tmp_cfg"
  cp "$tmp_cfg/.config" "$DEFCONFIG_FULL"
  printf 'regenerated %s (sha256 %s)\n' "$DEFCONFIG_FULL" "$(sha256 "$DEFCONFIG_FULL")"
  exit 0
fi

prepare_busybox() {
  local dest=$1
  mkdir -p "$(dirname "$dest")"
  if [ -n "${BUSYBOX_BIN:-}" ] && [ -f "$BUSYBOX_BIN" ]; then
    cp "$BUSYBOX_BIN" "$dest"
  elif [ -n "${FAST_INITRAMFS_GZ:-}" ] && [ -f "$FAST_INITRAMFS_GZ" ]; then
    local tmp_ext
    tmp_ext=$(mktemp -d)
    gzip -dc "$FAST_INITRAMFS_GZ" | (cd "$tmp_ext" && cpio -id bin/busybox 2>/dev/null)
    [ -f "$tmp_ext/bin/busybox" ] || { rm -rf "$tmp_ext"; die "bin/busybox missing in FAST_INITRAMFS_GZ"; }
    cp "$tmp_ext/bin/busybox" "$dest"
    rm -rf "$tmp_ext"
  else
    local apk="${BUSYBOX_APK:-$OUT/dl/busybox-static-aarch64-$BUSYBOX_VERSION.apk}"
    mkdir -p "$(dirname "$apk")"
    if [ ! -f "$apk" ]; then
      need curl
      curl -fsSL -o "$apk.tmp" "$BUSYBOX_BASE/aarch64/busybox-static-$BUSYBOX_VERSION.apk" \
        || die "failed to download busybox-static APK (provide BUSYBOX_BIN, BUSYBOX_APK, or FAST_INITRAMFS_GZ)"
      mv "$apk.tmp" "$apk"
    fi
    [ "$(sha256 "$apk")" = "$BUSYBOX_APK_SHA_aarch64" ] \
      || die "busybox APK sha256 mismatch (expected $BUSYBOX_APK_SHA_aarch64)"
    local tmp_apk
    tmp_apk=$(mktemp -d)
    tar xzf "$apk" -C "$tmp_apk" 2>/dev/null || tar xf "$apk" -C "$tmp_apk"
    [ -f "$tmp_apk/bin/busybox.static" ] || { rm -rf "$tmp_apk"; die "busybox.static missing in APK"; }
    cp "$tmp_apk/bin/busybox.static" "$dest"
    rm -rf "$tmp_apk"
  fi
  local actual_bb
  actual_bb=$(sha256 "$dest")
  [ "$actual_bb" = "$BUSYBOX_BIN_SHA_aarch64" ] \
    || die "busybox sha256 mismatch: got $actual_bb, expected $BUSYBOX_BIN_SHA_aarch64"
  chmod 0755 "$dest"
}

stage_initramfs() {
  local build_dir=$1
  local stage_base="$build_dir/initramfs-stage"
  local stage_dir="$stage_base.dir"
  rm -rf "$stage_dir" "$stage_base.cpio" "$stage_base.list" "$build_dir/$INITRAMFS_REL_NAME"
  mkdir -p "$stage_dir/bin" "$stage_dir/sbin" "$stage_dir/proc" "$stage_dir/sys" \
    "$stage_dir/dev/pts" "$stage_dir/run" "$stage_dir/sys/fs/cgroup" "$stage_dir/newroot" "$stage_dir/tmp"
  install -m 0755 "$HERE/initramfs/init" "$stage_dir/init"
  install -m 0755 "$HERE/initramfs/um-command.sh" "$stage_dir/um-command.sh"
  prepare_busybox "$stage_dir/bin/busybox"
  chmod 1777 "$stage_dir/tmp"

  gcc -O2 -o "$build_dir/gen_init_cpio" "$SRC/usr/gen_init_cpio.c"
  cat > "$stage_base.list" <<LISTEOF
dir / 0755 0 0
dir /bin 0755 0 0
dir /sbin 0755 0 0
dir /proc 0755 0 0
dir /sys 0755 0 0
dir /sys/fs 0755 0 0
dir /sys/fs/cgroup 0755 0 0
dir /dev 0755 0 0
dir /dev/pts 0755 0 0
dir /run 0755 0 0
dir /newroot 0755 0 0
dir /tmp 1777 0 0
file /init $stage_dir/init 0755 0 0
file /um-command.sh $stage_dir/um-command.sh 0755 0 0
file /bin/busybox $stage_dir/bin/busybox 0755 0 0
nod /dev/console 0600 0 0 c 5 1
nod /dev/null 0666 0 0 c 1 3
LISTEOF
  local applets=(
    sh ash mount umount mkdir rmdir mknod chmod chown chgrp cp ln rm mv
    cat grep egrep cut head tail sed awk tr od printf echo true false
    sleep sync poweroff switch_root chroot setsid timeout dd stat readlink
    find env id ls test "[" gunzip gzip unxz xzcat tar cpio
  )
  for app in "${applets[@]}"; do
    printf 'slink /bin/%s /bin/busybox 0755 0 0\n' "$app" >> "$stage_base.list"
  done

  "$build_dir/gen_init_cpio" -t "$SOURCE_DATE_EPOCH" "$stage_base.list" > "$stage_base.cpio" \
    || die "gen_init_cpio failed"
  gzip -n -9 -c "$stage_base.cpio" > "$build_dir/$INITRAMFS_REL_NAME"
}

BUILD_DIR="$OUT/build"
ART_DIR="$OUT/artifacts"
rm -rf "$BUILD_DIR" "$ART_DIR"
mkdir -p "$BUILD_DIR" "$ART_DIR"

stage_initramfs "$BUILD_DIR"

if [ -f "$DEFCONFIG_FULL" ]; then
  cp "$DEFCONFIG_FULL" "$BUILD_DIR/.config"
else
  generate_config "$BUILD_DIR"
fi

# Relative INITRAMFS_SOURCE keeps .config / IKCONFIG independent of build path.
"$SRC/scripts/config" --file "$BUILD_DIR/.config" --set-str INITRAMFS_SOURCE "$INITRAMFS_REL_NAME"
"$SRC/scripts/config" --file "$BUILD_DIR/.config" -e INITRAMFS_COMPRESSION_GZIP

make_args "$BUILD_DIR"
make -C "$SRC" "${MAKE_ARGS[@]}" olddefconfig
make -C "$SRC" "${MAKE_ARGS[@]}" -j"$JOBS"

# Relink stub_exe with 16 KB PT_LOAD alignment and refresh embedded stub in linux.
cp "$BUILD_DIR/arch/um/kernel/skas/stub_exe" "$ART_DIR/stub_exe-orig"
rm -f "$BUILD_DIR/arch/um/kernel/skas/stub_exe" "$BUILD_DIR/arch/um/kernel/skas/stub_exe.dbg"
make -C "$SRC" "${MAKE_ARGS[@]}" -j"$JOBS" STUB_EXE_LDFLAGS="$STUB16K_LDFLAGS"

cp "$BUILD_DIR/linux" "$ART_DIR/linux-full"
cp "$BUILD_DIR/arch/um/kernel/skas/stub_exe" "$ART_DIR/stub_exe"
cp "$BUILD_DIR/$INITRAMFS_REL_NAME" "$ART_DIR/initramfs-full.cpio.gz"
cp "$BUILD_DIR/.config" "$ART_DIR/kernel-full.config"
"$NDK/bin/llvm-strip" --strip-debug "$ART_DIR/linux-full"
chmod 755 "$ART_DIR/linux-full" "$ART_DIR/stub_exe"

# External-rootfs control variant (same config without embedded initramfs).
"$SRC/scripts/config" --file "$BUILD_DIR/.config" --set-str INITRAMFS_SOURCE ""
make -C "$SRC" "${MAKE_ARGS[@]}" olddefconfig
make -C "$SRC" "${MAKE_ARGS[@]}" -j"$JOBS" STUB_EXE_LDFLAGS="$STUB16K_LDFLAGS"
cp "$BUILD_DIR/linux" "$ART_DIR/linux-full-noinitrd"
cp "$BUILD_DIR/.config" "$ART_DIR/kernel-full-noinitrd.config"
"$NDK/bin/llvm-strip" --strip-debug "$ART_DIR/linux-full-noinitrd"
chmod 755 "$ART_DIR/linux-full-noinitrd"

# 16 KB ELF & GNU_RELRO gate
READELF="$NDK/bin/llvm-readelf" bash "$REPO/research/uml-kernel-build/check-16kb-kernel.sh" \
  "$ART_DIR/linux-full" "$ART_DIR/linux-full-noinitrd" "$ART_DIR/stub_exe"
READELF="$NDK/bin/llvm-readelf" REQUIRE_BUNDLED_CLOSURE=0 bash "$REPO/tools/ci/check-elf-16kb.sh" \
  "$ART_DIR/linux-full" "$ART_DIR/linux-full-noinitrd" "$ART_DIR/stub_exe"

(
  cd "$ART_DIR"
  sha256sum linux-full linux-full-noinitrd stub_exe initramfs-full.cpio.gz \
    kernel-full.config kernel-full-noinitrd.config > SHA256SUMS
)

{
  printf 'profile=full-smp-arm64\n'
  printf 'kernel_tag=%s\n' "$KERNEL_TAG"
  printf 'kernel_commit=%s\n' "$KERNEL_COMMIT"
  printf 'kernel_version=%s\n' "$KERNEL_VERSION"
  printf 'ndk_clang=%s\n' "$("$NDK/bin/clang" --version | head -1)"
  printf 'source_date_epoch=%s\n' "$SOURCE_DATE_EPOCH"
  printf 'build_stamp=%s\n' "$BUILD_STAMP"
  printf 'linux_full_sha256=%s\n' "$(sha256 "$ART_DIR/linux-full")"
  printf 'linux_full_size=%s\n' "$(stat -c %s "$ART_DIR/linux-full")"
  printf 'linux_full_noinitrd_sha256=%s\n' "$(sha256 "$ART_DIR/linux-full-noinitrd")"
  printf 'linux_full_noinitrd_size=%s\n' "$(stat -c %s "$ART_DIR/linux-full-noinitrd")"
  printf 'stub_exe_sha256=%s\n' "$(sha256 "$ART_DIR/stub_exe")"
  printf 'stub_exe_size=%s\n' "$(stat -c %s "$ART_DIR/stub_exe")"
  printf 'initramfs_full_sha256=%s\n' "$(sha256 "$ART_DIR/initramfs-full.cpio.gz")"
  printf 'initramfs_full_size=%s\n' "$(stat -c %s "$ART_DIR/initramfs-full.cpio.gz")"
  printf 'kernel_full_config_sha256=%s\n' "$(sha256 "$ART_DIR/kernel-full.config")"
  printf 'kernel_full_noinitrd_config_sha256=%s\n' "$(sha256 "$ART_DIR/kernel-full-noinitrd.config")"
} > "$ART_DIR/SHA256SUMS.env"

cat "$ART_DIR/SHA256SUMS"
echo "FULL_KERNEL_BUILD_OK out=$ART_DIR"
