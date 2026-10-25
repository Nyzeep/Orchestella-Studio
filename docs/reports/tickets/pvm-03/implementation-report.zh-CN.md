# 会话 5 报告：PVM-03 + PVM-06

- 日期：2026-10-25
- 分支：`feat/pvm-03-compile-service`（从 `main = e2e5b60` 切出）
- 设备：**未使用**（无 adb、无 APK、无 VM）

## 1. 结论

| 工单 | 结论 | 说明 |
| --- | --- | --- |
| PVM-03 | **done（零设备口径）** | 编译服务 payload、AXP1 server、distcc v1 编解码、argv 白名单、资源上限与失败码全部实现并有可执行证据；真实 libclang 编译按票面归 DV-PVM-02，标 `not-tested` |
| PVM-06 | **not-started** | 时间盒用尽，按开工 Prompt §4「不做半成品」处理；§4 给出已完成的设计结论与先行条件，可直接开工 |

PVM-03 的验收命令中，`test-distcc-inprocess.sh` 原本不存在，本轮补齐并真跑：
`PVM_DISTCC_E2E_OK cases=2`、`PVM_EQUIVALENCE verdict=EQUIVALENT`。

## 2. 仓库状态

- 分支 `feat/pvm-03-compile-service`，中文 commit，未合并、未 push `main`。
- 5 个已跟踪的规划文档改动（accelerator/fastlane 那批）**原样保留**，未提交、未还原、未 stash；提交按显式文件清单 `git add`，未用 `-A/-u`。
- 撤销了上一轮遗留的 `fastlane-payload/{include/fastlane/transport.h, src/transport.cpp}` 改动：本设计自带 socket 层后不再需要，回到 `main` 可减少对 FLN1 传输面的侵入。
- CI：见 §5「未验证项」——本轮所有 C++ 证据在 **Windows + MinGW 14.2** 上真跑，Linux CI 尚未跑到这份提交。

## 3. PVM-03

### 3.1 wire 对齐证据

三层 wire 各有一手权威，且三个独立实现互相对齐（不是自己证明自己）：

| 层 | 权威 | 本轮证据 |
| --- | --- | --- |
| distcc v1 | 上游 `doc/protocol-1.txt` + `src/rpc.c`/`serve.c`/`exitcode.h`（本地上游 clone，`AC_INIT 3.4`）与仓内 `request-v1/response-v1` 字节夹具 | `encodes_the_golden_response_byte_for_byte` 逐字节相等；`hex_parameter_is_eight_lowercase_digits` 断言 8 位小写左补零；`DONE` 回显协议版本 1 |
| AXP1 帧 | Java `axp1/*.java` 与其 `golden-v1.hex` | 5 类帧（DATA/EOF/RST/PING/PONG）逐字节复现；HELLO 用 golden 直送，服务端回 HELLO_ACK 且 role=GUEST、nonce 16B |
| 独立客户端 | `tools/pvm-compiler/axp1_distcc_client.py`（按 Java 布局 + protocol-1 手写，**不看 C++ 实现**） | `test_axp1_client.py` 里 HELLO 与 golden **整帧相等**、请求流与 `request-v1.hex` 相等 |

握手协商修正（上一轮缺陷）：HELLO_ACK 现在回 `min(本地, peer)`，不再是本地值。Java 侧
`Axp1DistccChannelFactory` 自己也算一次 `min`，双方不一致会在下一帧立刻 `FRAME_TOO_LARGE`。
测试 `acknowledges_the_negotiated_frame_and_compiler_identity` 固定该行为。

RST 码对齐 Java 语义：握手期 `VERSION_MISMATCH→1`、`FRAME_TOO_LARGE→3`、`PROTOCOL_ERROR→4`、
`AUTH_FAILED→2`；超时按 Java 行为**只断连或回 5**（不静默）。上一轮把 `kFrameTooLarge` 一律
降级成 `PROTOCOL_ERROR`、把版本不匹配直接断连，均已按 Java 参考实现纠正。

### 3.2 argv 白名单

三分类 ALLOW / ABSORB / REJECT，`ARGV_REJECTED:<CLASS>` 为固定枚举、**不回显原参数**
（`never_echoes_the_offending_argument` 直接对 `/tmp/secret-host-path` 断言）。

- 17 个拒绝类，每类至少一条表驱动用例（26 行表）；`PATH_LIKE` 不再是死代码：
  `-std=c11/../../etc/passwd` 命中它。
- 新增两类，直接对应「会真的写文件」：`DEPENDENCY_OUTPUT`（`-MD/-MF/-MJ/-fdep-file`）、
  `FILE_OUTPUT`（`-save-temps*/`-ftime-trace*/`-fprofile*`/`--serialize-diagnostics`）。
  这两类在 clang 里走 `createOutputFile`/`DependencyFileGenerator`，与 `setOutputStream` **无关**，
  是零写盘环境必须挡住的口子。
