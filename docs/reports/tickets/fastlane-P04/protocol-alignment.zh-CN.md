# P04 × payload v1 协议对照（零设备）

日期：2026-10-21 ｜ P04 宿主实现：`runtime/fastlane-terminal` ｜ 对端契约：`fastlane-payload/docs/protocol.md` @ `github/feature/fastlane-dataplane`（`2755f2d3`）｜ 本报告只读对端，不修改 B 的代码。

## 1. 结论

**P04 的 `OSFL` v1 与 payload 的 `FLN1` v1 不是同一条 wire protocol。** 两者共享 14 字节头、big-endian、session_id 与 length 的位置，但 magic、帧类型、最大帧、HELLO 语义、PTY 参数、关闭语义、credit 帧、错误码和端口模型全部不同。除“头部长度相同”外，没有任何一帧可以在两端之间成功交换；P04 的 HELLO（type 0x01）也会被 payload 按 `min_ver/max_ver/token` 解析并拒绝。

因此：**DV-02 不能把 P04 的 `TerminalProtocol` 直接接到 B 的 payload 上。** 本报告建议以 B 的 `FLN1` 为 vsock wire 契约，把 P04 的状态机/credit 语义放到一个 codec 适配层后面；不要改 B 的实现，也不要在 P04 里偷偷兼容两套 magic。

## 2. 逐字段差异表

P04 行号相对 `runtime/fastlane-terminal/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/terminal/`；payload 行号相对 `fastlane-payload/include/fastlane/protocol.h` 或协议文档章节。

| 字段 | P04 `OSFL` v1 | payload `FLN1` v1 | 判定 |
| --- | --- | --- | --- |
| magic | `4F 53 46 4C`（"OSFL"），`TerminalProtocol.java:26` | `46 4C 4E 31`（"FLN1"），`protocol.md` §2 / `protocol.h:kFrameMagic` | 语义不兼容 |
| version | 1，`TerminalProtocol.java:27` | 1，`protocol.h:kProtocolVersion` | 相同 |
| 固定头 | 14 B：magic/version/type/sessionId(i32)/length(u32)，`TerminalProtocol.java:28` | 14 B：magic/version/type/session_id(u32)/length(u32)，§2 | 布局相同，session 符号性不同 |
| 最大帧 | 1,048,576 B，`TerminalProtocol.java:29` | 65,536 B，`protocol.h:kMaxFramePayload` | 语义不兼容（P04 会发 payload 直接断链的帧） |
| 最大 DATA | 1 MiB（受 credit 限制） | 32,768 B，`protocol.h:kMaxPtyDataFrame` | 语义不兼容 |
| session id | 0=连接级；正整数；host 单调分配、退休不复用，`TerminalMux.java:545-559` | 0=连接级；否则 PTY id；无 host 分配/退休规则 | 语义不兼容（生命周期所有权不同） |
| 端口模型 | 单 vsock 承载全部 PTY，`PROTOCOL.md` §1 | 四端口：control/terminal/proxy/distcc，§1 | 语义不兼容 |
| 鉴权 | 无 | HELLO 携带 token，§3 | 语义不兼容 |
| HELLO | type `0x01`，12 B：maxFrame/initialCredit/features；可选、可重复，`TerminalProtocol.java:176-182`；`TerminalMux.java:275-280` | type `0x01`：u16 min_ver/u16 max_ver/u8 token_len/token；必须首帧且恰好一次；成功回 `0x81 HELLO_OK`（ver/caps/max_frame/initial_window），§2-3 | 语义不兼容 |
| OPEN_PTY | type `0x02`：i32 cols/i32 rows/i32 outputCredit/u16 name；ack 复用 `0x02` + 4 B inputCredit，`TerminalProtocol.java:196-205`；`TerminalMux.java:136-140` | type `0x03`：u16 cols/u16 rows + argv；ack `0x83 PTY_OPENED`（u32 pid/u32 initial_window），§3 | 语义不兼容 |
| DATA | type `0x03`，1 B..1 MiB，`TerminalMux.java:162-188` | type `0x04`，≤32,768 B，§3 | 语义不兼容 |
| RESIZE | type `0x04`，8 B cols/rows，无 ack，`TerminalProtocol.java:228-244` | type `0x05`，u16 cols/rows；ack `0x85 RESIZED`，§3 | 语义不兼容 |
| SIGNAL | type `0x05`，1 B 1..64，双向 | type `0x06`，1 B，host→guest | 语义不兼容（opcode/方向） |
| CLOSE | type `0x06`，空 payload，半关闭本端，`TerminalMux.java:215-223` | type `0x07`，u8 force；回 `0x87 CLOSED`（u8 reason），§3 | 语义不兼容 |
| EOF | type `0x07`，i32 exitCode，半关闭本端，`TerminalMux.java:226-236` | type `0x08`，空：PTY master EOF；退出码在 `0x09 EXIT`（i32 exit_code/u8 term_signal） | 语义不兼容 |
| 流控 | type `0x0C CREDIT`，i32 增量，窗口上限 4 MiB，`TerminalProtocol.java:275-290`；`TerminalMux.java:638-649` | type `0x0A WINDOW`，u32 credit_bytes；上限 2×initial_window，§4 | 语义不兼容 |
| PING/PONG | `0x08`/`0x09`，i64 nonce，`TerminalProtocol.java:292-299` | `0x02`/`0x82`，opaque 0..32 B，§3 | 语义不兼容 |
| METRICS | `0x0A`，空请求 / 64 B（8×i64），`TerminalProtocol.java:326-354` | `0x11` 请求 / `0x91 METRICS_OK`（u32 pty_sessions + 3×u64 + u32 distcc_jobs + u32 uptime_s = 36 B），§3 | 语义不兼容 |
| ERROR | `0x0B`，u16 code + 原始 UTF-8；正 session id 视为会话致命，`TerminalProtocol.java:301-324`；`TerminalMux.java:516-522` | `0xFF`，u16 code + u16-length string；错误码表不同（B 的 1=PROTOCOL_MALFORMED，P04 的 1=PROTOCOL_MAGIC） | 语义不兼容（opcode 与码表都冲突） |
| 回复命名空间 | 无 `0x8n`；请求/回复同 type | 请求 `0x0n`，成功回复 `0x8n`，错误 `0xFF`，§3 | 语义不兼容 |
| export/import | 无 | `0x0B..0x10`、`0x90` | P04 未覆盖 |
| 错误恢复 | 协议错误后由 channel 调用方关闭；`pumpOnce` 现在会 abort 全部 session | 畸形帧后关闭连接，不重新同步，§7 | 方向一致，细节不同 |

