# 新会话事实交接：剩余工单实施与真机 AVF 调试

编写：2026-10-16。Owner：主协调 Codex。配套执行文档：同目录 `AVF_SHIZUKU_REMAINING_PROMPT_20261016.zh-CN.md`。先完整读取本文，再完整读取配套 Prompt；由新会话执行剩余任务。

## 1. 用户最新目标与新增授权

用户要求：制作一份详细分工的 Prompt 和一份交接文档，随后新开会话完成本阶段剩余全部工单；用户现在连接真机，并明确表示“新工单允许激活并验证 avf 来进行项目的调试工作”。

这是新会话进行本机 AVF 准备、合法开发级权限操作、隔离 QA 实验与真实 Guest 验证的明确授权，不再沿用“没有设备/没有 AVF 调试授权”作为未经复核的停止理由。新会话应主动推进全部可解工作，包含研究票复现后必要的契约内产品修复；缺硬件/平台能力/架构裁定时给准确证据并继续独立任务。

授权内可做：重新发现当前设备/user、核安装/签名/实例、在保护数据前提下安装本项目同签名验证包或独立 QA 包、准备官方 Shizuku、请求本 App 的 client 授权、针对固定包/当前 user 合法授予并读回 MANAGE/CUSTOM、在新 QA 盘上创建/启动/交互/正常停止 AVF 与公平 QEMU 对照。不要为这些已经授权的常规步骤反复征求同一许可。系统配对、安装确认、指纹/密码等机主步骤由用户本人完成；拒绝和撤回应被尊重。

授权没有取消现有边界：不 Root/解锁、不改全局 hidden_api_policy/SELinux/thermal/系统分区、不降低 targetSdk、不放开私有目录、不把用户根盘复制公共目录、不清 App 数据/卸载以绕签名。新生产 VM owner/特权 IPC/独立常驻代理仍受 ACR-01；允许隔离验证不等于已批准全部代理产品架构。用户没有要求 commit、push、开远程 Issue/PR 或发布，不自动执行这些动作。

## 2. 本回合实际做了什么

本交接回合只读核对主仓、封板文件、设备连接与固定属性/包状态，编写两份文档；没有安装 APK/Shizuku、grant/revoke、配对、启动 App/VM、操作实例或跑 benchmark。设备发现启动了宿主 adb daemon（5037），没有修改设备安全设置。

只读平台结果采集时间：2026-10-16 16:19 左右（+08:00）。证据外部目录：

`Orchestella/artifacts/avf-shizuku-next-stage/handoff-20261016/`

其中 `device-readonly-preflight.json`、`current-user-package-query.json` 与 preservation 为 canonical。本报告不保存设备序列号/完整 ROM fingerprint；新会话必须重新发现，不硬编码本轮 serial。

## 3. 今日已核基线：不能只看 HEAD

| 项 | 本回合事实 |
| --- | --- |
| 开发根 | `PROJECT_ROOT`，本身非 Git 仓 |
| 唯一活动源码 | 开发根 `Orchestella_dev` |
| HEAD / branch | `428860ad9a1cb335f8be78c157b2ff57faf2a6da` / `main` |
| 分支/worktree | 1 个 main，1 个 worktree |
| 工作区 | **dirty，上一轮 0.5.0 的源码未提交；必须完整保留** |
| 封板比对 | 昨日 closeout 清单中的 36 个任务变更文件原 bytes/SHA 全部一致，无漂移 |
| Git 输出计数 | 当前 porcelain 34 项；含整目录折叠，不能误认为36份逐文件交付丢了2份 |
| 版本配置 | 上轮未提交工作区是 0.5.0/code500，HEAD 对应已发布0.4.5/code405 |
| diff 检查 | 本回合 `git diff --check` exit0 |
| W:/V: | **今日 subst 返回空，W:、V: 均不存在**；旧文档的“已映射”是昨日事实 |

