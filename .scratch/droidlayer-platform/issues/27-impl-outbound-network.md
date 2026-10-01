# 27: 出站网络——实例可访问互联网

Type: implementation
Status: completed

## What to build

用户在 shell 中使用发行版包管理器（`apk update`、安装软件包）即可成功——出站 NAT 语义落地（ADR-0008）：guest 免配置出站、DNS 可用（含 Android 无 resolv.conf 的处理）、IPv6 关闭为明示行为。QEMU 档以 SLIRP 实现；契约测试记录出站语义，供 34（AVF 档）复用。

**Blocked by:** 26

## Acceptance criteria

- [x] 真机上 `apk update` 成功且能安装一个软件包
- [x] DNS 解析在宿主换网（Wi-Fi↔移动数据）后仍可用或可恢复（记录实际行为）
- [x] guest 无对外可达地址（入站默认为零暴露）
- [x] 契约测试：出站语义条目建立

## Test strategy

契约测试（出站 NAT 语义）；真机冒烟清单（含弱网/断网恢复行为记录）。

## Prototype/research dependency

无。「QEMU TCG 基线」（18）的吞吐数据用于性能预期标注，不阻塞本票。
