#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# uml-runner 宿主侧准备脚本（Windows Git Bash / WSL / Linux 通用）
#
#   ./prepare.sh install    安装 debug APK
#   ./prepare.sh push       单次 adb push rootfs 到 /data/local/tmp + 设备侧 toybox tar 解包
#   ./prepare.sh config     用 run-as 写入 files/uml-config.json
#   ./prepare.sh start      启动 RunnerActivity 并跟 logcat
#   ./prepare.sh stop       touch files/uml-stop 让内核优雅退出
#   ./prepare.sh status     pm path / files 目录 / uml-exit.txt
#   ./prepare.sh log        logcat -s UmlRunner
#   ./prepare.sh pull       取回 uml-console.log / uml-exit.txt
#   ./prepare.sh all        install + push + config + start
#
# 设计要点：rootfs 只 push 一次（不逐文件 push），解包在设备侧做；
# 应用以 targetSdk 28 安装，run-as 可用（debuggable）。
# ---------------------------------------------------------------------------
set -euo pipefail

# Git Bash 会把 /data/local/tmp 当本地路径改写（MSYS_NO_PATHCONV），内部一律关掉转换
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PKG=${PKG:-dev.orchestella.umlrunner}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
EVIDENCE=${EVIDENCE:-$(cd "$REPO_ROOT/.." && pwd)/evidence/uml-20261028}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT or ANDROID_HOME}}
ADB=${ADB:-$SDK/platform-tools/adb.exe}
APK=${APK:-$HERE/app/build/outputs/apk/debug/app-debug.apk}
REMOTE_TGZ=/data/local/tmp/uml-rootfs.tar.gz
OUT_DIR=${OUT_DIR:-$HERE/out}

[ -x "$ADB" ] || ADB=adb
command -v "$ADB" >/dev/null 2>&1 || { echo "找不到 adb（试过 $ADB 与 PATH）" >&2; exit 1; }

log()  { printf '\n=== %s ===\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

ROOTFS_TGZ=$(ls "$EVIDENCE"/alpine-rootfs/alpine-minirootfs-*-aarch64.tar.gz 2>/dev/null | head -1 || true)

need_device() {
  local n
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  [ "$n" -ge 1 ] || die "没有处于 device 状态的设备（adb devices）"
}

need_installed() {
  if ! "$ADB" shell "pm path $PKG" 2>/dev/null | grep -q package:; then
    die "$PKG 未安装或不可见。先跑 ./prepare.sh install
（HyperOS/MIUI 上 adb install 若报 INSTALL_FAILED_USER_RESTRICTED：
 设置 → 更多设置 → 开发者选项 → 打开「USB 安装」（需登录小米账号），
 并在手机上点「继续安装」确认弹窗。）"
  fi
  if ! "$ADB" shell "run-as $PKG id" 2>/dev/null | grep -q 'uid='; then
    die "run-as $PKG 不可用（APK 必须 debuggable；本工程的 debug 变体满足该条件）"
  fi
}

files_dir() { "$ADB" shell "run-as $PKG pwd" | tr -d '\r'; }

cmd_install() {
  log "安装 APK: $APK"
  [ -f "$APK" ] || die "APK 不存在，先跑 ./gradlew assembleDebug"
  local out rc
  set +e
  out=$("$ADB" install -r -t "$APK" 2>&1)
  rc=$?
  set -e
  echo "$out"
  if [ $rc -ne 0 ]; then
    case "$out" in
      *INSTALL_FAILED_USER_RESTRICTED*)
        die "设备拒绝 adb 安装（HyperOS/MIUI 限制）。
需要手动开启：设置 → 更多设置 → 开发者选项 → 「USB 安装」开启（登录小米账号），
然后重新执行本命令，并在手机上同意安装弹窗。" ;;
      *INSTALL_FAILED_DEPRECATED_SDK_VERSION*)
        die "系统拒绝 targetSdk 28 的安装（Android 17 对过低 targetSdk 的策略）。" ;;
      *) die "adb install 失败（原文见上）" ;;
    esac
  fi
}

