# 实测数据驱动的性能优化方案（2026-11-05）

- 状态：research（分析提案，不含实现、不改产品代码；采纳与否归 ACR/维护者裁定）
- 基线：main `9f18c31`；分支 `docs/perf-plan-20261105`
- 作者：Lane N 性能方案研究智能体（GLM-5.3-Flash）
- 设备口径：全部使用稳定别名（device-myron / device-mey / qa-alt-01），无序列号/型号/私人数据
- 数字纪律：本方案每个实测数字后紧跟出处（报告 § 或 文件:行，行号以 `9f18c31` 为准）；凡仓库无记载一律写「未取数」，不估算不外推；跨会话/跨夹具数字**可并读不可相除**（ACR 草案 §1.4，来源：`docs/research/ACR-draft-four-lanes-20261029.md` §0.4）
- 置信度分级：**高** = 已有实测基线外推；**中** = 原理成立、同族场景已实测但本项未测；**低** = 待验证（无本项数据）

---

## 执行摘要

1. **【最高性价比 ①】fabric 派发宽度与记账定案（零代码）**：QEMU-TCG 面按 **2 vCPU 记账、宽并行任务不派 QEMU**，派发优先级 **native > UML > QEMU-TCG**——T4（S(4)=1.42 不达判据、S(8)=1.12 反噬）与 T9（S(2)=1.60@n=10）双设备复核，翻译税中位 92%，全部实测在案，只差把数字落进编排决策。
2. **【最高性价比 ②】编译加速以 payload 进程内 libclang 为主路径**：真机 protected VM 内 p50 **98 ms/编译**（dv-pvm-01 实测），对比 QEMU guest 内单文件全编 32,750 ms（n=1）；distcc 网络卸载的真机三判据至今未验证，不应作为主路径依赖。
3. **【最高性价比 ③】编译 worker 池容量旋钮 0→N**：池与分派器已接线且 JVM 全绿，容量出厂 0 = 今天行为（`ProtectedWorkerPool.java:150`），打开它只差一个产品监听起点调用点与容量决策——典型「旋钮已存在只差开关」。
4. 启动路径：T3 凭据-only 重复启动已实现+CI 回归门，缺一次真机验证即可常态化（§A1）；P1-5 温池冷启动移出主锁已在 `fix/instance-write-locks` 分支（d92e4ac）待合入（§A2）。
5. 快赢清单（旋钮已存在/已建成）：T7 QemuTuning 11 变体设备矩阵、worker 池容量、UML `ncpus=` 宽度、LOST 预留回收（已修 b07dca0）。
6. 需要新开发的只有两处重投入：fabric 编排接线（B2/C1 的落地）与首启 provision 超时/锁恢复硬化（A3）。
7. 图形组结论：先补端到端显示基线测量（当前为 0），再谈投入；不提案 VirGL/GPU 加速（验收前置未达成）。

---

## 0. 证据底座（本方案依赖的实测数字总表）

