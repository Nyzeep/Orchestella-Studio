# Orchestella 下一阶段方案：Protected Fast Lane + QEMU Full Lane（2026-10-21）

> **性质**：方向重排、技术边界分析与分阶段执行方案；不是实现报告，不宣称 protected Guest、Fast Lane 或任何性能收益已经通过。
> **基线**：`main 4b075c93`（2026-10-21 只读核对），源码 0.5.3 / code503；公开安装包仍为 v0.4.5。
> **证据等级**：AOSP 源码/官方文档 > 本仓代码与既有真机报告 > Podroid 最新源码 > 两份 DeepSeek 分享（AI 生成，只作线索，不代替一手证据）。
> **本回合**：未修改产品代码、未运行 Gradle、未操作设备；只新增方案、ticket 与决策记录。参考项目克隆在本地 `references/`，已通过 `.git/info/exclude` 排除，不会进入版本控制。

---

## 0. 结论摘要

1. **protected 自定义 Full Linux 在当前无 Root 约束下不可行，且不是“再写一个 adapter”能解决的。** pvmfw 只启动经 Google/OEM 签名的 boot image；App 无法用自己的 key 签一个通用 Linux 内核。即使签名问题被绕过，AOSP protected 分支还会拒绝 `app_data_file` 根盘，protected crosvm 也不提供原生 virtio-net。当前 HONOR 设备只声明 protected，因此这条线应停止产品化投入。
2. **protected 下唯一真实的硬件加速路径是 Google 签名的 Microdroid + 应用 payload。** 它不是“完整 Linux VM”，但可以提供一个**硬件 CPU 加速的 Linux 用户空间**：payload 是 App APK 内的 `.so`，可使用 persistent encrypted storage、通过 vsock 与宿主通信。它足以支撑一个“Fast Lane”，但不足以兑现 Full Linux 契约。
3. **Fast Lane 的上限由 SELinux 决定，而不是由性能决定。** 已核实：payload 只能从 APK 挂载点 `/mnt/apk` 执行；encrypted storage 是 `MS_NOEXEC`，且 `neverallow` 禁止从 `encryptedstore_file` 执行；payload 无 capabilities，不能 chroot/mount；`neverallow` 只允许 `crash_dump` ptrace，因此 PRoot 也不可行。Fast Lane 只能是**密封在 APK 内的 Linux 工具链/用户空间**，不是可 `apt install` 的发行版。
4. **新主线应是“双车道 + 交叉优化”，而不是“让 protected 跑 Full Linux”。**
   - **Full Lane**：QEMU，完整发行版、systemd、Docker、原生网络、GPU；CPU 慢，但能力完整，继续作为 Full Linux 的唯一交付路径。
   - **Fast Lane**：protected Microdroid，硬件 CPU、持久加密存储、密封工具链、终端/构建；能力窄，但 CPU 接近原生。
   - **交叉优化**：按设备与工作负载选道；把 QEMU 中 CPU 密集的构建显式 offload 到 Fast Lane；共享发行版清单、工具链与遥测；用测量结果调 QEMU 参数，而不是把 QEMU 说成“已被 pVM 加速”。
5. **这不是放弃 non-protected。** non-protected 仍是未来 Full Linux AVF 的唯一自洽路径；保留现有 adapter、测试和 07/08 工单，不删除、不改绿。只是当前不买新机、不把它当发布阻塞。
6. **发布 P0 不变。** 生产签名、QEMU/gvproxy 真机停止收敛、CI 门禁、终端输入仍是发布前阻塞；Fast Lane 不能成为推迟这些工作的理由。
7. **第一个门是设备原型，不是架构重构。** 当前设备就能验证 protected Microdroid payload 的 create/run/stop/encrypted storage/vsock。若这个门失败，Fast Lane 停止，QEMU 继续交付；若通过，再决定是否投入密封工具链。

---

## 1. 当前项目的真实状态

### 1.1 已经成立的能力

- **QEMU 是唯一有真实用户路径的执行策略**：7 个发行版、独立 kernel/initramfs、systemd、gvproxy/passt 网络、终端、SSH、停止/重开持久性均有真机证据（2026-10-17 独立 Debian 验收）。
- **Full Linux 契约、能力矩阵、单运行实例、App 私有 raw 根盘、默认 loopback、token 鉴权、冷恢复**都已形成代码与文档边界。
- **P0-2 运行时所有权**已收敛到 `RuntimeProcessSupervisor`：QEMU、gvproxy、socket 由同一 owner 有界停止；纯 JVM 与真实宿主进程测试通过，但真机 20 次启停仍 `not-tested`。
- **P0-3 跨 Controller 单实例竞态**已修复并有并发契约测试。
- **P0-1 生产签名**尚未完成：当前没有生产 release keystore，release 构建按设计失败；公开 v0.4.5 与 0.5.3 验证包仍是 Android Debug 证书。这是发布信任的第一风险。
- **P0-4 输入、P0-5 CI 门禁、P0-6 GPL/NOTICE**仍有未完成项。

### 1.2 AVF 代码的真实现状

- `ReflectiveAvfVirtualMachineApi.startCustomVm()` 明确构造 `VirtualMachineCustomImageConfig`，并在 `:172` 硬编码 `setProtectedVm(false)`。它只服务 non-protected custom kernel，不包含任何 protected 路径。
- `AvfVirtualMachineEngine.capabilityMatrix()` 声明 `HARDWARE_VIRTUALIZATION`、terminal、bridge、RFB；这是 non-protected 自定义 VM 的能力声明，不是 protected Microdroid 的能力声明。
- 生产路径没有 `Microdroid`、`PayloadConfig`、`setPayloadBinaryName`、`setEncryptedStorageBytes` 或 `connectVsock` 的实现；protected 需要新引擎，而不是给现有适配器加一个布尔值。
- 当前测试大量使用自写 `android.system.virtualmachine` fake SDK，不能替代真实 crosvm/virtmgr/APEX 行为。
- 2026-10-19 审计曾指出 AVF 生命周期缺口（`AvfSession.close()` 不停 VM、`register` 失败无清理、`waitFor` 把 DELETED 当正常等）。当前 `AvfVirtualMachineEngine` 已包含 `stopRuntime`/退出分类，但是否全部闭合仍以代码与真机复核为准；Fast Lane 不能直接复用未审计的 AVF 生命周期。

