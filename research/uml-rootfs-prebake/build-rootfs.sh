#!/usr/bin/env bash
# Build a pre-baked Linux rootfs for the UML fast-shell lane.
#
# The first boot of this image only mounts /proc, /sys, /dev, /dev/pts and
# cgroup2 and execs /ready.sh. Package installation, locale generation,
# certificate bundles and the base toolchain all happen here, at build time.
#
# Supported distros: alpine (musl) and debian (glibc).
# Supported target arches: aarch64 (alias arm64) and x86_64 (alias amd64).
#
# The script is deliberately research-scoped: it writes only below --out and
# --work, and never touches the product tree or the product provisioning path.

set -euo pipefail

SELF_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"

# Pinned inputs. Changing any of these changes the manifest "inputs" block.
ALPINE_BRANCH="${ALPINE_BRANCH:-3.22}"
ALPINE_VERSION="${ALPINE_VERSION:-3.22.8}"
ALPINE_BASE_SHA256_AARCH64="${ALPINE_BASE_SHA256_AARCH64:-36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4}"
ALPINE_BASE_SHA256_X86_64="${ALPINE_BASE_SHA256_X86_64:-2d7f46af9b7e86ad64ebeba3fe9b75b62dd5b259e708afb2ab21268f627696e5}"
APK_STATIC_VERSION="${APK_STATIC_VERSION:-2.14.13-r0}"
APK_STATIC_SHA256="${APK_STATIC_SHA256:-d78d2a9ebadc8179b3b9063c3316e948f594c833db51eaeabe67f1d044245527}"

DEBIAN_SUITE="${DEBIAN_SUITE:-trixie}"
DEBIAN_SNAPSHOT="${DEBIAN_SNAPSHOT:-20261109T000000Z}"
DEBIAN_MIRROR="${DEBIAN_MIRROR:-https://snapshot-cloudflare.debian.org/archive/debian/${DEBIAN_SNAPSHOT}/}"

# 2026-11-10T00:00:00Z. Every file mtime in the shipped archive is clamped to
# this value so the artifact does not depend on wall-clock build time.
DEFAULT_SOURCE_DATE_EPOCH=1762732800

ALPINE_PACKAGES_DEFAULT="bash coreutils findutils grep sed gawk tar gzip xz zstd curl wget ca-certificates openssl openssh git tzdata musl-locales procps iproute2 less e2fsprogs build-base python3"
DEBIAN_PACKAGES_DEFAULT="locales ca-certificates bash coreutils findutils grep sed gawk tar gzip xz-utils zstd less procps iproute2 tzdata curl wget git openssh-server openssh-client build-essential python3 openssl e2fsprogs iputils-ping"

DISTRO=""
ARCH=""
OUT_DIR=""
WORK_DIR=""
PACKAGES=""
SOURCE_DATE_EPOCH="$DEFAULT_SOURCE_DATE_EPOCH"
KEEP_WORK=0
WITH_EXT4=0

log()  { printf '[prebake] %s\n' "$*" >&2; }
warn() { printf '[prebake] WARN: %s\n' "$*" >&2; }
die()  { printf '[prebake] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
    cat >&2 <<'EOF'
Usage: build-rootfs.sh --distro alpine|debian --arch aarch64|x86_64 --out DIR [options]

Options:
  --work DIR                 Build work directory (default: <out>/work)
  --packages "p1 p2 ..."     Override the distro package set
  --source-date-epoch N      Reproducible timestamp (default: 1762732800)
  --keep-work                Keep the extracted rootfs under --work
  --ext4                     Also emit a deterministic ext4 image
  --help                     Show this help

Outputs (inside --out):
  <distro>-<version>-<arch>.tar.gz          deterministic rootfs archive
  <distro>-<version>-<arch>.tar.gz.sha256   sidecar checksum
  <distro>-<version>-<arch>.ext4            optional ext4 image
  manifest.json                             build inputs, packages, outputs

Environment pins:
  ALPINE_BRANCH=3.22  ALPINE_VERSION=3.22.8  APK_STATIC_VERSION=2.14.13-r0
  DEBIAN_SUITE=trixie DEBIAN_SNAPSHOT=20261109T000000Z
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --distro) DISTRO="${2:-}"; shift 2 ;;
        --arch) ARCH="${2:-}"; shift 2 ;;
        --out) OUT_DIR="${2:-}"; shift 2 ;;
        --work) WORK_DIR="${2:-}"; shift 2 ;;
        --packages) PACKAGES="${2:-}"; shift 2 ;;
        --source-date-epoch) SOURCE_DATE_EPOCH="${2:-}"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        --ext4) WITH_EXT4=1; shift ;;
        --help|-h) usage; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

