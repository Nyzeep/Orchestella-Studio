# 研究报告：UML 多实例规模化（U6）——N 实例并存的 RSS/CPU/启动/磁盘宿主基线

- 日期：2026-11-11
- 会话：u6-multi-instance（工单 `.scratch/uml-zero-device-20261110/prompts/07-u6-multi-instance.md`）
- 基线：`github/integration/uml-zero-device-20261110` 之上的分支 `feat/uml-u6-multi-instance`（起点 `5ef13035`）
- 性质：零设备宿主参考；不写产品代码、不改 `LaneBudgetLedger`、不改 U1/U2 脚本与 release.yml
- 产物：`tools/research/uml-multi-instance/`（`bench.sh`、`parse.py`、`.gitignore`；`out/` 原始数据不入库）

- 触发票号 / 日期 / 执行者（模型）：`07-u6-multi-instance` / 2026-11-11 / ZCode（GLM）

## 问题清单

1. N=1/2/4/8 个 UML 实例并存时，每实例 RSS/Pss、宿主总 RSS、CPU、T3 就绪时间各是多少？
2. 磁盘账本是什么：rootfs 共享 vs 每实例 guest RAM 文件？
3. 失败模式有哪些？宿主资源在哪一档到不了（停止条件）？
4. 内存/CPU 账本公式与保守上限是什么？与 `LaneBudgetLedger` 语义如何对齐？
5. Android 设备窗口（LMK/FGS/freezer/进程数/端口/存储配额）——全部 `not-tested`，入口命令是什么？

## 逐问题结论（来源见 §8/§9）

1. **N=8 全部就绪**（3 轮 × 24 实例全 READY，0 失败）。每实例主进程 VmRSS 中位 **17.4 MB，不随 N 增长**；因内核镜像页跨实例共享，主进程 **Pss 从 16.7 MB（N=1）降到 11.6 MB（N=8）**。
2. 宿主 VmRSS 总和随 N 线性：**18.5 → 148.3 MB**（边际 ≈ **18.1 MB/实例**）；Pss 总和边际 ≈ **11.1 MB/实例**。stub 进程恒定 **1.1 MB**。
3. guest T3 uptime 基本不随 N 变（中位 **0.18–0.27 s**）；宿主 all-ready **232→293 ms（N=1）→ 441 ms（N=8）**。
4. 磁盘：rootfs 经 hostfs **全实例共享**（一次 310 MB，边际 0）；每实例 guest RAM 文件被 UML `unlink`，`du` 不可见，但经 `/proc/<pid>/fd` 实测 **sparse 256 MB、实际 ext4 块 ≈ 11.1 MB/实例**（上限 = `mem=`）。
5. CPU：稳态 idle ≈ **0**；boot 一次性 ≈ **0.15–0.19 CPU-s/实例**；端口：**0 个 TCP/UDP**（con=null、无网络守护）。
6. 失败模式：本基准实现了一整套失败分类（early_exit / timeout_no_ready / panic / um_init_error / host_mem_skip），本轮**零失败**；SIGTERM 干净停止 rc=1。宿主 7.8 GB 内存跑到 N=8 未触发 800 MB floor。
7. 设备项**全部 `not-tested`**；host 数据只作账本输入，不外推为 Android 结论。

## 0. 结论

