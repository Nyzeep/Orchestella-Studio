# runtime-performance 剩余工单交接（2026-10-18）

## 0. 这份文档怎么用

这是给下一个会话的交接文档。先读本文件，再读 prompt `docs/agent-handoff/runtime-performance-next-session-prompt-20261018.md`。不要从 T1 重新开始；T1/T2/T4 已实现、已合入集成分支、已通过全量 JVM 验证。

所有人工复核统一等到本轮剩余工单全部完成（或按 spec 明确 deferred）之后，再做一次集中复核。中途不要为单张票停下来等人工批准，除非遇到真实阻塞：设备数据缺失导致 T5/T6 条件判断无法进行、需要架构决策、或需要签名/发布/安全授权。

## 1. 当前基线

| 项目 | 值 |
|---|---|
| 仓库根 | `/workspace` |
| `main` | `893fb72`（未改动） |
| 集成分支 | `perf/runtime-performance` |
| 集成分支 HEAD | `eabab28`（docs-only 尾部提交；代码 HEAD `904aa2f`） |
| 集成分支状态 | 干净，未 push，未合并到 `main` |
| 已完成 | T1、T2、T4 |
| 待完成 | T3、T5、T6、T7、T8 |

T1/T2/T4 的票分支和 worktree 仍保留：

- `perf/t1-launch-timeline`，`/tmp/orch-perf-t1`
- `perf/t2-provisioned-signal`，`/tmp/orch-perf-t2`
- `perf/t4-stop-fast-path`，`/tmp/orch-perf-t4`
- 集成分支 worktree：`/tmp/orch-perf-integration`

新会话可以复用集成分支 worktree，也可以从 `perf/runtime-performance` 新建 T3/T7 等 worktree。先执行 `git worktree list` 和 `git status`，不要删除或覆盖其他会话的 worktree。

## 2. 已完成工作的关键结论

### T1 启动阶段时间线

- 新增 `app/src/main/java/io/github/nyzeep/orchestella/LaunchTimeline.java`。
- 一次 launch 追加一行 JSONL 到 `<runtimeDirectory>/launch-timeline.jsonl`。
- 阶段：`LAUNCH_START`、`ASSETS_VERIFIED`、`INITRAMFS_BUILT`、`GATEWAY_READY`、`QEMU_STARTED`、`SESSION_REGISTERED`、`STOP_REQUESTED`、`STOP_CONFIRMED`。
- 稳定 API：`LaunchTimeline.open(...)`、`mark(Stage)`、`markInitramfsBuilt(long)`、`markStopConfirmed(StopResult)`、`finish()`。
- T3 跳过 rootfs 时必须调用 `markInitramfsBuilt(0)`（或实际读取字节数），让 T8 能断言 0 字节契约。
- 已知偏差：第一次 stop 结果落盘；后续 retry 只更新内存，不追加第二行。

### T2 已装配信号与元数据

- 新增 `ProvisioningSignal`，识别行首、大小写精确的 `DROIDLAYER_PROVISIONING_COMPLETE version=1`；跨任意 chunk 边界可识别；`version=10`、大小写变体、相似文本不命中。
- `InstanceLifecycle.Instance` 新增 `provisioning.markerVersion`、`provisioning.rootfsSha256`、`provisioning.recordedAtMillis`；旧/不完整元数据按未装配读取。
- 只读查询：
  - `isProvisioned()`：marker 版本受支持；
  - `isProvisionedFor(currentRootfsSha256)`：证据与当前 distro sha 匹配。
- **T3 必须用 `isProvisionedFor`，不能只看 `isProvisioned()`。**
- T3 必须从 `InstanceLifecycle.FileStore` 重新加载实例，不能使用内存里的旧 `Instance`。
- 旧实例不会补写 evidence；首次装配后的下一次启动才可能命中快速路径。
- AVF 路径未接 sink；本 effort 以 QEMU 主线为准。
- 既有元数据仍保留 `bridgeToken`/`guestPassword` 字段（P0-2 设计）；新增 provisioning 字段不含 secret。

