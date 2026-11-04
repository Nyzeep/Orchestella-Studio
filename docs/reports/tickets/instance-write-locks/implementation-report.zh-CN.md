# Ticket instance-write-locks 实施报告（P0-3 残余 + P1-5 并发正确性收口）

- 票号/标题：积压清单 P0-3 残余（三个写路径不持目录锁）+ P1-5（ProtectedWarmPool 持锁跨 VM 冷启动）
- 执行 session / 模型 / thinking：Lane L 并发正确性智能体 / GLM-5.3-Flash / thinking
- 分支：`fix/instance-write-locks`　基线 commit：`9f18c31`

## 1. 任务目标

1. **P0-3 残余**：`InstanceLifecycle.Controller` 的 `delete`、`create`（三个重载）、`exportToArchive` 只持实例级 `synchronized(this)`；app 会创建多个 Controller 共享同一状态目录，实例级锁不互斥目录级写。照 `importFromArchive`（`InstanceStorageManager.java:501`，move-into-place + save 在 `store.stateMonitor()` 内）的既有模式，把三个写路径收口到目录级锁。
2. **P1-5**：`ProtectedWarmPool.acquire()` 在 `ReentrantLock` 内完成整段冷创建（`api.start`、payload 轮询、控制口 HELLO，可长达数十秒）；`state()/stats()/release()` 同锁，冷启动期间全部被阻塞。要求把冷启动移出主锁，保持租约语义（无双重租出、release 幂等、预算/admission 账本约束不变、stats 弱一致可接受），选最小正确方案、不引入新并发原语栈。

## 2. 实际完成

### 2.1 P0-3 残余：三个写路径收口目录级锁 ✅

- **原缺陷**：三个写路径只持 `Controller` 实例监视器。`ConcurrentStartContractTest` 的既有契约明确"每个 Controller 在生产中各自 new Store，不变量不能依赖 Controller 实例锁"；`cloneInstance`/`start`/`exportToDirectory`/`resizeRootVolume` 等在上轮 P0-3 已全部改为 `synchronized (store.stateMonitor())`，仅这三个写路径漏网。对 `Store` 接口的自定义实现（契约注释明确"custom stores rely on the caller for exclusion"），这三个路径的 check-then-write 完全裸奔；对 `FileStore` 而言，`exportToArchive` 的 `store.load` 之后、根卷读取之前的窗口也不受任何目录级互斥保护（并发 delete 可在 load 与卷读取之间清掉根卷）。
- **方案**（`runtime/instance/src/main/java/.../InstanceLifecycle.java`）：
  - `delete(id)` → `synchronized (store.stateMonitor())` 内调 `store.delete(id)`（抽出 `deleteLocked` 私有方法并注释理由，与既有 `xxxLocked` 风格一致）；
  - `create` 三个重载：外层两个保持委托，四参实现在 `synchronized (store.stateMonitor())` 内完成实例构建 + `store.save`；
  - `exportToArchive(id, out)` → `synchronized (store.stateMonitor())` 内 `store.load` + `InstanceStorageManager.exportToArchive`（抽出 `exportToArchiveLocked`，与 `exportToDirectoryLocked` 同构）。
- **不变量保持论证**：
  - 锁对象与已收口路径完全一致（`store.stateMonitor()`），`FileStore` 按规范目录 realPath 共享静态监视器（`STATE_MONITORS`），跨 Controller 实例互斥成立；
  - `synchronized` 可重入：Controller 持有监视器后调用 `FileStore` 内部同监视器方法无死锁，与 `cloneInstance` 等已收口路径同构；
  - 方法可见行为（返回值、异常类型、校验顺序）不变：`delete` 仍原样委托 `store.delete`，`create` 的 `requireNonNull` 校验仍在锁外，`exportToArchive` 的 load→导出顺序不变；
  - 不触碰 `runtime/contract/**` 与任何 forbidden 文件。

### 2.2 P1-5：冷启动移出主锁 ✅

