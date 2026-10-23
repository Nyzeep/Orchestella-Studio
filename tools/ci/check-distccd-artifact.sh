#!/usr/bin/env bash
# Verify the Android arm64 distccd artifact: ELF class, 16 KB PT_LOAD, 64-byte
# PT_TLS, static closure, pinned SHA-256 and --version under qemu-user.
#
# Usage: bash tools/ci/check-distccd-artifact.sh [artifact-dir]
set -uo pipefail
export LC_ALL=C

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ART="${1:-${ROOT}/build/fastlane-distccd}"
BIN="$ART/bin/distccd"
NDK_REVISION="27.0.12077973"
MAX_BYTES=$((4 * 1024 * 1024))
fail=0

note() { echo "FAIL $*"; fail=1; }
ok() { echo "PASS $*"; }

[ -d "$ART" ] || { echo "FAIL artifact directory missing: $ART" >&2; exit 1; }
[ -f "$BIN" ] || { echo "FAIL missing $BIN" >&2; exit 1; }
[ -x "$BIN" ] || note "distccd is not executable"

command -v file >/dev/null 2>&1 || note "file(1) is required"
FILE_OUT="$(file -b "$BIN" 2>/dev/null || true)"
case "$FILE_OUT" in
  *"ELF 64-bit"*) ok "ELF64" ;;
  *) note "not ELF64: ${FILE_OUT:-unreadable}" ;;
esac
case "$FILE_OUT" in
  *"ARM aarch64"*) ok "AArch64" ;;
  *) note "not AArch64: ${FILE_OUT:-unreadable}" ;;
esac
case "$FILE_OUT" in
  *"statically linked"*) ok "static" ;;
  *) note "not statically linked: ${FILE_OUT:-unreadable}" ;;
esac

find_readelf() {
  local candidate
  for candidate in \
    "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
    "${ANDROID_HOME:-}/ndk/${NDK_REVISION}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
    "${ANDROID_SDK_ROOT:-}/ndk/${NDK_REVISION}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"; do
    if [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  command -v llvm-readelf 2>/dev/null || true
}
RE="${QEMU_READELF:-${READELF:-$(find_readelf)}}"
if [ -z "$RE" ] || ! command -v "$RE" >/dev/null 2>&1; then
  note "llvm-readelf not found (set READELF)"
else
  header="$("$RE" -h "$BIN" 2>&1 || true)"
  printf '%s\n' "$header" | grep -Eq 'Class:[[:space:]]*ELF64' && ok "readelf class" || note "readelf class"
  printf '%s\n' "$header" | grep -Eq 'Machine:[[:space:]]*AArch64' && ok "readelf machine" || note "readelf machine"
  printf '%s\n' "$header" | grep -Eq 'Type:[[:space:]]*(DYN|EXEC)' && ok "readelf type" || note "readelf type"

  load_count=0
  while read -r align; do
    [ -n "$align" ] || continue
    load_count=$((load_count + 1))
    case "$align" in
      0x*) (( align >= 16384 )) || note "PT_LOAD alignment $align < 16 KB" ;;
      *) note "unreadable PT_LOAD alignment: $align" ;;
    esac
  done < <("$RE" -l "$BIN" | awk '$1=="LOAD"{print $NF}')
  [ "$load_count" -gt 0 ] && ok "PT_LOAD segments=$load_count 16KB-aligned" || note "no PT_LOAD segments"

  tls_align="$("$RE" -l "$BIN" | awk '$1=="TLS"{print $NF; exit}')"
  if [ -z "$tls_align" ]; then
    note "no PT_TLS segment"
  else
    case "$tls_align" in
      0x*) if (( tls_align >= 64 )); then ok "PT_TLS alignment=$tls_align"; else note "PT_TLS alignment $tls_align < 64"; fi ;;
      *) note "unreadable PT_TLS alignment: $tls_align" ;;
    esac
  fi

  if "$RE" -l "$BIN" | awk '$1=="INTERP"{found=1} END{exit found?0:1}'; then
    note "static binary has PT_INTERP"
  else
    ok "no PT_INTERP"
  fi
  if "$RE" -d "$BIN" | grep -q 'NEEDED'; then
    note "static binary has DT_NEEDED entries"
  else
    ok "no DT_NEEDED"
  fi
fi

QEMU="${QEMU_AARCH64:-}"
if [ -z "$QEMU" ]; then
  QEMU="$(command -v qemu-aarch64-static || command -v qemu-aarch64 || true)"
fi
if [ -z "$QEMU" ] || [ ! -x "$QEMU" ]; then
  note "qemu-aarch64 not found (install qemu-user-static or set QEMU_AARCH64)"
else
  VERSION_OUT="$(timeout 30 "$QEMU" "$BIN" --version 2>&1 || true)"
  if printf '%s\n' "$VERSION_OUT" | head -1 | grep -q '^distccd 3\.4 '; then
    ok "distccd --version under qemu-user"
  else
    note "unexpected --version output: $(printf '%s\n' "$VERSION_OUT" | head -1)"
  fi
fi

if [ -f "$ART/SHA256SUMS" ]; then
  if (cd "$ART" && sha256sum -c SHA256SUMS >/dev/null 2>&1); then
    ok "SHA256SUMS"
  else
    note "SHA256SUMS mismatch"
  fi
else
  note "missing SHA256SUMS"
fi

size="$(wc -c < "$BIN" | tr -d ' ')"
if [ "$size" -le "$MAX_BYTES" ]; then
  ok "size=${size}B budget=${MAX_BYTES}B"
else
  note "size ${size}B exceeds ${MAX_BYTES}B budget"
fi

if [ ! -f "$ART/SBOM.spdx.json" ]; then
  note "missing SBOM.spdx.json"
else
  python3 - "$ART/SBOM.spdx.json" "$BIN" <<'PY' || fail=1
import hashlib
import json
import sys
from pathlib import Path

sbom = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
binary = Path(sys.argv[2]).read_bytes()
digest = hashlib.sha256(binary).hexdigest()
packages = {p.get("name"): p for p in sbom.get("packages", [])}
problems = []
for name in ("distcc-source", "distccd-android-arm64"):
    if name not in packages:
        problems.append(f"SBOM missing package {name}")
if "distccd-android-arm64" in packages:
    package = packages["distccd-android-arm64"]
    checksums = {c.get("checksumValue") for c in package.get("checksums", [])}
    if digest not in checksums:
        problems.append("SBOM binary checksum does not match bin/distccd")
    if package.get("licenseConcluded") != "GPL-2.0-or-later":
        problems.append("SBOM distccd license is not GPL-2.0-or-later")
if problems:
    for problem in problems:
        print(f"FAIL {problem}")
    sys.exit(1)
print("PASS SBOM")
PY
fi

if [ "$fail" -ne 0 ]; then
  echo "DISTCCD_ARTIFACT_FAILED"
  exit 1
fi
echo "DISTCCD_ARTIFACT_OK"
