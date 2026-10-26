# 会话 10 报告：60 秒地板归因（DV-PVM-03）

> 设备别名 `qa-alt-01`（API 37 / arm64-v8a / Gunyah / protected-only），实例 `192796b1`。
> 基线 `main = 50e0790`，工作分支 `feat/pvm-10-floor-attribution`。
> 本轮**没有改任何产品代码**，只新增一条宿主侧诊断回路与一份归因报告。

## 1. 结论

**60 秒不是"多余的往返开销"，是「请求结束信号从未发出」导致的载荷侧帧超时，而客户端拿到的 distcc 字节数为 0——上一轮观测到的每一次"远端编译"其实都是本地补编。**

归到开工 Prompt 的候选表：命中 **C1 家族（产品缺陷）**，但机制与 C1 的字面描述不同，本轮把它记为 **C4**：

| 候选 | 开工 Prompt 的描述 | 本轮裁决 |
| --- | --- | --- |
| C1 | EOF 帧发了，但没被对端读到 | **否定**（帧根本没发） |
| C2 | 响应已送达，客户端在等流结束 | **否定**（`bytes_out = 0`，没有响应） |
| C3 | 60 秒在 guest 的 distcc / console 往返里（测量缺陷） | **否定**（客户端 60 秒后确实拿到 0 字节） |
| **C4** | **桥把「请求结束」定义成 TCP 半关闭；真实 distcc 客户端从不半关闭** | **成立** |

机制链条（每一环都有本轮证据，见 §4）：

1. 真实 distcc 客户端以 `DOTI<8 位十六进制长度>` 结束请求，**从不调用 `shutdown(SHUT_WR)`**（上游 `src/remote.c` 发完请求后直接用同一个 fd 读响应）。
2. 桥的 `pumpToChannel` 把「客户端 EOF」当作唯一的结束信号，读完才 `closeWrite()` 发 AXP1 EOF 帧。客户端不半关闭 → **AXP1 EOF 帧永不发出**。
3. 载荷侧 `axp1_read_frame` 在数据面上按 `frame_timeout_ms = 60000` 阻塞读。60 秒后返回 `kTimeout` → `send_rst(WireError::kTimeout)` → **请求从未进入编译**。
4. 桥收到 RST，`Axp1Error.TIMEOUT` 被 `reasonCode()` 映成 `AXP1_HANDSHAKE_TIMEOUT`（一个只该出现在握手的名字），连接以 `CHANNEL_FAILED` 终止。
5. distcc 客户端 `dcc_readx` 读到 `read() == 0` → `unexpected eof` → `EXIT_TRUNCATED(108)` → **本地补编**。补编成功，所以 `distcc_rc = 0`、`.o` 存在、verbose 输出里出现 "remote"（"remote compilation of '…' failed, retrying locally" 正好含这个词）。

**是否建议撤回「2.8× 回归」这个前提：建议撤回，而且要撤得更彻底。**
不是"加速了但被 60 秒吃掉"，而是**这条链路上从来没有发生过远端编译**。会话 9 §4.3 的两段式账本（"约 60 s 固定地板 + 约 20 s 载荷相关成本"）里，前半段是 `frame_timeout_ms`，后半段是**本地补编随夹具变大的成本**，两者都不是产品路径的固有开销。M3「交火」应当重判为"管道接通、作业未跨过"。

## 2. 仓库状态

| 项 | 值 |
| --- | --- |
| `main`（= `origin/main`） | `50e0790` |
| 工作分支 | `feat/pvm-10-floor-attribution`（从 `main` 切出） |
| 未提交改动 | 改前 5 个 `.scratch/` 改动（原样保留，未 commit、未 checkout、未 clean） |
| `git diff --name-only main..HEAD` | 见 §3 |
| 产品代码 | 未改动 |

## 3. 改动清单

| 文件 | 性质 |
| --- | --- |
| `runtime/fastlane-distcc/src/test/java/…/distcc/FloorLoopTest.java` | **仅观测**：新增宿主侧回路，5 个用例（1 个 `@Ignore` 的 PVM-10 回归测试） |

没有第二类改动：没有改默认值、没有改控制流、没有改 `.github/workflows/**`、没有改 `app/` 的任何开关。`fastlane-payload/src/compile_service/**` 一个字节都没动。

## 4. 证据

### 4.1 D0 · 宿主侧回路（无需设备，单轮 < 30 s）

```
./gradlew :runtime:fastlane-distcc:test --tests '*FloorLoop*'
```

