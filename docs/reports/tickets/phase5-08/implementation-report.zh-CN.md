# Phase 5 隔离整合与本地交付报告

- 票号/标题：Phase5-08，同一 Full Linux 实例的真实显示/可选功能/本地服务与诚实能力。
- 协调者：Codex；用户指定 ask-matt，采用 wayfinder/research→spec/tickets→并行implement/tdd→独立规范轴/规格轴审查。
- 分支 `phase5/graphics-productivity-storage`；HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`；没有 commit、git add、push、远端 PR/release。

## 1. 任务目标

将文档要求推进到原创源码、构建、运行可独立执行的验证和可审核本地产物，保留第一会话的Linux基线/终端/主题/网络；设备独占与架构/安全边界不扩张。整合新显示/输入、恢复安装、Rootless开发功能与严格数据原型，并把无法设备验收或权限受阻的项明示。

## 2. 实际完成

- 四条独立 research 并行完成，23个参考目录中22个固定官方clone（含新增desugaring许可参考），AOSP与linuxUSBIP无法完整clone的失败/固定HTTP源码记录保留。各结论有URL/revision/许可/源码位置和未运行边界。
- 创建主整合+graphics/profiles/data/AVF/9p独立worktree；精确复制第一会话必要生产依赖95文件及对应测试闭包，摘要/before字节保全；后续只读核实一个forwarding依赖修复并记录refresh。没有整仓/Git历史/凭据/实例归档。
- 真实Wayland CPUcopy字节通路：独立perVMtoken/authrelay、严格RFB/frame资源门、真实input/状态/EOF/重连，删除host模板帧；App专用filesystemdisplayendpoint与AVFfreshvsockconnector共用codec。没有启动即CONNECTED/虚构60FPS。
- guest控制：保持v1HELLO唯一reader，新增FEATURES1白名单/version动作、3shelperdeadline与拒绝后PING；原DISPLAYprefix/LAUNCH/错误ACK/断连修复。32hexcapturegeneration确认拒缺参/越界/未知profile。静态AArch64源码重编而非分发旧agent。
- 首Debian13labwcprofile，普通用户seat/headless、实际globals/capture/尺寸/currentgeneration与Surface非纯色真实帧才ready；输入Unicode保守未验但键盘/ASCII和触控板回退有通路。UI选择默认仅终端，进度/取消/失败/重试可查，实例切终端不关VM。
- Podman/DockerCLI/code-server独立guestprofile，Python签名bootstrap等待基础Linux、锁/超时/取消/ENOSPC/升级配置保全；code-serverfixed输入与随机强凭据、普通用户loopback/authHTTP/service、精确tarstage回收。CLI不会冒daemon成功。
- 手机IDEorigin与release-specificresources、安全WebView边界、单editor/cookie清理、临时loopbackforward/显式guestNICrelay和LAN期限；退出等待unexpose终态，失败保留owner/可见提示并阻止新scope，不暗开cloudtunnel。
- 原生呈现：ARGB/RGBA/stride、currentPBO静态末帧、GL/EGL/swap实际失败返回、context重建、main-threadVSYNC、latestframeSurface重附；触摸按真实frame尺寸coalesce且没有Handler整帧无界队列。
- 官方AVFAPI/双权限/CPU拓扑/PFD、停止锁外/迟到FDexactonce，持久get/config逐字段匹配而非删除VM；标准只读9popcode/tag/framing/限额/路径securehandles/回收。USBFD模型与严格Downloadscatalog/staging原型有实际tests/benchmark，但不标生产透传/挂载。

## 3. 本地最终产物

在外部 `Orchestella/artifacts/phase5-parallel-20261013/delivery/`：

- `Orchestella-Phase5-local-debug.apk`：**71,892,457 B**；SHA256 **f58c865d8e83ef8c7629c9cf3ad7037e7f3b3a1e27d6452e7386847a2fed2623**。独立ID `io.github.nyzeep.orchestella.phase5`，min26/target36/arm64-v8a，签名v2验证通过。
- `guest-agent-aarch64-musl`：**463,360 B**；SHA256 **2713462bf7dad13f166dce51a84f0f456f2f28200367c9f156720e9953bb3117**；静态ELF64AArch64无PT_INTERP/PT_DYNAMIC；源码SHA **7b914f42571ab31394fa71ee04eb6597e64391b528d8cb087d0d449068348d94**。
- runtimebundleSHA **fd249df921cfc2ae08c0a14465d847c6726ed3a54862ca753012297249a8b8ae**，manifest一致，除agent外428entry保全。
- `apk-content-verification.json`证明kernel/bundle/manifest/QEMU、canonicalguestinit与七optionalpayload byte一致；`public-references.json`、`phase5-source-manifest.json`、`phase5-integration.patch`、`build-lock.json`、`SHA256SUMS`、设备时间请求与工具复现入口随目录提供。

APK包含NOTICE.PHASE5与原kernel/QEMU许可记录，新增desugar_jdk_libs2.1.5采用GPL2Classpath例外许可文本和输入SHA；code-server不进APK，是显式guest下载，安装保留MIT/thirdpartynotices。此批本地审查，不公开发布；公开发布完整对应源码门未执行。

## 4. 测试与证据

真实原件位于coordinator/A/B/C/desktop各owned evidence及preserved。最终 `coordinator/evidence/phase5-delivery-build.log.txt`：**BUILD SUCCESSFUL**，contract/instance/QEMUcheck、AVFengine、App **61JUnit**、NDKarm64native与assembleDebug通过。gate发生在最终sizecoalescing/输入/context改动之后。所有测试数的累计重跑不合并造总数。

- 图形codec：初版17、新ASCII目标后18wireJUnit；17既有contract入口；relay15actualLinuxprocess/pipe/socket/execprobe与合成权限测试分开。
- guest控制：actualPOSIXstdin/testTCP **50/50cases、865assertions、96helpers**，25对响应/调用一致；首次缺generation产品失败+RSTharness失败保留，修复后全过。只此测试family选择是testmacro，不能升级成nativeAF_VSOCK实测。
- profiles：原full **25项24pass/1拒绝原因断言失败**；修正后该targetpass，未重跑整套。后续unit1→2三目标、staging六故障+四回归、ENOSPC/ignoreTERM/弱secret/race/relaydead等定点均过；独立review另保留旧snapshot180s timeout与freshtargetgreen，不虚构25全绿。
- data：26JUnit+17contract，真实TCP/HTTP/文件截断/取消/lease；13合成IOmetrics。65MiB/1025files stage23270.6756ms/CPU22812.5ms/peakRSS255438848B，详矩阵；不是手机改善数值。
- 9p：18AVFmain、strictJava17/Linuxstdiochild，1500sessionFD8→8、400symlinkatomicrace0outside。Windows/缺secureprovider拒绝；Androidprovider未验。
- AVFAPI：19JVM契约，独立latchstop与lateFD回收复评通过；官方persistent恢复/配置12mismatch、runfailedretry目标通过；真实平台未运行。
- 原生颜色转换程序实际红/蓝/alpha通过。APK内容/签名/aapt身份实际通过；设备交接脚本在REQUESTED_NOT_GRANTED状态确定性拒绝、未发ADB操作。

## 5. 独立审查

Standards与Spec两轴独立执行，早期integration/editor/profile/API报告与失败原件保留。实际P2已逐目标修复：子进程SIGCHLD、页面interrupt共享control、connect缺调用、末帧/失败呈现/色彩、generation/灰placeholder、触摸尺寸无界队列、profile取消/锁/弱凭据/组身份、IDEuidhelperNNP与unit升级、tarstage取消残留、AVF连接锁与持久冷恢复。限定localsource审批不等于产品设备验收。最终结论见 `docs/reports/reviews/phase5-final/`，不自审worker本人owned域作独立批准。

## 6. 未完成 / 架构门 / 风险

没有占用第一会话荣耀设备：全部ARM64guest桌面/容器/IDE/USB/SAF/AVF实机步骤 **NOT_RUN**。没有GPU硬件renderer与zero-copy，权限/buffer真实限制由ACR-01保留；没有KDE/niri安装profile、七发行版统一桌面、Dockerrootlessdaemon、USBnativecrossprocessFD/VMMpassthrough、AndroidSAFproductionbroker或真实手机IO改进。AVF本地IDEforward尚不支持并明确拒绝。WebViewcookie/WS/SW全流量、contextloss与CJK真实apps需交接后验收。9pJava特殊file/FIFO并发替换残余DoS边界与missingprovider已披露。

APK为独立debugpackage，不自动迁移主应用实例；首次创建需实际下载发行版。默认回环，LAN明示仅10min但无TLS，设备负例尚未运行。宿主ClassA永久保留，无系统PATH/注册表/驱动/服务/虚拟化/ADB改动；一个worker首轮合成JVMtemp用了C盘默认路径，已定位最小目录、记录偏差、未清理或扩大读取；以后所有输出改D根java.io.tmpdir。不得把这份报告写成所有目标“完全可用”。

## 7. 恢复 / 后续动作

[集成指南](../../orchestration/phase5-integration-guide.zh-CN.md)提供文件所有权、before哈希、buildflags、必要回归和设备授权后可运行步骤；[验证矩阵](../../orchestration/phase5-verification-matrix.zh-CN.md)逐发行版/profile/策略区分证据。设备请求仅REQUESTED_NOT_GRANTED，需明确交接才执行脚本；这不阻塞当前本地可审查产物交付。GPU/USB真正新暴露与平台权限需后续用户裁定ACR，不擅自修改acceptedADR。

报告人：Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计
