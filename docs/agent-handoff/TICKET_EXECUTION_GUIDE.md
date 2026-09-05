# Ticket 执行指南（Codex Orchestrator 工作流）

> 读者：Orchestrator Agent。Worker 的约束在 AGENT_WORKER_CONTRACT.md；架构变更走 ARCHITECTURE_CHANGE_POLICY.md。

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
7. **决定并行**：检查同批 active 工单的冲突面（矩阵"can parallel?"列）；冲突面为 MEDIUM/HIGH 的不得同时 active。
8. **创建 Worker session**：注入 scope contract（= 工单文件 + 本指南引用 + AGENT_WORKER_CONTRACT.md + 词汇表），明确唯一 ticket、允许模块、验收标准。
9. **选择 skill workflow**（见 §3 映射）。
10. **管理 branch/commit**：见 §5 Git 策略；Worker 在自己的分支工作。
11. **收集测试结果**：Worker 报告必须含规定测试的运行结果（REPORTING_STANDARD）；契约测试 + JVM 测试为验收硬条件。
12. **触发 code review**：`review` 状态 → 指派 review session（可派 review child），对照 spec/ADR 检查（双轴：规范一致性 + 工单符合度）。
13. **生成中文报告**：确认 Worker 报告符合 REPORTING_STANDARD，缺失则退回。
14. **更新工单状态**：review 通过 → `completed`（resolved）+ 合并；失败 → `failed` + 返工意见。
15. **判断解锁**：依赖图上以本票为 blocker 的工单是否转为 ready；对照矩阵检查新并行组合。
16. **循环**：回到 3。每完成一个里程碑向用户提交中文进度报告。

## 3. Matt Pocock Skills 映射（按 session 类型选用，不全跑）

| Session 类型 | 工作流 |
| --- | --- |
| 架构决策（变更门触发后） | `grill-with-docs`（= grilling + domain-modeling）→ 新/修订 ADR → 更新 map |
| 需求澄清（HITL） | `grilling` + `domain-modeling`，一次一问 |
| 研究（AFK） | `research`（产出 docs/research/ 报告 + 工单 Answer） |
| 原型 | `prototype`（throwaway 产物，不进主干；报告归档） |
| implementation 票 | `implement`（+ `tdd`：契约/JVM 测试先行；结束自跑 `code-review` 前置自检） |
| code review | `code-review`（规范轴 + spec 符合轴） |
| hardening 票 | `implement` + `code-review`（验收主体是走查清单与全量回归） |
| 集成/合并 | Orchestrator 主导 + `code-review` 抽查 |

注意：`wayfinder` 已完成（map 进入维护态）；`to-spec`/`to-tickets` 已消费，不重复运行。

## 4. Model Policy（编排级决策，Worker 不得自选）

- 默认：**GPT-5.6 Sol + reasoning/thinking: Max**，适用于所有 implementation/hardening/review session。
- research/prototype child 可降档以省额度，但结论性输出（报告、决策建议）必须由默认配置产出或复核。
- 仅当环境明确不可用时降级，且必须在报告中记录实际使用的模型与理由。

## 5. Git / Branch 策略

- 分支命名：`ticket/<id>-<short-name>`（如 `ticket/25-guest-assets-baseline`）；prototype 用 `prototype/<id>-<short-name>`。
- **一个 ticket 一个分支**；分支从最新 `main` 创建。
- Commit：Conventional Commits + `[ticket-NN]` 前缀（如 `feat(qemu): ... [ticket-26]`）；提交只含本票工作；小步提交。
- Worker **不得直接修改 `main`**；不得 merge 自己的分支。
- Integration：review 通过后由 Orchestrator 合并（`--no-ff` 保留票边界）；合并前 rebase 到最新 main。
- 冲突处理：Worker 解决自己分支上的冲突并说明；跨票结构性冲突 → Orchestrator 仲裁，必要时串行化重排（更新矩阵）。
- prototype 分支永不合并进 main（结论进 docs/reports 与研究文档）。

## 6. 与用户（HITL）的交互点

- needs-triage → ready 的放行；真机原型（17/18）的设备提供；架构变更门的裁决；里程碑验收。除此之外 Orchestrator 应自主推进并在里程碑汇报。
