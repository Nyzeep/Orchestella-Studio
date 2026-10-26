# P04 实现报告：Fast Lane 终端多路复用（零设备）

状态：**部分完成，FLN1 adapter 已落地；DV-02 待真机**（2026-10-21 集成更新，见 §12）｜ 日期：2026-10-21 ｜ 票：[P04-terminal-multiplexer.md](../../../../.scratch/orchestella-fastlane/issues/P04-terminal-multiplexer.md) ｜ 分支：`feature/fastlane-runtime`（模块）→ `integration/fastlane-20261021`（adapter）

## 1. 目标与边界

在单条 vsock 连接上复用多个 PTY 会话：固定头帧、多 session 状态机、双向 credit 流控、半关闭、VM stop 收敛、协议错误可归因。

本票是**零设备**交付：纯 JVM 编解码/状态机 + 内存流；没有真实 vsock、真实 PTY、Android 终端 UI 接线。DV-02 才能验证跨进程终端。

**重要边界**：P04 的 `OSFL` v1 与 B 的 payload `FLN1` v1 不兼容，不能直接联调。详见 [`protocol-alignment.zh-CN.md`](protocol-alignment.zh-CN.md)。本报告不把宿主侧单测写成跨语言通过。

## 2. 设计接缝

- `TerminalProtocol`：纯字节 codec，固定 14 字节头，`magic | version | type | sessionId | length | payload`；所有整数 big-endian。
- `TerminalFrame`：不可变帧；payload 进出都复制，避免调用方改字节。
- `TerminalMux`：纯状态机，不创建线程、不缓存终端正文；sink 由调用方提供。
- `TerminalMuxChannel`：把一个 `InputStream`/`OutputStream` 接到 mux；`pumpOnce/pumpAll` 由调用方驱动，不拥有线程。
- `TerminalMuxMetrics`：只含计数，不含终端内容。
- `TerminalErrorCode`/`TerminalCloseReason`：稳定的 typed 错误与关闭原因。

## 3. 改动清单

| 文件 | 改动 |
| --- | --- |
| `TerminalProtocol.java` | 14 B 头、短读循环、最大帧/长度/会话校验、HELLO/OPEN_PTY/RESIZE/SIGNAL/EOF/CREDIT/PING/ERROR/METRICS 编解码；OPEN_PTY name 字节长度校验 |
| `TerminalMux.java` | 多 session 状态机；先登记后写 OPEN_PTY/ack（防 reentrant sink 丢会话）；DATA/CREDIT 先改本地账再写，写失败即关闭 session；HELLO 的 peer maxFrame 生效；`sendError` 先写帧再通知 listener |
| `TerminalMuxChannel.java` | EOF/协议错误/传输 IOException 都 abort 全部 session；close 幂等并关闭两端流 |
| `PROTOCOL.md` | 明确 host-side v1 冻结、与 FLN1 不兼容；credit 部分补充“zero or partial”背压 |
| `src/test/java/.../TerminalProtocolTest.java` | 19 个测试：golden frames、全部帧类型 round-trip、非法 magic/version/type/length/session、截断/尾随、短读、10k 帧、fixture |
| `TerminalMuxTest.java` | 22 个测试：多 session、credit、半关闭、重复/退休 ID、VM stop、reentrant open、HELLO maxFrame、关闭中写入、credit overflow |
| `TerminalMuxChannelTest.java` | 11 个测试：pumpOnce/pumpAll、EOF/协议/传输错误 abort、两端流关闭、close 幂等、10k 帧 |
| `TerminalMuxStressTest.java` | 4 个测试：100 session、128 上限、credit stall、10k DATA |

## 4. 帧与命令

固定头：

```text
magic(4)="OSFL" | version(1)=1 | type(1) | sessionId(4, i32) | length(4, u32) | payload(length)
```

