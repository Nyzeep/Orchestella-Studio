# Phase 5 本地 editor 独立只读审查

- 日期：2026-10-13；审查者：productivity-research supporting child 转 independent reviewer / Codex，无派生。
- 结论：**request-changes**。首轮发现的多项 P1/P2 已即时通知协调者，随后观察到源码修复；截止快照仍有三项 P2 行为缺口。源码修复均不等于回归已通过，更不等于完整 editor、guest 或 Android 支持已验证。
- 工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`，下文 WORK；分支 `phase5/graphics-productivity-storage`；HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 基线：已有协调者 `Orchestella/artifacts/phase5-parallel-20261013/coordinator/dependency-baseline` 中 captured-before；不存在对应基线的新文件明确按新增审查。不是仅比较 HEAD 而忽略第一会话未提交依赖。
- 截止证据：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/B/evidence/editor-review/final-editor-review-cutoff.json`，采集时间 **2026-10-13 12:10:07 UTC**。下文 E 指该 `editor-review` 目录。并行源码可能继续变化；本报告引用行号与摘要以此快照为准。

## 范围、规范与执行边界

只读 WORK 的 `LocalEditorActivity.kt`、`GuestFeaturesActivity.kt`、`GuestFeatureSelections.java`、`SetupWizardActivity.kt` optional choices、`QemuInstanceLauncher.Session.openServiceForward/close`、策略中性的 `ExecutionSession`，以及必要的 `ExecutionSessionRegistry`、BridgeClient feature 返回、已复制 gvproxy forwarding adapter。没有发现单独的 `Orchestella_phase5_integration` 目录，指定 integration 文件实际位于 WORK 的 App package。

尚未合流的服务模块只读于 `Orchestella_phase5_data/runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/services/`；必要的 EndpointPolicy/LocalTcpRelay/ScopedService 与其 prototype tests 已读。guest profile 的 schema/异步 job 行为只读于 `Orchestella_phase5_profiles/guest-assets/features/profilectl.py`，没有抢改 worker 数据或 guest 脚本。没有把尚未完成的 worker 构建闭包列作其已交付缺陷。

规格：WORK `.scratch/orchestella-phase5/spec.md:25–31,45–50,60–63`，工单 `07-services-and-readonly-data.md:13–18`、`08-integration-and-device-gates.md:14–20`。引用本人的 `docs/research/2026-10-13-phase5-productivity.md` 关于固定 code-server 4.141.0、认证、cookie、WebView 与实际 gvproxy 数据面的结论。

已读总政策仍有效：活动主仓 AGENTS、全部 DEFENSIVE_CONSTRAINTS policy、Host/Artifact/Architecture/Asset/Reporting policy，以及 ADR-0005/0008/0009/0010/0011。`code-review` Skill 调用因同名歧义失败，随后读取总目录 `.agents/skills/code-review/SKILL.md`，本人执行 Standards/Spec 两轴。既定独立只读与不派生约束保持，没有额外询问已有基线或改用其他代理。

**本 review 没有执行宿主编译/Gradle/JUnit/guest 脚本/HTTP 服务/设备测试**；宿主编译验证由协调者负责。没有 adb、真机、VM、宿主 daemon、系统权限/服务/防火墙/PATH 修改。没有网络请求、云 tunnel、外部账号或内容外发；没有读取真实 credentials/.env。只在已授权 B/evidence 写最小脱敏证据，在 WORK 写唯一正式 review 文件；没有生产、协议、UI、Gradle、测试源码、tracker、ADR 修改，无 commit/push/PR/release。

## Standards 轴

本轴依据项目真实规则，不以命名、文件长度等一般 code smell 充当 P1/P2。

