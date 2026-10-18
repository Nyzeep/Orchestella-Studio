# T4 退出顺序、元数据与未确认停止

Status: ready-for-agent
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