新会话先查 git status/log/branches/worktree/版本与当前文件，识别后续变化，不 reset/clean、不 checkout 覆盖未提交内容。**从 HEAD 新建 worktree 会漏掉当前0.5.0全部实现**；不能当作正确实现起点。主仓单目录协作优先，不再生成大量长期开发分支/旧 merge_dev/phase5_* 仓。

若需 ASCII 路径，在确认 W:/V: 无占用后，以用户已授权的项目用途建立 W:→Orchestella_dev、V:→Orchestella外部根；有占用就核实，不覆盖别的会话。新证据不能写回旧 build 目录。

## 4. 当前真机：已连接，但 AVF 尚未验证

设备别名 `handoff-device-01`，本回合恰好1台adb状态device。型号 `MEY_AN00`（发现时），ROM build ID `HONORMEY-AN00`；当前Android user=0。与2026-10-15历史403取证设备不是可以默认相同的对象，不继承其grants/实例/ROM行为。

| 只读字段 | 观察 | 不能推导 |
| --- | --- | --- |
| Android / API | 16 / 36 | custom Java API可访问 |
| ABI / 页面 | arm64-v8a /4096字节 | Guest、16KiB或hugepages兼容已测 |
| AVF feature | true | 本应用VM可create/run |
| APEX包 | com.android.virt，versionCode=3 | 等于研究所用AOSP commit |
| protected声明 | true | 当前nonprotected custom Linux可用 |
| nonprotected声明属性 | unknown/null | 不代表false，也不代表true；必须实际API能力查询 |
| MANAGE/CUSTOM App权限 | unknown | 不能继承旧设备已grant状态 |
| 当前user包查询 | Orchestella与Shizuku管理器精确查询均exit0但无匹配 | 不证明其他user无包/无残留数据，不证明已安装签名 |
| Shizuku server/Binder | 未查询 | 不以管理器查询结果代替进程检查 |
| 实例、活VM、包事务 | 未查询 | 安装前还需核实，不可假定无数据 |
| AVF运行、Guest、性能 | NOT_TESTED；真实样本0 | feature/属性不能代替闭环 |

使用的是上一轮已实现 `tools/device-validation/avf_preflight.py collect`，11项固定查询完成，状态PREFLIGHT_ONLY。权限/API capability无typed probe，工具有意保持unknown。采集成功不是AVF通过。新会话应先确认仍为同设备/同user，若多设备显式选目标。

## 5. 必读材料与优先级

先读主仓 AGENTS.md、CONTEXT.md，再读：

1. `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/` 全部 Markdown policy，及 HOST_ENVIRONMENT_POLICY、ARTIFACT_ORGANIZATION_POLICY、ASSET_REUSE_AND_HYGIENE_POLICY、ARCHITECTURE_CHANGE_POLICY、REPORTING_STANDARD.zh-CN。
2. 本文、配套Prompt、`.scratch/orchestella-avf-shizuku/scope.md`、`map.md`、`spec.md`、17份issues；活动effort仍是这一份，不新起另一套tracker。
3. `docs/research/2026-10-15-avf-shizuku-upgrade.md`，优先其后续实施补记；`docs/reports/orchestration/avf-shizuku-next-stage-20261015.zh-CN.md`；逐票01/02/03/04/13实施与独立review。
4. 外部昨日 `closeout/delivery-manifest.json`、`final-audit.json`、`integration/*`；A/B/C/D分项研究按工作主题读取，不重新clone/重做全部调研。
5. ADR至少0001/0002/0003/0005/0007/0008/0009/0010/0011/0014/0016；涉及输入/图形/剪贴板时再读0012/0017/0018/0019，历史强断言与当前证据冲突按ACR处理。

两份旧AVF_SHIZUKU_NEXT_SESSION_20261015文档是上轮研究启动入口，现仅作背景；其中clean405/无设备/尚未实现client不再是最新事实。本交接、新查代码/设备与已接受契约优先，不能把旧Prompt重新执行为全新研究。

所有docs和.scratch多为local ignored；不要以GitHub clone假定存在，也不要整体force-add。缺失triage-labels/ADR0015/GPL_RELEASE_DELIVERY_POLICY已有16的历史来源保全，不要凭引用标题编造或静默恢复历史状态。主仓旧AGENTS中的v0.1.5及旧DroidLayer路径也不能当当前版本。

