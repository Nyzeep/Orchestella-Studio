# Phase 5-10：只读 9p 标准 framing 与资源回收实施报告

- 工单：integration `.scratch/orchestella-phase5/issues/10-compliant-readonly-9p.md`。
- 执行者：storage-research / ninep-worker，会话配置模型 `new-provider/gpt-6.1-sol`。
- 分支：`phase5/worker-9p`；基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`；commit：无。
- 独立工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_9p`。
- 外部证据：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/ninep-repair/evidence/preserved`（下称 E）。
- 状态建议：本地 patch 已实现并经宿主 JVM/进程测试，交协调者独立审查与文件级合流；不宣称 guest mount 或 Android AVF 实测通过。

## 1. 背景与目标

先按 supporting child 任务完成 USB/Downloads 研究并写入隔离 integration 工作区 `docs/research/2026-10-13-phase5-usb-storage.md`。研究用标准 frame 复现原 `Avf9pShareServer` response 缺 tag、Tattach 使用132而标准为104、短 payload未捕获数组越界。协调者追加本实施工单，授权独立 worktree，仅改该server和它的测试。

本工单修复既有只读 Downloads 数据面的标准协议行为、输入限额、fid/FD回收与路径边界，保持控制/数据通道分离、既有契约和readonly语义。没有实现 USB 透传，也没有增加共享目录、任意URI、Bridge命令或宿主权限。

读取独立 AGENTS、dispatch/spec。该 worktree 的全部指定policy与先前只读主仓版本经hash比对相同，继续适用防御性规则、artifact/host/asset/architecture/report要求。implement/tdd/code-review Skill工具同名歧义均有尝试记录，按协调者指示读取project SKILL.md及TDD tests/mocking；已授权stream测试接缝来自工单/spec，不再额外确认。用户禁止派生/commit优先于skill的派生审查和commit指示；本次只有自行TDD与自审，不伪称独立review已完成。

## 2. 实际完成与验收对应

| 验收项 | 结果及证据 |
| --- | --- |
| 标准7byte size/type/tag，Tattach104/R105，response长度 | 完成。独立标准wireclient核验nonzero tag、Rversion21B、Rattach20B、Rgetattr160B、Rread/Rreaddir overhead11B；literal opcode来自官方Linux6.6协议头；wire-red→wire-green原始结果保留 |
| 协商msize、短输入/非法string/未知opcode | 完成。msize范围256–65536、unsigned size/count、按已协商值在分配前拒绝超限；短field、strict UTF-8、NUL、trailing payload、NOTAG约束、有界错误；unknown ENOSYS，未协商请求EINVAL；fragmented input可处理，transport短帧/零进展停止 |
| fid/version重置/断流/无效请求finally回收 | 完成。Session clear关闭channel/root anchor；包括invalid version重协商、EOF、读取短帧、输出异常；clunk/Tremove关闭；已open fid重复lopen/walk拒绝EBUSY，不替换泄漏；partialwalk不分配destination |
| readonly所有写请求EROFS | 完成。14写类/legacy create/wstat opcode矩阵、lopen写/截断/append/创建/exclusive/tmpfile拒绝；未知legacy Topen不猜测写意图，ENOSYS。guest-facing写请求不触碰宿主路径或创建文件 |
| containment/symlink/TOCTOU可执行负例或安全失败边界 | 完成SecureDirectoryStream根锚点+逐段NOFOLLOW+secure-relative lookup/open，无unsafe path fallback；Windows不支持则attach ENOSYS。Linux400次原子symlink↔regular竞态无outside读取。host恶意special-file替换的剩余DoS限制明确见§7，不宣称全面race消除 |
| 独立wire回归、现有contract修订、中文报告/localpatch | 完成。原契约改用StandardNinePClient，旧TestNinePClient保留但已无测试引用；三个新测试文件只测public serve/stream。严格编译、18注册AVFmain、Linux独立process、Windows拒绝门通过；文件级patch位置见§9 |

生产公开API保持 `Avf9pShareServer(SharedDirectorySpec)` 与 `serve(InputStream, OutputStream) throws IOException`。调用方仍拥有streams；server只回收自己创建的fid/channel/directory资源。不会给caller/root授予Android文件权限。error不含原始host path/name/payload。

## 3. 修改模块与局部行为

实际修改/新增五个源码测试文件，均在独占范围：

1. `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/Avf9pShareServer.java`。
2. `runtime/engine-avf/src/test/java/io/github/nyzeep/orchestella/runtime/engineavf/Avf9pShareContractTest.java`。
3. 同测试目录 `StandardNinePClient.java`。
4. 同测试目录 `StandardReadonlyNinePTest.java`。
5. 同测试目录 `StandardReadonlyNinePProcessTest.java`。

另新增本中文实施报告。未改其他AVFAPI、App、contract、Gradle、guest、共享协议定义、tracker、ADR、活动主仓。没有checkout/merge/reset/commit/push/PR/release。

固定最大值：256 fids、4096 qids、4096 inspected directory entries、64 path components、单名255 UTF-8 bytes、单frame最大65536 bytes。超过资源边界明确拒绝，不创建后台队列。minimum msize256使所有固定reply可容纳；requested更小拒绝，不 silently输出比msize大的frame。

根目录从filesystem anchor逐段secure打开，祖先symlink同样拒绝。所有guest请求只在pinned export范围内lookup/open，`..`在export根钳住，slash/backslash/NUL/colon/绝对path拒绝。session固定原directory对象，host rename后仍以该对象为已授权export；版本重置/关闭释放锚点。目录special/symlink不暴露为可读对象，read-dir type用DT_DIR4/DT_REG8。statfs容量返回unknown0，避免用path-based FileStore查询越过锚点。BasicFileAttributes没有POSIX ctime，getattr不再把creation time伪报ctime，valid mask不宣传不存在字段。

Tflush因该server串行处理，在之前请求响应之后返回Rflush，没有可在Rflush后继续发旧tag的async任务；这不是并发native I/O中断API。对未完成的host阻塞read，transport owner的关闭策略仍需评估。

## 4. 测试、原始结果与真实性

TDD主接缝是实际 `serve` 字节stream，测试literal标准opcode与完整response，不引用server私有常量/方法，也不使用旧错误client。第一切片wire red失败在Rversion type/tag/length；修header后green。第二切片parser red失败在无协商attach；修strict parsing/negotiation后green。第三切片secure红例证明Windows原实现仍不安全接受directory；引入secure-provider门后green。

运行环境：Windows `D:/JDK17`（现有只读）与已有WSL `Ubuntu-24.04`（Linux SecureDirectoryStream真实实现）。WSL已有环境缺Java可运行输入，协调者明确授权隔离官方Temurin17 tar下载/解包，细节见§5。没有Gradle/Android build，也没有adb/device操作。

| 验证 | 本次最终结果 | Raw证据 |
| --- | --- | --- |
| Windows exact seam compile/test | 通过；secure_provider_available=false、filesystem读/竞态未运行；attach返回ENOSYS | `final-windows-execution.json` |
| Linux strict Java17 compile | `--release 17 -encoding UTF-8 -Xlint:all -Werror`通过，101相关源码和test编译 | `suite-final-avf-suite.json` |
| Linux标准wire | 通过framing/version/msize/readonly14类、real read、symlink/partialwalk、FID256、negative offset/cookie、huge unsigned count、getattr160B、onebyte碎片、unsigned invalid length、4097directory超限 | `final-linux-linux-execution.json` |
| 所有18注册AVFmain相关契约 | 18/18通过，包括修订的Avf9pShareContractTest；其他AVF模拟契约只用于模块回归 | `suite-final-avf-suite.json`，19 records=1compile+18run |
| FD资源回归 | 1500会话（500×正常EOF/版本重置/短frame），以及invalid renegotiation/输出异常；process FD count8→8 | `final-linux-linux-execution.json` |
| 实际子JVMstdio | 正确读取合成file、EROFS、开fid后EOF正常退出、短frame非零退出 | 同上；`process-fixture-final-linux/` sibling stderr `.log.txt` |
| symlink替换竞态 | 400次atomic swap，3次合法内部读取、397明确拒绝、零outside sentinel读取 | 同上；仅合成fixture |
| git diff格式 | diff --check通过 | `diff-check.json` |

第一次suite compile错误地纳入非依赖的network JUnit下载测试，因无JUnit classpath失败；按实际AVF parity dependency缩小到所需network contract后严格编译和全部注册测试通过，未安装JUnit、未改Gradle、未用mock替代失败。一次suite发现classname regex未包含数字遗漏9p入口，已修正并最终全18再跑通过。这些失败记录保留 `suite-first-avf-suite.json`/`suite-resolved-avf-suite.json`，不把它们说成通过。最终数字以suite-final为准。

测试不是Android AVF VM/vsock、guest Linux mount、QEMU9p、USB/FD设备probe或APK集成。未运行上述项目，无实机验收结论。LinuxProcessTest是本地child JVM，通过真实pipe、host fixture文件与安全relative目录操作；AVF相关模型测试仍只是模型回归。

## 5. 官方LinuxJDK输入、许可、系统变化

仅在协调者授权后下载到外部 `C/ninep-repair/toolchain/inputs` 并解包到 `toolchain/extracted`，没有包安装、system PATH/registry/service/firewall/driver/WSL配置/user目录变更。Adoptium API403、GitHub API rate-limit失败均保留；使用同官方GitHub固定发行页的真实asset链接有限回退，不用第三方镜像。

- 官方发行：[Temurin17.0.20.1+1](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1)。
- 实际input：[OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz)。
- [官方sha256 sidecar](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.sha256.txt)。
- size=193,252,603B；SHA-256=`3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e`，实际与官方sidecar相同。
- 许可：GPL-2.0 with Classpath Exception、assembly exception及各文件声明；归档内 `legal/java.base/{LICENSE,ASSEMBLY_EXCEPTION,ADDITIONAL_LICENSE_INFO}` 已保留。不是生产App新增依赖。
- 精确JDK路径：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/ninep-repair/toolchain/extracted/jdk-17.0.20.1+1`。在已有WSL用对应`/mnt/d/.../bin/java`和`javac`绝对路径运行，`-Djava.io.tmpdir`明确设E内。
- provenance：`linux-jdk-input.json`、`temurin-release-asset-discovery.json`、API失败JSON、inputs侧license/sha。工具链按ClassA保全，可供协调者只读复用，禁止批末删除。

