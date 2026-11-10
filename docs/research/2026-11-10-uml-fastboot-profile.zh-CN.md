# 研究报告：UML fast-boot profile（U1）——最小内核 + 内置 initramfs + 预烘焙 rootfs

- 日期：2026-11-10
- 会话：u1-fastboot（工单 `.scratch/uml-zero-device-20261110/issues/02-fastboot-profile.md`）
- 基线：`62d96e99`（`research/uml-zero-device-direction-20261110`）；分支 `feat/uml-u1-fastboot`
- 性质：零设备研究原型；不写产品代码、不改主构建、不改版本号/签名/发布通道
- 产物：`research/uml-fastboot/`（`defconfig-fast`、`defconfig-fast.fragment`、`initramfs/init`、`build-fast.sh`、`build-rootfs.sh`、`bench-fast.py`、`manifest.md`）

- 触发票号 / 日期 / 执行者（模型）：`02-fastboot-profile` / 2026-11-10 / CodeBuddy Code（DeepSeek-V4.1-Flash）

## 问题清单

1. 现有 pin（`169b17ff` / Linux 7.2.0-rc4 / NDK r29）能否构建最小 ARM64 UML 内核，并通过 16 KB PT_LOAD 检查？
2. 内置 initramfs + 预烘焙 rootfs 能否在 x86_64 宿主参考上到 T3？T0–T3 是多少（n/中位数/夹具）？
3. UP vs SMP、hostfs vs ubd0/ext4、内置 initramfs vs 外置 rootfs 三组对照各是什么结果？
4. fast shell 与 full systemd 的承诺边界是什么？需要哪些配置/能力？
5. 哪些结果是 `not-tested`，不能外推？

## 逐问题结论（来源见 §9）

1. **能**：ARM64 UP/SMP 内核 + 16 KB stub + initramfs 构建成功；`check-16kb-kernel.sh` 全过；两次干净重建逐字节一致。
2. **能**：x86_64 宿主参考（`x86_64-host-tlinux4-5.4-4k`，n=5）UP 的 T0→T3 中位 112.6–151.8 ms、guest T3 中位 80–100 ms；但宿主 5.4 只能 ptrace，不是设备 SECCOMP。
3. **两组完成、一组部分**：hostfs vs ubd 与 initramfs vs 外置 rootfs 完成；UP vs SMP 只有 ARM64 构建级对比，宿主 ptrace 把 `ncpus=4` 夹到 1。
4. **两档承诺**：fast shell 只承诺 T3；systemd/Docker/网络服务不在本 profile，T4 未测。
5. **not-tested**：ARM64 设备 boot、16 KB 宿主、systemd T4、host-level 断网、proot 同轮对照。

## 0. 结论

1. **UML 不能达到 proot 的“无内核秒开”。** proot 的 `login` 只是一次 `exec`，没有 guest 内核；UML 必须付内核 init + init/挂载成本。本 profile 的目标是**亚秒级 T3**，不是零成本。
2. **fast shell 的 T3 已在 x86_64 宿主参考上量到 UP 中位 113–152 ms（guest T3 80–100 ms）**；内核到 init 约 40–50 ms，root/pseudo-fs 就绪与第一条命令在 10 ms 粒度上常重合。夹具为 `x86_64-host-tlinux4-5.4-4k`，n=5，**不是 ARM64 设备结论**。SMP 内核列因 ptrace 夹到 1 CPU，中位 168–206 ms，不作多核结论。
3. **rootfs 通道不是这一量级的主导项。** hostfs 与 ubd0/ext4 的差在 10–20 ms 量级（n=5、宿主负载高，方向不足以定序）；内置 initramfs 与外置 rootfs 的差也在同一量级。真正的固定成本是 UML 内核 init。
4. **fast profile 只承诺 T3。** systemd/Docker/网络服务不在本 profile 的承诺内；full systemd 的 T4 必须另测，且本轮的裁配置（无 MEMCG/PIDS、无 overlay/loop）不足以支撑 Docker。
5. **UP vs SMP 的运行时对照在本宿主未成立**：宿主内核 5.4 没有 `close_range`，UML 退回 ptrace；ptrace 模式把 `ncpus=4` 夹到 1 CPU。ARM64 SMP 只完成构建（`not-tested` 设备 boot）；UP/SMP 的运行时结论仍只能引用 2026-11-07 设备轮（两者同量级）。

## 1. 口径与夹具

严格按 `.scratch/uml-zero-device-20261110/spec.md` §2：

