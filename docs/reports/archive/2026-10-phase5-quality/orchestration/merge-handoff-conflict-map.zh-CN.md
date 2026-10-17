# 两线合流冲突地图与安全顺序

日期：2026-10-13。性质：只读源码核验与下一会话交接；本文不是完成合并或设备验收的证明。

本会话只写本报告，没有执行 merge、apply、stage、commit、push、构建、设备操作或远程访问。下一会话的合并、提交、推送授权由用户的交接 prompt 给出，仅针对第一方项目。当前两条线均须保留。

## 结论与边界

**以最新 Main 品质修复为 foundation，仅移植 Phase5 的真实增量。** 两仓 HEAD 相同，开发成果主要在 dirty working tree 和 untracked/ignored 源码中，普通 `git merge` 不会合入这些成果。Phase5 曾逐文件复制 Main 的早期未提交成果；它的全部工作树不能作为最新 Main 的替代品，交付 patch 也不能未经当前基线核对就整体覆盖。

- Main：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev`，分支 `main`。
- Phase5：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`，分支 `phase5/graphics-productivity-storage`。
- 共同 HEAD：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- Phase5 artifact 根：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013`，下文简称 `P5_ART`。
- 唯一本文输出：Main 的 `docs/reports/orchestration/merge-handoff-conflict-map.zh-CN.md`。

已读两线 AGENTS、DEFENSIVE_CONSTRAINTS 下全部三份 policy、HOST_ENVIRONMENT_POLICY、ARTIFACT_ORGANIZATION_POLICY，Main quality spec/report、Phase5 spec/integration guide/verification matrix。Host/Artifact 历史文档仍写 DroidLayer 路径；本任务使用用户明确指定的 Orchestella roots。读取是本地源码/测试/脱敏交付元数据，未读取凭据、用户实例盘或根 raw cache。另一审查负责 Phase5 artifact 完整性；本报告没有重复哈希全部 141 个 delivery input。

## 改动分类与测量口径

统计来自各仓 `git diff HEAD --name-only -z` 的 tracked 改动，加 `git ls-files --others --exclude-standard -z` 中生产白名单的新文件。白名单为 `app/src/main/`、`app/src/test/`、`runtime/`、`guest-assets/`、`tools/`，另纳入构建入口 `app/build.gradle.kts`；排除 build、cache、temp、__pycache__、凭据与原始缓存。下面是源码范围的汇总，不是整仓文件清单，也不包含 ignored runtime 大二进制。

| 度量 | Main | Phase5 |
| --- | ---: | ---: |
| tracked dirty 全部路径 | 76 | 104 |
| 白名单 tracked dirty | 75 | 103 |
| 白名单 untracked 新文件 | 55 | 96 |
| 白名单改动总路径 | 130 | 199 |
| 仅一侧改动的路径 | 13 | 82 |

两侧共有 117 个改动路径：86 个当前原始字节相同；31 个不同。31 个不同进一步分为：

- **15 个复制基线漂移**：不在 Phase5 delivery `files` 的增量名单中，且已核对 Phase5 当前文件全部等于 `coordinator/dependency-baseline/<owning path>`，最新 Main 已继续变化。保留 Main，不把旧副本当 Phase5 feature。
- **16 个实际交付差异**：14 个 `captured-live-production-dependency` 文件，以及以 tracked HEAD 为 base 的 `app/build.gradle.kts`、`QemuLaunchSpec.java`。

86 个相同文件不需为文本合并再作选择；仍要按最终引用关系运行测试。82 个 Phase5-only 路径表示 Main 的改动集合里没有该路径，不等于全部都是新文件或彼此独立：其中包括修改 Main 原先未改的旧文件，例如 manifest/native/AVF。不要整树复制这 199 个路径。

协调会话已冻结 Main 的质量交付闭包（131 路径），其包含范围与本节 130 个生产白名单路径的口径不同。精确入口如下，由协调会话给出；本子未重新哈希该完整快照：

- 冻结目录：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-phase5-merge-handoff/quality-main/`。
- safe patch：该目录 `quality-main-source.patch`，1446461 bytes，SHA-256 `bf95bab41a34e9ae5ccbc8dbd7d43b2a80ae32b6846b29f47da0cfb766719174`。
- 同目录 `quality-main-freeze-manifest.json`、`freeze-verification.json`：协调会话确认131路径集合匹配、all/source hash unchanged；仅解析patch，**未在新工作树apply**。runtime输入的精确摘要/来源以该manifest及其`RuntimeInputs`引用为准，不猜摘要前缀。
- canonical交接文档：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev/docs/agent-handoff/QUALITY_PHASE5_MERGE_HANDOFF.zh-CN.md`；新会话完整prompt：同目录 `QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md`。

集成消费这些精确冻结入口与完整SHA，不把本文计数或来源校验当patchapply/最终产品验收。

## Phase5 的正确 base 如何定位

权威增量索引是 `P5_ART/delivery/phase5-source-manifest.json` 的 `files[]`，交叉参照 `delivery/build-lock.json` 的 `sourceClosure`。它记录 owning `path`、`baseline`、`beforeSha256`、`afterSha256` 与规范化摘要。交付 patch 是 `P5_ART/delivery/phase5-integration.patch`。依赖来源记录是：

- `P5_ART/coordinator/evidence/production-dependency-baseline.json`：明确复制的生产闭包；capturedAt 为 `2026-10-13T19:05:44.835695`。
- `P5_ART/coordinator/evidence/dependency-test-baseline.json`：复制的测试闭包。
- `P5_ART/coordinator/evidence/forwarding-dependency-refresh.json`：后来转发依赖的单文件更新来源。
- **before 原始文件目录**：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/coordinator/dependency-baseline/`，在该目录下拼接 manifest 的完整 owning `path`。例如 BridgeClient 的 base 是该目录下 `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/BridgeClient.java`。

