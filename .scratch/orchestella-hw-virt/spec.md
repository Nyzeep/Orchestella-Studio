# Spec: AVF 零设备硬化

Date: 2026-10-19
Base: `avf/zero-device-hardening`（从 `avf/mode-facts` @ `9f7790e`）
Map: `map.md` · Handoff: `handoff.md`

## 问题

AVF 引擎已经写完，但生产接线残缺：正常关闭可能不停 VM；非 owner 被当作已确认停止；注册失败泄漏 VM；退出码/删除/崩溃不可区分；默认 2 vCPU 在 bootstrap 后才拒绝；能力矩阵五处不一致；9p/音频/转发是死路径；hidden API 探针与引擎各有一套反射；Android 17 网络会被 `crosvm_minimal` 拒绝。当前设备 protected-only，真机验收必须等用户提供 non-protected 设备。

## 目标

在**零设备**条件下，把 AVF 推进到可交接的代码就绪状态：

1. 所有权/停止/退出可信：close/terminate/stop/注册失败都收敛；UNCONFIRMED 不写 STOPPED；崩溃/DELETED 不返回 0。
2. 启动准入在 bootstrap 前：默认配置要么被接受，要么 typed 拒绝；字段不静默丢失；持久配置差异有受控迁移。
3. 能力真相单一：UI/session/engine/描述符一致；未接线能力不出现；QEMU 桌面 HUD 修复。
4. 平台访问单一接缝：hidden API 豁免、权限、模式、反射 shape/failure 一套；App-owner 默认。
5. 数据面诚实：RFB 显示为唯一活路径；9p 接线或 UNSUPPORTED；forward/audio/clipboard 明确状态；Android 17 网络 plan/GpuConfig/status 35 可识别。
6. CI 能阻塞回归；设备验收工具可 dry-run，输出全 `not-tested`。

## 非目标

- 真机 create/run/vsock/stop/network/9p/display 验收、性能、发布、合并 main。
- protected 产品线、shell-owner/UserService/ACR-01、H5 QEMU rig。
- QEMU 新功能、QEMU 发布签名/迁移、Android 17 全量适配。
- hidden exact-count CPU、MATCH_HOST+nr_cpus、GPU/音频硬件加速。

## 架构决定

- **Owner 是启动结果的一部分**：`RuntimeOwner` 窄接缝；非 owner 不是 CONFIRMED。
- **能力从接线派生**：`SessionCapabilities` → `CapabilityMatrix`；UI 只读 session。
- **准入先于装配**：`AvfLaunchAdmission` 在 bootstrap 前给 plan/rejection。
- **平台访问一个 adapter**：`AvfPlatformAccess` 拥有 hidden API/权限/模式/反射失败；豁免只装一次。
- **数据面一个装配点**：RFB 显示 live；9p/forward/audio/clipboard 要么接线要么 UNSUPPORTED。
- **协议命名诚实**：RFB 不是 Wayland；能力枚举区分 `GRAPHICS_RFB`/`DISPLAY` 与 `GRAPHICS_WAYLAND`，不得把 RFB 映射成 Wayland。
- **网络是 plan，不是布尔**：`NONE`/`NATIVE_TAP` + 静态/DHCP + GpuConfig workaround + 失败降级。
- **AVF v1 = 1 vCPU、App-owner、non-protected、RFB、terminal/bridge**；其余能力按实际接线声明。

## 工单图

```
01 CI ─────────────────────────────┐
02 ownership ──────────────────────┤
03 admission ──► 08 network ───────┤
04 capability ──► 06 display/audio/clipboard ──► 07 9p/forward ──┤
05 platform access ────────────────┤
09 device harness dry-run ─────────┤
                                    └──► 10 integration/report
```

初始 frontier：01、02、03、04、05、09（02/03 共享文件但无逻辑依赖）。详见 `map.md`。

## 零设备 Definition of Done

- 每票的 fake/JVM/契约测试 red→green；
- 集成分支全量 runtime checks + app tests + lint + assemble 绿；
- 架构守卫有效（故意回归会红）；
- 报告明确区分“fake/JVM 通过”和“真机未验”；
- 设备验收工具 dry-run 可运行，所有设备步骤 `not-tested`；
- 没有 AVF 成功/性能/发布声明。

## 设备门（不在本 effort）

用户提供一台声明 non-protected 的设备后，按 `docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §3.3 与工单 09 执行。候选：Pixel 6 Pro、Pixel Tablet、Pixel 10/11；Pixel 8a/8 Pro 有已知问题；MT6991/GenieZone 需自验。必须实测 `getCapabilities() & 2`、两权限、`setProtectedVm(false)`、create/run/vsock/stop。

## 风险

- HiddenApiBypass 6.1 在目标 ROM 失效 → 设备门失败后评估 shell-owner，或停 AVF。
- Android 17 `crosvm_minimal` 网络修复因 ROM 而异 → 设备门验证；不能联网则 AVF 不做 Full Linux 承诺。
- 1 vCPU / RFB-only / 无音频/转发 → 产品契约需用户确认。
- 共享文件并行冲突 → 集成分支解决；每票最小改动。
