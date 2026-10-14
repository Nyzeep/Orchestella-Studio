# Rootless 容器与本地开发服务

Type: research
Status: resolved
Owner: productivity-research (supporting child; coordinator retains ticket)
Blocked by: none

## Question

当前 guest kernel 的 rootless Podman 条件、Docker CLI/daemon 区别、各发行版 ARM64 软件供应与验证步骤是什么？code-server 与官方 VS Code Server 的许可/认证/网络差别是什么？如何落地默认回环、每实例随机凭据、明确 LAN 开关的生命周期，安全手机 WebView 与现有端口转发适配？

## Supporting output

`docs/research/2026-10-13-phase5-productivity.md`。固定公开源码、官方输入及许可证。参考 guest runtime 修复与桥接/网络 ADR。不得启用云 tunnel、外部账号、默认 Docker TCP/共享 socket；不得改生产代码/共享协议。引用结论与未实测项目明确区分。

## Comments

2026-10-13：协调者在派发前认领研究节点。

## Answer

研究已完成，官方URL/revision/许可、权限和未运行边界：[正式研究](../../../docs/research/2026-10-13-phase5-productivity.md)。本答案是决策证据，不是设备验收。后续源码/本地APK与验证矩阵已交付。