- `-o` 与其值、`argv[0]`、输入文件名归 ABSORB（**从不转发**）。原因是一手事实：distcc 客户端
  `dcc_scan_args` 在缺 `-o` 时会**自己补上**（`src/arg.c:277-305`），照票面字面拒绝会让真实客户端
  100% 失败；而危险实质（写 guest 指定路径）在解析阶段就消失了。

### 3.3 资源上限

| 上限 | 值 | 用例 |
| --- | --- | --- |
| `.i` | 32 MiB | `rejects_oversized_requests`（DOTI 超限）、AXP1 累积超限 |
| `.o` | 64 MiB | `maps_oversized_object_...` → `JOB_TOO_LARGE` |
| 诊断 | 64 KiB，UTF-8 边界安全截断 | `truncates_diagnostics_without_splitting_utf8`（在 `é` 中间切开会被挡住） |
| 单 job 墙钟 | 60 s，**可中断** | `cancels_a_job_that_overruns_its_wall_clock`：20 ms 预算、200 ms 引擎，断言总耗时 < 150 ms 且产物被丢弃 |
| 并发 | 1（上限 2，env 可调） | `keeps_four_concurrent_jobs_apart`：4 路真实连接，各自 SERR 携带自己的 marker，互相不含他人 |
| 单连接读超时 | 60 s（握手 3 s） | `answers_a_timeout_with_rst_5_and_never_compiles` |

墙钟从「编译返回后再判」改成看门狗线程置 `JobCancel`：in-process 没有子进程可 kill，
只能协作取消 + **丢弃迟到结果**，这一点写进了代码注释。

超限不再直接断链：先停止累积、继续排空到 EOF，然后照常回
`DONE/STAT=0x6900/SERR=JOB_TOO_LARGE/DOTO(0)`，客户端能看懂；只有持续灌流（超过
`ceiling + max_input`）才 RST，避免被无限流拖死。

### 3.4 host e2e 结果（真跑）

```
$ bash tools/pvm-compiler/test-distcc-inprocess.sh
PVM_CSDL_UP port=10501
PVM_OBJECT_IDENTICAL bytes=608
PVM_ARGV_REJECTED class=CLANG_INTERNAL stat=0x6500
PVM_EQUIVALENCE verdict=EQUIVALENT
PVM_EQUIVALENCE detail=all four levels agree
PVM_DISTCC_E2E_OK cases=2
```

```
$ bash tools/pvm-compiler/test-java-bridge-interop.sh
  PVM_JAVA_BRIDGE_UP port=5488 payload=10504
  PVM_JAVA_BRIDGE_INTEROP_OK cases=2 object_bytes=608 rejected_stat=0x6500
PVM_JAVA_BRIDGE_OK
```

第二个脚本走的是**宿主真实桥接**：`DistccTcpVsockBridge` + `Axp1DistccChannelFactory`
（真 token、真能力协商）→ 真实 `pvm_compiler_payload_host`，只是把 distcc 客户端换成
「把捕获的请求字节写进桥端口」——正是 distcc 会做的事。它不需要 `distcc`，所以在没有
distcc 的机器上也能验证桥接路径。

FakeEngine 的固定对象重新生成为 **608 B**，并实现了 PVM-05 夹具契约（`fixture_entry`），
这样 `compare_objects.py` 能走到 LINK 级给出 EQUIVALENT，而不是卡在 INCONCLUSIVE。

单元/集成测试：`42/42 passed`，ctest 5 组全绿（含 `pvm_e2e`）。
Android 交叉构建（NDK 27，FakeEngine）：`libpvm_compiler_payload.so` **1,721,080 B**，
四个 `PT_LOAD` 对齐均为 `0x4000`，`NEEDED` 只有 `libvm_payload.so/liblog/libm/libdl/libc`，
动态符号表里本项目的符号只剩 `AVmPayload_main`（其余为 libc++ 静态残留）。

### 3.5 ALLOWED_CODES

`tools/accelerator-perf/collect.py` 增加 `PAYLOAD_LOAD_FAILED / ARGV_REJECTED /
JOB_TOO_LARGE / JOB_TIMEOUT`，两版 schema 的 `propertyNames.enum` 同步，
`test_validate.py` 新增 `test_pvm_failure_codes_are_accepted`（逐码构造一份报告要求零缺陷）。
`76 tests OK`。

## 4. PVM-06（not-started）

时间盒在 PVM-03 用尽（PVM-03 的 verify 链路要从零搭：宿主可移植构建、独立客户端、
桥接互操作）。按开工 Prompt §4 不交半成品，本票 **not-started**。

