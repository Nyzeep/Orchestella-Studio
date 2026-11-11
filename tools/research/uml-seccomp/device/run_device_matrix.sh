#!/usr/bin/env bash
# U3 12-case device-b matrix harness — runner-app channel, one-key closed loop.
#
# ============================================================================
# 主会话设备操作 SOP（device-b：API 35 / arm64 / 内核 5.10.x / 4K 页）
# ============================================================================
# 本脚本是唯一入口；它内嵌全部 adb 调用（serial 从环境变量 DEVICE_SERIAL 读，
# 干跑 DEVICE_SERIAL 留空即可列出 12-case 计划、零 adb 接触）。前置一次性准备：
#
#   0) U3 guest rootfs（宿主侧，含 CAP 探针 init）：
#        cc 静态编译由 guest/build_guest.sh 完成（WSL 或任何有 cc 的宿主）：
#          tools/research/uml-seccomp/guest/build_guest.sh /tmp/uml-seccomp-guest
#        产物：/tmp/uml-seccomp-guest/rootfs/init（静态二进制）与 rootfs.img（ubd0 用）
#   1) runner APK（wave2 §1.3 形态：jniLibs 含 libuml_kernel.so / libuml_kernel_smp.so /
#      libuml_stub.so；keepDebugSymbols 通配、useLegacyPackaging）：
#        cd research/uml-runner && ./gradlew assembleSdk36Debug
#        adb install -r -t app/build/outputs/apk/sdk36/debug/app-sdk36-debug.apk
#   2) 权限与驻留（runner 的 FGS 通知在 33+ 需运行时权限）：
#        adb shell pm grant dev.orchestella.umlrunner android.permission.POST_NOTIFICATIONS
#        adb shell svc power stayon usb
#        adb shell dumpsys deviceidle whitelist +dev.orchestella.umlrunner
#   3) 一键准备（推 U3 rootfs + ext4 镜像，等价 wave2 §3 的 tar 管道）：
#        DEVICE_SERIAL=<serial> \
#          U3_ROOTFS_DIR=/tmp/uml-seccomp-guest/rootfs \
#          U3_ROOTFS_IMG=/tmp/uml-seccomp-guest/rootfs.img \
#          tools/research/uml-seccomp/device/run_device_matrix.sh --prepare
#   4) 一键跑矩阵（每 case 独立 config/start/poll/pull/judge 闭环）：
#        DEVICE_SERIAL=<serial> tools/research/uml-seccomp/device/run_device_matrix.sh --run
#      产物：tools/research/uml-seccomp/device/build/out/<ts>/<case>/{
#        console.log, exit.txt, harness.meta, verdict.txt}
#   5) 聚合（device-b 车道 cases doc + android15 profile；BLOCKED 行按 unknown 落；产物在 device/build/out 下，git-ignored）：
#        python tools/research/uml-seccomp/matrix.py \
#          --cases tools/research/uml-seccomp/fixtures/matrix/cases-device-b.json \
#          --run-dir tools/research/uml-seccomp/device/build/out/<ts>/<case> ... \
#          --mode aosp \
#          --profile tools/research/uml-seccomp/profile/generated/aosp-app-android15-arm64.json \
#          --format markdown
#
# 单 case 通道：--run --case <id>。离线重判（不碰设备）：--judge --outdir <ts>。
#
# 每 case 闭环（本脚本自动化，主会话不需要手动敲）：
#   a. 清场：run-as rm -f files/uml-exit.txt files/uml-stop；
#   b. 写 config：files/uml-config.json（run-as stdin 管道）。args 是【整体替换】数组
#      （uml-runner README §5 机制），支持 ${stubExe}/${rootfs} 占位符；kernel 字段
#      【不支持占位符】——脚本用实测绝对路径（pm path → base.apk → lib/arm64 解析
#      nativeLibraryDir；SMP case 指向 libuml_kernel_smp.so）；
#   c. 启动：adb shell am start -S --activity-clear-task -n <pkg>/.RunnerActivity；
#   d. 轮询：run-as 探 files/uml-exit.txt，每 2s 一次，超过 CASE_TIMEOUT(默认 90s)
#      判超时：touch files/uml-stop + am force-stop；
#   e. 取证：pull uml-console.log（头部元信息+argv+内核 stdout/stderr 镜像）与
#      uml-exit.txt（exit_code / signal_guess / reason / duration_ms）；
#   f. 判定（exit code + console 标记，标记来自 cases-device-b.json / cases.json）：
#        PASS   = exit_code=0 且全部标记命中（seccomp-fastpath 额外要求
#                 “Userspace mode: SECCOMP”——快路径真的落地，不是 PTRACE 回退）
#        FAIL   = 非零 exit / 标记缺失 / 超时，附三分法 triage：
#          SIGSYS(159/signal_guess=31/Bad system call) = filter TRAP
#            → 查 profile/generated/aosp-app-android15-arm64.json 的 syscalls 反集
#              （enforce 模式无名字；要名字走 run_device.sh trace 通道）
#          “SECCOMP userspace requested but not functional” = close_range/seccomp
#            probe 失败 fatal（R1 §5.1.5：先 uname -r ≥5.9 再查厂商 SELinux）
#          errno=38 / “Function not implemented” = 内核缺（5.10 无 epoll_pwait2/
#            futex_waitv/landlock/mseal）——不是 filter 问题（35 面放行该号只
#            产生 ENOSYS，不产生 SIGSYS）
#        BLOCKED= 结构性不可跑（通道限制/资产缺失），不启动内核，写
#          harness.meta STATUS=blocked，聚合矩阵落 unknown。
# ============================================================================
#
# 判定阈值与超时只在本脚本；不改产品、不改 profile、不做任何设备写操作
# （files/ 下的 config/console/exit 之外无副作用）。

