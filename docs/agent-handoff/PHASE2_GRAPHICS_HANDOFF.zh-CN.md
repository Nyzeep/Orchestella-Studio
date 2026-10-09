# Orchestella Studio Phase 2（M2 里程碑：图形桌面与输入集成）工程交接文档

> 版本：3.0（2026-10-05）｜ 维护方：Chief Architecture & Release Strategist ｜ 接收方：All Phase 2 Agent Sessions  
> 性质：**Phase 1 竣工与 Phase 2 启动的权威交接入口（Handover Entry）**。

---

## 0. 事实优先级与当前基线状态（Fact Baseline）

当本文档与任何其他历史信息源冲突时，按以下优先级判断：
1. **当前 repo / tracker / Git 实际状态**（`main` 分支最新提交、`.scratch/orchestella-platform/`）
2. **已批准的 CONTEXT.md / ADR-0001 ~ ADR-0016 / spec.md**
3. **已归档的复盘与发版报告**（`docs/reports/retro/phase1-mvp-retrospective.zh-CN.md`）
4. **本交接文档**
5. **历史聊天记录**（无约束力）

### 当前项目基线事实：
- **项目正式名称**：**Orchestella Studio**（项目标识与工程目录：`Orchestella-Studio`）；
- **GitHub 官方仓库**：[`https://github.com/Nyzeep/Orchestella-Studio`](https://github.com/Nyzeep/Orchestella-Studio)；
- **Phase 1（MVP 里程碑 · Ticket 16~37 全量工单）**：100% 达成闭环并通过双轴复审与真机实测（HONOR 80 GT · Android 15）；
- **首个正式发版**：`v0.1.0-alpha` 已正式签名、打标并推送到 GitHub Releases 页面（附带 `Orchestella-Studio-v0.1.0-alpha.apk` 安装包与中文特性说明）；
- **CI / Actions 状态**：GitHub Actions（Guest assets & CI 工作流）**100% GREEN (Success)**；
- **Git 主干状态**：`origin/main` 保持最新，工作树完全纯净（Clean）。

---

## 1. Phase 2 核心使命：M2 里程碑（Wayland 原生移动图形通道）

在 Phase 1 中，项目有意锁定了“终端 Only”，打牢了双引擎（QEMU TCG 兜底 + AVF/pKVM 硬件加速）、网络 NAT、端口转发、只读共享与安全凭据的底座。

根据 **ADR-0012 架构修订案（2026-10-01 修订）**，Phase 2 的核心目标是构建**高性能、低延迟、面向触控交互的现代 Wayland 原生移动图形桌面**：

### 1.1 核心技术路线与决策原则
1. **全面废除传统 VNC-First 路线，坚定实施 Wayland-First**：
   - 彻底摒弃传统 VNC（RFB）协议 CPU 差分轮询开销过大、发热严重、无硬件双缓冲以及触控延迟高的致命瓶颈；
   - 宿主端直接利用 Android 原生 `SurfaceView` / NDK `ANativeWindow` 承载图形呈现，构建共享内存（`ashmem` / `AHardwareBuffer` / `DMA-BUF`）高速零拷贝渲染面，实现移动桌面交互 60 FPS 满帧体验。
2. **Guest 内现代轻量 Wayland 合成器**：
   - Guest 内部运行轻量级合成器（如 `labwc` 或 `Weston`），提供流畅的窗口管理与 XWayland 兼容支持；
   - 支持通过桌面环境（如 XFCE4 / Sway / Openbox）或独立应用模式拉起 Linux GUI 程序（浏览器、文本编辑器、开发工具）。
3. **移动端深度输入集成（Input & IME Bridge）**：
   - **触控手势映射**：单指滑动仿真鼠标移动、单指点按仿真左键、双指点按仿真右键、双指滑动仿真滚轮；
   - **键盘焦点与按键条**：提供辅助快捷键浮条（Ctrl, Alt, Esc, Tab, Super），保障移动软键盘与外接物理键盘（蓝牙/OTG）输入；
   - **输入法（IME）悬浮穿透**：探索宿主 Android 拼音/五笔输入法向 Guest 应用直接注入文字的通道，解决 Linux 移动端中文输入难题。
4. **双引擎策略中立与契约一致性（ADR-0002）**：
   - 图形通道作为策略中立的抽象契约注入平台层；
   - 在 AVF 硬件虚拟化档（通过 vsock 或专用共享内存通道）与 QEMU 软件模拟档（通过 LocalSocket 协议桥或模拟显卡）均保持相同的行为契约与用户体验。

---

## 2. 架构不可变性与防御性硬红线（Defensive Constraints）

后续所有工单与代码实现必须无条件遵循以下纪律：
1. **契约策略中立（ADR-0002）**：
   - `:runtime:contract` 严禁出现策略专属逻辑或类名（严禁硬编码 AVF 或 QEMU）；所有图形能力差异必须通过 `CapabilityMatrix` 数据表达；
2. **Class A 基石资产绝对保护**：
   - `.toolchains/`、`artifacts/`、WSL 交叉编译容器沙箱及 `app-debug.apk` 基线资产保持绝对只读，严禁擅自修改或删除；
3. **资产卫生铁律**：
   - 严禁向 Git 仓库提交任何 `.log` 凭据文件（测试运行生成的日志文件只能保留在本地 `.scratch/` 或 `.toolchains/` 中）；
4. **编译与代码质量**：
   - 全模块必须保持 Java 17 + `-Xlint:all -Werror` 零告警通过，Android Lint 零错误；
5. **发布合规与零遥测（ADR-0010 / ADR-0015）**：
   - 保持严格的零遥测原则，绝不引入未经审计的第三方追踪 SDK。

---

## 3. 双会话高效协同作战体系设计

为了实现最高效的推进并避免单会话上下文过载（Context Rot / Token Exhaustion），Phase 2 采用**策略会话与专项会话两级协同架构**：

```
       ┌────────────────────────────────────────────────────────┐
       │   策略调度会话（Orchestrator Tier · 架构守护与全局调度）      │
       │   - 维护工单依赖图谱（DAG Frontier）                     │
       │   - 标准化派发 Worker 任务 Prompt                        │
       │   - 独立 Worktree 隔离运行、双轴（Standards+Spec）复审    │
       │   - 主干合并（Merge to main）、发布管理与 CI 监控           │
       └───────────────────────────┬────────────────────────────┘
                                   │
                 ┌─────────────────┴─────────────────┐
                 │                                   │
                 ▼                                   ▼
┌─────────────────────────────────┐ ┌─────────────────────────────────┐
│ 专项会话 1：图形架构调研与工单派发  │ │ 专项会话 2..N：代码实现专员      │
│ （Research & Scaffolding Tier） │ │ （Implementation Workers）      │
│ - 2026 前沿移动图形技术调研     │ │ - 独立 Worktree 物理隔离开发   │
│ - 运行 /grill-with-docs 架构推敲│ │ - 严格 TDD（红→绿→重构）节奏     │
│ - 产出 M2-spec.md 规格说明书    │ │ - 契约自测与回归验证闭环        │
│ - 派发示踪箭工单（Ticket 38+）  │ │ - 提交实施报告提请复审          │
└─────────────────────────────────┘ └─────────────────────────────────┘
```

### 3.1 会话分工与执行次序
1. **第一步（由专项会话 1 执行）**：
   - 启动【图形桌面与输入集成专项调研与工单派发会话】；
   - 充分联网调研 2026 最新技术（如 Android 14/15 上的 `AHardwareBuffer`、`termux-x11` 架构演进、`wayland-android`、`Sommelier` 协议代理等）；
   - 执行 `/grill-with-docs` 架构推敲，修订/增补 ADR-0012，产出权威规格文档 `.scratch/orchestella-platform/spec-m2.md`；
   - 将 M2 拆解为具有严格依赖边（Blocking Edges）的示踪箭工单（如 Ticket 38: Wayland 传输契约与协议桩、Ticket 39: SurfaceView 渲染面与双缓冲通道、Ticket 40: 触控映射与输入法桥接、Ticket 41: Guest 合成器自动化装配与轻量桌面运行）；
   - 汇报任务图谱至策略会话。
2. **第二步（由策略调度会话执行）**：
   - 接收 M2 工单图谱，进行全局项目依赖与并行性分析；
   - 按照拓扑前沿（Ready Frontier）开始派发实现工单 Prompt，调度 Implementation Workers 展开高质量并行构建！
