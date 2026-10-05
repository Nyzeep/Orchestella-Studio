# Ticket 36 实施报告（Implementation Report）

- **工单编号**：Ticket 36
- **工单标题**：AVF 档能力对齐——出站网络与端口转发（AVF Parity: Outbound Network & Port Forwarding）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **工作区与特性分支**：`DroidLayer_dev_t36`，分支 `ticket/36-avf-network-forwarding`（基于 `main`，Commit `c878b21`）
- **看板跟踪项**：`.scratch/droidlayer-platform/issues/36-impl-avf-parity-network-forwarding.md`（已置 `ready-for-review`）
- **当前状态**：**Contract-Level Completed（JVM 模拟层全绿）· 提请独立复审 · 真机冒烟待 HITL（票 17）**

---

## 1. 任务背景与核心目标

Ticket 34 交付了 AVF 硬件虚拟化执行策略的契约核心（生命周期/终端/桥接传输/装配），但刻意不等待网络能力对齐——出站网络与端口转发的 AVF 档对齐由本票承接（ADR-0002 三纪律："契约测试套件从第一天存在并对所有执行策略运行"）。

本票目标：完成后 L2（硬件虚拟化档）用户获得与 L1（软件模拟档）**相同的网络能力面**：

1. **出站网络**（对齐 Ticket 27）：AVF 档实例的出站 NAT 契约与 Ticket 27 同源同套——同一份策略中立 `OutboundNetworkSpec`、同一组 guest 网络契约键、`setNetworkSupported(true)` 装配开关转正为契约行为；
2. **端口转发与热更**（对齐 Ticket 29）：AVF 档支持转发规则动态添加/删除/持久化，经 **vsock 隧道 + 宿主转发代理**（`AvfVsockPortForwarder`）实现与 QEMU 档 QMP `hostfwd` **完全一致的热更语义**（创建→本机命中→删除→即时失效，全程无 VM 重启）；
3. **契约等价性**：两策略网络与转发契约测试**同一套**，在 AVF 实现上全绿；
4. **能力矩阵**：AVF 档网络与端口转发能力以数据声明为"已验证"（诚实分级，见 §3.4）。

按派工单与票 34 先例，本票的契约验证在 **AVF 适配层模拟实现**（JVM + 假 vsock 网络 + 模拟 guest）上完成；真机 AVF 网络冒烟属 HITL 协作事项（依赖原型票 17 的真机数据，该票仍为 open），不在本会话执行，如实登记于 §7。

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 独立隔离工作树 | 独立 Git Worktree `DroidLayer_dev_t36`，分支 `ticket/36-avf-network-forwarding`（基线 `main` @ `c878b21`） | **100% 隔离** |
| **runtime:contract 零修改** | 派工令明令保持零修改 | `runtime/contract` 源码一个字节未动；出站网络复用既有 `NetworkContract.OutboundNetworkSpec`，转发复用既有 `PortForwardingEngine`/`PortForwardingRule` 接缝（均只消费、不修改） | **零侵入** |
| **Owned: engine-avf** | `runtime/engine-avf`（扩展） | 出站网络规格装配（`AvfLaunchSpec.networkSpec` + cmdline DNS 键 + 反射 cmdline 装配步骤）、`AvfVsockPortForwarder`、`AvfVmSession.openChannel`；main 源码零 Android 类引用 | **完全受控** |
| **Owned: network** | `runtime/network`（测试套件策略中立化） | `PortForwardingManagerContractTest` 提炼 `runAllScenarios(Path, LiveEngineFactory)`：场景集不变、QEMU 档默认运行行为不变；发布 `testArtifacts` 供引擎模块复用 | **完全受控** |
| **Owned: capability** | `runtime/capability`（扩展） | 新增 `NetworkCapabilityDeclaration` 数据类（能力矩阵数据化）；既有 `DeviceCapabilityEvaluator`/`AvfCapabilityEvaluator` 零行为变更 | **完全受控** |
| **Forbidden: app** | 严禁触碰应用层 | `app/` 零改动（转发规则的产品化 UI 不在本票范围；`AvfVsockPortForwarder` 经缝隙预留生产接线） | **零触碰** |
| **Forbidden: guest-assets / Class A 资产** | 严禁改动 guest 资产、`.toolchains/`、票 26 锁定产物 | 零触碰——guest 内转发代理以模拟替身交付（真机 guest agent 涉及 initramfs 身份锁，见 §7） | **零冲突** |
| **Forbidden: .log 提交** | 严禁提交 .log 文件 | `git diff main --name-only` 含 `.log$` 的文件数 = 0 | **零冲突** |
| **编译纪律** | Java 17 + `-Xlint:all -Werror` 零告警 | 全部改动模块零告警通过（`BUILD SUCCESSFUL`，见 §5） | **100% 合规** |
| **提交纪律** | Conventional Commits 中文日志 | 4 个实施提交 + 1 个交付提交，格式与票 29/34 一致（见 §8） | **100% 合规** |