| 指标 | 本报告采集 |
|---|---|
| T0 spawn | 宿主 `time.monotonic_ns()`，`Popen()` 返回后 |
| T1 kernel init | guest printk 时间戳，`Run /<init> as init process` 行 |
| T2 rootfs mounted | guest `/proc/uptime`，`U1_T2_READY`（root + proc/sys/dev/devpts/cgroup2 就绪；外置根在 `switch_root` 之后） |
| T3 first command | guest `/proc/uptime`，`U1_READY`（真实执行 busybox true 之后） |
| T0→T3 | 宿主 `CLOCK_MONOTONIC` 从 T0 到读到 `U1_READY` 行；与 guest 时间列分开报告 |

- 夹具：`x86_64-host-tlinux4-5.4-4k`（8 vCPU，4 KB 页，宿主内核 5.4.241，`/dev/shm` noexec，共享宿主负载高）。
- 每列 n=5，轮转交错；表中给中位数 [min–max]；guest `/proc/uptime` 粒度 10 ms。
- **跨夹具不除**：宿主 T0→T3 与 guest T3 是两种时钟；不与 proot/QEMU 数字相除。

## 2. 构建结果（ARM64，主交付）

源码 pin `Raymer8639/linux-um-arm64` `um-arm64-v0.3.0` / commit `169b17ff…` / Linux 7.2.0-rc4；NDK r29；Ubuntu 24.04 容器；`KBUILD_BUILD_TIMESTAMP` 与 initramfs mtime 固定。

| 产物 | 大小 (B) | sha256 |
|---|---|---|
| `arm64-up/linux-fast`（内置 initramfs + 16 KB stub） | 9,517,760 | `21e1c3f9…4389` |
| `arm64-up/linux-fast-noinitrd` | 8,862,400 | `a5efce2b…17fb` |
| `arm64-up/stub_exe`（16 KB 重链） | 2,056 | `87666f91…a09a12` |
| `arm64-up/initramfs.cpio.gz` | 661,899 | `7523c9a2…8381` |
| `arm64-smp/linux-fast` | 10,355,504 | `45b3ccdd…69a7` |
| `arm64-smp/linux-fast-noinitrd` | 9,700,144 | `99f6c3e7…260e` |
| `arm64-smp/stub_exe` | 2,056 | `87666f91…a09a12` |

- `check-16kb-kernel.sh` 对 UP/SMP 内核 + stub **全部通过**：`ELF_16KB_OK all_targets_pass`；stub 单 `LOAD` 段 `p_align=0x4000`。
- 16 KB 重链 stub 与既有 `research/uml-kernel-build/release-assets/v0.3.0/stub_exe-16k` **逐字节一致**；原版 stub（常规链接）也与 release 逐字节一致（`731c48d5…`）。
- **两次干净 OUT 重建**：`linux-fast`、`linux-fast-noinitrd`、`stub_exe`、`initramfs.cpio.gz`、两份 config 全部 **逐字节一致**；详见 `research/uml-fastboot/manifest.md` §5。
- 16 KB **宿主** 仍 `not-tested`；16 KB **guest** 是 pinned 内核既有能力。

配置取舍见 `defconfig-fast.fragment`：保留 cgroup v2（sched/cpuacct/freezer/device/blkio）、devtmpfs(+mount)、devpts、proc/sysfs/tmpfs、ext4、ubd、hostfs、seccomp(+filter)、namespaces/netns、binfmt elf/script、null channel、mconsole、kallsyms、IKCONFIG、static link、printk time、net/unix/inet/packet/vector/tun；裁掉 debug/trace、HID/input 驱动、WLAN/以太网、netfilter、USER_NS/PID_NS/MEMCG、loop/NBD/ISO9660/autofs/quota/FUSE/overlay、多余 UML channel、BINFMT_MISC/PPP/SLIP、除 gzip 外解压器、modules/rust。`CONFIG_INPUT=y` 是 TTY 的 `select` 结果，HID/输入驱动已关。

## 3. x86_64 宿主参考 T0–T3（参考，不是设备结论）

夹具 `x86_64-host-tlinux4-5.4-4k`；hostfs 目录与 64 MB ext4 均由 `build-rootfs.sh` 从 Alpine 3.22.2 minirootfs + `/init` + `/um-command.sh` 生成；n=5，轮转。