1. **多实例的边际成本由「内核进程 RSS + guest RAM 触碰页」主导，且 idle 量级很小**：x86_64 宿主上每实例 VmRSS 边际 ≈ 18.1 MB（PSS 口径 ≈ 11.1 MB），N=8（≈145 MB 总 VmRSS、≈95 MB 总 PSS）在 7.8 GB 宿主上毫无压力。**但这只是 idle shell**；guest RAM 按 `mem=` 触顶时每实例可增长到 ~256 MB（见 §3 账本的保守档）。
2. **启动几乎不随 N 退化**：guest 内 T3 uptime 0.18→0.27 s；宿主并发拉起到全部就绪 232→441 ms。多实例上限不会先被启动时间卡住。
3. **磁盘的「每实例 guest RAM 文件」项必须用 `/proc/<pid>/fd` 计量**：UML 建 RAM 文件后立即 unlink，`du` 恒为 0；打开的 fd 维持 inode，实际 ext4 块 ~11.1 MB（idle），脏页在宿主内存压力下会写回该（已删除但打开的）文件——这也是**实例运行期间宿主 MemAvailable 几乎不降**的原因（guest RAM 页是可回收的 file-backed 页）。
4. **与 `LaneBudgetLedger` 的对齐见 §3**：每实例 `ramMiB` 预留取保守档（`mem=` + 内核开销），`diskGiB` 取 guest RAM 文件上限 + rootfs 策略份额，`cpuCores` 沿用 R-Q3 的「并发预算」语义——UML lane 的 CPU 并发预算不会被 idle 实例耗尽，先到的是 RAM/磁盘。
5. **host ≠ Android**：本表是 WSL x86_64（6.18 内核，SECCOMP 模式）参考，不能推出设备上限；设备窗口全部 `not-tested`（§5）。

## 1. 方法与夹具

### 1.1 通道选择：noinitrd 内核 + hostfs 共享 rootfs（二选一的理由）

任务给的二选一：内置 initramfs 内核 vs `linux-fast-noinitrd` + hostfs rootfs。本基准选**后者**：

1. **账本语义需要**：多实例上限公式里有一项「rootfs 共享摊销」。hostfs 让所有实例直接挂同一份解包目录，共享摊销可实测（边际 0）；内置 initramfs 会把 rootfs 复制进每个内核镜像（`linux-fast` 9.0 MB vs `linux-fast-noinitrd` 8.4 MB），摊销语义失真。
2. **产品路线**：U2 预烘焙 rootfs 是产品 fast-shell 路线；Wave 1 跨测已验证该组合到 READY（`docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md` §4）。
3. **可控驻留**：`orchestella.ready=shell` 让 ready.sh `exec /bin/sh -l` 读 stdin；bench 持有 FIFO 写端使实例存活，稳态采样窗口结束后 SIGTERM 干净停止（与 `research/uml-runner`、`bench-fast.py` 相同的停止方式）。

### 1.2 启动命令（U2 Wave 1 跨测形态）

```
<kernel> mem=256M rw con=null con0=null,fd:1 seccomp=auto panic=1 printk.time=1 \
  rootfstype=hostfs rootflags=<解包 rootfs 目录> \
  init=/init-zero-install orchestella.ready=shell
```

- `seccomp=auto`：本宿主内核 6.18 支持 seccomp，实测落 **SECCOMP 模式**（console `Userspace mode: SECCOMP`）；`seccomp=on` 在部分宿主会 rc=1，`auto` 由 UML 探测。
- 每实例独立 `TMPDIR`（guest RAM 文件落盘目录，需 exec-capable；本宿主 `/root` ext4 OK）。
- 每实例 stdin 为 bench 持有的 FIFO（读写打开，避免写端 open 阻塞），stdout 为 console 日志（T2/T3 marker 来源）。
- `um.hold_ms` 不适用（那是 U1 initramfs 探针参数；U2 ready.sh 只认 `orchestella.ready`）。

### 1.3 夹具与指纹

| 夹具 | 值 |
|---|---|
| 内核 | `linux-fast-noinitrd`（U1 x86_64-up），8,417,560 B，sha256 `81c85621f997cadbde990567f3b729154ee23b91d57da7fd8db30d623cd69520` |
| 内核 config | `kernel-noinitrd.config`，sha256 `57b50840acddc89575bb2b8a58e045bb77286859687c3840f756560464152245` |
| 内核版本 | Linux 7.2.0-rc4（`Raymer8639/linux-um-arm64` pin，U1 构建） |
| rootfs tar | `alpine-3.22.8-x86_64.tar.gz`，114,590,691 B，sha256 `90ac674f6148ed4b99f7e832c426826e7129ae286312e73bec54fd8baeb372c4` |
| rootfs 解包目录（hostfs 共享） | 325,161,436 B（du -sb） |
| rootfs ext4 备选（每实例镜像，本测未用） | 456,130,560 B |
| UML userspace 模式 | SECCOMP |
| 宿主 | WSL2 Ubuntu 24.04，内核 `6.18.33.2-microsoft-standard-WSL2`，20 逻辑 CPU，MemTotal 7,985,836 kB |
| 页参数 | 4 KB 页，`CLK_TCK=100` |
| guest 参数 | `mem=256M`，UP（未传 `ncpus`） |
| 轮次 | N∈{1,2,4,8} × n=3 轮；稳态窗口 10 s；采样 200 ms/tick；marker 轮询 10 ms；每轮前 1 个不入数据实例预热页缓存 |
| 证据 | `out/u6-scaling-20261111/`（manifest.json + instances.csv + samples.csv + console 日志 + ports 快照；gitignored，本机留存） |

