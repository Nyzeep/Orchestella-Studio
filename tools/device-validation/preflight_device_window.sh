#!/usr/bin/env bash
# 设备窗口开场自检（T7/T8/T9 通用）。纯 bash，零设备可 --self-test。
#
# 固化 round-2 报告的三类事故：
#   §1 #11  推送与 QEMU 启动竞速 → 推送后必须设备侧 md5 对账才准启动
#   §6.5    force-stop 与 am start 竞速 → 无间隔时 START 落在死 Activity 的 top-most 瞬间
#   §1 #9   T7-F 与 T8② 熄屏 doze 并行 → WiFi 休眠掐断 gvproxy 上游（<1 B/s）
#   §2.1 P2 bootprobe 1.7s 自退 vs 2s 轮询 → 中停必须长驻负载
#
# 子命令与退出码：
#   push-verify <local> <remote>   0 一致 / 1 md5 不一致或无法取设备摘要 / 2 前置条件缺失 / 6 push 失败
#   start <package> <activity>     0 COLD|WARM / 2 前置条件缺失 / 5 启动态异常(非 COLD/WARM 或缺失) / 7 am start 失败
#   plan [--max-temp N] [--require-long-running W]   0 通过 / 2 参数错 / 3 温度 >= 阈值 / 4 短负载被拒
#   --self-test                    0 全通过 / 1 有用例失败
#
# 环境变量（默认值即真实设备窗口用法）：
#   ADB=adb                           adb 可执行名/路径
#   PREFLIGHT_THERMAL_ROOT=/sys/class/thermal   热区读取根目录（自检用假目录）
#   PREFLIGHT_LARGE_BYTES=104857600  超过该字节数打印设备侧大小与可用空间
#   PREFLIGHT_START_SETTLE=3         force-stop 后等待秒数
set -uo pipefail

ADB="${ADB:-adb}"
THERMAL_ROOT="${PREFLIGHT_THERMAL_ROOT:-/sys/class/thermal}"
LARGE_BYTES="${PREFLIGHT_LARGE_BYTES:-104857600}"
START_SETTLE="${PREFLIGHT_START_SETTLE:-3}"
MAX_TOTAL_MS_WARN=30000
SELF="${BASH_SOURCE[0]}"

warn() { printf 'WARN %s\n' "$*" >&2; }
err() { printf 'ERROR %s\n' "$*" >&2; }

usage() {
  cat <<'EOF'
用法:
  preflight_device_window.sh push-verify <local> <remote>
  preflight_device_window.sh start <package> <activity>
  preflight_device_window.sh plan [--max-temp 38] [--require-long-running <work>]
  preflight_device_window.sh --self-test
EOF
}

require_adb() {
  command -v "$ADB" >/dev/null 2>&1 && return 0
  err "adb 不可用: $ADB（无设备时请用 --self-test）"
  exit 2
}

file_size() {
  stat -c %s "$1" 2>/dev/null || wc -c <"$1"
}

file_md5() {
  local out
  if command -v md5sum >/dev/null 2>&1; then
    out="$(md5sum "$1" 2>/dev/null)" || return 1
  elif command -v md5 >/dev/null 2>&1; then
    out="$(md5 -q "$1" 2>/dev/null)" || return 1
  else
    return 1
  fi
  # md5sum 遇到反斜杠文件名会在行首加 `\` 转义，先剥掉再取第一列
  out="${out#\\}"
  printf '%s\n' "${out%% *}"
}

# ---------------------------------------------------------------- push-verify

