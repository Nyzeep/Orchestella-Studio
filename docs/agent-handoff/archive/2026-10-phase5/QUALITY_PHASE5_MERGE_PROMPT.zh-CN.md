# Orchestella 双会话代码合流：新会话完整执行 Prompt

> 将本文交给一个新的开发会话执行。本文是具体执行指令；背景、既有结果和证据由同目录 [完整交接文档](QUALITY_PHASE5_MERGE_HANDOFF.zh-CN.md) 承载。当前生成交接的会话没有合并、提交或推送。

你负责把 Orchestella 第一会话的品质优化与已返回的 Phase 5 开发成果合成一个可审查、可构建的第一方项目版本，完成适用验证、独立审查、提交，并普通推送到核实的第一方仓库集成分支。用户已经指定由本新会话完成这些操作。请持续执行已授权的可逆工作，使用并行子代理提高效率，由协调者串行处理共享热点。最终交付实际代码、统一运行包、新 APK、测试与限制矩阵、提交和推送结果，不能只交一份计划。

## 1. 用户目标与此次边界

保留第一会话已改善的原生 Linux、终端、真实主题、首页与对齐体验，合入 Phase 5 已实现的可选桌面、开发工具、显示/服务契约及 AVF/9p 修复。发现由合流造成的编译、协议、生命周期、打包或体验回退时，修到适用检查通过。

用户的最新范围是：

> “当前先不进行完成七发行版安装服务和网络恢复与关机验收工作”
>
> “我将交给一个新的会话来进行双会话的代码合并工作”
>
> “我交给另一份新会话完成操作和推送，当前会话任务到此结束。”

因此本任务不会自动重启已停止的七发行版长时间验收队列，也不把整个 Phase 5 愿景作为此次新增研发清单。KDE/niri、另外五族增强 profile、Docker rootless daemon、GPU/零拷贝、USB 原生透传、生产 SAF broker 等未完成项目继续按真实状态记录。合流必须验证已有源码声称支持的行为；必要的定向设备回归可运行，范围应与本次合并直接对应。

全部参考仓库（含 Droidspaces-OSS、Podroid、Termux、PRoot、UserLAnd、LinuxDeploy、termux-x11 等）仅用于本地研究，禁止向它们上游推送、创建 PR 或写入。推送授权只覆盖核实后的 Orchestella 第一方源码与经过审查的具名文档/小型证据，不覆盖私有凭据、实例盘、整个外部 artifact 根或参考源码。

## 2. 精确输入、冻结锚与必须先读的资料

工作区总目录：

```text
D:\Development project(开发项目)\Orchestella开发目录
```

有效来源：

```text
主线品质源码：Orchestella_dev
Phase 5 canonical 源码：Orchestella_phase5_dev
建议新集成工作树：Orchestella_merge_dev
主线冻结根：Orchestella\artifacts\quality-phase5-merge-handoff\quality-main
Phase 5 交付根：Orchestella\artifacts\phase5-parallel-20261013\delivery
Phase 5 captured before：Orchestella\artifacts\phase5-parallel-20261013\coordinator\dependency-baseline
新集成证据根：Orchestella\artifacts\quality-phase5-integration
```

如建议路径或分支已存在，先核其归属与状态；选择新的隔离名称并记录，不覆盖、删除或接管不明工作。

先读取主仓下列入口，并让每个 worker/reviewer 遵守：

- `AGENTS.md`、`CONTEXT.md`、`docs/agents/issue-tracker.md`。
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及两份 policy。
- `docs/agent-handoff/HOST_ENVIRONMENT_POLICY.md`、`ARTIFACT_ORGANIZATION_POLICY.md`、`ASSET_REUSE_AND_HYGIENE_POLICY.md`、`ARCHITECTURE_CHANGE_POLICY.md`、`REPORTING_STANDARD.zh-CN.md`。
- `docs/agent-handoff/QUALITY_PHASE5_MERGE_HANDOFF.zh-CN.md`。
- `docs/reports/orchestration/merge-handoff-phase5-verification.zh-CN.md`。
- `docs/reports/orchestration/merge-handoff-conflict-map.zh-CN.md`，使用其中 G01–G35 的路径、两侧语义和验证要求，不重复猜测文件归属。
- `.scratch/orchestella-platform/quality-20261013-map.md`、`spec-quality-20261013.md`，以及当前 map 引用的相关 accepted ADR。
- Phase 5 canonical 的 `docs/reports/tickets/phase5-08/implementation-report.zh-CN.md`、`docs/reports/orchestration/phase5-verification-matrix.zh-CN.md`、`phase5-integration-guide.zh-CN.md`，其 map/spec/ACR 通过这些文档的具名引用读取。

