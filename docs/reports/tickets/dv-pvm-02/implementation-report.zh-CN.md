# 会话 6 报告：DV-PVM-02a / 02b

- 日期：2026-10-25
- 分支：`feat/dv-pvm-02-device-e2e`（自 `main = 6441bd6`）
- 设备：`qa-alt-01`（单机，`adb devices` 恰 1 台 device 态）

## 1. 结论

| 段 | 结果 | 一句话 |
| --- | --- | --- |
| **02a** 产品版 clang 引擎上设备 | **done** | `clang_engine.cpp` 首次编成 arm64 payload；真机上跑通一次真实 distcc job：`STAT=0x0000`、`.o` 91,568 B、`EQUIVALENT` 判据里的链接级与行为级均在目标机闭合、VM stop/delete 干净 |
| **02b** QEMU guest 端到端 | **not-attempted** | 时间盒被 02a 的真机 bring-up 吃尽（7 轮失败循环），且生产 launch 路径未接线（见 §4） |

02a 的核心发现不是「编不过」，而是**编过了也跑不通**：引擎把 driver 参数 `-fno-crash-diagnostics` 塞进了 `-cc1` 参数表，`CompilerInvocation::CreateFromArgs` 对每个 job 都返回失败，payload 把引擎的 `kArgvRejected` 统一映射成 `ARGV_REJECTED:CLANG_INTERNAL`——所以内核从外表看「拒绝一切输入」，与 token、传输、vsock 全无关。这一行只有在真机上跑一次才会暴露。

## 2. 设备资格（只读项，无序列号/型号）

| 项 | 值 |
| --- | --- |
| 固定别名 | `qa-alt-01` |
| API | **37** |
| ABI | arm64-v8a |
| capabilities 原始位 | **1**（protected-only，与前几轮一致） |
| Hypervisor | Gunyah（`crosvm` 日志：`Gunyah { device: "/dev/gunyah" }`） |
| VM 配置 | `setProtectedVm(true)`、`cpu_topology=ONE_CPU`、2 GB、`DEBUG_LEVEL_FULL`、输出捕获 |
| 权限 | `MANAGE_VIRTUAL_MACHINE`、`USE_CUSTOM_VIRTUAL_MACHINE` 均 `pm grant` 成功 |

## 3. 02a

### 3.1 引擎编译（第一步确实是编译）

ASCII 沙箱 + NDK 27.0.12077973 交叉构建。首次编译只有**一个**语法错误，其余 API 用法（`InputKind(..., PP=true)`、`CreateFromArgs`、`setOutputStream` 生命周期）与 22.1.8 头文件一致：

| 轮次 | 结果 | 内容 |
| --- | --- | --- |
| 1 | FAIL | `clang::Language::Cxx` 不存在——LLVM 22 的枚举是大写 `CXX` |
| 2 | OK | 编过，0 warning（`-Wall -Wextra`） |
| 3 | OK | 补 `-fvisibility=hidden -fvisibility-inlines-hidden` 后导出面收敛 |

**产物事实**（`libpvm_compiler_payload.so`，CLANG 引擎）：

| 项 | 值 |
| --- | --- |
| 大小 | 2,788,040 B（strip 前）；APK 内 1,060,032 B |
| SHA-256 | `e2a80ae5410ef804aceb29aa4ef4f0fa9a9f49dc796f758b3e40094bde4d07d9` |
| `PT_LOAD` 对齐 | 4 段全 `0x4000`（16 KB） |
| `NEEDED` | `libvm_payload` / `liblog` / `libclang-cpp` / `libLLVM` / `libm` / `libdl` / `libc`（无 RUNPATH） |
| 动态符号表里的**本项目**符号 | **只有 `AVmPayload_main`**（其余 1373 个是 libc++ 静态残留） |

第三轮才补的 visibility 是评审级缺陷：`pvm_compile_service_clang` 是独立静态库，漏了 `fl_pvm_add_compile_service_library` 已有的那两条标志，于是 `make_clang_engine` 与 `ClangEngine::{compile,compiler_version,~ClangEngine}` 全部泄漏进 `.so` 的动态符号表，直接破坏「payload 唯一 ABI 面是 `AVmPayload_main`」这条不变量。

### 3.2 上设备前的两个前置缺口（都不是引擎问题）

**缺口一：payload 拿不到 token。** `ORCHESTELLA_ACCEL_TOKEN` 在真机上恒为空——Microdroid **没有**宿主到 payload 的环境变量通道（`VirtualMachineConfig` 无 `setEnv`；唯一的运行时配置面 `setPayloadConfigPath` 与 `setPayloadBinaryName` 互斥，且 `Executable` 任务被 `execute_no_trans` 挡住）。而 payload 域零文件写，`ready()` 又要求 token 恰 32 位小写 hex，于是服务在 `AVmPayload_notifyPayloadReady()` 之前就 `exit 1`。

处置：token 解析顺序改为 `env → <apkContents>/assets/accel_token`（后者是握手前唯一存在的宿主→payload 通道）。env 仍然优先，宿主孪生与 shell 启动路径行为不变。**这是产品级设计缺口，不是 QA 权宜**：没有它，保护 VM 里的编译服务永远起不来。

