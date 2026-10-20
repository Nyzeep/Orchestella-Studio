# avf-zero-device-08：Android 17 网络契约（实施报告）

- 工单：`.scratch/orchestella-hw-virt/issues/08-android17-network-contract.md`
- Owner：Agent A（生命周期 lane）
- 分支：`ticket/08-android17-network-contract`，叠加于 `ticket/03-avf-launch-admission` @ `f0708f3`
- 日期：2026-10-20
- 结论：**代码就绪（fake/JVM 通过）；真实 AVF TAP/DHCP/DNS/apt 与 Android 17 `crosvm_minimal` 未验。**

## 目标与完成状态

| 验收项 | 零设备证据 | 尚需验证 |
| --- | --- | --- |
| `useNetwork` 不再无条件 true | `AvfNetworkAssemblyContractTest.noneNeverCallsUseNetworkTrue`（config 无网络、无 `useNetwork: applied`） | 真机 NONE 启动 |
| TAP 时 `useNetwork: true` + GpuConfig applied | `tapDhcpEnablesNetworkAndAppliesHeadlessGpu`（backend=2d，notes 双 outcome） | 真机 `setGpuConfig` 反射 shape |
| GpuConfig API 缺失 benign / 失败 typed | `gpuUnsupportedApiIsBenign`；`gpuFailureRefusesWhenDegradationIsDisabled`；`gpuFailureDegradesToNoNetworkWhenAllowed` | 真机 API 真实方法名与行为 |
| Guest cmdline：DNS 始终有 | 所有 plan 场景断言 `droidlayer.dns=`；`AvfLaunchSpec.appendNetworkKeys` | 真机 guest 解析 |
| 静态 plan 有 guest_ip/gw/mask | `staticPlanCarriesGuestAddressingKeys` | 真机 guest 静态地址配置 |
| DHCP 不伪造静态键 | `tapDhcpEnables...` + `AvfNetworkPlanTest.dhcpDeclaresNoStaticKeys` | 真机 DHCP 客户端/systemd-networkd |
| status 35 识别为网络后端失败，非正常退出 | `status35IsNeverANormalExit`（kind=NETWORK_BACKEND_UNAVAILABLE，code=35，`waitFor()!=0`） | 真机 crosvm 退出码面 |
| 只重试一次或直接失败 | 本票选择**直接失败**（见下）；无重试循环 | 策略若改为重试需另实现 |
| 网络失败仍可启动 terminal/display（策略允许） | `gpuFailureDegrades...` 启动成功、网络关闭 | 真机 terminal/display 仍可用 |
| `AvfNetworkAssemblyContractTest` 表驱动 | 7 个 plan/outcome 场景 | — |

## 修改模块

- 新增 `AvfNetworkPlan`：`NONE` / `NATIVE_TAP` + `DHCP`/`STATIC` + DNS + `allowDegradeToNone`；IPv4 校验；NONE 不携带地址；DHCP 不携带静态键。
- 新增 `AvfNetworkFailure`：Android 17 签名（network requested + status 35 + <1.5 s + 空 console）。
- `AvfLaunchSpec`：新增 `networkPlan`；默认（未显式设置）保持 TAP+DHCP 以兼容旧调用方；cmdline 始终写 `droidlayer.dns=`，静态 plan 追加 `orchestella.guest_ip/gw/mask`，DHCP 不写。
- `ReflectiveAvfVirtualMachineApi`：
  - `useNetwork` 由 plan 驱动；NONE 记录 `useNetwork: skipped`；
  - 懒加载 `VirtualMachineCustomImageConfig$GpuConfig$Builder`，`setBackend("2d")` + 可选 `setSurfaceless(true)` + `setGpuConfig`；
  - `GpuConfigOutcome` = `APPLIED` / `UNSUPPORTED`（benign，仍尝试网络）/ `FAILED`（按 plan 降级为无网络或抛 `NETWORK_BACKEND_UNAVAILABLE`）；
  - 可选 `getExitStatus()` 观测；status 35 不折叠为 0。
