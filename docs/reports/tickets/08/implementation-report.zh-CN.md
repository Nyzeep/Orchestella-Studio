# 工单 08 实施与审查报告：多终端并发支持与右侧滑出抽屉管理

- **工单编号**：Phase 6.0 - 工单 08 (Ticket 08)
- **工单标题**：多终端并发支持与右侧滑出抽屉管理 (Multi-Terminal Session Concurrency & Right Slide-out Drawer Management)
- **研发规范与指导**：`/implement` + `/tdd` 测试驱动开发 + 双轴代码审查（Standards & Spec 轴）
- **开发分支**：`feature/ticket-08-multi-terminal-drawer`
- **基线提交**：`792d578`
- **状态**：**ready-for-review**
- **报告日期**：2026-10-10

---

## 1. 目标背景与核心痛点

### 1.1 背景与业务场景
在移动端 Linux 容器与虚拟机运维（如 Orchestella Studio）中，开发者经常面临长耗时任务（如后台编译内核、`apt upgrade`、启动本地服务、tail 查看日志）与临时交互任务（如编辑配置文件、查看进程状态、执行 git 命令）并存的真实开发场景。
在原有单终端模型下：
1. **单一会话阻塞**：一旦前台终端执行长耗时阻塞命令，整个实例终端被占满，无法开辟新窗口处理紧急调试；
2. **缺乏多任务视图**：用户无法直观了解当前容器运行了多少个独立 Shell 会话，无法快速切换或重命名工作流标签；
3. **数据串流风险**：若没有帧级多路复用协议与隔离的 Ring Buffer，多终端在后台并发接收输出时极易发生文本混乱（Cross-session Bleed）。

### 1.2 工单需求与规格目标
对标 Termius、JuiceSSH 与桌面级多标签页终端体验，在 Orchestella Studio 中为运行中容器赋予**多终端并发（Multi-Terminal Sessions）**与**右侧滑出抽屉管理**能力：
- 支持右边缘滑出手势与右上角轻量快捷按钮（`◫ 终端 (N)`）呼出抽屉；
- 抽屉面板集成：「+ 新建终端」显眼按钮、会话 RecyclerView 列表（带实时运行指示绿点、当前活动 Badge、长按重命名、关闭 ✕ 确认）、底部会话计数与快捷收起；
- 会话间毫秒级无闪烁切换，后台会话独立维护输出缓冲区，切回时即刻重绘；
- 纯 JVM 线程安全的多会话聚合器与帧多路复用契约，支持会话上限配额保护与活动指针平滑迁移（Failover migration）。

---

## 2. 架构设计与深模块演进

```mermaid
flowchart LR
    subgraph UI_Layer ["UI / 交互层 (Presentation Layer)"]
        DrawerLayout["DrawerLayout (GravityCompat.END)"]
        HandleBtn["btnDrawerHandle (◫ 终端 (N))"]
        DrawerPanel["TerminalDrawerPanel (Dracula 主题抽屉)"]
        TerminalView["ModernTerminalView (终端画布)"]
    end

    subgraph Core_Manager ["会话管理层 (Session Aggregator Layer)"]
        Manager["MultiTerminalSessionManager (纯 JVM 线程安全单例)"]
        Session1["Session 1 (活跃活动指针)"]
        Session2["Session 2 (后台运行中)"]
        SessionN["Session N (后台运行中)"]
    end

    subgraph Protocol_Layer ["协议与数据流隔离层 (Multiplexer Protocol)"]
        RingBuffer["TerminalRingBuffer (各会话独立环形缓冲)"]
        Proto["PtyMultiplexerProtocol (Magic: PT, DATA/RESIZE/CLOSE 帧)"]
    end

    DrawerLayout --> DrawerPanel
    HandleBtn -->|"点击呼出/收起"| DrawerLayout
    DrawerPanel -->|"创建/选择/重命名/关闭"| Manager
    Manager --> Session1
    Manager --> Session2
    Manager --> SessionN
    Session1 --> RingBuffer
    Session2 --> RingBuffer
    Session1 -->|"激活画布渲染"| TerminalView
    Proto -->|"多路复用帧编解码"| RingBuffer
```

### 2.1 核心模块 1：右侧滑出抽屉面板 (`TerminalDrawerPanel.java`)
- **抽屉布局与主题**：
  - 基于 Material `DrawerLayout` 设置 `GravityCompat.END`，背景使用 Dracula Palette 深色调（`0xFF21222C` / `0xFF282A36`）；
  - 面板顶部配置显眼扁平圆角按钮 `「+ 新建终端」`（绿色高亮 `0xFF50FA7B`）；
  - 面板中部基于 `RecyclerView` 呈现会话卡片：
    - 状态指示灯（活动/最近更新为绿色 `●`，后台等待为空闲灰色）；
    - 标题文本（当前活动为粉紫色高亮，非活动为浅白色）；
    - 当前会话 Badge（紫蓝色圆角胶囊标签 `当前`）；
    - 关闭按钮 `✕`（红色警示，点击弹出二次确认对话框）；
    - 长按条目弹出 `AlertDialog` 实时重命名会话（如自定义为 `构建服务`、`Nginx 日志`）；
  - 底部提供会话总数实时统计（`共 N 个终端`）与快捷收起按钮（`收起 ❯`）。
