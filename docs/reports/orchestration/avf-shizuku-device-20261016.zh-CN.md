# AVF / Shizuku 真机调试与剩余工单恢复编排报告

- 报告日期：2026-10-16；最后验收与交付收口跨至2026-10-17（本地时区 +08:00），沿用原批次名。范围：本轮 `batch-01 / resume-01` 已落盘实现、独立审查、本地集成、限定真机验收及最终交付核验；QEMU 新 Debian QA 真实结果见第九节。
- 唯一开发根：`PROJECT_ROOT`。唯一源码：其下 `Orchestella_dev`。活动 effort：`.scratch/orchestella-avf-shizuku/`。
- 分支与基线：`main@428860ad9a1cb335f8be78c157b2ff57faf2a6da`；0.5.0 至 0.5.3 全部未提交实现保留。主协调已重新核对 HEAD、分支、工作树及 `git diff --check`；没有建立其他 worktree、提交或推送。
- 证据简称：`E` = 开发根 `Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/`；`R` = `E/resume-01/`；历史 `H` = 开发根 `Orchestella/artifacts/avf-shizuku-next-stage/20261015/`。这些均为本地项目证据位置。

## 一、交付结论与背景

本轮已完成可独立推进的产品局部修复、有界离线工具、来源保全、本地集成和相应双轴审查。最终原包配置 App 为 **747 tests / 98 suites，failure、error、skip 均为 0**；lint **Fatal 0、Error 0、Warning 748**。原包与独立 `.phase5` 两份 code503 APK 已核版本、签名、唯一 Guest 入口、完整 MIT 声明和 pinned 输入。独立 `.phase5` code503 在真机完成 **8 项框架场景 PASS，包含七项向导和一项输入**。这些结果来自 `R/integration-503/` 三份原件。

QA4 隔离原型已完成普通权限 session 跨旧截止点、normal remove、App death、server death、官方重启并重新 bind 五项限定真机验收；固定 QA/user0 的 ADB 单项和双项权限对照、冷读回、逐项恢复全部通过。QA4 最终为两权限 DENIED、UserService 0、Shizuku server 1。**没有完整 typed grant/readback/同 session restore 软件流程成功证据。**

当前 ROM 的真实 UserService `getCapabilities` 为 bits=1、protected=true、nonprotected=false；与现有 nonprotected custom Linux adapter 不兼容。普通 App hidden API 门、UserService Context 访问 EACCES、合成 PFD 可用分别成立，不能相互替代。07、08、09、10 仍有平台、owner、架构或真实测量阻塞；ACR-01 仍待用户裁定。真实 AVF 成功样本为 **0**，没有性能提升主张，也没有新增生产 VM owner 或特权 IPC。

实际工单文件为 **01–20、22、ACR-01，共 22 份**；21 无工单文件。完成状态按各票范围分别判断，不将全部 22 票标为 completed。独立 `.phase5` code503 的新 Debian QA 已完成真实 systemd 启动和基础环境装配、journal/owned 日志观察、Guest 正常关机、App 正常关机与 QA-only 强制断电、重开后的持久标记核验。最终正常关机，QEMU0、owned 前台服务无，测试盘保留；限定范围和未验项见第九节。

## 二、授权范围、实际实现与保持的契约

已完整读取 AGENTS、CONTEXT、DEFENSIVE_CONSTRAINTS 全部 policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING、两份 20261016 剩余交接、resume dispatch、tracker closeout manifest/validation、当前 map/spec、13 报告及相关最终独立审查。旧文档的零设备、17 票、0.5.0、nonprotected 未知、502 待安装等描述保留其历史含义，当前结论取最新原件。

用户授权包括真机 AVF 调试、隔离 QA，以及 11/12/14/15 复现后的必要契约内修复。主协调独占设备、安装、权限、实例、Gradle、版本和签名窗口；实施与审查代理按有界目录协作。机主确认、首次配对、口令与系统确认仍由机主完成。调试授权不等于批准新生产 owner。

