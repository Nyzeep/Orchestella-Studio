# Phase 5 研究：VM guest 中的 rootless 容器与本地开发服务

- 触发工单：`.scratch/orchestella-phase5/issues/03-rootless-productivity.md`；日期：2026-10-13。
- 执行者：productivity-research supporting child / Codex，亲自研究，没有派生子代理。
- 隔离工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`。
- 分支：`phase5/graphics-productivity-storage`；基线 commit：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。新增 commit：无；push / PR / release：无。
- 正式输出仅本文件。原始证据：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/B/evidence`，下文简称 **E**。协调者追加授权的下载输入位于同批 `B/inputs`，简称 **I**。
- 状态用语：**已核对**表示静态源码、公开 metadata 或实际下载校验；**NOT_RUN / 未运行**表示没有在真实 guest 执行。二者不能替代。

## 1. 问题与结论

1. **这是完整 Linux VM 内的 rootless 容器。** Android 宿主免 Root 与 guest 内一次性管理员安装是不同权限层。Podman 日常进程必须以普通 guest 用户运行；容器 UID 0 映射到该用户或其 subordinate IDs。PRoot、Android Root namespace/chroot 容器的限制与绕过办法不适用于这里。容器“host”在上游文档中指本项目的 Linux guest，不是 Android。
2. **生产 Image 已满足 77 项既有 kernel required 配置，但不能据此宣布全部容器网络可用。** 本研究从活动主仓 packaged `Image` 静态解出 IKCONFIG，逐项比对通过；真实缺口是 nftables MASQUERADE/FIB 等表达式，以及部分 x_tables 仅为未保证交付的模块。先验收单容器 `pasta` 或适用于已安装版本的 `slirp4netns`；Netavark bridge、容器名称 DNS 与 Docker 默认 bridge 单列门。
3. **Docker CLI、Podman Docker 兼容接口、rootless Docker daemon 必须分开。** CLI 存在或 `docker --version` 成功，不证明有 Docker engine。`podman-docker` 是兼容 wrapper；Podman Unix API 不提供全部 Docker daemon 语义。首版可以只安装真正的 CLI；rootless daemon 需要另外配置 RootlessKit、UID 映射、user systemd 和网络/storage，不能附带静默启用。
4. **首选 code-server 固定官方 ARM64 standalone tar。** `4.141.0` 已实际下载并校验 234,587,826 字节、SHA-256；内含 Node ELF 为 AArch64，包版本与 MIT 字段吻合。它是可审计的固定发布输入，本研究没有完成上游签名验证，不能称“已验签”。Alpine/musl 不能直接复用 glibc standalone。
5. **官方 Microsoft VS Code Server、code-server、OpenVSCode Server 是三种分发和认证模型。** Microsoft 产品许可不能用 VS Code 源码 MIT 代替；`code tunnel` 要求外部账户并经 Azure 服务。SSH 路径不能笼统说成也必须云登录。本任务不采用云 tunnel。OpenVSCode 提供 MIT ARM64 发行，但文档与源码对默认 token 有差异，实施必须显式设置 token-file。
6. **最小服务数据面：guest loopback 服务 → 显式 guest NIC relay → 既有 gvproxy API 确认的 Android loopback 转发 → 精确 origin 的客户端。** 活动主仓当前只支持 TCP loopback 转发，尚不能承诺 LAN。多实例 WebView 共享 `127.0.0.1` CookieManager 存在跨端口 cookie 边界，单凭不同端口不是认证隔离。

## 2. 治理、读取与数据边界