## 3. 架构设计细节

### 3.1 出站网络装配（AC-1，对齐 Ticket 27）

- **规格共享（策略中立数据的直接复用）**：`AvfLaunchSpec` 新增 `networkSpec` 字段，默认即 `NetworkContract.OutboundNetworkSpec.defaultSpec()`——与 QEMU 档 `QemuLaunchSpec` 消费的是**同一个类型、同一份默认值**（`net0` / `10.0.2.0/24` / DHCP `10.0.2.15` / DNS `8.8.8.8` / IPv6 强制关闭）。策略间切换时网络语义零漂移。
- **guest 网络契约键对齐**：guest cmdline 追加与 QEMU 档同键的 `droidlayer.dns=<cmdlineDns>`（票 27 语义：guest 从 `/proc/cmdline` 解析该键写入 `/etc/resolv.conf`，缺省回退 `8.8.8.8`/`1.1.1.1`），使同一 guest provisioning 逻辑在两策略下行为一致。
- **crosvm 网络后端开关转正**：`ReflectiveAvfVirtualMachineApi` 的 `setNetworkSupported(true)` 装配步骤在本票正式纳入范围（该调用在票 34 中被评审指出属票 36 范围，票 34 实施报告 §9.5 如实登记"若裁定应随票 36 移除/处理，届时随网络档对齐一并处理"——本票即该裁定的落地：**不移除，而是转正为契约行为**，`AvfNetworkAssemblyContractTest` 断言装配后 `isNetworkSupported()==true` 且 notes 记录 `setNetworkSupported: applied`）。
- **cmdline 传输通道（可选反射步骤）**：平台 builder 的内核命令行面按版本可能以 `setKernelCmdline` 或 `setParams` 暴露——仿照既有 `addDisk` 双签名回退模式新增可选装配步骤（首个可解析变体生效，全部缺失降级为 notes 记录，绝不静默）。JVM 假 SDK 增加 `setKernelCmdline` 记录面，契约测试断言 vsock 服务端口键与 `droidlayer.dns=` 键完整到达 config。真机实际暴露的 setter 变体随真机冒烟在 reflection notes 中核实。

### 3.2 vsock 隧道端口转发引擎（AC-2，对齐 Ticket 29 热更语义）