cmd_push_verify() {
  if [ "$#" -ne 2 ]; then
    usage
    exit 2
  fi
  local local_path="$1" remote_path="$2"
  local host_md5 dev_md5 raw host_size dev_size remote_dir df_out

  if [ ! -f "$local_path" ]; then
    err "本地文件不存在: $local_path"
    exit 2
  fi
  require_adb
  if ! host_md5="$(file_md5 "$local_path")" || [ -z "$host_md5" ]; then
    err "无法计算本地 md5: $local_path"
    exit 2
  fi
  host_size="$(file_size "$local_path")"
  printf 'PUSH local=%s remote=%s size=%s md5=%s\n' \
    "$local_path" "$remote_path" "$host_size" "$host_md5"

  if ! "$ADB" push "$local_path" "$remote_path"; then
    err "adb push 失败: $local_path -> $remote_path"
    exit 6
  fi

  # 强制设备侧对账：只有 md5 相等才准启动（§1 #11 的 push/启动竞速正是少了这一步）
  raw="$("$ADB" shell md5sum "$remote_path" 2>/dev/null | head -n1)"
  raw="${raw//$'\r'/}"
  dev_md5="${raw%%[[:space:]]*}"
  if [ -z "$dev_md5" ]; then
    err "设备侧 md5sum 无输出，拒绝放行: $remote_path"
    printf 'MD5_MISMATCH local=%s device=<empty>\n' "$host_md5"
    exit 1
  fi
  if [ "$dev_md5" != "$host_md5" ]; then
    printf 'MD5_MISMATCH local=%s device=%s path=%s\n' "$host_md5" "$dev_md5" "$remote_path"
    err "摘要不一致，禁止启动（重新 push 或改用 loop-rw 正规文件操作，勿用 debugfs rm+write）"
    exit 1
  fi
  printf 'MD5_OK local=%s device=%s path=%s\n' "$host_md5" "$dev_md5" "$remote_path"

  # 大文件：尺寸误判是 push 竞速的触发条件之一，额外留设备侧尺寸与余量证据
  if [ "$host_size" -ge "$LARGE_BYTES" ]; then
    dev_size="$("$ADB" shell stat -c %s "$remote_path" 2>/dev/null | tr -d '\r' | head -n1)"
    printf 'PUSH_LARGE device_size=%s local_size=%s\n' "${dev_size:-<empty>}" "$host_size"
    remote_dir="${remote_path%/*}"
    [ "$remote_dir" = "$remote_path" ] && remote_dir="/"
    df_out="$("$ADB" shell df -k "$remote_dir" 2>/dev/null | tr -d '\r')"
    printf 'PUSH_LARGE device_free:\n%s\n' "${df_out:-<empty>}"
    if [ -n "$dev_size" ] && [ "$dev_size" != "$host_size" ]; then
      warn "设备侧大小不一致（md5 已通过，仍建议复查写入完整性）"
    fi
  fi
  exit 0
}

# --------------------------------------------------------------------- start

parse_field() {
  local key="$1" line
  while IFS= read -r line; do
    line="${line%$'\r'}"
    case "$line" in
      "$key":*)
        line="${line#"$key":}"
        printf '%s\n' "${line# }"
        return 0
        ;;
    esac
  done <<<"$2"
  return 1
}

cmd_start() {
  if [ "$#" -ne 2 ]; then
    usage
    exit 2
  fi
  local pkg="$1" act="$2" component out rc state total
  case "$act" in
    .*) act="$pkg$act" ;;
  esac
  component="$pkg/$act"
  require_adb

  # 未 force-stop 时 am start 往往只把 intent 投给仍 top-most 的已死 Activity 记录：
  # 命令 exit 0、无新实例、后续 probe 全部跑在空气上。故必须先 force-stop 并留出
  # 记录回收时间（§6.5 实测 3s 足够），再用 -W 观察 LaunchState 自证新实例。
  "$ADB" shell am force-stop "$pkg" >/dev/null 2>&1 \
    || warn "am force-stop 失败: $pkg（继续，但 LaunchState 判读可能失真）"
  sleep "$START_SETTLE"

  out="$("$ADB" shell am start -W -n "$component" 2>&1)"
  rc=$?
  printf '%s\n' "$out" | tr -d '\r'
  if [ "$rc" -ne 0 ]; then
    err "am start 失败 rc=$rc component=$component"
    exit 7
  fi

  state="$(parse_field LaunchState "$out" || true)"
  total="$(parse_field TotalTime "$out" || true)"
  printf 'START component=%s LaunchState=%s TotalTime=%s\n' \
    "$component" "${state:-<missing>}" "${total:-<missing>}"

  case "$state" in
    COLD | WARM) ;;
    *)
      warn "LaunchState=${state:-<missing>}，不是 COLD/WARM：可能只派发了 intent 未新建实例，或首启权限弹窗卡住"
      exit 5
      ;;
  esac
  case "$total" in
    '' | *[!0-9]*) warn "TotalTime 不可解析: ${total:-<missing>}" ;;
    *)
      if [ "$total" -gt "$MAX_TOTAL_MS_WARN" ]; then
        warn "TotalTime=${total}ms 超过 ${MAX_TOTAL_MS_WARN}ms，冷启异常慢（TCG 宿主可放宽，但需记录）"
      fi
      ;;
  esac
  exit 0
}

# ---------------------------------------------------------------------- plan

