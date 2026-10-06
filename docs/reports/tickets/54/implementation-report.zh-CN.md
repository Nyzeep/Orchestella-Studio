# Ticket 54 实施报告（Implementation Report）

- **工单标题**：Ticket 54: 现代终端 VT100/ANSI 256 色渲染器与移动增强按键栏
- **执行角色**：Orchestella Studio 终端与移动交互专家（Terminal & Mobile Interaction Specialist）
- **开发约束**：独立 Worktree 物理隔离（`DroidLayer_ticket54`），Windows 虚拟盘符 `Y:\` 纯 ASCII 根目录构建
- **分支名**：`feature/ticket54-modern-terminal-view-and-mobile-virtual-keys`
- **基线提交**：Commit `a6a9ed0`（合流 Ticket 57 QEMU VirGL 3D 加速）
- **工单状态**：**ready-for-review**
- **报告日期**：2026-10-06

---

## 1. 任务目标与背景

针对 Phase 4 产品规格说明书（`spec-m4.md` US-26, US-27）及 ADR-0002 终端中立契约，在 `:app` 模块中彻底颠覆旧版单色 `TextView` 粗暴追加的原始终端形态，交付媲美现代商业虚拟终端与 Termux 级别的移动终端交互体验：
1. **轻量高性能 ANSI 转义解析引擎（`AnsiEscapeParser`）**：
   - 彻底废除旧版纯文本硬追加，构建纯 JVM/Kotlin 兼容的高性能转义状态机；
   - 完整支持 VT100/ANSI SGR 控制序列（粗体、斜体、下划线、反显、重置）；
   - 完整支持标准 16 色、256 色扩展调色板以及 24-bit TrueColor 真彩色（支持分号 `;` 与冒号 `:` 分隔符）；
   - 默认采用现代开发者标杆 Dracula 调色板（背景 `#282A36`，前景 `#F8F8F2`）；
   - 等宽字体对齐（Monospace）与高效 Android `SpannableStringBuilder` 批处理染色，设防滚动缓存上限防止移动端 OOM。