| # | 事实 | 数字 | 出处 |
|---|---|---|---|
| E1 | UML app 域多核（T2 G1） | SMP `ncpus=4`，4×sha256(32MB) 并行/串行 0.2554 → 有效加速 **3.92×**（E=0.98），无 tracee 被杀 | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §2 |
| E2 | QEMU MTTCG 扩展曲线（T4，device-myron，n=3 轮） | C200 make smp1/2/4/8 中位 399/241/281/356 s → **S(2)=1.66 / S(4)=1.42 / S(8)=1.12**；S(8)<S(4) 反噬；`S(4)≥2.0 且 E(4)≥0.60` 不满足 | 同上 §4、§4.1 |
| E3 | T9 补轮 n≥10（device-mey） | smp1 中位 1014.5s / smp2 中位 632.5s → **S(2)=1.60、E(2)=0.80**，配对同轮 S(2) 中位 1.61；与 device-myron 1.66 跨设备一致 → **手机宿主 QEMU MTTCG 最优宽度 = 2 vCPU** | `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §0 T9、§8 |
| E4 | 翻译税（同轮 `-j1` 对 `-j1`） | ≈88–93%，**中位 92.0%** | 2026-11-07 报告 §4.1 |
| E5 | native 宿主参照（同轮同夹具） | `make -j8 / -j1` = 4.791/31.750 s → **6.63×**（E=0.83） | 同上 §4.1 |
| E6 | 宿主原生 8 核 vs QEMU TCG 并行宽度 | native 6.63× ≫ QEMU 1.1–1.7×（同轮同窗，UML-worker 最硬论据） | 同上 §4.1 |
| E7 | payload 进程内编译（dv-pvm-01 01b 编译门，qa-alt-01 protected VM 单 vCPU） | clang 22.1.8 进程内 10/10 成功，**p50=98 ms、P95=332 ms**，obj 123,920 B 恒定，RSS +1.0%（2 GB VM 无 OOM；libLLVM 常驻 28 MB + libclang 14 MB） | `docs/reports/tickets/dv-pvm-01/implementation-report.zh-CN.md` §1、§4 |
| E8 | 编译器库加载成本 | dlopen libLLVM 235 ms（RSS→35.3 MB）、libclang-cpp 336 ms（→49.7 MB） | 同上 §4 加载门表 |
| E9 | 编译器库体积与打包 | strip 后 libLLVM 61.7 MB + libclang-cpp 62.2 MB = 123.8 MB；APK 基线 9.5 MB → 含编译器 140.7 MB，6 `.so` 全 STORED+16KB 对齐门禁全过 | `.scratch/orchestella-pvm-compiler/issues/PVM-04-libclang交叉构建spike.md` Comments、`PVM-08-APK打包与体积.md` Comments 第二条 |
| E10 | QEMU guest 内单文件全编（TCG） | 32,750 ms（n=1 复用，含预处理）；pVM 单 job 端到端 2,809 ms（n=1）；native bare ≈313 ms/文件 p50（n=3）——**三数来自不同会话不同夹具，禁横比** | `docs/research/ACR-draft-four-lanes-20261029.md` §1.1 性能行、§1.4 |
| E11 | UML 编译 2,000 函数夹具 | UML 中位 940 ms vs native 中位 460 ms = **2.04×**（15 样本，产物逐字节相同） | `docs/research/2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md` §5.2、§5.4 |
| E12 | UML 启动 | UP 0.21–0.43 s / SMP 0.23–0.43 s（guest uptime 至 marker），spawn→exit duration 中位 612.5 ms（T1，n=10） | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1 |
| E13 | QEMU 车道真机启动（产品路径冒烟） | `lane_started QEMU STARTED 24823ms`、`ACCEL STARTED 414ms`；awaitAcceleratorReady gate 3.5–3.9 s、payload_ready ~30 s（QA VM） | `docs/reports/tickets/dv-pvm-02b/implementation-report.zh-CN.md` §2（该报告顶部推翻声明仅限 distcc 远端编译归因，车道启动 logcat 不在其列） |
| E14 | T3 凭据-only 重复启动 | 已装配实例 0 次打开/0 B 读取 rootfs，segment ≤256 KiB（真实 segment ~59 KB）；红/绿证据 + CI 回归门已入库；**真机 not-tested** | `docs/reports/orchestration/runtime-performance-remaining-20261018.zh-CN.md` §T3/T8 |
| E15 | T7 QemuTuning 旋钮 | CPU model / `tb-size` / `aio` / `discard=unmap` / `detect-zeroes=unmap` / vCPU / passt UNIX stream，共 11 变体 × 6 workload = 66 行 dry-run；默认值逐项复刻今天行为；**设备 not-tested** | 同上 §T7 |
| E16 | UML 多核旋钮 | `ncpus=`（`--cpus=` 为 unknown param 透传），默认 host-1，banner 自述 "Using 7 CPUs of the host's 8" | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1、§7.1.2 |
| E17 | 宿主频率档位对 TCG 的影响 | device-mey 大核持续负载 ~1.5 GHz（标称 3.2 GHz）上 smp1 中位 1014.5 s，比 device-myron 399 s 慢 **~2.5×** → TCG 绝对墙钟不可跨设备比较，成本模型须按宿主档位分档 | `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §0 T9 注、§8.4 |
| E18 | App 域长驻冻结约束 | HyperOS freezer 将后台 App 连同 UML 子进程整组冻结（33 min 零漂移）；前台服务+wake lock 下 MagicOS 60 min 零冻结（RSS Δ+0.04%）、12/12 采样 `cgroup.freeze=0` | 2026-11-07 报告 §7.1.6；2026-11-08 报告 §2.2 |
| E19 | P1-5 温池锁问题与修复 | 冷创建（`api.start`+30 s 轮询+HELLO）全程持 `ReentrantLock`，`state()/stats()/release()` 同锁（`ProtectedWarmPool.java:301,310,499`，积压 P2-5/P1-5 行）；修复 d92e4ac「锁内决策+锁外执行」在 `fix/instance-write-locks` 分支，1133 tests 绿，**尚未在 main `9f18c31`** | `docs/reports/quality-backlog-20261104.zh-CN.md` :53、:199；`git show d92e4ac` 报告 §2.2、§6.3 |
| E20 | worker 池容量旋钮 | `Config.Builder.capacity` 默认 0 = 确定性 `DISABLED` 拒绝（不建 VM 不排队，与今天逐字节等价）；`WorkerPoolDistccDispatcher` 已接线，产品 TCP 监听起点仍缺（PVM-09 §5.4 划归 DV-PVM-02b）；`queueTimeoutMs(30s) < idleTimeoutMs(60s)` 校参约定已入 javadoc | `app/src/main/java/io/github/nyzeep/orchestella/ProtectedWorkerPool.java:150`、`WorkerPool.java:20`、`docs/reports/tickets/pvm-06/worker-wiring-report.zh-CN.md` §2–§4、§8 |
| E21 | distcc 链路真机状态 | PVM-10 拆除 60 s 地板、PVM-11 两段式 `-target` 宿主孪生复现 `0x6500`→修后绿（52/52）；真实 distcc 3.4 客户端零设备 E2E 1/1 绿（STAT=0、远端 .o 608 B ≠ 本地 1448 B）；**真机三判据（DOTO>0、distcc_ms−本地秒级、无 `running locally instead`）not-tested** | `docs/reports/tickets/pvm-11/implementation-report.zh-CN.md` §1、§8；`docs/research/2026-11-04-pvm-zero-device-round.zh-CN.md` §0 Lane A、§2 |
| E22 | 首启 provision 超时与锁 | TCG+大版本差场景 92 包 `-Syu` 事务超产品 1800 s/操作超时（attempt6 `operation-timeout`）；超时 SIGKILL 遗留 `/var/lib/pacman/db.lck` 无恢复路径（attempt7）；首启 >30 min（boot-retry 可收敛） | `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §1 表 #6/#7、§8.3 |
| E23 | 账本现状 | LOST 预留不释放缺口**已修**（b07dca0，`DualLaneCoordinator.java:229` 现调 `releaseLaneReservation(ACCEL)`）；pVM 内存「不可换出」non-reclaimable 标记**仍缺**（`LaneBudgetLedger.java` 无该字段，仅 `canAdmitPool`:58）；cpuCores「并发预算」语义未文档化（R-Q3） | `app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java:224-231`；`runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/duallane/LaneBudgetLedger.java:58`；`docs/research/2026-11-04-qemu-mttcg-scaling.md` §4.1–4.2 |
| E24 | 显示通道现状 | 生产路径 = RFB over LocalSocket/vsock，能力枚举 `GRAPHICS_WAYLAND` 未声明（ADR-0017 实现状态注记 2026-10-20）；guest 侧 RFB 采集率固定 `--max-fps 15`（phase5 冻结记录） | `docs/adr/0017-wayland-graphics-channel.md` 实现状态注记；`docs/reports/archive/2026-10-phase5-quality/reviews/phase5-profiles/review-report.zh-CN.md` :24 |
| E25 | 宿主 UI 渲染基线 | QA 包 framestats：scroll p50 5 ms / janky 0.21%；tab 切换 p50 6 ms / janky 9.55%（2026-10-28 采集） | `evidence/optimize-20261028/framestats-before-{scroll,tab}.txt` |
| E26 | 首启显示/图形预期管理 | TCG 档图形 = 软渲染 + 5–20× CPU 减速叠加；M4 GPU 前置（gfxstream 原型）未达成 | `docs/adr/0012-graphics-phasing.md` Consequences |

> 口径警告（全文适用）：E10 三数跨会话跨夹具，只用于量级叙事，禁止相除；E1/E5 与 E2/E3 来自不同夹具（sha256 vs C200 make），ACR §1.2 已钉「可并读不可相除」。

---

## A. 启动路径

### A1 · T3 凭据-only 重复启动路径真机验证并常态化 【类型：管线已建成，缺一次真机验证与观测】

- **现状证据**：已装配实例重复启动走 credentials-only，宿主 0 次打开/0 B 读取 rootfs，initramfs 段 ≤256 KiB（真实 ~59 KB）；`ProvisionedLaunchRegressionTest` 红/绿证据齐、已入 CI 门（E14）。真机侧从未跑过一遍：launch-timeline.jsonl 的 `rootfsBytes=0 / credentialsOnly=true` 无真机样本。
- **优化方案**：不改代码。设备窗口执行 `docs/reports/orchestration/runtime-performance-delivery-handoff-20261018.zh-CN.md` §5 的真机清单 1–3 条（Ubuntu/Arch 各一实例，首装→停止→重启，核对 timeline 字段与 guest 到达提示符）。
- **预期收益**：重复启动跳过 distro rootfs 全量读取（Ubuntu/Arch rootfs 数百 MB–GB 级 IO）与整段嵌入；量级未测（**置信度：中**——JVM 契约已证 IO 归零，真机墙钟收益未取数）。
- **成本与风险**：一次设备窗口（~1 h）；无代码风险。回退条件（metadata/sha 不符自动走全量）已内建。
- **验证方法**：真机（timeline 字段核对）；timeline JSONL schema 有 allowlist 测试，字段可信。
- **依赖与阻塞**：无。这是所有后续启动优化（A2/D2）的观测前提。

### A2 · P1-5 温池冷启动移出主锁合入 main，温池容量作为显式决策项 【类型：已开发待合并 + 低成本快赢】

- **现状证据**：`ProtectedWarmPool.acquire()` 在 `ReentrantLock` 内贯穿整段冷创建（`api.start` + payload 就绪轮询上限 30 s + 控制口 HELLO），冷启动期间 `state()/stats()/release()` 全部阻塞（E19，积压 P2-5 行 `quality-backlog-20261104.zh-CN.md` :53）。修复已完成：d92e4ac（`fix/instance-write-locks` 分支，基于 9f18c31）重构为「锁内决策 + 锁外执行」，CREATING 状态排他替代持锁互斥，新增 4 并发回归用例 + 阴性验证，1133 tests 全绿（E19）；**该提交尚未进 main**。
- **优化方案**：① 合入 d92e4ac；② 既有 17+4 用例零改动保持；③ 温池保持容量语义不变，把「protected 预热池是否预建槽位」列为产品显式决策（与 B3 的 worker 池容量决策同场裁定）。
- **预期收益**：行为正确性收益为主——冷启动期间 UI/查询线程的 `state()/stats()` 与租约 `release()` 不再挂死（修复报告 §7 明说**未做定量基准**）；次级收益是温池命中时 acquire 等待不再被无关冷创建阻塞（**置信度：高**——行为改进有测试实证；墙钟量化收益：未取数）。
- **成本与风险**：合并成本≈0（分支干净、基于当前 main）；风险低（单 stop owner/预算账本不变量有论证，报告 §2.2）。
- **验证方法**：零设备自验（新增并发测试 + 既有 ProtectedWarmPoolTest 17 用例已绿）；真机温池冷/热启动计时归后续设备窗。
- **依赖与阻塞**：无外部依赖；与 P0-3 残余同票同分支。

### A3 · 首启 provision 硬化：每操作超时分档 + pacman 锁恢复路径 【类型：新开发（小），首启成功率向】

- **现状证据**：TCG guest 内 92 包 `-Syu` 大事务（含 linux-firmware 拆分包）超出产品 provision `run_step` 1800 s/操作超时，`operation-timeout(124)`；SIGKILL 遗留 `db.lck` 后下次 boot 报 `unable to lock database`（被误报为 offline）——产品**无锁恢复路径**（E22）。
- **优化方案**：两处产品改动：① `run_step` 超时按操作类型分档（package-install 给更高上限或改为「进度心跳 + 总闸」）；② provision 前置检查/恢复 `db.lck`（存在且无 pacman 进程时删除），照 T7-F probe-init.sh 的探针侧修法（`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §1 表 #7）产品化。
- **预期收益**：首启成功率提升（当前 92 包场景首启 >30 min 且会撞超时）；对「快」的收益是间接的——避免 boot-retry 反复重启造成的数倍首启时间（**置信度：中**——失败模式已实测，修复后收益未测）。
- **成本与风险**：小-中（状态机内两处）；风险：超时放宽可能掩盖真死锁，须保留总闸与心跳日志。
- **验证方法**：真机（T7-F 配方复跑全链）；锁恢复分支可先零设备单测（镜像状态注入）。
- **依赖与阻塞**：不阻塞 ACR 投票；若 92 包事务被拆分（镜像 vintage 策略变更）则本条自动降级。

