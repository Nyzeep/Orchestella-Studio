# Android 16 Protected Microdroid 执行 sealed toolbox 的合规路径研究

日期：2026-10-22 ｜ 分支：`device/dv01a-selinux-followup` ｜ 关联：DV-01A / DV-01
结论：**在 Android 16 当前平台（API 36，AOSP 16 + 设备 ROM）上，不存在仅靠 host / APK 打包 / 公开 AVF API 就能让 protected Microdroid payload 执行 `/mnt/apk/bin/sh` 的合规路径。** 这是 guest 侧 SELinux 策略缺口，需要平台 / ROM / AVF 镜像变更。本文不提出、也不实现任何绕过。

---

## 0. 一句话结论

`/mnt/apk` 由 zipfuse 以 `context=u:object_r:system_file:s0` 挂载，payload 运行在 `u:r:microdroid_app:s0`。Android 16 的 policy 只给 `coredomain`（含 `microdroid_app`）对 `system_file` 的 `execute`，没有 `execute_no_trans`；由于不存在 `system_file` 到其它域的 `type_transition`，`execve` 必须走“无域转换”路径，于是被 `execute_no_trans` 拒绝，返回 `EACCES(13)`。

- `access(X_OK)` 成功、`execve` 失败，是同一策略下的预期不对称，不是打包/挂载/文件损坏。
- `bin/` 的 zipfuse 执行位是 AOSP 有意的设计（`guest/zipfuse/src/inode.rs`），但它只解决 Unix mode，不解决 SELinux。
- AVF 公开 API 没有“修改 APK 挂载 context”或“标记 APK 内容可执行”的参数。
- extra APK、`DEBUG_FULL`、custom image、A17 tenant APK、vendor policy 都不能在 Android 16 上构成合规路径。
- 需要平台变更：最窄的规则是给 `microdroid_app` 对 payload APK 文件类型的 `execute_no_trans`（推荐用专用 type，而不是放开整个 `system_file`）。

---

## 1. 问题、约束与停止条件

目标问题：在 **protected VM** 内，让 payload 执行 **sealed toolbox**（APK 根 `bin/` 下的只读 BusyBox 与 wrapper），支撑 FLN1 terminal 的 `/mnt/apk/bin/sh -c ...` 语义。

硬约束（来自 DV-01A 与本轮交接）：

- 不修改 root / SELinux / pvmfw；不使用 `/system/bin/sh`、`/apex/.../sh` 替代 sealed toolbox；
- 不在 encrypted storage 执行；不 chmod / memfd / execveat / 复制到其它可执行路径 / 进程内伪 shell；
- 保持 protected VM、sealed toolbox 只读、FLN1 wire 与 terminal 语义；
- 如果不存在合规路径，停止代码修改，产出平台 / ROM 变更请求，DV-01A 保持 `blocked_external`。

本文只回答“是否存在合规机制”，不把“技术上能绕过去”当作答案。

---

## 2. 设备与证据基线（2026-10-21，2026-10-22 复核）

设备别名 `qa-alt-01`，API 36，arm64-v8a，`ro.build.type=user`，声明 `android.software.virtualization_framework`。以下为脱敏事实，原始设备证据在外部 artifact 根。

2026-10-21 最终运行：

- `outcome=DV01_CORE_PASS_TERMINAL_BLOCKED`；create/run/payloadReady 成功；control HELLO/HELLO_OK 成功（caps=45，6 ms，`auth_token=empty`）；stop 均 `CONFIRMED`；20/20 cycles pass，residual=false。
- terminal：`TERMINAL_ERROR_FLN1_7`，detail `fln1=7 execve_errno_13`；storage marker/persistence `not-tested`。
- 分层诊断：`realpath`、`stat`、`access(X_OK)` 全部成功，失败只在 `execve`。
- 差分：把 OPEN_PTY 指向 `/mnt/apk/bin/manifest.json`（`bin/` 下的非 ELF 文件）仍得到 `EACCES(13)` 而非 `ENOEXEC(8)`；`access()` 在 noexec 挂载上会失败，因此排除 noexec。
- guest audit 直接证据：

```text
avc: denied { execute_no_trans } for path="/mnt/apk/bin/sh" dev="fuse"
scontext=u:r:microdroid_app:s0 tcontext=u:object_r:system_file:s0 tclass=file permissive=0
```