历史 Host/Artifact policy 中仍有 DroidLayer 路径；本任务使用用户指定的上述 Orchestella 根，不能为解决旧命名修改或放宽安全 policy。仅读取任务必需的具名源码、规则和脱敏证据。网络仅用于核实第一方远端、必要的已锁定构建输入/依赖和明确 guest profile 输入，按对应 lock/URL 处理。禁止后台整仓扫描、全历史打包、外部代码分析上传或云备份。原始验证输出使用 `.log.txt`/`.json`/`.md`，敏感数据保持在原私有位置。

建议 skills（用 Skill 工具调用可用的同名能力，重复名称时核对应 `SKILL.md`）：

- `ask-matt`：沿用用户要求，选择与当前步骤相关的技能。
- `wayfinder`：使用现有质量/Phase 5 地图与 tracker，建立合流工单和依赖，不重做已裁定产品决策。
- `resolving-merge-conflicts`：按精确 before/ours/theirs 分析实际冲突。
- `code-review`：共享控制/guest 协议与数据边界的独立审查。
- `android-dev`：仅在需要当前设备和 Android 构建验证时使用，先核设备归属。
- `handoff`：任务结束时输出简明最终交接，链接本次 canonical 报告而不复制大段旧材料。

## 3. 第一个门：冻结真实性和来源状态

主线和 Phase 5 的历史共同 HEAD 均为：

```text
8d0e8c75d769ecc645ea102b90a304c8657344a1
```

主线历史分支为 `main`，Phase 5 为 `phase5/graphics-productivity-storage`；两侧成果均未提交，普通分支 merge 不会收取这些成果。重新只读核 `rev-parse HEAD`、分支与 index/working-tree 状态，不对两来源执行 `reset/clean/stash`、切分支覆盖或 apply。

主线冻结：

```text
quality-main-source.patch
1446461 bytes
SHA-256 bf95bab41a34e9ae5ccbc8dbd7d43b2a80ae32b6846b29f47da0cfb766719174
76 tracked modifications + 55 named new sources = 131 patch paths
```

核同目录 `quality-main-freeze-manifest.json`、`freeze-verification.json`、`build-input-manifest.json` 和 `SHA256SUMS`。摘要必须做实际相等比较，打印输出不算通过。仅对 manifest 的具名路径检查摘要、大小、路径安全和当前 drift。主线冻结 patch 已解析，未在新树实际应用，不能提前引用为 apply PASS。

Phase 5 冻结：

```text
phase5-integration.patch
1493079 bytes
SHA-256 aa382388a53c60b11a9b05428336930f649b69ad6208e0eaf7736397cfc7578f
phase5-source-manifest.json
SHA-256 e3eb645be924346a3c5620d4c8007623f3b5ea5df10da6b555fb879b4ed01a8d
build-lock.json
SHA-256 cc7a5267f8d877a455bcd40c6de95838e3c3fa8f35d1557b377727106ccc13f4
```

核 `SHA256SUMS` 的 14 个具名交付项、141 条 source closure 与 canonical owning paths；保留 raw/normalized EOL 的区别。该 141 项与整个 Phase 5 dirty tree 不是同一集合。五个 Phase 5 worker 成果已折入 `Orchestella_phase5_dev`，禁止再逐 worker branch/patch 合入一次。

若来源发生 drift，不静默覆盖或自行重生成旧证据；比较具体路径的新变化，保全最小具名增量，并在合流报告中说明最终选择。出现秘密、损坏来源、未知外发或真实架构冲突时，停止相关操作、保持独立工作推进，按既有门报告具体阻塞。不要把历史 synthetic replay 当当前主线可直接 apply。

