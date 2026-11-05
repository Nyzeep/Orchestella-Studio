# Ticket: 生产代码 78 处纯空 catch 全量闭合（P3-1）实施报告

- 票号/标题：P3-1（工程质量积压清单 2026-11-04）——生产代码 78 个纯空 catch 补理由注释 + 失败不可见类小修复
- 执行 session / 模型 / thinking：Lane R 空 catch 闭合智能体 / GLM-5.3-Flash / thinking
- 分支：`chore/empty-catch-closure`　基线 commit：`2d66f67`（main）
- 工作区：`ASCII 路径 worktreet/orch-lane-r`（独立 worktree，未触碰主检出）
- 报告日期：2026-11-06

## 1. 任务目标

按 `docs/reports/quality-backlog-20261104.zh-CN.md` 末尾 Addendum（2026-11-05 · 空catch分诊抽样）的三档分诊与四类注释模板，对生产代码 78 处纯空 catch 做全量闭合：失败不可见类小修复（≥5 处）、其余补"有意为之"的真实理由注释、测试基建维持现状，并以 `:app:testDebugUnitTest`（带 `-Porchestella.assets.allowMissingPayload=true`）+ 受影响 runtime 模块 check 验证。

## 2. 实际完成

### 2.1 清单重建（口径勘误，重要）

Addendum 的 78 处清单在基线 `9f18c31` 上由脚本重建但未随报告入库。本 lane 以同一方法重扫本树基线 `2d66f67`（生产代码 = `app/src/main` + `runtime/*/src/main` + `accel-extension/src/main`，Java/Kotlin，catch 块体除注释外无任何语句）：

- **严格"纯空体"命中 73 处**；与 78 的差值 **5 处全部是 Kotlin 命名捕获** `catch (_: X) { }`（`AboutActivity.kt:265`、`MainActivity.kt:346`、`MainActivity.kt:1644`、`SetupWizardActivity.kt:200`、`SetupWizardActivity.kt:924`）——原始扫描把命名捕获计入，本轮按任务口径"命名捕获不算空体"应剔除，但其中：
  - `MainActivity.kt:1644` 被任务显式点名为修复档 → **按修复档执行**；
  - `MainActivity.kt:346` 是 Addendum 15 处建议注释档之一（#16）→ **按注释档执行**；
  - 其余 3 处为机械类 → 一并注释，达成 78 处全量闭合（否则重扫仍会命中）。
- 分布与 §2 正文逐文件核对一致（`PersistentTerminalSessionPool` 6 处为 9f18c31 起的漂移，与 Addendum 记载相符）；**accel-extension 命中 0**。
- 另有测试代码空 catch 约 22 处，其中 2 处抽样锚点（`BridgeClientResponsePumpTest.java:272`、`MockHttpServer.java:229`）按分诊**维持现状**，不改动。

### 2.2 处置计数

| 档位 | 处数 | 说明 |
|---|---|---|
| **修复（失败可见化）** | **6** | 任务点名 5 处 + 新增 1 处（`DesktopActivity.java:228`，见 §3） |
| **注释（四类模板）** | **72** | 全部在 catch 体内补一句真实反映语义的理由注释（口径对齐 `SocketPortForwarder.java:69-79`） |
| **维持现状** | **2** | 测试基建（§4 表末两行） |
| 合计 | **80** | 78 生产 + 2 测试基建抽样 |

### 2.3 重扫验证（本树，改动后）

- 生产代码纯空体：**0 处**（改动前 73 严格 + 5 命名捕获；`comment_only` 由 70 → 138 = 70 存量 + 72 新注释 − 4 处命名捕获被扫描器口径排除）。
- 扫描器：`scan.py`（括号/字符串/注释感知的状态机扫描，非 grep），结果 JSON 与上下文转储存于 worktree 外 `ASCII 路径 worktreet/scan-empty-catch/`（脚本不入库，方法与 Addendum 记载一致）。

## 3. 修复类逐条说明（6 处）

