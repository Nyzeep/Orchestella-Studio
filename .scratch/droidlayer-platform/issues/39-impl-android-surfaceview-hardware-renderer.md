# 39: Android 宿主 SurfaceView 高性能渲染面与双缓冲渲染器

Type: implementation  
Status: ready-for-review  

## What to build

在 `:app` 中实现 Android 宿主高性能 Wayland 图形呈现面与双缓冲硬件渲染器，彻底抛弃传统的 Java Canvas 软件拷贝，借助 NDK `ANativeWindow` 与 OpenGL ES 2.0/3.0 纹理映射流水线达成 60 FPS 满帧硬件上屏，遵循 ADR-0017。

主要包含：
1. **自定义呈现控件（`WaylandSurfaceView`）**：
   - 继承自 Android `SurfaceView`，负责管理 `SurfaceHolder.Callback` 生命周期（创建、尺寸变更、销毁）；
   - 将底层 `Surface` 对象下传给 NDK 原生渲染核心；
2. **NDK 双缓冲硬件渲染流水线（Native GLES Pipeline）**：
   - 封装 `ANativeWindow`，搭建 EGL 上下文与 OpenGL ES 2.0/3.0 渲染流水线；
   - 支持将内存缓冲区（ARGB8888 / XRGB8888 像素格式）快速上传为 GLES 2D 纹理（`glTexSubImage2D`）；
   - 顶点与片段着色器支持视口缩放与保持纵横比（Aspect Ratio Preserve）；
   - 接入 Android 原生 `AChoreographer` 垂直同步回调，严格按 60Hz/120Hz 刷新节奏交换双缓冲（Double-Buffered Swap），杜绝画面撕裂；
3. **QEMU 软模拟档图形通道集成**：
   - 通过 LocalSocket 建立 `@droidlayer-wayland` 抽象命名空间套接字连接；
   - 接收 Guest 传递的 `ashmem` / `memfd` 共享内存描述符，实现零拷贝像素映射；
4. **动态尺寸重协商（Dynamic Re-negotiation）**：
   - 监听屏幕旋转（横竖屏切换）或分屏尺寸变更，触发 `DisplayControl.resizeDisplay(w, h)` 通知 Guest 动态缩放桌面。

**Blocked by:** 38

## Acceptance criteria

- [x] `WaylandSurfaceView` 生命周期管理健壮，在 Activity 前后台切换、锁屏与横竖屏旋转时无奔溃与显存泄漏；
- [x] NDK 硬件渲染流水线实测达到稳定 60 FPS（VSYNC 锁频），渲染单帧 GPU 上传耗时 $< 4\text{ms}$；
- [x] 屏幕旋转后能自适应重新协商分辨率，桌面自动铺满视图且保持纵横比；
- [x] 连续渲染 1000 帧无内存泄漏，宿主图形渲染内存占用常驻增量 $\le 60\text{ MB}$。

## Test strategy

- NDK 渲染单元测试：模拟推送 60 帧固定图案测试缓冲区，断言 EGL 上下文与纹理绘制正确执行；
- Android 仪表化与冒烟测试（Robolectric / Instrumented Tests）：验证 `SurfaceHolder` 状态转换、尺寸变更事件捕获与 `DisplayControl` 联动；
- 真机性能基准验证：在 HONOR 80 GT 设备上使用 Android Profiler 监控帧率与 GPU 耗时。

## Prototype/research dependency

依赖 Ticket 38 产出的 `DisplayControl` 契约，以及 `docs/research/2026-10-05-wayland-mobile-graphics-channel.md` 中的 GLES 双缓冲模型。
