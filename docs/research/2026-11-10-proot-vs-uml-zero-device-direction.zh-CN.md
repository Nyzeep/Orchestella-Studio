# 为什么 proot 能“秒开”，UML 能不能？—— 零设备 UML 快速启动与规模化方向调研

- 日期：2026-11-10
- 范围：Termux/proot-distro 的启动模型；Linux UML（`ARCH=um`）独立内核方案；本项目已有真机证据；下一步零设备 UML 开发方向
- 方法：一手来源优先（官方文档、源码仓库、AOSP 文档），项目内部证据单独标注；事实 / 推断 / 待实测三分
- 关联：`.scratch/uml-zero-device-20261110/spec.md`（工作包与验收）、`.scratch/orchestella-uml-fabric/map.md`（当前 effort）

## 0. 结论摘要

1. **proot 的“秒开”不是“更快的 Linux VM”，而是“没有 guest 内核”。** PRoot 是 `chroot`、`mount --bind`、`binfmt_misc` 的用户态实现，靠 `ptrace` 拦截并改写系统调用；guest kernel 从不参与，rootfs 只是一个目录/压缩包，`proot-distro login` 最终 `exec` 的是 `proot ... /bin/sh -l`。[S1][S2]
2. **proot-distro 明确不支持 systemd/OpenRC、cgroups/namespaces、真实 root、嵌套 proot**；它的定位是“无 root 的 rootfs 用户态”，不是完整 Linux VM。[S2]
3. **UML 是另一种东西：它有一个真正的 Linux 内核，只是这个内核作为普通用户态进程跑在宿主内核上。** 所以 UML 必须付内核 boot + init 的成本；但内部真机证据已经到 **220–400 ms（中位 ≈330 ms）** 到 guest marker，2026-11-07 的 ext4 + bootprobe 轮为 **0.22–0.40 s**。[S10][S11]
4. **本项目“等很久”的主因不是 UML 内核 boot，而是 QEMU 全 VM 流程 + rootfs 装配 + 首启 provisioning + systemd。** QEMU 启动是分钟级；T7 的 92 包 provisioning 事务本身可到 <1800 s。[S10][S12] 这是“推断 + 已有证据”，精确占比要 U0 的启动分解来量化。
5. 因此下一步不是追求 proot 式“无内核秒开”，而是把 UML 做成**两档承诺**：
   - **Fast shell**：预烘焙 rootfs + 最小 init + 预置工具链，到第一条命令；
   - **Full systemd**：同一内核到 `multi-user.target` / 网络 / SSH 就绪。
   两档分开测量、分开承诺；不要用一个“启动时间”混称。

## 1. 先纠正比较对象：三种不同的东西

| 维度 | proot / proot-distro | UML（`ARCH=um`） | QEMU（TCG） |
| --- | --- | --- | --- |
| **是否有 guest 内核** | ❌ 没有；guest kernel never involved | ✅ 有；真 Linux 内核作为用户态进程 | ✅ 有；完整 guest 内核 |
| **指令执行** | 宿主 CPU 原生执行；`ptrace` 改写 syscall | 宿主 CPU 原生执行；UML 内核拦截/处理 guest syscall | QEMU 软件翻译 guest 指令 |
| **rootfs 形态** | 目录 / rootfs tarball / OCI 层 | `ubd0` 块设备镜像或 hostfs 目录 | 磁盘镜像 / rootfs 镜像 |
| **启动路径** | `exec proot` → `exec /bin/sh` | 内核 boot → init → 挂载 rootfs → init 程序 | 内核 boot → 设备初始化 → init → systemd |
| **启动成本** | 进程创建 + ptrace 接管；无内核 boot | 内核 boot + init；本项目实测 0.22–0.40 s 到 marker | 内核 boot + 设备模拟；分钟级 |
| **systemd / Docker** | ❌ 官方明确 generally not possible | ⚠️ 有真内核，下游 ARM64 端口已跑 Docker/overlay2；本项目 T10 PASS | ✅ 完整内核，可跑 systemd；Docker 未取数 |
| **隔离** | 弱：路径翻译，不是内核隔离 | 中：真内核 + 宿主 syscall 边界；上游文档承认隔离较弱 | 较强：完整 VM 边界 |
| **targetSdk** | 需 legacy 28 域（36 域 app data 无 exec） | 36 域可从 `nativeLibraryDir` exec 内核/stub | 36 域可用（打包模式） |
| **主要代价** | 无内核、无服务、syscall/IO 慢 | syscall 拦截开销；内核 boot；内存/CPU | 翻译税；启动慢；资源重 |

