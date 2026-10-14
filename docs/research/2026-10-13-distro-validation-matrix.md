# 2026-10-13 registry7 发行版输入与 musl 工具链事实矩阵

本轮覆盖 `DistroCatalogRegistry` 当前注册的 Ubuntu、Debian、Fedora、Arch Linux ARM、openSUSE Leap、AlmaLinux、Rocky Linux 七个固定 manifest。**7/7 rootfs 精确 size + SHA-256 已通过；这不表示七个发行版已经启动或完成 firstboot。** 本 worker 没有操作设备、QEMU、Gradle、Git 提交/push，也没有执行 guest 程序、包管理器或 APK 安装脚本。

## 范围、路径与证据

- 仓库：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev`。
- 本轮 rootfs 输入根（下文 `INPUT_ROOT`）：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-20261013/distro-inputs`。
- 工具链根（下文 `TOOLCHAIN_ROOT`）：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-20261013/guest-toolchain`。
- 先读 `AGENTS.md`、`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/` 三文件、`ARTIFACT_ORGANIZATION_POLICY.md`、`HOST_ENVIRONMENT_POLICY.md`。其中旧 DroidLayer 路径按本次明确批准的 Orchestella 路径执行。
- 只读 `runtime/distro/src/main/java/io/github/nyzeep/orchestella/runtime/distro/Official*Catalog.java` 和 `app/src/main/java/io/github/nyzeep/orchestella/DistroMirrorCatalog.kt`；七个 Catalog 前后 SHA-256 未变化，证据为 `INPUT_ROOT/evidence/catalog-read-only-gate.json`。
- 下载只使用 Catalog 原 URL、当前配置的公开镜像链，以及官方发行版包仓的 HTTPS。没有上传 workspace、凭据、日志或用户内容。
- 主会话随后明确授权读取三个现有 Class A 项目缓存 `D:/Orchestella-rootfs/` 中的固定包；先精确 hash，再复制到本轮输入根，原包未修改。
- Canonical 原始证据：`INPUT_ROOT/evidence/registry7-validation.json`、`INPUT_ROOT/evidence/<id>-validation.json`、`INPUT_ROOT/official-metadata/<repo-id>/result.json`；均有即时 `.preserved.txt` 孪生。原始证据没有新建 `.log` 扩展名。

## 固定输入矩阵

以下文件均位于 `INPUT_ROOT/<文件名>`，全量字节数与 SHA-256 同时匹配当前 Catalog；没有用 latest 或其他 release 的字节替换失败下载。

| id | Catalog release | 实际 os-release | 文件名 | 精确字节数 | SHA-256 | 取得方式 |
| --- | --- | --- | --- | ---: | --- | --- |
| ubuntu | 24.04 | Ubuntu 24.04.5 LTS / noble，VERSION_ID=24.04 | ubuntu-base-24.04.5-base-arm64.tar.gz | 29936675 | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` | 配置的 TUNA Ubuntu cdimage 镜像 |
| debian | 13 | Debian 13 / trixie，DEBIAN_VERSION_FULL=13.7 | debian-13-rootfs.tar.xz | 90346224 | `e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91` | 配置的 TUNA 固定 distrobuilder 20261010_05:24 镜像 |
| fedora | 43 | Fedora Linux 43 Container Image / stable | fedora-43-minimal-rootfs.tar.gz | 51525008 | `59327d988a136a185148842cf6625366e4815cb7e85d3d0802453c7b9e4af497` | Catalog 公开 GitHub release 原 URL |
| arch | rolling | Arch Linux ARM，ID=archarm，BUILD_ID=rolling | ArchLinuxARM-aarch64-latest.tar.gz | 829367415 | `42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319` | D:/Orchestella-rootfs 同名固定包 hash 复用 |
| opensuse | 16.0 | openSUSE Leap 16.0，ID=opensuse-leap | opensuse-leap-image.aarch64-1.0.0-networkd-Build18.75.tar.xz | 31426856 | `d43d3e63203c3e08dbd5164a904fbf92df69ff21713ff18c82da179e00639ef6` | Catalog 公开 GitHub release 原 URL |
| almalinux | 9 | AlmaLinux 9.8 (Olive Jaguar)，VERSION_ID=9.8 | AlmaLinux-9-Base-rootfs.tar.gz | 66985382 | `ae65b206007f1635dfb045e9ac0e67dbdd9820c536115298b7e89cc078895d37` | D:/Orchestella-rootfs 同名固定包 hash 复用 |
| rocky | 9.6 | Rocky Linux 9.6 (Blue Onyx)，VERSION_ID=9.6 | Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz | 50342584 | `257534a55c3422b91cf1bf2dfcd02a752d3b2661902b76350b7a9ca39cc27edb` | D:/Orchestella-rootfs 同名固定包 hash 复用 |

