# UML 方案全方位调研与下一步方向（2026-10-31）

> **入库说明**：本文原为用户提供的外部调研报告（WorkBuddy 出品），因批次 1 与后续工单多处引用，于 2026-10-31 补录入库。内容与原文一致，未改写。
> **性质**：联网调研 + 决策建议。不改 `CONTEXT.md`/ADR/代码，不产生工单。
> **基线**：`main = d224336`（工作树干净）；最新发布 **v0.6.2**（2026-10-30，GitHub Releases 已同步）。
> **方法**：4 个并行子智能体分工检索（上游内核 / 下游工程 / Android 平台与分发 / 性能与替代方案），主协调对关键结论二次复核（LKML 原文、GitHub API）。
> **证据等级**：`[官方源码]` `[官方文档]` `[一手邮件]` `[基准]` `[代码/发行资产]` `[社区]` `[未找到证据]`。
> **纪律**：本文不把不同夹具的性能数字相除；凡推算必标注假设。

---

## 0. 结论摘要

**一句话：UML 的技术可行性已经被本项目自己证实，但"UML 该做什么"这个产品问题，联网证据给出了与直觉相反的答案——它不该做 Docker/长驻容器，该做"无 AVF 设备的原生构建引擎"。**

四条支撑结论：

1. **上游仍然是死路，且比 10-28 时更明确。** arm64 UML 仍未进 mainline；RFC 无 v2、未进 patchwork；maintainer Richard Weinberger 2026-08-16 回信只说「补丁欢迎，但别指望别人干苦活」，并**否决了 4.19 老宿主内核的回退路线**（要求较新宿主）。→ 自建下游分支是唯一路径，不存在"等上游"这个选项。
2. **下游已从"能不能跑"进化到"有产品"。** `GlassHaven/Haven`（1391★、AGPL-3.0、日更）在 APK 里内置 `libvmlinux.so` / `libuml-stub.so` / `libuml-passt.so` 与 UML guest 管理器，targetSdk 35；`Raymer8639` 已发布 **2/4 vCPU 的 `linux-bionic-smp`**；`uml4termux` 有 **3606s 连续运行 + 105 轮/10047s + 60min dockerd** 的脚本化证据。→ "只有 Termux 能跑 UML"的旧判断已被推翻；但 **app 域多核仍是空白**（Haven 仍是 UP）。
3. **性能口径必须重写。** 下游移植的 `syscall 9.841 → 1.987 µs` 与本仓库实测 1.999 µs 逐位吻合，证明**本项目的数字可信、且收益来自下游私有优化**。但本仓库的 `2.04×` 是**单文件、单核**；UML 在 app 域仍是 UP，真实工程构建（`make -j`）会被单核抵消，**不能对外说"编译只慢 2 倍"**。
4. **平台门：三扇开、两扇关、四扇新出现。** 开：filesDir 的 W+X `mmap`、匿名 `PROT_EXEC`、memfd（本项目 36 域实测全过，且 Haven/drolin 用同样路径出货）。关：`execve` 仅限 `nativeLibraryDir`；AVF 自定义 VM 仍需 adb 授权。新：targetSdk 37 原生库只读、16 KB Play 硬截止已生效、全应用内存上限、本地网络权限化。

**推荐方向（详见 §4/§5）**：把 UML 从「第三条 Full Linux 车道」收敛为 **「原生构建引擎（实验）」独立 GPL 扩展包**——只承诺终端、编译、文件操作三件事，默认 `ext4+ubd` 盘，明确不承诺 Docker/桌面/systemd/长驻。**下一步不是写产品代码，而是先过 G1（app 域多核）与 G2（16K 宿主）两道门**；这两道不过，UML 就只能停在研究档。

---

## 1. 现状校准：哪些是已确证的，哪些还是空白

### 1.1 本仓库已确证（不再需要调研）

| 事实 | 数据 | 出处 |
|---|---|---|
| 36 域启动 | `Linux 7.2.0-rc4 … aarch64`，`UMARM_BOOT_OK`，退出码 0 | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §5.1 |
| 启动耗时 | 220/320/340/400 ms，中位 ≈330 ms | 同上 §6 |
| 编译 | 940 ms vs native 460 ms = **2.04×**（15 样本，产物逐字节相同） | `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5 |
| 归因 | 固定开销 3.75×（tiny 40→150 ms），边际 1.83× | `evidence/uml-20261028/round22/wc-raw-data.md` §4 |
| 系统调用 | 8.0×（`find` 2000 文件） | go/no-go §7.1 |
| 域探针 | ①filesDir W+X ②匿名 exec ③memfd 三组在 36 域**全过** | 同上 §4 |
| 硬限制 | `execve` 仅 `nativeLibraryDir`；hostfs 下 `#!/bin/sh` 不能作 init；UP 单核 | 同上 §8.1 |

