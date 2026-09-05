# AVF 第三方边界（文档级）

Labels: wayfinder:research
Type: research
Status: resolved

## Question

一个无 Root 的第三方 App 在 2026 年的 Android 上，通过 AVF 到底能做什么、不能做什么？权限、API 形态、VM 类型、通信通道、设备覆盖的真实边界是什么？

## Answer

结论已核实并记录于 **docs/research/2026-09-05-前置研究独立审视.md** §2.1（AOSP 源码/官方文档逐条对照）。

要点：`android.system.virtualmachine` 全部 @SystemApi（API 34 起）；公开形态只能启动 Microdroid 载荷 VM；custom kernel/initrd/os 走 custom VM 配置（Android 16 起 `VirtualMachineCustomConfig`），需 `USE_CUSTOM_VIRTUAL_MACHINE`（signature|development，源码注明 "Not for use by third-party applications"，但 development 级可 adb pm grant）——Podroid/koiTerminal 即此路线；宿主↔VM 官方通道 vsock（VM 只能监听、仅 owner App 可连）；TCG 实测慢 5–20×（Linaro ~12×）；设备矩阵：Pixel 6+ 全支持、三星 Snapdragon 长期禁用（2026 Exynos S26 才开启）、AVF 对新 SoC 自 Android 15/16 起强制；Android 16 官方 Terminal 应用 = AVF Debian VM + Pixel 10 GPU 加速。

**剩余未知（移交原型票「AVF 端到端验证（真机）」）**：custom app VM 实机网络形态、GPU 配置对第三方是否可用、App 更新后 VM instance identity/secret 行为、OEM 实机差异。
