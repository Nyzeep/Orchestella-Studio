# DV-01: Protected Microdroid payload 真机门（串行 1）

Type: device-verification
Status: dv01_core_pass_terminal_blocked（blocked_external: DV-01A SELinux `execute_no_trans`）
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
- terminal PTY 返回 FLN1 7（`EXEC_FAILED`，`TOOLBOX_EXEC_FAILED`）；DV-01A 进一步定位为 `execve` EACCES(13)，guest audit 为 SELinux `execute_no_trans` 拒绝；`/mnt/apk/bin/sh` 未产生输出，storage marker/持久化未测试。
- 负例：错误端口被 `FastLaneLaunchSpec` 拒绝；custom image 在 config builder 被接受（create 层未测试）；错误 payload 未获得 typed rejection，标记 `unconfirmed`。
- 判定：`DV01_CORE_PASS_TERMINAL_BLOCKED`；不得进入 DV-02，不得宣称完整 DV-01。
- 证据：`protected-fastlane/20261021/dv01/`（`fastlane-dv01-20261021-final.json`、`cycles-20.tsv`、`terminal-failure.txt`、`selinux-exec-denial.txt`）。
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md`。

下一步（更新）：terminal 的根因已定位为平台 SELinux 策略（见下）；在平台授予 `execute_no_trans` 或提供支持的可执行挂载前，不重复修 toolbox 打包/挂载。terminal 通过前不勾选完整 DV-01 验收项，不进入 DV-02。未尝试 root/unlock 或绕过安装限制。

## 2026-10-21 DV-01A — terminal 定位为外部 SELinux 阻塞

**Status: blocked_external**；focused ticket：`DV-01A-toolbox-exec-selinux.md`。

### 证据链

- 默认端口 5100/5101 下 control HELLO/HELLO_OK 成功（6 ms），terminal 的 vsock connect 也成功；不是端口/vsock 问题。
- OPEN_PTY 错误消息的 stage/errno 诊断：`fln1=7 execve_errno_13`。`realpath`、`stat`、`access(X_OK)` 都成功，失败只在 `execve`。
- APK 中 `bin/sh` 存在（1,115,944 bytes），ZIP `external_attr` mode 0（zipfuse `bin/` 默认执行位）；`access(X_OK)` 成功证明实际 mode 可执行。不是 ENOENT，也不是缺少执行位。
- 把 OPEN_PTY 指向 `/mnt/apk/bin/manifest.json`（`bin/` 下的普通非 ELF 文件）仍返回 EACCES(13) 而不是 ENOEXEC(8)；`access()` 在 noexec 挂载上也会失败，因此排除 noexec。
- DEBUG_FULL 诊断运行的 guest audit 直接证据：

```text
avc: denied { execute_no_trans } for path="/mnt/apk/bin/sh" dev="fuse"
scontext=u:r:microdroid_app:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

- 解释：`/mnt/apk` 是 zipfuse 挂载，文件标签为 `system_file`；A16 policy 有 `allow coredomain system_file:file execute`（access 通过），但没有给 `microdroid_app` 对 `system_file` 的 `execute_no_trans`。execve 无 domain transition 需要该权限，因此 EACCES。
- 结论：这不是 APK 打包/toolbox 文件可直接修复的问题；需要平台侧策略变更（或平台支持的可执行挂载）。按停止条件记录 `blocked_external`，未修改 root/SELinux/pvmfw，未用 `/system/bin/sh`、encrypted storage 执行、memfd、chmod 等绕过。

### 本轮变更与门禁

- `FastLaneDv01Contract` 默认端口改为 5100–5103；新增 JVM parity 测试读取 `runtime.h`；最终 20 轮不传 `qa*Port`，证明默认命令可用。
- `verify_payload_apk.py` 新增 v2+v3 签名检查；v2-only 负例被拒绝（`missing v3`）。
- payload 的 OPEN_PTY 失败消息加入 stage/errno（仅 ERROR message 文本，FLN1 格式/字节不变）；probe 只记录 sanitized class（如 `execve_errno_13`）。
- 零设备门禁：315 tests / 8 skipped / 0 failures；`verifyFastlanePayloadApk` / `verifyFastlaneToolboxApk` 通过。
- 最终真机运行：`outcome=DV01_CORE_PASS_TERMINAL_BLOCKED`；20/20 cycles pass，stop 264–391 ms，residual=false；APK `5eb462d0…`、payload `5a6566b6…`、toolbox manifest `e0352292…`；terminal/storage 未通过。

### 决策

- 保持 `dv01_core_pass_terminal_blocked`，不进入 DV-02，不宣称完整 DV-01。
- 下一步需要平台/系统侧决定：为 `microdroid_app` 授予 `execute_no_trans`（`system_file`），或提供平台支持的可执行挂载；之后重跑 terminal + storage marker/持久化 + 负例 + 20 轮。
- 本结论不改变 FLN1 wire、accepted ADR、版本号、release channel 或签名身份。

## 2026-10-22 DV-01A 合规路径研究结论

**Status 保持 `dv01_core_pass_terminal_blocked`；不进入 DV-02。**

- `/research` 结论（决策门 B）：Android 16 当前平台不存在仅靠 host/APK 打包/公开 AVF API 让 `microdroid_app` 执行 `/mnt/apk/bin` 的合规路径。根因仍是 guest SELinux：`coredomain` 对 `system_file` 有 `execute`、无 `execute_no_trans`，且无 `type_transition`；zipfuse 的 `bin/` 执行位只解决 Unix mode。
- 研究：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`
- 平台/ROM 变更请求：`docs/reports/orchestration/fastlane-dv01a-platform-change-request-20261022.zh-CN.md`
- 2026-10-22 最小复现（新 token、`qaCycleCount=1`、默认端口、无代码改动）：control HELLO/HELLO_OK 与 stop `CONFIRMED` 通过；terminal 仍为 `TERMINAL_ERROR_FLN1_7` / `fln1=7 execve_errno_13`。1-cycle 的 `outcome=FAILED` 是完整 20 轮与 terminal 要求的预期结果；20/20 权威结果仍为 2026-10-21。
- 本轮只提交研究/决策/报告与 ticket 更新；未改代码、FLN1 wire、accepted ADR、版本号、签名身份、release channel；未改 root/SELinux/pvmfw，未使用任何绕过。
- 解除条件不变：平台为 payload APK 文件类型授予 `execute_no_trans`（推荐专用 `microdroid_payload_file`），或提供等价的受支持可执行挂载；之后重跑 terminal + storage + 负例 + 20 轮。
