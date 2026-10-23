# 批次二修复：空 stage、QEMU readiness 与并发取消

- 日期：2026-10-23
- Base：`feat/accelerator-batch2 @ a596d73`
- Branch：`feat/accelerator-batch3`
- 范围：仅修复批次二三处回归；功能改动见 AX-02/AX-06 报告。
- 零设备：本记录没有运行 adb、没有安装 APK、没有启动真实 VM。
- CI：**未验证**。本批只做本地 JVM/fake 验证；PR/CI 尚未运行。

## 1. 结论

三处缺陷均已修复，修复提交与功能提交分开。

| # | 缺陷 | 修复提交 | 回归 |
| --- | --- | --- | --- |
| 1 | `ProtectedFailure.fromLegacy` 空 stage NPE | `26abb10` | 16/16 |
| 2 | QEMU `isReady` 等同 `isAlive` | `04a8233` | 4/4 |
| 3 | 协调器持锁等待，`cancel` 阻塞 | `41ce08c` | 28/28 |

## 2. 缺陷一：`ProtectedFailure.fromLegacy` 空指针

### 根因

`ProtectedFailure.java:150`（修复前）：

```java
String stage = trimmed.toLowerCase(Locale.ROOT).contains("execve")
        ? STAGE_EXECVE : null;
...
if (parsed == null && stage.equals(STAGE_EXECVE)) {
```

纯 code 输入（如 `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`）没有 `execve`，`stage` 为 `null`，反向调用 `stage.equals` 直接 NPE。同文件其他可空比较已使用常量在前，只有这一处遗漏。

### 修法

改为 `STAGE_EXECVE.equals(stage)`，行为不变：只有确实带 `execve` 的 legacy 输入才默认 errno 13。

### 回归

`ProtectedFailureTest` 新增 5 例：

- 纯 code：无 stage/errno，不抛异常，render 只有 code；
- 带 `stage=execve`：errno 默认 13；
- 矩阵 cell `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)`；
- `execve_errno_13` 仍映射平台 code；
- `null`、空白、未知 code、含路径输入均为 `FASTLANE_UNKNOWN`。

验收：`./gradlew :runtime:engine-fastlane:test --tests '*ProtectedFailure*'`，16/16 通过。

## 3. 缺陷二：QEMU readiness 不等于进程存活

### 根因

`QemuLaneRuntime.java:30-32`（修复前）：

```java
@Override
public boolean isReady() {
    return isAlive();
}
```

协调器在 `awaitQemuReady` 中只要 `isReady()` 为真就置 `READY`。QEMU 进程活着但 guest 尚未可服务时会被误报 `READY`。

### 修法

- 新增构造器 `QemuLaneRuntime(RuntimeOwnedSession, BooleanSupplier readySignal)`；
- 单参构造器委托 `() -> false`，没有 readiness 来源时永远不 ready；
- `isReady()` 返回 `isRuntimeAlive() && readySignal.getAsBoolean()`；
- 协调器因此在没有信号时保持 `STARTING`，不会直接 `READY`。

当前仓库没有 `DualLaneCoordinator` 的生产装配根（`grep` 仅测试构造），本批没有伪造一个装配根；真实调用方必须传入由 guest 就绪探针驱动的信号。

### 回归

`QemuLaneRuntimeDualLaneTest` 4 例：

- 默认构造器：alive 但 not ready；
- alive 未 ready：协调器 `STARTING`；
- 信号置位：`READY` + `lane_ready`；
- ready 前进程死亡：`LOST` 且 ACCEL 被停止。

验收：`./gradlew :app:testDebugUnitTest --tests '*DualLane*'`，Qemu+Coordinator 32/32 通过。

## 4. 缺陷三：协调器持锁等待导致并发取消失效

### 根因

`DualLaneCoordinator.java` 修复前：

- `startAll` / `awaitAcceleratorReady` / `stopAll` / `cancel` 全部 `synchronized`（108、187、217、225）；
- `awaitQemuReady`（254-274）和 `awaitAcceleratorReady`（187-213）在 monitor 内循环并 `clock.sleep`；
- 另一个线程调用 `cancel()` 只能等 `startAll` 的 readiness 超时或返回。

### 修法

- 改 `ReentrantLock` + `volatile stopRequested`；
- 状态/lease 变更持锁；readiness 轮询每轮持锁检查、释放锁后睡眠，下一轮重新检查；
- `cancel()` 先置 `stopRequested`，再取锁停止已启动车道；停止顺序仍是 QEMU 先、ACCEL 后；
- 保留既有语义：两个 start 先发出、`UNCONFIRMED` 保留 lease/预算、`close()` 也先置停止标志。

### 回归

`DualLaneCoordinatorTest` 新增 3 例：

- 真实时钟下 `startAll` 卡在 QEMU alive-not-ready，100 ms 后 `cancel` 在 200 ms 内返回，QEMU/ACCEL 各 stop 一次，预算归零；
- `awaitAcceleratorReady` 等待中 `cancel` 同样及时返回；
- 新增显式断言：ACCEL start 在第一次 QEMU readiness 探测之前发出。

测试线程使用 daemon，超时不会拖住 Gradle JVM。验收：`./gradlew :runtime:instance:test :app:testDebugUnitTest --tests '*DualLane*'` 通过（instance 29、app 相关 32）。

## 5. 验证汇总

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:engine-fastlane:test --tests '*ProtectedFailure*'` | 16/16 |
| `./gradlew :runtime:instance:test` | 29/29 |
| `./gradlew :app:testDebugUnitTest --tests '*DualLane*'` | 32/32 |
| `python tools/ci/check_sensitive_data.py --range feat/accelerator-batch2..HEAD` | `SENSITIVE_DATA_OK` |
| `git diff --check feat/accelerator-batch2..HEAD` | 通过 |

Gradle 使用显式 ANDROID_HOME 与显式 Aliyun 镜像；未修改依赖版本。

## 6. 偏差与未验证

- `QemuLaneRuntime` 的真实 readiness 信号没有生产装配；本批只保证没有信号时不会误报 READY。
- `cancel()` 的及时性以 fake lane 的同步 stop 验证；真实 `RuntimeProcessSupervisor.stopRuntime` 的阻塞时间未测。
- `awaitQemuReady` 死亡路径仍在锁内调用 `stopLane(ACCEL)`；只影响该异常路径，不影响 cancel 的轮询等待。
- **not-tested**：真实 QEMU + ACCEL 同时运行、真实进程死亡、真实 stop 超时。
- CI 未运行。