---

## B. 编译加速

### B1 · 编译主路径定为 payload 进程内 libclang，distcc 网络卸载降级为可选增强 【类型：机制门已过，产品接线是主工作量】

- **现状证据**：进程内编译真机基线 p50 **98 ms**（E7），库加载 235/336 ms（E8），体积 123.8 MB/打包门禁全过（E9）。对比两条候选路径的现状：guest TCG 内单文件全编 32,750 ms（E10，n=1）；distcc 卸载链路真机三判据（DOTO>0、`distcc_ms − 本地` 秒级、无本地回退）**至今 not-tested**（E21）——且 dv-pvm-02b 首轮的 85–94 s 数字已被推翻声明作废，不得引用为基线。
- **优化方案**：编译工作调用方（awaitAcceleratorReady 的产品消费者，`dv-pvm-02b` 报告 §9 列为空缺）直接走 payload 进程内编译器；distcc 网络卸载仅在「编译任务量超出单 payload 吞吐且多 worker 已就绪」时启用。落点：PVM-09 已完成 manifest/facts/starter/dispatcher 车道启动，缺的就是调用方一格（`docs/reports/tickets/pvm-06/worker-wiring-report.zh-CN.md` §1 第 3 层）。
- **预期收益**：单文件编译从 TCG guest 的 32.75 s 量级降到亚秒级（≈两到三个数量级；**置信度：高**——E7 为真机实测基线，量级差有 E10 佐证但两者夹具不同，精确倍数禁止声明）。
- **成本与风险**：中。风险：① payload 需 131 MB APK 增量（E9，扩展包形态已可容纳）；② protected-only 资格设备面（W-FAB-03 普查 0 个 AOSP AVF 样本，2026-11-08 报告 §0 T13）；③ triple 语义（声明的 musl triple vs 实出 android triple）已记录 advisory，对「只要 .o」无害（PVM-11 报告 §6）。
- **验证方法**：零设备可自验宿主孪生（52/52 既有）；真机判据=同夹具产物等价（PVM-05 归 DV-PVM-02）+ 端到端 compile_ms。
- **依赖与阻塞**：编译工作调用方（产品接线一格）；不依赖 distcc 真机验证（这正是选它为主路径的理由）。

