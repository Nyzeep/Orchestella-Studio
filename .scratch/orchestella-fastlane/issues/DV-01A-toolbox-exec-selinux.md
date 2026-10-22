# DV-01A: toolbox `bin/sh` guest exec is blocked by Microdroid SELinux

Type: external-blocker
Status: blocked_external
Priority: P0（DV-01 terminal/storage 的前置）
Owner: 平台/ROM 决策方（外部）；主协调跟踪
Blocked by: Android 16 `microdroid_app` SELinux policy（缺少 `execute_no_trans`）

## 结论

DV-01 的 terminal PTY 在 `/mnt/apk/bin/sh` 上返回 FLN1 7（`EXEC_FAILED`）。DV-01A 已把失败定位到 `execve` 的 SELinux 拒绝，而不是文件缺失、ZIP 执行位或 noexec 挂载：

```text
avc: denied { execute_no_trans } for path="/mnt/apk/bin/sh" dev="fuse"
scontext=u:r:microdroid_app:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

因此这是平台策略阻塞，需要平台/系统侧变更或平台支持的可执行挂载；本轮没有修改 root/SELinux/pvmfw，也没有用替代解释器或 encrypted storage 执行绕过。

## 证据

- 默认端口 5100/5101 下 control HELLO/HELLO_OK 成功（6 ms），terminal vsock connect 成功。
- `OPEN_PTY` 的 stage/errno 诊断：`realpath`、`stat`、`access(X_OK)` 成功，`execve` 返回 EACCES(13)。
- APK `bin/sh` 存在（1,115,944 bytes），`external_attr` mode 0；zipfuse 对 `bin/` 赋默认执行位，且 `access(X_OK)` 成功证明实际 mode 可执行。
- `/mnt/apk/bin/manifest.json`（普通非 ELF 文件，位于 `bin/`）也返回 EACCES 而非 ENOEXEC；`access()` 在 noexec 挂载上会失败，因此排除 noexec。
- AOSP A16 policy：`allow coredomain system_file:file execute` 存在（access 通过），但没有给 `microdroid_app` 对 `system_file` 的 `execute_no_trans`；execve 无 domain transition 时必须检查该权限。
- zipfuse 以 `context=u:object_r:system_file:s0` 挂载 `/mnt/apk`；payload 进程域为 `microdroid_app`。

证据文件（外部，脱敏）：`protected-fastlane/20261021/dv01/terminal-failure.txt`、`selinux-exec-denial.txt`、`diagnostics/diag-*.json`。

## 明确不接受的绕过

- 不改 SELinux policy、pvmfw 或 payload 域；
- 不用 `/system/bin/sh`、`/apex/.../sh` 替代 sealed toolbox；
- 不在 encrypted storage 执行（协议规定不可执行）；
- 不 chmod guest 文件、不 memfd/execveat、不复制到其它可执行路径；
- 不改 FLN1 wire 或把 exec 语义替换为进程内伪 shell。

这些都会绕过 sealed toolbox 的隔离承诺，或需要平台级权限，按 DV-01 停止条件不执行。

## 解除条件与下一步

1. 平台/系统侧为 `microdroid_app` 授予对 `system_file` 的 `execute_no_trans`（或提供平台支持的、可执行的 APK 内容挂载）；
2. 重新安装 APK，验证 `/mnt/apk/bin/sh -c 'printf DV01_TERMINAL_OK'` 返回 exit 0 且有输出；
3. 再跑 storage marker 写/读、stop→start 持久化、负例和完整 20 轮；
4. terminal/storage 全通过后才把 DV-01 更新为 `PASS`，才进入 DV-02。

## 验收

- [ ] guest audit 不再出现 `execute_no_trans` denial；
- [ ] terminal 输出 `DV01_TERMINAL_OK`，退出码 0；
- [ ] storage marker 跨 stop/start 保留；
- [ ] 20/20 cycles pass，stop CONFIRMED，无 residual；
- [ ] 证据仍只使用设备别名，不含 serial/IMEI/Android ID/账号/token/按键正文/截图/完整 logcat。

## 2026-10-22 合规路径研究：无 host 侧解法，转平台/ROM 变更请求

**Status 保持 `blocked_external`。决策门 B：Android 16 当前平台不存在合规路径。**

研究全文：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`
平台变更请求：`docs/reports/orchestration/fastlane-dv01a-platform-change-request-20261022.zh-CN.md`

