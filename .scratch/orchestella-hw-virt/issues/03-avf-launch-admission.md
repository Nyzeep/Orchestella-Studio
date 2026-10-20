# 03: AVF 启动准入与持久配置迁移

Type: implementation
Status: ready-for-agent
Priority: P0
Owner: AVF launch/config owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

> **并行说明**：本票与 02 都触碰 `AvfInstanceLauncher.java` / `ReflectiveAvfVirtualMachineApi.java`，但没有逻辑依赖；可并行开工，集成时按方法边界解决冲突（02 生命周期、03 配置/admission）。不要为了避冲突而重排/格式化共享文件。

## 用户价值与来源

默认 `VmHardwareConfig` 是 2 vCPU，AVF 适配器只接受 1 vCPU，但拒绝发生在 `AvfInstanceLauncher` 已经 bootstrap rootfs/initramfs 之后；策略链随后回退 QEMU 又 bootstrap 一次。默认 AVF 路径“存在但不可达”，还付两次装配成本。持久 VM 配置用严格相等校验，DNS/内存一变就 `CONFIG_MISMATCH`，没有恢复路径。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §4、§5；`VmHardwareConfig.java:15`；`AvfInstanceLauncher.java:198-211`；`ReflectiveAvfVirtualMachineApi.java:85-90,154-192`；AOSP `VirtualMachineManager.java:356`（`delete`）、`VirtualMachineConfig.java:709-716`（`isCompatibleWith`）。

## What to build / 范围

1. 新增窄 `AvfLaunchAdmission`（建议 `runtime/engine-avf`）：输入 `VmHardwareConfig`/launch request + AVF host facts（non-protected 支持、1 vCPU 能力），输出 `LaunchPlan` 或 typed rejection；**必须在任何 bootstrap 之前调用**。
2. AVF v1 CPU 约束：1 vCPU → `CPU_TOPOLOGY_ONE_CPU`；`cpuCores != 1` → 明确 `HARDWARE_UNSUPPORTED`（推荐）或带记录降为 1 vCPU（二选一，测试固定）。不做 hidden exact-count / MATCH_HOST+nr_cpus。
3. 字段映射必须完整或明确不可表达：
   - memory → `setMemoryBytes`；
   - root disk 大小 → AVF 没有独立 disk-size setter；校验 raw 文件大小或记录 `NOT_EXPRESSIBLE`，不得假装已设置；
   - `gpuAccelerationEnabled` → v1 不支持 AVF GPU；记录 `NOT_EXPRESSIBLE`，能力矩阵标 UNSUPPORTED，不因此拒绝启动（默认配置的 GPU=true 不能变成必败）；
   - `extraKernelArguments` → 追加到 guest cmdline（拒绝换行/控制字符；不记录敏感值）；
   - 其他字段若不能表达，typed 记录，不静默丢弃。
4. 持久 VM 配置差异分类：
   - 先用 AOSP `isCompatibleWith` 判定身份是否兼容；
   - 身份不兼容（protected/debug/payload 等）→ 受控 delete/recreate，保留根盘；若 stop 未确认则拒绝 rebuild；
   - 非身份差异（DNS、vsock 端口、params、内存等）→ 也走 delete/recreate 或明确拒绝，二选一并测试；不得静默复用旧配置或永久 `CONFIG_MISMATCH`。
   - `manager.delete(name)` 只在 VM 已确认停止后调用；删除的是 VM 配置/state，不是用户 root raw。
5. 实例元数据 schema version：`InstanceLifecycle` 的 properties 加版本/迁移；缺 `hardware.cpuCores` 时不得静默变成 2 vCPU/2 GB；部分硬件键/未知键行为明确。
6. 若默认配置被拒，错误必须在 bootstrap 前返回，并让策略链直接走 QEMU（只 bootstrap 一次），或给用户明确“AVF 不支持该配置”的提示。

## 行为验收（零设备）

- [ ] 默认 2 vCPU 配置在 bootstrap 前被 admission 拒绝（或带记录降为 1 vCPU），测试断言 bootstrap 未发生。
- [ ] 1 vCPU + 合法 memory/disk/extra args 得到 `LaunchPlan`，字段与 `AvfLaunchSpec` 一致；无静默丢弃。
- [ ] GPU=true 的默认配置不会导致 AVF 必败；能力矩阵/诊断明确 GPU UNSUPPORTED。
- [ ] 身份不兼容的持久配置 → 受控 rebuild；stop UNCONFIRMED 时拒绝；root raw 未被删除。
- [ ] 非身份差异（改 DNS/端口/内存）有确定路径：rebuild 成功，或 typed rejection；不再永久 `CONFIG_MISMATCH`。
- [ ] 旧元数据缺 hardware 键 → 显式迁移/记录，不静默改变有效硬件；部分/未知键有测试。
- [ ] 策略链不再双 bootstrap；拒绝路径无孤儿/无临时文件泄漏。

## 测试 seam 与 red 命令

- 新增 `AvfLaunchAdmissionTest`（纯 JVM，表驱动：cpu/memory/disk/gpu/extra args/nonprotected facts）。
- 扩展 `AvfVirtualMachineEngineTest` / `ReflectiveAvfVirtualMachineApiTest`：`LaunchPlan` 映射、`isCompatibleWith`、delete/recreate、stop UNCONFIRMED 拒绝。
- App 层：`InstanceStrategyLauncher` 在 bootstrap 前调用 admission 的 fake 测试；断言 bootstrap 计数为 1。
- 元数据：`InstanceLifecycle`/`FileStore` golden fixtures。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check :runtime:instance:check \
    :app:testDebugUnitTest --tests '*AvfLaunchAdmissionTest' \
    --tests '*InstanceStrategyLauncher*' --no-daemon --stacktrace
  ```
- 先 red：默认 2 vCPU 触发 bootstrap（或 `HARDWARE_UNSUPPORTED` 在 bootstrap 后）；DNS 变更 `CONFIG_MISMATCH`；旧元数据静默 2 vCPU/2 GB。

## 失败、数据与兼容

- 不静默改变用户 CPU/RAM/盘；不接受“为了通过把 2 改成 1 但不告知”。
- rebuild 必须保留 root raw；删除前确认 stop；不删用户实例目录。
- 错误信息只含 reason/字段名，不含路径/token/口令/用户正文。
- 兼容旧实例：旧配置可读；迁移有版本；不因新字段丢失旧数据。

## 迁移、回滚与依赖

- 元数据迁移是单向、可回滚到旧读取（保留未知键）；不重写用户根盘。
- 回滚：撤回 admission/迁移代码；保留旧配置读取路径。
- 依赖：不新增第三方库；不依赖 hidden exact-count。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-03/implementation-report.zh-CN.md`。
- 完成 = admission 在 bootstrap 前生效 + 配置差异有路径 + 元数据迁移测试 + 全量 check + 报告明确“真机未验”。

## 不做

- 不做真实 create/run/stop（设备 effort）；不做 protected/GPU/音频/网络数据面；不做 hidden CPU count；不改 QEMU 硬件语义。
