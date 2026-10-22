# DV-01B: 设备无关 preflight 实现与零设备门禁

Type: implementation / host-only verification
Status: merged-to-main；device preflight blocked by platform（合并后已按 main 重建重跑，结果一致；不改变 DV-01 结论）
Priority: P1（full DV-01 之前的准入检查）
Owner: Fast Lane integrator
Blocked by: DV-01A 平台 SELinux `execute_no_trans`（真机 preflight 前不绕过）
External gates: 真实设备 preflight；操作者显式触发 full；不得进入 DV-02

## 目标

在不接设备的前提下，把 DV-01B preflight 的 schema、设备无关 instrumentation 模式和 host runner 合成一个可审查分支，并证明：

- preflight 只创建一个 VM、只跑一次固定 terminal 命令、只 stop/delete 一次；
- 不跑 encrypted storage、20 cycles 或负例矩阵；
- 报告严格落在九个阶段、三个 outcome 和三个 `not_tested` 精确值上；
- host runner 的 artifact 布局、run-summary 和 P3 validator 一致；
- 不修改 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。

## 已完成

- 合并 `feat/fastlane-preflight-schema`、`feat/fastlane-preflight-mode`、`feat/fastlane-device-runner` 到 `integration/fastlane-dv01b`，保留 `--no-ff` 合并节点。
- P1：新增 `fastlane-dv01-preflight` QA 模式；九阶段、三 outcome、三个 `not_tested` 值；单次 create/run/terminal/stop/delete；不写 storage marker、不跑 cycles/负例。
- P2：新增 `tools/fastlane-device/run_dv01.py`；外部 artifact 根布局、脱敏 `commands.txt`、`run-summary.json`、`SHA256SUMS`；serial 只留在进程内存，token 只出现在 run 目录名。
- P3：新增 preflight/run-summary 校验器、fixtures 和 host-only CI；报告与 summary 的 schema 已对齐 P2 的 blocked-before-report 形态。
- 集成修正：P1 terminal 成功阶段使用 `TERMINAL_EXIT_0`；UNCONFIRMED stop 不再通过 `close()` 触发第二次 stop；preflight 的 `storageMarker` 不再被记为 true；P3 summary schema 接受三种 CLI 模式和 `report_present=false` 的 null 绑定；CI 增加 `tools/fastlane-device` 测试路径与 pytest 步骤。

## 零设备验证（2026-10-22）

- `tools/fastlane-build/build-payload.sh`：`FASTLANE_PAYLOAD_OK files=2 symbol=AVmPayload_main`。
- `fastlane_build.py build --manifest fastlane-toolbox/manifest.json`：toolbox 构建成功。
- `./gradlew :app:fastlaneZeroDeviceCheck`（qa/dv01，payload 与 toolbox 属性）：`BUILD SUCCESSFUL`。
- `python3 -m pytest tools/fastlane-schema -q`：39 passed。
- `python3 -m pytest tools/fastlane-device -q`：48 passed。
- `:runtime:engine-fastlane:test` 与 `:app:compileDebugAndroidTestJavaWithJavac`：通过。
- 本机为 JDK 17 / Android SDK 36 / build-tools 36.1.0 / NDK 27.0.12077973 / CMake 3.22.1；未接 adb、模拟器或真机。

## 真机 preflight（2026-10-22）

- 构建来源：`integration/fastlane-dv01b` @ `152dff9`（合并候选；`main` 尚未合并）。
- 设备：操作者别名 `qa-device`；API 36 / arm64-v8a / `virtualization_framework=true` / `protected_bit=true` / 两项权限 `GRANTED`（不记录型号或序列号）。
- P1 直连一次：`outcome=PREFLIGHT_TERMINAL_BLOCKED`，`terminal.reason_code=TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`，`exec_stage=execve`，`errno=13`；stop `CONFIRMED`，delete `VM_DELETED`。
- `not_tested`：`encrypted_storage_persistence`、`20_cycles`、`negative_cases`。
- 报告通过 `validate.py --report --strict`；report/summary binding 通过。
- 外部 artifact：`protected-fastlane/20261022/preflight/<token>/`（token 不记录在仓库）。
- 未跑 full：平台阻塞按契约停止，未重试、未绕过。
- 构建中发现并修复 P1 报告的 `virtualization_framework` 特性名笔误（点号 → 下划线，改用 `AvfCapabilityEvaluator.VIRTUALIZATION_FEATURE`），附源码级回归测试；修复前的一次运行作废，不作为证据。

