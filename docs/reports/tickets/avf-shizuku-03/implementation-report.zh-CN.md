# avf-shizuku-03：真实 Shizuku 普通客户端本地实施报告

- 工单：`.scratch/orchestella-avf-shizuku/issues/03-shizuku-client-lifecycle.md`。
- Owner：A / 主协调；执行：Codex / new-provider/gpt-6.1-sol；日期：2026-10-15。
- 分支/基线：main / `428860ad9a1cb335f8be78c157b2ff57faf2a6da`。无A新commit、push、worktree；版本0.5.0/code500由主协调管理，A只在App依赖块加SDK。
- E=`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015`。研究：E/research-auth/report.zh-CN.md与reference-manifest.json，正式综合研究在docs/research/2026-10-15-avf-shizuku-upgrade.md。

## 用户价值与当前状态

现有Shizuku文字引导已接真实SDK的安装/连接/客户端授权状态。用户只能通过可见按钮请求client consent；界面明确它不会自动授予Android MANAGE/CUSTOM，也不会启动VM。源码接线已冻结交统一编译/单测/lint与独立review。**首次集成check已exit0；定点修复后的offline终验与复审pending，真实设备配对/权限对话框/撤回尚未验收。**

## 实际完成与验收映射

| 验收项 | 本地交付 | 验证边界 |
| --- | --- | --- |
| 未安装、Binder待到达、未授权、拒绝、授权、死亡、重连 | Transport查询唯一manager包，controller输出各状态；未连接不以SDK缓存grant当授权 | 纯模型事件序列已过；真实SDK/Android需集成构建与设备 |
| 仅可见点击请求、拒绝不自动重复 | REQUESTING期间不重复发出；普通拒绝、记忆拒绝分开；30s单次deadline、无自动重试 | request/denial、remembered/unsupported、timeout模型回归 |
| sticky/dead/result生命周期与代际 | 对话框onStart注册；onStop注销并清pending；Binder epoch与界面lifecycle分别退役；进程内唯一requestCode关联真实SDK原code | death/reconnect/old code/old listener模型回归；真实排队SDK事件待设备 |
| 撤回可能force-stop | 文案明确manager撤回可停止App，重新打开重查；不承诺原Activity有撤回回调 | 上游固定源码证据；本设备未操作 |
| 权限与实际VM分层 | 显示Shizuku client状态和本App两项Android读值；重复说明API/Guest另验、重启需用户重启server | 不点亮AVF成功、不改launcher/VM owner |
| 固定SDK与许可 | api/provider13.1.5，传递aidl/shared同版；完整MIT文本随assets，About明确可达 | 四模块POM官方GET已证；实际APK条目待唯一集成构建 |

## 模块与文件

1. `runtime/capability/.../ShizukuClientController.java`：纯Java controller、Facts/Snapshot/Transport/Events公共行为接缝。状态包括NOT_INSTALLED、BINDER_PENDING、UNAUTHORIZED、REQUESTING、DENIED、DENIED_REMEMBERED、AUTHORIZED、DEAD、UNSUPPORTED、ERROR、TIMED_OUT、STOPPED；不承载AVF结果。
2. `ShizukuClientControllerTest.java`与capability build中的`shizukuClientControllerTest` JavaExec，接入`:runtime:capability:check`。
3. App `AndroidShizukuTransport.kt`：真实Shizuku13.1.5 API；sticky Binder/dead/permission listeners显式注册/注销到main handler，保留SDK传入requestCode，不给旧结果贴当前epoch。只查固定manager包，不枚举应用。
4. App `ShizukuClientDialog.kt`：共享DialogFragment。可见请求/刷新、官方安装/启动说明、打开manager、复制两条ADB命令；没有命令执行、grant/revoke或VM操作。对话框停止观察时退役pending/listeners/deadline，不无限轮询。
5. Main/Setup原向导进入共享dialog；Settings增加同入口，已grant仍可查看普通client状态；平台不适用时保留现有向导隐藏规则。Main/Settings恢复时重读Android预检；与02共享UI同owner顺序实施。
6. `app/build.gradle.kts`仅增加固定SDK依赖；`AndroidManifest.xml`增加provider与精确manager包queries。`strings.xml`、Settings布局提供准确状态与动作。
7. `app/src/main/assets/third-party-notices.txt`完整MIT版权/许可，列api/provider/aidl/shared；AboutActivity和About布局增加本地可读第三方许可入口。

