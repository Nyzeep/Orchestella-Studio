# Phase 5 桌面支撑研究：七发行版 ARM64 包矩阵、CPU 通路与可恢复会话

- 触发：`.scratch/orchestella-phase5/issues/02-desktop-package-matrix.md`；2026-10-13。
- 执行者：desktop-research supporting child / Codex；亲自研究，无进一步派生。
- 隔离工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`；调度分支 `phase5/graphics-productivity-storage`，调度基线 `8d0e8c75d769ecc645ea102b90a304c8657344a1`。本 child 未改变分支、未 commit/push/PR/发布。
- 只读活动主仓，下文 `MAIN`：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev`。
- 唯一正式输出为本文；原始证据，下文 `EVIDENCE`：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/desktop/evidence`。
- 新公开参考根，下文 `REF`：`D:/Development project(开发项目)/Orchestella开发目录/phase5-references`。

**建议首组合为 Ubuntu 24.04 ARM64 的 labwc headless + pixman + wayvnc + foot，Debian 13 为第二个验证目标。** Ubuntu 的基础原生 init 已有第一会话设备证据，适合减少同时排查的变量；Debian 的桌面包更新，尤其 wayvnc 0.9.1 有输出 resize 支持。该通路保留 Wayland compositor，使用 CPU 捕帧及 RFB 字节传输，不能标为 GPU 加速、跨 VM 零拷贝或已经实测的原生 Wayland 传输。Xvnc/Xfce 保留为诊断对照；KDE、niri 分别作为独立实验 profile。七个发行版不能共用一张未经探测的 labwc/niri 安装包表。

## 1. 研究范围、已有成果与接口边界

先读隔离区 `AGENTS.md`、dispatch 和工单。隔离 HEAD 缺少 ignored 的治理/ADR/研究文件，按协调者补充从 MAIN 读取 `AGENTS.md`、`DEFENSIVE_CONSTRAINTS/README.md`、该目录两份 policy、Host/Artifact/Architecture/Asset/Reporting policy；另读 Orchestration、GPL Release、QEMU JNI policy。旧 DroidLayer 路径不授权另建工作区，本次路径采用用户与 dispatch 明确指定的 Orchestella 根。

`research` Skill 调用返回同名歧义错误；随后读取项目 `.agents/skills/research/SKILL.md`。依其 primary-source、单一 canonical 文件要求完成；用户明确禁止子派生，未执行技能中的 background-agent 建议。

本次复用而不重做以下 MAIN 文档：

| 输入 | 对 Phase 5 的有效限制与可用接缝 |
| --- | --- |
| `.scratch/orchestella-platform/map.md`、`spec-m2.md` | Wayland-first；策略中立 `DisplayControl` / `WaylandChannel` / `FrameData`；模式、resize、输入与帧呈现要分别验证。旧 60 FPS、内存数字是承诺/目标文字，不能当当前测量。 |
| `spec-m3.md` | 音频输出、256 KB 文本剪贴板、指针捕获为独立能力；桌面安装失败不能破坏已工作的音频/终端服务。不要借 RFB 顺便启用未经限制的 clipboard/file-transfer。 |
| `spec-m4.md` | `VmHardwareConfig`、原生 presenter 接缝可复用；GPU 开关不是 renderer 成功证据；单运行 Instance、冷恢复、禁止任意 host 挂载仍有效。 |
| `spec-quality-20261013.md` | 真实发行版 PID1、mandatory baseline 验证后才 ready；不得预写 `.firstboot-done`、覆盖用户 init/服务/配置、因无 marker 格式化根盘；默认终端和随机凭据保留；控制只有一个 reader。 |
| `docs/research/2026-10-13-distro-validation-matrix.md` | 七固定 rootfs size/SHA 已验证，不重新下载或改锁；Alma 实际为 9.8、Catalog 为 major 9；Rocky 固定输入为 9.6；Arch 为固定摘要 rolling 快照。既有官方包索引可只读复用。 |
| `2026-10-13-guest-runtime-audit.md` | 已修真实 init/恢复/marker/签名 bootstrap；Ubuntu native systemd/cgroup2/DBus/SSH/DNS 的真机观测属于第一会话，不是本 child 实测。其他发行版与最终模板仍按该报告的待验边界。 |
| `2026-10-13-linux-reference-survey.md` | VM、Root 容器、PRoot、X server 各自边界清楚；不搬 Android AID/禁浏览器 sandbox/禁包签名等 PRoot 补丁。 |
| `2026-10-13-terminal-reference-survey.md` | 持久终端模型与单 `BridgeClient` response pump；初始同 token HELLO 最长 900 秒有界重试，常规请求 5 秒；桌面先打开也复用 `serveSession`，不能新建第二个 control reader。 |
| `2026-10-13-theme-ui-audit.md` | 宿主主题不能覆盖 guest 桌面像素；ThemeApplier 保持布局/状态色与会话，不需要在桌面研究里重写 UI。 |

本 child 只读核对的现有桌面问题：

- `MAIN/guest-assets/scripts/orchestella-desktop-setup.sh:77–110` 只真正适配 Alpine/APT，未识别发行版的 `--check` 却 exit 0；Debian 必装 `sfwbar`，本次完整 trixie main arm64 索引没有此名称；这会阻断整笔 apt 安装。不能仅凭 `ID_LIKE=debian` 套 Ubuntu 包表。
- 同脚本 `:114–121` 固定 UID 1000；`:139–155` `cp -f` 覆盖用户 compositor/bar/terminal/audio 配置，`:194` 无运行验收即打印成功。
- `orchestella-desktop-launch.sh:5–24,40–60,65–80` 混用 root 与 `/run/user/1000`、固定 `wayland-0`、全局 pgrep；回退 Weston 时沿用 WLR 环境但没有明确选择其 headless backend。Unix socket 存在和 compositor 进程存在均不证明画面已送至 Android。
- `MAIN/guest-assets/agent/display-agent.c:141–174` 忽略 `wlr-randr` 返回值并始终成功，`:229–235` FPS 仅 ACK，`:243–253` pointer/touch/key 仅接受而不注入；`FRAME_MAGIC` 只有定义，无 compositor 帧写出路径。该文件仍接收 DROIDLAYER 前缀，当前 BridgeClient 发 ORCHESTELLA 前缀，直接对接须核对兼容处理。不能把已有 JVM/display ACK 叫作真实 GUI。

以上只报告，不修改生产、协议、UI、Gradle、tracker 或 ADR。

## 2. 官方包证据的取得与信任边界

复用第一会话 `Orchestella/artifacts/quality-20261013/distro-inputs/official-metadata` 的完整原始索引，逐个重新检查压缩文件精确 size、SHA-256/SHA-512，再解析 ARM64/noarch/all/any 记录。原文件未改。新增 Noble Universe、EPEL 9、Rocky 9.6 Vault metadata；Noble Universe 本 child 实际运行 gpgv，以固定 rootfs 的 Ubuntu archive **公共** keyring 验 InRelease，再核验 Packages.xz 摘要。没有读取个人 GPG/SSH/Git 凭据。

`EVIDENCE/packages/*.json` 记录版本、架构、包 location/filename、checksum、Depends/Requires；`runtime-dependencies/*.json` 补齐 Mesa、字体、PAM、wlroots、seat 等分包。Arch DB 的 `desc` 和 `depends` 是分开的，完整依赖以 `archlinuxarm-*-with-depends.json` 为准，不能把早期 desc-only 记录里的缺字段解释为“无依赖”。

| 代号与发行版 | 本次核查的官方入口 | 认证/版本边界 |
| --- | --- | --- |
| U，Ubuntu 24.04 Noble | [main arm64](https://ports.ubuntu.com/ubuntu-ports/dists/noble/main/binary-arm64/Packages.xz)、[universe arm64](https://ports.ubuntu.com/ubuntu-ports/dists/noble/universe/binary-arm64/Packages.xz)、[InRelease](https://ports.ubuntu.com/ubuntu-ports/dists/noble/InRelease) | main 复用已验签证据；Universe 本次 gpgv 返回 0、size/SHA 匹配。表是 release 仓版本，不是 noble-updates/security 最新版本。实际安装必须继续启用签名保护的更新仓，重新锁 solver 结果。 |
| D，Debian 13 Trixie | [main arm64](https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.xz) | 复用第一会话签名 InRelease 与固定 size/SHA，重验原始索引。没有混用 sid/testing 替代 stable 包。 |
| F，Fedora 43 | [Everything aarch64 repomd](https://dl.fedoraproject.org/pub/fedora/linux/releases/43/Everything/aarch64/os/repodata/repomd.xml) | release primary 校验通过，实际 RPM 签名/安装未运行；updates 不在表内。 |
| A，Arch Linux ARM rolling | [core.db](https://ca.us.mirror.archlinuxarm.org/aarch64/core/core.db)、[extra.db](https://ca.us.mirror.archlinuxarm.org/aarch64/extra/extra.db) | 固定 DB 摘要复核；包 PGPSIG 字段记录，未执行桌面包签名/事务。官方普通 mirror 既有 TLS 错误没有被绕过；使用第一会话已验证的官方区域节点。当前 DB 与固定 rootfs 的时间可不同。 |
| S，openSUSE Leap 16.0 | [oss aarch64 repomd](https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/aarch64/repodata/repomd.xml) | primary SHA-512 重验；第一会话 repomd 公钥/签名验证可引用，独立 key trust anchor 与 RPM 实装仍未完成。没有用 Tumbleweed 包冒充 Leap。 |
| L，AlmaLinux major 9 / 固定 9.8 | [BaseOS](https://repo.almalinux.org/almalinux/9/BaseOS/aarch64/os/repodata/repomd.xml)、[AppStream](https://repo.almalinux.org/almalinux/9/AppStream/aarch64/os/repodata/repomd.xml)、[显式 9.8 AppStream](https://repo.almalinux.org/almalinux/9.8/AppStream/aarch64/os/repodata/repomd.xml) | 本次显式 9.8 primary checksum 与复用 major9 AppStream 完全一致，见 `alma98-minor-binding.json`。这是本次观测，major9 URL 不永久锁 minor。 |
| R，Rocky 固定 9.6 | [9.6 Vault AppStream](https://dl.rockylinux.org/vault/rocky/9.6/AppStream/aarch64/os/repodata/repomd.xml)，另对照[当前 major9](https://dl.rockylinux.org/pub/rocky/9/AppStream/aarch64/os/repodata/repomd.xml) | Vault primary size/checksum 本次通过；当前 major9 已含 el9_8，不能写成 9.6 包。安装时若用正常 major 更新须显示 minor 变化；不建议为了保留旧版本默认冻结安全更新。 |
| E，Fedora EPEL 9，供 Alma/Rocky 可选扩展 | [Everything aarch64 repomd](https://dl.fedoraproject.org/pub/epel/9/Everything/aarch64/repodata/repomd.xml)、[官方启用说明](https://docs.fedoraproject.org/en-US/epel/getting-started/) | Fedora 官方扩展仓，不是 Alma/Rocky BaseOS 内置桌面。EPEL/CRB 的启用应为明确 guest 操作；完整 closure solver 与 RPM 签名未运行。当前 EPEL 9 不是 Rocky 9.6 时间快照。 |

“未发现”只对上述完整索引集合成立，不代表世界上没有源代码、社区仓或自编译方式。RPM metadata digest 与包签名不是同一个验证。没有使用 `--allow-untrusted`、`--nogpgcheck`、TLS bypass、随意 PPA/COPR 或 `allowerasing`。

## 3. 七发行版的真实桌面包矩阵

以下包已在 arm64/aarch64 索引中找到；Xfce meta 包可为 all，字体可为 noarch/any。表选代表版本，全部 NEVRA/多版本/checksum 位于对应 JSON。**全部桌面 profile 的 guest 安装、启动、输入与 Android 呈现均 NOT RUN。**

| 发行版 | KDE / compositor 包 | labwc / niri | Xfce 与 CPU 诊断 Xvnc | Wayland CPU 捕帧/参考 compositor |
| --- | --- | --- | --- | --- |
| Ubuntu 24.04 U | `plasma-desktop 4:5.27.11-0ubuntu2`；`kwin-wayland 4:5.27.11-0ubuntu3` | `labwc 0.7.1-1build1`；main+universe 未发现 `niri` | `xfce4 4.18`、`xfce4-session 4.18.3-1build2`；`tigervnc-standalone-server 1.13.1+dfsg-2build2` | `wayvnc 0.7.2-1build2`；`wlr-randr 0.3.0-1`；`weston 13.0.0-4build3` |
| Debian 13 D | `plasma-desktop 4:6.3.6-1`、`plasma-workspace 4:6.3.6-2`、`kwin-wayland 4:6.3.6-1` | `labwc 0.8.3-1`；trixie main 未发现 `niri` | `xfce4 4.20.1`、`xfce4-session 4.20.2-2`；`tigervnc-standalone-server 1.15.0+dfsg-2.1~deb13u1` | `wayvnc 0.9.1-1`、`wlr-randr 0.4.1-1`、`weston 14.0.2-1` |
| Fedora 43 F | `plasma-desktop 6.4.5-1.fc43`、`plasma-workspace 6.4.5-4.fc43`、`kwin 6.4.5-1.fc43` | `labwc 0.9.1-1.fc43`；`niri 25.08-1.fc43`，真实 aarch64 包 | `xfce4-session 4.20.2-2.fc43`、`xfwm4 4.20.0-3.fc43`；`tigervnc-server 1.15.0-7.fc43` | `wayvnc 0.9.1-3.fc43`、`wlr-randr 0.5.0-2.fc43`、`weston 14.0.2-2.fc43` |
| Arch Linux ARM A | `plasma-desktop 6.7.5-1`、`plasma-workspace 6.7.5-3`、`kwin 6.7.5-3` | `labwc 0.20.2-1`；`niri 26.04-1`，真实 aarch64 包 | `xfce4-session 4.20.4-1`、`xfwm4 4.20.0-2`；`tigervnc 1.16.2-5` | `wayvnc 0.10.2-1`、`wlr-randr 0.5.0-1`、`weston 15.0.1-3` |
| Leap 16.0 S | `plasma6-desktop 6.4.2-bp160.1.2`、`plasma6-workspace 6.4.2-bp160.1.2`、`kwin6 6.4.2-bp160.1.2` | `labwc 0.8.1-bp160.2.1`，另有 bp160.1.13；`niri 0.1.9-bp160.2.1`，另有 bp160.1.15 | `xfce4-session 4.20.3-bp160.1.1`、`xfwm4 4.20.0-bp160.1.3`；`tigervnc 1.15.0-160000.4.1` 等 | `wayvnc 0.9.1-bp160.1.13`、`wlr-randr 0.4.1-bp160.1.13`、`weston 14.0.0-bp160.1.14` |
| Alma 固定 9.8 L+可选 E | EPEL：`plasma-desktop/kwin-wayland 5.27.12-1.el9`、`plasma-workspace 5.27.12-2.el9` | 本次 BaseOS+AppStream+EPEL 未发现 `labwc`/`niri` | EPEL：`xfce4-session 4.18.3-1.el9`、`xfwm4 4.18.0-2.el9`；AppStream `tigervnc-server 1.15.0-7.el9_8.2` | EPEL `wayvnc 0.7.2-1.el9`、`weston 8.0.0-10.el9`。有 wayvnc 不代表已有兼容 compositor。 |
| Rocky 固定 9.6 R+可选当前 E | 当前 EPEL 同上，但与旧 9.6 的完整依赖事务 UNCERTAIN | 本次 Vault AppStream+当前 BaseOS/AppStream+EPEL 未发现 `labwc`/`niri` | Vault `tigervnc-server 1.14.1-8.el9_6`；当前 EPEL Xfce 4.18，不能称 9.6 发布时包集 | EPEL 同上；当前 major9 TigerVNC 1.15.0 的 el9_8 记录单独保留，不混入 Vault 行 |

其他具体差异：

- U 有 `sfwbar 1.0~beta13-1build2`；D 未发现 sfwbar，有 `waybar 0.12.0-1`。F 有 `sfwbar 1.0~beta16.1-2.fc43`。第一通路先不装 bar，避免把可选桌面装修变成核心失败点。
- XWayland：U `xwayland 2:23.2.6-1`；D `2:24.1.6-1`；F `xorg-x11-server-Xwayland 24.1.8-2.fc43`；A 实际名 `xorg-xwayland 24.1.14-1`；S `xwayland 24.1.6-160000.*`；R Vault 为 `23.2.7-*.el9_6`，当前 L/R 仓为 `24.1.9-*.el9_8`。
- niri 的 X11 兼容要单列 `xwayland-satellite`：A `0.8.3-1`、F `0.7-1.fc43`、S `0.5-bp160.1.13`。S 的 niri 0.1.9 不能套最新 niri 25.08+ 的自动集成行为。
- Xfce 4.18 用作 X11/Xvnc 对照；Xfce 4.20 部分组件已有 Wayland 能力，不能因此宣称整套 xfwm4/Xfce session 已是完整 Wayland desktop。[Xfce 官方路线图](https://wiki.xfce.org/releng/wayland_roadmap)
- A 安装应使用签名保护的完整升级事务并记录新包集，不能 `pacman -Sy` 后零散装新包制造 partial upgrade；这不授权改固定 rootfs manifest。

## 4. renderer、seat、DBus、登录与字体的实际依赖

### 4.1 labwc + wayvnc

U 的 labwc 依赖 `libwlroots12t64 >=0.17`，官方索引为 `0.17.1-2.1build1`；D 依赖 `libwlroots-0.18`，索引为 `0.18.2-3`；S RPM Requires 为 `libwlroots-0.18.so`；A labwc Requires 为 `libwlroots-0.20.so`，DB 中 `wlroots0.20 0.20.2-1`。这些 ABI 不能混用。labwc 还依赖 Cairo/Pango/pixman/Wayland/libinput/xkbcommon 与字体，U/D labwc 包本身要求 XWayland，因此 `--no-install-recommends` 也不会消掉所有 X11 closure。

wlroots 0.17/0.18 官方环境文档明确：`WLR_BACKENDS=headless`、`WLR_RENDERER=pixman`、`WLR_HEADLESS_OUTPUTS=1` 可选取 CPU headless 通路；`WLR_RENDERER_ALLOW_SOFTWARE=1` 是 **GLES2 renderer** 允许软件 GL 的开关，与 pixman 不是同一回事。[0.17.1 env](https://gitlab.freedesktop.org/wlroots/wlroots/-/blob/0.17.1/docs/env_vars.md) / [0.18.2 env](https://gitlab.freedesktop.org/wlroots/wlroots/-/blob/0.18.2/docs/env_vars.md)

明确只选 headless 时，不需要取得 guest DRM/input 的物理 seat 权限；客户端输入由 wayvnc 使用虚拟 pointer/keyboard 协议注入。不能因没显示器就默认跑 DRM backend，更不能对 Android 执行 Root、SELinux、驱动或权限修改。若将来选择 guest DRM，则需真实 kernel virtio-gpu/DRM 节点、udev、libseat/logind 或 seatd 的正常 guest 登录授权；不要同时盲启两个 seat 管理方案。

wayvnc 需要 Wayland capture、virtual keyboard/pointer，依赖 neatvnc、aml、pixman、xkbcommon、jansson。U 依赖 `libneatvnc0 >=0.7.1`，D `>=0.9.1`；U/D binary 还有 libdrm/libgbm/libpam 依赖，即便本次选择 CPU 仍由包管理器解决库 closure，不能删掉认为“GPU 才用”的动态库。标准 Wayland socket 内含 FD 语义，不会因用 socat/TCP/vsock 转发字节就自动跨 VM 共享 SHM。

### 4.2 普通用户与会话

安装包可以由 guest root 完成，**compositor、wayvnc、客户端统一由选定的普通 guest 用户运行**。UID 从 passwd/login 身份取得，不固定 1000，不让 root 使用另一个用户的 runtime dir。不复用或重写已有登录密码，不输出 password/token。当前基础环境的 root 终端不证明存在桌面普通用户：首次 profile 必须显式选已有用户，或在用户确认安装 profile 时创建独立普通用户/home；保留既有账号、UID、sudo policy 和 root 凭据。需要正常密码登录时生成一次独立随机凭据并安全交付，不能把 marker 缺失当再次重置密码的理由。

Linux PAM 登录配合 `pam_systemd` 创建 `/run/user/$UID`、用户 systemd manager；XDG runtime directory 应由该 UID 独占、0700、生命周期属于登录会话。`su`/`sudo` 改变用户并不保证产生原始用户的 XDG runtime 环境。没有正确 user bus/runtime 时明确 `login-required`，或由独立管理入口创建严格权限的临时 runtime + `dbus-run-session`，不能 `chmod 777 /run/user/1000`。[pam_systemd](https://www.freedesktop.org/software/systemd/man/latest/pam_systemd.html) / [XDG Basedir spec](https://specifications.freedesktop.org/basedir-spec/latest/)

| 家族 | session/login 包与 renderer/font 最小关注点 |
| --- | --- |
| U/D | `libpam-systemd`、`dbus-user-session`；沿用真实 systemd、DBus 和登录服务。`fontconfig fonts-noto-core fonts-noto-cjk`；U Mesa `libegl-mesa0/libgl1-mesa-dri 24.0.5-1ubuntu1`、D `25.0.7-2+deb13u1`（表内仓版本）。 |
| F | `systemd-pam 258-1.fc43`、dbus-broker/daemon 实际 provider；`google-noto-sans-fonts`、`google-noto-sans-cjk-fonts` 或 `-vf-fonts`；`mesa-libEGL mesa-libgbm mesa-dri-drivers 25.2.4-2.fc43`。 |
| A | systemd/PAM/DBus 实际登录与 user manager；`seatd` 提供相关 seat 库，安装不等于必须启其 daemon；`noto-fonts noto-fonts-cjk fontconfig`；Mesa `1:26.2.4-1`。 |
| S | systemd/logind/PAM、`dbus-1`/实际 user bus provider；`google-noto-sans-fonts google-noto-sans-cjk-fonts fontconfig`；`Mesa-dri Mesa-libEGL1 Mesa-libGL1 24.3.3-160000.*`。 |
| L/R | `systemd-pam`、dbus-broker/daemon 等实际 provider；`google-noto-sans-fonts google-noto-sans-cjk-ttc-fonts fontconfig`；L9.8 Mesa `25.2.7-*.el9_8.alma.1`，R9.6 Vault `24.2.8-*.el9_6`；当前 R major9 是另一版本线。 |

UTF-8 locale、包库存和 `fc-match` 找到字体仍不等于实际 CJK/emoji 渲染无缺字；最终要在真实 foot/GUI 中观察。安装 profile 不覆盖 `/etc/profile.d/00-orchestella-env.sh`、terminal integration、PulseAudio 或用户 fontconfig 设置。

### 4.3 niri 与 KDE 的独立限制

niri 当前维护源是 [niri-wm/niri](https://github.com/niri-wm/niri)，旧 YaLTeR URL 跳转到该源。本次固定 v26.04 的 Cargo 依赖 **Smithay**，并非 wlroots；支持 wlr 协议不等于使用 wlroots。

- `Cargo.toml:28–37,102–117` 有 DRM/EGL/GBM/libinput/libseat、GLES 与 pixman feature；真正 backend 的 renderer API 使用 `GlesRenderer`。不能因 dependency feature 写 `renderer_pixman` 就宣称用户可选 `WLR_RENDERER=pixman niri`。
- `src/backend/headless.rs:1,12–51` 明写 “Headless backend for tests”，初始化 surfaceless EGL/GLES；`src/main.rs:178–185` 传 headless=false；`src/niri.rs:715–729` 正常入口依 existing DISPLAY 选择 nested winit，否则 TTY。当前源码存在测试 headless 不能当已发布无 GPU 桌面 CLI。
- 官方固定文档 `docs/wiki/Getting-Started.md` 的 “Virtual Machines” 要求 VM 启用 3D；ARM/kmsro 设备可能需明确 render node；`niri-session`/`--session` 负责 user systemd/DBus/portal 环境。本文未编译 niri，未证明 llvmpipe+surfaceless/TTY 可用。
- 最新源码有 screencopy 和 virtual pointer/keyboard，允许继续研究与 wayvnc 的协议组合，但需要逐发行版版本验证；不能从最新源码推出 S 的 0.1.9 或 F 的 25.08 全部同等支持。

KDE 是 Plasma、KWin、Qt/KF、DBus、用户 session、portal/polkit、字体等组合，不是安装 `plasma-desktop` 一个包即可。KWin 6.3.6 有 `--virtual` backend 和 QPainter 软件实现，但该输出如何捕帧、如何接 Android 和输入仍未验；**wayvnc 官方明确不支持 KDE/GNOME/Weston**，不得 `startplasma-wayland && wayvnc` 当通用方案。KDE CPU profile 需另做 KWin/PipeWire/KRDP 或输出捕获研究，不作为首通路。

Mesa 官方把 llvmpipe 描述为 LLVM 软件光栅化；存在 Mesa 包或 host EGL 不等于 guest VirGL 硬件渲染成功。[Mesa llvmpipe](https://docs.mesa3d.org/drivers/llvmpipe.html)

## 5. 首个 CPU 组合与普通用户启动建议

这是**未执行的最小实施建议**，交给 owning worker，不能直接把下述示例写进本 child 的生产模块。

### 5.1 独立 guest 安装器与最小包集合

新独立桌面 installer 先要求基础 `orchestella-guest-provision` 的 verified/PID1/session 条件满足。U/D 首通路只请求：

```sh
# guest 内，由被授权的安装入口执行；本研究未运行。
apt-get update
apt-get install --no-install-recommends \
  labwc wayvnc foot wlr-randr \
  fontconfig fonts-noto-core fonts-noto-cjk \
  libpam-systemd dbus-user-session
```

实际实现要先做 solver/preflight、记录真实版本与下载体积、分阶段有期限事务；保留签名/CA/既有更新源。labwc 的依赖会拉取其要求的库/XWayland。bar、浏览器、音频、GPU benchmark 是后续选项；不因某个 benchmark/可选 bar 不存在而破坏首组合。Mesa EGL/DRI 在需要 GLES 客户端时作为独立依赖组，不把 `virgl_dri.so` 存在当硬件能力验证。

默认建议从 **1280×720、scale=1、上限 15 FPS** 起测，属于减少 CPU/流量的实验参数，不是测得帧率或最终 SLA。RGBA 单帧 3,686,400 B、两帧 7,372,800 B 是计算预算，实际总内存另测；尺寸、stride、payload 必须有独立上限与溢出检查。

### 5.2 普通用户 session 及 user service

以已通过 PAM 正常登录的 guest 用户启动。验证 UID 非零，XDG_RUNTIME_DIR owner 为该 UID、0700，`systemctl --user`/DBus 可用。配置放新 profile 专属路径 `~/.local/share/orchestella/desktop/labwc-cpu-v1/`，不覆盖 `~/.config/labwc`。

下面展示 worker 可生成的 user unit 的关键部分；profile 文件及 after-start 脚本目前没有实现/安装：

```ini
[Unit]
Description=Orchestella experimental CPU Wayland desktop

[Service]
Type=simple
UMask=0077
Environment=WLR_BACKENDS=headless
Environment=WLR_RENDERER=pixman
Environment=WLR_HEADLESS_OUTPUTS=1
Environment=XDG_SESSION_TYPE=wayland
Environment=XDG_CURRENT_DESKTOP=labwc
Environment=LABWC_UPDATE_ACTIVATION_ENV=0
RuntimeDirectory=orchestella-desktop
RuntimeDirectoryMode=0700
ExecStart=/usr/bin/env -u DISPLAY -u WAYLAND_DISPLAY -u WAYLAND_SOCKET /usr/bin/labwc -C "%h/.local/share/orchestella/desktop/labwc-cpu-v1" -s "%h/.local/share/orchestella/desktop/labwc-cpu-v1/after-start"
Restart=no
KillMode=control-group
TimeoutStopSec=10
```

用户显式 `systemctl --user start orchestella-desktop-labwc.service`；安装不默认 enable、自启或启用 lingering。停止只结束本 unit 的子进程，不 `pkill labwc` 影响用户其他 session，不停止 Linux/terminal/SSH。此 unit 不需要 SDDM/LightDM，也不切换系统默认 target。

labwc 为 after-start 设置实际 `WAYLAND_DISPLAY`；after-start 首先探测 socket/protocol/唯一 headless output，选实际 output 名，使用命令参数数组执行：

```sh
wlr-randr
# OUTPUT 来自上一步真实输出枚举，不固定为 WL-1/wayland-0。
wlr-randr --output "$OUTPUT" --custom-mode 1280x720 --scale 1
# 读回实际 mode/scale，失败不能继续发布 desktop-ready。
foot --title='Orchestella guest desktop' &
wayvnc --config "$HOME/.local/share/orchestella/desktop/labwc-cpu-v1/wayvnc.conf" \
  --output "$OUTPUT" --unix-socket --max-fps 15 \
  "$XDG_RUNTIME_DIR/orchestella-desktop/rfb.sock"
```

after-start 最终应由 session supervisor 监测 foot/wayvnc 和捕帧失败；仅 Type=simple unit 的 active 仍不能充当 desktop-ready。无 user manager 时，可以在有效的普通用户 runtime 下以 `dbus-run-session -- env WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_HEADLESS_OUTPUTS=1 labwc ...` 做诊断；不要从 root 的 `$HOME`/DBus 环境直接 `su` 到 UID1000 冒充登录。

### 5.3 wayvnc 的 Unix socket、认证、resize 与输入

已核对官方 v0.7.2/v0.9.1 源码：

- `--unix-socket/-u` 把**位置参数 address**解释为 RFB Unix 监听路径；`--socket/-S` 是另一条 wayvncctl 管理 IPC。两者不能混淆。AF_UNIX socket 在 guest kernel 内，host Android 看不到，仍需原创、鉴权、有限字节 relay。
- 私有 Unix RFB 方案要求 runtime 0700、socket 权限限制、relay 持有正确 guest UID/会话身份，host→relay 经已有 per-instance token 鉴权。只有这个内部通路可用 `enable_auth=false`，且不能改成 TCP/WebSocket。认证 relay 未实现/不就绪时停止，不开放无认证网络 listener。
- 若选择 guest TCP/用户可见网络 VNC，则必须显式 `127.0.0.1`、每实例随机独立凭据、TLS/VeNCrypt 与客户端身份校验；配置/key mode0600，不把密码放 argv、日志或报告。`enable_auth=true` 但未提供所需凭据/证书应失败，不能降到固定密码或 DES。PAM 使用实际 guest 登录策略，不能借此重置系统账户密码。
- v0.9.1 默认启用自动 resizing，`-R/--disable-resizing` 关闭；实现 `on_client_resize` 使用 wlr-output-management，需兼容 compositor/headless 输出及客户协商扩展。不要强行假设 v0.7.2 有同一回调：本次 v0.7.2 源码无该布局 resize 路径。U 首通路可由既有 display 控制 adapter 调 wlr-randr 后读回，D 可另验 RFB resize；两种状态都要等待新尺寸的实际帧。
- 输入需 Wayland virtual pointer/keyboard，缺协议时 wayvnc 提示可 `--disable-input`，这种情况只能显示为 view-only，不得宣称可交互。RFB 键盘/剪贴板不自动满足 Android composing/CJK/text-input-v3 契约；首验 ASCII 键鼠，IME 单独验证。既有 bounded clipboard bridge 保留，RFB clipboard 默认不额外开启。

### 5.4 Weston 与 Xvnc 对照

Weston 14.0.2 源码有独立 headless 和 VNC backend。`weston --backend=headless --renderer=pixman ...` 只创建 compositor 输出，不会自动给 Android 发送 pixels；**wayvnc 不支持 Weston**。VNC backend 为另一通路，依赖 neatvnc/aml，并要求 `/etc/pam.d/weston-remote-access`、同用户认证、TLS。官方手册默认地址 0.0.0.0，实验必须显式限制 127.0.0.1。本次官方 [Noble arm64 libweston-13-0 filelist](https://packages.ubuntu.com/noble/arm64/libweston-13-0/filelist) 与 [Trixie arm64 libweston-14-0 filelist](https://packages.debian.org/trixie/arm64/libweston-14-0/filelist) 均明确列出 `headless-backend.so` 和 `vnc-backend.so`；这是实际包文件清单证据，尚未加载模块或运行认证。其余发行版仍需核对模块/包构建配置，尤其 EPEL Weston 8 不得套 14 的 backend 文档。

D 的最小 stock Weston VNC 实验命令是 `weston --backend=vnc --renderer=pixman --address=127.0.0.1 --width=1280 --height=720 --vnc-tls-key=<该guest用户私有key> --vnc-tls-cert=<该guest用户证书>`；PAM policy 与认证凭据准备失败则拒绝，不用 `--disable-transport-layer-security` 绕过。U13 的 renderer/CLI flags 应按13版再验，不能照抄14版参数。

Xvnc+Xfce 可在 U/D 用 TigerVNC standalone server、xfce4-session/xfwm4、fonts、DBus 构建不依赖 DRM 的 X11 诊断对照；用 `dbus-run-session -- startxfce4` 放在专属 TigerVNC session 启动入口，以普通用户、loopback、随机凭据启动。它是独立 X server/framebuffer，不能标为 Wayland。它的成功可以定位基础用户/session/客户端问题，不能替代 labwc CPU profile 的验收，也不静默成为正式默认路线。

## 6. 安装、取消、重试、marker 与用户配置保全

基础 Linux 与桌面状态必须分开：不读写基础 `.firstboot-done` 来表示 GUI。建议新桌面状态根 `/var/lib/orchestella/desktop/<profile>/`，用户会话运行状态放 `$XDG_RUNTIME_DIR/orchestella-desktop/`，与 Instance、Session 的实体关系分开。

| 阶段/状态 | 检查和持久记录 | 取消/失败/恢复 |
| --- | --- | --- |
| `uninstalled` → `planning` | distro id/真实 version/arch、profile revision、配置所有者、repo/key、solver 包版本/体积/空间；先确认 kernel/PID1/base verified | 不改源、不安装；不支持的组合明确 unsupported，禁止未知发行版 check-success。 |
| `installing` | 每实例+profile 锁；实际 package-manager transaction、stage/attempt/exit；仅 guest root 执行包安装 | 下载/索引阶段可终止本 job；进入 dpkg/rpm/pacman/zypper 写数据库阶段只请求取消，在安全事务边界停止，不 SIGKILL、不清系统锁文件。无法立即取消显示 cancelling。 |
| `verifying-packages` | 查询已安装版本/架构/owner、ELF/loader、相关 protocol helper/字体与动态依赖 | 包命令 exit0 不够；失败保存 checkpoint，不写 installed。下一次显式 retry 检查包数据库修复需求；APT 用正常 configure/audit/依赖修复流程，RPM/pacman/zypper 用各自事务/锁机制，不跨包族套命令。 |
| `configuring` | 新 profile 配置先 staging，路径 owner/type/无 symlink 越界；用 O_EXCL/受管路径发布 | 只创建缺失配置；用户文件不 cp-f、不改 UID/密码/profile。受管文件若 digest 与上次不符视为用户修改，保留并给新模板另名/显式冲突提示，不覆盖。 |
| `installed` | 包版本、profile/schema、配置摘要、基线身份与已完成步骤；同目录 temp+fsync+rename+目录 fsync | 只代表安装，不能写 GUI ready。没有 snapshot 时不能承诺软件包事务完全回滚；取消后已安装包保留并准确列出，基础终端照常用。 |
| `starting` → `socket-ready` → `capture-ready` → `presented` | Session generation、UID、实际 WAYLAND_DISPLAY/output、renderer、协议、buffer/sequence、host 呈现结果 | 不持久复用上次 session-ready；冷启动新 generation 重验。旧 socket/PID 文件只有确认属本 profile 且无活进程才处理，不清用户整个 runtime。 |
| `failed` / `cancelled` / `interrupted` | reason/stage、已变更内容、resume checkpoint、显式 retry 入口 | 无无限后台重试；锁回收核对 owner PID/boot identity。桌面错误不触发 VM reformat、密码重置或必需服务停止。 |

安装器与 session supervisor 不得拥有共享 guest-init/bridge 的独立写入权；调用既有鉴权控制通道，未知状态保持失败。恢复时把 package checkpoint、managed-config marker、session generation 分开；marker 缺失不代表文件/用户配置可删除。

## 7. 从启动到真实帧握手的验收阶段

wayvnc v0.7.2 `src/main.c:673–704,806`、v0.9.1 `:845–883,992` 会在真实捕帧前发送灰色 placeholder，断开 compositor 后也可能 blank_screen。**所以版本握手、socket 可连、ServerInit、首次 RFB frame 甚至固定灰色画面均不足以说明真实桌面可用。**

建议固定以下有期限阶段，各阶段独立 reason，不用一个 ready 布尔值掩盖区别：

1. **base-ready**：真实 native PID1、mandatory baseline、普通用户/login/user bus 合规；是前置条件，不是 desktop。
2. **session-started**：本 profile 子进程 group 启动、UID/runtime/config 符合预期；generation 与当前 Instance/Session 对应。
3. **compositor-ready**：在正确用户的 socket 完成 Wayland registry roundtrip，确有 output 和必需捕帧/输入协议；实际 mode/scale 读回。监听 socket 存在不能代替 roundtrip。
4. **capture-ready**：明确捕帧成功事件、实际 surface/foot 已 map、renderer=CPU/pixman、有效 buffer format/stride/尺寸/长度；不能消费灰色占位帧写 ready。
5. **transport-ready**：relay 完成 token 鉴权与有限 RFB/帧格式协商；选择明确像素格式/encoding，检查超大矩形、越界、截断、resize 代际、EOF、backpressure，停止错 token/未知编码。控制 reader 仍唯一，图形数据面单独拥有 reader。
6. **host-presented**：host 实际解码本次真实 compositor frame，交给 existing FrameData/presenter 并确认 present；只取目标 FPS 不能显示为观测 FPS。
7. **interactive-verified**：真实 foot/测试应用键鼠输入改变内容，后续 damage/frame 序号增加，宿主看到该变化；旋转/resize 读回新 mode，并呈现新 generation 新尺寸的真实帧；实际 Unicode/IME、clipboard、音频另记能力。

真实验证可以让普通 foot 显示本次合成诊断文本并用键盘改动它，结合 capture 状态与实际内容变化证明链路；这是客户端真实绘制，不得用宿主模板图片/agent 生成彩条冒充桌面。只比较 hash 不能判断是正确内容，更不能将 guest/host 不同时间的 `CLOCK_MONOTONIC` 数字直接相减冒充端到端延迟。FPS/延迟/CPU 要说明采样点与方法，没有数据就显示 unavailable。

raw pixman buffer → guest capture → RFB/受限字节 relay → host decoder → FrameData → 原生 presenter 是明确 CPU-copy 路线。Wayland compositor、RFB 传输、host GPU 纹理呈现、guest GPU 3D 是四项不同事实；原生 host GLES 显示不授予 `GPU_HARDWARE_ACCELERATION`。

应由后续实施票运行的负例（全部 NOT RUN）：包安装失败/缺 inventory/签名错误不 installed；取消写事务不损数据库；用户配置修改保留；未知 UID/runtime owner 拒绝；协议缺失降 view-only；占位帧不能 ready；错 token、坏矩形/stride、超 payload、迟到旧尺寸帧拒绝；Wayland socket关闭/RFB EOF 后 ready 撤回；断网显式 retry；停止桌面不影响终端；cold restart 不复用 last-ready；两后端分别实测，无旧 artifact/mocks 替代。

## 8. profile 状态与实施顺序

| Profile | 包/源码事实状态 | 安装/运行/真实帧 | 建议 |
| --- | --- | --- | --- |
| `labwc-cpu` U | 官方签名包元数据确认；WLR headless/pixman 与 wayvnc0.7.2 Unix listener 有源码依据 | NOT RUN；U 的基础 init 是第一会话已有观测 | 首次真实 CPU 桌面目标；resize 用独立 wlr-randr adapter+读回，标 RFB copy。 |
| `labwc-cpu` D | labwc0.8.3+wlroots0.18、wayvnc0.9.1、有 resize 源码 | NOT RUN | 第二个验证目标；不必装 sfwbar；优先验证自动 resize 与虚拟键鼠。 |
| `labwc-cpu` F/A/S | ARM64 包存在，版本/ABI 各异 | NOT RUN | solver、登录、输出协议、帧、输入、冷恢复逐发行版验证；不合并成“一次通过全部支持”。 |
| `labwc-cpu` L/R | 本次指定仓无 labwc；有 wayvnc 不够 | BLOCKED_PACKAGE_PROFILE / NOT RUN | 保留 terminal；研究批准的 compositor 替代/受控构建，不自动启外部社区仓或复制二进制。 |
| `weston-cpu-headless` | 官方源码可创建 CPU 输出 | NOT RUN，headless 本身没有 host 帧通道 | 诊断 compositor；捕获 adapter 要另做。 |
| `weston-vnc` U/D/F/S 等 | U13/D14 官方 ARM64 filelist 确认 backend；F/S 等实际打包仍待确认 | NOT RUN | PAM/TLS/loopback；EPEL8版本不套用14功能。 |
| `niri` F/A/S | 本次真正找到 aarch64 包；Smithay/GLES/seat 事实确认 | EXPERIMENTAL / NOT RUN | 先真实 EGL/DRM/3D 与版本-specific protocol gate；U/D/L/R 暂无本次官方包证据，不能营销通用支持。 |
| `kde-wayland` U/D/F/A/S、L/R+E | 包存在，KWin独立架构，wayvnc非兼容方案 | EXPERIMENTAL / NOT RUN | Qt/KF/session/capture/input closure 后验；不先选为最低变量 CPU 组合。 |
| `xfce-xvnc` | 各族有包，L/R需 EPEL/CRB 等扩展前提 | DIAGNOSTIC / NOT RUN | 保留 X11 对照；不替换 Wayland-first、不把Xfce部分Wayland组件当整套Wayland。 |
| `waypipe` | 代理 Wayland **客户端**与 buffer，需本地 compositor | REFERENCE_ONLY / NOT RUN | Android没有因SurfaceView自动变成Wayland server；不是可直接嵌入的全桌面视频方案。 |
| `noVNC` | Web RFB client，可用于获准的独立诊断 | REFERENCE_ONLY / NOT RUN | 需要WebSocket/RTC等transport与安全边界；不静默新增WebView/无认证web server，也不冒充原生输入/IME。 |

最低实施顺序：先只实现独立 installer/session supervisor 与诚实状态；再接原创鉴权 CPU-copy 帧 adapter；在一个 U Instance 中完成真实 foot 显示与键鼠；随后 D resize/IME/冷恢复；其余发行版依实际包/ABI 分别开放实验状态。任何步骤都保留终端可用，不把失败 profile 提升为 validated。

## 9. 新参考源码：维护源、固定 revision、许可与源码索引

下载前逐项确认维护源、公开版本标签/许可与 `REF/<name>` 不存在。新增六项均 `--depth 1 --single-branch --no-tags`；niri 最初完整 checkout 被 Windows `Configuration:-Animations.md` 非法文件名阻断，随后对**本次新 clone**稀疏检出 `src/niri-config/niri-ipc/resources`。单命令 `core.protectNTFS=false` 仅允许索引记录被排除的路径，没有写系统或全局 Git 配置，没有 materialize wiki 路径。其余既有参考未 fetch/pull/update，未初始化 submodule、未构建/运行上游脚本。

GitHub API 公开查询返回限流 403 后转官方网页/raw/git tags，没有使用 credential/gh login 绕过；waypipe 首次 ls-remote 连接 reset 记录保留，随后官方 API/许可 GET 和一次浅 clone 成功。所有失败证据保留在 EVIDENCE，不包装成成功。

| 项目 / 固定本地 tag | 官方维护源 / peeled commit | 许可事实与本次用途 |
| --- | --- | --- |
| labwc / 0.8.3 | [labwc/labwc](https://github.com/labwc/labwc)，`1fe4797a9d29b5094c9e38c336752d7f57ed593f` | `meson.build:5` / source SPDX 为 GPL-2.0-only；核对 compositor接口，参考不进入Apache代码。U0.7.1另取官方固定源文件，不假装clone0.8.3就是U源码。 |
| niri / v26.04 | [niri-wm/niri](https://github.com/niri-wm/niri)，`8ed0da44d974c32c6877d2f4630c314da0717ecb` | `Cargo.toml:12` GPL-3.0-or-later；Smithay/GLES/正常启动与测试headless边界。 |
| weston / 14.0.2 | [freedesktop wayland/weston](https://gitlab.freedesktop.org/wayland/weston)，`015b3b4d4c05da44a22349ea6e651d1a8f678c59` | COPYING MIT/Expat，组件仍需逐文件许可；本次核对CPU和VNC backend。官方tags已见16.0.0，clone14是为了对照D/F包，未追latest。 |
| waypipe / v0.11.2 | [freedesktop mstoeckl/waypipe](https://gitlab.freedesktop.org/mstoeckl/waypipe)，`a1ffdd8d0f44cbdb25a3edd1c6adc0a30cfcf754` | README:126–129明确 Rust waypipe GPLv3-or-later、waypipe-c MIT；不能把0.11全仓概括成MIT。 |
| noVNC / v1.7.0 | [novnc/noVNC](https://github.com/novnc/noVNC)，`63107bd06d9e1f6136ff21aeda8cd62cbf0d433e` | core MPL-2.0；LICENSE:4–55列UI BSD、字体OFL、pako MIT等组件，按实际引入文件留notice，不当成全MIT。 |
| kwin / v6.3.6 | [KDE plasma/kwin](https://invent.kde.org/plasma/kwin)，`b8de4329447824b1b1e7a36b3a57acfd069f1423` | 多SPDX许可；所读main/compositor/virtual backend为GPL-2.0-or-later，LICENSES逐组件；核对D版本虚拟/QPainter路径，不复用代码。 |

wayvnc 没有越权新增到参考根；官方 v0.7.2 commit `4de85acadbabdcebad0f8d8076f6c9f7df6052b0`、v0.9.1 `e4ec935d29c0aa2de38d9c12dd30d682f83b91c2` 的公开单文件与 ISC COPYING 放 EVIDENCE/upstream；它是源码研究证据，不是新增生产依赖或已验二进制。

关键源码引用均为固定 revision 的 1-based 行号：

| 结论 | 官方源码位置 |
| --- | --- |
| labwc ABI、许可、pixman依赖 | [meson.build:1–75](https://github.com/labwc/labwc/blob/1fe4797a9d29b5094c9e38c336752d7f57ed593f/meson.build#L1) |
| labwc renderer、SHM、捕帧与实际socket | [server.c:462–538,643,716–738](https://github.com/labwc/labwc/blob/1fe4797a9d29b5094c9e38c336752d7f57ed593f/src/server.c#L462)；[seat.c:468–565](https://github.com/labwc/labwc/blob/1fe4797a9d29b5094c9e38c336752d7f57ed593f/src/seat.c#L468) |
| 专属配置/startup/DBus环境 | [labwc.1.scd:35–118](https://github.com/labwc/labwc/blob/1fe4797a9d29b5094c9e38c336752d7f57ed593f/docs/labwc.1.scd#L35)；[config autostart:62–66](https://github.com/labwc/labwc/blob/1fe4797a9d29b5094c9e38c336752d7f57ed593f/docs/labwc-config.5.scd#L62) |
| U版本labwc捕帧global | [0.7.1 server.c:395](https://github.com/labwc/labwc/blob/0.7.1/src/server.c#L395) |
| wayvnc0.7.2 Unix RFB、认证、占位帧 | [main.c:673–806,1396](https://github.com/any1/wayvnc/blob/4de85acadbabdcebad0f8d8076f6c9f7df6052b0/src/main.c#L673) |
| wayvnc0.9.1 resize、认证、占位帧、真正capture | [main.c:802–843,845–1019](https://github.com/any1/wayvnc/blob/e4ec935d29c0aa2de38d9c12dd30d682f83b91c2/src/main.c#L802)；[选项:1896–1944,1984](https://github.com/any1/wayvnc/blob/e4ec935d29c0aa2de38d9c12dd30d682f83b91c2/src/main.c#L1896) |
| wayvnc仅适配指定Wayland协议/非KDE、headless建议 | [README:7–11](https://github.com/any1/wayvnc/blob/e4ec935d29c0aa2de38d9c12dd30d682f83b91c2/README.md#L7)；[FAQ:3–10](https://github.com/any1/wayvnc/blob/e4ec935d29c0aa2de38d9c12dd30d682f83b91c2/FAQ.md#L3) |
| niri Smithay而非wlroots、GPL、rendererfeatures | [Cargo.toml:8–37,102–139](https://github.com/niri-wm/niri/blob/8ed0da44d974c32c6877d2f4630c314da0717ecb/Cargo.toml#L8) |
| niri正常入口不选测试headless | [main.rs:178–185](https://github.com/niri-wm/niri/blob/8ed0da44d974c32c6877d2f4630c314da0717ecb/src/main.rs#L178)；[headless.rs:1–51](https://github.com/niri-wm/niri/blob/8ed0da44d974c32c6877d2f4630c314da0717ecb/src/backend/headless.rs#L1)；[niri.rs:715–729](https://github.com/niri-wm/niri/blob/8ed0da44d974c32c6877d2f4630c314da0717ecb/src/niri.rs#L715) |
| niri登录/VM/ARM说明 | [Getting-Started.md](https://github.com/niri-wm/niri/blob/8ed0da44d974c32c6877d2f4630c314da0717ecb/docs/wiki/Getting-Started.md)，固定文件保全 `upstream/niri-2604-getting-started.txt`；章节 Virtual Machines / Asahi, ARM / Slower start |
| Weston VNC无需图形硬件，PAM/TLS/监听/resize | [weston-vnc.man:8–87](https://gitlab.freedesktop.org/wayland/weston/-/blob/015b3b4d4c05da44a22349ea6e651d1a8f678c59/man/weston-vnc.man#L8)；[backend-vnc/vnc.c:1167–1184,1217–1272](https://gitlab.freedesktop.org/wayland/weston/-/blob/015b3b4d4c05da44a22349ea6e651d1a8f678c59/libweston/backend-vnc/vnc.c#L1167)；[meson依赖](https://gitlab.freedesktop.org/wayland/weston/-/blob/015b3b4d4c05da44a22349ea6e651d1a8f678c59/libweston/backend-vnc/meson.build#L1) |
| KWin虚拟后端与QPainter | [main_wayland.cpp:309,450](https://invent.kde.org/plasma/kwin/-/blob/b8de4329447824b1b1e7a36b3a57acfd069f1423/src/main_wayland.cpp#L309)；[virtual_backend.cpp:74–90](https://invent.kde.org/plasma/kwin/-/blob/b8de4329447824b1b1e7a36b3a57acfd069f1423/src/backends/virtual/virtual_backend.cpp#L74)；[compositor_wayland.cpp:87–119,143–165](https://invent.kde.org/plasma/kwin/-/blob/b8de4329447824b1b1e7a36b3a57acfd069f1423/src/compositor_wayland.cpp#L87) |
| waypipe代理职责及分许可 | [README:4–32,126–129](https://gitlab.freedesktop.org/mstoeckl/waypipe/-/blob/a1ffdd8d0f44cbdb25a3edd1c6adc0a30cfcf754/README.md#L4)；[waypipe.scd:22–40,164](https://gitlab.freedesktop.org/mstoeckl/waypipe/-/blob/a1ffdd8d0f44cbdb25a3edd1c6adc0a30cfcf754/waypipe.scd#L22) |
| noVNC许可和WebSocket/RTC入口 | [LICENSE.txt:4–55](https://github.com/novnc/noVNC/blob/63107bd06d9e1f6136ff21aeda8cd62cbf0d433e/LICENSE.txt#L4)；[rfb.js:90–118](https://github.com/novnc/noVNC/blob/63107bd06d9e1f6136ff21aeda8cd62cbf0d433e/core/rfb.js#L90) |

引用上游 GPL/MPL 原理不授权代码复制；本研究未引入第三方代码/库到 App。若未来实际打包 guest/compositor 二进制或改造程序，按 ADR0014 与 GPL Release Policy 记录精确源码、构建输入、notice/source obligation；公开 rootfs/package 下载与 APK 分发边界分别审查。

## 10. 保留第一会话 init/ready 修复的最小生产依赖清单

这是协调者请求的路径/调用关系清单，所有路径相对 MAIN；**本 child 没有复制、覆盖或生成这些文件**。实际逐文件 SHA、diff、是否纳入隔离基线由协调者负责，不能把整份 dirty 主仓机械复制。

| 必要闭包 | 调用关系与保留理由 |
| --- | --- |
| `guest-assets/initramfs/runtime-functions.sh`、`runtime-entry.sh`；`guest-assets/scripts/orchestella-guest-provision.sh` | dispatcher选择真实systemd/BusyBox PID1，数据盘创建签名、输入校验、持久包/配置/服务ready与恢复阶段；是桌面所需正确Linux基线。 |
| `guest-assets/shell/20-orchestella-terminal-integration.sh`、`orchestella-prompt.sh`；`guest-assets/scripts/sync-guest-init.py` | sync脚本:13–28,64–85嵌入canonical runtime、hook和prompt；不维护第二套脚本。terminal模块由其owner保持。 |
| `guest-assets/initramfs/init`、`app/src/main/resources/guest_init` | sync输出必须字节一致；QemuRuntimeBootstrapper会在运行时把resource作为`/init`覆盖base initramfs，漏掉guest_init会复活旧PID1/marker问题。 |
| `app/src/main/java/io/github/nyzeep/orchestella/GuestRuntimeProvisioner.java`、`FirstBootProvisionPipeline.java`、`DistroMirrorProvisioner.java` | pipeline:65调用mandatory wrapper，无论optional upgrade开关均安装baseline；正确ARM64源、dnf5等包族事实。不要调用Wizard历史“先写firstboot-done”生成器。 |
| 同目录 `DistroInstanceCreator.java` | :142写`ORCHESTELLA_EMPTY_ROOT_VOLUME_V2`，供runtime-entry严格授权第一次mkfs；保留“不因marker缺失格式化”。 |
| 同目录 `QemuRuntimeBootstrapper.java`；`runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/EmbeddedProvisionInput.java` | bootstrap:88–143匹配真实DistroManifest、传size/SHA/filename和init模板；缺校验输入拒绝。RuntimeArtifactManifest/RuntimeAssetInstaller为已有调用依赖，只有有相关dirty修复才需同步。 |
| `runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/InstanceUserConfig.java` | SecureRandom默认实例凭据与持久配置；不读取实例properties或重置已有用户密码。 |
| `guest-assets/agent/guest-agent.c`；`app/src/main/resources/initramfs.cpio.gz`；`app/src/main/assets/runtime-artifacts-v1.sha256`；既有对应NOTICE/source记录 | 幂等同token HELLO必须实际ARM64静态binary进入验证过的cpio；包与manifest作为同一闭包，不能只复制源码或沿用旧agent。摘要以第一会话canonical evidence为准。 |
| `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/BridgeClient.java`；`app/src/main/java/io/github/nyzeep/orchestella/BridgeConnector.java` | 持久唯一response pump、初始900秒有界HELLO/常规5秒请求；desktop先进入与terminal复用同session控制连接。调用点由协调者统一集成，不让desktop worker再造reader。 |

`GuestRuntimeStatus.java` 只是把真实stage呈给UI的可选接入，不是PID1必要依赖。`MainActivity`、Wizard、QEMU/AVF launcher调用点归协调者；如需保留单独修复，用精确hunk而非整页面替换。基线provision的root身份与可选桌面普通用户身份分开，安装desktop不修改共享guest init。

## 11. ADR 影响、实际验证与交付边界

| ADR | 本次影响检查 |
| --- | --- |
| 0006 | 沿用固定输入、guest内可选provision、不碰用户根盘。已有catalog来源与ADR“官方tarball”文字差异在第一会话已有记录，本报告不另改manifest。新增桌面包profile可在既有风味适配中表达。 |
| 0011 | user service/session失败只停止桌面；进程死亡后冷恢复重新验帧，不承诺热恢复或无限后台长跑。 |
| 0012 / 0017 | Wayland compositor-first保留；但0012 amendment与0017明确跳过独立RFB/VNC路线、零拷贝/60FPS文字。**将wayvnc/RFB CPU-copy作为正式产品fallback仍需协调者确认/走架构变更门，不能凭本研究静默宣称不触及ADR。** Xvnc/Xfce只诊断；普通QEMU VM与guest共享host物理Linux内核的0017图示文字、仅靠socket跨VM共享memfd的假设应由graphics研究/协调者处理。 |
| 0016 | 同时一个运行Instance；desktop+terminal是同Instance的访问上下文，不为了GUI另启一台VM或暗中放宽并发。 |
| 0019 | 不将host GLES、Mesa库存、GPU开关或像素上传当guest硬件GPU；CPU profile诚实标software。GPU全机型/Robustness保宿主不崩溃等历史绝对文字不能当本次验证；与新事实冲突由协调者处理，不改ADR。 |

本 supporting child 只提交finding，不创建ACR/tracker或修订ADR。显式CPU fallback的用户目标可交给协调者形成具体决策，是否采用RFB relay或原创直接capture由产品/架构owner裁定。

**已实际运行**：限定本地只读源码/文档核对；完整官方metadata解析及压缩文件size/digest复核；Noble Universe InRelease gpgv；新增EPEL/Vault primary checksum；Alma显式9.8与major9 primary绑定；六项官方浅/稀疏clone；固定revision/许可源码读取。证据脚本仅public GET/read-only parser，未执行上游/发行版安装脚本。

**未运行**：任何guest包安装、service/login/logind/seat、compositor/Xvnc、real capture/relay/Android presenter、GPU/Mesa renderer查询、键鼠/IME/clipboard、resize、FPS/延迟/内存/CPU、冷恢复、完整solver/全部RPM或Arch桌面包签名验证、Gradle/APK或设备测试。没有adb、安装App、启动VM、截屏、设备输入、ADB server操作；设备由第一会话独占。

**未完成与风险**：正式帧adapter/installer是后续实施；U/D之外的Weston发行版VNC模块、所有Weston模块加载/认证、niri各版本实际协议/renderer、EPEL+旧Rocky9.6 closure均须验证。D的sfwbar缺包、U/D wayvnc resize差异、EPEL不含labwc/niri、placeholder首帧、UID/runtime、配置覆盖及旧display ACK假成功是本次最小实施需要先处理的具体问题。

**修改/产物**：仅本文、新`REF/{labwc,niri,weston,waypipe,noVNC,kwin}`、EVIDENCE下公开原始下载/解析JSON/受限研究脚本/保全孪生；不存在生产模块、UI、Gradle、协议、tracker/ADR修改或commit。网络仅公开官方metadata/raw/git GET和公共问题查询，未外发宿主源码、日志、实例、路径清单或凭据；未写C盘项目文件、系统PATH/Registry/service/driver/firewall、WSL/SDK或既有参考。工具自身后台输出属于Host管理，不作为本项目canonical证据。

外部资产owner为desktop-research，交付后由协调者接管。公开参考源码与metadata/原始证据保留供review/复现；一次性parser/pycache属Class C，当前批不做清理。新原始证据不用`.log`，每份下载/JSON即时保留`preserved/`字节一致孪生。旧Class A资产未删除、改名或更新。

报告人：Codex / desktop-research supporting child ｜ 日期：2026-10-13 ｜ 耗时：未统计
