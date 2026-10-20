# AVF 可达性复核与设备门（2026-10-19）

> **性质**：对《硬件虚拟化零设备推进方案（2026-10-19）》的独立复核 + 追加调研。
> **基线**：`main 979947b` + `avf/mode-facts 9f7790e`。
> **本回合约束**：未修改产品代码、未运行 Gradle、未操作设备。所有"已核"均给出来源；"未验"明确留白。
> **证据等级**：AOSP 源码 > 固定提交的第三方源码 > 第三方 Issue/设备清单 > 二手报道。第三方设备清单只用于选机，不替代本应用的真机验收。

---

## 0. 结论摘要

1. **上一份报告的三个"未决"里，Q3 已经可以判定为"是"，Q4 对 non-protected 路径不适用，Q1 是"条件成立但脆弱"。** 这改变了计划的形状：真正的硬门不是源码事实，而是**一台声明 non-protected 的设备**。
2. **当前 HONOR MEY-AN00（SM8735 / API 36）只声明 protected，不能运行本项目的未签名自定义内核。** 这不是权限或 hidden API 问题，也不是改一个布尔值能解决的。Podroid 的一手实现同样明确：protected VM 需要 pvmfw 签名的 payload，raw custom kernel 只能走 non-protected；只有 protected 的设备必须回退 QEMU。
3. **本地审计新增两个 P0，严重度不低于上一份报告的停止/退出问题：**
   - `AvfSession.close()` 只关通道，不调用 `session.stop()`；`PersistentTerminalSessionPool` 对非 `RuntimeOwnedSession` 直接当作"已确认停止/仍存活"，因此正常关闭也可能把 AVF VM 留在后台。
   - `AvfInstanceLauncher` 在 `engine.launch()` 之后、`ExecutionSessionRegistry.register()` 失败时没有清理路径，VM 会成为孤儿。
4. **能力矩阵不是"四处不一致"，而是"五处 + 一条死代码"：** evaluator baseline、engine peripherals、session baseline、MainActivity graphics、hardcoded SoC blocklist；同时 QEMU session 没有覆写 `capabilityMatrix()`，桌面 HUD 会把 QEMU 显示能力读成 software baseline。MainActivity 的 `currentCapabilityMatrix()` 目前没有调用方。
5. **QEMU 可以冻结功能，但不能冻结发布工作；AVF 是一条受设备门约束的第二产品线，不是 QEMU 的替代品。** 下一批零设备工作应收敛为四票：票 23 收尾、所有权/停止、能力与硬件约束、平台访问层；H3/H6/T7 等拿到 non-protected 设备后再做。H5 rig 保留为可选 spike，不进关键路径。
6. **Android 17 的网络兼容性有一个具体坑：** 带 `useNetwork(true)` 的无 GPU AVF VM 可能被路由到 `crosvm_minimal`，该二进制没有 `net` feature，在参数解析阶段以 status 35 退出。Podroid 的修复是声明一个 headless 2D GPU，把 VM 路由到完整 crosvm。我们的适配器目前无条件 `useNetwork(true)`，且没有声明 GPU，必须在 H3 前处理。

---

## 1. 对上一份报告的勘误

