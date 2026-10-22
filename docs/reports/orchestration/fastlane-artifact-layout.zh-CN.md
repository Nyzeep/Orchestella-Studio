# Fast Lane Artifact 布局（设备无关）

日期：2026-10-22 ｜ 状态：v1 布局；runner 与操作者共同遵守
关联：[Preflight 契约](fastlane-preflight-contract.zh-CN.md)、[设备矩阵](fastlane-device-matrix.zh-CN.md)。

---

## 1. 根路径

所有真实运行产物放在 approved external resource root 下，按以下固定层级组织：

```text
protected-fastlane/<date>/<mode>/<token>/
```

| 段 | 规则 |
| --- | --- |
| `protected-fastlane` | 固定根名；位于外部 artifact 根，不在源码树、仓库根或临时目录 |
| `<date>` | preflight/full 开始日期的 UTC `YYYYMMDD`，例如 `20261022` |
| `<mode>` | CLI 模式：`qualification` / `preflight` / `full`；**不是**报告里的 `fastlane-dv01-preflight` |
| `<token>` | 每次 run 新生成的不透明标识，16–64 位小写 hex；推荐 16 位；不得复用，不得含设备名/型号/日期语义 |

示例（全部为占位符）：

```text
protected-fastlane/
  20261022/
    preflight/
      <token>/
        report.json
        run-summary.json
        commands.txt
        SHA256SUMS
        artifacts/
          <apk-name>.apk
          <test-apk-name>.apk
          <payload-name>.so
```

**不要按设备型号/厂商建目录。** 不允许 `protected-fastlane/<vendor>/...`、`.../by-device/<model>/...` 或把 alias 当目录名；设备身份只出现在报告/摘要的 `device_alias` 字段，且必须是操作者别名。

---

## 2. 每次 run 的文件

| 文件 | 必需 | 内容 |
| --- | --- | --- |
| `report.json` | 是 | 设备侧 preflight 报告的规范副本；内容必须匹配 `orchestella.fastlane.dv01.preflight.v1`，并通过 `tools/fastlane-build/preflight-report.schema.json` 校验 |
| `run-summary.json` | 是 | host runner 的运行摘要，schema `orchestella.fastlane.dv01.run.v1`；见 §3 |
| `commands.txt` | 是 | 脱敏后的命令计划/执行记录；见 §4 |
| `SHA256SUMS` | 是 | 对同目录文件与 `artifacts/` 的 SHA-256 清单；最后生成；见 §5 |
| `artifacts/` | 可选 | APK、test APK、payload 等构建输入；只在外部根，不进入仓库 |
| `README.txt` | 可选 | 人读说明；不得写设备标识、token 映射或原始日志 |

如果 runner 为 provenance 保留设备原始报告文件名（例如 `fastlane-dv01-preflight-<token>.json`），必须同时写 `report.json` 作为规范副本，并在 `run-summary.json.report_path` 中记录规范副本名；原始文件名副本只能作为附加文件，不得替代 `report.json`。两种情况下都不得覆盖同目录已有文件。

`report.json` 是完成设备阶段后的必需产物。若 runner 在安装、授权、拉取或 schema 校验之前就阻塞，设备侧没有报告可归档：此时 `run-summary.json` 必须写 `report_present=false`、`report_path=null`、`report_sha256=null`，并在 `README.txt` 记录阻塞原因；不得伪造 `report.json`，也不得把阻塞写成完整 run。

`report.json` 和 `run-summary.json` 都不包含：serial、model、Android ID、账号、token、原始 logcat、完整错误消息、本机绝对路径、真实 IP/MAC/SSID。

---

## 3. `run-summary.json` 最小字段

```json
{
  "schema": "orchestella.fastlane.dv01.run.v1",
  "mode": "preflight",
  "alias": "<alias>",
  "outcome": "PREFLIGHT_TERMINAL_OK",
  "reason_code": null,
  "report_path": "report.json",
  "report_sha256": "<sha256>",
  "apk_sha256": "<sha256>",
  "test_apk_sha256": "<sha256>",
  "started_at_utc": "<UTC>",
  "finished_at_utc": "<UTC>",
  "adb_invoked": true,
  "serial_recorded": false
}
```

