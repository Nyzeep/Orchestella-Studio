# Spec · 零设备 UML 快速启动与规模化方向（2026-11-10）

- 基线：main `bd315c08`（2026-11-10，v0.6.2 + 150+ 未发版提交），工作分支 `research/uml-zero-device-direction-20261110`
- 当前 effort：`.scratch/orchestella-uml-fabric/map.md`（13/13 工单 resolved，ACR 待投票）
- 研究输入：`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`（外部一手来源与启动分解）
- 内部证据：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md`、`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`、`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md`、`docs/research/ACR-draft-four-lanes-20261029.md`、`docs/research/2026-10-21-host-native-lane-evaluation.md`
- 纪律：**零设备优先**；ACR 通过前不写产品集成代码；真机项标 `not-tested` 并给出下个窗口入口；跨夹具数字禁止相除；不补造 ADR-0015。

## 0. 问题陈述

用户问题：为什么 Termux 的 proot-distro 下载完 rootfs 后几乎可以“秒启动”Linux，而本项目要等待内核启动、rootfs 装配、provisioning、systemd 等一系列流程？

结论先行：

1. **proot 不是“更快的虚拟机”，而是没有 guest 内核的用户态兼容层。** 它用宿主内核 + `ptrace` 路径重写模拟 `chroot`/`bind mount`，rootfs 是 tarball；启动只是宿主 exec 一个用户态进程，没有内核 boot、没有 systemd/OpenRC、没有真实 namespace/cgroup。它的“秒开”与完整 Linux VM 不在同一个 Runtime 类别。
2. **UML 也不是 proot 的同类替代。** UML 在 App 进程里跑一个真 Linux 内核；它必须付内核初始化 + init 的成本，但远低于 QEMU。内部真机证据已经到 **220–400 ms（中位 ≈330 ms）到 guest marker**，2026-11-07 的 ext4 + bootprobe 轮为 **0.22–0.40 s**；所以 UML 内核启动不是当前产品“慢”的主因。
3. **当前产品等待主要来自 QEMU 全 VM 流程与首启装配/provisioning，而不是 UML boot。** QEMU 要 boot 完整内核、装配 rootfs、首启安装包/证书/locale/服务；这些是 UML 方向可以拆开优化的部分。
4. 因此下一步不应追求“proot 式无内核秒开”，而应把 UML 做成**两档承诺**：
   - **Fast shell**：预烘焙 rootfs + 最小 init + 预置工具链，下载/解包完成后到第一条命令；
   - **Full systemd**：同一内核启动到 `multi-user.target` / 网络 / SSH 就绪，用于服务与 Docker 场景。
   两者分别测量，不用一个“启动时间”混称。

## 1. 事实基线（内部证据）

| # | 事实 | 证据 | 对方向的含义 |
| --- | --- | --- | --- |
| F1 | proot 无 guest 内核，`ptrace` 重写路径；`proot-distro` 明确 systemd/OpenRC/socket 服务通常不可启动，真实 namespace/cgroup 不可用 | `docs/research/2026-10-21-host-native-lane-evaluation.md` §4.1；ACR §1.1 | proot 的“秒开”不能作为 UML 的对照基线；它属于 Userspace Compatibility |
| F2 | proot 原生指令但 syscall 路径有开销；内部记录 `openat` proot 28.005 µs vs native 2.503 µs（约 11×） | `docs/research/2026-10-21-host-native-lane-evaluation.md` §2；ACR §4.3 | “启动快”不等于“跑得快”；UML 也要单独测 syscall/IO |
| F3 | UML 真内核已在 Android 17 App 域启动；启动 220–400 ms，中位 ≈330 ms；fork+exec 1.49×、纯 CPU 1.08×；syscall 约 8× 是结构性开销 | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1 | UML 的卖点是“真内核 + 原生指令”，不是“零开销” |
| F4 | 2026-11-07 设备轮：ext4 + `work=bootprobe` 20 循环，19/20 干净，boot 0.22–0.40 s；唯一失败为 stop 文件残留（基础设施，非 boot 不稳定） | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5.1 | UML 已有亚秒级 fast path 的真机样本；下一步是产品化与可重复测量 |
| F5 | SMP `ncpus=4` guest nproc=4，4×sha256(32MB) 有效加速 3.92×；SMP 启动 0.23–0.43 s（UP 0.21–0.43 s） | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1–§2 | 多核 fast path 可用；SMP 不是启动时间的致命变量 |
| F6 | UML 长驻必须持前台服务 + wake lock；HyperOS Dozing 会冻结 App + UML 子进程；MagicOS 60min FGS 驻留 PASS | `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §7.1.6；`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T8② | 规模化必须把 FGS/wake lock/freezer 作为一等约束 |
| F7 | 首启 provisioning 的包事务是分钟级（T7 92 包事务 <1800 s 的成功样本）；QEMU 启动为分钟级 | `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F；`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1 | 预烘焙 / 首启零安装是压缩“等待感”的最高杠杆；需 U0 实测确认占比 |
| F8 | UML 内核可复现构建已打通（pin `169b17ff`，stub 逐字节一致，内核差 1,040 B 为 release clang flavor 溯源缺口）；16 KB stub 重链已产出，行为等价 not-tested | `.scratch/orchestella-uml-fabric/issues/01-uml-kernel-reproducible-build.md` | 零设备能够继续做构建/打包/基准；16 KB 宿主仍缺 |
| F9 | ACR 技术缺项只剩 16K 宿主与 distcc@36；产品代码在 ACR 通过前不写 | `.scratch/orchestella-uml-fabric/map.md`；ACR §8.2 | 本方向的工作包必须是研究/原型/CI，不得越过 ACR |

> 外部一手来源（proot 官方站/源码、proot-distro README、Linux `arch/um` 文档/源码、AOSP seccomp/SELinux、Termux 文档等）见研究输入文件；本 spec 只引用内部证据与本项目实测。

## 2. 指标定义（先统一口径）

所有启动数字必须标注是哪一个“就绪点”。禁止把不同就绪点或不同 lane 的数字相除。

| 指标 | 定义 | 采集点 | 适用 lane |
| --- | --- | --- | --- |
| **T0 spawn** | 宿主 `ProcessBuilder.start()` / `exec` 返回 | 宿主 monotonic clock | UML / QEMU |
| **T1 kernel init** | guest 内核第一条可控 init 输出 / `init` 开始执行 | guest console + 内核 timestamp | UML / QEMU |
| **T2 rootfs mounted** | `/` 已挂载，`/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2 就绪 | guest `/proc/uptime` + marker | UML / QEMU |
| **T3 first command** | 第一条用户命令可执行并输出 `READY`（shell fast path） | guest `/proc/uptime` + 宿主 clock | UML / QEMU / proot |
| **T4 service ready** | systemd `multi-user.target` / 网络 / SSH 就绪 | `systemd-analyze` + guest marker | UML / QEMU |
| **T5 warm resume** | 从 snapshot/预热点恢复到 T3 | 宿主 clock + guest marker | UML（待验证） |
| **P0 proot exec** | `proot` 进程 exec 到 shell 并输出 `READY` | 宿主 clock | proot |
| **Q0 QEMU boot** | QEMU 进程 spawn 到 T4 | 宿主 clock + guest marker | QEMU |