全部为"吞掉的失败对用户/上层不可见且无其他呈现面"，修复只加可观测性、不改行为语义（`SocketPortForwarder` 既定口径）：

| # | 位置（基线 2d66f67 行号） | 吞掉的是什么 | 修复 |
|---|---|---|---|
| 1 | `TerminalActivity.java:1032`（Addendum #1） | 桥就绪等待 `awaitCurrentControlReady` 的 `IOException`：失败即不回调 `onReady`，排障只剩启动超时一个症状 | catch 内 `android.util.Log.w("TerminalActivity", "bridge ready wait failed: " + 类名)`，与同文件 :1070 的 "Bridge control ended" 同型（只记异常类名，不插值异常消息） |
| 2 | `PersistentTerminalSessionPool.java:189`（Addendum #2） | 终端输出泵循环意外 `Exception`（EOF 正常返回走 `break` 不入此路），会话静默结束无原因记录 | catch 参数改 `pumpFailure`，`Log.d("TerminalPump", "pty pump ended abnormally: " + 类名)` 后照常走 finally 收尾（结束通知语义不变） |
| 3 | `LowLatencyAudioPlayer.java:167`（Addendum #3） | `audioTrack.play()` 失败：状态机已先置 `RUNNING`，UI 说在播、实际无声的状态漂移 | 回退 `isPaused=true` + `transitionState(PAUSED)`（监听器可观察到回滚）+ `Log.w("AudioPlayer", ...)` |
| 4 | `MainActivity.kt:1644`（Addendum #4，命名捕获 `_`） | `reconcileOrphanInstances` 对账整轮异常被吞，实例停留幽灵 `RUNNING` 态且无线索 | 改**逐实例** try/catch + `Log.w`（单实例失败不再放弃整轮）；外层保留整轮 catch 并对存储级失败记 `Log.w`（list/构造失败仍整轮跳过，行为兼容） |
| 5 | `VirglJniBridge.java:127`（Addendum #5） | `nativeSetMemoryQuotaMb` 失败（catch Throwable）：上层认为配额已设置、实际未生效且不可见 | 保持 void 契约，`LOGGER.log(WARNING, "nativeSetMemoryQuotaMb failed, native quota not applied (configured=..)", t)`（同文件 :91 nativeCleanup 同型） |
| 6 | `DesktopActivity.java:228`（**任务第 3 条单列新增**） | `desktop-bridge-serve` 线程内 `BridgeConnector.serveSession` 任意异常：桌面桥 serve 循环死亡无任何呈现面（本处 `Events` 仅空实现 onReady/onPong/onResize，无失败回调，桌面侧也无 TerminalActivity 式桥状态栏） | catch 内 `Log.w("DesktopBridge", "desktop bridge serving ended: " + 类名)`；serve 正常结束走 `awaitResponsePump()` 返回不入此路，异常结束必经此 catch，日志无常态噪声 |

升档理由（#6）：任务第 3 条"若个别 catch 的真实语义是吞掉了不该吞的错误，按建议修复处理并纳入计数，报告中单列"。该 catch 与 TerminalActivity:1070（桥 serve 线程，已有 `Log.w`）完全同型，唯桌面侧漏配，属"失败不可见"共性。

**明确未升档的边界判定**（诚实注释而非修复，逐处读上下文后确认）：

- `LowLatencyAudioPlayer:145`（pause 失败）：`pause()` 仅在 track 已失效时抛 `IllegalStateException`（可听播放中不会抛），保持 PAUSED 与用户所听一致，无 :167 那种状态漂移 → 注释档，注释写明该依据。`LowLatencyAudioPlayer:187`（stop/flush 失败）：worker 已先停，STOPPED 是准确终态 → 注释档。
- `DesktopActivity:135`（帧确认失败）：失败经 `presentationGate.finish(ticket, false)` 已有呈现面 → 注释档。
- `ImeBridgeInputConnection:174`：`return false` 本身就是 IME 框架的"未送达"信号，非静默 → 注释档。

