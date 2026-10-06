# 49: 策略中立硬件参数配置模型与实例物理删除契约扩展

Type: contract  
Status: ready-for-agent  
Labels: m4-spec, tracer-bullet, lifecycle, hardware-config  

## User Story

作为拥有不同配置设备的 Android 开发者，我希望能够在创建或停止虚拟机时显式自定义 vCPU 核心数、RAM 运存与磁盘容量，并在实例废弃后将其从存储中彻底物理销毁，以便精准控制系统资源并释放手机私有存储空间（US-20, US-21, US-22, US-23）。

## What to build

在 `:runtime:contract` 与 `:runtime:instance` 中扩展策略中立的硬件配置实体与生命周期物理删除契约，遵循 ADR-0002 与 spec-m4.md：

1. **硬件参数配置实体（`VmHardwareConfig`）**：
   - 在 `:runtime:contract` 中定义不可变实体 `VmHardwareConfig`：
     * `cpuCores`（1~8 核，默认 2）；
     * `ramMegabytes`（512MB~8192MB，默认 2048MB）；
     * `diskCapacityBytes`（2GB~64GB，默认 8GB）；
     * `gpuAccelerationEnabled`（默认根据设备自适应）；
     * `extraKernelArguments`（可选内核启动参数）；
   - 提供完备的输入校验算法与安全默认工厂方法；
   - 扩容 `FullLinuxContract.Instance`，挂接 `VmHardwareConfig` 配置；
2. **生命周期存储契约扩展（`InstanceLifecycle.Store`）**：
   - 在 `InstanceLifecycle.Store` 接口中新增方法：
     ```java
     void delete(String id) throws IOException;
     ```
3. **安全熔断机制（Safety Circuit Breaker）**：
   - 在 `InstanceLifecycle.FileStore` 中实现删除操作前的状态互斥校验：
     * 仅当实例状态处于 `STOPPED`、`CREATED` 或 `CRASHED` 时允许物理删除；
     * 若实例状态为 `RUNNING` 或 `PROVISIONING`，强制抛出 `IllegalStateException` 拦截删除；
4. **级联物理资产销毁（Cascade Physical Purge）**：
   - 彻底删除宿主私有目录中关联的虚拟磁盘文件（`<id>.raw`）、临时交换文件（`<id>.swap`、`*.tmp`）以及元数据文件（`<id>.properties`）。

**Blocked by:** 44

## Seam Design

- **配置实体解耦**：`VmHardwareConfig` 作为策略中立数据载荷，放置于 `:runtime:contract`，不依赖任何 Android SDK 或 VMM 专有库；执行引擎（QEMU 与 AVF）仅按此数据提取启动参数；
- **存储接口演进**：`InstanceLifecycle.Store` 在不破坏既有 `save`、`load`、`list` 语义的前提下，正交追加 `delete` 操作；
- **熔断校验**：在 `FileStore.delete()` 内部调用 `load(id)` 校验 `instance.state()`，保持原子性。

## Acceptance Criteria

- [ ] `VmHardwareConfig` 不可变类定义完整，对非法核心数（$\le 0$ 或 $> 8$）、非法内存（$< 512\text{MB}$）及非法磁盘（$< 2\text{GB}$）准确抛出 `IllegalArgumentException`；
- [ ] `InstanceLifecycle.Store` 成功声明 `void delete(String id) throws IOException`；
- [ ] `InstanceLifecycle.FileStore` 实现级联删除：调用 `delete(id)` 后，对应的 `.properties` 元数据与 `.raw` 磁盘文件均被物理移除，`list()` 不再包含该实例；
- [ ] 当目标实例状态为 `RUNNING` 时，调用 `delete(id)` 确定性抛出 `IllegalStateException`，且磁盘文件与元数据完好无损；
- [ ] 当试图删除一个不存在的实例 ID 时，抛出 `NoSuchFileException` 或明确的 `IOException`；
- [ ] 纯 JVM 单元测试覆盖率达 100%，构建通过无任何编译器警告。

## Device Verification Steps

在 Android 真机上通过 ADB 命令验证契约与物理删除行为：
```bash
# 1. 运行 JVM 契约测试
./gradlew :runtime:contract:test :runtime:instance:test

# 2. ADB 推送测试 APK 并验证私有目录物理清理
adb shell "run-as io.github.nyzeep.droidlayer ls -la files/instances/"
# 断言删除后 <id>.raw 与 <id>.properties 物理文件已彻底消失，无孤儿文件滞留
```

## Test Strategy

- JVM 纯单元测试：
  - `VmHardwareConfigTest`：测试各参数极值、合法边界、默认值与序列化；
  - `InstanceLifecycleDeleteTest`：测试正常删除、状态熔断（RUNNING 状态拦截）、不存在实例删除、以及多实例隔离测试；
- 运行 `./gradlew :runtime:contract:test :runtime:instance:test`。

## Prototype/research dependency

依赖 ADR-0002 契约规范与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md` 竞品生命周期分析。
