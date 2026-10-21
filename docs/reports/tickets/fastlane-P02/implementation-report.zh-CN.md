# P02 Fast Lane payload runtime — 实现报告（零设备）

状态：零设备实现与宿主强化完成；真机（DV-01/DV-02）未验证。P04 已按 FLN1 对齐（2026-10-21 集成，见 §8）。
分支：`feature/fastlane-dataplane`。日期：2026-10-21（第二轮加固）。

## 1. 目标与范围

在 `fastlane-payload/` 交付 `AVmPayload_main()` 的最小 guest runtime：

- 只读 `/mnt/apk`（工具）与可写 `/mnt/encryptedstore`（数据）；
- control/terminal 帧协议、PTY 管理、storage/export/import、proxy bridge、distcc bridge；
- 不假定 guest TCP、chroot、mount、systemd；
- 所有失败有 typed code，日志不含 token/源码/按键正文。

不包含：真机 vsock、Microdroid SELinux、真实 Termux 工具、真实 `distccd`、NDK 产物。

## 2. 设计 seam（先于实现）

1. **边界**：宿主始终发起 vsock（AOSP `neverallow microdroid_payload ... connect`），payload 只 `listen/accept`。
2. **端口**：control/terminal 走帧；proxy/distcc 是原始字节流，payload 不解析 SOCKS5/distcc 协议。
3. **帧**：`magic FLN1 | version | type | sessionId | length | payload`，14 字节头，payload ≤ 64 KiB；短读必须循环读满；未知 type 在解码层拒绝。
4. **握手**：每个帧连接第一帧必须是 `HELLO`；版本 1；可选 token（≤255 字节）常量时间比较；`HELLO_OK` 返回能力位、最大帧、初始窗口；握手阶段 10 秒 receive timeout。
5. **失败**：1..14 为线上错误码（协议/版本/token/session/exec/storage/path/export/busy/internal），宿主映射到 spec 的 `TERMINAL_PROTOCOL_FAILED`/`STORAGE_FAILED`/`TOOLBOX_EXEC_FAILED`。
6. **PTY 所有权**：session 由打开它的连接拥有；连接断开时强制关闭；sessionId 单调递增、永不复用。
7. **流控**：每 PTY 初始 credit 由配置给出（1024..1 MiB），payload 发送 `PTY_DATA` 扣减，宿主 `WINDOW` 归还；credit 为 0 时停止读 master。
8. **storage**：`home/workspace/tmp/cache/exports` + `.fastlane-layout`（`version=1`）；路径逐组件拒绝 `..`、绝对路径、symlink。
9. **导出**：guest 侧 ustar/PAX tar → `EXPORT_DATA`；符号链接/设备/socket/凭据名拒绝或跳过；单文件 ≤ 8 GiB−1，总量 ≤ 16 GiB。
10. **导入**：host 流式 `IMPORT_BEGIN(0=unknown)/DATA/END(size,sha256)`；guest 写 `tmp/.import-*`，校验后 staged swap；只替换 tar 中出现的 `home`/`workspace` 顶层根，失败回滚。
11. **proxy**：宿主预开 vsock channel，payload 与本地工具连接 FIFO 配对；本地端点优先 UDS，TCP 127.0.0.1 失败则降级为仅 UDS（能力位反映）。
12. **distcc**：每连接一个 `distccd --inetd` child；vsock ↔ child stdin/stdout 双向泵；stderr 丢弃；child 退出后不再等待客户端半关闭。
13. **资源**：每个 fd/child/thread 有唯一 owner；停止顺序为 listener → 连接 → bridge → PTY；SIGPIPE 忽略；连接槽在 accept 后原子预占。
14. **测试接缝**：`ByteSource`/`FrameReader`、`Transport`（AF_VSOCK 或 Unix socket）、`PtyManager`、`TarReader/Writer`、`ProxyBridge` channel 队列。
15. **零设备诚实性**：Unix socket 传输只用于宿主测试；报告不把它写成“真实 vsock 通过”。

## 3. 第二轮加固（2026-10-21）

在既有实现上修复失败路径，不改变 `FLN1` 线上字节：

