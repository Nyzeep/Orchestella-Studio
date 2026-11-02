#!/bin/sh
# Zero-device regression for the staging mode guard (ticket 11).
#
# Covers the three probe verdicts with PATH stubs (so the cases are host
# independent), the real host's verdict against an independently measured
# truth, and the fail-fast branch of build.sh itself.
#
# No -e on purpose: every case is asserted individually and the counters decide
# the exit status. Nothing is deleted with rm -rf; the scratch tree lives under
# /tmp so the build.sh safe_reset_directory whitelist accepts it.
set -u

umask 022

here=$(cd -- "$(dirname -- "$0")" && pwd -P)
GUEST_ASSETS_DIR=$(cd -- "$here/.." && pwd -P)
BUILD="$GUEST_ASSETS_DIR/build.sh"
CHECK="$here/check-staging-fs.sh"

WORK="/tmp/droidlayer-guest-assets.staging-guard.$$"
STATE="$WORK/state"
LOG="$WORK/log"

ok=0
bad=0

pass() {
  ok=$((ok + 1))
  printf 'ok   %s\n' "$1"
}

fail() {
  bad=$((bad + 1))
  printf 'FAIL %s\n' "$1" >&2
}

show() {
  [ -f "$1" ] || return 0
  sed -n '1,4p' "$1" >&2
}

cleanup() {
  [ -d "$WORK" ] || return 0
  find "$WORK" -type f -exec unlink -- {} \; 2>/dev/null || true
  find "$WORK" -depth -type d -exec rmdir -- {} \; 2>/dev/null || true
}
trap cleanup 0

mkdir -p -- "$WORK" "$STATE" "$LOG" "$WORK/staging" \
  "$WORK/stub-noop" "$WORK/stub-preserve" "$WORK/stub-statfail" \
  "$WORK/stub-tools" || {
  printf 'cannot create scratch directory: %s\n' "$WORK" >&2
  exit 1
}

