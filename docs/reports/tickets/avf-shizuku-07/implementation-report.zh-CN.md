# 07 实施准备与真实 Guest 验收报告

- 票号/标题：07 / 真实 custom Linux Guest 端到端闭环。
- 执行：Worker B / Codex GPT-6.1-sol；日期：2026-10-16。
- 源码：`Orchestella_dev`，main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，接手 dirty 0.5.0/code500；不从 HEAD 重建、不覆盖原36份交付。
- 状态：**blocked-platform-and-architecture**。QA3实查当前ROM nonprotected声明false，现有nonprotected adapter对此ROM不支持；正确owner与真实Guest/stop门未闭合。本票无create/run，真实AVF0，不能用protected声明或Microdroid代验收。
- E：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/avf-platform-device/`。Class B；原件即时 E/preservation，维护者验收归档前不清理。

## 1. 任务目标与本轮边界

按原票在获批正确 owner 上，以全新 QA raw 盘、pinned 输入、精确1 vCPU、合预算 RAM 完成 create/run→vsock→原单 reader 鉴权→终端/固定命令/resize→网络与服务→正常stop，保留既有实例和软件执行路径。主协调唯一设备、安装、权限、VM和构建操作者；B 未获设备窗口，不运行 adb/UI/Gradle，不写生产 owner/IPC。

已完整读共用协议/policy/交接、活动scope/spec与05/ACR/07/08、旧09、现engine/adapter/launcher及固定research-performance。旧09已有正确API形状、项目锁外STOP、FD接纳/迟到回收和同名持久配置核验，不重报新修复。

## 2. 实际完成及验收映射

| 原票验收 | 本轮实际交付 | 状态 |
| --- | --- | --- |
| APK/source/ROM/module/调用身份、session/owner/FD绑定 | [ACR报告](../avf-shizuku-acr-01/implementation-report.zh-CN.md)核QA1–QA3：AppAPP_UID/untrusted_app、US2000/shell，APP_UID；QA3 code3/APK/source/ROM/user绑定已列 | 身份和合成FD来源已证；真实VM owner/VM FD仍未证 |
| API/权限/nonprotected/配置/FD各自失败即停 | QA3 US实际QUERY_RETURNED_DECLARATION_ONLY、bits1/protected=true/nonprotected=false；App构造METHOD_UNAVAILABLE；US App dataDir lstat EACCES，32B合成PFD正负门另有成功原件 | **当前ROM对现nonprotected adapter不支持**；不追加create/run或用合成FD成功覆盖平台门 |
| run后vsock、鉴权、QA命令、resize，不等payload | 固定验收顺序与原单reader要求；run/state/vsock/auth/command/service独立 | 动作预案保留，仅未来兼容平台/owner获批后；当前真实执行NOT_RUN |
| 正常stop/双启动/重复stop/旧回调/取消/death/错误FD | [08报告](../avf-shizuku-08/implementation-report.zh-CN.md)保留原9组及独立P2 ready后stop顺序red→最小fix新增5组green、全App盘lease | JVM合成结果有效；正确owner真实stop/death门仍未完成，不拿QA FD成功代VM终态 |
| 既有实例/盘/凭据保留，QA关闭 | B未读取/修改用户盘、实例秘密或embedded载荷；只批准新QA盘动作 | 无数据变更；真实QA VM从未启动，不能签“已正常关机” |

**本应用真实 AVF/性能样本仍为0。** QA1成员可见、两权限、Context metadata、QA进程remove均不算本票VM成功。

## 3. 已比较的入口与实际调用链

当前生产 `AvfInstanceLauncher.launch` 用 App ApplicationContext 构建 ReflectiveAvfVirtualMachineApi；普通Shizuku client未改变这一调用进程。adapter要求完整hidden形状、两权限、nonprotected、精确1CPU，先get同名VM、完整核config与stopped状态后复用或create，再run。AvfSession立即返回、通道lazy连接，晚到通道错误不回同步QEMU catch。不能把session对象存在称Guest ready。

QA1的App hidden成员限制、QA2的USER_SERVICE_CALL_FAILED / OPERATION_FAILURE作为历史原件完整保留；不能以后续QA3分项成功覆盖旧失败，也不能把QA2通用错误解释成ROM拒绝的根因。

**QA3最新实证已收敛当前路线。** `device/qa3-manager-capability-receipt.json`中US实际构造manager并查询，serviceCapability_capabilityResult为QUERY_RETURNED_DECLARATION_ONLY，serviceCapability_capabilityBits=1、declaresProtected=true、declaresNonProtected=false；App在固定manager构造阶段METHOD_UNAVAILABLE。顶层managerInstance=NOT_CREATED/capabilityBits=NOT_QUERIED是前置identity快照，不能覆盖后续分项调用。本ROM对当前nonprotected adapter不支持，不增加create/run验证、不改protected或用Microdroid替代。

`device/qa3-context-fd-receipt.json`另证App新QA state可写，US在所给App Context的DATA_DIR_LSTAT阶段OS_ERROR_13（EACCES）；固定32B App→US合成RO/RW PFD正向读写/receiver副本关闭/caller原件保持成功，wrong mode/type/same/replay/symlink/stale/end拒绝各有字段。这里可用的是受限FD，不是shell对App私有路径或平台持久state的访问权，也未证明US→virtmgr/crosvm或Java path-based配置可消费FD；G4/owner与死亡门仍未闭合。native/libavf的LLNDK/APEX/API36不构成普通APK分发或绕过平台能力的承诺。两原件、APK/source/ROM绑定与最小待决方案统一详ACR报告§2/§4–5。

当前建议R0是按事实保留软件执行路径，真实07/08及09/10样本继续阻塞；未来只有兼容平台与实际入口证据齐备，才请求用户裁定具体Context/path/state/FD owner及秘密可读边界，不泛求新生产代理批准。

## 4. 未来兼容平台的最小07窗口（当前ROM不放行）

唯一固定动作细节为 **E/device-action-packet.zh-CN.md §2**。以下是能力前提满足后的预案；当前device-01因nonprotected=false停止在能力门，不能据此预案执行VM或改绿。

1. 先在兼容设备/ROM取得所需nonprotected支持声明和固定入口可调用证据，重绑APK/source/user/ROM/APEX；再完成合法Context/state namespace、G4 FD入口及真实owner方案，主协调初审ACR、用户裁定具体边界并同步ADR/spec，才冻结实际APK/签名/版本和QA资源。QA3已完成的本机声明/合成FD不等于这些未来条件均满足。
2. 新QA instance/session/generation、专用raw盘，核无活VM/同盘RW占用；pinned kernel/baseinitrd/rootfs的hash/provenance，精确1CPU/nonprotected，发行版预算RAM及宿主reserve。不能拿MATCH_HOST或protected Microdroid替代。
3. 固定create/get/run分别记录真实UID/domain、namespace、virtmgr parent与可取得的CID关联；run返回只记run_returned。CONFIG_MISMATCH拒绝，不delete/setConfig掩盖旧身份。
4. 原control/terminal固定端口连接、唯一reader完成版本/token鉴权，再经既有终端执行固定QA uname/CPU/合成标记、bridge resize并stty验证。服务ready另验，payload callback=not_applicable。
5. owned provisioning、受控端点的IP/DNS/出站和默认loopback端口转发分别验证。现AvfSession没有完整产品service-forward接线，库有forwarder不足以签验收；如需生产变更由主协调再排边界，不能跳过网络产品面。
6. 既有认证power发正常关机，记录guest ack、真实stop reason/独立stopped观测、所属VM进程与全部FD/worker收敛。未知仍活着禁止同盘QEMU fallback。主协调/独立review核原件后才交E性能样本。

QA建议总ready预算120s、stop观察10s只作为事前固定测试预算，不是平台保证；首次不适用保留失败并在下次尝试前调整。不能用约100×200ms重试当总deadline20s，也不能用waitFor0当真实停止。

## 5. 测试、证据与未运行项

B既有离线命令与exit以08 canonical为准：生产+合成平台锁的lock-red/caller-deadline-red exit1、观测fixture exit0、错误Future结束假设red exit1；候选原9组及独立P2新增5组green均exit0。这些为**JVM_SYNTHETIC**。QA3此次只收敛文档/原件引用，模型和生产不变，不重复无关JDK/Gradle/App/Guest套件。

设备原件由主协调采集：QA1/QA2历史及QA3两份manager/capability与Context/FD receipt，准确字段/限制详ACR报告，hash在E/evidence/source-and-device-references.json。合成32B PFD是真机Binder FD实验，不是VM磁盘/配置或Guest成功；US的nonprotected=false是成功返回的声明，不是实际create/run失败。B无设备命令，未运行07 create/run、Guest命令/resize/网络/服务/normal stop、真实VM取消/death、QEMU对照和benchmark。真实AVF0；**09/10不产生真实AVF样本或优化收益**，不以protected/Microdroid替代。

## 6. 修改范围、风险、回滚与后续

修改仅本报告、07原票本批补记及E动作包/隔离fixture/证据；其他B交付见ACR/08报告，最终manifest精确列出。main/HEAD未变、commit无；不commit/push/branch/worktree/reset/clean，不改版本、App/Guest、生产engine、map/spec、ADR/policy或他人文件。修改前原票bytes/hash/局部副本在E/before-source与preservation。

当前阻塞是QA3真实返回的nonprotected=false，叠加App manager构造入口不可用、US对所给App Context路径EACCES、G4/owner未闭合；平台mLock/stop和网络产品接线仍缺真实VM验收。能力声明不变时用户批准代理也不能放行现路线，不为全绿改protected/安全策略。未来兼容平台还须逐门核真实入口、正常关机与资源收束，其他独立工作继续。

回滚仅B本轮文档/原票补记和隔离原型，保留后续并行修改及所有失败；无持久盘/身份迁移。Class A工具链/缓存/源码底包/WSL、旧APK/source bundles/旧证据完整保留，未清理。新增原型自研、无库进入构建、无新分发许可影响。当前建议保留R0软件执行，未来取得兼容平台/G4具体证据后再提交Context/path/state/FD owner及秘密可读边界的ACR选择。各reviewer已取得P2新seal，独立复审结论由其原报告承担，不由B自签。QA3文档收敛新seal为E/seals/qa3-rom-nonprotected-unsupported-verified，旧seal及失败保持原件；修改前字节/hash见E/qa3-convergence-before.json。

Architecture Change Policy：新owner/IPC仍由ACR-01承载，本票未实施架构变更；未越设备/生产/宿主系统/数据出站边界。

报告人：Worker B / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
