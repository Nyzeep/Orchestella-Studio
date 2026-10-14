# Guest runtime audit：完整 Linux、PID1 与可恢复基础服务

日期：2026-10-13。范围：官方下载输入、App 创建与 payload、initramfs、QEMU/AVF 启动参数、native guest agent、发行版 init 与基础服务。用户明确授权完善无 Android Root 的完整 Linux VM；不采用 PRoot。构建和设备验证由主会话负责。本次没有运行 Gradle、安装真实软件包或操作设备。

## 治理与决策

已读 `AGENTS.md`、`DEFENSIVE_CONSTRAINTS/README.md` 与目录内两份 policy、Artifact/Host/Architecture Change Policy、`CONTEXT.md`、ADR-0002/0005/0006/0008/0009/0010/0011。防御目录共有三个文件；未改任何安全政策。

用户请求修复已有 Full Linux 契约：共用 Linux kernel、原生发行版 PID1、真实包管理器、基础服务。主会话已确认属于 ADR-0006 的 bug 修复，不改变 runtime 分类、内核、镜像格式、网络暴露面或桥协议；按 Architecture Change Policy §3 无需重开 ADR。本次默认网络仍为既有 guest NAT；SSH 不创建宿主转发，不接受空密码。每实例随机密码必须成功写入 guest 后才启用密码登录；缺失或历史弱凭据保留密码入口关闭并明确提示 action-required，不偷偷改旧密码。恢复控制台不能成为“完整系统 ready”的替代判据。

读取限本仓相关源码/决策，联网仅读取下列官方公共资料，不发送宿主源码、凭据或实例数据。研究文档位于既有 canonical research 目录。探针输出使用项目外部资源根的本任务子目录，不使用 `.log` 扩展名；不写 C:、注册表、系统服务或防火墙。

## 已确认根因（实现前源码证据）

1. `guest-assets/initramfs/init` 的 `install_generic_linux_init_fallback()` 无条件 `cat > "$target_root/sbin/init"`。首次装配和所有重启都调用它，随后 switch_root 固定 `/sbin/init`。若 `/sbin/init` 是指向 systemd 或 BusyBox 的链接，写入会截断原生二进制；即使之前安装了 systemd unit，也不会由该 shell supervisor 启动。它只循环 shell/bridge/proxy，不是发行版服务管理器。
2. generic init 与 Alpine launcher 在执行 firstboot 之前 `touch /opt/orchestella/.firstboot-done`。`InstanceCreationWizardModel.generateFirstBootScript()` 看见该 flag 就返回；其自身也在运行前写 flag，并把安装放后台，失败仍删除脚本。结果是未安装也完成、失败不可恢复。新的 `FirstBootProvisionPipeline` 必须与旧生成器解耦。
3. `DROIDLAYER_PROVISIONING_COMPLETE` / `provisioned-v1` 仅证明格式化/解包/注入完成，不能证明原生 PID1、基础包或服务就绪。旧脚本在安装任何基础包前发送 COMPLETE。
4. 可挂载 ext4 无 marker 或 foreign filesystem 被当作“肯定没有用户数据”，自动重新格式化。该假设不成立；必须拒绝自动重装已有文件系统，保留数据供恢复。
5. guest 的 root 密码默认公开常量 `1234`，`chpasswd` 失败被吞掉。`InstanceUserConfig.DEFAULT_ROOT_PASSWORD` 亦为该值。guest 端删除默认常量，缺失密码保持账户锁定；主机凭据 worker 负责随机实例密码和持久化。
6. `EmbeddedProvisionInput.writeSegment()` 在覆盖 `/init` 时把 `etc/droidlayer/rootfs.sha256` 写为空。initramfs 遇到空摘要跳过 guest 复验，并允许扫描其他 tarball。主机信任 worker 负责锁定 manifest 注入；guest 强制 filename/size/hash 三项匹配，缺项拒绝格式化/解包。
7. `DistroMirrorProvisioner` 把 Ubuntu ARM64 指向 x86 archive，把 Arch Linux ARM 指向 x86 Arch 仓库；未覆盖 Alpine，RPM/ZYPPER repo payload 未被 initramfs 安装。APT 同时列多个“tier”会让不可达镜像导致 update 失败，不等于顺序回退。镜像和真实包管理器必须匹配发行版/架构。

`QemuLaunchSpec` 与 `AvfLaunchSpec` 均通过 `rdinit=/init` 启动项目 kernel/initramfs；没有 PRoot 路径。native guest agent 负责认证 bridge 与终端字节转发，不能代替 PID1；其 shell exec 不修复上述服务管理问题。生产启动会用 `app/src/main/resources/guest_init` 覆盖 packaged init，因此源/资源必须同步。

## 官方事实与镜像选择

