# AVF / Shizuku 准备与有证据升级 spec

Status: 0.5.3本地/框架交付范围保留；2026-10-17追加protected优先规划（23–27/ACR-02），新增任务未实现，具体就绪/阻塞按map与各票；未决架构不整份ready化。
Owner: Codex 主协调。建立：2026-10-15；2026-10-16增补。基线：428860ad9a1cb335f8be78c157b2ff57faf2a6da，保留dirty0.5.0→0.5.3。
来源：[正式研究](../../docs/research/2026-10-15-avf-shizuku-upgrade.md)、[scope](scope.md)、[map](map.md)。用户已授权直接合成、拆票与选择现有行为seam，不再进行常规粒度访谈。

## Problem Statement

用户希望在有条件的Android设备上尽量用本机操作准备硬件虚拟化，并理解失败原因；开发者需要清洁检出可验证的Guest资产和可信AVF性能样本。目前授权引导只处理一项权限，普通Shizuku说明没有真实client接线，历史设备实验仍被hiddenAPI阻断，Guest CI又依赖本机ignored模板。把预检误称成功会误导用户，也无法建立性能基线。

## Solution

先修复清洁构建与授权准备的一致性，交付真实普通Shizuku client状态和本地验证工具。所有状态显示当前事实：平台声明、两项Android权限、Shizuku授权与实际Guest分别表述。新VM代理、真实AVF及性能优化以隔离实验、ACR和设备证据为门，保留现有软件执行路径与用户实例。

## User Stories

1. 作为构建维护者，我希望从不含ignored模板的检出生成所需资源，以便CI不依赖某台电脑。
2. 作为维护者，我希望canonical漂移在任何准备写入前失败，以免生成动作掩盖源码不同步。
3. 作为维护者，我希望检查原始字节和APK唯一条目，以免换行转换形成假通过。
4. 作为用户，我希望看到两项Android权限各自的状态，以便知道尚缺哪一步。
5. 作为用户，我希望复制的ADB模板包含实际所需两项权限，以便一次按完整步骤操作。
6. 作为用户，我希望授权确认只说明准备条件，以免误以为Guest已运行或性能已证明。
7. 作为用户，我希望知道当前适配器的单核限制，同时保留我的硬件选择。
8. 作为用户，我希望在未安装Shizuku时有明确官方安装与首次启动说明。
9. 作为用户，我希望server未启动、Binder未到达和不支持分别说明。
10. 作为用户，我希望由我主动请求Shizuku授权，拒绝后不被自动反复弹窗。
11. 作为用户，我希望server死亡或设备重启后状态重新检查，旧回调不恢复过期授权。
12. 作为用户，我希望知道Shizuku已授权仍不代表Android两权限或AVF可运行。
13. 作为用户，我希望撤回后重开应用能按真实状态恢复，既有实例不被重建。
14. 作为测试者，我希望只读探针采用明确设备与固定命令，以便避免操作其他设备或读取秘密。
15. 作为测试者，我希望失败、回退和不完整样本被保留并排除AVF成功统计。
16. 作为研究者，我希望样本绑定源码/APK/输入/资源/阶段/会话，以便前后对照可归因。
17. 作为用户，我希望缺少设备或架构批准时明确看到阻塞，而非伪造“通过”。
18. 作为用户，我希望升级不改变根盘、身份、凭据、网络暴露或现有可用的软件路径。

## Implementation Decisions

