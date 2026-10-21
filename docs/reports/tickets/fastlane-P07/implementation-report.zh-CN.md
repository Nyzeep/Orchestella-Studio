# P07 实现报告：Fast Lane 能力矩阵与 Lane Router（零设备）

日期：2026-10-21 ｜ 票：[P07-capability-router.md](../../../../.scratch/orchestella-fastlane/issues/P07-capability-router.md) ｜ 分支：`feature/fastlane-runtime`

## 1. 目标与边界

建立 Fast Lane 的唯一能力事实源与纯函数选道逻辑：能力矩阵、`FULL/FAST/UNSUPPORTED`、typed reason、保守回退、明确“Fast Lane 不是 Full Linux”。

本票是**零设备**交付：纯 Java 数据类 + 纯函数 truth table；不接 Android UI、不接真实设备能力探测、不改 QEMU Full Lane 默认。真实设备 fact 由 DV-01 读取，产品集成由 DV-04 验证。

## 2. 设计接缝

- `FastLaneCapabilities`：不可变能力矩阵；`v1()` 是 canonical 声明，`none()` 是不可用矩阵，`project(DeviceFacts)` 把声明收窄到本设备实际可用能力。
- `DeviceFacts`：三态 `Support {YES,NO,UNKNOWN}` 的设备观察；缺读是 `UNKNOWN`，不是 yes。
- `RequirementProfile`：显式需求；`null` 表示按 `Workload` 取默认需求，显式 `none()` 表示不继承默认。
- `Workload`：`INTERACTIVE_SHELL/COMPILE/FULL_LINUX/UNKNOWN`。
- `FastLaneRouter.decide(facts, workload, requirements)`：纯函数，无 I/O、无状态。
- `FastLaneDecision`：lane + typed reason + effective capabilities + 解释文案。
- `FastLaneCopy`：中文徽章/限制文案数据；不依赖 Android 资源。

## 3. 能力矩阵（v1）

| 能力 | v1 | 说明 |
| --- | --- | --- |
| `terminal` | true | PTY + vsock 多路复用 |
| `hardwareCpu` | true | protected pVM vCPU；1 vCPU 起步 |
| `encryptedStorage` | true | `/mnt/encryptedstore` |
| `sealedToolbox` | true | APK `bin/` 内密封工具 |
| `tcpProxy` | true | 宿主 SOCKS5/HTTP CONNECT |
| `exportImport` | true | guest tar ↔ SAF；依赖 encrypted storage |
| `distccOffload` | true | 实验性 QEMU→Fast Lane 编译 |
| `nativeNetwork` | false | 无 `eth0`/NAT/DNS/端口转发 |
| `nativePackageInstall` | false | 不能从 encrypted storage 执行新二进制 |
| `docker` | false | 无 PID1/namespace/服务管理 |
| `systemd` | false | 同上 |
| `gpu` | false | 无 graphics HAL；display=terminal only |

`project(DeviceFacts)` 的收窄规则：

- Fast Lane 不可用（AVF 非 YES、protected 非 YES、权限非 YES、hidden API 非 NO）→ `none()`。
- 可用时：`encryptedStorage`/`exportImport` 取决于 encrypted storage fact；`sealedToolbox`/`tcpProxy`/`distccOffload` 取决于对应 fact；`terminal`/`hardwareCpu` 为 true；五项 Full-Linux-only 能力恒 false。
- `exportImport` 不能脱离 encrypted storage 单独为 true；这是本轮修正的一个 capability 虚报。

## 4. Router 规则与优先级

`decide` 的实际顺序：

1. `workload == FULL_LINUX` 或 `requirements.requiresFullLinux()`（systemd/docker/nativeNetwork/gpu/packageInstall）→ `FULL + REQUIRES_FULL_LINUX`。显式 Full Linux 需求优先于未知 workload。
2. `workload == UNKNOWN`（含 null）→ `FULL + UNKNOWN_WORKLOAD`。
3. `hiddenApiBlocked == YES` → `FULL + HIDDEN_API_BLOCKED`。
4. `managePermissionGranted == NO` → `FULL + PERMISSION_DENIED`。
5. `avfPresent == NO` → `FULL + NO_AVF`。
6. `protectedVmSupported == NO` → `FULL + PROTECTED_UNAVAILABLE`。
7. 任一关键 fact 仍为 UNKNOWN → `FULL + UNKNOWN_DEVICE`。
8. 显式能力缺口：storage/exportImport → `STORAGE_UNAVAILABLE`；proxy → `PROXY_UNAVAILABLE`；toolbox → `TOOLBOX_UNAVAILABLE`；distcc → `DISTCC_UNAVAILABLE`。
9. 全部满足 → `FAST + FAST_LANE_AVAILABLE`。

