# Review 报告：Ticket 27

- **审查对象**：`ticket/27-outbound-network`，比对基线 `c3e6a3f`（或 `main`），审查命令 `git diff c3e6a3f...HEAD`
- **待审查 Commit**：`d33592f feat(network): 实现出站网络装配与 SLIRP 网络契约 (Ticket 27)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer） + Standards / Spec 双只读 review subagent
- **审查方式**：Standards 规范轴与 Spec 工单规格轴双轴并行物理隔离复核；在本地环境实测执行 `./gradlew :runtime:contract:check :runtime:engine-qemu:check --rerun-tasks` 验证构建与用例真实性。

---

## Standards

**NOT PASS — 1 Hard Violation, 3 Baseline Smells**

### 1. 文档化规范与架构约束遵从性

- **[HARD VIOLATION] 架构分层违背与策略中立性破损（ADR-0008 §3 "机制/语义分离... 上层只见转发规则"）**
  - **位置**：`runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContract.java`（第 38–51 行、第 150–167 行）
  - **规则**：依据架构总则与 ADR-0008，`runtime/contract` 是跨虚拟化引擎（QEMU 与后续 Ticket 34 的 AVF）的公共策略中立契约层，只定义网络语义与配置数据模型。具体 VMM 的命令行参数组装与执行细节严禁泄露入契约层，必须严格隔离在 `runtime/engine-qemu` 内部。
  - **事实**：`NetworkContract` 直接定义并实现了面向 QEMU 命令行语法的序列化方法：
    ```java
    public enum DeviceType {
        VIRTIO_NET_PCI("virtio-net-pci"),
        VIRTIO_NET_DEVICE("virtio-net-device");
        public String qemuDeviceName() { ... }
    }
    public String toNetdevArg() {
        StringBuilder sb = new StringBuilder();
        sb.append("user,id=").append(netdevId)...
    }
    public String toDeviceArg() {
        return deviceType.qemuDeviceName() + ",netdev=" + netdevId;
    }
    ```
  - **影响**：QEMU 专有的 `-netdev` 与 `-device` 参数字符串组装逻辑污染了公共契约；后续 AVF 档（Ticket 34）复用 `NetworkContract` 时将背负无用的 QEMU 专有命令行字段与方法。

- **[COMPLIANT] 资产治理遵从性（`ASSET_REUSE_AND_HYGIENE_POLICY.md`）**：
  - 未触碰或删除任何 Class A 永久保全资产（离线 SDK 工具链、内核镜像源码、官方 Alpine rootfs 底包等完好无损）；
  - 变更严格限制于本地 Git 跟踪文件，未产生未受控的临时废弃物。

- **[COMPLIANT] 模块所有权边界（`AGENT_WORKER_CONTRACT.md`）**：
  - 严守红线禁区：`runtime/instance/`、`runtime/engine-avf/`、`storage/`、`guest-assets/` 与内核镜像保持**零修改**；
  - 修改文件严格限定于派工声明的 `runtime/contract/`、`runtime/engine-qemu/`、`tests/contract/` 及相关报告。

- **[COMPLIANT] Java 与工具链编译质量**：
  - 源码与测试代码均通过 `-Xlint:all -Werror` 编译门控，零警告、零隐式类型转换异常。

### 2. Baseline Code Smells（启发式代码异味）

- **Feature Envy（依恋情结 · `NetworkContract.java:150-167`）**：
  `OutboundNetworkSpec` 越俎代庖直接组装 QEMU CLI 参数字符串，其实质为 `QemuLaunchSpec` 的内部职责，属于典型的跨模块依恋。
- **Speculative Generality（夸夸其谈 · `NetworkContract.java:76-80, 136-145`）**：
  在仅实现出站 NAT 的 Ticket 27 中，预留了无后备字段的空桩方法 `hasInboundExposure()`（硬编码返回 `false`）与 `inboundRulesCount()`（硬编码返回 `0`）；同时 `ipv6Enabled` 虽为 Builder 参数，但传入 `true` 时直接抛出异常，属于将硬性不变式（`ipv6=off`）伪装成可选配置项的过度设计。
- **Primitive Obsession（基本类型偏执 · `NetworkContract.java:57-64`）**：
  IP 地址与 CIDR 子网掩码均使用原生 `String` 配合正则表达式进行校验。鉴于 MVP 契约规模，此项作为非阻断 Judgement Call，不强制抽象为强类型 Value Object。

---

## Spec

**NOT PASS — 3 项验收要求缺陷/遗漏，1 项真实环境 DNS 穿透缺陷**

### 1. 工单验收标准（Acceptance Criteria）逐条核对

| 验收项 | 工单要求 | 审查判定 | 审计证据与缺陷分析 |
| :--- | :--- | :---: | :--- |
| **AC-1** | `QemuLaunchSpec` 正确装配 SLIRP 参数（`10.0.2.0/24`、`dhcpstart=10.0.2.15`、`virtio-net-pci` / MMIO），且明示关闭 IPv6（`ipv6=off`） | **⚠️ PARTIAL** | 参数项组装正确且 `ipv6=off` 显式声明；但装配逻辑错误下沉在 `runtime/contract` 中调用，破坏了架构分层。 |
| **AC-2** | DNS 穿透妥善设计，解决 Android 宿主缺失 `/etc/resolv.conf` 的解析传递问题 | **❌ FAIL** | **生产环境默认调用失效**：在 `NetworkContract.java` 中，`defaultSpec()` 默认 `dnsServer = null`、`cmdlineDns = null`。在缺少 `,dns=` 的情况下，QEMU SLIRP 默认尝试读取宿主 `/etc/resolv.conf`，而在 Android 宿主上此文件根本不存在！由于上层 `QemuInstanceLauncher.java` 调用 7 参数 `build()` 委托 `defaultSpec()`，导致真机运行时实例的默认 DNS 代理将处于上游无源状态。 |
| **AC-3** | 契约测试覆盖出站 NAT 参数校验、默认入站零暴露（零 hostfwd 泄露）与配置回放 | **✅ PASS** | `NetworkContractTest` 与 `QemuLaunchSpecTest` 严密覆盖了默认参数、CIDR/IPv4 校验、`hostfwd` 泄漏检测、`ipv6=off` 确保与不可变 Replay。 |
| **AC-4** | 真机网络冒烟用例规范（`tests/contract/outbound_network_smoke_spec.sh`）严谨可执行 | **⚠️ PARTIAL** | **遗漏软件包安装验证**：工单明确要求“真机上 `apk update` 成功且能安装一个软件包”；冒烟脚本（第 94–102 行）仅执行了 `apk update` 与 `apk info -v alpine-base`（本地包信息查询），并未执行 `apk add <pkg>` 进行实际包安装下载断言。 |

### 2. 遗漏要求与未完成项
1. **软件包安装缺失**：工单 `.scratch/droidlayer-platform/issues/27-impl-outbound-network.md:14` 明文要求：`"- [ ] 真机上 apk update 成功且能安装一个软件包"`。当前测试脚本未覆盖包安装行为。
2. **宿主换网与弱网恢复行为未记录**：工单第 15 行与第 21 行明确要求：`"- [ ] DNS 解析在宿主换网（Wi-Fi↔移动数据）后仍可用或可恢复（记录实际行为）"` 与 `"真机冒烟清单（含弱网/断网恢复行为记录）"`。实施报告与冒烟脚本均未对网络切换场景进行验证与事实记录。
3. **“免配置出站”在 Guest 侧未闭环**：工单与 ADR-0008 承诺“guest 免配置出站”。当前 `outbound_network_smoke_spec.sh` 中（第 36–74 行）依赖脚本显式执行 `udhcpc`、手动添加默认网关 `ip route add default via 10.0.2.2` 及写入 `/etc/resolv.conf`。这表明 Alpine rootfs / initramfs 未默认启用网络服务的自动发现，尚未达到真正的实例“免配置”。

### 3. Scope Creep（范围蔓延）
- 在 `OutboundNetworkSpec` 中引入了未在派工包范围内的 `hasInboundExposure()` 与 `inboundRulesCount()` 虚拟打桩方法；
- 声明了未被任何 Java 逻辑或断言引用的无用常量 `DEFAULT_GATEWAY_IP = "10.0.2.2"`。

### 4. 边界漏洞与实现瑕疵
- **DNS 双通道传递不对称**：`OutboundNetworkSpec.withDns(String)` 仅配置了 `dnsServer`（供 SLIRP 使用），但将 `cmdlineDns` 置为 `null`，导致内核启动参数丢失 `droidlayer.dns=<ip>`，削弱了 cmdline DNS 兜底通道的效能；
- **IPv4 正则存在前导零八进制解析风险**：`NetworkContract.java:29` 正则 `[01]?[0-9][0-9]?` 允许诸如 `010.0.2.15` 的前导零 IP，在 POSIX/C 环境的解析器中会被误当八进制解析。

---

## 测试真实性与覆盖评估

评审专员在本地真实工具链环境下重新执行了全量验证：
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check --rerun-tasks
```
**实测结果**：
- 执行状态：`BUILD SUCCESSFUL in 15s`，17 个 actionable tasks 全部重新执行通过（17/17 PASS）；
- 核心输出：
  - `:runtime:contract:networkContractTest` $\rightarrow$ `NETWORK_CONTRACT_TEST_OK`
  - `:runtime:engine-qemu:qemuLaunchSpecTest` $\rightarrow$ 
    - `DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 no_9p=1`
    - `DNS_LAUNCH_SPEC_OK dns=8.8.8.8 cmdlineDns=1`
    - `MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device`