### 1.3 当前设备与 non-protected 事实

- HONOR MEY-AN00 / SM8735 / Android 16 / API 36；`android.software.virtualization_framework=true`，`com.android.virt` 存在。
- `vm info` 只声明 protected；`getCapabilities()=1`；`/dev/gunyah` 存在，`/dev/kvm` 不存在；后端更可能是 Gunyah，不是 pKVM。
- 真实 AVF Guest 成功样本仍为 0；protected 平台 payload 也尚未真实跑通。
- non-protected 在 AOSP 下不检查 `app_data_file` label，是 Full Linux AVF 的自洽路径；但当前 ROM 不声明 non-protected。未来候选设备包括 Pixel/Tensor、天玑 9400+、Exynos 2500 等，但**两份分享中的设备清单只是线索，不是本项目的验收证据**；购买前必须实测 `getCapabilities() & 2`、`setProtectedVm(false)`、App 私有盘、custom create/run/stop。

### 1.4 发布与性能现状

- QEMU 默认参数仍是 `cortex-a57`、`tcg,thread=multi`、无 `tb-size`、`cache=writeback`、passt TCP loopback；`QemuTuning` 只提供 opt-in 单变量实验，默认值未变。
- Podroid 1.3.0 已经默认使用 `-cpu max,sve=off,pauth-impdef=on`、`tcg,thread=multi,tb-size=512`、每盘 iothread、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`、2 vCPU、guest ZRAM。这些是**待验证假设**，不是可以直接照搬的结论。
- 项目已有 T7 单变量矩阵，但真机样本为 0；任何 QEMU 默认值变更都必须先有同设备、同镜像、同资源、控温、n≥10 的数据。

---

## 2. 两份 DeepSeek 分享：哪些成立，哪些必须修正

### 2.1 第一份（`2peuu19ml84idl66ey`）

主题是 Podroid 的虚拟化方案，以及“protected + QEMU 交叉优化”的初步设想。

成立的部分：

- Podroid 是 QEMU/TCG + AVF 双后端；AVF 只在其 non-protected custom kernel 路径可用；protected-only 设备自动回退 QEMU。
- Podroid 的 QEMU 调优包括 MTTCG、较大 `tb-size`、`-cpu max,pauth-impdef=on`、每盘 iothread、ZRAM、`mitigations=off` 等；这些能在源码中核对。
- “protected 不能直接跑 Podroid 的 Alpine + 自定义内核”成立，因为 pvmfw 只接受签名 payload。
- “把性能体验与产品契约解耦”“按工作负载选择执行环境”是正确方向。

必须修正/补足的部分：

- **它没有区分“protected custom kernel”和“protected Microdroid payload”。** 前者不可行；后者可行，但只能跑应用 payload，不是完整发行版。第一份分享的“任务级混合代理”只给出了方向，没有回答 payload 能否运行 Linux 用户空间、能否持久化、能否联网。
- **它把“交叉优化”说得过于宽泛。** QEMU TCG 不能把 guest 指令透明 offload 给另一个 pVM；pVM 也不能向 QEMU 暴露 KVM。真正可行的是“选道”和“显式构建 offload”，不是“让 pVM 加速 QEMU 的 CPU”。
- **它引用的性能倍数没有在本项目设备上复核。** 只能当假设。

### 2.2 第二份（`u7qo8r2tme9unot71u`）

主题是 protected 与 non-protected 在运行 Linux 时的区别，以及低成本验证设备。

成立的部分（已用 AOSP/Podroid 交叉核对）：

- protected 启动链经过 pvmfw，验证 AVB 签名的 kernel/initrd；自有未签名内核不能启动。
- protected 分支对 kernel/initrd/整盘做 SELinux label 检查，明确排除 `app_data_file`；转交 FD 不改变 label。
- protected crosvm 不提供原生 virtio-net；pVM 的通用通信路径是 vsock。
- non-protected 不走 pvmfw、不做上述 label 检查，是 Full Linux custom kernel 的自洽路径。
- 当前骁龙设备只暴露 protected 的现象与项目真机证据一致；non-protected 的设备清单（Pixel/Tensor、天玑 9400+、Exynos 2500）可作为选机线索。

必须修正/补足的部分：

- **“没有 app-side workaround”只对 custom kernel 成立。** Microdroid 的 app payload 是官方支持的 protected 路径；它不能跑 Full Linux，但可以跑我们自己的 native payload。这是本方案的核心切口。
- **设备清单不是兼容性承诺。** 不同 ROM/APEX 的 `getCapabilities`、`setProtectedVm(false)`、网络、label 行为必须逐台实测；不能因为芯片型号就购买或宣称支持。
- **“pVM 块 I/O 慢 25%、网络慢 75%”这类数字没有在本项目设备上复核。** 只能作为“I/O/网络不是 pVM 强项”的定性提示；不能写进产品文案或验收指标。

---

## 3. Podroid 1.3.0 最新代码分析（参考项目，不是依赖）

克隆位置：本地 `references/Podroid`，`main @ 332138a`（2026-10-20，v1.3.0），浅克隆 52 MB。许可证 GPL-2.0-or-later；只借鉴架构与机制，不复制代码。

### 3.1 它做了什么

- **引擎抽象**：`VmEngine` + `QemuEngine` + `AvfEngine` + `EngineHolder` 运行时路由；`decideBackend()` 是纯函数，有单测。
- **AVF 只走 non-protected**：`AvfCapabilities.kt:6-9` 明确写“raw custom kernel/initrd 只能以 non-protected 启动；protected 需要 pvmfw 签名 payload；protected-only 设备回退 QEMU”。`AvfEngine.kt:1110-1118` 在 protected-only 时抛 `UnsupportedOperationException`；`EngineHolder.kt:249-274` 的 AUTO 要求 non-protected + 两权限 + service 可达 + network feature。
- **多 vCPU 的工程处理**：`AvfCpuPolicy.kt` 记录 MATCH_HOST 在 Tensor G3/G4 上可能早启动 reset；Android 16+ 用 AIDL `CpuOptions.CpuTopology.cpuCount(N)` 的反射 Proxy 覆盖 raw config，得到均匀 topology；否则用 `nr_cpus=N` 限制 guest 上线的核数。
- **QEMU 调优**：`QemuEngine.kt:535-596` 使用 `-cpu max,pauth-impdef=on`、`tcg,thread=multi,tb-size=256/512`、每盘 iothread、`cache=writeback`、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`、`num-queues=cpus`；`SettingsRepository.kt:110-111` 默认 `max,sve=off,pauth-impdef=on` 与 `tb-size=512`；guest 侧 ZRAM lz4 1.5x RAM、`mitigations=off`、`mq-deadline`。
- **AVF 数据面**：vsock 9100/9101/200000；Kotlin 9p2000.L server；TAP + Android tethering DHCP；headless 2D GPU 声明用于把联网 VM 路由到完整 crosvm。
- **工程细节**：QEMU 以 `libqemu-system-aarch64.so` 放进 jniLibs；FGS + `PDEATHSIG`；QMP 动态 hostfwd；无 CI。

