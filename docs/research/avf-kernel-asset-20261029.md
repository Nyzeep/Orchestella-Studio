# AVF 自定义内核资产评估（W-F · 会话 24 · 2026-10-29）

> **性质**：只出评估文档，不含任何产品代码改动。
> **回答的问题**：「我们自己的 AVF 内核资产到底能不能用？什么时候能用？」
> **范围**：`guest-assets/kernel/` 的构建配置、`app/src/main/assets/Image` 的实际二进制、`../Podroid/` 的反射先例、AOSP `packages/modules/Virtualization` 的固定源码。
> **本回合未做**：未跑 Gradle、未操作设备、未下载内核源码包、未 `git add/commit`、未改 `docs/research/` 之外的任何文件。
> **证据分级**：`A`＝本机实读的源码/二进制/文件；`B`＝本回合联网直读的上游源码（GitHub 上的 AOSP 模块镜像，固定分支）；`C`＝联网检索命中的官方文档正文（本环境无法直连 `*.google.com`/`*.googleapis.com`，只能取检索索引里的正文，见 §10.2）；`D`＝既有项目文档（引用不重测）。

---

## 0. 一页速览

| # | 问题 | 一句话答案 | 四态 |
| --- | --- | --- | --- |
| 1 | 当前主力机能不能跑我们的自定义内核 | **不能。** 是信任链问题，不是配置问题：protected pVM 的内核要过 pvmfw 里烧死的 AVB 公钥校验，我们的裸 `Image` 没有 vbmeta 签名，注定在 `verify_payload` 处失败 | **已验证** |
| 2 | 未来的非 protected 设备能不能 | **能，但全是条件。** `VirtualMachineCustomImageConfig` 接受任意 `kernelPath`/`initrdPath`，但需要「设备声明 non-protected + 有 `virtualizationservice` + 两个权限可授 + hidden API 豁免生效 + 真机 create/run 通过」五项同时成立 | **条件式** |
| 3 | 别人的反射代码能不能抄 | **能抄形态，不能抄结论。** `../Podroid` 的反射 shape 与 AOSP 固定源码逐方法对得上（仅 `setNetworkSupported` 是死名），但它自己也在 protected-only 设备上回退 QEMU，从未证明过自定义内核跑通 | **已验证（代码）/ 未验证（跑通）** |
| 4 | 风险有多大 | 平台耦合（`@hide`，无 API 稳定性承诺）、厂商后端差异、16K 页、无 pvmfw 度量后的安全语义、GPL 义务、**零真机分母** | **未验证** |

**给我们自己的资产下的判定**：内核资产本身**没有坏**——`Image` 是合法的 ARM64 raw 内核（magic `ARMd` @0x38，`Linux version 7.2.3`，SHA256 与随包清单一致），它现在就在给 QEMU 车道供货。**卡住它的从来不是这个二进制，而是它能被投递到哪条启动路径上。**

---

## 1. 结论摘要表（含四态与出处）

| 编号 | 结论 | 四态 | 关键出处 |
| --- | --- | --- | --- |
| C1 | protected-only 设备（当前主力机）**不可用**，且不可用原因是信任链而非配置 | 已验证 | `VirtualMachineConfig.java:1314-1326`；`guest/pvmfw/src/main.rs:57-58, 269-277`；`android/virtmgr/src/virtualmachine.rs:678-690`；会话 22/23 真机事实 |
| C2 | 非 protected 设备**可用（有条件）**：内核/initrd 任意，代价是失去 pvmfw 度量与内存隔离 | 条件式 | `VirtualMachineCustomImageConfig.java`（`setKernelPath`/`setInitrdPath`）；`android/android.system.virtualmachine.res/AndroidManifest.xml`；`android/virtmgr/src/crosvm.rs:269-292` |
| C3 | `../Podroid` 的反射先例**可复用**（shape 与 AOSP 一致），但不可当作"已验证可跑"的背书 | 已验证 / 未验证 | `AvfReflect.kt:27-34, 331-366, 521-526`；`AvfEngine.kt:975-990, 1019-1034, 1109-1125` |
| C4 | 风险清单 10 条，其中 6 条属"未验证"，2 条属"结构性缺陷"（本机无服务、库内无法复现 Image） | 未验证 | 见 §7 |

四态定义：
- **已验证**＝本机文件/二进制实读、或真机命令输出、或固定源码逐行核对，三者之一可复现；
- **条件式**＝源码/文档支持"路径存在"，但成立需要尚未取得的设备条件；
- **未验证**＝有实现或有说法，没有任何一次成功样本；
- **未取数**＝本轮没有取到这个数（不是"没有"，是"没测"）。

---

## 2. 资产盘点

### 2.1 目录实况（A 级）

`guest-assets/kernel/` 只有三个文本文件，共 240 行：

```
guest-assets/kernel/
├── droidlayer.fragment   75 行   1495 B
├── orchestella.fragment  75 行   1495 B
└── required.config       90 行   1972 B
```

**第一个发现：两个 fragment 逐字节相同**（`diff` 无输出）。而构建脚本只读其中一个：

```bash
# guest-assets/build.sh:72
KERNEL_FRAGMENTS=("$GUEST_ASSETS_DIR/kernel/droidlayer.fragment")
```

`orchestella.fragment` 在构建路径里**没有任何引用**。这不是"两份配置"，是"一份配置 + 一份同名副本"。任何只改 `orchestella.fragment` 的改动都不会进入内核——这是单一真相源缺失（记入 §7 R8）。

**第二个发现：`required.config` 不是"建议"，是硬门。** 构建流程是 `defconfig` → `merge_config.sh` 合入 fragment → `olddefconfig` → **逐项校验** → 才允许 `make Image`：

```bash
# guest-assets/build.sh:97-105
  ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- olddefconfig
java -jar "$TOOLS_JAR" verify-config \
  "$KERNEL_BUILD/.config" "$GUEST_ASSETS_DIR/kernel/required.config"
make ... -j "$MAKE_JOBS" Image
```

`guest-assets/README.md` 里写明负向构建会以 `CONFIG_EXT4_FS: expected y but was n` 失败且不产出 `Image`。也就是说：**fragment 决定"额外开什么"，`required.config` 决定"少一个就构建失败"。**

### 2.2 内核二进制到底在不在库

这是本轮最需要说清的一条，因为"资产在不在"直接决定"能不能用"。

| 位置 | 是否存在 | 说明 |
| --- | --- | --- |
| `guest-assets/out/Image` | **不存在**（目录为空） | 构建输出目录被 `.gitignore:12` 忽略；本机从未在此产出过 |
| `app/src/main/assets/Image` | **存在**，52,070,912 B（49.6 MiB） | 随 App 打包的内核；被 `.gitignore:23` 的 `/app/src/main/assets/*` 忽略，**git 未跟踪**（`git ls-files` 报 not known to git） |
| `app/src/main/resources/initramfs.cpio.gz` | **存在**，1,267,115 B | 同上，未跟踪 |

对本机 `Image` 做的最小取证（A 级）：

```
offset 0x38 (56)  →  41 52 4d 64        # "ARMd"，ARM64 raw Image 头魔数
字符串             →  "Linux version 7.2.3"
SHA256             →  d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5
随包清单           →  app/src/main/assets/runtime-artifacts-v1.sha256 第 1 行同值
```

结论：**内核二进制在库，但不是"guest-assets 构建产物"，而是"App 资产 + 未跟踪"**。它当前的消费者是 QEMU 车道（`guest-assets/README.md` 声明 `Image` 是 Linux 7.2.3 AArch64 内核，与 ADR-0005 的 pinned 共同内核一致）。对 AVF 而言，这份 raw `Image` **就是** `setKernelPath()` 最合适的候选输入——crosvm 要的正是 `ARM\x64` 裸镜像，`../Podroid` 甚至专门写了 `ensureRawKernel()` 做 gzip→raw 转换（`AvfEngine.kt:975-990`），而我们这份已经是 raw，省掉那一步。

