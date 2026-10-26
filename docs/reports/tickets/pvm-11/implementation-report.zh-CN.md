# PVM-11 实现报告 · 载荷 argv 接受两段式 `-target <triple>`

- 票：`PVM-11`（`.scratch/orchestella-pvm-compiler/issues/`）
- 基线：`main = 104f79a`（PR #32 / #33 已合并）
- 分支：`feat/pvm-11-clang-target`
- 日期：2026-10-27（会话 12A）
- 设备：**全程未使用**，`adb` 一次未调

## 0. 摘要

上一轮（PVM-10）把 60 秒地板拆掉之后，真实 distcc 流量第一次走到了 argv 白名单面前，
然后被拒：`STAT=0x6500` + 27 字节 SERR。本轮把这个拒绝在**宿主上复现出来**，确认它
就是 `-target aarch64-alpine-linux-musl` 这两段式参数造成的，然后把四种 target 写法
一起补上。

一句话：**拦在「远端第一次真正编译」前面的不是超时，是一个 7 字节的短选项。**

## 1. 结论

**`-target` 就是根因，宿主复现到字节级；三条安全断言全绿。**

新用例 `distcc_server_accepts_the_argv_a_real_distcc_client_sends` 用设备侧倒推出的
9 参 argv 走完整 distcc 编解码，改之前拿到的就是设备上的那两个数：

- `response.stat = 25856` = `0x6500`（distcc exit 101 `EXIT_BAD_ARGUMENTS`）
- `response.serr = "ARGV_REJECTED:UNKNOWN_FLAG\n"`，13 + 12 + 1 + 1 = **27 字节**

27 字节加上 5 个 12 字节令牌头 = **87 字节**，与设备侧 `bytes_out=87` 完全相等。
**宿主孪生从「一直绿」变成了「能复现设备的红」**，这比修好本身更有价值。

改之后同一组字节得到 `stat=0`、`serr` 空、`DOTO` 4 字节、引擎被调用 1 次，
转发给引擎的 flag 只剩 `-c` 和 `-O1`（两个 target 声明都被策略吸收，不转发）。

| # | 安全断言 | 阈值 | 结果 |
| --- | --- | --- | --- |
| A1 | 非 aarch64 triple 仍被拒 | `x86_64-*` / `armv7a-*` / `riscv64-*` / `aarch64x-*` 全部 `TARGET_MISMATCH` | **绿** |
| A2 | `-march=native` 仍被拒 | 三个 `native` 写法全部 `UNKNOWN_FLAG`，具体值仍放行 | **绿** |
| A3 | 路径型值仍被拒 | `-target aarch64-../../etc/passwd` → `PATH_LIKE`；空值/缺值 → `BAD_TARGET` | **绿** |

全套：**52/52 用例通过**（改前 48 条，本轮新增 4 条），`ctest` 5/5。

## 2. 仓库状态

- `main = 104f79a`，分支 `feat/pvm-11-clang-target`（从 `main` 切）
- 工作区另有 5 个 `.scratch/` 改动（会话 10/11 遗留），**本轮未提交、未触碰**
- `git diff --name-only`（本轮）：

```
fastlane-payload/src/compile_service/argv_policy.cpp
fastlane-payload/src/compile_service/argv_policy.h
fastlane-payload/src/compile_service/distcc_server.cpp
fastlane-payload/tests/test_argv_policy.cpp
fastlane-payload/tests/test_distcc_server.cpp
docs/reports/tickets/pvm-11/implementation-report.zh-CN.md
```

## 3. 改动清单

