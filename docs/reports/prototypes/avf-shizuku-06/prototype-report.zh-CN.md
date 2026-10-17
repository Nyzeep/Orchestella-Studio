# 原型报告：06 本机权限与 Shizuku 设备验收

- 日期：2026-10-16；Worker A / Codex / new-provider/gpt-6.1-sol。
- 背景/scope：独立QA验证当前user的Shizuku client、固定MANAGE/CUSTOM development权限与中断/恢复；client、Android grants、平台能力与真实AVF分别证明。不实施生产grant入口或VMowner。
- 分支/基线：main / 428860ad9a1cb335f8be78c157b2ff57faf2a6da，保留dirty0.5.0/code500。无A commit/ref/worktree/版本/签名操作。
- 本批根 E：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`。A输出 `E/research-auth-device/` 及preservation；唯一设备操作者主协调，原件 `E/device/` 及 `E/preservation/device/`。Class B长期保全至维护者验收归档，不自动清理；Class A零改动。
- 状态：**in-progress-device**。已有真实client/actor、MANAGE变更中断、重开partial与ADB恢复读回；软件内完整两权限流程、其他系统门仍开，产品子票21不创建。AVF/Guest/性能样本0。

## 假设与实验环境

设备device-01，Android16/API36，当前user0。官方Shizuku13.6.0/code1086由主协调安装，以官方ADB流程启动，实际server UID2000。这证明ADB准备路线，不能称本机首次无线配对成功；配对码/口令/系统确认由机主本人，Worker不读取或代答。

独立QA包 `io.github.nyzeep.orchestella.researchauth`；SDK api/provider/aidl/shared固定13.1.5（MIT），精确provider/queries。QA1/2/3独立版本由主协调指定并签装，最新QA3 code3/0.1.2-identity：`E/delivery/identity-permission-capability-qa-v3.apk` SHA256 `2f7d67f33e6b8702695a42d0fe1fbf661bdb14b8d7f3ad7ed38940dc6450840f`。

生产旧系统包code300/另一签名与旧user128数据由主协调保全，原500未覆盖。初始普通包查询“无包”不再泛化为无历史数据。QA成功不签收生产向导、签名升级或冷恢复。A未操作设备、Gradle、版本或签名。

## 逐门验收矩阵

| 06要求 | 当前实证 | 结论/未完成 |
| --- | --- | --- |
| 官方manager安装/server启动 | 主协调官方13.6.0/1086、ADB starter与实际UID2000 | ADB路线已验，本机首次配对/OEM/重启未验 |
| client授权 | QA1真实clientGranted=true、binderAlive=true、serverVersion13 | 仅签当前readback，不推人类对话框体验 |
| 拒绝/记忆拒绝/撤回 | 既有controller本地行为回归保留 | 本机各路径未验，不自动弹窗重试 |
| actor权限分表 | 初态App两DENIED、US两GRANTED；操作后App真实MANAGE GRANTED/CUSTOM DENIED | 身份与时点分别记录，shell权限不代填App |
| typed两权限操作 | QA3固定executor已实现，MANAGE真实生效后App/US退出，action receipt未更新 | 不签callback、同session restore或软件双权限成功 |
| partial | 重开App读回MANAGE GRANTED/CUSTOM DENIED | 真实partial成立，不称准备完成 |
| 恢复 | 主协调固定QA/user0 ADB revoke本轮新增MANAGE，重开App两DENIED | ADB人工恢复闭环成立，不是软件内restore |
| fixed/未声明/无GRANT/cross-user/策略拒绝 | 实际Java边界27项合成负例 | OEM真实系统负例未验，不能以合成替代 |
| death与数据 | QA1/2 normal remove后目标pidof exit1空；MANAGE变更后App/US退出 | 退出具体因果UNKNOWN，不代替独立App/server death与撤回 |
| 产品向导与ADB兜底分验 | 独立QA、官方ADB准备和恢复有原件 | 生产向导未验，系统旧包/签名边界保留 |

## 实际实现与修改模块

只写canonical `prototypes/avf-shizuku-identity-probe/`、05/06原票与正式报告；未写SetupWizard/MainActivity/launcher/engine/Guest/ADR/policy。

- `FixedPermissionPlan`：两个固定enum，逐项初值/读回、partial/unknown/stale/death拒绝、第一项失败停止、不自动重试；已有grant不重复、不列入恢复集合，显式恢复只撤当前live session确认新增项。
- `QaPermissionPort`：只接受固定QA Context、真实shell UID2000、user0/owner UID匹配；PackageManager固定grantRuntimePermission/revokeRuntimePermission/getPermissionFlags在US进程解析，不接受任意包/user/permission，不执行pm/shell/exec。requested/development与system/policy fixed flags修改前校验，资格/API未知拒绝。恢复检查REVOKE资格，不要求GRANT资格。
- `QaPermissionExperiment`：绑定当前epoch，首次快照只读；App按钮7开session，8/9/10明确选择MANAGE/CUSTOM/both，11恢复新增，12读回。App独立checkSelfPermission随动作收证，不取shell读值冒充。
- UserService每IPC先查真实caller/epoch，daemon(false)、remove(true)、固定destroy。仅当前独立QA/user0，不开放生产授权器或Guest特权通道。
- 重要限制：新增ledger在US内存。Activity离开/remove、App/server death或撤回后，不能凭新进程重建。当grant后readback前失联，新增归属保持UNKNOWN；主协调须依事前初态/实际操作/独立读回显式恢复，不能自动撤可能预先存在的授权。当前实验正好出现该中断，不能产品化忽略。
- 05的分步诊断、合成FD及最小manager查询复用同QA，不调用VM create/load/run/stop/delete。详见05 canonical，实验不会触碰用户盘。

## 实际离线测试、首轮失败与审查修复

A只运行JDK/Python离线；无网络、无C:写入，四个13.1.5缓存AAR定点只读。`qa3-compile-02/result.json` aidl0/javac0；`qa3-regressions-01/result.json`共72项实际Java边界合成检查全0：session24、FD metadata11、permission plan27、disconnect4、side-effect ledger6。不证明真实Binder/SELinux或Android系统权限行为。

权限27项包括：无选择/无client/Binder/Root/cross-user/错target UID/缺manifest/无GRANT零写；初值UNKNOWN零写；第一项拒绝即停；第二项失败partial；readback UNKNOWN；两项间death；旧epoch不恢复；已有grant不重复；只恢复本次新增；REVOKE与GRANT资格分开。没有Android权限变化由这些fixture执行。

QA1首轮CLIENT_QUERY_FAILED真实保留。未授权refresh调用checkRemotePermission，异常吞App facts；QA2起逐字段错误分类、client未授权不做远端资格查询。QA2合成门只有OPERATION_FAILURE，不能定位Context/FD拒绝；QA3逐stage保留前序结果与副作用，实际结果见05。

两轴P2修复：旧disconnect可能退役新session，实际Activity公共边界red编译0/运行1→加visible/epoch/connection guard后0/0；初始NO_FILES字段掩盖后续写入，改initial observation并单调effect ledger，后续失败不抹mkdir/create事实。独立复审由reviewer/主协调签，不自审approve。

## 真机权限中断与恢复：可追溯闭环

A实际读取以下主协调原件，设备执行均不属于Worker操作。

1. `E/device/qa3-permission-session-initial.json`：固定QA/user0/UIDAPP_UID；MANAGE/CUSTOM两DENIED；requested*Development=true、serverCanGrant/Revoke=true、两flags0。它是修改前初态证据。
2. `qa3-permission-manage-only-receipt.json`仍为同一初态与elapsed，未出现action结果。**这只证结果没有落盘，不能推断操作没命中或没执行。**
3. `permission-interruption-readonly-01.json`：当前user0、QA code3 installed，App与:identity_qa均pidof exit1空、crosvm/virtmgr无；MANAGE 在固定 QA 用户下读回 true，其他已检查用户读回 false（具体用户编号不公开）。CUSTOM无grant条目仅能待精确读回。已证MANAGE变更发生，同时目标进程退出；不能签typed端到端流程完成。
4. `qa3-after-manage-app-readback.json`（2026-10-16T10:16:56Z）：重开APP_UID，MANAGE GRANTED_TO_ACTOR_UID/CUSTOM DENIED_TO_ACTOR_UID，client=true/server2000；customBuilder仍MEMBER_UNAVAILABLE_OR_HIDDEN。这是App真实partial。
5. `qa3-restore-manage-adb.json`（10:17:22Z）：主协调固定QA/user0 revoke本轮新增MANAGE，exit0。它是ADB人工恢复，不是软件restore。
6. `qa3-after-manage-restore-readback.json`（10:18:09Z）：重开App两DENIED、client仍true，真实终态已回本次初态；未撤预先grants或其他user。

闭环为：初态两DENIED → MANAGE变更 → App/US退出且回执未落盘/内存ledger丢失 → 重开真实partial → 固定ADB恢复 → App两DENIED。不能由此签callback、内存restore、CUSTOM或软件双权限通过。

固定AOSP BASE `99b01a65cc4c104933788b3143285ab6bae65827` PermissionService.kt：grant进入setRuntimePermissionGranted（744–751），development分支允许（993附近）；OnPermissionFlagsChangedListener仅runtime revoke或permission.hasGids且授权状态变化安排killUid（2718–2809）。没有development grant无条件杀目标的规则。该pin不是OEM ROM精确源码，当前退出具体触发者UNKNOWN；App消失后非daemon US消失与设计相容，不替代独立death实测。Worker没有读完整logcat/退出trace/description。

后续固定控制包 `E/research-auth-device/permission-interruption-controls.zh-CN.md` 已交主协调：限QA/该时间窗退出reason/subreason/status/timestamp只读摘要，不收description/trace正文；CUSTOM单项及两项ADB控制各自先保存初态、每步独立readback、只恢复确认新增。无原件不签通过，ADB成功与软件内成功分开。

## Context / FD / capability 对06的边界

A已实际读 `qa3-context-fd-receipt.json` 与 `qa3-manager-capability-receipt.json`。App新私有QA state可写，shell同Context在DATA_DIR_LSTAT得到OS_ERROR_13；App→US固定RO/RW合成PFD可用及正负/副本关闭门成立，不能推US→virtmgr/crosvm。

US固定manager constructor/getCapabilities真实bits1，仅protected=true、nonprotected=false；App manager constructor METHOD_UNAVAILABLE。声明不是运行，当前ROM不满足现nonprotected Java adapter前提；主协调不继续不兼容VM尝试，不拿protected/Microdroid替代customLinux。任何两权限成功都不解除该平台限制或App hiddenAPI。AVF/Guest/性能样本0。

## 保全、未完成、产品子票与回滚

QA1三份关键原源码raw缺口已如实审计 `qa1-source-preservation-gap.json`，仍保有hash/classes/APK/设备原件，但不能用QA2或反编译冒充原源码。QA2改QA3前13输入已精确保全；QA3 runner先保存src/manifest/runner/notice原bytes双份，再仅编译snapshot。19构建输入与seal/source/preservation逐字节一致。固定actions=`identity-v3-actions.zh-CN.md`，seal=`identity-v3-frozen-source.json`；旧批/失败不覆盖，新.log=0。

未完成：CUSTOM与两项真实对照、完整typed callback/同session restore、fixed/OEM系统负例、client拒绝/记忆拒绝/manager撤回、独立App/server death与设备重启、首次本机配对、生产向导及独立复审。设备已到，不以device-availability泛化停止。只读准备与独立代码/测试/报告均已交付，后续门由主协调/机主串行执行。

**产品子票21不创建。** 当前grant中断和恢复语义不足以交付生产自动grant入口；新证据/最小可交付方案须先交主协调。A不改生产入口，不把已授权调试替代ACR-01。

恢复只针对本轮确认新增、且约定恢复的固定QA/user0权限；本批MANAGE已外部恢复并读回。其他未知状态由主协调依初态/终态显式处置，不自动撤授权。先remove隔离service并核退出，再白名单处理QA资产，不卸载清数据解决签名、不动生产实例/根盘/其他user、不清理旧证据或Class A。SDK完整MIT随包，无manager/server复制、无新增GPL对象。无网络出站/后台重试/系统安全设置变更、无产品架构变更实施、无commit/push/release或越界操作。

## 2026-10-16 resume-01：QA4 同session旧deadline P2增补

本增补只修独立QA3的submit deadline隔离，旧设备事实、失败、首审及未测门原文保留。恢复起点main@428860ad dirty502；主协调独立完成生产503构建不受本原型影响。Worker未改生产App/runtime/Guest/版本，未执行Gradle/设备/adb/UI/签名、ref/commit/push/worktree或从HEAD重建，未读取任一独立轴报告。本次范围仍是固定QA/user0两权限实验，不增加生产grant入口或产品21。

外部根Q=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01/qa-deadline-fix/`，保全根P=`batch-01/resume-01/preservation/qa-deadline-fix/`。修改前先把20份QA3原型文件及05/06旧报告精确bytes保存至Q/P的before-qa3。19份构建输入与原identity-v3-frozen-source.json、qa3-apk-01/source、qa3-compile-02/source逐项一致；未覆盖旧seal、源码、失败或首审，QA1三源raw缺口继续保留，不以QA4 snapshot倒填。

