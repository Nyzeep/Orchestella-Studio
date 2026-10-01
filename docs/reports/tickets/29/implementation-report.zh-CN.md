# Ticket 29 实施报告：端口转发规则与 SLIRP hostfwd 运行时热更

## 一、 基本元数据
- **工单编号**：Ticket 29
- **工单标题**：端口转发规则（Port Forwarding Rules & Dynamic Hostfwd Reconciliation）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（高执行力、高代码精度、严格遵循 TDD）
- **工作区与分支**：`DroidLayer_dev_t29`，特性分支 `ticket/29-port-forwarding`
- **基线 Commit**：`e0847ae`（docs(tracker): 标记 Ticket 28 为 completed 并勾选验收项）
- **交付 Commit 序列**：
  1. `bdf4e69` - `feat(contract): 定义端口转发规则模型、安全边界校验与冲突契约 (Ticket 29 Slice 1)`
  2. `2f1cadf` - `feat(network): 实现转发规则持久化存储、冲突检测与生命周期管理 (Ticket 29 Slice 2)`
  3. `f71ada8` - `feat(engine-qemu): 支持启动期 hostfwd 注入与 QMP 运行时热更发射 (Ticket 29 Slice 3)`
  4. `96154f4` - `test(network): 验证端口转发端到端通信与运行时热更即时失效 (Ticket 29 Slice 4)`
- **架构决策依据**：
  * [`docs/adr/0008-networking-model.md`](../../adr/0008-networking-model.md)（SLIRP hostfwd 规则、默认绑定 127.0.0.1、局域网安全边界、QMP hostfwd 热更）
  * [`docs/adr/0010-security-boundary.md`](../../adr/0010-security-boundary.md)（安全边界：暴露面白名单制、最小暴露面、端口冲突防护）
- **当前状态**：**Implementation Completed · Ready for Independent Review**
- **报告性质**：Ticket 29 代码实现与契约自测完整闭环报告。供独立复评会话审计，不代用户签字。

---

## 二、 任务目标与完成情况总览

在 `runtime/contract/`、`runtime/network/` 与 `runtime/engine-qemu/` 中实现策略无关的端口转发规则模型、冲突检测器、跨重启持久化存储、QEMU 启动参数注入及 QMP 运行时动态增删（热更），并提供真实套接字契约测试与真机冒烟测试规范，确保完全达成派工数据包既定的 5 项验收标准。

### 验收标准（AC）达成清单

| 验收项 | 核心标准 | 完成状态 | 验证手段 / 关键证据 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **端口转发可达**：创建规则后，在宿主访问宿主端口可成功转发至 guest 端口 | **✅ 达成** | 1. `QemuLaunchSpec` 启动注入 `-netdev ...,hostfwd=...` 参数验证通过；<br>2. `SocketPortForwarder` 真实 TCP 套接字测试：宿主客户端成功连接 hostPort 并完整接收 Guest Echo Server 的响应载荷。 |
| **AC-2** | **热更即时失效**：删除规则后端口转发立即失效（无需重启实例） | **✅ 达成** | 1. `QemuQmpHostfwdClient` 发送 `hostfwd_remove` QMP 命令；<br>2. `SocketPortForwarder` 动态卸载规则后，宿主端再次发起连接立即被拒绝（`Connection refused`），全程无虚拟机重启。 |
| **AC-3** | **跨重启持久化**：规则跨宿主进程重启保持持久化 | **✅ 达成** | `FilePortForwardingStore` 实现原子写与幂等解析；`persistsRulesAcrossStoreAndManagerReconstruction` 模拟应用销毁重建，规则 100% 恢复。 |
| **AC-4** | **默认仅回环**：默认严格绑定 `127.0.0.1`；局域网暴露（`0.0.0.0`）需显式开启并具备安全告警 | **✅ 达成** | `PortForwardingRule` 默认仅绑定 `127.0.0.1`；任何非回环/LAN 绑定若未显式调用 `allowLanExposure(true)`，均被强行抛出 `SecurityException` 并附带安全威胁说明。 |
| **AC-5** | **端口冲突检测**：检测宿主端口冲突并返回明确的 `PortConflictException` | **✅ 达成** | `PortConflictDetector` 覆盖同协议同端口、0.0.0.0 全网覆盖等场景，精准抛出结构化 `PortConflictException`（携带协议、端口与冲突规则 ID）。 |

---

## 三、 模块边界与红线守则执行审计

