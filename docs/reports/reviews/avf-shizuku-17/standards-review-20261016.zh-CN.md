# 17 独立 Standards 首审：会话退出监督与启动代际

审查者：独立 Standards / Codex GPT-6.1-sol，2026-10-16。结论：**request-changes，1项P2，未发现P1**。此为冻结源码的独立规范轴结论，不读取另一轴结果；实施者报告不充当独立通过。

## 背景、固定点与实际范围

唯一effort为 `.scratch/orchestella-avf-shizuku/`。沿用户指定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da的dirty0.5.0/code500起点，旧36文件与并行实现保全，未使用HEAD三点空diff。

主协调后来追加C冻结包。外部C根为 `LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`。固定点为 **C/final-freeze.json** 与 frozen-source/；17份source/test全部与seal、canonical及preservation孪生匹配。其中14份App输入属17，三份Guest输入属18。八份11/12/17/18票据/报告与delivery-manifest的hash匹配。

已查17原票、11原型报告、17实施报告、20261016 Worker C Prompt；冻结Sequence、Registry、Pool、Reconciler、Guard、FGS/Binding、Main/Detail/Terminal限定hunk及四个测试；定点核Controller start/单实例语义、既有桥接/停止边界。共同规范为AGENTS/CONTEXT、DEFENSIVE全部policy、HOST/ARTIFACT/ASSET/ARCHITECTURE/REPORTING及项目code-review技能。摘要和原件hash在 batch-01/review-standards/17-18/frozen-input-verification.json 及即时preservation。

## 硬规范与行为发现

### S-C-01 · P2 · 被拒绝的重复启动覆盖有效launch代际，令其真实退出变为STALE

- 文件/符号及常规1起点行：冻结 `InstanceLaunchGuard.begin` **22–27**；`MainActivity.launchInstance` **1491–1506、1601–1605**；`PersistentTerminalSessionPool.SessionHolder` **62–63** 与 `completeExit` **253–257**。根路径均为app/src/main/java/io/github/nyzeep/orchestella，具体审查字节为C/frozen-source副本。
- 实际触发：A已经begin并把实例置RUNNING，仍在bootstrap/平台启动、尚未注册owner；MainActivity重建或第二入口的另一Activity executor处理同一实例B。入口只查Registry，看不到owner便调用begin；begin无条件覆盖IN_FLIGHT与LATEST为B。Controller随后因实例已RUNNING拒绝B。B失败清理以enteredRunning=false移除自己的IN_FLIGHT，但LATEST仍B，A的原token丢失。A继续注册时只能新造holder UUID，确认退出时因LATEST不同被completeExit判STALE；FGS新START也可能因token不匹配被拒，原实例正常退出不再收束metadata/guard/owner/通知。这是实际源码可达竞态推导，没有声称设备已复现。
- 规范来源：20261016 Worker C要求旧代/失败不能清新会话，保留真实owner和单运行实例；17原票的旧失败不得清现owner/新通知；ADR-0011实例状态与冷恢复；REPORTING真实性要求。新代际门不能让一个从未获准启动的请求使有效owner失效。
- 必要最小修复：启动领取使用排他admission，已有同实例inflight时拒绝且不改IN_FLIGHT/LATEST；被拒请求不执行end/FGS清理。保证获准A的token从begin至holder注册稳定。补固定顺序A已RUNNING未注册→B拒绝→A注册/真实退出的回归，核metadata/Registry/guard/通知一次收尾、根盘/身份不变；新freeze后定点复审，保留旧seal与red。

## 其他规范与证据真实性

- 退出与归属：wait0正常、23异常并保留原码；只有接受的FORCE意图及owner确认退出才解释非零为有意停止，graceful不掩盖失败。wait异常/中断不假称VM死，保留owner/guard/metadata；中断标记保留。expected-session条件unregister及generation防旧watcher、旧FGS STOP、延迟UI是现有边界，S-C-01为其遗漏的启动admission顺序。
- EOF与单reader：pump EOF只结束transport，不取代owner；Reconciler不再用可连socket/waitFor0替身证明监督；owner退休后再次通知，Terminal保留attachedHolder/bindingGeneration检查。未新增控制reader、权限/IPC/VMowner、热恢复或多实例能力。
- 停止边界：typed动作在Pool monitor内串行于注册/完成，保持接受后记录意图；底层停止时延不由本票证明。没有把caller结束/Thread.interrupt或AVF适配器wait0当真实平台stop认证；08的真实收敛门仍独立，报告已有明确限制。
- 原件：C/evidence/final-lifecycle-junit.json为 **exit0、OK (33 tests)**；actual-fgs-1.json为 **exit0、ACTUAL_FGS_ADAPTER_OK checks=14 AndroidBoundary=synthetic device=false**。相关源码、测试与固定输出对应，孪生字节相同。没有将33与14或逐轮red/green累加为完整App测试数，未重跑全套。
- 范围与许可：Main/Detail stop、FGS/Binding和Terminal最小扩锁由主协调明确批准并入原票；未触D输入/向导或AVF底层。无新依赖/参考实现复制、版本/签名/Manifest/公共资源变更。process-local generation为生命周期关联，不是bridge secret，不输出用户正文/凭据。

## 启发式 smell：判断性，非硬违规

核全部12项Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest。repo允许的现Registry/Pool facade和局部公共测试接缝优先，未因委托或字段组合提出泛化重构。没有新增可靠smell发现；S-C-01为可达行为错误。跳过工具已覆盖项。

## 限制、修改与后续

完成冻结源码/证据独立审查及P2早报；未运行设备/adb/UI/安装/Gradle、JVM重跑或网络。主协调提供的App/runtime/lint集成结果不是本review新运行，不替代新admission顺序回归及真实FGS/Activity/VM停止。`.phase5` code502仍待机主OEM“继续”确认，实际installed501，不记新包设备green。真实Guest/通知/进程死亡、APK最终绑定仍开。

本review只写指定17/18正式报告与review-standards/17-18/preservation；生产/版本/tracker无改动，commit/ref/worktree无操作。branch沿用户提供main，Commit无；Class A/原始red/旧APK/用户盘保全，新.log证据0。风险为被拒竞争启动退役有效代际；后续按S-C-01最小修复、新seal与定点复审关闭。Architecture Change Policy：既有契约bugfix，无新owner/暴露面，本审查未批准架构变化；ACR-01继续独立。

报告人：独立 Standards / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
