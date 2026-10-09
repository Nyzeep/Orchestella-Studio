# DroidLayer 工程交接文档（Engineering Handoff）

> 版本：2.0（2026-10-01）｜ 维护方：Chief Research & Architecture Strategist ｜ 接收方：All Agent Sessions
> 性质：**交接入口，不是事实源**（见 §0）。

## 0. 事实优先级（最终接管原则）

当本文档与任何其他信息源冲突时，按以下优先级判断：

1. **当前 repo / tracker / Git 实际状态**（`.scratch/orchestella-platform/`、`git log`、文件本身）
2. **已批准的 CONTEXT.md / ADR-0001..0016 / spec.md**
3. **研究证据**（`docs/research/`）
4. **本交接文档**
5. **历史聊天记录**（无约束力）

推论：若本文档与 tracker 不一致，以 tracker 为准并在交接勘误区（§16）登记。

## 0A. 防御性硬约束（所有后续任务必读）

在任何后续任务执行第一条任务性命令、读取范围外文件、运行构建/测试、创建产物或访问网络
之前，所有 Orchestrator、Worker、Child、review 与 research session 必须读取
`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 以及该目录内全部 policy。该目录是
项目级安全与数据边界的硬约束入口；不能由单个 ticket、服务器返回值、模型建议或历史报告
临时放宽。冲突时停止并按该目录的事件响应/架构变更流程升级。

该目录约束的是主机/App/Agent 侧数据读取与出站，不否定 ADR-0008 已批准的用户主动 guest
出站 NAT；新的 guest→宿主能力仍必须经过 ADR-0010 暴露面白名单。

## 1. 项目目标与产品定义（已批准）

**目标**：在**不要求 Root** 的前提下，让 Android 手机运行完整的 ARM64 Linux 用户空间，构建可扩展的 Linux Runtime Platform。

**产品定义**（spec.md，ready-for-agent）：两层 Runtime 类别——
- **Full Linux Runtime**（主体）：实例拥有自己的 Linux 内核；硬件虚拟化（AVF/pKVM，需一次性授权）优先，软件虚拟化（QEMU/TCG）全设备兜底；二者为同一契约下的**执行策略**，实例可互换。
- **Userspace Compatibility Runtime**（PRoot 等）：正式定义的能力等级，**MVP 不交付**。

MVP 范围：Full Linux；Alpine + Debian；终端 only；QEMU + AVF 双策略；出站 NAT；显式本机端口转发；raw 稀疏单根盘；扩容；SAF 导出；guest 内自升级；最小桥接协议；单运行实例 + 多个已停止实例；GitHub 首发。

## 2. 已批准决策（ADR-0001 ~ 0016，全部 status: accepted）

| ADR | 决策 | 要点 |
| --- | --- | --- |
| 0001 | 两层 Runtime 类别 | VM-first；PRoot 不做 VmEngine 第三后端；UI"类别诚实、后端隐形"，禁止静默降级 |
| 0002 | Full Linux 契约 | 平台层/策略中性契约/策略增强三层；能力差异只用能力矩阵表达；契约测试对所有策略运行 |
| 0003 | 无 Root 三级 | MVP 出厂 L1+L2；授权向导先检测后引导；目标用户=技术早期采用者 |
| 0004 | 兼容性地板 | minSdk 26 / arm64-only / RAM≥3GB；低于地板诚实拒绝 |
| 0005 | Guest Kernel 契约 | 单一 pinned 版本两策略共用；能力清单缺项构建失败；CI 复现构建；MVP 打包分发 |
| 0006 | 发行版模型 | DistroManifest + 官方 tarball + 首次引导 Provisioning；OCI 出 MVP；首批 Alpine+Debian |
| 0007 | 存储模型 | raw 稀疏单根盘；guest 内自升级；扩容+SAF 导出进 MVP；快照不进；全私有+自动备份排除 |
| 0008 | 网络模型 | 默认出站 NAT + 显式转发规则（默认 127.0.0.1）；IPv6/VPN/host mode 非目标 |
| 0009 | 桥接协议 | 传输无关、版本化、per-VM token；六条 MVP 命令；控制/数据面分离 |
| 0010 | 安全边界 | 隔离承诺三级分述；暴露面白名单制；每实例随机凭据；承诺只加码 |
| 0011 | 生命周期 | 前台可靠、后台尽力而为；进程死亡=VM 停止但冷恢复；热恢复进雾区 |
| 0012 | 图形分期 | MVP 终端 only；M2=VNC+输入/IME、M3=Wayland、M4=GPU（原型前置） |
| 0013 | USB/音频 | MVP 均不做；USB 挂 M2+ QEMU 先行；音频挂 M2 仅输出 |
| 0014 | 许可证 | **Apache-2.0 + 零代码复制立场**；GPL 义务 CI 清单流程化 |
| 0015 | 发布渠道 | GitHub 首发、F-Droid 随后、Play 观望；签名密钥一次做对；更新检查可关 |
| 0016 | 多实例与实体模型 | MVP 单运行实例；Distro ≠ Image ≠ Instance ≠ Session 不合并 |

## 3. 不可违反的约束（Worker 不得触碰）

1. 不要求 Root；所有能力经公开/可授权机制实现。
2. 禁止"同一实例静默降级"；类别与性能徽章必须诚实（ADR-0001/0003）。
3. 性能承诺必须可测量；禁止"0% 损耗""接近原生（QEMU 档）"类表述（ADR-0001）。
4. 暴露面白名单制：新增 guest→宿主能力必须走显式决策 + ADR 修订（ADR-0010）。
5. 安全承诺只加码不减码；凭据禁止公开常量（ADR-0010）。
6. 零代码复制：GPL 参考项目只借鉴架构（ADR-0014）；禁 vendored Termux 系/Podroid/Droidspaces 代码与二进制。
7. 无遥测；更新检查可关闭（ADR-0015）。
8. spec 的承诺变更必须先重开对应 decision 票/ADR，禁止实现中静默改（用户裁定的变更纪律）。
9. Full Linux 契约的策略中性：执行策略差异只允许以能力矩阵数据表达（ADR-0002）。
10. 实体模型 Distro ≠ Image ≠ Instance ≠ Session 不得合并（ADR-0016）。

## 4. 信息置信度标注体系（全文适用）

| 标注 | 含义 | 本项目实例 |
| --- | --- | --- |
| **已批准决策** | 用户明确拍板，ADR 固化 | ADR-0001..0016 全部 |
| **研究结论** | 有来源证据的外部事实 | docs/research/ 六份（AVF 权限、Podroid 源码、Terminal 参考实现、许可证、国产 OEM、PRoot） |
| **建议（未批准）** | 历史讨论/前置文档中的主张 | 探讨文档1.md 中未被 ADR 吸收的部分（如"OCI 优先"已被 ADR-0006 **否决**）；不得当作需求 |
| **待验证假设** | 有依据但未实测 | AVF custom VM 实机网络/GPU、app 更新后 instance 身份、FUSE 随机写性能、pm grant 在 OEM 门控下可用性 |
| **需真机原型** | 只能靠设备实验 | 票 17（P1 AVF e2e）、票 18（P2 QEMU/TCG 基线） |

**Worker 绝对不能自行修改的内容**：ADR 内容与 status；CONTEXT.md 词条；spec.md 的承诺；暴露面白名单；桥接命令集（走版本流程）；已关闭 decision 票的结论。修改的唯一通道 = 架构变更门（ARCHITECTURE_CHANGE_POLICY.md）。

## 4A. Agent / Model Capability Tiering & Routing Policy

> 本节是所有后续 Agent session 的**模型与能力路由单一事实来源**。`ORCHESTRATION_GOVERNANCE_POLICY.md` 负责把本节结论落实到 dispatch packet、委派和生命周期；它不得覆盖本节的能力与层级选择。模型能力是任务匹配依据，不是工程决策权限。

### Purpose

模型与 Agent 按 **能力层级（Capability Tiers）** 与 **risk / cost of error** 路由，彻底废除硬编码特定商业模型代号的遗留机制，实现模型中立化（Model-Agnostic）治理。路由综合考量不可逆性、blast radius、验证成本、系统边界和错误的补救代价。

### Capability Tiers（四大能力层级契约）

为保障工程治理的长期稳定性与跨模型迁移弹性，定义四大标准化能力层级：

1. **Architect Tier（高推理/架构层）**
   - **核心特征**：具备极高逻辑推理深度（High/Extended Reasoning）、大上下文全局把握、严谨的跨系统边界权衡与架构决策能力。
   - **职责范围**：主导系统设计、ADR 制定/修订、系统边界裁定、高风险系统与安全审计、重大决策门禁。
2. **Implementation Tier（高执行/代码层）**
   - **核心特征**：具备卓越的代码生成精度、严格遵循 TDD、深度理解 Bionic/POSIX/JNI 契约、精通构建脚本与指令集优化。
   - **职责范围**：在已批准 scope、ADR、spec 和 packet 内高效编码、测试编写、构建链维护与局部重构；不得自行改变架构或扩大 scope。
3. **Reviewer Tier（独立审计层）**
   - **核心特征**：具备批判性审查能力、严格保持角色独立，擅长代码规范走查（Standards）与规格偏离度审查（Spec Alignment）。
   - **职责范围**：审查跨模块边界、架构契约、安全白名单、生命周期及并发数据竞争风险；发现冲突时冻结实现并升级，绝不自我宣布通过。
4. **Lightweight / Research Tier（调研与信息获取层）**
   - **核心特征**：高响应吞吐、低延迟、高效的信息检索与结构化归纳能力。
   - **职责范围**：执行外部技术信源探索、官方文档检索、日志分析、元数据校验与代码库机械性巡检。

### Source of Truth

- repo、tracker、已批准 ADR、spec 和当前工作树提供工程事实；它们的优先级仍以 §0 为准。
- 历史模型测试报告是参考输入，不是产品事实源，绝不能覆盖 repo、tracker、ADR 或 spec。
- **Capability tier determines who should handle a task; it does not determine who is allowed to change the project's decisions.** 拥有更高能力层级的 Agent 同样不能自行改变产品需求、ADR、spec 承诺、架构边界或 ticket scope。
- **A worker executes approved intent; it does not silently redefine approved intent.** 发现冲突时必须停止并升级，不得以“实现困难”为理由顺手改设计。

### Default Roles & Tier Mapping

| 角色 | 契约能力层级 | 职责边界 |
| --- | --- | --- |
| Primary Agent / Orchestrator | **Architect Tier** (High Reasoning) | 读取事实、形成计划和 invariants、选择风险路由、创建 dispatch packet、协调验证与集成；不因此获得改写产品决策的权力 |
| Implementation Worker | **Implementation Tier** (High Code Execution) | 在已批准 scope、ADR、spec 和 packet 内实现；不得自行改变架构、产品语义或扩大 scope |
| Architecture / Security Reviewer | **Reviewer Tier** (Independent Dual-Axis Review) | 独立审查跨边界、架构、安全、契约、生命周期和存储完整性风险；发现冲突时冻结实现并升级 |
| Critical Auditor | **Architect Tier** (Maximum Depth, Independent) | 处理 critical decision、catastrophic-risk、最终架构/安全审计；必须保持独立，由独立于原 Worker 的会话执行 |
| Auxiliary / Research Agent | **Lightweight / Research Tier** | 负责一手资料研读、官方技术文档抓取、日志走查与依赖核对，支撑主会话决策 |

默认角色不是强制每个 ticket 都调用的流水线。低风险任务可以直接进入 Worker → tests → review；高风险任务才增加规划、升级和独立审计。

### Risk-Based Routing

| Risk | 典型工作 | 默认能力路由 |
| --- | --- | --- |
| **Risk 0 — Mechanical** | formatting、文档、boilerplate、简单 DTO/日志、简单测试生成、已明确设计下的机械修改 | `Implementation Tier` 或 `Lightweight Tier` |
| **Risk 1 — Normal implementation** | 已明确 ADR/spec 的 feature、普通 Kotlin implementation、明确接口下的 adapter、普通 bug fix/test repair | `Implementation Tier` |
| **Risk 2 — Integration / Multi-file** | 跨模块实现、runtime wiring、Android ↔ native integration、QEMU integration、bridge、networking、storage、lifecycle | 默认 `Implementation Tier`；必要时升级 `Architect Tier` 介入 |
| **Risk 3 — Complex Engineering** | 跨 domain implementation、complex debugging、tricky runtime interaction、困难 integration、涉及多个已批准 contract 的修改 | `Architect Tier` (指导/排障) + `Implementation Tier` (执行) |
| **Risk 4 — Architecture / Security / System Boundary** | AVF、pKVM、QEMU architecture、guest kernel contract、host ↔ guest boundary、security、capability matrix、lifecycle semantics、storage integrity、runtime contract | `Architect Tier` (深度推理) + 独立 `Reviewer Tier` |
| **Risk 5 — Critical Decision / Audit** | 修改 accepted ADR 核心结论、产品边界、data corruption risk、security-critical change、architecture rewrite、最终安全/架构审计、高风险 AVF 结论 | `Architect Tier` (顶格推理)，并要求独立的二次 review / audit |

`Risk` 是 task risk，不是模型权限等级；同一 ticket 的不同子任务可以有不同 Risk。选择理由必须记录在 dispatch packet 中。

### Escalation Rules

出现以下任一情况，Worker 必须停止越界修改、保留证据并升级，不能“顺手调整设计”：

- ADR 与代码冲突，或 spec 与实现要求冲突；
- 当前 ticket 需要改变产品语义、产品边界、已批准 contract 或 dependency graph；
- 发现新的 security boundary，或 AVF 行为与预期不一致；
- kernel contract、host ↔ guest contract、data persistence semantics 需要变化；
- benchmark 结果可能改变 acceptance criteria；
- 现有 contract 无法解决问题，或出现 architecture smell。

一般复杂性、integration blocker 或难调试问题升级到 `Architect Tier (Standard/High)`。涉及 architecture、security、AVF、kernel、data integrity 或 accepted ADR 变化时升级到 `Architect Tier (Deep Reasoning)`；critical decision 或最终审计必须由独立的 `Architect Tier / Reviewer Tier` 形成新的决策记录和独立审计结论。

能力调度不能制造不存在的 infrastructure capability。真实环境 blocker 必须如实记录和升级，不得用提升能力层级代替真实环境验证。

### Worker Constraints

- Worker 只能执行 approved intent：不得改 ADR、spec、CONTEXT、产品需求、accepted contract、ticket status 或 ticket scope；不得因能力层级更强而获得例外。
- Worker 不得自行降 reasoning、换模型、重新定义主要 workflow 或让实现结果反向成为架构决策；不确定时停止并回报 Orchestrator。
- Orchestrator 也不能以能力层级替代用户/架构变更门的裁决。任何新决策必须走 `ARCHITECTURE_CHANGE_POLICY.md`。
- 已经 active 的 session 不因本节自动重派；若需要新的 Worker、review 或 escalation，按本节和当前真实状态重新形成 packet。

### Review Policy

推荐工作流如下；它是按风险选择的工作方式，不是每个 ticket 的强制调用清单：

```text
Planner / Orchestrator
        ↓
 Architect Tier
        ↓