| case | rootfs 通道 | 实际 ncpus | seccomp | T0→T3 ms 中位 [min–max] | guest T3 ms 中位 [min–max] | guest T1 ms (n=5) | guest T2 ms (n=5) |
|---|---|---|---|---|---|---|---|
| `initramfs-up` | 内置 initramfs | 1 | ptrace | **151.8 [101.9–310.9]** | **80.0 [70–120]** | 50 | 80 |
| `hostfs-init-up` | initramfs → hostfs switch_root | 1 | ptrace | 138.6 [126.0–184.6] | 100.0 [100–150] | 50 | 100 |
| `ubd-init-up` | initramfs → ext4 switch_root | 1 | ptrace | 133.1 [126.3–193.5] | 100.0 [100–160] | 50 | 100 |
| `hostfs-direct-up` | hostfs 直启（无 initramfs） | 1 | ptrace | 112.6 [108.9–201.5] | 80.0 [80–170] | 40 | 80 |
| `ubd-direct-up` | ubd0/ext4 直启（无 initramfs） | 1 | ptrace | 116.9 [107.0–149.3] | 90.0 [80–110] | 40 | 90 |
| `initramfs-smp` | 内置 initramfs，SMP 内核 | **1（请求 4）** | ptrace | 167.7 [121.1–189.2] | 130.0 [90–150] | 110 | 130 |
| `hostfs-init-smp` | initramfs → hostfs，SMP 内核 | **1（请求 4）** | ptrace | 206.4 [148.8–211.7] | 170.0 [110–170] | 110 | 170 |

解释与限制：

- **SECCOMP 未启用**：宿主 5.4 无 `close_range`（5.9+），UML `init_seccomp()` 探测失败；`seccomp=auto` 退回 **ptrace**。ARM64 设备 6.12 宿主上是 SECCOMP 快路径（既有 2026-10-29/11-07 证据），本表不能代表设备的 syscall 成本。
- **SMP 被夹到 1 CPU**：ptrace 模式不支持多 CPU，`ncpus=4` 被内核降为 1；`U1_INFO` 实测 `ncpus=1`。因此这两列不是有效的 UP vs SMP 对照。
- 宿主负载高（`getloadavg` 启动时 ~16，`/proc/loadavg` 2–3；共享宿主），T0→T3 的 min–max 波动较大；guest T3 更稳定。**只做同轮方向参考，不做跨夹具倍数**。
- 所有运行都在 READY 后由 harness `SIGTERM` 停止；UML 进程退出 `-6`（SIGABRT）。这是宿主 5.4 上 guest `poweroff`/停止路径触发 UML `os_dump_core` 的已知失败模式，发生在 T3 之后；不影响 T3 采集。设备 6.12 的既有 `poweroff` 路径为 exit 0（2026-10-29 证据），待设备窗口复验。
- 启动日志有 `epollctl add err fd …, Operation not permitted`（UML 控制台 fd 注册）与 `Using a channel type which is configured out of UML`（裁掉 pty/tty/xterm channel 后的默认 console 探测）两类噪声；均未阻断启动。

- **断网/无包管理器**：所有 case 都不传 `vec0=`/网络设备，guest 日志无网络接口初始化；`initramfs/init` 与 `um-command.sh` 均不调用包管理器，Alpine rootfs 的 `apk` 未被调用。宿主级“断网注入”未单独做（guest 本身没有网络设备），属于 `not-tested` 的边界。
- **失败退出码**：PID 1 不能以非零码退出（内核会转成 kernel panic），所以 init 的失败契约是 `UM_INIT_ERROR` + `poweroff`；`bench-fast.py` 的 `main` 对任何 init 错误返回 1（非零），作为可执行的失败判据。

## 4. 三组对照

### 4.1 内置 initramfs vs 外置 rootfs

同带 initramfs 内核：`initramfs-up`（80 ms guest T3）vs `hostfs-init-up`（100 ms）/`ubd-init-up`（100 ms）。差 20 ms，接近 10 ms 粒度上限；**没有稳定排序**；外置 rootfs 的 mount + switch_root 成本约 0–20 ms。结论：在这一启动量级，rootfs 形态不是主导项；选择应由持久化/可写/镜像分发决定，而不是 T3。

### 4.2 hostfs vs ubd0/ext4

直启列：hostfs 80 ms vs ubd 90 ms guest T3（宿主 T0→T3 112.6 vs 116.9 ms）。差 10 ms（guest）/4.3 ms（host），在 10 ms 粒度与宿主噪声内；**不能据此宣称 ubd 更快**。ubd 需要 64 MB 镜像与 `mkfs.ext4`，hostfs 需要宿主目录；两者都可用，产品选择看写时复制/快照/权限边界（U2/U5）。