`fallback` 的规则：

- `fullLaneAvailable == NO` → `UNSUPPORTED + 触发原因`。
- `fullLaneAvailable` 为 YES 或 UNKNOWN → `FULL + 触发原因`。UNKNOWN 按保守策略保留 Full Lane。

**不静默切换**：任何非 FAST 决策都带 typed reason 和中文解释；`FastLaneDecision` 拒绝 `FAST + 非 FAST_LANE_AVAILABLE` 以及 `非 FAST + FAST_LANE_AVAILABLE` 的矛盾组合。

## 5. 完整 truth table

`R` 列是显式 requirements；`null` 表示按 workload 取默认。

| # | Device facts | Workload | R | Lane | Reason |
| --- | --- | --- | --- | --- | --- |
| 1 | protectedOnly（AVF/protected/permission/storage/proxy/toolbox/distcc 全 YES、hidden NO、full lane YES） | INTERACTIVE_SHELL | null | FAST | FAST_LANE_AVAILABLE |
| 2 | protectedOnly | COMPILE | null | FAST | FAST_LANE_AVAILABLE |
| 3 | protectedOnly | COMPILE | compileWithDistccOffload | FAST | FAST_LANE_AVAILABLE |
| 4 | protectedOnly | FULL_LINUX | none | FULL | REQUIRES_FULL_LINUX |
| 5 | protectedOnly | INTERACTIVE_SHELL | systemd=true | FULL | REQUIRES_FULL_LINUX |
| 6 | protectedOnly | UNKNOWN | systemd=true | FULL | REQUIRES_FULL_LINUX（显式需求优先） |
| 7 | noAvf（full lane YES） | INTERACTIVE_SHELL | null | FULL | NO_AVF |
| 8 | noAvf + fullLane UNKNOWN | INTERACTIVE_SHELL | null | FULL | NO_AVF（保守） |
| 9 | noAvf + fullLane NO | INTERACTIVE_SHELL | null | UNSUPPORTED | NO_AVF |
| 10 | hiddenApiBlocked + full lane YES | INTERACTIVE_SHELL | null | FULL | HIDDEN_API_BLOCKED |
| 11 | hiddenApiBlocked + full lane NO | INTERACTIVE_SHELL | null | UNSUPPORTED | HIDDEN_API_BLOCKED |
| 12 | permissionDenied | INTERACTIVE_SHELL | null | FULL | PERMISSION_DENIED |
| 13 | protectedVmSupported=NO | INTERACTIVE_SHELL | null | FULL | PROTECTED_UNAVAILABLE |
| 14 | 全 UNKNOWN | INTERACTIVE_SHELL | none | FULL | UNKNOWN_DEVICE |
| 15 | protectedOnly | UNKNOWN | null | FULL | UNKNOWN_WORKLOAD |
| 16 | protectedOnly | null | null | FULL | UNKNOWN_WORKLOAD |
| 17 | encryptedStorage=NO | INTERACTIVE_SHELL | null | FULL | STORAGE_UNAVAILABLE（默认需求含 storage） |
| 18 | encryptedStorage=NO | INTERACTIVE_SHELL | exportImport=true | FULL | STORAGE_UNAVAILABLE |
| 19 | encryptedStorage=UNKNOWN | INTERACTIVE_SHELL | null | FULL | UNKNOWN_DEVICE |
| 20 | tcpProxy=NO | INTERACTIVE_SHELL | null | FAST | FAST_LANE_AVAILABLE（proxy 不是默认需求） |
| 21 | tcpProxy=NO | INTERACTIVE_SHELL | tcpProxy=true | FULL | PROXY_UNAVAILABLE |
| 22 | sealedToolbox=NO | COMPILE | compile | FULL | TOOLBOX_UNAVAILABLE |
| 23 | distcc=NO | COMPILE | compileWithDistccOffload | FULL | DISTCC_UNAVAILABLE |
| 24 | distcc=UNKNOWN | COMPILE | compileWithDistccOffload | FULL | UNKNOWN_DEVICE |
| 25 | protectedOnly + fullLane NO | INTERACTIVE_SHELL | null | FAST | FAST_LANE_AVAILABLE（Fast 不依赖 Full） |
| 26 | protectedOnly + fullLane NO | FULL_LINUX | null | UNSUPPORTED | REQUIRES_FULL_LINUX |
| 27 | noAvf + fullLane NO | INTERACTIVE_SHELL | null | UNSUPPORTED | NO_AVF |

