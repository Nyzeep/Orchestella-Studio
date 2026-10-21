# P02 Fast Lane payload runtime — 实现报告（零设备）

状态：零设备实现与宿主测试完成；真机（DV-01/DV-02）未验证。
分支：`fastlane/agent-b-dataplane`。日期：2026-10-21。

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
3. **帧**：`magic FLN1 | version | type | sessionId | length | payload`，14 字节头，payload ≤ 64 KiB；短读必须循环读满。
4. **握手**：每个帧连接第一帧必须是 `HELLO`；版本 1；可选 token 常量时间比较；`HELLO_OK` 返回能力位、最大帧、初始窗口。
5. **失败**：1..14 为线上错误码（协议/版本/token/session/exec/storage/path/export/busy/internal），宿主映射到 spec 的 `TERMINAL_PROTOCOL_FAILED`/`STORAGE_FAILED`/`TOOLBOX_EXEC_FAILED`。
6. **PTY 所有权**：session 由打开它的连接拥有；连接断开时强制关闭；sessionId 单调递增、永不复用。
7. **流控**：每 PTY 初始 credit 256 KiB，payload 发送 `PTY_DATA` 扣减，宿主 `WINDOW` 归还；credit 为 0 时停止读 master。
8. **storage**：`home/workspace/tmp/cache/exports` + `.fastlane-layout`（`version=1`）；路径逐组件拒绝 `..`、绝对路径、symlink。
9. **导出**：guest 侧 ustar/PAX tar → `EXPORT_DATA`；符号链接/设备/socket/凭据名拒绝或跳过；宿主负责 manifest/hash。
10. **导入**：host 流式 `IMPORT_BEGIN(0=unknown)/DATA/END(size,sha256)`；guest 写 `tmp/.import-*`，校验后 staged swap `home/workspace`，失败回滚。
11. **proxy**：宿主预开 vsock channel，payload 与本地工具连接 FIFO 配对；本地端点优先 UDS，TCP 127.0.0.1 失败则降级为仅 UDS（能力位反映）。
12. **distcc**：每连接一个 `distccd --inetd` child；vsock ↔ child stdin/stdout 双向泵；stderr 丢弃。
13. **资源**：每个 fd/child/thread 有唯一 owner；停止顺序为 listener → 连接 → bridge → PTY；SIGPIPE 忽略。
14. **测试接缝**：`ByteSource`/`FrameReader`、`Transport`（AF_VSOCK 或 Unix socket）、`PtyManager`、`TarReader/Writer`、`ProxyBridge` channel 队列。
15. **零设备诚实性**：Unix socket 传输只用于宿主测试；报告不把它写成“真实 vsock 通过”。

## 3. 改动

新增：

- `fastlane-payload/CMakeLists.txt`、`README.md`、`docs/protocol.md`、`scripts/build-android.sh`；
- `include/fastlane/{error,paths,process,protocol,proxy_bridge,pty,runtime,sha256,storage,tar,transport,distcc_bridge,vm_payload_api}.h`；
- `src/{error,paths,process,protocol,proxy_bridge,pty,runtime,sha256,storage,tar,transport,distcc_bridge,main,vm_payload_stub}.cpp`；
- `tests/{test_main,test_support,test_protocol,test_sha256,test_storage,test_process,test_bridges,test_runtime}.cpp`。

关键实现：

- `runtime.cpp`：四个 listener、HELLO/控制分发、PTY reader 线程、导出/导入状态机、METRICS、优雅停止；
- `storage.cpp`：布局/路径安全、tar 导出、导入校验与 staged swap/rollback；
- `pty.cpp` + `process.cpp`：`posix_openpt`/`fork`/`execve`、TIOCSCTTY、resize、进程组信号、waitpid 回收；
- `proxy_bridge.cpp`：UDS/loopback TCP 本地端点 + 预开 channel 配对 + 半关闭/空闲超时；
- `distcc_bridge.cpp`：`distccd --inetd` stdio 桥；
- `transport.cpp`：`AF_VSOCK`（`VMADDR_CID_ANY`）与 Unix socket 测试传输。

## 4. 协议

完整字节见 `fastlane-payload/docs/protocol.md`。实现覆盖：

`HELLO/HELLO_OK/PING/PONG/OPEN_PTY/PTY_OPENED/PTY_DATA/RESIZE/RESIZED/SIGNAL/CLOSE/CLOSED/EOF/EXIT/WINDOW/EXPORT/EXPORT_BEGIN/EXPORT_DATA/EXPORT_END/IMPORT_BEGIN/IMPORT_DATA/IMPORT_END/IMPORT_DONE/METRICS/METRICS_OK/ERROR`。

补充说明：

- `WINDOW` 是本实现为流控增加的扩展；P04 票的帧类型列表未列出，需在 P04 fixture 落地时对齐（见 §8）。
- `IMPORT_BEGIN.total_bytes=0` 表示未知大小，`IMPORT_END` 的 size/hash 为准；这是为了让宿主从 SAF 流式导入而不必先落临时文件。
- 固定默认 vsock 端口 5100/5101/5102/5103，必须与 P01 `FastLaneLaunchSpec` 一致；环境变量可覆盖，但生产 payload 无法读取宿主配置，端口一致性是跨 agent 契约。

## 5. 测试命令与结果

宿主 C/C++（零设备；cmake 3.31.6 / g++ 14.2）：