> 这张表的核心：**proot 与 UML/QEMU 不是同一 Runtime 类别**。`CONTEXT.md` 把 proot 归为 Userspace Compatibility，把 UML/QEMU 归为 Full Linux 的执行策略；ACR §1.3 也明确“四车道 ≠ 四个 Runtime 类别”。[S13][S14]

## 2. proot 的“秒开”到底是怎么来的

### 2.1 机制：没有内核，只有路径翻译

PRoot 官方定义：

> "PRoot is a user-space implementation of chroot, mount --bind, and binfmt_misc."
>
> "Technically PRoot relies on ptrace..."
>
> "It is worth noting that the guest kernel is never involved, regardless of whether QEMU user-mode is used or not."
>
> "Technically, when guest programs perform access to system resources, PRoot translates their requests before sending them to the host kernel."[S1]

所以 proot 的“Linux”不是一个 VM：它是宿主内核上的一个被 `ptrace` 接管的进程树。rootfs 只是新根目录；`/proc`、`/sys`、`/dev` 等通过 bind 模拟，权限通过 UID/GID 映射“假装”成 root。[S1]

### 2.2 proot-distro 的启动路径

proot-distro 的 `login` 最终执行的是：

```
proot --kill-on-exit --link2symlink --sysvipc \
      --kernel-release=... -L --change-id=0:0 \
      --rootfs=/.../containers/ubuntu/rootfs --cwd=/root \
      --bind=/dev --bind=/proc --bind=/sys ... \
      /bin/sh -l
```

也就是说：**下载/解包完成后，启动就是一次 `exec`**；没有内核 decompress、没有设备初始化、没有 systemd。[S2]

### 2.3 proot 的边界（官方 README 原文）

- **No background services**："starting service supervisors (`systemd`, `OpenRC`, socket-activated daemons) is generally not possible."
- **No cgroups / namespaces**："features that need real Linux kernel namespaces (`unshare`, container-in-container, network namespaces) do not work — proot is path translation, not kernel isolation."
- **No real root**："proot uses UID/GID remapping to fake root. Programs that genuinely need kernel-level root (`sudo`, `mount` of real filesystems, `iptables`, etc.) will fail."
- **No nesting**：proot-distro 拒绝在另一个 proot 内运行。
- **No live state migration**：`backup`/`restore` 只归档 rootfs，不保存运行中进程的内存状态。
- **Performance**："`proot` intercepts every system call via `ptrace`. Filesystem-heavy workloads (compilation, package managers) are noticeably slower than native execution." [S2]

这解释了为什么 proot 能“秒开”，但也解释了它为什么不能替代完整 Linux：**它把内核、服务、namespace、真实 root 都省掉了**。

### 2.4 为什么它看起来“下载完就秒开”

`proot-distro install` 的主要成本是下载 OCI 层/rootfs 并落盘；`login` 只是 `exec`。所以用户感知是“下载完就进去了”。这不是启动优化，而是**启动根本没有内核阶段**。同一份 rootfs 在 proot 里跑 `apt`/编译时，仍然会因 `ptrace` 路径翻译明显变慢。[S2]

## 3. 本项目为什么“要等很多流程”

### 3.1 QEMU 的固有成本

QEMU 要 boot 一个完整 guest 内核、初始化虚拟设备、挂载 rootfs、启动 init/systemd；这是分钟级。内部报告明确写“QEMU 车道的分钟级启动”。[S10]

### 3.2 首启 provisioning 是另一个大头

当前产品首启还包括 rootfs 装配、包安装、locale、证书、服务 enablement 等。T7 的成功样本里，92 包事务本身可到 <1800 s。[S12] 这不是 UML 内核 boot 能解决的；它属于“首启流水线”。

### 3.3 UML 内核 boot 其实已经很快

内部证据：

