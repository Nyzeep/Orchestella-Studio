# Ticket B2 实施报告：fabric 编排接线——编排消费 route 契约，宽并行任务派发

- 票号/标题：B2 · fabric 编排把 UML 面纳入编译 worker（宽并行任务派发）——性能方案「编排层新开发、数据已齐」项；C1 报告 follow-up 明确 B2 = 编排消费 `FabricWidthDecision.route` 契约
- 执行 session / 模型 / thinking：Lane T fabric 编排接线智能体 / GLM-5.3-Flash / thinking
- 分支：`feat/b2-fabric-orchestration-wiring`　基线 commit：`966c70e`（main）
- 工作区：ASCII 路径隔离 git worktree（主检出未触碰）

## 1. 任务目标

落地性能方案 §B2 的编排层：宽并行编译任务按 C1 决策路由——宽并行（>2）不派 QEMU 面、按 `HOST_NATIVE > UML > QEMU_TCG` 优先级派到 native/UML 面、QEMU 面只接收 ≤2 并行任务；沿用既有 feature gate/账本 admission；不改变单任务（≤2 并行）现有路径的行为（引用：`docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` §B2 + `docs/reports/tickets/c1-fabric-width-decision/implementation-report.zh-CN.md` §9/§10 follow-up）。

## 2. 实际完成

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 现状核实（B2 描述与当前代码一致） | ✅ | 全仓无产品编译派发编排点：`DistccTcpVsockBridge`（loopback TCP→vsock 字节桥，17+ 桥测试）的 `ChannelFactory` 唯一产品形态是 `WorkerPoolDistccDispatcher`（pVM 面设计意图路径，容量 0 时确定性 `DISABLED`），产品 TCP 监听起点缺格 = DV-PVM-02b gap（`WorkerPool.java:51-59` 类注释明文）；UML 面无任何编排接入。方案「编排层新开发」判断与代码一致，B2 的正确落点 = 新建编排层消费 C1 契约 |
| 编排消费 `route` 契约 | ✅ | 新增 `FabricOrchestrator`（app/.../FabricOrchestrator.java，`1d70d7b`）：`dispatch(item, parallelism)` 内部调用 `FabricWidthDecision.route(parallelism, availableWidths())`，宽并行按契约硬性不派 QEMU 面、优先级 native > UML > QEMU、授予 `min(并行度, 面可用宽度)`；admitted 判决原样透传（拒绝码 `WIDE_PARALLEL_NO_FACE`/`NO_FACE_AVAILABLE`/`INVALID_REQUEST` 不重述不改名） |
| QEMU 面只接收 ≤2 并行 | ✅（双重防线） | ①记账点（C1 已落）：`AcceleratorLaunchDispatcher` 构建 qemuRequest 即 `qemuLaneBudgetCores(topology)` clamp；②消费点（本票新增）：`ledgerBackedWidths` 对传入的 QEMU 面宽度再 `min(w, QEMU_TCG_ACCOUNTED_VCPUS)`——即使调用方误传原始拓扑（测试用例 `qemuFaceWidthIsClampedToTheDecisionValueEvenWhenOverdeclared` 锁定），QEMU 面可派宽度也 ≤2 |
| 面宽度从账本派生（「任务并发上限 = 账本核数和」D-3） | ✅ | `ledgerBackedWidths(ledger, qemuFaceWidth, nativeFaceWidth, umlFaceWidth)`：QEMU 面 = C1 记账预留值；native 面 = ACCEL 车道预留 + 账本余量（`budget.cpuCores() - snapshot().cpuCores()`，与池 `Admission.ledgerBacked` 同一判定界）；UML 面 = 接线方 `IntSupplier` 供给（未接线传 0），clamp 到余量防超订。三面宽度之和 == 账本核数由构造保证（测试 `ledgerBackedWidthsSumToTheLedgerBudgetAfterBothLanesReserve` 等两条锁定） |
| 在途宽度记账（并发不超账本） | ✅ | admitted 授予按面记 outstanding，工作返回/失败即释放；编排锁内完成「算可用宽度→route→记账」原子步，worker 执行在锁外（并发派发互不串行化）。测试 `outstandingGrantsBoundDispatchToTheLedgerSum`：阻塞 worker 占住 native 独核时第二次宽派发策略拒绝、窄派发仍走 QEMU 面、在途和恰为账本和（1+2=3） |
| per-worker 回退与掉线剔除（D-3） | ✅ | worker 失败（`FaceException` 与 `RuntimeException` 均收口）→ 释放授予 + `evict(face)` + 固定码 `FACE_FAILED`；被剔除面宽度读 0，后续派发自然回退到剩余面（测试锁定「native 失败 → UML 顶上 → arm() 后恢复」）；重建路径（池 evict-and-rebuild、UML 重启）调 `arm(face)` 复位。per-worker 本地回退阈值本身属 distcc 客户端既有行为（crossfire LOCAL_FALLBACK / 05 文档「UML 面单独调参」），不入编排面 |
| feature gate 沿用 | ✅ | 不加新 flag：编排消费调用方传入的既有双 gate 布尔（`BuildConfig.ACCELERATOR_LANE_ENABLED` + `settingsManager.isAcceleratorLaneEnabled()`，与 `MainActivity.kt:1747` 同源）；gate 关闭 → 固定码 `GATE_DISABLED`，宽度源零读取、worker 零触碰（测试锁定） |
| 单任务（≤2）路径不变 | ✅ | ①产品路径零改动：本票只新增类与 assembly 工厂格，不改任何既有调用点（launch 链、池、桥、账本全部原样）；②契约层：窄并行经 `route` 的判决与 C1 时代逐字节一致（测试 `narrowWorkStillReachesTheQemuFaceWhenItIsTheOnlyFace`/`narrowWorkKeepsTheRoutePriorityOrder`）；③出厂面宽度下「 shipped envelope（= 两车道请求和，无余量）+ UML 未接线」时编排的判决 = QEMU 面 2 + native 1，宽并行（≥3）被策略拒绝——不比今天更激进 |
| assembly 接线格 | ✅ | `AcceleratorLaneAssembly.fabricOrchestrator(enabled, result, qemuFaceWidth, nativeFaceWidth, umlFaceWidth, workers)`（+37 行）：共享协调器账本，与 `workerPool(...)` 同型的消费就绪 seam；测试 `fabricOrchestratorConsumesTheC1RouteContractOverTheCoordinatorLedger` 锁定 C1 风格装配下宽派发授予 native 面 1（账本派生） |
| 全量验证 | ✅ | `:app:testDebugUnitTest`（带豁免）**1207 tests / 0 failures / 0 skipped**（基线 1190 + 新增 17 = 1207，计数吻合）；`:runtime:instance:test :runtime:instance:check` 52/0、`:runtime:fastlane-distcc:test :runtime:fastlane-distcc:check` 77/0/8 skipped（编排只读消费两模块公开契约，回归确认）；`:app:lintDebug` 0 errors / 33 warnings（逐条 grep 证实零条落在 `FabricOrchestrator`/`AcceleratorLaneAssembly` 改动文件，warning 全为既有存量） |

