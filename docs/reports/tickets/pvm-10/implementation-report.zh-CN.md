# PVM-10 实现报告 · 请求结束信号从 TCP 半关闭换成 DOTI 自定界

- 票：`PVM-10`（`.scratch/orchestella-pvm-compiler/issues/PVM-10-请求结束信号.md`）
- 基线：`main = 722bf8e`（PR #32 已合并）
- 分支：`feat/pvm-10-doti-termination`
- 日期：2026-10-26（会话 11）

## 0. 摘要

归因（DV-PVM-03）已把 60 秒钉在载荷侧帧超时上：桥把「请求结束」定义为 TCP 半关闭，而真实
distcc 客户端用 `DOTI<8 位十六进制长度>` 自定界、从不 `shutdown(SHUT_WR)`。本轮把判据换成
distcc 协议自定界——载荷一收满 `DOTI` 体就开始编译，桥保持完全不解析 distcc 字节。

一句话：**挡在 1.05 s 与 25.6 s 之间的不是性能，是一个字节数。**

## 1. 结论

**机制修好了，验收没过。**

60 秒地板消失了：桥上的连接从「60563 ms、`bytes_out=0`、`TIMEOUT`」变成
「476 ms、`bytes_out=87`、`COMPLETED`」，门 3483 ms 通过。载荷在收到完整请求后
**7 毫秒级**就开始回答。

但客户端拿到的不是对象：载荷以 `STAT=0x6500`（distcc exit 101 = `EXIT_BAD_ARGUMENTS`）
拒绝了这次作业，distcc 于是本地补编，`rc=0`、`.o` 在。**这不是 PVM-10 的回归**——argv
策略一直如此，只是此前请求永远走不到那条代码。

| # | 判据 | 阈值 | 结果 |
| --- | --- | --- | --- |
| A | `bytes_out > 0` 且响应含 `DOTO` | 客户端真的拿到对象 | **形过实不过**：`bytes_out=87`，响应里有 `DOTO` 令牌但长度为 0，`STAT=0x6500` |
| B | `distcc_ms − 同夹具本地编译` | < 5 s | **不过**：`distcc_ms=22544`，同一轮 `local_compile_ms=31142`；差值来自「远端被拒 → 本地重编」，不构成加速证据 |
| C | `EXIT_TRUNCATED` 不出现、`@Ignore` 用例转绿 | 远端路径真的被走到 | **不过**：JVM 侧用例已绿，但设备上 distcc 打印 `failed with exit code 101` + `running locally instead` |

| 轮次 | `distcc_ms` | 同夹具本地编译 | 桥 `duration_ms` | `bytes_out` |
| --- | --- | --- | --- | --- |
| 修前（同日 21:09，50 函数夹具 2418 B `.i`） | — | — | **60 563** | **0** |
| 修后（2000 函数夹具 90 126 B `.i`） | 22 544 | 31 142 | **476** | **87** |
| 修后复跑（同夹具） | 31 981 | 31 188 | 770 | 87 |

修前修后的夹具不同（旧证据是 50 函数那次），所以**不能**把 60.5 s 与 22.5 s 直接相减；
能直接比的是桥上的两个数：`duration_ms` 60563 → 476/770，`bytes_out` 0 → 87。

**下一个阻塞点**（不属于本票）：载荷对真实 distcc 的 argv 报了
`ARGV_REJECTED:<class>`，SERR 长 27 字节，只可能是 `UNKNOWN_FLAG` 或 `INCLUDE_PATH`
两个 12 字符类名之一；我们的命令行里没有任何 `-I`，所以是 `UNKNOWN_FLAG`——某个
distcc 自己加进 argv 的参数没进白名单。这是 PVM-10 修好之后才**变得可见**的缺陷。

## 2. 仓库状态

- `main = 722bf8e`，分支 `feat/pvm-10-doti-termination`
- 工作区另有 5 个 `.scratch/` 改动，属会话 10 遗留，**本轮未提交、未触碰**
- `git diff --name-only`：见 §3

## 3. 改动清单