### 3.2 它没有做什么

- **没有 protected Linux 路径**。Podroid 的官方文档直接写：protected-only 设备想要硬件速度“需要 root”；它没有尝试 Microdroid payload。
- **没有多发行版**。只有 Alpine；没有 systemd；没有项目这样的 Full Linux 契约。
- **没有可复现发布**。无 CI、无校验和、guest root 密码是公开常量；这些是应避免的反面。
- **没有把 QEMU 调优变成受控实验**。它的默认值是维护者经验 + 社区反馈，不是本项目要求的同设备统计。

### 3.3 对本项目的可借鉴点

- 引擎选择用纯函数 + 能力矩阵，不用 UI 分支。
- QEMU 调优按单变量实验推进；`-cpu max,sve=off,pauth-impdef=on`、`tb-size`、iothread、`aio`、`discard`、`detect-zeroes` 都值得在本项目 QEMU 11.1.1 上复测。
- vsock 9p/端口转发/终端复用是成熟机制；可以借鉴协议分层，不复制 GPL 实现。
- 多 vCPU 的 fallback ladder 思路可用于 protected Microdroid 的 MATCH_HOST 失败场景。
- 但 Podroid 的 protected 结论只适用于“自定义内核”，不能推出“protected 不能做任何 Linux”。这正是本方案的差异化空间。

---

## 4. Protected VM 的硬边界（AOSP 源码核对）

### 4.1 为什么 protected custom Full Linux 不可行

| 门 | 事实 | 结果 |
| --- | --- | --- |
| pvmfw 签名 | pvmfw 验证 AVB 签名的 kernel/initrd；只接受 Google/OEM 签名的 boot image | App 无法签自己的通用内核；protected custom Full Linux 不可启动 |
| SELinux label | protected 分支对 kernel/initrd/整盘做 label 检查；`app_data_file` 被明确排除 | 即使签名通过，App 私有 raw 根盘也会被拒绝 |
| 网络 | 当前 AOSP protected 实现不提供原生 virtio-net；pVM 通用通信是 vsock（本项目研究亦如此） | Full Linux 的原生出站/NAT/DNS 契约不成立 |
| Guest 模型 | protected 典型 guest 是 Microdroid/签名 payload，不是标准发行版 | systemd/PID1、Docker、完整 distro 不是“换个布尔值” |
| I/O | protected 需要 bounce buffering；块/网络有额外开销 | 即使能跑，I/O 密集型体验也受限 |

结论：**protected 不是 Full Linux 的“更安全的版本”，而是另一条信任模型。** 当前设备的 protected-only 事实不会因为更多代码而改变；继续投入 25/26（protected custom boot/private disk）没有产品回报。

### 4.2 protected Microdroid payload 的真实能力

AOSP `VirtualMachineConfig` 提供了与 custom image 并列的 **app payload** 路径：

- `setPayloadBinaryName("libxxx.so")`：payload 是 APK `lib/<ABI>` 内的 `.so`，入口 `AVmPayload_main()`。
- `setProtectedVm(true)`：protected pVM；必须显式调用。
- `setMemoryBytes()`、`setCpuTopology(ONE_CPU|MATCH_HOST)`：内存与 vCPU。
- `setEncryptedStorageBytes()`：persistent encrypted storage，ext4，host 私有 backing file，key 由 VM secret 派生。
- `setDebugLevel(NONE|FULL)`：FULL 会削弱保护，生产必须 NONE。
- `VirtualMachine.connectVsock(port)`：宿主连接到 guest 的 vsock server。
- `AVmPayload_getApkContentsPath()`：`/mnt/apk`，APK 内容只读挂载。
- `AVmPayload_getEncryptedStoragePath()`：`/mnt/encryptedstore`。
- `AVmPayload_getVmInstanceSecret()`、`AVmPayload_isNewInstance()`：per-VM secret 与新实例判断。
- `AVmPayload_runVsockRpcServer()`：Binder RPC over vsock（也可以直接用 vsock socket）。

这不是“Full Linux”，但它是一个**有真实 Linux 内核、硬件 vCPU、持久加密存储、vsock 通道**的执行环境。关键是它允许第三方 App 用自己的 APK 作为 payload，不需要 Google 签我们的内核。

### 4.3 SELinux 决定的 Fast Lane 上限

以下来自 AOSP `system/sepolicy` Microdroid 策略与 `guest/encryptedstore` 源码：

