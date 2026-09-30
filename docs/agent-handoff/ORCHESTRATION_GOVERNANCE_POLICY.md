# 编排治理策略（Orchestration Governance Policy）

> 状态：项目级 active policy。适用于所有 User → Orchestrator → Worker → Child 的正式
> DroidLayer 工作。它是 session 委派、并行、branch/worktree 生命周期及 Git 发布的唯一
> 事实来源；模型选择的唯一 policy 见 `ENGINEERING_HANDOFF.md` §4A；artifact 与仓库组织的
> 唯一 policy 见 `ARTIFACT_ORGANIZATION_POLICY.md`。报告路径与报告内容由
> `REPORTING_STANDARD.zh-CN.md` 单独定义，宿主机写入由 `HOST_ENVIRONMENT_POLICY.md` 单独定义。

## 1. 适用范围与决策顺序

本策略只治理工作如何被编排，不改变产品、ADR、SPEC、CONTEXT 或 tracker 的事实来源。
模型路由冲突统一按 `ENGINEERING_HANDOFF.md` §4A 处理；本策略不另行定义模型池或默认模型。
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
- source-controlled evidence、ephemeral output、retention 与 cleanup 路径（按 `ARTIFACT_ORGANIZATION_POLICY.md`）；
- 所需宿主环境权限或明确的只读限制。

Worker 只能执行 packet 中的 scope。发现完成当前任务似乎要求修改其他 ticket、ADR、
SPEC、CONTEXT、产品需求或 dependency graph 时，停止越界修改，记录证据，并走
Architecture Change Policy；不得自行宣布需求或架构变化。

## 3. Model & Capability Tier Selection Policy

`ENGINEERING_HANDOFF.md` §4A 是 Risk-based capability routing 的唯一事实来源。本节只规定如何
把它落实到 session，不复制一套可能漂移的模型表；任何旧报告、历史 dispatch packet 或
历史 Worker 配置都不能覆盖 §4A。

Orchestrator 在创建 Worker 或 Child 前必须按以下顺序决定，并把结论写入 dispatch packet：

```text
Task → Session role → Difficulty → Risk → Verification cost
     → Required Capability Tier → Reasoning Depth selection
```

执行要求：

- **能力层级解耦**：彻底消除硬编码具体供应商模型代号的遗留约束，严格按四大标准化能力层级（Architect Tier、Implementation Tier、Reviewer Tier、Lightweight / Research Tier）进行路由；
- **角色映射规则**：
  * Primary Agent / Orchestrator 默认采用 **Architect Tier**（高逻辑推理深度）；
  * Implementation Worker 依据 §4A 的风险表匹配 **Implementation Tier**（高执行与代码精度）；
  * Review、跨系统边界审查与规范对齐采用独立的 **Reviewer Tier**（双轴独立复评）；
  * Architecture / Security 审查与 Critical Audit 采用顶格深度的 **Architect Tier**，且必须由独立会话承接，严禁 Worker 自我核准；
  * 调研、事实检索与辅助分析采用 **Lightweight / Research Tier**；
- **Risk 驱动匹配**：Risk 由错误成本、不可逆性、blast radius、验证成本和边界影响决定，不由代码量或 ticket 大小决定。一个 ticket 的不同子任务可以分属不同 Risk；
- **Child 独立选型**：Child 不自动继承 parent 的模型或能力层级。每个 Child 由 Orchestrator 依据 `role + scope + difficulty + risk + verification cost + required capability tier` 独立选择，并遵守 §4A 的 Worker/Child 权限边界；
- **能力边界约束**：具备更高能力层级不授予改变 ADR、spec、产品语义、架构、ticket scope 或 tracker 状态的权力；发现冲突必须停在 §4A 的 escalation rules，并走 Architecture Change Policy。

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
Child packet 必须说明输入、允许操作、输出与交回对象，以及遵循 artifact policy 的
source-controlled evidence、ephemeral output 和 cleanup 路径。

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

### 7A. Project Directory Boundary & Worktree Freshness

所有 DroidLayer **Git worktree**（包括 ticket、review、prototype 和 governance worktree）
必须位于以下项目工作区边界内：

```text
允许：D:\Development project(开发项目)\DroidLayer开发目录\*
禁止新建 active Git worktree：D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\worktrees\*
```

`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer` 仍是 `HOST_ENVIRONMENT_POLICY.md` 定义的
外部资源根，可保存 toolchain、cache、VM image、运行输出和 evidence；本条只禁止把 Git
worktree 注册在该资源根下，不要求删除或批量迁移其中的历史资产。历史报告、handoff 和
旧路径引用继续作为审计记录保留。

创建或迁移 worktree 前，Orchestrator 必须：

1. 用 `git rev-parse --show-toplevel` 确认 repository root；
2. 确认候选物理路径位于允许边界内且目标不存在；
3. 使用 `git worktree add`、`git worktree move` 或其他 Git 官方 worktree 管理命令；
4. 操作后执行 `git worktree list --porcelain`；
5. 核对 registered path 与 physical path 一致，并记录 branch、HEAD 和未提交状态。

接手任何已有 worktree 时，不能只相信 handoff 或 dispatch packet 中的旧快照。必须重新
核对 `git worktree list --porcelain`、`git status --short --branch`、`git rev-parse HEAD`、
`git branch --show-current`、upstream（若无则明确记录）、与 `main` 的 merge-base 及
ahead/behind、`git diff`、`git ls-files --others --exclude-standard`、ignored 文件摘要和
最近提交记录。报告至少要明确 HEAD、branch、dirty/clean、ahead/behind、untracked、ignored
以及是否存在其他同 ticket worktree/branch；不能以“看起来最新”替代核验。

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
