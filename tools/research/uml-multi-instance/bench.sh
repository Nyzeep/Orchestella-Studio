#!/usr/bin/env bash
# bench.sh -- U6 UML multi-instance scaling bench (research fixture, no product code).
#
# Launches N UML instances concurrently on the WSL x86_64 host and records:
#   - per-instance host RSS (VmRSS / Pss) and cumulative CPU ticks
#   - host total UML RSS, host MemAvailable, host busy CPU ticks
#   - T2/T3 console markers per instance (host wall-clock latency + guest uptime)
#   - disk: shared hostfs rootfs (du) and per-instance guest RAM file (du;
#     UML unlinks the RAM file right after mmap, so observed on-disk cost is 0
#     and guest RAM lives in host page cache mapped into the UML process)
#   - failure modes: early exit, no READY within boot timeout, host-memory skip
#
# Fixtures (defaults are WSL-local paths, see --help):
#   kernel : research/uml-fastboot out/x86_64-up artifacts linux-fast-noinitrd
#   rootfs : research/uml-rootfs-prebake alpine-x64 tarball, unpacked once and
#            shared read-mostly by all instances through hostfs
#
# Boot form -- the U2 wave-1 cross-test form (docs/research/
# 2026-11-10-zero-device-uml-wave1-integration.zh-CN.md section 4):
#   <kernel> mem=<MEM> rw con=null con0=null,fd:1 seccomp=auto panic=1 \
#     printk.time=1 rootfstype=hostfs rootflags=<rootfs-dir> \
#     init=/init-zero-install orchestella.ready=shell
#
# The prebaked ready script prints T2_MOUNTS_READY / T3_READY / READY, then
# execs /bin/sh -l reading stdin; stdin is a FIFO held open by this bench so
# the instance stays up during the steady-state window. Teardown is SIGTERM
# (the same clean stop research/uml-runner and bench-fast.py use).
#
# Output (per run dir): manifest.json, instances.csv, samples.csv, bench.log,
# per-round console logs and port snapshots. parse.py turns these into the
# summary table. Raw outputs are gitignored.
set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

# ---- defaults -------------------------------------------------------------
NS="1,2,4,8"
ROUNDS=3
MEM="256M"
HOLD_S=10
TICK_MS=200
BOOT_TIMEOUT_S=60
WARMUP=1
MEM_FLOOR_KB=800000      # skip a round when host MemAvailable is below this
KERNEL="${U6_KERNEL:-/root/orch-device/research/uml-fastboot/out/x86_64-up/artifacts/linux-fast-noinitrd}"
ROOTFS_TAR="${U6_ROOTFS_TAR:-/root/orch-device/research/uml-rootfs-prebake/out/alpine-x64/alpine-3.22.8-x86_64.tar.gz}"
ROOTFS_DIR="${U6_ROOTFS_DIR:-/root/orch-device/research/uml-multi-instance-work/alpine-x64-rootfs}"
WORK="${U6_WORK:-/root/orch-device/research/uml-multi-instance-work}"
TAG=""
OUT="$SCRIPT_DIR/out"

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --ns) NS="$2"; shift 2 ;;
        --rounds) ROUNDS="$2"; shift 2 ;;
        --mem) MEM="$2"; shift 2 ;;
        --hold-s) HOLD_S="$2"; shift 2 ;;
        --tick-ms) TICK_MS="$2"; shift 2 ;;
        --boot-timeout-s) BOOT_TIMEOUT_S="$2"; shift 2 ;;
        --no-warmup) WARMUP=0; shift ;;
        --kernel) KERNEL="$2"; shift 2 ;;
        --rootfs-tar) ROOTFS_TAR="$2"; shift 2 ;;
        --rootfs-dir) ROOTFS_DIR="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --tag) TAG="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[ -n "$TAG" ] || TAG="run-$(date -u +%Y%m%d-%H%M%S)"

# ---- guards ---------------------------------------------------------------
if [ "$(uname -s)" != "Linux" ]; then
    echo "bench.sh must run on Linux (WSL). Try: wsl -e bash -c 'bash $0 ...'" >&2
    exit 2
fi
for f in "$KERNEL" "$ROOTFS_TAR"; do
    [ -f "$f" ] || { echo "missing fixture: $f" >&2; exit 2; }
done
KCFG="$(dirname "$KERNEL")/kernel-noinitrd.config"
[ -f "$KCFG" ] || KCFG=""

