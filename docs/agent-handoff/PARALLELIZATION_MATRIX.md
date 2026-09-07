# 并行化矩阵（Parallelization Matrix）

> 依据：工单依赖图 + 计划模块归属（仓库尚无代码，模块为 ADR-0002 三层划分派生的**计划模块**；首批票落地后由 Orchestrator 校准本表）。
> 冲突面：LOW = 不同模块/不同文件；MEDIUM = 共享测试套件或数据文件；HIGH = 同一模块核心文件。Required capability 是 dispatch floor；实际模型与 reasoning 只按 ORCHESTRATION_GOVERNANCE_POLICY.md §3 选择。本表不能自动批准并行，六项并行 gate 见该策略 §6。

## 1. 计划模块归属表

| 模块域 | 内容 | 主要属主票 |
| --- | --- | --- |
| `guest-assets/` | 内核 config、initramfs、Provisioning 逻辑、SHA256 清单 | 25（创建） |
| `runtime/contract` | Full Linux 契约定义与契约测试套件 | 26（创建），27–31/34/36/37 扩展 |
| `runtime/instance` | 实例模型、状态机、元数据 | 26（创建），28 扩展 |
| `runtime/distro` | DistroManifest、校验 | 26（创建），32 扩展 |
| `runtime/capability` | 能力矩阵、检测、引擎选择 | 26（创建），34 扩展 |
| `runtime/engine-qemu` | QEMU 执行策略（SLIRP、QMP、磁盘、通道） | 26（创建），27/29/30 扩展 |
| `runtime/engine-avf` | AVF 执行策略（custom VM、vsock、TAP） | 34（创建），36/37 扩展 |
| `bridge/` | 桥接协议实现（宿主侧 + guest agent 基座） | 26（基座），28（power），31（notify/open-url），36/37（vsock 侧） |
| `network/` | 转发规则模型、持久化、对账 | 29 |
| `storage/` | 根盘管理、扩容、导出 | 33 |
| `ui/` | 实例列表、创建向导、授权向导、规则编辑、设置 | 26（最小面），27–31/33/35 扩展 |
| `terminal/` | 终端会话/视图 | 26 |
| `app/` | 应用装配、设置、更新检查 | 35 |
| `tests/contract` | 契约测试套件 | 全部策略票扩展（共享文件——冲突热点） |

## 2. 工单矩阵

| Ticket | Dependencies | Blocked by | 冲突面 | Owned modules | Forbidden modules | Can parallel? | 推荐批次 | Required capability |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 Guest资产基线 | — | 无 | —（首票） | guest-assets/ | 其余全部 | 否（serial-first） | B0 | L4 |
| 26 第一切片 | 25 | 25 | — | contract, instance, distro, capability, engine-qemu, bridge, ui, terminal（初始骨架） | engine-avf | 否（serial） | B0 | L4 |
| 27 出站网络 | 26 | 26 | 与 29 同模块（先后解）；与 28/30 LOW | engine-qemu(网络), contract, ui(徽章无), tests | engine-avf, storage | 是（B1 组内） | B1 | L3 |
| 28 停止/冷恢复 | 26 | 26 | bridge(power) + instance 状态机；与 30 LOW（guest-assets 装配配置） | bridge, instance, contract, ui, tests | engine-avf, storage | 是（B1 组内） | B1 | L3 |
| 30 只读共享 | 26 | 26 | 9p 数据面 + guest-assets 装配配置（与 28/32 LOW-MEDIUM） | engine-qemu(9p), bridge(数据面), guest-assets(挂载), contract, tests | engine-avf, network | 是（B1 组内） | B1 | L3 |
| 29 端口转发 | 27 | 27 | engine-qemu 网络层（与 27 串行后无冲突）；network/ + ui 规则编辑 | engine-qemu(hostfwd), network, ui, contract, tests | engine-avf, storage | 是（B2 组内） | B2 | L3 |
| 31 桥接补全 | 28 | 28 | bridge 命令集 + 白名单修订 | bridge, ui(通知), contract, tests | engine-avf, storage | 是（B2 组内） | B2 | L3 |
| 32 Debian | 26,27 | 26,27 | guest-assets 风味 + distro 数据（与 30 的装配配置 MEDIUM——错峰或文件分域） | guest-assets(Debian 风味), distro, tests | engine-avf | 是（B2 组内） | B2 | L3 |
| 33 导出/扩容 | 28 | 28 | storage/ + ui | storage, ui, tests | engine-avf, engine-qemu(仅读接口) | 是（B2 组内） | B2 | L3 |
| 34 AVF 策略 | 26,17 | 26,17 | engine-avf + capability + contract 注册（此时 27–33 可已合入，注意 rebase） | engine-avf, capability, bridge(vsock 传输), contract, ui(向导/徽章), tests | storage | 否（serial） | B3 | L4 |
| 36 AVF网络/转发对齐 | 34,27,29 | 34,27,29 | engine-avf + tests/contract（与 37 HIGH——建议串行 36→37） | engine-avf(网络/vsock 隧道), tests | storage, engine-qemu | 否（与 37 串行） | B4 | L4 |
| 37 AVF共享/恢复/桥接对齐 | 34,28,30,31 | 34,28,30,31 | engine-avf(9p-over-vsock 自研服务端——最大件) + tests | engine-avf, bridge, tests | engine-qemu | 否（36 之后） | B4 | L4 |
| 35 硬化 | 26–33,36,37,18 | 全部+P2 | app/ + 全局走查（无并行对象） | app, ui, 全局 | — | 否（最后） | B5 | L4 |
| 17 P1 AVF e2e（prototype） | 需用户真机 | — | 无（不进主干） | 临时分支/实验目录 | 主干全部 | 是（随时） | B2 起（可提前） | L4 |
| 18 P2 QEMU 基线（prototype） | 25/26 产出 | 26 | 无（实验分支） | 实验分支 | 主干全部 | 是（26 后随时） | B1 起 | L4 |

## 3. 批次计划

| 批次 | 票 | 并行性 | 备注 |
| --- | --- | --- | --- |
| **B0**（串行） | 25 → 26 | 串行 | 项目地基；26 完成后全图铺开 |
| **B1**（并行 ×3 + 原型） | 27 ∥ 28 ∥ 30；18（P2，实验分支） | 是 | 冲突面 LOW；30 与 28 仅 guest-assets 装配配置轻微交叠（不同文件域） |
| **B2**（并行 ×4 + 原型） | 29 ∥ 31 ∥ 32 ∥ 33；17（P1，若真机就绪） | 是 | 32 与 30 的 guest-assets 交叠——若 30 已在 B1 合入则无冲突 |
| **B3**（串行） | 34（← 26 + 17） | 串行 | AVF 策略证明切片；需 P1 数据与真机 |
| **B4**（串行推荐） | 36 → 37 | 36∥37 冲突面 HIGH（同模块），建议串行 | 9p-over-vsock 自研是最大件 |
| **B5**（串行） | 35 | 串行 | 全量回归 + 安全走查 + 数字回填 |

**Serial-only tickets**：25、26、34、36→37（相互串行）、35。
**关键并行纪律**：`tests/contract` 是共享热点——所有策略票扩展套件时，Orchestrator 必须确认同批无其他票正在修改同一契约测试文件，否则串行化。
