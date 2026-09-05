# 宿主↔Guest 桥接协议：vsock 契约与 guest-agent 信任模型

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 04

## Question

Host↔Guest 集成的产品契约：是否收敛为单一、传输无关、版本化的 guest-bridge 协议（vsock/virtio-console 之上）+ 统一 guest-agent？文件共享、剪贴板、通知、URL、端口管理是否都走同一协议？

## Why it matters

这是"Android 与 Linux 是一个产品"的粘合层；协议不版本化，未来每加一个能力都会破坏旧 guest。Podroid 的 host bridge（NOTIFY/FORWARD/OPEN/POWER）+ 传输非对称但协议统一的模式已验证可行（01 票）。

## Known facts

- AVF vsock：VM 只能监听、仅 owner App 可连；QEMU 档无 vsock，需 virtio-console 等价通道 → 协议必须传输无关。
- Podroid：hvc2（QEMU）vs vsock 9101（AVF），协议同一——该分层值得继承。

## Working recommendation

定义传输无关的 guest-bridge 协议 v1（握手+版本协商+命令集+per-VM 鉴权 token），两后端共用；MVP 命令集：ping/resize/port-forward/notify/open-url/file-request；文件共享 MVP 用 9p/vsock（复用已验证模式）而非自造协议。

## Reversibility

从 v1 起带版本协商即可平滑演进；不版本化则不可逆地积累破坏性变更。

## Answer

2026-09-05 用户全盘采纳。固化于 **docs/adr/0009-guest-bridge-protocol.md** 与 CONTEXT.md 词条（Guest Bridge / Guest Agent / 共享目录）。

决定要点：
1. 单一传输无关协议 v1（握手/版本协商/命令集/应答）+ per-VM token 鉴权（Provisioning 注入）。
2. MVP 命令集六条：ping/version、resize、port-forward add/remove、notify、open-url、power。
3. 控制面/数据面分离：文件共享走独立 9p 通道（QEMU=virtio-9p、AVF=9p-over-vsock），MVP 仅只读共享 Downloads。
4. 宿主向后兼容 N-1 版 agent；guest agent 静态单二进制、按风味装为 init 服务。
5. 剪贴板等进雾区；命令扩张须走版本流程。

解锁：安全边界票（12）。待实现细节：AVF 档 9p-over-vsock 自研服务端（许可证要求自研）。