2026-10-22 复核：设备在线、QA app/test APK 已安装；本轮用新的 16-hex token、`qaCycleCount=1` 做了一次最小复现（不是 20 轮长跑），结果仍为 `TERMINAL_ERROR_FLN1_7` / `fln1=7 execve_errno_13`（该 1-cycle 运行的 `outcome=FAILED` 是 harness 对完整 20 轮 + terminal 要求的预期结果，不代表平台行为改变）。本轮没有修改 APK、payload、SELinux、pvmfw 或 root 状态。

证据位置（外部，不入库）：

```text
protected-fastlane/20261021/dv01/{fastlane-dv01-20261021-final.json,terminal-failure.txt,selinux-exec-denial.txt,cycles-20.tsv}
protected-fastlane/20261022/dv01a/  # 本轮 decision 文档与最小复现摘要
```

---

## 3. SELinux 语义：为什么 access 成功、execve 失败

### 3.1 两个不同的权限

- `file:execute`：文件可以作为可执行映像被 `execve`，或被 `mmap(PROT_EXEC)` 映射。
- `file:execute_no_trans`：`execve` 时不发生 domain transition、继续留在当前域，所额外需要的权限。
- `file:entrypoint`：当 `type_transition` 把执行导向新域时，新域需要该文件的 `entrypoint`。

因此 `execve` 的检查不是只看 `execute`：没有域转换时还要 `execute_no_trans`；有域转换时则要目标域的 `entrypoint`。`access(X_OK)` 只检查 `execute`，所以它可以在 `execve` 仍被拒绝时成功。

### 3.2 Android 16 的实际规则

A16 `system/sepolicy/microdroid/system/private/domain.te:92`：

```te
allow coredomain system_file:file { execute read open getattr map };
```

`microdroid_app` 被声明为 `coredomain`（`microdroid_app.te:9`），所以它得到 `execute/read/open/getattr/map`：这解释了 `access(X_OK)` 成功，也解释了 payload 能通过 `dlopen`/`mmap` 执行 APK 里的 `.so`。

A16 `microdroid/system/private/microdroid_app.te` 全文没有任何 `system_file` 的 `execute_no_trans` 或 `type_transition`；它只有：

```te
type microdroid_app, domain, coredomain, microdroid_payload;
type microdroid_app_exec, exec_type, file_type, system_file_type;
allow microdroid_app kmsg_device:chr_file w_file_perms;
allow microdroid_app kmsg_debug_device:chr_file w_file_perms;
allow microdroid_app apex_mnt_dir:dir r_dir_perms;
```

全树也没有 `type_transition microdroid_app system_file:...`（因此 `execve` 无法转入别的域）。

作为对照，A16 `microdroid/system/private/shell.te:16-17` 给 debug shell 的权限是：

```te
r_dir_file(shell, system_file)
allow shell system_file:file x_file_perms;
```

而 `microdroid/system/public/global_macros:24` 定义：