## 4. 建立隔离集成树与品质基底

从共同 HEAD 创建新的第一方集成分支，建议 `integration/quality-phase5`。本会话已获用户提交和普通推送授权，所有实现和 Git 写操作只发生在新集成树。原两线保留作为证据。

命令骨架（执行前完成摘要和目标存在性门）：

```bash
MAIN_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev'
INTEGRATION_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella_merge_dev'
QUALITY_FREEZE='D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-phase5-merge-handoff/quality-main'
BASE_HEAD='8d0e8c75d769ecc645ea102b90a304c8657344a1'
QUALITY_PATCH="$QUALITY_FREEZE/quality-main-source.patch"

git -C "$MAIN_ROOT" worktree add -b integration/quality-phase5 "$INTEGRATION_ROOT" "$BASE_HEAD"
git -C "$INTEGRATION_ROOT" apply --check "$QUALITY_PATCH"
git -C "$INTEGRATION_ROOT" apply "$QUALITY_PATCH"
```

`--check` 失败时先诊断目标树/摘要/路径，不改成强制覆盖。以上不是让你在原 dirty 主仓运行 apply。

依 manifest 恢复明确需要的公共 ignored 输入；只复制具名文件，核目标摘要：

- `runtime-inputs/` 的 base cpio、guest_init、proxy_relay、runtime manifest 与 NOTICE。
- `build-inputs/` 的 Image、QEMU ELF、gvproxy ELF、QEMU 输入/许可证/source-offer资料，按 `build-input-manifest.json` 的 owning target 映射恢复。
- 主线 canonical init/shell/provision/generator 源、测试与工具已包含于源码闭包，确认生成投影与 ignored APK resource 的关系。

AGP 会剥离 gvproxy ELF，冻结 source hash 与 APK hash不同是已有记录；必须按 manifest 解释或以实际构建复现，不能要求不正确的 source=package 断言。QEMU 的 keepDebugSymbols 行为另行核对。

ignored docs/tracker 按具名清单复制：本交接及配套 prompt、两个核验报告、quality map/spec 与其相关工单，以及 `docs/research/` 下以下五个已存在文件：

- `2026-10-13-linux-reference-survey.md`。
- `2026-10-13-terminal-reference-survey.md`。
- `2026-10-13-theme-ui-audit.md`。
- `2026-10-13-guest-runtime-audit.md`。
- `2026-10-13-distro-validation-matrix.md`。

相关工单准确入口见现有 map；禁止 `cp -r docs` 或把所有 `.scratch`、external/temp 一并提交。新会话要提交的 ignored 文档可精确 `git add -f <named-file>`，不能强加整个目录。

在新树跑品质基底适用宿主 gate，保存结果，再提交一个独立品质 foundation commit，便于审查 Phase 5 后续差异。若验证失败，先分清环境输入与实际源码失败。禁止通过降低 `-Werror`、禁用测试、增加 lint baseline 或沿用旧 APK 来获得假通过。

## 5. 并行分工与共享热点串行所有权

建议以下独立职责；并行度按可用代理和机器能力取合理值，不让多个代理写同一文件或同时占用手机：

1. **只读来源/集成审查**：复核 manifest、before 分支、最终差异；独立检查品质回退。
2. **显示模块**：RFB codec/input/channel、display relay、presenter/generation gate 与独立测试。共享 DesktopActivity/launcher 的最终接线交协调者。
3. **profiles/identity**：guest feature/profile 源与状态模型，补自定义身份传递/持久化链，验证普通用户和恢复边界。共享 C/generator/entry 最终接线交协调者。
4. **services/data**：loopback editor、临时 forwarding/lease、WebView边界与选集/USB原型；保留未接通能力的 gating，不扩展原型为 production。
5. **AVF/9p**：官方 API shape、双权限、FD/stop/cold recovery与只读9p；不承诺当前 OEM 可运行。

协调者独占 BridgeClient、ExecutionSession、QEMU/AVF App launchers、guest-agent.c、init generator/entry、现有页面共享接线、Gradle/manifest、runtime assets/NOTICE 和 Git 提交。worker 返回精确 paths、before/after、实现与测试结论；只读 reviewer 不修源码。每个 dispatch packet 给出允许读写路径、输出位置、依赖和设备/网络限制。已经进入 canonical 的 Phase 5 worker 成果不能重复移植。

