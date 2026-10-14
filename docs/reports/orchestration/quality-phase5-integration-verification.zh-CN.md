# 品质优化与 Phase 5 合流验证报告

- 日期：2026-10-14；执行者：Codex / new-provider/gpt-6.1-sol。
- Scope：[完整执行 prompt](../../agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md) 的适用宿主、协议、guest producer、profile、显示、服务、AVF/9p、构建与产物门，以及定向设备预检。
- 分支：`integration/quality-phase5`；隔离工作树：`Orchestella_merge_dev`。
- 共同 HEAD：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 品质 foundation：`3b637e48f51fb4b78282d468d9c78b37d582de87`。
- 合流源码提交：`cfec895ca0bd0186b212f7c73d27b8d6bb06898c`。
- 最终源码提交（含编辑器重入修复）：`6bbc31c29e97c0eba64122d22d9ea301d658d097`。
- 实施内容与修改域见 [实施报告](quality-phase5-integration.zh-CN.md)。本报告只记录本次实际结果；历史品质 628 / Phase5 61 / profiles 24/25 不累计为本次测试数。

## 本次实际结果

下列 evidence 路径均相对批准外部根 `Orchestella/artifacts/quality-phase5-integration/`。小型可公开摘要见 [verification-summary.json](../../../artifacts/quality-phase5-integration/verification-summary.json)。原始日志、XML、较大产物与字节一致的 preserved 副本留在外部根；没有提交整个 artifact 目录。

