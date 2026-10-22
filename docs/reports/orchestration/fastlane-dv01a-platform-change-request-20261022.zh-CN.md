# 平台 / ROM 变更请求：允许 protected Microdroid payload 执行 APK 内 sealed toolbox

日期：2026-10-22 ｜ 请求方：Orchestella Fast Lane（DV-01 / DV-01A）｜ 状态：`blocked_external`，等待平台 / ROM 决策
关联研究：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`

---

## 1. 请求摘要

请为 Android 16 Microdroid 的 `microdroid_app` 域提供一种**受支持的、可执行 payload APK 内文件**的机制，使 protected VM 中的 payload 能执行 `/mnt/apk/bin/sh`（sealed toolbox）而不需要 root、SELinux 放宽、pvmfw 修改或 privileged helper。

最小可接受变更：给 `microdroid_app` 对 payload APK 文件类型授予 `execute_no_trans`。推荐用**专用文件类型**限定范围，而不是放开整个 `system_file`。

这不是为了绕过平台策略；当前阻塞是 AOSP 16 guest policy 的一个缺口：zipfuse 明确为 `bin/` 设置执行位，但 `microdroid_app` 只被授予 `execute`、没有被授予 `execute_no_trans`，因此 `execve` 必然返回 `EACCES`。

---

## 2. 影响范围

| 项 | 值 |
| --- | --- |
| 设备 | `qa-alt-01`（API 36 / Android 16，arm64-v8a，`ro.build.type=user`；只使用别名） |
| 平台 | AOSP 16 + 设备 OEM ROM；AVF `com.android.virt` versionCode=3；vendor API level 202504 |
| 应用 | 隔离 QA 包 `io.github.nyzeep.orchestella.qa.dv01`，protected Microdroid payload |
| APK SHA256 | `5eb462d0f4059623ba5a1e5aafdc019e6bfd9a0a4ab995d78b3ddd5d45f8ede6` |
| payload（APK 内） SHA256 | `5a6566b648c3410c46eef821052b0f5acaa79cb1d0817fece410b8bb7c44fb07` |
| toolbox manifest SHA256 | `e03522928386755676cb196a9826c1f7da51240e5be63e4f283b8c7447edae6b` |
| VM | protected=true，1 vCPU，1024 MB，256 MB encrypted storage，DEBUG_NONE |
| 受影响阶段 | DV-01 terminal PTY、storage marker、stop→start 持久化；因此 DV-01 不能完整通过，DV-02 不能开始 |

---

## 3. 复现与证据（脱敏）

### 3.1 2026-10-21 最终运行

- `outcome=DV01_CORE_PASS_TERMINAL_BLOCKED`；
- create/run/payloadReady 成功（约 0.8–1.4 s）；
- control HELLO/HELLO_OK 成功（caps=45，6 ms，`auth_token=empty`）；
- stop first/second 均 `CONFIRMED`；20/20 create/run/stop/delete pass，stop 264–391 ms，每轮 `residual=false`；
- terminal：`TERMINAL_ERROR_FLN1_7`，detail `fln1=7 execve_errno_13`；storage `not-tested`。

### 3.2 分层诊断

| 检查 | 结果 |
| --- | --- |
| `realpath("/mnt/apk/bin/sh")` | 成功 |
| `stat` | 成功（常规文件） |
| `access(X_OK)` | 成功 |
| `execve` | `EACCES(13)` |
| 指向 `bin/manifest.json`（非 ELF） | 仍 `EACCES(13)`，不是 `ENOEXEC(8)` |
| noexec 挂载 | 排除（`access(X_OK)` 成功；Linux `access()` 遵守 `MS_NOEXEC`） |
| 文件缺失 / ZIP mode 0 | 排除（文件存在；zipfuse 对 `bin/` 默认赋执行位） |

### 3.3 Guest audit（直接证据）

```text
avc: denied { execute_no_trans } for path="/mnt/apk/bin/sh" dev="fuse"
scontext=u:r:microdroid_app:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

### 3.4 2026-10-22 最小复现

使用新的 16-hex token、`qaCycleCount=1`（不是 20 轮长跑），默认端口 5100–5103，未修改 APK/payload/平台：

- `control HELLO/HELLO_OK`：pass（caps=45）；
- `stop_first`：`STOP_CONFIRMED`；
- `terminal_pty`：`TERMINAL_ERROR_FLN1_7`，detail 仍为 `fln1=7 execve_errno_13`；
- 该 1-cycle 运行的 `outcome=FAILED` 是 harness 对“完整 20 轮 + terminal”要求的预期结果，不代表平台行为改变；20/20 的权威结果仍是 2026-10-21 运行。

原始设备报告仅存于外部 artifact 根，不进入仓库。

---

## 4. 根因（精确到规则）

### 4.1 挂载 context

`packages/modules/Virtualization/guest/microdroid_manager/src/main.rs` 在启动 payload 前挂载主 APK：

```rust
zipfuse.mount(
    MountForExec::Allowed,
    "fscontext=u:object_r:zipfusefs:s0,context=u:object_r:system_file:s0",
    ...
)
```

