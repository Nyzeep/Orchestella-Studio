# Phase 5 实现与验证矩阵

- 日期：2026-10-13；scope：隔离分支本地研发交付，禁止远端写与commit。
- 研究证据：四个 `docs/research/2026-10-13-phase5-*.md`；运行原件：外部 `Orchestella/artifacts/phase5-parallel-20261013/`。
- 分级：**实现**表示实际生产/原型源码存在；**宿主通过**表示真实JVM/Windows/WSL合成数据的程序测试；**NOT_RUN**表示没有在真实ARM64 guest或Android执行。研究/源码形状/fixture从不等于设备功能已验收。

## 按功能

| 功能 | 本轮实际实现 | 已运行证据 | guest / Android / 设备 |
| --- | --- | --- | --- |
| 真实显示数据面 | 原创token→RFB3.8，Raw/DesktopSize、4MP/16MiB边界、输入、有界队列与重连；guest普通用户UnixRFB经独立virtio/vsockrelay | 17初版新增Javawire测试，整合后ASCII新增为18；15Linuxrelay测试，现有17contract入口；框架拒绝模板帧 | labwc/wayvnc/virtio/vsock/AndroidSurface实际 NOT_RUN |
| Surface呈现 | bool真实EGL/GL/swap成功、ARGB→RGBA/currentPBO、最新真实帧重播、context重建；generation/尺寸/时间/非uniform门 | 原生红/蓝/alpha转换程序通过；App呈现gate与键码回归，整合App最初59后61及最终以build原件为准 | 真EGLcontextloss/旋转/Surface/后台/输入damage NOT_RUN |
| GPU/zero-copy | 未接入真实VirGL/rutabaga/gfxstream后端；官方权限/ABI/buffer/fence研究与ACR | 包内ELF与固定官方源码核查，当前virtio-gpu2D；旧stub不升级能力 | AVF/旗舰GPU/renderer/performance NOT_RUN；架构权限 BLOCKED |
| Desktopprofile | Debian13 ARM64 labwc/headless/pixman、普通用户session/wayvnc/foot/waybar、可取消/重试与真实captureconfirm，unitsv2 | profile Linux全套25中24pass/1安全拒绝错误码断言失败；修target通过，取消/弱secret/ENOSPC/升级/relaydead/竞态定点通过；独立review限定snapshot批准 | 创建、下载、捕帧、输入、恢复、KDE/niri均 NOT_RUN；KDE/niri尚无安装profile |
| Podman | Debian/Ubuntu普通用户rootlessprofile，kernel/userns/subid/overlay/seccomp、固定digestrun/DNS/TLS/volume/port验收分项 | 实际packagedImage的77required满足；MASQ/FIB缺项记录；CLI/profile合成进程负例 | 固定镜像层拉取、podman run/DNS/TLS/volume/port ARM64 NOT_RUN |
| DockerCLI | Debian独立signeddocker-cli包、版本与daemon状态分别说明；Ubuntu不显示未经核实pureCLI | signedARM64包元数据+profilefixture | 真CLI安装 NOT_RUN；rootlessdaemon未实现，明确 PARTIAL |
| code-server | fixed4.141.0 ARM64、大小/SHA/受控tar、普通用户loopback/随机strongsecret/0600、authHTTP/生命周期/取消精确staging回收 | 234587826B tar实际下载SHA通过；真实合成HTTPlistener/login/stop、10stagingfault与回归、units1→2保配置3目标 | 官方程序实际guest安装/运行、移动IDE完整加载 NOT_RUN |
| 手机访问/LAN | 精确loopbackorigin/固定release资源路由、无JSbridge、cookie清理与单editor；临时gvproxyloopback规则/显式guestNICrelay；明确10minLAN租约 | 26dataJUnit含实际TCP/HTTP/lease/portcrash；实际App编译与回归通过；WebView源码边界独立review | WebSocket/ServiceWorker全流量、cookie实际隔离、LANclient/TLS/屏幕动作 NOT_RUN；AVF App服务forward明确尚不支持 |
| 9p只读 | 标准104/105/tag/7byteheader、限额、拒写、secure-relative目录FD、所有fid/session回收 | 18注册AVF入口、真实LinuxchildJVM、1500会话FD8→8、400symlinkrace无outside读取 | AndroidSecureDirectoryStream provider/真实guestmount NOT_RUN；缺provider ENOSYS拒绝，FIFO竞态残余边界已披露 |
| SAF/共享catalog | 可信SourceProof精确Downloads选集、数量/字节/名字配额、streamhash/取消/原子generation、catalogspecUNVERIFIED | 26JUnit实际文件/TCP断流与取消；65MiB/1025合成files与13项基准 | Androidgrant/heldFDproof/production broker/VMM挂载未接入，PARTIAL/NOT_RUN |
| USB/OTG | 官方FD/QEMUhostdevice/libusb/usbip边界研究，FD授权/拒绝/stalecallback/拔出回收模型原型 | 独立JVM模型/合成probe，3官方参考clone；源码入口明确 | AndroidUSB授权/native跨进程FD/热插拔/VMM透传未实现；新exposure ACR_REQUIRED |
| AVFAPI | 双权限/官方Builder/Disk/create/run/longvsock/PFDownership、stop锁外和迟到FD回收、持久get/getConfig严格复用 | 19JVM官方shape契约；独立blockedconnect停止探针通过，lateFDclose1；持久resume/configmismatch/failedrunretry目标通过 | realcustomVM/OEMpermission/nativeinterrupt/AF_VSOCK NOT_RUN；cpu>1精确拒绝回QEMU |
| guest控制 | HELLOv1/token唯一reader、FEATURES1固定动作/profile、失败DISPLAY后PING、3秒helperdeadline、CONFIRM32hexgeneration | 真POSIXstdin+testTCP50/50case、865assertions、96helpers、25对响应等价；初版缺generation失败保留修复后全过 | AArch64 ELF已重建/校验/打包，真实guest执行 NOT_RUN |

