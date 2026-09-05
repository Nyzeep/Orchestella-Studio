# Worker Agent 强约束合同（Agent Worker Contract）

> 读者：被 Orchestrator 派生的 Worker Agent。本合同是强约束：违反任何"必须"即视为该 session 失败，其产出退回。

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
13. 不得直接修改 `main`；不得合并自己的分支（见 Git 策略）。

## 2. 交付定义（Definition of Done）

一个 ticket 被 Worker 标记 `review` 的前提：

- [ ] 工单全部 Acceptance criteria 满足（逐条勾选或说明偏差）
- [ ] 规定测试全部运行且通过（失败项必须在报告中如实列出）
- [ ] diff 仅涉及 owned modules
- [ ] 中文实施报告已写入 `docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`
- [ ] 分支提交历史干净（只含本票工作，Conventional Commits + `[ticket-NN]`）

## 3. Child Agent Policy

Worker 可在必要时派生子代理，仅限四类角色：

| Child 角色 | 允许的 scope | 输入 | 输出 |
| --- | --- | --- | --- |
| **research child** | 只读检索外部资料 | 明确的问题清单 | 研究纪要（带来源），交回 Worker 综合 |
| **prototype child** | 一次性实验分支/临时目录中的验证代码 | 明确的假设与验证步骤 | 实验数据 + 结论（不进主干） |
| **review child** | 只读审查 Worker 自己的 diff | diff + 工单验收标准 | 审查意见清单 |
| **handoff child** | 只读整理上下文 | 指定文档/工单 | 摘要 |

规则：

1. 每个 child 必须有明确的 role、scope、输入、输出（写进派生 prompt）。
2. **最大委托深度 = 2**（Worker → child；child 不得再派生）。
3. child 不得领取任何正式 implementation ticket。
4. child 不得修改产品需求、ADR、CONTEXT.md。
5. child 不得绕过架构变更门：child 发现冲突 → 回报 Worker → Worker 走门流程。
6. 环境并发额度受限（本机实测：后台子代理易被并发限制中止）——child 应短小、串行；失败时 Worker 以主线程完成同等工作。