补充：

- `Workload.INTERACTIVE_SHELL` 的默认需求是 `terminal + persistentStorage`；`COMPILE` 是 `terminal + persistentStorage + sealedToolbox`；`FULL_LINUX` 是 `terminal + persistentStorage + nativeNetwork + systemd + docker + nativePackageInstall`；`UNKNOWN` 是 `none`。
- 显式传入 `RequirementProfile.none()` 表示“不要求任何可选能力”，不会继承 workload 默认需求；这是调用方的显式选择，不是静默降级。
- `RequirementProfile` 的 `tcpProxy/exportImport/distccOffload` 是可选需求；未要求时对应能力可以 false 而仍选 FAST。
- 规则 7 的 UNKNOWN 检查覆盖 `avfPresent`、`protectedVmSupported`、`managePermissionGranted`、`hiddenApiBlocked`；storage/proxy/toolbox/distcc 的 UNKNOWN 在第 8 步按缺口返回 `UNKNOWN_DEVICE`。

## 6. 与 Full Lane 能力矩阵的关系

- 本模块**不依赖** `runtime/contract`，也不修改 `FullLinuxContract` 或 QEMU 默认；P07 只提供 Fast Lane 的能力数据与选道。
- 概念映射（仅文档，不是代码依赖）：
  - Fast `terminal` ↔ `FullLinuxContract.Capability.TERMINAL`。
  - Fast `hardwareCpu` ↔ `FullLinuxContract.Capability.HARDWARE_ACCELERATION`。
  - Fast `gpu=false`；Full Lane 是否声明 `GPU_HARDWARE_ACCELERATION` 由现有 Full Lane 矩阵决定，本模块不改。
  - Fast 的 `sealedToolbox/tcpProxy/exportImport/distccOffload` 是 Fast Lane 策略专属增强，不升级进 Full Linux 契约。
  - `nativeNetwork/docker/systemd/nativePackageInstall` 是 Full Linux-only 需求；Fast 矩阵恒 false，Router 遇到即 `REQUIRES_FULL_LINUX`。
- 结论：两张矩阵不冲突；Fast Lane 从不声明任何 Full Linux 专属能力。

## 7. 文案

`FastLaneCopy.NOT_FULL_LINUX` 明确：

> Fast Lane 不是 Full Linux：不提供 systemd、Docker、原生网络、GPU、音频/USB 或原生包安装。

`badge` 三种：`Fast Lane · protected 硬件 CPU 密封工作区`、`Full Linux · QEMU`、`不可用`。`explain` 对 FAST/FULL/UNSUPPORTED 都带“不是 Full Linux”的限制说明。文案只提供数据，不接 Android UI。

## 8. 测试

### 8.1 命令

```bash
javac --release 17 -Xlint:all -Werror \
  -d <out/main> $(find runtime/fastlane-capability/src/main/java -name '*.java')
javac --release 17 -Xlint:all -Werror \
  -cp <out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  -d <out/test> $(find runtime/fastlane-capability/src/test/java -name '*.java')
java -cp <out/test>:<out/main>:<junit-4.13.2.jar>:<hamcrest-core-1.3.jar> \
  org.junit.runner.JUnitCore \
  io.github.nyzeep.orchestella.runtime.fastlane.capability.FastLaneCapabilitiesTest \
  io.github.nyzeep.orchestella.runtime.fastlane.capability.FastLaneRouterTest
```

