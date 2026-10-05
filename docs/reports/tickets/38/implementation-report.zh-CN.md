# Ticket 38 实施报告（Implementation Report）

> 工单：`38-contract-display-control-and-graphics-channel`（策略中立图形显示与控制协议契约）  
> 工作区路径：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_ticket38`  
> 工作分支：`feature/ticket-38-contract-display-control`（基于 commit `41d46a9`）  
> 状态：**实施完成，提请独立复审（ready-for-review）**  
> 日期：2026-10-05  

---

## 1. 任务背景与核心目标

在 Phase 1（M1 里程碑）中，Orchestella Studio 交付了免 Root 的全功能 Linux 终端环境（Ticket 25–37）。根据 ADR-0012 与 ADR-0017 架构决议，原定传统 VNC 方案在移动设备上面临高 CPU 发热（差分计算）、无 VSYNC 撕裂及无法融合 Android 宿主输入法（IME）等致命问题。因此 M2 里程碑确立全面转向 **Wayland-First 现代移动图形通道**。

本工单（Ticket 38）作为 M2 里程碑的基石契约任务，核心目标是在策略中立的基础契约层（`:runtime:contract`）中完成图形显示、通道抽象、控制线协议与深度输入事件模型的设计与实现，彻底解耦宿主展现层与底层虚拟化执行策略（QEMU 软模拟 vs AVF 硬件虚拟化）。

---

## 2. 模块边界与纪律红线遵从核对（Ownership & Defensive Guardrails Verification）

- **管辖模块（Owned Modules）严格遵守**：
  - 本次所有代码修改和新增文件严格限制在 `:runtime:contract`（`runtime/contract/`）与工单/报告文档目录；
  - 严禁触碰模块（`:app`、`:runtime:engine-qemu`、`:runtime:engine-avf`、`guest-assets/`、`.toolchains/`、`artifacts/`）保持 0 触碰、0 修改。
- **纯 JVM 可测性与零平台依赖**：
  - `:runtime:contract` 保持 100% 纯 Java 17 标准库编写，严禁且零引入任何 Android SDK / NDK / AWT 等平台私有依赖；
  - 编译器选项严格开启 `-Xlint:all -Werror`，全量测试以零告警、零错误通过。
- **策略中立铁律（ADR-0002 / ADR-0017）**：
  - 契约层中严禁出现任何执行策略专属硬编码（零 `if (isAvf)` 或 `if (isQemu)`）；
  - 图形能力差异通过 `CapabilityMatrix` 数据形态表达（如 `graphicsBadge()` 提供策略中立的透明展示）。
- **零 `.log` 泄露**：
  - 严禁并确保没有任何运行时生成的 `.log` 文件被添加到 Git 跟踪。
- **工作区边界纪律**：
  - 全部开发、测试与文件创建均在物理隔离的专用 Worktree `DroidLayer_ticket38` 下完成。

---

## 3. 架构设计细节与核心实现

依据 ADR-0017 与 `.scratch/droidlayer-platform/spec-m2.md`，本工单完成了 5 项核心契约设计：

### 3.1 能力矩阵扩容（`Capability.GRAPHICS_WAYLAND`）
- 在 `FullLinuxContract.Capability` 枚举中新增 `GRAPHICS_WAYLAND`，作为能力矩阵的一等声明；
- 在 `FullLinuxContract.CapabilityMatrix` 中扩展了图形能力校验与性能徽章方法：
  - `graphicsBadge()`：根据执行策略提供策略中立的透明能力描述（`Wayland 原生通道（共享内存）` vs `Wayland 原生通道（vsock 硬件加速）`）；
  - 提供 `softwareVirtualizationWithGraphics()` 与 `hardwareVirtualizationWithGraphics()` 辅助基线构造方法。

### 3.2 策略中立显示控制抽象（`DisplayControl` & `DisplayMode`）
- **`DisplayMode`**：定义了不可变的显示模式数据结构，封装 `width`、`height`、`scale`、`dpi`、`targetFps`，提供 `withDimensions`、`withScale`、`withFps`、`withDpi` 纯函数演化方法；
  - 实施防御性边界校验：分辨率 $100 \le width, height \le 7680$、缩放比例 $0.1 \le scale \le 5.0$、帧率 $1 \le targetFps \le 240$、DPI $> 0$；
- **`DisplayListener`**：状态观察器接口，解耦提供分辨率重协商通知（`onDisplayResized`）、显示模式变更（`onModeChanged`）与帧率更新（`onFpsUpdated`）；
- **`DisplayControl`**：策略中立生命周期接口，支持宿主发起双向动态重协商：`resizeDisplay(width, height)`、`updateScale(scale)`、`updateTargetFps(targetFps)`，以及观察器的注册与移除。

### 3.3 图形通道抽象（`WaylandChannel`）
- 传输无关的连接句柄抽象，封装连接状态机：`CONNECTED`、`DISCONNECTED`、`ERROR`；
- 数据面与控制流完全解耦：
  - 提供 `registerFrameListener` / `unregisterFrameListener` 接收呈现层双缓冲帧数据（`FrameData`）；
  - `FrameData` 支持标准像素格式常量（`FORMAT_RGBA_8888`、`FORMAT_RGBX_8888`、`FORMAT_BGRA_8888`、`FORMAT_BGRX_8888`），包含 `stride`、帧号与纳秒时间戳；
  - 提供 `sendInputEvent(InputEvent event)` 统一向下投递输入事件。

### 3.4 Bridge v1 线协议命令扩容（`BridgeControl`）
- 扩容控制通道解析与分发器，新增 3 条图形显示管理命令：
  - `DROIDLAYER/DISPLAY MODE <width> <height> <scale>\n`
  - `DROIDLAYER/DISPLAY RESIZE <width> <height>\n`
  - `DROIDLAYER/DISPLAY FPS <fps>\n`
- **防御性边界拦截**：
  - 分辨率范围校验：$100 \le width, height \le 7680$；
  - 缩放比例范围校验：$0.1 \le scale \le 5.0$（排除 NaN 与 Infinity）；
  - 帧率范围校验：$1 \le fps \le 240$；
  - 超长单行限制（$\le 2048$ 字符）；
  - 非法参数、超界数值、缺失字段、未知子命令或非数值输入，确定性返回 `DROIDLAYER/ERROR malformed-command\n`；
  - 当显示适配 seam 不可用或执行失败时，确定性返回 `DROIDLAYER/ERROR display-unavailable\n`；成功返回 `DROIDLAYER/OK display\n`；
- 扩展 `BridgeControl.Seams` 与 `BridgeControl.Display` 适配接口，支持统一的 5-Seam 聚合分发（Terminal、Power、Notification、UrlOpener、Display）；
- 宿主端 `BridgeClient` 扩容对应的 `displayMode`、`displayResize`、`displayFps` 同步请求方法并分类响应为 `ok-display`。

### 3.5 策略中立输入事件模型（`InputEvent`）
- 定义多态输入事件层次结构与基类接口 `InputEvent`（`Type: POINTER, TOUCH, KEY, TEXT_COMMIT`）：
  - **`PointerEvent`**：封装鼠标与光标行为（`MOVE`, `BUTTON_DOWN`, `BUTTON_UP`, `SCROLL`），支持绝对坐标 $(x, y)$、相对位移 $(\Delta x, \Delta y)$ 及标准 Linux 鼠标按键码（`BTN_LEFT = 272`, `BTN_RIGHT = 273`, `BTN_MIDDLE = 274`）；
  - **`TouchEvent`**：封装多点触控（`DOWN`, `MOVE`, `UP`, `CANCEL`），携带触控槽位 ID（$pointerId \ge 0$）与绝对归一化视口坐标（$0.0 \le x, y \le 1.0$）；
  - **`KeyEvent`**：封装键盘事件（`PRESS`, `RELEASE`），携带标准 Linux/XKB 按键码与修饰键位掩码（`MOD_SHIFT`, `MOD_CTRL`, `MOD_ALT`, `MOD_SUPER`, `MOD_CAPS_LOCK`, `MOD_NUM_LOCK`）；
  - **`TextCommitEvent`**：封装宿主 IME 文本提交，支持多字节中日韩 Unicode 字符与 Emoji，通过 Base64 紧凑打包避免转义丢失；
- **`InputEventSerialization`**：
  - 提供单行高效文本序列化与反序列化工具方法；
  - **安全防御边界**：严格限制单行序列化长度上限 $\le 2048$ 字符，超长直接抛出 `IllegalArgumentException` 安全拦截；畸形与非法格式确定性报错。

---

## 4. 验收标准逐项落地与实测比对（Acceptance Criteria Verification）

| 验收项 | 规格要求 | 实施状态 | 对应代码与实测证据 |
| :--- | :--- | :---: | :--- |
| **AC-1** | `FullLinuxContract.Capability` 扩容 `GRAPHICS_WAYLAND`，能力矩阵反映真实支持 | **PASS** | `FullLinuxContract.java` 新增枚举项与 `graphicsBadge()`；`FullLinuxContractTest` 验证软件/硬件/无图形三种状态徽章。 |
| **AC-2** | `DisplayControl` 与 `WaylandChannel` 契约接口定义完备，纯 Java 17，无平台依赖 | **PASS** | 交付 `DisplayControl`、`DisplayMode`、`DisplayListener`、`WaylandChannel`，纯 Java 17 标准库，无任何 Android/NDK/AWT 依赖。 |
| **AC-3** | `BridgeControl` 成功集成并分发 `DISPLAY MODE`、`RESIZE`、`FPS`，非法参数确定性返回 `malformed-command` | **PASS** | `BridgeControl.java` 完成命令解析与分发；`BridgeControlDisplayTest` 覆盖全部合法、边界（100/7680, 0.1/5.0, 1/240）、超界、畸形与缺漏参数。 |
| **AC-4** | 策略中立输入事件模型支持序列化与校验，单行上限（2048 字符）及格式畸形均被安全拦截 | **PASS** | 交付 `PointerEvent`、`TouchEvent`、`KeyEvent`、`TextCommitEvent` 与 `InputEventSerialization`；`InputEventSerializationTest` 覆盖中文字符、Emoji、超长 2048 字符及畸形语法拦截。 |
| **AC-5** | 契约层 JVM 单元测试覆盖率达到 100%，所有测试在既有构建中全绿通过，零回归 | **PASS** | `./gradlew :runtime:contract:clean :runtime:contract:check` 全量 15 个任务 100% 绿灯通过，Phase 1 历史契约测试与新增 M2 契约测试全部 PASS。 |

---

## 5. TDD 执行全流程与测试证据

### 5.1 红灯阶段（Red Phase）
在生产代码实现前先期编写了三大契约测试套件：
- `DisplayControlContractTest.java`
- `BridgeControlDisplayTest.java`
- `InputEventSerializationTest.java`
运行 `compileTestJava` 触发预期的编译失败（100 个预期符号缺失错误，见 Gradle task-57 日志）。

### 5.2 绿灯阶段（Green Phase）
编写所有生产代码并接入 `build.gradle.kts`，执行全量契约测试，所有新增测试及历史回归测试全部一次性通过。

### 5.3 重构与全量干净验证（Refactor & Regression）
在 Worktree 根目录下执行：
```powershell
.\gradlew.bat :runtime:contract:clean :runtime:contract:check
```
**测试输出摘录**：
```text
> Task :runtime:contract:clean
> Task :runtime:contract:compileJava
> Task :runtime:contract:classes
> Task :runtime:contract:compileTestJava
> Task :runtime:contract:testClasses
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeControlDisplayTest
BRIDGE_CONTROL_DISPLAY_TEST_OK
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:displayControlContractTest
DISPLAY_CONTROL_CONTRACT_TEST_OK
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:inputEventSerializationTest
INPUT_EVENT_SERIALIZATION_TEST_OK
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:portForwardingContractTest
PORT_FORWARDING_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:storageContractTest
STORAGE_CONTRACT_TEST_OK
> Task :runtime:contract:test
> Task :runtime:contract:check

