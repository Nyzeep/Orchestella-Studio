# 报告规范（Reporting Standard · zh-CN）

> 所有正式 session（implementation / prototype / research / review）完成后必须产出中文报告并提交入库。无报告 = 未完成。
> 语言：中文正文，技术术语保留英文。禁止虚报：未运行的测试不得写成已通过。

## 1. Implementation 报告（每张票一份）

路径：`docs/reports/tickets/<ticket-id>/implementation-report.zh-CN.md`（如 `docs/reports/tickets/26/implementation-report.zh-CN.md`）

```markdown
# Ticket <NN> 实施报告

- 票号/标题：
- 执行 session / 模型 / thinking：
- 分支：ticket/<id>-<short-name>　基线 commit：

## 1. 任务目标
（引用工单 What to build，一两句）

## 2. 实际完成
（逐条对应 Acceptance criteria：✅/⚠️/❌ + 证据）

## 3. 未完成 / 与预期偏差
（无则写"无"；有则说明原因与建议）

## 4. 修改的模块
（按模块域列出，与 owned modules 对照，确认未触碰 forbidden）

## 5. 重要设计决策
（实现中做出的取舍；若触及 ADR 边界必须标注并链接架构变更记录）

## 6. 测试
- 契约测试：新增/扩展条目 + 运行结果（真实输出摘要）
- JVM 测试：新增/扩展 + 结果
- 冒烟清单：真机验证项与结果

## 7. Benchmark / 实测数据
（启动耗时、操作延迟等；无则写"本票不涉及"）

## 8. 风险
（实现引入的新风险与缓解）

## 9. 新发现
（实现中发现的 spec/ADR 缺口、bug、可复用模式——不自行修改，供 Orchestrator 分诊）

## 10. 对后续 ticket 的影响
（依赖本票的票需要注意什么）

## 11. Commit
（分支 + commit 列表，一行一个）

## 12. Ticket 状态
（建议：completed / failed / needs-architecture-decision + 理由）
```

## 2. Research 报告（研究票 / 临时研究）

路径：`docs/reports/research/<topic>/research-report.zh-CN.md`；正式研究票的最终结论同时归档 `docs/research/<date>-<topic>.md`。

```markdown
# 研究报告：<主题>
- 触发票号 / 日期 / 执行者（模型）：
## 问题清单
## 逐问题结论（每条带来源 URL/路径；无法确认标 UNCERTAIN）
## 对已有决策的影响检查
（明确回答：是否需要重开 ADR/decision？哪个？证据是什么？）
## 产出物
（归档文档路径）
```

## 3. Prototype 报告（原型票 17/18 及其他）

路径：`docs/reports/prototypes/<ticket-id>/prototype-report.zh-CN.md`

```markdown
# 原型报告：<票号 标题>
- 假设清单（被验证什么）
- 实验环境（设备型号 / OS 版本 / 授权方式）
- 实验步骤与观测数据（原始数据表）
- 结论：每个假设的 ✅ 成立 / ❌ 不成立 / ⚠️ 部分成立
- 触发器判定：是否命中某个重开触发器？命中哪个 ADR/雾区条目？
- 实验产物位置（分支/目录；声明永不合并主干）
- 对能力矩阵的建议变更
```

## 4. Review 报告

路径：`docs/reports/reviews/<ticket-id>/review-report.zh-CN.md`

```markdown
# Review 报告：Ticket <NN>
- 审查对象（分支/commit 范围）与审查者（模型）
- 规范轴（符合 ADR/CONTEXT 词汇/架构约束？逐项）
- Spec 轴（工单验收标准逐条核对：✅/⚠️/❌ + 证据）
- 测试真实性与覆盖评估
- 暴露面检查（是否引入白名单外能力）
- 结论：approve / request-changes（清单）/ escalate（架构变更门）
```

## 5. 通用纪律

1. 报告在 `review` 状态前必须存在；Orchestrator 验收时核对报告与 diff 一致。
2. 所有路径按本规范入库并随票提交（Conventional Commits：`docs(reports): ... [ticket-NN]`）。
3. 数字必须可追溯（命令输出、截图位置、数据表）；禁止估计值冒充实测。
4. 每份报告末尾一行：`报告人：<agent/model> ｜ 日期：<date> ｜ 耗时：<tokens/时长（如可得）>`。