计时纪律：
- guest 内统一 `/proc/uptime`（10 ms 粒度；Alpine busybox `date` 无 `%N`）；
- 宿主侧统一 `CLOCK_MONOTONIC`；
- 每列至少 n=5，取中位数；跨轮交错，控温；
- 记录内核版本、defconfig、rootfs sha256、页大小、`ncpus`、`mem`、host 负载；
- 任何跨设备/跨夹具数字只做方向参考，不做倍数结论。

## 3. 工作包

### U0 · 启动阶段基准协议与 harness（P0，零设备）

**目标**：把 T0–T5 变成可复现的脚本与报告；先解析已有真机日志，再为下个设备窗口提供一键采集。

**交付**：
- `tools/research/uml-boot-bench/README.md`：协议、字段、就绪点、n/中位数/控温规则；
- `tools/research/uml-boot-bench/parse_boot_log.py`：解析 `uml-console.log`、`uml-exit.txt`、QEMU serial、`dmesg`、`systemd-analyze`、proot 计时日志，输出 JSON + Markdown 表；
- `--self-test`：用合成日志覆盖 UML/ QEMU / proot 三种 lane，断言 T0–T4 提取；
- 用 2026-11-07/11-08 轮已有日志做只读回归（敏感数据门后入库的摘录）。

**验收**：
- `python3 tools/research/uml-boot-bench/parse_boot_log.py --self-test` 通过；
- 对已有 UML 日志输出 `T3` 与 `duration_ms`，与报告中的 0.22–0.40 s 量级一致；
- 不依赖 adb、不依赖 Android SDK；纯 Python 3 标准库。

**风险**：UML console 可能没有内核 timestamp；需要 `printk.time=1` 或 `initcall_debug`；QEMU/proot 日志格式不统一。

