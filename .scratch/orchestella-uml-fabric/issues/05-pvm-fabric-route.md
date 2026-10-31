# 05: pVM「完整内核 + 硬件加速 + 多核」组合路线深调（fabric 核心）

Type: research
Status: resolved（2026-10-31，fabric-batch1）
Priority: P0（用户指定的深调主线）
Owner: （待分配会话）
Blocked by: None
External gates: 无（零设备调研）

## 背景与硬边界（不得推翻，只能在此之上工作）

protected-only 设备上「第三方 App 自有内核 + 硬件 vCPU 同体」已被源码级证伪（10-28 文档 §3：pvmfw AVB 信任键 + virtmgr 文件标签 + `setProtectedVm(false)` 抛异常；Microdroid neverallow execmem）。因此「创新路线」只能是**组合编排**：一个逻辑 Linux，多个执行面。本票把这条路线的可行性、上限与缺口查清楚。

## 必须回答

- [ ] **Microdroid 多 vCPU**：`VirtualMachineConfig` 支持 vCPU 数的 API 与上限（APK 签名 debug/release 差异？`num_vcpus` 类参数）；payload 内多核利用（distcc `-j` 与 vCPU 数的匹配）；多 vCPU protected VM 的内存约束。
- [ ] **两 VM 并存**：QEMU（4 vCPU TCG）+ Microdroid（N vCPU）同时运行的资源现实性（内存 6.3GB QEMU 实测 + pVM payload 的预算叠加）；AVF 是否允许同一 App 并持两个 VM 实例。
- [ ] **fabric 协议与编排**：现有资产盘点（`LaneBudgetLedger`、`DistccTcpVsockBridge`、`dv_pvm_crossfire.py`、P09 distcc 跨车道 ticket）；任务派发粒度设计（编译单元级 distcc vs 进程级）；编译器 ABI 一致性门（P09 既有约束，引用不重做）；UML 面如何作为第三个 worker 挂进 distcc 池（若 G1 过）。
- [ ] **真三合一的前景 segment**：non-protected AVF 设备现状——Pixel 系（pKVM non-protected）`CAPABILITY_NON_PROTECTED_VM` 可得性；MTK GenieZone（`/dev/gzvm`）非保护 VM 的实机证据；在该 segment 上「自有内核 + 硬件 vCPU + 多核」是否可经 `MANAGE_VIRTUAL_MACHINE`（adb grant）+ non-protected VM 交付；给出「protected-only fabric / non-protected 真三合一」的双轨路线图。
- [ ] **产品语言**：这条路线对外的准确表述（不写「硬件加速单车道」「完美调度」等不可证伪措辞；引用 ACR 草案 §4 文案纪律三词定义）。

## 交付

- `docs/research/2026-11-XX-pvm-fabric-route.md`：双轨路线图 + 能力上限表 + 缺口清单（哪些需要设备窗口验证，哪些纯设计可定）；
- 更新本 map 的 Decisions so far。

## 停止条件

- 任何「让 protected VM 接受第三方内核」的方案出现 → 记录并标注「需平台信任链变更，不可交付」，不展开设计。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。参考：10-28 文档 §3/§5；会话 23 §7.1（本机 AVF 服务缺失——注意该结论是单设备样本）；ACR 草案 §1/§4。
- 2026-10-31 / CodeBuddy（fabric-batch1 轮）：resolved。五问闭环（AOSP Virtualization main 源码级核验 + 先例互证）：①Microdroid vCPU 公开 SystemApi 仅 1/match_host 两档，任意 N 走 custom config 触发 USE_CUSTOM_VIRTUAL_MACHINE（signature|development）；②AVF 无每 App VM 数上限，两 VM 并存真约束是资源账本（pVM 内存捐赠+不可换出+lmkd 可整杀 crosvm）→ distcc 本地回退为常态路径；③决定性新事实：MANAGE_VIRTUAL_MACHINE=signature|preinstalled|development 且 AVF API 全 @SystemApi not for third party → Play 渠道第三方 App 在任何 AVF 设备（含 non-protected Pixel）均无 pVM 生产通道，双轨都只能走开发/预装通道；④双轨路线图落定（轨道 A=protected 组合编排：QEMU 生产面+Microdroid 编译 worker+UML 第三 worker 挂接条件表；轨道 B=non-protected 自有内核自定义 VM 完整 distccd 节点，Pixel 8–10 有 Podroid 在营先例）；⑤产品语言正反例齐备，新增禁用「真三合一作普通功能承诺」「同一内核跨车道共享」。停止条件未触发（customKernelImage 记录为「需平台信任链变更，不可交付」未展开）。交付：docs/research/2026-11-05-pvm-fabric-route.md（含缺口清单 D-1~D-6 纯设计可定 + 设备窗口 W-FAB-01~06）。未验证项：全部设备窗口项 not-tested（W-FAB-01~06）。
