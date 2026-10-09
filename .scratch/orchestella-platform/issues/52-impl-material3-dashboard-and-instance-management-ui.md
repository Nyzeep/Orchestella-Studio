# 52: Material 3 Expressive 仪表盘重塑与实例 CRUD 交互闭环

Type: implementation  
Status: ready-for-review  
Labels: m4-spec, tracer-bullet, ui-ux, material3-dashboard  

## User Story

作为注重视觉审美与操作效率的移动端开发者，我希望 Orchestella Studio 拥有符合现代 Android 设计规范的 Material 3 仪表盘，通过精美的卡片和呼吸状态动画查看虚拟机运行状态，并能通过长按弹出菜单便捷地启动桌面、打开终端、修改硬件配置或彻底删除废弃实例（US-19, US-22, US-23）。

## What to build

在 `:app` 中全面重构主界面 UI 与交互逻辑，替换旧版 `activity_main.xml`，遵循 ADR-0016 与 spec-m4.md：

1. **全新 Material 3 Expressive 仪表盘（`activity_main.xml` 重做）**：
   - 适配 Android 15/17 Edge-to-Edge 沉浸式全屏，顶部状态栏与底部导航栏实现完全透明与内容穿透；
   - 接入 Material You 动态色彩主题（`DynamicColors.applyIfAvailable()`），卡片、按钮与背景色随用户壁纸自动适配；
   - 头部设立品牌与全局状态卡片：展示当前宿主环境（CPU 架构、可用内存、AVF 虚拟化就绪徽章）；
2. **UTM 风格虚拟机实例卡片（`layout_instance_card.xml`）**：
   - 圆角浮动卡片设计，展示发行版图标（Alpine/Debian）、实例 ID、创建时间；
   - **动态呼吸状态微标（State Badge）**：
     * `RUNNING`：绿光呼吸动效（Alpha 周期动画），标注当前后端策略（如 `pKVM 硬件虚拟化` 或 `QEMU 软模拟`）；
     * `STOPPED`：中性深灰胶囊标签；
     * `CRASHED`：琥珀红警示标签，点击可快速呼出崩溃日志查看器；
   - 硬件标签栏：直观显示当前分配的 vCPU 核数、内存配额（MB）与磁盘大小（GB）；
3. **实例上下文操作底部弹窗（`InstanceActionBottomSheet`）**：
   - 支持长按卡片或点击卡片操作项呼出 BottomSheet：
     * 🖥️ **启动图形桌面**：携带认证 Token 与 Display 参数直接启动 `DesktopActivity`；
     * 💻 **打开终端控制台**：拉起升级后的 `TerminalActivity`；
     * ⚙️ **配置硬件参数**：弹出 `VmHardwareConfigDialog`，仅在关机状态可编辑核心与运存滑块；若在运行状态则置灰并标注“需关机后调整”；
     * 📦 **导出备份归档**：调用 SAF 导出 `.raw` 磁盘与元数据；
     * 🗑️ **彻底删除实例**：调用 Ticket 49 契约，若处于运行态则禁用并提示“运行中无法删除”；点击后弹出二次警示对话框，确认后异步物理销毁并刷新列表；
4. **实例列表状态响应式更新（`MainActivity.kt` 升级）**：
   - 彻底解耦主线程耗时 I/O，实例列表读取与物理删除在后台线程执行；
   - 刷新列表时执行平滑淡入动画，保证 60 FPS 界面丝滑度。

**Blocked by:** 49, 50

## Seam Design

- **视图绑定抽象**：采用 ViewBinding 或独立的 `InstanceCardViewHolder` 进行状态绑定，业务状态流（`VmState`）驱动 UI 渲染；
- **操作委派接口**：定义 `InstanceActionListener`，将“启动”、“配置”、“删除”等操作抽象为回调，解耦具体的 Activity 导航；
- **配置对话框解耦**：`VmHardwareConfigDialog` 与 `HostHardwareDetector`（Ticket 50）联动，接收当前配置并返回修改后的新 `VmHardwareConfig` 实例。

## Acceptance Criteria

- [x] 主界面布局符合 Material 3 Expressive 规范，沉浸式底栏与状态栏无遮挡、无布局畸变；
- [x] 实例卡片能准确渲染各生命周期状态（`RUNNING` 呼吸动效、`STOPPED` 静态灰度、`CRASHED` 告警）；
- [x] 长按卡片能平滑弹出 `InstanceActionBottomSheet`，包含启动桌面、终端、配置、备份、删除五大核心操作项；
- [x] 当实例处于 `RUNNING` 状态时，“硬件配置”与“彻底删除”按钮严格置灰，点击给出友好 Toast 提示；
- [x] 关机状态下确认删除实例后，后台线程成功执行物理清理并自动从 UI 列表中移除卡片，无需手动杀进程重启；
- [x] 纯 JVM 测试（`MainActivityUiStateTest` / `InstanceCardStateTest`）100% 绿灯通过。

## Device Verification Steps

在 Android 真机上验证现代仪表盘与卡片操作：
```bash
# 1. 启动应用主界面
adb shell am start -n io.github.nyzeep.droidlayer/.MainActivity

# 2. 检查 UI 布局层级是否无 Overdraw 与布局异常
adb shell dumpsys activity top | grep -A 10 "View Hierarchy"

# 3. 验证长按删除操作
# 在真机上创建测试实例 -> 关机 -> 长按卡片 -> 点击删除 -> 确认
# 检查物理私有目录是否已回收
adb shell "run-as io.github.nyzeep.droidlayer ls -la files/instances/"
```

## Test Strategy

- JVM 单元测试：
  - `InstanceCardPresentationTest`：测试各状态枚举到 Badge 颜色、文本与按钮使能逻辑的转换；
  - `InstanceActionSafetyTest`：测试状态机下对非法删除与非法修改硬件参数请求的拦截；
- 运行 `./gradlew :app:testDebugUnitTest`。

## Prototype/research dependency

依赖 Ticket 49（生命周期删除与硬件实体）、Ticket 50（硬件探测与配置），以及 UTM 交互美学调研。