- 2026-10-29：UML 在 Android 17 App 域启动，220–400 ms，中位 ≈330 ms；fork+exec 1.49×、纯 CPU 1.08×；syscall 约 8× 是结构性开销。[S10]
- 2026-11-07：ext4 + `work=bootprobe` 20 循环，19/20 干净，boot 0.22–0.40 s；唯一失败是 stop 文件残留（基础设施，不是 boot 不稳定）。[S11]
- 2026-11-07：SMP `ncpus=4` 启动 0.23–0.43 s，和 UP 0.21–0.43 s 同量级；4×sha256 有效加速 3.92×。[S11]

**结论（推断，需 U0 量化）：** 用户感知的“等很久”主要来自 QEMU 全 VM 与首启 provisioning；UML 的 fast path 已经有亚秒级真机样本。下一步是把这条 fast path 产品化，并把首启 provisioning 前移到构建期。

## 4. UML 的独立内核方案：它到底是什么

### 4.1 内核作为用户态进程

Linux 内核文档：

> "The UML kernel is just a process running on Linux - same as any other program. It can be run by an unprivileged user and it does not require anything in terms of special CPU features."
>
> "It is not accelerated in any way (vhost, kvm, etc) and it is not trying to access any devices directly. It is, in fact, a process like any other."[S3]

UML 的设备是“100% paravirtual”：全部映射到宿主提供的 files/sockets/pipes；没有真实硬件模拟。[S3]

### 4.2 两套 userspace 模式：ptrace 与 seccomp

UML 需要拦截 guest userspace 的 syscall。上游文档描述了 `PTRACE_SYSEMU` 路径：宿主内核协助 UML 拦截 guest 程序，UML 内核处理请求。[S3] 下游 ARM64 端口（本项目使用的 `Raymer8639/linux-um-arm64`）明确写：

> "Syscall interception PTRACE_SYSEMU; syscall cancellation where the host has none; syscall substitution where a seccomp filter rejects a cancelled call"
>
> "Userspace mode SECCOMP (default fast path) and ptrace"[S4]

这意味着 UML 有两种模式：**SECCOMP 快路径**（只 trap 需要模拟的 syscall）与 **ptrace 模式**（更慢）。本项目的 runner 用 `seccomp=on`，与这个快路径对齐。[S15] 但内部测量仍显示 syscall 约 8× 开销（相对 native），说明“快路径”不等于“零开销”。[S10]

### 4.3 上游 UML 是 uniprocessor；本项目用的是下游 ARM64 SMP 补丁

上游 UML HowTo 写：

> "UML is strictly uniprocessor at present. If you want to run an application which needs many CPUs to function, it is clearly the wrong choice."[S3]

但本项目使用的是下游 ARM64 端口 + SMP 补丁（issue 01 记录 316 项 config diff；G1 真机 4 vCPU PASS）。[S14][S11] 所以：

- **不要**引用上游“UML 单核”来否定本项目 SMP；本项目已经有真机 4 vCPU 证据。
- **也不要**把下游 SMP 当作上游保证；它是 fork 的能力，需要持续维护/构建门禁。

### 4.4 16KB 页

下游端口的 README 写 "Boot to userspace Alpine and Debian 12, 4 KB and 16 KB guest pages"。[S4] 但**宿主** 16KB 仍是本项目的 ACR 缺项：现有三台设备都是 4K 页；公开通道只有 Pixel 系开发者选项。[S14] 因此“16KB guest 页”与“16KB 宿主”必须分开说。

### 4.5 启动方式：ubd / hostfs

UML 没有安装器；rootfs 是宿主上构建好的块设备镜像（`ubd0=... root=/dev/ubd0`）或 hostfs 目录。[S3] 这正是 U2“预烘焙 rootfs”能成立的前提：**rootfs 可以在构建期准备好，启动时只挂载 + init**。

## 5. 公平对比表（同一就绪点才有意义）