## 6. Phase 5 的文件级移植算法

以 `phase5-source-manifest.json` 的每个 `files[]` entry 为索引，交叉核 `build-lock.json`，不要以 199 个 dirty paths 或整树比较作为新增功能清单。

| baseline 类型 | 正确 base | 移植动作 |
| --- | --- | --- |
| `tracked-HEAD`，32项 | 固定 HEAD 的 owning blob | 核 beforeSha，再对当前集成文件移植 Phase 5 hunks |
| `captured-live-production-dependency`，14项 | `coordinator/dependency-baseline/<owning path>` | 核 beforeSha，做 base/最新品质 ours/Phase 5 canonical theirs 三路分析 |
| `new-Phase5-file`，95项 | 无独立旧文件 | 先查同 path 是否已被品质基底引入；不存在才新增，已存在则比较并合语义 |

对所有 existing 文件：ours=theirs则无需覆盖；ours=base可取 Phase 5增量；theirs=base则保留品质版本；两边均变化时三路逐 hunk。`git merge-file --stdout --diff3` 可产生供审查的合成文本，但没有文本冲突仍需语义审查。输出到具名集成 evidence/temp，不就地覆写来源文件。不要用整份 `git apply --3way` 假设 captured base 来自 Git HEAD。

conflict map 的 130/199、共享117（86相同、31不同）是生产白名单测量口径；品质 patch131和Phase5交付141是不同口径。31不同中15个确定为旧主线副本漂移，保留最新品质；16个为真实交付差异。14 captured中实测3个文本冲突：

- DesktopActivity：最新 startup期限常量，与 Phase5 可选profile启动/真实帧状态轮询合并。
- QemuInstanceLauncher：实例ID/control-only recovery 与 displaySocket/token/ServiceForward 构造及关闭归属合并。
- PortForwardingPresenterTest：保留单份 add/remove 计数 fixture，测试取 union。

其他文件具体取舍按G01–G35处理。生成的 `guest-assets/initramfs/init` 不做整边手选；先合 canonical modules，再生成。

## 7. 必须保留并实审的语义

### 控制通道与统一 guest agent

保留主线唯一 reader、序列化 pending、3900000ms启动预算、首控制事务一次性30000ms settle、普通 reauth/实际事务总5000ms、HELLO→READY→PING→PONG证明、迟到READY归属、固定 stage/causeCode 与失败 transport/reader 有界退休。QEMU前台恢复只退役失败控制通道，保留同一实例VM/终端；AVF不能安全单独重开时继续明确拒绝。

FEATURES1、DISPLAY、CONFIRM-FRAME 和可选服务调用接入这套 transaction/deadline/认证体系。不得新增竞争控制 reader、另一个BridgeClient或无限前后台重试。旧agent/不支持命令明确失败，拒绝扩展后control仍可PING。

guest C 必须同时保留 Main幂等HELLO、固定POWER白名单、ACK后才实际guest poweroff，以及Phase5的部分写/EINTR、feature helper限制/子进程回收、display/profile调用。POWER ACK只是受理，不是guest退出或持久化完成；宿主状态以真实进程退出为准。控制日志不得出现token、请求原文或密码。至少覆盖HOST→guest、stdin/test TCP、实际生产socket适配的共同schema与拒绝边界。

### Linux基线、网络与凭据

保留 native init、私有recovery supervisor、完整输入SHA/大小、空盘creation signature、已有盘不隐式格式化、受管repository事务与vendor/user配置保全、阶段timeout和失败原码/sync、手动recovery不自动provision。可选profile失败不能伪造base ready，也不能销毁基础系统。

保留每实例随机24字符默认密码、旧/导入未知密码不静默重置、弱凭据拒绝、无日志secret。保留private services API、apply-before-save与rollback、关闭后冷启动不重开、loopback默认与unsupported UDP/LAN规则可删除。guest DNS来自active Android network IPv4快照，尊重VPN；QEMU与AVF接线一致。两个QEMU launch分支都必须 `cache=writeback`，不得恢复unsafe。