| 范围 | 已实际完成的内容 | 本次证据边界 |
| --- | --- | --- |
| 01 清洁 Guest 生成 | 源码 render、canonical 严格 bytes 检查、模板准备职责分离；先校 canonical 再生成，失败零写入 | 历史本地实现保留，本批 Guest/同步回归及 503 唯一条目复核；云 kernel/build/smoke 未由此关闭 |
| 02 诚实预检 | MANAGE/CUSTOM 分别显示 GRANTED/DENIED/UNKNOWN，两条 ADB 模板、精确单核限制和预检文案；刷新使用同一份 facts | 普通 App 权限、Shizuku 状态和真实 Guest 分门；不以预检 READY 声称 AVF 可运行 |
| 03 普通 Shizuku client | 官方 13.1.5 client/provider、可见动作授权、listener 生命周期与代际、旧 deadline 退役、完整 MIT 和 About 入口 | 未新增生产 grant、exec、UserService 或 VM 代理；设备产品 UX 由 06 单列 |
| 17 会话退出监督 | 异步 stop 捕获 expected session，错代拒绝；owner 注册后先建立 watcher 再更新通知；启动 admission 排他，不覆盖有效 generation | 三 P2 源码/JVM 与 503 集成通过；真实 QEMU 三种停止、持久性和最终 FGS 撤销已验，精确异常退出/竞态与通知完整矩阵仍开 |
| 18 自有装配日志 | owned firstboot unit 常规日志走 journal/owned 文件，failed 保留原操作码/state/sync 并一次 console 摘要，recovery/interactive 仍可见 | 五个受控 sink 及 Guest 回归通过；真实 Debian PID1/systemd、ready、journal 路由和日志可查已验，failed/recovery 与精确副本计数仍开 |
| 19 输入 | 保留右 Alt 布局、左 Alt/CtrlAlt 快捷键，死键组合；composition 只提交一次，连接退役后迟到输入无效，close 不提交候选 | 30 项本地回归及真实 Android 输入框架通过；右 Alt resolver 部分受控，实体布局/OEM 未验 |
| 20 向导草稿 | 版本化轻量草稿、模型/View/summary 一致恢复；坏 schema/distro/resource 拒绝；保留 creating marker 并拒绝重复 admission | 14 项模型回归及真正 Activity.recreate 通过；marker 不等真实下载/create 事务跨重建 |
| 04/22 验证工具 | 复用固定查询、路径/junction 拒绝、证据关联工具；增加 typed、错代拒绝、失败即停、有限容量离线阶段 recorder | 最终 127=原 100+新 27；输出 COMPLETE_OFFLINE / synthetic_seam，真实 receipt=0，生产 Android 接线仍开 |
| 05/06 隔离 QA | 身份、Context、合成 RO/RW PFD、能力查询；QA4 operation token 和底层 flight；ADB 权限控制及限定生命周期实测 | 不进入生产 grant/VMowner；真实 VM FD 入口、持久 namespace、软件完整恢复流程仍未闭合 |
| 16 来源治理 | 三份缺失治理文档的历史来源已研究并保全 | 不把历史 policy 静默恢复 active |

本轮未改变 Full Linux 策略中性契约、单运行实例、App 私有 raw 根盘、原 token 鉴权/单控制 reader、默认 loopback 和冷恢复语义。没有为了达到通过而修改 hidden_api_policy、SELinux、targetSdk、系统分区或盘访问权限，没有借 protected/Microdroid 结果替代 custom Linux。

## 三、逐票矩阵与剩余阻塞

以下状态对应当前 map/spec。原 `R/tracker-closeout/validation.json` 保留第一次文档收口事实，随后限定的 QEMU 真机增补由 `R/tracker-qemu-update/validation.json` 承载；旧封板不覆盖。局部实现完成、设备部分已验、外部门未验和平台硬阻塞分别保留。

