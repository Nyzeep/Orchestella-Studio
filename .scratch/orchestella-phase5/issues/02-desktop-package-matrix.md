# 多发行版桌面组合与恢复事务

Type: research
Status: resolved
Owner: desktop-research (supporting child; coordinator retains ticket)
Blocked by: none

## Question

七个真实 ARM64 发行版的 KDE/niri/labwc/轻量桌面包、版本与 seat/DBus/logind/renderer 依赖是什么？选择哪个首组合可以实现普通用户可恢复启动与真实帧握手？如何保留终端默认、包签名与用户配置，失败不写 ready、不破坏基础 Linux？

## Supporting output

`docs/research/2026-10-13-phase5-desktop-matrix.md`。优先核对活动主仓现有 distro 矩阵、M2/M3/M4 spec、质量规格与 ADR；报告无绿色实测虚报。不得修改 App UI、guest init、tracker、ADR。固定引用和参考源码许可。

## Comments

2026-10-13：协调者在派发前认领研究节点。

## Answer

研究已完成，官方URL/revision/许可、权限和未运行边界：[正式研究](../../../docs/research/2026-10-13-phase5-desktop-matrix.md)。本答案是决策证据，不是设备验收。后续源码/本地APK与验证矩阵已交付。
