# 01: Boot timeline harness — 统一 T0–T5 的 UML/QEMU/proot 启动分解

Type: implementation
Status: ready-for-agent
Priority: P0（所有快速启动与规模化工作的口径底座）
Owner: （待分配）
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
