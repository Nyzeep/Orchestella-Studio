#!/bin/sh
# Run one U3 capability case under an AOSP-like seccomp filter.
#
# The harness is the only place that knows how to assemble UML args and how to
# record a run.  It never modifies the profile or the AOSP sources; it asks
# gen_profile.py for a flat filter and seccomp_launch to install it.
#
# Usage:
#   run_uml_seccomp.sh --case ID --profile PROFILE.json --kernel VMLINUX \
#     --outdir DIR [--mode aosp|trace|baseline] [--guest-root DIR] \
#     [--guest-image IMG] [--host-shim glibc-x86_64] [--allow-extra a,b] \
#     [--deny-extra c] [--timeout SEC] [--strace] [--bpftrace]
#
# Output per run: console.log, stderr.log, filter.txt, harness.meta.
# Exit status is 0 for completed/unsupported runs; 2 for usage errors.

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CASE=""
PROFILE=""
KERNEL=""
OUTDIR=""
MODE="aosp"
GUEST_ROOT="/tmp/uml-seccomp-guest/rootfs"
GUEST_IMAGE="/tmp/uml-seccomp-guest/rootfs.img"
HOST_SHIM=""
ALLOW_EXTRA=""
DENY_EXTRA=""
TIMEOUT=60
USE_STRACE=0
USE_BPFTRACE=0
LAUNCHER=""

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --case) CASE=${2:-}; shift 2 ;;
        --profile) PROFILE=${2:-}; shift 2 ;;
        --kernel) KERNEL=${2:-}; shift 2 ;;
        --outdir) OUTDIR=${2:-}; shift 2 ;;
        --mode) MODE=${2:-}; shift 2 ;;
        --guest-root) GUEST_ROOT=${2:-}; shift 2 ;;
        --guest-image) GUEST_IMAGE=${2:-}; shift 2 ;;
        --host-shim) HOST_SHIM=${2:-}; shift 2 ;;
        --allow-extra) ALLOW_EXTRA=${2:-}; shift 2 ;;
        --deny-extra) DENY_EXTRA=${2:-}; shift 2 ;;
        --timeout) TIMEOUT=${2:-}; shift 2 ;;
        --strace) USE_STRACE=1; shift ;;
        --bpftrace) USE_BPFTRACE=1; shift ;;
        --launcher) LAUNCHER=${2:-}; shift 2 ;;
        --help|-h) usage ;;
        *) echo "run_uml_seccomp: unknown argument $1" >&2; usage ;;
    esac
done

[ -n "$CASE" ] || { echo "run_uml_seccomp: --case is required" >&2; exit 2; }
[ -n "$PROFILE" ] || { echo "run_uml_seccomp: --profile is required" >&2; exit 2; }
[ -n "$KERNEL" ] || { echo "run_uml_seccomp: --kernel is required" >&2; exit 2; }
[ -n "$OUTDIR" ] || { echo "run_uml_seccomp: --outdir is required" >&2; exit 2; }
[ -f "$PROFILE" ] || { echo "run_uml_seccomp: profile not found: $PROFILE" >&2; exit 2; }

mkdir -p "$OUTDIR"
OUT=$(CDPATH= cd -- "$OUTDIR" && pwd)
CONSOLE=$OUT/console.log
STDERR=$OUT/stderr.log
: > "$CONSOLE"
: > "$STDERR"

STATUS=ok
REASON=""
STRACE_STATUS=not-requested
BPFTRACE_STATUS=not-requested
RC=""
SIGNAL=""
TIMED_OUT=0
START_MS=$(date +%s%3N 2>/dev/null || echo 0)
DURATION_MS=""

finish() {
    local end_ms guest_sha
    end_ms=$(date +%s%3N 2>/dev/null || echo 0)
    if [ "$START_MS" != "0" ] && [ "$end_ms" != "0" ]; then
        DURATION_MS=$((end_ms - START_MS))
    fi
    if [ "$CASE" = "ubd0-boot" ]; then
        guest_sha=$(sha256sum "$GUEST_IMAGE" 2>/dev/null | cut -d' ' -f1)
    else
        guest_sha=$(sha256sum "$GUEST_ROOT/init" 2>/dev/null | cut -d' ' -f1)
    fi
    {
        echo "CASE=$CASE"
        echo "STATUS=$STATUS"
        echo "UNSUPPORTED_REASON=$REASON"
        echo "PROFILE=$(basename "$PROFILE" .json)"
        echo "PROFILE_SHA256=$(sha256sum "$PROFILE" | cut -d' ' -f1)"
        echo "MODE=$MODE"
        echo "HOST_SHIM=$HOST_SHIM"
        echo "ALLOW_EXTRA=$ALLOW_EXTRA"
        echo "EXTRA_DENY=$DENY_EXTRA"
        echo "HOST_ARCH=$(uname -m)"
        echo "HOST_KERNEL=$(uname -r)"
        echo "HOST_SECCOMP=$(awk '/^Seccomp:/{print $2}' /proc/self/status 2>/dev/null)"
        echo "HOST_NNP=$(awk '/^NoNewPrivs:/{print $2}' /proc/self/status 2>/dev/null)"
        echo "KERNEL_SHA256=$(sha256sum "$KERNEL" 2>/dev/null | cut -d' ' -f1)"
        echo "GUEST_SHA256=$guest_sha"
        echo "RC=$RC"
        echo "SIGNAL=$SIGNAL"
        echo "TIMED_OUT=$TIMED_OUT"
        echo "DURATION_MS=$DURATION_MS"
        echo "STRACE=$STRACE_STATUS"
        echo "BPFTRACE=$BPFTRACE_STATUS"
    } > "$OUT/harness.meta"
    exit 0
}

