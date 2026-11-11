# UML rootfs 预烘焙与首启零安装原型（U2）

- 日期：2026-11-10
- 工作分支：`feat/uml-u2-rootfs-prebake`（base `62d96e99`，`research/uml-zero-device-direction-20261110`）
- 工单：`.scratch/uml-zero-device-20261110/issues/03-rootfs-prebake.md`
- 范围：Alpine（musl）与 Debian（glibc）两个发行版的 ARM64 rootfs 预烘焙；x86_64 同构 UML 参考首启
- 纪律：零设备；不写产品代码、不改 `guest-assets/`；真机项标 `not-tested`；跨夹具数字不相除

## 0. 结论摘要

1. **首启零安装成立。** Alpine 与 Debian 的包安装、locale、证书 bundle、基础工具链都在构建期完成；首启
   `init-zero-install` 只挂载 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2 并 `exec /ready.sh`。
2. **两次构建 sha256 一致（两个发行版、两种格式）。**
   - Alpine 3.22.8 aarch64：`149b2041…bab59`（两次独立构建，58–87 s）。
   - Debian trixie aarch64：`a27c4353…792e`（两次独立构建，346–439 s，含下载；构建时间随宿主负载变化）。
   - Alpine 3.22.8 x86_64：tar.gz `90ac674f…72c4`、ext4 `2d569b36…845f`，两次均一致。
3. **断网首启到 READY。** x86_64 UML 参考（hostfs 与 ext4 两种 rootfs 通道）均输出
   `T2_MOUNTS_READY` / `T3_READY` / `READY`；控制台日志无 `apk`/`apt`/`dpkg`/`pacman`，无 `eth0`/DHCP
   配置。x86_64 参考 T3 量级 **0.1–0.3 s**：三次独立 n=5 采集中，Alpine 宿主中位 132–201 ms / guest
   0.10–0.14 s，Debian 宿主中位 133–281 ms / guest 0.10–0.19 s；宿主侧受负载影响明显，guest
   `/proc/uptime` 更稳定。
4. **体积与构建时间可入账。** Alpine aarch64：111 包，安装后 306 MiB（apk 统计；`du` 317 MiB），tar.gz
   **110,170,511 B**；Debian aarch64：213 包，安装后 656 MiB，tar.gz **228,692,824 B**。当前产品 Arch 首启的 92 包事务在 T7 终态
   中于 1800 s 内通过，预烘焙把这整段从首启移除。
5. **不是产品可分发物。** 预烘焙 rootfs 含大量 GPL-2.0-only / GPL-3.0-or-later 包；GPL 对应源码、NOTICE
   与 Android APK 分发边界需 U5/法务审查后才能进入产品打包。

## 1. 方法与时序口径

