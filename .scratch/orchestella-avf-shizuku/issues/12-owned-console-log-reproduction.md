# 12: 项目自有firstboot日志多路输出取证

Type: research
Status: reproduced-fixed-and-native-log-observed; failure-recovery-count-gates-open
Owner: Worker C（20261016真实owned report子进程与受控sink已交付）
Blocked by: Debian真实PID1/systemd、ready及journal/owned文件可查已验；native failed/recovery、console全程计数/逐event唯一性及完整编辑体验未验

用户价值：降低后台装配对命令编辑干扰且失败/audit可查。来源研究§8、旧terminal-runtime §2与C当前report/owned unit符号。允许合成root/stdout/log/console sink fixture，区分native、recovery、失败摘要；禁止关闭audit/journal、吞用户文本、全局quiet或重写终端传输。

验收：用真实report子进程和受控sink计次数，先证明重复路径/阶段，再提出单路化局部修复；失败state/日志/一次可见摘要齐全；无journal救援阶段不假死；单getty与既有PATH修复保持。red seam为test-guest-runtime-provision.py与distro runtime fixture；已有GUEST_RUNTIME_TEST_ROOT指向外部合成根。设备屏显/编辑体验待QA盘；不以静态多写入宣称每条一定重复。性能不适用。

无新权限/IPC/FD/owner，不读取真实盘；日志不采按键/token。回滚受控sink/test原型，保留raw证据；库依赖无新增。报告 `docs/reports/prototypes/avf-shizuku-12/`，E/reproduction-12/与preservation。原记录为静态来源已证、运行次数未测；本轮运行证据及派生18见补记，真实Guest门仍开。

## 2026-10-16 Worker C：真实report子进程计数及派生18

证据根：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`。执行生产report/run_step/write_state/fail_stage前缀子进程，以sentinel外部合成ROOT、固定成功/exit23子命令驱动；native fanout由实际owned unit字段决定，recovery/interactive继承stdout，直接console映射到外部连续流sink。只模拟环境，不复制report逻辑；不是实际PID1/journald/设备。普通文件`>`首轮覆盖旧行导致不完整计数已保留并说明，改用字符console连续流语义的append映射后重跑 `red-owned-report-stream-*` 才用于结论。

修复前：native正常/failed、recovery正常/failed、interactive正常共五场景，每条running及终态report均可见console两份、自有文件一份；failed实际process exit1，state failed/operation_exit_code=23、sync一次、无done。证据 `evidence/red-owned-report-stream-owned-report-summary.json`及五个case/child脚本原件、preservation。

证据成立后派生[18](18-owned-provision-log-routing-fix.md)局部修复owned脚本和firstboot unit：native常规进度console0、failed摘要1；recovery/interactive每条1，自有文件/state/原退出码/sync保留。`run-owned-report.py --label green-owned-report` exit0/五场景通过；维护性项目入口 `GUEST_RUNTIME_TEST_ROOT=<本批明确外部根> python -B guest-assets/scripts/test-guest-runtime-provision.py --owned-report-only` exit0，`OWNED_REPORT_TEST_OK checks=5 guestValidated=false`，不调用canonical prepare。独立入口同范围复验不累加计数。

正式报告：`docs/reports/prototypes/avf-shizuku-12/prototype-report.zh-CN.md`、`docs/reports/tickets/avf-shizuku-18/implementation-report.zh-CN.md`。C生产/test最终冻结，精确三份Guest输入及17的App输入见本批 `final-freeze.json`；canonical init/guest_init由主协调统一同步，C未生成。真实Guest journal/recovery/失败屏显/编辑体验、全套集成和独立审查仍open，不标设备completed。

未改audit/kernel/全局stderr/用户正文/单getty/PATH，未加App过滤/reader/数据面/权限/owner，不触发ACR。没有网络、设备、Gradle、安装/启停/版本/签名/commit/ref/worktree，旧dirty0.5.0与A/D并行保留。原始证据即时preservation，Class B至维护者验收归档，不清理旧资产。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

原report真实子进程受控sink重复console red和18五场景green保留；native常规console0/0、failed0/1，recovery正常/失败与interactive1/1，failed state/原码23/sync一次/无done保留。18源码独立两轴approve，主协调canonical/Guest回归及503唯一guest_init与源码bytes门已完成，不再写待同步/待审查。实际Guest/PID1/journal/屏显和编辑体验仍未验，不能从受控fanout签实机日志去重或所有终端消息消失。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板
