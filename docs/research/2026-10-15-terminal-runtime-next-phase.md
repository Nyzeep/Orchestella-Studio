# 研究报告：v0.4.2 终端与运行时下一阶段的有限优化

- 日期：2026-10-15。
- 触发：下一阶段背景研究与代码风险调查，consolidation-20261015。
- 执行者：terminal-runtime research worker / GPT-6.1-sol。
- 研究对象：用户指定活动仓 `Orchestella_merge_dev` 的原 v0.4.2 行为边界。当前源码为多人协作工作区，不声称对应某个经 Git 核验的固定提交；本 worker 未读取或操作 refs、历史、worktree 管理状态。
- 权限：生产只读；仅新增本文及用户指定外部 research-runtime 证据。未修改产品代码、测试、Manifest、构建、ADR、policy、实例根盘或设备。
- 验证级别：静态代码核查、既有报告核对、一手资料研究。未运行 Gradle、JVM/Android 测试、ADB、模拟器、QEMU、AVF、guest 命令或包管理器。下面的测试均是下一阶段验收设计，不是本轮已通过结果。

## 1. 推荐结论与真实基线

下一轮应围绕“能安静、正确地输入命令，离开再回来仍能知道发生了什么”做有限优化。推荐先做 S1 的项目自有日志路由和 S3 的退出/恢复一致性；有设备或 Android 生命周期测试环境后，再做 S2 输入边界、S4 向导草稿恢复。S5 是这些改动的共同兼容性门，不作为扩发行版、重写运行时或承诺 GPU 的新项目。

| Slice | 用户可见价值 | 证据性质 | 建议优先级与停止条件 |
| --- | --- | --- | --- |
| S1 项目自有 provisioning 日志单路化 | Shell 输入行和全屏程序减少被后台装配进度打断，失败仍可诊断 | 直接代码证实多个写入路径；实际每条消息来源/重复次数待 trace | P1；先只改自有 service/report 路由，不关闭 audit，不更换终端传输 |
| S2 输入边界与 connection 重绑验收 | 物理键盘 AltGr、死键、中文候选、回车及粘贴有明确字节语义 | AltGr/死键存在静态行为缺口；真实 IME 重绑损失/重复尚属假设 | P1；先可复现回归，再局部编码/绑定修复，不全局改输入模式或限速 |
| S3 异常退出与冷恢复一致性 | 正常停机、意外终止、控制不可用可区分；回前台不接到旧流 | 退出码被丢弃、旧 holder 判断与恢复替身 handle 的代码证据 | P1；保留单 reader/鉴权，不引入独立存活 VM 或快照恢复 |
| S4 系统重建后的向导草稿恢复 | 旋转/系统亮暗切换后步骤、配置、摘要保持一致 | 自定义模型未显式保存是静态事实；控件具体丢失范围待生命周期实验 | P2 小切片；保存草稿，不重放下载/创建，不绕过资源与授权检查 |
| S5 有限发行版与硬件兼容门 | 推荐配置、可用性标签与实际已测范围一致，失败可解释 | 现有公式/预检与启动门存在不同含义；设备端仍需验证 | P1 验收工作，P2 局部体验；不新增支持承诺、不批量换包 |

### 1.1 必须保留的已完成修复

活动仓 `README.md` 的 v0.4.2 更新和“当前版本验证范围”说明：Fedora 43、Debian 13 已有实例在 ARM64 Android / QEMU 路径完成命令输入、前后台切换及无重复登录回归；当前设备未授权 AVF。这是既有验收范围，本轮没有重新执行，不能扩写成七发行版、全部 IME、全部 OEM、AVF 或 GPU 已验证。

`guest-assets/initramfs/init` L98–145 已建立单一 `orchestella-console.service`，按 `console=` 选择 ttyAMA0/hvc0，并 mask console-getty 与这两个 serial-getty 实例；不再把“重复 getty”列为未修缺陷。`TerminalScreenModel` 的协议应答回路及主题相关修复也不能重新包装为新工作。

既有终端报告 `2026-10-13-terminal-reference-survey.md` 的限制仍有价值：一个真实 guest shell、多个共享输入的输出视图；进程内 holder 持续保存 VT 状态，raw ring 不是恢复 live VT 的依据；普通 shell/全屏程序并不等于完整 xterm、复杂字素和所有输入法完全兼容。`TerminalActivity.java` L213–223、L987–1025 已对共享 shell 做说明；不建议把输出视图改名为“独立 shell 已交付”。

背景与路线文档中的“全机型”“60 FPS”“近原生”等历史表述不是本轮实测事实。AVF CPU 虚拟化、guest GPU 设备、宿主渲染后端和 UI 上屏需要分别验证。[Q1][Q2][V1][V2]

### 1.2 本报告证据用语

