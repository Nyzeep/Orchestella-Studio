# 27: 真实生产启动阶段与停止来源接线，先QEMU后扩AVF

Type: implementation
Status: implemented（零设备切片 @ a950ebd；真实QEMU事件原件与AVF样本仍归设备窗）
Priority: P1
Owner: recorder与各source模块owner协作；构建/设备主协调统一
Blocked by: 22（completed-offline-reviewed）
Conditional gates: AVF真实事件必须来自相应已合法运行的owner；未过07不产AVF成功receipt
Parent: 22离线工具的生产纵向切片，复用04/09样本边界

## 用户价值、来源与交付

能知道一次真实启动/失败/停止在哪个阶段耗时或中断，为稳定性排错和后续优化提供原始依据；不再把离线COMPLETE_OFFLINE标为生产完成。来源：22报告、详细研究的阶段/原reader边界、近期方案§4/7。

先打通一条现QEMU用户启动→输入准备→进程owner→原认证Bridge/终端/已能观测的服务→正常或异常停止的版本化本地事件闭包。它本身可独立验，不等待pVM。AVF只在既有已合法owner/接口处增加同口径可观测事件；模式/平台失败也准确记last-stage，不隐式尝试VM或更改launcher选择。

## 允许范围与非目标

允许新的小型event组件/有界sink与真实触发点、现bootstrap/launcher/监督/Bridge handler最小observer、离线工具兼容及行为测试。模块共用文件由原owner写锁协调。禁止新VMowner/权限/exec、修改Guest命令集或偷偷新增QA命令、第二reader/解析任意终端正文、全局诊断/上传、逐字符同步落盘、大规模性能重构。

现真实pipeline的顺序先核：例如bootstrap可能在adapter模式/API检查前；22旧schema若假设preflight全先于inputs，必须诚实版本化event偏序/来源适配或标缺失，不回填过去时间/伪排序。不把CLOCK_MONOTONIC、JVM/Python clock、Guest uptime和Android elapsedRealtime混减。

## 行为验收

- [ ] 显式诊断关闭时零持久采集/文件/queue/重试，不影响Guest正常执行；启用sink条数/字节/保留范围/超限策略明确。
- [ ] 同一次launch/source/APK/ROM alias/请求与实际策略/owner/session/generation、Android单调时基和真实last-stage绑定；rawexception/path/口令/token/候选/按键/metadata/embedded载荷不入事件。
- [ ] 成功、bootstrap校验失败、API/权限/模式拒绝、QEMU回退、cancel、迟到错代、sinkI/O失败及容量满保持真实结果，sink失效不假ready、不阻塞owner收尾。
- [ ] 原Bridge唯一reader和鉴权前置不变，stage来自原handler/owner信号，不抢读；没有固定QA挑战接口时记not_observed，不按终端提示符制造guest_command_ok。
- [ ] run返回、VM状态、通道连上、agent认证、终端命令、服务ready分别记录；customLinux不等待Microdroid payload，未知不填默认成功。
- [ ] stop意图、owner底层退出、资源/FD释放三事实独立，EOF/FGS通知结束/退出码0不能单独签normal stop。
- [ ] 新QA QEMU正常与至少一个受控失败有真实事件原件，非所有字段必须先有；缺必需设备证据的样本结构校验器拒成功组。synthetic永久单列、工具不认证物理真伪。
- [ ] 开关/事件接线开销记录方法明确，未测不称零开销；不报告硬件提速或未采CPU/RSS/GPU指标。

## Seam、red命令与设备门

优先现launch sequence、bootstrap、Bridge公共路由+注入clock/sink；red覆盖真实阶段/失败/关闭行为，green只加observer。App测试、runtime:contract:check、相关AVF/QEMU契约和Python recorder/validator按改动实际执行，确认JavaExec入口不空跑。真实QEMU新QA盘/无事务窗口；AVF样本/目标QA挑战/服务信号未闭合时不作为本票已有能力。

不要为了让全部字段齐而创建新Guest bridge命令：若确需命令版本扩张，另票遵循协议协商/ADR与scope后处理。本票可以完成真实阶段/缺项诚实闭包，不伪造09的完整benchmark前置已满足；09仍需真实AVF、完整来源/正常stop/资源工作负载。

## 回滚、兼容、许可与证据

事件schema有版本且旧synthetic输入历史保留；新旧工具需明确拒不兼容而非静默reinterpret。无用户盘格式/实例迁移，不改变策略结果；rollback定点observer/sink，不清旧证据。依赖标准库/现Android API，自研，不新增遥测或第三方服务。

报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，双轴review同id；外部27专属批次/真实receipts与preservation。当前22仍只离线完成，本票仅建立尚未实现；旧127项不计本票已通过。

## Comments

