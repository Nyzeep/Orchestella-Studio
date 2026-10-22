# DV-01B: 设备无关 preflight 实现与零设备门禁

Type: implementation / host-only verification
Status: code-complete-zero-device-passed；device preflight pending（不改变 DV-01 结论）
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

## 未完成 / 待执行

- [ ] 真实设备 preflight：操作者提供 alias、APK、payload、toolbox SHA 后运行一次；当前没有真机样本。
- [ ] 设备侧报告通过 `tools/fastlane-build/preflight-report.schema.json` 校验。
- [ ] 只有 `PREFLIGHT_TERMINAL_OK` 才允许操作者显式触发 full；blocked 结果停止，不重试、不绕过。
- [ ] P2 的 `qualification` / `full` 模式仍只有 fake-adb 测试，不构成真机证据。
- [ ] P2 当前分别调用 validator 的 `--report` 和 `--summary`，未使用 `--strict` 的 report/summary binding；设备侧 APK/toolbox SHA 的输入绑定仍由操作者检查。

## 边界

- 不把本票的零设备结果当成 DV-01 或 DV-01B 真机 PASS；DV-01 仍为 `DV01_CORE_PASS_TERMINAL_BLOCKED`，不改历史结论。
- 不进入 DV-02（terminal UI、proxy、distcc、export/import）。
- 设备矩阵不追加真实设备行；真实 alias 映射、serial、原始 logcat 和报告只放 approved external artifact root。
- 不修改 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。

## 证据

- 分支：`integration/fastlane-dv01b`（基于 GitHub `main`）。
- 合并节点：schema → preflight-mode → device-runner；worker 分支保留。
- 零设备日志与 pytest 输出在本会话 artifact；仓库只保留契约、schema、fixtures、runner 和本票。
