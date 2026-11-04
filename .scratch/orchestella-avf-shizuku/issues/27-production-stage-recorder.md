# 27: 真实生产启动阶段与停止来源接线，先QEMU后扩AVF

Type: implementation
Status: ready-for-agent（现有QEMU/owner不变的范围）
Priority: P1
Owner: recorder与各source模块owner协作；构建/设备主协调统一
Blocked by: 22（completed-offline-reviewed）
Conditional gates: AVF真实事件必须来自相应已合法运行的owner；未过07不产AVF成功receipt
Parent: 22离线工具的生产纵向切片，复用04/09样本边界

## 用户价值、来源与交付

能知道一次真实启动/失败/停止在哪个阶段耗时或中断，为稳定性排错和后续优化提供原始依据；不再把离线COMPLETE_OFFLINE标为生产完成。来源：22报告、详细研究的阶段/原reader边界、近期方案§4/7。

先打通一条现QEMU用户启动→输入准备→进程owner→原认证Bridge/终端/已能观测的服务→正常或异常停止的版本化本地事件闭包。它本身可独立验，不等待pVM。AVF只在既有已合法owner/接口处增加同口径可观测事件；模式/平台失败也准确记last-stage，不隐式尝试VM或更改launcher选择。

## 允许范围与非目标

允许新的小型event组件/有界sink与真实触发点、现bootstrap/launcher/监督/Bridge handler最小observer、离线工具兼容及行为测试。模块共用文件由原owner写锁协调。禁止新VMowner/权限/exec、修改Guest命令集或偷偷新增QA命令、第二reader/解析任意终端正文、全局诊断/上传、逐字符同步落盘、大规模性能重构。

现真实pipeline的顺序先核：例如bootstrap可能在adapter模式/API检查前；22旧schema若假设preflight全先于inputs，必须诚实版本化event偏序/来源适配或标缺失，不回填过去时间/伪排序。不把CLOCK_MONOTONIC、JVM/Python clock、Guest uptime和Android elapsedRealtime混减。

## 行为验收

- [ ] 显式诊断关闭时零持久采集/文件/queue/重试，不影响Guest正常执行；启用sink条数/字节/保留范围/超限策略明确。
- [ ] 同一次launch/source/APK/ROM alias/请求与实际策略/owner/session/generation、Android单调时基和真实last-stage绑定；rawexception/path/口令/token/候选/按键/metadata/embedded载荷不入事件。
- [ ] 成功、bootstrap校验失败、API/权限/模式拒绝、QEMU回退、cancel、迟到错代、sinkI/O失败及容量满保持真实结果，sink失效不假ready、不阻塞owner收尾。
- [ ] 原Bridge唯一reader和鉴权前置不变，stage来自原handler/owner信号，不抢读；没有固定QA挑战接口时记not_observed，不按终端提示符制造guest_command_ok。
- [ ] run返回、VM状态、通道连上、agent认证、终端命令、服务ready分别记录；customLinux不等待Microdroid payload，未知不填默认成功。
- [ ] stop意图、owner底层退出、资源/FD释放三事实独立，EOF/FGS通知结束/退出码0不能单独签normal stop。
- [ ] 新QA QEMU正常与至少一个受控失败有真实事件原件，非所有字段必须先有；缺必需设备证据的样本结构校验器拒成功组。synthetic永久单列、工具不认证物理真伪。
- [ ] 开关/事件接线开销记录方法明确，未测不称零开销；不报告硬件提速或未采CPU/RSS/GPU指标。

## Seam、red命令与设备门

优先现launch sequence、bootstrap、Bridge公共路由+注入clock/sink；red覆盖真实阶段/失败/关闭行为，green只加observer。App测试、runtime:contract:check、相关AVF/QEMU契约和Python recorder/validator按改动实际执行，确认JavaExec入口不空跑。真实QEMU新QA盘/无事务窗口；AVF样本/目标QA挑战/服务信号未闭合时不作为本票已有能力。

不要为了让全部字段齐而创建新Guest bridge命令：若确需命令版本扩张，另票遵循协议协商/ADR与scope后处理。本票可以完成真实阶段/缺项诚实闭包，不伪造09的完整benchmark前置已满足；09仍需真实AVF、完整来源/正常stop/资源工作负载。

## 回滚、兼容、许可与证据

事件schema有版本且旧synthetic输入历史保留；新旧工具需明确拒不兼容而非静默reinterpret。无用户盘格式/实例迁移，不改变策略结果；rollback定点observer/sink，不清旧证据。依赖标准库/现Android API，自研，不新增遥测或第三方服务。

报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，双轴review同id；外部27专属批次/真实receipts与preservation。当前22仍只离线完成，本票仅建立尚未实现；旧127项不计本票已通过。

## Comments

- 2026-11-05 / ZCode（票据收尾 lane I）：**状态核实——未实现，维持 `ready-for-agent（现有QEMU/owner不变的范围）` 不变**。核实依据（勿信票面 Status，本次为独立对账）：
  - `docs/reports/tickets/` 下无 `avf-shizuku-27/` 目录（已实现票 01/02/03/04/07/08/13/17/18/19/20/22/23 均有对应实施报告）；
  - git log（含 `--all`）无任何以本票为对象的 commit，无「生产阶段事件接线 / production stage recorder」相关实现提交；
  - `map.md` 交付矩阵与本票票面末行自述一致：「22离线127通过不等27已接生产」「本票仅建立尚未实现」；
  - 2026-11-04 reconciliation snapshot（`docs/agents/issue-tracker.md`）将 27 列为 still-open ready。
  - **处置**：QEMU 先行切片可零设备完成，符合 ready-for-agent 口径，故不改 Status，追加如下开工说明。AVF 侧样本与「新 QA QEMU 真实事件原件」仍属设备门，agent 不得伪造（见阻塞边）。