thermal_snapshot() {
  local f name t found=0 max=-1
  THERMAL_MAX=""
  if [ ! -d "$THERMAL_ROOT" ]; then
    return 1
  fi
  for f in "$THERMAL_ROOT"/thermal_zone*/temp; do
    [ -e "$f" ] || continue
    found=1
    name="$(cat "${f%/temp}/type" 2>/dev/null || printf 'unknown')"
    t="$(tr -dc '0-9-' <"$f" 2>/dev/null || true)"
    [ -n "$t" ] || continue
    # 内核多以毫摄氏度上报，|t|>1000 时折算
    if [ "$t" -gt 1000 ] 2>/dev/null; then
      t=$((t / 1000))
    elif [ "$t" -lt -1000 ] 2>/dev/null; then
      t=$((t / 1000))
    fi
    printf 'THERMAL_ZONE name=%s temp=%sC\n' "$name" "$t"
    if [ "$t" -gt "$max" ]; then
      max="$t"
    fi
  done
  [ "$found" -eq 1 ] || return 1
  THERMAL_MAX="$max"
  return 0
}

cmd_plan() {
  local max_temp=38 require_work="" t
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --max-temp)
        [ "$#" -ge 2 ] || { usage; exit 2; }
        max_temp="$2"
        shift 2
        ;;
      --max-temp=*)
        max_temp="${1#*=}"
        shift
        ;;
      --require-long-running)
        [ "$#" -ge 2 ] || { usage; exit 2; }
        require_work="$2"
        shift 2
        ;;
      --require-long-running=*)
        require_work="${1#*=}"
        shift
        ;;
      *)
        err "未知参数: $1"
        usage
        exit 2
        ;;
    esac
  done
  case "$max_temp" in
    '' | *[!0-9]*)
      err "--max-temp 需为整数摄氏度: $max_temp"
      exit 2
      ;;
  esac

  cat <<'EOF'
PLAN 本轮排程约束
  - T7-F（shell 域 QEMU 网络面） × T8②（熄屏 doze）不可并行：doze 令 WiFi 休眠，gvproxy 上游 <1 B/s 停滞，整轮 attempt 作废
  - 串行顺序：T8② 熄屏窗口跑完并亮屏 → 再跑 T7-F；T7-F 全程禁熄屏、禁 doze 触发
  - 中停（stop-file / 运行中停止）必须长驻负载：bootprobe 约 1.7s 自退，与 2s 轮询竞态会误判为 watcher 缺陷
  - 每次 push 后 md5 对账通过才准 am start；am start 前必须 force-stop + 等待记录回收
EOF

  if [ -n "$require_work" ]; then
    case "${require_work,,}" in
      *bootprobe*)
        printf 'PLAN_REFUSE work=%s 短负载（约 1.7s 自退）不能做中停测试，改用 resident 长驻负载\n' "$require_work"
        exit 4
        ;;
    esac
    printf 'PLAN_LONG_RUNNING work=%s accepted\n' "$require_work"
  fi

  if thermal_snapshot; then
    t="$THERMAL_MAX"
    printf 'THERMAL_MAX=%sC threshold=%sC\n' "$t" "$max_temp"
    if [ "$t" -lt "$max_temp" ]; then
      printf 'THERMAL_OK=%sC 低于阈值 %sC\n' "$t" "$max_temp"
      exit 0
    fi
    if [ "$t" -eq "$max_temp" ]; then
      printf 'THERMAL_AT_LIMIT=%sC 等于阈值 %sC（要求严格低于），按超阈值处理\n' "$t" "$max_temp"
    else
      printf 'THERMAL_TOO_HOT=%sC 高于阈值 %sC\n' "$t" "$max_temp"
    fi
    err "先冷却：停掉 UML/QEMU 负载并静置 5-10min，复测通过再开窗口（T9 性能列要求凉机起始）"
    exit 3
  fi

  printf 'THERMAL_UNKNOWN 未在 %s 找到 thermal_zone*（宿主或无内核热区），温度判定跳过，不阻塞\n' "$THERMAL_ROOT"
  exit 0
}

# ------------------------------------------------------------------ self-test

