# 完整发行版与基础服务

Type: research
Labels: wayfinder:research
Status: resolved
Assignee: guest-worker / coordinator
Parent: ../quality-20261013-map.md
Blocked by: none

## Question

DroidSpace、Podroid、PRoot 与官方发行版如何把 rootfs 装配为有 init、包管理器、DNS、证书、用户和基础服务的工作环境？Orchestella 当前 guest 启动链哪些部分导致只有简陋 shell？如何在现有 VM-first 契约内安全、幂等地完善新旧实例，且网络/安装失败不会被报告为完成？

## Comments

2026-10-13：分配 guest-worker 诊断和实现，coordinator 接管因认证失败停止的外部参考研究。源码仅下载公开输入，不上传本项目；随机凭据与现有 NAT 不变。

## Answer

确认两项根因：通用 init 写入会穿透发行版 symlink 损坏原生 init，旧 firstboot 完成标记提前写入导致配置被跳过。采用保留原生 init、私有恢复 supervisor、版本化必需包安装/验证、显式失败重试与真实 PID1/服务就绪门；用户旧数据与启动配置保留。每族按锁定 rootfs 和官方 ARM64 软件仓适配，随机凭据、校验链与 NAT 保持。Ubuntu 真机已验证 systemd/cgroup2、DBus/SSH、DNS、持久化和基线 ready；其他族动态结果由实施报告记录，研究结论不替代运行验收。

证据：[参考源码调查](../../../docs/research/2026-10-13-linux-reference-survey.md)、[guest 审计](../../../docs/research/2026-10-13-guest-runtime-audit.md)、[发行版矩阵](../../../docs/research/2026-10-13-distro-validation-matrix.md)。
