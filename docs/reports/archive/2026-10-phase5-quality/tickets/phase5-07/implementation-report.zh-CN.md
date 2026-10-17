# Phase 5-07：受限本地服务与只读文件数据面实施报告

- 票号/标题：07，受限本地服务与只读文件数据面。
- 执行者：data implementation worker / Codex（new-provider/gpt-6.1-sol）；亲自执行，无派生。
- 唯一工作树 W：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_data`。
- 分支：`phase5/worker-data`；基线/当前 HEAD：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 外部批次 E：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013`。
- 提交、git add、push、merge、reset：均无。交付为新增 untracked 文件和包含这些文件的本地 patch。

## 1. 目标与实际完成

本票在确定的服务 origin 与用户明确选定的 Downloads 文件集合内实现可测的纯 Java 模块，保持 Android/guest/VMM 集成由协调者负责。已读 AGENTS、防御目录全部政策、HOST/ARTIFACT/ARCHITECTURE/ASSET/REPORTING/ORCHESTRATION 政策、Phase 5 spec、07 号工单、总派工以及 ADR-0007/0008/0010 的相关边界。并行 productivity/storage 研究形成后已读结论，未重复联网调研。

| 验收项 | 实际结果与证据 |
| --- | --- |
| 精确 endpoint 与凭据模型 | 已实现：HTTP(S) + 原始 authority 精确 `127.0.0.1:<确认端口>`，无 DNS；拒 userinfo、secret query、外部/IPv6/编码/IDN host 与路径混淆。每实例生成 256-bit 随机凭据；用户输入有强度下限、显式 reveal 和清零。 |
| 真实 listener/relay 生命周期 | 已实现并运行真实 TCP socket：双向字节、半关闭、重复启动、端口冲突、stop/close、在用连接回收和终态拒重启。LAN 必须双重授权确认与有限 deadline，默认无 bind、到期关闭、不持久化。 |
| scoped service health | 已实现真实 `GET /healthz`、禁 redirect/代理/缓存，startup timeout、503/恢复/退出、重复 start、stop listener 核验。仅 handle alive 不会成功；失败关闭保留 ownership 供显式重试回收。 |
| 只读明确文件集合 | 已实现完整清单预检、可信 SourceProof、非 seekable 流、数量/名称/MIME/单文件/整代/保留代磁盘 quota、取消、声明大小与 hash 校验、失败回滚、原子 generation。 |
| 来源与目标边界 | 已测：URI/authority/路径/名称/敏感字符串 sentinel 拒绝，真实 Windows directory junction 来源祖先与 cache 拒绝。生产不递归 scan source，SourceProof 不可证明或变化即拒绝。 |
| guest sharing | 只交逻辑 catalog spec 与只读流；没有 private path、fsdev 参数或挂载操作。mountStatus 只有 `UNVERIFIED`，不会出现虚构 mounted/ready。 |
| USB | 交付独立纯模型与可运行 host probe：实际合成文件句柄 ownership 回收；FD 数字是模型 ID。Android USB 与 native passthrough 未运行；passthrough 明确抛出 ACR-required。 |
| 测试/benchmark/报告/patch | 最终 26 个外部 JUnit + 17 个既有 contract main 全通过；13 个实际 synthetic benchmark 指标与 USB model probe。证据即时保全，报告与本地 patch 可审查。 |

## 2. 精确 API 清单

生产包是 `io.github.nyzeep.orchestella.runtime.contract.services` 与 `.sharing`。不存在 App、Bridge、guest、Gradle 或既有 contract 修改。

### services

