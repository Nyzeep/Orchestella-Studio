#!/usr/bin/env bash
# Orchestella Studio — U1 fast-shell UML profile build.
#
# Produces, from the pinned Raymer8639/linux-um-arm64 tree:
#   arm64-up    : minimal UP kernel + 16 KB-aligned stub + built-in initramfs
#   arm64-smp   : same config with CONFIG_SMP=y + NR_CPUS=8
#   x86_64-up   : host-reference build (same fragment, 4 KB pages)
#   x86_64-smp  : host-reference SMP build
#
# Every artifact is recorded in out/manifest.sha256 together with the effective
# .config, the initramfs archive and the toolchain identity.  Builds are
# deterministic for a fixed source pin, NDK r29 and container path layout:
# KBUILD_BUILD_TIMESTAMP and the initramfs cpio mtimes are pinned.
#
# Usage (inside a build container; the `--container` wrapper is below):
#   SRC=/src NDK=/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 \
#     ./build-fast.sh --targets arm64-up,arm64-smp
#
# Usage on a machine with the NDK and build deps:
#   SRC=... NDK=... ./build-fast.sh
#
# Container wrapper (requires SRC, NDK_ROOT; uses image u1-build:24.04):
#   SRC=... NDK_ROOT=... ./build-fast.sh --container
#
# It does NOT touch :app, versionCode/versionName, signing or release assets.

set -euo pipefail
LC_ALL=C
export LC_ALL

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
KERNEL_BUILD_DIR="$REPO/research/uml-kernel-build"

KERNEL_TAG="um-arm64-v0.3.0"
KERNEL_COMMIT="169b17ff3f66d507cb137b770ceb1fd9f4547a32"
KERNEL_VERSION="7.2.0-rc4"
NDK_API=${NDK_API:-30}
JOBS=${JOBS:-$(nproc)}
SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-0}
BUILD_STAMP=${BUILD_STAMP:-"Thu Jan 1 00:00:00 UTC 1970"}
BUILD_IMAGE=${BUILD_IMAGE:-u1-build:24.04}

BUSYBOX_VERSION="1.37.0-r20"
BUSYBOX_BASE=${BUSYBOX_BASE:-"https://dl-cdn.alpinelinux.org/alpine/v3.22/main"}
# sha256 of the pinned Alpine static-busybox APKs and of bin/busybox.static.
BUSYBOX_APK_SHA_aarch64="ee469aee2958feffd7f64dd96655704025ff60af614f77a1d7323dc237c34da2"
BUSYBOX_APK_SHA_x86_64="488ad6efd04b5a722719e79f8e0dcc2c24afd6758867af3ce41b04839e60c74b"
BUSYBOX_BIN_SHA_aarch64="a6bb1e6e349cbdfa690a78cd0017413c5446bdae02ee003ceb682d4ae1a004ff"
BUSYBOX_BIN_SHA_x86_64="a477b4c1b332bb9b5d926dbfc75e0efc9b53193708d8be83295bfbb376fa9839"

TARGETS=${TARGETS:-arm64-up}
STUB16K_LDFLAGS="-Wl,--no-rosegment -static -Wl,-z,max-page-size=16384"

usage() {
    cat <<'EOF'
usage: build-fast.sh [--container] [--targets arm64-up,arm64-smp,x86_64-up,x86_64-smp]
                     [--regen-config] [--help]

  --container     run the build in the pinned Ubuntu 24.04 image
                  (requires SRC and NDK_ROOT on the host)
  --targets LIST  comma-separated targets; default $TARGETS or arm64-up
  --regen-config  regenerate defconfig-fast from the pinned ARM64 UP config +
                  defconfig-fast.fragment, then exit (no kernel build)
  --help          this text

Environment:
  SRC             kernel source tree (tag um-arm64-v0.3.0)
  NDK             NDK r29 prebuilt dir (…/toolchains/llvm/prebuilt/linux-x86_64)
  OUT             output root (default: <repo>/research/uml-fastboot/out)
  JOBS            make parallelism (default: nproc)
  BUSYBOX_BASE    Alpine mirror; APKs are sha256-pinned
  BUILD_IMAGE     container image used by --container (default: u1-build:24.04)
EOF
}

