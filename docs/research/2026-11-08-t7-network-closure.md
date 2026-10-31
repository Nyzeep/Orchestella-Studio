# T7 网络面源码闭环 · 产品 QEMU↔gvproxy 对接语法提取与探针配方改写

- 日期：2026-11-08（研究子代理 B，fabric 批次 3）
- 对应报告：`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §6（T7 判定 PARTIAL 的根因链）；本文是对其中第 4 步「QEMU 11.1.1 ↔ gvproxy v0.9.0 协议不匹配」的**更正**与收口。
- 证据等级标注：[本项目源码] = 本仓库 0.6.x 源码（file:line）；[官方源码] = 上游仓库源码逐字核读；[官方文档] = 上游 README/文档；[本项目实测] = 设备轮 2026-10-31（device-myron）或更早产品真机联调（提交 109b43d5）；[非实测-推算] = 本文推断。
- 纪律：本文只读产品源码、只写 `docs/research/` 与 `research/uml-fabric-probe/`；无 git 写操作；设备只用稳定别名 device-myron；192.168.127.x 为产品默认虚拟网段。

## 0. 结论速览（5 条）

1. **产品对接语法就在本仓库**：`runtime/engine-qemu/.../QemuLaunchSpec.java`。生产默认路径（`QemuTuning.DEFAULTS`，`passtUnixSocketPath=null`）给 QEMU 的网络行是 **`-netdev socket,id=orchestella-net,connect=127.0.0.1:<port>` + `-device virtio-net-device,netdev=orchestella-net`**（QemuLaunchSpec.java:520-523、:569-575）。2026-11-07 报告 §6 所记「对接 spec 不在 app Java 源内（在 DroidLayer 层）」**不成立**——它在 `runtime/engine-qemu` Gradle 模块内，当日检索只覆盖了 `app/` 目录。
2. **passt 是死资产**：仓库从未包含 passt 二进制（全库 find 与 `git log -S passt -- app runtime` 无二进制痕迹）；`buildWithPasst`/`passtTcpPort` 只是历史命名。passt 形态的 `-netdev stream,addr.type=unix` 仅在 `QemuTuning.passtUnixSocketPath` 非空时生成，生产恒为 null（QemuTuning.java Builder 默认值 + `DEFAULTS`）。当前生效路径 = **QEMU socket netdev → gvproxy qemu 协议监听（127.0.0.1 TCP）**。
3. **「协议不匹配」裁决为误诊**：QEMU `-netdev socket` 与 `-netdev stream` 两种语法都是同一 4 字节**大端**长度前缀帧协议（QEMU net/socket.c `htonl`/net_fill_rstate、net/stream_data.c `net_stream_data_receive` 同帧头）[官方源码]；gvproxy `QemuProtocol` = "32bits big endian size of the packet, then the packet"（pkg/types/configuration.go 注释，pkg/tap/protocols.go `binary.BigEndian`）[官方源码]。**两种语法对 gvproxy `-listen-qemu` 均线兼容**，与 gvproxy 官方 README 的两种示例一致 [官方文档]。
4. **EOF 真因**：`cannot read size from socket: EOF` 出自 gvproxy `pkg/tap/switch.go` `rxStream`——语义是「连接被接受后 **0 字节即关闭**」，不是帧格式错配（那会打 `invalid packet size`）。且 gvproxy qemu 监听是**单发 accept**（cmd/gvproxy/main.go `config.Interfaces.Qemu` 分支只 Accept 一次）——任何探活连接或残留连接都会烧掉 accept 槽位并永久断开该 gvproxy 生命周期的网络面。这正是产品提交 109b43d5 记录的实测踩坑：「连接式探测会被当作 QEMU 客户端导致 EOF 掉线」。
5. **修正配方已写入探针目录**：`qrun.sh net` + `t7/gvproxy-t7.yaml` + `probe-init.sh` 分层诊断（§5 diff）。T7 一次通过的置信度自评见 §6。

## 1. 产品语法提取（逐字引用）

### 1.1 QEMU 侧：netdev / device / drive / 内核参数

`app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java:664-675`（launch 路径，[本项目源码]）：

```java
String[] spec = QemuLaunchSpec.buildWithPasst(
        NativeExecutableStore.ensure(context, "libdroidlayer_qemu.so").toString(),
        paths.kernel.toString(), paths.initramfs.toString(),
        parameters.instanceRootVolume, controlSocket, terminalSocket, config,
        String.valueOf(gateway.tcpPort),    // ← gvproxy 的 TCP 端口，即 connect 目标
        guestKernelArgs);
