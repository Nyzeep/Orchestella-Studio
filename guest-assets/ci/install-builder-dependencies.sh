#!/usr/bin/env bash
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

SNAPSHOT="$(lock_value UBUNTU_SNAPSHOT)"
[[ "$SNAPSHOT" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || {
  printf 'invalid Ubuntu snapshot identifier: %s\n' "$SNAPSHOT" >&2
  exit 1
}

# The immutable manifest is expected to be the Ubuntu 24.04/noble amd64 image
# recorded in inputs.lock. Refuse a silently substituted base.
# shellcheck source=/dev/null
source /etc/os-release
[[ "${ID:-}" = ubuntu && "${VERSION_ID:-}" = 24.04 \
   && "${VERSION_CODENAME:-}" = noble ]] || {
  printf 'unexpected builder base: ID=%s VERSION_ID=%s CODENAME=%s\n' \
    "${ID:-missing}" "${VERSION_ID:-missing}" "${VERSION_CODENAME:-missing}" >&2
  exit 1
}
[[ "$(dpkg --print-architecture)" = amd64 ]] || {
  printf 'builder base is not amd64\n' >&2
  exit 1
}
[[ -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ]] || {
  printf 'Ubuntu archive keyring is missing\n' >&2
  exit 1
}

# The image contains Ubuntu's archive keyring. APT validates the signed Release,
# index hashes and package hashes before any downloaded package is installed.
rm -f /etc/apt/sources.list
find /etc/apt/sources.list.d -mindepth 1 -maxdepth 1 -type f -delete
cat > /etc/apt/sources.list.d/droidlayer.sources <<EOF
Types: deb
URIs: https://snapshot.ubuntu.com/ubuntu/$SNAPSHOT/
Suites: noble noble-updates noble-security
Components: main universe
Architectures: amd64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

export DEBIAN_FRONTEND=noninteractive
apt-get -o Acquire::Check-Valid-Until=false update
apt-get install --yes --no-install-recommends \
  bc \
  binutils-aarch64-linux-gnu \
  bison \
  build-essential \
  ca-certificates \
  cpio \
  curl \
  diffutils \
  e2fsprogs \
  file \
  findutils \
  flex \
  gawk \
  gcc-aarch64-linux-gnu \
  gzip \
  libelf-dev \
  libssl-dev \
  openjdk-17-jdk-headless \
  qemu-system-arm \
  rsync \
  shellcheck \
  xz-utils

dpkg-query --show --showformat='${binary:Package}=${Version}\n' \
  bc binutils-aarch64-linux-gnu bison build-essential ca-certificates cpio \
  curl diffutils e2fsprogs file findutils flex gawk gcc-aarch64-linux-gnu \
  gzip libelf-dev libssl-dev openjdk-17-jdk-headless \
  qemu-system-arm rsync shellcheck xz-utils \
  | LC_ALL=C sort > /tmp/droidlayer-builder-packages.txt
