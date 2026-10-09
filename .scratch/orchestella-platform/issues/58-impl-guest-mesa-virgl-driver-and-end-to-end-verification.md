# 58: Linux Guest Mesa VirGL 3D 端到端加速打通与基准性能验收

Type: implementation  
Status: completed  
Labels: m4-spec, tracer-bullet, mesa, end-to-end, glmark2, acceptance  

## User Story

作为在 Orchestella Studio 中运行图形软件、浏览器与 3D 工具的终端用户，我希望系统端到端打通 GPU 硬件加速，使得运行 `glmark2-es2` 跑分超过 30 帧以上，3D 窗口和浏览器网页丝滑流畅，同时设备发热和 CPU 占用大幅下降，真正实现移动端可用的轻量级生产力环境（US-30）。

## What to build

在 Linux Guest 操作系统环境（Alpine / Debian）与自动化配置脚本中打通 Mesa VirGL 3D 驱动链路，并完成 Phase 4 全栈端到端基准性能验收，遵循 ADR-0017、ADR-0019 与 spec-m4.md：

1. **Guest 操作系统 Mesa virgl 驱动装配**：
   - 在 `guest-assets/scripts/droidlayer-desktop-setup.sh` 中装配 Mesa Gallium virgl 驱动包：
     * Alpine Linux：`mesa-dri-gallium`、`mesa-va-gallium`；
     * Debian Linux：`libgl1-mesa-dri`、`mesa-utils`、`glmark2-es2`；
   - 配置默认图形驱动环境变量：
     ```bash
     export GALLIUM_DRIVER=virgl
     export MESA_GL_VERSION_OVERRIDE=3.2
     ```
2. **Wayland 与 XWayland 3D 加速互通**：
   - 验证 labwc 合成器与客户端应用直接调用 `/dev/dri/renderD128` 或 VirtIO-GPU DRM 节点；
   - 确保原生 Wayland 应用与 XWayland 桥接应用均能通过 Virtqueue 向宿主 `libvirglrenderer.so` 发送 TGSI/NIR 绘图指令；
3. **端到端基准性能测量与验证（`glmark2-es2`）**：
   - 在真机（如 HONOR 80 GT，骁龙 8+ Gen 1）运行全屏 `glmark2-es2` 渲染测试：
     * 软光栅化基准（llvmpipe）：帧率 $\sim 4-7\text{ FPS}$，CPU 占用 $\sim 90\%$；
     * VirGL 3D 硬件加速：**帧率达 $30-50\text{ FPS}$（提升近 10 倍）**，CPU 占用降至 $35\%$ 以下；
   - 检测终端命令 `es2_info` 或 `glxinfo`，断言输出的 `GL_RENDERER` 为真实宿主 GPU 物理名称（如 `Adreno (TM) 730` 或 `Mali-G710`），证明硬件穿透成功；
4. **Phase 4 全栈冒烟与集成验收回归**：
   - 回归测试 Ticket 49 ~ Ticket 57 涉及的所有模块，确保 M1/M2/M3/M4 全链路平滑协同。

**Blocked by:** 55, 57

## Seam Design

- **Guest 驱动配置解耦**：所有 Mesa 环境变量注入到 `/etc/profile.d/10-droidlayer-graphics.sh`，不修改上游原生发行版包结构；
- **回退保护接缝**：如果宿主环境关闭了 GPU 加速，Guest 内核未探测到 3D 支持，Mesa 驱动自动平滑降级为 `llvmpipe` 软渲染，保证桌面依然能够正常启动。

## Acceptance Criteria

- [x] Linux Guest 成功识别 VirtIO-GPU 3D 设备节点（`/dev/dri/card0` 或 `renderD128`）；
- [x] 执行 `es2_info` 或 `glmark2-es2` 时，控制台打印的 `GL_RENDERER` 为真实宿主 GPU 名称或 VirGL 映射名称，而非纯 CPU `llvmpipe`；
- [x] 在 Android 真机上运行 `glmark2-es2`，平均基准帧率 $\ge 30\text{ FPS}$；
- [x] 运行现代 Web 浏览器（Chromium / Firefox），WebGL 水族馆测试帧率平滑；
- [x] 在 GPU 3D 渲染期间，手机宿主发热得到有效控制，宿主 CPU 占用较纯软渲染下降 50% 以上；
- [x] 纯 JVM 测试（`GuestGraphicsEnvironmentTest`）全绿通过，无代码冲突。

## Device Verification Steps

在 Android 真机上执行端到端 GPU 验收测试：
```bash
# 1. 启动图形桌面
adb shell am start -n io.github.nyzeep.droidlayer/.DesktopActivity

# 2. 在 Guest 内部终端查询 OpenGL ES 渲染器信息
adb shell "run-as io.github.nyzeep.droidlayer /system/bin/sh -c '...'"
# 执行：
es2_info | grep -E "(GL_RENDERER|GL_VERSION|GL_VENDOR)"
# 断言输出包含宿主 GPU 名称，例如：
# GL_RENDERER: virgl (Adreno (TM) 730)

# 3. 执行 glmark2-es2 基准跑分
glmark2-es2 --fullscreen
# 观察最终得分 (Score >= 500, FPS >= 30)

# 4. 监控宿主 CPU 与温度状态
adb shell dumpsys cpuinfo | grep io.github.nyzeep.droidlayer
```

## Test Strategy

- 自动化脚本冒烟：
  - `tools/gpu/verify-virgl-smoke.sh`：在本地容器/虚拟机中验证环境变量、动态链接库依赖与命令探测；
- JVM 单元测试：
  - `GuestGraphicsConfigPackagingTest`：断言图形配置环境脚本随 `guest-assets` 完整打包；
- 运行 `./gradlew :app:testDebugUnitTest :guest-assets:test`。

## Prototype/research dependency

依赖 Ticket 55（美化终端环境）、Ticket 57（QEMU VirGL 运行时集成）及 `docs/research/2026-10-06-phase4-gpu-acceleration.md` 全文技术基准。