本任务所有输出是精确批准的D盘路径。未读实际`.env`、token、私钥、USB序列号、host userdata；网络只GET公开官方JDK/src资料，没有host源码/日志出站。合成fixture中的“secret/sentinel”是测试字符串，没有用户秘密。raw证据不使用`.log`扩展名。

## 6. 标准来源与自审

wire依据为研究已锁定Linux v6.6 SHA `ffc253263a1375a65fa6c9f62a893e9767fbebfa`：[include/net/9p/9p.h](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/include/net/9p/9p.h)，Tattach104/R105、HDRSZ7、9P2000.L opcode、getattr结构。许可SPDX GPL-2.0-only；仅分析协议，未复制Linux实现入生产。原研究E中 `linux-9p-header.source.txt`/hash可核验。Java生产与测试为原创代码，沿用项目Apache-2.0许可。

自行审查两轴：规范轴核对独占路径、policy范围、ClassA保全、无设备/系统修改/出站、report与diff一致；spec轴核对standardwire、strict parser、allclose、readonly拒写、安全失败门与真实JVM/process证据。自审修复了目录handle close异常时channel所有权传递、invalid-version旧fid未失效、getattr ctime valid mask与路径深度上限。最终无已知未处理的本工单正确性缺陷，残余产品/host-concurrency边界在§7明确。**未做独立review**，需要协调者另行审查；不把本次code-review自审称为第二个模型审查。