### 4.3 UP vs SMP

- **构建级**：ARM64 UP 9,517,760 B；SMP 10,355,504 B（+8.8%）；stub/initramfs 相同。
- **运行时**：本宿主 ptrace 强制 1 CPU，未取得有效 SMP T3；ARM64 SMP 设备 boot `not-tested`。现有可引用证据仍是 2026-11-07 设备轮：UP boot 0.21–0.43 s、SMP(ncpus=4) 0.23–0.43 s，同量级。
- 结论：**不把本轮 x86_64 SMP 列当多核结论**；下个设备窗口用 `arm64-smp/linux-fast` + `ncpus=4` 复测。

## 5. fast shell vs full systemd 取舍

| 维度 | fast shell（本 profile） | full systemd（对照，未在本轮实测） |
|---|---|---|
| 承诺 | **T3 first command** | T4 `multi-user.target`/网络/SSH |
| 内核配置 | 最小：无 MEMCG/PIDS、无 overlay/loop、无 netfilter、无 HID/WLAN | 需要 cgroup v2 完整控制器、overlay/loop、网络栈、更多驱动/安全选项 |
| rootfs | 内置 initramfs 或 Alpine minirootfs（无包管理器调用） | 预烘焙发行版 + systemd unit + 首启 provisioning |
| 启动路径 | kernel → init → 挂载 → `/um-command.sh` | kernel → initramfs → systemd → target |
| 本报告数据 | guest T3 80–120 ms（x86_64 参考） | **未测**；既有 QEMU T4 为分钟级，UML T4 需设备/下一轮 |
| Docker | 不承诺；无 MEMCG/PIDS/overlay/loop | 需额外配置，且 cgroup v2 控制器要齐全 |
| 适用 | 快速 shell、脚本、单命令、轻量工具 | 服务、Docker、完整发行版体验 |

> 两档不要合并成一个“启动时间”。fast profile 的 `cgroup2` 挂载只是“cgroup2 文件系统可用”，不等于 Docker 可用。

## 6. 失败模式

| 现象 | 根因 | 处置 |
|---|---|---|
| 宿主 `seccomp=on` 直接 fatal | 宿主 5.4 无 `close_range`；`on` 是强制 SECCOMP | 宿主参考用 `seccomp=auto`；ARM64 设备用 `on`（6.12 宿主） |
| `ncpus=4` 实际 1 | ptrace 模式不支持 SMP | 记录 `U1_INFO ncpus=1`；设备窗口用 SECCOMP 复测 |
| READY 后 UML 退出 -6 | 宿主 5.4 上 `poweroff`/停止路径触发 `os_dump_core` | harness 在 READY 后 SIGTERM；T3 不受影响；设备 6.12 复验 exit 0 |
| `epollctl add err … EPERM` | 控制台 fd 注册 | 非阻断；`con0=null,fd:1` 已避开输入侧 |
| `Using a channel type which is configured out` | 裁掉 pty/tty/xterm channel 后内核探测默认 console | 非阻断；`con=null` 已显式关闭 |
| 宿主 `/dev/shm` noexec → `PROT_EXEC mmap` 失败 | UML `check_tmpexec()` | harness 设 `TMPDIR` 到普通目录；设备用 `filesDir` |
| x86_64 用 GNU ld 链接 stub 失败 | 下游 `STUB_EXE_LDFLAGS` 含 LLD-only `--no-rosegment` | 参考构建用 `-Wl,-n -static`；ARM64 保持 LLD 16K 重链 |
| 下游树 x86 构建 glibc 冲突 | `compiler_attributes.h` 强制 include 与 glibc `__attribute_alloc_size__` | 参考构建 CC wrapper 丢弃该 force-include；ARM64/bionic 不受影响 |

## 7. 下个设备窗口命令入口（not-tested）

1. 取 `out/arm64-up/artifacts/linux-fast` → `libuml_kernel.so`，`stub_exe` → `libuml_stub.so`，核对 sha256，放入 `research/uml-runner` 的 `jniLibs/arm64-v8a/`；`./gradlew assembleSdk36Debug` 安装。
2. 内置 initramfs 启动参数：
   ```
   mem=512M rw con=null con0=null,fd:1 seccomp=on panic=1 printk.time=1
   um.hold_ms=1000
   stub_exe=<nativeLibraryDir>/libuml_stub.so
   ```
