# Ticket C1 实施报告：fabric 派发宽度与 vCPU 记账定案落地

- 票号/标题：C1 · 派发宽度与 vCPU 记账定案——QEMU 面按 2 记账、宽并行不派 QEMU（性能方案最高性价比项，原判"零代码决策"，本票将其落为真实编排语义）
- 执行 session / 模型 / thinking：Lane P 编排决策落地智能体 / GLM-5.3-Flash / thinking
- 分支：`feat/c1-fabric-width-decision`　基线 commit：`2d66f67`
- 工作区：`ASCII 路径 worktreet/orch-lane-p`（隔离 git worktree，主检出未触碰）

## 1. 任务目标

把 `docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` §C1 的定案落进既有账本/admission 路径：① QEMU-TCG 引擎面的 vCPU 记账按 2 计（而非硬件核数）；② 宽并行任务（并行度 > 2 的编译任务类）不被派到 QEMU 面；沿用既有 feature gate/配置惯例，默认取向为启用（定案）；单测锁定新记账语义。

## 2. 实际完成

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 现状核实（C1 引用的 文件:行 与当前代码一致） | ✅ | 账本 `LaneBudgetLedger.reserve()` 按 `request.cpuCores()` 求和校验（runtime/instance/.../LaneBudgetLedger.java:22-43）；admission 双车道 `DualLaneCoordinator.admission()`（app/.../DualLaneCoordinator.java:299-301）+ 池 `WorkerPool.Admission.ledgerBacked`（app/.../WorkerPool.java:293-311）均读账本快照/预算；记账挂点 `AcceleratorLaunchDispatcher.dispatch()` 构建 `qemuRequest = LaneRequest(QEMU, hardware.cpuCores(), …)`（app/.../AcceleratorLaunchDispatcher.kt:153-158，`2d66f67` 行号）——正是 C1 指认的"虚高占额"点 |
| ① QEMU-TCG 面按 2 记账 | ✅ | 新增 `FabricWidthDecision.qemuLaneBudgetCores(hardwareCores)` = `min(hardwareCores, 2)`；`AcceleratorLaunchDispatcher` 接线（唯一"硬件事实→车道预算"转换点）。1 核客户机不虚增；launch 拓扑与 QemuTuning 默认值不变（记账与 `-smp` 解耦） |
| ② 宽并行不派 QEMU 面 | ✅（决策核形态） | 新增 `FabricWidthDecision.route(parallelism, faceWidths)`：并行度 > 2 硬性排除 `QEMU_TCG` 面（固定码 `WIDE_PARALLEL_NO_FACE`）；优先级 `HOST_NATIVE > UML > QEMU_TCG`；授予宽度 = `min(并行度, 面预算宽度)`。QEMU 面编译派发产品接线本就不存在（DV-PVM-02b gap）、UML 面未接入编排（B2 未落地），故落为 B2 消费的派发契约而非接产品调用方 |
| feature gate 默认值取向 | ✅ | C1 为实测定案、非可调旋钮：不加新 flag，活在既有编排路径双 gate（build 时 `orchestella.acceleratorLane`→`BuildConfig.ACCELERATOR_LANE_ENABLED` + 用户 `settingsManager.isAcceleratorLaneEnabled()`）之下默认生效（见 §5） |
| 单测锁定新语义 | ✅ | 新增 `FabricWidthDecisionTest` 15 用例 + `AcceleratorLaunchDispatcherTest` 2 用例（§6）；既有账本断言逐个核对后零改动通过（§6） |
| 全量验证 | ✅ | `:app:testDebugUnitTest`（带豁免）1188 tests / 0 failures / 0 skipped（基线 1171 + 新增 17 = 1188）；`:runtime:instance:test :runtime:instance:check` BUILD SUCCESSFUL（44 tests，账本契约回归面，模块零改动）；`:app:lintDebug` 见 §6 |

## 3. 未完成 / 与预期偏差

