# 宿主 Termux / glibc / PRoot 作为 Fast Lane 兜底的评估（2026-10-21）

> 状态：研究结论，不是产品承诺。本文不修改 Runtime 类别、`CONTEXT.md`、能力矩阵或任何产品代码。
> 范围：在 protected Fast Lane 不可用或能力不足时，宿主侧 Termux、Termux glibc（`glibc-runner`）、PRoot/proot-distro 能否作为“无 Root、非 non-protected”设备的兜底 Linux 体验；它们与 QEMU Full Lane、Fast Lane 的边界。
> 证据等级：**[官方事实]** 官方文档/源码/策略；**[社区经验]** 单点基准、issue、维护者经验，不能当受控实验；**[本项目未验证]** 本仓库推断或尚未在 Android 真机执行。
> 本环境未运行 adb、Android 构建或真机测试；本文所有“本项目性能”均为 `not-tested`，不引用宿主数据冒充 Fast Lane 数据。

## 0. 结论与建议

**主建议：本阶段不产品化。** Termux/glibc/PRoot 都不提供 guest kernel，不能兑现 Full Linux 契约（systemd、Docker、真实 root、原生网络/GPU 的完整组合），也不具备 Fast Lane 的 protected pVM 隔离。把它们叫“Fast Lane”或“Linux VM”会造成契约混淆。

**备选：作为 Userspace Compatibility Runtime 的兜底候选。** 只在以下 G1–G5 全部有证据后，才考虑以独立名称、独立能力矩阵、独立 UI 徽章提供“宿主内核上的用户态兼容层”，绝不进入 Fast Lane/Full Linux 的能力声明。

**当前证据结论：需要更多证据，而不是“性能不够”。** 决定性阻断是 **targetSdk/exec 门**、**只读 APK 前缀的可执行性**、**GPL/LGPL 分发义务** 和 **隔离语义**；性能只是 workload 相关问题。宿主的原生 CPU 数字不能外推为 protected pVM 的数字。

| 决策 | 含义 | 触发条件 |
| --- | --- | --- |
| 不产品化（当前） | 不新增类别、不做产品承诺、不进入发布说明 | 默认状态 |
| 作为兜底 | 仅 Userspace Compatibility；独立命名/能力矩阵；明确“不是 VM” | G1–G5 通过，且 P0/P1 发布门与 ACR-03 已处理 |
| 需要更多证据 | 本文列出的门未闭合 | 任何一门缺证据 |

## 1. 评估对象与能力边界

| 方案 | 执行方式 | 是否有 guest kernel | 隔离级别 | 与 Fast Lane 的关系 |
| --- | --- | --- | --- | --- |
| Host Termux（Bionic） | Android 宿主 CPU 原生执行 | 否 | Android app 沙箱 / 同 UID | 不是 Fast Lane；可作为宿主工具环境 |
| Termux glibc（`glibc-runner`） | 宿主 CPU 原生执行，glibc loader/sysroot | 否 | 同上 | 同上，ABI 更接近发行版 |
| PRoot / proot-distro | 宿主 CPU 原生执行 + ptrace 路径重写 | 否 | 弱：共享宿主内核，仅路径视图 | 仅 Userspace Compatibility 兜底 |
| QEMU Full Lane | TCG 翻译 guest 指令 | 是 | 进程级 VM | 现有 Full Linux 主线 |
| Protected Fast Lane | pVM 硬件 vCPU + Microdroid | 是（Microdroid GKI） | pVM/SELinux | ACR-03 未决，实验开关 |

**类别关系**：`CONTEXT.md` 已有 **Userspace Compatibility Runtime**（不提供 guest kernel、以用户态手段提供受限 Linux 环境、隔离较弱）。Host Termux/glibc/PRoot 只能归入这一类；本文不新增“Host Native”类别，也不把 PRoot 放进 protected。若未来要把“Host Native”做成产品名词，那是产品命名/能力矩阵决策，不是新的 Runtime 类别。

## 2. Termux 原生 Bionic：CPU 近原生，但不是 VM

### 2.1 已核实事实

