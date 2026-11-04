# 工单 27 实施报告：生产启动阶段与停止来源接线（零设备切片）

- 票号/标题：avf-shizuku-27 生产启动阶段与停止来源接线，先 QEMU 后扩 AVF
- 执行 session / 模型 / thinking：实施 lane J（ZCode）／GLM-5.3-Flash
- 分支：`feat/avf-shizuku-27-stage-recorder`　基线 commit：`9f18c31`（main）
- 工单：`.scratch/orchestella-avf-shizuku/issues/27-production-stage-recorder.md`（含 lane I 的 agent-ready 开工说明）

## 1. 任务目标

按工单 What to build：在现有 QEMU pipeline 的真实触发点上挂纯观察 observer，建立生产侧 16 门 stage 事件闭包——对齐票 22 离线版 `tools/device-validation/avf_stage_recorder.py` 的 16 门口径的事件组件、版本化有界 sink、诊断显式开关（默认关）、last-stage 诚实化，以及 Python 侧新 schema 结构校验器；真实 QEMU 事件原件与 AVF 样本按阻塞边留归设备窗。

## 2. 实际完成（逐条对照六组 Acceptance criteria）

### 组① 诊断关闭时零持久采集；开启态限额与超限策略 —— ✅

- `ProductionStageRecorder.disabled()`：单测断言关闭态**零时钟读、零 sink 调用、零 identity 保留**，所有操作返回 `DISABLED`，snapshot 全零；`StageTraceProvider.open(false, dir, …)` 断言**不创建任何文件**。
- 开关：`SettingsManager.KEY_STAGE_TRACE_DIAGNOSTICS`（`stage_trace_diagnostics_enabled`），默认 false，无 UI 亦无默认 I/O；`StageTraceProvider.observerForLaunch` 关闭态返回 no-op observer，launch 行为不变。
- 限额显式：`MAX_EVENTS=16`、`MAX_TRACE_BYTES=32768`（与 22 号 16 事件/32768 bytes 同量级），越界构造被拒绝（IllegalArgumentException）；超限退役为 `CAPTURE_LIMIT` 并保持终态。sink 侧 `DEFAULT_MAX_FILE_BYTES=128KiB`、`DEFAULT_ARCHIVES_KEPT=1`，轮转与保留有测试。
- 证据：`ProductionStageRecorderTest`（disabledRecorder…、disabledProvider…、explicitEventAndByteBounds…、boundArguments…）。

### 组② 16 门按票面行为验收第 5/6 条分别记录；stop 三事实独立；clock 全注入 —— ✅

- 16 门 `Stage`/`Route`/`StopFact`/`Failure` 枚举与离线 recorder 同名同序（wire name 一致）；`Route` 门类校验与 22 一致（agent_authenticated/guest_command_ok/services_ready 仅收 `authenticated_single_reader`）。
- 生产真实触发点分别记录：run 返回（QEMU/AVF launcher 的 `RUN_BEGIN`→`VM_CREATED`→`RUN_RETURNED`）、VM 状态（supervisor owner 退出事实）、通道连上（`BridgeConnector.serveSession` 的 `VSOCK_CONNECTED`，取自 session 缓存 client 建立这一既有事实）、agent 认证（`AGENT_AUTHENTICATED`，取自原 handshake 结果）、终端命令（`GUEST_COMMAND_OK`，取自原 PING/PONG 命令回执，**不解析终端正文**）、服务 ready（`SERVICES_READY` 只在认证路由接受标记；生产 v1 无服务回执信号 → 保持 not_observed，不伪造）。
- stop 三事实独立：stop 意图（`stopRuntime` 入口 `STOP_REQUESTED`）、owner 底层退出（supervisor `onVmStoppedObserved` → `OWNED_VM_STOPPED`）、资源/FD 释放（channels+gateway 双确认 → `OWNED_RESOURCES_RELEASED`）；`STOP_COMPLETED` 需三事实齐全才可记录（缺 guest shutdown ack 时被 `STOP_UNCONFIRMED` 拒绝）——EOF/退出码 0 单独不能签 normal stop。AVF v1 通道关闭为 best-effort，故不记资源释放事实（不发明）。
- 时钟：全部注入 `LongSupplier`（生产 `SystemClock::elapsedRealtimeNanos`，事件记 `clock:"android_elapsed_realtime_ns"`）；倒退/抛错 → `CLOCK_REJECTED` 退役。
- 证据：`ProductionStageRecorderTest.allSixteenGates…/stopCompletedWithoutAllThreeFacts…/duplicateGate…/backwardsOrBrokenClock…`；`LaunchStageWiringTest.confirmedStop…/alreadyStopped…/bridgeRoute…`。

### 组③ 失败路径 last-stage 真实；不假 ready、不阻塞收尾 —— ✅