已完成的设计结论（来自 AXP1/distcc/icecream/sccache 的对照，供下一轮直接用）：

1. **容量 0 必须是显式拒绝**，不是挂起：对齐 `iceccd -m 0` 语义，收到 HELLO 后立即回
   `JOB_TIMEOUT` 类拒绝，不要先读 DOTI（省流量，也避免对端写满 socket）。
2. **队列 2×N，满即拒**；反例是 icecream 的 `job_requests` 无上限无拒绝，`make -j` 会打爆 scheduler。
3. **worker 选择用 `inflight/capacity` 比值 + 平局轮询**（icecream `pick_server_least_busy`），
   不要抄它的 `FASTEST` 历史统计——池子只有 1–2 个实例，噪声大于收益。
4. **自回收抄 distcc 的 50 次 / 60 秒**（`src/prefork.c:149-150`），这是 in-process 唯一的防泄漏手段。
5. **崩溃后在飞作业回 `104` 并幂等重投一次**，不要学 icecream `handle_end()` 直接 `erase+delete`。
6. **停止顺序固定**：停 accept → 未开始作业回拒绝码 → 10 s drain → 标记 poisoned →
   最后才释放 libclang 全局（target/asm 注册是进程级一次性状态）。
7. 先行条件：决策地图 §9.2 的 tcc 超时 `TCCState` 泄漏在新后端下不成立（libclang 路径每 job
   新建 `CompilerInstance`），但 §9.1 的 `FL_REQUIRE_NOEXEC=1` 仍未做，建议与 PVM-06 一起收。

## 5. 未验证项与偏差

### 5.1 未验证（不许当已验证读）

| 项 | 原因 | 归谁 |
| --- | --- | --- |
| 真实 `distcc 3.4` 客户端 | 本机无 distcc，`wsl.exe` 被宿主安全策略禁用（Program Blacklist），无法造 Linux 环境 | Linux CI / DV-PVM-02 |
| 真实 libclang 编译 | 宿主无 Android 版 libclang；CLANG 目标未编译，仅对照 22.1.8 头文件核对 API | DV-PVM-02 |
| 产品构建里的 Linux 宿主单测 | FLN1 `transport.cpp` 写死 `<linux/vm_sockets.h>`，Windows 无法编译，故改用 `pvm-service` 可移植入口跑同一批源码 | CI |
| Gradle 单测 | 中文路径下 Gradle 单测的已知问题；Java 侧改用 `javac + JUnitCore` 直跑（7 个新用例 + 17 个既有用例全绿） | CI |

### 5.2 偏差（都在代码注释里写明，需维护者认可）

1. **STAT 数值与计划不同（纠正性偏差）**。计划写 `ARGV_REJECTED→100<<8`、`JOB_TOO_LARGE→101`、
   `JOB_TIMEOUT→102`。但 STAT 是 `wait()` 状态字：`101`/`102` 落在低字节会被读成「被信号 101/102
   杀死」，而 distcc 自己的等价语义是 `EXIT_BAD_ARGUMENTS=101`、`EXIT_OUT_OF_MEMORY=105`、
   `EXIT_TIMEOUT=118`、`EXIT_COMPILER_MISSING=110`（`src/exitcode.h`）。现取
   `0x6500 / 0x6900 / 0x7600 / 0x6e00`，`COMPILE_ERROR` 透传编译器退出码。
   这样做还顺手符合 distcc 客户端 `< 128 = 本次编译失败、>= 128 = 换台机器重试` 的两段式判断。
2. **argv 白名单扩容**。票面只列 `-c/-x/-O/-g/-std/--target`。真实构建一定会带
   `-Wall/-fPIC/-pthread/-march=` 等，严格按票面会让每个真实 job 直接 `ARGV_REJECTED`（硬失败，
   不会 fallback）。现在按「只影响代码生成或诊断」的能力放行：`-W*`、`-O(0..3|s|z|g|fast)`、
   `-g[0-3|gdb|dwarf-N]`、一组固定 `-f*` 字面量、`-m*`（词形校验，`-mllvm` 明确拒绝）、`-pthread`。
   `-D/-I/-include/-isystem/-L/-l/--sysroot/-B/@file/-Xclang/-fplugin` 仍一律拒绝。
3. **`-o` 归 ABSORB 而非 REJECT**（理由见 §3.2，一手来源 `src/arg.c:277-305`）。
4. **测试改用独立可移植入口**。新增 `fastlane-payload/pvm-service/CMakeLists.txt`，与产品构建
   共用 `cmake/pvm-compile-service.cmake` 里的同一份源码与开关，避免两处漂移。
