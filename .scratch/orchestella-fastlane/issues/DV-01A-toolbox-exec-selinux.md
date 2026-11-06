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

## 2026-11-04 真机复现（device-myron，小米 HyperOS OS4.0 / Android 17 API 37）——判定：仍阻塞

**Status 保持 `blocked_external`。** 在一台与前两轮不同平台的设备（别名 device-myron：小米 HyperOS OS4.0、Android 17 / API 37、security patch 2026-10-01、内核 6.12 GKI 4K 页、骁龙 SM8850 档、hypervisor=gunyah）上，用与本票 2026-10-21/22 证据完全相同的 QA probe 合同（`AvfQaProbeInstrumentation` mode=`fastlane-dv01-preflight`，1 cycle、默认端口 5100/5101、未改 APK/payload/平台）复现了同一阻塞签名：

- control HELLO/HELLO_OK 通过（3 ms）、payload ready 800 ms、create/run 全过；`terminal_pty` 返回 `blocked / TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`（EXEC_FAILED）、`exec_stage=execve`、`errno=13`（EACCES）、`output_bytes=0`——与 2026-10-21 的 `fln1=7 execve_errno_13` 逐字一致；
- sealed toolbox 未变：probe 复核的 `toolbox_manifest_sha256` = `e0352292…`，与本票 2026-10-21 权威证据相同；QA APK / payload .so 从 2026-10-24 构建复用（该日之后 payload 源码仅 distcc/cc1 变更，不触及 exec 路径）；
- 停止干净：`STOP_CONFIRMED` + `VM_DELETED`；outcome=`PREFLIGHT_TERMINAL_BLOCKED`。

### 本轮新增平台事实（非本票阻塞机制本身，但影响后续取证方式）

1. **guest 内核 audit 在该平台不可采**：受保护 VM 的 console 转发只到 pvmfw 阶段（`Console(2051): [INFO] pVM firmware … Starting payload...` 之后再无 guest 输出，内核为 quiet），host 侧 `dmesg` 受 SELinux 限制（`klogctl: Permission denied`）。因此本轮拿不到与 2026-10-21 同形的 raw avc 行；阻塞判定改由 payload 侧 typed 诊断（`fln1=7 execve_errno_13`，`realpath/stat/access(X_OK)` 通过而仅 `execve` 拒绝）承载，机制描述仍引用本票上方的 2026-10-21 avc 行。
2. **HyperOS freezer 与 QA instrumentation 互斥**（三轮实测）：app 侧在 VM 创建后约 5 秒被 HyperOS 冻结（`freezeUid SUCCESS uid=10054 reason=new process / tobg`，`PolicyMaker freeze_able`），crosvm 子进程随之停摆，`am instrument -w` 永久挂起；deviceidle 白名单**不能**豁免该冻结。可复现的处置是保持亮屏 + QA app 前台（TOP_APP 状态不满足 freeze_able），或按 UML 线已验证的 specialUse FGS + PARTIAL_WAKE_LOCK 方案。`run_dv01.py` 两轮均因此挂起被终止，本轮取证改用与 harness 完全同参的 `am instrument` 直跑（前台 + 亮屏守护循环）；这是 host 编排差异，device 侧命令逐字一致。
3. AVF 平台能力实测：App actor `getCapabilities` raw bits=1（protected=true / non-protected=false），`setProtectedVm(false)` REJECTED（UnsupportedOperationException）——设备 protected-only，custom Linux 门保持 `NO_NON_PROTECTED_VM`；两权限显式 grant 后 GRANTED，hidden API exemption INSTALLED。qualification 四阶段（platform_declaration/capabilities/permissions/protected_mode）pass，样本计数 `platform_qualification_samples=1`、其余 0。

### 原始证据（仓外，含真实标识）

`evidence/dv01a-20261104/`（外部 evidence 根，永不入库）：`probe-fastlane-dv01-preflight-<token>.json`（本轮 probe 报告原件；qaRunToken 不入库，token SHA-256=`338958e1…` 记录于外部 `device-alias-mapping-20261104.md`）、`avf-qualification/avf-device-validation-20261104T155948*.json/.md`（qualification harness 报告与 stage 证据）、`preflight-run4-window-slice.txt`（virtmgr/Console(2051)/freezeUid 关键行切片）、`logcat-during-preflight-20261104.txt`（全程捕获，含三轮 freeze 取证）、`pagesize-channels-20261104.txt`、`getprop-full-20261104.txt`、`device-alias-mapping-20261104.md`（真实序列号/型号仅存此目录）。

