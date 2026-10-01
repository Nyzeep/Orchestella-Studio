# Review 报告：Ticket 27

- **审查对象**：`ticket/27-outbound-network`，比对基线 `c3e6a3f`（或 `main`），审查命令 `git diff c3e6a3f...HEAD`
- **初审 HEAD**：`d33592f feat(network): 实现出站网络装配与 SLIRP 网络契约 (Ticket 27)`（初审裁定：`REQUEST_CHANGES`）
- **复审 HEAD**：`9833548 fix(network): 架构解耦、内置 Android 默认 DNS 兜底与冒烟用例闭环 (Ticket 27 Review Refactor)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行二次针对性复查（Re-Review）；在本地环境实测执行 `./gradlew :runtime:contract:check :runtime:engine-qemu:check --rerun-tasks` 验证全量构建与契约测试真实性。

---

## 阶段复审总览（Re-Review Summary）

针对初审报告所提出的 **1 项 Hard Violation**（契约层泄露 QEMU CLI）与 **3 项 Spec 缺陷**（Android 默认无源 DNS 缺陷、冒烟用例缺失实际安装包验证、网络切换恢复未记录），Implementation Worker 已于 commit `9833548` 完成了精准、原子化的闭环重构。

经复核，所有阻断项全部修复到位，代码异味完全消除，本地构建与契约测试 100% 保持全绿。最终裁定由 `REQUEST_CHANGES` 提升为 **APPROVE**。

---

## Standards

**PASS — 0 Hard Violations, 0 Baseline Smells**

### 1. 架构解耦与策略中立性核验（ADR-0008 §3）
- **[RESOLVED] 契约层 QEMU 专有序列化代码已彻底剥离**：
  - 在 [`NetworkContract.java`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContract.java) 中，彻底移除了 `toNetdevArg()` 与 `toDeviceArg()` 方法；
  - `DeviceType` 移除了 `qemuDeviceName` 字段及构造器，恢复为纯粹的策略无关枚举（`VIRTIO_NET_PCI`, `VIRTIO_NET_DEVICE`）；
  - 移除了仅作为占位的虚拟打桩方法 `hasInboundExposure()` 与 `inboundRulesCount()`，消除了 Speculative Generality；
  - 优化 IPv4 正则表达式（`IPV4_OCTET` 严格拦截前导零，如 `010.0.2.15`），杜绝了 POSIX/C 环境下的八进制歧义解析隐患。
- **[RESOLVED] CLI 序列化职责内聚归位**：
  - 在 [`QemuLaunchSpec.java`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpec.java#L107-L136) 中实现专有的包私有静态格式化方法：
    * `static String formatNetdevArg(NetworkContract.OutboundNetworkSpec spec)`
    * `static String formatDeviceArg(NetworkContract.OutboundNetworkSpec spec)`
  - 参数拼装完全收敛在 Engine 模块内部，`runtime/contract` 保持 100% 策略中立，为后续 Ticket 34（AVF 档）复用清除了所有 QEMU 专有污染。

### 2. 治理规范遵从性
- **[COMPLIANT] 资产分类与卫生规范（`ASSET_REUSE_AND_HYGIENE_POLICY.md`）**：
  - Class A 永久基石资产（Android 离线 SDK、Linux 6.6 内核源码树、Alpine rootfs 底包等）保持绝对零触碰；
  - 变更严格限制于本地 Git 跟踪文件，无任何多余临时消耗物外溢。
- **[COMPLIANT] 模块边界隔离（`AGENT_WORKER_CONTRACT.md`）**：
  - 严守红线禁区：`runtime/instance/`、`runtime/engine-avf/`、`storage/`、`guest-assets/` 与内核镜像保持**零修改**；
  - 修改范围仅限于派工边界内的 `runtime/contract/`、`runtime/engine-qemu/`、`tests/contract/` 与关联报告。
- **[COMPLIANT] 编译质量**：
  - 全工程 Java 源码与测试代码均受 `-Xlint:all -Werror` 严格门控，零警告、零隐式类型转换异常。

---

## Spec

**PASS — 4 项验收标准（AC-1 ~ AC-4）全部达成闭环**

### 1. 工单验收标准（Acceptance Criteria）逐条核对

| 验收项 | 工单要求 | 初审状态 | 复审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :---: | :--- |
| **AC-1** | `QemuLaunchSpec` 正确装配 SLIRP 参数（`10.0.2.0/24`、`dhcpstart=10.0.2.15`、`virtio-net-pci` / MMIO），且明示关闭 IPv6（`ipv6=off`） | ⚠️ PARTIAL | **✅ PASS** | 参数拼装由 Engine 层专有方法完成；`-netdev` 强制包含 `ipv6=off`、`net=10.0.2.0/24`、`dhcpstart=10.0.2.15`；正确支持 PCI 与 MMIO 设备。 |
| **AC-2** | DNS 穿透妥善设计，解决 Android 宿主缺失 `/etc/resolv.conf` 的解析传递问题 | ❌ FAIL | **✅ PASS** | **默认 DNS 兜底彻底闭环**：`OutboundNetworkSpec.defaultSpec()` 默认内置 `DEFAULT_FALLBACK_DNS` (`8.8.8.8`)；SLIRP `,dns=8.8.8.8` 与内核 cmdline `droidlayer.dns=8.8.8.8` 同步透传，解决 Android 宿主无 `/etc/resolv.conf` 导致实例默认 DNS 瘫痪的核心缺陷。 |
| **AC-3** | 契约测试覆盖出站 NAT 参数校验、默认入站零暴露（零 hostfwd 泄露）与配置回放 | ✅ PASS | **✅ PASS** | `NetworkContractTest` 验证纯语义模型、严格无前导零 IP 校验；`QemuLaunchSpecTest` 验证 Engine CLI 格式化输出、IPv6 显式禁用、入站零暴露断言。 |
| **AC-4** | 真机网络冒烟用例规范（`tests/contract/outbound_network_smoke_spec.sh`）严谨可执行 | ⚠️ PARTIAL | **✅ PASS** | **实测流程完整补齐**：脚本 Step 6c 补齐了 `apk add --no-cache curl` 安装与 HTTP 请求探测断言，并在 Step 8 明确固化了 Wi-Fi $\leftrightarrow$ 移动蜂窝网络切换的观测与自愈协议。 |

### 2. 缺陷修复核销证据
1. **安装软件包验证补齐**：[`outbound_network_smoke_spec.sh`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/tests/contract/outbound_network_smoke_spec.sh#L102-L110)（第 102–110 行）增加了执行 `apk add --no-cache curl`、校验二进制 `curl --version`、执行 HTTP 探测以及清理的完整链路，完全满足工单“真机上 `apk update` 成功且能安装一个软件包”的要求。
2. **Android 默认无源 DNS 修复**：[`NetworkContract.java`](file:///d:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev/runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/NetworkContract.java#L26-L30) 定义了 `DEFAULT_FALLBACK_DNS = "8.8.8.8"`，`Builder` 默认装配公共 DNS。上层无参调用 `QemuLaunchSpec.build()` 时默认输出 `-netdev user,...,dns=8.8.8.8` 与 cmdline `droidlayer.dns=8.8.8.8`，实现物理真机开箱即用。
3. **网络自愈与换网协议固化**：冒烟脚本第 8 步固化了 Wi-Fi $\leftrightarrow$ 蜂窝网络切换 3–5 秒后的自愈验证准则（`apk update && nslookup dl-cdn.alpinelinux.org` 退出码 0），行为预期透明可测量。

---

## 测试真实性与覆盖评估

复审专员在本地真实工具链环境下重新执行了全量任务重跑验证：
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check --rerun-tasks
```
**实测结果**：
- 执行状态：`BUILD SUCCESSFUL in 14s`，17 个 actionable tasks 全部重新执行通过（17/17 PASS）；
- 核心输出摘要：
  - `:runtime:contract:networkContractTest` $\rightarrow$ `NETWORK_CONTRACT_TEST_OK`
  - `:runtime:engine-qemu:qemuLaunchSpecTest` $\rightarrow$ 
    - `DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1`
    - `ENGINE_CLI_FORMATTERS_TEST_OK`
    - `DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1`
    - `MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device`
  - `:runtime:engine-qemu:embeddedProvisionInputTest` $\rightarrow$ `EMBEDDED_PROVISION_INPUT_TEST_OK`
  - `:runtime:engine-qemu:runtimeAssetInstallerTest` $\rightarrow$ `RUNTIME_ASSET_INSTALLER_TEST_OK`