Alma Catalog 锁 major `9`，实际固定字节为 `9.8`，只在 major 维度一致；不能将此包描述为 9.6。Arch 是固定 SHA 的 rolling 快照，文件名里的 `latest` 不是允许动态换包的依据。GitHub URL 和 Catalog SHA 是本轮下载约束；本轮没有重新认证六个自托管包与原始 OCI/container 上游的制作链。

Catalog 原 URL：

- Ubuntu：<https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz>。
- Debian：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/debian-13-rootfs.tar.xz>。
- Fedora：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/fedora-43-minimal-rootfs.tar.gz>。
- Arch：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/ArchLinuxARM-aarch64-latest.tar.gz>。
- Leap：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/opensuse-leap-image.aarch64-1.0.0-networkd-Build18.75.tar.xz>。
- Alma：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/AlmaLinux-9-Base-rootfs.tar.gz>。
- Rocky：<https://github.com/Nyzeep/Orchestella-Studio/releases/download/distro-catalog-v1/Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz>。

成功镜像 URL 与失败尝试均记录在各族 JSON；Ubuntu 成功镜像是 `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz`，Debian 是 `https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261010_05%3A24/rootfs.tar.xz`。本机 Debian 最终取得了该固定日期包，没有复用更新日期。三个大包整包网络超时后，曾对原 URL 做有界 HTTP206 分段尝试；主会话提供旧缓存后已停止该任务，分段文件没有作为已验证 rootfs 使用。

## rootfs 静态 preflight

只在 tar 内读取和解析成员，按 Linux POSIX 路径解析目录 symlink、hardlink 与最终目标。检查 ELF magic、ELF64、little-endian、e_machine=183、程序/节头与 segment 文件边界、PT_INTERP 最终目标存在。下表的“ELF 通过”只表示这些结构检查通过，不能证明所有动态依赖、运行时 syscall、VM 内核或 firstboot 可用。

| id | /sbin/init | 包管理入口 | 有效 CA bundle / PEM certificate 数 | preflight / boot 状态 |
| --- | --- | --- | --- | --- |
| ubuntu | 缺失，解析至不存在的 /usr/sbin/init | /usr/bin/apt，AArch64 ELF 通过 | 未发现有效 bundle | 输入已验证；须补 systemd-sysv / CA；boot NOT RUN |
| debian | → /usr/lib/systemd/systemd，AArch64 ELF + loader 通过 | apt，AArch64 ELF 通过 | /etc/ssl/certs/ca-certificates.crt，150 | 静态检查完成；boot NOT RUN |
| fedora | 缺失，merged-sbin 解析至不存在的 /usr/bin/init | dnf / microdnf 链接到 dnf5，AArch64 ELF 通过 | /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem，146 | 输入已验证；须补 systemd；boot NOT RUN |
| arch | → /usr/lib/systemd/systemd，AArch64 ELF + loader 通过 | pacman，AArch64 ELF 通过 | /etc/ssl/certs/ca-certificates.crt → /etc/ca-certificates/extracted/tls-ca-bundle.pem，121 | 静态检查完成；boot NOT RUN |
| opensuse | → /usr/lib/systemd/systemd，AArch64 ELF + loader 通过 | zypper，AArch64 ELF 通过 | /etc/ssl/ca-bundle.pem → /var/lib/ca-certificates/ca-bundle.pem，121 | 静态检查完成；boot NOT RUN |
| almalinux | → /usr/lib/systemd/systemd，AArch64 ELF + loader 通过 | dnf → dnf-3，Python 脚本 | /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem，146 | 静态检查完成；boot NOT RUN |
| rocky | 缺失，解析至不存在的 /usr/sbin/init | dnf → dnf-3，Python 脚本 | /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem，148 | 输入已验证；须补 systemd；boot NOT RUN |