```

`runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java`：

- :569-575 `passtNetdevArg`（网络行的唯一生成点）：

```java
private static String passtNetdevArg(String passtTcpPort, QemuTuning tuning) {
    if (tuning.passtUnixSocketPath() != null) {
        return "stream,id=orchestella-net,addr.type=unix,addr.path="
                + tuning.passtUnixSocketPath();
    }
    return "socket,id=orchestella-net,connect=127.0.0.1:" + passtTcpPort;
}
```

- :520-528 netdev 与配套 device 行（buildWithPasst 装配段）：

```java
Collections.addAll(args,
        "-netdev", passtNetdevArg(passtTcpPort, tuning),
        "-device", "virtio-net-device,netdev=orchestella-net",
        "-device", "virtio-serial-device",
        "-chardev", "socket,id=control,path=" + controlSocketName + ",abstract=on,server=on,wait=off",
        ...);
```

- :553-567 / :542-551 drive 与块设备行（DEFAULTS：无 aio/discard/detect-zeroes/iothread/multi-queue）：

```java
"-drive", "if=none,id=root,file=" + rootVolume + ",format=raw,cache=writeback"
"-device", "virtio-blk-device,drive=root"
```

- :30-32 `GUEST_CMDLINE = "console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda ..."`；guest 网络寻址走内核 cmdline 通道，`QemuInstanceLauncher.java:651-655` 拼装：

```java
String guestKernelArgs = "orchestella.guest_ip=" + NetworkGatewayDaemon.GUEST_IP
        + " orchestella.guest_gw=" + NetworkGatewayDaemon.GUEST_GATEWAY
        + " orchestella.guest_mask=" + NetworkGatewayDaemon.GUEST_NETMASK
        + " orchestella.guest_dns=" + NetworkGatewayDaemon.guestDnsFor(context)
        + " orchestella.proxy_enabled=0";
```

- 基础参数：`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57`（:487、:532-536，`QemuTuning` 默认 cpu=cortex-a57、无 tb-size）+ `-nodefaults -display none -monitor none -no-reboot`（:497-500）+ `-smp`/`-m` 取 `VmHardwareConfig.defaultConfig()` = **2 vCPU / 2048 MB**（VmHardwareConfig.java:15、:19）。

**0.6.x 生产默认启动数组（网络面相关，[本项目源码] 归纳）**：

```
-netdev socket,id=orchestella-net,connect=127.0.0.1:<port>
-device virtio-net-device,netdev=orchestella-net
-drive if=none,id=root,file=<volume>,format=raw,cache=writeback
-device virtio-blk-device,drive=root
```

（注意 **没有** `-netdev stream`、没有 hostfwd——转发已全部迁到 gvproxy 配置面。）

### 1.2 gvproxy 侧：启动参数与配置文件

`app/src/main/java/io/github/nyzeep/orchestella/NetworkGatewayDaemon.java`（[本项目源码]）：

- :218-219 启动形：`new ProcessBuilder(gvproxyPath, "-config", configFile.getAbsolutePath())` —— **产品从不使用 `-listen-qemu` 等 CLI 监听旗标，全部经 YAML 配置**。
- :335-359 `configuration()` 生成的 YAML（逐字模板，端口/路径为参数）：

```yaml
log-level: info
services: "unix://<app私有目录>/net-api/<hash>/api.sock"
interfaces:
  qemu: "tcp://127.0.0.1:<port>"
