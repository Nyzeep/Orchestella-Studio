# P10 实施报告：Fast Lane 可观测性与安全（零设备）

> 票：[P10-telemetry-security.md](../../../.scratch/orchestella-fastlane/issues/P10-telemetry-security.md)
> 模块：`runtime/fastlane-telemetry` ｜ 分支：`feature/fastlane-qemu-observability` ｜ 基线：`018855bc`
> 结论：10 类阶段事件、9 项指标、schema 金样、脱敏、有界队列、停止未确认诊断与 27 recorder 适配器均已实现；**46 个 JVM 测试通过**。真机/Android 集成 `not-tested`。

## 1. 目标与边界

让 Fast Lane 的启动、停止、失败可诊断，同时保证 token、口令、按键正文、源码、URL 凭据、完整路径不进入日志。只做纯 JVM 模块：

- 事件 schema 版本化、未知字段忽略、缺失阶段 `not_observed`；
- 指标只含计数/耗时/大小；
- 生产 debug `NONE`；`FULL` 仅 QA 且显式标记削弱保护；
- 停止未确认保留 `stage/pid/grace/exit`，不写 `STOPPED`；
- 与 ticket-27 `LaunchTimeline` 的只读适配器；
- 不修改 27、不接 Android UI、不跑设备。

## 2. Schema

### 2.1 事件类型（10）

`FASTLANE_REQUESTED`、`VM_CREATED`、`PAYLOAD_READY`、`TERMINAL_READY`、`PROXY_READY`、`EXPORT_READY`、`DISTCC_READY`、`STOP_REQUESTED`、`STOP_CONFIRMED`、`STOP_UNCONFIRMED`。

### 2.2 单行 JSON（schema v1）

```json
{"schema":1,"event":"VM_CREATED","instanceId":"i-abc123","elapsedMs":12,"status":"OK","debugLevel":"NONE","protectionWeakened":false,"metrics":{"startupMs":1234,"payloadReadyMs":900,"terminalBytes":10,"proxyBytes":20,"distccJobs":2,"distccFailures":1,"distccTotalMs":450,"cpuPercent":42,"rssBytes":1048576}}
{"schema":1,"event":"STOP_UNCONFIRMED","instanceId":"i-abc123","elapsedMs":5000,"status":"FAILED","debugLevel":"NONE","protectionWeakened":false,"stop":{"stage":"PROCESS_EXIT_WAIT","pid":4242,"graceMs":5000,"exitCode":null}}
```

金样文件：[`fastlane-events-v1.jsonl`](../../../../runtime/fastlane-telemetry/src/test/resources/golden/fastlane-events-v1.jsonl)。测试逐字节比较，字段顺序固定。

### 2.3 字段规则

| 字段 | 规则 |
| --- | --- |
| `schema` | 当前 `1`；`!= 1` 拒绝，不静默重解释 |
| `event` | 10 个枚举之一 |
| `instanceId` | `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`；拒绝路径、URL、邮箱、IPv4、JWT/GitHub/AWS token 形状 |
| `elapsedMs` | 非负、单调不减；时钟回退被钳制并计数 |
| `status` | `OK` / `FAILED` / `CANCELLED` |
| `debugLevel` | `NONE` / `FULL`；`FULL` 时 `protectionWeakened:true` |
| `metrics` | 可选；仅数字；无采样时省略 |
| `stop` | 仅 `STOP_CONFIRMED`/`STOP_UNCONFIRMED`；后者必须 `FAILED` |

**未知字段**：`FastLaneEventCodec` 只读已知字段，未知顶层/嵌套字段跳过；`schema=2` 拒绝；嵌套深度上限 16，避免栈溢出。

**缺失阶段**：`FastLaneTelemetry.notObserved()` 按枚举顺序返回未记录阶段；`summaryJson()` 输出：

```json
{"schema":1,"instanceId":"i-1","enabled":true,"observed":["FASTLANE_REQUESTED","VM_CREATED"],"notObserved":["PAYLOAD_READY","TERMINAL_READY","PROXY_READY","EXPORT_READY","DISTCC_READY","STOP_REQUESTED","STOP_CONFIRMED","STOP_UNCONFIRMED"],"dropped":0,"clockRegressions":0}
```

不补造时间、不把缺失阶段填成成功。`enabled:false` 时 `notObserved` 为空，避免把“未采集”误读成“未发生”。

## 3. 指标

`FastLaneMetrics` 只有数字字段：

| 字段 | 含义 | 未知值 |
| --- | --- | --- |
| `startupMs` | 启动耗时 | `null` |
| `payloadReadyMs` | payload 就绪耗时 | `null` |
| `terminalBytes` / `proxyBytes` | 终端/代理累计字节 | `0` |
| `distccJobs` / `distccFailures` / `distccTotalMs` | distcc job 数/失败数/总耗时 | `0` |
| `cpuPercent` | CPU 采样（0–100） | `null` |
| `rssBytes` | RSS 采样 | `null` |

