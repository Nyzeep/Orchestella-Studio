# ACR-04: FLN1 作为 control/terminal canonical wire

Type: decision
Status: accepted（集成会话，2026-10-21；实现已落地，真机仍待 DV-02）
Priority: P0（阻塞 DV-02 终端联调）
Owner: 主协调；P02 owner 为 payload wire 的 normative 实现
Blocked by: 无
Related: [P02](../issues/P02-payload-runtime.md)、[P04](../issues/P04-terminal-multiplexer.md)、[P04 协议对照](../../docs/reports/tickets/fastlane-P04/protocol-alignment.zh-CN.md)、`fastlane-payload/docs/protocol.md`

## 决定

1. **`FLN1` v1 是 control/terminal 的 canonical wire contract**，以 `fastlane-payload/docs/protocol.md` 与 C payload 实现为准。magic、14 字节头、帧类型、限制、错误码不因本裁定改变。
2. **`OSFL` 退役**。宿主不再实现第二套 magic/opcode，也不保留“看起来一样”的私有字节。`runtime/fastlane-terminal/src/main/java` 中不再有 `TerminalProtocol`。
3. **`TerminalMux` 状态机保留**，wire 编解码落在 `TerminalCodec` seam 上；生产实现只有 `Fln1TerminalCodec`。session id 所有权、credit 方向、EOF/EXIT 分离、CLOSED reason、ERROR 方向都由 adapter 负责映射。
4. **跨语言字节契约只有一份**：`protocol/fln1-golden.json`。Java `Fln1GoldenFixtureTest` 与 C++ `test_golden_fixture.cpp` 读取同一文件，逐字节 decode/re-encode。
5. **不新造 ADR 编号**，不补写缺失的 ADR-0015/0017/0018/0019 正文。本 ACR 只记录该集成决策。

## 影响

- **P04**：状态改为“部分完成，FLN1 adapter 已落地；DV-02 待真机”。宿主侧 58 个状态机/codec 测试 + 1 个真实 payload 进程 E2E 通过；真实 vsock/PTY/Android UI 仍为 `not-tested`。
- **P02**：wire 字节不变；P02 报告增加“P04 已按 FLN1 对齐”的交叉引用。payload 仍是唯一 normative 实现。
- **DV-02 前置**：终端/代理/存储联调必须在真实 protected pVM 上重跑；本机 Unix socket 的 E2E 不能替代 vsock。
- **CI**：新增 `payload-host` job（CTest + E2E）与全模块 JVM 门禁；fixture 路径纳入 path filter。

## 语义映射（实现约束）

| 宿主概念 | FLN1 | 约束 |
| --- | --- | --- |
| host 分配 session id | 由 payload 在 `PTY_OPENED` 分配 | adapter 维护 local↔wire FIFO；OPEN_PTY 以 wire session 0 发出 |
| 双向 credit | 只有 guest→host 的 `initial_window` / `WINDOW` | host→guest DATA 不受窗口限制，仅受 32 KiB 帧上限；host 的 WINDOW 增量按 2×initial_window 截断 |
| EOF 携带退出码 | `EOF`（空）+ `EXIT`（i32 + term_signal） | adapter 在 `read` 中把 EOF 延迟到 EXIT；无 EXIT 时以 -1 收口 |
| host 发 ERROR | 不支持（payload 视为 unknown command） | host 侧违规按连接致命处理，由 channel 关闭流 |
| `METRICS` 64 B | `METRICS_OK` 36 B 字段不同 | terminal mux 不实现 METRICS 帧；telemetry 归 P10 |

## 未决 / 风险

- 真实 `AF_VSOCK`、payload 5 秒发送超时下的持续读取、Android 终端 UI 接线：DV-02。
- payload `WINDOW` 的 32 位加法在极端增量下会回绕（`pty.cpp`）；本 adapter 的增量上限使其不可达，但应由 P02 owner 后续修复。
- payload 默认 8 个 session，宿主默认 128；DV-02 前必须对齐 `FASTLANE_MAX_SESSIONS` 与 launch spec。