### U1 · Fast-boot UML profile（P0，零设备）

**目标**：做一个“最小内核 + 内置 initramfs + 预烘焙 rootfs”的 fast shell profile，测到 T3；保留 systemd profile 作对照。

**交付**：
- `research/uml-fastboot/`：`defconfig-fast`、`initramfs/init`、`build-fast.sh`、`manifest.md`；
- 两组对照：UP vs SMP；hostfs vs `ubd0` ext4；内置 initramfs vs 外部 rootfs；
- ARM64 内核 + stub 构建产物与 sha256；x86_64 宿主参考启动日志（明确标注“参考，不是设备结论”）；
- 一页“fast shell vs systemd”取舍表。

**验收**：
- ARM64 内核构建成功；stub 过 `check-16kb-kernel.sh` 或明确记录 16K 缺口；
- x86_64 参考 boot 到 `READY`，T0–T3 有数；
- 不写产品代码、不改主构建、不改版本号。

**风险**：x86_64 参考不能外推 ARM64；内核裁太小会丢 cgroup/Docker；16K 宿主仍未验证。

### U2 · Rootfs 预烘焙与 build-time provisioning（P0，零设备）

**目标**：把首启的包安装、locale、证书、基础工具链移到构建期；首启只做挂载 + init + exec，网络关闭也能到 ready。

**交付**：
- `research/uml-rootfs-prebake/`：Alpine 与一个 glibc 发行版（Ubuntu/Debian 二选一）的 rootfs 构建脚本、manifest schema、sha256；
- 一个“首启零安装”init：不调用包管理器、不联网，输出 `READY`；
- 与当前 provisioning 流程的首启时间/大小对照表。

**验收**：
- 同一输入两次构建的 sha256 一致，或明确记录非确定项；
- 断网首启到 `READY`，日志无 package manager 调用；
- 产物大小、构建时间、包清单入库；
- 不写产品代码。

**风险**：跨架构 rootfs 装配、包管理器非确定性、许可证/NOTICE、镜像体积。

### U3 · Android-like seccomp / host 兼容性 harness（P1，零设备）

**目标**：在宿主上用接近 Android App 域的 seccomp/no-new-privs 约束跑 UML，找结构性阻断；为下个设备窗口列“必须验”的 syscall。

**交付**：
- `tools/research/uml-seccomp/`：seccomp profile（从 AOSP 策略抽取/生成）、`strace`/`bpftrace` 采集、矩阵报告；
- 覆盖 `ubd`、`hostfs`、`vector`、`ncpus`、`mconsole`、`SIGCHLD`、`clone`、`mount`、`personality`、`setrlimit`；
- 与 AOSP seccomp 源对照的“允许/拒绝/未知”表。

**验收**：
- harness 能在 filter 下启动 host-arch UML；记录 denied syscall；
- 矩阵中每项有 pass/fail/unknown；unknown 必须写设备验证入口；
- 不改产品 seccomp 策略；不写产品代码。

**风险**：Android seccomp 按 arch/OS 版本不同；宿主代理不精确；UML 可能用未列出的 syscall。

### U4 · Warm-start / snapshot 可行性（P2，零设备研究）

**目标**：判断 UML 是否能 checkpoint/restore、hibernation、预热点池；给出 go/no-go 与内存成本。

**交付**：
- 一手来源与源码调研（`arch/um`、`mconsole`、CRIU、相关补丁）；
- 可行则给 host 原型与 T5 数据；不可行则给“预热点池”设计与内存预算；
- 决策 memo（不写产品代码）。

**验收**：
- 结论有来源/原型证据；不把“未找到”写成“不可行”；
- 明确与 FGS/freezer 的交互；
- 不写产品代码。

**风险**：上游可能没有完整 checkpoint/restore；CRIU 对 UML 支持未知；预热点池内存成本高。

### U5 · 打包 / CI / 许可证（P1，零设备）

**目标**：把内核、stub、rootfs、initramfs 的可复现构建和 GPL 对应源码交付物做成 CI 门。

**交付**：
- CI job：kernel build + stub 16K + manifest + source bundle + NOTICE；
- `check-uml-artifacts.py`：校验 sha256、defconfig、版本、GPL 源包完整性；
- 干净检出构建说明（不依赖本地 `evidence/`）。

**验收**：
- CI 绿；干净检出可构建；manifest 与产物一致；
- 不发布、不改版本号、不写产品代码。

**风险**：CI 构建时长、上游源码可复现性、GPL 法务结论。

### U6 · 多实例规模化（P2，零设备宿主 + 设备门）