- **静态已证实**：源码存在明确分支/写入路径或未保存的字段；不是“已在设备重现”的同义词。
- **既有已验收**：引用 v0.4.2 README/历史报告，注明其设备与场景边界。
- **需实验**：代码风险可能造成的用户现象，尚未取得该现象的运行证明。
- **未运行**：本轮所有构建、设备、guest、性能与恢复测试。不能拿官方资料或 mock 通过替代真实设备结果。

## 2. S1：降低项目自有控制台噪音，保留可诊断性

### 当前证据与原因链

| 位置 | 已核实事实及含义 |
| --- | --- |
| `guest-assets/initramfs/runtime-functions.sh` L298–317；内嵌副本 `init` L718 起、L729–730 | `orchestella-firstboot.service` 明确使用 `StandardOutput=journal+console`、`StandardError=journal+console`。systemd 会把这些输出额外复制到系统 console。[S1] |
| `guest-assets/scripts/orchestella-guest-provision.sh` L16–25、L54–72 | 状态有持久 state 与 `orchestella-provision.log.txt`；`report()` 还同时打印 stdout、直接写 `/dev/console`。即使只把 unit 改成 journal，直接写 console 仍存在。 |
| 同脚本 L112–136 | 包管理器等详细 stdout/stderr 已写专门日志；适合优先调整的是项目状态报告与 unit 路由，不需要把所有诊断删除。 |
| `runtime/engine-qemu/.../QemuLaunchSpec.java` L30–32、L547–565 | kernel console 和终端都使用 ttyAMA0，宿主终端来自同一个 serial chardev。因此 kernel/PID1/自有 service 的 console 输出可能进入同一 VT。 |
| `runtime-functions.sh` L324–338 | 登录时已有 pending/failed 的简短提示和 `--status`/`--retry` 入口。可复用这个可见失败路径，而非要求用户在原始噪音里寻找状态。 |

systemd 官方 v257 文档证实 `journal+console` 的复制语义；getty generator 说明 kernel console 与登录终端可能共用串口。journald forwarding、PID1 状态、kernel printk、audit generation 和应用直接写 console 是不同路径。[S1][S2][S3][S4]

**已证实**的是多条自有输出路径和共享 console；尚未证实所有显示的 `audit` 都由 journald 转发，也未证明每条进度一定重复两次。不能用一条 `ForwardToConsole=no` 或 `quiet` 宣称根因已全部修复。

### 本切片目标与有限实现方向

在 native systemd 的后台 baseline 验证阶段，项目的常规 running/step 进度进入 journal/项目日志；交互 console 只保留必要的启动阶段、一次明确失败/恢复提示和登录状态摘要。recovery 与初次 initramfs 尚无可靠 journal 时，继续有可见进度和失败理由，不能造成假死。

实现前先列明 project-owned 写入源，分别给 native/recovery 定义路由；避免 native `stdout → journal+console` 与显式 `/dev/console` 并行。同一规则要覆盖独立 runtime-functions 与 init 的打包内嵌版本。以项目 version marker 和 owned unit 边界迁移，保留用户自定义文件/日志；不要在 App 端按正则吞掉 guest 文本。

这一轮不应改 audit 开关、停 journald-audit socket、全局丢弃 stderr、把所有 unit 调为 null，或在 ANSI 解析前后删除“看起来像日志”的行。官方 `Audit=` 会改变内核审计生成，属于不同语义；kernel `console_loglevel` 是显示阈值，也不能代替逐来源归因。[S2][S4]

### 测试 seam 与验收

1. 扩展 `guest-assets/scripts/test-guest-runtime-provision.py` 和 `test-distro-runtime-matrix.py` 的合成根 fixture。使用 sentinel root、合成 state、stdout/日志/console 三个受控 sink；验证 native running 状态不重复打到交互 sink，失败写 state+日志且有一个可见摘要，recovery 没 journal 时仍能解释进度。不得访问真实根盘。
2. 扩展现有 `GuestAssetsShellScriptTest` / 打包检查，核对 init 与独立函数语义一致；保留单 getty 实例 mask、原 init、凭据和只读共享边界。仅“字符串包含 journal”不足以证明消息路由正确。
3. 真机先用下一阶段批准的新测试实例，分别 Fedora 43、Debian 13：native baseline 运行时连续编辑长命令、使用 nano/top、退后台再进入；命令字节没有混入项目进度，常规步骤没有重复屏显。失败场景应显示失败理由，`--status` 与项目日志可追溯；guest 原始错误与审计记录继续可查。
4. 新装配、已有 ready 实例重启、显式 recovery 三条路径分别签收。**本轮未运行**，不对现有实例应用任何配置。

风险：过度安静会隐藏网络停滞；严重失败也可能打断输入，需定义一次性摘要行为。剩余 kernel/systemd console 噪音可能依然存在，要按来源收取最小 trace，单独决定是否调整显示等级。需要独立 PTY/新传输才能完全分离时，应停止扩大本切片并过架构变更门。

## 3. S2：先证明输入边界，再局部修复

### 当前证据与已具备能力

