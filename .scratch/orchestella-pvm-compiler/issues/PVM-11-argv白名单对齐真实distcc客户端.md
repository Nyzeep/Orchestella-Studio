# PVM-11 · argv 白名单对齐真实 distcc 客户端

- Type: implementation（C++，载荷侧，零设备）
- Status: **implemented（零设备证据齐；真机复验 not-tested）**
- Priority: P0（argv 白名单是「远端第一次真正编译」前面的最后一道门）
- Parent: `../决策地图.md`
- Zero-device: 是（宿主孪生 + 字节级对账；真机复验判据见 §真机复验入口，not-tested）
- 分支：`feat/pvm-11-clang-target`（commit `753a3ff` + `2936a6c`）
- 报告：`docs/reports/tickets/pvm-11/implementation-report.zh-CN.md`
- 基线：`main = 104f79a`；前置：PVM-10（60 s 地板拆除）已真机验证

## 一句话

PVM-10 拆掉 60 s 帧超时地板之后，真实 distcc 流量第一次走到了 argv 白名单面前，然后被拒：
设备侧 `STAT=0x6500`（distcc exit 101 `EXIT_BAD_ARGUMENTS`）+ 27 字节 SERR。根因不是
`-I` 也不是超时，是 clang 客户端自动追加的两段式短选项 `-target aarch64-alpine-linux-musl`
——白名单只认连接写法时，两段式落到 `UNKNOWN_FLAG`（类名 12 字符，正好凑成 27 字节）。
修法：白名单接受四种 target 写法并按架构（`aarch64`/`aarch64-*`）而非逐字 triple 校验，
不一致时记入 `declared_target` advisory；同时拒绝 `-march/-mtune/-mcpu=native`（CPU 探测
会让出码随载荷所在核漂移）；并按输入文件名后缀推断语言，C++ 作业不再被当 C 编。

## 改动面

| 位置 | 改动 |
| --- | --- |
| `argv_policy.cpp` / `argv_policy.h` | **产品行为改动**：target 四写法（`--target <t>` / `-target <t>` / `--target=<t>` / `-target=<t>`）；判据改「必须是 `aarch64` / `aarch64-*`」，不等于配置 triple 时接受但记 `declared_target`；`is_native_machine_flag()` 拒绝 `-march/-mtune/-mcpu=native`；无 `-x` 时按输入名后缀（`.c/.i→c`、`.C/.cpp/.cc/.cxx/.c++/.ii→c++`）推断语言 |
| `distcc_server.cpp` | **仅观测**：`declared_target` 非空时向 stderr 打 `PVM_CSDL target_advisory declared=… configured=…` |
| `test_distcc_server.cpp` / `test_argv_policy.cpp` | 仅测试：真实 9 参 argv 金标（110 字节 argv 体、整帧 2672 字节对账）、四写法矩阵、native 族拒绝、架构判据、旧守卫在新写法下存活 |

## 判据表（宿主零设备，红 → 绿）

| 观测 | 修前 | 修后 |
| --- | --- | --- |
| `distcc_server_accepts_the_argv_a_real_distcc_client_sends` | `response.stat = 25856 = 0x6500`、`serr = "ARGV_REJECTED:UNKNOWN_FLAG\n"` | `stat=0`、`serr` 空、`DOTO` 4 字节、引擎调用 1 次 |
| 字节对账 | 27 B SERR + 5×12 B 令牌头 = **87 B** = 设备侧 `bytes_out` | 转发 flag 只剩 `-c`、`-O1`（两个 target 声明被吸收） |
| argv 体 | 9 参 110 字节 = 设备侧 `bytes_in=2672` 的缺口（`-target`7 + triple25 + 两帧头 = 56 B） | 整帧断言 `12+12+9×12+110+12+2418 = 2672` 通过 |
| A1 非 aarch64 triple | — | `x86_64-*`/`armv7a-*`/`riscv64-*`/`aarch64x-*` 全部 `TARGET_MISMATCH` |
| A2 native 调优 | `-march=native` 被放行（原洞） | 三种 native 写法全部 `UNKNOWN_FLAG`，确定性值（如 `armv8-a+crypto`）仍放行 |
| A3 路径守卫 | — | `-target aarch64-../../etc/passwd` → `PATH_LIKE`；空值/缺值 → `BAD_TARGET` |

全套 **52/52 用例通过**（`pvm_compile_service_tests`，改前 46 条，本轮新增 6 条），
`pvm-service` 便携工程 `ctest` 5/5。

## 回路

```bash
cmake -S fastlane-payload/pvm-service -B build/pvm-service -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/pvm-service -j
ctest --test-dir build/pvm-service --output-on-failure
```

- 纯 C++、无设备；Windows 可跑（产品构建的 FLN1 传输含 `<linux/vm_sockets.h>`，编不了，
  便携工程与产品构建共用 `cmake/pvm-compile-service.cmake` 的源码与开关）。
- 真实客户端 E2E（Linux/WSL）：`/usr/bin/distcc` + `/usr/bin/cc` + `FL_PVM_COMPILER_HOST_BIN`
  指向 `pvm_compiler_payload_host` 后
  `./gradlew :runtime:fastlane-distcc:test --tests '*PvmCompilerPayloadE2e*' --console=plain`。
- Windows 侧 Gradle 必须在 ASCII 盘符（`subst X:` → 仓库根）下跑，中文路径下 test worker
  会 `ClassNotFoundException`。