### T4 停止路径快速收尾

- `QemuInstanceLauncher.Session.closeChannels` 不再逐 forward 发远程 `unexpose`；forward 释放以 gvproxy 退出为准。
- 单个 `ServiceForward.close()` 仍保留远程 unexpose 契约。
- `RuntimeProcessSupervisor` 的 `closeChannels` 默认硬 deadline 1000 ms；超时返回 `UNCONFIRMED(stage=channels)`，但仍会停网关。
- 已死进程 + 网关清理失败时 stage 为 `gateway`。
- 池改为 per-instance transition lock；其他实例不被慢停止阻塞；同实例并发 register/stop 不发布第二个 owner。
- 已知偏差：停止在 per-instance lock 内执行，不在池级 monitor 内；“严格锁外停止”未完全满足。
- force-wait 路径（QEMU 未确认退出）不停止网关，listener 可能仍存在；`ServiceForward.isActive()` 以网关存活为准。
- T3/T8 不要依赖 closeChannels 的远程 forward 清理。

## 3. 剩余工单与依赖

```
T1 ─┐
T2 ─┴─> T3 ──> T8
T1 ─────> T7
T3 ──> T5 ──> T6   （T5/T6 为条件票）
```

| 票 | 优先级 | 阻塞 | 性质 | 当前判断 |
|---|---|---|---|---|
| T3 重复启动只嵌入凭据 | P0 | T1、T2 | 实现 | 立即启动，最高优先级 |
| T8 启动性能回归门 | P1 | T3 | 测试 + CI | T3 合入后启动 |
| T7 QEMU 单变量调优 | P2 | T1 | 实验/报告 | 可与 T3 并行；不得改默认值 |
| T5 只读输入盘（宿主） | P2 条件 | T3 | 条件实现 | 只有 T3 数据证明首次启动/initrd 内存仍是主要成本才做 |
| T6 只读输入盘（guest） | P2 条件 | T5 | 条件实现 | 只有 T5 未 deferred 才做 |

**不要为了“完成工单”硬做 T5/T6。** 没有设备数据时，按 spec 将 T5 标为 `deferred` 并写明理由；T6 不启动。这是完成，不是跳过。

## 4. 推荐工作流

**`/implement-spec` 续跑模式**：把 T3/T5/T6/T7/T8 当作 task graph，按 ready frontier 并行派 implementer；每票独立 worktree/branch；全部合入同一个 `perf/runtime-performance` 集成分支；最后做一次 `/code-review`（Standards + Spec）。

不要用每票 `/implement` 串行跑完整流程，因为 T3 与 T7 可以并行，T8 只依赖 T3；也不要重新运行 T1/T2/T4。

### 波次

- **Wave 0（验证基线）**：确认集成分支 HEAD、T1/T2/T4 报告和测试；跑一次目标测试，确认环境可用。
- **Wave 1（并行）**：
  - T3：`perf/t3-credentials-only`，P0，先做。
  - T7：`perf/t7-qemu-tuning`，P2 实验，可与 T3 并行；不得改默认值，不得碰 T3 文件。
- **Wave 2（T3 合入后）**：
  - T8：`perf/t8-regression-gate`，T3 合入后启动。
  - T5 决策门：读 T3 的 `## Comments` 和度量；无设备数据/无证据时 `deferred`，有证据再实现。
  - 如果 T5 启动：T5 合入后再 T6。
- **T7 与 T5 的冲突**：T5 会改 `QemuLaunchSpec`；如果 T5 启动，T7 必须等 T5 合入或先完成 T7 再启动 T5，不能并行改同一文件。T5 若 deferred，T7 不受影响。

## 5. 每票实施要点

### T3 重复启动只嵌入凭据

先读：

- `.scratch/runtime-performance/issues/03-credentials-only.md`
- `.scratch/runtime-performance/prompts/T3-credentials-only.md`
- `app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java`
- `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/EmbeddedProvisionInput.java`
- `guest-assets/initramfs/init` 的 `provisioned-v1` 分支

实现要点：