5. **新增 `PvmCompilerBridgeInterop`**（test 源集里的 `main`）：`PvmCompilerPayloadE2eTest` 需要
   `/usr/bin/distcc`，本轮无法跑；这个驱动补上「不需要 distcc 也能验桥接」的缺口。

## 6. 风险

1. **CLANG 引擎仍是无证据代码**。`clang_engine.cpp` 按 22.1.8 头文件重写（`InputKind(..., PP=true)`、
   `CollectingConsumer`、`TextDiagnosticBuffer` 的 `errs()/warnings()` 在 22.x 已被移除），
   但从未编译过。DV-PVM-02 第一件事就该是编它，而不是跑它。
2. **`-fno-exceptions` 的收益依赖 `fastlane_payload_core` 不抛**。payload .so 现在整层
   `-fno-exceptions`（含 main），而它链的 `vm_payload_stub` 之外没有 FLN1 代码，路径是干净的；
   一旦有人把 core 拉进这个 .so，抛异常会直接 `std::terminate`。
3. **握手 nonce 仍非密码学随机**（时钟 + 计数器）。Java 侧从不校验 nonce（`parseHandshake`
   解析后不再使用），凭据是 token；但若后续引入 challenge-response，这里必须先换掉。
4. **中文路径 + MinGW 的组合会误报**：`cc`/`ld` 打不开非 ASCII 路径，`compare_objects.py` 会
   判 DIFFERENT。脚本已把夹具复制到 ASCII 临时目录规避；CI 在 Linux 上不受影响，
   但本地复现时要知道这条。

## 7. 需要维护者决策（3 条）

1. **STAT 码表以 distcc `exitcode.h` 为准**（§5.2 第 1 条）——这会让真机 perf 报告里的失败码
   与 spec §5.3 的字符串码呈两套编号。建议：字符串码作为 perf 报告的 reason code，
   STAT 只做 wire 语义，两者在文档里显式映射一次。
2. **argv 白名单的边界**：是否认可「按能力放行」的扩容（§5.2 第 2 条）？若坚持票面严格表，
   需要在 app 侧把 `-Wall/-fPIC` 之类从 guest 的 argv 里剔掉，否则真实构建会硬失败。
3. **PVM-06 的容量上限**：决策地图 §8.4 写「flag 打开后允许 1，不自动扩张」，PVM-06 票面写
   「N 可配，上限受预算账本约束」。下一轮按哪个执行？建议：`N ∈ {0,1}` 由 flag 控制，
   `N>1` 需要显式预算参数，默认拒绝。

## 8. 给 DV-PVM-02 的交接

| 项 | 值 |
| --- | --- |
| payload 名 | `libpvm_compiler_payload.so`（`OUTPUT_NAME` 同名，与 `fastlane_payload.so` 并列，**不合并**） |
| 入口 | `AVmPayload_main`（`visibility("default")` 是唯一导出符号） |
| 监听 | `AF_VSOCK`，`VMADDR_CID_ANY`，默认端口 **5103**（与 `FastLaneDv01Contract.DEFAULT_DISTCC_PORT` 一致），`ORCHESTELLA_PVM_COMPILER_PORT` 可覆盖 |
| 就绪 | `AVmPayload_notifyPayloadReady()` 在**端口已 bind 且 token 合法之后**调用；token 缺失时返回 1 且不通知 |
| token | `ORCHESTELLA_ACCEL_TOKEN`，32 位小写 hex；不合法直接拒绝服务 |
| triple | 默认 `aarch64-unknown-linux-android`，`ORCHESTELLA_PVM_COMPILER_TRIPLE` 覆盖；**必须与宿主 `requiredCompilerTriple` 逐字节相等**，否则宿主抛 `COMPILER_MISMATCH` |
| 能力 | HELLO_ACK 带 `id1 distcc=1`、`id2 max_frame=min(1 MiB, peer)`、`id3 triple`、`id6 COMPILER_VERSION`（`clang::getClangFullVersion()` / `fake-engine 1`） |
| 并发 | 默认 1 个 job（1 vCPU），`ORCHESTELLA_PVM_COMPILER_JOBS` 最大 2；超出的连接直接关闭（distcc v1 无 in-band busy 包） |
| 启动即会踩的坑 | ① 三个 `.so` 必须与 payload 同目录（无 RUNPATH）；② `-fno-exceptions` 是全层的，别把带 try/catch 的代码塞进来；③ libclang 目标需要 `FL_PVM_COMPILER_LLVM_LIBS/SRC/BUILD` 三个路径，缺一个 CMake 直接 `FATAL_ERROR`；④ 首次 `InitializeAllTarget*` 有一次性开销，别把它算进 job 墙钟 |
| 尚未接线 | `FastLaneLaunchSpec` 尚未加这个 payload 的启动项（归 PVM-08/manifest），DV-PVM-02 需要用探针路径手动起 |
