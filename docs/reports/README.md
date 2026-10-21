# 报告索引

报告按“当前有效”和“历史归档”分开。当前源码为 **0.5.3 / code503**，公开安装包仍为 **v0.4.5 开发预览版**。

## 当前有效

### 主线与验收

- [Fast Lane × QEMU 交叉优化 Spec（2026-10-21）](orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md)：当前实现规格；P01–P12 并行零设备模块、DV-01–DV-04 串行真机门、distcc 跨车道与能力矩阵。
- [Protected Fast Lane + QEMU Full Lane（2026-10-21）](orchestration/protected-fast-lane-plan-20261021.zh-CN.md)：方向与硬边界；protected Microdroid 密封 Linux toolbox、QEMU Full Lane、构建 offload。
- [Fast Lane 集成分支（2026-10-21）](orchestration/fastlane-integration-20261021.zh-CN.md)：四条模块分支合并、FLN1 adapter、CI 全模块门禁与证据分级；真实 Android 构建 `BUILD_BLOCKED`。
- [下一阶段优化方案（2026-10-18）](orchestration/next-optimization-plan-20261018.zh-CN.md)：QEMU 主线、发布签名 P0、运行时收敛；其 AVF 排序已由 2026-10-21 方案重排，P0/P1 仍有效。
- [P0-2 QEMU / gvproxy 运行时生命周期收敛（2026-10-18）](orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md)：单一 RuntimeProcessSupervisor、有界停止、gvproxy handle 与未确认停止保留 RUNNING；纯 JVM/宿主进程验证，真机未测。
- [维护与优化 Prompt（2026-10-18）](orchestration/optimization-prompt-20261018.zh-CN.md)：本回合清理、文档归档、P0-3 单实例竞态修复与验证记录，含可直接交接的中文 Prompt。
- [0.5.3 限定实现、构建与真机验收](orchestration/avf-shizuku-device-20261016.zh-CN.md)：747 项 App 测试、lint 0 Error / 748 Warning、8 项 Android 框架场景与 Debian QA 结果。
- [源码与中文成果同步（2026-10-17）](orchestration/github-source-sync-20261017.zh-CN.md)：0.5.3 可靠性改进与 Protected AVF 规划的公开同步记录。
- [Protected 优先近期方案（2026-10-17）](orchestration/protected-avf-near-term-plan-20261017.zh-CN.md)：Protected 专项门与证据要求；其总体优先级已被 2026-10-18 方案重排。

### AVF / Shizuku 工单与审查

- [工单实施报告](tickets/)：当前 AVF / Shizuku 工单的实施记录。
- [独立审查](reviews/)：最终轮 spec / standards 审查与设备证据复审。
- [原型报告](prototypes/)：隔离 QA 与研究原型的报告，不代表原产品升级或 Full Linux 硬件验收。

## 历史归档

- [归档说明](archive/README.md)
- `archive/2026-10-phase5-quality/`：Phase 5 与质量合流阶段。
- `archive/2026-10-v045/`：v0.4.5 发布与设备验证阶段。
- `archive/2026-10-avf-history/`：被最终轮取代的 AVF 审查轮次。

历史报告按当时快照理解；最新事实以 [docs/README.md](../README.md)、2026-10-21 Fast Lane 方案/Spec 为准；QEMU 发布线仍以 2026-10-18 方案的 P0 为准。
