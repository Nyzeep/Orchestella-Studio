# 票 23 实施报告：模式事实、调用身份与诚实的硬件准备入口

- 日期：2026-10-20
- 收尾 Agent：Agent D（票 10 范围）
- 原始实现：`avf/mode-facts` @ `9f7790ef`（已包含在 `ticket/01-ci-full-gate` @ `b9f2be7`）
- 工单：`.scratch/orchestella-avf-shizuku/issues/23-mode-capability-diagnostics.md`
- 状态：completed（零设备代码就绪）；真机行为未验

## 1. 本票交付

1. **模式事实是一个不可变观察**：`AvfCapabilityEvaluator.AvfModeFacts` 保存 protected/non-protected 两个 `VmSupport`，以及 `source`、`actor`、`sampledAtMillis`、`romBinding`。
2. **模式先于权限**：`AvfCapabilityEvaluator` 先看模式，再看两项权限；protected-only 返回 `MODE_UNSUPPORTED`，不可读返回 `MODE_UNKNOWN`，二者都不会进入“待授权”向导。
3. **平台访问有 typed state**：`AvfPlatformFacts.AccessState` 区分 `AVAILABLE` / `HIDDEN_API_BLOCKED` / `PERMISSION_DENIED` / `REFLECTION_UNAVAILABLE` / `MODE_UNSUPPORTED` / `MODE_UNKNOWN`；`ReflectiveAvfPlatformAccess` 是探针与引擎共用的唯一反射 adapter。
4. **UI 诚实展示**：MainActivity、SettingsActivity、SetupWizardActivity 使用同一份 `AvfHostFactsProbe.probe(this)` 结果展示模式/平台访问；QEMU 路径保留，不把模式失败描述成“芯片没有虚拟化硬件”。
5. **没有第二套身份/权限事实**：`AvfPlatformFacts` 只记录 App actor 的权限与能力；没有把 Shizuku/UserService 的权限或 bits 填进 App 行。研究 §3.3 的 App/shell 双 actor 记录由 09 设备验收工具的第 2 阶段在真机时分别采集。
6. **10 的补强**：`AvfPlatformFacts.managerPresent` 让“feature 字符串缺失但 manager 服务存在”的 OEM ROM 进入授权路径，而不是被误报为“设备未声明”；`HIDDEN_API_BLOCKED`/`PERMISSION_DENIED` 的 shape 失败也被视为 manager 存在的证据。

## 2. 行为验收对照

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| protected-only / non-protected-only / both / neither / 读取失败 / 部分 actor 分别明确 | 代码就绪 | `AvfCapabilityEvaluatorTest` 的 `protectedOnlyModeCannotEnterAuthorizationWizard`、`unknownModeIsDistinctFromAuthorization`、`nonProtectedModeStillUsesThePermissionGate`、`platformAccessStatesAreTypedAndActionable`；`AvfPlatformAccessTest` 的 mode/reflection 矩阵 |
| App 权限、Shizuku/US 身份权限与 VM 模式不混填 | 代码就绪 | `ReflectiveAvfPlatformAccess.ACTOR = "app"`；`AvfPlatformFacts` 只含 App 权限；没有 shell/US 值写入 App 行 |
| 模式不满足 adapter 时说明并保留 QEMU，不称芯片无 EL2 | 代码就绪 | `MODE_UNSUPPORTED` 文案“当前系统未声明本应用所需的非保护型虚拟机模式”；MainActivity 仍引导 QEMU |
| onResume/显式复查同一代 facts，旧异步回调不覆新状态 | 代码就绪 | `MainActivity` 在 `onResume`/刷新处同步 `AvfHostFactsProbe.probe(this)` 并整体赋值；没有异步回调写回旧 readiness |
| 全权限但接口不可达/模式 unknown 时不声称 Guest/性能成功 | 代码就绪 | `MODE_UNKNOWN` 分支在 `AvfCapabilityEvaluator` 中先于 READY；`performanceBadge()` 只在 READY 时显示“启动待验证” |
| 已有 client 拒绝/death、双权限/两命令、1CPU 说明不退化 | 通过 | `:runtime:capability:check`、`:runtime:engine-avf:check`；04 的 1 vCPU UI 提示仍由 `SetupWizardActivity` 使用 `AvfLaunchAdmission.cpuSupport` |

## 3. “App 内探针在 hidden API 受限时只能 UNKNOWN”是否解决

**代码路径已解决，真实 ROM 行为仍留设备门。**

- 05 在 `OrchestellaApplication.attachBaseContext` 安装 `HiddenApiBypass 6.1`，早于任何 AVF 类加载；`ReflectiveAvfPlatformAccess` 在第一次平台读之前只执行一次豁免。
- 豁免失败不会再把结果吞成单一 `UNKNOWN`：`ReflectiveAvfPlatformAccess.classify` 把 `SecurityException` 归为 `PERMISSION_DENIED`，把缺失方法/字段/非法访问归为 `HIDDEN_API_BLOCKED`，把缺失类/链接失败归为 `REFLECTION_UNAVAILABLE`，其余归为 `MODE_UNKNOWN`。
- 因此 hidden API 受限时 UI 会显示“hidden API 受限 / 平台接口缺失 / 权限未授予”等可操作状态，而不是“模式未知”。但 **HiddenApiBypass 6.1 在目标 ROM 上是否真的生效**、`getCapabilities` 的 App actor 是否能读到 bit2，只有真机能回答；这些仍是设备门项。
- 10 增加的 `managerPresent` 进一步覆盖“feature 字符串缺失 + 权限未授予但 manager 存在”的 OEM ROM：返回 `NEEDS_AUTHORIZATION`，不是“设备未声明”，同时保持“权限未授予 ≠ READY”。

## 4. 验证

已执行（本机、零设备）：

```text
:runtime:capability:check                 BUILD SUCCESSFUL
:runtime:instance:check                   BUILD SUCCESSFUL
:runtime:engine-avf:check                 BUILD SUCCESSFUL
  AVF_PLATFORM_ACCESS_TEST_OK states=6 manager=2 exemption=2 engine_mapping=3
  AVF_CAPABILITY_MATRIX_AUDIO_TEST_OK ...
  AVF_RUNTIME_OWNERSHIP_TEST_OK ...
```

全量 app 测试/lint/assemble 在 10 的 gate 中执行；结果见 `docs/reports/tickets/avf-zero-device-10/implementation-report.zh-CN.md`。

## 5. 未验项（真机）

- 目标 ROM 的 `HiddenApiBypass 6.1` 实际行为；
- App actor 与 shell/UserService actor 的 `getCapabilities` bits；
- protected-only 设备的真实 `UnsupportedOperationException` 文案/时序；
- ROM binding 的真实值（报告中只保留脱敏绑定，不记录 fingerprint/serial）；
- 页面在真实 onResume/进程重建下的显示。

## 6. 结论

票 23 的零设备部分完成：模式事实、调用身份、typed failure 与 UI 展示一致；hidden API 受限不再只能落到 `UNKNOWN`。**真实 AVF 样本仍为 0；本报告不是设备验收通过。**