die() { printf 'build-fast: %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

sha256() { sha256sum "$1" | awk '{print $1}'; }

fetch_file() {
    # fetch_file <url> <dest>
    if [ -f "$2" ]; then return 0; fi
    need curl
    curl -fsSL -o "$2.tmp" "$1" || die "download failed: $1"
    mv "$2.tmp" "$2"
}

prepare_busybox() {
    # prepare_busybox <arch> <dest-binary>
    arch=$1
    dest=$2
    case "$arch" in
        aarch64) apk_sha=$BUSYBOX_APK_SHA_aarch64; bin_sha=$BUSYBOX_BIN_SHA_aarch64 ;;
        x86_64)  apk_sha=$BUSYBOX_APK_SHA_x86_64;  bin_sha=$BUSYBOX_BIN_SHA_x86_64 ;;
        *) die "unsupported busybox arch: $arch" ;;
    esac
    apk="$OUT/dl/busybox-static-$arch-$BUSYBOX_VERSION.apk"
    mkdir -p "$(dirname "$apk")"
    fetch_file "$BUSYBOX_BASE/$arch/busybox-static-$BUSYBOX_VERSION.apk" "$apk"
    [ "$(sha256 "$apk")" = "$apk_sha" ] \
        || die "busybox APK sha256 mismatch for $arch (expected $apk_sha)"
    tmp=$(mktemp -d)
    tar xzf "$apk" -C "$tmp" 2>/dev/null || tar xf "$apk" -C "$tmp"
    [ -f "$tmp/bin/busybox.static" ] || die "busybox.static missing in APK for $arch"
    [ "$(sha256 "$tmp/bin/busybox.static")" = "$bin_sha" ] \
        || die "busybox binary sha256 mismatch for $arch"
    install -m 0755 "$tmp/bin/busybox.static" "$dest"
    rm -rf "$tmp"
}

