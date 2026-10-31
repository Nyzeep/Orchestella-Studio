---
title: pVM「完整内核 + 硬件 vCPU + 多核」组合路线深调（fabric 核心工单 05）
date: 2026-11-05（批次实际作业日 2026-10-31）
status: research
scope: 零设备调研；不改 CONTEXT.md/ADR/代码，不构成实施承诺。工单：.scratch/orchestella-uml-fabric/issues/05-pvm-fabric-route.md
method: 仓库资产盘点（LaneBudgetLedger / DualLaneCoordinator / DistccTcpVsockBridge / dv_pvm_crossfire.py / P09）+ AOSP AVF 源码级核验（浅克隆 packages/modules/Virtualization main 快照）+ 联网先例调研（Podroid / DroidVM / source.android.com）。
evidence: [官方源码] AOSP 源码/官方文档；[社区] 第三方项目文档与自述，未独立复现；[本项目实测] 本仓库真机证据（会话 21–23、DV-PVM-02a）；[内部基线] 工单指定的已确立内部事实；[未找到证据] 检索不到。访问日期均为 2026-10-31。
---

## 0. 结论摘要

1. **AVF 的 vCPU 参数在公开 SystemApi 里只有两档：1 vCPU（默认）或 match_host（宿主全核）**；任意 N（`CpuOptions.CpuTopology.cpuCount`）存在于 AIDL/JSON 配置层，但走 `payloadConfigPath`/custom config 即触发 `USE_CUSTOM_VIRTUAL_MACHINE`（signature|development）。vCPU/内存参数与 APK 签名 debug/release 无关；debug level 只追加 ramdump 内存（17 + swiotlb MiB）。[官方源码]
2. **AVF 无每 App 并持 VM 数量上限**（源码仅有 CID 分配区间 10–65535 与调试输出上限 100）；两 VM 并存的真约束是资源账本：QEMU 实测 `-m 6371M`（4 vCPU TCG）+ pVM 内存捐赠且不可换出（memlock RLIMIT∞、protected 另加 swiotlb）+ 每 VM 一个 crosvm 进程可被 lmkd 整体杀掉。`LaneBudgetLedger` 已具备单账本三车道记账形状。[官方源码]
3. **决定性通道事实：`MANAGE_VIRTUAL_MACHINE` = signature|preinstalled|development，AVF Java API 全部 @SystemApi，官方 README 明言 not available to third party apps**——Play 分发的第三方 App 在任何 AVF 设备上（含 non-protected Pixel）都没有生产通道，只有 adb/Shizuku 授权的开发通道（权限跨重启保留、卸载即失）。这同时适用于 protected 与 non-protected 两条轨。[官方源码]
4. **「真三合一」（自有内核 + 硬件 vCPU + 多核）在 non-protected pKVM 设备上技术成立且有在营先例**（Podroid 在 Pixel 8/8a/9/9 Pro/9 Pro XL/10 上以 non-protected 自定义内核 VM 运行，多核 1-or-全部、Tensor 设备有 secondary CPU bringup 失败需自动降核的先例），但它同样需要 `USE_CUSTOM_VIRTUAL_MACHINE`，故只存在于开发通道；protected-only 设备上「第三方内核进 pVM」仍需平台信任链变更，不可交付（停止条件未触发，仅记录边界）。[社区] + [官方源码]
5. **fabric 的可交付形态是「组合编排」双轨**：轨道 A（protected-only 设备）= QEMU 翻译执行全功能车道 + protected Microdroid 进程内编译 worker（经既有 TCP↔vsock 桥，distcc 编译单元级派发，clang-22 ABI 门沿用 P09）；轨道 B（non-protected pKVM 设备）= 自有内核自定义 VM 作为完整 distccd 节点。UML 作第三个 worker 的挂接条件已列明（§2.3），其价值是并行宽度而非单任务速度（纯 CPU 1.08×、syscall 8×）。[内部基线] + [本项目实测]

---

## 1. 硬边界与本次调研范围

10-28 文档 §3.1 的单条决定性事实不变：硬件 vCPU 由 EL2 hypervisor 提供，hypervisor 只执行平台信任链接受的镜像，故「第三方 App 自有内核 + 硬件 vCPU 同体」在 protected-only 设备上被证伪（pvmfw AVB 信任键、virtmgr 文件标签、`setProtectedVm(false)` 在 `hypervisor_vm_supported=false` 的设备上直接抛 `UnsupportedOperationException`——本次在源码级复核了第三条，见 §2.4）。（来源：`docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md` §3.1，内部文档）