## 4. 最终清单表（78 + 2）

行号为**基线 `2d66f67`**（重建口径）；本轮改动后行号整体后移，定位以类名/方法名为准。模板：①close/清理 best-effort ②观察者隔离 ③解析降级 ④防御读/OEM 兼容。

### app 模块（54 处：修复 5 + 注释 49）

| 位置 | 吞掉的异常 | 档位 | 处置 |
|---|---|---|---|
| `TerminalActivity.java:1032` | IOException（桥就绪等待失败） | **修复** | Log.w（§3#1） |
| `PersistentTerminalSessionPool.java:189` | Exception（PTY 泵异常死亡） | **修复** | Log.d + 照常收尾（§3#2） |
| `LowLatencyAudioPlayer.java:167` | Exception（play 失败状态漂移） | **修复** | 状态回退 + Log.w（§3#3） |
| `MainActivity.kt:1644` | Exception（孤儿对账整轮） | **修复** | 逐实例 try/catch + Log（§3#4） |
| `DesktopActivity.java:228` | Exception（桌面桥 serve 线程死亡） | **修复** | Log.w（§3#6） |
| `PersistentTerminalSessionPool.java:109` | IOException（响应写回 pty） | 注释① | 写失败=会话将亡，泵循环经 notifySessionEnded 收尾 |
| `PersistentTerminalSessionPool.java:148` | Exception（会话结束监听器） | 注释② | 观察者隔离，one-shot 通知 |
| `PersistentTerminalSessionPool.java:179` | Exception（输出监听器循环） | 注释② | 观察者隔离，不破坏 ringBuffer append |
| `PersistentTerminalSessionPool.java:211` | Exception（provisioning 监听器） | 注释② | 观察者隔离，UI 侧监听器 |
| `PersistentTerminalSessionPool.java:347` | Exception（session.close） | 注释① | 关闭路径 best-effort，结束通知在后不受影响 |
| `TerminalHistoryStore.java:168` | IOException（finally 删临时文件） | 注释① | 写结果已定，临时文件清理 best-effort |
| `LowLatencyAudioPlayer.java:145` | Exception（pause 失败） | 注释① | track 已失效时才抛，PAUSED 与所听一致（§3 边界判定） |
| `LowLatencyAudioPlayer.java:187` | Exception（stop/flush 失败） | 注释① | worker 已停，STOPPED 为准确终态 |
| `LowLatencyAudioPlayer.java:205` | Exception（track.release） | 注释① | shutdown 期 best-effort teardown |
| `LowLatencyAudioPlayer.java:210` | IOException（audioSource.close） | 注释① | 同上 |
| `LowLatencyAudioPlayer.java:348` | Exception（状态监听器） | 注释② | 观察者隔离 |
| `TcpAudioSource.java:50` | IOException（连接失败路径关 socket） | 注释① | 主异常 rethrow，半连 socket 关闭 best-effort |
| `TcpAudioSource.java:71` | IOException（inputStream.close） | 注释① | close best-effort，字段置空不受影响 |
| `TcpAudioSource.java:78` | IOException（socket.close） | 注释① | 同上 |
| `LocalSocketAudioSource.java:47` | IOException（同 TcpAudioSource:50） | 注释① | 同型 |
| `LocalSocketAudioSource.java:68` | IOException | 注释① | 同型 |
| `LocalSocketAudioSource.java:75` | IOException | 注释① | 同型 |
| `DesktopActivity.java:135` | Exception（帧确认失败） | 注释 | finish(ticket,false) 即失败呈现面（§3 边界判定） |
| `DesktopActivity.java:335` | Exception（STATUS 轮询探测） | 注释 | 轮询失败跳过本轮，下轮重试 |
| `DesktopActivity.java:520` | Exception（沉浸式 flag 应用） | 注释④ | 装饰性 best-effort，退回默认系统栏 |
| `ImeBridgeInputConnection.java:174` | Exception（按键转发） | 注释 | `return false` 即 IME 框架的失败信号（§3 边界判定） |
| `LogViewerActivity.java:214` | Exception（`logcat -c`） | 注释① | 部分 ROM 拒绝 exec，旧日志留存至下次刷新 |
| `MainActivity.kt:346`（命名捕获） | Exception（状态栏时钟格式化） | 注释 | 装饰性时钟，失败不动标签（Addendum #16） |
| `MirrorSpeedTester.java:116` | Exception（finally disconnect） | 注释① | 测速结果已定，连接本就要走 |
| `NativeGlesSurfacePresenter.java:45` | Throwable（nativeGetVersion） | 注释④ | 防御读，回退固定不可用文案 |
| `NetworkGatewayDaemon.java:91` | IOException（删 owner socket 文件） | 注释① | 残留 unix socket 由 gateway 互斥判定兜底（Addendum #7） |
| `NetworkGatewayDaemon.java:224` | IOException（gvproxy 日志泵 readLine） | 注释① | 随进程退出结束，预期收尾（Addendum #8） |
| `PackagedAssetStreams.java:116` | Throwable（ApplicationInfo 读取） | 注释④ | 防御读，null 走调用方 AssetManager 回退 |
| `QemuInstanceLauncher.java:762` | IOException（QEMU 日志泵） | 注释① | 与 gvproxy 日志泵同型（Addendum #9） |
| `QemuInstanceLauncher.java:1022` | IOException（取消路径 socket.close） | 注释① | closed CAS 恰一次；调用方要么 rethrow 真错误要么持关闭句柄（Addendum #10） |
| `SetupWizardActivity.kt:200`（命名捕获） | Exception（takePersistableUriPermission） | 注释 | 授权持久化 best-effort；丢失在挂载使用点可见、可重选 |
| `SetupWizardActivity.kt:924`（命名捕获） | Exception（网卡枚举） | 注释④ | 防御读回退 null，显式连通性测试才是结论 |
| `WaylandSurfaceController.java:196` | Exception（drawBitmap/lockCanvas 段） | 注释 | 帧级失败丢帧自愈，finally 保证 unlockAndPost（Addendum #17） |
| `WaylandSurfaceController.java:201` | Exception（unlockCanvasAndPost） | 注释① | surface 拆除竞态，无法补救 |
| `WaylandSurfaceController.java:218` | Exception（clear 填色） | 注释 | 装饰性，保留上一帧内容 |
| `WaylandSurfaceController.java:223` | Exception（unlockCanvasAndPost） | 注释① | 同 :201 |
| `WaylandSurfaceView.java:78` | Exception（focusable 视图属性） | 注释④ | 装饰性属性，init 必须继续 |
| `WaylandSurfaceView.java:84` | Exception（getHolder） | 注释④ | holder 为 null 仅关闭绘制路径，注册处有判空 |
| `AboutActivity.kt:265`（命名捕获） | Exception（头像 PNG 缓存写） | 注释① | 缓存 best-effort，头像本体已解码显示 |
| `FileDropTargetListener.java:93` | IOException（桥通知失败） | 注释② | 通知是附加呈现面，传输结论已由回调参数承载 |
| `FileDropTargetListener.java:147` | Throwable（跨应用拖拽授权） | 注释④ | OEM 兼容；授权缺失在 stageUris 读错误处可见 |
| `FileDropTargetListener.java:172` | Throwable（permissions.release） | 注释① | finally 清理 best-effort，传输结果已定 |
| `FileDropTargetListener.java:292` | Exception（DISPLAY_NAME 查询） | 注释③ | 降级取 URI 末段，调用方只要标签 |
| `FileDropTargetListener.java:306` | Exception（SIZE 查询） | 注释③ | 降级 -1（未知大小），seam 显式支持值 |
| `PointerCaptureHandler.java:239` | Throwable（requestPointerCapture） | 注释④ | OEM 兼容（Addendum #18） |
| `PointerCaptureHandler.java:248` | Throwable（releasePointerCapture） | 注释④ | 同上 |
| `PointerCaptureHandler.java:272` | Throwable（setPointerIcon TYPE_NULL） | 注释④ | 图标 API 为 OEM 面，失败保留默认光标 |
| `PointerCaptureHandler.java:285` | Throwable（restorePointerIcon） | 注释④ | 同上 |
| `terminal/TerminalSessionInstance.java:92` | Exception（输出监听器） | 注释② | 观察者隔离 |