ProbeActivity的局部变化：每次submit独立Object token与deadline Runnable，deadline必须匹配本次token和当前epoch/可见性；正常finish移除自己的timer，只有当前token可清自己的busy并发布结果。开启permissionSession改到当前token/epoch/可见性成立的主线程完成分支，旧worker结果不能在deadline/end/death/旧Activity退役后重新开启session。当前call的5秒截止仍报告CALL_DEADLINE_UNDERLYING_NOT_CANCELLED；全局IN_FLIGHT只在底层operation真实返回的finally释放，超时/end/death不放行第二个在途调用。未引入通用调度框架、新IPC或持久恢复ledger。build_qa.py仅新增两个批准QA根的output-root选择，继续先双份snapshot再编译副本，APK版本仅由主协调传参。

最终同一测试字节的真实red→green：

| 原件 | 运行结果 | 核心验收 |
| --- | --- | --- |
| Q/activity-deadline-red-02/result.json | 原QA3 javac0；9场景8项运行exit1，当前deadline控制PASS | A snapshot完成后同epoch B grant/readback/restore在途，旧A timer真实++epoch并清permissionSession；另捕获旧finish清busy和迟到snapshot重开session |
| Q/activity-deadline-green-02/result.json | QA4 javac0；9场景110断言全部PASS | A旧timer派发后B正常结果与显式restore可用；旧finish/timer不退役后续call/session；当前截止全局flight至真实返回；snapshot截止、end、death、旧Activity迟到被拒绝 |
| Q/qa4-compile-01/result.json | AIDL0、javac0 | 全部19构建输入真实编译，Worker没有构建或签装APK |

