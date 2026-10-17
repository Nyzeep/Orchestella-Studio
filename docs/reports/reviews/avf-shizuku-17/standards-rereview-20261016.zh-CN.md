# 17 独立 Standards 定点复审：排他 admission、异步 stop 与 watcher 顺序

结论：**approve，限新冻结源码与已提供的 JDK / 精确 Main 片段证据范围；S-C-01 / P2 闭合，无新增必须修改发现**。另外按规范独立检查主协调指定的 stop/watcher 两处小 diff，未发现违规。旧首审 request-changes 和原冻结/失败记录保持，不把本批准扩为真实 Android FGS、VM 或最终 code503 验收。

## 背景、scope 与固定点

沿唯一源码 Orchestella_dev、main@428860ad9a1cb335f8be78c157b2ff57faf2a6da 的 dirty 工作区及本批 code502。恢复时 C 新 seal 尚未落盘，本 review 没有在进行中代码上签通过；主协调随后明确提供 `batch-01/lifecycle-logs/p2-stop-dispatch-fixed/final-freeze.json`，本次才定点复审。

已完整恢复 AGENTS/CONTEXT、DEFENSIVE 全部 policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、两份20261016交接/reviewer协议、resume dispatch、旧同轴 index及05/08/17/18/19/20/22正式报告。按原独立轴约束，未读取需求轴。共同硬规范包括 ADR-0011 的生命周期/异常持久化与冷恢复、20261016 Worker C 的 expected-session、generation、通知和单reader边界。

新 seal 共17项：相对502只有 `InstanceLaunchGuard.java`、`MainActivity.kt`、`InstanceLaunchSequenceTest.java` 三项变化，其余14项保持原字节。17项 frozen/canonical/preservation全部匹配，三项before-502与旧 seal相同；patch和source-manifest匹配新 seal。18三份Guest输入未变，沿原approve，不重做已完成审查。

独立定点核验脚本和摘要位于 `batch-01/resume-01/review-standards/{verify-c-freeze.py,c-p2-frozen-verification.json}` 及即时 preservation。本脚本仅读固定文件/hash/class/既有原件，没有执行 C 的编译或测试 runner。

## S-C-01 闭合及相关小 diff

### S-C-01 · 排他 admission 不再覆盖有效 generation

冻结 `InstanceLaunchGuard.begin` 常规1起点22–28行，先 `IN_FLIGHT.putIfAbsent`，已有 admission 返回null，只有获准请求写LATEST。冻结 `MainActivity.launchInstance` 1504–1513行对null直接给定提示后返回，不进入beforeLaunch/启动/失败收尾。因此B被拒绝时不改A的token/metadata/FGS清理，A后续register使用原generation。

新测试 `rejectedConcurrentLaunchCannotReplaceUnregisteredOwnersGeneration`（冻结test常规404–435行）覆盖A已RUNNING尚未注册→B拒绝→A注册→真实seam wait退出，断言A generation稳定、拒绝请求不触发afterLaunchFailure/end/反馈、STOPPED/注销/guard释放、close及反馈各一次。旧代测试增加显式释放旧admission再开始新代，符合排他语义，不是移除旧代断言；中间失败原件继续保留。

首审规范问题在这一冻结源码范围闭合。未把JDK fixture的内存session wait当真实QEMU/AVF owner停止。

### stop 异步调度：在点击时捕获 expected session

冻结 `MainActivity.requestInstanceStop` 常规1223–1240行，先捕获requestedSession，再进入IO协程；执行时核Registry仍为同一对象，随后交现有expected-session typed requestStop。点击时无owner或A在排队期间被B替代均拒绝，不能把原A动作作用于B。Pool monitor仍将动作接受/stop intent写入与注册、完成串行，失败不新增intent；未改变底层停止机制或声称caller结束等于VM退出。

原始Main片段 red-s04 实际输出B_terminations=1/B_intent=FORCE并exit1；最终green对force/graceful的A→B替换均为0/NONE，另核当前owner只dispatch一次、null点击后迟到owner不被停止。这里按本轴规范独立检查主协调指定的S04小diff，不采纳另一轴结论。

### watcher 顺序：owner 注册后先安装监督，再更新通知

冻结Main常规1563–1590行取得注册owner/generation后，先Thread.start退出watcher，再执行第二次FGS.start。通知更新抛错不再跳过watcher；外围afterLaunchFailure仍因存在owner拒绝清理它。watcher按expected session与generation完成metadata/注销/guard/反馈；FGS START本身保留generation/live-owner检查，不能靠迟到通知恢复已结束owner。

red-s05原件为通知异常后owner_present=true、state=RUNNING、FGS_stops=0，exit1。最终green在通知异常后分别观察exit0→STOPPED、exit23→CRASHED且保留23；owner移除、FGS stop一次、close一次、不terminate。没有将通知失败当VM死亡，也未为此新增reader或独立owner。该项同样只按规范审查现diff。

## 测试真实性、来源与限制

- `lifecycle-logs/evidence/p2-green-admission-final-junit.json` 为exit0、**OK (34 tests)**，四个原测试类；不是把旧33、阶段15或逐轮red/green加总。admission red exit1、修复后旧fixture未释放admission的中间exit1均保留。
- `p2-stop-dispatch-fixed/evidence/green-final-main-run.json` exit0，实际Kotlin coroutines与生产Java Registry/Pool/Controller/FileStore，覆盖上述stop和通知顺序；UI/FGS是受控边界。已逐读runner，确认它嵌入生产Main语句，未以重写调度的模型代替。
- 冻结Main整文件SHA256为 `589d55fb5386cae4f73ca4ab8b24d5ea7134eafa75e959515211dfc60a7bfc66`，与原green片段记录相同；stop/owner-watcher片段逐字符相同，编译harness中包含精确片段，原件与孪生一致。
- 原34项运行当时未即时保全逐文件输入bytes，这一历史限制保留。恢复代理后补37份显式javac输入snapshot，另做一次仅来源绑定的javac exit0；114个class与原成功测试输出逐字节相同。**本 reviewer只读核完37份snapshot/canonical/preservation和114个前后class及记录，未重编译、未重跑测试，也不将补证倒填为原运行时已存在的源码快照。**
- 新seal、before、patch/source-manifest、关键原始red/green、来源绑定和保全记录均定点匹配。没有重复全仓/测试套件、设备或Gradle操作。

## 完成、未完成与治理

完成S-C-01及上述stop/watcher小diff的独立规范复审，18/其他未改输入沿原结论。未完成最终code503原包/隔离包构建及必要集成、真实Android协程/FGS/通知/Activity重入、真实VM正常关机/强停/撤回/进程死亡；主协调正在处理的构建不由本报告提前批准。原18真实Guest/journald/屏显、D物理/OEM/真实创建事务、05/07/08/ACR及真实性门仍独立。真实AVF样本仍0，无性能主张。

12项smell基线按项目现结构及本次最小hunk核过，无新增需要改动的判断性smell；工具已覆盖项不重复报告。变更仍是既有生命周期契约内bugfix，无新权限/IPC/VMowner/数据面，未触发新的Architecture Change Policy流程，AVF ACR-01仍未决。

修改仅本正式standards-rereview及指定resume-01/review-standards脚本/摘要/报告副本和preservation；旧正式首审和旧index未改。无生产、设备/adb/UI、Gradle、版本、签名、网络、ref/worktree/commit操作，无Class A/旧包/源码/失败证据或用户数据清理，无生产回滚需求。branch沿用户提供main，Commit无。后续由主协调绑定最终构建与设备门；源码若再变更，另给精确seal定点复核。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
