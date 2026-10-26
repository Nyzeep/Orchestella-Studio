#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Native-lane rootfs builder. Fetch, verify, unpack and populate the Alpine
# rootfs that the `proot` and `bare` columns compile inside.
#
#   prepare_rootfs.sh fetch                  # download + verify (any host)
#   prepare_rootfs.sh plan                   # print the device command list
#   prepare_rootfs.sh all --where device --adb adb
#   prepare_rootfs.sh all --where host       # Linux host with apk / apk.static
#   prepare_rootfs.sh status
#
# Why the split exists: the maintainer's workstation is Windows, and Git Bash
# cannot create symlinks (minirootfs is full of them) or device nodes. So the
# host stage stops at "download and verify"; unpacking and populating happen on
# the device, where the binaries are aarch64 anyway and no emulation is needed.
#
# Nothing third-party is committed: only this script and tools.lock.json.
set -euo pipefail

KIT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
LOCK="$KIT_DIR/tools.lock.json"
CACHE="$KIT_DIR/cache"
BIN_DIR="$KIT_DIR/bin"
ROOTFS="$KIT_DIR/rootfs"
PROFILE="match22"
WHERE="host"
ADB="${ADB:-adb}"
DEVICE_ROOT="/data/local/tmp/native-lane"

PY=$(command -v python3 || command -v python || true)

log() { printf '[native-lane] %s\n' "$*"; }
die() { printf '[native-lane] ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) HOST_OS=windows ;;
  Linux) HOST_OS=linux ;;
  Darwin) HOST_OS=darwin ;;
  *) HOST_OS=other ;;
esac

lock_get() { # lock_get <dotted.path>
  [ -n "$PY" ] || die "python is required to read tools.lock.json"
  "$PY" - "$LOCK" "$1" <<'PY'
import json, sys
node = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    node = node[part]
print(node)
PY
}

digest_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    "$PY" -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  fi
}

# ---------------------------------------------------------------- fetch stage

fetch_one() { # fetch_one <artifact-name> <cache-filename>
  local name="$1" filename="$2"
  local url sha want actual target tmp
  url=$(lock_get "artifacts.$name.url")
  sha=$(lock_get "artifacts.$name.sha256")
  want=$(lock_get "artifacts.$name.bytes")
  target="$CACHE/$filename"
  mkdir -p "$CACHE"

  if [ -f "$target" ]; then
    actual=$(digest_of "$target")
    if [ "$actual" = "$sha" ]; then
      log "$name: cache hit, digest verified"
      return 0
    fi
    log "$name: cached file does not match the lock, re-downloading"
    rm -f "$target"
  fi

  need curl
  tmp="$target.part"
  log "$name: downloading $url"
  curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$url" \
    || die "$name: download failed (check network or re-pin the lock)"
  actual=$(digest_of "$tmp")
  if [ "$actual" != "$sha" ]; then
    rm -f "$tmp"
    die "$name: sha256 mismatch
  lock:  $sha
  file:  $actual
Refusing to use an unverified binary. Re-pin tools.lock.json deliberately if the
upstream artifact really changed."
  fi
  mv "$tmp" "$target"
  local size
  size=$(wc -c <"$target" | tr -d ' ')
  log "$name: verified $size B (lock says $want B)"
}

stage_fetch() {
  fetch_one alpine-minirootfs alpine-minirootfs.tar.gz
  fetch_one proot-static proot-static.apk

  mkdir -p "$BIN_DIR"
  local apk="$CACHE/proot-static.apk"
  local member
  member=$(lock_get "artifacts.proot-static.member")
  log "extracting $member"
  rm -rf "$BIN_DIR/usr" "$BIN_DIR/proot-static" "$BIN_DIR/proot.static"
  tar -xzf "$apk" -C "$BIN_DIR" --strip-components=2 "$member" 2>/dev/null \
    || die "could not extract $member from $apk"
  [ -f "$BIN_DIR/proot.static" ] || die "extraction produced no proot binary"
  mv "$BIN_DIR/proot.static" "$BIN_DIR/proot-static"
  chmod +x "$BIN_DIR/proot-static"
  log "proot-static ready: $BIN_DIR/proot-static"
  log "check it with: file $BIN_DIR/proot-static  (expect: ARM aarch64, statically linked)"
}

# -------------------------------------------------------------- unpack stage

stage_extract() {
  local tarball="$CACHE/alpine-minirootfs.tar.gz"
  [ -f "$tarball" ] || die "run 'fetch' first"
  case "$HOST_OS" in
    windows)
      die "this host cannot unpack the rootfs: minirootfs contains symlinks and