### B2 · fabric 编排把 UML 面纳入编译 worker（宽并行任务派发） 【类型：新开发（编排层），数据已齐】

- **现状证据**：同一设备同窗三方并读（禁相除，E6 上下文）：native 6.63×（E5）、UML 3.92×/E=0.98（E1）、QEMU-TCG ≤1.77×（E2 同轮比值）。UML 编译夹具 2.04×（E11）；UML 启动 0.21–0.43 s（E12）比 QEMU 车道 24.8 s（E13）低两个量级；长驻/崩溃恢复门已过（T3/T5，2026-11-07 报告 §0）。
- **优化方案**：按 05 文档 D-1/D-3 落地编排：`DistccTcpVsockBridge` 增加 lane 标记通道（vsock=pVM 面 / TCP=UML 面，wire 不变）、任务并发上限 = 账本核数和、per-worker 回退与掉线剔除（`docs/research/2026-11-05-pvm-fabric-route.md` §4 D-1/D-3）。
- **预期收益**：宽并行编译任务的墙钟随 worker 宽度接近 native 形态（同 SoC 上 6.6× vs 1.1–1.7× 的差距，E5/E6）；UML worker 冷启动几乎免费（E12），可按需扩缩（**置信度：高**——三面宽度数字同窗实测；端到端编排收益未取数）。
- **成本与风险**：中-大（编排层新开发 + UML 启动器产品化照 `research/uml-runner` 同型 + 前台服务/wake lock 硬前提，E18）。风险：GPL-2.0 内核分发义务（ACR §1.1 发布形态行）；App 域长驻 freezer 前提必须落实。
- **验证方法**：零设备：编排单元测试（fake worker）；真机：C200 夹具派发 vs 单 worker 对照，控温协议照 F-MTTCG §5.2。
- **依赖与阻塞**：ACR 四车道投票（D-3 编排策略输入已列议程）；UML 启动链产品接线。

