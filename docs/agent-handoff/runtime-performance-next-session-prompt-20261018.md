工作流模式：`/implement-spec` 续跑模式（task-graph + ready frontier + 每票独立 worktree/branch + 单一集成分支 + 结束一次 `/code-review`）。不要用每票 `/implement` 串行跑完整流程，也不要重新执行 T1/T2/T4。

你是 Orchestella Studio 的新实施会话，负责完成 `runtime-performance` 剩余的 T3/T5/T6/T7/T8。所有人工复核统一等到本轮全部工单完成（或按 spec 明确 deferred）之后再做；中途不要为单张票停下来等人工批准，除非遇到真实阻塞：设备数据缺失导致 T5/T6 条件判断无法进行、需要架构决策、或需要签名/发布/安全授权。

## 第一步：读交接与基线

1. 读 `docs/agent-handoff/runtime-performance-next-session-20261018.zh-CN.md`（本会话的完整交接文档）。
2. 读 `AGENTS.md`、`CONTEXT.md`、`.scratch/runtime-performance/spec.md`、`.scratch/runtime-performance/parallelization.md`。
3. 读剩余票与对应 prompt：
   - `.scratch/runtime-performance/issues/03-credentials-only.md` + `prompts/T3-credentials-only.md`
   - `.scratch/runtime-performance/issues/07-qemu-tuning.md` + `prompts/T7-qemu-tuning.md`
   - `.scratch/runtime-performance/issues/08-regression-gate.md` + `prompts/T8-regression-gate.md`
   - `.scratch/runtime-performance/issues/05-input-disk-host.md` + `prompts/T5-input-disk-host.md`（条件票）
   - `.scratch/runtime-performance/issues/06-input-disk-guest.md` + `prompts/T6-input-disk-guest.md`（条件票）
4. 读已完成工作的关键代码：`LaunchTimeline.java`、`ProvisioningSignal.java`、`InstanceLifecycle.java` 的 provisioning 部分、`RuntimeProcessSupervisor.java`、`PersistentTerminalSessionPool.java`。
5. 读已完成报告：
   - `docs/reports/orchestration/runtime-performance-t1-t2-t4-20261018.zh-CN.md`
   - `docs/reports/tickets/runtime-performance-01|02|04/implementation-report.zh-CN.md`

当前基线：

- 仓库根：`/workspace`
- `main`：`893fb72`（不要改）
- 集成分支：`perf/runtime-performance`
- 集成分支 HEAD：`eabab28`（代码 HEAD `904aa2f`）
- 已完成并合入：T1、T2、T4
- 未完成：T3、T5、T6、T7、T8
- 不 push、不合并 `main`、不 force-push。

先执行：

```bash
cd /workspace
git log --oneline -3 perf/runtime-performance
git status --short
git worktree list
```

确认集成分支存在、工作树干净、T1/T2/T4 的提交和报告都在。如果 `/tmp/orch-perf-integration` 还在且干净，可直接复用它；否则从 `perf/runtime-performance` 新建一个 ASCII 路径 worktree。不要删除或覆盖其他会话的 worktree。

## 第二步：按 task graph 开工

依赖关系：

```text
T1 ─┐
T2 ─┴─> T3 ──> T8
T1 ─────> T7
T3 ──> T5 ──> T6   （T5/T6 条件票）
```

### Wave 0：基线确认

- 跑一次目标测试，确认环境可用（命令见下）。
- 确认 `perf/runtime-performance` 包含 T1/T2/T4；不要重新实现它们。
- 确认 T3 的阻塞 T1/T2 已合入；T3 可以立即开始。

### Wave 1：T3 + T7 并行

- **T3**：分支 `perf/t3-credentials-only`，P0，最高优先级。
  - 从 `perf/runtime-performance` 切出，独立 worktree。
  - 按 `.scratch/runtime-performance/prompts/T3-credentials-only.md` 执行 TDD。
  - 关键约束：
    * 从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例；
    * 用 `DistroCatalogRegistry` 按 `rootfsFileName` 匹配当前 manifest sha；
    * 只有 `instance.isProvisionedFor(sha) == true` 才走 credentials-only；
    * `provisioned=false`/缺失/版本不符/sha 不匹配一律走全量路径；
    * `provisioned=true` 时完全不打开 rootfs 文件；
    * 保留全量嵌入分支，不删；
    * T1 timeline 用 `markInitramfsBuilt(0)` 表示本次未读 rootfs；
    * 不记录 token/口令/按键。
  - 目标测试：`QemuRuntimeBootstrapperProvisionedTest`（计数流 + 大小上限 + 回退）；`:runtime:engine-qemu:check` + `:app:testDebugUnitTest`。
  - 有设备时做 Ubuntu/Arch 重复启动；没有设备写 `not-tested`。

- **T7**：分支 `perf/t7-qemu-tuning`，P2 实验，可与 T3 并行。
  - 从 `perf/runtime-performance` 切出，独立 worktree。
  - 按 `.scratch/runtime-performance/prompts/T7-qemu-tuning.md` 执行。
  - 只改 `QemuLaunchSpec` 的参数消费点/新增 tuning 值对象/矩阵脚本/报告。
  - **默认值不变**；不使用 `cache=unsafe`；不把 AVF 算作收益。
  - 没有设备数据时只交付脚本 + `not-tested` 报告，不得编造 benchmark。
  - 如果 T5 之后启动并修改 `QemuLaunchSpec`，T7 必须等 T5 合入或先完成 T7，不能并行改同一文件。