- Guest模块源码render、canonical验证、APK模板准备与严格检查职责分离。prepare先验证canonical，仅写可再生模板；check只读且按bytes比较；默认源码更新不作为CI准备前置。
- 复用现有App资源Copy，不新增App构建Python前提，不迁移根盘/内核或sourceSets模型。
- 两Android权限作为预检必要条件；保留现有READY枚举的“预检已满足”含义并统一产品文案，接口/nonprotected/实际运行未知另说明，不在预检中隐式启动VM。
- 精确vCPU限制属于现适配器约束；不将MATCH_HOST解释为任意核数，不自动改用户值。
- 普通Shizuku client只读取manager/server/Binder与自身授权，注册/注销listener并以生命周期/Binder代际防迟到结果。授权请求仅来自可见点击；无自动grant、exec、UserService或VM操作。
- 锁定官方MIT client/provider发布版本13.1.5；独立依赖说明与NOTICE。不打包管理器/server，不复制品牌资源。
- 采用已有授权入口与设置/创建向导，状态模型与Android transport分离到可测边界；不引入新全局框架或无限轮询。
- 验证工具只接受allowlist字段与显式设备；不读取实例元数据、raw日志/按键/口令/token/embedded载荷。样本receipt文件限定在明确证据根，hash/会话/代际绑定；结构校验不能替代物理真实性审查。
- custom Linux不以Microdroid payload callback作为通用ready门；run返回、可观察state、vsock、agent认证、固定QA命令、服务ready和stop各自记录。
- App加入client属于向后兼容新功能，集成构建升MINOR为0.5.0/code500；不自动commit、push、tag、发布或安装。
- VM代理必须通过独立ACR；未决项不能以本spec的ready标签获得产品化批准。

## Testing Decisions

- 现有Guest CLI子进程是最高行为seam：临时合成根下验证缺文件、canonical/模板漂移、无输出目录、原bytes、幂等、失败零写入；保留Guest套件并核APK bytes。
- 现有capability/probe/wizard契约和授权用户动作是预检seam：分别缺MANAGE/CUSTOM、读取失败、拒绝、全有、回退、CPU说明；无runtime fixture不能声称系统UI通过。
- client transport注入真实事件序列：未安装/未运行/Binder到达/请求/拒绝/允许/death/重连/迟到结果/销毁；验证外部状态与请求次数，不断言私有实现。
- 验证工具通过合成fake adb和公开CLI验证命令allowlist、精确serial、失败即停/超时、输出脱敏、路径逃逸、证据hash与代际、fallback/缺门/资源组和synthetic不计实测。
- 只写必要行为回归，逐slice red→green；统一运行实际JavaExec契约入口、App套件、Guest回归、assemble与lint。独立规范/Spec双轴审查。
- 真机Shizuku请求/撤回/force-stop、OEM启动、真实AVF、Guest/数据保留/正常停止与性能全部独立待验，不以JVM或工具synthetic通过替代。

## Out of Scope

新特权VM代理或VM持久所有权、自动development grant、任意shell执行、全局hiddenAPI/SELinux修改、Root/解锁、kernel/GPU/VMM升级、多实例/热恢复、用户盘压力写入、远程上传/issue/PR/发布。退出/IME/向导/日志风险需先获得各自运行反馈再形成局部修复，不在本轮准备工作中全面重构。

## Further Notes

设备零连接；因此真实AVF与优化没有已测收益。所有blocked票仍有独立交付与明确通过门。docs与tracker为本地ignored，需要本地交接保全；不可假设GitHub fresh clone已有这些文档。回滚定点源码/依赖/文案，保留根盘、ClassA资产与原始证据。实际完成与未完成以逐票报告和map最终状态为准。

## 2026-10-16 resume-01 最终证据收口

本节解释20261016新授权及当前验收范围，原Problem/Solution/Out of Scope和零设备说明作为20261015起始spec保留。用户按两份20261016剩余交接明确授权设备AVF调试，以及11/12/14/15复现后的必要契约内产品修复；已由17/18/19/20承接，22承接有界离线recorder。原“先反馈再修复”门已分别取得实际red后履行，不把原Out of Scope静默改成任意权限/owner授权。

### 增补的实施与验收