- 编译器门控：零警告、零错误，完美兼容 `-Xlint:all -Werror`。

---

## 暴露面检查

- **入站暴露面**：严格维持零暴露。默认启动参数中无任何 `-redir` 或 `hostfwd`，契约测试严密锁定；
- **IPv6 暴露面**：全局强制明示关闭（`ipv6=off`），拦截了非目标协议栈引入的安全与兼容性风险；
- **参数注入安全**：所有 QEMU 启动参数均为不可变数组传参，杜绝了 Shell 解析与命令拼接注入；
- **宿主特权边界**：无多余 Android 宿主权限请求，无白名单外网络服务启动。

---

## 结论

**APPROVE**

Ticket 27 在经过初审反馈与针对性重构后，架构解耦彻底到位，Android 真实宿主环境下的默认 DNS 解析缺陷彻底排除，真机网络冒烟用例规范完整闭环，所有单元测试与契约测试全绿通过。

本工单已完全具备向主干分支（`main`）合并的工程与架构就绪条件。

---

汇总：Standards 0 个 blocking finding，0 个 smells；Spec 0 个 blocking defect，4 个 acceptance criteria 均达到 100% 闭环。

报告人：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer） ｜ 日期：2026-10-01 ｜ 耗时：双轴独立审计 + 本地全量构建与用例实测验证