Approved plan / invariants
        ↓
Implementation
        ↓
Implementation Tier
        ↓
Tests / CI
        ↓
Review
        ↓
Reviewer Tier
        ↓
Critical?
   ┌────┴────┐
   │         │
   No       Yes
   │         │
   ▼         ▼
 Merge   Independent Architect Tier Audit
```

- Risk 0–1 可采用 `Implementation Tier → tests → Reviewer Tier`；Risk 2–3 通常采用 `Architect Tier（规划/复杂问题）→ Implementation Tier → tests → Reviewer Tier`。
- Risk 4–5 必须由 Architect Tier 参与规划/审查；涉及 critical decision、security 或 architecture 的结论不得由同一个 Agent 自己宣布通过。
- Benchmark 必须坚持 `raw data → deterministic threshold → acceptance`；不能用 LLM opinion 替代真机数据或验收门槛。
- Review 的能力层级也必须写入 packet；reviewer 必须独立于被审查 Worker。

### Current Ticket Snapshot & Routing

以下是反映 Ticket 26 验收合并后最新项目状态的路由快照；它记录工程事实和建议能力层级：

| Ticket | 当前事实 | Risk | 能力层级路由 |
| --- | --- | --- | --- |
| **26** | **`completed`，已正式合并入 `main`**（commit `5cd059d` / `d91a0ac`）；QEMU 软模拟基线全绿，HONOR 80 GT 真机 167 项验收全过 | 已完结 | 历史成果作为 L1 战略基石；不再重新派发 |
| **25** | `completed`，已 merge 到 `main` | 已完结 | 作为 Guest 资产与内核契约基线 |
| **17 — AVF E2E** | `open`（升阶至 Phase 1 双引擎自适应核心）；涉及 AVF 真机与 Android 边界 | **Risk 4–5** | `Architect Tier` 负责边界判定；`Implementation Tier` 负责适配开发；独立 `Reviewer Tier` 审计 |
| **18 — QEMU / TCG benchmark** | `open`；Ticket 26 已产出真机数据，需系统化固化性能基准 | **Risk 3–4** | Methodology：`Architect Tier`；数据收集：`Implementation Tier`；验收结论：`Architect Tier` |
| **27–37** | 处于 DroidLayer 2.0 分期规划路径中（Phase 1–4） | 按需评定 | 依据具体工单的 scope、risk 与 verification cost 动态派发 |

### Facts vs Policy

- **事实**：Ticket 状态、worktree 状态、builder blocker、ADR/spec 是否 accepted、benchmark 原始数据和真实设备结果；这些必须回到 repo、tracker、Git 和外部证据核对。
- **Policy**：上表的 Risk 分类、能力层级、升级条件、Worker 限制和 review/audit 路径；它不能把建议变成事实，也不能改变事实。

### Important Non-Goals

- 本节不是新的架构决策、产品需求、验收标准或 ticket 放行。
- 本节不授权任何 Agent 修改 ADR、spec、tracker 状态、dependency graph、生产代码或宿主环境。
- 本节不要求所有 ticket 使用最高能力层级，也不以代码量推断风险；模型能力只服务于已批准的工程意图。

## 4B. Artifact & Repository Organization

所有后续 Session 在创建文件、报告、证据、实验产物或生成输出前，必须遵守
`docs/agent-handoff/ARTIFACT_ORGANIZATION_POLICY.md`。该文件是 artifact 与 repository
organization 的唯一 policy；本 handoff 只提供入口，不复制其完整内容。Worker/Child 必须
使用 packet 指定的 canonical path，生成物与临时资源必须与 source-controlled evidence 分离。

## 4C. Project Directory Boundary & Worktree Freshness

Git worktree 的目录边界、创建/迁移命令和接手前 freshness verification 以
`ORCHESTRATION_GOVERNANCE_POLICY.md` §7A 为唯一 policy。允许的项目工作区是
`D:\Development project(开发项目)\DroidLayer开发目录\*`；不得新建注册在
`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\worktrees\*` 下的 active Git worktree。
外部资源根仍按 `HOST_ENVIRONMENT_POLICY.md` 使用，历史路径和审计记录不因本规则批量删除。
任何 Agent 接手已有 worktree，必须重新报告 registered/physical path、HEAD、branch、
upstream、与 main 的关系、dirty/clean、diff、untracked、ignored 和 recent log；handoff
中的旧 HEAD 不能替代当前 Git 核验。

## 5. 仓库与 Git 状态（核实于 2026-10-01）

- 本次交接的 `main` 主分支已正式合流 **Ticket 26 里程碑提交（commit `5cd059d` / `d91a0ac`）**。当前工作区干净，无未处理冲突。
- `main` 现已完整包含 Ticket 25（Guest 资产构建基线、Alpine 3.24.1 镜像与内核）与 Ticket 26（全功能 Linux/QEMU 软模拟运行时、双抽象套接字控制协议、JNI 桥接层、16-page / 4-byte 对齐 APK 与 HONOR 80 GT 真机验收套件）。
- 历史独立开发分支 `ticket/26-linux-qemu-rebuild` 已通过 Pull Request #1 完成合流并完成生命周期收口；相关实验性日志与证据已在 `docs/reports/` 归档。

## 6. Tracker 状态（.scratch/orchestella-platform/，以当前事实为准）

- `map.md`：wayfinder 决策地图（Labels: wayfinder:map）。已记录 18 条 Decisions-so-far；随着 DroidLayer 2.0 路线图发布，部分雾区（图形栈演化、多发行版支持、容器化）已形成清晰规划。
- `spec.md`：MVP 产品规格 v1.0。
- `issues/`：Ticket 25 与 Ticket 26 均已标记为 **`completed`**。Ticket 17（AVF 真机端到端）纳入 Phase 1 规划，Ticket 18（性能基线）已有 Ticket 26 真机数据。后续 Ticket 27–37 正式纳入 DroidLayer 2.0 分期跃升演进路径。

## 7. Research 状态（全部已归档，docs/research/）

| 报告 | 关键结论（供工程与架构引用） |
| --- | --- |
| **2026-10-01-android-linux-ecosystem-and-hardware-acceleration** | 2026 最新虚拟化全景（QEMU vs AVF vs PRoot）；高通骁龙 8 Elite 封锁 Non-Protected VM 导致官方 Linux Terminal 停摆深度剖析；16 KB Page 规范与 JIT 代码缓存；Mesa Turnip/Venus 图形突破与 Wayland-over-SurfaceView 架构 |
| **2026-10-01-orchestella-architecture-evolution-roadmap** | DroidLayer 2.0 四阶段演进路线图：Phase 1 双引擎自适应架构 $\rightarrow$ Phase 2 多发行版与用户体验 $\rightarrow$ Phase 3 极速 Wayland 桌面与现代输入桥接 $\rightarrow$ Phase 4 轻量容器与移动开发者工作站 |
| 2026-09-05-前置研究独立审视 | AVF 权限边界（@SystemApi + development 级授权）；TCG 慢 5–20×；W^X/jniLib 约束；设备矩阵 |
| 2026-09-05-podroid-源码分析 | VmEngine/路由/rootfs/vsock 模式实证；教训清单（零校验、公开凭据、无 CI、GPL-2.0） |
| 2026-09-05-android16-terminal-参考实现 | 官方应用=特权组件；第三方可用"可授权子集"（nixos-avf/koiTerminal 实证）；gfxstream 链路 |
| 2026-09-05-license-inventory | crosvm=BSD-3、gfxstream=Apache-2.0；GPL 义务 CI 清单；组件许可矩阵 |
| 2026-09-05-cn-oem-avf-status | 361 款机型声明支持 AVF；OEM ADB 门控矩阵；pm grant 可用性待实测 |
| 2026-09-05-proot-modern-android | PRoot 实测开销与硬限制；交付路径（jniLib + 目录树实例） |


## 8. Prototype 状态

- **票 17（P1 AVF 端到端）**：open。**阻塞票 34**。需要用户提供 AVF 真机（Pixel 6+ 等，**尚未确认是否持有**）。重开触发器已写入票内。
- **票 18（P2 QEMU/TCG 基线）**：open。**阻塞票 35**（数字回填）；可复用票 25 产出。重开触发器：FUSE 随机写不可接受 → 重开 ADR-0007。

## 9. 未完成工单与原型（当前 14 张未完成，含 2 张 open prototype）

| ID | 标题 | 类型 | Blocked by | 状态 |
| --- | --- | --- | --- | --- |
| 25 | Guest 资产构建基线 | task | — | completed |
| 26 | 第一垂直切片：Alpine 实例 + shell（QEMU 档，含最小桥接基座） | implementation | 25 | ready-for-agent（分支实际在 active 实施） |
| 27 | 出站网络 | implementation | 26 | needs-triage |
| 28 | 停止·冷恢复·异常标记 | implementation | 26 | needs-triage |
| 29 | 端口转发规则 | implementation | 27 | needs-triage |
| 30 | 只读共享 Downloads | implementation | 26 | needs-triage |
| 31 | 桥接命令补全（通知/打开链接） | implementation | 28 | needs-triage |
| 32 | Debian 第二发行版 | implementation | 26, 27 | needs-triage |
| 33 | 实例导出与磁盘扩容 | implementation | 28 | needs-triage |
| 34 | AVF 执行策略（同一契约） | implementation | 26, 17 | needs-triage |
| 35 | 安全与体验硬化 | hardening | 26–33, 36, 37, 18 | needs-triage |
| 36 | AVF 对齐：网络+转发 | implementation | 34, 27, 29 | needs-triage |
| 37 | AVF 对齐：共享+恢复+桥接 | implementation | 34, 28, 30, 31 | needs-triage |
| 17 | P1 AVF 端到端验证 | prototype | 需用户 AVF 真机 | open |
| 18 | P2 QEMU/TCG 基线 | prototype | 25/26 产出 | open |

## 10. 依赖图与 MVP 执行序列

```
25 ─→ 26 ─┬→ 27 ─→ 29 ──────────────┐
          ├→ 28 ─→ 31                ├──→ 35 ←(18 数据)
          ├→ 30 ────────────────────┤
          ├→ 32                      │
          └→ 33(←28) ────────────────┘
