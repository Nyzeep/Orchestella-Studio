# Ticket 27 实施报告：出站网络与 SLIRP 契约实现

## 一、 基本元数据
- **工单编号**：Ticket 27
- **工单标题**：出站网络——实例可访问互联网（Outbound Network & SLIRP Contract）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（高执行力、高代码精度、严格遵循 TDD）
- **特性分支**：`ticket/27-outbound-network`
- **基线 Commit**：`4f3837962e29c8e2114b8a15b91dad0552735764` (基于 `main`)
- **架构决策依据**：
  * [`docs/adr/0008-networking-model.md`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/docs/adr/0008-networking-model.md)（默认出站 NAT + 显式端口转发，明示关闭 IPv6，DNS 穿透机制）
  * [`docs/adr/0010-security-boundary.md`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/docs/adr/0010-security-boundary.md)（安全边界：暴露面白名单制，入站默认零暴露）
- **报告性质**：Ticket 27 代码实现、TDD 测试验证与真机冒烟规范的完整实施报告。供独立审计会话复评，不代用户签字。

---

## 二、 任务目标与完成情况总览

按 ADR-0008 与派工包要求，在 `runtime/engine-qemu` 与 `runtime/contract` 中实现规范的出站网络装配，使 Alpine Linux 实例在启动时默认获得免配置的出站 NAT 访问能力（支持 `apk update` 与软件包安装），建立网络契约测试套件，并妥善解决 Android 宿主环境缺失 `/etc/resolv.conf` 的 DNS 解析适配问题。

### 验收标准（AC）达成清单

| 验收项 | 核心标准 | 完成状态 | 验证手段 / 关键证据 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **参数装配与无侵入**：`QemuLaunchSpec.build` 正确装配 SLIRP 参数：<br>1. `-netdev user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15`<br>2. `-device virtio-net-pci,netdev=net0`（支持 `virtio-net-device`）<br>3. 保证 IPv6 明示关闭（`ipv6=off`） | **✅ 达成** | `QemuLaunchSpec.java` 默认重载安全发射 SLIRP 与 virtio-net 参数，`QemuLaunchSpecTest` 逐项验证 flag/value 与无侵入约束。 |
| **AC-2** | **DNS 穿透适配**：支持宿主 DNS 地址（如 Google DNS `8.8.8.8`、Cloudflare DNS `1.1.1.1` 或动态传入的活动系统 DNS），支持 DHCP 与 cmdline 穿透，确保 guest 解析可用 | **✅ 达成** | `OutboundNetworkSpec` 支持 `withDns()`、`builder().dnsServer().cmdlineDns()`，并在 QEMU `-netdev` 中追加 `,dns=<ip>`，在 `-append` 中追加 `droidlayer.dns=<ip>`。 |
| **AC-3** | **契约测试全绿**：在 `runtime/contract` 与 `runtime/engine-qemu` 实现契约测试，覆盖出站 NAT 参数正确性、零入站默认暴露（无 `hostfwd` 泄露）与配置回放 | **✅ 达成** | 新增 `NetworkContractTest`（覆盖默认参数、IPv6 拒绝、CIDR/IP 校验、零入站暴露、Builder 回放）及 `QemuLaunchSpecTest`，全部纳入 `gradle check`。 |
| **AC-4** | **真机网络冒烟规范**：编写或更新针对实机运行的验收用例，能够在 shell 中执行 `apk update` 并成功拉取 Alpine 仓库索引 | **✅ 达成** | 编写 `tests/contract/outbound_network_smoke_spec.sh`，规范了网络驱动检测、DHCP 租约、网关路由、DNS 解析、`apk update` 及安全隔离的完整冒烟逻辑。 |

---

## 三、 模块边界与红线守则执行审计

严格遵守派工数据包中的 Ownership 边界与红线禁区：

1. **允许修改的模块（Owned）**：
   - `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContract.java`（新增）：策略无关网络契约及不可变 `OutboundNetworkSpec` 模型；
   - `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContractTest.java`（新增）：网络契约测试；
   - `runtime/contract/build.gradle.kts`（修改）：将 `networkContractTest` 纳入 `check` 依赖链；
   - `runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpec.java`（修改）：保留原 7 参数静态方法并委托默认出站网络规范，增加 8 参数重载支持定制网络规范，安全装配 `-netdev` 与 `-device`；
   - `runtime/engine-qemu/src/test/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpecTest.java`（修改）：增加出站网络参数、IPv6 显式禁用、入站零暴露、DNS 穿透与 MMIO 设备的测试；
   - `tests/contract/outbound_network_smoke_spec.sh`（新增）：真机网络冒烟验收脚本。
2. **严禁修改的模块（Forbidden）**：
   - `runtime/engine-avf/`：**零触碰**；
   - `storage/` 及根磁盘扩容逻辑：**零触碰**；
   - `guest-assets/` 内核镜像与 `inputs.lock`：**零触碰**；
   - `runtime/instance/`（Ticket 28 范围）：**零触碰**。

---

## 四、 严格 TDD 研发节奏记录

本次开发严格遵循红 $\rightarrow$ 绿 $\rightarrow$ 重构节奏，分垂直切片分步交付：