### runtime 模块（24 处：修复 1 + 注释 23）

| 位置 | 吞掉的异常 | 档位 | 处置 |
|---|---|---|---|
| `engine-qemu/VirglJniBridge.java:127` | Throwable（native 配额设置失败） | **修复** | WARNING 日志（§3#5） |
| `engine-qemu/VirglJniBridge.java:104` | Throwable（nativeGetAllocatedMemoryBytes） | 注释④ | 防御读回退 JVM sentinel（Addendum #6） |
| `engine-qemu/VirglJniBridge.java:115` | Throwable（nativeGetMemoryQuotaMb） | 注释④ | 回退最近一次配置值 |
| `engine-qemu/VirglJniBridge.java:137` | Throwable（nativeIsRobustnessEnabled） | 注释④ | 回退 JVM 侧 flag |
| `engine-qemu/VirglJniBridge.java:148` | Throwable（nativeTryAllocateTestMemory） | 注释④ | 测试辅助防御读，回退 JVM sentinel |
| `engine-qemu/VirglJniBridge.java:160` | Throwable（nativeReleaseTestMemory） | 注释④ | 同上 |
| `engine-qemu/VirglJniBridge.java:171` | Throwable（nativeSimulateShaderCrash） | 注释④ | 回退 JVM mock 路径 |
| `engine-qemu/QemuQmpHostfwdClient.java:98` | IOException（reader.close） | 注释① | QMP 会话关停 best-effort |
| `engine-qemu/QemuQmpHostfwdClient.java:101` | IOException（writer.close） | 注释① | 同上 |
| `contract/BridgeClient.java:67` | Exception（diagnostic 监听器） | 注释② | 诊断监听器隔离，不拖垮被诊断的控制路径 |
| `contract/BridgeClient.java:212` | IOException（inbound.close） | 注释① | 传输已失败，close best-effort |
| `contract/BridgeClient.java:213` | IOException（outbound.close） | 注释① | 同上 |
| `instance/InstanceLifecycle.java:961` | IOException（删除清扫逐文件） | 注释① | 已知产物删除在上方会抛错；此处只兜残留，不改变 delete 结果 |
| `instance/InstanceSnapshotManager.java:237` | IOException（快照逐文件删） | 注释① | 单文件失败不中止整目录清除 |
| `instance/InstanceStorageManager.java:521` | IOException（finally 删临时卷） | 注释① | 主失败已在上游抛出（Addendum #19） |
| `network/BridgeProxyGateway.java:237` | NumberFormatException（URI 端口解析） | 注释③ | 保 host，端口走默认 80（Addendum #11） |
| `network/BridgeProxyGateway.java:308` | IOException（数据泵 read） | 注释 | 对端断开结束泵，finally CMD_CLOSE 为呈现面（Addendum #12） |
| `network/BridgeProxyGateway.java:312` | IOException（finally CMD_CLOSE） | 注释① | closeChannel 兜底回收通道 |
| `network/BridgeProxyGateway.java:322` | IOException（502 响应发送） | 注释① | 传输已坏，closeChannel 收口 |
| `network/BridgeProxyGateway.java:364` | IOException（rawIn.close） | 注释① | 关停期 best-effort |
| `network/BridgeProxyGateway.java:367` | IOException（rawOut.close） | 注释① | 同上 |
| `network/BridgeProxyGateway.java:386` | IOException（guest→target 写） | 注释 | 写坏即断，读泵将感知断开并走收口 |
| `network/BridgeProxyGateway.java:392` | IOException（socket.close） | 注释① | 通道拆除 best-effort |
| `network/download/ResilientDownloadEngine.java:368` | NumberFormatException（Content-Range total） | 注释③ | -1 = 未知总长继续（Addendum #20） |