# ---- staging --------------------------------------------------------------
mkdir -p "$WORK" "$OUT"
STAGE="$WORK/stage-$TAG"
rm -rf "$STAGE"
mkdir -p "$STAGE"
RUN_OUT="$OUT/$TAG"
rm -rf "$RUN_OUT"
mkdir -p "$RUN_OUT"

if [ ! -x "$ROOTFS_DIR/init-zero-install" ]; then
    echo "[stage] unpacking rootfs tarball -> $ROOTFS_DIR"
    rm -rf "$ROOTFS_DIR"
    mkdir -p "$ROOTFS_DIR"
    tar -xzf "$ROOTFS_TAR" -C "$ROOTFS_DIR"
fi
[ -x "$ROOTFS_DIR/init-zero-install" ] || { echo "rootfs dir lacks init-zero-install: $ROOTFS_DIR" >&2; exit 2; }
[ -x "$ROOTFS_DIR/ready.sh" ] || { echo "rootfs dir lacks ready.sh: $ROOTFS_DIR" >&2; exit 2; }

LOG="$STAGE/bench.log"
SAMPLES="$STAGE/samples.csv"
INSTANCES="$STAGE/instances.csv"
HOSTCLK_TCK=$(getconf CLK_TCK)
PAGESIZE=$(getconf PAGESIZE)
HOST_UNAME=$(uname -r)
HOST_NPROC=$(nproc)
echo "bench uid=$(id -u) host=$HOST_UNAME nproc=$HOST_NPROC clk_tck=$HOSTCLK_TCK pagesize=$PAGESIZE" > "$LOG"
printf 'n,round,phase,ts_rel_ms,inst,rss_main_kb,pss_main_kb,rss_other_kb,pss_other_kb,cpu_ticks_cum,tasks,host_memavail_kb,host_cpu_busy_cum,host_cpu_all_cum\n' > "$SAMPLES"
printf 'n,round,inst,pgid,rc,ready_ok,fail_mode,ready_host_ms,t3_guest_uptime_s,t2_guest_uptime_s,rss_hwm_kb,rss_end_kb,pss_end_kb,cpu_boot_ticks,cpu_steady_ticks,ram_apparent_bytes,ram_blocks_bytes,tasks_end\n' > "$INSTANCES"

# ---- helpers --------------------------------------------------------------

now_ns() { date +%s%N; }

mem_available_kb() { awk '/^MemAvailable:/{print $2}' /proc/meminfo; }

host_cpu_ticks() { # prints "busy all"
    awk '/^cpu /{b=$2+$3+$4+$6+$7+$8; a=b+$5; print b, a}' /proc/stat
}

# task_state <pid> -> first char of /proc/<pid>/stat state, empty if gone
task_state() {
    local line
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 0
    # comm of the processes we launch ("linux-fast-noin", "uml-userspace")
    # has no spaces, so positional fields are safe for these pids.
    set -- $line
    printf '%s' "${3:-}"
}

# scan_pgid_stats <pgid list> -> "pgid pid cpu_ticks rss_pages state" per task
scan_pgid_stats() {
    cat /proc/[0-9]*/stat 2>/dev/null | awk -v pg="$1" '
        function rindex(s, t,  i) {
            for (i = length(s) - length(t) + 1; i >= 1; i--)
                if (substr(s, i, length(t)) == t) return i
            return 0
        }
        BEGIN { n = split(pg, P, " "); for (i = 1; i <= n; i++) want[P[i]] = 1 }
        {
            i = rindex($0, ")"); if (i == 0) next
            split(substr($0, 1, i), h, " "); pid = h[1]
            split(substr($0, i + 2), a, " ")
            pgrp = a[3]
            if (pgrp in want)
                printf "%s %s %d %s %s\n", pgrp, pid, a[12] + a[13], a[22], a[1]
        }'
}

read_status_field() { # <pid> <pattern> -> value or empty
    awk -v pat="$2" '$0 ~ pat {print $2}' "/proc/$1/status" 2>/dev/null
}

read_pss_kb() { awk '/^Pss:/{print $2}' "/proc/$1/smaps_rollup" 2>/dev/null; }