| 票 | 当前状态与已交付 | 仍未关闭的门 |
| --- | --- | --- |
| 01 | completed；清洁 Guest 生成、严格 bytes 与本地双轴完成 | 云 kernel/build/smoke 不在本地完成范围 |
| 02 | completed-local；双权限/UNKNOWN、诚实文案与历史双轴完成 | 原产品设备授权 UI、权限 UX 在 06；不代表 AVF |
| 03 | completed-local；普通 client 生命周期、13.1.5/MIT 与历史双轴完成 | client 拒绝/记忆拒绝/管理器撤回、首次本机配对、reboot/OEM 与生产 UX |
| 04 | completed-local；固定只读工具、路径安全和结构关联完成，后续工具总数见 22 | 不认证设备物理真实性 |
| 05 | partial-device-verified；真实身份/Context/合成 PFD/capability、QA4 源码双轴及五项限定生命周期实测 | 跨 UID Binder 负客户端、真实 timeout/迟到 FD、跨升级 state/G4/owner；QA1 三源缺口 |
| 06 | partial-device-verified；固定 QA/user0 ADB 单/双权限控制与恢复通过，QA4 普通 session/death/rebind 通过 | typed 完整 grant/readback/同 session restore、拒绝/撤回/首次配对/reboot/OEM、原产品向导 |
| 07 | blocked-platform-and-architecture；本 ROM nonprotected=false 已有真实反证，无 create/run | 兼容平台、05/G4、用户 ACR 裁定；本应用真实 Guest/鉴权/终端/网络/services/normal stop |
| 08 | blocked-owner-and-platform；平台锁合成反证与候选停止模型 P2 后 5+9 组及双轴限定通过 | ACR/07、真实在途 connect、迟到 FD、停止/death 与盘 lease 收敛 |
| 09 | blocked-real-avf；04/22 工具已备，真实 AVF 样本 0 | 07 真闭环、公平同资源分组、生产阶段接线与真实正常停止 |
| 10 | blocked-measurement；没有优化或收益主张 | 09 先取得真实数据，再选单变量或证据化不采用 |
| 11 | reproduced-fixed-and-qemu-stop-verified；17 修复、503 集成/双轴、新 Debian QA 三种停止与持久性已验 | 精确异常退出码、wait 异常/错代/EOF 注入、Activity 重入/通知全矩阵、App 死亡后 VM 冷恢复及 AVF |
| 12 | reproduced-fixed-and-native-log-observed；18 集成/双轴，真实 systemd ready、journal/owned 文件可查 | native-failed/recovery、console 全程计数、逐 event 唯一性和完整编辑体验 |
| 13 | completed-local-and-framework；503 两包、747/98、lint 0 Error/748 Warning、8 框架场景完成 | 不替其他票关闭设备、架构、原包升级或发布门；不签整阶段全部完成 |
| 14 | reproduced-and-framework-verified；AltGr/死键反馈后由 19 修复，真实框架通过 | 实体国际布局键盘、OEM IME/焦点/锁屏/前后台 |
| 15 | reproduced-and-android-verified；真正 recreate red 后由 20 修复，七向导框架通过 | 物理 rotation/uiMode/OEM、真正下载/create 跨重建及进程死亡 |
| 16 | resolved-source-preserved；历史来源研究与保全完成 | 不静默恢复旧 policy 为现行规则 |
| 17 | implemented-integrated-reviewed；三 P2、503 集成与双轴完成，QEMU 三停止路径/持久性/最终 FGS 撤销已验 | 精确异常退出码、wait 异常/错代/EOF 注入、Activity 重入/通知全矩阵、App 死亡后 VM 冷恢复及 AVF |
| 18 | implemented-integrated-reviewed；五 sink、Guest 回归/双轴，真实 Debian systemd ready 与 journal 路由已验 | native-failed/recovery、console 全程计数、逐 event 唯一性及完整编辑体验 |
| 19 | implemented-integrated-framework-reviewed；30 本地、真实输入框架、D 五输入匹配与双轴完成 | 实体国际布局键盘、OEM IME、焦点/前后台/锁屏与实际用户体验 |
| 20 | implemented-integrated-android-reviewed；14 模型、七向导框架及双轴完成 | 物理 rotation/uiMode/OEM、真实事务跨重建/进程死亡；creating marker 不代事务 |
| 22 | completed-offline-reviewed；127=100+27 工具回归及双轴完成 | Android 时钟、生产 launch/Bridge/QA/services/stop 接线及真实 receipt 不在离线交付内 |
| ACR-01 | needs-architecture-decision；技术方案及真实平台/Context/合成 FD 反证已整理 | 用户未裁定新 owner/有限 IPC；兼容平台、G4、合法 state、秘密可读能力、死亡收敛、迁移/回滚仍需具体证据 |

当前 tracker closeout 已完成文档收口：**27 文档快照、22 文档变更、51 份显式源码输入前后不变**；真实工单数 22，map 链接覆盖精确集合、依赖图无环，research 与 13 只追加。该文档收口不构成源码测试重跑或设备全验。本文不再修改 scope/map/issues、13 或其他报告，最终交付审计 `final-delivery` 由主协调另写。

## 四、A–E 分工、恢复代理与审查影响

