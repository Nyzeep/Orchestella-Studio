# 研究报告：Phase 5 Android USB 授权 FD 与只读 Downloads 数据面

- 触发工单：`.scratch/orchestella-phase5/issues/04-usb-readonly-storage.md`；日期：2026-10-13。
- 执行者：storage-research supporting child / 会话配置模型 `new-provider/gpt-6.1-sol`；自行执行，无进一步派生。
- 分支：`phase5/graphics-productivity-storage`；研究基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`；本任务 commit：无。未 commit / push / PR / release。
- 正式输出仅本报告；公开下载、原创探针、编译结果及 raw evidence 属于外部 C 工作区，不进入生产源码。

## 1. 结论与可实施的首条路径

1. **Android 无 Root 可利用用户授权的 USB FD 做 native I/O。** `UsbManager.openDevice()` 由服务检查权限后返回 FD；JNI 对它做 `F_DUPFD_CLOEXEC`，`getFileDescriptor()` 提供当前连接持有的 FD。libusb 官方 Android 示例和 `libusb_wrap_sys_device()` 明确支持这种路径。它不要求 App 重新打开所有 `/dev/bus/usb` 节点，也不意味着所有设备、接口、reset、kernel-driver detach 或 isochronous 都保证可用。[A1–A5、B1–B3]
2. **锁定版本 QEMU 已有可研究的 FD 接入口。** `usb-host` 的 `hostdevice` 经 `qemu_open()` 打开，而 `/dev/fdset/<id>` 在该函数内会从 fdset 复制 FD；随后 `libusb_wrap_sys_device()` 包装。可以原型研究 Unix `SCM_RIGHTS` → QMP `add-fd` → `hostdevice=/dev/fdset/<id>`，不必错误宣称“QEMU 完全没有授权 FD 通道”。但当前启动器只有 `ProcessBuilder`，不会自动把 Android Java 的 FD 整数变成子进程 FD；QEMU 初始化未设置 `NO_DEVICE_DISCOVERY`，产品 libusb/USB controller/kernel 支持未验证，因此还不是实际可交付直通。[C1–C4、P1]
3. **Linux 标准 USBIP exporter 不能直接消费 `UsbDeviceConnection` FD。** `usbip_host` 绑定物理设备；其 `usbip_sockfd` 接收的是 TCP socket FD，由内核收发 URB。它依赖宿主内核支持和驱动/sysfs 管理权限。Android USB 用户弹窗不授予这些权限。Android userspace FD → 自研 USBIP exporter 在原理上是另一种开发项目，不能等同现成 Linux exporter。[D1–D4]
4. **usbipd-win 的职责是 Windows 本地 USB exporter。** 它安装服务、驱动相关组件、PATH 与防火墙规则；bind 需要管理员权限。它不能为 Android App 提供 USB 权限，也不是 Android USB 库。本次只读源码，未安装、启动或调用服务/设备命令。[E3]
5. **AVF 不能再笼统写成“源码没有 USB”。** 固定的 Android 16 源码已有 `UsbConfig.controller` 和 crosvm controller 开关，但在所核对的 `IVirtualMachine`、Java SDK API 中没有接受 `UsbDeviceConnection`/USB FD 的 attach/detach 方法。custom image 仍隐藏，权限含 `MANAGE_VIRTUAL_MACHINE` 和 `USE_CUSTOM_VIRTUAL_MACHINE`；controller 存在不证明第三方 App 可以直通。当前项目没有可验证的 AVF USB 数据面，必须保持未支持/未验证状态，而不是做永久不可能判断。[F1–F5]
6. **先实现原创“精确 Downloads 选集 → 受配额 staging → catalog-only 只读 9p broker”原型。** 输入仅用户显式选择且能证明属于当前用户 Downloads 的文件，内部缓存原子发布，不遍历整个 Downloads/host，不把私有目录交给通用文件系统后端。broker 只通过内部对象 ID/已持有 FD 提供选集；既有 Bridge 六命令不变。QEMU 缺失 9p 后端时明确拒绝接入，不用 stub 或测试视图冒充 guest mount。当前已独立编译并运行 staging 核心合成探针，尚未实现 Android 授权门和 catalog broker 的产品接线。[P1–P7、G1–G5、§7–§8]

**实际验证的额外缺陷：** 现有 `Avf9pShareServer` 丢失 response tag；标准 `Tattach=104` 返回 `ENOSYS`，因为代码使用 132；短 `Tversion` payload 抛未捕获数组越界异常。自有测试客户端同样使用错误 attach 编号且把 response body 从 type 后立即解析，掩盖这些 wire 问题。它们是既有只读行为的 bug 修复对象，不需要把 USB 或新目录加入白名单。[P3、P7、D5、实测 evidence]

## 2. 范围、治理与证据分类

本任务读取三个范围：隔离工作区内精确相关的生产源码、guest 配置、QEMU lock/构建脚本与 dispatch/issue；活动主仓中指定政策、ADR-0007/0010/0013 和既有研究；新获取的官方公开源码。没有读凭据、`.env`、私钥、实例镜像、完整 Git 历史或其他用户数据。所有网络请求只包含公开项目 URL、公开 revision 和官方文档请求，不发送项目源码、报告、日志或 host 文件清单。

路径代号如下，后文源码路径都相对于对应代号；这不是新建目录体系：

| 代号 | 绝对路径 | 权限/owner |
| --- | --- | --- |
| W | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev` | 生产源码只读；child 只写本报告 |
| M | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev` | 活动主仓全部只读 |
| R | `D:/Development project(开发项目)/Orchestella开发目录/phase5-references` | 仅新建本 child 所有的四个指定目标；已有 qemu 只读 |
| E | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/evidence` | 本 child 的下载、源码证据、合成探针与原始结果 |

先读 W/AGENTS.md。W 中没有未跟踪的政策目录，按协调者明确补充从 M 读取了 `DEFENSIVE_CONSTRAINTS/README.md`、其两项 policy，以及 HOST / ARTIFACT / ARCHITECTURE / ASSET / REPORTING 政策；同时读取 W 的 dispatch 与 issue。没有复制、修改或另建政策。旧政策内 DroidLayer 路径以本次用户明确批准的 Orchestella 路径和 dispatch 为实际边界。

research Skill 工具调用已尝试：同名全局/项目技能返回歧义，按项目绝对路径调用返回 `Skill is not allowed for subagent`。随后只读项目 `.agents/skills/research/SKILL.md`，遵循 primary source 与单一研究归档要求；其中“派生 background agent”被用户“自己执行不派生”覆盖。没有声称工具加载成功。

