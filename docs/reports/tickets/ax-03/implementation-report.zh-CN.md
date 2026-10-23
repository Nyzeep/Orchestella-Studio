# AX-03 加速通道握手与帧协议 实施报告

- 日期：2026-10-23
- Base：`main @ 4765d4b`
- Branch：`feat/accelerator-batch1`
- 工单：`.scratch/orchestella-accelerator/issues/AX-03-加速通道握手与帧协议.md`
- 零设备：本会话没有运行 adb、没有启动设备或真实 VM。AXP1 测试全部在内存传输上完成。

## 1. 结论

零设备范围内**完成**。新增独立的 `AXP1` v1 协议：12 字节长度前缀帧、HELLO/HELLO_ACK 握手、32 hex token 常量时间鉴权、能力协商、DATA/EOF/PING/PONG、RST 双向关闭、4 MiB 接收窗口与 100 ms→2 s 的握手前重连。

协议没有接入 `DistccTcpVsockBridge`：该 bridge 仍是透明字节泵（`DistccTcpVsockBridge.java:54`，拷贝循环在 `:397`），AXP1 是新的传输层 seam。真正把 guest/host 两端切到 AXP1 属于 AX-04/AX-06，本票不改变 payload 或 FLN1 wire。

## 2. 现状

- `DistccTcpVsockBridge` 只做字节泵：没有版本、鉴权、能力协商、帧边界或背压；它明确不解析 distcc 协议。
- 现有测试 `DistccTcpVsockBridgeTest` 有 **16** 个 `@Test`（工单描述为 17 个，实际基线是 16；`DistccHostIntegrationTest` 另有 1 个在无真实 distcc 时 skip 的集成测试）。
- 控制面已有的 FLN1 `HELLO/HELLO_OK` 属于 terminal/control codec，不能直接复用为加速通道：AXP1 需要独立 magic、版本、能力与错误码，且不能修改 FLN1 wire。

## 3. 协议

### 3.1 帧头（12 字节，大端）

| 字段 | 宽度 | 值 |
| --- | --- | --- |
| magic | 4 | `"AXP1"` |
| version | 1 | `1` |
| type | 1 | 见下表 |
| flags | 2 | 0（保留） |
| length | 4 | payload 字节数，读取前先与 max_frame 比较 |

`Axp1Codec.HEADER_BYTES=12`（`Axp1Codec.java:20`）；超长长度在分配前拒绝（`Axp1Codec.java:51`）。

### 3.2 帧类型

| code | 帧 | 方向 | payload |
| --- | --- | --- | --- |
| 1 | HELLO | 发起方 → 对端 | role、16B nonce、32B token、capability TLV |
| 2 | HELLO_ACK | 对端 → 发起方 | role、16B nonce、capability TLV |
| 3 | DATA | 双向 | 透明字节，<= max_frame |
| 4 | EOF | 双向 | 空；只关闭发送方向 |
| 5 | RST | 双向 | `u16` error code；立即双向关闭 |
| 6 | PING | 双向 | 0..32 字节 opaque |
| 7 | PONG | 双向 | 原样回显 PING |

工单列出的帧里没有独立 ERROR 帧，因此握手拒绝与协议错误统一由 `RST` 的 `u16` code 承载；错误码固定为 `VERSION_MISMATCH=1`、`AUTH_FAILED=2`、`FRAME_TOO_LARGE=3`、`PROTOCOL_ERROR=4`、`TIMEOUT=5`、`CLOSED=6`（`Axp1Error.java`）。这是对“错误码”要求的实现选择，不是新增 wire 类型。

### 3.3 HELLO / HELLO_ACK

HELLO payload：

```
u8 role (1=host, 2=guest)
u8 nonce_len (16)
nonce[16]
u8 token_len (32)
token[32] ASCII lowercase hex
capability TLV block
```

HELLO_ACK payload 相同，但没有 token；它携带对端 nonce 与对端能力。双方 role 必须互补，否则 `PROTOCOL_ERROR`。

### 3.4 能力 TLV

`u16 count`，每项 `u8 id | u16 length | value`：