### 测试基建（维持现状 2 处）

| 位置 | 说明 |
|---|---|
| `runtime/contract/src/test/.../BridgeClientResponsePumpTest.java:272` | 命名捕获 `expectedClosedPipe`，测试 fake guest 写已关管道，语义自明（Addendum #21） |
| `runtime/network/src/test/.../download/MockHttpServer.java:229` | 测试 fake server `serverSocket.close()` best-effort（Addendum #22） |

## 5. 未完成 / 与预期偏差

- **无未完成项**。偏差 1：78 → "73 严格 + 5 命名捕获"的口径勘误（§2.1），5 处全部处置（其中 1644 修复、4 处注释）；偏差 2：修复档 5 → 6（新增 `DesktopActivity.java:228`，任务第 3 条授权的单列升档，§3）。

## 6. 修改的模块

| 模块 | 文件数 | 内容 |
|---|---|---|
| `app`（main） | 23 | 修复 5 文件 + 注释 19 文件 |
| `runtime/contract` | 1 | BridgeClient 3 处注释 |
| `runtime/engine-qemu` | 2 | VirglJniBridge（1 修复 + 6 注释）、QemuQmpHostfwdClient 2 注释 |
| `runtime/instance` | 3 | 3 处注释 |
| `runtime/network` | 2 | BridgeProxyGateway 8 注释、ResilientDownloadEngine 1 注释 |

