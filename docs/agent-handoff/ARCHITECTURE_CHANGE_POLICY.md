# 架构变更门（Architecture Change Policy）

> 目的：防止实现过程静默侵蚀已批准的架构。本项目有 16 个 accepted ADR 与一份 ready-for-agent spec——它们是承诺，不是建议；修改它们的唯一合法通道是本门。

## 1. 触发条件（Worker 必须停下的时刻）

Worker 在实现中遇到以下任一情况，**不得直接改变架构**：

1. 当前抽象不够用（契约/能力矩阵/实体模型无法表达现实需要）；
2. 现实推翻了已批准 ADR 的假设（如：AVF custom VM 实测行为与 ADR-0008 前提不符；FUSE 存储性能不支持 ADR-0007 选型）；
3. 完成 ticket 需要改变已有架构（如"必须把策略细节漏进契约才能实现"）；
4. 新 research 与当前 decision 冲突；
5. 新 prototype 结果命中某个重开触发器（票 17/18 内已列明）；
6. 发现两条 ADR 互相冲突，或 ADR 与 spec 冲突。

## 2. 门流程（九步，顺序执行）

1. **停止扩大实现**：冻结与冲突相关的实现工作；已完成的独立部分可保留。
2. **记录发现**：把现象、证据、影响范围写成报告（REPORTING_STANDARD 的 research/implementation 报告均可承载），附可复现证据（实测数据/日志/引用）。
3. **创建 Architecture Change Request**：在 tracker 新建工单（`Type: decision`，`Status: needs-architecture-decision`，命名 `ACR-<NN>-<slug>`），正文 = 问题描述 + 证据链接 + 受影响 ADR/spec/工单清单。
4. **指向证据**：每条主张必须挂证据（研究文档、原型报告、实测输出）；无证据的偏好不构成变更请求。
5. **请求 Orchestrator 判断**：Orchestrator 初审（是否真冲突 / 是否可用能力矩阵等既有机制消化）；真正决策升级给用户。
6. **必要时重新进入 `/grill-with-docs`**：涉及产品取舍的变更必须回到 grilling 流程（一次一问、六段格式），不得由 Agent 代答。
7. **必要时创建新 ADR / 修订旧 ADR**：新决策 → 新 ADR（编号递增）或旧 ADR 标注 superseded；CONTEXT.md 词条同步更新；map 的 Decisions-so-far 登记。
8. **重新检查受影响 ticket**：依新决策更新/重开/作废受影响的 implementation 票；PARALLELIZATION_MATRIX 与 spec 相应修订；雾区条目毕业或清理。
9. **恢复 implementation**：受影响票转回 ready，按新契约继续；Orchestrator 在交接勘误区登记本次变更。

## 3. 边界澄清（哪些不算架构变更，不需要过门）

- 工单 Acceptance criteria 内的实现取舍（如 QEMU 参数、UI 细节、错误文案）——写进实施报告即可；
- 数据文件更新（能力矩阵的"已验证"状态翻转、DistroManifest 新条目）——随票正常提交；
- bug 修复（不改契约语义）；
- 测试套件扩展（不改已定义行为）。

拿不准时：按架构变更处理，宁过门勿绕行。

## 4. 历史先例（本门精神的既行案例）

- 研究/原型结论**从未**静默修改 ADR：四张研究票完成后均执行"对已关闭决策的影响检查"并明确记录"无需重开"（docs/research/ 各篇末节）；
- 用户裁定的变更纪律：「如果新证据确实足以推翻现有决策，请先重新打开对应 decision，而不是静默修改」。