- `MountForExec::Allowed` = 不加 `--noexec`；
- `/mnt/apk` 下所有文件（含 `bin/`）统一标为 `system_file`；
- 没有 API、payload config 字段或 host 参数可以修改这个 context。

### 4.2 权限差异

`system/sepolicy/microdroid/system/private/domain.te`：

```te
allow coredomain system_file:file { execute read open getattr map };
```

`microdroid_app` 是 `coredomain`，所以：

- `execute`：允许 `mmap(PROT_EXEC)`，也允许 `access(X_OK)` 通过；
- 没有 `execute_no_trans`：`execve` 在不发生 domain transition 时被拒绝；
- 没有 `type_transition microdroid_app system_file:...`：`execve` 不会进入其它域，因此必须走 `execute_no_trans`。

`system/sepolicy/microdroid/system/private/microdroid_app.te` 全文没有 `system_file` 的执行规则；唯一的执行转换是 manager → launcher：

```te
# microdroid_manager.te
domain_auto_trans(microdroid_manager, microdroid_app_exec, microdroid_app)
```

`/system/bin/microdroid_launcher` 被标为 `microdroid_app_exec`（`file_contexts`），APK 内容不是。

### 4.3 为什么 zipfuse 的 `bin/` 执行位没有用

`guest/zipfuse/src/inode.rs` 明确对 `bin/` 使用 `EXECUTABLE_FILE_MODE`，注释说明这是为了让 payload 携带可执行文件（并引用 authfs 的 `measure_io` 测试）。这只设置 Unix mode，不改变 SELinux label；因此 `access` 成功、`execve` 仍被 policy 拒绝。

---

## 5. 为什么 host / APK 侧不能修

- **打包**：ZIP `external_attr` mode 0 或 0755 都不影响 `system_file` label；项目已验证 0755 路径仍被拒绝。
- **挂载**：context 由 `microdroid_manager` 硬编码；`VirtualMachineConfig` 没有 mount/context/exec 参数。
- **API**：`setPayloadBinaryName` 只接受 `lib/<ABI>` 下的 `.so`；`setPayloadConfigPath` 的 `TaskType::Executable` 由 `microdroid_manager` 执行，受 `neverallow microdroid_manager ... execute_no_trans` 限制；`addExtraApk` 在 A16 非 multi-tenant 时是 `--noexec`，且 `extra_apk_file` 只有读权限。
- **debug**：`DEBUG_FULL` 只提供日志/adb/root，Javadoc 明确可能绕过 protected VM 保护；不是生产路径，且属于 SELinux 绕过。
- **custom image**：是独立 OS 镜像，不是执行 APK 内容的机制；需要 `USE_CUSTOM_VIRTUAL_MACHINE`，protected 还需 pvmfw 信任的签名。
- **vendor policy**：guest policy 编译进 `microdroid_precompiled_sepolicy`，打包进 AVB 签名的 microdroid system image / `com.android.virt` APEX；host vendor sepolicy 不进入 guest。A16 该 APEX `updatable:false`，因此需要平台 OTA 或 OEM 重建系统镜像。

---

## 6. 请求的变更

### 6.1 方案 A（最小、较宽，仅建议用于验证）

`system/sepolicy/microdroid/system/private/microdroid_app.te`：

```te
# Allow the payload to exec binaries bundled in its own APK (zipfuse /mnt/apk).
allow microdroid_app system_file:file execute_no_trans;
```

- 直接消除 AVC；
- 但 `system_file` 覆盖整个 guest system image，语义过宽；`microdroid_app` 本来就能运行任意 payload 代码，所以这不是域逃逸，但仍不是最小权限。

### 6.2 方案 B（推荐）

1. 新增 payload APK 专用类型（`system/sepolicy/microdroid/system/private/file.te`）：

```te
type microdroid_payload_file, file_type;
```

2. 主 APK 挂载改用该 context（`guest/microdroid_manager/src/main.rs`）：

```text
fscontext=u:object_r:zipfusefs:s0,context=u:object_r:microdroid_payload_file:s0
```

3. 允许 zipfuse 关联该文件系统（`zipfuse.te`）：

```te
allow microdroid_payload_file zipfusefs:filesystem associate;
```

4. 给 `microdroid_app` 对自身 APK 的完整权限（`microdroid_app.te`）：

```te
allow microdroid_app microdroid_payload_file:file {
    getattr open read map execute execute_no_trans
};
```

5. 如 debug shell 仍需运行 `bin/` 工具（例如 authfs 测试）：

```te
allow shell microdroid_payload_file:file rx_file_perms;
```

6. 保留 `neverallow microdroid_manager { file_type fs_type }:file execute_no_trans;`；保留 `system_file` 现有规则；新增负例，确保其它域不能执行 `microdroid_payload_file`。

**为什么推荐 B**：权限限定在 payload APK，不放开整个 system image；sealed toolbox 仍只读；未来 tenant/多租户可复用同一类型思路；不需要新增宿主权限。

### 6.3 方案 C（长期）