**边界核实**：未触碰 `.github/workflows/**`、`tools/ci/**`、任何 `build.gradle.kts`。任务点名的其他 lane 文件逐一核实：`AcceleratorLaunchDispatcher.kt` 的 3 个 catch（:184/:187/:190）均非空体、无需处理；`FabricWidthDecision.java` 在本树（基线 2d66f67）不存在；Settings 相关文件无空 catch 命中——故与其他 lane 无交集、无 follow-up 移交项。未触碰主检出。

## 7. 重要设计决策

1. **注释全部写入 catch 体内部**（而非 try 上方）：与 `SocketPortForwarder.java:69-79` 仓库范式一致，且使"注释豁免"在未来 detekt `EmptyCatchBlock` 规则下直接生效。
2. **修复类只加可观测性**：6 处全部保持方法签名与控制流不变（§3 表），日志只记异常类名、不插值异常消息（对齐 `BridgeClient` "异常消息可能含对端数据"的既有红线与 TerminalActivity:1070 既有写法）。
3. **诚实注释原则**：Addendum 曾把 `LowLatencyAudioPlayer:145` 归入 close/release 同型，本轮逐处读上下文后认定其无状态漂移（依据见 §3 边界判定），注释按真实语义写而非套模板。
4. **命名捕获一并注释**：4 处 Kotlin `catch (_:)` 虽按口径不算"空体"，但为达成 78 全量闭合与重扫干净（且其中 346 本就在 Addendum 注释档），补了一句话注释；此类是 1 行注释级最小 diff。
5. 本轮未引入任何日志栈变更（android.util.Log 仅用于 app 层 4 个文件、java.util.logging 仅 VirglJniBridge 既有 LOGGER），未触碰 P3-6 日志口径议题。

## 8. 测试

- **契约/JVM 测试**（全部真实运行，命令带 `-Porchestella.assets.allowMissingPayload=true`）：
  - `:app:testDebugUnitTest` → **1171 tests / 0 failures / 0 errors**（与本树基线 1171/0 持平；JUnit XML 汇总 `app/build/test-results/testDebugUnitTest/`）；
  - `:runtime:contract:check` → 141 tests / 0 fail；
  - `:runtime:engine-qemu:check` → 2 tests / 0 fail；
  - `:runtime:instance:check` → 52 tests / 0 fail；
  - `:runtime:network:check` → 通过（该模块 test NO-SOURCE，socketForwarderDiagnosticsTest 任务输出 SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK）。
  - 4 个 runtime 模块即本轮全部受影响 runtime 模块（改动最多的抽样即全集）。
