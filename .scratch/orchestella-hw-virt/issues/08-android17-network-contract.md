# 08: Android 17 网络契约

Type: implementation
Status: resolved
Priority: P1
Owner: AVF network owner
Blocked by: 03
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

适配器无条件 `useNetwork(true)`，但 Android 17 的 `crosvm_minimal` 没有 `net` feature：无 GPU 的联网 VM 会被路由到 minimal，`--net` 在参数解析阶段失败（status 35），整台 VM 起不来。Podroid 的修复是声明一个 headless 2D GPU，把 VM 路由到完整 crosvm。我们的 guest 网络配置还只传 DNS，没有 guest IP/DHCP 契约；AVF 网络是否真能通仍未设计完。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §0.6、§4；Podroid issue #44（含 1.2.6 修复说明）；`ReflectiveAvfVirtualMachineApi.java:101`；`AvfLaunchSpec.java:90-105`；`guest-assets/initramfs/init:4152-4195`；`AvfNetworkAssemblyContractTest`。

## What to build / 范围

1. 新增 `AvfNetworkPlan`（建议在 `engine-avf`）：`NONE` / `NATIVE_TAP`，以及可选静态 IPv4 或 DHCP、DNS、是否允许降级。由 admission（03）和 host facts（non-protected/network 支持）决定，不再在适配器里写死 `true`。
2. `ReflectiveAvfVirtualMachineApi` 配置构建：
   - 只有 plan=NATIVE_TAP 才调用 `useNetwork(true)`；
   - 请求网络时尝试声明 headless `GpuConfig`（backend `"2d"`，surfaceless/无 display），让 virtmgr 走完整 crosvm；API 不存在 → `UNSUPPORTED`（benign），调用失败 → `FAILED`（记录并降级/拒绝，不能静默）；
   - 记录 `useNetwork`/`setGpuConfig` 的 typed outcome；不把 outcome 当 guest ready。
3. Android 17 失败签名：
   - fake API 能模拟 VM 在 <1.5s 停止、console 为空、status 35/reason；引擎识别为 `NETWORK_BACKEND_UNAVAILABLE`；
   - 只允许一次无网络重试（如果策略允许），或直接失败并给用户可见原因；禁止无限重试/热循环；
   - 不把 status 35 当正常退出（与 02 的退出分类联动）。
4. Guest 网络契约：
   - `AvfLaunchSpec` 传 `droidlayer.dns=`；
   - 若 plan 是静态 IP，传 `orchestella.guest_ip/gw/mask`（guest init 已有）；
   - 若 plan 是 DHCP，明确 guest 需要 DHCP 客户端/systemd-networkd；不假装静态已配置；
   - 不在本票扩 guest 命令面；不改 ADR-0008 的 IPv6=off。
5. 能力描述符（04）只在 plan 实际可用时声明网络/forward 相关能力；如果网络不可用，terminal/display 仍可启动，用户看到“无网络”而不是整台 VM 失败。

## 行为验收（零设备）

- [ ] `useNetwork` 不再无条件 true；plan=NONE 时 fake API 记录不到 `useNetwork: true`。
- [ ] plan=NATIVE_TAP 时 fake API 记录 `useNetwork: true` + `setGpuConfig: applied`；GpuConfig API 缺失/失败有 typed outcome，不崩溃。
- [ ] guest cmdline 断言：DNS 始终有；静态 plan 有 `orchestella.guest_ip/gw/mask`；DHCP plan 不伪造静态键。
- [ ] fake 模拟 status 35/空 console：识别为网络后端失败；只重试一次或直接失败；不会把 VM 当正常退出。
- [ ] 网络失败时 terminal/display 仍可启动（若策略允许）；UI/诊断有明确原因。
- [ ] 不记录 token/用户网络内容；不扩大 LAN 暴露；IPv6 仍关闭。
- [ ] `AvfNetworkAssemblyContractTest` 扩展为 plan 表驱动，不再只断言 DNS。