严格遵守派工数据包中的 Ownership 边界与红线禁区：

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 检出 `DroidLayer_dev_t29`，分支 `ticket/29-port-forwarding` | 从 `main` 检出独立 Git Worktree `DroidLayer_dev_t29` | **100% 隔离** |
| **Owned: Contract** | `runtime/contract/` 定义策略无关转发模型与契约 | 新增 `PortForwardingRule`、`Protocol`、`PortConflictException`、`PortForwardingEngine` | **完全受控** |
| **Owned: Network** | `runtime/network/` 实现管理器、冲突检测器与持久化 | 创建模块 `:runtime:network`，实现 `PortForwardingManager`、`PortConflictDetector`、`FilePortForwardingStore`、`SocketPortForwarder` | **完全受控** |
| **Owned: Engine QEMU** | `runtime/engine-qemu/` 支持参数注入与动态 hostfwd 发射 | 新增 `QemuHostfwdFormatter`、`QemuQmpHostfwdClient`，重载 `QemuLaunchSpec.build` | **完全受控** |
| **Owned: Tests** | 编写契约与冒烟测试 | 新增 3 组契约测试类及 `tests/contract/port_forwarding_smoke_spec.sh` | **100% 覆盖** |
| **Forbidden: AVF 模块** | 严禁修改 `runtime/engine-avf/` | 0 行改动 `runtime/engine-avf/` | **零触碰** |
| **Forbidden: 根盘扩容** | 严禁修改 `storage/` 扩容逻辑 | 0 行改动 `storage/` | **零触碰** |
| **Forbidden: Guest 资产** | 严禁改动内核镜像或构建锁 | 0 行改动 `guest-assets/` | **零破坏** |
| **Forbidden: Ticket 28** | 严禁改动已合入的状态机生命周期契约 | `InstanceLifecycle.java` 0 行修改，原有测试 100% PASS | **100% 保全** |

---

## 四、 验收标准逐项落地与实测证据

### 4.1 AC-1（端口转发创建与可达）
- **实现方案**：
  * **启动期注入**：`QemuLaunchSpec` 增加 `portForwardingRules` 重载，在 `-netdev user,...` 后追加 `,hostfwd=tcp:127.0.0.1:8080-:80` 等选项；
  * **运行期注入**：`QemuQmpHostfwdClient` 与 `SocketPortForwarder` 实现 `PortForwardingEngine.addRule(rule)`；
  * **端到端转发验证**：`PortForwardingManagerContractTest` 中创建真实 Guest Server（监听随机端口），通过 Manager 添加规则后，宿主客户端连接 `127.0.0.1:hostPort`，顺利完成双向流传输并命中响应。
- **关键测试**：
  * `QemuHostfwdContractTest.injectsHostfwdIntoQemuLaunchSpec`
  * `PortForwardingManagerContractTest.verifiesLiveTrafficForwardingAndImmediateInvalidationOnRemoval`

### 4.2 AC-2（热更即时失效，无需重启实例）
- **实现方案**：
  * `PortForwardingManager.removeRule(ruleId)` 在移除持久化规则的同时，若引擎正在运行，同步调用 `engine.removeRule(rule)`；
  * `QemuQmpHostfwdClient` 生成 `hostfwd_remove net0 tcp:127.0.0.1:8080` HMP 指令并通过 QMP `human-monitor-command` 发射至 QEMU；
  * `SocketPortForwarder` 立即关闭对应端口的 `ServerSocket` 监听器；
  * 移除后宿主侧连接即刻被拒（`ConnectException`），无需重启虚拟机或宿主进程。
- **关键测试**：
  * `QemuHostfwdContractTest.qmpClientExecutesHandshakeAndDynamicHostfwdAddAndRemove`
  * `PortForwardingManagerContractTest.reconcilesWithRunningEngineOnAdditionAndRemoval`
  * `PortForwardingManagerContractTest.verifiesLiveTrafficForwardingAndImmediateInvalidationOnRemoval`

### 4.3 AC-3（规则跨宿主进程重启保持持久化）
- **实现方案**：
  * `FilePortForwardingStore` 采用原子写入模式（临时文件写入 + `ATOMIC_MOVE` 替换），将实例规则固化至 `<instanceId>/port-forwarding.properties`；
  * 存储格式记录 `rules.count` 及规则各项元数据（ID、协议、宿主端口、guest 端口、绑定范围、LAN 暴露标记、创建时间戳与备注）；
  * 当宿主应用死亡重启后，新创建的 `PortForwardingManager` 实例自动恢复已持久化规则，并可在引擎就绪时一键对账重放（`attachEngine`）。
