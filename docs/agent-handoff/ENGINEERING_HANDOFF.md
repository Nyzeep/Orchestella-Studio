# DroidLayer 工程交接文档（Engineering Handoff）

> 版本：1.0（2026-09-05）｜ 交接方：Planning/Architecture Agent ｜ 接收方：Codex Orchestrator Agent
> 性质：**交接入口，不是事实源**（见 §0）。

## 0. 事实优先级（最终接管原则）

当本文档与任何其他信息源冲突时，按以下优先级判断：

1. **当前 repo / tracker / Git 实际状态**（`.scratch/droidlayer-platform/`、`git log`、文件本身）
2. **已批准的 CONTEXT.md / ADR-0001..0016 / spec.md**
3. **研究证据**（`docs/research/`）
4. **本交接文档**
5. **历史聊天记录**（无约束力）

推论：若本文档与 tracker 不一致，以 tracker 为准并在交接勘误区（§16）登记。

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

## 5. 仓库与 Git 状态（核实于 2026-09-05）

- 分支：`main`（唯一分支）；HEAD `b556a49`；24 个提交；工作区干净。
- 无产品代码——仓库当前只含文档、tracker、skills 配置（`.agents/skills/`）、`CONTEXT.md`、`docs/`、`.scratch/`、`skills-lock.json`、`探讨文档1.md`。
- 提交历史要点：`1afa387` bootstrap → `26ea95c` wayfinder map → `ecf78e1..08ac37f` ADR-0001..0016 → `9c72ada/6600767/18496cb/9b0dd00` 四张研究票 → `362b8e2` spec v1.0 → `b556a49` 实现工单发布。
- **勘误**：`b556a49` 提交信息与 map Notes 曾写 "issues/25–38"，实际文件为 **25–37**（13 张）。map 已在本交接提交中修正；提交信息无法改写，以本勘误为准。

## 6. Tracker 状态（.scratch/droidlayer-platform/）

- `map.md`：wayfinder 决策地图（Labels: wayfinder:map）。已记录 18 条 Decisions-so-far；雾区 5 条（Compatibility 契约设计、OTA/instance 身份、benchmark 验收数字、UI/UX 细化、AVF 独立存活等增强）。
- `spec.md`：MVP 产品规格 v1.0，**ready-for-agent**。
- `issues/`：37 个文件 = 01–24（wayfinder 决策票：**22 张 resolved**，**17/18 两张 prototype open**）+ **25–37（13 张实现轨道，全部 needs-triage）**。
- tracker 规则见 `docs/agents/issue-tracker.md`（本地 Markdown 约定 + Wayfinding operations）。

## 7. Research 状态（全部已归档，docs/research/）

| 报告 | 关键结论（供票引用） |
| --- | --- |
| 2026-09-05-前置研究独立审视 | AVF 权限边界（@SystemApi + development 级授权）；TCG 慢 5–20×；W^X/jniLib 约束；设备矩阵 |
| 2026-09-05-podroid-源码分析 | VmEngine/路由/rootfs/vsock 模式实证；教训清单（零校验、公开凭据、无 CI、GPL-2.0） |
| 2026-09-05-android16-terminal-参考实现 | 官方应用=特权组件；第三方可用"可授权子集"（nixos-avf/koiTerminal 实证）；gfxstream 链路 |
| 2026-09-05-license-inventory | crosvm=BSD-3、gfxstream=Apache-2.0；GPL 义务 CI 清单；组件许可矩阵 |
| 2026-09-05-cn-oem-avf-status | 361 款机型声明支持 AVF；OEM ADB 门控矩阵；pm grant 可用性待实测 |
| 2026-09-05-proot-modern-android | PRoot 实测开销与硬限制；交付路径（jniLib + 目录树实例） |

## 8. Prototype 状态

- **票 17（P1 AVF 端到端）**：open。**阻塞票 34**。需要用户提供 AVF 真机（Pixel 6+ 等，**尚未确认是否持有**）。重开触发器已写入票内。
- **票 18（P2 QEMU/TCG 基线）**：open。**阻塞票 35**（数字回填）；可复用票 25 产出。重开触发器：FUSE 随机写不可接受 → 重开 ADR-0007。

## 9. 待执行工单（15 张 open）

| ID | 标题 | 类型 | Blocked by | 状态 |
| --- | --- | --- | --- | --- |
| 25 | Guest 资产构建基线 | task | — | needs-triage |
| 26 | 第一垂直切片：Alpine 实例 + shell（QEMU 档，含最小桥接基座） | implementation | 25 | needs-triage |
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
| 决策地图 | `.scratch/droidlayer-platform/map.md` |
| 产品规格 | `.scratch/droidlayer-platform/spec.md` |
| 工单 | `.scratch/droidlayer-platform/issues/NN-*.md` |
| ADR | `docs/adr/0001..0016-*.md` |
| 词汇表 | `CONTEXT.md` |
| 研究 | `docs/research/` |
| tracker/labels/domain 规则 | `docs/agents/issue-tracker.md`、`triage-labels.md`、`domain.md` |
| 编排流程 | `docs/agent-handoff/TICKET_EXECUTION_GUIDE.md` |
| Worker 约束 | `docs/agent-handoff/AGENT_WORKER_CONTRACT.md` |
| 并行矩阵 | `docs/agent-handoff/PARALLELIZATION_MATRIX.md` |
| 报告规范 | `docs/agent-handoff/REPORTING_STANDARD.zh-CN.md` |
| 架构变更门 | `docs/agent-handoff/ARCHITECTURE_CHANGE_POLICY.md` |
| 历史上下文（非需求） | `探讨文档1.md` |

## 15. 新 Agent 接管 Checklist

1. 读本文档 + spec.md + map.md（低分辨率地图）。
2. 核对 tracker：`ls .scratch/droidlayer-platform/issues/` 与 §9 表一致；`git log` 确认无未预期提交。
3. 读 CONTEXT.md（词汇表）与 AGENTS.md（工作约束）。
4. 抽读 ADR-0001/0002/0009/0010（架构骨架）。
5. 确认 15 张 open 工单状态与依赖（PARALLELIZATION_MATRIX.md）。
6. 确认用户放行票 25/26（needs-triage → ready-for-agent 的转换权在用户/Orchestrator）。
7. 按 TICKET_EXECUTION_GUIDE.md 启动首个 ticket（推荐 25）。
8. 遇到与 ADR/现实冲突 → 走 ARCHITECTURE_CHANGE_POLICY.md，不静默改。

## 16. 交接勘误区（随发现随登记）

- 2026-09-05：`b556a49` 提交信息与 map Notes 的 "issues/25–38" 为笔误，实际为 **25–37**（13 张）；map 已修正。