- **交互联动**：
  - 会话选中：点击任意会话条目即刻触发 `onSessionSelected`，自动平滑收起抽屉，终端画布毫秒级切入目标会话；
  - 返回键拦截：重写 `TerminalActivity.onBackPressed()`，当抽屉处于打开状态时优先收起抽屉，防止误退 Activity。

### 2.2 核心模块 2：纯 JVM 线程安全多终端会话管理器 (`MultiTerminalSessionManager.java`)
- **双层隔离映射**：
  - 采用 `ConcurrentHashMap<String, Map<String, TerminalSessionInstance>>` 组织实例与终端会话，外层 Key 为 `instanceId`，内层 Key 为 `sessionId`；
  - 每个实例维护独立的 `activeSessionIdMap` 与原子计数器 `sessionCounterMap`，保证会话 ID（如 `sess-1`, `sess-2`）单调递增且不冲突；
- **会话上限配额保护 (Quota Enforcement)**：
  - 支持配置最大并发会话数（默认 16），超过配额时抛出受控 `IllegalStateException`，防止资源耗尽；
- **活动指针平滑故障转移 (Smooth Active Session Failover)**：
  - 当用户关闭当前正活跃的会话时，管理器在锁保护下自动将活动指针平滑转移至该实例下剩余的最新可用会话；
  - 若所有会话均被关闭，活动指针置空，上层 UI 优雅回退创建默认初始会话，杜绝空指针或界面假死；
- **原子重命名**：
  - `renameSession(instanceId, sessionId, newTitle)` 校验非空与存在性后原子更新标题，并分发变更事件。

### 2.3 核心模块 3：帧级多路复用协议与数据隔离 (`PtyMultiplexerProtocol.java` & `TerminalSessionInstance.java`)
- **协议帧结构**：
  - 魔数标头：2 字节 ASCII `PT` (`0x50, 0x54`)；
  - 帧类型 (Type)：1 字节 (`FRAME_TYPE_DATA = 0x01`，`FRAME_TYPE_RESIZE = 0x02`，`FRAME_TYPE_CLOSE = 0x03`)；
  - 会话 ID 长度与字符串：`short` 长度 + UTF-8 `sessionId`；
  - 载荷长度与载荷：4 字节 `int` 长度 + 原始二进制字节流；
- **数据隔离与后台无缝重绘**：
  - 每个 `TerminalSessionInstance` 封装独立的 `TerminalRingBuffer`（默认容量 2000 行）、光标位置及 IME 输入状态；
  - 当多个会话在后台并发产生输出时，数据直接流入各自独立的 Ring Buffer，彻底阻断跨会话文本污染（Zero Cross-session Bleed）；
  - 用户切换会话时，`switchToSessionUi()` 从目标会话获取最新 `getTranscript()` 全量刷新画布，光标自动归位。

---

## 3. 双轴代码审查报告 (Code Review)

### 3.1 规范轴 (Standards Axis)
1. **纯 JVM 测试解耦**：
   - `MultiTerminalSessionManager` 与 `PtyMultiplexerProtocol` 保持 100% 纯 JVM 实现，不依赖任何 Android Framework 运行时或 Mock 框架；
   - 单元测试运行于纯 JVM 宿主环境，毫秒级执行完毕，无测试不稳定隐患。
2. **线程安全性与并发控制**：
   - 共享状态采用 `ConcurrentHashMap`，实例级互斥操作使用 `synchronized (lock)` 临界区；
   - 对 `listeners` 的通知采用不可变快照遍历，避免并发修改异常 (`ConcurrentModificationException`)。
3. **主题与编码规范**：
   - 界面控件全面对齐项目既有的 Dracula 调色板 (`DraculaPalette.COLOR_GREEN`, `COLOR_BLUE`, `COLOR_CYAN`, `COLOR_MAGENTA`, `COLOR_RED` 等)；
   - 所有源代码严格保存为 UTF-8 无 BOM 格式，杜绝 Windows 环境下 `javac` 字符集解析失败。
4. **生命周期与资源泄露防御**：
   - `TerminalActivity.onDestroy()` 中妥善注销监听器并释放后台线程池；
   - 会话关闭对话框使用 Context 防护，避免内存泄露。

