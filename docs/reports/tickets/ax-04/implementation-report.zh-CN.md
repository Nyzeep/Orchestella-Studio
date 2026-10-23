# AX-04 Guest 到宿主加速上行链路 实施报告

- 日期：2026-10-23
- Base：`feat/accelerator-batch1 @ 5fa22c8`
- Branch：`feat/accelerator-batch2`
- 工单：`.scratch/orchestella-accelerator/issues/AX-04-Guest到宿主加速上行链路.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM 或 gvproxy；路径结论来自一手源码/文档，配置生成由 JVM 单元测试覆盖。
- CI：**未验证**。本批只做本地验证；PR/CI 尚未运行。

## 1. 结论

零设备范围内**完成**。

- 主路径：gvproxy。guest 连 `192.168.127.254:3632`，gvproxy 把 `hostIP` 的入站流量 NAT 到宿主 `127.0.0.1:3632`；宿主监听只绑 `127.0.0.1`。
- 回退路径：QEMU slirp。guest 连 `10.0.2.2:3632`（或自定义 subnet 的 network+2），宿主仍只绑 `127.0.0.1`。
- 工单候选里的 `192.168.127.1` 是 gvproxy 的虚拟网关，不是宿主 loopback；按一手源码修正为 `192.168.127.254`（gvproxy `hostIP`）。
- QEMU `guestfwd` 在 v11.1.1 支持 `tcp:...-tcp:...`，但只属于 user/slirp backend；生产 QEMU 走 `-netdev socket,connect=127.0.0.1:<gvproxy-port>`，guestfwd 不生效，故不选为主路径或回退。
- 新增 `AcceleratorUplink`：生成 guest `DISTCC_HOSTS`、端口、宿主监听地址、每次运行新生成的 32 hex token；绑定断言、失败分类与 AXP1 错误映射齐全。

## 2. 路径裁定（一手来源）

访问日期均为 2026-10-23。

| 候选 | 事实 | 结论 |
| --- | --- | --- |
| gvproxy `192.168.127.1` | 这是 gvproxy 的 `GatewayIP`；gvproxy 在该地址跑 DNS 和 HTTP API（README 的 `http://192.168.127.1:80`），不是宿主 loopback | 不采用 |
| gvproxy `192.168.127.254` | gvproxy `v0.9.0`：`pkg/types/configuration.go` 的 `NAT` 注释“Useful for reaching the host itself (localhost) from the virtual network”；`cmd/gvproxy/config.go` 在 `NAT` 为空时默认 `{HostIP: "127.0.0.1"}`，并把 `HostIP` 放进 `GatewayVirtualIPs` | **主路径** |
| slirp `10.0.2.2` | QEMU wiki Networking：guest 连 gateway IP 的端口就是宿主对应端口；默认 subnet `10.0.2.0/24` | **回退路径** |
| QEMU `guestfwd` | QEMU `v11.1.1` `qemu-options.hx` 支持 `guestfwd=tcp:10.0.2.100:1234-tcp:host:port`；但只属于 `-netdev user` | 不选；生产 gvproxy 路径是 `-netdev socket` |

来源：

- gvproxy v0.9.0（tag commit `6dbb5d4fa0033356c72a201346f8350594229868`）：
  - `https://github.com/containers/gvisor-tap-vsock/blob/v0.9.0/pkg/types/configuration.go`
  - `https://github.com/containers/gvisor-tap-vsock/blob/v0.9.0/cmd/gvproxy/config.go`
  - `https://github.com/containers/gvisor-tap-vsock/blob/v0.9.0/README.md`
- QEMU v11.1.1（tag commit `c3d48b7d1e89604920e5b81b91140c2ad39a1943`）：
  - `https://github.com/qemu/qemu/blob/v11.1.1/qemu-options.hx`（guestfwd 形式）
  - `https://github.com/qemu/qemu/blob/v11.1.1/docs/system/invocation.rst`
  - `https://wiki.qemu.org/Documentation/Networking`（slirp 10.0.2.2）
- 本仓库事实：`QemuLaunchSpec.java:569-575` 生产路径是 `-netdev socket,connect=127.0.0.1:<port>`；`NetworkGatewayDaemon.java:96-97,331-343` 使用 guest `192.168.127.15`、gateway `192.168.127.1`、hostIP `192.168.127.254`。

### 2.1 决策记录

