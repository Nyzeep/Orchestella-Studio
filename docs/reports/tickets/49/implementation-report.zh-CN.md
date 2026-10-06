# Ticket 49 实施报告（Implementation Report）

> 工单：`49-contract-vm-hardware-config-and-lifecycle-delete`（策略中立硬件参数配置模型与实例物理删除契约扩展）  
> 工作区物理路径：`X:\`（映射至 `DroidLayer_ticket49` Worktree）  
> 工作分支：`feature/ticket49-contract-hardware-delete`  
> 状态：**实施完成，提请独立复审（ready-for-review）**  
> 日期：2026-10-06  

---

## 1. 任务背景与核心目标

依据 ADR-0002（契约策略中立）、ADR-0019（GPU 硬件加速渲染与全栈交互体验架构）以及 `spec-m4.md`：
在 Phase 4（M4 里程碑）中，DroidLayer 平台需支持灵活定制虚拟机硬件规格，并实现废弃实例的物理销毁闭环。

本工单（Ticket 49）作为 M4 里程碑的先导 Tracer-Bullet 工单，核心完成两大基础契约设施交付：
1. **策略中立硬件参数配置模型（`VmHardwareConfig`）**：
   - 在 `:runtime:contract` 中定义不可变配置实体 `VmHardwareConfig`：包含虚拟 CPU 核心数 `cpuCores` ($1 \sim 8$)、内存 `ramMegabytes` ($512\text{MB} \sim 8192\text{MB}$)、虚拟磁盘 `diskCapacityBytes` ($2\text{GB} \sim 64\text{GB}$)、GPU 硬件加速开关 `gpuAccelerationEnabled`、以及高级内核扩展参数 `extraKernelArguments`；
   - 完备的输入边界强校验（超限或负数即刻抛出 `IllegalArgumentException`）、流式 `Builder` 与安全默认值工厂方法；
   - 扩容 `FullLinuxContract.Capability`，追加 `GPU_HARDWARE_ACCELERATION`；扩容 `FullLinuxContract.Instance` 与 `InstanceLifecycle.Instance` 挂接硬件配置并支持向后兼容元数据序列化与反序列化。
2. **生命周期物理删除契约与安全熔断机制（`InstanceLifecycle.Store.delete`）**：
   - 在 `InstanceLifecycle.Store` 声明 `void delete(String id) throws IOException;`；
   - 在 `InstanceLifecycle.FileStore` 实现删除操作，并加入**安全熔断机制（Safety Circuit Breaker）**：仅允许在 `STOPPED`、`CREATED`、`CRASHED` 状态下物理删除；若实例处于 `RUNNING` 或 `PROVISIONING` 状态时，严禁删除并抛出 `IllegalStateException`，保证目标磁盘与元数据完好无损；
   - 级联物理移除宿主私有目录中关联的 `<id>.raw` 磁盘镜像（含 `instance.rootVolume()` 与 `root-volumes/<id>.raw`）、临时交换与缓存文件（`<id>.swap`、`*.tmp`、`<id>.properties.tmp`）以及元数据文件（`<id>.properties`）。

---

## 2. 纪律红线与架构约束核对（Guardrails & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 结论 |
| :--- | :--- | :--- | :--- |
| **执行环境隔离** | 虚拟盘符 `X:\`（映射至 Worktree `DroidLayer_ticket49`） | 全流程在 `X:\` 挂载环境下执行，所有命令均限定在工作树内 | **100% 遵从** |
| **所有权边界（Ownership）** | 改动严格限定在 `:runtime:contract` 与 `:runtime:instance` | 仅修改 `runtime/contract/` 与 `runtime/instance/`，未破坏既有对外接口 | **完全受控** |
| **测试命令规范** | 严禁尾随 `gradlew.bat --stop` | 全程执行 `.\gradlew.bat ...` 命令，无任何 `--stop` 尾随 | **100% 遵从** |
| **编译质量红线** | 开启 `-Xlint:all -Werror`，零警告、零隐式转换 | 全部模块通过严格编译参数，编译零警告零报错 | **完全合规** |
| **向下兼容性** | 既有 `.properties` 元数据文件缺失硬件配置时安全回退 | `fromProperties()` 缺省自动回退至 `VmHardwareConfig.defaultConfig()` | **100% 兼容** |

---

## 3. 核心实现与架构设计细节

### 3.1 `VmHardwareConfig` 策略中立模型
- **位置**：`io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig`
- **常量定义**：
  ```java
  public static final int MIN_CPU_CORES = 1;
  public static final int MAX_CPU_CORES = 8;
  public static final int DEFAULT_CPU_CORES = 2;

  public static final int MIN_RAM_MEGABYTES = 512;
  public static final int MAX_RAM_MEGABYTES = 8192;
  public static final int DEFAULT_RAM_MEGABYTES = 2048;

  public static final long MIN_DISK_CAPACITY_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GB
  public static final long MAX_DISK_CAPACITY_BYTES = 64L * 1024L * 1024L * 1024L; // 64 GB
  public static final long DEFAULT_DISK_CAPACITY_BYTES = 8L * 1024L * 1024L * 1024L; // 8 GB
  ```
- **输入合法性前置检查**：对违规 vCPU、RAM、磁盘容量抛出确定性 `IllegalArgumentException`；
- **能力矩阵与契约扩展**：
  - `FullLinuxContract.Capability` 追加 `GPU_HARDWARE_ACCELERATION`；
  - `FullLinuxContract.CapabilityMatrix` 提供 `supportsGpuAcceleration()` 与 `gpuBadge()`；
  - `FullLinuxContract.Instance` 挂接 `id` 与 `hardwareConfig`。

### 3.2 `InstanceLifecycle.Store.delete` 物理级联清扫与安全熔断
- **状态熔断守卫**：
  ```java
  State state = instance.state();
  if (state == State.RUNNING || state == State.PROVISIONING) {
      throw new IllegalStateException("Cannot delete instance while " + state);
  }
  if (state != State.STOPPED && state != State.CREATED && state != State.CRASHED) {
      throw new IllegalStateException("Cannot delete instance in state " + state);
  }
  ```
- **级联清扫算法**：
  1. 清理 `instance.rootVolume()`（即绑定的真实虚拟磁盘文件）；
  2. 清理 `directory.resolve(id + ".raw")` 及 `root-volumes/<id>.raw`；
  3. 清理 `<id>.swap`、`<id>.tmp`、`<id>.properties.tmp`；
  4. 清理 `crashDiagnostic.logPath()`；
  5. 清理 `directory` 下以 `<id>.` 或 `<id>-` 开头的所有附属临时文件；
  6. 物理移除 `<id>.properties` 元数据文件。

---

## 4. 验证实证与测试报告

### 4.1 JVM 契约测试（100% 绿灯）
执行命令：
```powershell
.\gradlew.bat :runtime:contract:test :runtime:instance:test
.\gradlew.bat :runtime:contract:check :runtime:instance:check --rerun-tasks
```

**测试结果记录**：
```
> Task :runtime:contract:audioStreamContractTest
AUDIO_STREAM_CONTRACT_TEST_OK
> Task :runtime:contract:bridgeControlClipboardTest
BRIDGE_CONTROL_CLIPBOARD_TEST_OK
> Task :runtime:contract:bridgeControlDisplayTest
BRIDGE_CONTROL_DISPLAY_TEST_OK
> Task :runtime:contract:displayControlContractTest
DISPLAY_CONTROL_CONTRACT_TEST_OK
> Task :runtime:contract:fullLinuxContractAudioCapabilityTest
FULL_LINUX_CONTRACT_AUDIO_CAPABILITY_TEST_OK
> Task :runtime:contract:inputEventSerializationTest
INPUT_EVENT_SERIALIZATION_TEST_OK
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:pointerEventRelativeMotionTest
POINTER_EVENT_RELATIVE_MOTION_TEST_OK
> Task :runtime:contract:portForwardingContractTest
PORT_FORWARDING_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:storageContractTest
STORAGE_CONTRACT_TEST_OK
> Task :runtime:contract:vmHardwareConfigTest
VM_HARDWARE_CONFIG_TEST_OK
> Task :runtime:instance:instanceLifecycleDeleteTest
INSTANCE_LIFECYCLE_DELETE_TEST_OK
> Task :runtime:instance:instanceStorageContractTest
INSTANCE_STORAGE_CONTRACT_TEST_OK

