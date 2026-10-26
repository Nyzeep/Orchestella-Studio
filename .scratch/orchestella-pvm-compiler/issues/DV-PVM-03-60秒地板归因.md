# DV-PVM-03 · 60 秒地板归因

- 状态：**已归因（C4）**，修复拆给 PVM-10
- 分支：`feat/pvm-10-floor-attribution`
- 报告：`docs/reports/orchestration/session10-floor-attribution-2026-10-26.zh-CN.md`
- 基线：`main = 50e0790`

## 一句话

60 秒不是往返开销，是**载荷侧帧超时**：桥把「请求结束」定义成 TCP 半关闭，而真实 distcc 客户端从不半关闭（它用 `DOTI<8 位十六进制长度>` 自定界）。载荷空等 `frame_timeout_ms = 60000` 后 `RST(kTimeout)`，客户端拿到 0 字节 → distcc 本地补编。

**推论：这条链路上从来没有发生过远端编译。** 会话 9 的「2.8× 回归」与「60 s 地板 + 20 s 载荷成本」两段式账本都要撤回——前半段是帧超时，后半段是本地补编随夹具变大的成本。

## 判据表（实际结论）

| 观测 | 归因 | 实测 |
| --- | --- | --- |
| 桥自身收尾是否拖时间 | 否 | 对端发 EOF 后客户端 **8 ms** 看到 EOF |
| 「对端不发 EOF」的代价 | = 桥空闲看门狗 | 1534 ms / 1537 ms，两档均等于 `idleTimeoutMs`（1500）；生产为 `min(jobDeadlineMs,120_000)` = **120 s**，非 60 s |
| 链路上有无隐藏 60 s | 无 | d0c 静默对端仍只等 1537 ms |
| 客户端不半关闭会怎样 | **请求结束信号缺失** | 载荷收到全部 2162 B、`saw_eof=false`、`timed_out=true`；客户端 **0 B** |
| 设备侧 `duration_ms` | **60 563** | `bytes_in=2672`（= 2418 B `.i` + 254 B 头）、`bytes_out=0`、终态 `Axp1Error.TIMEOUT` |

## 回路

```
./gradlew :runtime:fastlane-distcc:test --tests '*FloorLoop*'
```

- 纯 JVM、无设备、单轮 < 30 s。
- **必须在 ASCII 虚拟盘符下跑**（`subst X:` 指向仓库），中文路径下 test worker 会对所有测试类报 `ClassNotFoundException`。
- 新增测试源后首跑要加 `--rerun-tasks`。
- 计时落盘：`runtime/fastlane-distcc/build/floor-loop/floor-loop-report.txt`。
- `pvm10RegressionRequestWithoutHalfCloseMustReachThePayload` 是**能在本 bug 上变红**的回归测试，按设计 `@Ignore`，PVM-10 修复后启用。

## 为什么不是 C1 / C2 / C3

- **C1**（EOF 帧发了没读到）：否定。d0d 显示载荷端 `saw_eof=false`——帧根本没发；设备侧 `bytes_out=0` 也说明载荷没开始答。
- **C2**（响应已送达，客户端等流结束）：否定。`bytes_out=0`，没有响应可等。
- **C3**（60 s 在 guest 的 distcc/console 里）：否定。桥自己数的 `duration_ms` 就是 60.5 s，且客户端确实拿到 0 字节后转本地。

## 修复约束（写给 PVM-10，别踩）

1. **不能简单地把「空闲 N 毫秒就发 EOF」当修法**：`distccd` 在编译期间每秒 `select` 一次 stdin，读到 EOF 就 `killpg(SIGTERM)`（上游 `src/exec.c` `dcc_collect_child`，日志 "Client fd disconnected, killing job"）。修不好会从"超时"变成"编译到一半被杀"。
2. 三个候选修法（§7 决策 2）：(a) 桥解析 distcc v1 前置；(b) 加显式请求结束旁路；(c) 载荷按 `DOTI` 长度自终止。本轮倾向 (c)，保持桥的透明契约。
3. `AXP1_HANDSHAKE_TIMEOUT` 这个名字对数据面超时是**误标**：`Axp1DistccChannelFactory.reasonCode()` 把 `Axp1Error.TIMEOUT` 一律映成握手超时。修 PVM-10 时顺手拆成两个 reason code，否则下一轮的证据还会骗人。

## 遗留

- `driver` 的 `instrumentation_final` 只抄 7 个标量，**丢掉 `connections[]`**。本轮能定案靠的是设备上残留的原始 JSON。必须补。
- `distcc_remote_seen` 是假阳性：`"remote" in text.lower()` 会命中本地补编的告警串 `remote compilation of '…' failed, retrying locally`。废弃或改名。
- `fallback_count` 测的是加速车道 readiness gate 回退，不是 distcc 的本地回退，不能用来反驳"发生了本地补编"。
- 设备侧 6 份证据里 4 份 `connections: []`（11:48–12:44 批次，全期零连接），归属轮次未追。
