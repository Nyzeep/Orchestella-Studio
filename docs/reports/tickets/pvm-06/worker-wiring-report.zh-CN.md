# PVM-06 接线报告 · 编译 worker 池进入设计意图调用路径

- 票：`PVM-06`（`.scratch/orchestella-pvm-compiler/issues/PVM-06-编译worker池与调度.md`，接线轮 Comment 5）
- 性质：接线（Lane C 专项），前置为 777827e 的「零接线」池实现
- 分支：`feat/pvm-06-worker-wiring`　基线：main `b39e8e6`
- 日期：2026-11-04
- 设备：**全程未使用**，`adb` 一次未调；全部验证走 JVM 测试/契约测试（零设备约束达标）

## 1. 背景、scope 与「零接线」的成因

777827e 交付了 `WorkerPool` 接口 + `ProtectedWorkerPool` 实现（N 槽编译 worker 池），
但 `app/src/main` 无任何构造点。侦查确认「零接线」有三层成因：

1. **缝存在但没有池侧实现**：设计意图（工单背景「宿主把 distcc 连接分派到空闲实例」，
   `WorkerPool.Lease` javadoc「The dispatcher opens its own distcc channel on
   `session.spec().distccPort()`」）的分派点就是 `DistccTcpVsockBridge.ChannelFactory`
   （`runtime/fastlane-distcc`）——桥每接受一条 distcc 连接经它开一个通道。E2E（PVM-11）
   在该缝上插的是直连单 payload 的 `Axp1DistccChannelFactory`，不经任何池。
2. **模块方向断了**：池类型在 `app`，桥类型在 `:runtime:fastlane-distcc`，而 app 主源
   依赖列表里没有该模块（只有 androidTest 依赖）——app 主源**引用不到桥类型**。
3. **工单边界**：PVM-06 明写「产品接线归 PVM-09，本票零接线」；PVM-09 完成了车道启动
   （manifest/facts/starter/dispatcher）但把「编译工作的生产调用方」划归 DV-PVM-02b
   设备工作（PVM-09 报告 §5.4「这是边界，不是遗漏」）——池因此始终没有进入任何调用路径。

本轮 scope：把 1、2 两层接通（以契约测试可验证为界），第 3 层（产品 TCP 监听起点）
按既有边界不越界，记录缺口并给最小方案。

## 2. 接线方案（一句话）

**新增 `WorkerPoolDistccDispatcher`（app）实现 `DistccTcpVsockBridge.ChannelFactory`：
一条 distcc 连接 = 一次 `pool.acquire(job)` 租约，在租出实例自己的 distcc 端口上做
AXP1 握手，桥终态关通道时租约恰一次归还池**；生产构造走
`AcceleratorLaneAssembly.workerPool(...)`（与双车道共用同一预算账本），容量出厂默认仍 0。

## 3. 实际完成（逐条）

| # | 项 | 落点 | 结果 |
| --- | --- | --- | --- |
| 1 | 分派器本体：租约生命周期 | `app/src/main/java/io/github/nyzeep/orchestella/WorkerPoolDistccDispatcher.java`（新增）：`open()` = acquire → per-lease opener → `LeasedChannel`（close 后 `releaseOnce`）；opener 失败先 release 再原样重抛；输出流半关不归还（AXP1 正常协议形态） | **完成**（契约测试 6 条） |
| 2 | 生产 opener：租出实例的 distcc 端口 | `axp1OverLease(token, caps, triple, limits)`：`Axp1DistccChannelFactory` 原样复用，包 `() -> lease.session().openChannel(spec.distccPort())`；token/triple 每轮由调用方传入（uplink plan × payload manifest），本类不存任何秘密 | **完成**（集成测试证明 connect 打到 5103 而非 5100） |
| 3 | 池拒绝 → 桥可观测固定码 | `WORKER_POOL_<CODE>` 机械全映射（17 码），形状 `[A-Z0-9_]{1,64}` 过桥的 `ConnectionStats` 归一化，有全码测试钉住 | **完成** |
| 4 | 生产构造缝 + 账本共享 | `AcceleratorLaneAssembly.workerPool(api, specFactory, result, config)`：`Admission.ledgerBacked(coordinator.ledger())` 只读共享；容量由调用方 config 决定，`Config.defaults()` 仍 0 = 今天的确定性 `DISABLED` 拒绝（决策地图 §8.4 未动） | **完成**（装配测试 2 条） |
| 5 | 模块方向 | `app/build.gradle.kts` 主源 +1 行 `implementation(project(":runtime:fastlane-distcc"))`（纯 JVM：engine-fastlane + contract，无 Android 依赖进场） | **完成** |
| 6 | 文档一致性 | `WorkerPool.java`/`ProtectedWorkerPool.java` 的「intentionally not wired」javadoc 改写为当前状态（分派器已接、产品监听起点未接、0 容量默认行为不变） | **完成** |
| 7 | 不回归 PVM-11 真实 distcc 客户端 E2E | `runtime/fastlane-distcc` **源码零改动**；模块 `test`+`check` 全绿，8 skipped 均为既有 POSIX 前置（含 E2E 本体，Windows 按文档 skip，上轮真跑在 WSL） | **无回归** |

