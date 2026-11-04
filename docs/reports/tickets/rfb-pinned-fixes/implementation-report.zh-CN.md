# Ticket rfb-pinned-fixes 实施报告（RFB 输入编码器钉住疑点收尾）

- 票号/标题：rfb-pinned-fixes —— `RfbInputEncoder` 三个钉住的可疑行为判定与修复
- 执行 session / 模型 / thinking：Lane G RFB 修复智能体（ZCode subagent / GLM-5.3-Flash）
- 分支：`fix/rfb-pinned-issues`　基线 commit：`f366bbf`（main）

## 1. 任务目标

收尾轮在 `RfbInputEncoderTest` 中故意钉住了三个可疑行为（当时测试绿 = 错误行为被锁定）：
① SCROLL 事件覆盖光标坐标；② CAPS/NUM LOCK 修饰位一律拒收；③ 宽/高为 0 时静默 clamp 到 (0,0)。
要求对照 RFC 6143 与调用方上下文逐条判定正确行为，最小修复实现并同步改写钉住用例，
不改既有消息类型线格式（字节布局不动）、不新增协议特性。

## 2. 逐条判定（钉住的原行为 → 判定 → 修复 → 测试证据）

### 2.1 SCROLL 事件覆盖光标坐标

- **钉住的原行为**：`pointer()` 的 else 分支对 BUTTON_DOWN/UP/SCROLL/MOVE 一律执行
  `x = clamp(event.x(), width); y = clamp(event.y(), height)`，滚轮事件携带的 x/y 会覆盖编码器
  跟踪的指针位置并写进滚轮 PointerEvent 包。钉住用例 `scrollUpSetsBit3AndFollowsWithPlainPointerPacket`
  等直接用 `scroll(3, 4, …)` 的 (3,4) 断言包坐标。
- **判定（应改为：SCROLL 不重定位指针，滚轮包携带跟踪位置）**。依据：
  - **协议（RFC 6143 §7.5.5）**：PointerEvent 每条都是对指针完整状态的断言——"The pointer is now at
    (x-position, y-position)"，没有"仅滚轮、位置无效"的表达；滚轮 = button 4/5（bit3/bit4）的
    press+release。因此滚轮包必须携带一个合法位置，问题只在"哪个位置是对的"。
  - **调用方上下文（grep 生产者）**：三个 SCROLL 生产者语义不一致——
    `GestureStateEngine.onTwoFingerMove`（runtime/contract）与 `TouchGestureMapper`（app）传引擎自己
    跟踪的虚拟光标 (cursorX, cursorY)（冗余但一致）；而 `PointerCaptureHandler.onCapturedScroll`（app，
    相对捕获滚轮路径）传**占位坐标 `(0.0, 0.0)`**。若编码器信任 SCROLL 的 x/y，该路径每次滚轮都会把
    指针瞬移到 (0,0)——调用方可感知的错误行为。
  - **取舍记录**：编码器跟踪的 (x,y) 是"线上最后一次断言的指针位置"，是唯一与 guest 实际指针状态
    保证一致的值；两个"好心"生产者传光标坐标属于冗余信息，忽略它们对调用方零改动（线格式仍合规）。
    协议与调用方预期冲突处以"线格式正确 + 调用方可感知正确"裁定：位置取跟踪值，SCROLL 的 x/y 忽略。
- **修复**：`pointer()` 中 else 分支收窄为 `else if (action != SCROLL)`；MOVE/BUTTON_DOWN/BUTTON_UP
  仍按事件坐标更新（点击携带权威落点），SCROLL 只累积 delta、滚轮包复用跟踪位置。