## 测试 seam 与 red 命令

- 纯 JVM：`AvfNetworkPlanTest`、`AvfNetworkAssemblyContractTest`、`ReflectiveAvfVirtualMachineApiTest` 新增 GpuConfig/status 35 场景。
- fake API 增加可配置的 `useNetwork`/`setGpuConfig` 记录与 `run` 失败签名；不要依赖真实 Android。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check --no-daemon --stacktrace
  ./gradlew :app:testDebugUnitTest --tests '*Network*' --no-daemon --stacktrace
  ```
- 先 red：plan=NONE 仍 `useNetwork: true`；无 GpuConfig；status 35 被当 0；cmdline 只有 DNS。

## 失败、数据与兼容

- 网络失败可降级为“无网络”，但必须可见；不允许静默黑盒。
- 不把 AVF TAP 当作 QEMU gvproxy 等价物；不承诺端口转发/LAN。
- 不改变 QEMU 网络；QEMU 回归保留。
- 失败诊断只含 stage/reason/status，不含用户网络内容。

## 迁移、回滚与依赖

- 无实例数据迁移；网络 plan 是启动时决策。
- 回滚：恢复 `useNetwork(true)` 旧行为需有 feature flag/记录，不静默；保留 plan 作为测试工具。
- 依赖：不新增第三方库；GpuConfig 通过现有反射，不引入 gfxstream。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-08/implementation-report.zh-CN.md`。
- 完成 = plan/useNetwork/GpuConfig/status 35 的 fake 测试全绿 + cmdline 契约 + 全量 check + 报告明确“真机 TAP/DHCP/apt 未验”。

## 不做

- 不做真机 TAP/DHCP/DNS/apt/端口转发验收；不做 protected 网络（平台拒绝）；不做 LAN 暴露；不做 QEMU gvproxy 改动。

Claimed by: Agent A / 2026-10-20T01:20:19Z

## Answer

Status: resolved（代码就绪；真实 TAP/DHCP/DNS/apt 与 Android 17 crosvm 未验）

- 网络是 plan：新增 `AvfNetworkPlan`（NONE / NATIVE_TAP + DHCP/STATIC + DNS + allowDegrade），`ReflectiveAvfVirtualMachineApi` 只在 plan 启用时调用 `useNetwork(true)`，NONE 记录 `useNetwork: skipped`。
- GpuConfig：懒加载 headless 2D（backend `"2d"`、`setSurfaceless(true)`）；`APPLIED` / `UNSUPPORTED`（benign，仍尝试网络）/ `FAILED`（按 plan 降级为无网络或抛 `NETWORK_BACKEND_UNAVAILABLE`）；不静默。
- status 35：可选的 `getExitStatus()` 观测 + 早停窗口；`AvfVmSession` 归类为 `NETWORK_BACKEND_UNAVAILABLE`，`waitFor()` 返回 35，不是 0。
- cmdline：始终 `droidlayer.dns=`；静态 plan 追加 `orchestella.guest_ip/gw/mask`；DHCP 不伪造静态键；IPv6 仍关闭。
- 降级：GpuConfig FAILED + allowDegrade → VM 继续启动、无网络；notes 有 `gpu-config: failed` 与 `network: degraded`。
- 重试策略：本票选择“直接失败”，没有自动重试/热循环（工单允许）。
- 测试：`AvfNetworkPlanTest` + 扩展 `AvfNetworkAssemblyContractTest`（7 场景）；全量 `:runtime:engine-avf:check`、`*Network*` app 测试、848 条 app 测试全绿。

未验：GpuConfig 真实反射 shape、Android 17 status 35 观测面、真实 TAP/DHCP/DNS/apt、网络失败时 terminal/display 真机可用性。详见 `docs/reports/tickets/avf-zero-device-08/implementation-report.zh-CN.md`。
