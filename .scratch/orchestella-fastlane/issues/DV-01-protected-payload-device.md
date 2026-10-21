# DV-01: Protected Microdroid payload 真机门（串行 1）

Type: device-verification
Status: blocked_external
Priority: P0（第一个真机门）
Owner: 设备唯一操作者/主协调
Blocked by: P01, P02, P03, P04, P05, P06, P07, P08, P09, P10, P11, P12
External gates: 当前 HONOR 设备、QA APK、MANAGE_VIRTUAL_MACHINE（必要时 USE_CUSTOM）、QA 窗口

## 启动条件

**所有 P01–P12 零设备验收通过后**才启动。设备窗口唯一操作者；一次只跑一个 DV。

## 目标

在真实 protected-only 设备上验证 Fast Lane 的最小平台链：

- `getCapabilities` bit1；`setProtectedVm(true)` 不抛；
- `VirtualMachineConfig` + `setPayloadBinaryName` + encrypted storage + vsock；
- create/run；payload marker；`connectVsock` 握手；
- encrypted storage 写/读跨 stop/start；
- stop 收敛；20 次启停；
- 失败分类。

## 步骤

1. 安装 QA APK；确认 `android.software.virtualization_framework`、`vm info`、`getCapabilities`。
2. 授权 `MANAGE_VIRTUAL_MACHINE`（必要时 `USE_CUSTOM_VIRTUAL_MACHINE`）；确认 hidden API 豁免。
3. 创建 QA VM：1 vCPU、1GB、256MB encrypted storage、DEBUG_NONE（QA 可 FULL 抓 console）。
4. run；payload 初始化；宿主 `connectVsock(control)` 握手；terminal PTY echo。
5. 写 encrypted storage marker；stop；再 start；marker 存在。
6. 重复 20 次；记录 stop 状态/耗时/孤儿。
7. 负例：`setProtectedVm(false)`、custom image、错误 payload、错误端口。
8. 记录宿主不能读取 encrypted storage 明文；导出走 guest 侧。

## 验收

- [ ] create/run/stop 成功；stop CONFIRMED；20 次无孤儿。
- [ ] vsock control/terminal 双向；payload ready 可观测。
- [ ] encrypted storage 跨重启保留；host 不可读。
- [ ] 失败分类至少覆盖 `HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNAVAILABLE`、`PAYLOAD_VERIFY_FAILED`、`VSOCK_FAILED`、`STOP_UNCONFIRMED`。
- [ ] 证据脱敏：无序列号/IMEI/Android ID/账号/token/按键正文。
- [ ] 不把 `vm info` 声明当真实 payload 成功；不把 fake 当设备通过。

## 证据

外部 artifact 根：`protected-fastlane/20261021/dv01/`；含：
- 设备/ROM/APK/SHA/时间/actor；
- getCapabilities/`vm info` 脱敏输出；
- create/run 参数（脱敏）；
- vsock 握手/terminal echo；
- storage marker 跨重启记录；
- 20 次启停表；
- 失败样本/最早拒绝层。

## 停止条件

- `rejected_at_stage`/`blocked_external`：按分类停止，不绕过。
- `stop_unconfirmed`：先修 P01/P02 生命周期，不进入 DV-02。
- 需要 Root/unlock/pvmfw 修改：立即停止。

## 2026-10-21 执行记录

**Phase 0A/0B/0C 完成；DV-01 未执行，状态 `blocked_external`。**

- payload：NDK 27.0.12077973 构建成功，`T AVmPayload_main`，三个 `PT_LOAD p_align=0x4000`；staged SHA256 `7f5c4d72…`，APK 内 stripped SHA256 `0d40e13f…`；`libvm_payload.so` 未打包。
- APK：`io.github.nyzeep.orchestella.qa.dv01`；payload entry 为 stored，data offset `0x4DC000`，`zipalign -c -P 16` 通过；APK SHA256 `1b6f7b7a…`；test APK SHA256 `8a47e739…`。
- harness：`AvfQaProbeInstrumentation` 新增 `fastlane-dv01`；新增 `FastLaneDv01Contract` + 11 个 JVM 测试；payload/toolbox APK 门通过。
- 零设备门禁：313 tests / 8 skipped / 0 failures（4 个既有条件 skip + 4 个 Windows 主机夹具 skip）。
- 真机：当前连接设备不是文档中的 HONOR MEY-AN00；两个 APK 均被 OEM 安装限制拒绝，返回 `INSTALL_FAILED_USER_RESTRICTED`。包未安装、权限未授予、VM 未创建、instrumentation 未执行。
- 判定：`BLOCKED_EXTERNAL`（`DEVICE_MISMATCH` + `INSTALL_FAILED_USER_RESTRICTED`）；未尝试 root/unlock/SELinux/pvmfw 修改或安装限制绕过。
- 证据：`protected-fastlane/20261021/dv01/`（含 `fastlane-dv01-not-run.json`，这是 host blocked record，不是设备通过证据）。
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md`。

下一步：连接/授权 HONOR MEY-AN00，允许 USB 安装，安装 APK 并授予 `MANAGE_VIRTUAL_MACHINE`，重跑 `qaMode=fastlane-dv01`。在设备侧 JSON `outcome=PASS` 且 20 次表完整前，不勾选本票验收项，不进入 DV-02。