- **测试证据**：wheel 段 5 个既有用例改为"先 MOVE 定位再 SCROLL"；新增
  `scrollIgnoresCarriedCoordinatesAndKeepsTrackedPointerPosition` 锁定"占位坐标 (77,88) 不移动指针、
  无先前位置时滚轮包指向跟踪原点 (0,0)"；`scrollPreservesHeldButtonsInMask` 原样保留（buttonDown
  定位后滚动位置不变，恰为新语义）；渠道级 `sendsEvdevModifiersUnicodeAndPointerAsOrderedRfbPackets`
  中 scroll(5,1) 的携带坐标恰等于跟踪位置 (5,1)，改后逐字节断言不变，验证通过。

### 2.2 CAPS/NUM LOCK 位一律拒收

- **钉住的原行为**：`supports()` 的 KeyEvent 门要求 `(modifiers & ~MODIFIERS) == 0`，
  MODIFIERS 仅含 SHIFT/CTRL/ALT/SUPER；携带 `MOD_CAPS_LOCK`/`MOD_NUM_LOCK` 状态位的事件被整条拒绝。
  钉住用例 `rejectsUnmappedKeysAndIllegalModifierBits` 三条 assertFalse；渠道测试
  `RfbDisplayChannelTest` 第 340 行经公开 API 同样钉住拒收（`accept()` 返回 false、rejected 计数增长）。
- **判定（应改为：锁定位收下并忽略）**。依据：
  - **协议（RFC 6143 §7.5.4）**：KeyEvent 线格式只有 down-flag + keysym，**没有锁定状态字段**；
    锁定键本身（Caps_Lock 0xffe5 / Num_Lock 0xff7f，keycode 58/69）作为普通按键 press/release 发送，
    由 server 端翻转锁定状态——编码器已支持该路径（`mapsBoundaryKeycodes` 锁定）。
  - **正确语义**：MOD_CAPS_LOCK/MOD_NUM_LOCK 是**状态指示位**而非可同步修饰符。在线上盲目合成锁定键
    按下/抬起会在不知道 server 锁定状态时错误翻转 guest 的锁（经典 RFB 锁键失步问题）；因此唯一
    线格式正确的处理是"收下并忽略"。
  - **调用方可感知性**：一刀切拒绝意味着只要调用方上报锁定位（如 caps lock 开启时的键盘状态），
    **全部键盘输入被静默丢弃**（accept() 返回 false）——这是实际伤害，也是判定原行为错误的直接依据。
  - **取舍记录**：本修复不试图把宿主锁定状态"同步"到 guest（RFB 核心协议无 server→client 锁定状态
    反馈，同步需扩展协议，超出"不新增协议特性"边界）；若宿主锁开启而 guest 锁未开，字符大小写由
    guest 状态决定，调用方需要精确字符时应走 TextCommitEvent 或自行翻译（既有机制已覆盖）。
- **修复**：`supports()` 允许掩码扩为 `MODIFIERS | LOCK_MODIFIERS`（新增常量
  `LOCK_MODIFIERS = MOD_CAPS_LOCK | MOD_NUM_LOCK`）。编码路径零改动：`modifier()` 不映射锁定位、
  `MODIFIER_CODES` 不含锁定键，`synchronizeModifiers()` 自然忽略——线上字节与 MOD_NONE 完全一致。
- **测试证据**：`rejectsUnmappedKeysAndIllegalModifierBits` 改写为
  `acceptsLockStateBitsAndRejectsUnknownModifierBits`（三条锁定位 assertTrue；未知位 0x40/0x80 仍
  assertFalse；未映射 keycode 仍拒绝）；新增 `lockStateBitsProduceNoExtraWireFrames` 锁定
  `press(30, MOD_CAPS_LOCK)` 线上只有裸键按下帧、shift 合成不受 NUM_LOCK 位扰动；
  `RfbDisplayChannelTest` 第 340 行改为 assertTrue + `wire.key(true, 'a')` 消费编码帧。

### 2.3 宽/高为 0 时 clamp 到 (0,0)

- **钉住的原行为**：`clamp(value, size) = max(0, min(size-1.0, value))`，size=0 时结果恒为 0，
  `encode(event, 0, 0)` 静默产出指向 (0,0) 的 PointerEvent 包。