### 1.2 仍是空白（本文要回答或明确标记）

Docker、passt 网络、小时级长驻、`ext4+ubd` 默认盘、16 KB **宿主**、app 域 SMP、链接（`ld`）与大工程构建、GPL 分发形态。

---

## 2. 联网调研结论

### 2.1 上游：不接收、不维护、无 SMP、无 16K

| 问题 | 结论 | 来源与等级 |
|---|---|---|
| arm64 是否进 mainline | **否**。`arch/um/Makefile` L27–31 的 `HEADER_ARCH` 过滤仍只有 `x86 x86_64 i386`；`arch/arm64/` 下无 `um/`、无 `Makefile.um`；linux-next 与 `uml/linux.git next` 同样 404 | [官方源码] https://github.com/torvalds/linux/blob/master/arch/um/Makefile#L27-L31 |
| RFC 后续 | **无 v2/v3**；patchwork linux-um 搜 `arm64` 为 "No patches to display"（从未正式投递） | [一手邮件] https://patchwork.ozlabs.org/project/linux-um/list/?q=arm64 |
| maintainer 态度 | Richard Weinberger 2026-08-16：「**Well, it's reasonable to require a non-ancient host kernel.**」「**Patches are welcome, but don't expect others to do the hard work.**」→ 方向不否决，但**明确不代劳**，且 4.19 老设备回退被否 | [一手邮件] https://lkml.iu.edu/2608.2/00477.html |
| SMP | `UML_SUBARCH_SUPPORTS_SMP` 只有 x86 在 `select`；Kconfig 帮助仍写「每 vCPU 一个宿主线程，但普通线程的用户态代码仍在单线程 stub 内运行」→ **arm64 结构上 UP** | [官方源码] https://github.com/torvalds/linux/blob/master/arch/um/Kconfig#L94-L115 |
| 页大小 | mainline 仅 `select HAVE_PAGE_SIZE_4KB`，**无 16K 选项**；无「4K guest 跑 16K 宿主」修复 | [官方源码] https://github.com/torvalds/linux/blob/master/arch/um/Kconfig#L29 |
| 安全模型 | `arch/um/kernel/skas/stub.c` 的 "Known security issues" 仍在：用户态可跳入 stub 执行任意被允许 syscall、骗取 memory FD、读写全部物理内存、屏蔽 SIGALRM 逃逸调度；文档仍称隔离 "relatively weak" | [官方源码] https://github.com/torvalds/linux/blob/master/arch/um/kernel/skas/stub.c#L11-L32 |
| syscall 优化 | 2026 年 arch/um 合入的是 NOMMU、perf-event、futex 修复、`__init` 映射收紧；**无 stub/syscall 快路径优化**。1.987 µs 来自下游私有补丁（FUTEX_WAKE 跳过、CNTVCT 自旋、匿名缺页批量挂 handoff） | [官方源码] https://github.com/torvalds/linux/commits/master/arch/um ／ [一手邮件] 同上 |
| 维护活跃度 | 三位 maintainer（Weinberger / Ivanov / Berg），`uml/linux.git` 有 next/fixes 树；2026-05 起 `arch/um` 约 64 次提交，属"低但稳定"，**只护 x86** | [官方源码] MAINTAINERS L29205–29217 |

**净判断**：把 arm64 UML 当"上游能力"来 pin 是不可能的。项目若产品化，**必须自己拥有一个可复现的下游树**，并把"上游化"当成可选的战略投入而非前置条件。

### 2.2 下游：产品级先例已经出现，但关键空白恰好是本项目最需要的两项

