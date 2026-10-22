# Fast Lane DV-01B preflight 契约（设备无关）

日期：2026-10-22 ｜ 状态：v1 契约基线，P1/P2/P3/P4 集成按本文件对齐
适用范围：Fast Lane DV-01B 的设备无关 preflight；不改变 DV-01 既有结论，不修复平台 SELinux。
关联文档：[DV-01 真机结果](fastlane-dv01-20261021.zh-CN.md)、[DV-01A 平台变更请求](fastlane-dv01a-platform-change-request-20261022.zh-CN.md)、[Artifact 布局](fastlane-artifact-layout.zh-CN.md)、[设备矩阵](fastlane-device-matrix.zh-CN.md)。

---

## 1. 目的与边界

preflight 是 full DV-01 之前的一次**有界、设备无关、可脱敏归档**的门禁运行：只创建一个 VM，只执行一次固定短命令的 terminal 检查，然后 stop/delete。它的职责是回答“这台设备现在是否值得进入 20 轮 full”，不是证明完整 Fast Lane。

- 不跑第二次 start、不跑 encrypted storage 持久化、不跑 20 cycles、不跑完整负例。
- 不修改 root/SELinux/pvmfw，不使用 `/system/bin/sh`、encrypted storage 执行、chmod、memfd、execveat 或伪 shell 绕过 terminal 阻塞。
- 不改变 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。
- 只使用操作者提供的设备别名；不硬编码设备名、型号、序列号或具体测试端口。

**硬门禁：先 preflight，只有 `PREFLIGHT_TERMINAL_OK` 才允许跑 full。** 任何 blocked 结果都必须停止，不得用 full 运行“再确认一次”，也不得自动降级或重试绕过。

---

## 2. 共享契约摘要

| 项 | 契约值 |
| --- | --- |
| `qaMode` | `fastlane-dv01-preflight` |
| `schema` | `orchestella.fastlane.dv01.preflight.v1` |
| 设备报告路径 | `files/avf-qa/fastlane-dv01-preflight-<token>.json` |
| 报告 `mode` 字段 | `fastlane-dv01-preflight` |
| 设备别名 | `qaDeviceAlias`，默认 `qa-device`，匹配 `[A-Za-z0-9._-]{1,32}` |
| vsock 端口 | 默认 5100–5103；端口是运行参数，不是设备画像 |
| 设备报告字段 | 见 §6；JSON Schema 见 `tools/fastlane-build/preflight-report.schema.json` |
| 产物根 | `protected-fastlane/<date>/<mode>/<token>/`，见 [Artifact 布局](fastlane-artifact-layout.zh-CN.md) |

注意：报告里的 `mode` 是 QA 模式名 `fastlane-dv01-preflight`；产物路径里的 `<mode>` 是 CLI 模式 `qualification` / `preflight` / `full`。两者不是同一个字符串，归档时不要混用。

---

## 3. 执行范围与阶段

preflight 恰好执行以下九个阶段；每个阶段都必须出现在 `stage_results` 中，即使它因为前序阻塞而没有被执行。

| # | 阶段 | 必须观察 | 通过条件 | 典型固定 `reason_code` |
| --- | --- | --- | --- | --- |
| 1 | `parameter_validation` | token、alias、payload 名、toolbox SHA、端口、超时、cycle 参数 | 全部合法 | `QA_ARGUMENTS_VALID` / `QA_ARGUMENTS_REJECTED` |
| 2 | `platform_facts` | hidden API 豁免、manager、权限、protected 支持、capabilities、protected bit | 平台面可读且 protected 可用 | `PLATFORM_OBSERVED` / `HIDDEN_API_BLOCKED` / `PERMISSION_DENIED` / `PROTECTED_UNAVAILABLE` |
| 3 | `create` | 一次 protected VM 创建 | 返回 handle | `VM_CREATED` / `CREATE_FAILED` / `PREFLIGHT_BLOCKED` |
| 4 | `run` | 一次 run | 返回成功 | `RUN_RETURNED` / `CREATE_BLOCKED` |
| 5 | `payload_ready` | 有界等待 payload ready | 在 deadline 内观察到 | `PAYLOAD_READY_OBSERVED` / `PAYLOAD_READY_NOT_OBSERVED` |
| 6 | `control_hello` | control vsock 的 FLN1 HELLO / HELLO_OK | 收到 HELLO_OK | `HELLO_HELLO_OK` / `VSOCK_FAILED` |
| 7 | `terminal_pty` | 一次 `/mnt/apk/bin/sh -c 'printf DV01_TERMINAL_OK'` 或等价固定短命令 | 退出码 0 且输出匹配固定 marker | `TERMINAL_EXIT_0` / `TERMINAL_ERROR_FLN1_7` / `TERMINAL_*` |
| 8 | `stop` | 一次 stop | `CONFIRMED` | `STOP_CONFIRMED` / `STOP_UNCONFIRMED` / `STOP_ALREADY_STOPPED` |
| 9 | `delete` | 仅在 stop 已确认时 delete，并检查无残留 | delete 成功且 residual 不存在 | `VM_DELETED` / `VM_DELETE_UNCONFIRMED` / `DELETE_SKIPPED_STOP_UNCONFIRMED` |