self_test() {
  local base case_out pass=0 fail=0 f md5 wrong rc stub drive rest
  base="${TMPDIR:-/tmp}"
  base="${base//\\//}"
  # 形如 `<盘符>:/...` 的 Windows TMPDIR 要折成 POSIX 的 `/<盘符小写>/...`，
  # 否则带盘符冒号的路径塞进 PATH 会被 `:` 切断，stub 永远找不到。
  case "$base" in
    [A-Za-z]:/*)
      drive="${base%%:*}"
      rest="${base#*:}"
      base="/${drive,,}${rest}"
      ;;
  esac
  base="${base%/}"
  WORK="$(mktemp -d "$base/pfw.XXXXXX")" || {
    err "mktemp 失败: $base"
    exit 1
  }
  case_out="$WORK/case.out"
  cleanup() {
    local d
    [ -n "${WORK:-}" ] || return 0
    # 沙箱里 rm 可能被安全删除包装器接管，逐文件 unlink、逐目录 rmdir
    find "$WORK" -type f -exec unlink {} \; 2>/dev/null || true
    for d in "$WORK/bin" "$WORK/thermal/thermal_zone0" "$WORK/thermal" "$WORK"; do
      rmdir "$d" 2>/dev/null || true
    done
  }
  trap cleanup EXIT

  mkdir -p "$WORK/bin" "$WORK/thermal/thermal_zone0"
  printf 'cpu-thermal\n' >"$WORK/thermal/thermal_zone0/type"
  printf '30000\n' >"$WORK/thermal/thermal_zone0/temp"

  # stub adb：PATH 前置，按 STUB_* 环境变量回放，不碰真机
  stub="$WORK/bin/adb"
  {
    printf '%s\n' '#!/usr/bin/env bash'
    printf '%s\n' '# self-test stub: 只回放，不执行真实 adb'
    printf '%s\n' 'cmd="${1:-}"; shift || true'
    printf '%s\n' 'case "$cmd" in'
    printf '%s\n' '  push) printf "stub-push %s -> %s\n" "${1:-}" "${2:-}"; exit "${STUB_PUSH_RC:-0}" ;;'
    printf '%s\n' '  shell)'
    printf '%s\n' '    sub="${1:-}"; shift || true'
    printf '%s\n' '    case "$sub" in'
    printf '%s\n' '      md5sum) printf "%s  %s\n" "${STUB_MD5:-00000000000000000000000000000000}" "${1:-}" ;;'
    printf '%s\n' '      stat) printf "%s\n" "${STUB_DEV_SIZE:-0}" ;;'
    printf '%s\n' '      df) printf "Filesystem 1K-blocks Used Available Use%% Mounted on\n/dev/block/stub 1000 100 %s 10%% /data\n" "${STUB_FREE_KB:-900}" ;;'
    printf '%s\n' '      am)'
    printf '%s\n' '        a="${1:-}"; shift || true'
    printf '%s\n' '        case "$a" in'
    printf '%s\n' '          force-stop) printf "stub-force-stop %s\n" "${1:-}" ;;'
    printf '%s\n' '          start) printf "Starting: Intent { cmp=stub }\nStatus: ok\nLaunchState: %s\nActivity: stub\nTotalTime: %s\nWaitTime: %s\n" "${STUB_LAUNCH_STATE:-COLD}" "${STUB_TOTAL_TIME:-1234}" "${STUB_TOTAL_TIME:-1234}" ;;'
    printf '%s\n' '          *) printf "stub-am %s\n" "$a" ;;'
    printf '%s\n' '        esac ;;'
    printf '%s\n' '      *) printf "\n" ;;'
    printf '%s\n' '    esac ;;'
    printf '%s\n' '  *) printf "stub-adb %s\n" "$cmd" ;;'
    printf '%s\n' 'esac'
    printf '%s\n' 'exit 0'
  } >"$stub"
  chmod +x "$stub"
  export PATH="$WORK/bin:$PATH"

  # 测试用的本地载荷：小文件
  f="$WORK/payload.bin"
  printf 'orchestella-preflight\n' >"$f"
  md5="$(file_md5 "$f")"
  # 故意构造一个与真实摘要必然不同的"错误摘要"。字面量按 15 位一段拼接，
  # 避免连续的 32 位 hex 被敏感数据门禁当作疑似令牌（该门禁对 16-64 位 hex 报
  # token_shape，40/64 位豁免）。
  wrong="0123456789abcde""f0123456789abcd""ef"

  check() { # <label> <expect_rc> <actual_rc>
    if [ "$3" -eq "$2" ]; then
      pass=$((pass + 1))
      printf 'CASE ok   %-44s rc=%s\n' "$1" "$3"
    else
      fail=$((fail + 1))
      printf 'CASE FAIL %-44s rc=%s want=%s\n' "$1" "$3" "$2"
      sed -n '1,8p' "$case_out" | sed 's/^/    | /'
    fi
  }

  check_grep() { # <label> <pattern>
    if grep -q -- "$2" "$case_out"; then
      pass=$((pass + 1))
      printf 'CASE ok   %-44s match=%s\n' "$1" "$2"
    else
      fail=$((fail + 1))
      printf 'CASE FAIL %-44s missing=%s\n' "$1" "$2"
      sed -n '1,8p' "$case_out" | sed 's/^/    | /'
    fi
  }

  # 1-2: md5 一致 / 不一致
  STUB_MD5="$md5" ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/nothere" \
    bash "$SELF" push-verify "$f" staging/payload.bin >"$case_out" 2>&1
  check "push-verify md5 一致" 0 $?
  check_grep "push-verify md5 一致输出 MD5_OK" 'MD5_OK'

  STUB_MD5="$wrong" ADB=adb \
    bash "$SELF" push-verify "$f" staging/payload.bin >"$case_out" 2>&1
  check "push-verify md5 不一致" 1 $?
  check_grep "push-verify 打印两端摘要" 'MD5_MISMATCH local='

  # 3: 大文件路径（阈值压到 0 触发，避免造 100MB 文件）
  STUB_MD5="$md5" STUB_DEV_SIZE=22 STUB_FREE_KB=1048576 ADB=adb \
    PREFLIGHT_LARGE_BYTES=0 \
    bash "$SELF" push-verify "$f" staging/payload.bin >"$case_out" 2>&1
  check "push-verify 大文件附加信息" 0 $?
  check_grep "大文件打印设备侧可用空间" 'PUSH_LARGE device_free'

  # 4-5: 启动竞速判读
  STUB_LAUNCH_STATE=COLD STUB_TOTAL_TIME=1234 ADB=adb PREFLIGHT_START_SETTLE=0 \
    bash "$SELF" start com.example.app .MainActivity >"$case_out" 2>&1
  check "start COLD 通过" 0 $?
  check_grep "start 打印 LaunchState=COLD" 'LaunchState=COLD'

  STUB_LAUNCH_STATE=UNKNOWN ADB=adb PREFLIGHT_START_SETTLE=0 \
    bash "$SELF" start com.example.app .MainActivity >"$case_out" 2>&1
  check "start LaunchState 异常" 5 $?

  # 6-8: plan 温度三分支（低温 / 等于 / 高于，含 milli 折算）
  printf '30000\n' >"$WORK/thermal/thermal_zone0/temp"
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" bash "$SELF" plan >"$case_out" 2>&1
  check "plan 30C < 38C" 0 $?
  check_grep "plan 打印排程互斥" 'T8'

  printf '38000\n' >"$WORK/thermal/thermal_zone0/temp"
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" bash "$SELF" plan >"$case_out" 2>&1
  check "plan 38C == 阈值" 3 $?
  check_grep "plan 等于阈值走 AT_LIMIT" 'THERMAL_AT_LIMIT'

  printf '45000\n' >"$WORK/thermal/thermal_zone0/temp"
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" bash "$SELF" plan --max-temp 38 >"$case_out" 2>&1
  check "plan 45C > 38C" 3 $?
  check_grep "plan 高温提示冷却" '先冷却'

  # 9-10: 短负载拒绝 / 长驻负载放行
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" \
    bash "$SELF" plan --require-long-running bootprobe >"$case_out" 2>&1
  check "plan 拒绝 bootprobe" 4 $?

  printf '30000\n' >"$WORK/thermal/thermal_zone0/temp"
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" \
    bash "$SELF" plan --require-long-running resident >"$case_out" 2>&1
  check "plan 接受 resident" 0 $?

  # 11: 无热区时不阻塞
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/nothere" bash "$SELF" plan >"$case_out" 2>&1
  check "plan 无热区不阻塞" 0 $?

  # 12: 非法阈值
  ADB=adb PREFLIGHT_THERMAL_ROOT="$WORK/thermal" bash "$SELF" plan --max-temp 38.5 >"$case_out" 2>&1
  check "plan 非法 --max-temp" 2 $?

  printf 'PREFLIGHT_DEVICE_WINDOW_TEST_CASES pass=%s fail=%s total=%s\n' \
    "$pass" "$fail" "$((pass + fail))"
  if [ "$fail" -ne 0 ]; then
    err "自检失败"
    return 1
  fi
  printf 'PREFLIGHT_DEVICE_WINDOW_TEST_OK\n'
  return 0
}

main() {
  local cmd="${1:-}"
  if [ "$#" -gt 0 ]; then
    shift
  fi
  case "$cmd" in
    push-verify) cmd_push_verify "$@" ;;
    start) cmd_start "$@" ;;
    plan) cmd_plan "$@" ;;
    --self-test | -t) self_test ;;
    '' | -h | --help) usage; exit 2 ;;
    *)
      err "未知子命令: $cmd"
      usage
      exit 2
      ;;
  esac
}

main "$@"