1. 在 `QemuInstanceLauncher.launch`（或 T3 允许的最小调用点）从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例；用 `DistroCatalogRegistry` 按 `rootfsFileName` 匹配当前 manifest sha；调用 `instance.isProvisionedFor(sha)`。
2. 给 `QemuRuntimeBootstrapper.bootstrap` 增加可选 `provisioned`/`credentialsOnly` 参数；保留所有旧签名。
3. `provisioned=true`：**完全不打开 rootfs 文件**，embedded-input 只写目录、`bridge.token`、guest 凭据、hostname、镜像源配置、first-boot 脚本等小文件；initrd 不得包含 rootfs tarball。
4. `provisioned=false`、证据缺失、版本不符、sha 不匹配：走当前全量路径，输出与现在逐字节等价。
5. `EmbeddedProvisionInput` 增加凭据-only 变体；不要删除全量分支。
6. T1 timeline：调用 `markInitramfsBuilt(0)`（或实际读取字节数）表示本次没有读 rootfs；不要改 T1 API 签名。
7. 测试：
   - 计数输入流/文件打开守卫证明 `provisioned=true` 时 rootfs 读取字节为 0 且文件未被打开；
   - `provisioned=false` 与当前实现逐字节等价；
   - 凭据-only initrd 硬上限常量；
   - marker 缺失/不匹配自动回退；
   - `:runtime:engine-qemu:check :app:testDebugUnitTest`。
8. 有设备时做 Ubuntu 与 Arch 重复启动；没有设备写 `not-tested`。

### T8 启动性能回归门

先读 T3 的 `## Comments`、`issues/08-regression-gate.md`、`.github/workflows/ci.yml`。

实现要点：

- 新增 `ProvisionedLaunchRegressionTest`，复用 T3 的计数流和大小上限；
- 断言已装配路径不打开/不读取 rootfs；
- 人为把 T3 的 provisioned 分支改回全量嵌入，确认测试变红；恢复后变绿，记录红/绿证据；
- 测试不需要网络、设备、真实 QEMU/gvproxy；
- CI 至少跑 app unit test；如果已有 runtime 检查任务，一并接入；失败阻塞 PR；
- 不改生产代码；不输出 rootfs 内容或 secret。

### T7 QEMU 单变量调优实验

先读 `issues/07-qemu-tuning.md`、`prompts/T7-qemu-tuning.md`、`QemuLaunchSpec.java`、`tools/qemu-android/qemu-jni.lock`。

实现要点：

- 新增 tuning 值对象，默认值与今天逐项相同；
- 参数消费点只改 `QemuLaunchSpec` 允许的位置；
- 矩阵脚本可 dry-run，记录设备/温控/镜像/tuning 值；
- 没有设备数据时只交付脚本 + `not-tested` 报告，不编造数字；
- **不改默认值**；不使用 `cache=unsafe`；不把 AVF 算作收益；
- `:runtime:engine-qemu:check` 通过。

### T5 只读输入盘（宿主侧，条件票）

先读 T3 的度量与 `## Comments`。

- 只有 T3 数据仍显示首次启动宿主准备或 initrd 内存是主要成本时才实现。
- 否则在 T5 票追加 `## Comments`：`deferred` + 证据/理由；不启动 T6。
- 若实现：独立 drive id、`readonly=on`、`format=raw`；root 盘 `cache=writeback` 与设备顺序不变；不引入 9p；未启用时 `QemuLaunchSpec` 输出与今天逐项相同。
- 如果 T5 启动，T7 必须等 T5 合入后再改 `QemuLaunchSpec`。

### T6 只读输入盘（guest 侧，条件票）

- 只有 T5 未 deferred 才启动。
- guest init 优先从第二个 virtio-blk 读取；size + SHA-256 校验通过才允许解包；失败/缺失/格式不识别必须在写根盘前 fail；embedded-input 保留兜底；marker 不变；不改宿主 Java、QEMU 参数、根盘格式。
- 跑 guest 65 项回归 + smoke；无设备写 `not-tested`。

## 6. 环境与命令

