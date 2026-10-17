# ACR-01: 特权代理VM所有权与有限FD/IPC边界

Type: decision
Status: needs-architecture-decision
Owner: 用户（最终裁定）/ 主协调（证据初审）
Blocked by: 用户未裁定；对应模式的兼容平台/合法VM入口、G4私有FD与state namespace、具体owner/秘密可读能力/死亡及迁移回滚证据（2026-10-17 protected优先规划见末节，不要求所有研究先有non-protected平台）

## 问题与受影响契约

研究§5/§9及A/B固定源码证明：原App hiddenAPI受限，UserService不是有效App Context，VM state/virtmgr/FD绑定实际调用者，不能只替换Binder/包名。生产代理将改变ADR0002/0003/0007/0009/0010/0011/0016下的VM owner、App死亡随停、私有根盘和暴露面。现有权限请求研究不构成这项批准。

## 待裁定方案与推荐

QA3已取得当前ROM nonprotected=false，现nonprotected adapter对此ROM不支持。当前技术建议R0：保留诚实软件执行，停止07/08本机VM窗口，09/10不产生真实AVF样本，不以protected/Microdroid替代；这不需要泛求“继续AVF”的批准，也不代表用户已作新架构裁定。

未来真正待用户裁定的是兼容平台与G4入口证据齐备后的Context/path/state/FD owner具体边界：是否接受App保留私有盘/身份/会话权威，单个daemon=false、受App lease约束的进程取得本次固定RO kernel/initrd、单RW root及明确state能力，按已验证死亡链收敛；或继续R0。QA3已证所给App Context路径在shell下EACCES，合成PFD成功不能直接解决Java path型配置，必须先有合法namespace/实际入口/迁移回滚，不以“允许Shizuku代理”概括批准。assembled initrd FD的本轮秘密可读能力仍须显式裁定。完整有限方案见ACR canonical报告§4–5，当前不放行生产owner/IPC。

## 允许与禁止范围

允许架构文档、威胁模型、失败状态与经批准隔离实验；禁止生产代理、general shell/guest特权命令、私有目录放权/public盘拷贝、平台安全设置修改、静默变更ADR或持久namespace。

## 决策验收

- [ ] 每条VM操作的caller身份、实际权限检查位置、进程domain和Context已证。
- [ ] kernel/initrd/root/state FD来源、hash、可写性、symlink/路径、所有权转移/释放和秘密生命周期明确。
- [ ] IPC版本/caller/session代际/重复请求/取消/timeout/FD关闭/旧回调/Binder death/撤回负例齐全。
- [ ] 单reader、token鉴权、单实例和默认回环语义保留；不从Guest派发shell权限。
- [ ] 用户明确裁定，必要新ADR/旧ADR superseded和spec/票更新后才恢复受影响implementation。

## 测试、迁移与回滚

Seam为05实际实验与类型化协议负例设计，red为不合法身份/FD/旧代际拒绝；没有可运行实现时不得编造测试命令已通过。设备必须提供当前ROM证据；性能不适用。迁移不得移动现有根盘或删除持久VM；回滚回旧App/软件执行策略并关闭新代理引用，已有盘/身份保留。依赖/许可需按最终路线审查，当前无新产品依赖。

## 报告与当前结论

证据来源为既有research-auth/research-performance与本批QA1–QA3真实原件。正式技术方案为 `docs/reports/tickets/avf-shizuku-acr-01/implementation-report.zh-CN.md`，独立审查保留各自报告。QA3真实manager声明nonprotected=false，App Context路径在shell下lstat EACCES，固定32B App→US合成PFD正负门已有分项结果；这不是VM成功，当前ROM的现adapter不支持。G4实际VM FD入口、合法state/owner/死亡链及具体用户裁定仍未闭合，冻结07/08等新owner生产实现，其他独立工作继续。

## Comments

2026-10-15：主协调初审确认是真架构边界，不由普通client票或本次研究授权替代。

## 2026-10-16 batch-01 QA1 / QA2 阶段记录

