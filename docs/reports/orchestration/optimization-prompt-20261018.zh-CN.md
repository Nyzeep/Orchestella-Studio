# Orchestella 维护与优化 Prompt（2026-10-18）

> **用途**：记录本回合的维护性优化，并给出可直接交给下一个 Agent/会话的中文 Prompt。
> **基线**：`Orchestella_dev` main `b0a167c` + 本回合改动；源码 0.5.3 / code503；公开安装包仍为 v0.4.5 开发预览版。

## 一、本回合目标

在不改变产品契约、不触碰用户实例与发布物的前提下，完成一次维护性优化：

1. 清理可再生成的缓存、重复安装包和损坏临时文件。
2. 对 `docs/` 做分类整合，归档被取代的阶段报告与旧交接，更新入口文档到最新调研结论。
3. 从最新审计中选取一个可在纯 JVM 闭环的 P0 问题做代码修复。
4. 用测试和构建验证修复，不把“工单存在”或“单元测试通过”当成真机验收。

## 二、已完成内容

### 1. 工作区清理（约 358 MB）

删除或归档的内容：

- `Orchestella_dev/.gradle/`、`.kotlin/`：项目级缓存，可重建；删除前已停止 Gradle daemon。
- 根目录 `gradle-8.13-bin.zip`：与 `Orchestella/tools/preserved-development-toolchains/...` 同 SHA-256，wrapper 缓存已解压。
- `temp/jdk17.zip`：未被任何脚本/文档引用，当前构建使用 `D:\JDK17`。
- `temp_ubuntu-base.tar.gz`：不是有效 gzip/tar，无引用。
- `stitch_orchestella_mobile_ui_concepts.zip`：与保留的同名目录逐文件一致。
- `STRATEGY_HANDOFF_NEW_PHASE.md`：v0.4.0 时代战略交接，移入 `Orchestella/archive/historical-input/`。

**未清理**：`Orchestella/` 下的 artifacts（33G）、test-runtimes（20G WSL）、tools（4.9G SDK/工具链/签名密钥），全部历史 APK，`app/src/main/jniLibs`、`assets/Image`、`resources`，既有 `.log` 与原始证据。它们受项目证据/工具链/rootfs/WSL 约束保护。

### 2. 文档分类与归档（54 份历史文档）

新增/更新：

- `docs/README.md`：当前状态、路线、研究依据、文档分类、证据约定。
- `docs/reports/README.md`：当前报告索引。
- `docs/adr/README.md`：18 份 ADR 索引，标注 ADR-0015 缺失与 ACR-02 未决。
- `docs/agents/triage-labels.md`、`docs/agents/domain.md`：补齐 `AGENTS.md` 原先悬空的入口。
- `docs/agent-handoff/archive/README.md`、`docs/research/archive/README.md`、`docs/reports/archive/README.md`：归档说明。

归档位置：

- `docs/agent-handoff/archive/2026-10-avf/`、`2026-10-phase5/`：被取代的交接与 Prompt。
- `docs/research/archive/2026-10-phase5/`：Phase 5 专项研究。
- `docs/reports/archive/2026-10-phase5-quality/`、`2026-10-v045/`、`2026-10-avf-history/`：阶段报告、工单、审查的首轮/旧轮。

同步修复了 `.scratch` 三个 map、`artifacts/quality-phase5-integration/verification-summary.json` 与 `tools/phase5/make-local-patch.py` 的引用。

**注意**：`/docs/` 仍被 `.gitignore` 忽略；经审查的文档必须显式 `git add -f`。本回合已将 2026-10-18 优化方案与两份 2026-10-15 支撑研究显式纳入版本控制。

### 3. 入口文档更新到最新调研

- 统一版本事实：源码 0.5.3 / code503，公开包 v0.4.5；0.5.3 为 747 tests / 98 suites、lint 0 Error / 748 Warning；v0.4.5 的 713 tests 不再与 0.5.3 混写。
- 明确发布信任：v0.4.5 与 0.5.3 验证包均为 Android Debug 证书；生产签名与数据迁移是下一公开版本 P0。
- 明确 AVF 边界：真实 AVF Guest 成功样本 0；当前 ROM protected-only；AVF 框架不等于 pKVM；Gunyah 节点存在。
- 明确 QEMU 定位：唯一有真实设备证据的完整 Linux 路径，但停止收敛、gvproxy 收尾、跨 Activity 单实例互斥仍在收敛中。
- 明确 16 KB 两层状态：ELF/APK 对齐已验证，16 KB 真机运行未测。
- 明确 Android 17 窗口：API 37 已稳定，targetSdk 36，2026Q4–2027H1 适配。
- 更新 `CONTEXT.md` 的硬件虚拟化、Protected/Non-protected、隔离承诺、显示通道与 16 KB 词条。
- 在 `protected-avf-near-term-plan-20261017.zh-CN.md` 头部标注：其总体排序已被 2026-10-18 方案重排。