| 上一份报告的说法 | 本次复核 | 证据 |
| --- | --- | --- |
| Q3 未决：non-protected 是否检查 App 私有盘 label | **已决：不检查。** 整个 label 校验块位于 `if config.protectedVm { ... }` 内；non-protected 分支不调用 `check_label_*`。App 私有 `app_data_file` 根盘在 non-protected 下应当被接受，ADR-0007 可保住 | `android/virtmgr/src/virtualmachine.rs:679-710`、`:1307-1320`；见 §2.1 |
| Q4 未决：DICE 是否随 App 更新失效 | **对 non-protected 不适用。** DICE/pvmfw 是 protected VM 的启动链；non-protected 自定义 VM 不走 pvmfw。protected VM 需要签名 payload，本项目的未签名 kernel/initrd 不能作为 protected custom Linux 启动 | `docs/pvm_dice_chain.md`；Podroid `AvfCapabilities.kt:6-8`；见 §2.2 |
| hidden API 未决 | **条件成立：** Android 16 的 ART 变更曾使旧版 HiddenApiBypass 崩溃；TerminalAppPlus 在 `targetSdk 37` 上使用 `hiddenapibypass:6.1`，在 `attachBaseContext` 里 `addHiddenApiExemptions("Landroid/system/", ...)`。这是"可行"的存在性证据，不是 AOSP 保证 | TerminalAppPlus `app/build.gradle.kts`、`Application.kt`；LSPosed issue #52；见 §2.3 |
| CI 的 lint/assemble/16KB 也不在 | **不准确。** `qemu-android.yml` 已有 `assembleDebug`、`lintDebug`、ELF/16KB、`zipalign -P 16` 检查；问题是它只 `workflow_dispatch`，且 `ci.yml`/`release.yml` 不跑 AVF/capability/distro/network。修法是"把已有检查提升为 PR/release 门"，不是重写 | `.github/workflows/ci.yml:45`、`release.yml:97`、`qemu-android.yml:383,463` |
| 默认 App-owner | **只在 non-protected 设备上成立。** 当前真机 protected-only，`setProtectedVm(false)` 会直接抛 `UnsupportedOperationException`（`hypervisor_vm_supported()` 为 false）。App-owner 不是当前设备的可执行方案 | `VirtualMachineConfig.java:1314-1326`；当前设备 `vm info` 仅 protected（2026-10-17 证据） |
| 默认 2 vCPU 必失败 | **确认。** `VmHardwareConfig.DEFAULT_CPU_CORES=2` → `AvfLaunchSpec.cpuCount=2` → 适配器硬拒 `!=1`。补充：Android 16+ 存在 hidden 的 exact-count AIDL 路径（Podroid 用动态 Proxy 重写 `CpuOptions.CpuTopology.cpuCount(N)`），但它比 1 vCPU 更脆弱，不建议作为第一版 | `VmHardwareConfig.java:15`；`ReflectiveAvfVirtualMachineApi.java:85-90`；Podroid `AvfReflect.kt:142-265` |
| "H5 rig 是最强零设备证据" | **有价值，但不是 AVF 证据。** QEMU aarch64 TCG 验证的是"引擎能驱动一个真实 VMM"，不是 crosvm/AVF 的 config、label、权限、vsock 时序。它不能替代设备门，且实现成本不低 | 见 §5.3 |
| "H1 不改 ADR-0020 语义" | **ADR-0020 明确把 AVF 排除在所有权范围之外。** 给 AVF 加 `RuntimeOwnedSession` 是范围扩展，必须新增 ADR-0021 或修订 ADR-0020，否则代码与决策记录不一致 | `docs/adr/0020-runtime-process-ownership.md:30` |
| QEMU 只剩工程尾巴 | **大体成立，但"尾巴"里仍有发布阻塞项。** 生产 keystore、同包/新包迁移、真机 20 次启停、T7 性能、Arch 首启都不在代码层；CI 全量门禁是工程层，应作为发布门而不是新功能 | `docs/reports/orchestration/hardware-virtualization-zero-device-plan-20261019.zh-CN.md` §1.2；本次 CI 核对 |

---

## 2. 三个事实的源码判定

### 2.1 Q3：non-protected 不做 label 校验

AOSP Android 16 QPR2 镜像（`LineageOS/android_packages_modules_Virtualization@lineage-23.2`）
`android/virtmgr/src/virtualmachine.rs`：

```rust
// :676-710
let kernel = maybe_clone_file(config.kernel.as_ref())?;
let initrd = maybe_clone_file(config.initrd.as_ref())?;

if config.protectedVm {
    check_protected_vm_is_supported()?;
    check_label_for_kernel_files(kernel.as_ref(), initrd.as_ref(), calling_partition)?;
    // disk image / partition label checks ...
}
```

允许标签列表在 `:1307-1320`，注释明确写着：

```rust
/// App private data files are deliberately excluded, to avoid arbitrary payloads being run on
/// user devices (W^X).
fn check_label_is_allowed(...) {
    match context.selinux_type()? {
        "apk_data_file" | "shell_data_file" | "staging_data_file"
        | "system_file" | "virtualizationservice_data_file"
        | "vendor_microdroid_file" => Ok(()),
        _ if calling_partition == CallingPartition::Vendor => Ok(()),
        _ => bail!("Label {} is not allowed", context),
    }
}
```

**判定**：`app_data_file` 被排除是 protected 分支的 W^X 规则；non-protected 分支不执行这段检查。因此：

- App 私有 raw 根盘、kernel、initrd 在 non-protected 下应被接受；
- ADR-0007 的"全部 App 私有存储"在 App-owner + non-protected 路径下**不需要改**；
- 上一份报告"Q3 未决"可以关闭。

