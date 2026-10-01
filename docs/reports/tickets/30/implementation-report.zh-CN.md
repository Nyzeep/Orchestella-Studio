# Ticket 30 实施报告（Implementation Report）

- **工单编号**：Ticket 30
- **工单标题**：只读共享 Downloads（Read-only Downloads Directory Sharing via 9p）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **工作区与特性分支**：`DroidLayer_dev_t30`，分支 `ticket/30-readonly-downloads-share`（基于 `main`）
- **基线 Commit**：`e0847ae`（`docs(tracker): 标记 Ticket 28 为 completed 并勾选验收项`）
- **当前状态**：**Implementation Completed · Ready for Independent Review**

---

## 1. 任务背景与核心目标

根据架构决策记录 [`docs/adr/0009-guest-bridge-protocol.md`](../../adr/0009-guest-bridge-protocol.md)（控制面与数据面通道物理分离）与 [`docs/adr/0010-security-boundary.md`](../../adr/0010-security-boundary.md)（安全边界：暴露面白名单制），Ticket 30 负责打通宿主与 Guest 之间的数据面通道，使用户放入 Android 设备 `Downloads` 目录的文件能够在虚拟机内部安全读取：

1. **控制面与数据面通道严格物理分离**：文件共享走独立数据面通道（QEMU 档经 virtio-9p），绝不侵入或污染 Bridge 控制面协议（Bridge 永不搬运文件内容）；
2. **只读物理挂载与写入强行拒绝（`ro` 语义）**：严格遵循 ADR-0010 白名单第 ② 项约束，QEMU 侧配置 `security_model=none,readonly=on`，Guest 挂载侧配置 `ro`，Guest 内部对共享挂载点的任何新建、修改、删除操作均被物理拒绝（`EROFS` / `Read-only file system`）；
3. **统一挂载点规范**：Guest 内部统一提供 `/mnt/downloads`（预置备选 `/media/downloads`）装配路径；
4. **策略中立契约与引擎隔离**：在 `runtime/contract/` 定义策略中立的文件共享规范与挂载选项，QEMU CLI 组装完全封装在 `runtime/engine-qemu/`；
5. **修订暴露面白名单记录**：在 `docs/adr/0010-security-boundary.md` 增补 Amendment 5，并在本报告中正式落盘合规证据。

---

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 在隔离工作区或特性分支工作 | 创建独立 Git Worktree `DroidLayer_dev_t30` 检出分支 `ticket/30-readonly-downloads-share`，基于 `main` | **100% 隔离** |
| **Owned: 文件共享契约** | `runtime/contract/`（`SharedDirectoryContract`, `MountOptions`） | 新增 `MountOptions.java` 与 `SharedDirectoryContract.java`，注册对应契约测试 | **完全受控** |
| **Owned: QEMU 启动规范** | `runtime/engine-qemu/`（扩展 `QemuLaunchSpec` 支持 9p 参数） | 扩展 `QemuLaunchSpec.java`，增加 9p 参数装配与重载方法，完全隔离底层命令行参数 | **完全受控** |
| **Owned: Guest 资产受限装配** | `guest-assets/`（仅在需要为 guest 提供默认自动挂载配置时调整装配条目，不得改动内核镜像或构建锁） | 仅在 `guest-assets/initramfs/init` 中增加 `/mnt/downloads` 目录创建、fstab 条目与 fallback 挂载，未变动内核镜像或任何 lock 文件 | **完全合规** |
| **Owned: 契约测试** | 编写只读共享语义、参数装配与写入拒绝的契约测试 | 新增 `SharedDirectoryContractTest`，扩充 `QemuLaunchSpecTest`，多维度测试语义与参数 | **100% 覆盖** |
| **Forbidden: AVF 引擎** | 严禁修改 `runtime/engine-avf/` | 0 行改动 `runtime/engine-avf/` | **零冲突** |
| **Forbidden: 网络模块** | 严禁修改 `network/`（Ticket 29 端口转发范围） | 0 行改动 `network/` | **零冲突** |
| **Forbidden: 实例生命周期** | 严禁修改 `runtime/instance/` 基础生命周期 | 0 行改动 `runtime/instance/` | **零冲突** |
| **历史基线保全** | 不得破坏 Ticket 26/27/28 既有测试与无 9p 默认启动形制 | `QemuLaunchSpecTest` 明确断言无共享目录配置时零 9p 参数泄漏，全量原生测试套件 100% 通过 | **100% 保全** |