| 文件 | 性质 |
| --- | --- |
| `fastlane-payload/src/compile_service/argv_policy.h` | **产品行为改动**：`ArgvDecision` 增 `declared_target`（advisory 记录）；`ArgvPolicyConfig::target_triple` 注释改为「按架构校验、引擎始终按此出码」 |
| `fastlane-payload/src/compile_service/argv_policy.cpp` | **产品行为改动**：target 分支接受四种写法；triple 判据从「逐字等于配置」改为「必须是 `aarch64` / `aarch64-*`」，不一致时记入 `declared_target`；新增 `is_native_machine_flag()` 拒绝 `-march/-mtune/-mcpu=native` |
| `fastlane-payload/src/compile_service/distcc_server.cpp` | **仅观测**：接受作业后若 `declared_target` 非空，向 stderr 打一行 `PVM_CSDL target_advisory declared=… configured=…` |
| `fastlane-payload/tests/test_distcc_server.cpp` | 仅测试：真实 9 参 distcc argv 的 golden（含 110 字节 argv 体、`stat=0`、flag 只留两项） |
| `fastlane-payload/tests/test_argv_policy.cpp` | 仅测试：四种写法矩阵、advisory 记录、架构判据、新写法下的旧守卫；`native` 族拒绝；原 `target_must_match_the_configured_triple` 改名为 `target_accepts_the_configured_triple_and_refuses_x86`（断言未放宽） |
| `docs/reports/tickets/pvm-11/implementation-report.zh-CN.md` | 文档：本报告 |

**产品行为改动只有两个文件**（`argv_policy.{h,cpp}`），且都在同一个函数里；
`distcc_server.cpp` 只多一行日志。没有动 `contains_path_separator`、`is_file_output`、
`is_dependency_output`、`kValuePrefixes`、`kAllowedCodegen` 任何一处。

## 4. 证据（红 → 绿）

构建入口用仓库自带的便携验证工程（本机是 Windows，产品构建的 FLN1 传输含
`<linux/vm_sockets.h>` 编不了）：

```
cmake -S fastlane-payload/pvm-service -B build/pvm-service -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/pvm-service -j
ctest --test-dir build/pvm-service --output-on-failure
```

### 4.1 红（改前）

```
FAIL distcc_server_accepts_the_argv_a_real_distcc_client_sends:
  test_distcc_server.cpp:430: CHECK_EQ failed: response.serr == std::string()
  (actual=ARGV_REJECTED:UNKNOWN_FLAG
  , expected=)
0/1 passed
```

把断言顺序调成先查 `stat` 时，同一条用例报 `actual=25856, expected=0`。
`25856 = 0x6500`，设备侧 `STAT` 一字不差。

### 4.2 另一片红：joined 短写法与空值

补完两段式后，剩下两种写法还没接上，红得同样明确：

```
FAIL argv_policy_accepts_all_four_target_spellings:
  test_argv_policy.cpp:142: spelling 3 rejected as ARGV_REJECTED:UNKNOWN_FLAG
FAIL argv_policy_target_guards_survive_the_new_spellings:
  test_argv_policy.cpp:200: CHECK failed: rejected_with({"...", "-target="}, "BAD_TARGET")
```

`spelling 3` 就是 `-target=<triple>`；`-target=` 当时落到 `UNKNOWN_FLAG`，
接上之后按 `BAD_TARGET` 拒绝。

### 4.3 第三片红：`-march=native` 当时是**放行**的

```
FAIL argv_policy_refuses_native_machine_tuning:
  test_argv_policy.cpp:216: -march=native was not refused: accepted
```

这条是 T3 顺手补齐的同族缺口：`-m` 前缀家族只看「值是否 word-shaped」，
`native` 完全合格，于是被原样转发给引擎——驱动会去探测它实际跑在哪颗核上，
出码随之悄悄改变。同一族的 `-mcpu=native` 一并拒了。

### 4.4 绿（改后）

```
PASS distcc_server_accepts_the_argv_a_real_distcc_client_sends
1/1 passed
PVM_CSDL target_advisory declared=aarch64-alpine-linux-musl configured=aarch64-unknown-linux-android
```

那行 `target_advisory` 是本轮唯一新增的可观测输出：客户端声明了
`aarch64-alpine-linux-musl`，载荷按 `aarch64-unknown-linux-android` 出码。
**不一致没有消失，只是从「静默丢弃」变成了「有记录」。**

三条安全断言单独跑：

```
PASS argv_policy_target_architecture_must_be_aarch64        1/1 passed
PASS argv_policy_refuses_native_machine_tuning              1/1 passed
PASS argv_policy_target_guards_survive_the_new_spellings    1/1 passed
```

全套：

```
100% tests passed, 0 tests failed out of 5
52/52 passed
```

### 4.5 字节对账（可复核）

新用例把 argv 体**硬断言**在 110 字节上，并断言整帧长度 = `12 + 12 + 9×12 + 110 + 12 + |DOTI|`：