以下保留QA1/QA2阶段的证据与判断历史，当前结论以文末QA3收敛及canonical报告为准。状态继续 needs-architecture-decision；设备已连接，不再以“无设备”笼统阻塞。正式 canonical 为 [ACR 技术报告](../../../docs/reports/tickets/avf-shizuku-acr-01/implementation-report.zh-CN.md)，07/08 各有独立报告。证据根 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/avf-platform-device/`，原票修改前 bytes/hash 与局部副本已保全，B 未改生产 owner/IPC、ADR/spec/map 或版本。

主协调 QA1 真机原件：APP_UID/untrusted_app、client 已授权但自身 MANAGE/CUSTOM denied、customBuilder member hidden；UserService UID2000/shell、自身两权限 granted、builder MEMBER_VISIBLE_NOT_INVOKED，APP_UID；manager NOT_CREATED、capability NOT_QUERIED、dataDir AVAILABLE_NOT_OPENED、NO_FILES_OPENED_OR_CREATED、AVFfalse。正常 remove 后精确 QA 进程未检出。canonical 原件为本批 `device/identity-v1-authorized-app-receipt.json`、`identity-v1-user-service-receipt.json`、`identity-v1-remove-confirm.json`，不能外推 VM death/stop。

最早剩余门是 A QA2 的真实 manager/context/state/合成 FD，随后 G4 必须证明选定 VM 入口能消费受限 FD。固定 AOSP Java custom 配置在调用进程按路径打开 kernel/initrd/root，因此 Binder FD 读写通过仍不等真实 VM 配置可用；不允许 `/proc/self/fd`、改 packageName、目录放权或公共复制替代证据。libavf 的 LLNDK/APEX/API36 标注只作静态比较，非普通 NDK 分发承诺。

B 已交具体 R1/R0 条件方案、caller/user/package/version/epoch/FD/state/死亡威胁模型、迁移回滚及07/08固定动作包；R1为仅在05/G4通过后裁定单个 daemon=false、受App全局盘lease约束的有限调用进程，App保有盘/身份/会话，拒绝任意path/exec/Binder透传；R0保留现App与诚实软件执行。详细可审阅边界只在ACR报告§5，不代用户批准。平台长期持锁时stop不保证完成，未知就保留STOP_UNCONFIRMED及盘lease。

离线分层：生产+合成shared-mLock的lock-red/caller-deadline-red均exit1；正确观测fixture exit0；错误Future完成=底层结束假设red exit1；候选有界模型最终9组exit0，详08报告及原件。绿只证明合成约束，未验证ROM/真实FD/death/VM。用户裁定及ADR/spec修订仍未完成，07/08生产继续冻结；其他独立工作持续。

QA2已实际执行：主协调 `device/identity-v2-synthetic-gates-receipt.json` 读取exit0，但内部为 USER_SERVICE_CALL_FAILED / OPERATION_FAILURE，尚无独立Context-state/FD结果。不能把取证exit0当实验通过，不能从通用操作失败推出EACCES、SELinux FD拒绝或路线不可行；前置身份snapshot的NO_FILES_OPENED_OR_CREATED也不证明失败前无文件副作用。最早门改为A的QA3固定stage/class/errno诊断，再补真实manager/nonprotected及G4，禁止回显path/message。R1目前只是具体条件方案，技术前提尚不足以放行生产。ACR报告还明确assembled initrd RO FD可能使调用进程读到本轮token/凭据，RO与禁日志不消除该可读能力，必须作为用户显式裁定边界，不能虚称代理无法读秘密。

## QA3 最终真机收敛（当前结论）

canonical原件：本批 `device/qa3-manager-capability-receipt.json` 与 `device/qa3-context-fd-receipt.json`。US真实UID2000/shell，固定manager查询QUERY_RETURNED_DECLARATION_ONLY、serviceCapability_capabilityBits=1、declaresProtected=true、declaresNonProtected=false；App在MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE。顶层identity snapshot的NOT_CREATED/NOT_QUERIED不覆盖后续namespaced分项调用。**当前ROM对现nonprotected adapter不支持**，没有create/run，不能拿protected声明或Microdroid替代custom Linux。

Context/FD分项：App NEW_QA_STATE_WRITABLE；US在所给App Context的DATA_DIR_LSTAT阶段OS_ERROR_13（EACCES，不单凭errno归因SELinux）。App→US固定32B合成RO/RW PFD读/写回、receiver副本关闭、caller原件保持均true；wrongmode/type/same/replay/symlink/stale/end拒绝各有字段，closed FD只签发送或接收边界拒绝，不编造具体错误。合成FD成功不授shell私有路径访问权，不证明Java path配置、平台state、US→virtmgr/crosvm、跨UID负客户端或VM死亡收敛。

当前建议R0保留软件执行路径，07/08正确owner与真机Guest/stop门继续blocked，09/10不产生真实AVF样本，真实AVF0。未来只有兼容nonprotected平台与G4实际入口具备后，才由用户裁定具体Context/path/state/FD owner和本轮秘密可读边界；不泛求批准、不生产新owner/IPC。完整最小待决见ACR报告§4–5。旧QA1/QA2失败、首审与P2封板均保留；本次新seal `avf-platform-device/seals/qa3-rom-nonprotected-unsupported-verified/`，模型/生产不动，P2复审由原reviewer独立完成。

## 2026-10-16 resume-01 最终证据收口

E/device/qa3-manager-capability-receipt.json真实getCapabilities bits1，仅protected=true、nonprotected=false，当前ROM不兼容现nonprotected custom Linux。E/device/qa3-context-fd-receipt.json中App新QA state可写，US所给App Context目录EACCES；App→US合成PFD通过仍非G4实际VM FD入口、合法持久state或owner。真实AVF样本0；07/08/09/10按准确外部门阻塞，ACR-01用户未裁定。

ACR/07/08技术方案与模型沿现报告/封板，不重做研究。QA4 ordinary session/App-server death/rebind限定PASS和ADB两权限对照不解决VMowner/G4，不能据daemon=false/US消失签VM收敛。当前R0按真实ROM限制保留软件执行路径，是技术建议而非用户已经批准新架构。未来R1必须具体明确namespace、真实caller/user/签名/代际、有限FD/assembled initrd秘密可读边界、正常stop/失败/死亡链和迁移回滚，用户裁定及必要ADR/spec后方能产品化。新生产特权IPC/VMowner仍冻结；本次文档未实施架构变更。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17 近期protected研发优先增补（规划，不关闭本ACR）

用户已确认protected优先研发、non-protected持续兼容、QEMU保证现FullLinux可用。当前non-protected适配器对该ROM不支持的事实保持；历史“未来先有兼容nonprotected平台”只约束原路线，不再作为独立protected原型的前置。新路线的模式、boot、私有来源/数据与state也必须各自合法，不能通过本次优先级调整消除。

近期方案见 `docs/reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md`；[受控平台pVM](24-bounded-protected-platform-probe.md)→[最小custom](25-protected-custom-boot-feasibility.md)→[私有盘/state入口](26-protected-private-data-feasibility.md)为新的证据路径。低成本来源/label/网络可行性预检可先行，典型App私有raw被protected参考规则拒绝须作为产品否决性门，不PublicCopy/chcon绕过。系统payload成功仍非FullLinux。

本ACR只裁定实际caller/owner/namespace/FD与embedded秘密可读/死亡及迁移回滚，必须比较原App owner最小API适配，不能预设一定UserService迁移。新[ACR-02](ACR-02-protection-semantics.md)专门处理保护意图/降级和隔离语言（含pVM双向vsock的ADR0010勘误），与本ACR并行准备、互引受影响规则，不互相代替或循环阻塞。

Status保持needs-architecture-decision；用户未批准具体新的生产owner/私有盘迁移/IPC或秘密读取面。本回合只追加文档与before保全，无原型/设备/源码/acceptedADR变更。07/08/09/10原真实验收门未被关闭。
