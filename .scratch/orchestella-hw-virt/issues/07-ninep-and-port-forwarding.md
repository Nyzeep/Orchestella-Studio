# 07: 9p 与端口转发接线

Type: implementation
Status: resolved
Priority: P1
Owner: AVF data-plane owner
Blocked by: 03, 04, 06
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

`Avf9pShareServer` 与 `AvfVsockPortForwarder` 在生产引用为 0；`AvfSession` 没有覆写 `openServiceForward`，所以端口转发直接抛默认“当前执行策略尚未验证”。9p 的 guest 侧 mount 与 vsock 端口公告已经存在，但宿主从未调用 `startShareServer()`。共享目录与端口转发是 Full Linux 的两个核心数据面，要么接线，要么诚实标 UNSUPPORTED。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4；`AvfVirtualMachineEngine.java:123-153,182-206`；`ExecutionSession.java:85-93`；`Avf9pShareServer.java`；`AvfVsockPortForwarder.java`；`guest-assets/initramfs/init:288-301,466`。

## What to build / 范围

1. **9p 接线**：`AvfInstanceLauncher` 在 `sharedDirectory != null` 时调用 `session.startShareServer()`，并把它纳入 session owner 的生命周期（stop/close 时关闭；失败不泄漏线程/通道）。使用 `AvfLaunchSpec.sharedDirectoryVsockPort()`，删除硬编码 9102。
2. 9p 契约：只读共享、路径白名单、statfs/ENOSYS 行为按现有 `Avf9pShareServer` 测试；guest mount 使用同一 `droidlayer.vsock_9p_port`，不新增 guest 命令面。
3. **端口转发**：先判定 guest 是否有可用的 vsock→TCP relay。当前 guest 的 `orchestella-proxy-relay` 走 virtio-serial，不是 vsock；若没有 guest relay，则 AVF v1 **明确 UNSUPPORTED**：
   - `ExecutionSession.openServiceForward` 返回 typed IOException/能力 absent；
   - `AvfVsockPortForwarder` 不进入生产默认路径，删除或隔离为 experimental；
   - 能力矩阵无 forward；UI 不显示“本地转发可用”。
   如果本票能补一个 guest relay 契约（仅 vsock 端口、loopback 限制、无 LAN 暴露），才接线；否则不要假装。
4. 端口来源统一：所有 vsock 端口从 `AvfLaunchSpec` 取，不在 launcher/engine 里硬编码 9102/9103/9104；冲突/越界在 build 时拒绝。
5. 生命周期：9p server 线程/通道随 session stop/close 收敛；UNCONFIRMED stop 时不得重复启动第二个 server；失败有 typed reason。
6. 能力描述符（04）由实际 server/forwarder 生成；未接线则 absent。

## 行为验收（零设备）

- [ ] 配置 sharedDirectory 时，fake vsock 上 9p server 启动，客户端可完成 lookup/read；未配置时不启动、不公告端口。
- [ ] 9p server 在 stop/close 后关闭；重复 close 不重复；stop UNCONFIRMED 时保留 owner/diagnostic。
- [ ] 端口转发：若 guest relay 不存在，`openServiceForward` 返回明确不支持和用户可见原因；生产无 `AvfVsockPortForwarder` 默认调用；能力矩阵无 forward。
- [ ] 若接线 forward：fake vsock 上 host port → guest port 双向字节流；端口冲突/关闭/重复 open 有测试；仅 loopback，不扩 LAN。
- [ ] 所有 vsock 端口来自 spec；没有 9102/9103/9104 硬编码。
- [ ] 共享目录不逃逸、不写、不 world-readable；不记录用户文件内容/路径集合。

## 测试 seam 与 red 命令

- 9p：`Avf9pShareContractTest`/`StandardReadonlyNinePTest` 扩展为 production wiring；新增 `AvfShareWiringTest`。
- forward：`AvfVsockPortForwarderTest`/`AvfNetworkForwardingParityContractTest` 扩展；若 UNSUPPORTED，新增“default session 拒绝并给 reason”测试。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check :app:testDebugUnitTest \
    --tests '*NineP*' --tests '*Share*' --tests '*Forward*' --no-daemon --stacktrace
  ```
- 先 red：launcher 不启动 9p；`openServiceForward` 抛默认字符串；端口硬编码。

## 失败、数据与兼容

- 9p 失败不阻塞终端/显示；用户看到“共享不可用”而不是整台 VM 失败。
- 不把共享目录挂成可写；不扩大白名单；不记录用户文件内容。
- forward UNSUPPORTED 是正常状态，不用“即将支持”掩盖。
- 不改变 QEMU 的 gvproxy/转发语义；QEMU 回归必须保留。

## 迁移、回滚与依赖

- 无实例数据迁移；共享目录配置兼容旧实例。
- 回滚：撤回 9p 启动/forward 接线，恢复 UNSUPPORTED；保留测试。
- 依赖：不新增 guest 命令面、不引入 socat/第三方 relay 到产品；若加 guest relay，必须走 ADR-0009/0010 和 ACR。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-07/implementation-report.zh-CN.md`。
- 完成 = 9p production wiring 或明确 UNSUPPORTED + forward 明确状态 + 端口统一 + 全量 check + 报告明确“真机 vsock/9p 未验”。

## 不做

- 不做真机 vsock 时序/端口复用/共享读写验收；不做 LAN 暴露；不做网络 NAT（08）；不改 guest 认证协议。

Claimed by: Agent B / 2026-10-20T01:50:00Z

## Answer

- 9p 接线：`InstanceStrategyLauncher.LaunchParameters` 新增可选 `sharedDirectory`；`AvfInstanceLauncher` 把它写入 `AvfLaunchSpec.sharedDirectory(...)`，并在 launch 后调用 `startShareIfConfigured()`。配置了共享才 `startShareServer()`，成功后 `declareSharedDirectory(true)`；失败只撤回 9p 能力并记录 typed `AvfEngineException` reason，不影响终端/显示启动。
- 生命周期：`AvfVmSession.startShareServer()` 在 `shareLock` 内单 owner；重复调用返回同一个 server；session closed 后再调用抛 typed `VSOCK_CONNECT_FAILED`，不会开第二条 vsock 连接或第二个线程。stop/close 走既有 `closeChannels()`，share 通道关闭后 serve 线程退出；`isShareServing()` 暴露真实线程状态。
- 端口统一：9p 使用 `spec.sharedDirectoryVsockPort()`，显示使用 `spec.graphicsVsockPort()`（06 已移除 9103 硬编码）；`AvfLaunchSpec` 删除未接线的 audio 端口字段/常量/校验；Builder 继续拒绝 1024–65535 之外和 control/terminal/9p/graphics 冲突。
- 端口转发：判定 guest 没有 vsock→TCP relay（现有 `orchestella-proxy-relay` 走 virtio-serial），AVF v1 **UNSUPPORTED**。`AvfSession.openServiceForward()` 返回 typed `IOException`（稳定原因串），`AvfVsockPortForwarder` 及其测试/JavaExec 任务删除；能力矩阵无 forward；PortForwarding UI 在无 QEMU live engine 时已显示“需切换 QEMU”。
- 红→绿：`AvfShareWiringTest`、`Avf9pShareLifecycleTest`、`AvfLaunchSpecTest` 的端口用例先红（API/行为缺失），实现后 engine `check` 与 app `testDebugUnitTest` 全绿。
- 未验：真机 vsock 9102 的 9p mount、真实共享目录读写、stop UNCONFIRMED 下的平台行为；forward 按 UNSUPPORTED 交付。
