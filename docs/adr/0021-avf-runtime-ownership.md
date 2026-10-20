---
status: accepted
date: 2026-10-20
---

# ADR-0021: AVF 运行时所有权、停止与退出分类

## Context

ADR-0020 把 QEMU 的进程、网关与 socket 收拢到单一 supervisor，并在 Consequences 中明确写着“AVF 路径不在本 ADR 的所有权范围内”。AVF 路径随后长出了自己的生命周期缺口：

- `AvfSession.close()` 只关闭显示/通道，不调用 `session.stop()`，正常关闭可能把 VM 留在后台；
- `PersistentTerminalSessionPool` 对非 `RuntimeOwnedSession` 直接返回 `true`（“legacy sessions keep close-only”），`InstanceLaunchSequence` 对非 owner 直接返回 `CONFIRMED`，两者都把“无法确认”当成“已停止”；
- `ReflectiveAvfVirtualMachineApi.waitFor()` 把平台 status 0（STOPPED）与 2（DELETED）都折叠成 0，崩溃/删除/停止请求不可区分；
- `stop()` 吞掉平台异常、没有 grace、没有二次状态探测；
- `engine.launch()` 成功后 `ExecutionSessionRegistry.register()` 抛异常时没有清理路径，VM 成为孤儿。

AVF 没有宿主进程可以 `destroy()`；它的“停止”是平台 API 调用加状态观测。因此 ADR-0020 的进程语义不能原样套用，但“单一 owner、有界停止、UNCONFIRMED 不写 STOPPED”的结论必须延续。本 ADR 扩展 ADR-0020 的范围到 AVF，不修改 ADR-0020 的原文。

## Decision

1. **Owner 是启动结果的一部分**：`AvfSession` 实现 `RuntimeOwnedSession`，`stopRuntime(graceMs)` 是 close、terminate、session 池退休与退出观察共用的唯一停止入口。调用方不再用 `instanceof` 探测“是否有 owner”。
2. **有界停止协议**：`stopRuntime` 固定顺序为 `平台 stop() → 在 grace 内轮询 status → 释放平台 handle（close）→ 二次轮询`。任何一步只按观测到的 `STOPPED(0)`/`DELETED(2)` 判定成功；平台调用返回或抛异常都不是停止证据。
3. **停止结果**：`CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED`。只有前两者中的 `CONFIRMED` 与 `ALREADY_STOPPED` 允许把元数据写成 STOPPED、释放根盘与启动 guard。`UNCONFIRMED` 保留 owner、RUNNING 元数据、guard 与前台绑定。
4. **幂等与单 owner**：同一 session 的重复 stop/close/terminate 由 session 串行化；已确认的结果直接复用，不产生第二个平台 stop owner。`close()` 必须走 owner 停止，不允许只关通道。
5. **退出分类**：`AvfExitInfo.Kind` 区分 `NORMAL`、`CRASHED`、`DELETED`、`STOP_REQUESTED`、`OBSERVE_FAILED`（网络后端失败的识别在工单 08 的启动契约中定义）。兼容的 `waitFor()` 返回 `exitCode()`：只有 `NORMAL` 与 `STOP_REQUESTED` 为 0，`DELETED` 至少为 2，崩溃与观察失败非 0。
6. **失败清理**：`engine.launch()` 成功后注册失败时，必须对同一 owner 发起有界 stop；确认停止后以普通启动失败返回，未确认时在错误中显式标注 `unconfirmed`，不假装清理完成。
7. **诊断**：`LaunchTimeline` 记录 launch requested、create、run returned、stop requested、stop confirmed/unconfirmed、exit observed；只记录阶段、结果与耗时，不记录 token、口令、网络内容或用户正文。
8. **App 死亡**：AVF VM 由平台管理，App 进程死亡后的 VM 归属不由本 ADR 承诺；设备验收必须单独观察该行为（见工单 09/10 的 not-tested 清单）。本 ADR 只保证宿主侧 close/terminate/池退休不会留下“已确认停止”的假象。

## Consequences

- 正常关闭不再把 AVF VM 留在后台；重复 close 不会重复 stop。
- 停止卡住时返回 UNCONFIRMED，元数据保持 RUNNING，UI 可以明示“停止未确认”，而不是显示 STOPPED。
- DELETED 与非 0 退出不再被当成正常退出；`InstanceLaunchSequence` 会记录 CRASHED 或 STOP_UNCONFIRMED。
- 所有判定都可在 fake SDK/fake host 的 JVM 测试中覆盖；真实 AVF 的 create/run/stop 仍需设备验收，当前成功样本为 0。
- 不新增产品实例状态；UNCONFIRMED 仍由诊断与现有 RUNNING 元数据承载。
- 不改变 QEMU 的 `RuntimeProcessSupervisor` 语义，也不引入 protected/shell-owner。

## Alternatives considered

- **只给 `close()` 补一个 `session.stop()`**：无法区分崩溃、DELETED 与停止请求，也无法处理 stop 抛异常或平台不收敛。
- **把 AVF 强行套进 ADR-0020 的 ProcessHandle**：AVF 没有宿主进程句柄，`destroy/destroyForcibly` 没有对应物，会制造假的“进程已退出”证据。
- **非 owner 继续返回 CONFIRMED/true**：这是本轮要消除的 P0；无法确认就必须如实报告。
