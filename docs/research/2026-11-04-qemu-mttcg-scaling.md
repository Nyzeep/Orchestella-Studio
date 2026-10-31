---
title: QEMU MTTCG 多核扩展性与调度策略调研（fabric 工单 04）
date: 2026-11-04（批次实际作业日 2026-10-31）
status: research
scope: 零设备调研 + benchmark 方案设计；不改 CONTEXT.md/ADR/代码，不构成实施承诺。工单：.scratch/orchestella-uml-fabric/issues/04-qemu-mttcg-scaling.md
method: 联网调研（QEMU 官方文档与邮件列表、Linaro 基准页、Linux man-pages、AOSP sepolicy/cgroup 文档、社区基准）+ 仓库资产只读核对（LaneBudgetLedger / DualLaneCoordinator / QemuLaunchSpec / 既有夹具文档）。
evidence: [官方源码] 源码/commit 级核验；[官方文档] 官方文档；[一手邮件] 邮件列表第一手帖；[基准] 有明确夹具的第三方基准；[社区] 第三方自述，仅互证；[本项目实测] 本仓库真机/单测证据；[本项目源码] 本仓库源码核对；[内部基线] 工单指定的已确立内部事实；[推算] 非实测推算；[未找到证据] 检索不到。访问日期均为 2026-10-31。
---

## 0. 结论摘要

1. **文献取数情况：aarch64 guest 的 TCG 扩展性曲线有数据，但宿主全部是 x86 Linux 主机；ARM64 手机宿主（App 域）样本为 0**。最强一组：Linaro 用 QEMU 9.1.0 跑 Android Cuttlefish，aarch64 guest TCG 启动时间 smp1 1034s → smp4 380s（**2.72×**）→ smp6 360s（**2.87×**）→ smp8 375s（回落 **2.76×**）[基准]。停止条件「文献无 Android/ARM64 TCG 扩展性数据」**部分命中**（有 aarch64 数据、无手机宿主数据），方案按工单要求转纯实测设计（§5），未编造任何手机侧数字。
2. **MTTCG 机制面（[官方文档]）**：thread=multi 时每 vCPU 一个宿主线线程；softmmu 下**每 vCPU 有独立 TCG 上下文与 TCG region，翻译阶段无锁**；tb_jmp_cache 每 vCPU 一份、全局 TB 哈希表（QHT）无锁查找；**全系统共享单一代码生成缓冲区**（写满触发所有 vCPU 全量 flush）；翻译内存不随核数线性翻倍，随核数线性增长的是每 vCPU 状态（上下文、TLB、jmp cache、线程栈）。
3. **扩展性的头号杀手不是翻译本身，是「宽原子回退串行化」与跨 vCPU 事件**：Linaro 修复前数据里 aarch64 `-cpu max` 因原子指令宽度超过宿主而触发 EXCP_ATOMIC 全停，smp8（1735s）比 smp4（1137s）**慢 53%**；换 `-cpu cortex-a57` 后恢复扩展（smp4 = 2.9×）[基准]。本项目真机参数已是 `-cpu cortex-a57`（会话 22）[内部基线]，规避了该坑，但 guest 用户态 128 位原子负载的行为列为 benchmark 必控变量。Cota 一手邮件自述 tb_lock 移除后「多核启动时仍大量 idle，怀疑来自跨 vCPU 事件（如 TLB 失效）」[一手邮件]。
4. **vCPU 数匹配**：QEMU 官方文档没有给出「vCPU=物理核」公式或超订警告 [未找到证据]；文献拐点在 4–6 vCPU（Linaro：「Android seems to be optimized for quad cores」）[基准] + 社区一致建议「-smp 不超过设备大核数」[社区]。本项目 -smp 4 在 8 核手机上约为 50% 订阅，给宿主 App 自身线程（主线程/RenderThread/Binder/GC/gvproxy）留余量——**这是纯设计论证，最终由 §5 benchmark 判定**。
5. **big.LITTLE 绑核是「可行性情报」而非承诺**：内核允许同 UID 进程间 setaffinity（man-pages EPERM 规则）[官方文档]；AOSP domain.te 允许全部域对 `self:process` 的 `setsched`（含 sched_setaffinity 走的 LSM hook）[官方源码]；Android cpuset 组（top-app/foreground/background/restricted）由框架管理，App 只能在框架划定的 cpuset 内细化 [官方文档]。**不改批次 1 前提（任务队列限流、不承诺绑核）**，绑核只作可选实验项，全部 not-tested（§3.2）。
6. **资源账本需求 8 条（R-Q1~R-Q8，§4）**，全部与 05 文档 §2.2 账本模型对齐，不改代码。最关键两条：match_host 模式下 ACCEL 车道 `cpuCores` 应填宿主全核数——账本核数字段语义必须定义为「并发预算」而非物理核（必然超订）；pVM 内存「不可换出 + 整进程可杀」要求账本支持 non-reclaimable 标记与运行中 LOST 的预留回收路径（运行中 ACCEL LOST 目前不释放预留，是 DualLaneCoordinator 的现状缺口，只列需求）。ACCEL 预算被拒→QEMU 单车道降级语义已存在，引用不重设计。
7. **benchmark 与 W-FAB-01 合并为一份可执行设计（§5）**：同一 make 夹具（生成器产出的 200 文件 × 10 函数 ≈ 2,000 函数 C 工程，与 10-29 夹具同族，`.o` sha256 等价校验），列组 A = QEMU `-smp 1/2/4/8` 四列，列组 B = protected Microdroid（1 vCPU / match_host 两档，即 W-FAB-01 原文），列组 C = native 参照列（同轮，翻译税判定依赖）+ 可选 proot/UML（G3 范围）。A+B 同轮采集；G3 其余列窗口不足时独立成轮、禁止跨轮相除。

## 1. 范围与前提

