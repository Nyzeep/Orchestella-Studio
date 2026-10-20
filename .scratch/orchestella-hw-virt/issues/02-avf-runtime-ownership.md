# 02: AVF 运行时所有权、停止与退出

Type: implementation
Status: ready-for-agent
Priority: P0
Owner: runtime lifecycle owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

当前 AVF 的正常关闭可能把 VM 留在后台：`AvfSession.close()` 只关通道，不调用 `session.stop()`；`PersistentTerminalSessionPool` 对非 `RuntimeOwnedSession` 直接返回 `true`（“legacy sessions keep close-only”）；`InstanceLaunchSequence` 对非 owner 直接当 `CONFIRMED`。同时 `waitFor()` 把 STOPPED 和 DELETED 都返回 0，`stop()` 吞异常、无 grace、无二次探测。用户看到“已停止”，VM 可能还活着；这是本 effort 最高优先级的安全不变量。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4；`AvfInstanceLauncher.java:94-101,213-218`；`PersistentTerminalSessionPool.java:648-654`；`InstanceLaunchSequence.java:88-93`；`ReflectiveAvfVirtualMachineApi.java:551-595`。

## What to build / 范围

1. `AvfSession` 实现 `RuntimeOwnedSession`（或等价的窄 `RuntimeOwner` seam），让启动结果携带 owner，而不是靠 `instanceof` 探测。
2. 有界停止：`stopRuntime(graceMs)` 返回 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`；顺序为 stop 请求 → bounded 等待/状态探测 → 必要时强制 → 二次探测；平台 stop 抛异常不等于已停止。
3. 退出分类：区分正常退出、崩溃/非 0、`DELETED`、停止请求导致的退出、观察失败；`DELETED` 不能当 0。`ExecutionSession.waitFor()` 的兼容语义要写清楚（可用新的 `ExitInfo`/`RuntimeExit`，`waitFor` 委托并返回非 0 表示异常）。
4. `close()` 与 `terminate()` 都走同一个 owner；幂等；`close()` 不得只关通道。`stop()` 可重复调用，不产生第二个 stop owner。
5. 注册失败清理：`engine.launch()` 成功后 `ExecutionSessionRegistry.register()` 抛异常时，必须有有界 stop；UNCONFIRMED 时保留诊断/owner，不得留下孤儿。QEMU 已有 `cleanupFailedLaunch`，AVF 需要等价物。
6. `PersistentTerminalSessionPool` 的非 owner 分支改为“无法确认”：不得返回 `true`；保留 RUNNING/owner 或返回 false，并记录 typed 原因。
7. `InstanceLaunchSequence` 删除“非 owner = CONFIRMED”默认；非 owner 是 typed failure/UNCONFIRMED，不是成功。
8. 复用或新增 `LaunchTimeline` 阶段：launch requested、create、run returned、stop requested、stop confirmed/unconfirmed、exit observed；不记录 token/口令/用户正文。
9. 新增 ADR-0021（AVF runtime ownership）：说明 AVF 的 close/terminate/stop/exit、App 死亡、UNCONFIRMED 语义，并明确它扩展了 ADR-0020 的范围（ADR-0020 原文把 AVF 排除在外）。

## 行为验收（零设备）

- [ ] fake 宿主下 `close()` 后 fake VM 必须 stopped；`close()` 重复调用不重复 stop。
- [ ] fake 宿主下 stop 卡住/不收敛 → `UNCONFIRMED`；元数据保持 RUNNING、owner/guard/根盘占用保留。
- [ ] fake 宿主下 VM 崩溃 → 非 0/CRASHED；`DELETED` → 异常分类，不得 0。
- [ ] `stopRuntime` 正常 → CONFIRMED；已停止 → ALREADY_STOPPED；stop 抛异常 → UNCONFIRMED（不能当成功）。
- [ ] `register()` 失败 → VM 被有界 stop；UNCONFIRMED 时诊断可见；无孤儿。
- [ ] 非 `RuntimeOwnedSession` 的 session 不再被 `InstanceLaunchSequence`/`PersistentTerminalSessionPool` 当作 CONFIRMED/true。
- [ ] 同一 session 的 close/terminate/pool stop 不会并发产生两个停止 owner。
- [ ] LaunchTimeline 的阶段顺序单调，测试可断言。

## 测试 seam 与 red 命令

- 先写失败测试：`InstanceLaunchSequenceTest`（非 owner 分支）、`PersistentTerminalSessionPoolTest`（非 owner）、`AvfVirtualMachineEngineTest`/`ReflectiveAvfVirtualMachineApiTest`（stop/waitFor）、新增 `AvfLaunchCleanupTest`（register 失败）。
- 使用现有 `FakeVirtualizationContext`/`VirtualMachine`/`AvfVirtualMachineApiFixture`；不要 mock Android。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
  ./gradlew :app:testDebugUnitTest --tests '*InstanceLaunchSequenceTest' \
    --tests '*PersistentTerminalSessionPoolTest' --no-daemon --stacktrace
  ```
- 先让“close 不停”“非 owner=CONFIRMED”“register 失败孤儿”三条 red，再最小 green。

## 失败、数据与兼容

- 失败只记录 reason code、阶段、grace、是否强制、状态值；不记录 token、口令、用户正文、serial。
- 兼容：`ExecutionSession` 既有调用方不能因新方法断裂；`waitFor` 的返回语义要有文档/测试。旧 session（非 owner）行为变更要点名，不得静默。
- 不删除用户根盘；不因 stop 失败写 STOPPED；不新增产品状态（除非 ADR-0021 明确）。
- 不做第二 stop owner；不在 UI 线程做阻塞 stop。

## 迁移、回滚与依赖

- 无实例数据迁移；行为变更只影响生命周期状态。
- 回滚：撤回 owner/stop 实现与 ADR-0021；保留 QEMU 路径不变。
- 依赖：不新增第三方库；不改 QEMU `RuntimeProcessSupervisor` 语义。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-02/implementation-report.zh-CN.md`；双轴 review 同 id。
- 完成 = 上述 fake 测试全绿 + 全量 app/runtime check + ADR-0021 + 报告明确“真机未验”。

## 不做

- 不做真机 stop/崩溃/App 死亡验收（设备 effort）。
- 不实现 protected/shell-owner；不改 QEMU 生命周期；不把 UNCONFIRMED 新造成产品状态。