- C1 原文第 ③ 点「记账公式按 R-Q7（`usedCpu = smp N + match_host 全核`）」：公式所依赖的 ACCEL 面 match_host 档当前未启用（`ACCEL_REQUEST` 为 ONE_CPU=1 定档），且其语义文档化落点 `LaneBudgetLedger`/`LaneRequest` javadoc 在 `runtime/instance` 模块——按本 lane 文件边界（不碰 `runtime/instance/**`）留 follow-up（§9）。当前 `reserve()` 的求和校验顺序对 R-Q7 公式天然兼容，未来 match_host 档启用时按 R-Q2 填宿主全核即可，无需改账本机制。
- 「仅派 2 并发」备选路径（C1 原文"跳过 QEMU 面或仅派 2 并发"）取了前者（硬排除）——定案证据（S(8) 反噬、翻译税 92%）支持不派而非限流派；若未来混部场景需要"限流派"，`route` 是唯一改动点。
- 真机端到端复测（F-MTTCG-C200 派发）不在本票 scope（零设备票）；C1 验证方法原文即"零设备（数据已在案）"。

## 4. 修改的模块

| 模块 | 文件 | 性质 |
| --- | --- | --- |
| `:app` main | `app/src/main/java/io/github/nyzeep/orchestella/FabricWidthDecision.java`（新增） | C1 决策核：记账 clamp + 派发路由，纯函数、固定码、无 I/O、无设备数据 |
| `:app` main | `app/src/main/java/io/github/nyzeep/orchestella/AcceleratorLaunchDispatcher.kt`（+13/-1 行） | 记账接线（qemuRequest cpuCores 换为 `FabricWidthDecision.qemuLaneBudgetCores(...)`）+ kdoc |
| `:app` test | `app/src/test/java/io/github/nyzeep/orchestella/FabricWidthDecisionTest.java`（新增 15 用例） | 语义锁定 |
| `:app` test | `app/src/test/java/io/github/nyzeep/orchestella/AcceleratorLaunchDispatcherTest.kt`（+2 用例，fixture `params()` 参数化） | 接线锁定 |
| docs | `docs/research/2026-11-05-performance-optimization-plan.zh-CN.md`（C1 节落地标注）、`docs/reports/tickets/c1-fabric-width-decision/implementation-report.zh-CN.md`（本报告） | 记录（git add -f） |

**边界核对**：未触碰 Settings/诊断 UI 文件、`runtime/instance/**`、`.github/workflows/**`、`MobileAuxiliaryKeyBar`；未处理任何空 catch（另一 lane 负责）；不涉及真实设备数据（证据引用全部走稳定别名文档路径，无序列号/私人数据入仓）。

## 5. 重要设计决策

1. **记账落点选在 dispatcher 而非账本**：`LaneBudgetLedger`（runtime/instance）是通用双车道契约模块，不应知道 QEMU-TCG 的性能定案；`AcceleratorLaunchDispatcher` 的类注释自述为"the one call site that routes a real launch"，且是全仓唯一从硬件事实构建 QEMU `LaneRequest` 的产品路径（grep 证实，测试直接构造的除外）。在该转换点把"客户机拓扑"换算成"车道并发预算"（R-Q3 语义），账本与 `ledgerBacked` admission 的既有求和机制原样吃到记账后预算——全链生效且 `runtime/instance` 零改动（文件边界达标）。`AcceleratorLaneAssembly` 继续按"envelope = 两车道请求之和"契约工作，不变。
2. **记账公式取 `min(拓扑, 2)` 而非恒 2**：定案的本意是"TCG 面有效并发宽度上限 = 2"（S(2) 最优、S(4) 不达判据、S(8) 反噬）；1 核客户机的有效宽度就是 1，按 2 记会虚增预算，违背预算正确性方向（D1 同向）。常量 `QEMU_TCG_ACCOUNTED_VCPUS = 2` 单点定义，记账 clamp 与派发排除共用。
3. **默认值取向 = 启用、不加新旋钮**：C1 是判据驱动的实测定案（2026-11-07 报告 §4.1 对 W-FAB-07/08 的回答 + 方案 §C1，T9 双设备复核 n=10），不是"T7 型未验证参数"（后者才是 opt-in 旋钮惯例的适用面）。编排路径本身已有 build flag + 用户开关双 gate；C1 在其下作为账本语义定案无条件生效。方案既定口径"不改 QEMU 默认参数"同步守住：launch 拓扑原样传给 `qemuLaunch`，只有记账变。
4. **宽并行拒绝用独立固定码 `WIDE_PARALLEL_NO_FACE`**（区别于 `NO_FACE_AVAILABLE`）：让编排日志能区分"策略有意不派"与"真的无面可用"，符合仓库 fixed-code 惯例（对照 `LaneRejectCode`、`WorkerPool.Code`）。`Dispatch` record 采用仓库 `Decision(allowed, rejectCode)` 形状（admitted 时 code 为 null，恰一互斥由 compact constructor 强制）。
5. **是否新开 ADR：否**。`docs/adr/README.md` 为 accepted 架构决策索引（无显式收录标准节），21 条全部为架构级（runtime 类别/契约/许可/生命周期/进程所有权等）；C1 是性能参数级定案，不改任何 ADR 契约语义，且 `docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md` §3 明确"工单 Acceptance criteria 内的实现取舍（如 QEMU 参数）——写进实施报告即可"，故不触发架构变更门、不新开 ADR。落地记录已标注在方案文档 C1 节（落地+commit 引用）+ 本工单报告，符合任务预置的判断路径。

