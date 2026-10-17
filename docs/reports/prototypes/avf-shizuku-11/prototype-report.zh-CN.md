# 原型报告：AVF/Shizuku 11 退出原因与会话代际

- 日期/执行者：2026-10-16，Worker C / new-provider/gpt-6.1-sol。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da；起点为保留的 dirty 0.5.0/code500，未创建 ref/worktree，未 commit。主协调后来构建的501仅为中间QA包，不代表本票最终实现。
- 背景/scope：完成原11的公共调用链反馈，以派生17承接契约内生产修复；不新增 VM owner、特权 IPC、热恢复或第二 reader。
- canonical 证据根：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`，下文简称 E。原件与 `E/preservation/` 字节孪生保留，Class B，维护者验收归档前不清理。

## 假设清单与实际调用链

原20261015 `reproduction-11/result.json` 与 harness 已完整读取，其结论仅是 Controller/合成 Store 接缝缺代际保护，不能称设备 bug。该旧证据原样保留。

本轮读到的生产顺序为 MainActivity.launchInstance → launch guard / Controller.beforeLaunch → launcher 注册 ExecutionSessionRegistry / Pool → MainActivity wait watcher；TerminalActivity 的 pump EOF 回调 → InstanceSessionReconciler；显式停止分别由 MainActivity.requestInstanceStop、InstanceDetailActivity.requestPowerChange 发起；通知由 OrchestellaForegroundService 接收异步 Intent。launcher/AVF 底层只读。

原 Main watcher 丢弃 waitFor 返回值并在 finally 按 instanceId normal/unregister/stop。为测试真实调用位置的行为，先把该 watcher 的原语义移入 Main 实际调用的 InstanceLaunchSequence.awaitGuestExit 公共接缝，再逐 slice 建立退出原因/归属回归。它是宿主 Java 行为接缝，不是运行过 Android Activity 或 VMM 的证明。

## 实验环境与步骤

Windows / Python3.12.9 / `D:/JDK17`。直接使用既有 JUnit4.13.2 与 hamcrest1.3 缓存，显式编译生产 Java 闭包、真实 Controller/FileStore/Registry/Pool/Reconciler 与测试；合成 ExecutionSession 只提供受控 wait/流/退出信号。输出、class、临时目录均在 E，不调用 Gradle、设备、安装或网络。FileStore 只操作外部测试元数据；从未创建或访问真实根盘。

固定入口：

```text
python -B <E>/run-lifecycle.py --label <唯一标签> --classes InstanceLaunchSequenceTest InstanceSessionReconcilerTest PersistentTerminalSessionPoolTest ForegroundSessionBindingTest
python -B <E>/run-fgs-adapter.py
```

第二入口编译并运行实际 OrchestellaForegroundService 源码，Android Service/Intent/Notification/Context 为外部显式合成边界；不能替代 Android framework 或设备。

## 观测与结论

| 场景/假设 | 固定 red 原件，真实 Java exit1 | green 与结论 |
| --- | --- | --- |
| EOF 足以标 VM 死亡 | red-eof-compiled-junit.json：预期ATTACHED、实际CRASHED | green-eof：EOF仅流事件；保留owner/metadata，不创建socket替身 |
| exit23 应保存原退出码 | red-exit23-junit.json：实际NORMAL | green-exit23：CRASHED，原23，未声称磁盘损坏 |
| wait失败可统一收尾 | red-wait-failure-junit.json：实际CRASHED | green-wait-failure：WAIT_FAILED，owner/guard/通知保留；中断标志保留 |
| A迟到不得退出B | red-stale-owner-junit.json：实际NORMAL | green-stale-owner：STALE，不写B metadata/diagnostic、不close/terminate B、不停B通知 |
| explicit force非零是有意退出 | red-force-intent-junit.json：实际CRASHED | green-force-intent：仅动作接受的本owner force+确认退出可正常停止；graceful不掩盖23 |
| 旧STOP不得清新通知 | red-fgs-binding-junit.json | actual-fgs-1.json：实际FGS适配器受控14条通过，含错token/缺token/重复/冷service/迟到START |
| CRASHED可冷恢复 | red-cold-relaunch-junit.json：InvalidTransitionException | green-orphan-cold：用既有coldRestore，单运行实例与凭据/根盘引用保留 |
| socket可连可替代VM owner | red-orphan-socket-junit.json：实际ATTACHED | green-orphan-cold：无owner记INTERRUPTED，不调用socket替身probe |
| B尚未register时A可收尾 | red-new-launch-before-register-junit.json：触发old notification stop | green-pending-generation：新launch代际已开始即拒旧完成 |
| 通知投递失败可留假活owner | red-notification-failure-junit.json：Registry仍有退出owner | green-notification-failure：先退休已确认owner再反馈 |
| EOF后owner退休应再次核对UI状态 | red-eof-owner-retirement-junit.json：未收到第二通知 | final-lifecycle：流结束、owner退休各一次，退休后释放listener；Terminal回调保留bindingGeneration/attachedHolder检查 |

表中原件均在 `E/evidence/`。最后运行 `final-lifecycle-{javac,junit}.json`：javac exit0、JUnit exit0，四类合计33项，0失败。该33是当前四类总数，不与旧批或逐轮结果相加。实际FGS边界14条与JUnit分开计数。条件 unregister 保留新holder、重复退出/注销不重复close/stop、拒绝/错代请求不留下假force意图均有正负断言。

## 产出与触发器判定

修复由17票与 `docs/reports/tickets/avf-shizuku-17/implementation-report.zh-CN.md` 承接。实验class/临时harness只在E，永不当生产源码合入；维护性回归位于原App测试目录。生产与测试17份输入的最终bytes/SHA见 `E/final-freeze.json` / `E/frozen-source/`。

本轮确认的是 Java 公共调用链与受控 Android 边界缺口。没有执行 QEMU/AVF/设备进程死亡、真实通知/Activity重入或性能实验；实际用户可达调度和ROM行为仍待主协调QA窗口。原设备产品签名冲突由主协调处理，本Worker没有卸载/清数据/安装/启停。

不触发 Architecture Change Policy：使用既有 CRASHED/INTERRUPTED/coldRestore 语义、原 owner/鉴权通道；不改 accepted ADR/产品能力矩阵、权限或暴露面。不建议因合成结果翻转任何AVF能力声明。scope扩展的Main stop/catch/orphan、Detail stop、FGS及Terminal最小回调均经主协调明确批准；未触SetupWizard/input/AVF底层/版本/公共strings。

## 未完成、风险与后续动作

未完成：真实Android framework/设备的正常关机、force/撤回/死亡、通知/Activity重入，以及独立规范/Spec审查。观察wait失败时owner保留是诚实的未知状态，不能据此同盘重启其他VMM。Main与Guest最终构建、版本、签名和canonical同步由主协调串行执行；精确动作在 E/final-freeze.json。无性能结论、无新依赖、无网络、无系统资源修改、无commit。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
