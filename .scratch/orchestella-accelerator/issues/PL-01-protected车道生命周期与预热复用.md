# PL-01: protected 车道生命周期与预热复用

- Type: implementation
- Status: ready-for-human
- Priority: P1
- Owner: protected lane owner
- Blocked by: 无（零设备）
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

protected 车道当前每次运行都是 create → run → payloadReady → preflight → stop → delete。2026-10-21 的真机 20/20 cycles 已证明 stop/delete 收敛，但每次冷建 VM 的固定开销（create、pvmfw 校验、payload 就绪）没有单独计量。`execute_no_trans` 未解除前 terminal 仍 blocked，但 control/vsock/stop 可用，可以把“预热复用”做成独立的机制优化，为平台解封后的 Fast Lane 与交火控制面争取时间。

## 目标

- 在 app 进程内维护一个有界的 protected VM 预热池（默认容量 0，显式开关打开后为 1）。
- 复用时不重复 create；每次 acquire 仍做健康检查，失败则丢弃并冷建。
- 所有状态、耗时、回收原因可观测；不得伪造 `RUNNING`/`PAYLOAD_READY`。

## 任务

- [x] 定义 `ProtectedWarmPool` 状态机：`IDLE` / `CREATING` / `READY` / `LEASED` / `DRAINING` / `FAILED`。
- [x] 定义租约：单池容量、空闲 TTL、最大等待时间、重复 release 的幂等语义。
- [x] 定义预算：池内存上限、与 QEMU 运行时并存的准入接口（与 AX-01 共用，不重复实现预算逻辑）。
- [x] acquire 健康检查：一次 control `HELLO/HELLO_OK`，2 秒超时；失败分类 `HELLO_FAILED` 并冷建一次，第二次失败直接上报。
- [x] 计数与耗时：`cold_creates`、`reuses`、`create_ms`、`ready_ms`、`evictions`、`evict_reason`；只写计数与固定 code，不写路径/原始日志。
- [x] 停止路径复用 `RuntimeProcessSupervisor`，禁止新增第二套 stop owner；池关闭/进程死亡时执行有界 drain。
- [x] 失败分类：`CREATE_FAILED` / `READY_TIMEOUT` / `HELLO_FAILED` / `EVICTED` / `STOP_UNCONFIRMED`。
- [x] fake AVF API + JVM 测试：复用、TTL 过期、预算拒绝、健康检查失败、崩溃恢复、双 release、drain 超时。
- [x] 文档：容量/内存换算表、开关、失败回退、与 AX-01 的边界。

## 验收命令

```bash
./gradlew :runtime:engine-avf:test :app:testDebugUnitTest --tests '*WarmPool*'
python tools/ci/check_ci_module_coverage.py
```

以上不启动设备、不调用 adb、不创建真实 VM。

## 边界

- 不改 exec 语义、不改 SELinux/pvmfw、不改 FLN1 wire。
- 不把预热池默认打开；默认容量 0，需显式配置。
- 不把 `HELLO_OK` 当作 terminal 可用；terminal 仍按 DV-01A 的 blocked 结论处理。

## 证据

- 测试清单与通过计数写入实施报告 `docs/reports/tickets/pl-01/implementation-report.zh-CN.md`。
- 真机项全部标 `not-tested`；预热池的真机收益待 DV-ACC。
