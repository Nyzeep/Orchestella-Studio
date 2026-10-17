# 品质与 Phase 5 合流独立 Spec 审查报告

- 审查者：Codex / new-provider/gpt-6.1-sol，未参与被审实现，独立承担 Phase5 数据与产物边界轴；实际审查了 input lock 与 producer。
- 背景与 scope：按 [合流 prompt §12](../../../agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md) 审查真实 readiness、显示分配/FD/线程、profile 身份与取消、WebView/origin/lease/owner、AVF/9p、SAF/USB 拒绝边界，以及静态 guest、许可证和 APK 对应关系。品质、终端及控制协议生命周期由另一独立轴审查，本报告不代替其结论。
- 工作树：`Orchestella_merge_dev`；分支：`integration/quality-phase5`。
- 固定 base：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 父代理明确通知的最终源码快照：`6bbc31c29e97c0eba64122d22d9ea301d658d097`。预审期间没有提前写最终 PASS；正式结论在收到该通知后形成。
- 实际执行只读 `git diff 8d0e8c75d769ecc645ea102b90a304c8657344a1...6bbc31c29e97c0eba64122d22d9ea301d658d097`，merge-base 等于指定 base；范围为 340 个文件、47039 行新增、5564 行删除。最终核对 `app/`、`guest-assets/`、`runtime/`、`tools/phase5/` 与该快照无源码 drift。

实际 commit 清单（`git log base..snapshot`，新到旧）：

1. `6bbc31c29e97c0eba64122d22d9ea301d658d097` — `fix(editor): retire the previous instance scope on task reentry`。
2. `cfec895ca0bd0186b212f7c73d27b8d6bb06898c` — `feat(integration): combine Phase 5 with quality and identity safeguards`。
3. `3b637e48f51fb4b78282d468d9c78b37d582de87` — `feat(quality): preserve native Linux, terminal and UI foundation`。

## 结论与批准范围

**approve：批准上述冻结源码的合流及对应宿主构建/产物交付。最终快照无尚未关闭的 Spec 阻断 finding。** 预审及提交级复核发现的实际问题已向父代理即时提供源码行号、触发条件和证据，修后重新核对了实现、完整适用 suite 与新 APK。

本批准不包含新候选的 Android/ARM64 guest 设备功能验收，也不包含公开二进制 release 或对应源码公开发布门。候选 APK 与已安装主应用签名不兼容，未安装，运行回归明确 **NOT_RUN**。桌面、IDE、GPU、OEM AVF、Android 9p、SAF/USB 的等级不得从宿主 fixture、旧报告、权限预检或 APK 内容核验提升。

## 规范与具名输入

已读新树 `AGENTS.md`、`CONTEXT.md`、`docs/agents/issue-tracker.md`、`DEFENSIVE_CONSTRAINTS/README.md`、`DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md`、`INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md`，以及 HOST、ARTIFACT、ASSET、ARCHITECTURE、REPORTING 五份 policy。执行前限定只读新树的具名规则、源码/测试和批准外部根的具名脱敏证据，无网络端点。

项目指定的 skill 实际读取路径为 `D:/Development project(开发项目)/Orchestella开发目录/.agents/skills/code-review/SKILL.md`；同名 Skill 调用返回歧义后，按项目 prompt 读取该文件并遵循其 Spec 轴：固定三点 diff、commit list，核缺失/部分实现、越界增项及实现错误，逐项挂规范与实现证据。

具名规范入口：