| 类型 | 公共接缝与行为 |
| --- | --- |
| `EndpointPolicy` | `loopback(String scheme, int port)`；`origin(): URI`；`requireAllowed(String): URI`。只允许精确 origin，主导航无 query/fragment。 |
| `EndpointPolicy` 资源入口 | `requireAllowedResource(String): URI`。可选 cache-buster 仅 `v`/`version`（有界 hex 或数字版本）与 `t`（数字）；query ≤256、最多3项、重复/编码/未知键拒绝。 |
| `EndpointPolicy` editor 入口 | `requireAllowedEditorNavigation(String)`、`requireAllowedEditorResource(String)`。按固定 code-server 4.141.0 真实路由和字段校验，规则见下一节；不是任意同 origin query 放行。 |
| `ServiceCredential` | `generate()`、`fromUserInput(char[])`、`reveal(boolean explicitlyRequested): char[]`、`close()`。reveal 返回副本，调用方负责使用后清零；`toString()` 仅 redacted。 |
| `LocalTcpRelay` | 构造 `LocalTcpRelay(int targetLoopbackPort)`；`startLoopback(int listenPort): Binding`、`startLan(int listenPort, LanLease): Binding`；`binding(): Binding/null`、`failure(): String/null`；`stop()/close()` 可抛 IOException。端口0为 OS 分配实际端口。 |
| `Binding` | `Binding(String bindAddress, int port, Instant expiresAt)`；loopback expiresAt=null，LAN为声明 deadline。 |
| `LanLease` | `LanLease(boolean userAuthorized, boolean authenticationConfigured, Instant deadline)`。两个 boolean 必须 true，deadline未来且最多1小时。认证 flag 是调用方对上游实际认证配置的确认，relay 本身不伪装认证服务。 |
| `ScopedService` | 构造 `(EndpointPolicy, Launcher, ServiceCredential, Duration startupTimeout)`；`start()/probe()/snapshot(): Snapshot`；`enableLan(int, LanLease): Binding`、`lanBinding()`、`disableLan()`、`stop()/close()`。 |
| 生命周期适配 | `Launcher.launch(ServiceCredential): Handle throws IOException`；`Handle.isAlive(): boolean`、`Handle.close() throws IOException`。Launcher 必须拥有真实服务与 listener；没有假 listener 实现。 |
| 状态 | `State = STOPPED/STARTING/HEALTHY/UNHEALTHY/FAILED/CLOSED`；`Snapshot(State, URI origin, String failure)`，错误只输出稳定代码。HTTP 200 只证明当次本地 health endpoint 可达，不证明 editor/认证/VMM/guest 全链路。 |

Scoped LAN 仅在真实 HEALTHY 后开启，100ms间隔 watchdog检查；HTTP非200、服务退出/不可达、stop或close关闭 relay。connect/read probe各500ms，启动期限最多1分钟。monitor 与端口释放测试真实执行；HTTP响应 body 未解释为业务 readiness。16个并发 relay connections、32个copy线程、32KiB/方向buffer，单次 target connect750ms，read polling500ms、无数据30秒关闭；失败没有补传队列或重试。close后 relay不可再 start；stop后仅能由调用方重新显式 start。

### 固定 editor query 兼容篱笆

协调者独立审查指出最初 cache-buster 白名单会阻断正常 editor 路由。已从 E/B/inputs 的固定 ARM64 tar只读核对必要 source；没有执行其 Node/脚本，也没有下载新输入。锁定 tar SHA-256：`e5cc78da1d631c6cf6162f9b18eeaa8ee9d3239e1a88bb99d0602cdc5fa1852d`；包内 product commit `8b41915d25547e2edd553bab03ea4cbd621f98c5`、quality=stable。来源在 E/C/implementation/evidence/editor-fixed-routes；源码必要摘录和许可证头保留于该处及孪生位置，不复制上游实现到生产。

- 导航 query仅 `/`、`/login[/]`、`/logout[/]`。`folder`/`workspace`互斥，是≤512字符绝对 POSIX guest path；不允许 scheme、反斜杠、`..`、双slash、control或嵌套percent。workspace必须以 `.code-workspace` 结尾；`ew`仅true/1；login/logout `to`只允许 `/`。这些字段不授权读取任何 host 文件。
- webview精确路由为 `/static/out/vs/workbench/contrib/webview/browser/pre/`，或固定 `/stable-8b41915d25547e2edd553bab03ea4cbd621f98c5/static/out/.../pre/` 下 `index.html`、`service-worker.js`、`fake.html`。
- index真实字段：UUID `id`/`origin`、数字 `parentId`、`swVersion=6`、空或publisher.name形式 `extensionId`、`platform=browser`、固定 `vscode-resource-base-authority=vscode-resource.vscode-cdn.net`、精确当前 `parentOrigin`。可选 `remoteAuthority`为空或精确当前127.0.0.1:port、`purpose`有界标识、`disableServiceWorker=true`、`vscode-coi=1..3`。
- SW仅 `v=6`、固定resource authority、当前remote authority/空、platform=browser；fake仅UUID id。错误路由/缺字段/多字段/错端口/外部authority/secret query全部拒绝。
- editor query≤1024、最多16项，键不能编码，重复键拒绝，值严格 UTF-8 单次 decode。没有接受 password/token/auth/cookie 字段，也不会将 query写日志。
- 限定在根路径部署与固定bundle；未知升级/路径/参数继续拒绝，需要新真实fixture才扩白名单。WebSocket、ServiceWorker内部代理资源、扩展外部网络及 cookie隔离未通过此JVM规则自动验证。