现有四个 systemd init 的 PT_INTERP 都是 `/lib/ld-linux-aarch64.so.1`，对应 loader 在各归档内均存在；没有发现上述 ELF 结构的截断或非 AArch64 问题。

- Debian init SHA-256：`46044b7912af7f7087837554b186ca3ddb640c0f6646cf5e625be5078d5db80d`。
- Arch init SHA-256：`9b86f7c8cbadc9f8586128b49c15d14b2a37783bafc7dc5716bd5dd17c1eb271`。
- Leap init SHA-256：`12153eb59b51cb02df0ca71990a11a1921f879bb80ec2cc082fb13e643ef3979`。
- Alma init SHA-256：`32db48df72014f2c856f654f1f507b4e34cc74476a529704356c7600805889ea`。

Leap 的 `/etc/ssl/certs/ca-certificates.crt` 链接在原包中指向缺失目标，但另一个官方 bundle `/etc/ssl/ca-bundle.pem` 有效。Alma/Rocky 使用 RHEL 风格 bundle，不能用 Ubuntu bundle 路径的存在性作为唯一 CA 判断。Ubuntu 原包只有 archive 公钥、没有 CA bundle；先改成全 HTTPS 后再安装 ca-certificates 可能形成引导依赖，应按受签名保护的包安装流程处理。

Arch tar 内三个 systemd unit 文件名包含字面量 `\\x2d` 转义，这是 Linux 合法名称；本轮 POSIX 只读检查保留该语义，没有将其在 Windows 上解包。最终七包均未发现 POSIX 路径逃逸或重名成员。工具链 APK 的 Windows 解包规则另外拒绝反斜杠与绝对/逃逸路径。

## 官方 arm64/aarch64 包基线与签名

这些是官方仓元数据的事实与脚本依赖建议，不表示已经在 guest 执行安装。各包精确版本、arch、文件 checksum、Requires/Provides/Conflicts 位于 `INPUT_ROOT/official-metadata/<repo-id>/result.json`。RHEL major 仓库是当前 `/9/`，可能升级固定 9.6 rootfs 的 minor；没有把当前仓元数据说成 9.6 vault 快照。

