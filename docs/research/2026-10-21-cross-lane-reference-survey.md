# Protected Fast Lane 与 QEMU 交叉优化：参考项目调研（2026-10-21）

> 性质：一手源码/官方文档为主的参考项目调研，不是实现报告，不宣称任何性能收益。
> 方法：浅克隆最新 main，逐项核对 README、构建脚本、关键源码与许可证；外部事实用 AOSP/官方文档交叉验证。
> 参考目录：本地 `references/`（外部 checkout，本地 exclude，不进入 GitHub）。
> 与本项目的关系：为 `protected Microdroid` 的 Fast Lane、QEMU Full Lane、跨车道构建 offload 提供可借鉴/应避免的机制。

---

## 0. 结论摘要

1. **Termux 是 protected Microdroid 内最匹配的用户空间候选**：它本来就是 Android/Bionic 用户空间，不依赖 glibc、不需要 chroot/proot；`$PREFIX` + `termux-exec` 的路径重写可以在只读 APK `bin/` 前缀上工作。它不能提供 guest kernel、systemd、Docker、原生网络或 GPU，但能提供 bash/git/python/clang 等真实工具链。
2. **PRoot 不是硬件虚拟化，也不是 protected 内的可行路径**：它用 ptrace 拦截 syscall，guest 指令本身是硬件执行，但 syscall 密集负载有明显开销；protected 内 `microdroid_payload.te` 没有 ptrace allow（SELinux 默认拒绝，也没有显式 neverallow），PRoot 大概率不可用，但结论需要真机探针闭合。PRoot 只能作为宿主侧 Userspace Compatibility 的参考/兜底，不能作为 Fast Lane 的核心。
3. **QEMU 与 protected Fast Lane 的最强交叉点是分布式编译**：QEMU guest 保持完整 Linux，使用 `distcc` 在本地预处理；Fast Lane 通过 vsock 接收字节流，用 `distccd --inetd`（stdin/stdout，无需 TCP socket）在硬件 CPU 上编译，返回目标文件。它不破坏 POSIX 语义，不需要在 protected 里跑完整发行版，也不需要 guest 有原生网络。
4. **protected Fast Lane 不能提供原生网络/GPU/TCP**：唯一通用通道是 vsock；`microdroid_app` 没有 TCP socket 的通用 allow，也没有 graphics HAL。网络必须由宿主代理；distcc 必须用 `--inetd` + vsock 桥；显示 v1 只做终端。
5. **DroidVM 证明 Gunyah/crosvm/VirGL 路线在 root 下可行，但不是本项目的路径**：它要求 root、直接打开 `/dev/gunyah`，其 `pseudo-unprotected` 也不是官方 AVF non-protected 能力。可借鉴其后端抽象/GPU/VNC/磁盘 UI，不可作为无 Root 承诺。
6. **Podroid 仍是 QEMU 调优的最佳工程参照**，但它对 protected 的结论只适用于“自定义内核”；它没有尝试 Microdroid payload。Orchestella 的差异化空间就在这里。
7. **没有证据支持“protected 下跑完整发行版 VM”**。任何方案若要求自定义 kernel、systemd PID1、Docker、原生 `eth0`、GPU，必须回到 non-protected/QEMU 路线。

---

## 1. 参考项目矩阵

