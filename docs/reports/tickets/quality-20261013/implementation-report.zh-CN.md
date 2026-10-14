# Orchestella Linux 工作站品质优化实施与验收

日期：2026-10-13。状态：运行验收进行中；本报告只把已取得的证据记为通过。

## 交付范围

用户要求广泛联网研究、拉取相似项目并实际优化完整 Linux 环境、终端、主题、首页与布局；后续追加命令记录/高亮分段、七个发行版验证，并将 AVF 图形、桌面、容器/开发服务、USB/存储增强交独立 Phase 5 会话。所有仓库禁止远端推送。

参考目录及固定源码调查见 [Linux 参考研究](../../../research/2026-10-13-linux-reference-survey.md) 与 [Termux/终端研究](../../../research/2026-10-13-terminal-reference-survey.md)。Droidspaces 要求 host Root，其服务装配可研究，但本项目保持无 Root、独立 guest kernel 的 VM-first 路线。没有把 GPL 实现复制进原创 Apache 应用代码。

第二会话的完整指令已交付：[Phase 5 并行开发 prompt](../../../agent-handoff/PHASE5_PARALLEL_SESSION_PROMPT.zh-CN.md)。要求隔离工作树/构建、协调设备时间、先闭合真实图形帧与输入，再引入 KDE/niri 等可选环境。

## 完整 Linux 基线

修复了两个根因：通用脚本穿透 `/sbin/init` symlink 破坏发行版 init；旧首次配置提前写完成标记，导致真正安装被跳过。

新方案保留真实 init，并在缺少或损坏时使用私有、受控的恢复 supervisor。各发行版安装自己的 init、DBus、SSH、证书、UTF-8、网络工具、基础命令、nano/less/tmux 等。只有实际包库存、原生 PID1、服务与 SSH 配置通过后才写 `ready / verified`。失败保留阶段/原因和重试入口，不写提前成功标记。

新根盘只有合法创建签名且未存在文件系统时才允许第一次格式化；用户已有根盘、配置和 Alpine inittab 自定义项保留。损坏 init、包安装失败、离线、服务失败、旧提前成功标记、CA 引导、凭据与用户 policy 均有回归。

镜像大小与 SHA-256 继续由固定 manifest 锁定，主机传至 guest 的 `rootfs.file / rootfs.size / rootfs.sha256` 不再为空或从未验证下载自证。新实例采用独立随机凭据；旧/导入实例未知密码保留未知，弱旧凭据不默认启用密码 SSH，也不静默重置。

七个固定 rootfs 与官方 ARM64 软件仓核查见 [发行版矩阵](../../../research/2026-10-13-distro-validation-matrix.md)。修复了 Fedora 的真实包名/分包、openSUSE 的架构仓路径、RPM curl 最小变体、ArchLinuxARM ID 和官方 TLS 镜像等差异。

## 终端与界面

终端使用原创持久 VT 屏幕模型，处理光标、擦除、区域滚动、备用屏幕和 UTF-8 分包；修复 IME、组合修饰键、真实 shell Tab、硬件输入、粘贴与上滚阅读锚点。终端退出页面不会停止 VM；标题条与辅助键不遮住正文。

命令与输出依据明确 Bash shell 标记分区，提交后的命令有界保留、以高对比颜色显示，并与输出/下一提示空行分隔。literal `>` 输出不会变成用户命令。长命令/CJK/emoji 按终端列宽重新布局。top/nano 保留原生备用屏幕语义，不插入命令块。

边界：未知 shell 未集成标记时保持标准 VT；用户尚在编辑命令时，异步 stdout 与 readline echo 同源，不能逐字节宣称完全隔离。提交后的命令记录使用 guest 的真实 history，不把隐藏密码输入或被 history 排除的命令重新记入。

控制通道只有一个 reader。首次 HELLO 有期限重试；guest 同 token/protocol 的重复 HELLO 幂等，错误认证仍拒绝。recovery 交给 native init 后的控制操作重新 HELLO 并以 PING/PONG 证明当前上下文，防旧 READY 错配。实际尺寸通过鉴权 RESIZE/ioctl 更新。

