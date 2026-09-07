# 编排报告：Ticket 26 Readiness Blocker Resolution

- 背景 / scope：在第一次 Ticket 26 readiness audit 为 FAIL 后，只处理 B1–B4 的分类、证据、
  active ticket/governance scope 澄清与 re-audit 输入；不实施 Ticket 26，不修改 Ticket 25。
- 基线：main @ 83309ec（开始本轮时）；当前工作只在 Orchestrator worktree 的允许文档范围内。
- 结论摘要：B1、B2、B3 已通过受限 scope/policy 解决；B4 是尚未满足的 EXTERNAL BLOCKED。
  因此本轮不能派工。

## 1. Blocker 分类与结论

| ID | 类型 | 根因 | 仓库内可解决 | 用户决策 / 外部资源 | Architecture Change | scope 调整 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | scope ambiguity；implementation prerequisite | Ticket 26 的二次启动与 Bridge v1 必须扩展 Ticket 25 所建 guest-assets，但矩阵未授予下游 ownership | 是 | 否 | 否 | 是：窄 guest-assets 例外 | **RESOLVED** |
| B2 | scope / lifecycle ambiguity | 26 与 28 同时出现停止、状态持久化、重启与恢复措辞，未切分正常与异常路径 | 是 | 否 | 否 | 是：26/28 lifecycle 边界与 test ownership | **RESOLVED** |
| B3 | documentation/policy gap；dependency prerequisite | 没有 QEMU source pin、Android/NDK build/ABI/APK、license/provenance 或 CI gate 的可审计输入 | 是 | 否 | 否 | 是：QEMU supply-chain policy、受限 pipeline ownership | **RESOLVED** |
| B4 | external/HITL blocker；environment blocker | AC 明示 arm64/RAM≥3 GiB 真机与真机 bridge；项目内无可审计 Platform Tools 路径、合格设备或测试记录 | 否 | 是：设备、测试授权及可取证链路 | 否 | 是：状态转为 blocked，记录解除条件 | **EXTERNAL BLOCKED** |

“RESOLVED”仅表示 readiness 输入已经清楚、可由受控 Worker 依既有 ADR 实现和验证；不表示
产品已经实现或未经运行的 QEMU/guest behavior 已被证明。

## 2. B1 — guest-assets ownership

### 根因与证据

guest-assets/initramfs/init 当前每次启动会格式化 Root Volume、解包 tarball、写 marker 后
关机；它不能保留第二次启动的数据。Guest Agent 只完成一次握手后退出，OpenRC scaffold
也明确没有 transport byte stream。guest-assets/README.md 与 Ticket 25 实施报告均把
transport、terminal/ping、所需 init packages 与 second-stage lifecycle 留给 Ticket 26。

矩阵原先仅将 guest-assets 写为 Ticket 25 创建，且 Worker Contract 禁止触碰未拥有模块。
所以这是本票的下游 integration layer，不是 Ticket 25 未完成或需要 reopen 的实现。

### 处理与验证

已在 Ticket 26 scope contract 和矩阵中授予只完成 second-stage/agent/bridge 的精确文件例外，
同时继续禁止 kernel、输入基线、Ticket 25 report/tracker、ignored outputs 和无关重构。
未来 Worker 的验证是两次启动 sentinel、guest agent process/smoke 及正常关机路径；任何
kernel/tarball/build-baseline 变化均为越界。

## 3. B2 — Ticket 26 / 28 lifecycle split

ADR-0011 已定义进程死亡、冷恢复与异常终止；ADR-0016 已定义单运行实例；ADR-0002 禁止将
QEMU 专属类型漏进契约。因此不需要新架构选择。

Ticket 26 只创建 Instance/Root Volume/normal STOPPED ↔ RUNNING 的转换记录、首次
Provisioning、正常 guest shutdown、二次启动、Bridge baseline，并且仅在宿主进程仍存活时
验证 UI/Activity 重建对正常状态的显示。Ticket 28 独占 host-initiated power、shutdown
ack/timeout、App 进程死亡后对旧状态的解释、强杀后的 stale-state reconciliation/cold restore、
VMM crash、异常标记和日志导出。对应契约测试被分为 baseline 与 recovery/power 文件域。

以既有 CONTEXT/ADR 术语进行的边界压力测试给出一致结论：guest 自主正常关机后同根盘再次
启动属于 Ticket 26；宿主进程存活时重建 UI 也属于 Ticket 26；杀死宿主进程后看到旧 RUNNING
记录、对账并一键恢复则只属于 Ticket 28。普通二次启动、UI/Activity 重建、Session exit 都
不得误称为 Cold Restore 或 Instance stop。

此切分已写入 Ticket 26 contract、Ticket 28 wording 和矩阵。它避免把 Session exit 混同
Instance stop，避免把普通二次启动误称 cold restore。

## 4. B3 — QEMU JNI supply chain