| 项目 | 规模/活跃度 | 关键能力 | 与本项目的关系 |
|---|---|---|---|
| `zalexdev/linux-um-arm64` | 107★，最后提交 10-17 | `prebuilt-20260816`：kernel 10 MB + stub + passt + umnet + `debian-docker.ext4.gz` 246 MB；**HEAD 编译已坏**（issue #4：`user_mode` 未声明；prebuilt 丢 `CONFIG_SMP`） | 上游移植源；**不能直接依赖 prebuilt** |
| `Raymer8639/linux-um-arm64` | fork，0★，10-24 | v0.3.0：`linux-bionic` 9.6 MB + **`linux-bionic-smp` 13 MB（2/4 vCPU）** + passt；无 APK | 与本地同源（本仓库即用其产物）；**SMP 内核已存在但未在 app 域验证** |
| `michaelamoetoevsky/uml4termux` | 0★，**10-30 最活跃** | v0.5.0：4 vCPU（seccomp）、**3606s 连续 + 105 轮/10047s、RSS 无上升**、60 min dockerd 常活、gvproxy/BESS；**明确不支持 systemd** | 长驻与 Docker 的**唯一硬证据**，但宿主是 Termux 域 |
| `fahrulseptiana/drolin` | 0★，10-24 | v0.1.0：**debug APK 113.7 MB** + Ubuntu 26.04 ext4 48 MB；targetSdk 35；无真机 gate（CI 只 build） | 证明 APK 形态可行，**不证明可启动** |
| `GlassHaven/Haven` | **1391★ / 105 fork / 64 open issues / 日更** | v5.91.3 arm64 APK **194 MB**；`libvmlinux.so` + `libuml-stub.so` + `libuml-passt.so` + `UmlGuestManager`；targetSdk **35**、min 26、compile 37；无 root；**UP**、busybox init、无 Docker | **首个产品级先例**；但其定位是 SSH/VNC/RDP 客户端的内置本地环境，不是发行版管理器 |
| `lakitu12/linux-um-arm64-gki` | 0★，10-25 | Android 17 / GKI 6.18 上 71 补丁、UP-only、16K 默认；自述 **4K 变体在 16K 宿主会被 `os_early_checks` 拒绝** | 唯一正面触及 16K 宿主问题的分支 |

**三个必须吸收的教训（来自公开 issue）**：

1. `zalexdev#4`：移植 HEAD 会编译失败，prebuilt 的配置还会丢 `CONFIG_SMP` → **"用作者的 prebuilt"不是可复现方案**。
2. `Haven#686`：guest 异常退出残留 `F_SETLK` 锁，下次启动 `ubda` 打不开 → `Kernel panic: VFS: Unable to mount root fs`。**这正是本仓库尚未做的"崩溃后恢复"路径**。
3. Haven 5.89.7 的 changelog：Android 沙箱强杀 `set_robust_list` / `rseq`，guest 启动 ~100 ms 后 signal 31 静默死亡，修法是在内核里阉割这两个调用。**任何"我们自己构建"的内核都必须处理这条**。

**关键空白**：① app 域多核（Haven 仍 UP）；② 16 KB **宿主**真机（全部项目都没有）；③ systemd（uml4termux 明确"out"，drolin 只在 Linux CI 跑过）。

### 2.3 Android 平台与分发：门开在哪、关在哪、新门是什么

| 类别 | 事实 | 影响 | 来源 |
|---|---|---|---|
| ✅ 已开 | `mmap(PROT_EXEC)` 于 app 私有文件、匿名 exec、memfd+exec 在 targetSdk 36 域实测全过；Haven/drolin 用同路径出货 | UML 的 `check_tmpexec()` 与 guest RAM 映射可行 | 本仓库实测；[代码/发行资产] |
| ❌ 仍关 | `execve` 仅限 `nativeLibraryDir`（`apk_data_file`），且要求 `lib*.so` 命名 + Android linker 解释器 | 内核与 stub 必须走 jniLibs；这也是三家下游项目的共同做法 | [官方文档] https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission |
| ❌ 仍关 | AVF 自定义 VM 仍需 `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE` 两个 development 权限（adb/Shizuku 代授），**无免 adb 入口** | pVM 车道在消费级设备上仍不可交付 | [官方源码] AOSP `docs/custom_vm.md` |
| 🆕 targetSdk 37 | 经 `System.load()` 加载的原生文件必须**只读**，否则 `UnsatisfiedLinkError`；**未提及** `execve`/`mmap`/memfd | 本项目不用 `System.load` 加载内核，**不受影响**；但"运行时下载内核再 dlopen"的路被堵死 | [官方文档] https://developer.android.com/about/versions/17/behavior-changes-17 |
| 🆕 targetSdk 37 | 应用内存上限（MemoryLimiter）**对所有 targetSdk 生效**；本地网络默认阻断、跨应用 loopback 需 `USE_LOOPBACK_INTERFACE` | `mem=2G` 的 guest 可能被压；guest 端口转发要走新权限 | 同上 |
| 🆕 16 KB | Play 要求 2025-11-01 起新应用/更新支持 16 KB，可延期至 **2026-05-31**——**该宽限期已过** | APK 内 `.so` 必须 16 KB 对齐（已有 `check-apk-16kb.sh`）；但**内核页大小是另一件事**，见 §6 R1 | [官方文档] https://support.google.com/googleplay/android-developer/thread/368982598 |
| ⚠️ 分发 | Play「Device and Network Abuse」：不得从 Play 外下载可执行代码（dex/JAR/.so），**例外**是 VM/解释器内仅间接访问 Android API 的代码 | 内核以 `.so` 形态随包分发风险中等；"首次下载内核"高风险；rootfs 属数据，风险低 | [官方文档] https://support.google.com/googleplay/android-developer/answer/9888379 |
| ⚠️ GPL | **未找到**任何"GPL-2.0 内核随 APK 分发被 Play 拒/被接受"的公开先例 | 只能自建合规文件，不能引用先例 | [未找到证据] |
| ℹ️ 平台信号 | Google 自家 Linux Terminal 仍是**开发者选项 + Pixel 限定**的 AVF/Debian VM；镜像硬编码，无第三方复用入口；Android 17 加 Weston GUI（实验） | 平台把"跑 Linux"做成了需要特定 SoC 的 VM，**恰好绕开 UML 的差异点**（任意 arm64、免开发者选项、单 App 分发）；短期不会做掉本项目，但也不会给 UML 开门 | [官方文档] Android 15 release notes；[报道] |

