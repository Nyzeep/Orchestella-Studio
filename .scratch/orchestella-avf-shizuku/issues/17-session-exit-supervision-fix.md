# 17: 按运行会话归属记录退出与保留活 VM owner

Type: implementation
Status: implemented-integrated-reviewed; qemu-stop-and-persistence-verified
Parent: 11-exit-session-reproduction
Owner: Worker C
Blocked by: QEMU三种停止/STOPPED/重开持久性/最终FGS撤销已验；真实exit23/wait异常/错代/EOF/Activity重入/通知全矩阵、App死亡后VM冷恢复及AVF未验

## 证据与范围

原11旧Controller有限red保留。本轮以真实Pool/Registry/Reconciler/Controller运行：`terminalEofDoesNotReplaceTheOwnedRunningSessionOrWriteCrashMetadata`失败，预期ATTACHED实际CRASHED；证据`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/evidence/red-eof-compiled-junit.json`，Java exit1。当前MainActivity退出watcher静态证实丢弃waitFor结果且finally按id normal/unregister/stop；先将现有行为移至该生产调用的公共监督seam再逐slice测exit23/旧代，不把静态来源或旧harness称设备bug。

写锁：InstanceLaunchSequence、ExecutionSessionRegistry、PersistentTerminalSessionPool、InstanceSessionReconciler、InstanceLaunchGuard及对应必要Java测试；MainActivity退出watcher限定hunk。主协调2026-10-16另批准MainActivity.requestInstanceStop与InstanceDetailActivity.requestPowerChange必要最小expected-session typed stop接线。保全所有既有dirty改动，不改SetupWizard/input/AVF底层/strings/版本/资源。

## 验收

- 当前owner exit0只记录一次正常停止；exit23记录异常终止及原退出码，不宣称盘损坏；允许冷恢复且身份/凭据/根盘引用不变。
- 用户明确force stop得到确认后非零退出可正常停止；graceful请求仍等待真实退出，拒绝/异常不留下假意图；错代请求不能操作新session。
- wait异常/中断不能证明VM已死：保留owner、运行状态、launch guard与FGS，反馈观察失败且不泄露异常正文。
- 终端EOF但owner尚在，不判VM死亡、不创建socket替身/第二reader；输入仍按既有EOF拒绝。
- A退出迟到不能改B metadata/diagnostic、Registry/Pool、guard、反馈或通知；条件unregister只清expected session且close一次。
- 反馈在当前代完成中发出；迟到UI回调检查generation和新的launch guard。设备FGS/UI真实门单列。
- 无owner的持久RUNNING按既有host death冷恢复语义收敛，不以socket可连证明受监督owner。

## 测试、回滚、交付

直接JDK入口：`python -B <本批lifecycle-logs>/run-lifecycle.py --label <唯一标签> --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest`。仅显式Java生产闭包和JUnit4.13.2/hamcrest既有缓存，class/tmp在本批外部根，无Gradle/设备/网络。每个red/green独立原件及即时preservation。

主协调串行需求：App指定类及完整testDebugUnitTest、assemble/lint；新QA实例的正常关机/强制停止/通知/重入，签名冲突未消除前设备门保持open，不卸载清数据。回滚只定点恢复Worker C修改前字节和必要hunk；不回退其他owner/旧dirty/用户数据。无新依赖或参考源码复制；许可沿原源码。报告`docs/reports/tickets/avf-shizuku-17/implementation-report.zh-CN.md`；11原型报告与原票补记保留证据级别。

契约检查：修正既有生命周期语义，不新增VMowner/IPC/数据面、热恢复、多实例或权限，不触发Architecture Change Policy。

## 2026-10-16 最终本地交付与批准扩锁

主协调逐项批准扩锁：两Activity typed stop接线；OrchestellaForegroundService最小generation start/stop重载及STOP匹配；Main orphan→INTERRUPTED、launch catch按本代且无owner收尾；TerminalActivity既有setSessionEndListener条件一行，让EOF后的已确认owner退休再次reconcile。新增ForegroundSessionBinding及测试只承载既有FGS绑定边界，不创建owner/后台任务/权限。原输入/渲染/正文、attachedHolder/bindingGeneration门、单reader保持。

launch guard生成process-local UUID；注册holder沿用该代际，Registry返回expected session对应token；FGS START/STOP及Main延迟UI均绑定同一代际。错误/缺失/旧token不得清新绑定；旧generation刚完成后新launch尚未register也不能被清除。确认wait完成时先persist/退休owner，再投递通知，通知异常不留假活owner。wait异常/中断则保留owner/guard/metadata/通知，不能据此重启同盘。EOF与owner退休各一次事件，退休后释放listener；重复注销零额外close/stop，旧holder仍受UI代际门约束。

当前固定入口 `run-lifecycle.py --label final-lifecycle --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest ForegroundSessionBindingTest`：javac exit0，JUnit exit0，33项/0失败；`run-fgs-adapter.py`：实际FGS生产源码在显式合成Android边界14条通过。exit0/23、wait失败/中断、显式force/graceful/拒绝/错代/重复、EOF活owner、A晚到/B运行与B尚未注册、条件unregister、冷恢复/单实例/身份保留、通知投递失败及EOF后owner退休均有运行断言。各red原件/preservation保留，设备/真实framework未跑。

生产/test已最终冻结并通知主协调，17份App/Guest输入精确bytes/SHA与定点副本在本批 `lifecycle-logs/final-freeze.json`、`frozen-source/`；本票App输入14份，Guest三份归18。没有由C生成canonical。正式中文实施报告 `docs/reports/tickets/avf-shizuku-17/implementation-report.zh-CN.md`，原11报告 `docs/reports/prototypes/avf-shizuku-11/prototype-report.zh-CN.md`。

状态为本地已实现，集成/设备/独立双轴审查仍open；未运行Gradle/设备/安装/启停/版本/签名/commit/ref/worktree/网络。基线main@428860a，起点dirty0.5.0与并行A/D保留。主协调最终502+构建/Guest同步/设备验收另记实际原件，不用旧批或合成结果改completed。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

当前C新seal为E/lifecycle-logs/p2-stop-dispatch-fixed/final-freeze.json，17输入逐项匹配；三P2为异步stop在点击时捕获expected owner、注册后先安装watcher再通知、排他admission不覆盖有效generation。34项最终JDK与Main精确片段真实red/green、37输入/114class补来源关联保留，不把补证倒填原时间。17/spec-rereview-20261016和standards-rereview-20261016均approve，三P2源码/JVM审查闭合；503 App747/98及编译/包资源集成已完成，不再写等待503或独立review。

真实FGS/通知策略/Activity重入、QEMU/AVF owner正常poweroff/exit23/force/EOF先于owner退出/冷恢复仍待设备原件；503八框架场景是D输入/向导，不证明本票真实VM停止。QA4 US死亡也非VM死亡。整票不标全部completed，08底层停止有界性/盘lease独立保留。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板