3. 外置 rootfs 对照：把 `out/rootfs-aarch64.ext4` 推到 `filesDir`，追加
   `ubd0=<filesDir>/rootfs-aarch64.ext4 umroot=/dev/ubda umrootfstype=ext4`；
   或 hostfs：`umroot=<filesDir>/rootfs umrootfstype=hostfs`。
4. 判定：console 出现 `U1_T2_READY`、`U1_READY`；失败只认 `UM_INIT_ERROR code=… step=…`。采集 T0–T3 用本目录 `bench-fast.py`（U0 的 harness 若已合入可复用同一字段）。
5. 必测：ARM64 SECCOMP 模式、`ncpus=4` 真 SMP、16 KB 宿主（若可获得）、`poweroff` 退出码、app 域 exec fast kernel/initramfs。

## 8. not-tested 与边界

- ARM64 设备 boot：`not-tested`（零设备轮）。
- 16 KB 宿主：`not-tested`；本轮只验证 16 KB PT_LOAD 对齐。
- 宿主 x86_64 参考：Linux 5.4 + ptrace + 1 CPU；**不外推 ARM64 设备结论**。
- systemd/Docker/网络服务：fast profile 不承诺；未测。
- proot 对照：未在本轮同夹具测 proot；只引用其机制（无 guest 内核），不引用未测毫秒数。
- Alpine minirootfs 只是夹具；U2 的预烘焙 rootfs 才是产品输入。

## 9. 启动参数与一手来源

| 参数 | 归属 | 作用 | 本 profile 用法 |
|---|---|---|---|
| `init=` | 通用 | 指定 init 程序 | **无内置 initramfs 时**用 `init=/init`；有内置 initramfs 时内核先跑 `/init`，`init=` 不生效 |
| `rdinit=` | 通用 | 覆盖 initramfs 的 `/init` | 未用（只用内置 `/init`） |
| `root=` / `rootfstype=hostfs` / `rootflags=` | 通用 + UML hostfs | 根设备/根类型/hostfs 宿主目录 | hostfs 直启列 |
| `ubd0=` | UML | 挂载块设备镜像；设备名为 `/dev/ubda` | ubd0/ext4 直启列与 initramfs 外置根列 |
| `umroot=` / `umrootfstype=` | **本项目 init 自定义**（非内核 UML 参数） | 内置 initramfs 下指定外置根，供 `switch_root` | `hostfs-init-*` / `ubd-init-*` 列 |
| `mem=` | UML | guest 内存 | 256M（bench）/128M（smoke）/512M（设备建议） |
| `con=` / `con0=` | UML | 控制台通道；`con0=null,fd:1` 为输出到 fd1 | `con=null con0=null,fd:1` |
| `seccomp=on/auto/off` | UML | `on` 强制 SECCOMP；`auto` 探测失败退回 ptrace；`off` 禁用 | 设备 `on`；5.4 宿主 `auto` |
| `ncpus=` | 下游 ARM64 SMP | vCPU 数；默认 host-1 | 设备 4；宿主 ptrace 被夹到 1 |
| `stub_exe=` | 下游 | 外部 stub 路径 | runner/设备显式传入 |
| `panic=` | 通用 | panic 行为 | `panic=1` |
| `printk.time=` | 通用 | printk 时间戳 | `1`（T1 解析） |
| `initrd=` | UML | 外部 initrd 文件 | 未用（内置 initramfs） |

来源（均为 pinned 树内文件，2026-11-10 核对）：

- `Documentation/virt/uml/user_mode_linux_howto_v2.rst` §Arguments（`mem=`、`ubdX=`、`root=`、`conX=`）与 hostfs as root（`ubd0=/path/to/root/directory`、hostfs mount option）。
- `arch/um/Kconfig`：UML 选项、`HAVE_PAGE_SIZE_16KB if UML_ARM64`、`PAGE_SIZE` 注释（16 KB guest 可在 4 KB 宿主运行，反向不行）。
- `arch/um/os-Linux/start_up.c`：`seccomp=on/auto/off` 语义（`on` 强制、失败 fatal；`auto` 失败回退）、`Userspace mode: SECCOMP|ptrace`、ptrace 下 SMP 夹到 1 CPU。
- `arch/um/kernel/initrd.c`：`initrd=` 外部 initrd；内置 initramfs 走 `init/initramfs.c` 与 `usr/Kconfig` 的 `INITRAMFS_SOURCE`/压缩选项。
- `arch/um/configs/arm64_defconfig` 与 `arch/x86/configs/x86_64_defconfig`：ARM64/x86_64 的基线 defconfig。
- 下游能力/限制（PTRACE_SYSEMU、SECCOMP 快路径、16 KB guest、Docker/overlay2、cpuset 缺失）见 `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md` §4.2 对 `Raymer8639/linux-um-arm64` README 的引用。

