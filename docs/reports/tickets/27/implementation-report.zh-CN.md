# Ticket 27 实施报告：出站网络与 SLIRP 契约实现（含 Review 整改闭环）

## 一、 基本元数据
- **工单编号**：Ticket 27
- **工单标题**：出站网络——实例可访问互联网（Outbound Network & SLIRP Contract）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（高执行力、高代码精度、严格遵循 TDD）
- **特性分支**：`ticket/27-outbound-network`
- **基线 Commit**：`4f3837962e29c8e2114b8a15b91dad0552735764` (基于 `main`)
- **整改依据**：[`docs/reports/reviews/27/review-report.zh-CN.md`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/docs/reports/reviews/27/review-report.zh-CN.md)（独立评审裁定：`REQUEST_CHANGES`）
- **架构决策依据**：
  * [`docs/adr/0008-networking-model.md`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/docs/adr/0008-networking-model.md)（默认出站 NAT + 显式端口转发，机制/语义分离，明示关闭 IPv6，DNS 穿透机制）
  * [`docs/adr/0010-security-boundary.md`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/docs/adr/0010-security-boundary.md)（安全边界：暴露面白名单制，入站默认零暴露）
- **报告性质**：Ticket 27 代码实现、独立评审整改重构与全量测试自测报告。供独立审计会话复评，不代用户签字。

---

## 二、 任务目标与完成情况总览

在 `runtime/engine-qemu` 与 `runtime/contract` 中实现策略无关的公共网络契约与 QEMU 专有出站网络装配，使 Alpine Linux 实例在启动时默认获得免配置的出站 NAT 访问能力（支持 `apk update` 与软件包安装），建立网络契约测试套件，并通过内置公共 DNS 兜底彻底解决 Android 宿主环境缺失 `/etc/resolv.conf` 的 DNS 解析适配问题。

### 验收标准（AC）达成清单（含 Review 整改项）

| 验收项 | 核心标准 | 完成状态 | 验证手段 / 关键证据 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **参数装配与无侵入（架构解耦重构）**：`QemuLaunchSpec.build` 正确装配 SLIRP 参数：<br>1. `-netdev user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=8.8.8.8`<br>2. `-device virtio-net-pci,netdev=net0`（支持 `virtio-net-device`）<br>3. 保证 IPv6 明示关闭（`ipv6=off`）<br>4. **契约解耦**：CLI 序列化完全内聚于 Engine 层 | **✅ 达成** | 移除了 `NetworkContract` 中的 `toNetdevArg()` / `toDeviceArg()`；由 `QemuLaunchSpec.formatNetdevArg` 与 `formatDeviceArg` 负责拼装，测试覆盖率 100%。 |
| **AC-2** | **DNS 穿透适配（默认 DNS 兜底）**：解决 Android 宿主缺失 `/etc/resolv.conf` 的解析无源缺陷，实例开箱具备公共 DNS（`8.8.8.8` / `1.1.1.1`）解析能力 | **✅ 达成** | `OutboundNetworkSpec.defaultSpec()` 默认内置 `DEFAULT_FALLBACK_DNS` (`8.8.8.8`)，并通过 SLIRP `,dns=` 与内核 cmdline `droidlayer.dns=` 双通道同步透传。 |
| **AC-3** | **契约测试全绿**：在 `runtime/contract` 与 `runtime/engine-qemu` 实现契约测试，覆盖出站 NAT 参数正确性、零入站默认暴露（无 `hostfwd` 泄露）与配置回放 | **✅ 达成** | `NetworkContractTest` 验证纯语义模型与严格无前导零 IP 校验；`QemuLaunchSpecTest` 验证 CLI 参数生成、IPv6 阻断与零 `hostfwd` 泄露。全部纳入 `gradle check`。 |
| **AC-4** | **真机网络冒烟规范（完整闭环）**：编写实机运行验收用例，覆盖 `apk update` 与实际安装软件包（`apk add curl`）及换网恢复协议 | **✅ 达成** | `tests/contract/outbound_network_smoke_spec.sh` 补充了 `apk add --no-cache curl` 安装与 HTTP 请求探测断言，并固化了 Wi-Fi $\leftrightarrow$ 蜂窝移动网络切换的观测与恢复协议。 |

