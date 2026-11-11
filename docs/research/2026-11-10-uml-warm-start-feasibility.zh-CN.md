# UML warm-start 可行性决策 memo —— checkpoint/restore、hibernation、预热点池（U4）

- 日期：2026-11-10
- 工作包：`.scratch/uml-zero-device-20261110/spec.md` §3 U4（Warm-start / snapshot 可行性，P2 零设备研究）
- 工作分支：`feat/uml-u4-warm-start`（基于 Wave 2 集成 head）
- 方法：一手来源优先（Linux `arch/um` 源码与 Documentation、CRIU 官方文档与源码、下游端口仓库本体），内部设备证据单独标注；**事实 / 推断 / 待实测三分**；三条路径严格区分、不混称
- 原型：本环境（Windows 宿主）无现成 x86_64 UML 产物且构建成本超时间盒，**原型跳过**，相应结论标 `待实测` 并在 §9 给出设备/宿主窗口入口命令

## 0. 结论摘要（判词）

| 路径 | 判词 | 一句话依据 | 证据等级 |
| --- | --- | --- | --- |
| **① checkpoint/restore（落盘快照、跨进程生命周期恢复）** | **NO-GO** | 上游/下游均无任何机制；CRIU 无 UML 支持（ptrace 模式架构性受阻，seccomp 模式零先例）；历史 checkpoint 扩展全是已停维护的研究原型 | 一手来源（源码 + 官方文档） |
| **② hibernation（内核软件 suspend-to-disk）** | **NO-GO** | `CONFIG_HIBERNATION` 依赖 `ARCH_HIBERNATION_POSSIBLE`，UML 从未定义；`arch/um` 无 swsusp 架构 glue，配置项根本选不上。注意：UML **有**进程内 suspend-to-mem（`um_suspend_ops`），但那是"暂停"不是"落盘重启恢复" | 一手来源（源码） |
| **③ pre-warmed pool（预热点池）** | **CONDITIONAL GO** | 机制前提全部成立（COW 多实例共享只读 backing + 每实例 COW 层 + mconfig 热加设备）；内存锚点有设备实测（idle RSS ≈ 22–24 MB/实例）；硬前提 = 前台服务 + wake lock；resume 延迟与 N 实例/ARM64 数字待实测 | 机制：一手来源；内存锚点：内部实测；规模/resume：待实测 |

对产品承诺的直接回答（问题 6）：**T5 warm resume 不应作为 fast path 承诺的一部分。** fast path 冷启动已有亚秒级真机样本（0.22–0.40 s 到 marker），warm resume 的边际收益上限就是这 ~0.3 s；它退化为"预热点池"的可选优化，等 ACR 与设备窗口再定，不改变 spec §0 的两档承诺（fast shell T3 / full systemd T4）。

## 1. 三种路径的严格定义（本 memo 全文按此口径）

| 路径 | 定义 | guest 内存/进程状态 | 宿主进程生命周期 | 对标产品语义 |
| --- | --- | --- | --- | --- |
| checkpoint/restore | 把运行中 VM 的内存+进程状态**落盘**，之后（可能进程已退出、宿主已重启）**恢复**到同一执行点 | 落盘后可复现 | 可跨进程生命周期 | QEMU `savevm`/migration；CRIU 语义 |
| hibernation | 内核软件 suspend-to-disk：内核自己把系统状态写进 swap/镜像，进程退出，下次 boot 时 resume | 落盘 | 跨进程生命周期（重启后） | Linux `CONFIG_HIBERNATION` 语义 |
| pre-warmed pool | 预先启动好的实例**保持存活**（可暂停），需要时把请求交给它 | 始终在宿主内存里 | 不跨进程生命周期 | Android 预热 Activity/进程池语义 |

**禁止混称的三个高频误区**：mconsole `stop/go` 是 ③ 里的"暂停"原语，不是 ①/②；UML `um_suspend_ops` 是进程内 s2idle 型挂起，不是 ②；Android freezer 冻结是宿主级暂停，两者恢复后都会出现"时钟追赶"，但这不构成 ①/②。

## 2. 问题 1：UML 上游/下游有没有 checkpoint/restore、hibernation、save/restore？

**结论：都没有。三条独立的证据链。**

### 2.1 上游源码（torvalds/linux master，`arch/um`，访问 2026-11-10）

