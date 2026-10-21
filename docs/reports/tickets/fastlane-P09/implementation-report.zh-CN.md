# P09 Distcc 跨车道实现报告（零设备）

日期：2026-10-21 ｜ 分支：`fastlane/agent-c-toolbox` ｜ 状态：宿主桥与真实 distcc 宿主编译已验证；protected payload/设备 `not-tested`

## 1. 目标

QEMU guest 的 `distcc clang` 通过宿主 loopback TCP → 每连接一条 vsock →
payload `distccd --inetd` 完成编译，对象经原路返回；宿主与 payload 只做字节
桥，不解析 distcc 协议。

## 2. 设计 seam

`DistccTcpVsockBridge` 的职责边界：

- `ChannelFactory` 是唯一 vsock 接缝；测试用内存 pipe/假 distcc 替换。
- 每个 TCP 连接对应一个 `FastLaneChannel`，连接间无共享缓冲。
- 可选 `StatsSink` 只接收 `ConnectionStats`（outcome/字节数/耗时），
  不接收源码、对象、argv 或 token。
- `Outcome`：`COMPLETED`、`CHANNEL_FAILED`、`IDLE_TIMEOUT`、
  `LIMIT_REJECTED`、`BRIDGE_CLOSED`。
- 不解析 distcc 协议；`distccd --inetd` 的 stdio 契约交给 P02。

## 3. 改动

`runtime/fastlane-distcc/src/main/java/.../DistccTcpVsockBridge.java`：

- 保留 loopback-only、连接上限、双向字节泵；
- 新增空闲 watchdog（`idleTimeoutMs`，0 关闭）；旧 5 参构造的最后参数语义
  改为 idle timeout，避免 distcc“发完请求等响应”时被 socket read timeout
  误杀；
- 新增半关闭：客户端 `shutdownOutput` → 关闭 channel output（让
  `distccd` 看到 stdin EOF），继续把响应 drain 回客户端；
- `close()` 取消活跃连接并标记 `BRIDGE_CLOSED`；
- 终态幂等，避免双向泵竞争重复计数；
- 连接上限拒绝在开 channel 之前发生，并上报 `LIMIT_REJECTED`；
- 统计与失败分类接口，供 P10/P07 消费。

测试：

- `DistccTcpVsockBridgeTest`：10 个 JUnit 测试（原 4 个 + 半关闭、连接上限、
  空闲超时、取消、并发隔离、非法参数）。
- `DistccHostIntegrationTest`：真实 `distcc 3.4` 客户端 → 桥 → 真实
  `distccd --inetd` → 返回 `.o`，再链接/运行得到 `42`。无 distcc 的环境会
  `Assume` skip。

## 4. 测试证据

```bash
javac --release 17 -Xlint:all -Werror -d <tmp>/fastlane-c-classes \
  -cp <tmp>/junit-4.13.2.jar $(find runtime/engine-fastlane/src/main/java \
  runtime/fastlane-distcc/src/main/java runtime/fastlane-distcc/src/test/java -name '*.java')
java -cp <tmp>/fastlane-c-classes:<tmp>/junit-4.13.2.jar:<tmp>/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore \
  io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridgeTest \
  io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccHostIntegrationTest
# OK (11 tests)
```

覆盖点：

- 假 distcc 字节 fixture：`DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO`
  字节级完全一致；客户端 half-close 后仍收到完整响应。
- 连接上限：第二个连接在 channel 打开前被拒绝，factory 只调用一次。
- 空闲超时：无字节连接在 150 ms 后关闭，outcome `IDLE_TIMEOUT`。
- 取消：`bridge.close()` 后客户端立即看到关闭，outcome `BRIDGE_CLOSED`。
- 并发：4 条连接各自回显自己的 payload，无串流。
- 真实 distcc：`.o` 非空、可链接、可执行；stats 为 `COMPLETED` 且双向
  字节数 > 0。

## 5. Guest 契约

见 [`guest-contract.md`](guest-contract.md)。要点：

- payload 每连接 fork/exec `/mnt/apk/bin/distccd --inetd`；
- child stdin/stdout = vsock 流，stderr 不得进入协议；
- `PATH`/`DISTCCD_PATH=/mnt/apk/bin`，`TMPDIR=/mnt/encryptedstore/tmp`；
- `waitpid` 有界回收，payload 停止时清理子进程；
- v1 只承诺 C + clang 同版本/同 target/flags；C++ `not-tested`；
- host 仍只绑 loopback；guest 通过 QEMU 转发的宿主地址连接。

## 6. 失败与回退

宿主桥映射：`CHANNEL_FAILED`/`IDLE_TIMEOUT` → 客户端本地回退；
`LIMIT_REJECTED` → 立即回退；`BRIDGE_CLOSED` → VM 停止/会话结束回退。
payload spawn/compiler 失败由 P02 映射为 `DISTCC_COMPILER_MISMATCH` 或
`DISTCC_BRIDGE_FAILED`。客户端 `DISTCC_FALLBACK=1`（默认）不应让构建失败；
要证明 Fast Lane 真正编译，必须在测试中用 `DISTCC_FALLBACK=0` 并核对宿主
字节计数。

## 7. 未验证 / not-tested

- protected payload 内 `distccd --inetd`、Bionic/Termux 执行、vsock 直连：
  `not-tested`，DV-03。
- QEMU guest 经 passt/gvproxy 到宿主 loopback 的具体地址/端口转发：未实机。
- C++、GCC、跨版本 clang、多 vCPU 并发：`not-tested`。
- 端到端 ≥1.5x 性能：未测；不得用宿主结果替代。
- 真实 distcc 宿主测试只证明字节桥保真，不证明 Android 路径。

## 8. 安全/隐私

- 只绑 loopback；非 loopback 构造即拒绝。
- 桥本身无认证；端口仅在一次会话期间绑定、使用临时端口、随 VM 关闭。
  本机其他应用访问该 loopback 端口的风险记录在契约中，后续由 P02/P07
  的 token/能力门处理。
- 不记录源码/对象/argv/token；只记录计数。

## 9. 研究来源

- distcc 协议说明（`DIST/ARGC/ARGV/DOTI`、`DONE/STAT/SERR/SOUT/DOTO`，
  12 字节头、十六进制长度）：
  <https://raw.githubusercontent.com/distcc/distcc/master/doc/protocol-1.txt>
- `distccd --inetd`（inetd/stdin-stdout 单连接模式）：
  <https://raw.githubusercontent.com/distcc/distcc/master/man/distccd.1>
- `DISTCCD_PATH` 与编译器搜索路径：同上 "SEARCH PATHS"。
- Termux distcc 构建参数（`--disable-pump-mode`，GPL-2.0）：
  <https://github.com/termux/termux-packages/blob/master/packages/distcc/build.sh>
- AOSP Microdroid vsock/TCP 边界（背景）：
  <https://source.android.com/docs/core/virtualization/microdroid>
- 本仓跨车道调研：`docs/research/2026-10-21-cross-lane-reference-survey.md`