## 6. 十七份工单的真实进度

01–04、13已完成本地实现/验证，16完成来源研究：共6份完成其定义范围。11部分完成。其余10份未完成。总计17=16编号+ACR-01，不是17份已实现。

| 票/文件slug | 当前状态与已有内容 | 本次剩余交付/真依赖 |
| --- | --- | --- |
| 01 clean-guest-generation | completed | 保留回归；不重做生成闭环 |
| 02 honest-authorization-preflight | completed-local | 真机UI/两权限展示需本轮补验，不重造模型 |
| 03 shizuku-client-lifecycle | completed-local | SDK/provider/状态代码已有，真实拒绝/撤回/重启/OEM由06签收 |
| 04 safe-validation-tools | completed-local | 可复用工具；无物理真实性认证，生产recorder未齐 |
| 05 identity-fd-feasibility | blocked-device（旧记录） | **今日设备已到应重新claim**；身份、Context/state、合成FD及限制；不能只跑shape就完成 |
| 06 local-permission-device-acceptance | blocked-device（03已完成） | 当前user首次设置/client两权限grant/readback/revoke/partial/server death/OEM；受控成功后可细化grant产品实现票 |
| ACR-01 vm-ownership | needs-architecture-decision | 05证据后裁定VMowner/有限IPC/死亡关闭方案；用户仍是产品架构最终裁定者 |
| 07 real-avf-guest | blocked-architecture-and-device | 05+ACR+真实设备；完整custom Linux create/run/认证agent/命令/resize/services/normal stop |
| 08 bounded-readiness-and-stop | blocked-owner-and-device | ACR+07；总deadline、底层在途/迟到FD/取消/stop/旧回调；fixture部分可预先准备 |
| 09 real-performance-baseline | blocked-real-avf | 04+07+设备；同资源冷热、失败样本与指标，不能把回退混入 |
| 10 first-measured-bottleneck | blocked-measurement | 09之后只改首个有证瓶颈；无显著瓶颈可作证据化否决而非伪提速 |
| 11 exit-session-reproduction | reproduced-local，剩余门开 | 已有公共seam运行red；补实际调用链/exit23/异常/EOF/旧代/通知，成立后契约内局部修复 |
| 12 owned-console-log-reproduction | needs-runtime-evidence，无依赖 | 可立即合成sink运行取证，native/recovery/failed分开；成立后仅自有report/unit路由修复 |
| 14 physical-keyboard-reproduction | blocked-keymap-and-device-evidence | Android框架KeyMap/AltGr/死键/IME重绑与必要实体键盘；手机连接不等于外设到齐 |
| 15 wizard-recreation-reproduction | blocked-android-lifecycle-evidence | 可用当前手机或现有框架建立真正recreate反馈；草稿与view/model/summary/不重复create |
| 16 governance-source-recovery | resolved-source-preserved | 已完成，现行policy恢复若需要另裁定，不算本轮必做重写 |
| 13 local-integration-verification | completed-local旧批 | 本轮新实现后追加验收批次/报告，不拿昨日结果替新diff通过；不破坏旧封板 |

研究票11/12/14/15原范围是“先复现”。用户本次要求剩余全部工单实现，已授权在复现后继续必要的契约内修复：先在原票追加证据，并用新子票或明确验收增补承载生产变更，遵循一票一文件，从现有最大编号后递增、写出parent/边界/测试/回滚，别把未知现象预先标ready。ACR真实权限/架构变化不被研究票改名绕过。

## 7. 代码与已有反馈，避免重做

