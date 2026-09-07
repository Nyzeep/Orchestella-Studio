# 编排报告：Ticket 26 Readiness Re-Audit

- 审计日期：2026-09-07（Asia/Shanghai）
- 背景 / scope：复核第一次 readiness audit 后 B1–B4 的分类和允许的治理修复；本报告不实施
  Ticket 26、不重开 Ticket 25，也不以文档替代真机验收。
- 原审计结论：docs/reports/orchestration/ticket-26-readiness-audit.zh-CN.md 为
  **NOT READY / needs-triage**，识别 B1 guest-assets ownership、B2 lifecycle overlap、
  B3 QEMU JNI supply chain、B4 真机/ADB readiness。
- 本轮最终 verdict：**FAIL**。
- branch / 基线 / commit：main / 83309ec（本轮开始前的 HEAD）/ 同批治理提交待创建；不以待
  创建 commit 冒充已提交。

## 1. Re-audit checklist

| PASS 条件 | 当前证据 | 结果 |
| --- | --- | --- |
| Ticket 25 已完成且未被重开 | Ticket 25 tracker 为 completed、已合入 main；本轮未触碰其 implementation/history | PASS |
| Ticket 26 scope / ownership 无歧义 | docs/reports/tickets/26/worker-scope-contract.zh-CN.md 规定 runtime/app、精确 guest-assets 例外、forbidden scope | PASS |
| Ticket 28 overlap 消除 | Ticket 26 只覆盖宿主进程存活时的正常转换记录与 UI/Activity 重建；Ticket 28 独占进程死亡后的记录解释、power/recovery/exception | PASS |
| QEMU JNI minimum supply-chain 固定 | QEMU_JNI_SUPPLY_CHAIN_POLICY.md 固定 source/toolchain policy、CI/ABI/APK/GPL/update gates | PASS |
| Architecture 无 pending decision | 现有 ADR-0002/0005/0009/0011/0014/0016 已覆盖；无 ACR trigger | PASS |
| 依赖/并行明确 | 25 → 26 保持 B0 serial；没有新 dependency、没有 27/28/30 Worker | PASS |
| acceptance / test strategy 可执行 | contract/JVM/guest/QEMU tests 已定义；物理真机验收仍不可执行 | **FAIL** |
| external hard blockers satisfied or non-hard | Ticket 26 的 arm64 真机与 Bridge AC 是硬 gate；设备/授权/可取证路径未证实 | **FAIL** |

## 2. Per-blocker disposition

| Blocker | 原因 | Resolution / evidence | 最终分类 |
| --- | --- | --- | --- |
| B1 | guest-assets 未授予 Ticket 26 | 窄 second-stage/agent/bridge ownership 已写入 contract 和 matrix；不涉及 Ticket 25 baseline | **RESOLVED** |
| B2 | 26/28 生命周期、状态与 stop/recovery 重叠 | 26 = 宿主进程存活时的 normal foundation；28 = App 进程死亡后的状态解释、power、cold restore、crash/log extension；test ownership 分离 | **RESOLVED** |
| B3 | 没有 QEMU source/package/license/CI path | official QEMU 11.1.1 initial pin、NDK/build/APK/GPL/update policy 与 fail-closed gates 已固化 | **RESOLVED** |
| B4 | 无可审计合格真机与部署/日志路径 | B4 research 确认这属于 Ticket 26 自身硬 AC；没有设备证据、未调用 ADB | **EXTERNAL BLOCKED** |

## 3. Scope / dependency / architecture changes

本轮只做现有工单和治理的澄清：

- Ticket 26 获得受限 guest-assets second-stage/bridge 与 QEMU pipeline ownership；
- Ticket 28 获得仅用于 power 的 guest agent extension，并独占 recovery/exception；
- matrix 明确上述例外与 QEMU build/license package domain；
- Ticket 26 改为 blocked，解除条件为 B4 HITL 准备。

特别地，Ticket 26 的旧“跨应用重启”措辞已改为“宿主进程仍存活时跨 UI/Activity 重建”。
它仍会写入正常状态转换记录，但不对 App 进程死亡后新进程看到该记录的语义或 UI 一致性作
断言；该对账和 Cold Restore 专属 Ticket 28。这与 ADR-0011、CONTEXT 中 Instance State /
Cold Restore 的定义一致，不是新架构决策。

没有新增 ticket、没有改变 25 → 26 或后续 DAG、没有修改 ADR/spec/CONTEXT、没有改变
Guest Bridge 命令集或 Exposure Whitelist。因此 **未触发 Architecture Change Policy**。

