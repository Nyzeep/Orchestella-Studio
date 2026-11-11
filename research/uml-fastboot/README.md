# research/uml-fastboot — U1 fast-shell UML profile

零设备研究原型：用**最小 UML 内核 + 内置 initramfs + 预烘焙 rootfs** 把启动做到
**T3 first command**，并保留 systemd profile 作对照。不写产品代码、不改主构建、
不改版本号/签名/发布通道。

结论口径先写在前面：

- **UML 不可能达到 proot 的“无内核秒开”**。proot 没有 guest 内核；UML 有，
  必须付内核 boot + init 的成本。本 profile 的目标是**亚秒级 T3**，不是零成本。
- **fast shell 与 full systemd 是两档承诺**。本 profile 只承诺 T3；不承诺
  systemd、Docker、网络服务或包管理器。
- 宿主 x86_64 数据只是**协议验证**，不是 ARM64 设备结论。16K 宿主 `not-tested`。

## 文件

| 文件 | 说明 |
|---|---|
| `defconfig-fast` | ARM64 UP 最小内核配置（完整 `.config`，由 pinned `linux-bionic.config` + `defconfig-fast.fragment` 经 `olddefconfig` 解析） |
| `defconfig-fast.fragment` | 相对 pinned 配置的**显式取舍**；同时用于 ARM64 SMP 与 x86_64 参考配置 |
| `initramfs/init` | PID 1：挂 proc/sys/dev/devpts/cgroup2；按 `umroot=` 决定 switch_root 还是本地 exec；失败输出 `UM_INIT_ERROR` 并关机 |
| `initramfs/um-command.sh` | T3 探针：真实执行 busybox true，打印 `U1_READY uptime=…`，按 `um.hold_ms` 关机或 `um.shell=1` 进 shell |
| `build-fast.sh` | 构建 ARM64 UP/SMP + x86_64 UP/SMP 内核、16 KB stub、内置 initramfs；写 `out/manifest.sha256` |
| `build-rootfs.sh` | 生成 hostfs 目录与 ext4 镜像（Alpine minirootfs + init/um-command） |
| `bench-fast.py` | 矩阵式 T0–T3 采集（宿主 `CLOCK_MONOTONIC` + guest `/proc/uptime`），输出到 `out/` |
| `manifest.md` | pinned 输入、构建命令、产物 sha256、两次构建比对、16K 结论 |
| `out/` | 构建输出与日志（不入库） |

## 快速开始

### 0. 输入

```sh
# 内核源码：Raymer8639/linux-um-arm64 tag um-arm64-v0.3.0
# sha256(linux-um-arm64-v0.3.0.tar.gz) = 08578a49…e0116c
git clone --depth 1 --branch um-arm64-v0.3.0 \
  https://github.com/Raymer8639/linux-um-arm64 /path/to/src

# NDK r29（仅 linux-x86_64 host 工具链）
curl -O https://dl.google.com/android/repository/android-ndk-r29-linux.zip
unzip android-ndk-r29-linux.zip
```

### 1. 构建（容器内）

```sh
docker build -t u1-build:24.04 - <<'EOF'
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential bison flex bc python3 file cpio gzip xz-utils zstd \
    libelf-dev libssl-dev e2fsprogs kmod ca-certificates curl && rm -rf /var/lib/apt/lists/*
EOF

docker run --rm \
  -v "$PWD:/work" \
  -v /path/to/src:/src:ro \
  -v /path/to/android-ndk-r29:/ndk:ro \
  -e SRC=/src \
  -e NDK=/ndk/toolchains/llvm/prebuilt/linux-x86_64 \
  -e TARGETS=arm64-up,arm64-smp,x86_64-up,x86_64-smp \
  u1-build:24.04 \
  bash /work/research/uml-fastboot/build-fast.sh
```

产物：`out/<target>/artifacts/{linux-fast,linux-fast-noinitrd,stub_exe,initramfs.cpio.gz,kernel*.config}`。
`linux-fast` 内置 initramfs；`linux-fast-noinitrd` 用于“外置 rootfs 直启”对照。

### 2. 生成 rootfs 夹具

```sh
docker run --rm -v "$PWD:/work" -w /work/research/uml-fastboot \
  u1-build:24.04 bash -c './build-rootfs.sh x86_64 && ./build-rootfs.sh aarch64'
```