分类：新增官方参考为后续可复用只读资产，暂按 Class A 保全；正式研究和执行 JSON 为 Class B；合成 `.part`、class 文件为本批定点 Class C。当前批不做清理，保留失败 Linux 目标和失败请求记录。所有证据用 `.json` / `.source.txt` / `.java` / `.py`，没有创建 `.log`。没有 C 盘写入、系统设置、服务、驱动、SDK 安装、ADB 或其他设备操作；工具管理的终端输出由宿主工具保存，不作为本报告的唯一证据。

## 3. 固定来源、许可与复现入口

行号采用源码文件的 1-based 行号。官方文本存 E 下同名 `.source.txt`；请求、响应 hash、失败和实际 URL 分别在 fetch JSON 中。主仓 dirty 文档不能仅靠 HEAD 证明，精确已读输入的 SHA-256 在 `project-input-provenance.json`，没有制作整仓 manifest。

### 3.1 Android / libusb / QEMU

| ID | 官方 URL、revision、源码位置 | 许可 / 权限前提 / 未运行边界 |
| --- | --- | --- |
| A1 | [UsbManager.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/hardware/usb/UsbManager.java)，Android `android-16.0.0_r1` → `99b01a65cc4c104933788b3143285ab6bae65827`，L1092–1115 openDevice；L1314–1342 授权到拔出、VIDEO/CAMERA | Apache-2.0；USB host feature + 用户授权；未连接任何设备 |
| A2 | [UsbDeviceConnection.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/hardware/usb/UsbDeviceConnection.java)，同 revision，L65–76 open、L144–162 close/FD；L184 claim；L240–318 control/bulk；L356–405 wait | Apache-2.0；有效授权连接，接口/端点兼容；未运行 Android API |
| A3 | [UsbDeviceConnection JNI](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/jni/android_hardware_UsbDeviceConnection.cpp)，L46–88 复制/回收 FD | Apache-2.0；FD 有效；native dup 行为为源码事实，未进行 USB native probe |
| A4 | [UsbRequest.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/hardware/usb/UsbRequest.java)，L33–43 bulk/interrupt/control 边界、cancel/close | Apache-2.0；有效连接/请求；未运行异步设备传输 |
| A5 | [libusbhost/usbhost.c](https://android.googlesource.com/platform/system/core/+/68be0c2c0006a0740d0b1809abe4717308f90d15/libusbhost/usbhost.c)，Android r1 → `68be0c2c0006a0740d0b1809abe4717308f90d15`，L605–636 接口 ioctls、L662–778 control/bulk/SUBMITURB/REAP/DISCARD，L693–697 请求类型 | 文件头 Apache-2.0；内核 usbfs 能力/SELinux/接口归属；未实测 OEM |
| B1 | [libusb/core.c](https://github.com/libusb/libusb/blob/0fc1ca754d3a36c1287a7535b90d3d0cf1f000c8/libusb/core.c#L1393)，`0fc1ca754d3a36c1287a7535b90d3d0cf1f000c8`，L1393–1428 wrap FD 与所有权、NO_DEVICE_DISCOVERY；自 1.0.23 支持 | LGPL-2.1-or-later，文件头 L8；本次只读，不新增链接库；未 native 编译 |
| B2 | [android/README](https://github.com/libusb/libusb/blob/0fc1ca754d3a36c1287a7535b90d3d0cf1f000c8/android/README#L54)，同 revision，L54–108 Java grant→FD→wrap 示例与兼容性保留说明 | libusb 项目许可；不是各 Android/OEM 的兼容性承诺 |
| B3 | [linux_usbfs.c](https://github.com/libusb/libusb/blob/0fc1ca754d3a36c1287a7535b90d3d0cf1f000c8/libusb/os/linux_usbfs.c#L1581)，同 revision，L1581–1666 包装、fd_keep；L2143 bulk、L2315 iso、L2462 control、L2508–2515 dispatch、L2109 discard | LGPL-2.1-or-later；USBDEVFS ioctls 可用；未提交任何 URB |
| B4 | [libusb/io.c](https://github.com/libusb/libusb/blob/0fc1ca754d3a36c1287a7535b90d3d0cf1f000c8/libusb/io.c#L311)，同 revision，L311–327 异步取消、L1327 不可释放 active transfer | LGPL-2.1-or-later；须处理完成事件；未测 unplug/cancel |
| C1 | [QEMU host-libusb.c](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/hw/usb/host-libusb.c#L948)，`c3d48b7d1e89604920e5b81b91140c2ad39a1943`，L282–297 init；L948–988 wrap；L1102–1129 close/reset；L1181–1244 hostdevice；L1762–1780 properties | 此文件 L17–33 为 MIT 文本；QEMU 整体 COPYING 为 GPL-2.0，其他文件逐文件核验；未运行 QEMU USB |
| C2 | [QEMU util/osdep.c](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/util/osdep.c#L328)，同 revision，L328–350 `/dev/fdset/` → monitor_fdset_dup_fd_add | QEMU 文件许可/整体 GPL-2.0；必须事先实际交付 FD；未做 IPC |
| C3 | [monitor/fds.c](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/monitor/fds.c#L206) L206–215 与 [qapi/misc.json](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/qapi/misc.json#L333) L333–359，QMP add-fd 取 SCM_RIGHTS | QEMU 整体 GPL-2.0，保留文件声明；Unix FD transport，不能由 TCP/QMP JSON 整数替代；未运行 |
| C4 | [hw/usb/meson.build](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/hw/usb/meson.build#L84)，L84–87 libusb.found() 才构建 host-libusb | QEMU 源码许可；不能仅由参数类判定 APK 支持 |
| C5 | [hw/9pfs/9p-local.c](https://github.com/qemu/qemu/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/hw/9pfs/9p-local.c#L841)，L81–84 openat、L353–381 nofollow、L841 起 open nofollow | QEMU 源码许可；实际必须启用 9p，不能把 Java path view 等同原生后端 |

### 3.2 Linux USBIP / virtiofs / Windows / AVF / SAF

| ID | 官方 URL、revision、源码位置 | 许可 / 权限前提 / 未运行边界 |
| --- | --- | --- |
| D1 | [Linux USBIP Kconfig](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/drivers/usb/usbip/Kconfig)，`v6.6` → `ffc253263a1375a65fa6c9f62a893e9767fbebfa`，L3–28 core/vhci；L52–60 host | Linux COPYING GPL-2.0 + syscall note；代码按 SPDX；需相应 kernel config，未运行内核 |
| D2 | [stub_main.c](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/drivers/usb/usbip/stub_main.c#L374) L374 注册 stub driver；[stub_dev.c](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/drivers/usb/usbip/stub_dev.c#L38) L38–143 TCP sockfd/sysfs/kthreads | 两文件 SPDX GPL-2.0-or-later；host 驱动/sysfs 权限；没有绑定设备 |
| D3 | [tools/usb/usbip/README](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/tools/usb/usbip/README#L65)，L65–109 server/client、L171–175解绑 usb-storage | Linux 源码许可，文件/工具逐项核验；未 insmod/bind/attach |
| D4 | [协议源码](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/Documentation/usb/usbip_protocol.rst)及[官方渲染文档](https://docs.kernel.org/usb/usbip_protocol.html)，固定源码包含 SUBMIT/UNLINK/iso descriptors，渲染页为抓取快照 | Linux 文档许可；TCP 本身未定义认证/TLS；未监听 TCP/网络传输 |
| D5 | [9p.h](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/include/net/9p/9p.h#L160)，L160–161 attach 104/105；L329 HDRSZ=7 | SPDX GPL-2.0-only；仅作为协议参照，不复制到生产 |
| D6 | [usb/core/devio.c](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/drivers/usb/core/devio.c#L1089)，L697 destroy async、L1089–1109 release、L1713 isoc、L2694/2734 submit/discard、L2858 disconnect | SPDX GPL-2.0-or-later；Linux6.6 不证明 Android OEM 内核相同；设备行为未运行 |
| E1 | [virtiofsd](https://gitlab.com/virtio-fs/virtiofsd/-/tree/4bfa71253bdc26cdc3be5828a30ea440154f683c)，README L52–66 默认权限、L179–183 readonly、L213–215 socket FD、L252–270 sandbox、L534–577 非特权例外；Cargo.toml L9 | Apache-2.0 AND BSD-3-Clause；默认 sandbox 与无 sandbox 模式区别见 §6；未编译、未 mount |
| E2 | [libfuse](https://github.com/libfuse/libfuse/tree/b3f0fac436931223bf67f4484e8fede2ab2eecef)，README L6–23 kernel/userspace、L100–118 setuid fusermount；LICENSE L1–13 | include/lib/meson.build LGPL-2.1，其余 GPL-2.0；FUSE device/mount 权限，未安装或使用 fusermount |
| E3 | [usbipd-win](https://github.com/dorssel/usbipd-win/tree/60fdd78b392f847c1ad162d9459b962c15910eaa)，README L13–41 Windows/service/firewall、L49–80 bind/client；文件头 SPDX GPL-3.0-only；[Microsoft USB/WSL 指南](https://learn.microsoft.com/en-us/windows/wsl/connect-usb) | 本仓 GPL-3.0-only；Windows 管理员 bind、服务/驱动管理；本次仅源码参考，零系统安装 |
| F1 | [VirtualMachineRawConfig.aidl](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/virtualizationservice/aidl/android/system/virtualizationservice/VirtualMachineRawConfig.aidl)，android16-release 抓取时 `c984fc337c11ca5edc03ccf02037b2455dd8fcaf`，L119–120 usbConfig；[UsbConfig.aidl](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/virtualizationservice/aidl/android/system/virtualizationservice/UsbConfig.aidl) L19–21 controller | Apache-2.0；只证明 controller 可配置 |
| F2 | [crosvm.rs](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/virtmgr/src/crosvm.rs)，L161–167 config、L1084–1085 --no-usb；[aidl.rs](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/virtmgr/src/aidl.rs) L674–682 / L1632–1637 权限 | Apache-2.0；不把 crosvm CLI 自动扩成 App API |
| F3 | [IVirtualMachine.aidl](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/virtualizationservice/aidl/android/system/virtualizationservice/IVirtualMachine.aidl)，L20–79 完整接口；L56 vsock，无 USB FD attach/detach | Apache-2.0；结论限定此 revision/API，不声称所有版本无实现 |
| F4 | [VirtualMachineCustomImageConfig.java](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java)，L27 @hide、L598 setter、L628–677 controller only；[VirtualMachine.java](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachine.java) L141–144 @hide/@SystemApi | Apache-2.0；反射找到 class 不等于普通 SDK/权限可用；未 AVF 创建 VM |
| F5 | [AVF module manifest](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/android/android.system.virtualmachine.res/AndroidManifest.xml)，MANAGE=signature/preinstalled/development；USE_CUSTOM=signature/development | Apache-2.0；不是普通 USB user grant，未 grant 或修改权限 |
| G1 | [SAF 官方文档](https://developer.android.com/training/data-storage/shared/documents-files?hl=en)，2026-10-13 抓取快照 SHA 在 supplementary-source-fetch.json；HTML L8985–9005 Android11限制、L9088 后 persistent grant 局限 | 官方文档/示例按网站许可；已读页面，不操作 picker |
| G2 | [DocumentsContract.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/provider/DocumentsContract.java)，L309 opaque ID、L991–1005 tree descendant、L1399 isChild、L1625–1650 findDocumentPath(treeUri) | Apache-2.0；精确 URI grant，不授予目录 native path 权限 |
| G3 | [ContentResolver.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/android/content/ContentResolver.java)，L1615–1630 r/w可能pipe/socket、FD归调用方关闭；L1672 取消 | Apache-2.0；mode="r"，不请求 rw 来强制 seek；未真实 provider |
| G4 | [DownloadStorageProvider.java](https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/10efab28373273505a6fba5ef0f738ae02571bb9/src/com/android/providers/downloads/DownloadStorageProvider.java)，L180–194 find path、L538–560 raw/media/db 映射；抓取分支后锁定此 SHA | Apache-2.0；同名 URI/provider 不能仅靠字符串可信；OEM 行为未核验 |
| G5 | [FileSystemProvider.java](https://android.googlesource.com/platform/frameworks/base/+/99b01a65cc4c104933788b3143285ab6bae65827/core/java/com/android/internal/content/FileSystemProvider.java)，L139–147 canonical 包含检查 | Apache-2.0；canonical check 与之后 open 仍需处理变更竞态，不能当事务性授权 |

固定 URL 的 GET 有失败记录：若干起初错误路径 404、GitHub commit raw 429、Android developer URL timeout/旧路径404；改用官方正确目录或 v6.6 tag 的有限回退。没有无限重试、登录/凭据使用或第三方镜像。AVF 从旧 `virtualizationservice/`、`java/` 路径已迁移到 `android/virtualizationservice/`、`android/virtmgr/`、`src/`；错误 URL 没有被当作“功能不存在”的证据。

### 3.3 官方参考实际取得状态

先由项目官网/一方文档确认官方仓库（virtio-fs 官网、libfuse 官网、Microsoft USB/WSL 文档、kernel.org）；然后检查目标不存在、`ls-remote` revision、官方 LICENSE/COPYING；最后使用 `clone --depth=1 --filter=blob:none --sparse --single-branch`，仅新 clone 做 sparse-checkout。没有对任何已有参考 pull/checkout。

| 目标绝对路径（R 下） | 实际 revision / 许可 | 结果 |
| --- | --- | --- |
| `R/virtiofsd` | `4bfa71253bdc26cdc3be5828a30ea440154f683c`；Apache-2.0 AND BSD-3-Clause | shallow=true，sparse src/docs，初始 clean，成功 |
| `R/libfuse` | `b3f0fac436931223bf67f4484e8fede2ab2eecef`；LGPL-2.1/GPL-2.0 文件范围见 LICENSE | shallow=true，sparse lib/include/example/util/doc，初始 clean，成功 |
| `R/usbipd-win` | `60fdd78b392f847c1ad162d9459b962c15910eaa`；GPL-3.0-only | shallow=true，sparse Usbipd/Installer，初始 clean，成功 |
| `R/linux-usbip` | 预检 `v6.6` peeled SHA `ffc253263a1375a65fa6c9f62a893e9767fbebfa`；Linux COPYING 已预检 | kernel.org clone 240s 超时，留下不完整目标，未 checkout/sparse/继续修改；**不是可用参考 clone**。USBIP/devio/9p 所需官方 raw 已存 E；不得说 clone 成功 |
| `R/qemu` | 只读 rev `c3d48b7d1e89604920e5b81b91140c2ad39a1943`，与项目 lock 匹配 | graphics-research 所有；没有改 sparse/checkout/pull；缺失 USB 文件只从官方 raw 取得到 E |

满足“必要至少三项浅层/稀疏 clone”；第四项的失败保持可审计。目标和命令原始结果在 `reference-preflight.json`、`public-source-fetch.json`、`official-reference-clones.json` 及各 `<project>-clone.json`。libfuse/usbipd-win 初猜 COPYING.LIB/LICENSE.md 的404保留，确认正确 LICENSE/COPYING.md 后才 clone。

## 4. Android USB FD：真正可行的范围与生命周期

### 4.1 权限不是任意节点访问

`requestPermission` 的结果必须来自本 App 的 PendingIntent，核验当前连接的 `UsbDevice` 与此次请求 generation，再调用 `hasPermission/openDevice`；弹窗拒绝、拔出、重新枚举都不能复用旧授权或旧 FD。[A1]

`UsbHostManager.openDevice` 会检查 denylist、设备是否仍连接、调用 UID/package 的 permission。Java 将服务 PFD 交 JNI 后关闭原 PFD，JNI 复制 FD 保存于连接。App 可以用该 FD 的受控 duplicate 做 native I/O，但授权并未改变整个 usbfs 的 DAC/SELinux 权限。[A1、A3；E/aosp-usb-host-manager.source.txt L556–571，Apache-2.0，同 frameworks SHA]

对 native 库建议 `libusb_init_context(... NO_DEVICE_DISCOVERY ...)`，再 `libusb_wrap_sys_device(ctx, ownedDuplicate, &handle)`；不要直接照抄旧 Android README 中 context 参数的写法，当前 core 文档优先。wrap 不发送 USB 请求；后续 claim/传输/重置分别可能失败。不要以一次 wrap 成功宣称 guest 可以控制整台设备。[B1–B3]

### 4.2 各传输类型

| 类型 | Android Java API | native/libusb/QEMU | 产品判断与权限限制 |
| --- | --- | --- | --- |
| control | `UsbDeviceConnection.controlTransfer`，EP0 | USBDEVFS_CONTROL / libusb control；QEMU 有 control callback 路径 | 只使用明确接口/设备 class 的必要请求；不把任意 vendor/reset control 加入默认权限；未实测 [A2、A5、B3、C1] |
| bulk | 同步 bulkTransfer 或 UsbRequest 异步 | libusb bulk → SUBMITURB；QEMU bulk pipeline | 串口/选定非系统接口适合后续首类 prototype；USB mass storage 的 bulk OUT 同样能携带写/格式化请求，不能称“只读 Downloads” [A4、B3、C1] |
| interrupt | UsbRequest async queue/requestWait | libusb interrupt → bulk/interrupt URB dispatch；QEMU interrupt | Java 不提供同步 interrupt 专用 API；需事件循环和 cancel 生命周期，不承诺所有 HID/token [A4、B3、C1] |
| isochronous | 没有相应公开 Java UsbRequest 数据面；AOSP 请求初始化只分 bulk/interrupt | libusb Linux 有专门 iso URB；QEMU L547–762 iso ring | **源码可表达，Android OEM 实际 ioctl、带宽、调度与 reset 后恢复未验证**；不能由 libusb 支持推导摄像头/音频成功 [A5、B3、C1、D6] |

视频类 target P+ 还需 CAMERA；本项目当前 Manifest 未声明该权限，本任务不建议新增。系统拥有的 hub/HID/接口可能被 denylist 或内核驱动占用；默认不调用 `claimInterface(force=true)` 或自动 detach。USB user grant 不自动授予绕过系统驱动的能力。[A1、A2、UsbHostManager 源码]

### 4.3 FD / cancel / unplug 状态机（建议，未设备执行）

`Denied → PermissionPending → Open → Wrapped → Claimed → Active → Quiescing → Closed`，任一阶段失败都进入 Quiescing/Closed；每个 attachment generation 单调递增，旧 callback 不得改变新设备状态。

- Android connection FD 为 borrowed；本地 broker 在连接仍有效且未关闭时复制一份，记录唯一 owner。Java connection、native duplicate、libusb handle、QEMU fdset 各自有明确回收动作；FD 整数不是跨进程能力。[A3、B1、C2–C3]
- libusb wrap 后 `libusb_close()` **不关闭外部 FD**。owner 需在 handle 释放后关闭 duplicate；失败路径也要执行。关闭 `UsbDeviceConnection` 不能替代关闭其他 duplicate。[B1、B3]
- 不同时使用 Android `requestWait` 和 libusb/QEMU event loop 回收同一 open file description 的 URB。`dup` 共享底层文件对象，两个 reaper 可能抢走完成通知；这是从 usbfs 状态归属推导出的限制，不是本次实测。[A5、D6]
- cancel 只提出异步取消：停止接受新请求 → 对 submitted transfer 调用 cancel → 继续 pump events，等每项 callback/取消结果 → release interface → close handle → close owned FD → close connection。不能在 cancel 调用后立即 free buffer/transfer。[B4]
- detach 或传输返回 NO_DEVICE/ENODEV 都视作终态，禁止自动按 VID/PID、相同 bus address 或新 FD 重新连接。临时授权到拔出结束；重新插入走新 generation/用户动作。[A1、C1、D6]
- 超时不能靠无限等待掩盖泄漏；独立 native 子进程可用有界 shutdown 并终止由本任务创建的进程作为最后回收策略，但本次没有创建这种进程或处理设备。实际关闭后等待者唤醒/OEM errno 必须在后续设备交接中验证。

QEMU close 路径会 reset、release、reattach kernel driver；对 Android 首原型应禁止未授权自动 reset/detach，并观察 close 的真实副作用。`guest-reset` 开关不能替代审核其他 reset 路径。[C1 L994、L1119–1122、L1155–1159]

### 4.4 最小 USB prototype（规格，未实现/未运行）

`UsbAuthorizedFdProbe` 的第一级只查能力/权限与 FD 生命周期，不连 guest：feature、PermissionPending 拒绝/取消/重复结果、open nullable、有效 FD duplicate、描述符读取是否完整、libusb no-discovery wrap/close、FD 数目回归、拔出回调及 race。输出仅稳定设备类别和 errno/计数，不记录序列号、USB payload 或主机路径清单。

第二级在显式用户动作和架构批准后，选一件专用合成/可丢弃的非系统 bulk/interrupt 测试设备，独占一个接口；验证 read/write 协议 fixture、short transfer、timeout、cancel、unplug，再进行 QMP Unix FD transfer。启用 guest USB controller、对应 guest kernel config、libusb 链接和 fdset 接线都需锁定构建证据。isoc、摄像头、USB盘完整块设备、远程 USBIP 不纳入首级承诺。本次**任何一级设备操作都未执行**。

## 5. USBIP 与 AVF 的职责边界

标准 Linux exporter 的输入是 **内核已绑定的物理 USB device + TCP socket**，不是授权后的用户态 USB FD。`USBIP_HOST`、`USBIP_CORE` 属于出口；`USBIP_VHCI_HCD` 属于 importer；client 的功能驱动在 guest/client kernel 中运行。guest root 允许其自身加载驱动，并不使 Android host 获得 sysfs bind、module 或内核资源权限。[D1–D3]

USBIP `SUBMIT` 承载控制/数据 URB，OUT payload 在请求，IN payload 在回复；iso 有 per-packet descriptor，网络延迟仍会影响时序。`UNLINK` 指定提交 seqnum，成功取消后返回 `-ECONNRESET`，完成先于取消则返回另一种结果，不能简单地同时发送 unlink 和原 submit completion。原生协议没有给出默认认证/加密边界；它不应通过默认开放 LAN:3240 来实现本项目“免 Root USB”。[D4]

可自研授权 FD → userspace USBIP URB translator，但这需要描述符、控制状态、interface/altsetting、同步/异步完成、iso 包、取消/断开全部模型，以及 guest importer。把现成 Linux `usbipd` 拿进 App 不能省掉它的 kernel exporter 依赖；usbipd-win 不提供 Android exporter。[D1–D4、E3]

AVF 的 USB controller 配置会影响 crosvm `--no-usb`，所查 raw config 没有传入设备 FD 的字段；`IVirtualMachine` 没有 USB attach 方法。SDK class 的存在、反射可调用 setter，和实际授权、设备访问是分开的能力门。所查 manifest 的 `USE_CUSTOM_VIRTUAL_MACHINE` 是 development/signature，当前 App Manifest只声明 MANAGE，不能据此宣称 custom VM 权限闭合。[F1–F5、P1]

不能以 pVM 内存隔离推导“绝对没有双向 I/O”：所查 `IVirtualMachine.connectVsock()` 就是受控通信接口；也不能以 non-protected VM 支持推导 USB fd passthrough。USB 输入、图形共享、VFIO 设备分配各有单独门。本报告不裁定 graphics-research 所有的显示通道或其他 AVF 设备分配问题。[F3]

## 6. 项目共享现状与 virtiofs/FUSE 的实际含义

### 6.1 精确项目源码证据

以下 W 文件按基线输入 hash 固定；项目根 LICENSE 为 Apache-2.0，外部 QEMU/libusb/kernel 不因此重许可。读取源码不是运行设备的授权。

| ID | 源码路径与行号 | 实际现状 / 已验证程度 |
| --- | --- | --- |
| P1 | `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java` L271–298；`app/src/main/AndroidManifest.xml` L3–15 | 当前 App 走 buildWithPasst，ProcessBuilder 启动；没有本条调用的 sharedDirectory 参数、USB grant 或外部存储权限入口；静态事实 |
| P2 | `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java` L445–489、L558–563、L620–649 | buildInternal 能格式化 local fsdev readonly；buildWithPasst 没有共享参数；安全配置类不等同可运行后端 |
| P3 | `runtime/engine-avf/src/main/java/io/github/nyzeep/orchestella/runtime/engineavf/Avf9pShareServer.java` L84–85、L178–203、L256–266、L344–367、L517–531、L553–583、L725–807 | 错误attach、漏tag、serve缺finally、version清fid不close、lopen替换channel、walk替换fid未close、realpath→open race；前三项有本次独立实证，其他为源码判断 |
| P4 | `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/SharedDirectoryContract.java` L68–77、L204–245、L279–295；`MountOptions.java` L38–45、L76–78 | builder 拒写，但 hostPath 仅绝对路径/元字符检查，不证明Downloads；DefaultReadOnlyView 仅normalize/startsWith，读API可能跟随symlink；不是安全物理共享验证 |
| P5 | `tools/qemu-android/qemu-jni.lock` L4–11、L22；`tools/qemu-android/ci/build-in-container.sh` L279–297 | lock QEMU11.1.1/rev与C1一致；**--disable-virtfs、--disable-vhost-user**；未取得/运行当前 APK 的device-help/config-host，不能把历史参数测试称packaged backend成功 |
| P6 | `guest-assets/kernel/required.config` L27–30/L39、`kernel/orchestella.fragment` L21–24/L31、`kernel/droidlayer.fragment` 同位置；`guest-assets/initramfs/init` L250–267 | 要求9p virtio和FUSE；未明确保证 `NET_9P_FD`、`VIRTIO_FS`、USBIP/VHCI/XHCI；init有9p挂载并忽略失败，AVF交给agent trans=fd；**不是实际Image .config证明** |
| P7 | `runtime/engine-avf/src/test/java/io/github/nyzeep/orchestella/runtime/engineavf/TestNinePClient.java` L349–353/L403–412；`AvfLaunchSpec.java` L92–94/L263–274 | request写tag，但response从position(1)读payload，未检查tag；其opcode匹配错误实现；share端口与控制/终端分离已有契约，保持 |

`security_model=none,readonly=on` 加 guest `ro` 应保留；不能靠 chmod 0444 或 guest mount `ro` 单独代替服务端拒绝写类 9p 操作。当前 DefaultReadOnlyView 只能证明测试API拒写；真正 guest root 是否被 hypervisor 拒写仍需物理通道验证。[P2–P4]

### 6.2 virtiofs 不等于 host 上需要挂 FUSE

virtiofsd 是 vhost-user device daemon，guest 用 FUSE 协议的 virtio-fs kernel driver访问，常规 virtiofsd 并不先在 Android host 挂一个 `/dev/fuse` filesystem。libfuse 则是 host kernel FUSE userspace 库，常规 mount 需要 `/dev/fuse` 和挂载/helper 权限。这两件事不能混为“下载 libfuse 就把 SAF 挂成本地路径”。[E1–E2]

virtiofsd 默认 namespace sandbox 的创建、capability 与 uid/gid 映射有权限前提；官方 README 同时允许无 sandbox、相同 UID/GID 的非特权模式。因此不能声称“virtiofsd 在任何环境都必须真 root”。但无 sandbox 不能作为本项目削弱边界的默认答案：Android cross-build、cap-ng/seccomp、沙箱策略、vhost-user/memory FD、guest `CONFIG_VIRTIO_FS` 及只读白名单均需验证。目前产品构建禁用了 vhost-user，guest配置未保证virtiofs，因此没有可直接替换的闭合方案。[E1、P5–P6]

virtiofsd 的 `--fd` 是监听 socket 的 FD，不是 USB FD/SAF 文件 FD；`--readonly` 禁止 guest 写请求，不会把 underlying host 文件系统变成只读（例如 atime仍可更新）。任何只读评估需要以“guest不能改内容/元数据”的契约为主，记录host自行更新/读取副作用，不能误判成guest写成功。[E1 README L179–183/L213–215]

QEMU 原生 9p local 使用 openat/nofollow；本项目 Java view与AVF server的 TOCTOU 结论不能直接外推为“上游 QEMU 同样会跟随 symlink逃逸”。后者必须按实际 backend源码与合成竞态单独验证。[C5、P3–P4]

## 7. 不扩大白名单的原创 Downloads 数据面与 prototype

### 7.1 授权与证明门

Android11+不能使用 `ACTION_OPEN_DOCUMENT_TREE` 请求整个 Download、内部共享根或可靠SD根；SAF file grant 也不会让 QEMU 获得一个可任意枚举的原生目录。`content://` URI和 document ID是不透明标识；`EXTRA_INITIAL_URI`只影响picker初始位置，不限制用户最终选择范围。[G1–G3]

建议首级只接 `ACTION_OPEN_DOCUMENT` 返回的精确单文件/少量选集，读取mode="r"，不索取持久write grant，不申请 MANAGE_EXTERNAL_STORAGE，不启用从 cloud provider下载。用户必须看到此次文件数量、大小预算、只读用途、取消和缓存保留期。未明确授权的文件不 query、不 open、不进入缓存；未知 provider、范围无法证明、数量/大小未知或变更则拒绝。

**不能凭URI字符串证明Downloads。** trusted authority 还需校验其实现身份/签名或受平台信任的provider binding；仅依靠“名称叫Downloads”不足。需要provider可调用的实际包含证明，或对已授权PFD做文件身份检查；不可先广泛枚举Roots/目录来“寻找匹配”。[G2、G4–G5]

特别注意 `findDocumentPath()` 的公开契约要求 tree URI，而Download整树grant被限制。不可捏造tree URI，或假设file grant自动允许对parent查询。首个Androidprobe允许拒绝无法证明的输入：对自己已授权PFD做fstat、只读取该FD的`/proc/self/fd/<n>`链接、检查它是regular/seekable并确属预先锁定的**当前用户**Downloads根；只接受已审计的挂载别名，不自动扩大到sdcard根。pipe/socket、deleted FD、unknown alias、无法证明归属都拒绝。FD/proc路径的Android SELinux可用性、FUSE别名与race行为 **UNCERTAIN，待显式设备交接验证**；不能作为本次已验证生产能力。[G3、D6；此门为本报告提出的原型设计]

staging 前后重复验证身份/大小/mtime与范围，变化即废弃整代；使用已打开FD读取，绝不把URI的 `_data`/raw id拼成native path重新open。provider无法证明snapshot一致性时明确报告不支持稳定选集。只选视频/音频或只grantUSB也不授权另外的文件/provider。

### 7.2 数据对象与拒写边界

推荐模块分成内部三部分，保持数据通道与Bridge控制通道分离：

1. `DownloadsSelectionGate`：接收此次用户动作的精确授权对象，生成不可由guest指定的source能力；限额建议首原型64文件、总16MiB、单对象大小已知。配额是实验参数，产品预算由协调者定义。
2. `ReadOnlyGenerationStore`：只将已批准对象流式写入本App独占、该选集所属的pending空间；64KiB buffer，计数上界，逐对象hash与copy后复读校验；所有对象成功后同filesystem atomic publish。source FD和output FD在每条退出路径关闭。generation不是根盘snapshot，不扫描host或`.git`，也不参与后台同步/出站。
3. `DownloadsCatalog9pBroker`：原创只读9P2000.L handler，root是内部固定catalog，fid只映射generation/object ID；路径由允许的单段文件名查表，不提供hostPath/URI任意open。仅read/getattr/readdir等必要操作，所有write/lcreate/setattr/rename/link/symlink/unlink/xattr-create返回EROFS；未知operation有界拒绝。Qid/tag/offset均按正式9p协议，不沿用错误fixture。guest只看逻辑Downloads选集，不见缓存、目录管理信息、其他App数据或主机路径。

`SharedDirectorySpec` 的mount tag="downloads"、guest mount=/mnt/downloads、ro语义可由协调者的内部adapter保留；adapter接入catalog source需明确owner，**本任务没有修改共享契约或wire协议**。QEMU按既有virtio-9p物理面接线，AVF按既有独立9p stream接线；缺backend立即返回unavailable。暂不引入9p-over-TCP、通用host目录export、virtiofs切换或新的Bridge命令。

ADR-0010 明确“禁止共享App私有数据目录”。所以**本方案不把staging parent或私有路径交给通用 `-fsdev local` 后端**；私有cache只是broker持有的内部实现资源，guest只得到经批准Downloads对象的bytes。如果协调者选择把私有staging目录直接作为local fsdev root，该解释与ADR文字存在冲突，必须先记录ACR/用户裁定，不可把内部cache悄悄变成目录共享。本次JVM测试用的是纯合成sandbox路径，不能据此批准产品private directory export。

实现顺序可先完成无Android依赖的选集/配额/atomic-generation核心和协议正确的catalog broker，用合成source、byte streams及只读契约测试；再给Android permission/PFD adapter和QEMU backend增加精确能力门。现有包不能接9p时，核心仍可独立构建/benchmark，guest能力如实为unavailable。生产最短修复路径是先修已存在server wire/清理错误，不把下载新库当修复。

### 7.3 Symlink / URI / 失败一致性

- 不接受guest递交URI/authority/native路径；fid来自服务端catalog。禁止绝对path、`..`、NUL、斜杠/反斜杠嵌套、名称冲突以及声明的合成敏感sentinel。字符串percent-encoding不用于拼provider/path；URI scheme必须content且匹配此次grant对象，未知authority、混淆user-id、伪造tree/document组合拒绝。
- 对真实filesystem源，capability必须从已固定root FD经 `openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS)` 或逐段 `openat(O_NOFOLLOW)`/fstat持有目录FD得到；根据Android内核可用性选择，**不**用check-then-reopen路径证明。SAF不能证明identity的source在首级拒绝。已有Java server的toRealPath和之后open之间存在race，应修复或仅允许broker自建不可变catalog。
- 内部cache绝不接受外部name作为绝对文件名；保留host-independent object ID，display name单独审计；只写本generation目录，CREATE_NEW不覆盖。源/目标symlink、hardlink、mount跨越、magic `/proc`链接应有负例；权限拒绝发生在首次source读取前。
- failed/cancelled generation不publish，旧成功generation继续有效直到它自身grant/lease撤回；失败不是“刷新成功”。用户撤回共享则立即阻止新read、关闭session/FD，并让旧fid明确失败；不能一边继续提供旧cache一边声称已撤权。
- 断流发生在copy期间，整体失败；9p reply中途断开，关闭session，client得到短帧/transport error，禁止把部分Rread解析为成功。中断/取消不得自动重选目录、重扫provider、补传或重试。
- 只读cache不等于绝对snapshot：Android若并发改源且不支持稳定对象版本，应拒绝发布；一致性声明只覆盖通过明确proof的generation。报告日志只留计数、hash、errno，URI/path/payload不输出。

### 7.4 可直接分配的修复与必须决策的项目

| 项目 | 建议归类 | 后续验收 |
| --- | --- | --- |
| 9p opcode/tag/短帧/字符串长度/负offset/msize处理 | 既有语义bugfix，无新增暴露面 | 独立标准协议测试，不能让自有client忽略tag；真实Linux client后续验证 |
| serve/version/clunk/walk/lopen全部FD回收、flush取消语义 | 既有资源/正确性bugfix | 断流、version重协商、clone/reopen失败、取消、1000会话FD计数回归 |
| 默认只读view与AVF server symlink竞态 | 安全bugfix | pinned directory/object FD；真实替换竞态sentinel不可读 |
| QEMU无virtfs时明确拒绝而非输出不存在设备参数 | 既有能力错误处理 | 固定binary/device-help构建证据；package smoke目前未运行 |
| 精确Downloads选集core/catalog原型 | 不扩大现有内容范围的supporting prototype；产品接线须协调者审核 | 先运行合成证明；真实SAF/PFD gate不成立时保持拒绝 |
| 直接把App私有cache目录-fsdev导出 | ADR-0010文字冲突，先ACR | 明确裁定逻辑选集与物理private路径边界，不自行改ADR |
| raw USB guest passthrough、自研USBIP通道、AVF USB新增API、LAN exporter | ADR-0010/0013 ACR | 每设备主动授权、精确类别/范围、生命周期、能力矩阵、独立review |
| 任意目录/SD根/读写共享、根盘迁到SAF | ADR-0010/0007 ACR；本票禁止 | 无本次实现授权 |

## 8. 已运行的合成验证与后续安全 benchmark

### 8.1 本次真实执行

环境仅Windows本地 `D:/JDK17/bin/javac.exe` 与 `java.exe`，不启动WSL/VM/QEMU，不调用Android/USB/ADB。直接编译固定hash的 `MountOptions`、`SharedDirectoryContract`、`Avf9pShareServer` 三个已有源码与原创 `StorageResearchProbe.java`；class和数据只写E。没有Gradle/production改动。

| 指标 | 结果 | 可解释范围 |
| --- | --- | --- |
| 合成staging断言 | 19项通过 | 成功复制、provider/range模型拒绝、路径/sentinel拒绝、声明和实际配额、短读/超长/断流/取消不publish、契约API拒写和词法越界 |
| 每代合成输入 | 32×4096B + 1×8MiB = 33文件 / 8,519,680B | 只生成指定固定数据，无读取真实Downloads/host |
| 三代copy+hash+reopen校验耗时 | 79.6614 / 40.4287 / 40.5086 ms | Windows JVM、含warmup/OS cache；不是Android/9p/QEMU/USB吞吐，不能外推“零拷贝”或性能承诺 |
| synthetic source open/close | 103 / 103 | 所跑路径无source泄漏；不证明USB/9p FileChannel全部回收 |
| 标准Tversion tag | 输入4660（0x1234），按标准reply位置读到0 | 现有server漏2字节tag；实际不合规；不是guest启动成功 |
| 标准Tattach104 | baseline返回ENOSYS38 | 错误opcode；132/133 fixture非标准协议 |
| 短Tversion payload | 未捕获ArrayIndexOutOfBoundsException | actual baseline崩溃，要求有界frame/payload解析 |
| mutation errno | 当前未带tag帧内可观察EROFS30 | 不合规response仍须修；不能写成Linux client已验证EROFS |

结果源：`synthetic-storage-results.json`、`synthetic-probe-execution.json`、`standard-attach-results.json`、`standard-attach-execution.json`。探针可以由固定命令重现，但当前sandbox代名不能覆盖；重跑用新且明确owner的sandbox，只生成合成数据。失败`.part`保留于专属sandbox，既不作为“ready”也不继续传输。测试包含`.env`等**字符串sentinel**，没有读实际凭据。

未运行：真实Android picker/provider、PFD/proc alias gate、真实symlink/hardlink竞态、USB grant/控制/bulk/interrupt/isoc、QMP SCM_RIGHTS、QEMU9p mount、AVF VM/vsock、guest image配置核验、OEM kernel ioctl、driver/service安装。没有将这些项目写成通过。

### 8.2 后续 benchmark 步骤（规格，未执行）

1. 在独立prototype sandbox生成固定seed fixture：4KiB/64KiB/1MiB/8MiB对象与少量目录；敏感sentinel全部合成。设64对象/16MiB上界，不以真实workspace或whole Downloads作fixture。预留源size/digest，只记录公开seed/计数。
2. 基线测direct synthetic stream → boundedstaging，与copy+hash+publish分别计时；记录warmup/每轮参数/p50/p95、峰值buffer内存、bytes、source open/close、取消耗时。至少热身后30轮，独立环境信息；本次3轮只作可运行性观测，非统计benchmark。
3. catalog broker按正式9p帧跑version/attach/walk/lopen/read/getattr/readdir/clunk；对照Linux官方opcode和nonzero tags，随机分片header/payload、交错tag、EOF和malformed长度。拒绝protocol drift；debug fakeclient不能替代kernel mount。
4. fail injection在0B、64KiB、最后chunk、prepublish及reply中途：source异常/失联、cancel、配额溢出、short/extra数据、provider identity改变。断言旧published generation不变、失败代不可见、无重新扫描/重试，所有FD归零。
5. 路径负例覆盖symlink交替指向Downloads内外、root替换、hardlink到合成outside sentinel、`..`/绝对path/编码混淆/magiclink、URIauthority/user-ID/tree伪造与未知provider。OUTSIDE_SENTINEL只存在同一隔离fixture中，不触碰真实App/host内容。
6. 写类完整矩阵：write、truncate、append、lcreate、mkdir、rename/renameat、unlink/unlinkat、mknod、link/symlink、setattr、xattrcreate及O_TMPFILE/write-intent。服务端与guest VFS分别断言EROFS；即使guest root试图remount rw也不能成功修改source。source内容hash不变，atime不冒充内容写测试。
7. 后续协调者拿到明确设备交接后，才让测试App生成自己所有的合成Downloads对象，经用户picker逐项授权；运行上述真实PFD身份门及readonly读取/cancel。不要把SAF对任意目录授权作为成功fixture。基准数据始终本地，关闭telemetry/build scan/tunnel。
8. 独立证据锁定APK/QEMU/libusb/kernel `.config`/revision/license、guest mount选项与errnos；三种状态分开报告：模型通过、host broker通过、guest真实mount通过。任何核心门失败即保持unavailable，禁止mock结果顶替真实mount。

## 9. ADR-0007 / 0010 / 0013 影响与交付边界

- **ADR-0007**：不改App私有raw单根盘、ext4、SAF单向备份及无snapshot承诺。内部选集generation缓存不是VM快照；本次无数据支持重开磁盘格式/位置。把root镜像放SAF/shared目录或按共享性能修改盘位置才需ACR。[M/docs/adr/0007-storage-model.md L7–13/L23–27]
- **ADR-0010**：只允许已批准Downloads对象的只读视图。缩小到显式选集、修复现有protocol/leak/path bug、缺backend拒绝不需要增加白名单；直接导出private缓存路径、任意provider/SD根、写共享、USB设备或network exporter属于冲突/加面。此报告记录证据，不修改ADR/tracker，协调者按Architecture Change Policy裁定并创建ACR。[M/docs/adr/0010-security-boundary.md L12–15/L24/L42–46]
- **ADR-0013**：保留M2+、QEMU先行、自研授权FD模式、AVF待原型、特殊类别不承诺。libusb/QEMU FD evidence使QEMU路线有依据，但不足以将USB标记已完成；AVF controller源码也不足以改变能力矩阵为支持。真正USB guest接入之前需每设备授权及ADR-0010显式修订。[M/docs/adr/0013-usb-audio-scope.md L7–9/L18–21]
- 架构变更门：本任务**未实施架构变更**。存在条件性ACR项（USB数据面、private-path physical export、virtiofs替换/新transport若偏离既定QEMU9p契约），已交协调者，不创建/修改tracker或ADR。
- 越界：无生产/shared协议/UI/Gradle/主仓修改，无设备/系统操作，无远端写入，无host内容出站。源码事实与模型实测范围均明确。Linux fourth clone失败、真实Android gate/guest后端未验证是交付缺口，未隐瞒。

## 10. 产出、保留与下一步建议

唯一正式报告：`W/docs/research/2026-10-13-phase5-usb-storage.md`。

原始证据：`E`。关键索引是 `reference-preflight.json`、`official-reference-clones.json`、`project-input-provenance.json`、`official-source-line-index.json`、各 fetch JSON，以及两组 probe execution/results JSON。原创探针为 `StorageResearchProbe.java` 和 `StandardAttachProbe.java`；它们是外部研究证据，不是已合并模块。公开source快照按固定来源hash保留，失败记录不删；后续原始证据的保留/清理由Phase5协调者统一负责，不删除ClassA。

参考根：`R/virtiofsd`、`R/libfuse`、`R/usbipd-win`，均真实浅层稀疏clone；`R/linux-usbip`仅不完整失败目标，其官方所需source存E。graphics所有的`R/qemu`全程只读。

建议协调者优先分配现有9p bugfix worker，使用标准opcode/tag的独立client并覆盖finally回收与malformed frames；并行实现无Android依赖的Downloads选集/配额/generation core及catalog broker prototype。Android SAF/USB native FD仅准备probe规格，在明确设备交接后验证。QEMU9p缺后端继续明确拒绝；USB guest/USBIP/AVF USB能力先保留待决策与未验证状态。

报告人：storage-research / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计
