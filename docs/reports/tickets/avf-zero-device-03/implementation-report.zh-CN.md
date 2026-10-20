# avf-zero-device-03：AVF 启动准入与持久配置迁移（实施报告）

- 工单：`.scratch/orchestella-hw-virt/issues/03-avf-launch-admission.md`
- Owner：Agent A（生命周期 lane）
- 分支：`ticket/03-avf-launch-admission`，叠加于 `ticket/02-avf-runtime-ownership` @ `dbfd8b9`
- 日期：2026-10-20
- 结论：**代码就绪（fake/JVM 通过）；真实 AVF create/rebuild 未验，真实 AVF 成功样本仍为 0。**

## 目标与完成状态

| 验收项 | 零设备证据 | 尚需验证 |
| --- | --- | --- |
| 默认 2 vCPU 在 bootstrap 前被拒 | `AvfLaunchAdmissionTest.rejectsDefaultTwoVcpuBeforeAnyPlan`；`AvfLaunchBootstrapOrderTest.rejectedDefaultConfigDoesNotBootstrap`（bootstrap 计数 0） | 真机 2 vCPU 拒绝路径 |
| 1 vCPU + memory/disk/extra args 得到 plan | `acceptsOneVcpuAndMapsEveryField`；`admittedConfigBootstrapsExactlyOnce` | 真机 `setMemoryBytes` |
| GPU=true 不导致必败 | `field("gpuAccelerationEnabled") == NOT_EXPRESSIBLE` + 诊断 | 真机无 GPU 配置的 create/run |
| disk 无独立 setter | `NOT_EXPRESSIBLE` 且记录 `observedRootVolumeBytes` | 真机 raw 大小与 guest 根盘一致性 |
| extra args 拒绝控制字符 | `rejectsControlCharactersInExtraArguments`；`AvfLaunchSpec.Builder.extraKernelArguments` 二次校验 | 真机 cmdline 实际生效 |
| 身份不兼容 → 受控 rebuild | `AvfPersistentConfigTest.identityIncompatibleStoredConfigRebuilds`（fake protected 配置） | 真机 `isCompatibleWith` 语义 |
| 内存/DNS/端口等非身份差异有确定路径 | `memoryChangeRebuildsAndKeepsRootRaw`；`ReflectiveAvfVirtualMachineApiTest` 11 类字段差异均 delete/create | 真机 delete/create 与 root raw |
| stop UNCONFIRMED / RUNNING 时拒绝 rebuild | `runningVmRefusesRebuild`（`VM_STOP_UNCONFIRMED`，delete=0）；`runningPersistentVmIsNotStoppedByAnotherLaunch` | 真机 RUNNING status |
| `manager.delete` 只在确认停止后 | rebuild 分支先读 status==0 再 delete；测试断言 delete 计数 | 真机 delete 是否触碰用户 raw |
| 旧元数据缺 hardware 键 → 显式迁移 | `InstanceMetadataMigrationTest.legacyMetadataIsMarkedAndKeepsItsEffectiveHardware` | 真机旧实例目录 |
| 部分硬件键 fail closed | `partialHardwareBlockFailsClosedInsteadOfDefaulting` | — |
| 未知键不改变有效硬件 | `unknownKeysDoNotChangeEffectiveHardware` | — |
| 策略链不再双 bootstrap | `prepare()` 在 bootstrap lambda 前完成 admission；拒绝时计数 0 | QEMU 回退的 bootstrap 仍由既有 QEMU 路径执行（未改） |

## 修改模块

- 新增 `runtime/engine-avf/.../AvfLaunchAdmission.java`：`evaluate(config, hostFacts, rootVolumeBytes)` → `Plan` 或 typed `Rejection`；1 vCPU 硬约束、字段 `EXPRESSED/NOT_EXPRESSIBLE`、控制字符拒绝、null config 的保守 512 MB 路径。
- 新增 `AvfHostFacts`：`SUPPORTED/UNSUPPORTED/UNKNOWN`；只有明确的 UNSUPPORTED 提前拒绝，UNKNOWN 交给平台 adapter。
- `AvfLaunchSpec`：新增 `extraKernelArguments`，追加到 guest cmdline（在 9p 端口之后、DNS 之前），拒绝空白/控制字符。
- `AvfEngineException`：新增 `INVALID_LAUNCH_CONFIG`。
- `ReflectiveAvfVirtualMachineApi`：
  - 复用前用 `isCompatibleWith` + 全字段比较；匹配且已停止才 reuse，匹配但 RUNNING 仍按原契约拒绝；
  - 不匹配 → 先确认 status==0，再 `manager.delete(name)` → `create` → `run`；
  - `loadShape` 增加 `VirtualMachineManager.delete(String)`。