- `sessionId=0` 只用于连接级帧（HELLO/PING/PONG/METRICS/ERROR 的连接级用法）。
- PTY session id 为正整数，host 单调分配；同一 mux 内退休 id 不复用。
- 最大 payload 1,048,576 B；负 length 是 `PROTOCOL_LENGTH`，超限是 `FRAME_TOO_LARGE`。
- 短读：先读 1 字节判断干净 EOF，再 `readFully` 读剩余头与 payload；任何截断都是 `TRUNCATED_FRAME`。
- 命令：HELLO、OPEN_PTY、DATA、RESIZE、SIGNAL、CLOSE、EOF、PING、PONG、METRICS、ERROR、CREDIT。DATA/PONG/ERROR/CREDIT 是命令集的必要配套，已写入 `PROTOCOL.md`。

## 5. 状态机

每个 session 独立跟踪两个半关闭标志：

```text
OPENING --accept--> OPEN
OPEN --local CLOSE/EOF--> HALF_CLOSED_LOCAL
OPEN --remote CLOSE/EOF--> HALF_CLOSED_REMOTE
HALF_CLOSED_LOCAL --remote CLOSE/EOF--> CLOSED
HALF_CLOSED_REMOTE --local CLOSE/EOF--> CLOSED
任意状态 --协议错误/VM stop--> CLOSED（带 close reason）
```

- DATA 只在 `canSendData()` / `canReceiveData()` 允许时通过；本地半关闭后写 DATA 是 `SESSION_CLOSED`。
- CLOSE/EOF 在发送侧幂等；重复 OPEN_PTY 对活动 id 是 `SESSION_EXISTS`，对退休 id 是 `SESSION_RETIRED`。
- 默认最多 128 个活动 session；超限是 `SESSION_LIMIT`。
- `vmStopped()` 一次性把全部 session 置 CLOSED，credit 清零，不写帧；之后任何 send/onFrame 都是 `VM_STOPPED`。
- `TerminalMuxChannel.pumpOnce()` 在干净 EOF 时 `abortAll(TRANSPORT_CLOSED)`，在协议错误时 `abortAll(PROTOCOL_ERROR)`，在原始 IOException 时 `abortAll(TRANSPORT_CLOSED)`；不会留下半开 session。

## 6. 流控与背压

- 每方向独立 credit；初始 256 KiB，最大 4 MiB。
- `sendData` 接受 `min(data.length, peerMaxFrameBytes, 1 MiB, sendCredit)`，返回接受字节数；调用方重试剩余。
- 0 或部分接受都计 `creditStalls`；`PROTOCOL.md` 已同步这一口径。
- 接收 DATA 超过 receiveCredit 是 `CREDIT_EXCEEDED`：发 ERROR、重置 session，不把字节交给消费者。
- CREDIT 溢出是 `CREDIT_OVERFLOW`：发 ERROR、重置 session。
- `sendData` 先扣 sendCredit 再写；写失败立即关闭 session。`grantCredit` 先加 receiveCredit 再写；写失败立即关闭 session。避免“本地账与对端账永久不一致”。
- `HELLO.maxFrameBytes` 会约束本端后续 DATA 的最大帧；`initialCredit` 作为建议值记录，实际 credit 由 OPEN_PTY/ack 协商。

## 7. 测试

### 7.1 命令

```bash
javac --release 17 -Xlint:all -Werror \
  -d <out/main> $(find runtime/fastlane-terminal/src/main/java -name '*.java')
javac --release 17 -Xlint:all -Werror \
  -cp <out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  -d <out/test> $(find runtime/fastlane-terminal/src/test/java -name '*.java')
java -cp <out/test>:<out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  org.junit.runner.JUnitCore \
  io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalProtocolTest \
  io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMuxTest \
  io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMuxChannelTest \
  io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMuxStressTest
```

### 7.2 结果

- `javac --release 17 -Xlint:all -Werror`：主源码与测试均通过。
- JUnit：**56 tests OK**（Protocol 19 + Mux 22 + Channel 11 + Stress 4）。
- Gradle `:runtime:fastlane-terminal:test`：**not-run**。本环境没有 Android SDK；根 `settings.gradle.kts` 包含 `:app`，配置阶段不可用。没有把 standalone 结果冒充 Gradle 结果。