| 标记 | 定义 | 采集 |
| --- | --- | --- |
| T0 spawn | 宿主启动 UML 进程 | 宿主 monotonic clock（shell `date +%s%N`） |
| T1 init | guest 内核开始执行 `/init-zero-install` | 控制台 `Run /init-zero-install as init process` |
| T2 rootfs mounted | `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2 就绪 | guest `T2_MOUNTS_READY uptime=` |
| T3 first command | `/ready.sh` 输出 READY | guest `T3_READY uptime=` + 宿主时间戳 |

- guest 时间统一取 `/proc/uptime`（10 ms 粒度；Alpine busybox `date` 无 `%N`）；
- 宿主时间统一用 `date +%s%N`，在 UML 控制台每行输出时打时间戳；
- 每个参考各 n=5，取中位数；
- **x86_64 参考不是 ARM64 设备结论**；ARM64 产物只做了构建、sha 与 `verify.sh`，未在设备上 boot。

## 2. 构建路径

### 2.1 Alpine（musl）

`build-rootfs.sh --distro alpine --arch aarch64`：

1. 下载并校验官方 `alpine-minirootfs-3.22.8-aarch64.tar.gz`
   （sha256 `36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4`）。
2. 下载同分支的 `apk-tools-static 2.14.13-r0`（sha256 `d78d2a9e…527`），在宿主执行：
   `apk.static --root <root> --arch aarch64 --no-scripts --no-cache add <包集合>`。
   `--no-scripts` 是 apk-tools 文档明确用于“为不同架构提取系统镜像”的路径；证书触发器等由构建脚本
   在 binfmt 可用时显式执行。
3. 构建期执行 `update-ca-certificates`（需要能 `chroot` 执行 aarch64；binfmt `F` 标志）。
4. 清空 `/var/cache/apk`、`/var/log/apk.log`、`/lib/apk/db/lock`；清空 `/dev`、`machine-id`、
   `resolv.conf`、SSH host key；写固定 hostname/hosts；所有 mtime clamp 到 `SOURCE_DATE_EPOCH`。
5. 确定性 tar.gz：`--sort=name --format=posix --pax-option=delete=atime,delete=ctime
   --mtime=@1762732800 --owner=0 --group=0 --numeric-owner` + `gzip -9n`。

默认包集合（解析后 111 包）：`bash coreutils findutils grep sed gawk tar gzip xz zstd curl wget
ca-certificates openssl openssh git tzdata musl-locales procps iproute2 less e2fsprogs build-base python3`。
关键版本：musl 1.2.5-r13、gcc 14.2.0-r6、OpenSSH 10.0_p1-r11、Python 3.12.16-r0、ca-certificates 20261020-r0。

### 2.2 Debian（glibc）

`build-rootfs.sh --distro debian --arch aarch64`：

1. `mmdebstrap --arch=arm64 --variant=minbase --mode=root --skip=mount --components=main
   --include=<包集合> --format=dir trixie <root> <snapshot>`
2. 镜像固定为 `https://snapshot-cloudflare.debian.org/archive/debian/20261109T000000Z/`；构建记录
   `Release` sha256（`ed56aac4…138a`）。
3. 跨架构执行依赖 `qemu-user-static` + binfmt。qemu-user 不实现 `posix_spawn`，而 Python 3.13 的
   `subprocess` 默认使用它，导致 `python3-minimal` 的 `py3compile` postinst 失败（rc 127）。构建脚本导出
   `_PYTHON_SUBPROCESS_USE_POSIX_SPAWN=0`，只影响构建期 postinst，不进入镜像。
4. 构建期 `locale-gen` 生成 `C.UTF-8` 与 `en_US.UTF-8`；删除 apt lists/cache、dpkg/apt 日志、
   `ldconfig/aux-cache`、SSH host key、machine-id、resolv.conf；固定 hostname/hosts；mtime clamp。
5. 同样的确定性 tar.gz 配方。

默认包集合（解析后 213 包）：`locales ca-certificates bash coreutils findutils grep sed gawk tar gzip
xz-utils zstd less procps iproute2 tzdata curl wget git openssh-server openssh-client build-essential
python3 openssl e2fsprogs iputils-ping`。关键版本：glibc 2.41-12+deb13u4、gcc 14.2.0-19、OpenSSH
1:10.0p1-7+deb13u4、Python 3.13.5-1、ca-certificates 20250419。

> `--skip=mount` 是因为构建容器没有 `CAP_SYS_ADMIN`；需要 `/proc` 的 maintainer script 会显式失败而不是
> 静默降级。本次 minbase + 工具链集合没有遇到此类失败。

## 3. 复现性结果

固定 `SOURCE_DATE_EPOCH=1762732800`（2026-11-10T00:00:00Z）。两次构建使用独立输出目录与独立缓存。

| 产物 | 构建 1 | 构建 2 | 结论 |
| --- | --- | --- | --- |
| Alpine 3.22.8 aarch64 tar.gz | `149b2041a93a22a0f94aa105cc1f2480c1ff92b7b06926e909ad6e58201bab59` | 同左 | 逐字节一致 |
| Debian trixie aarch64 tar.gz | `a27c43535f635a0e2896ad288a02b9585ebde595d89b8f1fa06a21677821792e` | 同左 | 逐字节一致 |
| Alpine 3.22.8 x86_64 tar.gz | `90ac674f6148ed4b99f7e832c426826e7129ae286312e73bec54fd8baeb372c4` | 同左 | 逐字节一致 |
| Alpine 3.22.8 x86_64 ext4 | `2d569b36cbe34f1a46628826e791fcf2602de52654a38718dfca4214cecc845f` | 同左 | 逐字节一致 |

非确定项与边界：