| Gate | 实际结果 | 适用范围与证据 |
| --- | --- | --- |
| 品质冻结真实性 | PASS；131 源路径、12 公共运行/构建输入；drift=0 | `source-verification/quality-source-verification.json`；14/14 SHA256SUMS，actual size/hash 相等比较 |
| Phase5 冻结真实性 | PASS；14/14 校验项、141/141 owning source；32 HEAD before、14 captured before、95 new | `source-verification/phase5-source-verification.json`；raw/normalized EOL 各自核 pin，不将 raw EOL 差异当语义变化 |
| 品质 foundation apply | `--check` exit0、apply exit0；App 628 项，0 failure/error/skip；四个 runtime checks PASS | `evidence/foundation/apply.json`、`evidence/foundation-cache/gradle-result.json` |
| 最终完整 App union | **663 tests、0 failures、0 errors、0 skips**；92 XML suites | `evidence/merge-host/editor-reentry-final/gradle-result.json` exit0；`build/merge-host/outputs/app/test-results/testDebugUnitTest/`；包含显示 socket 4 负例及编辑器重入 5 回归，保留原测试 |
| 全适用 runtime checks | contract、instance、engine-qemu、engine-avf、network、distro、capability 全部 PASS | `evidence/merge-host/final-after-display-timeout/gradle-result.json` exit0；之后 app-only 修改的最终门重跑 contract check；独立 main-based 契约门按日志记录，不凑入 JUnit 总数 |
| Bridge 控制扩展 | PASS；response-pump main 25 场景；FEATURES/CONFIRM 同 reader/reauth、拒绝后 PING | 最终 contract `check` 日志；contract JUnit 24 项全绿，main-based tests 另列 |
| AVF 官方 shape/资源 main | PASS；官方 API、双权限、FD/stop/late connect、持久配置/冷恢复拒绝负例 | `reflectiveAvfVirtualMachineApiTest` 与 `avfVirtualMachineEngineResourceTest` 已注册进 check，本次日志实际执行；不是 OEM custom VM 验收 |
| guest-assets JVM suite | **73 tests、0 failures、0 errors、2 skips** | `evidence/guest-utf8/result.json` exit0、`build/guest-utf8/outputs/root/test-results/test/`；Windows 两项 Linux-only 进程用例跳过，不能写成零 skip |
| Linux guest 发行版合成矩阵 | **133 checks、exit0、skip0** | `identity-profiles/guest-final-validation/20261014-final-generated-source/distro-runtime-matrix/evidence/`；仅合成包/服务路由，未恢复七族设备长测 |
| Linux guest 失败诊断 | **14 checks、exit0、skip0** | 同验证根 `distro-failure-diagnostics/evidence/`；原退出码、sync/持久化与失败原因 |
| Linux native/recovery/loader | **56 checks、exit0、skip0** | 同验证根 `native-recovery-loader/evidence/`；内部 generator `--check` 实际通过 |
| Linux manual recovery | **11 checks、exit0、skip0** | 同验证根 `manual-recovery/evidence/`；手动恢复不自动 provision |
| 身份 guest 定向 | **7 checks PASS** | `identity-profiles/guest-identity-20261014/evidence/identity-results.json`；无密码 marker、非法身份、noSudo、自有 grant 与用户配置保全；合成根而非 ARM64 guest |
| POSIX control union | **50/50 cases、865 assertions、96 helpers、25 对 stdin/testTCP 等价** | `posix-validation/evidence/protocol-union-support-result.json`；最终 run `20261014T042332597852Z`；新 C fresh host binaries，不复用旧 ELF |
| HELLO 专项 | **14/14 cases、44 assertions** | 同 POSIX 最终 evidence；幂等、坏 token/版本/畸形请求；未知命令明确 ERROR 后 PING，无 helper/POWER 派发 |
| POWER 专项 | **24/24 cases、96 assertions** | 同 POSIX 最终 evidence；Linux compile-time mock only，未执行宿主 power；Windows-only production unavailable 用例未在这次 Linux family 执行 |
| 完整 Linux profiles | **39 tests、0 failures、0 errors、0 skips、exit0，262.819 秒** | `identity-profiles/linux-profile-final-20261014/evidence/result.json` 与 `profile-contracts.log.txt`；无选集参数，sourceUnchanged=true |
| profile 新 deadline/cancel/schema targets | **3 tests、0 failure/error/skip、exit0** | `identity-profiles/profile-final-targets-20261014/evidence/`；leader 退出后后代持 stdout、取消/组回收、STATUS 错误 schema |
| 显示 focused JVM | **21 tests、0 failure/error/skip** | `display-services-avf/evidence/display-jvm-final-result.json`；后续新增显示 socket 4 负例已进入完整 App union |
| Native presenter/renderer/lifecycle scoped | **38 tests、0 failure/error/skip**；严格 Java17 `-Xlint:all -Werror` | `display-services-avf/evidence/native-presenter-scoped-final-result.json`；显式注入成功/false/异常 native adapter，生产仍是真实 JNI |
| services/选集/USB 原型 | **27 tests、0 failure/error/skip**；严格编译 | `display-services-avf/evidence/services-selection-usb-result.json`；模型/合成 I/O，不是 Android 原生透传 |
| Linux relay | **15 tests、0 failure/error/skip** | `display-services-avf/evidence/linux-display-relay-result.json`；真实本机进程，不是 ARM64 Wayland→Android Surface |
| Linux 标准只读 9p | PASS；wire、真实 child/EOF、拒写/边界；1500 sessions FD **8→8**；400 symlink races **0 outside reads** | `display-services-avf/evidence/linux-ninep-complete-result.json`；六具名源 Java17 `-Xlint:all -Werror`；Android provider/mount 单列 NOT_RUN |
| 同源 native 颜色 | PASS；红/蓝/alpha 的静态 host 编译与 WSL 实际执行 | `display-services-avf/evidence/native-color-result.json`；不代替 Android EGL/Surface/context-loss |
| Java/Android/NDK 构建 | PASS；严格 runtime 编译门不降低；ARM64 CMake/NDK、assembleDebug exit0 | `evidence/merge-host/native-build-final/`、`display-owner-final/` 与最终 `editor-reentry-final/` |
| 最终 lint | **0 errors、688 warnings**，exit0 | 最终 `:app:lintDebug`，AndroidTest/UnitTest/App 分析全部运行；XML `build/merge-host/outputs/app/reports/lint-results-debug.xml`；未加 baseline 或禁用测试 |
| generator | PASS；canonical init 与 APK resource 相等，7 payload 来自最终源码 | `sync-guest-init.py --check` 实际执行；guest baseline gate 与 APK 内容提取交叉核对 |
| ARM64 agent producer | PASS；47 locked inputs、39 consumed headers；编译前后 hash/dep 集合相等 | `runtime/verified/evidence/static-agent-build.json`；ELF64LE AArch64，无 PT_INTERP/PT_DYNAMIC，GNU_STACK 无 X；生产不带测试宏 |
| producer 缺头文件负例 | PASS；移除 ctype.h lock 项，exit1、未生成 binary | `runtime/input-lock-negative/result.json`；Class A 真实头文件未改 |
| cpio repack | PASS；唯一 agent 替换，其他 **428 payload/mode 保全**、确定性 gzip | `runtime/evidence/guest-agent-runtime-update.json`；before bundle 对 manifest 实际相等，旧输入保留 |
| APK 内容与许可证 | PASS；AXML 身份、7 runtime hashes、唯一 agent、guest_init、7 payload、NOTICE/许可/native libs | `delivery/apk-content-verification.json`、`delivery/merged-badging.log.txt`、独立 Spec ZIP 比较；不代替真实执行 |
| staged source/data boundary | PASS；196 allowed paths、195 changed staged paths、9 exact-byte assets；无大 binary/实例盘/private-key block | `merge/staged-source-gate.json`；源码 commit `cfec895c`，精确路径 stage；生成二进制与凭据未入库 |