`TerminalInputConnection.java` L83–103 已用完整 UTF-8 commit；L120–135 区分 code-unit/code-point 删除；L280–297 本地保留候选，只在 commit/finish 后发送。L55–65 支持 bracketed paste 与换行规范化。现有 `TerminalInputConnectionTest.java` L177–257 已覆盖候选替换、finish 幂等、Unicode/修饰键、粘贴及 close 丢弃未提交 composition。不能称其“只有 ASCII”或“完全没有 IME”。

仍有两个具体物理键盘缺口：

- `sendKeyEvent` L173–185 无差别从 Unicode 查询 meta 中去掉 `META_ALT_MASK`，再把 `event.isAltPressed()` 作为 ESC 前缀。右 Alt/AltGr 用于键盘布局选字符时，这个路径没有与左 Alt 的 shell meta 语义分开。Termux 固定源码在 `TerminalView.java` L790–815 显式保留右 Alt 的布局组合语义。[T1]
- L242–246 把 `getUnicodeChar()` 结果直接当 Unicode scalar 校验，未处理 `KeyCharacterMap.COMBINING_ACCENT` 标记。带该标记的值不在有效 Unicode 范围；没有累积 accent / `getDeadChar` 路径。Android 官方定义了这套返回值，Termux L827–839 也显式处理。[A4][T1]

因此“这两类框架输入结果缺少专门处理”静态已证实；具体机型与布局上出现丢字、ESC 或错误字符的范围仍需实际 KeyCharacterMap/物理键盘验收。`ACTION_MULTIPLE` 兼容差异可记录，但该旧入口不宜作为现代 Android 的首要新功能。

### IME connection 重绑假设

`ModernTerminalView.java` L95–104 即使重新绑定同一 screen 也 reset connection；L132–135 close 旧 connection；L149–156 创建时也 reset。`TerminalActivity.java` L387–420 在 onStart 重连、onStop detach；L595 把 holder 模型重新绑定 View。close 当前设计明确丢弃未提交 composition，并且有对应测试。

Android 文本编辑器的 `finishComposingText()` 是保留现有文本并结束 composing 状态；终端通常把本地 composing 作为待发送缓冲，Termux 也在 finish/commit 时发送并清空。[A3][T1] 因而不能仅凭自定义缓冲就断言违反 InputConnection，也不应擅自把 focus loss/close 改为自动提交：那会把未选定的拼音或密码输入发给 shell。

需要实验的问题是：OEM IME 在前后台/主题/焦点变化时是否先 finish，是否保存候选到新 connection，是否出现丢失或双发。现有纯方法测试不覆盖系统重绑时序。

### 用户目标与范围

右 Alt 在支持布局中输入正确字符，左 Alt+B 仍向 shell 发送 meta；死键加基字符形成正确 Unicode，无法组合时采取明确且可测的回退。中文、emoji、Enter、Tab、Ctrl+C、paste 均不因重绑双发；未明确提交的候选不被自动执行或写诊断。

优先局部抽出键盘布局结果的纯编码 seam、补框架侧事件测试，再调整 meta/死键处理。connection 变化仅在实际故障复现后修复绑定策略。不要全局改成 visible-password inputType、抄 Termux Editable 实现、重写 emulator、引入固定串口 pacing 或无限重试。

### 可执行验收

| 层次 | 验证内容 |
| --- | --- |
| JVM/框架 key-map | 使用真实框架或受控 resolver，验证右 Alt 布局字符、左 Alt+B、Ctrl+Alt、死键+e、死键+空格、双死键、不能组合的字符；sink 比较精确 UTF-8，不让 stub 的恒零 Unicode 冒充真实布局。 |
| InputConnection 时序 | `setComposingText(n) → ni → 你 → commit → finish` 只一份；code-point 删除不拆 surrogate；新 connection 不接受旧 connection 的迟到 commit；失焦不擅自发送候选。保留当前 close 零发送断言。 |
| lifecycle | 有条件时 ActivityScenario/Robolectric 重入与 replace input connection；系统测试记录输入 API 次序和固定原因码，禁止记录候选正文、口令、完整按键或剪贴板。 |
| 设备 | 至少一个中文 IME、一个 AOSP/Gboard 路径和一把支持 AltGr/死键的 USB/蓝牙键盘；在 shell 与 nano 中输入合成 ASCII/CJK/emoji，IME 开合、锁屏、退后台、返回，核对 guest 收到的文本与行数。 |
| 粘贴/压力 | 受控合成文本 1/16/64 KiB，检查多字节与 CR/LF、bracketed-paste 边界、Ctrl+C 响应；只有稳定复现丢字/卡顿才决定队列背压方案，不从旧单次观察推断 UART 总会 burst 丢字。 |

风险：不同 OEM 与布局差异大；连接退役不等于应补发 composition。硬件支持与 IME 支持必须分开签收。本轮没有运行这些场景，也没有断言大粘贴已经丢字。

