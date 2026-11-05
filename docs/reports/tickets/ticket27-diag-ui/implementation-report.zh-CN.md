# Ticket 27 实施报告（诊断开关 UI 入口切片）

- 票号/标题：票 27「生产启动阶段记录器（ProductionStageRecorder/StageTraceFileSink）」遗留尾巴之一——诊断开关 UI 入口
- 执行 session / 模型 / thinking：Lane Q 诊断 UI 接线 / ZCode（GLM-5.3-Flash）
- 分支：`feat/ticket27-diag-ui`　基线 commit：`2d66f67`（main）

## 1. 任务目标

票 27 零设备切片（lane J）已入库生产启动阶段记录器，开关键 `stage_trace_diagnostics_enabled`（默认关、关闭态零持久采集）已备但无 UI 入口。本切片补设置界面开关一行，接线 SettingsManager 既有读写路径；不新增采集行为、不改 recorder/sink 本体。

## 2. 实际完成

- ✅ **UI 挂点**：`app/src/main/res/layout/activity_settings.xml` 尾部新增「🩺 诊断与开发者选项」分区，完全沿用既有分区惯例（分区标题 TextView + `MaterialCardView` 容器 + 左标题/摘要右 `MaterialSwitch` 行模式，对齐「终端使用体验偏好」的光标闪烁行）。开关 id `switch_settings_stage_trace`，`contentDescription` 与标题同源。
- ✅ **文案走 strings 资源**：`app/src/main/res/values/strings.xml` 新增（中文默认，仓库仅有单一 `values/` 目录、无多语言目录，沿用 `settings_*`/`_desc` 命名惯例）：
  - `settings_stage_trace_diag_title`＝「启动阶段诊断追踪（默认关）」；
  - `settings_stage_trace_diag_desc`＝「开启后，每次实例启动的阶段事件写入应用私有目录，仅用于本机问题定位；关闭时不创建任何诊断文件、队列或后台任务」——写清默认关与关闭态零采集。
- ✅ **接线**：`SettingsActivity.kt` 新增 `setupDiagnosticsSection()`（onCreate 中紧随 `setupTerminalSection()` 调用）：回显 `settingsManager.isStageTraceDiagnosticsEnabled`，切换时经既有 setter `setStageTraceDiagnosticsEnabled` 写同一键 `stage_trace_diagnostics_enabled`——与启动管线原始读取方 `StageTraceProvider`（直接按 `SettingsManager.KEY_STAGE_TRACE_DIAGNOSTICS` 读同一 prefs 文件）共享单键，无第二事实源。
- ✅ **不新增采集行为**：diff 仅 UI/资源/单测，`ProductionStageRecorder.java`、`StageTraceFileSink.java`、`StageTraceProvider.java` 均未改动。
- ✅ **工单回填**：`.scratch/orchestella-avf-shizuku/issues/27-production-stage-recorder.md` Comments 追加 2026-11-05 lane Q 条目，剩余尾巴＝真实 QEMU 事件原件归设备窗。

## 3. 未完成 / 与预期偏差

- **UI 开关本身未自动化测试**（如实记录）：仓库无 UI 测试基建——设置页为 View 体系（XML + Activity，非 Compose），`app/src/androidTest` 仅有需真机的探针类（AvfQaProbeInstrumentation 等），单测侧无 Robolectric/Espresso。按任务预案降级为 SettingsManager 键读写单测 + 编译验证。人工冒烟建议（未执行，无设备窗）：
  - 设置页开关回显与存储值一致（默认关）；
  - 切换后杀进程重进保持；开启后下次启动产生 `stage-traces/` 单文件、关闭后启动零新文件。

## 4. 修改的模块

| 文件 | 变更 | 域 |
| --- | --- | --- |
| `app/src/main/res/layout/activity_settings.xml` | +69 行：诊断分区卡片与开关行 | 设置 UI 资源 |
| `app/src/main/res/values/strings.xml` | +3 行：标题/摘要字符串 | 设置 UI 资源 |
| `app/src/main/java/io/github/nyzeep/orchestella/SettingsActivity.kt` | +15 行：`switchStageTrace` 字段、findViewById、`setupDiagnosticsSection()` | 设置 UI |
| `app/src/test/java/io/github/nyzeep/orchestella/SettingsManagerTest.java` | +25 行：2 个新测试 | app 单测 |
| `.scratch/orchestella-avf-shizuku/issues/27-production-stage-recorder.md` | Comments 追加 | 工单 |

