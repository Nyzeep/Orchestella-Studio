# T1 有界进程 stop seam

Status: resolved
Type: task
Blocked by: 无

## 交付

扩展 `QemuProcessLauncher.ProcessHandle`，提供：

- `waitFor()`
- `waitFor(long timeout, TimeUnit unit)`
- `destroy()`
- `destroyForcibly()`
- `isAlive()`
- `default long pid()`

真实 `ProcessBuilder` 实现直接映射 `java.lang.Process`；现有 fake/调用方全部更新。

## 验收

- 编译通过，旧方法保留。
- 新增纯 JUnit 测试验证：存活进程 `waitFor(timeout)` 返回 false；`destroyForcibly` 后 `waitFor` 返回 true；`pid()` 可用。
- `QemuProcessLauncherTest` 接入 `:runtime:engine-qemu:check`。

## 测试

- `runtime/engine-qemu/src/test/.../QemuProcessHandleSeamTest.java`
- 更新 `QemuProcessLauncherTest.java` 的 fake。
- `./gradlew :runtime:engine-qemu:check`

## Comments

2026-11-03 协调：本轮做 tracker 对账，逐条核对代码后回填（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T1）。

2026-11-03 协调：交付项逐条核对，全部命中代码。
- 六个方法齐备：`QemuProcessLauncher.java:18`（`waitFor()`）、`:21`（`waitFor(long, TimeUnit)`，注释写明 false=仍存活）、`:24`（`destroy`）、`:27`（`destroyForcibly`）、`:30`（`isAlive`）、`:33-35`（`default long pid() { return -1L; }`）。
- 真实映射：`:62-97` `ProcessBuilderFactory` 直接委托 `java.lang.Process`，`pid()` 在 `:93-95`。
- fake 更新：`app/src/test/.../ControlChannelRecoveryTest.java:362-363` 已实现 `destroyForcibly`/`isAlive`。

2026-11-03 协调：运行态证据（关键——本票此前唯一疑点是「`QemuProcessHandleSeamTest` 未列入 `runtime/engine-qemu/build.gradle.kts:98-110` 的 `dependsOn`，可能从未执行」）。
- 命令 `./gradlew :runtime:engine-qemu:check --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL in 1m 6s`，16 actionable tasks。
- 该 JUnit4 用例**确实被执行**：`runtime/engine-qemu/build/test-results/test/TEST-io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessHandleSeamTest.xml` 记 `tests="2" skipped="0" failures="0"`。结论：内建 `test` 任务按 Gradle 8.13 的 JUnit4 默认发现机制执行它，未列入 `dependsOn` 不影响执行——该疑点关闭。
- `not-tested`：真机上的有界停止收敛（本轮零设备）。
