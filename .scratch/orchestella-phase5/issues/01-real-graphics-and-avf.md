# 真实图形传输与 AVF 权限边界

Type: research
Status: resolved
Owner: graphics-research (supporting child; coordinator retains ticket)
Blocked by: none

## Question

第三方无 Root App 在实际 AVF API/权限下能配置哪些 GPU/传输能力？如何在现有 QEMU Full Linux 实例实现真实 guest frame → Android Surface 与输入回传，且严格验证尺寸、stride、资源限额、连接与失败状态？vsock、dma-buf/FD/blob、fencing 分别需要哪些特权？首个可执行 CPU fallback 是什么？

## Supporting output

`docs/research/2026-10-13-phase5-graphics-avf.md`；scope 见调度记录。首次研究必须基于官方源码、固定本地 revision/许可证，覆盖现有 QemuWaylandChannel/AvfWaylandChannel/图形契约。禁止修改生产/共享协议或 tracker。未经设备交接不运行 adb。研究后继续实施规格，不把研究当已实测。

## Comments

2026-10-13：协调者在派发前认领研究节点。

## Answer

研究已完成，官方URL/revision/许可、权限和未运行边界：[正式研究](../../../docs/research/2026-10-13-phase5-graphics-avf.md)。本答案是决策证据，不是设备验收。后续源码/本地APK与验证矩阵已交付。
