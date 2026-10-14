# OTG 权限与只读共享数据面

Type: research
Status: resolved
Owner: storage-research (supporting child; coordinator retains ticket)
Blocked by: none

## Question

Android USB Host 用户授权 FD 如何与 QEMU/libusb 对接，各传输类型、热拔出/取消边界是什么？无 Root usbip/AVF 直通真实可行性是什么？打包 QEMU 与 guest 的 9p/virtiofs 支持、SAF URI/FD/只读 Downloads 边界如何实现？首条可交付数据面与合成基准是什么？

## Supporting output

`docs/research/2026-10-13-phase5-usb-storage.md`。只读已有参考；新增参考固定 revision/许可，禁止 Windows 服务/驱动安装。不得扩大只读 Downloads 的既有暴露面。用合成数据提出断流、路径/URI/symlink escape、拒写和 FD 生命周期测试。不得修改 production/tracker/ADR 或接触真机。

## Comments

2026-10-13：协调者在派发前认领研究节点。

## Answer

研究已完成，官方URL/revision/许可、权限和未运行边界：[正式研究](../../../docs/research/2026-10-13-phase5-usb-storage.md)。本答案是决策证据，不是设备验收。后续源码/本地APK与验证矩阵已交付。
