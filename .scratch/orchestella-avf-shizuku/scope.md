# Orchestella AVF / Shizuku 本阶段 scope

日期：2026-10-15建立；2026-10-16 batch-01/resume-01最终增补。Owner：Codex 主协调。活动 effort 仅为 `.scratch/orchestella-avf-shizuku/`；旧 `.scratch/orchestella-platform/` 与 phase5 工单只引用，不续写或更改全局 tracker 默认。下列初始范围和批初基线作为历史保留，当前事实以本文末节及map矩阵为准。

## 已授权交付

完成固定一手来源的 Shizuku/AVF 综合研究、隔离可行性与设备验证工具、决策地图、spec、独立工单和依赖图；报告和工单形成后实施无阻塞、契约不变且可测的升级。普通 client/development grant 可在既有 L2 边界评估；新 VM owner/特权进程/IPC 面需要 ACR 和具体决策。

## 事实与文档归属

- 综合研究唯一 canonical：`docs/research/2026-10-15-avf-shizuku-upgrade.md`。
- 本 effort：map/spec/issues 及 scope。
- 原始证据和参考 manifest：开发根 `Orchestella/artifacts/avf-shizuku-next-stage/20261015/`，四线各自目录与 preservation。
- 单票实施/review：现有 `docs/reports/tickets/`、`docs/reports/reviews/`。
- 小型可维护验证工具：待工单确定后归属 `tools/device-validation/` 或 owning module；实验源码归 `prototypes/<明确名称>/`。
- 所有新 raw evidence 禁用 `.log`，不含用户凭据、键入正文、bridge token、embedded initrd 或设备序列号；不上传。

## 不可跳过的门

1. 产品/Guest/CI 在正式综合报告和工单形成前只读。
2. 历史 403 hidden API 拦截后 QEMU 的事实按原版本继承；405 QEMU 验证不充当 AVF 成功。
3. feature、Shizuku、Android grant、接口身份、SELinux/FD/owner、VM生命周期、Guest readiness分别证明。
4. 无adb设备：真实 AVF/性能/撤回/数据保留/正常停止验收均待设备；合成 fixture 只证明测试范围。
5. 新代理/VM owner 生产实施必须经 ACR 决策和设备可行性；不得改全局 hidden API、SELinux、targetSdk、系统分区或用户盘权限来绕过。
6. 不改发布标签，不提交/推送/发布，不创建远程 issue/PR。不同生产包使用递增版本；按变化性质决定 PATCH/MINOR。
7. 保全 SDK/NDK/JDK/缓存、参考仓、根盘、源码交付与原始证据；不清理旧资产、不新建长期worktree。

## 测试默认

用户已授权主代理按研究选择常规粒度和现有测试接缝，无需为写工单或常规测试再停下来。优先现有 CLI行为、授权模型/能力判定、launcher/engine与单reader契约；增加 seam 仅限外部可观察行为。测试须记录真实 red/green，设备门单列。

## 当前基线

`main@428860ad9a1cb335f8be78c157b2ff57faf2a6da`；2026-10-15研究起点为单branch/worktree、clean、0.4.5/code405，随后01–04的未提交0.5.0/code500实现保留。旧起点见原外部 `baseline.md` 和 `historical-apk-identity.json`，不能当本轮当前状态。

## 2026-10-16 执行增补

用户明确授权按20261016主代理及Worker Prompt实施剩余全部工单并在文档约束内激活/验证AVF；11/12/14/15复现后可建立自包含实施子票完成契约内修复。真实VMowner/特权IPC仍需ACR-01，不由本授权代答。未授权commit/push/PR/发布。

本轮批次：外部 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，Owner主协调Codex，Class B证据长期保全至维护者归档、不自动清理。读取范围/网络端点/数据分类/文件写锁/设备与构建队列见该根 `dispatch-scope.md`；每件原始证据即时preservation，不新建.log，不包含serial、token、密码、embedded载荷或用户正文，不外发。

本轮实核：HEAD/main/单worktree不变，0.5.0/code500 dirty；旧封板明确36变更文件SHA全部一致，未从HEAD重做。W:/V:当前未映射；SDK/JDK17/Python3.12.9与Ubuntu-24.04存在。实际ADB发现唯一device-01当前user0；当前user精确查询本App/Shizuku/QA包无匹配，相关App/server/crosvm/virtmgr进程数0。这不证明其他user无包或数据；两Android权限unknown，真实AVF样本0。canonical证据为本批 `baseline-source.json`、`device-readonly-preflight.json`、`current-user-package-process-query.json`。

主协调是唯一设备写/安装/grant/VM启停与Gradle/版本/APK/签名owner。A=05/06原型；B=ACR/07/08方案与fixture；C=11/12与派生17/18；D=14/15与派生19/20（SetupWizard独占）；E=工具/recorder与09/10准备（必要22）；A确证权限实施预留21。worker不得自行adb/Gradle/版本/ref/worktree。构建前所有输入冻结并确认，设备窗口仅一个owner、明确新QA实例/盘与恢复条件；AVF未确认停止不得同盘QEMU重启。当前旧“无设备”不是停止依据，所有独立可解工单持续推进。

## 2026-10-16 resume-01 最终证据收口

本节是当前执行终态。前文的“无adb设备”、批初unknown/无包查询、W/V未映射以及21预留均为当时记录，不是当前frontier。恢复dispatch记主协调已恢复W/V。实际工单01–20、22、ACR-01共22文件，无21；本轮不新增或补造21。