- **生命周期与证据真实性**：spec/issue07 要求 listener 实际建立与移除，不能用 boolean/null、accepted job 或页面销毁代替。下述 R2/R3 仍未满足实际撤销确认；close 失败时不能静默报告“已关闭”。
- **凭据与暴露面**：ADR-0010 要求每实例随机凭据、不扩大宿主面。初版多 editor 共享可读 cookie 和持久认证标记已反馈；单 owner、周期 status 重验等已见。ServiceWorker/CacheStorage/WebSocket 仍有运行边界，不能宣称已获跨实例强隔离。
- **权限与用户选择**：明确 LAN dialog + 有期限 lease 属既有显式能力；没有提出默认 LAN、云 tunnel、系统 CA/防火墙/新 Android permission。明文 LAN 的告警是已实现限制说明，本报告不凭空把新系统权限作为修复前提。
- **只读所有权**：worker-owned EndpointPolicy 的 IDE 兼容缺口由协调者与 owner 协商，reviewer 没有改它；活动主仓的 gvproxy 契约也未被 reviewer 覆盖。

本轴为 R2/R3 记录生命周期规范缺口，初版认证/多 scope 问题记录源码修复观察；没有完整安全审计 approval。

## Spec 轴：截止仍需修改的 findings

### R1 [P2] 白名单拒绝真实 editor 的工作区与 ServiceWorker 请求

**证据**：WORK `LocalEditorActivity.kt:104–117,169–173` 将导航交给 `requireAllowed`、资源与 SW 请求交给 `requireAllowedResource`。worker data 的 `EndpointPolicy.java:36–77` 禁所有 navigation query，resource query 仅接受 `t/v/version`，且不超过三项。其 `EndpointPolicyTest` 甚至把 `folder` 列为应拒绝，因此这些测试不能证明真实 IDE 请求兼容。

本次从**已校验的实际 code-server 4.141.0 ARM64 tar** 只读提取：

- `out/node/routes/vscode.js:117–158`：已登录后重新打开 last folder/workspace，服务 redirect 到 `/?folder=...` 或 `/?workspace=...`。首次可以显示无文件夹的根页面，但选择/重开真实工作区后该导航被拒绝。
- `lib/vscode/out/vs/workbench/contrib/webview/browser/pre/index.html:32–37`：扩展 WebView 使用 `id/origin/platform/swVersion` 等参数；`255–256` 注册 `service-worker.js?v=...&vscode-resource-base-authority=...&remoteAuthority=...&platform=...`。这些是正常 bundle 路由，当前 policy 全部拒绝。

**影响**：打开目录、恢复上次 workspace、依赖扩展 WebView/SW 的实际工作流失败。不是假设性的外链攻击，也不是要求允许任意 query。spec 第27/31/50行要求实际本地 editor 与严格 origin，禁止秘密进入 URL不等于禁止所有正常非秘密参数。

**最小修复**：按固定发行版真实路由分离导航、资源、SW 的有限词汇、数量/长度/编码与目标约束。继续拒绝 userinfo、外部 host、未知端口、凭据/token/password 参数；对 guest 内工作区选择处理为明确 guest 语义。若某些扩展 WebView暂不支持，明确标识范围，不能写完整 editor 已通过。由服务 policy owner 与 host owner共同完成，不全局放宽 origin。

**可执行回归**：把上述 tar 的原始路由构成正例 fixture；验证正常 `folder/workspace` 及固定 SW query，在允许 origin 可成功，在不同端口/host、编码秘密/重复键/未知重定向时仍被拒。随后用真实 Android WebView验证登录、打开文件夹、终端、workspace 重开与最小扩展 WebView；本 reviewer 未运行。

来源原件与 SHA：E `official-bundle-request-paths.json`、`out__node__routes__vscode.js.source.txt`、`lib__vscode__out__vs__workbench__contrib__webview__browser__pre__index.html.source.txt`。输入 tar 的 publisher/source provenance 在既有研究中；不使用伪造 HTML 验证 editor。

### R2 [P2] 关闭页面时撤销失败仍可能留下真实宿主监听，失败状态对用户不可见

**证据**：WORK `LocalEditorActivity.kt:214–225` 现在保留 `forward.close()` 失败的 handle，这是相对初版的改进；但其 catch 仅 `main.post { if (!closed) ... }`。`retireEditor():228–233` 已先设 `closed=true`，所以真正退出时撤销失败的告警被抑制，且一次 cleanup 后没有有界重试或可见的待撤销状态。

