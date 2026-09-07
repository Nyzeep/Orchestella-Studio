# 编排报告：Ticket 26 Readiness Audit

- 审计日期：2026-09-07（Asia/Shanghai）
- 背景 / scope：Post-Governance Takeover 后，对 Ticket 26「第一垂直切片——创建 Alpine
  实例并进入 shell（QEMU 档）」进行只读接管与就绪性审计。本报告不重开、不修改或重跑
  Ticket 25；不启动 Ticket 26 或后续票的实现。
- 审计基线：`main @ 5b83fb0`（`docs(orchestration): 完善模型与代理编排治理规则`）
- Git / worktree：`main...origin/main` 无本地 ahead/behind 标记；工作树干净；仅有 `main`
  worktree。远端为 `git@github.com:Nyzeep/DroidLayer.git`。
- 最终判定：**NOT READY（保持 `needs-triage`）**。

## 1. 实际完成

### 1.1 接管事实核对

已读取并以当前仓库为准核对 `AGENTS.md`、八份 handoff / governance policy、tracker、
map、spec、`CONTEXT.md`、ADR-0001 至 ADR-0016、Ticket 25/26 全文、Ticket 25 scope
contract / implementation / review 报告、相关研究报告以及当前 Git 状态。

当前事实与早期 handoff 快照有正常的历史差异：早期文档记录的 `b556a49`、无产品代码、
Ticket 25 `needs-triage` 已被后续实际状态取代。当前 `main` 含 Ticket 25 的
`590cc17` merge commit（已验证为 `HEAD` 祖先）、Ticket 25 tracker 为 `completed`，且
`docs/reports/reviews/25/review-report.zh-CN.md` 的双轴结论为 `approve`。这只是历史快照
陈旧，不改写历史报告或 handoff。

Ticket 25 的可消费证据真实存在：受版本控制的可复现构建源、Provisioning、guest agent
基座、输入锁定、验证脚本与 CI 定义都在 `guest-assets/`；当前工作区还存在预期的
`guest-assets/out/Image`、`initramfs.cpio.gz`、`SHA256SUMS`。后者按 `.gitignore` 设计为
本地构建产物，不能被误当成已提交的 Android 分发资产。

### 1.2 Ticket 26 的已确认意图

Ticket 26 的产品目的明确：以 QEMU/TCG 软件虚拟化建立第一个 Full Linux 垂直切片，让
符合支持地板的 arm64 设备从 Alpine 目录项进入 root shell；同时首次建立策略中性的
Full Linux contract、实例 / 发行版 / 能力骨架，以及 Guest Bridge v1 的握手、协商、
per-VM token、ping 和终端控制面基础。

该意图与以下已批准约束一致，未发现需要重开 ADR 的冲突：

- ADR-0001/0002：Full Linux 的 QEMU 策略、策略中性契约与能力矩阵；
- ADR-0004：`minSdk 26`、`arm64-v8a`、RAM 至少 3 GiB 及模拟器诚实提示；
- ADR-0005/0006/0007：同一 pinned kernel、官方 Alpine tarball、guest 内 Provisioning、
  raw 稀疏单根盘与持久化语义；
- ADR-0009/0010：传输无关桥接、token 鉴权、控制/数据面分离与不扩大暴露面；
- ADR-0011/0012/0014/0016：生命周期边界、终端 only、Apache-2.0/零代码复制、单运行
  实例及四实体不合并。

因此下文的缺口是 ticket scope / ownership / 验收环境问题，不是产品架构变更；目前不触发
Architecture Change Policy。

### 1.3 依赖、并行与当前冲突

| 项目 | 证据 | 结论 |
| --- | --- | --- |
| 直接 blocker | Ticket 26 本文写为 `**Blocked by:** 25`；Ticket 25 当前为 `completed` | 已解除 |
| 资产前提 | Ticket 25 的构建源、验证证据及本地输出存在 | 已满足；分发/消费方式仍需在 26 packet 固化 |
| 原型前提 | Ticket 26 标注无硬依赖；Ticket 18 在 26 后才领取 | 不阻塞 26 |
| 其他正式工作 | 27–37 均为 `needs-triage`；17/18 为 `open`；无 `active` 实现票 | 无当前 Worker 冲突 |
| 并行 | 矩阵将 `25 → 26` 列为 B0 serial，并列为 serial-only | 必须串行；没有为 27/28/30 或其他票派工 |

Tracker 的 `Blocked by` 使用加粗正文而非 tracker 约定的裸字段，自动化解析可能漏掉
依赖。这是应在 scope/metadata 整理时一并修复的活动文档缺口；本审计通过人工核对确认
25 已完成，未据此改变 26 状态。

## 2. Ticket 26 就绪性核对