stack:
  mtu: 1500
  subnet: 192.168.127.0/24
  gatewayIP: 192.168.127.1
  deviceIP: 192.168.127.15
  hostIP: 192.168.127.254
  gatewayMacAddress: <auto>   # 由 gvproxy 自动生成；实跑 yaml 省略此行即可（门禁禁止入库真实 MAC）
  forwards: {}          # 有转发规则时为 "127.0.0.1:<hport>": "192.168.127.15:<gport>" 列表
```

- 常量与端口：:96-100 `GUEST_IP=192.168.127.15 / GUEST_GATEWAY=192.168.127.1 / GUEST_NETMASK=255.255.255.0 / GUEST_DNS=223.5.5.5（仅兜底，优先取宿主活动网络解析器，:117-148）`；:609-611 `listenPortFor = 24100 + floorMod(instanceId.hashCode(), 800)`（**24100–24899**；设备轮用的 45654 不在产品端口形内）。
- :602-607 就绪探测 = `portBound()`（**ServerSocket bind 测试**，不是连接测试）：「连接式探测会被当作 QEMU 客户端导致 EOF 掉线——实测踩坑」的直接防御（提交 109b43d5）。
- :243 启动循环注释「never consume the QEMU single-client listener」——产品对 qemu 监听面单客户端语义的源内自认。
- API 面（:228-229、:449-479）：仅用于端口转发 HTTP（`/services/forwarder/expose|unexpose|all` over unix socket），pacman 直驱不需要。

### 1.3 进程编排（RuntimeProcessSupervisor 的角色）

`RuntimeProcessSupervisor.java`（[本项目源码]）不含网络语法；其与网络面相关的编排语义：
- :167-169 `isRuntimeAlive()` 把 gvproxy 与 QEMU 同视为 runtime 一体；
- :140-147 停止序：先 QEMU（destroy→forcibly 有界）→ 关通道 → **再停 gateway**；启动序反之：`NetworkGatewayDaemon.start()` 先行（QemuInstanceLauncher.java:637-645，`timeline.mark(GATEWAY_READY)` 后才 `launcher.launch`，:730-731）——**gvproxy 就绪先于 QEMU 启动是产品硬序**，因为 QEMU `connect=` 连接失败致命且无重试（§3.3）。
- 启动失败清理 `cleanupFailedLaunch`（QemuInstanceLauncher.java:779-812）同时回收 gateway —— **每次 QEMU 重启必然伴随 gvproxy 重启**，天然规避单发 accept 槽位复用问题（§3.4）。

## 2. 裁决：passt vs gvproxy 在 0.6.x 的实际角色

### 2.1 资产事实

- 全仓库 `find` 与 `git log -S passt -- app runtime`（只读）均无 passt 二进制踪迹；APK 资产清单 `app/src/main/assets/runtime-artifacts-v1.sha256` 只含 QEMU/Image/initramfs——**passt 从未入库**。打包网络资产只有 `libgvproxy.so`（`NativeExecutableStore.ensure(context, "libgvproxy.so")`，NetworkGatewayDaemon.java:184）。
- git 时间线（只读）：`2a6a8681`（2026-10-11，v0.2.0）引入 gvproxy 面，当日初版用 passt 形语法 `-netdev socket,server=off,addr.type=unix,addr.path=…` + `virtio-net-pci`；同日 `109b43d5`「网络平面真机联调修正——QEMU 兼容语法与 ROM 约束」改为现形（§2.2 引文）。`b8dbae6e`（2026-10-18）加 `QemuTuning`，明确「DEFAULTS is byte-for-byte identical」「`passtUnixSocketPath` null keeps the **existing TCP loopback transport to passt**」——"passt" 至此只剩命名化石。

### 2.2 :634 与 :1006 两处注释的矛盾裁决

- `QemuInstanceLauncher.java:633-636`：「gvproxy bridges the guest onto the host stack (the packaged QEMU has no slirp; **passt is blocked by Android SELinux**)」——指 **passt 守护进程本体**不能在 Android 上跑（passt 需要独立 daemon + 自建 tap/ socket 语义，在 app/shell 域被 SELinux 拦）。
- `QemuInstanceLauncher.java:1003-1007`：「The **passt -netdev/-device pair** is no longer stripped: the passt stream backend is the network plane」——指 QEMU 侧的 **passt 形状参数对**（历史上 sanitizeSpecForTargetBinary 曾裁掉它）不再被剥离。
- 两者共同成立，不矛盾：**QEMU 侧保留 passt 形状的 netdev/device 对，但对端不是 passt 进程，而是 gvproxy 的 qemu 协议监听**。唯一残留的 passt 字样是方法名 `buildWithPasst` 与 `QemuTuning.passtUnixSocketPath`（非 null 才生成 stream/unix 行——生产恒 null，走 TCP 行）。
- **当前生效路径（裁决）**：QEMU `-netdev socket,connect=127.0.0.1:<port>` → gvproxy `interfaces.qemu` qemu 协议监听（同一 YAML `-config` 启动）→ gVisor 栈 NAT。会话 22（2026-10-29）真机全链路出网（网关/上游 ICMP 0% 丢包、DNS 解析成功、`pacman -Sy` 10.2 MiB @ 0.8–1.7 MB/s、hostIP 192.168.127.254 映射 127.0.0.1）即此路径的实机证据（ACR 草案 §7.3）[本项目实测]。

## 3. 协议矩阵与 EOF 根因

### 3.1 线上协议（三方互证）

| 侧 | 实现 | 帧格式 | 等级 |
|---|---|---|---|
| gvproxy `QemuProtocol` | `pkg/tap/protocols.go` `qemuProtocol.Read/Write` = `binary.BigEndian.Uint32/PutUint32`；注释「QemuProtocol is 32bits big endian size of the packet, then the packet」（pkg/types/configuration.go） | 4B 大端长度 + 帧载荷 | [官方源码] |
| QEMU `-netdev socket` | `net/socket.c` `net_socket_receive`：`uint32_t len = htonl(size)` 帧头 + `iov_send`；收方向 `net_fill_rstate` 大端逐字节长度 | 同上 | [官方源码] |
| QEMU `-netdev stream` | `net/stream_data.c` `net_stream_data_receive`：同样 `uint32_t len = htonl(size)` + iovec；收方向同一 `net_fill_rstate`（stream.c `net_socket_rs_init(&d->rs, net_stream_data_rs_finalize, …)`） | **同上（与 socket 线兼容）** | [官方源码] |
| gvproxy README | `-listen-qemu tcp://…` + `-netdev socket,id=vlan,connect=127.0.0.1:<port>` 为官方示例；QEMU≥7.2 亦可 `-netdev stream,addr.type=unix` 直连 `-listen-qemu unix://…`（同一监听、同一协议，只换套接字形式） | — | [官方文档] |