- 本票只管 **QEMU/宿主侧**的多核调度（工单背景）；pVM 面多 vCPU 由工单 05 覆盖，本文只在资源账本对接处引用其结论。
- **框架前提改用批次 1 产出**（05 文档 §2.2）：CPU 超订以任务队列限流处理，「fabric 编排只承诺并发任务数 ≤ 两车道账本核数和，不承诺绑核」。本票 §3.2 的亲和性研究全部标注为可行性情报，不改该前提。
- 本项目已确证事实：`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp 4 -m 6371M` 在 36 域真机启动至 guest root shell（来源：docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md §4.3，会话 22）[内部基线]；`QemuLaunchSpec` 支持 `tuning.tbSizeMib()` 调参（runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java:533-535）[本项目源码]。
- 口径纪律：跨会话/跨夹具数字禁止横比（ACR-draft-four-lanes-20261029.md §1.4）；推算标「非实测」；不引用 Raymer「25×」「编译≈native」（10-31 文档 §2.4）。本文所有外部数字均带夹具描述与证据等级。

## 2. MTTCG 机制面：翻译税随核数如何变化

### 2.1 线程模型

- `-accel tcg,thread=multi`：**每个 vCPU 一个宿主线程**；默认在「前后端均支持且无 icount/replay 等不兼容特性」时自动开启（来源：QEMU manpage，Debian bullseye qemu-system(1) 原文 "When the TCG is multi-threaded there will be one thread per vCPU therefore taking advantage of additional host cores"）[官方文档]。
- MTTCG 设计文档原文："In the general case of running translated code there should be no inter-vCPU dependencies and all vCPUs should be able to run at full speed"——即纯翻译执行路径理论上无跨 vCPU 依赖；同步只发生在共享内部结构与需要一致机器状态的场景（来源：qemu.org/docs/master/devel/multi-thread-tcg.html）[官方文档]。
- 回退到单线程轮转（thread=single）的条件：显式指定、icount 模式 [官方文档]（同上）。
- vCPU 线程命名：`mttcg_start_vcpu_thread()` 以 `"CPU %d/TCG"` 命名（accel/tcg/tcg-accel-ops-mttcg.c）[官方源码]（社区运行态截图互证：top/ps 中可见 `CPU 0/TCG`、`CPU 1/TCG` [社区]）。`-name debug-threads=on` 打开 Linux 逐线程命名以便观测（qemu manpage：Naming of individual threads can also be enabled on Linux to aid debugging）[官方文档]。

### 2.2 锁结构的演变：从 tb_lock 到无全局翻译锁

历史演变（对判断「锁竞争随核数的变化」有用）[一手邮件]（lore.kernel.org/all/1502149958-23381-1-git-send-email-cota(at)braap.org/，2017-08 tb_lock removal 系列封面帖）：

- MTTCG 合入早期（QEMU 2.9，2017）仍保留全局 `tb_lock` 串行化翻译；2017-08 的 "multiple TCG contexts" + "tb_lock removal" 两个系列把它拆掉：softmmu 引入**每 vCPU TCG 上下文**（翻译并行、无锁）、**per-page lock**（页级失效保护）、`tb->jmp_lock`（TB 跳转链保护）、QHT（无锁查找哈希）。作者在该帖同时自述："I still see a lot of idling when booting many cores. I suspect it comes from cross-CPU events (e.g. TLB invalidations)"——**锁拆分解决了翻译串行，但跨 vCPU 事件仍是观察到的剩余瓶颈**。

现状结构表（来源：qemu.org/docs/master/devel/multi-thread-tcg.html）[官方文档]：

| 结构 | 作用域 | 保护方式 | 随核数的含义 |
|---|---|---|---|
| tb_jmp_cache | 每 vCPU | 原子访问 | 每 vCPU 一份，无竞争 |
| tb_ctx.htable（QHT） | 全局 | 无锁查找、插入时同步 | 查找不竞争 |
| TCG 上下文 + TCG region | 每 vCPU | 翻译无需加锁 | 翻译完全并行 |
| 代码生成缓冲区 | **全系统共享一份** | 写满触发全 vCPU flush（经 async_safe_run_on_cpu 全停） | 核越多，一次全停 flush 的代价越大 |
| 全局页表 l1_map | 全局 | 无锁 radix tree（cmpxchg 插入） | — |
| 页级 TB 失效 | per-page lock | — | 不同页互不阻塞 |
| MMIO/IO 内存、中断状态 | 全局 | BQL（Big QEMU Lock） | IO/中断密集负载仍是串行点 |
| 跨 vCPU TLB flush | 跨 vCPU | tlb_flush_*_all_cpus_synced（全停同步） | TLB shootdown 密集负载的扩展性上限 |

（另：user-mode 仿真的翻译仍由 mmap_lock 串行——与本项目 QEMU system 模式无关，仅注记。）

### 2.3 内存开销随核数的行为

- **随核数线性增长**（每 vCPU 一份）：TCG 上下文与关联 TCG region、CPUTLB（softmmu，按 MMU index 分表）、tb_jmp_cache、宿主线线程栈 [官方文档]（multi-thread-tcg.rst Shared Data Structures 一节列出 per-vCPU 归属；具体字节数官方未给出，不估算）。
- **不随核数线性翻倍**（共享）：翻译代码缓冲区（`-tb-size` 控制总量）+ QHT + 全局页表。因此「MTTCG 内存开销随核数」的主要增量是每 vCPU 状态与线程栈，而翻译缓存总量不变 [官方文档] + [推算，结构性结论]。
- `-tb-size` 语义："Controls the size (in MiB) of the TCG translation block cache"，作用于共享缓冲区总量（来源：Debian bullseye qemu-system(1)）[官方文档]；Linaro 基准页的 monitor 输出实测默认容量为 1,073,659,904 B ≈ 1024 MiB（"gen code size 764934307/1073659904"，即默认缓冲区按 ~1 GiB 申请、按需提交）[基准]。本项目 QemuLaunchSpec 已有 `tuning.tbSizeMib()` 通道，默认不传（用 QEMU 内置默认）[本项目源码]。
- 对账本的含义：tb-size 是 QEMU 进程宿主侧内存的一部分，进 §4 R-Q1 的 `ramMiB` 口径（量级需设备窗口标定）。

### 2.4 扩展性的真实瓶颈（文献证据）