### 4. 代码优化：P0-3 跨 Controller 单实例竞态

**问题**：`InstanceLifecycle.FileStore` 的 `synchronized` 是实例级；每个 Activity/Controller 各建一个 FileStore。`Controller.start()` 的 `load → list → transition` 没有跨 Controller 共同锁，两个不同实例可能同时通过“没有 RUNNING”检查，最终写入两个 RUNNING。

**修复**：

- `Store` 新增 `default Object stateMonitor()`；自定义 Store 默认仍使用实例自身，保持兼容。
- `FileStore` 以 canonical 目录为键维护进程级 monitor；同一目录的所有 FileStore 共享同一把锁。
- `FileStore.save/load/list/delete` 改为在共享 monitor 内执行。
- `Controller.start/resume/coldRestore` 在完整检查+转换区间持有共享 monitor；`transition` 以及 `suspend/forceStop/recordCrash/reconcile/resetToStopped/updateHardwareConfig/updateGuestPassword` 的读改写也在同一锁内。
- 不新增常驻进程、VM owner、文件格式或产品契约；不覆盖 clone/import 等长时间磁盘 I/O。

**测试**：新增 `runtime/instance/src/test/.../ConcurrentStartContractTest.java`，4 项：

1. 同 canonical 目录的 FileStore 共享 monitor，不同目录隔离。
2. 两个 Controller 并发启动不同实例：恰好一个成功，一个 `SingleRunningInstanceException`，最终恰好一个 RUNNING。
3. 两个 Controller 并发 `resume` 两个 SUSPENDED 实例：同样的单 RUNNING 不变量。
4. 两个 Controller 并发 `coldRestore` 两个 INTERRUPTED 实例：同样的单 RUNNING 不变量。

## 三、验证记录

在 ASCII 短路径 `W:` 下执行（`JAVA_HOME=D:\JDK17`、`ANDROID_HOME=D:\AndroidSDK`）：

```bash
./gradlew :runtime:instance:test --tests io.github.nyzeep.orchestella.runtime.instance.ConcurrentStartContractTest --offline --no-daemon
./gradlew :runtime:instance:check :app:testDebugUnitTest --offline --no-daemon
```

结果：

- 新增并发契约测试：4/4 通过。
- `:runtime:instance:check`：通过（含 normal lifecycle、recovery、storage、delete、clone、sparse disk、user config、power controller、snapshot、guest feature 契约）。
- `:app:testDebugUnitTest`：**747 tests / 98 suites，0 failures / 0 errors / 0 skipped**。
- 构建期间只有既有的 AndroidManifest `extractNativeLibs` 警告与 Kotlin 弃用警告，没有新增错误。

## 四、未完成项（按优先级）

1. **P0-1 生产签名与数据迁移**：生成离线 release keystore，移除默认口令与 `.toolchains` 回落；设计 v0.4.5 导出 → 新包导入的实例保留路径；发布包必须用 `apksigner` 验证不是 `CN=Android Debug`。
2. **P0-2 QEMU/gvproxy 生命周期收敛**：引入单一 `RuntimeProcessSupervisor`，拥有 QEMU、gvproxy、control/terminal/display/proxy socket；`stop(graceMs)` 依次 `destroy → 等待 → destroyForcibly → 关闭通道 → 停网关`；消除 `completeExit` 与 watcher 的收尾顺序竞态。
3. **P0-4 终端输入可靠性**：同一 screen 重绑不 reset InputConnection；`onCreateInputConnection` 只在 sink/screen 变化时重建；Android 17 下显式 `showSoftInput`；右 Alt/AltGr/死键按 `KeyCharacterMap` 处理。
4. **P0-5 CI 与发布门**：把 `:runtime:*:check`、`:app:lintDebug`、`:app:assembleDebug` 与 16 KB ELF/APK 检查纳入 CI；修复 guest-assets CI；PR 触发 guest 相关检查。
5. **P0-6 GPL/NOTICE 修正**：`guest-assets/GPL-SOURCES.md` 的 Apache-2.0 表述、`NOTICE.GUEST-RUNTIME` 的 musl 版本、`NOTICE.QEMU` 引用文件。
6. **P2 AVF**：按 3–4 周时间盒做 24 有界 protected probe 与 26 纸面 label 门；若 `app_data_file` 被拒，标记该 ROM protected 产品化 `resolved-negative`，不进入 G2/G3。