- **Alpine 仓库是滚动的。** 没有官方 snapshot 服务；manifest 记录本次 `main`/`community` 的
  `APKINDEX.tar.gz` sha256 和每个包的 `P/V/C`。同一索引下两次构建一致；索引变化后不承诺。
- **Debian 用固定 snapshot 时间戳。** `Release` sha256 与包版本固定；同一 snapshot 下两次构建一致。
- **ext4** 使用固定 UUID/hash seed、关闭 lazy init/journal，并把 `SOURCE_DATE_EPOCH` 传给 `mke2fs`；
  x86_64 两次一致。**ARM64 ext4 未构建/未 boot**，标 `not-tested`。
- manifest 的 `build.startedAtUtc`/`durationSeconds` 不参与产物身份；产物身份是 `outputs[].sha256`。

调试期曾出现一次 sha 差异，定位为两次构建之间修改了 `init-zero-install`；同一脚本下 diff 仅该文件不同。
最终两次全量构建（上表）已排除该因素。

## 4. 断网首启证据

### 4.1 参考环境

- 宿主：Debian 13 x86_64，8 vCPU，容器内；内核 `Linux 5.4.241-1-tlinux4-0025.17 x86_64`。
- UML：自建 `ARCH=um` Linux 6.12.116（`make ARCH=um defconfig`），`mem=512M`（Alpine）/`768M`（Debian）。
- rootfs：x86_64 同构 Alpine / Debian 产物；hostfs 与 ext4 两种通道。
- 命令行没有 `eth0=`/`tcp=` 等网络设备参数；init/ready 不含网络客户端或 DHCP 调用。

最终产物 hostfs 参考（各 n=5，同一轮条件）：

```text
Alpine: T1 146 ms / T2 192 ms / T3 201 ms；guest T3 0.14 s
Debian: T1 107 ms / T2 127 ms / T3 133 ms；guest T3 0.10 s
```

三次独立 n=5 采样的范围（同一 x86_64 宿主，不同负载）：

| 发行版 | 宿主 T3 中位 | guest T3 中位 | 说明 |
| --- | --- | --- | --- |
| Alpine | 132 / 144 / 201 ms | 0.10 / 0.10 / 0.14 s | 三次复跑；最终产物为 201 ms |
| Debian | 281 / 175 / 133 ms | 0.19 / 0.14 / 0.10 s | 三次复跑；最终产物为 133 ms |

宿主侧中位受页面缓存与并发负载影响可达约 2×；guest `/proc/uptime` 的波动小于宿主墙钟。
**不能据此比较 Alpine 与 Debian 谁更快**；两者只用于证明首启零安装可到 READY。

Alpine ext4 参考（n=1，仅证明通道可用）：

```text
EXT4-fs (ubda): mounted ... r/w ... ordered data mode
T2_MOUNTS_READY uptime=0.09
T3_READY uptime=0.09
READY
```

控制台摘录（Alpine hostfs 一次）：

```text
Run /init-zero-install as init process
INIT_ZERO_INSTALL stage=mounts
INIT_MOUNT_PRESENT fs=dev target=/dev
T2_MOUNTS_READY uptime=0.09
T3_READY uptime=0.09 action=poweroff
READY
```

> `/dev` 已由内核 `CONFIG_DEVTMPFS_MOUNT` 挂载；init 检测到已挂载后不再覆盖。cgroup2 在参考内核上挂载成功。

### 4.2 “无包管理器调用”与“断网”

- `grep -Ei '\b(apk|apt|apt-get|dpkg|pacman|yum|dnf)\b'` 对全部 boot 日志无输出；
- `grep -Ei 'eth0|dhcp|wlan|ipv4.*addr'` 对全部 boot 日志无输出；
- `init-zero-install`/`ready.sh` 的非注释行经 `verify.sh` 检查，不含包管理器或网络客户端命令；
- rootfs 内仍保留 `apk`/`apt` 二进制（供后续更新策略使用），但首启不调用它们。

### 4.3 `verify.sh` 结果

| 产物 | 检查数 | 结果 |
| --- | --- | --- |
| Alpine 3.22.8 aarch64 | 20 | `VERIFY_OK`，0 failure |
| Debian trixie aarch64 | 23 | `VERIFY_OK`，0 failure |