Git Bash/Windows refuses to create them without elevated privileges.
Use: prepare_rootfs.sh plan   -> print the device command list, or
     prepare_rootfs.sh all --where device --adb <path>"
      ;;
  esac
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  log "unpacking minirootfs into $ROOTFS (excluding ./dev, which is bind-mounted)"
  tar -xzf "$tarball" -C "$ROOTFS" --exclude='./dev/*' --exclude='dev/*'
  mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/tmp"
  chmod 1777 "$ROOTFS/tmp"
  write_provenance
}

write_provenance() {
  local sha version
  sha=$(lock_get "artifacts.alpine-minirootfs.sha256")
  version=$(basename "$(lock_get "artifacts.alpine-minirootfs.url")" .tar.gz)
  cat >"$ROOTFS/.native-lane-rootfs.json" <<EOF
{
  "schema_version": 1,
  "base": "$version",
  "base_sha256": "$sha",
  "profile": "$PROFILE",
  "unpacked_on": "$HOST_OS",
  "note": "/dev is bind-mounted at run time; the tree deliberately has no device nodes."
}
EOF
  log "provenance written to $ROOTFS/.native-lane-rootfs.json"
}

# ------------------------------------------------------------ populate stage

repos_for_profile() {
  "$PY" - "$LOCK" "$PROFILE" <<'PY'
import json, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
print(" ".join(lock["profiles"][sys.argv[2]]["repositories"]))
PY
}

packages_for_profile() {
  "$PY" - "$LOCK" "$PROFILE" <<'PY'
import json, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
print(" ".join(lock["profiles"][sys.argv[2]]["packages"]))
PY
}

stage_populate_host() {
  [ -d "$ROOTFS/etc/apk" ] || die "run 'extract' first"
  local apk_bin=""
  for candidate in apk.static apk; do
    if command -v "$candidate" >/dev/null 2>&1; then apk_bin="$candidate"; break; fi
  done
  [ -n "$apk_bin" ] || die "no apk / apk.static on PATH. On a Windows host use
'--where device'; on a Linux host install apk-tools-static."
  local repos repo_flags=""
  repos=$(repos_for_profile)
  for repo in $repos; do repo_flags="$repo_flags --repository $repo"; done
  # shellcheck disable=SC2086
  log "populate ($PROFILE) via $apk_bin: $(packages_for_profile)"
  # shellcheck disable=SC2086
  "$apk_bin" --root "$ROOTFS" --arch aarch64 --no-cache --no-scripts \
    --allow-untrusted $repo_flags add $(packages_for_profile) \
    || die "apk populate failed. The device path is the supported one; see 'plan'."
  write_provenance
}

stage_populate_device() {
  need "$ADB"
  local repos repo_args="" pkgs
  repos=$(repos_for_profile)
  for repo in $repos; do repo_args="$repo_args --repository $repo"; done
  pkgs=$(packages_for_profile)
  log "populate on device ($PROFILE): $pkgs"
  # shellcheck disable=SC2086
  "$ADB" shell "$DEVICE_ROOT/bin/proot-static -r $DEVICE_ROOT/rootfs -b /dev -b /proc \
    -w /root /sbin/apk add --no-cache $repo_args $pkgs" \
    || die "device apk add failed; the device needs network for this step"
  log "verifying on device"
  "$ADB" shell "$DEVICE_ROOT/bin/proot-static -r $DEVICE_ROOT/rootfs -b /dev -b /proc \
    -w /root $(lock_get "profiles.$PROFILE.clang_binary") --version | head -1"
}

stage_push_device() {
  need "$ADB"
  "$ADB" shell "mkdir -p $DEVICE_ROOT/cache $DEVICE_ROOT/bin $DEVICE_ROOT/work"
  "$ADB" push "$CACHE/proot-static.apk" "$DEVICE_ROOT/cache/" >/dev/null
  "$ADB" push "$CACHE/alpine-minirootfs.tar.gz" "$DEVICE_ROOT/cache/" >/dev/null
  "$ADB" push "$KIT_DIR/fixtures/fixture.i" "$DEVICE_ROOT/work/" >/dev/null
  "$ADB" push "$BIN_DIR/proot-static" "$DEVICE_ROOT/bin/" >/dev/null
  "$ADB" shell "chmod 755 $DEVICE_ROOT/bin/proot-static"
  log "pushed cache, fixture and proot-static"
}

stage_extract_device() {
  need "$ADB"
  "$ADB" shell "cd $DEVICE_ROOT && rm -rf rootfs && mkdir -p rootfs && \
    tar -xzf cache/alpine-minirootfs.tar.gz -C rootfs --exclude='./dev/*' && \
    mkdir -p rootfs/dev rootfs/proc rootfs/sys rootfs/tmp && chmod 1777 rootfs/tmp" \
    || die "device-side unpack failed"
  log "rootfs unpacked on device at $DEVICE_ROOT/rootfs"
}