`QemuInstanceLauncher.java:108–114` 在 `engine.removeRule` 成功后才清 active/从 serviceForwards 移除；移除失败保留真实规则。已复制的 `NetworkGatewayDaemon.java:241–253` 在 remove 的 API/回读失败后会尝试 **expose 回滚**，所以不是“抛异常但肯定已关闭”。session retains handle 只提供以后停止实例时的机会，不能推出页面退出已撤销。

另一个同类未归属窗口：`QemuInstanceLauncher.java:102–116` 在 `engine.addRule` 完全返回之后才构造/登记 handle；若暴露成功、后续回读与回滚 unexpose 都失败，open 抛异常前仍没有 session-owned handle。API 的 confirmed map 被清空不等于物理 listener 已删除。

**影响**：用户关闭 editor/开启另一实例后，旧 host loopback 端口仍可能监听；页面文案承诺关闭撤销，却没有在失败路径给出持久状态。LAN 对象本身关闭不等价于 upstream host forward 关闭。spec 第49/61行与 issue07 第14行要求真实 listener removal。

**最小修复**：临时规则的 pending/active/removing/failed 都要由 session/service scope 跟踪；退出失去 Activity 后仍保持可见的 cleanup 失败状态与明确恢复动作。网络 API 删除/回读失败保留所有权，进行有限且可取消的清理，不静默无限重试；未确认物理撤销不能标“已关闭”。必要时提示用户停止该实例，但该提示必须在退出后仍可见。保持第一会话 gvproxy API 的所有权，协调者决定其事务失败适配方式。

**可执行回归**：用现有 ForwardingEngine API接缝模拟：expose 已建立真实 listener，下一 GET 失败且 rollback 删除失败；以及 remove 成功但 GET失败、rollback重开。断言 session仍持有待清理规则，页面close后失败可见，显式retry后socket不能连接、端口可重新绑定。另测失败不误删用户持久端口规则。不能仅断言 forward字段为null。

### R3 [P2] UNEXPOSE 异步 job 未完成就释放 editor scope，退出重入会竞态失败

**证据**：WORK `LocalEditorActivity.kt:220–225` 只调用一次 `feature("UNEXPOSE", "code-server")`，既不检查 accepted/payload，也不查询最终 `exposed=false` / job完成。共享 `editorWork` 只串行桥接调用，不串行 guest job 完成。

profile worker 的 `launch/worker/relay_profile` 为异步执行：命令发布全局 `install.lock` 后立即返回状态；真正 `systemctl stop`、`is-active` 与 NIC listener removal 稍后执行。其 `public():230` 已提供 `job_active`，但宿主退出路径没有使用。其全局 job活跃时另一个 operation被拒为 busy。

**触发**：关闭A、立即重开同实例B或旋转。A的UNEXPOSE仍在guest后台停止relay；B开始时看到 `state=starting`，在启动状态门被拒，或EXPOSE遇busy。即便A“撤销已受理”，旧relay的关闭仍没有最终确认。新 executor/same session identity 能防止一些跨VM误操作，却不覆盖同session内异步job的完成顺序。

**最小修复**：在owned session与service generation范围内等待有界终态；接受UNEXPOSE不等于cleanup完成。观察`exposed=false`、job终止及实际host listener移除后再允许同service新scope。busy、failed、timeout显示确定原因，保留恢复动作；不要干扰新session或停止用户的code-server主服务来冒充relay关闭。

**可执行回归**：延迟guest UNEXPOSE job完成，同时关闭A并打开B；另在旧session退出后替换registry再执行旧callback。断言B不会被旧scope撤销，等待有期限、failure明确，guest NIC relay与host port都确实关闭。必要修复由host与profile owner协调；reviewer未修改worker。

## 初轮已反馈、后来见到源码修复的 P1/P2

以下问题不是截止仍原样存在的finding，不重复算作开放缺陷。全部标为 **SOURCE FIX OBSERVED / REGRESSION NOT_RUN**，后续运行未通过前不升级为验收完成。

