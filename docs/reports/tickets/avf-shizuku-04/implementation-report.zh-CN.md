# 工单04实施报告：只读设备取证与可信样本接收工具

- 票号：avf-shizuku-04；状态：**ready-for-review**。规范轴P2已修复并完成新回归，等待reviewer复审批准；本票未标completed。
- 执行：D / Codex GPT-6.1；日期：2026-10-15。
- 分支与基线：main / `428860ad9a1cb335f8be78c157b2ff57faf2a6da`；本票无 commit、push、tag、worktree 或 refs 操作。A/C 同仓并行，不能把本票结束状态表述为整仓干净。
- 派工依据：主协调在正式研究/spec/17工单形成后追加明确授权，仅提升自有AVF工具和本票状态/报告/证据；原 D 研究阶段仍仅写 validation-design，历史证据不改写。
- 外部原始证据根：`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/implementation-04/`。Owner D / 主协调，长期归档，无自动清理。

## 1. 目标与实际交付

把 D 研究原型中经合成验证的最小工具提升到现有 tools/device-validation，提供显式外部 evidence/output root、明确 serial、固定只读查询、可信样本接收门、阶段汇总和可重复的 source-only 测试。本票不负责 AVF 真实启动、权限代理或性能优化。

本票新增以下6个自有文件，均为GPL-3.0-or-later自研源码/说明，只有标准库依赖：

| 文件（相对主仓） | 用途 |
| --- | --- |
| tools/device-validation/avf_evidence_io.py | 显式根、受限路径、严格JSON、原子不覆盖输出与独立保全副本 |
| tools/device-validation/avf_preflight.py | 固定allowlist discovery/collect；serial显式，raw不落盘 |
| tools/device-validation/avf_benchmark.py | 离线样本/receipt校验与11阶段n/median/range；始终不认证设备真实性 |
| tools/device-validation/avf_test_fixtures.py | 仅测试使用的小型合成构造源码，两个CLI运行不依赖它 |
| tools/device-validation/test_avf_tools.py | 公开CLI、fake adb子进程、合成证据根；直接与unittest两入口 |
| tools/device-validation/README.avf-validation.zh-CN.md | 接口、环境、边界、设备保护/正常停止/回滚验收清单 |

另更新本票 `.scratch/orchestella-avf-shizuku/issues/04-safe-validation-tools.md` 并新增本报告。未修改现有 terminal/distro 工具、共享 README、App/Guest、ADR/policy、VM owner、kernel/rootfs或系统配置。未把研究 run_checks 的固定根或大型生成fixture搬入源码；测试数据只写外部显式AVF_VALIDATION_TEST_ROOT。

## 2. 行为验收与证据

| 验收 | 结果与边界 |
| --- | --- |
| 固定只读命令、显式serial、失败/超时/撤回不成功 | 已通过fake adb真实Python子进程，校验精确argv；非零退出即停，timeout与撤回保留PARTIAL_PREFLIGHT，无重试 |
| 字段allowlist与unknown | ROM/API/ABI/page/module/feature声明有限解析；serial/raw stdout不输出；权限/API capability无typed探针固定unknown，不推导custom ready |
| 样本证据/归属/资源/hash/失败门 | 缺原件、hash错、16门缺失、乱序/非单调/错误类型、错session/代际/owner/VM、资源/冷热/调用上下文不一致、fallback/QEMU/UNKNOWN/失败均排除；输入保持原样 |
| synthetic隔离与不认证 | synthetic单列preview，正常合成集device candidate0；故意伪造全部device字段的合成反例仍device_verdict=NOT_DETERMINED、authenticity=NOT_AUTHENTICATED_BY_THIS_TOOL |
| 路径/链接/恶意字段 | 显式绝对root，受限相对文件；路径逃逸、隐藏/动态路径、新.log、链接/junction、额外秘密字段、重复JSON key/NaN/大输入拒绝；仅支持Python3.12+且具备is_junction能力，能力缺失/低版本先拒绝，无False回退；链接与运行门负例实际通过，无skip |
| 统计与停止 | 11阶段仅n/median/min/max(ns)，不报p95/p99；run返回/RUNNING不是Guest ready；custom Linux不依赖Microdroid payload callback；stop须本session正常停止receipt |
| 输出与保全 | 独立staging+fsync，先发布独立preservation再发布原件；不覆盖旧有效结果；修改测试原件不影响保全副本；全部测试通过 |

