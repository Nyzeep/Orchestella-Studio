# AX-04: Guest 到宿主加速上行链路

- Type: implementation
- Status: ready-for-human
- Priority: P0
- Owner: network/uplink owner
- Blocked by: AX-03
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

P09 写的是“宿主 TCP listener 绑 127.0.0.1，guest 用 `DISTCC_HOSTS=127.0.0.1:<port>`”。但 guest 里的 127.0.0.1 是 guest 自己，这条路径在真机上必然失败。gvproxy 拓扑是 guest `192.168.127.15`、网关 `192.168.127.1`；slirp 拓扑的宿主地址是 `10.0.2.2`。必须先定义清楚 guest 如何到达宿主加速端口，再谈 distcc。

## 目标

- 为两种网络后端（gvproxy / slirp）生成正确的 guest 配置。
- 宿主监听范围最小化：只允许 guest 所在虚拟网络访问，不绑 `0.0.0.0`，不暴露到 LAN。
- 带 token 鉴权；无 token 的握手直接拒绝。

## 任务

- [x] 调研并记录三条候选路径的可行性：gvproxy 网关地址、slirp `10.0.2.2`、QEMU `guestfwd`。
- [x] 选定一条主路径与一条回退路径；在文档中写明为什么、以及在目标 QEMU 11.1.1 上的参数形态。
- [x] 实现 `AcceleratorUplink`：根据 `NetworkSpec` 生成 guest 侧 `DISTCC_HOSTS` 与端口，生成宿主监听地址。
- [x] 绑定策略：只绑虚拟网关地址或由 QEMU 转发的私有地址；禁止 `0.0.0.0`；启动时断言。
- [x] token：每次运行生成新的 32 hex token，写入 guest 环境与 AX-03 握手；不落盘到公共目录。
- [x] 配置生成器测试：gvproxy/slirp 两套 golden；端口冲突、地址缺失、IPv6 关闭。
- [x] 失败分类：`UPLINK_UNAVAILABLE` / `UPLINK_BIND_FAILED` / `UPLINK_AUTH_FAILED`。
- [x] 文档：网络拓扑图、暴露面分析、与 P05 代理的边界（不得复用 SOCKS 代理绕过）。

## 验收命令

```bash
./gradlew :runtime:engine-qemu:test :runtime:fastlane-distcc:test
python -m pytest tools/fastlane-device -q
```

## 边界

- 不修改 gvproxy 二进制或 QEMU 网络模型；只生成参数与配置。
- 不把监听端口暴露到 Wi-Fi/LAN；不做 UPnP、不做公网。
- 不在本票实现真正的 distcc 编译（AX-06）。

## 证据

- 路径选择与参数样例写入 `docs/reports/tickets/ax-04/implementation-report.zh-CN.md`；真机连通性标 `not-tested`。
