# ACR-01 技术方案与证据报告：有限 VM owner / FD / IPC 边界

- 票号：ACR-01；执行：Worker B / Codex，GPT-6.1-sol；日期：2026-10-16。
- 主仓：`Orchestella_dev`，`main@428860ad9a1cb335f8be78c157b2ff57faf2a6da`，接手 dirty 0.5.0/code500；既有 36 文件保全以本批 `baseline-source.json` 为准。B 没有从 HEAD 重建实现。
- 状态：**needs-architecture-decision**。本报告完成技术方案与隔离验证；不表示批准新生产 owner，不表示真实 AVF 成功。
- 外部 E：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/avf-platform-device/`。原件在 E/evidence，逐项即时孪生在 E/preservation；Class B 长期保留至维护者验收归档，不自动清理。
- 依据：20261016 完整 Worker B/共用协议、本批 dispatch-scope、活动 scope/spec/05/ACR/07/08、旧 phase5-09 完整报告、现 engine/adapter/launcher，以及固定 `20261015/research-performance/report.zh-CN.md` / reference-manifest。未重新 clone、GET 或运行上游脚本。

## 1. 背景与当前结论

**QA3 最新结论：当前 ROM 对现有 nonprotected adapter 不支持，07/08 的正确 owner 真机门继续阻塞，09/10 不产生真实 AVF 样本。** UserService 实际固定调用 manager constructor/getCapabilities，返回 capabilityBits=1、declaresProtected=true、declaresNonProtected=false；这是成功取得的能力声明，不是 VM create/run 验证。App 构造入口 METHOD_UNAVAILABLE。全过程无 create/run，真实 AVF 成功0；不拿 protected 声明或 Microdroid 替代当前 custom Linux 路线。

Context 与 FD 分别取证：App 的新 QA state 可写；实际 UID2000/shell 的 UserService 在所给 App Context 的 DATA_DIR_LSTAT 就返回 OS_ERROR_13。App→UserService 固定32B合成 RO/RW PFD 则成功读取/写回，接收副本关闭且 caller 原件保持。**合成 FD 成功没有使 shell 获得 App 私有路径访问权，也未证明 FD 能接入 Java path-based custom 配置或 virtmgr/crosvm。** 当前继续冻结新生产 owner/IPC，保留软件执行路径。Context/path/state/FD owner 的架构边界仍由本 ACR 承载，不请求泛化批准来覆盖本 ROM 的能力缺口。

本轮 B 只做方案、固定动作包、外部 JVM 模型及原票/报告补记。生产 `runtime/engine-avf`、App launcher、VM owner/IPC 均未修改。旧09的项目短锁 STOP、迟到 PFD/dup 回收和同名持久配置核验保持既有成果，不重复报成新修复。

## 2. 本批真实 QA1 / QA2 历史与 QA3 最终证据

以下原件由主协调执行和保存，B 只读核对。canonical 在 `batch-01/device/`，其 preservation 由主协调维护；B 的 `E/evidence/source-and-device-references.json` 记录精确 hash，不复制整份共享证据为第二 canonical。

| 原件 | 实际结果 | 可证明与不能证明 |
| --- | --- | --- |
| identity-v1-authorized-app-receipt.json | APP_UID，untrusted_app domain；clientGranted=true，serverUid2000；App MANAGE/CUSTOM 均 DENIED；customBuilder MEMBER_UNAVAILABLE_OR_HIDDEN | client 授权与 App Android grant 分离；普通 App 成员访问受限。未调用 builder、manager 或 VM |
| identity-v1-user-service-receipt.json | USER_SERVICE UID2000、u:r:shell:s0；其自身两权限 GRANTED；customBuilder MEMBER_VISIBLE_NOT_INVOKED；APP_UID；stale/end epoch 拒绝 true | 真实进程身份不同且可见成员，不等于 constructor 可调用、Context 有效或文件可访问 |
| 同一 US receipt 的未测字段 | managerInstance NOT_CREATED、capabilityBits NOT_QUERIED、dataDir AVAILABLE_NOT_OPENED、NO_FILES_OPENED_OR_CREATED、avfSuccess=false | manager、nonprotected、state、FD、VM 一律未闭合，不能用 shape 或 metadata 代替 |
| identity-v1-remove-confirm.json | 精确 QA `:identity_qa` pidof exit1、无输出 | 正常 remove 后该 QA 进程未检出；没有 VM，不能外推 App/server death 时 VM 停止 |
| identity-v1-app-receipt.json | 初次 CLIENT_QUERY_FAILED，随后授权后另存成功快照 | 初始失败原件保留，不以后续结果覆盖 |

QA1 独立包 `io.github.nyzeep.orchestella.researchauth`，code1 / 0.1.0-identity；主协调 signed-apk.json 记录 45,535B、SHA256 `ba80b7820dd0a612520c2a1801c64d97307fa49a9c02c859b6c7f1a0ec053160`；source freeze、构建和证书来源分别为 A 的 `identity-v1-frozen-source.json`、`identity-apk-01/result.json` / signed-apk.json。设备为 device-01/user0/API36，本批 ROM/APEX 绑定引用 `device-readonly-preflight.json`。B 未复制或操作签名材料。

QA2 已由主协调实际安装 code2 / 0.1.1-identity 并执行固定按钮5，canonical `batch-01/device/identity-v2-synthetic-gates-receipt.json` 为 exit0 取证、内部结果 **USER_SERVICE_CALL_FAILED / OPERATION_FAILURE**。仍能取得本次 US UID2000/shell/两权限 granted/shape 可见，但没有独立 Context-state 或 FD 各门结果；manager NOT_CREATED、capability NOT_QUERIED。这里 exit0 只表示成功读取 receipt，不能签实验通过。snapshot 中 NO_FILES_OPENED_OR_CREATED 是前置身份快照字段，不能据此证明后续失败前零文件副作用。

QA2 APK 的主协调回传 SHA256 为 `e2bba6148846004035e7ca82003005f4644381bf4c12b95bbd99d0b72e5febf4`，交付位置 `batch-01/delivery/identity-context-fd-qa-v2.apk`；source/input 由 A 的 identity-v2-frozen-source.json / context-fd-apk-01/result.json 绑定。B 的引用账本定点核原件与 APK hash，不读取签名材料。

QA2阶段的最早动作曾是定位固定失败stage/class/errno；旧OPERATION_FAILURE不能推出Context EACCES、FD被SELinux拒绝或路线不可行。该失败与原始seal完整保留，不能用QA3后续成功覆盖。以下QA3分项实证更新当前结论；G4 Java path-only仍硬门，R1仍无生产实施放行。

### QA3 最新分项实证

QA3独立包仍为 `io.github.nyzeep.orchestella.researchauth`，code3 / 0.1.2-identity；本批 `research-auth-device/identity-v3-frozen-source.json`、`qa3-apk-01/result.json`及其中source原bytes绑定构建输入。主协调安装原件为 `device/install-identity-permission-capability-qa-v3.json`，user0、exit0；实际已签交付 `delivery/identity-permission-capability-qa-v3.apk` 为61,919B，B定点SHA256 `2f7d67f33e6b8702695a42d0fe1fbf661bdb14b8d7f3ad7ed38940dc6450840f`。设备/ROM/APEX引用同批preflight；不把固定AOSP pin当此ROM源码版本。

| 真机原件/字段 | 实际观察 | 精确结论与限制 |
| --- | --- | --- |
| qa3-manager-capability-receipt.json / serviceCapability_* | UID2000/shell，MANAGE/CUSTOM granted；manager仅为capability查询构造；QUERY_RETURNED_DECLARATION_ONLY、bits=1、declaresProtected=true、declaresNonProtected=false | 查询成功取得当前平台声明，nonprotected明确false。当前nonprotected adapter对此ROM不支持；protected声明不证明任何VM已运行，不替代custom Linux |
| 同原件 / appCapability_* | APP_UID，MANAGER_CONTEXT_CONSTRUCTOR阶段METHOD_UNAVAILABLE | 普通App的固定构造入口未通过；不能把US成员/权限或client授权搬到App身份 |
| qa3-context-fd-receipt.json / appState_* | NEW_QA_STATE_WRITABLE，SENTINEL_WRITE；QA目录/哨兵创建和写入、描述符关闭有effect观测 | 只证明App本次新QA namespace可写；未创建平台vm/name或证明升级恢复兼容 |
| 同原件 / serviceState_* | 实际UID2000/shell、APP_UID；DATA_DIR_LSTAT / OS_ERROR_13 | 对所给App Context的dataDir访问在lstat即EACCES，不能据Context metadata可读认定state可写；未发生service state mkdir，不泛化所有Context/入口都不可用，也不凭errno单独归因SELinux |
| 同原件 / positive_* | SYNTHETIC_READ_WRITE_OK；roRead/rwWriteReadback/receiverCopiesClosed均true，App观察RW mutation且callerCopiesRemainOpenAfterSuccess=true | 本次固定32B App→US合成PFD可用，receiver finally关闭副本，caller originals仍开；不是用户根盘、平台配置FD、异步death回收或US→virtmgr/crosvm验证 |
| 同原件 / 固定负门 | wrongRoMode/wrongRwMode=FD_MODE_REJECTED；pipe=REGULAR_FILE_REQUIRED；sameObject=DISTINCT_OBJECTS_REQUIRED；replay=FD_REPLAY_REJECTED；对应receiverCopiesClosed=true；symlink、staleFdEpoch、stale/end epoch均拒绝，callerCopiesRemainOpenAfterRejection=true | 支持本轮固定类型/mode/来源及代际门；closedFdRejectedBeforeOrAtReceiver=true且closedFdBoundary=OPERATION_FAILURE，只签边界拒绝，不编造具体内核错误；真实不同UID Binder负客户端仍未由此证明 |

两份receipt的顶层managerInstance=NOT_CREATED、capabilityBits=NOT_QUERIED属于前置identity快照；本次真实查询以serviceCapability_/appCapability_为准，Context/FD以各分项stage/effect为准，不能用顶层旧值覆盖后续调用。两次实验generation各自绑定（非同一次VM session），均avfSuccess=false/provesAvfUsable=false、无create/load/run/VM stop。原件是主协调设备执行结果；B只读分析，不追加设备操作。

当前门：能力声明已取得且对现adapter为不支持；App state可写、US的所给App路径被拒绝、合成FD成功均已分项观测。剩余G4实际FD入口、合法持久state namespace、真实owner/死亡/停止不因这些部分成功而消失。不得为“补07”改protected、换Microdroid、改全局安全策略、公开复制盘或同ROM盲目create/run。

## 3. 三条既有入口的有限比较

| 路线 | 固定来源/本机依据 | 主要未闭合门 | 本轮处理 |
| --- | --- | --- | --- |
| App 直接反射 | 现AvfInstanceLauncher用App Context直接构建adapter；QA1 builder成员隐藏，QA3 App固定manager构造METHOD_UNAVAILABLE | 普通client/grant不改变App接口解析；US查得本ROM nonprotected声明false，当前adapter所需能力不满足 | 保留现诚实失败/软件执行路径；不加全局exemption、不降targetSdk，不声称已运行App create |
| UserService Java + 受限 FD | QA3 shell2000实际manager查询bits1/nonprotected=false；App state可写、US DATA_DIR_LSTAT EACCES；32B App→US合成PFD成功 | 当前ROM能力门不满足；所给App Context路径被拒绝；Java custom配置仍按路径开FD，尚无合法持久namespace和实际FD配置入口/owner/死亡链 | 此ROM停止推进当前nonprotected路线。合成FD证明保留为未来候选输入，不改packageName模拟UID、不用 `/proc/self/fd` 或公开复制绕路径门 |
| 平台 native/libavf/工具 | 固定 `c984fc337c11ca5edc03ccf02037b2455dd8fcaf` 的 raw config FD、create/start/stop/connect/waitForStop | LLNDK/APEX/binder_rpc_unstable/API36不等普通NDK分发；也不消除平台能力、权限/SELinux与owner门 | 只静态比较，不作为本ROM nonprotected缺口的绕行；无native构建/分发/第二套owner，不用protected/Microdroid或root CLI代验收 |

来源均为旧固定导出，不当目标 ROM 同 commit 认证。`VirtualizationService.nativeSpawn/nativeConnect` 通过 UDS/RpcBinder 连接 virtmgr，真实请求身份来自 UID/parent PID/domain；不把包装一个全局 Binder 称为迁移 VM owner。

## 4. 当前停止门与未来重开所需最小输入

固定动作细节仍在 **E/device-action-packet.zh-CN.md**，但07/08 VM动作在当前ROM不放行。B无设备窗口，不自行追加create/run或用其他入口绕nonprotected=false。

已取得的manager声明、Context分项和合成FD正负证据见§2；它们无需重复等待。剩余门分别为：

1. **平台能力前提**：只有目标设备/ROM对既定nonprotected custom路线声明支持，且固定入口可调用，才有继续07受控实验的前提。当前device-01未满足；仅换owner/授权限或用户批准不能改写该声明。更换设备/ROM需重新绑定原件，不继承本机可用性。
2. **Context/path/state边界**：当前所给App Context路径在shell进程中lstat即EACCES。将来方案必须具体说明谁创建/持久化平台state、namespace与UID/domain、App升级/冷恢复如何一致，不能伪造Context、改packageName或把shell自选目录当App state。
3. **G4实际FD入口**：合成App→US FD已通过；Java custom配置按路径开文件与US→virtmgr/crosvm仍未验证。必须有合法、可交付的类型化FD入口及所有权/失败回收证据，不能以 `/proc/self/fd`、私有目录放权或公共复制盘填空。native/libavf分发门另证，不因API36符号存在放行。
4. **owner/死亡/资源**：新owner的caller/user/package/version/代际、真实不同UID负客户端、RpcBinder/virtmgr归属、App/server death与停止/迟到FD/同盘lease收敛。正常remove或合成stop模型不能代替真实VM终态。

这些是未来兼容平台的精确重开输入，当前不请求用户批准未验证的VM代理来“补齐07”。用户裁定及必要ADR/spec修订之前，生产owner/IPC仍冻结；App私有盘、实例身份和既有软件执行路径保持。

## 5. 最小用户待决方案（当前R0，未来R1仍需具体证据）

**当前技术建议为R0**：按QA3真实声明把本ROM记录为现有nonprotected adapter不支持，保留诚实软件执行路径；07/08真实owner/Guest/stop门和09/10真实AVF样本继续blocked。这个记录依据已得事实，不需要泛求“是否允许继续AVF”的批准，也不表示用户已经作出新的产品架构裁定。

**用户未来真正要裁定的仅是R1的具体Context/path/FD owner边界**：在兼容平台和G4入口证明具备后，是否接受一个daemon=false、受App lease约束的有限调用进程，对本次固定RO kernel/initrd、单个RW root以及明确state能力具有可读/可写权力，并在App/server死亡时按已验证链收敛；或继续R0，不引入该进程。本轮已证实所给App Context路径在shell下不可用，故R1必须先提交具体namespace/入口与迁移回滚，不能只批准“用Shizuku代理”。App继续掌握盘/身份/元数据/会话权威，有限FD仍是实际权限授予，秘密可读边界见下文。表内为未批准的未来条件方案，不能视为本ROM实施授权。native R2只保留来源比较，不在本批新增分发或另一套owner。

| 裁定项 | R1 的精确提案 | 拒绝/终态要求 |
| --- | --- | --- |
| 调用方 | 固定包/签名、Android user、真实 Binder caller UID；绑定 server epoch、协议版本、instance/session/generation | 不信 payload 里的 UID/package；换 user/签名/version/旧 epoch 拒绝，不产平台副作用 |
| 数量/常驻 | App 范围一个 active lease、一 VM；daemon=false，App lease/Binder death 退役 | 旧底层 connect/VM 未收敛不得创建新 generation/同盘 QEMU，不能每次 timeout 再启动新代理 |
| 操作集合 | 固定 prepare/start、control/terminal 固定端口 connect、stop、typed 状态/事件；取状态不得再开无限阻塞 worker | 无 exec/general path、Binder passthrough、raw platform 配置、任意端口或任意系统进程 kill |
| 文件 capability | App 校验并持原始 FD；给独立 dup：kernel/base或assembled initrd RO，单个指定 root RW；有限状态能力只限专属 namespace | provenance/hash/mode/大小/文件种类/来源 gate；拒绝 closed/错 mode/符号链接来源/越界；不设 world-readable、不公共复制用户盘 |
| state namespace | App私有持久元数据权威；QA3已证实所给App dataDir在shell下lstat EACCES，未来须明确可行的合法namespace及owner route/version，并以G4实证 | 不把shell自选目录当App state；不因配置不兼容delete/setConfig/迁移用户身份。若新namespace必需，先提交具体迁移/回滚并由用户裁定 |
| FD 所有权 | 接收成功后唯一本次 owner；失败/取消关闭所有本次接收 dup，App originals 保留；迟到返回结果在服务端/接收端各自按实际新 FD owner 关闭 | 不能把两个跨 Binder dup 算成同一 FD；close 失败保留 unresolved；ACK 只确认接纳，不证明 VM 不再持该 FD |
| readiness | 单调总 deadline，连接/退避/握手/命令/服务共用；run/state/vsock/auth/QA/services 分列，custom payload not_applicable | 固定本 session 全部 connect 最多1在途、零队列；永久拒绝不重试；callback 必须 current generation；原单 reader/token 不变 |
| stop/死亡 | 正常先原认证 bridge power；stop intent、平台返回、owner stopped 观测、resources released 分列；App/server death 分别实测 | 超时只退役 caller，保留 STOP_UNCONFIRMED 与盘 lease。平台 mLock 可能挡 stop；只有获批且本 ROM 已证的独立进程终态链才能进一步收敛 |
| Guest/网络边界 | 不给 Guest 新宿主权限；单控制 reader、原鉴权、默认 loopback、只读 Downloads 既有规则 | 不由 Guest 发 shell 命令；不默认 LAN；net/forward 库存在不签服务可用 |

若选择 R1，批准应写明只覆盖上述边界及选定已验证入口；owner/state/分发机制变化要重新初审，不能由本次概括批准扩大。IPC 成为新的宿主权限面，即使 Guest 契约不变，也应修订 ADR0003/0007/0010/0011/0016 相关边界并核0002/0009，spec/map 由主协调更新。

**秘密生命周期也是裁定项。** App 持有 token/身份权威，不代表收到 assembled initrd RO FD 的调用进程“无法读到秘密”：该本轮资产可能含 bridge token/Guest 凭据。R1 接纳这个 FD 就扩大了该进程对本轮秘密的可读能力，必须在批准中明确。提案只许在固定启动操作中转交最小 FD，不解析或回显 initrd、不开诊断副本/持久 secret cache、不接纳任意用户正文；平台接纳后按真实所有权时点关闭本地 dup，失败/迟到同样回收，App原始/运行时资产沿既有生命周期处理。RO不能防读取，close也不能证明已读取的内存已清零，不作不可读取或安全擦除承诺。若用户不接受此最小可读边界，选择R0，不用日志约定假装消除权限变化。

## 6. 本轮隔离验证与证据层级

唯一运行工具为现有 Python3.12.9、D:/JDK17（javac17.0.10），无 Gradle/adb/UI/network。命令统一 `python E/run-fixtures.py <mode> [unique-label]`；完整 argv/exit/stdout/stderr 原件见 E/evidence/*.json / *.log.txt，所有 java.io.tmpdir/TEMP 在 E/temp。

| 入口/原件 | exit | 实际证明 |
| --- | --- | --- |
| compile；compile-lease | 0 / 0 | 未改生产纯 Java 依赖及外部 substitute-platform/prototype 编译；不是 Android 构建 |
| lock-red | 1，TimeoutException | 生产 engine/adapter 面对合成 shared-mLock 平台时，“connect 未释放而 stop 完成”断言失败 |
| lock-observe | 0 | stop 到平台入口但 body0；connect仍在途；当前 waitFor 可返回0，不能充作真实平台 stop；释放 latch 后迟到原 PFD close1/dup0、stop/close各1、worker0 |
| caller-deadline-red | 1，TimeoutException | 当前生产连接 getter 本身没有 caller 截止；外部200ms观察失败，不是 ROM timeout 值 |
| bounded-red | 1，AssertionError | 错误假设“Future done ⇒ 底层已结束”被实际在途模型反证 |
| bounded-lease-green | 0，9组 | 候选纯 JVM 模型按显式 fake clock/tick 维护总budget、有限在途/attempt、迟到FD、stop proof、EOF/旧代/close失败与全App盘lease |

详细分层及限制以 [08报告](../avf-shizuku-08/implementation-report.zh-CN.md) 为canonical。独立Spec审查P2的ready后observeStop遗漏顺序已另有真实red，外部候选模型最小修复后新增5组和原9组回归均通过；源码冻结于E/seals/p2-stop-observation-fixed，不能把重复运行累计或把模型green当生产修复。没有用Future.cancel/Thread.interrupt宣称native结束；持续阻塞模型在finally释放latch保证fixture自己结束。QA3真实32B Binder PFD结果单列于§2，也不能证明VM FD、death或平台中断。此次QA3收敛只更新文档/引用，不改模型，不重跑无关测试。

## 7. 已完成、未完成与风险

已完成固定动作包、QA1身份/QA2失败历史分析、QA3真实manager能力声明及App/US Context与32B合成FD正负分项、R0当前建议和未来R1精确条件、隔离red/green、外部模型P2定点修复及本报告；ACR原票同步补证。当前ROM nonprotected声明false，对现adapter不支持，因此不执行07/08的VM窗口，09/10不产生真实AVF基线或优化样本；真实AVF0，不能声称已实测VM启动失败或protected成功。

未完成的是兼容平台上的G4实际FD入口与合法state/owner namespace、真实VM/create/run/认证Guest/网络/normal stop、App/server/owner死亡收敛、用户对具体Context/path/FD owner与秘密可读边界的裁定、必要ADR/spec修订及生产owner/IPC。reviewer已取得P2冻结seal，B不据此自签复审通过；独立结论由各reviewer原报告承担。无性能收益或ROM无限hang结论。

主要风险是 privileged owner 变更、持久 namespace 不兼容与取消后 VM 状态未知。缓解为逐门失败即停、App全局盘lease、在途额度、原件与失败保留；不能用“daemon=false”“waitFor返回0”“pidof无QA”或软件回退掩盖底层未停。

## 8. 修改、回滚与宿主/架构检查

B 修改仅 ACR/07/08 三张原票、本 canonical 三报告和 E 下 source/runner/evidence/动作包/保全账本；修改前精确 bytes/hash 和原票局部副本在 E/before-source.json / before-source，生产五文件只hash，不归档整仓/.git。最终清单由 E/delivery-manifest.json 记录。map/spec/ADR/policy、其他 owner、版本/签名/Gradle 输入均未写。

回滚仅 B 新增文档/外部隔离原型及原票本批补记，先按 manifest 比较并保留后来并行修改；不 reset/clean、移动用户根盘、删除持久 VM 或 Class A。原始失败、旧09源码/报告、旧500 APK、SDK/JDK/缓存/WSL/参考源完整保留，无清理。源码未进入 APK，无新依赖；新模型自研，未复制平台实现，固定 AOSP 仅引用其 Apache-2.0 已核文件。

Architecture Change Policy：**真实owner/IPC方案触发并继续由本ACR承载；本轮未实施架构变更。** 无commit/push/branch/worktree/ref操作、无设备或系统写入、无宿主内容外发。各reviewer已取得P2冻结seal；本报告只交QA3定点收敛证据，独立复审结论不由B代签。此次变更前6份文档、动作包/runner及全部旧seal逐文件hash在E/qa3-convergence-before.json，原bytes在fixture-history/qa3-rom-convergence/before；最终新seal为E/seals/qa3-rom-nonprotected-unsupported-verified，旧失败/首审/P2 seal全部保留。

报告人：Worker B / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
