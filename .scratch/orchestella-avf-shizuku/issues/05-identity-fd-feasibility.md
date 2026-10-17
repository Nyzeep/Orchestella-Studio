# 05: UserService身份、Context与合成FD可行性实验

Type: prototype
Status: partial-device-verified; fd-owner-upgrade-gates-open
Owner: Worker A / 主协调（唯一设备操作者）
Blocked by: 跨UID Binder负客户端、真实timeout/迟到FD、完整跨升级state/config及G4/owner；当前ROM nonprotected=false，ACR用户未裁定

## 用户价值与来源

在不动用户根盘前决定是否有合法可交付的AVF调用路线。研究§5–6，A source-only isolated-probe、B Context/virtmgr/FD源码是来源。普通client03有助UX，但实验可独立QA程序执行，不强制依赖03。

## 范围与交付

仅明确隔离QA包/原型，App与UserService固定identity/API shape、Context state目录、合成只读FD/可写新QA文件，严格caller UID/session/version。先不createVM；各门有原始脱敏receipt。若需要create/run则另走07范围。禁止general exec、任意path、用户盘/public副本、global hiddenAPI/SELinux/targetSdk修改、Root/解锁。源码放明确prototype目录，实验实现不直接并入生产。

## 验收与测试seam

- [x] 当前独立QA APK/source与device-01 API36证据绑定，App/US真实UID/domain与Context正例/拒绝分别记录；AOSP pin只作研究基线，不冒充ROM精确源码。
- [x] API shape与实际manager调用分开；真实US能力bits1、protected声明true/nonprotected声明false，App constructor METHOD_UNAVAILABLE，不推VM运行成功。
- [ ] 合成FD主要门已真实运行：App→US固定RO/RW、wrong mode/pipe/同对象/重放/symlink/旧epoch/end及副本关闭；closedFD仅证发送或接收边界拒绝，具体端未知。wrong caller为真实Java gate合成输入，跨UID Binder负客户端及超时/迟到FD设备门仍未运行；IPC没有path输入。
- [ ] daemon=false/remove=true与normal remove已实证；App/server death及client撤回须独立取证，MANAGE变更后的App/US退出不代替已知因果的death实验。
- [ ] 实验严格使用新QA namespace，无生产实例/根盘读取；同签名QA1→QA2→QA3升级已执行，完整state/config跨升级与owner兼容、所有关闭确认仍待验。

red/green历史：2026-10-15 A研究版source-only未编译；canonical AIDL因方法id混用真实失败，inspect显式id=0后aidl/javac均0、6个class，旧报告保留。2026-10-16已装配独立QA1/2/3并由主协调签装运行；QA1刷新/QA2合成门诊断真实失败保留，QA3分步诊断后取得Context/合成FD/capability原件。旧disconnect P2真实公共边界red1→guard green0；最终实际Java边界72合成检查全0、QA3 aidl/javac0/0。设备未运行门明确保留，不以合成签收；性能不适用，AVF样本0。

## 数据、回滚、许可与报告

只读现有数据；创建文件只新QA目录。无token/embedded载荷/凭据输出，IPC无guest通道。回滚卸载/删除实验QA资产需按白名单另核，不改产品。MIT client声明保留。报告 `docs/reports/prototypes/avf-shizuku-05/prototype-report.zh-CN.md`。本批E=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，证据为research-auth-device与device，各有preservation；旧E/prototype-05历史指针不覆盖。当前已有真实identity/Context/合成FD与capability结果，但完整死亡/跨升级/owner门未签收，实验不升级为生产架构或VM能力证明。

## Comments

2026-10-15：零设备；这是可行性实验授权范围，不是生产VM代理批准。

