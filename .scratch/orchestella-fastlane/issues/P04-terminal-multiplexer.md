# P04: Fast Lane 终端多路复用（零设备）

Type: implementation
Status: partially implemented（FLN1 adapter landed 2026-10-21；Gradle not-run locally；DV-02 pending）
Priority: P1
Owner: terminal/protocol owner
Blocked by: None（零设备）；真机由 DV-02 验证
External gates: 无

## 目标

在单条 vsock 控制连接上复用多个 PTY 会话：

- 帧：`magic | version | type | sessionId | length | payload`；
- 命令：`HELLO`、`OPEN_PTY`、`RESIZE`、`SIGNAL`、`CLOSE`、`EOF`、`PING`、`METRICS`；
- 流控：窗口/credit，防止终端输出淹没 vsock；
- 关闭/VM stop/异常断开有确定语义。

## 零设备范围

- 纯 JVM 编解码/状态机 + 内存流测试；
- 与 payload C 实现的协议对齐（共享 fixture）；
- 不要求设备。

## 行为验收

- [ ] 编解码 round-trip；非法 magic/version/length/sessionId 拒绝。
- [ ] 多 session 并发 open/resize/close 不串流；sessionId 复用拒绝。
- [ ] 大输出/慢消费者触发流控；不 OOM；背压可观测。
- [ ] EOF/半关闭/重置/VM stop 收敛；fd/线程无泄漏。
- [ ] 与现有终端 UI 的 session 抽象兼容；不复制 Termux 代码。
- [ ] 日志不含按键正文/终端内容。

## 联网调研

- Termux terminal-emulator 的输入/输出/resize 语义；
- 本仓 `PtyMultiplexerProtocol`/`ModernTerminalView` 的现有协议；
- vsock 流控/credit 设计；
- Android `TerminalInputConnection` 生命周期（仅接口参考）。

## 交付

- 协议 spec/fixture；
- JVM `TerminalMux` + tests；
- C `pty_manager` 的协议适配接口（与 P02 协作）；
- 压力测试（10/100 session，长输出）。

## 停止条件

- 协议与 payload C 实现无法对齐：先冻结 fixture，再改一端；不双写不一致。
- 需要真实 vsock 才能决定：标 `not-tested`，DV-02 验证。

## Comments

### 2026-10-21 零设备实现

状态：**部分完成，FLN1 adapter 已落地；DV-02 待真机**。

- `TerminalMux`/`TerminalMuxChannel`/FLN1 codec 已落地：14 B 头、短读、多 PTY、guest→host credit、半关闭、VM stop/EOF/协议错误收敛。
- 58 个 JUnit 4 测试通过（standalone `javac --release 17 -Xlint:all -Werror` + JUnitCore），含 golden fixture、10k 帧、credit 耗尽、关闭中写入、reentrant open、transport EOF；1 个进程级 E2E 在设置 `-Dfln1.payload` 时通过。
- Gradle `:runtime:fastlane-terminal:test` **not-run**（本环境无 Android SDK/JDK 17）；CI 已覆盖全部 JVM 模块。
- **已裁定**：`FLN1` 是 canonical wire，`OSFL` 退役（ACR-04）。宿主 codec 适配 payload 分配的 session id、单向 credit 与 EOF/EXIT 分离；payload 字节未改。
- 跨语言 fixture：`protocol/fln1-golden.json`；进程 E2E 走 payload 的 Unix socket transport。
- 实现报告：`docs/reports/tickets/fastlane-P04/implementation-report.zh-CN.md`（§12 为集成更新）。