- [QUALITY_PHASE5_MERGE_PROMPT](../../../agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md) §7、§8、§9、§10、§11、§12；[QUALITY_PHASE5_MERGE_HANDOFF](../../../agent-handoff/QUALITY_PHASE5_MERGE_HANDOFF.zh-CN.md)。
- [merge-handoff-conflict-map](../../orchestration/merge-handoff-conflict-map.zh-CN.md) G20、G25–G35 及统一 schema。
- [Phase5 spec](../../../../.scratch/orchestella-phase5/spec.md)，Implementation Decisions 与 Testing Decisions；[Phase5 map](../../../../.scratch/orchestella-phase5/map.md)；[ACR-01](../../../../.scratch/orchestella-phase5/issues/ACR-01-gpu-buffer-and-usb-authority.md)。
- Phase5 具名实现票 [05](../../../../.scratch/orchestella-phase5/issues/05-authenticated-real-display.md)、[06](../../../../.scratch/orchestella-phase5/issues/06-recoverable-guest-profiles.md)、[07](../../../../.scratch/orchestella-phase5/issues/07-services-and-readonly-data.md)、[08](../../../../.scratch/orchestella-phase5/issues/08-integration-and-device-gates.md)，以及 AVF/9p 对应源码与负例。
- [本次 integration map](../../../../.scratch/quality-phase5-integration/map.md) 与 02、03、04 票；[最终实施报告](../../orchestration/quality-phase5-integration.zh-CN.md)、[最终验证报告](../../orchestration/quality-phase5-integration-verification.zh-CN.md)。旧 Phase5 实施/矩阵/指南与旧核验仅用于来源和范围定位，不作新设备通过证据。

## Spec 轴逐项核对

以下源码路径均相对本仓根；行号来自最终快照对应文件。外部 evidence 路径均相对 `Orchestella/artifacts/quality-phase5-integration/`。

