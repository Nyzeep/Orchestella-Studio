# T1 有界进程 stop seam

Status: ready-for-agent
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