- **协议**：`FrameReader` 拒绝未知 type（`UNKNOWN_COMMAND`）；拒绝 transport 返回超过请求长度的读；`PayloadReader` 用减法判断剩余长度，消除 `offset + length` 无符号回绕；新增确定性随机字节测试（2000 轮，含合法 magic）。
- **进程/PTY**：`spawn_piped` 在第二/第三条 `pipe2` 失败和 `fork` 失败时关闭已建 fd；exec 状态管道处理 `EINTR`；`resolve_executable` 只接受普通文件（拒绝可执行目录）；PTY 子进程检查 `setsid`/`TIOCSCTTY`；`PtyManager` 只在锁内 `dup` master 供 `poll`，避免 close 后 fd 号复用；EOF 后 1 秒内未 `waitpid` 成功则强制关闭而非丢表项；session id 跳过 0 且不复用。
- **tar/storage**：`put_octal` 超限抛 `LIMIT_EXCEEDED`，不再静默截断（单文件上限 8 GiB−1）；PAX record 的长度和 `size` 都用溢出检查；PAX `size` 应用后重新校验上限；导出凭据名单大小写不敏感；导出/导入的总量检查改为不会回绕的减法；备份 staging 创建失败时清理导入 staging。
- **runtime**：连接槽用 CAS 预占，消除 accept 与 `connections` 计数的竞争；stop 在锁内 shutdown 连接 fd，worker 先从集合移除再 close，避免 fd 复用误 shutdown；listener 启动失败时回收 proxy/PTY；握手超时；`from_environment`/`start()` 校验窗口、session/连接上限、token 长度；import `fsync` 失败 typed 返回；import 帧大小检查防回绕。
- **bridge**：distcc 输出结束后 `shutdown(SHUT_RD)` 解除 uplink 阻塞，客户端保持连接也不会挂住 handler；stop 等待 active job 收敛；proxy/distcc 的 detached thread 创建失败时关闭 fd 并回退计数；proxy stop 在注册表锁内 shutdown。
- **构建**：新增 `FL_SANITIZE=ON`（ASan+UBSan，`-fno-sanitize-recover=all`）；`Threads::Threads` 显式链接。

## 4. 协议

完整字节见 `fastlane-payload/docs/protocol.md`。实现覆盖：

`HELLO/HELLO_OK/PING/PONG/OPEN_PTY/PTY_OPENED/PTY_DATA/RESIZE/RESIZED/SIGNAL/CLOSE/CLOSED/EOF/EXIT/WINDOW/EXPORT/EXPORT_BEGIN/EXPORT_DATA/EXPORT_END/IMPORT_BEGIN/IMPORT_DATA/IMPORT_END/IMPORT_DONE/METRICS/METRICS_OK/ERROR`。

补充说明：

- `WINDOW` 是本实现为流控增加的帧；P04 的 `OSFL` 使用 `CREDIT`，两者不兼容（见 §8）。
- `IMPORT_BEGIN.total_bytes=0` 表示未知大小，`IMPORT_END` 的 size/hash 为准。
- 固定默认 vsock 端口 5100/5101/5102/5103，必须与 P01 `FastLaneLaunchSpec` 一致；环境变量可覆盖，但生产 payload 无法读取宿主配置，端口一致性是跨 agent 契约。
- 单 tar entry 上限 8 GiB−1（11 个八进制位），导出/导入总量 16 GiB、最多 100000 entry；超限是 typed 错误，不截断。
- 未知 type（包括看似 reply 的 `0x8n`）一律拒绝；v1 没有前向兼容的 reply 命名空间。

## 5. 测试命令与结果

宿主 C/C++（零设备；cmake 3.31.6 / g++ 14.2）：

```sh
cmake -S fastlane-payload -B /tmp/fl-build -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/fl-build -j
ctest --test-dir /tmp/fl-build --output-on-failure
```

结果：`1/1 Test #1 ... Passed`，内部 **70/70** case 通过（上一轮 49）。新增/强化覆盖：