### B3 · 编译 worker 池容量 0→N：旋钮已存在，差一个监听起点与容量决策 【类型：低成本快赢】

- **现状证据**：`WorkerPool`/`ProtectedWorkerPool` 已实现且全绿（21+9 用例），`WorkerPoolDistccDispatcher` 已把「一条 distcc 连接 = 一次 acquire 租约」接进设计意图调用路径；容量出厂默认 0 = 确定性 `DISABLED` 拒绝（不建 VM 不排队），与今天行为逐字节等价（E20）。产品 TCP 监听起点只剩一个调用点：`new DistccTcpVsockBridge(loopback, port, new WorkerPoolDistccDispatcher(...), jobLimit, idleMs)`（worker-wiring 报告 §4）。
- **优化方案**：① 实现监听起点（一格调用）；② 容量按预算账本定档（`Admission.ledgerBacked` 已共享双车道账本，`resize` 受 `CPU_EXCEEDED` 约束，worker-wiring 报告 §6 判据 `workerPoolGrowthIsBoundedByTheCoordinatorLedger`）；③ 校参：池排队超时（默认 30 s）< 桥 idle（默认 60 s），`maxConnections ≥ 2×capacity + 少量`（§8/§9）。
- **预期收益**：并发编译吞吐随容量 N 线性（直至账本上限）；每个 worker 是 B1 的进程内编译器（p50 98 ms/文件），N=2 即 ~2× 文件级吞吐（**置信度：中**——池行为 JVM 全绿，真机多实例并发收益未测，归 DV-PVM-02b 三判据）。
- **成本与风险**：小。风险：容量提升是显式决策（决策地图 §8.4 明文「默认不得变更」）——本提案不改默认值，只把开关交给产品配置；内存评审前置（每 worker 一台 VM）。
- **验证方法**：零设备：装配/分派契约测试已绿可直接回归；真机：多实例并发编译收益、vsock 并发、重建退避（DV-PVM-02b 清单）。
- **依赖与阻塞**：B1（调用方语义）先行裁定容量非零才有意义。