| 能力 | 是否允许 | 证据/原因 |
| --- | --- | --- |
| 从 `/mnt/apk` 执行 | **允许** | `/mnt/apk` 由 zipfuse 挂载，文件 context 为 `system_file`；`allow coredomain system_file:file { execute read open getattr map }` |
| 从 encrypted storage 执行 | **禁止** | `encryptedstore` 以 `MS_NOSUID|MS_NODEV|MS_NOEXEC` 挂载；`neverallow domain encryptedstore_file:file { execute execute_no_trans }` |
| chroot | **禁止** | payload 的 inheritable/bounding caps 被清空；策略没有 `capability sys_chroot` |
| mount tmpfs/overlay/loop | **禁止** | 没有 `capability sys_admin`；没有 mount allow |
| PRoot | **禁止** | `neverallow { domain -crash_dump } domain:process ptrace`；只有 crash_dump 能 ptrace |
| memfd + fexecve | **很可能禁止** | `microdroid_app` 没有 `tmpfs:file execute`；需真机确认 |
| 从 encrypted storage mmap 动态库 | **很可能禁止** | `encryptedstore_file` 只给 create/read/write 类权限，没有 `map`；需真机确认 |
| vsock 入站（guest listen/accept） | **允许** | `allow microdroid_payload self:vsock_socket { create listen accept ... }` |
| vsock 出站（guest connect） | **禁止** | `neverallow microdroid_payload self:vsock_socket connect` |
| 原生 virtio-net | **当前 protected 路径不提供** | 没有原生网络；只能 vsock 代理 |
| GPU/显示 | **不提供** | Microdroid 无 graphics/UI HAL；没有 GPU 配置 |
| persistent storage | **允许** | encrypted storage，ext4，跨 VM/设备重启；host 不可解密 |
| 多 vCPU | **允许但需验证** | `CPU_TOPOLOGY_MATCH_HOST`；异构成因可能导致早启动 reset |
| 调试输出 | **允许但削弱保护** | `DEBUG_LEVEL_FULL`；生产必须 NONE |

**这张表就是 Fast Lane 的产品边界。** 任何声称“protected 下可以 apt install 完整发行版、Docker、原生网络、GPU 桌面”的方案，都必须先推翻这些 neverallow/mount 事实；当前没有证据。

### 4.4 因此，Fast Lane 的可行形态

**可行：密封 Linux 工具链/用户空间（Sealed Linux Toolbox）**

- 把工具/用户空间作为 **APK 根 `bin/` 条目**打进 APK；payload 仍在 `lib/<ABI>`。zipfuse 对 `bin/` 下的文件默认给 execute 位（`EXECUTABLE_FILE_MODE`），`lib/` 则依赖 zip 的 unix mode，不能假设可执行。
- 候选实现 A：**Termux bootstrap**（Bionic + `$PREFIX`，已有 bash/git/python/clang 生态）；候选实现 B：自建静态工具集。两者都从 `/mnt/apk/bin` 运行，数据在 encrypted storage。Termux 的 proot 仍不可用（SELinux ptrace），但 Termux 本体不依赖 proot。
- 二进制/库全部在 APK 挂载点（`system_file`，可执行/可 map）；可写数据、源码、构建产物放 encrypted storage。
- 终端：payload 在 devpts 上开 PTY，通过 vsock 提供多路复用；宿主复用现有终端 UI。
- 网络：payload 监听 vsock 端口，宿主侧做 TCP/SOCKS5/HTTP CONNECT 代理；guest 侧工具用 `http_proxy`/`ALL_PROXY`/`git http.proxy`。没有原生 `eth0`、没有 ICMP、没有 raw socket。
- 持久化：encrypted storage 作为 `/home`、`/workspace`、包缓存；host 不可读，导出必须 guest 侧 tar → vsock → SAF。
- 构建：编译器从 APK 执行，源码/对象在 encrypted storage；产物可回传 QEMU Full Lane 运行。

**不可行：完整发行版 VM**

- 没有自己的内核；没有 systemd PID1；没有 Docker/Podman；没有原生网络；没有 GPU；不能从 encrypted storage 执行新安装的 native 包；不能 chroot/proot 任意 rootfs。
- 因此不能叫 Full Linux，也不能承诺 `apt install` 任意包、Docker、桌面 3D、USB、音频。

**待验证：JIT**

- Node/V8、JVM 的 JIT 需要 executable memory；Microdroid 的 W^X/`execmem` 策略可能禁止。Fast Lane 可能只适合 AOT 编译型工作负载（C/C++/Rust/Go），不适合 Node/JVM。必须用最小 JIT 测试确认，不能假设。

---

## 5. 新方向：双车道 + 交叉优化

### 5.1 产品结构

| 维度 | Full Lane（QEMU） | Fast Lane（protected Microdroid） |
| --- | --- | --- |
| Runtime 类别 | Full Linux Runtime | **Accelerated Linux Workspace（工作名，待 ACR-03）** |
| Guest kernel | 项目 pinned kernel | Google 签名的 Microdroid GKI |
| 用户空间 | 完整发行版，systemd/PID1 | 密封工具链/用户空间，无 systemd |
| CPU | TCG 软件翻译，慢 | pVM 硬件 vCPU，接近原生 |
| 内存 | QEMU 进程 RSS，可调 | VM 内存，受 host 限制 |
| 存储 | App 私有 raw 根盘，host 可备份 | encrypted storage，host 不可解密；guest 侧导出 |
| 网络 | passt/gvproxy，原生 `eth0`/NAT/DNS/端口转发 | vsock + host 代理；无原生网络 |
| 显示 | VirGL/RFB/Wayland（按实际接线） | 终端；VNC 需额外验证；无 GPU |
| 包管理 | 完整 apt/dnf/pacman | 不能安装 native 包；可运行 sealed 工具或纯脚本包 |
| Docker/Podman | 支持（guest 内核/namespace） | 不支持 |
| 适用 | 完整开发、服务、桌面、兼容性 | CPU 密集终端、编译、密封工具链、构建加速 |
| 性能定位 | 通用但 CPU 慢 | CPU 快，I/O/网络/显示受限 |

