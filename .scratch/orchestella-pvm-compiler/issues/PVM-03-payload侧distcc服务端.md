# PVM-03: payload 侧 distcc 服务端（无 exec）

- Type: implementation（C++，零设备 + 宿主 e2e）
- Status: **done（2026-10-25，零设备口径）** —— 证据见 `docs/reports/tickets/pvm-03/implementation-report.zh-CN.md`；真实 clang 编译归 DV-PVM-02
- Priority: P0（Wave 2）
- Owner: payload owner
- Blocked by: 无（PVM-02、PVM-05 已合并；设备事实已由 DV-PVM-01 提供）
- Parent: `../决策地图.md`
- Zero-device: 是（宿主真实 distcc 客户端 + 内存/回环传输）

## 2026-10-25 修订（真机后，必须按这个做）

DV-PVM-01 拿到了三条硬事实，直接改变本票的设计：

1. **payload 域零文件写**：对自身 memfd 的 `write/ftruncate/mmap` 全 `EACCES(13)`，`/data*`、`/dev`、`/tmp` 全部不可写。所以：**输入、输出、诊断全部在内存**；clang 必须用 `CI.setOutputStream(raw_svector_ostream)`（01b 已验证），**禁止任何 tmpfile / memfd / 落盘**。
2. **不要改 FLN1 的 `distcc_bridge.cpp`**：那是终端 payload，不含 LLVM。把编译器塞进去会把终端 payload 从几 MB 撑到 130 MB+，且两个用途寿命不同。改为**新建独立编译服务 payload**：
   - `fastlane-payload/src/compile_service/`（`distcc_server`、`argv_policy`、`clang_engine`）；
   - `src/pvm_compiler_payload_main.cpp` 产出 `libpvm_compiler_payload.so`；
   - CMake 目标 `pvm_compiler_payload`，默认不构建，`FL_BUILD_PVM_COMPILER_PAYLOAD=ON` 时构建（与 `pvm_probe` 同级，不链进 `fastlane_payload`）。
3. **传输走 AXP1 over vsock**，不是 QA 探针的行协议：payload 侧实现 AXP1 server（与 `runtime/fastlane-distcc/.../axp1/` 的 Java 编解码同 wire），宿主侧复用 `Axp1DistccChannelFactory`。新增 `COMPILER_VERSION` 能力项（现有 ID 1–5 之外），供 PVM-01 的 `payloadCompilerVersion` 使用。

零设备验收相应调整：

- **wire / argv / 上限 / 并发**：用 `FakeEngine`（返回一段固定合法 ELF 对象）验证，可完整在宿主跑；
- **真实 clang 编译**：零设备不做，归 `DV-PVM-02`（设备 e2e）；本票只在报告里标 `not-tested`；
- **`ALLOWED_CODES` 同步**：把 `PAYLOAD_LOAD_FAILED` / `ARGV_REJECTED` / `JOB_TOO_LARGE` / `JOB_TIMEOUT` 加进 `tools/accelerator-perf` 的允许码，否则真机失败样本写不进报告。

## 2026-10-25 之前的原始工单（保留，以修订为准）

### 背景

`fastlane-payload/src/distcc_bridge.cpp` 现在是「accept → `spawn_piped(distccd --inetd)` → 泵字节」。在 protected payload 里这个 `spawn` 必然失败。本票把它换成 `InProcessCompiler`，协议层不动。

同时，**服务端接收的是 guest 传来的 argv**。guest 里用户可以跑任意命令，所以 argv 是不可信输入：`-Xclang -load` 能在 payload 进程里加载任意插件，`@file` 能读文件，`-o` 能写文件。这是本票的第一优先级，不是加固项。

## 目标

- 实现 distcc v1 服务端：`DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO`，字节级对齐 golden。
- 编译走 `InProcessCompiler`，全程无 `fork/exec`，不落盘（输入输出用内存 buffer）。
- argv 只做**白名单提取**，其余一律 `ARGV_REJECTED`。
- 资源上限：输入大小、输出大小、单 job 墙钟、并发数。
- 复用现有 `DistccTcpVsockBridge` 与 golden fixture，不改宿主侧行为。

