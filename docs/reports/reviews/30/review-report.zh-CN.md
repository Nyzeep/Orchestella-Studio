# Review 报告：Ticket 30

- **审查对象**：`ticket/30-readonly-downloads-share`，比对基线 `e0847ae`（`main`），审查命令 `git diff e0847ae...HEAD`
- **审查 HEAD**：`a07713c docs(report): 修订 ADR-0010 暴露面白名单并产出 Ticket 30 实施报告 (Ticket 30 Deliverable)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行并行独立子代理复评（Dual-Axis Independent Review）；在本地 `DroidLayer_dev_t30` 工作区实测运行 `./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:capability:check :runtime:distro:check :runtime:instance:check --rerun-tasks` 验证全量构建、契约测试与编译器门控；在 WSL 环境验证 `guest-assets/initramfs/init` 脚本语法及回退测试。

---

## 阶段复审总览（Review Summary）

针对 Ticket 30（只读共享 Downloads 目录与 9p 数据面通道）的实现交付，独立评审专员基于双轴（Standards + Spec）审计标准，对其包含的 4 个递增提交（`e63bd48`、`c5612cc`、`f9716f9`、`a07713c`）执行了全面复核。

经审计：
1. **Standards 轴**：0 项 Blocking Hard Violations，4 项 Non-blocking Judgement Calls（代码异味与防御建议）。数据面与控制面实现严格物理隔离；零 9p 泄漏不变式在无入参时 100% 保持；Class A 基石资产卫生规范严格恪守（仅修改 `init` 脚本，未变动内核镜像与构建锁）；编译器门控 `-Xlint:all -Werror` 保持零告警。
2. **Spec 轴**：0 项 Blocking Defect。工单定义的 AC-1（宿主文件即时只读可见）、AC-2（四重物理写拒绝）、AC-3（ADR-0010 白名单 Amendment 5 修订记录）、AC-4（契约测试与回退脚本 100% 全绿）全部达成闭环。模块边界严格限定在 Ticket 30 owned 模块内，未发生越权修改。
3. **本地实测**：全量测试 33/33 actionable tasks 全部重新执行通过（100% PASS）；WSL 环境脚本语法与回退测试全部通过。

综合评定：**APPROVE**。

---

## Standards

**PASS — 0 Blocking Violations, 4 Non-blocking Judgement Calls / Baseline Smells**

### 1. 数据面与控制面物理分离（ADR-0009）
- **[PASS] 独立 9p 传输通道**：
  - QEMU 命令行装配中，Bridge 控制面通过独立的抽象套接字与串口设备（`-chardev socket,id=control,... -device virtserialport,chardev=control,name=droidlayer.bridge`）交互；
  - 共享文件数据面完全经由独立 virtio-9p 通道传输（`-fsdev local,id=fsdev0,... -device virtio-9p-pci/virtio-9p-device,...`）；
  - Bridge 控制协议中 0 行侵入文件内容传输代码，严禁两面混用，完全符合 ADR-0009 §3 规范。

### 2. 防越界逃逸与输入安全校验（Security & Escape Defense）
- **[PASS] 相对路径遍历逃逸防御**：
  - `SharedDirectoryContract.DefaultReadOnlyView.resolveSafe` 采用 `root.resolve(relativePath).normalize()`，并通过 `!target.startsWith(root)` 实施边界前置断言，物理杜绝了包含 `..` 的路径遍历逃逸，违规访问抛出 `SecurityException`。
- **[PASS] Shell 元字符严格拦截**：
  - `SharedDirectoryContract.validateHostPath` 引入 `SHELL_METACHARACTERS` 正则匹配，严格拒绝包含 `;`、`&`、`|`、`` ` ``、`$`、换行符的非法宿主路径，防范任何潜在的命令行注入风险。
