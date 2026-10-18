# Ticket 05 实施报告

- 票号/标题：runtime-performance-05 只读输入盘（宿主侧，条件票）
- 执行 session / 模型 / thinking：续跑 T5 决策门 / DeepSeek-V4.1-Flash / 未记录
- 分支：未创建　基线 commit：`91510b2`（集成分支，含 T3/T7/T8）

## 0. 背景 / scope / 边界检查

- 背景 / scope：条件票决策门；只在设备度量证明首次启动宿主准备或 initrd 内存是主要成本时，才实现只读 virtio-blk 输入盘。
- 越界检查：未实现、未创建分支、未改生产代码；这是 spec 明确允许的 `deferred`。
- Architecture Change Policy：未触发（无架构变更）。

## 1. 任务目标

仅当 T3 的度量仍显示首次启动宿主准备或 initrd 内存占用是主要成本时，把 distro tarball 作为独立只读 virtio-blk 设备暴露给 QEMU；否则按 spec 保持 `deferred`。

## 2. 实际完成

- ✅ 执行 T5 决策门：读取 T3 的 `## Comments`、T3 实施报告与度量。
- ✅ 结论：**`deferred`**，未进入实现。
- ✅ 理由：T3 只有 JVM 合成 fixture 数据（credentials-only segment 523 B / 全量 segment 903 B），没有 Ubuntu/Arch 真机启动耗时、首次启动宿主准备耗时或 initrd 内存数据；本会话 `command -v adb` 返回不可用，无设备。无法证明“首次启动宿主准备或 initrd 内存仍是主要成本”，不满足 spec 的启动条件。
- ✅ 未创建 `perf/t5-input-disk-host` 分支，未修改 `QemuLaunchSpec` 或任何生产代码，未启动 T6。
- ⚠️ T5 的验收项（只读、raw、独立设备、未启用时参数逐项相同）全部 `not-tested`，因为本票未实现。

## 3. 未完成 / 与预期偏差

- 未实现只读输入盘。这是按 spec 的条件票规则主动 `deferred`，不是跳过或伪造完成。
- 未做设备矩阵，因此没有 T5 所需的第一手证据；该缺口与 T7 的 `not-tested` 同源（无设备）。

## 4. 修改的模块

- 无生产代码修改。
- 仅本报告与工单 `## Comments`。

## 5. 重要设计决策

- **没有证据就不扩大架构面**：只读 virtio-blk 输入盘会增加 QEMU 设备拓扑与 guest 侧消费复杂度；在 T3 已消除重复启动 rootfs 读取、且没有设备数据证明首次启动/initrd 内存是主要成本时，保持现状更符合 spec 的“可选架构线”定位。
- **不阻塞主线**：T5 的 `deferred` 不影响 T3/T7/T8 的交付与验证。

## 6. 测试

- 本票未实现，未新增或运行 T5 测试。
- 决策依据测试：T3 `QemuRuntimeBootstrapperProvisionedTest` 8/8；T8 `ProvisionedLaunchRegressionTest` 2/2；集成全量验收集另见 orchestration report。
- 设备：`not-tested`（无 adb/设备）。

## 7. Benchmark / 实测数据

| 数据 | 值 | 来源 |
| --- | --- | --- |
| credentials-only segment | 523 B | T3 JVM 合成 fixture（4 KiB rootfs） |
| 全量 segment | 903 B | T3 JVM 合成 fixture |
| 真机首次启动宿主准备耗时 | `not-tested` | 无设备 |
| 真机 initrd 内存占用 | `not-tested` | 无设备 |

这些数字只证明 T3 快速路径不含 rootfs payload，不能证明首次启动或 initrd 内存是主要成本，因此不构成启动 T5 的证据。

## 8. 风险

- 若未来设备数据表明首次启动/initrd 内存确实是主要瓶颈，当前 `deferred` 会延后该优化；需要重新打开本票并补充设备证据。
- 若 T7 的 tuning 值对象后续被修改，T5 实现时必须保留默认值等价与 `QemuTuning.DEFAULTS` 委托。

## 9. 新发现

- 本会话没有任何 Android 设备或 `adb`，T5/T6 与 T7 的设备矩阵都无法产生第一手数据；这是环境约束，不是代码问题。
- T3 的快速路径已经把“重复启动 rootfs 读取”降为 0；是否还需要输入盘，取决于首次启动与 initrd 内存的设备测量，而不是重复启动。

## 10. 对后续 ticket 的影响

- T6 不启动；其状态与理由记录在 T6 工单 `## Comments`。
- 未来启动 T5 时，T7 的 `QemuTuning` 默认委托与 `QemuLaunchSpec` 默认 argv 等价测试必须保留；不能并行改同一文件。
- 人工复核时若决定继续 T5，需要先提供设备测量：首次启动各阶段耗时、initrd 大小/内存占用、Ubuntu 与 Arch 样本。

## 11. Commit

- 无代码 commit；本报告与工单 Comments 在集成分支以 docs commit 入库。

## 12. Ticket 状态

`deferred`（条件不满足；无设备数据）。建议人工复核是否补设备测量后再决定是否继续。

报告人：CodeBuddy (DeepSeek-V4.1-Flash) ｜ 日期：2026-10-18 ｜ 耗时：未记录