> **命令勘误**：开工 Prompt 写的是 `testDebugUnitTest`，那是 Android 模块的任务名。`:runtime:fastlane-distcc` 是 `java-library`，任务名是 `test`。
> **环境坑**：本机必须在 ASCII 虚拟盘符下跑（`subst X:` 指向仓库），否则 test worker 对中文路径下的类一律 `ClassNotFoundException`（9 个测试类全红，与用例无关）。首次运行还需 `--rerun-tasks` 强制重编译，否则 Gradle 的文件监视会把新增测试源判成 UP-TO-DATE。

四个用例的实际读数（原样摘自 `build/floor-loop/floor-loop-report.txt`）：

```
[floor-loop] d0a eof_after_ms=8     bytes=12
[floor-loop] d0b wait_ms=1534  idle_ms=1500  outcome=IDLE_TIMEOUT/1534
[floor-loop] d0c wait_ms=1537  idle_ms=1500  bytes=0  outcome=IDLE_TIMEOUT/1538
[floor-loop] d0d half_close=no  payload_saw_eof=false  payload_bytes=2162  payload_timed_out=true  client_bytes=0  outcome=CHANNEL_FAILED/812
```

| 用例 | 桩的行为 | 实测 | 判读 |
| --- | --- | --- | --- |
| d0a | 客户端半关闭，桩回响应并发 EOF | EOF 在 **8 ms** 后到达 | 桥自身收尾是干净的，C1/C2 不成立 |
| d0b | 客户端半关闭，桩回响应但**不发** EOF | 客户端等 **1534 ms**，桥以 `IDLE_TIMEOUT` 结束 | "对端不发 EOF"的代价 = 桥的空闲看门狗，**不是**某个隐藏的 60 s |
| d0c | 桩完全不回 | 等 **1537 ms**，`IDLE_TIMEOUT` | 桥 ↔ 载荷之间没有别的隐藏超时 |
| d0d | **真实 distcc 形状：请求写完不半关闭** | 载荷收到全部 2162 字节、`saw_eof=false`、`timed_out=true`；客户端 **0 字节**；桥 `CHANNEL_FAILED` 于 812 ms | **C4 成立**：请求字节全到了，结束信号没到 |

关于 d0b 的换算：生产装配里 `idleTimeoutMs = min(jobDeadlineMs, 120_000)`，`jobDeadlineMs` 默认 300 000，所以**生产上的空闲看门狗是 120 s，不是 60 s**（开工 Prompt 的 F6 把 `socketReadTimeoutMs`（生产为 0，禁用）与空闲看门狗记混了；实测两者都不是 60 s）。因为 d0b 证明等待时长严格等于 `idleTimeoutMs`（1534 vs 1500，差值来自 50 ms 的看门狗轮询粒度），可以不跑满 120 s 就下结论。

### 4.2 D1 · 把设备上已有的证据读回来（零成本）

会话 9 的开工 Prompt 说过 `duration_ms` 已经采了、只是没人读。**它还在设备上。**
`files/avf-qa/pvm-compiler-<token>.json` 有 6 份残留，其中最决定性的一份（`direct_qemu` 模式、`gate_ms = 3610`）：

```json
"connections": [{"outcome": "CHANNEL_FAILED",
                 "failure_code": "AXP1_HANDSHAKE_TIMEOUT",
                 "bytes_in": 2672, "bytes_out": 0, "duration_ms": 60563}],
"connections_completed": 1, "jobs_requested": 2,
"bridge_elapsed_ms": 900158, "failure_code": "BRIDGE_JOBS_INCOMPLETE"
```

四个字段逐一对上 §4.1 的 d0d：

| 字段 | 值 | 含义 |
| --- | --- | --- |
| `bytes_in` | **2672** | 50 函数夹具的 `.i` 是 2418 B，加上 254 B 的 `DIST/ARGC/ARGV` 头正好 2672 → **请求一个字节不少地转发了** |
| `bytes_out` | **0** | 客户端拿到 **0 个 distcc 字节**。没有 `DONE`、没有 `DOTO`、没有 `STAT` |
| `duration_ms` | **60 563** | 连接活了 60.5 s，与 `frame_timeout_ms = 60000` + 建设/收尾开销吻合 |
| `failure_code` | **`AXP1_HANDSHAKE_TIMEOUT`** | 终态 `Axp1Error` 是 `TIMEOUT`。载荷侧产生 `kTimeout` 只有一条路：数据面 `frame_timeout_ms` 到点后 `send_rst(kTimeout)`；握手期超时则是不发 RST、直接关（桥侧会看到 `CLOSED` 而非 `TIMEOUT`）。所以这条记录本身就是"载荷帧超时"的直接指纹 |

**这一份记录同时否掉 C1 与 C2**：C1 要求"EOF 帧发了但没被读到"（可 `bytes_out = 0` 说明载荷压根没开始答），C2 要求"响应已送达"（同样被 `bytes_out = 0` 否掉）。