检查覆盖：manifest 必备字段；产物 sha/大小；`init-zero-install`/`ready.sh` 可执行且无包管理器/网络调用；
无产品 `firstboot-done` 或 provisioning state；`machine-id`/`resolv.conf` 为空；hostname 固定；无 SSH host key；
证书 bundle 存在；无 apk 缓存/`.apk`/db lock/apk log 或 apt lists/`.deb`/dpkg updates/apt+dpkg lock；
包数据库与 manifest 包集合逐项一致；init/ready/package DB/证书 sha 与 manifest 一致；整棵 rootfs 的
tree digest 与 manifest 一致（可检出额外/缺失/被改文件）。manifest 另用 `python3-jsonschema` 对
`manifest.schema.json` 校验通过。

## 5. 与当前 provisioning 流程的对照

| 维度 | 当前产品首启（Arch/QEMU，T7-F 成功样本） | 本原型（预烘焙） |
| --- | --- | --- |
| 包事务 | 92 包 `-Syu`；T7 终态在 1800 s 内通过，此前 attempt6 曾到达 `run_step` 1800 s 超时；产品默认下载 900 s、事务 3600 s | 首启 0 包；构建期完成 |
| 首启阶段 | 下载 → rootfs 装配 → 92 包事务 → machine-identity → credentials → locale → ssh → service-enablement | 挂载 5 类文件系统 → `exec /ready.sh` |
| 到 READY | 分钟级（QEMU boot + provisioning；无同轮 T3 分解） | x86_64 参考 T3 量级 0.1–0.3 s（三次 n=5 复跑） |
| rootfs 体积 | Arch ext4 3,221,225,472 B（T7 样本） | Alpine 110,170,511 B tar.gz / 306–317 MiB 安装；Debian 228,692,824 B / 656 MiB 安装 |
| 构建成本 | 首启一次性承担 | Alpine 58–87 s；Debian 346–439 s（含下载；随宿主负载变化） |
| 网络 | 首启需要仓库网络 | 首启不需要网络 |

**口径警告**：两边发行版、包集合、架构与夹具不同，不能把 1800 s 与 0.1–0.3 s 相除，也不能外推 ARM64 设备。
上表只回答“哪些阶段从首启消失”，不回答“快多少倍”。

## 6. 包清单与体积

完整逐包 `name=version` 见：

- `research/uml-rootfs-prebake/manifests/alpine-3.22.8-aarch64.json`（111 包）
- `research/uml-rootfs-prebake/manifests/debian-trixie-aarch64.json`（213 包）

| 项 | Alpine | Debian |
| --- | --- | --- |
| 解析包数 | 111 | 213 |
| 安装后体积（`du`） | 317 MiB | 656 MiB |
| tar.gz | 110,170,511 B | 228,692,824 B |
| 构建时间 | 58–87 s | 346–439 s（含下载） |
| libc | musl 1.2.5 | glibc 2.41 |
| 编译器 | gcc 14.2.0 | gcc 14.2.0 |
| Python | 3.12.16 | 3.13.5 |
| OpenSSH | 10.0p1 | 10.0p1 |
| 证书 | ca-certificates 20261020 | ca-certificates 20250419 |

体积主要来自 `build-base`/`build-essential`、Python、tzdata/locale 与证书。如果产品 fast-shell 不需要编译器，
可在构建时用 `--packages` 裁剪；本原型保留“基础工具链”以验证离线可用性。

## 7. 许可证 / NOTICE 边界

### 7.1 事实

- **Alpine**：111/111 包在 `/lib/apk/db/installed` 有 `L:` 字段。分布（按包计，非互斥）：
  GPL-3.0-or-later 15、MIT 14、GPL-2.0-only 10、GPL-2.0-or-later AND LGPL-2.1-or-later 9、
  SSH-OpenSSH 7、GPL-2.0-or-later 5、LGPL-2.1-or-later 4、PSF-2.0 4、BSD-2/3-Clause 各 3、
  Apache-2.0 3 等。`L:` 是信息字段，APK 不校验；不能当法律结论。
- **Debian**：213/213 个 `usr/share/doc` 条目可解析到 `copyright`（181 个实体文件 + 32 个指向其他包 doc
  目录的符号链接）；180 个 copyright 正文提到 GNU GPL。`build-essential`、gcc、coreutils、bash 等均为 GPL 系。