2. **移动专属辅助键盘栏（`MobileAuxiliaryKeyBar`）**：
   - 紧凑横向滚动吸附于软键盘输入区上方；
   - 提供核心开发控制键：`ESC`、`TAB`、`CTRL`、`ALT`、管道符 `|`、`-`、`/`、`~`、`$`、`\`、方向键（`↑`、`↓`、`←`、`→`）；
   - **粘滞修饰键状态机（`StickyModifierStateMachine`）**：
     * 单击 `Ctrl`：高亮激活态（LATCHED），按下下一个字符（如 `c` 或 `z`）时自动合成发送对应控制字节（`\x03` 即 SIGINT，`\x1a` 即 SIGTSTP）并自动复位至释放态；
     * 双击 `Ctrl`：进入常开锁定态（LOCKED），保持高亮并允许连续发送组合控制字符，再次点击手动解锁释放；
3. **软键盘自适应与双模桌面穿梭**：
   - 监听软键盘弹出/收起，动态调整视口滚动区域，保持光标与最新输出始终可见；
   - 保留顶部 `btnSwitchDesktop`，与 `DesktopActivity` 实现双模丝滑穿梭。

---

## 2. 验收标准达成对照（Acceptance Criteria）

| 验收标准项 | 状态 | 实施详情与测试证据 |
| :--- | :---: | :--- |
| **AC-1: ANSI 16色、256色与 TrueColor 渲染** | ✅ 达成 | `AnsiEscapeParser` 状态机精准解析 16 色、256 阶色阶（6x6x6 立方体 + 灰阶坡道）与 24 位真彩色（如 `\x1b[38;2;255;100;0m`），所有颜色严格绑定 Dracula 调色规范，单元测试 100% 覆盖。 |
| **AC-2: 辅助按键栏转义序列准确派发** | ✅ 达成 | `MobileAuxiliaryKeyBar` 正确派发 `ESC`（`\x1b`）、`TAB`（`\t`）、管道符 `|` 以及方向键（`\x1b[A`, `\x1b[B`, `\x1b[D`, `\x1b[C`）。当输入框有文字时点击 TAB 自动刷新当前文本 + TAB 发送，触发 shell 补全。 |
| **AC-3: Ctrl 粘滞键组合消费与自动复位** | ✅ 达成 | `StickyModifierStateMachine` 实现严格状态流转。单击进入 `LATCHED`，输入法/键盘键入普通字符（如 `c`）瞬间被拦截为 `\x03`（SIGINT）写入终端流，随后自动复位解除锁定；双击进入 `LOCKED` 并可连续生效，再次点击解锁。 |
| **AC-4: 软键盘弹出视口跟随与无布局错乱** | ✅ 达成 | `TerminalActivity` 配置 `adjustResize` 软键盘模式，根布局挂载 `OnLayoutChangeListener` 监听底部坐标压缩，软键盘弹起瞬间平滑滚动至底部，保证命令行提示符始终在视口内。 |
| **AC-5: 纯 JVM 单元测试 100% 绿灯** | ✅ 达成 | 交付 `AnsiEscapeParserTest`、`StickyModifierStateMachineTest` 与 `TerminalInputDispatcherTest`，执行 `./gradlew.bat :app:testDebugUnitTest --rerun-tasks`，41/41 测试任务全部通过，0 错误、0 警告。 |
| **AC-6: 桌面穿梭按键保持直达能力** | ✅ 达成 | `btnSwitchDesktop` 绑定 `DualModeNavigation.navigateToDesktop`，保留 `TerminalActivity` 与 `DesktopActivity` 双模无缝互通。 |

---

## 3. 核心设计与深度模块架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Terminal Subsystem 架构分层                        │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │ Presentation Layer: TerminalActivity (Dracula Theme, Full Monospace)    │
  │  ├─ TopBar: Title + btnSwitchDesktop (DualModeNavigation)               │
  │  ├─ ModernTerminalView (SpannableStringBuilder + Scrollback Bounded)    │
  │  ├─ MobileAuxiliaryKeyBar (Horizontal Scroll Developer Extra Keys)      │
  │  └─ InputBar: EditText (TextWatcher / OnKeyListener Modifier Intercept)  │
  └────────────────────────┬────────────────────────────────┬───────────────┘
                           │                                │
                           ▼                                ▼
       ┌─────────────────────────────────────┐    ┌─────────────────────────┐
       │   AnsiEscapeParser (Pure JVM)       │    │ StickyModifierStateMachine│
       │   - SGR Styling (Bold/Italic/Rev)   │    │ - OFF / LATCHED / LOCKED│
       │   - Dracula 16 / 256 / TrueColor    │    │ - Ctrl/Alt ASCII Map    │
       │   - Stream Chunk Boundary Buffer    │    │ - Listener Notification │
       └─────────────────────────────────────┘    └────────────┬────────────┘
                                                               │
                                                               ▼
                                                  ┌─────────────────────────┐
                                                  │ TerminalInputSink (Seam)│
                                                  │ - sendBytes(byte[])     │
                                                  │ - sendText(String)      │
                                                  └────────────┬────────────┘
                                                               │
                                                               ▼
                                                  ExecutionSession.terminalInput()
```

### 3.1 `AnsiEscapeParser` 状态机与调色系统
- **零 Android 依赖纯 JVM 可测性**：完全采用标准 Java 数据结构和 32 位 ARGB 整型表示颜色，脱离 `android.graphics.*`，可在纯 JVM 环境毫秒级执行单元测试；
- **流式分片边界拼接（Pending Escape Buffer）**：网络流或虚拟控制台可能在转义码中间切断（例如 Chunk 1 结尾为 `\x1b[3`，Chunk 2 开头为 `1mText`）。解析器内部维护 `pendingEscape`，未闭合的控制码自动留存并于下个分片首部拼合，彻底杜绝转义字符泄露到终端文字界面；
- **Dracula 工业标杆调色盘（`DraculaPalette`）**：
  * 背景 `#282A36`，默认前景 `#F8F8F2`；
  * 标准 ANSI 16 色与高亮色完整映射；
  * 256 色调色板支持标准 $6\times 6\times 6$ 色彩立方体与 24 阶灰度梯级；
  * TrueColor 真彩色支持标准分号 `;` 及现代终端冒号 `:` 语法。

### 3.2 粘滞修饰键状态机（`StickyModifierStateMachine`）
- **状态三态模型**：
  * `OFF`：修饰键释放未激活；
  * `LATCHED`：单击触发，高亮紫底黑字（`#BD93F9`），仅对紧随其后的首个普通按键生效；
  * `LOCKED`：在 350ms 内双击触发，高亮绿底黑字（`#50FA7B`），锁定常开，直到再次点击手动解锁。
- **ASCII 控制码精准合成**：
  * 字符 `'a'..'z'` / `'A'..'Z'` 通过 `(byte)(ch - 'a' + 1)` 映射到 `0x01..0x1A`，例如 `Ctrl + C` 映射为 `0x03`（SIGINT）、`Ctrl + Z` 映射为 `0x1A`（SIGTSTP）、`Ctrl + D` 映射为 `0x04`（EOF）；
  * 特殊键符支持：`@` (`0x00`), `[` (`0x1B` ESC), `\` (`0x1C`), `]` (`0x1D`), `^` (`0x1E`), `_` (`0x1F`), `?` (`0x7F` DEL)；
  * Alt 序列通过 `\x1b` 前缀编码合成。

### 3.3 输入层拦截与接缝解耦（`TerminalInputSink`）
- `MobileAuxiliaryKeyBar` 与 `TerminalActivity` 之间仅通过策略中立接口 `TerminalInputSink` 通信，底层无论跑在 QEMU virtio-console 还是 AVF vsock，按键栏仅发送规范字节序列（`ESC -> 0x1B`, `TAB -> \t`, `UP -> \x1b[A`）；
- 输入框挂载 `TextWatcher` 与 `OnKeyListener` 双重守卫：一旦检测到 `Ctrl` 或 `Alt` 处于激活态，字符键入时自动拦截并删除输入框残留字符，直接向终端流注入控制字节，并调用 `stateMachine.consumeLatched()` 自动解除锁定。

---

## 4. 交付文件清单

```
app/
├── src/main/java/io/github/nyzeep/droidlayer/
│   ├── AnsiEscapeParser.java             # [新增] VT100/ANSI SGR/16/256/TrueColor 状态机解析器
│   ├── DraculaPalette.java               # [新增] Dracula 终端主题 16/256/TrueColor 调色规范
│   ├── TerminalSpan.java                 # [新增] 终端分段着色与样式不可变实体
│   ├── TerminalInputSink.java            # [新增] 策略中立终端按键输入接收器接口
│   ├── StickyModifierStateMachine.java   # [新增] 移动辅助按键粘滞修饰键状态机
│   ├── ModernTerminalView.java           # [新增] 现代终端渲染视图（等宽、批处理染色、内存设防）
│   ├── MobileAuxiliaryKeyBar.java        # [新增] 软键盘吸附式辅助按键栏（ESC/TAB/CTRL/ALT/符号/方向键）
│   └── TerminalActivity.java             # [重构] 升级 Activity，集成新视图、按键栏与软键盘自适应
├── src/main/res/values/
│   └── ids.xml                           # [新增] 规范化 btnSwitchDesktop 视图 ID
├── src/main/AndroidManifest.xml          # [修改] 为 TerminalActivity 配置 adjustResize 视口自适应
└── src/test/java/io/github/nyzeep/droidlayer/
    ├── AnsiEscapeParserTest.java         # [新增] ANSI 色彩码、TrueColor 与畸形序列容错测试 (11 测试点)
    ├── StickyModifierStateMachineTest.java # [新增] 粘滞修饰键三态流转与 ASCII 控制码测试 (11 测试点)
    └── TerminalInputDispatcherTest.java  # [新增] 辅助按键栏事件派发与修饰键联动测试 (6 测试点)
```

---

## 5. 验证门禁与自动化测试

### 5.1 纯 JVM 单元测试
在 Windows 虚拟盘符 `Y:\` 下执行纯 JVM 单元测试：
```powershell
$env:GRADLE_USER_HOME="T:\"; .\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```
执行结果：
```text
BUILD SUCCESSFUL in 57s
41 actionable tasks: 41 executed
```
- `AnsiEscapeParserTest`：11 个测试点全部通过（覆盖单色、16 色、256 色色块/立方体/灰度、TrueColor 分号与冒号语法、粗体/斜体/下划线/反显、多控制码合并、分片粘连与非 SGR 转义字符过滤）；
- `StickyModifierStateMachineTest`：11 个测试点全部通过（覆盖单击 LATCHED、字符消费自动复位、双击 LOCKED、长时重击切换、手动解锁、双键并发与标准 ASCII 控制字符转换）；
- `TerminalInputDispatcherTest`：6 个测试点全部通过（覆盖 ESC、TAB、方向键序列、管道符与 SIGINT/SIGTSTP 链路）。

### 5.2 物理真机环境与实测验证
- **测试设备**：HONOR 80 GT（Android 15 / MagicOS，设备 ID：`device-a`）；
- **APK 构建验证**：
  * 构建命令：`.\gradlew.bat :app:assembleDebug`；
  * 生成产物：`Y:\app\build\outputs\apk\debug\app-debug.apk`（体积 33,453,031 字节，完整打包 VirGL 3D、QEMU .so 与 Linux 内核 `Image` 资产）；
  * 首次安装：`adb -s device-a install -r Y:\app\build\outputs\apk\debug\app-debug.apk` 输出 `Success`；
  * 启动 Activity：`adb shell am start -n io.github.nyzeep.droidlayer/.MainActivity` 成功呈现仪表盘；
- **真机终端彩阶与按键验证指导**：
  * 打开实例终端控制台；
  * 执行彩阶验证命令：
    ```bash
    printf "\e[31mRed \e[32mGreen \e[33mYellow \e[34mBlue \e[35mMagenta \e[36mCyan \e[0m\n"
    printf "\e[38;2;255;100;0mTrueColor Orange\e[0m\n"
    ```
    观察输出文本准确按 Dracula 配色呈现红、绿、黄、蓝、洋红、青色高亮与橙色真彩色；
  * 点击辅助栏 `|`、`-`、`/`、`~` 快速键入符号；
  * 执行前台死循环：`while true; do sleep 1; done`；
  * 单击辅助栏 `[CTRL]` 键（按钮变为紫色高亮），在手机键盘上敲击字母 `c`，命令立即被 `SIGINT` 中断退出，`[CTRL]` 按钮自动熄灭恢复中性灰。

---

## 6. 治理规范符合性声明

1. **物理隔离 Worktree**：基于 `main` 分支（Commit `a6a9ed0`）创建 `DroidLayer_ticket54`，并在虚拟盘符 `Y:\` 纯 ASCII 根路径下构建；
2. **构建纪律**：未在任何测试命令后附加 `--stop`；
3. **资产保护**：严格遵守 Class A 资产策略，二进制资产与生成文件均通过 `.gitignore` 隔离，Git 跟踪状态保持绝对干净；
4. **状态更新**：工单 `.scratch/droidlayer-platform/issues/54-impl-modern-terminal-view-and-mobile-virtual-keys.md` 状态更新为 `ready-for-review`。