### 1.4 度量口径

| 指标 | 采集 |
|---|---|
| 每实例 RSS | `/proc/<mainpid>/status` 的 `VmRSS`（主进程；stub 进程单列），稳态窗口内中位数 |
| 每实例 PSS | `/proc/<mainpid>/smaps_rollup` 的 `Pss`（含共享页分摊，推荐预算口径） |
| 宿主总 RSS | 每 tick 全实例 `rss_main + rss_other` 求和 → 每轮 tick 中位 → 跨轮中位 |
| CPU | `/proc/<pid>/task/*/stat` 的 `utime+stime` 求和（**含主进程 3 线程 + stub**；顶层 `/proc` 在本宿主不暴露线程目录，pgid 扫描会漏线程 CPU，已修正）；boot 相位与稳态相位分开 |
| T3 就绪 | console `T3_READY uptime=<s>` marker；宿主墙钟 latency 从 fork 到 marker 读到（10 ms 轮询粒度） |
| 磁盘 | rootfs：`du -sb` 解包目录（一次）；guest RAM 文件：`/proc/<pid>/fd` 上 deleted 文件 `stat -L` 的 size（sparse 上限）与 blocks×512（实际分配）；`du` 恒 0（unlink），不再作为证据 |
| 失败模式 | early_exit（ready 前进程死亡）/ timeout_no_ready / Kernel panic / UM_INIT_ERROR / host_mem_skip（MemAvailable < 800 MB 跳轮）/ fifo_open_failed |
| 停止 | SIGTERM 主进程 → 2 s 内不退则 SIGKILL → 组内残留（stub/线程）逐 pid SIGKILL；本宿主 SIGTERM 停止 rc=1（UML 接 SIGTERM 退出码，非 0，非 137） |
| 端口 | 每轮 `ss -tunp` 过滤本组 pid：**全部 12 轮 0 条**；mconsole socket 为 unix socket（`/root/.uml/<umid>/mconsole`，非 TCP），退出后无残留 |

## 2. 数据表（x86_64 宿主参考，N=1/2/4/8 × n=3）

### 2.1 主表（中位数；N=1/2/4/8 各 3 轮、12 轮全 READY、0 失败）

| N | 实例数 | ready_ok | 失败 | 每实例 VmRSS 主进程 (kB) | 每实例 Pss 主进程 (kB) | stub VmRSS (kB) | 宿主总 VmRSS (kB) | 宿主总 Pss (kB) | ready_host_ms 中位 | all_ready_ms 中位 | 稳态 CPU (s) | guest RAM 文件实际块 (B) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 3 | 3 | 0 | 17448 | 16712 | 1084 | 18532 | 17256 | 293 | 293 | 0.01 | 11,141,120 |
| 2 | 6 | 6 | 0 | 17446 | 13770 | 1084 | 37052 | 28620 | 229 | 238 | 0.00 | 11,141,120 |
| 4 | 12 | 12 | 0 | 17446 | 12301 | 1084 | 74120 | 51376 | 273 | 324 | 0.00 | 11,143,168 |
| 8 | 24 | 24 | 0 | 17450 | 11569 | 1084 | 148308 | 96928 | 348 | 441 | 0.00 | 11,151,360 |

（每实例 VmRSS/Pss 为各轮各实例稳态中位数的总中位；宿主总 RSS/PSS 为每 tick 求和→每轮中位→跨轮中位；guest RAM 文件块为实例级中位。`ram_file` 的 sparse 上限恒为 268,437,824 B = `mem=` 256 MB + memfd stub 2,368 B。）