| 范围 | 已完成且可追溯的门 | 保留的边界 |
| --- | --- | --- |
| 17生命周期 | expected-session、exit0/23/等待失败/EOF/旧代、排他admission和注册后watcher先于通知；C三P2后34JDK及Main精确片段，503集成、独立双轴approve | 真实FGS/UI/VM正常退出/强停/通知/冷恢复未验；caller结束非底层stop |
| 18owned日志 | 生产report子进程五受控sink场景；native journal/owned文件，failed一次摘要；recovery可见；canonical与503 APK bytes、Guest回归和双轴 | 真实Guest/PID1/journald/failed/recovery屏显与编辑体验未验；不滤audit/用户文本 |
| 19输入 | 30本地回归、真正Android KeyEvent/getDeadChar/ModernTerminalView；501/502及503输入框架PASS、双轴 | 右Alt字符resolver部分受控，实体国际布局键盘/OEM IME/焦点/前后台/锁屏未验 |
| 20草稿 | 14模型回归、真正Activity.recreate第3/4步与非法schema/distro/resource、两creating marker，502/503七向导框架PASS及双轴 | marker非实际下载/create；物理rotation/uiMode/OEM、真实事务跨重建/进程死亡未验；不新建任务owner |
| 22离线recorder | 127=原100+新27工具回归、独立双轴approve；COMPLETE_OFFLINE、synthetic_seam、Python/注入时钟 | 未接生产Android时钟/launch/Bridge/QA/services/stop，不产真实receipt或设备样本 |
| 05/06隔离QA | identity/Context/合成PFD/capability真实分项；QA4旧deadline源码/JVM九场景110断言及最终双轴限定approve；普通session/death/reconnect限定实测 | 原型不并入生产grant/VMowner；跨UID/真实deadline交错/迟到FD/G4/state升级门未闭合 |

503统一原件：resume-01/integration-503/{tests-and-source-audit,final-apk-verification,framework-acceptance}.json。747 tests/98 suites全过、lint0Error/0Fatal/748Warning；8框架PASS=七向导一输入，不累计旧502或局部套件数。原包与.phase5两包签名/版本、唯一guest_init和MIT、pinned Image/baseinitrd/QEMU同500。仅.phase5真实安装，原产品code300另一签名及user128数据保留，不能以卸载清数据打通原包升级。

QA4 code4/0.1.3-identity普通permission session同epoch跨旧5秒点8277ms后readback、normal remove、App force-stop→US0/server仍活、server TERM→App观察death/US0、官方starter重启并重新bind均限定PASS；最终双DENIED/US0/server1。resume-01/qa4-device-summary.json没有权限变更/VM/设备强制B在途交错，故真实系统deadline精确交错、完整typed grant/readback/同session restore与VM death仍未签收。ADB单/双权限grant/cold-readback/revoke控制计划全PASS且恢复双DENIED，见permission-control-summary.json；不等软件内UX。首次本机配对、拒绝/记忆拒绝/管理器撤回、reboot/OEM及生产授权向导仍未验。

### 不变的架构和平台门

当前US真实getCapabilities bits1/protected=true/nonprotected=false，对现nonprotected custom Linux不兼容；US所给App Context目录EACCES，App→US合成PFD可用不证G4实际VM FD入口或owner。07/08准确blocked，09真实AVF样本0，10无测量不报收益。ACR-01用户未裁定，未来需兼容平台/G4/state/具体FD及秘密可读能力/死亡收敛/迁移回滚后再裁定，不因本spec或调试授权放行。

仍不允许：任意shell/exec、Root/解锁、全局hiddenAPI/SELinux、降targetSdk、私有盘放权/public复制、kernel/GPU/VMM升级、多实例/热恢复、用户盘压力写入、数据出站/发布。原03普通client没有自动grant/生产UserService；21没有工单文件。QA1三份原源码bytes缺口保留，后续精确快照不倒填历史。01–04/16保持历史完成范围。