# Deleted temp files of a process: UML creates the guest RAM file in TMPDIR
# and unlinks it right after mmap, so du() cannot see it. The open fd keeps
# the inode alive: stat -L gives the sparse apparent size and the ext4 blocks
# actually allocated (dirty guest pages are written back there under host
# memory pressure, which is why MemAvailable barely drops while instances run).
deleted_fd_bytes() { # <pid> -> "apparent blocks" (bytes)
    local fd tgt out a=0 b=0 s bl
    [ -d "/proc/$1/fd" ] || { echo "0 0"; return 0; }
    for fd in "/proc/$1/fd/"*; do
        tgt=$(readlink "$fd" 2>/dev/null) || continue
        case "$tgt" in
            *deleted*) ;;
            *) continue ;;
        esac
        s=$(stat -L -c %s "$fd" 2>/dev/null) || continue
        bl=$(stat -L -c %b "$fd" 2>/dev/null) || bl=0
        a=$((a + s))
        b=$((b + bl * 512))
    done
    echo "$a $b"
}

# Cumulative utime+stime over ALL threads of a process. Top-level /proc on
# this host does not expose thread dirs, so a pgid scan misses the UML helper
# threads; /proc/<pid>/task/*/stat covers them.
task_cpu_ticks() { # <pid> -> ticks
    local t u
    [ -d "/proc/$1/task" ] || { echo 0; return 0; }
    u=0
    for t in "/proc/$1/task/"*/stat; do
        [ -r "$t" ] || continue
        set -- $(cat "$t" 2>/dev/null)
        [ "$#" -ge 15 ] || continue
        u=$((u + ${14} + ${15}))
    done
    echo "$u"
}

task_threads() { # <pid> -> thread count (0 if gone)
    local t c=0
    for t in "/proc/$1/task/"*; do
        [ -d "$t" ] && c=$((c + 1))
    done
    echo "$c"
}

MY_PGID=$(ps -o pgid= -p $$ | tr -d ' ')

# append one samples.csv row-set for the current round state
sample_once() { # $1 = phase, $2 = ts_rel_ms
    local phase="$1" ts="$2" scan pgid pid cpu tasks
    local i main stub tgid ro po rss pss cm cs
    scan=$(scan_pgid_stats "$PGIDS")
    local mem_avail busy all
    mem_avail=$(mem_available_kb)
    read -r busy all <<< "$(host_cpu_ticks)"
    for i in $INSTS; do
        pgid="${PGID[$i]:-}"
        main="${MAINPID[$i]:-}"
        cpu=0; tasks=0
        rss=0; pss=0
        if [ -n "$main" ] && [ -r "/proc/$main/status" ]; then
            rss=$(read_status_field "$main" '^VmRSS:')
            pss=$(read_pss_kb "$main")
            [ -n "$rss" ] || rss=0
            [ -n "$pss" ] || pss=0
        fi
        ro=0; po=0; cm=0; cs=0
        stub="${STUBPID[$i]:-}"
        if [ -n "$stub" ] && ! [ -r "/proc/$stub/status" ]; then
            STUBPID[$i]=""
            stub=""
        fi
        if [ -z "$stub" ] && [ -n "$main" ]; then
            while read -r p2 pid c2 r2 s2; do
                [ "$p2" = "$pgid" ] || continue
                [ "$pid" != "$main" ] || continue
                tgid=$(read_status_field "$pid" '^Tgid:')
                if [ "$tgid" = "$pid" ]; then
                    STUBPID[$i]="$pid"
                    stub="$pid"
                    break
                fi
            done <<< "$scan"
        fi
        # cpu over all threads of main + stub (pgid scan misses threads here)
        cm=$(task_cpu_ticks "$main")
        if [ -n "$stub" ]; then
            cs=$(task_cpu_ticks "$stub")
            ro=$(read_status_field "$stub" '^VmRSS:')
            po=$(read_pss_kb "$stub")
            [ -n "$ro" ] || ro=0
            [ -n "$po" ] || po=0
        fi
        cpu=$(( cm + cs ))
        tasks=$(( $(task_threads "$main") + $( [ -n "$stub" ] && task_threads "$stub" || echo 0 ) ))
        printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
            "$N" "$ROUND" "$phase" "$ts" "$i" \
            "$rss" "$pss" "$ro" "$po" "$cpu" "$tasks" \
            "$mem_avail" "$busy" "$all" >> "$SAMPLES"
    done
}

