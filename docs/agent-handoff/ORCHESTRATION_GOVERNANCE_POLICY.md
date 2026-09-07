# 编排治理策略（Orchestration Governance Policy）

> 状态：项目级 active policy。适用于所有 User → Orchestrator → Worker → Child 的正式
> DroidLayer 工作。它是 session 选择、委派、并行、branch/worktree 生命周期及 Git 发布
> 的唯一事实来源；报告路径与报告内容由 `REPORTING_STANDARD.zh-CN.md` 单独定义，宿主机
> 写入由 `HOST_ENVIRONMENT_POLICY.md` 单独定义。

## 1. 适用范围与决策顺序

本策略只治理工作如何被编排，不改变产品、ADR、SPEC、CONTEXT 或 tracker 的事实来源。
出现治理规则冲突时，按以下顺序执行：

1. 用户对当前任务的明确要求；
2. `HOST_ENVIRONMENT_POLICY.md`；
3. `ARCHITECTURE_CHANGE_POLICY.md`；
4. 本策略；
5. `AGENT_WORKER_CONTRACT.md`；
6. `TICKET_EXECUTION_GUIDE.md`；
7. `PARALLELIZATION_MATRIX.md`；
8. 历史报告、历史审计与历史 scope contract。

这不替代 `ENGINEERING_HANDOFF.md` §0 对产品事实、tracker 与 Git 状态的优先级。无法在
上述规则内安全解决的冲突必须停在相关区域，记录 finding，并交由 Orchestrator 依
Architecture Change Policy 处理。

## 2. 一个 session，一个正式 scope

每个正式 session 只能承担一个明确 scope：一个 ticket，或用户明确授权的单一
governance / integration / audit 任务。Orchestrator 在创建 Worker 或 Child **之前**必须
形成 dispatch packet，至少写明：

- task 与 session role；
- in-scope / out-of-scope、依赖与模块 ownership；
- Matt Pocock workflow；
- difficulty、risk、verification cost、required capability；
- model 与 reasoning；
- branch/worktree、测试与报告路径；
- 所需宿主环境权限或明确的只读限制。

Worker 只能执行 packet 中的 scope。发现完成当前任务似乎要求修改其他 ticket、ADR、
SPEC、CONTEXT、产品需求或 dependency graph 时，停止越界修改，记录证据，并走
Architecture Change Policy；不得自行宣布需求或架构变化。

## 3. Model Selection Policy

### 3.1 选择顺序与可用池

Orchestrator 必须按以下顺序决定，而不是沿用历史 session 的模型：

```text
Task → Session role → Difficulty → Risk → Verification cost
     → Required capability → Model selection → Reasoning selection
```

active policy 的可用模型池只有下列组合：

| 模型 | 允许的 reasoning |
| --- | --- |
| GPT-5.6 Sol | `medium`、`high`、`xhigh`、`max` |
| GPT-5.6 Terra | `xhigh`、`max` |

GPT-5.6 Sol 是最高级推理模型，用于困难、高风险、复杂架构与难验证问题；GPT-5.6 Terra
是均衡型模型，用于大多数常规工程工作，目标是效率、成本与质量的平衡。

新建 Codex session 的默认值是 **GPT-5.6 Terra / `max`**。这是初始值，不是所有任务的
强制配置；Orchestrator 必须根据 packet 向下选择足以满足任务的组合，以平衡效率、成本与
质量。不得在 active policy 中加入其他模型名或虚构 reasoning 档位。

### 3.2 Capability floor 与推荐组合

L1–L4 是治理 capability floor，不是产品的无 Root 等级，也不是模型名称。矩阵给出的
level 是最低能力要求；它不能替代对当前任务 difficulty、risk 与 verification cost 的判断。

| Level | 适用情形 | 推荐选择 |
| --- | --- | --- |
| L1 | 文件索引、状态检查、简单整理 | Sol / `medium`；若简单任务需要稳定长上下文，可用 Terra / `max` |
| L2 | 普通文档、普通 research、常规 review/support | Sol / `medium` 或 `high`；Terra / `xhigh` 或 `max` |
| L3 | 普通 implementation、复杂 research、integration analysis、non-trivial review | Terra / `max`，或 Sol / `high`、`xhigh` |
| L4 | kernel、AVF/pKVM、QEMU、VMM、storage architecture、security boundary、architecture change、高风险或难验证问题 | Sol / `xhigh` 或 `max` |

困难度、风险或验证成本上升时，Orchestrator 从 Terra 升至 Sol，并按 `high` → `xhigh` →
`max` 选择；不能静默降低 required capability。若当前 session 的模型或 reasoning 不足，
必须在执行前识别并重新 dispatch 或请求用户调整，不能先以不足能力实施再补充说明。

Worker 不得为节省额度自行降档、换用未授权模型，或以“任务看起来简单”为由忽略 packet
中的 capability / reasoning。历史报告中的模型记录不构成当前选择依据。

### 3.3 Child 的独立选择

Child 不自动继承 parent 的模型或 reasoning。Orchestrator 对每个 Child 单独依据
`role + scope + difficulty + risk + verification cost + required capability` 选择模型与
reasoning，并把结论写入 dispatch packet。例如 L4 Worker 可以获得 Terra / `max` 的
research Child，或 Sol / `medium` 的文档 Child；parent 的配置不产生自动升级权限。