### 2.2 轮次明细

| N | 轮 | 实例 | ready | all_ready_ms | 宿主总 VmRSS 中位 (kB) |
|---|---|---|---|---|---|
| 1 | 1 | 1 | 1 | 306 | 18540 |
| 1 | 2 | 1 | 1 | 293 | 18528 |
| 1 | 3 | 1 | 1 | 251 | 18532 |
| 2 | 1 | 2 | 2 | 227 | 37044 |
| 2 | 2 | 2 | 2 | 290 | 37052 |
| 2 | 3 | 2 | 2 | 238 | 37084 |
| 4 | 1 | 4 | 4 | 348 | 74120 |
| 4 | 2 | 4 | 4 | 310 | 74112 |
| 4 | 3 | 4 | 4 | 324 | 74124 |
| 8 | 1 | 8 | 8 | 385 | 148336 |
| 8 | 2 | 8 | 8 | 441 | 148308 |
| 8 | 3 | 8 | 8 | 468 | 148224 |

### 2.3 派生量（账本输入）

| 量 | 值 | 说明 |
|---|---|---|
| 每实例 VmRSS 边际成本 | (148308−18532)/7 ≈ **18.1 MB** | 主进程 17.4 MB + stub 1.1 MB；不随 N 变 |
| 每实例 Pss 边际成本 | (96928−17256)/7 ≈ **11.1 MB** | 共享内核镜像页已按实例分摊 |
| boot CPU | **0.15–0.19 CPU-s/实例**（中位 15–19 ticks/CLK_TCK=100） | 一次性，集中在 ~0.2–0.5 s 启动窗 |
| 稳态 CPU | ≈ **0**（idle shell，10 s 窗口 0–1 ticks） | 多实例并存本身几乎不耗 CPU |
| guest T3 uptime | 0.18 → 0.27 s（N=1→8） | guest 内启动不随 N 明显退化 |
| 宿主 MemAvailable 变化 | 每轮 −Δ≈0 甚至为正（±70 MB 噪声内） | guest RAM 页 file-backed 可回收，见 §6 风险 R3 |
| 每实例任务数 | **4**（主进程 3 线程 + `uml-userspace` stub 进程） | 设备 pids cgroup 预算输入 |
| 宿主 TCP/UDP 端口 | **0**（12 轮 ss 快照） | con=null、无网络守护配置 |

## 3. 内存/CPU/磁盘账本公式与保守上限

### 3.1 每实例成本（实测口径）

```
per_instance_rss_idle   = VmRSS_main(17.4 MB) + VmRSS_stub(1.1 MB) ≈ 18.5 MB   [VmRSS 口径]
per_instance_pss_idle   = 11.6 MB (N=8) … 16.7 MB (N=1)                        [PSS 口径，推荐预算口径]
per_instance_cpu_boot   ≈ 0.15–0.19 CPU-s（一次性）
per_instance_cpu_steady ≈ 0（idle）
per_instance_disk_live  ≈ 11.1 MB（guest RAM 文件实际 ext4 块，idle；上限 = mem=）
rootfs_shared_once      = 310 MB（hostfs 目录，全实例共享，边际 0）
rootfs_per_instance_alt = 435 MB（ext4 镜像策略，每实例一份，本测未采用）
```

### 3.2 保守上限公式（零设备输入）

