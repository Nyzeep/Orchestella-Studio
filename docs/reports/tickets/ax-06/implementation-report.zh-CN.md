# AX-06 distcc 跨车道端到端宿主验证 实施报告

- 日期：2026-10-23
- Base：`feat/accelerator-batch2 @ a596d73`
- Branch：`feat/accelerator-batch3`
- 工单：`.scratch/orchestella-accelerator/issues/AX-06-distcc跨车道端到端宿主验证.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM 或 QEMU guest。
- CI：**未验证**。真实 distcc 集成测试在宿主本地运行；PR/CI 尚未运行。

## 1. 结论

零设备、宿主范围内**完成**。

- 新增 host 侧 `Axp1DistccChannelFactory`：在现有 `DistccTcpVsockBridge` 的 `ChannelFactory` 外挂 AXP1 HOST 握手，保留桥的透明字节语义。
- 新增加速侧 `DistccdInetdChannel`（`distccd --inetd` 的 stdin/stdout 泵，stderr 丢弃）与 `Axp1DistccAccelerator`（AXP1 GUEST ↔ distccd 双向泵，保留半关闭）。
- 真实 distcc 3.4 客户端经 TCP → 桥 → AXP1 HOST → 回环 AXP1 GUEST 模拟器 → 真实 `distccd --inetd` 完成 `hello.c` 编译、链接、运行；`.o` SHA-256 与本地编译一致。
- token 错、握手超时、编译器 triple 不匹配、加速器中途退出四条失败路径均由 distcc 本地回退，产物可链接、可运行，并记录固定 failure code。
- 新增 `tools/fastlane-build/test-distcc-e2e.sh` 与 `tools/fastlane-distcc` pytest；日志只保留 case/outcome/code/hash/字节/耗时。

## 2. 拓扑

```text
distcc client (host)
  |  TCP 127.0.0.1:<bridge>
  v
DistccTcpVsockBridge
  |  ChannelFactory -> Axp1DistccChannelFactory
  v
AXP1 HOST handshake (HELLO/HELLO_ACK, token, distcc, triple, arch)
  |  AXP1 DATA frames
  v
AXP1 GUEST simulator (host-only loopback)
  |  DistccdInetdChannel stdio
  v
distccd --inetd -> cc
```

生产替换点只有传输层：把回环 `ChannelFactory` 换成真实 vsock `FastLaneChannel`，AXP1 与 distccd 泵不变。本报告不把回环模拟写成 vsock/设备证据。

## 3. 协议与安全

- 角色：宿主侧 `Axp1Role.HOST`，加速侧 `Axp1Role.GUEST`。
- token：`AcceleratorUplink.newToken()`（每次运行 32 位 CSPRNG hex），只进 AXP1 HELLO；不写日志。
- 能力：`distcc=true`、`compiler_triple`、`arch`；host 侧校验 peer **原始声明**的 `distcc` 与 triple。peer 未声明 triple 也按 `COMPILER_MISMATCH` 拒绝，不采用协商回退值。
- 绑定：桥和测试加速器都只绑 `127.0.0.1`；不接受 LAN/公网地址。
- stderr：`distccd --inetd` 的 stderr 重定向到 DISCARD，永不进入 AXP1 DATA。
- 日志：`ConnectionStats` 只含 outcome、双向字节数、耗时、固定 `failureCode`；e2e 报告只含 case、outcome、code、SHA-256、计数、耗时。
- 未修改 FLN1 wire、AXP1 帧格式、payload、ADR、版本号或 applicationId。

## 4. 成功路径证据

宿主：x86_64 Debian，distcc/distccd 3.4，`cc` 为系统 GCC；源文件为固定 `hello.c`，flags 为 `-O2 -Wall -c`。

| 项 | 观测值 |
| --- | --- |
| 编译退出码 | 0 |
| 远端 `.o` SHA-256 | `c8460fbebf395a58cbddb82af45fd18f6ef1ad8dec30b3bb490f26804a16ecb1` |
| 本地对照 `.o` SHA-256 | 同上（一致） |
| 链接/运行 | 退出码 0，输出 `42\n` |
| 桥统计 | `COMPLETED`、`failureCode=NONE`、bytes 23962/1492 |
| 编译耗时（含本地对照） | 约 1107 ms（本机单次观测，不作为性能结论） |

编译输出与本地对照在本次运行中逐字节一致；未观察到需要解释的差异。

## 5. 失败回退证据

| 场景 | 桥 outcome / failureCode | distcc 退出 | 产物 |
| --- | --- | --- | --- |
| token 错误 | `CHANNEL_FAILED` / `AXP1_AUTH_FAILED` | 0（fallback） | 本地 `.o` 可链接/运行，哈希与本地一致 |
| 握手超时（400 ms） | `CHANNEL_FAILED` / `AXP1_HANDSHAKE_TIMEOUT` | 0（fallback） | 同上 |
| 编译器 triple 不匹配 | `CHANNEL_FAILED` / `COMPILER_MISMATCH` | 0（fallback） | 同上 |
| 加速器握手后退出 | `CHANNEL_FAILED` / `AXP1_CLOSED` | 0（fallback） | 同上 |

所有失败路径都使用 `DISTCC_FALLBACK=1`；测试断言 `.o` 非空、可链接、运行输出 `42\n`。没有产出坏对象。

## 6. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `runtime/fastlane-distcc/.../Axp1DistccChannelFactory.java` | 新增 | AXP1 HOST 包装、token/triple/distcc 校验、固定 reason |
| `runtime/fastlane-distcc/.../DistccdInetdChannel.java` | 新增 | `distccd --inetd` stdio 泵，stderr DISCARD |
| `runtime/fastlane-distcc/.../Axp1DistccAccelerator.java` | 新增 | AXP1 GUEST ↔ distccd 双向泵、半关闭 |
| `runtime/fastlane-distcc/.../axp1/Axp1Connection.java` | 修改 | 保留并暴露 peerCapabilities 原始视图，供严格能力校验 |
| `runtime/fastlane-distcc/.../DistccTcpVsockBridge.java` | 修改 | `ConnectionStats.failureCode`；AXP1 异常映射固定 code |
| `runtime/fastlane-distcc/build.gradle.kts` | 修改 | 可选 e2e 报告系统属性 |
| `Axp1DistccChannelFactoryTest.java` | 新增 | 6 例：token、成功透明字节、auth、triple、distcc、超时 |
| `Axp1DistccHostIntegrationTest.java` | 新增 | 5 例真实 distcc 成功/失败路径 |
| `DistccTcpVsockBridgeTest.java` | 修改 | 新增 1 例固定 failureCode；原 16 例保留 |
| `tools/fastlane-build/test-distcc-e2e.sh` | 新增 | 可复现 e2e 入口 |
| `tools/fastlane-distcc/evidence.py`、`verify_report.py`、`test_evidence.py` | 新增 | 报告校验、固定字段日志、11 例 pytest |

## 7. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:fastlane-distcc:test` | BUILD SUCCESSFUL；63 tests、0 failure、0 skipped |
| `python -m pytest tools/fastlane-distcc -q` | 11 passed |
| `bash tools/fastlane-build/test-distcc-e2e.sh` | BUILD SUCCESSFUL；5 个 case 全部通过，`DISTCC_E2E_OK cases=5` |
| `python -m pytest tools/ci tools/fastlane-device tools/fastlane-distcc -q` | 111 passed, 7 subtests passed |

