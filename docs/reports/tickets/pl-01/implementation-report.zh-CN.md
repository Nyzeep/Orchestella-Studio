# PL-01 protected 车道生命周期与预热复用 实施报告

- 日期：2026-10-23
- Base：`main @ 4765d4b`
- Branch：`feat/accelerator-batch1`
- 工单：`.scratch/orchestella-accelerator/issues/PL-01-protected车道生命周期与预热复用.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM。全部验证在 JVM 与 fake `FastLaneVirtualMachineApi` 上完成。

## 1. 结论

零设备范围内**完成**。交付一个有界 protected 预热池：默认容量 0（关闭），显式 `capacity=1` 才启用；复用前必做一次 control `HELLO/HELLO_OK`，失败丢弃并冷建一次，二次失败上报 `HELLO_FAILED`；所有状态、耗时与回收原因都有固定 code。

没有把 `HELLO_OK` 当作 terminal 可用。DV-01A 的 terminal blocked 结论未改变，池只覆盖 control/vsock/stop。

## 2. 现状与代码路径

- protected 车道由 `runtime/engine-fastlane` 描述：`FastLaneLaunchSpec` 明确是 “protected Microdroid app-payload launch”（`FastLaneLaunchSpec.java:7`），`FastLaneSession` 持有唯一 VM handle 与 owner stop（`FastLaneSession.java:181`）。
- DV-01A 的 control HELLO 目前只存在于设备 instrumentation：`FastLaneDv01Probe.controlHandshake`（`app/src/androidTest/.../FastLaneDv01Probe.java:1126`）。它不可从 app main 复用，因此池内用同一个 FLN1 codec（`Fln1TerminalCodec` + `Fln1Payloads.Hello/HelloOk`）做一次 `HELLO/HELLO_OK`，不复制 wire 定义。
- 当前没有池：每个探针周期都是 create → run → payloadReady → preflight → stop → delete；仓库里没有 `WarmPool` 实现（只有本工单）。
- `RuntimeProcessSupervisor` 是 QEMU 专用 stop owner：构造函数要求 `QemuProcessLauncher.ProcessHandle` + `RuntimeGateway`（`RuntimeProcessSupervisor.java:23-85`），无法持有 Fast Lane/Microdroid handle。protected 车道已有的唯一 stop owner 是 `FastLaneSession.stopRuntime`（`FastLaneSession.java:181`）。

## 3. 设计

### 3.1 状态机

`ProtectedWarmPool.State`：`IDLE` / `CREATING` / `READY` / `LEASED` / `DRAINING` / `FAILED`（`ProtectedWarmPool.java:73`）。

| 当前 | 事件 | 下一页 |
| --- | --- | --- |
| IDLE | acquire | CREATING |
| CREATING | payload ready + HELLO_OK | LEASED |
| CREATING | create/ready/hello 失败 | FAILED（或重试一次） |
| READY | acquire + 健康检查通过 | LEASED，`reuses+1` |
| READY | TTL 过期 / 进程死亡 / HELLO 失败 | 丢弃后 CREATING 或 FAILED |
| LEASED | release | READY（进程仍活）或 IDLE |
| LEASED | shutdown 超时 | FAILED |
| 任意 | stop 未确认 | FAILED + `STOP_UNCONFIRMED` |

acquire 与 release 由同一个 `ReentrantLock` 串行化；租约等待用 `Condition`，不轮询。

### 3.2 租约、TTL 与等待

- 单池容量只允许 0 或 1；默认 0。容量 0 时 `acquire` 返回 `DISABLED`，不会偷偷冷建。
- 空闲 TTL 默认 120 s，惰性判定：在 acquire/close 时检查 `readyAtMillis`，不引入后台 reaper 线程。
- `maxWaitMs` 默认 0：另一个 lease 未归还时立即 `WAIT_TIMEOUT`；非零时最多等待该预算，lease 释放会 signal。
- 重复 `release` 是幂等 no-op，返回 `false`，不会二次 stop 或回收。

### 3.3 预算与 AX-01 边界

| 项 | 本票行为 |
| --- | --- |
| 单 VM 内存 | `Config.memoryMb`，默认 1024 MiB |
| 池内存上限 | `Config.poolMemoryLimitMb`；小于单 VM 时 acquire 直接 `BUDGET_REJECTED`，不 create |
| 与 QEMU 并存 | `Admission` 函数式接口（`ProtectedWarmPool.java:139`）；AX-01 实现真正的双车道预算 |
| 默认准入 | `Admission.allowAll()`，仅作为未接入 AX-01 时的安全默认；没有重复实现 AX-01 的配额逻辑 |

### 3.4 acquire 健康检查

`helloCheck`（`ProtectedWarmPool.java:573`）每次 acquire 都执行：

1. 通过 `FastLaneSession.channel(controlPort)` 打开 control 通道；
2. 发送一帧 FLN1 `HELLO(min=1,max=1,token="")`，读取一帧；
3. 只接受 `HELLO_OK` 且版本为 1；超时默认 2 s；
4. 无论成功失败都关闭该 control channel，不缓存。

失败路径：丢弃当前 VM（`FastLaneSession.stopRuntime`），计数 `HELLO_FAILED`；如果是复用失败，只冷建一次；冷建后的 HELLO 再失败直接抛 `HELLO_FAILED`，不再无限重试。

### 3.5 停止路径

- 池不直接调用平台 stop，也不新增 stop owner；统一走 `FastLaneSession.stopRuntime(graceMs)`。
- `shutdown(timeoutMs)` 先置 `closeRequested` 并进入 `DRAINING`；有未归还 lease 时最多等 `timeoutMs`，超时则停 VM、计 `DRAIN_TIMEOUT`、返回 `TIMED_OUT`。若关闭时正有 create 在锁内进行，shutdown 不等待锁，而是在 create 边界检查 `closeRequested`，停止新 VM 后以 `CLOSED` 结束该 acquire。
- stop 返回 `UNCONFIRMED` 时池进入 `FAILED`，计 `STOP_UNCONFIRMED`，不伪装成已停止。

### 3.6 计数

`Stats` 只有数字与固定 `Code`：`cold_creates`、`reuses`、`create_ms`、`ready_ms`、`evictions`、`evict_reasons`。`create_ms`/`ready_ms` 是本池生命周期内的累计值，不是直方图。不记录路径、token、argv、payload 或原始日志。

## 4. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `app/src/main/java/io/github/nyzeep/orchestella/ProtectedWarmPool.java` | 新增 | 状态机、租约、TTL、预算、健康检查、计数、drain |
| `app/src/test/java/io/github/nyzeep/orchestella/ProtectedWarmPoolTest.java` | 新增 | 18 个 JVM 测试 + fake API/control codec + 可推进时钟 |
| `app/build.gradle.kts` | 修改 | app main 增加 `:runtime:fastlane-terminal` 依赖（纯 JVM，无 Android 依赖） |

没有修改 `runtime/engine-avf`、`FastLaneSession`、FLN1 wire、ADR、版本号或 applicationId。

## 5. 测试矩阵

| # | 测试 | 覆盖 |
| --- | --- | --- |
| 1 | `defaultCapacityIsZeroAndAcquireIsDisabled` | 默认关闭，不 create |
| 2 | `releaseThenAcquireReusesTheSameSession` | 复用、reuses/cold_creates 计数 |
| 3 | `idleLeasePastTtlIsEvictedAndReplaced` | TTL（fake clock） |
| 4 | `poolMemoryLimitRejectsBeforeCreating` | 池内存上限 |
| 5 | `sharedLaneAdmissionCanRejectThePool` | AX-01 准入接口 |
| 6 | `helloFailureEvictsAndColdCreatesOnce` | 首次冷建 HELLO 失败后一次替换 |
| 7 | `secondHelloFailureIsReportedAndStopsThePool` | 二次失败上报 `HELLO_FAILED` |
| 8 | `reuseHelloFailureGetsOneColdCreateThenReports` | 复用失败只换一次，不二次重试 |
| 9 | `deadWarmSessionIsEvictedAndReplaced` | 进程死亡恢复 |
| 10 | `duplicateReleaseIsIdempotent` | 双 release |
| 11 | `shutdownWithOutstandingLeaseIsBoundedAndStopsTheVm` | drain 超时 |
| 12 | `unconfirmedStopLeavesThePoolFailed` | stop 未确认 |
| 13 | `acquireWaitsBoundedWhenAnotherLeaseIsOut` | max wait |
| 14 | `payloadReadyTimeoutStopsTheAttempt` | `READY_TIMEOUT` |
| 15 | `helloHandshakeTimeoutIsBounded` | HELLO 超时 |
| 16 | `runtimeFailureDuringCreateIsCreateFailed` | `CREATE_FAILED` |
| 17 | `shutdownDuringCreateIsBoundedAndStopsTheNewVm` | create 进行中关闭：有界返回、停止新 VM |
| 18 | `statsCarryOnlyCountersAndFixedCodes` | 计数面不含 payload/路径 |

全部使用 fake API/内存流/可推进时钟，没有网络、真实 sleep 依赖或设备。

## 6. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:engine-avf:test :app:testDebugUnitTest --tests '*WarmPool*'` | BUILD SUCCESSFUL；ProtectedWarmPoolTest 18/18 通过；engine-avf 的 20 个 JavaExec harness 全部通过 |
| `python tools/ci/check_ci_module_coverage.py` | 58 passed, 0 failed |
| `./gradlew :app:testDebugUnitTest`（全量回归） | 116 个测试类、893 tests、0 failures、0 errors |
| `python -m pytest tools/fastlane-device -q` | 67 passed, 7 subtests passed |