| ID / 原优先级 | 初版可证实问题 | 截止观察 |
| --- | --- | --- |
| F1 / P1 | host等待`STATUS.exposed=true`，guest只提供`guest_relay`，成功EXPOSE也会超时 | profile worker `public():229` 已输出 `exposed = guest_relay is True`；双方认证键为 **authenticationConfigured**，本次未见`authConfigured`短键。仍需真实JSON跨bridge回归 |
| F2 / P1 | QemuSession 引用不存在的`PortForwardingRule.Protocol.TCP` | `QemuInstanceLauncher.java:101` 已使用顶层 `runtime.contract.Protocol.TCP`；未替协调者执行编译 |
| F3 / P1 | 多LocalEditorActivity共用127.0.0.1 cookie与全局SW client；不同port不隔离cookie，旧页面可读新登录cookie | `LocalEditorActivity:31–36,75–76,228–233` 增单owner/shared串行scope，先destroy旧web。消除了显式允许多个旧页面同时保留的初版路径；SW/CacheStorage与WebSocket边界仍待运行，不能宣称完整强隔离 |
| F4 / P1 | authenticated只取一次，health仅匿名`/healthz`=200，配置失效后仍可保留LAN/本机转发 | `52–56,180–187,200–209` 已周期/开启前重验status，检查owned session一致，失败cleanup+blank；健康仍不是实时登录成功的证据 |
| F5 / P2 | Session.close与add-rule/register handle竞争，没有closing门；旧Activity cleanup重新lookup新session而UNEXPOSE错误目标 | `QemuInstanceLauncher:87–90,168–173` 增synchronized+closing；LocalEditor `130,181–183,221–223` 保留ownedSession且核验registry身份。同session异步job和撤销失败分别见R2/R3 |
| F6 / P2 | selected profiles一次发多个异步INSTALL，guest全局job锁使其余busy而host丢弃结果 | `GuestFeatureSelections:28–44` 已排序并按安装状态轮询，有45分钟deadline；仍需multi-selection/busy/cancel/timeout/PING回归 |
| F7 / P2 | LAN lease到期只stop，下一次开启覆盖旧relay，deadline/pump executors遗留 | LocalEditor `204–209` 已close旧对象，candidate失败也close；deadline撤listener不等于完整对象closed，需反复短lease生命周期回归 |

修复均由协调者/worker完成，reviewer仅只读核对。没有因问题被即时修复而删除初轮审查证据。

## 401/login、origin、cookie/storage、WebSocket/SW 的实际验证边界

- **真实code-server语义**：根页未登录可能302/303到login，不应要求所有正确路径固定401；固定源码`routes/login.ts`与worker `code_health`验证未认证入口、匿名healthz、密码POST、Set-Cookie与登录后workbench。它是待运行探针，不是本review运行记录。匿名healthz=200只说明该endpoint响应，不能独自证明password正确、Origin约束、WebSocket升级认证。
- **实际来源**：code-server 4.141.0 tar已在前一研究中下载SHA/size通过。此次只读其路由/登录HTML/SW，未执行tar内Node、未联网重新下载，未读取真实guest配置或密码。
- **本机origin**：EndpointPolicy精确raw authority为`127.0.0.1:<port>`，拒userinfo、未知port、外部host、file/content/javascript、模糊path和secret query。App关闭file/content/mixed content、SSL错误cancel，无新增Android JS bridge。这些静态边界有效，但不证明真实editor已加载。
- **cookie**：固定4.141.0 `src/node/http.ts:302–324` 未显式HttpOnly/Secure；cookies按host/path而非TCP端口隔离。随机cookie suffix防名字碰撞不等于隔离。现在单owner方案必须验证旧WebView真的不再运行、新登录前全局cookie清理callback完成。
- **storage/SW**：`WebStorage.deleteAllData`、`web.clearCache(true)`不能直接当作已注销所有ServiceWorker/已清CacheStorage的证据。ServiceWorkerController是全局设置；销毁页面不自动证明注册与后台worker终止。必须测试旧registration、CacheStorage、port复用、延迟cookie callback不会影响新scope。
- **WebSocket**：当前没有单独的WebSocket endpoint enforcement seam。不能从两个WebViewClient HTTP/navigation callback推出JS/WebSocket所有连接都已拦截。必须运行选定origin的合法WS upgrade、未认证拒绝，以及恶意脚本对未知loopback/外部ws endpoint的负例，并明确平台实际可覆盖范围。现有EndpointPolicyTest不覆盖浏览器WebSocket。
- **LAN**：用户明确dialog、非持久lease、Instant→monotonic截止、到期关真实listener与connections，是LocalTcpRelay源码已提供的机制；LocalEditor十分钟期限且开启前status/health gate已见。明文LAN仍明示“未TLS/可信网络”，本review没有要求默认LAN、云tunnel、系统证书安装或新permission。真实不同设备登录/WS与超时不可达未运行，不能声称LAN editor完整支持。