| 项目 | 固定提交 | 许可证 | 与本项目相关的部分 | 可借鉴 | 应避免/限制 |
| --- | --- | --- | --- | --- | --- |
| Termux app | `8629e63`（2026-09-26） | GPLv3 only | bootstrap 安装、`$PREFIX`、终端、`TermuxService` | prefix 布局、bootstrap 解包、终端/会话生命周期、FGS 保活 | 直接复制 GPL 代码需对应源码；它的 Android UI/权限模型与本项目不同 |
| Termux packages | `f1b5f46`（2026-10-21） | 脚本/补丁随包许可证 | `distcc`、`clang`、`gcc`、`qemu`、`virglrenderer-android`、`proot` | 交叉编译配方、版本、`bin/` 工具集、distcc/clang 组合 | 逐包许可证审计；GPL 工具分发义务；不能整体搬仓库 |
| Termux:X11 | `5986cfc`（2026-10-20） | GPLv3 | `lorie` X server、shell loader、显示传输 | X11 显示通道、输入注入、无 GPU 的软件渲染 UX | v1 不做 GUI；不能假设 protected 有 graphics HAL |
| Termux PRoot fork | `e2d843e`（2026-10-18） | GPLv2（PRoot/CARE） | ptrace、seccomp、fork/clone 修复 | 宿主侧 Userspace Compatibility 的稳定性经验 | protected 内无 ptrace allow、SELinux 默认拒绝，需真机探针；不能作为 Fast Lane 核心 |
| upstream PRoot | `25dc6a3`（2026-10-02） | GPLv2 | 同上 | syscall 拦截/路径重写/`-0` 语义 | 同上；性能开销随 syscall 密度上升 |
| termux-exec | `2cd0ba6`（2026-07-02） | Debian copyright（需逐项核） | `LD_PRELOAD` 路径重写 | 让 `$PREFIX` 程序在只读前缀上找到 libs/工具 | 只适用于 Bionic/Android；不能替代内核 namespace |
| Termux glibc packages | `75fa9e0`（2026-10-20） | MIT（仓库元数据） | `glibc-runner` / glibc 包 | 原生 glibc 前缀的可行性、loader/sysroot 参考 | 镜像仓库，实际包许可证逐项核；不能假设与 Microdroid Bionic 兼容 |
| gvisor-tap-vsock / gvproxy | `1cd3f04`（2026-10-13） | Apache-2.0 | 用户态 TCP/IP、vsock 监听、端口转发 | 宿主侧 TCP↔vsock 桥、连接生命周期、转发 API | guest 内 `gvforwarder` 需要 TAP/CAP_NET_ADMIN；protected payload 不可用 |
| DroidVM | `3348830`（2026-10-12） | GPLv3 | Gunyah/GenieZone/KVM、crosvm/QEMU、VirGL/GfxStream、VNC | 多后端抽象、GPU/VNC/磁盘/网络 UI、Gunyah 启动参数 | 需要 root；`pseudo-unprotected` 不是官方 non-protected；不能作为无 Root 产品路径 |
| Podroid | `332138a`（v1.3.0，2026-10-20） | GPL-2.0-or-later | QEMU/AVF 双后端、QEMU 调优、vsock/9p | QEMU 调优候选、engine/router 纯函数、AVF CPU policy | protected-only 直接回退 QEMU；不能把其结论套到 Microdroid payload |
| AOSP Virtualization | `175a51b`（2026-03 时代快照） | Apache-2.0 | `VirtualMachineConfig`、Microdroid、encrypted storage、vsock | protected payload API、`distccd --inetd` 可用的进程/stdio 模型 | 目标 ROM 的 APEX 可能不同；必须真机验证 |
| AOSP system/sepolicy | 浅克隆 main | Apache-2.0 | Microdroid 域、`encryptedstore_file`、`microdroid_app` | 明确 exec/noexec/ptrace/vsock 边界 | 目标 ROM 可能定制；以真机 denial 为准 |

---

## 2. Termux：最接近 Fast Lane 的现成用户空间

### 2.1 它是什么

- Termux 是 Android app + 一个 Bionic 用户空间前缀（`$PREFIX=/data/data/com.termux/files/usr`）。
- 首次启动解压 `bootstrap-aarch64.zip`，包含 `bin/`、`lib/`、`etc/`、`home/` 等。
- `termux-exec` 通过 `LD_PRELOAD` 重写路径，使程序在前缀内找到 `lib/`、`bin/`。
- `pkg`/`apt` 安装的包写入 `$PREFIX`；不需要 root、不需要 chroot、不需要 proot。
- 终端是 app 内 VT100/xterm 模拟；`TermuxService` 持有 PTY 和前台服务。

### 2.2 为什么适合 protected Microdroid

- Microdroid 是 Android/Bionic 用户空间；Termux 包也是 Bionic/ARM64，ABI 匹配。
- Microdroid 允许从 APK `bin/` 执行（zipfuse 给 `bin/` execute 位），刚好可以放一个只读 Termux 前缀。
- `encrypted storage` 可以挂 `$HOME`、`$TMPDIR`、包缓存；数据持久且宿主不可读。
- 不需要 chroot/proot/mount；避开 SELinux 的硬门。
- 终端可以走 vsock；不需要 Android Binder/UI。

### 2.3 不能做什么