| 必要条件 | 结果 | 审计结论 |
| --- | --- | --- |
| Ticket 25 已完成 | 通过 | tracker、merge ancestry、实施/审查报告与资产均可验证。 |
| 阻塞依赖已解除 | 通过 | 唯一明示依赖为 25，现已完成。 |
| 架构输入可用 | 通过 | spec、CONTEXT、16 份 accepted ADR 与相关研究足以约束产品语义；无新架构冲突。 |
| 当前并行冲突 | 通过 | 没有 active Worker；26 仍按 B0 串行。 |
| What to build / 产品目标 | 基本清楚 | Ticket 正文和 spec 能确定垂直切片目标。 |
| Scope、owned modules、forbidden modules | **不通过** | Ticket 本身没有这些字段；矩阵的计划模块没有覆盖实现该票所必需的 `guest-assets`，且禁止范围只列 `engine-avf`。 |
| 与后续票的生命周期边界 | **不通过** | 26 的“优雅退出、再次启动、状态跨应用重启持久”与 Ticket 28 的停止、冷恢复、状态持久化 / `power` 命令没有可执行的切分。 |
| Acceptance criteria 可验证 | 部分通过 | 行为目标明确，但必要的 QEMU Android 打包、真实设备和生命周期边界尚未落成可运行的验证路径。 |
| Test strategy 可执行 | **不通过** | 只列契约/JVM/真机类别，未定义具体 seam、命令、装置和设备测试记录；当前 approved project toolchain 没有 `platform-tools/adb.exe`，也没有已确认的 arm64、RAM≥3 GiB 真机。 |
| 当前仓库足以支持实现 | **不通过** | Android skeleton 与 Ticket 25 基线存在，但桥接与持久二次启动所必需的 downstream guest-assets ownership、QEMU source/build/packaging 输入、设备验收准备尚未固化。 |
| 不可接受的宿主环境 blocker | **存在** | 真机是本票 Acceptance criteria 的硬要求；本轮没有假定用户持有或连接设备，也没有启动 ADB / 修改宿主环境。 |

## 3. 阻塞项与证据

### B1：必要的 guest-assets 变更没有被授予 Ticket 26

`PARALLELIZATION_MATRIX.md` 把 `guest-assets/` 仅列给 Ticket 25 创建，而 Ticket 26 的
owned modules 仅列 contract、instance、distro、capability、engine-qemu、bridge、ui、
terminal 初始骨架。可是当前 `guest-assets/initramfs/init` 每次启动都会格式化根盘、解包
tarball，并在 Provisioning 完成后关机；它尚不能作为已装配实例的二次启动路径。现有
README 也明确说明 OpenRC service scaffold 保持 disabled、实际 transport / byte stream、
所需 init packages 与 lifecycle 属 Ticket 26。

因而，要兑现“进入 shell”“优雅退出后再次启动”“数据保持”与桥接，Ticket 26 必须以
**明确、窄范围的下游扩展**修改 guest agent / initramfs 的二次启动和 transport 安装逻辑。
这不是重开或顺手重构 Ticket 25，却不能在未获得 ownership 的情况下由 Worker 擅自改动。

### B2：Ticket 26 与 Ticket 28 的生命周期责任重叠

Ticket 26 已要求实例优雅退出、再次启动和运行/停止状态跨应用重启持久；Ticket 28 又以
停止、冷恢复、异常终止、状态持久化和 `power` 命令为其主交付。没有明确切分时，26
Worker 无法知道是否可以实现 guest `power`、强杀后的冷恢复、异常标记、日志导出，或只能
做正常 QEMU 会话的最小 `RUNNING` / `STOPPED` 持久化。

这会直接影响契约 v0、实例状态机和共享 `tests/contract`，所以不能靠 Worker 临场判断。

### B3：QEMU Android 分发与许可证输入尚未成为可执行任务输入

Ticket 25 有 kernel/initramfs 的 reproducible pipeline，但其 scope 明确排除了 QEMU Android
打包与 jniLib。Ticket 26 要求 QEMU 作为 `jniLib` 执行，却尚无固定的 QEMU source/version/
checksum、NDK 构建入口、APK 资产与 SHA256 自检接线、对应源码/NOTICE 输出或其测试 seam。
这些是 ADR-0005/0014 已有约束下的 implementation planning，不需要新 ADR；但必须在
scope contract / dispatch packet 中写成可审查输入，不能由 Worker 无界扩张。

同样，计划模块名尚未映射到实际 `app/**` 源路径；没有这个映射无法有效审计 Worker diff。

### B4：真机验收环境尚未就绪

本票的首条 Acceptance criterion 与桥接验收均要求真实 arm64、RAM≥3 GiB Android 设备。
项目内 SDK platform 与 build-tools 存在，debug APK 也存在；但项目批准的本地 SDK 中没有
`platform-tools/adb.exe`，仓库和既有报告没有确认已连接的合格设备或 Ticket 26 的设备
测试记录。本审计没有调用 ADB，以避免启动 host 工具或造成未披露的用户目录写入。