- **判定（应改为：拒绝编码，fail-fast）**。依据：
  - **协议**：RFC 6143 PointerEvent 的 x/y 是 framebuffer 内的 U16 坐标；0 尺寸 framebuffer 不是合法
    RFB 状态（本仓 `RfbProtocol.dimensions()` 对 ServerInit/DesktopSize/Raw 矩形一律拒绝 ≤0），
    不存在"按协议语义"处理 0 尺寸的合法输出——任何包都是错的。
  - **调用方上下文**：渠道内 `accept()` 以 CONNECTED 为门，`Session.width/height` 来自协商/更新时
    已过 `dimensions()` 校验的值，恒 ≥1；`encode(event, 0, 0)` 只可能是直接使用者的编程错误。
  - **取舍记录**：选择 fail-fast（IllegalArgumentException）而非静默钳制，与 `encode()` 对不支持事件
    的既有失败模式一致；guard 收窄在指针路径——键盘/文本线格式不含坐标、不消费尺寸，行为不变。
- **修复**：`pointer()` 入口对 `width < 1 || height < 1` 抛 IllegalArgumentException（带尺寸信息）。
- **测试证据**：新增 `rejectsPointerEncodingWithNonPositiveFramebufferSize`（MOVE/RELATIVE_MOVE/
  BUTTON_DOWN/SCROLL × (0,480)/(640,0)/(0,0)/(-1,100) 全部断言抛出）；
  `keyboardAndTextEncodingDoNotRequireFramebufferDimensions` 锁定键盘/文本在 (0,0) 尺寸下仍可编码，
  证明 guard 严格限定在指针路径。

## 3. 未完成 / 与预期偏差

无。三项钉住疑点全部判定并修复，线格式字节布局未动（消息类型 4/5、键值序、滚轮 bit3/4/5/6、
按钮重映射 bit0/1/2 与修复前逐字节一致），未新增协议特性。

## 4. 修改的模块

- `runtime/contract`（owned modules 范围内）：
  - `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoder.java`
    （supports 锁定位放行；pointer() SCROLL 不更新位置 + 非法尺寸 fail-fast，共 3 处小改）
  - `runtime/contract/src/test/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoderTest.java`
    （wheel 段改写 + 3 个新用例 + LOCK 位用例改写）
  - `runtime/contract/src/test/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbDisplayChannelTest.java`
    （第 340 行 LOCK 位公开 API 断言同步改写，1 行级改动）
- 未触碰 forbidden modules；docs 报告按规范入库（`git add -f`）。
- 敏感红线自查：本报告与测试新增内容均为合成线格式用例，无任何真实设备数据，不涉及设备口径。

## 5. 重要设计决策

1. **SCROLL 位置语义裁定为"编码器跟踪位置优先"**：三个生产者 x/y 语义不一致（占位 vs 跟踪值），
   跟踪位置是唯一与线上历史一致的值；对好心传坐标的生产者零影响，对占位生产者修复了指针瞬移。
2. **锁定位"收下即忽略"而非"合成同步"**：协议无锁定状态通道，合成会错误翻转 guest 锁；忽略保证
   线格式正确且输入不再被静默丢弃。锁定状态的真实同步留给按键路径（keycode 58/69 普通键）与
   调用方文本路径，不扩展协议。
3. **0 尺寸 fail-fast 而非容错**：0 尺寸不是合法 RFB 状态，渠道路径不可能触达（dimensions 校验 +
   CONNECTED 门），抛 IAE 是对直接使用者最明确的契约表达；guard 仅限指针路径，键盘/文本不受影响。

## 6. 测试