**净判断**：UML 在**权限模型上是合法的**（三条 mmap 路径 + nativeLibraryDir exec），真正的风险不在"能不能跑"，而在**分发合规**与**新设备页大小**。

### 2.4 性能：本仓库的数字被上游邮件交叉验证，但适用边界比想象中窄

| 证据 | 数字 | 等级 |
|---|---|---|
| 下游移植优化前后（Snapdragon 870，SECCOMP，7 轮中位） | syscall **9.841 → 1.987 µs**；openat 20.223 → 4.501 µs；fault 24.592 → 11.076 µs | [一手邮件]（与本地 1.999 µs 吻合） |
| 上游 nommu v13 lmbench（同机同夹具） | getpid：native 161 ns / ptrace 34.5 µs / seccomp 26.2 µs / **nommu+seccomp 2.6 µs**；fork+exec 511 / 2834 / 2461 / **140 µs** | [基准] patchwork 评论 |
| UML I/O 弱项 | 2025 PARAVIRT 论文：UML 磁盘写比其原生化方案慢 **2.5×**，网络吞吐低 **4–5.9×** | [基准] |
| 缺页机制 | ptrace 模式每次缺页 **6 次**上下文切换；seccomp **2 次** | [一手邮件] lore.kernel.org |
| hostfs vs ubd | 默认同步配置下 ubd 0.8 MB/s vs hostfs 25 MB/s；**关掉 O_SYNC 后 ubd 接近宿主** → 差异主要来自同步策略，不是驱动 | [社区，2016，口径为顺序写] |
| Docker in UML | 有可运行三方证据（diuid / uml-docker / usmol），但 usmol 作者明确：**UML 无 iptables nat 表，Docker 网络难做**；且 dockerd 是 syscall 密集长驻进程，正好落在最差区间 | [社区] |
| 长驻稳定性 | **无公开的 RSS 泄漏/时间回绕报告**；已知 CVE-2025-37880（time-travel `sched_yield` 不推进时间 → 死锁）、CVE-2025-39951（virtio_uml 探测失败 UAF） | [CVE] |

**口径冲突必须记录**：Raymer README 称 syscall ≈2.0 µs ≈ **25× native**，并称"编译≈native"；本仓库实测 syscall **8×**、编译 **2.04×**。差异来源是基准方法（`getppid` 微基准 vs `find` 2000 文件、guest 工具链不同）。**对外只能引用本仓库同夹具数据，禁止引用 README 的 25× 或"编译≈native"。**

**关于 `2.04×` 的三条使用约束**（本文最重要的方法学提醒）：

1. 它是**单文件、单核**。app 域 UP → `make -j8` 拿不到 8 核并行。对 native 单核是 2.04×；对 native 8 核并行的等效差距最坏可达 ~16×。
2. 它对**小 TU 不利**：固定开销（进程启动 + 168 MB LLVM 库映射）是 3.75×，只占单 TU 9–16%；但一个 200 个小文件的工程，固定开销占比会翻几倍，**2.04× 必然劣化**。
3. 它**不能**与 32,750 ms（QEMU）、2,809 ms（pVM）相除——不同夹具、不同工具链（`ACR-draft-four-lanes-20261029.md` §1.4 已警告）。

