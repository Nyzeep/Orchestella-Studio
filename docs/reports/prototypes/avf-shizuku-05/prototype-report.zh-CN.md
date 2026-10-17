# 原型报告：05 身份、Context 与合成 FD 可行性

- 日期：2026-10-16；Worker A / Codex / new-provider/gpt-6.1-sol。
- 背景/scope：在独立QA包验证App与Shizuku UserService身份、固定API shape、实际Context、合成FD与最小能力查询；不实施生产VMowner，不创建VM。依据原票05、20261016两份交接、当前scope/spec与本批dispatch-scope。
- 分支/基线：main / 428860ad9a1cb335f8be78c157b2ff57faf2a6da，保留dirty0.5.0/code500。主协调核旧36份任务文件SHA一致；A无commit/ref/worktree/版本/签名操作。
- 外部根 E：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`。A输出 `E/research-auth-device/` 及preservation；设备原件由唯一操作者主协调写 `E/device/` 及 `E/preservation/device/`。Class B长期保全至维护者验收归档，不自动清理；Class A零改动。
- 状态：**in-progress-device**。身份、Context正例/拒绝、App→US合成FD与当前能力声明已有真实结果；完整death、跨升级/owner与独立复审门仍开。真实AVF/Guest/性能样本均0。

## 假设、环境与当前结论

设备别名device-01，当前user0，Android16/API36。报告不保存serial或完整fingerprint。官方Shizuku13.6.0/code1086由主协调安装并以官方ADB流程启动；实际server UID2000。client SDK api/provider/aidl/shared固定13.1.5、MIT，版本不与manager混称。

QA独立包 `io.github.nyzeep.orchestella.researchauth`，与生产旧系统包/其他user数据分开。主协调分别签装QA1(code1/0.1.0-identity)、QA2(code2/0.1.1-identity)、QA3(code3/0.1.2-identity)。QA3交付 `E/delivery/identity-permission-capability-qa-v3.apk` SHA256 `2f7d67f33e6b8702695a42d0fe1fbf661bdb14b8d7f3ad7ed38940dc6450840f`；QA证书SHA256 `a2001d21314616a7d475f442936da4c2d17423db22316336c84b8c60339b9669`（主协调签名证据）。

| 假设/门 | 实际结果 | 判定与边界 |
| --- | --- | --- |
| 独立QA可离线装配 | canonical Manifest/Activity/AIDL/UserService与无Gradle runner完整，主协调签装运行 | 成立；构建不等平台可创建VM |
| App Context包名代表真实身份 | APP_UID/untrusted_app；US UID2000/shell，但APP_UID | 该简化假设不成立，真实进程身份与包Context分开 |
| UserService可见固定custom成员 | App customBuilder MEMBER_UNAVAILABLE_OR_HIDDEN；US MEMBER_VISIBLE_NOT_INVOKED | 成员可见成立；不等构造/VM调用成功 |
| 同包Context使shell可用App私有state | App新QA state可写；US在DATA_DIR_LSTAT返回OS_ERROR_13 | 不成立；不得造Context、chmod或public-copy绕门 |
| App打开的合成FD可供US使用 | 32B RO/RW PFD真实读写、App观察末字节修改，receiver关闭副本/caller原件保持 | 成立于App→US合成FD；US→virtmgr/crosvm未验 |
| 当前ROM声明nonprotected | US实际manager getCapabilities返回bits1，protected=true/nonprotected=false | 未声明nonprotected，现Java adapter前提不满足；protected不是customLinux成功 |
| caller/epoch与销毁可界定 | 真实stale/end拒绝；normal remove后目标pidof无返回；合成caller gate覆盖 | 部分成立；完整App/server death、撤回、跨UID Binder负客户端未验 |

## 真实设备原件与观测

A实际读取并分析下列原件，均由主协调运行，不能归为Worker设备操作。

- `E/device/identity-v1-authorized-app-receipt.json`：APP_UID/QA_PID、untrusted_app；MANAGE/CUSTOM均DENIED；clientGranted=true，server UID2000。customBuilder隐藏，manager shape可见但未调用。
- `E/device/identity-v1-user-service-receipt.json`：US UID2000/QA_PID、shell；两VM权限GRANTED_TO_ACTOR_UID；APP_UID。staleEpochRejected/endedEpochRejected=true。capability NOT_QUERIED、manager NOT_CREATED、AVFfalse。client允许读回不证明人类允许过程或首次配对体验。
- QA1首轮 `identity-v1-app-receipt.json` 的CLIENT_QUERY_FAILED保留。源码未授权时也调用checkRemotePermission，异常吞掉App facts；QA2起按字段隔离、client未授权不做该远端查询。
- QA2 `identity-v2-synthetic-gates-receipt.json` 真实USER_SERVICE_CALL_FAILED/OPERATION_FAILURE；没有Context/FD字段，不能据此定位为某一拒绝门。原因是局部结果被后续异常丢弃的诊断缺口；QA3分步保留。
- QA1/QA2 normal remove分别有 `identity-v1-remove-confirm.json`、`identity-v2-remove-confirm.json`，目标QA service pidof exit1/空。这不代替其他死亡或撤回门。

QA3 `E/device/qa3-context-fd-receipt.json`（2026-10-16T10:06:13Z）实际exit0、SYNTHETIC_STAGES_RECORDED，只表示已记录，逐字段判定如下：

| 门 | 原始字段/值 | 结论 |
| --- | --- | --- |
| App state | appState_contextStateResult=NEW_QA_STATE_WRITABLE；mkdir/new sentinel/write2B/close均OBSERVED | App真实Context可创建本轮新私有QA state |
| US同Context | serviceState_contextStateResult=OS_ERROR_13；effect_lastStage=DATA_DIR_LSTAT | shell对App私有目录访问被拒；不是manager已create失败 |
| 正合成FD | positive_fdResult=SYNTHETIC_READ_WRITE_OK；roRead/rwWriteReadback/appObservedRwMutation=true | 固定合成PFD在US可读写 |
| 副本关闭 | positive_receiverCopiesClosed=true；callerCopiesRemainOpenAfterSuccess=true；拒绝后caller原件亦true | 已观测收到副本关闭、App原件保留；不是全进程FD泄漏审计 |
| 错RO/RW | 两项FD_MODE_REJECTED，副本关闭true | 模式拒绝成立 |
| 同对象/pipe/重放 | DISTINCT_OBJECTS_REQUIRED / REGULAR_FILE_REQUIRED / FD_REPLAY_REJECTED | 固定对象/类型/重放拒绝成立 |
| symlink/代际 | appSymlinkOpenRejected/staleFdEpochRejected/staleEpochRejected/endedEpochRejected=true | 自有O_NOFOLLOW和服务代际门成立 |
| closed FD | closedFdRejectedBeforeOrAtReceiver=true；closedFdBoundary=OPERATION_FAILURE | 只证发送或接收边界拒绝，具体端未知 |
| 副作用证据 | App/fixture mkdir/create/write/dup/close/symlink及US固定1B写入effects均保留 | 初始identity观察不掩盖后续文件副作用 |

QA3 `E/device/qa3-manager-capability-receipt.json`（2026-10-16T10:08:51Z）：US固定VirtualMachineManager(Context) constructor后getCapabilities返回QUERY_RETURNED_DECLARATION_ONLY，bits=1、declaresProtected=true、declaresNonProtected=false；App在MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE。initial snapshot的NOT_CREATED/NOT_QUERIED仅属于初始子快照，实际查询看serviceCapability_/appCapability_字段。仅构造与声明查询，没有create/load/run/stop/delete。固定AOSP pin这两个方法不spawn；ROM进程前后检查仍由主协调单独核。不能把声明当运行，不能用protected/Microdroid或系统其他VM替代customLinux。

06另证MANAGE变更后QA App/US退出、重开partial与ADB恢复，具体因果UNKNOWN，详见06 canonical；它不代替预先设定的独立App/server death实验。

## 实际完成的实现与修改模块

仅 `prototypes/avf-shizuku-identity-probe/`、05/06原票及canonical报告，未写SetupWizard/MainActivity/launcher/engine/Guest/ADR/policy。

- 独立Manifest、可见QA Activity、精确provider/queries、官方13.1.5库与完整MIT notice装配；没有INTERNET、任意Intent命令、业务广播或生产service导出。
- `ProbeSession` 真实caller UID、固定协议、递增正epoch、end/destroy拒绝；UserService仅shell UID2000，daemon(false)、remove(true)、destroy保留16777114。
- `ProbeSnapshot` 固定identity/shape，不初始化AVF类。`QaContextState`真实Context下新0700目录及0600/O_EXCL/O_NOFOLLOW哨兵。`QaSyntheticFiles`与`SyntheticFdExercise`只允许固定32B合成RO/RW角色，校验fstat owner/regular/size/mode、对象和sentinel后固定小I/O，服务finally关闭副本。
- 每个固定步骤独立类型/errno/stage，`ProbeStageLedger`保留已经发生的创建/写入事实；不输出任意异常消息、路径、secret或用户正文。不使用/proc/self/fd重开、chmod、relabel或公开盘副本。
- QA3 `QaManagerCapability`固定constructor/getCapabilities查询。`FixedPermissionPlan`、`QaPermissionPort`、`QaPermissionExperiment`仅06固定QA/user0两权限实验，无general shell/exec。
- `ProbeConnectionEvents`修正迟到旧disconnect退役新session；全进程单inflight，5秒UI截止只退役结果不称底层取消，30秒client等待无自动重试。
- `scripts/build_qa.py`只复用现SDK36/Build-Tools36.1.0/JDK17与四个精确缓存AAR。先双份保存src/manifest/runner/notice原bytes，再仅编译snapshot；label不覆盖，不联网、不签名、不安装、不写C:。APK参数只由主协调指定运行。

## 真实测试、失败历史与独立审查

最终 `E/research-auth-device/qa3-compile-02/result.json`：aidl0/javac0。19份构建输入与source snapshot、preservation逐字节一致，seal=`identity-v3-frozen-source.json`。主协调QA1/2/3 APK构建/签名/安装另有原件，A未执行Gradle/设备/签名/版本操作。

`qa3-regressions-01/result.json`：实际Java公共边界配合合成输入共72项全部0：session24、FD metadata11、permission plan27、disconnect4、side-effect ledger6。它们不证明真实Binder/FD/SELinux/Android系统行为。

真实失败保留：identity-compile01–05的Windows生成注释编码/路径分隔问题，06/07编译绿；context-fd-compile01缺受检异常处理的compile red，修正后02–04绿。需求轴P2旧A remove、新B bind、迟到A disconnect，实际Activity使用的回调边界先编译0/运行1，断言late A disconnect retired active B；加visible/epoch/connection对象对称guard后0/0。规范轴P2初始NO_FILES字段掩盖后续副作用，现限定initial observation并使用单调effect ledger，6项行为检查保留前序成功/后序失败。独立复审结论由reviewer/主协调签收，A不自审approve。

## 源码保全缺口与纠正

QA1 `identity-apk-01/result.json` 原hash定点核验：Activity/Service/AIDL三份关键原始源码没有匹配的不可变raw副本。before-source是更旧source-only，QA2源码不能代替。QA1仍有source hash、classes/generated、APK与原始device receipts，但无法从不可变原件逐行复核这三份源文件。`qa1-source-preservation-gap.json`如实记录并已通知主协调/两review轴，不重建或反编译冒充。

QA2改QA3前已逐hash保全13份原型输入于preservation/qa2-source-before-qa3。QA3 runner已修为先双份raw snapshot再编译。`source-evidence-audit-01.json`实核19冻结输入无漂移、13份已引用设备原件与batch preservation字节一致、A根新增.log=0。所有原始失败/成功证据保留。

## ACR影响、未完成、回滚与后续动作

给ACR-01/Worker B的四条独立实证：App接口受限；shell同包Context私有state被EACCES拒绝；App→US合成最小FD可行；当前ROM未声明nonprotected。直接搬现Java adapter到UserService没有可行性闭环。可继续有限typed协议/生命周期fixture或独立权限体验；不批准新生产VMowner、持久namespace或protected替代路线。最终架构仍由用户裁定，ACR-01保持未决。

未完成：跨UID Binder负客户端、超时/迟到FD设备收敛、独立App/server death与client撤回/设备重启、完整跨升级state/config/owner兼容、独立复审。US→virtmgr/crosvm与VM/create/Guest未运行；当前nonprotected限制已明确，不绕平台安全继续不兼容VM实验。

回滚仅先remove本隔离service并确认退出，权限按06事前初态显式恢复；主协调白名单处理独立QA资产，不清生产数据、不撤预先grants、不清理旧证据或Class A。实验源码不直接入生产。没有新产品架构实施、Guest暴露面、GPL对象来源或发布动作；SDK完整MIT随包。不触发已执行的产品架构变更，既有ACR门保持；无commit/push/release、无越界。

固定动作/预期/副作用/前置/回滚：A根identity-v1/v2/v3-actions.zh-CN.md及permission-interruption-controls.zh-CN.md。后续只依主协调实际设备原件回填，05不签completed。

## 2026-10-16 resume-01：QA4 submit deadline P2 增补

本增补仅修独立QA3的旧deadline污染同permissionSession后续调用，不重做或改写前述设备结果、旧失败与首审。恢复基线为main@428860ad dirty502；主协调并行503生产构建属于其独立窗口，本Worker未改生产App/runtime/Guest/版本、未执行Gradle/设备/adb/UI/签名或ref/commit/push/worktree。未读取任一独立轴报告，修复依据本次明确P2和实际Activity回归。

本轮外部根Q=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01/qa-deadline-fix/`，保全根P=`batch-01/resume-01/preservation/qa-deadline-fix/`。改前20份原型文件与05/06旧报告精确bytes先保存到Q/P的before-qa3；其中19构建输入与原identity-v3 seal、qa3-apk-01/source、qa3-compile-02/source逐项一致，mismatch=0。旧seal/源码/失败/首审未覆盖，QA1三源raw缺口继续明确保留，不以QA4 snapshot倒填。

