# AGENTS.md

Orchestella Studio（曾用工作名 DroidLayer）：无需 Root 的 Android 原生双引擎 Linux 运行环境与移动工作站 —— 在不要求 Root 的前提下，在 Android 手机上运行完整 ARM64 Linux 虚拟机（AVF 硬件虚拟化 + QEMU 软件虚拟化全透明降级、Mesa VirGL 3D 加速、Wayland 桌面、每实例随机凭据与持久后台保活）。

**当前源码状态**：0.6.0 / code600（main `0383393` 之后的稳定版准备轮，2026-10-29），公开安装包仍为 v0.4.5 开发预览版。本轮的发布工程、双包形态与四车道口径见 [README.md](README.md)（中文，默认） 与 [中文文档入口](docs/README.md)。开头产品描述是目标，不代表 AVF、GPU、桌面或所有设备均已验收；当前真实 AVF Guest 成功样本为 0。不要跳过决策直接写代码。

**当前主线**：QEMU 全设备可靠可用优先；AVF protected 是时间盒研究线，不阻塞发布。发布签名、QEMU/gvproxy 停止收敛、跨 Controller 单实例互斥、终端输入生命周期和 CI 门禁是 P0/P1 待完成项。

**文档跟踪**：`/docs/` 仍默认被 `.gitignore` 忽略；经审查的文档必须显式 `git add -f`，不要整体强制添加，且 `git add -f` 前必须先过敏感开发者数据检查门。2026-10-18 优化方案与两份 2026-10-15 支撑研究已显式纳入版本控制；历史阶段报告已移入 `docs/reports/archive/`，旧交接已移入 `docs/agent-handoff/archive/`。

**版本与构建通道**：发布版 / 内部开发版 / 隔离 QA 版的版本号、applicationId、签名与分发边界以 `docs/standards/VERSIONING.md` 第 5 节为硬约束；合并到 `main` 不等于发布，不自动递增版本号。

**历史阶段记录**：v0.1.5-preview 的“全链路测试通过”只说明当时所列范围，不能作为当前版本或后续硬件能力的证明。下列旧 platform map/default tracker 指针保留历史用途，本轮任务以明确指定的 avf-shizuku effort 为准。

- 治理规范与防御性硬约束：`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其目录内全部 policy；该目录规则是所有 Agent 的硬约束。
- 敏感开发者数据与真机证据：真机序列号/IMEI/Android ID/设备指纹及其哈希、真机截图/录屏/完整设备日志、真实 LAN/公网 IP/MAC、个人邮箱/本机路径、签名与凭据永不入库；设备证据只用稳定别名并放外部 artifact 根。提交前执行 [SENSITIVE_DEVELOPER_DATA_POLICY](docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md)。
- 当前决策地图：`.scratch/orchestella-fastlane/map.md`；`.scratch/orchestella-avf-shizuku/map.md` 为 AVF 线入口，`.scratch/orchestella-platform/map.md` 仅历史入口。
- 研究资料（已核实的外部事实）：`docs/research/`；旧 Phase 5 专项见 `docs/research/archive/2026-10-phase5/`。
- 当前报告索引：`docs/reports/README.md`；归档索引：`docs/reports/archive/README.md`。
- 架构决策索引：`docs/adr/README.md`；ADR-0015 缺失，不得按引用标题补造正文。

## Agent skills

### Issue tracker

本地 Markdown tracker：工单与 spec 存放在 `.scratch/`，当前 effort 为 `.scratch/orchestella-fastlane/`。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用五个默认 triage 角色：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

single-context：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 已知缺口与禁止补造

- `docs/adr/0015-release-channels.md`、`docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md` 以及 ADR-0017/0018/0019 引用的部分研究文档不存在；只能记录缺口，不能根据标题编造正文。
- ADR-0010 关于 protected VM 双向通信的绝对表述与官方 vsock 证据冲突，等待 ACR-02 裁定；不要直接改写 accepted 正文。
- 历史报告中的测试数量、版本号、签名状态和 AVF 结论只覆盖当时范围；当前事实以 0.6.0 源码与 `docs/standards/VERSIONING.md` 第 4 节的版本状态为准。