- 两个镜像都包含 GPL-2.0-only 包；GPL-2.0 §3 要求随二进制分发提供完整对应源码（或有效书面要约）。

### 7.2 结论边界

- **本原型不是产品可分发物。** 将预烘焙 rootfs 放进 Android APK 前，必须完成：
  1. 生成 SPDX/NOTICE（Alpine 从 `L:`，Debian 从 `copyright`）；
  2. 为每个 GPL/ LGPL 包准备对应源码或书面要约；Debian 可用同 snapshot 时间戳的源码包，Alpine 需按
     精确 `P/V/C` 归档源码（无官方 snapshot 服务）；
  3. 审查 GPL-2.0-only 与 App 的 Apache-2.0/闭源边界、Alpine/Debian 商标使用；
  4. 由 U5/法务给出结论。
- 在这些审查完成前，产物只保留在研究目录，不进入 `app/`、`guest-assets/` 或发布通道。

## 8. 与 proot 的差异（避免混口径）

- **proot**：没有 guest 内核；rootfs 解包后 `exec` 用户态进程，systemd/OpenRC、真实 namespace/cgroup
  通常不可用。它的“秒开”来自省略内核与 init，不是同一 Runtime 类别。
- **UML**：有真实 guest 内核，必须付内核 boot + init 的成本；本原型把**包安装/provisioning**从首启移除，
  但保留内核 boot 与 init。x86_64 参考 T3 0.1–0.3 s 是“内核 boot + init + 挂载 + READY”的总和，不是 proot
  的 exec-only 指标。
- 因此不能把本原型的 T3 与 proot 的 exec 时间相除；报告只声明“首启零安装”，不声明“达到 proot 秒开”。

## 9. 风险、停止条件与 not-tested

| 项 | 状态 | 说明 |
| --- | --- | --- |
| ARM64 真机首启 | `not-tested` | 无设备；aarch64 产物完成构建、sha、verify |
| 16K 宿主 | `not-tested` | 现有宿主/容器均 4K 页 |
| Debian aarch64 内核首启 | `not-tested` | init 脚本同构；x86_64 Debian glibc 参考已 boot 到 READY |
| ARM64 ext4 boot | `not-tested` | x86_64 ext4 已 boot；ARM64 仅 tar.gz |
| Alpine 仓库跨时间可复现 | 有限 | 无官方 snapshot；记录 APKINDEX sha 与包 `C:` |
| qemu-user postinst | 已绕过 | `_PYTHON_SUBPROCESS_USE_POSIX_SPAWN=0`；记录在构建脚本 |
| 许可证/NOTICE | 未审查 | 不进入产品打包 |
| Root/unlock/SELinux | 未需要 | 全部构建在普通 Linux 用户态完成（root 仅用于 chroot/debootstrap） |

停止条件均未触发：没有跨架构装配失败（qemu binfmt 可用），没有包管理器不可解释的差异，没有需要
Root/unlock/改 SELinux 的步骤。

## 10. 交付物

- `research/uml-rootfs-prebake/build-rootfs.sh`
- `research/uml-rootfs-prebake/manifest.schema.json`
- `research/uml-rootfs-prebake/make-manifest.py`
- `research/uml-rootfs-prebake/init-zero-install`
- `research/uml-rootfs-prebake/ready.sh`
- `research/uml-rootfs-prebake/verify.sh`
- `research/uml-rootfs-prebake/manifests/*.json`（4 份样例 manifest）
- 本报告

## 11. 参考

- `.scratch/uml-zero-device-20261110/spec.md` §2、§3 U2
- `.scratch/uml-zero-device-20261110/issues/03-rootfs-prebake.md`
- `docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md` §2–§6
- `docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F（92 包事务 <1800 s、Arch ext4
  3,221,225,472 B）
- `guest-assets/scripts/orchestella-guest-provision.sh:140-141`（下载 900 s / 事务 3600 s 默认预算）
- `research/uml-runner/README.md` §4（hostfs push/extract 通道）
- `docs/standards/RELEASE-SIGNING.md` §6.4（`check_licenses.py` 发布门）