- 不能 `pkg install` 到只读 APK `bin/`；装到 encrypted storage 也不能执行（`MS_NOEXEC`）。
- 不能提供 systemd/PID1、Docker/Podman、原生 `eth0`、GPU、音频、USB。
- 不能保证所有 Termux 包都能在 Microdroid 的 Bionic/Android API 版本上运行；需要逐包验证。
- 不能把 Termux 的 GPLv3 代码直接复制进本项目而不履行对应源码义务；只借鉴布局/机制，或按 GPL 兼容方式使用。

### 2.4 建议的 v1 形态

- 先验证一个**最小 Termux bootstrap**（bash + coreutils + git + clang）从 `/mnt/apk/bin/usr` 运行。
- 如果 bootstrap 的符号链接/绝对路径在 APK `bin/` 中不成立，退回**静态 busybox + 少量静态工具**。
- 工具集清单、SHA256、许可证、来源全部版本化；不放任 APK 体积膨胀。

---

## 3. PRoot：原生指令，但不是硬件虚拟化

### 3.1 机制

- PRoot 用 `ptrace` 拦截子进程的 syscall，模拟 `chroot`、`mount --bind`、`binfmt_misc`。
- guest 指令由 CPU 直接执行（不是 QEMU 翻译），所以 CPU 计算本身接近原生。
- 每个 syscall 都要陷入 ptrace、参数转换、结果回写；文件/进程/网络密集负载有明显开销。
- 公开数据差异很大：有文章称 20–50%，也有称 <5%；取决于 syscall 密度和测量方法。不能引用单一数字当结论。

### 3.2 在 protected Microdroid 内大概率不可行（需真机探针）

- AOSP `microdroid_payload.te`（commit `7836cbb29`）中**没有**任何 ptrace allow，也**没有**显式 neverallow ptrace。
- SELinux 默认拒绝：没有 allow 的权限不会成立，因此 PRoot 的 `PTRACE_TRACEME`/`PTRACE_SYSCALL` 在 payload 域内大概率不可用。
- 这是“无 allow、默认拒绝”，不是“官方 neverallow”；在 protected pVM 内做一次最小 ptrace 探针之前，不应写成“官方明确禁止 PRoot”，也不应为它设计“绕过 ptrace”的方案。

### 3.3 宿主侧的价值

- PRoot + proot-distro 仍然是“无 Root 跑完整发行版用户空间”的成熟方案；可以作为 **Host Native / Userspace Compatibility** 兜底。
- 它运行原生 ARM64 指令，CPU 不经过 TCG；对 CPU 密集、syscall 较少的任务可能比 QEMU 快。
- 但它没有 guest kernel、systemd、Docker、真正隔离；不能替代 Full Linux，也不能提供 protected 隔离。
- 可作为 QEMU 之外的“快速但弱隔离”车道，或作为 Fast Lane 不可用时的降级；产品语言必须区分。

---

## 4. Termux glibc / termux-exec：原生 glibc 前缀的可能

- `termux-glibc` 提供 glibc 运行时/包，`glibc-runner` 让 glibc 程序在 Termux 前缀中运行。
- 这证明“在 Android 内核上原生跑 glibc 用户空间”是可行的，不需要 proot；但依赖 loader/sysroot/路径配置。
- 对 Fast Lane 的潜在价值：如果 Termux Bionic 工具链不满足某些 Linux 工具，glibc 前缀可以提供更接近发行版的 ABI。
- 风险：glibc 与 Android 内核/`/etc`/NSS/动态 loader 的兼容性、包许可证、前缀只读/可写边界。
- v1 不把 glibc 作为必需路径；先验证 Bionic Termux；glibc 作为 FL-12 研究/备选。

---

## 5. Microdroid：protected 下唯一可用的硬件执行环境

### 5.1 可用 API

- `VirtualMachineConfig.Builder` + `setPayloadBinaryName` + `setProtectedVm(true)` + `setMemoryBytes` + `setCpuTopology` + `setEncryptedStorageBytes` + `setDebugLevel`。
- `VirtualMachine.connectVsock(port)` 返回 `ParcelFileDescriptor`；宿主可当字节流。
- `AVmPayload_getApkContentsPath()` → `/mnt/apk`；`AVmPayload_getEncryptedStoragePath()` → `/mnt/encryptedstore`。
- `AVmPayload_getVmInstanceSecret` / `isNewInstance`；可选远程证明。

### 5.2 已确认的限制

