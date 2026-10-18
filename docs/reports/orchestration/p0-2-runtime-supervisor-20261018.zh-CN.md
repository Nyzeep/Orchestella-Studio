# P0-2 QEMU / gvproxy 运行时生命周期收敛（2026-10-18）

## 结论

P0-2 的运行时所有权已收敛到单一 `RuntimeProcessSupervisor`：

- QEMU 进程、gvproxy、control / terminal / display / proxy socket 由同一个 supervisor 拥有；
- `stop(graceMs)` 有界、幂等、线程安全，返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`；
- 只有确认停止后才写 STOPPED、才允许同端口重启；
- 未确认停止时保持 RUNNING、保留 session owner 与根盘占用，并记录结构化诊断；
- session 池替换/注销、启动失败清理、详情页 60 秒超时都不再绕过 supervisor。

**验证范围**：纯 JVM fake、真实宿主 OS 子进程、项目单测。**真机未测**（当前环境无设备），20 次连续启停、真实 QEMU/gvproxy 固定端口重启标记为 `not-tested`。

## 基线

- 基线：`origin/main 12f5fc2`（2026-10-18），分支 `fix/p0-2-runtime-supervisor`。
- 基线测试：`:runtime:contract:check :app:testDebugUnitTest`，98 suites / 747 tests / 0 failures。
- 环境：Temurin JDK 17、Android SDK Platform 36、Build Tools 36.1.0、NDK 27.0.12077973、Gradle 8.13。环境内 Maven Central 返回 429，使用 Aliyun 镜像（Gradle init script + `-Porchestella.repositories.useAliyunMirror=true`），未改仓库的 settings 仓库策略。
- 基线首次接入 `QemuProcessLauncherTest` 时发现一个**既有**契约缺口：测试期望 `launch(null)` 抛 `IllegalArgumentException`，实现却抛 `NullPointerException`。本轮把 seam 修正为抛 `IllegalArgumentException`，测试断言未放宽。

## 改动

### 1. 进程 seam（T1）

`QemuProcessLauncher.ProcessHandle` 新增：

- `waitFor(long timeout, TimeUnit unit)`
- `destroyForcibly()`
- `isAlive()`
- `default long pid()`（-1 表示不可用）

真实 `ProcessBuilder` 实现直接映射 `java.lang.Process`；现有 fake 全部补齐。新增 JUnit `QemuProcessHandleSeamTest`，并把 `qemuProcessLauncherTest` 接入 `:runtime:engine-qemu:check`。

### 2. gvproxy（T2）

`NetworkGatewayDaemon.Running` 实现 `RuntimeGateway`：

- `isAlive()`
- `stop(long graceMs)` / `stop()`
- 顺序：`destroy → waitFor(grace) → destroyForcibly → waitFor(force)`
- 只有确认退出后才 `LIVE.remove` 并删除 API socket；未确认时保留 LIVE，阻止同端口新网关
- `stop` 幂等；重复调用返回同一确认结果，不删除新持有者的 socket

`start()`：

- 在 `START_LOCK` 内串行
- 同实例已有 LIVE：存活快速失败；已死先清理
- 启动前检查固定端口；已被占用则报“旧网关未退出/端口被占用”，不再把 `portBound` 当作 readiness
- 不同 live 实例不得共享同一 gvproxy 端口
- 启动失败用同一有界 terminate helper 清理进程、API socket 与 LIVE

### 3. Supervisor（T3）

新增纯 Java `RuntimeProcessSupervisor`：

- 拥有 `ProcessHandle`、`RuntimeGateway`、channel closer
- `stop(graceMs)` 在单一 monitor 内执行；并发调用者阻塞并得到同一结果
- 阶段超时/失败返回 `UNCONFIRMED`；QEMU 未确认退出时不停止 gateway
- `Diagnostics` 记录 stage、pid、graceMs、forced、exitCode（不含 token/口令/按键/用户文件）
- `Session` 实现 `RuntimeOwnedSession`；`close()` = “退休并停止全部资源”，`terminate()` 同样走 supervisor；`UNCONFIRMED` 时 terminate 抛 `IOException`

### 4. 退出顺序与元数据（T4）

- QEMU watcher 只调用 `session.confirmRuntimeStopped()`，不再直接 stop gateway。
- `InstanceLaunchSequence.awaitGuestExit` 在 `waitFor` 后调用同一个 supervisor stop。
- 只有 `CONFIRMED` / `ALREADY_STOPPED` 才 `completeExit` 并写 STOPPED/CRASHED。
- `UNCONFIRMED` 返回 `ExitStatus.STOP_UNCONFIRMED`，保留 RUNNING、registry 与 guard。
- 详情页 60 秒超时文案改为“停止未确认：虚拟机在 60 秒内未退出；实例仍保留，请查看运行日志”；不标记 STOPPED、不重启。
- MainActivity 对 `STOP_UNCONFIRMED` 使用独立文案。

### 5. Session 池与启动失败（T5）

- `PersistentTerminalSessionPool.register` 替换旧 holder 前先停止旧 runtime；旧 stop 未确认时抛 `IOException`，不发布新 holder。
- `unregister(id)` / `unregister(id, expected)` 先停止 owned runtime；未确认时保留 holder。
- `clear()` 停止所有 owned runtime。
- `ExecutionSessionRegistry.register` 传递 checked failure。
- `QemuInstanceLauncher.cleanupFailedLaunch` 统一处理 bootstrap、Session 构造、注册失败：有 session 走 supervisor；无 session 则有界停 QEMU + gateway。

### 6. 文档

- 新增 ADR-0020（运行时进程所有权与有界停止）。
- `CONTEXT.md` 新增 Runtime Process Supervisor / Stop Confirmation / STOP_UNCONFIRMED。
- 新增 `.scratch/p0-2-runtime-supervisor/spec.md` 与 6 张 tracer-bullet 票。

## 调用链

### Before

```text
launch:
  gvproxy.start (local Running)
  QEMU start
  watcher: handle.waitFor → Virgl.cleanup → gateway.stop (destroy only)
  Session.handle (no gateway)

