# U1 fast-boot profile manifest

- 日期：2026-11-10
- 会话：u1-fastboot（工单 `.scratch/uml-zero-device-20261110/issues/02-fastboot-profile.md`）
- 基线：`62d96e99ad0334a5cc666f8387ac949fc0f2c025`（`research/uml-zero-device-direction-20261110`）
- 分支：`feat/uml-u1-fastboot`；worktree（本地路径不记录）
- 性质：零设备研究原型；不写产品代码、不改主构建、不改版本号/签名/发布通道

## 1. 输入 pin

| 输入 | 值 | sha256 |
|---|---|---|
| 内核源码 tag | `Raymer8639/linux-um-arm64` `um-arm64-v0.3.0` | tarball `08578a494412125dfc6b8f203e984b68d20e313173319d6a6c115e2875e0116c` |
| 内核 commit | `169b17ff3f66d507cb137b770ceb1fd9f4547a32` | — |
| 内核版本 | Linux `7.2.0-rc4` | — |
| NDK | r29 `android-ndk-r29-linux.zip` | `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| NDK clang | `Android (13989888, +pgo, +bolt, +lto, +mlgo, based on r563880c) clang version 21.0.0` | — |
| 基线 ARM64 UP config | `research/uml-kernel-build/defconfigs/linux-bionic.config` | 见仓库 |
| ARM64 SMP 派生 | `defconfig-fast` + `CONFIG_SMP=y` + `NR_CPUS=8`（`build-fast.sh` 生成，不与 pinned SMP+Docker config 混用） | 见 `defconfig-fast` / `build-fast.sh` |
| 静态 busybox APK (aarch64) | Alpine 3.22 `busybox-static-1.37.0-r20` | `ee469aee2958feffd7f64dd96655704025ff60af614f77a1d7323dc237c34da2` |
| 静态 busybox binary (aarch64) | `bin/busybox.static` | `a6bb1e6e349cbdfa690a78cd0017413c5446bdae02ee003ceb682d4ae1a004ff` |
| 静态 busybox APK (x86_64) | Alpine 3.22 `busybox-static-1.37.0-r20` | `488ad6efd04b5a722719e79f8e0dcc2c24afd6758867af3ce41b04839e60c74b` |
| 静态 busybox binary (x86_64) | `bin/busybox.static` | `a477b4c1b332bb9b5d926dbfc75e0efc9b53193708d8be83295bfbb376fa9839` |
| Alpine minirootfs (aarch64) | 3.22.2 | `6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489` |
| Alpine minirootfs (x86_64) | 3.22.2 | `412454ed98025ad9cd910d13f3d448b184e81502baa83180e89f98d0f13674be` |

## 2. 构建环境与命令

- 构建容器：Ubuntu 24.04（`u1-build:24.04`），GCC 13.3.0 / GNU Make 4.3 / GNU ld 2.42；`KBUILD_BUILD_TIMESTAMP="Thu Jan 1 00:00:00 UTC 1970"`，`SOURCE_DATE_EPOCH=0`。
- ARM64：NDK r29 cross（`CC=clang --target=aarch64-linux-android30`，`LLVM_IAS=1`）。
- x86_64 宿主参考：同容器 GCC 13 + **host-only CC wrapper**。pinned 下游树把 `include/linux/compiler_attributes.h` 强制 include 到 UML USER_OBJS（为 NDK/bionic sysroot 设计）；glibc 的 `__attribute_alloc_size__` 会与其 `__alloc_size__` 冲突。wrapper 仅丢弃这一条 force-include，内核对象仍由 `compiler_types.h` 获得属性；bionic 没有该宏，因此 ARM64 产品构建不受影响。这是参考构建的宿主兼容 workaround，不是内核源码补丁。

```sh
# 容器内（u1src/u1ndk2/u1work 为本地 docker volume）
cd /src
SRC=/src NDK=/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 \
  TARGETS=arm64-up,arm64-smp,x86_64-up,x86_64-smp \
  bash /work/research/uml-fastboot/build-fast.sh
