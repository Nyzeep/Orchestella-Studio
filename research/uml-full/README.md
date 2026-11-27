# research/uml-full —— ARM64 UML 完整内核载荷可复现构建（W01·02）

针对 Debian 13 (`systemd v257`) 与 Docker 26.1.5 / `containerd` / `runc` 工作负载构建的可复现 `full-smp-arm64` ARM64 UML 内核、配套 16 KB `stub_exe` 与支持 XZ 解压的装配 `initramfs` 流水线。

## 文件清单

| 文件 | 说明 |
| --- | --- |
| `defconfig-full.fragment` | 基于 `research/uml-kernel-build/defconfigs/linux-bionic-smp.config` 的可审查 Kconfig 增量与工作负载关联注释 |
| `defconfig-full` | `olddefconfig` 归档结果（`INITRAMFS_SOURCE=""` 路径无关基线，49,878 B） |
| `initramfs/init` | 支持 `umroot=`、`umrootfstype=` 与 `uminit=/sbin/init` 原生 init 交接的 PID 1 脚本 |
| `initramfs/um-command.sh` | 无外置根盘时的 initramfs 兜底脚本 |
| `build-full.sh` | 固定输入、相对路径 `INITRAMFS_SOURCE`、路径映射与 16 KB stub 同步重链构建脚本 |
| `verify-reproducible.sh` | 双干净异构路径（alpha vs beta）复现验证与 ELF 分段/成员定位脚本 |
| `manifest.md` | 锁定输入、Kconfig 增量审计、双干净构建摘要对照与 ELF/16 KB 检查实测 |

## 复现方式

```sh
# 1. 生成/校验最终 defconfig-full
OUT=/tmp/uml-full-regen bash research/uml-full/build-full.sh --regen-config

# 2. 运行双干净异构路径复现验证（支持离线传入 FAST_INITRAMFS_GZ / BUSYBOX_BIN / BUSYBOX_APK）
SRC=/root/linux-um-arm64-um-arm64-v0.3.0 \
NDK=/root/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 \
FAST_INITRAMFS_GZ=/tmp/fast-initramfs.cpio.gz \
JOBS=$(nproc) \
  bash research/uml-full/verify-reproducible.sh
```