- `arch/um/Kconfig` 结尾只有：
  ```
  config ARCH_SUSPEND_POSSIBLE
  	def_bool y
  	depends on !SMP

  menu "Power management options"
  source "kernel/power/Kconfig"
  ```
  即：UML 声明了 suspend 可能力（仅 !SMP），并透出 `kernel/power/Kconfig`；**全文没有 `ARCH_HIBERNATION_POSSIBLE`**。
- `kernel/power/Kconfig`：`config HIBERNATION` 的依赖是 `depends on SWAP && ARCH_HIBERNATION_POSSIBLE`。UML 不定义后者 → **hibernation 在 UML 上配置不可达**。同时 `arch/um/` 下没有 `power/` 目录、`arch/um/kernel/` 里没有 `hibernate.c`（目录清单逐项核对过）——swsusp 所需的 `swsusp_arch_suspend()/swsusp_arch_resume()` 架构 glue 不存在。这是**架构性缺失**（有人补齐 glue 才能选上），不是"功能坏了"。
- `CONFIG_SUSPEND`（suspend-to-RAM/standby）依赖 `ARCH_SUSPEND_POSSIBLE` → **UML UP 构建可以选上**。而且 UML 真的实现了它：`arch/um/kernel/um_arch.c` 定义 `const struct platform_suspend_ops um_suspend_ops`（`.valid` 只接受 `PM_SUSPEND_MEM`；`.prepare`/`.finish` = `um_irqs_suspend()/um_irqs_resume()`；`.enter` = `um_idle_sleep()`——"identical to the idle sleep…only wake up on something we actually want to wake up on"），`late_initcall(init_pm_wake_signal)` 里 `suspend_set_ops(&um_suspend_ops)`。**语义是进程内挂起：宿主进程存活、内存保留、靠 wake signal 唤醒**。属于第 ③ 类的暂停原语，不是 hibernation。
- `arch/um/configs/x86_64_defconfig`：grep `suspend|hibernat|CONFIG_PM` 零命中——上游默认配置根本不开 PM。
- 历史面：patchwork.ozlabs.org `linux-um` 项目搜索 `checkpoint` = **0 patches**（2026-11-10）。

### 2.2 下游端口（本项目内核来源）

- `Raymer8639/linux-um-arm64` README（访问 2026-11-10）全文能力表（boot/PTRACE_SYSEMU/SECCOMP/Docker/USB passthrough 等）**不含任何 checkpoint/restore/save/restore/hibernation/suspend 条目**；commits 全文搜索 `checkpoint` = 0。该端口基于 uml/next（Linux 7.2-rc4）merge 7.3.1，上游 `arch/um` 这部分代码原样继承，未增加任何快照能力。
- 社区同类下游 `uml4termux` 在 README 明确自述 "has no RAM save/restore, so do not read 'resume' as 'instant'"（检索摘要引述，未逐字复核页面）——下游生态的一致口径是"没有 RAM 级 save/restore"。

### 2.3 研究先例（都不是可用基线）

- **SBUML**（Scrapbook for User-Mode Linux，Univ. of Tokyo/KTH，约 2005–2008）："We have developed SBUML (Scrapbook for UML), an extension of UML, by adding checkpointing functionality to UML." 用于模型检查/测试。**是研究原型，未上游化，面向老版本 UML，无人维护**。
- **TTVM**（King et al., UC Davis）：modified UML 做 checkpoint/rewind 调试研究，同样是学术工件。
- 含义：给 UML 加 checkpoint 在学术上做过且可行，但**没有任何可复用、可维护的上游/下游实现**；要走这条路等于自己维护一个 UML fork 的核心改造，与本项目"下游端口跟上游 merge"的维护模型冲突。

## 3. 问题 2：mconsole 到底能做什么？有没有 save/restore？

**结论：没有 save/restore；`stop/go` 是进程内暂停，内存与进程状态保留但不落盘。**

