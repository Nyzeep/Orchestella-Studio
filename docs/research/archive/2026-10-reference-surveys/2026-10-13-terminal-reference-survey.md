# 终端参考实现核查与品质修复

日期：2026-10-13。范围：Termux、xterm.js、xterm 控制序列规范、Windows Terminal 官方交互文档，以及 Orchestella 的终端输入、渲染、持久会话和控制通道。主仓基线为 `8d0e8c75d769ecc645ea102b90a304c8657344a1`；本次源码改动留在共享工作树，未提交、未 push。本文的上游行号来自固定 revision，主仓“修复后”行号来自本次工作树，可随后续编辑移动。

本次先读取 `AGENTS.md`、`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/` 的三份约束文档和 Host/Artifact policy，先检查未提交 diff，再发诊断和实施方案。读取限定为约束、终端及其相关契约/测试。公开资料请求只下载来源，未上传主仓内容。没有修改设备、系统设置或执行 Gradle；统一 W: 构建和真机验收由主会话负责。

## 来源与许可证

| 来源 | 固定 revision / 版本 | 许可证与边界 | 本地资料 |
| --- | --- | --- | --- |
| [Termux](https://github.com/termux/termux-app/tree/8629e632fcb95da272221be327db653fb24befe9) | `8629e632fcb95da272221be327db653fb24befe9`，2026-10-13 shallow clone | [LICENSE.md L1–6](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md#L1-L6)：仓库 GPLv3-only；列出 Android Terminal Emulator 的 Apache-2.0 代码例外。不能据此推断整个终端模块及后续修改都可直接按 Apache 引入。 | 工作根 `termux-app/`，只读分析 |
| [xterm.js](https://github.com/xtermjs/xterm.js/tree/829e85988b281f6f446272f692c2c80f236e9c04) | `829e85988b281f6f446272f692c2c80f236e9c04`，commit 日期 2026-10-12 | [MIT LICENSE](https://github.com/xtermjs/xterm.js/blob/829e85988b281f6f446272f692c2c80f236e9c04/LICENSE)；只比较行为，没有移植实现 | 外部 evidence 的 `reference/xtermjs/` |
| [xterm Control Sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) | 文档 XTerm Patch #411，更新日期 2026-08-23，2026-10-13 访问 | 协议行为参考；没有导入文档或代码，不将该网页当作代码再许可依据 | 官方网页 |
| [Windows Terminal Actions](https://learn.microsoft.com/en-us/windows/terminal/customize-settings/actions) | 网页给出的文档 revision `5b3d5ebdf35b2c5a73f6e8565ad0d8fc9e74a942`；updated_at 2025-08-21 | 本次仅引用官方文档。单独取 LICENSE 的连接被 reset，未完成许可文件核验，故不声称已核实该文档许可或批准代码复用。 | 官方网页及其固定 GitHub 文档链接 |

Orchestella 根 `LICENSE` L1–3 为 Apache-2.0。本批屏幕模型、组合键编码、IME 缓冲、持久会话修复及单 reader 路由均为依据协议与现有接口独立编写；没有大段复制 Termux GPL 代码，没有添加 Termux 依赖。未来若要引入成熟 emulator 库，应单独核对文件级版权、修改历史、NOTICE 与发行兼容性并记录决策。

## 一手证据与适用结论

| 主题 | 可核查证据 | 对本项目的结论 |
| --- | --- | --- |
| PTY 与 UTF-8 | Termux [termux.c L36–85](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/jni/termux.c#L36-L85)：ptmx/pts、IUTF8、初始 winsize、fork/setsid/stdio。 [TerminalSession L123–172](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java#L123-L172)：进程、I/O 与 waitFor 属于 session。 | Orchestella 传输是 VM guest 控制台，不能拿 Android 本地 shell 假装 guest。宿主持续解码 UTF-8，并让 emulator 状态跟随持久 session。 |
| IME | Termux [TerminalView L309–437](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L309-L437)：TYPE_NULL 默认、可选 char input、composing/commit/finish、本地 editable 与 Unicode 写入、LF→CR；L339–341 说明 IME_ACTION_NONE 的 TV 换行问题。 | 合成阶段不把候选拼音注入 shell；最终 commit 或 finish 只发送一次；editor action 和按键回车均发送 CR。保留 TYPE_NULL 并移除显式 IME_ACTION_NONE。 |
| Ctrl/Alt/Shift 与 extra keys | Termux [TerminalView L792–839](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L792-L839)：硬件修饰键参与 special-key 编码和基字符获取；[TerminalExtraKeys L54–70](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/terminal/io/TerminalExtraKeys.java#L54-L70)：辅助键走同 terminal 输入。 | 不允许辅助键绕过黏滞状态或硬件键丢掉 Alt+Ctrl。Tab 为真实 0x09；Shift+Tab、修饰箭头和 F1–F12 为标准序列。 |
| 滚动与选择 | Termux [TerminalView L459–498](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L459-L498) 在选择时保持位置；[L575–589](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L575-L589) 区分主屏历史、备用屏箭头滚动和 mouse tracking。 | 用户翻历史或选择文本时不强制滚底，不因光标闪烁重建文本。当前实现保留 Android 原生选择/复制；备用屏手势转箭头与鼠标协议仍是后续增强项。 |
| 粘贴 | Termux [TerminalEmulator L3540–3551](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java#L3540-L3551)；xterm.js [Clipboard.ts L12–28、53–57](https://github.com/xtermjs/xterm.js/blob/829e85988b281f6f446272f692c2c80f236e9c04/src/browser/Clipboard.ts#L12-L28)：换行规范化、按 mode 2004 包围粘贴、避免 ESC 提前结束粘贴。 | 辅助键、系统 context action、Ctrl+Shift+V 共用 paste 路径；粘贴不套用 latched Ctrl/Alt，不做本地 echo，guest DECSET 2004 生效时才加帧。 |
| 光标/erase/备用屏 | xterm.js [InputHandler.ts L215–251](https://github.com/xtermjs/xterm.js/blob/829e85988b281f6f446272f692c2c80f236e9c04/src/common/InputHandler.ts#L215-L251)、L1229/1324/1350/1383、L2014–2032、L2260–2281、L2890；[BufferSet.ts L82–126](https://github.com/xtermjs/xterm.js/blob/829e85988b281f6f446272f692c2c80f236e9c04/src/common/buffer/BufferSet.ts#L82-L126)。xterm 规范 ED/EL/CUP/DECSTBM/1049/2004。 | VT 是可定位的二维屏幕状态，不能把 CSI H/K/A 等直接剥除后拼成日志。主屏历史、备用屏内容及光标必须独立维护。 |
| resize | Termux [TerminalView L985–995](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L985-L995) 测 grid 并更新 session；[TerminalSession L102–109](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java#L102-L109) 同时更新 PTY 与 emulator。 | IME、字体、方向或 viewport 变化后都更新模型并经既有鉴权 Bridge 发 cols/rows；尺寸扣掉文字 padding、字距及真实行距。 |
| 生命周期/退出 | Termux TerminalSession L166–172 waitFor、L245–249 cleanup、L350–364 退出描述；窗口列表区分选中 session 与运行状态。 | 离开 UI 不终止 shell；EOF/写失败禁用输入并切换恢复状态，不把关闭输出视图当作杀 shell。 |
| 快捷键与设置 | Termux TermuxPropertyConstants L110–121、L194–202、L251、L274–281、L327–332：hardware shortcuts、IME、光标、历史与 extra keys 为独立设置。Windows Actions 文档“Clipboard integration / Unbind keys”分别定义复制粘贴与底层应用快捷键。 | Ctrl+C 保留 SIGINT，Ctrl+V 保留 guest 行为；明确 Ctrl+Shift+C/V 作终端复制/粘贴。现有字体/字距/行距/光标偏好通过监听即时生效，不修改 SettingsActivity 或 ThemeStore。 |

Windows 文档的引用位置是固定 revision [actions.md](https://github.com/MicrosoftDocs/terminal/blob/5b3d5ebdf35b2c5a73f6e8565ad0d8fc9e74a942/TerminalDocs/customize-settings/actions.md) 的命名章节；本次原始文件下载失败，因此没有伪造行号。上述 Termux/xterm.js 文件的固定行号已在本地源码/下载物以 `rg -n` 核对。

## 主仓真实不足与已落实修复

| 基线证据 | 已落实行为及当前证据 |
| --- | --- |
| `ModernTerminalView.java` L154 禁用选择，L174/L318/L456 无条件滚底；L196–257 闪烁时重建 TextView。 | `ModernTerminalView` 使用原生文本选择；选区活动时只推进模型、保持呈现不变；离开底部停止 follow；光标在 `TranscriptView.onDraw` 绘制，只 invalidate。当前 L208 起初始化，L239 起 selection callback、L268 grid、L306 render、L415 onDraw。 |
| `AnsiEscapeParser.java` 基线 L106 剥除非 SGR CSI；旧 View 把 CR 当删除整行、BS 当删除上一个 char。 | 新 `terminal/TerminalScreenModel.java` 实现逐单元格写入、延迟自动换行、CUP/HVP/CHA/VPA、相对移动、ED/EL/ECH、ICH/DCH、IL/DL、DECSTBM、滚动/反向滚动、save/restore、DEC 1/6/7/25/47/1047/1048/1049/2004。主屏 bounded scrollback，备用屏不进入历史。CJK/emoji 与 combining 做基本列宽处理，不拆 surrogate。 |
| `TerminalInputConnection.java` 基线 L79–87 用互斥分支处理 Ctrl/Alt，L238–260 限 char 且特殊键提前 return；L311 忽略 composing。 | `TerminalInputConnection` L46 modes、L57 paste、L196 special-key 编码、L282 composing、L294 finish；Ctrl 与 Alt 组合，Alt+Unicode 完整 UTF-8，所有常规辅助键共用 same encoder/consume。硬件 dispatch 被处理后不再走 super 双发，也不依赖先显示 IME。 |
| `ImeBridgeInputConnection` 基线 L90–93 以400个 UTF-16单位分包，可能从 emoji 中间切断；失败继续发后续包。 | 新 helper `terminal/ImeCompositionBuffer` 只保留未提交 IME 文本；图形 IME 分包避开 surrogate边界，失败即停；合成态删除先在本地消耗，剩余才发 guest key。 |
| `PersistentTerminalSessionPool` 基线 L73 每次 read 独立 `new String(... UTF_8)`。 | 持续 `InputStreamReader` L87；holder 自己持有 emulator L36–40，pump L99 是唯一 raw→VT 解析者，DSR/DA通过既有terminal流回答；UI L540绑定该模型，恢复不重放截断 raw。raw ring仅日志/历史摘要。EOF callback 注册晚于EOF也能收到，死流写入被拒绝。 |
| `TerminalActivity` 基线 L563 attach 后 clear/replay；L780 在异步执行时读取可变 instanceId；L143 初始化本地命令猜测补全。 | listener代际绑定，后台回调统一post，旧实例callback失效；输入在入队时捕获holder和bytes。活动页不再初始化本地行编辑/假prompt/假completion；Tab直接到guest。输出视图切换仍共享同一真实Shell，drawer只对选中项标“当前”，关闭说明Shell继续运行。 |
| `BridgeConnector.java` 基线 L114另建Client，L167直接读control stream；QEMU/AVF Session另有缓存BridgeClient，导致resize/power/clipboard并发争读。 | `BridgeConnector.serveSession` 复用 session 缓存Client；`BridgeClient.startResponsePump` 是唯一reader，按类型把guest请求和等待中的host响应路由。请求序列化、write锁、启动HELLO默认3900秒/普通请求5秒的分别有界超时、EOF唤醒、响应失配拒绝；超时冻结transport，避免迟响应被误认作下一请求。生产泵模式未鉴权时不发送非HELLO请求。旧同步contract API保留供原测试。 |
| 旧 resize 只有启动时测量，且没有扣 padding/字距/行距。 | View grid→150ms debounce→相同ready Client.resize。已核对未修改的 `guest-assets/agent/guest-agent.c` L116–129 parse，L131–147打开terminal_device并于L144执行TIOCSWINSZ，鉴权loop L342–349返回OK/resize-unavailable。不新增协议暴露面，不启动第二reader。 |

真实终端页面是 `TerminalActivity` 程序创建；`layout/activity_terminal.xml` 实际不存在，未新增无效XML。辅助键为固定第一行 ESC/TAB/CTRL/ALT，第二行固定48dp箭头及横向滚动工具/符号/F1–F12，避免原单长行将箭头藏到末尾。新增“命令→发送命令”标准多行对话框用于明确批量输入，确认后通过同PTY队列发送并回车；它不模拟prompt或本地echo。

## 验证与交付

已在现有JDK17、缓存JUnit4及Android36接口jar上执行纯JVM测试；没有调用Gradle、安装依赖或修改设备。

- 110项JUnit通过：46项VT/语义模型、8项viewport状态、23项终端输入、11项Sticky、8项持久Pool、14项图形IME。覆盖top反复定位重绘、vim备用屏进入/退出、滚动区域、擦除/插删、resize/cursor、CJK/emoji逐字符与UTF-8逐byte分包、composition、组合修饰、真实Tab、paste、EOF与attach。
- 新持久状态回归使用64字符raw ring，后台50帧淘汰1049h后仍保留alternate/application cursor、光标及主屏；UI未attach时DSR仍回PTY。
- 既有 `BridgeClientContractTest.main` 通过；`BridgeClientResponsePumpTest.main` 24场景通过：guest请求夹在resize响应之前、超时冻结、断线唤醒、响应失配、未鉴权零发送、并发resize/power序列化、调用方销毁不打断在途resize、超长帧尾零副作用、desktop先进入后terminal复用同鉴权reader、启动HELLO与普通请求分离deadline、初次HELLO丢失后的有限重发、已发送HELLO预算内的重复READY不应答ping、未重发时额外READY拒绝、重发总deadline与token不进入诊断，以及恢复agent切换native后的新鉴权、cached token拒绝清引用/阻止POWER、有效格式的错误新token不可复用READY、普通reauth丢HELLO短期重试、普通reauth静默不进入3900秒期限、旧READY不能绕过当前agent鉴权、全部11类控制操作共用同客户端鉴权。
- Bash实际执行8项检查通过：当前提交history、ignorespace排除、UTF-8、多行PS2、HISTTIMEFORMAT排除、退出码、PROMPT_COMMAND数组保留、重复source不套娃；探针使用`HISTFILE=/dev/null`。结果`terminal/shell-markers.json`及保全副本。
- scoped `git diff --check` 通过。Android联合编译/APK、真机IME与full-screen程序验收由主会话统一记录，不在此把JVM代替设备验收。

可复跑脚本：`Orchestella/artifacts/quality-20261013/terminal/run-terminal-jvm-tests.py`。原始结果：同目录 `jvm-regression.log.txt`，即时保全副本 `preserved/jvm-regression.log.txt`；编译输出 `classes/`、公开来源 `reference/` 均是外部项目产物，未放入源码模块或Git。owner为本终端worker；研究源码保留供复核，classes/一次性脚本可在本批验收后由主会话清理，测试证据保留。不创建 `.log` 扩展名证据。

## 独立审查与真机反馈的收敛修复

本节记录主会话独立review/设备反馈之后实际修复的边界，避免把初版测试通过等同于全部实现正确。

| 发现 | 修复与验证 |
| --- | --- |
| 持久holder虽然保留raw ring，但若UI恢复仍reset并重放截断流，长期vim/top会失去alternate/SGR/cursor；后台DSR无人回答。 | holder持续解析且是唯一VT写入点，UI只绑定snapshot；64字符ring+50次后台重绘淘汰1049h后的mode/cursor/DSR/主屏恢复回归通过。 |
| Activity `resizeExecutor.shutdownNow()`会interrupt共享Client等待中的resize，进而冻结持续control流。 | 销毁改graceful `shutdown()`，待执行任务检查bindingGeneration/session，UI resize callback移除。在途resize延迟ACK→调用方queue销毁→后续ping仍成功的回归通过。 |
| 达到512KiB行限后返回空字符串会继续解析同一行尾部，允许尾部伪OPEN-URL被当作新帧。 | 直接IOException终止pump，不继续解析；测试要求handler零effects、ready关闭、后续请求失败。外部旧语义variant执行 `prove-oversized-regression.py` 显示RED：`oversized frame tail executed a guest action`；production GREEN。证据 `oversized-negative.log.txt` 及即时保全副本。 |
| bounded history满2000行后行数不变，不能靠行数差计算阅读位置。 | Snapshot携带累计`evictedHistoryRows`，View按差减去对应像素行高；满环追加100行后仍能以调整后的index找到同一阅读行，回归通过。真正被淘汰的文本不能恢复。 |
| Desktop先进入仍调用旧serveStreams另建client，cachedClient永不ready且与其争读，Terminal后进入也不再handshake。 | 最小改`DesktopActivity`桥接启动为同`serveSession`，显示协商以bounded awaitReady等待共享鉴权client；desktop→terminal→resize/power回归记录只有一个读取Thread。生产`serveStreams`调用已移除，仅旧API定义和测试保留。 |
| 真机首次Ubuntu安装在5秒后尚在mkfs/解包，普通request超时错误地冻结初始HELLO。 | 生产HELLO启动deadline3900秒，常规5秒；测试注入短deadline，HELLO延迟200ms超过普通100ms仍成功，然后resize仍按100ms超时。等待只在后台执行，不隐式重试，deadline后失败即停。 |
| 真机恢复控制台初次输入观察到仅首字符，但之后整串bulk命令正常回显/执行。 | 主会话保全`ubuntu-serial-bulk-test.log.txt`；现有QEMU UART/chardev具备FIFO容量背压，证据不足以把初次观察归为恒定burst丢字。未添加传输pacing，也未保留新rawInput诊断钩子；AVF不降速。 |
| 真机native Ubuntu中agent早于agetty启动，早期resize成功后`stty size`仍为24×80。 | 主会话probe证实systemd/cgroup2/DBus/SSH/DNS运行；这与guest agetty后续重设winsize相符。`TerminalActivity.onResume`每次前台恢复重发当前grid，同holder重入不再early-return跳过；字体/IME仍经尺寸listener。现有OK resize只表示当次ioctl成功，没有尺寸readback，未添加永久轮询；修复后实际尺寸由主会话最新APK再次probe，不在此声称已验证42列。 |
| 真机辅助栏第一行仅显示巨大的ESC，其他常用键在屏外。 | 外层HorizontalScrollView对子布局给无限宽，权重失效；改外层LinearLayout.VERTICAL，仅第二行工具区域内部横滚，第一行4等权受真实viewport宽度约束。此几何修复交主会话后续APK截图核验。 |

后续最小授权范围新增`DesktopActivity`仅桥接启动/awaitReady片段；未修改Wayland surface、音频、主题或其他桌面UI。没有修改`ExecutionSession`、QEMU/AVF终止方法，主会话负责真实VM终止。

## 后续实机收敛与真实命令分区增量

主会话后续反馈浮动会话入口遮盖约108dp×48dp的真实输出；`TerminalActivity`现使用独立40dp标题栏，左侧当前真实视图标题、右侧“1个输出视图”打开既有drawer，正文FrameLayout内只保留VT与离线卡，不再叠会话按钮。标题占用的高度由View尺寸回调自然计入rows。四个常用键外层纵向布局和标题栏/当前semantic UI均以现有Android36接口和编译输出执行独立javac，EXIT0，证据`ui-header-compile.log.txt`。

主会话probe确认native Ubuntu的systemd/cgroup2/DBus/SSH/DNS及持久文件真实运行，但control曾长期未ready、winsize仍24×80；guest agent第一次启动exit1后重启说明单次早HELLO可能丢失。已与guestworker协调现有协议同token HELLO幂等验证，**必须与新guest agent配套**：宿主仅在握手pending期间每1秒重发HELLO，总3900秒有界，READY后立即停止；唯一reader按实际HELLO发送预算消耗迟到READY，绝不将其当作PING/RESIZE响应。普通请求仍一发、5秒失败即停。新增脱敏状态/attempt/cols/rows/accepted日志，不输出token或请求正文。guest幂等修复由其他worker持有，未在终端worker报告伪称自己修改agent。旧guest在鉴权后会拒绝重复HELLO，因此不允许只升级宿主重发而沿用旧guest。

用户追加要求已提交命令保留、高对比以及命令/输出空行。为保持真实shell边界，本批新增`guest-assets/shell/20-orchestella-terminal-integration.sh`与`terminal/ShellCommandTranscript.java`：

- 官方语义基于[iTerm2 Semantic Prompts文档](https://iterm2.com/documentation-escape-codes.html)的OSC133 A=prompt起点、B=input起点、C=output起点、D=完成/取消。该官方文档是访问时的网页，无已核实固定源码revision/许可证，只有行为参考、未导入代码。
- `A;orchestella=1`、`C;cmd=<base64>`、`B;continuation=1/2`是本项目明确的扩展，**不是**iTerm2标准既有字段。只在明确opt-in A后启用显示分区；原VT解析和位置报告保持原网格。
- Bash-only交互脚本在真实prompt hook后加载，保留原PROMPT_COMMAND/PS0/PS1/PS2行为；提交payload来自`history 1`的新条目，HISTCMD不新增则只发边界，不从上一条history或Android输入推断。HISTCONTROL/HISTIGNORE/关闭history排除项不归档。提示符身份来自guest USER/LOGNAME/id与HOSTNAME/hostname，不伪造宿主用户。PS2原内容外包明确范围标记，避免将普通`>`/`❯`输出误分类为命令，也避免多行命令重复展示echo。
- C payload限8192字节，strict UTF-8+base64，拒绝ESC/C0（仅允许LF/Tab）/C1；未知、超长OSC无动作。命令只作为本地display data，没有执行、出站、同步或另存裸未提交输入。
- normal屏幕显示最多32块/64000字符的提交快照，命令与输出及相邻块用空行分隔；提交历史不受普通CR/EL/输出区域ED重绘擦除；用户后续明确要求的显式清屏/RIS采用新的可见显示epoch（见末节），不删除Shell历史或日志。command高亮背景采用theme foreground，墨色按亮度选黑/白，测试light/dark至少4.5:1。真正bounded eviction仍允许淘汰旧块，并保持累计行锚点。
- alternate屏幕直接返回原VT snapshot，编辑器不插空行、不强制command色；退出后normal块恢复，编辑器像素不存为命令输出。连续echo、空命令/CtrlC、PS2、hidden read、背景stdout、literal `>`/`❯`、clear/reset、emoji、恶意marker、bounded历史、OSC title/52等均有回归。

安装seam：新文件放`/etc/orchestella/shell/20-orchestella-terminal-integration.sh` mode0644；00环境脚本在prompt加载后source；两个prompt源码只追加真实guest身份前缀。生产init内嵌副本与base initramfs装配由guestworker协调，终端worker没有编辑init或包装guest二进制。

宿主例外纠正：首次本地Bash history索引探针没有设置HISTFILE，可能触发工具默认用户`.bash_history`读写。没有读取或输出既有历史内容、没有清理用户文件；后续全部probe固定`HISTFILE=/dev/null`。结果仅合成测试文本。该例外已经即时告知主会话，不把它当作今后写用户目录的授权。

## 配套APK实图核对与长命令显示修复

主会话实际配套APK反馈HELLO于attempt29 ready、resize41×35已应答，IME打开后`stty size`为16×41。终端worker读取本批`screenshots/terminal-command-separation.png`、`terminal-top.png`、`terminal-nano-reentry.png`三张原图：命令分区/输出空行、固定标题栏和全屏重入可见；第一条长命令在右缘停于`outpu…`，下一行仅有原字符串中的引号，确认显示裁切。

原因是`ShellCommandTranscript`直接拼接权威command文本，未把超过41列的记录生成显示换行，而TextView为保持二维VT网格已关闭Android二次折行。修复只在normal语义快照按与VT相同的`characterWidth`规则折行，计入prompt已占列，保留全部字符和高亮，映射新增换行后的UTF-16光标offset；immutable存储仍为原command，因此resize可重新布局而不积累软换行。历史淘汰锚点计入这些显示行。alternate依然直接返回原网格，未修改View、Activity、guest或控制协议。

新增5个回归首先全部RED，证据`terminal/command-wrap-red.log.txt`及即时保全副本；修复后95项JUnit/14项Bridge均GREEN。覆盖实图同构41列命令、无空格长token与缩宽/恢复、CJK/emoji/combining不拆字符、光标映射、alternate行数/内容/位置不受折行影响。截图是修复前实图，修复后的APK截图由主会话继续核验；此处不把模型回归当作已完成修复后实机验收。

## recovery → native 控制鉴权生命周期

主会话后续实机发现：init/recovery agent先完成HELLO，而原生systemd启动时旧agent停止，新agent在相同打开的virtio控制流上重新等待HELLO。宿主cached `ready=true`只证明旧agent曾鉴权，不能证明新agent上下文；旧实现在这一阶段直接RESIZE/POWER，会被新agent按malformed-handshake拒绝。

新增精确fixture先复现RED：初HELLO→READY、模拟server切换回未鉴权状态、同client.resize直接发送被拒绝；证据`terminal/native-reauth-red.log.txt`及即时保全副本。修复仅`BridgeClient.java`和`BridgeClientResponsePumpTest.java`：

- 初次成功鉴权的token只在客户端私有内存字段保留，不落盘、不公开getter、不写日志或异常请求正文；失败清除该引用。Java String不能保证物理内存擦除，此处承诺的是不持久化和终止保留引用。
- 生产response-pump模式下每个非HELLO控制操作都在同public method同步锁内执行HELLO、PING确认、实际request。重新鉴权及真实request共享普通5秒总deadline，只有HELLO可按既有1秒间隔有限重发；不会使用初始3900秒启动期限，也不重发可能有副作用的POWER等request。
- 协议v1的READY没有请求ID，早期重复HELLO可能丢失或留下迟到READY，不能要求耗尽旧预算，也不能把旧READY当新agent身份确认。新HELLO之后的既有PING/PONG构成串行确认：迟READY仍只按实际HELLO发送预算消耗，只有PONG证明当前guest已读过新的HELLO。错误token或无法确认PONG时，不发送实际request，关闭错位/失败的transport，ready清除。
- 唯一reader和已有session缓存Client保持不变；没有新协议、没有让原生未鉴权agent直接接受control commands、没有改实例metadata。重新鉴权期间`isReady()`暂为false，防止guest主动动作在身份未确认时通过；完成后恢复。
- 原始同步协议fixture保留单步 API 与旧测试，用于直接验证输入/输出wire；生产Terminal/Desktop入口均使用session response pump，享受命令前自动reauth。外部实机driver的直接协议连接必须使用相同已鉴权/幂等HELLO约定。

新增7个场景及更新后的原14场景均GREEN，共21项；全终端95项JUnit保持GREEN。新测试覆盖server重启后成功resize、错误token阻POWER与清reference、主动错误token不能复用旧READY、短期丢HELLO恢复、普通deadline失败不发实际命令、旧READY后PINGunauthorized阻止控制，以及PING/RESIZE/POWER/NOTIFY/OPEN-URL/DISPLAY/CLIPBOARD等11类操作每次都验证同reader/同鉴权事务。主会话最终APK/POWER实机和全项目lint结果另外记录，不能把本轮fixture通过当作已确认实际关机完成。

## 大镜像首次装配的启动等待预算

主会话真实Arch输入约829MB，在独立3GB VM中校验需要5–8分钟，校验和解包阶段agent尚未启动，总首次装配可能20–30分钟。此前15分钟默认握手期限不足以覆盖正常冷启动。按用户明确要求，生产默认首次等待统一为`BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS = 3_900_000L`：校验1800秒、解包1800秒、启动预留300秒，总65分钟有界。字段初值、默认response pump和Desktop awaitReady复用同一常量，不按发行版增加新接口。

只有初次握手采用该预算；命令前重新鉴权、PING确认和真实request仍共享普通5秒总期限，错误令牌立即拒绝，HELLO间隔仍为1秒且在总期限后停止，单reader不变。短期限测试注入接口保持原样，不需要等待65分钟运行fixture。页面继续显示既有启动状态和真实guest进度，未改正在运行的QA进程、VM或根盘；外部driver已启动的900秒期限无法由这份源码更新自动改变，需其owner另行安排参数与后续验证。

## 首次控制同步、清屏与阅读位置的最终增量

最新实机READY attempt161后首控制恰好5秒失败，而该boot guest agent NRestarts=0。当前C实现逐字节读取control line、poll并按序应答HELLO；161条启动请求的后续READY会排在新PING前。压力回放先RED，定位`reauth_ping`超时（`startup-backlog-red.log.txt`），随后仅首个控制事务增加30秒有界settle等待，成功或失败后消耗一次，后续事务仍5秒，初始装配仍3900秒。没有固定sleep或新协议。现有短期测试注入默认保持原严格期限；额外参数用于缩短settle回归。

诊断现在只输出内部固定`stage`和`causeCode`：startup_hello/reauth_hello/reauth_ping/request/reader，以及timeout/eof/expected_mismatch/unexpected_ready/malformed_response/oversized_frame/auth_rejected/io等，不拼接guest正文、令牌或foreign异常消息；首失败只记录一次。真实当前C源码的Windows字符流fixture也通过：165条HELLO，首事务463ms超过普通100ms测试期限后完成PONG，下一普通PING继续成功（`real-c-agent-backlog.log.txt`）。该fixture验证实际C鉴权/字符流语义，不替代ARM virtio/poll性能和下一实机ACK。

用户进一步要求clear真正生效与阅读不自动跳顶。首先复现normal语义记录忽略HOME+2J+3J/Readline清屏的两个RED（`semantic-clear-red.log.txt`）；现显式normal清屏/RIS开启可见显示epoch，隐藏旧boot/命令块，保留Shell历史、原始日志和metadata。输入prompt阶段HOME+2J处理Readline Ctrl+L；正常输出区域单独2J不会误删已提交块，alternate擦除不影响primary记录。辅助栏首排第五键CLEAR直接清可见显示并发送0x0C请求真实程序重绘，不发送字面`clear`命令，不套用Sticky Alt。

`TerminalScrollState`明确FOLLOWING/READING意图。touch/selection期间只更新持久模型，呈现冻结；结束后保留阅读锚点，bounded eviction只按真实淘汰行修正。TextView重建期间捕获一次锚点、布局完成后恢复；requestChildFocus/rectangle不再决定terminal viewport，不再用fullScroll抢夺子控件focus。8项状态回归通过，但实际触摸惯性/IME/高输出性能由主会话设备复评。

安全control恢复接入仅每次显式onStart后台一次：持有当前session/binding/foreground代际，只有failed客户端才调用其他worker的`recoverControlChannel()`，成功后重新单reader握手，失败显示控制不可用且Shell字节流继续；没有定时无限重试或改terminal输入输出。旧serve回调与captured Client identity绑定，防同session恢复后的旧失败覆盖新READY。`awaitDisconnectedReader`仅failed状态、<=5秒等待、拒绝reader自己join且interrupt传播；QEMU控制transport/cache重置及AVF拒绝恢复由transportworker持有，不算本worker修改。

最终106项JUnit、24项Bridge、旧同步contract全部GREEN；终端UI与只读transport接线独立javac EXIT0，证据`clear-scroll-recovery-compile.log.txt`。生产源码与测试一起封板后交主会话统一全App/contract/lint/实机清屏、滚动、resize和POWER验证，未操作设备或正在装配的根盘。

后续主会话真实raw trace确认vt220 clear采用`OSC133 C → ESC[H ESC[J AFTER_REAL_CLEAR`，并不发2J/3J。新增精确序列和prompt Ctrl+L HOME+J测试先RED（`real-home-j0-clear-red.log.txt`），现primary绝对左上角的默认/显式ED0也开启可见epoch，隐藏旧命令块并正常呈现AFTER_REAL_CLEAR/新prompt。不根据`clear`文本猜测、不改TERM；非HOME部分ED0、正常输出HOME+2J重绘和alternate HOME+J不触发primary语义清理。新增4测试后110项JUnit/24项Bridge GREEN；生产只变更模型的一处ED0分支，日志/Shell历史不变，修复后实机验收仍由主会话完成。

## 实际限制与后续边界

1. 当前VT集合覆盖常规readline/top/vim序列，不能宣称完整xterm兼容。Sixel/Kitty图形、DEC选择性erase、可编程tabstop、鼠标协议、8-bit C1完整解析未实现；备用屏触摸滚动尚未翻译为箭头。普通箭头/页键可交互。
2. resize保留可见内容与光标，但尚无Termux级完整历史软换行reflow；Android TextView仍负责实际glyph排版，CJK/emoji宽度为基本范围判断，复杂ZWJ/grapheme/字体fallback可能不精确。当前关闭TextView二次自动折行，避免常规ASCII网格失配。
3. 选择期间冻结显示以保持Android选区offset；后台仍更新有界模型，结束选择后呈现最新内容。巨大历史下选择/复制和快速输出仍需真机性能验收。
4. 本实例提供一个真实guest shell；多个drawer条目是共享shell的输出视图，不是新建PTY。已有multiplexer编解码没有生产guest路由支持，本批未假装独立终端已交付。
5. raw日志可能截断，但不再用于恢复live VT状态。进程级重启后的guest进程/transport恢复仍受现有实例恢复机制约束；本批持久性覆盖同App进程内UI停止/重建与后台输出。
6. 除已授权且配套guest幂等的启动HELLO与命令前短期reauth HELLO有限重发外，Bridge超时/协议失配失败即停，不复用可能错位的流；控制功能不可用时日志明确失败，terminal字节流可继续。没有把metadata RUNNING当作Linux桌面或Bridge-ready。
7. 语义命令块当前仅Bash opt-in有效；其他shell保持原VT，不猜prompt。提交前异步stdout与readline echo来自同PTY，OSC133无法标识每个编辑中字符的来源；临时input pane尊重原VT，C权威提交后才归档/分离。不能宣称编辑期间背景输出逐字节完全隔离。没有echo的密码没有Android输入归档路径；但用户主动在命令参数中输入秘密时，guest Bash的history规则仍决定是否保留，host没有额外凭空识别秘密能力。

本批没有修改MainActivity、SettingsActivity、主题定义、build.gradle或native。用户后续明确授权新增guest shell integration，仅修改`guest-assets/shell/*`；init/agent/guest二进制由其他worker负责。主会话另行处理真实VM终止及全项目品质工作，其改动不归本报告的worker文件清单。