## 7. 残余风险、未完成与后续动作

1. Android Java provider是否提供SecureDirectoryStream、本App对真正Downloads的SAF/native根授权和guest NET_9P_FD仍未实测；不支持安全provider时故意不可用。此工单不会从绝对路径Spec自动获得Downloads权限；由协调者的既有/后续授权adapter证明输入范围。
2. secure-relative+NOFOLLOW阻止guest路径/symlink逃逸，但不提供immutable host snapshot。host能在只读guest运行期间更改regularfile内容、原子替换不同regular inode、重命名root、建立hardlink；不是guest写权限。真实用户撤权/关闭必须由transport owner结束session，不自动重新连接。
3. Java17标准SeekableByteChannel API没有可审计的descriptor fstat/O_NONBLOCK门。若**宿主恶意写方**把检查过regularfile替换为FIFO/specialfile，在最终open前仍可能造成阻塞/类型竞态；guest只读不能自行创建该文件。本工单拒绝静态specialfile但没有native atomic type门，所以不能宣称恶意宿主并发修改下完全无DoS。需要JNI安全open/fstat或只暴露内部不可变catalog，归协调者接线，不在本票新增API/Gradle/native代码。
4. Tflush是serial ordered ack，不会中断正阻塞的hostFileChannel读取；真实vsock断流与cancel应由transport-owner策略单独验证。已证明当前同步完成/EOF/异常回收，未宣称native asynchronous取消。
5. pin root必须访问filesystem祖先directory handles；没有列举祖先内容也没有向guest暴露。但AndroidSELinux可能拒绝，应返回unavailable，不静默退到toRealPath再open。
6. 原错误TestNinePClient已无引用，按独占范围保留文件未删除。独立新tests未新增Gradle任务（本票禁止Gradle修改）；Avf9pShareContractTest已有任务会编译新client，但独立process/limits测试需协调者用报告内runner执行或后续正式加入owner build。
7. 未运行guestmount、Android真机、AVF hardware、USB、QEMU9p及APK构建；没有设备独占交接。后续协调者先审patch，再精确合流，验证生产binary/config与真实授权数据面；任何runtime无法满足门都应明确unavailable。

