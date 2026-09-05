# 多实例模型：MVP 支持同时运行几个实例？

Labels: wayfinder:grilling
Type: grilling
Status: resolved

## Question

MVP 的实例并发语义：同时运行几个 Full Linux 实例（1 个？多个？）？多实例时的内存/CPU 预算如何分配与呈现？创建多实例是否受鼓励（存储与内存的现实约束）？

## Why it matters

决定实例管理的 UI 复杂度、资源预算算法（ADR-0004 的 RAM 地板只是创建门槛，运行时预算是另一回事）、以及"实例状态持久化 + 冷恢复"（ADR-0011）在多实例下的语义。参考：Podroid 为单实例模型（每后端一个命名 VM）。

## Known facts

- QEMU 档：每实例 = 一个 VMM 进程持有 GB 级 RSS；两实例并存直接翻倍内存压力。
- AVF 档：多 VM 由 VirtualizationService 管理理论上可行，但内存预算与调度行为未实测（原型票可带验证）。
- ADR-0004 的 RAM ≥ 3GB 地板 + guest 默认 8GB 根盘：多实例并存的实际可行区间很窄。

## Working recommendation

MVP = **单运行实例**（同时只跑一个，可存在多个已停止实例）；多实例并发列为后续增强（能力矩阵声明），等真机内存数据。待 grilling 确认。

## Reversibility

单→多是加法（资源预算器扩展）；多→单收缩伤 UI 与用户预期。先单后多符合可逆性。

## Answer

2026-09-05 用户采纳推荐并补充领域模型要求。固化于 **docs/adr/0016-multi-instance-and-entities.md**（含三层标注）与 CONTEXT.md 新增实体词条。

决定要点：
1. MVP 单运行实例；多已停止实例并存；切换 = 停止当前 + 冷启动；多实例并发为后续增强（真机数据决定解锁）。
2. **领域模型 Distro ≠ Image ≠ Instance ≠ Session** 写入 ADR 与 CONTEXT.md：Instance 为产品基础实体；Session 为与运行中实例的一次交互/访问上下文；MVP 单运行 = Session↔Instance 配对约束，**不是实体合并**——未来并发只放宽配对约束，实体模型不变。
3. 证据标注：单实例资源现实性为**类比证据**（Podroid DeviceResourcePolicy 旁证）；AVF 多 VM 行为、解锁阈值标【待验证】（并入原型票观察清单）。
