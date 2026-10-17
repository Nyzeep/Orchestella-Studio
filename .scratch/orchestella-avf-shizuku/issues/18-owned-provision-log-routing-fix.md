# 18: 自有装配日志单路输出与失败摘要

Type: implementation
Status: implemented-integrated-reviewed; native-journal-device-observed
Parent: 12-owned-console-log-reproduction
Owner: Worker C
Blocked by: 真实Debian systemd ready、unit stdout/stderr=journal及两处owned marker已验；native failed/recovery、console全程计数/逐event唯一性及完整编辑体验未验

## 已成立的运行证据

本批`lifecycle-logs/run-owned-report.py --label red-owned-report-stream`直接执行生产report/run_step/write_state/fail_stage子进程；只映射Guest ROOT sentinel与/dev/console到外部受控sink。native按现owned unit的journal+console路由，recovery/interactive按继承console路由：running/ready/failed每条console均2份，owned文件均1份。失败process exit1，state保留operation_exit_code=23、status=failed、无done，sync请求1次。最初用普通文件覆盖语义的计数原件保留；stream轮仅将console重开映射为append，不修改生产report函数体。证据是合成sink运行，不是实际PID1/journal/QA Guest屏显。

## 范围与验收

只改`guest-assets/scripts/orchestella-guest-provision.sh`的owned report路由、`guest-assets/initramfs/runtime-functions.sh`的owned firstboot unit及必要测试。保留audit、kernel/全局stderr、journal、用户正文、单getty、PATH、密码/token/状态语义；不加App过滤或第二reader/终端数据面，不改AVF/input/Setup/版本/strings。

- native owned unit默认常规进度只进journal和owned文件；不再journal+console外加直接console重复。
- native failed保留journal/owned文件/state/原操作码、sync及一次直接console摘要，含可查状态而不打印用户正文/凭据。
- recovery无journal时进度/失败仍通过继承stdout可见，每条一次，不因去噪假死；交互`--status/--retry`保留可见反馈。
- 子进程使用真实生产report及真实状态/失败路径，控制native/recovery/failed sink计数；各slice red→green，记录command/exit/hash与即时preservation。
- 外部合成测试不触真实盘/网络/宿主/dev，不能称Guest设备验收通过。

## 交付、回滚及主协调门

canonical `guest-assets/initramfs/init`及生成guest_init由主协调在最终输入冻结后同步；Worker C不在共享半成品生成。独立测试通过显式Guest模块源码，不依赖先生成canonical，fixture/tmp及证据全部在本批lifecycle-logs。主协调再跑65Guest/10sync/6依赖门及新增回归，assemble/lint与APK唯一guest_init字节检查。

报告`docs/reports/tickets/avf-shizuku-18/implementation-report.zh-CN.md`，原12原型报告与票补记；原件/preservation为Class B保留至维护者验收，不自动清理。回滚只精确恢复C before对应脚本/hunk及主协调生成物，保留其他owner和旧dirty内容。无新增依赖/复制代码/网络请求，许可沿原源码。契约内日志路由修复，不触发Architecture Change Policy。QA新盘native/recovery/失败可见性待主协调串行，签名阻塞不得用卸载清数据绕过。

## 2026-10-16 最终本地交付

已修改owned provisioner的report路由及owned firstboot unit（Environment=ORCHESTELLA_PROVISION_REPORT=journal，stdout/stderr=journal）。native常规进度只进journal/owned文件，failed另一次直接console；recovery/interactive默认stdout每条一次。未改失败state/原操作码/sync流程、audit/kernel/全局stderr、用户正文/单getty/PATH或原token/reader。

`run-owned-report.py --label green-owned-report`真实子进程五场景全部通过，native正常console0/0、native失败0/1、recovery正常1/1、recovery失败1/1、interactive正常1/1；failed子进程exit1/state failed/operation_exit_code23/sync一次/无done，自有文件每条一份。项目维护性回归 `GUEST_RUNTIME_TEST_ROOT=<本批明确外部根> python -B guest-assets/scripts/test-guest-runtime-provision.py --owned-report-only` exit0，`OWNED_REPORT_TEST_OK checks=5 guestValidated=false`；该限定模式不调用canonical准备，原模式prepare/check门保留。原始red与green、child源码、argv/exit与preservation均在本批lifecycle-logs。

C生产/test已最终冻结，三份Guest输入精确bytes/SHA见`lifecycle-logs/final-freeze.json`，没有由C生成canonical。主协调固定同步：在唯一源码仓执行 `python -B guest-assets/scripts/sync-guest-init.py`（无参数），再 `python -B guest-assets/scripts/sync-guest-init.py --check`；新外部输出下跑Guest全套/同步/依赖门与本回归、统一App构建和APK唯一guest_init原bytes比较。未运行的全套/设备/PID1/journal/屏显/编辑体验及独立审查保持open，不用旧计数或受控sink改completed。

正式报告已完成：`docs/reports/prototypes/avf-shizuku-12/prototype-report.zh-CN.md`和`docs/reports/tickets/avf-shizuku-18/implementation-report.zh-CN.md`。分支main/基线428860a，旧dirty0.5.0及A/D并行改动保留；Worker无Gradle/设备/安装/启停/版本/签名/commit/ref/worktree/网络动作。Class A与旧原件保留，无自动清理。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量。

## 2026-10-16 resume-01 最终证据收口

以下为当前收口，前文各批/首审/中间状态作为历史保留，不覆盖原red或旧seal。E为本批batch-01，R为E/resume-01。集成三原件为R/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json：App747/98 suites、failure/error/skip0，lint0Error/0Fatal/748Warning；503两包已构建核签名/版本/唯一guest_init及MIT，pinned Image/baseinitrd/QEMU同500。仅隔离.phase5 503真实安装，原产品code300另一签名及user128数据保留，原包503不称安装成功。

C新17输入中本票三Guest输入与旧seal不变，主协调已完成canonical同步、Guest65/sync10/依赖门6及owned回归（沿各原件，不重复计数），503唯一guest_init233837B/SHA256 c8fbc1f8e4d76077385ad8a5e1027718615049042245cfcf802f039066461150与源码一致。18/spec-review-20261016和standards-review-20261016均限定approve；canonical/集成/独立审查不再open。

真实Guest/PID1/systemd-journal/native/recovery/failed屏显、日志耐久性及命令编辑体验仍NOT_TESTED；五受控sink报告子进程不代实际journal。保留audit/kernel/用户正文/单getty/PATH/失败原码/sync/无done，不增加App过滤或reader。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板