| 要求与状态 | 最终实际实现及核验 | 新证据与验收边界 |
| --- | --- | --- |
| **桌面 readiness：源码满足，设备 NOT_RUN。** prompt §7 要求“同次 guest Wayland/capture_generation、鉴权后的 RFB 新非纯色帧、当前 generation 的实际 EGL/GL/swap 成功及 CONFIRM”，并要求设备 input damage。Phase5 spec 第41–45、58–60行区分 transport 与 capture/ready。 | `guest-assets/features/profilectl.py:989` 实际 grim capture、尺寸/长度/非纯色门，`:1012` 写本次 capture_generation；`:1414`、`:1422` 在 mutex 内复验 generation、capture、boot、取消状态才确认。`DesktopPresentationGate.java:17`、`:34`、`:52` 约束时间、尺寸、非纯色和本地 epoch，迟到确认不能消费新 ticket；`DesktopActivity.java:120`、`:352` 核当前 session/client/lifecycle 后发送确认。`DoubleBufferedRenderer.java:164` 仅成功呈现后调用 observer；native `graphics/native_gles_presenter.cpp:475` 检查 GL error 与真实 eglSwapBuffers 返回值。 | 最终 App 包含 gate 6项、renderer 11项、native contract 10项、native lifecycle 9项、Surface lifecycle 8项；RFB/relay 与 profile capture/stop/resize 负例均核过。没有本轮 ARM64 capture→RFB→Android Surface/input damage 设备证据，维持 experimental。CPU-copy 标示及无 GPU/zero-copy claim 保留。 |
| **显示分配、FD、线程：满足宿主门。** prompt §7、§12 与 spec 第43–44、58行要求先校验再分配、4MP/4096/16MiB、有界队列和断流收口。 | `runtime/contract/.../display/RfbProtocol.java:13`、`:133`、`:207` 和 `WaylandChannel.java:115` 检查边长、总像素、aggregate update、stride/format/长度；完整真实 Raw 像素才发布。`RfbDisplayChannel.java:290` 有界 writer queue，`:313`/`:491` deadline/close；Android VSYNC 与 latest-frame 槽不排无界整帧。`QemuInstanceLauncher.java:77`、`:99`、`:546`、`:554` 的最终接缝预持 socket、单 Atomic close owner、连接前后取消门、Future 期限/中断；生产 pool 最多2 worker、无队列。 | 4项新增生产接缝负例覆盖忽略 interrupt 的阻塞 connect 由 close 唤醒、排队超时不 connect、成功移交后不异步误关/双 close 只关一次、interrupt 保持与线程退役，已进入完整 App663。真实 Android native cancel/context-loss 仍未实跑，不转写设备 PASS。 |
| **profile 普通用户、身份 marker、取消与配置保全：满足宿主门。** spec 第46、48、60行与票06要求普通用户、原子状态/单实例锁、失败可重试、已有配置保留；prompt §8要求未知/root身份 action-required，noSudo 不自动加权。 | `runtime-functions.sh:107` 原子 root-owned0600 instance-user marker；`profilectl.py:341` 同一 FD/fstat、有界读取并在操作内固定身份；普通用户 uid/gid/home/groups 校验与 root/缺失/非法拒绝。`:269` 非阻塞读，leader/EOF 共同受 deadline/cancel；`:319` 终止原进程组；`:421` 保留已有配置，`:548` 普通用户 owned units，`:623` 强随机认证/0600验证。基线安装与可选状态分离，无 root 替代、无删除根盘/覆盖 init。 | 完整无选集 `identity-profiles/linux-profile-final-20261014/evidence/profile-contracts.log.txt`：39/0/0/0、exit0，源摘要匹配最终源码；新增3项已在全套中。配置/升级、ENOSPC、签名/输入拒绝、取消/staging、实际合成 HTTP listener、普通用户路径均覆盖。真实 ARM64 用户/Podman/code-server 执行 NOT_RUN。 |
| **WebView exact origin、lease、owner：满足源码/宿主门，平台全流量 NOT_RUN。** spec 第49–50、61行及票07要求精确所选 loopback origin、秘密不入URL、显式有限 LAN、stop后关闭。 | `EndpointPolicy.java:49`、`:57`、`:75` 精确 scheme/raw authority/端口、route/query与路径拒绝；`LocalEditorActivity.kt:108` 禁 file/content/mixed，SSL cancel，无 JS bridge；`:166` cookie/storage 回调核 owner/policy。`:184` 处理 singleTask 新 Intent，跨实例先 retire/recreate；`:251` cleanup 确认 UNEXPOSE/owner，失败保留 pendingRevocation，旧 scope 立即拒绝且新 scope 排在串行 cleanup 后。`LocalTcpRelay.java:67` 限明确授权/认证/至多10min，`:127` 关闭 listener 与活动连接；LAN 退后台即关，不持久 autoenable。 | services/selection/USB模型27项及实际本机 TCP/HTTP/lease 负例；最终 App 新5项覆盖 A→B、重复切换、同实例、closed重开、空选择。WebView login/WS/SW/cookie、LAN客户端/到期的 Android 全流量仍 NOT_RUN；exact-origin callback 与纯 Kotlin顺序测试不等于平台强隔离验收。 |
| **AVF/9p：拒绝边界与宿主负例满足。** prompt G28/G29、spec 第42、51、62行要求官方 shape、双权限、FD/stop/cold restore及只读范围。 | `ReflectiveAvfVirtualMachineApi.java:72` 双权限，`:154` 持久配置逐字段匹配，`:212` 官方 long-port/PFD与late ownership；`AvfVirtualMachineEngine.java:214` stop不持连接锁，`:296`、`:332` late channel/一次归属关闭。App AVF service forwarding仍明确不支持。`Avf9pShareServer.java:222` 拒写，`:254` SecureDirectoryStream逐祖先NOFOLLOW；缺安全provider返回ENOSYS，无 path fallback。 | 新 runtime check 实际执行官方 shape/resource main；Linux9p wire/process原件：1500 sessions FD8→8、400 symlink race 0 outside reads，exit0；Windows gate明确 secure_provider=false/browse_not_run。OEM AVF、Android provider与guest mount NOT_RUN。FIFO并发替换残余DoS限制继续披露，无新扩大共享范围。 |
| **SAF/USB prototype 明确拒绝：满足范围约束。** spec 第51–52、62行、ACR-01第19–21行与prompt要求未接通不得升级production。 | `DownloadsSelection.java:26`、`:65` 必须明确授权与可信 SourceProof，精确选集不扫描工作区；`ReadOnlyStager.java:32`、`:300` 只发布 UNVERIFIED catalog，限额/hash/取消/原子generation，失败保留旧代；`prototypes/phase5-usb-fd/.../UsbFdSession.java:74` 无条件拒绝native passthrough。向导未接通共享仍禁用。 | 新27项模型/I/O suite含路径/URI/.git/.env、未知proof、截断/取消/拒写与FD撤销。Android SAF held-FD/broker与USB跨进程/VMM透传未接通；模型与合成基准没有改写成物理挂载或手机性能。 |
| **static guest producer/input lock：独立实审满足。** prompt §9要求合成最终C后真实Linux AArch64静态重编、固定NDK/musl/UAPI/builtins provenance，不能用旧ELF或测试宏。 | `tools/phase5/build-guest-agent.py:37`、`:53`、`:62`、`:80` 实际消费依赖闭包与锁逐项校验、编译前后hash/dep输出一致；`:95` 核唯一GNU_STACK无X。锁47项中39实际头文件均有固定包成员来源。编译为Linux musl，生产flags无host-test/POWER mock宏。 | reviewer直接读47输入比size/SHA，39头文件与锁定musl-dev/Linux UAPI APK具名tar成员内存比字节，0差异；读取最终重编记录并直接解析477400B ELF，ELF64LE AArch64，无PT_INTERP/PT_DYNAMIC、GNU_STACK无X。缺ctype.h锁项负例exit1、binary未生成。此检查由本独立轴承担。 |
| **repack428、payload/NOTICE/licenses/source/APK对应：满足产物门。** prompt §9第219–225行要求只替唯一agent、保全428项、生成投影与七payload同源、真实APK/许可对应。 | `repack-guest-agent.py:30`、`:74`、`:99`、`:104` 核唯一agent、旧bundle manifest、其他entry保全及mtime0压缩；`sync-guest-init.py:24`、`:29`、`:101` 从最终canonical模块/七payload生成并check。`runtime-build-inputs.lock.json` 明确QEMU/kernel/gvproxy/base bundle及producer；gvproxy source与AGP剥离后hash分别记录。 | reviewer直接解析前后cpio，其他428entry名称/顺序/payload SHA/mode全等，唯一agent等于重编ELF；直接读最终APK AXML/ZIP并对Git快照blob比较7 runtime pins、guest_init、七heredoc payload、8 NOTICE/source/license资产与native libs。QEMU GPL/source-offer、musl/compiler-rt与desugar GPL+Classpath/Assembly许可存在并对应。公开binary/source-release gate未执行。 |