## 最终产物对应关系

| 产物 | Bytes | SHA-256 |
| --- | ---: | --- |
| 最终候选 APK | 71969178 | `e0ac730bd4e0441e6d9d347e7f25643f1ba9b0b00c4c8fa5a63474fdd8e7101a` |
| Linux AArch64 静态 agent | 477400 | `6c6470872f2bc45c56729bb4d9848f0f0b5e559f79dc8b3e64e3ba4c643893e1` |
| agent 最终 C source | 42245 | `f1250967916612da9680526a51fa99973c711b21ed6b4044cbd2468dff1c5fb5` |
| agent 47 输入锁 | 21496 | `f2631c082d40dbda20b3046956c7cd94ed5e309c60784d128f669061812947e3` |
| 公共 initramfs cpio.gz | 1267115 | `c96fe6e6d95b097b7b7509a669b8aaf19acf73de4541d4b4e069795213540361` |
| canonical init / guest_init | 230603 | `75c4d6e7b30013092f1220b9980ba552f22c9b50490fcc8687c970935f0a72b8` |
| runtime manifest | 595 | `83c60a72a62efda5e9b062959f30e3f73d93bc363610db3a1723ffa74cf4e380` |

APK 主身份 `io.github.nyzeep.orchestella`，versionCode300 / `0.3.0-preview.1`，minSdk26、targetSdk36、arm64-v8a；正常构建无 `.phase5` 后缀。最终 APK 和 runtime 副本位于外部 `delivery/`，`SHA256SUMS` 9 个具名条目已逐项相等比较，来源提交由 `delivery/apk-summary.json` 记录。此前候选 `91d166b2…` 和 `bbc9da57…` 分别保留于 `delivery/history/pre-display-owner/` 与 `delivery/history/pre-editor-reentry/`，均不作为最终交付。最终包对应源码提交 `6bbc31c2`。

QEMU input ELF SHA `525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a` 与 APK 相同；gvproxy source `c1ea8646…` 和 AGP-stripped APK `0f21c658…` 分别按其真实来源核对，没有错误要求 source=package。desugar 2.1.5 JAR pin、Classpath/Assembly license、QEMU/GPL/source-offer 与最终 guest-agent source/producer 已对应。公开 binary release 门未执行，本次只普通推送源码集成分支。

