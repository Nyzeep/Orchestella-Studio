# 05 独立 Standards 首审：冻结 QA1 / QA2 身份、Context 与合成 FD

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。首审结论：**request-changes，2 项 P2，未发现 P1**。随后按新冻结包定点复审，**S-A-01/02 均在所述源码范围闭合**；完整 QA3 新增权限/manager 功能及真实设备门不在本次批准范围，详末节。首审原件保留。

## 背景、固定点与实际范围

唯一活动 effort 为 `.scratch/orchestella-avf-shizuku/`。分支/基线为用户指定 `main@428860ad9a1cb335f8be78c157b2ff57faf2a6da`，本轮从已封板的 dirty 0.5.0/code500 工作区起步；36 原文件以外部 batch-01/baseline-source.json 为准。没有用 HEAD 三点空 diff 代替未提交实现审查，没有新建 worktree。

外部批次根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`，下文记作 batch。固定点为 A 的 before-source.json、identity-v1-frozen-source.json、identity-v2-frozen-source.json。QA2 当前 canonical 已开始后续实现，本次逐行审查使用 **research-auth-device/preservation/qa2-source-before-qa3/**，13 项 bytes/SHA 与 QA2 冻结清单全部匹配。只读核验的脚本、结果与即时孪生位于 batch/review-standards/{verify-frozen-inputs.py,first-pass-verification.json,preservation/}。

已查：冻结原型全部 Java/AIDL/Manifest、build_qa.py、A QA1/QA2 固定动作包、32B 合成 FD 来源/模式/关闭、实际 ProbeSession/SyntheticFdGate 的合成 harness，QA1/QA2 编译输入清单、未签与已签 APK 关键 ZIP 条目、MIT notice、QA1 四份实际 receipt；主协调后来明确提供 QA2 实际失败与 B 增量 seal 后，另核该新原件。没有读取另一 review 轴文件或结论；本报告为 Standards，不代 Spec 轴逐票验收。

## 硬规范与行为发现

### S-A-01 · P2 · 旧 disconnect 回调可退役新绑定并覆盖新证据

- 文件/符号：冻结 `prototypes/avf-shizuku-identity-probe/src/main/java/io/github/nyzeep/orchestella/researchauth/ProbeActivity.java`，`bind` 内 `onServiceDisconnected`，常规 1 起点行 **232–236**。引用对象是上述 QA2 保全副本，不是后续 canonical。
- 实际触发：绑定 A 被 remove；新绑定 B 建立后，A 的异步 disconnect 才到达。`onServiceConnected` 有 `visible / binding == epoch / connection == this` 检查，disconnect 却直接 `service = null; ++epoch; show(...)`。旧事件会改变 B 的 epoch/状态与固定 receipt，B 的有效完成随后因 binding 不匹配被丢弃。这是冻结源码的明确事件序列问题，未声称已在设备复现。
- 规范来源：`AVF_SHIZUKU_REMAINING_PROMPT_20261016.zh-CN.md` Worker A 关于旧 generation、App/server death 的门，以及 reviewer 的 caller/epoch/真实性要求；活动 spec 的生命周期/Binder 代际规则；`REPORTING_STANDARD.zh-CN.md` §0、§6 对真实结果的要求。
- 必要最小修复：disconnect 先核回调所属 connection 与有效代际；旧绑定事件不改变新 service/epoch/receipt。增加通过实际 Activity 使用的接缝验证 remove/rebind→old disconnect→new completion 的定点行为回归。新源码另冻结后复审，不回写本次 seal。

### S-A-02 · P2 · 文件实验后 receipt 仍声称没有打开或创建文件

- 文件/符号：冻结 `ProbeSnapshot.java` **33** 行的 `filesystemAndVmState`；`ProbeActivity.java` **189–215、266** 行；`QaFdClient.run` **14–16** 行与 `QaContextState.inspect` **29–36** 行。行号均为常规 1 起点。
- 实际触发：按钮5先取得 identity snapshot，写入 `NO_FILES_OPENED_OR_CREATED`；随后创建 Context namespace/sentinel 与合成 FD/symlink。成功路径不会覆盖原字段；后续异常也只向保留的 snapshot 加 `callFailureKind`，局部阶段结果丢失。因此整个最终 receipt 可同时表示实验失败和“没有文件副作用”。主协调后续提供的 QA2 原件确实保留了这一字段，但原件本身不能定位失败阶段，也不能据此断言已经/没有创建哪些文件。
- 规范来源：`REPORTING_STANDARD.zh-CN.md` 禁止虚报、数字/结论可追溯；`DEFENSIVE_CONSTRAINTS/README.md` 每个任务最小安全门与证据分层；20261016 Prompt 的 Context/state/FD 各门独立取证、固定失败类别要求。
- 必要最小修复：将该字段明确限定为最初 identity-only 观测；Context/FD 阶段单列固定 stage/result/异常类别和可用 errno，失败保留此前已完成阶段及副作用状态。禁止回显任意异常 message/path。补在创建 sentinel 后失败的定点回归，证明不会重发“整个实验零文件副作用”的陈述。

## 已核规范、证据真实性与边界

- 身份与权限：服务仅接受 shell UID2000 与固定 QA package；owner UID来自真实 supplied Context，IPC caller 来自 Binder；协议、正且递增 epoch、重复 begin、end/retire 后拒绝在实际 ProbeSession 中实现。权限自查先 clearCallingIdentity，再 finally restore。client consent、App 两权限、服务自身两权限分开。wrong-caller 的 harness 是合成值，不能当跨 UID Binder 实测。
- FD：App 在新私有 namespace 创建固定32B合成文件，0700/0600、O_EXCL/O_NOFOLLOW、lstat/fstat inode、owner/regular/size 校验；服务按 fstat/F_GETFL 验 exact RO/RW、不同对象、replay 和固定哨兵，最多接纳一对。收到的 Binder 副本在 service finally 关闭，含 caller/epoch 拒绝；App originals 用 try-with-resources。未接受任意 path/command，不读取真实盘/embedded initrd。真实 Binder/SELinux/FD关闭语义仍是设备门，metadata harness 不能替代。
- Context：只在实际 Context dataDir 下创建新的固定 QA 子目录，不伪造 package/Context、不 chmod 私有目录、不使用公共盘或 `/proc/self/fd`。没有 manager/create/run/VMstate 修改。App state 可写不等 shell state 可写，更不等持久 VM namespace 已证。
- 单在途与结束：静态 IN_FLIGHT 覆盖本 App 的 Binder 工作；UI deadline 只退役，不声称底层取消；UserService daemon=false，remove=true 调 reserved destroy。App/server death 与撤回仍需分别实测。
- 版本/许可：QA1 `0.1.0-identity/code1`、QA2 `0.1.1-identity/code2` 为主协调显式指定的独立 QA 版本；未改变生产版本。两已签 APK SHA 分别为 `ba80b7820dd0a612520c2a1801c64d97307fa49a9c02c859b6c7f1a0ec053160`、`e2bba6148846004035e7ca82003005f4644381bf4c12b95bbd99d0b72e5febf4`。只读解析编译 Manifest 确认 QA 包名及版本，关键 dex/Manifest/notice 与各自 unsigned 包一致、无重复条目、无 native/Guest 资产。四个 Shizuku SDK 13.1.5 与完整 MIT notice 记录一致。证书仅核既有主协调记录，不声称本审查重新运行签名验证工具。
- QA1 原件：`device/identity-v1-app-receipt.json` 为 CLIENT_QUERY_FAILED；授权后的 App 为 UIDAPP_UID/untrusted_app，MANAGE/CUSTOM denied、client granted；US 为 UID2000/shell、自身两权限 granted、builder member visible，manager NOT_CREATED/capability NOT_QUERIED。remove-confirm 的精确 QA pidof exit1/无输出只支持该进程未检出。未把 shape、权限、进程 remove 当 VM/Guest/死亡收敛成功。
- QA2 更新：初始审查授权时仅编译。主协调后来给出 `device/identity-v2-synthetic-gates-receipt.json` 后，只读核到取证 exit0、内部 USER_SERVICE_CALL_FAILED / OPERATION_FAILURE。没有独立 state/FD 结果，不能推出 EACCES、SELinux FD拒绝或路线不可行。真实 AVF 样本仍0。

## 启发式 smell：判断性，非硬违规

按项目 code-review 技能核过 Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。项目明确的隔离实验、固定小协议与 provenance 字段优先；没有提出有充分触发证据且值得扩大本轮改动的 smell 发现。未把格式、编译器或既有工具已约束项列为人工违规。

## 限制、未完成与后续

QA1 旧 **ProbeActivity.java / IdentityProbeService.java / IIdentityProbe.aidl** 的精确源码 bytes 未匹配找到；仅有其冻结 hash、编译记录、classes 与 APK。**三份 QA1 源码无法逐行复核**，本审查没有把 QA2 冒充 QA1。build_qa.py 冻结版会记录 src hash，却未随当次构建立刻保全精确源码；后续构建应即时双份保存输入源 bytes，旧证据保留，不能事后重建冒充原件。此项作为首审追溯限制披露，当前报告不扩大为“QA1 已完整源码通过”。

QA3、后续 B 新模型、C/D/E 其他实现均不在本首审批准范围；22 由另一个新增授权包和独立报告承载。未执行设备/adb/UI/安装/Gradle、完整测试重跑、网络、版本/生产修改、commit/ref/worktree操作。`.phase5` 机主安装确认不属于本报告的已验事实。

本轮 Standards 修改仅本正式报告、指定 review-standards 核验脚本/摘要与 preservation；没有生产回滚需求。风险是旧代事件污染证据和 snapshot 副作用字段误读。后续分别按 S-A-01/02 最小修复、新 seal 与定点复核闭合；05真实 manager/nonprotected/FD/VM、06设备矩阵及 ACR门保持未闭合。新 owner/IPC 将触发 Architecture Change Policy；本审查未实施架构变更或变更 accepted ADR。Commit：无；branch：沿用户提供 main，不更改。

## 新冻结包定点复审：QA3 中的首审 P2

主协调随后交付 `research-auth-device/identity-v3-frozen-source.json`。本次只复核 S-A-01/02 及输入保全，不把 QA3 的新 grant/manager 能力整体签成已审，更不签真实设备通过。19份精确输入在 `qa3-compile-02/source/` 与 `preservation/qa3-compile-02/source/` 全部匹配 seal；相关原件与孪生核验记录在 batch/review-standards/p2-recheck-verification.json。

- **S-A-01 源码闭合**：新 `ProbeActivity.onServiceDisconnected`（常规216–219行）调用实际 `ProbeConnectionEvents.disconnected`；后者先校验 visible、binding==activeEpoch、sourceConnection==activeConnection，再执行退役副作用。旧代、同epoch错connection与停止Activity均不能改变新绑定。`disconnect-race-red/result.json` 的java exit1/late A disconnect断言、green的exit0/4场景及QA3回归同源接缝均核过；Android异步投递仍未在本review重跑。
- **S-A-02 源码语义闭合**：新 `ProbeSnapshot.inspect`（33–34行）把无文件操作限定为initial identity snapshot；Activity进入合成阶段明确另报effect ledger；`QaFdClient`各步分开捕获固定类别并保留先前结果，`QaContextState`及`QaSyntheticFiles`用实际创建/写入后记录的单调effect账本。后续失败不抹掉已观察mkdir/create，异常只报固定类别/errno，不回显path/message。实际StageLedger 6项回归验证后来失败保留先前事实与不可变snapshot；这不是内核文件系统故障注入，真实Context/FD结果仍须单独原件。
- 输入保全改进：新build_qa.py在编译前即时双份保存限定src、脚本和notice，编译源/Manifest均来自本次snapshot，避免后续编辑改变构建输入。已核新compile record aidl/javac均0；QA3 72项是24+11+27+4+6个合成检查，未把它们当Android权限/FD成功。本次只使用其中callback/ledger结果支持上述两项闭合。

复审结论：**首审两项 P2 在明确的新冻结源码范围闭合，定点复审 approve**；完整 QA3 新增功能、05/06真机矩阵、G4/ACR/Guest/stop不在这项批准内。QA1三份精确旧源码缺失仍是不可追溯限制，不能被新保全倒填。`.phase5` code502的OEM“继续”确认仍待机主，实际安装版本按主协调为501，本报告不记安装绿。首审报告的原字节保持在 batch/review-standards/first-pass-reports/ 与 preservation；本正式报告仅追加复审，不删除历史发现。无新增P1/P2、smell或生产修改，无设备/Gradle/版本/ref操作。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