**顺带定性会话 9 的两个遗留问题**：

- **`BRIDGE_JOBS_INCOMPLETE` 不是独立缺陷**，是同一条因果链的尾巴：distcc 在 60 秒后放弃远端、改本地补编，第二个作业（driver 的 slack job）永远等不到第一条连接结束，`connections_completed` 只能停在 1。
- **`fallback_count = 0` 不能用来反驳本地补编**：它测的是加速车道 readiness gate 的回退（`PvmProbeInstrumentation:820` 的 `gateReady ? 0 : 1`），与 distcc 自己的 fallback 无关。
- 会话 9 那三份 `crossfire.json` 里 `connections_completed = 1` 的轮次，对应设备侧的 token 前缀是 `0a37`（一次 12 字节的探针，`bytes_in=12/bytes_out=14/7 ms`）与 `99c6`（上面这份 60.5 s 的真作业）。driver 只把 7 个标量抄进 `instrumentation_final`，**`connections[]` 数组被丢掉了**——这就是"采了没人读"的真实原因：不是没人读，是没落盘。

### 4.3 与会话 9 的 device 数据对账

单看 `distcc_ms`，会以为存在"60 s 地板 + 20 s 载荷成本"。把 60 s 认成 `frame_timeout_ms`、把余量认成**本地补编**，三份数据全部落在误差内：

| 轮次 | `distcc_ms` | − 60 000 | 同夹具本地编译 | 差 |
| --- | --- | --- | --- | --- |
| perf-s9b（2000 函数） | 91 097 | 31 097 | `local_compile_ms` 30 482 | **+615 ms** |
| perf-s9c（2000 函数） | 83 274 | 23 274 | `assemble_ms` 25 781 | −2 507 ms |
| perf-s9d（50 函数） | 62 554 | 2 554 | `assemble_ms` 2 406 | **+148 ms** |

两个边界都拟合得很好（+148 ms 与 +615 ms），中间那轮 −2.5 s 属于"补编时是否复用已有 `.i`"的差异。**没有任何一轮需要再引入一个独立的 20 s 段。**

另外，会话 9 的决定性实验（夹具缩 37 倍、`distcc_ms` 只从 83.3 s 降到 62.6 s）在这个模型下是必然的：60 s 是常数，剩下的是本地编译，本来就该随夹具缩小。

### 4.4 为什么 02a 的「payload 编译 1.05 s」不是反例

02a 走的是 QA 的 **compile 模式**（`PvmProbeInstrumentation` 的 `compile-*` 分支），那里显式写了 `outgoing.close()` 并在注释里说明"close() 发 EOF 帧、保留读半边"。那条路**主动**发了 AXP1 EOF，所以载荷立刻编译、1.05 s 返回。
distcc 那条路没有这一步。**同一个载荷，两条入口，一条会说话一条不会。**

## 5. 未验证项与偏差

1. **没有上设备复跑**。D0 与 D1 已经给出同一结论，按开工 Prompt §9 的停止条件"D1 读出 `duration_ms` → 停"执行。D2（补 5 个桥侧时间戳、`instrument_jobs` 对齐、guest `strace`）未做。
2. **C4 的最后一环是推断，不是直测**：d0d 证明的是"AXP1 EOF 没发出"，`duration_ms = 60 563` 证明的是"载荷侧 60 s 超时后 RST"。把两者焊起来的"载荷 `frame_timeout_ms` 是唯一的 60 000 来源"依据是常量唯一性 + 会话 9 的三条旁证（固定地板、CPU 空闲、夹具尺寸无关），**没有**在载荷里打印过时间戳。
3. **`@Ignore` 的回归测试没有跑过**：`pvm10Regression…` 按设计在修复前是红的，本轮保持禁用以免把 CI 打红；它是"能在本 bug 上变红"的那条断言，修复后启用即成为 DV-PVM-03 的回归测试。这是对 `/diagnosing-bugs`"Phase 1 必须有一条已变红的回路"的一处刻意偏差，理由写在用例注释里。
4. **d0b 的 120 s 换算没有实测**：只测了 1500 ms 两档（1500/1534/1537），"等待严格等于 `idleTimeoutMs`"是线性外推。
5. **设备侧 6 份证据里 4 份 `connections: []`**（11:48–12:44 那批，`bridge_elapsed_ms ≈ 600 s` 全期零连接）。这批与 `perf-s9b/c/d` 的 `connections_completed = 1` 对不上，本轮没有追它们的归属轮次；不影响结论（决定性那份来自另一轮）。
6. **真机日志里 `distcc_remote_seen = True` 是假阳性**：它只是 `"remote" in text.lower()`，而本地补编的告警串 `remote compilation of '…' failed, retrying locally` 正好命中。这条指标应当废弃或改名。