## 失败历史与修复真实性

- 品质首次 offline build 选用缺 Material/AppCompat 的旧缓存；切到已有 ASCII-cache 只读复用后通过，没有重复下载整工具链。
- guest-assets 首次默认 Windows GBK 编译遇 UTF-8 fixture 错误；显式 UTF-8 修复后 73/0/0/2 通过。
- lint 模型的 `path=null` 为 Gradle 格式化未缓存依赖错误；定位 AndroidTestCompileClasspath 三个固定依赖，再补 lint31.13.2 工具闭包的 9 个缺 JAR。只按原 repositories 最小解析固定输入；完整 offline lint 实际通过，无 source/config gate 规避。具体记录在 `build/lint-diagnosis/lint-environment-result.json`。
- 第一次完整合流 App 为 641 tests / 8 failures，旧 native presenter 用例对空 Surface/未加载 JNI 要求假 ready。加入生产默认 JNI、实例级可注入 adapter 与实际 false/异常拒绝回归，原 11 tests 保留；最终完整 App 663 全绿。原失败原件未覆盖。
- profiles 本次早期完整 36 项出现 3 failures、随后 1 error（job_active schema）、带 trace 再出现 1 cancel failure（unsafe identity）。修复实际读取快照/状态 mutex/error schema，独立预审又复现 leader 退出但后代 stdout 持有超时失效；改非阻塞 EOF/leader 同期限与原进程组回收并补回归。最终无选集完整 39 项全绿，旧三轮全套失败保留。定点 3 项绿色未替代全套。
- input lock 首版只有 2 个头文件，独立审查要求锁定实际消费闭包；补 39 个头文件、与官方锁定包成员逐项字节比较并加入缺项负例，实际按最终锁重编 ELF。锁定输入不以仅说明来源代替实际校验。
- 独立预读发现 selection 瞬态 reauth 误退休、握手期间重入丢 ready、同模型重绑丢 READ 锚点、QEMU gateway 启动失败残留，以及显示 connector open 阶段超时/所有权缺口。均修复并补当前回归；最终显示连接器 4 负例全部进入完整 App 套件。固定提交终审随后发现 singleTask 编辑器未处理新实例 Intent；修复先退休旧访问范围再重建所选实例，并增加 5 项回归。最终 App 663 项全绿、APK/lint exit0，修复提交 `6bbc31c2`。
- 独立 Linux 9p 初始 harness 少列 MountOptions、额外 strict JVM fixture 的必要 deprecated/try override 警告均保留并定点更正；未降低 `-Werror` 或删除负例。
- Git 暂存会对默认 text asset 转换 EOL，故运行 hash/许可证 asset 单独保持 byte exact；9 项暂存 blob 与工作树原始字节实际相等，不改许可证内容取得通过。

## 设备结果

本轮起初 adb 无设备；构建后设备 MEY-AN00/API36 重新连接。重新预检：当前前台为系统 launcher，没有正在运行的 QEMU/gvproxy；主应用仍 code300/0.3.0-preview.1。实际读取安装的 base.apk 摘要 `53adad7f88e83359c721ed91f21a72a19dd86eeaa53ca7c910eddf7cd79d734b`，等于品质冻结 APK。

已安装主应用证书 SHA-256 `473783ce71218e02b322ef4d5440d7ef19328431921475b8d0bf5f45ecf3bde0`；新候选 Android Debug 证书 `a831ede3237180a5741c98fa3062f5dc9975d775ef3b6ba3ac49b517c485ac01`。签名不兼容，保留数据的覆盖更新不可行。按 prompt 不读取/复制 release signing 材料、不卸载主应用、不清数据/改系统确认策略，本次未执行 install，也未 force-stop/启动旧应用冒充新代码。

