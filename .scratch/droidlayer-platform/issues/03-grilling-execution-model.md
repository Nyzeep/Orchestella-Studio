# 执行模型：VM-first 确认与 PRoot 取舍

Labels: wayfinder:grilling
Type: grilling
Status: open

## Question

本项目的执行模型是否正式定为「VM-first（guest 自带内核）+ guest 内容器」，PRoot 是否进入正式产品架构（还是不做/仅作受限工具）？

## Why it matters

第一性决策：决定 guest 是否拥有自己的 kernel，从而决定 namespace/cgroup/systemd/Docker 能力来自 guest 还是 Android host，决定与 Droidspaces 路线的分界，也决定 VmEngine 抽象要不要为"非 VM"后端扭曲。

## Known facts

- AVF 设备上 VM 近原生；QEMU TCG 慢 5–20×（可测）；PRoot 靠 ptrace 系统调用拦截，无真实隔离与内核能力（proot-distro 自述局限）。
- Podroid 无 proot 路径，纯 VM 已支撑 Podman/Docker/LXC（源码核实，见 docs/research/2026-09-05-podroid-源码分析.md）。
- 前置讨论默认把 PRoot 列为 Priority-3 后端——该默认未经对齐，且与 VM 领域模型不同质。

## Working recommendation

VM-first 确认；PRoot 不进 MVP 架构，也不做成 VmEngine 第三后端；最多在未来作为受限"工具箱模式"独立评估。

## Reversibility

PRoot 现在不做 → 未来补做成本低（独立子系统）；一旦做成 VmEngine 后端并把差异泄漏进上层 API，撤出成本高。