这些运行门是限制/待验项目，不将未运行的恶意浏览器实验伪报为发生过的攻击。

## SetupWizard、optional choices 与策略中立契约

- WORK `SetupWizardActivity.kt:925–955,1039–1043,1114` 复选项默认未选，desktop仅Debian、Podman/editor仅Debian/Ubuntu，持久化仅显式选择；本地没有默认LAN、cloud账户或Docker daemon安装选择。
- `GuestFeatureSelections`空集合直接return，初版并行INSTALL丢项已见修复。安装failed/cancelled不自动重新下载，45分钟wait有界；真实安装结果与用户重试须验证。
- `GuestFeaturesActivity.kt:44–50,70–75,93–104` 明确设备验证未完成、普通用户、Docker CLI与daemon区别；服务密码由用户在guest查看后输入，不进Intent访问URL或status。`onDestroy`用shutdown而非shutdownNow，不主动interrupt共享BridgeClient，保留早期review已识别的控制流纪律。
- `ExecutionSession.java:81–88` 提供中立ServiceForward接口；未支持策略默认明确throw“尚未验证”。当前QEMU路径只准guest18080、host127.0.0.1、确认后返回handle，没有借旧QEMU hostfwd冒充当前gvproxy。
- 尚未合流worker服务类的JVM实验不等于这些Activity已接入同样所有权/health逻辑。LocalEditor直接使用LocalTcpRelay，没有通过ScopedService复用所有管理语义；这本身不是必须替换成某抽象的缺陷，需测试真实消费路径。

## 交给协调者的可执行回归清单

宿主编译/JVM运行由协调者执行。已有测试接缝位于 data worker：`prototypes/phase5-readonly-data/src/test/java/.../services/{EndpointPolicyTest,LocalTcpRelayTest,ScopedServiceTest}.java`。可以用既有JUnit runner运行这些类；它们当前的HTTP fixture是synthetic server，不是完整code-server。

| 回归 | 可执行输入与必须断言 | 层级 / 本次状态 |
| --- | --- | --- |
| Schema round-trip | worker实际public JSON：version/profile/state/authenticationConfigured/exposed/job_active，经FEATURES响应到host；未知/缺字段fail closed，受理不等ready | JVM + Python/bridge接缝 / NOT_RUN |
| Real URL vocabulary | E固定bundle的folder/workspace、pre-index、SW query正例；未知origin/secret/path/重复参数负例 | EndpointPolicyTest扩展 / NOT_RUN |
| Temporary rule transaction | API失败与rollback失败、真实loopback listener、close后connect失败且可rebind；用户持久规则保持 | JVM live-engine/ServiceForward接缝 / NOT_RUN |
| Close / reopen / replace session | latch延迟EXPOSE/UNEXPOSE，A关闭+B打开、registry换代、close/add竞争、晚到callback | host scope/bridge fixture / NOT_RUN |
| Explicit LAN lease | 未授权/无auth拒绝，短lease到期连接断开，重复启停/failed bind对象归零，crash/status失败撤整个scope，restart不恢复LAN | LocalTcpRelay/consumer整合 / NOT_RUN |
| Optional multi-install | 选择两项以上，第一INSTALL异步、第二busy；按状态完整推进，失败/取消/timeout不循环重装，后续PING可用 | host+guest fixture / NOT_RUN |
| Real login and workbench | 未认证root/WS拒绝，正确login、cookie轮换、打开目录/终端/workspace；health页不是完成判据 | 真正guest code-server / NOT_RUN |
| Browser isolation | 两次scope切换/port复用，cookies/DOM/CacheStorage/SW、iframe/new window/redirect、未知WS、SSL错误fail closed | Android WebView / NOT_RUN；必须先取得设备交接 |