receipt文件必须来自明确输入根下固定 receipts/<64位hex>.json，实际字节hash以及kind/sample/session/generation/owner/VM/clock/binding hash/event/source/outcome/时间一致；样本文件精确字节hash与manifest绑定。不从JSON任意路径取文件，不读取设备metadata或embeddedinitrd。

JSON hash/engine/boolean/source/reviewed字段可以被自洽伪造，所以这些结果只证明结构、关联和完整性，不是AVF真实性认证。device统计也仅命名candidate，独立设备证据审查才可给物理验收结论。测试中故意伪造device标签的输入和输出都属于合成反例，不能统计为实测样本。

## 3. TDD seam与真实red/green

已读本地 `.agents/skills/tdd/SKILL.md` 及 tests/mocking；seam沿用正式工单/spec已确认的公开Python CLI、fake adb子进程和显式合成证据根，未扩大到未批准模块。

研究阶段原型已先完成96项结构/合成测试，不能虚称其全部来自本票test-first。提升阶段新增可移植路径/发布语义和公开CLI行为按实际切片执行：

| 切片 | Red | Green / 修正 |
| --- | --- | --- |
| 显式外部根输出 | red-output：1项，缺avf_evidence_io，实际exit1 | green-output：1项通过；生产工具无需研究根 |
| 独立保全副本 | red-independent-copy：2项中新增副本独立性真实失败 | green-independent-copy：2项通过；原先共享inode的问题改为两次独立staging发布 |
| benchmark CLI拒绝 | red-benchmark-cli：3项，新增CLI未提供，实际失败 | green-benchmark-cli：3项通过；显式evidence/output root与固定错误码 |
| 全量样本CLI | regression-benchmark-cli：85项通过 | 含合成原件、完整缺门/资源/回退/错误hash/链接等拒绝 |
| fake adb子进程 | red-preflight-process：94项，新增preflight seam未实现，实际失败 | 首次green-preflight-process为93通过+1失败，原因WindowsCRLF测试断言；按行校验固定错误码后regression-cli-newlines：94项通过 |
| 最终限定边界 | 补显式root/大输入/旧证据/不认证等既定5项 | final-unittest与final-direct：同一99项各通过，0 failure/error/skip |

旧失败及green证据全部保留，未覆盖重写。初始缺模块/CLI的red属于提升入口未交付，不当作真实设备故障；独立副本red是实际I/O行为缺陷。没有用mock AVF运行或凭终端正文解析来获取green。

## 4. 初版99项实际测试与原件保留

在仓根执行，唯一必需环境变量为绝对外部 `AVF_VALIDATION_TEST_ROOT`。测试内部UUID目录全部保留；没有默认C:temp。以下为主协调可独立重验命令，使用新的标签目录：

```bash
AVF_VALIDATION_TEST_ROOT='LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/implementation-04/test-runs/main-coordinator' \
  PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover \
  -s tools/device-validation -p 'test_avf*.py'

AVF_VALIDATION_TEST_ROOT='LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/implementation-04/test-runs/main-coordinator-direct' \
  PYTHONDONTWRITEBYTECODE=1 python -B tools/device-validation/test_avf_tools.py
```

D实际调用外部 `implementation-04/run_local_checks.py`，该runner设置等价环境并运行上述入口，逐次保全源码快照、stdout/stderr与退出状态：

- **final-unittest：Ran 99 tests in 14.310s，OK，exit0。** 原始输出 `implementation-04/evidence/final-unittest.log.txt` 与 final-unittest.json。
- **final-direct：Ran 99 tests in 14.866s，OK，exit0。** 逐项输出 `implementation-04/evidence/final-direct.log.txt` 与 final-direct.json。
- 二者为同一99项测试的两个入口，不相加为198项覆盖；研究96项另计，不当作真机或性能样本。

此票不跑Gradle/App/Guest全套、assemble、lint或设备；主协调负责相关集成及独立review。本票所需Python测试实际完成。source-only语法/文件单/保全收口记录在 `implementation-04/evidence/closeout/`，未以空test任务冒充覆盖。

## 5. 未完成与设备blocker

规范轴reviewer发现P2后，按主协调裁定完成最小收窄与修复；等待reviewer复审批准，票状态保持ready-for-review。真实设备零连接的旧观测不自动获得本票设备许可；本票没有调用真实adb，包括devices-l也未重复。无真实ROM命令兼容、权限/API能力、owned create/run/state、vsock认证、固定Guest命令、services ready、normal stop/FD释放或性能样本；待07/09设备门。