结论：**不存在 QEMU 11.1.1 ↔ gvproxy v0.9.0 的协议不匹配**。`-netdev stream` 与 `-netdev socket,connect=` 对 `-listen-qemu`（或 `interfaces.qemu`）都是合法客户端。产品约束三条（打包 QEMU 不支持 socket netdev 的 `addr.type` 新语法、无 UNIX socket 支持/sun_path 108 限制、`virtio-net-pci` 需 efi-virtio.rom 而打包无 ROM → QEMU 直接退出）因此才选 legacy `connect=` + TCP + `virtio-net-device`——这是 109b43d5 的真机实测结论 [本项目实测]，与上游语义自洽。

### 3.2 EOF 字符串的准确语义

`pkg/tap/switch.go` `rxStream`（[官方源码]）：

```go
_, err := io.ReadFull(reader, sizeBuf)   // 读 4 字节长度前缀
if err != nil {
    return fmt.Errorf("cannot read size from socket: %w", err)
}
```

- `EOF` = 对端**一个字节都没发就关闭**。若帧格式错配（如裸帧无前缀），会走到 `validateStreamPacketSize` 打 `invalid packet size: …` 或读体阶段 `cannot read packet from socket`——**设备轮日志里没有这些**。故「gvproxy 期望 4 字节长度前缀帧而 QEMU 发了别的」的旧解释不成立。
- 另注意 `cmd/gvproxy/main.go` 的 qemu 监听 accept 循环是**单发**：`Accept()` 一次 → `vn.AcceptQemu(ctx, conn)` → goroutine 返回，**该 gvproxy 进程此后不再 accept**。第一个（哪怕是 0 字节的）连接结束后，真实 QEMU 的连接只会滞留内核 backlog，永远无人服务 [官方源码]。