26 ─(17 P1)→ 34 ─┬→ 36(←27,29)
                 └→ 37(←28,30,31) ─→ 35
```

执行序列：**25 → 26 → (27 ∥ 28 ∥ 30) → (29 ∥ 31 ∥ 32 ∥ 33) → 34(等 P1) → (36 → 37) → 35**。P2 可在 26 后随时插入。

## 11. 已知风险

1. AVF 政策风险（development 级权限"非第三方"定位；已以 QEMU 完全体对冲）。
2. FUSE 存储随机写性能未实测（唯一可能重开 ADR-0007 的项，P2 裁决）。
3. specialUse 前台服务的 Play 政策审查（Play 缺席已对冲）。
4. gfxstream 第三方可用性未证实（M4 前置，不阻塞 MVP）。
5. AVF instance 身份与 app/内核更新交互未实测（OTA 雾区）。
6. OEM ADB 门控可能波及 pm grant（未实测；授权向导失败路径已设计为一等场景）。

## 12. 已知未知数（全部挂原型/雾区）

P1/P2 的全部待测项；OTA 通道形态；benchmark 验收数字；Compatibility Runtime 契约设计；AVF 独立存活/热恢复/passt 等增强；UI/UX 具体交互。

## 13. 领域术语速览（完整定义见 CONTEXT.md，32 词条）

- **Full Linux Runtime / Userspace Compatibility Runtime**：两类并列的 Runtime 类别；前者有 guest 内核，后者无。
- **执行策略**：Full Linux 内部执行 guest 指令的方式（硬件虚拟化=AVF/pKVM；软件虚拟化=QEMU/TCG）；对用户不可见，除性能徽章。
- **Runtime 类别 / 实例（Instance）**：实例创建时归属唯一类别；实例 = 根盘 + 元数据，产品基础实体。
- **Distro / Image / Session**：发行版是目录项；镜像是二进制磁盘资产（装配输入 tarball / 产物根盘）；会话是与运行中实例的一次交互上下文。四实体不合并。
- **Full Linux 契约 / 能力矩阵 / 策略专属增强**：策略中性契约 + 数据化能力声明 + 显式增强。
- **DistroManifest / Provisioning**：发行版清单；首次引导在 guest 内装配根盘。
- **端口转发规则 / 暴露面白名单 / 隔离承诺**：入站唯一通道；guest 可触达宿主资源的显式清单；分级隔离表述。
- **Guest Bridge / Guest Agent / 共享目录**：传输无关版本化协议；guest 内代理；只读 Downloads 数据通道。
- **无 Root 等级（L1/L2/L3）/ 授权向导 / 支持地板 / 诚实拒绝 / 冷恢复 / 实例状态 / 根盘 / 导出**：见 CONTEXT.md 对应词条。

## 14. 文档入口索引

| 内容 | 路径 |
| --- | --- |
| 决策地图 | `.scratch/orchestella-platform/map.md` |
| 产品规格 | `.scratch/orchestella-platform/spec.md` |
| 工单 | `.scratch/orchestella-platform/issues/NN-*.md` |
| ADR | `docs/adr/0001..0016-*.md` |
| 词汇表 | `CONTEXT.md` |
| 研究 | `docs/research/` |
| tracker/labels/domain 规则 | `docs/agents/issue-tracker.md`、`triage-labels.md`、`domain.md` |
| 编排流程 | `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md` |
| 模型选择与 Agent 路由（唯一 policy） | 本节 §4A |
| 会话、委派、并行与工作树治理 | `docs/agent-handoff/ORCHESTRATION_GOVERNANCE_POLICY.md` |
| Artifact 与仓库组织（唯一 policy） | `docs/agent-handoff/ARTIFACT_ORGANIZATION_POLICY.md` |
| Worker 约束 | `docs/agent-handoff/AGENT_WORKER_CONTRACT.md` |
| 并行矩阵 | `docs/agent-handoff/PARALLELIZATION_MATRIX.md` |
| 报告规范 | `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md` |
| 架构变更门 | `docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md` |
| 宿主环境修改策略 | `docs/agent-handoff/HOST_ENVIRONMENT_POLICY.md` |
| 防御性硬约束（所有任务必读） | `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy |
| 当前状态审计 | `docs/reports/orchestration/current-state-audit-2026-09-19.zh-CN.md` |
| 历史上下文（非需求） | `探讨文档1.md` |