```

## 3. 产物（ARM64 UP，2026-11-10 首建）

| 产物 | 大小 (B) | sha256 |
|---|---|---|
| `linux-fast`（内置 initramfs + 16 KB stub） | 9,517,760 | `21e1c3f97269a831fa8bab5fd3ff0e8a0e9edc59554d17cce34913924f584389` |
| `linux-fast-noinitrd`（外置 rootfs 对照） | 8,862,400 | `a5efce2b9197071a2db89735e247fa3cf548644b7d036ea99bf688b14eff17fb` |
| `stub_exe`（16 KB 重链） | 2,056 | `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` |
| `stub_exe-orig`（常规链接，对照） | 2,000 | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e`（与 release stub 逐字节一致） |
| `initramfs.cpio.gz`（内置 cpio 的副本） | 661,899 | `7523c9a20b956a3104d04540559422a7b566ef630a592c9d4ca8fdb2e5bd8381` |
| `kernel.config`（含 INITRAMFS_SOURCE 路径） | 30,700 | `8b854fe2d9dd9f19328a9ac6ce755b283a4523d74a8b55dfaa82e46f07fe6051` |
| `kernel-noinitrd.config` | 30,506 | `863de65f04ab18d141f98867b3cf20591e88acf57f3a3cc4d2e8ea416d0bd26e` |

- `stub_exe` 与既有 `research/uml-kernel-build/release-assets/v0.3.0/stub_exe-16k` **逐字节一致**（`87666f91…`），证明 16 KB 重链配方在新配置下可复现。
- `kernel-noinitrd.config` 的 sha256 与 `defconfig-fast` 相同，说明“无 initramfs 变体”确实只是同一配置去掉 `INITRAMFS_SOURCE`。

### 16 KB 检查

```sh
READELF=<ndk>/bin/llvm-readelf bash research/uml-kernel-build/check-16kb-kernel.sh \
  out/arm64-up/artifacts/linux-fast out/arm64-up/artifacts/stub_exe \
  out/arm64-smp/artifacts/linux-fast out/arm64-smp/artifacts/stub_exe
# ELF_16KB_OK all_targets_pass
```

stub 实测单 `LOAD` 段 `p_align=0x4000`；内核 `linux-fast` 全 `PT_LOAD` ≥ 16 KB。**16 KB 宿主仍 not-tested**（当前宿主与设备均为 4 KB 页）；16 KB **guest** 是 pinned 内核既有能力，不是本 profile 新增。

### 3.2 rootfs 夹具（hostfs 目录 + ext4 镜像）

`build-rootfs.sh` 从 Alpine 3.22.2 minirootfs + `/init` + `/um-command.sh` 生成；
x86_64 用于宿主参考，aarch64 供下个设备窗口（未 boot）。

| 夹具 | 大小 (B) | sha256 |
|---|---|---|
| `out/rootfs-x86_64/`（hostfs 目录） | — | tree `8b36e35e521f5f16558e0db16b34d56c5809863f3505b1edb884550cd9fa0456` |
| `out/rootfs-x86_64.ext4` | 67,108,864 | `30bc505a5a832eb357a624e0bb6380b508df0c5b983b5b79ecce50fdcec0b49f` |
| `out/rootfs-aarch64/`（hostfs 目录） | — | tree `c7e395e6583e6ce39c6ae26ee7782c0f40e9f245a3465db709bb1fc44cc360c6` |
| `out/rootfs-aarch64.ext4` | 67,108,864 | `51b4292d54d73cf07b84505da061fe57541fd24ad21ca6451974cae1572095d7` |

```sh
OUT=$PWD/out bash build-rootfs.sh x86_64
OUT=$PWD/out bash build-rootfs.sh aarch64
```

> rootfs 是 U1 的夹具，不是 U2 的预烘焙交付；ext4 镜像/目录在 `out/`（gitignored）。

## 4. 配置关键取舍

见 `defconfig-fast.fragment` 与 README 的取舍表。摘要：