| 原分工 | 本轮实际职责与交付 | 恢复及协作影响 |
| --- | --- | --- |
| A：05/06 授权与身份 | 隔离身份/Context/合成 FD/能力与固定权限动作包；QA1→QA2→QA3→QA4 逐门保留失败 | QA4 旧 timer/finish 与后续 operation 混淆在新 seal 中修复；两轴最终限定通过。设备动作仍由主协调执行 |
| B：ACR/07/08 | owner/Context/path/FD 路线分析、平台锁及候选有界 readiness/stop fixture、ACR 具体约束 | 不因模型 green 增生产 owner；候选停止观测 P2 修复后定点复审通过，真实平台门保持 |
| C：11/12→17/18 | 会话退出监督、expected-session、admission、watcher 顺序与 owned 日志路由 | 原句柄中断后恢复代理完成缺失 final-freeze；保全 before/patch/source，新补来源绑定不倒填为中断前已存在 |
| D：14/15→19/20 | 输入编码/连接生命周期与向导草稿、模型/View/summary、真正 recreate | 五份冻结输入未变；502/probe3 新原件获独立增量签收，503 同输入再完成八场景，不重复计算 |
| E：04 复用、22、09/10 准备 | 有界离线 recorder、错代/失败/关闭/停止三事实门和 127 项工具回归 | 真实 AVF 闭环未有，09/10 不产样本；没有伪造 receipt 或接第二 reader |
| 主协调 | 设备/安装/权限/版本/签名/Gradle 串行、方案综合、最终验收和交付审计；用户保留 ACR 裁定 | 恢复后重新核设备/初态、恢复空闲 W/V 映射；已完成独立 Debian QEMU 启动、日志及三种停止验收，并接管网络故障代理的文档收尾 |
| 独立 Spec / Standards | 按冻结对象分别审需求与规范，保留首审 request-changes，修后定点复核 | 两轴不互读、不用实施者自审代替；恢复代理延续原件，仅补变更范围。本文汇总两轴，不构成第三次源码审查 |

恢复没有改从 HEAD 重建工作树，因那会遗漏 dirty 0.5.0 及后续实现。没有新增长期开发仓、worktree 或动态工作流。主协调重新建立失效代理后，C17 对 37 份显式 javac 输入补来源绑定，114 个 class 与原成功输出逐 bytes 相同；这是恢复后的新补证，原 34 项测试未重新执行，也不宣称原测试运行时已有该快照。

QA1 历史 `ProbeActivity.java`、`IdentityProbeService.java`、`IIdentityProbe.aidl` 三份精确原源码 bytes 缺失继续明示。QA2/QA3/QA4 的后续精确源码保全不能倒填 QA1，不能由 APK 反编译声称其已可精确重建。

## 五、测试真实性与独立审查

### 5.1 已运行集合及其用途

| 集合 | 实际结果与来源 | 不可外推的结论 |
| --- | --- | --- |
| 最终 App 原包配置 | `R/integration-503/tests-and-source-audit.json`：747/98，failure/error/skip=0 | 不与旧 715、746 或局部 34/30/14 相加；不证明设备 VM |
| lint | 同原件：Fatal0/Error0/Warning748 | 不是零告警；748 Warning 保留 |
| 最终冻结输入 | C17 新 seal 17 项、D seal 5 项均 all_match，无 mismatch | 只证明当前集成输入与已审冻结关联 |
| 本批 runtime | resume dispatch：contract/capability/AVF20 JavaExec/QEMU 共 64 任务 exit0 | JavaExec/合成平台不是 20 台或 20 次真实 AVF；不称 503 再全跑 |
| 本批 Guest | runtime65、sync10、Python 依赖门6，各自 exit0 | 不与 App/工具累计；云 kernel/build/smoke 未跑 |
| 最终工具 | 127=100+27，通过；22 双轴核原件和原始输出 | 结构/关联/离线标签不是物理认证或真实性能 |
| C17 局部 | 34 项 JDK、精确 Main 片段 red→green；javac/class 来源补证 | Android UI/FGS 边界合成，caller 返回不等底层 VM stop |
| C18 局部 | 五场景真实 report 子进程/受控 sink，正常及 failed 路由与原操作码23/state/sync | 不等真实 PID1/journald 或所有发行版屏显 |
| D19/20 | 30 输入/14 模型回归；503/probe3 八真实框架场景通过 | 不以框架事件或 creating marker 关闭实体键盘/OEM/真实事务 |
| QA4 | 同一 harness 旧 QA3 八失败加当前 deadline 对照通过 → QA4 九场景、110 断言全过；AIDL/javac/D8/aapt2 原记录均0 | 实际 Activity 配 JVM 队列/时钟/View/远端 Binder 替身，不认证目标系统精确在途交错 |
| QA4 真机 | 普通 session 8277ms、normal remove、App death、server death、官方重启 rebind 五项限定 PASS | 无权限变更、无 VM、未强制真实 B 在途 timer 交错 |
| ADB 权限控制 | CUSTOM 单项及逐次双权限 grant/cold-readback/revoke 全 PASS，恢复双 DENIED | 仅人工 ADB 控制与恢复，不等软件内 typed grant/restore UX |

