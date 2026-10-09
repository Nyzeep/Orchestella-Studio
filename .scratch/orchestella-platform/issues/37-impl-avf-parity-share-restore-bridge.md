# 37: AVF 档能力对齐——只读共享、恢复语义与桥接命令

Type: implementation
Status: completed

> **实施与评审记录**：2026-10-05 实施完成并经独立代码复审裁定 APPROVE（`docs/reports/reviews/37/review-report.zh-CN.md`）；`runtime:contract` 零修改；自研 9P2000.L 只读服务端经 vsock 数据通道（端口 9102）交付，AC-1/2/3/4 全量达成。

## What to build

硬件虚拟化档补齐剩余契约能力：只读共享 Downloads 经 **9p-over-vsock 数据通道**（自研 9p 服务端——参照已验证的可行规模，按自研立场实现，ADR-0009/0014）；优雅停止/冷恢复/异常标记的 vsock 路径对齐；`notify`/`open-url` 经 vsock 传输对齐。完成后 L2 用户获得与 L1 完全一致的功能面——MVP 全部契约测试在两个策略上全绿。

**Blocked by:** 34, 28, 30, 31

## Acceptance criteria

- [x] AVF 档实例内只读可见 Downloads 文件，写入被拒（与 30 相同验收）
- [x] AVF 档优雅停止/冷恢复/异常标记与 QEMU 档语义一致（与 28 相同验收）
- [x] AVF 档 notify/open-url 可用（与 31 相同验收）
- [x] MVP 全部契约测试在 QEMU 与 AVF 两个实现上全绿（能力矩阵两列对齐）

## Test strategy

既有共享/生命周期/桥接契约测试对 AVF 实现运行；9p 服务端协议合规性测试（对照 9p 行为规范选测关键操作）；真机冒烟。

## Prototype/research dependency

P1（17）数据；Podroid 的 9p-over-vsock 可行性规模作为旁证（docs/research/2026-09-05-podroid-源码分析.md §4）。