## 4. S3：退出原因、前台重入与冷恢复一致性

### 直接代码证据

| 位置 | 已证实的行为 | 不能据此直接宣布的现象 |
| --- | --- | --- |
| `MainActivity.kt` L1545–1560；`InstanceLaunchSequence.java` L28–31 | 调用 `session.waitFor()` 后丢弃返回码；finally 统一走 `recordNormalGuestStop`。底层已有 recordCrash API，却未在此按退出原因分流。 | 尚未用实际非零 QEMU 退出做真机复现；不能宣布所有状态都已损坏。 |
| `MainActivity.kt` L1455–1465；`InstanceLifecycle.java` L954–1026 | 主页面把失去 registry 的 RUNNING 归 STOPPED；领域默认 reconciliation 支持 INTERRUPTED，并已有 coldRestore。不同入口可能给出不同解释。 | STOPPED 是 API 允许的参数，不宜把文案不一致夸成存储损坏。 |
| `ExecutionSessionRegistry.java` L68–76；`PersistentTerminalSessionPool.java` L220–227；`MainActivity.kt` L1478–1483 | get 返回 holder 的 session，不核对 `isAlive`；launch 的“已运行则连接”以非 null 判断。EOF 到 unregister 之间存在旧引用窗口。 | 窗口实际持续时间和用户能否稳定命中需并发 fixture。 |
| `TerminalActivity.java` L489–510、L725–749 | Activity 直接调 reconciler；recoverer 顺序 connect/close 两个 QEMU abstract socket，再构造 `waitFor()=0`、`destroy()` 空实现的 handle。此处未显式设置 connect 的时间边界。 | 不能仅从代码证明每次会 ANR；也不能把可连接 socket 等同于真实受监督 session 已恢复。 |
| `OrchestellaForegroundService.java` L70–103、L164–181 | start intent 能建立 RUNNING 通知；通知进入 Terminal 只带 instanceId，不携 bridgeToken。普通入口会带 token；新 Activity 在 onCreate 从 intent 读取 token。 | 当前已 READY 的 shared client 有私有鉴权状态，通知进入不必然失败；pending 初次握手路径需要单独测试。 |

当前良好边界应保留：holder 是唯一终端解码/VT 写入点；onStop detach 不 close shell；bridge 是单 reader；`recoverControlOnForeground` L752–775 每次显式 onStart 才尝试一次已失败 control 的恢复，带 foreground/binding/client identity；不应退回 UI 建第二 reader 或无界重试。

### 有限目标

正常关机显示已停止；非预期非零退出保留固定原因与 crash 状态；App 进程被系统/用户结束后的旧 RUNNING 标为可冷恢复的中断状态，根盘和身份不变。回前台尽量绑定现存 holder；控制失败和 shell 还活着可以分别表达。通知、主页面和已打开终端应给出一致状态。

“终端 EOF”只证明流结束，不独自证明 VM 进程退出。分类应组合真实进程观察、显式停止意图和传输状态；由已有 Controller 决定持久状态，不把 raw guest 文本当退出判据。若正常关机路径并非稳定 exit 0，先 fixture/设备确认，再确定退出映射，不能简单把所有非零都标恶意或数据损坏。

可先局部补纯 `exit classification / expected session identity` seam，修调用层错误分类和 stale 判断。不要借此把全部 VM 所有权迁入新进程、加入热恢复、socket 存在即领养为受监督 VM。QEMU 专属探测不应进一步泄露到策略中性产品契约。若现有 handle 生命周期无法表达安全恢复，保留明确“无法重连，请冷恢复”，另走架构门。

### 测试 seam 与设备验收

1. 扩展 `InstanceLaunchSequenceTest`、`InstanceSessionReconcilerTest`、`PersistentTerminalSessionPoolTest`、`ControlChannelRecoveryTest`；注入 exit=0、非零、wait 异常、预期 terminate、terminal EOF 但进程未退出、registry 仍有死 holder、旧 watcher 晚到。验收 metadata、持有 session identity、通知停止次数与错误分类，不能只测 enum 字符串。
2. 竞争序列：A 退出回调晚到时已启动 B/新代 A，不得 unregister 或 stop 新会话；旧 resize/bridge 回调不覆写新 READY；每条终端/控制流最多一个 reader，失败流不续用。
3. 通知 cold Activity、普通 openTerminal、pending bridge、已 READY bridge 四种入口，都从已批准的本地元数据/现存会话取得必要上下文；不新增 token 出站、日志或可复制诊断字段。实际采用哪条恢复路径由实施审查决定。
4. 真机先完成前后台、锁屏、Activity 重建、正常 guest poweroff。进程死亡与 Android Task Manager Stop 只在后续单独批准的临时实例上做，不用正在工作的根盘。Android 13 Task Manager Stop 不发送生命周期清理回调；不能靠 onDestroy 保证保存或恢复。[A5]
5. 每次冷恢复检查实例 ID、root volume 引用、hostname、随机凭据保持一致；未主动恢复不自动启动 guest。若选用 ApplicationExitInfo，只 API 30+ 守卫并输出脱敏原因码；旧 Android 保留 unknown/interrupted，不新增日志读取权限。