## 3. 未完成 / 与预期偏差

- **①桥 lane 标记通道（方案 B2 优化方案第 1 项，05 文档 D-1）未做**：`DistccTcpVsockBridge` 增加 lane 标记通道（vsock=pVM 面 / TCP=UML 面）。原因：05 文档 §2.3 明文该改动面「要过 ACR」，且 UML 面无产品启动链与监听端时该 wire 改动无消费方；受本 lane 文件边界（不碰首启/provision 相关与 Settings UI，不碰 build 文件）与「不硬改禁区」纪律约束，记 follow-up 交回编排层（§9）。
- **UML 面产品接线未做**（UML 启动器产品化 + 前台服务/wake lock 硬前提，方案 B2 成本节自列）：编排以注入 `FaceWorker` seam 消费 UML 面，宽度由接线方 `IntSupplier` 供给、未接线传 0——UML 面接入时编排层零改动可挂（C1 报告 §10 预判兑现）。
- **真机端到端复测不在本票 scope**：B2 验证方法原文「零设备：编排单元测试（fake worker）；真机：C200 夹具派发 vs 单 worker 对照」——本票零设备，完成前半；真机项归设备窗（§10）。
- B3（监听起点/容量定档）是相邻票：编排的宽度输入（账本余量）随 B3 容量决策自动生效，本票不预支其行为。

## 4. 修改的模块