### 8.2 结果

- `javac --release 17 -Xlint:all -Werror`：主源码与测试均通过。
- JUnit：**32 tests OK**（Capabilities 8 + Router 24）。
- Gradle `:runtime:fastlane-capability:test`：**not-run**。本环境没有 Android SDK；根 `settings.gradle.kts` 包含 `:app`，配置阶段不可用。

### 8.3 覆盖

- canonical 矩阵逐项断言；五项非能力恒 false；`terminalOnlyDisplay`。
- `project` 收窄：storage 缺失时 `encryptedStorage=false` 且 `exportImport=false`；hidden API/permission/no AVF 时 `none()`。
- truth table：上表 1-27 的主干行均有测试；组合优先级（full-linux 需求 vs unknown workload、Fast 不依赖 Full、hidden API + no full lane、storage UNKNOWN、toolbox 缺失）。
- decision 不变量：FAST 必须 `FAST_LANE_AVAILABLE`；非 FAST 不得 `FAST_LANE_AVAILABLE`；空解释拒绝。
- 文案包含“不是 Full Linux”“systemd/Docker/原生网络/GPU/包安装”。

## 9. 破坏性变更

- 删除 `FastLaneReason.NO_LANE_AVAILABLE`（未发布 WIP）。原因：`UNSUPPORTED` 现在直接携带触发原因（`NO_AVF`/`REQUIRES_FULL_LINUX`/`HIDDEN_API_BLOCKED` 等），诊断信息更具体；如果保留该枚举，Router 永远不会产生它，形成死代码。
- `FastLaneDecision.unsupported(...)` 的 reason 现在是触发原因，不再是通用的“无车道”。调用方若按 `NO_LANE_AVAILABLE` 分支需要改为读 `reason()` 的具体值；本仓库尚无该调用方。
- `FastLaneCapabilities.project` 的 `exportImport` 现在依赖 `encryptedStorage`；如果旧调用方假设 exportImport 恒 true，需要按 device fact 处理。

## 10. 未验证（not-tested）

- 真实设备 `DeviceFacts` 的读取（AVF 存在、protected 能力位、MANAGE 权限、hidden API、storage/proxy/toolbox/distcc）。
- ACR-03 是否接受第三 Runtime 类别；本模块只提供数据，不对外声明。
- Android UI/徽章接线；DV-04。
- 与 Full Lane 现有 `FullLinuxContract.CapabilityMatrix` 的运行时合并；本模块不依赖它，避免改变 QEMU 默认。
- 真实 `tcpProxy/exportImport/distccOffload` 的设备可用性；P05/P06/P09 与 DV-02/03。

## 11. 研究来源

- `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md` §2.2/§4.8/§6。
- `.scratch/orchestella-fastlane/issues/P07-capability-router.md`。
- `docs/research/2026-10-21-cross-lane-reference-survey.md` §1/§5/§8。
- 本仓 `runtime/contract/src/main/java/.../FullLinuxContract.java`（只读参考能力词汇，未修改）。
- AOSP `VirtualMachineManager.getCapabilities()` / `CAPABILITY_PROTECTED_VM`：`references/aosp-virtualization/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java:102/188`。
- ACR-03：`../orchestella-avf-shizuku/issues/ACR-03-protected-fast-lane-runtime-class.md`（未决）。

## 12. 风险与下一步

1. **ACR-03 未决**：Fast Lane 不能对外声明为第三 Runtime 类别；本模块只能作为内部实验数据。
2. **能力声明与真机不符**：`v1()` 是目标声明，不是验收结果；DV-01/02 发现不符时以实测修正，不能把声明当通过。
3. **调用方绕过 Router**：UI/session 若直接读 `FastLaneCapabilities.v1()` 而不看 `project(facts)`，会虚报能力；产品接线必须只消费 `FastLaneDecision` + effective matrix。
4. **`RequirementProfile.none()` 的显式覆盖**：调用方若误传 `none()`，可能在没有 storage 的设备上选 FAST；接线层应对“交互式 shell/编译”使用 `null` 让 workload 默认需求生效。
5. **下一步**：DV-01 读取真实 facts；DV-04 接 UI；ACR-03 裁定后再决定是否产品化。