1. **宽原子回退串行化（EXCP_ATOMIC 全停）**：guest 原子指令宽度超过宿主原子能力时，QEMU 回退为「全 vCPU 停下、串行执行该原子指令」。Linaro 修复前实测：aarch64 guest `-cpu max` 下 smp1 1145s / smp2 1151s / smp4 1137s / **smp6 1326s / smp8 1735s**——加核反而更慢；换 `-cpu cortex-a57`（简化模型、避开宽原子）后 smp4 654s = **3.34×**、smp6 656s 持平、smp8 698s 略退（来源：Linaro wiki Android/QEMU boot time analysis，修复版 x/AwB44QY 页；宿主 i7-11850H 8C16T x86 Linux，QEMU 9.1.0，Android Cuttlefish 启动负载，单次测量无控温记录）[基准]。**本项目 `-cpu cortex-a57` 已在正确的一侧**[内部基线]；教训是「-cpu max 不等于更快」，任何 benchmark 不得改 CPU 型号而不复测。
2. **跨 vCPU 事件**：TLB shootdown 等 synced flush 要求全部 vCPU 停下（multi-thread-tcg.rst Current solution 一节）；Cota 一手邮件观察到的多核 boot 大量 idle 与此吻合 [一手邮件]。含义：**锁密集/共享内存密集型 guest 负载（跨核自旋、频繁munmap/mprotect）比独立负载扩展性差**——这决定 §5 夹具应包含两类负载形态（独立编译单元 vs 共享文件系统压力天然混合于 make），并要求 benchmark 记录「墙钟 vs CPU 时间」差值作为争抢指标。
3. **BQL 串行段**：MMIO 访问与中断状态更新仍过 BQL；中断密集负载（网络/定时器风暴）是潜在串行点 [官方文档]。本项目 gvproxy/virtio 路径属此类，长编译负载中占比小，列为观测项不作结论。
4. **翻译本身**：并行翻译（每 vCPU 独立上下文）意味着翻译吞吐随核数增长；但共享缓冲区写满触发**全 vCPU flush**，工作集接近 tb-size 时核数放大 flush 损失 [官方文档] + [推算，结构性结论]。量级参考：Linaro 页 aarch64 启动全程 TB expansion ratio 17.6、TB 平均宿主体积 361 B、生成代码 765 MB/1 GiB 上限 [基准]。

### 2.5 扩展性数据汇总（逐条带夹具）

| # | 数据 | 夹具 | 证据等级 |
|---|---|---|---|
| E-1 | aarch64 guest TCG：smp1 1034s / smp2 512s（**2.02×**）/ smp4 380s（**2.72×**）/ smp6 360s（**2.87×**）/ smp8 375s（**2.76×**）；pauth=off 必要（pauth=on 时 smp2 1280s，pauth 占执行时间至 40%） | 宿主 i7-11850H（8C16T，x86 Linux）；QEMU 9.1.0 + cmpxchg 修复补丁；Android Cuttlefish（aosp_cf_arm64_only_phone-img-12425990）完整启动到 BOOT_COMPLETED 标记；`-cpu max,pauth=off`；**单次测量、无控温记录** | [基准]（Linaro wiki，Pierrick Bouvier，linaro.atlassian.net/wiki/x/CgBP3AY） |
| E-2 | 同上 x86_64 guest：smp1 1036s / smp2 410s / smp4 280s / smp6=smp8 260s（QEMU 进程 CPU 占用 "barely reaches 500%"，Android 启动序列用不满 4 核）；KVM 参照 30s（TCG 整体 ≈ ×8–34） | 同上 | [基准]（同上） |
| E-3 | 修复前对照组（同负载）：aarch64 `-cpu max,pauth=off` smp1 1145s / smp4 1137s / **smp8 1735s**（EXCP_ATOMIC 串行化）；`-cpu cortex-a57` smp4 654s（**3.34×**）/ smp8 698s | 同上（QEMU 9.1.0 无 pbonzini 修复） | [基准]（linaro.atlassian.net/wiki/x/AwB44QY） |
| E-4 | tb_lock 移除前后（**相对收益**，非绝对核数扩展比）：debian-arm `-smp 8` 启动+关机 16.90s→12.78s（**1.32×**）；ubuntu ppc64el `-smp 16` 115.38s→76.36s（**1.51×**）；单线程无变化 | 宿主 Xeon E5-2690 16C32T；bootup+shutdown，5 次取均值 | [一手邮件]（lore.kernel.org/all/1502149958-23381-23-…，2017-08） |
| E-5 | 超订反例：2 核宿主上 riscv64 TCG dhrystone/make：2 vCPU 优于 4 vCPU（宿主饱和后加核只增加竞争） | 宿主 2C；ubuntu 22.04 riscv64 镜像；dhrystone + make -j 对比 | [社区]（stackoverflow.com/questions/74561599，高声誉回答，机理解释与 E-1/E-2 一致） |
| E-6 | **ARM64 手机宿主（App 域）TCG `-smp` 扩展性曲线：未取数**。检索到的 Android 手机侧 QEMU 数据（Termux 环境 sysbench/dd/网络吞吐）均无 -smp 维度；Termux QEMU ARM64 vs x86_64 guest 对比（dd 46.0 vs 14.4 MB/s、sysbench cpu 187.9 vs 117.0 eps、内存 306.7 vs 64.6 MiB/s）是跨架构对比，不能回答核数扩展 | 宿主：Android 手机 Termux；QEMU TCG 无 KVM；负载 sysbench/dd/speedtest；**未给 -smp、设备型号未记录完整** | [社区]（bl.ocks.org/Jobians/46a94af7…——32 位 gist id 因敏感门截断，站点已废弃，完整条目经 web.archive.org 检索 "Jobians Termux QEMU" 可还原）→ 记入 §7 停止条件处理 |
| E-7 | 社区调优口径："-smp 别超过设备大核数"、绑大核 taskset、tb-size 调大减少重复翻译 | 内容农场文章，无完整夹具 | [社区，质量低，仅互证]（iisidc.cn/archives/79448） |

**E-1~E-3 是同一研究、同一宿主的三组列，允许组内互比；E-4 是「锁优化前后」不是「核数扩展」；E-1/E-3 与本项目手机宿主不同构，只能作「形态参考」（亚线性、拐点在 4–6、原子串行化可反噬），不得换算为本项目预期值。**