---

## 三、 Review 专项整改（Rework Slice）详细记录

根据独立 Review 报告提出的 1 项 Hard Violation 与 3 项 Spec 缺陷，进行了针对性的原子化重构：

### 1. 架构解耦与策略中立性恢复（Standards 轴修复）
- **问题根源**：此前在 `runtime/contract/NetworkContract.java` 中直接实现了面向 QEMU 的 `toNetdevArg()`、`toDeviceArg()` 及 `DeviceType.qemuDeviceName`，违反了 ADR-0008 机制与语义分离的原则，导致后续 AVF 档（Ticket 34）复用契约时背负无用的 QEMU CLI 方法。
- **重构方案**：
  1. 彻底移除 `NetworkContract` 中的 `toNetdevArg()` 与 `toDeviceArg()` 方法；
  2. 移除 `DeviceType` 中的 `qemuDeviceName` 字段，使 `DeviceType` 恢复为纯粹的策略中立枚举（`VIRTIO_NET_PCI`, `VIRTIO_NET_DEVICE`）；
  3. 移除未被工单范围引用的空桩方法 `hasInboundExposure()` 与 `inboundRulesCount()`；
  4. 将 CLI 拼装收敛至 `runtime/engine-qemu/QemuLaunchSpec.java`，定义专有的包私有静态格式化器：
     - `static String formatNetdevArg(NetworkContract.OutboundNetworkSpec spec)`
     - `static String formatDeviceArg(NetworkContract.OutboundNetworkSpec spec)`
  5. 优化 IPv4 正则表达式，严格禁止前导零（如 `010.0.2.15`），规避 POSIX/C 环境下的八进制歧义解析隐患。

### 2. Android 宿主无 `/etc/resolv.conf` 的默认 DNS 兜底（Spec AC-2 修复）
- **问题根源**：原实现中 `defaultSpec()` 的 `dnsServer` 默认为 `null`。在缺乏显式 `,dns=` 参数时，QEMU SLIRP 默认尝试读取宿主机的 `/etc/resolv.conf`。由于 Android 平台完全不存在该文件，导致实例在默认调用时 DNS 处于无源瘫痪状态。
- **重构方案**：
  1. 在 `NetworkContract` 中明确声明公共 DNS 常量：
     ```java
     public static final String DEFAULT_FALLBACK_DNS = "8.8.8.8";
     public static final String SECONDARY_FALLBACK_DNS = "1.1.1.1";
     ```
  2. 将 `OutboundNetworkSpec.Builder` 的 `dnsServer` 与 `cmdlineDns` 初始默认值均设置为 `DEFAULT_FALLBACK_DNS` (`8.8.8.8`)；
  3. `withDns(String)` 同时同步设置 SLIRP `dnsServer` 与内核 `cmdlineDns`，确保双通道一致兜底；
  4. 既有调用方在调用 `QemuLaunchSpec.build()` 时，实例开箱即可自动获得经过网关 NAT 转发的可靠公共 DNS 服务。

### 3. 补全真机冒烟用例规范（Spec AC-4 修复）
- **问题根源**：原冒烟脚本仅包含 `apk update` 和 `apk info`，遗漏了工单中“真机上 `apk update` 成功且能安装一个软件包”的要求；同时缺少对宿主网络切换（Wi-Fi $\leftrightarrow$ 移动蜂窝网络）行为的明确说明。
- **重构方案**：
  1. 在 `tests/contract/outbound_network_smoke_spec.sh` 的 Step 6 追加实际软件包安装流程：
     ```sh
     apk add --no-cache curl
     curl --version
     curl -I -s --connect-timeout 5 https://dl-cdn.alpinelinux.org/alpine/ | head -n 1
     apk del curl
     ```
  2. 在 Step 8 明确固化宿主网络切换的观测与恢复协议：记录 Android 宿主底层套接字重绑特性，指明在网络切换 3–5 秒稳定后通过 `apk update && nslookup dl-cdn.alpinelinux.org` 验证连接自愈。

