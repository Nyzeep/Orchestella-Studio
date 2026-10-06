# 50: 宿主物理硬件感知探测器与稀疏磁盘安全扩容引擎

Type: implementation  
Status: completed  
Labels: m4-spec, tracer-bullet, hardware-detector, sparse-disk, qemu-smp-memory  

## User Story

作为在不同配置硬件（旗舰平板 vs 紧凑低配手机）上运行 Linux 虚拟机的开发者，我希望应用能感知宿主 CPU 物理核数与可用运存并给出安全配置建议，同时支持在关机状态下对磁盘安全进行稀疏无损扩容且动态转换为底层 QEMU 参数，以防止因资源超额导致宿主 LMK 闪退或因硬编码参数而浪费物理算力（US-20, US-21, US-28）。

## What to build

遵循 ADR-0007, ADR-0019 与 spec-m4.md，在 `:app`、`:runtime:instance` 与 `:runtime:engine-qemu` 中实现宿主硬件自适应嗅探、稀疏磁盘安全扩容与 QEMU 启动参数转换扩展：

1. **宿主物理硬件感知探测器（`HostHardwareProfile` & `HostHardwareDetector`）**：
   - 在 `:app` 模块建立纯 Java 不可变实体 `HostHardwareProfile`（解耦 Android SDK，便于纯 JVM 测试）：
     * 记录 `totalCores`、`totalRamBytes`、`availRamBytes`、`thresholdRamBytes`、`lowMemory`；
     * 实现安全分配计算模型：
       - 强制保留至少 2048MB（2GB）物理内存给 Android 宿主操作系统；
       - 推荐 RAM：`min(物理总运存 - 2048MB, 可用空闲运存 * 0.75)`；
       - 推荐 vCPU：`max(1, 物理核心数 - 1)`；
       - 划分绿色安全区（`SAFE_GREEN`）、黄色预警区（`WARNING_YELLOW`）与红色封锁区（`BLOCKED_RED`）；
   - 在 `:app` 模块实现 `HostHardwareDetector`：
     * 读取 `Runtime.getRuntime().availableProcessors()` 探测物理 CPU 核心总数；
     * 通过 Android `ActivityManager.getMemoryInfo()` 读取物理总 RAM、当前可用 RAM、低内存阈值与 lowMemory 标志；
     * 具备平台解耦适配接缝。

2. **稀疏磁盘安全扩容引擎（`SparseDiskResizer`）**：
   - 位于 `:runtime:instance` 模块（纯 Java 标准库，仅依赖 `java.nio.file.Path` 与 `java.nio.channels.FileChannel`）；
   - 仅允许在实例处于关机（`STOPPED`）状态下扩容（非 STOPPED 状态抛出 `IllegalStateException`）；
   - 校验目标容量必须严格大于当前容量（严禁缩容，缩容抛出 `IllegalArgumentException("Disk shrinking is not supported")`）；
   - 校验宿主存储分区的剩余可用空间，空间不足抛出 `InsufficientStorageException` 阻止超额预配；
   - 使用 Java NIO `FileChannel.position(targetBytes - 1).write(ByteBuffer.wrap(new byte[]{0}))` 实现微秒级稀疏扩展，保持宿主底层文件系统（ext4/f2fs）的 Sparse 文件特性，零额外物理磁盘碎片。

3. **QEMU 启动参数转换扩展（`QemuLaunchSpec` 扩容）**：
   - 扩展 `QemuLaunchSpec`，提供接收策略中立 `VmHardwareConfig` 的启动参数构建方法；
   - 动态生成：
     * `-smp {cpuCores}`；
     * `-m {ramMegabytes}M`；
     * 若配置包含 `extraKernelArguments`，将其追加至内核命令行；
   - 保持向后兼容性，确保既有 7/8/9/10/11 参数旧构建入口功能无损。

**Blocked by:** 49  
**Blocks:** 53 (Dashboard), 54 (Setup Wizard)

## Seam Design

- **硬件感知接缝**：`HostHardwareProfile` 作为纯数据承载与算子模型，不引用 `android.app.ActivityManager`，使推荐算法、区间划分与边界校验可在纯 JVM 单元测试中 100% 覆盖；`HostHardwareDetector` 专注 Android Framework 与纯模型之间的适配；
- **存储扩容接缝**：`SparseDiskResizer` 采用标准 `java.nio.file.Path` 与 `StorageSpaceChecker`，可独立在纯 JVM 下验证稀疏写入、状态熔断与边界异常，无缝接合 `InstanceStorageManager` 与 `InstanceLifecycle.Controller`；
- **启动转换接缝**：`QemuLaunchSpec` 作为 QEMU 专有命令行装配器，直接消费中立的 `VmHardwareConfig` 并产出底层 CLI 阵列，上层业务逻辑无感知底层标志格式。

## Acceptance Criteria

- [x] `HostHardwareProfile` 纯 Java 实体定义完整，准确实现 2048MB 宿主保留、推荐 RAM/vCPU 公式与绿/黄/红三色安全警戒区间评估算法；
- [x] `HostHardwareDetector` 正确自 `ActivityManager.getMemoryInfo()` 与 `Runtime.availableProcessors()` 提取硬件数据；
- [x] `SparseDiskResizer` 严格限定在 `STOPPED` 状态下执行；对运行态（`RUNNING` 等）调用抛出 `IllegalStateException`；
- [x] `SparseDiskResizer` 对缩容或等容调用确定性抛出 `IllegalArgumentException("Disk shrinking is not supported")`；
- [x] `SparseDiskResizer` 在存储空间不足时抛出 `InsufficientStorageException`；
- [x] `SparseDiskResizer` 使用 NIO `FileChannel.position(targetBytes - 1).write(...)` 正确扩容磁盘文件，且保持前置已有数据字节级完整无损；
- [x] `QemuLaunchSpec` 支持 `VmHardwareConfig`，输出 `-smp {cpuCores}` 与 `-m {ramMegabytes}M`，且既有单测 100% 兼容通过；
- [x] 纯 JVM 单元测试全部绿灯通过（严禁在命令尾随 `gradlew.bat --stop`）；
- [x] 在 Android 真机（`AGYJUT3601001102`）上通过 ADB 验证真实硬件感知读取与稀疏磁盘扩容。

## Device Verification Steps

1. 纯 JVM 单元测试：
   `./gradlew.bat :runtime:contract:test :runtime:instance:test :runtime:engine-qemu:test :app:testDebugUnitTest`
2. 真机 ADB 硬件信息与稀疏磁盘验证：
   - 查询设备物理核心与运存：`adb -s AGYJUT3601001102 shell "cat /proc/cpuinfo; cat /proc/meminfo"`
   - 验证宿主目录下的磁盘稀疏扩容前后逻辑大小与实际物理块分配。
