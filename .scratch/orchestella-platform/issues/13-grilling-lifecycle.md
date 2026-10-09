# 生命周期：后台运行、崩溃恢复与电池立场

Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 04

## Question

VM 的生命周期承诺：后台长跑用什么机制（FGS 类型/唤醒锁）、App 被杀/重启后 VM 状态如何恢复、电池策略是否给用户选项、QEMU 子进程受 phantom process killer 影响如何处理？

## Why it matters

"手机上的 Linux"的可用性一半取决于生命周期工程；这决定产品能承诺哪些场景（长编译/服务器/常驻服务）。

## Known facts

- Podroid：FGS specialUse + PARTIAL_WAKE_LOCK + PDEATHSIG（注意线程作用域坑）；支持开机自启（01 票）。
- Phantom killer 32 进程全局上限仍活跃，Android 14+ 有开发者选项关闭开关（round-0 报告 §2.1）。
- AVF VM 由系统 VirtualizationService 托管，进程死亡语义与 QEMU 子进程不同（细节需原型确认）。

## Working recommendation

MVP 承诺：前台使用可靠；后台运行"尽力而为"（FGS+wake lock，如实告知 OEM 查杀风险）；App 被杀→VM 停止（v1 不承诺 VM 存活于进程外），但实例状态持久化、重启后一键恢复；电池策略给"性能/均衡"两档。

## Reversibility

"App 被杀→VM 停止"日后若要升级（VM 独立存活），需 AVF 层深挖——已在 map fog 中跟踪，不阻塞本票。

## Answer

2026-09-05 用户采纳。固化于 **docs/adr/0011-lifecycle.md** 与 CONTEXT.md 词条（实例状态 / 冷恢复）。

决定要点：
1. 前台完全可靠；后台尽力而为（specialUse FGS + 唤醒锁 + 忽略电池优化引导 + OEM 风险如实告知）。
2. 进程死亡 = VM 停止（v1 不承诺独立存活）；实例状态持久化 + 一键冷恢复从第一天存在。
3. 异常终止标记 + 清理/日志导出路径；重启默认不自启（可选自启）。
4. 电池两档（性能/均衡）+ 常驻状态通知；锁屏默认继续运行。
5. phantom killer：宿主进程模型收敛 + 官方开关指引（非功能前提）。
6. 热恢复进雾区；"VM 独立存活"待 AVF 原型数据，若证实走能力矩阵增强。

解锁：图形路线分期票（14）。
