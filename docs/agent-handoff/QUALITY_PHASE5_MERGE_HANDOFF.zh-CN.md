# Orchestella 品质优化与 Phase 5 双会话合并交接

## 交接目的与权限

用户于本轮最后一条指令明确结束当前开发和七发行版验收，要求本会话核验返回的 Phase 5 交付，提供完整交接和详细合并 prompt，由一个新会话完成双线整合、验证及项目仓库推送。

本交接时没有执行代码合并、commit、push、远程 PR 或 release。继任会话获得的推送授权仅针对 Orchestella 第一方项目仓库；所有参考仓库继续只读，不得向它们的上游推送。

交接日期沿用本轮任务日期 2026-10-13；精确冻结时间见外部 JSON 的 `capturedAt`。主机跨午夜时不要凭文档日期推算当前时钟。

配套指令：[完整合并执行 prompt](QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md)。

## 一、必须先理解的 Git 状态

两个有效来源均为未提交工作树：

| 角色 | 路径（总目录下） | 当前分支 | 已核 HEAD |
| --- | --- | --- | --- |
| 第一会话品质优化 | `Orchestella_dev` | `main` | `8d0e8c75d769ecc645ea102b90a304c8657344a1` |
| Phase 5 规范集成来源 | `Orchestella_phase5_dev` | `phase5/graphics-productivity-storage` | 同上 |

两仓从该 HEAD 到当前 HEAD 的 commit 列表都为空。**`git merge phase5/graphics-productivity-storage` 不会取得其未提交成果**。不得 reset/clean/stash 或 checkout 覆盖任一来源，也不得用 Phase 5 工作树整树替换最新主仓。

Phase 5 曾在约 19:05 的时点复制第一会话部分活动源码作为依赖快照，后续第一会话又继续修复。Phase 5 patch 的 before 不是统一来自 Git HEAD；必须按文件的真实 before SHA 找基底。

用户在第一会话开始前已有一批未提交的构建主义 UI 改动。这些与本轮修复一起冻结，不能因不在 HEAD 中就当作可丢弃临时内容。

## 二、本地冻结交付位置

总目录：

`D:\Development project(开发项目)\Orchestella开发目录`

本次合并交接外部根：

