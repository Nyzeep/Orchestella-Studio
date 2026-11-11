# UML / QEMU / proot 启动时间线基准协议（U0）

- 协议 ID：`uml-boot-bench/1`
- 状态：U0 交付；解析器 `parse_boot_log.py` 与本文同版本
- 适用：U1 fast-boot profile、U2 rootfs 预烘焙、U3 seccomp/host 兼容性、ACR 证据包
- 不适用：产品启动承诺、跨架构外推、未实测的“秒开”结论

**一句话规则**：每个启动数字必须同时带 **就绪点（T0–T5/P0/Q0）**、**lane**、**锚点（宿主 monotonic 或 guest 内核时间）**、**n** 与 **夹具标识**；缺失就给 `unknown`，不猜、不跨锚点相减、不跨 lane/设备相除。

## 1. 就绪点定义

| 点 | 名称 | 锚点与单位 | 采集点 | 适用 lane |
| --- | --- | --- | --- | --- |
| **T0 spawn** | 宿主创建 guest 进程 | 宿主 `CLOCK_MONOTONIC`，绝对毫秒（anchor，不是时长） | `ProcessBuilder.start()` / `exec` 返回 | UML / QEMU / proot |
| **T1 kernel init** | guest 内核开始执行可控 init | guest 内核时间，毫秒（自内核启动） | 带内核 timestamp 的 `Run /<init> as init process` | UML / QEMU |
| **T2 rootfs mounted** | `/` 与 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2 就绪 | guest `/proc/uptime`，毫秒 | 挂载完成后的 `UMARM_BOOT_OK guest_uptime=<s>`（首选）；U2 零安装的 `T2_MOUNTS_READY uptime=<s>`（U0.1 alias，次之）；缺失时退回 `VFS: Mounted root` 内核 timestamp | UML / QEMU |
| **T3 first command** | 第一条用户命令可执行并输出 `READY` | guest `/proc/uptime`，毫秒 | `BENCH boot_uptime_ms=<ms>`（首选）、`UMARM_WORK_START ... uptime=<s>`（fallback）、`BOOTPROBE ... uptime=<s>`（第二 fallback）；U1/U2 alias `U1_READY uptime=<s>` / `T3_READY uptime=<s>` 排在最后；裸 `READY` 不产生数值（见 §4.1 alias 规则） | UML / QEMU |
| **T4 service ready** | systemd `multi-user.target` / 网络 / SSH 就绪 | guest 内核时间，毫秒 | `systemd-analyze` 的 `Startup finished in ... = <total>`；缺失时退回带 timestamp 的 `Reached target multi-user.target` | UML / QEMU |
| **T5 warm resume** | 从 snapshot/预热点恢复到 T3 | 宿主 `CLOCK_MONOTONIC`，毫秒 | `BOOT_TIMING` 的 `ready_point=T5` 配对 | UML（待验证） |
| **P0 proot exec** | proot 进程 exec 到 shell 并输出 `READY` | 宿主 `CLOCK_MONOTONIC`，毫秒 | `p0_ms=` 或 `t0_monotonic_ms`/`ready_monotonic_ms` 配对 | proot |
| **Q0 QEMU boot** | QEMU 进程 spawn 到 T4 | 宿主 `CLOCK_MONOTONIC`，毫秒 | `q0_ms=` 或 `t0_monotonic_ms`/`ready_monotonic_ms` 配对（`ready_point=Q0` 或 `T4`） | QEMU |
| **duration_ms** | 宿主 spawn 到进程退出（含配置加载、workload、退出） | 宿主 `CLOCK_MONOTONIC`，毫秒 | `uml-exit.txt` 的 `duration_ms=`；控制台 `kernel exited ... duration_ms=` 仅作 fallback | UML / QEMU / proot |

补充：

