# T3 RuntimeProcessSupervisor 所有权

Status: ready-for-agent
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