`Orchestella\artifacts\quality-phase5-merge-handoff\`

### 第一会话完整生产/测试/工具源增量

`quality-main\quality-main-source.patch`：从共同 HEAD 导出的 tracked 修改，加上明确筛选的新增源码，使用 full-index/binary 格式。已只解析和核对路径，未应用到任何工作树。

- 76 个 tracked 修改、55 个新增源文件，共 131 个 patch 路径。
- patch 大小：1,446,461 字节。
- patch SHA-256：`bf95bab41a34e9ae5ccbc8dbd7d43b2a80ae32b6846b29f47da0cfb766719174`。
- `quality-main-freeze-manifest.json`：每个选中源码路径、状态、大小和 SHA，以及运行输入与 APK 摘要。
- `freeze-verification.json`：131 路径集合与清单一致、源码和输入摘要一致、明确未 apply。
- `SHA256SUMS`：patch、APK、公共运行与构建输入摘要；交接末只读复核14/14匹配。
- 交接末只读复核131个具名当前源码的大小/SHA均无drift；本轮未apply、未构建或重跑测试。

筛选排除了 `.git`、build/temp/cache、`__pycache__`、用户磁盘、每实例 embedded runtime、私有 QA 凭据。正式文档和 tracker 多数被 ignore，**不在此源码 patch 中**，继任须按本文引用定点复制到集成工作区。

### 第一会话运行资源与安装包

`quality-main\runtime-inputs\` 包含唯一公共基线运行输入：

- `initramfs.cpio.gz`：带本轮真实静态 ARM64 guest-agent 的基础运行包。
- `guest_init`：最终同步模板，含完整基线、受管仓库、恢复与手动诊断入口。
- `proxy_relay`。
- `runtime-artifacts-v1.sha256`。
- `NOTICE.GUEST-RUNTIME`。

这些是公共运行输入，不含每实例 bridge token/password。`quality-main\build-inputs\` 另冻结7项公共构建输入（Image、QEMU ELF、gvproxy ELF及QEMU输入/许可证/source-offer资料），owning target、大小与SHA见 `build-input-manifest.json`。runtime的5项与build的7项交付副本及当前输入均已只读核对。

AGP剥离gvproxy后，源码SHA `c1ea864636182a774235c8866c70f2a0223dfcd4f08d24e37a7e82d9882beb9c` 与APK SHA `0f21c658fb7870a95682c37784de24a12806b97bea49e6c0ae5a93fad66b5d37`不同；manifest已记录原因，不能误称二者字节相同。QEMU使用keepDebugSymbols，按各自输入记录验证。

**不要把 `device-smoke` 的 private 目录、root volume 或 credential-bearing embedded initramfs 纳入 Git。**

`quality-main\Orchestella-Quality-local-debug.apk`：当前已构建并安装的品质版本。

- 大小：72,403,761 字节。
- SHA-256：`53adad7f88e83359c721ed91f21a72a19dd86eeaa53ca7c910eddf7cd79d734b`。
- 主应用身份：`io.github.nyzeep.orchestella`。
- 版本：`0.3.0-preview.1`，versionCode 300。
- 本交接读取设备 base.apk 的摘要与该 APK 一致。它不是合并后 APK。

### Phase 5 交付

`Orchestella\artifacts\phase5-parallel-20261013\delivery\`

规范来源是 `Orchestella_phase5_dev` 和该 delivery，**不要再把五个 worker worktree 的 patch 重复合入**。以下文件必须由继任先核 SHA256SUMS：

- `phase5-integration.patch`。
- `phase5-source-manifest.json`、`build-lock.json`。
- `final-delivery-gate.json`、`patch-forward-replay.json`、`apk-content-verification.json`。
- `guest-agent.c`、`guest-agent-aarch64-musl`、`guest-init`、`runtime-artifacts-v1.sha256`、`NOTICE.PHASE5`。
- `public-references.json` 和 preserved 证据。

Phase 5 APK：`Orchestella-Phase5-local-debug.apk`。

- 大小：71,892,457 字节。
- SHA-256：`f58c865d8e83ef8c7629c9cf3ad7037e7f3b3a1e27d6452e7386847a2fed2623`。
- 实际 package：`io.github.nyzeep.orchestella.phase5`。
- 实际 versionCode 200、versionName `0.2.0-phase5-local`。
- 该隔离身份和旧版本不能覆盖正式主应用版本。Phase 5 基础 namespace/applicationId 仍为主包名，`.phase5` 是显式隔离构建配置产生的后缀；合流时需保留有条件的测试变体，正常构建不能默认带后缀。

交付逐项核验详见 [Phase 5 核验报告](../reports/orchestration/merge-handoff-phase5-verification.zh-CN.md)。

## 三、核验出的合并风险

完整路径和语义取舍详见 [文件级冲突地图](../reports/orchestration/merge-handoff-conflict-map.zh-CN.md)。本段只列决定合并路线的结论。

- Phase 5 的 141 patch 输入中，before 类型包括 tracked HEAD、captured live dependency 与新增文件。交付在锁定 synthetic baseline 的 replay 通过，**不能据此称它已能直接 apply 到最新主仓**。
- Phase 5 当前工作树带有大量从第一会话复制来的源码。不同于主仓的文件，既有真正 Phase 5 改动，也有主会话后续修复形成的旧副本漂移。不能把所有差异都视作需要合入的新功能。
- 主线 `BridgeClient` 已有 single reader、启动预算、首次事务 settle、每操作 HELLO/PING 认证证明、有界退休和恢复接口；Phase 5 有 FEATURES、DISPLAY/首帧确认等扩展。扩展必须接到同一事务/读取体系，不能重新创建第二个控制 reader。
- 主线 C agent 已实际实现 v1 POWER 派发、同 token 幂等 HELLO；Phase 5 有 DISPLAY/FEATURES/relay 等处理。必须合成一个源码后重新编译静态 ARM64 二进制，不任选一边旧 binary。
- 主线 init 保留真实 PID1、创建签名/校验、失败/timeout/持久化、受管仓库、Bash 恢复与 shell 命令分段；Phase 5 安装 profile/显示 relay/服务。应合并 canonical 模块后用 generator 生成，不对十几万字节的生成模板人工选整边。
- 两边 bootstrap/embedded 输入不同：主线保持 manifest SHA/大小与身份边界，Phase 5 引入 feature/profile 资产。不能为了打包新 profile 清空 checksum、信任未知路径或绕开大小检查。
- 主线 QEMU root disk 从 `cache=unsafe` 改为尊重 guest flush 的 `cache=writeback`。Phase 5 参数扩展不能回退持久化行为。
- 主线版本为 code300；Phase 5 代码/隔离 APK 仍在 code200。Gradle、manifest、applicationId、版本和来源 NOTICE 必须逐项整合，不能整个覆盖。
- 图形入口应由真实 guest 首帧、输入链与 profile readiness 共同判断。主线因旧模板画面而关闭未完成桌面入口；Phase 5 的真实 CPU-copy通路若合入，只有在合并后契约和运行探针通过后才开放对应组合，不直接翻所有发行版 capability=true。

## 四、第一会话最终进度（冻结时）

### 已实现与本地验证

- 原生 Linux init 保护、恢复 supervisor、幂等必需包/服务配置、完整 SHA/大小门、失败与超时状态、用户根盘/配置保留。
- 七族固定输入精确 SHA/size 与对应官方 ARM64 包集合核查；真实 Fedora/Leap/RPM/Arch 差异已适配。
- 每实例随机默认密码；旧/导入未知密码不静默重置；凭据日志脱敏。
- gvproxy 私有 Unix services API、完整 `/services/forwarder/` 路径、HTTP framing/超时/拒绝门、实际 TCP loopback应用与回滚、关闭后持久移除。
- 当前 Android 网络 IPv4 DNS 快照；尊重 active VPN/移动网络，不绑定到绕过 VPN 的网络；QEMU/AVF 接线一致。
- 原创持久 VT/UTF-8/IME/组合键/Tab/粘贴/选择、备用屏幕、命令高对比分段、长命令换行、累计淘汰行锚点。
- CLEAR 首排快捷键、显示 epoch、真实 HOME+J/default0清屏兼容、显式 FOLLOW/READ滚动状态。
- 控制单 reader、同 token HELLO、启动预算、首次事务 settle、每操作短认证证明，失败 reader 退役和 QEMU control-only前台恢复；AVF控制恢复仍明确不支持安全单通道重开。
- 真实主题、字号/间距/光标设置；去 App 强制引导；动态首页、真实运行策略/状态；图标居中和并排卡片等高。
- 新镜像装配内存门，Arch 大输入不再允许用户低内存静默启动。

冻结时读取的最后测试记录：

| 门 | 结果 | 说明 |
| --- | --- | --- |
| App 单元测试 | 628，失败0、错误0、跳过0 | 已有报告，不在交接阶段重跑 |
| Android lint | 错误0、警告669 | 警告仍在，不创建 baseline 掩盖错误 |
| guest-assets | 73，失败0、错误0、跳过2 | Windows 跳过两项 Linux-only 用例 |
| runtime contract/instance/engine-qemu | 最近对应 gate 日志成功 | 合并后必须重新跑全部适用门 |
| 七族/恢复/诊断探针 | 133矩阵、56恢复、后续定向测试 | 合成/本地结果不能替代设备验收 |

事实 JSON：`quality-phase5-merge-handoff\evidence\quality-main-progress.json`。完整测试日志在 `quality-20261013\evidence\`，终端/guest 专项证据在其 owning 子目录。

### 真机已取得的结果与仍未闭合项

设备为荣耀 MEY-AN00、API36；同轮 AVF预检声明/授权不等同 custom VM 服务可用，本机实际执行 QEMU。

| 范围 | 冻结时结果 |
| --- | --- |
| Ubuntu | 原生 systemd/cgroup2、DBus/SSH、包/UTF-8、DNS、冷重启文件保留、ready；真实随机密码SSH登录；界面正常 POWER ACK后VM退出和状态STOPPED |
| Debian | 七项实际 Linux健康探针全部通过；早轮最后关机遇旧agent POWER缺口，后已修源码/静态资产，未把那轮整批结果改成通过 |
| Fedora | 七项实际健康探针全部通过；旧POWER问题与恢复Bash修复有明确版本差异，最终关机整批复验未完成 |
| AlmaLinux | actual9.8；原根盘与缓存恢复后完整RPM事务、原生init/DBus/SSH/包/磁盘/ready六项通过，最终DNS为2，整批不算通过；后续默认网络切VPN，需重新验证而非猜Linux坏 |
| openSUSE | 原生启动、仓库刷新和显式恢复诊断成功；最新retry进入安装，用户结束任务时未完成服务/网络/关机验收，已取消 |
| Rocky | 原生安装链进入刷新，旧600秒预算失败；恢复/关机最终未完成 |
| ArchLinuxARM | 输入/内存/完整校验已核；大镜像解包在原盘继续，driver握手写压力导致失败；修后未重跑完，未宣称ready |
| 终端 | 命令高对比/空行、真实41列和IME尺寸、top/nano/重入；CLEAR直接按键清屏、正常关机已实测；HOME+J微修与长输出锚点最终组合实图还需继任复评 |
| 首页对齐 | 已安装APK实图核验；三按钮168×168设备像素、中心y=380，CPU/RAM卡片上下边线一致 |
| 图形/Phase5设备能力 | 本会话不替代Phase5验收，不能从基础Linux通过推断GPU/桌面/IDE/容器/USB/SAF都可用 |

七族验收**已按用户要求停止**。新会话任务是合并和适用验证，不默认恢复上述长时间测试队列。

### 已核但未实现的自定义身份缺口

独立只读核查确认：向导/creator保存用户名和主机名，但 `LaunchParameters` 没有它们；`QemuRuntimeBootstrapper.writeSegment` 传 hostname=null、guestUser=null、sudo=false；InstanceLifecycle 也未持久化 hasSudo。EmbeddedProvisionInput 和 guest安装端已有对应字段支持，缺在主机传递/持久化链。

这项没有写入半完成 API，也未实施修复。继任在合并 identity/profile/服务链时应单独补齐，兼容旧签名与未知旧密码，不用 root 的 PASS 声称自定义用户已验证。

## 五、Phase 5 的真实范围与验收限制

原报告：[Phase5实施](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/tickets/phase5-08/implementation-report.zh-CN.md>)、[验证矩阵](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/orchestration/phase5-verification-matrix.zh-CN.md>)、[集成指南](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/orchestration/phase5-integration-guide.zh-CN.md>)。跨仓引用采用明确canonical路径；继任仍须检查目标存在性与冻结摘要。

已交付的本地实现包括：真实服务帧/输入链和CPU-copy回退、token验证/Surface重附/resize、Debian13 labwc、Debian/Ubuntu Podman/code-server、默认回环开发服务和随机凭据、受限WebView、显式限时LAN、AVF API/权限/FD/停止/冷恢复、标准只读9p修复、USB FD/SAF数据原型。

未完成或未实测的部分包括：真实 ARM64 Android/guest显示/GPU、KDE/niri及其它发行版桌面、Docker rootless daemon、USB原生透传、生产SAF broker，以及完整Phase5服务设备验收。不能将CPU-copy等同GPU共享内存/零拷贝，不把原型挂成稳定能力。

Phase5本地测试与patch replay的粒度、旧24/25后定点修正边界，以核验报告为准。141文件的最新locked replay可证明其特定合成基底重放，旧129/141版本记录不是最终交付，也不是当前主仓重放结论。

## 六、环境与设备收口

构建与工具：

- 主仓 `W:`，旧别名 `O:`；Phase5 `P:`；Phase5外部资产 `Q:`；历史Gradle工具/cache `V:`。均已实测 subst映射。
- SDK `D:\AndroidSDK`，JDK17 `D:\JDK17`。
- Gradle工具 `V:\gradle-8.13\bin\gradle`、cache `V:\gradle-home`；项目和cache均需短路径，原中文括号路径会导致worker ClassNotFound。
- adb `D:\手机调试与开发相关\platform-tools\adb.exe`。
- Git Bash调用Android路径需要 `MSYS_NO_PATHCONV=1` / 对应工具处理。
- 静态guest工具链/provenance在 `quality-20261013\guest-toolchain\`，复用已验证musl+Linux UAPI+compiler-rt，不改成Android Bionic动态二进制。

交接前停止了经命令行/路径/hash/PID-starttime识别的自有host QA supervisor、运行中的自有QA进程与其转发。没有删除raw/cache、没有清除App数据、没有修改用户镜像。

收口证据：

- `evidence\qa-supervisor-freeze.json`。
- `evidence\qa-runtime-stop.json`：含逐slot检查结果以及最终ps记录；不能证明归属的条目未被定向终止，最终设备没有QEMU/gvproxy测试进程。
- 主已验证Ubuntu实例冻结状态：STOPPED。
- private QA凭据和根盘仍在各自原位置，仅供本地owner恢复/清理，不推Git。

继任如需真机验证先重新列设备、核对目标和是否有其它会话在使用。用户本轮已授权APK安装/调试，但用户不一定持续在屏幕旁；INSTALL_FAILED_ABORTED必须如实处理。该荣耀机 `adb install -r` 有时无可见确认而失败，随后将明确APK推 `/data/local/tmp/` 再 `pm install -r --user 0 <文件>` 曾成功；不要绕过系统确认或改安全策略。

## 七、推荐合并顺序

1. 核规则、冻结manifest、兩repo HEAD/dirty状态和所有SHA。source drift则先重新保全增量，不能用旧摘要静默覆盖。
2. 在新干净集成worktree从共同HEAD创建集成分支，不改两来源树；应用品质补丁并恢复公共运行输入、相关ignored文档；先跑其基线门并本地commit。
3. 读取Phase5每文件before类型/SHA，只从141规范交付取真正增量；先合独立新模块与契约，再合应用接线和guest canonical模块，最后资源生成/构建设置。
4. 对重叠文件按真实基底三路分析，保留两侧语义；不使用整树ours/theirs或自动删除测试。
5. 合成C/显示relay/profile安装源后实际重编guest二进制；生成init；重打bundle并更新大小/hash、NOTICE/源码出处；检查两执行策略共用一致资产。
6. 恢复主applicationId、保版本code≥300且遵守版本政策；隔离variant仅用于明确测试，生产UI按真正验证能力门。
7. 重新运行合并后的App/runtime/guest/profile/显示/服务/AVF/9p完整适用测试和lint。未授权或未实跑设备场景标未运行，不将旧报告复制为新PASS。
8. 两轴独立review，处理实际冲突与必要身份缺口；生成合并实施/验证报告与新APK/摘要。
9. 新会话按最新用户授权commit并普通push到核实的第一方origin集成分支；默认不force-push、不直接覆盖main、不发布release、不向参考仓库写入。remote/destination变化时停止并说明具体阻塞。

完整可执行命令、工单分工与终止条件在配套prompt中。

## 八、交接文件与建议 skills

canonical交接由本文件、完整执行prompt、Phase5只读核验和文件级冲突地图四份文档组成。按具名路径保存的字节一致副本、document manifest与校验和位于 `Orchestella\artifacts\quality-phase5-merge-handoff\documents\`，总入口为该外部根的 `README.md`。这些文档补充源码冻结后的说明，不改变131源码闭包或任一既有APK。

建议继任调用 `ask-matt`、`wayfinder`、`resolving-merge-conflicts` 和 `code-review`；实际Android/设备验证时调用 `android-dev`，最终交接可用 `handoff`。具体分工见配套prompt的skills与并行所有权条款。

本会话交付状态：完成两线核验、冲突分析与合并执行指令；没有实施合流或Git提交/推送。七发行版长测队列保持停止，原来源树、根盘和私有QA数据保留。