- **原缺陷**：`acquire()` 持主锁贯穿整段冷创建（`api.start` + payload 就绪轮询（默认上限 30s）+ 控制口 HELLO）；`state()/:301`、`stats()/:310`、`release()/:499` 同锁——单个槽冷启动期间，UI/查询线程的 `state()/stats()` 与租约 `release()` 全部阻塞在锁上。
- **方案**（`app/src/main/java/.../ProtectedWarmPool.java`，纯状态机重构，未引入任何新并发原语，仍只用既有 `ReentrantLock` + `Condition` + volatile `closeRequested`）：
  - `acquire()` 重构为"锁内决策 + 锁外执行"：循环在锁内判定槽状态（closed/capacity/LEASED/CREATING/READY/DRAINING），只有走到冷创建分支时先 `beginColdCreate()`（预算 + admission + 置 `CREATING`）再 `break`，在 `finally` 释放主锁后才执行 `createWithRetry(retriesLeft)`；
  - `createAttempt()` 的阻塞调用（`api.start`、`awaitPayloadReady`、`helloCheck`）全程不持主锁；新会话在发布前只是局部变量，`publishLease()` 在锁内一次性 `session=created` + 置 `LEASED` + `signalAll`；失败路径 `settleCreateFailed()` 在锁内置 `FAILED` + `signalAll`；
  - **互斥不再靠锁而是靠槽状态**：整个 create episode（含 HELLO 失败后的一次重试）槽保持 `CREATING`，其他 acquire 在锁内看到 `CREATING` 走有界等待（`awaitSlotChange`），不可能双重创建；
  - `acquireReady()` 改为返回 `ReuseOrReplace(lease, replacementRetries)`：PROCESS_DIED/TTL_EXPIRED 驱逐后替换创建保留 `coldCreateOnce` 的 1 次 HELLO 重试预算，reuse-HELLO 失败后的替换保持单次尝试（0 重试），与旧语义逐路径一致；
  - `shutdown()` 增加 `CREATING` 处理：等待条件从 `currentLease != null` 扩为 `currentLease != null || state == CREATING`；超时仍有在途 create 时返回 `TIMED_OUT` 且**绝不 stop 未发布会话**（创建线程在自己的 `closeRequested` 检查点自行 stop 并 settle），保持"单一 stop owner"语义；
  - 计数器（coldCreates/createMillis/readyMillis/evictions）全部改为锁内短临界区提交（`beginCreateAttempt`/`noteCreateMillis`/`noteReadyMillis`/`recordEvictionUnderLock`），冷启动中途的 stats 观测为弱一致（票面明确可接受）；
  - 防御加固：`createWithRetry` 对意外 `RuntimeException` 也 settle（槽永不卡死在 `CREATING`）；retry 途中 `beginColdCreate` 被预算拒绝时同样 settle + signal，等待者不会睡到超时。
- **不变量保持论证**：
  - **无双重租出**：槽状态迁移全部在主锁内（begin/publish/settle/lease/release），`CREATING` 是排他所有权标记，第二个 acquire 只能有界等待后复用或超时；回归测试断言冷启动全程 `api.start` 恰好进入一次；
  - **release 幂等**：`release()` 本体未改（`lease == currentLease && !released` 判定 + `released` 标记），且现在冷启动期间可立即返回（对非当前租约返回 false）；
  - **预算/admission 账本约束不变**：`beginColdCreate()` 在主锁内先查 `memoryMb > poolMemoryLimitMb` 与 `admission.admit(memoryMb)`，每次尝试（含 HELLO 重试）都重查，与旧 `coldCreate()` 逐次重查一致；预算拒绝置 `FAILED` 并传播 `BUDGET_REJECTED`；
  - **单 stop owner**：`stopSession()` 仍是对 `FastLaneSession.stopRuntime` 的唯一调用封装（其内部自带 stopLock 幂等）；shutdown 超时路径不触碰未发布会话，在途创建线程自认领 stop；
  - 既有 17 个 `ProtectedWarmPoolTest` 用例零改动全部通过，覆盖 DISABLED/BUDGET_REJECTED/HELLO 重试/PROCESS_DIED/TTL/DRAIN_TIMEOUT/STOP_UNCONFIRMED 全部分支。

## 3. 未完成 / 与预期偏差

- 无未完成项。一处说明：工单给出的 app 基线 1167 tests 在本 worktree（9f18c31）实测为 1129（0 失败），差异为基线快照/环境所致；本票在此实测基线上 +4（新增 app 并发回归用例），实测 1133。数字均可由 JUnit XML 复算。

## 4. 修改的模块

| 文件 | 变更 | 边界 |
|---|---|---|
| `runtime/instance/src/main/java/.../InstanceLifecycle.java` | delete/create/exportToArchive 收口目录级锁（owned） | ✅ 独占 |
| `runtime/instance/src/test/java/.../InstanceLifecycleWriteLockContractTest.java` | 新增并发回归测试（owned） | ✅ 独占 |
| `app/src/main/java/.../ProtectedWarmPool.java` | 冷启动移出主锁状态机重构（owned） | ✅ 独占 |
| `app/src/test/java/.../ProtectedWarmPoolColdStartConcurrencyTest.java` | 新增并发回归测试（owned） | ✅ 独占 |
| `docs/reports/tickets/instance-write-locks/implementation-report.zh-CN.md` | 本报告（git add -f） | 报告 |