隔离 `.phase5` 测试曾有唯一 BootstrapTest 原包名断言失败，原件保留；正式原包配置已真实通过。未修改测试隐藏包名断言，也不将编译阻塞或外层命令 exit0 单独当产品行为通过。八项框架由真实 Android instrumentation 驱动目标 `.phase5` 生产类，其中七项为真正 Activity.recreate、坏草稿和受控 creating marker，一项为真实 KeyEvent/KeyCharacterMap/InputConnection/View；这些均没有点击真实创建或运行 VM。

### 5.2 最终两轴结论及限制

| 审查对象 | 最终结论与正式原件 | 批准限度 |
| --- | --- | --- |
| 01–04/13 历史500 | 各票 `docs/reports/reviews/avf-shizuku-<票>/review-report.zh-CN.md` 双轴 approve；02/03/04 历史 P2 已闭合 | 500 当时本地范围；13 旧审查不是针对 503 的新整包独立 review |
| 05 QA4 | `spec-final-qa4-20261016.zh-CN.md`、`standards-final-qa4-20261016.zh-CN.md` 均限定 approve | S-06/S-A-03 冻结源码与实际 Activity/JVM 九场景；不签 typed 软件完整恢复、真实 deadline/death 或 05/06 completed |
| 08 | `spec-rereview-20261016.zh-CN.md`、`standards-review-20261016.zh-CN.md` 末节定点 approve | 候选停止观测模型 P2、5 新组+9 旧组；不签生产 owner 或真实停止 |
| 17 | `spec-rereview-20261016.zh-CN.md`、`standards-rereview-20261016.zh-CN.md` 均 approve | expected-session、watcher 顺序、admission 三 P2 的源码/JVM 与来源关联；真实 FGS/VM 门仍开 |
| 18 | `spec-review-20261016.zh-CN.md`、`standards-review-20261016.zh-CN.md` 均 approve | 三 Guest 输入及五受控 sink；后续 canonical/APK bytes 已由 503 原件另闭合 |
| 19/20 | 各票 `spec-rereview-20261016.zh-CN.md`、`standards-rereview-20261016.zh-CN.md` 均 approve | 原冻结源码与 502/probe3 框架增量；503 同输入框架复验由 integration-503 原件承载，物理/OEM/真实事务不在批准内 |
| 22 | `spec-review-20261016.zh-CN.md`、`standards-review-20261016.zh-CN.md` 均 approve | 离线 typed recorder 与最终127工具，生产插桩和设备真实性另验 |

QA4 固定 seal 为 `R/qa-deadline-fix/identity-v4-frozen-source.json`，SHA256 `6f83dcaffb72038776fd8088115aac93a6fdff0e0113a09590e73d36ec25af15`。两轴确认 operation 独立 token、旧 timer/finish 不退役新调用、迟到 snapshot 不重开已退役 session；全局 IN_FLIGHT 仍仅在真实底层返回后释放。当前 deadline、close、onStop、death 不假取消 Binder。QA3 原 request-changes、全部旧 red/green/seal/APK 与失败原件保留。

前期报告代理仅核已落盘原件，随后主协调接管最后的 QEMU 真机验收和本报告更新。各次测试、编译及设备动作以对应原件为准，没有将各审查者的有限 approve 合成覆盖所有产品与设备门的批准；后续文档增补未重复运行已通过的测试。

## 六、设备分门事实、权限恢复与未决架构

| 门 | 当前真实观察 | 结论 |
| --- | --- | --- |
| 普通 App hidden API | QA3 manager 构造入口不可用 | Android grants/client 授权不解除 App non-SDK 限制 |
| UserService 平台能力 | `E/device/qa3-manager-capability-receipt.json`：getCapabilities bits1、protected=true、nonprotected=false | 本 ROM 不兼容当前 nonprotected custom Linux；不是属性 unknown，也不是可改 owner 消除的门 |
| App state | 新私有 QA state 可写 | 不代表另一 UID 的持久 VM namespace 已合法可用 |
| UserService Context | `E/device/qa3-context-fd-receipt.json`：访问所给 App Context 目录 DATA_DIR_LSTAT EACCES | 不改 packageName 字符串模拟 owner，不 chmod 或拷用户盘绕过 |
| 合成 PFD | App→US 32B RO/RW 及模式/type/同 inode/replay/symlink/stale/closed 等正负门，receiver 副本关闭、caller 原件保留已实测 | 不证明 Java path-only/virtmgr/crosvm 实际 VM FD 入口、合法 state 或 VM owner |
| QA3 typed MANAGE | 曾实际 grant，随后 App/US 退出且旧 receipt 未更新；冷重开读回 partial，ADB 人工恢复 | 退出原因 UNKNOWN；不由 QA4 deadline 修复反推根因或完整恢复成功 |
| 恢复批权限对照 | `R/permission-control-summary.json`：初始两 DENIED，CUSTOM 单项及 MANAGE→CUSTOM 两项冷读回、反向逐项恢复全部符合 | 状态 PASS_ADB_CONTROL_AND_RESTORATION_ONLY，最终两 DENIED |
| QA4 普通 session | 同 epoch 跨旧5秒点，打开后8277ms仍可 readback | 真实普通 session 限定通过，没有设备强制 B 在途 timer 交错 |
| QA4 remove/death/rebind | normal remove；App force-stop 后 US 退出/server 活；server TERM 后 App 观察 death、US0；官方 starter 重启后 clientGranted/binder 读回并重新 bind | 五项限定 PASS，server death receipt 为 SERVER_DEAD_PERMISSION_RESTORE_NOT_CONFIRMED；不是 VM 死亡/软件权限恢复证明 |