set -u
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... paths un-mangled

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SECCOMP_ROOT=$(CDPATH= cd -- "$HERE/.." && pwd)          # tools/research/uml-seccomp
REPO_ROOT=$(CDPATH= cd -- "$SECCOMP_ROOT/../../.." && pwd)

RUNNER_PKG=${RUNNER_PKG:-dev.orchestella.umlrunner}
DEVICE_SERIAL=${DEVICE_SERIAL:-}
CASE_TIMEOUT=${CASE_TIMEOUT:-90}
POLL_INTERVAL=${POLL_INTERVAL:-2}
# Device evidence never enters the repository: the default output lives under
# a `build/` directory (git-ignored at every level) and console logs are
# additionally covered by the global `*.log` rule.
OUT_ROOT=${OUT_ROOT:-$HERE/build/out}
PROFILE_REL="tools/research/uml-seccomp/profile/generated/aosp-app-android15-arm64.json"
PROFILE_PATH="$REPO_ROOT/$PROFILE_REL"
CASES_DEVICE_B="$SECCOMP_ROOT/fixtures/matrix/cases-device-b.json"

ADB=${ADB:-}
if [ -z "$ADB" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB=adb
    elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb.exe"
    elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb.exe" ]; then
        ADB="$ANDROID_SDK_ROOT/platform-tools/adb.exe"
    else
        ADB=adb   # fail with a clear message only when a device op is attempted
    fi
fi

usage() {
    sed -n '2,80p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

MODE=""
ONLY_CASE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --plan|--list) MODE=plan; shift ;;
        --run) MODE=run; shift ;;
        --prepare) MODE=prepare; shift ;;
        --judge) MODE=judge; shift ;;
        --case) ONLY_CASE=${2:-}; shift 2 ;;
        --outdir) OUT_ROOT=${2:-}; shift 2 ;;
        --help|-h) usage ;;
        *) echo "run_device_matrix: unknown argument $1" >&2; usage ;;
    esac
done
[ -n "$MODE" ] || MODE=plan   # default: the zero-touch plan

say() { printf '%s\n' "$*"; }
die() { printf 'RUN_DEVICE_MATRIX_ERROR: %s\n' "$*" >&2; exit 2; }
require_serial() {
    [ -n "$DEVICE_SERIAL" ] || die "DEVICE_SERIAL is empty: this mode touches a device; export DEVICE_SERIAL=<serial> (or use --plan for the zero-touch plan)"
}
dsh() { "$ADB" -s "$DEVICE_SERIAL" shell "$@"; }          # adb shell, serial bound

# ---------------------------------------------------------------------------
# The 12-case table (mirrors fixtures/matrix/cases.json + cases-device-b.json).
# Per case: <id>|<class>|<kernel-key>|<markers space-separated>|<args-key>
#   class RUN            = runner channel executes it (with the args variant)
#   class BLOCKED        = structurally unservable by the runner channel; the
#                          row is marked blocked with the reason, never started
#   kernel up|smp|-      = which kernel file the config's kernel field names
#   args-key base|smp2|smp4|ubd0 = the args-array variant (whole replacement)
# ---------------------------------------------------------------------------
CASES_TABLE="hostfs-boot|RUN|up|CAP hostfs=ok|base
ubd0-boot|RUN|up|CAP ubd0=ok|ubd0
vector-net|BLOCKED|-|CAP vector=ok|-
ncpus-2|RUN|smp|CAP smp=2|smp2
ncpus-4|RUN|smp|CAP smp=4|smp4
mconsole|BLOCKED|-|CAP mconsole=ok|-
sigchld|RUN|up|CAP sigchld=ok|base
clone|RUN|up|CAP clone=ok|base
mount|RUN|up|CAP mount=ok|base
personality|RUN|up|CAP personality=ok|base
setrlimit|RUN|up|CAP setrlimit=ok|base
seccomp-fastpath|RUN|up|CAP hostfs=ok;Userspace mode: SECCOMP|base"

