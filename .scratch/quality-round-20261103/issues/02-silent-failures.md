# Q2 三处静默失败收口

Status: resolved
Type: task
Blocked by: 无
Effort: .scratch/quality-round-20261103/

## 背景

三处吞异常（坐标以当前工作树实测为准）：

1. `runtime/network/src/main/java/io/github/nyzeep/orchestella/runtime/network/SocketPortForwarder.java:101-105` —— accept 循环 `IOException` 分支：`if (isClosed()) break;` 否则**什么都不做直接下一轮**，是忙旋（100% CPU、无日志、无退避、永不自愈）。注意不是 prompt 说的 `catch (Exception ignored)`，`SocketException`（`:98-100`）才是正常移除路径。
2. 同文件 `:121-122`（`handleClient` 的 `catch (Exception ignored) {}`）、`:135-136`（`relay` 的 `catch (IOException ignored)`）、`:47-48`（`ActiveListener.close`）。`relay` 是 `static`，无实例上下文，天然无法上报。
3. `app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java:199-205` —— `proxy_relay` 读取 `catch (Exception ignored) {}` 后把 `null` 继续传下去。`guest_init` 同位置是 `throw new IOException("guest-init-unavailable")`，proxy_relay 是硬化时漏改（`751e662` 只改了 guest_init）；为 null 时 `EmbeddedProvisionInput.java:309-312` 直接跳过写入，guest 侧 `runtime-entry.sh:21-24` 的 `if` 没有 else → guest 无出站代理、APT 静默不通。

**契约边界（不得越线）**：`PortForwardingEngine` 不加抽象方法（已有 `SocketPortForwarder`、`NetworkGatewayDaemon.ForwardingEngine`、`MockEngine` 三个实现者）；`PortForwardingRule` 一行不改；`QemuRuntimeBootstrapper` 既有 `bootstrap(...)` 重载签名与 `RuntimePaths` 字段不动。

## 交付

- 新增 `runtime/network/.../PortForwardingObserver.java`：多事件接口，**每个方法都有 `default {}` 空实现**（对齐 `download/DownloadProgressCallback:33/42`），提供 `NOOP` 常量。事件至少覆盖 accept 失败（带 `fatal` 标志）、客户端连接失败、relay 异常结束、关闭汇总。
- `SocketPortForwarder`：
  - 新增 `SocketPortForwarder(PortForwardingObserver)` 重载；保留无参构造（委托 `NOOP`）；传 `null` 视为 `NOOP`。
  - observer 绝缘：回调走统一 `notify(...)`，内部 catch `RuntimeException` 吞掉并注释说明——这是全类**唯一**允许保留的空 catch。
  - accept `IOException` 分支：节流记录 + 50ms→200ms 指数退避（上限 1s）+ 连续失败 ≥10 次则记录并中止该 listener（`fatal=true`，关 socket）。`SocketException` + `isClosed()` 保持静默。
  - `handleClient`：`catch (Exception)` 收窄为 `IOException`（节流记录 + 继续）；`RuntimeException` 单独 catch（记录 + 重抛）。
  - `relay`：`static` 改实例方法，携带 `ruleId` 与方向；「本端已关闭或对端干净 EOF」静默，其余节流记录。
  - `ActiveListener.close()`：保持不抛（`Closeable` 契约），记录一条 DEBUG 级、不节流事件。
  - `addRule` 同 id：改幂等替换（先关旧 listener 再绑定）+ 记一条事件。现状旧 `ServerSocket` 既不关闭也不退出接受循环，是静默 fd 泄漏。
- 节流：key = `ruleId + eventKind + cause 简单类名`；同类 key 每 1000ms 最多 1 条；首条必出；`close()` 时一条汇总报「抑制了 N 条」。**禁止按连接或按 4KB 打点。**
- `QemuRuntimeBootstrapper`：
  - 新增注入式诊断 sink `java.util.function.Consumer<String>`（包私有构造重载，默认 no-op）。
  - `LaunchTimeline` 新增 `public synchronized void noteDegradation(String)`，写入既有 `diagnostics` 列表（`Diagnostics` 会被序列化进 JSONL 的 `"diagnostics":[...]`）。
  - `proxy_relay`：**保留 `null` 容忍**（23 个既有测试的夹具对未知资源恒返回 `null`）；`null` → 记 `proxy-relay-absent` 并继续；抛异常 → 记 `proxy-relay-unreadable: <cause>` 并上抛 `IOException("proxy-relay-unavailable", failure)`。
  - `QemuInstanceLauncher` / `AvfInstanceLauncher` 构造处接 `timeline::noteDegradation`。
- `runtime/network/build.gradle.kts`：新增 `JavaExec("socketForwarderDiagnosticsTest")` 并入 `check.dependsOn`。该模块 `test` 任务 `exclude("**/*")`，**不挂就永远不跑**。

## 验收