stage_initramfs() {
    # stage_initramfs <arch> <stage-base> — writes <stage-base>.cpio and
    # leaves loose files in <stage-base>.dir for inspection.  Device nodes are
    # described in the cpio list instead of mknod: the repo filesystem is a
    # bind/FUSE mount that may refuse mknod, while gen_init_cpio creates them
    # inside the archive.
    arch=$1
    base=$2
    dir="$base.dir"
    rm -rf "$dir" "$base.cpio" "$base.list"
    mkdir -p "$dir"/bin "$dir"/proc "$dir"/sys "$dir"/dev/pts \
        "$dir"/sys/fs/cgroup "$dir"/newroot "$dir"/tmp
    install -m 0755 "$HERE/initramfs/init" "$dir/init"
    install -m 0755 "$HERE/initramfs/um-command.sh" "$dir/um-command.sh"
    prepare_busybox "$arch" "$dir/bin/busybox"
    chmod 1777 "$dir/tmp"
    need gcc
    gcc -O2 -o "$OUT/gen_init_cpio" "$SRC/usr/gen_init_cpio.c"
    cat > "$base.list" <<LISTEOF
dir / 0755 0 0
dir /bin 0755 0 0
dir /proc 0755 0 0
dir /sys 0755 0 0
dir /sys/fs 0755 0 0
dir /sys/fs/cgroup 0755 0 0
dir /dev 0755 0 0
dir /dev/pts 0755 0 0
dir /newroot 0755 0 0
dir /tmp 1777 0 0
file /init $dir/init 0755 0 0
file /um-command.sh $dir/um-command.sh 0755 0 0
file /bin/busybox $dir/bin/busybox 0755 0 0
slink /bin/sh /bin/busybox 0755 0 0
slink /bin/mount /bin/busybox 0755 0 0
slink /bin/umount /bin/busybox 0755 0 0
slink /bin/mkdir /bin/busybox 0755 0 0
slink /bin/grep /bin/busybox 0755 0 0
slink /bin/cat /bin/busybox 0755 0 0
slink /bin/cut /bin/busybox 0755 0 0
slink /bin/sync /bin/busybox 0755 0 0
slink /bin/poweroff /bin/busybox 0755 0 0
slink /bin/sleep /bin/busybox 0755 0 0
slink /bin/switch_root /bin/busybox 0755 0 0
slink /bin/uname /bin/busybox 0755 0 0
slink /bin/true /bin/busybox 0755 0 0
slink /bin/echo /bin/busybox 0755 0 0
nod /dev/console 0600 0 0 c 5 1
nod /dev/null 0666 0 0 c 1 3
LISTEOF
    "$OUT/gen_init_cpio" -t "$SOURCE_DATE_EPOCH" "$base.list" > "$base.cpio" \
        || die "gen_init_cpio failed for $arch"
}
make_common() {
    # make_common <out-dir> <subarch> — prints arguments one per line
    out=$1
    subarch=$2
    printf '%s\n' "ARCH=um" "SUBARCH=$subarch" "O=$out" \
        "HOSTCC=gcc" "HOSTCXX=g++" \
        "KBUILD_BUILD_TIMESTAMP=$BUILD_STAMP" \
        "KBUILD_BUILD_USER=orchestella" "KBUILD_BUILD_HOST=u1-fastboot"
    if [ "$subarch" = arm64 ]; then
        [ -n "${NDK:-}" ] || die "NDK is required for ARM64 builds"
        [ -x "$NDK/bin/clang" ] || die "missing NDK clang: $NDK/bin/clang"
        printf '%s\n' \
            "CC=$NDK/bin/clang --target=aarch64-linux-android$NDK_API" \
            "LD=$NDK/bin/ld.lld" "AR=$NDK/bin/llvm-ar" "NM=$NDK/bin/llvm-nm" \
            "OBJCOPY=$NDK/bin/llvm-objcopy" "OBJDUMP=$NDK/bin/llvm-objdump" \
            "READELF=$NDK/bin/llvm-readelf" "STRIP=$NDK/bin/llvm-strip" \
            "LLVM_IAS=1" "CLANG_TARGET_FLAGS=aarch64-linux-android$NDK_API"
    else
        # Host-reference build only.  The pinned tree force-includes
        # include/linux/compiler_attributes.h into UML USER_OBJS for the
        # NDK/bionic sysroot; on glibc that macro collides with
        # __attribute_alloc_size__ in <stdlib.h>.  Bionic has no such macro,
        # so the ARM64 product build is untouched.  The wrapper drops only
        # that one force-include; kernel objects still get compiler_types.h.
        ensure_x86_cc
        # GNU ld (the x86 host-reference linker) has no --no-rosegment; that
        # flag is LLD-specific and only exists for the 16 KB stub recipe.
        # Upstream's x86 stub uses -n -static and a single loadable segment.
        printf '%s\n' "CC=$OUT/cc-x86_64" "STUB_EXE_LDFLAGS=-Wl,-n -static"
    fi
}

ensure_x86_cc() {
    cat > "$OUT/cc-x86_64" <<'WRAP'
#!/usr/bin/env bash
args=()
while [ $# -gt 0 ]; do
    if [ "$1" = "-include" ]; then
        case "${2:-}" in
            */include/linux/compiler_attributes.h) shift 2; continue ;;
        esac
    fi
    args+=("$1")
    shift
done
exec /usr/bin/gcc "${args[@]}"
WRAP
    chmod 0755 "$OUT/cc-x86_64"
}

readarray_make_args() {
    # Bash 3 has no readarray; the build container has Bash 5.  Keep it simple.
    mapfile -t MAKE_ARGS < <(make_common "$1" "$2")
}

