# 07: 真实custom Linux Guest端到端闭环

Type: implementation
Status: blocked-platform-and-architecture
Owner: AVF实施代理 / 主协调 / 设备维护者
Blocked by: 当前ROM nonprotected=false；兼容平台、05/G4实际VM FD与合法state/owner、ACR-01用户具体裁定

## 用户价值与来源

在合法受控调用路径证明本应用运行完整Linux并可交互，失败仍诚实回退。研究§5–7、403反证、A/B一手源码。06不是必需前置，可使用已经合法获得的development grants。

## 范围与交付

仅ACR批准后的类型化调用/owner与现契约接线；新QA raw盘、pinned kernel/基础initrd/明确distro hash，精确1CPU及满足预算RAM，create/run→认证agent→终端命令/resize→正常停止。禁止复制用户盘/public目录、替换kernel/GPU/VMM、隐式共享/额外reader、Microdroid成功替代customLinux。

## 行为验收与测试

- [ ] 当前APK/commit/ROM/module、App/代理身份、session/generation/VM owner/FD来源绑定。
- [ ] API/权限/nonprotected/配置/FD错误各自fail-closed，回退真实QEMU样本标QEMU。
- [ ] run返回后独立得到vsock、认证agent、固定QA uname/CPU/命令输出及resize；不等待不存在通用payload回调。
- [ ] 正常stop、双启动/重复stop、旧回调、取消/death、错误FD与配置不兼容均有fixture和设备门。
- [ ] 全部既有实例ID/根盘/凭据指纹保留，无未授权活VM被停止；实验QA实例正常关闭。

Seam为现engine/launcher/FullLinux契约与真实设备receipt；先red当前hiddenAPI/owner失败，然后最小路线green。具体运行命令固定到批准原型/测试脚本，当前未实现不得虚报。设备与ACR均必需。性能本票只记录阶段，不作收益比较。

## 生命周期、回滚、许可与证据

坚持单reader/token/私有raw盘和App死亡收敛语义；FD所有权与错误回收按ACR。回滚仅新调用路径，原QEMU与盘不变，不delete持久VM来掩盖不兼容。最终依赖许可另核，GPL源码交付门不变。中文实施/review avf-shizuku-07；原始E/implementation-07/与preservation。当前真实AVF零样本，状态不得ready。

## 2026-10-16 batch-01 QA1 / QA2 阶段记录

以下保留QA1/QA2阶段记录，当前状态以文首及文末QA3收敛为准。该阶段状态为blocked-architecture-and-device；此处device表示所需真实VM证据未齐，当时设备已连接且主协调已完成QA1身份门。canonical中文报告 [07报告](../../../docs/reports/tickets/avf-shizuku-07/implementation-report.zh-CN.md)，技术路线详 [ACR报告](../../../docs/reports/tickets/avf-shizuku-acr-01/implementation-report.zh-CN.md)。本批外部 `avf-platform-device/device-action-packet.zh-CN.md` 已给G0–G4和07串行动作、输入/预期/副作用/停止及恢复条件；执行权仅主协调，B无adb/UI/Gradle窗口。

QA1真实AppAPP_UID/untrusted_app的builder成员隐藏，shell UserService2000的成员可见但未invoke；manager/capability/state/FD/VM仍未测。下一步A QA2 manager/context/state/合成FD及G4真实配置接纳，不能把FD接收、Context metadata或shell两权限当可交付VM。新生产owner/IPC须用户裁定ACR后修订ADR/spec，再放行。

07动作预备为精确1CPU/nonprotected/合预算RAM、新QA盘+pinned输入、真实owner/create/get/run关联、原单reader认证、固定QA uname/CPU/合成命令/resize、网络与owned services、认证power正常关机及独立stop/资源收束。run/state/vsock不等Guest ready，payload callback不适用；现App服务转发缺完整接线不以库fixture代验收。未确认stop不得同盘QEMU回退。

B只完成外部离线fixtures和方案，未改生产engine/adapter/launcher或旧09已修STOP/FD/persistence。08有界候选最终9组合成green不算07运行；create/run/Guest/服务/normal stop真实样本仍0，暂无性能数据或独立review签收。修改前原票bytes/hash、原始red/green及即时preservation均保全于本批B根。

QA2最新真实结果已纳canonical：`device/identity-v2-synthetic-gates-receipt.json`的取证exit0，但实验内部USER_SERVICE_CALL_FAILED / OPERATION_FAILURE，未产生独立state/FD结果。它不能证明Context EACCES、FD消费被SELinux拒绝或路线不可行；snapshot中NO_FILES_OPENED_OR_CREATED也不能证明后续失败前零文件副作用。主协调已交A用QA3固定stage/class/errno定位，不回显path/message；manager/nonprotected仍需实际调用，G4 Java path-only仍硬门。07生产及真实VM闭环继续blocked，不能由QA2已运行改绿。

## QA3 最终真机收敛（当前结论）

本批 `device/qa3-manager-capability-receipt.json`：US真实UID2000/shell，固定manager构造/getCapabilities返回QUERY_RETURNED_DECLARATION_ONLY，serviceCapability_capabilityBits=1、declaresProtected=true、declaresNonProtected=false；App在MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE。顶层identity snapshot的NOT_CREATED/NOT_QUERIED不覆盖后续分项。**当前ROM对现nonprotected adapter不支持**，无create/run，不拿protected声明或Microdroid替代custom Linux。状态更新为blocked-platform-and-architecture；不是无设备，也不是实测VM启动失败。

`device/qa3-context-fd-receipt.json`：App NEW_QA_STATE_WRITABLE；US对所给App Context的DATA_DIR_LSTAT返回OS_ERROR_13。32B App→US合成PFD正向读写/receiver关闭/caller原件保持true，wrongmode/type/same/replay/symlink/stale/end负门有原件。这里证明的是受限FD能力，不是shell对私有路径访问权，也不是Java path配置、平台state或US→virtmgr/crosvm可行。closed FD仅签发送或接收边界拒绝，真实不同UID负客户端、VM owner及死亡收敛仍未验。

07/08的正确owner、认证Guest、网络/服务、normal stop设备门继续blocked；09/10不产生真实AVF样本，真实AVF0。现有动作包中VM动作只作为未来兼容平台的预案保留，当前不执行。当前建议R0软件执行；兼容平台/G4入口具体证据齐备后，才由用户裁定Context/path/state/FD owner及本轮秘密可读边界，详ACR canonical§4–5，不泛求生产代理批准。

B只更新本票/三报告/动作包与引用封板，不改生产或模型。新seal `avf-platform-device/seals/qa3-rom-nonprotected-unsupported-verified/` 保全全部旧seal/失败/P2冻结源；P2新增5组和原9组回归证据保持，独立review结论由原reviewer签署。

## 2026-10-16 resume-01 最终证据收口

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

QA4普通session/App-server death/reconnect仅证明无VM隔离UserService生命周期，ADB两权限对照仅证明授权/恢复，不打开本票真实VM门。仍无create/run、vsock/认证Guest/QA命令/resize/网络/services/正常stop原件，不能说VM已启动失败或已通过。保留现软件执行与原盘/数据，不试protected/Microdroid替代。技术方案沿ACR/07报告和avf-platform-device既有封板，不重做研究。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