- **`AvfVsockPortForwarder implements PortForwardingEngine`**（engine-avf main）：宿主侧在规则 `hostPort` 开 `ServerSocket` 监听；每条接受的连接经 **vsock 隧道**连接到 guest 内转发代理（vsock 端口 = 规则 `guestPort`），代理中继到 guest 本地服务 `127.0.0.1:<guestPort>`。双向字节中继语义与票 29 的 `SocketPortForwarder` 逐行同构。
- **热更语义与 QEMU 档一致**：`addRule` 即时生效（无 VM 重启）；`removeRule` 立即解绑宿主端口——新连接即刻 `Connection refused`；重加同宿主端口恢复服务。连接形态语义刻意与 `SocketPortForwarder` 完全镜像（热删仅解绑监听、存量流量自然收尾），保证两策略契约等价。
- **缝隙设计（生产/测试同路径）**：`VsockChannelFactory` 函数接口取得 guest 侧通道——生产侧经新增的 `AvfVmSession.openChannel(port)`（带 100×200ms 重试，桥接 guest 启动窗口；独立于 bridge 控制/终端双通道）；测试侧注入假 vsock 网络。app 层零改动即可在未来接线 `new AvfVsockPortForwarder(session::openChannel)`。
- **持久化/冲突检测免费复用**：`PortForwardingManager`（票 29）本就是引擎无关的对账层——原子持久化（`FilePortForwardingStore`）、冲突检测（`PortConflictDetector`）、attach 重放、热更传播对 AVF 引擎**零新代码**生效，由同一套契约测试验证。

### 3.3 两策略契约等价（AC-3）

- `PortForwardingManagerContractTest`（票 29 验证资产）提炼出 `runAllScenarios(Path, LiveEngineFactory)`：八个场景（默认回环/冲突拒绝/LAN 覆盖冲突/持久化重建/热更对账/attach 重放/删除落盘/真实流量四步验证）**一行断言未改**；`main` 默认仍跑 QEMU 档 `SocketPortForwarder`（Gradle 任务与输出不变）。
- 新增 `AvfNetworkForwardingParityContractTest`（engine-avf）：**调用同一个 `runAllScenarios`**，注入 AVF 工厂——按 guest 服务端口注册模拟 guest 内转发代理，返回 `new AvfVsockPortForwarder(session::openChannel)`（经生产会话路径）。套件在 AVF 引擎上全绿即"同一套测试、两个策略"的直接证明。
- 模拟 guest（`SimulatedAvfGuest`）新增 `listenForwardProxy(vsockPort, targetTcpPort)`：vsock→`127.0.0.1` 中继，与真机 guest agent 目标形态一致。

### 3.4 能力矩阵数据声明（AC-4）

- `NetworkCapabilityDeclaration`（runtime/capability）：能力矩阵以**数据**表达（ADR-0002），按执行策略声明 `OUTBOUND_NETWORK`/`PORT_FORWARDING` 两项能力，验证等级**有序且诚实**：
  - `CONTRACT_VERIFIED`：同一套契约测试在该策略实现上全绿；
  - `DEVICE_VERIFIED`：另加真机端到端实测。
- 声明现值：软件模拟档出站网络 = `DEVICE_VERIFIED`（票 26/27 真机 `apk update`）；软件档转发 = `CONTRACT_VERIFIED`（票 29）；**AVF 档两项均 `CONTRACT_VERIFIED`（票 36）**。
- 诚实性由测试固化：`refusesToClaimUnobservedDeviceVerificationForAvf` 断言 AVF 档**不预认** `DEVICE_VERIFIED`——真机冒烟属 HITL 原型项（票 17）。
- 两策略声明**同一能力集合**（parity set 断言）——L2 用户获得与 L1 相同网络能力面的矩阵表达。
- 说明：派工令要求 `runtime:contract` 零修改，故未在 contract 的 `Capability` 枚举上新增成员；若未来需将两项能力升格为 contract 枚举成员（供 `CapabilityMatrix`/UI 徽章消费），须走架构变更门，数据条目可平移（类 javadoc 已注明迁移路径）。

## 4. TDD 实施记录（RED → GREEN）

