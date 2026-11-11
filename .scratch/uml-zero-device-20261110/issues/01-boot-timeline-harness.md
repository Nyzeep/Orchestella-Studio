# 01: Boot timeline harness — 统一 T0–T5 的 UML/QEMU/proot 启动分解

Type: implementation
Status: resolved（2026-11-10，wave1-integration）
Priority: P0（所有快速启动与规模化工作的口径底座）
Owner: uml-u0-boot-timeline
Blocked by: None
External gates: 无（纯宿主 Python；真机日志只作脱敏输入）

## 背景

当前仓库已有可用的启动数字，但口径分散：

- UML 真机 `BENCH boot_uptime_ms` / `UMARM_BOOT_OK`（`research/uml-runner/guest/um-command.sh`、`research/uml-fabric-probe/guest-overlay-uml/init`）；
- UML 宿主 `duration_ms`（`research/uml-runner/app/.../RunnerActivity.java:316`）；
- QEMU guest serial 与 `systemd-analyze`（`research/uml-fabric-probe/guest-overlay-qemu/`）；
- 现有报告写“220–400 ms / 中位 ≈330 ms”“0.22–0.40 s”，但字段、就绪点、n、控温不统一（见 `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5.1）。

本票把这些口径固化成**一个协议 + 一个解析器 + 一个自测**，让后续 U1/U2/U3 和 ACR 证据包都引用同一张表。

## 必须做

1. 新增 `tools/research/uml-boot-bench/README.md`：
   - 定义 T0 spawn、T1 kernel init、T2 rootfs mounted、T3 first command、T4 service ready、T5 warm resume、P0 proot exec、Q0 QEMU boot；
   - 每项写清采集点（宿主 `CLOCK_MONOTONIC` / guest `/proc/uptime` / kernel timestamp）、n、中位数、控温与夹具字段；
   - 明确“跨就绪点、跨 lane、跨设备数字禁止相除”。
2. 新增 `tools/research/uml-boot-bench/parse_boot_log.py`（Python 3 标准库，无第三方依赖）：
   - 输入：UML console 日志、`uml-exit.txt`、QEMU serial、`dmesg`、`systemd-analyze` 文本、proot 计时日志；用 `--lane uml|qemu|proot` 与 `--format text|json`；
   - 输出：每个 run 的 T0–T4/P0/Q0 可用字段、缺失字段、n、中位数、夹具标识；
   - 解析 `BENCH boot_uptime_ms=`、`UMARM_BOOT_OK`、`duration_ms=`、`systemd-analyze` 的 `Startup finished in`、QEMU/内核 timestamp；
   - 不猜测缺失值；缺失就输出 `unknown` 并说明需要补什么日志。
3. `--self-test`：内置合成日志覆盖三种 lane，断言字段提取、缺失字段、单位换算、中位数。
4. 用仓库内**已脱敏**的 2026-11-07/11-08 UML 日志片段做一次只读回归（不要新增真机日志；不要带入设备标识）。

## 边界

- 不执行 adb、不依赖 Android SDK、不引入 jq/pandas。
- 不改 `research/uml-runner` / `research/uml-fabric-probe` 的现有脚本；只读它们的格式。
- 不改产品代码、不改版本号/签名/发布通道。
- 不把 x86_64 参考数据当 ARM64 设备结论。

## 完成判据

- `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` 通过并打印唯一 OK 串；
- 对已有 UML 日志能输出 T3 与 `duration_ms`，与报告 0.22–0.40 s 量级一致（允许日志缺失时输出 `unknown`）；
- README 的就绪点定义被 U1/U2/U3 票直接引用；
- `git diff --check` 干净；敏感数据门通过。

## Comments

- 2026-11-10 / 主会话（ask-matt 研究轮）：建票。输入：`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、`.scratch/uml-zero-device-20261110/spec.md` §2、UML 设备轮报告。
- 2026-11-10 / uml-u0-boot-timeline：认领；base 62d96e99；worktree /workspace-wt/u0。
- 2026-11-10 / wave1-integration：集成复核通过，Status → resolved。head `4289b217`；`--self-test` → `UML_BOOT_BENCH_SELF_TEST_OK checks=118`；19 个 fixtures；2026-10-29 真实脱敏片段 T3=400 ms / `duration_ms=3182` / T1,T2 `unknown`；`py_compile`、`git diff --check`、产品路径、敏感门全过；合并提交 `8915fdea`。
  - 票据“必须做 4”要求 11-07/11-08 原始脱敏日志回归；本轮仓库内没有这两天的原始日志，按报告 §2.1 以 2026-10-29 真实片段 + 11-07 合成样本替代并保持该原始日志回归为 `not-tested`。这是范围内的替代，不是完整满足。
  - 跨测日志的 `T3_READY` / `READY` 当前不被 parser 识别，T3 为 `null`；T1/T2 由 kernel timestamp fallback 得到 40 ms，但 T2 是 VFS 时点，不等于 U2 的 `T2_MOUNTS_READY=0.09 s`。该契约缺口记录在集成报告 §4；建议开 U0.1 小票，本会话未改 parser。