| 族 / 仓入口 | 官方元数据核查 | 包基线事实 | 签名事实 |
| --- | --- | --- | --- |
| Ubuntu noble，https://ports.ubuntu.com/ubuntu-ports/ | InRelease + main/binary-arm64/Packages.xz，size/SHA-256 通过 | systemd、systemd-sysv、udev、dbus、openssh-server、openssh-client、ca-certificates、iproute2、ncurses-term、procps | 从固定 rootfs 提取 ubuntu-archive-keyring.gpg；gpgv InRelease 返回 0 |
| Debian trixie，https://deb.debian.org/debian/ | InRelease + main/binary-arm64/Packages.xz，size/SHA-256 通过 | systemd、systemd-sysv、udev、dbus、openssh-server、openssh-client、ca-certificates、iproute2、ncurses-term、procps | rootfs keyring .gpg 是 .pgp 的链接；用实际目标公钥 gpgv 返回 0 |
| Fedora 43 Everything，https://dl.fedoraproject.org/pub/fedora/linux/releases/43/Everything/aarch64/os/ | repomd 指定 primary，精确 size/checksum 通过 | 实际包 dnf5；systemd 与 systemd-udev 分包；dbus-tools 拥有 dbus-uuidgen；openssh-server/clients、glibc-langpack-en、ncurses-term 可用 | rootfs gpgcheck=1、repo_gpgcheck=0；RPM-GPG-KEY-fedora-43-aarch64 → fedora-43-primary 存在；未运行 rpmkeys 验包 |
| Alma 9，https://repo.almalinux.org/almalinux/9/ | BaseOS、AppStream/aarch64/os/ primary 精确 size/checksum 通过 | dnf、systemd、systemd-udev、dbus-tools、openssh-server/clients；curl-minimal Conflicts curl；ncurses-term 在 AppStream | rootfs 启用 RPM gpgcheck；/etc/pki/rpm-gpg/RPM-GPG-KEY-AlmaLinux-9 存在；未运行 rpmkeys 验包 |
| Rocky 9，https://dl.rockylinux.org/pub/rocky/9/ | BaseOS、AppStream/aarch64/os/ primary 精确 size/checksum 通过 | 同上；不能只启 BaseOS 后要求 ncurses-term；保留 curl-minimal 或做显式包替换 | rootfs 启用 RPM gpgcheck；/etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-9 存在；未运行 rpmkeys 验包 |
| Leap 16.0，https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/aarch64/ | repomd primary 精确 size/SHA-512 通过 | openssh-server + openssh-clients 实际分包；dbus-1、dbus-1-tools、udev、terminfo | 同目录 repomd.xml.key + .asc 均取得；gpgv detached repomd 签名返回 0 |
| Arch Linux ARM rolling，https://ca.us.mirror.archlinuxarm.org/aarch64/ | core.db、extra.db 读取并记录 SHA-256 | core：base 3-3(any)、archlinuxarm-keyring 20240419-2(any)、systemd/systemd-sysvcompat 262-1(aarch64)、pacman、openssh、dbus、ca-certificates | repo 包记录有 PGPSIG；抽样 base 包 size/SHA + 用固定 rootfs 公钥 gpgv 验签返回 0；未验整仓全部包 |

重要分包证据：

- Fedora `dnf5-5.2.17.0-2.fc43.aarch64` 拥有 `/usr/bin/{dnf,dnf5,microdnf,yum}`，并虚拟 Provides dnf/microdnf/yum；库存检查必须针对实际包 dnf5，而非 `rpm -q dnf`。`microdnf` 在当前 repo 也有旧独立包记录，不能只按命令名判断实际 owner。
- Fedora `dbus-tools-1.16.0-4.fc43.aarch64` 的 primary file list 明列 `/usr/bin/dbus-uuidgen`，包 SHA-256 `ae653c5ed8c54578a440868ff57cf3b30a284e9d4b75ae0bb591964463ed1d88`；dbus 元包不能代替工具包。
- Fedora `systemd-258-1.fc43.aarch64` 拥有 `/usr/bin/init` 与 `/usr/bin/systemd-machine-id-setup`；`systemd-udev-258-1.fc43.aarch64` 是单独包；`glibc-langpack-en-2.42-4.fc43.aarch64` 在官方 repo 存在。
- Alma/Rocky `dbus-tools-1.12.20-8.el9.aarch64` 拥有 dbus-uuidgen；`ncurses-term-6.2-12.20210508.el9.noarch` 位于 AppStream；curl-minimal 的 Conflicts curl 在官方 primary 中明确列出。
- Leap `openssh-server` / `openssh-clients` 的官方记录为 10.0p2 系列；`dbus-1-tools-1.14.10-160000.2.2.aarch64` 拥有 dbus-uuidgen。仓路径必须带 `/aarch64/`，不能沿用 Tumbleweed 或遗漏架构层。
- 若脚本只需初始化 machine-id，现有/安装后的 systemd 提供 systemd-machine-id-setup。使用该工具并按发行版维护 `/var/lib/dbus/machine-id` 与 `/etc/machine-id` 关系可减少 uuidgen 依赖；保留 uuidgen 调用则必须安装实际 owner 工具包。原包中 Fedora、Ubuntu、Rocky 缺这两个工具，Alma 有 systemd-machine-id-setup、缺 uuidgen，Arch/Debian/Leap 二者均存在。

