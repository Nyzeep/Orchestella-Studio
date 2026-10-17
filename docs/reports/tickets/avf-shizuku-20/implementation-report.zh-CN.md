# Ticket 20 实施报告：创建向导系统重建草稿一致性

- 票号/标题：20 wizard-saved-draft，Parent=15。
- 执行session/模型/thinking：Worker D / Codex / GPT-6.1；thinking未单独记录。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；接入时dirty 0.5.0/code500，旧36份未提交交付及其他worker并行修改保留，无commit/ref/worktree/reset/clean。
- 外部canonical证据根：`Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`（下称batch），D输出在`android-input-recreate/`（下称D根）。Class B保留至维护者验收归档，不自动清理，raw证据即时preservation。

## 1. 任务目标

在15真正Activity.recreate失败反馈后，局部保存四步创建向导的非秘密草稿，使model、控件、summary一致；恢复时重验schema/catalog/资源，creating不得重发下载/create。不引入全局任务框架，不增加configChanges绕过系统重建。

## 2. 先运行red的事实

主协调安装独立`.phase5` code501/probe02v2，在device-01/user0实际运行系统重建。原产品包被旧签名记录阻断后保持历史数据，没有卸载、清数据或跨user激活。D没有设备写窗口。

| 原始证据 | 实测结果 |
| --- | --- |
| `device/recreate-501-step3-red.json` | before STEP_HARDWARE、CPU2/RAM3820/disk16；after STEP_STORAGE、CPU4/RAM3875/disk16。before model distro=ubuntu，spinner选择动作未使模型同步，summary仍Ubuntu/8GB/4CPU/3819MB |
| `device/recreate-501-step4-red.json` | before STEP_STARTUP_TASKS，控件qauser/qahost但model=root/orchestella；after STEP_STORAGE、CPU4、控件恢复root/orchestella |

两条adb进程exit0，instrument stream均明确FAIL draft differs after real Activity.recreate；不是测试通过。D读取两份原件后才解除SetupWizard/model冻结并提升生产修复。精确初始bytes/SHA见D根`scope-and-before.json`和`before/`、`preservation/before/`。

## 3. 实际完成与验收

| 验收项 | 实施与证据 |
| --- | --- |
| 版本化轻量草稿 | InstanceCreationWizardModel.saveDraft只输出schema1的16个白名单字符串字段；Activity在Bundle独立键creation_draft_v1保存并恢复 |
| 步骤与配置 | 保存step、distro/version、diskGb、CPU/RAM、mirror、autoUpdate、username/host/sudo及desktop/podman/editor选择；不保存整个model/userConfig |
| 模型与控件一致 | 创建控件后和Android自动View恢复后，重新从已校验模型绑定owned控件；恢复期间监听器不回写model。第1步前进时同步当前spinner，消除延迟onItemSelected导致的旧distro |
| 身份/摘要实时性 | username/host/sudo和可选profile监听写入draft；有效身份更新内存userConfig但保留内存随机password。summary随磁盘/CPU/RAM/mirror/autoUpdate/身份变化刷新，显示当前身份和profile |
| schema/字段/清单重验 | 严格字段集合与schema，拒绝缺项/未知字段/非法数字布尔或enum；distro/version必须仍在当前官方选择目录；不静默接受过期manifest |
| 资源变化重验 | 恢复前按当前HostHardwareProfile检查CPU/RAM和2GB宿主reserve，再按当前manifest重新计算boot RAM要求；disk在2–64GB范围。存储剩余空间仍通过现有StatFs和创建前检查 |
| 不完整身份 | 未完成的安全文本草稿可保留，不构造非法InstanceUserConfig；创建动作仍走原username/hostname校验 |
| 创建防重放 | tryBeginCreation只允许首次进入；Activity入口isCreating即返回。saved creating恢复不调下载/create，不恢复Future/Executor/Context，隐藏创建/上一步并明确要求返回实例列表确认。其他草稿字段无效时也保留in-flight marker，防止旧事务变为自动retry |
| 秘密排除 | saved map不含password/token、Context/Future/Executor或任意用户对象；不读取既存实例凭据/根盘。未创建模型重建的随机候选password重新生成，不把它当既存实例凭据 |
| 已运行本地回归 | 候选创建重入先red后green；生产model新6项+既有8项共14项JUnit通过；最终Activity/model Kotlin定点编译exit0 |
| 系统green | 主协调已在实装code502/probe3上运行真正Activity.recreate：step3/4前后记录的model/view/summary逐项一致；三项invalid-state拒绝及两项受控creating防重放均PASS。D已读取逐场景原件并核preservation，签收明细见§8 |

## 4. 未完成与偏差

code502/probe3的Android recreate、无效state拒绝及受控in-flight防重放已真实验收，完整App746项通过、lint0Error/748Warning，冻结源码独立Spec/Standards两轴均approve，原件与范围见§8。仍未运行：物理旋转/uiMode、实际OEM IME/实体键盘、真实下载/创建事务跨重建和进程死亡后的任务/进度恢复。这些未验项单列，不拿受控creating标记冒充真实事务。主协调C三项P2修复后的503再构建是后续集成批次，D源码保持原冻结字节，502通过不自动签收503。