shquote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# --- case configuration ---------------------------------------------------
ROOT_ARGS=""
CASE_ARGS=""
USE_FD=0
USE_MCONSOLE=0

case "$CASE" in
    hostfs-boot|sigchld|clone|mount|personality|setrlimit)
        ROOT_ARGS="rootfstype=hostfs rootflags=$GUEST_ROOT"
        ;;
    ubd0-boot)
        [ -f "$GUEST_IMAGE" ] || { STATUS=error; REASON=guest-image-missing; finish; }
        ROOT_ARGS="ubd0=$GUEST_IMAGE root=/dev/ubda rootfstype=ext4"
        ;;
    vector-net)
        ROOT_ARGS="rootfstype=hostfs rootflags=$GUEST_ROOT"
        # Synthetic MAC is generated at runtime; override via UML_TEST_MAC if needed.
        vector_mac="${UML_TEST_MAC:-$(printf '02:%02x:%02x:%02x:%02x:%02x' 0 0 0 0 1)}"
        CASE_ARGS="vec0:transport=fd,fd=3,mac=${vector_mac}"
        USE_FD=1
        ;;
    mconsole)
        ROOT_ARGS="rootfstype=hostfs rootflags=$GUEST_ROOT"
        CASE_ARGS="u3_wait_ms=4000"
        USE_MCONSOLE=1
        ;;
    ncpus-2|ncpus-4)
        ROOT_ARGS="rootfstype=hostfs rootflags=$GUEST_ROOT"
        if ! "$KERNEL" --help 2>&1 | grep -q 'ncpus'; then
            STATUS=unsupported
            REASON=upstream-uml-has-no-ncpus
            finish
        fi
        CASE_ARGS="ncpus=${CASE#ncpus-}"
        ;;
    seccomp-fastpath)
        ROOT_ARGS="rootfstype=hostfs rootflags=$GUEST_ROOT"
        if ! "$KERNEL" --help 2>&1 | grep -q 'seccomp='; then
            STATUS=unsupported
            REASON=upstream-uml-has-no-seccomp-mode
            finish
        fi
        # The seccomp fast path's startup probe uses close_range (Linux 5.9+).
        if ! python3 -c 'import ctypes,sys
libc=ctypes.CDLL(None, use_errno=True)
r=libc.syscall(436, 1, 0, 0)
sys.exit(0 if r == 0 or ctypes.get_errno() != 38 else 1)' 2>/dev/null; then
            STATUS=unsupported
            REASON=host-kernel-lacks-close-range
            finish
        fi
        CASE_ARGS="seccomp=on"
        ;;
    *)
        STATUS=error
        REASON=unknown-case
        finish
        ;;
esac

if [ "$CASE" != "ubd0-boot" ] && [ ! -f "$GUEST_ROOT/init" ]; then
    STATUS=error
    REASON=guest-init-missing
    finish
fi

# --- host shim -------------------------------------------------------------
if [ -n "$HOST_SHIM" ]; then
    shim_file=$HERE/profile/host-shims/$HOST_SHIM.txt
    if [ ! -f "$shim_file" ]; then
        STATUS=error
        REASON=host-shim-missing
        finish
    fi
    shim=$(grep -v '^#' "$shim_file" | grep -v '^$' | tr '\n' ',' | sed 's/,$//')
    if [ -n "$ALLOW_EXTRA" ]; then
        ALLOW_EXTRA="$ALLOW_EXTRA,$shim"
    else
        ALLOW_EXTRA="$shim"
    fi
fi