# ------------------------------------------------------------------ plan mode

stage_plan() {
  local repos repo_args="" pkgs clang_bin
  repos=$(repos_for_profile)
  for repo in $repos; do repo_args="$repo_args --repository $repo"; done
  pkgs=$(packages_for_profile)
  clang_bin=$(lock_get "profiles.$PROFILE.clang_binary")

  cat <<EOF
# Native-lane rootfs: the exact device sequence (profile: $PROFILE)
# Run from tools/native-lane/ (the push sources are relative to it).
# Replace 'adb -s <serial>' with your device; serials never belong in the repo.

adb shell 'mkdir -p $DEVICE_ROOT/cache $DEVICE_ROOT/bin $DEVICE_ROOT/work'
adb push cache/proot-static.apk          $DEVICE_ROOT/cache/
adb push cache/alpine-minirootfs.tar.gz  $DEVICE_ROOT/cache/
adb push bin/proot-static                $DEVICE_ROOT/bin/
adb push fixtures/fixture.i              $DEVICE_ROOT/work/
adb shell 'chmod 755 $DEVICE_ROOT/bin/proot-static'

# unpack without ./dev: /dev is bind-mounted at run time, so no mknod and no root
adb shell "cd $DEVICE_ROOT && rm -rf rootfs && mkdir -p rootfs && \\
  tar -xzf cache/alpine-minirootfs.tar.gz -C rootfs --exclude='./dev/*' && \\
  mkdir -p rootfs/dev rootfs/proc rootfs/sys rootfs/tmp && chmod 1777 rootfs/tmp"

# populate (needs network on the device)
adb shell "$DEVICE_ROOT/bin/proot-static -r $DEVICE_ROOT/rootfs -b /dev -b /proc \\
  -w /root /sbin/apk add --no-cache$repo_args $pkgs"

# proof the rootfs is runnable, and the version the columns will actually use
adb shell "$DEVICE_ROOT/bin/proot-static -r $DEVICE_ROOT/rootfs -b /dev -b /proc \\
  -w /root $clang_bin --version | head -1"

# then, one command for the whole measurement:
python tools/native-lane/measure.py --device --adb adb --regen-dot-i \\
  --rootfs $DEVICE_ROOT/rootfs --work $DEVICE_ROOT/work
EOF
}

stage_status() {
  printf 'host os      : %s\n' "$HOST_OS"
  printf 'profile      : %s\n' "$PROFILE"
  for f in "$CACHE/alpine-minirootfs.tar.gz" "$CACHE/proot-static.apk" "$BIN_DIR/proot-static"; do
    if [ -f "$f" ]; then printf 'present      : %s (%s B)\n' "$f" "$(wc -c <"$f" | tr -d ' ')"
    else printf 'missing      : %s\n' "$f"; fi
  done
  if [ -d "$ROOTFS" ]; then
    printf 'rootfs       : %s (%s entries)\n' "$ROOTFS" "$(find "$ROOTFS" -maxdepth 1 | wc -l | tr -d ' ')"
  else
    printf 'rootfs       : not unpacked\n'
  fi
}

# ---------------------------------------------------------------------- main

STAGE="${1:-status}"
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --where) WHERE="${2:?--where needs host|device}"; shift 2 ;;
    --adb) ADB="${2:?--adb needs a path}"; shift 2 ;;
    --profile) PROFILE="${2:?--profile needs a name}"; shift 2 ;;
    --rootfs) ROOTFS="${2:?--rootfs needs a path}"; shift 2 ;;
    -h | --help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

case "$PROFILE" in
  match22 | stable) ;;
  *) die "unknown profile: $PROFILE (match22 | stable)" ;;
esac

case "$STAGE" in
  fetch) stage_fetch ;;
  extract) stage_extract ;;
  populate)
    case "$WHERE" in
      host) stage_populate_host ;;
      device) stage_populate_device ;;
      *) die "--where must be host or device" ;;
    esac
    ;;
  plan) stage_plan ;;
  status) stage_status ;;
  all)
    case "$WHERE" in
      host)
        stage_fetch
        stage_extract
        stage_populate_host
        log "rootfs ready: $ROOTFS"
        ;;
      device)
        stage_fetch
        stage_push_device
        stage_extract_device
        stage_populate_device
        log "rootfs ready on device: $DEVICE_ROOT/rootfs"
        ;;
      *) die "--where must be host or device" ;;
    esac
    ;;
  *) die "unknown stage: $STAGE (fetch|extract|populate|plan|status|all)" ;;
esac