### 3.3 QEMU 侧时序约束

`net/socket.c` `net_socket_connect_init`：`connect=` 除 EINTR/EINPROGRESS 外**一败即致命、无重试**（`error_setg_errno(... "can't connect socket")`）[官方源码]。⇒ 必须先确认 gvproxy 端口 LISTEN 再拉 QEMU（产品硬序，§1.3）；探针侧同理。

### 3.4 设备轮 4 步根因链的改写（对 2026-11-07 报告 §6 的更正）

| 步 | 2026-11-07 记录 | 2026-11-08 裁决 |
|---|---|---|
| 1 打包 QEMU 无 slirp | 成立 | 不变 [本项目实测] |
| 2 passt 被 SELinux 阻断 | 成立（passt daemon 不可用） | 不变；且 passt 本就是死资产（§2） |
| 3 gvproxy unix bind 被 SELinux 拒，TCP 可用 | 成立 | 不变；产品把 API unix socket 放 app 私有目录规避；探针无转发需求可整体省略 `services` |
| 4 QEMU↔gvproxy 协议不匹配 | 两种语法均 `cannot read size from socket: EOF` → 判「协议层不匹配，spec 在 DroidLayer 层」 | **误诊**。协议线兼容（§3.1）；EOF = 首个被 accept 的连接 0 字节关闭，且 accept 槽位单发被烧（§3.2）。触发途径（按可能性）：a) 对 45654 做过连接式探活（设备轮「TCP 45654 可用」的验证方式若含 connect 即命中）[非实测-推算]；b) 上一次尝试的 QEMU 连接未随 gvproxy 重启清理，重启 QEMU 复用旧 gvproxy [非实测-推算]；c) QEMU 先起、`connect=` 致命失败与探活交叉 [非实测-推算]。产品侧防的是同一坑：bind 测试 + 硬序 + 失败清理连 gateway 一起回收（§1.2/§1.3，提交 109b43d5 [本项目实测]）。设备轮原始尝试行在仓库外 evidence，精确触发途径待下一窗口用 §5 分层诊断复核 |

## 4. 设备轮尝试行 vs 产品行逐项对照

| 项 | 产品行（file:line） | 设备轮尝试 | 差异判定 |
|---|---|---|---|
| netdev | `socket,id=orchestella-net,connect=127.0.0.1:<port>`（QemuLaunchSpec.java:574） | `-netdev socket,connect=` 一支同型；另一支 `-netdev stream` | 语法本身可判不背锅（§3.1）；差异在**监听面被污染**（§3.4） |
| NIC 设备 | `virtio-net-device,netdev=orchestella-net`（:523） | 报告未记 ROM 错误，推定同型 [未找到证据] | 若曾用 `virtio-net-pci`，QEMU 会因缺 efi-virtio.rom 直接退出（109b43d5） |
| gvproxy 监听 | `-config` YAML `interfaces.qemu: tcp://127.0.0.1:<port>`（NetworkGatewayDaemon.java:218-219、:341） | 设备轮监听形未记录于报告 [未找到证据]（仅证 TCP 45654 可用） | CLI `-listen-qemu` 与 YAML `interfaces.qemu` 等价（同落 `config.Interfaces.Qemu`）[官方源码]；设备轮无 stack 段/无 services 段 |
| stack 配置 | subnet/gatewayIP/deviceIP/hostIP/gatewayMacAddress/mtu 显式写入（:342-344） | 未配（gvproxy 默认值恰好同为 192.168.127.x） | 非致命；配方改为显式产品值以消除变量 |
| 就绪探测 | `portBound` bind 测试（:602-607） | 「TCP 45654 可用」验证方式不明 [未找到证据] | **关键风险点**：任何 connect 式验证即烧槽位 |
| QEMU/gvproxy 生命周期 | 一一对应、失败清理连带 gateway（QemuInstanceLauncher.java:779-812） | 多次尝试疑似复用 gvproxy [未找到证据] | 关键风险点：单发 accept 槽位 |
| 端口 | 24100–24899（:609-611） | 45654 | 非致命；配方归位产品端口形 |