- **[官方事实]** Termux 是 Android 终端模拟器 + 自己的包生态；“It does not emulate any system; Programs are executed natively.”（[Termux FAQ](https://wiki.termux.com/wiki/FAQ)）
- **[官方事实]** “Termux executes programs natively on Android host OS by default, without any emulation or containerization (docker/VM/chroot/proot), and uses the Android host kernel underneath... Programs are compiled with Android NDK and dynamically linked against Android system bionic... under `/system/lib`.”（[Termux execution environment](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)）
- **[官方事实]** 包安装在前缀 `$PREFIX=/data/data/com.termux/files/usr`，不能写到 `/bin`、`/usr`、`/etc` 等标准目录；包与路径按 `com.termux` 编译，换包名/用户/存储需要重编译。（同上；Termux packages 文件系统布局文档）
- **[官方事实]** Termux 免 root，使用 APT；它不是 rooting 工具，也不提供真实 root。（[termux.dev](https://termux.dev/)、Termux FAQ）
- **[官方事实]** Android 10 起，targetSdk ≥ 29 的 app 不能对 app home 目录内的文件调用 `exec()`：“Untrusted apps that target Android 10 cannot invoke `exec()` on files within the app's home directory. This execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file.”（[Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)）
- **[官方事实]** Termux 当前用 `targetSdkVersion=28` 维持兼容；`compileSdkVersion=36`。（[termux-app gradle.properties](https://github.com/termux/termux-app/blob/master/gradle.properties)）Termux 官方说明这是“backward compatibility mode”，并明确该豁免“may end in some future Android version”。（[Termux NLnet/NGI Mobifree 公告](https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant)）
- **[官方事实]** `termux-exec` 通过 `$LD_PRELOAD` 注入共享库，拦截 `exec()` 家族；2.5.0 增加 `system_linker_exec`，把 ELF 交给 `/system/bin/linker*` 以绕过 Android 10 的 app data exec 限制；执行后 `/proc/self/exe` 会变成 linker 路径，相关包需要读取 `TERMUX_EXEC__PROC_SELF_EXE` 修补。（[termux-exec-package](https://github.com/termux/termux-exec-package)、[commit 2fe4775](https://github.com/termux/termux-exec-package/commit/2fe477505c28efb96c61d1a750cc84fdff68c35d)）
- **[官方事实]** Android 对 `/proc/net/*`、`/proc/loadavg`、`/proc/stat`、`/proc/uptime` 等有访问限制；普通 app 只能看到自己的进程（`hidepid=2`）。因此 `htop`、`netstat`、`ps` 等工具会退化。（Termux FAQ）

### 2.2 兼容性与包管理

- **[官方事实]** Termux 仓库有 1000+ 包（bash、git、Python、clang、cmake 等），但它们是 **Bionic/NDK 移植**，不是任意发行版 `.deb`；glibc 程序不能直接链接运行。包管理是 Termux 的 APT 源，不是 Debian/Ubuntu 源。
- **[官方事实]** 前缀路径硬编码在所有二进制中；不能把前缀移到 SD 卡或任意目录；`/proc`/`/sys` 是受限视图。
- **[官方事实]** 没有 guest kernel，因此没有真实 systemd、Docker/Podman、内核模块、任意 mount、真实 root；这些只能在 Full Linux VM 内承诺。
- **[本项目未验证]** 我们的 App 若 targetSdk ≥ 29，Termux 风格的前缀能否通过 `termux-exec` 的 `system_linker_exec` 在**只读 APK `bin/` 前缀**上工作；官方文档没有覆盖这种前缀布局，必须自建 PoC。

### 2.3 与 Fast Lane 的关系

Host Termux 的原生 CPU 上限高于 QEMU TCG，但它没有 pVM 隔离、没有 vsock、没有 protected 内存，也没有 Fast Lane 的密封工具链边界。它可以作为**宿主侧工具环境/兜底**，不能作为 Fast Lane 的实现，也不能把它的性能数字当 Fast Lane 的数字。

> 注：P03 讨论的是把 Termux/静态工具放进 **APK `bin/` 作为 Fast Lane 的 sealed toolbox**；那是 guest/payload 内的只读工具集，不等于本文的 Host Termux。两者不能混称。

## 3. Termux glibc / `glibc-runner`：原生 glibc，但不是完整发行版

- **[官方事实]** `glibc-runner` 是 Termux 的 glibc 启动器；脚本用 `ld.so` 直接启动 glibc 二进制，核心路径是 `exec ... ld.so $@`；`patchelf --set-rpath ... --set-interpreter ...` 可把二进制改到 `$GLIBC_PREFIX/lib/ld-*`；命令别名 `grun`。`GLIBC_PREFIX` 默认为 `$APP_PREFIX/glibc`。（[glibc-runner.sh](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc-runner/glibc-runner.sh)）
- **[官方事实]** 它不经过 proot：这是宿主 CPU 上的原生 glibc 用户态，只是换了 loader/sysroot。
- **[官方事实/源码观察]** glibc 包对 Android 做了实质性补丁：删除 `clone3`、替换 `syscall`/`shm*`/`setfs*`、复制 `android_passwd_group.*` 与 `android_system_user_ids.h`、使用 `fakesyscall.json` 处理不支持的 syscall、安装 `shmem-android` 的 System V 共享内存模拟、`--disable-nscd`。（[gpkg/glibc/build.sh](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/build.sh)）
- **[官方事实]** 许可证不能一句话概括：glibc 上游是 LGPL-2.1；Termux 的 glibc 包元数据写 `GPL-3.0, LGPL-3.0`；`glibc-runner` 包写 `GPL-3.0`；它还依赖 `termux-exec-glibc`、`bash-glibc`、`patchelf-glibc`、`binutils-glibc`、`strace-glibc`。（同上与 [build.sh](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc-runner/build.sh)）
- **[社区经验]** glibc-on-Android 的 NSS、`/etc`、`/proc`、`seccomp`、`AT_HWCAP`、线程/信号差异需要用补丁和 fakesyscall 处理；不是“装上就能跑所有 glibc 程序”。
- **[本项目未验证]** 我们的目标发行版工具链、16 KB 页设备、Android 14/15/16 上的实际通过率；没有 PoC，不能承诺“可运行任意 glibc CLI”。

**判断**：`glibc-runner` 适合作为“某些 glibc-only CLI 的宿主兜底”，不适合作为完整发行版或 Fast Lane。它仍受 targetSdk/exec 门、前缀只读/可写边界和 GPL/LGPL 分发义务约束。

## 4. PRoot / proot-distro：原生指令 + ptrace 路径重写

### 4.1 机制

- **[官方事实]** “PRoot is a user-space implementation of chroot, mount --bind, and binfmt_misc... Technically PRoot relies on ptrace... when guest programs perform access to system resources, PRoot translates their requests before sending them to the host kernel... the guest kernel is never involved, regardless of whether QEMU user-mode is used or not.”（[proot-me.github.io](https://proot-me.github.io/)）
- **[官方事实]** 它不模拟 CPU；guest 指令由宿主 CPU 原生执行；跨架构才用 QEMU user-mode。
- **[官方事实]** `proot-distro` 是 Termux 的 PRoot 编排层：支持 OCI/Docker 镜像、rootfs tarball、跨架构 `qemu-user`；但“starting service supervisors (`systemd`, `OpenRC`, socket-activated daemons) is generally not possible”；“features that need real Linux kernel namespaces (`unshare`, container-in-container, network namespaces) do not work — proot is path translation, not kernel isolation”；并拒绝嵌套 proot。许可证 GPL-3.0-only。（[proot-distro README](https://github.com/termux/proot-distro)）

### 4.2 性能证据：不能用一个数字概括

- **[社区经验]** 同一台设备上的 `sysbench`/`glmark2` 对比（proot-distro issue #634，2026-02-08）：CPU 单核 `events per second` 约 1144 vs 1143（几乎相同）；内存约 3984 vs 1525 MiB/s（约 2.6× 慢）；glmark2 约 1979 vs 906 FPS（约 2.2× 慢）。维护者在该 issue 中直言：“If you care about performance, don't use proot-distro... proot performance penalty depends on complexity of its configuration.”（[issue #634](https://github.com/termux/proot-distro/issues/634)）
- **[社区经验]** 该样本是单设备、单版本、非控温/非随机化，不能外推为我们的设备或 pVM；但它足以说明“CPU 近原生、I/O/元数据/图形路径明显变慢”的方向。
- **[本项目未验证]** 我们的 apt/pacman、编译、git、Python 工作负载在 PRoot 下的开销；没有本项目 n≥10 数据，禁止写“提升/下降 X%”。

**判断**：PRoot 适合 CPU 密集、syscall 较少、文件元数据操作不重的 CLI；对包管理、编译的 `fork/exec/open/stat` 密集路径不友好。它是弱隔离的兼容层，不是 VM。

### 4.3 protected Microdroid 内不可行的证据边界

既有调研曾写“Microdroid 的 SELinux `neverallow ... ptrace` 直接禁止 PRoot”。本次核对应降级为：

- **[官方事实]** AOSP `microdroid_payload.te`（commit `7836cbb29`）中**没有**任何 `ptrace` allow，也**没有**显式 `neverallow` ptrace；只有 vsock、adbd unix socket、devpts、kmsg、`extra_apk_file` 等规则。（[microdroid_payload.te](https://android.googlesource.com/platform/system/sepolicy/+/7836cbb29/microdroid/system/private/microdroid_payload.te)）
- **[官方事实]** SELinux 默认拒绝：没有 allow 的权限不会自动成立。因此 PRoot 的 `PTRACE_TRACEME`/`PTRACE_SYSCALL` 在 payload 域内大概率不可用。
- **[本项目未验证]** 但“大概率”不是“官方 neverallow”；需要在 protected pVM 内做一次最小 ptrace 探针才能把结论从“无 allow”升级为“实测拒绝”。在证据闭合前，不应在产品/研究文档中写“官方明确 neverallow 禁止 PRoot”。

## 5. Microdroid / protected 的硬边界

- **[官方事实]** Microdroid 运行 APK 内的 payload 二进制及其共享库；APK 以 zipfuse/dm-verity 方式提供只读内容；官方定位是无 UI/无 Graphics/无 HAL 的最小 VM。（[Microdroid](https://source.android.com/docs/core/virtualization/microdroid)；本仓库既有调研亦引用该文）
- **[官方事实]** `microdroid_payload` 策略只允许 vsock 和 adbd unix stream，没有 `tcp_socket` 规则；这解释了 Fast Lane 的“无原生网络、终端/代理走 vsock”设计。
- **[本项目未验证]** 在 payload 内从 `/mnt/apk/bin` 执行 Termux/静态工具（P03）与在宿主 Termux 中执行是两条不同路径；前者是密封工具集，后者是宿主兼容层。PRoot 需要 ptrace，不能把“宿主 PRoot 可用”外推为“protected 内可用”。

## 6. 能力矩阵

| 能力 | Host Termux (Bionic) | Termux glibc (`grun`) | PRoot / proot-distro | QEMU Full Lane | Protected Fast Lane |
| --- | --- | --- | --- | --- | --- |
| CPU 执行 | 宿主 ARM64 原生 | 宿主 ARM64 原生 | 宿主原生 + ptrace | TCG 翻译，慢 | pVM 硬件 vCPU |
| Guest kernel | 无 | 无 | 无 | 有 | 有（Microdroid GKI） |
| 隔离 | app 沙箱 / 同 UID | 同左 | 弱；共享宿主内核 | VM 级 | pVM + SELinux |
| systemd/Docker | 否 | 否 | 否（无 cgroups/namespaces） | 是（guest 内） | 否 |
| 原生网络 | 是（Android 栈） | 是 | 是（共享宿主） | 是 | 否（仅 vsock） |
| GPU/显示 | 否 | 否 | 否（可借宿主 X/Wayland，非产品承诺） | VirGL（未完成真机验收） | 否 |
| 包安装 | Termux APT 源 | gpkg/pacman | 发行版包管理（弱隔离） | 完整发行版 | 仅 APK 内 payload |
| root | 否 | 否 | 假 root | guest root | 无通用 root |
| 性能证据 | 原生；本项目未测 | 原生；本项目未测 | CPU 近原生、I/O/图形变慢（社区单点） | T7 矩阵 `not-tested` | 硬件声称；未验收 |
| Runtime 类别 | Userspace Compatibility | Userspace Compatibility | Userspace Compatibility | Full Linux | Accelerated Linux Workspace（ACR-03 未决） |

## 7. 产品化前置门（G1–G5）

1. **G1（决定性）targetSdk/exec 门**：我们的 App 是否 targetSdk ≥ 29？若是，必须证明 `termux-exec` 的 `system_linker_exec`、APK 原生库目录或其他合法路径能在**自研包名/只读前缀**上执行；否则 Termux/glibc/PRoot 在第一次 `exec` 就失败。不得要求用户降 targetSdk 或改 SELinux。
2. **G2 只读 APK 前缀与静态/直接 syscall 程序**：统计 bash、coreutils、git、clang、Python、静态 busybox 的实际通过率；记录 `/proc/self/exe`、shebang、`LD_PRELOAD`、raw syscall 的失败样本。
3. **G3 Android 14/15/16 真机矩阵**：SELinux、`/proc` 限制、16 KB 页、厂商 ROM 差异；没有真机就保持 `not-tested`。
4. **G4 本项目工作负载的 PRoot 开销**：apt/pacman、编译、git、Python；n≥10、控温、同设备；禁止引用单一百分比或把社区数字当本项目结论。
5. **G5 许可证/分发**：Termux app GPLv3-only、PRoot GPLv2、proot-distro GPL-3.0-only、glibc-runner GPL-3.0、glibc 包 `GPL-3.0, LGPL-3.0`；若要随 APK 分发二进制，必须完成对应源码/重链接义务与法务审查。**未完成 G5 前不得打包。**

## 8. 证据分级表（决策相关）

| 结论 | 等级 | 来源 | 对决策的含义 |
| --- | --- | --- | --- |
| Termux 原生执行 ARM64、无 emulation/container | 官方事实 | Termux execution environment wiki | CPU 上限高，但不是 VM |
| Android 10 W^X 限制 app home 内 `exec()` | 官方事实 | Android 10 behavior changes | G1 是决定性门 |
| Termux 以 targetSdk 28 规避，未来可能失效 | 官方事实 | termux-app gradle.properties；Termux 2024 公告 | 不可把豁免当长期能力 |
| `termux-exec` 用 LD_PRELOAD/linker 绕过 exec 限制 | 官方事实 | termux-exec README/commit | 需在只读 APK 前缀上自证 |
| `glibc-runner` 用 `ld.so` 原生启动 glibc | 官方事实 | glibc-runner.sh | 可做 glibc CLI 兜底，不是发行版 |
| glibc 包有 NSS/shmem/clone3/fakesyscall 补丁 | 官方事实（源码） | gpkg/glibc/build.sh | 兼容性不是“装上就好” |
| PRoot 用 ptrace 重写路径、无 guest kernel | 官方事实 | proot-me.github.io | 弱隔离，不是 VM |
| PRoot CPU 近原生、I/O/图形变慢 | 社区经验 | proot-distro issue #634 | 需按 workload 实测，禁止单数字 |
| proot-distro 无 systemd/cgroups/namespaces、拒绝嵌套 | 官方事实 | proot-distro README | 不能当 Docker/服务环境 |
| Microdroid payload 策略无 ptrace allow、无 TCP | 官方事实 | AOSP `microdroid_payload.te` | PRoot 在 protected 内大概率不可用；需真机探针 |
| “Microdroid 官方 neverallow ptrace” | 本项目未验证/应降级 | 未找到显式 neverallow | 不要继续引用绝对表述 |
| 宿主 Termux/PRoot 可作为产品兜底 | 本项目未验证 | 无 PoC/无真机/无 G5 | 当前不产品化 |

## 9. 未验证与停止条件

- **未验证**：任何 Android 真机上的 Termux/glibc/PRoot 启动、exec、`/proc`、16 KB 页、targetSdk 集成；任何本项目的性能数字；任何 GPL/LGPL 法务结论；protected pVM 内的 ptrace 实测。
- **停止条件**：需要 Root/unlock/改 SELinux/改 pvmfw 才能继续 → 停止；需要把 Host 数字写成 Fast Lane 数字 → 停止；结论会改变 Runtime 类别 → 先提 ACR，不直接改 `CONTEXT.md`。
- 本文不改变 `CONTEXT.md`、不新增 Runtime 类别、不写产品承诺；若未来要把 Host Native 做产品化，应另开产品决策/ACR，并完成 G1–G5。

## 10. 来源

访问日期均为 2026-10-21。

1. Termux FAQ：https://wiki.termux.com/wiki/FAQ
2. Termux execution environment：https://github.com/termux/termux-packages/wiki/Termux-execution-environment
3. Termux app `gradle.properties`：https://github.com/termux/termux-app/blob/master/gradle.properties
4. Termux NLnet/NGI Mobifree 公告（2024-11-11）：https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant
5. Android 10 behavior changes（execute permission）：https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
6. termux-exec-package：https://github.com/termux/termux-exec-package ；`system_linker_exec` commit：https://github.com/termux/termux-exec-package/commit/2fe477505c28efb96c61d1a750cc84fdff68c35d
7. Termux glibc packages：https://github.com/termux-pacman/glibc-packages ；`glibc-runner.sh`：https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc-runner/glibc-runner.sh ；`gpkg/glibc/build.sh`：https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/build.sh
8. PRoot 官方站：https://proot-me.github.io/
9. proot-distro README：https://github.com/termux/proot-distro ；性能 issue #634：https://github.com/termux/proot-distro/issues/634
10. AOSP `microdroid_payload.te`（commit `7836cbb29`）：https://android.googlesource.com/platform/system/sepolicy/+/7836cbb29/microdroid/system/private/microdroid_payload.te
11. Android Microdroid 文档：https://source.android.com/docs/core/virtualization/microdroid
12. Termux app 许可证：https://github.com/termux/termux-app/blob/master/LICENSE.md
13. PRoot 许可证（GPLv2）：https://github.com/proot-me/proot/blob/master/COPYING
14. proot-distro 许可证（GPL-3.0-only）：https://github.com/termux/proot-distro/blob/master/LICENSE

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 本文未运行设备/构建；所有本项目性能与真机结论为 `not-tested`。