**目标**：测 N 个 UML 实例并存的 RSS/CPU/启动/端口/磁盘，给产品上限输入。

**交付**：
- host benchmark：N=1/2/4/8，记录 RSS、CPU、T3、失败模式；
- 内存/CPU 账本公式与保守上限；
- 设备窗口清单（Android 内存压力、FGS、freezer、端口）。

**验收**：
- host 数据表完整；设备项标 `not-tested`；
- 不写产品代码。

**风险**：host ≠ Android；UML syscall 8× 会让高并发 I/O 放大。

### U7 · ACR 证据包（持续）

**目标**：把 U0–U3/U5 的证据整理进 ACR §8，供投票；不写代码。

**交付**：ACR addendum、证据表、未决项清单。
**验收**：每项证据有路径/命令/日志；缺项明确。

## 4. 优先级与依赖

```
Wave 1（P0）：U0 ──┬── U1 ──┐
                  └── U2 ──┴── U4（warm start）
Wave 2（P1）：U3、U5
Wave 3（P2）：U6
持续：U7（ACR 证据包）
```

- U1/U2 可与 U0 并行，但必须使用 U0 的字段与计时口径；
- U4/U6 依赖 U1/U2 的 fast path 基线；
- U7 在 Wave 1+2 后形成 ACR 证据包；
- 产品集成、`UmlInstanceLauncher`、主包通道、16K 宿主、distcc@36 不在本 spec 的实现范围。

### 4.1 里程碑

| 里程碑 | 内容 | 出口证据 | 不承诺 |
| --- | --- | --- | --- |
| **M1 · Fast path 基线** | U0 + U1 + U2 | T0–T3 表、fast vs systemd 取舍、预烘焙 rootfs 首启零安装 | 不承诺设备 ARM64 秒级；不承诺 Docker |
| **M2 · Android 可行性** | U3 + U5 | seccomp 矩阵、GPL/CI 打包门 | 宿主 pass ≠ 设备 pass；不承诺 16K |
| **M3 · 规模化** | U4 + U6 | warm-start go/no-go、N 实例数据 | 不承诺快照可用；host ≠ Android |
| **M4 · ACR 证据包** | U7 | ACR §8 更新、缺项清单 | 不代替投票；不写产品代码 |

M1 的“目标值”由 U0 实测基线决定，不在本 spec 里凭空写毫秒数；M1 之后再决定是否对外承诺“亚秒级”。

## 5. 验收总门

1. 所有工作包零设备可执行；需要真机的项必须在报告里标 `not-tested` 并给命令入口。
2. 所有数字带就绪点（T0–T5/P0/Q0）、n、中位数、设备/宿主与夹具标识。
3. 不出现跨夹具倍数、未实测的“秒开”承诺、或把 proot 与 UML 混为同一 Runtime 类别。
4. 不写产品代码、不改版本号/签名/发布通道、不改 ADR 正文、不补造 ADR-0015。
5. 敏感数据门：真机日志/截图/序列号/路径不入库；只引用已脱敏的仓库内报告。

## 6. 排除项

- 产品集成（`InstanceLaunchCoordinator` 之外的 UML 启动器、主包/扩展包形态）——等 ACR。
- 16K 宿主真机验证——设备门。
- distcc COMPLETED@36——DV-01A 外部阻塞。
- “UML 比 proot 快/慢 N 倍”——同轮同夹具数据未取，禁止。
- 用 x86_64 参考数据直接外推 ARM64 设备结论。

## 7. 风险与停止条件

| 风险 | 触发信号 | 处置 |
| --- | --- | --- |
| x86_64 参考不代表 ARM64 | 宿主 T3 与设备 T3 方向不一致 | 只把宿主当协议验证；设备窗口重测 |
| fast profile 丢能力 | cgroup/Docker/网络不可用 | 保留 systemd profile；两档承诺分开 |
| rootfs 预烘焙不可复现 | 两次 sha 不同且无法解释 | 记录非确定项；不宣称可复现 |
| seccomp 代理不精确 | 宿主 pass/设备 fail | 矩阵标 unknown；设备窗口优先验 |
| warm start 不可行 | 无 checkpoint/restore 证据 | 退回预热点池设计，算内存账 |
| 规模化内存超限 | N 实例 RSS 超预算 | 给保守上限；设备门再定 |

**停止条件**：需要 Root/unlock/改 SELinux/改 pvmfw 才能继续 → 停止并记录；需要写产品代码才能继续 → 等 ACR；需要把未实测数字写成承诺 → 停止。

## 8. 第一批票

