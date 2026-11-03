# Q3 孤儿网关启动期判定（设计与纯 JVM 组件，零接线）

Status: resolved
Type: task
Blocked by: 无
Effort: .scratch/quality-round-20261103/

## 背景

进程被杀后 QEMU/gvproxy 可能存活并占住派生端口。现状全链以「本进程持有 `java.lang.Process` 句柄」为前提：

- `NetworkGatewayDaemon.java:289-295` `ensureGatewaySlotAvailable` 只查进程内静态表 `LIVE`（`:105`）；SIGKILL 后表随进程消失，`LIVE.get` 返回 null → **静默通过**。
- `:297-309` `portAssignedToAnotherInstance` 同样只遍历 `LIVE`（且有清理副作用）。
- `:602-607` `portBound` 是全仓唯一进程外证据，但只返回 `boolean`，**没有任何身份信息**。
- `:206-208` 端口被占时抛 `IOException("gateway port is already in use; old gateway may still be running")`，抛点在 `:233` 的 `try` 之外 → 不进任何清理逻辑，不上报诊断、不提示处置。最终用户看到的是 `MainActivity.kt:1883` 的「启动失败：IOException」。

端口派生 `:609-611` `24100 + floorMod(instanceId.hashCode(), 800)` 由 JLS 固定 → 同一 instanceId **永远**同一端口，没有「换个端口绕开」的产品空间。同源稳定身份串有两个：`filesDir/net-api/<hex(hashCode)>/api.sock`（`:192`）与配置文件 `net-<instanceId>.yaml`（`:212`，该文件名**逐字出现在 gvproxy argv 里**）。

## 交付

- `runtime/instance` 新增纯 JVM 深模块（无 Android、无线程、无 I/O 副作用），形态对齐同级 `InstanceStopSignal`：
  - `GatewaySlotVerdict` 枚举：`FREE` / `ORPHAN_OF_THIS_INSTANCE` / `HELD_BY_UNKNOWN` / `EVIDENCE_UNAVAILABLE`。
  - `ProcessRef`：pid + cmdline 快照，值对象。
  - `GatewaySlotEvidence`：`portBound`、`apiSocketPresent`、`List<ProcessRef>`、`evidenceComplete`。
  - `GatewaySlotArbiter`：`verdict(...)` 纯判定 + `describe(...)` 稳定短句；`derivedPort(instanceId)`、`configFileName(instanceId)`（`net-<id>.yaml`）、`apiSocketDirName(instanceId)`（hex）派生函数集中在此，禁止调用方各写一份。
- 身份判据：`portBound` 只是必要不充分条件。`ORPHAN_OF_THIS_INSTANCE` 必须同时满足「端口被占」**且**「进程表存在 cmdline 含 `net-<instanceId>.yaml` 的进程」。**端口号永不是身份。** 证据缺失 → `HELD_BY_UNKNOWN` / `EVIDENCE_UNAVAILABLE`，禁止回收。
- **零接线**：`NetworkGatewayDaemon`、`QemuInstanceLauncher`、`MainActivity` 一行不改。类 javadoc 逐字对齐 `InstanceStopSignal:37-41` 的 Intentionally-not-wired 措辞，并写明三条不接线理由：跨进程无句柄（只能 `Os.kill`/`ProcessHandle`，全仓 `app/src/main` 零先例）、误杀会让另一实例的 QEMU 因 netdev socket 断开而崩、回收确认链（kill → 重新探测 → 仍占用则 UNKNOWN）只能在真机验。
- `runtime/instance/build.gradle.kts` 注册 `JavaExec("gatewaySlotArbiterContractTest")` 并入 `check.dependsOn`。

## 验收

- 端口空闲 → `FREE`，即使进程表里有个 cmdline 像 gvproxy 的进程。
- 端口被占 + cmdline 命中 `net-<id>.yaml` → `ORPHAN_OF_THIS_INSTANCE`。
- **端口被占但身份不匹配 → `HELD_BY_UNKNOWN`（不得判孤儿）**——这是本议题最容易犯的错，必须有独立用例。
- 证据不可读 → `EVIDENCE_UNAVAILABLE`，**不得**退化为 `FREE`。
- `describe(...)` 对四种 verdict 各返回稳定、非空、不含绝对路径的短句。
- `git diff --stat` 证明 `app/src/main/**` 与 `NetworkGatewayDaemon.java` 未被触碰。