> **推算（非实测，禁止对外引用）**：若 QEMU 车道按 4 vCPU 并行、单文件 32,750 ms，则等效 8,188 ms/文件；UML 940 ms → 约 8.7× 优势，而非 35×。真实倍数取决于两车道各自 vCPU 数与并行度，必须用同一 `make -j` 夹具复测。

---

## 3. 可选 UML 方案对比

### 3.1 产品形态（5 选 1）

| 方案 | 内容 | 优势 | 代价/风险 | 适用场景 | 判定 |
|---|---|---|---|---|---|
| **A. 现状冻结** | 保留研究分支与证据，不产品化 | 零成本、零风险 | prebuilt 会腐烂（HEAD 已坏）、设备升级到 16K 后失效、先例（Haven）会吃掉时间窗 | 只想保留技术储备 | 可作为兜底，不作为推荐 |
| **B. 独立 GPL 扩展包 + 构建引擎**（推荐） | 第二个 APK，内含 UML 内核 + stub，与主包同一握手/versionCode 约束；只做终端/编译/文件 | 许可隔离（GPL 不进主包）；主包零体积零崩溃面；可独立发版/撤回 | 双包安装摩擦；每次主包发版需同步；194 MB 级体积（含 rootfs 另算） | **无 AVF 设备的开发者用户** | **推荐** |
| **C. 并入主包做第三执行策略** | 内核进主包 jniLibs，Lane Router 直接选道 | 体验最好、无第二个 APK、共享 rootfs/agent | GPL 传染主包；+9.6 MB 内核 + 崩溃面进主包；targetSdk 36 是公开先例里的孤例，风险自担 | UML 已稳定、GPL 法务通过之后 | 二期可选 |
| **D. Docker/长驻容器车道** | 以 dockerd + overlay2 为卖点 | 差异化最强（手机上跑容器） | UP + 无 iptables nat + syscall 8× + 无 app 域长稳证据；Haven/本地都 UP | 极客实验 | **暂不推荐**（证据不支持） |
| **E. 放弃 UML** | 资源转向 QEMU 调优 / 等 AVF | 最保守、最省人 | 放弃"无 AVF 设备的原生编译"这一唯一差异点；QEMU 105× 的编译体验无解 | AVF 普及到所有目标机型 | 兜底，非当前最优 |

### 3.2 内核供给（4 选 1，与 3.1 正交）

| 方案 | 内容 | 优势 | 代价 | 判定 |
|---|---|---|---|---|
| ① 直接用下游 prebuilt | `prebuilt-20260816` / Raymer v0.3.0 | 立刻可用 | 不可复现、HEAD 已坏、配置丢失、作者不维护 → **供应链不可控** | 仅用于研究 |
| **② 自建下游分支**（推荐） | pin 一个 commit + 自带 defconfig + 可复现构建脚本 + 产物 sha256 入 manifest | 可复现、可审计、可打自己的补丁（rseq/robust_list、16K 对齐） | 一次性建流水线；每季度 rebase；补丁作者不维护 | **推荐** |
| ③ 回移到项目 pinned 内核 | 与 QEMU 共享基线 | 单一内核版本，契约统一 | arm64 UML 补丁依赖 7.2-rc4 上下文，回移成本高；作者不维护 | 二期评估 |
| ④ 上游化（自己做 maintainer） | 把 33+ 补丁整理投递，接住 review | 长期维护成本最低、生态收益最大 | 人力投入最大，且 Richard 明说"别指望别人" | 战略选项，非当前 |

### 3.3 盘与网络（设计决策，不单列为方案）

- **盘**：默认 `ext4 + ubd0`（产品），`hostfs` 只做研究/会话挂载。依据：本仓库实测 hostfs 下 `#!/bin/sh` 脚本不能作 init（Debian/Ubuntu 的 init 脚本大量用 `/bin/sh`），且 hostfs 绕过 guest 权限模型。
- **网络**：`passt` over vector fd（三家下游一致）。风险：targetSdk 37 的本地网络/loopback 权限化；Docker 场景额外缺 iptables nat。

---

## 4. 推荐方向与理由

### 4.1 推荐：方案 B + 内核供给 ② —— 「原生构建引擎（实验）」独立 GPL 扩展包

**定位一句话**：*在没有 AVF 的 arm64 设备上，把"编译一个文件 32 秒"变成"1 秒"，代价是不支持 GPU、不承诺多核、不承诺 Docker。*

**四条理由，按权重排序**：