未触碰：`runtime/contract/**`、`.github/workflows/**`、QemuRuntimeBootstrapper、InstanceStrategyLauncher、RuntimeProcessSupervisor、BridgeConnector、SettingsManager、MobileAuxiliaryKeyBar、各 `build.gradle.kts`。越界检查：无。

## 5. 重要设计决策

1. **P0-3 采用既有 `xxxLocked` + `stateMonitor()` 模式而非新锁**：与上轮 P0-3 收口的 14 个方法保持同构，锁对象唯一（`store.stateMonitor()`），不引入第二把锁避免锁序问题。
2. **P1-5 选"锁内决策 + 锁外执行"而非异步化/每槽锁线程池**：`ReentrantLock` 可重入意味着"在 acquire 的锁内调用内部 lock/unlock"无法真正放锁（外层持有计数不归零），因此冷创建 episode 在结构上必须位于 acquire 外层持有之外——这是本重构的核心正确性要点；异步化需要引入执行器与回调生命周期，违背"最小正确方案"约束。
3. **CREATING 作为排他所有权标记**：把互斥从"持锁"迁移到"槽状态"，episode 含重试全程保持 `CREATING`，避免旧代码"重试间隙置 FAILED → 其他 acquire 抢占 → 双重创建"的新窗口。
4. **retry 预算按驱逐原因分流**：PROCESS_DIED/TTL 驱逐 → 1 次重试；reuse-HELLO 失败 → 0 次重试。若统一为 0 会改变 `reuseHelloFailureGetsOneColdCreateThenReports` 等既有契约（已被测试捕获后修正）。

## 6. 测试

### 6.1 P0-3：`InstanceLifecycleWriteLockContractTest`（新增 8 用例）

- **确定性契约（可复现于旧代码失败）**：
  - `createOverloadsCallStoreSaveUnderTheDirectoryStateMonitor` / `deleteCallsStoreDeleteUnderTheDirectoryStateMonitor` / `exportToArchiveCallsStoreLoadUnderTheDirectoryStateMonitor`：instrumented Store 断言 `Thread.holdsLock(stateMonitor())`——三个写路径必须在目录监视器内到达 store 方法；
  - `twoControllersCannotCreate/Delete/ExportToArchiveConcurrently`：双 Controller + barrier probe（`ConcurrentStartContractTest` 同款技术），断言两线程从未在 probe 点相遇（即被目录监视器串行化）。
- **真实 FileStore 竞争压测**：`concurrentCreatesAcrossControllersProduceDistinctLoadableInstances`（8 线程 × 5 create+export，断言 40 实例、id 全唯一、状态 CREATED）；`createExportDeleteHammerKeepsTheStoreConsistent`（8 线程 × 6 create→export→delete 全周期，断言零异常、账目守恒 created==deleted、终态目录零残留零 .tmp）。
- **阴性验证（证明测试捕获原缺陷）**：把 `InstanceLifecycle.java` 临时回退到基线实现运行本测试类 → 恰好 3 个 holdsLock 契约用例失败（"create must call store.save while holding the directory state monitor" 等），恢复修复后全绿。

### 6.2 P1-5：`ProtectedWarmPoolColdStartConcurrencyTest`（新增 4 用例）

- `coldStartDoesNotBlockStateStatsAndRelease`：`api.start` 阻塞在 latch 上期间，`state()` 返回 `CREATING`、`stats().coldCreates()==1`、`release(null)==false` 三个探针各在 2s 内完成（持锁旧实现下探针挂死）；零预算第二 acquire 立即 `WAIT_TIMEOUT` 且 `api.start` 恰进入一次（无双重创建）；latch 释放后 creator 正常拿到租约。
- `secondAcquireWaitsForTheColdCreateThenReusesWithoutSecondCreate`：maxWaitMs=10s 的第二 acquire 等待在 `CREATING` 上（150ms 后仍存活、start 仍只有一次），创建完成并 release 后 waiter 复用同一实例（instanceId 相同、`reuses()==1`、`api.start` 全程仅一次）。
- `failedColdCreateSettlesFailedAndWakesTheWaitingAcquire`：在途创建失败置 `FAILED` 后，等待中的 acquire 被唤醒并发起自己的创建（两线程均 `CREATE_FAILED` 收场、无挂死）。
- `shutdownWhileColdStartInFlightNeverTouchesTheUnpublishedVm`：冷启动在途时 `shutdown(50ms)` 返回 `TIMED_OUT` 且 `stopped==0`（未触碰未发布会话）；创建线程随后自行 stop 并 settle `FAILED`。
- **阴性验证**：把 `ProtectedWarmPool.java` 临时回退到基线实现运行本测试类 → 探针失败"probe did not finish within 2000ms; the call is blocked"，恢复修复后全绿。
- 既有 `ProtectedWarmPoolTest`（17 用例）、`ProtectedWorkerPoolTest`、`DualLaneCoordinatorTest`、`AcceleratorLaneAssemblyTest` 全部零改动通过。

