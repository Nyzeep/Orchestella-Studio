# P04 设计说明：Fast Lane 终端多路复用（零设备）

## 帧与编号

- 固定 14 字节头：`magic(4)="OSFL" | version(1)=1 | type(1) | sessionId(4, 大端有符号正整数) | length(4, 大端) | payload(length)`。
- `sessionId=0` 只用于连接级控制帧（HELLO/PING/PONG/METRICS）；PTY 会话使用 1..2^31-1，进程内单调分配，已退休 ID 永不复用。
- 命令：HELLO/OPEN_PTY/DATA/RESIZE/SIGNAL/CLOSE/EOF/PING/PONG/METRICS/ERROR/CREDIT。DATA、PONG、ERROR、CREDIT 是列表命令的必要配套，写入协议契约。

## 状态机

- 每个 session 独立跟踪两个半关闭标志：`localHalfClosed`（本端已发 CLOSE/EOF）与 `remoteHalfClosed`（对端已发 CLOSE/EOF）。`OPENING → OPEN → HALF_CLOSED_LOCAL | HALF_CLOSED_REMOTE → CLOSED`；协议错误/VM stop → `RESET`（对外表现为 CLOSED + 原因）。
- `openSession` 拒绝重复活动 ID、退休 ID、超过上限；`close` 幂等；`vmStopped()` 一次性收敛全部 session，之后任何 send 返回 typed 错误。
- `TerminalMux` 不创建线程、不缓存终端正文；只保存计数、credit 与状态，避免 fd/线程泄漏与日志泄漏。

## 流控与背压

- 每个方向独立 credit，初始 256 KiB；发送 DATA 前扣减，接收 DATA 后扣减接收 credit；接收方消费后显式 `grantCredit` 发 CREDIT。
- `sendData` 返回本次接受的字节数（0..len），调用方负责重试剩余部分；因此不会无界排队/OOM，`sendCredit` 与 `creditStalls` 可观测。
- 超过接收 credit 的 DATA 是协议违规：记录 `CREDIT_EXCEEDED`，重置该 session，不把字节交给消费者。

## 关闭/异常语义

- host CLOSE → HALF_CLOSED_LOCAL；guest EOF（可带退出码）→ HALF_CLOSED_REMOTE；两边都完成才 CLOSED。
- 非法 magic/version/length/type/sessionId → 解码错误或 ERROR 帧；未知 session 的 DATA → `UNKNOWN_SESSION`，不串流。
- VM stop → 所有 session RESET，credit 清零，通道关闭；不等待对端。

## 测试接缝

- `TerminalProtocol` 纯字节编解码 + 固定 fixture；`TerminalMux` 纯状态机；`TerminalMuxChannel` 用内存流同步 `pumpOnce()`，不启线程。
- 覆盖：round-trip、非法帧、短读、多 session 交错、重复/退休 ID、credit 耗尽/恢复/超限、半关闭、关闭中写入、VM stop、传输 EOF/错误收敛、100 session 压力、10k 帧压力、长输出无串流。
- `not-tested`：真实 vsock/PTY/Android 终端 UI 接线，留给 DV-02；本模块只提供协议与状态机契约。