### sharing

| 类型 | 公共接缝与行为 |
| --- | --- |
| `DownloadsSelection` | `authorize(boolean userAuthorized, URI downloadsBoundary, Set<URI> selectedFiles, SourceProof): DownloadsSelection throws IOException`；`downloadsRoot()`、`selectedFiles()`。无 proof 的生产捷径。 |
| `SourceProof` | `verify(URI boundary, URI selectedSource): String throws IOException`。可信适配器必须证明当前授权、provider/current-user Downloads归属、持有source对象与稳定版本；返回非空≤256本地比较token，不能包含payload。无证明或版本变化失败。 |
| `ReadOnlyStager` | 构造 `(Path existingOwnedCacheRoot, Limits)`；`stage(DownloadsSelection, List<SelectedFile>, Cancellation): Generation`；`current(): Generation/null`、`storedBytes(): long`、`close()`。 |
| `Limits` | `(int maxFiles, long maxFileBytes, long maxTotalBytes, long diskQuotaBytes, int maxNameBytes)`；maxFiles≤10000、名称UTF-8≤240。quota包含仍保留的已发布generation与在途数据。 |
| `SelectedFile` | `(URI sourceUri, String name, String mimeType, long declaredBytes, String expectedSha256, Kind kind, InputSupplier input)`。declaredBytes=-1为未知但仍按实际流计数；可选expectedHash；Kind仅FILE可入选。明确MIME，不解析内容。 |
| `InputSupplier` | `open(): InputStream throws IOException`，必须来自proof持有的同一对象。不能以URI/path重新open替代真实Android身份门。 |
| `Cancellation` | `cancel()`、`isCancelled()`；取消会关闭已附着source流，blocked socket read实际可中断。适配器自身的open与close也必须有界/可取消；任意第三方阻塞supplier不由Java强制杀死。 |
| `Generation` | `id(): String`、`files(): List<FileInfo>`、`open(String exactCatalogName): InputStream`、`write(String, byte[])`始终ReadOnlyFileSystemException、`guestShareSpec(): GuestShareSpec`、`close()`。release关闭已打开读句柄并回收本代quota。 |
| `FileInfo` | `(String name, String mimeType, long bytes, String sha256)`。immutable完成清单；URI和host path不进入guest spec。 |
| `GuestShareSpec` | `(String generationId, List<String> catalogNames, String mountTag, String guestMountPoint, boolean readOnly, MountStatus mountStatus)`。tag=downloads、mount=/mnt/downloads、readOnly=true、mountStatus只有UNVERIFIED。没有privatePath或物理fsdev路径。 |

URI结构先默认拒绝：file仅明确Downloads边界内regular file；content只识别 ExternalStorage Documents 的明确 primary:Download/Downloads ID语法，未知/opaque DownloadsProvider拒绝。但这些语法检查从不替代 SourceProof。Android11不能申请整个Downloads tree，本模块的boundary不是虚构tree授权；Android适配器若不能在已有精确file grant/持有FD上证明归属和稳定版本，必须拒绝。

每次stage首先校验完整选集和所有描述符；流前/流后/原子发布前重新检查proof。64KiB buffer，CREATE_NEW、NOFOLLOW、每文件force、输入hash与落盘重读hash比较，chmod readonly失败即回滚。同filesystem ATOMIC_MOVE不支持时不会退为非原子成功。只有整代完整后更新volatile catalog；旧代显式保留直至release。failed/cancelled代不成为current，cleanup只针对本次明确创建的文件名和本代目录，不递归枚举source，不重扫、不续传。

### USB prototype

包 `io.github.nyzeep.orchestella.prototype.usbfd` 只存在prototypes，未进入runtime契约。

- `UsbFdSession`：`requestPermission(): String nonce`；`permissionResult(String nonce, boolean granted, Opener)`；`borrowForProbe(): ProbeLease`；`detached()/close()`；`state()`；`requireNativePassthrough()`始终UnsupportedOperationException/ACR_REQUIRED。
- `Descriptor`：`fd()`、`duplicate()`、`close()`；`Opener.open()`。grant后才调用open，duplicate要独立owner，拒绝相同FD/负FD。旧callback、拔出、close不复用授权。ProbeLease只在有效OPEN scope返回FD，拔出/关闭先回收duplicate再原connection。
- `UsbFdModelProbe.main(String[] externalTemp)`可运行，用实际合成文件句柄与模型ID，打印Android probe步骤而不调用设备。真实adapter需匹配设备与nonce、hasPermission/openDevice、保留UsbDeviceConnection、复制PFD、取消/拔出回收；native传FD到VMM还需ACR、真实IPC和设备验收。

