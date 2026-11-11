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
# ----------------------------------------------------------------------------
# adb-shell 通道（--channel adb-shell；仅 vector-net / mconsole 两 case 归此
# 通道，其余 10 case 恒走上述 runner 通道）。细节与一手判据见
# device/adbshell-channel.md。S0-S4 与 runner 步骤 0-5 完全独立：
#
#   S0) WSL 交叉编译静态产物（glibc 静态 + 0 GNU_PROPERTY 校验内嵌；一次性）：
#         wsl bash tools/research/uml-seccomp/device/build_adbshell_binaries.sh
#       产物（git-ignored）：device/build/binaries/{seccomp_launch,run_with_fd,
#       mconsole_probe,probe_shell}
#   S1) 5 分钟探针（shell 域 exec / socketpair / pathname-bind 一手实证）：
#         DEVICE_SERIAL=<serial> tools/research/uml-seccomp/device/run_device_matrix.sh --probe
#       判读：PROBE exec_ok=1 → 通道可行；PROBE_BIND ok=1 的目录 → mconsole
#       socket 落点（需要时 UML_MCONSOLE_DIR=<dir>/ 覆盖，尾斜杠必须）；
#       全 ok=0 → mconsole 本设备不可跑（AOSP 基线 shell 无 shell_data_file
#       sock_file 权限），按 FAIL(marker-missing)+探针证据落判。
#   S2) 跑两 case（staging 自动完成：binaries/uml_stub push、内核 cat 自 runner
#       nativeLibraryDir 并 sha 双读、filter.txt 由 gen_profile.py 宿主生成、
#       U3 rootfs 解包到 WORKDIR —— 与 runner 步骤 0 的 rootfs 产物同源）：
#         DEVICE_SERIAL=<serial> U3_ROOTFS_TGZ=<posix-packed rootfs.tar.gz> \
#           tools/research/uml-seccomp/device/run_device_matrix.sh --run --channel adb-shell
#         （单 case：追加 --case vector-net | --case mconsole）
#   S3) 判定闭环与 runner 通道共用 judge_run（SIGSYS/ENOSYS/SECCOMP-fatal/标记
#       三分法不变）；console.log/exit.txt 由设备侧脚本直写后 pull，exit.txt
#       字段与 uml-exit.txt 同形；harness.meta 落 CHANNEL=adb-shell。
#   S4) 聚合：与步骤 5 相同（matrix.py），--run-dir 指向同一 out/<ts> 下对应
#       case 目录。
#
#   设备侧布局：WORKDIR=/data/local/tmp/uml-adbshell/{bin,uml_kernel,filter.txt,
#   guest-rootfs,tmp,.uml/<umid>/mconsole,runs/<ts>/<case>}。mconsole socket
#   路径由 uml_dir+umid 决定（mconsole= 参数只认 notify: 形态，不能指定路径
#   —— v5.10 mconsole_kern.c 一手勘误，详见 device/adbshell-channel.md §3.2）。
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
    sed -n '2,101p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

MODE=""
ONLY_CASE=""
CHANNEL=runner
while [ $# -gt 0 ]; do
    case "$1" in
        --plan|--list) MODE=plan; shift ;;
        --run) MODE=run; shift ;;
        --prepare) MODE=prepare; shift ;;
        --judge) MODE=judge; shift ;;
        --probe) MODE=probe; shift ;;
        --channel) CHANNEL=${2:-}; shift 2 ;;
        --case) ONLY_CASE=${2:-}; shift 2 ;;
        --outdir) OUT_ROOT=${2:-}; shift 2 ;;
        --help|-h) usage ;;
        *) echo "run_device_matrix: unknown argument $1" >&2; usage ;;
    esac
done
[ -n "$MODE" ] || MODE=plan   # default: the zero-touch plan

say() { printf '%s\n' "$*"; }
die() { printf 'RUN_DEVICE_MATRIX_ERROR: %s\n' "$*" >&2; exit 2; }
case "$CHANNEL" in
    runner|adb-shell) ;;
    *) die "unknown --channel $CHANNEL (runner|adb-shell)" ;;