测试分布：`DistccTcpVsockBridgeTest` 18（原 16 例全部保留，另加 failureCode 与自由文本收敛 2 例）、`Axp1DistccChannelFactoryTest` 7、`Axp1DistccHostIntegrationTest` 5、`Axp1ConnectionTest` 17、`AcceleratorUplinkTest` 15、既有 `DistccHostIntegrationTest` 1。

`ConnectionStats` 的 `failureCode` 在构造器做固定 token 校验；路径、token 形状或自由文本一律归一为 `CHANNEL_FAILED`，观测面不会成为泄露通道。

## 8. 偏差与已知限制

1. **宿主回环模拟，不是 vsock**：测试用 loopback TCP 代替加速 VM 的 vsock 传输；AXP1 与 stdio 泵是真实代码，传输层是模拟。不得把本报告当作设备/vsock 证据。
2. **只验证 C 与同架构同编译器**：C++ `not-tested`，跨架构 `not-tested`，clang/gcc 混用 `not-tested`。
3. **编译器版本未验证**：只验证 triple 匹配；同一 triple 下的编译器版本差异未比较。
4. **distcc 自身重试/并发**：`-j` 与多 job 未做压力测试；桥的连接上限、idle timeout 只由既有单测覆盖。
5. **脚本依赖宿主 distcc**：缺失时返回 `DISTCC_E2E_BLOCKED`（exit 2）；设置 `ALLOW_DISTCC_E2E_SKIP=1` 可在明确无 distcc 的环境跳过。本机已安装 distcc 3.4 完成真实运行。
6. **未接设备**：不运行 adb、不启动 QEMU guest、不使用真实 vsock/Fast Lane；CI 未运行。

## 9. 追记（2026-10-24，PVM-05）：SHA 判据的适用范围

本报告第 4 节以 `.o` SHA-256 与本地编译一致作为证据。该判据只在「同一编译器、同一路径、同一参数」的构建间成立；一旦编译实例或构建路径可能不同（例如 PVM 路线的 guest 预处理 + payload 内同源编译），路径、时间戳与随机种子都会使 SHA 不等，产生假阴性。自 PVM-05 起，跨实例的产物一致性以 `tools/compiler-equivalence/compare_objects.py` 的四级判据（链接 → 行为 → 符号/重定位 → 差异白名单）为准；SHA-256 保留为同源同参数构建的廉价辅助信号，不再是判据。本节为追记，上文证据与结论维持原样。