---

## C. UML 运行时宽度与调度

### C1 · 派发宽度与 vCPU 记账定案：QEMU 面按 2 记账、宽并行不派 QEMU 【类型：零代码决策，数据已齐——本方案最高性价比项】

- **现状证据**：T4 判据不满足（`S(4)≥2.0 且 E(4)≥0.60` 实测 1.42/0.35；`S(8)=1.12 < S(4)×1.15` 反噬条款命中，E2）；T9 以 n=10 复核 S(2)=1.60/E(2)=0.80 且跨设备一致（E3）；翻译税中位 92%（E4）；T4 报告已给结论「QEMU 车道 vCPU 预算按 2 记账（而非 4）；宽并行任务不应派给 QEMU 面；派发宽度优先级 native > UML > QEMU-TCG」（2026-11-07 报告 §4.1 对 W-FAB-07/08 的回答）；R-Q3 的「cpuCores=并发预算语义」为纯设计可定（mttcg 文档 §4.2 R-Q3）。
- **优化方案**：ACR 投票采纳后写进编排（D-3 输入）与账本 javadoc：① QEMU 面预算按 2 vCPU 记账；② 派发器对宽并行任务（可拆分 make 型）跳过 QEMU 面或仅派 2 并发；③ 记账公式按 R-Q7（`usedCpu = smp N + match_host 全核`）。
- **预期收益**：避免把宽并行任务压进 92% 翻译税且 S(8) 反噬的面——同负载在 native/UML 面可快 2–6×（E5/E1/E11 同窗可并读）；资源账本不再按虚高的 4 vCPU 占额（**置信度：高**——全部为已实测数字的直接应用）。
- **成本与风险**：零代码（纯决策+javadoc）；风险仅在于「交互型负载 vs 编译型负载」混部时 2 vCPU 可能偏保守——判据绑定 make 型夹具，交互负载另行评估。
- **验证方法**：零设备（数据已在案）；产品化后用 F-MTTCG-C200 夹具复测派发端到端。
- **依赖与阻塞**：ACR 议程（2026-11-08 报告 §8 已列为头号议题输入）。

### C2 · UML worker 宽度旋钮 `ncpus=` 产品化，默认档按宿主档位选择 【类型：内核旋钮已存在，产品接线未做】

- **现状证据**：UML SMP 多核旋钮是 `ncpus=`（`--cpus=` 是 unknown param 透传 userspace），默认 host-1（E16）；ncpus=4 下 G1 门 3.92×/E=0.98 无 tracee 被杀（E1）；SMP 启动与 UP 同档（0.23–0.43 s vs 0.21–0.43 s，E12）。频率受限宿主上 TCG 绝对性能再降 ~2.5×（E17）——宽度与档位解耦的教训同样适用于 UML 面的默认档。
- **优化方案**：产品 UML worker 启动参数暴露 `ncpus=N`（默认维持 host-1），N 由账本并发预算扣除后给出；对 CPU 预算紧张（前台 UI + 双车道并存）场景保守取 2。
- **预期收益**：编译 worker 吞吐 ×N（上限受 E1 的 E=0.98 边界与账本约束）；启动成本几乎不变（E12）（**置信度：高**——T1/T2 实测；产品化端到端未测）。
- **成本与风险**：小（启动参数一格）；风险：GPL 分发义务（与 B2 同）；`ncpus` 上限应 clamp 到宿主可用核 − 宿主 App 自身预留（mttcg 文档 §3.1 的 50% 订阅论证）。
- **验证方法**：零设备：参数构造测试；真机：T1/T2 探针复跑（配方在 `research/uml-fabric-probe/`，2026-11-08 报告 §9）。
- **依赖与阻塞**：B2 编排落地。

### C3 ·（可选实验，不承诺）App 域 vCPU 亲和探针 W-FAB-09 【类型：待验证，列为探针项而非提案】