约束：

- `alias` 是操作者别名；不是序列号/型号。
- `reason_code` 只在 blocked/failed 时使用固定代码；不得塞入原始异常消息。
- `report_path` 是相对文件名，不是绝对路径；不得以 `/` 开头。
- `serial_recorded` 必须为 `false`；runner 不得把 serial 写入任何持久文件。
- 不写 token；token 由目录名承载。

---

## 4. `commands.txt`

`commands.txt` 是可审计的命令计划，不是原始 adb 输出。规则：

- 一行一条命令；顺序与实际执行一致。
- 真实设备选择参数写成 `-s <serial>` 占位符；实际 serial 只在进程内存中使用。
- 本地 APK/test APK/报告路径写成 `<apk>`、`<test-apk>`、`<report-path>` 等占位符；不得出现本机绝对路径。
- 不写 stdout/stderr、不写 `adb devices` 原始行、不写 `getprop`/`dumpsys`/`logcat` 输出。
- 允许写固定 `-e` 参数和固定短命令；不允许写 token、账号、签名口令。

示例（占位符）：

```text
adb -s <serial> install -r <apk>
adb -s <serial> install -r <test-apk>
adb -s <serial> shell am instrument -w -e qaMode fastlane-dv01-preflight -e qaRunToken <token> -e qaDeviceAlias <alias> <component>
adb -s <serial> pull <report-path> <artifact-root>/report.json
```

示例里的 `<token>` 是命令行占位符，不是实际 token；实际 token 只在运行时注入，不写入 `commands.txt`。即使 `commands.txt` 在外部根，也不写真实 serial；这样复制/分享该目录时不会带出设备标识。

---

## 5. `SHA256SUMS`

- 使用 `sha256sum` 兼容格式：`<64-hex>  <relative-path>`。
- 覆盖 `report.json`、`run-summary.json`、`commands.txt` 和 `artifacts/` 下的文件。
- **不覆盖自身**；在最后一步原子生成。
- 路径相对于当前 run 目录，不写绝对路径。
- 生成后禁止再修改同目录文件；如需补文件，新建 run 或明确记录在 `README.txt` 并重新生成清单。

---

## 6. 不覆盖历史

1. 每个 run 使用新 token；目录已存在或 token 冲突时**失败并换 token**，不得 `--force`、不得清空、不得复用。
2. 不删除、不移动、不重命名历史 run；需要保留旧结论时让它留在原目录。
3. 不使用 `history/` 覆盖同一 token 的旧报告；如确需保留被替代的报告，放在同一 run 的独立文件并在 `README.txt` 说明，或新建 run。
4. 写入采用“临时文件 + rename”；`report.json`、`run-summary.json`、`commands.txt` 先落盘，`SHA256SUMS` 最后落盘。中途失败时保留半成品目录并标记，不得伪装成完整 run。

---

## 7. 原始设备报告与仓库边界

- 原始设备报告、完整命令输出、logcat/AVC、截图/录屏、serial 台账只允许在 approved external resource root；**不进入** Git、issue、PR、CI artifact、聊天或公开文本。
- 仓库只保留：本布局、[Preflight 契约](fastlane-preflight-contract.zh-CN.md)、[设备矩阵](fastlane-device-matrix.zh-CN.md) 和 `tools/fastlane-build/preflight-report.schema.json`。
- 设备矩阵不写 token；需要定位产物时，用 `run_date + device_alias + preflight_outcome` 在外部根检索，不把 token 复制进仓库。
- 提交前按 [SENSITIVE_DEVELOPER_DATA_POLICY](../../agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md) 做暂存差异检查；docs 默认被忽略，只对审查过的文件逐个 `git add -f`。

---

## 8. 权限与保留

- 目录建议 `0700`，文件建议 `0600`（平台支持时）；至少保证同机其他普通用户不可读。
- 不自动轮转/删除历史 run；保留策略由操作者在外部根管理，任何删除都需要明确批准。
- 共享 artifact 根时，先确认不含 token 映射、serial、账号或原始设备日志。
