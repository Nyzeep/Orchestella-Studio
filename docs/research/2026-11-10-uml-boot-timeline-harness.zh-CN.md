# U0 · UML/QEMU/proot 启动时间线 harness —— 实现与回归报告

> 日期：2026-11-10｜分支：`feat/uml-u0-boot-timeline`｜base：`62d96e99`
> 范围：U0（零设备）；不写产品代码、不执行 adb、不依赖 Android SDK、不引入第三方 Python 包
> 协议：`tools/research/uml-boot-bench/README.md`（protocol `uml-boot-bench/1`）
> 工单：`.scratch/uml-zero-device-20261110/issues/01-boot-timeline-harness.md`

## 0. 结论

1. **协议与解析器已落地**：`tools/research/uml-boot-bench/` 包含 T0–T5/P0/Q0 定义、纯标准库解析器、118 项 self-test、合成 fixtures 与仓库内脱敏片段。
2. **真实脱敏回归通过量级判据**：2026-10-29 报告 §5.1 的已脱敏 UML 片段输出 **T3 = 400 ms**、`duration_ms = 3182 ms`；T3 落在报告 0.22–0.40 s 量级的上界。
3. **缺失字段不猜**：同一片段没有 printk timestamp、`UMARM_BOOT_OK` 无 `guest_uptime`，解析器分别输出 T1/T2 = `unknown` 并给出需要补的字段；没有用 `duration_ms` 反推 T3。
4. **零产品代码改动**：变更只在 `tools/research/uml-boot-bench/` 与 `docs/research/`；`app/`、`runtime/`、`guest-assets/` 零改动。
5. **限制**：2026-11-07/11-08 的原始 console/exit 日志在外部证据根、不在仓库；本轮只能用其报告字段形态的合成样本覆盖格式，原始日志回归标 `not-tested`。本轮没有真机执行，ARM64 设备项全部标 `not-tested`。

## 1. 交付物

| 文件 | 作用 |
| --- | --- |
| `tools/research/uml-boot-bench/README.md` | T0–T5/P0/Q0 协议、字段、单位、n/中位数、控温、禁止跨就绪点相除 |
| `tools/research/uml-boot-bench/parse_boot_log.py` | Python 3 标准库解析器；`--lane uml\|qemu\|proot`、`--format text\|json`、`--self-test` |
| `tools/research/uml-boot-bench/fixtures/` | 合成日志 + 仓库内已脱敏的 2026-10-29 UML 片段 + provenance 说明 |
| `docs/research/2026-11-10-uml-boot-timeline-harness.zh-CN.md` | 本报告 |

## 2. 口径与实现

### 2.1 就绪点与锚点

| 点 | 锚点 | 解析来源 | 备注 |
| --- | --- | --- | --- |
| T0 spawn | 宿主 `CLOCK_MONOTONIC`，绝对 anchor | `BOOT_TIMING` 的 `t0_monotonic_ms` | 不是时长，不参与中位数 |
| T1 kernel init | guest 内核时间 | 带 timestamp 的 `Run /... as init process` | 需要 `printk.time=1` |
| T2 rootfs mounted | guest `/proc/uptime` | `UMARM_BOOT_OK guest_uptime=<s>`；fallback `VFS: Mounted root` timestamp | `UMARM_BOOT_OK` 无值 → unknown |
| T3 first command | guest `/proc/uptime` | `BENCH boot_uptime_ms=<ms>`；fallback `UMARM_WORK_START ... uptime=<s>` | 不用 `duration_ms` 反推 |
| T4 service ready | guest 内核时间 | `systemd-analyze` 的 `Startup finished in ... = <total>`；fallback `multi-user.target` timestamp | 截断行 → unknown |
| T5 warm resume | 宿主 `CLOCK_MONOTONIC` | `BOOT_TIMING` `ready_point=T5` 或 `BOOT_TIMELINE T5 elapsed_ms=` | 普通冷启动没有 T5 |
| P0 proot exec | 宿主 `CLOCK_MONOTONIC` | `p0_ms=` 或 `t0`/`ready` 配对 | proot 没有 T1–T4 |
| Q0 QEMU boot | 宿主 `CLOCK_MONOTONIC` | `q0_ms=` 或 `t0`/`ready` 配对（`ready_point=Q0`/`T4`） | serial 自身不能给 Q0 |
| `duration_ms` | 宿主 `CLOCK_MONOTONIC` | `uml-exit.txt`；console `kernel exited ...` fallback | spawn→exit（含配置加载、workload、退出），不是 T3 |