- **静态重扫**：改动后生产代码纯空 catch **0 处**（§2.3）。
- **冒烟清单**：本票不涉及（零设备票，无 adb/设备调用）。

## 9. Benchmark / 实测数据

本票不涉及。

## 10. 风险

- **低**：注释类为纯注释/等价重排（单行 catch 展开为多行），编译产物语义不变（`:app` 1171 测试与 4 模块 check 全绿佐证）。
- **低**：修复类 6 处新增日志在热路径上的开销可忽略（TerminalActivity:1032、PersistentTerminalSessionPool:189、DesktopActivity:228 均只在异常路径打印；LowLatencyAudioPlayer:167 仅失败路径；VirglJniBridge:127 仅失败路径；MainActivity:1644 仅对账失败路径）。
- **低**：`LowLatencyAudioPlayer:167` 的状态回滚会让监听器观察到 RUNNING→PAUSED 的往返（这正是修复目的）；无既有测试断言该序列，全量单测通过。
- **注**：gradle 未联网、未调用 adb、未连设备；本轮所有验证宿主完成，零设备。

## 11. 新发现

- 测试代码空 catch 存量约 22 处（Addendum 口径），本轮仅按分诊维持 2 处抽样锚点，其余未盘点——若 P2-3 detekt 落地，`EmptyCatchBlock` 建议只对生产源集启用，测试源集用 baseline。
- `NetworkGatewayDaemon.java:91` 与 `InstanceLifecycle.java:961` 的注释同时写明了兜底依赖（gateway 互斥判定、known-artifact 删除在上游抛错）；若未来重构挪动这些兜底，注释需同步复核。
- `LogViewerActivity.clearLog` 在 `logcat -c` 失败时仍 Toast "已清空"（提示语与实际不完全一致，装饰级）；本轮按注释档处理，如需精确提示属产品口径，留分诊。

## 12. 对后续 ticket 的影响

- **P2-3（detekt + baseline）**：`EmptyCatchBlock` 规则现可直接以"注释豁免"启用且 baseline 为空（生产侧存量已清零），实施成本较原计划进一步下降。
- 后续轮若改终端池/音频播放器/桥 serve 相关行为，请先读 §4 表中对应注释——它们现在是这些 catch 的契约说明。

## 13. Commit

分支 `chore/empty-catch-closure`（基于 main `2d66f67`），共 3 个 commit：

1. `2186098` — `fix(quality): 6 处空 catch 失败可见化——终端就绪等待/PTY 泵/音频恢复/孤儿对账/显存配额/桌面桥`（修复档 6 文件）
2. `741a250` — `chore(quality): 72 处空 catch 补真实理由注释——P3-1 全量闭合注释档`（注释档 29 文件，220+/35-）
3. 本报告自身的入库 commit（`docs(reports): 空 catch 全量闭合实施报告入库——78 处分诊清单、6 修复、1171 测试全绿`，`git add -f` 过 docs ignore；hash 见分支 HEAD）。

## 14. Ticket 状态

**completed** —— 78 处生产空 catch 全量闭合（6 修复 + 72 注释），2 处测试基建按分诊维持现状，重扫归零，1171 app 测试与 4 个 runtime 模块 check 全绿；未越界（无 workflows/tools-ci/gradle-script/其他 lane 文件改动），未触发 Architecture Change Policy（无接口/ADR 层面变更，仅注释与异常可见性日志）。

---

报告人：Lane R 空 catch 闭合智能体（GLM-5.3-Flash）｜ 日期：2026-11-06 ｜ 耗时：单 session（清单重建 + 78 处分诊 + 35 文件改动 + 全量验证 + 报告）
