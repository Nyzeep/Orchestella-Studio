---
status: accepted
date: 2026-09-05
amended: 2026-10-01
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

## Amendments & Command Specifications (2026-10-01 修订：Ticket 31 通知与打开链接语义)

1. **`notify` 命令线协议规范**：
   - 线格式：`DROIDLAYER/NOTIFY <title> <message>\n`；
   - 字段编码：`<title>` 与 `<message>` 建议使用标准 URL 编码（若带空格或特殊字符，如 `Build%20Done`）或 `base64:<data>` 前缀编码，彻底规避以空格分词造成的参数歧义；
   - 参数边界：单行与单字段上限为 2048 字符；
   - 响应码：
     - 成功受理：`DROIDLAYER/OK notify\n`
     - 格式畸形或超长：`DROIDLAYER/ERROR malformed-command\n`
     - 宿主通知未就绪或被用户关闭：`DROIDLAYER/ERROR notify-unavailable\n`
   - Android 宿主实现：绑定通知渠道 `droidlayer_guest_notifications`（`IMPORTANCE_DEFAULT`），携带启动应用的主活动 `PendingIntent`，支持点击与滑动清除。
2. **`open-url` 命令线协议规范**：
   - 线格式：`DROIDLAYER/OPEN-URL <safe-http-url>\n`；
   - 安全白名单：仅放行 `^[hH][tT][tT][pP][sS]?://.+`（严格限制 `http://` 与 `https://` Scheme 且必须具备非空 Host）；
   - 参数边界：URL 长度上限 2048 字符，禁止包含换行符或控制字符；
   - 违规处理：非 HTTP/HTTPS 协议（如 `file://`、`content://`、`intent:`、`javascript:` 等）或语法异常均确定性拒斥为 `DROIDLAYER/ERROR malformed-command\n`；
   - 响应码：
     - 成功受理：`DROIDLAYER/OK open-url\n`
     - 格式畸形或非法 Scheme：`DROIDLAYER/ERROR malformed-command\n`
     - 宿主无可用应用处理：`DROIDLAYER/ERROR open-url-unavailable\n`
   - Android 宿主实现：通过 `Intent.ACTION_VIEW` 配合 `FLAG_ACTIVITY_NEW_TASK` 调用系统默认应用，捕获 `ActivityNotFoundException` 与 `SecurityException` 兜底。
