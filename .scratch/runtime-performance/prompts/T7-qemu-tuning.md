你是 Orchestella 的实施会话，只负责工单 **T7 QEMU 单变量调优实验**。

## 前置

- 阻塞于 **T1**（需要阶段时间线）。如果 T5 已改 `QemuLaunchSpec`，等 T5 合入后再动同一文件。
- 这是实验票：**默认值不变**，改默认为独立的后续决定。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 分支 `perf/t7-qemu-tuning`；独立 worktree + 空闲 ASCII 映射。

## 先读

1. `AGENTS.md`
2. `.scratch/runtime-performance/spec.md`
3. `.scratch/runtime-performance/issues/07-qemu-tuning.md`
4. `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java`
5. `docs/reports/orchestration/next-optimization-plan-20261018.zh-CN.md` 的 P2-3
6. `tools/qemu-android/qemu-jni.lock`（QEMU 11.1.1）

## 目标

把性能相关参数变成可注入的 tuning 值对象，默认值与今天逐项相同；提供可重复执行的设备矩阵脚本与结论报告。

单变量矩阵：

- CPU 模型：`cortex-a57` vs `neoverse-n1`（暴露 LSE，无 SVE）vs `max,sve=off`
- TCG：`tb-size=32/64/128`
- 磁盘：`aio=threads` vs `io_uring`（平台允许时）；`discard=unmap`、`detect-zeroes=unmap`
- vCPU：2 vs 4
- 网络：TCP loopback vs `-netdev stream` unix socket

工作负载：boot 到提示符、`apt update`、`tar -xzf`、`gcc` 编译、`dd`、`iperf3`。统计：同设备/同镜像/同资源、控温、n≥10、中位数 + IQR。

## 边界

- 你拥有：新增 tuning 值对象、矩阵脚本、报告；`QemuLaunchSpec` 的参数消费点。
- 不改 bootstrapper / supervisor / gateway / guest。
- **不改默认值**；不使用 `cache=unsafe`；不把 AVF 算作收益。
- 没有设备数据时报告必须写 `not-tested`，不得编造数字。

## TDD

1. 先写测试：默认启动参数与当前输出逐项相同。
2. 实现 tuning 值对象与参数消费。
3. 写矩阵脚本（dry-run 可验证），再在设备上跑；没有设备就只交付脚本与 `not-tested` 报告。
4. 跑 `:runtime:engine-qemu:check`。

## 命令

```bash
cd /x
./gradlew :runtime:engine-qemu:check --offline
git diff --check
```

## 交付

- 提交到 `perf/t7-qemu-tuning`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：默认值等价测试、矩阵数据位置、结论（含负结果）、未测项。
- 最终回复：哪个变量值得进入默认变更讨论、证据强度、哪些是 not-tested。