```
12 (DIST) + 12 (ARGC) + 9×12 (ARGV 头) + 110 (ARGV 体) + 12 (DOTI 头) + 2418 (.i) = 2672
```

与设备侧 `bytes_in = 2672` 相等。9 个 argv 里 `-target`(7) + `aarch64-alpine-linux-musl`(25)
加两个帧头正好 **56** 字节，正是 `argv_policy` 报错前那笔算不平的缺口。

## 5. 未验证项与偏差

- **真机未复验**。本轮按 Prompt §5 全程零设备，验收全部在宿主。设备回来后的三条判据见 §8。
- **sanitizer 门本机跑不了**：MSYS2 mingw g++ 14.2 不带 libasan，
  `FL_SANITIZE=ON` 只能由 CI（Linux）跑。本机 `pvm-service` 工程本来也没有该选项。
- **`tools/ci/run-fastlane-zero-device.sh` 的 `cpp` / `jvm` / `python` / `e2e` 四步在本机均不可用**，
  全是这台机器既有的限制，与本轮改动无关：
  - `cpp`：产品构建先卡在 TinyCC 的 `FetchContent_Populate`（取不到 tarball），
    即使取到也会因 `<linux/vm_sockets.h>` 编不了 → **替代执行**：`pvm-service` 便携工程，
    与产品构建共用 `cmake/pvm-compile-service.cmake` 的源码与编译开关，结果即 §4。
  - `jvm`：javac 兜底路径有三个各自独立的既有缺陷 —— ① 不给 `-encoding UTF-8`，
    中文注释在 zh-CN Windows 默认 GBK 下直接报错；② classpath 用 POSIX 的 `:` 拼接，
    Windows 的 javac 要 `;` 且认不了盘符混排；③ `MODULES` 列表少 `runtime/contract`，
    `fastlane-distcc` 主源引 `NetworkContract` 必然找不到。三者任一都会让这一步中断。
    **替代验证**：改了哪些 Java 侧能观测到的东西——没有。全仓 grep
    `ARGV_REJECTED|0x6500|target_triple|declared_target`，唯一相关断言是
    `PvmCompilerBridgeInterop` 的 `-Xclang -load` 用例（仍是 `CLANG_INTERNAL` / `0x6500`），
    不受本轮影响；distcc 线格式（令牌集合、`STAT` 取值）一个字节没变。
  - `python`：`tools/fastlane-build` 依赖 PyYAML，本机装不上（PyPI 不可达），
    venv 又是 Windows 布局（`Scripts/python.exe` vs 脚本找的 `bin/python3`）。
    另两个套件可以跑：`qemu-tuning` 11 条 OK、`compiler-equivalence` 13 条 OK（7 skip）。
  - `e2e`：需要 `build/fastlane-payload/fastlane_payload_host`，`cpp` 没成产物就不存在。
    等价覆盖由 `pvm_compile_service_pvm_e2e`（AXP1 + distcc 全链路、真编解码）提供，已绿。
- **`-mcpu=native` 超出了 Prompt 字面**：Prompt 只点了 `-march` / `-mtune`，但三者同属
  一次 CPU 探测、同一个漏法，一并拒了；见 §7 决策 2 供维护者否决。
- 开工 Prompt §3 的 `dcc_add_clang_target()`（distcc `src/compile.c:549`）为上一轮结论，
  本轮未重新归因；本轮独立验证的是字节账（§4.5）与拒绝点（§4.1），两者自洽。

## 6. 风险：triple 语义判断错会长什么样

本轮唯一的语义决定是「**接受任意 `aarch64-*`，引擎仍按配置 triple 出码**」。判错的三种长相：

- **判宽了（把非 aarch64 也放行）**：`x86_64-*` 需求会被接受，然后拿 aarch64 的对象回去。
  客户端链 `aarch64-unknown-linux-android` 时，症状是**链接期**才炸的
  `Relocations in generic ELF (EM: 183)` 一类错误，而不是编译期 —— 排查方向会被误导到链接器。
  护栏：A1，四个非 aarch64 triple 必须 `TARGET_MISMATCH`。