已读取隔离目录 `AGENTS.md`、派发报告和本工单；隔离 worktree 缺少 ignored/untracked 治理资料，因此按协调者说明，从只读活动主仓 `Orchestella_dev` 读取 `AGENTS.md`、`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及目录两份 policy，以及 HOST、ARTIFACT、ARCHITECTURE、ASSET、REPORTING 五份政策、ADR-0005/0008/0009/0010/0011 和 `docs/research/2026-10-13-guest-runtime-audit.md`。没有复制或修改这些文件。

`research` Skill 调用返回用户级/项目级同名歧义，随后直接读取总目录 `.agents/skills/research/SKILL.md`，执行其中 primary-source、单文件归档要求。其后台代理要求由任务“亲自研究、不再派生”覆盖。没有调用 workflow 或新代理。

读取限相关源码、政策、公开参考、明确 kernel 资产和已留存发行版官方索引。联网仅公开 GitHub/raw、Podman/Coder/Microsoft/Docker 官方文档、官方 Ubuntu 包索引及公开 OCI registry metadata/发行输入。没有上传宿主源码、日志、Git 历史、路径清单或用户数据；没有读取 `.env`、宿主凭据、密钥、实例根盘或设备数据。Docker registry 公共只读 pull token 仅在请求进程内存中使用，没有写文件、日志或报告，没有创建外部账户。

没有 adb、真机、QEMU/AVF 启动、宿主 daemon、系统安装、WSL/防火墙/PATH/注册表/服务变更。没有手动写 C:。Class A 原件只读保全；未做清理。主仓与设备仍由第一会话独占。活动主仓未提交文件可能继续变化，本报告的静态结论以读取时内容为准，不替代协调者后续接口冻结。

## 3. 官方参考目录、revision、许可与源码定位

总目录 `D:/Development project(开发项目)/Orchestella开发目录/phase5-references/` 下八个目标在创建前均不存在。先核对官方项目入口与许可，再全部使用 `git clone --depth=1 --filter=blob:none --sparse`，只获取必要源码/文档目录；没有 submodule 初始化、源码构建或安装。超过“至少三项必要项目”的要求。新建参考以固定 checkout revision 只读保留，今后已有目录不 fetch/pull/reset/update。

| 本地目录名 | 官方仓库与本次 checkout revision | 已核对许可与关键 source 位置 |
| --- | --- | --- |
| podman | [podman-container-tools/podman](https://github.com/podman-container-tools/podman)，`b17e7db1d3ad0c6ca56e292aa95273facb80f8ad` | Apache-2.0；`LICENSE:1–3`；`docs/tutorials/rootless_tutorial.md:18–59,169–171`；`rootless.md:15–31` |
| common | [containers/common](https://github.com/containers/common)，`a5ccdae846b629b5ceaefa6ffd5c6511409c3487` | Apache-2.0；`LICENSE:1–3`；`README.md:3–5` 明确已迁移到 containers/container-libs；`docs/containers.conf.5.md:369–373,493–516,782–796` |
| netavark | [containers/netavark](https://github.com/containers/netavark)，`e32aa54b9004f8a914e58b7e3b2d9d290f9dfca5` | Apache-2.0；`Cargo.toml:3–6` 为 2.1.0；`src/firewall/mod.rs:42–73,95–118`；`src/firewall/nft.rs:122–160,200–217,434–477` |
| aardvark-dns | [containers/aardvark-dns](https://github.com/containers/aardvark-dns)，`0443fbeed17ee45e209ef36f8fd26a94846ed261` | Apache-2.0；`LICENSE:1–3`；`README.md:3–8`，A/AAAA 容器记录与 upstream forwarding |
| rootlesskit | [rootless-containers/rootlesskit](https://github.com/rootless-containers/rootlesskit)，`c75b53802b2247955e53a9c07290a6d25634c5eb` | Apache-2.0；`LICENSE:2–4`；`README.md:23–31,74–91,174–177` |
| moby-cli | [docker/cli](https://github.com/docker/cli)，`e4078058de0a9f4d5cb65f946f4b8a090e19fffe` | Apache-2.0；`LICENSE:2–4`；`README.md:1–11,94–95`。该目录名不改变它仅是 CLI 的事实 |
| code-server | [coder/code-server](https://github.com/coder/code-server)，`41cf6f7eaffd34eed01a985b6e843e09b0f98ed7` | MIT；`LICENSE:1–21`；`docs/install.md:93–123,149–170`；实际采用发行版另锁 `8b41915…`，见 §7 |
| openvscode-server | [gitpod-io/openvscode-server](https://github.com/gitpod-io/openvscode-server)，`2bfb814c5215c51a10e80c2cb1b58ed91068ad8b` | MIT；`LICENSE.txt:1–21`；`README.md:90–104`；`src/vs/server/node/serverConnectionToken.ts:50–89` |

精确命令、目标不存在事实、stderr、revision 与许可证文件 SHA：E 的 `sparse-clones-batch1.json`、`sparse-clones-batch2.json` 及字节一致 preserved 副本。Podman 官网现指向新组织，旧 `containers/podman` 链接的重定向不应当作不同实现。common 的旧仓库是历史配置参考，不冒充新版本唯一维护源。

上述许可证是各项目顶层许可，不是整个依赖闭包的统一许可。实际分发还需保留 Linux GPL source offer，以及 OCI runtime、FUSE、Node、VS Code、npm/native modules 和扩展各自的 copyright/notice。MIT 支持再分发，但不授予 Microsoft 商标、专有扩展或 Marketplace 使用权。

## 4. guest kernel 的实际静态检查

### 4.1 检查对象与强度

本次读取活动主仓 `app/src/main/assets/Image`，大小 **52,070,912** 字节，SHA-256 **`d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5`**，与同目录 `runtime-artifacts-v1.sha256:1` 一致。按 `IKCFG_ST`/`IKCFG_ED` 标记在偏移 18,789,944 / 18,859,667 之间静态解出 gzip config，共 12,023 行；config SHA-256 **`2f5e06160ebe8393e3d903c3d95b2fb55e5d84dcb017cdd8ba3b68b11d757b03`**。

解出 config 与既有 `Orchestella/artifacts/25/ticket25-reproducible-qemu-2026-09-21-main-8859234a7cd922d4/kernel.config` 字节完全一致。按隔离目录 `guest-assets/kernel/required.config` 的 **77 条 `CONFIG_…=y`** 精确比对，缺项 **0**。这是对实际 packaged 二进制的静态检查，不是仅检查 fragment 意图，也不是启动了 guest。

版本输入以项目 `tools/qemu-android/ci/guest-assets-inputs.lock:7–9` 和既有 `BUILD-INPUTS:5–6` 的 Linux **7.2.3** / source SHA `8ba259e8e7b13ec6ef0941c8a39ad90b24bd4a4d6c0010ba6bafb794550ecd03` 为准。本次没有升级 kernel、运行 build 或另行验证上游发布状态。旧资产政策中“Linux 6.6”文字不能覆盖当前锁线事实。

证据：E `packaged-kernel-static-check.json`、`packaged-kernel.config.txt`、`guest-kernel-static-check.json`。相关生产文件 hash 记录于 `local-readonly-source-revisions.json`，未复制整仓。

### 4.2 已具备和真实缺口

下表行号为提取后 `packaged-kernel.config.txt` 的原始 config 行号。

| 能力 | 实际 config / 行号 | 能推出什么；不能推出什么 |
| --- | --- | --- |
| userns / namespaces | `NAMESPACES=y:208`，`USER_NS=y:213`，`NET_NS=y:215`；其他 namespace 已过 required | 有内核实现；仍需 guest sysctl、LSM 与普通用户实际 `unshare` 成功 |
| cgroups | `CGROUPS=y:180`，`MEMCG=y:183`，`BLK_CGROUP=y:185`，`CGROUP_SCHED=y:187`，`FAIR_GROUP_SCHED=y:190`，`CFS_BANDWIDTH=y:191`，`CGROUP_PIDS=y:194`，`CGROUP_BPF=y:204` | 编译能力具备；cgroup2 挂载、user manager、controller delegation、实际资源限制未由静态 config 证明 |
| seccomp | `SECCOMP=y:837`、`SECCOMP_FILTER=y:838` | 内核具备过滤器；仍需 libseccomp/OCI runtime、有效 profile 和拒绝 syscall 的运行证据 |
| storage | `FUSE_FS=y:10988`、`OVERLAY_FS=y:10993`、ext4 已过 required | 满足候选实现；native overlay 挂载、`/dev/fuse` 权限与 helper 可执行仍未运行 |
| 单容器用户态网络 | `TUN=y:2741`、`VETH=y:2744`、`BRIDGE=y:1468` | pasta/slirp 使用的 namespace/TUN 候选能力存在；没有证明工具已安装、DNS 或 TCP 可用 |
| nft 基础 | `NF_CONNTRACK=y:1238`、`NF_NAT=y:1259`、`NF_TABLES=y:1260`、`NF_TABLES_INET=y:1261`、`NFT_CT=y:1264`、`NFT_NAT=y:1271` | 基础并不涵盖所有 NAT/firewall 表达式 |
| nft bridge 缺口 | **`NFT_MASQ` 未设:1269**；**`NFT_FIB_IPV4` 未设:1419**、IPv6:1439；`NFT_REJECT` 未设:1274 | Netavark 2.1 的 nft 代码明确构造 masquerade 与 fib local-type，当前不能保证该路径；reject 不是本次已经证明必用的表达式，按实际版本补验 |
| iptables/x_tables | `NETFILTER_XTABLES=m:1282`、`XT_MATCH_ADDRTYPE=m:1316`、`XT_MATCH_CONNTRACK=m:1325`、`IP_NF_IPTABLES=m:1425`；`XT_NAT` 未设:1303、`XT_TARGET_MASQUERADE` 未设:1309、`XTABLES_LEGACY` 未设:1284 | 不能当作完整默认 Docker/旧 Netavark bridge 条件；还需 matching modules 交付与加载 |
| AppArmor / checkpoint | `SECURITY_APPARMOR` 未设:11231；`CHECKPOINT_RESTORE` 未设:216 | 不承诺 AppArmor container confinement 或 CRIU checkpoint；seccomp 与 UID 隔离是另外的能力 |

构建路径 `guest-assets/build.sh:69–104` 是 defconfig → fragment merge → olddefconfig → required verifier → `make Image`；`310–311` 复制 Image/config。没有在该构建路径交付完整 `modules_install` 结果，因此不能把 `=m` 当作 built-in 或保证真实 guest 内有 matching module。发行版自带不同 kernel 的 `/lib/modules` 也不能自动与项目 Image 匹配。

为判断缺项，本研究从既有 Class A `linux-7.2.3.tar.xz` **只读提取三份 Kconfig** 到 E，没有补全/修改 partial source tree。对应 `net/netfilter/Kconfig:532–549` 的 NFT_MASQ、`605–627` 的 NFT_FIB/INET，以及 `net/ipv4/netfilter/Kconfig:54–62` 的 FIB_IPV4 解释依赖。证据为 `kernel-netfilter-sources.json` 和三个 `*-Kconfig.source.txt`。

**最小建议**：当前 kernel 先保留；单容器用户态网络实测独立推进。若需要 Netavark nft bridge，kernel owner 应明确加入 `NFT_MASQ`、`NFT_FIB_IPV4`/`NFT_FIB_INET` 及依赖，按实际所用网络版本增加 required gate；新 Image/hash 必须走既有显式迁移与验证。旧 iptables 路径则需要相匹配的完整 netfilter/模块交付，不能通过 `--privileged`、关 seccomp 或设 `firewall_driver=none` 掩盖。

### 4.3 普通用户运行的权限前提

- guest 管理员一次性安装 Podman、conmon、OCI runtime、UID mapping helpers、网络工具和 storage helper，并创建普通开发用户。subuid/subgid 分配至少一个不重叠的 65,536-ID 范围；例如 `developer:100000:65536` 仅是示例，实际要检查 guest 现有账户和分配，不能无条件追加。新增/改动映射后的 `podman system migrate` 只在用户明确停容器时进行。
- `newuidmap`/`newgidmap` 的受控 setuid 或 file capability 来源须为发行版签名包；rootless Podman 自身不是 setuid。核验文件权限、nosuid 挂载和 helper 实际行为。不要在其父服务套 `NoNewPrivileges=yes`、`RestrictNamespaces=yes` 等导致 helper 或 userns 失效的约束后，降为 guest root 运行来假称 rootless。
- 普通用户实测 `unshare -Ur true`、`podman unshare cat /proc/self/uid_map`/`gid_map`。读取 `user.max_user_namespaces`；`kernel.unprivileged_userns_clone` 是某些发行版提供的开关，缺此 sysctl 不等于内核没有 userns。Ubuntu AppArmor 对 unprivileged userns 的文档限制要按实际自有 guest kernel 判定；本 Image 未编译 AppArmor，不能照搬启用该 LSM 的 Ubuntu kernel 结论。
- 数据放 guest 本地 ext4 用户目录，如 `~/.local/share/containers/storage`；运行态放受 logind 管理、用户专属 0700 的 `/run/user/<uid>`。禁止将 graphroot 放 Android 只读 Downloads/9p/NFS、宿主目录或共享系统 socket。
- 普通用户的 cgroup2 resource control 需要真实发行版 PID1、systemd user manager 与 delegation。仅 `User=developer` 的 system unit 或手工 `su` 不自动获得完整登录会话和 user bus。OpenRC 可研究 daemonless Podman，但不能共用 systemd/Quadlet、cgroup controller 已验证标签。

来源：[Podman rootless tutorial 固定 checkout](https://github.com/podman-container-tools/podman/blob/b17e7db1d3ad0c6ca56e292aa95273facb80f8ad/docs/tutorials/rootless_tutorial.md#L18-L59)，`169–171`；[RootlessKit README](https://github.com/rootless-containers/rootlesskit/blob/c75b53802b2247955e53a9c07290a6d25634c5eb/README.md#L74-L91)；[Docker rootless](https://docs.docker.com/engine/security/rootless/)、[troubleshoot](https://docs.docker.com/engine/security/rootless/troubleshoot/)。这些软件安装与 sysctl 检查在本研究均 **NOT_RUN**。

## 5. Podman 网络、storage、DNS 与七族 ARM64 供应

### 5.1 不混淆三层网络

Android↔guest 的 gvproxy/AVF 网络、guest↔rootless container 的 pasta/slirp、容器间 bridge/Netavark 是三层机制。guest 有 DNS 不能证明 container 有 DNS；安装 aardvark 也不能证明已创建 DNS-enabled 网络。

- **pasta**：Podman 5.x 起通常作为 rootless 默认，新 Podman 源码将 pasta 作为必要工具；实际安装版本仍须核对。它在 guest 的用户命名空间内建立用户态网络，不需要 Android 的系统 TUN/VPN 权限。上游 IPv6 支持不扩大 ADR-0008 的产品 IPv6 承诺。避免自动 publish 全部端口、`--map-gw` 等扩大 guest host 可达性的便利选项。
- **slirp4netns**：Ubuntu 24.04 的 Podman 4.9 等稳定包可显式选用；保持 `allow_host_loopback=false`。它的 libslirp 与旧 QEMU hostfwd 概念不能拿来描述当前活动 App 的 gvproxy 数据面。新 Podman 的源码/包可能移除该路径，不能写全版本统一 fallback。
- **Netavark**：负责接口、bridge、NAT/port forwarding，与 aardvark 协作。checkout 的 2.1.0 `src/firewall/mod.rs:42–73` 仅含 nftables/firewalld/none，不能把旧 common 文档列出的 iptables driver 当成新版本能力。当前缺 MASQ/FIB 的 Image 不应被列为 Netavark bridge ready。
- **aardvark-dns**：管理 A/AAAA 容器名称并转发其他请求。单独 pasta/slirp 网络使用各自 resolver 机制；仅在 DNS-enabled Netavark network 上验容器 alias/name resolution。解析器 upstream、guest `/etc/resolv.conf`、stub resolver 和 nested NAT 均要实测。不得向用户承诺 Android 系统 DNS 自动可用。
- **native overlay / fuse-overlayfs**：Podman 文档给 rootless native overlay ≥5.12/5.12.9 条件；本 Image 的 pinned 版本高于该最低值，但仍需 ext4/xattr/用户命名空间真实 mount。`fuse-overlayfs` 是可选兼容路径，要有 FUSE/helper 权限。已有 storage.conf 或存储格式不能静默切换。VFS 可作为显式低性能降级，禁止 `ignore_chown_errors` 单 UID 扁平化代替正确映射。

来源：[rootless limitations](https://github.com/podman-container-tools/podman/blob/b17e7db1d3ad0c6ca56e292aa95273facb80f8ad/rootless.md#L4-L31)；[稳定 Podman v5.7.1 network options](https://github.com/podman-container-tools/podman/blob/f845d14e941889ba4c071f35233d09b29d363c75/docs/source/markdown/options/network.md#L36-L94)；[新 network options](https://github.com/podman-container-tools/podman/blob/b17e7db1d3ad0c6ca56e292aa95273facb80f8ad/docs/source/markdown/options/network.md#L53-L95)；[Netavark nft implementation](https://github.com/containers/netavark/blob/e32aa54b9004f8a914e58b7e3b2d9d290f9dfca5/src/firewall/nft.rs#L122-L217)；[aardvark README](https://github.com/containers/aardvark-dns/blob/0443fbeed17ee45e209ef36f8fd26a94846ed261/README.md#L3-L8)。稳定版 tag revision 另有 `release-tag-revisions.json` 保全；稳定文档部分“private 默认 slirp”描述与 5.x 默认 pasta 说明不一致，因此实施必须显式指定已支持后端，并检查 `podman info`。

### 5.2 官方软件包 metadata 已核对，真实安装未运行

本研究复用第一会话留存的官方 ARM64/noarch 索引，仅解析所需 package record 并重算索引 hash，没有下载 rootfs 或实际包事务。另下载 Ubuntu noble universe ARM64 Packages.xz：15,313,828 字节、SHA `6df230cf5cfebcbd59e4e2713b8eed07dc0aaed66fb471ebf046cb70ccb07275`，与既有 Ubuntu InRelease 的该条目完全一致；没有重新执行其 GPG 签名校验。以下版本是该次索引样本，不能视为永不更新的 release lock。

| 当前 guest 家族 | ARM64 索引中实际供应 | 最小安装/权限建议与限制 |
| --- | --- | --- |
| Debian 13 / trixie | Podman `5.4.2+ds1-2+b2`；crun 1.21；conmon 2.1.12；netavark/aardvark 1.14；passt、slirp4netns、fuse-overlayfs；独立 `docker-cli 26.1.5+dfsg1-9+deb13u1` | 签名 APT 的 podman、uidmap、crun/conmon、passt，显式后端；需要 user session 的 libpam-systemd/dbus 支持；CLI 可独立安装 |
| Ubuntu 24.04 / noble | universe Podman `4.9.3+ds1-1build2`；crun 1.14.1；conmon 2.1.10；netavark/aardvark 1.4；passt 20240220；slirp4netns 1.2.1；fuse-overlayfs 1.13；main 有 uidmap | 开启匹配 noble 的签名 universe；首个组合明确 slirp4netns 或单独验 pasta。该索引 `docker.io` 依赖 containerd/iptables，不能当 CLI-only 安装 |
| Fedora 43 | Podman 5.6.2；crun 1.24；conmon 2.1.13；netavark 1.16.1、aardvark 1.16；passt/slirp/fuse；独立 docker-cli 28.4.0 | signed DNF transaction；shadow-utils 的实际 newuidmap/gidmap provider/权限须安装后查；user systemd/SELinux runtime 状态按真实 guest 判定 |
| Arch Linux ARM / rolling | Podman 6.1.4；netavark/aardvark 2.1.0；crun 1.30.1、conmon 2.2.1；passt 2026-10-02；shadow 4.20 | 用 Arch ARM 签名 pacman 仓库且先保持 keyring 可验证；不能复用 Ubuntu 4.9 的 slirp/iptables 配置。docker 包存在不证明可无 daemon 提取 CLI |
| openSUSE Leap 16.0 | Podman 5.4.2；netavark 1.14.1、aardvark 1.14；conmon、passt/slirp/fuse、shadow；docker 多版记录 | 固定 Leap/aarch64、签名 zypper；不混 Tumbleweed。需要查 actual OCI runtime dependency/provider；CLI 单独包本次未确定 |
| AlmaLinux 9 | AppStream Podman 5.8.2；netavark 1.17.2、aardvark 1.17.x；crun 1.27；passt/slirp/fuse；BaseOS shadow-utils | BaseOS + AppStream 均保留、RPM gpgcheck 开启。并未核对 Docker CE repo/CLI 独立包，不自动加第三方源 |
| Rocky 9 | AppStream Podman 5.8.0/5.8.2 多条；其余同家族 netavark/aardvark/passt/slirp/fuse；BaseOS shadow-utils | 与 Alma 同样逐 provider 核验；major 9 更新仓与旧 rootfs version 分开描述，不自动升级整个 guest |

官方索引 URL：[Debian main](https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.xz)、[Ubuntu main](https://ports.ubuntu.com/ubuntu-ports/dists/noble/main/binary-arm64/Packages.xz)、[Ubuntu universe](https://ports.ubuntu.com/ubuntu-ports/dists/noble/universe/binary-arm64/Packages.xz)、[Fedora 43](https://dl.fedoraproject.org/pub/fedora/linux/releases/43/Everything/aarch64/os/repodata/repomd.xml)、[Arch ARM core](https://ca.us.mirror.archlinuxarm.org/aarch64/core/core.db)/[extra](https://ca.us.mirror.archlinuxarm.org/aarch64/extra/extra.db)、[Leap 16](https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/aarch64/repodata/repomd.xml)、[Alma AppStream](https://repo.almalinux.org/almalinux/9/AppStream/aarch64/os/repodata/repomd.xml)/[BaseOS](https://repo.almalinux.org/almalinux/9/BaseOS/aarch64/os/repodata/repomd.xml)、[Rocky AppStream](https://dl.rockylinux.org/pub/rocky/9/AppStream/aarch64/os/repodata/repomd.xml)/[BaseOS](https://dl.rockylinux.org/pub/rocky/9/BaseOS/aarch64/os/repodata/repomd.xml)。

审计记录 E `distro-productivity-package-metadata.json` 按源路径/hash/版本/架构/包路径/checksum/可用 license 分项记录；APT 原始必要 stanza 位于 `debian-trixie-productivity-packages.source.txt`（Podman:213、CLI:73、uidmap:274）及 `ubuntu-noble-universe-productivity-packages.source.txt`（Podman:160、netavark:112、docker.io:67）。实际包签名、全部依赖安装和七族运行兼容性均 **NOT_RUN**。Alpine/OpenRC 是额外风味，本次没有重新核对其 Podman 包 metadata，也没有把它列为 glibc code-server 支持组合。

### 5.3 官方验收镜像固定输入

本次对公开 manifest 原字节计算 SHA，与 HTTP `Docker-Content-Digest` 和索引指向值相互核对；没有拉取 layer、验 image signature 或执行 Podman。

| 用途 | 已核对固定引用 | 能验什么与状态 |
| --- | --- | --- |
| Shell/storage/UID/DNS/资源限制 fixture | `docker.io/library/alpine@sha256:24bb3511a0db7b5114a4aee033c65a8a4148f39b7b80a398e548546db967a36f`，tag 3.22.2，linux/arm64/v8 | ARM64 leaf manifest 1,025 字节已核对；index digest `sha256:4b7ce07002c69e8f3d704a9c5d6fd3053be500b7f1c69fc0d80990c2ad8dd412`。**layer pull / podman run：NOT_RUN** |
| 最小 hello 执行 | `quay.io/podman/hello@sha256:5c44ef36dc5e35a76904da0e028cf9413e0176a653525162368af13fed03571c`，linux/arm64 | leaf 427 字节已核对；index `sha256:41316c18917a27a359ee3191fd8f43559d30592f82a144bbc59d9d44790f6e7a`。不能代替 shell/DNS/resource 测试；**pull/run：NOT_RUN** |

Alpine 来源 annotation：`https://github.com/alpinelinux/docker-alpine.git#4dc13cbc7caffe03c98aa99f28e27c2fb6f7e74d:aarch64`，版本 3.22.2；[官方 image 页面](https://hub.docker.com/_/alpine)。Alpine 镜像是多组件发行版，不是单一 MIT/Apache 软件；按实际包清单保留 BusyBox/Linux/GPL 等各自义务。本研究只固定测试输入，不创建再发布镜像。