- bootstrap 失败 → `fail(CREATE_FAILED)`（QEMU launcher 与 AVF launcher 的既有 catch 路径，先记真实终态再走原清理）；权限拒绝/模式拒绝 → AVF admission 拒绝映射 `fallback(PERMISSION_DENIED/API_BLOCKED/…)`（`mapFailure` 有界映射，未知显式 `UNKNOWN`）；QEMU 回退 → AVF trace 终态 `FALLBACK` 且 reason=真实失败码；cancel → 迟到错代在 `InstanceLaunchSequence.awaitGuestExit` 的 STALE 路径 `cancel()`；sink 失败 → `SINK_FAILED`（不重试不排队）；容量满 → `CAPTURE_LIMIT`。
- 行为断言：失败后任何 mark 返回 `RETIRED`（不假 ready）；终态迁移先于 owner 清理记录、observer 抛错被吞（supervisor/bridge hook 均有 throw 测试证明不影响 owner 收尾/控制流）；错代 claim `IDENTITY_MISMATCH` 且不读时钟不碰 sink、当前 trace 完好。
- 证据：`ProductionStageRecorderTest.failureKeeps…/fallbackRetires…/cancelRetires…/lateWrongGeneration…/sinkFailure…/explicitEventAndByteBounds…`；`LaunchStageWiringTest.avfEngineReasonsMap…/failedChannelCleanup…/throwingObserver…`。

### 组④ schema 版本化；旧 synthetic 输入显式拒收；不回填时间/伪排序 —— ✅

- 每行事件与 trace_end 都带 `schema_version:1`＋`schema:"orchestella_stage_trace"`＋`provenance:"production"`；sink 文件首行写 file_header（同 schema/version）；已存在文件首行不匹配 → **显式拒绝（永久 refused，返回 false）并原样保留**，包括 22 号 `offline_stage_trace`/`synthetic_seam` 输入与 version=2 头——不静默 reinterpret。
- 偏序诚实：生产 recorder 不实行离线链的线性顺序拒绝（现真实 pipeline 中 AVF admission 在 shared bootstrap 前、inputs 位于 preflight 窗口内），事件按观察序写入、时间只取标记时刻（不回填、不重排）——有专门测试以 preflight_begin→inputs_begin→inputs_verified→preflight_end 的真实序断言通过；Python 校验器 `validate_trace` 断言 `t_ns` 非倒退、end 行 `observed_gates` 必须与实际事件一致（`INCONSISTENT_REJECTED`）。
- 证据：`StageTraceFileSinkTest`（fresh/append/foreign/stale/rotation）；`ProductionStageRecorderTest.observedPartialOrder…/everyCarriedLine…`；Python `test_avf_stage_trace_schema` 15 项。

### 组⑤ 敏感字段不泄漏 —— ✅

- 组件 API 无自由文本通道：stage/route/stop fact/failure 全枚举；identity 四字段限 `[a-z0-9-]{1,64}`，token/path/设备序号形态值 `Identity.of` 返回 null → 该 launch 不记录（不伪造 identity）；事件键集合封闭（exact-key 断言，无 message/exception/path/token 键）；事件不含设备序列号/型号/用户数据。
- Python 校验器同口径：identity 字段 `IDENTITY_REJECTED`、reason 限 `[A-Z_]{1,32}`（`REASON_REJECTED`）、synthetic 永久单列。
- 证据：`ProductionStageRecorderTest.unbindableIdentity…/eventLinesCarryOnly…/sensitiveShapedInputs…`；Python `test_token_or_path_shaped_identity…/test_free_text_reason…`。

### 组⑥ 构建与测试全绿；JavaExec 不空跑 —— ⚠️（本票相关全绿；Python 全量存在与本票无关的宿主环境性失败）

- `:app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true`：**141 个测试类，1167 tests，0 failures / 0 errors / 0 skipped**（含本票新增 38 项）。
- `:runtime:contract:check`：BUILD SUCCESSFUL（22 个任务实跑，JavaExec 契约入口实际执行非空跑）；`:runtime:engine-qemu:check`：BUILD SUCCESSFUL（本票未改 engine-qemu，按验收"按改动实际涉及"口径补跑确认无回归）。
- Python：新增 `test_avf_stage_trace_schema` **15 tests OK**（`python -B -m unittest`，`AVF_VALIDATION_TEST_ROOT` 指向临时外部目录）。全量 discover（`test_avf*.py`，197 项）中 `test_avf_device_mode.DeviceCliTests` 有 **23 failures + 1 error**——均为 adb CLI 在本 Windows 宿主的环境性失败（例：期望 `ADB_TIMEOUT` 实得 `ADB_UNAVAILABLE`，宿主 adb 行为差异）；该工具与其测试**本票零改动**（git status 可证），与本票新增文件无 import 关系。是否为 Linux CI 上的既有红，交 CI 复核（见"新发现"）。
- 命令（ASCII 路径 worktree 实跑）：
  - `./gradlew :app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true`
  - `./gradlew :runtime:contract:check`、`./gradlew :runtime:engine-qemu:check -Porchestella.assets.allowMissingPayload=true`
  - `AVF_VALIDATION_TEST_ROOT=<外部临时目录> python -B -m unittest discover -s tools/device-validation -p "test_avf*.py"`