### 2.6 翻译税是否随核数恶化——小结

- 机制上翻译不是串行点（每 vCPU 独立上下文/region，无锁）[官方文档]；随核数恶化的是共享结构事件（全量 flush、TLB shootdown、BQL 段）而非翻译本身。
- 翻译税占比的答案只能来自同轮 native 参照列（§5.4 判定标准）；文献无手机宿主数据，此处不下结论。

## 3. 调度策略分析

### 3.1 vCPU 数与手机物理核匹配

**QEMU 官方立场**：multi-thread-tcg.rst 与 qemu manpage 均未给出「vCPU 数 vs 宿主核数」的推荐公式或超订警告 [未找到证据]（检索 2026-10-31：multi-thread-tcg.rst 全文、qemu-system(1) manpage、qemu.org 新闻页）。官方只定义机制语义（每 vCPU 一线程、tb-size、thread=single|multi）[官方文档]。

**超订的行为机理**（社区高声誉解答 + 文献一致）：vCPU 是普通宿主线线程，由内核 CFS 调度；宿主饱和后每加一个 vCPU 只增加竞争，guest 整体吞吐下降或持平（E-5 [社区]；Linaro smp6→smp8 无收益/回落 [基准]）。

**4 vCPU 是否最优的论证（纯设计，非实测）**：

- 目标设备形态：8 核 big.LITTLE（1+3+4 或 4+4），偶见 6 核/10 核。App 域前台 cpuset 通常含全部核（§3.2）。
- 宿主 App 侧常驻线程：主线程、RenderThread、Binder 池、ART GC/JIT、gvproxy 进程、TCP↔vsock 桥线程、遥测/日志——需要随时可用的核做 UI 响应与桥转发。
- -smp 4 ⇒ QEMU 4 个 vCPU 线程，占 8 核的 50% 订阅，CFS 有余量同时喂 UI 线程；-smp 8 ⇒ 满订 + 跨 vCPU 事件开销增大（§2.4），UI 争抢风险最高。
- 文献拐点：Android guest 负载在 smp4–6 达峰（E-1：smp4→smp6 仅 +0.15×；E-3：smp6 持平 smp4、smp8 回退）。
- 结论形态：**「-smp 4 是当前证据下最稳的默认档」这一表述成立；「4 是全局最优」证据不足**——最优值依赖设备核数拓扑与负载，必须由 §5 四列数据回答。产品措辞遵守 ACR §4 三词纪律（不得写「完美调度」）。

候选默认策略（**纯设计建议，逐设备需 benchmark/设备窗口确认，不写代码**）：

| 设备可用核（App 域 cpuset） | 候选默认 -smp | 依据 |
|---|---|---|
| ≤2 | 2 | 不超过核数（E-5 超订反噬） |
| 4 | 3–4 | 满订边缘，留 1 核给宿主线程 |
| 6–8 | 4 | 50% 订阅；文献拐点 |
| >8 | 4–6 | 拐点后收益衰减，UI 余量优先 |

### 3.2 big.LITTLE 亲和在 App 域的可行性（可行性情报，不改前提）

**内核层（[官方文档]，man-pages）**：

- `sched_setaffinity(2)`：EPERM 条件为「调用者 euid ≠ 目标 uid/euid 且无 CAP_SYS_NICE」——**同 App UID 的进程间（Orchestella 主进程 ↔ QEMU 子进程）调用合法**；affinity 是 per-thread 属性（gettid），fork 继承、execve 保留；真实生效掩码 = 请求掩码 ∩ cpuset，交空则 EINVAL（linux.die.net/man/2/sched_setaffinity、man7 ptrace(2) 同 uid 判定算法）。
- **cpuset 由 Android 框架管理**：App 进程被框架放进 cpuset 组（top-app/foreground/background/restricted；AOSP task_profiles.json 属性名即 Low/High/MaxCapacityCPUs → background/foreground/top-app 的 cpus 文件），App **不能自选组**（SetTaskProfiles/SetProcessProfiles 是框架侧 API；cgroup abstraction layer 文档）[官方文档]（source.android.com/docs/core/perf/cgroups；android.googlesource.com device/generic/goldfish task_profiles.json）。因此绑核只能「在框架给的组内细化」：前台态通常含大核 → 绑大核可行；进程退到 background/restricted 后只剩小核 → 绑核目标核可能消失（设备相关）。

**SELinux 层（[官方源码]，历史 main 快照 + 需实测兜底）**：

- `sched_setaffinity` 走 LSM `task_setscheduler` hook → SELinux `setsched` 权限。AOSP system/sepolicy domain.te（快照 ae46511bfa62）：`allow domain self:process { fork sigchld sigkill sigstop signull signal getsched setsched getsession getpgid setpgid getcap setcap getattr setrlimit };`——**对自身（self）的 setsched 对全部域（含 untrusted_app/untrusted_app_34）放行**；untrusted_app.te 与 public/app.te 的 neverallow 均不触及 self:process setsched。
- 当前 main 的 domain.te 逐字复核被 googlesource 拒绝（NOT_FOUND），按纪律标注：**现行版本逐 tag 复核未完成，以设备实测为准**。社区互证：Termux 在 App 域用 `taskset` 绑核是广泛实践（E-7 同类来源 [社区]）；**本机实测 not-tested**。

**工程现实（结论：可做、不稳、不改前提）**：

- QEMU 自身无 per-vCPU 亲和 CLI：QEMU 7.2 引入的 `thread-context` 对象（`-object thread-context,id=tc1,cpu-affinity=0-1`）只服务于**内存预分配等经上下文创建的线程**，commit 原文明言 "upper layers are already aware of how to handle CPU affinities for long-lived threads like iothreads or vcpu threads"——vCPU 亲和由 libvirt 类上层外部处理（repo.or.cz qemu armbru commit e2de2c49）[官方源码]。
- 所以 App 域唯一路径是**事后对子进程线程逐个 setaffinity**（/proc/<pid>/task 枚举同 UID 可读 → sched_setaffinity(tid)），而 vCPU 线程虽常驻、其他 worker 线程动态创建，没有「新线程自动继承」的接口保证。这正好支持批次 1 前提：**以任务队列限流为主，绑核只作不承诺的可选实验**（not-tested，探针项入 §6 W-FAB-09）。

