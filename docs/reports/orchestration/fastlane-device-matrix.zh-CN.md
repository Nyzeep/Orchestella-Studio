# Fast Lane 设备矩阵（设备无关模板）

日期：2026-10-22 ｜ 状态：模板；每台设备/每次 preflight 运行只追加一行
关联：[Preflight 契约](fastlane-preflight-contract.zh-CN.md)、[Artifact 布局](fastlane-artifact-layout.zh-CN.md)。

---

## 1. 用途

设备矩阵是**人读的运行索引**，不是证据正文，也不是设备画像库。它回答：哪台设备在哪个 Android/ABI 组合上跑过 preflight、结果是什么、是否进入 full。

矩阵故意不预置任何真实设备名、型号、序列号或真实 alias；`qa-device` 只是契约默认占位值。下表中的 `<alias>`、`<alias-a>`、`<alias-b>` 都是占位符；实际运行时由操作者提供稳定别名，别名的真实映射只保存在 approved external resource root，不进入仓库。

---

## 2. 通用表头

固定列（顺序不要变）：

```text
run_date, device_alias, sdk_int, abi, virtualization_framework, protected_bit, manage_permission, preflight_outcome, terminal_reason, full_20_cycles, notes
```

Markdown 形式：

| `run_date` | `device_alias` | `sdk_int` | `abi` | `virtualization_framework` | `protected_bit` | `manage_permission` | `preflight_outcome` | `terminal_reason` | `full_20_cycles` | `notes` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

## 3. 列定义

| 列 | 允许值 / 写法 | 说明 |
| --- | --- | --- |
| `run_date` | `YYYY-MM-DD`（UTC） | 以 preflight 开始时间为准；与报告 `started_at_utc` 同一天 |
| `device_alias` | `<alias>` 占位；实际值匹配 `[A-Za-z0-9._-]{1,32}` | 操作者提供的稳定别名；默认 `qa-device`；绝不写序列号/型号/厂商名 |
| `sdk_int` | 整数，例如 `36` | 来自报告 `sdk_int` |
| `abi` | 例如 `arm64-v8a`、`x86_64` | 来自报告 `abi`，只写主 ABI |
| `virtualization_framework` | `true` / `false` | 设备是否声明 `android.software.virtualization_framework` |
| `protected_bit` | `true` / `false` | `getCapabilities` 的 protected bit |
| `manage_permission` | `GRANTED` / `DENIED` / `UNKNOWN` | 只写权限状态，不写授权命令或账号 |
| `preflight_outcome` | `PREFLIGHT_TERMINAL_OK` / `PREFLIGHT_TERMINAL_BLOCKED` / `PREFLIGHT_BLOCKED_EXTERNAL` | 只能是契约的三个 outcome |
| `terminal_reason` | 固定 reason code | OK 写 `PREFLIGHT_TERMINAL_OK`；平台阻塞写 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)`；外部阻塞写 `PERMISSION_DENIED` 等 |
| `full_20_cycles` | `yes` / `no` / `not-run` | 只有 preflight OK 且实际 20/20 通过才写 `yes`；`not-run` 表示门禁未放行；`no` 表示跑了但未 20/20 |
| `notes` | 短句，固定代码/计数 | 只写原因、门禁和外部 artifact 根类别；不写原始日志、路径、token、账号 |

### 3.1 `terminal_reason` 的写法

- `PREFLIGHT_TERMINAL_OK`：terminal 固定命令退出码 0，输出匹配固定 marker。
- `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)`：Android 16 `microdroid_app` 的 `execute_no_trans` 平台阻塞；不要附原始 AVC/logcat。
- `VSOCK_FAILED`：control 或 terminal 的 vsock connect/HELLO 失败。
- `TERMINAL_ERROR_FLN1_<n>`：其他 FLN1 terminal 错误。
- `HIDDEN_API_BLOCKED` / `PERMISSION_DENIED` / `PROTECTED_UNAVAILABLE` / `PAYLOAD_VERIFY_FAILED`：pre-lifecycle 外部或安装门禁。
- `QA_ARGUMENTS_REJECTED`：参数/alias/端口/cycle 参数不合法。

不要写 `foo: raw message`、`/data/...`、`adb -s <real>` 或设备型号。

---

## 4. 追加规则

1. **设备变化只追加行，不改 harness。** 新设备、新 alias、新 SDK/ABI、权限状态变化、APK/payload/toolbox 变化，都新增一行；不要修改既有行来“覆盖”历史。
2. **一次 preflight 一行。** 同一设备多次运行就是多行；不要合并成“最新状态”而丢掉失败样本。
3. **Full 结果跟随对应 preflight。** 优先在同一行的 `full_20_cycles` 记录跟进结果；如果 full 另起一次独立运行，另追加一行，并在 `notes` 写 `follow-up to <run_date>/<alias>`（不写 token）。
4. **Blocked 也记录。** `PREFLIGHT_TERMINAL_BLOCKED` 和 `PREFLIGHT_BLOCKED_EXTERNAL` 必须留行；失败样本不能删。
5. **不写历史结论为当前事实。** 旧行只代表当时设备/构建/权限状态；不得把旧 `PREFLIGHT_TERMINAL_OK` 复制到新设备或新构建。
6. **不做设备目录。** 不按型号/厂商建矩阵分组；alias 是唯一设备键。

---

## 5. 示例行（全部为占位符）

以下示例只说明写法，不是真实设备记录；`<alias-a>` 等不是默认设备。

| `run_date` | `device_alias` | `sdk_int` | `abi` | `virtualization_framework` | `protected_bit` | `manage_permission` | `preflight_outcome` | `terminal_reason` | `full_20_cycles` | `notes` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-22 | `<alias-a>` | 36 | arm64-v8a | true | true | GRANTED | `PREFLIGHT_TERMINAL_OK` | `PREFLIGHT_TERMINAL_OK` | yes | 20/20；artifact 在外部根 |
| 2026-10-22 | `<alias-b>` | 36 | arm64-v8a | true | true | GRANTED | `PREFLIGHT_TERMINAL_BLOCKED` | `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)` | not-run | 平台 SELinux 阻塞；未绕过，未跑 full |
| 2026-10-22 | `<alias-c>` | 35 | arm64-v8a | true | false | DENIED | `PREFLIGHT_BLOCKED_EXTERNAL` | `PERMISSION_DENIED` | not-run | 权限/protected 门禁；修复后需重新 preflight |
| 2026-10-23 | `<alias-d>` | 34 | x86_64 | false | false | UNKNOWN | `PREFLIGHT_BLOCKED_EXTERNAL` | `PROTECTED_UNAVAILABLE` | not-run | 设备不满足 protected 前置；不进入 full |
| 2026-10-23 | `<alias-e>` | 36 | arm64-v8a | true | true | GRANTED | `PREFLIGHT_TERMINAL_OK` | `PREFLIGHT_TERMINAL_OK` | no | full 已跑但未 20/20；保留失败样本 |

说明：最后一行演示“preflight OK 但 full 失败”必须如实写 `no`，不能因为 preflight OK 就写 `yes`。

---

## 6. 维护边界

- 矩阵只记录固定 reason code 和计数；原始设备报告、命令输出、logcat、截图只放外部 artifact 根。
- 不在矩阵里写 token；token 只出现在外部 artifact 路径和报告文件名。
- 不把 alias 映射、序列号台账或真实型号写进本文件。
- 新增列属于契约变更：先改 [Preflight 契约](fastlane-preflight-contract.zh-CN.md) 和 schema 版本，再改矩阵模板；不要在单次运行中临时加列。