| 模块 | 文件 | 性质 |
| --- | --- | --- |
| `:app` main | `app/src/main/java/io/github/nyzeep/orchestella/FabricOrchestrator.java`（新增，324 行） | B2 编排核：route 消费 + 账本派生宽度 + 在途记账 + evict/arm，纯 JVM、固定码、无 I/O、无设备数据 |
| `:app` main | `app/src/main/java/io/github/nyzeep/orchestella/AcceleratorLaneAssembly.java`（+37/-1 行） | `fabricOrchestrator(...)` 接线格 + kdoc |
| `:app` test | `app/src/test/java/io/github/nyzeep/orchestella/FabricOrchestratorTest.java`（新增 16 用例） | 语义锁定（fake worker） |
| `:app` test | `app/src/test/java/io/github/nyzeep/orchestella/AcceleratorLaneAssemblyTest.java`（+1 用例） | 接线锁定 |
| docs | `docs/research/2026-11-05-performance-optimization-plan.zh-CN.md`（B2 节落地标注）、`docs/reports/tickets/b2-fabric-orchestration-wiring/implementation-report.zh-CN.md`（本报告） | 记录（新增文件 `git add -f`） |

**边界核对**：未触碰 `runtime/instance/**`（账本只读消费公开 API）、首启 provision 相关文件、Settings UI 文件、`.github/workflows/**`、各 `build.gradle.kts`、`runtime/fastlane-distcc/**`（桥 D-1 改动按 ACR 约束留 follow-up）；不涉及真实设备数据（测试与 javadoc 全为构造值/在案数字引用）。

## 5. 重要设计决策

1. **编排落点 = 消费就绪 seam 而非强行找产品调用方**：现状核实确认产品编译派发编排点不存在（native 面监听器是 DV-PVM-02b、UML 面未产品化）。仓库先例（`WorkerPoolDistccDispatcher` 落地时同样无产品监听方）支持把编排落为「设计意图路径上的消费就绪 seam」+ 接线状态明文（类 kdoc "Wiring state (B2)" 节），不虚构调用点、不改既有路径。
2. **宽度派生公式与其不变量**：三面宽度之和 == 账本核数（D-3）由 `ledgerBackedWidths` 构造保证——QEMU 面与 native 面传「已计入 snapshot 的车道预留值」（调用方从 launch 链持有，单一转换点在 `AcceleratorLaunchDispatcher`），native 面再吃账本余量（与池 `ledgerBacked` admission 同界），UML 面供给值 clamp 到余量（超报不超订账本）。产品 shipped envelope（包络 = 两车道请求和）下三面 = QEMU 2 + native 1 + UML 0，和 = 3 = 账本核数：编排今天的行为就是 C1 语义的忠实消费，宽度随 B3/C2 预算决策自动生长。
3. **无 worker 的面 = 真不可用（`NO_FACE_AVAILABLE`），有 worker 的 QEMU 面被宽并行排除 = 策略拒绝（`WIDE_PARALLEL_NO_FACE`）**：编排可用宽度 = 账本预算宽度 − 在途 − 无 worker/被剔除置 0。这保持 C1 两码语义在编排层的诚实性——测试 `aFaceWithoutAWorkerIsAGenuineOutageNotThePolicyRefusal` 与 `wideParallelWorkRefusesWithThePolicyCodeWhenOnlyQemuRemains` 分 别锁定。QEMU 面的产品 worker = guest 本地执行 seam（产品接线时以 no-op 型 worker 注册）。
4. **失败收口形状**：任何面 worker 失败（受检 `FaceException` 与未预期 `RuntimeException`）统一 = 释放授予 + evict + 固定码 `FACE_FAILED`——账本宽度不泄漏（失败路径与成功路径同样记账），编排日志只有一个失败面，不向调用方泄漏 worker 内部异常类型；与池「opener 失败 release lease」同向但多一步 evict（D-3 掉线剔除）。
5. **Outcome 形状 = 恰一互斥**：`Outcome(route, code)`——admitted/route 拒绝时携带 `FabricWidthDecision.Dispatch` 原样（拒绝码不重述），编排层自有码仅 `GATE_DISABLED`/`FACE_FAILED` 两个；compact constructor 强制恰一互斥，延续 C1 `Dispatch` 与仓库 `Decision(allowed, rejectCode)` 惯例。
6. **是否新开 ADR：否**。理由同 C1 报告 §5.6：性能参数级/编排实现取舍，不改任何 ADR 契约语义；`docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md` §3 口径「工单 Acceptance criteria 内的实现取舍写实施报告即可」。方案 B2 节已加落地标注（引用本报告与 commit）。

