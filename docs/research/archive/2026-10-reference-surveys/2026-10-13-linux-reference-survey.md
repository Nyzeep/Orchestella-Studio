# Android Linux 参考项目源码调查：Root、PRoot、VM 与可恢复安装

研究日期：2026-10-13。研究方式：限时只读本地源码核查，加 GitHub CLI 公开搜索/API 与 AOSP Gitiles 官方资料读取。本文提供外部事实和实施建议，不修改 ADR、spec、产品契约或 tracker，不代表 Orchestella 已实现这些能力。

**最直接的结论**：Orchestella 的无需 Root、独立 Linux guest kernel、AVF/QEMU 双后端目标，应沿 VM 路线独立实现。Droidspaces 的 Root namespace 容器、proot-distro/Andronix 的 PRoot 用户空间、Termux:X11 的显示服务器承担不同职责，不能把这些项目的启动脚本与 Android 兼容补丁混成同一种运行时。镜像能解包、shell 能打开，均不足以证明 init、必需包、证书、locale、DNS、后台服务和可恢复升级已经可用。

## 1. 范围、权限与验证状态

已先读 `AGENTS.md`、`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md`、同目录 `DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md` 与 `INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md`，以及 `ARTIFACT_ORGANIZATION_POLICY.md`、`HOST_ENVIRONMENT_POLICY.md`。本任务只读取用户/协调者指定的独立公开参考 checkout 和相关治理/研究文档，唯一主动新增的项目文件是本文。旧 policy 中的 DroidLayer 路径不产生另建目录授权；本次实际工作区和报告路径由委托明确指定。

网络范围是 `api.github.com`（公开仓库 metadata/search/contents）、`android.googlesource.com`（官方源码/文档）；尝试 `source.android.com` 官方 AVF 页面时连接超时。请求只含公开项目名、公开 revision、公开文件路径；没有上传宿主工作树、Git 历史、凭据、镜像或用户数据。没有新增 clone、初始化子模块、安装依赖、构建、运行 guest/测试脚本、连接真机、提交或 push。限时范围内未递归审计所有第三方组件。

| 状态 | 本次完成程度 |
| --- | --- |
| 已执行 | 本地 `git log -1`/`remote get-url`、定点 `rg` 与源码读取；公开 `gh search repos`/`gh api`；官方 AOSP 文档/manifest/build 声明读取 |
| 静态确认 | 下列固定 revision 的代码分支、包列表、init 交接、下载检查、迁移标记与许可证声明 |
| 未运行 | 构建、镜像启动、联网安装、SSH/VNC 登录、两后端切换、迁移崩溃恢复、性能、设备/OEM 兼容性 |
| 有限/不可取得 | UserLAnd 的 runtime 子模块未初始化，针对其固定 revision 的公开 GitHub API 返回 404；未读取该子模块实际实现 |
| 不属于本报告验证 | Termux app/terminal 模块逐文件许可证核查，由另一研究任务负责；本文只记录 Podroid/Droidspaces 的集成与署名声明 |

已有 [2026-09-05 Podroid 调查](2026-09-05-podroid-源码分析.md)对应旧 revision `bc7477fb`；本文保留该历史记录，以新 revision 核查结果补充，不把旧记录当成当前源码的证明。

## 2. 来源、固定 revision、维护时间与许可

本地目录名相对于用户指定的参考根。所有时间以 UTC 记录。`pushed_at` 是 GitHub 仓库级活动时间，可来自其他分支/tag；不能等同于默认分支代码更新、release 日期或安全维护证明。GitHub metadata 的 `license` 是探测结果；最终以实际文件和分组件许可为准。

