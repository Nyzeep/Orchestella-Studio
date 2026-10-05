# Review 报告：Ticket 36

- **审查对象**：`ticket/36-avf-network-forwarding`，比对基线 `c878b21`（`main`），审查命令 `git diff c878b21...HEAD`
- **审查 HEAD**：`2075f6f docs(report): 产出 Ticket 36 实施报告并将看板置为 ready-for-review (Ticket 36 Deliverable)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴双轴独立审计（Dual-Axis Independent Review）；在独立工作区 `DroidLayer_dev_t36` 运行全量构建与门控测试重跑验证。

---

## 阶段复审总览（Review Summary）

针对 Ticket 36（AVF 档能力对齐——出站网络与端口转发）的交付，独立评审专员严格依照架构纪律与工单验收标准，对比对基线 `c878b21` 的全部 19 个改动文件（+1338, -79）执行了复核。

**核心发现**：
1. **Standards 轴（0 Blocking Violations，0 Smells 留存）**：
   - 严格遵从 ADR-0001、ADR-0002 与 ADR-0008；`runtime:contract` 保持零侵入、零修改；
   - 完美复用策略中立的 `NetworkContract.OutboundNetworkSpec` 与 `PortForwardingContract` 模型；
   - 端口转发引擎 `AvfVsockPortForwarder` 实现自包含，宿主端口监听与 vsock 隧道按需建连，资源释放与热更语义严密；
   - 资产卫生零违背，零 `.log` 文件，Class A 永久资产保持完好，编译器 `-Xlint:all -Werror` 零告警通过。
2. **Spec 轴（AC-1 ~ AC-4 100% 达成闭环）**：
   - **AC-1（出站网络与 DNS）**：`AvfLaunchSpec` 正式装配出站网络规格，guest 命令行注入 `droidlayer.dns=`，反射适配器提供可选 cmdline 装配与 `setNetworkSupported(true)`；
   - **AC-2（端口转发与热更）**：`AvfVsockPortForwarder` 完整实现 `PortForwardingEngine` 缝隙，`addRule` 即时生效，`removeRule` 立即断开活跃连接并拒绝新连接，实现与 QEMU 档同构的热更失效语义；
   - **AC-3（契约等价性）**：`PortForwardingManagerContractTest` 提炼为共享驱动，`AvfNetworkForwardingParityContractTest` 在 AVF 引擎上完整运行持久化、冲突检测、热更对账、流量转发全部用例，验证了策略等价性；
   - **AC-4（能力矩阵）**：通过 `NetworkCapabilityDeclaration` 声明 AVF 档网络与转发能力为 `CONTRACT_VERIFIED`，诚实标记设备验证层级。
3. **本地实测验证**：
   - 全模块测试套件重新执行通过（`62/62 actionable tasks 100% PASS`）。

综合裁决结论：**【 APPROVE 】**。

---

## 评审裁定结论

# 【 APPROVE 】

Ticket 36 架构解耦优良，网络与端口转发热更语义与 QEMU 档高度一致，具备合入主分支（`main`）的准入条件。