Leap 官方 key URL：<https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/aarch64/repodata/repomd.xml.key>，size=1680，SHA-256=`b5745739ebfb95b25b8e810f9bcb847fe750ccda598bd85f02f0e974599a6d7e`。已验证签名 fingerprint=`AD485664E901B867051AB15F35A2F86E29B700A4`；key 由官方 HTTPS 取得，本轮没有另设独立 fingerprint trust anchor。

Arch 抽样 `base-3-3-any.pkg.tar.xz` 为 2384 字节，SHA-256=`66af0072b148b2607e3a36b550a20811f1935688c87b807e69297eb585ead483`，签名 fingerprint=`68B3537F39A313B3E574D06777193F152BDBE6A6`。固定 rootfs 的 `archlinuxarm.gpg` 是 ASCII armored 公钥，先 dearmor 再 gpgv 验证。canonical `https://mirror.archlinuxarm.org` 在本机验证出现 hostname mismatch，未绕过 TLS；使用原包官方 mirrorlist 列出的 California 区域镜像可正常验证 TLS。这不是修改 Catalog 的依据。

## 纯 musl sysroot 与静态 agent

原任务只授权合成 hello；主会话在 guest worker 完成 HELLO 幂等源码后追加实际 guest-agent 编译授权。最终源码限定 SHA-256=`bc404ee51f1eef710f7781826a3a811eb94bd79b5b679d1e096a0d32c68dad9e`，编译前后均一致。只写外部工具链根；App 资源、initramfs、manifest、APK 由主会话集成。

官方 stable release 查询：<https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml>，本次解析为 **Alpine 3.24.2 / v3.24**。包来自 `https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/`。

| 输入 | 版本 | 包 SHA-256 | 已认证的 APKINDEX checksum |
| --- | --- | --- | --- |
| musl | 1.2.6-r2 | `5e9674b7f41152fe2119093b5cb4c13eaaadb19c2d5422b2d7267913e663ee6e` | `Q1JNQts+O/Z+jaJ4QHLMXQ5svftIU=` |
| musl-dev | 1.2.6-r2 | `ca72b84f4eb3c36bbbec04d919eb10159d414c7a43413cc2253841d301fa9486` | `Q1fd1f//8oIPvl7Tx7qmQcaJh84FQ=` |
| linux-headers | 7.0.0-r1 | `d61656e81116040ebac0bc8d9046a3ba97f01a00ee085809103576e8a437468f` | `Q10L75G8gal3VsCE6vAfDSjwSwkPE=` |

APKINDEX 的 RSA/SHA-1 签名由官方下载的 `alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub` 验证通过；公钥 SHA-256=`d11f6b21c61b4274e182eb888883a8ba8acdbf820dcc7a6d82a7d9fc2fd2836d`。每包验证 index size、压缩 control 流 Q1 checksum，以及受其保护的 `.PKGINFO` datahash SHA-256，之后才安全解包数据流。所有路径/link 预检查，链接在 Windows sysroot 中按验证后的目标字节 materialize，未执行 APK 脚本。合成绝对路径、`..` 逃逸、Windows drive、反斜杠、NUL 负例均拒绝，证据 `TOOLCHAIN_ROOT/evidence/extraction-negative-gates.json`。

Sysroot：`TOOLCHAIN_ROOT/sysroot`，包括 `usr/lib/{libc.a,crt1.o,crti.o,crtn.o}`、musl headers 与官方 `linux/vm_sockets.h`。libc.a SHA-256=`f82a6354e55ace2881b15f90dc6a81f598b6b40a60fcbb2cf3fd9b1eed2ddde1`。没有混用 NDK Android headers 或 Bionic。

编译器：`D:/AndroidSDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe`；linker=`lld`。实际 agent 的 printf/scanf 路径还需要 musl 128-bit long-double 算术 helper；使用同版 NDK 现有 freestanding `lib/clang/18/lib/baremetal/libclang_rt.builtins-aarch64.a`，SHA-256=`d049ccd356bf0b0872afafeaef11c997779f85ab09372356246b65536a629331`。该库只提供 compiler runtime helper；它不改变 sysroot 的 musl Linux ABI。