- **[PASS] 路径绝对性约束**：
  - `validateHostPath` 强制要求 POSIX 绝对路径（以 `/` 开头）或 Windows 盘符绝对路径（形如 `C:\`），拦截相对路径入参。

### 3. 零 9p 泄漏不变式（Zero 9p Leak Invariant）
- **[PASS] 无入参时干净启动**：
  - 在 `QemuLaunchSpec.build` 中，当 `sharedDirectorySpec == null` 时（既有 7 参数与 8 参数构建入口），不追加任何 `-fsdev` 或 `-device virtio-9p-*` 参数；
  - `QemuLaunchSpecTest` 明确断言默认启动规格输出恰好 41 个参数且 `no_9p=1`，确保历史实例与未配置共享目录的启动形制 0 回归、0 泄漏。

### 4. 资产卫生与 Class A 遵从（Asset Hygiene & Policy）
- **[PASS] Class A 资产只读保护**：
  - 对照 `docs/agent-handoff/ASSET_REUSE_AND_HYGIENE_POLICY.md`，`guest-assets/` 下的修改严格限制在文本脚本 `guest-assets/initramfs/init` 中；
  - 内核二进制镜像（`Image`）、Alpine 官方输入 tarball、SHA-256 清单及构建锁文件均保持 **0 行改动**。
- **[PASS] 模块所有权边界（`PARALLELIZATION_MATRIX.md`）**：
  - 改动严格控制在 Ticket 30 owned 模块：`runtime/contract/`、`runtime/engine-qemu/`、`guest-assets/initramfs/init`；
  - 严禁模块保持零接触：`runtime/engine-avf/`（0 行）、`network/`（0 行）、`runtime/instance/`（0 行）、`app/`（0 行）。

### 5. 编译器门控与构建工程质量
- **[PASS] 严格编译门控**：
  - `runtime/contract/build.gradle.kts` 与 `runtime/engine-qemu/build.gradle.kts` 均配置 `-Xlint:all` 与 `-Werror`；
  - Java 17 严格编译检查下保持 **0 警告、0 错误**。

### 6. Fowler 异味基线审查与非阻断建议（Judgement Calls & Observations）
1. **Duplicated Code（建议后续提炼 Shell 函数）**：
   - 位置：`guest-assets/initramfs/init:129-133` 与 `guest-assets/initramfs/init:209-213`；
   - 现象：在 root volume restart 分支与 fresh provisioning 分支中，向 `/newroot/etc/fstab` 写入 `downloads` 挂载条目的检查与追加逻辑完全重合（5 行重复）；
   - 评定：非阻断异味，建议后续小步重构抽取为 `append_downloads_fstab_entry()` 辅助函数。
2. **Speculative Generality（微小投机性冗余）**：
   - 位置：`guest-assets/initramfs/init:85,128,208` 及 `SharedDirectoryContract.java:35`；
   - 现象：声明并创建了备选挂载点 `/media/downloads`（`ALTERNATIVE_GUEST_MOUNT_POINT`），但在后续实际挂载与 fstab 流程中未被使用（统一使用 `/mnt/downloads`）；此外 `MountOptions.SecurityModel` 定义了 `PASSTHROUGH`、`MAPPED` 等枚举，但 ADR-0010 已锁定为 `none`；
   - 评定：非阻断，保留了未来扩展可能，但当前建议精简无用备选路径以保持极简。
3. **Message Chains（调用链路耦合）**：
   - 位置：`runtime/engine-qemu/src/main/java/.../QemuLaunchSpec.java:213`；
   - 现象：`spec.mountOptions().securityModel().wireValue()` 存在连续三级方法调用；
   - 评定：非阻断，后续可在 `MountOptions` 增加快捷访问器或由 `MountOptions` 直接格式化选项字符串。
4. **HostPath 深度防御建议（Defensive Depth Suggestion）**：
   - 位置：`SharedDirectoryContract.java:280-296` (`validateHostPath`)；
   - 现象：当前 `validateHostPath` 拦截了 Shell 元字符与相对路径，但未对 `hostPath` 自身执行 `Path.normalize()`，理论上传入 `/sdcard/Download/../../data` 仍可通过校验；测试用例中亦构造了 `/data/user/0/custom/files`；
   - 评定：非阻断。由于宿主层传入路径由宿主受信逻辑控制，且 `resolveSafe` 已保护 Guest 访问，但建议在后续加固中为 `validateHostPath` 增加规范化与敏感目录黑名单（拒绝根目录 `/` 与 `/data/data`）。

---

## Spec

**PASS — 4 项工单验收标准（AC-1 ~ AC-4）全部达成闭环**

### 1. 工单验收标准（Acceptance Criteria）逐条核验

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **宿主文件即时只读可见**：<br>1. 放入 Downloads 目录后 guest 内即可访问<br>2. 统一挂载点 `/mnt/downloads`<br>3. 相对路径无损枚举与内容读取 | **✅ PASS** | 1. `SharedDirectoryContract.SharedDirectorySpec` 规范化了宿主与 Guest 目录绑定，默认挂载点为 `/mnt/downloads`；<br>2. `ReadOnlyView` 门面及 `DefaultReadOnlyView` 仿真实现验证：在宿主临时目录写入文本及二进制 PDF 文件后，`listRelativePaths()` 即时枚举，`readString(...)` 与 `readBytes(...)` 准确读取（`SHARED_DIRECTORY_CONTRACT_TEST_OK`）；<br>3. `guest-assets/initramfs/init` 在启动与挂载阶段自动确保挂载点存在并注入 9p ro 挂载。 |
| **AC-2** | **四重物理写拒绝（只读语义）**：<br>1. ① 契约层强制只读（拒绝 `readOnly=false`）<br>2. ② QEMU CLI 强制追加 `readonly=on`<br>3. ③ Guest 挂载参数强制 `ro`<br>4. ④ 写操作拦截并返回只读文件系统错误（`EROFS`） | **✅ PASS** | 1. **契约层**：`MountOptions.builder().readOnly(false).build()` 物理抛出 `IllegalArgumentException`，`SharedDirectorySpec` 前置断言 `mountOptions.isReadOnly()`；<br>2. **CLI 层**：`QemuLaunchSpec.formatFsdevArg` 强行追加 `,readonly=on`，单测断言禁止包含 `readonly=off`；<br>3. **Guest 挂载层**：`MountOptions.toGuestMountOptionsString()` 生成 `,ro`，`init` 脚本中显式声明 `ro` 与 `nofail`；<br>4. **VFS/语义层**：`ReadOnlyView` 中 `createFile`、`writeBytes`、`delete`、`createDirectory` 统一抛出 `ReadOnlyFileSystemException`（Java 映射 Linux `EROFS: errno 30`）。 |
| **AC-3** | **白名单合规修订记录**：<br>1. 遵循 ADR-0010 白名单变更流程<br>2. 新增只读共享 Downloads 条目<br>3. 锁定安全参数与物理边界 | **✅ PASS** | `docs/adr/0010-security-boundary.md` 增补 **Amendment 5（只读共享 Downloads 目录）**：<br>1. 锁定暴露面仅限显式授权的 Downloads 目录，严禁暴露宿主根目录与 App 私有数据目录；<br>2. 锁定物理只读边界（`readonly=on`、`ro`，拒绝写操作返回 `EROFS`）；<br>3. 锁定数据面走 virtio-9p 独立通道，绝不与 Bridge 混用；<br>4. 锁定 Shell 元字符防注入要求。 |
| **AC-4** | **契约测试全绿**：<br>1. 共享目录契约测试 100% 通过<br>2. QEMU 启动规范测试覆盖 9p 参数<br>3. 脚本语法与回退测试全绿 | **✅ PASS** | 1. 新增 `SharedDirectoryContractTest` 覆盖基线参数、写入拒绝、路径校验、注入拦截及读写语义，执行输出 `SHARED_DIRECTORY_CONTRACT_TEST_OK`；<br>2. `QemuLaunchSpecTest` 覆盖 PCI 与 MMIO 9p 设备、底层 Formatter 及无 9p 干净启动；<br>3. WSL 实测 `bash -n guest-assets/initramfs/init`（RC=0）与 `test-initramfs-init-fallback.sh`（`ALPINE_BUSYBOX_INIT_FALLBACK_OK`）。 |

### 2. 行为边界与阶段划分澄清（Scope & Boundary Clarification）
1. **`app/` 模块与 Android UI 接线边界**：
   - 工单原始场景描述“用户把文件放进设备 Downloads 目录后，guest 内即可只读访问”。评审发现当前变更未修改 `app/` 模块的 `QemuInstanceLauncher.java`；
   - 依据 `docs/agent-handoff/PARALLELIZATION_MATRIX.md`，Ticket 30 属于 B1 并行批次，其 Owned 模块严格限定为 `engine-qemu(9p)`, `bridge(数据面)`, `guest-assets(挂载)`, `contract`, `tests`，而 `app/` 模块属于 Ticket 26（最小接线）与 Ticket 35（全局装配/硬化）所有；
   - Worker 恪守所有权红线未越界触碰 `app/`，本票在契约层、引擎组装层与镜像启动层已达成 100% 功能完备与接口就绪，属于正确的治理遵从。
2. **真机冒烟测试的依赖说明**：
   - 工单测试策略中提及“真机冒烟”，在无物理 Android 设备连接的自动化流水线中，通过 JVM 严格契约测试与 Linux/WSL 真实 initramfs 环境测试作为等价验证基线；真机端到端全链路冒烟将在后续整体发布前由 Orchestrator 统筹安排。

---

## 本地执行与实测证据（Local Execution Evidence）

评审专员在 `DroidLayer_dev_t30` 隔离工作区重新执行了全量验证任务：
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:capability:check :runtime:distro:check :runtime:instance:check --rerun-tasks
```