对每个 manifest entry 按以下规则处理：

1. `captured-live-production-dependency`：读上述 before 文件，要求 SHA-256 等于 entry 的 `beforeSha256`。本次 14 个 captured 文件均核对匹配。不要以共同 HEAD 替代这份包含早期 Main 品质修复的 before。
2. `tracked-HEAD`：base 是 `git show 8d0e8c75d769ecc645ea102b90a304c8657344a1:<path>` 的字节；再次核 entry 的 before digest/EOL 约定。不要把 captured 文件也归到这个分支。
3. `new-Phase5-file`：先查集成树是否已存在同 owning path。不存在可新增；存在须比较来源并合并，不可用“new”覆盖 Main 的 untracked 新源码或新测试。
4. 规范化 after SHA 一致、合成 forward replay 通过，只证明该交付重现规则；不证明当前 Main 能直接 `git apply`、不证明合并后清树、编译或设备运行。

14 个 captured 文件对应下表 G01、G02、G03、G04、G05、G09、G10、G13、G16、G18、G19、G20、G26（ImeBridgeInputConnection）、G34（PortForwardingPresenterTest）。每项必须保留其 Main 变化与 Phase5 增量，不能按整文件任取一侧。

## 文件组地图

路径前缀均相对各自仓根：`A = app/src/main/java/io/github/nyzeep/orchestella`；`T = app/src/test/java/io/github/nyzeep/orchestella`；`C = runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract`；`Q = runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu`；`V = runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf`；`I = runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance`。这些前缀仅缩短表格，owning path 必须完整用于 beforeSHA 查找。

