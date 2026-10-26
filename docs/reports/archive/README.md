# 历史报告归档

本目录保存已被后续阶段报告取代的工单实施、独立审查和编排记录。归档保留当时的验证范围与证据边界，**不代表当前版本状态**。

> 可见性：本归档包含少量来自开发者本地 ignored 资产的历史报告，未随公开仓库上传；fresh clone 中是否存在以 Git 跟踪文件为准。

- `2026-10-phase5-quality/`：Phase 5 与质量合流阶段的编排、工单与审查报告。
- `2026-10-v045/`：v0.4.5 发布、设备验证与 UX 修复阶段的报告。
- `2026-10-avf-history/`：AVF / Shizuku 工单中被最终轮复审取代的首审与复审记录。
- `2026-10-zero-device/`：零设备批次的工单报告（`runtime-performance-01..08`、`fastlane-P01..P12`、`avf-zero-device-01..10`）。
  这三批全部是「没有连接真机」的产物，报告内的实测章节为 `not-tested`；真机口径已由 `dv-pvm-*`、`fastlane-DV-01*` 与 `avf-shizuku-1x` 系列接管，那几批留在 `tickets/` 未归档。

当前有效入口：

- [当前报告索引](../README.md)
- [下一阶段优化方案（2026-10-18）](../orchestration/next-optimization-plan-20261018.zh-CN.md)
- [0.5.3 真机验收报告](../orchestration/avf-shizuku-device-20261016.zh-CN.md)

历史报告中的测试数量、版本号、签名状态和“通过 / 未通过”只覆盖当时所列范围。
