# P12 实施报告：宿主 Termux / glibc / PRoot 兜底评估（零设备）

> 票：[P12-host-native-research.md](../../../.scratch/orchestella-fastlane/issues/P12-host-native-research.md)
> 主报告：[2026-10-21-host-native-lane-evaluation.md](../../../research/2026-10-21-host-native-lane-evaluation.md)
> 结论：**本阶段不产品化；保留为 Userspace Compatibility 兜底候选/内部对照；需要更多证据。** 不改 Runtime 类别、不改 `CONTEXT.md`、不写产品承诺。

## 1. 目标

回答：protected Fast Lane 不可用或能力不足时，宿主侧 Termux/glibc/PRoot 能否作为“无 Root、非 non-protected”设备的兜底 Linux 体验？与 QEMU Full Lane、Fast Lane 的边界是什么？

交付决策建议，不写产品承诺、不改 Runtime 类别。

## 2. 方法

- 只做桌面研究：官方文档、AOSP 策略源码、项目 README/脚本、单点社区基准。
- 每条结论标 **官方事实 / 社区经验 / 本项目未验证**，附 URL。
- 不跑 adb、不接真机、不做 Android 构建；宿主数据不冒充 Fast Lane 数据。
- 区分 Host Termux 与 P03 的 sealed toolbox：前者是宿主兼容层，后者是 APK `bin/` 内的只读工具集。

## 3. 关键结论

### 3.1 Termux 原生 Bionic

- **官方事实**：Termux 原生执行 ARM64，不做 CPU/系统模拟，使用 Android 宿主内核；包是 Bionic/NDK 移植，前缀 `$PREFIX` 硬编码，不能用任意发行版 `.deb`。
- **官方事实**：Android 10 起 targetSdk ≥ 29 不能对 app home 内文件 `exec()`；Termux 目前用 targetSdk 28 维持兼容，官方明确该豁免未来可能失效。
- **官方事实**：`termux-exec` 用 `$LD_PRELOAD`/linker 拦截 `exec()`；2.5.0 的 `system_linker_exec` 是对 Android 10 限制的绕行，但 `/proc/self/exe` 会变成 linker，相关包需修补。
- **本项目未验证**：我们的 App 若 targetSdk ≥ 29，能否在**只读 APK `bin/` 前缀**上复现该绕行；官方文档未覆盖。**G1 是决定性门。**
- 能力边界：无 guest kernel、无 systemd/Docker、无真实 root、无 GPU；`/proc` 受限使 `htop`/`netstat`/`ps` 退化。

### 3.2 Termux glibc / `glibc-runner`

- **官方事实**：`glibc-runner` 用 `ld.so` 直接启动 glibc 二进制，`patchelf` 设置 interpreter/rpath，命令别名 `grun`；不经过 proot。
- **官方事实/源码观察**：glibc 包删除 `clone3`、替换 `syscall`/`shm*`、使用 `fakesyscall.json`、`android_passwd_group`/`android_system_user_ids`、`--disable-nscd`；NSS/`/etc`/`/proc`/seccomp 差异真实存在。
- **官方事实**：许可证不是单一：glibc 上游 LGPL-2.1；Termux 包元数据 `GPL-3.0, LGPL-3.0`；`glibc-runner` 包 `GPL-3.0`。
- **判断**：可做某些 glibc-only CLI 的宿主兜底，不是完整发行版，不是 Fast Lane；仍需 G1/G5。

### 3.3 PRoot / proot-distro

- **官方事实**：PRoot 用 ptrace 把 chroot/bind/binfmt 请求翻译给宿主内核；guest 内核从不参与；CPU 近原生，隔离弱。
- **官方事实**：proot-distro 支持 OCI/rootfs，但无 systemd、无 cgroups/namespaces、无 Docker daemon、拒绝嵌套 proot；GPL-3.0-only。
- **社区经验**：issue #634 单设备样本显示 CPU 单核几乎相同、内存约 2.6× 慢、glmark2 约 2.2× 慢；维护者明确“在意性能就不要用 proot-distro”。该样本非控温/非随机化，不能外推。
- **本项目未验证**：我们的 apt/pacman、编译、git 工作负载开销；没有 n≥10 数据。

### 3.4 protected Microdroid 的边界（对既有调研的修正）

既有调研写“Microdroid 的 SELinux `neverallow ... ptrace` 直接禁止 PRoot”。本次核对：