## 3. 修改文件与所有权

全部文件为新增，W中既有tracked文件未修改。基线 `.gitignore` 的 `/docs/` 规则会忽略本报告；交付脚本明确把这个owned报告加入patch/manifest，未用git add或改ignore规则。18个可见untracked源/测试/脚本与1个ignored-owned报告共19个交付文件。

| 所属路径 | 文件 |
| --- | --- |
| `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/services/` | EndpointPolicy.java、LocalTcpRelay.java、ScopedService.java、ServiceCredential.java |
| 同上级 `sharing/` | DownloadsSelection.java、ReadOnlyStager.java、PathBoundary.java（内部helper） |
| `prototypes/phase5-readonly-data/` | run-tests.py、run-benchmarks.py；src/main/java/.../prototype/sharing/SyntheticStorageBenchmark.java；src/test/java/.../contract/services/的EndpointPolicyTest、LocalTcpRelayTest、ScopedServiceTest；sharing/的ReadOnlyStagerTest、SymlinkBoundaryTest。 |
| `prototypes/phase5-usb-fd/` | src/main/java/.../prototype/usbfd/的UsbFdSession、UsbFdModelProbe；src/test/java/.../UsbFdSessionTest。 |
| `docs/reports/tickets/phase5-07/` | 本implementation-report.zh-CN.md。 |

外部JUnit放prototype测试入口，避免未经授权改Gradle或将JUnit依赖隐式加到production module。测试runner同时直接编译现有runtime/contract main/test源和prototype，不下载依赖、不修改V:/缓存。使用cached JUnit4.13.2、Hamcrest1.3，JDK固定D:/JDK17；全部classes、JVM temp、args和data放E/C所有权目录，没有使用Q盘/coordinator build。

## 4. 测试与真实证据

最终命令（可重跑到全新外部目录，不覆盖旧证据）：

```text
python "D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_data/prototypes/phase5-readonly-data/run-tests.py" --label editor-compatible-final-full --full
python "D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_data/prototypes/phase5-readonly-data/run-benchmarks.py"
```

javac使用 `--release 17 -encoding UTF-8 -J-Dfile.encoding=UTF-8 -Xlint:all -Werror`。最终gate证据：E/C/implementation/evidence/`20261013T121603638180Z-editor-compatible-final-full.log.txt`，26个JUnit、17个现有contract main均exit0；不是仅输出静态检查。其保全副本在E/B/implementation/evidence/preserved。

| 测试接缝 | 数量与实际行为 |
| --- | --- |
| EndpointPolicy | 5 tests：精确origin与port、IPv6/encoded-host/IDN、路径UTF8/escape、资源query、固定editor导航与webview/SW URLfixture及错端口/credential拒绝。 |
| LocalTcpRelay | 3 tests：真实双向bytes/half-close、实际冲突、active connection关闭、LANfalse/expired拒绝、0.0.0.0 actual bind与deadline释放、闭后重启拒绝。 |
| ScopedService | 5 tests：实际HTTP listener/503/恢复/crash、随机credential/脱敏、LAN经过upstream401/正确auth200、无listener/timeout拒绝、失败close保留ownership重试释放。 |
| ReadOnlyStager | 9 tests：nonseekable/已知abc SHA literal、URI/敏感sentinel、descriptor预检不open、失败replacement、真实TCP截断/blocked cancel、未知size实际quota、保留代quota/lease回收、SourceProof缺失/变化拒绝。 |
| SymlinkBoundary | 1 test：实际源祖先与cache junction拒绝；Windows没有file-symlink权限，不能称已测Linux symlink/openat race。 |
| UsbFdSession | 3 tests：拒权不open、grant/duplicate/detach关闭实际file handles、stale/closedcallback、invalidFD/dup失败与原句柄回收。不是mock成功的USB passthrough。 |