cmd_push() {
  need_device
  need_installed
  [ -n "${ROOTFS_TGZ:-}" ] || die "找不到 $EVIDENCE/alpine-rootfs/alpine-minirootfs-*-aarch64.tar.gz"

  log "1/3 单次 push（整包，不逐文件）: $(basename "$ROOTFS_TGZ") → $REMOTE_TGZ"
  "$ADB" push "$ROOTFS_TGZ" "$REMOTE_TGZ"
  local local_sz remote_sz
  local_sz=$(stat -c %s "$ROOTFS_TGZ" 2>/dev/null || wc -c < "$ROOTFS_TGZ")
  remote_sz=$("$ADB" shell "stat -c %s $REMOTE_TGZ" | tr -d '\r')
  [ "$local_sz" = "$remote_sz" ] || die "推送大小不一致 local=$local_sz remote=$remote_sz"
  echo "size ok: $remote_sz bytes"

  log "2/3 设备侧解包（toybox tar，直接从 stdin 读压缩流）"
  echo "实际执行："
  echo "  adb shell \"cat $REMOTE_TGZ | run-as $PKG sh -c 'gzip -dc | tar -xf - -C files/rootfs'\""
  "$ADB" shell "run-as $PKG mkdir -p files/rootfs"
  "$ADB" shell "cat $REMOTE_TGZ | run-as $PKG sh -c 'gzip -dc | tar -xf - -C files/rootfs'"

  log "3/3 校验"
  local n
  n=$("$ADB" shell "run-as $PKG sh -c 'ls files/rootfs | wc -l'" | tr -d '\r')
  echo "files/rootfs 顶层条目数: $n"
  "$ADB" shell "run-as $PKG sh -c 'ls files/rootfs; echo ---; cat files/rootfs/etc/os-release'"
  [ "$n" -ge 5 ] || die "解包结果异常（顶层条目过少）"
}

cmd_config() {
  need_device
  need_installed
  local fd tmp
  fd=$(files_dir)
  tmp=$(mktemp)
  cat > "$tmp" <<JSON
{
  "_note": "uml-runner 研究配置；args 省略时用内置默认（含 nativeLibraryDir 里自动解析的 stub_exe）",
  "rootfs": "$fd/files/rootfs",
  "stdin": "pipe",
  "memMb": 512
}
JSON
  log "写入 files/uml-config.json（rootfs=$fd/files/rootfs）"
  "$ADB" shell "run-as $PKG sh -c 'cat > files/uml-config.json'" < "$tmp"
  rm -f "$tmp"
  "$ADB" shell "run-as $PKG cat files/uml-config.json"
}

cmd_start() {
  need_device
  need_installed
  log "启动 $PKG/.RunnerActivity（清空 logcat 后跟踪 UmlRunner）"
  "$ADB" logcat -c || true
  "$ADB" shell "am start -n $PKG/.RunnerActivity"
  echo
  echo "正在跟踪 logcat（Ctrl-C 退出；内核输出同时写 files/uml-console.log）"
  exec "$ADB" logcat -s UmlRunner:I
}

cmd_stop() {
  need_device
  need_installed
  log "touch files/uml-stop"
  "$ADB" shell "run-as $PKG sh -c 'touch files/uml-stop'"
  echo "内核将在 500ms 内收到 SIGTERM，3s 未退则 SIGKILL。"
}

cmd_status() {
  need_device
  log "pm path"
  "$ADB" shell "pm path $PKG" || true
  log "run-as ls files"
  "$ADB" shell "run-as $PKG sh -c 'ls -l files'" || true
  log "uml-exit.txt"
  "$ADB" shell "run-as $PKG cat files/uml-exit.txt" 2>/dev/null || echo "(尚未产生)"
  log "uml-console.log 尾部 40 行"
  "$ADB" shell "run-as $PKG sh -c 'tail -n 40 files/uml-console.log'" 2>/dev/null || echo "(尚未产生)"
}

cmd_log()  { exec "$ADB" logcat -s UmlRunner:I; }

cmd_pull() {
  need_device
  need_installed
  mkdir -p "$OUT_DIR"
  log "取回日志到 $OUT_DIR"
  "$ADB" exec-out "run-as $PKG cat files/uml-console.log" > "$OUT_DIR/uml-console.log" || true
  "$ADB" exec-out "run-as $PKG cat files/uml-exit.txt"   > "$OUT_DIR/uml-exit.txt"   || true
  wc -c "$OUT_DIR"/uml-console.log "$OUT_DIR"/uml-exit.txt || true
  echo
  tail -n 30 "$OUT_DIR/uml-console.log" 2>/dev/null || true
}

case "${1:-}" in
  install) cmd_install ;;
  push)    cmd_push ;;
  config)  cmd_config ;;
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  status)  cmd_status ;;
  log)     cmd_log ;;
  pull)    cmd_pull ;;
  all)     cmd_install; cmd_push; cmd_config; cmd_start ;;
  *) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 1 ;;
esac