### 5.2 交叉优化的四个可行层次

1. **设备/工作负载选道（Lane Router）**
   - 新实例创建时按设备能力、发行版要求、工作负载标签选择 Full/Fast/Auto。
   - Auto 规则：需要 systemd/Docker/原生网络/GPU/包管理 → Full；纯终端/编译/密封工具链 → Fast；不确定 → Full（保守）。
   - UI 显示两条车道的真实能力矩阵和选择原因；不静默切换。

2. **构建 offload（Turbo Build）**
   - Full Lane 的 guest 通过一个显式命令（如 `orch-build`）把源码树打包，经 vsock 送到 Fast Lane；Fast Lane 用密封工具链编译，产物回传。
   - 这不是透明系统调用拦截，不破坏 POSIX 语义；是一个明确的“把 build 放到快车道”的产品功能。
   - v1 只支持 C/C++/Rust 静态构建和 Debian/Ubuntu 目标；工具链版本必须与 Full Lane 匹配或产出静态二进制。
   - 价值：QEMU 的 CPU 瓶颈主要落在编译；把编译放到 pVM 可以拿到接近原生的速度，而 Full Lane 仍保留完整运行环境。
   - 代价：源码/产物传输、工具链一致性、构建缓存管理；必须测量端到端收益，不能只测 pVM 内部编译时间。

3. **共享工件与清单**
   - 同一 `DistroManifest` 驱动 Full Lane 的 rootfs 和 Fast Lane 的 sealed toolchain；版本、SHA256、许可证同源。
   - 共享 dotfiles、包缓存、构建缓存；通过 guest 侧同步，不经 host 明文落盘。
   - Fast Lane 的 sealed toolchain 单独版本化；不能与 Full Lane 的 rootfs 静默漂移。

