# PL-02 protected 失败面统一与平台门复评 实施报告

- 日期：2026-10-23
- Base：`feat/accelerator-batch1 @ 5fa22c8`
- Branch：`feat/accelerator-batch2`
- 工单：`.scratch/orchestella-accelerator/issues/PL-02-protected失败面统一与平台门复评.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM。全部验证在 JVM、临时 .te fixture 与宿主 Python 上完成。
- CI：**未验证**。本批只做本地验证；PR/CI 尚未运行。

## 1. 结论

零设备范围内**完成**。

- 新增一套 canonical 失败词汇：`ProtectedFailureClass`（7 类）、`ProtectedFailureCode`（16 个固定 code）、`ProtectedFailure`（code + stage + errno，固定渲染）。
- preflight/full/runner/矩阵共用同一写法：`CODE stage=<stage> errno=<n>`；legacy 写法只在输入边缘归一，不再出现在新输出。
- 新增离线 `tools/ci/check_microdroid_exec_gate.py`，支持 `allow`/`neverallow`、`coredomain`/`domain` 继承、属性规则、宏（含嵌套）、注释、多行与反斜杠续行；输出 `GATE_OPEN`/`GATE_CLOSED`/`GATE_UNCLEAR` 和证据行号。
- A16、A17、AOSP main 三个最小 fixture 均为 `GATE_CLOSED`（payload 类型 `system_file`）。A17 只对 `tenant_apk_file` 有 `x_file_perms`，不是主 payload 挂载类型。
- 新增平台门复评 runbook；矩阵新行与 runner 新报告改用统一 code，§7 历史行未改写。

## 2. 现状与代码路径

- 统一前的四层写法：`FastLaneDv01Contract.java:272-293` 输出 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` 与 `TERMINAL_ERROR_FLN1_<n>`；`run_dv01.py:681-750` 只校验 `[A-Z][A-Z0-9_]{1,63}`；矩阵 §3.1 写 `(fln1=7, execve errno=13)`；设备 issue 里还有 `execve_errno_13` 与 `PREFLIGHT_TERMINAL_BLOCKED`。
- 平台事实：A16/main 的 `microdroid_app` 声明为 `domain, coredomain`（`microdroid/system/private/microdroid_app.te:9`），`domain.te:92` 只给 `coredomain` 对 `system_file` 的 `execute`；A17 增加 `allow microdroid_app tenant_apk_file:file x_file_perms`，主 payload 仍是 `system_file`。
- 没有可重复检查器；fixture 需要人工从 AOSP/ROM 拷贝。

## 3. 设计

### 3.1 失败分类

| Class | Canonical codes |
| --- | --- |
| `PLATFORM_BLOCKED` | `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` |
| `PERMISSION` | `PERMISSION_DENIED`、`HIDDEN_API_BLOCKED` |
| `SCHEMA` | `SCHEMA_INVALID`、`CONFIG_INVALID` |
| `LIFECYCLE` | `TERMINAL_ERROR`、`VM_CREATE_FAILED`、`READY_TIMEOUT`、`HELLO_FAILED`、`VSOCK_FAILED`、`PROCESS_DIED` |
| `STOP` | `STOP_UNCONFIRMED`、`DRAIN_TIMEOUT` |
| `INSTALL` | `PAYLOAD_VERIFY_FAILED`、`PROTECTED_UNAVAILABLE` |
| `UNKNOWN` | `FASTLANE_UNKNOWN` |

`ProtectedFailure.fromTerminal(fln1, detail)` 只在 `fln1=7 + execve + errno=13` 时产出平台阻塞；其它 FLN1 错误产出 `TERMINAL_ERROR`。`fromLegacy` 接受 `TERMINAL_ERROR_FLN1_7`、`execve_errno_13`、`PREFLIGHT_TERMINAL_BLOCKED` 与矩阵旧 cell；没有 `fln1=7/errno=13` 证据的 `PREFLIGHT_TERMINAL_BLOCKED` 只映射为 `TERMINAL_ERROR`，不冒充平台阻塞。原始 AVC、路径、logcat、token 不进入类型。

### 3.2 Gate 检查器

`evaluate(paths, payload_type)`：

1. 两遍解析：先收集所有 `define(... )`，再解析规则，保证跨文件宏可见。
2. 有效域 = `microdroid_app` + 其 `type` 属性（`domain`、`coredomain`）；有效 payload 类型 = `system_file` + 其属性（如 `system_file_type`）。
3. 支持 `allow`/`neverallow`、`typeattribute`、注释、跨行 brace、反斜杠续行、嵌套宏。
4. 判定：
   - 命中 `allow ... execute_no_trans` → `GATE_OPEN`；
   - 有 `neverallow` 冲突 → `GATE_UNCLEAR`；
   - 命中未解析的 `*_perms` 宏且无 allow → `GATE_UNCLEAR`；
   - domain 或 payload 类型既未声明也未被规则提及 → `GATE_UNCLEAR`；
   - 解析完成无命中 → `GATE_CLOSED`，证据为“有 execute 但无 execute_no_trans”的规则行与类型声明行。
5. 不联网；缺文件返回 `POLICY_FILE_MISSING`，不可读返回 `POLICY_FILE_UNREADABLE`。

### 3.3 Fixtures

| Case | Branch | Commit | 结果 |
| --- | --- | --- | --- |
| `a16` | `android16-release` | `d4a7f39...` | `GATE_CLOSED` |
| `a17` | `android17-release` | `e066568...` | `GATE_CLOSED` |
| `aosp-main` | `main` | `4571ddd...` | `GATE_CLOSED` |