本次求证对象是**组合编排路线**：一个逻辑 Linux、多个执行面（QEMU 翻译执行面 + pVM 硬件 vCPU 面 + UML 原生指令面），即「fabric」。所有结论遵守工单全局纪律：不同夹具数字禁止相除；推算标注非实测；本批次无设备，真机相关项标 `not-tested` 并入设备窗口清单（§6）。

---

## 2. 五问逐答

### 2.1 Q1 · Microdroid 多 vCPU：API、上限与 payload 多核利用

**API 面（[官方源码]，packages/modules/Virtualization main 快照，2026-10-31）：**

| 层 | 参数 | 取值 | 备注 |
|---|---|---|---|
| Java SystemApi `VirtualMachineConfig.Builder.setCpuTopology` | `CPU_TOPOLOGY_ONE_CPU`(默认) / `CPU_TOPOLOGY_MATCH_HOST` | 1 个 vCPU / 宿主全核 | javadoc：Topology also has an effect on memory usage as each vCPU requires additional memory to keep its state. 无任意 N 的 setter |
| AIDL `CpuOptions.CpuTopology` | `cpuCount`（默认 1）/ `matchHost` | 任意 N 存在于传输层 | crosvm `add_cpu_arg` 按 `CpuCount(count)` 或 host-cpu-topology 生成 `--cpus` |
| JSON 配置（`payloadConfigPath` / raw config） | `cpu_topology: match_host` 等 | 任意 N / match_host | `is_custom_config()` 对 ConfigPath 一律返回 true → 需 `USE_CUSTOM_VIRTUAL_MACHINE` |

- **任意 N 的代价**：`payloadConfigPath`、extra APK、非 microdroid OS、自定义 kernel（`CustomConfig.customKernelImage`）都判为 custom，enforcement 点在 virtmgr `create_vm_context` 后 `check_use_custom_virtual_machine()`（android/virtmgr/src/virtualmachine.rs:668/1405；android/virtualizationservice/src/aidl.rs:1105）。[官方源码]
- **match_host 的实现**：`check_if_all_cpus_allowed()` 读 /proc/self/status 的 Cpus_allowed_list；全核连续可用则 `--host-cpu-topology`，否则显式传可用核数（crosvm.rs:378–392/2218）。即 vCPU 数受 virtmgr 进程 affinity 约束。[官方源码]
- **签名 debug/release 差异**：vCPU/内存参数与签名无关。debug level 影响的是 ramdump 预留（17 + swiotlb MiB，仅 debuggable VM）与控制台通道；payload 完整性（pvmfw AVB/DICE）与 debug level 相关，但与核数无关。[官方源码]
- **`setProtectedVm(false)` 的源码语义**：`hypervisor_vm_supported()`（ro.boot.hypervisor.vm.supported）为 false 时抛 `UnsupportedOperationException`（Non-protected VMs are not supported on this device.）；protected 侧对称依赖 `hypervisor_protected_vm_supported()`。这把 10-28 文档的实测异常（内部证据）与平台属性建立了源码级对应。[官方源码]

**内存约束（多 vCPU protected VM）：**

- 默认内存 256 MiB（`config.memoryMib` 未设时 crosvm `unwrap_or(256)`，crosvm.rs:463–466）；`setMemoryBytes` 无 API 上限，实际受设备内存与捐赠模型约束。[官方源码]
- protected VM 内存是**捐赠 + 不可换出**：pKVM 把页所有权从 host 转给 pVM，host stage-2 被限制访问；virtmgr 启动时把 crosvm 进程 `RLIMIT_MEMLOCK` 提到 `RLIM_INFINITY`（crosvm.rs/main.rs:118–128、virtualizationservice aidl.rs:403–413）。官方架构页明言 All guest pVM memory is therefore attributed to the crosvm instance … can result in the process being killed (terminating the VM)——内存压力下 lmkd 杀 crosvm 即 VM 整体消失。[官方文档]（source.android.com/docs/core/virtualization/architecture）
- protected VM 在请求内存之外**追加 swiotlb**（`get_swiotlb_mib`，仅 protected；按磁盘数/控制台数/balloon 估算）。[官方源码]
- pvmfw 对多 vCPU 无禁止：`guest/pvmfw/src/fdt.rs` 对任意 num_cpus 做 GIC/timer DT 修补。**protected + match_host 的实机启动稳定性：not-tested。**

**payload 多核利用：**