## 五、可直接使用的中文 Prompt

> 你在 `Orchestella_dev` 仓库工作，源码基线为 0.5.3 / code503，公开安装包仍为 v0.4.5 开发预览版。请先阅读 `AGENTS.md`、`docs/README.md`、`docs/reports/orchestration/next-optimization-plan-20261018.zh-CN.md` 和 `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md`，再开始任何操作。
>
> 当前已完成一次维护性优化：清理了可再生成的缓存与重复/损坏临时文件；将 54 份被取代的阶段文档归档到 `docs/reports/archive/`、`docs/agent-handoff/archive/`、`docs/research/archive/`；更新了 README/docs/AGENTS/CONTEXT 到最新调研结论；在 `runtime/instance/.../InstanceLifecycle.java` 中把 `FileStore` 改为按 canonical 目录共享 monitor，并让 `Controller` 在 `load → list → transition` 全程持锁，修复跨 Controller 单实例竞态。新增 `ConcurrentStartContractTest` 4 项，`:runtime:instance:check :app:testDebugUnitTest` 全部通过（747 tests / 98 suites）。
>
> 下一任务只做 **P0-2 QEMU/gvproxy 生命周期收敛**，不要同时改签名、AVF、GPU 或发行版。要求：
> 1. 先读 `QemuProcessLauncher.java`、`QemuInstanceLauncher.java`、`NetworkGatewayDaemon.java`、`InstanceLaunchSequence.java` 与现有 fake/测试，确认进程和网关的所有权缺口；不要只加一行 `destroyForcibly()`。
> 2. 设计一个最小的 `RuntimeProcessSupervisor`（或等价 seam），统一拥有 QEMU 进程、gvproxy、control/terminal socket；`stop(graceMs)` 必须有界，顺序为 `destroy → 等待 → destroyForcibly → 等待 → 关闭通道 → 停网关`；`NetworkGatewayDaemon.stop` 必须等待退出且幂等；启动前清理已死亡的 `LIVE` 条目。
> 3. 修正 `InstanceLaunchSequence.awaitGuestExit` 与 QEMU watcher 的收尾顺序：元数据进入 STOPPED / 允许重启之前，网关必须已经释放固定端口；如果不收敛，保留 `STOP_UNCONFIRMED` 与盘 lease，不要假装已停止。
> 4. 用纯 JVM fake 进程覆盖：忽略 `destroy()` 的进程、强制终止后退出、网关等待顺序、同端口立即重启、启动失败清理。不要依赖真机才能验证核心逻辑。
> 5. 运行 `:runtime:instance:check`、相关 runtime 模块测试与 `:app:testDebugUnitTest`；报告实际命令与结果，区分已验证、未运行、需真机。
> 6. 不引入新的常驻进程、VM owner、特权 IPC、签名或发布操作；不改 accepted ADR；不清理 `Orchestella/` 证据、APK、WSL、SDK 或既有 `.log`。
>
> 完成后更新 `docs/reports/` 下的报告与 `docs/reports/README.md`，用中文说明改动、验证边界和剩余风险；除非用户明确要求，不要创建 Release 或上传安装包。

## 六、约束

- 不把单元测试、假 SDK、合成 FD 通过当作真机 Guest 或性能通过。
- 不继续用 Debug 证书发布“正式版”；不把 protected 写成默认能力。
- 不在 P0 完成前新增发行版、替换 kernel/QEMU、迁移 qcow2、重做 GPU/桌面。
- 不清理 `Orchestella/artifacts`、`test-runtimes`、`tools`、历史 APK、`app/src/main` 运行资产与既有 `.log`。
- 不整体 `git add -f docs/`；只显式添加经过审查的文档。