```sh
cmake -S fastlane-payload -B fastlane-payload/build -DCMAKE_BUILD_TYPE=Debug
cmake --build fastlane-payload/build -j
ctest --test-dir fastlane-payload/build --output-on-failure
```

结果：`1/1 Test #1: fastlane_payload_tests ... Passed`，内部 **49/49** case 通过：

- protocol 13：头部字节、所有类型 round-trip、1 字节短读、背靠背帧、坏 magic/version/超大/截断、payload 读写、常量时间比较；
- SHA-256 4：FIPS 向量、流式 vs 一次性、hex、文件；
- storage 12：布局幂等/未知版本/symlink marker、路径穿越/symlink、tar 排序/跳过凭据/socket、symlink 拒绝、导入替换、穿越/绝对/链接拒绝、hash 失败与截断保留旧数据、长路径/UTF-8；
- process/PTY 9：exec 解析、环境、shell exit、resize、SIGTERM、强制关闭、credit 背压、fd 泄漏、session 上限；
- bridges 9：distcc 单 job/并发/超限/缺失工具；proxy TCP/UDS/配对超时/空闲超时/stop；
- runtime 2：HELLO 必须第一帧；完整 control 回合（HELLO→PING→OPEN_PTY→PTY_DATA/EXIT→EXPORT→IMPORT→METRICS）。

符号检查：

```sh
nm fastlane-payload/build/fastlane_payload_host | grep AVmPayload_main
# 0000000000006b59 T AVmPayload_main
```

NDK/arm64 `.so`：本环境没有 `ANDROID_NDK_HOME`，**未运行** `scripts/build-android.sh`，因此 `AVmPayload_main` 在 arm64 `.so` 中的导出仅为脚本静态保证（`-fvisibility=hidden` + `visibility("default")`），未做 `llvm-nm` 实测。报告中不把它写成 NDK 构建通过。

## 6. 未验证 / not-tested

- 真实 `AF_VSOCK` bind/accept、Microdroid `microdroid_app` SELinux、`/mnt/apk` 执行、`devpts`、`/mnt/encryptedstore` 挂载；
- 真实 `distccd --inetd`（zero-device 用 `/bin/sh -c cat` 代替）；`distccd` 是否需要 `--allow` 的 inetd 行为待 P09/DV-03 确认；
- Termux bootstrap 的 `$PREFIX`/RUNPATH/symlink 在只读 APK 前缀中的可用性；
- NDK arm64 构建与 16 KB 对齐；
- P04 的真实帧 fixture（本实现按 spec 冻结了字节，未与 P04 代码互测）；
- guest 本地 TCP loopback 是否被 SELinux 允许（proxy 能力会因降级而只提供 UDS）。

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
- RFC 1928（SOCKS5）：见 P05 报告。
- `references/gvisor-tap-vsock` @ `1cd3f04` README：gvproxy 的 vsock/unix 监听与 guest 侧 TAP 需求（本实现不使用 guest TAP）。

## 8. 跨 agent 契约

- **P04**：帧格式按 P04 票/spec 冻结；P04 代码尚未出现，**待与 P04 fixture 对齐**。本实现新增 `WINDOW` 扩展、`HELLO` 首帧约束和 `EXPORT/IMPORT` 类型；若 P04 的 type 编号不同，以 P04 fixture 为准改 C 侧，不双写。
- **P09**：payload 只实现 `distccd --inetd` stdio 桥，宿主 `DistccTcpVsockBridge` 仍归 Agent C；本会话未修改 `runtime/fastlane-distcc`。每连接一个 child、不解析协议，与 P09 票一致。
- **P05**：payload 的 proxy bridge 不解析 SOCKS5；宿主 `FastLaneProxyServer.serveChannel()` 是配对接口。宿主需为每个并发 tunnel 预开一条 vsock channel；当前 `FastLaneSession` 对同一端口缓存单 channel，生产接线需 P01 提供“每连接新 channel”的 factory。
- **P06**：导出 tar 的字节由 P06 `FastLaneExporter` 消费；导入由 P06 `FastLaneImporter` 产生 canonical tar，`IMPORT_BEGIN.total=0` 已同步到协议文档。
- **P01**：默认端口 5100–5103 必须与 launch spec 一致；token 来源待 P01/DV-01 明确（当前为可选 `FASTLANE_AUTH_TOKEN`）。

## 9. 风险与下一步

1. vsock/端口契约是最大集成风险：P01 必须使用相同端口，且 proxy 需要每连接新 channel。
2. `AVmPayload_main` 运行在 isolated namespace，只能链接允许的库；本实现用 `c++_static` 并在 NDK 脚本中链接 `libvm_payload` stub，需在 DV-01 确认 APEX 解析。
3. PTY 子进程环境未包含 `LD_LIBRARY_PATH`（Termux 依赖 RUNPATH）；P03 工具链落地后按 manifest 补。
4. 导出对 symlink 采取硬拒绝，可能拒绝含 symlink 的真实 home；若 DV-02 发现常见工具创建 symlink，需要改为“跳过并报告”，这是产品决策。
5. 导入以整棵 `home/workspace` 替换，未在 tar 中的文件会消失；UI 必须二次确认。
6. distcc 的 `--inetd` 参数集和 stderr 处理需在 DV-03 用真实 distccd 验证。
