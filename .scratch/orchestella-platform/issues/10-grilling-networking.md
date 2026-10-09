# 网络模型：QEMU 档与 AVF 档的统一网络故事

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 04

## Question

网络如何统一建模：默认 NAT 的产品语义、端口转发的形态与持久化、IPv6 与 VPN 共存的 MVP 立场、入站可达性怎么给？

## Why it matters

网络是"能不能用"的第二关（编译/包管理/Web 服务都靠它）；两后端机制不同（SLIRP vs TAP+DHCP），不统一会让上层行为漂移。

## Known facts

- Podroid：QEMU=SLIRP（IPv6 off、静态地址、cmdline 传 DNS——Android 无 /etc/resolv.conf 的坑）、AVF=TAP+tethering DHCP；VPN 冲突零处理（01 票）。
- VpnService（TUN）方案与真实 VPN 互斥；入站访问必须经 host 侧端口转发（两后端皆然）。
- ICMP 在 SLIRP 下经非特权 socket 可用（Podroid 核实）。

## Working recommendation

产品语义统一定义为「默认出站 NAT + 显式端口转发规则（持久化、可热更）」；MVP 不承诺 IPv6 与 VPN 共存，文档如实标注；地址语义对两后端统一（避免应用层感知 10.0.2.15 差异）。

## Reversibility

NAT 语义稳定，后续可加 host-mode/IPv6；端口转发规则模型在本票先定方向、细节并入「宿主↔Guest 桥接协议」票。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0008-networking-model.md** 与 CONTEXT.md 词条（端口转发规则）。

决定要点：
1. 默认出站 NAT（免配置出站可用）。
2. 入站仅经显式端口转发规则：默认绑 127.0.0.1、LAN 显式开启并提示、规则持久化、运行时热更。
3. 机制/语义分离：QEMU=SLIRP hostfwd、AVF=vsock 隧道，上层只见规则模型。
4. 非目标：IPv6、host mode（未来增强）；VPN 共存标注"未验证"并入原型观测项。
5. 契约测试覆盖两策略转发语义等价；热更命令集归桥接票统一。

待原型：AVF custom VM 网络实际行为（原型票验证清单）；VPN 共存行为。