| ID / 路径组 | Main 必须保留 | Phase5 应移入的增量 | 风险 / 合并方法 | 合并后验收 |
| --- | --- | --- | --- | --- |
| G01 `C/BridgeClient.java` | 单 reader；3900000ms 启动预算；首事务 30s settle；每次命令 HELLO+PING 鉴权屏障；失败 causeCode、旧 reader 退休 | FEATURES 1 白名单、CONFIRM-FRAME 32hex generation、4096 字节 feature 响应分类 | 0 文本冲突仍须审 exchange/classify/pending。扩展全部经过 Main 同一 exchange/deadline，不能绕过 reauth | 重复/迟到 READY、agent 重启、失败后恢复、旧 agent 拒绝扩展后 PING、feature 请求互斥与无 token 日志 |
| G02 `guest-assets/agent/guest-agent.c` | v1 HELLO 幂等；新的 POWER 白名单；先成功 ACK 再 guest poweroff，宿主不执行关机 | 部分写/EINTR；FEATURES helper 3s 限额；DISPLAY 实际 profile 调用；拒绝扩展不终止控制；helper FD/SIGCHLD 回收 | 0 文本冲突但 Phase5 C 无最新 POWER。合成一次 schema 后重编 ARM；不可任挑两侧 ELF | POSIX/stdin/TCP 双路径、HELLO/POWER/RESIZE/DISPLAY/FEATURES 全协议 union 与故障回收 |
| G03 `A/ExecutionSession.java` | `recoverControlChannel()`：只退役失败控制，保持 VM/终端 | display connect/reconnect/status、capabilityMatrix、ServiceForward 接口 | 默认方法 union；接口签名与两种 adapter 同时修回 | terminal/control/display 可独立退出；旧 adapter 确定性不支持服务 |
| G04 `A/QemuInstanceLauncher.java` | instanceId；controlClosed/锁；reader join 在锁外；活终端复入恢复；guestDnsFor；主 NAT 生命周期 | filesystem displaySocket/token；独立 RFB；临时 guest18080 回环转发及回收；2D 能力诚实 | 1 文本冲突：Session 构造/参数。串行整合 instanceId、proxy、displaySocket、token；close 同时关闭正确 owned resources | 停止/重连/终端存活、无第二 reader、Private DNS、端口建立/失败撤销/stop、display FD 独立 |
| G05 `A/AvfInstanceLauncher.java` | 控制不可安全单独恢复时返回 false；guestDnsFor 的 networkSpec | token 鉴权 vsock9103 显示；display close/status；按实际 session 报能力 | 0 文本冲突，需检查接口与关闭归属；不得用 closeChannels 重置活终端冒充 control recovery | 官方 API shape、stop/late FD/persistent 配置；AVF 服务转发仍明确不支持 |
| G06 `A/QemuRuntimeBootstrapper.java` | 最新 1MiB 有界 resource 读取；manifest 根镜像大小/SHA；装配后发布规则 | 此文件 Phase5 是原复制 base，没有 feature 增量 | **直接保留 Main**，旧 readAllBytes 不回归 | 超限/短读/缺输入/错 SHA 失败；新 guest_init 通过原 trust 链 |
| G07 `Q/EmbeddedProvisionInput.java` | 独立锁定 rootfs SHA/size、metadata 注入、流式复验、随机凭据；旧无 digest 调用失败即停 | 两线当前同字节；不需更换 | 不把新的可选 payload 变成替代 rootfs 信任输入 | rootfs.file/size/sha256 非空且独立来源；截断/超长/错 digest 拒绝 |
| G08 `A/FirstBootProvisionPipeline.java`、`GuestRuntimeProvisioner.java`、`DistroMirrorProvisioner.java`、`I/InstanceUserConfig.java` | 原生 init/实际基础包、随机/未知旧凭据政策、发行版源配置与真实 ready | 管线/信任源以 Main 为准；feature profile 独立挂接 | FirstBootPipeline 两线同字节。不能将 optional installed 当基础 ready 或反向破坏已有用户配置 | 七族基础配置/原生服务、弱凭据拒绝、已有盘不格式化、失败可重试 |
| G09 `guest-assets/initramfs/init` | Main 最新 runtime、仓源 ownership、恢复/Bash、输入 heartbeat/超时 | 可选 payload 安装模块及 relay/profile 脚本 | 本文件大量内容是生成投影；不逐份维护旧 embedded block | 从 canonical 模块重新生成；生成内容/APK resource 相同；安装 optional 失败基础 Linux 保留 |
| G10 `guest-assets/initramfs/runtime-entry.sh` | Main create/restart 路径的 owned 仓源 staging；不覆盖 vendor/user repo | `install_guest_optional_features` 与失败日志 | 0 文本冲突，保两类调用，不把 optional 失败接入全局 fail/ready | 新装与 cold restart 均维护仓源；feature payload fault 不阻断基础启动 |
| G11 `guest-assets/initramfs/runtime-functions.sh` | owned-v1 仓源 SHA/GPG/TLS、checksum 1800s heartbeat、2h firstboot、manual recovery 不自动 provision、ELF Bash 探针 | Phase5 文件等于旧 copied base，无增量 | **保留 Main**；由 G13 embed 新 optional 函数，而非换掉模块 | vendor/user 配置保留、manual 模式、损坏 init、TCG 长事务与期限 |
| G12 `guest-assets/scripts/orchestella-guest-provision.sh` | 失败状态 sync、operation exit/timeout、真实最后步骤、managed repo scope、DNF/zypp 针对性修复 | Phase5 文件等于旧 copied base，无增量 | **保留 Main**；optional installer 自有状态机，不替代 base provision | 原退出码/失败持久化、离线/坏签名/CA、七族 native init 与包/服务真验证 |
| G13 `guest-assets/scripts/sync-guest-init.py` | shell hook/prompt、最新 functions/provision/entry canonical source → init 与 APK `guest_init` | 六 profile 输入 + display relay，hash delimiter、.next/chmod/mv | 0 文本冲突；先合 source 再运行 producer；旧 Phase5 生成 init 不能作为最终 source | `--check`；七 optional payload 与最终 APK source 对齐，NOTICE/manifest 同时更新 |
| G14 `guest-assets/shell/20-orchestella-terminal-integration.sh`、prompt/env | 明确 Bash markers、真实 history、高亮分段、已有用户 policy；CLEAR 不删除 shell history | 本组以 Main 品质为基底；不从 feature 模块重写 shell/init | feature 会话普通用户与既有 terminal session 分开 | CJK/emoji/长命令、隐密/history 排除、alt screen、clear/history/log 分界 |
| G15 `A/terminal/TerminalScreenModel.java`、`ShellCommandTranscript.java`、`ModernTerminalView.java`、`MobileAuxiliaryKeyBar.java` | 最新 clear display interval、scroll anchor/follow、输出追加与 IME/尺寸行为、可见 CLEAR | 已核的 Phase5 文件是旧 copied base，无 feature 增量 | **保留 Main**；同名“new”源码不得用旧 Phase5 副本覆写 | 阅读历史时不跳、清屏重建显示区间、alternate screen、重连/UTF8 |
| G16 `A/TerminalActivity.java` | 前台/绑定 generation、expectedClient guard、仅显式 onStart 恢复、实例ID | 鉴权成功后启动用户选定 profile | 0 文本冲突但旧 onReady 增量在 guard 前启动功能。移植时使用当前有效 client/session 回调语义 | 旧 reader 回调不启动 profile/不更新新 UI；退出页面 VM 仍运行；单次选择幂等 |
| G17 `A/MainActivity.kt` | AVF“预检通过”而非“可用”；启动 foreground service 保活；真实首页/终端入口 | Phase5 文件等于 copied base，未增加首页 feature 增量 | **保留 Main**；新工具入口采用 G19/G33；不全局强开旧桌面按钮 | 新旧实例终端可达、App 返回/后台保活、无假硬件/图形能力 |
| G18 `A/SetupWizardActivity.kt` | Main 共享目录尚未接通的禁用说明、创建流程/真实性 | 默认终端，可选 Debian labwc、Debian/Ubuntu Podman/editor；双 AVF 权限说明；保存选择 | 0 文本冲突；只并 choices/bind/selection hunks，不能重新打开未 production 的 SAF 开关 | 默认不下载桌面；未知 distro/profile 不可选；USB/共享不冒充可用 |
| G19 `A/InstanceDetailActivity.kt` | 状态/电源/终端、busy 与生命周期/随机 SSH | “桌面与开发工具”导航到 GuestFeaturesActivity | 0 文本冲突但语义需更新：可打开安装/状态页，不等于可直接启动桌面 | 同一实例/真实 session、failed/installing/awaiting-frame 正确展示 |
| G20 `A/DesktopActivity.java` | 最新 `DEFAULT_STARTUP_TIMEOUT_MS`，唯一控制 client 的启动门 | 真实 STATUS 轮询、capture generation、present observer/CONFIRM-FRAME、重连、CPU copy HUD | 1 文本冲突：awaitReady 与 launch/status 逻辑。保 Main 期限常量，保 Phase5 数据面证据；不能退回旧 template | capture → token/RFB → 成功 GL present 三段证据；新非纯色帧、输入 damage/重建才可确认 |
| G21 `app/src/main/res/layout/activity_main.xml` 与现有 UI XML/theme | Main 最新布局/真实数据/构建主义主题，清理假入口；Phase5 多为相同或旧副本 | optional 页面动态控件与真实状态，必要新增资源 | activity_main 是 copied drift；不批量复制 res，资源ID与主题逐处查 | 首页、setup、detail、terminal、editor/desktop 的主题/布局与状态一致 |
| G22 `app/build.gradle.kts`、runtime contract/instance build | Main versionCode=300、versionName=0.3.0-preview.1、全部现有质量/安全测试 | native staging、2.1.5 core-library desugar/许可、Phase5 新测试注册 | 正式 `namespace/applicationId=io.github.nyzeep.orchestella`；`.phase5` 仅隔离 QA 条件，不能继承发布构建参数 | full JVM/runtime/lint/native build；检查 merged manifest/APK ID 与版本；-Werror/serialVersionUID 不降级 |
| G23 `app/src/main/AndroidManifest.xml` | 主 application/组件安全属性、正式第一方身份 | USE_CUSTOM_VIRTUAL_MACHINE；新 feature/editor Activity exported=false、editor singleTask | HEAD-base 增量；权限声明不是平台/OEM授权或成功运行 | 双权限负例/回退、Activity 不外部导出、正式 APK 不安装为 QA id |
| G24 `Q/QemuLaunchSpec.java` | **两个 build 分支 cache=writeback**，不得恢复 cache=unsafe | withDisplayChannel：私有 socket 路径限额、独立 virtserialport | HEAD-base 逐 hunk 合；0 文本冲突也需显式保 durability | init/root盘冷恢复一致性、显示socket参数；错误路径/缺virtio-serial拒绝 |
| G25 `C/display/*`、`C/WaylandChannel.java`、`A/QemuWaylandChannel.java` | 现有控制鉴权与策略中性契约 | token 版本握手、RFB3.8 Raw/DesktopSize、4MP/16MiB、真实帧、有界队列/输入/重连 | 新深模块先加；旧 Qemu template/空 input 不能保留为 ready fallback | stream fixture/负例、超限不分配、断流、拒绝后 control PING、QEMU/AVF adapter 各自连接 |
| G26 `A/ImeBridgeInputConnection.java`、`DesktopKeyMapper.java`、触摸输入 | 既有终端 IME/修饰键语义 | 显示协议实际 keyboard/ASCII 字节映射；鼠标/触控板回退 | Ime 为 captured 增量；Unicode、触屏与 USB 各自分级，不用 clipboard 绕过未验证能力 | 按键/ASCII 编码与 capability 负例；CJK/emoji Android 未测保持未验证 |
| G27 `A/DoubleBufferedRenderer.java`、native presenter/surface/controller、`DesktopPresentationGate.java` | 真正失败不宣称成功的资源/线程边界 | pitch/ARGB-RGBA、EGL/GL/swap bool、context rebuild、真实最新帧重播、generation 门 | 合并实际 presenter 返回语义，不能将 host GLES 上屏升级成 guest GPU/零拷贝 | color/alpha native 回归、Surface/rotation/context loss、当前generation新非uniform帧成功 present |
| G28 `V/AvfVirtualMachineApi.java`、`ReflectiveAvfVirtualMachineApi.java`、`AvfVirtualMachineEngine.java`、exception | Main adapter/DNS/活终端保持规则 | 官方 Builder/Disk/create/run/get/getConfig；双权限、长vsock、锁外stop/cancel、lateFD cleanup、持久配置严格复用 | Phase5 改 Main 原本未改源码；与 G03/G05 contract 联动。serialUID/-Werror 保持 | shape + blocked-connect stop + late FD + failed-run retry + persistent mismatch；真 OEM AVF 单列 |
| G29 `V/Avf9pShareServer.java`、相关 Linux-process tests | 只读/授权范围，不扩大 host workspace 读取 | 标准 9p framing/tag/opcode、secure-relative dir FD、fid/session回收 | 不具备 Android SecureDirectoryStream 时 ENOSYS 拒绝；不能因 JVM provider 成功 claim mount | 拒写/路径/symlink/race/fd循环；Android provider 与真实 guest mount 待新证据 |
| G30 `C/services/*`、`A/LocalEditorActivity.kt`、service adapters | Main gvproxy 私有 services API、apply-before-save、撤销/冷启动不重开、Private DNS | 精确 loopback origin、无JSbridge/危险 file/content/mixed/SSL绕过、临时转发、显式10min LAN lease、认证/health/stop | 独立服务模块先加，G04 动态规则不持久；WebView 路由/WS/SW 与跨实例隔离须新验 | 真实TCP绑定/冲突/撤销/到期/crash；WebView login/WS/SW/导航/旋转/后台与 cookie 隔离 |
| G31 `C` 的 storage/USB 探针与模型模块 | 默认拒绝宿主扫描/任意共享，Main 未接通开关保持不可用 | 精确Downloads选集/SourceProof/quota/hash/catalog；USB FD 生命周期原型 | 可以新增源码/测试，不 production 接线无实现或 bypass guard；不把模型当USB透传 | 合成拒绝/取消/热拔出/断流；实际 SAF heldFD/provider/VMM 未运行分别标记 |
| G32 `guest-assets/features/*`、`guest-assets/display/display-relay.py` | base init/firstboot、随机凭据、根盘/用户配置保留 | labwc/headless/pixman/wayvnc；Podman探针；DockerCLI独立；code-server固定输入/0600随机secret/owned units-v2 | 模块先入；安装受理≠ready；普通用户路径、锁/取消、staging回收、配置保全同闭包 | 完整 profile suite + ENOSPC/取消/弱secret/upgrade/race；ARM64 各profile 新验证 |
| G33 `I` feature model、`A/GuestFeatureSelections.java`/`GuestFeaturesActivity.kt` | 既有实例/终端状态模型不回退 | 白名单可选profile/选集、安装与启动阶段、功能入口与失败/重试 | 新模块先加，UI stage 与 G01/G02/G32 JSON schema 一致，真实运行证据再 ready | planned/installing/installed/starting/awaiting-frame/ready/failed/cancelled 与错误匹配 |
| G34 `T/PortForwardingPresenterTest.java` 与两线测试源/注册入口 | Main 新 NAT/DNS/terminal/POWER/provision/reader恢复测试 | Phase5 RFB/frame/profile/services/AVF/9p 测试与注册 | Port test 1 hunk 是 addCalls/removeCalls 两侧重复计数器与格式冲突；采用 Main 单份实现。全部新增测试取 union，旧复制测试不覆盖新断言 | full suite，非仅 runPhase5JvmAppTests 的61项；禁止 @Ignore/disable gate/临时exclude 规避失败 |
| G35 `tools/phase5/*`、repack producer、runtime assets/NOTICE/source offer | Main 最新 C 源/源摘要/运行包校验，其他428项条目与mode保全，GPL供应链与 source offer | guest-agent producer、APK校验/七输入、Phase5 NOTICE/Classpath许可、锁与 provenance | 源码合法合成后只生成一套ARM ELF/bundle/manifest/NOTICE；忽略资源显式列来源。不能挑任一旧包或整archive | ELF无动态解释器、源/agent/build输入/包SHA对应；APK提取核验；许可证与最终源码闭包匹配 |