## 重要实现边界

进程内AtomicInteger分配每次client requestCode，pending同时绑定当前Binder epoch；death、API失败、timeout、onStop均退役。来自旧Binder或已关闭对话框的SDK原requestCode不能完成新请求；新生命周期的listener闭包也有generation guard。refresh只读事实，不能自行发起授权请求。

成功结果还需当前transport事实读回。SDK调用异常映射ERROR，未知安装映射ERROR；server版本过旧明确UNSUPPORTED；已安装但无Binder只能说明尚未连接，不能单靠client推断server确实停止。权限请求30秒deadline只负责UI等待与结果退役，不声称能取消管理器系统对话框或全局授权；用户之后可主动重新检查。

Provider声明：`${applicationId}.shizuku`、exported=true、multiprocess=false、`android.permission.INTERACT_ACROSS_USERS_FULL`保护。API_V23 uses-permission与V3_SUPPORT由官方provider AAR合并。没有声明自有`moe.shizuku.manager.permission.*`权限，没有打包manager/server/图标或使用其App身份。

此client授权不自动grant/revoke Android权限，不新建exec/UserService、特权VM代理/长期server，不改变Guest bridge/FD/VM持久owner，也不触及Instance/根盘/凭据。所有外部页面仅由用户点击固定官方URL或已安装manager触发，未增加后台出站路径。

## 真实red→green与集成状态

E/implementation-03中的JDK17结果与preservation：

- `client-missing-manager-red-utf8.json`：初始缺controller能力的compile red；`client-missing-manager-green.json`编译0/测试0。最初默认输出编码失败的原记录保留，UTF8重跑保留可读缺类信息；不当行为assert red。
- `client-request-denial-red.json`：编译0/测试1，visible request没有进入REQUESTING；green编译0/测试0。验证主动动作、重复点击不双发、拒绝后refresh无自动请求。
- `client-death-epoch-red.json`：编译0/测试1，新Binder继承旧pending；green0/0。验证旧code不能完成新请求、当前readback才授权、queued旧listener不污染新生命周期、stop后回调无效。
- `client-api-failure-red.json`：编译0/测试1，SDK读失败后旧pending复活；green0/0。并验证记忆拒绝/不受支持server不请求。
- `client-request-timeout-red.json`：缺超时公共行为的compile red；green0/0。验证超时不成功、不自动重试、旧result不能恢复。
- 最终controller包含7个用户行为场景，上述均是公开Transport边界的结果，不检查源码字符串或私有成员。实际SDK事件的Android执行不由fake结果替代。
- `precompile-static.json`：4件XML解析、diff whitespace检查、version500保留；它只证明语法/静态范围，不替代Android编译。
- 主协调已启动`client-integration-checks`：`:runtime:capability:check`、完整`:app:testDebugUnitTest`、`:app:lintDebug`及`recordShizukuDependencyProvenance`，只解析合法官方固定依赖。**当前pending，测试结果等待主协调回填。**
- 为避免独立review修复前用相同500产生多个不同APK，主协调本窗口暂不assemble；唯一APK构建/内容检查在review收口后。A未自行运行Gradle。

真实首次无线配对、client系统授权、拒绝/撤回force-stop、server restart、设备重启、OEM后台、真实UI可达性/主题与Android merged manifest均须后续设备/构建验收。本轮未adb、安装、配对、grant、强停或读取设备盘。性能不适用。