### 终端与UI

保留persistent VT/UTF8、shell权威OSC133 submitted history、多行/长命令/中文宽度、输入输出空行和对比、Tab/IME/modifier、alternate screen。保留CLEAR显示epoch与HOME+J0精确处理、FOLLOW/READ锚点和累计淘汰行、触摸/选择期间稳定viewport、前台恢复generation guard。旧Phase5复制文件不能回滚这些功能。

保留真实主题与设置持久化、动态真实首页、去强制首次引导、右上三图标居中和CPU/RAM等高。新页面按现有构建主义风格接入主题。TerminalActivity的profile启动回调须先核有效client/session/generation，旧reader回调不能启动新profile或覆盖新状态。

### 桌面、IDE与原型能力

真实ready要求同次guest Wayland/capture_generation、鉴权后的RFB新非纯色帧、当前generation的实际EGL/GL/swap成功及CONFIRM，并在设备上有应用/输入damage证据。CPU-copy路径按CPU-copy标示，host GLES和vsock字节传输不能声称guest GPU/零拷贝。守住像素/边长/16MiB等分配上界与断流资源回收。默认终端，仅支持矩阵明确组合能选择profile。

code-server保留普通用户、0600随机认证配置、exact loopback origin、无危险file/content/mixed/SSL绕过或JSbridge、跨实例cookie/WS/SW边界。临时guest18080与hostloopback映射按owner回收，LAN仅用户明确动作和10min租约，失败/退出/到期停止、无持久autoenable。未验证或未接通的SAF/USB/9p Android provider用明确gating，不能为了UI可点击绕过拒绝。

## 8. 单独修复已核实的身份缺口

该缺口已经确认，旧会话未实施半成品修复：向导保留 InstanceUserConfig，但 LaunchParameters丢失hostname/username/sudo；bootstrap writeSegment传null/null/false；InstanceLifecycle未持久hasSudo。EmbeddedProvisionInput及guest端已有字段支持。

把修复作为独立可审查合流子项：

1. 明确新旧metadata对hostname、username、hasSudo的兼容缺省；既有root/未知旧密码保持原语义，读取不得重新生成密码。
2. 从向导/creator到保存、冷加载、LaunchParameters、QEMU/bootstrap/AVF实际输入逐层传递经现有validator验证的值；保留旧调用签名的兼容路径。
3. 不从页面标签、实例ID或root实测结果猜用户配置；禁止任意shell文本拼接。
4. 原生guest创建/SSH/普通用户profile执行与sudo配置按实际身份验证；profile前置条件不足时显示action-required，不能悄悄使用root替代。
5. 测试冷加载后的配置保留、旧metadata迁移、无sudo选择、非法用户名/主机名拒绝、空/未知密码不重置、profile实际user路径。此处修复后运行定向guest验证，不拉起七族整批长测。

## 9. 统一构建源、运行资源和许可证

主线生产 `namespace/applicationId=io.github.nyzeep.orchestella`，基础versionCode至少300、versionName遵守主线版本政策。Phase5已锁APK是code200/0.2.0-phase5-local，仅历史隔离交付；不要继承其降级参数。`.phase5`与调试签名仅在显式隔离属性下保留，正式debug构建不默认后缀。不要读取/复制release签名材料。

先合 canonical runtime-functions、runtime-entry、provision、shell与feature/display payload，运行 `guest-assets/scripts/sync-guest-init.py` 并通过其 `--check`。最终模板和APK `guest_init`必须相同，七个可选payload来自最终源码，而非旧Phase5生成文本。

合成最终C后实际编译一套Linux AArch64静态guest-agent。复用已验证的NDK clang、musl CRT/libc、Linux UAPI、compiler-rt arithmetic builtins输入，核其provenance。参考Main `quality-20261013/guest-toolchain/`与Phase5 `tools/phase5/build-guest-agent.py`，在新集成外部根输出新binary和build记录；不得改成Android Bionic或给生产构建加host-test/POWER mock宏。

