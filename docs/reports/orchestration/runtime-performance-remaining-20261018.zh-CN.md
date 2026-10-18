# 编排报告：runtime-performance 剩余工单收尾（T3/T5/T6/T7/T8，2026-10-18）

- 背景 / scope：按 `.scratch/runtime-performance/` 的 task graph 与 `/implement-spec` 续跑模式，完成 T1/T2/T4 之后的剩余票：T3（P0 实现）、T8（P1 回归门）、T7（P2 实验脚手架）、T5/T6（条件票）。人工复核统一留到本轮结束。
- branch / 基线 / commit：基线 `perf/runtime-performance` HEAD `bd24d24`（代码 HEAD `904aa2f`，含 T1/T2/T4）；集成分支 `perf/runtime-performance`；本报告提交前集成 HEAD `e82bc44`。未 push、未合并 `main`、未开 PR。
- 工作区：T3 `/tmp/orch-perf-t3`（`perf/t3-credentials-only`）、T7 `/tmp/orch-perf-t7`（`perf/t7-qemu-tuning`）、T8 `/tmp/orch-perf-t8`（`perf/t8-regression-gate`）、集成分支 `/tmp/orch-perf-integration`。T5/T6 未创建分支。

## 实际完成

### T3 重复启动只嵌入凭据（completed）

- 每次 launch 从 `InstanceLifecycle.FileStore(context.getFilesDir()/instances)` 重新加载实例，按 `rootfsFileName` 解析 `DistroCatalogRegistry` 当前 sha，仅 `isProvisionedFor(sha)==true` 走 credentials-only。
- `credentialsOnly=true` 时不 stat/size/open rootfs；`EmbeddedProvisionInput.writeCredentialsOnlySegment(...)` 只写凭据、身份、hostname、镜像源、first-boot、`.active` 与信任元数据；无 rootfs entry。
- 全量分支保留，产物与 `EmbeddedProvisionInput.writeSegment` 重建结果逐字节相同。
- 256 KiB 硬上限，在 `Files.move` 发布前检查；超限删除 `.partial` 并 fail。timeline `markInitramfsBuilt(0, initramfsBytes)`；JSONL 暴露 `rootfsBytes=0`、`credentialsOnly=true` 与非空 `initramfsBytes`。
- 回退条件：metadata 缺失/损坏、marker 版本不符、sha 不匹配、rootfs 文件名未知/歧义、读取异常。

### T8 启动性能回归门（completed）

- 新增 `ProvisionedLaunchRegressionTest`（3 个用例）：已装配路径 0 次打开 / 0 B 读取 / 无 rootfs entry / segment ≤ 256 KiB / timeline `rootfsBytes:0`；另覆盖 `QemuInstanceLauncher.credentialsOnlyLaunch` 的持久化 evidence 决策。
- 同一 256 KiB 不可压缩 fixture 的全量路径打开 1 次、含 rootfs entry、segment > 256 KiB，作为负控制。
- 临时把 bootstrapper 的 `credentialsOnly` 强制为 false，测试红（3 tests / 2 failed，含 `expected:<0> but was:<1>`）；临时把 launcher 决策强制为 false，测试红（3 tests / 1 failed）；恢复后绿（3 tests / 0 failures）。证据：`docs/reports/tickets/runtime-performance-08/red-green-evidence.md`。
- CI 新增命名回归门步骤；全量步骤扩展为 `:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :app:testDebugUnitTest`。

### T7 QEMU 单变量调优实验（completed，设备 not-tested）

- 新增 `QemuTuning` 值对象与 `buildWithPasst(..., QemuTuning)` 重载；`DEFAULTS` 逐项复刻今天行为，旧签名委托到默认 tuning。
- 单变量覆盖 CPU model、`tb-size`、`aio`、`discard=unmap`、`detect-zeroes=unmap`、vCPU、passt UNIX stream；无 cache 字段，测试禁止 `cache=unsafe`。
- `tools/qemu-tuning/matrix.py --dry-run --image ubuntu-24.04`：11 变体 × 6 workload = 66 行，记录 image、设备型号/Android 版本、电池温度、热状态与 `samples_target=10`，全部 `status=not-tested`；证据 `docs/reports/tickets/runtime-performance-07/matrix-dry-run.tsv`。
- 结论：保留现状；无设备数据，不提议修改默认值，不把 AVF 算作收益。

### T5 / T6 只读输入盘（deferred）

- T5 决策门：T3 只有 JVM 合成数据（523 B vs 903 B segment），`adb` 不可用，无 Ubuntu/Arch 真机启动耗时或 initrd 内存数据；无法证明首次启动宿主准备/initrd 内存是主要成本，按 spec `deferred`。
- T6 阻塞于 T5，未启动。
- 两份报告：`docs/reports/tickets/runtime-performance-05/implementation-report.zh-CN.md`、`docs/reports/tickets/runtime-performance-06/implementation-report.zh-CN.md`。

## 未完成内容

- 所有真机项：Ubuntu/Arch 重复启动、真实 guest marker 端到端、真实 initrd 内存/启动耗时、64 forward 真实 gvproxy 停止窗口、Android listener 行为，均 `not-tested`（无 adb/设备）。
- T7 设备矩阵与统计（n≥10、中位数 + IQR、控温）未执行；`io_uring`、`neoverse-n1`、`max,sve=off`、`-netdev stream` 的真实可用性未验证。
- GitHub Actions 实际运行 `not-tested`（未 push / 未开 PR）。
- 继承 T1/T2/T4 已知偏差：T1 stop retry JSONL 保留第一次结果；T4 严格锁外停止未完全满足、force-wait 路径网关可能存活；T2 AVF sink 未接、旧实例无 evidence。