## 4. 记录的缺口（不硬编）与最小可行方案

- **产品 TCP 监听起点在 app 主源仍不存在**（guest distcc → `bridge.start()` → 本分派器）。
  这是 PVM-09 报告 §5.4 明文划给 DV-PVM-02b 的设备工作，本轮零设备不越界。剩余工作量已
  压到一个调用点：
  `new DistccTcpVsockBridge(loopback, port, new WorkerPoolDistccDispatcher(pool, axp1OverLease(token, caps, triple, limits)), jobLimit, idleMs)`——无剩余适配层。
- **排队超时与桥 idle 看门狗的关系**：acquire 默认排队超时（Config 30s）应短于桥 idle
  （默认 60s）；若看门狗先触发，它关通道 → `LeasedChannel.close()` → 租约归还，两侧都
  安全，只是不如先到先得礼貌。已写进类 javadoc，接监听起点时按实测校参。
- **AXP1 握手对租出实例的端到端 wire 级验证**留在 fastlane-distcc 模块既有测试
  （`Axp1DistccChannelFactoryTest` 等，握手协议层）与本轮 app 侧集成测试（池层到
  connect 端口层）之间：app 单测内伪造 AXP1 GUEST 端等于复制协议知识，不做；两段拼起来
  已覆盖「连接 → 池 → 租出实例端口」全链，唯独「租出实例 × 真 payload 握手」这最后
  一格归 DV-PVM-02b 设备窗（零设备约束下不可达）。

## 5. 修改的模块

| 文件 | 性质 |
| --- | --- |
| `app/src/main/java/io/github/nyzeep/orchestella/WorkerPoolDistccDispatcher.java` | **新增**：池后端 distcc 分派器（`ChannelFactory` 实现） |
| `app/src/main/java/io/github/nyzeep/orchestella/AcceleratorLaneAssembly.java` | **+25 行**：`workerPool(...)` 生产构造缝（账本共享、容量透传） |
| `app/src/main/java/io/github/nyzeep/orchestella/WorkerPool.java` | 仅 javadoc：接线状态更新 |
| `app/src/main/java/io/github/nyzeep/orchestella/ProtectedWorkerPool.java` | 仅 javadoc：接线状态更新 |
| `app/build.gradle.kts` | **+4 行**：主源依赖 `:runtime:fastlane-distcc`（含注释） |
| `app/src/test/java/io/github/nyzeep/orchestella/WorkerPoolDistccDispatcherTest.java` | **新增**：9 条（fake pool 6 + 真 `ProtectedWorkerPool`×fake 平台 3） |
| `app/src/test/java/io/github/nyzeep/orchestella/AcceleratorLaneAssemblyTest.java` | **+2 条**：workerPool 缝 |
| `.scratch/orchestella-pvm-compiler/issues/PVM-06-编译worker池与调度.md` | Comment 5 回填 |
| `docs/reports/tickets/pvm-06/worker-wiring-report.zh-CN.md` | 本报告 |

未触碰：`runtime/**` 全部源码（含 fastlane-distcc、engine-fastlane、instance）、
`MainActivity`/`AcceleratorLaunchDispatcher`/`DualLaneCoordinator`（产品启动路径零改动）、
`LaneBudgetLedger` 行为面、`ProtectedWarmPool`、`fastlane-payload/`、PVM-11 相关文件。

## 6. 测试与验证结果（Gradle 实跑，非虚报）

执行环境：纯 ASCII 路径的本地 git worktree（基线 main `b39e8e6`），Gradle 8.13/JDK17，
`./gradlew`；app 构建带 `-Porchestella.assets.allowMissingPayload=true`（worktree 无
QEMU payload 产物，46ce4e5 的构建期 fail-fast 按其文档显式豁免，仅命令行参数、不改源）。

| 门 | 命令 | 结果 |
| --- | --- | --- |
| 新增分派器契约 | `:app:testDebugUnitTest --tests …WorkerPoolDistccDispatcherTest` | **9/9 绿**（`tests="9" skipped="0" failures="0"`） |
| 装配缝 | 同上 `--tests …AcceleratorLaneAssemblyTest` | **15/15 绿**（原 13 + 新 2） |
| 池回归 | 同上 `--tests …ProtectedWorkerPoolTest / ProtectedWarmPoolTest / DualLaneCoordinatorTest` | 21 + 18 + 30 全绿 |
| app 全量 | `./gradlew :app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true` | **138 类 1128 tests / 0 skipped / 0 failures / 0 errors** |
| fastlane-distcc（PVM-11 回归面） | `./gradlew :runtime:fastlane-distcc:test :runtime:fastlane-distcc:check` | **77 tests / 8 skipped / 0 failures**；8 个 skip 均为既有 POSIX 前置用例（含 `realDistccClientCompilesThroughThePayloadServer`，上轮已在 WSL 真跑闭环），模块源码本轮零改动 |
| instance | `./gradlew :runtime:instance:test :runtime:instance:check` | **44 tests / 0 failures**，`BUILD SUCCESSFUL` |
| CI 模块覆盖 | `python tools/ci/check_ci_module_coverage.py` | `232 passed, 0 failed` |
| 空白 | `git diff --check` | 退出码 0 |