**边界**：`check_partitions_for_files` 对所有 VM 仍会检查 Treble 边界（system/vendor 与 App 的侧别），这不是 SELinux label 校验；App 私有文件在数据侧，正常不会触发。该函数未在真机上验证，H4 仍需一次真实 create。

### 2.2 Q4：DICE 只属于 protected；未签名 custom kernel 不能走 protected

- `docs/pvm_dice_chain.md`：pVM DICE chain 由 vendor → pvmfw → Microdroid 扩展，用于 protected VM 的度量/密钥派生。
- `crosvm.rs:271-292`：只有 `config.protectedVm` 分支才选择 `--protected-vm` / `--protected-vm-with-firmware`；non-protected 不走 pvmfw。
- Podroid 的 `AvfCapabilities.kt:6-8` 写得很直接：

> Podroid runs a raw custom kernel/initrd image, which AVF can only boot as a NON-protected VM
> (protected VMs need a pvmfw-signed payload). So: prefer non-protected; if the device only
> supports protected VMs we cannot run here and the caller must fall back to QEMU.

**判定**：

- non-protected 路径没有 DICE 身份，因此不存在"App 更新导致 DICE 失效"的问题；
- 真正需要关心的是**持久 VM 配置的路径/字段稳定性**，不是 DICE；
- protected-only 设备不能作为本项目 custom Linux 的交付路径。除非未来接受"签名 payload + 无原生网络 + shell-owner 存储"的另一条产品线，否则 protected 研究线应关闭，不再占本轮带宽。

### 2.3 Q1：hidden API 豁免"可用但脆弱"

一手固定源码（TerminalAppPlus，`88a015928ebc26012f515ae34859a431de1a9c98`）：

```kotlin
// Application.kt
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
        "Landroid/system/", "Landroid/os/", "Landroid/view/",
        "Landroid/content/", "Landroid/permission/", "Llibcore/io/"
    )
}
```

```kotlin
// app/build.gradle.kts
compileSdk = 37
minSdk = 37
targetSdk = 37
implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
```

这证明：在 Android 17 / targetSdk 37 的第三方 release APK 里，本进程 hidden API 豁免 + 两次 `pm grant` + non-protected 设备可以跑通 AVF custom VM（TerminalAppPlus 明确拒绝 `protected:true`）。

但要注意三条限制：

1. **不是 AOSP 保证。** Android 16 的 ART 变更曾让旧版 HiddenApiBypass 在 `setHiddenApiExemptions` 崩溃（LSPosed issue #52）；社区随后发布兼容版本。Google 随时可能再次改 ART 内部结构。
2. **必须早于任何 AVF 类加载。** 我们的 `AvfHostFactsProbe.readModeFacts()` 在 Activity 里直接 `Class.forName`，没有豁免；在 hidden API 受限的设备上会返回 `UNKNOWN`，从而把"设备仅 protected"误显示成"模式未知"。豁免应放在 `Application.attachBaseContext` 或一个先于探针的 holder 里。
3. **分发与合规。** Google Play 不允许 hidden API 使用；项目当前走 GitHub/F-Droid 发布，需在文档与许可/免责声明中明确。不要把 hidden API 豁免当成"权限"——它不授予任何 Android permission。

**判定**：App-owner 仍是默认；shell-owner 是"目标设备上 HiddenApiBypass 6.1 实测失败"时的备选，不需要现在预建 ACR-01 的完整架构。H4 应把豁免、权限读取、能力探测、失败分类收进一个平台访问模块。

---

## 3. 设备门：当前设备与候选设备

### 3.1 当前设备为什么不能作为 AVF 交付路径

已有真机证据（2026-10-17，未在本回合重跑）：

- HONOR MEY-AN00 / SM8735 / Android 16 / API 36；
- `android.software.virtualization_framework=true`，`com.android.virt` 存在；
- `vm info`：`Only protected VMs are supported`；
- `getCapabilities() = 1`（protected），non-protected=false；
- `/dev/gunyah` 存在，`/dev/kvm` 不存在；后端更可能是 Gunyah，不是 pKVM；
- App 进程内 `VirtualMachineCustomImageConfig$Builder` 被 hidden API 拒绝；Shizuku/UserService（UID 2000）能构造 manager 并读到 `bits1`；
- App 私有 state 对 UserService 是 EACCES；合成 32B FD 可传递，但真实 VM FD 入口未验。