### 3.3 与宿主 App 渲染线程的争抢

- 调度归属：QEMU vCPU 线程与主线程/RenderThread 同属一个 App 进程（同一 UID、同一组 cgroup——前台时同在 top-app/cpuctl top-app 组）[官方文档]（cgroup abstraction layer）。框架经 task profiles 对关键线程做 uclamp/组提升（top-app 组 = MaxPerformance/MaxIoPriority）[官方文档]。
- 方向性结论：Android 侧的保障是「渲染线程在 top-app 组内被 uclamp 提权」，QEMU vCPU 同组无特权；**满订时（-smp 8）vCPU 长时间占核，渲染/输入依赖 CFS 抢占与 uclamp 提权，jank 风险最高；50% 订阅（-smp 4）下天然有余量** [推算，结构性结论]。QEMU vCPU 线程默认与普通线程同优先级，QEMU 无 per-vCPU 优先级 CLI [未找到证据]。
- App 侧可调项：主进程对 QEMU 子进程（同 UID）可 setpriority 调**低**其优先级（调高需 CAP_SYS_NICE，App 无）（man nice/setpriority 语义）[官方文档]；vCPU 线程级操作需逐 tid，与 §3.2 同一路径，not-tested。观测手段：`-name debug-threads=on` + 线程名 `CPU %d/TCG`（§2.1）。

### 3.4 小结

「4 vCPU 是否最优」的答案分层：机制面（无锁翻译、共享结构事件）+ 文献拐点（4–6）+ 宿主余量（50% 订阅）三线证据都指向 **4 是稳健默认档**；最优档位与设备拓扑相关，判据在 §5.4，最终数据来自设备窗口 W-FAB-07。

## 4. 资源预算需求清单（对接 LaneBudgetLedger，只提需求不改代码）

### 4.1 账本现状（[本项目源码] 核对，字段名/行为逐条引用）

- `LaneBudget(cpuCores, ramMiB, diskGiB, acceleratorAvailable)`（runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/duallane/LaneBudget.java:4）。
- `LaneRequest(lane, cpuCores, ramMiB, diskGiB)`（同目录 LaneRequest.java:4）；`LaneId { QEMU, ACCEL }`（LaneId.java:4-7）；`LaneRejectCode { NONE, CPU_EXCEEDED, RAM_EXCEEDED, DISK_EXCEEDED, ACCELERATOR_UNAVAILABLE, INVALID_REQUEST }`（LaneRejectCode.java:5-11）。
- `LaneBudgetLedger.reserve()` 校验顺序（LaneBudgetLedger.java:22-43）：非法参数→INVALID_REQUEST；`lane==ACCEL && !acceleratorAvailable()`→ACCELERATOR_UNAVAILABLE（:27-29）；CPU 超额→CPU_EXCEEDED（:30-32）；RAM→RAM_EXCEEDED（:33-35）；DISK→DISK_EXCEEDED（:36-38）；通过后累加 usedCpu/usedRam/usedDisk 并返回 `Reservation`（:39-43）。
- `release(Reservation)`（:45-52）；`canAdmitPool(memoryMb)`（:54-62，javadoc：pool 属 ACCEL 车道预算、不在 ledger 预留、池自有 lease）；`snapshot()`（:64-66）。
- `DualLaneCoordinator.startAll()`（app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java:115-210）：QEMU reserve 被拒 → `BUDGET_REJECTED` + 整体不启动（:124-131）；**ACCEL reserve 被拒 → accelState=DISABLED + 继续启动 QEMU**（:133-139）——「ACCEL 预算被拒→QEMU 单车道」语义已存在，本清单引用不重设计。ACCEL 启动失败 → LOST + 释放 accelReservation（:180-188）。
- **现状缺口观察**：运行中 ACCEL 车道消失（`awaitAcceleratorReady()` 检测 `!accelRuntime.isAlive()` → 置 LOST 并发事件，DualLaneCoordinator.java:224-229）**不释放 accelReservation**；预留仅在 stop confirmed（:367）或启动失败（:181-182）路径回收。pVM worker 被 lmkd 整杀是 05 文档认定的常态（05 §2.2），此缺口进入 R-Q6。

### 4.2 需求清单

