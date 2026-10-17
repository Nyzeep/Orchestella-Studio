# 17 独立 Spec 定点复审：停止目标、退出监督与排他启动 admission

结论：**approve（本次冻结三项 P2 的源码与 JVM 证据范围），无新增 P0/P1/P2**。原 Spec S-04、S-05 在该范围关闭；主协调转交的 admission 覆盖有效代际问题亦已独立复核修复。503 新包集成、真实 Android FGS/UI/停止与 QEMU/AVF 设备门继续单列，本报告不签整票 completed。`spec-review-20261016.zh-CN.md` 首审原件保持。

## 背景、冻结对象与独立性

审查者：恢复后的独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol，2026-10-16。已完整读取项目 AGENTS/CONTEXT、指定全部 policy、两份 20261016 剩余交接/Reviewer 协议、resume-01/dispatch.md、原 Spec closeout 与 08/17/18/19/20/22 同轴报告；本轮重读17原票和冻结实施报告。已审的 C18 等独立范围沿旧结论，不重复全套。未读另一轴报告，admission 缺陷仅接主协调转交，实际判断来自本次冻结源码、测试及原始执行记录。

沿用用户给定唯一源码 `Orchestella_dev`、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，原 dirty 0.5.0 与本批502实现完整保留。没有从 HEAD 新建实现基线或执行 Git/ref/版本命令，无 commit。503 构建由主协调另行执行，本次不以旧502结果签三P2后的新包通过。

主协调发送精确指针后才开始本复审：`batch-01/lifecycle-logs/p2-stop-dispatch-fixed/final-freeze.json`（Worker C resume-01，created_local=2026-10-16T21:46:37+08:00）。比较 `before-502/` 与 `frozen-source/`，局部 patch 为 `changes-from-502.patch`（7997B / SHA256 `1a1182c9abcd0e9ad7f47c0bcb878f405be0449400605560873dcb0a780e7a50`）。17项输入中仅 MainActivity、InstanceLaunchGuard、既有 InstanceLaunchSequenceTest 三份变化；其余14项与原 C seal 字节相同，其中 Guest 三项继续归18原审结论。