stop:
  UI → Session.terminate → handle.destroy
  Session.close → close channels only
  unregister/register → Session.close (QEMU may stay alive)

exit:
  awaitGuestExit → session.waitFor → completeExit → STOPPED
  watcher → gateway.stop
  ^ two independent waiters; STOPPED can precede gateway release
```

### After

```text
launch:
  gateway = gvproxy.start
  QEMU start
  Session(handle, gateway, supervisor)
  watcher: handle.waitFor → Virgl.cleanup → session.confirmRuntimeStopped()

stop:
  any caller → Session.stopRuntime
    → supervisor.stop(graceMs)
       1 process.destroy
       2 process.waitFor(grace)
       3 process.destroyForcibly
       4 process.waitFor(force)
       5 closeChannels once
       6 gateway.stop(grace)
    → CONFIRMED | UNCONFIRMED | ALREADY_STOPPED

exit:
  awaitGuestExit → session.waitFor → session.stopRuntime
    → only CONFIRMED/ALREADY_STOPPED: completeExit → STOPPED/CRASHED
    → UNCONFIRMED: STOP_UNCONFIRMED, keep RUNNING + owner + guard
```

## 测试

### 命令

```bash
# 基线
./gradlew :runtime:contract:check :app:testDebugUnitTest --no-daemon

# 最终
./gradlew :runtime:contract:check :runtime:instance:check \
  :runtime:engine-qemu:check :app:testDebugUnitTest :app:lintDebug \
  --no-daemon -Porchestella.repositories.useAliyunMirror=true