生产应用尚无本票格式的全部recorder，本工具不会从日志/徽章伪造原件。后续插桩和VMowner方案按独立工单/ACR，本票不新增IPC、权限或VM操作。CPU/RSS/I/O/输入延迟/GPU/网络/电耗采样不在小型工具本票范围，不作性能结论。

## 6. 风险、回滚、资产与边界

- 支持范围更正为Python3.12+且必须具备可调用的Path.is_junction，本轮仅实测3.12.9。初版3.9+宣称不成立：其False回退会在3.9–3.11静默跳过junction检查，规范轴P2指出后已移除该回退；现在两个CLI和IO入口先以RUNTIME_UNSUPPORTED拒绝低版本/缺能力，零目标目录写入。模拟旧版本仅验证拒绝门，不是实跑旧解释器。WindowsNTFS的hard-link原子发布与junction拒绝实际通过；其他文件系统不支持则失败，不降级为覆盖。
- 输出原件与保全独立，逐个原子发布；不承诺两个文件跨崩溃事务。中断可留下完整保全而无原件，保留以供诊断。仅定点移除本次staging名，历史证据/资产不清理。
- 输入root/路径检查拒绝链接，但不承诺抵御同目录恶意并发替换；操作者须独占批准的证据根。双份同盘不是异地备份，无上传授权或网络功能。
- 自有极小合成test-scratch和反例长期保留，独立副本测试会故意修改原件以证明保全不联动；该差异属于测试预期，不得自动修复/清理。
- 回滚仅撤销本票新增6个工具/测试/说明和状态变更；研究、red/green、原始设备证据、ClassA SDK/JDK/内核/rootfs/WSL、签名材料与既有实例均保全。不删除用户盘，不安装/卸载/force-stop，不改W:/V:、C:、PATH、注册表、服务或安全策略。
- 无越界：仅主协调新授权的文件/报告/本票状态和implementation-04外部证据。未读取token/凭据/metadata/embedded载荷，未联网或触达无关目录，没有commit/push/worktree。

## 7. 决策与后续

不改变Full Linux契约、权限/IPC/VMowner、guest kernel/根盘/网络或产品承诺，不触发Architecture Change Policy，无ADR重开。依赖均标准库，自研fixture/helper只供测试，没有上游实现复制或新第三方依赖。

主协调下一步：审查新增6个文件与I/O/链接/保全/不认证边界，按上述公开命令独立复验；通过后再集成。设备验收使用README清单保护签名/实例引用/活VM/唯一reader，仅QA盘并正常停止/回滚；工具结构通过不等于设备07/09已通过。

## 8. 规范轴P2修复与复审回归

reviewer指出初版avf_evidence_io使用getattr(..., lambda: False)在缺少is_junction时静默继续，与3.9+及链接拒绝的文档承诺冲突。主协调裁定最小收窄为已实测的Python3.12+，不新增旧解释器兼容实现。

修复仅触及原04范围：IO中加入require_supported_runtime，要求解释器>=3.12且Path.is_junction可调用；root构造和每次文件路径入口先检查，再直接调用is_junction，不再假False。两个公开CLI在访问adb或输入/输出根之前调用同一运行门。README同步收窄，原99项与研究产物不覆盖。无设备或架构变更，无其他冻结票改动。

新增一项公开入口负例test_unsupported_runtime_rejected_at_public_entries_without_writes：独立子进程分别删除当前受支持解释器的Path.is_junction能力、模拟sys.version_info为3.11，在EvidenceStore公开IO、benchmark CLI及preflight CLI共六个组合验证固定RUNTIME_UNSUPPORTED/exit2/目标根零创建。stdout仅固定错误码，stderr为空；测试执行记录写在被拒根之外并即时保全。该模拟不是旧Python真实运行证据。

- **p2-runtime-red**：新增一项测试实际产生6个失败，IO仍会输出，两个CLI先返回INPUT_REJECTED/ADB_REJECTED，证明旧实现缺少前置运行门。原始输出evidence/p2-runtime-red.log.txt，旧实现与新测试快照sources/p2-runtime-red/。
- **p2-runtime-unittest**：修复后同一套**100项**，Ran 100 tests in 16.968s，OK，exit0，零failure/error/skip；原始evidence/p2-runtime-unittest.log.txt/.json。
- **p2-runtime-direct**：直接脚本入口同一**100项**通过，exit0，零failure/error/skip；逐项原始evidence/p2-runtime-direct.log.txt/.json，新增运行门用例及既有junction用例均ok。
- 100=原99+新增1项（六个子用例），两个入口不合并为200项覆盖；初版final-unittest/final-direct的99项证据长期保留。新版源码快照sources/p2-runtime-unittest/、sources/p2-runtime-direct/；更正文档/票/报告快照与保全审计在evidence/p2-runtime-closeout/及preservation/。