### 2.2 解析器行为

- **输入**：`--console`、`--exit-file`、`--dmesg`、`--systemd-analyze`、`--timing`；多 run 时按出现顺序配对，数量不匹配直接报错。
- **输出**：每个 run 的 `metrics`、`anchors`、`sources`、`missing`、`warnings`、`fixture`、`exit`；summary 给 `n` 与 `median_ms`。
- **缺失语义**：JSON 用 `null` + `missing` 原因；text 用 `unknown`；proot 的 T1–T4 明确 `not applicable`，不伪装成 0。
- **不猜值**：
  - 没有 `BENCH`/`WORK_START` → T3 缺失，不用 `duration_ms` 推；
  - `systemd-analyze` 行没有 `= <total>` → T4 缺失，不把组件相加冒充 total；
  - `UMARM_BOOT_OK` 没有 `guest_uptime` → T2 缺失；
  - Q0 没有宿主配对 → 缺失，不用 serial 的 T4 冒充 Q0；
  - 非法单位/负值/`ready < t0` → 对应指标缺失 + warning。
- **跨 lane 防护**：proot lane 的 console 若出现 `BENCH`，指标被忽略并 warning，保证 `LANE_METRICS`/`LANE_NOT_APPLICABLE` 与 README 一致。
- **隐私默认值**：`uml-exit.txt` 的 `kernel=`/`stub_exe=`/`rootfs=`/`args=` 含本机路径或 argv，解析器不输出；夹具只用合成路径或报告内已脱敏摘要。

## 3. 研究子智能体核实（外部日志格式）

标注：`[官]`=官方文档/源码确认；`[仓]`=仓库格式确认；`[推]`=推断/未确认。

| 字段（精确格式） | 单位/精度 | lane → 就绪点 | 缺失时补什么 |
| --- | --- | --- | --- |
| `/proc/uptime` 首字段 `^(\d+)\.(\d{2})` `[官]` | s；**截断** 10 ms；`CLOCK_BOOTTIME`（含 suspend） | UML/QEMU guest；proot=宿主 | 宿主 `CLOCK_MONOTONIC` spawn/ready |
| `UMARM_BOOT_OK guest_uptime=([\d.]+)` `[仓]` | s；2 位小数 = 10 ms | UML/QEMU → T2（mount 后） | 裸 `UMARM_BOOT_OK` 只是结束；另补 timed marker |
| `BENCH boot_uptime_ms=(\d+)`；`UMARM_WORK_START .* uptime=([\d.]+)`；`BOOTPROBE .* uptime=([\d.]+)` `[仓]` | ms / s；10 ms 量化 | UML/QEMU → T3 | T0 宿主 mono、T1 时间戳 |
| `Run (\S+) as init process`（无时间）+ printk `[%5lu.%06lu]` `[官/仓]` | 显示 sec.usec；UML 实际 10 ms | UML/QEMU → T1/T2 | `printk.time=1` 或抓 `dmesg` |
| `initcall (\S+) returned (-?\d+) after (\d+) usecs` `[官]` | µs；单次时长，非绝对时刻 | UML/QEMU 诊断 | `initcall_debug`；console 加 `ignore_loglevel` |
| `Startup finished in ...`；`(\S+) reached after (.+) in userspace\.` `[官]` | ms 精度；段/总可缺 | systemd → T4 | 无 systemd 用 guest marker |
| `exit_code=`、`reason=`、`ended_at=yyyy-MM-dd HH:mm:ss.SSS`、`duration_ms=(\d+)` `[仓]` | 宿主 wall ms；含配置加载与整个 workload | UML 退出 / T0 参考 | spawn/ready 宿主 mono；`duration_ms` ≠ T3 |
| proot 无官方格式；仓库 `NL_T0=`/`NL_T1=` `[官/仓]` | cs；10 ms | proot 批量，**非 P0** | `PROOT_SPAWN_MONO_NS`、`PROOT_READY_MONO_NS`、`PROOT_READY_MARKER` |

关键结论：