保留用户认可的构建主义设计。主题覆盖页面、导航、弹窗、卡片与终端，字号/间距/光标设置真正持久化；移除强制首次 App 引导、假终端/假按键、固定 CPU/FPS/延迟/IOPS。首页设备信息前台每3秒刷新，运行引擎、初始化进度、失败提示与 SSH 指令都有实际来源。

真实 QEMU TCP 回环映射接入 gvproxy 私有 Unix services API；规则先应用再保存，失败回滚。禁用时移除持久启动配置，重启不会重新开放。现有 UDP/LAN/AVF 转发缺口明确显示未支持，未把配置保存冒充已应用。

当前 App 图形链未完成：QEMU 的旧本地模板帧和空输入回传不算可用桌面，AVF production session 也缺真实图形接口。本轮 UI 已关闭该虚假可用入口；真实图形/可选桌面交 Phase 5 实施，不声称所有 GUI 已完成。

## 构建与静态证据

生产 agent 实际由 NDK clang、已验证 musl sysroot 和裸机 compiler-rt 构建为 AArch64 静态 ELF，无动态解释器；不是仅修改 C 源。musl/compiler-rt NOTICE 已进入 APK。原运行包的其他428项内容/模式保持，并更新固定运行校验清单。

截至本记录已完成：

- App 单元测试：599 项，失败/错误/跳过均为0。
- runtime contract、instance、engine-qemu 检查通过。
- Android lint：错误0，警告671；未用 baseline 隐藏错误。
- guest-assets 打包/实际进程测试：73 项，失败0，2项 Linux-only 用例在 Windows 跳过。
- 启动恢复探针、七族合成矩阵、官方包名与输入校验通过；细节及平台限制见 guest 审计，不把合成结果当作设备结果。

审查为两个独立轴：[Standards](../../../reports/reviews/quality-20261013/standards-review.md)、[Spec](../../../reports/reviews/quality-20261013/spec-review.md)。图形新范围的未完成事实单列，不与基线通过相混淆。

## 真机证据与运行矩阵

设备：荣耀 MEY-AN00，Android16/API36。AVF 声明/授权预检并不能保证 custom VM 服务可运行；本机实际回退到 QEMU。全部设备结果限定于该环境。

| 发行版 | 已取得实际结果 | 后续验收状态 |
| --- | --- | --- |
| Ubuntu24.04.5 | systemd、cgroup2、DBus/SSH、DNS、基础包/UTF-8、冷重启文件保留、ready；真实回环SSH随机密码登录；top/nano与命令分段 | 新 POWER/UI 关机最终复验进行中 |
| Debian13 | 七项实际探针全部通过，ready/verified | 旧 agent POWER 缺口已定位并补新二进制，最终关机复验待闭合 |
| Fedora43 | 七项实际探针全部通过，dnf5、原生服务和ready | 同一旧 POWER 缺口；恢复 Bash 修复随后版本生效 |
| openSUSE Leap16 | 真机原生启动并执行仓库刷新 | 安装/服务探针进行中 |
| AlmaLinux9（固定包实际9.8） | 真机原生启动并执行仓库刷新 | 安装/服务探针进行中 |
| Rocky9.6 | 固定输入/官方包名验证 | 排队设备运行 |
| ArchLinuxARM | 固定输入/官方包名验证，明确3GiB QA配置与装配内存门 | 排队单独设备运行 |

原始证据按用途保留在项目外部资源根 `Orchestella/artifacts/quality-20261013/`。截图、日志和结果均不包含实例密码/token。QA资源 ZIP 明确不可安装，仅用于提前运行最新生产 guest 资源；最终 APK 会重新构建，并比较这些资源摘要。测试使用独立 QA 根盘与网络，不覆盖用户实例。

## 最终状态

本报告会在所有实际测试结束后补齐最终 APK 摘要、矩阵与已知限制。当前不得从此进行中记录推断七族或所有图形能力完全可用。没有 commit、push、release 或向参考项目上游发送任何内容。