实际修改只有ProbeActivity、build_qa.py、README与两个必要测试文件：每次submit用独立Object token与自己的deadline Runnable；deadline须匹配当前token/epoch/可见性，正常finish只removeCallbacks自己的timer并按token退役自己的busy。全局IN_FLIGHT仍只在真实operation返回的finally释放，当前5秒截止继续报告CALL_DEADLINE_UNDERLYING_NOT_CANCELLED，不取消底层、不放行第二个在途调用。permissionSession的开启从worker移至当前token/epoch/可见性均成立的主线程完成分支，旧snapshot在deadline/end/death/onStop后返回不再重开会话。没有通用调度框架、新IPC或版本常量变更。runner仅新增批准output-root参数以把本次编译/后续主协调构建放入Q并即时P保全。

真实运行证据如下，均有P逐字节副本：

| 证据 | 实际结果 | 证明范围 |
| --- | --- | --- |
| activity-deadline-red-02/result.json | javac0，九场景八项运行exit1；current-deadline控制exit0 | 保全的原QA3中A完成、B grant/readback/restore在途、旧A timer会退役B；旧finish可清busy，迟到snapshot可重开已退役session |
| activity-deadline-green-02/result.json | javac0，九场景110断言全部PASS | 同一最终harness对QA4：B正常结果与restore可用；旧timer/finish隔离；当前deadline保留全局flight至真实返回；end/death/旧Activity迟到拒绝 |
| qa4-compile-01/result.json | AIDL0、javac0 | 全部19构建输入真实编译通过；Worker未生成APK |