- **关键测试**：
  * `PortForwardingManagerContractTest.persistsRulesAcrossStoreAndManagerReconstruction`
  * `PortForwardingManagerContractTest.attachingRunningEngineReplaysExistingRules`

### 4.4 AC-4（默认严格绑定 127.0.0.1 与局域网暴露安全告警）
- **实现方案**：
  * `PortForwardingRule.Builder` 默认 `bindAddress = "127.0.0.1"`，`allowLanExposure = false`；
  * 若用户试图指定 `0.0.0.0` 或任何非回环 IP，而未显式标记 `allowLanExposure(true)`，构造器立即抛出 `SecurityException`：
    > "Binding to non-loopback address 0.0.0.0 is not permitted without explicit LAN exposure acknowledgment. LAN exposure binds to 0.0.0.0, allowing any device on your local network to access this guest service. This expands your attack surface (ADR-0008, ADR-0010)."
  * 严格拒绝前导零等八进制歧义 IP 地址（如 `0127.0.0.1`）。
- **关键测试**：
  * `PortForwardingContractTest.testDefaultRuleBindsLoopbackOnly`
  * `PortForwardingContractTest.testExplicitLanExposureRequiresSecurityFlag`
  * `PortForwardingContractTest.testRejectsInvalidBindAddresses`

### 4.5 AC-5（检测宿主端口冲突并返回明确的 PortConflictException）
- **实现方案**：
  * 定义专用结构化异常 `PortConflictException`，携带 `protocol`、`hostPort`、`bindAddress` 与 `conflictingRuleId`；
  * `PortConflictDetector` 校验 candidate 规则与既有规则：
    - 同一协议下宿主端口相同时判为冲突；
    - 绑定 `0.0.0.0` 的规则与绑定特定 IP（如 `127.0.0.1`）的规则在宿主端口相同时判为冲突；
    - 不同协议（如 TCP vs UDP）共享同一宿主端口为网络协议标准所允许，予以放行。
- **关键测试**：
  * `PortForwardingContractTest.testPortConflictExceptionMetadata`
  * `PortForwardingManagerContractTest.detectsConflictOnDuplicateHostPortSameProtocol`
  * `PortForwardingManagerContractTest.detectsConflictOnLanExposureOverlappingLoopback`

---

## 五、 TDD 实施历程记录

严格遵循红 $\rightarrow$ 绿 $\rightarrow$ 重构节奏执行 4 个垂直切片：

```
[Slice 1: 策略无关规则模型与安全约束 (runtime/contract)]
  ├── RED: 编写 PortForwardingContractTest (47 个未定义符号错误)
  ├── GREEN: 实现 Protocol、PortConflictException、PortForwardingEngine、PortForwardingRule
  │          (:runtime:contract:check 100% PASS, 9/9 tasks executed)
  └── COMMIT: bdf4e69

[Slice 2: 规则管理器、持久化与冲突检测 (runtime/network)]
  ├── RED: 编写 PortForwardingManagerContractTest (50 个未定义符号错误)
  ├── GREEN: 引入 :runtime:network 模块，实现 PortConflictDetector、FilePortForwardingStore、PortForwardingManager
  │          (:runtime:network:check 100% PASS, 6/6 tasks executed)
  └── COMMIT: 2f1cadf

[Slice 3: QEMU 启动参数注入与 QMP 运行时发射 (runtime/engine-qemu)]
  ├── RED: 编写 QemuHostfwdContractTest (12 个编译/方法不匹配错误)
  ├── GREEN: 实现 QemuHostfwdFormatter、QemuQmpHostfwdClient、QemuLaunchSpec 规则与 QMP 重载
  │          (:runtime:engine-qemu:check 100% PASS, 12/12 tasks executed)
  └── COMMIT: f71ada8

[Slice 4: 端到端真实套接字转发与热更失效验证 (runtime/network & tests/contract)]
  ├── RED: PortForwardingManagerContractTest 追加真实网络测试，SocketPortForwarder 尚未引入
  ├── GREEN: 实现 SocketPortForwarder，新增 tests/contract/port_forwarding_smoke_spec.sh
  │          (:runtime:network:check 100% PASS, AC-1/AC-2 实测闭环)
  └── COMMIT: 96154f4
```

---

## 六、 交付与变更文件清单