### 本轮未做

- 未修改 FLN1 wire、payload、SELinux/pvmfw、签名身份、版本号；
- 未使用任何绕过（/system/bin/sh、encrypted storage 执行、chmod、memfd、伪 shell 均未触碰）;
- 未进入 DV-02；20 轮、storage persistence、负例未测（本票仍 blocked_external，无 20 轮要求）；
- 未在 Pixel 系或 A17 QPR 上验证平台变更请求是否已被某次 OTA 满足（本设备 security patch 2026-10-01 的 HyperOS 未满足：执行仍被拒）。

## 关联

- DV-01：`.scratch/orchestella-fastlane/issues/DV-01-protected-payload-device.md`
- 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md` §7
- 本轮实施报告：`docs/reports/tickets/dv01a-mode-facts/implementation-report.zh-CN.md`
- P03 toolbox 报告：`docs/reports/tickets/fastlane-P03/implementation-report.zh-CN.md`
- 研究：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`
- 平台变更请求：`docs/reports/orchestration/fastlane-dv01a-platform-change-request-20261022.zh-CN.md`

## Comments

- 2026-11-07 / ZCode（设备窗 T6 / GLM-5.3-Flash）：**跨设备复现矩阵 +1（device-nova），判词：新形态**。设备别名 device-nova（Android 16 / API 36 / HONOR 系粗口径 / SM8735 / 4K 页 / protected-only 实测 REJECTED，非-protected `setProtectedVm(false)`→REJECTED 见同轮 qualification 落档）。原始证据仓外 `evidence/device-window-20261106/t6-dv01a/`。
  - **probe 运行**：QA 通道包 purpose=dv01a + `AvfQaProbeInstrumentation qaMode=fastlane-dv01-preflight`（1 cycle），返回 `PREFLIGHT_BLOCKED_EXTERNAL`；evidence schema `orchestella.fastlane.dv01.preflight.v1`：parameter_validation=`QA_ARGUMENTS_REJECTED`（完整 toolbox 合同未装配，qaToolboxSha 等参数缺位），create/run/payload_ready/control_hello/terminal_pty 全部 blocked。
  - **新形态实质**：本设备的阻塞比 2026-10-21 权威证据（myron：SELinux `execute_no_trans` execve EACCES）**更早更底层**——payload VM 在本机 hypervisor 层即崩溃：`crosvm: unknown gh exit reason: 3 → vcpu hit unknown error: Invalid argument (os error 22) → vcpu crashed → exiting with crash (exit 33)`，启动后 ~3.4s，payload 回调零发生（同轮 PvmProbe qaMode=compiler 取证，qaMemoryMb=1024/2048 均复现）。 toolbox exec 阶段不可达，本票签名（fln1=7 execve_errno_13）无法在本机复现亦无法证伪。
  - **矩阵现状**：① Pixel 系/先行设备（2026-10-21 权威证据，同签名阻塞）；② device-myron / HyperOS API 37（2026-11-04 同签名复现）；③ device-nova / API 36（2026-11-07 **新形态**：Gunyah vcpu crash，先于 SELinux 阶段）。
  - **佐证**：本机厂商自有受保护 VM（trustedvm/cpusys_vm）常驻正常 → hypervisor 本体可用，stock-AVF microdroid 崩溃；`qaDebugLevel=0` 时受保护 VM `VirtualMachineConfig.Builder.build()` 反常抛异常（本 ROM 似要求 debuggable initrd，与 AOSP 语义相反）——ROM virt 框架与 stock AVF 实现分歧的两条独立证据。
  - **结论**：Status 维持 **blocked_external** 不变；解除条件不因新设备改变；待该平台适配（ROM/Gunyah 上游）后以完整 toolbox 合同复跑（20 轮/storage/负例等要求不变）。
