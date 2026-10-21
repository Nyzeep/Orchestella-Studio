# P05: Fast Lane 网络代理（零设备）

Type: implementation
Status: implemented（zero-device 验收通过；DV-02 真机待验）
Priority: P1
Owner: network owner
Blocked by: None（零设备）；与 P02 的 vsock 集成在 DV-02 验证
External gates: 无

## 目标

在 Fast Lane 没有原生网络的前提下，提供可用的 TCP 出口：

- 宿主 SOCKS5（RFC 1928）+ HTTP CONNECT，只绑 127.0.0.1；
- guest payload 在 vsock 上提供 proxy bridge；
- guest 工具通过 `ALL_PROXY`/`http_proxy`/`git http.proxy` 使用；
- DNS 由宿主代理解析（SOCKS5 域名）；
- 不承诺 ICMP/raw socket/UDP 广播/原生 `eth0`。

## 零设备范围

- 纯 JVM/宿主进程测试：本地 TCP 目标、DNS/TLS、并发/超时/半关闭；
- 用内存/本地 socket 模拟 vsock 侧；
- 不要求设备。

## 行为验收

- [ ] SOCKS5 无认证握手、CONNECT IPv4/域名、成功/失败回复码正确。
- [ ] HTTP CONNECT 解析、Host/Port、200/4xx/5xx、字节隧道。
- [ ] 只绑 127.0.0.1；拒绝非 loopback 绑定配置（除非显式 LAN 决策）。
- [ ] 连接上限/空闲超时/半关闭/重置/取消；无 fd/线程泄漏。
- [ ] DNS 失败、目标拒绝、TLS 握手失败有 typed reason；不把代理错误当成功。
- [ ] guest 侧 `curl`/`git`/`pip` 的配置文档（`ALL_PROXY` 等）。
- [ ] 不记录 URL 路径/凭据/正文；只记录字节数/结果。

## 联网调研

- RFC 1928 SOCKS5；HTTP CONNECT；
- gvisor-tap-vsock/gvproxy 的连接生命周期/转发 API（Apache-2.0）；
- Podroid `AvfTcpRelay`/`VsockPortForwarder`（只借鉴协议/生命周期）；
- 本项目 passt/gvproxy 的端口/绑定语义（Full Lane 不改）。

## 交付

- 宿主 `FastLaneProxyServer`（SOCKS5/HTTP CONNECT）+ tests；
- guest `proxy_bridge` 接口（与 P02 协作）；
- 配置文档 + 失败分类。

## 停止条件

- DNS/TLS/长连接无法稳定：Fast Lane 只做离线/终端；不声明联网。
- 需要 LAN 暴露：另做产品决策；默认不开放。

## Comments

- 2026-10-21（Agent B，`fastlane/agent-b-dataplane`）：零设备实现完成。`runtime/fastlane-proxy` 提供 SOCKS5（RFC 1928）+ HTTP CONNECT、loopback-only 绑定、连接/空闲上限、半关闭、typed failure、`serveChannel(FastLaneChannel)` guest 接口；JVM 32/32 测试通过（含 DNS/TLS/并发/超时/取消）。未验证真实 vsock 配对、Android SELinux、guest curl/git/pip；宿主需为每 tunnel 预开 channel。详见 `docs/reports/tickets/fastlane-P05/implementation-report.zh-CN.md`。