## 已实测的文本冲突与语义审查

只读运行了 `git merge-file --stdout --diff3 <current Main> <captured before> <current Phase5>`，stdout 仅留在进程内，不写 working tree；14 个 captured 文件之前均核 before SHA。结果为 **3 个文件、各 1 个文本冲突 hunk**：

1. **DesktopActivity（G20）**：Main 把 `awaitReady(900_000L)` 升为 `BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS`；Phase5 修改其后 launch/status/display 接线并仍携带旧期限。保新常量，合入 Phase5 120s optional-start 轮询/实际帧确认。这两个期限服务不同阶段，不应互相替代。
2. **QemuInstanceLauncher（G04）**：Main 的 instanceId/控制失败恢复与 Phase5 的 displaySocket/token/ServiceForward 叠在 Session 构造和 launch 参数上。保 Main 的身份、controlClosed 与锁外reader退休；新增独立 display 参数/owned service 回收。不能退回旧三参数构造导致恢复上下文丢失。
3. **PortForwardingPresenterTest（G34）**：两侧都新增 `addCalls/removeCalls`，removeRule 一侧换行、一侧单行；是重复测试 fixture/格式 hunk。保单份计数，保 Main 字节风格；另行 union 新测试，避免重复加计数器。

其余 11 个 captured 文件本次合成 rc=0。**这不是全 patch 可无冲突的结论**：未把全部 tracked-HEAD/new-file/ignored resource 做三方合并，也未执行当前集成树 `git apply --check`、编译、测试或设备验证。