| 指标 | proot | UML | QEMU |
| --- | --- | --- | --- |
| 到第一条 shell 命令 | 进程 exec + ptrace 接管；无内核 boot（具体毫秒未在本项目实测） | 内核 boot + init + exec；本项目真机 0.22–0.40 s 到 marker | 内核 boot + 设备初始化 + init；分钟级 |
| 到 systemd/服务就绪 | ❌ 官方不支持 systemd/OpenRC | ⚠️ 有真内核；fast profile 不承诺 systemd，full profile 另测 | ✅ 可到 systemd；启动更慢 |
| Docker/容器 | ❌ 无 cgroups/namespaces | ⚠️ 下游端口 README 称 dockerd/overlay2 可用；本项目 T10 PASS | ⚠️ 完整内核可跑；本项目未取数 |
| 文件/编译负载 | 明显慢（ptrace 路径翻译）[S2] | syscall 约 8×；纯 CPU 1.08×、fork+exec 1.49×[S10] | 翻译税约 88–93%（QEMU TCG）[S11] |
| 隔离 | 弱（路径翻译） | 中（真内核 + 宿主 syscall 边界；上游承认较弱）[S3] | 较强（VM 边界） |
| targetSdk | 28 域才能 exec 发行版二进制（内部证据 S13/S14） | 36 域可从 nativeLibraryDir exec | 36 域可用（打包模式） |

> 注意：proot 的“启动快”与 UML/QEMU 的“启动慢”不是同一指标；proot 到“shell”没有内核阶段，UML/QEMU 到“shell”包含内核 boot。把三者放进同一个“启动时间”柱状图是错误口径。

## 6. 启动时间分解：事实、推断、待实测

| 阶段 | proot | UML | QEMU | 证据等级 |
| --- | --- | --- | --- | --- |
| 下载/解包 rootfs | 有（OCI 层/rootfs） | 有（预烘焙镜像可离线） | 有（rootfs 镜像/装配） | 事实（机制） |
| 内核 boot | 无 | 有；0.22–0.40 s 到 marker | 有；分钟级 | UML/QEMU 本项目实测 |
| init / 挂载 | 无（直接 exec shell） | 有；`um-init` 挂 proc/sys/dev/pts/cgroup | 有；initramfs + systemd | 事实（机制） |
| systemd/服务 | 不支持 | full profile 才有；fast profile 不做 | 有；首启 provisioning 另计 | 事实/待实测 |
| 首启 provisioning | 无（包管理器手动跑） | 可前移到构建期；首启零安装是目标 | 当前产品首启有 92 包事务 <1800 s 样本 | 推断 + 内部证据 |

**待实测（U0/U1/U2）：** 在 ARM64 设备上，把“下载/解包”“内核 boot”“init”“首启 provisioning”分别计时；目前只有 UML 到 marker 的 0.22–0.40 s 与 T7 的 provisioning 总量，缺同一轮的完整分解。

## 7. 已有先例

### 7.1 Haven：同一个 App 同时提供 proot 与 UML

GlassHaven/Haven 的 README 写：

> "Local Linux — a Linux userland via PRoot (no root, any Android 8+ device): Alpine, Debian, Ubuntu, Arch, or Void, side-by-side."
>
> "Linux Guest (UML) — a whole Linux kernel (user-mode Linux) booted as an app process on any arm64 device, no root: Alpine with apk, its own ext4 disk and network stack, for when PRoot's syscall emulation isn't enough."[S5]

这是一个非常直接的产品先例：**proot 负责轻量用户态，UML 负责需要真内核的场景**；两者不是替代关系，而是分层。它同时证明“arm64 App 进程内跑 UML、无需 root”在真实产品中成立。

### 7.2 Raymer8639/linux-um-arm64：本项目的下游内核来源

该仓库 README 明确：

- 不是上游 UML，是 Android/Termux ARM64 下游端口；
- 已测试 Alpine/Debian 12、4KB/16KB guest pages；
- `PTRACE_SYSEMU`、SECCOMP 快路径、ptrace 模式；
- Docker/overlay2（cgroup v2）可跑；
- 无 32 位 compat；`wlan0` 关联未测；cpuset 控制器缺失。[S4]

这解释了为什么本项目的 UML 不是“理论可行”，而是有下游工程基础；也解释了为什么 16KB **guest** 与 16KB **宿主** 要分开。

### 7.3 Termux 社区的 UML 讨论

