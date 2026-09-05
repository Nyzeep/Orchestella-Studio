# 36: AVF 档能力对齐——出站网络与端口转发

Type: implementation
Status: needs-triage

## What to build

硬件虚拟化档补齐网络能力（ADR-0008 语义在 AVF 实现上等价成立）：guest 出站 NAT（crosvm TAP 型后端 + 宿主侧网络桥接）；端口转发经 vsock 隧道到 guest 内桥接代理（热更语义与 QEMU 档一致）。完成后 L2 用户获得与 L1 相同的网络能力——契约测试的网络/转发条目在两个策略上全绿。

**Blocked by:** 34, 27, 29

## Acceptance criteria

- [ ] AVF 档实例 `apk update` 成功（与 27 相同验收，同一套契约测试）
- [ ] AVF 档端口转发：创建→本机命中→删除→失效（与 29 相同验收，含热更）
- [ ] 能力矩阵更新：AVF 档网络/转发能力声明变为"已验证"
- [ ] 两策略转发语义等价性契约测试通过

## Test strategy

既有网络/转发契约测试直接对 AVF 实现运行（不写新测试，只扩展运行对象）；真机冒烟。

## Prototype/research dependency

P1（17）的网络形态实测数据（TAP/网络桥接的设备差异）。
