# 30: 只读共享 Downloads

Type: implementation
Status: needs-triage

## What to build

用户把文件放进设备 Downloads 目录后，guest 内即可只读访问（统一挂载点）。数据面通道（9p 家族）与控制面分离（ADR-0009）；只读语义为暴露面白名单的当前边界（ADR-0010）；QEMU 档经 virtio-9p 实现，guest 侧挂载由装配预配置。

**Blocked by:** 26

## Acceptance criteria

- [ ] 向 Downloads 放入文件后，guest 内挂载点可见该文件
- [ ] guest 内对该挂载点的写入被拒绝（只读语义成立）
- [ ] 暴露面白名单修订记录完成（新增只读共享条目，ADR-0010 流程）

## Test strategy

契约测试（共享目录只读语义——为 AVF 档 9p-over-vsock 实现复用）；真机冒烟。

## Prototype/research dependency

无。