结合 §2.2：**当前设备不能运行本项目的未签名 custom Linux。** 这不是"再写一个 adapter"能解决的；需要换设备，或接受另一条 protected 产品线（签名 payload + 无原生网络 + shell 存储），后者不建议作为当前主线。

### 3.2 候选设备（用于选机，不替代验收）

Podroid 的设备兼容清单（issue #62，2026-09 更新）给出了可复现的第三方样本：

| 设备 | Android | AVF 结果 | 备注 |
| --- | --- | --- | --- |
| Google Pixel 6 Pro（Verizon，locked bootloader） | 17 | AVF 可用 | 无需解锁；QEMU 也可用 |
| Google Pixel 10 Pro XL | 17 | AVF 可用 | 维护者设备；Android 17 beta 曾有 `--net` 问题，1.2.6 已绕过 |
| Google Pixel 11 | 17 | AVF 可用 | 用户报告 |
| Google Pixel Tablet | 17 | AVF 可用 | locked bootloader；多核正常；Waydroid 需内核 PSI |
| Moto G06 | 15 | AVF 可启动但无网络接口 | 不是所有 non-protected 设备都有可用 TAP |
| Redmi K Pad 2 | 16 | AVF 操作导致设备 hang/reboot | 高风险，不建议作为验收机 |
| Pixel 8a / Pixel 8 Pro | 16/17 | 有 boot/panic 问题（#29/#108） | 不作为首选 |
| MediaTek MT6991 / GenieZone（TerminalAppPlus 目标） | 17 | 作者声称验证 | 需自行复核 `getCapabilities` bit 2 与 create/run |

**选机规则**（按优先级）：

1. 必须能在 `adb shell` 下确认 `android.software.virtualization_framework` 且 `getCapabilities() & 2 != 0`；只看芯片型号/营销名不算。
2. 必须能 `pm grant` 两个权限（release 包也可；目标设备实际行为要验）。
3. 必须能创建并启动一个**非 protected** 的最小 custom VM（可以是测试 payload），并正常 stop。
4. 优先选择 Podroid 清单里已有 AVF 成功记录、且没有已知 boot/hang 问题的机型：Pixel 6 Pro、Pixel Tablet、Pixel 10/11。
5. 避免把"最新骁龙/GenieZone"当成兼容性承诺；MediaTek 设备需要单独验证 `/dev/gzvm`、ROM 策略和 non-protected 位。
6. 不建议为 AVF 专门买一台只能 protected 的机器；那会把项目带向另一条产品线。

### 3.3 设备验收最小清单

在真实设备上，按顺序记录每一步的原始输出（不含序列号/账号）：

1. `getprop ro.boot.hypervisor.vm.supported` / `ro.boot.hypervisor.protected_vm.supported`；
2. `pm list features | grep virtualization_framework`；
3. App 与 shell/UserService 分别调用 `getCapabilities()`，记录 bits 与 actor；
4. `pm grant` 两权限，冷启动后复核；
5. `setProtectedVm(false)` 不抛 `UnsupportedOperationException`；
6. 用 App 私有目录的 kernel/initrd/root raw 创建 non-protected custom VM；
7. `run` 返回后，用 vsock 9100/9101 完成 Bridge/终端握手（run 返回不等于 guest ready）；
8. 正常 stop → 状态收敛；重复 20 次启停；记录失败/孤儿；
9. 网络：先不带 `useNetwork`，再带 `useNetwork(true)` + headless 2D GPU workaround；记录 guest `eth0`、DHCP/静态 IP、DNS、apt；
10. 9p/display：guest 端 `display-relay.py --transport vsock` 与 9p server 的实际连通；
11. App 进程死亡/强制停止后，VM 是否随之停止；
12. 同设备同资源（1 vCPU/相同 RAM/相同 rootfs）与 QEMU 对照，保留所有失败样本。

---

## 4. 本地代码审计：报告之外的新发现