| id | 能力 | 值 |
| --- | --- | --- |
| 1 | distcc | 1 字节 0/1 |
| 2 | max_frame | u32 |
| 3 | compiler_triple | UTF-8 |
| 4 | arch | UTF-8 |
| 5 | features | u16 count + 每项 u16 len + UTF-8 |

未知 id 不参与行为，但保留在 `unknownIds()` 中用于诊断；值的字节不进入日志（`Axp1Capabilities.java:117`）。协商规则：`max_frame` 取小、`distcc` 取与、features 取交集、对端的 triple/arch 优先（`Axp1Capabilities.java:162`）。

### 3.5 鉴权与超时

- token 必须是 32 个小写 hex；比较前对期望值与实际值各做一次 SHA-256，再用 `MessageDigest.isEqual` 比较 32 字节摘要，长度差不会短路（`Axp1Connection.tokenEquals`）。
- 失败只发送 `AUTH_FAILED`（2 字节 RST），不返回期望值、不记录 token；异常消息固定。
- 握手读取默认 3 s（`Axp1Limits.DEFAULT_HANDSHAKE_TIMEOUT_MS`），测试用 100 ms 验证边界。
- 握手后收到 HELLO/HELLO_ACK 视为 `PROTOCOL_ERROR` 并 RST。

### 3.6 流控、半关闭与重置

- 接收窗口默认 4 MiB（`Axp1Limits.DEFAULT_WINDOW_BYTES`）。`Axp1Window.offer` 在窗口满时阻塞 reader 线程，reader 因此停止从传输读取，形成反压；不会无限堆内存（`Axp1Connection.java:548`）。
- `EOF` 只 finish 入站方向，写方向仍可用；`closeWrite()` 幂等（`Axp1Connection.java:220`）。
- `RST` 立即置 closed、唤醒所有阻塞读、关闭传输；重复 `reset`/`close` 幂等（`Axp1Connection.java:235`）。
- `close()` 尽力发送 EOF 后释放双向；关闭后的读抛 `CLOSED`。

### 3.7 重连

`Axp1Connector`（`Axp1Connector.java:43`）：100 ms 起、每次翻倍、上限 2 s、默认 3 次重试，即 100/200/400 ms。只对握手前的 `TIMEOUT`/`CLOSED` 重试；`AUTH_FAILED`/`VERSION_MISMATCH`/`PROTOCOL_ERROR` 不重试。握手成功后数据面断开不在这里重连。

### 3.8 日志与统计

`Axp1Stats` 只有：入/出 DATA 字节数、入/出帧数、PING/PONG 计数、当前 buffered、持续时间、终止 code。`toString()` 不含 token、argv、源码或对象正文（测试断言 `statsCarriesCountsBytesAndDurationOnly`）。

## 4. 改动清单

| 文件 | 类型 |
| --- | --- |
| `runtime/fastlane-distcc/src/main/java/.../axp1/Axp1Error.java` | 新增 |
| `.../axp1/Axp1Exception.java` | 新增 |
| `.../axp1/Axp1Role.java` | 新增 |
| `.../axp1/Axp1Frame.java` | 新增 |
| `.../axp1/Axp1Codec.java` | 新增 |
| `.../axp1/Axp1Capabilities.java` | 新增 |
| `.../axp1/Axp1Limits.java` | 新增 |
| `.../axp1/Axp1Transport.java` | 新增（含 `FastLaneChannel` 适配） |
| `.../axp1/Axp1Stats.java` | 新增 |
| `.../axp1/Axp1Connection.java` | 新增（握手、读写、窗口、RST/EOF） |
| `.../axp1/Axp1Connector.java` | 新增（握手前重连） |
| `runtime/fastlane-distcc/src/test/java/.../axp1/Axp1ConnectionTest.java` | 新增，17 tests |
| `runtime/fastlane-distcc/src/test/resources/axp1/golden-v1.hex` | 新增，7 条 golden vectors |

没有修改 `DistccTcpVsockBridge`、FLN1 wire、payload 字节、版本号或 applicationId。

## 5. 测试矩阵