| 能力 | 结论 | 依据 |
| --- | --- | --- |
| 从 APK `bin/` 执行 | 允许 | zipfuse `bin/` 默认 `EXECUTABLE_FILE_MODE`；`allow coredomain system_file:file execute` |
| 从 encrypted storage 执行 | 禁止 | `MS_NOSUID|MS_NODEV|MS_NOEXEC`；neverallow execute |
| chroot/mount | 禁止 | payload caps 清空；无 `sys_admin` |
| PRoot | 大概率不可用（需真机探针） | `microdroid_payload.te` 无 ptrace allow，SELinux 默认拒绝；无显式 neverallow |
| TCP socket | 不应假设可用 | `microdroid_app` 没有通用 tcp_socket allow；distcc 用 `--inetd` 绕过 |
| vsock 入站 | 允许 | `microdroid_payload self:vsock_socket { create listen accept ... }` |
| vsock 出站 | 禁止 | `neverallow ... connect` |
| 原生网络/GPU | 不提供 | protected 无 virtio-net；Microdroid 无 graphics HAL |
| 多 vCPU | 允许但需验证 | `CPU_TOPOLOGY_MATCH_HOST`；异构 topology 可能 reset |

### 5.3 关键设计推论

- **不要**在 payload 里假定 TCP：`distccd` 用 `--inetd`，Binder/vsock 用 raw socket；需要 TCP 的守护进程要么改成 stdio，要么不放进 Fast Lane。
- **不要**在 payload 里假定可写可执行目录：工具放 APK `bin/`，数据放 encrypted storage。
- **不要**在 payload 里假定 systemd/root：payload 是 `microdroid_app` 域，caps 被清空；用显式进程管理。
- **不要**把 debug level FULL 带进生产：它会削弱保护；用 vsock/metrics 观察，不用 console。

---

## 6. gvproxy / gvisor-tap-vsock：宿主侧网络桥，不是 guest 网络

- `gvproxy` 是用户态 TCP/IP + 端口转发；支持 `-listen unix://...`、`-listen-qemu`、`-listen-bess`、`-listen-vfkit` 等。
- `gvforwarder` 在 guest 内需要 TAP 设备/CAP_NET_ADMIN；protected payload 没有这些能力。
- 因此：
  - **QEMU Full Lane** 继续用现有 passt/gvproxy；不要改。
  - **Fast Lane** 用宿主侧 SOCKS5/HTTP CONNECT 代理；payload 内工具通过 `ALL_PROXY`/`http_proxy`；不创建 `eth0`。
  - **distcc** 用宿主 TCP↔vsock 桥，payload 内 `distccd --inetd`；不需要 guest TCP。
- gvproxy 的连接生命周期/转发 API 可作为宿主代理的实现参考；许可证 Apache-2.0。

---

## 7. DroidVM：root 下的 Gunyah 参考，不是无 Root 方案

- 支持 `/dev/gunyah`、`/dev/gzvm`、`/dev/kvm`，需要 root（Magisk/KernelSU/APatch）。
- 支持 crosvm/QEMU、UEFI、VirGL/GfxStream、VNC、9p、桥接网络。
- 其 `pseudo-unprotected` 模式是 fork 策略，不是官方 AVF `getCapabilities` 的 non-protected；不能拿它证明 official non-protected 可用。
- 对本项目的价值：
  - 多后端抽象/设备能力探测/GPU/VNC/磁盘 UI 的参考；
  - 说明“Gunyah + crosvm + 自定义内核”在 root 下可行，但不是我们的无 Root 路径；
  - 其 GPLv3 代码不能直接复制进本项目（本项目 GPLv3 兼容，但仍需履行义务；且 root 依赖不符合产品边界）。

---

## 8. 跨车道方案矩阵

| 方案 | 隔离 | CPU | 完整发行版 | 原生网络 | GPU | 无 Root | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A. Host Termux（Bionic） | Android app 沙箱 | 原生 | 否 | 是（宿主） | 有限 | 是 | 快速兜底/参考；不是 VM |
| B. Host PRoot（proot-distro） | 弱（共享内核） | 原生指令 + ptrace | 是（用户空间） | 是（宿主） | 有限 | 是 | Userspace Compatibility 兜底；syscall 开销 |
| C. Protected Microdroid Fast Lane | protected pVM | 硬件 vCPU | 否（密封 toolbox） | 否（代理） | 否 | 是 | **当前主线** |
| D. QEMU Full Lane | 进程级 | TCG 慢 | 是 | 是 | VirGL | 是 | **Full Linux 主线** |
| E. QEMU + Fast Lane distcc | 混合 | 编译在 pVM | QEMU 保持 | QEMU 保持 | QEMU | 是 | **交叉优化首选** |
| F. QEMU + Host Termux distcc | 混合 | 编译在宿主 | QEMU 保持 | QEMU 保持 | QEMU | 是 | 无 protected 时的备选，但不隔离 |

