# UML 内核可复现构建：Raymer8639 v0.3.0 复现流水线与资产比对（工单 01）

日期：2026-10-31 · 执行：fabric-batch1-A-build · 状态：S1/S2/S3 完成 · 本批次无设备，真机项一律 `not-tested`

交付物：`research/uml-kernel-build/`（脚本 / pinned defconfig / manifest / checklist / 16KB 检查脚本）；本文 = 方法 + diff 结论 + 资产比对表。

## 0. 摘要（5 条）

1. **构建打通**：x86_64 容器 + stock Android NDK r29 交叉编译，UP 与 SMP 两个 profile 均一次构建成功（UP ≈10 分钟/8 核，SMP ≈5 分钟）。arm64 binfmt 容器路线可用（已验证 `aarch64`）但被放弃为主路径：NDK 官方无 linux-arm64 host 工具链，且 release 本身就是 linux-x86_64 工具链产物（§2）。
2. **stub 逐字节复现**：`stub_exe` 两个 profile 均与 release 资产 sha256 一致（`731c48d5…`，2,000 B）。内核本体非逐字节一致：两个 profile 都恰好差 1,040 B（0.011% / 0.0078%），主因是 release 用了非 stock 的 clang flavor（`dev, +pgo, -bolt, -lto, -mlgo` vs NDK 的 `13989888, +pgo, +bolt, +lto, +mlgo`，同基线 r563880c / clang 21.0.0）——MANIFEST 只记 `ndk=r29` 未记 flavor，属 release 溯源信息缺口（§3）。
3. **config 可 pin**：release 二进制自带 IKCONFIG，已提取为 pinned defconfig（UP 1,734 行 / SMP 2,021 行）；与重建产物 IKCONFIG 往返 diff 仅剩编译器探针行（UP 8 行、SMP 1 行，无功能差异；两侧 `CONFIG_RUST` 均未启用）（§3.3）。
4. **SMP 不只是 `CONFIG_SMP`/`NR_CPUS`**：UP vs SMP 全量 diff 316 项（18 删 / 23 改 / 275 增），SMP profile 是「SMP + Docker」组合（netfilter/nftables 全家桶、bridge/veth、overlayfs、PID/USER ns、MEMCG、cgroup pids、BPF_SYSCALL、TREE_RCU 等），与 release 说明一致；mainline 的 x86-only 限制是 `arch/x86/um/Kconfig` 的 `select UML_SUBARCH_SUPPORTS_SMP if X86_CX8`，下游补丁 = 新增 `arch/arm64/um/`（62 文件）+ generic um SMP 件，app 域配置面就绪（内核可换参数通道已在 uml-runner 预留），性能/稳定性 `not-tested`（§4）。
5. **Haven 三坑全部落为可执行检查项**：① rseq/set_robust_list = 内核侧 neuter + 扫描门成对（Haven 教训：步骤不在构建脚本里会在 relink 时丢失）；② ubda F_SETLK panic 机制在内核侧、修复在启动器侧 teardown（Haven v5.89.24），内核不应改；③ 16KB 对齐内核过、**stub release 原生不过**（exec 装载能跑但会被本仓库 `check-apk-16kb.sh` 拦），修（`-z max-page-size=16384`）或豁免需二选一（§5 + checklist 文件）。

## 1. 对象与 pin

| 项 | 值 | 证据 |
|---|---|---|
| tag | `um-arm64-v0.3.0` 注解 tag（对象 `48af0dbc…`）→ **commit `169b17ff3f66d507cb137b770ceb1fd9f4547a32`**（branch `um-arm64`） | `git ls-remote --tags`（2026-10-31）+ release MANIFEST |
| 基线 | Linux 7.2.0-rc4，uml 树 next `2f88f568…`，38 commits（README.md:Base 自述） | 源码 Makefile + repo README + MANIFEST |
| 工具链 | NDK r29 / API 30 / 16KB（`CONFIG_PAGE_SHIFT=14` IKCONFIG 实测） | MANIFEST + IKCONFIG |
| 源码获取 | codeload tarball `um-arm64-v0.3.0`（sha256 `08578a4944…`，266,259,756 B）；git 全量克隆两次在网络传输中失败，改 tarball | 本地实测 |

release 资产（GitHub Release `um-arm64-v0.3.0`，2026-10-24 发布，`sha256sum -c SHA256SUMS` 全过）：

