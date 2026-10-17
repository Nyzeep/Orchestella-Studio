# 下一会话执行 Prompt：剩余工单实施、真机授权与 AVF 闭环

日期：2026-10-16。配套事实交接：同目录 `AVF_SHIZUKU_REMAINING_HANDOFF_20261016.zh-CN.md`。下方主代理 Prompt 可整体交新会话，主代理自行分发工作包，无需用户分别启动每个子代理。

## 主代理启动 Prompt（复制开始）

你是 Orchestella Studio 剩余工单的主协调与实施代理。目标是在保留现有未提交代码、用户数据与已接受契约的前提下，完成 `.scratch/orchestella-avf-shizuku/` 中所有剩余工单的实际实现/验证，包含真机 AVF 准备、受控可行性实验、真实 custom Linux Guest、生命周期/体验缺陷修复和基于真实数据的性能工作。不要重新从零研究或只交未来建议。

### 一、先读这次事实交接，不执行旧研究启动流程

工作根：`PROJECT_ROOT`；唯一源码仓 `Orchestella_dev`。

先依次完整读取：

1. `Orchestella_dev/docs/agent-handoff/AVF_SHIZUKU_REMAINING_HANDOFF_20261016.zh-CN.md`。
2. 本文件的主代理 Prompt、分工协议和全部 Worker Prompt。
3. 主仓 AGENTS.md、CONTEXT.md；DEFENSIVE_CONSTRAINTS 全部 policy；HOST_ENVIRONMENT_POLICY、ARTIFACT_ORGANIZATION_POLICY、ASSET_REUSE_AND_HYGIENE_POLICY、ARCHITECTURE_CHANGE_POLICY、REPORTING_STANDARD.zh-CN。
4. `.scratch/orchestella-avf-shizuku/scope.md`、map.md、spec.md、issues内17份工单；`docs/agents/issue-tracker.md` 的旧活动入口只作背景，本次明确继续新effort，不改全局默认。
5. `docs/research/2026-10-15-avf-shizuku-upgrade.md`、`docs/reports/orchestration/avf-shizuku-next-stage-20261015.zh-CN.md`；01–04与13实施/独立审查，按主题读A/B/C/D外部分项证据和相关ADR。

事实优先级：当前代码/设备与固定一手证据决定事实，accepted ADR决定契约；冲突按ACR处理。旧文档中无设备、clean405、只有文字Shizuku引导、W:/V:已存在都是历史状态，必须更正而非照抄。不要复用旧设备serial或把原403日志换成500/当前ROM。

### 二、用户已经给出的授权及边界

用户本次明确要求剩余全部工单实施，并说“新工单允许激活并验证avf来进行项目的调试工作”。你有权在现约束内主动进行真机准备/合法开发级授权/隔离QA实验/实际AVF调试，不需要再次泛问是否允许尝试AVF。

按范围可执行：发现当前设备和Android user、核App安装/签名/实例/活事务；安装本项目正确签名的验证包或独立QA包；从官方来源准备Shizuku并引导用户完成首次系统步骤；请求本App client授权；仅对本项目/明确QA包、当前user、固定MANAGE/CUSTOM合法grant/revoke/readback；在明确新建QA盘上运行AVF/QEMU、Guest命令、resize、网络/服务、正常停止与安全基准。尽量通过App流程完成，保留ADB兜底，分别记录“软件内成功”与“ADB人工调试成功”，不能混为同一UX验收。

机主的指纹/口令/配对码/系统确认由用户本人完成；不要绕过。遇到这些人类步骤，只提出确切操作和为什么需要，其他独立工作继续。若App签名不符，保全数据并记录，禁止卸载清数据解决。

不在授权内：Root/解锁、全局hidden_api_policy、SELinux permissive、改thermal/sysfs安全策略、系统分区替换、降低targetSdk掩盖接口问题、私有目录world-readable、用户根盘公共拷贝/格式化。不要导出凭据、bridge token、配对密钥或embedded initrd载荷。默认LAN监听/Guest暴露面/多实例/热恢复不扩大。

新生产VMowner/特权IPC/独立代理生命周期仍需 ACR-01：先以05具体证据形成可审阅方案，再请求用户裁定具体边界；本次AVF调试授权允许受控实验，不自动关闭ACR。架构问题不能把普通client已授权当答案，也不能让全部独立票因此停摆。

不自动commit/push/tag/release/开远程Issue或PR。任务是本地完整实现与证据交付，公开v0.4.5保持，发布独立授权。不要因为技能默认“commit”或“每worker一个worktree”覆盖这一约束。