- **T0 是参考点，不是时长**。它不参与中位数；只用于与同一 `CLOCK_MONOTONIC` 上的 READY 时间配对，得到 `t3_from_spawn_ms` 等跨段指标。
- **T1–T4 是 guest 内核相对时间**。它们不能与宿主 `duration_ms` 相减；`duration_ms` 包含 spawn、内核 boot、init、命令执行和退出，不是 T3。
- **T5 只在有 warm-resume 协议时定义**。普通冷启动没有 T5。
- **proot 没有 T1–T4**：它没有 guest 内核、rootfs 挂载或 systemd 阶段；它的就绪点是 P0，不是 T3。
- **guest 计时精度**：`/proc/uptime` 首字段是 `CLOCK_BOOTTIME`（含 suspend），按 `%lu.%02lu` **截断到 10 ms**（不是四舍五入）；UML 的 `sched_clock` 由 jiffies 派生，pinned 内核 `HZ=100`，同样是 10 ms 地板。不要报亚 10 ms 差异，也不要把 guest uptime 与宿主 monotonic 当同一时钟。

## 2. 锚点纪律（禁止跨锚点/跨 lane 相除）

1. **宿主锚点**：`CLOCK_MONOTONIC`，单位毫秒；T0、T5、P0、Q0、`duration_ms`、`t3_from_spawn_ms` 用它。
2. **guest 锚点**：guest 内核时间（`/proc/uptime` 第一字段或内核 printk timestamp），单位毫秒；T1–T4 用它。
3. **禁止**把 guest 的 T3 与宿主 `duration_ms` 相减、相除或互相“校正”。只有显式记录同一宿主 clock 的 T0 与 READY 配对时，才输出 `t3_from_spawn_ms`。
4. **禁止**把 T3 与 T4 相减后称为“systemd 启动时间”；T4 是绝对就绪点，不是增量。
5. **禁止**跨 lane、跨设备、跨夹具做倍数结论。同一夹具、同一轮、同 lane 的数字才可以比较；方向性参考必须标注 `not-tested`。
6. **禁止**把 x86_64 宿主参考数据外推为 ARM64 设备结论。
7. **同 run 一致性**：T1–T4 或 `t3_from_spawn_ms` 大于 `duration_ms` 时，解析器给 warning 但不改数值；这通常意味着输入来自不同 run，必须拆开重测。

## 3. 采样、统计与控温

- **每列至少 n=5**；报告必须同时给 n 与中位数，不能只给最小值/最大值。
- **10 ms 地板**：guest `/proc/uptime` 与 UML `sched_clock`（pinned `HZ=100`）都只有 10 ms 量级；任何小于 10 ms 的差异不构成结论。
- **中位数定义**：`statistics.median`；奇数取中间值，偶数取中间两值均值；`unknown` 不参与。
- **交错轮转**：不同 profile/列按轮次交错执行（例如 Latin 式序换），避免热漂移只落在某一列。
- **控温**：每轮前后记录温度与可用内存；记录 host 负载、屏幕状态、充电状态、是否持 FGS/wake lock。设备轮报告至少写清这些字段是否存在，缺项标 `not-tested`。
- **夹具字段**：lane、内核版本、defconfig、rootfs sha256、guest/host 页大小、`ncpus`、`mem`、stub 版本、宿主负载。解析器会从日志回填能识别的字段；其余由 `BOOT_TIMING` 元数据提供。
- **禁止只报一个“启动时间”**：fast shell（T3）与 full systemd（T4）必须分列；QEMU 的 Q0 与 UML 的 T3/T4 不是同一就绪点。

## 4. 日志格式与字段

### 4.1 UML console（`uml-console.log`）

按优先级识别：