| 资产 | 大小 (B) | sha256 |
|---|---|---|
| linux-bionic | 9,648,496 | `5c205533a5b02d5b22683475807aea7d6c2f4df8aec6be4d6034f8185ec59c18` |
| linux-bionic-smp | 13,272,376 | `b8ccc5f0d6327e2d3684ffe3d77c95835a66d277c5b6466c9452113272bdb360` |
| stub_exe | 2,000 | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e` |

与仓库记录比对：`libuml_kernel.so` = 9,648,496 B（前缀 5c205533…）、`libuml_stub.so` = 2,000 B（前缀 731c48d5…）——与 research/uml-runner/README.md:35-36 的映射（libuml_kernel.so=linux-bionic、libuml_stub.so=stub_exe）及会话 21 §5.3 记录完全吻合。

## 2. 方法（含构建路径决策）

- **路径 1（arm64 binfmt 容器，按工单序位先行探测）**：`docker run --rm --platform linux/arm64 alpine uname -m` → `aarch64`，binfmt 可用。但 NDK 官方仓库只有 `android-ndk-r29-{darwin,linux,windows}.zip`，linux 包 = x86_64 host 工具链（https://dl.google.com/android/repository/repository2-3.xml ，访问 2026-10-31，无 linux-arm64 变体）；arm64 容器内要么换发行版 clang（来源进一步偏离 release），要么对 x86_64 clang 双层模拟。**决策：放弃为主路径**，理由是与 release 的构建形态保真度更低且慢 1–2 个量级。
- **采用路径（x86_64 容器交叉编译）**：release 自身即 NDK r29 linux-x86_64 工具链产物（harness/build-bionic-termux.sh 默认 `toolchains/llvm/prebuilt/linux-x86_64`），README 明说交叉编译是支持路径。容器 = `ubuntu:24.04` + `build-essential bison flex bc python3 file`，命令与上游 `harness/build-bionic-termux.sh` 完全同构（ARCH=um SUBARCH=arm64、`--target=aarch64-linux-android30`、LLVM_IAS=1、产物 llvm-strip --strip-debug）。
- **config 来源（关键决策）**：不从树内 `arm64_defconfig` 重推，而是 **`scripts/extract-ikconfig` 从 release 二进制提取内嵌 .config 作为 pinned defconfig**（release 开了 `CONFIG_IKCONFIG=y`）。这比「defconfig+片段重推」多一层保证：pin 的就是发布字节里携带的 config。树内 defconfig 路线的对照实验（defconfig+4 flag 生成后 diff）受宿主工具链探针污染，价值有限，未作为 pin 依据。
- 构建脚本：`research/uml-kernel-build/build-bionic.sh`（UP）/ `build-bionic-smp.sh`（SMP，从 pinned smp config 出发）。两次构建均在容器内完成，未触碰源码树工作区以外的本仓库文件。

## 3. S1 结果：产物比对与字节差异归因

### 3.1 比对表（核心交付）

| 产物 | release（v0.3.0 资产） | 本次重建 | 大小差 | sha256 | 一致性 |
|---|---|---|---|---|---|
| linux-bionic | 9,648,496 B / `5c205533…` | 9,647,456 B / `8ce2a3bc…` | −1,040 B（0.011%） | 不一致 | config 等价（见 3.3） |
| linux-bionic-smp | 13,272,376 B / `b8ccc5f0…` | 13,271,336 B / `62342700…`（协调复核更正，最终产物实测） | −1,040 B（0.0078%） | 不一致 | config 仅 1 行探针差 |
| stub_exe | 2,000 B / `731c48d5…` | 2,000 B / `731c48d5…` | 0 | **逐字节一致** | 两个 profile 的 stub 均一致 |

### 3.2 字节差异归因

- **主因（已实证）：clang flavor 不同**。release 内嵌 `CONFIG_CC_VERSION_TEXT="Android (dev, +pgo, -bolt, -lto, -mlgo, based on r563880c) clang version 21.0.0"`（IKCONFIG 提取）；stock NDK r29 clang 实测为 `Android (13989888, +pgo, +bolt, +lto, +mlgo, based on r563880c)`。同一 llvm-project 基线（r563880c）、同 clang 21.0.0，但 PGO/BOLT/LTO/MLGO 训练布局不同 → 代码生成不同。MANIFEST 只记 `ndk=r29`，未记 flavor——**复现 v0.3.0 内核字节需要该 dev flavor 工具链，公开渠道未见分发**（未找到证据）。
- 两个 profile 差值同为 1,040 B，指向固定字符串/表差（版本串长度、构建路径等），未逐字节归因，不猜。
- 重建含义：**功能等价的近复现**（config 等价 + 尺寸万分之一级 + stub 完全一致），不是逐字节复现。下游引用时用「重建链 + pinned config」，比对链以 release 资产 sha256 为准。

### 3.3 config 往返（release ↔ 重建）

- UP：`diffconfig` 8 行，全部 toolchain 探针（CC_VERSION_TEXT + RUSTC_*：release 构建机带 rustc 1.99.0、NDK 无 rustc；`CONFIG_RUST` 两侧均未启用，无功能差异）。
- SMP：仅 1 行（CC_VERSION_TEXT）。
- 结论：pinned config + olddefconfig 在 NDK r29 下无损往返；提取件即发布 config 的忠实快照。

## 4. S2 结果：`linux-bionic` vs `linux-bionic-smp`

### 4.1 diff 全量数据（`s2-config-diff.txt`，316 项）

- **不是**只差 `CONFIG_SMP`/`NR_CPUS`：18 删 / 23 改 / 275 增。
- 核心：`SMP n→y`，`NR_CPUS 1→16`（DEFAULT 1→2、RANGE_END 1→64）。
- 增 = 「Docker profile」（release 说明自述 "SMP + Docker kernel profile"）：NETFILTER/NFTABLES 全套（xtables/nft/conntrack/NAT/masquerade…）、BRIDGE、VETH、OVERLAY_FS、PID_NS、USER_NS、MEMCG、CGROUP_PIDS、CFS_BANDWIDTH、BLK_DEV_THROTTLING、KEYS、BPF_SYSCALL、EXT4_FS_POSIX_ACL/SECURITY、TREE_RCU/TREE_SRCU、QUEUED_SPINLOCKS/RWLOCKS、RPS/RFS/XPS、SKB_EXTENSIONS 等；`CPUSETS` 保持 n（与 release 说明的 cgroup 控制器清单 cpu/io/memory/pids 吻合）。
- 删/变：`SUSPEND`/`PM_SLEEP` 退出、`TINY_RCU→TREE_RCU`、`NEED_PER_CPU_KM` 退出、`FW_CACHE` 退出、`BROKEN_ON_SMP`/`FORCE_NR_CPUS` 退出、`UML_TIME_TRAVEL_SUPPORT` 随依赖消失。
- 与 release notes 逐项吻合（"includes PID/USER namespaces, cgroup2, overlayfs, veth/bridge and netfilter config"）。

### 4.2 mainline x86-only 限制与下游补丁范围

- mainline（torvalds master，访问 2026-10-31）：`arch/um/Kconfig:94` `config UML_SUBARCH_SUPPORTS_SMP bool`（无 default）+ `config SMP depends on UML_SUBARCH_SUPPORTS_SMP`；**x86-only 的实现点是 `arch/x86/um/Kconfig:16` 的 `select UML_SUBARCH_SUPPORTS_SMP if X86_CX8`** ——arm64 SUBARCH 无 select，SMP 在 mainline arm64 UML 下不可选。
- 下游（v0.3.0 树）：新增 `arch/arm64/um/`（62 文件：asm/ os-Linux/ shared/ vdso/），`arch/arm64/um/Kconfig:20` `select UML_SUBARCH_SUPPORTS_SMP` + `ARCH_USE_QUEUED_SPINLOCKS/RWLOCKS`；generic um 侧 SMP 件（arch/um/kernel/irq.c、um_arch.c、include/asm/smp.h 等）。Kconfig 注释自述其安全论证：setjmp_aarch64.S 只存 AAPCS64 callee-saved 集（跨 CPU longjmp 安全）、barrier.h 真 `dmb ish`、TPIDR_EL0 与 NT_ARM_SYSTEM_CALL 随线程携带；**实际 CPU 数由 `ncpus=` 启动参数给出、以 NR_CPUS 为上限**（一个二进制服务任意宿主）。

### 4.3 app 域就绪度评估

- 配置面：就绪。产物（release `linux-bionic-smp` 13,272,376 B 已随 release 提供）+ 运行参数通道已在 uml-runner 预留（`kernel` 参数可换 smp 版，research/uml-runner/README.md:133），app 无需代码改动。
- 运行面：release 自述 device smoke 2/4 vCPU + cgroup 控制器 + docker 控制器过 [官方文档(项目自述)]；**本项目域（Orchestella app + uml-runner）未测** —— SMP 内核换取多核的代价是 TREE_RCU/SMP locking 使单 CPU syscall 密集负载变慢（release 原文），app 域应默认 UP、SMP 作为显式 opt-in。`not-tested`。
- 注意：SMP/UP 是**两个不同二进制**，启动器按 `--cpus` 选内核时要把坑①的 neuter 步骤对两个 profile 都做（若采纳）。

## 5. S3：Haven 三坑 checklist

全文见 **`research/uml-kernel-build/checklist-haven-three-pits.md`**（一页，含逐条机制假设与构建期检查项）。要点：

1. **rseq/set_robust_list → signal 31**（Haven v5.89.8 notes，2026-09-19）：阉割在内核侧（post-link neuter 产物 ELF），触发在宿主沙箱 seccomp；归因缺口转工单 03。本仓库 v0.3.0 链无 neuter 无扫描门 → 若采纳 Haven 路线，neuter 与扫描门必须进构建脚本（成对）。
2. **ubda F_SETLK 残留 → 重启 panic**（Haven#686，closed）：panic 机制在内核侧（`os_lock_file` → ubd 打不开 → VFS panic，本仓库 `arch/um/os-Linux/file.c:583` 逐字一致），修复在启动器侧（失败必 teardown + pre-start F_GETLK 试锁）；内核不应降级锁语义（共写损坏风险）。
3. **16KB ELF 对齐**：内核本体（release 与重建、UP 与 SMP）全过 `check-elf-16kb.sh` 同口径检查；**stub release 原生不过**——app 域 exec 装载（非 dlopen）能跑，但以 `libuml_stub.so` 进 APK 就会被 `check-apk-16kb.sh` 拦。修法 = stub 链接 `-z max-page-size=16384`（重链代价约为零，破坏与 release stub 的 sha256 恒等、行为等价 `not-tested`）或 gate 加 exec-only 豁免；另注意 `keepDebugSymbols += libuml_kernel.so` 防 AGP strip 破坏比对链。

## 6. 设备窗口清单（本批次无设备，全部 not-tested）

1. 未 neuter 内核（UP/SMP 两个 profile）在目标设备沙箱首启存活（坑①复验，含 OEM/OS 版本差异样本）。
2. `-z max-page-size=16384` 重链 stub 的启动功能等价（坑③修法）。
3. SMP 内核在 app 域（uml-runner）2/4 vCPU 启动 + 单核 syscall 性能对照（4.3）。
4. 启动失败注入 → teardown → 立即重启回归（坑②启动器验收）。

## 7. 证据等级与来源

| 结论 | 等级 | 来源（访问日期均 2026-10-31） |
|---|---|---|
| tag/commit/MANIFEST、release 资产 sha256 | [官方源码] | https://github.com/Raymer8639/linux-um-arm64 （ls-remote / codeload tarball / Releases API） |
| release profile 语义（UP vs SMP+Docker）、device smoke 自述 | [官方文档(项目自述)] | Release body https://github.com/Raymer8639/linux-um-arm64/releases/tag/um-arm64-v0.3.0 |
| 坑① 现象与修法 | [社区(项目官方自述)] | https://github.com/GlassHaven/Haven/releases/tag/v5.89.8 （2026-09-19） |
| 坑② 现象/panic 串/修复 | [社区(项目官方自述)] | https://github.com/GlassHaven/Haven/issues/686 + v5.89.24 评论 |
| 内核锁行为、SMP select、Kconfig 注释 | [官方源码]（本仓库 v0.3.0 树实读） | arch/um/os-Linux/file.c:583、arch/arm64/um/Kconfig:20 等 |
| mainline x86-only 限制 | [官方源码] | https://github.com/torvalds/linux arch/um/Kconfig:94、arch/x86/um/Kconfig:16 |
| NDK host 变体清单 | [官方文档] | https://dl.google.com/android/repository/repository2-3.xml |
| clang flavor 差异、1,040 B 常量差 | [基准]（本次实测：llvm-readelf/IKCONFIG/sha256） | 本构建记录 |
| dev flavor 工具链公开分发渠道 | [未找到证据] | repository2-3.xml 与 Release 资产均无 |

## 8. 未验证项 / 限制

- 全部真机行为 `not-tested`（§6）；本批次性能数字为零，未做任何夹具相除。
- 1,040 B 常量差未逐字节归因（3.2）；dev flavor 工具链不可得 → 内核本体逐字节复现不可达，属上游溯源缺口而非本流水线缺陷。
- 树内 `arm64_defconfig`+片段能否重推出 release config：受宿主工具链探针污染，未完成干净对照（不影响 pinned config 路线）。
- Raymer README 的性能自述（syscall 5×等）按纪律未引用、未复算。
- 停止条件：**未命中**（两个 profile 均在容器内构建成功，失败原因 none；未触发「下游补丁本身失败」或 2.5 小时时间盒）。