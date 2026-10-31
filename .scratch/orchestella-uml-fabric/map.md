# Map: orchestella-uml-fabric

## Destination

回答两个问题并产出可投票的 ACR 输入：① UML 的零设备前置（可复现内核构建、平台策略核实、源码闭环、先例拆解）；② protected-only 设备上「完整内核 + 硬件加速 + 多核调度」的可行路线（组合编排 fabric）与真实上限。

## Notes

- 证据基线：`docs/research/2026-10-31-uml-direction-research.zh-CN.md`（CodeBuddy 调研）+ `docs/research/2026-10-31-uml-full-spectrum-research.zh-CN.md`（WorkBuddy 调研）+ 会话 21–23 真机报告 + `ACR-draft-four-lanes-20261029.md`。
- 纪律：不同夹具性能数字禁止相除（ACR §1.4）；推算必须标「非实测」；社区自述只作互证不作决策依据。
- 全部工单零设备；设备窗口项（G1–G4、Landlock、AVF 排查）不在本 effort，等零设备批次完成后再排。
- 工单彼此独立、无阻塞边，可任意并行认领；认领时把 `Status:` 改为 `claimed` 并注明会话名。

## Decisions so far

- 2026-10-31 工单 02 resolved：16KB 三日期收敛（Play 更新硬截止 2027-02-01；GitHub/F-Droid 渠道无门槛）；Android 17 三项中仅 targetSdk≥37 条件项构成未来前置（ACCESS_LOCAL_NETWORK / Safer Native DCL—C）；Play 政策下「运行时下载内核」禁止、rootfs 下载中风险。→ docs/research/2026-11-02-platform-policy-matrix.md
- 2026-10-31 工单 05 resolved：pVM 生产通道判词=权限层（MANAGE_VIRTUAL_MACHINE=signature|preinstalled|development，@SystemApi not for third party）——Play 渠道第三方 App 在任何 AVF 设备均无 pVM 生产通道；fabric 可交付形态=双轨组合编排（轨道 A protected：QEMU 生产面+Microdroid worker+UML 第三 worker；轨道 B non-protected 开发者通道：自有内核自定义 VM）。→ docs/research/2026-11-05-pvm-fabric-route.md
- 2026-10-31 工单 01 resolved：UML 内核可复现构建打通（x86_64 容器+NDK r29 交叉，pin 169b17ff）；stub 逐字节复现、内核差 1,040 B（release clang flavor 不可得，溯源缺口）；SMP 内核实为「SMP+Docker」组合 316 项 diff；Haven 三坑落为构建期检查项。→ research/uml-kernel-build/ + docs/research/2026-11-01-uml-kernel-reproducible-build.md
- 2026-10-31 工单 03 resolved（批次 2）：sepolicy/seccomp 五问闭环（② 漂移 1 行）；**rseq/`set_robust_list` 归因反转工单前提**——bionic 从不发起这两调用、app seccomp 允许表三分支均不含（默认 TRAP=SIGSYS 31），Haven signal 31 = seccomp × glibc≥2.35 构建内核，不适用本项目 NDK bionic 内核 → 三选一判「**无需处理（不阉割）**」，checklist 坑①回写为条件性检查项；execmod 触发面收窄（仅 COW 后 mprotect-exec），UML 全链走 execute/execmem 合法通道，「rootfs 只读镜像」改述为产品决策；checkpolicy 容器合成冒烟过，完整树编译裁定不做；01 遗留 defconfig 对照判 wontfix。→ docs/research/2026-11-03-selinux-seccomp-closure.md
- 2026-10-31 工单 04 resolved（批次 2）：QEMU MTTCG 扩展性——文献有 aarch64 曲线（x86 宿主 smp4 2.72×/拐点 4–6，标形态参考），手机宿主 0 样本 → 停止条件部分命中转纯实测设计；头号杀手=宽原子回退串行化（`-cpu max` 反噬、`cortex-a57` 已正确侧，列必控变量）；4 vCPU=稳健默认档非最优；big.LITTLE 绑核仅情报，不改「队列限流、不承诺绑核」前提；账本需求 R-Q1~R-Q8 对齐 05 模型（新缺口：ACCEL LOST 不释放预留，DualLaneCoordinator.java:224-229）；benchmark 与 W-FAB-01 合并为 F-MTTCG-C200 一份设计（`-smp 1/2/4/8` + native 参照列，n≥10 控温）。→ docs/research/2026-11-04-qemu-mttcg-scaling.md
- 2026-10-31 工单 06 resolved（批次 2）：Haven 运行期=四层叠防（immortal fork/PDEATHSIG 双绑/三段式关闭/#686 三处修复），**无 pre-start 试锁先例**（F_GETLK 预检是本项目自有对策）；许可隔离=Haven 主仓 AGPL 只借鉴、GlassOnTin/uml-transport（GPL-2.0）补丁配方可引用；lakitu 实数 75 补丁、os_early_checks 拒 4K 内核核实到代码级（start_up.c:977-996）、**16K guest 内核天然覆盖 4K/16K 宿主**→G2 工作量在启动器侧（预案 7 项）；uml4termux soak=方法可复用但 b1 实为 partial、「10047s」[未找到证据]、Termux 域证据不可平移 App 域，G-长驻门三门设计产出；drolin=jniLibs+LICENSING 最完整交付形态 / Stryker=运行时下载（矩阵②活样本）；Podroid grant UX 六阶段+五点诊断 → D-5/D-6 增补 + D-7 建议待裁定。→ docs/research/2026-11-06-downstream-teardown.md
- 2026-10-31 **设备窗口轮（device-myron，Android 17/SDK37，报告 docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md）**：**G1 PASS**（UML SMP `ncpus=4` 36 域 guest nproc=4，4×sha256 加速 3.92×/E=0.98，无 tracee 被杀）→ **UML 定位不收窄**，「单文件引擎」降级路径不触发；**G4 PASS**（kill -9 后重启 EXT4 recovery→mount 正常，Haven#686 未复现，启动器锁清理无需立项）；**F-MTTCG-C200 首个手机宿主样本**（QEMU 11.1.1，3轮×6列，.o digest 全一致）：S(2)=1.66/S(4)=1.42/S(8)=1.12 → **QEMU 面最优宽度=2 vCPU**（04 文档「4=稳健默认」降级）、翻译税 92%（同轮 -j1 对）；派发宽度优先级 native(6.63×) > UML(3.92×) > QEMU-TCG(≤1.77×)——**强化 UML 作 fabric 并行宽度 worker 的判词**。新坑：SMP 旋钮 `ncpus=`（`--cpus=` 无效）；App 域长驻 **HyperOS cgroup freezer 实锤**（屏幕 Dozing 后 App+UML 一起冻结 33min，RSS 零漂移、uptime 冻结，唤醒后 UML 时钟以宿主墙钟追赶）→ 06 票「须持前台服务」preflight 项获设备级证据。G2/W-061/062 not-tested（4K 页设备无 16K 通道）。**T7（36 域 QEMU 装配补验）PARTIAL**：DisableSandbox 预置 + `-Sy --needed` + provision 管线机械面推进到 package-install 门前全通，网络面直驱受阻于 QEMU11.1.1↔gvproxy v0.9.0 协议不匹配（无 slirp/SELinux unix-bind/EOF ×2 语法，根因链 4 步有日志）；10-29 的 offline 失败定位到传输层而非 pacman——包安装不是 36 域收敛的结构性阻塞，真终态留给下一窗口与 W-FAB-03 同窗。探针资产入库 `research/uml-fabric-probe/`。
- 2026-10-31 **零设备批次 3（2026-11-08 收口，B/C 双 subagent + 协调者 ACR 合入）**：①**T7 网络面源码闭环**（`docs/research/2026-11-08-t7-network-closure.md`）——**11-07 根因链第 4 步「协议不匹配」改判为误诊**：对接 spec 在本仓库 `runtime/engine-qemu/.../QemuLaunchSpec.java:569-575`（`-netdev socket,id=orchestella-net,connect=127.0.0.1:<port>` + `virtio-net-device`，11-07「spec 在 DroidLayer 层」系当日只搜 app/ 所致）；QEMU socket/stream 均为 4B 大端长度前缀帧、与 gvproxy qemu 监听**线兼容**（三方源码互证），EOF 真因=单发 accept 槽位被探活/残留连接烧毁（产品防御同坑：`portBound` bind 测试+gvproxy 先行硬序+失败清理连带 gateway，提交 109b43d5）；t7 配方按产品语法逐字改写（qrun.sh net 模式 + gvproxy-t7.yaml + 分层诊断），子自评一次通过 ~85%。②**research 修缮包四项落地**：stop-file watcher 双修（启动期清理+死后兜底，sim 6/6 PASS）、freezer 前台服务（specialUse FGS）+PARTIAL_WAKE_LOCK、`ncpus=` 旋钮全通道（`--cpus` 清零）、**stub 16KB 重链落地**（关键实测：原配方 `-Wl,-n` 钉死 p_align=0x8，只加 flag 无效；去 `-n`+flag → p_align=0x4000 过 check-16kb-kernel.sh；产物 2,056 B 入库，行为等价 not-tested 留 16K 窗口）；双 flavor gradle 构建通过。③**ACR 草案已合入设备轮+批次 3 全部增量**（§1.1/1.2/1.4/3.4/5.1/7.4/7.5/8），判词「包安装不是结构性阻塞」入档；就绪度清单与议程见 ACR §8（投票前缺：16K 宿主、D9、链接、T7 终态、W-FAB-03、distcc COMPLETED@36）。

## Not yet specified

- G1 app 域多核、G2 16K 宿主、G3 同夹具四列、G4 崩溃恢复：G1/G4 已过（设备轮）；G2/G3 需设备窗口，待零设备批次收口后立项。
- 下一设备窗口清单增量（ACR §8.2）：T7 终态（按 2026-11-08 配方）、16K 宿主（含 stub_exe-16k 行为等价）、D9 Docker、链接阶段、W-FAB-03 OEM 普查、distcc COMPLETED@36、F-MTTCG 补轮（n≥10）。
- ACR 投票：材料已合入（ACR §8），等设备窗口补数后开投票；议程头号=开发通道产品形态裁定。

## Out of scope

- 产品代码（集成、扩展包骨架、Lane Router 改动）：ACR 通过前不写。
- 任何需要 Root/解锁/改 SELinux 的路径。