- Microdroid payload 是进程内 native 代码；P09 2026-10-24 改道后 payload **不能** fork/exec distccd（真机证据 `execve("/mnt/apk/bin/sh")` → `EACCES(13)`，`avc denied { execute_no_trans }`），唯一实现是 PVM-02/PVM-03 的进程内编译器库。[内部基线]（.scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md「2026-10-24 改道」节）
- 因此多核利用 = **并发连接 × 每连接一个 worker 线程**：guest `make -j N`（N ≈ vCPU 数）经 distcc 发 N 个编译单元，桥侧并发 ≥ vCPU 数（P09 行为验收项），payload 进程内线程池消化。P09 既有约束（同版本 clang、C 优先、C++ 标 not-tested、端到端 <1.5× 停止产品化）**引用不重做**。[内部基线]
- payload+vsock 单次编译基线 1.05–1.08 s（92 KB `.i`，DV-PVM-02a 实测）：payload 与 vsock 不是瓶颈，派发粒度选编译单元级不会引入按任务计的显著固定成本。[本项目实测]（dv_pvm_crossfire.py 头注 PAYLOAD_COMPILE_BASELINE_MS）

### 2.2 Q2 · 两 VM 并存：资源现实性与 AVF 允许性

**AVF 允许同一 App 并持两个 VM 实例：**

- `VirtualMachineManager` API 按 name 键控（`create/get/getOrCreate/delete/importFromDescriptor`），无数量参数。[官方源码]
- virtualizationservice 源码无 per-UID/per-App VM 数上限；仅 CID 区间 `GUEST_CID_MIN..GUEST_CID_MAX`（10..65535）循环分配，调试 dump 有 `MAX_VMS_TO_DUMP=100`（只影响 bugreport 展示）。[官方源码]（android/virtualizationservice/src/aidl.rs:92/177/296/851–861）
- 每 VM 一个独立 crosvm 进程，由该 App 的 virtmgr 拉起；QEMU 则是 App 自己的进程。两者互不感知，AVF 不存在「同一 App 只能有一个 VM」的机制。[官方源码]

**资源现实性（记账模型，叠加项全部显式）：**

| 预算项 | 车道 | 数量级 | 等级 |
|---|---|---|---|
| guest RAM | QEMU | 实测 `-m 6371M`（4 vCPU TCG，会话 22） | [内部基线] |
| pVM RAM | Microdroid | 默认 256 MiB；编译 worker 建议 1–2 GiB 量级——**非实测推算**，需按 TU 大小标定 | 推算 |
| swiotlb | protected 附加 | 估算函数给出（随磁盘/控制台数），量级 tens of MiB | [官方源码] |
| 每帧进程开销 | 每 VM | crosvm/QEMU/virtmgr 各一进程 + vCPU 线程 | [官方源码] |
| vCPU 线程 | 两车道 | QEMU 4 + AVF match_host=全核 → 必然超订 | 结构性结论 |

- 关键差异：QEMU 内存可换出，**pVM（protected）内存不可换出**且整进程可被 lmkd 终止——fabric 必须把「worker 半途消失」当常态：distcc 客户端本地回退已是既有行为（dv_pvm_crossfire.py 的 LOCAL_FALLBACK/TRUNCATION 判定）。[本项目实测] + [官方文档]
- `LaneBudgetLedger` 现状：单账本按 lane 记 CPU/RAM/DISK，`ACCEL` 车道受 `acceleratorAvailable()` 门控，`canAdmitPool(memoryMb)` 已为「池 VM 属于加速车道预算」留了口（注释：pool 不在 ledger 预留，池有自己的 lease）。两 VM 并存记账 = QEMU 请求与 ACCEL/池请求同时进账本，无需新账本，只需把 pVM 的不可换出属性写进预算判定（纯设计可定，见 §4）。[本项目源码]（runtime/instance/.../duallane/LaneBudgetLedger.java）
- CPU 超订：QEMU 4 vCPU 线程 + AVF worker 并发线程同时竞争物理核。派发侧对策是**以任务队列限流代替核数精确切分**（fabric 编排只承诺「并发任务数 ≤ 两车道账本核数和」，不承诺绑核）。纯设计可定。
- 内存叠加示例（**非实测**，仅演示记账公式）：若 pVM worker 取 2048 MiB，则「QEMU 6371 + pVM 2048 + swiotlb + 双 VMM 进程开销」需 ≤ 设备可用 RAM；账本在 `reserve()` 时以 `RAM_EXCEEDED` 拒绝。此公式的合理性是纯设计可定，具体数字需设备窗口标定。