### Wave 2：T3 合入后

- **T8**：分支 `perf/t8-regression-gate`，P1。
  - 等 T3 合入 `perf/runtime-performance` 后启动。
  - 新增 `ProvisionedLaunchRegressionTest`，复用 T3 的计数流/大小上限。
  - 人为把 T3 的 provisioned 分支改回全量嵌入，确认测试变红；恢复后变绿，记录红/绿证据。
  - 接入 `.github/workflows/ci.yml` 的 PR 门（至少 app unit test；已有 runtime 检查任务一并接入）。
  - 不改生产代码；测试不需要网络/设备/真实 QEMU/gvproxy。

- **T5 决策门**：读 T3 的 `## Comments` 和度量。
  - 如果 T3 数据仍显示首次启动宿主准备或 initrd 内存是主要成本，且设备数据可用：启动 T5（`perf/t5-input-disk-host`），T5 合入后再 T6。
  - 如果没有设备数据或 T3 已消除主要成本：在 T5 issue 追加 `## Comments`，写 `deferred` + 理由/证据；**不要硬做 T5/T6**，T6 不启动。
  - T5 会改 `QemuLaunchSpec`；如果 T5 启动，T7 必须等 T5 合入后再改同一文件。

- **T6**：只有 T5 未 deferred 才启动；按 `prompts/T6-input-disk-guest.md` 执行 guest 侧输入选择/校验/回退/65 项回归。不改宿主 Java、QEMU 参数、根盘格式。

## 第三步：集成与验证

每票独立提交到自己的分支，然后按以下顺序合入 `perf/runtime-performance`：

1. T3（必须先合入，T8 依赖它）。
2. T7（如果与 T3 并行完成；若 T5 已启动，按 T5→T7 或 T7→T5 的顺序，避免同时改 `QemuLaunchSpec`）。
3. T8（T3 合入后）。
4. T5/T6（仅当未 deferred）。

集成时：

- 从 `perf/runtime-performance` 合并，不要直接改 `main`。
- 冲突时保留双方语义，不吞掉对方测试。
- 每次合并后跑目标测试；最终跑全量验收集：

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest :app:lintDebug \
  -Porchestella.repositories.useAliyunMirror=true --no-daemon
git diff --check
```

- Maven Central 当前 429；必须带 `-Porchestella.repositories.useAliyunMirror=true`。首次构建不要用 `--offline`；依赖缓存后可以再用。
- `local.properties` 指向 `/opt/android-sdk`，已被忽略，不要提交。
- 所有 Gradle 从 ASCII 路径执行，不要从中文/括号路径跑。
- 最终必须无新增 `.log` 证据文件；证据用 `.md/.json/.tsv/.log.txt`。

## 第四步：文档与最终交付

每票完成后：

- 在对应 issue 末尾追加 `## Comments`：commit hash、测试命令/结果、设备结果或 `not-tested`、风险。
- 新建 `docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`，按 `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md` 的模板写。
- 如果 T5 被 deferred，也要写 report，明确 `deferred` 的理由与证据，不要伪造完成。

全部完成后：

- 新建/更新一份 orchestration report（`docs/reports/orchestration/runtime-performance-remaining-20261018.zh-CN.md` 或类似），汇总所有剩余票、测试结果、deferred 项、风险和人工复核清单。
- 更新所有剩余 issue 的 `Status:` 为合适的 triage 标签：完成/待人工复核用 `ready-for-human`；deferred 也在 `## Comments` 写明 `deferred` 与理由，`Status` 使用 `ready-for-human`（需要人工裁定是否继续），不要发明新标签。
- 不要 push、不要合并 `main`、不要开 PR；把集成分支和报告留在本地，等用户集中人工复核。
- 最终回复必须列出：完成/ deferred 的票、每票 commit、全量测试数字、未测项、已知风险、人工复核清单、集成分支 HEAD。

## 绝对不要做

- 不要重新实现 T1/T2/T4，不要重写 `LaunchTimeline`/`ProvisioningSignal`/T4 的停止逻辑。
- 不要为了完成 T5/T6 而硬做；没有设备/度量证据就 deferred。
- 不要改默认 QEMU 参数、不要使用 `cache=unsafe`、不要把 AVF 算作性能收益。
- 不要引入 token/口令/按键/rootfs 内容到日志、时间线或报告。
- 不要新增 `.log` 扩展名证据文件。
- 不要 push、不要合并 `main`、不要 force-push。
- 不要在所有工单完成前要求人工逐票复核。

## 开始时先回复

先用 3–5 行确认你已读到：当前集成分支 HEAD、T1/T2/T4 已完成、剩余 T3/T7/T8 立即就绪、T5/T6 条件票、你将使用的 `/implement-spec` 续跑波次。然后直接开始 Wave 0/1，不要等待人工批准。
