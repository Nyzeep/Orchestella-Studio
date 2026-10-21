# P09: Distcc 跨车道编译（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
Priority: P0（交叉优化核心）
Owner: distcc/cross-lane owner
Blocked by: None（零设备）；与 P02/P03/P05 的集成由 DV-03 验证
External gates: 编译器版本/许可证审查

## 目标

让 QEMU Full Lane 的编译任务在 protected Fast Lane 的硬件 vCPU 上执行：

- QEMU guest 使用 `CC=distcc clang`，本地 `clang -E`；
- 宿主 TCP listener（127.0.0.1）接收 distcc 连接；
- 每连接 → vsock → payload；
- payload fork/exec `distccd --inetd`，把 vsock 字节流与 stdin/stdout 双向桥接；
- `.o` 经原路返回 guest；链接在 guest 完成。

不解析 distcc 协议；payload/宿主只做字节桥。

## 零设备范围

- 宿主 TCP↔内存流桥测试；
- 假 distcc 客户端/服务器（或真实 distcc 二进制在宿主 x86/arm64 上的本地测试）；
- payload 侧的 `distccd --inetd` stdio 桥可在宿主进程测试；
- 不要求设备。

## 行为验收

- [ ] 宿主 TCP listener 只绑 127.0.0.1；连接上限/空闲超时/半关闭/重置。
- [ ] 每连接对应一条 vsock 流；无串流；并发 ≥ vCPU 数；背压。
- [ ] payload 每连接 fork/exec `distccd --inetd`；child 退出回收；stderr 不污染协议。
- [ ] 假 distcc 客户端发送 `DIST/ARGC/ARGV/DOTI`，收到 `DONE/STAT/SERR/SOUT/DOTO`；字节一致。
- [ ] 真实 distcc 在宿主本地（x86_64 或 arm64）完成一次 C 编译；`.o` 可链接（host-only fixture）。
- [ ] 编译器不匹配/协议错误/超时 → typed failure；客户端可本地回退。
- [ ] 不记录源码/对象正文；只记录 job 计数/字节/耗时。
- [ ] 文档：guest `CC=distcc clang`、`DISTCC_HOSTS`、宿主端口、故障排查。

## 联网调研

- distcc 协议（`DIST/ARGC/ARGV/DOTI/DONE/STAT/SERR/SOUT/DOTO`）；
- `distccd --inetd` 的 stdio 模式/`DISTCCD_PATH`；
- Termux `packages/distcc` 构建（`--disable-pump-mode`）；
- clang target/sysroot/flags 一致性；
- QEMU guest 的 passt/gvproxy 端口转发语义。

## 交付

- 宿主 `DistccTcpVsockBridge` + tests；
- payload `distcc_bridge`（与 P02 协作）；
- 假 distcc 服务器/客户端 fixture；
- guest 配置文档；
- 编译器兼容性报告（C 优先；C++ 标 `not-tested`）。

## 停止条件

- `distccd --inetd` 在目标 Bionic/Termux 不可用：改用自研最小 distcc server 或停止。
- 编译器版本/ABI 不一致导致 `.o` 不可用：只支持同版本 clang/C；不承诺 C++。
- 端到端 <1.5x：停止产品化，保留选道/终端。

## 设备交接

DV-03：真实 QEMU guest `make -j` + Fast Lane `distccd`；n≥10；端到端计时；失败样本。

## 实施进展（2026-10-21，零设备）

已落地 `runtime/fastlane-distcc` 的宿主侧最小桥：

- `DistccTcpVsockBridge`：loopback TCP listener、每连接一个 `FastLaneChannel`、双向字节泵、连接上限、关闭收敛；
- 不解析 distcc 协议；不绑定非 loopback；
- 4 个 JUnit 测试通过（echo round-trip、非 loopback 拒绝、factory 失败关闭客户端、close 停止 listener）。

未完成（本票剩余）：

- payload 侧 `distccd --inetd` stdio 桥（与 P02 协作）；
- 真实 distcc 客户端/服务器 fixture；
- 与 P03 工具链/编译器版本一致性；
- DV-03 真机端到端。

## 实施进展（2026-10-21，Agent C，零设备）

- 宿主桥补齐：连接上限、空闲 watchdog、半关闭 drain、取消收敛、并发隔离、
  RST 释放槽位、`close()` 幂等/`port()` 失效、typed `Outcome` 与只含计数的
  `StatsSink`。
- 固定字节夹具：`request-v1.hex`（162 B）/`response-v1.hex`（112 B），
  `DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO` 字节级一致；
  half-close 后完整响应；256 KiB 二进制透传；child stderr 不进入协议。
- 真实 distcc 3.4 宿主测试：`distcc` → 桥 → `distccd --inetd` → `.o` 可链接
  并运行输出 42（host-only）。
- 测试：16 个桥单测 + 1 个真实集成测试，共 17 OK；原 10 个测试保留。
- guest 契约：`docs/reports/tickets/fastlane-P09/guest-contract.md`；与 P02
  `distcc_bridge.cpp` @ `a5eaa82c` 的差异表：
  `docs/reports/tickets/fastlane-P09/guest-contract-alignment.zh-CN.md`。
- 未验证：protected payload、Bionic/Termux distccd、QEMU 转发地址、C++、
  端到端性能；`fastlane-payload/` 未改（P02 所有）。
