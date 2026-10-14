# Phase 5 恢复与集成指南

- Scope：2026-10-13 第二开发线的隔离实现、本地构建与审查交付；无 commit/push/远端 PR/release。
- 工作区 `Orchestella_phase5_dev`；分支 `phase5/graphics-productivity-storage`；HEAD 基线 `8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 第一会话 `Orchestella_dev` 和荣耀 MEY-AN00 始终独占；此批没有 APK 安装、force-stop、ADB server 重启或设备输入。

## 本地产物与基线

最终入口是本报告、[验证矩阵](phase5-verification-matrix.zh-CN.md)及外部 `Orchestella/artifacts/phase5-parallel-20261013/delivery/`。最终 APK、SHA256SUMS、源码 patch、文件级 manifest、build lock 和设备时间请求均在那里。

本批按明确生产闭包复制第一会话接口（没有整仓/Git 历史/凭据/实例数据归档），before 字节仅保存在 `coordinator/dependency-baseline/`。`production-dependency-baseline.json`、`dependency-test-baseline.json`、`forwarding-dependency-refresh.json`说明复制时文件摘要、旧 HEAD 差别及后来单文件更新。最终 patch 相对此精确 before 生成，避免把第一会话未提交修复反向覆盖。正式文档因既有 `/docs/` ignore 规则需显式纳入本地 patch；不依赖 `git diff` 自动列出报告。

## 模块所有权与合流顺序

1. 协调者：共享 BridgeClient/FrameData、guest-agent、guest init 生成器与 entry、QEMU/AVF App adapter、现有页面、独立构建入口和 payload。
2. 图形 worker：纯 Java RFB codec/input/channel、独立 Python display relay和测试。
3. profiles worker：白名单 guest feature controller、bootstrap、session/rootless probe、固定输入与状态模型。
4. data worker：严格服务 origin、真实 TCP/lease、授权精确选集 staging/catalog与 USB FD 原型。
5. AVF API worker：官方方法/双权限/FD ownership、可取消连接、持久 VM 配置核验；9p worker：标准 framing/tag/opcode、只读 secure-relative访问和回收。

当前 worker 工作区均有未提交成果，保留供审查，不做 clean/reset/stash/自动删除。main 只读。若将 patch 合入当前第一会话，先在另一个隔离工作区 `git apply --check`；基线文件已经再次改变时按文件比较并手工移植 Phase 5 变化，不能强制三方覆盖整份文件。Gradle/guest-agent/init/Launch adapter是共享热点，要由协调者串行处理。

## 构建与输入

短路径：`P:` 仅映射本批主隔离工作区，`Q:` 映射本批外部 artifact；现有 W:/V:/O: 不重绑。SDK `D:/AndroidSDK`，Java `D:/JDK17`；local.properties为正斜杠。全部 Gradle output/project-cache/daemon/TEMP都在本批根，依赖通过 `GRADLE_RO_DEP_CACHE=V:/gradle-home/caches`只读复用。

本地构建命令（已取得对应输入后可复现）：

```bash
JAVA_HOME=D:/JDK17 GRADLE_USER_HOME=Q:/gradle-home \
GRADLE_RO_DEP_CACHE=V:/gradle-home/caches ANDROID_USER_HOME=Q:/android-home \
TEMP=Q:/temp TMP=Q:/temp MSYS_NO_PATHCONV=1 \
cmd.exe /d /c 'V:\gradle-8.13\bin\gradle.bat -p P:\ --offline --no-daemon --console=plain --project-cache-dir Q:/build/project-cache -I Q:/build/isolate-build.gradle -Porchestella.phase5.debugKeystore=Q:/build/phase5-debug.keystore -Porchestella.qemu.nativeLibDir=Q:/build/payload/jniLibs -Porchestella.qemu.assetsDir=Q:/build/payload/assets -Porchestella.qemu.initramfsDir=Q:/build/payload/resources :runtime:contract:check :runtime:instance:check :app:runPhase5JvmAppTests :app:assembleDebug'
```

Phase 5 调试签名与 `applicationIdSuffix=.phase5`只在明确提供上述 debugKeystore property时启用；未读取/复制 release 签名材料。最终包为 `io.github.nyzeep.orchestella.phase5`，不会更新主应用。这个副应用默认没有第一会话实例；不得复制真实实例 metadata/root盘作为隐式测试输入。签名不是公开发布凭据。

guest-agent通过 `tools/phase5/build-guest-agent.py`在已校验 musl sysroot + NDK clang 下静态 AArch64 重建；`repack-guest-agent.py`只替换唯一agent条目，其他428项payload byte/mode保全。新的 bundle digest与manifest同时更新。`sync-guest-init.py`嵌入六个profile输入和唯一displayrelay，`--check`核对源与APKtemplate；可选payload写入失败不停止基础Linux。APK内容通过 `tools/phase5/verify-apk.py`校验agent/bundle/manifest/init/七输入/NOTICE，无设备动作。

code-server4.141.0官方ARM64 tar已实际下载校验于B/inputs；安装器逐大小/SHA gate、受控解包、精确暂存回收；不执行未签名安装脚本。Podman hello与Alpine ARM64 leaf digest固定于输入锁。Linux测试复用现有Ubuntu-24.04 WSL；额外 musl/JDK 输入仅保存在外部目录，未安装系统包或修改WSL设置。第三方许可见 NOTICE.PHASE5、GPL/Classpath文本与每个research revision记录。

## guest / App 调用

版本化扩展 `ORCHESTELLA/FEATURES 1 ACTION PROFILE`只在既有 v1 HELLO/token认证后可调用，动作与四profile白名单。安装/启动受理不是ready。旧agent返回unsupported不可假成功；拒绝DISPLAY/FEATURES后PING仍可通信。唯一control reader与长期HELLO/短普通请求期限保持。

```sh
/usr/local/lib/orchestella/profilectl probe desktop-labwc
/usr/local/lib/orchestella/profilectl install desktop-labwc
/usr/local/lib/orchestella/profilectl status desktop-labwc
/usr/local/lib/orchestella/profilectl start desktop-labwc
/usr/local/lib/orchestella/profilectl cancel desktop-labwc
/usr/local/lib/orchestella/profilectl stop desktop-labwc
```

桌面首组合只提供Debian13 ARM64 labwc/headless/pixman/wayvnc。Wayland捕帧、token字节流、宿主Surface和GPUrenderer是不同证据。当前generation的grim/protocols与实际新非纯色帧成功present后才CONFIRM；wayvnc灰色占位不会ready。设备必须独立证明画面随应用/输入改变。RFB仅明确CPUcopyfallback，非原生Wayland协议/零拷贝。触摸使用鼠标/触控板回退；键盘与ASCII IME有实际编码路径，CJK/emoji保守显示未验证、不自动启用clipboard绕过。

Podman支持Debian/Ubuntu的显式 profile，普通用户run/info/DNS/TLS/volume/port结果独立；DockerCLI只Debian纯包，daemon未运行。code-server默认为普通用户127.0.0.1:8080，私有0600配置随机凭据；用户在guest终端显式取配置登录，状态/URL/日志不返回secret。App临时EXPOSE guestNIC18080，再通过实际确认的hostloopback映射打开；失败/退出等待UNEXPOSE终态，撤销失败提示并保留owner用于回收。LAN仅明确按钮与10minlease0.0.0.0:18081，认证失败/health失败/页面退出/到期关闭，无持久autoenable，无cloudtunnel。

## 设备交接与未运行验收

`tools/phase5/request-device-window.py`只写本地请求，不操作设备。只有协调会话明确交接、填入本地 grant 后，才执行 `validate-after-handoff.py`。grant必须包含 owner=phase5、status=GRANTED、准确 deviceSerial；安装还需 installPhase5DebugApproved=true。当前交付的请求是 REQUESTED_NOT_GRANTED，不是授权。

交接后流程：

1.核对第一会话释放设备与当前实例无共享操作，再核Android/API/AVF MANAGE与USE_CUSTOM权限；不根据Snapdragon/Android版本推断GPU。
2.按grant安装 `.phase5`包，启动后创建单独Debian13/Ubuntu24.04测试实例。先跑nativeinit/CA/DNS/gvproxy与coldboot，保存rootfs数据一致性证据。
3.选择labwc后记录实际签名下载计划、取消/重试/磁盘满；观察guest正常用户、真实wayland globals、grim捕帧、Android画面与输入damage、rotation/Surface/IME/断流重连；guestrenderer glxinfo/eglinfo/vulkaninfo与hostbackend分开记录。
4.普通用户profilectl start podman，核固定digest容器执行、DNS/TLS/volume/localhost端口及stop；Dockerdaemon缺项明确拒绝。
5.code-server认证/login、手机WebView打开文件、extension/websocket/SW/navigation、端口冲突/servicecrash、回后台/旋转/stop、LAN到期/关闭listener移除。跨实例cookie/ServiceWorker隔离尚需设备负例验证。
6.SAF heldFD证明/实际Downloadsgrant与USB拒绝/热拔出/nativefdstage仍未production接入；按照研究探针/原型运行，不把JVM catalog或FD模型视为guest共享/USB直通。

guest内只读检查脚本：

```sh
ORCHESTELLA_PHASE5_RUN=1 sh guest-runtime-smoke.sh
```

原始证据只写 `.log.txt`/`.json`/`.md`至本批device/evidence，并即时preserved；不复制凭据或实例根盘。`validate-after-handoff.py`预检/安装完成也不会写功能ready，验收矩阵必须人工按证据更新。

## 回归与限制

每次改共享链重新运行：contract check（含唯一reader/拒绝后PING）、instance feature model、engine QEMU launch/payload、AVF官方shape/FD/stop/persistentconfig、标准9pLinuxprocess、Appdisplaygeneration/key/nativecolor/firstboot/networkforward；profile变更只跑其受影响Linux进程负例和配置保全目标，失败历史保留，不把旧全套24/25和后续定点通过改写成未经运行的全绿。

尚未完成：真机图形/GPU性能、AVF/OEM可运行性、全七发行版可选桌面、KDE/niri实际profile、Dockerrootlessdaemon、SAFproductionbroker、USBnativeVMM透传和真实手机共享IO基准。相关权限与buffer架构冲突记录在ACR-01，未修改acceptedADR；现有源/宿主测试可交付，不宣称完整Phase5真机验收。

报告人：Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计