| Slice | RED 证据 | GREEN 证据 |
| :--- | :--- | :--- |
| 1 出站网络装配 | `AvfLaunchSpecTest` 扩展后 `:runtime:engine-avf:compileTestJava` 失败（`networkSpec()` 找不到符号）；`AvfNetworkAssemblyContractTest` 编译失败（`kernelCmdline()` 找不到符号） | `AVF_LAUNCH_SPEC_TEST_OK defaults=1 outbound_net=1 cpu_count=1 validation=9`；`AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_OK network_supported=1 cmdline_keys=1 dns_propagation=1` |
| 2 vsock 转发引擎 | 新建 `AvfVsockPortForwarderTest` 后编译失败（类不存在，5 处符号缺失） | `AVF_VSOCK_PORT_FORWARDER_TEST_OK live=1 hot_removal=1 re_add=1 close=1` |
| 3 等价套件 | `AvfNetworkForwardingParityContractTest` 编译失败（`runAllScenarios` 不存在 + network 测试类不可见） | 重构后 QEMU 档回归绿；`PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK`（AVF 引擎运行）+ `AVF_NETWORK_FORWARDING_PARITY_CONTRACT_TEST_OK same_suite=1 persistence=1 conflict=1 hot_reconcile=1 live_traffic=1` |
| 4 能力声明 | `NetworkCapabilityDeclarationTest` 编译失败（72 行错误，类不存在） | `NETWORK_CAPABILITY_DECLARATION_TEST_OK software=2 avf=2 honesty=1 parity_set=1` |

## 5. 全量回归证据（2026-10-05）

- **runtime 全部 7 模块**（`--offline`，`./gradlew :runtime:contract:check :runtime:network:check :runtime:capability:check :runtime:engine-qemu:check :runtime:engine-avf:check :runtime:instance:check :runtime:distro:check`）：`BUILD SUCCESSFUL in 10s`，28 条 `_OK` 断言行全绿。关键行：
  - `PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK` 出现 **2 次**——QEMU 档（本模块任务）+ AVF 档（parity 复跑），即 AC-3 的直接证据；
  - QEMU 档全部既有断言零漂移：`DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1`、`QEMU_HOSTFWD_CONTRACT_TEST_OK`、`DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1` 等；
  - AVF 档 9 个测试任务全绿（6 个既有 + 3 个新增）。
- **app 单测**（`subst X:` ASCII 别名绕开 CJK 路径后运行 `:app:testDebugUnitTest`）：**40 例，37 绿，3 失败**——失败项为 `AndroidSafExportAdapterTest` 3 例 JVM NPE，系**票 33 范围的既有失败**（本票 `git diff main` 对 `app/` 零改动，与票 34 记录的基线完全一致，非本票引入）。
- 改动总量：17 文件，+1197/−72，全部位于 `runtime/capability`、`runtime/engine-avf`、`runtime/network`。

## 6. AC 核对（与工单四项验收标准逐条对齐）

| AC | 结论 | 证据 |
| :--- | :--- | :--- |
| **AC-1 出站网络** | **契约级达成** | 同一份 `OutboundNetworkSpec` 装配 + `setNetworkSupported(true)` 转正 + `droidlayer.dns=` 同键对齐；`AVF_LAUNCH_SPEC_TEST_OK outbound_net=1`、`AVF_NETWORK_ASSEMBLY_CONTRACT_TEST_OK`、`NETWORK_CONTRACT_TEST_OK` 全绿。真机 `apk update`（票 27 同验收）待 HITL 真机冒烟，见 §7 |
| **AC-2 端口转发与热更** | **契约级达成** | `AvfVsockPortForwarder` 交付创建/删除/重加/持久化全链路；`AVF_VSOCK_PORT_FORWARDER_TEST_OK`（live=1 hot_removal=1 re_add=1）；持久化/冲突由 manager 套件在 AVF 引擎上验证（同票 29 场景）；热更语义与票 29 逐行同构 |
| **AC-3 契约等价性** | **达成** | 同一套 `PortForwardingManagerContractTest.runAllScenarios` 在两引擎上各自全绿（`PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK` ×2）；`AvfNetworkForwardingParityContractTest same_suite=1` |
| **AC-4 能力矩阵** | **达成（数据声明）** | `NetworkCapabilityDeclaration`：AVF 档网络/转发声明为 CONTRACT_VERIFIED 并带证据溯源；`NETWORK_CAPABILITY_DECLARATION_TEST_OK software=2 avf=2 honesty=1 parity_set=1`。contract 枚举升格路径见 §3.4/§7 |

