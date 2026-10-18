你是 Orchestella 的实施会话，只负责工单 **T8 启动性能回归门**。

## 前置

- 阻塞于 **T3**。先确认 T3 已合入你的基线。
- 基线：`perf/runtime-performance` 集成分支（含 T3）。

## 工作区

> 以下路径均相对仓库根目录；Gradle 必须通过 ASCII 映射执行（不要从中文/括号原路径运行）。

- 分支 `perf/t8-regression-gate`；独立 worktree + 空闲 ASCII 映射。

## 先读

1. `AGENTS.md`
2. `.scratch/runtime-performance/spec.md`
3. `.scratch/runtime-performance/issues/08-regression-gate.md`
4. `.scratch/runtime-performance/parallelization.md`
5. `.github/workflows/ci.yml`
6. `.scratch/runtime-performance/issues/03-credentials-only.md` 的 `## Comments`

## 目标

一个不依赖设备的 JVM 回归门：

- 已装配实例的启动路径不得打开或读取 distro rootfs 输入流；
- 凭据-only initrd 大小不得超过固定上限；
- T3 被回退或有人重新引入全量嵌入时，测试变红；
- 接入 CI 的 PR 门（至少 app unit test；若已有 runtime 检查任务，一并接入）。

## 边界

- 你拥有：新增回归测试、`.github/workflows/ci.yml` 的最小改动。
- 不改生产代码；不改 T3 的实现；不改测试以外的构建逻辑。
- 测试不需要网络、设备、真实 QEMU/gvproxy；不输出 rootfs 内容或 secret。

## TDD

1. 先写 `ProvisionedLaunchRegressionTest`，复用 T3 的计数输入流/上限断言。
2. 人为把 provisioned 分支改回全量嵌入，确认测试变红；恢复后变绿。
3. 接 CI：确保该测试在 PR 上执行，失败阻塞合并。
4. 跑目标测试与全量 app 测试。

## 命令

```bash
cd /x
./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' --offline
./gradlew :app:testDebugUnitTest --offline
git diff --check
```

## 交付

- 提交到 `perf/t8-regression-gate`，不要合入 `main`。
- 在工单文件末尾追加 `## Comments`：提交 hash、红/绿证据、CI 变更、未测项。
- 最终回复：回归门覆盖什么、不覆盖什么、CI 上如何看到失败。
