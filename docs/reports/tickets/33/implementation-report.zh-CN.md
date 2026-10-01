# Ticket 33 实施报告（Implementation Report）

- **工单编号**：Ticket 33
- **工单标题**：实例导出备份与磁盘扩容（Instance Export Backup and Disk Volume Expansion）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **工作区与特性分支**：`DroidLayer_dev_t33`，分支 `ticket/33-export-and-resize`（基于 `main`，Commit `73a4b70`）
- **看板跟踪项**：`.scratch/droidlayer-platform/issues/33-impl-export-and-resize.md`
- **当前状态**：**Implementation Completed · Ready for Independent Review**

---

## 1. 任务背景与核心目标

依据 ADR-0007（存储模型：raw 稀疏单根盘 + SAF 导出备份），DroidLayer 实例持久层采用单根盘架构（`root-volume.raw` + `instance-metadata.json`），数据完全保存在 App 私有存储目录下。
Ticket 33 负责落实该架构中的两大核心存储生命周期操作：
1. **实例单向导出备份（SAF 导出）**：
   - 用户可通过 Android 系统存储访问框架（Storage Access Framework, SAF），将处于 `STOPPED` 等非运行态的实例整盘数据（根盘 `root-volume.raw` + `instance-metadata.json`）单向导出至用户选定的外部存储位置；
   - 伴随导出生成标准 `SHA256SUMS` 校验和清单，支持目录结构导出（SAF Tree URI）与单流打包归档导出（SAF Document URI）；
   - 在契约层与管理器层提供无损校验方法，确保导出产物完整性与防篡改；
   - 明确遵循单向导出设计，导入恢复作为后续演进，本工单不开放未经验证的导入接口。
2. **磁盘稀疏扩容预检与在线扩展**：
   - 宿主侧使用 `RandomAccessFile.setLength` 扩展 raw 镜像逻辑尺寸，保持文件稀疏特性；
   - 坚决阻断任何缩容尝试（`newSizeBytes <= currentSizeBytes` 抛出 `IllegalArgumentException`）；
   - 扩容前与新实例创建前强制执行存储空间预检（`StoragePreflight`），比对宿主存储可用空间与扩容增量，若空间不足立即阻断并抛出 `InsufficientStorageException`，防止稀疏文件后续写入时触发内核 `ENOSPC` 导致系统崩溃；
   - Guest 侧在 `guest-assets/initramfs/init` 的启动阶段与 fallback 启动路径中集成自动 `resize2fs` 检测与在线文件系统扩容；
   - 在 `runtime:engine-qemu` 中通过 QMP `block_resize` 原语建立块设备扩容指令通道。

---

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 在独立隔离工作树开展工作 | 创建独立 Git Worktree `DroidLayer_dev_t33` 检出特性分支 `ticket/33-export-and-resize`（基线 `main` Commit `73a4b70`） | **100% 隔离** |
| **Owned: Contract 模块** | `runtime/contract/`（`StorageContract`） | 纯 Java 17 定义策略中立存储契约、空间预检器、校验清单与元数据模型，0 Android 依赖 | **完全受控** |
| **Owned: Instance 模块** | `runtime/instance/`（`InstanceStorageManager`, `InstanceLifecycle`） | 实现磁盘扩容、目录/ZIP 归档导出、状态互斥拦截、不可变时间戳更新，0 UI 依赖 | **完全受控** |
| **Owned: Engine QEMU 模块** | `runtime/engine-qemu/`（`QemuHostfwdFormatter`, `QemuQmpHostfwdClient`） | 实现 QMP `block_resize` 命令格式化与客户端发送调用，实现 `AutoCloseable` | **完全受控** |
| **Owned: Guest Assets** | `guest-assets/initramfs/init` | 在 rootfs 挂载后及启动脚本中集成 `resize2fs` 扩容机制 | **完全受控** |
| **Owned: Android App 适配器** | `app/`（`AndroidSafExportAdapter`, `AlpineInstanceCreator`） | 适配 SAF Document URI 与 Tree URI，集成存储空间预检门控 | **完全受控** |
| **Forbidden: AVF 引擎** | 严禁改动 `runtime/engine-avf/` | 0 行改动 `runtime/engine-avf/` | **零冲突** |
| **Forbidden: 桥接核心** | 严禁改动已交付的 Ticket 26/28/31 通道协议 | 未破坏桥接核心，磁盘扩容走 init 脚本与 QMP，完全契合设计意图 | **零冲突** |
| **编译与代码质量约束** | `-Xlint:all -Werror` 零告警、零隐式转换 | 全部模块编译与测试通过，零 javac 告警，资源妥善释放 | **100% 合规** |

