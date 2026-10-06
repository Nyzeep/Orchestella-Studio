# Ticket 44 实施报告（Implementation Report）

> 工单：`44-contract-audio-clipboard-and-peripherals`（策略中立剪贴板与音频契约扩展）  
> 工作区物理路径：`D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_ticket44`  
> 工作分支：`feature/ticket-44-contract-audio-clipboard`（基于基线 commit `1c81df8`）  
> 状态：**实施完成，提请独立复审（ready-for-review）**  
> 日期：2026-10-06  

---

## 1. 任务背景与核心目标

在 Phase 3（M3 里程碑：系统深度互通、音频流输出与外设集成）中，Orchestella Studio 致力于消除 Android 移动宿主与 Linux 虚拟机之间的“体验孤岛”（剪贴板完全隔离、虚拟机无音频输出、鼠标光标受限于屏幕边缘双光标碰撞）。

依照 ADR-0002、ADR-0013 与 ADR-0018 架构决议，底层执行引擎分为 QEMU 软模拟档（L1）与 AVF 硬件虚拟化档（L2）。上层 UI 呈现与底层硬件执行策略必须保持强解耦，所有互通能力必须首先在策略中立的契约层（`:runtime:contract`）定义完备。

本工单（Ticket 44）的核心目标是在 `:runtime:contract` 中完成以下 4 项核心契约设计与扩展：
1. **能力矩阵扩容（`FullLinuxContract.Capability`）**：
   - 在枚举中新增 `AUDIO_OUTPUT`（低延迟 PCM 音频流单向输出）、`CLIPBOARD_SYNC`（剪贴板双向无损增量同步）、`POINTER_CAPTURE`（无边界相对指针捕获）；
   - 在 `CapabilityMatrix` 中提供校验方法与策略透明徽章输出（`audioBadge()`、`clipboardBadge()`、`pointerCaptureBadge()`）及完整外设预设方法；
2. **Bridge v1 线协议命令扩容（`BridgeControl`）**：
   - 扩容剪贴板命令集：`DROIDLAYER/CLIPBOARD PUT base64:<data>\n` 与 `DROIDLAYER/CLIPBOARD GET\n`；
   - 强化安全防御：Base64 语法校验、有效 UTF-8 字符流校验、单次载荷严格限制为 256 KB（$\le 262,144$ 字节），超限拒绝并返回 `DROIDLAYER/ERROR payload-too-large\n`，畸形或非法前缀返回 `DROIDLAYER/ERROR malformed-command\n`；
   - 在 `BridgeControl.Seams` 中增加 `Clipboard` 适配器 seam 接口（提供 `onClipboardPut(String text)` 与 `onClipboardGet()` 回调）；
   - 在 `BridgeClient` 中扩展对应的客户端调用方法（`clipboardPut(String text)` 与 `clipboardGet()`）；
3. **音频流契约抽象（`AudioStreamContract`）**：
   - 定义策略中立的音频流格式与参数模型（固定 48,000 Hz 采样率、16-bit 小端有符号 PCM、双声道立体声 Stereo、4 字节帧大小、192,000 字节/秒吞吐、9104 端口）；
   - 定义音频流控制抽象接口（`start()`、`pause()`、`stop()`、状态监听器 `StateListener` 与数学/校验工具方法）；
4. **策略中立指针输入扩容（`PointerEvent`）**：
   - 扩容相对位移字段与状态构造（$\Delta x, \Delta y$ 双精度、按键掩码 `BUTTON_PRIMARY`, `BUTTON_SECONDARY`, `BUTTON_TERTIARY`）；
   - 提供 `isRelativeMotion()` 判断与无边界指针捕获模式构造；
   - 在 `InputEventSerialization` 中保持向前兼容的无损编解码。

---

## 2. 纪律红线与防御性约束遵从核对（Ownership & Defensive Guardrails Verification）

- **管辖模块（Owned Modules）严格遵守**：
  - 本次所有新增生产代码与单元测试严格限制在 `:runtime:contract` 模块（`runtime/contract/src/main/`、`runtime/contract/src/test/`）、`.scratch/` 与 `docs/reports/` 目录下；
  - 严禁触碰模块（`:app`、`:runtime:engine-qemu`、`:runtime:engine-avf`、`guest-assets/`、`.toolchains/`、`artifacts/`）保持 **0 触碰、0 修改**；
  - 严格自查 `git status` 与 `git diff`，无任何非管辖文件被修改。
- **纯 JVM 可测性与零平台依赖**：
  - `:runtime:contract` 保持纯 Java 17 标准库编写，**无任何 Android SDK / NDK / AWT 等平台私有依赖**；
  - 编译选项严格应用 Java 17 + `-Xlint:all -Werror`，达成零警告、零错误。