### 2.3 Q3 · fabric 协议与编排：资产盘点、粒度、ABI 门、UML 挂接

**现有资产盘点（全部已入库，无需新建）：**

| 资产 | 位置 | 在 fabric 中的角色 | 已验证程度 |
|---|---|---|---|
| `LaneBudgetLedger` | runtime/instance/.../duallane/LaneBudgetLedger.java | 三车道统一资源账本（CPU/RAM/DISK 显式预留；UNCONFIRMED stop 保留预留） | 单测通过（LaneBudgetLedgerTest） |
| `DualLaneCoordinator` | app/src/main/java/io/github/nyzeep/orchestella/DualLaneCoordinator.java | QEMU 主车道 + 加速车道的唯一准入/停止 owner；accel 失败降级 `ACCEL_LOST/DISABLED` 不停 QEMU | 单测通过（DualLaneCoordinatorTest） |
| `DistccTcpVsockBridge` | runtime/fastlane-distcc/.../DistccTcpVsockBridge.java | loopback:5103 TCP → 每连接一条 vsock `FastLaneChannel` 的字节桥；连接上限/空闲看门狗/半关闭/RST 释放槽位 | 17 个桥测试 + 真实 distcc 3.4 宿主集成通过（P09） |
| `dv_pvm_crossfire.py` | tools/device-validation/dv_pvm_crossfire.py | 跨车道端到端驱动：QEMU guest → gvproxy hostIP(192.168.127.254:5103) → 桥 → protected payload；clang major=22 硬门；宿主等价判定；负载分段归因 | 设备窗口项（QA-only） |
| P09 工单 | .scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md | wire 契约、golden fixture、guest 契约、改道记录（payload exec 被拒 → 进程内编译器） | 宿主侧已落地 |

**任务派发粒度：编译单元级（distcc job），不选进程级。**

- 进程级（整构建进程迁入 pVM）在 protected payload 上不可实现：无 exec（P09 改道证据），且 payload 是 single-binary 模型。**protected 内不可行是结构性结论，非缺实现。**[内部基线]
- 编译单元级 = guest `clang -E` 本地预处理 + `CC=distcc clang` 派发 `.i`（现 crossfire 路径），每连接一个 TU。固定成本有实测上界（payload 1.05–1.08 s/92 KB TU，见 §2.1），对中大型 TU 可摊薄。[本项目实测]
- payload 侧并发模型：每连接一个 worker 线程（`distccd --inetd` 语义的进程内等价物），连接数 = guest `-j` 值；桥侧背压/连接上限已实现。[本项目实测]

**编译器 ABI 一致性门（沿用 P09，不重做）：**

- 宿主（桥）与 payload 不解析协议；`REQUIRED_CLANG_MAJOR = 22` 在 crossfire 驱动硬检，payload ARGV 拒绝落到 distcc 本地回退；同版本 clang、C 优先、C++ not-promised、端到端 <1.5× 停止产品化——均引用 P09 原文。UML 面挂入同一门（UML guest 内也必须 clang 22 同版本）。[内部基线]

**UML 面作第三个 worker 的挂接条件（G1 过后逐条核对；全部条件不满足前不开发）：**

1. **G1 本体**：UML 在 App 域多核可用（map 中 G1 = app 域多核；10-28 §6.2 真机项）。not-tested。
2. **网络路径**：UML guest → 宿主 TCP（passt/vec/umnetx 三候选，SELinux `NETLINK_ROUTE` bind 争议未仲裁，必须本项目自测）。UML 不走 vsock `FastLaneChannel`——它直达宿主 loopback listener，因此**不复用** `DistccTcpVsockBridge` 的 vsock 通道，需要并行一个 TCP worker 接入点（或桥增加 lane 标记的 TCP 通道）。纯设计可定，但改动面要过 ACR。[内部基线]（10-28 §4.2「UML 走 passt/vec 上的 TCP」；10-31 调研 AAQWQ11 行）
3. **ABI 门**：UML guest 发行版内 clang major=22 同版本（与 payload 门同源）。纯设计可定（选型约束），实际可装性需设备窗口。
4. **单核现实**：上游 arm64 UML RFC 自述 UP only；会话 22 实测 UML 真实编译 2.04×、纯 CPU 1.08×、syscall 8×，编译加速角色已撤回。故 UML worker 的定位是**并行宽度**（多一个可派发槽位）而非单任务速度；distcc 客户端按完成节奏自然负载均衡，慢 worker 不会被饿死也不会拖垮整体，但本地回退阈值必须按 worker 区分（UML 面单独调参）。[本项目实测] + [内部基线]
5. **失败域**：UML 是宿主进程，崩溃不伤 QEMU/pVM 面；编排按「worker 掉线→剔除池」处理，与 pVM worker 被 lmkd 杀掉同一条降级路径（distcc 本地回退）。纯设计可定。