Termux 仓库 2022 年的讨论提出“Termux-on-Termux / UML”方向，并列出 UML 的三个代价：单处理器、syscall 慢、I/O 可能慢；当时 ARM64 UML 没有官方端口。[S6] 这个讨论是社区提案，不是上游承诺；它被后来的下游端口部分推进，但仍不能当作“Termux 官方支持 UML”。

## 8. 对“下一步零设备大规模 UML 开发方向”的回答

### 8.1 产品承诺先分两档

- **Fast shell**：`T3 first command`，目标是在 rootfs 已就绪的前提下尽可能接近“下载完就能用”；不承诺 systemd/Docker。
- **Full systemd**：`T4 service ready`，用于服务、Docker、完整发行版体验；启动更慢是合理的。

不要把两档合成一个“启动时间”，也不要用 proot 的“无内核秒开”作为 UML 的验收线。

### 8.2 零设备工作包（详见 spec）

| 包 | 目标 | 零设备交付 | 关键验收 |
| --- | --- | --- | --- |
| **U0 Boot timeline harness** | 统一 T0–T5 口径 | 协议 + 日志解析器 + self-test | 解析 UML/QEMU/proot 日志；已有 UML 日志回归 0.22–0.40 s 量级 |
| **U1 Fast-boot UML profile** | 最小内核 + initramfs + 预烘焙 rootfs | `defconfig-fast`、init、构建脚本、manifest、x86_64 参考 | ARM64 构建成功；参考 boot 到 T3；fast vs systemd 取舍 |
| **U2 Rootfs 预烘焙** | 首启零安装 | Alpine + 一个 glibc 发行版构建脚本、manifest、离线 init | 断网首启到 READY；两次构建 sha 一致或记录非确定项 |
| **U3 Seccomp/host compat** | 找出 Android App 域的结构性阻断 | AOSP-like seccomp profile + 矩阵 + self-test | `ubd`/`hostfs`/`vector`/`ncpus`/`mconsole` 矩阵，unknown 有设备入口 |
| **U4 Warm-start 可行性** | 判断 checkpoint/restore/预热点池 | 一手来源调研 + host 原型（可行时） | go/no-go；内存成本 |
| **U5 打包/CI/许可证** | 可复现 + GPL 对应源码 | CI job + `check-uml-artifacts.py` + source bundle | 干净检出构建；manifest 一致 |
| **U6 多实例规模化** | N 实例的 RSS/CPU/启动/端口 | host benchmark N=1/2/4/8 | 数据表 + 保守上限；设备项 not-tested |

### 8.3 优先级

- **P0（先做）**：U0 + U1 + U2 —— 直接回答“为什么慢、能不能快”，并给出 fast path 证据。
- **P1**：U3 + U5 —— Android 可行性与可分发性。
- **P2**：U4 + U6 —— warm start 与规模化。
- **持续**：U7 ACR 证据包；ACR 通过前不写产品集成代码。

### 8.4 里程碑

| 里程碑 | 出口 |
| --- | --- |
| M1 Fast path 基线 | T0–T3 表、fast vs systemd、预烘焙 rootfs 首启零安装 |
| M2 Android 可行性 | seccomp 矩阵、GPL/CI 打包门 |
| M3 规模化 | warm-start go/no-go、N 实例数据 |
| M4 ACR | ACR §8 证据包、缺项清单 |

## 9. 不建议做 / 容易误判

1. **不要把 proot 叫“更快的 Linux VM”。** 它没有 guest 内核，不能 systemd/namespaces/真实 root；把它当 VM 会误导产品承诺。[S1][S2]
2. **不要追求“UML 达到 proot 的秒开”。** proot 的秒开来自省略内核；UML 有内核，不可能零成本。目标是亚秒级 T3，并且 full systemd 另算。
3. **不要把 UML 和 QEMU 的启动数字直接相除。** 两者都 boot 内核，但 QEMU 有设备模拟、UML 没有；同轮同夹具才可以比较。[S14]
4. **不要用上游 UML 的“uniprocessor”否定本项目 SMP。** 本项目用下游 ARM64 SMP 补丁，G1 已实测 4 vCPU；但这是 fork 能力，不是上游保证。[S3][S11]
5. **不要把 16KB guest 页当成 16KB 宿主。** 下游端口支持 16KB guest；本项目缺的是 16KB 宿主设备证据。[S4][S14]
6. **不要在 ACR 前写产品集成代码。** 当前 ACR 技术缺项只剩 16K 宿主与 distcc@36；产品形态未投票。[S14]