QA4 五项与终态唯一汇总为 `R/qa4-device-summary.json`：permission_changes=[]、real_avf_samples=0、forced_timer_interleaving_on_device=false、最终双 DENIED、QA UserService0、server1。相关原件还记录 crosvm/virtmgr0；这只说明该 QA 窗口终态，不能预写主协调随后新 QEMU 窗口的进程状态。

ACR-01 用户未决。未来即使改用兼容 nonprotected 的平台，仍须先有 G4 实际 VM FD 消费入口、合法持久 state、实际 owner、有限 IPC/caller/代际、App/server 死亡收敛及迁移回滚证据。assembled initrd 的 RO FD 仍可能向接收进程暴露本轮 token/Guest 凭据；只读、禁日志与 close 不消除可读能力，必须纳入用户具体裁定。本文没有读取或复制相关载荷，没有替用户批准该能力。07→08 的真实 Guest/正常停止和 07→09→10 的真实性能依赖仍成立；06 不是人为新增的 07 强制前置。

## 七、版本、签名、源交付和 APK 身份

03 普通 client 新功能由 0.4.x 升为 0.5.0/code500；后续契约内 bugfix 迭代至 0.5.3/code503。各中间 501/502/503 与原件保留，不把不同 bytes 混用同一验收版本。公开历史发布物保持，本轮 APK 只供本地验证。

| 项目 | 原包验证产物 | 独立 phase5 验证产物 |
| --- | --- | --- |
| 文件（均在 E/delivery） | `Orchestella-Studio-v0.5.3-local-validation.apk` | `Orchestella-v0.5.3-phase5-validation.apk` |
| 包名 | `io.github.nyzeep.orchestella` | `io.github.nyzeep.orchestella.phase5` |
| 版本 | 503 / 0.5.3 | 503 / 0.5.3-phase5-local |
| 字节数 | 106520160 | 106520184 |
| SHA256 | `53a2c4947724a048101ba62918da44996204b827a0422c4d806f3a09860f2dd2` | `9a02ca4327fdf6ca5cc2bc55182e0899c8a4411a5909e655e2da0fec13ed6399` |
| 证书 SHA256 | `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06` | `a2001d21314616a7d475f442936da4c2d17423db22316336c84b8c60339b9669` |
| 保全 | preservation_equal=true | preservation_equal=true |
| 当前安装与用途 | 已核产物；未据此声称安装或原产品升级通过 | 本轮实际安装与八项真机框架验收；主协调另用此隔离包继续新 Debian QA |

当前设备原产品 code300 使用另一签名，且 OTHER_ANDROID_USER 数据保留；没有卸载或清数据绕过签名冲突。**原包 503 的产物核验和 phase5 503 的真实安装分别陈述**，隔离 QA 不证明原产品同签名升级、用户数据迁移或完整授权 UX。

两包的唯一 `guest_init` 均为 233837B、SHA256 `c8fbc1f8e4d76077385ad8a5e1027718615049042245cfcf802f039066461150`，与生成源码 bytes 一致；唯一 `assets/third-party-notices.txt` 为1531B、SHA256 `b3deed6613ab532486b327bf4cd36fa16931224fe7aee7109987a60f8806deb5`，与源码一致，包含 api/provider/aidl/shared 四项13.1.5和完整 MIT。未内嵌 Shizuku 管理器/server 或复制品牌资源。

pinned `Image`、基础 `initramfs.cpio.gz`、`runtime-artifacts-v1.sha256`、`libdroidlayer_qemu.so` 与历史500均同 bytes/hash，详 `R/integration-503/final-apk-verification.json`。本轮未换 GPL kernel/QEMU 等既有二进制输入，也没有新对应源码发布动作。当前源码交付是既有 main 上保留 dirty0.5.0→0.5.3、定点源码封板/补丁与实现报告，不能把基线 commit 单独当全部交付源码，也不能将仅有 manifest 当完整 GPL 对应源码。Guest 自有脚本已随源及唯一入口同步，后续若公开分发，仍须履行与该版本一一对应的完整源码及编译/安装资料交付门。