## 6. 测试

- **新增** `FabricOrchestratorTest`（16 用例，JVM，fake worker）：
  - gate：关闭时 `GATE_DISABLED` 且宽度源零读取、worker 零执行（零副作用）。
  - Outcome 形状：恰一互斥两反例。
  - 宽并行路由：8-way → native（授予 min(8,4)），native 预算归零 → UML 顶上且不受 QEMU 排除影响；仅 QEMU 有 worker → `WIDE_PARALLEL_NO_FACE`；无 worker 面 → `NO_FACE_AVAILABLE`（两码区分）。
  - 窄任务不变：≤2 并行唯一面为 QEMU 时照派（2→2、1→1），优先级序与 C1 时代一致；并行度 ≤0 → `INVALID_REQUEST`。
  - 在途记账 + D-3：阻塞 worker 占 native 独核时第二次宽派发策略拒绝、窄派发照走 QEMU（在途和恰=账本和 3）、grant 返回后面恢复服务；worker 失败一次（受检/运行时两种）→ 授予释放 + evict + `FACE_FAILED`、回退 UML、`arm()` 后恢复（重建修复 worker 语义）；显式 `evict`/`arm` 对称（evict 后 `NO_FACE_AVAILABLE`，arm 后恢复）。
  - 账本求和：真实 `LaneBudgetLedger` C1 产品流（QEMU 2 + ACCEL 1，包络=和）→ 三面和 == 账本核数；UML 超报 clamp 到余量且 native 保留预留（和仍守恒）；QEMU 宽度消费点再 clamp 到 2（调用方误传原始拓扑也不破防）；负数/超预算预留宽度 `IllegalArgumentException`。
- **新增** `AcceleratorLaneAssemblyTest` 1 用例：C1 风格装配（8 核 → clamp 2 + ACCEL 1）+ `startAll` 后经 `fabricOrchestrator(...)` 宽派发（4-way）授予 `HOST_NATIVE` 面 1（账本派生宽度，宽并行不落 QEMU 面）；gate 关闭接线 → `GATE_DISABLED` 零副作用。
- **既有断言逐个核对（零改动、零放松）**：`AcceleratorLaneAssemblyTest` 既有 15 用例、`AcceleratorLaunchDispatcherTest`（C1 接线）、`FabricWidthDecisionTest`（C1 决策核）、`WorkerPoolDistccDispatcherTest`、`DualLaneCoordinatorTest` 全部原样通过——本票不改任何既有类。
- **运行结果（真实输出摘要）**：
  - 定向：`./gradlew :app:testDebugUnitTest --tests FabricOrchestratorTest --tests AcceleratorLaneAssemblyTest -Porchestella.assets.allowMissingPayload=true` → BUILD SUCCESSFUL（XML 汇总 32 tests，其中 FabricOrchestratorTest 16 / AcceleratorLaneAssemblyTest 16）。
  - 全量：`./gradlew :app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true` → BUILD SUCCESSFUL，XML 汇总 **tests=1207 failures=0 errors=0 skipped=0**（基线 1190 + 新增 17 = 1207，计数吻合）。
  - 回归面：`./gradlew :runtime:instance:test :runtime:instance:check :runtime:fastlane-distcc:test :runtime:fastlane-distcc:check` → BUILD SUCCESSFUL（instance 52/0；fastlane-distcc 77/0/8 skipped，skip 为该模块既有 POSIX 守卫项）。
  - `:app:lintDebug`（带豁免）→ BUILD SUCCESSFUL（**0 errors / 33 warnings / 799 hints**；逐条 grep 证实零条落在 `FabricOrchestrator`/`AcceleratorLaneAssembly` 改动文件上，warning 全为既有存量）。
- **冒烟清单（真机）**：无（零设备票，不适用；真机项见 §10）。

## 7. Benchmark / 实测数据

本票不涉及（零设备；所引用数字 E1/E2/E5/E6/E11/E12 全部为在案实测，出处见方案 §0 证据表与 §B2 节）。真机端到端收益（宽并行墙钟随 worker 宽度接近 native 形态）按 B2 验证方法归 C200 夹具设备窗（控温协议照 F-MTTCG §5.2），前提是 UML 面/N 监听起点产品接线（B3/C2/本票 follow-up）。

## 8. 风险