- **选 gvproxy 为主路径**：生产 QEMU 已经走 gvproxy；gvproxy 默认 `NAT` 明确把 `HostIP` 映射到宿主 `127.0.0.1`，不需要改 gvproxy 二进制、不需要新增 QEMU 参数，且宿主监听可以是 loopback。
- **选 slirp 为回退**：当实例没有 gvproxy（或 gvproxy 启动失败）时，QEMU `-netdev user` 的 gateway 地址 `10.0.2.2` 是稳定的宿主别名；自定义 subnet 用 network+2。
- **不选 guestfwd**：它只对 user/slirp backend 生效；生产 gvproxy 路径的 QEMU netdev 是 `socket`，guestfwd 不会被解析。把它写成主路径会制造“配置存在但永远不生效”的假象。
- **失败表现**：gvproxy 未启动/端口未监听 → guest connect 超时或 refused → `UPLINK_UNAVAILABLE`，编译任务本地执行；宿主 bind 失败 → `UPLINK_BIND_FAILED`；token 不匹配 → `UPLINK_AUTH_FAILED`。不自动改绑 `0.0.0.0`，不回退到 LAN 地址。

## 3. 拓扑

```text
guest (gvproxy)                          host
192.168.127.15
   |  connect 192.168.127.254:3632
   v
gvproxy netstack  -- NAT HostIP -> 127.0.0.1 -->  127.0.0.1:3632
                                                    (AXP1 handshake -> distccd)

guest (slirp)
10.0.2.15
   |  connect 10.0.2.2:3632
   v
QEMU slirp  -------------------------------->  127.0.0.1:3632
```

## 4. 暴露面

| 项 | 策略 |
| --- | --- |
| 宿主监听地址 | 只允许 `127.0.0.1`；`requirePrivateBind` 拒绝 `0.0.0.0`、`::`、LAN 地址、空串 |
| guest 可达地址 | gvproxy `192.168.127.254` / slirp network+2；不把 `192.168.127.1` 当宿主 |
| IPv6 | `OutboundNetworkSpec` 强制关闭；`Plan.ipv6Enabled=false`；`DISTCC_HOSTS` 不含 `[` 或 `::` |
| LAN/Wi-Fi | 不绑定、不 UPnP、不做公网转发 |
| token | 每次运行 32 hex；只进 guest 环境与 AXP1 HELLO；不写公共目录、不打印；`Plan.toString()` 显示 `<redacted>` |
| 加密 | AXP1 只做 token 鉴权，**不做加密**；必须限制在 vsock/loopback，不能暴露到 LAN |
| P05 边界 | P05 的 SOCKS/HTTP 代理是出站代理，不是加速上行；不得复用 SOCKS 绕过绑定策略，也不把加速端口挂到代理暴露面 |

`DISTCC_HOSTS` 本身不携带 token（distcc 协议没有鉴权）；token 由 guest 侧 AXP1 握手使用。AXP1 的 token 校验与错误码见 AX-03；真正把 distccd 接到 AXP1 是 AX-06。

## 5. 实现

`AcceleratorUplink`：

- `plan(Backend, OutboundNetworkSpec, port, jobLimit, token[, occupiedPorts])`：
  - `Backend.GVPROXY`：要求 CIDR 为 `192.168.127.0/24`，guest host = `192.168.127.254`；
  - `Backend.SLIRP`：guest host = CIDR network + 2（默认 `10.0.2.2`，自定义 `192.168.76.0/24` → `192.168.76.2`）；
  - `DISTCC_HOSTS = <guestHost>:<port>/<jobLimit>`；
  - 宿主 bind 固定 `127.0.0.1`；
  - `token` 必须 32 小写 hex；`newToken()` 用 `SecureRandom` 每次生成；
  - 端口 1..65535、jobLimit 1..64、occupiedPorts 冲突 → `UPLINK_UNAVAILABLE`。
- `guestEnvironment()` 返回不可变 Map：`DISTCC_HOSTS` 与 `ORCHESTELLA_ACCEL_TOKEN`。
- `requirePrivateBind` 只接受 `127.0.0.1`，否则 `UPLINK_BIND_FAILED`。
- `classify(Axp1Error)`：`AUTH_FAILED` → `UPLINK_AUTH_FAILED`；其余（含 null）→ `UPLINK_UNAVAILABLE`。
- 不做网络 I/O、不解析主机名、不打开 socket。

## 6. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `runtime/fastlane-distcc/.../AcceleratorUplink.java` | 新增 | 两套 backend 的 plan、token、绑定断言、失败分类 |
| `runtime/fastlane-distcc/src/test/.../AcceleratorUplinkTest.java` | 新增 | 15 个测试 |
| `runtime/fastlane-distcc/build.gradle.kts` | 修改 | 增加 `api(project(":runtime:contract"))`（公开 API 使用 `OutboundNetworkSpec`） |

没有修改 gvproxy/QEMU 二进制、QEMU 启动路径、FLN1 wire、payload、ADR、版本号或 applicationId。