参考 Android 17 的 `tenant_apk_file` + `allow microdroid_app tenant_apk_file:file x_file_perms`，把“专用 APK 文件类型 + x_file_perms”模式用于主 payload APK。该方案涉及 A17 的多租户/AISeal 配置，不建议回移到 A16。

### 6.4 发布路径

| 层级 | 说明 |
| --- | --- |
| policy 源 | `system/sepolicy/microdroid/system/private/*.te` |
| manager | `packages/modules/Virtualization/guest/microdroid_manager/src/main.rs` |
| 编译 | `microdroid_precompiled_sepolicy` → AVB `microdroid` system image → `microdroid_super` / `microdroid_vbmeta` |
| 分发 | `com.android.virt` APEX；AOSP 16 构建配置为 `updatable:false`，设备上解析到 `/system/apex`（system APEX）。因此随平台 OTA 或 OEM 系统镜像发布，不能通过 Play/APEX 热更新 |
| vendor | host vendor sepolicy 无效；必须重建 guest/AVF 镜像 |

---

## 7. 验证计划

### 7.1 Host / 构建

- `microdroid_precompiled_sepolicy` 编译通过，无 neverallow 失败；
- 新增 SELinux 测试：`microdroid_app` 可对 `microdroid_payload_file` 执行 `execute_no_trans`；其它域（如 `shell` 之外的普通域）不能；
- APK/payload 门禁不变：stored、16 KB、`AVmPayload_main`、无 `libvm_payload.so`、v2+v3；
- 零设备测试无回归（当前基线 315 tests / 8 skipped / 0 failures）。

### 7.2 设备（`qa-alt-01`，只写别名）

- [ ] guest audit 不再出现 `execute_no_trans` denial；
- [ ] `/mnt/apk/bin/sh -c 'printf DV01_TERMINAL_OK; ...'` 退出码 0，输出 `DV01_TERMINAL_OK`；
- [ ] encrypted storage marker 写入/读取成功；
- [ ] stop → start 后 marker 保留；
- [ ] 20/20 create/run/stop/delete，stop `CONFIRMED`，residual=false；
- [ ] 负例有 typed rejection；`wrong_payload` 不再是 `unconfirmed`；
- [ ] 失败分类保持六类明确状态；
- [ ] 设备 JSON `outcome=PASS`；
- [ ] 未改 FLN1 wire、accepted ADR、版本号、签名身份、release channel；
- [ ] 证据仍只有别名，不含 serial/IMEI/Android ID/build fingerprint/账号/token/按键正文/截图/完整 logcat。

---

## 8. 安全影响

- protected VM 边界不变；变更只影响 guest 内的 `microdroid_app`。
- payload 已经能运行任意 native code（`.so` + `dlopen`/`mmap exec`），因此 `execute_no_trans` 不是域逃逸；它增加的是“同域子进程/新进程映像”的能力，这是 shell 的语义所需。
- 方案 B 把权限限定在 payload APK，避免整个 `system_file` 可执行；sealed toolbox 仍只读、不可写、不在 encrypted storage。
- 不新增宿主权限、root、pvmfw 或 SELinux 放宽；不改变 FLN1 wire。
- 残余风险：同域 shell 子进程继承 `microdroid_app` 的权限集合。这是该域既有的信任边界；若需更强隔离，可在方案 B 上增加专用 `microdroid_toolbox` exec 域，但需要为 `bin/` 单独挂载/标注。

---

## 9. 非目标 / 明确拒绝

- 不修改 root / SELinux / pvmfw；不使用 `setenforce 0` / `adb root` + `su`；
- 不使用 `/system/bin/sh`、`/apex/.../sh` 替代 sealed toolbox；
- 不在 encrypted storage 执行；
- 不 chmod / 复制到可执行路径 / memfd / execveat / 伪 shell；
- 不修改 FLN1 wire 或把 exec 语义替换为进程内解释器；
- 不把 `DEBUG_FULL` 的 adb shell/root 当作生产路径；
- 不把 custom image 当作执行 APK 内容的替代；
- 不在 A16 上模拟 A17 tenant 机制。

---

## 10. 参考

- 研究全文：`docs/research/microdroid-exec-selinux-20261022.zh-CN.md`
- DV-01A：`.scratch/orchestella-fastlane/issues/DV-01A-toolbox-exec-selinux.md`
- DV-01：`.scratch/orchestella-fastlane/issues/DV-01-protected-payload-device.md`
- DV-01 报告：`docs/reports/orchestration/fastlane-dv01-20261021.zh-CN.md`
- P03 报告：`docs/reports/tickets/fastlane-P03/implementation-report.zh-CN.md`
- AOSP 16 policy：`system/sepolicy/microdroid/system/private/{microdroid_app,domain,microdroid_manager,microdroid_payload,zipfuse,shell}.te`、`microdroid/system/public/{global_macros,file}.te`
- AOSP 16 AVF：`packages/modules/Virtualization/{guest/microdroid_manager/src/main.rs,guest/zipfuse/src/*.rs,libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java,build/microdroid/Android.bp,build/apex/Android.bp}`
- Android 17 参考：`system/sepolicy/microdroid/system/private/{microdroid_app,zipfuse,file}.te`（tenant APK）
