# 50: 宿主物理硬件感知探测器与稀疏磁盘安全扩容引擎

Type: implementation  
Status: ready-for-agent  
Labels: m4-spec, tracer-bullet, hardware-config, host-detection  

## User Story

作为在不同硬件配置（低配手机 vs 高性能平板）上使用 Orchestella Studio 的用户，我希望应用能自动探测我设备的物理 CPU 核心与当前可用运存，为我推荐安全且充足的虚拟机配置，并允许在关机时安全扩容虚拟磁盘，防止超出系统承载导致应用闪退（US-20, US-21）。

## What to build

在 `:app` 与 `:runtime:instance` 中实现宿主硬件探测器与稀疏虚拟磁盘扩容引擎，遵循 ADR-0004、ADR-0007 与 spec-m4.md：

1. **宿主物理硬件感知探测器（`HostHardwareDetector`）**：
   - 探测物理 CPU 核心总数（`Runtime.getRuntime().availableProcessors()`）；
   - 接入 Android `ActivityManager.getMemoryInfo()` 读取物理总 RAM、可用空闲 RAM 及低内存临界值（`threshold`）；
   - **安全分配与防 OOM 计算模型**：
     * 宿主底线保护：强制保留至少 $2048\text{ MB}$（2GB）物理内存给 Android 操作系统；
     * 推荐 RAM 计算：$\min(\text{物理总运存} - 2048\text{ MB}, \text{可用空闲运存} \times 0.75)$；
     * 推荐 vCPU 计算：$\max(1, \text{物理核心数} - 1)$；
     * 安全滑块警戒线：计算绿色安全区、黄色预警区与红色封锁区；
2. **稀疏磁盘安全扩容引擎（`SparseDiskResizer`）**：
   - 支持在实例关机（`STOPPED`）状态下安全扩容虚拟磁盘；
   - 校验目标容量必须大于当前容量（禁止缩容，防止 Guest 文件系统元数据崩溃）；
   - 校验宿主存储分区的剩余可用空间（`StatFs.getAvailableBytes()`）；
   - 利用 Java NIO `FileChannel.position(targetBytes - 1).write(ByteBuffer.wrap(new byte[]{0}))` 实现快速稀疏扩展，不预先耗尽物理块存储；
3. **QEMU 执行策略动态参数挂接（`QemuLaunchSpec` 扩容）**：
   - 根据实例绑定的 `VmHardwareConfig` 动态生成 QEMU 启动参数：
     * `-smp {cpuCores}`（支持多线程 TCG）；
     * `-m {ramMegabytes}M`；
     * 驱动虚拟盘挂载配置。

**Blocked by:** 49

## Seam Design

- **硬件探测抽象**：在 `:app` 定义 `HostHardwareDetector`，其依赖纯 Java 的 `HostHardwareProfile` 实体，便于脱离真实 Android 上下文进行 JVM 单元测试；
- **存储扩容解耦**：`SparseDiskResizer` 仅依赖标准 `java.nio.file.Path` 与目标字节数，不耦合具体 VMM 引擎；
- **启动参数转化**：`QemuLaunchSpec.Builder` 接收 `VmHardwareConfig`，生成不可变启动命令列表。

## Acceptance Criteria

- [ ] `HostHardwareDetector` 准确计算各种内存容量下的推荐与硬上限（在 4GB 设备上最多允许分配 2GB，在 12GB 设备上推荐 6GB~8GB，且永远保留 $\ge 2\text{GB}$ 给宿主）；
- [ ] `SparseDiskResizer` 能正确将已存在的 `.raw` 磁盘镜像扩容至新大小（如从 4GB 扩容至 16GB），且文件系统的实际物理分配块不发生巨幅膨胀（稀疏特性保留）；
- [ ] 试图缩减磁盘容量（如 8GB 缩至 4GB）时，被确定性抛出 `IllegalArgumentException("Disk shrinking is not supported")` 拦截；
- [ ] 当宿主磁盘剩余空间不足以容纳最低增量时，扩容请求被安全拒绝；
- [ ] `QemuLaunchSpec` 能将 `VmHardwareConfig(cpu=4, ram=3072)` 准确渲染为 `-smp 4` 与 `-m 3072M`；
- [ ] 纯 JVM 测试覆盖率达到 100%。

## Device Verification Steps

在真机上验证宿主硬件检测与磁盘扩容：
```bash
# 1. 查看设备物理内存与核心
adb shell cat /proc/meminfo
adb shell nproc

# 2. 检查生成的 QEMU 启动参数
adb logcat -d -s DroidLayer:I | grep -E "(-smp|-m)"

# 3. 验证 raw 镜像文件逻辑大小与实际物理块占用
adb shell "run-as io.github.nyzeep.droidlayer ls -lh files/instances/*.raw"
adb shell "run-as io.github.nyzeep.droidlayer du -h files/instances/*.raw"
```

## Test Strategy

- JVM 单元测试：
  - `HostHardwareDetectorTest`：模拟低配 3GB 设备、标配 8GB 设备、旗舰 16GB 设备测试推荐与上限安全算子；
  - `SparseDiskResizerTest`：在临时目录生成测试虚拟磁盘文件，测试扩容前后 `Files.size()` 变化与缩容拒绝断言；
  - `QemuLaunchSpecHardwareArgsTest`：测试硬件配置向命令行参数的映射转换；
- 运行 `./gradlew :app:testDebugUnitTest :runtime:engine-qemu:test`。

## Prototype/research dependency

依赖 Ticket 49（硬件配置实体）与 `docs/research/2026-10-06-virtualization-ux-and-competitors.md` 中 VMware 硬件参数设计。
