# P09 guest contract 与 P02 `distcc_bridge` 差异表

日期：2026-10-21（零设备）
P02 基线：`github/feature/fastlane-dataplane` @ `a5eaa82c04b911ee8f3a7d515277913dfa1cb606`
P09 基线：`feature/fastlane-toolbox` 本轮改动（以提交为准）
范围：只读对比；本文件不改 `fastlane-payload/**`，也不把 P02 的实现当作已验收。

## 1. 结论

两端在**字节路径**上兼容：宿主 `DistccTcpVsockBridge` 每连接打开一条
`FastLaneChannel`，P02 `DistccBridge::handle(fd)` 每连接 `spawn_piped` 一个
`distccd --inetd`，只把 fd 与 child stdin/stdout 对接；两边都不解析
`DIST/ARGC/ARGV/DOTI` 或 `DONE/STAT/SERR/SOUT/DOTO`。固定的 162/112 字节
夹具在宿主桥中逐字节通过（见 §5）。

仍有四个需要在对齐记录中明确的差异：**空闲超时归属**、**子进程回收窗口**、
**`DISTCCD_PATH` 环境变量**、**typed failure 只在宿主侧存在**。它们不影响
v1 的 C/clang host-only 字节测试，但在 DV-03 之前不能声称端到端通过。

## 2. 逐项对比

| 维度 | P09 原 contract | P02 实现（只读） | 结论 | 处理 |
| --- | --- | --- | --- | --- |
| 传输 | 宿主 loopback TCP → 每连接一条 vsock 流 → payload | 同；`handle(fd)` 接收一条原始 vsock 连接 | 一致 | 保持 |
| 子进程 | `/mnt/apk/bin/distccd --inetd`，路径从 manifest 解析 | `resolve_executable(apk_root, "distccd")`，`argv` 默认 `{"--inetd"}`（`distcc_bridge.cpp:28-35`） | 一致 | `apk_root` 必须是 `/mnt/apk` |
| 一个连接一个 child | 是 | 是；`active_` 上限 `max_jobs`，默认 4（`distcc_bridge.cpp:50-56`、`distcc_bridge.h`） | 一致 | 宿主与 payload 各自限流，有效并发取两者较小值 |
| stdin 半关闭 | 客户端 EOF 时关闭 channel 输出，让 `distccd` 看到 stdin EOF | 客户端 EOF 后 `shutdown(child.input_fd, SHUT_WR)` 并关闭（`distcc_bridge.cpp:113-114`） | 一致 | 保持 |
| stdout 排空 | 继续把响应回送给客户端，直到 channel EOF | `poll` child stdout，读到 EOF 后 `shutdown(fd, SHUT_WR)`，再 `SHUT_RD` 解除 uplink 阻塞（`distcc_bridge.cpp:133-168`） | 字节语义一致 | 响应必须完整；host 测试已覆盖 |
| stderr | 写日志或 `/dev/null`，不得进入 fd 1 | child stderr `dup2` 到 `/dev/null`（`process.cpp:152-156`） | 一致，P02 更严格 | v1 统一选择 `/dev/null`，不记录源码/对象 |
| 空闲超时 | 宿主 `idleTimeoutMs` 全局无字节 watchdog，0 关闭 | child stdout `poll` 超时 120000 ms（`distcc_bridge.cpp:18,133`） | **不一致**：宿主 watchdog 可能在合法编译期间先杀连接 | 生产宿主桥设 `idleTimeoutMs=0`，由 payload 的 120 s 负责；若必须设宿主超时，应 >180 s |
| 子进程回收 | 建议最多等 120 s 后 kill | stdout EOF 后 `wait_for_child(..., 5000)`，超时再 kill 1 s（`distcc_bridge.cpp:171-174`）；`stop()` 对全部 child `SIGKILL` 并最多等 2 s | **不一致（文档层）** | 把 P09 contract 改为“stdout EOF 后 5 s 回收，整体编译由 distcc 客户端超时约束” |
| 子进程环境 | `PATH`、`DISTCCD_PATH`、`HOME`、`TMPDIR` | `PATH=<apk>/bin:<apk>/bin/usr/bin`、`HOME`、`TMPDIR`、`XDG_CACHE_HOME`、`SHELL`、`TERM`、`LANG`；**没有** `DISTCCD_PATH`（`process.cpp:95-107`） | **差异**：P02 通过 PATH 找编译器，不依赖 `DISTCCD_PATH` | P09 contract 删除 `DISTCCD_PATH`，以 P02 环境为准；如未来需要，另做安全评审 |
| 工作目录 | 未规定 | `storage_root/tmp`；`storage_root` 为空时 `/`（`distcc_bridge.cpp:58-60`） | 兼容但 P09 未写 | P09 contract 明确要求可写 `TMPDIR`/cwd，不能是只读 APK 挂载 |
| 协议解析 | 不解析、不改写 | 不解析、不改写 | 一致 | 保持 |
| 错误分类 | 宿主 `CHANNEL_FAILED`/`IDLE_TIMEOUT`/`LIMIT_REJECTED`/`BRIDGE_CLOSED`；payload 侧 `DISTCC_COMPILER_MISMATCH` 等 | `handle()` 返回 `void`；spawn 失败仅关闭 fd；没有 typed payload error | **差异**：宿主只能看到连接关闭 | DV-03 先用 host 计数器 + distcc 客户端退出码判断；P02 若要 typed error，需另开票，不能由 P09 改 |
| 统计 | 宿主只记 outcome/字节/耗时 | payload 只记 `active_jobs`/`total_jobs`；无字节计数 | 兼容 | P10 聚合；不要记录源码/argv |
| 编译器一致性 | v1 只 C/clang；同版本、同 target、同 flags | 未校验版本或 target；`distccd` 按 PATH 找编译器 | **未实现** | DV-03 必须验证 `clang --version`、`-target`、`--sysroot`；不匹配时本地回退，不静默换编译器 |
| 认证/边界 | 宿主只绑 loopback；payload 只接受 vsock；无额外认证 | 同；`resolve_executable` 限制在 APK 根 | 一致 | 宿主端口按会话临时开放，随 VM 关闭 |