## 实际 finding 与修后复核

以下均已即时向父代理发出 request-changes及具体证据；最终快照中全部关闭，没有将实现缺口仅改为文字说明。

1. **输入锁只覆盖两头文件，未覆盖真实编译头文件。** 原 producer 编译整个 include root却只校验 stdio/vm_sockets。修后 `build-guest-agent.py:53`、`:62` 以实际 `clang -M` 消费闭包拒缺项，47固定输入/39头文件与锁定官方包具名成员逐项相等；实际按最终锁重编，缺header负例在产出前失败。证据：`source-verification/guest-agent-consumed-headers-{deps,archive-comparison}.json`、`guest-agent-input-lock-header-closure.json`、`runtime/verified/evidence/static-agent-build.json`、`runtime/input-lock-negative/result.json`。
2. **profile leader退出、后代仍持stdout时超时失效。** reviewer稳定无文件探针在timeout0.5s下实际3.063s返回；修后 `profilectl.py:269` 以非阻塞read将EOF纳入同一取消/期限，`:319` 回收原进程组。独立Linux无文件复核为timeout0.505s、cancel0.202s，live group与drain线程均0；新增负例进入最终39项全套。原36项错误/取消失败历史保留，未用3项定点绿色替代全套。
3. **显示connector建立阶段FD/线程未纳入取消，初修watchdog又有移交竞态。** 最终 `QemuInstanceLauncher.java:99`、`:554` 改预持socket与单Atomic close owner；成功不遗留后台watchdog，连接前后取消门、Future deadline和2worker上限收口。`QemuInstanceLauncherSanitizeTest.java:89` 起4项真实生产接缝负例证明阻塞/排队/成功/interrupt路径；最终完整App运行这些用例。中间实现不作为最终finding重复计算。
4. **singleTask编辑器忽略B实例Intent、继续使用A的id/origin/forward。** Manifest单任务重用、原Activity只在onCreate读取id且无onNewIntent，导致A→B未走owner/cookie撤销。独立提交 `6bbc31c…` 在 `LocalEditorActivity.kt:184` 接入重入处理，`LocalEditorReentry.kt:11` 先退役再recreate/finish，旧id保留供旧cleanup撤销A；新scope继续经过同串行队列与pendingRevocation门。`LocalEditorReentryTest.kt:9` 起5项回归进入最终663项完整App；修后APK重新构建并更新提交pin。