- **官方事实**：AOSP `microdroid_payload.te`（commit `7836cbb29`）中**没有** `ptrace` allow，也**没有**显式 `neverallow`；只有 vsock、adbd unix socket、devpts、kmsg 等规则。
- **官方事实**：SELinux 默认拒绝，没有 allow 的 ptrace 不会成立；payload 策略也没有 `tcp_socket`，与“无原生网络、走 vsock”一致。
- **本项目未验证**：PRoot 在 protected pVM 内被拒绝需要真机 ptrace 探针；在证据闭合前，不应继续引用“官方明确 neverallow”的绝对表述。正确表述是“无 ptrace allow，默认拒绝，实测 `not-tested`”。

### 3.5 能力矩阵（摘要）

| 能力 | Host Termux | glibc-runner | PRoot | QEMU Full Lane | Protected Fast Lane |
| --- | --- | --- | --- | --- | --- |
| CPU | 原生 ARM64 | 原生 ARM64 | 原生 + ptrace | TCG | pVM 硬件 vCPU |
| Guest kernel | 无 | 无 | 无 | 有 | 有 |
| systemd/Docker | 否 | 否 | 否 | 是 | 否 |
| 原生网络 | 是 | 是 | 是 | 是 | 否（vsock） |
| GPU | 否 | 否 | 否 | VirGL（未验收） | 否 |
| Runtime 类别 | Userspace Compatibility | Userspace Compatibility | Userspace Compatibility | Full Linux | Accelerated Linux Workspace（ACR-03 未决） |

## 4. 决策建议

**主建议：本阶段不产品化。** 不新增 Runtime 类别、不做产品承诺、不进入发布说明。

**备选：作为 Userspace Compatibility 兜底。** 仅在 G1–G5 全部有证据后，以独立名称/能力矩阵/徽章提供“宿主内核上的用户态兼容层”，明确不是 VM、不是 Fast Lane。

| 门 | 内容 | 当前状态 |
| --- | --- | --- |
| G1 targetSdk/exec | targetSdk ≥29 时，`termux-exec`/APK 原生库/其他合法路径能否执行 | **未验证（决定性）** |
| G2 只读 APK 前缀 | bash/coreutils/git/clang/Python/静态 busybox 通过率；`/proc/self/exe`、shebang、raw syscall | 未验证 |
| G3 Android 14/15/16 真机 | SELinux、`/proc`、16 KB 页、厂商 ROM | 未验证 |
| G4 本项目 PRoot 开销 | apt/pacman、编译、git、Python；n≥10、控温 | 未验证 |
| G5 许可证/分发 | Termux GPLv3-only、PRoot GPLv2、proot-distro/GPL-3.0、glibc-runner/GPL-3.0、glibc 包 GPL/LGPL | 未验证；未完成不得打包 |

**停止条件**：需要 Root/unlock/改 SELinux/改 pvmfw 才能继续 → 停止；需要把 Host 数字写成 Fast Lane 数字 → 停止；结论改变 Runtime 类别 → 先提 ACR，不改 `CONTEXT.md`。

## 5. 未验证 / not-tested

- 任何 Android 真机上的 Termux/glibc/PRoot 启动、exec、`/proc`、16 KB 页、targetSdk 集成；
- 任何本项目的性能数字（全部 `not-tested`）；
- protected pVM 内的 ptrace 实测；
- GPL/LGPL 法务结论；
- 宿主 x86/arm64 的 PRoot 测试也未运行（本环境未安装 PRoot）。

## 6. 来源

主报告 §10 列全部 URL；关键来源：

- Termux FAQ：<https://wiki.termux.com/wiki/FAQ>
- Termux execution environment：<https://github.com/termux/termux-packages/wiki/Termux-execution-environment>
- Android 10 W^X：<https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission>
- termux-app targetSdk：<https://github.com/termux/termux-app/blob/master/gradle.properties>
- termux-exec：<https://github.com/termux/termux-exec-package>
- glibc-runner：<https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc-runner/glibc-runner.sh>
- PRoot：<https://proot-me.github.io/>
- proot-distro：<https://github.com/termux/proot-distro>；issue #634：<https://github.com/termux/proot-distro/issues/634>
- Microdroid payload 策略：<https://android.googlesource.com/platform/system/sepolicy/+/7836cbb29/microdroid/system/private/microdroid_payload.te>
- Microdroid：<https://source.android.com/docs/core/virtualization/microdroid>

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 未运行设备/构建；本项目性能与真机结论均为 `not-tested`。