### 3.2 需求轴 (Spec Axis)
| 工单规格要求 | 对应实现代码/机制 | 达成状态 |
| :--- | :--- | :--- |
| **右侧滑出手势与快捷图标** | `DrawerLayout` + `GravityCompat.END` + 顶栏快捷悬浮按钮 `btnDrawerHandle` (`◫ 终端 (N)`) | ✅ 已达成 |
| **新建终端显眼入口** | `TerminalDrawerPanel` 顶部大按钮 `「+ 新建终端」`（绿色 Dracula 配色） | ✅ 已达成 |
| **多会话列表与状态指示** | `RecyclerView` + `SessionListAdapter` 展示状态绿点、当前活动 Badge、会话名称 | ✅ 已达成 |
| **长按重命名** | 条目长按触发 `AlertDialog` 输入框，实时原子重命名并在顶栏同步更新 | ✅ 已达成 |
| **关闭终端与活动指针转移** | 条目右侧 `✕` 按钮二次确认，关闭当前会话时平滑转移指针至剩余可用会话 | ✅ 已达成 |
| **数据隔离与零串流** | 各会话独占 `TerminalRingBuffer`，多线程并发写入互不干扰 | ✅ 已达成 |
| **帧多路复用协议契约** | `PtyMultiplexerProtocol` 严格校验魔数、帧类型、会话 ID 路由 | ✅ 已达成 |
| **返回键优雅拦截** | `onBackPressed()` 优先检测并关闭展开的抽屉 | ✅ 已达成 |

---

## 4. TDD 测试先行与验证数据

### 4.1 单元测试套件覆盖
- `app/src/test/java/io/github/nyzeep/orchestella/terminal/MultiTerminalSessionManagerTest.java`
  - `testCreateMultipleSessionsAutoIncrementsAndEnforcesQuota`: 验证单实例创建多个终端，会话 ID 自增且独立，达到配额上限时严格拦截；
  - `testRenameSession`: 验证会话原子重命名与非空校验；
  - `testSwitchActiveSession`: 验证在多会话之间平滑切换活动状态指针；
  - `testConcurrentWritingIsolation`: 模拟 3 个工作线程向 3 个独立会话高并发写入大量数据，断言各会话缓冲区内容严格隔离、零数据交叉污染；
  - `testCloseSessionAndActivePointerFailover`: 验证关闭活跃会话时指针自动平滑迁移至下一可用会话，全部关闭后优雅清空；
- `app/src/test/java/io/github/nyzeep/orchestella/terminal/PtyMultiplexerProtocolTest.java`
  - `testEncodeDecodeDataFrame`: 验证 `FRAME_TYPE_DATA` 编解码一致性；
  - `testEncodeDecodeResizeFrame`: 验证 `FRAME_TYPE_RESIZE` 终端行列尺寸载荷打包与拆包；
  - `testEncodeDecodeCloseFrame`: 验证 `FRAME_TYPE_CLOSE` 控制帧打包；
  - `testCorruptMagicRejection`: 验证非法魔数帧的严格异常拦截；
  - `testTruncatedStreamRejection`: 验证截断流的完整性防御；
  - `testStreamMultiplexingSeparation`: 验证多路复用流按会话 ID 精准拆分路由。

### 4.2 测试执行结果
在纯 ASCII 盘符 `W:\` 环境下执行：
```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
**执行输出与统计**：
- **测试套件总数**：79 个测试套件 XML 文件
- **测试用例总数**：458 个测试用例
- **失败数 (Failures)**：0
- **跳过数 (Skipped)**：0
- **测试通过率**：**100% 绿灯**
- **构建状态**：`BUILD SUCCESSFUL in 26s`

---

## 5. 变更文件清单

```text
app/src/main/java/io/github/nyzeep/orchestella/
├── TerminalActivity.java                             (集成 DrawerLayout, 右侧抽屉管理面板, 快捷手柄按钮, 返回键拦截与会话 UI 平滑切换)
└── terminal/
    ├── MultiTerminalSessionManager.java              (纯 JVM 线程安全多终端会话管理器, 配额保护与平滑 Failover)
    ├── PtyMultiplexerProtocol.java                   (多路复用帧编解码协议: DATA/RESIZE/CLOSE 帧)
    ├── TerminalDrawerPanel.java                      (Dracula 主题右侧滑出抽屉面板, RecyclerView 会话列表, 新建/重命名/关闭对话框)
    └── TerminalSessionInstance.java                  (独立终端会话实例: 独占 RingBuffer, 标题, 活跃状态与光标)

app/src/test/java/io/github/nyzeep/orchestella/terminal/
├── MultiTerminalSessionManagerTest.java              (多会话自增、上限保护、多线程并发隔离、指针 Failover 测试)
└── PtyMultiplexerProtocolTest.java                   (帧编码、解码、防篡改、截断防御及多路流隔离测试)
```

---

## 6. 结论
工单 08（多终端并发支持与右侧滑出抽屉管理）的所有功能规格、架构切面、并发数据隔离协议以及 TDD 单元测试已全部完成并高质量交付。所有 458 个单元测试用例在独立工作树下 100% 绿灯通过，代码规范与工单需求双轴审查均无缺陷，具备合并交付条件。