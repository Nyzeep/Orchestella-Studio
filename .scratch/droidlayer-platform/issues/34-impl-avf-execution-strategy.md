# 34: AVF 执行策略——同一契约跑硬件虚拟化

Type: implementation
Status: completed

> **评审轮记录**：2026-10-05 独立复审裁定 CHANGES_REQUESTED（`docs/reports/reviews/34/review-report.zh-CN.md`）；同日整改轮完成（架构解耦、真机数值面、vCPU、连接防卫、句柄恢复、UI去重），全量测试复跑通过；二次复查裁定 APPROVE。

## What to build

在支持 AVF 的设备上，用户经授权向导（先检测后引导，ADR-0003）完成一次性授权后，同一实例以硬件虚拟化运行，性能徽章切换为"完整 Linux · 硬件加速"。本票证明第二执行策略穿过与 26 相同的切片：创建 Alpine 实例 → 启动（custom VM：自定义内核/initrd/根盘）→ vsock 桥接传输 → shell；实例资产可在两个策略间互换（同一根盘两处可启动）。能力检测（feature flag、权限状态、custom VM 能力谱）驱动策略选择与向导（ADR-0001/0003）。

**刻意不等待 27–33**：本票只证明契约核心（生命周期/终端/桥接传输/装配），出站网络、转发、共享、恢复的 AVF 档对齐由票 36/37 承接——尽早让第二策略暴露契约缺陷（用户裁定的依赖结构）。

**Blocked by:** 26, 17（P1 原型结果）

## Acceptance criteria

- [ ] 真机（AVF 设备）上创建并启动 AVF 档实例并进入 shell，徽章显示"硬件加速"（**待 HITL 真机验收**：依赖 P1 原型票 17 的真机数据；本票已交付 JVM 模拟实现与全部模拟契约测试）
- [x] 授权向导端到端走通（检测 → 引导 → 授权 → 可用），检测不支持的设备不展示向导（代码与状态机已交付：`AvfCapabilityEvaluator` + `AvfAuthorizationWizardModel` + MainActivity 向导，纯 JVM 测试全绿；端到端真机复检随真机验收执行）
- [x] 同一根盘资产在 QEMU 档与 AVF 档均可启动（互换验证，ADR-0001 承诺兑现）（规格级互换验证 `AvfQemuInteropTest` 全绿：同一 Image/initramfs/raw 根盘路径零转换直供双引擎；双引擎实机互换启动待真机验收）
- [x] 契约测试的核心条目（生命周期/终端/桥接握手）在 AVF 实现上通过——同一套测试（6 个测试任务 100% 绿：同一 `BridgeClient` 原线协议、终端字节流、实例五态生命周期均在模拟 guest 上通过）
- [x] 反射失败的设备上透明回退 QEMU 档并给出原因（ADR-0002 能力声明机制）（`InstanceStrategyLauncher` 自动回退 + `AvfDegradationLog` 原因记录，失败原因映射与回退链路测试全绿）

## Test strategy

同一契约测试套件（本票是"不复制两套行为规范"的兑现点）；能力检测纯逻辑 JVM 测试；真机 AVF 冒烟。

## Prototype/research dependency

**硬依赖 P1（17）**：custom VM 启动/网络形态/instance 身份的实测数据。P1 若发现网络不可用或 instance 身份随更新失效，先重开对应决策再实施。