- protocol 17：原 13 项 + 未知 type、transport 超读、长度回绕、2000 轮确定性随机输入；
- SHA-256 4：FIPS 向量、流式 vs 一次性、hex、文件；
- storage 20：原 12 项 + PAX size 溢出/超限、重复路径回滚、staging 清理、导出短写（`/dev/full`）、大小写凭据、不可编码 size、非普通文件 hash；
- process/PTY 13：原 9 项 + 可执行目录拒绝、exec 失败 fd 回收、`RLIMIT_NOFILE` 下 pipe 回滚、session id 单调；
- bridges 12：原 9 项 + stderr 隔离、客户端不半关闭时 handler 收敛、stop 关闭排队 channel；
- runtime 4：HELLO 首帧、完整 control 回合、非法配置拒绝、未知 type 拒绝。

Sanitizer：

```sh
cmake -S fastlane-payload -B /tmp/fl-build-asan -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DFL_SANITIZE=ON
cmake --build /tmp/fl-build-asan -j
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=print_stacktrace=1 \
  ctest --test-dir /tmp/fl-build-asan --output-on-failure
```

结果：**70/70** 通过，无 ASan/UBSan 报告。宿主 LeakSanitizer 在 fork/exec 子进程路径上没有报泄漏。

符号检查：

```sh
nm /tmp/fl-build/fastlane_payload_host | grep AVmPayload_main
# 0000000000006b79 T AVmPayload_main
readelf -Ws /tmp/fl-build/fastlane_payload_host | grep AVmPayload_main
# GLOBAL DEFAULT FUNC ... AVmPayload_main
```

NDK/arm64 `.so`：本环境没有 `ANDROID_NDK_HOME`，**未运行** `scripts/build-android.sh`，因此 `AVmPayload_main` 在 arm64 `.so` 中的导出仅为脚本静态保证（`-fvisibility=hidden` + `visibility("default")`），未做 `llvm-nm` 实测。

## 6. 未验证 / not-tested

- 真实 `AF_VSOCK` bind/accept、Microdroid `microdroid_app` SELinux、`/mnt/apk` 执行、`devpts`、`/mnt/encryptedstore` 挂载；
- 真实 `distccd --inetd`（zero-device 用 `/bin/sh -c cat` 代替）；`distccd` 是否需要 `--allow` 的 inetd 行为待 P09/DV-03 确认；
- Termux bootstrap 的 `$PREFIX`/RUNPATH/symlink 在只读 APK 前缀中的可用性；
- NDK arm64 构建与 16 KB 对齐；
- P04 的真实帧 fixture / codec adapter（见 §8）；
- guest 本地 TCP loopback 是否被 SELinux 允许（proxy 能力会因降级而只提供 UDS）；
- 真实磁盘满（`/dev/full` 只证明短写映射为 `kIo`，未在 ext4/encryptedstore 上压满）。

## 7. 研究来源（一手）

- `references/aosp-virtualization` @ `175a51b`：
  - `libs/libvm_payload/include/vm_payload.h`、`vm_main.h`：`AVmPayload_main`、APK/encrypted storage 路径、VM secret；
  - `guest/microdroid_launcher/main.cpp`：`AVmPayload_main` 通过 isolated linker namespace 加载，允许的库仅 `libc/libm/libdl/libdl_android/liblog/libvm_payload/libbinder_ndk/libbinder_rpc_unstable`；
  - `guest/zipfuse/src/inode.rs`：`bin/` 下文件为 `EXECUTABLE_FILE_MODE`；
  - `guest/encryptedstore/README.md` + `src/main.rs`：ext4、dm-crypt/HCTR2、`encryptedstore_file` context；
  - `guest/microdroid_manager/aidl/android/system/virtualization/payload/IVmPayloadService.aidl`：`/mnt/apk`、`/mnt/encryptedstore`。
- `references/aosp-sepolicy`（浅克隆 main）：
  - `microdroid/system/private/microdroid_payload.te`：vsock `create listen accept ...`、`neverallow ... connect`、devpts、encryptedstore rw、无 execute；
  - `microdroid/system/private/microdroid_app.te`、`encryptedstore.te`。