ports_snapshot() { # $1 = out file, $2.. = pids
    local out="$1"; shift
    if ! command -v ss >/dev/null 2>&1; then
        echo "ss not available" > "$out"
        return 0
    fi
    local alt=""
    local p
    for p in "$@"; do alt="${alt:+$alt|}pid=$p"; done
    ss -tunp 2>/dev/null | grep -E "$alt" > "$out" 2>&1 || true
    wc -l < "$out" | tr -d ' ' > "${out%.txt}.count"
}

# stop one instance (SIGTERM, then KILL if needed) and set TERM_RC.
# Runs in the main shell on purpose: `wait` only sees this shell's jobs.
TERM_RC=0
term_one() { # $1 = pid
    local pid="$1" st k rc
    TERM_RC=0
    [ -n "$pid" ] || return 0
    st=$(task_state "$pid")
    if [ -n "$st" ] && [ "${st:0:1}" != "Z" ]; then
        kill -TERM "$pid" 2>/dev/null || true
        for k in $(seq 1 20); do
            st=$(task_state "$pid")
            [ -z "$st" ] && break
            [ "${st:0:1}" = "Z" ] && break
            sleep 0.1
        done
        st=$(task_state "$pid")
        if [ -n "$st" ] && [ "${st:0:1}" != "Z" ]; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
    wait "$pid" 2>/dev/null
    rc=$?
    case "$rc" in
        127) rc=-1 ;;   # pid already reaped elsewhere; unknown rc
    esac
    TERM_RC="$rc"
}

# ---- fixture hashes (for manifest) ---------------------------------------
echo "[manifest] hashing fixtures" | tee -a "$LOG"
KSHA=$(sha256sum "$KERNEL" | awk '{print $1}')
KBYTES=$(stat -c %s "$KERNEL")
TSHA=$(sha256sum "$ROOTFS_TAR" | awk '{print $1}')
TBYTES=$(stat -c %s "$ROOTFS_TAR")
ROOTFS_DU=$(du -sb "$ROOTFS_DIR" | awk '{print $1}')
KCFG_SHA=""
if [ -n "$KCFG" ]; then KCFG_SHA=$(sha256sum "$KCFG" | awk '{print $1}'); fi
EXT4="${ROOTFS_TAR%.tar.gz}.ext4"
EXT4_BYTES=""; [ -f "$EXT4" ] && EXT4_BYTES=$(stat -c %s "$EXT4")

CMDLINE_TPL="mem=$MEM rw con=null con0=null,fd:1 seccomp=auto panic=1 printk.time=1 rootfstype=hostfs rootflags=<ROOTFS_DIR> init=/init-zero-install orchestella.ready=shell"

SECCOMP_MODE="unknown"
KERNEL_BANNER=""

# ---- one instance round ---------------------------------------------------
# launch_round <n_instances> <dir>; sets globals: INSTS, PGID, MAINPID, STUBPID,
# PGIDS, T0_NS, and per-instance arrays used by run_round.
declare -a MAINPID STUBPID PGID FD
declare -a T_LAUNCH READY_NS T3_UP T2_UP READY_OK FMODE RC CPU_BOOT CPU0 CPU1 CPU_END
declare -a RAM_A RAM_B TASKS_END

cleanup_all() {  # safety net: stop everything still alive
    local i pid
    for i in $INSTS; do
        pid="${MAINPID[$i]:-}"
        [ -n "$pid" ] && kill -KILL "$pid" 2>/dev/null
    done
}
trap cleanup_all EXIT

launch_round() { # $1 = N, $2 = stage dir for this round
    local n="$1" dir="$2" i fdi
    mkdir -p "$dir"
    INSTS=""
    for i in $(seq 0 $((n - 1))); do
        INSTS="$INSTS $i"
        mkdir -p "$dir/tmp$i"
        mkfifo "$dir/fifo$i"
        eval "exec {fdi}<>\"$dir/fifo$i\"" || { FMODE[$i]="fifo_open_failed"; fdi=0; }
        FD[$i]=$fdi
        T_LAUNCH[$i]=$(now_ns)
        TMPDIR="$dir/tmp$i" "$KERNEL" "mem=$MEM" rw con=null con0=null,fd:1 \
            seccomp=auto panic=1 printk.time=1 \
            rootfstype=hostfs "rootflags=$ROOTFS_DIR" \
            init=/init-zero-install orchestella.ready=shell \
            <"$dir/fifo$i" >"$dir/console$i.log" 2>&1 &
        MAINPID[$i]=$!
        PGID[$i]=${MAINPID[$i]}
        STUBPID[$i]=""
        READY_OK[$i]=0
        FMODE[$i]="none"
        READY_NS[$i]=""
        T3_UP[$i]=""
        T2_UP[$i]=""
        RC[$i]=""
    done
    PGIDS=$(echo $INSTS | tr ' ' '\n' | while read -r i; do [ -n "$i" ] && echo "${PGID[$i]}"; done | tr '\n' ' ')
    T0_NS=$(now_ns)
    # cpu baseline right after the launch loop
    for i in $INSTS; do
        CPU0[$i]=$(task_cpu_ticks "${MAINPID[$i]}")
    done
}

