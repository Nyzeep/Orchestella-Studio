# 运行时启动 / 停止性能收敛（runtime-performance）

Status: ready-for-agent
Effort: .scratch/runtime-performance/
基线: main 44f5b23（以实际 dispatch 时 HEAD 为准）
性质: 性能优化；不改变 Full Linux 契约、ADR-0020 的所有权语义、ADR-0007 的存储边界

## Problem Statement

P0-2 已经把 QEMU / gvproxy 的生命周期收敛到单一 supervisor，但启动路径上仍有一笔确定性的、与发行版大小成正比的重复工作：

- 每次 `launch()` 都重建 `initramfs-embedded`：读取整个发行版 tarball、计算 SHA-256、gzip、写盘（`QemuRuntimeBootstrapper` + `EmbeddedProvisionInput`）。
- 输入规模：Ubuntu 约 30 MB、openSUSE 34 MB、Debian 90 MB、Fedora 92 MB、AlmaLinux/Rocky 约 104 MB、Arch 约 829 MB。
- tarball 本身已压缩，再 gzip 几乎不减小体积，只消耗 CPU 和 I/O。
- 该 initramfs 通过 `-initrd` 交给 QEMU，会被整体载入 guest RAM；Arch 在默认 2 GB guest 中先占掉约 40%，小内存配置可能直接无法启动。
- 每个实例保留一份 initramfs，磁盘上再复制一份 tarball 大小的文件。

guest 侧并不是每次都需要 rootfs：根盘一旦写入 `provisioning_version=1`，init 只从 embedded-input 读取 `bridge.token`、`guest.password`、`hostname` 等小文件。宿主没有消费这个事实，所以已装配实例仍在支付全量成本。

停止路径还有第二笔确定性开销：`closeChannels` 对每个 service forward 顺序发三次 gvproxy API（`refresh` → `unexpose` → `refresh`），单次超时 1.5 秒；这段不受 `graceMs` 约束，且在 session 池 monitor 内执行。网关随后本来就会被杀掉，远程 `unexpose` 是冗余的。

另外，项目没有 launch 阶段计时，也没有性能基线，因此无法证明收益，也无法防止回退。

## Solution

1. **先有度量**：为一次 launch 记录有序阶段与耗时，提供纯 JVM 可测的 API 和设备测量脚本。
2. **让宿主知道实例已装配**：消费 guest 已有的 `DROIDLAYER_PROVISIONING_COMPLETE version=1` 标记，持久化到实例元数据；缺失时一律按未装配处理。
3. **已装配实例只嵌入凭据**：重复启动不再读取 rootfs；首次装配保持现状。
4. **停止快速收尾**：关闭通道时跳过远程 `unexpose`，加硬 deadline，池锁外停止；保持 ADR-0020 的三态语义不变。
5. **可选架构线**：用只读 virtio-blk 输入盘替代 initramfs 嵌入，先宿主侧、后 guest 侧；仅在度量证明首次启动或 initrd 内存仍是主要成本时执行。
6. **最后才调 QEMU**：参数化 CPU 模型、tb-size、aio、discard、smp、netdev，做单变量矩阵；没有统计显著收益就不改默认。

## Non-goals

- 不引入 AVF 性能承诺；AVF 仍是被门挡住的研究线。
- 不把 `cache=writeback` 改成 `cache=unsafe`。
- 不改变 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED` 的语义，也不新增第二个 stop owner。
- 不提高 guest RAM 来掩盖 initrd 膨胀。
- 不做与启动/停止无关的终端渲染、UI 或发行版扩展。
- 不在本 effort 中处理生产签名、GPL/NOTICE 或 Android 17 适配。

## User Stories

1. 作为运行时工程师，我希望一次 launch 的阶段耗时可查询，以便知道时间花在资源校验、initramfs 构建、网关就绪还是 guest 启动。
2. 作为运行时工程师，我希望已装配实例的重复启动不再读取发行版 tarball，以便启动成本与发行版大小脱钩。
3. 作为用户，我希望重启一个已装配实例时不必等待整个发行版被重新打包。
4. 作为用户，我希望小内存配置也能启动 Arch 等大发行版，不被 initrd 占满 guest RAM。
5. 作为运行时工程师，我希望首次装配行为保持不变，以免优化破坏新实例。
6. 作为运行时工程师，我希望“已装配”判断在缺失证据时回退到保守路径，以免跳过必要的 rootfs。
7. 作为运行时工程师，我希望停止大量 service forward 时仍在有界窗口内完成，不被 1.5 秒 API 超时累加拖长。
8. 作为审查者，我希望有一个 JVM 测试能在有人重新引入 rootfs 读取时变红，以便不依赖真机防回归。
9. 作为性能负责人，我希望 QEMU 参数改动有单变量矩阵和统计口径，而不是凭感觉改默认。
10. 作为架构负责人，我希望只读输入盘是一条独立、可放弃的架构线，不与快速路径修复绑死。

## Decisions

- **已装配信号只认 guest marker**：`DROIDLAYER_PROVISIONING_COMPLETE version=1`，允许跨 chunk 分割；不根据根盘首字节或文件大小猜测。
- **缺失即保守**：没有 marker、版本不匹配、旧元数据缺字段，都按未装配处理。
- **首次装配不变**：本 effort 不改变首次装配的输入内容与 guest 行为。
- **停止释放以网关退出为准**：网关停止会关闭所有 forward 监听；关闭通道阶段不再逐个远程撤销。
- **调优默认不变**：任何 QEMU 默认值变更必须是独立决定，附矩阵数据。
- **只读输入盘是可选线**：只有 T3 的度量仍显示首次启动/initrd 内存是主要成本时才进入实现。
- **不新增常驻进程 / Binder service / 隐藏 API。**

## Acceptance

- 已装配实例重复启动：rootfs 输入流读取字节数为 0；initrd 大小有硬上限；首次装配行为不变。
- marker 跨 chunk、缺失、版本不符都有测试；元数据 round-trip；不含 secret。
- 64 个 service forward 的停止在有界窗口内确认，不因远程 API 超时超过 grace。
- 启动阶段记录可导出，含 stage、耗时、发行版、内存/vCPU，不含 token/口令/按键/用户文件。
- CI 中至少有一个回归测试能在 T3 被回退时变红。
- 所有未在真机验证的项明确写 `not-tested`，不以 JVM 测试冒充设备结论。

## Tickets

- T1 启动阶段时间线与度量旁路（无阻塞）
- T2 已装配信号与实例元数据（无阻塞）
- T3 重复启动只嵌入凭据（阻塞于 T1、T2）
- T4 停止路径快速收尾（无阻塞）
- T5 只读输入盘（宿主侧，阻塞于 T3；可选）
- T6 只读输入盘（guest 侧，阻塞于 T5；可选）
- T7 QEMU 单变量调优实验（阻塞于 T1；可选）
- T8 启动性能回归门（阻塞于 T3）