设备结论：**预检与身份/签名核对已运行；新 APK 安装和所有运行回归 NOT_RUN（签名不兼容）**。基础 Linux init/DNS/终端/POWER/冷保留、labwc 显示/input damage、普通用户服务与编辑器等都没有新候选设备 PASS。设备原实例、安装包与私有数据保持原样。明确证据 `delivery/device-result.json` 与 `device/preflight/`；公开报告使用稳定设备别名，不提交序列号或私有数据。

## 未运行、实验与未实现矩阵

| 范围 | 当前状态 | 具体限制 |
| --- | --- | --- |
| 新候选基础 QEMU Linux、终端 clear/READ/IME、POWER/冷保留、active DNS/forward | NOT_RUN on device | 主包签名不兼容；宿主/合成回归 PASS 不移作设备 PASS |
| Debian13 labwc CPU-copy、真实首帧及 input damage、CJK、旋转/context loss | 源码/宿主门 PASS；设备 NOT_RUN；experimental | 真实 capture→鉴权 RFB→当前 EGL/GL/swap→CONFIRM 门存在，但没有本轮 ARM64 Android acceptance |
| Debian/Ubuntu Podman、code-server 普通用户 profile | 完整 Linux fixtures PASS；真实 guest NOT_RUN；experimental | 普通用户/0600认证/锁与恢复门已实现，不把 install accepted/fixture 当容器与 IDE设备验收 |
| WebView login/WS/SW/cookie、LAN客户端/10min到期 | policy/lifecycle宿主门 PASS；设备 NOT_RUN | exact-origin/lease/owner门已实现；平台边界须真实设备验证 |
| OEM AVF custom VM/FD/冷恢复 | 官方 shape/负例宿主 PASS；OEM运行 NOT_RUN | 权限声明、预检不是 custom VM 可运行证明；AVF IDE forward 继续拒绝 |
| 标准只读 9p | Linux wire/process安全门 PASS；Android provider/mount NOT_RUN | 不具备安全 provider 时 ENOSYS，未绕过拒绝 |
| 精确 SAF 选集/USB FD | 原型模型/合成 I/O PASS | 生产 SAF broker、Android held-FD/VMM透传未接通；原型不挂成稳定能力 |
| GPU/buffer/fence、zero-copy | 未实现/现有 ACR 未闭合 | CPU-copy、host GLES、vsock字节通道均不等同 guest GPU共享buffer |
| KDE/niri、另五族增强 profile、Docker rootless daemon、USB原生透传 | 未实现 | 本轮只整合已有成果，未把 Phase5 愿景扩大为新增研发 |
| 七发行版长时间安装/服务/网络恢复/关机验收 | 用户取消，保持停止 | 本次133checks为本机合成矩阵，未重启原七族设备队列 |
| Windows guest-assets Linux-only 两项 | SKIP / NOT_RUN in that JVM suite | 另列 POSIX/Linux relay/9p实际结果；不篡改 suite skips |
| 发布签名、公开二进制release、对应源码公开交付门 | 未执行 | 本轮仅debug候选/普通源码分支push，未读签名secret或发布release |

## 临时资源、原来源与最终交付

profile worker 本批 39 个具名 child PID 均已不存在，active/zombie/固定 lock 为 0；POSIX helper 回收断言通过。设备没有本次临时 guest service、forward、lease；不建立后台验收队列。任务临时、合成输入与失败证据具名保留，未清理 Class A 资产；四个最终 guest基线门生成的合成 fixture 目录终态为 0。

原品质与 Phase5 来源工作树保留，原 source/index/drift校验由具名manifest核对；没有 reset/clean/stash/apply 到来源。外部 output/cache/temp 与短路径映射用于本任务，系统配置未改。未向参考仓写入、未触发新 Architecture Change Policy。最终独立审查结果和普通 push 状态由 [终审汇总](../reviews/quality-phase5-integration/review-report.zh-CN.md) 及外部 `delivery/push-result.json` 记录；本报告生成时尚待终审/push，未预称远端成功。

报告人：Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-14 ｜ 耗时：未单独统计
