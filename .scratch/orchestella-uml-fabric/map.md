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

## Not yet specified

- G1 app 域多核、G2 16K 宿主、G3 同夹具四列、G4 崩溃恢复：需设备窗口，待零设备批次收口后立项。
- ACR 投票：等本 effort 产出 + 设备数据齐备。

## Out of scope

- 产品代码（集成、扩展包骨架、Lane Router 改动）：ACR 通过前不写。
- 任何需要 Root/解锁/改 SELinux 的路径。
