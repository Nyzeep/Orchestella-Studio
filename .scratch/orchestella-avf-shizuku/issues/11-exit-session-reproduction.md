# 11: 退出原因与旧会话代际的有限复现

Type: research
Status: reproduced-fixed-and-qemu-stop-verified; remaining-lifecycle-gates-open
Owner: Worker C（B旧有限复现原件保留，20261016真实公共调用链反馈及17局部修复已交）
Blocked by: QEMU正常poweroff/App正常关机/强制停止与持久性已验；真实exit23/wait异常/错代/EOF/Activity重入/通知完整矩阵、App死亡后VM冷恢复及AVF未验

用户价值：正常关机/异常终止解释一致，旧watcher不能清除新会话。来源研究§8/C §4及现InstanceLaunchSequence/SessionRegistry/SessionPool。允许用合成session在现测试seam重现waitFor非零被统一normal、EOF仍活、A旧回调晚于B注册；禁止改VM所有权、热恢复、第二reader或立即全面重构。

验收：固定exit0/非零/wait异常/显式stop、旧新代并发，验证持久状态与unregister/通知次数；terminal EOF不单独当VM死亡；模拟及真实设备分别标注。red入口为InstanceLaunchSequenceTest、InstanceSessionReconcilerTest、PersistentTerminalSessionPoolTest（App testDebugUnitTest筛具体类），先记录当前行为再提局部修复票。设备正常关机可后补；进程杀死只新QA/明确批准，不碰活事务。性能不适用。

无新IPC/FD权限面，保持实例/盘/凭据；失败保留现象不手工改绿。回滚删除本次可再生fixture而非用户状态；依赖沿现测试库、无代码复制。报告研究canonical补记或 `docs/reports/prototypes/avf-shizuku-11/`；E/reproduction-11/与preservation。原封板为静态已证/运行未测；本轮有限运行证据见下方补记。完成为可重现反馈与修复边界，不代表已修。

## Comments

### 2026-10-15 B：一个最高公共行为接缝的有限运行反馈

协调者授权：01–04并行实施期间仅领取本研究票的最小行为复现；不改生产，不跑Gradle/App/设备，不触C/A/D文件，不因red自行修复。指定政策已在B研究完整读取，本次未重复dump。基线HEAD仍为`428860ad9a1cb335f8be78c157b2ff57faf2a6da`；共享工作树的其他实施变更不属本代理，不作回退或半成品审查。

**运行结论：旧A退出回调在同一实例B重新RUNNING后调用真实`InstanceLaunchSequence.afterGuestExit(controller, instanceId)`，会把B的元数据写为STOPPED。** 该结果是已运行的纯Java公共接缝反馈；真实Android Activity/watch线程是否可到达此调度顺序仍未验证，不能写成真机竞态、QEMU/AVF退出故障或用户数据损坏已复现。

接缝选择与边界：`afterGuestExit`只接收Controller与instanceId，无exit code或session代际参数。`Controller`为final，未伪造替代：harness直接使用真实Controller和真实beforeLaunch/afterGuestExit，以合成内存Store实现生产Store接口保存/重载状态；一个极小SyntheticSession只提供exit0信号与代际标签，不冒充生产ExecutionSession/VM。没有复制MainActivity watcher逻辑来“重演App”；MainActivity中`waitFor`结果丢弃、finally按id调用afterGuestExit仅作为已读静态caller证据，不计运行覆盖。

实际运行顺序：

1. 真实Controller.create创建仅内存合成实例，真实beforeLaunch使A进入RUNNING。
2. A合成waitFor返回0；latch只延迟旧退出completion。真实Controller.recordGracefulStop记录显式正常停止，不使用坏状态直接伪造恢复。
3. 真实beforeLaunch重新启动同instanceId到RUNNING，建立合成B代际2，B的alive=true。
4. 释放旧A回调，调用真实afterGuestExit。Store重载状态从RUNNING变为STOPPED，旧回调交付1次，新增元数据写入1次，无crash diagnostic。
5. 断言“旧A回调不得把B退出”失败：`AssertionError: stale A completion changed B RUNNING to STOPPED`，red Java进程exit1。对照为当前代exit0正常回调，状态STOPPED，进程exit0。

编译与可复现入口：已有`D:/JDK17/bin/javac.exe`/java，UTF-8、`--release 17 -proc:none -implicit:none`、显式空sourcepath。仅编译8份精确源码：InstanceLaunchSequence、InstanceLifecycle、InstanceUserConfig、InstanceStorageManager（仅生命周期类编译所需，存储操作未调用）、FullLinuxContract、VmHardwareConfig、BridgeHandshake、StorageContract；再加1份外部harness。无JUnit/Gradle/Android依赖，无整仓编译。所有class/tmp与证据均外置。

```text
python -B "LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/reproduction-11/run-reproduction.py"
```

runner为捕获预期red返回0；**真实red进程仍exit1**，不能把runner成功解释为产品已修。精确javac/java argv、stdout/stderr、exit、源文件SHA在原始JSON中。只运行当前代正常退出与旧代回调两个场景，不为相同证据扩大测试。

证据根（绝对路径）：`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/reproduction-11/`。