**但对应关系不可证**：库内没有任何机制能证明这个二进制是由当前 `droidlayer.fragment` 构建出来的（`guest-assets/out/` 为空、无 `kernel.config`、`Image` 未跟踪、无 `BUILD-INPUTS` 随 App 发布）。记入 §7 R9。

**源码包可达性（B 级，本轮实测）**：`inputs/inputs.lock:7-9` 锁定 `linux-7.2.3.tar.xz`，`curl -I` 返回 `200 OK`、`Content-Length: 160,060,344`。**本轮未下载、未校验 SHA256** → 源码完整性标"未取数"。

### 2.3 fragment 逐项解读：这些配置开了什么、为什么这么开

`droidlayer.fragment` 75 行，按意图可分七组。右列"AVF 相关性"是本轮新增判断。

**A 组 · 启动与根文件系统（1-10 行）**

| 配置 | 作用 | 为什么必须开 | AVF 相关性 |
| --- | --- | --- | --- |
| `BLK_DEV_INITRD` + `RD_GZIP` | 支持 initramfs 且支持 gzip 压缩的 cpio | 我们的 `initramfs.cpio.gz` 是 gzip 压缩；不开 `RD_GZIP` 内核直接 panic | 必需。AVF 的 `initrdPath` 同样吃这个 initramfs |
| `BINFMT_ELF` | 执行 ELF | 没有它连 `/init` 都跑不起来 | 必需 |
| `BINFMT_SCRIPT` | `#!` 解释器 | initramfs 的 `/init`、OpenRC 脚本全是 shell 脚本 | 必需 |
| `DEVTMPFS` + `DEVTMPFS_MOUNT` | 内核自动挂 `/dev` | 无 udev 的最小 rootfs 靠它拿到 `/dev/console`、`/dev/vda` | 必需：`console=hvc0` 需要 `/dev/hvc0` 节点存在 |
| `PROC_FS` / `SYSFS` / `TMPFS` / `TMPFS_POSIX_ACL` | 标准伪文件系统 | systemd/OpenRC、cgroup v2、容器运行时都依赖 | 必需 |

**B 组 · virtio 传输（11-24 行）**

`VIRTIO` + `VIRTIO_PCI` + `VIRTIO_MMIO` + `VIRTIO_BLK` + `VIRTIO_NET` + `VIRTIO_CONSOLE` + `HW_RANDOM_VIRTIO` + `VIRTIO_BALLOON` + `VSOCKETS` + `VIRTIO_VSOCKETS` + `NET_9P` + `NET_9P_VIRTIO` + `9P_FS`(+`POSIX_ACL`)。

这一组是**crosvm 的设备模型全集**，不是 QEMU 专属：
- `VIRTIO_CONSOLE` → `hvc0`，这是 AVF 下真正的交互通道。`../Podroid` 的注释给出了量化理由（`AvfEngine.kt:1035-1043`）：crosvm 的 PL011 被限速到 ~14 KB/s，"a btop frame is 8–16 KB"，即每次刷新要 0.7–1 s；virtio-console 是宿主内存速度。
- `VSOCKETS` + `VIRTIO_VSOCKETS` → AVF 的控制面。AOSP 的 `MIN_VSOCK_PORT = 1024`（`VirtualMachine.java`），我们的 9100/9101 规划合规。
- `NET_9P*` + `9P_FS` → 共享目录。**注意：这里是 9p，不是 virtio-fs**（见 §2.4）。
- `VIRTIO_BALLOON` → AVF/`VirtualMachineConfig` 的内存气球（`useAutoMemoryBalloon`）。

**C 组 · 文件系统（25-31 行）**

`EXT4_FS`(+`POSIX_ACL`+`SECURITY`) 是持久 raw 根盘；`SQUASHFS`+`SQUASHFS_XZ` 是只读 rootfs 层；`OVERLAY_FS` 把两者叠成可写视图；`FUSE_FS` 给用户态文件系统。这一组是"App 私有 raw 盘 + 只读镜像"的产品形态在 guest 侧的必要条件。

**D 组 · cgroup v2 全家桶（32-49 行）**

`CGROUPS` 下挂了 14 个控制器：`BLK_CGROUP`、`CGROUP_SCHED`、`FAIR_GROUP_SCHED`、`CGROUP_PIDS`、`CGROUP_FREEZER`、`CGROUP_DEVICE`、`CPUSETS`、`CGROUP_CPUACCT`、`MEMCG`、`CGROUP_PERF`、`CGROUP_BPF`、`CGROUP_HUGETLB`、`CGROUP_RDMA`、`CGROUP_MISC`，外加 `CFS_BANDWIDTH` 和 `BPF`/`BPF_SYSCALL`。

判读：这是**容器运行时（podman/docker）的准入清单**，不是"跑个 shell"的需求。`CGROUP_MISC` 是较新内核才有的符号——它反过来证明 fragment 是按 pinned 的 7.2.3 写的，换成老内核（比如 6.1 GKI）这一行会被 `olddefconfig` 丢掉，而 `required.config` 里也有它，会导致构建**在配置期就失败**。这一点对"要不要改用设备 GKI 内核"有直接否决力（§6.4）。

**E 组 · namespace 全套（50-56 行）**

`NAMESPACES` + `UTS`/`IPC`/`USER`/`PID`/`NET`/`TIME`。`USER_NS` 是 rootless 容器的前提；`TIME_NS` 是较新符号。与 D 组合起来看，fragment 的目标不是"一个 Linux 终端"，而是"**能跑 rootless 容器的 Linux**"。

**F 组 · seccomp 与网络（57-72 行）**

`SECCOMP`+`SECCOMP_FILTER`；`NET`/`UNIX`/`INET`/`NETFILTER`/`NF_CONNTRACK`/`NF_TABLES`(+`INET`)/`NFT_CT`/`NFT_NAT`/`NF_NAT`/`TUN`/`VETH`/`BRIDGE`/`BRIDGE_NETFILTER`。

- `TUN` → rootless 用户态网络的 slirp/pasta 路径；
- `VETH`+`BRIDGE`+`NFT_*` → netavark/podman 的桥接与 NAT；
- `SECCOMP_FILTER` → 容器默认 seccomp profile。

**G 组 · 控制台（73-75 行）**

`SERIAL_AMBA_PL011` + `SERIAL_AMBA_PL011_CONSOLE`，`BUILD_SALT=""`。

关键判读：**PL011 是给 QEMU `virt` 机器和 `smoke` 接缝用的**（`required.config:88-90` 的注释直接写 "QEMU AArch64 boot console used by the provisioning smoke seam"）。在 AVF 下，crosvm 提供的是 virtio-console，PL011 只在 `earlycon` 阶段被折叠进同一个输出流（`../Podroid` 的 `AvfEngine.kt:1053-1058` 对此有实现级说明）。所以**fragment 不需要为 AVF 改动这一组**，但**启动参数必须改成 `console=hvc0`**——这是 runtime cmdline，不是编译期配置。

`BUILD_SALT=""` 是为了可复现构建（清空默认的随机 salt，让相同输入产出相同字节）。这与 `guest-assets/README.md` 的"两次 clean build 字节比对"配套。

### 2.4 缺项（与 AVF 需求对照）

