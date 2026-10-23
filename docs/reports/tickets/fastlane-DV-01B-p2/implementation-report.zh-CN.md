# DV-01B P2 runner hardening 实施报告

- 日期：2026-10-22
- Base：`main @ f443805`
- Branch：`fix/fastlane-dv01b-runner-hardening`
- PR：#7
- 远端：github = `git@github.com:Nyzeep/Orchestella-Studio.git`
- 设备：本会话没有连接任何设备；没有安装、没有 grant、没有 instrumentation、没有 full、没有 20 cycles。

## 1. 结论

五项 hardening 已实现并通过零设备测试：

1. preflight 归档后以 canonical 路径做 `--strict` report/summary binding，并在归档前强制 APK/toolbox 输入绑定。
2. full 报告的 `auth_token` 只允许 `empty` / `not-run`，其他值归档前拒绝。
3. adb 只从显式参数、PATH 和 Android SDK 环境根解析，删除不可信固定路径。
4. full 非 `PASS` 结果不再丢 reason：优先沿用报告的固定 code，否则写 `FULL_BLOCKED`。
5. 新增仓库级敏感数据门禁，覆盖 PR/push/tag 与 release 上传前。

没有修改 contract、schema、FLN1 wire、payload 字节、accepted ADR、版本号、applicationId、签名身份或 release channel。

## 2. 目标与验收来源

- 工单：`.scratch/orchestella-fastlane/issues/DV-01B-p2-runner-hardening.md`
- 契约：`docs/reports/orchestration/fastlane-preflight-contract.zh-CN.md` §5–§7
- 政策：`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md` §3.1–§3.2
- Runner 边界：`tools/fastlane-device/README.md` 的 Known boundary

实现服从 contract/schema；本轮没有为了让测试通过而放宽任何 schema。

## 3. 改动

### 3.1 `tools/fastlane-device/run_dv01.py`

**preflight 的 strict binding 顺序**

1. 解析并本地校验 report；
2. `_bind_report_inputs()` 校验 report 的 `apk_sha256` 与 `toolbox_manifest_sha256`；
3. 原子写 `report.json`；
4. 构建并原子写 `run-summary.json`（`report_sha256` 指向刚写的字节）；
5. 用 canonical 路径执行 `validate.py --report <run>/report.json --summary <run>/run-summary.json --strict`；
6. 失败时保留 `report.json`，把 summary 改写为 `BLOCKED_OUTCOMES[mode]` + `SCHEMA_VALIDATION_FAILED` + `schema_validation.status=FAIL`，非零退出；
7. `README.txt` 与 `SHA256SUMS` 最后写，`SHA256SUMS` 覆盖最终 summary 与保留的 report。

不再使用 `.validate-<uuid>.json` 临时文件：`--strict` 要求 `report_path` 是 canonical 的 `report.json`，且 `check_binding` 会核对 report 路径、哈希、alias、outcome 与 `apk_sha256`。

**输入绑定**：preflight/full 在归档前要求 report 的 APK SHA-256 等于 `--apk` 实际字节哈希、toolbox manifest SHA-256 等于 `--toolbox-sha`；不匹配时不写 `report.json`。

**auth_token**：full 报告只接受 `empty` / `not-run`。其他值在写 report 前抛 `REPORT_AUTH_TOKEN_REJECTED`；原值不会进入 `report.json`、`run-summary.json`、`commands.txt`、`README.txt` 或任何归档文件。

**adb 解析**：`--adb`（必须是文件）→ `PATH` → `ANDROID_HOME/platform-tools/adb` → `ANDROID_SDK_ROOT/platform-tools/adb`；全部缺失为 `ADB_UNAVAILABLE`。显式 `--adb` 无效时不会回退。解析发生在创建 run 目录之前。

**full reason_code**：`PASS` 允许 null；其他 full outcome 若报告带合法固定 code 则沿用，否则写 `FULL_BLOCKED`。

