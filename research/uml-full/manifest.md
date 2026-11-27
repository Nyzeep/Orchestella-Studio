# full-smp-arm64 内核载荷可复现构建清单（W01·02）

- 构建日期：2026-11-27
- 构建环境：WSL2 Ubuntu 24.04.5 LTS（x86_64，GCC 13.3.0 / GNU Make 4.3 / GNU ld 2.42）+ Stock Android NDK r29 (`29.0.14206865`，`android-ndk-r29-linux.zip` SHA-256 `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf`，`Android (13989888, +pgo, +bolt, +lto, +mlgo, based on r563880c) clang version 21.0.0`，目标 `aarch64-linux-android30`)。

## 1. 锁定输入

| 输入项 | 值 / 标识 | SHA-256 |
| --- | --- | --- |
| 内核源码 | `Raymer8639/linux-um-arm64` `um-arm64-v0.3.0` (`169b17ff3f66d507cb137b770ceb1fd9f4547a32`, Linux `7.2.0-rc4`) | tarball `08578a494412125dfc6b8f203e984b68d20e313173319d6a6c115e2875e0116c` |
| 基础配置 | `research/uml-kernel-build/defconfigs/linux-bionic-smp.config` (`CONFIG_SMP=y`, `NR_CPUS=16`, `PAGE_SHIFT=14`) | 见仓库 |
| 增量片段 | `research/uml-full/defconfig-full.fragment` | 见仓库 |
| 归档终态配置 | `research/uml-full/defconfig-full` | `459e20d38e33b4a87cfa639f910dfaa2753ffc22af013a9e1661c3acc9b5af5f` |
| 静态 BusyBox | Alpine 3.22 `busybox-static-1.37.0-r20` (aarch64) | `a6bb1e6e349cbdfa690a78cd0017413c5446bdae02ee003ceb682d4ae1a004ff` |
| 确定性环境变量 | `SOURCE_DATE_EPOCH=0`, `TZ=UTC`, `LC_ALL=C`, `ZERO_AR_DATE=1`, `KBUILD_BUILD_TIMESTAMP="Thu Jan 1 00:00:00 UTC 1970"`, `KBUILD_BUILD_USER=orchestella`, `KBUILD_BUILD_HOST=uml-full-build`, `-ffile-prefix-map`, 相对 `INITRAMFS_SOURCE="orchestella-initramfs-full.cpio.gz"` | — |

## 2. 相对 `linux-bionic-smp.config` 的核心 Kconfig 增量与工作负载关联

- **Debian 13 / systemd v257**：保留 `CGROUPS`、`MEMCG`、`CGROUP_PIDS`、`CGROUP_BPF`、`USER_NS`、`PID_NS`、`NET_NS`、`IPC_NS`、`UTS_NS`、`AUTOFS_FS`、`EXT4_FS_POSIX_ACL`、`EXT4_FS_SECURITY`、`UNIX98_PTYS`、`DEVTMPFS`；新增 `CONFIG_PSI=y`（`systemd-oomd`/内存压力）、`CONFIG_FANOTIFY=y`、`CONFIG_PRINTK_TIME=y`。
- **Docker 26.1.5 / containerd 1.7.24 / runc 1.1.15**：保留 `OVERLAY_FS`、`SECCOMP`、`SECCOMP_FILTER`、`BPF_SYSCALL`、`KEYS`、`VETH`、`BRIDGE`、`BRIDGE_NETFILTER`、`NF_TABLES`、`NF_NAT`、`NFT_NAT`、`NFT_MASQ`、`NFT_COMPAT`、`NETFILTER_XT_MATCH_ADDRTYPE`、`IP_NF_IPTABLES`、`IP_NF_TARGET_MASQUERADE`；新增 `CONFIG_CPUSETS=y`、`CONFIG_NETFILTER_XT_MATCH_COMMENT=y`、`CONFIG_NETFILTER_XT_MATCH_STATE=y`、`CONFIG_NETFILTER_XT_TARGET_MARK=y`、`CONFIG_FUSE_FS=y`。
- **Arch Linux ARM 预留（Wave 2）**：新增 `CONFIG_SECURITY=y` + `CONFIG_SECURITY_LANDLOCK=y`（`LSM="landlock,lockdown,yama,loadpin,safesetid,ipe,bpf"`）。
- **可复现性与单体裁剪**：`# CONFIG_LOCALVERSION_AUTO is not set`、`CONFIG_DEBUG_INFO_NONE=y`（`# CONFIG_DEBUG_INFO is not set`）、`# CONFIG_MODULES is not set`。
- **SMP 口径**：内核按 `CONFIG_SMP=y`、`NR_CPUS=16` 编译，Studio QA 首证启动默认 `1 vCPU`（`ncpus=1`）。

## 3. 双干净异构路径构建校验（Build A vs Build B）

- Build A：`/root/uml-full-repro/alpha-workspace`
- Build B：`/root/uml-full-repro/beta-longer-workspace-dir`

| 工件 | 大小 (B) | Build A SHA-256 | Build B SHA-256 | 结论 |
| --- | ---: | --- | --- | --- |
| `linux-full` (`libuml_kernel_full.so`) | 14,393,208 | `d1ef40e68cdf73cadfb235e746b878d011209f677d8751b888768fa387f3b47f` | `d1ef40e68cdf73cadfb235e746b878d011209f677d8751b888768fa387f3b47f` | 逐字节一致 |
| `linux-full-noinitrd` | 13,737,848 | `ebf88b1f1b48fc15f4a24cfc6d9eb9fbb62e4fa9667a1ee3a193fbf4084d4b09` | `ebf88b1f1b48fc15f4a24cfc6d9eb9fbb62e4fa9667a1ee3a193fbf4084d4b09` | 逐字节一致 |
| `stub_exe` (`libuml_stub_full.so`) | 2,056 | `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` | `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` | 逐字节一致 |
| `initramfs-full.cpio.gz` | 662,027 | `ee1b03084b60eb846a3d1a2fd854904af9a4cd0fb3446ebbc5140439f4da7496` | `ee1b03084b60eb846a3d1a2fd854904af9a4cd0fb3446ebbc5140439f4da7496` | 逐字节一致 |
| `kernel-full.config` | 50,330 | `d2a6223dff0c8b9ec16279b44b8984122d702af06a9cd6f0c1dffdd336793896` | `d2a6223dff0c8b9ec16279b44b8984122d702af06a9cd6f0c1dffdd336793896` | 逐字节一致 |
| `kernel-full-noinitrd.config` | 49,878 | `459e20d38e33b4a87cfa639f910dfaa2753ffc22af013a9e1661c3acc9b5af5f` | `459e20d38e33b4a87cfa639f910dfaa2753ffc22af013a9e1661c3acc9b5af5f` | 逐字节一致 |