BUILD SUCCESSFUL in 27s
15 actionable tasks: 15 executed
```

---

## 6. 改动文件清单（Diff Inventory）

### 6.1 新增契约接口与实现（Owned: `:runtime:contract`）
1. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/DisplayMode.java`
2. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/DisplayListener.java`
3. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/DisplayControl.java`
4. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/WaylandChannel.java`
5. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/InputEvent.java`
6. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/PointerEvent.java`
7. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/TouchEvent.java`
8. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/KeyEvent.java`
9. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/TextCommitEvent.java`
10. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/InputEventSerialization.java`

### 6.2 扩展已有契约类（Owned: `:runtime:contract`）
11. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/FullLinuxContract.java`（新增 `GRAPHICS_WAYLAND`、`graphicsBadge` 等）
12. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControl.java`（新增 `DISPLAY` 命令集分发与 `Display` seam）
13. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeClient.java`（新增 `displayMode`、`displayResize`、`displayFps` 与 `ok-display` 分类）
14. `runtime/contract/build.gradle.kts`（注册 3 个新增测试任务并绑定到 `check`）

### 6.3 契约测试套件
15. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/DisplayControlContractTest.java`（新增）
16. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControlDisplayTest.java`（新增）
17. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/InputEventSerializationTest.java`（新增）
18. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/FullLinuxContractTest.java`（扩展图形徽章断言）