执行约束：

- 只创建一个实例；实例名由 token 和固定后缀生成，不包含设备名。
- `terminal_pty` 只执行一次固定短命令；不写 storage marker，不做第二次 start，不做持久化断言。
- `stop` 如果是 `UNCONFIRMED`，**不得 delete**；`delete` 阶段记为 `blocked`，并在 `not_tested` 中说明跳过原因。
- `not_tested` 必须至少包含以下三个精确值：`encrypted_storage_persistence`、`20_cycles`、`negative_cases`。可以追加其他安全的原因值，但不得用它们替代这三个。

明确不执行：

- 20 次 create/run/stop/delete；
- encrypted storage marker 写入、读取、跨 stop/start 持久化；
- 完整负例矩阵（错误端口、custom image、错误 payload 等）；
- DV-02 的 terminal UI、proxy、distcc、export/import。

---

## 4. outcome 与 terminal 分类

`outcome` 只有三个契约值。`terminal.reason_code` 负责更细的原因；不要把 reason code 当成 outcome。

| `outcome` | 何时使用 | `terminal.status` | `terminal.reason_code` 示例 | 允许 full？ |
| --- | --- | --- | --- | --- |
| `PREFLIGHT_TERMINAL_OK` | 一次 lifecycle 完成；terminal 退出码 0 且输出匹配；stop `CONFIRMED`；delete 成功且无残留 | `pass` | `PREFLIGHT_TERMINAL_OK` | 是，且必须同时满足 §5 的输入绑定 |
| `PREFLIGHT_TERMINAL_BLOCKED` | lifecycle 已开始，但 terminal 未达到 OK：平台 SELinux 拒绝、VSOCK/协议失败、terminal 非零退出、stop/delete 未确认等 | `blocked` 或 `fail` | `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`、`VSOCK_FAILED`、`TERMINAL_*`、`STOP_UNCONFIRMED` | 否 |
| `PREFLIGHT_BLOCKED_EXTERNAL` | 未进入 lifecycle 就被阻塞：hidden API、权限、protected 不可用、安装/完整性/参数门禁等 | `blocked` 或 `fail` | `HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNAVAILABLE`、`PAYLOAD_VERIFY_FAILED`、`QA_ARGUMENTS_REJECTED` | 否 |

如果 terminal 本身 `pass`，但 stop/delete 未确认，`terminal.status` 保持 `pass`，`outcome` 仍为 `PREFLIGHT_TERMINAL_BLOCKED`；不要为了凑 outcome 把 terminal 改写成 fail。

### 4.1 FLN1 7 / execve_errno_13

当 terminal 返回 FLN1 7 且诊断分类为 `execve_errno_13` 时，必须记录：

```json
{
  "outcome": "PREFLIGHT_TERMINAL_BLOCKED",
  "terminal": {
    "status": "blocked",
    "reason_code": "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM",
    "fln1_code": 7,
    "exec_stage": "execve",
    "errno": 13
  }
}
```

这是 Android 16 `microdroid_app` 对 payload APK 文件缺少 `execute_no_trans` 的平台策略阻塞，不是文件缺失、ZIP mode、noexec 挂载或 harness 失败。报告只写固定 reason code 和 stage/errno；**不写原始 AVC、logcat、错误消息或路径**。

### 4.2 实现值归一化

集成时必须把实现侧的细分值归一化到上面的三个 outcome；细分原因保留在 `terminal.reason_code` 或 `failure_codes`：