esac
require_serial() {
    [ -n "$DEVICE_SERIAL" ] || die "DEVICE_SERIAL is empty: this mode touches a device; export DEVICE_SERIAL=<serial> (or use --plan for the zero-touch plan)"
}
dsh() { "$ADB" -s "$DEVICE_SERIAL" shell "$@"; }          # adb shell, serial bound

# adb-shell channel device layout (all under shell_data_file; see
# device/adbshell-channel.md). Binaries land in $SHELL_WORK/bin, the U3
# rootfs in $SHELL_WORK/guest-rootfs, per-case evidence in runs/<ts>/<case>.
SHELL_WORK=${SHELL_WORK:-/data/local/tmp/uml-adbshell}
# mconsole socket dir (trailing slash required by the uml_dir= grammar).
UML_MCONSOLE_DIR=${UML_MCONSOLE_DIR:-$SHELL_WORK/.uml/}

# ---------------------------------------------------------------------------
# The 12-case table (mirrors fixtures/matrix/cases.json + cases-device-b.json).
# Per case: <id>|<class>|<kernel-key>|<markers space-separated>|<args-key>|<channel>
#   class RUN            = runner channel executes it (with the args variant)
#   class BLOCKED        = structurally unservable by the runner channel; the
#                          row is marked blocked with the reason, never started
#   kernel up|smp|-      = which kernel file the config's kernel field names
#   args-key base|smp2|smp4|ubd0 = the args-array variant (whole replacement)
#   channel runner|adb-shell = the channel that owns the row.  Rows owned by
#                          the adb-shell channel stay BLOCKED on the runner
#                          channel (below) and RUN via --channel adb-shell
#                          (SOP header, S0-S4; device/adbshell-channel.md).
# ---------------------------------------------------------------------------
CASES_TABLE="hostfs-boot|RUN|up|CAP hostfs=ok|base|runner
ubd0-boot|RUN|up|CAP ubd0=ok|ubd0|runner
vector-net|BLOCKED|-|CAP vector=ok|-|adb-shell
ncpus-2|RUN|smp|CAP smp=2|smp2|runner
ncpus-4|RUN|smp|CAP smp=4|smp4|runner
mconsole|BLOCKED|-|CAP mconsole=ok|-|adb-shell
sigchld|RUN|up|CAP sigchld=ok|base|runner
clone|RUN|up|CAP clone=ok|base|runner
mount|RUN|up|CAP mount=ok|base|runner
personality|RUN|up|CAP personality=ok|base|runner
setrlimit|RUN|up|CAP setrlimit=ok|base|runner
seccomp-fastpath|RUN|up|CAP hostfs=ok;Userspace mode: SECCOMP|base|runner"

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
case_channel()      { printf '%s' "$1" | cut -d'|' -f6; }

# ---------------------------------------------------------------------------
# Plan mode: the zero-touch 12-case plan (no adb, no device).
# ---------------------------------------------------------------------------
plan_adbshell() {
    # --plan --channel adb-shell: lists all 12 cases with their channel, so a
    # dry run shows 10 runner rows + the 2 adb-shell rows in one table.
    say "== U3 12-case device-b matrix plan (channel=adb-shell; zero touch) =="
    say "scope      : vector-net + mconsole (this channel); the other 10 rows"
    say "             stay on the runner channel (plan without --channel)"
    say "workdir    : $SHELL_WORK (device, shell_data_file)"
    say "mconsole   : socket = <UML_MCONSOLE_DIR><umid>/mconsole (uml_dir= grammar;"
    say "             the mconsole= kernel param only accepts notify: — see"
    say "             device/adbshell-channel.md §3.2)"
    say "probe      : --probe  (5-min A-1 check: exec / socketpair / pathname-bind)"
    say "binaries   : device/build/binaries (build_adbshell_binaries.sh, WSL, glibc"
    say "             static with 0 GNU_PROPERTY — enforced by the embedded readelf check)"
    say ""
    printf '%-18s %-10s %-11s %-28s %s\n' "case" "channel" "class" "kernel" "markers"
    while IFS= read -r row; do
        id=$(printf '%s' "$row" | cut -d'|' -f1)
        cls=$(printf '%s' "$row" | cut -d'|' -f2)
        kkey=$(case_kernel_key "$row")
        markers=$(case_markers "$row" | tr '\n' ',')
        ch=$(case_channel "$row")
        [ "$ch" = "adb-shell" ] && view=RUN || view="(runner ch)"
        kernel="-"; [ "$kkey" = "up" ] && kernel="libuml_kernel_noinitrd.so"
        [ "$kkey" = "smp" ] && kernel="libuml_kernel_smp_noinitrd.so"
        printf '%-18s %-10s %-11s %-28s %s\n' "$id" "$ch" "$view" "$kernel" "$markers"
    done <<EOF2A
$CASES_TABLE
EOF2A
    say ""
    say "adb-shell rows run via: DEVICE_SERIAL=<serial> ... run_device_matrix.sh \\"
    say "  --run --channel adb-shell [--case vector-net|mconsole]  (SOP header S0-S4)"
    say "markers      : CAP vector=ok / CAP mconsole=ok (guest init prints them;"
    say "               mconsole=ok is appended by the device-side probe on MCONSOLE_OK)"
    say "fail triage  : identical three-way reading (SIGSYS=filter TRAP,"
    say "               ENOSYS=kernel-missing, 'SECCOMP...not functional'=probe fatal)"
    say "mconsole delta: if every PROBE_BIND line is ok=0 (AOSP baseline policy has no"
    say "               shell_data_file:sock_file allow), the row is judged FAIL"
    say "               marker-missing with the probe output as evidence — honest"
    say "               FAIL, never a fake PASS (device/adbshell-channel.md §3.2)"
    say "next         : build_adbshell_binaries.sh (WSL) → --probe → --run --channel adb-shell"
}