### 2.4 Q4 · 真三合一的前景 segment：non-protected AVF 设备与双轨路线

**权限模型（先于设备能力，是决定性约束）：**

- AVF Java API（android.system.virtualmachine.*）全部 @SystemApi，「not available to third party apps」；Android 14 仅特权 App、Android 15 起所有**预装** App 可用；两个版本上都可经 `adb shell pm grant` 授予「for development purposes」。[官方文档]（libs/framework-virtualization/README.md，AOSP main；早期版本同文见 android.googlesource.com aml_net_351410000 标签）
- `MANAGE_VIRTUAL_MACHINE` protectionLevel = **signature|preinstalled|development**；`USE_CUSTOM_VIRTUAL_MACHINE` = **signature|development**，声明原文：Allows an application to run a Virtual Machine with a custom kernel or a Microdroid configuration file. **Not for use by third-party applications.**[官方源码]（android/android.system.virtualmachine.res/AndroidManifest.xml）
- 会话 23 实测 `pm grant … granted=true` 与此吻合：那是 development 通道授权，不是生产授权。[本项目实测]（ACR 草案 §7.1）
- **推论（本票最重要的新结论）**：Play 分发的第三方 App 在任何 AVF 设备上都不存在 pVM 生产通道——protected 轨（现 Fast Lane）与 non-protected 轨（真三合一）同判。pVM 面当前只能活在：开发/QA 通道（adb/Shizuku）、预装/OEM 渠道（preinstalled+signature）、或未来政策变化。fabric 的生产价值锚定在 QEMU 面，pVM 面是「开发通道能力 + 预装通道期权」。
- 先例互证：Podroid 明确要求用户手动 grant 两个开发态权限（adb 或 Shizuku rish），并写明权限跨重启与就地更新保留、卸载即失。[社区]（extv.github.io/Podroid/guide/backends.html）

**设备能力面：**

| segment | non-protected 能力 | 证据等级与来源 |
|---|---|---|---|
| Pixel 8 / 8a / 9 / 9 Pro / 9 Pro XL / 10（Tensor + pKVM） | 可用（`CAPABILITY_NON_PROTECTED_VM` 路径实机在营：Podroid non-protected 自定义内核 VM 已交付用户使用；多核 = 1 vCPU 或全核，Tensor G3/G4 有 secondary CPU bringup 失败先例，需自动降核重试） | [社区]（Podroid backends 文档 v1.3.0）+ [官方文档]（pKVM 架构，source.android.com）。**本机 `getCapabilities()` 实测：not-tested** |
| 高通（Gunyah，至 8 Elite Gen 5） | protected-only（仓库既有基线，本轮不复验） | [内部基线] |
| MTK GenieZone（/dev/gzvm） | DroidVM 项目自述支持 /dev/gzvm、/dev/gunyah、/dev/kvm 三后端；另一调研明确「MediaTek 的 GenieZone crosvm adapter 打开 /dev/gzvm 不等于每台 MTK 设备都对 App 暴露 AVF」 | [社区]（github.com/droid-vm/droidvm；sparkles.petar-kirov.dev research 页）。**无 AVF 框架级实机证据** |
| x86_64 | AVF 仅测试用途支持 non-protected（官方架构页原话） | [官方文档] |

**该 segment 上「自有内核 + 硬件 vCPU + 多核」能否经 adb grant 交付：**

- 技术上成立：non-protected 自定义 VM（自有内核）+ cpuCount/matchHost 多核 + pKVM 硬件 vCPU，正是 Podroid 在跑的形态；AOSP 侧对应物是 `USE_CUSTOM_VIRTUAL_MACHINE` 保护的 raw/custom config 路径（`is_custom_config`：任何 raw VM、自定义 kernel、ConfigPath、非 microdroid OS）。[官方源码] + [社区]
- 交付通道判词：**只能经 adb/Shizuku 开发授权交付，生产通道为零**（§权限模型）。因此「真三合一」是开发者/侧载 segment 的能力，不是 Play 通道功能；产品语言必须如实写「需开发通道授权」，不得写成普通功能。