## 为什么不是 INCLUDE_PATH（上游事实纠错）

PVM-10 票面曾把 27 字节 SERR 的 12 字符类名框定为 `UNKNOWN_FLAG` / `INCLUDE_PATH` 二选一，
且早期推断认为「真实 distcc 客户端会把 `-I` 带过线」。**这个推断是错的**：distcc v1
（cpp-on-client 默认路径）在**客户端本地**完成预处理并 strip 掉 `-I/-D/-U/-L/-l/-MF/-MT/
-MQ/-include/-imacros/-isystem/-MD/-MMD/-Wp,` 等（上游 `src/strip.c:65-139`，master
`8d569d19`），wire 上 argv 只剩「编译器名 + 前后端通用 flag + 源文件名 + `-o 输出`」，
**永远不含 `-I`**。因此 `INCLUDE_PATH` 拒因对真实 v1 客户端**不可达**，只会命中合成请求
或非 v1（pump）流量；实况就是 `UNKNOWN_FLAG`（`-target` 两段式），设备侧 87 字节回包与
宿主复现逐字节对上了。

## Comments

- **① 修复与测试证据（2026-10-27，会话 12A）**：commit `753a3ff`（四种 target 写法 +
  `-march/-mtune/-mcpu=native` 拒绝 + `declared_target` advisory）与 `2936a6c`（按输入
  文件名后缀推断语言，C++ 作业不再被当 C 编）。金标用例
  `distcc_server_accepts_the_argv_a_real_distcc_client_sends`
  （`fastlane-payload/tests/test_distcc_server.cpp`）改前红在
  `stat=25856 / serr=ARGV_REJECTED:UNKNOWN_FLAG`、改后绿 `stat=0 / serr 空`；
  三条安全断言（`argv_policy_target_architecture_must_be_aarch64`、
  `argv_policy_refuses_native_machine_tuning`、`argv_policy_target_guards_survive_the_new_spellings`）
  单独跑全绿。全套 52/52、ctest 5/5，逐条输出见报告
  `docs/reports/tickets/pvm-11/implementation-report.zh-CN.md` §4。
- **② 上游事实纠错（strip.c）**：修正本票早期「真实客户端会带 `-I` 过线」的错误推断。
  distcc v1 客户端本地 strip `-I/-D/-U/-L/-l/-MF/-MT/-MQ/-include/-imacros/-isystem/
  -MD/-MMD/-Wp,`（上游 `src/strip.c:65-139`，master `8d569d192141615e26a3f0b65315822e7c814c3d`；
  客户端侧 cpp 见 `src/cpp.c:54-101`），DOTI 线上 argv 不含 `-I` ⇒ `INCLUDE_PATH`
  （`argv_policy.cpp:238-243`）拒因只对合成/非 v1 流量可达。这是白名单与真实客户端之间
  已了结的一处不对齐：`.i` 已在客户端展开，载荷域零文件读写（DV-PVM-01：全部目录 EACCES），
  继续拒 `-I` 是安全上正确的收敛。若未来接 pump（协议 3），请求里会先出现 `CDIR` 与
  `NFIL/NAME/FILE` 段——那是新的票，不是本票白名单的锅。
- **③ 真机复验入口（not-tested）**：设备回来后按报告 §8 一次 crossfire 三条判据——
  1) `bytes_out > 0` 且 `DOTO` 长度大于 0（不是「有 DOTO 令牌」）；2) `distcc_ms − 本地
  编译` 落到秒级；3) 无 `EXIT_TRUNCATED`、distcc 日志无 `running locally instead`。
  预期 `STAT=0`、`SERR` 空或仅 warning；若 `STAT` 仍非 0 先看 SERR 类名再动手
  （候选第二注入参数：`-Qunused-arguments`、`-fdebug-compilation-dir`）。交火方法沿用
  DV-PVM-02（`.scratch/orchestella-pvm-compiler/issues/DV-PVM-02-真机端到端.md`）的
  02b 路径：guest 内 `DISTCC_HOSTS=<桥地址> distcc clang …`。
- **④ 零设备真实客户端 E2E（2026-11-04，本窗口）**：**过了**。真实 distcc 3.4 客户端
  （WSL `/usr/bin/distcc`，apt 3.4+really3.4-4build3；另自源构建同版产物
  `/root/distcc-build/distcc-3.4/distcc`——codeload v3.4 tag + autogen + configure +
  `make distcc`，GitHub releases 主路线被网络阻断，见证据笔记 A3 一节）→ 真
  `DistccTcpVsockBridge` + `Axp1DistccChannelFactory` → 真 C++ host 孪生
  `pvm_compiler_payload_host`（Linux 版，FakeEngine）整链跑通：
  `./gradlew :runtime:fastlane-distcc:test --tests '*PvmCompilerPayloadE2e*' --offline
  --configure-on-demand` **BUILD SUCCESSFUL**，
  `realDistccClientCompilesThroughThePayloadServer` **1/1 执行、0 跳过、0 失败**，
  载荷 `PVM_CSDL job done code=0`（STAT=0），远端 `.o` 恰 608 B（本地回退不可能凑巧
  同尺寸）、符号集相等、链接运行打印 42。证据：
  `.scratch/orchestella-pvm-compiler/round-pvm11/notes/E1-e2e-evidence.md`。
  边界：FakeEngine——本 E2E 证明协议 + argv 全链接受，**非真 clang 产物等价**；
  真机复验仍 not-tested（入口见 Comments ③）。