```
N_max = min(
    floor( B_ram   / ( MEM_GUEST + C_KERNEL ) ),        # RAM 保守档：guest 触顶
    floor( B_ram'  / PSS_IDLE ),                        # RAM idle 参考档（仅指示性）
    B_cpu,                                              # CPU 并发预算（见下）
    floor( B_disk  / ( MEM_GUEST + ROOTFS_SHARE ) ),    # 磁盘保守档
)

其中：
  MEM_GUEST      = mem= 参数（本测 256 MB；guest RAM 按触碰增长，上限即 mem=）
  C_KERNEL       ≈ 25 MB（内核镜像 + stub + 页表/host 栈触顶余量；
                   本测 VmRSS_main 17.4 MB + stub 1.1 MB 上取整）
  PSS_IDLE       ≈ 12–17 MB（N 越大共享摊销越充分）
  ROOTFS_SHARE   = 0（hostfs 共享）或 435 MB（每实例 ext4）
  B_ram/B_ram'   = 产品给 UML lane 的 RAM 预算；B_ram' 仅在「实例保持 idle」假设下可作参考
  B_cpu          = UML lane 的并发预算（LaneBudgetLedger.cpuCores，R-Q3 语义：
                   并发预算、不是物理核数）；idle 实例稳态 CPU≈0，
                   boot 突发 N×0.19 CPU-s 落在 ~0.5 s 内
  B_disk         = 产品磁盘配额（app data / per-app quota）
```

**数字示例（仅演示公式，不是设备结论）**：`mem=256M`、`B_ram=2 GB`、`B_disk=4 GB`、hostfs 共享 →
RAM 保守档 floor(2048/281) = **7**；磁盘档 floor(4096/268) = **15** → N_max ≈ 7（CPU 不先到）。
同参数下若实例保证 idle，PSS 档指示值会大一个数量级——**不可作为承诺**。

### 3.3 与 LaneBudgetLedger 的语义对齐（只读）

对齐对象：`runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/duallane/LaneBudgetLedger.java`（未改）。

| LaneBudgetLedger 语义 | 本基准的对齐 |
|---|---|
| `reserve(lane, cpuCores, ramMiB, diskGiB)` 三项同时预留，任一超限分别抛 `CPU/RAM/DISK_EXCEEDED` | 本报告 §3.2 的三条上限分量正好对应三个 reject code；UML 实例请求应按 `ramMiB = MEM_GUEST + C_KERNEL`（保守档）、`diskGiB = MEM_GUEST + ROOTFS_SHARE`、`cpuCores = lane 并发预算` 报价 |
| `cpuCores` 是 **lane 并发预算**，不是物理核数（R-Q3）；两 VMM face 并存时总预留可合法超过物理核数（R-Q7） | 宿主实测 idle 实例稳态 CPU≈0：UML lane 的并发预算不会先于 RAM/磁盘耗尽；boot 突发 ≈ 0.19 CPU-s/实例（<0.5 s），对并发预算的占用是短时的 |
| 预留只在 **stop confirmed** 时释放；UNCONFIRMED stop 保留租约（「do not delete the lease」） | UML 实例的 RSS/磁盘块随主进程退出才回收（进程态资源）；对应「confirmed stop = 进程已退出且被 reap」。父进程未 reap 的僵尸实例应按 UNCONFIRMED 处理，继续占用租约 |
| `release()` 后 `used*` 只减不增、下限 0 | 实例退出后 RSS/磁盘块立即归零（本轮 SIGTERM 停止后 `/root/.uml` 无残留、端口 0）；hostfs rootfs 目录与页缓存属宿主共享，不在实例租约内 |

## 4. 失败模式与停止条件

- **本轮 12/12 轮、24/24 实例全 READY，0 失败**（另含 1 个不入数据的预热实例，同样 READY）。宿主 7.8 GB 内存下 N=8 未触发 800 MB floor，停止条件未启动。
- bench.sh 实现并会如实记录的失败分类：`early_exit`（含 rc）、`timeout_no_ready`（60 s 无 marker，实例保留进入稳态采样）、`panic`（console `Kernel panic`）、`um_init_error`（console `UM_INIT_ERROR`）、`host_mem_skip`（MemAvailable < floor，整轮跳过并计 skipped）、`fifo_open_failed`、`console_missing`。
- SIGTERM 停止 rc=1（每实例一致）：这是本内核对宿主 SIGTERM 的退出码，与 U1 `bench-fast.py`「SIGTERM 干净停止」口径一致；teardown 后无 stub/mconsole/端口残留。
- 曾在早一版数据（`u6-scaling-20261111` 之前的试跑）观察到一次 N=1 单轮 all-ready 2,590 ms 的宿主侧毛刺（同轮 guest T3 uptime 正常 0.17 s，为宿主调度/IO 抖动）；本轮 12 轮 all-ready 227–468 ms 无复现。中位数口径对这类毛刺稳健。