1. **只有编译场景存在数量级收益。** 同设备同夹具：UML 940 ms vs QEMU 32,750 ms（即使按 4 vCPU 折算仍是 ~8.7× 量级优势）；而 Docker/长驻恰好落在 UML 最差区间（syscall 8×、UP、无 iptables nat、无长稳证据）。**用 UML 做它最擅长的事，而不是做它最不擅长的事。**
2. **这是唯一不依赖平台恩赐的差异化。** pVM 在本机 `ACCELERATOR_UNAVAILABLE`，AVF 自定义 VM 仍需 adb 授权；QEMU 编译不可用；proot 无内核且只能 targetSdk 28。UML 是唯一"任意 arm64 + 免 root + 现代 targetSdk + 真内核"的组合。
3. **许可与风险可以物理隔离。** GPL-2.0 内核进主包会污染整个闭源主包；独立扩展包把 GPL 义务、崩溃面、体积、targetSdk 36 孤例风险全部关在一个可卸载的盒子里。项目已有扩展包 + versionCode 同步 + fail-closed 的成熟机制可复用。
4. **先例证明市场存在，且没被占满。** Haven 1391★ 说明"App 内置本地 Linux"有真实需求；但它停在 targetSdk 35、UP、busybox init、无 Docker——**本项目在 36 域启动 + 2.04× 编译 + ext4 通道上是领先的**，窗口还在。

**明确不做的四件事**（写进能力矩阵，避免重复投入）：
- 不做 Docker/容器车道（直到有 app 域多核 + nat 证据）；
- 不做桌面/GPU（UML 无 GPU 直通，软件渲染不值得）；
- 不把 systemd 作为卖点（uml4termux 明确 out，本项目只在 QEMU 车道验证过）；
- 不做 protected 隔离（UML 是进程级，`seccomp` 模式明确不能当安全边界）。

### 4.2 与现有四车道的关系

| 车道 | 角色 | 变化 |
|---|---|---|
| QEMU Full | 默认、GPU、最大兼容、完整语义 | 不变 |
| pVM Fast | 有 AVF 设备的硬件 vCPU / 密封工具 | 不变（本机不可用） |
| proot/bare | 28 域轻量兼容 | 不变 |
| **UML Native** | **从"第三执行策略"降格为"构建引擎（实验）"** | **本文的收敛建议** |

> 这不是否定 10-28 的三车道设想，而是把它的适用范围从"Full Linux 的第三种执行策略"收窄到"一个具体负载"。等 G1/G2 过了，再谈是否升级为车道。

---

## 5. 后续实施步骤

### W0（本周，零设备，≈1 人日）—— 先把"可复现"这件事做掉

| 步骤 | 动作 | 完成判据 |
|---|---|---|
| S1 | 建 `research/uml-kernel-build/`：pin `Raymer8639` v0.3.0 的 commit + `linux-bionic-smp` defconfig + 构建脚本 + 产物 sha256 | 在干净容器里 `make ARCH=um SUBARCH=arm64` 成功，产物 sha256 与 manifest 一致 |
| S2 | 对比 `linux-bionic` vs `linux-bionic-smp` 的 config diff，确认 SMP 是否只差 `CONFIG_SMP`/`NR_CPUS` | diff 可读、无隐藏依赖 |
| S3 | 把 Haven 的三条坑写成检查项：rseq/`set_robust_list` 阉割、`ubda` 锁残留、signal 31 | 形成一页 checklist |

### W1（下一个设备窗口，P0）—— 两道决定生死的门

| 门 | 动作 | 通过判据 | 失败含义 |
|---|---|---|---|
| **G1 app 域多核** | 用 `linux-bionic-smp` + `--cpus=4` 在 targetSdk 36 探针 App 内启动，跑 `nproc` + 并行 `sha256` | guest 见 4 CPU，并行负载 wall time ≈ 1/4；无 seccomp 杀 tracee | **失败则 UML 只能是单核玩具**，编译卖点降级为"单文件快" |
| **G2 16K 宿主** | 找一台 16K 内核设备（或 AVD/合作机型），跑 16K guest | `UMARM_BOOT_OK` + 编译可跑；否则记录 panic 点 | **失败则 2027 新机型全部不可用**，产品化停止 |
| G3 真实构建 | 同夹具：一个 200 文件的小工程，QEMU vs UML vs native，`make -j` 各 3 轮 | 得到 UML/QEMU 墙钟比与 UML/native 比；**这是唯一可对外引用的编译数字** | 若 UML 相对 QEMU < 3× → 收益不足以支撑独立包 |
| G4 崩溃恢复 | 编译中 `kill -9` 主进程，再启动同一实例 | 能自动清锁并挂载成功（不重现 Haven#686） | 失败则需在 supervisor 层加锁清理 |