| 文件 | 性质 |
| --- | --- |
| `fastlane-payload/src/compile_service/distcc_server.h` | **产品行为改动**：`DistccParseStatus` 增 `kIncomplete`，`DistccParseResult` 增 `prefix_recognised` |
| `fastlane-payload/src/compile_service/distcc_server.cpp` | **产品行为改动**：`read_header` 三态、前缀解析、长度比较改减法 |
| `fastlane-payload/src/compile_service/axp1_server.cpp` | **产品行为改动**：数据面每帧重试解析、DOTI 收满即编译、EOF 帧降级为可选 |
| `runtime/fastlane-distcc/.../Axp1DistccChannelFactory.java` | 仅观测：新增 `AXP1_DATA_TIMEOUT` 与 `dataPlaneReasonCode()` |
| `runtime/fastlane-distcc/.../DistccTcpVsockBridge.java` | 仅观测：数据面原因码改走 `dataPlaneReasonCode()`；对端答完后写失败不再记 `CHANNEL_FAILED` |
| `runtime/fastlane-distcc/src/test/.../FloorLoopTest.java` | 测试：桩载荷改为按 DOTI 自终止；解禁 PVM-10 回归用例；修正 ARGV 长度谎报 2 字节的夹具 |
| `runtime/fastlane-distcc/src/test/.../Axp1DistccChannelFactoryTest.java` | 测试：原因码拆分单测 |
| `fastlane-payload/tests/{pvm_test_support.h,test_axp1_server.cpp,test_distcc_server.cpp}` | 测试：静默对端传输桩、DOTI 完成即应答、每个真前缀都是 `kIncomplete` |
| `tools/device-validation/dv_pvm_crossfire.py` | 仅观测：落盘 `connections[]`、新增 `remote_compile` 硬判据、删除 `distcc_remote_seen` |
| `tools/device-validation/test_dv_pvm_crossfire.py` | 测试：`remote_compile` 判定 8 例 |
| `.scratch/orchestella-pvm-compiler/issues/PVM-10-请求结束信号.md` | 文档：新票 |

**产品行为改动只有一处**：载荷自终止。其余全是观测/测试/文档。

## 4. 证据

### 4.1 T1 红灯（先红后绿）

构建入口用仓库自带的便携验证工程（本机是 Windows，FLN1 核心含 `<linux/vm_sockets.h>` 编不了）：

```
cmake -S fastlane-payload/pvm-service -B build/pvm-service -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/pvm-service -j
FL_TEST_FILTER=axp1 ./build/pvm-service/pvm_compile_service_tests.exe
```

红灯原文：

```
FAIL axp1_server_answers_a_complete_doti_without_a_client_half_close:
  no answer without a client half-close: outcome=5 (5=kTimeout 4=kProtocolError) engine_calls=0
17/18 passed
```

`outcome=5` 就是 `WireError::kTimeout`：载荷把 2000 字节的完整请求收全了，仍然空等到帧超时，
一个字节都没编译。与设备侧 `duration_ms=60563 / bytes_out=0` 同源。

转绿后：

```
PASS axp1_server_answers_a_complete_doti_without_a_client_half_close
PASS axp1_server_still_times_out_when_the_doti_body_never_completes
100% tests passed, 0 tests failed out of 5
```

### 4.2 解析层：每个真前缀都必须是「不够」而不是「不对」

`distcc_server_reports_every_proper_prefix_as_incomplete` 对基准请求（162 字节）按**一字节**步进切片，
162 个真前缀全部断言 `kIncomplete`。这条用例专门防「按长度差猜」的写法：DIST/ARGC/ARGV/DOTI
四段在不同偏移上失败，只有逐字节步进才覆盖得到。

### 4.3 JVM 回路

```
./gradlew :runtime:fastlane-distcc:test --rerun-tasks        # 全模块
./gradlew :runtime:fastlane-distcc:test --tests '*FloorLoop*'
```

`floor-loop-report.txt` 修前 / 修后：

```
修前 d0d half_close=no payload_saw_eof=false payload_bytes=2162 payload_timed_out=true  client_bytes=0  outcome=CHANNEL_FAILED/813
修后 d0d half_close=no payload_request_complete=true payload_saw_eof=false payload_bytes=2162 payload_timed_out=false client_bytes=12 outcome=COMPLETED/1
```