- **现状证据**：`sched_setaffinity` 同 UID 合法、AOSP domain.te 对 `self:process setsched` 全域放行（历史快照），cpuset 由框架管理、App 只能组内细化；QEMU 无 per-vCPU 亲和 CLI，唯一路径是事后逐 tid setaffinity（mttcg 文档 §3.2）；**全部 not-tested**。批次 1 前提「任务队列限流、不承诺绑核」维持。
- **优化方案**：不进入产品默认路径。设备窗口顺带跑 W-FAB-09 探针（记录 `sched_getaffinity` 现值、绑大核实测、SELinux denial），数据进 fabric 账本附录。
- **预期收益**：未知（**置信度：低**——纯待验证）；若可行仅作小核让位/大核偏置的微调。
- **成本与风险**：探针级；风险是改批次 1 前提——本提案明确不做。
- **验证方法**：真机探针；**依赖与阻塞**：无（搭其他窗口便车）。

---

## D. 内存与 IO

### D1 · 账本语义补丁：non-reclaimable 标记（R-Q4）+ cpuCores 并发预算语义文档化（R-Q3） 【类型：小改，预算正确性】

- **现状证据**：LOST 预留回收缺口已在 main 修复（b07dca0，`DualLaneCoordinator.java:229` 调 `releaseLaneReservation`，E23）；剩余两缺口：pVM 内存不可换出属性未进判定（`LaneBudgetLedger.java` 无 non-reclaimable 字段，仅 `canAdmitPool(memoryMb)`:58），cpuCores 字段「物理核数」误读风险未文档化（R-Q3/R-Q4，mttcg 文档 §4.2）。
- **优化方案**：`LaneBudgetLedger` 增补 non-reclaimable 标记（判定规则：Σ non-reclaimable ≤ 可用 RAM × 安全系数，系数待设备标定、禁拍默认值）+ `cpuCores` javadoc 定义为「车道并发预算」。两 VMM 并存记账公式按 R-Q7。
- **预期收益**：正确性收益——防止「QEMU 大 -m + pVM 2048 MiB 不可换出叠加」把设备推入 lmkd 杀进程循环（杀掉的恰是 carrying 不可换出内存的进程）；间接保护 QEMU 车道稳定性（**置信度：中**——机理成立、叠加场景未实测）。
- **成本与风险**：小；风险：系数未标定前不启用硬判定（标记先记账后生效）。
- **验证方法**：零设备（单测账本判定）；真机标定 RAM 上限（F-MTTCG §5.3 内存字段已列）。
- **依赖与阻塞**：无；与 B2/C1 的账本输入同场。

### D2 · 只读输入盘（T5/T6）重启评估：先补首启分段时间，再决定是否开发 【类型：先测量后决策，可能否决】

- **现状证据**：T5/T6 于 2026-10-18 deferred，理由是「无设备数据，无法证明首次启动宿主准备/initrd 内存是主要成本」（E14 出处报告 §T5/T6）；此后无新增测量。注意 T3 凭据-only（A1）已覆盖「重复启动」场景的宿主侧 IO 归零，T5/T6 的剩余价值集中在**首次启动**与 guest 侧输入盘。
- **优化方案**：不直接开发。设备窗口在 A1 的同一轮顺带采集：首启各阶段 timeline 分段（INITRAMFS_BUILT→guest marker）、initramfsBytes、guest 内存基线；数据达「首次启动/内存确为主要成本」门槛才立票，否则记录关闭该架构线（delivery-handoff §5 第 5 条原文路径）。
- **预期收益**：未知，取决于测量（**置信度：低**——待验证）；若数据不支持则显式否决，避免为不存在的瓶颈付架构成本。
- **成本与风险**：测量成本低（与 A1 同窗）；开发成本若立项为 M 级。
- **验证方法**：真机测量先行；零设备无意义。
- **依赖与阻塞**：A1 同窗执行；T5 改动涉及 `QemuLaunchSpec` 时避开 T7 矩阵（remaining 报告 §风险 4）。

---

## E. 图形与显示

### E1 · 显示通道端到端基线测量先行（当前为 0，先测再投入） 【类型：先测量】

- **现状证据**：生产路径为 RFB over LocalSocket/vsock，`GRAPHICS_WAYLAND` 未声明（E24，ADR-0017 实现状态注记）；guest 侧 RFB 采集率固定 `--max-fps 15`（E24）；**端到端显示延迟/帧率无任何实测记录**（全仓检索无 guest→宿主显示链路计时数据）；宿主侧 UI 渲染基线健康（scroll p50 5 ms/0.21% janky，E25），说明瓶颈若存在不在宿主 UI 层。ADR-0012 对 TCG 档的预期管理口径是「软渲染 + 5–20× CPU 减速叠加」（E26）。
- **优化方案**：设备窗口采一组最小基线：guest 侧帧产出速率（合成器统计）、RFB 通道帧间隔分布（宿主侧 log/对账）、输入→显示往返延迟（PointerCaptureHandler 注入时间戳 vs 帧到达）；同轮记录 TCG CPU 占用。数据进 ACR 后再决定 Wayland 适配器（ADR-0017 M3 触发条件「结构性瓶颈」）是否投入。
- **预期收益**：决策质量而非直接性能（**置信度：低**——无数据显示瓶颈在显示链路的哪一段）。
- **成本与风险**：一次设备窗口子集；无代码风险（打点可走 QA 通道）。
- **验证方法**：真机；**依赖与阻塞**：无。
- **明确不做**：本方案不提案 VirGL/gfxstream/GPU 加速——M4 前置未达成（E26），VirGL 真机验收未完成（ACR §1.1 GPU 行）。

