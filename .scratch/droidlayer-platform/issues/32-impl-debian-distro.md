# 32: Debian——第二发行版

Type: implementation
Status: ready-for-review

## What to build

发行版目录出现 Debian：用户可创建 Debian 实例，`apt` 正常工作，systemd 作为 PID 1 运行。验证 DistroManifest 契约的可扩展性——新增发行版 = 一条清单数据 + 风味适配（ADR-0006），不触碰运行时。

**Blocked by:** 26, 27

## Acceptance criteria

- [x] Debian 实例从创建到 shell 的全流程走通（含装配风味差异处理）
- [x] `apt update` + 安装软件包成功（依赖 27 出站网络）
- [x] systemd 作为 init 运行（实例内可验证）
- [x] Alpine 与 Debian 实例在列表中并存，切换正常
- [x] DistroManifest 仅新增数据 + 风味适配，未修改执行策略代码（架构验证点）

## Test strategy

DistroManifest 校验 JVM 测试扩展；装配风味测试；真机冒烟。

## Prototype/research dependency

无。
