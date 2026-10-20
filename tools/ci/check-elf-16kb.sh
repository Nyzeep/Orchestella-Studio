#!/usr/bin/env bash
# Shared AArch64 ELF / GNU_RELRO / 16 KB PT_LOAD check.
#
# Use for APK native libraries and for the QEMU product binaries:
#   READELF=/path/to/llvm-readelf bash tools/ci/check-elf-16kb.sh <file>...
set -uo pipefail

RE="${READELF:-llvm-readelf}"
if [ "$#" -eq 0 ]; then
  echo "usage: READELF=<readelf> $0 <elf>..." >&2
  exit 2
fi
if ! command -v "$RE" >/dev/null 2>&1; then
  echo "FAIL readelf not found: $RE"
  exit 1
fi

fail=0
for f in "$@"; do
  if [ ! -f "$f" ]; then
    echo "FAIL missing product $f"
    fail=1
    continue
  fi
  "$RE" -h "$f" | grep -Eq 'Class:[[:space:]]*ELF64' || { echo "FAIL class $f"; fail=1; }
  "$RE" -h "$f" | grep -Eq 'Machine:[[:space:]]*AArch64' || { echo "FAIL machine $f"; fail=1; }
  "$RE" -l "$f" | grep -q 'GNU_RELRO' || { echo "FAIL relro $f"; fail=1; }
  bad=0
  while read -r _ align; do
    case "$align" in
      0x*) if (( align < 16384 )); then bad=1; fi ;;
      *) bad=1 ;;
    esac
  done < <("$RE" -l "$f" | sed -n '/^Program Headers:/,/^ Section to Segment mapping:/p' | awk '$1=="LOAD" {print $1, $NF}')
  [ "$bad" -eq 0 ] || { echo "FAIL load_align $f"; fail=1; }
  echo "DT_NEEDED for $f:"
  "$RE" -d "$f" | grep NEEDED || true
done

if [ "$fail" -eq 0 ]; then
  echo "ELF_CLOSURE_16KB_OK"
  exit 0
fi
exit 1