- 2026-11-05 / ZCode（票据收尾 lane I）：**状态核实——未实现，维持 `ready-for-agent（现有QEMU/owner不变的范围）` 不变**。核实依据（勿信票面 Status，本次为独立对账）：
  - `docs/reports/tickets/` 下无 `avf-shizuku-27/` 目录（已实现票 01/02/03/04/07/08/13/17/18/19/20/22/23 均有对应实施报告）；
  - git log（含 `--all`）无任何以本票为对象的 commit，无「生产阶段事件接线 / production stage recorder」相关实现提交；
  - `map.md` 交付矩阵与本票票面末行自述一致：「22离线127通过不等27已接生产」「本票仅建立尚未实现」；
  - 2026-11-04 reconciliation snapshot（`docs/agents/issue-tracker.md`）将 27 列为 still-open ready。
  - **处置**：QEMU 先行切片可零设备完成，符合 ready-for-agent 口径，故不改 Status，追加如下开工说明。AVF 侧样本与「新 QA QEMU 真实事件原件」仍属设备门，agent 不得伪造（见阻塞边）。

- 2026-11-05 / ZCode（票据收尾 lane I）：**Agent-ready 开工说明**（供 `/implement` 直接领取；范围以票面「允许范围与非目标」为准，此处只做可执行化展开，不放宽任何非目标）。

  **What to build**（生产侧事件闭包，全部在现有 QEMU pipeline 的真实触发点上挂 observer）：
  1. 新建小型 Android 侧 stage 事件组件（app 模块内，风格对齐 `RuntimeProcessSupervisor` 的纯 JVM 可测组件）：固定枚举 Stage/Route/StopFact/Failure 对齐 22 号离线 recorder（`tools/device-validation/avf_stage_recorder.py`）的 16 门口径，事件有界（条数/字节上限与 22 的 16 事件 / 32768 bytes 同量级即可，超限策略显式），单一 Android 单调时基（`SystemClock.elapsedRealtimeNanos()`），禁止与 JVM clock/Guest uptime 混减。**不得**把 Python 组件直接接 Android 冒充原始采集器（22 报告已划界：生产跨线程/进程适配须另验）。
  2. 有界 sink：显式诊断关闭时零持久采集（不建文件/queue/线程）；开启时写 App 私有目录版本化 JSONL，schema 带 `schema_version`，旧 synthetic 输入历史保留、不兼容时显式拒绝而非静默 reinterpret。sink I/O 失败/容量满 → 事件组件显式退役（对齐 22 的 SINK_FAILED 语义），不阻塞 owner 收尾、不假 ready。
  3. 真实触发点最小 observer（只加观测、不改控制流）：`QemuRuntimeBootstrapper`（bootstrap 校验失败）、`InstanceStrategyLauncher`/`QemuInstanceLauncher`（run 返回、QEMU 回退）、`RuntimeProcessSupervisor`（owner 底层退出、资源/FD 释放）、`BridgeConnector` 现有公共路由（通道连上、agent 认证、终端命令、服务 ready，stage 取自原 handler/owner 信号，不抢读、不新增第二 reader）、`ExecutionSession`/`RuntimeOwnedSession`（stop 意图、迟到错代、cancel）。stop 三事实（意图/owner 退出/资源释放）独立记录，EOF/退出码 0 不得单独签 normal stop。
  4. last-stage 诚实化：bootstrap 校验失败、API/权限/模式拒绝、QEMU 回退、cancel、迟到错代、sink 失败、容量满均保留真实终态；无对应信号的阶段记 `not_observed`，未知不填默认成功；rawexception/path/口令/token/候选/按键/metadata 不入事件。
  5. 离线工具兼容：`tools/device-validation/` 的 recorder/validator 侧补新 schema 的结构校验（缺必需字段拒成功组）；synthetic 永久单列。

  **Acceptance criteria**（agent 可零设备自验子集，全部 JVM/宿主测试可覆盖）：
  - [ ] 诊断关闭时零持久采集：单测断言关闭态无文件创建、无线程、无队列；开启态限额与超限策略有测试。
  - [ ] 阶段事件按票面「行为验收」第 5/6 条分别记录（run 返回/VM 状态/通道/认证/终端/服务 ready；stop 三事实独立），red→green，clock 全部注入。
  - [ ] 失败路径 last-stage 真实：bootstrap 失败、权限拒绝、QEMU 回退、cancel、错代、sink 失败、容量满各有行为测试，断言不假 ready、不阻塞收尾。
  - [ ] schema 版本化：旧 synthetic 输入被新版工具显式拒收或明确版本分流的测试；不回填时间/伪排序的偏序断言。
  - [ ] 敏感字段不泄漏：构造含 rawexception/path/token 形态的输入，断言事件中不出现（可复用 `tools/ci/check_sensitive_data.py` 思路做组件级断言）。
  - [ ] `:app:testDebugUnitTest`（含新测试类）、`:runtime:contract:check`、按改动实际涉及的 `:runtime:engine-qemu:*` 契约测试与 Python `unittest` 全绿，JavaExec 入口不空跑；Gradle 实跑须在 ASCII 路径 worktree（中文路径 Test 任务坑见 zero-device-closeout-20261104 报告）。
  - [ ] 实施报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，双轴 review 同 id；未测项如实标 `not-tested`，不得以 JVM 证据冒充真实 QEMU 事件原件。

  **阻塞边**（agent 不可跨，留给设备窗/维护者，不阻塞上述切片开工）：
  - 「新 QA QEMU 正常 + 至少一个受控失败的真实事件原件」需真实 QEMU 运行窗口（设备/主协调统一核初态/盘/事务），agent 只能交结构校验器与 not_observed 占位，不得伪造 receipt；
  - AVF 同口径事件依赖 07（真实 AVF guest）未过，本票不产 AVF 成功 receipt；
  - `BridgeConnector` 等模块共用文件的改动须按票面与原 owner 走写锁协调，由主协调分派，不抢文件；
  - 若确需 Guest bridge 命令版本扩张，另票走协议协商/ADR，本票禁止。

