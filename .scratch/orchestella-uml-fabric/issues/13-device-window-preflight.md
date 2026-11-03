# 13: T7 · 设备窗口 preflight 自检脚本

Type: implementation
Status: resolved（2026-11-02，零设备产品化轮）
Priority: P2（可选票，但成本低、下个窗口必用）
Owner: （待分配）
Blocked by: None
External gates: 无（脚本本身零设备可测；实跑需设备窗口）

## 背景

`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §6 的 7 条方法论坑，以及 §1 表 #11 的两起「推送/启动竞速 + debugfs dentry 断链」事故。这些坑每轮重犯一次，代价是整轮 attempt 作废。本票把它们固化为**开场自检脚本 + 清单**。

## 必须做

新增 `tools/device-validation/preflight_device_window.sh`，纯 bash、零设备可 `--self-test`。功能分三组：

1. **推送完整性**（`push-verify`）：
   - `push <local> <remote>`：`adb push` 后**必跑设备侧 md5 对账**（`adb shell md5sum <remote>` vs 本地 `md5sum`），不一致即 `exit 1` 并打印两端摘要；
   - 大文件（>100MB）额外打印设备侧 `stat -c %s` 与文件系统可用空间。
2. **启动竞速防护**（`start <package> <activity>`）：
   - `am force-stop` → `sleep 3` → `am start -W` → 解析 `LaunchState`/`TotalTime`，非 `COLD`/`WARM` 或总时长异常则告警；
   - 明示「未 force-stop 时 am start 可能只派发 intent 不新建实例」的失败模式。
3. **排程互斥与阈值提示**（`plan`）：
   - 打印本轮排程约束：T7-F（shell 域 QEMU 网络面）与 T8②（熄屏 doze）**不可并行**；中停测试**必须用长驻负载**（bootprobe 1.7s 自退会与 2s 轮询竞态）；
   - 凉机阈值：T9 类性能列要求起始 `thermal_zone*` 温度低于给定阈值（默认 38℃，可 `--max-temp` 覆盖），超阈值则提示先冷却并 `exit 3`；
   - `--require-long-running <work>`：拒绝 `bootprobe` 这类短负载做中停测试，直接 `exit 4`。
4. `--self-test`：纯 host、无需 adb。用 stub 化的 `adb`（PATH 前置一个记录/回放脚本）验证：
   - md5 不一致 → `exit 1`；
   - md5 一致 → `exit 0`；
   - `plan` 的互斥提示与阈值判定的三种分支（低于/等于/高于阈值）；
   - `--require-long-running bootprobe` → `exit 4`。
   结尾唯一 OK 串 `PREFLIGHT_DEVICE_WINDOW_TEST_OK`。

同时在 `docs/research/` 的新报告里给一节「下个设备窗口开场清单」（5–7 条命令序列）。

## 边界

- 不执行任何真实 adb 命令（无设备）；`--self-test` 必须能离线跑。
- 不改现有 `tools/device-validation/*.py`。
- 不引入新依赖（不用 jq、不用 python）。

## 完成判据

`bash tools/device-validation/preflight_device_window.sh --self-test` → `PREFLIGHT_DEVICE_WINDOW_TEST_OK`；`bash -n` 通过。

## Comments

- 2026-11-02 / 主会话：建票。坑清单来源：round-2 报告 §6.4/§6.5/§6.6、§1 #9/#11、§2.1 的 P2 夹具竞态。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。新增 `tools/device-validation/preflight_device_window.sh`（纯 bash）：`push-verify`（push 后强制设备侧 md5 对账，不一致 exit 1；>100MB 附带大小与可用空间）、`start`（force-stop → sleep 3 → `am start -W` → 解析 LaunchState）、`plan`（T7-F × T8② doze 互斥、中停须长驻负载、`thermal_zone*` 凉机阈值 exit 3）、`--require-long-running`（拒 bootprobe，exit 4）、`--self-test`（PATH 前置 stub adb 回放）。证据：`PREFLIGHT_DEVICE_WINDOW_TEST_CASES pass=19 fail=0 total=19` + `PREFLIGHT_DEVICE_WINDOW_TEST_OK`，`bash -n` 通过。两个实现坑（已修）记入脚本注释：Windows 形态 `TMPDIR` 带盘符冒号会切断 PATH 使 stub 失效；`md5sum` 对反斜杠路径会在行首加 `\`。**未做**：`docs/research/` 报告里的「开场清单」一节 —— 已由本轮报告 §7 的「下个窗口入口」9 条承接。