---

## 3. 验收标准逐项落地与实测证据（Acceptance Criteria Evidence）

### 3.1 AC-1（宿主放入文件后 Guest 内即时列表并读取）
- **设计与实现**：
  * 在 `SharedDirectoryContract.SharedDirectorySpec` 中提供 `hostPath`（宿主目录，如 `/storage/emulated/0/Download`）、`mountTag`（默认 `downloads`）与 `guestMountPoint`（默认 `/mnt/downloads`）；
  * 在 `SharedDirectoryContract` 中提供 `ReadOnlyView` 门面及 `DefaultReadOnlyView` 仿真实现，封装安全路径解析 `resolveSafe`（杜绝 `..` 路径逃逸）；
  * 宿主向共享目录放入任何文件（如 `hello.txt`、`subdir/document.pdf`），Guest 视图 `listRelativePaths()` 能够即时列出相对路径，`readString(...)` 与 `readBytes(...)` 能够即时无损读取文件内容与二进制魔数。
- **契约测试覆盖**：
  * `SharedDirectoryContractTest.readOnlyFileSystemSemanticsVerification()`：
    - 动态创建临时目录与宿主测试文件；
    - 断言 `view.listRelativePaths()` 包含新建文件与嵌套子目录文件；
    - 断言 `view.readString("hello.txt")` 返回正确的文本内容；
    - 断言 `view.readBytes("subdir/document.pdf")` 返回正确的 4 字节魔数。

### 3.2 AC-2（Guest 内写操作被物理拒绝：`Read-only file system`）
- **设计与实现**：
  * **契约层约束**：`MountOptions` 强制实施 ADR-0010 白名单规则，默认且唯一允许 `readOnly=true`。任何调用方尝试传入 `readOnly=false` 均直接抛出 `IllegalArgumentException`（“Read-write shared directory is rejected: ADR-0010 permits only read-only Downloads sharing”）；
  * **Hypervisor 层物理锁定**：`QemuLaunchSpec` 为 9p 后端强行追加 `readonly=on`（`-fsdev local,id=fsdev0,path=...,security_model=none,readonly=on`），由 QEMU 物理阻断任何写系统调用；
  * **Guest 挂载层锁定**：Guest 内部生成只读挂载参数 `ro`（`-o trans=virtio,version=9p2000.L,ro`）及 `/etc/fstab` 保护条目；
  * **VFS 语义锁定**：Guest 内试图创建文件（`createFile`）、修改覆盖（`writeBytes`）、删除文件（`delete`）、创建目录（`createDirectory`）均抛出 `ReadOnlyFileSystemException`（映射 Linux 内核 `EROFS: errno 30`）。
- **契约测试覆盖**：
  * `SharedDirectoryContractTest.rejectsWritableMountOptionsPerAdr0010()`：断言试图配置可写选项被安全拒绝；
  * `SharedDirectoryContractTest.readOnlyFileSystemSemanticsVerification()`：断言对 Guest 视图的任何增删改操作均触发 `ReadOnlyFileSystemException`；
  * `QemuLaunchSpecTest.testLaunchSpecWithSharedDirectoryPci()`：断言命令行包含 `readonly=on`，严禁出现 `readonly=off`。