### 结论

- `/mnt/apk` 的 context 由 `microdroid_manager` 硬编码为 `system_file`；`VirtualMachineConfig`、payload config、extra APK 均无修改挂载 context 或 exec 语义的开关。
- A16 policy 对 `coredomain` 只授予 `system_file:file { execute read open getattr map }`（`microdroid/system/private/domain.te:92`），没有 `execute_no_trans`；也没有 `system_file` 的 `type_transition`。`access(X_OK)` 只检查 `execute`，`execve` 无域转换则需要 `execute_no_trans`，所以 EACCES 是必然结果。
- `microdroid_app.te` 没有 `system_file` 执行规则；唯一的执行转换是 `domain_auto_trans(microdroid_manager, microdroid_app_exec, microdroid_app)`（`microdroid_manager.te:57`），仅适用于 `/system/bin/microdroid_launcher`。
- `TaskType::Executable` 由 `microdroid_manager` 执行，受 `neverallow microdroid_manager { file_type fs_type }:file execute_no_trans`（`microdroid_manager.te:151-152`）限制；且是 `@TestApi` + `USE_CUSTOM_VIRTUAL_MACHINE`。
- extra APK 在 A16 非 multi-tenant 构建中 `--noexec`，`extra_apk_file` 只有读权限；`DEBUG_FULL` 明确可能绕过 protected VM 保护；custom image 是独立 OS 镜像且受 pvmfw 签名约束；A17 `tenant_apk_file` 不适用于 A16 主 payload。
- guest policy 编译进 `microdroid_precompiled_sepolicy`，打包进 AVB 签名的 microdroid system image / `com.android.virt` APEX；A16 该 APEX `updatable:false`，host vendor sepolicy 不进入 guest。

### 2026-10-22 最小复现

新的 16-hex token、`qaCycleCount=1`、默认端口、未改 APK/payload/平台：control HELLO/HELLO_OK 与 stop `CONFIRMED` 通过；`terminal_pty` 仍为 `TERMINAL_ERROR_FLN1_7` / `fln1=7 execve_errno_13`。1-cycle 运行的 `outcome=FAILED` 是 harness 对完整 20 轮 + terminal 的要求所致；20/20 权威结果仍为 2026-10-21 运行。

### 请求的平台变更（精确）

- 最小：`allow microdroid_app system_file:file execute_no_trans;`
- 推荐：新增 `microdroid_payload_file`（`file_type`），主 APK 以 `context=u:object_r:microdroid_payload_file:s0` 挂载，`zipfuse` 关联该 fs，`microdroid_app` 获得 `{ getattr open read map execute execute_no_trans }`；保留 manager 的 neverallow。
- 发布路径：平台 OTA / OEM 重建 microdroid 镜像；不能通过 Play/APEX 热更新。

### 本轮未做

- 未修改 FLN1 wire、accepted ADR、版本号、签名身份、release channel；
- 未改 root/SELinux/pvmfw，未使用 `/system/bin/sh`、encrypted storage 执行、chmod、memfd、execveat、伪 shell；
- 未进入 DV-02。

## 关联

- DV-01：`.scratch/orchestella-fastlane/issues/DV-01-protected-payload-device.md`
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md` §7
- P03 toolbox 报告：`docs/reports/tickets/fastlane-P03/implementation-report.zh-CN.md`
- 研究：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`
- 平台变更请求：`docs/reports/orchestration/fastlane-dv01a-platform-change-request-20261022.zh-CN.md`