### 3. x86_64 宿主参考 bench

```sh
cd research/uml-fastboot
python3 bench-fast.py bench-matrix.example.json
```

矩阵默认每个 case `n=5`，轮转交错；结果写 `out/bench-matrix.example.result.{json,md}`
（`out/` 与 `*.result.*` 均被 gitignore）。
所有数字必须带夹具标识（`fixture`）与 `n`/中位数；禁止把宿主数据外推 ARM64。

### 4. ARM64 设备窗口（not-tested here）

本 profile 的 ARM64 产物是 `out/arm64-up/artifacts/linux-fast`（内置 initramfs）与
`stub_exe`（16 KB 对齐）。设备窗口沿用 `research/uml-runner` 的 app 域通道：

1. 把 `linux-fast` 重命名为 `libuml_kernel.so`、`stub_exe` 重命名为
   `libuml_stub.so`，核对 sha256 后放进 runner 的 `jniLibs/arm64-v8a/`；
2. `./gradlew assembleSdk36Debug`，安装；
3. 启动参数：
   ```
   mem=512M rw con=null con0=null,fd:1 seccomp=on panic=1 printk.time=1
   um.hold_ms=1000
   stub_exe=<nativeLibraryDir>/libuml_stub.so
   ```
   （内置 initramfs 不需要 `rootfs`/`init=`；要测外置 rootfs，再传
   `ubd0=<filesDir>/rootfs.ext4 umroot=/dev/ubda umrootfstype=ext4`
   （UmlFaceLauncher 的 FAST_ROOTFS_IMAGE_NAME 约定名为 `rootfs.ext4`，
   放入时按此命名），或 `umroot=<filesDir>/rootfs umrootfstype=hostfs`。）
4. 判定串：console 出现 `U1_T2_READY`、`U1_READY`、exit 0；失败时只认
   `UM_INIT_ERROR code=… step=…`。

> 设备侧仍未验证：ARM64 boot、16K 宿主、app 域 exec fast kernel、cgroup2 在
> Android 域的可用性。报告必须标 `not-tested`，不得用宿主 T3 代替。

## 结果摘要（x86_64 宿主参考，2026-11-10）

完整 T0–T3 表、三组对照、失败模式与限制见
`docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md` §3–§6；原始 JSON/Markdown
由 `bench-fast.py` 写入 `out/`（本地证据，gitignored），不复制到报告。

- 内置 initramfs 的 guest T3 中位 **80 ms**、宿主 T0→T3 中位 **151.8 ms**；
  hostfs/ext4 外置根与直启的 UP 中位在 112.6–138.6 ms（同一量级）。
- 这是 **x86_64 参考**，不是 ARM64 设备结论；宿主 5.4 无 `close_range`，
  运行在 **ptrace** 模式（不是设备上的 SECCOMP），SMP 被夹到 1 CPU（SMP 内核列中位 168–206 ms，不作多核结论）。
- ARM64 UP/SMP 内核 + 16 KB stub + initramfs 已构建；两次干净重建逐字节一致；
  `check-16kb-kernel.sh` 全过。设备 boot 与 16 KB 宿主 `not-tested`。

## 配置取舍（相对 pinned release config）

| 保留 | 理由 |
|---|---|
| `CONFIG_UML/MMU`、`HOSTFS`、`BLK_DEV_UBD`、`BLK_DEV_INITRD`、`RD_GZIP` | 内核本体、hostfs/ubd 两种 rootfs、内置 initramfs |
| `DEVTMPFS(+MOUNT)`、`DEVPTS`、`PROC_FS`、`SYSFS`、`TMPFS` | T2 契约 |
| `CGROUPS` + sched/cpuacct/freezer/device/blkio | cgroup v2；不承诺 Docker（无 MEMCG/PIDS） |
| `SECCOMP/SECCOMP_FILTER`、`NAMESPACES/NET_NS`、`BINFMT_ELF/SCRIPT` | `seccomp=on` 快路径、动态链接 busybox、脚本 |
| `EXT4_FS`、`UNIX98_PTYS`、`NULL_CHAN`、`MCONSOLE`、`KALLSYMS`、`IKCONFIG` | rootfs/控制台/可诊断性 |
| `NET/UNIX/INET/PACKET/UML_NET_VECTOR/TUN` | U3 的 vector 矩阵保留；不是 T3 承诺 |
| `STATIC_LINK`、`CC_OPTIMIZE_FOR_SIZE`、`PRINTK_TIME` | 可移植 binary、体积、T1 时间戳 |