风险：修改状态映射影响启动单槽约束与错误提示，需要持久状态契约回归；异常退出并不保证 guest 文件系统所有缓存已刷盘。前台服务与 wake lock 是后台尽力而为措施，不是 OEM 永不杀进程保证；现有 12 小时 wake-lock 超时也不是 24 小时运行承诺。依据 ADR-0011 保留冷恢复，不扩展为内存态恢复。

## 5. S4：真实系统重建后的向导草稿恢复

### 事实与假设分开

`SetupWizardActivity.kt` L212–260 将 savedInstanceState 交给 super 后，每次按 intent 中 targetDistro 和即时 hostProfile 新建 `InstanceCreationWizardModel`；全文件没有自定义 `onSaveInstanceState` / `onRestoreInstanceState`、SavedStateHandle 或 ViewModel。`InstanceCreationWizardModel.kt` 构造参数 initialStep 默认 STEP_STORAGE，currentStep 保存在实例字段。

Manifest L41–44 的 SetupWizard 无 configChanges；Terminal 等页面的 orientation 例外不适用于它。Android 对未自行处理的旋转、窗口/系统 uiMode 变化可重建 Activity，普通 Activity 字段不会自动留存。[A1]

`switchDistro` L578–599 从 previousModel 复制步骤和配置，覆盖的是同一个 Activity 内的发行版切换。v0.4.2 的偏好实时切主题不重建成功，也不证明真实系统重建路径已经保存模型。

**静态已证实**：自定义步骤/模型没有显式保存恢复。**需实验**：第几步会退回、哪些配置 UI/model 会分歧、选择 spinner 自动恢复后 listener 会怎样触发。Android 有 ID 的 EditText/Spinner 等可能自动保存部分 View state，因此不能写成“旋转必丢所有输入”。[A2]

### 目标与最小方案

用户在第 3/4 步遇到旋转或系统亮暗切换后，发行版、磁盘、镜像、vCPU/RAM、用户名/hostname、勾选项及当前步骤与摘要一致。恢复轻量、版本化的草稿数据；重建时重新测宿主能力并按现有验证器检查。过期枚举、不存在发行版和已不可用配置给出明确回退，不把坏草稿当成可创建实例。

可优先使用已有 model 的小 snapshot/restore seam + saved instance state，不为四步向导引入新数据库或全局状态框架。保存 draft 不保存 Context、连接、Future、下载进度执行器、原始密码/bridge token；`isCreating=true` 不是可反序列化的工作任务。若创建已进行，重建只订阅已存在操作或显示明确状态，不自动再执行一次 create/download。

### 验收与停止条件

- 先 ActivityScenario.recreate / Robolectric 做 red fixture：第 3 步、第 4 步、非默认发行版、手填容量、非默认 RAM、用户名/hostname；重建后同时比对 model、控件、creation summary，不只截图。现有 `SetupWizardStepFlowTest` / `InstanceCreationWizardModelTest` 可测 snapshot 数据，但单独模型测试不足以证明 Android 重建接线。
- saved state 缺失、字段损坏/旧版本、非法 step/distro、RAM 已不满足安全门，均有明确行为；不得默默套用上次 AVF READY。
- UI 真实旋转和系统 uiMode 各一次；和应用偏好 live theme 各自记结果。编辑中尚未提交的内容、焦点与 IME 验收范围显式列明。
- 创建前草稿重建不产生下载；创建中重建不再发起第二次创建、不覆盖输出目录。设备不可用时本切片保持未验收，不靠扩充 configChanges 来绕过状态责任。

本轮没有执行重建实验，也未实施保存功能。主协调者已明确将其留为有生命周期/设备 gate 的下一阶段候选。

## 6. S5：把发行版与硬件体验做成有限、诚实的兼容门

### 当前证据