测试直接编译原样ProbeActivity、ProbeConnectionEvents、ProbeSession和FixedPermissionPlan，以真实按钮、onServiceConnected、onStop、death listener调用实际submit路径；Android主队列/时钟/视图和远端Binder由JVM替身提供，不复制submit模型。latch精确控制B在途，手动重放已派发旧timer/finish检查隔离。它是实际Activity源码合成回归，不是Android系统、Binder死亡或真实权限变更验收。原72项旧边界测试未重跑，不作为本轮新增通过；其余构建输入与QA3保全逐项一致。

red01/green01原件保留。green01的两项失败来自测试替身未把已派发timer标作退役；仅纠正替身记账后，以同一测试字节分别运行red02/green02，未修改断言规避失败。最终red02与green02的测试输入SHA相同，核心三路径均先真实red再green。

QA4冻结为Q/identity-v4-frozen-source.json，SHA256 `6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15`；22份原型/测试/README输入在Q/source及P/source，19构建输入与qa4-compile-01完全一致，notice另行双份保存。Q/qa3-to-qa4.patch记录原QA3到QA4的局部变化，Q/identity-v4-actions.zh-CN.md给固定构建/设备动作、前置/副作用/预期/恢复边界，均有P同名副本。主协调指定QA4 code4/0.1.3-identity，仅runner参数传入，签装由主协调执行。源码/runner/tests/README已停止写入，交独立规范与需求双轴复审，不自审approve。

本轮无新增系统/网络行为、Class A或生产资产变更；Q/P为Class B，owner主协调，长期保全至维护者验收归档，不自动清理。回滚仅按精确before-qa3与patch由主协调明确实施，不reset/clean、不从HEAD重做。05仍in-progress-device；完整系统death/跨升级/owner/真实timeout门与当前nonprotected=false、AVF样本0等旧边界不变。未触发新的产品架构变更；既有ACR-01仍未裁定。

报告人：Worker A / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未单独计量。
