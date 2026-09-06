# Model Selection Policy 审计报告

## 1. 审计结论

**Stage 2: COMPLETED DURING THIS AUDIT**

只读审计先确认 Stage 2 尚未完成：审计基线 `7d4252e` 的 active policy 仍把所有
implementation、hardening、review session 固定到 `GPT-5.6 Sol + Max`，并在并行化矩阵
的每个工单行重复该绑定。随后仅修改 active Model Selection / Thinking Policy，完成
能力等级、动态模型选择、reasoning band 与 child 独立选择规则。

## 2. 搜索范围与方法

已读取并核对：

- `AGENTS.md`；
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`；
- `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md`；
- `docs/agent-handoff/AGENT_WORKER_CONTRACT.md`；
- `docs/agent-handoff/PARALLELIZATION_MATRIX.md`；
- `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`；
- `docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md`；
- `docs/agent-handoff/HOST_ENVIRONMENT_POLICY.md`；
- `docs/reports/orchestration/`；
- `.scratch/droidlayer-platform/` tracker；
- 当前 `main`、Git history 与 worktree 状态。

全文搜索目录包括 `docs/agent-handoff/`、`docs/agents/`、`AGENTS.md`、
`.scratch/droidlayer-platform/` 及整个 repo（对 `docs/reports/`、`docs/research/` 做历史
记录分类）。关键词为：`GPT-5.6`、`Sol`、`Terra`、`model`、`models`、`thinking`、
`reasoning`、`模型`、`思考`、`推理`、`L1`、`L2`、`L3`、`L4`。

## 3. 审计前后的 active policy

审计前发现：

- `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md` §4 规定所有 implementation/
  hardening/review session 使用 `GPT-5.6 Sol + reasoning/thinking: Max`；
- `docs/agent-handoff/PARALLELIZATION_MATRIX.md` 的 Model/Thinking 两列对所有工单固定
  为 `GPT-5.6 Sol` / `Max`。

本次仅改动以下 active policy：

1. `TICKET_EXECUTION_GUIDE.md` §4 改为
   `Task / Session → Task type → Difficulty / Risk → Required capability →
   Orchestrator selects an available model`；新增 L1–L4 capability levels、用户与
   Orchestrator 的实际模型选择、动态可用性与未来模型兼容规则。
2. 同一节新增 reasoning band：`L1 → low/medium`、`L2 → medium`、`L3 → high`、
   `L4 → max`；允许 Orchestrator 按风险重新评估，Worker 不得自行降低要求。
3. 同一节新增 child policy：child 依据 `role + scope + difficulty + required
   capability` 独立选择，不自动继承 parent 的实际模型或 reasoning band。
4. `PARALLELIZATION_MATRIX.md` 将 Model/Thinking 列改为 Required capability/
   Reasoning band，并按工单风险标注 L3/L4；不再列出任何具体模型名称。

这些 level 是 capability level，不是模型名称。`Max` 在新 policy 中只表示 L4 reasoning
band，不表示某个具体模型。

## 4. 历史模型引用

以下引用保留原样，因为它们是历史事实而不是 active policy：

- `docs/reports/tickets/25/worker-scope-contract.zh-CN.md`：Ticket 25 Worker 的实际
  session 配置；
- `docs/reports/tickets/25/implementation-report.zh-CN.md`：Ticket 25 实际执行模型与
  thinking 记录；
- `docs/reports/orchestration/initial-takeover-report.zh-CN.md`：接管时排队 Worker 的
  实际配置。

报告中的 `Max`（如 QEMU `-cpu max` 或历史执行参数）不属于 active model policy；没有
修改历史 implementation/review/research/prototype/session 记录、Git history 或
   commit message。

## 5. 一致性检查

- **无固定模型 active policy：通过。** Active handoff policy 不再绑定 GPT-5.6 Sol、
  Terra、Luna 或任何单一名称。
- **能力分级：通过。** L1 lightweight、L2 standard、L3 high reasoning、L4 maximum
  reasoning 已写入执行指南。
- **Reasoning 可按级别调整：通过。** 默认 band 随 L1–L4 变化，Orchestrator 可按任务
  风险和运行环境调整并记录理由。
- **Child 独立选择：通过。** Child 不自动继承 parent 的模型或 reasoning band。
- **用户 / Orchestrator / 动态可用性 / 未来模型：通过。** 用户可在创建 session 时选择
  实际模型；Orchestrator 可按任务与当前可用模型选择；新增模型不需要改变项目架构。
- **Residual fixed-model wording：未发现 active 残留。** 搜索到的具体模型名称只在上述
  历史报告/历史 scope contract 中；未改动这些历史引用。

## 6. Scope 与未修改内容

除本报告外，本次只修改：

- `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md` 的 active Model Selection / Thinking /
  Child policy；
- `docs/agent-handoff/PARALLELIZATION_MATRIX.md` 的 active capability/reasoning 列。

明确未修改：产品定义、SPEC、CONTEXT、ADR、ticket graph/dependency/acceptance criteria、
Ticket 25 implementation/report/review、Ticket 26、其他 ticket、Host Environment Policy、
Git policy、Worker Contract 的非模型条款、Reporting Policy、Architecture Change Policy、
历史 orchestration/research/implementation/review/prototype reports、代码、测试与任何
宿主环境资源。

Ticket 状态核对保持：Ticket 25 = `completed`；Ticket 26 = `needs-triage`。本审计没有
启动、领取或修改 Ticket 26，也没有执行 build、VM、container、vctl 或新的 C: 写入。

## 7. Git 与 diff 审计

- 审计基线：`7d4252e9e539bfaefcefdb42b6f3974f353137c5`，分支 `main`。
- 变更前 worktree：clean。
- policy diff 核验：`git diff --stat` 与 `git diff` 显示仅两份 active policy 文件；
  `git diff --check` 返回 `rc=0`。
- 本次独立 commit `chore(orchestration): decouple model selection policy` 只包含上述
  两个 Model Selection Policy 文件与本报告；其最终 object id 以该提交完成后的 Git
  `HEAD` 为准。
- 本阶段没有宿主环境变化；工作区将在提交后保持 clean。

## 8. 最终状态

Stage 2 已在本审计中完成，且没有发现遗漏或需要 Architecture Change Gate 的问题。

报告人：Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
