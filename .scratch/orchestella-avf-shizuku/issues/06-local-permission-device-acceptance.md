# 06: 本机两权限授予、撤回与Shizuku设备验收

Type: prototype
Status: partial-device-verified; in-app-permission-ux-gates-open
Owner: Worker A / 主协调（唯一设备操作者）
Blocked by: 完整typed grant/readback/同session restore、首次本机配对、拒绝/记忆拒绝/管理器撤回/reboot/OEM及原产品UX；不再以设备未到停止

## 用户价值、来源与范围

验证无需电脑重复命令的实际权限准备闭环，避免仅grant返回就报成功。研究§4–6、A PermissionService固定源码为依据。允许QA流程检查server身份/用户ID/manifest requested permissions，用户主动选中两权限后有限授予并重新查询；先隔离工具，生产grant入口是否可交付需本票结论另切实现。禁止任意pm/shell接口、跨用户操作、Root自动选择、隐式授权、API豁免或把grant当AVF成功。

## 验收、失败与测试

- [ ] 目标OEM首次安装/本机配对/启动、App允许/拒绝、server death/设备重启逐项真机记录。
- [ ] MANAGE/CUSTOM分别合法grant/revoke并重新查实际结果；fixed flag、未声明、无GRANT身份/cross-user、策略拒绝明确失败。
- [ ] 允许一个但另一个拒绝时不宣称准备成功；用户选择重试或ADB兜底，自动重试次数为0。
- [ ] 管理器撤回可能force-stop App：只在新QA实例/无活事务条件验，冷恢复/根盘引用不变；Android grants与client授权分查。

Seam：typed permission executor的合成正负fixture +目标设备实际许可查询；实现前先red证明无身份/拒绝不会通过。当前无生产executor，具体命令由隔离原型README固定后再跑，不虚构已通过。设备门为用户本人系统确认与无活VM/包事务；性能不适用。

## 数据、迁移、回滚与许可

只改明确选择的固定QA/currentuser两权限，保留初值并按约定恢复，不动生产实例/owner/根盘。日志仅固定原因码，不含credential/设备ID。复用MIT SDK；无server代码复制。报告 `docs/reports/prototypes/avf-shizuku-06/prototype-report.zh-CN.md`；本批E=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，A证据research-auth-device与preservation、设备原件device与preservation。当前已有独立QA实际client/身份、MANAGE变更中断、重开partial及ADB恢复读回；软件内完整双权限流程/其他系统门仍未验。本票不代表AVF已跑通。

## Comments

2026-10-15：独立于ACR-01，可使用手动development授权验证；不成为07的无意义强制前置，07可用既有合法ADB授权。

2026-10-16 batch-01：设备已到，03本地已完成，恢复本机验收。主协调安装官方Shizuku13.6.0/code1086并以官方ADB starter启动，实际server UID2000；这是ADB准备证据，不当本机首次配对。独立QA1 clientGranted真机读回true，App MANAGE/CUSTOM仍均DENIED；US两项GRANTED另表，不互代。remove=true后目标QA进程无返回；其他death/撤回/重启未验。固定typed权限计划已实现并JDK27项正负门exit0（真实Android变更0），涵盖资格零写、逐readback/partial/death/stale/unknown及只恢复本次新增。Android执行器与当前user固定两权限实际授予/撤回待主协调窗口，不开产品21冒充已证。canonical报告 `docs/reports/prototypes/avf-shizuku-06/prototype-report.zh-CN.md`；本批E=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，A证据research-auth-device与preservation，设备原件device与preservation。生产旧系统包/另一签名和user128数据由主协调保全，QA不签收500向导或数据升级。

QA3已将typed executor提升canonical独立QA，固定QA/user0、两permission enum、真实caller/epoch、requested/development/fixed flags/GRANT/REVOKE资格；显式选择逐项readback，只有两项读回GRANTED才BOTH_GRANTED_PREPARATION_ONLY。恢复仅同live session确认新增，初态已有grant不撤；death后ledger未知需主协调依据事前原件显式处置，不自动撤。主协调指定code3/0.1.2-identity；最终qa3-compile-02 aidl/javac0，qa3-regressions-01实际Java边界72项0含权限27项，Android变更由主协调QA3设备窗口取证，当前仍未签收。固定动作identity-v3-actions.zh-CN.md及19输入冻结seal/双份raw已交；产品21仍等真实权限证据先报主协调，不改生产入口。

QA3真实权限中断与恢复：qa3-permission-session-initial证user0/QA UIDAPP_UID两项DENIED、development请求及GRANT/REVOKE资格true、flags0。MANAGE动作后旧action receipt未更新，但permission-interruption-readonly-01外部读回MANAGE=true且QA App/US均退出，不能说未执行；具体退出触发者UNKNOWN。qa3-after-manage-app-readback真实App MANAGE GRANTED/CUSTOM DENIED，构成partial；主协调固定QA/user0 ADB revoke新增MANAGE exit0，qa3-after-manage-restore-readback真实App两项回DENIED、client仍true。只签这条发生→中断→重开partial→ADB人工恢复闭环，不签typed callback/内存restore或软件双权限成功。完整typed流程、CUSTOM、client拒绝/记忆拒绝/撤回/重启/OEM/本机首次配对和生产向导仍待验，产品21不创建。QA3能力查询另证bits1仅protected，不满足当前nonprotected Java adapter，授权准备不等AVF可跑。QA1三份关键原源码保全缺口已如实审计，不影响不得以新源码冒充历史的边界；QA2单独保全13输入、QA3开始先snapshot双份再编译。

## 2026-10-16 resume-01 最终证据收口

R/permission-control-summary.json为PASS_ADB_CONTROL_AND_RESTORATION_ONLY，CUSTOM单项及MANAGE→两项授予的六次固定ADB grant/revoke、六次App冷读回全部匹配，最终恢复两DENIED。该控制计划完成不代表软件内按钮/回调/内存restore或生产授权向导成功。QA3 MANAGE生效→App/US退出且receipt未更新→重开partial→ADB人工恢复的历史不删除，退出触发者仍UNKNOWN。

QA4 code4/0.1.3-identity的R/qa4-device-summary.json为PASS_LIMITED_REAL_QA_SESSION_AND_LIFECYCLE：普通permission session同epoch跨旧5秒点8277ms仍可readback，normal remove；App force-stop→US退出且server活；固定shell server TERM→App观察SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED、US0；官方starter重启后binder/clientGranted读回true、重新bind成功。最终QA双DENIED、US0/server1、crosvm/virtmgr0。原件R/device/qa4-final-*及preservation；早期QA3强停证据R/device/after-qa-app-death.json保留。无权限变更/VM，未强制真实B在途timer交错，不能签真实deadline交错、完整软件grant/restore或VM死亡。

QA4末P2源码/JVM已由05/spec-final-qa4与standards-final-qa4限定approve；九场景/110断言只是受控队列边界。普通session和server重启/rebind实测已补，设备reboot、机主首次本机配对、client拒绝/记忆拒绝/管理器撤回与OEM真实系统负例仍未验。软件内完整grant/readback/同session restore及原产品签名升级/向导仍未闭合，21没有文件、不创建。

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
