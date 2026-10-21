# P12: Host-native Termux/glibc/PRoot 兜底评估（零设备）

Type: research
Status: completed-research-zero-device（建议：本阶段不产品化；需更多证据）
Priority: P2（对照/兜底，不进 v1 主线）
Owner: research owner
Blocked by: None
External gates: 无

## 目标

回答：在 protected Fast Lane 不可用或能力不足时，宿主侧 Termux / glibc / PRoot 能否作为“无 Root、非 non-protected”设备的兜底 Linux 体验？它和 Fast Lane/QEMU 的边界是什么？

产出**决策建议**，不写产品承诺、不改 Runtime 类别。

## 零设备范围

- 源码/文档/基准方法调研；不要求设备；
- 可运行宿主 x86/arm64 的 PRoot/Termux 测试（如环境允许），但明确不是 Android 真机；
- 不把 PRoot 放进 protected。

## 必须回答

- [x] Termux 原生 Bionic 前缀的 CPU 性能/兼容性/包管理能力；与 Fast Lane 的关系。
- [x] Termux glibc/`glibc-runner` 能否原生跑 glibc 程序；路径/loader/许可证风险。
- [x] PRoot 的 syscall 开销证据（多来源，不引用单一数字）；适用/不适用 workload。
- [x] Host Termux/PRoot 与 QEMU Full Lane、Fast Lane 的能力矩阵对比。
- [x] 是否值得作为 Userspace Compatibility 的产品增强，还是只做内部对照。
- [x] 与 ACR-03/ADR-0001 的类别关系；不得静默新增类别。

## 联网调研

- Termux wiki/termux-app/termux-packages/termux-exec/termux-glibc；
- PRoot 官方文档/论文/社区基准；
- proot-distro；
- 本项目 `CONTEXT.md` 的 Userspace Compatibility Runtime；
- Android 14/15/16 对 ptrace/exec 的限制。

## 交付

- `docs/research/2026-10-21-host-native-lane-evaluation.md`（或追加到现有调研）；
- 能力/性能/许可证对比表；
- 明确建议：不产品化 / 作为兜底 / 需要更多证据；
- 不修改产品代码。

## 停止条件

- 需要真机才能回答：标 `not-tested`，不猜。
- 结论会改变 Runtime 类别：提交 ACR，不直接改 `CONTEXT.md`。

## Comments

- 2026-10-21 / Agent D：研究完成，未改 Runtime 类别/`CONTEXT.md`/产品代码。结论：**本阶段不产品化**；Host Termux/glibc/PRoot 只能归入既有 Userspace Compatibility，作为兜底候选/内部对照；需 G1 targetSdk/exec、G2 只读 APK 前缀、G3 Android 14/15/16、G4 本项目 PRoot 开销、G5 GPL/LGPL 五门全部有证据才可讨论产品化。
- 关键修正：既有调研“Microdroid 官方 neverallow ptrace”证据不足。AOSP `microdroid_payload.te`（`7836cbb29`）无 ptrace allow、也无显式 neverallow；SELinux 默认拒绝，PRoot 在 protected 内大概率不可用，但需真机探针，不能继续写“官方明确禁止”。
- 未验证：无 Android 真机、无宿主 PoC、无本项目性能 n≥10、无法务结论；所有本项目数字 `not-tested`。
- 报告：`docs/research/2026-10-21-host-native-lane-evaluation.md`；实施摘要：`docs/reports/tickets/fastlane-P12/implementation-report.zh-CN.md`。
