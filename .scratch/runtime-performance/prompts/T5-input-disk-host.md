你是 Orchestella 的实施会话，只负责工单 **T5 只读输入盘（宿主侧）**。

## 前置（条件票）

- 阻塞于 **T3**。先读 T3 的 `## Comments` 与度量数据。
- **只有 T3 的数据仍显示首次启动宿主准备或 initrd 内存占用是主要成本时，才进入实现。** 如果 T3 已经消除了重复启动的确定性浪费、且首次启动可接受，把工单标为 `deferred` 并说明理由，不要为了完成工单硬做。
- 基线：`perf/runtime-performance` 集成分支（含 T3）。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 分支 `perf/t5-input-disk-host`；独立 worktree + 空闲 ASCII 映射。

## 先读

1. `AGENTS.md`
2. `.scratch/runtime-performance/spec.md`
3. `.scratch/runtime-performance/issues/05-input-disk-host.md`
4. `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java`
5. `tools/qemu-android/qemu-jni.lock`（确认 `--disable-virtfs`、版本 11.1.1）

## 目标

让 QEMU 启动参数可选地把 distro tarball 作为独立只读 virtio-blk 设备暴露：

- 独立 drive id、`readonly=on`、`format=raw`；
- 不改变 root 盘的 `cache=writeback` 语义与设备顺序假设；
- 未启用时输出与今天逐项相同；
- 不引入 9p（构建已 `--disable-virtfs`）。

## 边界

- 你拥有：`QemuLaunchSpec` 的输入盘参数、对应测试。
- 只允许最小改动：`QemuInstanceLauncher` 的参数传入点。
- 不要改 guest init（T6 负责）；不要改 bootstrapper 的凭据-only 逻辑；不要改 supervisor/gateway。
- 不要改默认值；本票只加可选能力。

## TDD

1. 先写 `QemuLaunchSpecTest`：启用时存在只读输入盘、独立 id、raw；未启用时逐项等于当前输出。
2. 断言 root 盘参数完全不变，且没有 `cache=unsafe`。
3. 实现并跑 `:runtime:engine-qemu:check`。

## 命令

```bash
cd /x
./gradlew :runtime:engine-qemu:check --offline
git diff --check
```

## 交付

- 提交到 `perf/t5-input-disk-host`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：是否 deferred 及理由、提交 hash、测试结果、未测项。
- 最终回复：如何启用、设备名/参数、T6 需要消费的设备路径与契约。