## 首次集成结果与定点修复

主协调回报首次`client-integration-checks`已exit0，capability完整check、App完整test、lint、4SDK provenance全部完成；未assemble，不复用同500产生不同APK。

冻结期间A只读发现一个真实时序缺口：客户端请求pending时，用户刷新若先读回granted=true而SDK结果尚未送达，旧pending未退役，原timeout可能把AUTHORIZED覆盖成TIMED_OUT。主协调明确授权定点修复后，新增第8个公开行为场景：pending→current readback grant→旧code timeout；`client-granted-readback-timeout-red.json`编译0/测试1、确切失败“the original timeout cannot undo current granted readback”，最小修改后green编译0/测试0。refresh在事实已grant时清pending/requestCode，UI同步移除deadline，旧code不能回退授权。证据与即时孪生在E/implementation-03/preservation。

没有扩大其他race；修复后再次冻结交主协调offline capability/App全套/lint终验及双轴复审。**最终计数/复审与唯一APK尚待主协调结果，设备项仍未验。**

## 依赖与许可证

精确直接坐标：`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5`。官方POM明确传递aidl/shared13.1.5与annotation1.3.0；实际Gradle选择结果和输入哈希由主协调provenance任务记录，不从Git HEAD推导发布版。

A研究API完整SHA `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`，官方发布Shizuku.java/BinderWrapper/Provider三个sources.jar成员字节一致；这不是所有AAR二进制/传递组件全量审计。api/provider POM与sources在E/research-auth；本票另GET官方aidl/shared POM，状态200、MIT全文引用对应root LICENSE，见`transitive-pom-provenance.json`与两POM（E/implementation-03）。

APK资产包含api/provider/aidl/shared四模块列表、`Copyright (c) 2021 RikkaW`与完整MIT许可；About→第三方开源许可无需网络可读。Shizuku管理器品牌/图标不复制。未来新增native/VM代理依赖不由本票授权。

## 风险、回滚、报告与状态

剩余风险：真实SDK/provider合并与targetSdk36行为需构建/设备；manager撤回可能终止App；已转交特权能力/VM生命周期在本票不存在，不能据本票泛化。Android两项grant原状态仍可能独立存在或缺失；只读展示不会自行撤销。

回滚controller/transport/dialog、provider/queries/SDK依赖、UI与notice入口即可，不卸载Shizuku、不清数据、不撤回已存在Android grants。无实例迁移、FD/owner变化或新的Guest暴露面；普通client属于已接受L2范围，本票不触发VM代理ACR。仅授权路径内源码与报告写入，无设备或系统越界，无commit/push。

建议状态：ready-for-review（本地接线完成，集成check与独立review pending；设备体验不签收）。后续以主协调实际测试、APK/provenance与双轴review裁定完成，不用此报告预先批准新架构。

## 主协调最终验收补记

本地状态：**completed-local**。作者封板时pending现由真实终验补齐：offline capcheck/client八场景入口、App715零失败错误跳过、lint0Error/Fatal与747Warning、4个13.1.5 AAR实际解析SHA固定。13唯一500/0.5.0 APK106506176B、有效同证书，唯一MITnotice1531B==source且四坐标/完整MIT正文齐全，About入口静态接线已审。E/integration/client-reviewed-verification.json、final-v050-apk-verification.json、apk-license-and-inputs.json有原件/保全。

独立规范/Spec轴均最终approve；原pending readback后旧timeout覆盖P2真实反例修复并独立复验，当前0发现。许可源已通过.gitignore精确例外可后续正常提交，生成镜像/资源仍ignored。真机首次配对/授权/撤回/death/OEM仍在06未验，VM owner/Guest/性能受ACR及设备门；本票无grant/exec/UserService产品代码。无commit/push/安装/发布。

报告人：A / Codex / new-provider/gpt-6.1-sol；主协调最终补记 ｜ 日期：2026-10-15 ｜ 耗时：未测量。
