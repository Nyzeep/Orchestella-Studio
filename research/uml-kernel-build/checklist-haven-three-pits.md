# Haven 三坑 checklist（构建期检查项 / 一页）

对象：本项目使用的 UML 内核产物链 `linux-bionic`（app 内改名 `libuml_kernel.so`）+ `stub_exe`（改名 `libuml_stub.so`），打包进 APK `lib/arm64-v8a/` 后从 `nativeLibraryDir` **exec**（research/uml-runner/README.md:4-25，访问 2026-10-31）。证据等级： Haven 一手发布说明/issue（项目官方自述）； 本仓库源码核对； `not-tested` = 本批次无设备。

---

## 坑① guest 内核被 Android 沙箱杀死：`set_robust_list` / `rseq` → signal 31 静默死亡

- **现象**（Haven v5.89.8 发布说明，2026-09-19，https://github.com/GlassHaven/Haven/releases/tag/v5.89.8 ，访问 2026-10-31）：
  > v5.89.7 relinked the guest kernel for the TCP stall fix and dropped a post-link step the previous kernel had: Android's app sandbox force-kills two syscalls the guest's libc issues at startup (`set_robust_list`, `rseq`), so the guest process died with signal 31 about 100 ms after launch — no console output, no network log, nothing to debug from. The shipped kernel binary is now neutered for those calls (uml-transport `uml-guest-4`), **a scan gate fails any kernel that ships without the step**, and a fresh guest boots on device.