## 4. Environment dependency / HITL requirement

完成本票 acceptance 前，需要用户提供并授权测试一台未 Root 的实体 Android 设备，并能记录：

1. API ≥26、arm64-v8a、RAM ≥3 GiB、可用存储及设备身份；
2. 一条可重复、可取证的 APK 安装、日志与证据路径；
3. 若使用 USB ADB：已启用 USB debugging、已确认 RSA、可审计 Platform Tools 路径；
4. 若 Windows 设备要求 OEM USB driver：先完成精确路径/影响披露并得到用户批准。

项目内 SDK 尚无 platform-tools/adb.exe；这不推断整个系统没有 ADB，也不推断用户没有设备。
不允许将模拟器、Ticket 17 AVF prototype 或 Ticket 18 performance prototype 冒充上述真机功能验收。

## 5. Unfinished work, risks and next action

QEMU Android/NDK compilation、nativeLibraryDir executable behavior、TCG W^X/SELinux、dependency
closure、guest second-stage 和全部 product tests 都尚未运行；它们是未来 Worker 的受控
implementation/verification work，不是本 re-audit 的静态成功。

Ticket 26 的 current blocker 是 B4。等待外部条件满足后，Orchestrator 应先复核 device/host
disclosure、scope contract、supply-chain policy 和 Git state，再进行新的 readiness re-audit。
若该次审计 PASS，才可将状态改为 ready-for-agent 并创建唯一 Worker/branch/worktree。

## 6. 模型、委派、workflow 与 worktree 影响

- Orchestrator：当前治理会话；界面未向本报告公开本会话的精确 model/reasoning，故不臆测。
- B1/B2：scope_lifecycle_analysis child，以 domain-modeling + grilling 进行只读术语、边界和
  反例核对；原 dispatch 选择为 GPT-5.6 Sol / xhigh。
- B3：qemu_supply_chain_research child，以 research 核验官方 QEMU/Android/GNU 来源；原
  dispatch 选择为 GPT-5.6 Terra / max。
- B4：device_acceptance_research child，以 research 核验真机/HITL 可行性；原 dispatch
  选择为 GPT-5.6 Terra / max。
- 本轮文档差异还经 code-review 的 Standards / Spec 两个只读 reviewer 复核；它们指出的报告
  元数据、生命周期措辞和 AC 条数已在本次暂存修订中逐项处理。没有 reviewer 或 child 领取
  正式 ticket、修改 main、merge、push 或启动外部 runtime。
- worktree：仍只有当前 main worktree；未创建、删除或移动 Ticket 26 branch/worktree。

## 7. 修改文件

1. .scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md
2. .scratch/droidlayer-platform/issues/28-impl-stop-cold-restore.md
3. docs/agent-handoff/PARALLELIZATION_MATRIX.md
4. docs/agent-handoff/QEMU_JNI_SUPPLY_CHAIN_POLICY.md
5. docs/reports/tickets/26/worker-scope-contract.zh-CN.md
6. docs/reports/orchestration/ticket-26-blocker-resolution.zh-CN.md
7. docs/reports/orchestration/ticket-26-readiness-reaudit.zh-CN.md
8. docs/research/2026-09-07-qemu-android-jni-supply-chain.md
9. docs/research/2026-09-07-ticket-26-device-acceptance-feasibility.md

未修改 Ticket 25、ADR、spec、CONTEXT、dependency graph、产品代码或任何后续 implementation
ticket。

## 8. Verification, git and worktree

- 执行：tracker/policy/ADR/spec/CONTEXT/guest-assets/readiness reports/research 的文本证据核对，
  官方 Android/QEMU/GNU 来源核验，Git status/branch/worktree/log 检查、双轴 review finding
  复核及最终 diff hygiene。
- 未执行：Ticket 25 verification、Ticket 26 build/contract/JVM/QEMU/ADB/真机测试、任何
  external runtime 操作。
- branch / worktree：main / 当前唯一 main worktree；没有 Ticket 26 branch or worktree。
- commit：本报告与同批治理澄清由一个新的 docs(orchestration) Conventional Commit 一并记录；
  精确 hash 以提交后的 Git history 为准。

## 9. Final verdict

**FAIL**

B1、B2、B3 的治理与供应链 readiness 已解决；B4 的真机/HITL 硬门尚未满足。Ticket 26
状态应为 **blocked**，而非 ready-for-agent。

**Ticket 26 仍不得进入 implementation。**

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