## 任务

- [ ] `src/compiler/argv_policy.{h,cpp}`：
  - 允许：`-c`、`-x cpp-output` / `-x c++-cpp-output`、`-O0..3`、`-g`、`-std=c*`（白名单）、目标三元组（必须等于配置值）；
  - 拒绝：`-o`、`@*`、`-Xclang`、`-fplugin*`、`-include*`、`-isystem`、`-I`、`-L`、`-l`、`--sysroot`、`-B`、任何含 `/` 的路径参数；
  - 每个拒绝返回固定子码（`ARGV_REJECTED:<flag-class>`，class 固定枚举，不含原始参数）。
- [ ] `src/distcc/distcc_server.cpp`：状态机解析 token，长度上限，背压，超时；错误映射 `STAT`。
- [ ] 替换 `DistccBridge::handle` 的 spawn 路径；保留 `active_`/`total_` 计数语义。
- [ ] 测试 `tests/test_distcc_server.cpp`：
  - golden `request-v1.hex` → `response-v1.hex` 字节一致；
  - 真实 `distcc 3.4` 客户端（宿主 x86_64）→ payload 侧服务 → `.o` 用 PVM-05 判据等价；
  - 负例：超长 argv、`@file`、`-Xclang -load`、超限 `.i`、半关闭、客户端中断；
  - 并发 4 路无串流（每连接独立状态）。
- [ ] 宿主 e2e 脚本：`tools/pvm-compiler/test-distcc-inprocess.sh`（沿用 AX-06 的 `DISTCC_E2E_OK` 风格）。

## 验收命令

```bash
cmake --build build/payload --target fastlane_payload_tests
ctest --test-dir build/payload --output-on-failure -R distcc
bash tools/pvm-compiler/test-distcc-inprocess.sh   # 期望 PVM_DISTCC_E2E_OK cases=N
```

## 边界

- 不实现 distcc 的 pump 模式、不实现压缩（压缩在 AXP1 层）。
- 不做远程链接；`-c` 之外的动作一律拒绝。
- 不修改宿主 `DistccTcpVsockBridge` 的协议行为；只允许新增「帧计数」埋点供 PVM-07。

## 失败信号

- 需要把 `.i` 写到文件才能编译 → 接口没做对，回 PVM-02。
- 单元测试里出现任何 `execve` → 立即失败。
- golden 字节不一致但「功能能跑」→ 不允许放过，先对齐字节。

## 证据

- ctest 输出、e2e 脚本输出、负例表（flag class → 固定码）。真机项由 DV-PVM-02（后续）承接，不写「已验证」。

## 2026-10-25 实施追记（会话 5 收口）

- **交付**：`fastlane-payload/src/compile_service/`（`compile_service` / `argv_policy` /
  `distcc_server` / `axp1_server` / `fake_engine` / `clang_engine` / `net`）+
  `src/pvm_compiler_payload_main.cpp` + `pvm-service/` 可移植验证入口 +
  3 个单测文件 + socket 级 e2e + `tools/pvm-compiler/{axp1_distcc_client.py,test-distcc-inprocess.sh,test-java-bridge-interop.sh}`。
- **零设备证据**：`42/42` C++ 用例、`ctest` 5 组全绿、`PVM_DISTCC_E2E_OK cases=2`、
  `PVM_EQUIVALENCE verdict=EQUIVALENT`、`PVM_JAVA_BRIDGE_OK`（真实 `DistccTcpVsockBridge`
  + `Axp1DistccChannelFactory`）、NDK 交叉构建的 `.so` 16 KB 对齐且只导出 `AVmPayload_main`。
- **与票面的三处偏差**（详见报告 §5.2，均需维护者认可）：STAT 改用 distcc `exitcode.h` 码值；
  argv 白名单按「只影响代码生成/诊断」扩容；`-o` 归 ABSORB（distcc 客户端会自己补 `-o`）。
- **未做**：真实 distcc 客户端 e2e、真实 libclang 编译（本机无 distcc、`wsl.exe` 被安全策略禁用、
  无 Android libclang）。这两项是 DV-PVM-02 的入场条件，不得当成已验证。
