# 57: QEMU 运行时内嵌 VirGL 3D 加速与 Android EGL 上下文隔离

Type: implementation  
Status: ready-for-review  
Labels: m4-spec, tracer-bullet, qemu, virglrenderer, gpu-acceleration  

## User Story

作为追求流畅图形界面的 Linux 移动用户，我希望在启动 QEMU 虚拟化实例时能够启用 3D 硬件加速，将图形和着色器计算分流给手机物理 GPU；同时当 Linux 内的程序发生恶性图形错误时，能够被安全隔离，不至于导致 Android 应用闪退或整机重启（US-30）。

## What to build

在 `:runtime:engine-qemu` 中集成 NDK 版 `virglrenderer` 并挂接虚拟显卡设备，遵循 ADR-0014、ADR-0019 与 spec-m4.md：

1. **`libvirglrenderer.so` NDK 交叉编译与供应链纯化**：
   - 编写构建脚本 `tools/virglrenderer/build-virgl-ndk.sh`（基于 NDK r27+ 构建 `arm64-v8a` 动态库）；
   - **依赖消除**：
     * 自研微型 `virgl_android_gl_loader.c`（基于 `eglGetProcAddress` 动态加载 Android GLES3 符号），完全剔除庞大的 `libepoxy` 依赖；
     * 提取 upstream 最小 `drm_fourcc.h` 头文件，消除 `libdrm.so` 实体链接依赖；
   - 产出严格符合 16KB 页对齐规范与 MIT 许可证的 `libvirglrenderer.so`；
2. **QEMU 启动规范挂接虚拟显卡（`QemuLaunchSpec` 扩展）**：
   - 当 `VmHardwareConfig.gpuAccelerationEnabled == true` 时，向 QEMU 启动参数追加：
     ```
     -device virtio-gpu-gl-device
     ```
   - 在 QEMU JNI 进程内初始化 VirGL 渲染上下文：
     ```c
     virgl_renderer_init(&callbacks, flags, &cookie);
     ```
   - 采用单进程内嵌模型（In-Process Link），消除跨进程 Socket 连接带来的断开与开销风险；
3. **EGL 鲁棒性防崩与显存配额守卫（三重防御机制）**：
   - 初始化 EGL 上下文时声明上下文隔离属性：
     * `EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT = EGL_TRUE`；
     * `EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT = EGL_LOSE_CONTEXT_ON_RESET_EXT`；
   - 发生 GPU 超时重置时，仅重置 VirGL EGL 上下文并向 Guest 返回错误，彻底杜绝引发 Android 内核崩溃；
   - 设置显存分配上限守卫（`MemoryQuotaSentinel`：默认硬上限 1024MB），超限时优雅拒绝，防止触发系统级 LMK。

**Blocked by:** 49, 56

## Seam Design

- **JNI 渲染桥接接缝**：`VirglJniBridge` 封装原生 `virgl_renderer` 调用，对 Java 层暴露简单的 `init()`、`cleanup()` 与状态回调；
- **配置驱动开关**：`QemuInstanceLauncher` 仅读取 `VmHardwareConfig.gpuAccelerationEnabled()` 决定是否拼接 GPU 参数与初始化 VirGL。

## Acceptance Criteria

- [x] `libvirglrenderer.so` 编译完成，体积 $< 1.5\text{MB}$，通过 16KB 内存页对齐检查（`objdump -p`）；
- [x] 动态链接表（`readelf -d`）中仅依赖 Android Bionic 标准库（`libc.so`, `libm.so`, `libEGL.so`, `libGLESv3.so`），绝对无 `libepoxy` 或 `libdrm` 外部依赖；
- [x] `QemuLaunchSpec` 在开启 GPU 加速时准确生成 `-device virtio-gpu-gl-device`，关闭时安全回退为 `-device virtio-gpu-device`（2D 软渲染）；
- [x] 宿主 EGL 驱动配置了 Robustness 上下文属性，模拟注入死循环 Shader 时，宿主 App 保持存活；
- [x] 纯 JVM 测试（`QemuGpuLaunchSpecTest`）100% 绿灯。

## Device Verification Steps

在 Android 真机上验证 VirGL 初始化与命令行参数：
```bash
# 1. 检查 APK 中 libvirglrenderer.so 的 16KB 页对齐与依赖表
readelf -l app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a/libvirglrenderer.so | grep -A 2 LOAD
readelf -d app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a/libvirglrenderer.so | grep NEEDED

# 2. 启动带 GPU 加速的实例，观察 QEMU 日志
adb logcat -d -s DroidLayerQemu:I | grep -E "(virtio-gpu-gl|virgl_renderer_init)"
# 断言输出包含 virgl_renderer_init succeeded
```

## Test Strategy

- JVM 单元测试：
  - `QemuGpuLaunchSpecTest`：测试 GPU 开关对应的参数组合生成；
  - `VirglMemoryQuotaTest`：测试显存配额守卫拦截大内存申请逻辑；
- 原生自动化验证：
  - 检查符号表依赖与 16KB 对齐断言；
- 运行 `./gradlew :runtime:engine-qemu:test`。

## Prototype/research dependency

依赖 Ticket 49（硬件配置模型）、Ticket 56（NDK 原生呈现器）与 `docs/research/2026-10-06-phase4-gpu-acceleration.md` 第 3 节与第 6 节技术规范。
