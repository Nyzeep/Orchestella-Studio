# DV-01B P2 runner hardening

Type: follow-up hardening / host-only verification
Status: implemented（零设备；PR #7；CI 结果见实施进展）
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
- 本轮状态：**已实现、零设备测试通过**；真机项仍未测试，见下。

## 实施进展（2026-10-22）

基线 `main @ f443805`；分支 `fix/fastlane-dv01b-runner-hardening`；PR #7。合并 SHA 待合并后记录。

### 完成项

- [x] 1. validator `--strict` 与输入绑定
- [x] 2. `auth_token` 归档约束
- [x] 3. adb 解析兜底路径
- [x] 4. full blocked 的 `reason_code`
- [x] 5. repo-level sensitive-data guard CI

### 改动

- `tools/fastlane-device/run_dv01.py`
  - preflight 先原子写 `report.json`，再写 `run-summary.json`，最后用 canonical 路径执行 `validate.py --report <run>/report.json --summary <run>/run-summary.json --strict`；不再用 `.validate-<uuid>.json` 临时文件做 binding。
  - binding 失败：保留 `report.json` 供审计，summary 改写为 `BLOCKED_OUTCOMES[mode]` + `SCHEMA_VALIDATION_FAILED` + `schema_validation.status=FAIL`，非零退出；改写后的 summary 不再重跑 binding。
  - `_bind_report_inputs()`：preflight/full 在归档前校验 `report.apk_sha256 == --apk` 实际 SHA-256、`report.toolbox_manifest_sha256 == --toolbox-sha`；不匹配时不写 `report.json`，summary 记 `SCHEMA_VALIDATION_FAILED`。
  - full：`auth_token` 只允许 `empty` / `not-run`；其他值在写 report 前抛 `REPORT_AUTH_TOKEN_REJECTED`，值不进入任何归档文件。
  - `_resolve_adb()`：只按 `--adb` → `PATH` → `ANDROID_HOME/platform-tools/adb` → `ANDROID_SDK_ROOT/platform-tools/adb` 解析；删除 `/opt/android-sdk`、`/usr/bin/adb` 硬编码。解析仍在 `_prepare_run_dir` 之前，全部缺失时不创建 run 目录。
  - full 非 `PASS` 且报告无合法固定 `reason_code` 时，summary 写 `FULL_BLOCKED`；只有 `PASS` 允许 null。
  - qualification：不再把 qualification report 送进 preflight schema（此前 validator 存在时必然失败），只校验 summary；报告格式与语义未变。
  - `SHA256SUMS` 仍在最终 summary 写完之后生成。
- `tools/fastlane-device/test_run_dv01.py`：新增 19 条用例（binding 成功/report 哈希不匹配/APK 哈希不匹配/toolbox 哈希不匹配、auth_token 三态、adb 五种解析、full reason 四种、qualification 不送 report、SHA256SUMS 覆盖最终 summary）；fixture APK SHA 改为 fixture 字节的真实哈希。
- `tools/fastlane-device/README.md`：同步 Report validation、adb 解析、auth_token、full reason 与 Known boundary。
- `tools/ci/check_sensitive_data.py` + `.github/workflows/sensitive-data-guard.yml`：仓库级门禁，覆盖 tracked 图片/媒体与密钥/env、diff 新增行、commit message、tag message；输出仅 `SENSITIVE_DATA_OK` / `SENSITIVE_DATA_FOUND` 与类别计数。
- `.github/workflows/release.yml`：在打包上传前运行同一 gate。

### 测试证据

- `python -m unittest discover -s tools/fastlane-device -p 'test_*.py'` → **67 tests OK**
- `python -m pytest tools/fastlane-schema -q` → **39 passed**
- `python tools/ci/check_sensitive_data.py --self-test` → **SENSITIVE_DATA_OK**
- `python tools/ci/check_sensitive_data.py --range origin/main...HEAD` → **SENSITIVE_DATA_OK**
- `./gradlew :runtime:engine-fastlane:test :app:compileDebugAndroidTestJavaWithJavac`（ASCII 盘符路径）→ **BUILD SUCCESSFUL**
- 负例验证：临时仓库中的假 serial/绝对路径/邮箱/私钥/token/图片/keystore/env 均返回 `SENSITIVE_DATA_FOUND`；64-hex SHA-256 与 40-hex commit SHA 不误报。发现并修复了“新增行以 `++ ` 开头会被误当作 `+++` 头而漏扫”的解析缺陷。

### CI

- PR #7：https://github.com/Nyzeep/Orchestella-Studio/pull/7
- 代码提交：Sensitive data guard run 39807630680（success）；Fast Lane DV-01B zero-device run 39807630318（success）；Fast Lane zero-device run 39807630486（success）；CI run 39807630347（`Contract & Unit Tests` failure）。
- 文档/解析修复提交：Sensitive data guard run 39807858201（success）；Fast Lane DV-01B zero-device run 39807858091（success）；Fast Lane zero-device run 39807858120（success）；CI run 39807858109（`Contract & Unit Tests` 在无关的 AVF 模拟 socket 用例上挂起）。
- 失败/挂起位置为 `:runtime:engine-avf:avfTerminalStreamContractTest`，与本次改动无交集；同一任务本机执行通过（17s），历史 main/integration 上也有通过记录，属间歇性环境 flake。
- 最终门禁状态以 PR checks 为准；`Contract & Unit Tests` 未绿前不合并，也不声称 CI 通过。

### 剩余 not-tested（仅真机项）

- 真实设备上的 preflight/full runner 执行：安装、grant、instrumentation、report pull。
- 真实设备上的 full 与 20 cycles。
- 真实设备的 AVF protected/non-protected 结论与平台阻塞复现。
- 本票不声称任何真机结果。

### 已知边界：full 的 `--strict`

full 报告没有 v1 report schema；`validate.py --report` 会按 preflight schema 校验收而必然失败（`E_SCHEMA_REQUIRED /mode`、`E_SCHEMA_CONST /schema`）。因此 full 只做本地输入绑定 + summary 校验，未修改 contract/schema。若要求 full report 也走 canonical `--strict`，需先为 full report 定义 schema（契约变更，另立票）。