- **机制假设**：内核 ELF 是 NDK static-bionic 可执行（v0.3.0：NDK r29 / API 30，IKCONFIG 实测），bionic 启动路径自身发起 `set_robust_list`(arm64 #99) / `rseq`(#293) 两个 **host** syscall；宿主 Android 沙箱 seccomp 判违规 → SIGSYS(31) 杀进程。**阉割位置 = 内核侧**（对产物 ELF 做 post-link neuter，使内核进程不再发起这两个 syscall）；**触发位置 = 启动器侧沙箱**（exec 的那个 app 进程受宿主 seccomp 约束）。
- **归因缺口（转工单 03）**：AOSP 标准 app seccomp 允许表通常放行这两个调用（普通 app 的 bionic 都会调）；「为何被杀」存在 OEM 定制 / Android 版本差异 / 启动器自有 filter 等可能。Haven 只给了现象与修法，未给 seccomp 编号证据 → [社区(项目官方自述)]，机制未独立复核。
- **已闭环（2026-10-31 fabric-batch2-A-policy 回写）**：上述机制假设中「bionic 启动路径发起这两个 syscall」与「标准允许表放行」两条均被源码**反转**——bionic（main+11/12/16/17-release）从不发起 set_robust_list/rseq（无 SYSCALLS.TXT 声明、全树无调用点），而 app seccomp 允许表（三分支 ALLOWLIST_APP+COMMON）均**不含**这两个调用、默认动作 SECCOMP_RET_TRAP=SIGSYS(31)。能发起两者的主流 libc 是 glibc≥2.35（dl-tls_init_tp.c:95/:111 主线程启动即注册）→ Haven 现象适用于其 glibc 系内核构建（Haven 树未公开，[未找到证据]），**不适用于本项目 NDK bionic 内核**——与会话 21 未 neuter 内核正常启动互证。**三选一结论：无需处理（保留，不阉割）**；本文检查项 1-2 转为条件性（仅当构建链改用非 bionic libc 时 neuter+scan gate 成对启用），构建链保持 NDK bionic 即无此风险。完整归因证据链与 W^X 映射表见 `docs/research/2026-11-03-selinux-seccomp-closure.md`（§3）。
- **本仓库现状**：Raymer8639 v0.3.0 构建链（`harness/build-bionic-termux.sh`）**没有** neuter 步骤、**没有** scan gate；源码树亦无 UML 侧处理（grep `arch/um/` 无果，本次核对）。会话 21 真机用未 neuter 的 release 原生二进制可跑，说明至少在当次设备/系统组合上未被杀 → 设备窗口必须复验（`not-tested`）。
- **设备复验闭环（2026-10-31 device-myron，Android 17 / SDK 37，HyperOS，36 域 untrusted_app_34 + ext4/ubd0 通道 + seccomp=on）**：未 neuter 的 release 资产（UP 与 SMP 两个 profile）各 10 次 am start 首启**全部存活**（`UMARM_BOOT_OK`，exit 0，无 SIGSYS），SMP 以 `ncpus=4` 起 4 核（**注意：该下游内核不认 `--cpus=`**，报 unknown param 透传 userspace，实际旋钮为 `ncpus=`，默认 host-1）；T1 全窗口 app 域 avc **exec 类 denied=0**。→ 「无需处理（不阉割）」获得 36 域 + ext4 + SMP 三重叠加的实证；`not-tested` 状态解除（证据 `evidence/uml-fabric-20261031/t1/`，报告 `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1）。
- **构建期检查项（可全自动）**：
  1. 静态扫描：产物 ELF 中 `set_robust_list`(#99)/`rseq`(#293) 立即数出现点（bionic 启动路径）——建 CI 扫描门，形态对标 Haven「scan gate fails any kernel that ships without the step」。
  2. 若决定 neuter：**neuter 与扫描必须成对**（Haven 的教训恰是「relink 丢了 post-link 步骤」——步骤不在构建脚本里就会在下次 relink 时消失）。
  3. 决策点（工单 03 联动）：阉割（Haven 路线）vs 保留 + 真机归因（本项目会话 21 证据倾向保留）。

## 坑② `ubda` 的 `F_SETLK` 锁残留 → 下次启动内核 panic（Haven#686）

- **现象**（Haven#686，2026-10-10，closed，https://github.com/GlassHaven/Haven/issues/686 ，访问 2026-10-31）——启动失败后 guest 进程泄漏、持有 rootfs.ext4 advisory lock，下次启动：
  ```
  F_SETLK failed, file already locked by pid <old>
  Failed to lock '.../files/uml/rootfs.ext4', err = 11
  ubda: Can't open ".../rootfs.ext4": errno = 11
  VFS: Cannot open root device "/dev/ubda" or unknown-block(0,0): error -6
  Kernel panic - not syncing: VFS: Unable to mount root fs on unknown-block(0,0)
  ```
- **机制**：**panic 机制在内核侧**——`os_lock_file()` 用 `fcntl(F_SETLK)` 锁 backing file（本仓库源码 `arch/um/os-Linux/file.c:583-608`，报错串逐字同上），ubd 打不开 backing file（`arch/um/drivers/ubd_kern.c:628`）→ root mount 失败 → panic。**触发与修复在启动器侧**：Haven 的修复是 v5.89.24 给 `UmlDesktopManager.fail()` 补 `closeGuest()`（teardown，#686 评论 2026-10-11）——**不是**改内核。
- **内核侧不应改**：锁失败降级为 warning 并继续挂载，会让两个 UML 共写同一可写 rootfs（数据损坏风险）；「拒绝 + 可诊断的报错串」是正确内核行为，持锁 pid 恰是泄漏诊断的关键输出。
- **构建期检查项（不可构建期拦截，启动器侧落地）**：
  1. 启动器铁律进验收清单：任何启动失败路径（banner 超时、console 异常、init 脚本失败）必须 teardown guest（poweroff → 硬杀兜底），对应 Haven `fail()` 缺 closeGuest 的教训。
  2. pre-start 探测：启动前对 rootfs 做 `F_GETLK` 试锁，持锁即报「旧 guest 未退」而非等内核 panic。
  3. 本仓库源码行为与上游一致（file.c 已核对），无需内核补丁；设备复现 `not-tested` → **2026-10-31 已复验（Haven#686 未复现）**：36 域 ext4/ubd0 通道，烧录中 `kill -9` 宿主 UML 进程 ×2 有效循环，重启同实例均 `EXT4-fs (ubda): recovery complete → mounted → UMARM_BOOT_OK`，无 F_SETLK panic、/proc 无残留持有者（证据 `evidence/uml-fabric-20261031/t3/`，报告 §3）。启动器侧锁清理方案无需立项。
- **可复用**：#686 的 panic 串可直接做本仓库日志监控的特征串。

## 坑③ 16 KB ELF 段对齐（对照 `tools/ci/check-apk-16kb.sh`）

- **要求**：16 KB page 设备要求 APK 内 native lib 的 PT_LOAD `p_align >= 0x4000`（ELF64/AArch64）。本仓库 gate：`tools/ci/check-apk-16kb.sh` = `zipalign -P 16` + `tools/ci/check-elf-16kb.sh`（后者要求全部 LOAD 段 >= 16384，tools/ci/check-elf-16kb.sh:44-48）。
- **实测（2026-10-31，llvm-readelf，本仓库同口径脚本 `research/uml-kernel-build/check-16kb-kernel.sh`）**：

  | 产物 | 16KB LOAD 对齐 |
  |---|---|
  | release `linux-bionic`（9,648,496 B） | **过** |
  | release `linux-bionic-smp`（13,272,376 B） | **过** |
  | release `stub_exe`（2,000 B） | **不过** |
  | 本次重建 `linux-bionic` | **过** |
  | 本次重建 `stub_exe`（与 release 逐字节一致） | **不过** |

- **机制**：内核本体（LLVM 链接）天然 16KB 对齐，达标；**stub 不达标且 release 原生如此**。stub 在 app 域是 `ProcessBuilder exec`（`stub_exe=<path>`，research/uml-runner/README.md:134），**不是 dlopen**——exec 从文件装载不要求 16KB LOAD 对齐，所以功能上能跑；但只要 stub 以 `libuml_stub.so` 名义进 APK `lib/arm64-v8a/`，现有 `check-apk-16kb.sh` 对「全部 *.so」的口径**今天就会拦它**。
- **机制假设标注**：绕开点在**装载方式**（exec vs dlopen），不是内核/启动器谁阉割——这一坑与前两坑不同类，是打包/装载语义问题。
- **构建期检查项（可全自动，二选一）**：
  1. **修**：stub 链接加 `-z max-page-size=16384`（stub 仅 2KB，重链代价约为零），产物进 APK 后全 gate 通过；与 release stub 的 sha256 恒等性会被破坏（比对口径要更新），行为等价性进设备窗口清单（`not-tested`）。
  2. **豁免**：`check-elf-16kb.sh` 加 exec-only 例外口径（按文件名或清单标注），并在 ACR 材料记录「为什么 stub 可以不对齐」。
- **联动注意**：`keepDebugSymbols += '**/libuml_kernel.so'`（research/uml-runner/README.md:45），否则 AGP 的 strip 会改变包内字节，破坏「与 release 资产 sha256 一致」的比对链。

---

## 与工单的关系

- 工单 01（本件）：给出三坑的机制定位与构建期检查项。
- 工单 03：坑①的 seccomp/SELinux 归因（`SYSCALLS.TXT` / `SECCOMP_ALLOWLIST_COMMON.TXT` 对照）。
- 工单 06：Haven 全量 issue 拆解（本 checklist 只覆盖三坑）。
- 设备窗口清单（2026-10-31 device-myron 轮更新，报告 `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`）：
  1. 未 neuter 内核在目标设备沙箱下的首启存活（坑①复验）——**已过**（36 域 UP+SMP 各 10/10，见坑①复验闭环行）。
  2. `-z max-page-size=16384` 重链 stub 后的启动功能等价（坑③修法验证）——仍 `not-tested`（设备为 4K 页宿主，无 16K 通道；4K 宿主上 release 未重链 stub 的 exec 装载持续可用：本轮 UP/SMP 全部启动 + T5 长驻均走该路径）。
  3. 启动失败注入 → teardown → 立即重启（坑②启动器验收）——**部分覆盖**：T3 kill -9 中断 → 重启 ×2 通过；完整的失败注入矩阵（banner 超时/取消/上游协程取消）仍待做（06 票 W-065）。另发现启动器 stop-file 看护缺口：进程已死时遗留的 `uml-stop` 会被下一次启动消费导致一循环非正常退出（T5 门1 cycle1），watcher 应在进程死后仍消费/清理 stop 文件。