### 7.3 覆盖的行为

- golden frame：HELLO 的精确 wire hex；fixture 每行 decode 后 re-encode 字节相等。
- 非法 magic/version/type/长度/负长度/截断头/截断 payload/尾随字节/session 0 与非 0 约束。
- 短读（每次只返回 1 字节）仍能读完整帧。
- 10k 帧：codec round-trip；mux 内 10k DATA 保持 credit/顺序；channel pumpAll 10k PING。
- 多 session 并发 open/data/resize/signal/close 不串流；100/128 session 上限。
- credit：耗尽返回 0、部分发送、恢复、CREDIT_EXCEEDED、CREDIT_OVERFLOW、10k DATA 不超窗口。
- 半关闭：host CLOSE → guest EOF、guest EOF → host CLOSE、local EOF 后写 DATA 被拒、close 幂等。
- reentrant sink：guest 在 `onOpenRequest` 内立即 accept，host 不会因为“先写后登记”丢 ack。
- VM stop：全部 session CLOSED + `VM_STOPPED`；之后 send/onFrame 均 typed 失败。
- transport：干净 EOF、畸形帧、原始 IOException 都 abort 全部 session；close 关闭 input/output 两端。
- 日志/异常消息不含终端正文；metrics 只含计数。

## 8. 已知限制（不是伪造通过）

1. **与 B 的 wire 不兼容**：见协议对照报告。P04 的 56 个测试只覆盖宿主侧 OSFL 状态机；没有一条跨语言帧通过。DV-02 前必须完成 codec 选择。
2. **sink 在 mux 锁内写**：`TerminalMux` 的所有 send/onFrame 都是 `synchronized`，sink 写 vsock 时持锁。若 socket 写阻塞，reader 线程无法进入 `onFrame` 授予 credit，可能形成停顿。当前设计明确“sink 必须短/不阻塞”；真正的非阻塞写需要独立 writer 线程/有界队列，属于后续集成票。
3. **listener 异常**：listener 在 mux 锁内调用；若抛 RuntimeException，当前 pump 会中止，可能留下 `OPENING` session。接口文档已要求 listener 不得抛异常；后续可在 codec/集成层隔离。
4. **retired id 集合增长**：同一 mux 内退休 id 保存在 `HashSet`，长寿命连接下随 session 数增长；128 活动 session 上限不限制历史总数。10k session 量级可接受，但不是无界安全。
5. **没有真实 vsock/PTY/UI**：`connectVsock`、`openpty`、`TIOCSWINSZ`、kill、Android 终端 UI 均未验证。

## 9. 未验证（not-tested）

- 真实 vsock 上的短读/半关闭/重置行为。
- payload 的 `openpty`/`fork`/`TIOCSWINSZ`/`kill`/exit code 行为。
- Android 终端 UI 的 session 抽象接线（现有 `PtyMultiplexerProtocol` 使用 string session id 与不同 magic，需要适配层）。
- 与 B 的 FLN1 跨语言 fixture（不存在；需要新票）。
- 慢消费者在真实 socket 上的背压与阻塞时长。
- 真实 VM stop 时 payload 的清理时序（DV-02）。

## 10. 研究来源

- `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md` §4.4/§5.2/§6。
- `.scratch/orchestella-fastlane/issues/P04-terminal-multiplexer.md`。
- `docs/research/2026-10-21-cross-lane-reference-survey.md` §2/§5/§9。
- 对端契约：`fastlane-payload/docs/protocol.md` @ `github/feature/fastlane-dataplane`（`2755f2d3`），只读。
- 现有 App 终端参考：`app/src/main/java/io/github/nyzeep/orchestella/terminal/PtyMultiplexerProtocol.java`（未修改）。
- vsock 流控/credit 设计参考：spec §4.4；Termux 终端/session 生命周期参考：调研 §2。

## 11. 风险与下一步

