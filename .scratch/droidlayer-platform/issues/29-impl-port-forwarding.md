# 29: 端口转发规则

Type: implementation
Status: ready-for-review

## What to build

用户可以为实例创建端口转发规则并在本机访问 guest 内的服务（如临时 web 服务器）。规则模型 `{协议, 宿主端口, guest 端口, 绑定范围}`（ADR-0008）：默认绑定 127.0.0.1；局域网暴露必须显式开启并展示安全提示；规则持久化、可在实例运行时增删（热更）；QEMU 档经 SLIRP hostfwd 实现（启动时注入 + 运行时动态变更）。

**Blocked by:** 27

## Acceptance criteria

- [x] 创建规则后，本机访问宿主端口可命中 guest 内服务
- [x] 删除规则后访问立即失效（热更，无需重启实例）
- [x] 应用重启后规则仍在（持久化）
- [x] 默认仅绑定 127.0.0.1；开启局域网暴露必须显式操作且出现安全提示
- [x] 端口冲突时有明确错误反馈

## Test strategy

转发规则对账 JVM 测试（增删/冲突/持久化）；契约测试（转发语义与热更——为 AVF 档 vsock 隧道实现复用）；真机冒烟。

## Prototype/research dependency

无。