ELF须为ELF64 little-endian AArch64，无PT_INTERP/PT_DYNAMIC，栈不可执行。source/build-input/binary SHA pin按新源码真实更新，不能关闭repack的校验来接受旧包。`repack-guest-agent.py`只替换唯一 `opt/droidlayer/guest-agent`，核另外428项payload/mode保全与确定性压缩；同步runtime-artifacts manifest、NOTICE.GUEST-RUNTIME、NOTICE.PHASE5、QEMU source offer/相关license及desugar GPL+Classpath许可。必要时调整producer的源pin并附来源，而不绕过门。

display relay是Python源码payload，须确认最终payload摘要与source一致；profilectl/inputs/bootstrap/session/probe同理。APK提取核对merged manifest的ID/version/min/target、七runtime hashes、唯一agent、guest_init、七payload、NOTICE和native libs；APK内容核验不替代真实执行。

## 10. Windows环境与适用验证

SDK `D:\AndroidSDK`，JDK `D:\JDK17`，ADB `D:\手机调试与开发相关\platform-tools\adb.exe`。现有W:/O:/P:/Q:/V:映射先读 `subst`；不要重绑占用盘符。新集成源码和Gradle cache/output都采用经检查的空闲短路径；原中文括号路径曾导致GradleWorkerMain加载失败。短路径映射与项目local环境可按既有授权复用，注册表、系统PATH/services/drivers/防火墙或OS虚拟化特性不能作为常规修复。

Gradle8.13历史位于 `V:\gradle-8.13\bin\gradle.bat`，已有依赖cache `V:\gradle-home`。新会话隔离自己的output/project-cache/TEMP；Phase5可复用 `GRADLE_RO_DEP_CACHE`。先核现有输入，offline运行，缺少合法锁定依赖时按固定repository最小下载并记录。Android shell路径使用 `MSYS_NO_PATHCONV=1`；先确认精确任务入口，不照搬P:/Q:或旧debug签名属性。

运行并保存以下适用门的最终结果：

- 主线完整App测试与Phase5新增类的union，不能只跑Phase5的61项选集；测试注册入口同样需合并。
- runtime contract、instance、engine-qemu、engine-avf的check及新能力/生命周期拒绝负例。
- guest基线、HELLO/POWER/control与FEATURES/DISPLAY的进程/协议union；shell/provision与资源producer检查。
- profile完整suite（旧全套24/25后来仅定点通过，必须重新运行完整适用集合），relay/RFB/presenter/native颜色与资源回收。
- services/端口/lease/WebView策略、选集/USB模型、AVF官方shape/FD/stop/cold恢复、Linux只读9p进程安全测试。
- lint、Java严格警告、Android/NDK构建、最终APK内容与license/source闭包。

常规Gradle入口可从当前build定义核出，例如完整App `:app:testDebugUnitTest`、lint `:app:lintDebug`、runtime各模块`:check`和`:app:assembleDebug`。Phase5的runPhase5JvmAppTests仅为补充，不能当完整union。Linux-only进程测试使用既有明确WSL环境，不安装系统包或修改WSL配置来隐藏缺项；平台不具备则记录NOT_RUN与影响。

历史品质App628、guest73（跳过2）、lint0错误/669警告只说明冻结品质版本。历史Phase5 App61、wire18、relay15、POSIXcontrol50/865 assertions、data26、AVF19与9p合成结果仅作回归参照，不累计成新测试总数。最终记录此次实际运行的suite、tests/failures/errors/skips、exit code和源码/APK摘要。通过后避免无理由重复全套；新变化/失败/未决关切才触发额外测试。

## 11. 定向真机验证与未运行边界

历史设备为荣耀MEY-AN00、Android16/API36，实际主线走QEMU。先重新列设备和前台运行状态，确认没有其它会话占用。用户已有此设备APK安装/调试授权；当前整合包与安全系统确认按该授权操作，不重设安全策略。Phase5历史 `REQUESTED_NOT_GRANTED`是当时独立`.phase5`会话请求，不能伪装成旧设备验收PASS，也不能沿用旧grant/serial执行其专用脚本。

