#!/usr/bin/env bash
# Build the adb-shell channel's static arm64 binaries on a POSIX host (WSL).
#
# Why this script exists: the shell domain (uid 2000) has no cc and no
# python3, so every host-side helper the two adb-shell cases need must be
# cross-compiled here and pushed as a static ELF.  Toolchain order mirrors
# guest/build_guest.sh (device-b evidence):
#   aarch64-linux-gnu-gcc (glibc static) is the ONLY supported path.
#   NDK/bionic static is rejected by this project's exec chain: bionic ELFs
#   carry BTI/PAC GNU_PROPERTY notes and the previous round proved only a
#   glibc static build with ZERO GNU_PROPERTY notes survives (the guest init
#   fix chain: -13 EACCES -> -22 bionic ELF rejected -> glibc static PASS).
#   A static glibc launcher additionally removes all loader resolution risk
#   when exec'ing from /data/local/tmp.
#
# Artifacts (git-ignored, device/build/ is covered by the global build/ rule):
#   device/build/binaries/seccomp_launch   seccomp filter installer + exec
#   device/build/binaries/run_with_fd      socketpair fd wrapper (vec0 fd)
#   device/build/binaries/mconsole_probe   UML mconsole client (C mirror)
#   device/build/binaries/probe_shell      A-1/A-3 feasibility probe
#
# Usage:  wsl bash tools/research/uml-seccomp/device/build_adbshell_binaries.sh
# Deps:   sudo apt install gcc-aarch64-linux-gnu   (WSL, once)
# Verify: the script fails hard if any artifact carries GNU_PROPERTY notes
#         (readelf check embedded below; rebuilds once with
#         -mbranch-protection=none before giving up).

set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SECCOMP_ROOT=$(CDPATH= cd -- "$HERE/.." && pwd)
OUT=$HERE/build/binaries

CC=${CROSS_CC:-aarch64-linux-gnu-gcc}
READELF=${CROSS_READELF:-}
if [ -z "$READELF" ]; then
    if command -v aarch64-linux-gnu-readelf >/dev/null 2>&1; then
        READELF=aarch64-linux-gnu-readelf
    else
        READELF=readelf
    fi
fi

die() { printf 'BUILD_ADBSHELL_ERROR: %s\n' "$*" >&2; exit 2; }

command -v "$CC" >/dev/null 2>&1 \
    || die "cross compiler not found: $CC (install once in WSL: sudo apt install gcc-aarch64-linux-gnu)"
command -v "$READELF" >/dev/null 2>&1 || die "readelf not found: $READELF"

CFLAGS_COMMON="-static -O2 -Wall -Wextra -Wl,-z,max-page-size=4096"

# check_no_gnu_property <candidate> <source> <extra-flags>
# The UML arm64 loader story (guest init round) requires 0 GNU_PROPERTY notes;
# a bionic-style BTI/PAC note set must never ship in this channel's binaries.
check_no_gnu_property() {
    local bin=$1 src=$2 extra=$3
    if "$READELF" -n "$bin" 2>/dev/null | grep -q 'GNU_PROPERTY'; then
        if [ -z "$extra" ]; then
            say "note: GNU_PROPERTY found in $(basename "$bin"); rebuilding with -mbranch-protection=none"
            return 1
        fi
        die "$(basename "$bin") still carries GNU_PROPERTY notes after -mbranch-protection=none ($src)"
    fi
    return 0
}

build_one() {
    # build_one <source> <output-name>
    local src=$1 name=$2
    local tmp="$OUT/.tmp-$name"
    if "$CC" $CFLAGS_COMMON -o "$tmp" "$src"; then
        if check_no_gnu_property "$tmp" "$src" ""; then
            mv "$tmp" "$OUT/$name"
            return 0
        fi
    fi
    # Retry path: suppress arm64 BTI/PAC codegen + notes at the source.
    if "$CC" $CFLAGS_COMMON -mbranch-protection=none -o "$tmp" "$src" \
            && check_no_gnu_property "$tmp" "$src" "retry"; then
        mv "$tmp" "$OUT/$name"
        return 0
    fi
    rm -f "$tmp"
    die "cannot build $name from $src (see compiler output above)"
}

say() { printf '%s\n' "$*"; }

mkdir -p "$OUT"

# The two case helpers + the probe (sources live in device/ beside this script)
# and the proven seccomp_launch (source at the uml-seccomp root, unchanged).
build_one "$SECCOMP_ROOT/seccomp_launch.c" seccomp_launch
build_one "$HERE/run_with_fd.c"            run_with_fd
build_one "$HERE/mconsole_probe.c"         mconsole_probe
build_one "$HERE/probe_shell.c"            probe_shell

say ""
say "== artifacts =="
for f in seccomp_launch run_with_fd mconsole_probe probe_shell; do
    b="$OUT/$f"
    [ -f "$b" ] || die "expected artifact missing: $b"
    "$READELF" -h "$b" | grep -q 'Machine:.*AArch64' \
        || die "$f is not AArch64 (toolchain mismatch?)"
    "$READELF" -h "$b" | grep -q 'Type:.*EXEC' \
        || say "note: $f ELF type: $( "$READELF" -h "$b" | sed -n 's/Type: *//p' )"
    if "$READELF" -n "$b" | grep -q 'GNU_PROPERTY'; then
        die "$f carries GNU_PROPERTY notes (post-check)"
    fi
    printf '%-18s %8s bytes  sha256=%s\n' "$f" "$(wc -c < "$b")" \
        "$(sha256sum "$b" | cut -d' ' -f1)"
done

say ""
say "BUILD_OK dir=$OUT"
say "next: DEVICE_SERIAL=<serial> tools/research/uml-seccomp/device/run_device_matrix.sh --probe"