### 三、先核当前基线和设备，再领取frontier

已知2026-10-16交接核查：HEAD仍`428860ad9a1cb335f8be78c157b2ff57faf2a6da`，单main/单worktree，**36份0.5.0任务变更文件未提交**，与昨日closeout seal一致。不同Git状态目录折叠计数不代表文件丢失。从HEAD新建worktree会遗漏0.5.0，不允许用旧405快照替代当前源码。先git status/diff/log/branch/worktree和实际版本核验，保留后续用户改动。

昨日canonical交付seal：`Orchestella/artifacts/avf-shizuku-next-stage/20261015/closeout/delivery-manifest.json`。只定点比较必要任务文件，禁止整仓/.git归档或对外上传。写本轮baseline/设备队列/操作账本到新外部批次，不覆盖昨日证据。

当前设备别名handoff-device-01，交接时恰好1台adb device，MEY_AN00、Android16/API36、arm64、4KiB，feature=true，com.android.virt versionCode3；nonprotected声明未知、protected声明true，两权限/API能力未知。当前user0下精确查询未返回本App或Shizuku管理器。**这些是查询结果，安装/活实例/真实能力必须在新会话再次核查。** 本交接没有安装或启动VM，真实AVF样本仍0。

宿主今日 `subst`为空且W:/V:不存在。构建需ASCII路径，先确认盘符空闲再映射W:到主仓、V:到Orchestella外部根；禁止覆盖其他会话映射。SDK D:/AndroidSDK、JDK D:/JDK17、adb ADB_PATH，Git Bash adb设置MSYS_NO_PATHCONV=1。WSL Ubuntu-24.04启动前复核，复用既有缓存/工具链，不重装。

先检查当前包及签名、活VM/事务与实例引用。已有数据只取本地不含secret的身份/引用指纹，主代理持有，不发给其他worker。选择新QA实例/盘，不直接拿历史用户Ubuntu作写基准或异常进程实验。若currentuser确无App，记录后可按授权安装验证包；不把“无包”泛化成所有user无数据。

原始500 APK位于旧批 `delivery/Orchestella-Studio-v0.5.0-local-validation.apk`，106506176B，SHA256 `500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c`，证书SHA256 `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06`。相同bytes可用于本次先验UI，不改代码复用版本；生产代码/资源不同的新APK先递增版本，按纯修复PATCH、重要新能力MINOR评估，不复用500装不同代码。统一版本与APK生成由主代理负责。

### 四、工单现状与启动分工

当前已完成：01/02/03/04/13本地实现，16来源研究。部分：11局部公共seam red。未完成10项：05/06/07/08/09/10/12/14/15/ACR-01。不要把17份已建当17份已实现。

初始并行frontier：

- A：05/06身份与本机权限实验；设备步骤进入主代理队列。
- B：07/08平台调用/FD/owner最小方案、可离线fixture；待05与ACR结果后接生产与真实AVF。
- C：11剩余调用链/退出、12自有日志来源，先固定运行red，再限定产品修复。
- D：14 KeyMap/物理键盘/IME、15系统recreate，先框架/手机反馈，再局部修复。
- E：复用04做真实recorder/基准准备；07未通过时只工具/合成门，09/10不能产AVF实测。
- 主代理：设备/数据/签名/版本/构建串行、方案综合、ACR用户裁定、真实验收与最终报告。
- 独立review：模块冻结后另派规范/Spec两个只读审查者，不能让实施者自审代替。

为避免同文件冲突，A/B按“先A原型与授权入口，再B按批准owner接线”交接共享launcher/engine；C拥有session监督/Guest自有日志，D拥有输入/向导草稿。D与A可能同时涉及SetupWizard，主代理分配时间窗或按hunk合入，禁止同时写同一个文件。E只工具/新事件组件，具体bootstrap/launcher插桩需原模块owner协作，由主代理登记写锁。

研究票11/12/14/15要先复现，但本次目标包含发现后的必要修复。可在现effort追加自包含实施子票，从当前最大编号之后递增，标parent、证据、scope、red命令、负例、设备门、回滚和许可；常规粒度自主决定，不为是否拆修复票再问。真正产品/权限/数据架构选择仍由用户。

### 五、设备/构建并行纪律（必须落到本轮scope）