## 按发行版 / profile / 策略

所有以下组合都**没有本轮设备运行验收**。允许选项仅代表有明确输入与实施计划，不表示七发行版桌面完全可用。

| Catalog固定实例输入 | 可选本轮profile | 官方ARM64供应核对 | QEMU / AVF |
| --- | --- | --- | --- |
| Ubuntu24.04 | Podman、code-server | labwc0.7.1/wayvnc0.7.2也有，但本轮首desktop实现只开放Debian；Podman4.9.3 | QEMU数据面可构建待设备；AVFpermission/network未实测 |
| Debian13 | labwc、Podman、code-server、DockerCLI | labwc0.8.3、wayvnc0.9.1、waybar0.12、Podman5.4.2、pureDockerCLI26.1.5 | CPUcopy两字节adapter实际实现；device两者NOT_RUN |
| Fedora43 | 无本轮安装选项 | KDE/labwc/niri供应记录、Podman5.6.2；不能套同脚本 | 基础发行版由第一会话验收，本轮增强NOT_RUN |
| ArchLinuxARM | 无本轮安装选项 | rollingversion固定研究revision；Podman6.1.4不能套slirp旧默认 | 同上 |
| openSUSELeap16 | 无本轮安装选项 | 包/架构元数据、Podman5.4.2；seat/Qt/renderer另核 | 同上 |
| Alma major9固定root9.8 | 无本轮安装选项 | EPEL/当前AppStream核对，不按major猜9.6；labwc/niri缺包 | 同上 |
| Rocky9.6 | 无本轮安装选项 | vault9.6与当前major9仓分别记录；KDE/Xfce不当Waylandframe完成 | 同上 |

## 性能与真实限制

本轮没有guestGPU/显示FPS/输入延迟/手机共享存储性能数值。UI只测实际帧与呈现，静态桌面低damageFPS不能作为硬件性能。

唯一完整合成IO基准来自WindowsJDK17：65MiB/1025精确files staging+proof/hash **23270.6756ms**、CPU **22812.5ms**、peakRSS **255438848B**；64MiB sequential-read两轮50.8/51.7ms；1024×1KiB create648.5ms/stat15.3ms/traverse21.3ms；双向copy144.6/123.2ms；真实TCP截断82.7ms/cancel29.1ms。OScoldcache未可控，手机/SAF/9p/virtiofs未运行，数值仅宿主合成路径。不能把这次成本称共享性能已经优化；proof/每chunk校验的开销需后续Android持有FD版本验证基准。

## 最终验收门

最终APK/ELF/sourceSHA、NOTICE、build锁、校验脚本与设备交接在[集成指南](phase5-integration-guide.zh-CN.md)及delivery目录。独立Standards/Spec报告分开，早期requestchanges与修复证据保留，不覆盖失败历史。source localapprove仅本地交付，不是AVF/GPU/USB/全七发行版完整产品验收。

报告人：Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计