- `DistroResourceRequirements.java` L5–10、L23–33 预算为固定压缩输入两份加 512 MiB，向 256 MiB 取整；注释明确它只是首次 initramfs 装配预算，不是发行版运行/包管理器工作负载保证。`MainActivity.kt` L1490 每次 launch 都调用 requireBootRam；“已有根盘能否降低 RAM”必须根据输入是否仍嵌入及 bootstrap 行为验证，不能只凭 provisioned 标记放开。
- `HostHardwareProfile.java` L97–110 推荐 `min(total−2048, available×0.75)` 后钳位到 VM 最小值；L138–152 RED 的主要 RAM 门是 total 留 2 GiB，`lowMemory`/threshold 作为字段保存却不直接参与该判断。计算推荐不是“永不 OOM”证明，配置期间的 available RAM 也不是启动时保留资源。
- 公式允许一个值得测的极端输入：available 为 0、总 RAM 充足时，recommended 仍可能被钳到最小值且 evaluateRamZone 给 GREEN。逻辑可由纯 fixture验证；这里不把它写成已在低配机上 OOM。
- `AvfCapabilityEvaluator.java` L34–69 的 READY 依据 SDK/ABI/声明/芯片 hint/MANAGE；`ReflectiveAvfVirtualMachineApi.java` L72–89 启动时另查 CUSTOM 权限、nonprotected bit、API shape，且目前精确 vCPU 只支持 1；UI 预检和实际 launch 的含义并不相同。这是检查分层差异，不是已授权真机启动失败证据。
- 该 AVF adapter L109–114 显式 `setProtectedVm(false)`；不能对这条 custom guest 路径沿用“宿主看不到内存”的 protected VM 承诺。AOSP 区分 capability bits，custom-image setter另需 USE_CUSTOM 权限。[V1][V2]
- `QemuLaunchSpec.java` L481/L559 根据配置选择 virtio-gpu-gl/virtio-gpu device；配置 flag 不等于 guest Mesa 驱动、宿主后端与 UI 全链路已被验证。QEMU 官方把 2D、virgl、Venus、rutabaga 及其前提分开。[Q2]

### 本轮可做的有限优化

沿用 pinned manifest、现有 flavor、单运行实例和 2 GiB 宿主保留硬边界。只补清楚的“装配最低需求 / 运行建议 / 当前内存压力 / 本次实际执行策略”提示，以及测试发现后的纯计算/预检一致性修正；不改变用户已选配置、不自动授 AVF 权限、不扩大 chip blocklist、不把检测失败解释为硬件永远不能。

AVF UI 可以诚实区分“预检条件满足、等待启动确认”与实际 session 已使用硬件虚拟化。上层消费策略中性能力数据；低层检测的缺权限/缺 API/nonprotected 不可用/精确 vCPU 不可表达可作为固定原因码返回。未有一手设备证据，不按品牌名单新增全局否决。探测本身也不应偷偷启动 VM。

硬件加速和 GPU 分别显示证据阶段；本报告不建议自动开启 virgl、替换 Mesa/Turnip、升级 kernel、承诺 FPS 或让 AVF GPU 进入本轮排期。16 KiB 设备需要 ELF/打包/运行三层门；ADR 或构建标记说已对齐不是本轮真机兼容证明。[A6]

### 最小兼容矩阵与验收

| 维度 | 下一轮最小场景 | 可通过的证据 | 不允许推导 |
| --- | --- | --- | --- |
| Fedora 43 / Debian 13 | 已有 ready 根盘冷启动；另建临时实例测首次装配；S1/S2/S3 各回归 | PID1/init 状态、唯一 console owner、bridge/终端状态、包基线 status、命令/resize | 一次 shell 提示符出现就当所有 baseline ready |
| Ubuntu / 其余四 catalog | 先保留 hash/静态 flavor fixture，按容量最多补一个代表新装配 | 每发行版分别标下载校验、装配、native init、terminal/network、未测项 | 7/7 hash 匹配等于 7/7 真机可用 |
| RAM / CPU | 总 RAM 3/4/8 GiB 的合成 profile；available=0、threshold/lowMemory、配置后内存变化；实机先测有限两档 vCPU | 预算单测、失败前置提示、实机启动/包管理器耗时和宿主压力摘要 | `totalCores−1` 是所有 TCG 设备最佳性能参数 |
| Android / OEM | 当前已测 QEMU 机型为基线；能力允许再补一个旧 API 与一个 API 35+ / 16 KiB 环境 | 页面尺寸、IME/前后台、FGS、native 启动及实际 page size | minSdk=26 / arm64 表示全部 OEM 都稳定 |
| AVF | 当前只测 NEEDS_AUTHORIZATION/unsupported/fail-closed fixtures；后续有授权设备才测 custom guest | 权限/API/capability/1-vCPU 形状检查与实际启动证据分开 | 未授权就是功能坏；检测到 feature 就可用；AVF CPU=GPU 加速 |
| GPU | 本轮只记录未验证/实际路径，不新增压力实验 | 有设备后对具体 backend/driver/rendering 做独立验收 | virtio-gpu 参数、GL 库加载或计划 ADR 就等于全链路硬件渲染 |

可复用 `HostHardwareProfileTest`、`DistroResourceRequirementsTest`、`AvfHostFactsProbeTest`、`AvfCapabilityEvaluatorTest`、`ReflectiveAvfVirtualMachineApiTest` 和 `guest-assets/scripts/test-distro-runtime-matrix.py`。新增 fixture 应验证边界行为，而非复制公式自证；启动 RAM gate 若调整，必须同时证明不会跳过仍需嵌入 rootfs 的 firstboot 路径。

