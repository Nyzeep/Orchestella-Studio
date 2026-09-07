# 编排报告：Post-Ticket-25 / Pre-Ticket-26 Governance Hardening

- 背景 / scope：在 Ticket 25 已完成、Ticket 26 尚未获实现放行的阶段，审计并补齐 active
  Orchestration / Handoff / Agent governance；不修改产品实现、ADR、SPEC、CONTEXT、tracker
  或历史报告。
- branch / 基线 / commit：`main`；基线
  `f8588ad86517751301308ca99eca5e7982b823b4`；本报告与治理文档由同一逻辑治理 commit
  承载，最终 hash 以提交后的 Git 历史为准。

## 审计结论

Stage 2 的 `6b34bea` 与 cleanup `f8588ad` 均存在，审计开始时 worktree clean。Ticket 25
tracker 状态为 `completed`，Ticket 26 为 `needs-triage`。active policy 的主要缺口是：

- 旧 Model Selection Policy 允许开放式模型池与 `low` reasoning，不能表达当前限定的
  Sol/Terra 池和 reasoning 档位；
- workflow 的选择顺序在 Worker 创建之后，Child/parallel/worktree/push 规则不完整；
- Parallelization Matrix 重复固定 reasoning，而非把它留给每次 session 的能力评估；
- Reporting Standard 没有完整固化 orchestration 路径、命名和通用字段。

历史报告、历史审计、历史 scope contract 中的旧模型事实已单独分类，保留原文，未被当成
active policy。

## 实际完成

- 新增 `ORCHESTRATION_GOVERNANCE_POLICY.md` 作为 session selection、Delegation、
  Parallel dispatch、branch/worktree lifecycle 与 Git/push/PR 的单一事实来源。
- 将 model selection 固化为：Task → Session role → Difficulty → Risk → Verification cost
  → Required capability → Model selection → Reasoning selection；默认新 session 为
  GPT-5.6 Terra / `max`，但必须按 packet 选择足够且不过度的 Sol/Terra 组合。
- 固化 User → Orchestrator → Worker → Child、最大 delegation depth 2、Child 独立模型评估、
  禁止递归委派及 Child 的产品/主分支/merge/push 边界。
- 将 workflow 指派前置到 session 创建前，明确 Matt Pocock workflow 映射。
- 固化并行六项 gate、独立 branch/worktree、worktree 生命周期与安全 cleanup 审计。
- 更新 reporting 的正式目录、命名与通用必填项；研究最终结论只归档到既有的
  `docs/research/` 结构。

## 未完成内容

无。Ticket 26 readiness audit、implementation、任何构建/VM/container/vctl 操作不属于本次
scope，均未执行。

## 修改文件

- `docs/agent-handoff/ORCHESTRATION_GOVERNANCE_POLICY.md`：新增 active policy。
- `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md`：将 dispatch 顺序改为先 packet 后创建
  session，并引用单一治理事实来源。
- `docs/agent-handoff/AGENT_WORKER_CONTRACT.md`：固化 Worker 对 packet、模型、workflow、
  branch/worktree 与 Child 边界的硬约束。
- `docs/agent-handoff/PARALLELIZATION_MATRIX.md`：保留 capability floor，移除重复的固定
  reasoning 列，改为引用 active policy。
- `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`：补齐 orchestration 报告路径、命名与
  通用字段，收敛研究归档路径。
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`：添加治理入口，并移除会误导后续 Agent 重启
  已完成 ticket 的 checklist 文案。

## 模型、委派、workflow 与 worktree 影响

本次没有创建 Worker 或 Child，没有新建 worktree，也没有删除 branch/worktree。当前 policy
要求每次未来 dispatch 都使用独立 packet，并在创建前选择 workflow、required capability、
model 与 reasoning。模型不会从 parent 自动继承，且 Worker 不能自行降档。

## 测试与验证结果

- `git diff --check`：`rc=0`；Markdown fence、文件存在性、索引/引用、active-policy
  keyword scan、tracker 状态与 diff scope audit 均通过。
- active policy 中未发现 Sol/Terra 以外的模型名、`L1 → low` 或“所有任务使用 max”规则；
  Matrix 中的 `LOW` 仅是并行冲突等级，不是 reasoning 档位。
- 未运行产品测试、构建、VM、container、vctl 或网络验证；本次为文档治理变更，不能虚构
  这些结果。

## 风险与后续动作

风险是未来 dispatch 未按 packet 执行；缓解措施是 TICKET_EXECUTION_GUIDE、Worker Contract、
Matrix 与 Engineering Handoff 均指向同一 active policy。后续只有在用户明确放行且 tracker
状态满足时，才可为 Ticket 26 做独立 readiness audit；本报告不构成实现授权。

## 越界与 Architecture Change Policy 检查

未修改产品代码、guest assets、Ticket 25、Ticket 26、ADR、SPEC、CONTEXT、tracker 状态、
历史报告或宿主环境。未发现产品架构冲突，未触发 Architecture Change Policy。

## 建议 PR 文本

标题：`完善 DroidLayer 编排、模型选择与 Agent 生命周期治理`

正文：

> ## 治理目标
> 在 Post-Ticket-25 / Pre-Ticket-26 阶段，补齐后续 Orchestrator、Worker 与 Child 的可执行
> 编排规则。
>
> ## 本次更新
> - Stage 2 Model Policy 已完成；本次将 Sol/Terra 模型与 reasoning 选择、capability floor
>   和升级规则固化为 active policy。
> - 增加 Worker / Child Delegation、Matt Pocock workflow assignment、并行六项 gate、
>   branch/worktree lifecycle，以及 Git/push/PR preflight。
> - 统一 formal report 的治理路径、命名与必填字段，并保留历史记录原文。
>
> ## 验证
> - 已执行文档一致性与 Git scope 自检；未运行产品构建或运行时测试，因为本次不改产品实现。
>
> ## 当前状态
> 本次未重新打开 Ticket 25，未开始 Ticket 26，实现代码未修改。

本报告只生成 PR 标题/正文；未创建 PR。

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
