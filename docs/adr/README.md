# 架构决策记录（ADR）索引

本目录保存已接受的架构决策。ADR 正文是治理记录，不因实现版本变化而静默改写；与后续证据冲突时，通过 ACR（架构变更请求）裁定。

| 编号 | 标题 | 状态 |
| --- | --- | --- |
| [0001](0001-two-runtime-classes.md) | 两层 Runtime 类别：VM-first Full Linux 与独立 Userspace Compatibility | accepted |
| [0002](0002-full-linux-contract.md) | Full Linux 契约：策略中性的三层划分与三条纪律 | accepted |
| [0003](0003-rootless-tiers.md) | 无 Root 三级承诺：L1 + L2，L2 配应用内授权向导 | accepted |
| [0004](0004-compat-floor.md) | 兼容性地板：minSdk 26 / arm64-only / RAM ≥ 3GB | accepted |
| [0005](0005-guest-kernel-contract.md) | Guest Kernel 契约：pinned 版本、构建校验、CI 复现 | accepted |
| [0006](0006-distro-model.md) | 发行版模型：官方 tarball + 首次引导 Provisioning | accepted |
| [0007](0007-storage-model.md) | 存储模型：raw 稀疏单根盘 + SAF 导出 | accepted |
| [0008](0008-networking-model.md) | 网络模型：默认出站 NAT + 显式端口转发 | accepted |
| [0009](0009-guest-bridge-protocol.md) | Guest Bridge：传输无关、版本化、token 鉴权 | accepted |
| [0010](0010-security-boundary.md) | 安全边界：隔离承诺分级 + 暴露面白名单 | accepted（pVM 通信表述待 ACR-02 裁定） |
| [0011](0011-lifecycle.md) | 生命周期：前台可靠、后台尽力而为、冷恢复 | accepted |
| [0012](0012-graphics-phasing.md) | 图形路线分期：MVP 终端 only，四里程碑渐进 | accepted |
| [0013](0013-usb-audio-scope.md) | USB / 音频范围：MVP 不做 | accepted |
| [0014](0014-licensing.md) | 许可证策略：GPL-3.0-or-later | accepted |
| 0015 | *缺失*：release channels 决策记录未找到 | 缺失，禁止按标题补造 |
| [0016](0016-multi-instance-and-entities.md) | 多实例模型：MVP 单运行实例；实体分离 | accepted |
| [0017](0017-wayland-graphics-channel.md) | Wayland 原生移动图形通道 | accepted |
| [0018](0018-audio-and-clipboard-bridge.md) | 系统级音频、剪贴板与外设指针 | accepted |
| [0019](0019-gpu-hardware-acceleration.md) | GPU 硬件加速渲染与驱动隔离 | accepted |
| [0020](0020-runtime-process-ownership.md) | 运行时进程所有权与有界停止 | accepted |
| [0021](0021-avf-runtime-ownership.md) | AVF 运行时所有权、停止与退出分类（扩展 ADR-0020 范围） | accepted |

## 已知缺口

- ADR-0015 文件不存在；旧交接与研究中的引用只能视为“计划中的编号”，不是已接受决策。
- ADR-0010 关于 protected VM 双向通信的绝对表述与后续官方 vsock 证据冲突，当前由 [ACR-02](../../.scratch/orchestella-avf-shizuku/issues/ACR-02-protection-semantics.md) 保留未决状态；不得直接改写 accepted 正文。
- ADR-0017 / 0018 / 0019 引用的部分研究文档未纳入公开仓库；引用标题不等于正文存在。

## 实现差异说明（2026-10-20，非正文修订）

ADR 正文不因实现版本变化而改写；以下只记录当前代码与已接受目标的差异，供后续实现/评审对照。

- **ADR-0017（Wayland）**：当前生产路径是 RFB over LocalSocket（QEMU）/ vsock（AVF），能力枚举为 `GRAPHICS_RFB`；`WaylandChannel` 是历史接口名，不等于 Wayland/DLFR 已接线。`GRAPHICS_WAYLAND` 仍只在真实 Wayland 适配器端到端接线后声明。
- **ADR-0018（音频/剪贴板/指针）**：AVF v1 的 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE` 均为 `UNSUPPORTED`（没有 guest 适配器）。QEMU 的音频播放、剪贴板桥接和 Android 指针捕获由 `DesktopActivity`/UI 驱动，**不是** session capability；在出现 session-owned 适配器前不得写进能力矩阵。
- **ADR-0019（GPU）**：AVF v1 不声明 `GPU_HARDWARE_ACCELERATION`；只有 RFB 的 CPU 拷贝显示。QEMU 的 VirGL 状态仍按其实际接线声明。
- **ADR-0021（AVF 所有权）**：`AvfSession` 的 `close()`/`terminate()`/池退休共用 `stopRuntime(graceMs)`；`AvfSession` 非 `final` 只是 02 的测试 seam，不是新的扩展点。