1. 主代理是唯一直接设备写操作者和安装/权限/VM启停协调者。Worker默认不自行adb、UI点击、grant、start/stop/kill；先交固定命令/预期/副作用/前置状态/回滚。主代理明确授予某个短设备窗口时才例外，窗口只一个owner，结束交还。
2. 每个设备窗口记录当前APK/source/ROM/user/QA实例/session/generation、所需权限/资源、开始/结束条件。不能两个VMM同时挂同一RW盘；AVF未知是否仍活时不立即同盘QEMU fallback。
3. Gradle只一个runner。构建前源码/资源/build输入冻结，worker在窗口内只读/写报告，未完成测试草稿留外部；不要重演上轮capability测试半成品导致编译阻塞。
4. 主代理统一versionCode/APK/签名/资产hash；worker不得自行安装临时不同包。检查设备实际安装版本/进程，先正常完成活VM/包事务，必要冷启才能确认新代码，不能force-stop打断用户事务。
5. QA disk/异常death/撤回测试必须明确新测试环境与恢复路径；保留正常路径对照。App/manager撤回可能force-stop，测试中先无活事务，不拿生产实例试。
6. 单reader/token/auth和默认loopback不变；性能探针只能走原认证路由，不抢读控制/终端。诊断不解析任意用户正文、不记录真实输入。
7. 产物新根建议 `Orchestella/artifacts/avf-shizuku-device-20261016/<唯一batch>/`；默认不覆盖已存在路径。原始证据.md/.json/.tsv/.log.txt，即时preservation；ClassA与旧APK/source bundles/原始日志全部保全，不清理。

### 六、AVF路径必须逐门取证

先利用当前手机验证平台→Shizuku→两权限→调用进程hiddenAPI/UID/domain→Context/state→FD→create/run→认证Guest/终端→网络/服务→正常stop。每门独立：

- 仅两permissions或Shizuku授权不解除普通App hiddenAPI；普通Binder wrapper不能搬走App接口解析。
- UserService官方非SDK边界不同，但非有效App Context；Java持久VM state位于Context data/vm/name，路径开FD在调用进程。不要把packageName改成App就声称UID/owner没变。
- 使用canonical `prototypes/avf-shizuku-identity-probe/`（已修AIDL id并编译），它只有固定identity/shape、capability NOT_QUERIED，无完整QA App。先装配独立QA包，不把实验塞进生产App。合成FD/state实验逐步扩展并审查，不使用公共用户盘。
- 原型先无grant/VM副作用证明identity/shape，之后固定权限与Context/合成FD，再在合适新QA盘验证VM。05不足时明确停在哪门，别盲改平台限制。
- 当前adapter nonprotected且精确1vCPU，RAM保持发行版预算/宿主reserve。Java ONE_CPU/MATCH_HOST不是任意核数。protected-only设备不能拿Microdroid成功替代custom Linux。
- custom VM没有通用payload-ready事件；run返回/RUNNING/连接可达不等Guest ready。以本应用owner/session、认证agent、固定QA命令和服务ready、正常stop证明，排除系统其他crosvm。
- 平台connectVsock可能在mLock持锁阻塞stop；caller Future timeout与底层结束分开。必须有限在途数、迟到FD回收、停止确认；不能无限起线程或伪称已取消。
- 首个owned AVF闭环证明后，才做同资源AVF/QEMU基准。任何fallback标真实QEMU或失败并保留，不进入AVF成功集。

### 七、按证据裁定ACR，不绕过也不空等

05已有真实identity/Context/FD门后，主代理初审：是否能在既有能力矩阵/契约内消化；若新VMowner/有限特权IPC必需，完善ACR-01的调用链/威胁模型/文件capability/持久namespace/死亡收敛/迁移/回滚与负例，向用户给出具体推荐和替代选择。用户批准后同步ADR/spec/受影响票，才放行07/08产品实现。

若用户暂不能裁定，继续11/12/14/15、06和fixture/工具；不把整个项目停止。若目标ROM硬门反证，记录不支持原因、原始错误类别和可替代路线，保留软件执行路径；不要为了“剩余全部”放宽安全政策、伪造通过或静默删工单。

### 八、性能与体验完成门

09：绑定APK/source/ROM/module/调用owner、精确CPU/RAM/QA盘/kernel/initrd/rootfs hash；冷/热、首次下载/装配/包baseline/ready盘分组，固定本地可控网络与温控/充电条件，保留失败/取消/回退。小样本仅median/range，不给少量样本包装p95/p99，不关thermal。工具JSON/receipt/hash只做结构与关联，主代理/独立审查核物理真实性，禁止手造字段签发成功。