本次仅更新允许文档并双份定点保全，未运行源码/测试改写、设备/Gradle/git/ref/版本/签名/网络或清理；既有生产修复属于契约内bugfix，真实VMowner/特权IPC仍由ACR单独承载。当前22文件矩阵、依赖图、frontier见map，证据hash/前后快照见resume-01/tracker-closeout。

报告人：文档收口 Worker / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量

## 2026-10-17：独立 Debian QEMU 真机验收增补

本节为后续实测，前文“待设备/待真实Guest”保留当时含义；当前进度以本节为准。唯一摘要为本批 `resume-01/qemu-device-summary.json`（19份原件的路径/bytes/SHA），原件及摘要均有即时 preservation。设备user0，独立 `.phase5` code503，新建且仅本轮使用的 `qa-qemu-503-01`；未操作原产品实例或其他user数据。

真实输入为Debian13、1vCPU、801MiB、4GiB新raw盘；App私有rootfs实核90351092B、SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。实际下载命中端点未完整观察，不将配置URL当实际端点。App显示QEMU软件虚拟化，且进程为独立App UID下的QEMU子进程；这不是AVF成功或性能对照。

原终端固定查询实证UID0、PID1=systemd、`status=ready/stage=verified/attempt=1/operation_exit_code=0`及done存在。owned firstboot服务`Result=success/ExecMainStatus=0/ActiveState=active/SubState=exited`，`StandardOutput=journal/StandardError=journal`；当次owned marker在journal和owned文件均可查。没有完整统计常规console副本数或逐report唯一性；owned文件重复repository-refresh阶段名不能当同一event重复，audit/kernel消息继续保留。

Guest `/sbin/poweroff`、App“正常关机”、仅新QA“强制断电”均实际令QEMU子进程退出并显示/持久化STOPPED。强制停止及最终关机后owned前台服务不存在；同一实例在正常与强制停止后重开，固定持久标记仍为`ORCHESTELLA_QA503_PERSIST=1`，force后停机记录与重启后最终停机记录的实例ID及根盘引用hash一致。最终通过Guest正常关机收尾，QEMU0、owned前台服务无，测试盘保留。成功停止的精确owner exit code未独立记录，不能由UI或EOF倒推0；force后UI复用“guest正常关机”文案，仅签有意停止及底层退出，不称Guest进行了graceful shutdown。

剩余：真实exit23/wait异常/错代竞态注入、EOF先于owner退出、Activity重入与通知完整矩阵、App进程死亡后VM的真实冷恢复；日志native-failed一次摘要、无journal recovery、console全程计数/逐event exactly-once及完整编辑体验仍未验。原型QA4的US死亡不代VM死亡；QEMU结果不关闭07/08/09/10/ACR。当前ROM nonprotected=false、真实AVF样本0不变。创建/停止过程中未改源码或系统策略、未清数据/卸载/公开根盘/增reader。

报告人：主协调 Codex / GPT-6.1 ｜ 日期：2026-10-17 ｜ 本节仅同步新增实测，不重写旧封板

## 2026-10-17 Protected优先近期需求增补（规划，尚未实现）

Canonical优先级、两轮迭代、owner/退出和验收方案为 [近期优化方案](../../docs/reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md)，map索引新增23–27与ACR-02；原22票完成范围/旧spec/原型均保留。本节只规定新增可验收需求，不替用户接受新的产品安全保证。

### Problem / Solution

当前设备仅protected声明而现Linux适配器需non-protected；现有权限预检不足以解释各层能力。protected实际boot、合法custom/私有根盘和owner尚未知。当前QEMU已可用但故障/冷恢复/真实创建事务等门未验，recorder仅离线。

解决方向由用户确认：protected优先验证、non-protected持续兼容、QEMU稳定交付。先模式事实和有界pVM控制、再自有最小Guest与私有数据可行性；可靠性与生产事件并行，不先扩GUI/GPU/镜像格式或默认保护承诺。

### 增补User Stories

