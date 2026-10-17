# 品质保全与协议生命周期独立终审

- 结论：**approve**。Standards 轴通过；本职责范围内的 Spec 轴通过。固定源码与实际宿主验证证据未发现尚未解决的阻断问题。
- 审查者：Codex / new-provider/gpt-6.1-sol；日期：2026-10-14。
- 背景与 scope：品质优化与 Phase 5 双线合流的独立品质保全／协议生命周期审查，覆盖 Main clear／scroll／IME／主题／布局、唯一控制 reader、deadline／reauth／POWER、DNS／forward／writeback／trust／identity 及测试 union。
- 分支：`integration/quality-phase5`；工作树：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_merge_dev`。
- 固定基底：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 品质 foundation：`3b637e48f51fb4b78282d468d9c78b37d582de87`。
- 合流提交：`cfec895ca0bd0186b212f7c73d27b8d6bb06898c`。
- 最终源码提交：**`6bbc31c29e97c0eba64122d22d9ea301d658d097`**，包含编辑器 singleTask 重入修复。
- 比较方式：`git diff 8d0e8c75d769ecc645ea102b90a304c8657344a1...6bbc31c29e97c0eba64122d22d9ea301d658d097 -- <本轴具名路径>`，以及 `git log --oneline 8d0e8c75d769ecc645ea102b90a304c8657344a1..6bbc31c29e97c0eba64122d22d9ea301d658d097`。merge-base 实核等于固定基底；终审时 HEAD 等于最终源码提交，app／runtime／guest-assets 没有未提交源码差异。
- 独立性：本人此前编写的 `tools/phase5/guest-agent-inputs.lock.json` 及相关 producer **完全排除本轴，不给其自审 PASS**；由另一独立 Spec 轴审查。完整工具链、产物供应链与 Phase5 数据面结论以 [Spec 审查](spec-review.zh-CN.md) 为准。

## Standards：规范合规

审查依据为已读 AGENTS.md、CONTEXT.md、issue tracker、[防御性约束入口及两份 policy](../../../agent-handoff/DEFENSIVE_CONSTRAINTS/README.md)、[Host policy](../../../agent-handoff/HOST_ENVIRONMENT_POLICY.md)、[Artifact policy](../../../agent-handoff/ARTIFACT_ORGANIZATION_POLICY.md)、[资产保全规则](../../../agent-handoff/ASSET_REUSE_AND_HYGIENE_POLICY.md)、[架构变更门](../../../agent-handoff/ARCHITECTURE_CHANGE_POLICY.md) 与 [报告规范](../../../agent-handoff/REPORTING_STANDARD.zh-CN.md)。协议与产品语义另对照 ADR-0002 Full Linux 契约、ADR-0007 存储、ADR-0008 网络、ADR-0009 桥接及 ADR-0010 安全边界。

项目 code-review 技能中的 Fowler smell baseline 仅作启发式判断，仓库明确规范优先；未重复工具已经执行的格式、lint 或编译检查，未将大文件或命名偏好列作阻断 finding。

| 规范／合流要求 | 固定版本实际核查 | 结论 |
| --- | --- | --- |
| ADR-0002／0009：策略中性接口、控制与数据面分离、鉴权前置 | FEATURES／DISPLAY／CONFIRM 调用继续走同一 BridgeClient exchange、pending 与唯一 reader；显示连接独立。QEMU control-only 恢复等待失败 reader 有界退休，不停止活终端；AVF 不具备安全单通道重开时明确拒绝。 | 符合 |
| 交接 prompt §7：startup／settle／ordinary 期限与每次认证证明 | 3900000／30000／5000ms 预算保留；首事务 settle 仅消费一次；普通请求先 HELLO→READY→PING→PONG 再发实际命令。迟到 READY、错应答、超长帧与超时按原失败门处理。diagnostic 只输出固定 stage／causeCode，不插入 token 或外部异常原文。 | 符合 |
| ADR-0007／0008：持久化、真实退出与回环网络 | QemuLaunchSpec 两个构造分支均为 `cache=writeback`。POWER ACK 只表示受理，页面及生命周期等待真实会话退出。active-network IPv4 DNS 与 private gvproxy API 保留；默认 TCP loopback、apply-before-save 与回滚语义保留。 | 符合 |
| ADR-0010、品质 spec：随机凭据、信任链与已有数据保全 | 不恢复公开固定密码；未知旧密码保持未知。rootfs 大小／SHA 来自独立发行版 manifest，流式复验与 partial 发布门保留。identity 按 validator 保存、冷加载并传入 QEMU／AVF，缺失旧 sudo 不授予权限。 | 符合 |
| 防御性 policy 与报告纪律：具名读取、来源不改、不将合成结果充作设备结果 | 终审仅读取固定 diff、具名源码／测试／证据；未联网、未改原两来源、未触设备或签名秘密。NOT_RUN 与实验状态保留。 | 符合 |

本轴没有未关闭的规范硬违例；没有要求架构变更或待裁定的新增暴露面。既有 Phase5 未完成能力与 ACR 不因本次 approve 获得放行。

## Spec：品质与合流行为

规格来源为品质 `spec-quality-20261013.md` 验收行为 2–8、追加验收的 shell marker／首次握手／READ／CLEAR，Phase5 spec 的既有实例保全、版本化控制扩展、可选功能与诚实验证状态，以及 [合流执行 prompt](../../../agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md) §6–10 和 [冲突地图 G01–G35](../../orchestration/merge-handoff-conflict-map.zh-CN.md)。未以旧 Phase5 整树或旧 APK 代替最终实现。

- clear／scroll／IME：持久 VT／UTF-8 解码、shell 权威 marker、命令投影、alternate screen、CLEAR 显示 epoch、HOME＋ED0 处理及累计淘汰行锚点保留。关键模型／输入文件与品质冻结源码做过 normalized 比较；真实合流改变逐 hunk 核对，raw EOL 差异未当成功能回退。同模型重入修复另列于下表。
- 主题／布局／首页：品质主题设置、ThemeApplier、首页布局与实际网络信息保持；Main 新 hunk 是经校验的身份透传。功能详情入口呈现可选安装／启动／真实状态与实验说明，没有把入口可点击或宿主 GLES 成功升级成全发行版桌面可用。
- 控制／guest：新 FEATURES 与 CONFIRM 复用认证和期限体系，拒绝扩展后仍可 PING；统一 C 保留幂等 HELLO、固定 POWER 白名单、ACK 后才 dispatch、helper 的期限与子进程回收。POSIX 测试宏结果与生产 ARM64／设备执行严格区分。
- identity／trust：hostname／username／hasSudo 从实例保存和冷加载到 LaunchParameters、两执行策略与 bootstrap 实际字段一致；旧签名兼容、非法值拒绝、旧密码不生成新值。普通用户 profile 缺身份或为 root 时明确 action-required，不静默以 root 代替；noSudo 保持用户选择。
- test union：固定提交中，品质 manifest 的25个具名 Java／Kotlin 测试文件内236个注解测试方法、Phase5 的33个具名文件内68个方法没有遗漏。此比较只证明方法保全，未由名称推断断言等价。另实审 response-pump、identity、重入、READ、启动失败回收等关键断言及 check 注册；实际完整 App XML 才是本次执行总数。

本职责范围的 Spec 没有未解决的错误实现或新增研发清单。真实设备验收未完成，按交接允许的受阻处理保留 NOT_RUN，不构成虚假设备 PASS。

## 四项预审 finding 关闭

下列修复均重新对 `6bbc31c29e97c0eba64122d22d9ea301d658d097` 的具名 blob／hunk 核对，工作树 normalized 内容与固定提交相等；不沿用活动工作树的预审结论。

| 编号／原问题 | 最终实现与具体位置 | 关闭依据 |
| --- | --- | --- |
| QP-01：瞬态 reauth 使 profile 选择 owner 退休 | `app/src/main/java/io/github/nyzeep/orchestella/GuestFeatureSelections.java:35` 以当前 session／client／generation 与 failed 状态判断归属；`runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/features/GuestFeatureSelectionDriver.java:61` 单独有界等待 ready。 | 固定 hunk 符合；GuestFeatureContractTest 的 transient reauthentication／stale mutation／pending 保留用例实际注册并在最终 runtime 日志通过。关闭。 |
| QP-02：初次 HELLO 未完成期间终端重入丢 ready | `app/src/main/java/io/github/nyzeep/orchestella/TerminalActivity.java:778` 观察同 cached client 的 ready，受 foreground／binding／client guard 与期限约束；不重新握手或建立竞争 reader。 | ControlChannelRecoveryTest 12项全绿，含重入等待唯一 reader、旧 waiter 退休不打断握手和期限／不额外 HELLO。关闭。 |
| QP-03：同终端模型重绑把 READ 强制切 FOLLOW | `app/src/main/java/io/github/nyzeep/orchestella/ModernTerminalView.java:96` 同 model 分支保留 scrollState、display epoch 与淘汰行基准，换模型才重置；CLEAR／显式 FOLLOW 语义保留。 | 固定实现逐段复核；TerminalScrollStateTest 8项、TerminalScreenModelTest 46项及对应页面接线／重绑回归所在完整 App 门全绿。尚未进行设备视觉复验，不据此声称设备已验。关闭。 |
| QP-04：取得 gateway 后启动失败遗留 gvproxy，阻断同实例重试 | `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java:445` 使用共用的一次停止门；`completeLaunch`（固定版本610行）在目录／参数／进程／发布失败时关闭未发布 session、销毁已取得 handle、回收 gateway。成功退出 watcher 使用同一回收门。 | QemuInstanceLauncherSanitizeTest 8项全绿；失败重试、interrupt／非法参数、cleanup 异常保留等负例与实际生产清理接线一致。关闭。 |

另核最终 `6bbc31c` 的编辑器 onNewIntent：跨实例／已关闭页先退休旧 scope 与 WebView，再重新创建；同实例存活 scope 保留，缺失实例关闭。LocalEditorReentryTest 5项在最终663项门通过。显示 socket 所有权修复的4项负例也进入完整 App 门；完整显示／WebView／数据边界审查由另一 Spec 轴负责。

## 新证据真实性与测试边界

下列路径以外部批准根 `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-phase5-integration/` 为基准。本审查者没有重跑构建／测试；读取实际 JSON、日志和 XML 并独立比较，未将旧轮计数累计成新结果。

| 本次门 | 直接核验结果与出处 |
| --- | --- |
| 最终 App／assemble／lint | `evidence/merge-host/editor-reentry-final/gradle-result.json` exit0，原始日志 BUILD SUCCESSFUL。实际解析 `build/merge-host/outputs/app/test-results/testDebugUnitTest/TEST-*.xml` 共92套：**663 tests／0 failures／0 errors／0 skips**，含编辑器重入新增5项。 |
| lint | 实际解析 `build/merge-host/outputs/app/reports/lint-results-debug.xml`：**Error 0、Fatal 0、Warning 688**。保留警告，没有将 warning 当零或由本轴重复提出 tooling style 问题。 |
| 全适用 runtime | `evidence/merge-host/final-after-display-timeout/gradle-result.json` exit0，具名日志确认 contract／instance／engine-qemu／engine-avf／network／distro／capability checks；`display-owner-final/gradle-result.json` 随后再次 contract check exit0。两次日志均含 response-pump task，前者含 GuestFeatureContractTest 通过记录。 |
| 完整 profiles | `identity-profiles/linux-profile-final-20261014/evidence/result.json` exit0、无选集参数、sourceUnchanged=true；六个具名 source pin 实算与当前原字节相等、normalized 与最终提交相等。原日志 `Ran 39 tests in 262.819s`、`OK`：**39项全绿**。 |
| guest 基线 | `identity-profiles/guest-final-validation/20261014-final-generated-source/` 下 matrix／failure diagnostics／native recovery loader／manual recovery 的 result.json 分别 **133／14／56／11 checks，exit0、skip0**；各 raw log SHA 与 result 字段实际相等。均声明 guestDeviceValidated=false。 |
| POSIX 控制／HELLO／POWER | `posix-validation/evidence/protocol-union-support-result.json` 锁定最终 C 源 `f1250967916612da9680526a51fa99973c711b21ed6b4044cbd2468dff1c5fb5`：50／50 cases、865 assertions；HELLO14／14、POWER24／24。真实宿主 stdin／testTCP 与 mock POWER，不是 ARM64 native transport／设备验收。 |
| 最终 APK | 对 `delivery/Orchestella-Quality-Phase5-local-debug.apk` 实算：**71969178 B**、SHA-256 **`e0ac730bd4e0441e6d9d347e7f25643f1ba9b0b00c4c8fa5a63474fdd8e7101a`**，与最终快照逐值相等。完整 ZIP／producer 对应关系由独立 Spec 轴负责。 |
| 设备 | `delivery/device-result.json`：signatureCompatible=false、installAttempted=false、runtimeRegression=NOT_RUN，existingAppDataPreserved=true；本次临时 service／forward／lease为0，七族长测未重启。 |

最终 runtime／App 检查与故障历史的解释沿用 [canonical 验证报告](../../orchestration/quality-phase5-integration-verification.zh-CN.md)，实现范围见 [canonical 实施报告](../../orchestration/quality-phase5-integration.zh-CN.md)。两份报告的数字整理及 README 下载指引属于文档收口，不改变本审查固定源码提交。

## 风险、未运行与后续动作

新候选与已安装主包签名不兼容。本次没有读取 release 签名、覆盖安装、卸载、清数据或改系统确认策略。新候选基础 Linux／终端 READ／clear／IME、POWER／冷保留、active DNS／forward、真实显示 input damage、普通用户服务等设备回归均 **NOT_RUN**。宿主 fixtures、源码门、APK hash 与旧设备结果不得移作新设备 PASS。

688项 lint 警告保留；GPU／zero-copy、OEM AVF 可运行性、Android 9p provider、SAF／USB 原型、未实现的其它 profiles 继续按 canonical 矩阵记录。此次 approve 仅覆盖固定提交的品质保全／协议生命周期源码与现有宿主证据，不能作为公开二进制发布、完整设备验收或未实现能力放行。

后续由协调者完成文档／两轴汇总和既有授权内的普通第一方集成分支交付。任何后续生产源码变更需重新核受影响 hunk／gate；不得仅凭本报告批准不同源码版本。邮件／回信和真实用户私有内容不在本报告或 Git 交付范围。

## 修改、越界与终态

- 审查实际完成：固定commit／merge-base／提交链、具名diff与源码行为、四项 finding 修复、测试 union、新证据数字与摘要比较；Standards／本轴 Spec 结论分别为通过，未解决 findings **0／0**，终态 **approve**。
- 本轮唯一新增文件：本 `standards-review.zh-CN.md`，目标写前核不存在且归属该获准 review 批次；无覆盖旧证据。
- 本轮修改源码、二进制、input-lock／producer、其他报告、tracker、ADR、宿主系统、设备：**无**。本轮 commit／stage／push：**未执行**。
- 本轮新增构建／测试运行、网络访问、自有设备 service／forward／lease／后台进程：**无**。
- 本轮未完成：新候选设备运行验收；自写 lock／producer 不由本人独立审查；完整数据／显示／许可证产物轴由另一审查者负责，不冒充本轴完成。
- 越界：**无**。未修改原两来源或 Class A 资产，未读秘密／私有 QA，未扫描历史或打包整仓。
- Architecture Change Policy：**未触发新的变更门**；既有 accepted ADR／spec／ACR 状态未修改。

报告人：Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-14 ｜ 耗时：未单独统计