| 级别 | 问题 | 位置 | 影响 | 零设备验收 |
| --- | --- | --- | --- | --- |
| P0 | `AvfSession.close()` 只关显示/通道，不调用 `session.stop()`；`PersistentTerminalSessionPool` 对非 `RuntimeOwnedSession` 返回 `true`（"legacy sessions keep close-only"） | `AvfInstanceLauncher.java:94-101`；`PersistentTerminalSessionPool.java:648-654` | 正常关闭/池替换可能把 AVF VM 留在后台；与 ADR-0011"App 死亡 VM 随停"冲突 | fake 宿主：close 后 fake VM 必须 stopped；非 owner 不得被当作 confirmed |
| P0 | `engine.launch()` 成功后 `ExecutionSessionRegistry.register()` 抛 `IOException` 时无清理；`AvfInstanceLauncher` 没有 QEMU 的 `cleanupFailedLaunch` 等价物 | `AvfInstanceLauncher.java:213-218`；对照 `QemuInstanceLauncher.java:752-784` | 注册失败 → VM 孤儿、根盘占用、后续启动冲突 | 注入 register 失败，断言 VM 被 stop 或以 UNCONFIRMED 保留 |
| P0 | `waitFor()` 对 status 0（STOPPED）和 2（DELETED）都返回 0；`stop()` 吞异常、无 grace、无二次探测 | `ReflectiveAvfVirtualMachineApi.java:551-562, 569-595` | 崩溃/删除/网络参数失败都被当成正常退出；无法区分 exit reason | fake：STOPPED→正常、DELETED→异常、stop 卡住→UNCONFIRMED、崩溃→非 0 |
| P0 | 默认 2 vCPU 在校验前先 bootstrap；`AvfInstanceLauncher` 丢掉 `diskCapacityBytes`、`gpuAccelerationEnabled`、`extraKernelArguments` | `VmHardwareConfig.java:15`；`AvfInstanceLauncher.java:198-211`；`Reflective...:85-90` | 默认 AVF 路径必失败，然后 QEMU 再 bootstrap 一次；硬件配置与 QEMU 不等价 | admission 测试：拒绝的配置不触发 bootstrap；接受配置字段完整 |
| P0 | 能力矩阵五处不一致 + QEMU session 未覆写；MainActivity 的 matrix 方法无调用方 | `AvfCapabilityEvaluator.java:129-136`；`AvfVirtualMachineEngine.java:23-25`；`AvfInstanceLauncher.java:59-61`；`MainActivity.kt:744-753`；`DesktopActivity.java:493-495` | UI 可能声称 graphics/audio/clipboard，但 session 只承认 baseline；QEMU 桌面 HUD 反而显示无图形 | 同一 fixture 驱动 UI 与 session，断言相等；删除未使用方法 |
| P1 | 数据面死路径：`AvfWaylandChannel`/`AvfAudioChannel`/`Avf9pShareServer`/`AvfVsockPortForwarder` 生产引用为 0；显示实际是 `QemuWaylandChannel` + RFB；`AvfInstanceLauncher` 硬编码 9103 | `AvfInstanceLauncher.java:39-45`；`AvfVirtualMachineEngine.java:123-206`；`AvfLaunchSpec.java:44-50` | "声称有图形/音频/共享"与实际不符；端口常量散落 | 每个能力有 SUPPORTED/UNSUPPORTED 与一处适配器；生产代码不再引用仅测试通道 |
| P1 | 持久 VM 配置严格相等；DNS/内存一变就 `CONFIG_MISMATCH`，无 `setConfig`/`delete` 恢复 | `Reflective...:154-192` | 用户改网络/内存后实例永久无法启动，只能手工删 VM | 配置差异分类：身份差异拒绝；非身份差异走受控 delete/recreate，保留根盘 |
| P1 | Android 17 网络：`useNetwork(true)` 无条件；无 headless GPU 声明；无 `crosvm_minimal` 失败检测 | `Reflective...:101`；`AvfLaunchSpec` 只传 DNS | 无 GPU 的联网 VM 可能被路由到 `crosvm_minimal`，`--net` 参数解析失败（status 35），整台 VM 起不来 | fake 宿主覆盖 `useNetwork` true/false；失败签名可识别；可降级为无网络并提示 |
| P1 | `AvfHostFactsProbe.readModeFacts()` 无 hidden API 豁免；与 engine 各有一套反射 shape | `AvfHostFactsProbe.java:108-136`；`Reflective...:388-441` | 受限设备上探针返回 UNKNOWN，UI 无法区分"仅 protected"与"未知"；两套失败语义 | 同一 fake classloader 驱动探针与 engine；失败分类为 AVAILABLE/HIDDEN_API_BLOCKED/PERMISSION_DENIED/MODE_UNSUPPORTED |
| P1 | CI 不跑 `:runtime:engine-avf:check`、`:runtime:capability:check`、distro、network；release 也不跑 | `.github/workflows/ci.yml:45`；`release.yml:97` | 最脆弱的反射/生命周期代码没有门禁 | 在 CI/release 加 umbrella check；故意失败能阻塞 |
| P2 | AVF 测试是 20 个 `JavaExec main()` harness，不是 JUnit；Gradle test report 不可见 | `runtime/engine-avf/build.gradle.kts` | 失败可见性差；CI 只能依赖 `check` 任务 | 转为 JUnit 或让 `check` 依赖并发布结果 |
| P2 | ADR-0020 明确排除 AVF；H1 的范围扩展没有决策记录 | `docs/adr/0020:30` | 代码与 ADR 冲突，未来评审会反复质疑 | 新增 ADR-0021（AVF runtime ownership）或修订 ADR-0020 |
| P2 | 实例元数据没有 schema version；缺 `hardware.cpuCores` 时静默变成 2 vCPU/2 GB | `InstanceLifecycle.java` 元数据读写；`VmHardwareConfig.java:60-67` | 旧实例的实际硬件被悄悄改变；未来迁移无分支点 | 版本化 codec + 迁移表 + golden fixtures |
| P2 | `AvfCapabilityEvaluator.BLOCKED_SOC_HINTS = ["sm8750"]` | `AvfCapabilityEvaluator.java:39` | 硬编码 SoC 黑名单是第五个真相源，可能误杀支持 non-protected 的机型 | 移除，改用 `getCapabilities` + 实际 create 的 typed 结果 |