- **策略中立铁律（ADR-0002 / ADR-0018）**：
  - 契约层中严禁出现任何执行策略专属硬编码（无任何 `if (isAvf)` 或 `if (isQemu)`）；
  - 所有能力差异通过 `CapabilityMatrix` 数据化透明表达。
- **资产卫生与环境保洁**：
  - 未提交任何运行时生成的 `.log` 日志或临时文件；
  - 全流程在物理隔离的专用 Worktree `DroidLayer_ticket44` 与分支 `feature/ticket-44-contract-audio-clipboard` 内完成。

---

## 3. 架构设计细节与核心实现

### 3.1 能力矩阵扩容（`FullLinuxContract.Capability`）与透明徽章
- **能力枚举扩展**：
  在 `FullLinuxContract.Capability` 中扩容：
  * `AUDIO_OUTPUT`：低延迟 PCM 音频流单向输出；
  * `CLIPBOARD_SYNC`：剪贴板双向无损增量同步；
  * `POINTER_CAPTURE`：无边界相对指针捕获。
- **`CapabilityMatrix` 徽章与预设**：
  * `audioBadge()`：
    - 软件模拟策略：`低延迟 PCM 音频（本地套接字 48kHz）`；
    - 硬件加速策略：`低延迟 PCM 音频（vsock 硬件加速 48kHz）`；
    - 未支持时：`无音频输出`；
  * `clipboardBadge()`：
    - 软件模拟策略：`剪贴板双向同步（本地通道）`；
    - 硬件加速策略：`剪贴板双向同步（vsock 硬件通道）`；
    - 未支持时：`无剪贴板同步`；
  * `pointerCaptureBadge()`：
    - 支持时：`相对指针捕获（无边界）`；
    - 未支持时：`无指针捕获`；
  * 扩展预设：`softwareVirtualizationWithPeripherals()` 与 `hardwareVirtualizationWithPeripherals()`，一站式表达包含外设的全功能矩阵声明。

### 3.2 Bridge v1 剪贴板协议扩展（`BridgeControl` & `BridgeClient`）
- **线协议格式与分发**：
  * `DROIDLAYER/CLIPBOARD PUT base64:<data>\n`：推送剪贴板文本到对端；
  * `DROIDLAYER/CLIPBOARD GET\n`：拉取对端当前剪贴板内容；
- **边界防御与格式校验**：
  * 载荷上限防御：常量 `MAX_CLIPBOARD_PAYLOAD_BYTES = 262144`（256 KB）；若解码前 Base64 字符数超长，或解码后原始 UTF-8 字节数 $> 262,144$ 字节，确定性返回 `DROIDLAYER/ERROR payload-too-large\n`；
  * 编码与语法校验：必须携带 `base64:` 前缀且字符合法；若 Base64 语法错误、缺少前缀或解码出的字节流为非法 UTF-8 字符序列，确定性返回 `DROIDLAYER/ERROR malformed-command\n`；
  * 适配接缝（Seam）：新增 `BridgeControl.Clipboard` 接口（`onClipboardPut(String text)` 与 `onClipboardGet()`），并在 `BridgeControl.Seams` 中聚合；若未挂载剪贴板适配器，确定性返回 `DROIDLAYER/ERROR clipboard-unavailable\n`；
- **客户端实现（`BridgeClient`）**：
  * `clipboardPut(String text)`：校验非空与 256KB 上限，Base64 编码后派发并返回 `ok-clipboard` 结果；
  * `clipboardGet()`：拉取对端剪贴板，自动解码 Base64 载荷并通过 `Result.payload()` 透明交付上层；
  * 响应流缓冲区由 512 字节安全扩展为动态 `ByteArrayOutputStream`（上限 512 KB），完美承载 256 KB 文本回传。

### 3.3 音频流契约抽象（`AudioStreamContract`）
- **PCM 格式常量与规范**：
  * 固定采样率：$48,000\text{ Hz}$（`DEFAULT_SAMPLE_RATE_HZ`）；
  * 采样深度：$16\text{ bit}$ 小端有符号 PCM（`DEFAULT_BITS_PER_SAMPLE`，`"s16le"`）；
  * 声道数：$2$ 双声道立体声（`DEFAULT_CHANNELS`）；
  * 帧大小：$4\text{ 字节}$（`DEFAULT_FRAME_SIZE_BYTES`）；
  * 单秒吞吐：$192,000\text{ 字节/秒}$（`DEFAULT_BYTES_PER_SECOND`）；
  * 默认端口：$9104$（`DEFAULT_PORT`，对齐 PulseAudio TCP / vsock 9104）；