1. **最高风险**：P04 与 B 的 wire 不兼容。若 DV-02 直接联调，会在 HELLO 阶段失败。先做 codec 决策，再开真实终端票。
2. **背压实现**：sink 持锁写是已知 liveness 风险；若 DV-02 出现“输出大时终端卡住”，优先加独立 writer/有界队列，而不是调大 credit。
3. **UI 适配**：现有 App 的 string session id 与 P04 的 int id 不同；需要明确的 `TerminalSession` 适配层，不在本票偷改 UI。
4. **协议演进**：若选择 B 的 FLN1，P04 的 `OSFL` fixture 只能作为宿主回归，不得再标“P02 契约”；本报告已修正 `PROTOCOL.md` 的状态行。


## 12. 2026-10-21 集成更新（取代前文的 OSFL 状态）

本节记录 `integration/fastlane-20261021` 上的 P0 收口；前文 §1–§11 是模块会话当天的历史记录，凡与本節冲突处以本節为准。

### 12.1 决策与实现

- **FLN1 是 canonical wire**（ACR-04：`.scratch/orchestella-fastlane/issues/ACR-04-fln1-canonical-wire.md`）。`OSFL` 退役，`TerminalProtocol.java` 已从生产源码删除；`src/test/resources/terminal-frames-v1.txt` 同步删除。
- 新增 `TerminalCodec` seam 与唯一生产实现 `Fln1TerminalCodec`；`TerminalMux` 的状态机、公开 API、半关闭与 VM stop 语义保留，wire 细节移入 adapter。
- `Fln1Payloads` 承载 FLN1 payload 布局；`TerminalMuxChannel` 只负责把流接到 codec，畸形帧仍是连接致命。
- payload wire **未修改**；B 的实现仍是 normative。

### 12.2 adapter 关键映射

- **session id**：FLN1 由 payload 在 `PTY_OPENED` 分配。host 用 wire session 0 发 `OPEN_PTY`，codec 以 FIFO 把返回 id 绑定到 host session；host 不再假设自己分配的 id 在线上有效。
- **credit**：只有 guest→host 有窗口；host 的 `sendData` 不检查任何 credit，按 32 KiB 帧上限切分；guest 接收 host DATA 也不扣 `receiveCredit`。`WINDOW` 增量按 `2 * initial_window` 截断。
- **EOF/EXIT**：codec 在 `read()` 中把 `EOF` 延迟到 `EXIT`，mux 只收到一次带真实退出码的结束；没有 EXIT 时以 -1 收口。
- **ERROR**：payload 不接受 host ERROR；host 侧违规改为抛 typed 异常并由 channel 关闭连接。
- **METRICS**：FLN1 的 `METRICS_OK` 字段与 OSFL 的 64 字节计数不同，terminal mux 不再实现 METRICS 帧；payload 遥测归 P10。

### 12.3 测试与 fixture

- `protocol/fln1-golden.json`：唯一跨语言 fixture，覆盖 HELLO/HELLO_OK/OPEN_PTY/PTY_OPENED/PTY_DATA/RESIZE/RESIZED/SIGNAL/CLOSE/CLOSED/EOF/EXIT/WINDOW/ERROR/PING/PONG。Java `Fln1GoldenFixtureTest` 与 C++ `test_golden_fixture.cpp` 读取同一文件并逐字节比对。
- 宿主 JVM：**59 tests OK**（codec 20 + fixture 4 + mux 22 + channel 9 + stress 4；数量与旧 56 的差异见 §12.4）。2026-10-21 加固后为 **64 tests**（codec 21 + fixture 4 + mux 26 + channel 9 + stress 4，见 §12.6）。命令：`javac --release 17 -Xlint:all -Werror` + JUnit 4.13.2。
- 进程级 E2E：`Fln1PayloadE2ETest` 启动真实 `fastlane_payload_host`，经 Unix socket 完成 HELLO(token)→HELLO_OK→OPEN_PTY→PTY_DATA→EOF/EXIT，断言输出 `e2e-ok` 与退出码 3；**通过**（本机，非 vsock）。
- Gradle `:runtime:fastlane-terminal:test`：本环境仍 **not-run**（无 Android SDK/JDK 17 toolchain）；CI 的 `jvm-distcc` job 已改为运行全部模块。

