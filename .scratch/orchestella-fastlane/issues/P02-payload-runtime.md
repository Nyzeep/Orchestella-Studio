# P02: Fast Lane payload runtime（零设备）

Type: implementation
Status: implemented（zero-device 验收通过；DV-01/DV-02 真机待验）
Priority: P0（Fast Lane 基础）
Owner: payload owner
Blocked by: None（零设备）；真机由 DV-01/DV-02 验证
External gates: 无

## 目标

实现 `AVmPayload_main()` 的最小 guest runtime：

- 读取 `/mnt/apk`（只读工具）与 `/mnt/encryptedstore`（可写数据）；
- 启动 control vsock server、PTY manager、storage/export、proxy bridge、distcc bridge；
- fork/exec `/mnt/apk/bin` 内工具；
- 不假定 TCP/chroot/mount/systemd；
- 协议版本化，失败 typed。

## 零设备范围

- NDK/CMake 构建 `libfastlane_payload.so`，`AVmPayload_main` 符号可检查；
- 协议编解码/状态机用宿主测试（内存流/pipe/本地 socket）；
- 不运行 Microdroid；不要求 adb/设备；
- 可编译进 `:app` 或独立 CMake target；CI 做 NDK build。

## 交付

- `fastlane-payload/`：`main.cpp`、`vsock_server`、`pty_manager`、`storage`、`proxy_bridge`、`distcc_bridge`、`protocol`；
- `vm_payload.h`/stub 的 Apache-2.0 使用说明；
- 协议文档（magic/version/type/sessionId/length/payload）；
- host-side 单测/模拟流测试；
- CMake/NDK 构建脚本。

## 行为验收

- [ ] `AVmPayload_main` 可编译为 arm64-v8a `.so`；符号/依赖检查通过。
- [ ] control server 支持 `HELLO`/`PING`/`OPEN_PTY`/`RESIZE`/`CLOSE`/`EXPORT`/`METRICS`；未知 type 拒绝。
- [ ] PTY：openpty/fork/exec `/mnt/apk/bin/...`；EOF/信号/resize/关闭收敛；不泄漏 fd。
- [ ] storage：加密目录初始化、版本 marker、路径穿越拒绝；不在日志打印内容。
- [ ] proxy bridge：vsock 字节流 ↔ 本地代理客户端；超时/半关闭/错误映射。
- [ ] distcc bridge：vsock 字节流 ↔ `distccd --inetd` stdin/stdout；每连接一个 child；child 退出回收。
- [ ] 所有路径不假定 TCP/chroot/mount；失败返回 typed code。
- [ ] 不记录 token/源码/按键正文。

## 联网调研

- AOSP `libs/libvm_payload/include/vm_payload.h`、`guest/microdroid_launcher`、`guest/microdroid_manager`；
- Microdroid `devpts`/vsock/noexec 边界；
- Termux `termux-exec` 的 `LD_PRELOAD` 路径重写；
- `distccd --inetd` 的 stdio 模式；
- Android `openpty`/`posix_openpt`/`poll` 在 Bionic 的可用性。

## 停止条件

- NDK 构建无法在零设备环境完成：拆分 host-only protocol 与 NDK build，先交付协议/测试。
- 需要真实 vsock 才能验证：标 `not-tested`，DV-01 验证；不在零设备票里假装通过。

## 设备交接

DV-01：payload 启动、vsock 握手、encrypted storage、stop；DV-02：PTY/工具/代理/导出。

## Comments

- 2026-10-21（Agent B，`fastlane/agent-b-dataplane`）：零设备实现完成。新增 `fastlane-payload/`（C++17/CMake）：`AVmPayload_main`、四 listener、帧协议、PTY、storage/tar 导出导入、proxy/distcc 桥。宿主 49/49 CTest 通过；`AVmPayload_main` 在宿主二进制中可检查。NDK arm64 构建未运行（环境无 NDK），真机 vsock/SELinux/Termux/distccd 标 `not-tested`。详见 `docs/reports/tickets/fastlane-P02/implementation-report.zh-CN.md`。P04 fixture 尚未出现，`WINDOW` 扩展与帧编号待对齐。
- 2026-10-21（Agent B，第二轮加固）：协议/资源/导入边界加固，`FLN1` 字节不变。宿主 **70/70** CTest 通过（ASan+UBSan 同样 70/70，`FL_SANITIZE=ON`）；新增未知 type/超读/长度回绕/随机输入、pipe 回滚、PTY fd 复用、PAX 溢出、distcc 半关闭收敛、握手超时与连接槽预占测试。P04 已推送并确认 `OSFL` 与 `FLN1` 不兼容，其对齐报告建议以 `FLN1` 为 wire 契约并在 P04 侧加 codec adapter；本轮不改 wire，也未完成 adapter，故真实终端仍未联调。NDK/真机仍为 `not-tested`。详见报告 §3/§5/§8。