## 合并后重建与真机 preflight（2026-10-22，main @ e599610）

- PR #6 已合并进 `main`（merge commit `e599610`，父提交 `47837f7` + `359f118`）；合并前修掉两处 CI 失败：CI/release 门禁补齐七个 `:runtime:fastlane-*:check`，payload 构建固定 NDK 27.0.12077973 与 CMake 3.22.1 并修正 NDK 内 llvm 工具定位。
- 契约 §5.5「变更即失效」：合并后 QA 版本后缀带 merge commit SHA，APK 字节改变，因此从 `main` 全新重建 toolbox、payload 与两个 APK，未复用合并前的任何结论。
- 重建产物：`orchestella.fastlane.dv01.qa.dv01` 通道（`versionName` 含 `e599610`），APK `f28d0749…`，androidTest APK `a72b44e2…`，payload `2a3ae6b4…`，toolbox manifest `e0352292…`。
- P1 直连一次：`outcome=PREFLIGHT_TERMINAL_BLOCKED`，`terminal.reason_code=TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`，`exec_stage=execve`，`errno=13`；stop `CONFIRMED`，delete `VM_DELETED`；九个阶段齐全，`not_tested` 仍为 `encrypted_storage_persistence`、`20_cycles`、`negative_cases`。
- 归档：外部 artifact 根下 `protected-fastlane/20261022/preflight/<token>/`，含 `report.json`、`run-summary.json`、`commands.txt`、`artifacts/`、`SHA256SUMS`；未覆盖历史 run。
- 校验：`validate.py --report --summary --strict` 三项全过（`OK preflight` / `OK summary` / `OK binding`），`SHA256SUMS` 逐项复算一致。
- 未跑 full：结果为平台阻塞，按契约停止，未重试、未绕过；设备矩阵追加一行真实别名记录。

## 未完成 / 待执行

- [x] 真实设备 preflight：已完成一次；结果为平台阻塞，见上节。
- [x] 设备侧报告通过 `tools/fastlane-build/preflight-report.schema.json` 校验。
- [ ] 只有 `PREFLIGHT_TERMINAL_OK` 才允许操作者显式触发 full；本次 blocked，未触发。
- [ ] P2 的 `qualification` / `full` 模式仍只有 fake-adb 测试，不构成真机证据。
- [ ] P2 当前分别调用 validator 的 `--report` 和 `--summary`，未使用 `--strict` 的 report/summary binding；设备侧 APK/toolbox SHA 的输入绑定仍由操作者检查。（本轮归档校验已用 `--strict` 完成 report/summary binding，但 P2 runner 本身仍未切换）
- [ ] P2 hardening 跟踪票：[DV-01B-p2-runner-hardening](DV-01B-p2-runner-hardening.md)（本轮未实现、未测试）。

## 边界

- 不把本票的零设备结果当成 DV-01 或 DV-01B 真机 PASS；DV-01 仍为 `DV01_CORE_PASS_TERMINAL_BLOCKED`，不改历史结论。
- 不进入 DV-02（terminal UI、proxy、distcc、export/import）。
- 设备矩阵只追加一列真实别名行；真实 alias 映射、serial、原始 logcat 和报告只放 approved external artifact root。
- 不修改 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。

## 证据

- 分支：`integration/fastlane-dv01b` 已通过 PR #6 合并进 `main`（merge commit `e599610`，父提交 `47837f7` + `359f118`）。
- 合并节点：schema → preflight-mode → device-runner；PR #6 以 merge commit 合入，未 squash、未 force、未打 tag。
- 零设备日志与 pytest 输出在本会话 artifact；仓库只保留契约、schema、fixtures、runner 和本票。