d0a/d0b/d0c 未变（6 ms / 1555 ms / 1542 ms），说明本轮没有顺手动到桥的空闲看门狗语义。

### 4.4 真机（`--phase crossfire --profile-segments`，`qa-alt-01` / API 37 / arm64-v8a）

门与桥（`crossfire.json` → `rounds[0]`）：

```
await_accelerator_ready = true      gate_ms = 3483      fallback_count = 0
connections = [{"bytes_in":90380,"bytes_out":87,"duration_ms":476,
                "outcome":"COMPLETED","failure_code":"NONE"}]
dot_i_bytes = 90126   distcc_ms = 22544   local_compile_ms = 31142
assemble_ms = 26096   preprocess_ms = 1404.5   transport_up_ms = 602
```

客户端自己的话（`round-0/distcc.log`，本轮新增落盘）：

```
distcc[930] ERROR: compile /root/fixture.c on 192.168.127.254:5103 failed with exit code 101
distcc[930] (dcc_build_somewhere) Warning: remote compilation of '/root/fixture.c' failed, retrying locally
distcc[930] Warning: failed to distribute /root/fixture.c to 192.168.127.254:5103, running locally instead
```

`101` 是 distcc 的 `EXIT_BAD_ARGUMENTS`。回包 87 字节正好等于「5 个 12 字节令牌头 +
27 字节 SERR」——`ARGV_REJECTED:` 前缀 14 字节、类名 12 字节、换行 1 字节，与
`UNKNOWN_FLAG`（或同长度的 `INCLUDE_PATH`）完全吻合，`DOTO` 为空，所以对象没回来。

`remote_compile` 判定输出：`verdict=false, reason=LOCAL_FALLBACK, local_fallback=true`，
`payload_code=null`——distcc 在 `DISTCC_VERBOSE=1` 下**不回显 SERR 正文**，只有退出码。
这一点写进 §7 的决策 1。

## 5. 未验证项与偏差

- **sanitizer 门本机跑不了**：MSYS2 的 mingw g++ 14.2 没带 libasan（`collect2: ld returned 1`），
  本机只跑了无 sanitizer 的 ctest。`FL_SANITIZE=ON` 的 ctest 仍是 CI（Linux）的门。
- `tools/ci/run-fastlane-zero-device.sh` 的 `cpp` 步骤在本机必然失败（产品构建含 Linux-only
  的 FLN1 传输），`python` 步骤因 venv 是 Windows 布局（`Scripts/python.exe` vs `bin/python3`）
  也跑不通——两者都是这台机器既有的限制，不是本轮引入。
- `tools/fastlane-build` 单测在本机 3 失败 14 错误（bash/zipfuse/符号链接类），与本轮改动无关。
- **开工 Prompt 的命令与事实有三处偏差**：
  1. `--apk` 参数不存在（驱动按包名 `--qa-package` 寻址，APK 已安装即可）；
  2. QA 构建**必须**显式 `-Porchestella.qaTargetSdk=28`。默认 `targetSdk=36` 会把应用放进
     `untrusted_app_34` 域，SELinux 拒绝执行 app 私有目录里的 gvproxy
     （`avc: denied { execute_no_trans }`），表现为 `QEMU_START_FAILED`——本轮为此白跑两轮；
  3. `@Ignore` 用例名是 `pvm10RegressionRequestWithoutHalfCloseMustReachThePayload`
     （Prompt 里写的是 `...RequestCompletesWithoutHalfClose`）。
- **`--phase all` 的 smoke 段在本机永远不过**：smoke 落 `am start` 不带实例 id，加速车道
  不会启动，只能等满 180 s 超时。交火段不受影响，本轮用 `--phase crossfire`。
- **实例状态是硬前置**：QA 只挑 `STOPPED/INTERRUPTED/CRASHED` 的实例；手动 `am start` 让实例
  变成 `RUNNING` 后立刻跑驱动，会得到 `QA_QEMU_INSTANCE_MISSING`。