| 裁掉 | 理由 |
|---|---|
| `DEBUG_KERNEL`、`DEBUG_INFO`、`FTRACE`、`FRAME_POINTER`、`COREDUMP`、`PROC_KCORE`、`DEVMEM` | release 产物本来就 strip；fast profile 只需 panic trace（KALLSYMS） |
| `INPUT/HID/SERIO/WLAN/WIRELESS/ETHERNET/NETCONSOLE` | App 域无真实硬件绑定；fast shell 不打开 |
| `NETFILTER/NET_SCHED/NET_9P/BPF_SYSCALL/PSI/AUDIT/SECURITY/USER_NS/PID_NS/MEMCG/CGROUP_BPF` | 首启不需要；full profile 另算 |
| `BLK_DEV_LOOP/NBD`、`ISO9660_FS`、`AUTOFS_FS`、`QUOTA`、`FUSE_FS`、`OVERLAY_FS`、`NLS` | 非 fast contract；Docker/镜像场景回 full profile |
| `PORT_CHAN/PTY_CHAN/TTY_CHAN/XTERM_CHAN/LEGACY_PTYS` | `con=null` + fd 通道足够 |
| `BINFMT_MISC`、`PPP`、`SLIP`、`DUMMY` | 首启不需要 |
| `RD_BZIP2/LZMA/XZ/LZO/LZ4/ZSTD` | 只保留 gzip 解压器 |
| `MODULES`、`RUST`、`LOCALVERSION_AUTO` | 单体内核 + 确定性版本串 |

> 版本号、签名、发布通道、`:app` 构建均未改动。内核源码 pin 与 GPL 对应源码
> 交付由 U5 负责；本目录只记录 pin。

## Init 契约

`initramfs/init` 是 PID 1，只做三件事：

1. 挂 `/proc`、`/sys`、`/dev`、`/dev/pts`、`/sys/fs/cgroup`（cgroup2）；
2. 若 cmdline 有 `umroot=` + `umrootfstype=`，挂外置 root、把伪文件系统挂进
   新根、`switch_root`；否则在 initramfs 内 `exec /um-command.sh`；
3. 任一步失败：打印一行
   `UM_INIT_ERROR code=<n> step=<step> detail=<...>`，然后 `poweroff -f`。

PID 1 不能以非零码退出（会变成 kernel panic），所以失败判据是**结构化错误行**，
不是 UML 进程退出码；`bench-fast.py` 与设备窗口都按该行判失败。

### 标记

| 标记 | 含义 |
|---|---|
| `[   0.123456] Run /init as init process` | T1：内核开始执行 init |
| `U1_T2_READY uptime=…` | T2：root + proc/sys/dev/devpts/cgroup2 就绪 |
| `U1_READY uptime=…` | T3：真实命令（busybox true）成功，first command 可达 |
| `UM_INIT_ERROR code=… step=…` | init 失败（结构化） |
| `U1_CMD_ERROR …` | T3 探针的真实命令失败 |

## 测试矩阵

| 对照 | case | 说明 |
|---|---|---|
| 内置 initramfs vs 外置 rootfs | `initramfs` vs `hostfs-initramfs`/`ubd-initramfs` | 同一带 initramfs 内核；后者由 init 挂外置根再 switch_root |
| hostfs vs `ubd0` ext4 | `hostfs` vs `ubd` | 同一无 initramfs 内核直启，隔离 rootfs 通道 |
| UP vs SMP | `*-up` vs `*-smp` / `ncpus=4` | 同 config 只切 `CONFIG_SMP`+`NR_CPUS`（ARM64）；x86_64 仅参考 |

## 边界

- 不承诺 Docker/systemd/网络在 fast profile 可用；`cgroup2` 挂载 ≠ Docker 可用。
- 不把 x86_64 宿主数据外推 ARM64；不把 `linux-fast` 当产品启动器。
- 16K 宿主 `not-tested`；16K guest 是 pinned 内核的既有能力，不是本 profile 新增。
- Alpine minirootfs 只是夹具；U2 的预烘焙 rootfs 才是产品输入。