- 2026-11-05 / ZCode（票据收尾 lane I）：**Agent-ready 开工说明**（供 `/implement` 直接领取；范围以票面「允许范围与非目标」为准，此处只做可执行化展开，不放宽任何非目标）。

  **What to build**（生产侧事件闭包，全部在现有 QEMU pipeline 的真实触发点上挂 observer）：
  1. 新建小型 Android 侧 stage 事件组件（app 模块内，风格对齐 `RuntimeProcessSupervisor` 的纯 JVM 可测组件）：固定枚举 Stage/Route/StopFact/Failure 对齐 22 号离线 recorder（`tools/device-validation/avf_stage_recorder.py`）的 16 门口径，事件有界（条数/字节上限与 22 的 16 事件 / 32768 bytes 同量级即可，超限策略显式），单一 Android 单调时基（`SystemClock.elapsedRealtimeNanos()`），禁止与 JVM clock/Guest uptime 混减。**不得**把 Python 组件直接接 Android 冒充原始采集器（22 报告已划界：生产跨线程/进程适配须另验）。
  2. 有界 sink：显式诊断关闭时零持久采集（不建文件/queue/线程）；开启时写 App 私有目录版本化 JSONL，schema 带 `schema_version`，旧 synthetic 输入历史保留、不兼容时显式拒绝而非静默 reinterpret。sink I/O 失败/容量满 → 事件组件显式退役（对齐 22 的 SINK_FAILED 语义），不阻塞 owner 收尾、不假 ready。
  3. 真实触发点最小 observer（只加观测、不改控制流）：`QemuRuntimeBootstrapper`（bootstrap 校验失败）、`InstanceStrategyLauncher`/`QemuInstanceLauncher`（run 返回、QEMU 回退）、`RuntimeProcessSupervisor`（owner 底层退出、资源/FD 释放）、`BridgeConnector` 现有公共路由（通道连上、agent 认证、终端命令、服务 ready，stage 取自原 handler/owner 信号，不抢读、不新增第二 reader）、`ExecutionSession`/`RuntimeOwnedSession`（stop 意图、迟到错代、cancel）。stop 三事实（意图/owner 退出/资源释放）独立记录，EOF/退出码 0 不得单独签 normal stop。
  4. last-stage 诚实化：bootstrap 校验失败、API/权限/模式拒绝、QEMU 回退、cancel、迟到错代、sink 失败、容量满均保留真实终态；无对应信号的阶段记 `not_observed`，未知不填默认成功；rawexception/path/口令/token/候选/按键/metadata 不入事件。
  5. 离线工具兼容：`tools/device-validation/` 的 recorder/validator 侧补新 schema 的结构校验（缺必需字段拒成功组）；synthetic 永久单列。

  **Acceptance criteria**（agent 可零设备自验子集，全部 JVM/宿主测试可覆盖）：
  - [ ] 诊断关闭时零持久采集：单测断言关闭态无文件创建、无线程、无队列；开启态限额与超限策略有测试。
  - [ ] 阶段事件按票面「行为验收」第 5/6 条分别记录（run 返回/VM 状态/通道/认证/终端/服务 ready；stop 三事实独立），red→green，clock 全部注入。
  - [ ] 失败路径 last-stage 真实：bootstrap 失败、权限拒绝、QEMU 回退、cancel、错代、sink 失败、容量满各有行为测试，断言不假 ready、不阻塞收尾。
  - [ ] schema 版本化：旧 synthetic 输入被新版工具显式拒收或明确版本分流的测试；不回填时间/伪排序的偏序断言。
  - [ ] 敏感字段不泄漏：构造含 rawexception/path/token 形态的输入，断言事件中不出现（可复用 `tools/ci/check_sensitive_data.py` 思路做组件级断言）。
  - [ ] `:app:testDebugUnitTest`（含新测试类）、`:runtime:contract:check`、按改动实际涉及的 `:runtime:engine-qemu:*` 契约测试与 Python `unittest` 全绿，JavaExec 入口不空跑；Gradle 实跑须在 ASCII 路径 worktree（中文路径 Test 任务坑见 zero-device-closeout-20261104 报告）。
  - [ ] 实施报告 `docs/reports/tickets/avf-shizuku-27/implementation-report.zh-CN.md`，双轴 review 同 id；未测项如实标 `not-tested`，不得以 JVM 证据冒充真实 QEMU 事件原件。

  **阻塞边**（agent 不可跨，留给设备窗/维护者，不阻塞上述切片开工）：
  - 「新 QA QEMU 正常 + 至少一个受控失败的真实事件原件」需真实 QEMU 运行窗口（设备/主协调统一核初态/盘/事务），agent 只能交结构校验器与 not_observed 占位，不得伪造 receipt；
  - AVF 同口径事件依赖 07（真实 AVF guest）未过，本票不产 AVF 成功 receipt；
  - `BridgeConnector` 等模块共用文件的改动须按票面与原 owner 走写锁协调，由主协调分派，不抢文件；
  - 若确需 Guest bridge 命令版本扩张，另票走协议协商/ADR，本票禁止。