## 5. 探针配方改写（diff 形式）

改动文件：`research/uml-fabric-probe/guest-overlay-qemu/qrun.sh`（新增 `net` 模式，T4 路径保持原样）、`t7/gvproxy-t7.yaml`（新增）、`t7/probe-init.sh`（加分层诊断）、`README.md`（同步）。`t7/orchestella-guest-provision.pacman-probe.sh` 不动（与产品唯一 diff 仍是 `:509 -Syu→-Sy`，已复核）。

### 5.1 qrun.sh（新增 net 模式；核心新增行）

```diff
+if [ "$1" = "net" ]; then
+    PORT=${T7_PORT:-24117}
+    ...
+    # 规则 1：gvproxy qemu 监听 = 单发 accept；重启 QEMU 前必须重启 gvproxy
+    pkill -f 't7-net.yaml' 2>/dev/null; sleep 1
+    # 规则 2：就绪判定只允许 LISTEN 检查（/proc/net/tcp 状态 0A），禁止连接式探测
+    "$GVPX" -config "$YAML" > "${LOG}-gvproxy.log" 2>&1 &
+    while read -r _ local st _; do case "$local" in *:"$HEX") [ "$st" = "0A" ] && LISTEN=1 ;; esac; done < /proc/net/tcp
+    ...
+    exec ./qemu-system-aarch64 -machine virt,gic-version=3 \
+        -accel tcg,thread=multi -cpu cortex-a57 -smp 2 -m 2048M \
+        -nodefaults -display none -monitor none -no-reboot \
+        -kernel assets/Image \
+        -append "root=/dev/vda rw init=/init console=ttyAMA0 orchestella.guest_ip=192.168.127.15 orchestella.guest_gw=192.168.127.1 orchestella.guest_mask=255.255.255.0 orchestella.guest_dns=223.5.5.5 orchestella.proxy_enabled=0" \
+        -drive if=none,id=root,file="$ROOTFS",format=raw,cache=writeback \
+        -device virtio-blk-device,drive=root \
+        -netdev socket,id=orchestella-net,connect=127.0.0.1:"$PORT" \
+        -device virtio-net-device,netdev=orchestella-net \
+        -serial "file:$LOG"
+fi
```

逐字对上产品的行：`-netdev socket,id=orchestella-net,connect=127.0.0.1:<port>`（QemuLaunchSpec.java:574）、`-device virtio-net-device,netdev=orchestella-net`（:523）、drive/device DEFAULTS 形（:553-567/:542-551，无 aio/discard/iothread）、`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57`（:487、:532-536）、2 vCPU/2048M（VmHardwareConfig.java:15/:19）、`orchestella.guest_*` 内核参数（QemuInstanceLauncher.java:651-655）、`-nodefaults -display none -monitor none -no-reboot`（:497-500）。探针形态既有差异（与网络面无关，沿 2026-10-31 轮口径）：无 initramfs（Arch rootfs 直启 `init=/init`）；串口落文件替代 abstract chardev 终端面；省略 control/netproxy/display 三条 virtio-serial 平面（Arch 探针无 droidlayer guest agent）。

### 5.2 t7/gvproxy-t7.yaml（新增）

产品 `configuration()`（NetworkGatewayDaemon.java:335-359）的 shell 域等价形：`interfaces.qemu: "tcp://127.0.0.1:24117"` + stack 全段显式（mtu 1500 / subnet 192.168.127.0/24 / gatewayIP .1 / deviceIP .15 / hostIP .254 / gatewayMacAddress 自动生成（已从入库 yaml 移除）/ forwards {}）。**唯一差异 = 省略 `services`**（API unix socket：shell 域 bind 被 SELinux 拒 + 探针无转发需求）。文件全文见该 yaml 内注释。

