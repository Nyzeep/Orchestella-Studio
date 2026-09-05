# 多实例模型：MVP 支持同时运行几个实例？

Labels: wayfinder:grilling
Type: grilling
Status: open

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