| 编号 | 需求 | 对齐字段/位置 | 判定规则建议 | 可定性 |
|---|---|---|---|---|
| R-Q1 | QEMU 面记账口径：`cpuCores` = `-smp N`；`ramMiB` = guest `-m` + VMM 进程自身开销（TCG 每 vCPU 状态 + tb-size 缓冲 + gvproxy 进程）。对应 05 §2.2「每帧进程开销」行的显式化 | `LaneRequest.cpuCores/ramMiB` | tb-size 走 `QemuLaunchSpec.tuning.tbSizeMib()`，默认不传时按 QEMU 内置默认上限记账（~1 GiB 申请、惰性提交，§2.3）[基准]；VMM 开销量级需设备窗口标定，先按常量预留槽位 | 纯设计可定 + 量级需标定 |
| R-Q2 | pVM 面记账走 ACCEL 车道：cpuCores 两档（1 / 宿主全核数）；ramMiB = memoryMib + swiotlb 估算（引用 05 §2.1 `get_swiotlb_mib`，不重做） | `LaneRequest(ACCEL,…)`；`LaneBudget.acceleratorAvailable` 对应 AVF 能力门 | match_host 档填 `LaneBudget.cpuCores()`（宿主全核）；1 CPU 档填 1。对应 05 §2.4 D-4「1/MATCH_HOST 两档枚举即可」 | 纯设计可定 |
| R-Q3 | **核数字段语义 = 「车道并发预算」而非物理核数**：match_host 时 `usedCpu` 必然 ≥ 物理核（QEMU smp4 + pVM 全核），`CPU_EXCEEDED` 判定不能被解读为「物理核数硬上限」 | `LaneBudget.cpuCores` 语义文档化（javadoc 级） | budget.cpuCores 定义为「并发任务预算上限」，与 05 §2.2「任务队列限流、不承诺绑核」同构；派发侧并发上限 = 账本核数和（05 D-3 引用） | 纯设计可定 |
| R-Q4 | pVM 内存「**不可换出**」进判定（05 D-2 的落点）：ACCEL 的 ramMiB 需携带 non-reclaimable 属性 | 新增标记（字段/包装类型，实现留白） | 判定规则建议：Σ non-reclaimable 预留 ≤ 可用 RAM × 安全系数（QEMU 侧可换出部分不占该上限）；系数需设备窗口标定，禁止拍默认值 | 判定规则纯设计可定；系数需标定 |
| R-Q5 | pVM「**整进程可杀**」属性的记账含义（引用 05 §2.2「All guest pVM memory … can result in the process being killed」）：lmkd 杀 crosvm 是外部性，预算判定不为其建模，但**要求预留生命周期与进程生死解耦**（见 R-Q6），且 `canAdmitPool(memoryMb)` 的池口保持现状语义 | `LaneBudgetLedger.canAdmitPool`（:54-62 注释不变） | 池准入仍走 `acceleratorAvailable && usedRam+memoryMb ≤ budget.ramMiB`；不可换出属性由 R-Q4 的判定规则覆盖 | 纯设计可定 |
| R-Q6 | **运行中 LOST 的预留回收路径**：ACCEL 运行中进程消失（LOST，非 stop 流程）时预留必须可回收；UNCONFIRMED stop 保留预留的既有规则不变（LaneBudgetLedger javadoc：UNCONFIRMED keeps its reservation） | `DualLaneCoordinator.awaitAcceleratorReady` LOST 分支（:224-229）现状不释放——列为缺口观察 | 建议：LOST 事件携带「释放/保留」决策（与 lmkd 杀 = 确定消失、可释放；连接半开 = 不确定、保留）——语义分层，实现留待后续工单 | 纯设计可定 |
| R-Q7 | 两 VMM 并存记账公式（演示用，**非实测**）：`usedRam = QEMU(-m + VMM 开销) + pVM(memoryMib + swiotlb)`；`usedCpu = smp N + match_host 全核`。若 pVM 取 2048 MiB、QEMU 维持实测 -m 6371M（会话 22 [内部基线]），叠加结果须 ≤ budget.ramMiB，否则 `RAM_EXCEEDED` → ACCEL DISABLED → QEMU 单车道 | `reserve()` 现有校验顺序不变 | 公式本身纯设计可定；budget.ramMiB 的设备上限值需标定 | 公式可定；上限值需标定 |
| R-Q8 | 降级路径不新增：ACCEL 被 ACCELERATOR_UNAVAILABLE / RAM_EXCEEDED / CPU_EXCEEDED 拒绝 → DISABLED → QEMU 单车道继续（DualLaneCoordinator.java:133-139 既有语义；会话 23 实测 `budget_rejected ACCEL ACCELERATOR_UNAVAILABLE → lane_started QEMU` [本项目实测]）。产品语言遵守 ACR §4 三词纪律 | `LaneRejectCode` 全集已覆盖需求，无新枚举 | — | 已存在，引用 |

### 4.3 与批次 1 缺口清单的关系

- R-Q4/R-Q6 是 05 文档 D-2（「pVM 不可换出 + 整进程可杀进预算判定；两 VM 并存 reserve/降级路径」）在 QEMU 面视角的展开；R-Q3/R-Q7 与 D-3（编排并发上限）衔接。不新增 D 编号，实现工单挂接时以 05 的 D 清单为准。

## 5. benchmark 方案（与 W-FAB-01 合并为一份可执行设计）

> 定位：G3（同夹具四列，10-31 文档 §5 W1）之外「QEMU 面多核」的独立数据源 + protected pVM 多 vCPU 吞吐（05 §6 W-FAB-01）。共用同一夹具、同一轮次协议、同一设备窗口。

### 5.1 夹具定义（可执行规格）

**夹具名 F-MTTCG-C200**：生成器产出的纯 C 工程。

- 生成器参数：`N_FILES=200`、`FUNCS_PER_FILE=10`（共 2,000 函数——与 10-29 §5.2 的 2,000 函数夹具同族；工程化拆分呼应 10-31 文档 G3 的「200 文件小工程」思路）；每函数 ~25 行、引用文件内静态全局状态防死代码消除；文件间仅共享一个公共头（`fixture.h`：类型与声明，无内联函数）。
- 构建：`clang -c -O1`（与 10-29 §5.2 夹具同 flags 家族）；`Makefile` 目标 `all: $(OBJS)`；`-j` 由外层注入。
- **规模校准**：若设备窗口实测 smp1 列单轮中位 < 30 s，则 `N_FILES` 逐级倍增至 400/500 重生成（保证计时噪声 < 1%）；短版 200×10 先跑 1 轮冒烟校准。校准记录随数据归档。
- 等价性：每列/每轮所有 `.o` 的 sha256 与生成器 manifest 逐字节比对（沿用 10-29 §5.2「同产物比的是同一份工作」口径），不一致的样本作废。
- 工具链：列组 A/C 用同一份 Alpine clang 22.1.3（沿用 10-29 §5.1 的宿主解包 + tar 推送路径，宿主 native 参照列与 QEMU guest 列逐字节同一工具链）；列组 B 用 payload clang 22.1.8（P09 既有约束）。**两套工具链 = 两个比对组，组间绝对值禁比**（ACR §1.4）；跨组只允许比较「加速比形态」，且报告中必须标注工具链差异。
- 传输：夹具 tar（含 Makefile + manifest）一次性推入 QEMU guest 与 pVM payload 资产目录；清单（文件数、总行数、总字节、每文件 sha256）入库归档。

### 5.2 列定义与轮次协议