## 3. 为什么不能靠改常量对齐

以下差异不是 magic/opcode 重命名能解决的：

1. **HELLO 是硬门**：B 要求首帧 HELLO、token 鉴权、`HELLO_OK` 协商 caps；P04 把 HELLO 当可选能力公告。P04 的 12 B HELLO 会被 B 解析成 `min_ver/max_ver/token_len`，token 长度非法。
2. **PTY 创建参数不同**：B 用 argv 启动命令，P04 只传一个 advisory name；B 的 `PTY_OPENED` 返回 pid/window，P04 的 ack 只返回 input credit。两者对“谁启动 shell、谁持有 pid”的假设不同。
3. **关闭语义不同**：P04 是双向半关闭 + 独立 EOF；B 是 force close + CLOSED reason + EOF/EXIT 分离。把 P04 的 EOF 当 B 的 EOF 会丢失退出码/信号。
4. **流控模型不同**：P04 的 credit 是绝对值增量、上限 4 MiB；B 是 `WINDOW`、上限 2×initial_window。换算会改变背压边界。
5. **端口/鉴权模型不同**：B 的四端口 + token 是 P02 的安全边界；P04 的单连接无鉴权。直接复用会把 proxy/distcc 的隔离假设弄错。

## 4. 建议

**建议 A（推荐）：以 B 的 `FLN1` 为唯一的 vsock wire 契约。**

- 保留 `TerminalMux` 作为宿主侧 session/credit/半关闭状态机；把当前硬编码的 `TerminalProtocol` 抽成 `TerminalCodec` seam。
- 新增 `Fln1TerminalCodec`：把 P04 的 `OPEN_PTY/DATA/RESIZE/SIGNAL/CLOSE/EOF/CREDIT` 映射到 B 的 `0x03/0x04/0x05/0x06/0x07+0x87/0x08+0x09/0x0A`，并实现 HELLO/HELLO_OK/token。
- P04 的 `OSFL` codec 只保留给宿主单测/fixture，不再声称是 P02 契约。
- 这是一张新的集成票（P04b 或 P02/P04 联合票），需要 B 的 owner 确认；本会话不改 B。

**建议 B（不推荐）：让 B 增加 v2 支持 P04 的 OSFL。**

- 需要改 payload C 实现、token/端口模型、关闭语义和流控；改动面大于建议 A，且会把已经落地的 B 契约推翻。

**建议 C（最小临时方案）：DV-02 前不接真实 payload。**

- P04 继续作为宿主侧状态机/协议测试交付；真实终端联调阻塞，直到 A 或 B 完成。不得用 P04 的 fixture 冒充跨语言通过。

## 5. 对接点与未验证

- 与 B 的对齐点：`fastlane-payload/docs/protocol.md` §1（四端口）、§2（FLN1 头）、§3（HELLO/OPEN_PTY/CLOSE/EOF/EXIT/WINDOW）、§4（流控）、§5（错误码）、§7（畸形帧后关闭）。
- 与 C（distcc）的对齐点：B 的 distcc 端口是 raw byte stream，P04 不涉及；P04 只负责 terminal/control 的 framed 端口。
- 未验证：真实 vsock、真实 PTY、Android 终端 UI 接线、B payload 的实际 C 行为（本报告只读文档/头文件）、P04 codec 与 B 的跨语言 fixture（不存在）。
- 风险：在 A 完成前，任何“终端已联通”的结论都不成立；P04 的 56 个测试只证明宿主侧状态机与 OSFL codec。