用户20261016授权将原11/12/14/15的先复现范围扩展为有证据后的契约内修复，由17/18/19/20自包含票承接；22只承接离线typed recorder。允许隔离QA和真实AVF调试不代表已批准新生产VMowner/特权IPC、持久state namespace或自动grant入口。原spec的Out of Scope按其20261015阶段理解，扩展仅按20261016增补列明；ACR-01用户未裁定，现nonprotected=false也不能由用户批准代理改变。

本轮最终生产版本为0.5.3/code503，HEAD仍main@428860a，0.5.0→0.5.3 dirty全部保留。主协调完成503原包与.phase5构建/核验；仅独立.phase5 503真实安装。原产品code300另一签名及user128数据保留，不卸载/清数据绕过，原包503只能表述为已构建产物。两包/版本/签名/unique guest_init/MIT及pinned Image/baseinitrd/QEMU同500证据见resume-01/integration-503/final-apk-verification.json。

本批当前可签收范围：747 App tests/98 suites，failure/error/skip0；lint0Error/0Fatal/748Warning；503/probe3真机八框架场景PASS（七向导一输入，无真实创建事务）。C17三P2后新seal及17两轴复审approve；18双轴approve；19/20源码及502框架同轴复审approve，503又实际复验且D输入不变；22离线127=100+27项及双轴approve。QA4九场景/110断言和05最终双轴限定approve只认证原型源码/JVM范围。

QA4实际code4普通session同epoch跨旧5秒点8277ms仍可readback，normal remove、App force-stop→US退出且server活、server TERM→App观察death/US0、官方starter重启/client重连及重新bind均限定PASS，终态QA两权限DENIED、US0/server1。原件resume-01/qa4-device-summary.json及device/qa4-final-*。没有权限变更/VM/强制真实B在途交错，不能签完整软件grant/restore或VM死亡。resume-01/permission-control-summary.json的单/双权限ADB对照及恢复全PASS仅认证ADB路线；早期after-qa-app-death.json原件保留。

真实getCapabilities bits1仅protected、nonprotected=false；当前ROM不兼容现nonprotected custom Linux。App新QA state可写，US所给App Context目录EACCES，App→US合成PFD通过仍非G4/owner/VM。真实AVF样本0，07/08/09/10维持准确blocked，ACR未决。剩余：typed完整软件grant/restore、拒绝/记忆拒绝/管理器撤回/首次本机配对/reboot/OEM、跨UID/迟到FD/state升级；实体键盘/OEM IME、物理rotation/uiMode、真实创建事务；真实FGS/VM normal stop/Guest日志/PID1/journal及性能。01–04/16历史完成范围不扩张。

本次文档收口只写本effort的scope/map/spec/既有issues、13实施报告和canonical研究追加；正式最终编排报告由主协调另写。无源码/tests/版本/签名/设备/Gradle/git/ref/网络/清理动作。定点27文档和51源码输入前后bytes/SHA、修改清单/证据索引存本批resume-01/tracker-closeout及resume-01/preservation/tracker-closeout；只读源码不变、旧seal/失败/QA1三源缺口与全部Class A保留，无整仓/.git归档或秘密输出。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板

## 2026-10-17：Protected优先近期规划授权增补

用户已确认protected优先研发、non-protected持续兼容、QEMU保证现FullLinux，要求据现况/大量资料调整近期优化方案，允许对应目录文档。本回合只写 [canonical近期方案](../../docs/reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md)、本scope/map/spec及必要23–27/ACR-02 proposal，引用本日设备重检与详细路线研究；不执行代码/构建/测试/设备/安装/grant/启停/新联网。

旧22票保留完成/未验范围，新增5个工程/原型票+ACR-02后28文件，21仍缺不补造。23只读能力事实/展示、24受控平台原型和27真实QEMU阶段接线有可执行frontier；25/26以真实boot/数据门放行。原11/12/14/15与17–20只补剩余验收或有证局部修复，不用新名重复整套实现。

研发优先级调整不等于protected默认产品支持已通过。QA固定protected失败即停可以规划；用户持久保护要求/弱模式降级、安全承诺与ADR勘误由ACR-02；owner/state/FD/embedded秘密可读/死亡收敛继续ACR-01，两票不同范围，不因本次“很好”关闭。

本轮读写前baseline及3份原tracker文件before已保全于外部 `Orchestella/artifacts/protected-avf-plan/20261017/`，Owner主协调，ClassB长期保留不清理，原始证据不用.log并preservation。仅有限文档/任务相关源指纹校验，不归档全仓/.git/根盘/签名材料，不改accepted ADR/CONTEXT/policy。0.5.3未提交源码、ClassA、旧APK和原实测/失败原件保持。

后续实施仍沿用户既有受约束真机调试授权，但必须由主协调核当前设备/签名/实例/事务/版本，单设备/Gradle窗口与源文件锁。平台未知/模式不支持/stop未确认不得假绿、不得同盘或单实例约束外并发fallback。普通client/两权限不解决模式或label/firmware；不允许Root/全局策略/放权/public盘拷贝。

规划验证只做文档引用/状态/依赖/完整性和独立一致性核查；历史747/127/8不重跑或累加，真实AVF仍0。没有commit/push/tag/release/工作树变更，计划就绪不等所有新票ready或所有工单已实现。