---

## 3. 验收标准逐项落地与实测证据（Acceptance Criteria Evidence）

### 3.1 AC-1（导出完整性：根盘与元数据、校验和一致、文件格式合理）
- **设计与实现**：
  * 在 `StorageContract` 中定义了标准文件名常量：`root-volume.raw`、`instance-metadata.json`、`SHA256SUMS`；
  * `InstanceExportMetadata` 记录实例 ID、发行版 ID/版本、运行类别、状态、根盘尺寸、根盘 SHA-256、创建时间、更新时间及导出时间戳，并具备紧凑 JSON 序列化与严格反序列化；
  * `ChecksumManifest` 支持生成标准 Linux `SHA256SUMS` 文本（`<hash>  <filename>\n`），并支持从文本逆向解析与比对；
  * `InstanceStorageManager.exportToDirectory` 将实例导出为标准目录结构，先流式写入 `root-volume.raw` 并实时累加 SHA-256 哈希，再计算写入元数据 JSON，最后生成并写入 `SHA256SUMS` 清单；
  * `InstanceStorageManager.exportToArchive` 支持将三者直接流式打包至 `ZipOutputStream`，无需宿主磁盘中间转落；
  * `InstanceStorageManager.verifyExportDirectory` 与 `verifyExportArchive` 提供端到端离线校验工具，全量逐字节核验校验和。
- **契约与单测覆盖**：
  * `StorageContractTest.formatsAndParsesChecksumManifest`：验证多文件哈希清单生成与反向解析；
  * `StorageContractTest.serializesAndDeserializesInstanceExportMetadata`：验证导出元数据 JSON 往返与合法性；
  * `InstanceStorageContractTest.exportsInstanceToDirectoryWithManifestAndVerifiesSuccessfully`：验证目录导出完整性与自动验签通过；
  * `InstanceStorageContractTest.exportsInstanceToZipArchiveAndVerifiesSuccessfully`：验证 ZIP 归档导出单流顺序生成与验签通过；
  * `InstanceStorageContractTest.rejectsExportingRunningInstance`：验证非停止态导出拦截（fail-closed）。

### 3.2 AC-2（SAF 路径验证：导出到用户选定的任意位置/URI 成功）
- **设计与实现**：
  * 引入 `AndroidSafExportAdapter` 适配 Android Storage Access Framework（SAF）；
  * 设计轻量解耦 Seam 接口：`DocumentStreamOpener`（针对单文档 URI `content://...`）与 `TreeDirectoryWriter`（针对目录树 URI）；
  * 在生产环境中自动对接 `context.getContentResolver().openOutputStream(...)` 与 `DocumentsContract.createDocument(...)`；
  * 支持通过 SAF 导出为单一归档文件（`.zip`）或在指定目录下建立完整三文件备份；
  * 真实处理文件流生命周期，全流程使用 try-with-resources 安全关闭句柄。
- **契约与单测覆盖**：
  * `AndroidSafExportAdapterTest.exportsArchiveToSafDocumentUriSuccessfully`：在单测环境驱动 DocumentStreamOpener 注入流，成功生成归档并通过 ZipInputStream 解析验证；
  * `AndroidSafExportAdapterTest.exportsIndividualFilesToSafTreeDirectoryUriSuccessfully`：驱动 TreeDirectoryWriter 分别创建三文件，验证数据内容及哈希一致性；
  * `AndroidSafExportAdapterTest.rejectsExportWhenInstanceIsRunning`：验证 Android 适配层拦截 RUNNING 实例导出。

### 3.3 AC-3（扩容生效：操作后反映新容量且既有数据完好）
- **设计与实现**：
  * `InstanceStorageManager.resizeRootVolume`：
    1. 校验当前实例状态：仅允许 `STOPPED` 或 `ERROR` 状态扩容，`RUNNING` 或 `PROVISIONING` 状态阻断；
    2. 校验目标容量：`DiskResizeSpec.validate(currentSize, newSize)` 坚决禁止缩容或等容；
    3. 校验宿主存储空间：`StoragePreflight.checkResizeSpace` 预检增量空间是否充裕；
    4. 执行文件扩容：使用 `RandomAccessFile.setLength(newSizeBytes)` 原地扩展稀疏文件，保持前序数据块完全无损；
    5. 更新元数据：通过 `instance.withUpdatedAt(clock)` 生成带有最新更新时间戳的不可变新实例记录。
  * `guest-assets/initramfs/init`：
    - 在 rootfs 挂载之后，检测存在 `/sbin/resize2fs`、`/newroot/sbin/resize2fs` 或 `/newroot/usr/sbin/resize2fs` 时，自动调用 `resize2fs "$ROOT_DEVICE"` 在线扩展 ext4 文件系统；
    - 静默失败回退（`2>/dev/null || true`），保证兼容不具备 resize2fs 工具的极端精简环境。
  * `runtime:engine-qemu`：
    - `QemuHostfwdFormatter.formatQmpBlockResize` 生成 QMP `{"execute":"block_resize","arguments":{"device":"root","size":<bytes>}}`；
    - `QemuQmpHostfwdClient.resizeBlockDevice` 支持在虚拟机运行态向 QEMU 发送块设备扩容指令。