- 现普通client：`ShizukuClientController`、`AndroidShizukuTransport.kt`、`ShizukuClientDialog.kt`，两Activity/设置入口、精确provider/queries、About第三方许可。只client，没有grant执行器/UserService/VM代理。
- 两权限facts/probe/wizard已完成，UNKNOWN不当DENIED/GRANTED，旧单bool不绕CUSTOM；ADB模板两命令。真实UI卡片复查同一次facts，不重绑CPU/RAM。
- 同步grant或Binder wrapper不解除App hidden API；当前App AVF adapter依然普通Context直接反射。历史403两权限+1CPU失败是hiddenAPI→QEMU，405键盘/PATH走QEMU；500无设备闭环。保留版本与ROM边界。
- AOSP Java manager持久state在Context data/vm/name，路径型资产由调用进程打开FD；virtmgr客户端identity与真实UID/父PID相关。UserService“无普通non-SDK限制”不证明Context/私有盘/FD/owner可用。只研究最有依据路径，不同做五个完整产品。
- Java现adapter精确仅1CPU，nonprotected/setProtectedVm(false)；MATCH_HOST不代表用户任意核数。平台libavf/vm count接口不是普通NDK分发已证路线。
- custom Linux不应等待Microdroid payload ready；run返回/RUNNING/vsock连接各非Guest ready。AVF通道lazy，启动后的通道异常不自动回同步QEMU fallback。
- 平台connectVsock可能持与stop/close同一mLock；项目旧09 STOP/FD修复已做，不重复。caller timeout返回不代表native调用结束；未确认停止前不可同盘QEMU重启。
- 现AVF入站转发库未接产品路径，THP/balloon不能凭setter存在声称生效；新真实Guest验收要检查网络/服务等FullLinux必要面，不能只看到提示符。
- 11已有 `Orchestella/artifacts/avf-shizuku-next-stage/20261015/reproduction-11/`。actual Controller+MemoryStore、8精确生产源码+latch：A正常结束后B已RUNNING，迟到A afterGuestExit将B写STOPPED，真实exit1；对照exit0。上层Android可达顺序/Registry/通知/exit23等未测，别称设备bug已复现。
- 日志只能处理自有report/owned unit，保留失败、audit和用户文本，禁止App正则吞终端。
- 键盘唤起、PATH、单getty、终端应答/主题、SemVer修复保留；AltGr/死键/重绑先事件red，不失焦自动提交候选。

## 8. 可复用产物、工具与固定来源

昨日本地唯一验证包：
`Orchestella/artifacts/avf-shizuku-next-stage/20261015/delivery/Orchestella-Studio-v0.5.0-local-validation.apk`

106506176B，SHA256 `500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c`；包io.github.nyzeep.orchestella，500/0.5.0，有效证书SHA256 `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06`。未安装过本轮设备。现如只安装这份**相同bytes**可沿500；改变生产代码/许可/资源后新APK必须递增，bugfix通常0.5.1/501，新增VM代理等重要能力按MINOR评估0.6.0/600，首次构建前核版本，不先定死再混装。

已有715App、65Guest+10sync+6preflight、工具100、AVF20入口、lint0error/747warning和APK同证书/唯一guest_init/完整MIT均是昨日旧批结果，不能替本轮新diff通过。独立审查的三项P2已修复：工具Python地板/junction、授权卡片旧状态、readback成功后旧timeout。工具需Python3.12+且可调用Path.is_junction。

可复用源码：tools/device-validation/{avf_preflight.py,avf_benchmark.py,avf_evidence_io.py,test_avf_tools.py,README.avf-validation.zh-CN.md}；输出/receipt采用显式外部root，synthetic永不进真实AVF成功集。现生产没有完整recorder，缺事件不得手造JSON绿；先补受控插桩与真实性审查。

canonical身份原型 `prototypes/avf-shizuku-identity-probe/` 已aidl/javac编译，**非完整QA App**。inspect AIDL明确id0、destroy=16777114；后续装配独立QA包，先无VM/路径/FD接口的身份门，再受控扩展合成FD。研究旧source-only副本混合方法id编译失败的历史保留，别复制错版重做。

