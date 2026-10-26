# PVM-10 · 请求结束信号：从 TCP 半关闭换成 DOTI 自定界

- 状态：**机制已实现并真机验证；验收 A/B/C 未过，阻塞点转为 argv 白名单（新票 PVM-11）**
- 分支：`feat/pvm-10-doti-termination`
- 报告：`docs/reports/tickets/pvm-10/implementation-report.zh-CN.md`
- 基线：`main = 722bf8e`
- 前置：`DV-PVM-03`（归因）已合并

## 一句话

distcc v1 的请求用 `DOTI<8 位十六进制长度>` 自定界，上游 `doc/protocol-1.txt` 明说「Closing the channel has no meaning and does not indicate the end of a packet」；桥却把「请求结束」定义成 TCP 半关闭。真实客户端从不半关闭，于是 AXP1 EOF 帧永不发出，载荷空等 `frame_timeout_ms = 60000` 后 `RST(kTimeout)`，客户端 0 字节转本地补编。

修法：**载荷按 DOTI 长度自终止，桥保持透明**。`frame_timeout_ms` 不动，退化为纯安全网。

## 改动面（本轮唯一一处产品行为改动）

| 位置 | 改动 |
| --- | --- |
| `distcc_server.h` | `DistccParseStatus` 增 `kIncomplete`；`DistccParseResult` 增 `prefix_recognised` |
| `distcc_server.cpp` | `read_header` 返回三态（ok / 不够 / 不对）；`parse_distcc_request` 按前缀解析，`kIncomplete` ≠ `kProtocol` |
| `axp1_server.cpp` | 数据面每收一帧重试解析，`kOk` 立即编译；EOF 帧仍接受但不再必需；`kTooLarge` 转入既有 drain 路径 |
| `Axp1DistccChannelFactory.java` | 新增 `AXP1_DATA_TIMEOUT` + `dataPlaneReasonCode()`，把数据面 `RST(TIMEOUT)` 与握手超时拆开 |
| `DistccTcpVsockBridge.java` | 数据面原因码走 `dataPlaneReasonCode()`；对端已答完后写失败不再记 `CHANNEL_FAILED` |
| `dv_pvm_crossfire.py` | `instrumentation_final` 落盘 `connections[]`；新增 `remote_compile` 硬判据（`REMOTE`/`LOCAL_FALLBACK`/`TRUNCATED`/`PAYLOAD_REJECTED`…），删除 `distcc_remote_seen` 假阳性；客户端全量输出落 `round-N/distcc.log` |

## 判据（真机）

| 观测 | 修前（21:09，2418 B `.i`） | 修后（2000 函数，90126 B `.i`） |
| --- | --- | --- |
| 桥 `duration_ms` | **60 563** | **476 / 770** |
| 桥 `bytes_out` | **0** | **87** |
| 桥 `outcome` | `CHANNEL_FAILED` / `AXP1_HANDSHAKE_TIMEOUT` | `COMPLETED` / `NONE` |
| 客户端 | 0 字节 → `EXIT_TRUNCATED` → 本地补编 | 87 字节 → `exit code 101`（`EXIT_BAD_ARGUMENTS`）→ 本地补编 |

**地板没了，但对象没回来**：载荷用 `ARGV_REJECTED:<class>`（SERR 27 字节 ⇒ 12 字符类名，
只可能是 `UNKNOWN_FLAG`/`INCLUDE_PATH`，而命令行里没有 `-I`）拒了这次作业。
argv 策略一直如此，只是此前请求永远走不到。**下一票 PVM-11**：把真实 distcc 的 argv 找出来。

## 遗留

- `Axp1DistccAccelerator`（payload 侧 Java，spawn 真实 `distccd` 的 02a 路径）仍以 AXP1 EOF 作为 stdin 结束信号。本轮未触及，编译模式仍会发 EOF 帧，因此行为不变。
- 数据面 `RST(TIMEOUT)` 的原因码拆分只影响诊断；生产默认开关仍关闭（等 AX-07）。
- `tools/device-validation` 的 `test_avf_tools.py` 需要 `AVF_VALIDATION_TEST_ROOT` 环境变量，未纳入零设备车道。
- QA 构建必须 `-Porchestella.qaTargetSdk=28`，否则 gvproxy 被 SELinux 拒绝执行（`QEMU_START_FAILED`）。