blocked_reason() {
    case "$1" in
        vector-net) echo "runner-channel-cannot-pass-vec0-fd (needs host socketpair; use the adb-shell channel per device/run_device.sh + run_with_fd.py)" ;;
        mconsole)   echo "runner-channel-cannot-host-mconsole-probe (needs a host-side client inside the run window; use the adb-shell channel per device/run_device.sh + mconsole_probe.py)" ;;
        *) echo "unknown-case" ;;
    esac
}

# The args-array variant (whole replacement; ${stubExe}/${rootfs} placeholders
# are expanded by the runner itself — README §5). init=/init points at the U3
# guest init (the CAP probe); con0=null,fd:1 is the proven device-b console
# form; printk.time=1 keeps T1 timestamps; seccomp=on is the kernel fast path.
case_args() {
    case "$1" in
        base)
            printf '%s\n' \
                "mem=512M" "rw" "init=/init" "con=null" "con0=null,fd:1" \
                'stub_exe=${stubExe}' "rootfstype=hostfs" 'rootflags=${rootfs}' \
                "seccomp=on" "panic=1" "printk.time=1" ;;
        smp2)
            printf '%s\n' \
                "mem=512M" "rw" "init=/init" "con=null" "con0=null,fd:1" \
                'stub_exe=${stubExe}' "rootfstype=hostfs" 'rootflags=${rootfs}' \
                "seccomp=on" "panic=1" "printk.time=1" "ncpus=2" ;;
        smp4)
            printf '%s\n' \
                "mem=512M" "rw" "init=/init" "con=null" "con0=null,fd:1" \
                'stub_exe=${stubExe}' "rootfstype=hostfs" 'rootflags=${rootfs}' \
                "seccomp=on" "panic=1" "printk.time=1" "ncpus=4" ;;
        ubd0)
            printf '%s\n' \
                "mem=512M" "rw" "init=/init" "con=null" "con0=null,fd:1" \
                'stub_exe=${stubExe}' 'ubd0=${rootfs}' "root=/dev/ubda" \
                "rootfstype=ext4" "seccomp=on" "panic=1" "printk.time=1" ;;
        *) die "unknown args variant $1" ;;
    esac
}

case_kernel_key()   { printf '%s' "$1" | cut -d'|' -f3; }
case_markers()      { printf '%s' "$1" | cut -d'|' -f4 | tr ';' '\n'; }
case_args_key()     { printf '%s' "$1" | cut -d'|' -f5; }

# ---------------------------------------------------------------------------
# Plan mode: the zero-touch 12-case plan (no adb, no device).
# ---------------------------------------------------------------------------
plan() {
    say "== U3 12-case device-b matrix plan (runner channel; DEVICE_SERIAL empty = zero touch) =="
    say "runner pkg : $RUNNER_PKG"
    say "profile    : $PROFILE_REL (API 35; device-b domain inherits it from zygote, R1 §5.1.3)"
    say ""
    printf '%-18s %-8s %-20s %-40s %s\n' "case" "class" "kernel" "markers" "args-variant / judgment"
    while IFS= read -r row; do
        id=$(printf '%s' "$row" | cut -d'|' -f1)
        cls=$(printf '%s' "$row" | cut -d'|' -f2)
        kkey=$(case_kernel_key "$row")
        markers=$(case_markers "$row" | tr '\n' ',')
        akey=$(case_args_key "$row")
        kernel="-"; [ "$kkey" = "up" ] && kernel="libuml_kernel.so"
        [ "$kkey" = "smp" ] && kernel="libuml_kernel_smp.so"
        printf '%-18s %-8s %-20s %-40s %s\n' "$id" "$cls" "$kernel" "$markers" "$akey"
    done <<EOF2
$CASES_TABLE
EOF2
    say ""
    say "class RUN      : runnable on the runner channel with the args variant"
    say "                 (pre-checked per case: ubd0-boot needs files/rootfs.img,"
    say "                  ncpus-* need libuml_kernel_smp.so in nativeLibraryDir;"
    say "                  missing → BLOCKED missing-asset, kernel never started)"
    say "class BLOCKED  : structurally unservable by the runner channel:"
    while IFS= read -r row; do
        cls=$(printf '%s' "$row" | cut -d'|' -f2)
        [ "$cls" = "BLOCKED" ] || continue
        id=$(printf '%s' "$row" | cut -d'|' -f1)
        say "  - $id: $(blocked_reason "$id")"
    done <<EOF3
$CASES_TABLE
EOF3
    say ""
    say "pass criteria  : exit_code=0 AND all markers in console.log"
    say "                 (seccomp-fastpath additionally requires 'Userspace mode: SECCOMP')"
    say "fail triage    : SIGSYS → filter TRAP (check the profile reverse set)"
    say "                 'SECCOMP ... not functional' → close_range/probe fatal"
    say "                 errno=38/Function not implemented → 5.10 kernel-missing"
    say "                 (epoll_pwait2/futex_waitv/landlock/mseal) — NOT the filter"
    say "next           : export DEVICE_SERIAL=<serial> then --prepare (assets) and --run"
}