`final-apk-verification.json` 明确 uploaded=false、published=false。没有 commit、push、tag、Release、远程 Issue/PR 或新发布动作；本地签名有效与本地验证通过不等发布批准。

## 八、修改范围、证据保全与资产状态

报告代理最初创建本报告及 `R/final-report-draft/`、`R/preservation/final-report-draft/` 同字节副本，原始草稿及输入索引保持不变。随后主协调完成 QEMU 真机验收，并接管因 API DNS 失败中断的文档增补。接管前报告原字节保存在 `R/final-report-qemu/before/`；最终报告和本次补丁另存 `R/final-closeout/` 及其 preservation，不回写历史草稿副本。

`R/tracker-closeout/` 保留27份文档、51份显式源码输入的前后 bytes/SHA、patch、证据索引及 validation。后续仅QEMU事实的11份文档更新由 `R/tracker-qemu-update/` 承载，源码/测试输入再次核对不变；其原有before、旧封板和对应孪生均保留。最终 `R/final-delivery.json` 只引用明确的本任务源码、报告、验证包和已脱敏原始证据，不归档整仓或Git历史，也不包含签名密钥、用户根盘、bridge token、口令、embedded initrd或按键正文。

实际宿主变化为恢复空闲W:/V:到已批准项目目录、在批准的外部批次创建构建/测试/报告和独立QA测试签名材料、复用既有Gradle/Maven/SDK/WSL缓存。Android插件误识别已有工具链后改用已核验adb/SDK，不重装SDK或系统组件。独立QA签名材料只留本地批准目录，不纳入交付快照或报告；生产签名秘密未读取。没有修改全局hiddenAPI、SELinux、thermal、系统分区或目标SDK，没有清数据/卸载/修改其他Android user来绕签名，也没有commit/push/发布。

Class A 的 SDK/NDK/JDK/Gradle/Maven缓存、kernel/rootfs底包、WSL构建沙箱及历史物理原件保留复用，所有旧APK、对应源码、失败/首审/复审和QA原件均保留。没有清理用户实例或其他Android user数据，测试盘按明确用途保留。新原始证据使用 `.json`、`.md`、`.log.txt` 等扩展名并即时保全；已有历史 `.log` 原样保留。回滚只能依据各票精确before/patch做有界恢复，不reset/clean源码或用删除数据获得通过。

## 九、独立 Debian QEMU 真机验收：限定通过

**状态：PASS_LIMITED_REAL_QEMU_NATIVE_AND_STOP_ACCEPTANCE。** 主协调已在独立 `.phase5` code503 中创建并验收唯一新测试实例 `qa-qemu-503-01`，没有操作原产品实例。唯一摘要为 `R/qemu-device-summary.json`，列出19份原件的路径、字节数及SHA；所有原件和摘要均有即时保全。实际资源为Debian13、1vCPU、801MiB RAM、4GiB新raw盘，仅终端、不共享宿主目录、不执行可选升级。实际App显示QEMU软件虚拟化，并观察到该App UID下的QEMU子进程；没有将其计入AVF样本。

App私有缓存的rootfs实核为90351092B，SHA256 `000f18fbcea39b276754dee7a1972df357f53329371c2df477e6504e15ed2907`，与当前清单一致。旧宿主同名缓存大小/hash不符，未冒充复用；实际下载命中端点未完整观察，不把配置URL当实际来源观测。原始首轮解包心跳、native启动及后续运行记录保留，没有重复创建或把正在写入的盘交给另一VMM。