git diff --check
```

### 结果

| 任务 | 结果 |
| --- | --- |
| `:runtime:contract:check` | 通过，3 suites / 24 tests |
| `:runtime:instance:check` | 通过，3 suites / 6 tests |
| `:runtime:engine-qemu:check` | 通过，1 suite / 2 tests（新增 JUnit）+ 既有 main-based 任务 |
| `:app:testDebugUnitTest` | 通过，100 suites / 764 tests / 0 failures / 0 errors / 0 skipped |
| `:app:lintDebug` | 通过，0 Error/Fatal，753 Warning |
| `git diff --check` | 通过 |

基线 98 suites / 747 tests → 最终 100 suites / 764 tests（+17）。新增用例全部为纯 JVM/宿主进程测试。

### 测试矩阵

| 要求 | 用例 |
| --- | --- |
| fake 忽略 destroy，grace 后强制收敛 | `RuntimeProcessSupervisorTest.processThatIgnoresDestroyIsForcedAfterGrace` |
| `waitFor(timeout)` 不无限阻塞 | `QemuProcessHandleSeamTest.realProcessExposesBoundedWaitForcibleStopAndPid`、`processThatIgnoresDestroyIsForcedAfterGrace` |
| gateway stop 等待退出且幂等 | `NetworkGatewayDaemonTest.gatewayStopWaitsForExitThenRemovesLiveEntry` |
| 旧网关存活阻止新 start；退出后可复用 | `NetworkGatewayDaemonTest.liveGatewayBlocksSlotAndDeadGatewayIsCleaned` |
| 启动失败清理 | `QemuLaunchCleanupTest.failedLaunchWithoutSessionStopsProcessAndGateway`、`failedLaunchWithSessionUsesTheSupervisor`、`NetworkGatewayDaemonTest.boundedTerminationForcesAndIsIdempotent` |
| 并发 stop 只执行一次 | `RuntimeProcessSupervisorTest.concurrentStopRunsOnceAndBothCallersSeeTheSameResult` |
| 池替换/注销/clear 停止旧资源 | `PersistentTerminalSessionPoolTest.registerReplacementStopsPreviousRuntimeBeforePublishingNewOwner`、`registerRefusesReplacementWhenPreviousStopIsUnconfirmed`、`unregisterStopsOwnedRuntimeAndKeepsUnconfirmedOwner`、`clearStopsEveryConfirmedRuntimeOwner` |
| 未确认 stop 不写 STOPPED | `InstanceLaunchSequenceTest.unconfirmedStopKeepsRunningMetadataOwnerAndGuard` |
| 既有回归 | 全量 app / contract / instance / engine-qemu 套件 |

## 证据边界

- **fake 进程**：supervisor 的停止顺序、超时、幂等、并发、gateway 失败、通道关闭。
- **真实宿主进程**：engine-qemu seam 用 `/bin/sh sleep` 验证 `waitFor(timeout)`、`destroyForcibly`、`pid`；gateway handle 用忽略 SIGTERM 的 `/bin/sh` 验证强制收敛与 LIVE 释放。它们证明 Java 进程语义，不等同于 Android 上的 QEMU/gvproxy。
- **未验证**：真实 QEMU + gvproxy 在设备上的停止时序、固定端口真实绑定/释放、Android `Process.destroyForcibly`/OEM 后台限制、20 次连续启停、真实 UI 超时路径。

## 未运行 / 需真机

- 同一 QA 实例连续启停 20 次、孤儿进程计数：`not-tested`（无设备）。
- 强制停止后真实固定端口立即重启：`not-tested`（无设备；JVM 只验证了槽位与进程退出）。
- 详情页真实 60 秒等待与 UI 明示：`not-tested`（无设备/无 ActivityScenario）。
- `ProcessHandle.pid()` 在 Android 上返回 -1（android.jar 的 `java.lang.Process` 不暴露 `pid()`）；真机诊断只有 stage/forced/grace。

## 剩余风险

1. **端口误判**：启动前的 `portBound` 探测会把任何占用固定端口的进程都视为旧网关；会快速失败，但不会自动重试。
2. **中断保守性**：gateway stop 在等待阶段被中断时可能返回未确认，即使进程随后已死；LIVE 条目会保留到下一次 start 清理。
3. **通道关闭耗时**：`closeChannels` 会为每个 service forward 调用 gvproxy API（单次 1.5s 超时）；大量转发时可能超出 grace，但仍有界。
4. **两个观察者**：watcher 与 `awaitGuestExit` 仍都等待进程，但都通过幂等 supervisor stop，不再是两个 stop owner。后续可合并为一个观察者。
5. **UI 超时策略**：当前按用户确认的方案只显示“停止未确认”并保留实例，不自动升级强杀。
6. **未确认时的 owner 保留**：`clear()` 遇到未确认 holder 会保留；这是有意的盘/端口保护，但会阻止同一进程内再次注册该实例。

## 提交

分支：`fix/p0-2-runtime-supervisor`

- `bc63314` docs(runtime): 定义运行时进程所有权与有界停止
- `7c6ac23` feat(engine-qemu): 增加有界进程停止 seam
- `efaa981` feat(network): gvproxy 有界停止与固定端口槽位检查
- `e4dd536` feat(runtime): 引入 RuntimeProcessSupervisor 统一运行时所有权
- `99020e6` feat(lifecycle): 停止确认后才写 STOPPED，未确认保留运行态
- `5661049` feat(session): 池替换与注销先停止旧运行时
- 本轮报告与 T6 清理提交见分支最新 HEAD

未创建 tag、Release，未上传 APK，未修改签名材料；未自行合并。