| 字段 | 格式 | 单位 | 映射 |
| --- | --- | --- | --- |
| `BENCH boot_uptime_ms=<int>` | `research/uml-runner/guest/um-command.sh` | ms | T3（首选） |
| `UMARM_WORK_START ... uptime=<float>` | `research/uml-fabric-probe/guest-overlay-uml/init` | s | T3（fallback） |
| `BOOTPROBE ... uptime=<float>` | `research/uml-fabric-probe/guest-overlay-uml/work/bootprobe.sh` | s | T3（第二 fallback，11-07 bootprobe 格式） |
| `U1_READY uptime=<float>` | U1 fast shell `research/uml-fastboot/initramfs/um-command.sh` | s | T3（U0.1 alias，低于以上全部 canonical marker） |
| `T3_READY uptime=<float> action=<shell\|poweroff>` | U2 `research/uml-rootfs-prebake/ready.sh` | s | T3（U0.1 alias，低于 `U1_READY`） |
| `UMARM_BOOT_OK guest_uptime=<float> nproc=<N> page=<P>` | 两个 guest overlay 的 init | s | T2；`nproc`/`page` 进夹具 |
| `UMARM_BOOT_OK`（无 `guest_uptime`） | 旧日志 | — | T2 缺失；不猜 |
| `T2_MOUNTS_READY uptime=<float>` | U2 `research/uml-rootfs-prebake/init-zero-install` | s | T2（U0.1 alias；优先于 `VFS: Mounted root` 内核 timestamp，低于 `UMARM_BOOT_OK guest_uptime=`） |
| `INIT_ZERO_INSTALL ...` | U2 `init-zero-install` 前缀行 | — | 不单独映射；仅作为裸 `READY` 的识别门 |
| `READY`（裸行） | U2 `ready.sh` | — | 仅当同一 run 出现 `T3_READY` 或 `INIT_ZERO_INSTALL` 前缀时视为零安装 run 的就绪佐证；自身无 uptime，不产生数值，不归任何 lane |
| `[    <s>] Run /<init> as init process` | 内核 printk（需 `printk.time=1`） | s | T1 |
| `[    <s>] VFS: Mounted root ...` / `EXT4-fs (...) mounted filesystem` | 内核 printk | s | T2 fallback |
| `[    <s>] Reached target multi-user.target` | systemd | s | T4 fallback |
| `Startup finished in ... = <total>` | `systemd-analyze` | s/ms/min/us | T4（优先于 marker） |
| `kernel exited code=<n> reason=<s> duration_ms=<n>` | runner 控制台镜像 | ms | `duration_ms` fallback |
| `BOOT_TIMELINE T<n> uptime_s=<s>` | 本协议规范 marker | s | T1–T4（规范优先） |
| `BOOT_TIMELINE T5 elapsed_ms=<ms>` | 本协议规范 marker | ms | T5 |

> 内核 printk timestamp 需要 `printk.time=1`（或 sysfs `/sys/module/printk/parameters/time=1`）。仓库 pinned UML 内核的 `CONFIG_PRINTK_TIME` 未开；没有 timestamp 时 T1/T2 只能 `unknown`。`initcall_debug` 的 `returned ... usecs` 是单个 initcall 的耗时，不是 init 开始时刻，解析器不用它推 T1。

**U0.1 alias 规则（票 05，跨会话标记）**：

- alias 的 uptime 单位是 guest `/proc/uptime` 秒（与 `UMARM_*` marker 相同，10 ms 截断粒度）；值非法/带单位后缀（如 `uptime=0.09ms`）给 warning 且不猜值。
- 优先级低于全部 canonical marker：T3 为 `BENCH` > `UMARM_WORK_START` > `BOOTPROBE` > `U1_READY` > `T3_READY`；T2 为 `UMARM_BOOT_OK guest_uptime=` > `T2_MOUNTS_READY` > 内核 timestamp fallback。canonical marker 在场时 alias 不生效。
- 裸 `READY` 永远不会产生 T3 数值（它没有 uptime）：同一 run 出现 `T3_READY` 或 `INIT_ZERO_INSTALL` 前缀时，它只是零安装 run 的就绪佐证（T3 仍取自带 uptime 的 marker；若全部缺失，missing 提示指向 `T3_READY`/`U1_READY` alias）；没有该上下文的 `READY`（例如其他工具的 shell READY）不归任何 lane，不产生 T3。
- U1 的 `U1_T2_READY uptime=` 目前**不在**协议内（已知缺口）：U1 fast-shell 日志的 T2 仍走 `VFS: Mounted root` 内核 timestamp fallback。

### 4.2 `uml-exit.txt`

解析 `exit_code=`、`signal_guess=`、`reason=`、`duration_ms=`。`kernel=`、`stub_exe=`、`rootfs=`、`args=` 含本机路径/argv，**解析器不输出**（隐私默认值）；需要 provenance 时在 `BOOT_TIMING` 中写 `rootfs_sha256=` 等脱敏字段。

### 4.3 QEMU serial

