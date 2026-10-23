# PL-02: protected 失败面统一与平台门复评

- Type: implementation
- Status: ready-for-human
- Priority: P1
- Owner: protected lane owner
- Blocked by: 无（零设备）
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

DV-01A 的失败码已经散落在 preflight、full report、host runner、矩阵和文档中：`TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`、`TERMINAL_ERROR_FLN1_7`、`execve_errno_13`、`PREFLIGHT_TERMINAL_BLOCKED` 各自表达同一件事的不同层。平台门是否解除需要人工读 AOSP policy，没有可重复的检查器。

## 目标

- 一套稳定的 reason code 分类，覆盖 preflight/full/runner/矩阵，禁止同一事实多种写法。
- 一个离线 policy fixture 检查器：输入 `.te` 片段，输出 `GATE_OPEN` / `GATE_CLOSED` / `GATE_UNCLEAR`，不联网。
- 一份平台门复评 runbook：新 ROM、新 AOSP release、OEM 策略变更时按固定步骤复评。

## 任务

- [x] 定义 `ProtectedFailureClass` 枚举与映射表：平台阻塞、权限、schema、生命周期、stop、安装、未知。
- [x] 统一 preflight/full/runner 的输出：固定 code + stage + errno（可空），不携带原始 AVC/logcat/路径。
- [x] 实现 `tools/ci/check_microdroid_exec_gate.py`：
  - 输入：一个或多个 `.te` fixture 路径；
  - 规则：`microdroid_app` 或其 `coredomain` 继承是否对 payload 文件类型拥有 `execute_no_trans`；
  - 输出：`GATE_OPEN` / `GATE_CLOSED` / `GATE_UNCLEAR` + 命中的规则行号；
  - 不访问网络；fixture 由人工从 AOSP/ROM 拷贝，仓库只存最小片段与来源说明。
- [x] 增加 A16、A17、AOSP main 三个 fixture 的自测；main fixture 当前应为 `GATE_CLOSED`。
- [x] 写复评 runbook：触发条件、取样位置、命令、判定、记录字段（ROM 别名/SDK/policy 来源/结论）。
- [x] 矩阵与报告改用统一 code；保留历史行，不覆盖。
- [x] 单元测试：分类映射、规则命中、注释/多行/宏、缺失文件、歧义输入。

## 验收命令

```bash
python -m pytest tools/ci -q
python tools/ci/check_microdroid_exec_gate.py --policy-fixtures tools/ci/fixtures/sepolicy
python tools/ci/check_sensitive_data.py --self-test
```

## 边界

- 不把 `GATE_CLOSED` 写成产品限制的永久结论；它只是当前 fixture 的判定。
- 不修改 accepted ADR 正文；需要裁定走 ACR。
- 不在 CI 中联网抓取 AOSP；fixture 更新是显式提交。

## 证据

- 实施报告：`docs/reports/tickets/pl-02/implementation-report.zh-CN.md`。
- 复评记录只写 ROM 别名、SDK、fixture 来源摘要、结论；不含设备指纹。