wait_ready() { # $1 = deadline_s ; polls consoles, records T2/T3; samples boot RSS
    local deadline="$1" i st t2 t3 resolved
    local poll=0
    while :; do
        resolved=1
        for i in $INSTS; do
            [ "${READY_OK[$i]}" = "1" ] && continue
            [ "${FMODE[$i]}" != "none" ] && continue
            resolved=0
            if [ -f "${CONSOLE_DIR}/console$i.log" ]; then
                t3=$(sed -n 's/^T3_READY uptime=\([0-9.]*\).*/\1/p' "${CONSOLE_DIR}/console$i.log" | head -1)
                if [ -n "$t3" ]; then
                    READY_NS[$i]=$(now_ns)
                    T3_UP[$i]="$t3"
                    t2=$(sed -n 's/^T2_MOUNTS_READY uptime=\([0-9.]*\).*/\1/p' "${CONSOLE_DIR}/console$i.log" | head -1)
                    T2_UP[$i]="${t2:-}"
                    READY_OK[$i]=1
                    continue
                fi
                st=$(task_state "${MAINPID[$i]}")
                if [ -z "$st" ] || [ "${st:0:1}" = "Z" ]; then
                    FMODE[$i]="early_exit"
                    continue
                fi
                if grep -q '^Kernel panic' "${CONSOLE_DIR}/console$i.log" 2>/dev/null; then
                    FMODE[$i]="panic"
                    continue
                fi
                if grep -q '^UM_INIT_ERROR' "${CONSOLE_DIR}/console$i.log" 2>/dev/null; then
                    FMODE[$i]="um_init_error"
                    continue
                fi
            else
                FMODE[$i]="console_missing"
                continue
            fi
        done
        [ "$resolved" = "1" ] && break
        if [ $((poll % 10)) -eq 0 ]; then
            sample_once boot "$(( ($(now_ns) - T0_NS) / 1000000 ))"
        fi
        poll=$((poll + 1))
        if [ $((poll * 10)) -ge $((BOOT_TIMEOUT_S * 1000)) ]; then
            for i in $INSTS; do
                if [ "${READY_OK[$i]}" = "0" ] && [ "${FMODE[$i]}" = "none" ]; then
                    FMODE[$i]="timeout_no_ready"
                fi
            done
            break
        fi
        sleep 0.01
    done
    # cpu after boot phase
    for i in $INSTS; do
        CPU1[$i]=$(task_cpu_ticks "${MAINPID[$i]}")
        if [ -n "${STUBPID[$i]:-}" ]; then
            CPU1[$i]=$(( CPU1[$i] + $(task_cpu_ticks "${STUBPID[$i]}") ))
        fi
        # a dead task must not lower the baseline below the earlier snapshot
        [ "${CPU1[$i]}" -lt "${CPU0[$i]}" ] && CPU1[$i]="${CPU0[$i]}"
        CPU_BOOT[$i]=$(( ${CPU1[$i]} - ${CPU0[$i]} ))
    done
}

steady_phase() { # sample TICK_MS cadence for HOLD_S seconds
    local end_tick=$(( HOLD_S * 1000 / TICK_MS )) t
    for t in $(seq 1 "$end_tick"); do
        sample_once steady "$(( ($(now_ns) - T0_NS) / 1000000 ))"
        sleep "0.$(printf '%03d' "$TICK_MS")"
    done
}