## 10. 待实测 / 开放问题

1. ARM64 设备上 T0–T5 的完整分解（目前只有 UML 到 marker 的 0.22–0.40 s 与 T7 provisioning 总量）。
2. 首启 provisioning 在用户总等待中的精确占比（U0/U2）。
3. SECCOMP 快路径 vs ptrace 模式的启动/吞吐差异（U1/U3）。
4. 预烘焙 rootfs 的可复现性与体积/许可证边界（U2/U5）。
5. UML warm start / checkpoint-restore 是否可行（U4）。
6. 多实例 N 的 RSS/CPU/端口上限（U6）。
7. 16KB 宿主、distcc@36 仍是设备/外部门（ACR §8.2）。

## 11. 来源

访问日期均为 2026-11-10。外部来源优先；内部来源用仓库路径标注。

- [S1] PRoot 官方站：https://proot-me.github.io/（"user-space implementation of chroot, mount --bind, and binfmt_misc"；"relies on ptrace"；"guest kernel is never involved"）
- [S2] Termux proot-distro README：https://github.com/termux/proot-distro（OCI/rootfs 安装；`login` exec proot；no systemd/OpenRC、no cgroups/namespaces、no real root、no nesting、no live state migration、performance）
- [S3] Linux kernel User Mode Linux HowTo：https://docs.kernel.org/virt/uml/user_mode_linux_howto_v2.html（UML kernel is just a process；100% paravirtual；PTRACE_SYSEMU；strictly uniprocessor；weak isolation）
- [S4] Raymer8639/linux-um-arm64：https://github.com/Raymer8639/linux-um-arm64（Android/Termux ARM64 下游端口；4KB/16KB guest pages；PTRACE_SYSEMU；SECCOMP/ptrace；Docker/overlay2；无 32-bit compat；cpuset 缺失）
- [S5] GlassHaven/Haven：https://github.com/GlassHaven/Haven（同一 App 提供 PRoot 与 UML Guest；"for when PRoot's syscall emulation isn't enough"）
- [S6] Termux UML 讨论（社区）：https://github.com/termux/termux-packages/discussions/8555（2022 提案；单处理器/syscall/IO 代价；当时无 ARM64 官方端口）
- [S7] Android 16 KB 页面（AOSP）：https://source.android.com/docs/core/architecture/16kb-page-size/16kb
- [S8] Android Developers 16 KB 页面：https://developer.android.com/guide/practices/page-sizes（Play 2027-02-01 要求；启动时间收益约 8% / 950 ms）
- [S9] Android Enterprise Security White Paper（seccomp-bpf 自 Android 8.0 应用于所有 App；SELinux）：https://www.android.com/static/2016/pdfs/enterprise/Android_Enterprise_Security_White_Paper_2019.pdf
- [S10] 本项目 `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md`（UML 220–400 ms；fork+exec 1.49×；CPU 1.08×；syscall 8×；QEMU 分钟级）
- [S11] 本项目 `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`（T5 0.22–0.40 s；SMP 0.23–0.43 s；G1 3.92×；QEMU TCG 翻译税）
- [S12] 本项目 `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md`（T7 92 包事务 <1800 s；T8② FGS 驻留；T10 Docker；T11 链接）
- [S13] 本项目 `docs/research/2026-10-21-host-native-lane-evaluation.md`（proot 机制、systemd/namespaces 限制、openat 28.005 µs vs native 2.503 µs）
- [S14] 本项目 `docs/research/ACR-draft-four-lanes-20261029.md`、`.scratch/orchestella-uml-fabric/map.md`（四车道定位、ACR §8.2 缺项、13/13 resolved）
- [S15] 本项目 `research/uml-runner/README.md`、`research/uml-kernel-build/README.md`（`seccomp=on`、`ncpus=`、内核可复现构建、16KB stub）

> 说明：Google/Android 官方文档在本次访问中出现登录重定向，[S7]–[S9] 的链接按官方 URL 记录；具体引文以仓库内已审查的研究文档和公开搜索摘要为准，未把不可复核的页面内容当作新事实。