TDD/red-green证据保留：endpoint初始缺模块、path escape失败、relay/lease/service/staging/USB缺接缝、catalog无privatePath测试、失败close失ownership、固定editor query缺API都先记录red再实施。没有把编译失败称为测试通过。self review依据项目code-review的标准/规格两轴亲自完成；工具同名歧义后读取项目SKILL。技能的派生/提交与历史报告commit要求被用户明确限制覆盖。独立高风险I/O review仍由协调者派发，worker不自行approve。

## 5. Benchmark实际数字

最终storage原型结果为E/C/benchmarks/evidence/`20261013T120222143810Z-synthetic-storage.json`（配套.log.txt）；13项metrics、exit0。来源hash记录在JSON；后续仅EndpointPolicy editor兼容变化，没有修改storage/benchmark源，故此storage测量仍对应当前storage实现。USB host model同批probe：E/C/implementation/evidence/`20261013T120222143810Z-usb-model-probe.log.txt`，exit0。

输入全部新生成在E/C/benchmarks/temp/该次时间戳下synthetic-dataset：固定seed的64MiB large.bin +1024×1KiB小文件。benchmark唯一Files.walk只遍历自己刚生成的synthetic tree；production staging没有Files.walk。正/返copy两端都是本批host合成目录，不是product双向同步、guest写共享或真实用户文件。

| 操作 | Wall ms | Process CPU ms | 条件/一致性 |
| --- | ---: | ---: | --- |
| 64MiB sequential create+校验 | 287.6420 | 296.8750 | 新synthetic文件，OS缓存不可控 |
| 64MiB first filesystem read | 50.7984 | 46.8750 | app cache bypass；含SHA |
| 64MiB repeat filesystem read | 51.7384 | 46.8750 | likely warm，未保证OS冷热 |
| application cache fill | 81.0756 | 78.1250 | 读新文件到内存并SHA |
| application cached read | 34.8996 | 46.8750 | 64MiB内存命中并SHA |
| 1024×1KiB create+SHA | 648.4579 | 968.7500 | 新synthetic文件 |
| 1024 small stat | 15.3440 | 31.2500 | 实际readAttributes，大小总数核验 |
| 1025 synthetic tree traverse | 21.2571 | 78.1250 | 实际遍历只限自产数据集 |
| 65MiB/1025 selected files stage+proof+hash复读 | 23270.6756 | 22812.5000 | 完整校验与原子发布；Windows路径/proof开销高 |
| 64MiB forward host copy+SHA | 144.6325 | 203.1250 | generation流→synthetic peer |
| 64MiB return host copy+SHA | 123.2029 | 109.3750 | standalone copy，非共享写 |
| TCP截断+回滚与旧hash校验 | 82.6547 | 140.6250 | 实际64KiB后EOF；整代不publish |
| TCP阻塞读取取消+回滚 | 29.1332 | 78.1250 | 实际cancel/close；旧代保持 |

large SHA-256：`c203b909e78af9884bd89dedf2eb6a740c706ab29e5c28d0345eeeb68d874208`；小文件digest组合：`f679d3681673c9933aff89075269b42232c46766aa5a34e38de07303e0cf321b`。所有数据操作执行hash或metadata计数一致性校验；metadata没有伪造内容hash。

峰值RSS **255,438,848 B（约243.61MiB）**，runner对本次Java进程每20ms调用Windows GetProcessMemoryInfo/PeakWorkingSetSize；各operation的JVM heap和process CPU另有实际字段，RSS不是heap冒充。CPU可因多线程/JIT高于wall。64MiB application cache专为缓存对比，production stage是64KiB流buffer。仅单轮工程观测，非p50/p95统计性能承诺；未flush OS page cache、未改系统缓存，**OS cold cache NOT_CONTROLLED**。

失败历史保留：最初javac参数文件默认编码不接受中文路径，修正JVM file.encoding后正常；添加SourceProof时synthetic helper依赖Windows JDK fileKey，而该平台返回null，`20261013T115940132935Z-synthetic-storage.json`实记exit1/staging未执行。已删除production synthetic捷径，将single-owner合成version fixture留在prototype；没有放松真实Android SourceProof。旧测量/失败记录未删除或覆盖。

## 6. 未运行、限制与后续责任