## 3. 需要在 P09 contract 中修正的文字

1. 删除 `DISTCCD_PATH=/mnt/apk/bin`；改为
   `PATH=/mnt/apk/bin:/mnt/apk/bin/usr/bin`，与 P02 `child_environment` 一致。
2. 明确 stderr 统一 `dup2` 到 `/dev/null`；payload 不得把 stderr 混入 fd 1。
3. 明确生产调用方传宿主 `idleTimeoutMs=0`；payload 的 `poll` 120 s 才是编译
   空闲上限。宿主超时只用于测试/诊断。
4. 把“waitpid 120 s”改为“stdout EOF 后 5 s 回收；整体由 distcc 客户端
   超时约束；payload stop 时 SIGKILL 并 reap”。
5. 把 `DISTCC_COMPILER_MISMATCH` 标为**产品层目标**，不是 P02 当前已实现的
   行为；在 P02 提供 typed error 前，宿主只能报告 `CHANNEL_FAILED`。
6. 明确 `cwd`/`TMPDIR` 必须是 encrypted storage 下的可写目录，不能是
   `/mnt/apk` 只读挂载。

## 4. 未对齐但不阻塞 v1 的项

- P02 没有按连接字节计数；P09 宿主有。P10 可以把宿主计数作为 Fast Lane
  编译流量的唯一来源，payload 只提供 job 数。
- P02 的 `SHUT_RD` 会在响应结束后丢弃客户端迟到的字节。distcc 的
  request/response 模型不会在 `DOTO` 之后继续发送；若未来支持 pump 模式，
  需要重新评审。
- P02 的 `max_jobs` 默认 4；P09 的 `maxConnections` 由调用方传入。二者都
  不是动态 vCPU 感知；DV-03 应固定为 `min(vCPU,4)` 并记录。

## 5. 本轮可复现证据（host-only）

- 固定字节夹具：`runtime/fastlane-distcc/src/test/resources/distcc/request-v1.hex`
  （162 字节）和 `response-v1.hex`（112 字节）；测试从资源读取并逐字节比较。
- `DistccTcpVsockBridgeTest`：16 tests OK，覆盖 echo、非 loopback 拒绝、
  factory 失败、关闭 listener、半关闭 drain、连接上限、空闲超时、关闭收敛、
  并发隔离、固定夹具 token、256 KiB 二进制透传、客户端 RST 释放槽位、
  `close()` 幂等与 `port()` 失效、stats sink 抛异常、child stderr 隔离。
- `DistccHostIntegrationTest`：1 test OK；真实 `distcc 3.4` → 桥 →
  `distccd --inetd` → `.o` 链接并运行输出 `42`（host-only，非 Android）。
- 命令与 Gradle 限制见 `implementation-report.zh-CN.md`。

## 6. 未验证

- protected payload / Bionic 上的 `distccd`、vsock、SELinux、`/mnt/apk/bin`
  执行位：`not-tested`（DV-03）。
- QEMU guest 的 `DISTCC_HOSTS` 转发地址与 `passt`/`gvproxy` 行为：`not-tested`。
- 编译器版本/target/flags 一致性与 C++：`not-tested`。
- 端到端加速比（n≥10）：`not-tested`；低于 1.5x 时按 spec 停止产品化。

## 7. 参考

- P02：`fastlane-payload/src/distcc_bridge.cpp:18,28-35,50-56,58-60,99,113-114,133-174`；
  `fastlane-payload/src/process.cpp:95-107,152-156`；
  `fastlane-payload/include/fastlane/distcc_bridge.h`。
- P02 协议：`fastlane-payload/docs/protocol.md` §1（distcc 端口为 raw bytes，
  每连接一个 `distccd --inetd`）。
- P09：`runtime/fastlane-distcc/src/main/java/.../DistccTcpVsockBridge.java`；
  `docs/reports/tickets/fastlane-P09/guest-contract.md`。