10：只选09证实的一个瓶颈变量，前后正确性/资源/温控同条件，不同时换kernel/GPU/CPU/网络。若没有显著收益，证据化不采用可作为票结论，但不能叫已提速。

11/12/14/15：每项从用户现象/公共行为seam建立固定red，补局部实现和正负回归；输入与recreate必须有实际Android反馈，纯模型不替代系统事件。保留刚修的键盘唤起、候选不自动提交、UTF8、单getty、PATH、主题、用户草稿/实例凭据。日志只owned路由，不关audit/过滤用户文本。无实体键盘时14框架部分继续，物理边界保留blocked并注明所需设备。

### 九、最终验收与交付

每票完成前更新Status/证据/验收、中文implementation或prototype/review报告，标已运行/未运行/待机主；不要只把blocked文字改completed。独立规范/Spec两轴审查diff与新增untracked/许可源码，有发现限定修复再复验；设备门只能原始实测闭合。

保留/按变更运行：App testDebugUnitTest、assemble、lint、runtime contract/capability check、engine-avf:test真实20JavaExec及QEMU相关入口、Guest65/新sync10/依赖门6、工具100及本轮新增回归。具体计数以实际报告为准，不把旧数或重复运行加总。旧runner硬编码旧批次，不原样覆盖使用；新runner输出/临时目录在新批准根。

APK逐项核版本/原包名/签名/unique guest_init和MITnotice、kernel/baseinitrd/来源hash与GPL对应源码影响。保留上轮0.5.0及公开0.4.5，不自动替换Release。最终交付逐票矩阵、map/frontier/依赖图、真实设备/AVF/失败及公平基准、改善或否决证据、未解blocker、代码/版本/分支/未提交状态与资产保全清单。用户目标是剩余工作尽可能完整实现，只有真实外部阻塞可停止相关票，不因上下文/任务长就留泛泛建议。

## 主代理启动 Prompt（复制结束）

## Worker 共用派工协议

下列各worker先按主代理指针读当前policy/交接/spec/自己的完整工单与已有canonical证据，不重复他人研究。主代理在分派时必须填入本轮实际baseline、唯一外部batch、允许源文件范围/写锁、report路径、当前设备窗口状态。新worker没有读过policy时先读，不因继承人名假定权限。

- 参考根：用户明确开发根的Shizuku/Shizuku-API、原固定AOSP导出与phase5-references，只读复用，新增GET只为具体缺口/目标版本，记录fullSHA/许可，不盲pull/clone/执行上游脚本。
- 默认禁止设备写/Gradle/版本/签名/ref/其他owner文件；用固定动作包交主代理执行。可在专属外部目录跑JDK/Python/合成fixture，不能C:temp或用户盘。
- 每个结果给证据级别、实际命令/exit、当前符号、正负验收、剩余门和短结论；原始证据不用.log，有即时preservation，不含秘密/完整设备ID。
- 不代用户作ACR，不把mock/feature/permission/tool结构通过当AVF成功，不自行commit/push/发布；独立review不自审。

### Worker A Prompt：05/06 授权与身份可行性

你负责05身份/Context/FD最早门、06本机准备/client/dev权限实际验收。读取本交接、对应两票、ACR-01、已完成02/03源码与报告、A固定research-auth、canonical identity-probe（不要复制旧未修AIDL）。

先交主代理一份新QA包最小装配与动作包：只固定UID/PID/domain/服务自身permission/限定API shape，capability若会spawn服务要另标副作用；不接受任意path/command。使用API/provider13.1.5并MIT，明确非daemon、旧generation、destroy和server/App death。主代理安装/绑定/执行/收证，你负责原型/合成正负测试与结果分析。

逐步验证Context可用性、合法新QA state namespace、App私有合成RO/RW FD的接收/使用/关闭、wrong mode/closed fd/path/symlink/caller/epoch拒绝。FD通过不等VM通过；不移动真实盘、不用/proc/self/fd技巧假定兼容、不chmod私有目录。

06覆盖官方本机首次配对/启动、用户client拒绝/记忆拒绝/撤回force-stop/重启、当前user固定包两权限grant/readback/revoke/partial/death。Android grants与client/server权限身份分表，原状态记录并按约定恢复，不撤用户预先授权。验证真实App向导与ADB兜底分别是否成功；若可行，在06证据后向主代理提出自包含grant产品子票，保留未知hiddenAPI门。