| 实现侧值 | 归一化规则 |
| --- | --- |
| `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` | `outcome=PREFLIGHT_TERMINAL_BLOCKED`，`terminal.reason_code` 保持不变 |
| `VSOCK_FAILED` | `outcome=PREFLIGHT_TERMINAL_BLOCKED`，`terminal.reason_code` 保持不变 |
| `PREFLIGHT_FAILED` | lifecycle 已开始 → `PREFLIGHT_TERMINAL_BLOCKED`；未开始 → `PREFLIGHT_BLOCKED_EXTERNAL`；细节保留在 `failure_codes` |
| `BLOCKED_EXTERNAL` | `PREFLIGHT_BLOCKED_EXTERNAL` |
| `FAILED` | lifecycle 已开始 → `PREFLIGHT_TERMINAL_BLOCKED`；未开始 → `PREFLIGHT_BLOCKED_EXTERNAL`；细节保留在 `failure_codes` |
| `PREFLIGHT_PASS` | `PREFLIGHT_TERMINAL_OK`（只有 terminal/stop/delete 全部满足时） |

禁止把 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`、`VSOCK_FAILED` 或 `PREFLIGHT_FAILED` 直接写进 `outcome`。

---

## 5. 门禁顺序

1. **主机零设备门禁**：构建、JVM/CTest/Python 测试和 APK/payload/toolbox 完整性门先通过；缺固定工具链时标 `BUILD_BLOCKED`，不得用假 APK/假 `.so` 冒充。
2. **设备 preflight**：用本契约运行一次 preflight；报告必须通过 `preflight-report.schema.json` 校验。
3. **Full 准入**：只有同时满足以下条件才允许 full：
   - `outcome=PREFLIGHT_TERMINAL_OK`；
   - `terminal.status=pass` 且 `terminal.reason_code=PREFLIGHT_TERMINAL_OK`；
   - `stop=CONFIRMED`，`stage_results.delete.status=pass`；
   - full 使用的 APK、payload、toolbox SHA 与 preflight 报告中的值一致；
   - 设备 alias、SDK、ABI 与 preflight 报告一致。
4. **Blocked 停止**：`PREFLIGHT_TERMINAL_BLOCKED` 或 `PREFLIGHT_BLOCKED_EXTERNAL` 一律不跑 full。平台阻塞（FLN1 7 / `execve_errno_13`）记录为 `PREFLIGHT_TERMINAL_BLOCKED` 的平台阻塞，不重试、不绕过。
5. **变更即失效**：设备、alias、SDK、ABI、APK、payload 或 toolbox 任何一项变化，都必须重新 preflight；不得复用旧 preflight 结论。
6. **Full 显式触发**：full 只能由操作者显式请求；CI、脚本和 preflight 本身不得自动进入 full，也不得在 preflight 失败后回退到“直接 full”。

一次 preflight 只对应一次 full 准入判断。full 重跑需要新的 preflight 或新的、明确记录的同输入绑定；不能拿旧的 OK 覆盖新的设备状态。

---

## 6. 报告字段

### 6.1 必需顶层字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `schema` | string | 固定 `orchestella.fastlane.dv01.preflight.v1` |
| `mode` | string | 固定 `fastlane-dv01-preflight` |
| `device_alias` | string | 操作者提供的别名；默认 `qa-device`；不是序列号/型号 |
| `sdk_int` | integer | Android API level |
| `abi` | string | 主 ABI，例如 `arm64-v8a`；不展开完整 ABI 列表 |
| `virtualization_framework` | boolean | 是否声明 `android.software.virtualization_framework` |
| `protected_bit` | boolean | `getCapabilities` 的 protected bit |
| `permission` | string | `GRANTED` / `DENIED` / `UNKNOWN` / `NOT_APPLICABLE` |
| `apk_sha256` | string | 64 位小写 SHA-256 或 `not-tested` |
| `payload_sha256` | string | 64 位小写 SHA-256 或 `not-tested` |
| `toolbox_manifest_sha256` | string | 64 位小写 SHA-256 或 `not-tested` |
| `stage_results` | object | 九个必需阶段；每项见 §6.2 |
| `terminal` | object | 见 §6.3 |
| `stop` | string | `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED` / `NOT_TESTED` |
| `outcome` | string | 三个契约值之一 |
| `started_at_utc` | string | ISO-8601 UTC |
| `finished_at_utc` | string | ISO-8601 UTC |
| `not_tested` | array | 必须包含 §3 的三个精确值 |

### 6.2 `stage_results`

每个阶段对象只允许：

```json
{
  "status": "pass | fail | blocked | not-tested",
  "reason_code": "UPPER_SNAKE_CASE",
  "detail": "可选；固定 token 或计数，例如 elapsed_ms=1401"
}
```

`detail` 只能是固定 token 或计数，**不得**包含原始错误消息、路径、设备标识、账号或 token。schema 对每阶段都要求 `status` 和 `reason_code`。

### 6.3 `terminal`

`terminal` 只允许以下五个字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | string | `pass` / `fail` / `blocked` |
| `reason_code` | string | 固定 reason code；FLN1 7 平台阻塞用 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` |
| `fln1_code` | integer/null | FLN1 错误码；不适用时为 `-1` 或 `null` |
| `exec_stage` | string | `execve` / `open_pty` / `fork` / `connect` / `hello` / `read` / `write` / `stop` / `not-tested` / `unknown` |
| `errno` | integer/null | 脱敏 errno；不适用时为 `-1` 或 `null`；FLN1 7 平台阻塞为 `13` |