**双轨路线图（fabric 版）：**

- **轨道 A · protected-only fabric（组合编排）**——适用于 Gunyah protected-only 的存量 Qualcomm 设备：
  1. QEMU 翻译执行面为唯一生产交付面（完整内核、GPU 通道、终端）；
  2. protected Microdroid 编译 worker 经既有 TCP→vsock 桥接入 distcc 池（编译单元级派发、进程内编译器、clang-22 门）——开发通道验证，生产启用需 preinstall/OEM 渠道或政策变化；
  3. UML 原生指令面作为可选第三 worker（G1 过后按 §2.3 条件挂接）；
  4. 编排原则：QEMU 主车道永不被 worker 故障拖垮（DualLaneCoordinator 既有降级语义 + distcc 本地回退）。
- **轨道 B · non-protected 真三合一（开发者通道）**——适用于 pKVM non-protected 设备（Pixel 系为主）：
  1. 同 App 内 QEMU 面不变（兼容性兜底）；
  2. 自有内核自定义 VM（non-protected）作为完整 Linux worker：原生 distccd、自带工具链、match_host 多核；
  3. 双权限 grant 引导（MANAGE_VIRTUAL_MACHINE + USE_CUSTOM_VIRTUAL_MACHINE）+ Podroid 式能力诊断（feature、权限、/apex/com.android.virt、service、capabilities 五点探针——先例可借鉴）；
  4. 多核降级自动化（bringup 失败自动减核重试并记忆，先例来自 Podroid）；not-tested。
- 两轨共享：LaneBudgetLedger 记账、DistccTcpVsockBridge wire 契约（轨道 B worker 走 TCP 不走 vsock）、clang-22 ABI 门、本地回退语义、产品语言三词纪律。

**停止条件核销**：本次未出现任何可绕过 pvmfw 信任链的「protected 接受第三方内核」方案。需要如实记录的一条边界事实：AOSP 提供了 `CustomConfig.customKernelImage`（boot Microdroid VM with the given kernel），但它 (a) 被 `USE_CUSTOM_VIRTUAL_MACHINE`（signature|development）门控，(b) protected 模式下 pvmfw 校验不变——对第三方 App 的 protected VM 仍不可用。标注「需平台信任链变更，不可交付」，不展开设计。[官方源码]

### 2.5 Q5 · 产品语言（遵守 ACR 草案 §4 三词纪律）

三词适用（引用不重定义）：**原生指令执行**=proot/bare、UML（无 EL2 边界）；**硬件 vCPU**=仅 pVM（EL2 hypervisor 提供）；**翻译执行**=仅 QEMU TCG。「硬件加速」禁用于执行策略表述（唯一合法含义是 GPU 侧，ADR-0019）。（来源：docs/research/ACR-draft-four-lanes-20261029.md §4）

**本路线正例：**

- 「编译加速扩展：编译单元在硬件 vCPU 上执行；完整内核仍在软件翻译执行的车道内运行。」（分开两个面，各用对词）
- 「pVM 编译 worker 需开发通道授权（adb 授权）安装，当前不随主包启用。」（如实交代通道）
- 「在支持非保护 VM 的设备上，开发者通道可启用自有内核 + 硬件 vCPU 的编译节点；多核拓扑为 1 核或宿主全核。」（可证伪：能力、通道、拓扑都有判据）
- 「UML 工作面：guest 指令原生执行、无硬件虚拟化边界；作为编译池的附加 worker，syscall 有开销。」（原生指令执行 + 开销披露）

**本路线反例（禁止出现）：**

- 「pVM 硬件加速编译」/「AVF 硬件加速让 Linux 整体变快」——「硬件加速」是 GPU 专属词，且 pVM 只加速被卸载的那部分计算。
- 「完美调度 / 接近原生」——不可证伪措辞（ACR §4.3 同禁）。
- 「真三合一」作为对普通用户的功能承诺（它依赖开发通道授权，措辞必须带通道限定词）。
- 「同一内核跨车道共享」——三个执行面是三个内核（QEMU guest 内核、Google Microdroid 内核、UML 内核），fabric 编排的是任务不是内核；写成「共享内核」即失实。

---

## 3. 能力上限表（fabric 各面，按执行面锁定）