**qualification**：不再把 qualification report 送给 preflight validator，只校验 summary。此前 validator 存在时 qualification 报告必然被 preflight schema 拒绝；报告格式与语义未改。

### 3.2 测试

`tools/fastlane-device/test_run_dv01.py` 新增 19 条用例：

| 要求 | 用例 |
| --- | --- |
| strict binding 成功 | `test_strict_binding_uses_canonical_run_paths` |
| report 哈希不匹配 | `test_strict_binding_detects_report_hash_mismatch` |
| APK 哈希不匹配 | `test_preflight_rejects_report_apk_sha_mismatch`、`test_full_rejects_report_apk_sha_mismatch` |
| toolbox 哈希不匹配 | `test_preflight_rejects_report_toolbox_sha_mismatch` |
| validator 缺失降级 | `test_validator_absent_preflight_is_local_only` |
| qualification 隔离 | `test_qualification_report_is_not_bound_to_preflight_schema` |
| auth_token | `test_full_auth_token_empty_and_not_run_are_accepted`、`test_full_auth_token_rejected_and_not_archived` |
| adb 解析 | 显式优先、PATH、ANDROID_HOME、ANDROID_SDK_ROOT、全部缺失不建目录、显式无效不回退 |
| full reason | PASS null、无 code 写 `FULL_BLOCKED`、保留报告 code、真实 validator 接受 |
| SHA256SUMS | binding 失败后仍指向最终 summary |

fixture 的 `apk_sha256` 已改为 fixture APK 字节的真实 SHA-256；不匹配用例显式覆盖成错误值。

### 3.3 敏感数据门禁

- `tools/ci/check_sensitive_data.py`（stdlib only，Python 3.12）
  - tracked 树：图片/视频扩展名、`.jks/.keystore/.p12/.pfx/.pem/.key`、`.env`/`local.properties`、带签名凭据键的 `gradle.properties`。
  - diff 新增行：设备标识（serial/IMEI/Android ID/build fingerprint/MAC）、Windows 盘符路径、home 目录路径、真实邮箱、私钥头、16–63 位 hex token（排除 SHA-256/commit SHA/hash 上下文）。
  - commit message 与 annotated tag message。
  - 输出只有 `SENSITIVE_DATA_OK` / `SENSITIVE_DATA_FOUND` 与类别计数；不打印命中行、值、路径或 commit 正文。
  - `--self-test` 在运行时临时目录生成合成负例，逐项断言命中；负例值不进入仓库。
- `.github/workflows/sensitive-data-guard.yml`：`pull_request`（无 paths 过滤）、`push main`、`push tags`、`workflow_dispatch`；`fetch-depth: 0`；`permissions: contents: read`；无 `continue-on-error`。
- `.github/workflows/release.yml`：在 “Assemble release bundle” 前运行 self-test 与默认扫描。

校准：当前分支的 `origin/main...HEAD` 返回 `SENSITIVE_DATA_OK`；64-hex SHA-256、40-hex commit SHA、稳定别名 `device-a/b`、`redacted-serial-hash`、`adb -s <serial>` 占位符不误报。

测试中发现并修复了一个门禁缺陷：旧解析把以 `++ ` 开头的真实新增行误判为 `+++` 文件头而漏扫。现在使用 `--output-indicator-new` 并显式跟踪文件头/ hunk，临时仓库回归用例同时包含普通新增行与 `++ ` 前缀新增行，二者都命中。

## 4. 测试证据

| 命令 | 结果 |
| --- | --- |
| `python -m unittest discover -s tools/fastlane-device -p 'test_*.py'` | 67 tests OK |
| `python -m pytest tools/fastlane-schema -q` | 39 passed |
| `python tools/ci/check_sensitive_data.py --self-test` | SENSITIVE_DATA_OK |
| `python tools/ci/check_sensitive_data.py --range origin/main...HEAD` | SENSITIVE_DATA_OK |
| `./gradlew :runtime:engine-fastlane:test :app:compileDebugAndroidTestJavaWithJavac` | BUILD SUCCESSFUL |