### 3.3 AC-3（暴露面白名单正式修订记录）
- **修订依据与流程**：
  * 依据 ADR-0010 §3（“暴露面白名单制，初始清单固定：①桥接六命令 + per-VM token（ADR-0009）②只读共享 Downloads ③端口转发默认 127.0.0.1（ADR-0008）……加面 = 显式产品决策 + 本 ADR 修订”）；
  * 本次落地清单第 ② 项“只读共享 Downloads”，在 `docs/adr/0010-security-boundary.md` 增补第 5 条修订案（Amendment 5）：
    1. **严格限定暴露面范围**：仅允许共享经用户显式授权的 Downloads 目录，严禁暴露宿主根文件系统、应用私有数据目录（`/data/data/...`）或外置存储根目录；
    2. **物理只读锁定**：数据面必须配置 `readonly=on` 与 `ro` 挂载参数，写操作物理返回 `EROFS`；
    3. **物理通道解耦**：通过 virtio-9p 独立传输，严禁与 Bridge 控制面共用套接字或通道；
    4. **防御性路径校验**：禁止包含任何 Shell 元字符（`;`、`&`、`|`、`` ` ``、`$`、换行符）与相对路径。

### 3.4 AC-4（全量契约测试 100% 全绿）
在 `DroidLayer_dev_t30` 执行全量模块检查与测试：
- 执行命令：`./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:capability:check :runtime:distro:check :runtime:instance:check`
- 测试结果：
```text
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:check
> Task :runtime:engine-qemu:embeddedProvisionInputTest
EMBEDDED_PROVISION_INPUT_TEST_OK deterministic=1 gzip=1 newc_entries=3 trailer=1 parent_dirs=2 token_validation=2
> Task :runtime:engine-qemu:qemuBridgeAdapterTest
> Task :runtime:engine-qemu:qemuLaunchSpecTest
DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1
ENGINE_CLI_FORMATTERS_TEST_OK
DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1
MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device
SHARED_DIRECTORY_PCI_SPEC_OK
SHARED_DIRECTORY_MMIO_SPEC_OK
ENGINE_FSDEV_FORMATTERS_TEST_OK
> Task :runtime:engine-qemu:runtimeArtifactManifestSchemaTest
> Task :runtime:engine-qemu:runtimeArtifactManifestTest
> Task :runtime:engine-qemu:runtimeAssetInstallerTest
RUNTIME_ASSET_INSTALLER_TEST_OK positive=1 tamper_rejected=1 absent_rejected=1
> Task :runtime:engine-qemu:check
> Task :runtime:capability:deviceCapabilityEvaluatorTest
> Task :runtime:capability:check
> Task :runtime:distro:distroManifestContractTest
> Task :runtime:distro:officialAlpineCatalogTest
> Task :runtime:distro:check
> Task :runtime:instance:normalInstanceLifecycleContractTest
> Task :runtime:instance:reconciliationAndRecoveryLifecycleContractTest
> Task :runtime:instance:check

BUILD SUCCESSFUL in 12s
33 actionable tasks: 19 executed, 14 up-to-date
```
- Linux/WSL 真实环境执行 `test-initramfs-init-fallback.sh` 与 `bash -n`：
```text
$ bash -n guest-assets/initramfs/init -> RC=0
$ test-initramfs-init-fallback.sh -> ALPINE_BUSYBOX_INIT_FALLBACK_OK (RC=0)
```
- 编译参数 `-Xlint:all -Werror` 全量启用，**0 警告、0 错误、100% 测试通过，无任何回归缺陷**。

---

## 4. TDD 节奏与实施历程（TDD Audit Trail）

```
[Slice 1: 文件共享契约与只读约束 (runtime/contract/)]
  ├── RED: 编写 SharedDirectoryContractTest，涵盖基线参数、只读约束、宿主路径合法性、Shell 元字符拦截、
  │        Guest 挂载命令格式化及 ReadOnlyView 读/写语义断言
  │        (编译失败，24 个未定义符号错误)
  ├── GREEN: 实现 MountOptions.java（强制 readOnly=true）与 SharedDirectoryContract.java
  │          (:runtime:contract:check 100% PASS，SHARED_DIRECTORY_CONTRACT_TEST_OK)
  └── REFACTOR: 完善 Windows / POSIX 混合路径兼容逻辑