写锁：prototypes/<本轮QA授权原型>、确证后授权client/permission相关新票允许文件；现生产launcher/VMengine归B，不得先增VM代理。输出本轮external/research-auth-device、05/06正式报告与ACR证据建议。结束给主代理最小可交付路线/反证、待用户选择和设备动作清单。

### Worker B Prompt：ACR-01 与 07/08 AVF调用/生命周期

你负责基于A/05真实证据收敛VM调用路线，完善ACR-01技术方案；批准后实施07真实customLinux与08有界readiness/stop。读取B固定research-performance、旧phase5票09和现engine/adapter/launcher，不重做已完成STOP/FD/persistent配置修复。

在ACR前只方案、隔离原型、合成负例和设备动作包，不写生产VMowner。比较App直反射、UserService/FD、平台native路线，但只实现最有证据的一条受控实验；Context/调用身份/UDS RPC/持久state/private path与SELinux逐门说明，libavf的LLNDK/APEX与API36符号不当普通NDK可分发承诺。以真实进程owner为准，不改包名字符串模拟身份。

ACR后只类型化固定VM操作，caller/user/package/版本/instance/session代际/FD来源与关闭、取消/death/旧回调/双启动/重复停止负例齐全。App保留用户盘/身份、代理只本轮最小能力、App/server死亡收敛，按用户最终批准实现；daemon默认true不能顺手沿用。

07从1vCPU/nonprotected/合预算RAM、新QA raw盘+pinned输入起步，create/run归属→vsock→原单reader鉴权→QA命令/resize→网络/服务→正常stop。不要让Microdroid callback成为customLinux门，系统其他crosvm不计。不能因库有forwarder/GPU就宣称全产品面成功。

08区分caller截止与底层connect结束，固定单flight/有限在途、总budget、迟到FD一次关闭与停止确认；平台mLock阻塞fixture保留，未停绝不同盘QEMU重启。设备步骤只交主代理窗口执行。

写锁：runtime/engine-avf、AVF launcher/策略选择与本轮批准代理模块；与C监督和E插桩协作，App/Guest公共入口由主代理排hunk。输出external/avf-platform-device、ACR方案、07/08中文实施/独立review输入及具体门。真实ROM不开放时准确blocked，不绕安全。

### Worker C Prompt：11/12 生命周期、会话与自有日志

你负责11剩余实际调用链/退出及12自有日志取证，复现成立后按本次授权做契约内局部修复；先补子票/验收范围，不借题重构VMowner。读取旧reproduction-11/result.json及harness，actual Controller公共seam旧A完成覆盖新B已真实red，Android/Registry/通知/exit23仍未验，不从零重复。

首先固定当前launch watcher/Registry/SessionPool/Controller上层可达顺序：exit0/23/wait异常、显式stop意图、EOF但VM活、旧代完成晚到、条件unregister、通知/cold restore；测metadata/diagnostic/新会话未被清除、停止次数，保持单reader/原token与foreground binding generation。不能一律非零当损坏或finally统一normal，不能把socket可连的替身当真实受监督owner。

12列owned report/stdout/journal+console/直接console来源；合成native/recovery/failed sinks跑次数/失败state，再安排QA Guest trace。成立后只自有provisioner/owned unit路由，常规噪音减小、失败可见/可查、无journal救援不假死。禁止关闭audit/全局stderr、App正则吞用户文本或第二终端数据面；必要新数据面升ACR。

写锁：session监督/Registry/Pool/相关测试和Guest自有日志模块；AVF底层归B、Setup/input归D，MainActivity共用需主代理锁。设备进程死亡/强停/撤回只在新QA无事务窗口由主代理执行。输出external/lifecycle-logs、原11/12证据及后续实施子票/中文报告，明确上层/合成/设备各级，保留旧失败。

### Worker D Prompt：14 输入与15系统重建

你负责14 AltGr/死键/IME退役和15真实系统recreate草稿一致性。不要把旧键盘唤起、主题/PATH等已修项重列。先查实际可用Android框架测试环境/目标手机输入法/实体键盘，手机已连接不等实体键盘具备；主代理执行设备动作。

14以真实KeyCharacterMap或受控resolver→精确UTF8 sink建立右Alt布局、左Alt meta、CtrlAlt、死键+base/space/不可组合red。不要用Android stub恒0当布局通过；composing/commit/finish只一份，退役connection迟到无效，close不自动发送未提交候选。中文/emoji/paste/焦点与前后台重绑保持既有语义；不记原始用户输入、口令、剪贴板，不固定逐字pacing。复现后最小编码/绑定修复；实体键盘缺失保留该验收门，其余框架测试继续。