## 5. 设备窗口清单（全部 not-tested）

宿主数据不外推。以下每一项在真机上验证前，状态一律 `not-tested`；命令为入口，不是结论。

| # | 窗口项 | 入口命令 / 检查点 | 要回答的问题 | 状态 |
|---|---|---|---|---|
| 1 | lmkd / LMK 内存压力 | `adb shell dumpsys meminfo`；`adb shell getprop \ initializer: grep ro.lmk.`；压力复现：`device_config put activity_manager max_cached_processes <n>` 或后台压测 | UML 主进程（VmRSS≈18 MB 起、可涨至 `mem=`）在 lmkd 杀序中的位置；被杀后 `LaneBudgetLedger` 租约是否按 confirmed stop 释放 | **not-tested** |
| 2 | FGS 驻留（Android 12+ 6 h 门、电池） | `adb shell dumpsys activity services <pkg>`；`adb shell cmd battery unplug` + `dumpsys deviceidle` | 常驻实例用哪种 FGS 类型（dataSync/specialUse）、6 h / Doze 是否打断 guest | **not-tested** |
| 3 | cached apps freezer | `adb shell device_config get activity_manager_native_boot cached_apps_freezer_enabled`；`adb shell dumpsys activity processes \| grep -i frozen` | 冻结对 guest 时钟（jiffies 停走）、console FIFO、mconsole 的影响；解冻后的 T3/时钟跳变 | **not-tested** |
| 4 | 进程/线程数配额 | `adb shell ulimit -u`（app 域）；`adb shell cat <cgroup>/pids.max` | 4 任务/实例 × N 是否撞 app 域 pids 上限 | **not-tested** |
| 5 | 端口冲突 | `adb shell ss -tunp`（实例运行时） | 本配置实测 0 端口；产品网络守护/转发通道（slirp/vsock 等）的端口占用与冲突 | **not-tested**（守护通道本身不在本 profile） |
| 6 | 存储配额 | `adb shell dumpsys diskstats`；`adb shell sm` / `cmd storage`；app data quota | guest RAM 文件向 `mem=` 增长（本测 sparse 256 MB）时的 per-app quota / FUSE 行为；rootfs 每实例 ext4（435 MB）策略的配额压力 | **not-tested** |
| 7 | cgroup2 可用性（实例内） | 实例内 `cat /proc/mounts`（init-zero-install 对 cgroup2 是 mount_optional，缺失只记 `INIT_MOUNT_SKIPPED`） | app 域能否挂 cgroup2；影响 systemd/Docker profile，不影响 fast shell | **not-tested** |
| 8 | ARM64 + 16 KB 页宿主 | U1 产物 `libuml_kernel.so`（16 KB 对齐）+ `./gradlew assembleSdk36Debug`（`research/uml-fastboot/README.md` §4） | ARM64 boot、16 KB 宿主 exec、app 域 SECCOMP 真实模式（ptrace vs filter） | **not-tested** |

## 6. 风险与限制

