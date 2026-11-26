#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    printf 'usage: %s OUT_DIR\n' "$0" >&2
    exit 2
fi
if [[ $(id -u) != 0 ]]; then
    printf "The fixture builder needs permission to set guest file owners and create /dev/console.\n" >&2
    exit 1
fi

here=$(cd -- "$(dirname -- "$0")" && pwd)
repository=$(cd -- "$here/../../.." && pwd)
mkdir -p -- "$1"
out=$(cd -- "$1" && pwd)
cache="$out/alpine-cache"
mkdir -p -- "$cache"
work=$(mktemp -d /tmp/w405-desktop.XXXXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir -p -- "$work/native" "$work/guest"

python3 -B - "$here" "$cache" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from download_alpine import CDN, cache_file

cache = Path(sys.argv[2])
for arch, digest in {
    "x86_64": "56e2810205c17a5dff78cdd7b2c2af4ccdef16f18d765d5bb0f527438b136676",
    "aarch64": "3f1bd6811370264cc363bb991ff15ac67621b239ea6e25ff2eb22af3d0641473",
}.items():
    cache_file(cache, f"{CDN}/releases/{arch}/alpine-minirootfs-3.23.8-{arch}.tar.gz", digest)
PY
python3 -B "$here/download_alpine.py" "$cache" "$here/desktop-packages.lock"

tar -xf "$cache/alpine-minirootfs-3.23.8-x86_64.tar.gz" -C "$work/native"
tar -xf "$cache/alpine-minirootfs-3.23.8-aarch64.tar.gz" -C "$work/guest"
root="$work/guest"
native="$work/native"
apk=("$native/lib/libc.musl-x86_64.so.1" --library-path "$native/lib:$native/usr/lib"
    "$native/sbin/apk" --root "$root" --arch aarch64 --network=no --cache=no --logfile=no)
: > "$root/etc/apk/repositories"
packages=()
while read -r digest path; do
    [[ -z "$digest" || "$digest" = \#* ]] || packages+=("$cache/${path##*/}")
done < "$here/desktop-packages.lock"
"${apk[@]}" verify "${packages[@]}"
"${apk[@]}" add --no-scripts "${packages[@]}"
"${apk[@]}" info -vv | LC_ALL=C sort > "$out/desktop-packages.installed.txt"

printf 'desktop:x:1000:1000:Display benchmark:/home/desktop:/bin/sh\n' >> "$root/etc/passwd"
printf 'desktop:x:1000:\n' >> "$root/etc/group"
printf 'desktop:!:0:0:99999:7:::\n' >> "$root/etc/shadow"
install -d -o 1000 -g 1000 -m 0700 "$root/home/desktop" "$root/home/desktop/.cache"
install -d "$root/etc/w405/labwc" "$root/usr/local/bin" "$root/dev" "$root/proc" "$root/sys" "$root/run"
install -m 0755 "$here/desktop/w405-desktop" "$root/usr/local/bin/w405-desktop"
install -m 0644 "$here/desktop/labwc-rc.xml" "$root/etc/w405/labwc/rc.xml"
install -m 0644 "$here/desktop/wayvnc.conf" "$root/etc/w405/wayvnc.conf"
install -m 0755 "$repository/guest-assets/display/display-relay.py" "$root/usr/local/bin/w405-display-relay.py"
[[ -c "$root/dev/console" ]] || mknod -m 0600 "$root/dev/console" c 5 1
[[ -c "$root/dev/null" ]] || mknod -m 0666 "$root/dev/null" c 1 3

(
    cd -- "$root"
    find . -exec touch -h -d '@0' {} +
    find . -print0 | LC_ALL=C sort -z | cpio --null -o --format=newc --reproducible --quiet
) | gzip -n -3 > "$work/desktop-base.cpio.gz"
cp -- "$work/desktop-base.cpio.gz" "$out/desktop-base.cpio.gz"
(cd -- "$out" && sha256sum desktop-base.cpio.gz | tee desktop-base.sha256)