- 2026-11-05 / ZCode（实施 lane J / GLM-5.3-Flash）：**零设备切片已实现，Status 置 implemented（零设备）**。证据（commit `a950ebd`，分支 `feat/avf-shizuku-27-stage-recorder`，基线 main@9f18c31；实施报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`）：
  - **实现**：`app/.../ProductionStageRecorder.java`（16 门 Stage/Route/StopFact/Failure 对齐 `tools/device-validation/avf_stage_recorder.py` 口径；注入单调时钟；观察序偏序、终态唯一、错代 IDENTITY_MISMATCH；16 事件/32768 bytes 上限；SINK_FAILED/CAPTURE_LIMIT 显式退役）＋ `StageTraceFileSink.java`（App 私有目录版本化 JSONL，文件头 `schema_version=1`，异 schema/旧 synthetic 显式拒绝并保留，轮转+保留份数显式）＋ `LaunchStageObserver.java`/`StageTraceProvider.java`（观察 seam 与组合根）。
  - **挂点（纯观察）**：`QemuInstanceLauncher`（LAUNCH_REQUESTED/INPUTS_BEGIN/INPUTS_VERIFIED/RUN_BEGIN/VM_CREATED/RUN_RETURNED、失败 CREATE_FAILED/READINESS_FAILED/RUN_FAILED）、`AvfInstanceLauncher`（PREFLIGHT/MANAGER_BEGIN、AVF→QEMU 回退 `fallback(映射原因)`、`mapFailure` 有界映射）、`RuntimeProcessSupervisor`（StopObserver：owner 退出/通道释放/网关释放三事实独立）、`BridgeConnector.serveSession`（vsock_connected/agent_authenticated/guest_command_ok，取自原 handshake/ping 结果，不抢读不设第二 reader）、`InstanceLaunchSequence`（迟到错代 cancel）、`ExecutionSession.launchStageObserver()` 默认接入点、`SettingsManager` 新增 `stage_trace_diagnostics_enabled`（默认关）。
  - **六组验收**：全部 JVM/宿主覆盖 ✅——①关闭零采集（无文件/时钟读/sink 调用断言）＋限额显式；②16 门按观察序分别记录＋stop 三事实独立（`STOP_COMPLETED` 需三事实齐全；无 guest shutdown ack 信号时不签 normal stop）；③失败路径 last-stage 真实（bootstrap 失败/权限拒绝/回退/cancel/错代/sink 失败/容量满各有行为测试，不假 ready、不阻塞收尾）；④schema 版本化（每行 `schema_version`＋sink 头；旧 synthetic 显式拒收不静默 reinterpret；不回填时间断言）；⑤敏感字段（事件键集合封闭＋token/path/设备序号形态 identity 拒绑定）；⑥构建与测试（见下）。
  - **测试实跑**：`:app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true` 141 类 **1167 tests，0 fail/error/skip**（含新增 ProductionStageRecorderTest 21＋StageTraceFileSinkTest 7＋LaunchStageWiringTest 10）；`:runtime:contract:check`、`:runtime:engine-qemu:check` BUILD SUCCESSFUL（JavaExec 实跑非空跑）；Python `unittest` 新增 `test_avf_stage_trace_schema` **15 tests OK**。Python 全量 discover 197 项中 `test_avf_device_mode.DeviceCliTests` 的 23 fail＋1 error 为本 Windows 宿主 adb CLI 环境性失败（该工具与测试本票未改动，`git status` 可证；与 CI Linux 口径需 CI 复核）。
  - **诚实边界（设备窗遗留）**：真实 QEMU 事件原件（正常＋受控失败）、guest shutdown ack 信号源、services_ready 服务回执信号源均未闭合——生产 wiring 中 `vm_running_observed`/`services_ready`/`GUEST_SHUTDOWN_ACKNOWLEDGED` 保持 not_observed，不伪造；成功组判定须过 `avf_stage_trace_schema` 全 16 门＋3 停止事实校验。AVF 同口径事件原件依赖票 07，本票不产 AVF 成功 receipt。开关 UI 入口未建（settings key 已备），记 follow-up。

- 2026-11-05 / ZCode（诊断 UI 接线 lane Q / GLM-5.3-Flash）：**开关 UI 入口已接线（上条 lane J 记录的 follow-up 之一），Status 维持 implemented（零设备）不变**。证据（分支 `feat/ticket27-diag-ui`，基线 main@2d66f67，commit 见本分支提交）：
  - **UI 挂点**：`activity_settings.xml` 新增「🩺 诊断与开发者选项」分区（既有 MaterialCardView + MaterialSwitch 行模式，置尾随「加速与性能」之后），开关行 id `switch_settings_stage_trace`；标题/摘要走 strings 资源 `settings_stage_trace_diag_title`/`settings_stage_trace_diag_desc`（中文默认，文案含「启动阶段诊断追踪（默认关）」与关闭态零采集说明）；`SettingsActivity.setupDiagnosticsSection()` 仅读写 `SettingsManager` 既有 getter/setter（同键 `stage_trace_diagnostics_enabled`，即 `StageTraceProvider` 原始读取路径），不新增任何采集行为，recorder/sink 本体未动。
  - **测试与验证**：`SettingsManagerTest` 新增 2 项（默认严格关 + 双向切换持久化且落在共享键），`:app:testDebugUnitTest --tests SettingsManagerTest` **11 tests, 0 fail/error/skip**；`:app:compileDebugKotlin -Porchestella.assets.allowMissingPayload=true` 绿。
  - **诚实边界**：仓库无 UI 自动化测试基建（设置页为 View 体系非 Compose，androidTest 仅有设备探针，单测侧无 Robolectric/Espresso），UI 开关本身未自动化测试，仅 SettingsManager 键读写单测 + 编译验证；人工冒烟项（开关状态回显、切换持久化）记入实施报告。**剩余尾巴不变：真实 QEMU 正常 + 受控失败事件原件仍归设备窗，agent 不伪造。**

## Comments

- 2026-11-07 / ZCode（设备窗 T4 / GLM-5.3-Flash）：**真实 QEMU 事件原件已采集（正常 + 三失败腿），Status 维持 implemented 不变**。设备别名 device-nova（新设备，Android 16 / API 36 / HONOR 系粗口径；原始证据仓外 `evidence/device-window-20261106/t4-ticket27/`，四条会话 trace 均过 `avf_stage_trace_schema.py` VALID）：
  - **成功腿**（debian 实例冷启→终端→UI"正常关机"，运行 273s）：观测 **10/16 门**（launch_requested/inputs_begin/inputs_verified/run_begin/vm_created/run_returned/vsock_connected/agent_authenticated/guest_command_ok/stop_requested），trace_end `state=ENDED`；**stop facts 2/3**（owned_vm_stopped + owned_resources_released 真实闭合；`guest_shutdown_acknowledged` 缺失 → `stop_completed` 依约不签）——lane J 2026-11-05 预言的三个 not_observed 信号源中，guest shutdown ack 缺口首次在真实产品启动链上实测坐实；"正常关机"路径 guest 侧无 ack 回执（console 未捕获 shutdown 序列）。
  - **失败腿×3**：①payload 缺件构建（allowMissingPayload 豁免）→ `READINESS_FAILED`（IOException @ GATEWAY 阶段，3 门）；②rootfs 卷校验和不匹配（guest 自我关机）→ `READINESS_FAILED`（8 门，2 事实）；③注入失败（rootfs 卷改名→启动→还原）→ `CREATE_FAILED @ inputs_begin`（2 门）。
  - **缺门归类**：preflight_begin/end、manager_begin 为 AVF lane 专属挂点（QEMU lane 不触发，符合挂点清单）；vm_running_observed/services_ready/guest_shutdown_acknowledged 维持缺信号源。
  - **设备备注**：诊断开关经设置页 UI 真实开启/关闭走通（lane Q 的 UI 入口首次真机使用）；采集期间 stop_requested 后 stop facts 落盘及时，无悬挂 trace。
  - **遗留不变**：guest shutdown ack / services_ready 信号源补齐仍 open；AVF 同口径事件依赖票 07。