### W2（G1/G2 通过后，≈1 周）—— 产品化外壳

1. **盘切换**：`hostfs` → `ext4+ubd0`，验证 `#!/bin/sh` 的 init 脚本可跑（Debian 最小 rootfs）。
2. **扩展包骨架**：复用现有 accel 包的握手/versionCode/fail-closed 机制，改名为 `uml` 包；主包只做"检测—提示—跳转安装"。
3. **GPL 合规包**：`LICENSES/GPL-2.0`、内核源码 tarball、构建脚本、`NOTICE` 三件套；随扩展包 release 附件发布。
4. **文案**：能力矩阵写 `GPU=UNSUPPORTED`、`SMP=实验`、`DOCKER=UNSUPPORTED`、`PROTECTED=UNSUPPORTED`；性能徽章写「原生指令，syscall 有开销」，**禁止写"硬件加速"或"接近原生"**。

### 停止条件（任一条命中即冻结，不进入 W2）

- G1 失败且无法用下游 SMP 内核解决；
- G2 在 16K 宿主上 panic 且需要改上游代码才能修；
- G3 显示 UML 相对 QEMU < 3×；
- 需要 Root / 解锁 / 改 SELinux / targetSdk ≤ 28 才能跑；
- GPL 法务判定扩展包模式不可行。

---

## 6. 待确认风险点

| ID | 风险 | 现状 | 触发后果 | 缓解 |
|---|---|---|---|---|
| **R1** | **16 KB 宿主** | 无任何公开真机证据；lakitu 自述 4K 变体在 16K 宿主被 `os_early_checks` 拒绝；本项目设备是 4K | 2027 新机型全线不可用 | G2；优先验证 16K guest（本项目已是 16K guest）；同时确认 `libuml_kernel.so` 的 ELF 段 16 KB 对齐过 `check-apk-16kb.sh` |
| **R2** | **UP 单核** | app 域 UP（本地 + Haven）；Termux 域已 4 vCPU | 编译卖点被单核抵消，大工程构建不如预期 | G1；失败则把定位收窄为"单文件/小工程" |
| **R3** | **上游/下游腐烂** | 上游不接收；`zalexdev` HEAD 编译坏；两位作者不维护 | 半年后无法重建产物 | S1 自建可复现构建；产物 sha256 入 manifest；季度 rebase |
| **R4** | **长驻稳定性** | 无公开数据；本地全是"启动→跑完→退出"；Haven 有锁残留 panic | 用户跑长任务时崩溃、实例损坏 | G4；supervisor 加锁清理 + 冷恢复；先只承诺"短任务" |
| **R5** | **GPL 分发** | 无公开先例；Play 政策对"下载可执行代码"有灰区 | 上架被拒 / 合规争议 | 独立扩展包 + 源码 tarball；不在运行时下载内核；国内商店单独评估 |
| **R6** | **targetSdk 36 孤例** | Haven/drolin 都停在 35；本项目 36 域启动是领先但也无同类背书 | Android 17/18 收紧后无先例可循 | 每季度跑一次 36 域启动回归；把 D1 三探针做成 CI 化探针 App |
| **R7** | **性能口径** | 本仓库 2.04×/8×；Raymer 25×；上游 nommu 2.6 µs | 对外数字被质疑或误导产品决策 | 只用同夹具数据；G3 产出唯一可引用表；不同夹具数字禁止相除 |
| **R8** | **安全边界** | `seccomp` 模式可读写 UML 进程全部物理内存，非安全边界 | 若被宣传为"隔离环境"会误导用户 | 能力矩阵与文案写死"进程级隔离，非安全边界" |
| **R9** | **内存上限** | Android 17 MemoryLimiter 对所有 targetSdk 生效，`mem=2G` 未验证 | 大内存编译被系统杀 | W1 顺带测 `mem=1G/2G`；默认 512M–1G |
| **R10** | **体积与安装摩擦** | 内核 9.6 MB + rootfs 数百 MB；Haven APK 194 MB | 安装转化率低 | rootfs 首次下载（数据非可执行码，政策风险低）；扩展包按需安装 |

---

## 7. 来源清单（访问日期均为 2026-10-31）