> `umroot=`/`umrootfstype=` 不是内核 UML 参数；内核把它们当作未知参数保留在 `/proc/cmdline`，由 `initramfs/init` 解析。不要把这两个 token 写进产品启动器的通用参数表。

`um.shell=1` 与 `um.hold_ms=` 是测试/对照接口（进交互 shell、控制关机前停留），不是 fast profile 的内核契约；`umroot=`/`umrootfstype=` 只用于 U1 的“内置 initramfs vs 外置 rootfs”对照。

## 10. 证据

| 证据 | 路径 |
|---|---|
| 配置与取舍 | `research/uml-fastboot/defconfig-fast`、`defconfig-fast.fragment` |
| init / T3 探针 | `research/uml-fastboot/initramfs/init`、`initramfs/um-command.sh` |
| 构建与 manifest | `research/uml-fastboot/build-fast.sh`、`manifest.md` |
| 宿主 bench | `research/uml-fastboot/bench-matrix.example.json`；原始结果 `out/bench-matrix.example.result.{json,md}` 与 console `out/logs/`（均本地、gitignored；命令可重生成） |
| rootfs 夹具 | `research/uml-fastboot/build-rootfs.sh`、`out/rootfs-*.env` |
| 16 KB 检查 | `research/uml-kernel-build/check-16kb-kernel.sh` 输出 `ELF_16KB_OK all_targets_pass` |
| 设备历史基线 | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1/§5；`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1–§2 |

## 11. 交付、修改与合规

- **实际完成**：`research/uml-fastboot/`（配置、fragment、init、um-command、build-fast、build-rootfs、bench-fast、manifest、README）；ARM64 UP/SMP + x86_64 UP/SMP 构建；hostfs/ext4 rootfs 夹具；x86_64 T0–T3 n=5；两次干净重建；16 KB 检查；报告。
- **未完成 / 偏差**：ARM64 设备 boot、16 KB 宿主、真 SMP 运行时、systemd T4、host-level 断网、proot 同轮对照均 `not-tested`；宿主 5.4 的 shutdown `SIGABRT` 是宿主限制，设备 6.12 待复验。
- **修改文件**：`research/uml-fastboot/**`、`docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md`；未改 `app/`、`runtime/`、`guest-assets/`、版本号、签名、发布通道。
- **branch / base**：`feat/uml-u1-fastboot` / `62d96e99`。**commit**：认领 `8dfa6dfe` + 本次实现提交（见 handoff）。
- **测试 / 验证**：`check-16kb-kernel.sh` → `ELF_16KB_OK all_targets_pass`；`bench-fast.py` 7 case × n=5 全 `ok`；两次 ARM64 UP 构建 sha 一致；init 失败路径 `UM_INIT_ERROR code=23 step=mount_root` 且无 `U1_READY`；敏感数据扫描无命中（排除 `out/`，含 `out/logs/`）。
- **风险**：宿主参考 ptrace/1 CPU 不能代表设备；fast profile 裁掉 MEMCG/PIDS/overlay/loop，不承诺 Docker/systemd；UML shutdown 在 5.4 宿主崩溃；16 KB 宿主仍是 ACR 缺项。
- **后续动作**：下个设备窗口按 §8 命令入口验 ARM64 + SECCOMP + `ncpus=4` + 16 KB；U2 接管预烘焙 rootfs；U4 评估 warm start；U7 收 ACR 证据。
- **是否越界**：否。仅研究/文档；未写产品代码，未改 ACR/ADR 正文，未补造 ADR-0015。
- **是否触发 ACP**：否（研究原型，不改变架构决策；ACR 技术缺项不变）。
- **政策偏差说明**：U1 票面明确要求 `research/uml-fastboot/`，本目录沿用既有 `research/uml-kernel-build`、`research/uml-runner` 先例；`ARTIFACT_ORGANIZATION_POLICY` 的默认新目录是 `prototypes/<name>/`。为保持票面验收命令与既有 evidence 路径一致，本次不迁移；若后续统一收紧，可整体移动到 `prototypes/u1-fastboot/` 并更新引用。
