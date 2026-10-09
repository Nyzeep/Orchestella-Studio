# 研究：PRoot on modern Android 现状与性能特征

Labels: wayfinder:research
Type: research
Status: resolved

## Question

若未来交付 Userspace Compatibility Runtime（PRoot），它在现代 Android 上的真实状态是什么：W^X 限制下的可执行文件交付方式（jniLib 打包 ptrace 引擎是否可行）、ptrace 在 app 沙箱内的可用性与限制、syscall 拦截开销的实测分布（syscall 密集 vs CPU 密集负载）、与 phantom process killer 的冲突面、以及 proot-distro 生态的发行版适配经验可借鉴多少？

## Why it matters

执行模型决策（ADR-0001）将 PRoot 定义为正式的 Compatibility Runtime 产品等级、但 MVP 不交付——交付决策推迟到"有数据之时"，本票就是那份数据的来源。它同时校准「兼容模式」徽章必须如实标注的能力差清单。

## Working notes

- proot-distro 文档与 issue 跟踪：发行版适配矩阵、已知缺陷（syscall 覆盖、io_uring 等）。
- Termux 依赖 targetSdk 28 的历史包袱 vs 我们 targetSdk 36 的差异（exec 来源、ptrace 权限语义）。
- 性能参考：proot 开销文献与实测（文件操作密集场景的已知劣化）。
- 产出：docs/research/proot-modern-android.md。

## Answer

报告已落盘：**docs/research/2026-09-05-proot-modern-android.md**。

核心发现：
1. 实测：UnixBench 比 chroot 低 44%、CPU 比 native 低 7–10%（Termux #5245）；CPU 密集近原生完胜 TCG，I/O 密集严重劣化；无 guest kernel 的内存优势真实。
2. 硬限制：嵌套 ptrace 失效、部分内核 TRACEME 被拒、phantom killer 冲突更紧迫、无隔离与内核能力（与 ADR-0001 定位一致）。
3. 交付方式可行：jniLib 打包 proot + 目录树实例 + tarball 直接解包（无块镜像 provisioning）。
4. ADR-0001"定义等级、推迟交付"被实测数据支持；无需要重开 ADR 的新证据。
5. 雾区新增：Compatibility Runtime 契约设计（若交付）。