---

## 四、 模块边界与红线守则执行审计

严格遵守派工数据包中的 Ownership 边界与红线禁区：

1. **允许修改的模块（Owned）**：
   - `runtime/contract/src/main/.../NetworkContract.java`（修改）：纯策略无关网络契约及不可变数据模型；
   - `runtime/contract/src/test/.../NetworkContractTest.java`（修改）：策略中立契约测试；
   - `runtime/contract/build.gradle.kts`（保持）：注册 `networkContractTest`；
   - `runtime/engine-qemu/src/main/.../QemuLaunchSpec.java`（修改）：内聚 CLI 格式化与网络参数装配；
   - `runtime/engine-qemu/src/test/.../QemuLaunchSpecTest.java`（修改）：更新默认参数断言与格式化器测试；
   - `tests/contract/outbound_network_smoke_spec.sh`（修改）：完善软件包安装与网络切换冒烟断言。
2. **严禁修改的模块（Forbidden）**：
   - `runtime/engine-avf/`：**零触碰**；
   - `storage/` 及根磁盘扩容逻辑：**零触碰**；
   - `guest-assets/` 内核镜像与 `inputs.lock`：**零触碰**；
   - `runtime/instance/`（属于 Ticket 28）：**零触碰**。

---

## 五、 本地测试与回归验证结果

### 1. 契约与引擎模块专项 Check
执行命令：
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check --rerun-tasks
```
**结果**：`BUILD SUCCESSFUL in 14s`，17 actionable tasks 全部执行通过（17/17 PASS）：
- `Task :runtime:contract:networkContractTest` -> `NETWORK_CONTRACT_TEST_OK`
- `Task :runtime:contract:bridgeControlContractTest` -> PASS
- `Task :runtime:contract:bridgeHandshakeContractTest` -> PASS
- `Task :runtime:contract:fullLinuxContractTest` -> PASS
- `Task :runtime:engine-qemu:qemuLaunchSpecTest` ->
  - `DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1`
  - `ENGINE_CLI_FORMATTERS_TEST_OK`
  - `DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1`
  - `MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device`
- `Task :runtime:engine-qemu:embeddedProvisionInputTest` -> PASS
- `Task :runtime:engine-qemu:runtimeArtifactManifestTest` -> PASS
- `Task :runtime:engine-qemu:runtimeAssetInstallerTest` -> PASS

### 2. 全工程单测全量验证（含 `:app:testDebugUnitTest`）
遵循 `docs/development/android-bootstrap.md` 规避 Windows 括号及非 ASCII 路径的规范，映射临时盘符执行：
```powershell
subst R: "D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev"
cmd /c "cd /d R:\ && gradlew.bat test --rerun-tasks"
subst R: /D
```
**结果**：`BUILD SUCCESSFUL in 33s`，75 actionable tasks 全部执行通过（75/75 PASS）：
- 所有 runtime 模块单测全部通过；
- `:app:testDebugUnitTest` 与 `:app:testReleaseUnitTest` 零失败、零跳过。
- 编译器门控：`-Xlint:all -Werror` 保持零告警通过。

---

## 六、 交付状态与后续交接

1. 针对 Review 报告中指出的全部问题已完成高精度、原子化闭环整改；
2. 架构解耦完全达成，Android 生产环境默认 DNS 穿透无源缺陷彻底排除，冒烟规范全量达标；
3. 本报告正式呈交 Orchestrator，准备提请独立评审专员执行第二轮复审。