---

## 5. 修订后的开发方案

### 5.1 总体判断

- **QEMU**：冻结功能，继续作为唯一交付路径；把发布门禁补齐，不再开新功能线。
- **AVF**：按"引擎已写完、接线与所有权未完成"处理；先做**代码就绪 + 诚实能力**，再用一台 non-protected 设备做产品门。不要在设备门通过前把 AVF 设成默认，也不要在 UI 上把 AVF 说成"已支持"。
- **protected**：不作为本轮产品路径。未签名 custom kernel 不能跑 protected；如果未来要做，是另一条产品线（签名 payload、shell/设备所有者、无原生网络），需要单独的产品决策。
- **H5 rig**：可选 spike。它验证的是 QEMU 驱动引擎，不是 crosvm/AVF；不要把它当设备证据，也不要让它阻塞 H1/H2。

### 5.2 Wave 0：零设备、现在就能做（建议四票 + CI）

| 票 | 内容 | 依赖 | 零设备验收 | 真机 |
| --- | --- | --- | --- | --- |
| W0-1 / H7 | 票 23 实施报告 + 双轴评审 + 勾选 + 合并 `avf/mode-facts`；在报告中回答"App 内探针在 hidden API 受限时只能 UNKNOWN，是否下沉 shell" | 无 | 报告与代码一致；评审记录存在 | 无 |
| W0-2 / H1 | `AvfSession` 实现 runtime owner；`close()` 必停 VM；注册失败清理；`waitFor` 退出分类；`stop(grace)` 三态 + 二次探测；LaunchTimeline；新增 ADR-0021 | 无 | fake：close→stopped；register 失败→无孤儿；STOPPED/DELETED/崩溃可区分；stop 卡住→UNCONFIRMED | 真实 stop/崩溃/App 死亡 |
| W0-3 / H2 | 能力矩阵单一事实源（从已接线适配器派生）；删除 QEMU session 的 baseline 默认坑；AVF 默认 1 vCPU + UI 诚实标注；配置差异分类与受控迁移；移除 SoC 黑名单 | 无 | 同一 fixture 断言 UI=session=engine；默认配置可启动（fake）；改 DNS/内存有明确路径 | capabilities 一致；配置变更可恢复 |
| W0-4 / H4a | 平台访问模块：hidden API 豁免（HiddenApiBypass 6.1，`attachBaseContext` 或 lazy holder）；探针与 engine 共用一套反射 shape/failure mapping；两权限检测/引导；默认 App-owner；shell-owner 只做接口预留 | 无（设备验证后置） | 策略矩阵测试：已授权/未授权/未知/豁免失败/仅 protected/不支持；探针不再无豁免裸跑 | `pm grant` 真实行为、hidden API 是否被拦、getCapabilities actor |
| W0-5 / CI | 把 `:runtime:engine-avf:check`、`:runtime:capability:check`、`:runtime:distro:check`、`:runtime:network:check` 加入 `ci.yml` 与 `release.yml`；把 `qemu-android.yml` 的 lint/assemble/16KB 提升为 PR/release 门；JUnit 化或发布 JavaExec 结果 | 无 | 故意失败能阻塞；CI 能看到 AVF 结果 | 无 |