check_pin() {
    [ -d "$SRC" ] || die "SRC is not a directory: $SRC"
    [ -f "$SRC/Makefile" ] || die "SRC does not look like a kernel tree: $SRC"
    ver=$(make -s -C "$SRC" kernelversion 2>/dev/null || true)
    [ "$ver" = "$KERNEL_VERSION" ] \
        || die "kernel version mismatch: got '$ver', expected $KERNEL_VERSION"
    if [ -d "$SRC/.git" ]; then
        head=$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)
        [ "$head" = "$KERNEL_COMMIT" ] \
            || printf 'warning: SRC HEAD %s != pinned %s\n' "$head" "$KERNEL_COMMIT" >&2
    fi
}

set_initramfs_source() {
    # set_initramfs_source <config> <stage>
    "$SRC/scripts/config" --file "$1" --set-str INITRAMFS_SOURCE "$2"
    # The compression choice is hidden while INITRAMFS_SOURCE is empty; pin it
    # now so olddefconfig cannot fall back to an uncompressed image.
    "$SRC/scripts/config" --file "$1" -e INITRAMFS_COMPRESSION_GZIP
}

generate_arm64_config() {
    # generate_arm64_config <out-dir> <up|smp>
    out=$1
    profile=$2
    mkdir -p "$out"
    if [ "$profile" = up ]; then
        cp "$HERE/defconfig-fast" "$out/.config"
    else
        cp "$HERE/defconfig-fast" "$out/.config"
        "$SRC/scripts/config" --file "$out/.config" -e SMP
        "$SRC/scripts/config" --file "$out/.config" --set-val NR_CPUS 8
    fi
}

generate_x86_config() {
    # generate_x86_config <out-dir> <up|smp>
    out=$1
    profile=$2
    mkdir -p "$out"
    readarray_make_args "$out" x86_64
    make "${MAKE_ARGS[@]}" x86_64_defconfig
    cp "$out/.config" "$out/base.config"
    ( cd "$out" && KCONFIG_CONFIG="$out/.config" \
        "$SRC/scripts/kconfig/merge_config.sh" -m -Q \
        "$out/base.config" "$HERE/defconfig-fast.fragment" )
    if [ "$profile" = smp ]; then
        "$SRC/scripts/config" --file "$out/.config" -e SMP
        "$SRC/scripts/config" --file "$out/.config" --set-val NR_CPUS 8
    else
        "$SRC/scripts/config" --file "$out/.config" -d SMP
    fi
}