更细的脱敏预审过程与独立比较留在外部 `review/spec/pre-review-evidence.json`；正式结论以本报告所列最终快照及新证据为准。

## 新测试与产物真实性

reviewer未重新执行生产构建、producer或设备任务；实际工作为源码/diff审查、只读hash/AXML/ZIP/ELF/cpio比较、解析新日志/XML，以及无文件的合成进程deadline/取消探针。下表是本次读取并核对的新运行原件，不累加历史或重复suite。

| 新验证 | 实际结果 | 具名原件 |
| --- | --- | --- |
| 完整App、lint、assemble | 92 suites、663 tests、failure/error/skip各0；lint Error/Fatal0、Warning688；构建exit0 | `evidence/merge-host/editor-reentry-final/gradle-result.json`、实际`build/merge-host/outputs/app/test-results/testDebugUnitTest/`与`reports/lint-results-debug.xml` |
| runtime适用门 | contract/instance/QEMU/AVF/network/distro/capability checks exit0；AVF shape/resource与response pump main确已执行 | `evidence/merge-host/final-after-display-timeout/gradle-result.json`及raw log；显示修复重跑contract，editor修后完整App重跑；main-based门不虚计为JUnit |
| profiles完整套件 | 39/0/0/0、exit0，262.819s；源码摘要未漂移 | `identity-profiles/linux-profile-final-20261014/evidence/{result.json,profile-contracts.log.txt}` |
| guest基线/恢复 | 133/14/56/11 checks，分别exit0、skip0；最终generator check | `identity-profiles/guest-final-validation/20261014-final-generated-source/evidence/final-verification.json`及其具名raw/summary；sourceUnchanged、preserved一致 |
| guest-assets JVM | 73 tests、0 failure/error、2 skips | `evidence/guest-utf8/result.json`；Windows两项Linux-only skip保留 |
| POSIX扩展进程union | 50/50 cases、865 assertions、96 helpers，25对stdin/testTCP；HELLO14/44、POWER24/96 | `posix-validation/evidence/20261014T042332597852Z/` final summary/process记录；x86_64 test宏/合成helper，不等于生产AArch64或AF_VSOCK实跑 |
| 显示/数据/9p | focused显示21、services/selection/USB27、Linux relay15；native color实际同源host执行；9p FD8→8/1500、symlink race400/0 outside | `display-services-avf/evidence/`具名result与raw；各项不累计成设备通过总数 |
| 最终产物清单 | 9个具名delivery文件实际size/SHA全等manifest | `delivery/runtime-output-manifest.json`、`verification-summary.json`、`apk-content-verification.json`、`apk-summary.json`，sourceCommit均锚定最终快照 |

最终直接核验的关键摘要：

