# Orchestella Studio Phase 3（M3 里程碑：系统互通、音频输出与外设集成）工程交接文档

> 版本：4.0（2026-10-06）｜ 维护方：Chief Architecture & Release Strategist ｜ 接收方：All Phase 3 Agent Sessions  
> 性质：**Phase 2 竣工归档与 Phase 3 启动的权威交接入口（Handover Entry）**。

---

## 0. 事实优先级与当前基线状态（Fact Baseline）

当本文档与任何其他历史信息源冲突时，按以下优先级判断：
1. **当前 repo / tracker / Git 实际状态**（`main` 分支最新提交、`.scratch/droidlayer-platform/`）
2. **已批准的 CONTEXT.md / ADR-0001 ~ ADR-0017 / spec.md / spec-m2.md**
3. **已归档的复盘与发版报告**（`phase1-mvp-retrospective.zh-CN.md`、`phase2-m2-retrospective.zh-CN.md`、`v0.2.0-alpha-release-notes.zh-CN.md`）
4. **本交接文档**
5. **历史聊天记录**（无约束力）

### 当前项目基线事实：
- **项目正式名称**：**Orchestella Studio**（项目标识与工程目录：`Orchestella-Studio`）；
- **GitHub 官方仓库**：[`https://github.com/Nyzeep/Orchestella-Studio`](https://github.com/Nyzeep/Orchestella-Studio)；
- **最新正式发版**：`v0.2.0-alpha`（Commit `04bae9f`，Tag `v0.2.0-alpha` 已正式发布）；
- **Phase 1（M1 终端底座）与 Phase 2（M2 Wayland 图形桌面）**：
  - Ticket 16 ~ 37（Phase 1 共 22 张工单）100% 验收合流；
  - Ticket 38 ~ 43（Phase 2 共 6 张工单）100% 验收合流；
- **CI / 自动化门控状态**：
  - `:runtime:contract:check` 15 项契约测试 100% 绿灯；
  - `:app:testDebugUnitTest` 130 项纯 JVM 单元测试 100% 绿灯；
  - `:runtime:engine-avf:check` 40 项测试 100% 绿灯；
  - `guest-assets` 纯 JVM 测试与 ShellCheck 零告警通过；
  - Android Lint 零错误通过；
- **Git 主干状态**：`origin/main` 保持最新，工作树完全纯净（Clean）。

---

## 1. Phase 3 核心使命：M3 里程碑（系统级深度互通、音频与外设）

在 Phase 2 中，项目确立并交付了高性能 Wayland 原生移动图形桌面、多指手势、Android IME 中文无损穿透以及双执行引擎对齐。
进入 **Phase 3（M3 里程碑）**，核心使命是消除移动宿主与 Linux Guest 之间的“体验孤岛”，构建系统级深度互通：

### 1.1 核心攻坚主题与候选技术路线
1. **剪贴板双向无损同步通道（Clipboard Bridge）**：
   - 痛点：移动端复制的命令/Token 无法直接粘贴进 Linux 浏览器或终端，桌面内选中文本无法复制回手机；
   - 架构路线：扩展策略中立 `BridgeControl` 或 Wayland `wl_data_device` 协议，打通 Android `ClipboardManager` 与 Guest Linux `wl-clipboard` / XWayland 剪贴板的双向增量同步。
2. **音频子系统低延迟流式输出（Audio Subsystem Output）**：
   - 依据 **ADR-0013**，音频输出正式进入研发范围；
   - 架构路线：在 `:runtime:contract` 抽象 `AudioStreamContract`；Guest 端配置 PipeWire / PulseAudio 虚拟音频汇聚端（Sink），通过专用 LocalSocket 或 vsock 端口（如 9104）流式传输 PCM 原始音频帧，宿主端基于 Android 原生 `AudioTrack` / Oboe 实现低延迟平滑播放。
3. **外部物理键鼠直通与相对指针捕获（External Mouse Pointer Capture）**：
   - 痛点：当用户连接蓝牙键盘或 OTG 鼠标时，Android 系统的默认鼠标捕获会产生双光标或边界受限；
   - 架构路线：接入 Android API 26+ `View.requestPointerCapture()`，在全屏桌面模式下捕获原始鼠标移动向量 $(\Delta x, \Delta y)$，直通给 Wayland 指针通道，提供纯正 PC 级鼠标操纵手感。
4. **SAF 文件直读与双向文件拖拽交互（File Drag & Drop & Direct Share）**：
   - 在已有的只读 Downloads 共享基础上，探索宿主文件拖入 Linux 窗口及 SAF 零拷贝双向写入机制。

---

## 2. 架构不可变性与防御性硬红线（Defensive Constraints）

后续所有工单与代码实现必须无条件遵循以下纪律：
1. **契约策略中立（ADR-0002）**：
   - `:runtime:contract` 严禁出现策略专属逻辑（严禁硬编码 AVF 或 QEMU）；所有能力差异必须通过 `CapabilityMatrix` 数据表达；
2. **Class A 基石资产绝对保护**：
   - `.toolchains/`、`artifacts/`、WSL 交叉编译容器沙箱及发布密钥保持绝对只读，严禁擅自修改或删除；
3. **资产卫生铁律**：
   - 严禁向 Git 仓库提交任何 `.log` 凭据文件；
4. **编译与代码质量**：
   - 全模块必须保持 Java 17 + `-Xlint:all -Werror` 零告警通过，Android Lint 零错误；
   - 纯 JVM 单元测试毫秒级回归标准不可动摇（继续保持接缝设计，严禁引入使纯 JVM 测试挂起的未隔离原生桩）；
5. **发布合规与零遥测（ADR-0010 / ADR-0015）**：
   - 保持严格的零遥测原则，绝不引入未经审计的第三方追踪 SDK。

---

## 3. 标准化调度工作流指导（Orchestrator Guidelines）

1. **工单创建与拆解**：
   - 针对 Phase 3 候选领域，通过 `/grill-with-docs` 推敲明确边界，产出 `ADR-0018`（及后续 ADR）与 `spec-m3.md`；
   - 拆解出具备明确依赖边（Blocked by）的垂直示踪箭工单（Ticket 44+）；
2. **高效并行派工**：
   - 继承 Phase 2 的成功经验，严格按照 `ORCHESTRATION_GOVERNANCE_POLICY.md` §6 的六项准则识别可并行工单，创建独立的 Worktree 物理沙箱（如 `DroidLayer_ticket44`）；
3. **双轴独立复审与合流**：
   - 严格按照 **Standards（规范轴）+ Spec（规格轴）** 执行代码复审，独立跑全量回归，产出 `review-report.zh-CN.md` 并裁决 APPROVE 后执行 `--no-ff` 合流至 `main`。