### Slice 1 & 2：网络契约与 `OutboundNetworkSpec` 模型
1. **Red**：编写 `runtime/contract/src/test/.../NetworkContractTest.java`，定义默认 SLIRP 出站规格、IPv6 显式拒绝规则、DNS 适配预设、CIDR 验证与不可变回放；在 `runtime/contract/build.gradle.kts` 中注册 `networkContractTest`。执行 `./gradlew :runtime:contract:compileTestJava`，确认由于类缺失产生 39 处编译报错，测试状态确认 **RED**。
2. **Green**：创建 `runtime/contract/src/main/.../NetworkContract.java`，实现不可变类 `OutboundNetworkSpec` 与 `DeviceType` 枚举；内置严格的 IPv4/CIDR 校验、ADR-0008 IPv6 强制拦截及参数构造逻辑。执行 `./gradlew :runtime:contract:check`，输出 `NETWORK_CONTRACT_TEST_OK`，测试状态确认 **GREEN**。

### Slice 3：QEMU 发射规范与网络参数安全装配
1. **Red**：在 `runtime/engine-qemu/src/test/.../QemuLaunchSpecTest.java` 中引入对 `-netdev user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15`、`-device virtio-net-pci,netdev=net0`、`ipv6=off` 确保、`hostfwd` 绝不泄露、DNS 穿透及 `virtio-net-device` 重载的断言。执行 `./gradlew :runtime:engine-qemu:check`，确认缺少重载方法，编译失败，测试状态确认 **RED**。
2. **Green**：更新 `runtime/engine-qemu/src/main/.../QemuLaunchSpec.java`，原有 7 参数 `build()` 方法无缝委托 `OutboundNetworkSpec.defaultSpec()`，保证既有调用方（如 `app` 模块中的 `QemuInstanceLauncher` 及 `QemuProcessLauncherTest`）完全兼容；提供扩展 8 参数 `build()` 支持自定义网络规范。执行 `./gradlew :runtime:engine-qemu:check`，输出 `DEFAULT_LAUNCH_SPEC_OK`、`DNS_LAUNCH_SPEC_OK`、`MMIO_NET_DEVICE_SPEC_OK`，测试状态确认 **GREEN**。

### Slice 4：冒烟规范与全量测试套件回归
1. 编写 `tests/contract/outbound_network_smoke_spec.sh`，覆盖真机环境下的 `eth0` 识别、DHCP 申请、网关可达性、DNS 解析、`apk update` 索引更新与隔离性断言（AC-4）。
2. 在宿主与本地工具链环境下执行全量回归验证，确保所有既有模块与新增契约测试 100% 通过。

---

## 五、 本地测试与回归验证结果

### 1. Runtime 模块全量 Check 验证
执行命令：
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:distro:check :runtime:capability:check :runtime:instance:check --rerun-tasks
```
**结果**：`BUILD SUCCESSFUL in 13s`，30 actionable tasks 全部执行通过（30/30 PASS）：
- `Task :runtime:contract:networkContractTest` -> `NETWORK_CONTRACT_TEST_OK`
- `Task :runtime:contract:bridgeControlContractTest` -> PASS
- `Task :runtime:contract:bridgeHandshakeContractTest` -> PASS
- `Task :runtime:contract:fullLinuxContractTest` -> PASS
- `Task :runtime:engine-qemu:qemuLaunchSpecTest` -> PASS
- `Task :runtime:engine-qemu:embeddedProvisionInputTest` -> PASS
- `Task :runtime:engine-qemu:runtimeArtifactManifestTest` -> PASS
- `Task :runtime:engine-qemu:runtimeAssetInstallerTest` -> PASS
- `Task :runtime:distro:distroManifestContractTest` -> PASS
- `Task :runtime:distro:officialAlpineCatalogTest` -> PASS
- `Task :runtime:capability:deviceCapabilityEvaluatorTest` -> PASS
- `Task :runtime:instance:normalInstanceLifecycleContractTest` -> PASS

### 2. 全工程单测全量验证（含 `:app:testDebugUnitTest`）
遵循 `docs/development/android-bootstrap.md` 规避 Windows 括号及非 ASCII 路径的规范，映射临时盘符执行：
```powershell
subst R: "D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev"
cmd /c "cd /d R:\ && gradlew.bat test --rerun-tasks"
subst R: /D
```
**结果**：`BUILD SUCCESSFUL in 33s`，75 actionable tasks 全部执行通过（75/75 PASS）：
- `:runtime:capability:test` -> PASS
- `:runtime:contract:test` -> PASS
- `:runtime:distro:test` -> PASS
- `:runtime:engine-qemu:test` -> PASS
- `:runtime:instance:test` -> PASS
- `:app:testDebugUnitTest` -> 4 tests / 0 failures / 0 errors
- `:app:testReleaseUnitTest` -> 4 tests / 0 failures / 0 errors

---

## 六、 变更文件与代码清单

### Git 状态快照
```
On branch ticket/27-outbound-network
Changes to be committed / staged:
	modified:   runtime/contract/build.gradle.kts
	new file:   runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContract.java
	new file:   runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContractTest.java
	modified:   runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpec.java
	modified:   runtime/engine-qemu/src/test/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpecTest.java
	new file:   tests/contract/outbound_network_smoke_spec.sh
	new file:   docs/reports/tickets/27/implementation-report.zh-CN.md
```

---

## 七、 交付状态与后续交接

1. 本地代码与契约测试已全部通过，红绿节奏完整闭环，模块红线与 ADR 决策零违背；
2. 本工单所有成果已就绪并准备好在 `ticket/27-outbound-network` 提交；
3. 本报告正式呈交 Orchestrator，等待调度独立 Reviewer 开展基于 Standards 轴与 Spec 轴的双轴代码复评。