- **参数校验与数学计算**：
  * `isValidSampleRate`、`isValidChannels`、`isValidBitsPerSample`：提供确定性参数校验；
  * `computeFrameCount(long byteCount)` 与 `computeByteCount(int frameCount)`：帧与字节精准互转；
  * `computeDurationMs(long byteCount)` 与 `computeBytesForDurationMs(double durationMs)`：播放时长与字节数互转（严格保证 4 字节帧对齐）；
- **控制抽象与生命周期观察**：
  * 状态模型：`State.IDLE`, `State.STARTING`, `State.RUNNING`, `State.PAUSED`, `State.STOPPED`, `State.ERROR`；
  * 控制方法：`start()`, `pause()`, `stop()`, `state()`, `format()`；
  * 状态监听：`addStateListener(StateListener)` 与 `removeStateListener(StateListener)`。

### 3.4 策略中立相对指针扩容（`PointerEvent`）
- **相对位移与按键掩码**：
  * 动作枚举扩容：新增 `Action.RELATIVE_MOVE`；
  * 物理按键掩码：定义 `BUTTON_PRIMARY = 1`（对应左键 BTN_LEFT / 272）、`BUTTON_SECONDARY = 2`（对应右键 BTN_RIGHT / 273）、`BUTTON_TERTIARY = 4`（对应中键 BTN_MIDDLE / 274）；
  * 相对运动工厂：`PointerEvent.relativeMotion(double deltaX, double deltaY, int buttonMask, long timestampNs)`；
  * 状态感知：`isRelativeMotion()` 对 `RELATIVE_MOVE` 确定性返回 `true`，对普通 `MOVE`、`BUTTON_DOWN`、`SCROLL` 返回 `false`；
- **序列化通道无损编解码（`InputEventSerialization`）**：
  * 序列化线协议格式：`INPUT/POINTER RELATIVE_MOVE 0.0 0.0 <deltaX> <deltaY> <buttonMask> <timestampNs>\n`；
  * 完整保持双精度浮点位移与按键掩码无损往返，100% 向前兼容现有 Wayland/Touch 输入事件。

---

## 4. TDD 节奏与测试验证（Red-Green-Refactor Evidence）

本工单严格遵循 TDD（红-绿-重构）工程规范：

### 4.1 红灯阶段（Red Phase）
在实现生产代码前，首先在 `runtime/contract/src/test/` 目录下创建 4 大契约测试套件：
1. `BridgeControlClipboardTest.java`
2. `AudioStreamContractTest.java`
3. `PointerEventRelativeMotionTest.java`
4. `FullLinuxContractAudioCapabilityTest.java`

执行 `.\gradlew.bat :runtime:contract:compileTestJava`，预期捕获 100 项符号缺失错误（`Clipboard` 符号未定义、`AUDIO_OUTPUT` 未定义、`relativeMotion` 未定义等），严格验证测试红灯。

### 4.2 绿灯阶段（Green Phase）
实施 `FullLinuxContract.java`、`BridgeControl.java`、`BridgeClient.java`、`AudioStreamContract.java`、`PointerEvent.java` 生产代码与完备 Javadoc 注释。

执行验证：
```
> Task :runtime:contract:audioStreamContractTest
AUDIO_STREAM_CONTRACT_TEST_OK
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlClipboardTest
BRIDGE_CONTROL_CLIPBOARD_TEST_OK
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeControlDisplayTest
BRIDGE_CONTROL_DISPLAY_TEST_OK
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:displayControlContractTest
DISPLAY_CONTROL_CONTRACT_TEST_OK
> Task :runtime:contract:fullLinuxContractAudioCapabilityTest
FULL_LINUX_CONTRACT_AUDIO_CAPABILITY_TEST_OK
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:inputEventSerializationTest
INPUT_EVENT_SERIALIZATION_TEST_OK
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:pointerEventRelativeMotionTest
POINTER_EVENT_RELATIVE_MOTION_TEST_OK
> Task :runtime:contract:portForwardingContractTest
PORT_FORWARDING_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:storageContractTest
STORAGE_CONTRACT_TEST_OK
> Task :runtime:contract:test
> Task :runtime:contract:check

BUILD SUCCESSFUL in 13s
18 actionable tasks: 18 executed
```

### 4.3 重构与全量回归（Refactor & Regression）
运行全量回归命令：
```cmd
.\gradlew.bat :runtime:contract:check
```
18 项任务全绿通过，编译器 `-Xlint:all -Werror` 零编译告警。