- **R1 · host ≠ Android**：WSL x86_64（6.18，SECCOMP filter 模式，4 KB 页）≠ ARM64 设备（16 KB 页、app 域 SELinux/seccomp、厂商 lmkd 参数）。表内每个数字只作账本输入。
- **R2 · idle ≠ 负载**：本测实例是 boot + idle shell。真实工作负载（编译/网络/包管理）下 RSS 单调走向 `mem=`、磁盘块同步增长、CPU 不再为 0；上限必须按 §3.2 保守档报价。
- **R3 · MemAvailable 对 UML 不敏感**：guest RAM 页是（已 unlink 但打开的）ext4 文件页缓存，宿主可回收、可写回；实例运行期间宿主 `MemAvailable` 几乎不降（本轮甚至为正波动）。这意味着**任何以 MemAvailable/avail 页为准的设备侧判断（lmkd、kill 序）在 UML 场景的表现与普通进程不同**，必须在设备上单独验证（§5-1）。副作用：宿主回收 guest 页会把压力转移成 guest 内核的回收（guest 内存压力），而不是宿主 OOM。
- **R4 · 计量口径陷阱**：顶层 `/proc` 不暴露线程目录 → 按 pgid/顶层 stat 统计会漏掉主进程 2 条辅助线程的 CPU（bench 已用 `/proc/<pid>/task/*` 修正）；`du` 对 guest RAM 文件恒 0（unlink）→ 磁盘证据必须走 `/proc/<pid>/fd` stat（bench 已内置）。
- **R5 · 时钟粒度**：T3 marker 轮询 10 ms；稳态采样 200 ms。启动时序结论与 U1 `bench-fast.py`（逐行 monotonic 戳）相比粒度更粗，本报告只把 T3 当辅助列。
- **R6 · 单宿主、共享负载**：无温度/独占控制；n=3 中位数对短毛刺稳健，但非受控实验室条件；N=1 档历史试跑出现过一次 2.6 s 宿主侧毛刺（§4）。
- **R7 · hostfs 并发写未测**：本轮全实例共享 rootfs 目录但只读使用（每实例 TMPDIR 隔离、无网络、无包管理）；并发写同一 hostfs 目录的行为未测，产品若允许实例写入需另测。
- **R8 · 单 CPU（UP）内核**：`mem=`×N 只是内存叠加；多 vCPU 实例（`ncpus>1`）在设备上的并发行为未测（U1 宿主 ptrace 曾把 SMP 夹到 1 CPU）。

## 7. not-tested 汇总

ARM64 设备 boot；16 KB 页宿主；app 域 SECCOMP 真实模式；lmkd 杀序与租约回收；FGS 6 h/Doze；cached apps freezer；进程/线程数配额；端口冲突（产品网络守护）；存储配额与 guest RAM 文件触顶；cgroup2 挂载；hostfs 并发写；SMP 多 vCPU 实例；真实工作负载下的 RSS/CPU/磁盘增长；快照/恢复（U4 范围）。**全部不外推。**

## 8. 依赖与复现

依赖：U1 `research/uml-fastboot/out/x86_64-up/artifacts/linux-fast-noinitrd`（+ `kernel-noinitrd.config`）；U2 `research/uml-rootfs-prebake/out/alpine-x64/alpine-3.22.8-x86_64.tar.gz`；Wave 1 跨测的启动命令形态。

```sh
# WSL 内（worktree 根，示例 /workspace-wt/u6）
bash tools/research/uml-multi-instance/bench.sh \
  --ns 1,2,4,8 --rounds 3 --hold-s 10 --tag <tag>
# 汇总（Windows 或 WSL python 均可）
python tools/research/uml-multi-instance/parse.py \
  tools/research/uml-multi-instance/out/<tag>
# 自检
python tools/research/uml-multi-instance/parse.py --self-test
```

默认夹具路径可用 `--kernel/--rootfs-tar/--rootfs-dir/--work/--out` 覆盖（`--help` 见脚本头注释）。`out/` 已 gitignore。

## 9. 证据与产物

| 产物 | 路径 |
|---|---|
| bench 脚本 | `tools/research/uml-multi-instance/bench.sh` |
| 解析/汇总（含 `--self-test`，21 项断言） | `tools/research/uml-multi-instance/parse.py` |
| 原始证据（本机留存，gitignored） | `tools/research/uml-multi-instance/out/u6-scaling-20261111/`（manifest.json、instances.csv、samples.csv、console 日志、ports 快照） |
| 上一次全量跑（脚本修 `tasks_end` 前的另一组独立数据，结论一致） | `tools/research/uml-multi-instance/out/u6-scaling-final/`（VM RSS/PSS/ready 与本轮差 <1%，`ram_blocks` 同为 ~11.1 MB） |
| 启动形态出处 | `docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md` §4 |
| 账本对齐对象（只读） | `runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/duallane/LaneBudgetLedger.java` |
| 相关研究 | `docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md`、`docs/research/2026-11-10-uml-rootfs-prebake.zh-CN.md`、`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、`docs/research/2026-11-05-pvm-fabric-route.md` §2.2 |
