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

## 关联

- DV-01：`.scratch/orchestella-fastlane/issues/DV-01-protected-payload-device.md`
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md` §7
- P03 toolbox 报告：`docs/reports/tickets/fastlane-P03/implementation-report.zh-CN.md`
