# 56: NDK 原生 GLES3/ANativeWindow 异步呈现通道与 PBO 乒乓流

Type: implementation  
Status: ready-for-agent  
Labels: m4-spec, tracer-bullet, graphics, ndk, gles3-pbo  

## User Story

作为在手机上使用图形桌面与 Web 浏览器的开发者，我希望画面上屏延迟尽可能低、宿主 CPU 占用尽量小，即使在没有开启 3D 加速的软渲染回退状态下，帧画面也能以极快的速度异步刷新到屏幕上，不再因 Java 层的位图拷贝造成掉帧卡顿（US-30）。

## What to build

在 `:app` 原生层（C++ / JNI）与 Java 层中构建 NDK 硬件直通呈现通道，遵循 ADR-0017 与 spec-m4.md：

1. **实现原生呈现器（`NativeGlesSurfacePresenter`）**：
   - 实现 Phase 2 ADR-0017 定义的策略中立接缝：`DoubleBufferedRenderer.SurfacePresenter`；
   - 彻底废除基于 Java `Bitmap.setPixels()` 和 `lockHardwareCanvas()` 的旧版实现；
   - 内部封装原生 C++ 句柄，与底层 `native_gles_presenter.cpp` 建立 JNI 桥接；
2. **NDK ANativeWindow 与 EGL 渲染管线**：
   - 接收 Android `Surface`，通过 NDK `ANativeWindow_fromSurface()` 建立底层原生窗口绑定；
   - 配置 EGL 上下文（EGL 1.4 + OpenGL ES 3.0）；
   - 配置 `eglSwapInterval(eglDisplay, 1)`，强制与系统硬件 VSYNC 对齐，消除画面撕裂；
3. **双缓冲 PBO（Pixel Buffer Object）异步上传流水线**：
   - 在 C++ 内部创建两个 PBO 缓冲区（`pboIds[2]`），采用乒乓流水线机制（Ping-Pong Streaming）：
     * PBO 0：执行异步内存映射并拷贝当前帧像素；
     * PBO 1：触发 GPU DMA 异步解包绑定至目标纹理（`GL_PIXEL_UNPACK_BUFFER`）；
   - 将 1080p 纹理上传耗时从 12ms 压降至 $1.5\text{ms} \sim 3.0\text{ms}$，CPU 拷贝耗时降至微秒级；
4. **视口变换与全屏渲染着色器**：
   - 使用极简顶点/片段着色器将目标纹理映射至视口矩形；
   - 精准响应 `ViewportAspectCalculator.ViewportMapping` 缩放矩阵，支持居中等比缩放与黑边填充。

**Blocked by:** 39, 43

## Seam Design

- **接缝完美复用**：直接实现既有的 `DoubleBufferedRenderer.SurfacePresenter` 接口，Java 调度器、帧丢弃算法与 Activity 完全不需要重构；
- **原生层生命周期受控**：`onSurfaceCreated`、`onSurfaceChanged`、`onSurfaceDestroyed` 与 `present` 严格遵循 JNI 内存生命周期管理，防止显存与 NativeWindow 泄漏。

## Acceptance Criteria

- [ ] `NativeGlesSurfacePresenter` 正式实现 `SurfacePresenter` 接口并通过纯 JVM 桩测试；
- [ ] 原生 C++ 成功初始化 EGL 上下文并绑定 `ANativeWindow`；
- [ ] 采用双 PBO 异步流水线完成像素直通上传，单帧呈现耗时 $\le 3.5\text{ms}$；
- [ ] 视口发生旋转或尺寸调整时，EGL 上下文平滑更新视口（`glViewport`），无黑屏、无画面拉伸变形；
- [ ] Surface 销毁时彻底释放 EGLSurface、EGLContext、PBO 及纹理资源，无内存与显存泄漏；
- [ ] 纯 JVM 测试（`NativePresenterLifecycleTest`）通过，C++ 代码符合 Clang-Tidy 规范。

## Device Verification Steps

在 Android 真机上验证 NDK 呈现通道与帧率性能：
```bash
# 1. 启动图形桌面
adb shell am start -n io.github.nyzeep.droidlayer/.DesktopActivity

# 2. 查看 Logcat 中 NativeGlesPresenter 初始化与 EGL 配置日志
adb logcat -d -s DroidLayerGles:I

# 3. 测量显示帧率与渲染延迟
adb shell dumpsys gfxinfo io.github.nyzeep.droidlayer framestats
# 断言 99% 的帧呈现耗时收敛在 16.6ms 以内 (稳定 60 FPS)
```

## Test Strategy

- JVM 桩测试：
  - `NativePresenterContractTest`：模拟 Surface 生命周期流转与像素数组传递；
- Native 编译与静态检查：
  - CMake 构建检查与 16KB 页对齐符号表检查；
- 运行 `./gradlew :app:testDebugUnitTest`。

## Prototype/research dependency

依赖 Phase 2 接缝设计（ADR-0017）与 `docs/research/2026-10-06-phase4-gpu-acceleration.md` 第 5 节呈现通道升级规范。