**边界检查**：未触碰编译派发/池/instance 相关 Java、`runtime/instance/**`、`.github/workflows/**`、任何 `build.gradle.kts`（未新增资源目录，沿用现有 `res/` 惯例）；未处理任何空 catch；`ProductionStageRecorder`/`StageTraceFileSink` 本体未动。无越界。

## 5. 重要设计决策

- **新分区而非塞入既有分区**：现有界面无诊断/开发者选项区；设置页分区语义清晰（镜像/AVF/终端/关于/外观/加速），诊断开关单独成区最贴合语义且不混淆「加速与性能」。布局模式逐属性复用既有卡片与开关行写法（0dp 圆角、2dp 阴影、48dp 最小触点）。
- **接线只走 SettingsManager 既有 getter/setter**：UI 不直接持有 SharedPreferences，保证与 `StageTraceProvider` 的原始键读取同键同源；开关不触发任何即时采集，仅在下次启动尝试生效（该语义写入 KDoc 与摘要文案）。

## 6. 测试

- **JVM 单测**（新增于 `SettingsManagerTest`）：
  - `stageTraceDiagnosticsDefaultsToOff`：干净安装默认严格关（零采集前置）；
  - `togglingStageTraceDiagnosticsPersistsUnderSharedKey`：开→关双向持久化，且 UI 写入落在共享键 `KEY_STAGE_TRACE_DIAGNOSTICS`、跨 manager 实例可见。
  - 运行结果：`:app:testDebugUnitTest --tests "io.github.nyzeep.orchestella.SettingsManagerTest"` → **tests="11" failures="0" errors="0" skipped="0"**（原 9 项 + 新 2 项）。
- **运行时行为既有覆盖（本切片未重复）**：关闭零采集断言已在 `ProductionStageRecorderTest`（disabled 时不建文件等）与 `LaunchStageWiringTest` 等 lane J 测试中。
- **编译验证**：`:app:compileDebugKotlin -Porchestella.assets.allowMissingPayload=true` → BUILD SUCCESSFUL（EXIT=0）。
- **UI 自动化**：not-tested（无基建，见第 3 节），未以 JVM 证据冒充 UI 测试。
- **冒烟清单**：无设备窗，未执行（项见第 3 节）。

## 7. Benchmark / 实测数据

本票不涉及（开关为设置读写，无启动路径改动；开关/事件接线开销记录方法属票 27 主切片既有口径）。

## 8. 风险

- 低。开关行仅新增视图与一次 prefs 布尔读写；`MaterialSwitch` 状态在 listener 挂接前赋值，避免 onCreate 回写（沿用光标闪烁行既有写法）。
- 潜在误解风险已用文案对冲：标题明示「默认关」，摘要明示「开启后写应用私有目录、关闭零采集」，用户不会误以为有遥测上传。

## 9. 新发现

- 设置页大量文案为 XML 硬编码中文（本切片新增文案走 strings 资源，与任务要求一致）；若未来做多语言，存量硬编码文案需统一迁移——不影响本票，供分诊。

## 10. 对后续 ticket 的影响

- 票 27 设备窗（真实 QEMU 事件原件）不受本切片影响；设备验收时可直接用该开关在生产界面开启诊断。
- 若后续票给设置页加 Robolectric/Compose 迁移，可补 `switch_settings_stage_trace` 的语义断言。

## 11. Commit

- 分支：`feat/ticket27-diag-ui`（基线 main@`2d66f67`）
- commit：见分支提交（feat(app): 设置页新增启动阶段诊断追踪开关 UI 入口——接线 SettingsManager 共享键，ticket 27 遗留尾巴）

## 12. Ticket 状态

- 建议：completed（本 UI 切片范围内）。票 27 整体维持 implemented（零设备）不变——剩余尾巴＝真实 QEMU 正常 + 受控失败事件原件，归设备窗，agent 不伪造。
- 未触发 Architecture Change Policy：无 ADR 边界触碰、无新能力暴露面、无构建脚本/依赖变更。

报告人：ZCode（GLM-5.3-Flash）｜ 日期：2026-11-05 ｜ 耗时：单 lane 会话（tokens 未统计）