外部根 E 为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/`。本次定点核验 `E/resume-01/review-spec/frozen-evidence-checks.json` 的 c17 部分及脚本，与本报告副本均有 `E/resume-01/preservation/review-spec/` 孪生。

## 三项修复逐项复核

| 项目及原验收 | 冻结行为 | 证据与结论 |
| --- | --- | --- |
| S-04：17第17行“错代请求不能操作新session”，第20–21行旧代不改新会话 | MainActivity.requestInstanceStop 第1222–1249行先在用户动作调用时取 requestedSession，再进入 IO；IO 校对象身份，typed Registry/Pool gate 再校 expected owner，不在异步执行时另选当前 owner | **关闭**。A点击后B替换，force/graceful都不改变B的停止意图、Registry、metadata或资源；点击时null也不会操作稍后注册的owner；当前force仍仅派发一次且不提前写STOPPED |
| S-05：17第16、18行真实退出一次收束、wait失败保留owner；注册后通知异常不能跳监督 | MainActivity 第1562–1590行取注册 session/generation 后先 Thread.start watcher，再执行第二次 FGS START。通知刷新抛错时，afterLaunchFailure仍保留已有owner，而已启动的watcher继续等待真实wait结果 | **关闭**。exit0→STOPPED、exit23→CRASHED保原码；owner退休、close和FGS STOP各一次，不因通知异常terminate未知VM。wait失败原保留语义未改 |
| admission排他：拒绝的重复启动不得覆盖当前已准入A的generation/运行槽 | InstanceLaunchGuard.begin 第21–27行用putIfAbsent，失败直接null且不写LATEST；Main第1505–1512行接null后直接返回，未进入beforeLaunch/launch或其他代失败清理。条件end仍校token | **独立核对修复成立**。B在A尚未register时被拒；B失败清理与null/旧token不能结束A，A注册沿用原generation，exit后正确收束一次 |

Main整文件冻结99493B / SHA256 `589d55fb5386cae4f73ca4ab8b24d5ea7134eafa75e959515211dfc60a7bfc66`；Guard2168B / `d9352e78d61bdc61367583f245ccefe224d2b132cef2b57edff9e55f6250c3e7`；SequenceTest30468B / `f39f747ced2d89abbe91b2fd12836341c909c5326ce53a6da22a1d4d60740e93`。这些是新freeze字节，不用当前可变工作树行号解释旧502发现。

S-04 的提前对象校验之后仍保留 Pool monitor 内的 expected-session 校验，因此“预检后B替换”不会越过typed gate。S-05 的变更不把通知失败当VM死亡；只调整监督建立与通知投递顺序，未新增reader/owner/IPC。若VM极快退出先投STOP，现有FGS START处理还需当前guard或Registry generation，已退役owner的迟到START会被拒；源码门保留，不把合成结果当真实系统服务调度通过。

admission新增测试使用实际Guard/Controller/Registry/Pool，检查被拒B零反馈、A仍RUNNING/同generation、注册后确认退出、close及反馈各一次。旧“先A后B而B尚未注册”测试补显式释放A admission，再begin B，与新排他语义一致；它没有删除对旧A完成不得清B的断言。没有用改测试掩盖重复启动被错误接纳。

## 原始 red/green 与来源关联

reviewer只读已有原件、源码与定点hash，没有重跑测试或编译。

| 固定命令/原件（相对lifecycle-logs） | 真实结果 | 证据层级 |
| --- | --- | --- |
| `p2-stop-dispatch-fixed/run-main-dispatch.py --label red-s04 --scenario stop` | kotlinc0/run1；B_terminations=1、B_intent=FORCE，断言queued A click terminated replacement B | 原Main精确片段、真实coroutines/Java生命周期；Android边界合成 |
| 同runner `--label red-s05 --scenario notification` | kotlinc0/run1；exit0后仍RUNNING/owner_present=true/FGS_stops=0 | 注册后通知异常跳watcher的原反证 |
| `run-lifecycle.py --label p2-red-admission --classes InstanceLaunchSequenceTest` | javac0/JUnit1；B本应被拒却返回新generation | 实际Java公共接缝及合成owner/FileStore |
| `run-lifecycle.py --label p2-green-admission-final --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest ForegroundSessionBindingTest` | javac0/JUnit0；**OK (34 tests)** | 最终同套；不累计原33项或中间重复green |
| Main runner `--label green-final-main --scenario all` | kotlinc0/run0；force/graceful A→B均B_terminations0/B_intent NONE；当前force1/null点击0；通知异常exit0 STOPPED、exit23 CRASHED，owner均退休、FGS stop1 | **MAIN_DISPATCH_PROBE_OK，device=false** |

原 `p2-green-admission-junit.json` exit1 是排他修复后旧代fixture仍占admission的中间失败，保留；最终版本显式释放旧代后34项通过。本报告不改历史失败为绿，不将六行Main场景、34项JUnit与早期14项合成FGS相加成完整App或设备数。

reviewer独立核验全部17项freeze/source/preservation与三项before-502，14未改项对应原seal；Main整文件hash和stop/watch两个精确片段与 `source/green-final-main/MainActivity-fragments.json` 一致。原kotlinc/JUnit/run记录的hash/exit与各preservation一致。

原34项成功运行最初未保存完整Java输入；C恢复时新增了来源绑定，reviewer按 `source/resume-01-jdk-inputs.json` 核37份显式源码snapshot及孪生，并逐项核 `java-bytecode-verification-resume-01/classes` 与原 `classes-p2-green-admission-final`：**114个class全部同字节**，与source-binding记录相符。C的补证是新javac0及bytecode比较，tests_repeated=false；本reviewer没有再次编译或执行JUnit。该关联不冒充中断前已经存在源码快照，也不覆盖Android完整Kotlin/资源构建。

## 实际完成、未完成与边界

实际完成本报告、外部同路径副本、定点核验JSON/脚本及即时preservation；只写本轴正式spec-rereview与 `resume-01/review-spec`、`resume-01/preservation/review-spec`。保留502原首审、全部旧seal与失败；没有改源码、测试、原始证据、tracker或其他owner文件。

剩余由主协调完成503实际输入/版本/包名/资源构建与必要真机FGS/UI/正常停止/强停/Activity重入签收。当前wait失败、底层停止有界性、真实FD/ROM/AVF仍各有独立门；本报告不由34项或Main片段green签VM已停止或允许未确认同盘回退。真实AVF/Guest/性能成功样本仍0，无性能主张。

风险是将合成Android边界误当目标ROM系统服务及VMM通过，故本结论仅关闭三项局部源码/JVM缺陷。Architecture Change Policy：修复属于既有契约内时序与代际bugfix，无新owner、权限、IPC或数据面，不代用户裁定ACR-01。

分支/commit沿用户给定main/428860a，无新commit；无生产/设备/adb/UI/Gradle/版本/ref/签名/网络/系统操作，无凭据、整仓归档、外发、清理或另一轴读取。Class B保留至维护者验收归档，不自动清理。

报告人：独立 Spec reviewer / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