说明：

- device fake-adb 套件在 WSL（POSIX）执行；Windows 原生路径会因 shebang 得到 `ADB_INVOCATION_FAILED`，这是既有边界。
- Gradle 在 ASCII 盘符路径执行；从含中文/括号的路径直接运行会出现既有的 `ClassNotFoundException`。
- `pytest tools/fastlane-schema` 的 `test_strict_binding_ok` 断言 fixture 的字节哈希，工作树若被 CRLF 物化会失败；本次在 LF 物化后 39 passed。GitHub Actions 的 LF checkout 是权威环境。

## 5. CI

- PR：https://github.com/Nyzeep/Orchestella-Studio/pull/7
- 代码提交（`e8c1390`）：
  - Sensitive data guard：run 39807630680（success）
  - Fast Lane DV-01B zero-device：run 39807630318（success）
  - Fast Lane zero-device：run 39807630486（success）
  - CI：run 39807630347（`Contract & Unit Tests` failure）
- 文档/解析修复提交（`2dd0b75`）：
  - Sensitive data guard：run 39807858201（success）
  - Fast Lane DV-01B zero-device：run 39807858091（success）
  - Fast Lane zero-device：run 39807858120（success）
  - CI：run 39807858109（`Contract & Unit Tests` 在无关的 AVF 模拟 socket 用例上挂起，未完成）

CI 失败/挂起的具体位置是 `:runtime:engine-avf:avfTerminalStreamContractTest` 的
`closingTheGuestSideEndsTheHostStream`（先出现 `Connection reset`，后一次直接阻塞）。
该任务与本次改动无交集；同一任务在本机从 ASCII 路径执行通过
（`AVF_TERMINAL_STREAM_CONTRACT_TEST_OK ...`，17s）。历史上同一 job 在 `integration/fastlane-dv01b`
与 `main` 上也有过通过记录，属间歇性环境 flake。最终门禁状态以 PR checks 为准；
在 `Contract & Unit Tests` 未绿之前不合并，也不声称 CI 通过。

## 6. 边界与残余风险

- **未接设备**。本报告的 fake-adb 结果不是真机证据；真机项保持 not-tested。
- **full 无 report schema**。full 报告不能用 `validate.py --report` 做 `--strict`；本轮只能本地输入绑定 + summary 校验。若要把 full 也纳入 canonical binding，需要先定义 full report schema（契约变更，另立票）。
- **`schema_validation.status=FAIL` 也覆盖本地输入绑定拒绝**：此时 validator 尚未运行，`full_schema_validation` 仍表示 validator 可用。README 已说明。
- **敏感的 `auth_token` 值只保证不落盘**；runner 不扫描 full report 的其他自由字段（只做 token/serial 子串检查与 auth_token 白名单）。
- **token 规则校准**：40/64 hex 被当作 commit SHA / SHA-256 provenance 放行；带 hash 标签的 16–63 hex 也放行。给设备标识做哈希并贴在 hash 标签后仍可能漏检，依赖设备标识标签规则与人工 review。
- **full 非 PASS 的进程退出码未改**（仍为 0），本轮只修 reason_code；若要让 CI 对 full 失败敏感，需要另立票调整退出码语义。
- **release gate 只覆盖 HEAD 树 + HEAD commit message**（release checkout 是浅克隆）；全历史审计仍按政策 §3.3 执行。

## 7. 未决 / 建议下一步

1. 真机恢复后执行一次 preflight runner，验证 canonical binding 与 `auth_token` 拒绝在真实报告上的行为；仍然只记录脱敏结论。
2. 为 full report 定义 v1 schema，之后把 full 也纳入 `--strict` report/summary binding。
3. 评估 full 非 PASS 的退出码（当前 0）是否符合 CI/自动化预期。
4. 按政策 §3.3 做一次全历史/tag/PR 文本审计；CI 门禁只覆盖新增内容。
