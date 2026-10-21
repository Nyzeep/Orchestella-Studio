# P08 实施报告：QEMU TCG 调优矩阵扩展（零设备）

> 票：[P08-qemu-tuning.md](../../../.scratch/orchestella-fastlane/issues/P08-qemu-tuning.md)
> 分支：`feature/fastlane-qemu-observability` ｜ 基线：`018855bc`
> 结论：**默认 argv 逐字节不变；新增候选全部 opt-in；未声称任何性能提升。** 真机/性能 `not-tested`。

## 1. 目标与范围

在 `runtime/engine-qemu` 增加可单变量验证的 QEMU 候选参数，同时保证：

- `QemuTuning.DEFAULTS` 的 argv 与改动前逐字节一致；
- 每个新参数是独立开关，默认关闭；
- 非法值拒绝，`cache=unsafe` 永不可表达；
- 矩阵脚本只生成 `not-tested` 计划，不伪造性能数字；
- 不改 QEMU 网络/GPU 默认、不改 App 产品路径。

本票只做零设备 argv 构造与矩阵；真机 A/B 由 DV-03 执行。

## 2. 设计结论（先于代码）

### 2.1 `tb-size` 单位确实是 MiB，旧字段名错误

- QEMU 11.1.1 `-accel tcg,tb-size=N` 的 `N` 单位是 **MiB**：`tcg_init(s->tb_size * MiB, ...)`（[accel/tcg/tcg-all.c](https://gitlab.com/qemu-project/qemu/-/blob/v11.1.1/accel/tcg/tcg-all.c)）；手册写 “Controls the size (in MiB) of the TCG translation block cache.”（[QEMU manpage](https://www.qemu.org/docs/master/system/qemu-manpage.html)）。
- 旧字段 `tbSizeKb` 把数字直接拼进 `tb-size=`，所以值 64 实际是 64 MiB；字段名会诱导把 512 MiB 当 512 KiB。**改名 `tbSizeMib`，不保留 `tbSizeKb()` 别名**，避免错误单位继续传播。
- `0` 仍表示“省略”，保留 QEMU 自动默认；上限 `MAX_TB_SIZE_MIB=1024`。

### 2.2 `aio=native` 是旧代码真实接受的非法组合

- QEMU 11.1.1 `block/file-posix.c` 明确：`aio=native was specified, but it requires cache.direct=on, which was not specified.`（[v11.1.1 file-posix.c](https://github.com/qemu/qemu/blob/v11.1.1/block/file-posix.c)）。
- 本项目的 root drive 固定 `cache=writeback`，没有 `cache.direct=on`。旧 `QemuTuning` 却接受 `native`，会在 QEMU 启动时失败。
- 本票把允许值收紧为 `threads` / `io_uring`；`native` 现在抛 `IllegalArgumentException`，并有测试。默认仍是省略 `aio`（QEMU 默认 `threads`）。

### 2.3 `detect-zeroes=unmap` 单独开启不会真正 unmap

- QEMU 只在 `discard=unmap`（`BDRV_O_UNMAP`）时保留 `BDRV_REQ_MAY_UNMAP`；否则清掉该 hint，行为接近 `detect-zeroes=on`（[v11.1.1 block/io.c](https://github.com/qemu/qemu/blob/v11.1.1/block/io.c)）。
- 因此矩阵保留单独的 `detect-zeroes-unmap` 变体，但报告明确：**它只应和 `discard=unmap` 一起解读**；不把它写成独立的 discard 收益。

### 2.4 iothread / num-queues 在 `virtio-blk-device`（MMIO）上可用

- `DEFINE_PROP_LINK("iothread", VirtIOBlock, conf.iothread, TYPE_IOTHREAD, ...)` 与 `DEFINE_PROP_UINT16("num-queues", ...)` 位于 `hw/block/virtio-blk.c`，MMIO `virtio-blk-device` 使用同一 `VirtIOBlock` 属性（[v11.1.1 virtio-blk.c](https://github.com/qemu/qemu/blob/v11.1.1/hw/block/virtio-blk.c)）。
- `iothread` 的正确用法是 `-object iothread,id=...` + `-device ...,iothread=...`；`num-queues` 必须是数字，不能写字面量 `vCPU`。
- 单 iothread 会让所有队列共享同一 AioContext，**不承诺每队列并行**；真正的并行需要 `iothread-vq-mapping`，本票不引入。

### 2.5 RNG 用 MMIO `virtio-rng-device`，不是 `virtio-rng-pci`

- 票面候选写的是 `virtio-rng-pci`，但本项目现有设备全部是 MMIO（`virtio-blk-device`、`virtio-gpu-device`、`virtio-net-device`）。
- QEMU 的 `TYPE_VIRTIO_RNG` 基类型是 `virtio-rng-device`，`virtio-rng-pci` 只是 PCI 子类；两者都可用，但 MMIO 版本与本项目设备模型一致，避免引入 PCI 依赖。
- `-object rng-random,id=rng0,filename=/dev/urandom` + `-device virtio-rng-device,rng=rng0` 是有效组合；`/dev/urandom` 是非阻塞的。票面的 `virtio-rng-pci` 作为备选记录，不在代码中启用。

### 2.6 `-overcommit mem-lock=off` 是默认值

- QEMU 手册：`mem-lock=on` 才会 mlock guest RAM，默认关闭（[QEMU manpage](https://www.qemu.org/docs/master/system/qemu-manpage.html)）。
- 因此该开关**不改变行为**；保留它只是让矩阵显式记录“已检查”，不期待性能差异。

### 2.7 CPU 候选

- `-cpu max,sve=off,pauth-impdef=on` 在 QEMU 11.1.1 有效；`max` 是 TCG 下可用特性的最大值，`pauth-impdef=on` 选择非加密 PAC，但在未指定 qarma 时 impdef 已是默认算法，**该参数是冗余的**（[cpu-features](https://www.qemu.org/docs/master/system/arm/cpu-features.html)、[v11.1.1 cpu64.c](https://gitlab.com/qemu-project/qemu/-/blob/v11.1.1/target/arm/tcg/cpu64.c)）。
- `sve=off` 单独开启仍可能留下 SME；票面未要求 `sme=off`，本票不擅自扩大候选。报告把 `sve=off,sme=off` 列为下一步候选。
- `max` 的 guest 可见特性跨 QEMU 版本不稳定，不做迁移/快照默认。

### 2.8 明确未实现的票面项

- **guest `mitigations=off` / `elevator=mq-deadline` 未实现**：本会话的用户 brief 候选列表未包含它们；`mitigations=off` 是安全削弱项，必须单独安全评审与产品决策，不能混在性能矩阵里默认打开。留作后续票。
- **`virtio-rng-pci` 改为 `virtio-rng-device`**：见 §2.5；这是设备模型一致性选择，不是功能删除。

## 3. 代码改动

| 文件 | 改动 |
| --- | --- |
| `QemuTuning.java` | `tbSizeKb`→`tbSizeMib`；新增 builder 与 4 个布尔开关（iothread/num-queues/rng/overcommit）；`aio` 收紧为 `threads`/`io_uring`；CPU 候选常量；旧 7 参构造器保留并默认关闭新开关 |
| `QemuLaunchSpec.java` | `accelArg` 使用 MiB；新增 `rootDeviceArg`/`effectiveVcpuCount`；按开关追加 `-object iothread`、`num-queues`、`rng-random`、`virtio-rng-device`、`-overcommit mem-lock=off`；默认顺序不变 |
| `QemuTuningTest.java` | 47 项默认 argv 金样；每个候选单独 argv 快照；非法值/去重/旧构造器测试 |
| `tools/qemu-tuning/matrix.py` | `tb_size_kb`→`tb_size_mib`；新增 7 个 P08 变体；仍只输出 `not-tested` |
| `tools/qemu-tuning/test_matrix.py` | 新增单变量、MiB、候选存在、无 unsafe 的测试 |

### 3.1 新增开关

| 开关 | 默认 | argv 效果 |
| --- | --- | --- |
| `cpuModel` | `cortex-a57` | `-cpu <value>` |
| `tbSizeMib` | `0`（省略） | `-accel tcg,thread=multi,tb-size=N` |
| `blockAio` | `null`（省略） | `-drive ...,aio=threads` 或 `aio=io_uring` |
| `discardUnmap` | `false` | `-drive ...,discard=unmap` |
| `detectZeroesUnmap` | `false` | `-drive ...,detect-zeroes=unmap` |
| `iothreadPerDisk` | `false` | `-object iothread,id=iothread-root` + `-device virtio-blk-device,drive=root,iothread=iothread-root` |
| `multiQueueVcpu` | `false` | `-device virtio-blk-device,drive=root,num-queues=<effective vCPU>` |
| `rngRandom` | `false` | `-object rng-random,id=rng0,filename=/dev/urandom` + `-device virtio-rng-device,rng=rng0` |
| `overcommitMemLockOff` | `false` | `-overcommit mem-lock=off`（QEMU 默认即此值） |
| `vcpuCount` | `0`（取硬件配置） | `-smp N` |

### 3.2 默认 argv 快照（47 项，逐字节）

```text
/native/lib/arm64-v8a/libdroidlayer_qemu.so
-machine
virt,gic-version=3
-accel
tcg,thread=multi
-cpu
cortex-a57
-smp
2
-m
2048M
-nodefaults
-display
none
-monitor
none
-no-reboot
-chardev
socket,id=terminal,path=droidlayer-terminal-i1,abstract=on,server=on,wait=off
-serial
chardev:terminal
-kernel
/data/runtime/Image
-initrd
/data/runtime/initramfs-embedded.cpio.gz
-append
console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda droidlayer.input_tag=droidlayer-input droidlayer.bridge_device=/dev/vport0p1 droidlayer.terminal_device=/dev/ttyAMA0
-drive
if=none,id=root,file=/data/instances/i1/root-volume.raw,format=raw,cache=writeback
-device
virtio-blk-device,drive=root
-device
virtio-gpu-gl-device
-netdev
socket,id=orchestella-net,connect=127.0.0.1:12345
-device
virtio-net-device,netdev=orchestella-net
-device
virtio-serial-device
-chardev
socket,id=control,path=droidlayer-control-i1,abstract=on,server=on,wait=off
-device
virtserialport,chardev=control,name=droidlayer.bridge
-chardev
socket,id=netproxy,path=orchestella-proxy-i1,abstract=on,server=on,wait=off
-device
virtserialport,chardev=netproxy,name=orchestella.proxy
```

**旧/新对比方法**：用 `git show 018855bc` 取出改动前的 `QemuTuning.java`/`QemuLaunchSpec.java`，与改动后分别编译，运行同一个 `PrintArgs`（固定路径与 `VmHardwareConfig.defaultConfig()`），`diff -u` 无输出；两边均为 47 行。测试 `QemuTuningTest` 另用字面量金样重复锁定。

## 4. 候选 argv 摘要

| 候选 | 关键 argv |
| --- | --- |
| baseline | `-accel tcg,thread=multi`；`-drive ...,cache=writeback`；`-device virtio-blk-device,drive=root` |
| CPU max | `-cpu max,sve=off,pauth-impdef=on` |
| tb-size 64 MiB | `-accel tcg,thread=multi,tb-size=64` |
| iothread | `-object iothread,id=iothread-root`；`-device virtio-blk-device,drive=root,iothread=iothread-root` |
| num-queues | `-device virtio-blk-device,drive=root,num-queues=2`（默认 2 vCPU；显式 `vcpuCount=4` 时为 4） |
| aio=threads | `-drive if=none,id=root,file=...,format=raw,cache=writeback,aio=threads` |
| discard | `-drive ...,discard=unmap` |
| detect-zeroes | `-drive ...,detect-zeroes=unmap`（单独开启时 QEMU 会清掉 unmap hint） |
| rng-random | `-object rng-random,id=rng0,filename=/dev/urandom`；`-device virtio-rng-device,rng=rng0` |
| overcommit | `-overcommit mem-lock=off`（默认值，无行为变化） |

## 5. 测试与结果

执行环境：JDK 21，`javac --release 17 -Xlint:all -Werror`，无 Android SDK；本环境无法跑全仓 Gradle。

```bash
# main-based engine-qemu 测试（节选）
java -cp <main>:<test> ...QemuTuningTest
# -> QEMU_TUNING_TEST_OK

java -cp <main>:<test> ...QemuLaunchSpecTest
# -> LAUNCH_SPEC_WITH_VM_HARDWARE_CONFIG_OK / ... / LAUNCH_SPEC_WITH_FULL_OPTIONS_OK

# Python 矩阵
python3 -m unittest tools/qemu-tuning/test_matrix.py -v
# -> Ran 11 tests ... OK
```

`QemuTuningTest` 覆盖 16 个场景：默认 47 项金样、旧 7 参构造器等价、CPU max、tb-size MiB、iothread、num-queues、aio=threads、discard、detect-zeroes、rng、overcommit、vCPU、默认无新参数、非法值（含 `aio=native`）、`-cpu`/`-accel` 不重复、`cache=unsafe` 禁止。

矩阵 dry-run：

```bash
python3 tools/qemu-tuning/matrix.py --dry-run --image ubuntu-24.04 \
  --output docs/reports/tickets/fastlane-P08/matrix-dry-run.tsv
# variants=18 workloads=6 rows=108
# measured_rows=0/108 status=not-tested
```

产物：[matrix-dry-run.tsv](matrix-dry-run.tsv)。18 变体 × 6 workload = 108 行，全部 `status=not-tested`，设备列 `-`。

## 6. 未验证 / not-tested

- **无真机**：未运行 adb、未启动 QEMU、未做 A/B；所有性能结论 `not-tested`。
- **无 QEMU 二进制 smoke boot**：iothread/num-queues 的 MMIO 行为、`virtio-rng-device` 的 guest 探测、`aio=io_uring` 在 Android seccomp 下是否可用，均只到源码/文档级证据。
- **`-cpu max` 未在目标 QEMU 构建上启动**；`sve=off` 是否真的关闭 SME 未测。
- **矩阵未采样本**：`n≥10`、控温、同镜像/资源的要求尚未执行；不得把 `not-tested` 写成通过。
- **未改默认**：`QemuTuning.DEFAULTS` 仍是 `cortex-a57`、无 `tb-size`、`cache=writeback`、TCP loopback passt、GPU 默认。

## 7. 风险与下一步

| 风险 | 说明 | 下一步 |
| --- | --- | --- |
| `-cpu max` 特性不稳定 | guest 可见特性随 QEMU 版本变化，不适合迁移/快照 | 仅实验；DV-03 固定 QEMU 版本 |
| iothread/num-queues 可能无收益 | 单 iothread 共享 AioContext；MMIO 无 irqfd | DV-03 分别测 iothread、num-queues、组合；n≥10 |
| `aio=io_uring` 可能被 seccomp 拦截 | Android 容器/seccomp 可能阻止 io_uring | 保留实验值；失败样本记录，不改默认 |
| `detect-zeroes=unmap` 单独无效 | QEMU 清掉 unmap hint | 只与 `discard=unmap` 组合解读 |
| `-overcommit mem-lock=off` 无变化 | QEMU 默认即 off | 不作为性能候选，仅作显式记录 |
| RNG 选 MMIO 而非票面 PCI | 与现有设备模型一致 | DV-03 若 guest 无 virtio-rng 驱动，记录并回退 |
| `qemu-tuned` 矩阵变多 | 108 行，设备窗口成本高 | DV-03 只挑 CPU/tb-size/iothread/num-queues 优先 |
| guest `mitigations=off` 未实现 | 会削弱 guest 安全，属于安全决策而非纯性能开关 | 另开安全评审票；不默认打开 |

## 8. 来源

- QEMU 11.1.1 源码：`accel/tcg/tcg-all.c`、`block/file-posix.c`、`block/io.c`、`hw/block/virtio-blk.c`、`include/hw/virtio/virtio-rng.h`、`target/arm/tcg/cpu64.c`（GitHub mirror `qemu/qemu` tag `v11.1.1`）。
- QEMU 手册：`-accel tcg,tb-size`、`-drive aio/discard/detect-zeroes`、`-overcommit mem-lock`、`-object rng-random`；ARM `virt` 机器与 CPU 特性文档。
- 本仓既有 T7：`docs/reports/tickets/runtime-performance-07/implementation-report.zh-CN.md`、`tools/qemu-tuning/matrix.py`。

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 真机与性能：`not-tested`。
