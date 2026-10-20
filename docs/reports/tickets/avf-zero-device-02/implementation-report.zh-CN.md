# avf-zero-device-02：AVF 运行时所有权、停止与退出（实施报告）

- 工单：`.scratch/orchestella-hw-virt/issues/02-avf-runtime-ownership.md`
- Owner：Agent A（生命周期 lane）
- 分支：`ticket/02-avf-runtime-ownership`，基线 `avf/zero-device-hardening` @ `45720ad`
- 日期：2026-10-20
- 结论：**代码就绪（fake/JVM 通过）；真实 AVF 未验，真实 AVF 成功样本仍为 0。**

## 目标与完成状态

| 验收项 | 零设备证据 | 尚需验证 |
| --- | --- | --- |
| `close()` 后 fake VM 停止；重复 close 不重复 stop | `AvfRuntimeOwnershipTest.closeStopsOnceAndRepeatsAreIdempotent`；`AvfLaunchCleanupTest.closeStopsTheOwnerOnceEvenWhenCalledTwice` | 真机 close 与平台 stop 时序 |
| stop 卡住/不收敛 → UNCONFIRMED，保留 owner/RUNNING | 引擎测试 `stuckPlatformStopIsUnconfirmed...`；app 测试 `unconfirmedStopKeepsRunningMetadataOwnerAndGuard` | 真机 stop 卡住样本 |
| stop 抛异常 → UNCONFIRMED | `throwingPlatformStopIsNotSuccess` | 真机 OEM 异常形态 |
| 已停止 → ALREADY_STOPPED | `alreadyStoppedVmReportsAlreadyStopped` | 真机重复 stop |
| 崩溃/DELETED 非 0，DELETED 不当 0 | `exitClassificationDistinguishesNormalDeletedAndStopRequest`；`AvfVirtualMachineEngineTest.numeric_status` 更新 | 真机 DELETED 与崩溃样本 |
| 非 owner 不再 CONFIRMED/true | `plainSessionIsNeverTreatedAsAConfirmedStop`；`plainSessionCannotBeReportedAsStopped` | — |
| register 失败无孤儿 | `AvfLaunchCleanupTest` 两条；生产路径 `AvfInstanceLauncher.launch` 调用 `cleanupFailedRegistration` | 真机 register 失败注入 |
| 同一 session 不产生第二个 stop owner | 引擎 `stopLock` 串行 + 结果复用；`close()` 单一 AtomicBoolean | 真机并发 stop |
| LaunchTimeline 阶段单调 | `launchTraceStagesAreMonotonic` 断言完整顺序 | 真机 timeline 文件落盘 |
| ADR-0021 扩展 ADR-0020 范围 | `docs/adr/0021-avf-runtime-ownership.md` + index | — |

## 修改模块

- `runtime/engine-avf`：
  - 新增 `AvfStopResult`、`AvfExitInfo`、`AvfLaunchTrace`。
  - `AvfVirtualMachineApi` 增加 `status()`、`waitForExit()`、`stopRuntime(graceMs)` 与带 trace 的 `startCustomVm` 默认接缝；不能观测状态的实现返回 UNCONFIRMED。
  - `AvfVirtualMachineEngine.AvfVmSession`：单一 stop owner（`stopLock`）、有界 stop、typed exit、`isRuntimeAlive()`、trace；`waitFor()` 委托 `waitForExit()`。
  - `ReflectiveAvfVirtualMachineApi.ReflectiveHandle`：`getStatus` 轮询、`stop → wait → close → 二次探测`、`STOPPED/DELETED` 分类；`stop()` 保留兼容入口但走同一有界实现。
  - `AvfEngineException` 新增 `VM_STOP_UNCONFIRMED`。
- `app`：
  - `AvfInstanceLauncher.AvfSession` 实现 `RuntimeOwnedSession`；`close()`/`terminate()` 都走 `stopRuntime`；`isRuntimeAlive()`；注册失败调用 `cleanupFailedRegistration`（2000 ms 有界）；LaunchTimeline 记录 AVF 阶段。
  - `InstanceLaunchSequence.stopAfterExit`：非 owner 返回 UNCONFIRMED，不再伪造 CONFIRMED。
  - `PersistentTerminalSessionPool.stopOwnedRuntime`：非 owner 返回 false，保留 holder。
  - `LaunchTimeline`：新增 `VM_CREATED`、`RUN_RETURNED`、`STOP_UNCONFIRMED`、`EXIT_OBSERVED` 与 `markStopUnconfirmed()`。