4. **QEMU 调优（把 Full Lane 的地板抬高）**
   - 在现有 T7 矩阵上增加 Podroid 验证过的候选：`-cpu max,sve=off,pauth-impdef=on`、`tb-size=256/512`、每盘 iothread、`num-queues`、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`、guest `mitigations=off`、`mq-deadline`、ZRAM。
   - 默认值不变，直到同设备、同镜像、同资源、控温、n≥10 的样本显示至少一个 workload 有统计显著改善且没有 >5% 回归。
   - 注意：`QemuTuning.tbSizeKb` 的字段名与 QEMU `tb-size`（MiB）单位不一致；实验前先核对，避免把 512 MiB 当成 512 KiB。

### 5.3 明确不做的“交叉”

- **不做 QEMU TCG 指令级 offload 到 pVM**：没有这样的 hypervisor 接口；实现不了，也不应该假装。
- **不做 pVM 内嵌套 KVM**：protected Microdroid 不暴露 `/dev/kvm`；在 pVM 里跑 QEMU 只会更慢。
- **不做 pVM GPU 直通/3D**：Microdroid 没有 graphics HAL；Fast Lane 只能终端/软件渲染。
- **不做 pVM 原生网络**：protected 路径没有 virtio-net；只能 vsock 代理。
- **不把 Fast Lane 叫 Full Linux**：它没有自己的内核、systemd、Docker、原生网络、包管理。

### 5.4 为什么这仍然是有创新性的

- Podroid 明确放弃 protected-only 设备（回退 QEMU）；Google Linux Terminal 依赖 non-protected；PRoot 没有硬件加速。
- Orchestella 可以是**唯一在 protected-only 无 Root 设备上同时提供“硬件加速 Fast Lane”和“完整 Linux Full Lane”的产品**，并把两者用构建 offload/统一 UI 连接起来。
- 这不是“又一个 AVF 终端”：Fast Lane 有持久加密存储、密封工具链、与 Full Lane 的构建协同；Full Lane 保留多发行版/设备广度。差异化是“设备广度 + 双车道 + 真实能力矩阵”，不是单点 AVX 性能。

---

## 6. 分阶段执行方案

### 6.1 P0 发布线（不变，优先于 Fast Lane 产品化）

| 工作 | 现状 | 出口 |
| --- | --- | --- |
| P0-1 生产签名与迁移 | 无 production keystore；release 构建按设计失败 | 非 Debug 证书；v0.4.5 导出 → 新包导入 → 实例可启动 |
| P0-2 QEMU/gvproxy 真机收敛 | 代码完成；真机 20 次启停 `not-tested` | 20 次启停 0 孤儿；强制停止后同端口可重启 |
| P0-4 终端输入/IME | 部分修复；OEM/实体键盘待验 | 三路 1000 字符混合文本逐字节一致；旋转/锁屏无重复 |
| P0-5 CI 门禁 | 部分接入；AVF/capability/distro/network 未全进 | 故意失败能阻塞；16 KB 检查绿 |
| P0-6 GPL/NOTICE | 部分修正 | 发布包 NOTICE/对应源码一致 |

**P0 出口不变**：一个非 Debug 签名、可迁移、连续启停稳定、输入可用的公开预览包。Fast Lane 不能阻塞或稀释这条线。

### 6.2 F0：零设备准备（现在就能做）

- 新增 ACR-03（Fast Lane runtime 类别/语义）草案，状态 `needs-architecture-decision`。
- 新增 ticket 28/29/30，定义原型范围、证据、停止条件。28 先按 `MANAGE_VIRTUAL_MACHINE` 授权；若 app payload 还要求 `USE_CUSTOM_VIRTUAL_MACHINE`，按实际失败记录并走合法 ADB grant，不绕过。
- 确认 `OrchestellaApplication.attachBaseContext` 的 HiddenApiBypass 6.1 豁免早于任何 AVF 类加载；`AvfHostFactsProbe` 与 engine 共用一套 AVF shape/failure mapping（当前已安装，但探针/引擎仍需统一失败语义）。
- 准备最小 payload 工程：`AVmPayload_main`、加密存储 marker、vsock echo、`AVmPayload_notifyPayloadReady`。
- 准备 QA APK：`MANAGE_VIRTUAL_MACHINE` 授权引导、payload 选择、create/run/stop、日志脱敏。
- 不引入生产 owner、不改 Full Linux 契约、不改 accepted ADR。

**F0 出口**：ACR-03 + 3 张票 + 可构建的 QA payload；无设备证据，全部标 `not-tested`。

### 6.3 F1：protected Microdroid 最小 payload（设备门）

对应 ticket 28。当前设备即可执行，不需要 non-protected 设备。

测试顺序：

1. `getCapabilities()` bit1；`setProtectedVm(true)` 不抛。
2. `VirtualMachineConfig.Builder` + `setPayloadBinaryName("libfastlane_probe.so")` + `setMemoryBytes(1GB)` + `setCpuTopology(ONE_CPU)` + `setEncryptedStorageBytes(256MB)`。QA 可先用 `DEBUG_LEVEL_FULL` 抓 console；生产目标 `DEBUG_LEVEL_NONE`；成功判据以 vsock/存储为准。
3. `create`/`run`；payload 输出 marker；宿主 `connectVsock(5000)` 握手。
4. payload 写 encrypted storage marker；stop；再 start；marker 仍在。
5. stop 收敛：status STOPPED；无孤儿；20 次启停。
6. 宿主不能读取 encrypted storage 明文；导出只能 guest 侧。
7. 负例：`setProtectedVm(false)` 抛 `UnsupportedOperationException`；custom image 被拒；错误 payload 签名/损坏被拒。

**F1 出口（四类之一）**：
- `pass_platform_protected_payload`：create/run/stop/vsock/storage 全通过；
- `rejected_at_stage`：明确最早拒绝层（hidden API/permission/payload/verify/vsock/stop）；
- `blocked_external`：设备/系统确认/权限窗口不可用；
- `stop_unconfirmed`：停止不收敛，必须先修生命周期。

**若 F1 失败**：Fast Lane 停止，不进入 F2；QEMU 继续交付；保留证据，不把失败写成“芯片不支持所有 protected”。

### 6.4 F2：密封 Linux toolbox（Fast Lane 的核心）

对应 ticket 29。前提 F1 通过。

- 先做两个候选的 spike，按证据二选一：
  - A：Termux bootstrap（Bionic/`$PREFIX`），验证 bash/git/python/clang 从 `/mnt/apk/bin/usr` 运行；
  - B：自建静态工具集（busybox/git/python/gcc），验证最小可执行链。
- 选择依据：能否在 `bin/` 下执行、动态链接/prefix 是否可用、APK 体积、许可证；不预设 A 或 B 成功。
- 优先以 **APK 根 `bin/` 条目**打包，利用 zipfuse 对 `bin/` 的 execute 默认位；payload 本身仍在 `lib/<ABI>`。
- payload 从 `/mnt/apk/bin/` 执行工具；`PATH`/`LD_LIBRARY_PATH`/`HOME` 指向 APK 挂载点与 encrypted storage。
- 若 AGP 不能直接产出根 `bin/`，用自定义打包任务在签名前注入，并验证 APK 签名、zipalign 16 KB 与安装/更新。
- 终端：PTY + vsock 多路复用；复用现有终端 UI 的 session 抽象。
- 数据：`/mnt/encryptedstore/home`、`/workspace`；首次运行初始化；导出 tar over vsock → SAF。
- 边界测试（必须真实执行并记录）：
  - 从 `/mnt/apk` 执行静态 busybox：通过；
  - 从 encrypted storage 执行：预期 EACCES/EPERM，记录；
  - `chroot`/`mount`/`unshare`：预期 EPERM，记录；
  - `proot`：预期 SELinux ptrace 拒绝，记录；
  - `memfd_create`+`fexecve`：预期失败或成功，记录；
  - 从 encrypted storage `mmap(PROT_EXEC)` 动态库：预期失败，记录；
  - JIT（Node/JVM）：预期被 W^X 拒绝或受限，记录。
- 若任一“预期失败”实际成功，说明目标 ROM 策略与 AOSP 不同，必须重新评估能力矩阵；不能直接扩大承诺。

**F2 出口**：一个可用的 sealed shell + 至少一个编译工具；数据持久化；终端稳定；边界测试有明确结论；APK 体积增量有明确预算（v1 建议 ≤80 MB 起步；完整 Termux bootstrap 另做体积决策）。

### 6.5 F3：数据面与网络（Fast Lane 可用性）

对应 ticket 30。前提 F1/F2 通过。

- **网络代理**：payload 监听 vsock 端口；宿主侧实现 SOCKS5/HTTP CONNECT；guest 工具通过 `ALL_PROXY`/`http_proxy` 访问 TCP；DNS 由代理解析（SOCKS5 域名）。
- 测试：`curl https://example.com`、`git clone`、`pip install` 纯 Python 包、`npm install`（若 Node JIT 可用）；不测 ICMP/raw socket。
- **导出/导入**：guest 侧 tar → vsock → 宿主 SAF；导入反向；不把 encrypted storage 明文落宿主。
- **显示**：v1 只做终端；VNC 作为后续可选（需要静态 VNC server，且无 GPU）。不把 RFB 写进 Fast Lane 能力矩阵，直到真实连通。
- **性能基线**：同设备、同 1-2 vCPU、同 RAM，对比 QEMU：
  - CPU：sysbench/openssl/xz；
  - 编译：同一 C 项目；
  - I/O：fio/dd；
  - 启动：payload ready 时间；
  - 网络：代理吞吐/延迟。
  - n≥10、控温、median/IQR；所有失败样本保留。

**F3 出口**：TCP 代理可用；导出/导入可用；至少一个 CPU workload 对 QEMU 有 ≥2x 优势且无不可接受回归；否则 Fast Lane 只保留为实验功能，不进入默认。

### 6.6 F4：交叉优化（构建 offload + 选道）

对应 ticket 30 的后续/新票（暂不预建，避免过度开票）。前提 F3 有数据。