---

## 5. 交付物清单（Changed Files List）

| 文件路径 | 变更类型 | 职责与变更概述 |
| :--- | :--- | :--- |
| `runtime/contract/build.gradle.kts` | 修改 | 注册 4 项新测试任务并挂接至 `check.dependsOn` |
| `runtime/contract/src/main/java/.../FullLinuxContract.java` | 修改 | 扩容 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`，增加徽章与预设 |
| `runtime/contract/src/main/java/.../BridgeControl.java` | 修改 | 新增剪贴板线协议解析、256KB 边界拦截、Base64/UTF-8 校验与 `Clipboard` seam |
| `runtime/contract/src/main/java/.../BridgeClient.java` | 修改 | 新增 `clipboardPut` 与 `clipboardGet`，支持动态大响应行缓冲与 Base64 解码 |
| `runtime/contract/src/main/java/.../AudioStreamContract.java` | 新增 | 策略中立音频流契约接口、格式模型、状态机与数学/校验工具方法 |
| `runtime/contract/src/main/java/.../PointerEvent.java` | 修改 | 增加 `Action.RELATIVE_MOVE`、物理按键掩码与 `relativeMotion` 构造与工具方法 |
| `runtime/contract/src/test/java/.../BridgeControlClipboardTest.java` | 新增 | 验证剪贴板线协议派发、256KB 边界、超限截断、畸形防御与 Seam 回调 |
| `runtime/contract/src/test/java/.../AudioStreamContractTest.java` | 新增 | 验证音频流格式参数、数学约束、帧计算与生命周期回调 |
| `runtime/contract/src/test/java/.../PointerEventRelativeMotionTest.java` | 新增 | 验证相对位移在 `PointerEvent` 与 `InputEventSerialization` 中的编解码无损性 |
| `runtime/contract/src/test/java/.../FullLinuxContractAudioCapabilityTest.java` | 新增 | 验证扩容能力枚举后的能力矩阵合法性与徽章表达 |
| `.scratch/droidlayer-platform/issues/44-contract-audio-clipboard-and-peripherals.md` | 修改 | 勾选验收标准并更新工单状态至 `ready-for-review` |
| `docs/reports/tickets/44/implementation-report.zh-CN.md` | 新增 | 本工单中文实施与复审报告 |

---

## 6. 验收标准逐项核对表（Acceptance Criteria Verification Matrix）

| 验收标准项（AC） | 状态 | 验证依据与实施说明 |
| :--- | :---: | :--- |
| `FullLinuxContract.Capability` 扩容 `AUDIO_OUTPUT`、`CLIPBOARD_SYNC`、`POINTER_CAPTURE`，能力矩阵反映真实支持 | **PASS** | `FullLinuxContract.java` 新增枚举项，提供 `audioBadge()`、`clipboardBadge()`、`pointerCaptureBadge()` 与预设；`FullLinuxContractAudioCapabilityTest` 全绿通过。 |
| `BridgeControl` 成功集成并分发 `CLIPBOARD PUT` 与 `GET`，256KB 边界拦截与格式校验严密 | **PASS** | `BridgeControl.java` 集成命令解析，支持 256KB 精确边界，超限返回 `payload-too-large`，畸形或非法 UTF-8 返回 `malformed-command`；`BridgeControlClipboardTest` 全绿通过。 |
| `AudioStreamContract` 接口定义完备，纯 Java 17，无平台私有依赖 | **PASS** | `AudioStreamContract.java` 定义固定 48kHz 16-bit 双声道 PCM 模型、状态机与帧计算方法，纯 Java 17 标准库编写；`AudioStreamContractTest` 全绿通过。 |
| `PointerEvent` 完整支持相对位移与按键掩码序列化 | **PASS** | `PointerEvent.java` 支持 `RELATIVE_MOVE`、按键掩码与 `isRelativeMotion()`，在 `InputEventSerialization` 中无损往返；`PointerEventRelativeMotionTest` 全绿通过。 |
| 契约测试集（`:runtime:contract:check`）100% 绿灯通过 | **PASS** | 执行 `.\gradlew.bat :runtime:contract:check`，18 项任务全量绿灯通过，`-Xlint:all -Werror` 零编译告警。 |

---

## 7. 结论与就绪状态

Ticket 44（策略中立剪贴板与音频契约扩展）已 100% 完成全部开发与测试工作。
所有修改严格隔离在管辖模块 `:runtime:contract` 内，保持零平台私有依赖与策略中立铁律。
本工单已满足全部交付定义（DoD），正式进入 `ready-for-review` 状态。