- 官方 HowTo v2（docs.kernel.org，访问 2026-11-10）记录的命令全集：`version / help / halt / reboot / config / remove / sysrq / cad / stop / go / proc / stack`。**没有 `save`、没有 `restore`、没有 `checkpoint`、没有 `snapshot` 命令**。与源码一致：`arch/um/drivers/mconsole_kern.c` 的 `UML_MCONSOLE_HELPTEXT` 同表（另有一个未列入帮助的 `log`）。
- `stop` 的源码语义（`mconsole_kern.c:227 mconsole_stop`）：`block_signals()` → 循环读 mconsole 请求直到收到 `go`。也就是说 **stop 之后这个 UML 宿主进程还活着，guest 全部状态（内核结构、guest 进程、页表、匿名映射）都还在宿主内存里**；HowTo 对 `go` 的警示是 "TCP connections may have timed out" 和 cron 补跑错过的任务——证明恢复是"原地续跑"，不是"重放"。
- HowTo 里 stop 被描述为对 "debugging/snapshotting" 有用——这里的 snapshot 指配合**宿主侧 COW 文件**在暂停期间做磁盘快照（复制 backing+COW），**不涉及内存**。没有任何机制把运行态内存持久化。
- `config`/`remove`：`config <dev>=<cfg>` 用与内核命令行相同的语法**运行时热加设备**（如 `config ubd3=/path`），`config <dev>` 查询，`remove` 摘除（设备须 idle：ubd 未挂载/无 swap/网口 down）。对热点池设计有用：池实例可以先以最小设备集启动，运行时再挂负载所需设备。
- **已知缺口（待实测）**：SMP（`ncpus>1`）下 `stop` 只在收到请求的处理线程里循环，其余 vCPU 宿主线程是否被同步冻结未验证；本 memo 的 stop 语义按 UP 口径表述。

## 4. 问题 3：CRIU 支不支持 UML？

**结论：CRIU 没有任何 UML 支持；ptrace 模式受 ptrace 单 tracer 限制架构性受阻；seccomp 模式（本项目 fast path）原理上有可能但零先例、零测试——不判"不可行"，判"未验证"。**

一手来源（访问 2026-11-10）：