推荐：**C + D + E**。A/B 作为 Userspace Compatibility 的研究/兜底，不作为 protected 硬件加速承诺；F 只在 C 不可用时评估，且明确不是 protected。

---

## 9. Distcc 交叉编译：具体可行路径

### 9.1 为什么是 distcc

- distcc 是成熟的分布式 C/C++ 编译前端；客户端本地预处理，服务器编译，返回 `.o`。
- 它不要求共享文件系统、同步时钟或相同头文件；只要求服务器有兼容的编译器。
- 对 QEMU TCG 的 Full Lane，编译是 CPU 密集、可并行的主瓶颈；把编译放到 protected pVM 的硬件 vCPU 上，收益直接。
- 它不破坏 POSIX 语义：Makefile 显式使用 `distcc`/`CC=distcc clang`，不是透明 syscall 拦截。

### 9.2 数据路径（关键）

```
QEMU guest (distcc client, clang -E locally)
   │ TCP to host port (passt/gvproxy)
   ▼
Host app: TCP listener (127.0.0.1)
   │ per-connection byte stream
   ▼
Fast Lane VM: vsock port
   │ payload accepts, spawns distccd --inetd
   ▼
distccd --inetd (stdio) + clang from /mnt/apk/bin
   │ object bytes back over vsock -> TCP -> guest
```

- `distccd --inetd` 从 stdin/stdout 服务一个客户端，不需要 TCP socket、不需要 CAP_NET_ADMIN、不需要 TAP。
- payload 只做字节桥：vsock stream ↔ child stdin/stdout；不解析 distcc 协议。
- 宿主侧只做 TCP↔vsock 桥；不解析 distcc 协议；连接级超时/并发上限。
- 不需要 guest 原生网络；QEMU guest 只连宿主 loopback 端口。

### 9.3 编译器一致性

- 客户端本地预处理使用 guest 的 clang；服务器必须使用**同版本/同 target/flags**的 clang，否则目标文件可能不兼容。
- v1 只支持 **clang**，因为 clang 可作为交叉编译器并较容易静态打包；GCC 需要更严格的版本匹配。
- 双方使用同一 `clang --version`、同一 `-target aarch64-linux-gnu`（或 guest 的实际 target）、同一 `-std`/`-fPIC` 等 flags；distcc 会传递 args。
- 先用一个最小 C 文件验证 `.o` 可链接/运行；再跑真实项目。
- 如果 guest 是 Debian/Ubuntu glibc，服务器 clang 编译 `.i` 不需要 glibc 头文件；链接仍在 guest 完成。ABI 由 clang target/flags 决定。
- C++ 的 libstdc++/libc++ ABI 更复杂；v1 只承诺 C，C++ 需单独验证。

### 9.4 并发与调度

- distcc 客户端 `-j` 控制并发；宿主桥限制每 VM 的最大连接数；payload `distccd --inetd` 每连接一个进程。
- Fast Lane 的 vCPU 数决定并行编译上限；1 vCPU 时并发收益有限，MATCH_HOST 多 vCPU 需先验证稳定性。
- 监控：每 job 的编译时间、字节数、失败率；不记录源码正文。
- 失败回退：distcc 客户端在服务器不可达时本地编译（默认行为）；宿主桥/VM 停止时，guest 构建继续但变慢，不失败。

### 9.5 与 ccache/sccache 的关系

- `ccache`/`sccache` 可减少重复编译；`distcc` 负责跨车道执行。
- 可在 Fast Lane 内启用 ccache（缓存目录在 encrypted storage）；但缓存的数据/对象是用户代码，需脱敏/清理策略。
- v1 先不做缓存，先证明端到端正确性与收益。

---

## 10. QEMU 调优参考（Podroid 1.3.0 + Termux packages）