### E2 · RFB 帧率上限 `--max-fps 15` 作为旋钮的实测（15 vs 30 对照） 【类型：旋钮已存在（guest 配置侧），低成本快赢】

- **现状证据**：guest RFB 采集率在 phase5 冻结为固定 `--max-fps 15` 命令参数（E24）；此后无帧率/流畅度对照数据。TCG 档下提高采集率的 CPU 代价未测（翻译税环境里 RFB 帧差分计算与 guest 负载争 CPU）。
- **优化方案**：设备窗口一轮两列对照（15/30，同实例同负载），记录 CPU 占用与主观流畅度；若 30 列 CPU 代价 <10% 且流畅度可感提升，把 15 改为按档位配置（终端场景 15、桌面场景 30）。
- **预期收益**：显示流畅度可感提升（30 fps 对 15 fps）；CPU 代价已量化才允许改（**置信度：低**——两侧均未测）。
- **成本与风险**：极小（配置对照）；风险：TCG 档 CPU 争抢可能反噬 guest 编译/交互负载——对照轮须记录。
- **验证方法**：真机两列对照；**依赖与阻塞**：可与 E1 同窗。

---

## 提案总览与优先级

| # | 提案 | 类型 | 置信度 | 成本 | 前置 |
|---|---|---|---|---|---|
| C1 | QEMU 面按 2 vCPU 记账、宽并行不派 QEMU | **零代码决策** | 高 | ~0 | ACR 投票 |
| B1 | 进程内 libclang 为主编译路径 | 接线一格 + 调用方 | 高 | 中 | 无 |
| B3 | worker 池容量 0→N | **旋钮已存在只差开关** | 中 | 小 | B1 裁定 |
| A1 | T3 凭据-only 真机验证常态化 | 管线已建成，缺验证 | 中 | 一次设备窗 | 无 |
| A2 | P1-5 温池修复合入 main | 已开发待合并 | 高 | ~0 | 无 |
| C2 | UML `ncpus=` 宽度旋钮产品化 | 旋钮已存在（内核侧） | 高 | 小 | B2 |
| D1 | 账本 non-reclaimable + R-Q3 语义 | 小改 | 中 | 小 | 无 |
| E2 | `--max-fps` 15/30 对照 | 旋钮已存在（guest 配置） | 低 | 极小 | 无 |
| A3 | provision 超时分档 + db.lck 恢复 | 新开发（小） | 中 | 小-中 | 无 |
| D2 | T5/T6 先测量后决策 | 先测量（可能否决） | 低 | 测量低 | A1 同窗 |
| E1 | 显示端到端基线测量 | 先测量 | 低 | 一次设备窗子集 | 无 |
| B2 | UML 纳入 fabric 编排 worker | 新开发（编排层） | 高（宽度数据）/端到端未取数 | 中-大 | ACR 投票 |
| C3 | 亲和探针 W-FAB-09 | 探针（不承诺） | 低 | 探针级 | 无 |

**「旋钮已存在只差默认值/开关」快赢组**：C1（记账决策）、B3（容量）、E2（max-fps）、C2（ncpus）、A2（合并即生效）；**需新开发**：A3、B1 调用方、B2 编排层、D1；**先测量后决策**：A1、D2、E1。

## 与既有结论的关系

- 不改 ACR 草案正文与四态标注；C1/B2 的数字引用全部为「同轮同夹具」口径，跨夹具处已标禁相除。
- 不推翻 runtime-performance T1–T8 交付语义（T7 旋钮保持 opt-in、默认值不变——本方案 A1/C1 不改 QEMU 默认参数）。
- P1-5/P0-3 修复的引用以 `fix/instance-write-locks` 分支报告为准，注明其尚未在 main。
- 空 catch 分诊抽样见 `docs/reports/quality-backlog-20261104.zh-CN.md` 末尾 Addendum（2026-11-05）；其中建议修复的 5 处与 E 组观测性建议互引。
- 本方案不构成实施承诺；每条提案进入执行前须过对应票立票与（如涉产品默认值）维护者裁定。