- **契约与单测覆盖**：
  * `StorageContractTest.enforcesDiskResizeSpecConstraints`：验证新尺寸必须大于原尺寸、正数及下限约束；
  * `InstanceStorageContractTest.resizesRootVolumeWhilePreservingExistingData`：测试在现有 512KB 根盘写入特殊 magic 字节，扩容至 2MB，验证文件尺寸增大且原有偏移内容完整保持；
  * `InstanceStorageContractTest.rejectsDownsizingRootVolume`：验证拒绝缩小容量；
  * `QemuHostfwdContractTest.formatsBlockResizeAndExecutesQmpCommand`：验证 QMP block_resize 命令生成与 socket 发送。

### 3.4 AC-4（ENOSPC 空间预检：宿主存储空间不足时创建/扩容有明确预检与阻断错误反馈）
- **设计与实现**：
  * 定义策略中立接口 `StorageContract.StorageSpaceChecker`，接收检查路径与所需字节数，返回布尔值或抛出详细空间不足信息；
  * 实现 `StoragePreflight.checkResizeSpace` 与 `StoragePreflight.checkCreationSpace`；
  * 当宿主可用存储空间小于扩容增量（`delta = target - current`）或创建配额时，立即抛出 `InsufficientStorageException`，明确携带 `requiredBytes` 与 `availableBytes` 详情；
  * 在 `AlpineInstanceCreator.create` 前置插入 `StoragePreflight.checkCreationSpace` 校验，拦截 2GB 根盘超卖；
  * 在 `InstanceStorageManager.resizeRootVolume` 前置插入 `StoragePreflight.checkResizeSpace` 校验，磁盘文件物理尺寸在空间不足时保持完全不变。
- **契约与单测覆盖**：
  * `StorageContractTest.blocksResizeWhenHostStorageSpaceIsInsufficient`：单元测试预检空间不足抛出异常并断言字节数；
  * `StorageContractTest.allowsResizeWhenHostStorageSpaceIsSufficient`：验证空间充足时顺利放行；
  * `InstanceStorageContractTest.blocksResizeWhenHostStorageSpaceIsInsufficientWithoutModifyingVolume`：在真实文件系统上模拟可用空间不足，验证扩容前拦截且源文件大小保持不变；
  * `AlpineInstanceCreatorTest.rejectsCreationWhenStorageSpaceIsInsufficient`：验证创建实例时空间不足抛出 `InsufficientStorageException` 并不在 store 中残留垃圾实例。

---

## 4. TDD 节奏与实施历程（TDD Audit Trail）