[ -n "$DISTRO" ] || { usage; die "--distro is required"; }
[ -n "$ARCH" ] || { usage; die "--arch is required"; }
[ -n "$OUT_DIR" ] || { usage; die "--out is required"; }
case "$SOURCE_DATE_EPOCH" in ''|*[!0-9]*) die "--source-date-epoch must be an integer" ;; esac

case "$ARCH" in
    arm64) ARCH=aarch64 ;;
    amd64) ARCH=x86_64 ;;
    aarch64|x86_64) ;;
    *) die "unsupported arch: $ARCH" ;;
esac

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    arm64) HOST_ARCH=aarch64 ;;
    amd64) HOST_ARCH=x86_64 ;;
esac
[ "$HOST_ARCH" = aarch64 ] || [ "$HOST_ARCH" = x86_64 ] \
    || die "unsupported build host arch: $HOST_ARCH"

mkdir -p "$OUT_DIR"
OUT_DIR="$(CDPATH='' cd -- "$OUT_DIR" && pwd)"
[ -n "$WORK_DIR" ] || WORK_DIR="$OUT_DIR/work"
mkdir -p "$WORK_DIR"
WORK_DIR="$(CDPATH='' cd -- "$WORK_DIR" && pwd)"
CACHE_DIR="$WORK_DIR/cache"
mkdir -p "$CACHE_DIR"

# Builds run as root because debootstrap/chroot and file ownership require it.
[ "$(id -u)" -eq 0 ] || die "must run as root (debootstrap/chroot and root-owned files)"

T0="$(date +%s)"
START_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

fetch() {
    url="$1"; dest="$2"
    if [ -s "$dest" ]; then return 0; fi
    log "fetch $url"
    curl -fsSL --retry 3 --retry-delay 2 -o "$dest.tmp" "$url"
    mv "$dest.tmp" "$dest"
}

# Repository indexes are metadata, not cacheable inputs: a stale cached index
# would misdescribe the packages apk actually fetched. Always re-fetch them.
fetch_fresh() {
    url="$1"; dest="$2"
    rm -f "$dest" "$dest.tmp"
    fetch "$url" "$dest"
}

sha256_file() { sha256sum "$1" | awk '{print $1}'; }

verify_sha256() {
    file="$1"; expected="$2"; label="$3"
    actual="$(sha256_file "$file")"
    [ "$actual" = "$expected" ] \
        || die "$label sha256 mismatch: expected $expected, got $actual"
}

# Run a command inside the target rootfs. Cross-arch execution relies on
# qemu-user binfmt_misc (flag F). We never fall back silently: if the target
# cannot execute, the caller decides whether that is fatal.
can_exec_in_root() {
    root="$1"
    if [ "$ARCH" = "$HOST_ARCH" ]; then return 0; fi
    chroot "$root" /bin/true >/dev/null 2>&1
}

run_in_root() {
    root="$1"; shift
    chroot "$root" "$@"
}

