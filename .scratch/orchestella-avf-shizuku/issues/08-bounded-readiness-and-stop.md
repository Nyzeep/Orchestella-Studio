# 08: 有界readiness、迟到FD与停止/异常分类

Type: implementation
Status: blocked-owner-and-platform
Owner: AVF生命周期代理
Blocked by: 当前ROM nonprotected=false；ACR-01/07及真实connect/迟到FD/owner stop/death收敛

## 用户价值与证据

启动/取消可解释且不会无限占用连接或误标正常停止。研究§7、平台connectVsock/stop共mLock，以及旧09已完成的项目锁/FD修复；本票不重做旧修复。

## 范围与交付

在正确owner路径上定义caller总deadline、连接尝试预算、取消、迟到FD释放、异常/stop意图与session代际。保持单reader，不把无限阻塞包线程后报“已中断”。底层调用可继续时必须明确残余状态/受控进程终态，不静默泄漏无限工作线程。禁止新owner/热恢复/第二reader/无限重连或粗暴终止用户包事务。

## 验收与测试门

- [ ] 成功、迟到、永久阻塞、取消、错误FD、stop/death、旧watcher晚到均以行为fixture验证。
- [ ] caller deadline与底层调用终止分别报告；平台mLock永久占用情况下有真实可验证收敛边界。
- [ ] terminal EOF但VM仍活不误标正常停止；停止意图/进程死亡/错误回调分类固定，旧代不能清除新会话。
- [ ] 取消/失败全部FD最终关闭一次，停止幂等；控制reader与鉴权不变。

red命令基于现AvfVirtualMachineEngineResourceTest及lifecycle契约增加阻塞/迟到fixture，实际入口 `:runtime:engine-avf:test`，设备再验平台真实阻塞/停止。当前不能以约100×200ms推总20秒。性能仅阶段延迟/失败率，不承诺提速；样本遵循09口径。

## 迁移、回滚、许可与报告

无持久盘迁移、token输出或新guest能力；owner由ACR决定。回滚局部生命周期逻辑，保留失败诊断。自研不引入库。实施/review avf-shizuku-08，原始E/implementation-08/。本票未运行；完成须fixture+正确owner设备stop门，不能mock绿即完成。

## 2026-10-16 batch-01 QA1 / QA2 阶段记录与隔离验证

以下保留QA1/QA2阶段记录，当前结论以文首及文末QA3收敛为准。该阶段状态为blocked-owner-and-device；设备已连接，此处阻塞是正确owner及真实stop证据不足，不是“无设备”。canonical [08中文报告](../../../docs/reports/tickets/avf-shizuku-08/implementation-report.zh-CN.md)，本批外部 `avf-platform-device/` 为 B 唯一fixture/动作/证据根，所有新raw为.json/.log.txt并即时preservation。生产engine/adapter/launcher与旧09测试未改。

已运行JDK17/Python离线验证，原件在本批B根evidence：

- lock-red exit1=TimeoutException：未改生产engine+adapter调用外部合成平台shared-mLock，connect未释放时stop到入口但body不能完成。lock-observe exit0分别观测stop_entry1/body0、in_flight1、platform_stopped UNCONFIRMED；此时当前waitFor可以返回0，不能单独作平台stop凭据。finally释放latch后迟到PFD close1/dup0、stop/close各1、worker0。
- caller-deadline-red exit1：未改生产getter无总caller截止，外部观察窗get超时。200ms只是fixture参数，非ROM承诺。
- bounded-red exit1：错误设计假设“Future done=底层结束”被反证，不冒充生产red。
- bounded-lease-green exit0：最终9组合成候选覆盖全session单flight/有限在途、总budget/attempt cap、永久拒绝、nullFD、lateFD一次close、重复stop、stop return与独立owner观测分离、EOF未知、旧代/cancel、close失败不放盘，以及全App盘lease防跨generation/同盘fallback并发。初轮8行PASS但硬编码计数9的原件和源码保留，最终9组以本次动态计数为准，不累计重复运行。