19. 作为用户，我希望系统声明、模式、调用身份/权限与实际Guest分别显示，以免被protected-only误导为芯片无虚拟化，或被授权绿灯误导为Guest成功。
20. 作为维护者，我希望未知能力和旧ROM证据保持未知/过期，以便只按具体ROM/实际结果选择后续测试。
21. 作为QA人员，我希望先有可靠停止和有限在途控制，再启动独立protected payload，以便失败/超时不遗留未知VM或重复使用同盘。
22. 作为研发人员，我希望Microdroid与最小customLinux按各自镜像/启动门取证，以便最早失败能归因。
23. 作为用户，我希望私有根盘、凭据、实例和持久state不因实验被迁到公共目录，以便保留当前数据保护契约。
24. 作为维护者，我希望原non-protected实现和QEMU仍有回归，以便新方向不回退旧用户路径。
25. 作为研发人员，我希望真实生产启动和停止事件有同源单调时钟、owner/代际和缺失说明，以便后续性能与故障分析可信。
26. 作为用户，我希望未来若明确要求保护性质，系统不会在我不知情时使用较弱路径；具体语义先由ACR-02裁定，本条不是已实现的新保证。

### 增补Implementation Decisions

- 模式事实层与策略选择/保护要求分开；数据只消费现有安全可用的只读来源，未知不填false、不隐式startVM或移owner来探测。
- 原型固定测试模式可自主失败即停；新的持久实例保护需求/降级行为/安全保证不在本spec中直接定案。
- 受控平台payload→合法最小kernel/initrd→真实私有合成盘/state是三项不同原型结果；低成本来源规则/网络可行性先行，完整盘/GUI投入受早期反证约束。
- 原App owner/API适配与UserService迁移分别比较；任何模式/文件标签/firmware限制不由grant或FD复制解除；真实新owner/IPC/秘密可读能力按ACR-01。
- 生产事件先QEMU闭合，真实偏序/clock与缺信号为准；必要schema版本化，不倒填时间使旧CHAIN通过，未存在的QA命令不靠正文解析伪造。
- 认证Bridge/单reader、loopback、共同kernel、私有单raw盘、默认出站/DNS、单实例与冷恢复保持；缺硬契约不能只声明策略增强/不适用来跳过。
- 遇protected固件/私有数据/网络硬门，先准确研究结论和ACR，不公共复制/目录放权/Root/解锁或预设完整Linux已成立。

### 增补Testing Decisions

沿现capability/probe/controller/launcher/Bridge/离线recorder公共seam；每行为red→green。模式组合/actor/unknown/旧代、关闭零采集、容量/I/O失败、真实偏序/错误时基、caller截止vs底层终止、迟到FD/STOP未知拒盘复用均有正负例。

设备prototype先新QA/状态/签名/资源/输入hash，收到系统确认由用户处理，数据初态/副作用/收尾记录明确。Microdroid payloadready和custom标记分别合法；QEMUstop/USdeath/工具receipt不等硬件VMstop。真实private-data入口失败允许resolved-negative研究结论，产品07仍blocked。

真实AVF全链/正常stop与资源/来源齐后09才比较冷热，10只改实测单变量；本轮不跑测试或设备，不把已有747/127/8和历史pVM声明当新增完成。

### Out of Scope / Further Notes

当前仍不默认批准：新保护选择器、Root/OEM分支、私有盘迁移/新增持久namespace、独立VM常驻/热恢复/多实例、kernel/GPU/VMM升级、无线ADB长期产品数据面、远程外发/发布。若实验要求变更则开具体决策，不静默改accepted ADR。

23/27是范围清楚的实施frontier，24是先离线后真机的受控prototype frontier；25/26设备阶段有真实依赖，ACR-02未决。06/17/18/19/20只补原剩余验收/有证局部fix，不重做已有实现。新增工单与优先级是规划交付，没有启动任何实现/构建/设备操作。
