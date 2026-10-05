# 43: Android 宿主图形桌面 Activity 界面、一键启动与终端/桌面双模切换

Type: implementation  
Status: ready-for-review  

## What to build

在 `:app` 中实现面向最终用户的 Android 宿主图形桌面 Activity 界面（`DesktopActivity`）、布局装配、实例卡片“启动图形桌面”交互入口，以及纯终端与图形桌面的无缝双模切换，遵循 ADR-0017 与 spec-m2.md（US-1, US-9, US-10）。

主要包含：
1. **桌面主界面（`DesktopActivity.java` & `activity_desktop.xml`）**：
   - 布局层级集成 `WaylandSurfaceView` 作为底层渲染面，浮动层叠 `VirtualKeyBar`（支持吸底与快捷键展开）；
   - 支持沉浸式全屏模式（Immersive Sticky Mode），自动隐藏系统状态栏与导航栏；
   - 监听 Activity 前后台生命周期（`onPause`, `onResume`, `onDestroy`），与底层 `WaylandSurfaceController` 保持严密同步；
2. **状态 HUD 浮条与透明度监控（`DesktopHudOverlay`）**：
   - 在屏幕顶部或角落提供半透明信息指示徽章（HUD Badge），展示：
     * 实时渲染帧率（如 `60 FPS`）；
     * 当前桌面分辨率与缩放比例（如 `1920x1080 @ 1.0x`）；
     * 图形通道连接状态（`CONNECTED` / `CONNECTING` / `DISCONNECTED`）；
     * 虚拟化底层图形策略徽章（如 `Wayland (共享内存)` 或 `Wayland (vsock 硬件加速)`）；
   - 点击可折叠或隐藏 HUD，避免遮挡 Linux 窗口控制按钮；
3. **终端与桌面双模无缝切换（Dual-Mode Navigation）**：
   - 提供快捷悬浮球或顶部工具条按钮，支持在纯终端 Shell 视图（`TerminalActivity`）与图形桌面视图（`DesktopActivity`）之间平滑自由切换；
   - 实例后台运行服务（`DroidLayerForegroundService`）与虚拟机保持不间断稳定运行；
4. **实例列表卡片一键拉起入口（`MainActivity.kt` 扩展）**：
   - 在已启动实例的卡片操作区，除了原有的“打开终端”外，新增“启动图形桌面”交互操作项；
   - 传递实例 ID 与运行凭据，构建安全的启动 Intent。

**Blocked by:** 39, 40

## Acceptance criteria

- [x] `DesktopActivity` 布局完备集成 `WaylandSurfaceView`、`VirtualKeyBar` 与 HUD 监控徽章；
- [x] 沉浸式全屏模式稳定运行，软键盘弹出时不引起画面错乱；
- [x] HUD 徽章能准确反映当前 FPS、分辨率、连接状态与底层虚拟化策略；
- [x] 支持在终端与桌面之间无缝跳转，返回键或返回按钮行为符合 Android 标准体验；
- [x] `MainActivity` 实例卡片提供一键直达“启动图形桌面”入口；
- [x] 纯 JVM 测试（`:app:testDebugUnitTest`）100% 绿灯通过，零回归。

## Test strategy

- JVM 纯单元测试：
  - `DesktopHudFormatterTest`：验证 HUD 格式化输出、性能徽章匹配与异常连接状态转换；
  - `DesktopLaunchIntentTest`：验证启动参数解析、空凭据校验与实例 ID 绑定；
  - `DualModeNavigationTest`：验证双模切换状态与 Activity 参数传递；
- 运行 `./gradlew :app:testDebugUnitTest` 保证全量绿灯。

## Prototype/research dependency

依赖 Ticket 39（渲染面与几何算子）、Ticket 40（触控手势与按键条），以及 spec-m2.md 第 3 节的用户故事设计。