2026-10-16 batch-01：已按新交接重新claim，设备已到。canonical原型装配独立QA1，固定SDK四模块13.1.5/MIT，主协调签名安装user0。App实际UIDAPP_UID/untrusted_app，customBuilder成员隐藏；UserService实际UID2000/shell，成员可见但未调用；APP_UID。capability NOT_QUERIED/manager NOT_CREATED/AVF0。真机旧epoch/end后拒绝通过，remove后目标pidof无返回；其余死亡/撤回不代签。QA1首轮CLIENT_QUERY_FAILED真实失败保留，QA2分字段修复。QA2 Context/新namespace/合成32B RO/RW FD及负例已实现、AIDL/javac0，实际metadata gate11项和session gate24项合成通过；设备结果待主协调。正式canonical报告 `docs/reports/prototypes/avf-shizuku-05/prototype-report.zh-CN.md`，本批E=`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`，A evidence=research-auth-device、设备=device，均preservation。不能把identity/shape成功当Context/FD或VM成功，ACR仍未决。

QA2实机 `device/identity-v2-synthetic-gates-receipt.json` 只得USER_SERVICE_CALL_FAILED/OPERATION_FAILURE，前门identity/stale仍成立；没有Context/FD字段，不能定位具体拒绝门。QA3已完成逐stage类型/errno及单调副作用ledger修复、固定QA/user0权限实验和最小manager ctor/getCapabilities只读门（无VM），aidl/javac0/0，实际Java边界72项0（24+11+27+4+6）。需求轴旧disconnect P2真实red1→guard green0；规范轴NO_FILES副作用P2已按initial observation/后续effects分开。QA3冻结 `research-auth-device/identity-v3-frozen-source.json`，19份原型构建输入bytes在qa3-compile-02/source与preservation双份，固定动作identity-v3-actions。QA1三份关键原源码缺口已审计qa1-source-preservation-gap.json并告主协调/两review轴，不以QA2或反编译冒充。真实QA3结果已于本批回传：device/qa3-context-fd-receipt.json证明App新QA state可写、US DATA_DIR_LSTAT=OS_ERROR_13、App→US合成RO/RW FD正例与模式/类型/同对象/重放/symlink/旧epoch/end拒绝、receiver副本关闭和caller原件保持。closedFD只证发送或接收边界拒绝，具体端未知。device/qa3-manager-capability-receipt.json真实US getCapabilities返回bits1，仅protected=true/nonprotected=false；App manager constructor METHOD_UNAVAILABLE。当前nonprotected Java adapter不满足该ROM能力声明，AVF样本仍0，不试protected/Microdroid冒充customLinux。Context拒绝/合成FD可行/capability限制分条写05 canonical并给ACR建议，完整death/跨升级/owner门与独立复审仍开，05不签completed。

## 2026-10-16 resume-01 最终证据收口

QA4 code4/0.1.3-identity的R/qa4-device-summary.json为PASS_LIMITED_REAL_QA_SESSION_AND_LIFECYCLE：普通permission session同epoch跨旧5秒点8277ms仍可readback，normal remove；App force-stop→US退出且server活；固定shell server TERM→App观察SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED、US0；官方starter重启后binder/clientGranted读回true、重新bind成功。最终QA双DENIED、US0/server1、crosvm/virtmgr0。原件R/device/qa4-final-*及preservation；早期QA3强停证据R/device/after-qa-app-death.json保留。无权限变更/VM，未强制真实B在途timer交错，不能签真实deadline交错、完整软件grant/restore或VM死亡。

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

QA4最后旧submit deadline P2已按每operation token/自身timer/finish隔离、真实底层返回才释放flight修复；R/qa-deadline-fix/identity-v4-frozen-source.json、activity-deadline-{red,green}-02/result.json证明实际Activity合成队列九场景110断言PASS。05/spec-final-qa4-20261016.zh-CN.md与standards-final-qa4-20261016.zh-CN.md均限定approve，源码/JVM复审门闭合；真实系统精确交错仍单列。normal remove及App/server死亡不再列作全未验，但仅签无VM隔离QA范围。

QA1 ProbeActivity/IdentityProbeService/IIdentityProbe.aidl三份精确原源码bytes缺口持续；QA2/QA3/QA4快照不倒填。当前state升级/G4/VMowner及真实FD死亡收束未完成，不标整票completed。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