1. **`/proc/uptime` 是 `CLOCK_BOOTTIME`**（2.6.39+，含 suspend），`ktime_get_boottime_ts64` + `timens_add_boottime`；`%02lu` **截断**到 10 ms，不是四舍五入。UML/QEMU 各自 guest boot 起算，proot 是宿主 boot 起算；time namespace 会产生偏移。因此 T2/T3 有 10 ms 地板，不能与宿主 monotonic 直接相减。（[proc_uptime(5)](https://man7.org/linux/man-pages/man5/proc_uptime.5.html)；[fs/proc/uptime.c](https://github.com/torvalds/linux/blob/master/fs/proc/uptime.c)）
2. **printk timestamp 默认由 `CONFIG_PRINTK_TIME` 决定**；mainline Kconfig 无 default（即 n），仓库 pinned UML `linux-bionic.config` 是 `# CONFIG_PRINTK_TIME is not set`。cmdline `printk.time=1` 或 sysfs `/sys/module/printk/parameters/time` 可开。`dmesg` raw 是 `[秒.微秒]`；`initcall_debug` 的 `calling %pS @ %i` 中 `%i` 是 PID，不是时间；`returned ... usecs` 是单次 initcall 耗时，不是 init 开始时刻。解析器只用 init-start marker 的 timestamp 做 T1，不用 initcall 推时刻。（[printk.c](https://github.com/torvalds/linux/blob/master/kernel/printk/printk.c)；[Kconfig](https://github.com/torvalds/linux/blob/master/lib/Kconfig.debug)；[dmesg(1)](https://man7.org/linux/man-pages/man1/dmesg.1.html)）
3. **systemd 段可缺**：firmware/loader 需要 ACPI FPDT 或 EFI `LoaderTime*`；容器可能只有 userspace 且没有 `=` 总时长；旧版常只有 kernel+userspace。`target reached after` 的 target 是 default.target（常 graphical/multi-user），语义是 `ActiveEnter − userspace`，**不是**总 T4；解析器只信 `Startup finished in ... = <total>`（或带 timestamp 的 target marker），截断行给 unknown。（[systemd-analyze(1)](https://www.freedesktop.org/software/systemd/man/latest/systemd-analyze.html)；[analyze-time-data.c](https://github.com/systemd/systemd/blob/main/src/analyze/analyze-time-data.c)）
4. **QEMU `-serial file:` 原样写 guest 字节，没有宿主时间戳**；guest printk 从 kernel start 的 0 起，`console=ttyAMA0` 由驱动接管、`earlycon` 更早。QEMU 没有 boot duration 字段；`-trace` 只有自身 event 的 PID 与 epoch 微秒，QMP 未确认。Q0 必须补宿主 clock。（[QEMU tracing](https://www.qemu.org/docs/master/devel/tracing.html)；[qdev-device-use](https://github.com/qemu/qemu/blob/master/docs/qdev-device-use.txt)）
5. **UML 没有独立时间戳**；generic printk 可用，但 `arch/um/kernel/time.c` 的 `sched_clock=jiffies*(NSEC_PER_SEC/HZ)`，仓库 `HZ=100` → 10 ms；且 pinned 内核未开 `PRINTK_TIME`。`con=null` 会把所有 console 置空，`con0=fd:0,fd:1` 再把 console0 接 stdin/stdout；只写 `con=null` 会丢日志。（[UML HOWTO](https://docs.kernel.org/6.16/virt/uml/user_mode_linux_howto_v2.html)；[arch/um/kernel/time.c](https://github.com/torvalds/linux/blob/master/arch/um/kernel/time.c)；`research/uml-kernel-build/defconfigs/linux-bionic.config:220,1507`）
6. **proot 没有官方“到 shell ready”格式**，`-v` 只是 debug；仓库 `NL_T0/NL_T1`+`wall_ms` 只测批处理（`T0` 在批量编译前、`T1` 在批量结束后），**不是 P0**。P0 需要自定义宿主 monotonic 字段；proot 无 guest 内核，T1/T2 不适用。（[proot(1)](https://manpages.debian.org/testing/proot/proot.1.en.html)；`tools/native-lane/measure.py:378-425`）

对解析器的落地影响：T3 新增 `BOOTPROBE` fallback（11-07 `bootprobe.sh` 格式，优先级低于 `UMARM_WORK_START`）；README 明确 10 ms 地板与 `NL_T0/NL_T1` 不是 P0；fixtures 增加 `uml-console-bootprobe.txt`。

## 4. Self-test 覆盖（118 checks）

命令：

```bash
python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test
# UML_BOOT_BENCH_SELF_TEST_OK checks=118
```

| 类别 | 覆盖 |
| --- | --- |
| UML 正常 | T1=40、T2=120、T3=400、`duration_ms=3182`；fixture（kernel/page/ncpus/mem）；source 归因 |
| UML 缺失 | 无 `BENCH` → T3 unknown；无 exit → duration unknown；text 输出 `unknown` |
| 真实脱敏片段 | T3=400、duration=3182、T3 落在 220–400 ms；T1/T2 unknown + 具体补日志建议 |
| 11-07 合成样本 | T2=340、T3=350（WORK_START）、duration=612 |
| 11-07 BOOTPROBE 格式 | `BOOTPROBE ... uptime=0.350` → T3=350 ms（第二 fallback） |
| QEMU | 有/无 `systemd-analyze`；旧式五段 total；截断行不猜；`multi-user.target` 新拼写 |
| systemd 单位 | `s`、`ms`、`us`、`min`、`1min 2.500s`；total 优先于组件 |
| proot | `t0`/`ready` 配对、`p0_ms` 简写、非法单位、无内核阶段、跨 lane marker 过滤 |
| 宿主配对 | P0、Q0、T3-from-spawn、T5；`ready < t0` 拒绝；缺一半配对 warning |
| dmesg / canonical | syslog 前缀、`BOOT_TIMELINE T1–T5`、mount fallback |
| 统计 | 奇数/偶数中位数、忽略 null、n 只计非 null、T0 不进 summary |
| 输出 | JSON `json.loads`、text Markdown 表、`unknown`、median |
| 健壮性 | 空输入、畸形 timestamp、非法 `BENCH`/`UMARM_WORK_START` 值不崩溃、不猜 |

## 5. 回归结果

### 5.1 仓库内已脱敏 UML 片段（2026-10-29 报告 §5.1）

命令：

```bash
python3 tools/research/uml-boot-bench/parse_boot_log.py \
  --lane uml \
  --console tools/research/uml-boot-bench/fixtures/uml-console-real-20261029-excerpt.txt \
  --exit-file tools/research/uml-boot-bench/fixtures/uml-exit-real-20261029-excerpt.txt \
  --format json
```

| 字段 | 值 | 来源/说明 |
| --- | --- | --- |
| T3 | **400.0 ms** | `console:boot_uptime_ms`；报告量级 0.22–0.40 s 的上界 |
| `duration_ms` | **3182.0 ms** | `exit:duration_ms`；spawn→退出，包含命令与收尾 |
| T1 | `unknown` | 无内核 timestamp；缺失原因要求 `printk.time=1` |
| T2 | `unknown` | `UMARM_BOOT_OK` 无 `guest_uptime`；缺失原因要求补该字段 |
| T4 | `unknown` | fast shell 样本没有 systemd-analyze |
| T5 | `unknown` | 没有 warm-resume 配对 |

判据“0.22–0.40 s 量级”按 **T3** 判定；`duration_ms=3182` 是宿主进程总时长，**不参与**该判据，也不与 T3 相减。

### 5.2 2026-11-07 格式合成样本（非真机日志）

`fixtures/uml-console-20261107-synthetic.txt` 按 11-07 报告 §1 的字段形态与中位数量级构造：

| 字段 | 值 | 说明 |
| --- | --- | --- |
| T2 | 340 ms | `UMARM_BOOT_OK guest_uptime=0.340` |
| T3 | 350 ms | `UMARM_WORK_START ... uptime=0.350` |
| `duration_ms` | 612 ms | 11-07 报告 UP 列 `duration_ms` 中位 612.5 的量级 |

该 fixture 只证明**格式兼容性**，不是真机回归；11-07/11-08 原始日志不在仓库。另有 `fixtures/uml-console-bootprobe.txt` 覆盖 11-07 `bootprobe.sh` 的 `BOOTPROBE ... uptime=` 格式（T3=350 ms）。

### 5.3 QEMU / proot fixtures

| 场景 | 结果 |
| --- | --- |
| QEMU serial + systemd total | T1=31、T2=510、T3=620、T4=1730 ms；Q0 无宿主配对 → unknown |
| QEMU 旧式五段 systemd | T4=8492 ms；组件解析完整 |
| QEMU 截断 `Startup finished in ... =` | T4 unknown + warning，不猜 |
| QEMU `multi-user.target` marker | T4=900 ms（kernel timestamp） |
| QEMU 宿主配对 | Q0=1730 ms；T0 anchor=200000 ms |
| proot 配对 | P0=50.101 ms；T1–T4 not applicable |
| proot 非法单位 | P0 unknown + warning |
| UML 宿主配对 | `t3_from_spawn_ms=400 ms` |

## 6. 验收记录

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| self-test | `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` | `UML_BOOT_BENCH_SELF_TEST_OK checks=118` |
| 编译 | `python3 -m py_compile tools/research/uml-boot-bench/parse_boot_log.py` | 通过 |
| 真实片段回归 | 见 §5.1 | T3=400 ms、duration=3182 ms |
| 多 run 中位数 | 两个 UML fixture + `--label` | T3 n=2、median=375 ms |
| `git diff --check` | `git diff --check` | 干净（提交前复跑） |
| 敏感数据门 | `python3 tools/ci/check_sensitive_data.py --self-test` | `SENSITIVE_DATA_OK` |
| 产品路径零改动 | `git diff --name-only github/research/uml-zero-device-direction-20261110..HEAD \| grep -E '^(app/\|runtime/\|guest-assets/)'` | 无输出 |

## 7. not-tested 与缺失项

| 项 | 状态 | 下一步入口 |
| --- | --- | --- |
| 2026-11-07/11-08 原始 console/exit 回归 | `not-tested` | 原始日志在外部证据根；下个设备窗口用 U0 采集模板重跑 |
| ARM64 真机 T0–T4 完整分解 | `not-tested` | 下个设备窗口：`printk.time=1` + `UMARM_BOOT_OK guest_uptime` + `BENCH boot_uptime_ms` + `systemd-analyze` |
| T5 warm resume | `not-tested` | U4 研究线；需要 snapshot/restore 或预热点池 |
| Q0 宿主配对 | 协议已定义、零设备 fixture 已测 | 设备窗口在宿主侧记录 spawn 与 T4 的 monotonic 时间 |
| 16K 宿主 | `not-tested`（ACR 缺项） | 设备门；与本票无关 |
| UML console 无 timestamp 的设备样本 | 已知风险 | 启动参数加 `printk.time=1`；否则 T1/T2 只能 unknown |

## 8. 风险与边界

1. **UML console 可能没有内核 timestamp**：默认 printk 时间戳存在与否取决于内核配置与启动参数；没有时 T1 只能是 unknown，不能从 `boot_uptime_ms` 反推。
2. **QEMU serial 没有宿主时间**：Q0 必须由宿主侧配对；serial 的 T4 不能冒充 Q0。
3. **systemd-analyze 版本差异**：旧式/新式组件集合不同；解析器只信 `= <total>`，截断行不猜。
4. **proot 计时格式是自定义协议**：没有外部统一标准；采集脚本必须写 `BOOT_TIMING v1`，否则 P0 无法比较。
5. **`/proc/uptime` 粒度与语义**：首字段是 `CLOCK_BOOTTIME`（含 suspend），按 `%lu.%02lu` 截断到 10 ms；UML `sched_clock` 由 jiffies 派生（pinned `HZ=100`）也是 10 ms。不要把亚 10 ms 差异当结论，也不要把 guest uptime 与宿主 monotonic 混用。
6. **跨设备/跨夹具**：本报告所有数字只用于协议验证；不做 UML/QEMU/proot 倍数结论，不把 x86_64 参考外推 ARM64。
7. **11-07/11-08 原始日志缺位**：格式兼容性由合成样本覆盖，真实日志回归留给下个设备窗口。

## 9. 来源

- `.scratch/uml-zero-device-20261110/spec.md` §2、§3
- `.scratch/uml-zero-device-20261110/issues/01-boot-timeline-harness.md`
- `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`
- `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1、§5.1
- `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1、§5.1
- `research/uml-runner/README.md`、`research/uml-runner/guest/um-command.sh`
- `research/uml-fabric-probe/guest-overlay-uml/init`、`guest-overlay-qemu/init`
- 外部格式来源：见 §3（研究子智能体清单）