## 测试

- `runtime/instance/src/test/java/.../GatewaySlotArbiterContractTest.java`（`main` + `@Test` 双入口，对齐 `InstanceStopSignalContractTest`，末尾打印 `GATEWAY_SLOT_ARBITER_TEST_OK`）
- `./gradlew :runtime:instance:check`

## 不在范围

- 任何接线（判定结果不进启动路径、不进错误文案）。
- 回收动作实现（`Os.kill`/`ProcessHandle`）——留设备窗口，报告给执行入口。
- 把 `LIVE` 改成跨进程注册表（违反 ADR-0020:30）。
- 新增实例状态（`HELD_BY_UNKNOWN` 只能是日志/文案，不能落库）。

## Comments

2026-11-03 协调：建票（来源 `.scratch/quality-round-20261103/notes/B3-gateway-slot.md`）。

2026-11-03 Q3-impl：claimed。**按工单要求零接线**（B3 曾建议至少把判定接进 `:206-208` 的错误文案，工单明确不接线，本轮从工单）。

2026-11-03 Q3-impl：完成。新增五个文件：
- `runtime/instance/.../GatewaySlotVerdict.java`：四态枚举，每态标注成立条件与允许动作。
- `ProcessRef.java`：pid + 不可变 cmdline，`toString` 只输出 pid 与 argv 数量。
- `GatewaySlotEvidence.java`：`portBound`/`apiSocketPresent`/`processes`/`processTableReadable` + `free()`/`unavailable()`/`hasIdentityFor`。
- `GatewaySlotArbiter.java`：`PORT_BASE`/`PORT_SLOTS`、`derivedPort`/`configFileName`/`apiSocketDirectoryName` 三个派生函数、四步严格 `verdict`、`describe`；类注释含三条不接线理由与「not dead code」。
- `build.gradle.kts` 注册 `gatewaySlotArbiterContractTest` 并入 `check.dependsOn`。

2026-11-03 协调：判定顺序固化在 `verdict` 内：`processTableReadable == false` → `EVIDENCE_UNAVAILABLE`；`!portBound` → `FREE`；身份命中 `net-<id>.yaml` → `ORPHAN_OF_THIS_INSTANCE`；否则 `HELD_BY_UNKNOWN`。**端口号不参与身份判定**，只有 cmdline 指纹算身份。

2026-11-03 协调：门禁实跑 `./gradlew :runtime:network:check :runtime:instance:check --offline`（`X:` ASCII 路径）→ `BUILD SUCCESSFUL in 2m 23s`；`:runtime:instance:gatewaySlotArbiterContractTest` 打印 `GATEWAY_SLOT_ARBITER_TEST_OK`；`javac -Xlint:all -Werror --release 17` 零告警；JUnitCore `OK (9 tests)`。

2026-11-03 协调：零接线证据 —— `git status --porcelain app/` 为空；本票改动仅 `runtime/instance/**`。`NetworkGatewayDaemon.java`、`QemuInstanceLauncher.java`、`MainActivity.kt` 一行未动。

2026-11-03 协调：**已知边界**。`derivedPort` 与 `NetworkGatewayDaemon.listenPortFor` 是两份独立实现（仓内无共享常量），漂移只能靠人工核对；若要单源化需把 `PORT_BASE`/`PORT_SLOTS` 提到 `runtime/contract`，属独立票。`configFileName(null)`/`verdict(null, …)` 抛 NPE（`derivedPort(null)` 按 `hashCode(0)` 处理，与现状一致）。**回收动作整条未实现**——跨进程只能 `Os.kill`/`ProcessHandle`，`app/src/main` 零先例，且误杀会让另一实例 QEMU 因 netdev socket 断开而崩；留设备窗口。`not-tested`：`/proc/<pid>/cmdline` 在目标 ROM 上的可读性（hidepid / SELinux）。