## 7. 红线自查与遗留事项（如实登记）

1. **真机 AVF 冒烟（网络+转发）待 HITL**：真机 `apk update` 与转发四步验收依赖原型票 17（P1 AVF e2e，仍 open）的设备数据；本会话零设备操作（沿用会话纪律），能力声明相应停留在 CONTRACT_VERIFIED，不预认 DEVICE_VERIFIED。
2. **guest 内真实转发代理未随本票交付**：真机形态为 guest 内 vsock 监听→`127.0.0.1` 中继的小型 agent；修改 guest-assets 将改变票 26 锁定的 initramfs 身份（`f8db1b52…` 四成员新身份），必须先走票 26 供应链锁 §7 锁更新授权。本票以模拟代理在 JVM 层完成契约验证；真机 agent 建议随票 37 的 9p-over-vsock 数据通道同轮授权落地（同为 guest 数据面脚本）。
3. **contract `Capability` 枚举未扩展**：派工令"runtime:contract 保持零修改"为硬约束，网络能力声明以 runtime/capability 数据类交付。若 Reviewer/Orchestrator 裁定应升格为 contract 枚举成员（如 `OUTBOUND_NETWORK`/`PORT_FORWARDING`），请另行授权架构变更门；现有数据条目可直接平移。
4. **cmdline 装配为可选反射步骤**：`setKernelCmdline`→`setParams` 双候选回退；真机暴露哪个变体（或都不暴露）待真机冒烟经 reflection notes 核实。即便表面缺失，guest 侧仍有固定 vsock 端口约定与解析器默认回退兜底，不阻塞网络契约语义。
5. **UDP 转发的引擎层简化（继承自票 29）**：`AvfVsockPortForwarder` 与 `SocketPortForwarder` 一致，仅按 TCP 监听转发（两策略语义一致）；UDP 规则的热更传播走 manager/冲突检测路径正常，真实 UDP 数据面为两策略共同的后续增强项。
6. **app 层产品化 UI 不在本票范围**：转发规则管理的产品面（UI/通知）属后续票；引擎与对账层已就绪。

## 8. 交付物清单与提交记录

**实施提交（分支 `ticket/36-avf-network-forwarding`）：**

| Commit | 标题 |
| :--- | :--- |
| `f33c1aa` | `feat(engine-avf): 装配 AVF 档出站网络规格与 guest 网络契约键 (Ticket 36 Slice 1)` |
| `65256e1` | `feat(engine-avf): 实现 vsock 隧道端口转发引擎与热更失效语义 (Ticket 36 Slice 2)` |
| `d61d974` | `test(network): 两策略转发契约等价套件扩展至 AVF 实现 (Ticket 36 Slice 3)` |
| `8eb579f` | `feat(capability): 声明 AVF 档网络与端口转发能力验证状态 (Ticket 36 Slice 4)` |
| 本提交 | `docs(report): 产出 Ticket 36 实施报告并将看板置为 ready-for-review (Ticket 36 Deliverable)` |

**交付物：**
1. 实施报告：`docs/reports/tickets/36/implementation-report.zh-CN.md`（本文件）；
2. 看板状态：`.scratch/droidlayer-platform/issues/36-impl-avf-parity-network-forwarding.md` → `ready-for-review`（验收项按证据如实勾选）；
3. 规范提交：5 个 Conventional Commits 中文日志（上表）。

**提请事项**：按评审独立性纪律（实现者不得自评），请 Orchestrator 派 Reviewer Tier 就 `ticket/36-avf-network-forwarding` 对 merge-base `c878b21` 执行 Standards + Spec 双轴独立复审；§7 五项遗留请一并裁量。

---

报告人：Implementation Worker（Ticket 36 实施会话） ｜ 日期：2026-10-05 ｜ 状态：提请独立复审