新增测试关键判据（摘）：`openAcquiresALeaseAndCloseReleasesItExactlyOnce`（二次 close 不
双归还）、`poolRefusalBecomesAFixedReasonWithoutOpeningAnything`（DISABLED →
`WORKER_POOL_DISABLED`，opener 零调用、租约零泄漏）、`everyPoolCodeMapsToABridgeShapedReason`
（17 码全过 `[A-Z0-9_]{1,64}`）、`openerFailureReleasesTheLeaseAndRethrowsUnchanged`
（FastLane/IOException 双路）、`pooledDispatcherServesJobsOnLeasedInstancesAndReusesTheWarmOne`
（同实例复用、两轮都打 5103）、`pooledDispatcherRunsUpToCapacityAndQueuesTheRest`
（2 槽 4 并发连接：2 服务 + 2 排队，全开后 2 租出，全关后 2 热）、
`productionOpenerTargetsTheLeasedSessionsDistccPortAndNeverLeaks`（vsock 拒 5103 →
`VSOCK_FAILED` 上抛、槽位回热、同一槽下一作业复用成功）、
`workerPoolGrowthIsBoundedByTheCoordinatorLedger`（双车道占 6/8 vCPU 后：resize(3) 拒
`CPU_EXCEEDED`、resize(2) 接受且零 VM 启动）。

## 7. Benchmark / 实测数据

本票不涉及（零设备，无启动耗时/编译延迟可测）。

## 8. 风险

- **排队中的连接与桥 idle 看门狗竞速**：池队列等待期间连接上无字节流动，看门狗可能在
  租约派发前关连接；后果是租约经 close 正常归还、连接以 `IDLE_TIMEOUT` 计数，无泄漏、
  无死锁。缓解：接线监听起点时保证 queueTimeoutMs < idleTimeoutMs（已写入 javadoc）。
- **app 主源新增 fastlane-distcc 依赖**：类路径进入桥/AXP1 类型。均为纯 JVM 类型，
  无 Android 依赖传递；app 体积影响为这些类的 dex 增量（桥 + axp1 编解码，小）。
- **`WORKER_POOL_*` 固定码进入 `ConnectionStats.failureCode` 面**：归一化形状有测试钉住，
  不引入自由文本。

## 9. 新发现

- `DistccTcpVsockBridge` 的 `LIMIT_REJECTED` 与池的 `POOL_SATURATED` 是两级独立准入
  （连接数上限 vs 队列上限）。接监听起点时建议 `maxConnections` 取 ≥ `2×capacity + 少量`
  或直接让池做唯一准入，避免双重拒绝语义混淆——供 DV-PVM-02b/监听起点票分诊。
- 冷建路径也会打一次 5100（FLN1 HELLO 不只发生在复用前，测试中实测）；与 PVM-06 报告
  「每次复用前一次 HELLO」表述不冲突但更细，记录备查。

## 10. 对后续 ticket 的影响

- **DV-PVM-02b / 监听起点**：分派器即用即接，无剩余适配层；需按 §4 校参（排队 vs idle）
  并补设备窗三判据（多实例并发编译收益、vsock 并发、重建退避表现）。
- **PVM-09 观测**：`WORKER_POOL_*` 失败码天然进桥的 `ConnectionStats`，可并入 logcat
  观测行，无需新埋点面。
- 容量提升（0→N）仍是显式决策（决策地图 §8.4），任何默认变更需预算与内存评审。

## 11. Commit

- 分支：`feat/pvm-06-worker-wiring`（基线 main `b39e8e6`）
- commit：见该分支顶端提交（`feat(pvm): PVM-06 编译 worker 池接线——…`，只 commit 未 push）

## 12. 越界与 Architecture Change Policy 检查

- 未触碰设备、未触碰 `runtime/**` 源码、未改产品启动路径行为（0 容量默认下装配缝构造的
  池与今天可观测等价）、未改任何既有断言或测试期望。
- 新增依赖方向 app → runtime 模块与既有结构一致，未建新 Gradle 模块，未触发
  Architecture Change Policy（无 ADR 边界改动）。

## 13. Ticket 状态

建议：接线部分 **completed**（分派器进设计意图调用路径，契约可验证）；产品监听起点保持
DV-PVM-02b 边界不变。PVM-06 票保持 implemented，Comment 5 记录本轮。

报告人：PVM-06 接线智能体（Lane C）｜ 日期：2026-11-04