- `issues/01-boot-timeline-harness.md`（U0）
- `issues/02-fastboot-profile.md`（U1）
- `issues/03-rootfs-prebake.md`（U2）
- `issues/04-seccomp-host-compat.md`（U3）

U4/U6/U5 先在 spec 中保留，等 Wave 1 结果再建票，避免过度拆分。

## 9. Wave 1 结果（2026-11-10）

- U0/U1/U2 三支已合并到 `integration/uml-zero-device-20261110`；三个 merge 提交为 U0 `8915fdea`、U1 `69903a0e`、U2 `1968704c`；集成 tracker/报告在 `1968704c` 之上追加，最终 head 以 `git rev-parse` 为准。集成报告：`docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md`。
- 票据 01/02/03 均为 `resolved`；U1/U2 的 ARM64 设备、16 KB 宿主、真 SMP、systemd T4、aarch64/Debian 独立复现等边界保持 `not-tested`。
- 跨分支最小测试：U1 x86_64 fast kernel + U2 Alpine x86_64 预烘焙 rootfs 到达 `T2_MOUNTS_READY` → `T3_READY` → `READY`；日志无包管理器/网络；n=1，宿主 `seccomp=auto`，**不是 ARM64 设备结论**。
- 已知契约缺口：U0 parser 不识别 `U1_READY` / `T2_MOUNTS_READY` / `T3_READY` / `READY`；建议开 U0.1 小票（alias + fixture + self-test），本集成会话未改 parser。
- Wave 2（U3/U5）可以集成分支为 base 开工；不得把 x86_64 宿主数字外推为 ARM64 结论，也不得把预烘焙 rootfs 当产品可分发物。

## 10. Wave 2 结果（2026-11-10）

- U3/U5 经敏感门清理后合并到 `integration/uml-zero-device-20261110`：清理后 head U3 `80d66d4e`、U5 `b74f57a5`；merge 提交 U3 `fd8d1f7b`、U5 `d54abd1c`。报告：`docs/research/2026-11-10-zero-device-uml-wave2-integration.zh-CN.md`。
- 敏感门修复：U3 三类（MAC 运行时生成、`digits[]` 拆分、9 个 `harness.env` → `harness.meta`）；U5 两处（打包文档路径字面量、self-test notes 占位符）。修复后两支与合并树均 `SENSITIVE_DATA_OK`。
- 合并树自测：U0 `checks=118`、U3 `profiles=10` + `checks=31`、U5 `checks=70` + manifest `passed=134 failed=0 info=6`；产品路径与 `release.yml` 零改动。
- 票 04 `resolved`（真机 seccomp 矩阵、16K 宿主保持 `not-tested`）；U5 记录在 `integration-log.md`。
- Wave 3（U4/U6）与 U0.1（parser 标记别名小票）以**最新** `github/integration/uml-zero-device-20261110` 为 base 开工；U0.1 只改 `tools/research/uml-boot-bench/`。

## 11. ARM64 设备窗口结果（2026-11-11）

- 设备分支 `device/uml-wave2-20261110`（base `5ef13035`）；设备 device-a（HONOR，API 36，4KB 宿主页）。报告：`docs/research/2026-11-11-uml-arm64-device-window-wave2.zh-CN.md`。
- **U1 真机 boot PASS**：UP 内置 initramfs n=5（T3 中位 330 ms guest uptime，exit 0）；SMP `ncpus=4` n=3（`U1_INFO ncpus=4`，T3 中位 410 ms）；16K 检查 `ELF_16KB_OK`。
- **U2 真机首启 PASS（两通道）**：Alpine 3.22.8 aarch64 hostfs n=5（T2/T3 中位 340/350 ms）与 ext4 n=5（350/350 ms），阴性检查无包管理器/网络。
- **seccomp 启动级 PASS**：`seccomp=on` 与 `auto` 均落 `Userspace mode: SECCOMP`，零 SIGSYS；U3 12-case 设备矩阵仍 `not-tested`。
- **2 实例并发 PASS**（runner 外 run-as 通道）；**FGS+wake lock 60 min**：frz=0、内核 RSS 零漂移、kill 后恢复 PASS。
- 保持 `not-tested`：16K 宿主、systemd T4、U3 12-case 设备矩阵、Debian aarch64、N≥3 多实例、周期 tick。
- 跨路径复现 open 项：`linux-fast`（内置 initramfs）未复现 manifest pin sha（其余产物逐字节一致；差异隔离在 initramfs 内嵌段）。
- 票据：02/03 External gates 设备项转 `resolved`；04 启动级转 `resolved`；05 实采解析 PASS 后 `resolved`。