- `references/termux-exec` @ `2cd0ba6`：`LD_PRELOAD` 路径重写机制（只借鉴，不复制）。
- `references/termux-packages` @ `f1b5f46`：`packages/distcc/build.sh`（distcc 3.4、GPL-2.0、`--disable-pump-mode`）。
- distccd 手册（Debian bookworm）：`--inetd` 单客户端 stdin/stdout、日志走 stderr、inetd 模式不要 `--log-stderr`、`--allow` 必填的说明。
- RFC 1928（SOCKS5）、RFC 7230 §9.3.6（CONNECT）：见 P05 报告。
- `references/gvisor-tap-vsock` @ `1cd3f04` README：gvproxy 的 vsock/unix 监听与 guest 侧 TAP 需求（本实现不使用 guest TAP）。
- P04 `docs/reports/tickets/fastlane-P04/protocol-alignment.zh-CN.md` @ `github/feature/fastlane-runtime`：`OSFL` 与 `FLN1` 不兼容、建议以 `FLN1` 为 wire 契约（只读引用）。

## 8. 跨 agent 契约

- **P04**：A 已推送 `feature/fastlane-runtime`。其 `PROTOCOL.md` 明确 `OSFL` v1 **不是** `FLN1` 的 wire-compatible 实现；其对齐报告建议保留 `FLN1` 为 vsock 契约、在 P04 `TerminalCodec` 后加 `Fln1TerminalCodec` adapter。本轮**不修改** `FLN1` 字节，也不实现双协议。
  - **2026-10-21 集成更新**：adapter 已在 `integration/fastlane-20261021` 落地（`Fln1TerminalCodec`），`OSFL` 退役，`FLN1` 字节未改。跨语言 fixture 为 `protocol/fln1-golden.json`；宿主对真实 `fastlane_payload_host` 的 Unix socket E2E 通过。真实 vsock/PTY 仍归 DV-02，不能据此宣称设备通过。
- **P09**：payload 只实现 `distccd --inetd` stdio 桥，宿主 `DistccTcpVsockBridge` 仍归 Agent C；本会话未修改 `runtime/fastlane-distcc`。每连接一个 child、不解析协议，与 P09 票一致。
- **P05**：payload 的 proxy bridge 不解析 SOCKS5；宿主 `FastLaneProxyServer.serveChannel()` 是配对接口。宿主需为每个并发 tunnel 预开一条 vsock channel；`FastLaneSession` 的单 channel 缓存不能直接用于 proxy 并发。
- **P06**：导出 tar 的字节由 P06 `FastLaneExporter` 消费；导入由 P06 `FastLaneImporter` 产生 canonical tar，`IMPORT_BEGIN.total=0` 已同步到协议文档；单 entry/总量上限已与 P06 对齐（8 GiB−1 / 16 GiB）。
- **P01**：默认端口 5100–5103 必须与 launch spec 一致。**token = not-wired（2026-10-21 结论）**：payload 只读 `FASTLANE_AUTH_TOKEN`（长度 ≤255，空则不校验）；AOSP app-payload 的 `VirtualMachineConfig.Builder` 没有 token/secret/env/argv setter，`setPayloadConfigPath` 只接受 APK 内路径且与 `setPayloadBinaryName` 互斥，`AVmPayload_getVmInstanceSecret` 不提供给宿主。因此真实 Microdroid 链路的 token 必然为空，token 校验实际关闭；E2E 的 token 是 test-only。不得用 E2E 通过证明生产已认证。

## 9. 风险与下一步

1. vsock/端口契约是最大集成风险：P01 必须使用相同端口，且 proxy 需要每连接新 channel。
2. `AVmPayload_main` 运行在 isolated namespace，只能链接允许的库；本实现用 `c++_static` 并在 NDK 脚本中链接 `libvm_payload` stub，需在 DV-01 确认 APEX 解析。
3. PTY 子进程环境未包含 `LD_LIBRARY_PATH`（Termux 依赖 RUNPATH）；P03 工具链落地后按 manifest 补。
4. 导出对 symlink 采取硬拒绝，可能拒绝含 symlink 的真实 home；若 DV-02 发现常见工具创建 symlink，需要改为“跳过并报告”，这是产品决策。
5. 导入只替换 tar 中出现的顶层根；若 UI 承诺“整树替换”，应在选择文件时检查 `home`/`workspace` 两个根都存在。
6. 8 GiB−1 的单文件上限是 ustar 八进制字段的硬限制；更大的文件需要 GNU base-256 或拆分，未实现。
7. distcc 的 `--inetd` 参数集和 stderr 处理需在 DV-03 用真实 distccd 验证；stop 只保证 2 秒内收敛 active job，超时后 detached handler 仍由 shared context 保活。