```te
define(`x_file_perms', `{ getattr execute execute_no_trans map }')
```

即 `shell` 有 `execute_no_trans`，`microdroid_app` 没有。这解释了为什么基于 adb shell 的测试（例如 authfs 的 `measure_io`）能跑 `bin/` 下的程序，而 payload 不能。

### 3.3 为什么没有“默认转换”

从 `microdroid_manager` 到 `microdroid_app` 的唯一转换是：

```te
# microdroid/system/private/microdroid_manager.te:57
domain_auto_trans(microdroid_manager, microdroid_app_exec, microdroid_app)
```

且 `/system/bin/microdroid_launcher` 被标为 `microdroid_app_exec`（`microdroid/system/private/file_contexts:102`）。也就是说，平台只允许“manager 执行 launcher → 进入 microdroid_app”这一条固定转换；APK 内的文件不是 `microdroid_app_exec`，不会触发这条转换。

---

## 4. zipfuse 挂载：执行位没问题，context 才是问题

### 4.1 `bin/` 的执行位是 AOSP 有意的

A16 `guest/zipfuse/src/inode.rs:199-205`：

```rust
let mut file_mode = DEFAULT_FILE_MODE;
if path.starts_with("bin/") {
    // Allow files under bin to have execute permission, this enables payloads to bundle
    // additional binaries that they might want to execute.
    // An example of such binary is measure_io one used in the authfs performance tests.
    // More context available at b/265261525 and b/270955654.
    file_mode = EXECUTABLE_FILE_MODE;
}
```

并且如果 ZIP entry 自带 Unix mode，zipfuse 会优先使用它（`inode.rs:233-235`）。项目 APK 的 `bin/sh` 的 ZIP `external_attr` mode 为 0，因此落到 `EXECUTABLE_FILE_MODE`；这与设备上 `stat`/`access(X_OK)` 成功一致。

### 4.2 挂载参数与 context

A16 `guest/microdroid_manager/src/main.rs:366-375`：

```rust
zipfuse.mount(
    MountForExec::Allowed,
    "fscontext=u:object_r:zipfusefs:s0,context=u:object_r:system_file:s0",
    Path::new(verify::DM_MOUNTED_APK_PATH),
    Path::new(VM_APK_CONTENTS_PATH),
    "microdroid_manager.apk.mounted".to_owned(),
)?;
```

`MountForExec::Allowed` 表示不传 `--noexec`；`guest/zipfuse/src/main.rs:110-115` 的挂载标志是 `MS_NOSUID|MS_NODEV|MS_RDONLY`，只有显式 `--noexec` 才加 `MS_NOEXEC`。所以：

- 不是 noexec；
- 不是 ZIP mode 0；
- 不是文件缺失；
- 文件标签由挂载参数固定为 `system_file`，与 ZIP 内容无关，host/APK 无法改变。

### 4.3 zipfuse 的 policy 明确按 system_file 处理

A16 `microdroid/system/private/zipfuse.te:26-34`：

```te
# allow mounting with context=u:object_r:system_file:s0 so that files provided
# by zipfuse are treated the same as the other files in /system or /apex
allow system_file zipfusefs:filesystem associate;
```

这不是“APK 可执行文件类型”，而是把 APK 内容当作系统文件处理；因此它继承的是 `system_file` 的权限集合，而不是 `shell_exec`/`microdroid_app_exec` 的 exec 语义。

---

## 5. 公开 API / 打包层有没有开关

### 5.1 `VirtualMachineConfig` 没有挂载 context 或 exec 开关

A16 `libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java` 的相关 API：

| API | 行号 | 能做什么 | 不能做什么 |
| --- | --- | --- | --- |
| `setPayloadBinaryName` | 1162 | 指定 `lib/<ABI>/` 下的 shared library | 不接受路径，不能指向 `bin/` 或修改 mount |
| `setPayloadConfigPath` | 1132 | 指定 payload JSON config（`TaskType::Executable`/`MicrodroidLauncher`） | `@TestApi` + `USE_CUSTOM_VIRTUAL_MACHINE`；仍然使用同一挂载 context |
| `addExtraApk` | 1116 | 附加 APK（解包挂载） | A16 非 multi-tenant 时 `--noexec`；`extra_apk_file` 只有读权限 |
| `setCustomImageConfig` | 1145 | 自定义 kernel/initrd/disks | 独立 OS，不是执行 APK 内容；需 `USE_CUSTOM_VIRTUAL_MACHINE`，protected 还需 pvmfw 信任的签名 |
| `setDebugLevel` | 1187 | `DEBUG_FULL` 导出日志、允许 adb/root | 明确“可能绕过 protected VM 保护”；不是生产路径 |
| `setProtectedVm` | 1211 | protected 开关 | 不改变 guest policy |
| `setEncryptedStorageBytes` | 1292 | encrypted storage | policy 明确 neverallow 从 encrypted storage 执行 |

`setPayloadBinaryName` 的 Javadoc 还明确要求文件位于 `lib/<ABI>`；不接受 `/`（`:1164-1167`）。payload config schema（`libs/libmicrodroid_payload_metadata/config/src/lib.rs:77-98`）只有 `TaskType::Executable` / `MicrodroidLauncher` 与 `command`，没有 mount/context/exec 字段。

### 5.2 `TaskType::Executable` 也救不了

`TaskType::Executable` 让 manager 直接 `Command::new(&task.command)`（`guest/microdroid_manager/src/main.rs:669-672`），执行者是 `microdroid_manager` 域。A16 `microdroid_manager.te:151-152`：

```te
neverallow microdroid_manager { file_type fs_type }:file execute_no_trans;
```

且 manager 只允许转换到固定白名单（`microdroid_manager.te:153-160`：`crash_dump`、`microdroid_payload`、`apkdmverity`、`encryptedstore`、`zipfuse`、`kexec` 等）。APK 文件是 `system_file`，不在转换白名单里；因此 `TaskType::Executable` 指向 `/mnt/apk/bin/sh` 一样会被拒绝。它还是 `@TestApi` 且要求 `USE_CUSTOM_VIRTUAL_MACHINE`，不属于普通 app 的生产路径。

### 5.3 extra APK 不是可执行路径

A16 `guest/microdroid_manager/src/main.rs:483-499`：extra APK 在非 `multi_tenant` 构建中使用 `MountForExec::Disallowed`（即 `--noexec`），context 为 `extra_apk_file`。A16 `microdroid_payload.te:41-42` 只给 `r_dir_file(microdroid_payload, extra_apk_file)`（读/映射，无 execute）；`microdroid/system/public/file.te:13` 也把 `extra_apk_file` 声明为普通 `file_type`，不是 `system_file_type`。同时 `addExtraApk` 的 Javadoc 明确“VM entry point must be in the main APK”。

### 5.4 A17 的 tenant APK 是另一条产品线

Android 17 的 policy 新增：

```te
# microdroid/system/private/microdroid_app.te（A17，GrapheneOS 17 镜像）
allow microdroid_app tenant_apk_file:file x_file_perms;
```

并新增 `tenant_apk_file` 类型、zipfuse 的 tenant 挂载规则（`g17_zipfuse.te:46-48`）。但：

- 这是 **tenant APK**（多租户 / AISeal 路线）的机制，不是主 payload APK；
- A16 没有 `tenant_apk_file`，也没有对应 API；
- 即使 A17，主 payload APK 仍然以 `system_file` 挂载，仍没有 `system_file:execute_no_trans`。

因此不能把它当作 Android 16 的合规路径。

---

## 6. 其它候选机制逐项排除

| 机制 | 是否合规 | 是否 protected | 是否改平台 | 结论 |
| --- | --- | --- | --- | --- |
| payload `.so` + `dlopen`（当前模型） | 是 | 是 | 否 | 可执行代码，但不是 `execve`；busybox 是 ELF 可执行文件，不是可链接库；不能用它冒充 shell |
| `TaskType::Executable` 指向 `/mnt/apk/bin/sh` | 否 | 是 | 否 | manager 域 `neverallow execute_no_trans`；且 `@TestApi` + `USE_CUSTOM_VIRTUAL_MACHINE` |
| extra APK（`addExtraApk`） | 否 | 是 | 否 | A16 `--noexec`；`extra_apk_file` 无 execute；entry point 必须在主 APK |
| `DEBUG_FULL` + adb shell/root | 否 | 否（破坏保护） | 否 | Javadoc 明确“may compromise … bypassing protected VM”；debug VM 的 `su` 为 permissive；不是生产路径，且属于 SELinux 绕过 |
| custom image | 否（对本问题） | 取决于签名 | 是 | 独立 OS 镜像；`USE_CUSTOM_VIRTUAL_MACHINE`；protected 需 pvmfw 信任的签名；不是“执行 APK 内容” |
| A17 tenant APK | 否（A16 不可用） | 是 | 是 | A17+ 的 tenant 机制，非主 payload；A16 无类型/API |
| vendor / OEM sepolicy | 否 | 是 | 是 | guest policy 编译进 microdroid 镜像并打包在 `com.android.virt` APEX；A16 APEX `updatable:false`；host vendor policy 不进入 guest |
| `/system/bin/sh` 替代 | 否 | 是 | 否 | 只有 `init`/`adbd` 能转换到 `shell`；payload 无 `execute_no_trans`；且被 DV-01A 明确禁止 |
| chmod / 复制到 /data / memfd / execveat / 进程内跳转 | 否 | 是 | 否 | 全部属于 DV-01A 禁止的绕过；不改变 SELinux 检查或破坏 sealed toolbox 语义 |

补充：A16 `domain.te:547` 明确 `neverallow domain encryptedstore_file:file no_x_file_perms`，即 encrypted storage 永不可执行；`domain.te:109` 只允许非 coredomain 执行 `/vendor`，payload 不适用。

---

## 7. 决策门结论：B（无合规路径）

**不存在**满足以下全部条件的 host / packaging / API 方案：

- 不需要 root / SELinux / pvmfw 修改；
- 不换 shell、不在 encrypted storage 执行；
- 保持 protected VM、sealed toolbox、FLN1 terminal 语义；
- 不引入额外宿主权限。

因此：

- 停止代码修改；不写任何绕过；
- DV-01A 保持 `blocked_external`；
- DV-01 保持 `dv01_core_pass_terminal_blocked`，不进入 DV-02；
- 产出平台 / ROM 变更请求（见第 8 节与独立报告）。

---

## 8. 平台 / ROM 变更请求（精确）

### 8.1 根因陈述

`microdroid_app` 对 `system_file` 有 `execute`、无 `execute_no_trans`；`/mnt/apk` 的所有文件被 zipfuse 统一标为 `system_file`；AOSP 又没有 `system_file` 的 `type_transition`。因此 payload 无法 `execve` 自己 APK 内的任何文件，尽管 zipfuse 有意为 `bin/` 设置了执行位。

### 8.2 方案 A（最小改动，较宽）

在 `system/sepolicy/microdroid/system/private/microdroid_app.te` 增加：

```te
# Allow the payload to exec binaries bundled in its own APK (zipfuse /mnt/apk).
allow microdroid_app system_file:file execute_no_trans;
```

- 优点：一行；直接消除 AVC；不改 mount/API。
- 缺点：`system_file` 覆盖整个 guest system image 与 `/mnt/apk`，语义过宽；虽然 `microdroid_app` 已经能运行任意 payload 代码（不是域逃逸），但会给“执行任意系统文件”开一个不必要的口子。
- 兼容性：A16 policy 中没有 neverallow 阻止该 allow（已检查 `microdroid_app` 相关 neverallow 为空）；manager 的 neverallow 不受影响。
- 适用：只作为应急/最小验证补丁；不建议作为长期形态。

### 8.3 方案 B（推荐，专用类型）

1. 在 `system/sepolicy/microdroid/system/private/file.te` 增加专用类型（不要继承 `system_file_type`，避免所有 coredomain 自动获得读/执行）：

```te
type microdroid_payload_file, file_type;
```

2. 在 `guest/microdroid_manager/src/main.rs` 的主 APK 挂载中，把 context 从 `system_file` 改为：

```text
fscontext=u:object_r:zipfusefs:s0,context=u:object_r:microdroid_payload_file:s0
```

3. 在 `zipfuse.te` 增加文件系统关联，并保持只有 zipfuse 能挂载：

```te
allow microdroid_payload_file zipfusefs:filesystem associate;
```

4. 在 `microdroid_app.te` 增加 payload 自身 APK 的完整权限：

```te
allow microdroid_app microdroid_payload_file:file {
    getattr open read map execute execute_no_trans
};
```

5. 如仍需 debug shell 运行 `bin/` 工具，单独给 `shell`：

```te
allow shell microdroid_payload_file:file rx_file_perms;
```

6. 保留 `microdroid_manager` 的 `neverallow ... execute_no_trans`（manager 不应直接执行 payload 文件）；保留 `domain.te` 对 `system_file` 的现有规则；新增 neverallow/CTS 测试，确保其它域不能执行 `microdroid_payload_file`。

- 优点：权限限定在 payload APK；不放开整个 system image；符合“sealed toolbox 只读、payload 自包含”的语义；未来多租户可复用。
- 代价：需要改 manager 的 mount context 与 policy，属于 AVF/Microdroid 平台变更。

### 8.4 方案 C（长期，参考 A17 tenant 模型）

A17 已为 tenant APK 引入 `tenant_apk_file` + `allow microdroid_app tenant_apk_file:file x_file_perms`，并把 tenant 挂载从 `MountForExec` 机制中独立出来。可在未来 Android 版本把同样的“专用 APK 文件类型 + x_file_perms”模式用于主 payload APK。该方案不适合在 A16 上回移，因为涉及多租户配置、AISeal 与新 API。

### 8.5 变更落点与发布路径

- policy 源：`system/sepolicy/microdroid/system/private/*.te`（A16 结构）；
- manager：`packages/modules/Virtualization/guest/microdroid_manager/src/main.rs`；
- 编译产物：`microdroid_precompiled_sepolicy` → AVB 签名的 `microdroid` system image → `microdroid_super` + `microdroid_vbmeta` → `com.android.virt` APEX；
- AOSP 16 的构建配置将 `com.android.virt` 标为 `updatable:false` / `future_updatable:false`（`build/apex/Android.bp:36-38`）；设备上 `pm path com.android.virt` 解析到 `/system/apex/com.android.virt.apex`（system APEX，不是 `/data/apex` 的可更新副本），与该配置一致。因此该修复**不能**通过 Play/APEX 热更新到达本设备，必须随平台 OTA 或 OEM 重建系统镜像发布；OEM 是否提供可更新 AVF APEX 以其平台配置为准。
- host vendor sepolicy 不会进入 guest；只改 vendor 分区无效。

### 8.6 验证计划（平台变更后）

Host / 构建：

- 编译 `microdroid_precompiled_sepolicy`，无 neverallow 失败；
- policy 单元/CTS（SELinux neverallow、`microdroid_app` 的 exec 规则、其它域的负例）；
- APK/payload 门禁不变：stored、16 KB、符号、无 `libvm_payload.so`、v2+v3。

设备（`qa-alt-01`，只写别名）：

- guest audit 不再出现 `execute_no_trans` denial；
- `/mnt/apk/bin/sh -c 'printf DV01_TERMINAL_OK; ...'` 退出码 0、输出 `DV01_TERMINAL_OK`；
- encrypted storage marker 写入/读取成功；stop → start 后 marker 保留；
- 20/20 create/run/stop/delete，stop `CONFIRMED`，residual=false；
- 负例有 typed rejection；`wrong_payload` 不再是 `unconfirmed`；
- 失败分类保持 `HIDDEN_API_BLOCKED`/`PERMISSION_DENIED`/`PROTECTED_UNAVAILABLE`/`PAYLOAD_VERIFY_FAILED`/`VSOCK_FAILED`/`STOP_UNCONFIRMED`；
- 设备 JSON `outcome=PASS`；零设备门禁无回归。

### 8.7 安全影响

- protected VM 边界不变；变更只在 guest 的 `microdroid_app` 域内增加“执行自己 APK 内文件”的能力。
- payload 本来就能在 `microdroid_app` 中运行任意 native code（`dlopen` + `mmap` exec），因此 `execute_no_trans` 不构成域逃逸；它增加的是“创建同域子进程/替换进程映像”的能力，这是 shell 的语义所需。
- 推荐方案 B 把范围限制在 payload APK 文件，避免把整个 `system_file` 变成可执行入口；sealed toolbox 仍只读、不可写、不在 encrypted storage。
- 不需要新增宿主权限、root、pvmfw 或 SELinux 放宽；不改变 FLN1 wire。
- 残余风险：同域 shell 子进程继承 `microdroid_app` 的权限集合；这是该域既有的信任边界。若要更强隔离，应在方案 B 基础上再引入专用 `microdroid_toolbox` exec 域与 `type_transition`/`entrypoint`，但需要为 `bin/` 单独挂载/标注，工作量更大。

---

## 9. 明确不采用的绕过

以下均已由 DV-01A 或本轮约束排除，本文不实现、不建议、不作为验证步骤：

- 改 SELinux policy / pvmfw / root；`setenforce 0`；`adb root` + `su`；
- `/system/bin/sh`、`/apex/.../sh` 替代 sealed toolbox；
- encrypted storage 执行；
- chmod、复制到可执行路径、memfd、execveat、`fexecve` O_PATH、伪 shell；
- 修改 FLN1 wire 或把 exec 语义替换为进程内解释器；
- 依赖 `DEBUG_FULL` 的 adb shell/root 作为生产路径；
- 把 custom image 当作“执行 APK 内容”的替代；
- 在 A16 上模拟 A17 tenant 机制。

---

## 10. 来源与验证镜像

### 10.1 主要来源（canonical）

Android 16 sepolicy（`system/sepolicy`，分支 `android16-release`）：

- `microdroid/system/private/microdroid_app.te`
- `microdroid/system/private/domain.te`（:92、:109、:547）
- `microdroid/system/private/microdroid_manager.te`（:57、:151-160）
- `microdroid/system/private/microdroid_payload.te`（:41-42）
- `microdroid/system/private/shell.te`（:16-17）
- `microdroid/system/public/global_macros`（:24）
- `microdroid/system/private/zipfuse.te`（:26-34）
- `microdroid/system/public/file.te`（:13）
- `microdroid/system/private/file_contexts`（:102）
- `microdroid/Android.bp`（:242-255，`microdroid_precompiled_sepolicy` / permissive `su`）

Android 16 Virtualization（`packages/modules/Virtualization`，分支 `android16-release`）：

- `guest/microdroid_manager/src/main.rs`（:366-375、:483-499、:549-582、:665-680）
- `guest/zipfuse/src/inode.rs`（:199-205、:233-235）
- `guest/zipfuse/src/main.rs`（:110-115）
- `libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java`（:1116、:1132、:1145、:1162、:1174-1178、:1187、:1211、:1292）
- `libs/libmicrodroid_payload_metadata/config/src/lib.rs`（:77-98）
- `build/microdroid/Android.bp`（:47-51、:109-114、:369-382）
- `build/apex/Android.bp`（:36-38、:104/:125、:135-140）
- `build/microdroid/bootconfig.debuggable`
- `docs/custom_vm.md`

Android 17（用于说明上游方向，不作为 A16 依据）：

- `microdroid/system/private/microdroid_app.te`（tenant APK 的 `x_file_perms`）
- `microdroid/system/private/zipfuse.te`（tenant 挂载）
- `microdroid/system/private/file.te`（`tenant_apk_file`）

Canonical URL 形态（替换 `<repo>`/`<path>`）：

```text
https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/android16-release/<path>
https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/android16-release/<path>
```

### 10.2 本次实际读取的镜像（provenance）

本轮环境无法直连 `android.googlesource.com`，因此用以下公开镜像读取并核对；结论只依赖上述 AOSP 源路径与设备 AVC，不依赖镜像的本地修改：

- Android 16 sepolicy：`LineageOS/android_system_sepolicy`，分支 `lineage-23.0`，commit `b0769f30959a1a02d777d53dfe0d61075143cc8b`（2026-01-09）。只读取了 microdroid 相关文件；未发现 Lineage 对 `microdroid_app` 执行策略的修改。
- Android 16 Virtualization：`watchings/AVF`，分支 `android16-release`，commit `c984fc337c11ca5edc03ccf02037b2455dd8fcaf`（与 AOSP tag `android-16.0.0_r1` 的 peeled commit 一致）。
- Android 17 sepolicy 参考：`GrapheneOS/platform_system_sepolicy`，分支 `17`。仅用于确认上游 tenant 方向，不作为 A16 事实。

### 10.3 局限

- 设备是 OEM ROM（`ro.build.type=user`），其 guest policy 可能与 AOSP 16 有 OEM 差异；但设备 AVC 是直接证据，且 AOSP 16 的规则本身已足以解释该 AVC。
- A16 的补丁级别/分支可能使行号漂移；引用时以“文件 + 规则文本”为准，行号仅用于定位。
- A17 tenant 机制是否向普通 app 开放未在本轮验证；即使开放，也不适用于 A16。

---

## 11. 结论

**决策门 B：无合规路径。**

在 Android 16 当前平台上，`microdroid_app` 无法 `execve` zipfuse 挂载的 APK 内容；这是 guest SELinux policy 缺口，不是 host 打包、ZIP mode、noexec、vsock 或 toolbox 内容问题。项目的 sealed toolbox / FLN1 terminal 设计本身没有错误；需要平台/ROM 为 payload APK 文件类型授予 `execute_no_trans`（推荐方案 B：专用 `microdroid_payload_file` 类型），或由平台提供等价的受支持可执行挂载。

在平台变更到达设备之前：DV-01A 保持 `blocked_external`，DV-01 保持 `dv01_core_pass_terminal_blocked`，不进入 DV-02，不写绕过代码。