## 7. 测试矩阵

| # | 测试 | 覆盖 |
| --- | --- | --- |
| 1 | `gvproxyGoldenPlanUsesTheHostAliasNotTheGateway` | gvproxy golden、.254 而非 .1 |
| 2 | `slirpGoldenPlanUses10_0_2_2` | slirp golden |
| 3 | `customSlirpSubnetDerivesTheSecondHostAddress` | 自定义 subnet |
| 4 | `gvproxyRejectsASlirpSubnet` | 地址缺失/backend 不匹配 |
| 5 | `missingSpecIsUnavailable` | 地址缺失 |
| 6 | `occupiedPortIsUnavailable` | 端口冲突 |
| 7 | `invalidPortIsUnavailable` | 端口范围 |
| 8 | `invalidJobLimitIsUnavailable` | job limit |
| 9 | `tokenMustBe32LowercaseHex` | 鉴权输入 |
| 10 | `generatedTokensAre32HexAndUnique` | token 生成 |
| 11 | `guestEnvironmentCarriesHostsAndToken` | guest 环境、不可变 |
| 12 | `planNeverPrintsTheToken` | 日志泄露 |
| 13 | `onlyLoopbackBindIsAccepted` | 0.0.0.0/::/LAN 拒绝 |
| 14 | `axp1ErrorsMapToUplinkFailureCodes` | 失败分类 |
| 15 | `ipv6IsAlwaysDisabled` | IPv6 关闭 |

## 8. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:engine-qemu:test :runtime:fastlane-distcc:test` | BUILD SUCCESSFUL；fastlane-distcc 49 tests、0 failure、1 skipped（无真实 distcc）；engine-qemu 2 tests、0 failure |
| `python -m pytest tools/fastlane-device -q` | 77 passed, 7 subtests passed |

Gradle 首次运行遇到 Maven Central 429；使用仓库已有的 `-Porchestella.repositories.useAliyunMirror=true` 显式镜像后通过，并设置 `ANDROID_HOME=<sdk-root>`。没有修改依赖版本。

## 9. 偏差

1. **gvproxy 地址从 .1 修正为 .254**：工单候选写 `192.168.127.1`；一手源码显示 .1 是 gateway/DNS/API，`HostIP=192.168.127.254` 才是 NAT 到宿主 loopback 的地址。实现与文档采用 .254，并明确记录 .1 不采用。
2. **guestfwd 不作为回退**：它在 v11.1.1 可用，但只属于 user/slirp backend；生产 gvproxy 路径是 `-netdev socket`。保留 slirp 10.0.2.2 作为回退更诚实。
3. **宿主 bind 固定 127.0.0.1**：不是“绑虚拟网关地址”。gvproxy 的 .254 是 guest 侧地址，宿主没有该地址；绑 loopback 是实际可行且暴露面最小的形态。
4. **未接入 AXP1 listener**：本票只生成配置与 token；真正把 token 用于握手、把 distccd 挂到 AXP1 是 AX-06。
5. **端口冲突是纯本地检查**：`occupiedPorts` 由调用方给，未在 plan 内探测真实端口。

## 10. 风险与未验证

- **not-tested**：真实 gvproxy v0.9.0 的 NAT 转发、真实 QEMU slirp 10.0.2.2、真实 guest 内 `DISTCC_HOSTS` 解析。
- **not-tested**：设备上的端口占用与 bind 失败；`requirePrivateBind` 是策略断言，不是 socket 绑定测试。
- **not-tested**：AXP1 与 distccd 的端到端编译；属于 AX-06。
- gvproxy 的默认 `NAT` 依赖其配置未显式覆盖 `nat`；本仓库 `NetworkGatewayDaemon` 没有覆盖，但 OEM/未来版本需复评。
- AXP1 只鉴权不加密；token 在 guest 环境/内存中可见，不能防同 guest 内的高权限读者。
- CI 未运行。

## 11. 待维护者决策

1. 是否接受 gvproxy 主路径使用 `192.168.127.254`（而不是工单初稿的 .1）。
2. 是否要求为 slirp 回退增加可配置的 gateway 别名（当前 network+2）。
3. 是否在 AX-06 前把 `DISTCC_HOSTS` 的 limit 与设备 CPU 预算绑定（当前调用方传 jobLimit）。
4. 是否需要在生产启动时显式断言 gvproxy 配置包含 `hostIP: 192.168.127.254`（当前只在文档记录）。
5. 是否要求把 `UPLINK_BIND_FAILED` 的真实 socket bind 异常也纳入统一映射（当前由调用方捕获后选择 code）。