# --- stubs ----------------------------------------------------------------
# A chmod that really stores the mode, paired with a stat that reports it back:
# this is what a native Linux filesystem looks like from the probe's angle.
cat > "$WORK/stub-preserve/chmod" <<'STUB'
#!/bin/sh
mode=$1
while [ "${mode#0}" != "$mode" ]; do
  mode=${mode#0}
done
shift
shift
for path in "$@"; do
  printf '%s=%s\n' "$path" "$mode" >> "$SG_STATE/modes"
done
exit 0
STUB
cat > "$WORK/stub-preserve/stat" <<'STUB'
#!/bin/sh
path=$4
[ -f "$SG_STATE/modes" ] || exit 1
while IFS='=' read -r recorded value; do
  [ "$recorded" = "$path" ] || continue
  printf '%s\n' "$value"
  exit 0
done < "$SG_STATE/modes"
exit 1
STUB
# The DrvFs shape: chmod succeeds but changes nothing, so the probe reads back
# the mode mkdir produced under umask 022.
cat > "$WORK/stub-noop/chmod" <<'STUB'
#!/bin/sh
exit 0
STUB
# An unreadable mode: the probe must say "undecidable", not "modes are fine".
cat > "$WORK/stub-statfail/stat" <<'STUB'
#!/bin/sh
exit 1
STUB
# build.sh verifies its cross toolchain before it resets staging; the guard
# fires long before any of these is used, so presence is all that matters.
for tool in aarch64-linux-gnu-gcc aarch64-linux-gnu-readelf aarch64-linux-gnu-strip \
    bison cpio curl flex gzip java make sha256sum tar xz; do
  cat > "$WORK/stub-tools/$tool" <<'STUB'
#!/bin/sh
exit 0
STUB
done
chmod 0755 "$WORK"/stub-noop/chmod "$WORK"/stub-preserve/chmod \
  "$WORK/stub-preserve/stat" "$WORK/stub-statfail/stat" "$WORK/stub-tools"/*

probe_residue() {
  find "$WORK/staging" -name '.mode-probe.*' -print 2>/dev/null
}

# --- 1. a filesystem that stores modes -> 0 --------------------------------
rc=0
SG_STATE="$STATE" PATH="$WORK/stub-preserve:$PATH" \
  bash "$CHECK" "$WORK/staging" > "$LOG/preserve.log" 2>&1 || rc=$?
if [ "$rc" -eq 0 ]; then
  pass "mode-preserving filesystem is accepted (rc=$rc)"
else
  fail "mode-preserving filesystem is accepted: expected rc=0, got rc=$rc"
  show "$LOG/preserve.log"
fi
if [ -z "$(probe_residue)" ]; then
  pass "probe directory leaves no residue"
else
  fail "probe directory leaves no residue: $(probe_residue)"
fi

# --- 2. a chmod that is silently a no-op -> 1 ------------------------------
rc=0
PATH="$WORK/stub-noop:$PATH" \
  bash "$CHECK" "$WORK/staging" > "$LOG/noop.log" 2>&1 || rc=$?
if [ "$rc" -eq 1 ]; then
  pass "filesystem that drops mode bits is rejected (rc=$rc)"
else
  fail "filesystem that drops mode bits is rejected: expected rc=1, got rc=$rc"
  show "$LOG/noop.log"
fi

# --- 3. an unreadable mode -> 2 -------------------------------------------
rc=0
PATH="$WORK/stub-statfail:$PATH" \
  bash "$CHECK" "$WORK/staging" > "$LOG/statfail.log" 2>&1 || rc=$?
if [ "$rc" -eq 2 ]; then
  pass "unreadable mode is reported as undecidable (rc=$rc)"
else
  fail "unreadable mode is reported as undecidable: expected rc=2, got rc=$rc"
  show "$LOG/statfail.log"
fi

# --- 4. the real host: the guard must agree with measured behaviour --------
mkdir -p -- "$WORK/hostprobe"
chmod 0700 "$WORK/hostprobe" 2>/dev/null || true
measured=$(stat -c %a "$WORK/hostprobe" 2>/dev/null) || measured=""
if [ "$measured" = "700" ]; then
  expected=0
  host_class="preserves"
else
  expected=1
  host_class="drops"
fi
rc=0
bash "$CHECK" "$WORK/staging" > "$LOG/host.log" 2>&1 || rc=$?
if [ "$rc" -eq "$expected" ]; then
  pass "real host ($(uname -s), mode=$measured, $host_class) verdict matches (rc=$rc)"
else
  fail "real host verdict: expected rc=$expected for mode=$measured, got rc=$rc"
  show "$LOG/host.log"
fi

# --- 5. build.sh fail-fast really exits 70 --------------------------------
rc=0
SG_STATE="$STATE" \
  PATH="$WORK/stub-noop:$WORK/stub-tools:$PATH" \
  GUEST_ASSETS_WORK_DIR="$WORK/build/work" \
  GUEST_ASSETS_OUT_DIR="$WORK/build/out" \
  GUEST_ASSETS_DOWNLOAD_CACHE="$WORK/build/downloads" \
  bash "$BUILD" > "$LOG/build.log" 2>&1 || rc=$?
if [ "$rc" -eq 70 ]; then
  pass "build.sh fails fast with exit 70 (rc=$rc)"
else
  fail "build.sh fails fast: expected exit 70, got rc=$rc"
  show "$LOG/build.log"
fi
if grep -q -e 'staging filesystem drops Unix mode bits' "$LOG/build.log" 2>/dev/null; then
  pass "build.sh explains the staging filesystem failure"
else
  fail "build.sh explains the staging filesystem failure"
  show "$LOG/build.log"
fi

# --- 6. the packing-time normalisation and assertion are still wired in ----
if grep -q -e 'type d -exec chmod 0755' "$BUILD" 2>/dev/null \
  && grep -q -e 'type d -perm /022' "$BUILD" 2>/dev/null; then
  pass "build.sh normalises and asserts staging directory modes before packing"
else
  fail "build.sh normalises and asserts staging directory modes before packing"
fi

# --- 7. --help is available for out-of-repo recipes -----------------------
rc=0
bash "$CHECK" --help > "$LOG/help.log" 2>&1 || rc=$?
if [ "$rc" -eq 0 ] && grep -q -e 'mkfs.ext4 -d' "$LOG/help.log" 2>/dev/null; then
  pass "--help documents the mkfs.ext4 -d use case (rc=$rc)"
else
  fail "--help documents the mkfs.ext4 -d use case: rc=$rc"
  show "$LOG/help.log"
fi

printf 'staging guard checks: ok=%s bad=%s\n' "$ok" "$bad"
[ "$bad" -eq 0 ] || exit 1
printf 'STAGING_GUARD_TEST_OK\n'