## 15. 新 Agent 接管 Checklist

1. 读本文档 + spec.md + map.md（低分辨率地图），并阅读 `ARTIFACT_ORGANIZATION_POLICY.md`。
2. 读取 `DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy；未完成这一步不得执行任务性命令。
3. 核对 tracker：`ls .scratch/orchestella-platform/issues/` 与 §9 表一致；`git log` 确认无未预期提交。
4. 读 CONTEXT.md（词汇表）与 AGENTS.md（工作约束）。
5. 抽读 ADR-0001/0002/0009/0010（架构骨架）。
6. 确认当前未完成工单状态与依赖（PARALLELIZATION_MATRIX.md）。
7. 以当前 tracker 确认候选票的真实状态与用户放行；不得从本文件的历史快照推导启动授权。
8. 先按本节 §4A 确定 session role、Risk、model 与 reasoning，再按 `ARTIFACT_ORGANIZATION_POLICY.md` 确定 report/evidence/output 路径，最后按 TICKET_EXECUTION_GUIDE.md 与 ORCHESTRATION_GOVERNANCE_POLICY.md 创建 dispatch packet；只启动 `ready-for-agent` 且已获放行的 ticket，已完成票不得重新启动。
9. 遇到与 ADR/现实冲突 → 走 `ARCHITECTURE_CHANGE_POLICY.md`，不静默改。

## 16. 交接勘误区（随发现随登记）

- 2026-09-05：`b556a49` 提交信息与 map Notes 的 "issues/25–38" 为笔误，实际为 **25–37**（13 张）；map 已修正。
- 2026-09-19：§5/§6 的 2026-09-05 仓库快照已由当前 Git/tracker 核实值替换；Ticket 26 的 tracker `ready-for-agent` 与独立分支报告 `active` 不一致，见当前状态审计，未由本次审计擅自改票状态。