## 4. Workflow assignment

Orchestrator 在创建任何 Worker 或 Child 前，先决定 role、scope、workflow、capability、
model 和 reasoning；Worker/Child 不得临场选择主要 workflow。标准映射如下：

| Session 情形 | 必选 workflow |
| --- | --- |
| 规划 / 架构不确定 | `wayfinder` |
| 需求澄清 | `grilling` + `domain-modeling` |
| 正式规格 | `to-spec` |
| 工单拆解 | `to-tickets` |
| 普通 implementation | `implement` + `tdd` |
| research | `research` |
| prototype | `prototype` |
| 领域模型 | `domain-modeling` |
| review | `code-review` |
| 架构变更 | `grill-with-docs` + `domain-modeling` + 新建/修订 ADR |

已经完成且没有新触发条件的 `wayfinder`、`to-spec`、`to-tickets` 不得为了形式重复执行。

## 5. Delegation Policy

正式层级为 `User → Orchestrator → Worker → Child`。从 Orchestrator 起最大 delegation
depth 为 2：Worker 是第一层，Child 是第二层；Child 不得再派生 Child、Worker 或无限
递归树。Orchestrator 指派的 review 也是第一层 Worker role；其窄 scope Child 仍受第二层
上限约束。

Child 只能承担窄支撑工作：research、prototype、targeted review、test investigation、
documentation analysis、environment inspection，或其他明确界定的 supporting work。每个
Child packet 必须说明输入、允许操作、输出与交回对象。

Child 不得自行领取正式 ticket、修改其他 ticket、ADR、SPEC、CONTEXT、产品需求或
dependency graph；不得修改 `main`、merge、push，或超出自己的 packet。涉及宿主环境的
Child 仍受 Host Environment Policy 约束，parent 已获能力不会自动授予 Child。Child 发现
架构问题时必须停止扩大 scope，输出 finding 给 parent；仅 parent / Orchestrator 可触发
Architecture Change Policy。

Worker 可以提出 Child 需求，但只能在 Orchestrator 确认 packet 后派发；不得自己决定
Child 的主要 workflow、required capability、model 或 reasoning。

## 6. Parallel dispatch

并行不是只看 DAG。每个并行组合在 dispatch 前必须同时满足以下六项：

1. ticket dependency 无冲突；
2. module ownership 无冲突；
3. shared test ownership 无冲突；
4. shared contract file 无冲突；
5. branch 无冲突；
6. worktree 无冲突。

`tests/contract` 是共享热点。任一条件不满足、文件边界未知，或两个任务会写同一核心
contract/test 文件时，必须串行。`PARALLELIZATION_MATRIX.md` 是规划输入而不是并行的
自动批准；Orchestrator 要在 dispatch packet 中记录六项核对结果。

## 7. Branch / worktree lifecycle

- 一个正式 ticket 一个 branch；一个 active Worker 一个独立 worktree。非 ticket 的
  governance/integration 工作只能在用户明确授权的 scope 内使用 Orchestrator worktree。
- 生命周期为 `create → active → review → merged → cleanup`。创建前记录 owner、ticket、
  branch、worktree path 与预期结束条件；review/merge 后由 Orchestrator 复核 cleanup。
- 常规保留目标是最新 3 个有价值的 active / recently finished worktree。超过该数量时，
  先合并已 review 完成的工作，再审计候选 cleanup；“三条”是审计阈值，不是自动删除授权。
- cleanup 前必须核对 `git worktree list`、branch inventory、候选 worktree 的未提交改动、
  未合并 commit、active Agent 使用状态、用户明确保留标记与 prototype 保留理由。任何
  active、dirty、含未合并 commit 或用户明确保留的 worktree/branch 都不得删除。
- prototype worktree 可因实验复现价值保留，但必须记录原因。定期执行 worktree/merged
  branch/stale worktree audit，并在相关报告中记录实际 cleanup 或“未清理”的理由。

## 8. Git、push 与 PR

一个逻辑变化一个 Conventional Commit；ticket commit 使用 `[ticket-NN]`，治理变更使用
相应的 `docs(orchestration)` 或 `docs(handoff)` 中文 subject。不得改写历史、squash 已完成
ticket，或用伪实现 commit 混入无关变更。

push 前由 Orchestrator 依次检查：`git status`、`git diff --check`、`git log`、ticket 状态、
产品代码变更、未解释 deletion 与未提交临时文件；再核对 `git remote -v`、fetch 后的远端
历史与 fast-forward 条件。不得 force push，也不得在远端已配置时擅自改 URL。没有 remote
时，只有用户提供明确目标后才可添加该目标并安全发布。

PR 必须提供中文标题和正文，正文说明治理目标、规则变化、测试/验证、当前 ticket 状态和
未修改内容。生成 PR 文本不等于创建 PR；除非用户明确要求并实际完成创建，否则不得声称
PR 已创建。所有正式报告的路径、命名、必填字段与最终报告责任以
`REPORTING_STANDARD.zh-CN.md` 为准。