### 5.3 probe-init.sh（分层诊断新增行）

```diff
+echo "T7_ARP_BEGIN"
+ping -c1 -W2 192.168.127.1 >/dev/null 2>&1
+echo "T7_ARP: $(ip neigh show 2>/dev/null | grep 192.168.127.1)"
+echo "T7_ETH_TX_RX: $(cat /sys/class/net/eth0/statistics/tx_packets ...) $(cat .../rx_packets ...)"
+echo "T7_PING_GW: $(ping -c1 -W2 192.168.127.1 2>&1 | tail -2 | tr '\n' ' ')"
+echo "T7_DNS: $(nslookup archlinuxarm.org 2>&1 | tail -3 | tr '\n' ' ')"
+echo "T7_ETH_TX_RX_2: ..."
 ...（原 T7_BEGIN / DisableSandbox / provision --native / T7_STATE 不变）
+echo "T7_ETH_TX_RX_3: ..."
```

判读表（下一窗口失败层一步定位）：tx 增长而 rx=0 且无 ARP 条目 → guest 帧没到 gvproxy（查 QEMU netdev/gvproxy 日志与 accept 槽位）；ARP 学到但 ping 网关丢 → gvproxy gVisor 栈问题；ping 通、DNS 不通 → 上游/DNS 面；DNS 通、pacman 失败 → 包管理器/镜像面（本轮修的就是它应一次见分晓）。

## 6. 下一设备窗口 T7 一次通过置信度自评

**结论：高置信（主观 ~85%）。** 理由与残留不确定项：

- **已逐字对上的部分**：QEMU 网络行、NIC 设备、drive/device 形、机器/加速/CPU/内存默认、guest 内核网络参数、gvproxy 配置文件结构、启动时序（gvproxy 先、LISTEN 后 QEMU）、生命周期规则（每尝试一个 gvproxy）——全部有 [本项目源码] file:line 支撑，且与会话 22 真机出网证据（ACR §7.3）同栈同形。协议层经 [官方源码] 三方互证已排除错配可能。
- **shell 域 vs app 域无法在源码内确认的残留项**（按风险降序）：
  1. **SELinux 面未变项**：qemu 监听走 127.0.0.1 TCP（设备轮已证可用 [本项目实测]），但 gvproxy 进程本体在 shell 域的 exec 与长驻（`/data/local/tmp` 下无 context 转换的 binary 执行）设备轮已跑通过——本轮复用同形态，风险低但非零 [未找到证据]。
  2. **设备轮 EOF 的精确触发途径**只能由 evidence（仓库外）复核；本配方以「硬规则 + 分层诊断」双保险覆盖三条候选途径，即便复发也能一轮定位到层（§5.3 判读表）。
  3. 打包 QEMU 11.1.1 的 `connect=` 语义按上游 net/socket.c 推定（「QEMU 兼容语法」修正即产自同一二进制的真机联调，109b43d5，间接实证）[本项目实测+官方源码]。
  4. DNS 223.5.5.5 在设备所处网络的可达性是环境变量，产品兜底同值（NetworkGatewayDaemon.java:100）。
- **停止条件核对**：本次交付不需要「源码外缺口」降级——对接 spec 已在 `runtime/engine-qemu` 内逐字找到并交叉验证，无编造成分。

## 7. 交付物清单

| 文件 | 动作 |
|---|---|
| `docs/research/2026-11-08-t7-network-closure.md` | 新增（本文） |
| `research/uml-fabric-probe/guest-overlay-qemu/qrun.sh` | 改写：新增 `net` 模式（T4 原路径逐字保留） |
| `research/uml-fabric-probe/t7/gvproxy-t7.yaml` | 新增 |
| `research/uml-fabric-probe/t7/probe-init.sh` | 改写：新增分层诊断 |
| `research/uml-fabric-probe/README.md` | 同步：t7 条目 + 网络面硬规则 4 条 |
| `research/uml-fabric-probe/t7/orchestella-guest-provision.pacman-probe.sh` | 未动（diff 复核仅 `:509`） |
