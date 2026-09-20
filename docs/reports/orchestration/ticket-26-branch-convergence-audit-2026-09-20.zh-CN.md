# 编排报告：Ticket 26 分支收敛审计（2026-09-20）

- 背景 / scope：只读审计 main 与三个已注册 Ticket 26 branch/worktree 的收敛前置条件；不读取 ticket-26-follow-up WIP 内容或 canonical 冻结 delivery 草案内容，不修改任何 Ticket 26 branch、worktree、WIP、delivery 草案、ADR、spec、CONTEXT 或产品代码。
- workflow / risk / 模型：code-review；Risk 3；推荐 GPT-5.6 Sol / High；当前运行时未提供该路由。根据用户当前任务 prompt 中“若环境无法提供，记录实际 fallback，不得自行降低风险结论”的直接条件授权，实际采用 GPT-5.6 Terra fallback；风险结论未降低，也不授权越界修改。
- branch / commit / Git 基线：本报告的 main 比较基线固定为 `ticket-26-governance-reconciliation-2026-09-20.zh-CN.md` 文件顶部的 branch/commit/精确基线项及第 1 节记录的 2026-09-20 Phase 0 snapshot；其 exact main/origin refs 及 0 behind / 5 ahead 是 canonical 事实。本报告将在双轴审查通过后的本地中文 Conventional Commit 中提交，当前未 push。为遵守本报告不粘贴 hash 的数据边界，本处不重复 hash、完整 commit 清单、完整 diff、绝对路径、日志、token 或 artifact 内容。
- 审计结论：尚不具备只保留 canonical worktree 的条件。

## 1. 共同祖先与双向独有提交

| 分支 | 与 main 的共同祖先（脱敏主题级描述） | main 独有 / 分支独有 | 当前摘要 |
| --- | --- | ---: | --- |
| ticket-26-original | 2026-09-07 的 Ticket 26 readiness governance checkpoint | 9 / 80 | clean；无 upstream；ignored 12 项（Gradle cache、build intermediates、toolchain）；原始线性实施历史。 |
| ticket-26-follow-up | 2026-09-19 的 device-gate dispatch governance checkpoint | 2 / 86 | 9 项 tracked WIP、0 untracked；无 upstream；ignored 未查询（用户直接 ticket-26-follow-up WIP 边界）；内容未读取。 |
| ticket-26-canonical | 2026-09-19 的 device-gate dispatch governance checkpoint | 2 / 102 | 7 项 tracked 修改、4 项 untracked；无 upstream；ignored 9 项（Gradle cache、build intermediates）；冻结 delivery 草案内容未读取。 |

这些计数说明三个分支均有 main 尚未包含的价值；canonical 命名不等于其余两条线已经被审查、迁移或可安全删除。

## 2. 功能域迁移矩阵

| 功能域 | 审计到的提交/报告级事实 | 结论 | 仍需独立审查的内容 |
| --- | --- | --- | --- |
| runtime / bridge | 受控 canonical 报告声称原分支 80 个线性提交已按序迁入，并记录 API 26 token、credential migration 与 QEMU lifecycle；本会话未完成逐提交/逐文件 mapping。 | 尚待独立审查 | source-to-canonical 映射、canonical HEAD 的 Linux/QEMU 动态验证、token/lifecycle 安全边界。 |
| guest assets | 受控报告记录 second-stage、Guest Agent、PTY/virtio-serial 基线与后续 credential-sync；本会话未完成逐提交/逐文件 mapping。 | 尚待独立审查 | source-to-canonical 映射、Linux/QEMU full verify 与 guest-to-host/lifecycle 行为。 |
| App 接线 | canonical 报告记录 LocalSocket、controller、最小实例 UI 与 terminal snapshot 接线及限定双轴 ACCEPT；本会话未审查实现迁移证据。 | 尚待独立审查 | canonical App 范围与完整 native artifact 存在时的 Android/真机行为。 |
| 报告 | canonical 含收敛与复审记录，原分支的 2026-09-18 动态证据仍须按历史 HEAD 引用；本会话未完成报告映射。 | 尚待独立审查 | source-to-canonical 报告映射与当前 canonical 范围的双轴 review。 |
| CI | 历史原分支存在 CI 实现与成功记录；当前 canonical HEAD 尚未以相同动态 gate 重跑，且首次 native CI 历史上曾在 Python 前置条件阶段失败。 | 尚待独立审查 | canonical 的 fail-closed CI、Linux/QEMU full verify、native APK delivery 与可复现证据。 |
| delivery 草案 | canonical 有 7 项 tracked 修改与 4 项 untracked supporting files；follow-up 另有 9 项 tracked WIP。内容均不在本会话读取范围。 | 仅存在于未提交 WIP | GPL release delivery、来源公开地址、owner、三年保留机制及草案的独立审查。 |
| follow-up 未提交工作 | follow-up 有 9 项 tracked WIP；内容不在本会话读取范围，也未被 canonical 审查证明覆盖。 | 仅存在于未提交 WIP | 逐文件 ownership、scope、敏感性与是否应保留/提交/拆分的独立审查。 |

