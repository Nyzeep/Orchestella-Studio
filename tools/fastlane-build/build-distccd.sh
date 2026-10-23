#!/usr/bin/env bash
# Reproducible Android arm64 distccd build (zero-device).
#
# Output: build/fastlane-distccd/bin/distccd plus SHA256SUMS, SBOM and build
# info. The binary is never committed. Override the output with
# FASTLANE_DISTCCD_OUT (the CI reproducibility job uses a second directory).
#
# Requires: Android NDK 27.0.12077973, clang/lld from that NDK, make, patch,
# curl, sha256sum and python3. It never calls adb, gradle or a device.
set -euo pipefail
umask 022
export LC_ALL=C
export TZ=UTC
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1792540800}"

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
VERSION="3.4"
PACKAGE="distcc"
TARBALL="distcc-${VERSION}.tar.gz"
URL="https://github.com/distcc/distcc/releases/download/v${VERSION}/${TARBALL}"
SOURCE_SHA256="2b99edda9dad9dbf283933a02eace6de7423fe5650daa4a728c950e5cd37bd7d"
NDK_REVISION="27.0.12077973"
OUT="${FASTLANE_DISTCCD_OUT:-${ROOT}/build/fastlane-distccd}"
CACHE="${FASTLANE_DISTCCD_CACHE:-${ROOT}/build/fastlane-distccd-cache}"
# configure/make run from the source directory; keep output paths absolute.
case "$OUT" in /*) ;; *) OUT="${ROOT}/${OUT}" ;; esac
case "$CACHE" in /*) ;; *) CACHE="${ROOT}/${CACHE}" ;; esac
JOBS="${FASTLANE_DISTCCD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

fail() {
  echo "FAIL $*" >&2
  exit 1
}

for tool in curl tar patch make sha256sum python3; do
  command -v "$tool" >/dev/null 2>&1 || fail "missing build tool: $tool"
done

find_ndk() {
  local candidate
  for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_HOME:-}/ndk/${NDK_REVISION}" \
    "${ANDROID_SDK_ROOT:-}/ndk/${NDK_REVISION}" \
    "${HOME}/Android/Sdk/ndk/${NDK_REVISION}"; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate/source.properties" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

NDK="$(find_ndk || true)"
[ -n "$NDK" ] || fail "Android NDK ${NDK_REVISION} not found (set ANDROID_NDK_HOME)"
NDK_FOUND="$(sed -nE 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK/source.properties" | head -1)"
if [ "${FASTLANE_ALLOW_ANY_NDK:-0}" != "1" ] && [ "$NDK_FOUND" != "$NDK_REVISION" ]; then
  fail "NDK revision is '${NDK_FOUND}', expected ${NDK_REVISION} (set FASTLANE_ALLOW_ANY_NDK=1 only for local experiments)"
fi

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *) fail "unsupported build host: $(uname -s)" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/${HOST_TAG}"
CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
for tool in "$CC" "$AR" "$RANLIB" "$STRIP"; do
  [ -x "$tool" ] || fail "NDK tool missing: $(basename "$tool")"
done

mkdir -p "$CACHE" "$OUT"
if [ ! -f "$CACHE/$TARBALL" ]; then
  echo "fetch $PACKAGE $VERSION source"
  curl -fsSL -o "$CACHE/$TARBALL.tmp" "$URL"
  mv "$CACHE/$TARBALL.tmp" "$CACHE/$TARBALL"
fi
echo "${SOURCE_SHA256}  ${CACHE}/${TARBALL}" | sha256sum -c - >/dev/null

rm -rf "$OUT/src" "$OUT/bin"
mkdir -p "$OUT/src" "$OUT/bin"
tar xzf "$CACHE/$TARBALL" -C "$OUT/src"
SRC="$OUT/src/${PACKAGE}-${VERSION}"
[ -d "$SRC" ] || fail "unexpected source layout under $OUT/src"

PATCH="$ROOT/tools/fastlane-build/distcc-reproducible.patch"
[ -f "$PATCH" ] || fail "missing $PATCH"
patch -p1 -d "$SRC" < "$PATCH" >/dev/null

export CC AR RANLIB STRIP
export CFLAGS="-O2 -fPIE -fno-emulated-tls -g0"
export LDFLAGS="-static -pie -Wl,-z,max-page-size=16384 -Wl,--undefined=orchestella_tls_align"
# distcc 3.4 assumes glibc for these two probes. Bionic has no getloadavg at
# API 26; a missing value is already handled by dcc_getloadavg(). C99
# vsnprintf is present in Bionic, so the bundled fallback must not be linked
# (it would duplicate libc symbols in a static link).
export ac_cv_func_getloadavg=no
export rsync_cv_HAVE_C99_VSNPRINTF=yes

CONFIGURE_ARGS=(
  --host=aarch64-linux-android
  --build="${FASTLANE_DISTCCD_BUILD:-x86_64-pc-linux-gnu}"
  --disable-pump-mode
  --without-avahi
  --with-included-popt
  --without-libiberty
  --disable-Werror
)
(
  cd "$SRC"
  ./configure "${CONFIGURE_ARGS[@]}" >"$OUT/configure.log" 2>&1
)

"$CC" $CFLAGS -c -o "$SRC/tls-align.o" "$ROOT/tools/fastlane-build/distcc-tls-align.c"
(
  cd "$SRC"
  make -j"$JOBS" distccd LIBS="tls-align.o" >"$OUT/build.log" 2>&1
  "$STRIP" --strip-all distccd
)

install -m 0755 "$SRC/distccd" "$OUT/bin/distccd"
BIN_SHA256="$(sha256sum "$OUT/bin/distccd" | awk '{print $1}')"
printf '%s  bin/distccd\n' "$BIN_SHA256" > "$OUT/SHA256SUMS"

python3 - "$ROOT/tools/fastlane-build/distccd-sbom.template.json" \
  "$OUT/SBOM.spdx.json" "$SOURCE_SHA256" "$BIN_SHA256" <<'PY'
import json
import sys
from pathlib import Path

template, destination, source_sha, binary_sha = sys.argv[1:5]
document = json.loads(Path(template).read_text(encoding="utf-8"))
text = json.dumps(document)
text = text.replace("@SOURCE_SHA256@", source_sha).replace("@BINARY_SHA256@", binary_sha)
Path(destination).write_text(json.dumps(json.loads(text), indent=2) + "\n", encoding="utf-8")
PY

PATCH_SHA256="$(sha256sum "$PATCH" | awk '{print $1}')"
cat > "$OUT/BUILD-INFO.txt" <<EOF
name=${PACKAGE}d
version=${VERSION}
source_url=${URL}
source_sha256=${SOURCE_SHA256}
patch=distcc-reproducible.patch
patch_sha256=${PATCH_SHA256}
ndk_revision=${NDK_FOUND}
host_tag=${HOST_TAG}
cc=aarch64-linux-android26-clang
cflags=${CFLAGS}
ldflags=${LDFLAGS}
configure=${CONFIGURE_ARGS[*]}
binary_sha256=${BIN_SHA256}
binary_bytes=$(wc -c < "$OUT/bin/distccd" | tr -d ' ')
EOF

rm -f "$OUT/configure.log" "$OUT/build.log"
echo "DISTCCD_OK version=${VERSION} sha256=${BIN_SHA256} bytes=$(wc -c < "$OUT/bin/distccd" | tr -d ' ')"