**缺口二：HyperOS 会冻结 QA 进程。** 真机上 VM 起后约 3 s，`PolicyMaker` 打 `freezeUid SUCCESS: uid=… reason=new process`，此后**所有线程停止调度**（`/proc/<pid>/status` 仍是 `S`，只有 cgroup 状态能看出来）。表现极具误导性：桥的 `ServerSocket` 在内核里仍接受连接，但 `accept()` 不再返回，宿主侧只看到一条永远没有响应的 socket——看起来像 payload 卡住。

处置：把应用自己的 launch activity 拉到前台（uid 变 foreground 即可解冻）。时机很关键：太早会在 instrumentation 重启进程时丢掉前台状态，必须在 `payloadReady` 之后。修好后心跳线程从「只写 1 行」变成稳定每 2 s 一行。

### 3.3 宿主侧通道：`adb forward` 在本机不可用

原计划是「宿主 Python AXP1 客户端 → `adb forward` → 应用内生产 `DistccTcpVsockBridge` → `vm.connectVsock` → payload」。实测三件事互相矛盾：

- 设备内 `nc 127.0.0.1 <bridge_port>` 能连上，桥记到 `COMPLETED`、`bytes_in=1`；
- 同一时刻 `adb forward tcp:N tcp:<bridge_port>` 成功建立，主机 `connect()` 立即成功；
- 但桥侧 `connections_completed` 在整个 300 s 里恒为 0，`vsock_connect_ms=null`（工厂从未被调用），`to_guest_bytes=0`。

即：adb server 接受了主机连接，adbd 没有把字节送到应用 socket，且不报错。换固定端口、换随机端口、清空 forward 表都复现。判为**本机 adb forward 的宿主→设备投递缺陷**，不在产品路径上。

处置：02a 改走**文件通道**——请求与响应以应用私有文件承载（`adb push` / `run-as cat`），应用内用**生产 AXP1 客户端** `Axp1DistccChannelFactory` 完成握手与收发。使用真正的 distcc v1 字节流、真正的 AXP1 帧、真正的 payload；唯一变化是宿主与应用之间的字节载体。桥模式仍保留在工具里（`--mode bridge`）并如实标注未打通。

### 3.4 单 job 数字（两轮，各自一个全新 VM）

夹具：2000 个函数 + `fixture_entry()` 的预处理单元，**92,051 B**（DV-PVM-01 已验证的量级）。

| 项 | 第 1 轮 | 第 2 轮 |
| --- | --- | --- |
| `create_ms` | 137 | 77 |
| `payload_ready_ms` | 2,567 | 2,575 |
| `axp1_handshake_ms` | 11 | 10 |
| 收发+编译 `transfer_ms` | 1,067 | 1,055 |
| 请求字节 | 92,186 | 92,186 |
| 响应字节 | 91,628 | 91,628 |
| `.o` 字节 | **91,568** | **91,568** |
| `STAT` | `0x0000` | `0x0000` |
| `SERR` | 空 | 空 |
| `stop_confirmed` / `deleted` | true / true | true / true |
| `payload` 退出码（停之后） | 0 | 0 |

### 3.5 等价判定

`.o` 结构：ELF64 / `REL` / AArch64；2001 个已定义符号（2000 × `probe_fn_N` + `fixture_entry`）；`.text` 16,004 B。

| 级别 | 状态 | 证据 |
| --- | --- | --- |
| 1 LINK | **PASS** | 用 NDK aarch64 driver 与 PVM-05 夹具 `fixtures/c/main.c` 链接成功，产物 96,872 B |
| 2 BEHAVIOR | **PASS（在目标机）** | 链接产物推到设备执行，退出码 **237**，正是 `fixture_entry()` 的期望值（`(0+2+4+6+2000+3998) % 251`） |
| 3 STRUCTURE | **PASS（自比对）** | 两轮独立运行的 `.o` **逐字节相同**（确定性成立） |
| 3/4 对照同版本宿主 clang | **not-run** | 见下 |

同版本对照（clang 22.1.8 宿主）没有拿到：官方 win64 包下载实测 13 KB/s（455 MB 需约 10 h，已放弃）；改走「用 PVM-04 的宿主构建树原地补编 `clang` 目标」，到本轮结束时仍在编译（1 h 40 min，已进入 driver 阶段但未产出 `clang.exe`）。因此 `compare_objects.py` 的 `EQUIVALENT` 判据**没有对成**，`tools/compiler-equivalence` 的四个级别里只有 1、2 与确定性被证据支撑。

为此给 `compare_objects.py` 增加了显式 `--no-run`：跨目标对象在分析宿主上是不可执行的，原实现会在 `run_program()` 处 OSError。跳过时结论行会写明「levels 1, 3, 4 agree; behavior skipped」，绝不静默降级。

## 4. 02b

**not-attempted**，三条理由，任何一条都单独成立：

