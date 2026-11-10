# 报告索引

报告按“当前有效”和“历史归档”分开。当前源码为 **0.6.2 / code602**，main 截至 2026-11-10（v0.6.2 + 150+ commits；tag v0.6.0–v0.6.2，见 [CHANGELOG](../../CHANGELOG.md)）；下方各报告的版本表述为其写作时的快照。

## 当前有效

### 主线与验收

- [零设备全项目大优化轮（2026-11-09 → 11-10）](grand-optimization-20261109.zh-CN.md)：六 lane 三批次——A 双 seam 拆分（QemuLaunchEvidence/FastLaneReflectionSupport +17 契约）、B 设计 token 基座（oe_design_tokens/Widget.Orchestella.*/四主题 surfaceLow/High/pressedFill）、C UML 加速四件套（资产对账/UmlChannel seam/wake lock 续期/宽度入账本 +47）、D Motion token 全族+ReportFullyDrawn+六项复审判词、E UX 修复（BACK 确认框/复位 no-op 根因/E-STORAGE-FULL 注入腿）、F 间距 token 化 96.6%+卡片三档；终集成 1339/0/0、lint 0e，main @ ec387e05；后续 T1 lane 合并至 ff279d64。
- [优化收口混合轮（2026-11-08 → 11-10）](optimization-closeout-20261108.zh-CN.md)：T1 ErrorCopy 单源统一（+7 固定码族，LaunchFailureCopy 委托壳化）、T2 动效挂钩+防残影守卫+pump 预算放宽、T3–T5 设备冒烟（磁盘校验/动效守卫/五页签/attach 跟随 PASS，BACK 无确认框缺陷立票）+ 性能确认 4 项全 PASS（首帧后权限弹窗/骨架→填充/3s 快照/冷启动体感）、W1 旧包清理（8 个 QA 包）、W2 UI 打磨（发丝线描边/幽灵禁用态/Material Symbols 矢量化 30 键/主题回归 7 点真机全 PASS）；1266/0/0，main @ 400151dc。
- [零设备全项目优化轮（2026-11-07）](zero-device-optimization-20261107.zh-CN.md)：四 lane 两批次——A 大文件拆分（InstanceLifecycle 1724→739、BridgeControl 847→593、ProtectedPoolKnobs 统一旋钮）、B Material Motion task 级全局过渡+token 体系、C 冷启动 18 项审计落地（主线程 SHA-256 后台化+记忆化、通知权限首帧后请求）、D ErrorCopy 固定码文案契约+向导/设置/终端 UX；集成冒烟 1260/0/0，CI 一次绿 @ 4ac7f248。
- [真机设备窗 2026-11-07（device-nova 首轮）](device-window-20261107.zh-CN.md)：T1 别名重建（4K 页/protected-only 候选）；T2 payload 缺件根因与重建（allowMissingPayload 构建产品 QEMU 必 IOException）；T4 票 27 真实事件（成功腿 10/16 门、guest_shutdown_acknowledged 缺口实测坐实、三失败腿 schema VALID、QEMU 产品链路真机全通）；T3 distcc@36 pVM 面平台级阻塞新形态（Gunyah vcpu crash exit 33，厂商 VM 正常）；T5① FabricOrchestrator.dispatch 零产品调用方结构性确认；T5② UML 面零设备切片并入 main（1227 测试）；T6 DV-01A 矩阵 +1（新形态）；T7 mode-facts qualification 4 pass / protected-only 实测。
- [更新卡片收敛与真机环境重置轮（2026-11-06）](ui-and-device-reset-20261106.zh-CN.md)：关于页两处更新入口合并为单卡（三态、只留 Release 说明摘要 +「在 GitHub 查看」），删除「前往下载/暂时忽略」与忽略状态；真机 6 个开发遗留包卸载与残留核验、QA `dvpvm` 构建安装与 UI 验证。
- [零设备收尾轮（2026-11-04）](orchestration/zero-device-closeout-20261104.zh-CN.md)：P1-1 `RfbInputEncoder` 补测 48 条、P13 `ACCEL_LOST` 预留泄漏修复（红→绿）、P0-2 `AGENTS.md` 时效刷新、P0-1 九份未入库证据文件快照；含中文路径 Gradle 单测坑复现与绕过。
- [工程质量积压清单（2026-11-04）](quality-backlog-20261104.zh-CN.md)：P0–P3 分级积压 21 项；对 2026-10-27 两份质量报告逐条核对落地状态、cleanup-plan §4 裁定项现状、AGENTS.md/CONTEXT.md 过期标记与全仓静默失败扫描。
- [交叉加速零设备进度与真机前置评估（2026-10-23）](orchestration/accelerator-zero-device-progress-20261023.zh-CN.md)：PL/AX 票状态、关键设计、测试证据、CI 历史失败与真机缺口；明确 AX-07/AX-08 未完成。
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
- [pVM 探针两包适配（2026-11-06）](tickets/pvm-probe-two-package/implementation-report.zh-CN.md)：`PvmProbeInstrumentation` 未随两包拆分更新，`/mnt/apk` 指主包导致 `Failed to find payload`；改为按扩展包 APK 设 `setApkPath` 后 VM 能起载荷，失败前移到 `PAYLOAD_EXIT_BEFORE_READY`（exit 1，真因待 guest console）。
- [票据收尾轮（2026-11-05）](tickets/ticket-closure-20261104/implementation-report.zh-CN.md)：fastlane P13（ACCEL_LOST 账本泄漏）闭环核验置 `implemented`（commit `b07dca0` 红绿反证）；avf-shizuku 27 核实未实现、补 agent-ready 开工说明。
- 批次三（2026-10-23）：[批次二修复](tickets/batch2-fixes/implementation-report.zh-CN.md)、[AX-02 选道](tickets/ax-02/implementation-report.zh-CN.md)、[AX-06 distcc 宿主 e2e](tickets/ax-06/implementation-report.zh-CN.md)。
- 真机交接：[真机交叉加速测试 Prompt](../agent-handoff/accelerator/device-cross-lane-test-prompt-20261023.zh-CN.md)：设备资格、AX-07/AX-08 前置门、分阶段测试与证据规则。
- [独立审查](reviews/)：最终轮 spec / standards 审查与设备证据复审。
- [原型报告](prototypes/)：隔离 QA 与研究原型的报告，不代表原产品升级或 Full Linux 硬件验收。

## 历史归档

- [归档说明](archive/README.md)
- `archive/2026-10-phase5-quality/`：Phase 5 与质量合流阶段。
- `archive/2026-10-v045/`：v0.4.5 发布与设备验证阶段。
- `archive/2026-10-avf-history/`：被最终轮取代的 AVF 审查轮次。
- `archive/2026-10-zero-device/`：零设备批次的工单报告（`runtime-performance-01..08`、`fastlane-P01..P12`、`avf-zero-device-01..10`）。

历史报告按当时快照理解；最新事实以 [docs/README.md](../README.md)、2026-10-21 Fast Lane 方案/Spec 为准；QEMU 发布线仍以 2026-10-18 方案的 P0 为准。