## 6. 测试

- **新增** `FabricWidthDecisionTest`（15 用例，JVM）：
  - 记账：8/4/2→2、1→1（不超真实拓扑）、0/-负数→`IllegalArgumentException`、常量钉值（`QEMU_TCG_ACCOUNTED_VCPUS == 2`，防两处数字漂移）。
  - 派发：窄并行（1/2）QEMU 唯一可用时可派、授予宽度 ≤ 面预算（clamp）；宽并行（3/4/8）即使 QEMU 唯一可用也拒绝（`WIDE_PARALLEL_NO_FACE`、宽度 0）；全三面可用时优先级 native > UML > QEMU；native 缺席时 UML 顶上且不受 QEMU 排除影响；请求 4 面 2 → 授予 2；策略性拒绝 vs 真无面两码区分；窄并行同走优先级序。
  - 请求形状：并行度 ≤0 → `INVALID_REQUEST`；空 fabric → `NO_FACE_AVAILABLE`；`Dispatch` record 恰一互斥（4 个反例）。
- **新增** `AcceleratorLaunchDispatcherTest` 2 用例（JVM）：
  - `c1AccountingClampsTheQemuBudgetWhileTheLaunchKeepsItsTopology`：8 核硬件下 `qemuLaunch` 收到的 `hardwareConfig.cpuCores()==8`（拓扑不变）而账本 budget/snapshot == 3（QEMU 2 + ACCEL 1，非旧语义的 9）。
  - `c1AccountingKeepsASingleCoreGuestAtItsRealWidth`：1 核客户机记账 1+1=2（clamp 不虚增）。
- **既有断言逐个核对（零改动、零放松）**：
  - `AcceleratorLaneAssemblyTest`（:73 `==4`、:141 `==6`、:294 `==6`）：该测试直接构造 `QEMU_REQUEST(4)`，不经过 dispatcher 的硬件→预算转换点；assembly/账本机制（envelope=请求之和、池增长受账本约束）语义不变 → 原样通过。
  - `AcceleratorLaunchDispatcherTest.armedFactsStartBothLanes…`（:89-90 `==3`）：fixture 为 2 核硬件，`min(2,2)=2` → 3/3 原样成立。
  - `DualLaneCoordinatorTest`、`runtime/instance` 模块账本测试：机制级，不受 app 层记账影响 → 原样通过。
- **运行结果（真实输出摘要）**：
  - 定向：`./gradlew :app:testDebugUnitTest --tests FabricWidthDecisionTest --tests AcceleratorLaunchDispatcherTest --tests AcceleratorLaneAssemblyTest --tests DualLaneCoordinatorTest -Porchestella.assets.allowMissingPayload=true` → BUILD SUCCESSFUL（26 tests）。
  - 全量：`./gradlew :app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true` → BUILD SUCCESSFUL，XML 汇总 **tests=1188 failures=0 skipped=0**（基线 1171 + 新增 17 = 1188，计数吻合）。
  - 回归面：`./gradlew :runtime:instance:test :runtime:instance:check` → BUILD SUCCESSFUL（模块零改动，账本契约回归确认）。
  - `:app:lintDebug`（带豁免）→ BUILD SUCCESSFUL（1m 17s；XML 汇总 **0 errors / 830 warnings**，逐条 grep 证实零条落在 `FabricWidthDecision`/`AcceleratorLaunchDispatcher` 改动文件上——warning 全为既有存量）。