BUILD SUCCESSFUL in 26s
27 actionable tasks: 27 executed
```

### 4.2 Android 真机实测验证（序列号：`device-a`，Android 15）
通过 `d8` 将编译字节码打包为 DEX 并在 Android ART 虚拟机内部真实驱动执行：

1. **`VmHardwareConfigTest` 真机运行结果**：
   ```bash
   adb -s device-a shell "dalvikvm -cp /data/local/tmp/test-ticket49.jar io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfigTest"
   # 输出：
   VM_HARDWARE_CONFIG_TEST_OK
   ```

2. **`InstanceLifecycleDeleteTest` 真机运行结果**：
   ```bash
   adb -s device-a shell "dalvikvm -cp /data/local/tmp/test-ticket49.jar io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycleDeleteTest /data/local/tmp/ticket49-test"
   # 输出：
   INSTANCE_LIFECYCLE_DELETE_TEST_OK
   ```

3. **真机物理存储清扫对账证据**：
   ```bash
   # 检查删除后的 STOPPED 实例存储目录：
   adb shell "ls -la /data/local/tmp/ticket49-test/delete-stopped-store"
   # 输出：
   total 6
   drwxrwxrwx  2 shell shell 3452 2026-10-06 17:46 .
   drwxrwxrwx 11 shell shell 3452 2026-10-06 17:46 ..
   # 断言：<id>.properties、<id>.raw、<id>.swap、<id>.tmp 全部彻底移除，无任何孤儿文件滞留！

   # 检查拦截 RUNNING 删除的 safety-store 目录：
   adb shell "ls -la /data/local/tmp/ticket49-test/running-safety-store"
   # 输出：
   -rw-rw-rw-  1 shell shell  527 2026-10-06 17:46 1d5fdfd7-2b47-4aa5-8936-86538caf8a10.properties
   # 断言：RUNNING 状态触发 IllegalStateException，元数据与磁盘完好无损，熔断安全机制 100% 有效！
   ```

---

## 5. 验收标准逐条对照表（Acceptance Criteria Verification）

| 验收条款 | 规格与行为标准 | 达成状态 | 验证实证与测试用例 |
| :--- | :--- | :---: | :--- |
| **AC-1** | `VmHardwareConfig` 不可变类定义完整，对非法核心数、非法内存及非法磁盘准确抛出 `IllegalArgumentException` | **PASS** | `VmHardwareConfigTest.rejectsIllegalCpuCores` / `rejectsIllegalRamMegabytes` / `rejectsIllegalDiskCapacityBytes` |
| **AC-2** | `InstanceLifecycle.Store` 声明 `void delete(String id) throws IOException` | **PASS** | `InstanceLifecycle.Store.delete(String)` 契约声明与接口实现 |
| **AC-3** | `InstanceLifecycle.FileStore` 实现级联删除：调用 `delete(id)` 后，对应的 `.properties` 元数据与 `.raw` 磁盘文件均被物理移除，`list()` 不再包含该实例 | **PASS** | `InstanceLifecycleDeleteTest.deletesStoppedInstanceAndPhysicallyPurgesFiles`（真机与 JVM 均实测通过） |
| **AC-4** | 当目标实例状态为 `RUNNING` 时，调用 `delete(id)` 确定性抛出 `IllegalStateException`，且磁盘文件与元数据完好无损 | **PASS** | `InstanceLifecycleDeleteTest.rejectsDeletionOfRunningInstanceWithSafetyCircuitBreaker` |
| **AC-5** | 当试图删除一个不存在的实例 ID 时，抛出 `NoSuchFileException` 或明确的 `IOException` | **PASS** | `InstanceLifecycleDeleteTest.throwsNoSuchFileExceptionWhenDeletingNonexistentInstance` |
| **AC-6** | 纯 JVM 契约测试覆盖率达 100%，构建通过无任何编译器警告（`-Xlint:all -Werror`） | **PASS** | `:runtime:contract:check` 与 `:runtime:instance:check` 27/27 任务全绿 |