plan() {
    if [ "$CHANNEL" = "adb-shell" ]; then
        plan_adbshell
        return
    fi
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
    # write_meta <run-dir> <case> <status> <unsupported-reason> <rc> <signal> <duration-ms> <console> [timedout] [channel]
    local dir=$1 case_id=$2 status=$3 unsupported=$4 rc=$5 signal=$6 duration=$7 console=$8
    local timedout=${9:-0}
    local channel=${10:-runner-app}
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
        echo "CHANNEL=$channel"
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

# ---------------------------------------------------------------------------
# adb-shell channel (--channel adb-shell): the vector-net / mconsole rows run
# as static-ELF processes in the shell domain (uid 2000), driven directly by
# adb.  Feasibility, boot-param derivations and the decision tables live in
# device/adbshell-channel.md; the SOP header (S0-S4) is the operator view.
# The runner-channel 10-case logic above is untouched by everything below.
# ---------------------------------------------------------------------------
winpath() {
    # adb.exe is a Windows binary: hand it Windows-mixed path forms for local
    # args (cygpath -m); POSIX hosts keep the path unchanged.
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

SHELL_BINARIES="seccomp_launch run_with_fd mconsole_probe probe_shell"

probe_mode() {
    # --probe: the 5-minute A-1/A-3 feasibility check (SOP S1).
    require_serial
    command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || die "adb not found (set ADB or ANDROID_HOME)"
    dsh "true" 2>/dev/null || die "device $DEVICE_SERIAL not reachable via adb"
    local f=$HERE/build/binaries/probe_shell
    [ -f "$f" ] || die "$f missing - build first in WSL: device/build_adbshell_binaries.sh (SOP S0)"
    dsh "mkdir -p $SHELL_WORK/bin" || die "cannot create $SHELL_WORK/bin"
    "$ADB" -s "$DEVICE_SERIAL" push "$(winpath "$f")" "$SHELL_WORK/bin/probe_shell" >/dev/null \
        || die "push probe_shell failed"
    dsh "chmod 0755 $SHELL_WORK/bin/probe_shell"
    say "== adb-shell channel probe ($SHELL_WORK/bin/probe_shell) =="
    dsh "$SHELL_WORK/bin/probe_shell" || true
    say "---"
    say "labels: $(dsh "ls -Z $SHELL_WORK/bin" 2>/dev/null | tr -d '\r' | head -n 3)"
    say "read: PROBE exec_ok=1 => the channel is viable; PROBE_BIND ok=1 dirs are"
    say "the mconsole socket candidates (override via UML_MCONSOLE_DIR=<dir>/)."
    say "Decision table: device/adbshell-channel.md section 2."
}

shell_stage() {
    # shell_stage <ts> <native-lib-dir>: push binaries, copy kernel+stub from
    # the runner nativeLibraryDir (same source as the 10 runner cases), host-
    # generate filter.txt, unpack the U3 rootfs.  Idempotent; re-runs re-stage.
    local ts=$1 lib=$2
    local bin_dir=$HERE/build/binaries
    local f
    for f in $SHELL_BINARIES; do
        [ -f "$bin_dir/$f" ] || die "$bin_dir/$f missing - build first in WSL: device/build_adbshell_binaries.sh (SOP S0)"
    done
    mkdir -p "$OUT_ROOT/$ts"

    say "[stage] device layout under $SHELL_WORK"
    dsh "mkdir -p $SHELL_WORK/bin $SHELL_WORK/tmp $SHELL_WORK/runs" \
        || die "cannot create $SHELL_WORK on device"
    dsh "command -v timeout >/dev/null" \
        || die "toybox timeout not found on device (needed by the per-case watchdog)"
    for f in $SHELL_BINARIES; do
        "$ADB" -s "$DEVICE_SERIAL" push "$(winpath "$bin_dir/$f")" "$SHELL_WORK/bin/$f" >/dev/null \
            || die "push $f failed"
        dsh "chmod 0755 $SHELL_WORK/bin/$f"
    done

    say "[stage] kernel + stub from $lib (same assets the runner channel uses)"
    local ksha_src ksha_copy
    dsh "cat $lib/libuml_kernel_noinitrd.so > $SHELL_WORK/uml_kernel.tmp && chmod 0755 $SHELL_WORK/uml_kernel.tmp && mv $SHELL_WORK/uml_kernel.tmp $SHELL_WORK/uml_kernel" \
        || die "kernel copy failed"
    dsh "cat $lib/libuml_stub.so > $SHELL_WORK/bin/uml_stub.tmp && chmod 0755 $SHELL_WORK/bin/uml_stub.tmp && mv $SHELL_WORK/bin/uml_stub.tmp $SHELL_WORK/bin/uml_stub" \
        || die "stub copy failed"
    dsh "chmod 0755 $SHELL_WORK/uml_kernel"
    ksha_src=$(dsh "sha256sum $lib/libuml_kernel_noinitrd.so" | tr -d '\r' | cut -d' ' -f1)
    ksha_copy=$(dsh "sha256sum $SHELL_WORK/uml_kernel" | tr -d '\r' | cut -d' ' -f1)
    [ -n "$ksha_src" ] && [ "$ksha_src" = "$ksha_copy" ] \
        || die "kernel copy sha mismatch (src=$ksha_src copy=$ksha_copy)"
    say "[stage] kernel sha256=$ksha_copy"

    say "[stage] filter.txt (host gen_profile.py, aosp-app-android15-arm64)"
    local PY=""
    if command -v python3 >/dev/null 2>&1; then
        PY=python3
    elif command -v python >/dev/null 2>&1; then
        PY=python
    else
        die "python not found on host (needed for gen_profile.py)"
    fi
    "$PY" "$SECCOMP_ROOT/gen_profile.py" --emit-filter "$PROFILE_PATH" \
        --allow-extra "" --deny-extra "" -o "$OUT_ROOT/$ts/filter.txt" >/dev/null \
        || die "filter generation failed"
    "$ADB" -s "$DEVICE_SERIAL" push "$(winpath "$OUT_ROOT/$ts/filter.txt")" "$SHELL_WORK/filter.txt" >/dev/null \
        || die "push filter.txt failed"
    dsh "$SHELL_WORK/bin/seccomp_launch --print --filter $SHELL_WORK/filter.txt -- x" \
        | tr -d '\r' | grep -q '^seccomp_launch allow=' \
        || die "seccomp_launch --print smoke test failed (static build or filter unusable?)"

    say "[stage] U3 guest rootfs"
    local tarball=""
    if [ -n "${U3_ROOTFS_TGZ:-}" ] && [ -f "${U3_ROOTFS_TGZ}" ]; then
        # Preferred: the tarball packed by guest/build_guest.sh on the POSIX
        # host (real exec bits) — the same artifact the runner channel pushes.
        tarball=$U3_ROOTFS_TGZ
    elif [ -n "${U3_ROOTFS_DIR:-}" ] && [ -d "${U3_ROOTFS_DIR}" ] && [ -f "${U3_ROOTFS_DIR}/init" ]; then
        # Fallback: pack here, forcing exec bits (GNU tar --mode) because a
        # DrvFs checkout cannot carry them (previous round root cause).
        tarball=$OUT_ROOT/$ts/rootfs.tar.gz
        tar --mode='u+rwx,go+rwx' -czf "$tarball" -C "$U3_ROOTFS_DIR" . \
            || tar -czf "$tarball" -C "$U3_ROOTFS_DIR" . \
            || die "tar failed for U3_ROOTFS_DIR"
    else
        die "U3_ROOTFS_TGZ (POSIX-packed rootfs.tar.gz) or U3_ROOTFS_DIR is required"
    fi
    "$ADB" -s "$DEVICE_SERIAL" push "$(winpath "$tarball")" /data/local/tmp/uml-adbshell-rootfs.tar.gz >/dev/null \
        || die "push rootfs tarball failed"
    dsh "rm -rf $SHELL_WORK/guest-rootfs && mkdir -p $SHELL_WORK/guest-rootfs && cd $SHELL_WORK/guest-rootfs && gzip -dc /data/local/tmp/uml-adbshell-rootfs.tar.gz | tar -xf -" \
        || die "on-device rootfs untar failed"
    dsh "rm -f /data/local/tmp/uml-adbshell-rootfs.tar.gz"
    dsh "ls -l $SHELL_WORK/guest-rootfs/init" | tr -d '\r' | grep -q 'rwx' \
        || die "guest-rootfs/init lost its exec bit (pack the rootfs on a POSIX host, SOP runner step 0)"
    say "[stage] OK"
}

gen_device_case_script() {
    # gen_device_case_script <run-dir>: emit the per-case on-device runner.
    # Writes console.raw during the run and folds header+raw into console.log
    # at the end, so the pulled console.log always carries provenance lines.
    local dir=$1
    cat > "$dir/device-case.sh" <<'DEVCASE'
#!/system/bin/sh
# Generated by run_device_matrix.sh --channel adb-shell; do not edit on device.
# Args: CASE TS TMO MAC UMID UMLDIR WORK
set -u
CASE=${1:?}; TS=${2:?}; TMO=${3:?}; MAC=${4:?}; UMID=${5:?}; UMLDIR=${6:?}; WORK=${7:?}
RUN=$WORK/runs/$TS/$CASE
CONSOLE=$RUN/console.log
RAW=$RUN/console.raw
STDERR=$RUN/stderr.log
EXITF=$RUN/exit.txt
PROBELOG=$RUN/mconsole-probe.log
mkdir -p "$RUN" "$WORK/tmp"
export TMPDIR="$WORK/tmp"
export HOME="$WORK"
: > "$RAW"; : > "$STDERR"; : > "$PROBELOG"

START=$(date +%s)
# Kernel args: mem/panic/con0/init are the device-proven runner-channel values;
# stub_exe is the copy from the runner nativeLibraryDir; hostfs rootflags point
# at the pushed U3 rootfs.  NO seccomp=on: the AOSP filter is installed
# host-side by seccomp_launch (run_uml_seccomp.sh aosp-mode shape).
KARGS="mem=512M rw init=/init con0=null,fd:1 stub_exe=$WORK/bin/uml_stub rootfstype=hostfs rootflags=$WORK/guest-rootfs panic=1"
RC=0

if [ "$CASE" = "vector-net" ]; then
    CASE_ARGS="vec0:transport=fd,fd=3,mac=$MAC"
    timeout -k 3 "$TMO" "$WORK/bin/run_with_fd" --fd 3 -- "$WORK/bin/seccomp_launch" \
        --filter "$WORK/filter.txt" --mode enforce --log "$STDERR" -- \
        "$WORK/uml_kernel" $KARGS "$CASE_ARGS" \
        </dev/null >"$RAW" 2>>"$STDERR"
    RC=$?
elif [ "$CASE" = "mconsole" ]; then
    CASE_ARGS="u3_wait_ms=4000 umid=$UMID uml_dir=$UMLDIR"
    WORK="$WORK" UMLDIR="$UMLDIR" UMID="$UMID" RUN="$RUN" RAW="$RAW" PROBELOG="$PROBELOG" \
    timeout -k 3 "$TMO" sh -c '
        set -u
        "$WORK/bin/seccomp_launch" --filter "$WORK/filter.txt" --mode enforce \
            --log "$RUN/stderr.log" -- "$WORK/uml_kernel" \
            mem=512M rw init=/init con0=null,fd:1 \
            stub_exe="$WORK/bin/uml_stub" rootfstype=hostfs \
            rootflags="$WORK/guest-rootfs" panic=1 u3_wait_ms=4000 \
            "umid=$UMID" "uml_dir=$UMLDIR" \
            </dev/null >"$RAW" 2>>"$RUN/stderr.log" &
        KPID=$!
        "$WORK/bin/mconsole_probe" --socket "${UMLDIR}${UMID}/mconsole" \
            --timeout 8 > "$PROBELOG" 2>&1
        if grep -q MCONSOLE_OK "$PROBELOG" 2>/dev/null; then
            echo "CAP mconsole=ok" >> "$RAW"
        fi
        wait "$KPID"
        exit $?
    '
    RC=$?
else
    echo "unknown case: $CASE" >&2
    exit 2
fi

# Orphan guard (the mconsole kernel nests under the inner sh): if timeout
# killed the wrapper, stop the kernel so it cannot outlive the case window.
pkill -f "$WORK/uml_kernel" 2>/dev/null
sleep 1
pkill -9 -f "$WORK/uml_kernel" 2>/dev/null

TIMED_OUT=0
if [ "$RC" = "124" ] || [ "$RC" = "137" ]; then
    TIMED_OUT=1
fi
SIG=0
if [ "$RC" -gt 128 ]; then
    SIG=$((RC - 128))
fi
END=$(date +%s)
DUR=$(( (END - START) * 1000 ))
{
    echo "== uml-seccomp adb-shell channel =="
    echo "case=$CASE channel=adb-shell umid=$UMID mac=$MAC uml_dir=$UMLDIR"
    echo "kernel_sha256=$(sha256sum "$WORK/uml_kernel" 2>/dev/null | cut -d' ' -f1)"
    echo "kernel_args=$KARGS $CASE_ARGS"
    cat "$RAW"
} > "$CONSOLE"
{
    echo "exit_code=$RC"
    echo "signal_guess=$SIG"
    echo "timed_out=$TIMED_OUT"
    echo "reason=adb-shell-channel"
    echo "duration_ms=$DUR"
} > "$EXITF"
echo "DEVICE_SCRIPT_DONE case=$CASE rc=$RC sig=$SIG timed_out=$TIMED_OUT duration_ms=$DUR"
DEVCASE
}

shell_run_case() {
    # shell_run_case <row> <ts>
    local row=$1 ts=$2
    local id run_dir mac umid umldir
    id=$(printf '%s' "$row" | cut -d'|' -f1)
    run_dir="$OUT_ROOT/$ts/$id"
    mkdir -p "$run_dir"
    case_markers "$row" > "$run_dir/markers.txt"
    mac=${UML_TEST_MAC:-$(printf '02:%02x:%02x:%02x:%02x:%02x' 0 0 0 0 1)}
    umid="u3-$id-$ts"
    umldir=$UML_MCONSOLE_DIR
    case "$umldir" in
        */) : ;;
        *) umldir="$umldir/" ;;
    esac

    say "[$id] start (adb-shell channel, kernel=libuml_kernel_noinitrd.so, timeout=${CASE_TIMEOUT}s)"
    gen_device_case_script "$run_dir"
    "$ADB" -s "$DEVICE_SERIAL" push "$(winpath "$run_dir/device-case.sh")" \
        "$SHELL_WORK/runs/$ts/$id/device-case.sh" >/dev/null || die "push device-case.sh failed"

    local start_s end_s
    start_s=$(date +%s)
    dsh "sh $SHELL_WORK/runs/$ts/$id/device-case.sh $id $ts $CASE_TIMEOUT $mac $umid $umldir $SHELL_WORK" \
        > "$run_dir/device.log" 2>&1 || true
    end_s=$(date +%s)

    # Evidence pull (probe log only exists for the mconsole row).
    local f
    for f in console.log exit.txt stderr.log device-case.sh mconsole-probe.log; do
        "$ADB" -s "$DEVICE_SERIAL" pull "$SHELL_WORK/runs/$ts/$id/$f" \
            "$(winpath "$run_dir/$f")" >/dev/null 2>&1 || true
    done

    local timedout=0
    grep -q '^timed_out=1' "$run_dir/exit.txt" 2>/dev/null && timedout=1
    local rc signal
    rc=$(sed -n 's/^exit_code=//p' "$run_dir/exit.txt" 2>/dev/null | head -n 1 | tr -d '\r')
    signal=$(sed -n 's/^signal_guess=//p' "$run_dir/exit.txt" 2>/dev/null | head -n 1 | tr -d '\r')
    [ -n "$rc" ] || rc=""

    local verdict reason triage
    verdict=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$run_dir/markers.txt" "$timedout" | sed -n 's/^VERDICT=//p')
    reason=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$run_dir/markers.txt" "$timedout" | sed -n 's/^REASON=//p')
    triage=$(judge_run "$run_dir/console.log" "$run_dir/exit.txt" "$run_dir/markers.txt" "$timedout" | sed -n 's/^TRIAGE=//p')

    write_meta "$run_dir" "$id" ok "" "$rc" "${signal:-0}" \
        "$(( (end_s - start_s) * 1000 ))" "$run_dir/console.log" "$timedout" adb-shell
    write_verdict "$run_dir" "$verdict" "$reason" "$triage"
    say "[$id] $verdict ($reason) console=$run_dir/console.log"
    [ "$triage" != "none" ] && say "[$id]   triage: $triage"
}