### 6.3 汇总计数（真实输出）

| 命令 | 结果 |
|---|---|
| `./gradlew :runtime:instance:check` | BUILD SUCCESSFUL；JUnit **52 tests, 0 failures**（基线 44 + 新增 8）；JavaExec 合同主进程全部 OK（INSTANCE_LIFECYCLE_DELETE_TEST_OK 等） |
| `./gradlew :app:testDebugUnitTest -Porchestella.assets.allowMissingPayload=true` | BUILD SUCCESSFUL；**1133 tests, 0 skipped, 0 failures, 0 errors** |
| 同上（stash 后基线 9f18c31 实测） | **1129 tests, 0 failures** → 本票 +4（新增 app 并发回归） |
| 阴性验证（回退 InstanceLifecycle.java） | `InstanceLifecycleWriteLockContractTest` 3 个契约用例失败（预期） |
| 阴性验证（回退 ProtectedWarmPool.java） | `ProtectedWarmPoolColdStartConcurrencyTest` 探针阻塞失败（预期） |

## 7. Benchmark / 实测数据

本票不涉及（无启动耗时/延迟指标要求；冷启动期间查询不再阻塞属行为正确性，未做定量基准）。

## 8. 风险

1. **stats 中途观测语义变化（弱一致）**：冷启动期间 `stats()` 现在可见 `CREATING` 与尚未提交的计数——票面明确可接受；计数提交点全部在锁内短临界区，无撕裂读。
2. **`specFactory`/`api.start` 首次脱离池锁并发执行**：`FastLaneSession.stopRuntime` 内部自带 stopLock 幂等保护，且冷启动在途时池内其他路径不触碰该未发布会话（shutdown 有 CREATING 分支、evictWarm/warmSessionIfReady 只认 READY），并发暴露面已收窄到平台 API 自身；测试覆盖了 shutdown/release/waiter 与在途创建并发交错的用例。
3. **`CREATING` 卡死风险（新引入形态）**：若 create 线程在 episode 中被 `Error` 级异常击穿，槽可能滞留 `CREATING`（旧实现持锁同样会滞留且更糟——锁也一并卡死）。已对 `PoolException`/`RuntimeException` 全路径 settle；`Error` 不设防，与旧实现同暴露面。

## 9. 新发现

- 无阻塞项。一个可复用模式沉淀：`Thread.holdsLock(monitor)` instrumented Store 是验证"写路径持目录锁"契约的最小确定性手段，可直接供后续 store 类票据复用（已在本票测试中实现为 `MonitorAssertingStore`）。

## 10. 对后续 ticket 的影响

- 后续若有新 Controller 写路径，必须照 `xxxLocked` + `store.stateMonitor()` 模式收口，`InstanceLifecycleWriteLockContractTest` 的 holdsLock 用例可直接扩展。
- 若 PVM 线后续把 warm pool 扩到多槽（capacity > 1），本票的"槽状态排他 + 锁内决策/锁外执行"骨架可直接按槽复制，`beginCreateAttempt` 的 `instanceSequence` 已在锁内单调分配。
- `ProtectedWarmPool` 行为对外可见变化仅两处：冷启动期间 `state()==CREATING` 可被观测、`shutdown()` 在在途创建下可能 `TIMED_OUT`（原实现表现为锁竞争超时，同样 `TIMED_OUT`）。

## 11. Commit

- `fix(instance): 三个写路径收口目录级锁——delete/create/exportToArchive 照 stateMonitor 模式`（P0-3 残余 + 8 个运行时并发回归测试）
- `fix(app): ProtectedWarmPool 冷启动移出主锁——CREATING 排他 + 锁内决策锁外执行`（P1-5 + 4 个 app 并发回归测试 + 实施报告）
- 分支：`fix/instance-write-locks`（仅 commit，不 push）

## 12. Ticket 状态

**completed**。两项收口均实现、均有确定性并发回归测试与旧代码阴性验证，全量验证绿（runtime 52/0、app 1133/0），未越界、未触发 Architecture Change Policy（两处均为既有模式补齐与既有类内部状态机重构，无 ADR 边界变更）。

报告人：Lane L / GLM-5.3-Flash ｜ 日期：2026-11-05 ｜ 耗时：单 session