| 真机门 | 实际结果 | 原件 |
| --- | --- | --- |
| Guest身份及基础环境 | UID0、PID1=systemd；status=ready、stage=verified、attempt=1、operation_exit_code=0，done存在 | `R/qemu-device/status-observed-01.json`、`units-observed-01.json` |
| owned unit日志路由 | Result=success、ExecMainStatus=0、ActiveState=active/SubState=exited；StandardOutput和StandardError均为journal | `R/qemu-device/units-observed-01.json` |
| journal及owned文件 | 当次owned marker在journal和文件中均可查；audit/kernel输出继续保留 | `R/qemu-device/journal-observed-01.json`、`owned-log-observed-01.json` |
| Guest正常关机 | 通过原终端执行`/sbin/poweroff`，QEMU子进程退出，界面为STOPPED | `R/qemu-device/guest-poweroff-cycle-a.json`、`R/device/qemu-cycle-a-after-poweroff-01.json` |
| App正常关机 | 从同一实例卡片选择正常关机，QEMU退出，实例显示已停止 | `E/device/qemu-final-app-graceful-selected.json`、`R/qemu-device/qemu-app-graceful-result-01.json` |
| QA-only强制断电 | 仅新QA、ready且无装配事务时确认断电；QEMU退出，持久状态STOPPED、owned前台服务不存在 | `E/device/qemu-final-confirm-force-stop.json`、`R/qemu-device/force-stop-state-selected.json` |
| 重启及数据保留 | 正常停止和强制停止后重开同一实例，标记均为`ORCHESTELLA_QA503_PERSIST=1`；force后停机记录与重启后最终停机记录的实例ID及根盘引用hash相同 | `R/qemu-device/cycle-b-persist-observed-01.json`、`after-force-marker-observed-final-02.json`、`final-terminal-state-audit.json` |
| 最终收尾 | 再次正常poweroff；实例STOPPED、QEMU0、owned前台服务无，QA盘保留 | `R/qemu-device/final-clean-poweroff-confirmed.json`、`final-terminal-state-audit.json` |

限定：成功停止的精确owner exit code未独立记录，不能由UI/EOF倒推为0；force后界面复用“guest正常关机”文案，只签有意停止和底层退出，不称Guest进行了graceful shutdown。没有完整统计routine console副本数或逐report唯一性，owned文件重复repository-refresh阶段名不能当同一event重复。native-failed一次摘要、无journal recovery、完整编辑体验、真实wait异常/错代/EOF竞态注入、Activity重入及通知全矩阵、App进程死亡后的VM冷恢复仍未验。卡片GPU标签不是GPU运行证据。

这些新结果仅缩小11/12/17/18的QEMU设备剩余门；07/08/09/10的平台、owner、ACR及真实AVF测量阻塞不变。后续11份文档增补由主协调接管DNS失败的文档代理完成，精确before/after和patch在`R/tracker-qemu-update/`，不覆盖原`tracker-closeout`封板；51份定点源码/测试输入再次核对不变。

## 十、风险、后续门与架构检查

剩余验收项为：完整软件 typed grant/readback/同 session restore；首次本机配对、拒绝/记忆拒绝/管理器撤回、设备 reboot/OEM 和原产品签名升级；跨 UID Binder/真实在途 deadline/迟到 FD/state 升级；精确异常退出码、wait 异常/错代/EOF 的设备竞态注入、通知完整矩阵和 Activity 重入、App 进程死亡后 VM 的真实冷恢复；native-failed 一次摘要、无 journal recovery、routine console 全程计数/逐 event 唯一性及完整编辑体验；实体键盘/OEM IME/物理 rotation/uiMode；真正下载/create 跨重建/进程死亡；兼容 AVF 平台/G4/用户 ACR 及其后真实性能。真实 Debian PID1/systemd、基础环境 ready、journal 路由、三种 QEMU 停止路径及重开持久性已按第九节限定签收，不再列为未验。

风险集中于证据误用：把 shape/grant/合成 PFD 当 VM，把原型 US death 当 VM stop，把 JVM 合成或 creating marker 当系统/事务完整验收，把 502 独立审查扩为 503 整包批准，把 QEMU 结果计入 AVF 成功集，以及用后补源码倒填 QA1 历史缺口。本文逐项标明层级、版本和未验范围，以可追溯原件限制结论。

已完成产品修改属于现有契约内局部 bugfix；文档同步不新增权限、owner、IPC、数据面或暴露面，不触发新的架构实施。ACR-01继续有效，只有用户针对具体owner/FD/秘密可读能力及死亡收敛方案的裁定才可能放行后续实现；当前ROM不兼容nonprotected的硬门也不会因批准代理架构而消失。没有commit/push/发布、资产清理或用户数据删除。

最终交付入口为 `R/final-delivery.json`，其审计读取本任务明确的源码清单、构建XML/既有原件、APK签名及固定ZIP条目、最新工单状态和设备终态；校验结果及精确任务源码/文档快照保存于 `R/final-closeout/` 和对应preservation。若审计未通过，不以报告正文替代通过结果。保留限制：QA1三份原源码历史缺口无法倒填，原产品签名升级未验，软件内完整grant/restore及外设/故障场景未全通，真实AVF与真实性能样本仍为0。

报告人：主协调 Codex / GPT-6.1（接管最终编排报告代理并完成QEMU验收与文档收尾） ｜ 日期：2026-10-17 ｜ 耗时：见各批次原件