- **无产品提交方期间的语义漂移风险（低）**：编排是消费就绪 seam，未来监听器接线时若面 worker 语义与本票假设（一连接≈一工作项、授予宽度=该面可并发数）不符，需回看本票测试锁定的契约——契约已用固定码/record 形状钉死，漂移会被编译期/测试期拦住。
- **双消费点数字漂移**：QEMU 面宽度 clamp 与 C1 记账共用 `QEMU_TCG_ACCOUNTED_VCPUS` 单点常量（本票消费点调用同一常量，不复制数字），ACR 修订时单点改动。
- **账本只读消费的一致性前提**：`ledgerBackedWidths` 要求调用方传入「已计入 snapshot 的车道预留值」（QEMU/native 面）；若未来调用方在 `startAll` 前接线，会出现短暂的面宽度双计（QEMU 预留既在宽度又在余量）——kdoc 已明文契约「pass the reservations, never re-derivations」，接线点应在 `startAll` 之后。
- **混部保守性沿用 C1**：宽并行策略拒绝意味着「只有 QEMU 面」时宽任务交回调用方本地回退（guest 本地 ≤2），不进 92% 翻译税面——墙钟换正确性，与 C1 定案同向。

## 9. 新发现 / Follow-up

- **Follow-up（编排层，交回编排层裁决）**：方案 B2 优化方案第 1 项「`DistccTcpVsockBridge` 增加 lane 标记通道（vsock=pVM 面 / TCP=UML 面，wire 不变）」未落地——05 文档 §2.3 明文该改动面需过 ACR，且 UML 面无产品启动链时无消费方。建议与 UML 启动链产品化（research/uml-runner 同型）同票过 ACR。
- **Follow-up（产品接线，DV-PVM-02b 既有 gap）**：native 面（pVM 池）的产品 TCP 监听起点仍是缺口；接线时把监听器的连接请求经 `FabricOrchestrator.dispatch` 走编排（native worker = `WorkerPoolDistccDispatcher` 型 seam），即可获得宽并行不派 QEMU 面的编排语义。
- **Follow-up（C2 依赖）**：UML 面宽度供给（`ncpus=N` 由账本并发预算扣除）由 C2 落地后经 `umlFaceWidth` IntSupplier 注入，编排层零改动。
- 编排层对 `LaneBudgetLedger` 为只读消费（snapshot/budget 公开 API），未发现账本机制缺口；D1（non-reclaimable 标记）落点仍在 runtime/instance，与本票无冲突。

## 10. 对后续 ticket 的影响

- **B3（worker 池监听起点/容量）**：编排的 native 面宽度 = ACCEL 预留 + 账本余量，与池 `ledgerBacked` admission 同界——B3 容量定档（显式决策）后编排面宽度自动跟随，无需改本票代码；B3 接监听起点时按 §9 follow-up 走编排派发。
- **C2（UML ncpus 产品化）**：宽度经 `umlFaceWidth` 注入即挂上编排；注入前 UML 面恒 0（不虚增预算）。
- **ACR（D-1/D-3 追认）**：D-3「并发上限 = 账本核数和 + 掉线剔除」已落为可回归语义（本票），ACR 追认输入 = 本报告 §5；D-1 桥 lane 通道待 ACR。
- **真机窗（C200 派发复测）**：编排判决的端到端收益测点——对照本票测试锁定的面判决与授予宽度语义控温复跑。

## 11. Commit

分支 `feat/b2-fabric-orchestration-wiring`（基线 `966c70e`）：

- `1d70d7b` feat(app): B2 fabric 编排落地——消费 FabricWidthDecision.route 契约的编排层
- `feadbd0` test(app): 锁定 B2 fabric 编排路由语义——16 用例编排核 + 1 用例 assembly 接线
- docs commit（本报告 + 方案 B2 节落地标注，新增文件 `git add -f`）：见 git log 同分支后续提交

## 12. Ticket 状态

**completed**（编排层部分；本地，零设备票）。B2 的「编排消费 route 契约」主体已落为可回归语义与 17 个新测试；方案 B2 的桥 lane 通道（D-1，ACR-bound）与 UML 面产品接线为明确的 follow-up（§9），不阻塞本票验收口径——方案 B2 验收的零设备半边（编排单元测试 fake worker）已满足。

报告人：Lane T fabric 编排接线智能体（GLM-5.3-Flash）｜ 日期：2026-11-06 ｜ 耗时：本 session