build_one() {
    # build_one <target> — target is arm64-up|arm64-smp|x86_64-up|x86_64-smp
    target=$1
    case "$target" in
        arm64-*) subarch=arm64; arch=aarch64; profile=${target#arm64-} ;;
        x86_64-*) subarch=x86_64; arch=x86_64; profile=${target#x86_64-} ;;
        *) die "unknown target: $target" ;;
    esac
    out="$OUT/$target"
    stage="$OUT/initramfs-$target"
    mkdir -p "$out"
    stage_initramfs "$arch" "$stage"
    stage_cpio="$stage.cpio"

    if [ "$subarch" = arm64 ]; then
        generate_arm64_config "$out" "$profile"
    else
        generate_x86_config "$out" "$profile"
    fi
    set_initramfs_source "$out/.config" "$stage_cpio"

    readarray_make_args "$out" "$subarch"
    make "${MAKE_ARGS[@]}" olddefconfig
    make "${MAKE_ARGS[@]}" -j"$JOBS"

    mkdir -p "$out/artifacts"
    cp "$out/linux" "$out/artifacts/linux-fast"
    cp "$out/arch/um/kernel/skas/stub_exe" "$out/artifacts/stub_exe"
    cp "$out/usr/initramfs_inc_data" "$out/artifacts/initramfs.cpio.gz" 2>/dev/null || true
    if [ "$subarch" = arm64 ]; then
        "$NDK/bin/llvm-strip" --strip-debug "$out/artifacts/linux-fast"
    fi
    chmod 755 "$out/artifacts/linux-fast" "$out/artifacts/stub_exe"

    if [ "$subarch" = arm64 ]; then
        # Relink the stub for 16 KB PT_LOAD alignment, then refresh the kernel
        # so its embedded stub is the aligned one too (build-stub-16k recipe).
        cp "$out/arch/um/kernel/skas/stub_exe" "$out/artifacts/stub_exe-orig"
        rm -f "$out/arch/um/kernel/skas/stub_exe" \
              "$out/arch/um/kernel/skas/stub_exe.dbg"
        make "${MAKE_ARGS[@]}" -j"$JOBS" STUB_EXE_LDFLAGS="$STUB16K_LDFLAGS"
        cp "$out/arch/um/kernel/skas/stub_exe" "$out/artifacts/stub_exe"
        cp "$out/linux" "$out/artifacts/linux-fast"
        "$NDK/bin/llvm-strip" --strip-debug "$out/artifacts/linux-fast"
        chmod 755 "$out/artifacts/stub_exe" "$out/artifacts/linux-fast"
        cp "$out/usr/initramfs_inc_data" "$out/artifacts/initramfs.cpio.gz" 2>/dev/null || true
    fi

    cp "$out/.config" "$out/artifacts/kernel.config"

    # External-root comparison kernel: same config, no built-in initramfs.
    # The fast initramfs is the default T3 path; this variant isolates the
    # "initramfs vs external rootfs" cost instead of charging both.
    "$SRC/scripts/config" --file "$out/.config" --set-str INITRAMFS_SOURCE ""
    make "${MAKE_ARGS[@]}" olddefconfig
    if [ "$subarch" = arm64 ]; then
        make "${MAKE_ARGS[@]}" -j"$JOBS" STUB_EXE_LDFLAGS="$STUB16K_LDFLAGS"
        cp "$out/linux" "$out/artifacts/linux-fast-noinitrd"
        "$NDK/bin/llvm-strip" --strip-debug "$out/artifacts/linux-fast-noinitrd"
        chmod 755 "$out/artifacts/linux-fast-noinitrd"
    else
        make "${MAKE_ARGS[@]}" -j"$JOBS"
        cp "$out/linux" "$out/artifacts/linux-fast-noinitrd"
        chmod 755 "$out/artifacts/linux-fast-noinitrd"
    fi
    cp "$out/.config" "$out/artifacts/kernel-noinitrd.config"

    {
        printf 'target=%s\n' "$target"
        printf 'kernel_sha256=%s\n' "$(sha256 "$out/artifacts/linux-fast")"
        printf 'kernel_noinitrd_sha256=%s\n' "$(sha256 "$out/artifacts/linux-fast-noinitrd")"
        printf 'stub_sha256=%s\n' "$(sha256 "$out/artifacts/stub_exe")"
        printf 'config_sha256=%s\n' "$(sha256 "$out/artifacts/kernel.config")"
        printf 'config_noinitrd_sha256=%s\n' "$(sha256 "$out/artifacts/kernel-noinitrd.config")"
        if [ -f "$out/artifacts/initramfs.cpio.gz" ]; then
            printf 'initramfs_sha256=%s\n' "$(sha256 "$out/artifacts/initramfs.cpio.gz")"
            printf 'initramfs_size=%s\n' "$(stat -c %s "$out/artifacts/initramfs.cpio.gz")"
        fi
        printf 'kernel_size=%s\n' "$(stat -c %s "$out/artifacts/linux-fast")"
        printf 'kernel_noinitrd_size=%s\n' "$(stat -c %s "$out/artifacts/linux-fast-noinitrd")"
        printf 'stub_size=%s\n' "$(stat -c %s "$out/artifacts/stub_exe")"
    } > "$out/artifacts/SHA256SUMS.env"
    printf 'built %s\n' "$target"
}