```
[Slice 1: Contract 模块定义与预检契约]
  ├── RED: 编写 StorageContractTest.java（包含尺寸约束、缩容拒绝、ENOSPC 空间预检阻断与放行、
  │        InstanceExportMetadata JSON 往返、ChecksumManifest SHA256SUMS 生成与解析）
  │        (编译失败，缺少 StorageContract 类及相关方法)
  ├── GREEN: 实现 StorageContract.java，包含 StorageSpaceChecker、InsufficientStorageException、
  │          StoragePreflight、DiskResizeSpec、InstanceExportMetadata、ChecksumManifest 与 SHA-256 工具；
  │          在 runtime/contract/build.gradle.kts 挂接 storageContractTest 任务；
  │          (:runtime:contract:check 100% PASS，输出 STORAGE_CONTRACT_TEST_OK)
  └── COMMIT: 2114174 feat(storage): define StorageContract, space preflight, and export manifest (Ticket 33 Slice 1)

[Slice 2: InstanceStorageManager 与不可变更新]
  ├── RED: 编写 InstanceStorageContractTest.java（覆盖目录导出、ZIP 导出、扩容原有数据保持、
  │        ENOSPC 预检阻断尺寸不变、RUNNING 状态拦截、缩容拒绝、文件缺失容错）
  │        (编译失败，缺少 InstanceStorageManager 与生命周期门面)
  ├── GREEN: 实现 InstanceStorageManager.java，提供 resizeRootVolume、exportToDirectory、
  │          exportToArchive 以及端到端校验验证器 verifyExportDirectory / verifyExportArchive；
  │          在 InstanceLifecycle.java 为 Instance 增补不可变更新方法 withUpdatedAt(clock)，
  │          在 Controller 中增补 resizeRootVolume、exportToDirectory、exportToArchive 门面；
  │          在 runtime/instance/build.gradle.kts 挂接 instanceStorageContractTest 任务；
  │          (:runtime:instance:check 100% PASS，输出 INSTANCE_STORAGE_CONTRACT_TEST_OK)
  └── COMMIT: 553c59d feat(storage): implement InstanceStorageManager with resize, preflight, and exports (Ticket 33 Slice 2)

[Slice 3: Guest 在线扩容机制、QMP block_resize 与 Android SAF 适配]
  ├── IMPLEMENT GUEST & ENGINE:
  │   - 在 guest-assets/initramfs/init 增加 resize2fs 自动在线扩容逻辑；
  │   - 在 runtime/engine-qemu 的 QemuHostfwdFormatter 增加 formatQmpBlockResize；
  │   - 在 QemuQmpHostfwdClient 实现 AutoCloseable 并增加 resizeBlockDevice；
  │   - 在 QemuHostfwdContractTest 增加 formatQmpBlockResize 格式校验与发送测试；
  │   - (:runtime:engine-qemu:check 100% PASS，输出 QEMU_HOSTFWD_CONTRACT_TEST_OK)
  ├── IMPLEMENT APP SAF & PREFLIGHT:
  │   - 改造 AlpineInstanceCreator 支持 StorageSpaceChecker，前置执行 StoragePreflight.checkCreationSpace；
  │   - 创建 AndroidSafExportAdapter 支持 SAF Document URI 与 Tree Directory URI 导出；
  │   - 编写 AlpineInstanceCreatorTest 与 AndroidSafExportAdapterTest 单元测试；
  └── COMMIT: 2af6762 feat(storage): integrate guest resize hook, qmp block resize, and android saf adapter (Ticket 33 Slice 3)

[Slice 4: 全量回归、看板更新与工单结项]
  ├── FULL REGRESSION: 执行全模块交付自测命令：
  │   ./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check --rerun-tasks (100% GREEN)
  ├── TRACKER: 更新 .scratch/droidlayer-platform/issues/33-impl-export-and-resize.md 状态为 completed；
  └── REPORT: 归档本实施报告 docs/reports/tickets/33/implementation-report.zh-CN.md。
```

---

## 5. 变更文件清单（Deliverables & Modified Files）

1. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/StorageContract.java`
   - 新增：策略中立存储契约，包含空间预检 Seam、ENOSPC 异常、扩容规则、导出元数据模型、SHA-256 清单及摘要工具。
2. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/StorageContractTest.java`
   - 新增：存储契约测试，全量覆盖预检、拒斥、格式化与哈希计算。
3. `runtime/contract/build.gradle.kts`
   - 变更：注册并挂接 `storageContractTest` 至 `check` 生命周期。
4. `runtime/instance/src/main/java/io/github/nyzeep/droidlayer/runtime/instance/InstanceStorageManager.java`
   - 新增：实例存储管理器，实现原地扩容、目录导出、ZIP 归档导出及完整性自动校验。
5. `runtime/instance/src/main/java/io/github/nyzeep/droidlayer/runtime/instance/InstanceLifecycle.java`
   - 变更：在 `Instance` 数据类中增补 `withUpdatedAt` 不可变更新方法；在 `Controller` 接口增补扩容与导出门面。
6. `runtime/instance/src/test/java/io/github/nyzeep/droidlayer/runtime/instance/InstanceStorageContractTest.java`
   - 新增：存储管理器契约测试，覆盖目录/归档导出、扩容数据保留、ENOSPC 预检阻断、状态互斥等。
7. `runtime/instance/build.gradle.kts`
   - 变更：注册并挂接 `instanceStorageContractTest` 至 `check` 生命周期。
8. `guest-assets/initramfs/init`
   - 变更：在 rootfs 挂载后与 busybox fallback 启动路径中植入 `resize2fs` 自动在线扩容脚本。
9. `runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuHostfwdFormatter.java`
   - 变更：增补 `formatQmpBlockResize` QMP 格式化方法。
10. `runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuQmpHostfwdClient.java`
    - 变更：实现 `AutoCloseable`，增补 `resizeBlockDevice` QMP 调用。