| 能力 | QEMU 面（翻译执行） | protected Microdroid 面（硬件 vCPU） | non-protected 自定义 VM 面（硬件 vCPU） | UML 面（原生指令执行） |
|---|---|---|---|---|
| 内核来源 | 自有（发行版内核） | Google Microdroid（不可换，源码级） | 自有（non-protected 才可行） | 自有（UML 内核，宿主进程） |
| vCPU | TCG 线程（实测 4） | 1 或 match_host；任意 N 需 custom 权限 | 1 / N（custom）/ match_host；Tensor 多核有 bringup 坑 | UP（上游 RFC 自述） |
| 多核利用 | `-j` 于 guest | 并发 distcc job × payload 线程池 | 原生 distccd `-j` | 单线程 worker 槽位 |
| 内存 | 可换出；实测 -m 6371M | 捐赠+不可换出+swiotlb；lmkd 可整杀 | 按普通进程记账（无 pvmfw 捐赠模型）；lmkd 行为需设备窗口核实 | 宿主进程内存，普通记账 |
| 交付通道 | Play 生产 | 开发通道（preinstalled/OEM 期权） | 开发通道（双权限） | App 域直接可用（现代 targetSdk 域已实测三探针过） |
| 网络 | gvproxy hostIP:5103 → 桥 | vsock（桥的 FastLaneChannel） | 宿主 TCP（无 vsock 依赖；细节待设计） | passt/vec → 宿主 TCP（可用性待自测） |
| 已验证程度 | 会话 22/23 真机 | 宿主侧全链 + 设备窗口未过（AVF 服务缺失样本 ×1） | 无（先例为第三方项目） | 启动/编译实测，长驻/Docker/链接未验 |

---

## 4. 缺口清单

**纯设计可定（零设备，后续工单可直接立）：**

- D-1 桥的多 lane 扩展：`DistccTcpVsockBridge` 增加 lane 标记通道（vsock=pVM 面 / TCP=UML 面与轨道 B 面），wire 不变。
- D-2 `LaneBudgetLedger` 增补：pVM 内存「不可换出 + 整进程可杀」属性进入预算判定；两 VM 并存的 reserve/降级路径（ACCEL 预算被拒 → QEMU 单车道，语义已存在）。
- D-3 编排策略：任务并发上限 = 账本核数和；per-worker 回退阈值（UML 面单独调参）；worker 掉线剔除。
- D-4 payload 进程内编译器的线程池参数与 vCPU 拓扑的映射（1/MATCH_HOST 两档枚举即可，无需任意 N）。
- D-5 轨道 B 的五点能力诊断与权限引导文案（借鉴 Podroid 先例，产品语言过 §2.5 纪律）。
- D-6 轨道 B 自定义 VM 的配置模板（payloadConfigPath/raw config + cpu_topology + memory_mib）与 `USE_CUSTOM_VIRTUAL_MACHINE` grant 流程说明。

**需设备窗口验证（全部 not-tested，入 §6 清单）：**

- protected VM 多 vCPU（match_host）在 Gunyah 设备上的启动稳定性与编译吞吐（n≥10，沿用 DV-03 方法）。
- 两 VM 并存的内存压力行为：lmkd 是否按预期杀 crosvm、mid-job 回退是否无损（distcc 语义）。
- 各 OEM 的 `virtualizationservice` 注册情况普查（会话 23 是单设备样本；HyperOS 设备无该服务）。
- Pixel 系 `getCapabilities()` 实测值与多核 bringup 失败复现（对照 Podroid 社区先例）。
- UML 面网络三候选（passt/vec/umnetx）在目标域的 SELinux 可用性自测。
- 轨道 B 自有内核 non-protected VM 冒烟（自有内核 + match_host + distccd 端到端）。

## 5. 与既有结论的关系

- 不推翻 10-28 §3 三车道结论，而是把「组合编排」的可行性上限与通道约束补齐：新增的决定性事实是**权限/通道层**的（signature|preinstalled|development），它把 pVM 双轨都约束在开发通道内。
- 不改 ACR 草案 §1 主表的行级结论；§7.1 的「AVF 服务缺失（单设备样本）」保持原样，本票只提供普查项（§4）。
- P09 契约、golden fixture、clang-22 门、DV-03 方法全部原样引用；本票未重做其中任何验证。

## 6. 设备窗口清单增量（供批次报告合并）

> 以下为本次调研新增的设备窗口项；编号延续本批次报告体例，均 not-tested。