- **判窄了（仍要逐字等于配置值）**：等于什么都没修，`-target aarch64-alpine-linux-musl`
  继续 `UNKNOWN_FLAG`，distcc 继续 `running locally instead`，而 `rc=0`、`.o` 在。
  **这是最危险的一种**：客户端看起来一切正常，只是从来没走远端。
  护栏：§4.1 那条 golden 直接断言 `stat=0` 且 `SERR` 为空；
  `distcc_server_maps_argv_rejection_to_a_fixed_stat` 仍钉着 `0x6500` 的映射关系。
- **`native` 漏放**：`-march=native` 一旦被转发，出码取决于载荷**当时**探到的 CPU。
  同一份 `.i` 在两次交火里得到不同对象，而 `STAT=0`、`DOTO` 完整 —— **没有任何信号**。
  护栏：A2。

另有一处**已知的、本轮不修**的语义边界：客户端声明 `aarch64-alpine-linux-musl` 时，
拿到的是 android triple 的对象。对「只要 `.o`」的 distcc 用途无害，对需要 glibc/musl
ABI 精确匹配的场景有害。记录在 `declared_target` 里，但**没有任何 wire 通道能把它送回客户端**
（distcc 只有 `DONE/STAT/SERR/SOUT/DOTO` 五个令牌，`SERR` 是客户端的告警通道，不能塞）。

## 7. 需维护者决策

1. **`declared_target` 要不要真的回传**。现在的落点是载荷 stderr 一行日志
   （`PVM_CSDL target_advisory`）。设备上 guest 的控制台不可达（`vm info` 的
   `Debug policy: adb:false`），所以这行日志在真机上**看不到**，实际可观测的只有宿主孪生与 CI。
   三条路：(a) 只留日志，接受「设备上不可观测」；(b) 在 `SERR` 里追加一行
   `note: compiled for <configured>, not <declared>` —— 会污染每一次作业的诊断输出，
   也会打破 `SERR` 为空这条既有断言；(c) 等 AXP1 侧扩一个 capability 回执字段。
   本轮按「不动 wire」选了 (a)。
2. **`-mcpu=native` 是否保留在拒绝名单里**。留在里面则比 Prompt 多拒一个写法；
   去掉则留一个和 `-march=native` 完全同形的洞。
3. **`AVX`/`+crypto` 这类扩展串要不要收窄**：`-march=armv8-a+crypto` 现在照样放行
   （A2 明确断言它放行）。它和 `-march=native` 的区别是「确定性的」，所以本轮不动，
   但如果后续发现引擎的 triple 与扩展串冲突，这里是下一个收紧点。

## 8. 交接：设备回来后第一动作

一次 crossfire，三条验收（与 Prompt §9 一致，一字未改）：

1. `bytes_out > 0` 且响应里 `DOTO` **长度大于 0**（不是「有 DOTO 令牌」——PVM-10 就是栽在
   这个区别上：87 字节回包里有 `DOTO` 头，长度却是 0）；
2. `distcc_ms − 同夹具本地编译` 落到**秒级**，且这一轮不许再用「远端被拒 → 本地重编」的差值冒充；
3. `EXIT_TRUNCATED` 不出现，distcc 日志里不能有 `running locally instead`。

预期：`STAT=0`、`DOTO` 非空、`SERR` 为空或只有 warning。若 `STAT` 仍非 0，
**先看 SERR 的类名再动手**——本轮之后 `UNKNOWN_FLAG` 不应再出现于真实 distcc argv，
再出现说明还有第二个注入参数（候选：`-Qunused-arguments`、`-fdebug-compilation-dir`）。

## 9. 声明

- 未碰设备：`adb` 一次未调，本轮零设备。
- 未改 `max_jobs`（仍是 1）、未改 `ONE_CPU`、未改任何默认超时、未开 PVM-06。
- 未改 `contains_path_separator`、`is_file_output`、`is_dependency_output`、
  `kValuePrefixes`、`kAllowedCodegen`；未把 `filter_argv` 改成「未知 flag 一律放行」。
- 未放宽既有断言。一处**改名**（`argv_policy_target_must_match_the_configured_triple`
  → `..._accepts_the_configured_triple_and_refuses_x86`），因为旧名字断言的是
  「必须逐字等于配置值」，而这正是本轮要推翻的契约；用例体内的三条断言原样保留。
- 未 `git add -A`、未直推 `main`、未合并自己的 PR。
- 敏感门：提交前跑 `--self-test` 与 `--range main..HEAD`，结果见 PR 描述。