创建中恢复采用保守in-flight门，不自动重连旧Activity的进度对象；用户返回列表确认任务结果。它能阻止恢复当场重发，不承诺跨进程任务续跑。旧Activity原任务的正常完成/失败依旧走原路径；本票不新造持久任务owner。

## 5. 修改模块与写锁核对

- `app/src/main/java/io/github/nyzeep/orchestella/InstanceCreationWizardModel.kt`：versioned draft白名单、恢复校验、身份/profile草稿、一次创建门。
- `app/src/main/java/io/github/nyzeep/orchestella/SetupWizardActivity.kt`：saved Bundle接线、Android View恢复后绑定、实时摘要、creating恢复提示和入口门。保留之前Shizuku/权限预检实现及其两权限口径。
- `app/src/test/java/io/github/nyzeep/orchestella/WizardDraftCandidateTest.kt`：新6项行为回归，连同现有InstanceCreationWizardModelTest运行；文件名保留外部候选来源，不宣称其为Android框架测试。
- MainActivity/Guest/AVFowner、公共版本/签名、Manifest/configChanges、inputType均未改。SetupWizard独占D，A只读；其他owner并行变更未覆盖。

## 6. 设计决策与架构边界

版本化map承担模型草稿，Android Bundle只负责序列化白名单标量；Android自动控件state单独处理，不能成为模型真值。坏state不记录原始Bundle/正文，只给固定重确认提示。资源重新校验发生在恢复与原有创建前门，未因草稿恢复扩大硬件预算。

没有序列化InstanceUserConfig，因为它包含password；只保存用户名/host/sudo，使用当前新模型的内存候选password形成有效配置。已存在实例的数据和凭据不参与此流程。

新增范围属于ARCHITECTURE_CHANGE_POLICY §3的契约内bug修复与测试扩展；不新增权限/IPC/FD/VMowner、后台代理或主机出站，不触发ACR。

## 7. 测试与真实命令

- `python -B <D根>/run-draft.py draft-creating-red`：JDK/Kotlin编译成功；JUnit6项中1失败，重复tryBeginCreation被错误接受。此red只证明外部候选的防重入行为，原生产系统red另见两份501原件。
- `python -B <D根>/run-draft.py draft-creating-green`：exit0，6项通过。
- `python -B <D根>/run-draft.py production-draft-green production`：编译当前生产model/其Java依赖与新旧测试；exit0，14项通过。覆盖第3/4步roundtrip、秘密排除/候选凭据重新生成、schema/字段/数字/enum、removed/updated distro、CPU/RAM/boot minimum变化、部分身份文本、unsupported profiles、creating重入和恢复禁止重发。
- `python -B <D根>/compile-activity.py production-activity-01`：外部runner缺LifecycleOwner classpath，编译失败；只有缺依赖错误，原件保留，不能称产品编译red。
- 补入既有离线lifecycle-common jar后，`production-activity-02`与最终`production-activity-final`均exit0；只编译当前生产SetupWizardActivity/model，对照既有QA501依赖输出，不运行Gradle。最后一次即冻结源码SHA，只有既有弃用API warnings。

所有完整command/exit/stdout在D根`draft-runs/`、`activity-compile/`和preservation。测试阶段不可相加成不同独立用例数；生产最终模型独立测试数14。无Gradle运行由D发起。

## 8. code502真实框架、集成与独立审查签收

原生Instrumentation probe03源码和Manifest在D根`framework-v3/`，版本3/0.1.2，编译原件为`framework-v3/compile/result.json`。主协调已将code502 App及probe3实装至device-01/user0；D此次只读验收原件并更新文档，不重新打包/签装或执行测试。

固定component仍为`io.github.nyzeep.orchestella.qa.recreate/io.github.nyzeep.orchestella.qa.RecreateProbe`。以下完整命令含参数、stream和前后安全字段均在batch/device原件，D已逐件读取并确认与batch/preservation/device对应文件字节一致；不能只凭adb exit0或汇总all_pass认定通过。