固定参考：Shizuku b844bc491f1790c72328e1a8e5b2349f8978f0ea；API a27f6e4151ba7b39965ca47edb2bf0aeed7102e5；AOSP Virtualization导出c984fc337c11ca5edc03ccf02037b2455dd8fcaf；base99b01a65cc4c104933788b3143285ab6bae65827；sepolicy d4a7f392598cee96d9479a8ac0f84259c19b043a。正式SDK为Maven api/provider/aidl/shared13.1.5、MIT，不从Git HEAD推发布版。clone/许可/大小/读过符号见旧E/research-auth、research-performance manifests。复用已有crosvm等，不无条件pull/重新clone。

## 9. 环境、批次与证据纪律

SDK D:/AndroidSDK，JDK D:/JDK17，adb ADB_PATH；Git Bash adb必须MSYS_NO_PATHCONV=1，避免/data路径改写。Ubuntu-24.04 WSL昨日可用，今日启动前复核，不重装SDK/WSL/依赖闭包。签名历史release.keystore与公开包不同，禁止自动接回；不读/输出密码或key。

昨日runner在旧E/run-gradle.py、build-output.init.gradle、run-guest-check.py可作参考，但它们**硬编码旧批次及W:/V:**，新会话应复制所需最小runner到新批次、重定向build/temp/evidence，不能原命令覆盖旧输出。统一Gradle/版本/APK窗口由主代理控制；模块源码冻结后才能构建。

新执行批次建议：`Orchestella/artifacts/avf-shizuku-device-20261016/<batch>/`（存在则用不覆盖的新标签）。正式研究更新旧canonical并注明新日期/证据，实施/原型/review报告沿现目录；tracker继续原effort。临时生成物与fixture均外部明确根；新证据不用.log，即时preservation，别复制token/embedded initrd/按键/原始口令、私钥/完整设备dump。设备serial运行时本地选择，交接用稳定alias。

保留SDK/NDK/JDK/Gradle/Maven缓存、kernel/rootfs、WSL沙箱、旧APK/对应源码、用户实例与原始证据。不得顺便清理/瘦身。引用既有canonical证据，不复制整仓/.git/历史包到agent或第三方服务。此回合未联网，新会话只为具体官方来源/合法依赖/QA所需端点联网。

## 10. 推荐执行顺序与完成标准

主代理先核新baseline/设备/user/安全状态，保护数据并建立串行设备队列；立刻启动05/06与11/12/14/15的独立可测工作。授权/AVF路线原型以最早门逐步证实，05成功后将ACR提交具体选择；ACR未决时继续独立修复，不全体等待。07后才拿真实样本做09/10，08底层收敛与stop门必须真实验，不用mock冒充。

“剩余全部完成”的定义：每票应有实际交付/正负测试/设备证据或准确不可消除blocker、中文实施与独立review；研究票转产品fix用自包含票承接；更新依赖图/frontier/状态并保留失败。仅文档/假进程/feature/权限绿不代表AVF成功。若本ROM非保护custom能力不开放，允许记录unsupported/blocked、保留QEMU和完成独立票；不得为追求全绿绕过平台安全或称全部17已实现。

最终需交付：逐票已完成/未完成/阻塞表；本机授权步骤及失败体验；本应用真实VM身份/Guest/bridge/终端/resize/网络/服务/正常stop；同资源基线/单变量优化或证据化不采用；回归、签名/版本/源交付与资产状态。commit/push/release仍需独立明确指令。

## 11. Suggested skills

先读用户指定开发根本地版本，再按需Skill：ask-matt路由；handoff跨会话；implement/implement-spec按真实frontier（默认commit/worktree流程按本次“保留未提交单主仓”约束调整）；tdd与code-review行为red→green/规范需求双轴；diagnosing-bugs用于11/12/14/15；prototype用于05；grill-with-docs仅真实ACR产品选择；to-spec/to-tickets仅补已获证据的派生实施票，不重建已完成tracker。android-emulator:android-dev用于设备操作，先核既有SDK/adb，不因插件缺路径重装。

没有要求动态工作流，不自动调用CreateWorkflow；“详细分工Prompt”指新会话委派，不是已经启动实施。

报告人：Codex / GPT-6.1 ｜ 日期：2026-10-16 ｜ 本回合只读核验与交接，无新生产实现。