需要的设备回归只针对合流影响：新APK身份/签名兼容，基础native init/读写/网络仍工作，终端clear/长输出阅读/命令高亮/重入，authenticated POWER后真实退出与冷文件保留，active网络DNS和loopback规则关闭/恢复。选择一个具备资源条件的已支持profile验证真实labwc显示与输入或普通用户开发服务；保存真实证据并关闭该批自己创建的临时服务/forward。不要恢复已经取消的七族任务。

安装若失败，如实记录。该手机曾 `adb install -r` 返回INSTALL_FAILED_ABORTED且没有可见提示，后按明确APK推 `/data/local/tmp/` 再 `pm install -r --user 0 <apk>`成功；可在既有授权内采用此方式，不能绕过系统确认、清App数据、改变权限或卸载带实例的主应用。安装后按需求force-stop/启动使新代码生效前，确认当前VM退出与目标App归属，不能杀其它会话进程。

因屏幕确认、独占设备、平台权限或运行环境受阻时，完成所有独立源码/构建/测试/报告，精确记录具体NOT_RUN和阻塞，不能伪造PASS。新声明支持的桌面/IDE若缺生产证据，保持experimental/未验证gating。GPU/zero-copy、OEM AVF、Android9p/USB/SAF等未实现或未实跑分别标注，不将QEMU基础PASS移用为其它策略PASS。

## 12. 独立review、提交、普通push和最终交付

至少两条独立审查轴：

- **品质保全/协议生命周期**：Main最新clear/scroll/IME/主题/布局、启动/恢复/POWER、DNS/forward/writeback/trust/identity以及最终测试union。
- **Phase5数据与产物边界**：真实readiness、display分配/FD/线程、profile普通用户/取消/配置保全、WebView/origin/lease、AVF/9p、原型拒绝、static producer/许可证/源码对应关系。

reviewer审最终diff与新证据，不能只看某一旧分支或宿主fixture。修复实际阻断问题并补必要回归；不得用“零文本冲突”代替审查。

按阶段创建可审查commit（品质foundation、Phase5模块/共享整合、身份链、统一producer/资源、必要修复/报告，可合理合并小提交）。stage使用精确paths或经审查路径列表，禁止对ignored资产整目录force-add。若公共binary按既有仓库资产策略存外部/release而不入Git，提交其对应producer、lock、NOTICE/source-offer与可复现输入引用；不能提交只在本机工作且无构建输入说明的源码。

第一方预期origin：

```text
git@github.com:Nyzeep/Orchestella-Studio.git
```

执行前核实际remote URL归属与push目标、待推提交/path集合，限定第一方项目。使用新集成分支普通push（例如核定后 `git push -u origin integration/quality-phase5`），不force-push、不自动覆盖main、不发布release、不向参考仓写入。无需为已明确授权的普通push再次泛泛确认；如果实际endpoint/ownership不符合指定第一方，停止此步并报告具体阻塞。若普通push发生non-fast-forward，先fetch并分析隔离分支状态，不能强推或reset原工作树。认证失败则保留本地提交，报告实际错误与待用户操作，禁止查找/输出凭据或修改安全策略。

本次canonical输出建议：

```text
docs/reports/orchestration/quality-phase5-integration.zh-CN.md
docs/reports/orchestration/quality-phase5-integration-verification.zh-CN.md
docs/reports/reviews/quality-phase5-integration/review-report.zh-CN.md
Orchestella/artifacts/quality-phase5-integration/ 下具名 lock、摘要、APK与脱敏证据
```

报告链接既有handoff/地图/研究，不复制旧实现报告；明确本次源码、运行包与APK对应SHA，失败历史、已验证/NOT_RUN/未实现状态及临时资源终态。对stage后的秘密扫描使用路径/字段约束和合成测试，不把真实密码或完整rootfs导出当诊断。

最终回复必须自包含，给出：完成的合流行为与解决的关键回退；实际集成工作树、分支、commit及普通push结果；新APK路径/ID/version/大小/SHA；实际测试与设备结果；保留未运行/实验功能矩阵；报告/源码出处链接；自己的临时进程/forward/lease终态。没有完成推送时明确“本地已提交，推送失败/未执行”及原因，不能含糊称远端完成。

达到这些结果后结束新会话，不把未实现Phase5愿景或已停止七族长测作为尾部承诺。