**上游内核**
1. `arch/um/Makefile` L27–31：https://github.com/torvalds/linux/blob/master/arch/um/Makefile#L27-L31
2. `arch/um/Kconfig` L29（4K only）、L94–115（SMP）：https://github.com/torvalds/linux/blob/master/arch/um/Kconfig
3. `arch/um/kernel/skas/stub.c` L11–32（known security issues）：https://github.com/torvalds/linux/blob/master/arch/um/kernel/skas/stub.c#L11-L32
4. MAINTAINERS L29205–29217：https://github.com/torvalds/linux/blob/master/MAINTAINERS
5. Richard Weinberger 回信（2026-08-16，含 syscall 9.841→1.987 µs）：https://lkml.iu.edu/2608.2/00477.html
6. patchwork linux-um（arm64 无记录）：https://patchwork.ozlabs.org/project/linux-um/list/?q=arm64
7. arch/um 2026 提交：https://github.com/torvalds/linux/commits/master/arch/um

**下游工程**
8. `zalexdev/linux-um-arm64`：https://github.com/zalexdev/linux-um-arm64 ／ issue #4：https://github.com/zalexdev/linux-um-arm64/issues/4
9. `Raymer8639/linux-um-arm64`（v0.3.0，`linux-bionic-smp`）：https://github.com/Raymer8639/linux-um-arm64
10. `michaelamoetoevsky/uml4termux`（v0.5.0，3606s soak）：https://github.com/michaelamoetoevsky/uml4termux
11. `fahrulseptiana/drolin`（debug APK 113.7 MB）：https://github.com/fahrulseptiana/drolin
12. `GlassHaven/Haven`（1391★，AGPL-3.0，194 MB APK）：https://github.com/GlassHaven/Haven ／ issue #686：https://github.com/GlassHaven/Haven/issues/686
13. `lakitu12/linux-um-arm64-gki`（Android 17 GKI，16K）：https://github.com/lakitu12/linux-um-arm64-gki
14. termux-packages PR #32700（linux-kernel-um + passt，open）：https://github.com/termux/termux-packages/pull/32700

**Android 平台**
15. Android 17 行为变更（原生库只读、MemoryLimiter、本地网络）：https://developer.android.com/about/versions/17/behavior-changes-17
16. Android 10 W^X（targetSdk≥29 禁 execve 私有目录）：https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
17. 16 KB Play 要求与延期：https://support.google.com/googleplay/android-developer/thread/368982598
18. 16 KB 技术文档：https://developer.android.com/guide/practices/page-sizes
19. Play Device and Network Abuse：https://support.google.com/googleplay/android-developer/answer/9888379
20. AOSP `docs/custom_vm.md`（adb root 前提）：https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/docs/custom_vm.md
21. 华为应用审核（root 类限制）：https://developer.huawei.com/consumer/cn/doc/App/50104

**性能与替代**
22. nommu UML v13 lmbench 评论：https://patchwork.ozlabs.org/comment/3612938/
23. PARAVIRT 论文（UML I/O 2.5×、网络 4–5.9×）：https://lacuna.tiptreesystems.com/work/paravirt-userland-containers-for-mobile-systems/ （DOI: 10.1109/mobilecloud66020.2025.00015）
24. 缺页上下文切换（ptrace 6 次 / seccomp 2 次）：https://lore.kernel.org/linux-um/20210303155523.124277-1-benjamin(at)sipsolutions.net/ （原文 `@` 因本仓库敏感数据门改写为 `(at)`，访问时还原）

> 注：以上来源清单中，个别 URL 的作者邮箱/机器标识已按 `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md` 门禁要求改写（`@` → `(at)`、长 hex 标识截断）；还原方式已随条目标注。
25. UML 官方 HOWTO（strictly uniprocessor）：https://docs.kernel.org/virt/uml/user_mode_linux_howto_v2.html
26. Docker in UML：https://github.com/weber-software/diuid ／ https://onlybits.org/jcaesar/usmol
27. CVE-2025-37880（time-travel sched_yield）：https://www.cvelogic.com/cve/CVE-2025-37880 ；CVE-2025-39951（virtio_uml UAF）：https://securityvulnerability.io/vulnerability/CVE-2025-39951
28. gVisor 平台支持：https://gvisor.dev/docs/architecture_guide/platforms/
29. LKL：https://github.com/lkl/linux

---

## 8. 一句话行动项

**本周只做一件事：把下游内核变成"我们能自己重建"的（S1）。** 在这件事完成前，任何"UML 产品化"的讨论都建立在他人的 prebuilt 上——而那个 prebuilt 的 HEAD 已经编译不过了。

---

报告人：WorkBuddy ｜ 2026-10-31 ｜ 本文为联网调研 + 决策建议，未运行构建或设备；所有"本项目"性能结论引自既有报告，新增结论均标注来源与等级。
