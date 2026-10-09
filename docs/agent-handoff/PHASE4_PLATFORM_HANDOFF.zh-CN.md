# Orchestella Studio Phase 4（M4 里程碑：GPU 硬件加速与深度外设拓展）工程交接文档

> 版本：5.0（2026-10-06）｜ 维护方：Chief Architecture & Release Strategist ｜ 接收方：All Phase 4 Agent Sessions  
> 性质：**Phase 3 竣工归档与 Phase 4 启动的权威交接入口（Handover Entry）**。

---

## 0. 事实优先级与当前基线状态（Fact Baseline）

当本文档与任何其他历史信息源冲突时，按以下优先级判断：
1. **当前 repo / tracker / Git 实际状态**（`main` 分支最新提交、`.scratch/orchestella-platform/`）
2. **已批准的 CONTEXT.md / ADR-0001 ~ ADR-0018 / spec.md / spec-m2.md / spec-m3.md**
3. **已归档的复盘与发版报告**（`phase1-mvp-retrospective.zh-CN.md`、`phase2-m2-retrospective.zh-CN.md`、`phase3-m3-retrospective.zh-CN.md`、`v0.3.0-alpha-release-notes.zh-CN.md`）
4. **本交接文档**
5. **历史聊天记录**（无约束力）

### 当前项目基线事实：
- **项目正式名称**：**Orchestella Studio**（项目标识与工程目录：`Orchestella-Studio`）；
- **GitHub 官方仓库**：[`https://github.com/Nyzeep/Orchestella-Studio`](https://github.com/Nyzeep/Orchestella-Studio)；
- **最新正式发版**：
  - `v0.2.0-alpha`（Release 已发布，含 820KB 签名包）；
  - `v0.3.0-alpha`（Release 已发布，含 55.6MB 完整内嵌运行时签名包，Tag `v0.3.0-alpha` 已推送）；
- **前序里程碑全部 100% 达成闭环**：
  - **Phase 1（M1 终端底座）**：Ticket 16 ~ 37（22 张工单）全量验收合流；
  - **Phase 2（M2 Wayland 图形桌面）**：Ticket 38 ~ 43（6 张工单）全量验收合流；
  - **Phase 3（M3 系统互通、音频与外设）**：Ticket 44 ~ 48（5 张工单）+ 宿主真机联调攻坚全量验收合流；
- **CI / 自动化门控状态**：
  - `:runtime:contract:check` 18 项契约测试 100% 绿灯；
  - `:app:testDebugUnitTest` 170+ 项单元测试 100% 绿灯；
  - `:runtime:engine-avf:test` 18 个测试类 100% 绿灯；
  - `:guest-assets:test` 51 项纯 JVM 测试与 ShellCheck 零告警通过；
  - 全工程 84 个 Gradle 构建任务 100% 绿灯通过，Java 17 `-Werror` 零编译告警，Android Lint 零错误；
  - 荣耀真机（Android 15）端到端实测验证全线通过（终端、桌面、音频输出、剪贴板防环同步、物理鼠标相对指针捕获、文件拖拽）；
- **Git 主干状态**：`origin/main` 保持最新，工作树绝对纯净（Clean）。

---

## 1. Phase 4 核心使命：M4 里程碑（GPU 硬件加速渲染、Vulkan/VirGL 原型与外设深度透传）

在 Phase 2 与 Phase 3 中，项目成功交付了 60 FPS Wayland 原生移动图形桌面、低延迟流式音频、双向防环剪贴板与物理鼠标相对指针捕获。
然而，当前桌面依然依赖**CPU 软光栅化（llvmpipe / pixman）**，在运行大型 Web 浏览器（Chromium / Firefox）、重度图形界面或 3D 应用时，CPU 负担高且缺少现代 GPU API（OpenGL ES 3.x / Vulkan）加速。

进入 **Phase 4（M4 里程碑）**，核心攻坚任务包括：

### 1.1 核心攻坚主题与候选技术路线
1. **QEMU 软模拟档 GPU 硬件加速（VirGL 3D / virtio-gpu-virgl）**：
   - 目标：将 Guest 内的 OpenGL ES 调用通过 `virtio-gpu-gl` 设备转发至宿主端 `virglrenderer`，在 Android 宿主通过 EGL/GLES 上屏渲染；
   - 调研核心：`virglrenderer` 针对 Android NDK 的编译与裁剪、GLES3 翻译开销、共享内存与跨进程纹理同步。
2. **AVF 硬件虚拟化档 GPU 跨边界加速（gfxstream / rutabaga / virtio-gpu Native Context）**：
   - 目标：探索 Android 15/16 pKVM 环境下 GPU 跨边界硬件加速；
   - 调研核心：AOSP 官方 `gfxstream`（基于 Vulkan 的高效命令流转发）与 `rutabaga_gfx`，Stage-2 边界下的共享跨虚拟机内存（Shared Memory Gralloc / AHardwareBuffer 直通）。
3. **Android 宿主底层渲染器升级（NDK ANativeWindow + GLES3/Vulkan 纹理呈现）**：
   - 将当前的 Java `DoubleBufferedRenderer` / `Canvas` 绘制流水线平滑升级为 NDK 级纹理上传或 EGL 双缓冲呈现，进一步压缩显示延迟与 CPU 占用。
4. **多点触控与平板手势深度扩容（Wayland Pointer Gestures）**：
   - 实现 Wayland `zwp_pointer_gestures_v1` 协议扩展，将宿主多指手势（两指缩放 Pinch、三指/四指滑动手势）无缝映射至 Linux 合成器。
5. **USB 设备穿透预研（USB Redirection）**：
   - 在 QEMU 档预研基于 `usbredir` / `libusb` 的 USB 外设透传，评估外接 U 盘、串口调试器与游戏手柄接入可行性。

---

## 2. 架构不可变性与防御性硬红线（Defensive Constraints）

后续所有工单与代码实现必须无条件遵循以下纪律：
1. **契约策略中立（ADR-0002）**：
   - `:runtime:contract` 严禁出现策略专属分支（严禁硬编码 AVF 或 QEMU）；所有能力差异必须通过 `CapabilityMatrix` 数据化透明表达；
2. **Class A 基石资产绝对保护**：
   - `.toolchains/`、`artifacts/`、WSL 交叉编译容器沙箱及发布密钥保持绝对只读，严禁擅自修改或删除；
3. **资产卫生与换行符防御**：
   - 严禁向 Git 仓库提交任何 `.log` 凭据文件；
   - Linux Guest 脚本严格遵循 `.gitattributes`，确保全量 LF 单一流换行；
4. **编译与代码质量**：
   - 全模块必须保持 Java 17 + `-Xlint:all -Werror` 零告警通过，Android Lint 零错误；
   - 纯 JVM 单元测试毫秒级回归标准不可动摇（保持 Seam 接缝设计，严禁引入未解耦的 Android 原生桩）；
5. **Windows 虚拟盘符命名空间分区**：
   - 并行 Worker Agent 必须采用独立分区虚拟盘（Worker 1: `X:`、Worker 2: `Y:`、Worker 3: `Z:`），严禁争用；
   - 严禁在测试命令尾随 `gradlew.bat --stop`，杜绝进程挂死。

---

## 3. 标准化调度工作流指导（Orchestrator Guidelines）

1. **第一阶段：前瞻技术调研（Research Phase）**：
   - 派发专项技术调研任务，广泛搜集 2026 前沿最新实战方案，产出技术调研报告《`2026-10-xx-phase4-gpu-acceleration-research.md`》；
2. **第二阶段：架构决策与规格固化（ADR & Spec Phase）**：
   - 召开决策推敲，产出 `ADR-0019: GPU 硬件加速渲染与驱动隔离架构`；
   - 编写产品规格书 `.scratch/orchestella-platform/spec-m4.md`；
3. **第三阶段：工单拆解与并行流水线推进（Scaffolding & Implementation）**：
   - 拆解出具备清晰拓扑边界的示踪箭工单（Ticket 49+）；
   - 在独立物理 Worktree 中组织多 Agent 并行实现，严格执行 Standards + Spec 双轴复审，全量绿灯后合流至 `main`。