本会话只拥有分支关系计数、受控报告与有限状态摘要，未完成逐提交/逐文件 source-to-canonical mapping；因此 runtime/bridge、guest assets、App 接线和报告均保守标为尚待独立审查，而不把分支计数或历史报告当作迁移证明。没有功能域能仅凭本会话允许的证据安全标为仅在 follow-up；canonical 未整体采纳 follow-up 已提交工作，剩余内容仍须独立处置。

## 3. 为什么不能只保留 canonical

1. follow-up 的 9 项 tracked WIP 未审查、未提交，且可能涉及 credential、instance metadata 与 QEMU lifecycle；本会话既未读取也不得丢弃。
2. canonical 自身有冻结 delivery 草案与 untracked 文件；它不是干净、收口完成的唯一候选。
3. 原分支仍有 80 个 main 未包含的线性提交，并承载历史 CI/QEMU 证据；尚未完成逐域的可逆映射与 canonical 同等动态验证。
4. main tracker 仍为 ready-for-agent，未确认 active Worker、canonical owner 或获批准的实施 worktree。
5. terminal relay、GPL delivery 与 Android/native/真机验收均未收口；不能以 worktree 清理替代技术或合规验收。

## 4. 最小且可逆的收敛前置条件

1. 由用户/Orchestrator 为 follow-up 的 9 项 WIP 指定独立 owner 与只读审查 packet；先形成路径/模块/敏感性摘要，再决定提交、拆分或继续保留，期间不 reset/clean/stash/delete。
2. 为 canonical 的 7 项 tracked 修改和 4 项 untracked supporting files 指定 delivery-draft 审查与 GPL 决策；未有书面 disposition 前继续冻结。
3. 建立 source-to-canonical 的可审查迁移矩阵：原分支 80 个提交按功能域映射到 canonical，注明未迁移、重建或待验证项；不以 cherry-pick/merge 替代审查。
4. 对 canonical 已提交实现运行独立 Standards + Spec review，并为 Linux/QEMU、APK/native 与 arm64 真机验证建立单独 evidence gate；历史原分支 CI 不得自动继承。
5. 在 main tracker 中经明确授权登记 active Worker、owner 与唯一获准干净 worktree；若仍无法确认，继续 ready-for-agent，不派发。
6. 上述条件都满足后，才可做新的 cleanup eligibility audit；该审计最多声明可候选，并不自动授权删除。

物理工作树收敛与唯一权威实现是不同结论：在不删除分支引用、先对 follow-up WIP 作可逆保全、保留 canonical 的未提交项，并独立处置未整体采纳的 follow-up 已提交工作后，可考虑可逆地退役干净旧 Ticket worktree。此举仍不代表 canonical 已完整替代全部历史、Ticket 已完成、可合并或可发布；main 的主工作树不在该 Ticket worktree 收敛范围。

## 5. 本会话明确禁止的动作

- merge、cherry-pick、rebase、reset、clean、stash、force push、branch 删除、worktree 删除、创建新 worktree；
- 读取、修改、暂存、提交或删除 ticket-26-follow-up WIP；
- 读取或处理 canonical 冻结 delivery 草案；
- 把现有分支、CI/QEMU 结果或 canonical 名称写为 Ticket 26 completed、main 已集成或真机验收完成。

## 6. Architecture Change Policy 检查

本审计没有改变任何产品决策、ADR、spec、CONTEXT、Full Linux 契约、Guest Bridge 命令或 Exposure Whitelist；仅报告 branch/worktree 收敛前置条件，Architecture Change Policy 未触发。

## 7. 修改、验证、风险与后续动作

- 修改文件：本报告；本会话未修改任何 Ticket 26 branch/worktree、WIP 或 delivery 草案。
- 已验证：registered worktree 的 branch、HEAD、dirty/untracked、upstream、main 关系、共同祖先主题级描述及双向独有提交计数。
- 未运行：build、CI、ADB、设备操作、artifact 下载、完整 CI 日志、ticket-26-follow-up WIP 内容或 canonical delivery 草案内容。
- 风险：未审查 WIP、冻结 delivery 草案、历史 CI 与当前 canonical 的验证断裂、terminal relay、GPL 以及真机/交付 gate 均阻止安全收敛。
- 后续：仅在第 4 节的可逆前置条件和新的 cleanup eligibility audit 均满足后，才可提出任何 worktree 收敛动作；本报告不授权删除。
- Architecture Change Policy：未触发。

报告人：Codex Orchestrator / GPT-5.6 Terra fallback ｜ 日期：2026-09-20 ｜ 耗时：未独立计时
