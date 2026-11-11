# uml-rootfs-prebake — 首启零安装的 UML rootfs 预烘焙原型

研究原型：把包安装、locale、证书与基础工具链全部前移到构建期，首启只做挂载 + init + exec。
**不是产品可分发物**：产物只在 `research/` 内验证，许可证/NOTICE 与 GPL 对应源码义务另行审查。

## 契约

- 构建期：安装包、生成 locale、生成证书 bundle、放置基础工具链、清理机器相关状态。
- 首启：`/init-zero-install` 挂载 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2，执行 `/ready.sh` 输出 `READY`。
- 首启不调用 `apk`/`apt`/`dpkg`/`pacman`，不联网，不生成 SSH host key。
- 同一输入集合的两次构建应产生相同 sha256；非确定项必须记录。

## 依赖

构建机：Linux（x86_64 或 aarch64）、root、`curl`、`tar`、`gzip`、`python3`、`e2fsprogs`（仅 `--ext4`）。

| 发行版 | 额外依赖 |
| --- | --- |
| Alpine | 跨架构 chroot 需要 `qemu-user-static` + binfmt_misc（Alpine 的包解包本身不需要执行目标代码） |
| Debian | `mmdebstrap`；跨架构还需要 `qemu-user-static` + binfmt_misc |

构建容器若没有 `CAP_SYS_ADMIN`，Debian 使用 `--skip=mount`；Alpine 的 `update-ca-certificates` 需要能
`chroot` 执行目标架构（binfmt `F` 标志）。两者都会在日志里明确说明，不会静默降级。

## 快速开始

```bash
# Alpine musl，aarch64
./build-rootfs.sh --distro alpine --arch aarch64 --out ./out/alpine-arm64

# Debian glibc，aarch64（默认使用 snapshot.debian.org 的固定时间戳）
./build-rootfs.sh --distro debian --arch aarch64 --out ./out/debian-arm64

# 同时产出 ext4（文件系统 UUID/hash seed 固定）
./build-rootfs.sh --distro alpine --arch x86_64 --out ./out/alpine-x64 --ext4

# 自定义包集合
./build-rootfs.sh --distro alpine --arch aarch64 --out ./out/min \
  --packages "bash coreutils curl ca-certificates openssh"
```

产物目录：`<distro>-<version>-<arch>.tar.gz`、`.sha256`、可选 `.ext4`、`manifest.json`。

## 复现性

固定输入：发行版 base tarball sha256、仓库索引 sha256（Alpine）或 Debian snapshot 时间戳 + `Release` sha256、
`SOURCE_DATE_EPOCH=1762732800`（2026-11-10T00:00:00Z）。

规范化步骤：清空 `machine-id`、`resolv.conf`、包缓存、包管理器日志、`/dev` 节点、SSH host key；
写入固定 `hostname`/`hosts`；所有文件 mtime clamp 到 `SOURCE_DATE_EPOCH`；tar 使用
`--sort=name --pax-option=delete=atime,delete=ctime --mtime=@... --owner=0 --group=0 --numeric-owner`，
gzip 使用 `-9n`。

```bash
# 两次构建对比
./build-rootfs.sh --distro alpine --arch aarch64 --out /tmp/a1
./build-rootfs.sh --distro alpine --arch aarch64 --out /tmp/a2
sha256sum /tmp/a1/*.tar.gz /tmp/a2/*.tar.gz
```

Alpine 没有官方 snapshot 服务，`dl-cdn` 仓库会滚动；manifest 记录本次使用的 `APKINDEX` sha256 与每个包的
`P/V/C` 字段，但不承诺“跨时间可复现”。Debian 使用 `snapshot-cloudflare.debian.org` 的固定时间戳。

## 校验

```bash
mkdir -p /tmp/rootfs && tar -xzf out/alpine-arm64/alpine-3.22.8-aarch64.tar.gz -C /tmp/rootfs
./verify.sh --root /tmp/rootfs --manifest out/alpine-arm64/manifest.json
```

`verify.sh` 检查 manifest 字段、产物 sha/大小、包数据库与 manifest 一致、整树 tree digest、无包管理器队列/锁/缓存、
无产品 provisioning 标记、init/ready 可执行且不含包管理器/网络调用、证书 bundle 与 manifest 一致。

## 参考首启（x86_64 宿主 UML）

aarch64 产物不能在 x86_64 宿主直接 boot；研究用 x86_64 同构产物做 init/READY 参考，不当作设备结论。

```bash
# 需要已构建的 ARCH=um x86_64 内核
TMPDIR=/tmp ./linux mem=512M rootfstype=hostfs rootflags=/tmp/rootfs rw \
  init=/init-zero-install con0=fd:0,fd:1 con=null orchestella.ready=poweroff
```

`orchestella.ready=poweroff` 让 `/ready.sh` 打印 `READY` 后关机，便于脚本采集；默认是 `shell`。
ext4 通道用 `ubd0=<image> root=/dev/ubda rootfstype=ext4`（UML 把第一块 ubd 命名为 `/dev/ubda`）。

## 已知边界

- 只做 Alpine + Debian 两个发行版；其余发行版 `not-tested`。
- ARM64 真机首启未验证；16K 宿主未验证。
- 首启零安装 ≠ 系统更新：镜像内仍保留 `apk`/`apt`，更新策略另立票。
- 预烘焙 rootfs 不是产品可分发物；GPL/第三方许可证与 NOTICE 需专门审查。