| 缺失 | 后果 | 状态 |
| --- | --- | --- |
| 无 `CONFIG_IKCONFIG` | 无法从 `Image` 反查最终 `.config`；本机 `grep -c "CONFIG_ARM64_4K_PAGES=y" Image` 返回 **0** | 已实读 |
| 无显式 `CONFIG_ARM64_*_PAGES` | 页大小交给 arm64 `defconfig` 默认（4K）。**推论**，非实测 | 未取数 |
| 无 `CONFIG_VIRTIO_FS` | 只能用 9p 共享；TerminalApp/Podroid 用的是 virtio-fs `sharedPath`。9p 在 AVF 下的可用性未验 | 未验证 |
| 无 `CONFIG_SECURITY_SELINUX`、无 Landlock | 与 `ACR-draft-four-lanes-20261029.md` §7.3 的实测吻合：guest `/sys/kernel/security/lsm` 只有 `capability`，pacman 7 因无 Landlock 报 `restricting filesystem access failed`。**这是对既有实测的机制解释，不是新实测** | 已验证（对照） |
| 无 `CONFIG_MODULES` | 全部内建，符合 ADR-0005 的 "Every entry must be built in, not a module" | 已验证 |

---

## 3. 结论一：protected-only 设备不可用（已验证）

### 3.1 三道门，一道比一道硬

当前主力机的模式声明（会话 22/23 事实，D 级引用）：

- `ro.boot.hypervisor.protected_vm.supported = true`
- `ro.boot.hypervisor.vm.supported` = **空**
- `service list`（537 项）中**没有 `virtualizationservice`**
- `pm grant ... MANAGE_VIRTUAL_MACHINE` 可执行且 `granted=true`，**但无效**
- 真实 AVF Guest 成功样本 = **0**

这三条属性/服务不是同一层的东西，必须分开说（`libs/hypervisor_props/src/lib.rs:21-34`，B 级）：

```rust
pub fn is_vm_supported() -> Result<bool> {
    Ok(hypervisorproperties::hypervisor_vm_supported()?.unwrap_or(false))
}
pub fn is_protected_vm_supported() -> Result<bool> {
    Ok(hypervisorproperties::hypervisor_protected_vm_supported()?.unwrap_or(false))
}
```

- **第 0 道门（服务）**：没有 `virtualizationservice`，framework 层连 `VirtualMachineManager` 都拿不到，`create()` 无从谈起。会话 23 已确认这不是权限问题——权限授予成功但服务不存在。这道门比下面两道更早，且本轮**无法**通过任何客户端手段绕开。
- **第 1 道门（模式位）**：`setProtectedVm(false)` 在属性为 false 时**直接抛异常**（`VirtualMachineConfig.java:1314-1326`，B 级）：

```java
public Builder setProtectedVm(boolean protectedVm) {
    if (protectedVm) {
        if (!HypervisorProperties.hypervisor_protected_vm_supported().orElse(false)) {
            throw new UnsupportedOperationException("Protected VMs are not supported on this device.");
        }
    } else {
        if (!HypervisorProperties.hypervisor_vm_supported().orElse(false)) {
            throw new UnsupportedOperationException("Non-protected VMs are not supported on this device.");
        }
    }
    ...
```

也就是说，**在 protected-only 设备上，non-protected 的 `VirtualMachineConfig` 根本构造不出来**。这不是"配错了参数"，是框架在 builder 里就把路封了。

- **第 2 道门（来源 label）**：即使绕过第 1 道，virtmgr 的 protected 分支还会校验 kernel/initrd 的 SELinux label（`android/virtmgr/src/virtualmachine.rs:678-690`，B 级）：

```rust
if config.protectedVm {
    check_protected_vm_is_supported()?;
    // In a protected VM, we require custom kernels to come from a trusted source
    // (b/237054515).
    check_label_for_kernel_files(kernel.as_ref(), initrd.as_ref(), calling_partition)
        .or_service_specific_exception(-1)?;
```