G01/G02 的机械无冲突尤其不能免审。Phase5 是在早期 single-reader 接口上增 feature methods，Main 后来加入控制重认证/失败退休；扩展必须走最终 Main 的 exchange。Phase5 guest-agent C/ELF 缺 Main 新 POWER，源码 union 后才能证明 HELLO、POWER、RESIZE、DISPLAY、FEATURES 与响应 JSON 是同一版本的实现。新 exception 与接口必须通过原 Java `-Werror` 和序列化警告门，不用降低编译门换取“成功”。

## 必须固化的一套 integrated schema

合并不能只追求 Java 编译。至少共同固定以下字段/状态和生命周期：

- 控制 v1：per-VM 64 小写hex token；HELLO重复合法、错token拒绝；一个 pending/reader；startup、settle、普通命令期限分别有界；reauth HELLO 后 PING/PONG 屏障；固定 `causeCode/stage`，不得日志输出token或外部异常原文。
- POWER：仅既有 shutdown/poweroff；成功 ACK 后才触发 guest utility。不能把旧无 POWER ELF 当最终 agent，也不能把 ACK 当磁盘已持久化/guest 已退出的证明。
- FEATURES 1：action/profile白名单；CONFIRM-FRAME 32hex generation；response JSON 与 feature model/UI 对齐；3s短helper与后台安装任务分开；旧agent/unsupported/error后control可继续PING。
- DISPLAY：MODE/RESIZE 与新 codec 的4096边长、4194304像素、16MiB上界一致；FPS未实现返回拒绝；显示独立token stream，QEMU private filesystem socket和AVF vsock9103各自管理FD，不占control reader。
- readiness三段：guest真实Wayland globals/grim capture和本次capture_generation → token鉴权后的真实RFB新帧 → 当前generation/尺寸/时间的新非纯色帧成功EGL/GL/swap并CONFIRM。安装、socket连接、旧模板帧、host GLES、旧单测均不足以写desktop ready；还需设备上应用/输入造成真实damage。
- 服务：guest loopback8080与临时guestNIC18080/host loopback映射分开；EXPOSE/UNEXPOSE终态与host rule owner共同回收；明确用户动作LAN18081的10min租约，失败/崩溃/退出/到期停止，不持久autoenable。WebView exact-origin/导航/WS/SW/cookie边界必须在合并后验。
- 基础与optional状态互不混用：base ready/verified仍由原生init、基础包、服务、SHA链保证；profile installed/starting/awaiting-frame/ready不能替代base完成标记。SAF/USB/GPU/零拷贝仍按实现与真验证分级。