完整可复现 argv、工具 hash、包 URL、CRT/header hash、source before/after hash：`TOOLCHAIN_ROOT/evidence/toolchain-provenance.json`。命令结构如下，尖括号表示上文已经给出的实际路径，不是待决输入：

```text
D:/AndroidSDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe
  --target=aarch64-linux-musl --sysroot=<TOOLCHAIN_ROOT/sysroot>
  -static -fuse-ld=lld -nostdinc -isystem <TOOLCHAIN_ROOT/sysroot/usr/include>
  -std=c11 -Os -Wall -Wextra -Werror -fno-ident -Wl,--build-id=none
  -nostdlib -Wl,-e,_start
  <sysroot/usr/lib/crt1.o> <sysroot/usr/lib/crti.o> <source.c>
  -Wl,--start-group <sysroot/usr/lib/libc.a>
  D:/AndroidSDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/lib/clang/18/lib/baremetal/libclang_rt.builtins-aarch64.a
  -Wl,--end-group <sysroot/usr/lib/crtn.o> -o <output>
```

| 产物 | 精确字节数 | SHA-256 | 验证 |
| --- | ---: | --- | --- |
| TOOLCHAIN_ROOT/hello-aarch64-musl | 78880 | `9cf59ce0dfca45a62de7a829a35234ed0874e5ccb2c7319ff6da41d6de3dc3b0` | ELF64 little-endian AArch64 EXEC；无 INTERP / DYNAMIC；NOT RUN |
| TOOLCHAIN_ROOT/orchestella-guest-agent-aarch64-musl | 480712 | `1c6369adeef447ecab4a8b0aefcfe9b3c75157fbf298dc058e43cf3900c3745d` | 同上；GNU_STACK 为 RW，无 X；NOT RUN |

实际 `llvm-readelf.exe -h -l -d` 输出与即时孪生位于 `TOOLCHAIN_ROOT/evidence/{hello,guest-agent}-readelf.txt`。未使用 Win32/network 测试宏，未额外链接 libpthread/libutil，未运行实际 guest binary。

原版许可证原文与出处证据已给主会话：

- `TOOLCHAIN_ROOT/evidence/musl-copyright.txt`，6196 字节，SHA-256=`b870108ec5e7790e9f9919064f1b9421d62d5f9b0e6c230c6adf7ea2da62e97b`；来自官方 Alpine distfiles 的 musl-1.2.6 源归档 COPYRIGHT。
- `TOOLCHAIN_ROOT/evidence/compiler-rt-license.txt`，15141 字节，SHA-256=`8d85c1057d742e597985c7d4e6320b015a9139385cff4cbae06ffc0ebe89afee`；来自实际 NDK27 NOTICE 第一个完整 LLVM license block，含 Apache-2.0 / LLVM exception / legacy notice。
- URL、原文件/归档 hash、成员名、提取范围详见 `TOOLCHAIN_ROOT/evidence/license-provenance.json`。此 worker 没有修改 App NOTICE。

## 验证边界与交付

已验证：七包固定 size/SHA、只读 tar/os-release/init/ELF/loader/pkgmanager/CA/key 检查、官方包名和分包元数据、上表注明的签名、musl 安全解包、合成 hello 与锁定最终源码的静态交叉编译。

未运行：七个发行版的 VM/真机 boot、firstboot 包安装、guest SSH/terminal/network/HELLO runtime、RPM 包签名命令、Gradle/APK 构建。主会话使用上文真实输入和二进制继续验收，不能仅据本矩阵把发行版标为实际可用。

外部产物 owner 为本轮 distro/toolchain worker，交付后由主会话保留/清理。固定 rootfs、原 Class A 缓存、官方 musl/UAPI 包与 sysroot 可复用，应保留；停止的 Range fragments、`.part`、下载探针及一次性脚本属于本轮临时输出，没有代替 canonical 完整包。所有新写入位于批准的本轮外部目录及本文件，没有修改系统 PATH/Registry/WSL、NDK 安装目录或 C: 用户资源；后台工具自身的会话输出由工具管理。