- Android APK/设备/SAF/USB/WebView/guest/VMM全部由协调者集成与交接，本worker没有adb、install、真机启动、guest修改或runtime挂载。26个JUnit不构成Android功能完成。
- Android provider签名/current-user Downloads/已持有PFD身份与稳定snapshot仍需真实适配。未知provider或不可证明身份必须拒绝。Java祖先NOFOLLOW/realpath只证明静态合成边界，不抵抗同UID恶意并发根替换/hardlink/mount race；真正安全门需native持有FD/openat能力和独立竞态验证。source supplier本身属于trusted adapter，不是guest可提供的任意代码。
- 本票交catalog/core/spec，不实现9p broker wire，更没有修既有Avf9pShareServer。QEMU fixed build缺virtfs/vhost-user，未启动真实9p/virtiofs，不能把本代cache交通用local fsdev暴露private目录；guest mount仍UNVERIFIED。后续catalog broker只能通过本代确切object/name读取，不导出private根。
- relay是TCP bytes，不建立TLS、不证明code-server真实认证已完成。synthetic auth401/200只测转发保持上游认证。实际LAN需协调者确认上游可信TLS、前台授权/警示与实例关闭接线；裸HTTP有密码不能被本票称安全LAN。真实LAN客户端/防火墙/OEM行为未测，测试只在本机接0.0.0.0 listener。
- HTTP200 probe不读取无限body，也不证明用户session/editor ready。launcher必须真实持有owner，权限/认证/guest service/listener分开验；外部gvproxy转发先回读confirmedport再构造EndpointPolicy。
- 一个活动editor切换前的CookieManager/DOMstorage清理callback、每实例cookie-suffix、WebSocket/SW/background请求完整拦截、noJSBridge/file/content/mixed-content/SSL错误拒绝由Android owner负责。端口不同不隔离cookie；此policy没有夸大WebView覆盖。
- USB model没有Android FD ioctl、native dup、libusb wrap、SCM_RIGHTS/QMP或guest controller操作。实际native passthrough须ADR-0010/USB相关ACR，不能把模型FD整数当跨进程权限。
- 1025文件staging耗时较高，当前不为性能改弱proof或hash gate；产品选择较小选集预算，后续优化需保持负例与真实source身份门。

## 7. 宿主、范围与架构检查

写入仅W的owned新增源/prototype/report和E/C/implementation、E/C/benchmarks、E/B/implementation/evidence/preserved。所有JVM temp/data/classes在外部D盘，V:/gradle-home cached jars只读复用。没有手动写C盘、改PATH/Registry/service/driver/firewall/系统虚拟化或Class A工具链；本批没有资产清理。事务自己失败数据/成功generation release只删除明确ownership的Class C路径；合成dataset、测试sandbox、原始证据继续保留供review。

没有读取真实Downloads、实例盘、`.env`、secret或Git objects；sentinel全部是合成字符串。没有上传、外部endpoint请求、遥测、云tunnel或后台重新扫描/重试。初始路径定位与只读分支状态核验先于全部政策完成读取；之后完成政策读取才新建代码、执行I/O测试与benchmark，此顺序偏差已记录。没有forbidden文件修改。

未触发已实施的架构变更：缩小到选定只读Downloads、默认loopback和临时授权LAN沿用批准边界；private-path physical export和真实USB接入保持冻结/未实现。条件性ACR事项已交协调者，本worker没有改policy/ADR/tracker或宣告guest/VMM能力ready。

## 8. 交付、保留与状态

- 本地patch：E/C/implementation/delivery/`phase5-07-data.patch`；含所有新增untracked文件，不需要git add，按单文件no-index diff生成；由交付gate验证 `git apply --reverse --check`，没有应用到任何工作树。
- 精确hash与稳定file list：E/C/implementation/delivery/`phase5-07-data-manifest.json`。patch、本报告、所有ownedsource和关键evidence hash在manifest；不生成整仓manifest。
- 收口gate：E/C/implementation/evidence/`phase5-07-delivery-gate.json`，检查branch/HEAD、tracked diff为空、untracked仅owned文件、patch反向check、whitespace、新`.log`为0、关键evidence及即时保全副本字节一致。gate与delivery均复制至E/B/implementation/evidence/preserved。
- owner：data-worker产生的代码/报告交协调者review，外部证据/合成Class C保留至Phase 5验收与独立review完成，由协调者按asset policy决定精确清理。未删除Class A或旧证据。
- 建议状态：本票授权的纯Java实现/原型slice完成、可合流审查；Android/guest物理集成与独立高风险复评尚未完成。没有commit，HEAD仍指定基线。协调者需按真实adapter能力保持provider/mount/USB的honest状态。

报告人：data implementation worker / Codex（new-provider/gpt-6.1-sol） ｜ 日期：2026-10-13 ｜ 耗时：未统计