11. `runtime/engine-qemu/src/test/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuHostfwdContractTest.java`
    - 变更：增补 QMP `block_resize` 序列化与交互契约测试。
12. `app/src/main/java/io/github/nyzeep/droidlayer/AlpineInstanceCreator.java`
    - 变更：支持注入 `StorageSpaceChecker`，创建 2GB 根盘前执行空间预检。
13. `app/src/main/java/io/github/nyzeep/droidlayer/AndroidSafExportAdapter.java`
    - 新增：Android SAF 适配器，支持 SAF Document URI 归档导出与 Tree URI 目录导出。
14. `app/src/test/java/io/github/nyzeep/droidlayer/AlpineInstanceCreatorTest.java`
    - 变更：增补空间不足拒绝创建实例的单元测试。
15. `app/src/test/java/io/github/nyzeep/droidlayer/AndroidSafExportAdapterTest.java`
    - 新增：SAF 适配器单元测试，覆盖 Document URI 导出、Tree URI 导出与运行态拦截。
16. `.scratch/droidlayer-platform/issues/33-impl-export-and-resize.md`
    - 变更：勾选所有验收标准，更新状态为 `completed`。
17. `docs/reports/tickets/33/implementation-report.zh-CN.md`
    - 新增：本实施报告。

---

## 6. 自测验证实录（Verification Execution Log）

### 6.1 核心交付命令验证
```powershell
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check --rerun-tasks
```
**输出实录**：
```text
> Task :runtime:contract:compileJava
> Task :runtime:contract:processResources NO-SOURCE
> Task :runtime:contract:classes
> Task :runtime:contract:compileTestJava
> Task :runtime:contract:processTestResources NO-SOURCE
> Task :runtime:contract:testClasses
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest

> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK

> Task :runtime:contract:portForwardingContractTest
PORT_FORWARDING_CONTRACT_TEST_OK

> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK

> Task :runtime:contract:storageContractTest
STORAGE_CONTRACT_TEST_OK

> Task :runtime:contract:jar
> Task :runtime:contract:test
> Task :runtime:contract:check
> Task :runtime:instance:compileJava
> Task :runtime:instance:processResources NO-SOURCE
> Task :runtime:instance:classes
> Task :runtime:instance:compileTestJava
> Task :runtime:instance:processTestResources NO-SOURCE
> Task :runtime:instance:testClasses

> Task :runtime:instance:instanceStorageContractTest
INSTANCE_STORAGE_CONTRACT_TEST_OK

> Task :runtime:instance:normalInstanceLifecycleContractTest
> Task :runtime:instance:reconciliationAndRecoveryLifecycleContractTest
> Task :runtime:instance:test
> Task :runtime:instance:check
> Task :runtime:engine-qemu:compileJava
> Task :runtime:engine-qemu:processResources NO-SOURCE
> Task :runtime:engine-qemu:classes
> Task :runtime:engine-qemu:compileTestJava
> Task :runtime:engine-qemu:processTestResources NO-SOURCE
> Task :runtime:engine-qemu:testClasses

> Task :runtime:engine-qemu:embeddedProvisionInputTest
EMBEDDED_PROVISION_INPUT_TEST_OK deterministic=1 gzip=1 newc_entries=3 trailer=1 parent_dirs=2 token_validation=2

> Task :runtime:engine-qemu:qemuBridgeAdapterTest

> Task :runtime:engine-qemu:qemuHostfwdContractTest
QEMU_HOSTFWD_CONTRACT_TEST_OK

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

> Task :runtime:engine-qemu:test
> Task :runtime:engine-qemu:check

BUILD SUCCESSFUL in 16s
28 actionable tasks: 28 executed
```

---

## 7. 移交结论与后续指引

Ticket 33（实例导出备份与磁盘扩容）的所有契约设计、存储管理器实现、QMP 指令适配、Guest 在线扩容挂钩、Android SAF 适配器、TDD 自动化测试、ENOSPC 阻断防护及交付验证已圆满闭环：

- **代码质量**：纯 JVM 模块编译参数 `-Xlint:all -Werror` 零告警通过，资源句柄全量严格闭合；
- **架构纯洁性**：核心存储契约与管理器 0 引入 Android SDK 依赖，遵循策略中立与 Seam 抽象设计；
- **分支状态**：`ticket/33-export-and-resize`（工作区干净，所有产物全量归档并纳入版本管理）；
- **移交声明**：Implementation Worker 现正式提请独立复审（Reviewer / Matt），按 Standards 与 Spec 双轴核验本次交付。