1. **时间盒**：02a 的真机 bring-up 用了 7 轮设备循环（编译 → token → 冻结 → forward → 引擎 argv → 文件通道），时间盒耗尽。
2. **生产 launch 路径未接线**：`AcceleratorLaneAssembly.assemble()` 至今没有生产调用方（上一轮已记录在案），QEMU 车道与 pVM 车道的**同时**启动在产品代码里不存在。02b 要的「guest 把编译发给 pVM」必须先把这两条车道并起来，那是产品接线，不在本轮授权范围内（「不改 main / 报告写完就停」）。
3. **guest 侧 distcc 未验证**：Alpine 内 `apk add distcc` 需要 guest 有出网，本轮没有验证过 gvproxy 的转发链路。

按开工 Prompt §3，未用宿主侧客户端冒充交叉加速。

## 5. 未验证项与偏差

### 5.1 未验证（不许当已验证读）

| 项 | 原因 | 归谁 |
| --- | --- | --- |
| 同版本宿主 clang 的 `.o` 对照 | 下载不可行；宿主 `clang` 目标补编未在本轮完成 | 下一轮（构建树已就位，增量续编即可） |
| 生产桥接路径（`adb forward` → 应用内 bridge） | 本机 adb forward 不投递，见 §3.3 | DV-PVM-02 续做，或换一台宿主 |
| `ru_maxrss` | payload 不输出、guest 无 console（`DebugPolicy{log:false}`）、宿主看不到 pVM 内部进程 | PVM-06（诊断面） |
| 真实 `distcc 3.4` 客户端 | 同上：需要能到达 payload 的 TCP 路径 | DV-PVM-02 续做 |
| 多于 1 个 job / 并发 | 文件通道每轮一个 job；并发归 PVM-06 | PVM-06 |

### 5.2 偏差

1. **02a 的宿主客户端位置**：Python AXP1 客户端改为应用内生产 AXP1 客户端（`Axp1DistccChannelFactory`），请求/响应走文件。原因见 §3.3。
2. **token 供给渠道**：产品代码新增 `assets/accel_token` 兜底（env 优先）。属设计缺口修补，需维护者认可渠道形态。
3. **`compare_objects.py` 新增 `--no-run`**：跨目标对象必需，语义是「跳过行为级并写明」。
4. **`axp1_distcc_client.py` 新增 `--doti-file`**：85 KB 的 `.i` 放不进 Windows argv。
5. **QA 夹具尺寸**：2000 函数 / 92 KB，沿用 DV-PVM-01 口径，未用 1 MB。

## 6. 风险

- **`ARVG_REJECTED:CLANG_INTERNAL` 的失败码语义被复用**：引擎层 `kArgvRejected` 与策略层拒绝共用一个字符串，本轮就是被它误导了近两小时。建议拆成 `ENGINE_REJECTED:<detail>`，否则以后任何引擎侧参数问题都会伪装成「argv 不合规」。
- **MIUI/HyperOS 冻结**：这是真机 QA 的常态风险，且症状与「服务卡死」完全同形。所有长驻 payload 的 QA 驱动都必须显式前台化，否则会反复误判。
- **`adb forward` 不可投递**：如果产品最终依赖宿主 TCP → 应用 loopback 这条路（QEMU guest → 宿主桥 → pVM），那么本机现象说明这条链在设备侧还有一层没验证过。02b 必须先单独验证它。
- **token 走 APK asset**：静态秘密随包分发，保护强度弱于逐次协商。若后续引入 challenge-response，这条要一并换掉。
- **宿主 `clang` 补编占用**：PVM-04 的宿主构建树被本轮改动过（新增 clang 目标产物），下一轮续编前需确认 `build/host` 仍可用。

## 7. 需要维护者决策（≤3）

1. **token 供给渠道定型**：接受「env 优先 + APK `assets/accel_token` 兜底」，还是要求平台侧给出真正的宿主→payload 通道（例如 authfs / 加密存储预置）？前者可立即产品化，后者更干净但依赖平台。
2. **`ARGV_REJECTED:CLANG_INTERNAL` 是否拆码**：引擎层拒绝与策略层拒绝共用字符串，本轮已实际造成误判。建议新增 `ENGINE_REJECTED` 并保留旧码一个版本做兼容。
3. **02b 的前置顺序**：先接 `AcceleratorLaneAssembly` 的生产调用（让 QEMU + pVM 同时起），还是先单独验证「guest → 宿主 loopback → 应用 bridge」这条 TCP 链？建议后者——它是 02b 唯一未验证的物理段，且可以零产品改动地测。

## 8. 给产品化的交接（还缺哪几根线）

1. **token 通道**：`assets/accel_token` 现在靠 QA 构建注入，产品需要一个正经的供给方式（决策 1）。
2. **`AcceleratorLaneAssembly` 的生产调用方**：`assemble()` 仍只被测试调用，加速车道的 `LaneStarter` 没有接线。这是「用户真的用上」的第一道门。
3. **QA 前台化写进驱动**：任何拉起 pVM 的自动化都必须在前台跑，否则结果不可信。
4. **`engines` 的失败码分工**：引擎侧拒绝需要独立码，否则真机排障成本按小时计。
5. **同版本宿主对照**：`build/host` 补编完成后，`compare_objects.py` 的四级判据才算齐；本轮只闭合了 1/2 级与确定性。