而允许列表显式排除了 `app_data_file`（`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §2.1 已逐行核对，D 级）。我们所有资产都在 App 私有目录，直接不满足。

- **第 3 道门（信任链，唯一不可绕）**：见 §3.2。

### 3.2 为什么这是信任链问题，而不是配置问题

判据一句话：**开关在属性里，钥匙在 pvmfw 里。属性可以改，钥匙拿不到。**

`guest/pvmfw/src/main.rs:57-58`（B 级）：

```rust
/// Trusted public key, used during verification of the signed kernel & ramdisk.
const PUBLIC_KEY: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/pvmfw_embedded_key_pub.bin"));
```

`guest/pvmfw/src/main.rs:269-277`（B 级）：

```rust
fn perform_verified_boot<'a>(signed_kernel: &[u8], ramdisk: Option<&[u8]>)
    -> Result<(VerifiedBootData<'a>, bool, usize), RebootReason> {
    let verified_boot_data = verify_payload(signed_kernel, ramdisk, PUBLIC_KEY).map_err(|e| {
        error!("Failed to verify the payload: {e}");
        RebootReason::PayloadVerificationError
    })?;
    ...
```

`guest/pvmfw/README.md`（B 级，原文）：

> pvmfw becomes the very first code that gets executed in the pVM, allowing it to validate the environment and abort the boot sequence if necessary. This process takes place whenever the VMM places a VM in protected mode and can't be prevented by the host.
> […] If any operation or check fails, or in case of a missing prerequisite, pvmfw will abort the boot process of the pVM, effectively preventing non-compliant pVMs and/or guests from running.

三个推论，逐条对应我们的资产：

1. **我们的 `Image` 是裸镜像，不是 AVB 签名镜像。** 头部就是 `ARMd`，没有 vbmeta 结构、没有 rollback index、没有 page_size 元数据。`verify_payload(signed_kernel, ...)` 拿到它必然返回 `PayloadVerificationError`。
2. **要过这道门，必须用设备 pvmfw 里那把公钥对应的私钥签名。** AOSP 默认是 `avb_testkey_rsa4096`（既有研究已核，D 级），OEM 通常替换成自己的；我们拿不到 OEM 私钥，改 `pvmfw.img` 又要解锁 bootloader 且破坏 Verified Boot（本机 `flash.locked=1`、`VerifiedBoot green`）。
3. **即使签名通过，vbmeta 里还要带对得上的 device tree / page_size / rollback 信息**——`main.rs:282` 的 `guest_page_size = verified_boot_data.page_size.unwrap_or(SIZE_4KB)` 说明这些不是可选项，是启动参数源。

**所以"C1 不可用"的准确表述是**：不是"我们没配对"，是"我们的镜像不是由被信任的私钥签出来的"。配置层能改的（`protected: false`、debug level、内存、CPU 拓扑）全都不触及这条链；改属性只会让 VM 更早失败或换一种失败。

**同样重要的反向结论**：这也**不是硅层判决**。同一颗 SoC 换一个 ROM 声明 `ro.boot.hypervisor.vm.supported=true`，non-protected 路径就可能开放（`docs/research/2026-10-17-avf-custom-linux-route-study.md` §1 已论证过"不能合并成一个芯片支持/不支持标签"）。所以问题不是"这块芯片不行"，是"这台 ROM 只卖了 protected 这一种"。

### 3.3 信任链原理图

```
                    ┌───────────────────── protected pVM 路径（当前设备唯一路径）─────────────────────┐
                    │                                                                                  │
  ROM / ABL ──► pvmfw.img（ABL 校验链式分区）──► hypervisor 把 pvmfw 载入受保护内存                    │
                    │        │                                                                          │
                    │        └─ pvmfw 内嵌 PUBLIC_KEY（编译期烧死，运行期不可改）                        │
                    │                 │                                                                 │
                    │                 ├─ verify_payload(我们的 Image) ──► 失败：无 vbmeta 签名            │
                    │                 │                                   → RebootReason::PayloadVerificationError
                    │                 │                                   → VM 根本不起                  │
                    │                 └─ 通过后 → DICE 度量链 → 派发 secret → 跳入 guest kernel              │
                    │                                                                                  │
                    └──────────────────────────────────────────────────────────────────────────────────┘

                    ┌───────────────────── non-protected 路径（本机没有）───────────────────────────────┐
                    │  virtmgr ──► crosvm                                                                 │
                    │     └─ add_pvm_arg(): 只有 config.protectedVm 才加 --protected-vm*                   │
                    │        non-protected 分支最多加 --unprotected-vm-with-firmware(customPvmfw)          │
                    │        → 无 pvmfw、无 DICE、无 AVB 度量                                              │
                    │     └─ 直接把 Image / initrd / disk 交给 crosvm 启动                                 │
                    │        → 任意内核可进（这是 C2 成立的技术根据）                                      │
                    └──────────────────────────────────────────────────────────────────────────────────┘
```

上图的 non-protected 分支对应 `android/virtmgr/src/crosvm.rs:269-292`（B 级）：

```rust
fn add_pvm_arg(&mut self, context: &RunContext) -> Result<()> {
    if !context.config.protectedVm {
        if let Some(custom_pvmfw) = &context.config.customPvmfw {
            ... self.arg("--unprotected-vm-with-firmware").arg(&custom_pvmfw_fd);
        }
        return Ok(());
    }
    ...
```

`return Ok(())` 之前没有任何度量调用——这就是"非 protected = 无 pvmfw 度量"的源码位置。

---

## 4. 结论二：非 protected 设备可用（条件式）

### 4.1 接口事实

`VirtualMachineCustomImageConfig`（B 级，AOSP 模块固定分支实读）关键形态：

```java
/** @hide */
public class VirtualMachineCustomImageConfig {
    @Nullable private final String kernelPath;
    @Nullable private final String initrdPath;
    @Nullable private final String bootloaderPath;
    @Nullable private final String[] params;
    @Nullable private final Disk[] disks;
    @Nullable private final SharedPath[] sharedPaths;
    ...
    public Builder setKernelPath(String kernelPath) { ... }
    public Builder setInitrdPath(String initrdPath) { ... }
    public Builder addParam(String param) { ... }
    public Builder addDisk(Disk disk) { ... }
    public Builder useNetwork(boolean network) { ... }
    public Builder setGpuConfig(GpuConfig gpuConfig) { ... }
```

`setKernelPath`/`setInitrdPath` 的语义就是**路径字符串**：宿主侧文件的绝对路径，由 virtmgr 打开成 FD 后交给 crosvm。没有格式校验、没有签名要求、没有白名单（non-protected 分支不做 label 检查）。`kernelPath` 与 `bootloaderPath` 是互斥的两条启动方式：前者是 Linux 直接引导（我们走这条），后者是 U-Boot 一类引导器（lfdevs 教程那条）。

### 4.2 Android API 级别门槛

| 事实 | 出处 | 等级 |
| --- | --- | --- |
| `android.system.virtualmachine.*` 这组 API **自 API 34（Android 14）引入**；都是 `@SystemApi`；都需要 `MANAGE_VIRTUAL_MACHINE` | AOSP `libs/framework-virtualization/README.md` | C |
| 该权限在 Android 14 只给特权应用，Android 15 起给所有预装应用；**两个版本都可以用 `adb shell pm grant` 授予第三方应用做开发** | 同上 | C |
| `VirtualMachineManager`/`VirtualMachineConfig`/`VirtualMachine` 在 **API 34** 进入 system API 面 | 同上 | C |
| **`VirtualMachineCustomImageConfig` 不在 API 面里**：类与全部方法都是 `/** @hide */`，我在该分支的 `api/system-current.txt`、`api/test-current.txt` 里 grep `VirtualMachineCustomImageConfig` 均为 **0 命中** | AOSP 模块 `api/*.txt` | A |
| `VirtualMachineConfig.Builder.setCustomImageConfig` 同样是 `@hide` + `@RequiresPermission(USE_CUSTOM_VIRTUAL_MACHINE)`，**不是** `@SystemApi`，也不在 `test-current.txt` | `VirtualMachineConfig.java`（方法注释块） | A |

**这是一条容易被误读的分界**：README 说"全部 @SystemApi"，指的是 `VirtualMachineManager` 那一组；**真正承载自定义内核的那个类被标成 `@hide`**，连 SystemApi 都不是。所以：

- 编译期拿不到（SDK stub 里没有）；
- 运行期要 `Class.forName` + `setAccessible(true)`；
- 在 Android 9+ 的 hidden API 限制下，**必须**先做豁免，否则 `Class.forName` 直接抛 `ClassNotFoundException` 或返回不可用。

### 4.3 权限门：`USE_CUSTOM_VIRTUAL_MACHINE` 能不能 adb 授予

权限声明的原文（B 级，`android/android.system.virtualmachine.res/AndroidManifest.xml`）：

```xml
<!-- @hide Allows an application to run a Virtual Machine with a custom
     kernel or a Microdroid configuration file.
     <p>Not for use by third-party applications. -->
<permission android:name="android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    android:protectionLevel="signature|development" />
```

对比同文件里的另外两个：

```xml
<permission android:name="android.permission.MANAGE_VIRTUAL_MACHINE"
    android:protectionLevel="signature|preinstalled|development" />
<permission android:name="android.permission.DEBUG_VIRTUAL_MACHINE"
    android:protectionLevel="signature" />
```

判定：**可以 adb 授予。** 依据是 `development` 这个 flag 的语义——带它的权限不会被自动授予，但**允许通过 `pm grant` 由 shell 授予**（AOSP 自己的 `docs/custom_vm.md` 就是这么用的：`adb shell su root pm grant ... android.permission.USE_CUSTOM_VIRTUAL_MACHINE`）。本轮核到的旁证：`development` flag 使权限可经 `pm grant` 授予，见 Stack Overflow 对该 flag 的解读与一篇 Android 权限体系论文（C 级，§10.2 第 6/7 条）。

**与本机实测的关系**：会话 22/23 实测的是 `MANAGE_VIRTUAL_MACHINE`（同为 `signature|...|development`），`granted=true` 但**不生效**——原因已定位为"服务不存在"，而不是"授权失败"。所以"adb 授予可行"这一条在本机是**已验证**的（对 MANAGE 而言），但"授予后能创建 VM"是**未验证**（本机永不具备）。`USE_CUSTOM_VIRTUAL_MACHINE` 本身在真机上**从未授予过** → 未取数。

### 4.4 hidden API 绕过

三个已经在仓库里的先例，按可信度排：

1. `../Podroid` 的 `AvfReflect.kt:5-12` 头注释直接写明："Each call is one Method.invoke with setAccessible(true) — **Android 14+ requires HiddenApiBypass (installed at app onCreate)** for these to resolve."
2. `docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §2.3 记录了 TerminalAppPlus 在 targetSdk 37 上使用 `hiddenapibypass:6.1` + `addHiddenApiExemptions("Landroid/system/", ...)` 的存在性证据。
3. 同一节也记录了反例：Android 16 的 ART 变更曾让旧版 HiddenApiBypass 在 `setHiddenApiExemptions` 崩溃（LSPosed issue #52）。

**注意 `../Podroid` 自身的一个缝**：`AvfReflect` 全文件只有 `Class.forName`，没有安装豁免的代码——它把这件事推给了 App 层。我们要照抄的话，**豁免必须装在 `Application.attachBaseContext`**，且必须早于任何 AVF 类加载，否则 `ReflectiveAvfVirtualMachineApi.loadShape()` 第一次 `classLoader.loadClass(...)` 就会失败。

### 4.5 "非 protected"的语义代价

这一条不能含糊，因为它直接决定产品文案：

| 维度 | protected pVM | non-protected custom VM |
| --- | --- | --- |
| pvmfw | 有，是 VM 的第一条指令 | **无**（`crosvm.rs:270-274` 里 non-protected 分支直接 `return Ok(())`） |
| DICE 度量链 | 有，从 ROM 到 pvmfw 到 guest | **无**，无任何 attestation 根 |
| 宿主能否读 guest 内存 | 不能（hypervisor 强制隔离） | **能**（就是普通进程内存） |
| 内核完整性 | AVB 签名 + 回滚保护 | 无，谁写谁生效 |
| 我们的自定义内核能否进 | 不能（§3） | **能** |

所以：**"非 protected"不是"差一点的隔离"，是"没有隔离"**。任何"硬件隔离""安全沙箱""宿主看不到 guest"的说法在这条路径上都是错的，写进 README 会变成虚假宣传。这一条与 `ACR-draft-four-lanes-20261029.md` §5.1 第 199 行的文案禁令同源（UML 不得写 protected/硬件隔离），但**AVF non-protected 同样适用**。

### 4.6 条件清单（缺一不可）

| # | 条件 | 当前状态 |
| --- | --- | --- |
| 1 | 设备 `ro.boot.hypervisor.vm.supported=true`（或 `getCapabilities() & 2 != 0`） | 本机 **false** → 无设备 |
| 2 | `virtualizationservice` 已注册 | 本机 **不存在**（537 服务里没有） |
| 3 | `MANAGE_VIRTUAL_MACHINE` + `USE_CUSTOM_VIRTUAL_MACHINE` 均可授且生效 | 前者本机 granted 但无效；后者未试 |
| 4 | hidden API 豁免生效，`VirtualMachineCustomImageConfig` 可加载 | 本机 App 进程内被拒（前文 D 级记录） |
| 5 | 真机 `create` → `run` → vsock 握手 → `stop` 全绿 | **0 次** |

**五项全空的结论只能是"条件式"**，不能升级为"可用"。

---

## 5. 结论三：反射先例摘录（可复用）

### 5.1 `../Podroid/.../AvfReflect.kt` — 拿 service 与建 shape

```kotlin
// AvfReflect.kt:26-34
private const val PKG = "android.system.virtualmachine"
private val MGR by lazy { Class.forName("$PKG.VirtualMachineManager") }
private val CFG by lazy { Class.forName("$PKG.VirtualMachineConfig") }
private val CFG_B by lazy { Class.forName("$PKG.VirtualMachineConfig\$Builder") }
private val CUSTOM by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig") }
private val CUSTOM_B by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Builder") }
private val DISK by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Disk") }.getOrNull() }
private val GPU by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig") }.getOrNull() }
```

```kotlin
// AvfReflect.kt:36-39  —— 不反射 getSystemService，而是直接用 Context 的公开方法
fun manager(ctx: Context): Any {
    val m = Context::class.java.getMethod("getSystemService", Class::class.java)
    return m.invoke(ctx.applicationContext, MGR) ?: error("No VirtualMachineManager")
}
```

**这里有一个值得抄的设计**：`getSystemService(Class)` 是公开 API，不需要豁免；只有参数里的 `Class` 是反射拿到的。这把"必须豁免"的范围压到了最小。

### 5.2 自定义镜像的组装（可复用的最小序列）

```kotlin
// AvfReflect.kt:331-342
fun setCustomImageConfig(b: Any, cfg: Any) {
    invokeDecl(b, "setCustomImageConfig", CUSTOM to cfg)
}
fun newCustomBuilder(): Any =
    CUSTOM_B.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
fun setName(b: Any, name: String) { invokeDecl(b, "setName", String::class.java to name) }
fun setKernelPath(b: Any, p: String) { invokeDecl(b, "setKernelPath", String::class.java to p) }
fun setInitrdPath(b: Any, p: String) { invokeDecl(b, "setInitrdPath", String::class.java to p) }
```

```kotlin
// AvfReflect.kt:343-360
fun addParams(b: Any, params: String) {
    val tokens = params.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val m = b.javaClass.getDeclaredMethod("addParam", String::class.java).apply { isAccessible = true }
    for (t in tokens) m.invoke(b, t)
}
fun addDisk(b: Any, path: String, writable: Boolean) {
    val diskCls = DISK ?: error("VirtualMachineCustomImageConfig\$Disk class not found on this device")
    val factoryName = if (writable) "RWDisk" else "RODisk"
    val factory = diskCls.getDeclaredMethod(factoryName, String::class.java).apply { isAccessible = true }
    val disk = factory.invoke(null, path)
    val addM = b.javaClass.getDeclaredMethod("addDisk", diskCls).apply { isAccessible = true }
    addM.invoke(b, disk)
}
```

```kotlin
// AvfReflect.kt:521-526  —— 全文件唯一的调用原语
private fun invokeDecl(target: Any, name: String, vararg typedArgs: Pair<Class<*>, Any?>): Any? {
    val argTypes = typedArgs.map { it.first }.toTypedArray()
    val argVals = typedArgs.map { it.second }.toTypedArray()
    val m = target.javaClass.getDeclaredMethod(name, *argTypes).apply { isAccessible = true }
    return m.invoke(target, *argVals)
}
```

### 5.3 降级策略（这是最值得抄的一段）

```kotlin
// AvfReflect.kt:128-136
fun applyProtectedVm(mgr: Any, builder: Any): AvfCapabilities.ProtectedVmChoice {
    val choice = AvfCapabilities.choose(getCapabilities(mgr))
    when (choice) {
        is AvfCapabilities.ProtectedVmChoice.NonProtected -> setProtectedVm(builder, false)
        is AvfCapabilities.ProtectedVmChoice.Unknown      -> setProtectedVm(builder, false)
        is AvfCapabilities.ProtectedVmChoice.Unsupported  -> Unit
    }
    return choice
}
```

```kotlin
// AvfCapabilities.kt:44-52
fun choose(capabilities: Int): ProtectedVmChoice = when {
    capabilities == 0 -> ProtectedVmChoice.Unknown
    capabilities and CAPABILITY_NON_PROTECTED_VM != 0 -> ProtectedVmChoice.NonProtected
    capabilities and CAPABILITY_PROTECTED_VM != 0 -> ProtectedVmChoice.Unsupported(
        "This device's hypervisor only supports protected VMs; AVF can't run " +
            "Podroid's custom Linux image here. Falling back to QEMU."
    )
    else -> ProtectedVmChoice.Unknown
}
```

调用点（`AvfEngine.kt:1109-1125`）：`Unsupported` → `throw UnsupportedOperationException(choice.reason)`，上层据此回退 QEMU。**这是整条先例里唯一有"行为契约"的部分**：能力位 → 三分支 → 明确的降级动作。

注意 `Unknown` 分支**故意不捕获** `setProtectedVm(false)` 的异常（`AvfReflect.kt:118-127` 的注释解释得很清楚）：在 protected-only 设备上会抛 `UnsupportedOperationException("Non-protected VMs are not supported on this device.")`，这是**真实信号**，不能吞。我们自己的实现如果吞掉，就会把"设备只支持 protected"误报成"模式未知"——这正是既有审计里 `AvfHostFactsProbe` 的问题类型（P1）。

### 5.4 与 AOSP 定义的一致性核对

比对基准：AOSP `packages/modules/Virtualization` 的 `libs/framework-virtualization`（固定分支镜像，B 级）。逐方法核：

| Podroid 调用 | AOSP 是否存在 | 结论 |
| --- | --- | --- |
| `VirtualMachineManager.getOrCreate/create/delete/getCapabilities` | 存在 | 一致 |
| `VirtualMachineConfig.Builder.setProtectedVm/setMemoryBytes/setCpuTopology/setDebugLevel/setConsoleInputDevice/setConnectVmConsole/setVmOutputCaptured/build` | 全部存在（逐个 grep 命中 1 次） | 一致 |
| `VirtualMachineConfig.Builder.setCustomImageConfig` | 存在（`@hide` + `@RequiresPermission`） | 一致 |
| `VirtualMachineCustomImageConfig.Builder.setName/setKernelPath/setInitrdPath/addParam/addDisk/useNetwork/build` | 全部存在 | 一致 |
| `VirtualMachineCustomImageConfig.Disk.RWDisk(String)/RODisk(String)` | 存在（静态工厂） | 一致 |
| `Builder.setNetworkSupported(boolean)` | **不存在**（该分支 `VirtualMachineConfig.Builder` 里 grep 为 0；网络开关是 custom image 的 `useNetwork`） | **不一致（死名）** |

关于 `setNetworkSupported`（`AvfReflect.kt:362-366`）：它先试 `useNetwork`、失败再试 `setNetworkSupported`。调用点是 `AvfEngine.kt:1094` 的 `setNetworkSupported(cb, true)`，`cb` 是 **custom image builder**，所以第一个名字必然命中，第二分支永远不会走。**这是防御性冗余，不是 bug**，但它说明"双名兼容"这种写法不能当作"跨版本兼容"的证据——AOSP 从来没有第二个名字。

另有一处需要点明：`../Podroid` 的 `setCpuTopology` 只有 `ONE_CPU`/`MATCH_HOST` 两个值，精确核数要靠 Android 16+ 的 raw AIDL proxy 改写（`AvfReflect.kt:161-299`）。我们的引擎走 1 vCPU（`ReflectiveAvfVirtualMachineApi` 用 `CPU_TOPOLOGY_ONE_CPU`），所以这条复杂路径**我们不需要**。

### 5.5 关于 `phase5-references` 的如实说明

任务给的参考位置 `../phase5-references/` 里**没有 AVF 源码快照**。实查（A 级）：该目录下是 `crosvm`、`qemu`、`podman`、`niri`、`kwin`、`noVNC` 等，`find` 不到 `VirtualMachineCustomImageConfig.java`、`VirtualMachineConfig.java`、`virtualizationservice`。

因此本轮**无法**做"与 phase5 快照逐字比对"，改用两条替代路径：
1. AOSP 模块的 GitHub 镜像固定分支（本回合直读，B 级）；
2. 项目内已有的 AOSP 引用（`docs/research/microdroid-exec-selinux-20261022.zh-CN.md` §5.1 的 API 行号表、`2026-10-19-avf-reachability-corrections.zh-CN.md` §2.1 的 virtmgr 行号）。

**这也意味着"pinned 上游快照"这个说法对 AVF 模块目前不成立**——记入 §9。

---

## 6. 重建路径（要重编内核时怎么做）

### 6.1 为什么本机做不了

- `guest-assets/build.sh` 需要：`aarch64-linux-gnu-` 交叉工具链、GNU make、bash、Unix 符号链接/设备节点、`tar`/`xz`/`bzip2`；产物 `Image` 是 Linux 内核构建体系的输出，Windows 宿主无法直接产出。
- 官方入口是容器：`./guest-assets/run-in-builder.sh build`，跑 `linux/amd64` 的固定 digest 镜像（`inputs/inputs.lock:2-3`）。
- 本机 **WSL 被安全策略禁用**，Docker/Podman 也不在可用工具里 → **重编必须在外部 Linux 环境**（Ubuntu 24.04 裸机/VM，或一台有 Docker 的机器）。这一点无需争论，是环境事实。

### 6.2 标准步骤（照 `build.sh` 实际流程，不是想象）

```bash
# 0) 外部 Linux 宿主（x86_64，Ubuntu 24.04）
git clone <仓库> && cd <仓库>
guest-assets/ci/install-builder-dependencies.sh      # 装交叉工具链与依赖

# 1) 一条命令（内部会做两次 clean build 字节比对 + QEMU smoke）
guest-assets/run-in-builder.sh verify
# 或直接：guest-assets/ci/run.sh

# 2) 只出内核
guest-assets/run-in-builder.sh build
```

内部实际发生的九步（`build.sh:55-105`）：
1. `download_checked`：下载 linux/busybox/musl/e2fsprogs 四个归档，**先校验 SHA256 再解包**；
2. `gradlew -p guest-assets jar` 产出 `guest-assets-tools.jar`（含 `verify-config`）；
3. 解包内核源码到工作目录；
4. `make O=<build> ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- defconfig`；
5. `scripts/kconfig/merge_config.sh -m -O <build> .config kernel/droidlayer.fragment`；
6. `make ... olddefconfig`；
7. `java -jar guest-assets-tools.jar verify-config .config kernel/required.config`（不过就停）；
8. `make ... Image`（带 `-fdebug-prefix-map` 保证路径无关、可复现）；
9. 产物拷到 `guest-assets/out/`：`Image`、`initramfs.cpio.gz`、`SHA256SUMS`、`kernel.config`、`busybox.config`、`BUILD-INPUTS`、GPL 材料。

### 6.3 为 AVF 投递需要额外做什么（增量，不是现成路径）

| 增量 | 是否需要重编 | 说明 |
| --- | --- | --- |
| 启动参数 `console=hvc0` | **不需要** | 运行时 cmdline，经 `addParam` 传入；fragment 已有 `VIRTIO_CONSOLE` |
| 去掉 `root=/dev/ram0` 之类 QEMU 专有参数 | 不需要 | 同上 |
| 把 kernel/initrd 放进 App 可访问路径并保证 label 可接受 | 不需要 | non-protected 不校验 label；protected 必失败（§3） |
| 16K 页宿主支持 | **需要**（若目标设备是 16K） | `CONFIG_ARM64_16K_PAGES`（AOSP 16KB 文档原文：arm64 直接改内核配置时"設定 `CONFIG_ARM64_16K_PAGES`（而非 `CONFIG_ARM64_4K_PAGES`）"）；同时 initramfs 里的 musl/busybox 要按 16K 重编 |
| virtio-fs 共享 | 需要（若放弃 9p） | 加 `CONFIG_VIRTIO_FS`；当前 fragment 只有 9p |
| AVB 签名（protected 才需要） | 需要 | 本轮不做，见 §8 |

### 6.4 一条否决性约束：不要换用设备 GKI 内核

有人会问"能不能直接用手机自带的 GKI 内核当 AVF guest 内核"。技术上不能，原因在配置契约而不是签名：

- 设备 GKI 是**给宿主**编的：没有 `VIRTIO_*`（宿主不需要 virtio 设备模型）、可能没有 `9P_FS`、通常 `CONFIG_MODULES=y` 而我们的契约要求全内建；
- 反向也不行：把我们的 fragment 套到 6.x GKI 上，`CONFIG_CGROUP_MISC`、`CONFIG_TIME_NS` 这类较新符号会被 `olddefconfig` 丢掉，然后 **`required.config` 校验直接失败**（`build.sh:98`）。这是硬失败，不是警告。

结论：**AVF guest 内核必须是自编的**，这恰好也是我们的资产唯一不可替代的价值。

---

## 7. 风险与未验证项

| ID | 风险 | 影响 | 状态 |
| --- | --- | --- | --- |
| R1 | **`@hide` 平台耦合 + Android 版本漂移**：`VirtualMachineCustomImageConfig` 不在任何 API 清单里，字段逐版增加（当前分支已有 `osName`、`sharedPaths`、`usbConfig`、`audioConfig`，早期版本没有） | 每次 Android 大版本都可能改名/改签名；Android 16 的 ART 变更已实际打碎过一次 HiddenApiBypass | 已验证（存在性）/ 未验证（新版行为） |
| R2 | **厂商后端差异**：本机 `/dev/gunyah` 存在、`/dev/kvm` 不存在；`crosvm.rs` 有 `--protected-vm-without-firmware`、`customPvmfw` 等分叉 | non-protected 是否开放、走哪条 crosvm 参数，逐设备不同；MediaTek GenieZone（`/dev/gzvm`）与高通 Gunyah 必须分开验 | 未验证 |
| R3 | **16K 页与 virtio 配置匹配**：guest 4K 内核 + 16K 宿主；pvmfw 从 vbmeta 取 page_size 且**默认 4K**（`main.rs:282`）；virtmgr 的 swiotlb 估算**硬编码 4096 并标了 TODO**（`crosvm.rs:717`） | 16K 设备上大概率能启动但内存布局/balloon/console 队列尺寸可能算错；我们从未在 16K 设备上跑过 | 未验证（且上游自己标了 TODO） |
| R4 | **无 pvmfw 度量后的安全语义** | non-protected 无 DICE、无 AVB、宿主可读 guest 内存。产品文案不得出现"隔离/硬件安全" | 已验证（源码）/ 需在文案层强制 |
| R5 | **GKI 合规与 GPL 义务** | `Image` 是 GPL-2.0-only WITH Linux-syscall-note；随包已有 `GPL-SOURCES.md`、`SOURCE-OFFER.txt`、`LICENSE.GPL-2.0`。若改由 AVF 分发同一 `Image`，对应源码义务不变；AVB 签名后的镜像仍是"目标代码"，提供义务不消失 | 已验证（材料齐备）/ 未验证（AVF 形态下的再分发流程） |
| R6 | **没有真机分母** | 真实 AVF Guest 成功样本 **0**；non-protected 设备 **0 台**；`USE_CUSTOM_VIRTUAL_MACHINE` **0 次授予**。任何"可用"的说法都缺样本 | 未验证（结构性空洞） |
| R7 | **本机无 `virtualizationservice`** | 比 protected 更早的门：连 `VirtualMachineManager` 都拿不到。会话 23 已确认为服务缺失而非权限问题 | 已验证（真机事实） |
| R8 | **fragment 双份且只有一份生效** | 改 `orchestella.fragment` 不生效，静默漂移 | 已验证（`diff` 相同 + `build.sh:72` 只读 droidlayer） |
| R9 | **`Image` 无法从库内复现** | `guest-assets/out/` 空、`Image` 未跟踪、无随包 `kernel.config`/`BUILD-INPUTS`；只能证明"SHA256 与清单一致"，不能证明"由当前 fragment 构建" | 已验证（现状）/ 未取数（对应关系） |
| R10 | **vsock 端口下限** | AOSP `MIN_VSOCK_PORT = 1024`，低端口被 virtmgr 拒绝；我们规划 9100/9101 合规 | 已验证（源码） |

关于 R6 的诚实补充：本轮的"条件式"判定**不是**在赌"以后会好"，而是把结论钉在"源码允许 + 设备不允许"这个落差上。落差本身是稳定的（一年多来没变），所以结论可以写死；但**任何"什么时候能用"的时间点，本报告不给**——给时间点就是编数据。

---

## 8. 发布位建议（本轮不实现）

### 8.1 明确不做的事

- **不新增 CUSTOM_VM 后端**。仓库里已有一套反射实现（`runtime/engine-avf/.../ReflectiveAvfVirtualMachineApi.java:171` 的 `setCustomImageConfig`、`:535-560` 的 `loadShape`），它属于"代码就绪、设备未验"状态，本轮**不动它、不推广它、不把它写进任何用户可见的能力声明**。
- 不因为这份评估去改 README、`build.gradle.kts`、AGENTS.md。README 的实际修改由另一位同事执行。

### 8.2 建议的 README 措辞（给同事直接用）

现状（`README.md:134`，Roadmap 第 4 条）已经写了一版：

> **AVF custom kernel (evaluate, do not implement)** — only meaningful on non-protected devices: pvmfw starts Google/OEM-signed images only (`Available OS list: ["microdroid"]`), so protected devices cannot run a custom kernel. This round is a feasibility assessment with no implementation commitment.

这句话**方向对，但把原因说窄了**：真正拦人的不是 `Available OS list`（那是 Microdroid payload 的枚举），是 pvmfw 内嵌公钥的 AVB 校验。建议改成：

> **AVF custom kernel (research direction, non-protected devices only)** — our pinned guest `Image` can only be booted by AVF as a **non-protected** VM. On a protected-only device the kernel must pass the AVB signature check against the public key compiled into `pvmfw`; an unsigned raw `Image` cannot, and this is a chain-of-trust gate, not a configuration flag. No non-protected device is available, so there is **zero** on-device evidence. Not implemented, not promised.

中文版（若 README 要中文）：

> **AVF 自定义内核（研究方向，仅限非 protected 设备）** —— 我们 pin 的 guest `Image` 只能以**非 protected** VM 形式被 AVF 启动。在 protected-only 设备上，内核必须通过 `pvmfw` 内置公钥的 AVB 签名校验，未签名的裸 `Image` 必然失败——这是信任链门，不是配置开关。当前没有非 protected 设备，真机证据为 **0**。不实现、不承诺。

三条措辞纪律（建议同事一并遵守）：
1. 不写"支持 AVF"——改为"评估中/研究方向"；
2. 不写"硬件隔离/安全隔离"——non-protected 没有（§4.5）；
3. 不给时间点——没有设备就没有里程碑（§7 R6）。

### 8.3 下一阶段的路线位置

按 `docs/research/ACR-draft-four-lanes-20261029.md` 的四车道口径，AVF 自定义内核**不是第五车道**，它是 pVM 车道在"设备条件变化"后的另一个执行形态。因此：

- 它**不占**当前四条车道的任何发布位；
- 它**不改变**"QEMU 是唯一已验证交付路径"这个事实；
- 它的启动条件是"出现一台 `getCapabilities() & 2 != 0` 且 `virtualizationservice` 在册的设备"，而不是"有人愿意写代码"。

---

## 9. 仍然空白的证据

按重要性排：

1. **任何一台 non-protected 设备的 `getCapabilities()` 原始位** —— 0 台。没有它，§4 全部停在"源码允许"。
2. **`virtualizationservice` 在非本机设备上是否注册** —— 本机没有，无法推断这是 ROM 通病还是本机个例。
3. **`USE_CUSTOM_VIRTUAL_MACHINE` 的真实授予结果** —— 从未执行过。理论可授（`development` flag），实测空白。
4. **我们的 `Image` 在真实 crosvm/AVF 下的启动日志** —— 0 条。连"第一个失败点在哪"（是 crosvm 拒绝、还是内核 early boot panic）都不知道。
5. **`Image` 与当前 fragment 的对应关系** —— 无 `kernel.config`、无 `BUILD-INPUTS`、`out/` 为空（R9）。
6. **16K 宿主 + 4K guest 的真实行为** —— 上游自己在 `crosvm.rs:717` 留了 `TODO: Use real page size`。
7. **pinned 7.2.3 源码包的 SHA256 实校** —— 本轮只 `HEAD` 到 200 与 160,060,344 字节，未下载校验。
8. **AVF 模块的 pinned 上游快照** —— `../phase5-references/` 里没有；本轮的 AOSP 基准是 GitHub 镜像的固定分支，不是项目自有的、带 hash 台账的快照（§5.5）。

---

## 10. 参考文献

### 10.1 本机实读（A 级）

- `guest-assets/kernel/droidlayer.fragment`、`orchestella.fragment`、`required.config`
- `guest-assets/build.sh:55-105, 300-360`；`guest-assets/README.md`；`guest-assets/inputs/inputs.lock:1-28`；`guest-assets/GPL-SOURCES.md`；`guest-assets/SOURCE-OFFER.txt`
- `app/src/main/assets/Image`（52,070,912 B，SHA256 `d56677f4…435dc5`，magic `ARMd`@0x38，`Linux version 7.2.3`）；`app/src/main/assets/runtime-artifacts-v1.sha256:1`
- `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/ReflectiveAvfVirtualMachineApi.java:171, 535-560`
- `../Podroid/app/src/main/java/com/excp/podroid/engine/avf/AvfReflect.kt`、`AvfEngine.kt`、`AvfCapabilities.kt`
- `.gitignore:11-12, 23`；`README.md:129-142`
- 既有研究（D 级引用，未重测）：`docs/research/2026-10-17-avf-custom-linux-route-study.md`、`2026-10-17-avf-device-recheck.md`、`2026-10-19-avf-reachability-corrections.zh-CN.md`、`2026-10-15-avf-shizuku-upgrade.md`、`2026-10-29-36domain-unlock-and-uml-compile.zh-CN.md`、`ACR-draft-four-lanes-20261029.md`、`microdroid-exec-selinux-20261022.zh-CN.md`

### 10.2 联网核证（可点击 URL）

> 说明：`android.googlesource.com`、`source.android.com`、`developer.android.com` 在本机网络环境下**直连失败**（curl 返回 000）。因此上游源码改用 GitHub 上的 AOSP 模块镜像（`LineageOS/android_packages_modules_Virtualization`，固定分支 `lineage-23.2`）**逐文件直读**；官方文档只能取检索索引中的正文，下文标注为 C 级。

1. [AOSP `Virtualization` 模块 —— `VirtualMachineCustomImageConfig.java`（固定分支镜像）](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java) —— 支撑 C2：`setKernelPath`/`setInitrdPath` 存在，类与全部方法 `@hide`。
2. [同仓 `VirtualMachineConfig.java`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java) —— 支撑 C1：`:1314-1326` `setProtectedVm(false)` 在属性为 false 时抛异常；`setCustomImageConfig` 为 `@hide` + `@RequiresPermission(USE_CUSTOM_VIRTUAL_MACHINE)`。
3. [同仓 `android.system.virtualmachine.res/AndroidManifest.xml`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/android/android.system.virtualmachine.res/AndroidManifest.xml) —— 支撑 C2：`USE_CUSTOM_VIRTUAL_MACHINE` = `signature|development`（可 `pm grant`）。
4. [同仓 `guest/pvmfw/src/main.rs`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/guest/pvmfw/src/main.rs) —— 支撑 C1 的核心：`:57-58` 编译期 `PUBLIC_KEY`；`:269-277` `verify_payload(..., PUBLIC_KEY)`；`:282` `guest_page_size` 默认 4K。
5. [同仓 `guest/pvmfw/README.md`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/guest/pvmfw/README.md) —— 支撑 C1：pvmfw 是 pVM 的第一条指令，校验失败即中止启动（原文引用见 §3.2）。
6. [同仓 `android/virtmgr/src/virtualmachine.rs`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/android/virtmgr/src/virtualmachine.rs) —— 支撑 C1：`:678-690` protected 分支的 label 检查；`:1835-1845` `check_protected_vm_is_supported()`。
7. [同仓 `android/virtmgr/src/crosvm.rs`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/android/virtmgr/src/crosvm.rs) —— 支撑 C2 与 R3：`:269-292` non-protected 分支不加 `--protected-vm*`；`:717` swiotlb 估算硬编码 4096 并标 TODO。
8. [同仓 `libs/hypervisor_props/src/lib.rs`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/libs/hypervisor_props/src/lib.rs) —— 支撑 C1/C2：`:21-34` 两个能力位来自 `ro.boot.hypervisor.*` 属性。
9. [同仓 `libs/framework-virtualization/api/system-current.txt`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/libs/framework-virtualization/api/system-current.txt) 与 [`test-current.txt`](https://github.com/LineageOS/android_packages_modules_Virtualization/blob/lineage-23.2/libs/framework-virtualization/api/test-current.txt) —— 支撑 §4.2：两文件里 grep `VirtualMachineCustomImageConfig` 均 0 命中。
10. [AOSP `libs/framework-virtualization/README.md`（固定 tag）](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_net_351410000/libs/framework-virtualization/README.md) —— 支撑 §4.2：API 34 引入、全部 `@SystemApi`、需 `MANAGE_VIRTUAL_MACHINE`、**可 `adb shell pm grant` 供开发**（C 级，检索索引正文）。
11. [AOSP `docs/custom_vm.md`（固定 tag）](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/tags/aml_ase_351114000/docs/custom_vm.md) —— 支撑 C2：示例 JSON 明确 `"protected": false`；`adb shell su root pm grant ... android.permission.USE_CUSTOM_VIRTUAL_MACHINE`（C 级）。
12. [AOSP「16 KB 页面大小」（source.android.com）](https://source.android.com/docs/core/architecture/16kb-page-size/16kb) —— 支撑 R3：arm64 直接改内核配置时用 `CONFIG_ARM64_16K_PAGES` 而非 `CONFIG_ARM64_4K_PAGES`（C 级）。
13. [Android Developers「支持 16 KB 页面大小」](https://developer.android.com/guide/practices/page-sizes) —— 支撑 R3 的应用侧要求（ELF 对齐、`getconf PAGE_SIZE` 验证）（C 级）。
14. [Google Play「16 KB page size compatibility requirement」公告](https://goo.gle/4sgFcFn) —— 支撑 R3 的时间点：2025-11-01 起，面向 Android 15+ 的新应用与更新必须支持 16KB（C 级；镜像转载见 [googblogs](https://www.googblogs.com/?p=91520)、[中文文档镜像](https://android-docs.cn/guide/practices/page-sizes)）。
15. [Stack Overflow：protection level flag 语义](https://stackoverflow.com/q/13515197) —— 支撑 §4.3：`development` 权限不会被自动授予，须 `adb shell pm grant`（C 级，社区来源，仅作旁证）。
16. [Zhauniarovich 等，Android 权限体系论文（PDF）](https://zhauniarovich.com/publication/2016/small-changes-big-changes-zhauniarovich-2016/small-changes-big-changes-zhauniarovich-2016.pdf) —— 支撑 §4.3：`FLAG_DEVELOPMENT` 使权限可由 `pm grant` 授予（C 级，学术来源）。
17. [Linux 7.2.3 源码包（pinned）](https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.2.3.tar.xz) —— 支撑 §2.2：本轮 `HEAD` 返回 200、`Content-Length: 160,060,344`（B 级；**未下载、未校 SHA256**）。

### 10.3 明确「未找到权威来源」的项

- **`VirtualMachineCustomImageConfig` 的正式 API level 编号**：该类是 `@hide`，**不存在**于任何 API 清单，所以"AOSP 官方给的 API level"这一说法本身不成立。能说的只有"AVF framework 面自 API 34 起，而自定义镜像类是 hidden API"。若有人引用某个具体 API 级别，那是二手推断。
- **non-protected VM 在 16KB 宿主上的 guest 页大小策略**：AOSP 文档只讲应用与内核构建，未给出 AVF 自定义 guest 的页大小约定；virtmgr 自己的代码是 `TODO`。**未找到权威来源**。
- **`USE_CUSTOM_VIRTUAL_MACHINE` 在 user 构建上 `pm grant` 的实际成功率**：只有 AOSP 文档的 `su root pm grant` 用法，没有针对非 root shell 的官方说明。本项目的实测只覆盖 `MANAGE_VIRTUAL_MACHINE`。**未找到权威来源**。

---

## 11. 一句话收束

内核资产是好的，路是断的：**同一份 `Image`，在 QEMU 上是唯一经过验证的交付物，在 AVF 上连"第一个失败点"都还没被记录过。** 结论写死——protected-only 设备不可用（信任链），非 protected 设备可用（五项条件全空），反射先例可抄（但抄不到成功），风险清单十项里八项仍是空白。下一阶段该做的不是写代码，是**弄到一台非 protected 设备**并把 §4.6 的五项条件一次跑完。