1. **按宿主架构组织，无 UML 通道**：CRIU master（`b88bcdb`）`criu/arch/` 只有 `aarch64/arm/loongarch64/mips/ppc64/riscv64/s390/x86`。CRIU 的 arch 维度是**宿主 CPU 架构**，"UML" 不是其中之一。GitHub tracker（issues/PR 标题+正文）搜 `UML` / `user mode linux` / `uml` 均 **0 命中**——没有支持，也没有被讨论过。
2. **ptrace 模式：架构性受阻**。CRIU 官方 [What cannot be checkpointed](https://criu.org/What_cannot_be_checkpointed) 原文："CRIU uses the same API as debuggers do to get some tasks' state and this API (the ptrace one) doesn't allow for multiple debuggers to explore a task. Thus tasks under gdb or strace cannot be dumped." UML ptrace 模式下 guest userspace 线程被 UML tracing 线程 ptrace（`PTRACE_SYSEMU` 路径），与 CRIU 的 parasite 注入机制**在同一批 tracee 上争唯一 tracer 位**——这是内核 ptrace 语义（一个 tracee 一个 tracer），不是"缺补丁"能解决的。
3. **seccomp 模式：原理可能，未验证**。seccomp 快路径下 guest 线程不被 ptrace，只挂 seccomp filter（SIGSYS trap）；CRIU 源码 `criu/seccomp.c` 有 seccomp filter 的 dump/restore（含 TSYNC 处理），UML 的 ubd（普通文件）、stub（memfd/匿名映射）、mconsole/virtio-uml（unix socket）都不落在 CRIU "打开/映射设备不能 dump" 等硬禁区。**所以"宿主 CRIU dump 一个 seccomp 模式 UML 进程树"原理上可行，但没有任何人做过、CRIU 无 UML 测试覆盖、seccomp filter/内存布局逐字节对齐的工程风险全部未知**。任何"可行"结论都必须先出原型。
4. 反方向（**CRIU 跑在 UML guest 内部** dump guest 进程）：UML 实现了 guest 侧 ptrace（`arch/um/kernel/ptrace.c`）且 `select HAVE_ARCH_SECCOMP_FILTER`，理论要件部分存在；但 CRIU 在 UML guest 内运行无官方文档、无测试、无已知案例 → **unknown，待实测**。
5. 检索摘要（Plumbers/CRIU 材料提到"任何内核（含 UML）可用于测试"）指的是**用 UML 内核测试 CRIU 的内核接口**，不是"用 CRIU checkpoint UML 实例"；两者不能混用。

## 5. 问题 4：预热点池能否替代？内存/CPU/启动成本量级

**结论：可以作为替代，机制前提全部有一手来源支撑，预算量级有设备实测锚点；规模上限与 resume 延迟待实测。**

### 5.1 机制前提（全部一手来源，torvalds/linux master，访问 2026-11-10）

| 前提 | 机制 | 来源 |
| --- | --- | --- |
| 一份 pristine rootfs 服务 N 个实例 | ubd **COW**：`ubd0=<backing>,<cow-N>`，backing 只读共享，写入落各实例 COW 层 | `arch/um/drivers/ubd_kern.c`（`#include "cow.h"`、COW header 校验与 per-device cow 结构） |
| backing 共享不锁死 | 锁的排他性 = 打开方式：`os_lock_file(fd, openflags->w)`，只读打开取 `F_RDLCK`（共享锁）；另有 `ubd0c=` flag 显式"shared between multiple UMLs, file locking will be turned off"（集群语义） | `arch/um/os-Linux/file.c:566 os_lock_file`；`ubd_kern.c:358` 帮助文本 |
| 按需挂载负载设备 | mconsole `config` 运行时热加设备 | HowTo v2 + `mconsole_kern.c` |
| 暂停/恢复原语 | mconsole `stop`/`go`（进程内）；内核 `CONFIG_SUSPEND`（UP 构建）走 `um_suspend_ops` | §3、§2.1 |
| 进程态成本极低的实例 | UML 内核就是普通宿主进程，内存按触发页计费（`mem=` 只是上限不是全额承诺） | HowTo v2（"The UML kernel is just a process running on Linux"） |

### 5.2 内存成本公式与量级（内部设备实测锚点）

**公式（池账本）**：

```
M_pool(N) ≈ N × RSS_idle(实例) + Σ_i growth_i
growth_i = 负载加载后新增的触发页（ubd cache、dockerd、用户内存；上限 = mem= 参数）
磁盘：1 × pristine rootfs（共享只读） + N × COW 增量
```

- **RSS_idle 实测锚点**（device-myron，HyperOS，arm64，UP 内核 + ext4 + mem=512M 夹具，guest idle）：
  - 11-07 T5 门2 attempt2：A 段 23564→22912 kB（36min，−2.77%）、B 段 23296→23516 kB（24min，+0.94%）；
  - 11-07 attempt1（被 HyperOS 冻结段）：RSS 恒定 23932 kB；
  - 11-08 T8②：22592→22600 kB（60min，Δ+0.04%）。
  - **量级：idle UP 实例 ≈ 22–24 MB RSS**（n=3 轮同型夹具；跨夹具只做方向参考）。
- `mem=` 是地址空间上限：512M 夹具下 idle 实例只触发 ~23 MB；但**负载增长不设此锚点**——dockerd 形态的 RSS 未在宿主侧取数（11-08 T10 的 76128 kB 是 **guest 内** RSS 口径，不可直接当作宿主 RSS）。
- **N 实例线性叠加是否成立、SMP 内核 RSS、16K guest 页 RSS**：全部 `待实测`（U6 范畴）。

### 5.3 CPU/启动成本

- **池内待命（被 freezer 冻结）**：CPU ≈ 0（11-07 attempt1：冻结 33 分钟零内存漂移、guest uptime 冻在 0.32s）。冻结本身就是"零 CPU 的暂停"。
- **池内待命（前台服务+wake lock、未冻结）**：idle CPU 占用未测 → `待实测`。
- **补池（冷启动）**：T0 spawn → T3 marker **0.22–0.40 s**（11-07 门1，20 循环 19/20，归因后 20/20）；UP 10/10 boot 0.21–0.43 s；SMP ncpus=4 boot 0.23–0.43 s（11-07 T1）。**补一个热实例的代价 ≈ 亚秒级**。
- **resume 成本（stop→go）**：无任何公开计时数据 → `待实测`（入口命令见 §9）。

## 6. 问题 5：与 Android FGS/freezer 的交互——warm 实例被冻结/杀死时如何恢复？

内部证据（设备轮，全部已入库脱敏）：

- **E18 汇总**（`docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` §E18）：HyperOS freezer 将后台 App 连同 UML 子进程整组冻结（33 min 零漂移）；前台服务+wake lock 下 MagicOS 60 min 零冻结（RSS Δ+0.04%）、12/12 采样 `cgroup.freeze=0`。
- **无前台服务的冻结-唤醒全链**（11-07 报告 §7.1.6）：熄屏数分钟内 App+UML 同入 `0::/apps/uid_…` 且 `cgroup.freeze=1`；唤醒后 `cgroup.freeze→0`，guest uptime 从 0.32 **跳至 1710 s**（UML 时钟以宿主墙钟追赶，无回绕），内存仅 +32 kB。**含义：冻结态的恢复 = thaw 原地续跑，状态完好**；代价是 guest 时钟/定时器跳变、TCP/超时类逻辑要能容忍时间跳（与 mconsole `go` 的警示同型）。熄屏 doze 还会掐网络上游（11-08 T7-F #9 同窗观测）。
- **前台服务是硬前提**（11-08 T8②）：`KeepAliveService(specialUse)` + `PARTIAL_WAKE_LOCK` 下 60min 全程 `frz=0`、RSS Δ+0.04%、tick 0→120 无缺失、满 tick 自主关机 exit 0。对照：无前台服务 = 整组冻结。**"App 域长驻必须持前台服务"已从设计建议升级为双设备证据链**（HyperOS 冻结实锤 + MagicOS 豁免实证）。runner 的已知坑：wake lock `acquire(2h)` 有超时上限，超长驻留会静默失效（`research/uml-runner/README.md` §9）。
- **被杀死（LMK/OOM/用户 force-stop/kill -9）**：UML 宿主进程即 guest 的全部生命周期，进程死 = guest 瞬时消失，**没有任何恢复手段**——恢复路径就是冷启动重建：kill -9 后重启 `EXT4 recovery complete → mounted → UMARM_BOOT_OK`，无 F_SETLK panic、无锁残留持有者（11-07 T3 崩溃恢复门，2/2）；stop-file watcher 负责清理遗留停止信号（11-08 T8① 三路径 PASS）。
- **交互结论（对热点池的设计输入）**：
  1. 池实例必须跑在前台服务+wake lock 之下，否则"热"随时变"冻"；
  2. 冻结不是故障，是可利用的零 CPU 暂停——恢复靠 thaw（系统行为），不需要我们实现 restore；
  3. 真正的数据恢复手段只有 rootfs（ext4 日志恢复已实测），内存态没有任何持久化手段；
  4. thaw 后 guest 内核时钟墙钟追赶、doze 掐网，池实例交出前应做健康检查（uptime 跳变检测/网络重置），入口见 §9。

## 7. 问题 6：T5 warm resume 能否作为 fast path 的一部分？

**结论：不能，也不需要。** 判据链：

1. fast path 的定义就绪点是 **T3 first command**（spec §2）；T5 的定义是"从 snapshot/预热点恢复到 T3"（spec §2 指标表）。三条路径中只有 ③ 能落地为 T5 实现（①② 判 NO-GO）。
2. 预热点池 resume 相对冷启动的**边际收益上限 = 冷启动的 0.22–0.40 s**。冷启动本身已是亚秒级真机样本，两档承诺（fast shell / full systemd）不依赖 warm resume 成立。
3. 代价侧：每热实例 ~22–24 MB RSS + FGS/wake lock 电耗与厂商保活策略风险 + freeze/thaw 时钟跳变面。**收益 ≤ ~0.3 s、代价是一等常驻约束**——作为对外承诺不成立；作为 P2 可选优化（尤其对 **T4 systemd 档**，冷启动到服务就绪远长于 T3，warm 价值才显现）值得在 ACR 后评估，且先补 §9 的待实测项。
4. 一致性：spec M3 出口本来就写明"不承诺快照可用"；本 memo 维持该口径。

## 8. 可行性矩阵

| 能力 | 上游 UML | 下游 arm64 端口 | 本项目可用性 | 证据等级 |
| --- | --- | --- | --- | --- |
| mconsole stop/go（进程内暂停） | 有（UP 语义已证） | 有（继承） | 可用，resume 延迟未测 | 一手来源；延迟待实测 |
| 内核 CONFIG_SUSPEND / `um_suspend_ops`（进程内 s2idle） | 有（!SMP） | 有（UP profile 继承；SMP profile 因 `depends on !SMP` 无） | UP 可用；对热点池无额外收益（stop 已覆盖） | 一手来源 |
| hibernation（suspend-to-disk） | 无（`ARCH_HIBERNATION_POSSIBLE` 缺失，选不上） | 无 | 无 | 一手来源 |
| CRIU checkpoint（宿主 dump UML 进程树） | 无 UML 支持；ptrace 模式受阻；seccomp 模式未验证 | 无 | 未验证（不判不可行） | 一手来源（官方限制页+源码）+ 推断 |
| CRIU inside guest（guest 内 dump guest 进程） | 无官方支持 | 无 | unknown | 待实测 |
| 研究性 checkpoint 扩展（SBUML/TTVM） | 学术原型，停维护 | 无 | 不可作为基线 | 一手来源（论文） |
| ubd COW 共享只读 backing + 每实例 COW | 有 | 有（继承） | 热点池可用 | 一手来源 |
| Android freezer 冻结 → thaw 原地恢复 | n/a | n/a | 已实测（HyperOS 冻结-唤醒链） | 内部实测 |
| kill -9 → 冷启动重建（ext4 日志恢复） | n/a | n/a | 已实测（T3 门） | 内部实测 |

## 9. 待实测清单与设备/宿主窗口入口命令

以下数字全部缺失，须在下个窗口补；命令均为入口，不构成本轮结论。

1. **mconsole stop→go resume 延迟（T5 warm resume 的核心数字）**——宿主 x86_64：
   ```sh
   # U1 fast kernel + U2 预烘焙 rootfs（仓库内 research/uml-fastboot、research/uml-rootfs-prebake 产物）
   ./linux mem=256M ubd0=alpine.ext4 root=/dev/ubda rw seccomp=on con=null con0=null,fd:1 &
   # uml_mconsole 随 uml-utilities 提供；socket 路径见 UML 启动输出
   time uml_mconsole <socket> stop
   time uml_mconsole <socket> go     # stop→go 的墙钟差即 resume 延迟
   ```
2. **SMP（ncpus>1）下 stop/go 是否冻结全部 vCPU 线程**——同上，`ncpus=4` 夹具 + guest 内负载计数器校验。
3. **池实例（前台服务+wake lock）空闲 CPU 占用**——设备窗口：11-08 T8② 同型采样，加 CPU 时间列。
4. **N 实例 RSS/CPU/端口（热点池账本的 N 项）**——U6 宿主 benchmark N=1/2/4/8，然后设备门复测。
5. **CRIU on UML（seccomp 模式）原型**——宿主 Linux：CRIU master + U1 fast kernel（seccomp=on）+ Alpine rootfs；`criu dump -t <uml pid>` 后 `criu restore`，验证 guest marker 可续跑。**先出可行性原型再谈工程结论**。
6. **冻结池实例 thaw 后健康检查**——复用 11-07 attempt1 形态：`cgroup.freeze` 翻转 + guest uptime 跳变检测 + 网络重置验证。

## 10. 产品含义（不写产品代码，等 ACR）

1. 对外承诺维持 spec §0 两档：**fast shell（T3）/ full systemd（T4）**；不新增"秒恢复/T5"承诺。对外文案不得把"冷启动亚秒级"表述成"快照秒恢复"。
2. 若 ACR 后产品化热点池：按 §5 公式做内存预算（N × ~22–24 MB 起步，负载增长另计），FGS+wake lock 作为硬前提落地（照 `research/uml-runner` KeepAliveService 同型，含 wake lock 2h 上限处理），池实例生命周期与 freezer/thaw 健康检查并入启动器设计。
3. checkpoint/restore 与 hibernation 两条线**关闭**，除非：上游出现 `ARCH_HIBERNATION_POSSIBLE`/swsusp glue，或 CRIU 侧出现 UML 支持/先行原型——两者出现前重开此话题没有工程入口。
4. U0 parser 若接 T5 字段，需与 §9.1 的 stop→go 采集口径对齐（就绪点、时钟、n/中位数规则）。

## 11. 来源（外部访问日期均为 2026-11-10；内部来源用仓库路径）

外部一手来源：

- [S1] Linux `arch/um/Kconfig`（`ARCH_SUSPEND_POSSIBLE` !SMP；无 `ARCH_HIBERNATION_POSSIBLE`；source kernel/power）：https://github.com/torvalds/linux/blob/master/arch/um/Kconfig
- [S2] Linux `kernel/power/Kconfig`（`HIBERNATION depends on SWAP && ARCH_HIBERNATION_POSSIBLE`；`SUSPEND depends on ARCH_SUSPEND_POSSIBLE`）：https://github.com/torvalds/linux/blob/master/kernel/power/Kconfig
- [S3] Linux `arch/um/kernel/um_arch.c`（`um_suspend_ops`、`um_idle_sleep`、`suspend_set_ops`）：https://github.com/torvalds/linux/blob/master/arch/um/kernel/um_arch.c
- [S4] Linux `arch/um` 顶层/`kernel/`/`configs/` 目录清单与 `x86_64_defconfig`（无 `power/` 目录、无 hibernate.c、defconfig 零 PM 项）：https://github.com/torvalds/linux/tree/master/arch/um
- [S5] Linux `arch/um/drivers/mconsole_kern.c`（命令全集；`mconsole_stop/go` 实现）：https://github.com/torvalds/linux/blob/master/arch/um/drivers/mconsole_kern.c
- [S6] Linux `arch/um/drivers/ubd_kern.c` + `arch/um/os-Linux/file.c`（COW；`os_lock_file` 排他性=w 标志；`c` flag 关锁）：https://github.com/torvalds/linux/blob/master/arch/um/drivers/ubd_kern.c
- [S7] Linux UML HowTo v2（mconsole 命令语义、stop/go 警示、UML is just a process）：https://docs.kernel.org/virt/uml/user_mode_linux_howto_v2.html
- [S8] Raymer8639/linux-um-arm64 README（能力表无 checkpoint/hibernation；基线 uml/next 7.2-rc4 + 7.3.1）：https://github.com/Raymer8639/linux-um-arm64
- [S9] CRIU "What cannot be checkpointed"（tasks with debugger attached 不能 dump）：https://criu.org/What_cannot_be_checkpointed
- [S10] CRIU 源码（`criu/arch/` 目录清单；`criu/seccomp.c` filter dump/restore 与 TSYNC）：https://github.com/checkpoint-restore/criu
- [S11] CRIU GitHub tracker 搜索 `UML`/`uml`/`user mode linux` = 0：https://github.com/checkpoint-restore/criu/issues
- [S12] Patchwork linux-um 搜索 `checkpoint` = 0 patches：https://patchwork.ozlabs.org/project/linux-um/list/?q=checkpoint
- [S13] SBUML（UML checkpointing 研究扩展，KTH/U. Tokyo）：https://kth.diva-portal.org/smash/get/diva2:1060473/FULLTEXT01.pdf ；https://people.kth.se/~artho/papers/checkpointing.pdf
- [S14] TTVM（modified UML checkpoint/rewind，UC Davis）：https://bob.cs.ucdavis.edu/assets/dl/ttvm.pdf
- [S15] uml4termux（下游自述 "no RAM save/restore"，检索摘要引述）：https://github.com/michaelamoetoevsky/uml4termux

内部来源（仓库相对路径）：

- [I1] `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（T5 定义、UML 机制、两档承诺）
- [I2] `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1（UML 真机启动 220–400 ms；syscall 8×）
- [I3] `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1/§5/§7.1.6（boot 0.22–0.40 s；kill -9 恢复门；HyperOS 冻结-唤醒链）
- [I4] `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §2.2/§3（T8② FGS+wake lock 60min；T10 dockerd guest RSS 锚点）
- [I5] `docs/research/2026-11-05-performance-optimization-plan.zh-CN.md` §E18（freezer/FGS 约束汇总）
- [I6] `research/uml-runner/README.md` §9（KeepAliveService、wake lock 2h 上限）
- [I7] `.scratch/uml-zero-device-20261110/spec.md` §2/§3 U4（T5 定义与验收）

## 12. 与 spec 验收的对照

- 结论有来源/原型证据：§2–§6 每条判词带来源；"未找到"一律写 `unknown`/`待实测`，未写成"不可行"（唯一"不可行"级结论 ①② 均有一手来源支撑，且 ① 的 seccomp 分支只判"未验证"）。
- 与 FGS/freezer 的交互：§6。
- 不写产品代码：本 memo 为唯一交付物；原型按 §0 说明跳过（Windows 宿主无 UML 产物、构建成本超时间盒），入口命令见 §9。