发现“脚本已安装”“权限预检”“helper受理”“template/frame fixture”与实际能力表述不一致时，以生产实现和实测证据为准，更新 gating/文案而非降低安全门。合法合并源码后仍不 claim device pass。

## 下一会话实际合流顺序

下列是交接方案，**本会话没有执行这些步骤**。

1. 核两原仓 HEAD/分支与主冻结快照；确认 Main frozen manifest（协调会话131路径闭包）、safe patch完整 SHA、精确 producer/source offer入口。保留两 dirty trees 与原artifact，不clean/reset/stash原仓。只归档明确源码闭包；禁止整个 roots/rawcache/`.pw.private`、根盘、签名或凭据入快照。
2. 在批准项目root内创建干净 integration worktree，从 `8d0e8c75d769ecc645ea102b90a304c8657344a1` 建新的第一方集成分支。先 `git apply --check` 主冻结safe patch，再 `git apply`。随后按Main manifest补齐明确 ignored canonical源码/producer所需资源，对其来源/摘要核验。
3. 新会话具有用户明确授权后才stage/commit Main质量基底，作为可审计integration foundation。当前任务只有生成prompt，不在本会话做这些Git写操作。
4. 以Phase5 manifest而非199路径dirtytree作为增量索引。先新增独立display、services、feature/model/profile模块及其测试；已有同名文件拒绝覆写。再移植Main原本未改的HEAD-base文件，逐文件检查仍是该HEAD字节。新的namespace/QA构建参数最后按正式目标核验。
5. 14个captured文件用精确before做三方逐hunk整合。先G03/G01接口，串行G04/G05、G02与G32/G25协议；再G09/G10/G13、G16/G18/G19/G20。对于G09等生成投影，合canonical source后重新生成，不保留两套embedded文本。
6. 保留15个copied drift的最新Main版本；对G22/G24保Main版本号、writeback及Phase5新增必要能力。合所有测试的union与注册入口；不要套旧测试副本覆盖Main已新增负例。
7. 最终源码定版后，按固定NDK/musl/compiler-rt输入重编一次AArch64静态guest-agent；repack保留其他428项byte/mode，重新生成canonical guest_init/最终bundle/runtime-artifacts manifest，补NOTICE和source offer。不能使用“拷贝两边binary任选”的方式。
8. full suite、脚本/协议进程/native/安全门通过后再build正式第一方ID的候选APK；提取APK核各runtime/payload/NOTICE/sourceSHA。按实际设备独占状态进行合流直接影响的定向回归：基础Linux、终端/clear/scroll/highlight、POWER/冷恢复/持久化、NAT/PrivateDNS及已声明支持的Phase5 profile/显示/服务。**用户已停止七发行版长测，此步骤不恢复基础七族整批验收。** 基础历史验收只能标原版本已验，不能自动继承到合并包。
9. 汇总变化、失败历史、NOT_RUN与残余限制，独立review最终source/artifact；按用户新会话授权提交并推**第一方**项目。先核远端归属，参考仓库上游没有推送授权。未验证能力不能随合并升级文案或发布claim。