原始 URL：[Alpine ARM64 manifest](https://registry-1.docker.io/v2/library/alpine/manifests/sha256:24bb3511a0db7b5114a4aee033c65a8a4148f39b7b80a398e548546db967a36f)、[Podman hello ARM64 manifest](https://quay.io/v2/podman/hello/manifests/sha256:5c44ef36dc5e35a76904da0e028cf9413e0176a653525162368af13fed03571c)。证据 E `alpine-3.22.2-arm64-digest-check.json`、`alpine-3.22.2-oci-index.json`、`alpine-3.22.2-arm64-oci-manifest.json`、`podman-hello-digest-check.json` 及两级 manifest。

## 6. Docker CLI 与 rootless daemon 的分开实施建议

| 能力 | 安装/运行模型 | 验收与权限 |
| --- | --- | --- |
| Docker CLI | 官方 docker/cli 或独立发行版 `docker-cli`；普通用户调用 | `docker --version` 仅 CLI ready；必须明确当前 context/endpoint、daemon 未连接状态。禁止自动接宿主 TCP、docker group 或系统 rootful socket |
| Podman 兼容 Docker CLI API | 用户显式启用 `systemctl --user start podman.socket`，endpoint 为 `/run/user/<uid>/podman/podman.sock` | 保持用户专属 Unix socket；不默认启动，不分享给其他用户，不转发 HTTP API；兼容 API 范围逐工作流验，不能显示“Docker daemon ready” |
| Rootless Docker engine | dockerd-rootless、containerd/runc、RootlessKit + UID helpers，普通用户 service | `dockerd-rootless-setuptool.sh install`、user systemd、linger 等仅在 guest 且显式 opt-in；`docker info` 的 Server rootless/security/cgroup/storage 与真实 run 单独验 |

Docker 官方 [rootless setup](https://docs.docker.com/engine/security/rootless/) 明确 daemon 和 container 一起在 user namespace 中；userns-remap 则仍保留 rootful daemon，不能等价。rootless helpers 需要至少 65,536 subuid/subgid。官方 [troubleshoot](https://docs.docker.com/engine/security/rootless/troubleshoot/) 把 CPU/memory/pids enforcement 限定于 cgroup v2 + systemd，列 overlay2 ≥5.11、fuse-overlayfs ≥4.18、VFS 等支持；这些最低版本不代替本 Image 的功能配置、guest storage 和权限实测。

本 Image 的 `NFT_MASQ`/iptables 缺口也影响 Docker bridge，不能因为 `docker --version` 成功就绕过。禁止开放默认 `tcp://0.0.0.0:2375`/2376、把 rootful `/var/run/docker.sock` 或 Android 侧 socket 挂到 code-server、自动关闭安全 profile。Rootless Unix API 对它所属普通用户仍是执行代码的强权限接口。所有 socket/daemon 状态独立展示；没有启用 daemon 就不显示容器引擎已运行。

本研究没有安装或运行 Docker CLI/engine，也没有固定整个 Docker CE 依赖闭包；此项 **NOT_RUN**。Debian/Fedora 的独立 CLI metadata 已核对；其他家族的 CLI-only 供应需要实施 owner 再明确包或官方 static tar lock。Ubuntu 当前纯 CLI 未核实，首版应 disabled，不为凑功能安装 daemon。

协调者实施输入补充：Debian 独立 CLI 的精确包为 `docker-cli 26.1.5+dfsg1-9+deb13u1`、`arm64`，URL `https://deb.debian.org/debian/pool/main/d/docker.io/docker-cli_26.1.5+dfsg1-9+deb13u1_arm64.deb`，SHA-256 `d9dbab1a7cae040aa617ab509067b4d070dc1dde190851e5b629150705da55fd`，Depends 仅 `libc6 (>= 2.34)`；不是 `docker.io` daemon 包。源 Packages.xz 本次 hash `be16b37b2f740f066ea93d35d56f04f94d63693e5a3eb421b052bf86fae75bcd`；必要原 stanza 在 E `debian-trixie-productivity-packages.source.txt:73` 起。包 binary 下载/安装 NOT_RUN。

活动主仓 `guest-assets/scripts/orchestella-guest-provision.sh:115,123,131` 等 mandatory package 列表与全文精确检查没有 `python3`。这只证明 baseline **不保证 Python3**，不能推出所有 rootfs 都没有它。若实施入口使用 `profilectl.py`，必须把 Python3 纳入该 feature bootstrap 的显式依赖并验证可执行，或提供受审查 shell 引导；不能在基础 provisioning ready 后默认 Python 已存在。没有修改基础包列表。

## 7. code-server 的固定 ARM64 输入、分发与许可

### 7.1 已实际下载和核验

采用候选固定版本 **code-server 4.141.0**，Git tag 指向 **`8b41915d25547e2edd553bab03ea4cbd621f98c5`**；[官方 release](https://github.com/coder/code-server/releases/tag/v4.141.0) 页面记录 2026-10-08 发布。不在实施脚本使用 `latest`、未固定 `curl | sh`、AUR latest 或浮动 npm install。

| 输入 | 固定 URL / digest | 本次状态 |
| --- | --- | --- |
| 首选 Linux ARM64 standalone | `https://github.com/coder/code-server/releases/download/v4.141.0/code-server-4.141.0-linux-arm64.tar.gz`；**234,587,826 bytes**；**SHA-256 `e5cc78da1d631c6cf6162f9b18eeaa8ee9d3239e1a88bb99d0602cdc5fa1852d`** | HEAD 与实际下载相同；实算 SHA/size 匹配；路径 I `code-server-4.141.0-linux-arm64.tar.gz`；真实 guest 安装/运行 NOT_RUN |
| 官方 ARM64 deb | `https://github.com/coder/code-server/releases/download/v4.141.0/code-server_4.141.0_arm64.deb`；发布 SHA `0bca33a9b01180ad88f0b8969a8e34ae1c76417df471697e7cd65c20ef5dd60d` | 仅公开资产 metadata 已核对，未下载/未安装；不作为已验证输入 |
| 官方 ARM64 rpm | `https://github.com/coder/code-server/releases/download/v4.141.0/code-server-4.141.0-arm64.rpm`；发布 SHA `b2d597808b89bdca19f730157eba3ea7279258ffbb0426f860a98f27d427f872` | 同上；不称 RPM 签名通过 |

发布 SHA 来源为[官方 expanded_assets](https://github.com/coder/code-server/releases/expanded_assets/v4.141.0)；E `code-server-assets.html.txt:91–100` 为 tar 链接/digest，`:226–235` 为 deb、`:37–46` 为 rpm。Git revision 来源 `release-tag-revisions.json`，不是根据页面标签猜测。实际下载证据 `code-server-arm64-download-verification.json`；静态 archive 证据 `code-server-arm64-static-archive-check.json`：`lib/node` 为 ELF64，machine=183/AArch64，package.json version=4.141.0/license=MIT。

第一次额外 archive 探针对目录误用 extractfile 导致 AttributeError；原下载证据如实保留，size/SHA 校验已在该错误前完成。后续检查只读真实 file member，独立记录修正结果，没有重下或覆盖原始证据。

### 7.2 可审计不等于已验签

已阅读固定 revision 的 `.github/workflows/release.yaml:31–43,126–133`（ARM64 build matrix/发布 assets）、`publish.yaml`、`ci/build/nfpm.yaml:1–25` 和官方完整资产列表。这些可解释官方 tar/deb/rpm 的发布方式；在本次检查范围内没有建立 ARM64 tar 的 detached GPG/Sigstore signature 或 provenance 验证链。GitHub 页面显示的发布 digest 与同源下载可以检测锁后变化，不提供独立 publisher-key 验证。

因此准确状态是：**官方固定 URL + revision + 实际 size/SHA 校验通过；上游签名验证 NOT_RUN / 未建立**。若产品验收要求“签名输入”，需 owner 另选确有签名验证链的 package/repository 或审核后形成自有签名输入；不能把 HTTPS、tag、页面 digest 或 GitHub verified commit 图标称为 tar 验签。

### 7.3 最小 guest 安装方式

固定 revision [docs/install.md:93–123](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/docs/install.md#L93-L123) 说明：standalone bundle 含 Node/native modules，Linux 需要 **glibc ≥2.28、GLIBCXX ≥3.4.21**。Debian/Ubuntu/Fedora/Leap/Alma/Rocky/Arch ARM 的实际 libc/native-addon ABI 仍需 guest 探针；不能仅按架构 label ready。

建议 guest 管理员将已校验 tar 解入 versioned `/opt/orchestella/code-server/4.141.0`，root-owned 程序目录、普通用户不可写；下载 size/SHA 锁先验证，安全 tar path/link/type 检查通过才解包。符号链接在完整安装与 `--version`/Node native-addon 探针通过后原子切换；安装状态和用户配置分离。所有命令由 guest 执行，宿主不执行 tar 内的脚本/Node。也可用户私有 `~/.local` 安装，但发行输入与用户可修改范围须分别记录。

官方 deb/rpm 会放 system 与 user service，来源 `ci/build/nfpm.yaml:18–22`；默认 `code-server@.service` 的 `User=%i` 避免 root 身份，但系统 unit 本身不保证 Podman 所需 user session。首个完整开发环境建议采用真正 user unit，见 §8。

Alpine/musl 必须另做固定 Node/npm 源构建/native-addon 闭包，官方文档明确建议 npm；不能调用不固定 `npx`、安装兼容 glibc 后就冒充通过。首次最小实施只对已验证的 glibc/systemd guest 开放 code-server。

许可为[固定 LICENSE](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/LICENSE#L1-L21)，MIT。同 tar 的 lib/vscode、Node 和 npm 组件 notices 随安装保留。扩展默认 Open VSX；[固定 FAQ:105–125](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/docs/FAQ.md#L105-L125) 解释 Microsoft Marketplace 限制。扩展来源与 license 分别审计；不自动下载登录/同步/云能力。

## 8. guest 普通用户、systemd、认证和网络的最小实施

以下是建议与待实施验证，不是本研究执行结果；本研究没有写任何 unit/生产脚本。

### 8.1 普通用户和生命周期

1. Provisioning 必须先确认真实发行版 PID1 与基础服务 ready，不能在恢复 supervisor 中仅写一个 unit 就标 service ready。与活动主仓既有 `guest-runtime-audit` 的 native PID1/ready gate 对齐。
2. guest 管理员一次性创建/核验普通开发用户及 UID helpers/subid 分配。通过真实 PAM/logind 会话，或经显式设置的 linger + `user@<uid>.service` 建立 user manager，确认 `/run/user/<uid>`、user D-Bus 和 delegated controllers。
3. code-server 在该用户 `systemctl --user` 的前台 ExecStart 中运行，禁止额外 `nohup`/shell 背景化。可用固定路径 `.../bin/code-server --config ... --bind-addr 127.0.0.1:8080 --auth password --disable-telemetry --disable-update-check --disable-proxy --cookie-suffix <instance-id>`；配置缺失/不合法时启动失败，不能 fallback `--auth none`。
4. service 使用 `UMask=0077`、明确 WorkingDirectory/用户数据目录、`Restart=on-failure`、有限 `StartLimitBurst/IntervalSec` 和 stop timeout。若预期从集成终端启动 Podman，不施加继承到 helper 的 NoNewPrivileges/RestrictNamespaces；Node V8 也不能盲设 MemoryDenyWriteExecute。这些 hardening 取舍须实际负例验证，不把 disabled filter 当 rootless 成功。
5. 安装默认不 enable 自启或创建宿主监听。用户显式 start 后保存“需要该 service”的实例配置；restart/stop/status 用真实 user service 结果，不以 marker 文件表示 active。Podman CLI 是 daemonless，常驻 container 可在对应版本支持下采用 rootless Quadlet/user units；Ubuntu 4.9 和 Arch 6.x 不复用未核对的 Quadlet 参数。
6. guest 服务可以随 VM 冷启动恢复；Android App/VMM 死亡则 VM 和监听消失，仍遵循 ADR-0011。不承诺 systemd 使手机后台无限存活。LAN 临时授权绝不随冷启动自动恢复。

上游 unit 来源：[固定 user unit](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/ci/build/code-server-user.service#L1-L11)；[Podman Quadlet 原理/位置](https://github.com/podman-container-tools/podman/blob/b17e7db1d3ad0c6ca56e292aa95273facb80f8ad/docs/source/markdown/podman-systemd.unit.5.md#L35-L90)。本报告建议的重启限流、安装状态及 stop semantics 是项目最小设计，尚未运行。

### 8.2 每实例认证

- 每实例、每 service 的独立 CSPRNG 密码（建议至少 32 random bytes 的表示），用户可见可改；**不复用 Bridge token**，不复制公开固定密码，不从 report/CLI argv/URL/query 注入秘密。上游默认 config 使用随机密码，但用户导入配置必须检查，不能认为默认值永远安全。
- 凭据文件位于普通用户 `~/.config/code-server/config.yaml`，目录 0700、文件 0600。优先 `hashed-password` Argon2，通过已固定 bundle 的库/受审计 guest 生成器处理，不使用在线未固定 `npx`；不把明文写日志。密码轮换后 restart 服务并撤销客户端旧会话。
- 固定源码 `src/node/cli.ts:151–159,425–430,613–654,753–758` 支持 config/env password，拒绝 CLI password；`src/node/util.ts:134–145` 使用 crypto randomFill/argon2；`routes/login.ts:71–104` 验证并限流。其密码登录不是多用户 IAM，IDE 登录者拥有整个该 guest 用户的代码执行/文件访问能力。
- 保持 upstream Origin 检查；不设置 `trusted-origins=*`、不引入 arbitrary proxy-domain。默认关闭 code-server proxy，避免一个被批准的 editor origin 顺带提供任意 guest 本地端口代理；需要开发预览时仍通过用户显式 service/端口规则控制。

固定认证来源：[cli.ts](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/src/node/cli.ts#L151-L159)、[login.ts](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/src/node/routes/login.ts#L71-L104)、[Origin 检查](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/src/node/http.ts#L340-L407)。对应 fixed source 原件与 hash 在 E。

### 8.3 当前 gvproxy API 与 guest loopback 的衔接

当前事实取自只读**活动主仓**，不以隔离 HEAD 的旧 `QemuHostfwdFormatter` 当主路径：

- `QemuInstanceLauncher.java:256–276` 启动 NetworkGatewayDaemon，使用 `buildWithPasst` legacy socket 连接；底层是 packaged gvproxy。
- `NetworkGatewayDaemon.java:67–99` 只加载用户明确端口规则，API 位于 App-private Unix socket，目录 0700、socket/config 0600；`173–174` / `256` 限 **TCP + host 127.0.0.1**。
- `198–199`、`258–260` 将宿主 `127.0.0.1:<hostPort>` 指向 **guest NIC `192.168.127.15:<guestPort>`**，不是 guest loopback。
- `219–250` 使用私有 API `GET /services/forwarder/all`、`POST .../expose`、`POST .../unexpose` 并回读确认/回滚。客户端只有规则 confirmed 才可称可打开。

因此仅 `code-server --bind-addr 127.0.0.1:8080` 加宿主规则不能保证通。最小保持服务 loopback 的方案：用户显式“打开 editor”时启动 guest 用户态 relay，绑定实际 guest NIC 的一个高端口，固定转发到 `127.0.0.1:8080`；再通过现有 PortForwardingRule/LiveEngine 建 host loopback 规则。relay 不接受来自 guest 的任意 target/host/命令参数，不是开放 CONNECT proxy。AVF 若已用 guest-loopback vsock proxy，应直接复用已验证 target 机制，不再叠 NIC relay；AVF 权限/可达性仍须 owner 实测。

该 NIC relay 也扩大了 VM 内 NIC 可达面，必须作为**显式 service 连接动作**管理，而不能随安装默认常开。stop/失败先撤 host 规则并回读确认，再停止 relay；跨实例/未知 port/status/API 未 ready 一律失败，不以存储的规则文件代替活跃监听。relay、service、host listener 三者状态分开。

**LAN 最小边界**：ADR-0008 允许显式 `0.0.0.0` 转发，但活动实现的支持谓词/response parser 仍限 loopback。本研究没有实施或验证 LAN。owner 先扩展既有 API adapter 的 bind/confirmation/timeout gates；用户显式同意、前台持续告警、短时授权、到期/停止/进程死亡关闭。维持 code-server password，LAN 登录必须使用可信 HTTPS/TLS 路径，不能裸 HTTP 传密码后称安全。证书错误 fail closed，不新增系统 CA 或防火墙修改；若暂无可信 TLS 和 current API LAN 能力，正确结果是“LAN unavailable”，本机服务继续可用。

## 9. 手机 WebView allowlist 与认证边界

隔离与活动主仓查到的 `AndroidUrlOpenerAdapter.java` 为**外部 `Intent.ACTION_VIEW`**：`14–15,31–34,41–68` 只验证 HTTP/HTTPS、合法 Host 和长度。没有在本次搜索范围发现已完成的内嵌 editor WebView 或精确 origin allowlist。不能把该 scheme-only opener 当作 WebView 安全实现。

建议首版只提供已确认宿主规则对应的 `http://127.0.0.1:<hostPort>/` 本机 origin，按标准 URL parser 精确匹配 scheme、host、port 和当前 instance；拒绝 userinfo、编码歧义、控制字符、未知 redirect、新窗口、任意 localhost 子域/地址或其他 loopback 端口。不能用字符串 contains/startsWith/endsWith 判断 host。每次打开、redirect 与可拦截的子请求均重新验证；WebSocket/ServiceWorker 的路径要有专门 runtime 验证，不能认为单个 `shouldOverrideUrlLoading` 覆盖所有流量。

IDE 确需 JavaScript，但不提供 `addJavascriptInterface` 或 unrestricted message bridge；禁 file/content access、file universal access、mixed content、SSL-error proceed、自动下载/任意 SAF 授权、摄像头/麦克风/地理定位等额外权限。外链由用户主动确认后交外部 browser。访问宿主私有文件/其他 App 的能力不得因为 IDE 能执行 guest code 而增加。localhost 的浏览器 secure-context 特例支持 service worker，不能据此允许 LAN HTTP；官方 FAQ `545–560` 也明确 LAN/IP HTTP 的 webview/service-worker 限制。

### 9.1 同 host 不同端口的 cookie 风险

固定 code-server `src/node/http.ts:302–324` 的 cookie options 只有 domain/path/sameSite=lax，未显式设置 HttpOnly 或 Secure；`login.ts:96` 保存 password-derived hash cookie。**浏览器 cookie 不按端口隔离**。因此同时把不同 VM 的 editor 放 `127.0.0.1` 不同 port 并共用 Android CookieManager，不能称跨 VM 认证隔离；仅 `cookie-suffix` 避免名字碰撞，不能阻止同 host 的恶意页面看到或发送别的 cookie。

最小候选为一个活动 editor：切换 instance 前停止旧 view，等待 cookie/storage 清理成功，确认仅当前 confirmed origin 可加载；每实例不同密码 + `cookie-suffix`。这仍需覆盖异步清理、ServiceWorker、WebSocket、后台请求和已打开页面的负例，本研究**未验证**其完整隔离，不能把它升级为强承诺。需要同时运行多个互不信任 editor 时，应采用每实例独立 WebView data-directory/process，或可验证的不同 host/独立认证 origin；不能只换 TCP 端口。此方案由 UI/architecture owner 决定，研究子代理不添加 WebView 生产实现。

证据：[固定 cookie options](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/src/node/http.ts#L302-L324)、[cookie-suffix option](https://github.com/coder/code-server/blob/8b41915d25547e2edd553bab03ea4cbd621f98c5/src/node/cli.ts#L181-L185)。Android 官方 unsafe URI 页面本次访问超时，未把未取回内容当引用证据；上述 Android 配置为本项目建议，必须由真实 WebView 测试确认。

## 10. 官方 VS Code Server 与 OpenVSCode Server

| 项目 | 分发/许可 | 登录/认证/网络 | 项目建议 |
| --- | --- | --- | --- |
| Coder code-server | 独立 MIT 项目；ARM64 glibc tar/deb/rpm；固定输入见 §7 | 本地 password/cookie/Origin gate；无需 Microsoft/GitHub 云账户；telemetry/update/proxy 可显式关 | 最小首选；关闭云同步，保留扩展独立许可 |
| Microsoft VS Code Server | 官方分发适用 Microsoft Server 产品条款；其 repository 源码 MIT 不覆盖产品限制 | `code tunnel` 两端需同一 GitHub/Microsoft 账户、Azure outbound relay；SSH remote server 是另外接入机制，不能断言也必须云登录 | 不打包为本 App 的泛用 IDE 服务、不自动接受条款或启云 tunnel；用户个人 SSH 使用另行说明 |
| OpenVSCode Server | VS Code OSS fork，MIT；已核对 ARM64 release asset | Docker image 文档默认 without-connection-token；README v1.64 描述可无认证，当前/固定 token parser 却默认 mandatory token；显式 token-file 消除默认差异 | 可作备选，token/file 0600，禁 without-connection-token；日志/URL 中 bearer token 的泄漏和轮换需另验 |

Microsoft 官方 [Server 文档](https://code.visualstudio.com/docs/remote/vscode-server) 说明 single-user 设计和不允许 hosting as a service；[产品许可](https://code.visualstudio.com/license/server) 的禁项包含 host/share/publish、将产品提供给第三方等。不能用“VS Code 开源 MIT”推出 Microsoft product 可再分发。该文档本身没有证明所有 Server 操作都必须外部登录，所以本报告只把外部账号要求限定在官方 tunnel。

[Remote Tunnels](https://code.visualstudio.com/docs/remote/tunnels) 安全说明要求两端同一 GitHub/Microsoft account，流量经 Azure 服务，组织可管控 `global.rel.tunnels.api.visualstudio.com`。本任务只读文档，**没有登录、创建 tunnel、连中继或同意产品许可**。相关原页面与 hash 在 E `vscode-server-license.source.txt`、`vscode-tunnels.source.txt`。

OpenVSCode 已核对固定 release `openvscode-server-v1.109.5`，revision **`4ffe2270acdf711bbefecc3e8c79f4b3631640e5`**；[ARM64 tar](https://github.com/gitpod-io/openvscode-server/releases/download/openvscode-server-v1.109.5/openvscode-server-v1.109.5-linux-arm64.tar.gz) 发布 SHA `36d9c14036489b63de84ebace837fcacf7e60e669a0dc715802c5443684ea4dc`。**仅 metadata，未下载、未验签、未运行**。E `openvscode-assets.html.txt:10–19`、`release-tag-revisions.json`。

固定 `serverConnectionToken.ts:50–89`：without flag 明确 None；显式 file/string 为 Mandatory；未指定时调用 default token generator。`resources/server/bin/code-server-linux.sh:22` 传 argv 给 server-main。README 的默认说明不能取代实际发行启动器/参数，故必须给 `--host 127.0.0.1 --connection-token-file <private-file>` 并实测未认证 HTTP/WebSocket 拒绝。关闭 telemetry，避免使用打印 token 的 debug 日志。MIT/open-source 也不使 Microsoft Marketplace/专有扩展自动可用。

## 11. 真正 guest 验证门与未运行清单

下面全部是交给实施/设备 owner 的验证步骤，**本研究未运行**，不应写成已通过。只在明确设备交接后执行；不在 Windows 宿主安装/启动 container engine 来替代。

| 门 | 真实观测与失败条件 |
| --- | --- |
| G1 用户/namespace | 普通 UID 非 0；userns 创建成功；newuidmap/gidmap 来自正确签名包；uid_map/gid_map 包含用户及合法 subrange。公开 report 只留脱敏范围、阶段/错误，不读取真实凭据 |
| G2 kernel/current boot | 在运行 guest 的 `/proc/config.gz`/版本与已验证 Image 对齐；cgroup2fs、controllers/delegation、user bus 和真实 PID1；不是恢复 supervisor 或混用不同内核模块 |
| G3 offline container | 固定 Alpine ARM64 digest，`--network=none` 运行 `/bin/sh -c 'id; cat /etc/os-release'`；记录 ARM64、storage driver、rootless=true、映射 UID；registry layer pull 仍是独立安装动作 |
| G4 网络/DNS | 已安装版本显式 pasta 或 slirp4netns；实际 `busybox nslookup`、HTTPS 与出站 TCP；host loopback 默认不可达；guest 出网和 container 出网分别观察。NAT/IPv6/VPN 不扩大已定义承诺 |
| G5 cgroup/seccomp | 设置 memory/cpu/pids 后读取容器自己的 memory.max/cpu.max/pids.max 并执行受限负例；seccomp profile 生效和拒绝 syscall 单独验，不只看 config/进程 Seccomp 字段 |
| G6 Netavark/DNS | 独立建 DNS-enabled rootless bridge，两个 fixed images 互查名字/alias、外部 DNS、明确 port publish 与 remove；缺 MASQ/FIB/matching module 时标 unavailable，不偷偷走 host network |
| G7 IDE 安装/权限 | size/SHA/tar path-link 校验、Node/ABI/PTY/native module smoke、普通用户 UID、真实 unit active、日志脱敏；失败不写 ready，显式 retry 后能恢复 |
| G8 认证/客户端 | 未认证 HTTP 与 WS 拒绝、错误密码限流、正确登录、旋转凭据后旧会话失效；外链/未知origin/file/content/js bridge/SSL异常被拒；不能用公开模板页面代替 editor loaded |
| G9 生命周期/转发 | service/relay/host forward 的 start/status/stop 和 API 回读一致；stop/失败/冷启动/VM death 删除监听；多实例 port 冲突失败；LAN 显式授权/超时/认证/TLS 门独立 |
| G10 Docker | CLI-only 未连接提示；如用户另选 engine，Server rootless=true、正确 context/Unix endpoint、真实 run、limits/network/storage 均验；不接受 docker group/rootful fallback |

已完成的验证只包括：八项新参考 shallow sparse clone 与许可核对；实际 packaged Image 静态配置/77 required gate；七族官方 package metadata 解析及 Ubuntu universe 索引 digest；固定 code-server tar 实际 size/SHA/ELF/包版本检查；两个官方 ARM64 image manifests digest。没有 systemd/PAM/Podman/Docker/IDE guest 安装、容器 pull/run、WebView、LAN、真机或性能数据。

收口检查 E `final-preservation-and-scope-gate.json`：60 件检查时已生成的 canonical 原始证据均有字节一致 preserved 副本，B 范围新 `.log` 文件为 0，八项参考均为 shallow=true / sparse=true。正式文件 `git diff --no-index --check /dev/null <report>` 无 whitespace 错误。保全 gate 自身也立即保存同字节孪生；没有依赖工具自动写在 C: 的输出作为项目 canonical 证据。

有限访问失败：GitHub API 返回 403 rate limit，改用官方 Git/HTML/raw；Docker Hub tags API 429，改用 registry manifest；Android unsafe URI 文档 timeout；Podman `hello/README.md` 路径 404，因此未将该猜测路径作权威引用。未对失败 endpoint 无限重试，没有创建后台上传队列。

## 12. ADR 影响、产物与后续责任

| ADR | 影响与建议 |
| --- | --- |
| 0005 Guest kernel | 77 条旧 required 已满足，但未涵盖实际 nft bridge 表达式。增补 NAT/FIB gate 符合既有完整 Linux/container 契约方向；新 Image/build input/hash 和实例迁移仍需 kernel owner 显式处理。当前先准确标 capability unavailable/未验证，不改 ADR 或静默换 kernel |
| 0008 Networking | 默认 host loopback、显式 forward 语义保留；当前 gvproxy adapter 仅 TCP loopback，应如实降级 LAN/UDP。服务 loopback↔guest NIC relay 要显式启停；LAN/TLS/timeout 必须走现有语义和可测 gates，不重回旧 SLIRP hostfwd 路径 |
| 0009 Bridge | 使用现有 per-VM authenticated 控制通道和端口规则，不在控制面搬 IDE文件、密码或图形/HTTP 大数据。若 service 生命周期需新增 bridge command，先由 owner 固化协商/版本/白名单，不能从本报告直接扩命令；本研究未修改协议 |
| 0010 Security | guest root/subuid 不获得 Android Root；凭据独立随机、没有云/telemetry。IDE认证和 WebView 是实际攻击面，不能借 scheme-only open-url 绕过；新原生 bridge、云 tunnel、任意本地proxy或宿主socket共享须进入架构变更门 |
| 0011 Lifecycle | guest user systemd 不能保证 Android后台永续；VM death=服务/host监听死亡，冷恢复仅持久配置/数据。LAN授权不自动恢复，service重启有限，不新增宿主独立常驻进程承诺 |

**是否需要重开 ADR**：本研究没有足以要求推翻上述产品决策的新证据；单容器可行性、网络后端版本与当前 implementation 缺口可以先用能力矩阵和现有契约修复承载。若后续要求新 bridge 命令、跨实例 WebView 强隔离新抽象、共享 host socket/目录、云 relay，或把默认 LAN/IPv6 加入承诺，则由协调者按 Architecture Change Policy 发起 ACR；本 child 不修改 tracker/ADR。

**实际修改**：仅新增本研究文件；新增八个公开参考 checkout、E 下证据及 preserved 孪生、I 下固定 code-server tar 和 Ubuntu universe metadata。无生产/协议/UI/Gradle/测试源码/tracker/ADR 修改。共享工作区其他所有者的变化不归本 child。工作区外全部手动写入都位于用户/派发及协调者明确授权的 `phase5-references` 与 `Orchestella/artifacts/phase5-parallel-20261013/B`。

**保留与清理**：owner 为 productivity-research/Phase5 协调者；reference 和经 SHA 验证的固定输入作为可复用资产保留；E 保留至 Phase5验收及独立review完成，之后由协调者按 asset policy 决定归档。没有清理任何既有资产，也没有删除访问失败或探针错误证据。正式文档是唯一解释层，不另写同类 report副本。

**最小后续**：profile owner先锁定普通用户、真实PID1、Ubuntu4.9或已选版本的明确网络后端和上述 code-server输入；network owner确认 gvproxy API/guest relay；UI owner完成精确origin与cookie边界负例；第一会话交接后运行G1–G10中对应门。Podman bridge、Docker rootless daemon、OpenVSCode备选、LAN及并行多实例WebView保持独立未验证状态。

报告人：productivity-research / Codex（new-provider/gpt-6.1-sol） ｜ 日期：2026-10-13 ｜ 耗时：未统计