- **Lane Router**：实例创建时按 capability + workload 选择 Full/Fast/Auto；UI 显示原因；失败显式回退。
- **Turbo Build v1**：Full Lane guest 的 `orch-build` 把源码 tar 经 vsock 送到 Fast Lane；Fast Lane 编译；产物回传；端到端计时。
- 只支持 C/C++/Rust 静态构建；不承诺任意语言/任意发行版。
- 若端到端收益 <1.5x，停止该功能，保留选道；不要为了“交叉”而交叉。

**F4 出口**：至少一个真实构建 workload 端到端 ≥1.5x，且 Full Lane 语义不变；否则记录负结果，不产品化。

### 6.7 F5：产品化（ACR-03 通过后）

- 新 Runtime 类别、能力矩阵、UI 徽章、创建向导、导出/导入、错误文案、遥测。
- 实验开关优先；默认关闭，直到 F1-F4 全部通过。
- 版本号按 VERSIONING.md；不因合并 main 自动递增；发布仍受 P0 门约束。
- 不在 Fast Lane 上声明 Full Linux、Docker、原生网络、GPU、音频、USB、systemd。

### 6.8 non-protected 未来线

- 保留 `runtime/engine-avf` 现有 non-protected adapter、20 个 JavaExec、跨策略 kernel/raw 盘测试；不删除、不改绿。
- 07/08 继续 blocked-on-device；09/10 仍需要真实 AVF 全链后才能测。
- 25/26（protected custom boot/private disk）标记为 **parked / resolved-negative for product**：AOSP 证据已经足够说明 protected custom Full Linux 不可行；保留原型票作为历史，不再占当前 frontier。
- 设备采购：不在 F1/F2 之前买；若 F1/F2 成功，Fast Lane 已能给 protected-only 用户价值，non-protected 购买可延后到有明确 Full Linux AVF 需求时。若购买，优先 Podroid 清单中有 AVF 成功记录且无 boot/hang 问题的机型，并逐项实测。

---

## 7. 验收指标

### 7.1 Fast Lane 平台门

- protected payload create/run/stop 成功；`stop` 返回 CONFIRMED；20 次启停无孤儿。
- encrypted storage 跨 stop/start/设备重启保留；host 无法读取明文。
- vsock 终端双向；payload ready 可观测；失败有 typed reason。
- `setProtectedVm(false)` 在当前设备明确拒绝；custom image 被拒的原因可归因。
- 所有证据脱敏：无序列号/IMEI/Android ID/账号/token/按键正文。

### 7.2 Fast Lane 产品门

- sealed shell 可用；至少一个编译工具可用；数据持久化；终端稳定。
- 边界测试结论明确：exec-from-APK 通过；exec-from-encrypted/chroot/mount/proot/memfd/JIT 的允许/拒绝有真实记录。
- TCP 代理可用（curl/git/pure-Python pip）；DNS/TLS 正常；无原生网络声明。
- 导出/导入可用；host 不落明文。
- APK 体积增量在预算内；启动时间可接受。

### 7.3 性能门

- 同设备、同资源、控温、n≥10；报告 median/IQR 和失败样本。
- Fast Lane 至少一个 CPU/编译 workload 对 QEMU ≥2x；否则不产品化。
- QEMU 调优至少一个 workload 统计显著改善且无 >5% 回归；否则保留默认。
- Turbo Build 端到端 ≥1.5x；否则停止。
- 不用“pVM 应该更快”代替数据；不把 AVF 平台声明当性能结果。

### 7.4 发布门

- P0-1/P0-2/P0-4/P0-5/P0-6 按原计划；Fast Lane 不降低这些门。
- 任何 Fast Lane 公开版本必须：非 Debug 签名、完整对应源码/NOTICE、能力矩阵不虚报、导出/导入可回滚。

---

## 8. 风险与停止条件

| 风险 | 触发 | 处置 |
| --- | --- | --- |
| Microdroid API 不可用 | hidden API/permission/payload builder 失败 | F1 记 `rejected_at_stage`；Fast Lane 停止；QEMU 继续 |
| payload 签名/verify 失败 | 自有 APK/idsig 被拒 | 检查 APK 签名/idsig；不改系统信任；失败则停 |
| vsock 不可用 | connect/listen 失败 | 修 payload readiness/端口；仍失败则停 |
| stop 不收敛 | 20 次启停有孤儿/UNCONFIRMED | 先修生命周期；不进入 F2 |
| encrypted storage 不可持久 | 重启后 marker 丢失 | 修 VM 配置/实例身份；失败则停 |
| 无法从 APK 执行工具 | exec/EACCES | 缩小为 native payload；不把 Fast Lane 叫 Linux |
| chroot/proot/mount 被拒 | EPERM/SELinux | 这是预期边界；改用 sealed toolbox，不绕过 |
| JIT 被拒 | Node/JVM 无法运行 | Fast Lane 只支持 AOT 工作负载；能力矩阵写明 |
| 原生包安装不可行 | `apt install` 无法执行新二进制 | 不承诺包管理；只提供 sealed 工具/纯脚本包 |
| 网络代理不足 | DNS/TLS/长连接失败 | 只做终端/离线；不声明原生网络 |
| 性能不达标 | <2x 或无显著优势 | 停止 Fast Lane 产品化；保留实验 |
| APK 体积过大 | 工具链 >80MB | 裁剪工具；或改为可选模块（需单独决策） |
| 安全/隐私 | debug FULL、日志泄露、host 可读 | 生产 DEBUG_NONE；日志脱敏；不导出 encrypted storage 明文 |
| GPL/许可 | 复制 Podroid 代码/未审二进制 | 只借鉴架构；工具链逐项许可/SHA；发布附对应源码 |
| 过度开票 | 新票过多、协作开销 | 只保留 28/29/30 + ACR-03；F4 后续票在数据后再开 |

---

## 9. 需要用户裁定