操作骨架（精确路径来自冻结交付；不能猜取“最新文件”。创建目录/分支前先查是否已存在）：

```bash
# 仅新会话、已获合并授权后执行；不要在原dirty工作树apply。
set -e
MAIN_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev'
INTEGRATION_ROOT='D:/Development project(开发项目)/Orchestella开发目录/Orchestella_merge_dev'
BASE_HEAD='8d0e8c75d769ecc645ea102b90a304c8657344a1'
QUALITY_FREEZE='D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-phase5-merge-handoff/quality-main'
QUALITY_PATCH="$QUALITY_FREEZE/quality-main-source.patch"
QUALITY_MANIFEST="$QUALITY_FREEZE/quality-main-freeze-manifest.json"
# 不匹配即停；manifest/runtime inputs也须按冻结验证规则核对。
QUALITY_HASH_OUTPUT="$(sha256sum "$QUALITY_PATCH")"
[ "${QUALITY_HASH_OUTPUT%% *}" = 'bf95bab41a34e9ae5ccbc8dbd7d43b2a80ae32b6846b29f47da0cfb766719174' ]
git -C "$MAIN_ROOT" worktree add -b integration/quality-phase5 "$INTEGRATION_ROOT" "$BASE_HEAD"
git -C "$INTEGRATION_ROOT" apply --check "$QUALITY_PATCH"
git -C "$INTEGRATION_ROOT" apply "$QUALITY_PATCH"
# 补齐QUALITY_MANIFEST明确批准的ignored源码/producer输入；核manifest；按授权提交品质foundation。
# Phase5按每个entry的baseline分支与beforeSha移植，不能整树copy/强制whole-patch覆盖。
```