- 生产配置 **不启用** 指标采样；`QA_FULL` 才启用，且在阶段边界取快照，不跑后台定时器。
- 指标不含命令、路径、token、按键或源码；`Redactor` 另做纵深防御。
- 计数器饱和到 `Long.MAX_VALUE`；负增量拒绝。

## 4. 脱敏

`Redactor` 的公开入口只有 `opaqueId`/`reasonCode`/`redact`。事件 schema 没有自由文本字段，`redact` 是给未来诊断字符串的纵深防御。

覆盖并通过测试的类别：

| 类别 | 例子 | 结果 |
| --- | --- | --- |
| token/密码赋值 | `access_token=SECRET`、`token := SECRET`、`password=hunter2` | `[redacted]` |
| CLI 口令 | `--password hunter2` | `--password [redacted]` |
| Basic/Bearer | `Authorization: Basic ...`、`Bearer "..."` | `[redacted]` |
| URL 凭据 | `https://alice:pa/ss@host/x` | `https://[redacted]@host/x` |
| 无 scheme userinfo | `alice:s3cr3t@localhost` | `alice:[redacted]@localhost` |
| 邮箱 | `alice [at] example [dot] com`（实际测试用 JVM 字符串构造） | `[redacted-email]` |
| 绝对路径 | `/data/.../my app/secret.db`、`C:\Users\...`、`~/...` | `[redacted-path]` |
| IP | `192.168.1.10` | `[redacted-ip]` |
| 长 hex/base64/JWT/GitHub token | 32+ hex、40+ base64url、`eyJ...`、`ghp_...` | `[redacted-blob]` / `[redacted-token]` |
| 源码/按键/多行/超长 | `int main() { ... }`、`rm -rf /\n`、>120 字符 | `[redacted-content]` |

**非目标**：`redact` 不是通用 DLP；它不保证识别所有编码/变形。事件 schema 的“无自由文本”才是主保证。

## 5. 有界队列与并发

- 默认容量 128，最大 1024；`ArrayDeque`，无后台线程、无无限队列。
- 满时优先淘汰：非 STOP 事件 → `STOP_REQUESTED` → `STOP_CONFIRMED` → 最后才是 `STOP_UNCONFIRMED`；`droppedEvents` 计数，不静默当已记录。
- `mark` 串行化；`flush` 只在快照/回队时持有 recorder 锁，sink I/O 期间释放，因此慢 sink 不会阻塞生命周期 owner。flush 期间新记录的事件留在队列，等下一次 flush。
- sink 失败：保留未写事件的原顺序，返回已写条数；`lastSinkError` 只存异常类名，不存 message。`LineSink` 契约要求“原子追加或写前抛出”，避免半行重复。
- 时钟回退：`elapsedMs` 钳制到上一个值，`clockRegressions` 计数；测试用可变时钟验证 10→10→20。
- 并发测试：8 线程 × 100 次 mark，容量 64，`queued + dropped == 800`。

## 6. 停止未确认

`FastLaneStopDiagnostics` 固定字段：`stage`（`SIGNAL_SENT`/`PROCESS_EXIT_WAIT`/`VSOCK_CLOSE`/`GATEWAY_EXIT`/`UNKNOWN`）、`pid`、`graceMs`、`exitCode`（可空）。

- `STOP_UNCONFIRMED` 构造时强制 `status=FAILED` 且有 diagnostics；`mark(STOP_UNCONFIRMED)` 没有诊断会拒绝。
- `STOP_CONFIRMED` 强制 `status=OK` 且有 diagnostics。
- 事件名/字段中不出现 `STOPPED`；测试显式断言。
- 未确认停止不写实例状态；本模块只记录诊断，状态机仍由 Runtime Process Supervisor 拥有。

## 7. 与 ticket-27 的适配器

`LegacyStageAdapter` 只读映射，完全等价才转发：

| Fast Lane | ticket-27 `LaunchTimeline.Stage` |
| --- | --- |
| `FASTLANE_REQUESTED` | `LAUNCH_START` |
| `VM_CREATED` | `VM_CREATED` |
| `STOP_REQUESTED` | `STOP_REQUESTED` |
| `STOP_CONFIRMED` | `STOP_CONFIRMED` |
| `STOP_UNCONFIRMED` | `STOP_UNCONFIRMED` |

`PAYLOAD_READY`、`TERMINAL_READY`、`PROXY_READY`、`EXPORT_READY`、`DISTCC_READY` 无 27 等价阶段，返回 empty/false，**不强行映射到 `RUN_RETURNED` 等阶段**。测试把映射结果与 27 的 12 个 stage 名做子集校验。27 本身未修改。

## 8. 测试与结果

环境：JDK 21，`javac --release 17 -Xlint:all -Werror`，JUnit 4.13.2，无 Android SDK。

