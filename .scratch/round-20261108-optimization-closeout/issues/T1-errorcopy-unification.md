# T1 · ErrorCopy 单源统一

Status: done
Owner: lane/t1-errorcopy（子智能体）
Files: app/src/main/java/io/github/nyzeep/orchestella/startup/LaunchFailureCopy.kt、app/src/test/java/io/github/nyzeep/orchestella/startup/LaunchFailureCopyTest.kt、app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt（仅 formatLaunchFailure 一处 + import/注释）、app/src/test 对应锁定测试

## What

2026-11-07 优化轮双 lane 并行产物 `ErrorCopy`（strings 版，向导/终端消费）与
`LaunchFailureCopy`（代码常量版，MainActivity 消费）功能重叠。统一为 **ErrorCopy 单源**：

1. `LaunchFailureCopy` 改为**委托 ErrorCopy**（保留对象与 `describe(failure)` 签名，
   实现转发；Javadoc 注明单源化决策与日期）。固定码 E-* 契约**只增不改名**。
2. 语义对齐要点：LaunchFailureCopy 现有的专用族
   （InstanceLifecycle.InvalidTransitionException / AvfEngineException 按 reason 分派 /
   LaneStartException / OutOfMemoryError）在 ErrorCopy 无对应条目——**在 ErrorCopy 中
   新增**这些族（新固定码，如 E-LIFECYCLE / E-AVF-* / E-LANE / E-OOM，命名与既有
   E-XXX 风格一致），ErrorCopy 的 strings.xml 补对应 error_* 键；判定顺序
   most-specific-first（子类判定顺序回归用例模式照 ErrorCopyTest 既有写法）。
3. MainActivity `formatLaunchFailure` 改走 `ErrorCopy.detailMessage(context, failure)`
   展示格式：主文案 + （错误码 E-XXX）副文本（D 报告 §三 建议）；异常类名继续写
   `OrchestellaLaunch` 日志（行为不变）。
4. `LaunchFailureCopyTest` 改为断言委托一致性（或并入 ErrorCopyTest 契约），不得
   删固定码断言。

## Acceptance

- 全仓 grep `LaunchFailureCopy` 仅剩委托实现与 MainActivity 消费点（无第二份映射表）。
- :app 全量单测 ≥1260/0/0（在 ASCII worktree 跑）。
- lint 无新增 error。
- strings.xml 新键双语资源一致（values / values-en 若存在）。

## 阻塞边 / 边界

- 不得触碰 OrchestellaMotion.kt、ModernTerminalView、ControlChannelRecoveryTest（T2 边界）。
- 禁止改 `res/anim`、themes（T2 边界）。

## Comments

### 2026-11-10（编排层回填）

T1 完成于 lane/t1-errorcopy b3be7ab8（子智能体 lane 回报 1266/0/0，+6 用例）。合并进 main b52ba76f；集成冒烟 1266/0/0。要点：LaunchFailureCopy 成无消费者委托壳（后续文案收口轮可删）；R.string.launch_failed 变 unused（warning 级）。
