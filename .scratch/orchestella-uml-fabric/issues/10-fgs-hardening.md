# 10: T4 · 前台服务硬化（真实缺口四项）

Type: implementation
Status: resolved（2026-11-02，零设备产品化轮）
Priority: P0
Owner: （待分配）
Blocked by: None
External gates: 无（零设备；行为项标 not-tested）

## 背景（已核实：大部分已具备）

| 能力 | 现状 | 坐标 |
|---|---|---|
| `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | **已有** | `app/src/main/AndroidManifest.xml:112-119` |
| 四权限（FGS / FGS_SPECIAL_USE / WAKE_LOCK / POST_NOTIFICATIONS） | **已有** | `AndroidManifest.xml:6,12,13,14` |
| 通知渠道 IMPORTANCE_LOW | **已有** | `OrchestellaForegroundService.java:191` |
| PARTIAL_WAKE_LOCK、非引用计数 | **已有** | `:171-173` |
| 启动前抬前台 + launch 后二次确认（带 generation） | **已有且更强** | `MainActivity.kt:1683-1684`、`:1844-1846` |

真机证据：HyperOS 无 FGS 时熄屏 33 分钟整组冻结；MagicOS 上 specialUse FGS + PARTIAL_WAKE_LOCK 60 分钟 `frz=0×12`。

## 真实缺口（本票范围）

1. **`startForeground` 未显式带 type**（`:137` 两参）。targetSdk=36 下靠 manifest 的 `TYPE_MANIFEST` 解析可运行，但 manifest 一旦被改/被覆盖就抛 `MissingForegroundServiceTypeException`。改为 `SDK_INT >= 34` 走三参 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，否则保持两参（参照 `research/uml-runner/.../KeepAliveService.java:52-57`）。
2. **wake lock 12h 且无续期**（`:176`）。改为常量 `WAKE_LOCK_TIMEOUT_MS = 2h`；`acquireWakeLock()` 在已持有且剩余不足时**续期**（记录 `acquiredAtMs`，剩余 < 60min 时 release+acquire）。依据：研究侧 `RunnerActivity.java:61,346` 用 2h；官方无 2h 规定（见 A4 笔记），2h 是本项目策略，注释要写清「本项目策略，非平台限制」。
3. **无 finally 语义**（`:135-137` 先 acquire 再 startForeground）。若 `startForeground` 抛异常，wake lock 悬挂到超时。改为 `try { startForeground } catch { release; throw }` 或 `finally` 语义等价结构。
4. **开机自启空转保活**（`BootCompletedReceiver.java:46-51` → service `:135`）：无实例运行也抬 FGS 并 acquire 锁。改为无实例时只抬通知不持锁（或直接不抬），保持「冷恢复实例后才 acquire」。
5. **`POST_NOTIFICATIONS` 运行时申请缺失**：FGS 仍成立但通知不可见，用户无法从通知栏感知/停止。加一个最小 helper（`NotificationPermissionPrompt.java`）并在既有「launch 前抬前台」位置调用一次（API 33+、未授予才请求）。**只加一行调用**，不动 MainActivity 的启动编排逻辑。

## 测试

- 可测部分抽成纯策略：新增 `WakeLockPolicy`（输入：now、heldSince、timeout、renewThreshold → 输出 acquire / renew / noop）放 `app/src/test` 可覆盖的位置或 `app` 内的包级静态类，JUnit4 覆盖：首次 acquire、未到期 noop、临近到期 renew、超时后 re-acquire。
- `-Werror` 不适用于 `:app`（只有 runtime 模块开），但仍不要留 warning。
- 行为项（三参 startForeground、权限弹窗、开机自启）标 `not-tested`，并在票内写下设备窗口复验步骤。

## 边界

- 不动 manifest 的 service 声明与 subtype 文案（已合规）。
- 不动 `MainActivity` 的启动时序（只在既有位置加一行权限请求）。
- 不改 `InstanceLaunchSequence` / `ADAPTER` 的 UNCONFIRMED 不释放语义。
- 不改 `BatteryPolicy` 展示逻辑。

## 完成判据

`./gradlew :app:assembleDebug` 绿；`:app:testDebugUnitTest` 绿（含新增策略测试）；manifest 无 diff。

## Comments

- 2026-11-02 / 主会话：建票。依据 `.scratch/orchestella-uml-fabric/notes/A2-launcher.md` §2.3（逐项现状表）与 §5（QEMU 车道已在 FGS+wake lock 之下，不裸后台）。范围已从 prompt 的「移植四权限 + specialUse」收窄为真实缺口四项 + 权限申请。
- 2026-11-02 / 实现子代理 + 主会话复核：**resolved**。① `startForeground` 在 API 34+ 走三参 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；② `WAKE_LOCK_TIMEOUT_MS=2h` + 剩余 <60min 时 release+re-acquire 续期（注释注明「本项目策略，非平台限制」）；③ `try/finally` 保证抬前台失败即释放；④ 无 generation（开机冷恢复）只抬通知不持锁；⑤ `NotificationPermissionPrompt` 在既有 launch 入口加一行（API<33 或已授予即返回）。决策逻辑抽 `WakeLockPolicy`（纯函数）。证据：`:app:assembleDebug` BUILD SUCCESSFUL、`WakeLockPolicyTest` `tests=4 failures=0`、`AndroidManifest.xml` 无 diff。**not-tested**（设备）：FGS type 展示、2h 续期、开机自启无锁、权限弹窗 —— 见报告 §7 第 4–7 项。
