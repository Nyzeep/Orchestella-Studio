#!/usr/bin/env bash
# Shared AArch64 ELF / GNU_RELRO / 16 KB PT_LOAD check.
#
# Use for APK native libraries and for the QEMU/UML product binaries:
#   READELF=/path/to/llvm-readelf bash tools/ci/check-elf-16kb.sh <file>...
#
# 16 KB page compatibility requires ELF64/AArch64, 16 KB-aligned PT_LOAD
# segments (p_align >= 0x4000), and page congruence (p_vaddr % 0x4000 ==
# p_offset % 0x4000). GNU_RELRO is checked when present (applicable to shared
# objects / dynamic PIEs) and enforced fail-closed when REQUIRE_RELRO=1.
set -uo pipefail

RE="${READELF:-llvm-readelf}"
REQUIRE_RELRO="${REQUIRE_RELRO:-0}"
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
  header="$("$RE" -h "$f" 2>&1 || true)"
  if ! printf '%s\n' "$header" | grep -Eq 'Class:[[:space:]]*ELF64'; then
    magic="$(od -An -tx1 -N4 "$f" 2>/dev/null | tr -d ' \n' || true)"
    detail="$(printf '%s\n' "$header" | head -1)"
    echo "FAIL class $f (readelf=$RE magic=${magic:-unreadable} first_line=${detail:-none})"
    fail=1
  fi
  printf '%s\n' "$header" | grep -Eq 'Machine:[[:space:]]*AArch64' || { echo "FAIL machine $f"; fail=1; }

  phdrs="$("$RE" -l "$f" 2>/dev/null || true)"
  is_dynamic=0
  if printf '%s\n' "$phdrs" | grep -qE '^[[:space:]]*DYNAMIC[[:space:]]'; then
    is_dynamic=1
  fi

  relro_line="$(printf '%s\n' "$phdrs" | awk '$1=="GNU_RELRO" {print $2, $3, $5, $6; exit}')"
  if [ -n "$relro_line" ]; then
    read -r _ r_vaddr r_filesz r_memsz <<< "$relro_line"
    r_lo=$((r_vaddr))
    r_fhi=$((r_lo + r_filesz))
    r_mhi=$((r_lo + r_memsz))
    relro_ok=0
    while read -r p_vaddr p_filesz p_memsz p_align; do
      l_lo=$((p_vaddr))
      l_fhi=$((l_lo + p_filesz))
      l_mhi_page=$(( ((l_lo + p_memsz + 4095) / 4096) * 4096 ))
      l_al=$((p_align))
      if (( r_lo >= l_lo && r_fhi <= l_fhi && r_mhi <= l_mhi_page && l_al >= 16384 )); then
        relro_ok=1
        break
      fi
    done < <(printf '%s\n' "$phdrs" | sed -n '/^Program Headers:/,/^ Section to Segment mapping:/p' | awk '$1=="LOAD" {print $3, $5, $6, $NF}')
    if [ "$relro_ok" = "1" ]; then
      echo "GNU_RELRO_OK $f"
    else
      echo "FAIL relro_16kb_coverage $f"
      fail=1
    fi
  else
    if [ "$REQUIRE_RELRO" = "1" ]; then
      echo "FAIL relro $f"
      fail=1
    elif [ "$is_dynamic" = "0" ]; then
      echo "GNU_RELRO=NOT_APPLICABLE(static-et-exec) $f"
    else
      echo "GNU_RELRO=ABSENT $f"
    fi
  fi

  bad=0
  load_count=0
  while read -r _ offset vaddr align; do
    load_count=$((load_count + 1))
    case "$align" in
      0x*)
        if (( align < 16384 )); then bad=1; fi
        if (( (vaddr % 16384) != (offset % 16384) )); then bad=1; fi
        ;;
      *) bad=1 ;;
    esac
  done < <(printf '%s\n' "$phdrs" | sed -n '/^Program Headers:/,/^ Section to Segment mapping:/p' | awk '$1=="LOAD" {print $1, $2, $3, $NF}')
  if [ "$load_count" -eq 0 ] || [ "$bad" -ne 0 ]; then
    echo "FAIL load_align $f"
    fail=1
  fi
  echo "DT_NEEDED for $f:"
  "$RE" -d "$f" | grep NEEDED || true
done

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