## 6. 风险

- **如果判错了**（比如真实原因是 guest 侧 distcc 在连接前就有 60 s 等待），代价是：PVM-10 会去修一个不存在的收尾协议缺陷，而真正的缺陷留在 guest/distcc 侧。缓解：`duration_ms = 60 563` 是**桥自己**记的时间，`bytes_out = 0` 是**桥自己**数的字节，两者都不依赖 guest 的 console 计时；且 d0d 在纯 JVM 里复现了同一组观测量。两个独立环境给出同一组数，判错概率低。
- **如果"distcc 不半关闭"这条前提在设备上是错的**（例如 guest 的 busybox/gvproxy 中间层替它 `shutdown`），那 C4 塌掉。缓解：d0d 用的是裸 socket，不是 distcc 本体；前提来自上游源码（`src/remote.c`、`doc/protocol-1.txt`），不是猜测。**上设备一条 `strace` 就能终结这个风险**，这也是交给下一轮的第一动作。
- **若直接把 AXP1 EOF 改成"发完 N 毫秒空闲就 EOF"**，会踩到 distcc 服务端的反向语义：`distccd` 在编译期间每秒 `select` 一次 stdin，读到 EOF 就 `killpg(SIGTERM)`（上游 `src/exec.c` 的 `dcc_collect_child`，"Client fd disconnected, killing job"）。**这条坑必须在 PVM-10 的设计里写明**，否则修完会变成"每次都编译到一半被杀"。

## 7. 需维护者决策（3 条）

1. **是否接受"交火从未真正跨过"这个重判**。若接受，M3 的完成状态要从 ✅ 降级，AX-07 的采样（n≥10、A/B、控温）必须先等 PVM-10 修完再开——因为现在采到的每一个数都是"超时 + 本地编译"。
2. **PVM-10 的修法选哪一种**：
   - (a) 桥内解析 distcc v1 前置（`ARGC` 计数 + `ARGV`/`DOTI` 长度），请求完整即发 AXP1 EOF——语义最正，但让桥从"透明字节管道"变成"懂协议"，与 `DistccTcpVsockBridge` 现有的"never parses the distcc protocol"契约冲突；
   - (b) 给 AXP1 加一个显式的「请求结束」旁路（客户端写完后桥主动发 EOF），仍不改桥的透明性，但需要 driver/客户端配合；
   - (c) 载荷侧改成"读到 `DOTI` 长度满足即开始编译"，不再依赖 EOF——改动落在载荷而不是桥，但会把 `frame_timeout_ms` 变成纯安全网。
   我倾向 (c) + (a) 的最小交集：**载荷按 `DOTI` 长度自终止，桥保持透明**。这需要你定，不属本轮。
3. **`connections[]` 要不要进 driver 的证据摘要**。本轮能定案纯属运气（设备上残留了原始 JSON）。建议把 `connections[]` 与 5 个桥侧时间戳一起写进 `instrumentation_final`，否则下一轮还会"采了看不见"。

## 8. 交接：会话 11 的第一动作

**一条 `strace` 定生死，不需要改任何代码：**

1. 设备上跑一轮 crossfire，guest 内 `strace -f -e trace=shutdown,close,write,sendto distcc clang …`，确认 `distcc` 进程**没有**对远端套接字调用 `shutdown(SHUT_WR)`。有这一条，C4 从"源码前提 + 宿主复现"升级为"设备直测"。
2. 紧接着同一轮把 `duration_ms`、`bytes_out`、`failure_code` 三个字段抄进 driver 的 `instrumentation_final`（§4.2 已经给出要抄哪几个字段）。
3. 然后才进 PVM-10 设计。**不要先改代码再验证**：`distccd` 侧"EOF = 客户端断开 = 杀编译"的反向语义会让一个想当然的修法把故障从"超时"变成"编译到一半被杀"，更难查。

## 9. 声明

- 没有碰任何被禁改的东西：`fastlane-payload/src/compile_service/**` 未改；`app/` 的产品默认开关未改；`.github/workflows/**` 未改；**没有任何默认超时值被改**（含 `frame_timeout_ms`）。
- 没有放宽或删除既有断言（含已知 flake `DistccTcpVsockBridgeTest.closeStopsTheListener`，本轮未碰）。
- 没有提交任何"性能修复"。
- 没有 `git add -A`，没有直推 `main`，没有合并自己的 PR。
- 敏感门：`--self-test` 通过，合并前 `--range main..HEAD` 通过。
- 设备：只做了 `run-as cat`（读文件），没有停 VM、没删实例、没装机、没改 stayon；设备保持开机，QA 双包与 Arch 实例保留给下一轮。