`-serial file:<log>` 本身不带时间戳；时间戳来自 guest 内核的 printk（`printk.time=1` 或默认 `CONFIG_PRINTK_TIME`）。解析字段与 UML console 相同；T1 通常是 `Run /init as init process`，T2 是 guest init 的 `UMARM_BOOT_OK guest_uptime=...`，T3 是 `UMARM_WORK_START ... uptime=...`，T4 是 `systemd-analyze` 或 `multi-user.target` marker。

**Q0 不能只靠 serial 得到**：必须另有宿主 `t0_monotonic_ms` → `ready_monotonic_ms`（`ready_point=Q0` 或 `T4`）配对，否则输出 `unknown`。

### 4.4 `dmesg`

接受默认 `[    0.000000] ...` 与 syslog 前缀 `<6>[    0.000000] ...`；用于补 T1/T2 的内核 timestamp。`dmesg -t` 无 timestamp 时 T1/T2 仍为 `unknown`。`initcall_debug` 的 `initcall ... returned 0 after N usecs` 目前不作为 T1 依据（T1 以 init-start marker 为准），避免把“某个 initcall 完成”误当成 init 开始。

### 4.5 `systemd-analyze`

解析 `Startup finished in ...` 的完整等式：

```
Startup finished in 1.234s (firmware) + 0.567s (loader) + 2.345s (kernel) + 0.890s (initrd) + 3.456s (userspace) = 8.492s
```

- 总时长取 `= <total>`；T4 = total。
- 组件（firmware/loader/kernel/initrd/userspace）只作明细，不单独当 T4。
- 单位支持 `us`、`ms`、`s`、`min`（含 `1min 2.500s` 形式）。
- 行被截断、没有 `= <total>` 时 T4 为 `unknown`，并给出 warning；不把组件相加冒充 total。
- `graphical.target reached after <X> in userspace` 只记 userspace 明细，不是 T4。

### 4.6 proot 计时日志（本协议定义）

proot 没有统一的外部计时格式。采集脚本按下述最小格式写一行（推荐 `BOOT_TIMING v1`）：

```
BOOT_TIMING v1 lane=proot
label=proot-alpine
t0_monotonic_ms=100000.000
ready_monotonic_ms=100050.101
ready_point=P0
rc=0
```

或直接给已算好的 P0：

```
BOOT_TIMING v1 lane=proot
label=proot-alpine
p0_ms=50.101
rc=0
```

规则：

- 仓库已有 `NL_T0=`/`NL_T1=`（`tools/native-lane/measure.py`）是**批处理墙钟**（`T0` 在批量编译前、`T1` 在批量结束后），不是 proot exec→shell READY 的 P0；不要把它填进 P0。P0 只接受本节的 `BOOT_TIMING` 字段。
- `t0_monotonic_ms` 与 `ready_monotonic_ms` 必须来自同一宿主 `CLOCK_MONOTONIC`。
- `ready_point` 缺省时：proot=P0、qemu=Q0、uml=T3。
- `ready < t0`、非法单位、缺一半配对 → 对应指标 `unknown` + warning；不做绝对值或猜测。
- 可选元数据：`kernel_version=`、`rootfs_sha256=`、`page_size=`、`ncpus=`、`mem_mb=`、`defconfig=`、`host_load=`。

## 5. 解析器用法

```bash
# 自测：唯一 OK 串
python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test
# -> UML_BOOT_BENCH_SELF_TEST_OK checks=157

# UML 单 run（console + exit）
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane uml \
  --console path/to/uml-console.log \
  --exit-file path/to/uml-exit.txt

# UML 多 run：同名参数按顺序配对；每个 run 一份
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane uml \
  --console run1.log --console run2.log --console run3.log \
  --exit-file run1.txt --exit-file run2.txt --exit-file run3.txt \
  --label run1 --label run2 --label run3 \
  --format json

# QEMU serial + 宿主配对（Q0）
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane qemu --console qemu-serial.log --timing qemu-timing.log

# proot
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane proot --timing proot-timing.log

# 把独立 dmesg / systemd-analyze 文本并入
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane qemu --console qemu-serial.log --dmesg dmesg.txt \
  --systemd-analyze systemd-analyze.txt
```

