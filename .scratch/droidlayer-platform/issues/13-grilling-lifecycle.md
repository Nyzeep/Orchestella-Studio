# 生命周期：后台运行、崩溃恢复与电池立场

Labels: wayfinder:grilling
Type: grilling
Status: open
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