teardown_round() { # $1 = round console dir; writes instances.csv rows
    local dir="$1" scan i st hwm pend pendp fa fb
    scan=$(scan_pgid_stats "$PGIDS")   # kept for the leftover-group sweep
    for i in $INSTS; do
        # cpu at end of steady phase (all threads of main + stub)
        CPU_END[$i]=$(task_cpu_ticks "${MAINPID[$i]}")
        if [ -n "${STUBPID[$i]:-}" ]; then
            CPU_END[$i]=$(( CPU_END[$i] + $(task_cpu_ticks "${STUBPID[$i]}") ))
        fi
        [ "${CPU_END[$i]}" -lt "${CPU1[$i]:-0}" ] && CPU_END[$i]="${CPU1[$i]:-0}"
    done
    for i in $INSTS; do
        local main="${MAINPID[$i]}"
        hwm=$(read_status_field "$main" '^VmHWM:')
        pend=$(read_status_field "$main" '^VmRSS:')
        pendp=$(read_pss_kb "$main")
        # deleted-fd disk cost must be read before the process is stopped
        read -r RAM_A[$i] RAM_B[$i] <<< "$(deleted_fd_bytes "$main")"
        if [ -n "${STUBPID[$i]:-}" ]; then
            read -r fa fb <<< "$(deleted_fd_bytes "${STUBPID[$i]}")"
            RAM_A[$i]=$(( ${RAM_A[$i]:-0} + fa ))
            RAM_B[$i]=$(( ${RAM_B[$i]:-0} + fb ))
        fi
        # task count (threads of main + stub) must also be read pre-kill
        TASKS_END[$i]=$(( $(task_threads "$main") + \
            $( [ -n "${STUBPID[$i]:-}" ] && task_threads "${STUBPID[$i]}" || echo 0 ) ))
        term_one "$main"
        RC[$i]=$TERM_RC
        # sweep leftover group members (stub, threads) -- never our own pgid
        local pgid="${PGID[$i]}" p2 pid c2 r2 s2
        while read -r p2 pid c2 r2 s2; do
            [ "$p2" = "$pgid" ] || continue
            [ "$pid" != "$main" ] || continue
            [ "$pgid" != "$MY_PGID" ] || continue
            st=$(task_state "$pid")
            if [ -n "$st" ] && [ "${st:0:1}" != "Z" ]; then
                kill -KILL "$pid" 2>/dev/null || true
            fi
        done <<< "$scan"
        local ram_a=0 ram_b=0 tasks_end=0
        ram_a=${RAM_A[$i]:-0}
        ram_b=${RAM_B[$i]:-0}
        tasks_end=${TASKS_END[$i]:-0}
        printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
            "$N" "$ROUND" "$i" "$pgid" "${RC[$i]}" "${READY_OK[$i]}" "${FMODE[$i]}" \
            "$( [ -n "${READY_NS[$i]}" ] && echo $(( (${READY_NS[$i]} - ${T_LAUNCH[$i]}) / 1000000 )) || echo -1 )" \
            "${T3_UP[$i]:--1}" "${T2_UP[$i]:--1}" \
            "${hwm:-0}" "${pend:-0}" "${pendp:-0}" \
            "${CPU_BOOT[$i]}" "$(( ${CPU_END[$i]:-0} - ${CPU1[$i]:-0} ))" \
            "$ram_a" "$ram_b" "$tasks_end" >> "$INSTANCES"
    done
}

