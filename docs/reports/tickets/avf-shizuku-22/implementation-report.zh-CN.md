# 工单22实施报告：有界离线 typed 阶段 recorder

- 票号/状态：avf-shizuku-22；**ready-for-review**。本地实现与回归已完成，独立规范/Spec审查由主协调另派，未自审签收。
- 执行：Worker E / Codex GPT-6.1-sol；日期2026-10-16。
- 基线/分支：主协调给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，dirty0.5.0/code500旧36SHA全部一致；并行其他owner改动继续保留。E未执行Git写操作、commit/ref/worktree/reset/clean，commit列表无。
- 外部专属根：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/benchmark/`，下文简称B。原件即时preservation，Class B长期保全至维护者验收归档，无自动清理、无外发。

## 1. 背景、scope与先证据后建票

Worker E按dispatch-scope、全部指定policy、两份20261016交接、scope/spec/04/09/10、现工具与固定B/D研究执行。04已有100项可信结构校验；现App没有完整16门recorder，不能手造成功receipt。主协调确认本轮先做独立工具/合成行为，不全面生产插桩。

本票在实际red-disabled缺模块、green-disabled以及red-marker行为失败、green-marker之后建立。范围只含tools/device-validation的新独立组件/测试、原README与04/09/10补记和对应中文报告；不改launcher/MainActivity/Guest/输入/版本/VMowner，不运行adb/UI/Gradle/设备/网络/Git写操作。原owner最小插桩包已提前通过协调通道交主协调，并定稿在B/owner-instrumentation-packet.zh-CN.md。

## 2. 实际完成与验收

| 验收 | 实际完成与证据边界 |
| --- | --- |
| 复用04 | 新组件复用avf_benchmark.CHAIN和alias，固定16门；JSON与实际输出sink复用avf_evidence_io，不新增第二套preflight/validator/统计器。原100项相关5个Python文件未改。 |
| 关闭零采集 | 不保存identity/clock/sink、不读clock、不调用sink，不建文件/队列/线程；所有操作DISABLED，外部目标根不存在。 |
| typed身份与时间 | CaptureIdentity frozen/slots，owner/session/generation/VM严格类型与有限alias；错身份在读clock/sink前拒绝。时间非负int64、不倒退。默认python_monotonic_ns、注入injected_monotonic_ns，永不标Android时钟。 |
| 固定字段与限额 | Stage/Route/StopFact/Failure固定枚举，无任意payload接口；最多16事件、JSON累计最多32768 bytes，仅有限计数与状态保留内存，不缓存事件/建队列。 |
| 失败/错代/迟到/取消/fallback | 首个失败终结当前trace，迟到不恢复；错代操作不破坏当前trace；cancel/fallback/fail保留固定终态/原因，不能完成成功路径。 |
| 认证路由 | agent/QA/services只允许AUTHENTICATED_SINGLE_READER标签，owner标签拒绝；不打开任何stream、不解析用户正文、不添加reader。标签仅接线检查，不能证明实际认证。 |
| 停止收束 | STOP_REQUESTED之后分别observe_stop：认证路由guest shutdown ack、owned VM停止、owned资源释放；缺任何事实或错顺序/路由拒绝STOP_COMPLETED。事实均合成调用者输入，不当作物理正常停止。 |
| sink失败 | 抛错或显式返回False以SINK_FAILED退役，无自动重试/队列、不暴露异常详情，旧证据不覆盖；本地EvidenceStore输出保持独立preservation。 |
| 非真实性 | trace固定kind=offline_stage_trace/provenance=synthetic_seam；完成仅COMPLETE_OFFLINE，real_receipt_count=0、device_verdict=NOT_DETERMINED，kernel=not_observed、payload_callback=not_applicable；无receipt/manifest/samples导出器。 |

## 3. 修改文件与重要取舍

- `tools/device-validation/avf_stage_recorder.py`：离线StageRecorder公开seam、身份/路由/时钟/有限状态与限额。
- `tools/device-validation/test_avf_recorder.py`：最终27项公开接口正负回归；真实文件sink仅使用本批专属外部根。
- `tools/device-validation/README.avf-validation.zh-CN.md`：使用/边界/复验、本轮设备状态历史更正、生产剩余门。
- `.scratch/orchestella-avf-shizuku/issues/22-offline-typed-recorder.md`：证据后建立的工具票；04/09/10仅追加本轮事实，04仍completed-local、09/10仍blocked。
- 中文报告：本票及04补记、09/10实施状态报告。原始命令、runner、源码快照、before bytes/hash、动作包、fixture与保全核验仅B内。

单调用者同步seam要求操作者串行调用且sink及时返回；没有创建可中断线程、后台任务或跨进程IPC。限额约束事件/字节/常驻数据，**不承诺阻塞sink的总wall deadline**，也不承诺并发调用安全。生产Android跨线程/进程适配与Clock/sink有界机制须原owner另验，不能直接把本Python组件接生产冒充Android原始采集器。

失败/取消/fallback不会自动向已失败sink重发终态；调用者可通过snapshot在独立证据出口保存固定终态。组件只持有身份/有限计数/终态，不能导出曾经拒收的正文。测试与本批fixture保存这些snapshot，不声称主机中断时有事务性全链trace。

## 4. TDD与实际测试

已尝试Skill工具调用tdd，环境因项目/全局同名歧义拒绝，随后直接完整读取本地 `.agents/skills/tdd/SKILL.md`、tests.md和mocking.md。seam沿scope/spec及用户明确授权的现工具/公开外部可观察接口，不重复访谈；时间/sink只在系统边界注入。

核心16片逐一实际red→green：disabled、marker、identity、order、clock、route、stop、complete、sink、cancel、fallback、bounds、config、sink-refusal、owner-failure、explicit-clock。red原件exit1保留；缺模块/方法属于新接口尚未实现的red，不称Androidbug。余11项为既定公开行为的边界扩展与集成回归，没有虚称每条都曾独立red。

- 早期recorder单独回归：**26 tests，0.015s，exit0，0 failures/errors/skips**，B/evidence/regression-recorder.*。
- 早期完整工具：126 tests = 原100 + 新26，15.510s，exit0；B/evidence/final-tools.*保留，不覆盖、不与最终集合累加。
- 收口时发现可调用但布尔为False的clock被`clock or monotonic_ns`忽略；新增独立red-explicit-clock真实失败（时间不是固定4321），最小改为仅None选择默认clock后green-explicit-clock通过。最终新增27项。
- **最终完整工具：127 tests = 原100 + 新27，17.021s，exit0，0 failures/errors/skips**，B/evidence/final-tools-clock-fix.{json,stdout.log.txt,stderr.log.txt}。包含原fake adb Python子进程、链接/junction/不覆盖、错代/缺receipt/合成不认证等旧门。
- 每片源码快照与原始stdout/stderr/command/exit在B/snapshots与evidence，同相对路径preservation；重复slice和重复入口不相加为更多覆盖。八个offline场景的固定事件和终态在B/fixtures/，全部合成且没有receipt/manifest/samples导出。

实际命令入口（runner记录完整argv、临时根、Python3.12.9）：

```text
python -B <B>/run-recorder-checks.py regression-recorder
python -B <B>/run-recorder-checks.py final-tools-clock-fix all
```

等价公开命令：设置新的明确外部AVF_VALIDATION_TEST_ROOT和PYTHONDONTWRITEBYTECODE=1，执行 `python -B -m unittest discover -s <唯一源码根>/tools/device-validation -p 'test_avf*.py' -v`。runner同时把TEMP/TMP/TMPDIR指向本批test-runs，不默认C:temp，无新bytecode/构建产物入源码。

## 5. 未完成、风险与新发现

真实设备/Android时钟/launcher/bootstrap/manager/run/vsock/认证QA/services/正常stop/FD/reader收束均未在本票运行。07独立owned AVF闭环前09真实样本0；10未测量、不选择瓶颈，不报任何收益。独立双轴review未运行，本票不标completed。

两个具体接线缺口已升级给原owner/主协调并记动作包：

1. 现AvfInstanceLauncher.launch先bootstrap，ReflectiveAvfVirtualMachineApi.startCustomVm随后才shape/permission/manager/capability；04设计CHAIN的preflight-before-input不是当前生产事实。后续必须明确preflight定义与真实偏序，必要时单独schema切片，不能回填时间让工具变绿。
2. BridgeClient现无固定qa-uname-cpu-v1执行接口。缺QA/services来源时保持缺门，不使用旧终端正文解析或新增第二reader/任意exec；新协议能力若必要先经版本/ADR0009及ACR边界。

固定Route和StopFact是防接线错误，不能成为真实性信任根。Python默认时钟也不能重标Android clock。全部本地测试通过不证明目标ROM、nonprotected能力或AVF运行。sink由操作者独占并及时返回；现EvidenceStore不承诺抵御同目录恶意并发替换或跨两个文件事务，独立双份同盘不是异地备份。

## 6. 回滚、资产、许可与架构

回滚仅撤回本票独立工具/测试/文档；保留旧04工具、原失败/新证据、Class A SDK/NDK/JDK/Gradle/Maven/WSL/kernel/rootfs、旧APK/源码交付与用户盘。无清理、无C:项目写入、无系统设置/安装/服务变化、无网络下载/上传，不读取用户metadata/凭据/embedded载荷或完整设备dump。

源码为自研GPL-3.0-or-later，仅Python标准库；没有复制上游实现或新第三方依赖。没有修改Full Linux契约/权限/owner/IPC/guest暴露面，不触发Architecture Change Policy，ACR-01未被本票代答。E生产写锁外零改动；主协调独占设备/Gradle/版本，生产插桩请求交原owner并待独立窗口。

## 7. 后续动作与状态

建议22 ready-for-review；主协调按未提交新增文件和本轮source hash安排独立规范/Spec两轴，不以旧HEAD空diff漏新组件。最终核对B/closeout/delivery-manifest.json和B/closeout/preservation-audit.json，以及evidence/final-tools-clock-fix原件与上述边界；根目录delivery-manifest.json保留为早期126项修订。04维持既定completed-local；09 blocked-real-avf、10 blocked-measurement。无新commit、无版本变化、无发布。

报告人：Worker E / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