**Wave 0 的顺序**：W0-1（当天可闭环）→ W0-5（两行 CI + 提升已有检查）→ W0-2（安全不变量）→ W0-3（用户可见真相）→ W0-4（接入 hidden API）。W0-2/3/4 可以并行，但 W0-2 是最高优先级的安全不变量。

### 5.3 Wave 1：拿到 non-protected 设备后

| 票 | 内容 | 依赖 | 真机验收 |
| --- | --- | --- | --- |
| W1-1 | 设备资格：按 §3.3 清单验证 `getCapabilities` bit2、两权限、`setProtectedVm(false)`、最小 custom VM create/run/stop | 用户提供设备 | 一条命令产出结构化报告 |
| W1-2 / H3a | 网络：`useNetwork` 条件化；Android 17 headless 2D GPU workaround；guest IP/DHCP；失败降级；9p/端口转发接线 | W0-3、W1-1 | guest `eth0`、DNS、apt；9p 读写；forward 真实端口 |
| W1-3 / H3b | 显示/音频/剪贴板：在 RFB-only 与 AvfWaylandChannel 之间二选一；删除死路径；音频/剪贴板要么接线要么标 UNSUPPORTED | W0-3、W1-1 | 显示帧、输入、剪贴板；音频按声明验收 |
| W1-4 / H6 | 设备验收包 + 20 次启停 + App 死亡 + 崩溃码 + 端口复用；CI 设备门 | W0-2/3、W1-2/3 | 一条命令出可归档报告 |
| W1-5 / T7 | 同设备同资源 1 vCPU/RAM/rootfs 的 AVF vs QEMU 性能；保留失败样本 | W1-4 | n≥10，控温，median/range |
| W1-6 / H5 | QEMU aarch64 rig（可选） | 无 | boot→vsock→9p→stop；明确标注"设备无关近似" |

### 5.4 Wave 2：产品决策

只有在 W1-1/W1-4 通过后，才讨论：

- AVF 是否进入默认引擎；
- 是否把 AVF 能力写进产品承诺（1 vCPU、RFB、无音频/剪贴板/forward 或已接线）；
- 是否发布带 AVF 的版本，还是继续 QEMU-only + "实验性 AVF"开关。

### 5.5 停止条件

| 触发 | 处置 |
| --- | --- |
| 4–6 周内没有可用的 non-protected 设备 | AVF 保持 code-ready；不发布 AVF 能力；QEMU 继续交付 |
| 目标设备 `getCapabilities` 无 bit2，或 `setProtectedVm(false)` 抛异常 | 该设备不合格；不要用 protected 绕过 |
| HiddenApiBypass 6.1 在目标设备实测失败 | 评估 shell-owner（ACR-01）；两者都不行则停 AVF |
| `useNetwork(true)` + GPU workaround 仍无法联网 | AVF 不能作为 Full Linux 交付；只保留终端/离线或停止 |
| 真实 stop/崩溃无法收敛 | 回退到 W0-2 修复，不发布 |
| 任何能力与真机不符 | 标记 UNSUPPORTED；不得静默 fallback |

---

## 6. 需要用户拍板的事

1. **设备**：是否为 AVF 投入一台已验证 non-protected 的设备（优先 Pixel 6 Pro / Pixel Tablet / Pixel 10/11，或 MT6991/GenieZone；必须实测 bit2 + create/run/stop）。没有设备，Wave 1 全部只能标 `not-tested`。
2. **产品契约**：是否接受 AVF v1 的收敛能力——建议 **1 vCPU、终端/Bridge、RFB 显示、只读 9p**；音频/剪贴板/端口转发在接线前明确 UNSUPPORTED；网络按 Android 17 workaround 验证。若不能接受，AVF 只做实验开关，不进默认。
3. **hidden API 失败时的备选**：是否接受 shell-owner（会触及 ADR-0007/0011 和 ACR-01）。建议**先不建**，等目标设备实测 HiddenApiBypass 6.1 失败再启动；TerminalAppPlus 的 Android 17 证据说明 App-owner 值得先试。
4. **发布节奏**：QEMU-only 继续发布；AVF 不进入 release notes 的"已支持"列表，直到 W1-4 通过。

---

## 7. 证据、来源与限制

### 7.1 本次实际读取/核对

