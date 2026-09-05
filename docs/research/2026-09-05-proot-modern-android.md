# PRoot on modern Android 现状与性能特征（研究票 R6 结论）

> 日期：2026-09-05。方法：主线程串行检索（Termux issue 实测数据、proot-me issue、社区 benchmark 帖）。
> 结论状态：性能数字**有实测来源**；Compatibility Runtime（ADR-0001）的未来交付决策以此为底。

## 1. 性能特征（实测数据）

| 维度 | 数据 | 来源 |
| --- | --- | --- |
| 综合基准 | proot-distro 在 UnixBench 上比 docker/chroot **低 44%**；CPU 性能比 native 低 **7–10%** | Termux issue #5245（"zero-overhead replacement" 提案的实测引用） |
| 瓶颈本质 | ptrace 拦截 = 每次系统调用进程停-走（stop-and-go），syscall 密集负载放大 | StackOverflow 拦截机制分析 + Brendan Gregg ptrace 分析 |
| I/O 密集负载 | 严重劣化（社区案例：proot-distro 下 nvim 打开 ~15 秒；"quite hell" 的 Snapdragon 685 实测帖） | GrapheneOS 论坛 / Reddit r/termux |
| 与 QEMU TCG 对比 | PRoot CPU 近原生（7–10% 损耗） vs TCG 5–20× 减速——**CPU 上 PRoot 完胜，I/O 上两者各有劣化**；PRoot 无 guest kernel（省 GB 级内存） | 综合 |

## 2. 硬性限制（Compatibility 章节的"能力差清单"素材）

1. **嵌套 ptrace 失效**：gdb/strace/任何 ptrace 工具在 proot 内不可用（proot-me issue #18）。
2. **ptrace(TRACEME) 在部分设备内核报 "Operation not permitted"**（2025-12 GNU Emacs 邮件列表报告）——逐机型兼容风险。
3. **proot 无法从进程脱离**：全部进程被 ptrace 挂钩。
4. **phantom process killer 冲突**：Android 12+ 的 32 进程上限直接导致 proot-distro 在 Android 14 上崩溃（社区实测）——与我们 ADR-0011 的应对策略（收敛宿主进程 + 官方开关指引）同样适用且更紧迫。
5. 无真实 kernel：namespace/cgroup/systemd-as-PID1/Docker 全部不可用（与 ADR-0001 的类别定位一致）。

## 3. 交付方式（若未来解锁 Compatibility Runtime）

- proot 可执行文件必须以 **jniLib（.so 名义）打包**（W^X 约束，targetSdk≥29 禁止从 data 目录 exec——ADR-0004/round-0 已确认的机制），exec 自 nativeLibraryDir。
- ptrace 自身子进程在 app 沙箱内合法（Termux 生态长期验证）。
- 实例形态 = 目录树（ADR-0001 已定的数据模型差异）；DistroManifest 的 tarball 可直接解包使用，无需块镜像 provisioning——**比 Full Linux 实例更简单的装配路径**。
- root 密码/uid 全部为模拟；共享面可复用同一白名单框架。

## 4. 对已关闭决策的影响检查

- ADR-0001（PRoot 定义为等级、MVP 不交付）：**实测数据支持该定位**——PRoot 有真实价值场景（低内存设备、CPU 密集负载）但也有硬伤（I/O 劣化、无隔离、兼容长尾），交付决策继续推迟是正确的。
- ADR-0009/0010（桥接与白名单）：PRoot 无 guest kernel，桥接协议的 guest-agent 形态将不同（用户态实现）——**若未来交付，需要单独的 Compatibility 契约设计票**（雾区新增条目，见下）。
- 无需要重开已关闭 ADR 的新证据。

## 5. 产生的雾区更新

- 新增雾区条目：**"Compatibility Runtime 契约设计（若交付）"**——与 Full Linux 契约平行的轻量契约（实例=目录树、无 vsock/virtio、终端通道语义、共享面复用白名单），触发条件 = 用户决定交付 Compatibility Runtime。
