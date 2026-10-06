---
status: accepted
date: 2026-10-06
---

# ADR-0019: GPU 硬件加速渲染、驱动隔离与全栈交互体验架构

## Context

在 Phase 2（ADR-0017）与 Phase 3（ADR-0018）中，Orchestella Studio 成功构建了 60 FPS Wayland 移动桌面、低延迟音频与双向剪贴板。
然而，在真实真机运行与长周期使用中，暴露出系统级性能与用户交互断层：
1. **CPU 软光栅化瓶颈与严重发热**：Guest 内桌面环境（labwc / Wayland）依赖 `llvmpipe` 软渲染，运行大型浏览器或 3D 界面时 CPU 100% 满载，急剧发热并触发内核降频；
2. **呈现管线开销**：宿主基于 Java `DoubleBufferedRenderer` 和 `HardwareCanvas`，存在跨 JNI 像素搬运与双重缓冲开销；
3. **硬件参数不可控与 OOM 隐患**：CPU 核心与内存硬编码（如 `-smp 2`），无法根据宿主物理能力（如 16GB 平板 vs 6GB 手机）动态划拨，低配机型易触碰 Android LMK 闪退；
4. **存储资源物理泄漏**：`InstanceLifecycle.Store` 缺失删除接口，损坏或废弃实例的 `.raw` 磁盘镜像（动辄数 GB）无法回收；
5. **弱网与断流下载脆弱**：依赖单点官方源，缺少 HTTP Range 断点续传与多源调度，容易导致部署中断；
6. **终端与人机引导落后**：终端仅为简陋单色 `TextView`，缺少 ANSI 256 色解析与移动辅助键盘栏，整体 UI 缺乏现代感。

根据 2026-10-06 专项调研（`docs/research/2026-10-06-phase4-gpu-acceleration.md` 与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md`），本决议正式固化 Phase 4（M4 里程碑）全局架构设计。

---

## Decision

确立“**QEMU 内嵌 VirGL 3D 兜底、NDK 原生 PBO 呈现管线、VMware 级硬件参数定制、生命周期物理清扫闭环、多源弹性断点下载、Material 3 仪表盘与 ANSI 现代终端**”的统合架构体系：

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                   Orchestella Studio M4 全栈架构与技术拓扑全景                          │
└────────────────────────────────────────────────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. 用户体验与表现层 (:app - Android 17 / Material 3 Expressive)                       │
 │    - Material 3 Dashboard: 实例卡片流、状态动态指示、长按操作面板                      │
 │    - ModernTerminalView: VT100 / ANSI 256色与真彩色渲染、移动辅助按键栏 (Ctrl/Esc/Tab/|)│
 │    - SetupWizardActivity: 权限嗅探 -> 硬件检测 -> 推荐配置 -> 弹性拉取               │
 └──────────────────────────────────────────┬───────────────────────────────────────────┘
                                            │ 依赖策略中立接口
 ┌──────────────────────────────────────────▼───────────────────────────────────────────┐
 │ 2. 策略中立契约与模型层 (:runtime:contract & :runtime:instance)                       │
 │    - FullLinuxContract.Capability 扩容: GPU_HARDWARE_ACCELERATION                   │
 │    - VmHardwareConfig: vCPU (1~8), RAM (512MB~8GB), 稀疏磁盘 (2GB~64GB), GPU 开关   │
 │    - InstanceLifecycle.Store: 扩容 void delete(String id) 接口，绑定状态安全熔断     │
 │    - ResilientDownloadEngine: HTTP Range 断点续传、GitHub/CDN/国内镜像多源 Failover │
 └─────────────────────┬──────────────────────────────────────────┬─────────────────────┘
                       │ QEMU 启动配置 (QemuLaunchSpec)            │ 呈现数据流
 ┌─────────────────────▼────────────────┐        ┌────────────────▼─────────────────────┐
 │ 3a. QEMU 运行时与 GPU 驱动集成        │        │ 3b. 宿主 NDK 硬件呈现管线            │
 │ - 内嵌精简版 libvirglrenderer.so     │        │ - NativeGlesSurfacePresenter        │
 │ - 自研微型 virgl_android_gl_loader   │        │ - ANativeWindow + GLES3 PBO 双缓冲  │
 │ - 挂接 -device virtio-gpu-gl-device  │        │ - AChoreographer 锁频 (3ms 提前量)   │
 │ - 三重防御: EGL Robustness + 显存守卫 │        │ - 显式同步栅栏 native_fence_sync    │
 └─────────────────────┬────────────────┘        └────────────────┬─────────────────────┘
                       └────────────────────────┬─────────────────┘
                                                │
 ┌──────────────────────────────────────────────▼───────────────────────────────────────┐
 │ 4. Linux Guest 操作系统层 (guest-assets)                                              │
 │    - 驱动适配: 启用 Mesa virgl 驱动 (virgl_dri.so)，硬件透传 OpenGL ES 3.2           │
 │    - 桌面合成: labwc 开启硬件加速渲染，帧率压降至 60 FPS 满帧                        │
 │    - 现代 Shell 预设: Starship 极简提示符与 LS_COLORS 高亮语法集成                   │
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

### 关键架构约束与硬红线
1. **契约策略中立（ADR-0002）**：`:runtime:contract` 不出现 QEMU/AVF 专有分支，硬件配置由 `VmHardwareConfig` 统一描述；
2. **单进程内嵌模型**：放弃 Termux-virgl 多进程 Socket 模式，QEMU 与 `libvirglrenderer.so` 单进程共享内存直通；
3. **Class A 资产绝对保护**：`.toolchains/`、发布密钥及交叉编译容器保持只读，不向 Git 提交 `.log` 或庞大预编译库；
4. **安全防御纵深**：强制保留 $\ge 2\text{GB}$ 宿主物理 RAM，EGL 上下文配置 `GL_LOSE_CONTEXT_ON_RESET_EXT`，显存配额守卫限制 1024MB，防止整机 Kernel Panic；
5. **规范与环境治理**：并行 Worker 使用物理隔离 Worktree（`X:` / `Y:` / `Z:` 虚拟命名空间），严禁测试命令尾随 `gradlew.bat --stop`。

---

## Consequences

- **正面收益**：
  * Linux 桌面 3D 渲染帧率由 4~7 FPS 飙升至 30~60 FPS，CPU 负载大幅下降，移动端发热与降频显著缓解；
  * 宿主呈现单帧消耗降低至 3.5ms 以内，端到端输入到显示延迟低于 20ms；
  * 用户可完全自由配置虚拟机硬件资源，彻底回收废弃实例物理空间；
  * 移动网络下载容灾能力极大强化，ANSI 终端与 Material 3 界面使产品体验跃升至现代专业级。
- **潜在代价与妥协**：
  * QEMU 增加 `virtio-gpu-gl-device` 启动参数与编译产物体积增加约 800KB；
  * 极端老旧设备（无 GLES 3.1+）或 GPU 驱动极端缺陷时，需回退至 ANGLE 或 CPU 软光栅。