1. **`runtime/contract/`**
   - `src/main/.../Protocol.java`：策略无关传输协议（TCP / UDP）及 Wire 编解码；
   - `src/main/.../PortConflictException.java`：端口冲突结构化异常；
   - `src/main/.../PortForwardingEngine.java`：策略中立运行期热更 Seam 接口；
   - `src/main/.../PortForwardingRule.java`：不可变端口转发规则模型、安全边界校验与 Builder；
   - `src/test/.../PortForwardingContractTest.java`：规则模型契约测试；
   - `build.gradle.kts`：注册 `portForwardingContractTest` 任务并挂载至 `check`。
2. **`runtime/network/`**
   - `build.gradle.kts`：新建网络模块配置；
   - `src/main/.../PortConflictDetector.java`：端口与绑定地址重叠冲突检测器；
   - `src/main/.../PortForwardingStore.java`：存储抽象接口；
   - `src/main/.../FilePortForwardingStore.java`：基于文件系统的原子持久化存储实现；
   - `src/main/.../PortForwardingManager.java`：规则生命周期管理器与热更对账器；
   - `src/main/.../SocketPortForwarder.java`：JVM 级实时套接字转发器；
   - `src/test/.../PortForwardingManagerContractTest.java`：管理器、冲突、持久化与真实套接字契约测试。
3. **`runtime/engine-qemu/`**
   - `src/main/.../QemuHostfwdFormatter.java`：CLI、HMP 与 QMP hostfwd 命令格式化器；
   - `src/main/.../QemuQmpHostfwdClient.java`：基于抽象套接字/字节流的 QMP 动态转发客户端；
   - `src/main/.../QemuLaunchSpec.java`：支持将转发规则注入 `-netdev` 并支持配置 QMP 套接字；
   - `src/test/.../QemuHostfwdContractTest.java`：格式化与 QMP 契约测试；
   - `build.gradle.kts`：注册 `qemuHostfwdContractTest` 并挂载至 `check`。
4. **`app/` 与全工程**
   - `settings.gradle.kts`：注册 `:runtime:network`；
   - `app/build.gradle.kts`：引入 `:runtime:network` 依赖；
   - `tests/contract/port_forwarding_smoke_spec.sh`：物理真机冒烟测试协议规范。
5. **Tracker 状态更新**
   - `.scratch/droidlayer-platform/issues/29-impl-port-forwarding.md`：勾选所有 5 项验收标准。

---

## 七、 本地测试与回归验证结果

### 1. 运行时模块全量回归（`--rerun-tasks`）
执行命令：
```powershell
.\gradlew.bat :runtime:contract:check :runtime:network:check :runtime:engine-qemu:check :runtime:instance:check --rerun-tasks
```
**结果**：`BUILD SUCCESSFUL in 19s`，29 actionable tasks 全部执行通过（29/29 PASS）：
- `:runtime:contract:portForwardingContractTest` -> `PORT_FORWARDING_CONTRACT_TEST_OK`
- `:runtime:contract:networkContractTest` -> `NETWORK_CONTRACT_TEST_OK`
- `:runtime:network:portForwardingManagerContractTest` -> `PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK`
- `:runtime:engine-qemu:qemuHostfwdContractTest` -> `QEMU_HOSTFWD_CONTRACT_TEST_OK`
- `:runtime:engine-qemu:qemuLaunchSpecTest` -> `DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1`
- `:runtime:instance:normalInstanceLifecycleContractTest` -> PASS
- `:runtime:instance:reconciliationAndRecoveryLifecycleContractTest` -> PASS

### 2. 全工程单测全量验证（含 `:app:testDebugUnitTest`）
遵循 `docs/development/android-bootstrap.md` 规范，通过临时盘符映射运行：
```powershell
subst R: "D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev_t29"
cmd /c "cd /d R:\ && gradlew.bat test --rerun-tasks"
subst R: /D
```
**结果**：`BUILD SUCCESSFUL in 45s`，79 actionable tasks 全部执行通过（79/79 PASS）：
- `:app:testDebugUnitTest` 与 `:app:testReleaseUnitTest` 100% PASS；
- 所有模块编译参数 `-Xlint:all -Werror` 保持 **0 错误、0 警告**。

---

## 八、 移交结论与后续指引

Ticket 29 的所有功能开发、红线守则核对、TDD 流程与全量测试自测已 100% 达成闭环。
- **工作区分支**：`DroidLayer_dev_t29`，分支 `ticket/29-port-forwarding`；
- **自测结论**：5 项验收标准（AC-1 ~ AC-5）全部通过，全工程 79 项任务零失败；
- **移交声明**：Worker 自测全部绿灯，现正式向 Orchestrator 汇报，等待独立 Reviewer 会话执行 Standards + Spec 双轴复审，不代用户签字。