| 编号 | 窗口项 | 方法要点 | 对应轨道 |
|---|---|---|---|
| W-FAB-01 | protected VM 多 vCPU 启动与吞吐 | `setCpuTopology(MATCH_HOST)` 于 protected Microdroid；记录 Gunyah 实机启动稳定性、nproc、`make -j` 吞吐；n≥10 | 轨道 A |
| W-FAB-02 | 两 VM 并存压力 | QEMU(-m 6371M) + Microdroid(2 GiB 级) 同启；内存压力下 lmkd 行为、VM mid-job 消失的 distcc 回退验证 | 轨道 A |
| W-FAB-03 | AVF 服务注册普查 | 多 OEM 多机型：service list grep virtualizationservice、getprop ro.boot.hypervisor.*supported、getCapabilities() | 双轨 |
| W-FAB-04 | Pixel non-protected 冒烟 | Pixel（pKVM）上 grant 双权限 → 自有内核 non-protected VM 启动 + `make -j`；对照 Podroid 先例；多核 bringup 失败样本采集 | 轨道 B |
| W-FAB-05 | MTK GenieZone AVF 暴露核实 | /dev/gzvm 存在的设备上验证 AVF 框架是否注册（/apex/com.android.virt、service、capabilities） | 双轨 |
| W-FAB-06 | UML 第三 worker 挂接前置 | 即 G1（app 域多核）+ 网络三候选自测 + UML guest 内 clang-22 可装性；通过后按 §2.3 条件表核对 | 轨道 A 第三面 |

## 7. 来源清单（访问日期均为 2026-10-31）

- [官方源码] AOSP packages/modules/Virtualization（main 快照，经 GrapheneOS GitHub 镜像 github.com/GrapheneOS/platform_packages_modules_Virtualization 克隆核验，对应 android.googlesource.com 同名仓库）：
  - libs/framework-virtualization/README.md（@SystemApi、MANAGE_VIRTUAL_MACHINE、adb grant=development purposes）
  - libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java（setProtectedVm 抛异常逻辑、setMemoryBytes 默认与 overhead 注、setCpuTopology 两档、CPU_TOPOLOGY_* 常量）
  - libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java（create/get/getOrCreate/delete、CAPABILITY_*）
  - android/android.system.virtualmachine.res/AndroidManifest.xml（两个权限的 protectionLevel 与声明原文）
  - android/virtmgr/src/virtualmachine.rs（is_custom_config、check_use_custom_virtual_machine）
  - android/virtmgr/src/crosvm.rs（add_cpu_arg、check_if_all_cpus_allowed、默认 256 MiB、get_swiotlb_mib、get_ramdump_mib）
  - android/virtmgr/src/main.rs、android/virtualizationservice/src/aidl.rs（RLIMIT_MEMLOCK、CID 区间、无 VM 数上限）
  - android/virtualizationservice/aidl/.../CpuOptions.aidl（cpuCount/matchHost）
  - guest/pvmfw/src/fdt.rs（num_cpus 修补，无核数上限）
  - build/debian/vm_config.json（custom OS 配置样例：protected:false、cpu_topology:match_host、memory_mib:4096）
- [官方文档] source.android.com/docs/core/virtualization/architecture（pKVM 内存捐赠、crosvm 内存归属与被杀语义、x86_64 仅测试用途）
- [官方文档] android.googlesource.com aml_net_351410000 标签同 README（Android 14/15 权限适用面差异）
- [社区] extv.github.io/Podroid/guide/backends.html（Pixel 8–10 non-protected 自定义内核 VM、双权限 grant、多核 bringup 自动降核、权限生命周期）
- [社区] github.com/droid-vm/droidvm（/dev/gzvm、/dev/gunyah、/dev/kvm 三后端自述）
- [社区] sparkles.petar-kirov.dev/research/android-dev-env/concepts（GenieZone adapter ≠ AVF 暴露的边界表述）
- [内部基线] .scratch/orchestella-uml-fabric/issues/05-pvm-fabric-route.md；docs/research/2026-10-28-native-kernel-three-lane-exploration.zh-CN.md §3；docs/research/ACR-draft-four-lanes-20261029.md §4/§7；docs/research/2026-10-31-uml-direction-research.zh-CN.md §1
- [内部基线] .scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md（含 2026-10-24 改道节）
- [本项目实测] tools/device-validation/dv_pvm_crossfire.py（clang major=22 门、payload 基线 1.05–1.08 s、回退判定）；runtime/instance/.../LaneBudgetLedger.java；runtime/fastlane-distcc/.../DistccTcpVsockBridge.java
- [本项目实测] 会话 22/23 真机数据（经工单指定为已确立内部事实，本票未复测）