| 列组 | 配置 | 回答的问题 | n |
|---|---|---|---|
| A（QEMU 扩展性，本票新增） | QEMU TCG `thread=multi`，`-smp 1/2/4/8` 四列；`-cpu cortex-a57`、`-m 6371M`、tb-size、rootfs 全列固定；guest 内 `make -j min(nproc_guest, smp)` | -smp 扩展曲线、饱和点 | 各 ≥10 |
| B（pVM，= 05 W-FAB-01 原文） | protected Microdroid `setCpuTopology` 1 / MATCH_HOST 两档；驱动侧 QEMU guest `make -j N` 经 distcc→桥→payload 进程内编译器派发；记录 pVM 启动稳定性、nproc、吞吐 | pVM 面 vCPU 扩展、两车道并存现实性 | 各 ≥10 |
| C-nat（native 参照，本票新增，**必须与 A 同轮**） | 宿主 adb shell 环境，同一 Alpine clang 同一夹具 `make -j <宿主可用核>`（并另记 `-j1` 串行列） | 翻译税占比基准 | ≥10 |
| C-opt（可选，G3 范围） | proot/bare、UML 列 | G3 四列横向 | ≥10 |

**同轮/独立规则**：A + B + C-nat 同一设备窗口轮换采集（交错执行以均衡温度）；C-opt 若窗口时间不足**必须独立成轮**，其数字不与 A/B/C-nat 相除。C-opt 中的 UML 列与 A 组对比时仅允许「同轮共夹具」前提下比较，否则标 `未取数`。

**控温与顺序**：每轮前静置至温度稳定（记录阈值与方法）；列顺序按 Latin square 随机化；轮间记录温度并冷却；出现降频（频率低于标称档、thermal 事件）的样本作废重跑；全程记录充电/电量状态（充电会引入热与频率伪影）。

### 5.3 采集字段表