[Slice 2: QEMU 启动规范 9p 命令行装配 (runtime/engine-qemu/)]
  ├── RED: 在 QemuLaunchSpecTest 中追加 PCI 9p 挂载、MMIO 9p 挂载与底层 formatters 测试
  │        (编译失败，5 个未定义方法/重载符号错误)
  ├── GREEN: 扩展 QemuLaunchSpec.java：增加 formatFsdevArg、formatFsDeviceArg 与支持 SharedDirectorySpec 的重载方法
  │          (:runtime:engine-qemu:check 100% PASS，PCI/MMIO/Formatters 均 OK，且无共享目录时保持零 9p 泄漏不变式)
  └── REFACTOR: 统一使用动态参数列表并保持参数安全性

[Slice 3: Guest 自动挂载支持 (guest-assets/)]
  ├── IMPLEMENT: 在 guest-assets/initramfs/init 中为 restart、provisioning 和 fallback launcher 增补
  │              /mnt/downloads 与 /media/downloads 目录准备、fstab 条目生成及 9p ro 挂载尝试
  └── VERIFY: bash -n 语法检查通过，WSL test-initramfs-init-fallback.sh 实测 PASS

[Slice 4: 暴露面白名单修订与文档闭环]
  ├── AMEND: 在 docs/adr/0010-security-boundary.md 登记 Amendment 5
  └── REPORT: 产出本实施报告
```

---

## 5. 变更文件清单（Deliverables & Modified Files）

1. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/MountOptions.java`
   - 新增：定义策略中立的挂载选项，强制执行只读物理边界（拒绝可写配置），支持安全模型与 9p 版本配置。
2. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/SharedDirectoryContract.java`
   - 新增：定义文件共享契约、`SharedDirectorySpec`、设备模型枚举（PCI/MMIO）、Shell 元字符与路径校验、Guest 挂载命令/fstab 生成器，以及用于 AC-1/AC-2 语义验证的 `ReadOnlyView` 门面。
3. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/SharedDirectoryContractTest.java`
   - 新增：编写文件共享全量契约测试，验证基线参数、写入物理拒绝、路径校验、Shell 注入拦截及读写语义。
4. `runtime/contract/build.gradle.kts`
   - 变更：注册 `sharedDirectoryContractTest` 任务并挂载至 `check` 依赖图。
5. `runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpec.java`
   - 变更：扩展 `QemuLaunchSpec`，提供支持 `SharedDirectorySpec` 的重载入口；实现 `formatFsdevArg` 与 `formatFsDeviceArg`；保持无共享配置时不带 9p 参数的既有基线。
6. `runtime/engine-qemu/src/test/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuLaunchSpecTest.java`
   - 变更：新增 `testLaunchSpecWithSharedDirectoryPci`、`testLaunchSpecWithSharedDirectoryMmio` 与 `testEngineFsdevFormattersDirectly` 测试用例。
7. `guest-assets/initramfs/init`
   - 变更：在 restart 阶段、provisioning 阶段及 Alpine fallback launcher 中增补挂载点创建与 9p 只读挂载条目。
8. `docs/adr/0010-security-boundary.md`
   - 变更：增补 Amendment 5，锁定只读共享 Downloads 目录的白名单落地与安全参数。
9. `docs/reports/tickets/30/implementation-report.zh-CN.md`
   - 新增：本实施报告。

---

## 6. 移交结论与后续指引

Ticket 30（只读共享 Downloads 目录与 9p 数据面通道）的所有开发工作、TDD 驱动验证、红线边界自查与交付报告已全部圆满完成：

- **分支状态**：`ticket/30-readonly-downloads-share`（全部文件就绪，编译与测试 100% PASS）；
- **向 Orchestrator 移交声明**：Implementation Worker 已完成全部实现与自测闭环，全量代码与测试全绿，无回归问题。现正式提请独立审计会话（Independent Review Session）执行 Standards 与 Spec 双轴复审，不代用户签字。