- Debian 发布虚拟机 cloud image，trixie 官方目录列出 arm64 generic/nocloud 的 raw、qcow2、tar.xz。目录本身不能证明 tar.xz 是 rootfs；禁止仅按后缀对整个磁盘归档执行根目录解包。[Debian Cloud](https://wiki.debian.org/Cloud)、[trixie latest](https://cloud.debian.org/images/cloud/trixie/latest/)。本次不修改 catalog；现有 Debian catalog 实际来源是项目固定发布的 linuxcontainers distrobuilder rootfs，不能因为类名 Official 就描述成 Debian 官方云镜像。
- Ubuntu 官方 Minimal Noble 页面提供 `ubuntu-24.04-minimal-cloudimg-arm64.img` 与 `ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz`，并提供 package manifest 与签名 checksum。后者是与现有 guest 解包方式兼容的官方云用户空间候选；更换仍须更新锁定 hash/size 并由目录 owner 完成。[官方目录](https://cloud-images.ubuntu.com/minimal/releases/noble/release/)。当前 catalog 是 Ubuntu Base，其“minimal”不自动证明 systemd、网络工具、SSH 全部存在。Ubuntu Base Wiki 访问超时，因此本报告不引用未取回页面的断言。
- Debian `systemd-sysv` 把 `/sbin/init` 指向 systemd，依赖匹配版本的 systemd。只安装 systemd unit 文件不能保证 init 链接存在或二进制完整。[Debian package](https://packages.debian.org/trixie/systemd-sysv)。基础安装应含 systemd、systemd-sysv、udev、dbus、openssh-server、ca-certificates、locales 与常用工具。
- Alpine `alpine-base` 依赖 openrc、busybox-openrc、busybox-mdev-openrc、alpine-baselayout、alpine-conf、alpine-release、apk-tools、busybox、busybox-suid、musl-utils。minirootfs 的 BusyBox init 不能被 shell 脚本覆盖；增加 OpenRC 后需配置 inittab 和 runlevels。[官方 alpine-base 依赖](https://pkgs.alpinelinux.org/package/v3.23/main/aarch64/alpine-base)。这里 v3.23 页面提供基线事实，不声明当前 catalog 的 v3.24.1 已被安装/验证。Alpine Wiki QEMU 页面返回 403，未将其内容当作证据。
- systemd 上游要求 proc/sysfs/devtmpfs、cgroups、inotify/signalfd/timerfd/epoll、Unix sockets 等内核配置，且 `/run` 必须存在、`/var/run` 指向 `/run`；当前 main README 要求 merged usr。包存在不代表 PID1 服务正常。[systemd README](https://raw.githubusercontent.com/systemd/systemd/main/README)。本次不升级 pinned kernel，不修改其安全能力策略。
- Arch Linux ARM 的仓库布局是 `$arch/$repo`，不是 x86 Arch 的 `$repo/os/$arch`。[Arch Linux ARM packages](https://archlinuxarm.org/about/package-signing)、[官方镜像](https://mirror.archlinuxarm.org/)。包签名不可关闭；更新先保持 keyring 可验证，完整升级与 base 包装配使用单次事务。

## 可实施方案

1. 保留已有真实 init 和绝对/相对链接；通过 guest 根内的可执行探针检验 systemd/BusyBox。独立 boot dispatcher 选择真实 PID1；缺失或探针失败才进入专用恢复 supervisor，输出失败 stage，永不伪报 ready。旧版截断 systemd/BusyBox 标识触发原包重装，成功前保持恢复状态。native PID1 真正启动后的内核 panic 无法用 shell 假装恢复，留给设备 gate 验证。
2. 独立 guest runtime provision module 固化基础包列表，按 apt/apk/dnf或microdnf/pacman/zypper安装；optional upgrade 与 mandatory baseline 分开。配置证书、UTF-8 locale、SSH host key、认证策略、dbus、init服务和原生 agent service。保留 initramfs BusyBox 配置 eth0/route/resolv.conf、多 DNS 与诊断，防止 Ubuntu Base 缺 ip 工具导致离线。
3. `/var/lib/orchestella/provision/state` 原子保存 version/status/stage/attempt/reason。锁防并发；中断后下一次恢复；失败保留脚本与状态。只有包库存验证、真实 PID1、dbus/SSH active 与配置验证全部成功才写完成标记。bootstrap 完成但 PID1尚未交接 = `awaiting-init`，不能称 ready。
4. 所有 guest 网络安装有超时、有限镜像回退，遵守原有签名/TLS；失败在控制台和登录 profile显示明确 stage，提供 `orchestella-guest-provision --retry`。无无限后台重试，无未认证宿主监听，无新增主机传输。
5. 仅空白根盘可首次格式化；已有 ext4/其他文件系统无 marker 时明确拒绝。摘要/大小/文件名在 mkfs 前全部验证。

## 已实现（0.3.0 / guest runtime version 2）

- Canonical 模块为 `guest-assets/initramfs/runtime-functions.sh`、`runtime-entry.sh` 和 `guest-assets/scripts/orchestella-guest-provision.sh`。`scripts/sync-guest-init.py` 把全部代码内嵌进 `initramfs/init` 并同步 `app/src/main/resources/guest_init`；运行时不依赖新增 APK 资源。入口使用独立 BEGIN/END 边界，完整内容与 runtime-entry 按字节核对，避免只通过 shell 语法而留下错误 argv。
- `/usr/sbin/orchestella-boot` 显式选择 `/usr/lib/systemd/systemd`、`/lib/systemd/systemd` 或 `/bin/busybox init`，不写 `/sbin/init`。OpenRC inittab 只维护受管块，保留用户 getty/custom/runlevel。恢复 supervisor 使用独立静态 BusyBox，停止自身 agent/console 进程组后交接；只识别旧项目 supervisor 标识的损坏 BusyBox 才允许从验证过的 initramfs 原子恢复，随后由 apk 重装发行版原包。未知二进制损坏保留并明确失败。
- 根盘新增创建签名只授权第一次 mkfs；已有文件系统无合法 marker 拒绝。匹配 digest 的 assembly-in-progress 只恢复解包，不再次格式化。校验三项元数据全部在 mkfs 前完成。解包有 1800 秒上限及 15 秒心跳。
- Mandatory baseline 与 optional upgrade 解耦，覆盖 apt/apk/dnf或microdnf/pacman/zypper；基础包包括 init、dbus、SSH、证书、UTF-8 locale、网络与进程工具、curl/wget/git、nano/less/tmux/terminfo。APT 刷新必须完整成功；多 tier 顺序尝试，用户源不被 stale tier 静默替换。无 CA 的 Debian/Ubuntu 用专用临时 APT sources/lists 通过发行版签名引导证书，不覆盖用户源，也不关闭签名/TLS。CA 与包操作失败会清除本次创建的 policy-rc.d，用户原有 policy 不删除。
- 随机密码仅成功 chpasswd 后记录非明文状态，SSH 禁空密码；配置更新后 restart 实际服务再验 active。旧弱密码 action-required，不写完成标记；缺失密码保持 password auth 关闭。重启仅应用宿主显式变化的凭据，私有 unlogged fingerprint 防止每次启动重置用户在 guest 内的 passwd 改动。
- 每个阶段原子写状态，单次自动 bootstrap 失败后等显式 retry；`.firstboot-done` 只有真实 PID1、包库存、sshd 配置和 dbus/SSH active 验证后写入。恢复阶段为 awaiting-init，未声称 ready。`--status`、控制台和登录 profile 暴露 stage/reason/retry 命令。

## 本地执行验证

执行环境为 Windows/Git Bash；没有运行 Gradle、QEMU 或设备命令，也没有真实安装 Linux 软件包。

1. `scripts/test-guest-runtime-provision.py`：48 项合成、离线探针通过（40 项基础验证加 5 项真实观测后 checkpoint、locale、共享挂载回归，再加 2 项终端 worker 的 hook 与 user@host prompt 原字节装配检查，以及旧 proxy respawn 精确迁移且保留用户自定义项）。覆盖包安装失败/库存不全/网络错误/服务失败/SSH配置失败不能成功；失败重试；verified 幂等不再安装；旧提前标记不可信；损坏 init 必须重装；bootstrap 不能伪报服务 ready；证书引导失败与重试且用户源字节不变；只清自建 policy；pending/变化的凭据必须先应用；旧弱凭据 action-required；Alpine custom inittab 保留且重复安装字节幂等；旧 BusyBox 可识别恢复和未知破坏保留；文件名/大小/摘要失败；已有根盘不得格式化；完整生成入口与参数匹配。
2. 独立 `javac` / `java`：`GuestRuntimePayloadProbe` 51 项断言通过，包括八个发行版无论 upgrade 选择始终存在 baseline、版本匹配、ARM64 仓库布局、RPM 签名保持、无 shell 注入和 wrapper 不预写成功。探针源码位于 `guest-assets/scripts/tests/`，不混入独立 guest-assets Gradle 模块的自动 Java 编译路径。
3. 四个 shell 源语法检查、`sync-guest-init.py --check`、init 与 APK 模板字节一致、完整入口与 canonical runtime-entry 字节一致通过。
4. 独占 diff 的 `git diff --check` 通过；Git 提示两个 Java 文件将按仓库既有设置转 CRLF，guest shell 源保持 LF。

两个原生 Linux 符号链接执行 fixture 在 Windows 未运行；一个 OpenRC 绝对链接操作采用 synthetic syscall 模型。没有把这些项描述为真实 Linux 链接通过。失败 fixture 仅含合成数据，清理后保留简短结果与字节一致孪生。

外部证据目录：`D:\\Development project(开发项目)\\Orchestella开发目录\\Orchestella\\artifacts\\guest-runtime-audit-20261013`。Owner 为本 guest-runtime audit 任务；用途为 synthetic/JVM 验证；源码不入该目录。保留文本结果及其 `.preserved.txt` 孪生供主会话 review；JVM classes 可在审查完成后由 owner 清理，不属于工具链/镜像等永久资产。不写 C:、系统目录或全局缓存。

当前交付模板 SHA-256：`dbf9c980aed9fd9a25dbe3430c3708d3b229a72f6d6e84bbeb8fe1e0e37e3575`（含既有七族/原生 init/恢复 Bash 修复，以及实际缓存解析超时触发的受管仓库事务隔离、1800 秒事务预算与完整 SHA 阶段心跳）。实测后的两项收敛修复：无真实 virtio 9p tag 时不生成共享 fstab mount；原生 PID1 交接时 packages-v2 检查当前包库存与有效 init 后复用 baseline，不重复整套安装，locale-a 已存在时不重复 locale-gen。

## 主会话设备观测与待验事项

主会话在 Honor MEY-AN00 上运行 0.3.0 Ubuntu 新实例，基础链已得到真实验证：首次 rootfs 解包约 357 秒；其间 QEMU CPU 活跃、根盘 allocated 大小增长，因此先前仅有 mount 日志不能推出无限循环。随后 guest 网络 ping/TCP/DNS 可通，基础包安装、init repair、CA、machine identity、locale、SSH 配置和 service enablement 成功，恢复阶段正确为 awaiting-init 且无完成标记。主会话正常关机后复用同一根盘冷启动，成功运行真实 systemd PID1，cgroup2、dbus 和 SSH active、DNS 正常；guest 243.722 秒记录 `status=ready stage=verified reason=none attempt=2` 并到达 `multi-user.target`。

Canonical 设备证据为项目外部 `Orchestella/artifacts/quality-20261013/evidence/ubuntu-native-final-progress.log.txt`，本 agent 只读核对：953 行 `cgroup2fs`、954/955 行 `active`、1005/1006 行 ready、1008 行 multi-user.target。该日志属于主会话；本 agent 没有操作设备，不能将这些运行归为自己的探针。此轮实测使用前一版模板；最后无共享挂载和 checkpoint 优化已在 45 项回归中通过，待最新 APK 冷启动复核。

待主会话继续验收：最终优化版本重启与 top/nano 交互、随机密码实际 SSH 登录与默认无宿主转发、掉网恢复、Debian 装配、Alpine/OpenRC 原生链接与损坏旧实例修复，以及其他发行版实际 ARM64 包名/签名/keyring/repo 兼容性。microdnf/ZYPPER/pacman 支持目前只有实现和合成验证，不冒充已安装。镜像清单维护仍由 catalog owner 负责，本次未修改 catalog/manifest。

## 官方来源补充

- [Ubuntu Noble ports repository](https://ports.ubuntu.com/ubuntu-ports/dists/noble/)：目录含 arm64 contents 和 main/restricted/universe/multiverse，支持纠正 ARM64 x86 archive 误配。
- [openSUSE Leap 16 repository](https://download.opensuse.org/distribution/leap/16.0/repo/oss/)：当前 Leap catalog 不能静默混用 Tumbleweed 仓库。
- [Arch Linux ARM package signing](https://archlinuxarm.org/about/package-signing)：使用 archlinuxarm-keyring、pacman-key populate 与 Required package signatures；不得抄用参考项目的 allow-untrusted/禁签名路径。

## 七族官方 ARM64 metadata 核验与差异修复（追加请求）

用户追加七个当前 catalog 发行版全部可用。本次扩展只涉及 rootfs/provision/agent 与既有终端 hook 装配，不扩大 UI。七族为 Debian 13、Ubuntu 24.04、Fedora 43、Arch Linux ARM rolling、openSUSE Leap 16.0、AlmaLinux major 9、Rocky 9.6；Alpine 是保留的额外 OpenRC 风味，不混计进这七个 catalog 条目。主会话镜像审计报告 Alma 固定 rootfs 实际 os-release 为 9.8，catalog major 9，不能写成 Alma 9.6。

`check-distro-baseline-metadata.py` 仅读取 metadata worker 已下载的官方索引，重新核对文件大小和 SHA-256/SHA-512，再解析完整 primary/Packages/pacman DB，对 canonical provision 脚本每个基线包做名称及 ARM64/noarch 匹配。七族全部 `ALL_BASELINE_NAMES_PRESENT`，不是仅对抽样包判断。结果与逐包版本/位置/checksum 位于本任务外部 `distro-baseline-metadata-results.json` 及 preserved 孪生。

| 发行版 | 官方索引与本次修复 | 信任验证界限 |
| --- | --- | --- |
| Debian 13 | [trixie arm64 Packages](https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.xz)，systemd-sysv、udev、dbus、SSH、locale 与所有工具名称完整存在 | metadata owner 执行 gpgv 验证 InRelease，return 0；索引 digest 本次重验 |
| Ubuntu 24.04 | [noble ports arm64 Packages](https://ports.ubuntu.com/ubuntu-ports/dists/noble/main/binary-arm64/Packages.xz)，保留无 CA 的签名 HTTP bootstrap；全部 baseline 包在 main 存在 | metadata owner 的 Ubuntu archive InRelease 签名 return 0；不关闭 TLS/包签名 |
| Fedora 43 | [Everything aarch64 repomd](https://dl.fedoraproject.org/pub/fedora/linux/releases/43/Everything/aarch64/os/repodata/repomd.xml)，实际包名 `dnf5`、`wget2-wget`，虚拟 `dnf`/`wget` 不能用于 rpm -q 库存判定；显式加 systemd-udev、dbus-tools、SSH client；sshd 实际 /usr/bin 路径动态解析 | official HTTPS repomd 的 primary 大小/checksum 重验；RPM 包签名执行仍在真实 guest transaction，不把 metadata checksum 叫作包签名通过 |
| Arch Linux ARM | [core](https://ca.us.mirror.archlinuxarm.org/aarch64/core/core.db)、[extra](https://ca.us.mirror.archlinuxarm.org/aarch64/extra/extra.db)，官方通用 mirror hostname 曾发生证书不匹配，改为已验证官方 ca.us 节点；保留 Required package signatures/keyring | 官方 HTTPS DB 可读，保存 package PGPSIG 存在性；本 agent 未执行 pacman 包签名校验 |
| Leap 16.0 | [oss aarch64 repomd](https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/aarch64/repodata/repomd.xml)，repo 必须带 `$basearch`；使用官方 repomd.xml.key；显式 udev、dbus-1-tools、openssh-server/client、ca-certificates-mozilla；原镜像有效 CA 在 /etc/ssl/ca-bundle.pem，不把 Debian bundle 链接缺失误当没 CA | primary checksum 重验；RPM/GPG 实际安装待设备；不混用 Tumbleweed |
| AlmaLinux 9 | [BaseOS](https://repo.almalinux.org/almalinux/9/BaseOS/aarch64/os/repodata/repomd.xml)、[AppStream](https://repo.almalinux.org/almalinux/9/AppStream/aarch64/os/repodata/repomd.xml)，两库都必须存在；dbus-tools/systemd-udev/glibc-langpack-en 位于 BaseOS，ncurses-term 位于 AppStream | primary checksum 重验；保留镜像 local RPM GPG key、gpgcheck=1；实际包事务待设备 |
| Rocky 9 | [BaseOS](https://dl.rockylinux.org/pub/rocky/9/BaseOS/aarch64/os/repodata/repomd.xml)、[AppStream](https://dl.rockylinux.org/pub/rocky/9/AppStream/aarch64/os/repodata/repomd.xml)，保留 major 9 正常更新，不能把旧 9.6 用户空间的包永远锁在已归档仓库 | 同上；rpm curl-minimal 显式冲突 curl 时保留已安装签名变体，禁止使用 allowerasing 掩盖 |

只读 pinned rootfs 审计的实际 os-release：Debian `ID=debian VERSION_ID=13`、Ubuntu `ubuntu/24.04`、Fedora `fedora/43`、Arch Linux ARM `ID=archarm BUILD_ID=rolling`、Leap `opensuse-leap/16.0`、Alma `almalinux/9.8`、Rocky `rocky/9.6`。Provisioner 将 archarm→arch、opensuse*→opensuse 正规化后再校验 catalog expected id；七族回归必须使用这些真实别名，不能用 mock ID 掩盖 unsupported-distro。

Arch 原 pacman.conf 有效 `SigLevel = Required DatabaseOptional`。RPM 精确 local key 路径均存在于已校验 rootfs：Fedora `RPM-GPG-KEY-fedora-43-aarch64` 指向 `43-primary`（SHA-256 `2b1449a082d3264dda8e18369f04e9ac4163bf3f8cb530b0783dc2ab064a08ec`）、Alma `RPM-GPG-KEY-AlmaLinux-9`（`387a8be6b70ecff4949d28778bf8abd06231654283b6e9515afc9305a1391458`）、Rocky `RPM-GPG-KEY-Rocky-9`（`3a66e521e7a4abac9768d889c7cd60a8250f59512b275cf576c8a10ff34624c1`）。Generated gpgkey 引用沿用这些精确路径。此处只证明受校验镜像包含信任锚和正确配置，不把 key 文件存在等同于已经执行全部软件包签名校验。Canonical 证据为主会话 `distro-inputs/evidence/*-validation.json`，未另行克隆或修改镜像。

RPM profile 另外保留已安装 coreutils-single、Fedora wget1-wget 兼容变体；由 rpm 库存确认之后选择实际包名。存在 dbus-broker.service 时启用实际 unit，start/is-active 仍通过 dbus.service 标准别名，避免 systemctl enable alias 被拒绝。Fedora 所有选源均含 updates 和版本/架构本地签名 key；Alma/Rocky 所有单源也含 BaseOS 与 AppStream。以上包名修复由官方 full primary 的真实缺项触发，未用 mock 结果替代上游事实。

`test-distro-runtime-matrix.py` 七族 76 项合成包管理器/服务后端验证通过，覆盖各族包安装与网络失败不 ready、重试、库存失效、服务失败、幂等、microdnf 引导，以及 RPM 最小变体不冲突；最终 actual provider（dnf5/wget2-wget）与 dbus-broker alias 回归也已包含在最新 76 项执行结果中。探针执行 canonical 源码的 unique fixture 快照，避免共享工作区编辑改变 sh 的读取偏移。该测试不能证明真实软件包已安装。

## Bridge v1 重复 HELLO 的实际交付要求

`guest-agent.c` 初始 authenticate 与控制 serve 现共用 HELLO 校验器，serve 接收 expected_token。已认证通道收到同 token、包含 v1 的合法 HELLO 时再次返回 READY 1 并继续；错 token 立即 unauthorized/exit 3，版本不兼容 exit 2，畸形 exit 1，未知命令仍 exit 4；没有扩展协议版本、命令或宿主暴露面。

14 项 Windows GCC 实际编译/进程探针通过，覆盖连续重试、命令后 HELLO、legacy prefix、错 token 后不能 PING、版本/格式/长度负例。C 源 SHA-256 锁定 `bc404ee51f1eef710f7781826a3a811eb94bd79b5b679d1e096a0d32c68dad9e`。`GuestAgentProcessTest` 同步增加跨平台回归；`test-guest-agent-hello.py` 提供独立入口，不运行 Gradle。

生产 C 必须是 ARM64 Linux 静态产物，不能把 Windows 测试 exe 或 Android bionic 动态产物放进 guest。原 canonical 构建入口 `guest-assets/build.sh` 273–282 使用 musl C11/Os/Wall/Wextra/Werror/static、无 build-id，strip 后 readelf 无 INTERP。主会话委托 native worker 准备 musl sysroot 与 NDK baremetal clang/builtins，再定点替换 packaged initramfs 的 `opt/droidlayer/guest-agent`，更新既有 runtime-artifacts-v1.sha256 锁定摘要；未增加绕过校验的新 binary 注入通道。跨编译/打包/实际 HELLO retry 由主会话持有，未完成二进制交付前仅源码修复不能称生产生效。

主会话已完成实际 ARM64 二进制和打包。本 agent 只读复验 `guest-toolchain/evidence/toolchain-provenance.json`、`guest-agent-runtime-update.json` 及生产 packaged initramfs：静态 agent 为 480,712 字节，SHA-256 `1c6369adeef447ecab4a8b0aefcfe9b3c75157fbf298dc058e43cf3900c3745d`；ELF machine AArch64 (183)，无 PT_INTERP/PT_DYNAMIC。cpio 内对应 entry 字节匹配该摘要；压缩 bundle SHA-256 `ff7823d758975729f86701f1340dcee506f3977b58cb8f4753f0e9c9cf97c78d` 与 App 的 runtime-artifacts manifest 一致。主会话保留其他 428 entries。故二进制已真正进入既有校验链，不是只改 C 源；ARM64 guest 实际重试结果仍以设备 gate 为准。

实际工具路径为 `D:\\AndroidSDK\\ndk\\27.0.12077973\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin\\clang.exe`；musl sysroot 为项目外部 `Orchestella/artifacts/quality-20261013/guest-toolchain/sysroot`；libclang_rt.builtins 使用同 NDK `lib/clang/18/lib/baremetal/libclang_rt.builtins-aarch64.a`，不是 Android Bionic 库。完整 command argv 与输入 SHA 以 toolchain-provenance.json 为 canonical。核心 flags 为 `--target=aarch64-linux-musl --sysroot=<sysroot> -static -fuse-ld=lld -nostdinc -isystem <sysroot>/usr/include -std=c11 -Os -Wall -Wextra -Werror -fno-ident -Wl,--build-id=none -nostdlib -Wl,-e,_start`，链接顺序为 musl crt1.o/crti.o、guest-agent.c、start-group libc.a + baremetal builtins、end-group、crtn.o。该路径未要求设备 gcc，也未安装新的宿主系统工具链。

终端 worker 的 `20-orchestella-terminal-integration.sh` 只由 owning worker 编辑。本任务在 sync 脚本内嵌其原字节到 `/etc/orchestella/shell/`，权限 0644，并在 inline prompt 初始化后以 Bash/readable 条件 source；无额外 APK asset 依赖。hook 并行更新时严格字节门会失败，必须重新 sync 后再构建，不能用旧嵌入脚本冒充新功能完成。

## Fedora 恢复登录 shell：实际 smoke 的定点修复

主会话第二批实际 Fedora 启动约 70 秒进入 recovery，状态为 repository-refresh；恢复控制台原先总用 BusyBox ash -l，因 Fedora /etc/profile.d/lang.sh 包含 Bash 专属重定向语法，在第 62 行报 unexpected redirection。此时尚未证明包基线失败，不能把 shell profile 错误归为 DNF/GPG 错误。

最小修复只改变恢复控制台 shell 选择：在 respawn 前一次检测 /bin/bash、/usr/bin/bash，要求真实可执行位、ELF 魔数，以及私有 BusyBox timeout 5 秒包围的 --noprofile --norc --version 成功。选择健康 Bash 后通过 setsid 启动 Bash -l；没有健康 Bash 时保留私有静态 BusyBox ash，避免依赖可能正在修复的 /bin/sh 或 /bin/busybox。候选健康探针不放在每次登录循环中，也不改变原生 PID1、包安装/状态、C agent 或 binary manifest。

本 agent 只读已验证的 pinned Fedora tarball /etc/passwd，确认 root 原生登录 shell 已是 /bin/bash，审计中的两条 Bash 路径均为 AArch64 ELF；native getty 无需改 passwd。`test-guest-runtime-provision.py --recovery-shell-only` 六项定向探针通过（Windows 的 ELF 执行位和 loader 探针明确采用合成模型），覆盖健康 Bash 优先、另一候选可用、旧损坏脚本不可执行、加载失败及私有 ash 兜底。两份 shell 源语法检查和 source/App 模板同步通过；此前 48 项基础恢复、76 项七族包矩阵结果继续保留，本次没有重跑或冒充新增完整矩阵通过。

当前运行中的 Fedora batch 使用修改前的 snapshot，仍由主会话继续记录真实 DNF 阶段。本修复需要下一版 APK 才生效；待下一轮看到 recovery shell=/bin/bash 和 lang.sh 错误消失后才可标记设备验证通过。

## Bridge v1 POWER：真实 Debian 检查触发的缺失路由修复

主会话报告 Debian 的七项真实 guest 检查通过（systemd、cgroup2、dbus/SSH、包/DNS、持久写入同步及 ready），但最后 bridge client POWER 被拒绝，driver 最终 failed-poweroff。前轮使用交互 shell 的 systemctl 关机并不能证明控制协议 POWER 已闭合。本 agent 核对 C 源发现原先没有 POWER 分支；`BridgeControl.PowerAction` 和 `BridgeClient.power()` 已有精确 shutdown/poweroff 两动作与 OK power/power-unavailable 响应，属于既有 v1 契约缺项修复，不新增命令、协议版本或 ADR。

C 在已认证 serve 循环的 PING 后加入严格完整行匹配，只接受 ORCHESTELLA 或 legacy DROIDLAYER 前缀的两个现有动作；额外字段、未知动作、大小写变化、shell 注入均拒绝。生产路径检查 guest root 和固定 ELF 可执行文件；真实 systemd/BusyBox init 使用 /sbin/poweroff，恢复 PID1 使用已分发的私有 BusyBox poweroff -f。没有请求 payload 拼接 shell，也没有 Android Root、宿主 power API、DISPLAY 或新的 I/O 面。pipe/access/fork 失败返回 power-unavailable；成功 fork 的 child 等待 parent 已成功写出 OK power 的 gate，随后延迟 250 毫秒再执行固定 execl argv。回复发送失败会取消 child；OK 表示成功派发，不等同于已经完成关机。

新 C 源 SHA-256 为 `2676eb618bf3c189bd2c1c35e67ca49f27a8eb7962e87c6f78e35d4cc73c8b81`。独立 `test-guest-agent-power.py` 25 项实际编译/进程探针通过，只在编译期 `ORCHESTELLA_TEST_POWER` mock 下统计派发，不执行 Windows/Linux 宿主关机。覆盖两前缀/两动作、恢复固定 argv、重复 HELLO 后 POWER、未认证与错 token 无派发、access/fork/pipe/reply 失败无假成功、非法/额外/注入动作，以及未编入 mock 的 Windows production 永远 unavailable。原 HELLO 14 项在新 C 源摘要下重新通过；`GuestAgentProcessTest` 增加三项自动 mock 回归，所有 POWER 测试显式使用 mock build，避免在 CI Linux 上误调用宿主关机。Windows mock 证明认证、解析与错误传播，不能替代生产 Linux 的实际 fork/exec 和关机验证。

旧静态 agent `1c6369…`/bundle `ff7823…` 对应此前 HELLO-only C 摘要，不能据此宣称新 POWER 生效。主会话已以新源摘要完成 musl ARM64 实际重编（production_test_defines=[]），canonical 证据在 `guest-toolchain/evidence/power-control/power-build-provenance.json` 和 `guest-agent-runtime-update.json`，保留原 agent 未覆盖并重新定点更新 cpio/manifest。本 agent 随后只读独立复验实际 production cpio entry：新 agent 494,760 字节，SHA-256 `1d0a8293c3d318f17a1326fb686598f22229851ed116360ab3175be8436dda9f`，ELF AArch64，无 PT_INTERP/PT_DYNAMIC；新 bundle `0caf5f9a6e2334ce3608fb745b69983b2328b15fcee0b31b9a2eca5685636fba` 与 App runtime manifest 一致。新二进制已实际进入锁定链，本 agent 没有修改 binary/manifest。

真实 control POWER 的 ACK 与 QEMU 退出仍由主会话设备 gate 执行；收到该证据前，Debian 七项 guest 检查可记录通过，但 driver 关机闭环不能标为完成。恢复 Bash 模板和七族包基线在本修复中未改动。

## 实际仓库缓存解析超时：保留配置与缓存的事务修复

主会话 `qa-run-3a81ab83477c` 中 openSUSE 与 AlmaLinux 原生 init 已健康，但 repository-refresh 在约 600 秒阶段上限失败，旧 state 把超时写成 offline-or-repository-error。只读 Alma `progress-0010.json` 明确记录 BaseOS 58 MB、AppStream 34 MB 下载成功及两个 repo ID 重复警告；Leap 同时刷新 vendor OSS/openh264 和新注入的同源 OSS。下载成功不等于缓存解析已完成，TCG 1 GB 下的 CPU 工作不能按断网处理。

最小改动保留原根盘、cache 与 vendor/user 配置：

- `run_step` 对 repository-refresh、包安装/修复、证书引导、locale 和 optional upgrade 使用 1800 秒总操作上限，短服务步骤维持 600 秒。timeout 的 124/137/143 单独记录 `reason=operation-timeout`、退出码和操作预算，不被外层 offline 文案覆盖；失败不写 ready，显式 retry 复用根盘与缓存。firstboot systemd unit 整体上限为 2 小时，避免多个合法长阶段被原 30 分钟总门提前取消。
- `stage_guest_package_repositories(root,inputDir)` 仅复制显式 provisioning payload 的 `.repo` 原字节到 `/etc/orchestella/provision-repos/<distro>/`，写版本/发行版/文件/精确 ID/digest marker。首次与冷恢复都可安装这份输入，不采纳未知 vendor 文件；没有注入或历史受管 marker 的旧实例保持原 vendor 配置，上次明确输入的 marker 可供离线重试。
- DNF/microdnf 的基线 refresh/install/init-repair 用 `--setopt=reposdir=<owned directory>`，仓库 ID 不变、缓存路径不变，仍 timeout=20/retries=0。ZYPPER 的基线只加载受管 reposd-dir 与空受管 servicesd-dir，按精确 alias 刷新，install 使用 --no-refresh 与 --repo；不刷新 optional openh264 或服务生成的重复 vendor 仓库。没有删除原 `.repo`、清空 cache、force-refresh、allowerasing、关闭 TLS/GPG 或自动信任未知 key。
- ZYPPER 网络策略只写 private ZYPP_CONF 副本，原 zypp.conf 字节保留。已从锁定 Leap 镜像确认 `download.max_silent_tries=0` 意味无限尝试，因此设置 1（一次尝试、零重试），connect_timeout/transfer_timeout 均 20。官方 [ZYPPER 手册源码](https://raw.githubusercontent.com/openSUSE/zypper/master/doc/zypper.8.txt) 核实 reposd-dir、servicesd-dir、no-refresh、refresh alias、install --repo、ZYPP_CONF 的实际参数，未以 mock 代替 CLI 存在性证据。
- Arch 829 MB 输入的完整 guest 软件 SHA 同样可能需数分钟；仍完整读 size/hash，不跳过或缩小校验。该阶段新增 30 秒 heartbeat 和 1800 秒完整校验上限，超时在 mkfs 前明确拒绝；原解包上限维持 1800。当前运行的旧 snapshot 不热改，新增行为在下一 runtime 模板中生效。

新版七族矩阵 **133 项**通过，覆盖 timeout 分类/重试、受管目录与 ID、无输入 vendor fallback、原 `.repo`/zypp.conf 字节不变、cache 保留、禁签名/TLS配置及未知 ID/marker 改动拒绝。主恢复探针 **56 项**通过，含此前 48 项、6 项恢复 shell 选择、2 项完整 SHA 心跳/校验超时负例；Windows 原生链接执行限制继续明确。source/App/full-entry 同步、shell 语法和 diff 检查通过。测试只采用合成包后端，不冒充实际长事务已通过；由主会话以新封存 runtime 对旧 QA 根盘冷恢复，真实复用下载缓存并完成安装后再记录设备 ready。

生产源码在 `d6b344…` 同步后冻结，C POWER 源 `2676eb…`、静态 agent `1d0a829…`、封存 cpio `0caf5f9…` 未修改。Agent 未运行 Gradle、设备命令、提交或 push。

## 受管仓库重试失败的退出码可观测性

主会话确认新受管仓库确已命中且重复 ID 不再激活；随后 Alma/Leap 在约 110 秒返回 repository-refresh 失败，未触及 1800 秒总预算。最后完整 stderr 尚待主会话 driver 的 15 秒只读、有界脱敏诊断，不能据此猜测 TLS、HTTP、签名或 key 信任原因，也不能继续增大超时或自动放行未知 key。

定向实际 shell wrapper 探针复现了元数据遗漏：修复前最终 `operation_exit_code=1` 能保存，但独立 last-operation 字段不存在，控制台摘要不显示 rc；上一分钟 running snapshot 的 rc=0 不能代表最终命令返回。本次只补 `last_operation_stage`/`last_operation_exit_code`，保存最近完成操作的原始返回；`fail_stage` 在 `if ! run_step` 或 wrapper 调用下仍保留原 rc，避免写成函数自身 exit 1，EXIT cleanup 保留中断返回。控制台摘要显示数字/固定 stage 字段，原始包 stderr 留在 guest 本地日志，不默认广播日志全文或凭据。未执行过命令的状态使用 not-run，不假造成功码。

`test-distro-runtime-matrix.py --failure-diagnostics-only` 10 项通过：两种实际 shell wrapper 对 1、4、124 返回的 state 与 report 一致、只有 124 分类为 operation-timeout、失败没有完成标记、重试不复用旧错误码、原始 stderr 不出现在摘要。失败测试先在旧脚本上运行并捕捉缺失字段，随后在修复源码通过；不以此模拟不同 GPG/HTTP 原因。修复前快照与新源码的 read_os_field 之后部分逐字节一致，故仓库、网络、信任、包事务和时限逻辑仍冻结，C agent/binary 也未改。当前模板 `00a516…` 是仅错误字段的新版本。

主会话 raw reader 只读取得两个文件，但文件系统标记为 `needs-recovery-readonly-no-replay`。未回放 ext4 journal 时，inode/state/log 的 running 内容仅代表历史持久视图，不能当作最终失败日志，更不能据 missing/stale tail 猜测 HTTP/TLS/GPG 原因。本任务不离线 replay、mount 或扩大导出；由主会话下一 driver 在运行 guest 内完成 15 秒只读、有界脱敏最终诊断，再决定原因。

失败状态原子 rename 只提供可见性，未单独保证 journal/log 持久。本次最小改动只在共用 report 中调整顺序：fail_stage 或意外 EXIT cleanup 先完成 state 写入、追加本地固定报告，再执行正常 guest `sync`，最后发 stdout/devconsole failure 通知，使观察该通知后立即停 VM 的 driver 已经过刷盘请求。若 sync 失败，输出固定 `DURABILITY_ERROR reason=sync-failed`，不宣称记录已落盘，也不覆盖原包错误码或写 ready。封存 writeback I/O 支持 flush，由主会话对真实 guest 重启读取效果验证；这不能将任意断电或未完成 journal 回放视为无条件持久成功。

`--failure-diagnostics-only` 扩展为 14 项并通过：两族原 rc、sync 时已存在 failed state/本地失败摘要、通知在 sync 后、sync 失败显式且 rc 保留、意外 cleanup 的 143 同门、重试恢复。测试先在旧源码明确失败于 missing flush，随后只修改 report 函数通过；除 report 外的生产脚本与 `00a516…` fixture 快照逐字节相同。新模板 `ec5a1ac…` 需下一 runtime snapshot 才生效，当前旧 batch 不热改。本 agent 未操作设备或打开/mount QA 根盘，未请求新网络端点，未执行 Gradle、提交或 push。

## 显式 owner 手动恢复诊断

主会话后续实际确认 Alma attempt 3 的受管仓库刷新成功并下载 102/102 RPM 输入，继续包安装/签名事务；该进展推翻了“仓库配置一定导致永久失败”的未证假设，尚未完整 ready。openSUSE 同一旧根盘冷恢复则停在 systemd local-fs 启动日志，直到 3900 秒 HELLO 等待结束，没有 agent/getty/provision 已启动的证据。两种现象必须分别诊断，不能由无输入推断内核崩溃，或把下载完成称为完整安装。

按用户明确要求，boot dispatcher 增加七行默认关闭的选择胶水：只有 `/proc/cmdline` 的完整 token 精确为 `orchestella.recovery=1` 才进入现有私有 recovery 并设置 manual 标志。缺省、0、true、10 和伪前缀继续原生 init；不覆盖 `/sbin/init` 或 native-init 原值。手动恢复仍用健康 Bash 和既有 token 认证 agent，控制台明确 `reason=owner-requested mode=manual baseline=not-ready automatic_provision=disabled`。仅跳过自动 bootstrap 和自动原生 handoff，原 provisioning state、包库存与根盘日志保留；owner 可显式重试，但恢复 PID1 不能被当作 Full Linux ready。

`test-guest-runtime-provision.py --manual-recovery-only` 11 项通过，运行 dispatcher/自动操作 guard 的原控制流，仅将 exec 系统调用替换为合成记录，验证默认原生、严格参数、普通损坏-init 自动恢复语义保留、显式手动恢复、无自动装包/交接，以及原 init/state 字节不变。source/App 模板与两份 shell 语法检查通过。包 provision 源、失败 sync 门及 C POWER/静态 agent 封存链未改。

该入口需新 runtime snapshot 才生效；主会话沿已保存 openSUSE UUID/raw 指定参数，以 probe-only 模式读取 guest 内有限 `journalctl --directory=/var/log/journal --no-pager -n 20`，无需宿主 mount/export，也不进行离线 journal replay。本 agent 没有操作设备或标记此次恢复现场通过。当前 Alma VM 保持原 snapshot 继续交易，不热修改。

research Skill 调用因用户级/仓库级同名歧义返回错误，随后直接读取仓库 `.agents/skills/research/SKILL.md`，按 primary-source 与 canonical 单文件要求完成研究。没有重复克隆 DroidSpace/Podroid/proot-distro，也未向外部资料服务发送仓库源码。