run_adbshell() {
    local ts lib
    ts=$(date +%Y%m%d-%H%M%S)
    lib=$(resolve_lib_dir)
    say "== U3 adb-shell channel run (scope: vector-net, mconsole) =="
    say "serial   : $DEVICE_SERIAL"
    say "lib dir  : $lib (kernel/stub source, shared with the runner channel)"
    say "workdir  : $SHELL_WORK"
    say "out      : $OUT_ROOT/$ts"
    say ""
    shell_stage "$ts" "$lib"
    say ""
    local summary_pass=0 summary_fail=0 summary_blocked=0 ran=0
    while IFS= read -r row; do
        local id ch verdict
        id=$(printf '%s' "$row" | cut -d'|' -f1)
        ch=$(case_channel "$row")
        [ "$ch" = "adb-shell" ] || continue
        if [ -n "$ONLY_CASE" ] && [ "$ONLY_CASE" != "$id" ]; then
            continue
        fi
        shell_run_case "$row" "$ts"
        ran=$((ran + 1))
        verdict=$(sed -n 's/^VERDICT=//p' "$OUT_ROOT/$ts/$id/verdict.txt" 2>/dev/null)
        case "$verdict" in
            PASS) summary_pass=$((summary_pass + 1)) ;;
            FAIL) summary_fail=$((summary_fail + 1)) ;;
            *) summary_blocked=$((summary_blocked + 1)) ;;
        esac
    done <<EOFROWSA
$CASES_TABLE
EOFROWSA
    say ""
    if [ "$ran" -eq 0 ] && [ -n "$ONLY_CASE" ]; then
        say "no adb-shell case matched --case $ONLY_CASE (only vector-net / mconsole live on this channel)"
    fi
    say "== summary: PASS=$summary_pass FAIL=$summary_fail BLOCKED=$summary_blocked (adb-shell channel) =="
    say "the other 10 cases belong to the runner channel: run without --channel (SOP steps 4-5)"
    say "aggregate with matrix.py (SOP step S4; harness.meta carries CHANNEL=adb-shell)"
}

run() {
    require_serial
    [ -f "$PROFILE_PATH" ] || die "profile missing: $PROFILE_PATH"
    command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || die "adb not found (set ADB or ANDROID_HOME)"
    dsh "true" 2>/dev/null || die "device $DEVICE_SERIAL not reachable via adb"
    if [ "$CHANNEL" = "adb-shell" ]; then
        run_adbshell
        return
    fi
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
    probe) probe_mode ;;
    *) usage ;;
esac