# ---------------------------------------------------------------------------
# Device context resolution (run/prepare modes only).
# ---------------------------------------------------------------------------
resolve_lib_dir() {
    # nativeLibraryDir is not addressable by a placeholder inside the config's
    # kernel field (README §5: kernel supports no placeholders) → measure it:
    # pm path → base.apk → sibling lib/arm64 (extractNativeLibs layout).
    local apk lib
    apk=$(dsh "pm path $RUNNER_PKG" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
    [ -n "$apk" ] || die "pm path returned nothing for $RUNNER_PKG (installed?)"
    case "$apk" in
        */base.apk) lib="${apk%base.apk}lib/arm64" ;;
        *) die "unexpected apk path: $apk (split-APK layout needs a manual RUNNER_NATIVE_LIB_DIR)" ;;
    esac
    if [ -n "${RUNNER_NATIVE_LIB_DIR:-}" ]; then
        lib="$RUNNER_NATIVE_LIB_DIR"
    fi
    dsh "ls $lib/libuml_stub.so" >/dev/null 2>&1 \
        || die "cannot see $lib/libuml_stub.so on device (resolve failed; set RUNNER_NATIVE_LIB_DIR)"
    printf '%s' "$lib"
}

resolve_files_dir() {
    # run-as lands in the package data dir; filesDir is its /files.
    local home
    home=$(dsh "run-as $RUNNER_PKG pwd" | tr -d '\r' | head -n 1)
    [ -n "$home" ] || die "run-as pwd failed (debuggable runner installed?)"
    printf '%s/files' "$home"
}