上述SHA门先计算再显式比较，结合`set -e`使不匹配立即停止；仍需按manifest核所有精确来源输入。

如integration路径/分支已存在，先核其归属和状态，选择新的隔离名，不覆盖已有工作。Phase5整patch的合成replay不是上述当前tree的apply gate；实际entry/hunk选择完成前不能声称patch可直接apply。

## 冻结快照与 ignored resource 规则

`git diff` 和 exclude-standard 新文件统计不能捕获所有构建必需的ignored输入。Main冻结manifest必须显式覆盖canonical新源码及producer，例如shell/provision/runtime模块、`sync-guest-init.py`，并说明投影关系：canonical source → `guest-assets/initramfs/init` → `app/src/main/resources/guest_init`。runtime来源还包括明确的 `app/src/main/resources/initramfs.cpio.gz`、`proxy_relay`、`app/src/main/assets/runtime-artifacts-v1.sha256`、kernel/相关JNI及NOTICE/source offer的输入锁与producer。

对大型runtime输入用已批准resource root的固定来源/大小/SHA/producer引用；只复制确需构建的精确文件，不把整个外部resource root当源码快照。`git add -f`如需要只能精确针对批准的新源码/小型脱敏证据；不能对resources/assets/docs目录整体强加。原policy/安全门不因本票临时放宽。

Phase5 delivery里旧C、ELF、guest-init、bundle/manifest、NOTICE是它的冻结交付证据，不是最终合并后的结果。最终source offer必须能对应新的统一C/固定输入/binary与包；早期sourceHash或任一侧APK摘要不能替代。

## 测试、验收与未完成状态

本次仅运行文件集统计、必要before SHA匹配、源码diff与14项只读merge-file；**没有运行测试、build或设备**。报告引用Main/Phase5既有结果只用于范围判断，不重新认证其全部artifact。

- Main quality实施报告仍有历史“验收进行中”文字；协调会话当前提供Main完整App测试628项、失败/错误/跳过0，lint错误0/警告669，当前APK与installed bytes匹配。其精确证据入口为quality artifact的`evidence/quality-main-progress.json`及canonical交接文档。用户已结束当前QA/七族验收并要求冻结交接，不在本会话追加验收；七族逐项最终状态按协调者最新矩阵裁定，本子没有重复设备审计。
- Phase5 build-lock记录profiles全套为 **24/25**，一项安全拒绝错误码断言失败；后来修了目标并通过相关定点回归。没有全套重跑证据，不能改写为25/25或完全通过。
- Phase5 `runPhase5JvmAppTests`只跑选定App类，lock记录61项；不是Main全套App测试的替代品。最终要运行Main完整App检查与Phase5新增类union、contract/instance/QEMU/AVF、guest process/relay/profile、安全负例、native/lint与APK内容校验。保留既有失败历史与Linux-only平台限制。
- 真机labwc/wayvnc/virtio或vsock/Surface、ARM64 Podman/code-server、WebView WS/SW/cookie隔离、LAN客户端/到期、真实AVF/OEM、Android只读9p provider和guest mount均需新证据。SAF production broker、USB native VMM透传、GPU/零拷贝、全七族桌面、KDE/niri、Docker rootless daemon并未因源码合并而完成。
- 设备请求 `REQUESTED_NOT_GRANTED`不等于授权；新的安装/运行按当时协调者独占时段与正式候选APK身份进行。不得安装QA `.phase5`包并据其结果宣称正式第一方包已验收。

验收报告必须分别写源码/宿主测试通过、构建与内容校验通过、真实guest/Android已验、NOT_RUN/未实现/受阻。只有新合并闭包的相应证据齐全，才升级其对应行为的结论。