## 3. 未完成 / 与预期偏差

- **真实 QEMU 事件原件（正常 + 至少一个受控失败）未产出**：需真实 QEMU 运行窗口（初态/盘/事务由主协调统一核），本票只交付结构校验器与 not_observed 占位，未伪造 receipt。
- **guest shutdown ack、services_ready 两个信号源未闭合**：生产 wiring 中对应 gate 保持 not_observed，成功组（全 16 门 + 3 stop facts）在当前信号面下不可达——这是诚实结果而非缺陷；补信号源（guest 协议或 owner 信号）留设备窗/后续票。
- **AVF 同口径事件原件**：依赖票 07 真实 AVF guest，本票不产 AVF 成功 receipt；AVF 侧仅完成 launch/preflight/inputs/manager/回退/stop 意图与 VM 停止事实的观察挂点。
- **诊断开关无 UI 入口**：settings key 已备（默认关），UI 开关与"接线开销实测"未做——未测不称零开销，工单该项如实标 not-tested。
- 偏差：开工说明建议的"runtime:contract BridgeClient 契约扩展"未做——`runtime/contract/**` 为本 lane 禁改边界，observer 走 app 层 `BridgeConnector.serveSession` 既有公共路由 + `ExecutionSession` 默认方法接入，未触碰 contract 模块。

## 4. 修改的模块

- app（新增）：`ProductionStageRecorder.java`、`StageTraceFileSink.java`、`LaunchStageObserver.java`、`StageTraceProvider.java`；测试 `ProductionStageRecorderTest.java`、`StageTraceFileSinkTest.java`、`LaunchStageWiringTest.java`。
- app（修改，最小观察 seam）：`QemuInstanceLauncher.java`（launch 各门/失败映射、Session 停路径三事实、SupervisorStopFacts）、`AvfInstanceLauncher.java`（PREFLIGHT/MANAGER/回退/mapFailure/AvfSession 停路径）、`RuntimeProcessSupervisor.java`（StopObserver 可选构造，观察点在既有判定点之后）、`BridgeConnector.java`（serveSession 三门 + 静态 notify 助手）、`ExecutionSession.java`（`launchStageObserver()` 默认方法）、`InstanceLaunchSequence.java`（STALE 路径 cancel）、`SettingsManager.java`（诊断 key）。
- tools/device-validation（新增）：`avf_stage_trace_schema.py`、`test_avf_stage_trace_schema.py`。`avf_stage_recorder.py` 按边界**只读未改**。
- 未触碰 forbidden：`runtime/instance/.../InstanceLifecycle.java`、`InstanceStorageManager`、`app/.../ProtectedWarmPool.java`、`.github/workflows/**`、`runtime/contract/**` 均未修改（git diff 对象清单可核）。

## 5. 重要设计决策

1. **不做离线链的线性顺序拒绝**：工单明示"bootstrap 可能在 adapter 模式/API 检查前……必须诚实版本化 event 偏序"，故生产 schema v1 记录观察序偏序，未观察门以 missing_gates/not_observed 报告——与 22 号离线 recorder 的关键偏离已在组件 javadoc 与 Python 校验器中双端写明。
2. **identity 逐调用携带（沿用 22 口径）**：`IDENTITY_MISMATCH` 成为"迟到错代"的机制载体；identity 四字段（instance UUID/launch 会话/launch-guard generation/实际策略）绑定真实 launch，不可绑定时整体不记录。
3. **失败终态先于 owner 清理**：清理路径（completeLaunch onFailure / cleanupFailedRegistration）先记真实失败码再执行既有清理，使 stop facts/时间线的权威记录仍在原处（LaunchTimeline/supervisor diagnostics），stage trace 不与之竞争。
4. **AVF v1 不记资源释放事实**：引擎通道关闭为 best-effort，无确认信号即不声明——"未知不填默认成功"的直接应用。
5. **GUEST_COMMAND_OK 绑定 PING 命令回执**：这是既有协议里唯一带回执的真实命令，不新造 QA 命令、不解析终端提示符（22 号划界沿用）。
6. 未触发 Architecture Change Policy：无新 VM owner/权限/exec、无 Guest 命令集变更、无 ADR 边界突破。

## 6. 测试