- **冒烟清单（真机）**：无（零设备票，不适用）。

## 7. Benchmark / 实测数据

本票不涉及（零设备；所引用数字 E2/E3/E4 全部为在案实测，出处见方案 §0 证据表与 `FabricWidthDecision` javadoc）。产品化后的端到端复测按 C1 验证方法归 F-MTTCG-C200 夹具设备窗。

## 8. 风险

- **混部保守性**（C1 原文已列）：判据绑定 make 型夹具，「交互型负载 vs 编译型负载」混部时 2 vCPU 记账可能偏保守——本票未改 QEMU 侧任何实际资源参数，仅账本占额变小（释放预算给池/UML 面），无回归性风险面。
- **双消费点数字漂移**：记账 clamp 与派发排除共用同一常量，测试钉值；若 ACR 未来修订宽度，`QEMU_TCG_ACCOUNTED_VCPUS` 单点改动。
- **语义误解风险**：`LaneRequest.cpuCores` 的"并发预算"语义文档化在 runtime/instance javadoc（R-Q3）尚未落地（follow-up），新读者可能把 QEMU 请求的 2 误读为 `-smp 2`——dispatcher kdoc 与测试注释已显式说明"记账与拓扑解耦"。

## 9. 新发现

- **Follow-up（受本 lane 文件边界限制）**：R-Q3「cpuCores = 车道并发预算」javadoc 与 R-Q7/R-Q2 match_host 档记账公式说明应补在 `runtime/instance/.../LaneBudgetLedger.java` 与 `LaneRequest.java`——另一 lane/后续票处理；本票不硬改。
- **Follow-up（编排层）**：B2 落地时直接消费 `FabricWidthDecision.route`（宽度输入由账本派生）；`WIDE_PARALLEL_NO_FACE` 出现时的编排动作（本地排队/降单核）是 B2 的设计输入，本票不预定义。
- QEMU 面编译派发（distcc → QEMU guest）产品接线缺格照旧记 DV-PVM-02b gap，与本票无冲突。

## 10. 对后续 ticket 的影响

- **B2（fabric 编排接线）**：派发决策核已就位——B2 的 per-face 宽度应从账本快照派生（QEMU 面有效宽度即记账值 2），并遵守 `route` 的优先级与宽并行排除；UML 面接入时无需改本票代码。
- **D1（账本语义补丁）**：`min(拓扑,2)` 记账使 QEMU 面不再按虚高核数占额，D1 的 non-reclaimable/R-Q3 javadoc 落地时与本票常量同场复核。
- **T7/启动路径**：QEMU 默认参数未动，T7 旋钮矩阵与 A1 无影响。
- **worker 池（B3）**：编排路径内账本占额变小 → `ledgerBacked` 判定的可用余量语义随之更真实（8 核机器旧语义 budget=9 全占，新语义 budget=3）；B3 容量定档时以此为准。

## 11. Commit

分支 `feat/c1-fabric-width-decision`（基线 `2d66f67`）：

- `5400fdb` feat(app): C1 fabric 宽度定案落地——QEMU-TCG 面并发预算按实测有效宽度 2 记账
- `46b2c9f` test(app): 锁定 C1 记账与派发语义——15 用例决策核 + 2 用例 dispatcher 接线
- docs commit（本报告 + 方案 C1 节落地标注，`git add -f`）：见 git log 同分支后续提交

## 12. Ticket 状态

**completed**（本地；零设备票，无真机验收项）。C1 定案已落为可回归的编排语义与契约测试；ACR 议程若需追认，输入即本报告 §5 的设计决策与方案 C1 节落地标注。