- 保留：UML/MMU、hostfs、ubd、initramfs(gzip)、devtmpfs(+mount)、devpts、proc/sysfs/tmpfs、cgroup v2（sched/cpuacct/freezer/device/blkio）、seccomp(+filter)、namespaces/netns、binfmt elf/script、ext4、null channel、mconsole、kallsyms、IKCONFIG、static link、printk time、net/unix/inet/packet/vector/tun。
- 裁掉：DEBUG_KERNEL/DEBUG_INFO/FTRACE/FRAME_POINTER/COREDUMP/PROC_KCORE/DEVMEM、HID/WLAN/WIRELESS/ETHERNET/NETCONSOLE、NETFILTER/NET_SCHED/NET_9P/BPF_SYSCALL/PSI/AUDIT/SECURITY、USER_NS/PID_NS/MEMCG/CGROUP_BPF、loop/NBD/ISO9660/autofs/quota/FUSE/overlay/NLS、port/pty/tty/xterm channels、BINFMT_MISC/PPP/SLIP/DUMMY、除 gzip 外的 initrd 解压器、MODULES/RUST/LOCALVERSION_AUTO。
- 保留 `CONFIG_INPUT=y` 是 `drivers/tty/Kconfig` 对 UML 必需 TTY 的 `select INPUT` 结果；HID 与输入驱动均已关闭。

## 5. 两次构建与差异（ARM64 UP，同一容器/同一 pin/干净 OUT）

| 产物 | 第一次 | 第二次（删除 `out/arm64-up` 后重建） | 结论 |
|---|---|---|---|
| `linux-fast` | `21e1c3f9…4389` | `21e1c3f9…4389` | 逐字节一致 |
| `linux-fast-noinitrd` | `a5efce2b…17fb` | `a5efce2b…17fb` | 逐字节一致 |
| `stub_exe` | `87666f91…a09a12` | `87666f91…a09a12` | 逐字节一致 |
| `initramfs.cpio.gz` | `7523c9a2…8381` | `7523c9a2…8381` | 逐字节一致 |
| `kernel.config` | `8b854fe2…6051` | `8b854fe2…6051` | 逐字节一致 |
| `kernel-noinitrd.config` | `863de65f…bd26e` | `863de65f…bd26e` | 逐字节一致 |

复现条件：同一源码 tar（`08578a49…e0116c`）、同一 NDK r29、同一容器路径 `/src` 与 `/work/research/uml-fastboot/out`、`KBUILD_BUILD_TIMESTAMP` 固定、initramfs cpio mtime 固定为 0。**跨不同构建路径**时 `CONFIG_INITRAMFS_SOURCE` 的绝对路径会进入 `kernel.config`，内核本体与 initramfs 仍预期一致；未做跨路径验证。

### ARM64 SMP（同 config + `CONFIG_SMP=y` + `NR_CPUS=8`）

| 产物 | 大小 (B) | sha256 |
|---|---|---|
| `linux-fast` | 10,355,504 | `45b3ccddb29827e0482b963e44093bdf993e6792e4f8124b3b78d7eee58969a7` |
| `linux-fast-noinitrd` | 9,700,144 | `99f6c3e7bcba214687950f04c29bef5d1aba28c5832187b859eca500215f260e` |
| `stub_exe` | 2,056 | `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` |
| `initramfs.cpio.gz` | 661,899 | `7523c9a20b956a3104d04540559422a7b566ef630a592c9d4ca8fdb2e5bd8381` |
| `kernel.config` | 31,030 | `8e9e2856641e950d1d3cce58cc07820718b27a36ed69baf9d24ea9285940394f` |
| `kernel-noinitrd.config` | 30,835 | `2b0d587de9ea87d52c35d5e7dd0f054b0124c08412e423624745c26319dc5922` |

SMP 内核比 UP 大 837,744 B（+8.8%）；stub 与 initramfs 相同。ARM64 SMP **只完成构建**，设备 boot `not-tested`。

## 6. not-tested / 边界

- ARM64 设备 boot：`not-tested`（零设备轮；命令入口见 README §4）。
- 16 KB 宿主：`not-tested`。
- x86_64 宿主参考：见 `bench-matrix.example.result.md`；宿主为 Linux 5.4，`close_range` 不存在 → SECCOMP 探测失败，参考运行在 **ptrace** 模式（`seccomp=auto`），不是 ARM64 设备上的 SECCOMP 快路径；不得外推。
- fast profile 不承诺 systemd/Docker/网络；`cgroup2` 挂载 ≠ Docker 可用。