- 文档：新增 ADR-0021，并在 ADR 索引登记；ADR-0020 原文未改。

## red → green 证据

Red（实现前，实际执行）：

```bash
./gradlew :runtime:engine-avf:avfRuntimeOwnershipTest --no-daemon
# BUILD FAILED：37 个编译错误，AvfLaunchTrace/AvfStopResult/AvfExitInfo 不存在

./gradlew :app:testDebugUnitTest --tests '*InstanceLaunchSequenceTest' \
  --tests '*PersistentTerminalSessionPoolTest' --tests '*AvfLaunchCleanupTest' --no-daemon
# BUILD FAILED：AvfInstanceLauncher.cleanupFailedRegistration 不存在（2 个错误）
```

实现后，行为 red 也真实出现并被修正：

- `plainSessionIsNeverTreatedAsAConfirmedStop`：旧实现返回 NORMAL/STOPPED；新实现 STOP_UNCONFIRMED/RUNNING。
- `plainSessionCannotBeReportedAsStopped`：旧实现 `unregister` 返回 true 并移除 holder；新实现 false 且保留。
- `AVF_VIRTUAL_MACHINE_ENGINE_TEST` 的 DELETED 用例：旧断言 0（bug），新断言非 0。
- 既有 `InstanceLaunchSequenceTest` 因非 owner 语义收紧而失败，测试替身改为显式 owner；这不是放宽断言，而是把“无法确认”从成功路径移到 UNCONFIRMED。

Green（实际执行）：

```bash
./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
# BUILD SUCCESSFUL；AVF_RUNTIME_OWNERSHIP_TEST_OK close_stop=1 idempotent=1
# unconfirmed=1 throwing=1 already=1 exit_normal=1 exit_deleted=1 exit_stop=1 trace=1
# 其余 20 个 AVF JavaExec 契约任务全部 OK

./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
# BUILD SUCCESSFUL；845 tests / 106 suites，failures=0 errors=0 skipped=0
```

补充：一次 `avfTerminalStreamContractTest` 出现 `SocketException: Connection reset`，立即重跑同一 `:runtime:engine-avf:check` 通过；该任务使用真实 loopback socket，属于测试夹具偶发，不是本次修改路径。未隐藏该记录。

## Zero-device 状态

- fake SDK / fake vsock / JVM：close、幂等 stop、UNCONFIRMED、ALREADY_STOPPED、stop 抛异常、DELETED/正常/停止请求分类、trace 顺序、register 失败清理、非 owner 语义全部通过。
- 全量 `:runtime:engine-avf:check` 与 `:app:testDebugUnitTest` 通过。
- 未连接 adb、未 pm grant、未启动真实 AVF。

## 未验项（真机）

- 真实 AVF `stop()` 是否在 grace 内收敛；OEM 是否返回异常或永久 RUNNING。
- 真机 DELETED/崩溃的实际 status 与 exit 形态；App 进程死亡后 VM 是否随停。
- 真机 close/terminate 与平台 close 的时序、并发 stop 竞态。
- 真实 `register()` 失败注入（目前为纯 JVM 替身）。
- LaunchTimeline 在设备运行时目录的实际落盘与内容审查。

## 风险、取舍与回滚

- 行为收紧：非 `RuntimeOwnedSession` 的 session 不再被当作已停止。生产 QEMU/AVF 都实现 owner；旧测试替身已显式迁移。若存在未识别的 legacy session，池会保留 holder 而不是静默移除，这是有意的诚实失败。
- `AvfSession` 从 `final` 改为可继承，仅为 app 侧 close 契约测试提供 seam；生产构造路径未变。
- `stopRuntime` 在 grace 内阻塞调用线程；调用方必须在工作线程执行（与 QEMU 一致），未在 UI 线程新增阻塞。
- 回滚：撤回 owner/stop/trace 代码、ADR-0021 与相关测试；QEMU 路径未改。
- 环境：本机初始无 JDK/Android SDK，已安装 Temurin 17、Android SDK 36/build-tools 36.1.0/NDK 27，并使用仓库已有的 Aliyun mirror 开关（Maven Central 对本机返回 429）。这些是环境准备，不进入提交。
- 工作树在开工时已有未跟踪的 `*.rej` 与 `__pycache__`，未删除、未纳入提交。
