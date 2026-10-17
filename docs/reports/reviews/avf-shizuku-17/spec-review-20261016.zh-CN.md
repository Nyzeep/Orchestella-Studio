# 17 独立 Spec 审查：退出监督、停止请求与代际归属

结论：**request-changes，2 项 P2，无 P0/P1**。停止请求在异步派发后才捕获owner，可操作替换会话；注册owner后通知启动抛错，会跳过退出watcher且保留无人监督owner。两项已即时回报主协调。33项JDK与14条合成FGS通过不覆盖这两条Activity调用顺序；设备门未完成本身不计发现。

## 1. 对象、基线与独立性

独立 Spec reviewer / Codex GPT-6.1-sol；2026-10-16。已读11/17原票、17实施报告、20261016 Worker C/Reviewer要求及项目AGENTS/CONTEXT和全部指定policy。固定终点是 `batch-01/lifecycle-logs/final-freeze.json` 与 `frozen-source/`，17份App/Guest输入全部独立匹配bytes/SHA；本票14份App源/测试，Guest三份由18报告承载。对照精确before，不以HEAD三点diff遗漏dirty实现。

基线为用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da、dirty0.5.0/code500。本reviewer无Git/ref/版本/commit操作；未读另一轴，不复用其结论。源码判断与已有执行原件分开，不声称源码事件推导已在设备复现。审查期间后续修复不纳本首审，原冻结结论保持。

## 2. 发现

### S-04 · P2 · 停止动作在IO派发后才捕获session，旧用户动作可停止新会话

**原验收**：17第17行要求“错代请求不能操作新session”；第20–21行要求旧代不能改新会话/反馈/通知。20261016 Worker C明确expected session及代际约束。

**冻结位置**：`MainActivity.kt` 的 `requestInstanceStop`，实际第1223–1238行；冻结SHA256 `a4d31c1497121b90d7a8dfe973dc7f8a1d680aa8ac5b07ff716a3e6eb666a25b`。

**触发**：当前A运行，用户在主界面对A确认强停或关机；`lifecycleScope.launch(Dispatchers.IO)`尚未执行，A退出，B以同instanceId启动。IO任务执行时才调用Registry.get并把B当作expected session，随后typed requestStop会合法接纳B，并对B terminate/power。Pool能防“捕获后替换”，无法判断用户动作原来针对A。Detail调用点在入队前捕获requestedSession，主界面未保持同样边界。

**最小修复**：在用户动作/派发前捕获expected session，IO执行时与当前Registry比较并经typed gate操作；替换时拒绝，不重新查新session作为目标。回归需覆盖“A用户点击→B注册→旧动作执行”且B terminate/power计数0，并保留当前代正常请求。无需新owner/IPC或全面重构。证据级别为冻结源码直接事件路径，未跑设备。

### S-05 · P2 · 已注册owner后的通知启动异常会跳过退出监督

**原验收**：17第16行要求当前owner exit0/23正确且一次记录；第18行观察失败需保留受监督owner；最终增补要求通知异常不留假活owner，Main watcher必须覆盖真实调用链。11/Worker C同样要求从实际launch/Registry/通知顺序核对。

**冻结位置**：`MainActivity.kt` 第1554–1561行，第二次 `OrchestellaForegroundService.start` 位于创建awaitGuestExit线程之前；同文件第1600–1608行catch调用 `afterLaunchFailure`。`InstanceLaunchSequence.java` 第81–87行见Registry存在owner即返回false，不做收尾。

**触发**：strategyLauncher.launch已成功注册session；更新真实执行徽章的第二次FGS start同步抛异常。执行跳过后续watcher构建，catch见owner存在而保留它，未在其他位置补建监督。之后VM实际exit0/23也无人调用awaitGuestExit；Registry/metadata/guard可能继续表示活owner，终端EOF只保留owner，重新launch只attach，因此不能靠EOF或重入收敛。

现有 `notificationFailureCannotKeepAConfirmedExitedOwnerRegistered` 测试是awaitGuestExit已确认退出后的STOP反馈抛错，不覆盖注册后、监督建立前的START失败。

**最小修复**：一旦session注册成功，保证退出监督一定建立，通知/徽章更新的异常不得跳过它；可局部隔离通知异常或调整调用顺序并保留本代guard。补“注册成功→FGS刷新抛错→真实合成owner退出→状态/Registry/FGS一次收束”的生产调用链门，保留wait失败不假定死亡的对照。不应仅注销/停止未知VM来规避监督。此项同为源码路径推导，不是Android已复现。

## 3. 已成立的局部行为与验收边界

| 验收 | 冻结实现与原件 | 判断 |
| --- | --- | --- |
| exit0/exit23/force/graceful | awaitGuestExit使用真实wait结果，FORCE仅在动作接受且确认退出后解释非零；graceful不吞23 | 公共Java接缝已覆盖 |
| wait失败/中断 | WAIT_FAILED，保留owner/metadata/FGS；不把异常当已停 | 已覆盖，真实平台仍未验 |
| 旧A完成/B已注册或尚在launch | Pool completeExit同时校expected对象与launch generation，操作/注销与register串行；延迟UI核latest | 正确覆盖该局部顺序；S-04另在派发边界 |
| EOF但VM仍活 | hasOwner独立于pumpFinished；Reconciler不造socket替身，不写crash；输入仍按EOF拒绝 | 符合；单reader未扩大 |
| owner退休再次反馈 | 流结束与owner退休分别一次，退休后清listener；Terminal原holder/bindingGeneration门保留 | 局部符合 |
| FGS代际 | START/STOP关联实例/generation，旧/缺token不能清新绑定；冷STOP不留空服务 | 14条合成适配门；真实FGS未验 |
| 无owner持久RUNNING | INTERRUPTED/coldRestore，保留根盘/身份/凭据，不以socket可达代VM监督 | 符合既有契约 |

无新owner/特权IPC/reader/用户盘迁移或权限扩大。写锁扩展在17原票明确记录，属于本轮授权调用点，不作为额外scope发现。

## 4. 测试真实性与限制

独立读取本批原件：`final-lifecycle-junit.json`为exit0、OK (33 tests)；`actual-fgs-1.json`为ACTUAL_FGS_ADAPTER_OK checks=14 AndroidBoundary=synthetic device=false。测试实际使用Sequence/Registry/Pool/Reconciler/Controller，session/Android系统边界合成。既有red逐轮保留，33与14分列，不累计为App全套或设备测试。

reviewer未运行重复套件、设备、Gradle、签名、网络或Git命令。真实正常关机/强停/通知/Activity重入、QEMU/AVF、502+集成仍未验，明确未完成不算虚报。现有33/14通过不能替代S-04/S-05缺失的Activity调用顺序。

## 5. 交付、后续与边界

实际完成本报告、`review-spec/17-18/checks.json`与报告副本，及 `preservation/review-spec/17-18/` 同字节保全。修改仅本轴正式review与外部证据；生产/tracker/版本/ref只读。首审原件不因后续修复覆盖。

必要后续：两项最小修复分别冻结后定点复核，再由主协调做实际框架/设备门；不得以本地无设备为由忽略可确定源码缺口。风险是停止错owner与失去退出监督，不构成已发生盘损坏结论。Architecture Change Policy：两项建议均在既有契约内，不批准新owner或IPC。本审查无生产/设备/系统/网络操作、清理或另一轴读取；Class B保留至维护者归档。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