- 契约/JVM 测试（`runtime/contract` 单一 JVM 测试源集）：
  - `RfbInputEncoderTest`：48 → 52 条（改写 6 个 wheel 用例 + 1 个 LOCK 位 supports 用例，
    新增 `scrollIgnoresCarriedCoordinatesAndKeepsTrackedPointerPosition`、
    `lockStateBitsProduceNoExtraWireFrames`、`rejectsPointerEncodingWithNonPositiveFramebufferSize`、
    `keyboardAndTextEncodingDoNotRequireFramebufferDimensions`）。
  - `RfbDisplayChannelTest`：18 条，1 处断言改写（LOCK 位接受并消费裸键帧）。
  - 运行命令：`./gradlew :runtime:contract:test`
  - **结果（JUnit XML 实测汇总）：141 tests / 3 skipped / 0 failures / 0 errors**
    （基线 main 为 137 tests / 3 skipped / 0 failed；+4 = 本次新增用例数，对账一致）。
    逐类：`RfbInputEncoderTest tests=52 skipped=0 failures=0`；
    `RfbDisplayChannelTest tests=18 skipped=0 failures=0`。
- 门禁：`./gradlew :runtime:contract:check` → **BUILD SUCCESSFUL in 16s**
  （含 `-Xlint:all -Werror` 编译门与模块注册的 JavaExec 契约任务，如 `VM_HARDWARE_CONFIG_TEST_OK`）。
- 冒烟清单：本票为纯 JVM 契约修复，无真机验证项；真机行为不受影响（渠道路径 width/height 恒 ≥1）。

## 7. Benchmark / 实测数据

本票不涉及。

## 8. 风险

- **锁定位接受后的大小写语义**：若宿主 caps lock 开启而 guest 未同步，'a' 仍按 guest 状态输出
  （RFB 核心协议限制，非本票引入；调用方可走 TextCommitEvent 精确注入）。已在新用例与报告中记录。
- **SCROLL 位置语义变更的调用方影响**：依赖"scroll 顺便移动指针"这一隐含行为的调用方（若有）需改为
  显式 MOVE。已核查仓库内三个生产者：两个本就传跟踪光标（等效），一个传占位坐标（正是本修复要救的
  路径），无依赖原行为的调用方。
- **encode 契约收紧**：直接以非法尺寸调用指针编码的使用者将由静默 (0,0) 变为 IAE；仓库内唯一生产
  调用方 RfbDisplayChannel 不可能触达，风险限于未来外部直接复用（package-private，暂不可见）。

## 9. 新发现

- `TouchGestureMapper` 与 `GestureStateEngine` 是两套高度相似的两指手势状态机（app 层与 contract 层
  各一份），后续可考虑收敛；本票不自行修改，供 Orchestrator 分诊。
- `PointerEvent.scroll` 的 x/y 字段在策略无关模型中语义模糊（占位/跟踪/触点三义），可在 ADR-0017
  语境下补一行 javadoc 明确"滚轮事件坐标不承诺为指针位置"；本票未改模型类，避免越出最小修复边界。

## 10. 对后续 ticket 的影响

- 后续如实现 RFB 锁定状态同步（需协议扩展/TigerVNC 类扩展），应基于本票的
  `LOCK_MODIFIERS` 常量位收下-忽略语义演进，而不是恢复拒收。
- 若引入 PointerEvent 语义澄清（新发现 2），`scrollIgnoresCarriedCoordinatesAndKeepsTrackedPointerPosition`
  是锚定用例，改语义需同步改写该测试。

## 11. Commit

- `fix/…`（实现+测试，见下方分支提交记录）
- `docs(report): …`（本报告）

（commit hash 以分支实际提交为准，见最终回复。）

## 12. Ticket 状态

completed —— 三个钉住疑点全部按"线格式正确 + 调用方可感知"裁定并修复，141/3/0 全绿，
check 门通过，报告与 diff 一致。

报告人：Lane G RFB 修复智能体（GLM-5.3-Flash）｜ 日期：2026-11-04 ｜ 耗时：本轮 session 内完成
