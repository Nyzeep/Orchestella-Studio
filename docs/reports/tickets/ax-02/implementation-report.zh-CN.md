# AX-02 加速车道能力事实与选道策略 实施报告

- 日期：2026-10-23
- Base：`feat/accelerator-batch2 @ a596d73`
- Branch：`feat/accelerator-batch3`
- 工单：`.scratch/orchestella-accelerator/issues/AX-02-加速车道能力事实与选道策略.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动真实 VM。
- CI：**未验证**。只做本地 JVM 验证；PR/CI 尚未运行。

## 1. 结论

零设备范围内**完成**。

- 新增 `AcceleratorFacts`（8 个事实）、`AcceleratorDecision`、`AcceleratorMode`、`AcceleratorReasonCode`、纯函数 `AcceleratorPolicy` 与 `AcceleratorFactProvider` seam。
- 决策只有 `QEMU_ONLY` / `QEMU_PLUS_ACCEL`，每次输出固定 reason code 与 `degradedCapabilities`，不静默降级。
- truth table 16 组，golden JSON 固定序列化；不按设备型号硬编码，不把 protected 当加速器，不重复 AX-01 预算。

## 2. 事实与决策

### 2.1 `AcceleratorFacts`

| 字段 | 含义 |
| --- | --- |
| `avfPresent` | 设备提供 AVF |
| `nonProtectedSupported` | AVF 支持 non-protected VM |
| `protectedOnly` | 设备/镜像只允许 protected；为真时直接拒绝加速 |
| `acceleratorImagePresent` | 加速车道镜像已安装/校验通过 |
| `vsockSupported` | 加速通道可用的 vsock 能力 |
| `compilerTriple` | 加速镜像编译器的 triple（不是宿主 triple） |
| `thermalState` | `NOMINAL` / `LIGHT` / `MODERATE` / `SEVERE` / `CRITICAL` / `UNKNOWN` |
| `batteryState` | `OK` / `LOW` / `CRITICAL` / `CHARGING` / `UNKNOWN` |

宿主编译器 triple 作为 `AcceleratorPolicy.decide(facts, requiredCompilerTriple)` 的第二个参数传入，保证决策是纯函数且比较对象显式。

### 2.2 决策与 reason code

| 条件（按顺序） | mode | reasonCode | degradedCapabilities |
| --- | --- | --- | --- |
| 无 AVF / 无 non-protected / 仅 protected | `QEMU_ONLY` | `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` | `accelerator`, `distcc` |
| 镜像缺失 | `QEMU_ONLY` | `ACCEL_IMAGE_MISSING` | `accelerator`, `distcc` |
| vsock 不可用 | `QEMU_ONLY` | `ACCEL_VSOCK_UNAVAILABLE` | `accelerator`, `distcc` |
| triple 缺失/为空/不匹配 | `QEMU_ONLY` | `COMPILER_MISMATCH` | `accelerator`, `distcc` |
| 热/电预算不允许（含 UNKNOWN） | `QEMU_ONLY` | `THERMAL_BUDGET` | `accelerator`, `distcc` |
| 全部满足 | `QEMU_PLUS_ACCEL` | `ACCEL_AVAILABLE` | 空 |

热预算允许 `NOMINAL` / `LIGHT`；电预算允许 `OK` / `CHARGING`。未知值一律拒绝，避免把“没读到”当成“可用”。

## 3. Truth table

| # | 场景 | mode | reason |
| --- | --- | --- | --- |
| 1 | 全部事实满足 | `QEMU_PLUS_ACCEL` | `ACCEL_AVAILABLE` |
| 2 | 充电中 + 全部满足 | `QEMU_PLUS_ACCEL` | `ACCEL_AVAILABLE` |
| 3 | 热状态 LIGHT + 全部满足 | `QEMU_PLUS_ACCEL` | `ACCEL_AVAILABLE` |
| 4 | 无 AVF | `QEMU_ONLY` | `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` |
| 5 | 仅 protected | `QEMU_ONLY` | `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` |
| 6 | non-protected 不支持 | `QEMU_ONLY` | `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` |
| 7 | 镜像缺失 | `QEMU_ONLY` | `ACCEL_IMAGE_MISSING` |
| 8 | vsock 不支持 | `QEMU_ONLY` | `ACCEL_VSOCK_UNAVAILABLE` |
| 9 | triple 不匹配 | `QEMU_ONLY` | `COMPILER_MISMATCH` |
| 10 | facts triple 为空 | `QEMU_ONLY` | `COMPILER_MISMATCH` |
| 11 | required triple 为空 | `QEMU_ONLY` | `COMPILER_MISMATCH` |
| 12 | 热 MODERATE | `QEMU_ONLY` | `THERMAL_BUDGET` |
| 13 | 热 SEVERE | `QEMU_ONLY` | `THERMAL_BUDGET` |
| 14 | 热 UNKNOWN | `QEMU_ONLY` | `THERMAL_BUDGET` |
| 15 | 电 LOW | `QEMU_ONLY` | `THERMAL_BUDGET` |
| 16 | 电 UNKNOWN | `QEMU_ONLY` | `THERMAL_BUDGET` |

优先级由 `factsPrecedenceIsFixed` 单独验证：无 non-protected > 镜像 > vsock > triple > 热/电。

## 4. Golden JSON

`runtime/fastlane-capability/src/test/resources/accelerator/truth-table-v1.json` 保存 16 组 case。稳定序列化示例：

```json
{"mode":"QEMU_ONLY","reasonCode":"ACCEL_IMAGE_MISSING","degradedCapabilities":["accelerator","distcc"]}
```

键顺序固定为 `mode` → `reasonCode` → `degradedCapabilities`；没有设备 ID、路径、token 或异常文本。

## 5. 改动清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `runtime/fastlane-capability/.../accelerator/AcceleratorFacts.java` | 新增 | 8 个事实 + builder |
| `.../ThermalState.java`、`BatteryState.java` | 新增 | 固定状态词汇 |
| `.../AcceleratorMode.java`、`AcceleratorReasonCode.java` | 新增 | 两种 mode、六个 reason |
| `.../AcceleratorDecision.java` | 新增 | 不可变决策 + 稳定 JSON |
| `.../AcceleratorFactProvider.java` | 新增 | 平台事实 seam |
| `.../AcceleratorPolicy.java` | 新增 | 纯函数选道 |
| `runtime/fastlane-capability/src/test/.../AcceleratorPolicyTest.java` | 新增 | 16 组 truth table + 边界 |
| `runtime/fastlane-capability/src/test/resources/accelerator/truth-table-v1.json` | 新增 | golden JSON |

没有修改 `FastLaneRouter` / `DeviceFacts`、AX-01 预算账本、FLN1 wire、ADR、版本号或 applicationId。

## 6. 验收

| 命令 | 结果 |
| --- | --- |
| `./gradlew :runtime:fastlane-capability:test` | BUILD SUCCESSFUL；`AcceleratorPolicyTest` 112 次执行（16 case × 7 方法）、0 失败；模块合计 144 |
| `python tools/ci/check_ci_module_coverage.py` | 58 passed, 0 failed |

## 7. 偏差与未验证

1. **新增 `ACCEL_VSOCK_UNAVAILABLE`**：工单规则没有单列 vsock，但事实表含 `vsockSupported`；为了避免把“通道不可用”误写成“没有 non-protected”，本票增加一个固定 reason。若维护者要求严格五个 reason，可删除该分支并合并语义。
2. **宿主 triple 作为决策参数**：`AcceleratorFacts` 只保留工单列出的 8 个字段；宿主 triple 不塞进 facts，避免把“设备事实”和“编译要求”混在一起。
3. **未提供真实 Android 实现**：本批按任务书“事实提供者接口 + fake”交付；`AcceleratorFactProvider` 是装配 seam，真实 AVF/热/电读取留给设备阶段。工单原任务的“真实 Android 实现”仍未完成。
4. **未接 UI**、未读取 AX-01 预算、未把 protected 车道当加速器。
5. **not-tested**：真实设备上的 AVF capabilities、thermal/battery 读数、镜像存在性；本票只验证纯函数与 fake。
6. CI 未运行。
