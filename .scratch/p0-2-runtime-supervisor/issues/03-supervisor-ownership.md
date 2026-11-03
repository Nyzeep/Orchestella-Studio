# T3 RuntimeProcessSupervisor 所有权

Status: resolved
Type: task
Blocked by: T1, T2

## 交付

新增 `RuntimeProcessSupervisor`（纯 Java，不依赖 Android）：

- 拥有 QEMU `ProcessHandle`、gateway handle、channel closer。
- `stop(long graceMs)`：幂等、线程安全、有界；返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`。
- 任一步超时/失败：`UNCONFIRMED`，保留诊断（stage、pid、graceMs、forced、exitCode）。
- `Session` 持有 supervisor；`Session.close()` 与 `terminate()` 都走它。
- `Session.stopRuntime(graceMs)` 与 `isRuntimeAlive()` 通过 `RuntimeOwnedSession` 暴露给池和退出序列。
- QEMU watcher 不再直接 stop gateway，只调用 supervisor。

## 验收

- 两个线程同时 stop 只终止一次，结果一致。
- fake 进程忽略 destroy 后 stop 在 grace 后强制收敛。
- fake 进程忽略 destroyForcibly 时返回 UNCONFIRMED，不调用 gateway stop。
- 通道只关闭一次，gateway 只 stop 一次。
- `Session.close()` 会停止 QEMU 与 gvproxy。

## 测试

- 新增 `RuntimeProcessSupervisorTest`。
- 更新 `ControlChannelRecoveryTest` 的 fake handle。
- `./gradlew :app:testDebugUnitTest --tests '*RuntimeProcessSupervisorTest' --tests '*ControlChannelRecoveryTest'`

## Comments

2026-11-03 协调：本轮做 tracker 对账，逐条核对代码后回填（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T3）。

2026-11-03 协调：交付项逐条核对。
- 类本体 `app/src/main/java/io/github/nyzeep/orchestella/RuntimeProcessSupervisor.java`：`StopResult` 三态 `:26`；三个所有权字段 `:55-57`（`process` / `gateway` / `closeChannels`）+ 构造 `:66-91`；`stop(long)` `:97-149`（`synchronized (lock)`，`:103` 终态直接返回，`:126`/`:133` 两次有界 `awaitExit`，UNCONFIRMED 可重试）。
- 诊断：`:29-53` 诊断类；`:151-155` `unconfirmed(stage, grace, forced)`；`:113-115` 区分 `channels` / `gateway` 两个 stage。
- Session 所有权：`QemuInstanceLauncher.java:161` 构造 supervisor；`:289-293` `terminate()`；`:355-357` `close()` → `stopRuntime(CLOSE_GRACE_MS)`。
- 契约暴露：`RuntimeOwnedSession.java:6-11`；`QemuInstanceLauncher.java:37` `implements ExecutionSession, RuntimeOwnedSession`；`:296-318`、`:320-323`。
- watcher 只调 supervisor：`:738-747` `finally { VirglJniBridge.cleanup(); session.confirmRuntimeStopped(); }` → `:325-328`。全仓 `gateway.stop(...)` 只剩 `RuntimeProcessSupervisor.java:160` 与 `QemuInstanceLauncher.java:802`（后者是 `session == null` 的失败清理回退，不是 watcher 路径）。

2026-11-03 协调：测试映射。`RuntimeProcessSupervisorTest` 覆盖 `:29`（通道/gateway 各只一次）、`:46`（忽略 destroy 后强杀收敛）、`:64`（忽略 destroyForcibly → UNCONFIRMED 且不动 gateway）、`:103`（并发 stop 只终止一次且结果一致）。`ControlChannelRecoveryTest.java:362-363` 已更新 fake handle。

2026-11-03 协调：**已知偏差**——`RuntimeProcessSupervisor` 是纯 Java 源码（只 import `java.*` 与 `QemuProcessLauncher`），但物理位置在 `app` 模块，未拆成独立 JVM 模块。spec 未要求独立模块，判过；若后续要在纯 JVM 模块复用，需另立迁移票。

2026-11-03 协调：实跑 `./gradlew :app:testDebugUnitTest --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL`。真机并发启停为 `not-tested`。