device_field() {  # device_field <file-key> <path-on-device>
    case "$1" in
        kernel-host) dsh "uname -r" | tr -d '\r' ;;
        kernel-arch) dsh "uname -m" | tr -d '\r' ;;
        sha)
            # toybox sha256sum; the lib dir is world-readable, files/ needs run-as
            case "$2" in
                lib/*) dsh "sha256sum $2" 2>/dev/null | tr -d '\r' | cut -d' ' -f1 ;;
                *) dsh "run-as $RUNNER_PKG sha256sum $2" 2>/dev/null | tr -d '\r' | cut -d' ' -f1 ;;
            esac
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Per-case judgment: verdict + triage (the R1 §5.1.5 three-way reading).
# ---------------------------------------------------------------------------
judge_run() {
    # judge_run <console> <exit> <markers-file> <timed_out>
    # prints: VERDICT=<PASS|FAIL> REASON=<...> TRIAGE=<...>
    local console=$1 exitfile=$2 markersfile=$3 timedout=$4
    local rc=0 signal="" line marker
    if [ ! -s "$exitfile" ]; then
        say "VERDICT=FAIL"
        say "REASON=harness-no-exit (uml-exit.txt missing/empty — the runner did not record an exit)"
        say "TRIAGE=none"
        return
    fi
    rc=$(sed -n 's/^exit_code=//p' "$exitfile" | head -n 1 | tr -d '\r')
    signal=$(sed -n 's/^signal_guess=//p' "$exitfile" | head -n 1 | tr -d '\r')
    if [ -n "$signal" ] && [ "$signal" = "31" ] || [ "$rc" = "159" ] \
            || grep -Eq 'Bad system call|SIGSYS' "$console" 2>/dev/null; then
        say "VERDICT=FAIL"
        say "REASON=sigsys (exit_code=$rc signal_guess=$signal)"
        say "TRIAGE=sigsys-filter-trap: a host syscall hit the SECCOMP_RET_TRAP default — check the reverse set of $PROFILE_REL (enforce mode carries no name; collect it via device/run_device.sh trace); this is a filter verdict, NOT a missing kernel"
        return
    fi
    if grep -Eq 'SECCOMP userspace requested but not functional|SECCOMP.*not functional' "$console" 2>/dev/null; then
        say "VERDICT=FAIL"
        say "REASON=seccomp-fatal"
        say "TRIAGE=seccomp-fatal: the kernel's seccomp fast-path probe failed (close_range/seccomp(2), R1 §5.1.5) — confirm the host kernel is >= 5.9, then look at vendor SELinux tightening; NOT a profile problem"
        return
    fi
    if grep -Eq 'errno=38|Function not implemented|ENOSYS' "$console" 2>/dev/null; then
        say "VERDICT=FAIL"
        say "REASON=enosys-seen"
        say "TRIAGE=enosys-kernel-missing: the 5.10 host kernel lacks epoll_pwait2/futex_waitv/landlock/mseal; the API 35 filter ALLOWS those numbers, so this is an errno fact (kernel absence), never SIGSYS — check callers before suspecting the filter (R1 §2.4)"
        return
    fi
    if grep -q 'Kernel panic' "$console" 2>/dev/null; then
        say "VERDICT=FAIL"
        say "REASON=kernel-panic"
        say "TRIAGE=kernel-panic: read the panic line in console.log (init failure vs trap); UM_INIT_ERROR is the fastboot-init structured failure, CAP probes failing show as marker-missing instead"
        return
    fi
    if [ "$timedout" = "1" ]; then
        say "VERDICT=FAIL"
        say "REASON=timeout (${CASE_TIMEOUT}s; uml-stop sent + am force-stop)"
        say "TRIAGE=timeout: the guest never reached a marker or exit — compare with the boot-form runs (wave2 §3); if 'Userspace mode: PTRACE' appears, the fast path fell back"
        return
    fi
    if [ "$rc" != "0" ]; then
        say "VERDICT=FAIL"
        say "REASON=exit-$rc"
        say "TRIAGE=nonzero-exit: read console.log tail; map against the triage table (SIGSYS/ENOSYS/SECCOMP-fatal) before blaming the guest"
        return
    fi
    while IFS= read -r marker; do
        [ -n "$marker" ] || continue
        if ! grep -qF -- "$marker" "$console" 2>/dev/null; then
            say "VERDICT=FAIL"
            say "REASON=marker-missing: $marker"
            say "TRIAGE=marker-missing: boot reached exit 0 but the capability line is absent — for ncpus rows check CAP smp=<n> (the guest probe prints it from /proc/cpuinfo); for seccomp-fastpath a 'Userspace mode: PTRACE' line means the fast path did not engage"
            return
        fi
    done < "$markersfile"
    say "VERDICT=PASS"
    say "REASON=exit-0-and-markers"
    say "TRIAGE=none"
}

# ---------------------------------------------------------------------------
# Prepare mode: push the U3 guest rootfs dir + ext4 image (wave2 §3 channel).
# ---------------------------------------------------------------------------
prepare() {
    require_serial
    [ -n "${U3_ROOTFS_DIR:-}" ] && [ -d "$U3_ROOTFS_DIR" ] && [ -f "$U3_ROOTFS_DIR/init" ] \
        || die "U3_ROOTFS_DIR must point at the built guest rootfs (guest/build_guest.sh output with init)"
    local files lib tarball imgtmp
    files=$(resolve_files_dir)
    lib=$(resolve_lib_dir)
    say "device filesDir : $files"
    say "device lib dir  : $lib"
    tarball=$(mktemp -u).tar.gz
    if [ -n "${U3_ROOTFS_TGZ:-}" ] && [ -f "$U3_ROOTFS_TGZ" ]; then
        # Preferred: the tarball packed by guest/build_guest.sh on the POSIX host
        # (real exec bits). A Windows/DrvFs-side pack loses +x and the guest init
        # dies with error -13 on device.
        cp "$U3_ROOTFS_TGZ" "$tarball" || die "cannot read U3_ROOTFS_TGZ"
    else
        # Fallback: pack here, forcing exec bits (GNU tar --mode) because a
        # DrvFs checkout cannot carry them.
        tar --mode='u+rwx,go+rwx' -czf "$tarball" -C "$U3_ROOTFS_DIR" . \
            || tar -czf "$tarball" -C "$U3_ROOTFS_DIR" . || die "tar failed for U3_ROOTFS_DIR"
    fi
    # Windows adb can't read MSYS-style paths (/tmp/...): convert for the push arg.
    tarball_push=$(command -v cygpath >/dev/null 2>&1 && cygpath -m "$tarball" || printf '%s' "$tarball")
    "$ADB" -s "$DEVICE_SERIAL" push "$tarball_push" /data/local/tmp/uml-u3-rootfs.tar.gz >/dev/null \
        || die "push rootfs tarball failed"
    dsh "run-as $RUNNER_PKG mkdir -p files/rootfs"
    dsh "cat /data/local/tmp/uml-u3-rootfs.tar.gz | run-as $RUNNER_PKG sh -c 'gzip -dc | tar -xf - -C files/rootfs'" \
        || die "on-device rootfs untar failed"
    dsh "rm -f /data/local/tmp/uml-u3-rootfs.tar.gz"
    dsh "run-as $RUNNER_PKG ls -l files/rootfs/init" || die "rootfs/init missing after untar"
    rm -f "$tarball"
    if [ -n "${U3_ROOTFS_IMG:-}" ] && [ -f "$U3_ROOTFS_IMG" ]; then
        imgtmp=$(mktemp -u).img
        cp "$U3_ROOTFS_IMG" "$imgtmp" || die "cannot read U3_ROOTFS_IMG"
        imgtmp_push=$(command -v cygpath >/dev/null 2>&1 && cygpath -m "$imgtmp" || printf '%s' "$imgtmp")
        "$ADB" -s "$DEVICE_SERIAL" push "$imgtmp_push" /data/local/tmp/uml-u3-rootfs.img >/dev/null \
            || die "push rootfs.img failed"
        dsh "cat /data/local/tmp/uml-u3-rootfs.img | run-as $RUNNER_PKG sh -c 'cat > files/rootfs.img'" \
            || die "on-device rootfs.img copy failed"
        dsh "rm -f /data/local/tmp/uml-u3-rootfs.img"
        dsh "run-as $RUNNER_PKG ls -l files/rootfs.img"
        rm -f "$imgtmp"
    else
        say "NOTE: U3_ROOTFS_IMG not provided; ubd0-boot will be BLOCKED (missing-asset) until pushed"
    fi
    say "PREPARE_OK rootfs=$( [ -n "${U3_ROOTFS_DIR:-}" ] && echo pushed ) img=$( [ -n "${U3_ROOTFS_IMG:-}" ] && echo pushed || echo skipped )"
}

# ---------------------------------------------------------------------------
# Run mode: the per-case closed loop.
# ---------------------------------------------------------------------------
write_config() {
    # write_config <run-dir> <kernel-path> <rootfs-field-path> <args-key>
    local run_dir=$1 kernel=$2 rootfs=$3 akey=$4
    local json args first=1
    json="{\"kernel\":\"$kernel\",\"rootfs\":\"$rootfs\",\"args\":["
    while IFS= read -r arg; do
        [ -n "$first" ] && first="" || json="$json,"
        json="$json\"$arg\""
    done <<EOFARGS
$(case_args "$akey")
EOFARGS
    json="$json]}"
    printf '%s' "$json" > "$run_dir/uml-config.json"
    # Windows adb hangs forever on `adb shell cmd < file` stdin redirects; inline the
    # payload as base64 on the command line instead (toybox base64 -d decodes it).
    local b64
    b64=$(base64 -w0 "$run_dir/uml-config.json")
    dsh "run-as $RUNNER_PKG sh -c 'echo $b64 | base64 -d > files/uml-config.json'" \
        || die "config push failed"
}

run_case() {
    # run_case <row> <ts>
    local row=$1 ts=$2
    local id cls kkey akey kernel rootfs_path markers_file run_dir lib files
    id=$(printf '%s' "$row" | cut -d'|' -f1)
    cls=$(printf '%s' "$row" | cut -d'|' -f2)
    kkey=$(case_kernel_key "$row")
    akey=$(case_args_key "$row")
    run_dir="$OUT_ROOT/$ts/$id"
    mkdir -p "$run_dir"
    markers_file="$run_dir/markers.txt"
    case_markers "$row" > "$markers_file"

    # Structurally unservable rows: honest BLOCKED, kernel never started.
    if [ "$cls" = "BLOCKED" ]; then
        say "[$id] BLOCKED: $(blocked_reason "$id")"
        write_meta "$run_dir" "$id" blocked "" 0 0 "" "$run_dir/console.log"
        write_verdict "$run_dir" "BLOCKED" "$(blocked_reason "$id")" "none"
        return
    fi
    if [ -n "$ONLY_CASE" ] && [ "$ONLY_CASE" != "$id" ]; then
        return
    fi

    lib=$(resolve_lib_dir)
    files=$(resolve_files_dir)

    # Conditional assets: missing → BLOCKED (never a fake FAIL).
    if [ "$kkey" = "smp" ] && ! dsh "ls $lib/libuml_kernel_smp_noinitrd.so" >/dev/null 2>&1; then
        say "[$id] BLOCKED: missing-smp-kernel ($lib/libuml_kernel_smp_noinitrd.so not in nativeLibraryDir)"
        write_meta "$run_dir" "$id" blocked "missing-smp-kernel" 0 0 "" "$run_dir/console.log"
        write_verdict "$run_dir" "BLOCKED" "missing-smp-kernel" "none"
        return
    fi
    if [ "$akey" = "ubd0" ] && ! dsh "run-as $RUNNER_PKG sh -c 'test -f files/rootfs.img'" >/dev/null 2>&1; then
        say "[$id] BLOCKED: missing-rootfs-img (files/rootfs.img not pushed; --prepare with U3_ROOTFS_IMG)"
        write_meta "$run_dir" "$id" blocked "missing-rootfs-img" 0 0 "" "$run_dir/console.log"
        write_verdict "$run_dir" "BLOCKED" "missing-rootfs-img" "none"
        return
    fi

    # Measured absolute paths: kernel has NO placeholder support (README §5).
    # RUN cases boot the U3 guest rootfs via external-rootfs shape → the built-in
    # initramfs kernels (libuml_kernel.so / _smp.so) carry their own U1 root and
    # never reach the CAP probes; the *noinitrd* builds are mandatory here.
    kernel="$lib/libuml_kernel_noinitrd.so"
    [ "$kkey" = "smp" ] && kernel="$lib/libuml_kernel_smp_noinitrd.so"
    rootfs_path="$files/rootfs"
    [ "$akey" = "ubd0" ] && rootfs_path="$files/rootfs.img"

    say "[$id] start (kernel=$(basename "$kernel") args=$akey timeout=${CASE_TIMEOUT}s)"
    dsh "run-as $RUNNER_PKG sh -c 'rm -f files/uml-exit.txt files/uml-stop'"
    : > "$run_dir/console.log"
    write_config "$run_dir" "$kernel" "$rootfs_path" "$akey"

    local start_ms end_ms
    start_ms=$(date +%s%3N 2>/dev/null || echo 0)
    dsh "am start -S --activity-clear-task -n $RUNNER_PKG/.RunnerActivity" >/dev/null \
        || die "am start failed for $RUNNER_PKG"

    local timedout=0 waited=0
    while [ "$waited" -lt "$CASE_TIMEOUT" ]; do
        sleep "$POLL_INTERVAL"
        waited=$((waited + POLL_INTERVAL))
        if dsh "run-as $RUNNER_PKG sh -c 'test -f files/uml-exit.txt'" >/dev/null 2>&1; then
            break
        fi
    done
    if ! dsh "run-as $RUNNER_PKG sh -c 'test -f files/uml-exit.txt'" >/dev/null 2>&1; then
        timedout=1
        dsh "run-as $RUNNER_PKG sh -c 'touch files/uml-stop'" >/dev/null 2>&1
        sleep 3
        dsh "am force-stop $RUNNER_PKG" >/dev/null 2>&1
    fi
    end_ms=$(date +%s%3N 2>/dev/null || echo 0)

    # Evidence: console mirror + exit record.
    dsh "run-as $RUNNER_PKG cat files/uml-console.log" > "$run_dir/console.log" 2>/dev/null
    dsh "run-as $RUNNER_PKG cat files/uml-exit.txt" > "$run_dir/exit.txt" 2>/dev/null

    local verdict reason triage rc
    verdict=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$markers_file" "$timedout" | sed -n 's/^VERDICT=//p')
    reason=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$markers_file" "$timedout" | sed -n 's/^REASON=//p')
    triage=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$markers_file" "$timedout" | sed -n 's/^TRIAGE=//p')
    rc=$(sed -n 's/^exit_code=//p' "$run_dir/exit.txt" 2>/dev/null | head -n 1 | tr -d '\r')
    local signal=""
    [ -f "$run_dir/exit.txt" ] && signal=$(sed -n 's/^signal_guess=//p' "$run_dir/exit.txt" | head -n 1 | tr -d '\r')
    [ -n "$rc" ] || rc=""

    write_meta "$run_dir" "$id" ok "" "$rc" "${signal:-0}" "$((end_ms - start_ms))" "$run_dir/console.log" "$timedout"
    write_verdict "$run_dir" "$verdict" "$reason" "$triage"
    say "[$id] $verdict ($reason) console=$run_dir/console.log"
    [ "$triage" != "none" ] && say "[$id]   triage: $triage"
}

write_meta() {
    # write_meta <run-dir> <case> <status> <unsupported-reason> <rc> <signal> <duration-ms> <console> [timedout]
    local dir=$1 case_id=$2 status=$3 unsupported=$4 rc=$5 signal=$6 duration=$7 console=$8
    local timedout=${9:-0}
    local profile_sha="" kernel_sha="" guest_sha="" host_arch="" host_kernel=""
    profile_sha=$(sha256sum "$PROFILE_PATH" 2>/dev/null | cut -d' ' -f1)
    host_arch=$(device_field kernel-arch 2>/dev/null || echo unknown)
    host_kernel=$(device_field kernel-host 2>/dev/null || echo unknown)
    {
        echo "CASE=$case_id"
        echo "STATUS=$status"
        echo "UNSUPPORTED_REASON=$unsupported"
        echo "PROFILE=aosp-app-android15-arm64"
        echo "PROFILE_SHA256=${profile_sha:-unknown}"
        echo "MODE=aosp"
        echo "CHANNEL=runner-app"
        echo "HOST_ARCH=${host_arch:-unknown}"
        echo "HOST_KERNEL=${host_kernel:-unknown}"
        echo "RC=${rc:-}"
        echo "SIGNAL=${signal:-0}"
        echo "TIMED_OUT=$timedout"
        echo "DURATION_MS=${duration:-}"
    } > "$dir/harness.meta"
}

write_verdict() {
    # write_verdict <run-dir> <verdict> <reason> <triage>
    {
        echo "VERDICT=$2"
        echo "REASON=$3"
        echo "TRIAGE=$4"
    } > "$1/verdict.txt"
}

run() {
    require_serial
    [ -f "$PROFILE_PATH" ] || die "profile missing: $PROFILE_PATH"
    command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || die "adb not found (set ADB or ANDROID_HOME)"
    dsh "true" 2>/dev/null || die "device $DEVICE_SERIAL not reachable via adb"
    local ts lib files
    ts=$(date +%Y%m%d-%H%M%S)
    lib=$(resolve_lib_dir)
    files=$(resolve_files_dir)
    say "== U3 device-b matrix run (serial=$DEVICE_SERIAL pkg=$RUNNER_PKG) =="
    say "lib dir : $lib"
    say "filesDir: $files"
    say "out     : $OUT_ROOT/$ts"
    say ""
    local summary_pass=0 summary_fail=0 summary_blocked=0
    while IFS= read -r row; do
        run_case "$row" "$ts"
        local id verdict
        id=$(printf '%s' "$row" | cut -d'|' -f1)
        [ -n "$ONLY_CASE" ] && [ "$ONLY_CASE" != "$id" ] && continue
        verdict=$(sed -n 's/^VERDICT=//p' "$OUT_ROOT/$ts/$id/verdict.txt" 2>/dev/null)
        case "$verdict" in
            PASS) summary_pass=$((summary_pass + 1)) ;;
            FAIL) summary_fail=$((summary_fail + 1)) ;;
            *) summary_blocked=$((summary_blocked + 1)) ;;
        esac
    done <<EOFROWS
$CASES_TABLE
EOFROWS
    say ""
    say "== summary: PASS=$summary_pass FAIL=$summary_fail BLOCKED=$summary_blocked =="
    say "aggregate with matrix.py (see the SOP header, step 5); raw evidence stays in"
    say "$OUT_ROOT/$ts (console logs are .log = git-ignored; do not commit device evidence)."
}

judge() {
    # Offline re-judge from pulled dirs: --judge [--outdir DIR]  (--case accepted as an alias for --outdir)
    local ts_dir=${OUT_ROOT:-${ONLY_CASE:-}}
    [ -n "$ts_dir" ] || die "--judge needs --outdir <ts dir containing per-case subdirs>"
    say "== offline re-judge over $ts_dir =="
    for v in "$ts_dir"/*/verdict.txt; do
        [ -f "$v" ] || continue
        say "$(basename "$(dirname "$v")"): $(tr '\n' ' ' < "$v")"
    done
}

case "$MODE" in
    plan) plan ;;
    prepare) prepare ;;
    run) run ;;
    judge) judge ;;
    *) usage ;;
esac