`2026-10-13-distro-validation-matrix.md` 已明确“七包固定 size/SHA 通过，不等于七发行版 boot”；本轮只引用其结论，不再次下载或执行 rootfs。Arch rolling 必须保留固定 hash；Alma major 9 的实际 minor 与目录标签分开。扩大 OCI 或发行版输入模型触及 ADR-0006，不能在体验修复中顺手决定。

## 7. 本地参考仓的可借鉴点与边界

| 参考 | 实际核查 | 借鉴与不能照搬的内容 |
| --- | --- | --- |
| Termux-app | `terminal-view/.../TerminalView.java` L309–437、L783–839；`TermuxActivity.java` 的 onSaveInstanceState/service 接线；官方站与固定 revision 对照 | 编码/IME 测试矩阵、左右 Alt、死键、session 与 UI 分离；它的宿主 PTY 不是 VM serial，不搬本地 shell、native PTY 进 guest 契约。仓库 GPLv3-only，文件来源另有 notices；仅研究行为，没有引入代码或二进制。[T1] |
| DroidSpace | `AGENTS.md`；`Android/.../service/TerminalSessionService.kt` L24–71、L236–241 | Service 拥有 session、UI dispose 后解除 client 引用是有用的生命周期检查点；其容器依赖 root，不能当 rootless VM 独立存活的证据。[R1] |
| proot-distro | `AGENTS.md`、`README.md` limitations（L1600 起） | 提示能力/内核/后台约束的诚实表达。PRoot 的 ptrace/path translation 与真 cgroups/namespace/init 不同；不因它能跑个别后台进程，就把它当 Full Linux 的透明执行策略。[R2] |

参考仓只读；没有读取其 refs、切分支、pull、构建或复制二进制。DroidSpace/proot-distro 的当前本地 revision 没有重新认证；可复核本地指定文件，但不杜撰固定 commit。Termux 固定 revision 与官方下载内容的 SHA 已保存在外部 evidence，短片段仅用于可追溯证据，不作为移植补丁。

## 8. 对既有决策的影响检查

| 决策 | 本轮结论 |
| --- | --- |
| ADR-0002、0016 | S1–S5 可沿现有中性契约与单运行实例做；不加入第二套 reader、不将 QEMU 特有 probe 扩进平台契约。真正独立 shell/PTy、多实例并发需新决策，不列入本轮。 |
| ADR-0011 | 异常退出/INTERRUPTED/coldRestore 的语义已有接口支持，可先修调用一致性；不承诺进程死亡后 VM 存活、热恢复或无限后台运行。 |
| ADR-0004、0019 资源约束 | 不降低 2 GiB reserve、不自动更改设备支持地板；若未来实测需改变支持地板/分配硬门，先重开对应决策。 |
| ADR-0005、0006 | 不升级 pinned kernel、不换 rootfs、不引入 OCI；首次装配与已有盘启动预算差异先实验。历史来源/官方 tarball 表述与 catalog 的差异由既有研究记录，本报告不悄悄修订。 |
| ADR-0010 | 不增加数据暴露面，不上传诊断/按键；当前 nonprotected custom VM 不能借用 protected 内存隔离文案。历史 ADR 中抽象 socket、页大小、芯片/GPU 的宽泛断言，本研究没有提供普遍性证明，发布不能把它们当覆盖所有设备的事实。 |
| ADR-0012、0019 图形承诺 | 本轮没有获得新的 GPU/AVF 真机数据。若今后要依据未证实的 60 FPS/全机型假设改变架构，必须另开 ACR；本轮不替用户采纳或否决路线。 |
| ADR-0014 | 依据官方协议、参考架构独立研究；无实现或二进制复制。当前 GPL 项目许可不会自动取消来源、NOTICE 与对应源码义务。 |

本轮没有触发实施性的架构变更，也没有重开或修改 accepted ADR。需升级的潜在事项是：若只能通过新 terminal 数据面解决噪音、改变执行策略安全表述、支持地板或 GPU 路线，必须在实现前另行提案。本报告是证据与排期建议，不是新的产品架构批准。

## 9. 建议本轮与下一轮交付边界

本次 consolidation 已由其他 owner 处理 CI 构建可靠性、lint 依赖恢复与版本/更新通道；本报告不重复实施，也不将这些结果算为终端 worker 修复。

建议下一轮只领取以下有限范围：

1. S1 自有 firstboot 状态输出去重与 journal 路由；保留所有关键失败、recovery 可见性、audit 和原始诊断。
2. S3 退出分类与 stale-session/通知入口回归，先完成测试，再局部修调用；不引入新的运行时所有权架构。
3. S2 物理键盘 AltGr/死键最小回归；真机 IME 时序拿到证据后才改 connection 行为。
4. S4 草稿模型重建恢复，依赖 Activity 重建测试与设备签收；本轮没有设备结果就保持候选。