复验沿用第4节命令，仅将外部AVF_VALIDATION_TEST_ROOT标签换为新的p2复审目录即可。已向主协调交付复审材料，由其转reviewer；批准前保持ready-for-review，不将工具回归当作设备/性能验收。

## 主协调最终验收补记

状态：**completed-local**。上文待复审为D封板时历史状态。规范轴04-rereview与独立Spec轴04均最终approve，原运行时junction P2已闭合，当前P1/P2=0。主协调新标签实际复验100 tests/OK/exit0（17.935s），见E/evidence/validation-tools-reviewed.log.txt与integration/tool-reviewed-verification.json；开发者两入口同集合100不累加200，旧99/研究96保留。

本票工具/本地说明/负例/独立review完成，设备真实性、AVF/Guest/性能仍未验，07/09门保留。正式双轴汇总docs/reports/reviews/avf-shizuku-04/review-report.zh-CN.md。无设备/系统/新架构动作，无commit/push/发布。

报告人：Codex / GPT-6.1；主协调最终补记 ｜ 日期：2026-10-15 ｜ 耗时：未单独计量

## 2026-10-16 Worker E复用补记

04保持原completed-local与历史双轴批准范围；本轮没有修改avf_preflight.py、avf_benchmark.py、avf_evidence_io.py、avf_test_fixtures.py或test_avf_tools.py，没有重造第二套探针/校验器/统计器。新增独立离线组件及测试由[22正式报告](../avf-shizuku-22/implementation-report.zh-CN.md)承载；README追加使用/边界与最新设备事实，原04票仅补记，09/10保持真实运行/测量阻塞。

本轮主协调给定main@428860a、dirty0.5.0/code500/旧36SHA全同；唯一专属证据根 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/benchmark/`。既有文件before bytes/hash、新增源码、各片red/green、原始command/exit与preservation保留，Class B长期至维护者归档、不自动清理、不外发。精确文件清单和保全核验见本根delivery-manifest.json、preservation-audit.json。

Worker E本轮最终回归Python3.12.9：原100+新增27共**127 tests/17.021s/exit0，0 failures/errors/skips**，evidence/final-tools-clock-fix.{json,stdout.log.txt,stderr.log.txt}及preservation为原件。早期recorder26 tests/0.015s和完整126 tests/15.510s均保留；收口falsey callable clock边界有独立red→green，默认clock仅None时选择。重复核心片和同集合运行不累加覆盖，fake adb仅Python子进程，未调用真实设备；这些时间不是AVF性能。最终清单与保全核验在本根closeout/delivery-manifest.json、closeout/preservation-audit.json，先前根清单为早期126项修订。

Python recorder只输出offline_stage_trace/synthetic_seam，clock=python或injected_monotonic_ns，完整也仅COMPLETE_OFFLINE，真实receipt0。关闭不采集/无队列，身份/代际/限额/单调时钟、认证路由标签、缺门/取消/回退/迟到、sink失败及正常stop三事实均为离线行为门；不认证Android来源或物理VM。组件要求单调用者串行且sink及时返回，没有阻塞sink的wall deadline承诺。

主协调已发现device-01/user0，旧“零设备”为2026-10-15历史事实。07独立owned AVF/Guest/正常stop闭环前，09真实样本仍0；10没有数据不选择瓶颈或收益。最小生产插桩动作包已交原owner/主协调并保全于外部owner-instrumentation-packet.zh-CN.md；生产preflight时序与固定QA命令接口未齐，不回填事件、不增加第二reader或解析终端正文。22独立双轴review尚未运行，由主协调安排；本轮不冒用04旧review覆盖22。

E未写launcher/MainActivity/Guest/输入/版本或VMowner，未adb/UI/Gradle/设备安装/权限/启停/commit/ref/worktree/reset/clean、未读取secret/embedded载荷或外发。保留Class A、旧APK/源码与用户实例，无系统/C:项目写入或清理。无新第三方依赖，自研GPL/标准库；不改变契约/权限/IPC/owner/暴露面，不触发Architecture Change Policy。分支main由主协调给定，commit无，09/10未完成原因与后续动作见各票中文报告。

报告人：Worker E / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
