# Ticket 执行指南（Codex Orchestrator 工作流）

> 读者：Orchestrator Agent。Worker 的约束在 AGENT_WORKER_CONTRACT.md；会话、模型、
> 委派、并行、worktree 与发布规则以 ORCHESTRATION_GOVERNANCE_POLICY.md 为准；架构变更
> 走 ARCHITECTURE_CHANGE_POLICY.md。

## 1. 状态机

工单状态（记录在工单文件 `Status:` 行）：

| 状态 | 含义 | 谁能设置 |
| --- | --- | --- |
| `needs-triage` | 已发布，待用户/Orchestrator 审定范围 | 初始状态 |
| `ready` (`ready-for-agent`) | 已审定，可被领取 | Orchestrator（用户放行后） |
| `blocked` | 依赖未完成或外部条件缺失（如 P1 需真机） | Orchestrator |
| `active` (`claimed`) | 已被某个 Worker 领取，正在执行 | Worker 领取时 |
| `review` | 实现完成，等待 code review | Worker 完成时 |
| `completed` (`resolved`) | 验收通过、合并完成 | Orchestrator（review 通过后） |
| `failed` | 验收失败，需返工或重开 | Orchestrator |
| `needs-architecture-decision` | 触发架构变更门，冻结实现 | Worker 或 Orchestrator |

状态转换规则：`needs-triage → ready → active → review → completed`；任何状态可进 `failed` 或 `needs-architecture-decision`；`failed → active`（返工）或 `needs-architecture-decision`。

## 2. Orchestrator 执行循环（16 步）

1. **读取交接**：ENGINEERING_HANDOFF.md（含事实优先级）+ map.md。
2. **验证状态**：`git log` / `git status` / tracker 文件清单与交接 §5–§9 对照；不一致以 tracker 为准并登记勘误。
3. **判断 ready**：工单 `Status: ready-for-agent` 且（所有 `Blocked by` 均为 `resolved`）且外部条件满足（如 34 需 17 完成、17 需真机）。否则标 `blocked` 并注明缺失条件。
4. **读取工单**：What to build / Acceptance criteria / Test strategy / Prototype dependency 全文。
5. **解析依赖**：核对 Blocked by 列表全部 resolved；注意跨票的**逻辑**依赖（如 37 的 vsock 传输依赖 34 的传输基座——已编码进 Blocked by）。
6. **分析模块归属**：按 PARALLELIZATION_MATRIX.md 的 owned/forbidden modules 确定本票允许触碰的范围。
7. **决定并行**：按 ORCHESTRATION_GOVERNANCE_POLICY §6 核对依赖、模块、共享测试、共享 contract file、branch 和 worktree；任一项不通过即串行。
8. **形成 dispatch packet**：在创建 session 前决定唯一 scope、role、Matt Pocock workflow、difficulty/risk/verification cost、required capability、model、reasoning、branch/worktree、测试和报告路径（见 ORCHESTRATION_GOVERNANCE_POLICY §2–§4）。
9. **创建 Worker / Child session**：注入 packet、工单、scope contract、AGENT_WORKER_CONTRACT.md、词汇表和必要 policy；不得把 workflow 或模型选择留给 Worker 临场决定。
10. **管理 branch/commit**：按 ORCHESTRATION_GOVERNANCE_POLICY §7–§8；Worker 在自己的独立 branch/worktree 工作。
11. **收集测试结果**：Worker 报告必须含规定测试的运行结果（REPORTING_STANDARD）；契约测试 + JVM 测试为验收硬条件。
12. **触发 code review**：`review` 状态 → 按 dispatch packet 指派 review Worker，对照 spec/ADR 检查（双轴：规范一致性 + 工单符合度）；其 Child 若需要，仍受 delegation depth 与 packet 约束。
13. **生成中文报告**：确认 Worker 报告符合 REPORTING_STANDARD，缺失则退回。
14. **更新工单状态**：review 通过 → `completed`（resolved）+ 合并；失败 → `failed` + 返工意见。
15. **判断解锁**：依赖图上以本票为 blocker 的工单是否转为 ready；对照矩阵检查新并行组合。
16. **循环**：回到 3。每完成一个里程碑向用户提交中文进度报告。

## 3. Session governance

ORCHESTRATION_GOVERNANCE_POLICY.md 是 Matt Pocock workflow assignment、Model Selection、
Delegation、Parallel dispatch、branch/worktree lifecycle 和 Git/push/PR 的唯一事实来源。
每次创建 Worker 或 Child 前必须先执行其 §2–§6；每次集成、cleanup 或发布前执行其 §7–§8。
已完成的 `wayfinder`、`to-spec`、`to-tickets` 只能在出现新的明确触发条件时重新进入。

## 4. Git / Branch 策略

ticket branch 命名、独立 worktree、Conventional Commit、`--no-ff` integration、prototype
边界、cleanup、remote preflight 和 push/PR 纪律均以 ORCHESTRATION_GOVERNANCE_POLICY §7–§8
为准。Worker 不得直接修改 `main` 或 merge 自己的 branch。

## 5. 与用户（HITL）的交互点

- needs-triage → ready 的放行；真机原型（17/18）的设备提供；架构变更门的裁决；里程碑验收。除此之外 Orchestrator 应自主推进并在里程碑汇报。