S5 作为这四项共同验收矩阵，每轮优先两个已测发行版，不把全部发行版×OEM×IME×GPU 的笛卡尔积塞入一次迭代。暂缓独立多 PTY、全面 VT 重写、PRoot Runtime、AVF/GPU 新路线、热恢复和大规模性能调参。这些议题并非已经否决，而是现有证据不足以替代用户决策。

## 10. 来源、产物与验证账本

### 一手来源索引

以下均于 2026-10-15 访问；moving main/master 和 live 文档不当作项目 pinned 版本。资料抓取只发送公开 URL/脱敏研究问题，不上传项目源码、完整日志、凭据或文件清单。

- [A1 Android：Handle configuration changes](https://developer.android.com/guide/topics/resources/runtime-changes)。
- [A2 Android：Save UI states](https://developer.android.com/topic/libraries/architecture/saving-states)。
- [A3 Android InputConnection API](https://developer.android.com/reference/android/view/inputmethod/InputConnection)；补核 [Android 16.0.0_r1 官方源码](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/view/inputmethod/InputConnection.java)。
- [A4 Android KeyCharacterMap API](https://developer.android.com/reference/android/view/KeyCharacterMap)。
- [A5 Android：Handle user stopping an app with FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)。
- [A6 Android：Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)。
- [S1 systemd v257 systemd.exec](https://github.com/systemd/systemd/blob/v257/man/systemd.exec.xml)。
- [S2 systemd v257 journald.conf](https://github.com/systemd/systemd/blob/v257/man/journald.conf.xml)。
- [S3 systemd v257 getty generator](https://github.com/systemd/systemd/blob/v257/man/systemd-getty-generator.xml)。
- [S4 Linux kernel printk basics](https://docs.kernel.org/core-api/printk-basics.html)。
- [Q1 QEMU Arm virt](https://www.qemu.org/docs/master/system/arm/virt.html)。
- [Q2 QEMU virtio-gpu backends](https://www.qemu.org/docs/master/system/devices/virtio/virtio-gpu.html)。
- [V1 AOSP AVF overview](https://source.android.com/docs/core/virtualization)。
- [V2 AOSP VirtualMachineManager](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineManager.java) 与 [VirtualMachineConfig](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java)。
- [T1 Termux 官方站](https://termux.dev/en/) 与 [TerminalView 固定 revision 8629e632](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java)。
- [R1 Droidspaces 官方仓](https://github.com/ravindu644/Droidspaces-OSS)，以保留的本地源码核查为主。
- [R2 Termux proot-distro 官方仓](https://github.com/termux/proot-distro)，以保留的本地说明核查为主。

初次猜测的部分文档路径返回 404，freedesktop getty 页有超时，长 API/XML 页面有提取截断。相关关键主张最终通过成功的 canonical URL 或固定源码短片段补核；失败请求不算证据。systemd v257 `Audit=` 与 latest 文档有版本差异，本报告只用它证明开关语义不同，不把 defaults 外推到每个安装实例。

### 产物与治理

Canonical 报告为本文件。外部证据根由用户明确指定为实际 `Orchestella` 目录：

`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/consolidation-20261015/research-runtime/`

- `official-manual-excerpts.json`：三个 systemd v257 官方 XML 的 URL、响应大小/SHA-256、短摘录及源行号；不保存整个上游实现。
- `official-api-excerpts.json`：Android 16 InputConnection、固定 Termux TerminalView、AOSP main AVF 两文件的短证据、URL 和响应 hash。
- `source-ledger.json`：一手来源索引、适用结论、局限与检索失败说明。
- `preserved/`：证据即时字节副本。小文件长期保留便于复核，owner 为本研究 worker，归档/清理由 coordinator 接管；无自动清理动作。

先读：活动仓 AGENTS、DEFENSIVE_CONSTRAINTS README 与全部 policy（含 VERSIONING）、CONTEXT、相关 ADR（0002–0006、0010–0012、0014、0016、0019）、map、Host/Artifact/Architecture Change/Reporting policy。旧 DroidLayer 路径遵循用户本轮明确指定的 Orchestella 活动与输出路径，没有另建 shadow workspace。

实际完成：源码证据、官方事实、五个 slice 与测试/设备验收门。本轮未完成：任何产品修复、构建、设备重现、性能基线与新兼容认证；这些是明确 scope，不计为通过。

新增文件仅本报告和指定 research-runtime 下的研究证据；生产代码修改为无。分支/基线 commit：未查询；commit/push：无；worktree/refs 操作：无；设备/实例操作：无。没有写系统配置、SDK、NDK、用户运行时或新网络传输功能。工具自有会话输出由工具管理，不作为项目公开证据。

未新建 `.log` 扩展名证据；不复制真实凭据、设备 ID、根盘、完整 Git/宿主清单或完整终端输入。代码静态不足和实验假设已分别标明。外部摘要 hash/副本完整性检查若随交接执行，只验证研究文件，不代表产品 gate。

报告人：terminal-runtime research worker / GPT-6.1-sol ｜ 日期：2026-10-15 ｜ 耗时：未单独计时