- `AvfVirtualMachineEngine.AvfVmSession`：network plan 启用且 status 35 且早停 → `NETWORK_BACKEND_UNAVAILABLE`。
- `AvfExitInfo`：新增 `elapsedMs` 与网络后端 kind。
- `AvfHostFacts`：新增 `nativeTap` 事实（默认 UNKNOWN）；`AvfLaunchAdmission.Plan` 新增 `networkIntent`，明确 UNSUPPORTED → NONE，其余 NATIVE_TAP。
- `AvfInstanceLauncher`：从 admission 的 `networkIntent` + host DNS 生成 `AvfNetworkPlan`；UNKNOWN 主机尝试 TAP 并允许降级。
- 测试 fixture：fake `GpuConfig`/`setGpuConfig`/`getExitStatus`，供纯 JVM 场景使用。

## red → green 证据

Red（实现前，实际执行）：

```bash
./gradlew :runtime:engine-avf:compileTestJava --no-daemon
# BUILD FAILED：AvfNetworkPlan/AvfNetworkFailure/GpuConfig 不存在（10+ 符号错误）
```

行为 red（旧实现）：`useNetwork(true)` 无条件调用；无 GpuConfig；status 35 被当正常退出（旧 `waitFor` 对 status 0/2 返回 0）。

Green（实际执行）：

```bash
./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
# BUILD SUCCESSFUL
# AVF_NETWORK_PLAN_TEST_OK none=1 dhcp=1 static=1 validation=1 status35=1
#   early_stop=1 console_observed=1
# AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_OK plan=3 use_network=1 gpu_applied=1
#   gpu_failed=2 gpu_unsupported=1 status35=1 dns=1 static_keys=1 dhcp_no_static=1

./gradlew :app:testDebugUnitTest --tests '*Network*' --no-daemon
# BUILD SUCCESSFUL

./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
# BUILD SUCCESSFUL；848 tests / 107 suites，failures=0 errors=0 skipped=0
```

## Zero-device 状态

- plan 三态、useNetwork 条件化、GpuConfig outcome、降级/拒绝、cmdline 契约、status 35 分类全部在 fake SDK/fake vsock 上通过。
- `:runtime:engine-avf:check` 与 `:app:testDebugUnitTest` 全绿。
- 未连接 adb、未启动真实 AVF、未做真实 TAP/DHCP/apt。

## 未验项（真机）

- **GpuConfig 的真实反射 shape**：本实现假设 `VirtualMachineCustomImageConfig$GpuConfig$Builder.setBackend(String)` 与 `setSurfaceless(boolean)` 存在；方法名/构造器若不匹配会走 `UNSUPPORTED`（benign），但 workaround 就不会生效。
- Android 17 `crosvm_minimal` 的真实 status 35 观测面（`getExitStatus()` 是否存在、返回什么）。
- 真实 TAP 是否可见、DHCP 客户端/systemd-networkd 行为、DNS 解析、apt。
- 网络失败时 terminal/display 的真机可用性。
- NONE plan 的真机启动（本环境只有 fake）。

## 风险、取舍与回滚

- **最大风险是 GpuConfig 反射 shape 未在真机确认**。所有缺失/失败都有 typed outcome，不会崩溃；但 workaround 是否真的把 VM 路由到完整 crosvm 只能设备验证。
- **直接失败而非自动重试**：status 35 是异步退出，自动无网络重试需要重跑 launch 与 UI 状态机；本票选择工单允许的“直接失败”路径，没有重试循环/热循环。若要重试，需在 exit watcher 层实现一次 NONE 重跑，并复用 `AvfNetworkPlan.none`。
- **host 的 nativeTap 事实**：当前没有生产探针，默认 UNKNOWN → 尝试 TAP + 允许降级。明确的 UNSUPPORTED 只在测试中构造；后续设备/平台层可接 `AvfHostFacts.withNativeTap`。
- 默认 `AvfLaunchSpec`（未设置 plan）仍为 TAP+DHCP，避免旧调用方行为回归；app 生产路径显式设置 plan。
- 未改 ADR-0008 的 IPv6=off；未改 QEMU 网络；未新增第三方库。
- 回滚：撤回 plan/GpuConfig/status35 代码与测试，恢复无条件 `useNetwork(true)`；需在报告中显式记录，不静默。
- 环境：Temurin 17 + Android SDK 36/build-tools 36.1.0/NDK 27；Aliyun mirror（Maven Central 429）。未进入提交。
