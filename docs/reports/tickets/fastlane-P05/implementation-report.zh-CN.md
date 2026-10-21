# P05 Fast Lane 网络代理 — 实现报告（零设备）

状态：零设备实现与 JVM 测试完成；真机（DV-02）未验证。
分支：`fastlane/agent-b-dataplane`。日期：2026-10-21。

## 1. 目标与范围

在 Fast Lane 没有原生网络的约束下，提供宿主侧 TCP 出口：

- 宿主 `FastLaneProxyServer`：SOCKS5（RFC 1928）+ HTTP CONNECT，默认只绑 `127.0.0.1`；
- guest payload 的 proxy bridge 与宿主接口（P02 协作）；
- DNS 在宿主解析；连接/空闲超时、连接上限、半关闭、取消；
- typed failure；不记录 URL 路径/凭据/正文。

不包含：ICMP/raw socket/UDP 广播/原生 `eth0`、LAN 暴露、在 guest 内运行 SOCKS5 解析器。

## 2. 设计 seam（先于实现）

1. **拓扑**：guest 不能主动 `connect()` vsock，因此宿主为每个 tunnel 预开一条 vsock channel；payload 只做本地工具连接 ↔ channel 的字节配对。
2. **协议位置**：SOCKS5/HTTP CONNECT 由**宿主**解析；payload 不解析代理协议，避免 guest 侧协议分叉。
3. **宿主入口**：`start()` 绑定 `127.0.0.1:<port>`（测试/宿主本地工具）；`serveChannel(FastLaneChannel)` 服务一条 guest tunnel（生产路径）。
4. **每连接一个 tunnel**：`ProxyTunnel` 拥有 client/channel 与 target socket；双向泵、半关闭、空闲看门狗。
5. **DNS**：`DnsTargetConnector` 在宿主 `InetAddress.getAllByName`；guest 永远不解析域名。
6. **目标策略**：默认拒绝 loopback/link-local/multicast/wildcard 目标，避免 guest 经代理访问宿主本地服务；测试显式开启 loopback。
7. **SOCKS5**：仅无认证、CONNECT；IPv4/域名；IPv6、BIND、UDP ASSOCIATE 用 RFC 回复码拒绝。
8. **HTTP**：仅 CONNECT + `host:port`；限制请求行/头部大小；不回显/转发 header。
9. **失败分类**：`PROXY_PROTOCOL_FAILED`、`PROXY_CONNECT_FAILED`、`PROXY_DNS_FAILED`、`PROXY_TIMEOUT`、`PROXY_LIMIT_EXCEEDED`、`PROXY_CANCELLED`；映射到 SOCKS REP/HTTP 状态。
10. **半关闭**：socket 模式上游 EOF → `target.shutdownOutput()`，下游 EOF → `client.shutdownOutput()`；两泵都结束才关闭。
11. **channel 模式**：vsock 无 `shutdown`，上游 EOF 仍关 target 写端；下游 EOF 关闭 channel。
12. **上限**：`maxConnections`、`connectTimeoutMs`、`idleTimeoutMs`、16 KiB 缓冲；超限连接在握手前关闭。
13. **取消/停止**：`close()` 关闭 listener、watchdog、所有 tunnel；停止不会留下半开 fd。
14. **隐私**：只计数 accepted/active/bytes/failure；不记录目标、URL、header、正文。
15. **测试接缝**：`TargetConnector` 可注入；`FastLaneChannel` 可用 socket 包装；本地 echo/TLS 服务器。

## 3. 改动

新增 `runtime/fastlane-proxy/README.md` 与：

- `ProxyFailure`、`ProxyException`、`ProxyConfig`、`ProxyTarget`；
- `TargetConnector`、`DnsTargetConnector`；
- `Socks5Protocol`、`HttpConnectProtocol`；
- `ProxyTunnel`（package-private）；
- `FastLaneProxyServer`；
- 测试：`ProxyConfigTest`、`Socks5ProtocolTest`、`HttpConnectProtocolTest`、`FastLaneProxyServerTest`。

没有修改 `settings.gradle.kts`、`runtime/engine-fastlane` 或其他 agent 的模块。

## 4. 行为与接口

### SOCKS5

- greeting：`VER=5`、`NMETHODS`、method list；只接受 `0x00`，否则回 `05 FF` 并关闭；
- CONNECT：`VER/CMD/RSV/ATYP`；ATYP 1（IPv4）和 3（域名）支持；ATYP 4 回 `0x08`；BIND/UDP 回 `0x07`；
- 成功回复：`05 00 00 01 <bound IPv4> <port>`；失败按 `ProxyFailure.socksReply()`。
- 连接失败：`PROXY_CONNECT_FAILED` → `0x05`（connection refused）；DNS → `0x04`；超时 → `0x06`。

### HTTP CONNECT

- 只接受 `CONNECT host:port HTTP/1.0|1.1`；绝对 URL/非 CONNECT/缺端口/IPv6 字面量 → 400/405/501；
- 成功 `HTTP/1.1 200 Connection Established`；失败 502/504/503，`Content-Length: 0`、`Connection: close`；
- 请求行 ≤ 8192 字节、头部总量 ≤ 16384 字节。

### 限制与生命周期

