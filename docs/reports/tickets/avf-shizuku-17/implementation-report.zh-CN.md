# Ticket AVF/Shizuku 17 实施报告

- 票号/标题：17，按运行会话归属记录退出与保留活 VM owner；Parent=11。
- 执行 session / 模型 / thinking：Worker C / new-provider/gpt-6.1-sol；thinking未可靠计量。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，起点 dirty 0.5.0/code500旧36变更保留。没有branch/ref/worktree/commit操作；主协调后续501是临时QA包，最终502+构建另由主协调归档。
- 外部E：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`。源文件修改前bytes/hash、原始red/green、最终冻结源与preservation均在E，Class B保留至维护者验收归档。

## 1. 任务目标

原11要求固定退出/旧代/EOF/显式停止的真实公共调用链反馈。本次按用户授权以17承接局部修复：非零退出解释诚实、旧完成不得清新会话、EOF不等VM死亡，保留单运行实例、原token/reader与冷恢复契约。

## 2. 实际完成及验收

| 验收 | 实际完成与证据 |
| --- | --- |
| exit0与exit23 | Main watcher实际委托InstanceLaunchSequence.awaitGuestExit；确认0正常停止，23记录CRASHED及原码/固定诊断，不宣称盘损坏。red-exit23→green及最终回归 |
| wait异常/中断 | 返回WAIT_FAILED，不把观察失败当VM退出；保留owner/metadata/guard/通知；中断标志保留，不输出异常原文。red-wait-failure及最终回归 |
| 显式stop | 两Activity用expected session typed stop；动作接受后记录GRACEFUL/FORCE，拒绝/失败不新增意图，重复同意图不重复执行；只有FORCE接受且owner确认退出可将非零视为有意停止。graceful不掩盖exit23 |
| 终端EOF | pump完成仅流事件，保留owner；Reconciler不写crash、不造socket替身、不第二reader。red-eof-compiled→green |
| 旧代完成 | Pool在确认退出收尾时同时核expected session和launch generation，metadata/guard/注销串行于register；已开始但未register的新代同样保护。red-stale-owner及red-new-launch-before-register→green |
| 条件unregister | 仅删除expected owner，新holder不close/terminate；重复退出/注销只清一次。最终Pool/Sequence断言 |
| 通知/反馈 | FGS START/STOP带相同process-local generation，实例与token匹配；旧STOP/缺token/重复STOP/冷service/迟到START受控回归。Main延迟UI回调检查latest generation。通知投递抛错前，已确认退出的owner先完成退休，不留假活Registry |
| EOF后真实退出反馈 | Pool对流完成和owner退休各通知一次；退休后释放listener。TerminalActivity原bindingGeneration/attachedHolder检查保留，仅允许owner退休再reconcile。red-eof-owner-retirement→final-lifecycle |
| cold restore | 无owner持久RUNNING收敛INTERRUPTED，不以可连socket及waitFor=0替身冒充监督；beforeLaunch对CRASHED/INTERRUPTED调用既有coldRestore，保留根盘/身份/凭据与单运行约束。red-orphan-socket、red-cold-relaunch→green |
| 启动失败 | 仅本代且尚无owner时记录launch失败并释放自己的slot/FGS；存在owner时不假定VM死，不注销；旧失败不得清新owner或新通知 |

所有上述“通过”均限定Java生产公共接缝或受控Android边界，不表示设备/真实VMM已验。详细原型反馈见 `docs/reports/prototypes/avf-shizuku-11/prototype-report.zh-CN.md`。

## 3. 未完成 / 偏差

未运行Gradle、APK、Android framework、设备正常关机/强停/撤回/进程死亡、真实FGS/Activity重入或QEMU/AVF。主协调报告原产品签名冲突，不能以卸载清数据绕过；这些门仍open。独立规范/Spec两轴审查由主协调另派，本实施者不自审放行。

final-freeze时测试包括33项；未将旧批、逐轮重复或实际FGS合成14条加总为App完整套件计数。主协调的统一构建结果必须引用其新证据，不能据本报告称已通过。

## 4. 修改模块及写锁

生产：

- `app/src/main/java/io/github/nyzeep/orchestella/InstanceLaunchSequence.java`：公共监督、退出原因、冷恢复和失败收尾。
- 同目录 `ExecutionSessionRegistry.java`、`PersistentTerminalSessionPool.java`、`InstanceLaunchGuard.java`：expected owner、typed意图、条件注销、process-local generation及EOF/退休通知。
- 同目录 `InstanceSessionReconciler.java`：保留EOF owner，无owner用INTERRUPTED。
- 同目录 `MainActivity.kt`：退出watcher、stop接线、orphan/catch及其代际反馈限定hunk。
- 同目录 `InstanceDetailActivity.kt`：requestPowerChange内typed stop限定hunk，原Shutdown客户端和等待逻辑保留。
- 同目录 `OrchestellaForegroundService.java`及新增 `ForegroundSessionBinding.java`：generation重载/匹配与可直接JDK验证的通知绑定状态边界。
- 同目录 `TerminalActivity.java`：既有setSessionEndListener回调条件一行，允许owner退休后二次reconcile，未动输入/渲染/正文处理。

测试：原 `InstanceLaunchSequenceTest.java`、`InstanceSessionReconcilerTest.java`、`PersistentTerminalSessionPoolTest.java`与新增 `ForegroundSessionBindingTest.java`。scope扩展的两处stop、FGS、Main orphan/catch、Terminal最小hunk均取得主协调明确批准并在17票记录。

未改SetupWizard、input、launcher/AVF底层、版本/签名/strings/公共资源，不抢D19/20；并行A/D及旧dirty改动全部保留。Guest变更属于18，统一17份冻结输入含14份本票App输入及3份Guest输入，见E/final-freeze.json。

## 5. 设计决策与契约

以原ExecutionSession对象身份+launch generation确定收尾归属；不修改ExecutionSession契约、不新增VM owner/FD/IPC。stop请求先验证expected owner再执行既有动作，接受后才留意图；waitFor仍唯一确认退出源，EOF只处理传输。非零退出按异常终止记录固定诊断，force意图只能解释它自己确认的退出。冷恢复复用Controller既有语义，清旧诊断的时点仍在显式coldRestore。

FGS Intent token来自launch guard并由注册holder沿用；只用于进程内生命周期关联，不是bridge token/鉴权凭据，不持久化。原无token API保留用于既有调用，缺token STOP不能清带token绑定。STOP异步晚到由service匹配防护；冷/重复STOP仅对当前idle startId调用stopSelfResult。

按Architecture Change Policy，属于既有契约内bugfix/回归，不触发ACR，无新权限/暴露面/后台任务。TDD与diagnosing-bugs项目版原文先读；Skill调用器因全局/项目同名歧义拒绝，依项目规则直接执行既定seam red→green；无需新增测试边界访谈。

## 6. 测试与真实输出

固定入口与结果：

```text
python -B <E>/run-lifecycle.py --label final-lifecycle --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest ForegroundSessionBindingTest
javac exit0; JUnit exit0; OK (33 tests)
python -B <E>/run-fgs-adapter.py
ACTUAL_FGS_ADAPTER_OK checks=14 AndroidBoundary=synthetic device=false
```

第一入口使用D:/JDK17，显式生产Java源码闭包、既有JUnit4.13.2/hamcrest1.3，class/tmp在E。Sequence/Registry/Pool/Reconciler/Controller为实际生产实现；合成session只提供可控wait/流，FileStore写外部合成元数据，不读取真实实例/根盘。第二入口编译实际FGS源码，Android系统类型为外部合成stub，验证14条实际onStartCommand/重载调用效果；不能称framework测试。

原始输出：`E/evidence/final-lifecycle-javac.json`、`final-lifecycle-junit.json`、`actual-fgs-0.json`、`actual-fgs-1.json`。每项red→green的argv/exit/输出均保留，索引见11原型报告。第一轮javac缺少生产依赖的编译失败、FGS整合fixture顺序失败原件同样保留，不当行为red；之后显式补全依赖/按生产begin→register顺序运行。

最终代码 `git diff --check` exit0；不把该静态检查当运行门。Gradle完整App/InstanceDetailActionRulesTest、assemble/lint及框架/设备门交主协调。

## 7. Benchmark / 实测数据

本票不涉及性能，无AVF/QEMU真实样本、无提速结论。所有合成调度不是设备竞态复现。

## 8. 风险

- wait失败/中断后保留owner是“退出未确认”；需要人工检查/新的受控owner停止验证，不能同盘重启其他VMM。
- stop动作与注册/退出收尾通过Pool monitor协调，调用仍受现launcher/platform停止时延约束；本票没有验证底层stop有界性，08的设备/平台门仍独立。
- Android真实调度、通知策略和Activity重入尚未由本Worker验证；必须通过主协调的新QA路径，不能把stub结果当ROM结论。
- listener退休后清除引用，旧holder UI回调仍通过原binding generation/holder门；真实UI时间序需设备补验。

## 9. 新发现

EOF后owner退休缺少二次状态通知、通知投递异常留下假活owner均已用固定red证实并限定修复。其它无证据重构未扩展。旧TerminalActivity socket探针方法本身未删除，但Reconciler不再调用该恢复路径；不会把waitFor=0的替身纳入owner监督。

## 10. 后续依赖与最小动作包

C生产/test已最终冻结，精确17份bytes/SHA在E/final-freeze.json，本票相关14份在其中；构建窗口不再改写。主协调新502+版本/签名统一处理，保留0.5.0与临时501旧证据。先跑四类+InstanceDetailActionRulesTest与全App testDebugUnitTest，再assemble/lint；FGS/Terminal/stop小hunk需框架编译及设备补验。

设备只用新QA盘/实例、无活用户事务；绑定APK/source/ROM/user/instance/session/generation，分别执行Guest正常poweroff、App接受/拒绝的graceful/force、EOF先于owner退出、重入与通知保留；观测持久状态/diagnostic及一次stop，旧代不得清新代。进程死亡/撤回由主协调另排已授权隔离窗口；签名不符先保全不卸载。本报告没有执行这些动作。

## 11. Commit与宿主/资产

分支main，新增commit无；未提交改动保留。没有网络/设备/Gradle/安装/版本/签名/系统资源写入；仅approved源码和E输出。SDK/JDK/缓存/WSL/旧APK/用户盘/旧证据未清理。必要before字节与final源码只定点保全，没有整仓或.git归档，不含serial/token/用户正文。原始证据不用.log；脚本与证据孪生校验见E/preservation-check.json。

## 12. Ticket状态

建议 `implemented-local; integration-device-review-open`。局部实现与独立运行回归完成，主协调集成、真实设备和独立审查门尚开，不将整票标completed。原11维持证据分级并链接17。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 13. 2026-10-16 resume-01：三项 P2 核验与补封板

用户明确要求继续原 batch-01 的未封板工作。本次沿原 17/18 票、原 `lifecycle-logs` 外部目录及既有 dirty 工作区恢复；没有从 HEAD 重建，没有改 tracker 或读取任一审查轴报告。已完整读取指定治理文件、两份 20261016 交接、原/恢复 dispatch 和原 17 全部验收。前文 33 项及旧冻结包是第一轮历史记录；本节与 `E/p2-stop-dispatch-fixed/final-freeze.json` 记录三 P2 后的实际冻结输入，旧 502 seal 与失败证据保留原字节。

### 精确修复范围

本次恢复核验确认中断前修复均已完整落盘，恢复期间没有再修改生产代码或测试。相对 `E/p2-stop-dispatch-fixed/before-502/` 只有以下三文件不同，其余原冻结的 14 份 C 输入逐字节不变：

| 发现 | 已落盘实现与原验收对应 |
| --- | --- |
| S04 / P2 | `MainActivity.requestInstanceStop` 在 UI 调用时捕获 `requestedSession`，进入 IO 协程后核对同一对象，再交原 expected-session typed stop。A 点击排队后被 B 替代及点击时无 owner 均不得操作迟到 B。 |
| S05 / P2 | `MainActivity.launchInstance` 在 owner 注册并取得 generation 后先启动退出 watcher，再进行第二次 FGS 更新；该次通知抛错不能跳过 owner 退出监督。exit0/23 分别完成 STOPPED/CRASHED、保留原退出码，close/FGS stop 各一次。 |
| S-C-01 / P2 | `InstanceLaunchGuard.begin` 使用 `putIfAbsent` 排他 admission，被拒请求返回 null 且不覆盖 LATEST。Main 对 null admission 提示后直接返回，不进入 `beforeLaunch` 或已获准请求的失败清理；现有 `InstanceLaunchSequenceTest` 增加拒绝请求不改 A generation/metadata/反馈的断言，旧代测试明确先释放旧 admission 再开始新代。 |

冻结三文件精确当前字节：

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `app/src/main/java/io/github/nyzeep/orchestella/MainActivity.kt` | 99493 | `589d55fb5386cae4f73ca4ab8b24d5ea7134eafa75e959515211dfc60a7bfc66` |
| `app/src/main/java/io/github/nyzeep/orchestella/InstanceLaunchGuard.java` | 2168 | `d9352e78d61bdc61367583f245ccefe224d2b132cef2b57edff9e55f6250c3e7` |
| `app/src/test/java/io/github/nyzeep/orchestella/InstanceLaunchSequenceTest.java` | 30468 | `f39f747ced2d89abbe91b2fd12836341c909c5326ce53a6da22a1d4d60740e93` |

原 bytes 在 `before-502/`，本次精确 17 份源码在 `frozen-source/`；局部 patch 为 `changes-from-502.patch`。以上均相对于 `E/p2-stop-dispatch-fixed/`，有对应 preservation 孪生。其他 C 文件、Guest、Input、Setup、engine 只读。

### 已核原始执行记录与最小补证

下列测试均为中断前真实原件，本次读取命令、exit、输出、来源片段及保全副本核验，不重复执行：

```text
python -B <E>/p2-stop-dispatch-fixed/run-main-dispatch.py --label red-s04 --scenario stop
kotlinc exit0; run exit1: queued A click terminated replacement B, B_terminations=1 / B_intent=FORCE
python -B <E>/p2-stop-dispatch-fixed/run-main-dispatch.py --label red-s05 --scenario notification
kotlinc exit0; run exit1: notification exception skipped exit watcher, RUNNING / owner_present=true
python -B <E>/run-lifecycle.py --label p2-red-admission --classes InstanceLaunchSequenceTest
javac exit0; JUnit exit1: rejectedConcurrentLaunchCannotReplaceUnregisteredOwnersGeneration
python -B <E>/run-lifecycle.py --label p2-green-admission-final --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest ForegroundSessionBindingTest
javac exit0; JUnit exit0; OK (34 tests)
python -B <E>/p2-stop-dispatch-fixed/run-main-dispatch.py --label green-final-main --scenario all
kotlinc exit0; run exit0; MAIN_DISPATCH_PROBE_OK scenario=all device=false actual_coroutines=true
```

最终 Main 整文件 SHA 和原 `source/green-final-main/MainActivity-fragments.json` 一致；stop 函数、owner 注册后 watcher 片段也逐字符一致。Main 固定调度覆盖 force/graceful 的 A→B 替换、当前 owner、null 点击后迟到 owner，以及第二次通知异常后的 exit0/23。使用真实 Kotlin coroutines、生产 Java Registry/Pool/Controller/FileStore；Android UI/FGS 是合成边界，不是 Android framework 或设备结果。原 `p2-green-admission-junit.json` exit1 是 admission 修复后旧代 fixture 未释放旧 admission 的历史失败，保留原件，最终 fixture 已显式释放旧代，不删除失败或把它当最终通过。

既有 JDK 34 项原件缺少逐文件源码快照。本次只补来源绑定，不重跑测试：

```text
python -B <E>/p2-stop-dispatch-fixed/verify-resume-freeze.py
exit0; original javac argv compile exit0
exact_java_inputs=37; classes_compared=114; all_equal_original_34_test_run=true
main_matches_original_green=true; tests_repeated=false
```

将原 javac 的显式当前 37 份 Java 输入逐文件保存至 `source/resume-01-jdk-inputs/`，仅将输出和临时目录改到本次 `java-bytecode-verification-resume-01/`；新编译 114 个 class 与原 `classes-p2-green-admission-final/` 全部逐字节一致。依据 `evidence/resume-01-source-binding-javac.json` 与 `resume-01-source-binding.json`，把当前源码与原已通过 34 项绑定；没有倒填中断前来源记录。恢复前核过的 35 份 P2 证据/片段/before 原件与孪生全部一致，runner 缺失的孪生本次补齐，最终完整保全检查见 `preservation-check-resume-01.json`。所有新增原始证据即时双份，未创建 `.log`。

### 状态、宿主影响与后续门

生产/test 自本次 freeze 起冻结，17 报告补记一并快照。main 基线仍为既有 `428860ad9a1cb335f8be78c157b2ff57faf2a6da`，保留原 0.5.0 和本轮 502 未提交实现；无新 commit/ref/worktree/reset/clean、设备/adb/UI、Gradle、版本/签名、网络或系统资源写入，W/V 映射由主协调管理。所有本次生成物限原批准外部 batch-01/lifecycle-logs，Class B 长期保全至维护者验收，无清理；没有新 VM owner/IPC/权限/数据面，不触发 Architecture Change Policy。

本票维持 `implemented-local; integration-device-review-open`。本次已闭合的仅是三项 P2 的本地实现、原 red/green 与精确来源冻结；主协调接收本包后统一递增 503、冻结构建输入、执行新包集成与必要真实 FGS/UI/停止验收，再由两轴独立复审。旧 502 的 App/Guest/工具结果保留在原 dispatch 所指证据，本节不声称它们验证了三 P2 后的 503 新包。其余设备、实体环境、ACR 与真实 AVF 门按主协调矩阵继续，真实 AVF 成功样本仍为 0。

报告人：Worker C 恢复代理 / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板