- `--format text`（默认）输出 Markdown 表；`--format json` 输出机器可读 JSON。
- 多 run 时，除 `--label` 外的文件参数必须是 0 个、1 个（仅单 run）或每个 run 一个；数量不匹配直接报错，不猜测配对。
- 退出码：0=解析完成（允许字段 `unknown`）；2=参数/文件错误；1=self-test 失败。
- 解析器不执行 adb、不依赖 Android SDK、不引入第三方 Python 包。

## 6. JSON 结构（`--format json`）

```json
{
  "protocol": "uml-boot-bench/1",
  "lane": "uml",
  "unit": "ms",
  "notes": ["..."],
  "runs": [
    {
      "label": "run-1",
      "lane": "uml",
      "fixture": {"kernel_version": "7.2.0-um", "page_size": 16384, "ncpus": 1, "mem_mb": 512},
      "anchors": {"t0_spawn_monotonic_ms": null},
      "metrics": {
        "t1_kernel_init_ms": 40.0,
        "t2_rootfs_mounted_ms": 120.0,
        "t3_first_command_ms": 400.0,
        "t4_service_ready_ms": null,
        "t5_warm_resume_ms": null,
        "p0_proot_exec_ms": null,
        "q0_qemu_boot_ms": null,
        "duration_ms": 3182.0,
        "t3_from_spawn_ms": null
      },
      "sources": {"t1_kernel_init_ms": "console:kernel_timestamp"},
      "missing": {"t4_service_ready_ms": "no ..."},
      "warnings": [],
      "exit": {"code": 0, "reason": "exited", "signal": 0}
    }
  ],
  "summary": {
    "unit": "ms",
    "n": {"t3_first_command_ms": 1},
    "median_ms": {"t3_first_command_ms": 400.0}
  }
}
```

- `metrics.*` 为 `null` 表示缺失；具体原因在 `missing`。
- `anchors.t0_spawn_monotonic_ms` 是绝对宿主 monotonic 值，不是时长，**不参与** `summary`。
- `summary.n` 只统计非 `null` 值；`median_ms` 同。

## 7. 回归结果（零设备）

### 7.1 仓库内已脱敏 UML 片段（2026-10-29 报告 §5.1）

`fixtures/uml-console-real-20261029-excerpt.txt` + `fixtures/uml-exit-real-20261029-excerpt.txt`：

| 字段 | 值 | 说明 |
| --- | --- | --- |
| T3 | **400 ms** | `BENCH boot_uptime_ms=400`；落在报告 0.22–0.40 s 量级的上界 |
| `duration_ms` | **3182 ms** | spawn→退出，包含命令与收尾；**不是** T3 |
| T1 | `unknown` | 该片段无 printk timestamp；需 `printk.time=1` |
| T2 | `unknown` | `UMARM_BOOT_OK` 无 `guest_uptime`；需补字段 |

### 7.2 2026-11-07 格式合成样本（非真机日志）

`fixtures/uml-console-20261107-synthetic.txt` 按 11-07 报告 §1 的字段形态与中位数量级构造（`UMARM_BOOT_OK guest_uptime=0.340`、`UMARM_WORK_START uptime=0.350`、`duration_ms=612`）：

| 字段 | 值 |
| --- | --- |
| T2 | 340 ms |
| T3 | 350 ms（落在 0.22–0.40 s） |
| `duration_ms` | 612 ms（spawn→退出） |

**限制**：2026-11-07/11-08 的原始 console/exit 日志在外部证据根，不在本仓库；本仓库只有 10-29 的脱敏摘要。11-07/11-08 的格式兼容性由该合成样本与报告字段覆盖，**原始日志回归标 `not-tested`**。

### 7.3 QEMU / proot

- QEMU systemd fixture：T1=31、T2=510、T3=620、T4=1730 ms；Q0 需宿主配对，未提供时 `unknown`。
- QEMU 旧式 systemd（firmware/loader/kernel/initrd/userspace 五段）=8492 ms；截断行不给 T4。
- proot fixture：P0=50.101 ms；T1–T4 明确 `not applicable`，不伪装成 0。

### 7.4 U1/U2 alias 合成样本（U0.1，票 05）