这不表示系统任意位置绝对没有 ADB，也不把不存在的真机测试写成失败；它表示当前 Agent
不能证明本票硬验收可执行。补齐时必须遵守 `HOST_ENVIRONMENT_POLICY.md`：优先使用项目
approved root，任何 tool-mandated user-level 写入先做完整 disclosure，不触碰 Registry、
system PATH、服务、驱动、防火墙或系统虚拟化设置。

## 4. 使其可重新审计的最小修复清单

以下是 ticket / dispatch 范围澄清，不授权实现：

1. 为 Ticket 26 增补一个正式 scope contract，或等价地修订其活动 tracker metadata，明确
   `Why`、in-scope、explicit non-goals、裸 `Blocked by: 25`、实际路径 ownership 与报告
   行政例外。
2. 将必要的下游 `guest-assets` 例外限定到为二次启动、已装配根盘、Guest Agent transport
   安装与 v1 protocol 所需的文件；明确不得重跑、重开或重构 Ticket 25 的已验收 build
   baseline，也不得提交 `guest-assets/out/` 或 `.work/`。
3. 明确 26/28 的界线。例如，26 只能交付正常创建 / 启动 / shell / 正常停止后的最小数据
   保持与 `RUNNING`/`STOPPED` 基线；28 独占 `power` 命令、强杀后的冷恢复、异常标记、
   诊断导出及其扩展测试。若选择不同切分，必须同时更新两票与矩阵，不能让两个 Worker
   共享同一未定义状态机。
4. 在 packet 中固定 QEMU 供应链、Android jniLib packaging、APK 内核/initramfs 校验、
   GPL 对应源码 / NOTICE 义务、终端组件的 Apache-compatible 选择边界，以及 contract / JVM
   test seams。这些均受已批准 ADR 约束，不扩大暴露面。
5. 提供可执行的验证计划：明确 Gradle/JVM 与 contract test 命令、真实设备冒烟清单、设备
   条件、日志/截图证据位置及失败分类。准备 project-scoped platform-tools 与一台符合支持
   地板的设备前，不得声称真机 Acceptance criterion 可以验收。
6. 完成上述 scope 与环境准备后，重新执行本 readiness audit。仅在所有项通过后才可将状态
   变为 `ready-for-agent`、形成 dispatch packet，并创建唯一的 `ticket/26-<short-name>`
   branch/worktree Worker。

## 5. 未完成内容

- 未修改 Ticket 26 状态、正文、依赖图、ADR、spec、CONTEXT 或矩阵；当前状态保持
  `needs-triage`。
- 未创建 Ticket 26 branch、worktree、Worker、Child、dispatch packet 或 implementation
  commit；未启动 27、28、30 或其他后续实现。
- 未运行 Ticket 25 构建、QEMU smoke、复现验证或其 review；也未运行 Ticket 26 产品测试、
  VM/container/vctl/ADB 操作。

## 6. 修改文件

- `docs/reports/orchestration/ticket-26-readiness-audit.zh-CN.md`（本报告）。

## 7. 模型、委派、workflow 与 worktree 影响

- 执行者：Codex Orchestrator（当前会话；未创建 Worker 或 Child）。
- 模型 / reasoning：当前会话的具体模型与 reasoning 档位未由调度接口向本报告暴露；未为
  任何下游 session 选择或声明模型。
- workflow：只读 repository takeover + readiness audit；没有进入 `implement`、`tdd`、
  `code-review`、prototype 或 architecture-change workflow。
- worktree：未创建、删除或移动任何 worktree；无 cleanup 动作。

## 8. 测试与验证结果

本审计执行的验证均为只读：`git status --short --branch`、`git branch -vv`、`git log`、
`git worktree list`、`git remote -v`、Ticket 25 merge ancestry、tracker / policy / source
读取、受控文件存在性检查及 ignored-output 归类。审计基线与写入本报告前的 `git status`
均为 clean；本报告是本次审计唯一的仓库变更。报告 diff 的 `git diff --check` 通过。

没有把静态文件存在性、历史 Ticket 25 结果、JVM skeleton build 或本地 APK 误报为 Ticket 26
验收；Ticket 26 的契约、JVM 和真机测试均为未运行。

## 9. 风险与后续动作

在 B1–B4 未解决时派工，会迫使 Worker 在共享 guest-assets、生命周期契约、QEMU 供应链或
宿主环境上自行做越界决定，违反 Worker Contract 与 Architecture Change Policy 的精神。
最小安全后续动作是先完成 scope / metadata / environment 的澄清，然后再做一次只读
readiness audit；在此之前，B0 串行纪律继续禁止任何后续 implementation ticket。

## 10. 越界与 Architecture Change Policy 检查

本次未改变任何产品承诺、ADR、spec、CONTEXT、暴露面白名单、桥接命令集、Ticket 25 实现或
历史报告。发现的问题可以在既有 ADR 内以 ticket scope 和执行准备消解，未发现证据要求
创建 ACR 或重开 architecture decision。

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