# Remove state that must not be frozen into an image. This is intentionally
# distro-agnostic; distro-specific callers remove their package caches first.
normalize_common() {
    local root="$1"
    rm -rf "$root/tmp"/* "$root/run"/* 2>/dev/null || true
    # /dev is mounted fresh by init-zero-install; never ship host/container nodes.
    if [ -d "$root/dev" ]; then
        find "$root/dev" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
    fi
    mkdir -p "$root/dev" "$root/proc" "$root/sys" "$root/tmp" "$root/run"
    chmod 1777 "$root/tmp"
    chmod 755 "$root/run"

    : > "$root/etc/machine-id"
    : > "$root/etc/resolv.conf"
    printf 'orchestella\n' > "$root/etc/hostname"
    printf '127.0.0.1\tlocalhost\n127.0.1.1\torchestella\n' > "$root/etc/hosts"

    rm -f "$root"/etc/ssh/ssh_host_* 2>/dev/null || true
    rm -f "$root"/root/.ash_history "$root"/root/.bash_history 2>/dev/null || true
    rm -rf "$root"/var/lib/systemd/random-seed 2>/dev/null || true

    install -m 0755 "$SELF_DIR/init-zero-install" "$root/init-zero-install"
    install -m 0755 "$SELF_DIR/ready.sh" "$root/ready.sh"
    cat > "$root/etc/profile.d/00-orchestella-locale.sh" <<'EOF'
# Pre-baked locale: musl/glibc both provide C.UTF-8 without extra data.
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
EOF
    chmod 0644 "$root/etc/profile.d/00-orchestella-locale.sh"
}

normalize_mtimes() {
    local root="$1"
    # -h touches symlinks themselves, not their targets. --clamp would keep
    # older package mtimes, but a single fixed timestamp is simpler to audit.
    find "$root" -xdev -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +
}

make_tar() {
    local root="$1" artifact="$2"
    # GNU tar reproducibility recipe: stable order, no atime/ctime, fixed
    # owner and mtime, gzip without name/timestamp.
    tar --sort=name --format=posix \
        --pax-option=delete=atime,delete=ctime \
        --mtime="@$SOURCE_DATE_EPOCH" \
        --owner=0 --group=0 --numeric-owner \
        -cf - -C "$root" . \
        | gzip -9n > "$artifact"
    sha256_file "$artifact" > "$artifact.sha256"
}

make_ext4() {
    local root="$1" artifact="$2"
    command -v mke2fs >/dev/null 2>&1 || die "mke2fs not found (install e2fsprogs)"
    # A fixed UUID and hash seed remove the two usual ext4 random inputs. The
    # superblock timestamps follow SOURCE_DATE_EPOCH in e2fsprogs >= 1.45.
    local image="$artifact"
    local bytes size
    rm -f "$image"
    bytes="$(du -sb "$root" | awk '{print $1}')"
    # 1.35x headroom plus 16 MiB for metadata and journal.
    size=$(( bytes * 135 / 100 + 16 * 1024 * 1024 ))
    size=$(( (size + 1048575) / 1048576 * 1048576 ))
    truncate -s "$size" "$image"
    SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" mke2fs -q -t ext4 -F \
        -U 00000000-0000-4000-8000-000000000000 \
        -E "root_owner=0:0,hash_seed=00000000-0000-4000-8000-000000000000,lazy_itable_init=0,lazy_journal_init=0" \
        -d "$root" "$image"
    # mke2fs stores a write time in the superblock on some versions; verify with
    # a second run in the report rather than claiming determinism here.
    sha256_file "$image" > "$artifact.sha256"
}

# ---------------------------------------------------------------- Alpine

build_alpine() {
    base_sha=""
    case "$ARCH" in
        aarch64) base_sha="$ALPINE_BASE_SHA256_AARCH64" ;;
        x86_64)  base_sha="$ALPINE_BASE_SHA256_X86_64" ;;
    esac
    base_name="alpine-minirootfs-${ALPINE_VERSION}-${ARCH}.tar.gz"
    base_url="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/releases/${ARCH}/${base_name}"
    base_tar="$CACHE_DIR/$base_name"

    fetch "$base_url" "$base_tar"
    verify_sha256 "$base_tar" "$base_sha" "alpine minirootfs"

    apk_pkg="$CACHE_DIR/apk-tools-static-${APK_STATIC_VERSION}.apk"
    apk_url="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main/${HOST_ARCH}/apk-tools-static-${APK_STATIC_VERSION}.apk"
    fetch "$apk_url" "$apk_pkg"
    verify_sha256 "$apk_pkg" "$APK_STATIC_SHA256" "apk-tools-static"

    apk_host="$WORK_DIR/apk-host"
    rm -rf "$apk_host"; mkdir -p "$apk_host"
    tar -xf "$apk_pkg" -C "$apk_host" sbin/apk.static
    apk_static="$apk_host/sbin/apk.static"
    "$apk_static" --version >/dev/null

    root="$WORK_DIR/alpine-rootfs"
    rm -rf "$root"; mkdir -p "$root"
    tar -xzf "$base_tar" -C "$root"

    repo_file="$WORK_DIR/alpine-repositories"
    cat > "$repo_file" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/community
EOF

    pkgs="$PACKAGES"
    [ -n "$pkgs" ] || pkgs="$ALPINE_PACKAGES_DEFAULT"

    # Fetch the indexes immediately before and after the transaction. If the
    # repository rolled mid-build, the recorded index would not describe the
    # packages in the image; fail instead of hiding that.
    for repo in main community; do
        idx_url="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/${repo}/${ARCH}/APKINDEX.tar.gz"
        fetch_fresh "$idx_url" "$WORK_DIR/index-before-${repo}.tar.gz"
    done

    log "apk add ($ARCH): $pkgs"
    # --no-scripts is the documented cross-arch extraction path. It leaves
    # post-install triggers (certificate symlinks, ssh key generation) to us,
    # which is exactly what a frozen image needs.
    "$apk_static" --root "$root" --arch "$ARCH" \
        --repositories-file "$repo_file" \
        --keys-dir "$root/etc/apk/keys" \
        --no-scripts --no-cache --no-progress \
        add $pkgs

    # Build-time post-install work that must not happen on first boot.
    if can_exec_in_root "$root"; then
        log "update-ca-certificates (build time)"
        run_in_root "$root" /bin/sh -c 'update-ca-certificates >/dev/null 2>&1 || true'
    else
        warn "cannot execute $ARCH binaries on $HOST_ARCH; certificate symlinks left to package bundle"
    fi

    # Locale: musl has C.UTF-8 built in. musl-locales supplies definitions for
    # glibc-style names without generating a locale archive.
    printf 'C.UTF-8\n' > "$WORK_DIR/alpine-locales.txt"
    rm -rf "$root/var/cache/apk"/* "$root/var/log/apk.log" "$root/lib/apk/db/lock" 2>/dev/null || true
    normalize_common "$root"
    normalize_mtimes "$root"

    # Record and validate the repository state before creating any artifact.
    # If the index rolled mid-build, fail while there is still no artifact to
    # mistake for a valid build.
    indexes="$WORK_DIR/alpine-indexes.txt"
    : > "$indexes"
    for repo in main community; do
        idx_url="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_BRANCH}/${repo}/${ARCH}/APKINDEX.tar.gz"
        idx_after="$WORK_DIR/index-after-${repo}.tar.gz"
        fetch_fresh "$idx_url" "$idx_after"
        before_sha="$(sha256_file "$WORK_DIR/index-before-${repo}.tar.gz")"
        after_sha="$(sha256_file "$idx_after")"
        [ "$before_sha" = "$after_sha" ] \
            || die "Alpine $repo index changed during build ($before_sha -> $after_sha); rebuild against a stable mirror"
        printf '%s %s\n' "$idx_url" "$after_sha" >> "$indexes"
    done

    artifact="$OUT_DIR/alpine-${ALPINE_VERSION}-${ARCH}.tar.gz"
    make_tar "$root" "$artifact"
    if [ "$WITH_EXT4" -eq 1 ]; then
        make_ext4 "$root" "$OUT_DIR/alpine-${ALPINE_VERSION}-${ARCH}.ext4"
    fi

    artifacts=("$artifact")
    if [ "$WITH_EXT4" -eq 1 ]; then
        artifacts+=("$OUT_DIR/alpine-${ALPINE_VERSION}-${ARCH}.ext4")
    fi
    cp "$root/etc/ssl/certs/ca-certificates.crt" "$WORK_DIR/ca-certificates.crt"
    manifest_common alpine "$ALPINE_VERSION" musl "$base_url" "$base_sha" \
        "$(cat "$WORK_DIR/alpine-locales.txt")" "apk-tools-static ${APK_STATIC_VERSION}" \
        "$indexes" "${artifacts[@]}"
}

# ---------------------------------------------------------------- Debian

build_debian() {
    command -v mmdebstrap >/dev/null 2>&1 || die "mmdebstrap not found (apt-get install mmdebstrap)"
    deb_arch=arm64
    [ "$ARCH" = x86_64 ] && deb_arch=amd64
    if [ "$deb_arch" != "amd64" ] && [ "$HOST_ARCH" != "$ARCH" ]; then
        command -v qemu-aarch64-static >/dev/null 2>&1 \
            || die "qemu-aarch64-static not found (apt-get install qemu-user-static binfmt-support)"
        # A throwaway root is not available yet; verify binfmt with a known
        # aarch64 binary from the cache once the base is unpacked. Check the
        # interpreter registration early to fail with a clear message.
        if [ ! -r /proc/sys/fs/binfmt_misc/qemu-aarch64 ] 2>/dev/null; then
            warn "binfmt_misc qemu-aarch64 entry not visible; attempting build anyway"
        fi
    fi

    root="$WORK_DIR/debian-rootfs"
    rm -rf "$root"

    pkgs="$PACKAGES"
    [ -n "$pkgs" ] || pkgs="$DEBIAN_PACKAGES_DEFAULT"
    include_csv="$(printf '%s' "$pkgs" | tr ' ' ',')"

    release_url="${DEBIAN_MIRROR%/}/dists/${DEBIAN_SUITE}/Release"
    fetch_fresh "$release_url" "$WORK_DIR/debian-Release-before"

    log "mmdebstrap ($deb_arch): $include_csv"
    # qemu-user does not implement posix_spawn, and Python 3.13's subprocess
    # uses it by default. Without this, python3-minimal's py3compile postinst
    # fails with "cannot get content of python3-minimal" (rc 127). The knob is
    # read by subprocess before any postinst runs and does not alter the image.
    export _PYTHON_SUBPROCESS_USE_POSIX_SPAWN=0
    # --skip=mount keeps the builder usable in containers without CAP_SYS_ADMIN.
    # Package maintainer scripts that genuinely need /proc are expected to fail
    # loudly; the minbase + toolchain set does not.
    mmdebstrap --arch="$deb_arch" --variant=minbase --mode=root --skip=mount \
        --components=main --include="$include_csv" --format=dir \
        "$DEBIAN_SUITE" "$root" "$DEBIAN_MIRROR"

    can_exec_in_root "$root" || die "cannot execute $ARCH binaries on $HOST_ARCH"

    log "locale-gen (build time)"
    printf 'C.UTF-8 UTF-8\nen_US.UTF-8 UTF-8\n' > "$root/etc/locale.gen"
    run_in_root "$root" /usr/sbin/locale-gen
    printf 'LANG=C.UTF-8\nLC_ALL=C.UTF-8\n' > "$root/etc/default/locale"
    printf 'C.UTF-8\nen_US.UTF-8\n' > "$WORK_DIR/debian-locales.txt"

    # mmdebstrap already runs ca-certificates' postinst; assert the bundle.
    [ -s "$root/etc/ssl/certs/ca-certificates.crt" ] \
        || die "ca-certificates bundle missing after debootstrap"

    # Package caches, logs and machine-specific state must not be frozen.
    rm -rf "$root/var/lib/apt/lists"/* \
           "$root/var/cache/apt/archives"/*.deb \
           "$root/var/log/apt"/* \
           "$root/var/cache/ldconfig/aux-cache" 2>/dev/null || true
    rm -f "$root/var/log/dpkg.log" "$root/var/log/alternatives.log" \
          "$root/var/cache/debconf"/*.dat-old \
          "$root/var/lib/dpkg/lock" "$root/var/lib/dpkg/lock-frontend" \
          "$root/var/lib/apt/lists/lock" "$root/var/cache/apt/archives/lock" \
          2>/dev/null || true
    # Host keys are per-instance; generating them at build time would both
    # leak one identity into every instance and break reproducibility.
    rm -f "$root"/etc/ssh/ssh_host_*
    normalize_common "$root"
    normalize_mtimes "$root"

    fetch_fresh "$release_url" "$WORK_DIR/debian-Release-after"
    before_sha="$(sha256_file "$WORK_DIR/debian-Release-before")"
    release_sha="$(sha256_file "$WORK_DIR/debian-Release-after")"
    [ "$before_sha" = "$release_sha" ] \
        || die "Debian Release changed during build ($before_sha -> $release_sha); use an immutable snapshot"
    indexes="$WORK_DIR/debian-indexes.txt"
    printf '%s %s\n' "$release_url" "$release_sha" > "$indexes"

    artifact="$OUT_DIR/debian-${DEBIAN_SUITE}-${ARCH}.tar.gz"
    make_tar "$root" "$artifact"
    if [ "$WITH_EXT4" -eq 1 ]; then
        make_ext4 "$root" "$OUT_DIR/debian-${DEBIAN_SUITE}-${ARCH}.ext4"
    fi

    artifacts=("$artifact")
    if [ "$WITH_EXT4" -eq 1 ]; then
        artifacts+=("$OUT_DIR/debian-${DEBIAN_SUITE}-${ARCH}.ext4")
    fi
    cp "$root/etc/ssl/certs/ca-certificates.crt" "$WORK_DIR/ca-certificates.crt"
    manifest_common debian "$DEBIAN_SUITE" glibc "$DEBIAN_MIRROR" "$release_sha" \
        "$(cat "$WORK_DIR/debian-locales.txt" | tr '\n' ' ' | sed 's/ $//')" \
        "$(mmdebstrap --version 2>/dev/null | head -1)" \
        "$indexes" "${artifacts[@]}"
}

# ---------------------------------------------------------------- manifest

manifest_common() {
    distro="$1"; version="$2"; libc="$3"; base_url="$4"; base_sha="$5"
    locales="$6"; tool="$7"; repo_index="$8"; shift 8
    root="$WORK_DIR/${distro}-rootfs"

    elapsed=$(( $(date +%s) - T0 ))
    artifact_args=()
    for artifact in "$@"; do
        artifact_args+=(--artifact "$artifact")
    done
    python3 "$SELF_DIR/make-manifest.py" \
        --root "$root" \
        --distro "$distro" --version "$version" --arch "$ARCH" --libc "$libc" \
        --source-date-epoch "$SOURCE_DATE_EPOCH" \
        --started-at "$START_ISO" --duration-seconds "$elapsed" \
        --host "$(uname -srm)" --tool "$tool" \
        --base-url "$base_url" --base-sha256 "$base_sha" \
        --repository-index "$repo_index" \
        --locales "$locales" \
        "${artifact_args[@]}" \
        --out "$OUT_DIR/manifest.json"
    log "artifact: $*"
    log "manifest: $OUT_DIR/manifest.json"
}

case "$DISTRO" in
    alpine) build_alpine ;;
    debian) build_debian ;;
    *) die "unsupported distro: $DISTRO" ;;
esac

if [ "$KEEP_WORK" -eq 0 ]; then
    # Keep the cache (input hashes) and manifests; drop the extracted trees.
    rm -rf "$WORK_DIR/alpine-rootfs" "$WORK_DIR/debian-rootfs" "$WORK_DIR/apk-host"
fi

log "done in $(( $(date +%s) - T0 ))s"