- `AvfInstanceLauncher`：新增 `prepare(parameters, hostFacts, BootstrapStep)`，admission 在唯一一次 bootstrap 之前；`launch` 使用 plan 的 memory/cpu/extra args；`hostFacts(context)` 把 UNKNOWN 如实传递。
- `VmHardwareConfig`：新增 `isLegacyDefaults` provenance（不参与 equals；用户编辑通过 5 参构造器自动清除）。
- `InstanceLifecycle`：写 `metadata.schemaVersion=1` 与 `hardware.legacyDefaults`；读时 schema 0 + 无 hardware 块 → 显式 legacy 迁移（保留 2 vCPU/2048 MB 有效值）；部分硬件块/缺块 schema 1 → 拒绝；未知 schema 版本 → 拒绝。
- 测试：新增 `AvfLaunchAdmissionTest`、`AvfPersistentConfigTest`、`AvfLaunchBootstrapOrderTest`、`InstanceMetadataMigrationTest`；重写 `ReflectiveAvfVirtualMachineApiTest` 的 persistent mismatch 用例为 rebuild 语义。

## red → green 证据

Red（实现前，实际执行）：

```bash
./gradlew :runtime:engine-avf:compileTestJava --no-daemon
# BUILD FAILED：AvfLaunchAdmission/AvfHostFacts 不存在

./gradlew :runtime:instance:test --tests '*InstanceMetadataMigrationTest' --no-daemon
# BUILD FAILED：VmHardwareConfig.isLegacyDefaults 不存在

./gradlew :app:testDebugUnitTest --tests '*AvfLaunchBootstrapOrderTest' --no-daemon
# BUILD FAILED：AvfInstanceLauncher.prepare / AvfHostFacts 不存在
```

行为 red：旧 `ReflectiveAvfVirtualMachineApi` 对内存等差异抛 `CONFIG_MISMATCH`（12 个变体），新契约要求 stop 确认后 delete/create；旧 `Instance.fromProperties` 在缺 hardware 时静默 `defaultConfig()`（2 vCPU/2 GB）。

Green（实际执行）：

```bash
./gradlew :runtime:engine-avf:check :runtime:instance:check --no-daemon --stacktrace
# BUILD SUCCESSFUL
# AVF_LAUNCH_ADMISSION_TEST_OK cpu=1 default_reject=1 memory=1 disk=not_expressible
#   gpu=not_expressible extras=1 facts=3 null_config=1 cmdline=1
# AVF_PERSISTENT_CONFIG_TEST_OK memory_rebuild=1 identity_rebuild=1 running_refusal=1
#   root_raw_preserved=1
# REFLECTIVE_AVF_API_TEST_OK ... config_rebuild=11 config_mismatch=1 running_vm_refusal=1

./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
# BUILD SUCCESSFUL；failures=0 errors=0 skipped=0
```

## Zero-device 状态

- admission、bootstrap 计数、persistent rebuild、root raw 保留、metadata schema/迁移全部在 JVM/fake SDK 上通过。
- `:runtime:engine-avf:check`、`:runtime:instance:check`、`:app:testDebugUnitTest` 全绿。
- 未连接 adb、未 pm grant、未启动真实 AVF。

## 未验项（真机）

- 真机 `VirtualMachineConfig.isCompatibleWith` 的实际身份语义，以及 protected/debug/payload 差异分类。
- 真机 `VirtualMachineManager.delete(name)` 是否只删 VM 配置/state、不触碰用户 raw 根盘（fake 只验证了路径未被本 adapter 删除）。
- 真机 RUNNING 状态下的拒绝路径、delete/create 时序、AVF 配置持久化。
- 真机 root raw 大小与 guest 根盘实际容量的一致性。
- 真机旧实例元数据迁移（本次只有 Properties fixture）。

## 风险、取舍与回滚

- **行为变更**：非身份差异从 `CONFIG_MISMATCH` 改为受控 rebuild。这是工单要求；rebuild 会删除平台 VM 配置/state，因此必须先确认 STOPPED。真机 delete 的副作用是本票最大未验风险。
- **1 vCPU 硬拒绝**：默认 2 vCPU 的 AVF 路径现在直接失败并在策略链回退 QEMU，不再双 bootstrap；用户会看到 AVF 不可用而不是 1 vCPU 的静默降级。
- **GPU**：默认 `gpuAccelerationEnabled=true` 只记录 NOT_EXPRESSIBLE，不拒绝启动；能力仍标 UNSUPPORTED。
- **元数据**：legacy 迁移保留历史有效值 2 vCPU/2048 MB 并记录 provenance；部分块拒绝加载，避免“补默认值”。未知 schema 版本拒绝，避免降级覆盖。
- **契约类型**：`VmHardwareConfig` 新增 provenance 字段，QEMU 与 UI 的 equals 行为不变（该字段不参与 equals）；旧 5 参构造器兼容。
- 回滚：撤回 admission/rebuild/schema 代码与测试；QEMU 路径未改，旧元数据仍可按旧读取路径解析（schema 0 无 hardware 块会迁移为 legacy 标记）。
- 环境：本机使用 Temurin 17 + Android SDK 36/build-tools 36.1.0/NDK 27；Maven Central 429，使用仓库已有 Aliyun mirror 开关。未进入提交。
- 工作树原有未跟踪 `*.rej` 未删除、未纳入提交。