`terminal` 不记录 `message`、`path`、`logcat`、`output` 正文或设备信息；输出只允许记录固定 marker 是否匹配。

### 6.4 可选诊断字段

以下字段可以出现，但必须是固定代码/计数，且不得包含原始内容：`failure_codes`、`qualification`、`payload_ready`、`vsock_control`、`vsock_terminal`。v1 的完整允许列表以 schema 为准；新增字段必须升 schema 版本，不得静默扩展。

---

## 7. 脱敏规则

### 7.1 允许

- 设备稳定别名（`<alias>`）、Android API、ABI、feature 布尔值、protected bit、权限状态；
- APK/payload/toolbox 的 SHA-256；
- 固定 reason code、FLN1 code、exec stage、errno、计数、UTC 时间；
- 不含真实标识的证据文件 SHA-256（用于 provenance）。

### 7.2 禁止

- 序列号、IMEI/MEID、Android ID、广告 ID、MAC/蓝牙地址、Wi-Fi BSSID、build fingerprint 及其截断/哈希；
- 设备型号/厂商名、账号、token、密钥、签名材料；
- 原始 logcat、AVC、dumpsys、错误消息、命令输出、路径、截图/录屏；
- 真实 LAN/公网 IP、SSID、主机名、个人邮箱、本机绝对路径。

`<token>` 只作为报告文件名和产物目录名的一次性不透明标识；**不得写进报告正文、文档、commit message、issue/PR 或矩阵**。`commands.txt` 中的序列号和路径必须替换为 `<serial>`、`<path>` 等占位符，真实值只在进程内存中使用。

提交前按 [SENSITIVE_DEVELOPER_DATA_POLICY](../../agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md) 执行暂存差异扫描；docs 默认被忽略，经审查后只对本文件、矩阵、布局和 schema 逐个 `git add -f`。

---

## 8. 集成检查清单

- [ ] P1 产出的报告 `schema` / `mode` / 阶段名与 §2、§3 一致。
- [ ] pre-lifecycle blocked 也写出 `stage_results.stop` 和 `stage_results.delete`（状态 `blocked`），不要只写前几个阶段。
- [ ] P1/P2 把 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`、`VSOCK_FAILED`、`PREFLIGHT_FAILED`、`BLOCKED_EXTERNAL`、`FAILED` 归一化为三个 outcome，而不是直接写入 `outcome`。
- [ ] `terminal` 只包含 §6.3 的五个字段；FLN1 7 / `execve_errno_13` 映射为 `fln1_code=7`、`exec_stage=execve`、`errno=13`。
- [ ] `not_tested` 包含 `encrypted_storage_persistence`、`20_cycles`、`negative_cases`；报告不含 `cycles`、`storage_persistence`、`negative_cases` 数组或 `stop_timings_ms`。
- [ ] 报告通过 `tools/fastlane-build/preflight-report.schema.json` 校验。
- [ ] 只有 `PREFLIGHT_TERMINAL_OK` 才触发 full；blocked 结果没有 full 行。
- [ ] 设备矩阵追加了一行，没有修改 harness 或历史行。
- [ ] 产物只落在外部 `protected-fastlane/<date>/preflight/<token>/`，仓库中没有原始设备报告。

---

## 9. 参考

- [DV-01 真机结果](fastlane-dv01-20261021.zh-CN.md)
- [DV-01A 平台变更请求](fastlane-dv01a-platform-change-request-20261022.zh-CN.md)
- [Fast Lane × QEMU 交叉优化 Spec](fastlane-cross-lane-spec-20261021.zh-CN.md)
- [Microdroid exec SELinux 研究](../../research/microdroid-exec-selinux-20261022.zh-CN.md)
- [敏感开发者数据策略](../../agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md)