- 契约测试：`:runtime:contract:check` 全绿（JavaExec 实跑）；`:runtime:engine-qemu:check` 全绿（补跑）。
- JVM 测试：新增 3 类 38 项（recorder 21 / sink 7 / wiring 10）；app 全量 `:app:testDebugUnitTest` 141 类 **1167 tests 全绿**（真实输出：`BUILD SUCCESSFUL`，XML 汇总 failures=0 errors=0 skipped=0）。red→green 过程中实际修复两处实现缺陷（sink 文件头缺换行导致 append 语义错误；supervisor 观察回调双发风险在测试镜像中被暴露后修正为生产双布尔确认）。
- Python：`test_avf_stage_trace_schema` 15 项 OK；全量 discover 197 项中 24 项 `test_avf_device_mode` 宿主环境性失败（与本票无关，见组⑥/新发现）。
- 冒烟清单：真机验证项全部未跑（无设备窗）——真实 QEMU 正常/受控失败事件原件、Android 单调时基实测、开关接线开销实测均标 **not-tested**。

## 7. Benchmark / 实测数据

本票不涉及（不报告硬件提速；未采 CPU/RSS/GPU 指标；接线开销未测）。

## 8. 风险

- **sink 文件被异 schema 占位即永久拒写**：诊断目录中若出现不匹配头文件，该 sink 实例拒绝并保留原文件；后续 launch 的 sink 新实例亦拒（同 active 文件）。属显式拒绝语义的代价，需要维护者清理或版本化文件名演进（follow-up）。
- **stop 三事实在 UNCONFIRMED 重试窗口内不完整**：trace 保持 ACTIVE（可被后续确认补全），进程死亡则留下未终止 trace——校验器判 TRACE_UNTERMINATED 非成功组，语义诚实但文件里存在悬挂记录。
- **观察点在 supervisor 锁内执行**：observer 契约要求快进快出（recorder 仅内存 + 小文件 append），锁内做慢 I/O 的错误实现会拖慢 stop；已用 try/catch 隔离异常，未隔离耗时。
- **eventCount/字节上限对失败 trace 的可用性**：16 门上限内含 stop_completed；trace_end 因容量被丢弃时（罕见）终态仅在内存/snapshot。

## 9. 新发现

- `tools/device-validation/test_avf_device_mode.DeviceCliTests` 的 24 项（23 fail + 1 error）在本 Windows 宿主全数失败，根因是 adb CLI 行为差异（`ADB_UNAVAILABLE` vs `ADB_TIMEOUT` 等）；该组测试断言与宿主 adb 可用性耦合，缺 CI/宿主对齐口径。建议 Orchestrator 分诊：Linux CI 上复跑确认是否为 Windows-only 环境性红，或给该组加宿主能力前置。
- `QemuInstanceLauncher.Session` 存在约 10 个构造重载链，观察 seam 只能挂在 canonical 私有构造上；后续如再增 session 级组件，建议收敛构造面（重构另票）。
- 诊断目录的"异 schema 拒绝 + 保留"缺少维护者清理工具（`stage-trace-v1.jsonl` 被占位时诊断静默失效于后续 launch）——可复用本票校验器做 CLI 体检（follow-up）。

## 10. 对后续 ticket 的影响

- 设备窗验证票可直接使用：`python -B tools/device-validation/avf_stage_trace_schema.py <trace.jsonl>`（VALID/SUCCESS_GROUP/REJECTED + code），成功组判据=全 16 门 + 3 stop facts + ENDED 终止行。
- 票 09（真实性能基线）仍不因本票视为前置已满足；本票事件不含耗时差值语义（只含观察时刻单调 ns），阶段耗时推导属消费侧。
- 补 guest shutdown ack / services_ready 信号源后，无需改事件 schema（字段已预留），仅需在既有路由补观察点。
- 票 07 完成后，AVF 成功 receipt 才可能过成功组校验；synthetic 样本永久单列。

## 11. Commit

- `a950ebd` feat(app): 生产启动阶段事件组件 27——16 门口径 recorder、版本化有界 sink 与既有路由 observer 挂点（实现 + JVM/Python 测试）
- 本报告与工单回填所在的 docs commit（分支 HEAD）：`docs(reports): avf-shizuku-27 实施报告与工单回填入库——零设备切片六组验收证据、设备窗遗留边界与 follow-up`

## 12. Ticket 状态

**implemented（零设备切片）**——工单 Status 已置该值并附证据 Comments。理由：六组 agent 可自验验收全部落地且全绿；真实 QEMU 事件原件、guest ack/服务回执信号源、AVF 样本（依赖 07）按阻塞边留设备窗，票面"新QA QEMU正常与至少一个受控失败有真实事件原件"一项未满足，故不置 completed，不声称 09 前置已满足。

报告人：ZCode（lane J）／GLM-5.3-Flash ｜ 日期：2026-11-05 ｜ 耗时：未可靠计量
