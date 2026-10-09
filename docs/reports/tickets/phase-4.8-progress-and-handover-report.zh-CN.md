# Orchestella Studio Phase 4.8 核心架构与移动交互深化进展报告

- **工单/阶段**：Phase 4.8（移动多模触控手势引擎、终端高级编辑与高级网络管理）
- **报告性质**：架构深化里程碑总结（Architecture Milestone）、阶段质量验收（Verification）与环境交接报告（Handover）
- **前序基线 Commit**：`76d34f7`（Phase 4.7 交付基线）
- **当前最新 Commit**：`d212f83`（分支：`main`）
- **开发与验证环境**：Windows 纯 ASCII 隔离盘符虚拟构建环境（`W:\` 为工程目录、`S:\` 为 Android SDK），OpenJDK 17，全纯 JVM 契约测试与状态机模拟驱动，无真机物理连接依赖
- **执行角色**：Orchestella Studio 核心全栈研发工程师
- **交付状态**：**ALL MISSIONS ACCOMPLISHED（四大核心攻坚任务 100% 圆满达成）**
- **报告日期**：2026-10-09

---

## 1. 阶段概述与战役目标达成度

Phase 4.8 战役在**暂无物理真机直接连接（纯开发机/离线环境）**的条件下全面启动。严格贯彻 Matt Pocock 提倡的深模块（Deep Modules）设计原则、测试驱动开发（TDD）方法论以及 Android 非 Root 安全沙箱防线，圆满完成了终端高级编辑与 ANSI 引擎深化、Wayland 桌面多模移动触控手势状态机与契约扩充、Material 3 动态端口转发规则可视化管理与纯 JVM 防错校验体系的构建，并通过了双重质量门禁校验。

### 四大攻坚任务达成度汇总表

| 任务编号与维度 | 核心目标与技术规格 | 达成状态 | 验证依据与交付成果 |
| :--- | :--- | :---: | :--- |
| **任务 1：终端交互引擎高级能力深化**<br>*(Terminal Advanced Editing & ANSI)* | 1. 光标左右移动（`\e[D` / `\e[C`）与任意位置插入/删除（退格、Delete `\e[3~`）；<br>2. 历史命令上下翻滚（`\e[A` / `\e[B`）并自动定位至行尾；<br>3. 信号控制（Ctrl+C 中断、Ctrl+D EOF、Ctrl+L 清屏）；<br>4. Tab 智能补全解耦扩展桩。 | **100% 达成** | 扩展 `InteractiveTerminalEngine.java`；编写并全绿通过 `InteractiveTerminalEngineAdvancedTest.java`（9 组覆盖光标定位、中间插入、退格删除、信号与 Tab 补全桩的测试）（Commit `fdd157d`） |
| **任务 2：Wayland 移动多模触控手势引擎**<br>*(Multi-touch & Gesture Engine)* | 1. 扩充 `:runtime:contract` 线协议与手势事件模型（`GestureEvent`）；<br>2. 落地双模触控状态机（绝对触控 Direct Touch + 触控板 Virtual Trackpad）；<br>3. 实现移动多指手势映射（单指左键、双指/长按右键、双指垂直滚动、双指捏合缩放）。 | **100% 达成** | 扩充 `InputEvent.java`、`GestureEvent.java`、`InputEventSerialization.java`；实现深模块 `GestureStateEngine.java`；编写 `GestureStateEngineTest.java` 并接入 Gradle 检查；扩展 `TouchGestureMapper.java` 捏合监听（Commit `149819d`） |
| **任务 3：动态端口转发与网络规则管理**<br>*(Network Forwarding UI & Validation)* | 1. 构建纯 JVM 端口校验器（1024~65535 非特权端口规范、协议冲突检测）；<br>2. 构建高内聚低耦合 `PortForwardingPresenter`；<br>3. 设计 Material 3 规范管理面板（触摸靶心 $\ge 48\times 48\text{ dp}$、规则动态启用/停用/删除）；<br>4. 接入实例菜单与主界面。 | **100% 达成** | 编写 `PortForwardingRuleValidator.java`、`PortForwardingPresenter.java` 及 16 组纯 JVM 单测；实现 `PortForwardingBottomSheet.kt`、布局 `layout_port_forwarding_sheet.xml` 及矢量图标资源（Commit `d212f83`） |
| **任务 4：工程质量门禁与发版归档**<br>*(Quality Gates & Handover)* | 1. 执行全栈编译与双重单元测试全集（主工程单元测试 + Guest Assets 门禁）；<br>2. 保持 Git 提交原子性与语义化；<br>3. 输出规范交接报告与架构文档。 | **100% 达成** | `testDebugUnitTest :runtime:contract:check` 62 项任务全绿（14s）；`guest-assets test --rerun-tasks` 3 项测试全绿（18s）；输出 Phase 4.8 交付报告 |

---

## 2. 核心架构演进与深模块设计

### 2.1 终端交互引擎高级行编辑（`InteractiveTerminalEngine.java`）

针对前序阶段光标仅停留在行尾、无法向左移动修改、无法使用 Delete 键以及无快捷信号控制的局限，本阶段将 `InteractiveTerminalEngine` 深化为兼具 VT100 / ANSI 控制序列解析与精细光标缓冲区管理的纯 JVM 深模块：

```
                           ┌───────────────────────────┐
                           │   Virtual Key / Soft KB   │
                           │ (Arrow Left/Right, Up/Dn, │
                           │  Tab, Ctrl+C, Ctrl+L...)  │
                           └─────────────┬─────────────┘
                                         │
                                         ▼
                     ┌───────────────────────────────────────┐
                     │     InteractiveTerminalEngine         │
                     ├───────────────────────────────────────┤
                     │  - cursorIndex: 0 .. lineLength       │
                     │  - currentLine: StringBuilder         │
                     │  - history: CommandHistory            │
                     │  - tabHandler: TabCompletionHandler   │
                     ├───────────────────────────────────────┤
                     │ ANSI 序列解析:                        │
                     │   \e[D / \e[C -> 光标左右移位          │
                     │   \e[A / \e[B -> 历史回溯/前进，移至末尾│
                     │   \e[3~       -> Delete 删除光标处字符 │
                     │ 控制字符:                             │
                     │   Ctrl+C      -> 中断行，回显 ^C      │
                     │   Ctrl+D      -> EOF 派发             │
                     │   Ctrl+L      -> 清屏并保持当前行     │
                     └───────────────────┬───────────────────┘
                                         │
                     ┌───────────────────┴───────────────────┐
                     ▼                                       ▼
        ┌─────────────────────────┐             ┌─────────────────────────┐
        │   ModernTerminalView    │             │   TerminalInputSink     │
        │ - 渲染 Prompt 与修饰光标 │             │ - 派发执行命令或原始字节 │
        │ - VT100 行内局部重绘    │             │ - 消费信号事件          │
        └─────────────────────────┘             └─────────────────────────┘
```

#### 关键技术突破：
1. **任意位置插入与删除**：
   - 当 `cursorIndex < currentLine.length()` 时，新键入的字符通过 `currentLine.insert(cursorIndex, text)` 插入，并使光标前移；
   - 退格键（Backspace）精确删除 `cursorIndex - 1` 处的字符；
   - Delete 键（`\e[3~`）精确删除 `cursorIndex` 处的字符，右侧文本向左平移；
   - 暴露 `getCursorIndex()`，在渲染层提供精确的光标反显与定位能力。
2. **命令历史导航增强**：
   - 上下方向键（`\e[A` / `\e[B`）从环形历史缓冲区召回前一条或后一条命令；
   - 自动将 `cursorIndex` 设置为当前召回文本的长度（`currentLine.length()`），完全符合人类操作直觉。
3. **快捷信号与控制字符捕获**：
   - `Ctrl+C`（`\u0003`）：中断当前行，行内回显 `^C\r\n`，自动换行并重置提示符，无需发起网络或虚拟机往返即可在 UI 层响应；
   - `Ctrl+D`（`\u0004`）：若当前行非空则忽略，若当前行处于空闲状态则派发 EOF；
   - `Ctrl+L`（`\u000c`）：重置屏幕输出，保留当前行输入与 Prompt，实现无感清屏。
4. **Tab 补全桩扩展接口**：
   - 定义 `TabCompletionHandler` 与 `TabCompletionListener`，在接收到 `\t` 时触发候选补全探测与补全文本派发，为 Phase 5+ 的 Shell 智能补全预留清晰的接缝（Seam）。

---

### 2.2 Wayland 移动多模触控手势引擎（`GestureStateEngine.java`）

在高分屏移动设备（如 560dpi 手机屏幕）上，直接触摸容易面临细小窗口按钮难以精准点按的难题。为此，在 `:runtime:contract` 中引入手势事件模型，并在契约层与呈现层落地了双模触控状态机。

```
                                    ┌───────────────────────┐
                                    │    Android Motion     │
                                    │ (ACTION_DOWN/MOVE/UP, │
                                    │  POINTER_DOWN/UP...)  │
                                    └───────────┬───────────┘
                                                │
                                                ▼
                        ┌───────────────────────────────────────────────┐
                        │              GestureStateEngine               │
                        ├───────────────────────────────────────────────┤
                        │  TouchMode:                                   │
                        │    1. DIRECT_TOUCH    (绝对坐标直接映射)      │
                        │    2. VIRTUAL_TRACKPAD (相对位移 Delta 驱动)  │
                        ├───────────────────────────────────────────────┤
                        │  State Machine:                               │
                        │    - IDLE                                     │
                        │    - SINGLE_TOUCH_DOWN (轻触等待)             │
                        │    - DRAGGING (单指拖拽/指针滑动)             │
                        │    - TWO_FINGER_DOWN (双指准备)               │
                        │    - SCROLLING (双指垂直滑动 -> 滚轮)         │
                        │    - PINCHING (双指开合 -> 缩放)              │
                        │    - LONG_PRESS_DETECTED (长按 -> 右键)       │
                        └───────────────────────┬───────────────────────┘
                                                │
                                                ▼
                              ┌───────────────────────────────────┐
                              │     GestureEvent / Wire Protocol  │
                              ├───────────────────────────────────┤
                              │ TAP              -> 左键单击      │
                              │ TWO_FINGER_TAP   -> 右键单击      │
                              │ LONG_PRESS       -> 右键单击      │
                              │ SCROLL (dy)      -> wl_pointer    │
                              │ PINCH_ZOOM       -> 视口缩放      │
                              │ MODE_SWITCH      -> 模式切换      │
                              └───────────────────────────────────┘
```

#### 关键技术突破：
1. **双模触控状态转移深模块（`GestureStateEngine`）**：
   - **绝对触控模式（Direct Touch）**：手指在屏幕上的绝对坐标 $(x, y)$ 按比例直接映射至 Wayland 合成器表面坐标，适合大按钮操作与平板交互；
   - **虚拟触控板模式（Virtual Trackpad）**：全屏化身触控板，手指滑动计算相对增量 $(\Delta x \cdot \text{scale}, \Delta y \cdot \text{scale})$，驱动虚拟鼠标指针。引入灵敏度系数（1.2x）与动态加速度机制，解决微小控件点击难题；
   - 支持通过 `setTouchMode(...)` 动态无缝切换，派发 `MODE_SWITCH` 事件。
2. **多指手势精准识别**：
   - **单指轻触（Tap）**：位移小于防抖阈值（15px）并在短时间内抬起，派发 `TAP` 事件（映射为鼠标左键按下与抬起）；
   - **长按（Long Press）**：单指静止按压超过阈值（500ms），触发长按震动与 `LONG_PRESS` 事件（映射为鼠标右键，唤出 Linux 桌面右键菜单）；
   - **双指轻触（Two-Finger Tap）**：双指同时触控并短时间内抬起，映射为鼠标右键；
   - **双指垂直滑动（Two-Finger Scroll）**：双指同向滑动，提取垂直位移 $\Delta y$，生成平滑的滚轮滚动事件（映射为 Wayland `wl_pointer.axis`）；
   - **双指捏合（Pinch-to-Zoom）**：双指反向滑动，计算双指间距比例变化 $\frac{d_{\text{current}}}{d_{\text{start}}}$，派发 `PINCH_ZOOM` 事件。
3. **线协议（Wire Protocol）序列化扩展**：
   - 扩充 `InputEventSerialization.java`，支持 `INPUT/GESTURE <type> <x> <y> <dx> <dy> <scale> <button>` 双向无损编解码，保持与前序事件类型的向前向后兼容性。

---

### 2.3 动态端口转发可视化管理与防错校验（`PortForwardingRuleValidator` & `PortForwardingPresenter`）

在免 Root 沙箱环境下，宿主端口与 Guest 端口映射必须严格受限且保证互斥。本阶段建立了纯 JVM 校验模型、MVP Presenter 状态管理与 Material 3 移动交互底栏：

```
                      ┌───────────────────────────────────────┐
                      │     PortForwardingBottomSheet (UI)    │
                      │  - Material 3 规范 (触摸靶心 >= 48dp) │
                      │  - 宿主端口 / Guest 端口输入框        │
                      │  - TCP / UDP 协议分段切换器           │
                      │  - 规则列表 Switch 开关与删除按钮     │
                      └──────────────────┬────────────────────┘
                                         │ UI 事件
                                         ▼
                      ┌───────────────────────────────────────┐
                      │       PortForwardingPresenter         │
                      │  - loadRules()                        │
                      │  - addRule(host, guest, proto, desc)  │
                      │  - toggleRule(ruleId)                 │
                      │  - deleteRule(ruleId)                 │
                      └──────────┬─────────────────┬──────────┘
                                 │                 │
              校验请求           ▼                 ▼ 数据持久化
   ┌───────────────────────────────────┐   ┌───────────────────────────────────┐
   │    PortForwardingRuleValidator    │   │      PortForwardingManager        │
   ├───────────────────────────────────┤   ├───────────────────────────────────┤
   │ 1. 宿主端口: 1024 ~ 65535 (免Root)│   │  - FilePortForwardingStore        │
   │ 2. Guest 端口: 1 ~ 65535          │   │  - PortConflictDetector           │
   │ 3. 宿主端口与协议唯一性防冲突校验 │   │  - 持久化 JSON 规则至网络存储区   │
   └───────────────────────────────────┘   └───────────────────────────────────┘
```

#### 关键技术突破：
1. **纯 JVM 防御性校验深模块（`PortForwardingRuleValidator`）**：
   - 彻底杜绝使用 Android 特权端口：宿主端口强制限制在 $1024 \sim 65535$，拦截任何尝试占用 $< 1024$ 系统端口的行为；
   - Guest 端口支持 $1 \sim 65535$ 全范围；
   - 实时冲突检测：当同协议（如 TCP）下已有规则占用了相同的宿主端口时，立即拦截并给出明确提示；支持不同协议（如 TCP 与 UDP）共享同一宿主端口。
2. **逻辑与呈现彻底解耦（`PortForwardingPresenter`）**：
   - Presenter 采用纯 JVM 实现，定义 `View` 契约接口，通过依赖注入接收 `PortForwardingManager` 与 `PortForwardingRuleValidator`；
   - 覆盖所有异步与同步状态：空规则展示、添加成功刷新、校验失败提示、开关状态切换、规则删除；
   - 便于离线无 Android 运行时环境下执行 100% 覆盖率单元测试。
3. **Material 3 人体工程学移动界面（`PortForwardingBottomSheet.kt`）**：
   - 输入框采用 Material 3 Outlined 样式，辅以清晰的辅助提示文字；
   - 规则条目卡片中，开关（`SwitchMaterial`）与删除图标均满足 $\ge 48\times 48\text{ dp}$ 触摸靶心，杜绝误触；
   - 优雅的协议角标（TCP 绿色 / UDP 蓝色）与动态空状态占位图示。

---

## 3. 自动化质量门禁与测试数据验证

在暂无物理真机的离线开发机环境下，全栈质量完全由纯 JVM 单元测试、状态机契约测试与协议序列化测试兜底保障。

### 3.1 质量门禁执行记录

#### 门禁一：主工程与契约全量单元测试
```powershell
.\gradlew.bat testDebugUnitTest :runtime:contract:check
```
- **构建状态**：`BUILD SUCCESSFUL in 14s`
- **任务统计**：`62 actionable tasks: 17 executed, 45 up-to-date`
- **关键测试套件明细**：
  - `AUDIO_STREAM_CONTRACT_TEST_OK`
  - `BRIDGE_CONTROL_CLIPBOARD_TEST_OK`
  - `BRIDGE_CONTROL_DISPLAY_TEST_OK`
  - `DISPLAY_CONTROL_CONTRACT_TEST_OK`
  - `FULL_LINUX_CONTRACT_AUDIO_CAPABILITY_TEST_OK`
  - `GESTURE_STATE_ENGINE_TEST_OK` *(Phase 4.8 核心状态机契约)*
  - `INPUT_EVENT_SERIALIZATION_TEST_OK` *(Phase 4.8 手势线协议序列化)*
  - `NETWORK_CONTRACT_TEST_OK`
  - `POINTER_EVENT_RELATIVE_MOTION_TEST_OK`
  - `PORT_FORWARDING_CONTRACT_TEST_OK`
  - `SHARED_DIRECTORY_CONTRACT_TEST_OK`
  - `STORAGE_CONTRACT_TEST_OK`
  - `VM_HARDWARE_CONFIG_TEST_OK`
  - `InteractiveTerminalEngineAdvancedTest` *(9 组高级终端行编辑用例)*
  - `PortForwardingRuleValidatorTest` *(10 组端口防错与冲突用例)*
  - `PortForwardingPresenterTest` *(6 组网络管理 Presenter 状态用例)*
  - `TouchGestureMapperTest` *(双指缩放与触控手势映射用例)*

#### 门禁二：Guest Assets 离线测试套件
```powershell
.\gradlew.bat -p guest-assets test --rerun-tasks
```
- **构建状态**：`BUILD SUCCESSFUL in 18s`
- **任务统计**：`3 actionable tasks: 3 executed`
- **测试通过率**：100%（全绿无衰退）

---

## 4. 关键文件资产清单与 Git 提交追溯

### 4.1 Git 语义化提交历史（基于 `main` 分支）

```
* d212f83 - feat(network): 落地 Material 3 动态端口转发管理与输入防错防冲突校验 (Phase 4.8)
* 149819d - feat(gesture): 扩充手势事件契约与 Wayland 移动双模触控状态机 (Phase 4.8)
* fdd157d - feat(terminal): 深化交互终端光标编辑、历史导航与 Tab 补全扩展 (Phase 4.8)
* 76d34f7 - docs: refresh README with Phase 4.7 full-stack capabilities and remove device-specific screenshots
* 841bf0d - docs(report): 归档 Phase 4.7 全栈架构重塑与体验打磨交接报告
```

### 4.2 核心源文件分布与职责

```
app/src/main/
├── java/io/github/nyzeep/orchestella/
│   ├── InteractiveTerminalEngine.java        # 扩展光标移动、任意位置编辑、历史定位与 Tab 补全
│   ├── TouchGestureMapper.java               # 接入双指 Pinch 监听器与多点触控距离解算
│   ├── PortForwardingRuleValidator.java      # [新增] 纯 JVM 非特权端口规范与冲突校验器
│   ├── PortForwardingPresenter.java          # [新增] 纯 JVM 端口转发 MVP 状态管理器
│   ├── PortForwardingBottomSheet.kt          # [新增] Material 3 端口转发管理底栏交互
│   ├── InstanceActionBottomSheet.kt          # 实例长按菜单接入端口转发操作项
│   └── MainActivity.kt                       # 实例卡片网络管理入口与安全初始化
├── res/
│   ├── drawable/ic_network.xml               # [新增] 矢量网络端口映射图标
│   ├── layout/item_port_forwarding_rule.xml  # [新增] 规则卡片布局 (>=48dp 触摸靶心)
│   ├── layout/layout_port_forwarding_sheet.xml # [新增] M3 端口转发管理面板布局
│   ├── layout/layout_instance_actions_sheet.xml# 扩充端口转发列表入口项
│   └── values/strings.xml                    # 国际化字符串资源扩充
├── test/java/io/github/nyzeep/orchestella/
│   ├── InteractiveTerminalEngineAdvancedTest.java # [新增] 终端光标、编辑、信号与补全测试
│   ├── PortForwardingRuleValidatorTest.java        # [新增] 端口防错与冲突检测单元测试
│   ├── PortForwardingPresenterTest.java            # [新增] 端口管理 Presenter 状态单元测试
│   └── TouchGestureMapperTest.java                 # 扩充 Pinch 捏合手势测试用例
│
runtime/contract/src/
├── main/java/io/github/nyzeep/orchestella/runtime/contract/
│   ├── input/InputEvent.java                 # 扩充 GESTURE 输入事件类型枚举
│   ├── input/GestureEvent.java               # [新增] 手势事件模型 (TAP, SCROLL, PINCH, MODE_SWITCH...)
│   ├── input/GestureStateEngine.java         # [新增] 双模触控 (Direct/Trackpad) 多指手势状态机
│   └── input/InputEventSerialization.java    # 扩充 INPUT/GESTURE 双向编解码
└── test/java/io/github/nyzeep/orchestella/runtime/contract/
    ├── GestureStateEngineTest.java           # [新增] 纯 JVM 手势状态机状态转移契约测试
    └── InputEventSerializationTest.java      # 扩充手势事件线协议往返测试
```

---

## 5. 架构结论与后续真机联调指引

Orchestella Studio Phase 4.8 战役在纯开发机与离线环境的约束下，通过深度模块化、状态机模拟与全覆盖的纯 JVM 契约测试，高质量完成了三大维度的能力升级：
1. **终端操作深度**：具备现代终端标准的行内任意游标编辑与控制信号响应；
2. **桌面触控质感**：提供适合高分屏的虚拟触控板微操模式与绝对触控直操模式；
3. **网络管理透明度**：向移动用户开放了安全无 root 限制的动态端口转发配置通道。

### 后续真机联调与测试验证建议：
1. **终端光标交互验证**：
   - 在真机启动终端，输入一段文本后点击左方向键将光标移至中间，键入新字符确认插入平滑无抖动；
   - 点击退格键确认中间字符精准擦除且右侧字符无残影；按下 Ctrl+C 确认立即重置当前行。
2. **多模桌面手势验证**：
   - 进入桌面后，通过 HUD 切换为虚拟触控板模式，单指在屏幕滑动观察指针跟随度；
   - 双指同向滑动页面验证 `wl_pointer.axis` 滚轮滚动；双指轻触或长按验证上下文菜单弹出。
3. **端口转发端到端验证**：
   - 实例运行后长按弹出菜单，进入“动态端口转发管理”；
   - 添加 `8080 -> 80` 规则，在 Linux Guest 内启动 `python3 -m http.server 80`；
   - 在宿主 Android 浏览器中访问 `http://127.0.0.1:8080`，验证 NAT 转发直通。
4. **会话收尾与盘符释放**：
   ```powershell
   .\gradlew.bat --stop
   subst W: /D
   subst S: /D
   ```
