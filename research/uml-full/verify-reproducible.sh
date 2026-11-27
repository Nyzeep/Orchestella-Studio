#!/usr/bin/env bash
# Dual-clean-path reproducibility verifier for full-smp-arm64 UML artifacts.
# Runs two independent builds in distinct clean directories (alpha vs beta)
# and localizes any byte discrepancy down to ELF section, cpio entry, or string.
set -euo pipefail

LC_ALL=C
export LC_ALL

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SRC=${SRC:-/root/linux-um-arm64-um-arm64-v0.3.0}
NDK=${NDK:-/root/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64}
REPRO_ROOT=${REPRO_ROOT:-/tmp/uml-full-repro}
JOBS=${JOBS:-$(nproc)}

WORK_A="$REPRO_ROOT/alpha-workspace"
WORK_B="$REPRO_ROOT/beta-longer-workspace-dir"
REPORT="$REPRO_ROOT/REPRO-REPORT.txt"

rm -rf "$WORK_A" "$WORK_B"
mkdir -p "$WORK_A" "$WORK_B"

# Hardlink-clone the immutable source tree into two distinct path prefixes.
cp -al "$SRC" "$WORK_A/src"
cp -al "$SRC" "$WORK_B/src"

run_one() {
  local label=$1
  local work=$2
  local log="$work/build.log"
  printf '=== Building %s in %s ===\n' "$label" "$work"
  SRC="$work/src" OUT="$work/out" NDK="$NDK" JOBS="$JOBS" \
    bash "$HERE/build-full.sh" >"$log" 2>&1 || {
      tail -n 60 "$log" >&2
      printf 'CLASSIFICATION=BUILD_FAILED build=%s\n' "$label" | tee "$REPORT"
      exit 1
    }
}

run_one "A" "$WORK_A"
run_one "B" "$WORK_B"

ART_A="$WORK_A/out/artifacts"
ART_B="$WORK_B/out/artifacts"
RE="$NDK/bin/llvm-readelf"
OBJCOPY="$NDK/bin/llvm-objcopy"

artifacts=(
  linux-full
  linux-full-noinitrd
  stub_exe
  initramfs-full.cpio.gz
  kernel-full.config
  kernel-full-noinitrd.config
)

mismatch=0
{
  printf 'REPRO_CHECK_TIMESTAMP=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'BUILD_A_DIR=%s\n' "$WORK_A"
  printf 'BUILD_B_DIR=%s\n' "$WORK_B"
  for item in "${artifacts[@]}"; do
    sha_a=$(sha256sum "$ART_A/$item" | awk '{print $1}')
    sha_b=$(sha256sum "$ART_B/$item" | awk '{print $1}')
    size_a=$(stat -c %s "$ART_A/$item")
    size_b=$(stat -c %s "$ART_B/$item")
    if [ "$sha_a" = "$sha_b" ]; then
      printf 'MATCH %s size=%s sha256=%s\n' "$item" "$size_a" "$sha_a"
    else
      mismatch=1
      printf 'DIFF  %s A(%s,%s) != B(%s,%s)\n' "$item" "$size_a" "$sha_a" "$size_b" "$sha_b"
      case "$item" in
        *.config)
          diff -u "$ART_A/$item" "$ART_B/$item" || true
          ;;
        *.cpio.gz)
          mkdir -p "$REPRO_ROOT/diff-$item/a" "$REPRO_ROOT/diff-$item/b"
          gzip -dc "$ART_A/$item" | (cd "$REPRO_ROOT/diff-$item/a" && cpio -idv 2>/dev/null || true)
          gzip -dc "$ART_B/$item" | (cd "$REPRO_ROOT/diff-$item/b" && cpio -idv 2>/dev/null || true)
          diff -rq "$REPRO_ROOT/diff-$item/a" "$REPRO_ROOT/diff-$item/b" || true
          ;;
        linux-*|stub_exe)
          echo "  -- ELF section diff for $item --"
          "$RE" -S -W "$ART_A/$item" > "$REPRO_ROOT/sec-a.txt" 2>/dev/null || true
          "$RE" -S -W "$ART_B/$item" > "$REPRO_ROOT/sec-b.txt" 2>/dev/null || true
          diff -u "$REPRO_ROOT/sec-a.txt" "$REPRO_ROOT/sec-b.txt" || true
          while read -r sec; do
            "$OBJCOPY" --dump-section "$sec=$REPRO_ROOT/s_a.bin" "$ART_A/$item" 2>/dev/null || continue
            "$OBJCOPY" --dump-section "$sec=$REPRO_ROOT/s_b.bin" "$ART_B/$item" 2>/dev/null || continue
            sa=$(sha256sum "$REPRO_ROOT/s_a.bin" | awk '{print $1}')
            sb=$(sha256sum "$REPRO_ROOT/s_b.bin" | awk '{print $1}')
            if [ "$sa" != "$sb" ]; then
              printf '    SECTION_DIFF %s A=%s B=%s\n' "$sec" "$sa" "$sb"
            fi
          done < <("$RE" -S -W "$ART_A/$item" 2>/dev/null | awk '/\[[ 0-9]+\]/ {name=$2; if (name ~ /^\./) print name}')
          strings "$ART_A/$item" | grep -F "$WORK_A" | head -n 5 || true
          strings "$ART_B/$item" | grep -F "$WORK_B" | head -n 5 || true
          ;;
      esac
    fi
  done
  if [ "$mismatch" -eq 0 ]; then
    printf 'CLASSIFICATION=REPRODUCIBLE\n'
  else
    printf 'CLASSIFICATION=NON_REPRODUCIBLE\n'
  fi
} | tee "$REPORT"

[ "$mismatch" -eq 0 ]