| 测试 | 覆盖 |
| --- | --- |
| `goldenVectorsDecodeAndReencode` | 7 条固定 wire（HELLO/HELLO_ACK/DATA/EOF/RST/PING/PONG） |
| `partialHeaderAndCoalescedFramesAreReadInOrder` | 半包（逐字节）与粘包 |
| `frameLengthIsBoundedBeforeAllocation` | 恶意 0xffffffff 长度，不分配 |
| `exactlyOneMebibyteIsAcceptedAndOneByteMoreIsNot` | 1 MiB 边界 |
| `versionMismatchIsTypedAndDoesNotEnterTheHandshake` | `VERSION_MISMATCH` + RST |
| `wrongTokenFailsWithoutEchoingTheExpectedValue` | `AUTH_FAILED`，异常不回显 token |
| `handshakeTimeoutIsBounded` | 100 ms 超时 |
| `negotiatedCapabilitiesUseTheServerLimitAndRecordUnknownIds` | max_frame 取小、features 交集、未知 id 记录 |
| `sixteenMebibyteStreamIsForwardedByteForByte` | 16 MiB 流 |
| `receiveWindowBoundsBufferedBytesAndPausesTheReader` | 64 KiB 窗口耗尽，buffered 不超窗，writer 被暂停 |
| `halfCloseClosesOnlyTheSendingDirection` | EOF 后仍可回写 |
| `closeWriteAndFullCloseAreIdempotent` | 重复半关闭/全关闭 |
| `resetClosesBothDirectionsAndIsIdempotent` | RST 双向关闭与幂等 |
| `concurrentResetWithWriterIsSafe` | writer 进行中 reset：无死锁、无二次关闭异常 |
| `pingIsAnsweredWithPong` | PING/PONG |
| `reconnectBackoffStartsAt100msCapsAt2sAndStopsAtThree` | 100/200/400 ms、3 次上限 |
| `statsCarryCountsBytesAndDurationOnly` | 统计面不含 payload/token |

## 6. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:fastlane-distcc:test` | BUILD SUCCESSFUL；Axp1ConnectionTest 17/17，DistccTcpVsockBridgeTest 16/16，DistccHostIntegrationTest 1 skipped（无真实 distcc） |
| `python -m pytest tools/fastlane-device -q` | 67 passed, 7 subtests passed |
| `python tools/ci/check_ci_module_coverage.py` | 58 passed, 0 failed |
| `git diff --check` | 通过 |

## 7. 偏差

1. **未接入 bridge**：AXP1 是独立协议实现，`DistccTcpVsockBridge` 仍是字节泵。把两端切到 AXP1 需要 guest 侧实现，属于 AX-04/AX-06；本票不改 payload/FLN1。
2. **错误帧**：工单帧表没有 ERROR，使用 `RST + u16 code` 承载错误；code 集合与工单一致。
3. **重连范围**：只对握手前 `TIMEOUT/CLOSED` 重试；鉴权/版本/协议错误立即失败，避免无意义重试。
4. **基线测试数**：工单写 `DistccTcpVsockBridge` 有 17 个测试，实际为 16 个；没有删除或跳过任何既有测试。

## 8. 风险与未验证

- **not-tested**：真实 vsock、真实 guest payload、设备上的 3 s 默认超时；测试使用内存传输与缩短超时。
- **not-tested**：AXP1 与 distccd 的端到端编译；属于 AX-06。
- **加密**：AXP1 只做 token 鉴权，不做传输加密。它必须运行在 vsock 或 loopback TCP 上；不得把 AXP1 端口暴露到 LAN。现有 bridge 的 loopback 约束不变。
- **token 生命周期**：token 是静态共享秘密，没有轮换/撤销；后续可在 HELLO 中加版本化扩展，但不能改 v1 wire。
- `AUTH_FAILED` 的常量时间比较覆盖 token 内容；长度格式错误会在解析阶段直接拒绝，仍返回同一错误码。

## 9. 待维护者决策

1. 是否接受 `RST + code` 作为唯一错误承载（不新增 ERROR 帧）。
2. AX-04 是否直接复用 `Axp1Connection` 的 DATA 流，还是需要把窗口/帧参数下放为配置。
3. 是否要求在 AX-04 前把 AXP1 接入 `DistccTcpVsockBridge`（本票按边界未接）。
4. 静态 token 的轮换策略与是否需要在 HELLO 中加入 key id。
5. 4 MiB 窗口与 1 MiB max_frame 是否符合真实 distcc 大对象/大预处理文件的预期。