## 8. 架构与越界检查

这是既有只读9P2000.L正确性与资源安全bugfix，不新增协议/权限/目录/USB功能，不改变ADR0007根盘模型或ADR0013路线图，也不修订ADR0010白名单。更严格拒绝symlink、unsupportedprovider和resourceoverrun属于既有边界防护。无需本工单ACR；若后续为了兼容而启用unsafe path fallback、私有目录fsdev或扩大SAF/USB权限，必须由协调者按Architecture Change Policy先决策。

无越界：仅server+ownedtests+本报告写入source worktree，external输入/证据均明确scope；无生产其他域、sharedcontract、tracker、ADR、Gradle/guest或主仓修改。branch没切换、无commit/push/PR/release、无系统安装、无ADB/设备操作、无host内容上传。

## 9. 本地patch与产出

文件级 patch：`E/phase5-10-readonly-ninep.patch`。仅包含上述五个source/test和本报告，不包含工具链、rawlog、fixtures、policies/spec或其他生产模块。SHA-256、exact文件变化hash、基线与scope记录在 `E/localpatch-manifest.json`。未apply到integration或活动主仓；由协调者在审查完成后文件级合流，避免覆盖其他AVFAPI worker。

本报告：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_9p/docs/reports/tickets/phase5-10/implementation-report.zh-CN.md`。

原始输入/验证canonical均在E，源码五项即时保全 `final-<filename>`；已有`final-owned-files.json`固定hash。工具链保留为ClassA，合成fixture/classes为本批实验输出，协调者收口前不清理、不删失败证据。

报告人：ninep-worker / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计