docs/research/2026-09-07-qemu-android-jni-supply-chain.md 核验了 Android W^X、
nativeLibraryDir/ABI、16 KB page-size、QEMU release/signature 和 GPLv2 一手来源。
新增 active policy 固定 initial QEMU 11.1.1 official archive、SHA-256、signature
fingerprint、tag/commit、NDK r28c 与 immutable Linux builder base。

政策还要求机器 lock、无浮动 build 输入、two-clean-build reproducibility、AArch64/closure
检查、explicit installer extraction、nativeLibraryDir path、runtime artifact hash/tamper
rejection、16 KB/APK signature、arm64 boot-to-shell、GPL source/NOTICE/written-offer 及更新
重跑。Android/NDK 构建、main ELF execution、TCG W^X/SELinux 和 dependency closure 仍是
Ticket 26 的 fail-closed tests；它们不再是可由 Worker 临场决定的供应链空白。

## 5. B4 — physical acceptance

Ticket 26 本身要求真机安装到 shell 及真机 Bridge 正/负 token 测试；Ticket 18 的 QEMU
performance baseline 和 Ticket 17 的 AVF research 都不能替代这两个功能 AC。
docs/research/2026-09-07-ticket-26-device-acceptance-feasibility.md 记录：项目 approved
SDK 中没有 platform-tools/adb.exe，也不存在经证实的合格设备/授权/测试记录。这不声称
系统其他位置没有 ADB，也不声称用户没有设备。

解除条件是用户提供并允许测试一台实体、未 Root、API ≥26、arm64-v8a、RAM ≥3 GiB 且有
足够存储的设备，并提供可重复、可取证的部署/日志路径。若要安装 Platform Tools、使用
ADB、或安装 OEM driver，必须先按 Host Environment Policy 对精确路径和影响披露；本轮没有
调用 ADB、连接设备、安装工具或修改宿主设置。

## 6. Scope、dependency 与 Architecture Change 检查

- 更新了 active Ticket 26/28 wording 与 PARALLELIZATION_MATRIX.md，没有新增或秘密改变
  dependency graph；25 → 26 仍为 B0 serial。
- 新增 QEMU policy 和 pre-dispatch scope contract；它们执行 ADR-0005/0014，不改 ADR/spec/
  CONTEXT，也不扩张 bridge 暴露面。
- 未修改 Ticket 25 implementation、historical report、review、status 或 commit history；
  未启动 27/28/30 implementation。
- 对 B1/B2 使用 domain-modeling 的实体/生命周期术语核对，并做基于既有 ADR 的 grilling 式
  边界压力测试。因 accepted ADR 已给出全部裁决，没有遗留需要用户在设计树中选择的
  architecture frontier。

## 7. 委派、模型与 workflow

| Child | 范围 | workflow | 模型 / reasoning | 修改权限 |
| --- | --- | --- | --- | --- |
| scope_lifecycle_analysis | B1/B2 guest-assets、bridge、26/28 lifecycle 对比 | domain-modeling + grilling | GPT-5.6 Sol / xhigh | 只读 |
| qemu_supply_chain_research | B3 官方 QEMU/Android/GPL source 与最小 policy | research | GPT-5.6 Terra / max | 仅新增其 research report |
| device_acceptance_research | B4 真机/ADB/HITL 可行性 | research | GPT-5.6 Terra / max | 仅新增其 research report |

最大委派深度为 1；没有 Child 领取正式 ticket、修改 tracker/ADR/spec/CONTEXT、merge、push 或
启动外部 runtime。

## 8. 未完成内容、风险与后续动作

未实现或测试任何 Ticket 26 产品路径；未创建 Worker、branch、worktree 或 Dispatch Packet。
QEMU policy 中的 fail-closed gates 也尚未运行，不能被描述为构建/真机成功。

下一步不是实现：保持 Ticket 26 blocked，等待 B4 的 HITL 条件。设备条件出现后，先核对
Host Environment Policy、执行一次 re-audit；只有 PASS 才能派工。

## 9. 修改文件与验证

本阶段实际修改文件如下：

1. .scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md
2. .scratch/droidlayer-platform/issues/28-impl-stop-cold-restore.md
3. docs/agent-handoff/PARALLELIZATION_MATRIX.md
4. docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md
5. docs/reports/tickets/26/worker-scope-contract.zh-CN.md
6. docs/reports/orchestration/ticket-26-blocker-resolution.zh-CN.md
7. docs/reports/orchestration/ticket-26-readiness-reaudit.zh-CN.md
8. docs/research/2026-09-07-qemu-android-jni-supply-chain.md
9. docs/research/2026-09-07-ticket-26-device-acceptance-feasibility.md

验证为只读 evidence 核对、官方一手资料链接核验、双轴 review finding 复核、git diff --check
和状态/branch/worktree 检查；没有虚构产品测试。

## 10. Commit / branch / worktree

- branch：main
- commit：本报告与同批治理澄清由一个新的 docs(orchestration) Conventional Commit 一并记录；
  精确 hash 以提交后的 Git history 为准
- worktree：仅当前 main worktree；未创建、删除或移动 worktree

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