- 编译器门控：无编译警告，完全兼容 `-Xlint:all -Werror`。

**深度评估**：
测试执行结果完全真实，但单元测试所断言的“默认发射规格”恰恰锁定了 `dnsServer = null` 的错误状态，导致“测试全绿”掩盖了“在无 `/etc/resolv.conf` 的 Android 真机环境下默认 DNS 解析必然失败”的系统级缺陷。

---

## 暴露面检查

- **入站暴露面**：严格满足 ADR-0010 与 ADR-0008。默认启动参数中无任何 `-redir` 或 `hostfwd` 规则注入，入站默认零暴露得到契约测试强制锁定；
- **协议暴露面**：`-netdev` 中强制携带 `ipv6=off`，成功阻断了未经验证的 IPv6 协议栈外溢风险；
- **命令注入防御**：命令行参数维持参数化数组结构（`String[]`），无 Shell 中间层介入，且测试显式断言杜绝了 `;`, `&`, `|`, `` ` `` 等元字符；
- **架构变更门**：未触发白名单外能力扩充，未引入未授权宿主权限。

---

## 结论

**REQUEST_CHANGES**

当前分支 `ticket/27-outbound-network` 尽管在代码整洁度、模块边界保护与基础单测方面表现良好，但在**架构策略中立性（Standards 轴）**与**真机 Android 生产可用性（Spec 轴）**上存在核心阻断项。

### 整改清单（Action Items）

1. **[架构分层纠偏 · Standards] 剥离 `runtime/contract` 中的 QEMU CLI 序列化代码**：
   - 将 `toNetdevArg()`、`toDeviceArg()` 以及 `DeviceType.qemuDeviceName()` 移出 `runtime/contract`，重构回 `runtime/engine-qemu` 内部（例如在 `QemuLaunchSpec` 或专属的 `QemuNetworkArgBuilder` 中完成参数拼接）；
   - `NetworkContract.OutboundNetworkSpec` 仅保留纯语义数据模型（字段、校验、语义 Getter 及 Builder），保持与 VMM 引擎完全解耦。
2. **[DNS 穿透修复 · Spec] 解决 Android 宿主环境缺失 `/etc/resolv.conf` 导致默认 DNS 失效的缺陷**：
   - 为 `OutboundNetworkSpec.defaultSpec()` 配置确定性的有效上游 DNS（如内置默认指向 `GOOGLE_DNS` `8.8.8.8` 或 `CLOUDFLARE_DNS` `1.1.1.1`）；
   - 或者在 `QemuInstanceLauncher.java` 中通过 Android 宿主 API（`ConnectivityManager` / `LinkProperties.getDnsServers()`）解析当前活动网络 DNS 并显式注入 `QemuLaunchSpec.build(...)`；
   - 统一 `withDns()`，使其对 SLIRP `dnsServer` 与 cmdline `droidlayer.dns` 保持同步传递。
3. **[工单完整度补全 · Spec] 完善真机冒烟测试与验证清单**：
   - 在 `tests/contract/outbound_network_smoke_spec.sh` 中补充真实的软件包安装命令（例如 `apk add --no-cache curl` 或其他轻量包），并验证二进制可用，达成“成功安装一个软件包”的工单要求；
   - 记录宿主换网（Wi-Fi↔移动网络）与断网情况下的 DNS 解析表现与恢复特征，补充至验收记录中。

---

汇总：Standards 1 个 blocking hard violation（架构契约层泄露 QEMU CLI），3 个 baseline smells；Spec 1 个 blocking defect（默认 Android DNS 无源失效），2 个 partial acceptance criteria。

报告人：DroidLayer Independent Reviewer（Reviewer Tier） ｜ 日期：2026-10-01 ｜ 耗时：双轴独立子代理审查 + 本地构建实测验证
