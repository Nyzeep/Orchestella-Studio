#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    printf 'usage: %s OUT_DIR\n' "$0" >&2
    exit 2
fi
here=$(cd -- "$(dirname -- "$0")" && pwd)
mkdir -p -- "$1"
out=$(cd -- "$1" && pwd)
cache="$out/alpine-cache"
mkdir -p -- "$cache"
work=$(mktemp -d /tmp/w405-clients.XXXXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir -p -- "$work/scanner" "$work/sysroot" "$work/protocols"

python3 -B "$here/download_alpine.py" "$cache" "$here/desktop-sdk.lock"
while read -r digest path; do
    if [[ -n "$digest" && "$digest" != \#* ]]; then
        tar --ignore-zeros --warning=no-unknown-keyword -xf "$cache/${path##*/}" -C "$work/sysroot"
    fi
done < "$here/desktop-sdk.lock"

python3 -B - "$here" "$cache" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from download_alpine import cache_file

cache = Path(sys.argv[2])
cache_file(
    cache,
    "https://archive.ubuntu.com/ubuntu/pool/main/w/wayland/libwayland-bin_1.22.0-2.1build1_amd64.deb",
    "9562788fd1d795f703bac4c50187b08071521d58c113b18d598df82e0ebe7ff6",
)
base = "https://raw.githubusercontent.com/swaywm/wlr-protocols/b010a03648b88d143236de193bddbfea0c08bc84/unstable"
for name, digest in {
    "wlr-screencopy-unstable-v1": "0f50f6187965b02782a12ba346ad950d6031454f6af6e1ac5b341a279ef16d56",
    "wlr-virtual-pointer-unstable-v1": "3ff6d540be0bc5228195bf072bde42117ea17945a5c2061add5d3cf97d6bb524",
}.items():
    cache_file(cache, f"{base}/{name}.xml", digest)
PY

dpkg-deb -x "$cache/libwayland-bin_1.22.0-2.1build1_amd64.deb" "$work/scanner"
scanner="$work/scanner/usr/bin/wayland-scanner"
for protocol in wlr-screencopy-unstable-v1 wlr-virtual-pointer-unstable-v1; do
    "$scanner" client-header "$cache/$protocol.xml" "$work/protocols/$protocol-client-protocol.h"
    "$scanner" private-code "$cache/$protocol.xml" "$work/protocols/$protocol-protocol.c"
done

cc=${W405_AARCH64_CC:-aarch64-linux-gnu-gcc}
gcc_headers=$("$cc" -print-file-name=include)
sys="$work/sysroot"
build_client() {
    local program=$1 protocol=$2
    "$cc" -std=c11 -O2 -Wall -Wextra -Werror -nostdinc -nostdlib -no-pie \
        -isystem "$gcc_headers" -isystem "$sys/usr/include" -I "$work/protocols" \
        -Wl,--dynamic-linker=/lib/ld-musl-aarch64.so.1 -Wl,--allow-shlib-undefined \
        "$sys/usr/lib/crt1.o" "$sys/usr/lib/crti.o" "$here/$program.c" \
        "$work/protocols/$protocol-protocol.c" \
        -L "$sys/usr/lib" -L "$sys/lib" -lwayland-client -lc "$sys/usr/lib/crtn.o" \
        -o "$out/w405-$program"
    readelf -W -h -l -d "$out/w405-$program" | grep -E 'AArch64|interpreter|NEEDED'
}
build_client capture wlr-screencopy-unstable-v1
build_client drag wlr-virtual-pointer-unstable-v1