run_round() { # $1 = N, $2 = round, $3 = base dir for round
    local n="$1" r="$2" base="$3"
    N="$n"; ROUND="$r"
    CONSOLE_DIR="$base/n$n-r$r"
    mkdir -p "$CONSOLE_DIR"
    local avail
    avail=$(mem_available_kb)
    if [ "$avail" -lt "$MEM_FLOOR_KB" ]; then
        echo "[n=$n r=$r] SKIP: host MemAvailable ${avail} kB < floor ${MEM_FLOOR_KB} kB" | tee -a "$LOG"
        for i in $(seq 0 $((n - 1))); do
            printf '%s,%s,%s,-1,-1,0,host_mem_skip,-1,-1,-1,0,0,0,0,0,0,0,0\n' "$n" "$r" "$i" >> "$INSTANCES"
        done
        return 0
    fi
    launch_round "$n" "$CONSOLE_DIR"
    wait_ready "$BOOT_TIMEOUT_S"
    local ready_cnt=0 i
    for i in $INSTS; do [ "${READY_OK[$i]}" = "1" ] && ready_cnt=$((ready_cnt + 1)); done
    local all_ready_ms=0
    for i in $INSTS; do
        [ -n "${READY_NS[$i]}" ] || continue
        local ms=$(( (${READY_NS[$i]} - ${T_LAUNCH[$i]}) / 1000000 ))
        [ "$ms" -gt "$all_ready_ms" ] && all_ready_ms=$ms
    done
    echo "[n=$n r=$r] ready $ready_cnt/$n all_ready_ms=$all_ready_ms fail_modes=$(echo $INSTS | tr ' ' '\n' | while read -r i; do [ "${FMODE[$i]}" != "none" ] && [ "${READY_OK[$i]}" = "0" ] && echo -n "$i:${FMODE[$i]} "; done)" | tee -a "$LOG"
    ports_snapshot "$CONSOLE_DIR/ports.txt" $(for i in $INSTS; do echo "${MAINPID[$i]}"; done)
    steady_phase
    teardown_round "$CONSOLE_DIR"
    # close fifo writer fds
    for i in $INSTS; do
        eval "exec ${FD[$i]}<&-" 2>/dev/null || true
        eval "exec ${FD[$i]}>&-" 2>/dev/null || true
    done
    INSTS=""; PGIDS=""
    sleep 2   # let the host settle between rounds
}

# ---- warmup (not recorded) ------------------------------------------------
if [ "$WARMUP" = "1" ]; then
    echo "[warmup] one unrecorded instance to warm page cache" | tee -a "$LOG"
    W="$STAGE/warmup"
    mkdir -p "$W/tmp0"
    mkfifo "$W/fifo0"
    exec 8<>"$W/fifo0"
    TMPDIR="$W/tmp0" "$KERNEL" "mem=$MEM" rw con=null con0=null,fd:1 \
        seccomp=auto panic=1 printk.time=1 \
        rootfstype=hostfs "rootflags=$ROOTFS_DIR" \
        init=/init-zero-install orchestella.ready=shell \
        <"$W/fifo0" >"$W/console0.log" 2>&1 &
    WPID=$!
    for t in $(seq 1 300); do
        grep -q '^READY' "$W/console0.log" 2>/dev/null && break
        sleep 0.05
    done
    sleep 1
    if grep -q 'Userspace mode: ' "$W/console0.log"; then
        SECCOMP_MODE=$(sed -n 's/^Userspace mode: //p' "$W/console0.log" | head -1)
    fi
    KERNEL_BANNER=$(grep -m1 '^Linux version' "$W/console0.log" || true)
    kill -TERM "$WPID" 2>/dev/null || true
    for t in $(seq 1 20); do
        st=$(task_state "$WPID"); [ -z "$st" ] && break; [ "${st:0:1}" = "Z" ] && break
        sleep 0.1
    done
    st=$(task_state "$WPID"); [ -n "$st" ] && [ "${st:0:1}" != "Z" ] && kill -KILL "$WPID" 2>/dev/null
    wait "$WPID" 2>/dev/null || true
    exec 8<&- 2>/dev/null || true
    echo "[warmup] done mode=$SECCOMP_MODE" | tee -a "$LOG"
fi

# ---- main loops -----------------------------------------------------------
for n in $(echo "$NS" | tr ',' ' '); do
    for r in $(seq 1 "$ROUNDS"); do
        echo "[run] n=$n round=$r start $(date -u +%H:%M:%S)" | tee -a "$LOG"
        run_round "$n" "$r" "$STAGE"
    done
done

# ports evidence line
PORT_TOTAL=$(cat "$STAGE"/n*-r*/ports.count 2>/dev/null | awk '{s+=$1} END {print s+0}')
echo "[ports] host TCP/UDP sockets held by UML pids across all rounds: $PORT_TOTAL" | tee -a "$LOG"
echo "[mconsole] /root/.uml entries after run: $(ls /root/.uml 2>/dev/null | wc -l)" | tee -a "$LOG"