15使用实际Activity recreate或可信框架：第3/4步、非默认distro/磁盘/CPU/RAM/用户名host，model/view/summary同时比对；Android自动View恢复和模型草稿分开。坏schema/旧distro/能力变化重新校验，创建中不重复下载/create，不保存密码/token/Context/Future，不增加configChanges掩盖。复现后版本化轻量草稿/saved-state局部修复，不引新全局框架。

写锁：TerminalInputConnection/相关输入纯seam、ModernTerminalView必要最小改动、SetupWizard与model/测试；SetupWizard同A授权入口协调时间窗，不同时写。不得改Guest/AVFowner/launcher或新后台任务。输出external/android-input-recreate、14/15证据与自包含修复子票、正式报告/设备窗口清单。

### Worker E Prompt：04复用、阶段recorder与09/10性能

你负责现工具复用、真实阶段事件/样本来源准备、09同资源基线，之后10只修首个有数据瓶颈。04已有100项、Python3.12+/junction拒绝、样本结构/哈希校验，不重造第二套。先读B分阶段设计与D工具README，现App没有schema全recorder，不能手造receipt或用布尔field让成功集变绿。

先为现公共launch/bootstrap/manager/run/vsock/认证/QA命令/services/stop设计有界本地typed recorder，固定monotonic时基/owner/session/generation，关闭零采集/无队列，sink失败不假ready，不保存embedded载荷/用户正文/secret；只在原单reader的认证路由收标记。跨模块插桩由owner写锁协调，源码fixture先验成功/失败/取消/迟到/错代、回退/缺门拒收，不新增第二reader。kernel标记不可安全取则not_observed，不伪造通用started。

07有主代理独立核验真实AVF闭环前，09真实样本保持0；可跑synthetic工具/收集计划。闭环后固定APK/source/ROM/APEX/UIDdomain/精确同1CPU/RAM/QA盘与输入hash、coldwarm/首次装配组、充电温控/噪声。新QA文件I/O/sync，不碰用户盘，不dropcache/改thermal，网络用可控端点，失败/超时/fallback保留。小样本n/median/range，不从公网上apt速度推CPU收益。

根据09测量选10一个变量，scope经票写清后实施前后对照及正确性/资源/失败门。没有收益给完整否决/回滚，不为“优化任务”强造百分比。native/crosvm参数存在不等App可用；CPU拓扑/balloon/THP/GPU的改变涉及新门时冻结并说明。

写锁：tools/device-validation、本轮受控event/benchmark新组件与测试；生产launcher/Guest/input修改经主代理分配原owner。设备benchmark动作由主代理串行执行，输出external/benchmark、原样本+统计/失败与09/10中文报告，不上传数据或无限重试。

### 独立 Reviewer Prompt（各派一位，不与实施者复用结论）

规范轴：基线为主代理本次记录的未提交工作区起点及后续有界diff/显式新增文件，不能用空HEAD三点diff漏未提交实现；读当前AGENTS/CONTEXT/policy/ADR与scope。核权限/IPC/FD/owner、数据/资产/版本/许可/报告真实性，区分hard规范与判断性smell；不为泛化重构提无证据偏好，不读另一轴结果。

需求轴：逐票看原始工单/新增修复子票/spec，核缺失、额外scope和错误行为；真实设备、合成、静态、未运行明确分级。AVF归属/Guest/normal stop与性能样本独立核真实性，不能仅信fixture字段/作者JSON；核原件hash/特定APK条目/固定命令证据时保护secret。不跑重复全套、不自动设备操作、不改生产，不读另一轴结论。

两轴都给每发现具体触发/文件符号或紧凑行、优先级、对应验收、必需修复；无发现也给范围/限制。原始首审/复审保留，源码冻结后审，修复后定点复核。各自只写external/review-<axis>与指定正式review报告，禁止commit/push/发布。

## 给新会话的最终提醒

当前设备已到，应马上重新核查并推进05/06，而非再次交“等待设备”的泛泛计划。允许调试AVF，但真实nonprotected/hiddenAPI/Context/FD/owner任何一门失败都要如实处理。目标是剩余票的完整实现与可验证结果；只有真实设备/外设/架构决定不足可保持blocker，所有独立可解任务持续完成。

编写人：Codex / GPT-6.1 ｜ 2026-10-16。本文件只是下一会话执行Prompt，本回合没有分派生产实现/安装/激活AVF，真实动作由新会话在上述授权范围执行。