- `ProxyConfig` 拒绝非 IPv4 loopback 绑定、非法上限/超时/缓冲；
- `maxConnections` 超限：accept 后立即关闭，`rejectedConnections` 增加；
- `idleTimeoutMs`：socket 读超时 + 每秒 watchdog；两端都无活动即关闭；
- 半关闭：见 §2.10；`close()` 关闭 listener + 所有 tunnel + watchdog；
- `serveChannel()` 在独立线程运行，阻塞到 tunnel 关闭；宿主 engine 每条 guest tunnel 调一次。

### 隐私

所有日志/异常消息只含失败类别与通用描述；`ProxyTarget.toString()` 不返回 host/port。没有 URL/header/正文写入。

## 5. 测试命令与结果

环境无 Android SDK/JDK 17；用 JDK 21 + `--release 17`：

```sh
javac --release 17 -Xlint:all -Werror -d <out> \
  -cp junit-4.13.2.jar \
  runtime/engine-fastlane/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/*.java \
  runtime/fastlane-proxy/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/proxy/*.java \
  runtime/fastlane-proxy/src/test/java/io/github/nyzeep/orchestella/runtime/fastlane/proxy/*.java
java -cp <out>:junit-4.13.2.jar:hamcrest-core-1.3.jar org.junit.runner.JUnitCore \
  ...proxy.ProxyConfigTest ...proxy.Socks5ProtocolTest \
  ...proxy.HttpConnectProtocolTest ...proxy.FastLaneProxyServerTest
```

结果：`OK (32 tests)`：

- `ProxyConfigTest` 4：loopback 接受、非 loopback/IPv6/非法上限拒绝；
- `Socks5ProtocolTest` 7：IPv4/域名 CONNECT、IPv6 回复 0x08、BIND 0x07、无认证方法 0xFF、截断、失败回复字节；
- `HttpConnectProtocolTest` 8：CONNECT+headers、IPv4、绝对 URL/缺端口/IPv6/超长行拒绝、200/504 响应；
- `FastLaneProxyServerTest` 13：SOCKS IPv4/域名、HTTP CONNECT、DNS 失败 0x04、超时 0x06、拒绝 0x05、HTTP 504、连接上限、半关闭、空闲超时、close、`serveChannel`、TLS 透传（测试运行时用 JDK `keytool` 生成临时 PKCS#12，不提交任何密钥）。

TLS 测试是“代理字节透明”的证据：`SSLSocket` 经 SOCKS tunnel 完成握手并回显；证书仅在临时目录生成，测试后删除。

## 6. 未验证 / not-tested

- 真实 vsock channel 与 P02 payload 的配对（DV-02）；当前 `serveChannel` 由 socket 包装 channel 测试；
- Android SELinux 是否允许 guest 本地 TCP loopback / UDS（payload 报告的 proxy 能力需按实际端点降级）；
- guest `curl`/`git`/`pip` 对 `ALL_PROXY` 与 UDS 代理的实际支持；
- 真实 SAF/Android 生命周期、长时间大流量、真实 DNS/TLS 服务器；
- `FastLaneSession` 目前对同一 vsock 端口复用单 channel，生产接线需要 P01 提供“每连接新 channel”的 factory（本模块只定义接口）。

## 7. 研究来源（一手）

- RFC 1928（SOCKS5）：greeting/method、CONNECT、ATYP、reply codes（0x00–0x08）、BIND/UDP 语义。https://www.rfc-editor.org/rfc/rfc1928.txt
- `references/gvisor-tap-vsock` @ `1cd3f04` README：gvproxy 的 `-listen vsock://`/unix socket 与 guest 侧 `gvforwarder` 需要 TAP；本实现只借鉴连接生命周期，不使用 guest TAP。
- P09 现有 `runtime/fastlane-distcc/DistccTcpVsockBridge`：loopback-only、每连接 channel、双向字节泵、连接上限/关闭收敛；P05 采用同样的所有权模型。
- 本仓 SAF 参考：`app/src/main/java/.../AndroidSafExportAdapter.java`、`AndroidSafImportAdapter.java`（流接口与 fail-closed 语义，见 P06）。
- AOSP Microdroid SELinux：`microdroid_payload` 无通用 tcp_socket allow；见 P02 报告来源。

## 8. 跨 agent 契约

- **P02**：宿主 `serveChannel(FastLaneChannel)` 是 guest proxy bridge 的 host 端接口；每条 tunnel 一条 channel。payload 只配对本地连接与 channel，不解析 SOCKS5。
- **P01**：需要提供“每次调用都新建 vsock 连接”的 channel factory；`FastLaneSession` 的缓存行为不能直接用于 proxy 并发。
- **P09**：不共用代码；distcc 的宿主 TCP↔vsock 桥仍由 Agent C 维护。
- **P10**：本模块只暴露计数（accepted/active/bytes/failure），不产生事件；事件接入由 P10 决定。

## 9. 风险与下一步

1. 若 guest 既不允许 TCP loopback 也不支持 UDS 代理，`ALL_PROXY` 工具无法使用；应在 DV-02 明确记录并只承诺“支持 UDS 的工具”。
2. 默认拒绝 loopback 目标会改变“本地测试目标”的行为；生产 UI 不应提供“允许访问宿主 loopback”的开关而不做产品决策。
3. channel 模式无法真正半关闭；POST/长连接场景需在 DV-02 验证。
4. 连接上限按 `tunnels.size()` 近似，accept 与 tunnel 注册之间有极小竞争；宿主侧仍有 P09/引擎的独立上限。
5. TLS 测试依赖本机 `keytool`；CI 无 JDK 时会被 `Assume` 跳过，报告中需保留“TLS 未跑”的诚实状态。