# ---- manifest -------------------------------------------------------------
U6_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
U6_TAG="$TAG" \
U6_KERNEL_PATH="$KERNEL" U6_KSHA="$KSHA" U6_KBYTES="$KBYTES" \
U6_KCFG="$KCFG" U6_KCFG_SHA="$KCFG_SHA" \
U6_TAR="$ROOTFS_TAR" U6_TSHA="$TSHA" U6_TBYTES="$TBYTES" \
U6_RFS="$ROOTFS_DIR" U6_RFS_DU="$ROOTFS_DU" \
U6_EXT4="$EXT4" U6_EXT4_BYTES="$EXT4_BYTES" \
U6_MEM="$MEM" U6_NS="$NS" U6_ROUNDS="$ROUNDS" U6_HOLD="$HOLD_S" \
U6_TICK="$TICK_MS" U6_BT="$BOOT_TIMEOUT_S" U6_WARMUP="$WARMUP" U6_FLOOR="$MEM_FLOOR_KB" \
U6_UNAME="$HOST_UNAME" U6_NPROC="$HOST_NPROC" U6_CLK="$HOSTCLK_TCK" U6_PS="$PAGESIZE" \
U6_CMD="$CMDLINE_TPL" U6_SECCOMP="$SECCOMP_MODE" U6_BANNER="$KERNEL_BANNER" \
python3 - "$STAGE" <<'PYEOF'
import json, os, sys

stage = sys.argv[1]
mem = {}
try:
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith(("MemTotal", "MemAvailable")):
                k, v = line.split(":")
                mem[k.strip()] = int(v.strip().split()[0])
except OSError:
    pass
m = {
    "schema": 1,
    "created_utc": os.environ["U6_TS"],
    "tag": os.environ["U6_TAG"],
    "kernel": {"path": os.environ["U6_KERNEL_PATH"], "sha256": os.environ["U6_KSHA"],
               "bytes": int(os.environ["U6_KBYTES"])},
    "kernel_config": {"path": os.environ["U6_KCFG"], "sha256": os.environ["U6_KCFG_SHA"]},
    "rootfs_tar": {"path": os.environ["U6_TAR"], "sha256": os.environ["U6_TSHA"],
                   "bytes": int(os.environ["U6_TBYTES"])},
    "rootfs_dir": {"path": os.environ["U6_RFS"], "du_apparent_bytes": int(os.environ["U6_RFS_DU"]),
                   "shared_by_instances": True},
    "rootfs_ext4": {"path": os.environ["U6_EXT4"], "bytes": int(os.environ["U6_EXT4_BYTES"]),
                    "note": "per-instance rootfs alternative, not used by this bench"},
    "guest_mem": os.environ["U6_MEM"],
    "ns": [int(x) for x in os.environ["U6_NS"].split(",")],
    "rounds_per_n": int(os.environ["U6_ROUNDS"]),
    "hold_s": float(os.environ["U6_HOLD"]),
    "tick_ms": int(os.environ["U6_TICK"]),
    "boot_timeout_s": float(os.environ["U6_BT"]),
    "warmup": int(os.environ["U6_WARMUP"]),
    "mem_floor_kb": int(os.environ["U6_FLOOR"]),
    "host": {"uname_release": os.environ["U6_UNAME"], "nproc": int(os.environ["U6_NPROC"]),
             "clk_tck": int(os.environ["U6_CLK"]), "pagesize": int(os.environ["U6_PS"]),
             "meminfo_kb": mem},
    "cmdline_template": os.environ["U6_CMD"],
    "uml": {"userspace_mode": os.environ["U6_SECCOMP"], "kernel_banner": os.environ["U6_BANNER"]},
    "notes": [
        "kernel boots in SECCOMP or ptrace userspace mode depending on host; see uml.userspace_mode",
        "UML creates the guest RAM file in per-instance TMPDIR and unlinks it right after mmap; the open fd keeps the inode alive so du() reports 0 B, but the ext4 blocks stay allocated (ram_blocks_bytes from /proc/<pid>/fd stat -L). Dirty guest pages are written back there under host memory pressure, so MemAvailable barely drops while instances run",
        "ready markers are polled at 10 ms granularity from console logs; T3 host latency resolution is +/-10 ms",
        "stdin is a FIFO held open by the bench so orchestella.ready=shell keeps each guest alive during the steady window; teardown is SIGTERM to the main pid (clean stop per research/uml-runner)",
        "rootfs is shared read-mostly through hostfs by all instances of a round",
    ],
}
with open(os.path.join(stage, "manifest.json"), "w") as f:
    json.dump(m, f, indent=2, sort_keys=True)
    f.write("\n")
PYEOF

# ---- copy evidence back ---------------------------------------------------
cp -r "$STAGE"/. "$RUN_OUT"/
echo "[done] run dir: $RUN_OUT" | tee -a "$LOG"
