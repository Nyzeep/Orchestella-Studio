# 研究：PRoot on modern Android 现状与性能特征

Labels: wayfinder:research
Type: research
Status: open

## Question

若未来交付 Userspace Compatibility Runtime（PRoot），它在现代 Android 上的真实状态是什么：W^X 限制下的可执行文件交付方式（jniLib 打包 ptrace 引擎是否可行）、ptrace 在 app 沙箱内的可用性与限制、syscall 拦截开销的实测分布（syscall 密集 vs CPU 密集负载）、与 phantom process killer 的冲突面、以及 proot-distro 生态的发行版适配经验可借鉴多少？

## Why it matters

执行模型决策（ADR-0001）将 PRoot 定义为正式的 Compatibility Runtime 产品等级、但 MVP 不交付——交付决策推迟到"有数据之时"，本票就是那份数据的来源。它同时校准「兼容模式」徽章必须如实标注的能力差清单。

## Working notes

- proot-distro 文档与 issue 跟踪：发行版适配矩阵、已知缺陷（syscall 覆盖、io_uring 等）。
- Termux 依赖 targetSdk 28 的历史包袱 vs 我们 targetSdk 36 的差异（exec 来源、ptrace 权限语义）。
- 性能参考：proot 开销文献与实测（文件操作密集场景的已知劣化）。
- 产出：docs/research/proot-modern-android.md。