## 7. 偏差

1. **stop owner**：工单要求复用 `RuntimeProcessSupervisor`，但该类只接受 QEMU 的 `ProcessHandle`/`RuntimeGateway`，不能拥有 protected Microdroid handle。实现改为复用该车道已有的唯一 owner stop `FastLaneSession.stopRuntime`，没有新增第二套 stop 机制。这是对文字要求的偏离，需要维护者确认。
2. **测试落点**：池与测试放在 `app`（工单要求“app 进程内维护”），因此 `engine-avf:test` 是回归门而不是池测试门；池测试由 `--tests '*WarmPool*'` 覆盖。
3. **开关形态**：显式开关是 `Config.capacity`，没有新增 Settings/UI 或 SharedPreferences 键；当前没有生产调用点，接 UI 会在 AX-01 双车道落地时一起做。
4. **TTL 惰性**：没有后台清理线程；TTL 在下一次 acquire/close 时生效。容量 1 下不会积累过期 VM。
5. **耗时口径**：`create_ms`/`ready_ms` 为累计值，不是分位数。

## 8. 风险与未验证

- **not-tested**：真机预热收益、DV-ACC 的 create/ready 分布、真实 pvmfw 校验耗时。全部真机项后置到 DV-ACC。
- **not-tested**：池接入真实 `FastLaneVirtualMachineApi` 的 binder 路径；本票只验证 fake 边界。
- `HELLO_OK` 只证明 control 面活着；terminal 仍按 DV-01A blocked 处理，`capabilities` 不因池而升级。
- 容量只支持 0/1；多实例池需要新的预算与实例 ID 策略，不在本票。
- stop 未确认时池停在 FAILED，下一次 acquire 会重新尝试冷建；若平台持续不收敛，会反复 create，需要 AX-01 的熔断策略。

## 9. 待维护者决策

1. 是否接受“复用 `FastLaneSession.stopRuntime` 而不是 `RuntimeProcessSupervisor`”的 stop owner 结论。
2. 是否要求在本票内把池接到生产 launch 路径，还是等 AX-01 后再接。
3. `FAILED` 是否应终止后续 acquire（当前允许重试）。
4. 默认 `idleTtlMs=120s`、`maxWaitMs=0` 是否符合产品预期。
5. 是否需要在 `SettingsManager` 暴露显式开关（当前只有 Config）。
