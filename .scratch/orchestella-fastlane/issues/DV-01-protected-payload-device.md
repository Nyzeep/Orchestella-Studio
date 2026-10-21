# DV-01: Protected Microdroid payload 真机门（串行 1）

Type: device-verification
Status: dv01_core_pass_terminal_blocked
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

**Phase 0A/0B/0C 完成；DV-01 真机结果 `DV01_CORE_PASS_TERMINAL_BLOCKED`。**

### 前置与构建

- 操作者指定替代 QA 设备（别名 `qa-alt-01`，API 36；不是 HONOR MEY-AN00，按要求只写别名）。APK 安装成功，`MANAGE_VIRTUAL_MACHINE`/`USE_CUSTOM_VIRTUAL_MACHINE` 均 granted。
- payload：NDK 27.0.12077973 构建成功，`T AVmPayload_main`，三个 `PT_LOAD p_align=0x4000`；staged SHA256 `7f5c4d72…`，APK 内 stripped SHA256 `0d40e13f…`；`libvm_payload.so` 未打包。
- 首次真机 create 因 v2-only APK 无法生成 idsig 而失败；在现有 debug signing config 启用 v3 后通过。最终 APK SHA256 `40b9b211…`，test APK `0e26c4dd…`；证书/渠道未变。
- payload 默认端口为 5100–5103，与 harness 默认 9100–9103 不一致；最终运行使用显式覆盖 5100–5103。后续需收敛默认值。
- 零设备门禁：313 tests / 8 skipped / 0 failures。

### 真机结果

- protected bit 可用；`setProtectedVm(true)` 接受，`setProtectedVm(false)` 抛 `UnsupportedOperationException`。
- create/run/payloadReady 成功（801–1202 ms）；control HELLO/HELLO_OK 成功（caps=45，3–8 ms，`auth_token=empty`）。
- stop first/second 均 `CONFIRMED`；20/20 次 create/run/stop/delete 全部 pass，stop 382–527 ms，每轮 residual=false。
- terminal PTY 返回 FLN1 7（`EXEC_FAILED`，`TOOLBOX_EXEC_FAILED`）；`/mnt/apk/bin/sh` 未产生输出，storage marker/持久化未测试。
- 负例：错误端口被 `FastLaneLaunchSpec` 拒绝；custom image 在 config builder 被接受（create 层未测试）；错误 payload 未获得 typed rejection，标记 `unconfirmed`。
- 判定：`DV01_CORE_PASS_TERMINAL_BLOCKED`；不得进入 DV-02，不得宣称完整 DV-01。
- 证据：`protected-fastlane/20261021/dv01/`（`fastlane-dv01-4d9a1e7c3b5f8026.json`、`cycles-20.tsv`、`terminal-failure.txt`）。
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md`。

下一步：修 toolbox `bin/sh` 的 guest 可执行性/挂载（不改 FLN1 wire、不改 pvmfw/SELinux），让 terminal 执行 `/mnt/apk/bin/sh`；再跑 storage marker/持久化、负例和完整 20 轮。terminal 通过前不勾选完整 DV-01 验收项，不进入 DV-02。未尝试 root/unlock 或绕过安装限制。