- JDK 17：`/opt/jdk17`
- Android SDK：`/opt/android-sdk`
- NDK：27.0.12077973（已安装）
- Gradle：8.13
- Maven Central 当前返回 429；使用项目显式 opt-in：

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew ... -Porchestella.repositories.useAliyunMirror=true
```

- `local.properties` 已由工作区本地生成，指向 `/opt/android-sdk`，被 `.gitignore` 忽略，不要提交。
- 所有 Gradle 从 ASCII 路径执行；不要从中文/括号路径跑。
- 首次构建可能需要联网下载依赖；依赖缓存后可加 `--offline`，但不要一开始就用 `--offline`。
- 全量验收集：

```bash
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest :app:lintDebug \
  -Porchestella.repositories.useAliyunMirror=true --no-daemon
git diff --check
```

当前基线测试数：app 812 / contract 24 / instance 15 / engine-qemu 2；lint 0 Error / 753 Warning。

## 7. 报告与证据纪律

- 每票提交后在 issue 末尾追加 `## Comments`：commit、测试命令/结果、设备结果或 `not-tested`、风险。
- 每票一份 `docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`；最终一份 `docs/reports/orchestration/...zh-CN.md`。
- `docs/` 默认被忽略；经审查的文档必须 `git add -f` 具体文件，不要整体 `git add -f docs`。
- 证据文件不得新增 `.log` 扩展名；用 `.md`、`.json`、`.tsv`、`.log.txt`。
- 不输出 token、口令、按键、用户文件内容或 rootfs 内容。
- 不要 push、不要合并到 `main`、不要 force-push；最终由人工集中复核后再决定 push/PR。

## 8. 已知风险与偏差

1. T4 严格“锁外停止”未完全满足；per-instance lock 内停止是有意设计。若新会话要改，需单独设计，不要顺手改。
2. T4 force-wait 路径网关可能仍存活，listener 可能仍存在；`isActive()` 已如实反映。
3. T1 首次 stop `UNCONFIRMED` 后 retry `CONFIRMED` 时 JSONL 保留第一次结果。
4. T2 旧实例/部分元数据保守回退；T3 必须用 `isProvisionedFor`。
5. T2 AVF 路径未接 sink；本 effort 先保证 QEMU 主线。
6. 设备数据缺失时 T5/T6 必须 deferred，不要硬做；T7 只能交付脚本/报告，不得编造 benchmark。
7. `InstanceLaunchSequence.afterLaunchFailure` 仍使用池 monitor，与 per-instance lock 不完全互斥；T4 已记录，T3/T8 不要依赖该路径做互斥。

## 9. 完成标准

本轮剩余工作完成 = 以下全部满足：

- T3 已实现并合入 `perf/runtime-performance`，T8 已接入并验证红/绿。
- T7 已交付 tuning 值对象 + dry-run 矩阵脚本 + 报告；默认值未变；无设备数据时明确 `not-tested`。
- T5/T6：要么有证据并完成，要么按 spec 明确 `deferred`（T5 写明理由，T6 不启动）。
- 全量验收集通过，`git diff --check` 通过，无新增 `.log`。
- 每票 `## Comments` 与 implementation report 存在；最终 orchestration report 存在。
- 集成分支未 push、未合并 main；所有人工复核留到本轮结束后统一进行。
- 最终回复：完成的票、deferred 的票与理由、测试证据、已知风险、下一步人工复核清单。

## 10. 指针

- 规格：`.scratch/runtime-performance/spec.md`
- 并行协议：`.scratch/runtime-performance/parallelization.md`
- 全部票：`.scratch/runtime-performance/issues/01..08`
- 全部 prompt：`.scratch/runtime-performance/prompts/T1..T8`
- 已完成报告：`docs/reports/orchestration/runtime-performance-t1-t2-t4-20261018.zh-CN.md`
- 已完成三票报告：`docs/reports/tickets/runtime-performance-01|02|04/implementation-report.zh-CN.md`
- ADR-0020：`docs/adr/0020-runtime-process-ownership.md`
- P0-2 风险 3：`docs/reports/orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md`