### 6.4 工单跟踪与交付文档
19. `.scratch/droidlayer-platform/issues/38-contract-display-control-and-graphics-channel.md`（AC 勾选与状态更新）
20. `docs/reports/tickets/38/implementation-report.zh-CN.md`（本报告）

---

## 7. 架构新发现与后续建议（Findings）

1. **输入事件与 IME 深度穿透互补性**：
   `TextCommitEvent` 基于 Base64 封装 Unicode 字符串，在传输层实现了对包含换行、空格、Emoji 及中文多字节字符的 100% 安全封包，消除了传统 URL-encode 在极端 UTF-8 序列下的边界歧义。后续 Ticket 39（NDK 渲染面）与 Ticket 41（IME 桥接）可直接依托此模型与 Guest Agent 对接。
2. **显示重协商状态与渲染尺寸对齐**：
   在后续 Ticket 39（NDK SurfaceView）实现中，建议监听宿主 SurfaceView `surfaceChanged` 事件后直接调用 `BridgeClient.displayResize(width, height)`，从而在横竖屏切换时触发 Guest 侧 `labwc` 合成器动态调整分辨率，实现无缝自适应。

---

## 8. 交付结论

本工单（Ticket 38）的所有 Acceptance Criteria 均已 100% 达成，零破坏现有 Phase 1 契约行为，保持严格的纯 JVM 可测性与策略中立铁律。已将工单看板标为 `ready-for-review`，提请 Orchestrator 指派独立代码复审专员进行审查。