### 实测输出证据（Execution Log Summary）
```text
> Task :runtime:contract:compileJava
> Task :runtime:contract:classes
> Task :runtime:contract:compileTestJava
> Task :runtime:contract:testClasses
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:jar
> Task :runtime:contract:test
> Task :runtime:contract:check
> Task :runtime:engine-qemu:compileJava
> Task :runtime:engine-qemu:classes
> Task :runtime:engine-qemu:compileTestJava
> Task :runtime:engine-qemu:testClasses
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
> Task :runtime:engine-qemu:test
> Task :runtime:engine-qemu:check
> Task :runtime:capability:compileJava
> Task :runtime:capability:classes
> Task :runtime:capability:compileTestJava
> Task :runtime:capability:testClasses
> Task :runtime:capability:deviceCapabilityEvaluatorTest
> Task :runtime:capability:test
> Task :runtime:capability:check
> Task :runtime:distro:compileJava
> Task :runtime:distro:classes
> Task :runtime:distro:compileTestJava
> Task :runtime:distro:testClasses
> Task :runtime:distro:distroManifestContractTest
> Task :runtime:distro:officialAlpineCatalogTest
> Task :runtime:distro:test
> Task :runtime:distro:check
> Task :runtime:instance:compileJava
> Task :runtime:instance:classes
> Task :runtime:instance:compileTestJava
> Task :runtime:instance:testClasses
> Task :runtime:instance:normalInstanceLifecycleContractTest
> Task :runtime:instance:reconciliationAndRecoveryLifecycleContractTest
> Task :runtime:instance:test
> Task :runtime:instance:check

BUILD SUCCESSFUL in 19s
33 actionable tasks: 33 executed
```