测试编译原样ProbeActivity/ProbeConnectionEvents/ProbeSession/FixedPermissionPlan，以真实按钮/连接回调/生命周期驱动submit，合成队列时钟与latch提供指定交错，远端Binder/Android依赖为JVM替身。它不是复制submit状态机，也不是系统权限、Binder death或设备timeout已验。原72项旧边界检查未重跑，不算本轮新增通过；其余构建输入与QA3保全一致。red01/green01原件保留；green01两项失败是测试替身未把已派发timer标为退役，修正替身后在相同最终测试字节上运行red02/green02，未改断言绕过失败。

Q/identity-v4-frozen-source.json SHA256 `6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15`，22份原型/测试/README输入和notice均在Q/source与P/source双份保存；19构建输入与qa4-compile-01完全一致。Q/qa3-to-qa4.patch及Q/identity-v4-actions.zh-CN.md双份保存，包含固定QA4构建命令、必要设备复核动作、前置/副作用/预期/恢复边界。主协调指定code4/0.1.3-identity，通过runner参数传入，未落生产版本；构建/签装/设备仅主协调执行。源码、runner、tests与README现已停止写入，供独立双轴复审，Worker不自审approve。

本轮签收的是旧timer不再误退役后续调用与restore入口的离线行为。软件内完整grant/callback/同session restore、真实系统death/撤回/OEM/首次配对和生产向导仍须主协调原件；不会由本回归补成已完成。此前CUSTOM/双权限对照若主协调后续已有新设备原件，须独立增补，本增补不读取或代签。内存ledger死亡后的UNKNOWN规则不变，产品21仍未创建，06仍in-progress-device；当前nonprotected=false和真实AVF样本0亦不受影响。

Q/P为Class B，owner主协调，长期保全至维护者验收归档，不自动清理；无网络、系统设置或Class A资产变更。回滚仅由主协调依据before-qa3精确bytes/patch明确实施，不reset/clean、不改用户数据。未触发新的产品架构变更，既有ACR-01未决；剩余设备/复审门如实保留，无越界。

报告人：Worker A / Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未单独计量。