| 本地目录 / 原仓库 | 本地 HEAD | HEAD 提交时间 / API pushed_at | 许可证据与复用边界 |
| --- | --- | --- | --- |
| `DroidSpace` / [ravindu644/Droidspaces-OSS](https://github.com/ravindu644/Droidspaces-OSS) | `c2d27d2db77dcfa27f16926b450c0eff89429d3b` | 2026-10-12 17:35:26 / 2026-10-13 07:32:24 | 根 LICENSE 为 GPLv3，`src/boot.c:5` 为 GPL-3.0-or-later；需分组件审查 Android 终端/显示依赖 |
| `Podroid` / [ExTV/Podroid](https://github.com/ExTV/Podroid) | `ce0121896b2895637ab1f656cbb0d75fa2874489` | 2026-09-21 14:03:05 / 2026-09-21 14:06:06 | LICENSE 为 GPLv2 文本；CREDITS:145–146 声称项目 GPLv2-or-later；实际终端依赖与鸣谢存在漂移，不能把根许可覆盖全部组件 |
| `proot-distro` / [termux/proot-distro](https://github.com/termux/proot-distro) | `5a427a1f924550c176ab34197e9fcf5d010fdbd9` | 2026-10-11 10:00:01 / 2026-10-11 10:00:25 | GPLv3；Python 文件许可头明确 GPL-3.0-or-later；本 revision 是 Python/OCI 架构，不能套用早期 Bash 插件版描述 |
| `Droidspaces-rootfs-builder` / [Droidspaces/Droidspaces-rootfs-builder](https://github.com/Droidspaces/Droidspaces-rootfs-builder) | `b60492d1b2e78f343789f2c94de23b831d1728a9` | 2026-10-11 10:48:03 / 2026-10-11 10:48:05 | 本 checkout 未发现项目级 LICENSE/COPYING，API license=null；有 Inter 字体许可证不能推出全部脚本授权。包和发行版也各有许可 |
| `AndronixOrigin` / [AndronixApp/AndronixOrigin](https://github.com/AndronixApp/AndronixOrigin) | `a909d3654b75464b72c595c8a2ad55e74013263c` | 2026-10-01 15:38:28 / 2026-10-01 15:38:28 | 根 LICENSE 为 MIT；本 revision 是 Go installer + 配置/OCI/兼容性规则，仍需核查捆绑文件和 guest 包 |
| `LinuxDeploy` / [meefik/linuxdeploy](https://github.com/meefik/linuxdeploy) | `d2f1af07a2f4880eae7da0d33b79691716ff9883` | 2021-08-05 13:23:23 / 2022-06-16 23:14:57 | LICENSE/README 声明 GPLv3；默认分支代码较旧，不能以 2022 push 当成现代 Android 兼容保证 |
| `UserLAnd` / [CypherpunkArmory/UserLAnd](https://github.com/CypherpunkArmory/UserLAnd) | `73bac3ac6dcd7259737093a46c2f2109179826d1` | 2026-10-10 03:39:57 / 2026-10-10 03:39:57 | 根 checkout 未见项目级 LICENSE，API license=null；runtime 子模块与发行版 assets 独立，不能假设许可或完整源码可得 |
| `termux-x11` / [termux/termux-x11](https://github.com/termux/termux-x11) | `fa3a8b430e2896a19f44c99a9cb056254615ae06` | 2026-10-06 17:40:03 / 2026-10-12 02:31:15 | 根 LICENSE 为 GPLv3；外部 X server 子模块与原生组件需分别核查；本次仅核查显示职责与使用约束 |

扩展资料没有由本 child clone：

- [limboemu/limbo](https://github.com/limboemu/limbo)：GitHub HEAD `887c6a68cd6b414377d7f8071bc12bbc16e59809`，提交 2022-03-09 17:21:28，API pushed_at 2024-08-18 14:56:31，API 识别 GPL-2.0。读取其 README，未深入或运行 QEMU fork。
- [AOSP packages/modules/Virtualization](https://android.googlesource.com/platform/packages/modules/Virtualization/)：本次官方 `main` endpoint 返回 revision `175a51b30123fa6b02b541f1969665708f7ec2c3`，提交 2025-03-26 20:04:23。以下结论限定于这个官方源码快照；不是声称已覆盖 2026 年所有 Android release。TerminalApp 的 Android.bp 与 AndroidManifest 明确 Apache-2.0，整个 AVF 栈仍有分组件许可。
- 本轮 `gh search repos` 使用 `UserLAnd in:name`、`linuxdeploy in:name`、`limbo android emulator`、`android avf terminal`，后两项初次为空，随后 `limbo emulator in:name,description` 找到原 Limbo 仓库。对 AVF 转用官方 AOSP 文件，没有把空搜索结果解释为项目不存在。

## 3. 运行时分类：明确 PID 1 与 kernel 边界

| 项目 | 真正承担的层 | init / kernel 证据 | 对 Orchestella 的意义 |
| --- | --- | --- | --- |
| Droidspaces | Root 下创建 PID/mount/net/cgroup namespace 的系统容器 | README:11、28 要求 Root/共享 host kernel；`monitor.c:223–251` 在新 PID namespace 中 fork init；`boot.c:433–455` pivot_root 或 recovery fallback，`:612–650` exec guest init | 可研究服务生命周期、DNS ownership 和能力探测；不能作为无需 Root 的实现替换 |
| Linux Deploy | Root chroot 与服务安装管理 | README:7–11；`EnvUtils.java:129–143` 执行 `su`；App 帮助 `res/values/strings.xml:355` 明确先取得 Root | 可借鉴安装流程、服务选择与随机凭据产品行为；未证明它具有独立 kernel 或 namespace PID1 |
| proot-distro / Andronix | ptrace/路径与 UID 转译的无 Root 用户空间 | proot-distro README:1610–1627；`proot_cmd.py:105–110` fake UID/rootfs 与 host `/dev,/proc,/sys` bindings；Andronix `proot.go:79–95,217–238` exec proot + login shell | 发行版管理、下载/归档校验、进度、环境清理值得研究；真实 cgroups、mount、完整 init 能力不能据此承诺 |
| UserLAnd | Android 分发/会话 UI，runtime 和发行版资产分仓 | README:7–9 无 Root；`:24–29` 外部 assets；app/build.gradle:135–145 明确 busybox/proot 等 executable 包装 | PRoot 方向有主仓证据，runtime 子模块缺失，不能宣称完成 init/网络隔离源码验证 |
| Podroid | QEMU/AVF 中运行 guest kernel 的 VM | README:8、33；`init-podroid:134` switch_root 到 `/sbin/init`；`etc/inittab:1–10` 再调用 OpenRC | 与 VM-first 最相关，但单 Alpine 系统的构建/服务契约不直接等于 Orchestella 多发行版基线 |
| Limbo | QEMU 系统级模拟器 | README:1、6–10列 QEMU 与 x86/ARM/PowerPC/Sparc | 说明完整系统模拟的历史覆盖；本轮没有性能、图形和新版 Android 适配结论 |
| 官方 AVF Terminal | 平台特权应用 + AVF VM | TerminalApp Android.bp:35–40 为 framework/platform APIs 与 privileged；manifest:23–31 要求两 VM 权限和 virtualization feature | 官方 Terminal 存在，不能推出任意第三方 APK 都可启动 custom Linux VM；须维持运行时能力检测与 QEMU 回退 |
| Termux:X11 | Android 上的 X server/显示通路 | README:5–8、27、101–124；运行时本身由 Termux/PRoot/chroot 另行提供 | 显示服务器不能替代 Linux runtime/init，也不是终端字符模拟器 |

**Podroid PID1 文案需要纠正**：CREDITS:43–45 同时说 BusyBox 提供 `/sbin/init`、OpenRC 为 PID1；实际 `switch_root /mnt/overlay /sbin/init` + inittab 调用 `/sbin/openrc` 表明预期是 **BusyBox init 占 PID1，OpenRC 编排服务**。本轮没有解析构建后 symlink/运行 `/proc/1/exe`，因此是源码启动链判断。测试时应读取 guest PID1 的实际 exe/comm，不能按鸣谢或进程名列表下结论。[P1][P2][P8]

## 4. 镜像、网络、登录与升级：可借鉴点及具体缺口

### 4.1 Droidspaces 与 rootfs-builder

**镜像基础设施是显式安装的，不是 rootfs archive 自带完整系统的假设**。Ubuntu 24.04 base 安装 bash/coreutils、curl/wget、`ca-certificates`、`locales`、udev、dbus、`systemd-sysv`、systemd-resolved、openssh-server、iproute2、dnsutils，以及开发工具/Docker；Debian 13 类似。locale 实际执行 locale-gen/update-locale，SSH 关闭 root login，DHCP 的 network unit 设置 UseDNS/UseDomains，必要服务显式建 wants symlink。[B1][B2]

这些 template 同时包含共享 Android kernel 的专用修补：AID 网络组、`_apt` 的 primary group、iptables-legacy、journald/udev 行为调整、根据 host/none 模式限制网络管理服务。这些是 Root 容器语境的兼容措施；独立 VM 的 Linux kernel 不应默认继承。`FROM ubuntu:24.04`/`debian:trixie` 加 `apt-get upgrade` 也没有在这些文件中锁定所有包版本；仅锁 Dockerfile revision 不等于可复现镜像。[B1][B2]

**DNS ownership 比硬编码 nameserver 更值得借鉴**。Droidspaces 在 gateway/macvlan 无手动 DNS 时让 DHCP/resolved 管理解析；其余情况由 runtime 写 `/run/droidspaces/resolv.conf`。这样避免绕过 LAN 的 split DNS/adblock。`src/net/network.c:2116–2134` 还描述网络切换监听与 heartbeat；这部分操作的是 Root host 路由，不可直接搬进非 Root App。[D3]

**状态采集也与隔离模型相绑定**。Droidspaces `src/status.c:8–23,210–284` 从 host 侧按 init 的 PID namespace 归属采集 CPU/RSS、读取 uptime/os-release，并进入 netns 获取 IP；内存优先使用 cgroup usage，批量两次采样服务多个容器。可借鉴“按运行实例身份聚合指标、批量采样、注明指标来源”的意图，但 AVF/QEMU guest 进程不在 Android host 的同一 `/proc` 中，需要 guest agent/虚拟化后端提供指标；不能把 host QEMU RSS 冒充 guest 已用内存。[D6]

Android rootfs 下载使用 HTTPS、连接/读取 timeout、可取消流、Content-Length 长度检查与失败清理；但所读 `download()` 没有按可信 manifest 校验下载内容 SHA-256。RootfsRepository 的 SHA-256 是 URL 派生缓存文件名，不是镜像内容验证。不能把“源码中出现 SHA-256”或“下载成功”当成供应链完整性证明。[D4][D5]

### 4.2 Podroid：最相关的 VM 系统链

- **可操作镜像**：build-rootfs 明确安装 Alpine base、OpenRC、BusyBox services、bash、CA、Dropbear/SFTP、Podman/Docker/LXC 及其 kernel/user namespace helpers、网络工具、VNC/PulseAudio/字体，并处理 newuidmap/newgidmap file capabilities 与 sudo/doas mode bits。[P3]
- **服务集合**：OpenRC runlevel 有 migrate/bootstrap/network/resize/dropbear/docker/lxc/x11/vsock/downloads/hostd/ready；只在 init script 存在时建 symlink，避免 phantom dependency。hwclock/networking 等由 initramfs/自有服务取代。[P3]
- **locale 限制**：构建脚本删除 `/usr/share/locale`，包表也不能证明 glibc locales 安装。Alpine/musl 的 UTF-8 行为与 Debian locale-gen 契约不同，不应据这张包表宣称多发行版中文/locale 已验证。[P3]
- **终端登录**：inittab 提供 hvc0/ttyS0 两个 backend 对应 getty；login wrapper 修正被重置为 0×0 的终端大小，然后 exec `/bin/login`。登录与终端 resize 是独立的验收点。[P2][P5]
- **凭据**：镜像写入公开固定密码 `podroid`。随机 salt 只改变哈希值，不能使密码随机；不符合 Orchestella 每实例随机凭据目标。[P3]
- **网络有后端区别**：AVF 使用 virtio NIC + udhcpc，QEMU/SLIRP 使用静态 10.0.2.15 与 10.0.2.2。QEMU 从 kernel cmdline 接收 Android resolver，因为 Android host 没有常规 `/etc/resolv.conf`；源码检查 IPv4 literal、去重并附加公网 DNS。AVF DHCP 失败后仅凭获得 IPv4 地址判成功，不能证明 DNS/TLS/公网访问成功。[P4]
- **存储恢复**：只读 squashfs lower 与持久 ext4 upper 分离，switch_root 交接到真实 root。挂盘失败先 e2fsck；只有 e2fsck 高错误码且读到的首 1 MiB 全零才格式化，否则进入救援 shell。应借鉴“修复前不覆盖已存在数据”的原则；首 1 MiB 全零不是整盘空白证明，不能照搬成通用无损判定。[P1]
- **迁移**：版本 stamp + 按数值排序的幂等脚本、失败停止后续脚本、marker 原子替换，允许下次启动从未完成项继续。实现用 app versionCode 作为 system-version，实际设计可把 App、system image、数据 schema 分别记录。[P3][P6]
- **资产更新**：install timestamp drift 触发重复制；同尺寸不同内容不会被 size check 静默漏掉。单文件采用 `.tmp` + file fsync + rename，全部成功才写总 stamp；但这不是全套 kernel/initrd/rootfs 的事务激活或内容摘要验证。[P7]

**不能宣称上游安全完善或恢复完备的具体例子**：

1. `init-podroid:96–99` 中 normalizer 使用 `|| true`，随后仍写 normalized marker；失败可能不再重试。这是静态控制流风险，没有运行 fault injection。[P1]
2. `podroid-migrate:47–67` 遇失败保留 last_ok，但最后无条件 `eend 0`；不是 fail-closed 服务状态。脚本 `.tmp`+mv 没有体现 durable fsync；“原子可见”与“断电持久”要分开验证。[P6]
3. `podroid-ready:4–6,23–27` 的 `after` 排序与打印 `Ready!` 没有核验 SSH listener、包状态、DNS/CA 或迁移成功。App 看到该文案不能证明系统就绪。[P9]
4. 构建 apk add 使用 `--allow-untrusted`；固定 root 密码、广泛后台服务与 `oom_score_adj=-1000` 也不能当成默认安全/资源策略直接沿用。[P3][P9]
5. `PodroidApplication:147–151` 即使提取失败也释放 assetsReady waiters；每文件 rename 防半文件，但旧、新资产可能混合，需另有 launch gate/整体 manifest。不能把 finally release 等同于验证成功。[P7]

### 4.3 proot-distro：可参考的 OCI、归档和状态树处理

当前版本直接读取 Docker/OCI layers，解析 platform，执行 whiteout，保存 manifest/config，再做 DNS/hosts/Android UID fixups；没有保证 OCI 镜像已经包含 CA、locale、init 或服务。README 明确 mount/root/cgroups/namespaces/完整 systemd/OpenRC 不受支持；`--kernel-release` 改的是报告字符串，不能赋予新 kernel 功能。[R1][R2][R3]

其工程实践对 VM 管理也有价值：layer 在 SHA-256 验证后才进入缓存；网络层拒绝非 HTTP(S)/TLS 降级并给请求 timeout；状态目录的写入经 dirfd/O_NOFOLLOW，使用独占新文件描述符防 symlink 导向 host 路径，tmp 名唯一后 rename，消费者再校验 inode/regular file。`atomic.py` 展示的是命名空间中的原子发布，本次所读实现不能证明断电 fsync 持久性。[R4][R5][R6]

默认 DNS 覆盖为 Google 8.8.8.8/8.8.4.4，与 VPN/局域网 split DNS 可能不一致。backup/restore 是 rootfs + manifest 文件状态，README 明确没有进程内存 live migration；恢复已有目录也不能仅因文件原子写函数存在就声称是全量事务恢复。[R2][R3]

### 4.4 Andronix：多发行版安装阶段与能力探测

该 revision 已是 Go installer；下载、解包、configure、desktop、done 状态以及重新执行幂等 setup 可让用户重复同一命令继续安装。先估计空间，缓存可用文件，OCI layer digest 检查，tarball `.part` 成功后 rename，未完成发行版 update 会要求先 resume install。[A1][A2]

CA 是真实问题：APT 家族 Update 会在更新索引后安装缺失的 ca-certificates；Debian base 列 `sudo nano wget ca-certificates tzdata procps`。但“更新 HTTPS 源前才发现缺 CA”仍会形成 bootstrap 问题，Orchestella 应在镜像基线中带 CA，不依赖首次在线 apt 修复。[A3][A4]

配置先清除 root 密码/SSH host keys/machine-id，安装后随机生成 machine-id；登录采用干净 env，LANG 默认 C.UTF-8。这些身份与环境处理可独立实现，但 LANG 设置不等于相应 locale 已存在。[A5][A6]

Android/PRoot 的兼容规则是条件检测/缓存后应用，不宜按发行版名一刀切。其源码同时禁用 Firefox content/RDD/utility sandbox、提供 bwrap stand-in、修改 APT sandbox/pacman sandbox 等；这些是 PRoot 的能力缺口补救，完整 VM 应让正常 Linux sandbox 工作，不能默认复制削弱防护的配置。[A3][A5]

Andronix 自更新、发行版 package upgrade 与付费 delta 流程彼此区分，未证明所有步骤都有 snapshot rollback。所读 tarball 下载分支只有 `src.sha != ""` 时才比较摘要，却始终显示 `sha256 checked`；因此用户提示不能替代必需的可信 checksum 检查。[A1][A2]

### 4.5 扩展覆盖：UserLAnd、Linux Deploy、Limbo、官方 Terminal 与 X11

- UserLAnd 把 App/runtime library/support assets/distro assets 分仓。`git submodule status` 显示未初始化的 `UserLAndLibrary` pin `c558ff293b4b0eb9380ac75ccc0854ad659bd220`；本轮对该固定对象的公开 API 为 404。主 App 明确 native extraction 用于 busybox/proot 等可执行文件；这可以证明主仓依赖 PRoot，却不能证明子模块的服务、PID1、恢复或许可设计。[U1][U2]
- Linux Deploy README 除 Root chroot 也说明在线镜像 bootstrap、SSH/VNC/桌面准备，首次自动生成密码。可借鉴“配置展示、服务管理、随机凭据”意图；Root/mount/System BusyBox/SuperSU 等做法不属于 Orchestella 的无需 Root 路线。[L1][L2]
- Limbo 官方 README 只支持本轮确认“QEMU 系统模拟、多架构”；不对其现代 GPU、AVF、镜像预装包与迁移作推断。[M1]
- 官方 Terminal 是 privileged 平台 App，要求 MANAGE_VIRTUAL_MACHINE/USE_CUSTOM_VIRTUAL_MACHINE/virtualization feature，manifest 中还可见 Recovery/Upgrade/DiskResize 页面。官方 custom VM 文档的 shell 示例需要 rooted AVF-enabled device；这描述调试工具，不代表 Terminal 用户必须 Root，也不代表一般 App 自动拥有 custom VM 能力。保护 VM 与普通 custom Linux VM 的权限/载荷契约须分别验证。[F1][F2][F3]
- Termux:X11 是 X server + companion package。PRoot 连接依赖共享 tmp/X socket；chroot 还涉及 mount namespace、SELinux、XKB_CONFIG_ROOT。其 README 的 `setenforce 0` 是 Root chroot 示例，不是 Orchestella 的产品建议。VM 与 host 不能自然共享同一个 Unix socket，应按已有传输/图形契约独立设计。[X1]

## 5. 原创实现可采用的组合与优先级

以下为基于上述外部事实的**研究建议**，不是已验证实现，也不是要求临时改变 accepted architecture。

| 优先级 / 可实践方向 | 可借鉴原理 | Orchestella 可独立实现的结果 | 必须避免的直接复制 |
| --- | --- | --- | --- |
| P0：Guest 基线与能力清单 | builder 显式装 init/DBus/CA/locale；Podroid 服务依赖 | 每发行版定义 init 实现与 `/sbin/init` 契约，离线核验 mandatory packages、CA bundle、UTF-8 locale、shell/login、DHCP/iproute2、所需服务；启动后确认 PID1 与服务状态 | 不把普通 OCI/minirootfs 等同完整系统；不搬 AID group/PRoot fake kernel/sandbox-disable |
| P0：分层就绪状态 | Podroid init→network→getty→ready 顺序，及其薄弱点 | 分别报告 image-valid、init-ready、network-configured、login-ready；网络离线能进入终端并显示状态；必需设施失败阻断“完整安装成功”，可选桌面失败单独记录 | 不靠 `Ready!` 文案、包命令 exit=0 或文件存在就宣称完成 |
| P0：可信输入和失败关闭 | PRoot digest/安全解包；Podroid file publication | 固定来源/manifest/大小/SHA-256，定点安全解包，输出元数据与来源关联；未核验不得切换 active；守住本项目下载/出站边界 | 不使用仅 URL hash、同源临时 checksum 作为独立来源认证；不盲随动态 endpoint，不引入后台上传 |
| P0：每实例身份与登录 | Andronix scrub + machine-id；Linux Deploy 随机密码 | 镜像不含共享 host key/凭据；实例首次启动生成身份/凭据，登录与 SSH 启用后分别验收；终端断开不终止 VM 的必要服务 | 不沿用 `root/podroid`，不把随机 salt 当随机密码，不默认开放 LAN SSH |
| P1：网络 provider 与 resolver ownership | AVF DHCP vs QEMU/SLIRP；Droidspaces LAN DNS ownership | 各后端产出同一网络状态对象；按设备/VPN/DHCP 决策 DNS，支持网络变化；区分路由、DNS、TLS、package mirror 错误 | 不把 SLIRP 地址硬套 AVF，不静默绕过 VPN/LAN DNS，不把 IPv4 addr 当网络验收 |
| P1：可恢复安装与升级 | Andronix 阶段 resume；Podroid 幂等 migration | 安装阶段与输入 digest 对应；App/system/schema 三个版本独立；迁移完成逐项 durable marker、失败停必需依赖、重入可恢复；整体镜像激活与回退受 manifest 管理 | 不 normalize失败仍写marker，不 migrate失败仍报成功，不把所有资产独立rename当整体事务 |
| P1：存储救援与保护 | Podroid lower/upper 分离、fsck后救援 | 保留只读系统与持久用户状态分层思想；检测已有数据时优先只读诊断/导出/救援，显式初始化才能格式化；新增 lower 前评估 upper覆盖/whiteout与包数据库漂移 | 不仅凭首1MiB全零格式化，不假设换lower自动消除upper中旧系统文件 |
| P2：兼容性与扩展包 | Andronix 能力probe + 发行版family | 对 kernel、图形、服务和包能力测量；将开发工具/容器/桌面作为可选包 profile，保留基线小而完整 | 不默认预装并启动 Docker/LXC/X11/PulseAudio全部服务；不把PRoot专用shim永久写入VM |
| P2：终端会话契约 | Podroid getty/winsize + backend adapter | 字符渲染、PTY/console transport、登录、安全凭据、resize、session恢复分别建契约；两后端一致行为可用独立协议实现 | 不因两个产品采用Termux就直接复制terminal modules；不把X11 server当字符终端 |

这些组合的新价值在于：以 VM guest 的真实状态与可恢复事务驱动原生 App，而非把别人的脚本拼起来。实现同类功能不需要复制其表达、源码布局或专有补丁；任何实际引入第三方组件仍需精确 revision、license、notice、source offer/对应源码与分发关系审查。

## 6. 许可证和终端复用的具体边界

Droidspaces README:105–108 署名 ReTerminal、Termux、LXC-Manager 为 terminal backend；项目自身 GPLv3。Podroid 当前 Gradle **直接依赖本地 `:terminal-emulator` 与 `:terminal-view`**，注释说来自 `MatanZ/termux-app:sixel4`；而 CREDITS:93–97 仍写 JitPack `0.118.1` 与 GPLv3。这是具体来源/许可记录漂移，不能据鸣谢推断实际 vendored fork 的授权，也不能把“GPLv2 根仓库”当作免审 Termux 来源的理由。[D1][P8][P10]

本任务没有读取 Termux app 或 Podroid vendored terminal 模块。最终 license 判定、API 复用范围和源代码义务以另一研究任务的逐文件结果为准。原理上，GPL代码可以在满足相应许可证和兼容分发义务后使用；本报告说“不能直接复制”，指的是不能无许可审查、无来源记录地粘贴进原创 VM 产品或声称原创，也不能因改名/改语言就当然摆脱派生义务。MIT 代码通常仍需保留许可/署名；没有项目级许可的 builder/UserLAnd 文件不自动获得可复用授权。发行版 image、kernel/QEMU、字体和显示组件还需要各自的包/组件许可记录。

## 7. 下一轮验证清单（全部未运行）

1. 镜像基线检查：逐发行版确认 init symlink/exe、mandatory package 状态、CA bundle、可用 UTF-8 locale、登录 shell、DBus/SSH/桌面相关 unit；以合成离线 fixture 验证缺包/缺证书/缺locale不会被写成安装成功。
2. 启动检查：AVF/QEMU 分别观察实际 `/proc/1/exe`、服务失败、getty/login、中文输出与终端 resize；shell可开、网络离线、必需服务失败应是不同状态。
3. 网络检查：Wi-Fi/蜂窝/VPN/split DNS/IPv6-only/断网恢复；分别验证路由、解析、TLS和package mirror，不依赖公有网站一次curl作为唯一依据。
4. 更新恢复检查：同尺寸新镜像、磁盘满、取消、崩溃/断电模拟、失败migration、normalizer错误、旧upper覆盖新lower；验证 active manifest和marker不提前推进，失败后可以重启/救援/回退。
5. 许可检查：精确 terminal fork revision与license/notice；builder/Image bundle包清单；所有实际分发二进制的对应源码与构建出处；不把本报告当作许可验收。

## 8. 可追溯关键源码索引

以下行号均为 GitHub/Gitiles 的 **1-based** 行号，并固定在本次审阅 revision。引用范围仅支持相应事实，不声称整文件/整个项目已安全审计。

| 编号 | 固定源码 / 关键行号 | 支持的事实 |
| --- | --- | --- |
| D1 | [Droidspaces README:11–34,105–112](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/README.md#L11) | Root、host kernel、终端署名/GPL |
| D2 | [src/monitor.c:223–251](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/src/monitor.c#L223)；[src/boot.c:433–455,612–650](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/src/boot.c#L433) | PID namespace、pivot_root、exec init |
| D3 | [src/net/network.c:1998–2113,2116–2134](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/src/net/network.c#L1998) | DHCP/resolved/runtime DNS ownership、Root路由监测 |
| D4 | [RootfsDownloadService.kt:156–204](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/Android/app/src/main/java/com/droidspaces/app/service/RootfsDownloadService.kt#L156) | HTTPS/timeout/长度检查/取消与失败清理 |
| D5 | [RootfsRepository.kt:19–41,124–133](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/Android/app/src/main/java/com/droidspaces/app/util/RootfsRepository.kt#L19) | size字段、URL hash文件名，不能替代内容摘要 |
| D6 | [src/status.c:8–23,210–284](https://github.com/ravindu644/Droidspaces-OSS/blob/c2d27d2db77dcfa27f16926b450c0eff89429d3b/src/status.c#L8) | namespace归属的批量指标、cgroup内存与VM指标边界 |
| B1 | [Ubuntu-24.04-base.Dockerfile:3–9,32–50,128–154,160–206,235–246](https://github.com/Droidspaces/Droidspaces-rootfs-builder/blob/b60492d1b2e78f343789f2c94de23b831d1728a9/templates/aarch64/Ubuntu-24.04-base.Dockerfile#L32) | mandatory包/locale/SSH/DHCP/服务/共享kernel修补 |
| B2 | [Debian-13-base.Dockerfile:3–10,24–40,121–145,151–161,192–199](https://github.com/Droidspaces/Droidspaces-rootfs-builder/blob/b60492d1b2e78f343789f2c94de23b831d1728a9/templates/aarch64/Debian-13-base.Dockerfile#L24) | Debian基线与Android group/service规则 |
| P1 | [init-podroid:38–108,110–134](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/init-podroid#L38) | ext4/squashfs/overlay、fsck、normalizer marker、switch_root |
| P2 | [etc/inittab:1–10](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/files/etc/inittab#L1) | init调用OpenRC、backend getty、shutdown |
| P3 | [build-rootfs.sh:16–50,54–92,138–152,189–205](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/build-rootfs.sh#L16) | 包、mode/capability、固定密码、locale删减、版本stamp、服务 |
| P4 | [podroid-network:9–79,81–155](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/files/etc/init.d/podroid-network#L9) | DNS grammar、AVF DHCP、QEMU static网络与device DNS |
| P5 | [podroid-login:1–23](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/files/usr/local/bin/podroid-login#L1) | winsize修复、真实/bin/login |
| P6 | [podroid-migrate:11–67](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/files/etc/init.d/podroid-migrate#L11) | 幂等按序迁移、last_ok、原子marker、失败后eend 0 |
| P7 | [PodroidApplication.kt:75–151,167–235](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/app/src/main/java/com/excp/podroid/PodroidApplication.kt#L75) | stamp/并行asset、finally释放waiter、fsync+rename |
| P8 | [CREDITS.md:43–45,93–97,145–146](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/CREDITS.md#L43) | PID1文案矛盾、旧terminal依赖声明、项目许可声明 |
| P9 | [podroid-ready:4–27](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-rootfs/files/etc/init.d/podroid-ready#L4) | after顺序、OOM保护、无探测Ready文案 |
| P10 | [app/build.gradle.kts:136–143](https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/app/build.gradle.kts#L136) | 实际vendored terminal modules及HiddenApiBypass依赖 |
| R1 | [README:1–14,1610–1664](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/README.md#L1610) | 新Python/OCI架构、PRoot能力限制、没有live state迁移 |
| R2 | [README:1430–1466](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/README.md#L1430) | OCI layers/DNS/Android UID初始化 |
| R3 | [commands/login/proot_cmd.py:105–159,170–217](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/proot_distro/commands/login/proot_cmd.py#L105) | fake UID/host binds、kill-on-exit、fake kernel/shared-tmp |
| R4 | [helpers/docker/layers.py:45–105](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/proot_distro/helpers/docker/layers.py#L45) | layer SHA-256后发布 |
| R5 | [helpers/download.py:25–34,87–107,143–168,242–261](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/proot_distro/helpers/download.py#L25) | timeout、截断body检查、redirect scheme/TLS规则 |
| R6 | [atomic.py:93–127,187–240](https://github.com/termux/proot-distro/blob/5a427a1f924550c176ab34197e9fcf5d010fdbd9/proot_distro/atomic.py#L93) | dirfd/O_NOFOLLOW、unique tmp fd、inode核验与原子rename |
| A1 | [internal/app/install.go:202–221,284–293,347–391,393–444,460–479](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/internal/app/install.go#L202) | 安装stage、空间预算、条件checksum、resume setup |
| A2 | [internal/app/update.go:40–47,61–64,110–138,154–168](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/internal/app/update.go#L40) | self/package/delta更新分工，未完成安装拒绝update |
| A3 | [internal/pkgmgr/pkgmgr.go:117–139,193–205,233–240](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/internal/pkgmgr/pkgmgr.go#L117) | CA bootstrap、PRoot package sandbox/shim |
| A4 | [distros/debian.conf:22](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/distros/debian.conf#L22) | Debian base包配置 |
| A5 | [internal/rootfs/configure.go:43–66,81–113,139–157,175–185](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/internal/rootfs/configure.go#L43) | DNS、随机machine-id、清身份密码、Firefox/bwrap兼容 |
| A6 | [internal/proot/proot.go:79–95,176–213,217–238](https://github.com/AndronixApp/AndronixOrigin/blob/a909d3654b75464b72c595c8a2ad55e74013263c/internal/proot/proot.go#L79) | capability flag/args、干净env、LANG、login |
| L1 | [LinuxDeploy README:7–11](https://github.com/meefik/linuxdeploy/blob/d2f1af07a2f4880eae7da0d33b79691716ff9883/README.md#L7) | chroot/Root、在线安装、SSH/VNC随机密码 |
| L2 | [EnvUtils.java:129–143](https://github.com/meefik/linuxdeploy/blob/d2f1af07a2f4880eae7da0d33b79691716ff9883/app/src/main/java/ru/meefik/linuxdeploy/EnvUtils.java#L129) | 真实su检测 |
| U1 | [UserLAnd README:7–9,24–29](https://github.com/CypherpunkArmory/UserLAnd/blob/73bac3ac6dcd7259737093a46c2f2109179826d1/README.md#L7)；[.gitmodules:1–3](https://github.com/CypherpunkArmory/UserLAnd/blob/73bac3ac6dcd7259737093a46c2f2109179826d1/.gitmodules#L1) | 无Root、assets分仓、runtime子模块 |
| U2 | [app/build.gradle:135–145](https://github.com/CypherpunkArmory/UserLAnd/blob/73bac3ac6dcd7259737093a46c2f2109179826d1/app/build.gradle#L135) | busybox/proot可执行文件的native extraction约束 |
| M1 | [Limbo README:1–13](https://github.com/limboemu/limbo/blob/887c6a68cd6b414377d7f8071bc12bbc16e59809/README.md#L1) | QEMU、多系统架构覆盖 |
| F1 | [TerminalApp Android.bp:1–3,35–40](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/TerminalApp/Android.bp#35) | Apache-2.0、framework/platform/privileged |
| F2 | [TerminalApp AndroidManifest.xml:23–31,59–69](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/TerminalApp/AndroidManifest.xml#23) | custom VM权限/feature、恢复/升级/resize UI |
| F3 | [docs/custom_vm.md:5–23,28–35](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/docs/custom_vm.md#5)；[docs/getting_started.md:5–32,39–43](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/docs/getting_started.md#5) | 调试命令与设备/保护VM/APEX签名边界，仅限该快照 |
| X1 | [Termux:X11 README:5–8,27–31,101–134](https://github.com/termux/termux-x11/blob/fa3a8b430e2896a19f44c99a9cb056254615ae06/README.md#L101) | 显示职责、PRoot tmp/socket、chroot namespace/SELinux条件 |

原仓库 metadata 来源为上述各仓库的 GitHub `repos/<owner>/<repo>` API，固定 revision 提交日期以本地 HEAD 或 `commits/HEAD` 为准；license 来源同时包括对应固定 HEAD 的根 LICENSE 与已引用文件许可头。所有维护信息是本次读取快照，不能替代后续升级时的重新锁定与审查。