- 本地代码：`runtime/engine-avf/**`、`runtime/capability/**`、`runtime/contract/**`、`app/…/AvfInstanceLauncher.java`、`AvfHostFactsProbe.java`、`InstanceLaunchSequence.java`、`PersistentTerminalSessionPool.java`、`QemuInstanceLauncher.java`、`MainActivity.kt`、`DesktopActivity.java`、`.github/workflows/*.yml`、`docs/adr/0007,0011,0020`。
- 测试结果：App `840 tests / 105 suites / 0 failures / 0 errors / 0 skipped`；lint `0 errors / 763 warnings`（本地构建产物，非本回合重跑）。
- AOSP 源码：`LineageOS/android_packages_modules_Virtualization@lineage-23.2`（Android 16 QPR2 镜像，本机无法直连 android.googlesource.com，故用 GitHub 镜像；文件 SHA256 见下）。文件与行号：
  - `android/virtmgr/src/virtualmachine.rs`（SHA256 `7e8bcf5d62b1adbaa3a3e53e6d92880a167eb8d3f4ba564ddc590f2073e1ed60`）
  - `android/virtmgr/src/crosvm.rs`（`7cf48278053ab16aef1725b1b93b7197cbc2bc91674b679b327f3f13466d4f9c`）
  - `libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java`（`9283ef4f5afa5ba295d36c5e928463a2353fa043f1130b56948c7987c70a9a90`）
  - `.../VirtualMachineCustomImageConfig.java`（`d2e97068fb21cb9bf570b2cb0b844a0794e92c2197c5f7b2e0a7282484cb4703`）
  - `.../VirtualMachineManager.java`（`8f6182d996c48a18dbaca2b75351cd8d2d769db70eabee3aadc4178781a7841b`）
  - `docs/pvm_dice_chain.md`（`51f381f17d046216dbd054219a376a6a3e9f8d57fb86dc092817e25da5ab64b2`）
- 第三方固定提交：
  - Podroid `ce0121896b2895637ab1f656cbb0d75fa2874489`，`AvfCapabilities.kt`、`AvfEngine.kt`、`AvfReflect.kt`、`AvfCpuPolicy.kt`；issues #44、#62、#29、#108。
  - TerminalAppPlus `88a015928ebc26012f515ae34859a431de1a9c98`，`Application.kt`、`app/build.gradle.kts`。
  - LSPosed/AndroidHiddenApiBypass issue #52。
- 项目既有真机证据：`docs/research/2026-10-17-avf-device-recheck.md`、`docs/research/2026-10-15-avf-shizuku-upgrade.md`、`docs/research/2026-10-17-avf-custom-linux-route-study.md`。

### 7.2 未验证/不能声称

- 当前 HONOR 的 protected Guest 实际 boot/stop；
- 任何 non-protected 设备上的本应用 create/run/vsock/stop；
- AVF 网络、9p、显示、音频、剪贴板的真实连通；
- HiddenApiBypass 6.1 在当前 ROM/Android 17 上的真实行为；
- protected + shell-owner 的签名/存储/网络可行性；
- AVF vs QEMU 性能；
- Android 17 AOSP 主干的精确源码（本次主要用 A16 QPR2 镜像 + Android 17 CDD/第三方 targetSdk 37 证据；Android 17 的 `VirtualMachineCustomImageConfig`/`virtmgr` 若有变化，H0 需要在目标设备/APEX 上再核）。

### 7.3 结论口径

- "源码已核"只表示在该固定源码版本上成立，不等于目标 ROM 的 APEX 行为；
- "第三方设备清单"只用于选机，不等于本应用验收；
- 没有真机样本的能力，一律写 `not-tested`，不写成"支持"。

---

## 8. 建议的下一步

1. 先做 **W0-1 + W0-5**：把票 23 合掉，把 CI 门禁补齐；这是当天能闭环、且能防止 AVF 继续退化的两件事。
2. 并行做 **W0-2 + W0-3**：所有权/停止/清理，以及能力矩阵/硬件约束；这两票直接修掉"VM 留后台"和"UI 虚报能力"。
3. **W0-4** 在 W0-2/3 之后接 hidden API 访问层；用 fake 宿主把策略矩阵跑绿，等设备再验。
4. 设备决策不要拖到 Wave 0 之后：如果决定投入 AVF，现在就按 §3.2/§3.3 选机；如果不投入，Wave 0 完成后就把 AVF 标为"代码就绪、未验证"，不要让 H3/H6 继续占带宽。
5. 不要启动 protected 产品线；除非用户明确接受"签名 payload + 无原生网络 + shell 存储"的另一条产品契约。
