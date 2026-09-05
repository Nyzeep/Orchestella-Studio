---
status: accepted
date: 2026-09-05
---

# 宿主↔Guest 桥接协议：传输无关、版本化、token 鉴权；控制面/数据面分离

1. **单一传输无关协议 v1**：握手 → 版本协商 → 命令集 → 应答模型；**per-VM 鉴权 token**（宿主生成、Provisioning 注入 guest、宿主侧校验）。传输映射：AVF=vsock、QEMU=virtio-console；协议不感知传输，新执行策略只需提供"一条字节流"。
2. **MVP 命令集（六条）**：`ping/version`、`resize`、`port-forward add/remove`、`notify`、`open-url`、`power`。剪贴板等一律不进 MVP（雾区跟踪）。
3. **控制面/数据面分离**：文件共享走独立数据通道，语义统一为"共享目录"（QEMU=virtio-9p、AVF=9p-over-vsock，Podroid 双路径实证）；MVP 仅**只读共享 Downloads**，读写为增强。桥协议永不搬运文件内容。
4. **兼容规则**：宿主向后兼容 N-1 版 guest agent；协商失败时新命令优雅降级。Guest agent = 静态单二进制，随 Provisioning 按 DistroManifest 风味安装为 OpenRC/systemd 服务。

## Considered Options

- **不做版本协商（否决）**：guest agent 随根盘老化（用户可长期不升级实例），无协商则每个新命令都是存量用户的兼容性地雷。
- **文件共享并入控制协议（否决）**：控制面被大流量与复杂语义污染；分离后两边各自简单、可独立优化。
- **无鉴权、依赖传输层隔离（否决）**：guest 是跑任意代码的环境，可能被攻破；"guest 能指挥宿主"的每个动作都需要应用层授权——per-VM token 是 ADR-0003 安全立场的执行。

## Consequences

- token 注入依赖 Provisioning 流程：注入失败 = 桥不可用，provisioning 测试必须覆盖。
- 9p 双实现工作量：QEMU 档现成；AVF 档 9p-over-vsock 服务端需自研（Podroid 886 行 Kotlin 证明可行规模；许可证立场要求自行实现）。
- per-distro agent 打包（风味脚本）是持续维护面，随发行版清单扩展。
- 命令集扩张须走"先协商、后实现、进版本"流程——新增能力不许静默出现。
