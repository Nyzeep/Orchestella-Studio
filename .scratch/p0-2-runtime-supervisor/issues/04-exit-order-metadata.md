# T4 退出顺序、元数据与未确认停止

Status: resolved
Type: task
Blocked by: T3

## 交付

- `InstanceLaunchSequence.awaitGuestExit` 在 `waitFor` 后调用 supervisor stop。
- 只有 `CONFIRMED` / `ALREADY_STOPPED` 才 `completeExit` 并写 STOPPED/CRASHED。
- `UNCONFIRMED` 返回新 `ExitStatus.STOP_UNCONFIRMED`，保留 RUNNING、registry、guard。
- 详情页 60 秒超时由 `InstanceDetailActionRules` 显示“停止未确认”，不标记 STOPPED、不重启。
- `MainActivity` 的状态文案区分 `STOP_UNCONFIRMED`。

## 验收

- 未确认停止后 `store.load(id).state() == RUNNING`，session 仍注册，guard 仍在。
- 正常退出仍写 STOPPED，重复 watcher 无副作用。
- 退出码 23 且 stop 确认时仍记录 CRASHED。

## 测试

- `InstanceLaunchSequenceTest` 新增未确认 stop 用例。
- `InstanceDetailActionRulesTest` 更新超时文案/决策。
- `./gradlew :app:testDebugUnitTest --tests '*InstanceLaunchSequenceTest' --tests '*InstanceDetailActionRulesTest'`

## Comments

2026-11-03 协调：本轮做 tracker 对账，逐条核对代码后回填（来源 `.scratch/quality-round-20261103/notes/B5-tracker.md` §T4）。

2026-11-03 协调：交付项逐条核对。
- `InstanceLaunchSequence.java:59` `exitCode = session.waitFor()` → `:70` `if (stopAfterExit(session) == StopResult.UNCONFIRMED)`；`stopAfterExit` `:88-95` → `owned.stopRuntime(EXIT_STOP_GRACE_MS)`。
- 只有确认才收尾：`:70-80`，`completeExit` 在 `:74`；`UNCONFIRMED` 在 `:71` 提前 return，`:79` 的 `InstanceLaunchGuard.end` 不执行 → 保留 registry 与 guard。
- 新枚举值 `ExitStatus.STOP_UNCONFIRMED` `:35`。
- `MainActivity.kt:1827-1828` 有独立文案「停止未确认：运行时未在时限内停止，实例仍保留，请检查运行日志」。
- 详情页 60 秒：`InstanceDetailActivity.kt:68`（`EXIT_TIMEOUT_MS = 60_000L`）、`:844`（deadline）、`:860-861`（`TIMED_OUT -> finishPowerChange(..., "停止未确认：…")`）；决策函数 `:1063-1068`。

2026-11-03 协调：测试映射。`InstanceLaunchSequenceTest` 覆盖 `:49`（未确认后保留 RUNNING/owner/guard，`:63` 断言 `STOP_UNCONFIRMED`、`:65` 断言 owner 保留）、`:276`（正常退出只写一次 STOPPED，重复 watcher 无副作用）、`:298`（退出码 23 且 stop 确认仍记 CRASHED，`:315` 断言 `crashDiagnostic().exitCode()==23`）。`InstanceDetailActionRulesTest.kt:66-69` 覆盖超时不得触发重启。

2026-11-03 协调：**措辞级偏差（语义等价，判过）**——票面写「详情页 60 秒超时**由 `InstanceDetailActionRules` 显示**」，实现是「Rules 只产出 `TIMED_OUT` 决策（`:1063-1068`），文案常量在 `InstanceDetailActivity.kt:860-861`」。行为达成，字面不成立；若审查口径要求字面满足需另立票搬常量。

2026-11-03 协调：实跑 `./gradlew :app:testDebugUnitTest --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL`。真机 60 秒超时表现为 `not-tested`。