这些为 JVM_SYNTHETIC；替代平台、SyntheticFd内存计数、显式clock/tick和模型stop observation不证明Android FD、Binder/死亡、ROM无限hang或平台中断。观察窗口内持续阻塞后在finally放行，保证fixture结束；不把Thread.interrupt/Future.cancel签成native终止。没有生产修复green或真实AVF样本。

可审阅方案要求caller deadline退役后仍保留底层在途额度，最多一个connect+一个stop worker、零无限队列；旧call/owner/resources未收敛跨代保留App全局盘lease，未知保持STOP_UNCONFIRMED，禁止同盘QEMU重启。真实进程终态路线需ACR明确批准且当前ROM证实，getStatus也可能共享平台锁，不能加无穷status线程。owner/FD/IPC审批方案详ACR报告；07正常对照后再由主协调按固定动作包执行新QA异常窗口。生产定时器、真实owner/death/stop/FD回收及独立双轴review仍未完成。

QA2最新真实结果：`device/identity-v2-synthetic-gates-receipt.json`取证exit0，内部USER_SERVICE_CALL_FAILED / OPERATION_FAILURE，未形成独立state/FD结果；不等Context EACCES、FD被SELinux拒绝或路线不可行，前置snapshot也不证明后续失败前无文件副作用。最早门为A的QA3固定stage/class/errno诊断，随后实际manager/nonprotected与G4 FD入口，不输出path/message。B按此准确收口，QA3新原件由主协调回传后再定点收敛；当前真实AVF样本0，08不转completed。

## QA3 最终真机收敛与P2保全（当前结论）

本批 `device/qa3-manager-capability-receipt.json`：US固定manager构造/getCapabilities成功返回QUERY_RETURNED_DECLARATION_ONLY，serviceCapability_capabilityBits=1、declaresProtected=true、declaresNonProtected=false；App在MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE。顶层identity snapshot的NOT_CREATED/NOT_QUERIED不覆盖后续分项。**当前ROM对现nonprotected adapter不支持**，无create/run，不用protected声明或Microdroid替代custom Linux。状态更新为blocked-owner-and-platform，正确owner/真实VM与stop/death门继续blocked。

`device/qa3-context-fd-receipt.json`：App新QA state可写，US在所给App Context的DATA_DIR_LSTAT为OS_ERROR_13；固定32B App→US合成PFD positive读写、receiver关闭、caller原件保持true，wrongmode/type/same/replay/symlink/stale/end拒绝有原件。closed FD仅签发送或接收边界拒绝，不编造具体内核错误；这些不等平台state、Java path配置、US→virtmgr/crosvm或VM FD/死亡收敛。合法Context/path/state/FD owner仍ACR，不以合成FD通过放行生产。

独立Spec P2的ready后observeStop缺陷已在外部模型真实red→最小修复，新增5组及原9组回归均exit0，源码冻结在 `avf-platform-device/seals/p2-stop-observation-fixed/`；当前代stop退役ready/一次关闭已发布FD，保留分类/底层在途/lease，旧代拒绝。各reviewer已获该seal，B不代签复审。此次QA3收敛不改模型、不重跑无关套件，不改生产。

当前技术建议保留R0软件执行；在兼容nonprotected平台与G4入口证据齐备后，才请求用户裁定具体Context/path/state/FD owner及本轮秘密可读边界，详ACR报告§4–5，不泛求批准。07/08设备门、09/10真实样本继续blocked，真实AVF0。新最终seal为 `avf-platform-device/seals/qa3-rom-nonprotected-unsupported-verified/`；全部旧seal/失败/P2源保留原bytes。

## 2026-10-16 resume-01 最终证据收口

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

候选模型ready后observeStop的P2在avf-platform-device/seals/p2-stop-observation-fixed已红绿闭合，新增5组和原9组回归分述；08/spec-rereview-20261016限定approve，规范轴既有定点复审闭合结论沿standards-review。它们不是生产owner或ROM stop修复。QA4无VM App/server死亡不证明virtmgr/crosvm终态；真实在途connect、mLock/迟到FD、正常关机、资源释放及同盘lease门仍未验，不能从Future/caller完成推底层停止。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