- `result.json`：机器可读结论/限制；`evidence/red-old-completion-after-restart.json`：真实red原始输出与exit1；`evidence/control-current-exit0.json`：正常对照。
- `src/ExitGenerationReproduction.java`、`run-reproduction.py`、`javac.args`：最小可重复工具；`evidence/javac.json`：编译exit0。
- `evidence/source-manifest.json`：八文件绝对路径/size/SHA与caller静态来源；运行前后八文件SHA一致。
- `evidence/status-before.json`、`status-after.json`：并行工作树状态原件；不据其他owner变更声称B修改生产。
- `evidence/preservation-check.json`及`preservation/`：所有本票生成证据即时字节孪生、无新增.log；合成root-volume路径始终不存在，真实根盘/元数据/凭据未读取或落盘。随机合成凭据只在内存，不输出、不保存。

局部修复边界建议：后续单独修复票应让退出观察与预期session identity/generation绑定，迟到旧退出对新RUNNING元数据、registry/launch guard/通知均不得有副作用，仍保持一个终端/控制reader、当前正常退出行为与单运行实例约束。现有Controller及afterGuestExit接口没有足够上下文自行判断这次回调属于谁，因此单改一个正常状态transition不能解决代际归属；应在owner/caller层明确接缝后再实施，不全面重构VM所有权。本研究没有采纳具体产品API或改契约，不触发已实施的架构变更，无新IPC/FD权限面。

未运行/未完成：非零exit23与wait异常分类、EOF但VM仍活、Registry.unregister/launch guard/通知次数、生产ExecutionSessionPool并发、真实Activity/设备/AVF/QEMU、端到端正常停机及性能。App上层launch guard、registry或UI是否阻止本次合成时序不在harness覆盖内，故不能把这个seam red直接称为用户可到达缺陷。票原验收中的这些门仍open；本轮按新增窄scope已交付一个运行red，不将整票全部改为completed，也不把静态exit23风险升级为已运行事实。Standards review另待明确派工。

B实际修改仅本票补记与外部reproduction-11；无生产/Guest/CI/ADR/policy、C/A/D文件修改、commit/push/网络/设备/系统动作。保留本次原件与preservation至协调者验收归档，不自动删除。报告人：B / ZCode GPT-6.1-sol ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量。

### 2026-10-16 Worker C：公共调用链运行反馈及派生17局部修复

已读取旧result/harness并保留其范围；不将旧Controller有限red升级为设备bug。当前实际调用顺序：Main launch guard/beforeLaunch→launcher注册Registry/Pool→Main wait watcher；Terminal EOF→Reconciler；Main/Detail显式stop；FGS异步START/STOP。Main watcher现实际委托公共监督seam，逐slice运行red→green。固定red包括EOF误判CRASHED、exit23误判normal、wait异常误判退出、旧A清B、新B尚未注册时旧A收尾、force意图缺失、冷恢复拒绝、socket替身恢复、通知投递失败留假活owner、EOF后owner退休无第二状态通知；原件不删除。

本地已完成派生[17](17-session-exit-supervision-fix.md)：expected session+launch generation收尾、条件unregister、typed stop接受后意图、退出码/观察失败区分、EOF保留owner、无owner INTERRUPTED/coldRestore、通知/UI代际及流结束/owner退休各一次通知。当前四类直接JDK生产接缝JUnit33/33，actual FGS源码+合成Android边界14条；无Gradle/设备/VMM运行，不称真实Activity/ROM通过。Android真实通知、Guest正常关机/强停/死亡、重入及独立两轴审查仍open。

本批证据根：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`。`evidence/final-lifecycle-{javac,junit}.json`、`actual-fgs-{0,1}.json`，每项red/green原件与即时preservation；`final-freeze.json`及`frozen-source/`含17份本轮App/Guest输入bytes/SHA。C生产/test已最终冻结并通知主协调，Guest canonical同步/版本/构建/签名/设备均交主协调串行。

正式[11原型报告](../../../docs/reports/prototypes/avf-shizuku-11/prototype-report.zh-CN.md)与[17实施报告](../../../docs/reports/tickets/avf-shizuku-17/implementation-report.zh-CN.md)区分原证据/运行接缝/受控Android/未验设备。仅契约内bugfix，无新owner/IPC/权限/reader/暴露面，不触发ACR。Main stop/orphan/catch、Detail stop、FGS及Terminal回调扩锁均经主协调批准，未触SetupWizard/input/AVF底层/版本/公共strings。分支main/基线428860a，旧dirty0.5.0及并行A/D保留，未commit/ref/worktree/设备/Gradle/网络。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

原旧A完成覆盖B的公共seam red及本批EOF/exit23/异常/stop/旧代/通知red保留，产品修复由17承接。C三P2后lifecycle-logs/p2-stop-dispatch-fixed/final-freeze.json和34JDK/Main精确片段均已封板，17/spec-rereview与standards-rereview均approve；当前本地代码、503集成和独立审查不再open。真实FGS/通知/Activity重入、QEMU/AVF退出/正常poweroff/强停及冷恢复/数据门未验，QA4无VM US死亡不能代本票VM监督。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板
