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

# Optional bundled-DT_NEEDED closure. External/system libraries are outside the
# product payload and cannot be resolved from the build output; the assertion
# is that every NEEDED entry present beside a checked ELF was itself passed to
# this gate. APK packaging enables it; QEMU product binaries keep the legacy
# behaviour until their build output is enumerated.
if [ "${REQUIRE_BUNDLED_CLOSURE:-0}" = "1" ]; then
  declare -A checked=()
  for f in "$@"; do
    checked["$(basename "$f")"]=1
  done
  bundled=0
  external=0
  closure_fail=0
  for f in "$@"; do
    dir="$(dirname "$f")"
    while IFS= read -r needed; do
      [ -z "$needed" ] && continue
      if [ -f "$dir/$needed" ]; then
        if [ -n "${checked[$needed]:-}" ]; then
          bundled=$((bundled + 1))
        else
          echo "FAIL bundled dependency is present but was not checked: $f -> $needed"
          closure_fail=1
        fi
      else
        external=$((external + 1))
      fi
    done < <("$RE" -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
  done
  if [ "$closure_fail" -ne 0 ]; then
    fail=1
  else
    echo "BUNDLED_NEEDED_CLOSURE_OK resolved=$bundled external_not_asserted=$external"
  fi
else
  echo "BUNDLED_NEEDED_CLOSURE=NOT_ASSERTED"
fi

if [ "$fail" -eq 0 ]; then
  echo "ELF_CLOSURE_16KB_OK"
  exit 0
fi
exit 1
