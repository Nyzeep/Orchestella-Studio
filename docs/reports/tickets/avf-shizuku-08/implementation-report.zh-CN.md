# 08 有界 readiness / 在途调用 / 停止收敛报告

- 票号/标题：08 / 有界readiness、迟到FD与停止/异常分类。
- 执行：Worker B / Codex GPT-6.1-sol；日期：2026-10-16。
- 主仓/分支/基线：`Orchestella_dev`，main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，接手 dirty 0.5.0/code500；保留旧36份交付，不从HEAD重做。
- 票状态：**blocked-owner-and-platform**。QA3实际取得当前ROM nonprotected声明false，现adapter不支持；正确owner、真实VM/stop/death门仍未完成。隔离fixture及P2模型修复不替代真机验收，真实AVF0，09/10不产生真实AVF样本。
- E：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/avf-platform-device/`。原件E/evidence、即时E/preservation；Class B保留至维护者验收归档，未清理。

## 1. 目标、已修边界与本轮问题

旧phase5-09已把项目session/reflective handle monitor移出阻塞I/O，保留单flight、迟到PFD/dup一次回收和持久配置核验。本票不重报这些修复。固定Android16来源显示下一层：`VirtualMachine.connectVsock`在平台mLock内同步连接，stop/close/getStatus也用该锁；native/Binder没有该方法的显式timeout参数。**这是固定源码的潜在阻塞链，不是当前ROM已复现无限hang。**

本轮验证三个独立问题：caller能否按总deadline结束；底层connect是否真的结束；VM/FD/盘是否已收敛到允许下一次启动的状态。Future完成、Thread.interrupt、stop入口或当前waitFor0均不能合并回答三个问题。

主协调已采集QA1–QA3真实原件，详[ACR报告](../avf-shizuku-acr-01/implementation-report.zh-CN.md)。QA3明确当前ROM仅声明protected（bits1，nonprotected=false），App固定manager构造METHOD_UNAVAILABLE；App合成state可写、US对所给App Context的DATA_DIR_LSTAT为OS_ERROR_13，32B App→US合成PFD可用。它们不等真实VM/FD/stop门通过；当前不追加create/run，不改protected或拿Microdroid替代。B只离线分析/文档收敛，生产代码与P2模型冻结源不动，无设备/Gradle操作。

## 2. 外部实验源码与实际接缝

| 外部文件 | 作用 | 使用的真实部分/替身 |
| --- | --- | --- |
| fixture-src/android/system/virtualmachine/VirtualMachine.java | 合成shared-mLock平台形状，connect持latch，stop入口与body分计数，忽略interrupt直到finally release | 独立自研模型，无Android/Binder/native实现；非复制上游源码，非APK |
| fixture-src/workerb/PlatformLockProbe.java | 调当前生产engine+adapter，观测stop被平台锁阻挡与caller deadline缺口 | 生产engine/adapter/launchSpec/API均原字节；只外部替代平台VM，复用7份现有JVM test support |
| fixture-src/workerb/BoundedReadinessProbe.java | 最小候选状态/预算/在途/FD/stop/盘lease模型 | 全部合成，无生产owner、特权IPC、真实FD、Guest或控制reader |
| run-fixtures.py | 用固定D:/JDK17编译/执行、有界45s runner、逐项raw JSON/log.txt即时保全 | 无Gradle、网络/adb、依赖安装或系统修改；java/tmp/TEMP均E/temp |

编译只读限定的纯Java runtime命名空间与7个test support，逐输入SHA/字节见 `compile-inputs.json` / `compile-lease-inputs.json`。不扫描或归档整仓/.git。初次运行源码在E/fixture-history/initial及preservation保留，随后只补caller截止red和全App盘lease。首轮bounded-green末尾曾把输出计数硬编码为9，实际PROTOTYPE_PASS是8行；原件保留，最终改为运行计数，**最终9组以bounded-lease-green为准，不能把两轮相加成17组。**

## 3. 实际运行记录与 red/green 层级

环境：Python3.12.9、javac17.0.10。以下命令均是 `python E/run-fixtures.py <mode> [label]`；argv/exit/stdout/stderr在同名 `E/evidence/<label>.json` / `.log.txt`，生成后即时保全。

| mode / label | 实际exit | 证据层级与结果 |
| --- | --- | --- |
| compile / compile | 0 | JVM编译；未运行Android或Gradle |
| lock-red / lock-red | 1 | **未改生产+合成平台锁的真实JVM red**：connect latch未释放，stop到平台入口，但期待200ms内返回得到TimeoutException；不是生产缺陷修复green |
| lock-observe / lock-observe | 0 | 同一模型正确观测：stop_entry1、stop_body0、in_flight1、platform_stopped UNCONFIRMED；interrupt无终止证明；现waitFor返回0 |
| bounded-red / bounded-red | 1 | **错误设计假设red**：fake clock到deadline后caller Future确实完成，底层仍在途1；断言“Future done ⇒底层结束”以AssertionError失败。不是旧生产代码red |
| bounded-green / bounded-green | 0 | 首版候选模型8组PASS，末尾硬编码计数误写9；保留历史，不用于最终计数 |
| compile / compile-lease | 0 | 添加caller截止及跨代lease后编译 |
| caller-deadline-red / caller-deadline-red | 1 | **未改生产+合成阻塞的真实JVM red**：尚未发stop时getter无caller截止，外部get(200ms)超时；清理前才发stop、finally释放latch |
| bounded-green / bounded-lease-green | 0 | **候选模型最终9组green**，详细见下节；未接入生产，不证明ROM可用 |

lock-red/lock-observe/caller-deadline-red三次都在finally释放latch并join：迟到原始PFD close1、未dup、平台stop/close body各1、fixture worker0。证明测试自身结束和既有迟到回收保留，不能称“生产平台stop已修复”。200ms/150ms只是合成观察窗，无设备延迟含义。模型中所谓永久阻塞是在观察窗口持续阻塞，最终由测试释放以结束进程；未宣称实际永不返回的方法已可取消。

## 4. 最终9组合成行为验收

| 组 | 真实模型行为断言 |
| --- | --- |
| deadline/finite inflight/singleflight | 同端口共享一个future；不同端口BUSY、零队列；deadline退役caller但liveConnects仍1；100次后续请求全拒；重复stop只一个平台调用；峰值connect1，总在途worker至多connect+stop2；盘lease不释放 |
| late FD/stop proof | 释放connect后迟到FD关闭一次；stop仍在途则不放盘；stop返回仍需current-generation独立owner停止观测；旧stop观测拒绝；unexpected stop分类保留 |
| total budget across stages | 初次瞬时失败、70单位退避后只剩30；成功connect/鉴权不重置deadline；100到期command拒绝、FD回收，services不能假ready |
| attempt cap | 总deadline未到也最多2次连接尝试，第3次拒绝，无无限重连 |
| permanent refusal | 权限拒绝一次即退役，无第2次连接 |
| bad/null FD | 返回null不能ready，future异常、session退役 |
| readiness/order/EOF/cancel/stale | 旧代请求不发底层工作；run/connect/auth/command/services逐门，未请求payload callback；乱序/旧代拒绝；EOF但未stop=TRANSPORT_EOF_VM_UNKNOWN；重复cancel一次close，迟到回调不能ready |
| close failure | 迟到FD close抛错只调用一次，保留closeFailed；即使stop返回/观测齐也不假报resources released、不放盘 |
| App-wide lease | 旧代cancel但connect未结束/stop未确认，新session BUSY且无底层调用；全部确认后只释放exact old lease；迟到old cleanup不能清除已持有的新owner，旧回调不能ready新代 |

此处SyntheticFd是内存计数对象，不是Android内核FD；不能替代A的Binder传FD/模式/来源/关闭证据。fake clock/tick显式驱动，不是生产定时器调度保证；独立stop observation是模型输入，不是设备证据真实性认证；App/server death、撤回、资源FD真实关闭、协议caller/user/package/version都需要后续真实入口与负客户端。模型没有读任何Guest数据，单reader/token不变只是边界未被触碰，不能签产品鉴权成功。

## 5. 生产方案的有限收敛边界（待ACR）

具体owner/IPC/FD/state/caller提案统一见ACR报告§5，B不在此复制一套新契约。实现时建议：

- 启动时只建立一个monotonic绝对ready deadline；连接/queue/backoff/鉴权/固定QA命令/services只用剩余budget。候选最小方案每session所有平台connect最多1在途，零排队、有限attempt；同端口singleflight，额外通道不能无限耗线程。
- caller截止/取消立即退役generation、拒绝waiter与新请求，但**底层额度直到实际call返回并回收迟到FD才释放**。Future.cancel/interrupt只是意图/调度，不写成平台取消完成；不超时就起新代理/线程。
- stop先发布意图，最多一个stop worker；stop return、当前owner的stopped观测、所有资源与在途回收分别记录。`getStatus`也可能共享平台锁，不用无穷额外status线程“确认”。当前adapter hostStopped使waitFor0更要与平台观测分开。
- App范围盘lease跨代保持，旧connect/owner未知不启动同盘QEMU或新AVF；stop未确认标STOP_UNCONFIRMED。EOF仅transport ended，非normal；expected guest shutdown与host强停/异常/未知分类。
- 平台mLock长期占用时，不承诺Java层能够终止。若ACR批准单独进程终态收敛，先验证App lease/Binder death→owner→RpcBinder client FD→virtmgr/VM的真实终态及恢复；kill/remove返回或pidof无QA不等盘安全。未证实就保持阻塞和诊断，不强杀用户事务或系统其他VM。

未来兼容平台的正常/异常窗口预案在 **E/device-action-packet.zh-CN.md §2–3**；当前ROM因nonprotected=false不放行这些VM动作。须在兼容平台与ACR具体owner边界获批后先做07正常对照，再单一新QA异常窗口；ready120s/stop观察10s只是原建议事前预算，不是平台保证。death/撤回仅无装配/包事务，由主协调独占执行并保留原权限，不能用QA进程终态替代VM收敛。

## 6. 与原票验收的对应及剩余门

本轮fixture覆盖成功、late、观察窗口内持续阻塞、cancel、null/权限、stop/旧代、EOF与资源失败；生产入口、真实错误FD/真实death未运行。caller截止与底层结束有明确反证；**真实平台mLock收敛边界尚未证实**，所以08不能完成。迟到FD计数/停止幂等只有模型及旧生产JVM层通过，不冒充Binder FD或ROM。

生产implementation仍冻结于ACR+07。QA2的USER_SERVICE_CALL_FAILED / OPERATION_FAILURE及其不完整分项是历史失败，完整保留，不被QA3后续结果覆盖；不能追溯将QA2通用错误签成Context/FD根因。

**QA3当前收敛：** `device/qa3-manager-capability-receipt.json`中US的serviceCapability_capabilityResult=QUERY_RETURNED_DECLARATION_ONLY、serviceCapability_capabilityBits=1、declaresProtected=true、declaresNonProtected=false；App在MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE。实际返回的是声明，当前nonprotected adapter对此ROM不支持；无create/run，不能拿protected或Microdroid代替。顶层managerInstance=NOT_CREATED/capabilityBits=NOT_QUERIED是前置identity快照，后续真实查询按各serviceCapability_/appCapability_字段判读。

`device/qa3-context-fd-receipt.json`独立证明App NEW_QA_STATE_WRITABLE、US对所给App Context的DATA_DIR_LSTAT为OS_ERROR_13（EACCES）；32B App→US合成PFD正向读写、receiver副本关闭和caller原件保持true，所列mode/type/same/replay/symlink/stale/end负门拒绝。closed FD只证明在发送或接收边界被拒绝，错误分类仍OPERATION_FAILURE；不编造具体内核关闭结果、异步死亡回收或真实VM FD生命周期。Context路径拒绝与FD可用并存，合成FD成功不能接管App state/path或证明Java path-based config/virtmgr/crosvm可用；真实不同UID负客户端与VM owner仍未验。

因此07/08正确owner设备门保持blocked，本ROM不放行VM/真实stop窗口；09/10不产真实AVF样本或收益，真实AVF0。未来先有兼容nonprotected平台及合法Context/state/G4 FD入口，再按ACR具体边界裁定owner与死亡收敛；不是泛问用户是否批准继续AVF。两receipt、QA3 APK/source/ROM绑定和完整分项以ACR报告§2/§4–5为canonical解释。各reviewer已获P2新seal，独立复审结论由各自报告承担，B不代签。

## 7. 修改、风险、回滚与合规

修改仅08原票补记、本canonical报告、B的ACR/07报告及E隔离fixture/runner/动作包/证据/保全。生产engine/API/adapter/launcher只读，修改前hash及原票bytes在E/before-source.json；最终精确清单/保全/无新.log门为E/delivery-manifest.json、output-validation.json。未改map/spec、ADR/policy、其他owner、Gradle、版本/APK/签名。

风险：green模型被误读成可交付owner、caller结束误作VM结束、跨代遗留worker造成资源累积。报告/动作/lease明确分层，下一步用实际ROM/owner负例核实；未知就保留阻塞。回滚只本批B文档/原票增补和外部合成源，按hash保留并行改动及所有原件，不reset/clean，不删旧票09成果/用户盘/实例/持久state。Class A/旧APK/对应源码与历史证据完整保全，无清理。

新fixture为自研、不进APK，无依赖下载或许可引入；参考AOSP固定源码只读用于接口/锁事实，未拷实现。宿主写入限批准D根，所有临时输出E/temp；无adb/UI/Gradle/network/C盘项目写入或系统设置变更，无内容出站。main/HEAD不变，commit无、branch/worktree/ref操作无。Architecture Change Policy：新owner/特权IPC由ACR-01继续承载，本轮只有方案/模型，未实施架构变更。

## 8. 独立 Spec 审查 P2：ready 后观察到 stop 的定点修复

### 发现、范围及真实 red

独立 Spec reviewer 指出：候选模型的 `Session.observeStop` 原先只写 stopObserved/stopEvidence/exitClassification，未退役 ready 或释放已发布 FD。原9组覆盖先cancel/stop再观察的顺序，遗漏 `connect→authenticated→command→services→ready=true→observeStop(current,false)`。这是真实候选模型缺陷，影响08的stop/death与失败FD一次关闭验收；不是Android/生产或ROM结论。本次只修外部候选模型、对应runner/封板记录和本报告，不动生产代码、原票/ACR/07、设备/Gradle/版本。

修改前精确字节/hash、08报告和旧seal逐文件hash在 **E/p2-stop-observation-before.json**；局部副本在 **E/fixture-history/p2-stop-observation/before/**，即时preservation。新增测试先通过模型既有 `connect/phase/ready/observeStop` 入口走上述顺序，此时模型未修。实际运行：

| 命令（均为 `python E/run-fixtures.py …`） | 实际exit | 观测 |
| --- | --- | --- |
| `compile compile-p2-stop-red` | 0 | 新顺序回归编译成功，observeStop原行为不变 |
| `stop-observation-red p2-stop-observation-red` | 1 | `ready_after_stop=true fd_closes=0 classification=UNEXPECTED_OWNER_STOP`；AssertionError `CURRENT_STOP_MUST_RETIRE_READY_AND_CLOSE_PUBLISHED_ONCE` |
| `compile compile-p2-stop-green` | 0 | 一行模型修复后编译 |
| `stop-observation-green p2-stop-observation-green` | 0 | 同一顺序得到ready=false、fd_closes=1，分类保留；重复stop/cancel不二次close |
| `compile compile-p2-stop-regression` | 0 | 新增正负顺序回归编译 |
| `stop-observation-green p2-stop-observation-regression-green` | 0 | 新增5组全部通过 |
| `bounded-green p2-existing-nine-green` | 0 | 原有9组重新运行，9组全部通过 |

各命令、exit、stdout/stderr、raw SHA在同名E/evidence/*.json与*.log.txt，逐次即时preservation。失败时try-with-resources仍关闭模型并join worker；真实red输出与修复前含测试源码另保存在 **E/fixture-history/p2-stop-observation/red/**。原bounded-lease-green及旧首审seal不覆盖，不把多次运行累计成通过数量。

### 最小修复与验收

模型修复仅在 `observeStop` 的当前generation检查之后增加 `retire(Reason.CLOSED)`，复用现有退役路径清除ready各门、拒绝pending caller并一次close已发布FD；后面的stop证据和expected/unexpected分类保持。**不修改liveConnects、flight的底层归属、platformStopReturned或盘lease释放条件**。旧generation仍先抛STALE，不能触及新owner。

新增5组分别验证：

1. 当前代ready后异常stop：立即非ready、published FD一次关闭，重复观察/cancel不重关，后续phase/connect拒绝，stop观察单独不放盘。
2. 当前代ready后预期关机：同样退役并保留EXPECTED_GUEST_SHUTDOWN，完整既有停止门后才允许放盘。
3. 已发布FD关闭失败：只尝试close一次、保留closeFailed，完成其他stop门仍不释放盘lease。
4. stop观察发生在connect仍被latch阻塞时：caller退役而liveConnects仍1，尚未返回FD不提前close，新session被lease拒绝；只有真实connect返回后迟到FD一次回收，再满足独立stop门才释放lease。
5. 旧代stop发送给新代ready owner：STALE，ready/FD/分类/stop证据及lease均保持，随后仅当前代stop可退役。

P2阶段最终结果是**新增5组 + 原有9组回归均通过**，仍仅为JVM_SYNTHETIC，不证明真实死亡、FD内核回收或ROMstop。P2修复没有改变生产实现或架构；该阶段QA3尚由A准备。随后到达的QA3真实manager/capability/Context/FD结论已独立收敛于本报告§1/§6/§9，不追改P2旧seal或将其模型结果升格为设备成功；08仍blocked。

### 新封板与独立复审指针

本次新seal标签为 **p2-stop-observation-fixed**，路径 **E/seals/p2-stop-observation-fixed/**；其output-validation.json核对新red/green、5+9组、旧seal字节不变、只读生产文件与原票内容、保全和无新.log门。E/delivery-manifest.json更新为本次canonical索引，旧 **seals/qa1-independent/** 与 **seals/qa2-failed-accurate/** 不覆盖。修复后源码和runner冻结于新seal的 `external/`，本报告冻结于其 `source/`。

首审原bytes仍在修复前不可变副本 **E/fixture-history/p2-stop-observation/before/external/**：`fixture-src/workerb/BoundedReadinessProbe.java` 为19,531B、SHA256 `ef0797640ec215eeb042a85cba5185f44ac5547f271e9b86168eb84a9fd4845a`；`run-fixtures.py` 为5,326B、SHA256 `e0deec9034d906a9aef5e2fbd3ff0342626241f7f0b89c2ea0346853445feb7d`。与Spec reviewer提供的qa1-independent首审SHA逐字节一致，preservation下对应不可变孪生也一致；不得拿已更新的live源码或其live保全副本替代首审原件。

给原Spec reviewer定点复审范围：外部 `fixture-src/workerb/BoundedReadinessProbe.java` 的 `Session.observeStop`、`observedStopRetiresReadySession`、`expectedStopRetiresReadySession`、`observedStopDoesNotEndInflightConnect`、`staleStopDoesNotRetireReadyGeneration`；对照before/red局部源及 `evidence/p2-stop-observation-red.json`、`p2-stop-observation-regression-green.json`、`p2-existing-nine-green.json`。复核当前代退役/一次close、expected/unexpected分类、旧代无影响与底层在途/lease未被stop观察提前清除。实施者只交证据，不代独立reviewer签复审通过。

## 9. QA3 最终收口与新封板

本次仅按主协调新到的两份QA3真实原件收敛三份canonical报告、三张原票与动作包，未改变P2模型、其runner或任何生产代码，也未运行设备/Gradle/新JDK测试。能力声明、Context拒绝和合成FD成功是三类独立实证，准确字段与APK/source/ROM绑定统一见ACR报告§2。

当前ROM的US实查nonprotected=false，对现adapter不支持，因此不启动VM来“补验”07/08；protected声明不等protected VM已成功，Microdroid也不替代custom Linux。App新QA state可写、US对该App Context的DATA_DIR_LSTAT为OS_ERROR_13、32B PFD正负门通过，仍未证明合法平台state namespace、Java path-based配置FD入口、US→virtmgr/crosvm或VM死亡收敛。**07/08正确owner设备门继续blocked，09/10不产生真实AVF样本，真实AVF0。** 当前保留R0软件执行；未来兼容平台/G4入口证据齐备后，用户只需裁定具体Context/path/state/FD owner、秘密可读能力与迁移回滚，不泛求代理生产批准。

修改前6份文档及外部动作包/索引/runner字节、全部旧seal逐文件hash在 **E/qa3-convergence-before.json**，原bytes与孪生在 **E/fixture-history/qa3-rom-convergence/before/** 及preservation。新最终seal为 **E/seals/qa3-rom-nonprotected-unsupported-verified/**，冻结本次三报告/三原票/动作包/封板脚本、两份共享receipt引用hash与分项观察；QA1/QA2失败、首审原bytes和P2 seal全部保持原字节。最终校验复核旧seal/模型/runner/生产五文件未变、共享原件保全、声明字段和源快照绑定，沿用P2已得5+9组结果，不把校验原件当本次重新运行测试。各reviewer已收到P2新seal，本次交QA3证据变更范围供其定点复核，B不代签独立审查。

首次QA3封板 `seals/qa3-rom-nonprotected-unsupported/` 实际exit1/校验未通过：新增章节比较器用DOTALL配合 `(.+)` 误把多段正文读作标题，导致三原票章节比较误报。失败seal及精确hash保留，诊断原件为 **E/evidence/qa3-initial-seal-failure.json**；修正仅限封板解析器，标题限定为非换行字符。定点比较原票实质验收/约束段落为0差异，修改前原bytes孪生亦一致；没有通过改变工单要求来消除失败。最终使用独立新标签 **qa3-rom-nonprotected-unsupported-verified**，不覆盖首轮失败或任何旧seal。

报告人：Worker B / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