| 对象 | Bytes | SHA-256 |
| --- | ---: | --- |
| `delivery/Orchestella-Quality-Phase5-local-debug.apk` | 71969178 | `e0ac730bd4e0441e6d9d347e7f25643f1ba9b0b00c4c8fa5a63474fdd8e7101a` |
| 合成最终C | 42245 | `f1250967916612da9680526a51fa99973c711b21ed6b4044cbd2468dff1c5fb5` |
| agent输入锁 | 21496 | `f2631c082d40dbda20b3046956c7cd94ed5e309c60784d128f669061812947e3` |
| 静态AArch64 agent | 477400 | `6c6470872f2bc45c56729bb4d9848f0f0b5e559f79dc8b3e64e3ba4c643893e1` |
| 公共cpio.gz | 1267115 | `c96fe6e6d95b097b7b7509a669b8aaf19acf73de4541d4b4e069795213540361` |
| canonical init / APK guest_init | 230603 | `75c4d6e7b30013092f1220b9980ba552f22c9b50490fcc8687c970935f0a72b8` |

实际APK身份为`io.github.nyzeep.orchestella`、versionCode300、`0.3.0-preview.1`、min26/target36。feature/editor Activity均exported=false；正常构建无`.phase5`后缀。QEMU APK ELF为`525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a`；gvproxy APK为`0f21c658fb7870a95682c37784de24a12806b97bea49e6c0ae5a93fad66b5d37`，与source ELF不同有AGP剥离记录。`runtime-build-inputs.lock.json`工作树CRLF与Git blob LF的JSON值/归一化内容相等，未当作语义drift；真正参与runtime checksum的资产按原字节比较。

## NOT_RUN、风险与后续动作

`delivery/device-result.json`记录荣耀稳定别名/API36、预检和安装包证书核对已执行；候选Debug证书与已安装release主应用不兼容，`installAttempted=false`、`runtimeRegression=NOT_RUN`、原App数据保全。reviewer没有执行任何设备操作。既有已安装品质APK或QEMU基础结果不迁移为本候选PASS。

- 新APK基础Linux/终端/DNS/POWER/冷恢复设备回归、真实桌面capture/input damage、Surface旋转/context-loss、普通用户容器/IDE均NOT_RUN。
- WebView login/WS/SW/cookie与LAN客户端/到期的实际Android负例NOT_RUN；纯policy/顺序回归只能证明宿主接缝。现有experimental/未验证文案保留。
- OEM AVF权限/运行/真实vsock cancel、Android SecureDirectoryStream与9p mount NOT_RUN。现有Linux9p对恶意FIFO并发替换的残余DoS边界已披露，不能扩大为任意宿主目录或production SAF授权。
- GPU/共享buffer/fence/zero-copy、KDE/niri、其他五族增强profile、Docker rootless daemon、USB原生VMM透传、production SAF broker仍未实现、未接通或未实测。ACR-01保持待架构裁定；本轮未接受新ADR或扩大暴露白名单。
- lint仍有688项Warning；本审查确认无Error/Fatal和无降低门换绿，不宣称零警告。
- 后续设备验收必须用合法兼容签名候选或明确授权的隔离测试身份，保存新运行证据；七发行版长测按用户取消要求保持停止。公开二进制发布前另行执行对应源码公开交付门，本approve不替代该门。

## 修改、越界与 Architecture Change Policy

reviewer唯一仓内写入为本报告；创建前确认目标不存在、无symlink、归属为`docs/reports/reviews/quality-phase5-integration/`。外部仅写具名脱敏`review/spec/pre-review-evidence.json`与终审证据；owner为本独立review轴，随本次Class B审查证据保留，由协调者收口管理。未修改实现/来源树，未stage/commit/push，未访问网络、未调用设备、未读签名secret/用户实例盘、未向参考仓写入。运行的合成进程探针均已结束，无自有listener/forward/lease或后台任务。

本审查未触发新的 Architecture Change Policy；既有ACR-01继续生效。未完成内容为上述明确NOT_RUN/未实现矩阵，没有剩余源码修复请求。正式报告的提交和第一方普通push归协调者；本审查没有执行Git写入或声称push完成。

报告人：Codex / new-provider/gpt-6.1-sol（独立 Spec reviewer） ｜ 日期：2026-10-14 ｜ 耗时：未单独计时