`fixtures/uml-console-u1-ready-ok.txt`（U1 fast shell）与 `fixtures/uml-console-u2-zero-install-ok.txt`（U2 零安装；字段形态取自 Wave 1 集成报告 §3 的跨测日志摘录，非真机日志）：

| fixture | 字段 | 值 |
| --- | --- | --- |
| U1 alias | T3 | 110 ms（`U1_READY uptime=0.11`）；T2=60 ms 走 VFS timestamp fallback（`U1_T2_READY` 不在协议内） |
| U2 alias | T2 | 90 ms（`T2_MOUNTS_READY uptime=0.09`，优先于同 run 的 VFS timestamp 50 ms） |
| U2 alias | T3 | 110 ms（`T3_READY uptime=0.11 action=poweroff`）；裸 `READY` 被 `INIT_ZERO_INSTALL` 门住，不加值 |
| 裸 READY 歧义 | T3 | `unknown`（无 `T3_READY`/`INIT_ZERO_INSTALL` 上下文，不归任何 lane） |
| 缺 uptime | T3 | `unknown`（`U1_READY` 无 uptime 不猜值） |
| 非法单位/值 | T2/T3 | `unknown` + `invalid ... uptime=` warning（`uptime=0.09ms`、`uptime=abc`） |

**限制**：U1/U2 的原始 console 日志在外部证据根，不在本仓库；alias 行为由上述合成 fixture 覆盖，真实 U1/U2 日志回归标 `not-tested`。

## 8. 缺失字段时该补什么

| 缺失 | 需要补的日志/参数 |
| --- | --- |
| T1 | 内核 cmdline 加 `printk.time=1`（或 `initcall_debug`）；保留 `Run /... as init process` 行 |
| T2 | init 挂载 `/proc,/sys,/dev,/dev/pts,cgroup2` 后输出 `UMARM_BOOT_OK guest_uptime=$(cut -d' ' -f1 /proc/uptime) nproc=$(nproc) page=$(getconf PAGESIZE)`；U2 零安装 init 输出 `T2_MOUNTS_READY uptime=<s>` |
| T3 | 第一条命令后输出 `BENCH boot_uptime_ms=$(awk '{printf "%d", $1*1000}' /proc/uptime)`，或 `UMARM_WORK_START ... uptime=<s>`；U1 fast shell 输出 `U1_READY uptime=<s>`，U2 `ready.sh` 输出 `T3_READY uptime=<s>`（alias 优先级见 §4.1） |
| T4 | guest 内运行 `systemd-analyze` 并把 `Startup finished in ... = ...` 落到 serial；或输出 `BOOT_TIMELINE T4 uptime_s=<s>` |
| T5 | warm-resume 采集脚本写 `BOOT_TIMING`，`ready_point=T5`，两端同一宿主 monotonic clock |
| P0 | proot 采集脚本写 `p0_ms=`，或 `t0_monotonic_ms`/`ready_monotonic_ms` + `ready_point=P0` |
| Q0 | QEMU 宿主侧记录 spawn 与 T4 的 monotonic 时间，写 `BOOT_TIMING`，`ready_point=Q0`（或 `T4`） |
| `duration_ms` | 保留 `uml-exit.txt`，或在控制台打印 `kernel exited code=... reason=... duration_ms=...` |

## 9. 来源与关联

- `.scratch/uml-zero-device-20261110/spec.md` §2（指标定义）、§3（U0）
- `.scratch/uml-zero-device-20261110/issues/01-boot-timeline-harness.md`
- `.scratch/uml-zero-device-20261110/issues/05-u0-marker-aliases.md`（U0.1 alias，票 05）
- `docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md` §3/§4（U1/U2 跨测标记证据与契约缺口）
- `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`
- `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、§5.1
- `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1、§5.1
- `research/uml-runner/README.md`（`uml-exit.txt` 字段、hostfs 约束）
- `research/uml-runner/guest/um-command.sh`（`BENCH boot_uptime_ms`）
- `research/uml-fabric-probe/guest-overlay-uml/init`（`UMARM_BOOT_OK` / `UMARM_WORK_START`）
- 外部格式核实：`docs/research/2026-11-10-uml-boot-timeline-harness.zh-CN.md` §3（研究子智能体来源清单）