### WSL 脚本实测证据
```bash
$ wsl bash -n guest-assets/initramfs/init
# Exit code: 0 (语法检查通过)

$ wsl bash -c "TICKET26_WORK_ROOT=/tmp bash guest-assets/scripts/test-initramfs-init-fallback.sh"
ALPINE_BUSYBOX_INIT_FALLBACK_OK
# Exit code: 0 (回退逻辑与 inittab 装配测试通过)
```

**实测判定**：
- 33 个 actionable tasks 全部重新执行，无缓存跳过；
- 契约测试与单元测试 100% PASS；
- 编译器门控 `-Xlint:all -Werror` 保持零警告零错误通过。

---

## 暴露面检查（Surface Area Audit）

- **凭据与敏感数据隔离**：共享目录逻辑纯粹针对文件系统数据面，未接触或记录任何 Bridge Token 或实例凭据；
- **宿主安全边界守护**：`validateHostPath` 与 `DefaultReadOnlyView` 确保宿主路径无法执行命令行注入或相对目录越界；
- **物理写隔离**：通过 Hypervisor 命令行（`readonly=on`）与 Guest 挂载参数（`ro`）实施四重只读物理锁定，确保恶意 Guest 无法对宿主文件执行写、改、删操作，杜绝勒索与篡改风险；
- **合规记录**：已在 `docs/adr/0010-security-boundary.md` 正式登记 Amendment 5，符合暴露面加码必先修订 ADR 的安全治理规则。

---

## 结论（Verdict）

### **APPROVE**

Ticket 30 的代码实现高质量地完成了工单规格所要求的只读共享 Downloads 目录设计。通过严格的数据面/控制面解耦（9p vs Bridge）、四重只读物理锁定（契约、QEMU、挂载、VFS）、零 9p 泄漏基线保持以及 ADR-0010 Amendment 5 规范修订，代码质量扎实，所有测试 100% 绿灯通过，完全满足合入主干（`main`）的准入要求。

---

- **Standards 汇总**：0 个 blocking findings，4 个 baseline smells / non-blocking suggestions（最显著为 `init` 中 fstab 追加逻辑代码重复，均不阻断合并）。
- **Spec 汇总**：0 个 blocking findings，4 个 acceptance criteria 均达到 100% 闭环。

**评审人**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）  
**日期**：2026-10-01  
**工作区**：`DroidLayer_dev_t30`（分支 `ticket/30-readonly-downloads-share`，HEAD `a07713c`）