# --- build launcher and filter --------------------------------------------
if [ "$MODE" != "baseline" ]; then
    if [ -z "$LAUNCHER" ]; then
        LAUNCHER=$OUT/seccomp_launch
        if [ ! -x "$LAUNCHER" ] || [ "$HERE/seccomp_launch.c" -nt "$LAUNCHER" ]; then
            if ! cc -O2 -Wall -Wextra -o "$LAUNCHER" "$HERE/seccomp_launch.c" 2>>"$STDERR"; then
                STATUS=error
                REASON=launcher-build-failed
                finish
            fi
        fi
    fi
    if ! python3 "$HERE/gen_profile.py" --emit-filter "$PROFILE" \
            --allow-extra "$ALLOW_EXTRA" --deny-extra "$DENY_EXTRA" \
            -o "$OUT/filter.txt" >>"$CONSOLE" 2>>"$STDERR"; then
        STATUS=error
        REASON=filter-build-failed
        finish
    fi
fi

# --- optional collectors ---------------------------------------------------
USE_STRACE_WRAP=0
USE_BPF_WRAP=0
if [ "$USE_STRACE" = 1 ] && [ "$USE_BPFTRACE" = 1 ]; then
    STATUS=error
    REASON=both-collectors-requested
    finish
fi
if [ "$USE_STRACE" = 1 ]; then
    if command -v strace >/dev/null 2>&1; then
        USE_STRACE_WRAP=1
        STRACE_STATUS=requested
    else
        STRACE_STATUS=unavailable
    fi
fi
if [ "$USE_BPFTRACE" = 1 ]; then
    if [ "$USE_MCONSOLE" = 1 ]; then
        STATUS=error
        REASON=bpftrace-mconsole-combination
        finish
    fi
    if command -v bpftrace >/dev/null 2>&1 && [ -f "$HERE/collect/syscalls.bt" ]; then
        USE_BPF_WRAP=1
        BPFTRACE_STATUS=requested
    else
        BPFTRACE_STATUS=unavailable
    fi
fi

# --- assemble command ------------------------------------------------------
TMPDIR=$OUT/tmp
mkdir -p "$TMPDIR"
export TMPDIR

set -- "$KERNEL" mem=256M rw init=/init con0=null,fd:1 $ROOT_ARGS $CASE_ARGS
if [ "$MODE" != "baseline" ]; then
    case "$MODE" in
        aosp) LMODE=enforce ;;
        trace) LMODE=trace ;;
        *) STATUS=error; REASON=unknown-mode; finish ;;
    esac
    set -- "$LAUNCHER" --filter "$OUT/filter.txt" --mode "$LMODE" \
        --log "$STDERR" -- "$@"
fi
if [ "$USE_FD" = 1 ]; then
    set -- python3 "$HERE/run_with_fd.py" --fd 3 -- "$@"
fi
if [ "$USE_STRACE_WRAP" = 1 ]; then
    set -- strace -f -ff -o "$OUT/strace" "$@"
fi

# --- run -------------------------------------------------------------------
if [ "$USE_BPF_WRAP" = 1 ]; then
    {
        printf '#!/bin/sh\nexec'
        for arg in "$@"; do
            printf ' %s' "$(shquote "$arg")"
        done
        printf '\n'
    } > "$OUT/bpftrace-cmd.sh"
    chmod +x "$OUT/bpftrace-cmd.sh"
    timeout -s KILL "$TIMEOUT" bpftrace -o "$OUT/bpftrace.txt" \
        -c "$OUT/bpftrace-cmd.sh" "$HERE/collect/syscalls.bt" \
        >>"$CONSOLE" 2>>"$STDERR"
    RC=$?
elif [ "$USE_MCONSOLE" = 1 ]; then
    UMID="u3-$CASE-$$"
    set -- "$@" "umid=$UMID"
    timeout -s KILL "$TIMEOUT" "$@" >>"$CONSOLE" 2>>"$STDERR" &
    UML_PID=$!
    if python3 "$HERE/mconsole_probe.py" \
            --socket "$HOME/.uml/$UMID/mconsole" --timeout 8 >>"$OUT/mconsole.log" 2>&1; then
        echo "CAP mconsole=ok" >> "$CONSOLE"
    fi
    wait "$UML_PID"
    RC=$?
else
    timeout -s KILL "$TIMEOUT" "$@" >>"$CONSOLE" 2>>"$STDERR"
    RC=$?
fi

if [ "$USE_STRACE_WRAP" = 1 ]; then
    if ls "$OUT"/strace* >/dev/null 2>&1; then
        STRACE_STATUS=collected
    else
        STRACE_STATUS=no-output
    fi
fi
if [ "$USE_BPF_WRAP" = 1 ]; then
    if [ -s "$OUT/bpftrace.txt" ]; then
        BPFTRACE_STATUS=collected
    else
        BPFTRACE_STATUS=no-output
    fi
fi

if [ "$RC" -eq 124 ] || [ "$RC" -eq 137 ]; then
    TIMED_OUT=1
fi
if [ "$RC" -gt 128 ]; then
    SIGNAL=$((RC - 128))
else
    SIGNAL=0
fi

finish
