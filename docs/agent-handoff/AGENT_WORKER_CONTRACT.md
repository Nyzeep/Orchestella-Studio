# Worker Agent 强约束合同（Agent Worker Contract）

> 读者：被 Orchestrator 派生的 Worker Agent。本合同是强约束：违反任何"必须"即视为该
> session 失败，其产出退回。session 的 model、reasoning、workflow、Child、worktree 与
> 发布规则以 ORCHESTRATION_GOVERNANCE_POLICY.md 为准。

## 1. 职责边界（硬约束）

1. **一次只处理一个 ticket**：只能实现被指派 ticket 描述的内容。
2. 不得实现其他 ticket 的内容（即使是"顺手就做了"）。
3. 不得自行扩大 scope：工单 Acceptance criteria 之外的功能一律不做；发现"应该做"的 → 写进报告"新发现"。
4. 不得重构无关代码；改动范围 = PARALLELIZATION_MATRIX 中本票 owned modules；forbidden modules 一个文件都不碰。
5. 不得修改产品需求：spec.md 的任何承诺不是你的可编辑对象。
6. 不得修改已批准 ADR 的内容或 status。
7. 不得关闭/修改其他 ticket（含把它们标 resolved/claimed）。
8. 不得修改其他 Worker 的工作（其他分支、其他 active 票的文件）。
9. 必须遵守 assigned ownership（owned/forbidden modules），提交前自查 diff。
10. 必须运行工单规定的测试（契约测试 + JVM 测试 + 票内列明的冒烟），并在报告中给出真实结果——禁止报告未运行的测试。
11. 必须产出中文报告（REPORTING_STANDARD 格式），无报告不算完成。
12. 遇到与 ADR/spec 冲突的现实 → 停止实现，走 ARCHITECTURE_CHANGE_POLICY，不得绕行。
13. 不得直接修改 `main`；不得合并自己的分支。
14. 必须使用 Orchestrator 在 dispatch packet 中预先指定的 workflow、required capability、model 与 reasoning；不得自行降档、换模型或重新定义主要 workflow。
15. 只能在分配的独立 branch/worktree 内工作；发现 scope、共享文件或环境权限与 packet 不一致时先停止并回报。

## 2. 交付定义（Definition of Done）

一个 ticket 被 Worker 标记 `review` 的前提：

- [ ] 工单全部 Acceptance criteria 满足（逐条勾选或说明偏差）
- [ ] 规定测试全部运行且通过（失败项必须在报告中如实列出）
- [ ] diff 仅涉及 owned modules
- [ ] 中文实施报告已写入 `docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`
- [ ] 分支提交历史干净（只含本票工作，Conventional Commits + `[ticket-NN]`）

## 3. Child 委派（硬约束）

ORCHESTRATION_GOVERNANCE_POLICY.md §5 是 Child 的唯一正式 Delegation Policy。Worker 只能
提出窄 scope Child 的需求，待 Orchestrator 形成并确认 packet 后才可派发；不得自行选择
Child 的 workflow、capability、model 或 reasoning。

Child 只能做 packet 指定的 research、prototype、targeted review、test investigation、
documentation analysis、environment inspection 或其他 narrow supporting work。Child 不得
领取正式 ticket、修改其他 ticket/ADR/SPEC/CONTEXT/产品需求/dependency graph、修改 `main`、
merge、push 或继续派生。Child 发现架构冲突时只输出 finding 给 parent；parent 依
ARCHITECTURE_CHANGE_POLICY 处理。