fixture 是最小片段，不是完整 policy；来源、URL、访问日期与 Apache-2.0 许可证写在 `SOURCES.md`。

### 3.4 矩阵与 runner

- 矩阵新行统一写 `CODE stage=<stage> errno=<n>`；§7 的 2026-10-22 历史行保留旧写法，只追加不改写。
- `run_dv01.py` 在归档前拒绝新报告里的 `execve_errno_13`、`PREFLIGHT_TERMINAL_BLOCKED`，以及 `TERMINAL_ERROR_FLN1_7 + execve + errno=13`，返回 `REPORT_TERMINAL_INVALID`。
- 历史报告/矩阵仍可用 `protected_failure.from_legacy` 读取。

## 4. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `runtime/engine-fastlane/.../ProtectedFailureClass.java` | 新增 | 7 类失败分类 |
| `runtime/engine-fastlane/.../ProtectedFailureCode.java` | 新增 | 16 个固定 code |
| `runtime/engine-fastlane/.../ProtectedFailure.java` | 新增 | 归一、渲染、stage/errno 校验 |
| `runtime/engine-fastlane/src/test/.../ProtectedFailureTest.java` | 新增 | 11 个 JVM 测试 |
| `tools/ci/check_microdroid_exec_gate.py` | 新增 | 离线 policy 判定器 |
| `tools/ci/test_check_microdroid_exec_gate.py` | 新增 | 17 个测试 |
| `tools/ci/fixtures/sepolicy/{a16,a17,aosp-main}/` | 新增 | 最小 policy 片段 |
| `tools/ci/fixtures/sepolicy/EXPECTATIONS.json`、`SOURCES.md` | 新增 | 期望值与来源 |
| `tools/fastlane-device/protected_failure.py` | 新增 | runner 侧同一词汇 |
| `tools/fastlane-device/test_protected_failure.py` | 新增 | 9 个测试 |
| `tools/fastlane-device/run_dv01.py` | 修改 | legacy 写法入站拒绝 |
| `tools/fastlane-device/test_run_dv01.py` | 修改 | 新增 1 个 legacy 拒绝测试 |
| `docs/reports/orchestration/fastlane-device-matrix.zh-CN.md` | 修改 | 新写法；历史行保留 |
| `docs/reports/tickets/pl-02/platform-gate-recheck.zh-CN.md` | 新增 | 复评 runbook |
| `.github/workflows/fastlane-zero-device.yml` | 修改 | 增加 tools/ci 单测与 gate fixture 步骤 |

没有修改 `FastLaneDv01Contract`、FLN1 wire、payload、ADR、版本号或 applicationId。

## 5. 验收

| 命令 | 结果 |
| --- | --- |
| `python -m pytest tools/ci -q` | 23 passed |
| `python tools/ci/check_microdroid_exec_gate.py --policy-fixtures tools/ci/fixtures/sepolicy` | `GATE_SUMMARY open=0 closed=3 unclear=0`，exit 0 |
| `python tools/ci/check_sensitive_data.py --self-test` | `SENSITIVE_DATA_OK` |
| `./gradlew :runtime:engine-fastlane:test --tests '*ProtectedFailure*'` | BUILD SUCCESSFUL；11/11 |
| `python -m pytest tools/fastlane-device tools/ci/test_check_microdroid_exec_gate.py -q` | 94 passed, 7 subtests passed |

Gradle 首次运行遇到 Maven Central 429；使用仓库已有的 `-Porchestella.repositories.useAliyunMirror=true` 显式镜像后通过，并设置 `ANDROID_HOME=<sdk-root>`。没有修改依赖版本。

## 6. 偏差

1. **Canonical 平台 code 沿用 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`**，没有新造 `PLATFORM_EXEC_BLOCKED`。理由：设备报告、runner 测试和矩阵已把它作为固定 code；换名会制造第二套写法。新格式只统一附加的 stage/errno 表达。
2. **A17 fixture 判 `GATE_CLOSED`（对 `system_file`）**。A17 的 `tenant_apk_file` 有 `x_file_perms`，但主 payload APK 仍以 `system_file` 挂载；不能把 tenant 机制当作主 payload 的解除。
3. **`PREFLIGHT_TERMINAL_BLOCKED` 不再无条件映射为平台阻塞**。它是 outcome，不是 reason；只有带 `fln1=7/errno=13` 证据时才映射为平台 code。
4. **AOSP main fixture 的 commit 是 2025-03-27**。抓取时 `main` 分支头就是这个 commit；报告如实记录，不假设它比 A16 新。

## 7. 风险与未验证

- **not-tested**：OEM/ROM 的真实 microdroid policy；fixture 只覆盖 AOSP 三个分支的公开片段。
- **not-tested**：真实设备上的 new report 生成端是否已使用 canonical code；本票只改 host 校验与矩阵模板。
- 判定器是文本级解析，不替代 `checkpolicy`/neverallow 编译；它只回答“这些片段是否给出 execute_no_trans”。
- CI 未运行；新增 workflow 步骤只做本地 YAML/脚本检查。
- `GATE_CLOSED` 只代表 fixture 当前状态，不是永久产品结论。

## 8. 待维护者决策

1. 是否接受继续以 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` 作为 canonical 平台 code（而不是改名）。
2. 是否要求设备端 probe 在下一版直接输出 `ProtectedFailure.render()` 的 stage= 格式。
3. A17 tenant APK 是否另开 fixture（`--payload-type tenant_apk_file`）作为研究项，而不是主 payload 门。