- **`equivalence` 仍为 `INCONCLUSIVE`**：Windows 宿主的 x86 ld 不能链 aarch64 对象
  （`Relocations in generic ELF (EM: 183)` / compare script 超时），与本轮无关。

## 6. 风险：`kIncomplete` 边界写错会长什么样

- **把「不够」判成「不对」**：载荷在请求还没收全时发 `RST(kProtocolError)`，客户端看到的是
  distcc 协议错误 → 本地补编 → `rc=0`、`.o` 在。**症状和修之前一模一样**，只是等待时间从 60 s
  变成几十毫秒——如果只看 `rc`，会以为修好了。
- **把「不对」判成「不够」**：畸形流会一直读到 `frame_timeout_ms`（60 s）才 RST，等于把
  DoS 面从「立即拒绝」放宽到「占满一个帧超时」。本轮的护栏是 `prefix_recognised`：读过完整
  DIST 令牌之后的任何缺陷都立即失败，只有「一个令牌都没认出来」的垃圾才交给 ceiling 兜底。
- **`prefix_recognised` 判据本身写宽**：`axp1_server_drains_an_oversized_request_and_answers_job_too_large`
  与 `axp1_server_stops_draining_a_hostile_endless_stream` 两条既有用例就是这条边界的哨兵——
  它们必须继续拿到 `JOB_TOO_LARGE` / `RST(kFrameTooLarge)`，而不是 `RST(kProtocolError)`。

## 7. 需维护者决策

1. **让载荷在拒绝时说出「是哪个参数」**：现在只回 `ARGV_REJECTED:<class>`，设备侧到不了具体
   argv 元素（distcc 在 verbose=1 不回显 SERR 正文，verbose=2 也没有 trace 输出）。两条路：
   (a) 载荷在 SERR 里追加一个**下标**而不是参数内容（不泄露路径，`ARGV_REJECTED:UNKNOWN_FLAG:3`）；
   (b) 让驱动把 `distcc` 的 `DISTCC_LOG` 指到 guest 可读目录再拉回。前者是产品改动，后者是证据改动。
2. **`AXP1_DATA_TIMEOUT` 是否进 `tools/fastlane-distcc/evidence.py` 的 `EXPECTED_CASES`**：
   目前只影响诊断文本，没有用例依赖它。
3. **`Axp1DistccAccelerator`（payload 侧 Java，spawn 真实 distccd 的 02a 路径）** 仍以 AXP1 EOF
   作为 stdin 结束信号。编译模式还会发 EOF 帧，所以行为不变；要不要一并改成 DOTI 自终止，
   属于下一轮的范围决定。

## 8. 交接：会话 12 的第一动作

**不要动 PVM-10 的任何东西**——机制已验证（476 ms / `COMPLETED` / `bytes_out>0`）。
新开一票（建议 `PVM-11 · 载荷 argv 白名单 vs 真实 distcc argv`），第一动作按顺序：

1. 用 §7 决策 1 的任一方案拿到被拒的 argv **下标**，与
   `distcc clang -c --target=aarch64-unknown-linux-android -O1 <tmp>.i -o <tmp>.o`
   对照，确认多出来的那个参数是谁加的（distcc 客户端 / Arch 的 clang 包装 / 我们自己的命令行）。
2. 在 `fastlane-payload/pvm-service` 的宿主回路里补一条**用真实 distcc 生成的 argv** 的用例
   （现在的 golden 只有 `cc -c fixture.c`，太干净，覆盖不到 `--target=` 与 `-o` 的临时路径）。
3. 之后才谈 AX-07 采样。`distcc_ms` 现在没有意义：它量的是本地补编。

## 9. 声明

- 未改任何默认超时值（`frame_timeout_ms` 仍 60000、`wall_budget_ms` 仍 60000）。
- 未改 `app/` 的产品默认开关。
- 未 `git add -A`、未直推 `main`、未合并自己的 PR。
- 未放宽既有断言；两处断言被**改基准**而非放宽，理由见 §5 与 §4.2：
  `distcc_server_rejects_protocol_defects` 的「截断头」子例改判 `kIncomplete`，
  `FloorLoopTest.d0d` 从「必然超时 0 字节」改为「必须自终止并拿到响应」。
