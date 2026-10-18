# Ticket 08 实施报告

- 票号/标题：runtime-performance-08 启动性能回归门
- 执行 session / 模型 / thinking：续跑 T8 ticket session / DeepSeek-V4.1-Flash / 未记录
- 分支：`perf/t8-regression-gate`　基线 commit：`7e081a8`（集成分支，含 T3）

## 1. 任务目标

提供一个不依赖设备的 JVM 回归门：已装配实例的启动路径不得打开或读取 distro rootfs；凭据-only initrd 不得超过固定上限；T3 被回退时测试变红；接入 CI 的 PR 门。

## 2. 实际完成

- ✅ 新增 `ProvisionedLaunchRegressionTest`（2 个用例）：
  - `provisionedLaunchNeverOpensOrReadsTheRootfs`：注入 opener 统计打开次数与读取字节，断言 0 次 / 0 B；解析 cpio 断言没有 rootfs entry；segment ≤ `CREDENTIALS_ONLY_MAX_BYTES`；timeline JSONL 含 `rootfsBytes:0`。
  - `theSameFixtureWouldFailIfFullEmbeddingReturned`：同一 256 KiB 不可压缩 tarball 走全量路径，断言打开 1 次、包含 rootfs entry、segment > 64 KiB，证明 fixture 对回退敏感。
- ✅ 人为把 `bootstrap(..., credentialsOnly)` 强制改成 `credentialsOnly = false`（模拟 T3 回退），测试变红：`provisioned launch must not open the distro tarball expected:<0> but was:<1>`。
- ✅ 恢复生产代码后测试变绿：2 tests / 0 failures。红/绿证据见 `docs/reports/tickets/runtime-performance-08/red-green-evidence.md`。
- ✅ CI `.github/workflows/ci.yml` 增加显式 `Run provisioned launch regression gate` 步骤；原 `Run contract checks and unit tests` 扩展为 `:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :app:testDebugUnitTest`。PR 失败会阻塞合并。
- ✅ 测试不访问网络、设备、真实 QEMU 或 gvproxy；不输出 rootfs 内容或 secret。
- ✅ 未修改生产代码。临时红跑改动已恢复，最终 diff 只有测试与 CI。

## 3. 未完成 / 与预期偏差

- CI 只在 GitHub Actions 的 `pull_request` 到 `main` 时运行；本轮按用户要求不 push、不开 PR，因此“真实 CI 运行”标为 `not-tested`。本地等价命令已通过。
- 回归门覆盖 launch 的 0 字节 rootfs 契约与 initrd 上限；不覆盖真实 QEMU 启动、guest marker 端到端、停止路径 64 forward 窗口。
- 未新增真实设备测试。

## 4. 修改的模块

- `app/src/test/java/io/github/nyzeep/orchestella/ProvisionedLaunchRegressionTest.java`（新增）
- `.github/workflows/ci.yml`（新增回归门步骤、扩展 runtime 检查）
- `docs/reports/tickets/runtime-performance-08/red-green-evidence.md`（红/绿证据）
- 未改生产代码、T3 实现、构建逻辑或测试框架。

## 5. 重要设计决策

- **用同一 fixture 同时证明两件事**：凭据-only 路径 0 打开、全量路径 > 64 KiB。这样即使有人把 provisioned 分支改回全量嵌入，第一个用例也会在“打开次数”或“上限”上失败。
- **断言 timeline 的公开契约**：`rootfsBytes:0` 是 T1/T3 已承诺的可观测信号，不需要访问内部字段。
- **CI 分两步**：先跑命名回归门，失败信息直接指向 T3；再跑全量 app + runtime 检查，保持原有覆盖。
- **红跑用临时本地改动**：不作为提交内容；恢复后 `git diff` 只剩测试与 CI，满足“不改生产代码”。

## 6. 测试

- `./gradlew :app:testDebugUnitTest --tests '*ProvisionedLaunchRegressionTest' -Porchestella.repositories.useAliyunMirror=true --no-daemon`：绿，2 tests / 0 failures。
- 红跑（临时 `credentialsOnly=false`）：BUILD FAILED，2 tests / 1 failed，失败信息见证据文件。
- `./gradlew :app:testDebugUnitTest -Porchestella.repositories.useAliyunMirror=true --no-daemon`：105 suites / 822 tests / 0 failures / 0 errors / 0 skipped（T3 基线 820 + 新增 2）。
- `git diff --check`：通过。
- CI 实际运行：`not-tested`（未 push / 未开 PR）。

## 7. Benchmark / 实测数据

本票不涉及性能测量；fixture 的 256 KiB 仅用于让全量 segment 超过 64 KiB 上限，不是发行版大小或性能数据。

## 8. 风险

- 测试依赖 T3 的 `RootfsStreamOpener` 与 `CREDENTIALS_ONLY_MAX_BYTES`；若 T3 后续重构签名，需要同步更新本测试。
- 回归门只证明“没有全量嵌入”，不能证明凭据-only initrd 被 guest 正确消费；后者仍需真机/guest 验证。
- CI 新增 `:runtime:engine-qemu:check` 会运行 main-based JavaExec 测试；若 CI 环境缺少其依赖，PR 会失败。本地已通过，CI 实际执行 `not-tested`。
- 64 KiB 上限是硬编码常量；若合法小文件增长，需要同步评估，不能静默放宽。

## 9. 新发现

- 用“全量路径 segment > 上限”作为负控制，可以在不读生产代码内部状态的情况下证明 fixture 的敏感性。
- `ProvisionedLaunchRegressionTest` 可在纯 JVM 下覆盖 T3 的核心回退风险，不需要设备或 QEMU。

## 10. 对后续 ticket 的影响

- 后续任何修改 `QemuRuntimeBootstrapper` 的票必须保持本回归门为绿；如果确实要改快速路径，先更新 T3/T8 的证据与上限理由。
- 真实 CI 运行需要在人工复核后 push/开 PR 才能确认；本轮不执行。

## 11. Commit

- `32784cc` test(runtime): 增加已装配启动零 rootfs 回归门
- （本报告、红/绿证据与工单 Comments 随后续 docs commit 入库）

## 12. Ticket 状态

建议 `completed`（JVM 红/绿验证完成、CI 配置完成）；GitHub Actions 实际运行与真实设备为 `not-tested`，建议人工复核后开 PR 验证 CI。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录