| 字段 | 来源 | 备注 |
|---|---|---|
| make 墙钟 | guest 内 `date +%s%N` 差值（C-nat 列宿主侧等价） | 主指标 |
| user/sys CPU 时间 | shell `time` / `/proc` 计时 | 与墙钟差值 = 争抢/等待指标 |
| 每 vCPU 线程 utime+stime | 宿主侧 `/proc/<qemu pid>/task/<tid>/stat`，按线程名 `CPU %d/TCG`（`-name debug-threads=on`） | 核间均衡性 |
| TB 统计 | QEMU monitor（TB count / gen code size / 缓冲用量；Linaro 页即用此口径 [基准]） | 翻译工作量与 tb-size 水位 |
| 温度 | 多个 thermal_zone（沿用 10-29 §5.2 的 zone 记录法） | 作废判据 |
| 频率 | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` + 受限状态 | 降频判据 |
| 内存 | `/proc/meminfo`；QEMU/payload 进程 VmRSS/VmHWM | 对接 R-Q1 口径标定 |
| 拓扑 | `nproc`、guest/宿主 CPU 拓扑（big.LITTLE 分簇记录） | 判定分层依据 |
| 环境 | QEMU 版本、内核版本、build 通道、充电状态 | 夹具描述必备 |

### 5.4 判定标准（纯设计建议，阈值可由 ACR 调整）

1. **「-smp 4 是否饱和」**：定义 S(n) = median(T(smp1)) / median(T(smpn))，E(n) = S(n)/n。判据：E(4) ≥ 0.60 且 S(4) ≥ 2.0 → 「4 列有效扩展」；S(8) < S(4) × 1.15 → 「8 列无收益，默认档不超过 4」；S(4)/S(2) < 1.2 → 「扩展性不足，触发回退讨论」。显著性：n≥10 中位数 + IQR 报告，列间比较用 Mann-Whitney U（p<0.05）。
2. **「翻译税占比」**：同一轮 C-nat 列（同 clang 同夹具）为基准，翻译税 ≈ 1 − T_native / T_QEMU（按列报告，串行列 `-j1` 对 `-j1` 比较以排除并行度干扰）。C-nat 不在场时该项一律 `未取数`。
3. **等价性门槛**：`.o` sha256 与 manifest 不一致的样本一律作废。
4. **pVM 列（B）**：判定沿用 05 W-FAB-01 原文（Gunyah 实机启动稳定性、nproc、`make -j` 吞吐，n≥10），不在本文改写。
5. **设备窗口产出**：一张「fabric CPU 预算表」（QEMU 各 smp 档吞吐 + pVM 两档吞吐 + 内存实测），直接喂 R-Q1/R-Q7 的量级标定。

### 5.5 与 W-FAB-01 的合并点与差异

- 合并：同一夹具包（F-MTTCG-C200）、同一轮次协议（控温、随机化、n≥10、等价性校验）、同一设备窗口、同一数据归档。
- 差异：A/C 列工具链 = Alpine clang 22.1.3；B 列工具链 = payload clang 22.1.8（P09 门）——按 5.1 的两比对组规则执行，不混算。B 列结论归 05 工单，A/C 列结论归本票，合成报告时交叉引用。

## 6. 设备窗口清单增量（编号延续 05 文档 W-FAB-01..06，均 not-tested）

| 编号 | 窗口项 | 方法要点 | 对应 |
|---|---|---|---|
| W-FAB-07 | QEMU `-smp 1/2/4/8` 扩展性四列 | §5 列组 A + 5.4 判定 1；同轮控温；手机宿主首个扩展性数据源 | 本票（R-Q3/Q7 标定） |
| W-FAB-08 | native 参照列（同轮） | §5 列组 C-nat + 5.4 判定 2；同 clang 同夹具 | 本票（翻译税） |
| W-FAB-09 | App 域亲和性探针（可选） | `sched_getaffinity` 现值记录；前台/后台态 `sched_setaffinity` 绑大核实测（主进程对自身 tid 与对 QEMU 子进程 tid 两条路径）；记录 SELinux denial 与 cpuset 内容；验证 domain.te setsched 在现行版本的实际行为 | 本票 §3.2（仅情报核验，不改前提） |

## 7. 停止条件核销与证据缺口

- 工单停止条件原文：「文献无 Android/ARM64 TCG 扩展性数据 → 如实记录未取数，方案改为纯实测设计」。**部分命中**：取到 aarch64 guest 扩展曲线（E-1/E-2/E-3）与锁优化收益（E-4），但宿主均为 x86 Linux 主机；**ARM64 手机宿主（App 域）的 -smp 扩展数据为 0 样本**（E-6）。处理：如实记录 + 方案按纯实测设计执行（§5），全部手机侧预期值以「形态参考」限定，未编造任何数字。
- 其余证据缺口（不阻塞本票交付）：QEMU 官方对超订的无立场声明 [未找到证据]；现行 AOSP main domain.te 逐字复核（googlesource 访问受限，改由 W-FAB-09 实测兜底）；TCGContext/TLB 的每 vCPU 字节数官方未公布（不估算）。

## 8. 与既有结论的关系

- 不改 ACR 草案 §1 主表与 §1.4 口径警告；本文所有外部数字只作形态参考，任何进入 ACR 的数字必须来自 §5 同轮数据。
- 不推翻 05 文档资源模型；§4 是其对 QEMU 面视角的需求展开（D-2/D-3 的展开与衔接），未新设账本或模型。
- 不改批次 1「任务队列限流、不承诺绑核」前提；§3.2 全部为可行性情报。
- 会话 22 的 `-smp 4` 真机启动事实与本票 `-cpu cortex-a57` 规避宽原子串行化的文献证据（E-3）相互印证；QEMU 参数不变性的重要性提升为 benchmark 必控变量。

## 9. 来源清单（访问日期均为 2026-10-31）

- [官方文档] QEMU Multi-threaded TCG 设计文档：https://www.qemu.org/docs/master/devel/multi-thread-tcg.html（vCPU 调度、per-vCPU TCG context/region、共享代码缓冲区、QHT/页锁、TLB synced flush、BQL；7.0 版同文镜像 https://www.qemu.eu/doc/7.0/devel/multi-thread-tcg.html）
- [官方文档] QEMU manpage（qemu-system(1)，Debian bullseye 版）：https://manpages.debian.org/bullseye/qemu-system-common/qemu-system.1.en.html（tb-size、thread=single|multi、-smp 语义；-name/debug-threads 概述见 qemu.org/docs/master/system/invocation.html）
- [一手邮件] Emilio G. Cota，"tcg: tb_lock removal" 系列（2017-08）：https://lore.kernel.org/all/1502149958-23381-1-git-send-email-cota(at)braap.org/（封面帖：page locks/jmp_lock/qht、多核 boot idle 归因跨 vCPU 事件；URL 中 `@` 因敏感门改写为 `(at)`，访问时还原）；perf 补丁：…/1502149958-23381-23-…（E-4 数字）
- [一手邮件] Emilio G. Cota，"MTTCG: i386, user+system mode" RFC（2015-08）：https://lists.gnu.org/archive/html/qemu-devel/2015-08/msg02560.html（原子自旋锁实现的历史教训）
- [基准] Linaro wiki，Pierrick Bouvier，"Android/QEMU boot time analysis"（两版）：https://linaro.atlassian.net/wiki/x/CgBP3AY（修复后 E-1/E-2）与 https://linaro.atlassian.net/wiki/x/AwB44QY（修复前对照组 + cortex-a57 数据 + TB 统计）
- [官方源码] QEMU mttcg_start_vcpu_thread 线程命名 "CPU %d/TCG"（accel/tcg/tcg-accel-ops-mttcg.c；经社区源码复述两处互证：blog.csdn.net/bme314/article/details/149251995、qemu.gevico.online/tutorial/2026/ch2/qemu-multi-core）
- [官方源码] QEMU ThreadContext 对象 commit（David Hildenbrand，QEMU 7.2）：https://repo.or.cz/qemu/armbru.git/commitdiff/e2de2c497e5744bc2c009aadd0af7633f2947948（cpu-affinity/node-affinity 属性；vCPU 亲和由上层处理的原文）
- [基准/社区] StackOverflow 74561599（2 核宿主 riscv64 超订行为解答）：https://stackoverflow.com/questions/74561599/what-happens-if-i-use-more-cores-in-qemu-than-total-available-cores-in-host
- [社区] Jobians，Termux QEMU ARM64 vs x86_64 基准（无 -smp 维度）：bl.ocks.org/Jobians/46a94af7…（32 位 gist id 因敏感门截断；站点已废弃，完整条目经 web.archive.org 检索可还原）
- [社区，质量低，仅互证] iisidc.cn/archives/79448（"-smp 别超过大核数"、taskset 绑大核建议）
- [官方文档] Linux man-pages：sched_setaffinity(2)（EPERM 规则、cpuset 交集、per-thread、fork/execve 继承；linux.die.net/man/2/sched_setaffinity）；ptrace(2)（PTRACE_MODE_ATTACH_REALCREDS 同 uid 判定算法；man7.org/kernel.org 版）
- [官方源码] AOSP system/sepolicy domain.te（快照 ae46511bfa62）：https://android.googlesource.com/platform/system/sepolicy/+/ae46511bfa62b56938b3df824bb2ee737dceaa7a/domain.te（`allow domain self:process { … getsched setsched … }`）；untrusted_app.te / public app.te（neverallow 不触及 self:process setsched；android-13.0.0_r3 cs.android.com 快照）
- [官方文档] Android cgroup abstraction layer：https://source.android.com/docs/core/perf/cgroups（task_profiles/SetTaskProfiles 框架管理；cpuset/cpuctl 组）；device/generic/goldfish task_profiles.json（LowCapacityCPUs=background/cpus、HighCapacityCPUs=foreground/cpus、MaxCapacityCPUs=top-app/cpus）
- [本项目实测] 会话 22/23 真机数据与 QEMU 参数（经工单指定为已确立内部事实）：docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md §4.3、ACR-draft-four-lanes-20261029.md §7.1
- [本项目源码] runtime/instance/.../duallane/LaneBudgetLedger.java、LaneBudget.java、LaneRequest.java、LaneId.java、LaneRejectCode.java；app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java；runtime/engine-qemu/.../QemuLaunchSpec.java:495,533-535
- [内部基线] docs/research/2026-11-05-pvm-fabric-route.md（§2.2 账本模型、§4 D-1..D-6、§6 W-FAB-01..06）；docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md §2.4/§5；docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md §5.1-5.2（2000 函数夹具与等价性口径）；.scratch/orchestella-uml-fabric/issues/04-qemu-mttcg-scaling.md