1. **是否接受 Fast Lane 作为新的 Runtime 类别（工作名 Accelerated Linux Workspace）？**
   推荐：接受为第三类，明确不是 Full Linux；先实验开关，F1-F4 通过后再默认。若拒绝，则 protected-only 设备继续 QEMU-only，Fast Lane 只做内部研究。
2. **Fast Lane v1 的范围：密封工具链，还是只做 native compute payload？**
   推荐：密封工具链（busybox/git/python/gcc），终端优先，不做 GUI；因为它是用户能感知的“Linux 体验”，而 native payload 只是后台服务。
3. **是否接受“不能 apt install native 包、没有原生网络、没有 GPU、没有 Docker/systemd”的 Fast Lane 能力矩阵？**
   推荐：接受，并把完整能力留给 Full Lane；否则 Fast Lane 没有可行形态。
4. **Turbo Build 是否进入 v1？**
   推荐：F4 再做，先证明 F1-F3；只支持 C/C++/Rust 静态构建；端到端 <1.5x 就停止。
5. **是否为 non-protected 购机？**
   推荐：暂缓。当前设备可验证 Fast Lane；non-protected 是 Full Linux AVF 的未来线，等 Fast Lane 结果和 P0 发布门之后再决定。
6. **P0 发布优先级是否保持不变？**
   推荐：不变。生产签名/QEMU 停止/CI/输入仍是发布前阻塞；Fast Lane 不是推迟发布的理由。

---

## 10. 本次文档变更

- 新增本方案。
- 在 `next-optimization-plan-20261018.zh-CN.md` 顶部加方向更新注记，指向本方案；P0/P1 内容保留。
- 在 `.scratch/orchestella-avf-shizuku/map.md` 追加 2026-10-21 节，更新当前 frontier/dependency/stop conditions。
- 新增：
  - `.scratch/orchestella-avf-shizuku/issues/28-protected-microdroid-payload-probe.md`
  - `.scratch/orchestella-avf-shizuku/issues/29-sealed-linux-toolbox.md`
  - `.scratch/orchestella-avf-shizuku/issues/30-fast-lane-data-network-and-baseline.md`
  - `.scratch/orchestella-avf-shizuku/issues/ACR-03-protected-fast-lane-runtime-class.md`
- 不修改 `CONTEXT.md`、accepted ADR、产品代码、版本号、签名或设备状态。

---

## 11. 证据与来源

### 11.1 本仓

- `runtime/engine-avf/.../ReflectiveAvfVirtualMachineApi.java`：`:172` 硬编码 non-protected；custom image 构造。
- `runtime/engine-avf/.../AvfVirtualMachineEngine.java`：capability matrix 与 vsock session。
- `runtime/engine-qemu/.../QemuTuning.java`：默认值与实验字段。
- `runtime/engine-qemu/.../QemuLaunchSpec.java`：`-cpu`/`-accel`/`-drive`/passt 参数。
- `docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md`：non-protected label、pvmfw、hidden API、设备门。
- `docs/research/2026-10-17-avf-custom-linux-route-study.md`：protected/custom Linux 硬门。
- `docs/reports/orchestration/next-optimization-plan-20261018.zh-CN.md`：P0/P1 与 QEMU 主线。
- `docs/reports/orchestration/release-readiness-fix-20261019.zh-CN.md`：生产签名缺口。
- `docs/reports/orchestration/p0-2-runtime-supervisor-20261018.zh-CN.md`：QEMU 生命周期收敛。

### 11.2 AOSP（本地参考克隆）

- `android.googlesource.com/platform/packages/modules/Virtualization`，`main @ 175a51b`（浅克隆；Android 16 时代）。
  - `libs/framework-virtualization/.../VirtualMachineConfig.java`：`setPayloadBinaryName`、`setProtectedVm`、`setEncryptedStorageBytes`、`setCpuTopology`、`setMemoryBytes`。
  - `libs/framework-virtualization/.../VirtualMachine.java`：`connectVsock`、`run`、`stop`。
  - `libs/libvm_payload/include/vm_payload.h`：APK contents、encrypted storage、vsock RPC、VM secret。
  - `guest/microdroid_manager/src/main.rs`：payload 执行、UID/GID、caps 清空、APK 挂载。
  - `guest/encryptedstore/src/main.rs`：`MS_NOSUID|MS_NODEV|MS_NOEXEC`。
  - `build/microdroid/init.rc`：`/data` noexec。
- `android.googlesource.com/platform/system/sepolicy`，浅克隆。
  - `microdroid/system/private/domain.te:92,547`：`system_file` 可执行；encrypted storage 不可执行。
  - `microdroid/system/private/microdroid_payload.te:60-68`：加密存储读写；vsock listen/accept；禁止 connect。
  - `microdroid/system/private/microdroid_app.te:9`：payload 域。
  - `microdroid/system/private/encryptedstore.te`、`file.te`、`zipfuse.te`：挂载与 context。
- AOSP 官方文档：
  - https://source.android.com/docs/core/virtualization/microdroid
  - https://source.android.com/docs/core/virtualization/architecture
  - https://source.android.com/docs/core/virtualization/security
  - https://source.android.com/docs/core/virtualization

### 11.3 Podroid 最新源码

- `references/Podroid`，`main @ 332138a`（v1.3.0，2026-10-20），GPL-2.0-or-later。
- `app/src/main/java/com/excp/podroid/engine/avf/AvfCapabilities.kt`
- `.../AvfEngine.kt`、`.../AvfReflect.kt`、`.../AvfCpuPolicy.kt`
- `.../engine/EngineHolder.kt`、`.../engine/QemuEngine.kt`
- `.../data/repository/SettingsRepository.kt`
- `docs/guide/backends.html`、`docs/guide/performance.html`

### 11.4 两份分享

- https://chat.deepseek.com/share/2peuu19ml84idl66ey
- https://chat.deepseek.com/share/u7qo8r2tme9unot71u

性质：AI 生成对话，只作线索；其中设备清单、性能倍数、厂商策略均未在本项目设备上复核。

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 本回合未运行 Gradle/设备/构建，未修改产品代码。