### 12.4 数量变化说明

旧 56 = Protocol 19 + Mux 22 + Channel 11 + Stress 4。新 59 = Fln1Codec 20 + GoldenFixture 4 + Mux 22 + Channel 9 + Stress 4。Protocol 的 OSFL 字节断言被 FLN1 golden fixture 取代；Channel 的 2 项 OSFL 专用编码断言合并进 fixture；Mux 删除 1 项 FLN1 不存在的对称 credit 断言，新增 duplicate HELLO 与 frame-limit 用例。净增 3 项，覆盖未减少。另有 1 个 E2E 测试在未设置 `-Dfln1.payload` 时跳过（设置后 60 项）。

### 12.5 仍然未验证

- 真实 `AF_VSOCK`、Android SELinux、devpts、Android 终端 UI 接线（DV-02）。
- payload 5 秒发送超时下的持续读取、真实 PTY 退出时序。
- 与现有 App `PtyMultiplexerProtocol`（string session id）的 UI 适配层。

### 12.6 2026-10-21 加固（failed-open / credit / session limit）

- **failed OPEN_PTY 污染 FIFO（P0）**：payload 的 `send_error` 固定 `session 0`（`runtime.cpp:186-195`），open 失败也走同一路径（`:603-611` → `:704`）。旧 adapter 在 `OPEN_PTY` 时把 local id 压入 `pendingOpens`，`handleError` 只在 `sessionId>0` 时关闭 session，于是下一次 `PTY_OPENED` 会绑定到已经失败的会话。修复：`Fln1TerminalCodec.translate(OP_ERROR)` 在 `wireSession==0` 时清空 `pendingOpens`；`TerminalMux.handleError` 在 `sessionId==0` 时仅关闭所有 `OPENING` session（`PROTOCOL_ERROR`），`OPEN` session 交给 transport EOF/`abortAll`。未改 payload，未给 ERROR 增加 session 语义。
- **默认 session 上限**：新增 `TerminalMux.DEFAULT_MAX_SESSIONS=8`，默认构造器使用 8；`MAX_SESSIONS=128` 仅为显式硬上限。需要 128 的测试显式传参。第 9 个 PTY 在宿主侧直接 `SESSION_LIMIT`，不再等到 payload 报错落进上一条。`FastLaneLaunchSpec` 未增加 `maxTerminalSessions`，标记 `not-wired`；未来可配置时必须同时配置 payload 与宿主。
- **host→guest credit**：`FLN1` 只有 guest→host 的 `initial_window`/`WINDOW`。旧实现让 host 也扣 `sendCredit`（4 MiB），guest 也扣 `receiveCredit`，累计 4 MiB 后 `sendData` 返回 0 / guest 报 `CREDIT_EXCEEDED`。修复：HOST 的 `sendData` 只受 `peerMaxFrameBytes`/`MAX_PTY_DATA` 限制，不扣 `sendCredit`；GUEST 的 `handleData` 不检查/不扣 `receiveCredit`；GUEST 发送与 HOST 接收仍保留窗口。host→guest 的背压来自同步 sink 与 payload 的 PTY 写入，不是窗口。
- **回归**：codec 1 项 + mux 4 项（failed-open 关闭与 FIFO 重置、ERROR 0 不影响 OPEN、默认 8、5 MiB host→guest）。模块测试 59→64（另 1 个 E2E 在未设置 `-Dfln1.payload` 时跳过）；`javac --release 17 -Xlint:all -Werror` 通过。
- **未验证**：真实 payload 的 `SESSION_LIMIT` 与 failed-open 时序仍只在宿主侧/普通进程 E2E 验证，DV-02 需在真实 vsock/PTY 上重跑。