| 场景/原件（相对batch） | 实际结果与签收界限 |
| --- | --- |
| `device/recreate-502-step3-acceptance.json` | stream PASS real Android framework wizard。真正Activity.recreate前后均STEP_HARDWARE、Debian、16GB、2CPU、3791MB；model/view/summary及记录的mirror、autoUpdate、身份、sudo、profile值逐项一致 |
| `device/recreate-502-step4-acceptance.json` | stream PASS。前后均STEP_STARTUP_TASKS、Debian、16GB、2CPU、3829MB；modelUser/username均qauser、modelHost/host均qahost，autoUpdate/viewAutoUpdate均true、summary同步，其余记录字段一致 |
| `device/recreate-502-invalid-schema.json` | stream PASS real Android framework invalid-schema。坏schema被拒，恢复STEP_STORAGE和默认配置；model/view/summary同步，未被旧控件state覆盖 |
| `device/recreate-502-invalid-distro.json` | stream PASS real Android framework invalid-distro。受控过期/未知distro被拒，恢复至STEP_STORAGE重新确认 |
| `device/recreate-502-invalid-resource.json` | stream PASS real Android framework invalid-resource。受控越界资源字段被拒，恢复至STEP_STORAGE重新确认 |
| `device/recreate-502-creating.json` | stream PASS real Android framework creating。受控creating=true在真正recreate后保持，配置/身份/摘要前后一致；原件明确create button hidden、duplicate admission rejected |
| `device/recreate-502-invalid-schema-creating.json` | stream PASS real Android framework invalid-schema-creating。坏schema使其他配置回默认，但creating=true仍保持，创建按钮隐藏且重复admission拒绝；未声称坏草稿原配置继续保留 |
| `device/input-framework-502-acceptance.json` | 19输入复验PASS；真实KeyEvent/getDeadChar/ModernTerminalView重绑与受控AltGr的expected/actual字节全部匹配 |

`integration-502/framework-acceptance.json`的七场景为step4/input/三项invalid/两项creating，all_pass=true；step3单独原件是额外一项，不重复计为该七项。两项creating仅受控模型marker，未执行下载/create；这里签收Android重建后的防重放门，不等实际下载事务跨重建、旧任务进度重连或跨进程续跑。

QA包身份固定于`integration-502/qa-apk-identity.json`：`io.github.nyzeep.orchestella.phase5`、code502/0.5.2-phase5-local、106519708B、SHA256 `3bd497b1d1031bfe9a86e2728a46fe16b99af8714a6289d29828845baa9f0735`。`integration-502/test-apk-audit.json`记录98 suites/746 tests，failures/errors/skipped均0、lint Error/Fatal均0、748Warning，D源码匹配冻结。D另外只读解析`build/prod502/app/test-results/testDebugUnitTest/TEST-*.xml`与`build/prod502/app/reports/lint-results-debug.xml`，核得原包配置同样746全过、lint0Error/748Warning；`evidence/gradle-app-502-original-package-check.log.json`记录主协调testDebugUnitTest/lintDebug exit0。App746项为完整集成套件，不与模型14项、输入30项重复累加。

冻结20源码的独立Spec/Standards两轴均approve，无新增必须修改发现，原文为`docs/reports/reviews/avf-shizuku-20/spec-review-20261016.zh-CN.md`和`standards-review-20261016.zh-CN.md`。审查发生在502设备签收前，批准范围是冻结源码/当时本地证据；本次补记由后续真实原件关闭相应系统门，不修改review首审的历史限定，也不把实施者更新当新的独立审查。

D冻结5源码/test及旧`final-freeze.json`、`delivery-audit.json`、patch保持不变；此次只更新14/15/19/20报告与票并新建`acceptance-docs-502/seal.json`，保全本次文档前后SHA和实际原件引用。主协调C三项P2修复将503再构建，D源码不变，502签收不能代替503构建/回归。物理旋转/uiMode、实际OEM/实体键盘、真实事务与进程死亡继续另列未验；D无设备/Gradle操作。

## 9. Benchmark、风险与回滚

本票不涉及benchmark，无性能收益数字。风险主要是Android/OEM自动View恢复时序、catalog/资源变化和实际创建事务的旧Activity存活；通过模型校验、restore后单向bind、in-flight锁定和真实框架green门控制。不引无限轮询或新全局框架。

D生产5个source/test文件已冻结于`final-freeze.json`，精确SHA/bytes、beforeSHA、`worker-d.patch`、`frozen-source/`与preservation齐备。rollback仅20有界hunk，不覆盖旧36变更/其他worker；保留原red、旧APK、SDK/JDK/缓存、根盘和Class A资产，无清理。

## 10. Commit、票状态与交付边界

无commit；main保留未提交。当前状态：`implemented-and-android-verified-external-gates-open`。15原始系统red与20的code502真正Activity.recreate、非法草稿拒绝及受控marker防重放green均已取得；冻结源码Spec/Standards两轴approve，主协调完整App746全过、lint0Error/748Warning。物理旋转/uiMode、OEM/实体键盘、真实下载/创建事务跨重建与进程死亡仍NOT_TESTED，503后续集成尚待主协调，不将上述有限签收扩展为全部外部体验已验。

D所有临时输出/runner/原型在获准D根，无C:项目临时输出；无新网络/依赖/许可或GPL代码复制、用户正文/secret/embedded载荷、出站或新.log。D未执行adb/UI/安装/grant/启停/Gradle/版本/签名/Git写操作。正式14/15/19/20报告分别归原票/派生票canonical目录，实施报告不代独立review。

报告人：Worker D / Codex / GPT-6.1 ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
