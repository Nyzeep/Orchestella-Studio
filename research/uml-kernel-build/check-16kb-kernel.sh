#!/usr/bin/env bash
# 检查 UML 产物 ELF 的 AArch64/16KB PT_LOAD 对齐（复用 tools/ci/check-elf-16kb.sh 同口径）
# 用法: READELF=<llvm-readelf> bash check-16kb-kernel.sh <elf>...
set -uo pipefail
RE="${READELF:-llvm-readelf}"
fail=0
for f in "$@"; do
  [ -f "$f" ] || { echo "FAIL missing $f"; fail=1; continue; }
  "$RE" -h "$f" | grep -Eq 'Class:[[:space:]]*ELF64' || { echo "FAIL class $f"; fail=1; }
  "$RE" -h "$f" | grep -Eq 'Machine:[[:space:]]*AArch64' || { echo "FAIL machine $f"; fail=1; }
  bad=0
  while read -r _ align; do
    case "$align" in
      0x*) (( align < 16384 )) && bad=1 ;;
      *) bad=1 ;;
    esac
  done < <("$RE" -l "$f" | sed -n '/^Program Headers:/,/^ Section to Segment mapping:/p' | awk '$1=="LOAD" {print $1, $NF}')
  [ "$bad" -eq 0 ] || { echo "FAIL load_align $f"; fail=1; }
done
[ "$fail" -eq 0 ] && echo "ELF_16KB_OK all_targets_pass"
exit $fail