- 注入会抛 `IOException` 的 observer：accept 循环不被打死，后续连接仍可建立。
- guest 端口未监听时 `onClientFailure` 至少 1 次（现状为 0）。
- 同 key 连续失败 N 次，观察者收到的记录数 < N（节流生效），且首条必出。
- 同 id 二次 `addRule`：旧监听口被关闭、地图里只有一个 listener、无 fd 泄漏。
- `proxy_relay` 流抛异常时 bootstrap 抛 `proxy-relay-unavailable`；返回 `null` 时 bootstrap 成功且 `diagnostics()` 含 `proxy-relay-absent`。
- `PortForwardingManagerContractTest` 8 个 scenario 原样通过（尤其 AC-2：`removeRule` 后 500ms 内新连接必须 `IOException`）。

## 测试

- `runtime/network/src/test/java/.../SocketPortForwarderDiagnosticsTest.java`（`main` + `@Test` 双入口）
- `app/src/test/java/.../QemuRuntimeBootstrapperDiagnosticsTest.java`
- `./gradlew :runtime:network:check`、`./gradlew :app:testDebugUnitTest`

## 不在范围

- `close()` 切断在飞连接（需引入 active-connection 集合，AC-2 只要求新连接被拒）。
- `SocketPortForwarder` 补 `protocol()` 校验 / UDP 支持。
- `guest-assets/initramfs/runtime-entry.sh:21` 补 else 分支（guest 产物本机无法重打，改了不生效）。
- `BridgeProxyGateway` 的裸 catch（已有 JUL，优先级低）。

## Comments

2026-11-03 协调：建票（来源 `.scratch/quality-round-20261103/notes/B2-portforward.md`）。建票时纠正 prompt 的一处前提：`SocketPortForwarder` 生产零引用（生产走 gvproxy + `NetworkGatewayDaemon.ForwardingEngine`），它是经 `testJar`/`testArtifacts` 发布给 `engine-avf` 的契约参考实现，所以本轮改它对外零爆炸半径，但也意味着这一处静默失败当前不直接产生用户可见症状。

2026-11-03 Q2-impl：claimed。

2026-11-03 Q2-impl：完成。
- 新增 `runtime/network/.../PortForwardingObserver.java`：5 个事件全 `default {}` + `NOOP`。
- `SocketPortForwarder.java`：observer 构造重载（null→NOOP）；`notify(Runnable)` 绝缘（全类唯一空 catch，带注释）；accept `IOException` 分支改「节流 + 50→1000ms 指数退避 + 连续 10 次 fatal 关 listener」；`handleClient` 收窄 `IOException` 并单列 `RuntimeException` 记录后重抛；`relay` 改实例方法带方向；同 id `addRule` 幂等替换（先关旧 listener）+ `onListenerReplaced`；`close()` 发 `onSummary` 汇总抑制数。
- 节流 key = `ruleId|eventKind|causeSimpleName`，1s 窗口，首条必出。
- `LaunchTimeline.noteDegradation(String)`；`QemuRuntimeBootstrapper` 新增 `Consumer<String> degradations`（6 参 launcher 构造 + 8 参构造，7 参测试构造委托 no-op）；`proxy_relay` null→记 `proxy-relay-absent` 继续，异常→记 `proxy-relay-unreadable` 并抛 `IOException("proxy-relay-unavailable")`。
- 接线：`QemuInstanceLauncher` 用 `timeline::noteDegradation`；`AvfInstanceLauncher` 因 timeline 晚于 bootstrapper 构造，改为 `List` 收集后在 `:362` 回放。

2026-11-03 协调：门禁实跑 `./gradlew :runtime:network:check :runtime:instance:check --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL in 2m 23s`，其中 `:runtime:network:socketForwarderDiagnosticsTest` 打印 `SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK`，既有 `PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK`、`BRIDGE_PROXY_GATEWAY_CONTRACT_TEST_OK` 保持绿（AC-2 热移除路径未被破坏）。

2026-11-03 协调：新增测试 `SocketPortForwarderDiagnosticsTest`（5 用例，双入口）与 `QemuRuntimeBootstrapperDiagnosticsTest`（2 用例）；后者证明「注入抛 `IOException` 的流 → 抛 `proxy-relay-unavailable` 而不是静默」，前者证明「观察者抛异常不打折转发」「guest 端口未监听至少 1 次 `onClientFailure`」「同 key 连续失败被节流」「同 id 重复 addRule 不泄漏监听口」「close 发汇总」。

2026-11-03 协调：**范围外但已记**——`guest-assets/initramfs/runtime-entry.sh:21-24` 的 `if [ -f /opt/orchestella/proxy-relay ]` 仍无 else 分支。guest 产物本机无法重打，改动不会生效，故本轮不动；下个设备窗口如重建 initramfs，应补一条 `ORCHESTELLA_PROXY_RELAY status=absent` 日志。`not-tested`：真机 logcat 是否收到 observer 事件（当前 `SocketPortForwarder` 无生产调用者）。