## 修改文件

- 生产：`QemuInstanceLauncher.java`、`QemuRuntimeBootstrapper.java`、`EmbeddedProvisionInput.java`、`QemuLaunchSpec.java`、`QemuTuning.java`（新增）。
- 测试：`QemuRuntimeBootstrapperProvisionedTest`（新增）、`ProvisionedLaunchRegressionTest`（新增）、`QemuTuningTest`（新增）、`runtime/engine-qemu/build.gradle.kts`。
- 工具 / CI：`tools/qemu-tuning/matrix.py`（新增）、`.github/workflows/ci.yml`。
- 文档：T3/T5/T6/T7/T8 五份 ticket 报告、T7 dry-run TSV、T8 红/绿证据、本 orchestration report、各工单 `## Comments`。
- 未改 ADR、`main`、发布签名、Full Linux 契约、QEMU 默认参数。

## 模型、委派、workflow 或 worktree 影响

- 采用 `/implement-spec` 续跑波次：Wave 1 T3 + T7 并行（独立 worktree/branch），T3 合入后 Wave 2 T8；T5 决策门在 T3 合入后执行。
- T3/T7/T8 各自独立 worktree；集成分支按 T3 → T7 → T8 顺序 `--no-ff` 合并；无冲突。
- T5/T6 未创建 worktree。未新增常驻进程、Binder service 或隐藏 API。

## 测试与验证结果

命令：

```bash
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest :app:lintDebug \
  -Porchestella.repositories.useAliyunMirror=true --no-daemon
git diff --check
```

结果：

| 任务 | 结果 |
| --- | --- |
| `:runtime:contract:check` | 通过，3 suites / 24 tests / 0 failures |
| `:runtime:instance:check` | 通过，4 suites / 15 tests / 0 failures |
| `:runtime:engine-qemu:check` | 通过，1 JUnit suite / 2 tests + 11 个 main-based JavaExec 任务 |
| `:app:testDebugUnitTest` | 通过，105 suites / 823 tests / 0 failures / 0 errors / 0 skipped |
| `:app:lintDebug` | 通过，0 errors / 753 warnings |
| `git diff --check` | 通过 |
| 新增 `.log` 证据文件 | 无 |

专项证据：

- T3 新增 8 tests：opener 0 次 / 0 B、timeline 0 / `credentialsOnly=true` / `initramfsBytes`、全量逐字节等价、缺失/不匹配回退、256 KiB 上限。
- T8 红/绿：bootstrapper 回退红 3 tests / 2 failed；launcher 回退红 3 tests / 1 failed；绿 3 tests / 0 failures。
- T7 `QEMU_TUNING_TEST_OK`：默认等价、单变量覆盖、无 `cache=unsafe`、UNIX stream 显式、非法值拒绝。
- T7 dry-run：66 行 / 11 变体 / 6 workload，全部 `not-tested`。

## 风险与后续动作

1. **T3**：宿主 evidence 与根盘实际状态不一致时 guest fail closed（不写根盘）；旧实例无 evidence 仍走全量。256 KiB 上限是硬常量（真实 segment 约 59 KB），未来增加合法小文件需显式评估。
2. **T8**：回归门覆盖 launch 的零 rootfs 契约、initrd 上限与 launcher evidence 决策，不覆盖 guest 消费、真实 QEMU 或 CI 实际运行。
3. **T7**：`-netdev stream` 仅参数构造通过；`io_uring`/CPU model 可能被设备内核或 QEMU 拒绝。设备实验必须记录失败样本，不能当作性能结果。
4. **T5/T6**：deferred 会延后首次启动/initrd 内存优化；需要设备测量后再决定是否继续。
5. **继承风险**：T4 严格锁外停止与 force-wait 网关存活、T1 stop retry、T2 AVF sink 未接。
6. 下一步人工复核通过后，才考虑 push `perf/runtime-performance`、开 PR 并让 CI 实际运行。

## 人工复核清单

- [ ] 复核 T3 的 evidence 判定入口、全量逐字节等价、0 字节/0 打开证明与 256 KiB 上限。
- [ ] 复核 T8 红/绿证据与 `.github/workflows/ci.yml` 的门禁步骤。
- [ ] 复核 T7 `QemuTuning.DEFAULTS` 与默认 argv 未变；决定是否投入设备矩阵。
- [ ] 复核 T5/T6 deferred 理由；决定补设备测量还是关闭该架构线。
- [ ] 决定是否 push/开 PR；确认 `main` 未被修改、无 force-push。
- [ ] 设备验证：Ubuntu/Arch 重复启动、真实 marker 端到端、initrd 大小/内存、64 forward gvproxy 停止、真实 CI。

## 越界与 Architecture Change Policy 检查

- 未改 ADR 正文；未新增 stop owner、实例状态、暴露面或 Full Linux 契约字段。
- 未改 QEMU 默认参数；`QemuTuning` 是 opt-in，默认路径逐项等价；未使用 `cache=unsafe`。
- 未把 AVF 算作性能收益；未提高 guest RAM。
- T3 改变的是 launch 输入准备（rootfs 读取/嵌入），不改变 guest marker、Provisioning 语义或根盘格式。
- T5/T6 未实现，未引入只读输入盘或 9p 变更。
- 未触发 Architecture Change Policy；若未来要把 T5 只读输入盘作为默认路径，需要单独设计/评审。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录