- `-cpu max,sve=off,pauth-impdef=on`：启用 LSE/加密/PAC，关闭 SVE；Podroid 默认。
- `-accel tcg,thread=multi,tb-size=256/512`：MTTCG + 大 TB cache；JIT 密集 guest 受益。
- 每盘 iothread + `num-queues=vCPU`；`cache=writeback`、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`。
- `-object rng-random` + `virtio-rng-pci`；`-overcommit mem-lock=off`。
- guest `mitigations=off`、`elevator=mq-deadline`、ZRAM lz4。
- 所有项都必须单变量 A/B；默认值不变，除非同设备/同镜像/同资源/控温/n≥10 有显著收益且无 >5% 回归。
- 注意本项目 `QemuTuning.tbSizeKb` 字段名与 QEMU `tb-size`（MiB）单位可能不一致；实验前先核对。

---

## 11. 明确不建议的路线

1. 在 protected 内跑自定义内核/完整发行版：pvmfw 签名 + label + 网络硬门，不可行。
2. 在 protected 内用 chroot/proot/mount/overlayfs/FUSE 绕过 noexec：没有对应的 SELinux allow（默认拒绝）+ caps 清空；不要浪费。
3. 把 `distccd` 直接监听 TCP 放进 payload：`microdroid_app` 没有通用 TCP allow；用 `--inetd`。
4. 把 gvforwarder/TAP 放进 payload：需要 CAP_NET_ADMIN/TUN；不可行。
5. 把 QEMU 放进 protected pVM 以“用硬件加速”：pVM 内没有 KVM，QEMU 仍 TCG，且多一层，必更慢。
6. 复制 Podroid/Termux/DroidVM 的 GPL 代码而不履行对应源码义务；只借鉴机制，逐项审计。
7. 把 Host Termux/PRoot 的性能数字直接当 Fast Lane 的数字；它们没有 protected 隔离，也没有 pVM 的 I/O/网络特征。

---

## 12. 待真机回答的问题

1. 当前 HONOR 设备能否用 `VirtualMachineConfig` + `setPayloadBinaryName` + `setProtectedVm(true)` 创建/运行 payload？
2. payload 能否从 `/mnt/apk/bin` 执行 Termux bash / 静态 busybox？
3. Termux bootstrap 的符号链接/绝对路径在 APK `bin/` 中是否成立？是否需要 flat 化/重写？
4. `distccd --inetd` 能否在 payload 中从 `bin/` 执行，并通过 vsock 完成一次真实编译？
5. `MATCH_HOST` 多 vCPU 是否稳定；1 vCPU 的 distcc 收益是否足够？
6. encrypted storage 的持久化/导出/删除语义是否满足产品要求？
7. 宿主 SOCKS5 代理能否支持 DNS/TLS/长连接；吞吐/延迟如何？
8. Fast Lane 与 QEMU 同资源对比，哪些 workload ≥2x；distcc 端到端是否 ≥1.5x？
9. Termux Bionic 工具链与 guest clang 的版本/ABI 是否足以产出可链接的 `.o`？
10. JIT（Node/JVM）是否被 W^X 拒绝；Fast Lane 是否只能做 AOT？

---

## 13. 来源

### 13.1 本地参考项目

- `references/termux-app` @ `8629e63`
- `references/termux-packages` @ `f1b5f46`（`packages/distcc`、`packages/ccache`、`packages/sccache`、`packages/mesa`、`packages/virglrenderer-android`）
- `references/termux-x11` @ `5986cfc`
- `references/termux-proot` @ `e2d843e`
- `references/proot` @ `25dc6a3`
- `references/termux-exec` @ `2cd0ba6`
- `references/termux-glibc` @ `75fa9e0`
- `references/gvisor-tap-vsock` @ `1cd3f04`
- `references/droidvm` @ `3348830`
- `references/Podroid` @ `332138a`
- `references/aosp-virtualization` @ `175a51b`
- `references/aosp-sepolicy`（浅克隆 main）

### 13.2 官方/外部

- AOSP Microdroid: https://source.android.com/docs/core/virtualization/microdroid
- AOSP AVF architecture/security: https://source.android.com/docs/core/virtualization/architecture
- AOSP Virtualization README: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/README.md
- distcc: https://www.distcc.org/ ；协议: https://www.distcc.org/distcc-lca-2004.pdf
- Termux wiki: https://wiki.termux.com
- PRoot: https://proot-me.github.io
- gvproxy/gvisor-tap-vsock: https://github.com/containers/gvisor-tap-vsock
- DroidVM: https://github.com/Droid-VM/DroidVM
- Podroid: https://github.com/ExTV/Podroid

---

报告人：CodeBuddy / DeepSeek-V4.1-Flash ｜ 日期：2026-10-21 ｜ 本报告未运行设备/构建，性能数字均为待验证假设。