JVM fixture通过只能证明被覆盖的host/relay行为；guest/Android/WebSocket/完整editor支持必须继续独立标未运行。reviewer没有运行上述命令、创建测试文件、请求设备交接或替协调者宣布测试结果。

## 截止身份、产物与架构门

下表是截止SHA，完整读取/基线/必要片段在E `read-scope-sha256.json`、`scope-drift-and-diff-check.json`、`final-editor-review-cutoff.json`；每件即时保存字节一致preserved副本，没有`.log`。

| 文件/owner | 截止 SHA-256 |
| --- | --- |
| WORK `LocalEditorActivity.kt` | `1af182112bd004cc97ad8342af3dd623d873df02ec189d6ab8b11660dfe86c43` |
| WORK `GuestFeaturesActivity.kt` | `245e1e9b484c8249c12e1d230c1534580e3e828f217cf074cedc33686d6adb6e` |
| WORK `GuestFeatureSelections.java` | `156f9f0ff4d6e7011d2dede2fb1645169a72cfd86eadbb55f3b05d3a09642d9f` |
| WORK `SetupWizardActivity.kt` | `b5ada163252457e5e69334b313cdb8ada054976bef70388e82faec6965ab8e89` |
| WORK `QemuInstanceLauncher.java` | `8e7914c08b55db57fbb1b92d7d58560a76b6f391119feaab86e92b44b2938b54` |
| WORK `ExecutionSession.java` | `913bab3104f2f04f9a2c1a4c5f6349b7b8738fd091de56267afc18d2cb768344` |
| WORK `NetworkGatewayDaemon.java` | `154811642c3f921156fb18496dbb5a25adb6a5f3748b8fbbe8efdc17040881b1` |
| profiles worker `profilectl.py` | `b9c8100b46e5ca2d9a9bb1331f8463c2d5b43aa05b566fe2dd601807cf4d4fa0` |
| data worker `EndpointPolicy.java` | `dba5c1c5577511d87cde194d4dcfdde584d31236dae598e563b3c629c050fca0` |
| data worker `LocalTcpRelay.java` | `f488a3494e660edecd712b9760e7c7996dc73bac61c6763271859a37779bbb04` |

**实际完成**：限定只读审查、固定release实际请求取证、即时P1/P2反馈、并行修复后的定点源码复核、两轴中文report、可执行回归建议。**未完成**：宿主compile/JVM、guest进程/安装/auth probes、Android editor/LAN/WebSocket/SW全链路、完整多实例隔离验证。commit：无；新增正式文件仅本报告；附属证据仅E，无生产变更或外发。

**Architecture Change Policy**：上述R1–R3为既有spec的兼容性/正确性与生命周期修复，没有重开ADR或扩大权限。若要求并行多个互不信任的editor、增加新的宿主JS/message bridge、默认LAN、云中继/认证或新的系统权限，须由协调者另走架构门；本review不提出或批准这些能力。ADR0008 loopback/显式LAN、0009桥接版本/鉴权、0010独立随机凭据与白名单、0011冷恢复/后台尽力而为仍有效；不以systemd或局部JVM tests替代这些产品边界。

Standards轴：2项仍开放的生命周期规范缺口（R2/R3）；Spec轴：3项仍开放的P2行为缺口（R1–R3），另7项即时反馈已见源码修复但回归未运行。结论保留request-changes，不作发布/设备approval。

报告人：Codex / independent editor reviewer ｜ 日期：2026-10-13 ｜ 耗时：未统计