```bash
javac --release 17 -Xlint:all -Werror -d /tmp/fl-t-main \
  $(find runtime/fastlane-telemetry/src/main/java -name '*.java')
javac --release 17 -Xlint:all -Werror \
  -cp /tmp/fl-t-main:/tmp/junit-4.13.2.jar -d /tmp/fl-t-test \
  $(find runtime/fastlane-telemetry/src/test/java -name '*.java')
java -cp /tmp/fl-t-main:/tmp/fl-t-test:/tmp/junit-4.13.2.jar:/tmp/hamcrest-core-1.3.jar:\
runtime/fastlane-telemetry/src/test/resources \
  org.junit.runner.JUnitCore \
  ...RedactorTest ...FastLaneEventSchemaTest ...FastLaneEventCodecTest \
  ...FastLaneTelemetryTest ...LegacyStageAdapterTest
# -> OK (46 tests)
```

覆盖：schema 金样、序列化/反序列化、未知字段、未来 schema 拒绝、脱敏红/绿、有界队列、STOP 优先级、并发、时钟回退、sink 失败、disabled 零副作用、FULL 标记、27 映射。

## 9. 隐私评审与修复

实现后做了一次只读隐私评审，修复了以下问题（均补测试）：

1. `mark(STOP_UNCONFIRMED)` 无诊断仍可序列化 → 构造器强制诊断/状态一致；codec 同样拒绝。
2. `instanceId` 可接受 token/IP 形状 → 拒绝 IPv4、AWS/GitHub/JWT/`sk-` 前缀。
3. `Redactor` 对 `access_token=`、`:=`、`--password`、Basic auth、带引号 Bearer、无 scheme userinfo、带空格路径有漏网 → 规则补齐，新增反例测试。
4. 公共构造器可创建 `enabled+FULL` → 公共构造器拒绝 `FULL`；只有显式 `QA_FULL` 可开启。
5. `flush` 在 sink I/O 期间持有 recorder 锁 → 改为 flushLock + 快照，sink 内 mark 不丢事件。
6. disabled 时 `notObserved` 返回全部 10 项 → 改为空 + `enabled:false`。
7. `cpuPercent` 解码截断 → 先范围检查再 cast。
8. 全 STOP 队列可能先淘汰 `STOP_UNCONFIRMED` → 淘汰顺序改为 REQUESTED → CONFIRMED → UNCONFIRMED。
9. 未知嵌套字段可栈溢出 → 深度上限 16。
10. sink 半写重复 → `LineSink` 契约明确“原子追加或写前抛出”。

## 10. 未验证 / not-tested

- **无 Android 真机**：未接 `ApplicationExitInfo`、未验证进程被杀后的退出原因、未跑 DV-01/04。
- **未接 UI**：`FULL` 的“削弱保护”提示文案/位置未实现；本模块只提供 `protectionWeakened` 字段。
- **未接 27 生产接线**：适配器接口已提供，但未修改 `LaunchTimeline`/App 调用点（按范围禁止）。
- **未接持久 sink**：没有实现文件/SAF sink；`LineSink` 由宿主提供，失败语义只到接口契约。
- **未做 Android 时钟**：默认 `Clock.systemUTC()`，未接 `SystemClock.elapsedRealtime`；回退已钳制，但单调性应由宿主注入。
- **未做日志轮转/加密**：队列有界，落盘策略不在本票；删除/导出语义见 P06。
- **未做压力/端到端**：并发测试是 JVM 级，不是设备级。

## 11. 风险与下一步

| 风险 | 说明 | 下一步 |
| --- | --- | --- |
| `FULL` 仍可被显式调用 | `QA_FULL` 是 public；纯 JVM 模块无法识别构建类型 | 宿主用构建 flag/QA 渠道门禁；DV-04 检查未混入生产 |
| `Clock.systemUTC` 非单调 | NTP/用户改时间会回退 | 宿主注入 `elapsedRealtime` 时钟；已有钳制 |
| 队列丢弃 | 容量满会丢最旧非 STOP 事件 | 监控 `dropped`；DV-01 观察是否出现 |
| 脱敏不是 DLP | 编码/变形 token 可能漏 | 主保证是无自由文本 schema；新增字段必须 typed |
| 27 映射不完整 | 只有 5 个等价阶段 | 不强行补；27 的 `not_observed` 是诚实结果 |

## 12. 来源

- 票 27：`.scratch/orchestella-avf-shizuku/issues/27-production-stage-recorder.md`；实现 `app/src/main/java/io/github/nyzeep/orchestella/LaunchTimeline.java`（schema 2，只读参考）。
- 票 22：`.scratch/orchestella-avf-shizuku/issues/22-offline-typed-recorder.md`（有界/typed/无正文的离线先例）。
- Android `ApplicationExitInfo`：<https://developer.android.com/reference/android/app/ApplicationExitInfo>（未来集成，未运行）。
- 项目敏感数据策略：`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md`。

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 真机/Android 集成：`not-tested`。
