# DV-01B P2 runner hardening

Type: follow-up hardening / host-only verification
Status: open — **not-tested**；本轮未改 runner 代码
Priority: P2（在把 P2 runner 当作 authoritative 路径之前必须完成）
Owner: Fast Lane integrator
Blocked by: DV-01B 集成分支合入 `main`（hardening 分支必须从包含 runner 的最新 `main` 切出）
External gates: 无；本票只改 host runner、schema 校验调用与 CI，不接设备

## 背景

DV-01B 集成 PR 已包含 `tools/fastlane-device/run_dv01.py` 与零设备测试，但以下五项在合并时被明确列为非阻塞后续项。它们不影响 P1 直连 preflight 的证据，但在使用 P2 runner 作为 authoritative 运行路径之前必须修复。

本票创建时 `main` 尚未包含 DV-01B，因此没有从 `main` 切出分支，也没有做任何半成品修改。所有项均为 `not-tested`。

## 必须完成项

### 1. validator `--strict` 与输入绑定

- 现状：`run_dv01.py` 分别调用 `validate.py --report <tmp>` 和 `--summary <tmp>`，没有 `--strict`；report/summary binding、APK SHA 与 toolbox SHA 的输入绑定没有由 runner 强制。
- 要求：
  - 先落盘 `report.json`，再落盘 `run-summary.json`，最后用一次 `validate.py --report report.json --summary run-summary.json --strict` 做 binding。
  - binding 失败必须把 summary 标为 `SCHEMA_VALIDATION_FAILED` 并以非零退出。
  - runner 必须校验报告里的 `apk_sha256` 等于 `--apk` 实际 SHA-256；`toolbox_manifest_sha256` 等于 `--toolbox-sha`。
  - 保留“validator 不存在时 local-only 校验”的现有降级，但 `schema_validation.full_schema_validation=false` 必须如实记录。
- 验收测试：binding 成功、report 哈希不匹配、APK 哈希不匹配、toolbox 哈希不匹配各一条 fake-adb 测试。

### 2. `auth_token` 归档约束

- 现状：full 报告原样归档；`auth_token` 字段可能带任意值（fixture 用 `empty`）。
- 要求：只允许 `empty` 或 `not-run`；其他值在归档前拒绝（`REPORT_AUTH_TOKEN_REJECTED`）或明确脱敏，不能原样写入 `report.json`。
- 验收测试：`empty` / `not-run` 通过；其他字符串被拒绝或脱敏，且 `commands.txt` 不含该值。

### 3. adb 解析兜底路径

- 现状：`_resolve_adb()` 在 PATH 之后硬编码 `/opt/android-sdk/platform-tools/adb` 和 `/usr/bin/adb`。
- 要求：只使用 `--adb`、`PATH`、`ANDROID_HOME`/`ANDROID_SDK_ROOT` 下的 `platform-tools/adb`；全部找不到就 `ADB_UNAVAILABLE` 失败。不要静默使用不可信的固定路径。
- 验收测试：显式 `--adb` 优先；PATH 命中；`ANDROID_HOME` 命中；全部缺失时失败且不创建 run 目录。

### 4. full blocked 的 `reason_code`

- 现状：full 模式 blocked 时 `_report_reason()` 可能返回报告里的 `null`，summary schema 允许 `null`，但矩阵/契约要求 blocked 使用固定 code。
- 要求：full blocked 且报告无固定 code 时，summary 写固定 `FULL_BLOCKED`（或契约新增的固定 code）；`null` 只允许出现在 `PREFLIGHT_TERMINAL_OK`/`PASS` 这类成功 outcome。
- 验收测试：full 报告 `outcome=FAILED` 且无 `reason_code` 时，summary 的 `reason_code` 是非空固定 code，并通过 schema。

### 5. repo-level sensitive-data guard CI

- 现状：没有仓库级扫描 job；当前只有 release secrets 与人工 policy。
- 要求：增加零设备 CI job（可挂到 `fastlane-dv01b-zero-device.yml` 或独立 workflow），扫描 tracked diff 中的：
  - 设备 serial / IMEI / Android ID / build fingerprint 形态；
  - 本机绝对路径（`[A-Za-z]:\\`、`/home/<user>`、`/Users/<user>`）；
  - token 形态的 16–64 位 hex 出现在非 artifact 路径；
  - 真实邮箱、账号、密钥材料。
- 要求：固定输出 `SENSITIVE_DATA_FOUND` / `SENSITIVE_DATA_OK`，不打印命中内容；附最小自测或 `--self-test`。
- 验收：在 PR 上能跑；对当前 `main` 返回 OK；对含假 serial/假绝对路径的 fixture 返回非零。

## 分支与验证

```bash
git switch main
git pull --ff-only origin main          # 确认 DV-01B 已合入
git switch -c fix/fastlane-dv01b-runner-hardening
```

完成后必须跑：

```bash
python -m pytest tools/fastlane-device -q
python -m pytest tools/fastlane-schema -q
./gradlew :runtime:engine-fastlane:test :app:compileDebugAndroidTestJavaWithJavac
```

不要直接 push `main`；从自己的分支开 PR。若 CI 未跑或失败，状态保持 `not-tested`。

## 边界

- 不改 FLN1 wire、payload 字节、accepted ADR、版本号、applicationId、签名身份或 release channel。
- 不改 preflight contract/schema 去适配实现；实现服从 contract/schema。
- 不接设备、不跑 full、不重试平台阻塞。
- 不把 token、serial、型号、账号、原始 logcat 或本机路径写入仓库/CI 输出。

## 证据

- 来源：DV-01B 交接 §9；`tools/fastlane-device/README.md` 的 Known boundary。
- 本轮状态：**未实现、未测试**；创建本票只是为了不丢失约束。