container_wrapper() {
    [ -n "${SRC:-}" ] || die "--container requires SRC"
    [ -n "${NDK_ROOT:-}" ] || die "--container requires NDK_ROOT (NDK root, not the prebuilt dir)"
    [ -d "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64" ] \
        || die "NDK_ROOT does not contain toolchains/llvm/prebuilt/linux-x86_64: $NDK_ROOT"
    docker image inspect "$BUILD_IMAGE" >/dev/null 2>&1 \
        || die "container image $BUILD_IMAGE not found (see README for the one-line build)"
    tgt=${TARGETS:-arm64-up}
    exec docker run --rm \
        -e U1_FASTBOOT_IN_CONTAINER=1 \
        -e SRC=/src \
        -e NDK=/ndk/toolchains/llvm/prebuilt/linux-x86_64 \
        -e OUT=/work/research/uml-fastboot/out \
        -e TARGETS="$tgt" \
        -e JOBS="$JOBS" \
        -v "$REPO:/work" \
        -v "$SRC:/src:ro" \
        -v "$NDK_ROOT:/ndk:ro" \
        "$BUILD_IMAGE" \
        bash /work/research/uml-fastboot/build-fast.sh
}

main() {
    regen=0
    container=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --container) container=1 ;;
            --targets) shift; TARGETS=${1:-} ;;
            --regen-config) regen=1 ;;
            --help|-h) usage; exit 0 ;;
            *) usage >&2; die "unknown argument: $1" ;;
        esac
        shift
    done
    [ -n "$TARGETS" ] || die "empty --targets"
    if [ "$container" = 1 ]; then container_wrapper; fi

    SRC=${SRC:-/src}
    OUT=${OUT:-"$HERE/out"}
    mkdir -p "$OUT"
    OUT=$(cd "$OUT" && pwd)
    export SRC OUT

    if [ "$regen" = 1 ]; then
        check_pin
        cd "$SRC"
        tmp="$OUT/regen-arm64"
        rm -rf "$tmp"; mkdir -p "$tmp"
        cp "$KERNEL_BUILD_DIR/defconfigs/linux-bionic.config" "$tmp/.config"
        ( cd "$tmp" && KCONFIG_CONFIG="$tmp/.config" \
            "$SRC/scripts/kconfig/merge_config.sh" -m -Q \
            "$KERNEL_BUILD_DIR/defconfigs/linux-bionic.config" \
            "$HERE/defconfig-fast.fragment" )
        readarray_make_args "$tmp" arm64
        make "${MAKE_ARGS[@]}" olddefconfig
        "$SRC/scripts/config" --file "$tmp/.config" -d INITRAMFS_SOURCE 2>/dev/null || true
        "$SRC/scripts/config" --file "$tmp/.config" --set-str INITRAMFS_SOURCE ""
        cp "$tmp/.config" "$HERE/defconfig-fast"
        printf 'regenerated %s (sha256 %s)\n' "$HERE/defconfig-fast" "$(sha256 "$HERE/defconfig-fast")"
        exit 0
    fi

    check_pin
    cd "$SRC"
    oldifs=$IFS; IFS=,; set -- $TARGETS; IFS=$oldifs
    for t in "$@"; do
        [ -n "$t" ] || continue
        build_one "$t"
    done
    {
        printf 'kernel_pin=%s\n' "$KERNEL_COMMIT"
        printf 'kernel_tag=%s\n' "$KERNEL_TAG"
        printf 'kernel_version=%s\n' "$KERNEL_VERSION"
        printf 'ndk=%s\n' "${NDK:-host-gcc}"
        printf 'ndk_clang=%s\n' "$([ -n "${NDK:-}" ] && "$NDK/bin/clang" --version | head -1 || echo n/a)"
        printf 'build_stamp=%s\n' "$BUILD_STAMP"
        printf 'source_date_epoch=%s\n' "$SOURCE_DATE_EPOCH"
        for d in "$OUT"/arm64-* "$OUT"/x86_64-*; do
            [ -f "$d/artifacts/SHA256SUMS.env" ] && cat "$d/artifacts/SHA256SUMS.env" && printf '\n'
        done
    } > "$OUT/manifest.sha256"
    printf 'manifest: %s\n' "$OUT/manifest.sha256"
}

main "$@"
