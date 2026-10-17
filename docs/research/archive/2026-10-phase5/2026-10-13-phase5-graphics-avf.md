# Phase 5 研究：真实图形数据面、AVF 权限与 CPU copy fallback

- 触发工单：`.scratch/orchestella-phase5/issues/01-real-graphics-and-avf.md`；日期：2026-10-13。
- 执行者：graphics-research supporting child / Codex，会话配置模型；亲自研究，未再派生代理。
- 分支与基线：派工记录指定 `phase5/graphics-productivity-storage`，基线 `8d0e8c75d769ecc645ea102b90a304c8657344a1`；本研究没有 commit、push、PR、release 或分支操作。
- 状态：**源码与公开资料研究已完成；CPU fallback、AVF 启动、GPU 和设备端到端验证均未实施、未运行。** 本文为协调者的实施输入，不改变 tracker、ADR 或产品能力状态。

## 1. 背景、范围与证据解释

目标是回答普通第三方 Android App 能否利用 AVF/custom VM/GPU，现有 App 实际消费什么图形，以及怎样让真实 guest 桌面和输入在 Android Surface 上闭环。必须区分 guest 光栅化、跨 VM 传输、宿主上屏三个阶段；宿主能创建 GLES Surface 不等于 guest 已获 GPU 加速。

本次按用户授权只读隔离源码、活动主仓必要文件及已有 Podroid/termux-x11。隔离目录没有指定防御约束和 ADR 的完整副本，故从活动主仓读完 `AGENTS` 所引用的防御约束全部文件，以及 HOST、ARTIFACT、ARCHITECTURE、ASSET、REPORTING 政策；派工记录和图形工单从隔离目录读取。政策中的历史 DroidLayer 根名不用于创建 shadow workspace，本批采用用户明确指定的 Orchestella 根。

本文路径别名如下，源码行号为通常从 1 起算的行号；主要函数名同时给出，避免协调者后续编辑导致行号漂移。

| 别名 | 绝对路径 | 用途 |
| --- | --- | --- |
| W | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev` | 隔离源码只读；唯一正式输出位于本目录 `docs/research/` |
| M | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella_dev` | 活动主仓只读，政策、ADR、必要接口、已打包 QEMU |
| R | `D:/Development project(开发项目)/Orchestella开发目录/phase5-references` | 本代理所属的新公开源码参考 |
| E | `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/evidence` | 原始网络、clone、许可、ELF 和定点文件 hash 证据 |

`E/local-source-evidence.json` 记录 13 个必要源码文件的采样 hash，并比较 M/W。QemuWaylandChannel 与 QemuLaunchSpec 的原始字节不同，但归一化换行后正文相同。协调者仍在工作，本文事实对应研究采样时的实现，后续修改应重新审查对应函数。没有读取凭据、实例数据、`.env` 或 Git object 内容，没有上传宿主文件。公网仅请求公开资料、Git 源码及 crosvm 锁定的公开 crate。

`research` Skill 工具调用因项目/全局同名条目歧义被拒绝；随后读取项目 `.agents/skills/research/SKILL.md` 并遵循其 primary-source 和单文档要求。该文件要求 background agent，但用户明确禁止进一步派生，因此由本 child 亲自完成。

## 2. 问题清单与主要结论

1. **MANAGE 不是 custom VM/GPU 的充分授权。** AOSP 将 MANAGE 定义为 `signature|preinstalled|development`，USE_CUSTOM 为 `signature|development`；原始 Linux/custom 配置另查 USE_CUSTOM。普通安装 App 没有可弹窗申请的普通运行时授权。开发性授权是否可用还受设备构建、API 可访问性、服务和 SELinux 约束。
2. **平台 Terminal 的能力不能直接移植为第三方 App 的能力。** 它采用 `platform_apis: true`、`privileged: true`、框架实现库和内部显示 Binder 服务；GPU 配置经过平台编译开关及 crosvm 参数装配。
3. **现有 QEMU 图形是模板；AVF 图形类没有接入实际 App session。** QemuWaylandChannel 在宿主生成背景、默认 CONNECTED，输入只记日志。AvfSession 没有覆盖 `waylandChannel()`/`displayControl()`，DesktopActivity 取得 null。guest display-agent 不捕获、不发送帧，指针/触摸/按键分支也没有注入。
4. **当前打包 QEMU 有真实 VNC、virtio-gpu 2D、virtio-serial 和输入设备实现。** 对实际 ELF 的静态检查支持此结论，不仅是查询上游或看启动参数；没有发现真实 virgl/rutabaga 注册和初始化符号。运行时列举、启动和 RFB 握手尚未执行。
5. **普通 vsock/virtio-serial 是字节通道，不传 guest FD。** guest 内核与 Android 内核仍分开，QEMU TCG 也如此。memfd/SCM_RIGHTS 的同内核 IPC 不能替代 virtio GPU blob、映射和同步协议。
6. **最小首通路可保留 Wayland guest。** labwc/wlroots headless + pixman → wayvnc 捕获及虚拟输入 → guest 内受限 Unix RFB → 新专用 virtio-serial 的 token 鉴权代理 → 宿主原创 RFB 解码 → 现有帧/Surface 接缝。获准且修正适配后的 AVF 可使用同一字节协议走 vsock。RFB 是首个 CPU 传输/对照方案，不能冒称原生 Wayland FD 零拷贝或 GPU 成功。
7. **需协调者执行 Architecture Change Policy 影响检查。** ADR-0012/0017 的“QEMU guest 与宿主共用 Linux 内核、直接 ashmem/memfd”假设与真实 VM 边界冲突；性能数字和“VNC 的物理固定上限”缺少本项目实测。ADR-0019 的已实现 GPU 前提也不成立。本 child 仅提交证据，不改决策。

## 3. 官方参考、固定 revision 与许可证

先检查所属目标不存在，再确认维护源及许可，执行新目录的 `git clone --depth=1 --filter=blob:none --sparse` 和首次稀疏设置。没有 pull/checkout 或修改已有参考。实际成功 **4 项**，达到至少三项要求。

| 项目 | 官方维护源与固定 revision | 本地参考与许可 | 证据 |
| --- | --- | --- | --- |
| QEMU | [维护仓](https://gitlab.com/qemu-project/qemu)，tag `v11.1.1`，`c3d48b7d1e89604920e5b81b91140c2ad39a1943`；[官网仓地址](https://www.qemu.org/download/) | `R/qemu`；[LICENSE](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/LICENSE)：模拟器整体 GPLv2，具体文件和 firmware 分别审查 | `E/qemu-clone.json`、`E/reference-provenance.json` |
| crosvm | canonical [Gitiles](https://chromium.googlesource.com/crosvm/crosvm/)；[官方书](https://crosvm.dev/book/)指向 Google 镜像；实际 clone [google/crosvm](https://github.com/google/crosvm) at `0e9c99daf64e6f5f57f416fa5cfb949f3199c48b` | `R/crosvm`；[LICENSE](https://github.com/google/crosvm/blob/0e9c99daf64e6f5f57f416fa5cfb949f3199c48b/LICENSE) 为 BSD-3-Clause | `E/crosvm-official-clone.json` |
| gfxstream | [google/gfxstream](https://github.com/google/gfxstream) at `07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe` | `R/gfxstream`；[LICENSE](https://github.com/google/gfxstream/blob/07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe/LICENSE) Apache-2.0；构建闭包/第三方仍须逐项许可审查 | `E/gfxstream-clone.json` |
| virglrenderer | [维护仓](https://gitlab.freedesktop.org/virgl/virglrenderer) at `c8e3fc3bd2f367d92fb1027061fe51afeec5136e`；其 README 明示其他仓库可能是 fork | `R/virglrenderer`；[COPYING](https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/c8e3fc3bd2f367d92fb1027061fe51afeec5136e/COPYING) MIT | `E/virglrenderer-clone.json` |
| AOSP Virtualization | [官方仓](https://android.googlesource.com/platform/packages/modules/Virtualization/)；HTTP 固定 main `175a51b30123fa6b02b541f1969665708f7ec2c3`；android16-release `c984fc337c11ca5edc03ccf02037b2455dd8fcaf` | **Git clone 失败，R/aosp-virtualization 未作为完整 checkout 交付**；选定源码分别存 `E/aosp-pinned`、`E/aosp-android16`；源码 Apache-2.0 标头和 Android.bp 的 `Android-Apache-2.0` 已核对 | `E/aosp-virtualization-official-clone.log.txt`、`E/aosp-pinned-api-downloads.json`、`E/aosp-android16-downloads.json` |
| rutabaga_gfx | crosvm Cargo.toml:222 锁 `=0.1.80`；[crate 元数据](https://crates.io/api/v1/crates/rutabaga_gfx/0.1.80) 指向 [magma-gpu/rutabaga_gfx](https://github.com/magma-gpu/rutabaga_gfx)，包内 VCS pin `d1b06416e2353c99c99e61eab15560484f45496f` | `E/rutabaga_gfx-0.1.80` 是有限文件证据，不是第五个 clone；BSD-3-Clause；crate SHA-256 `2817d168222af3c49269f583dd8e67429912953ae608f715fd97683ed50320a5` 与发布 checksum 相符 | `E/rutabaga-crate-download.json` |
| Wayland CPU 首源补充 | [any1/wayvnc](https://github.com/any1/wayvnc) at `b286ab91d0f41056317fb942e20fd117c2561228` | 只下载官方选定文件到 `E/wayvnc-pinned`，未修改桌面代理的独占 clone；COPYING 为 ISC | `E/wayvnc-source-downloads.json` |

AOSP 两个 HTTP snapshot 的提交时间分别为 2025-03/2025-04，不当作“2026 最新代码”或 OEM ROM 的精确版本。固定 revision 有利于复核，不能证明特定设备采用该版本。clone checkout 的 CRLF 和 HTTP 原字节可能不同，hash 按各自实际证据保存。

有限失败明确记录：AOSP Git 连接 443 失败、exit 128；crosvm canonical Git 连接失败后使用官方书列明的 Google 镜像成功；旧 AOSP javalib 路径 404 后通过官方树找到 `libs/framework-virtualization`；Android 16 权限 manifest 一次 SSL EOF，main manifest 和 Android 16 README/service/API 仍取得；WebSearch 工具因 provider 不编码 native WebSearch 失败，未使用其结果。没有循环重试或因此上传本地内容。原始资料及即时保全副本在 E 与 `E/preserved`。

## 4. AVF、Terminal 和 GPU 权限边界

### 4.1 权限与功能必须逐层验证

[官方权限 manifest](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/android.system.virtualmachine.res/AndroidManifest.xml#26) 的 MANAGE 定义位于 26–27 行，USE_CUSTOM 位于 33–34 行，并明示后者不供第三方应用使用。[framework README:13–21](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/README.md#13) 说明 API 34 引入、全部为 SystemApi、MANAGE 为受限权限及开发授权语义。

| 阶段 | 官方事实 | 对 Orchestella 的含义 |
| --- | --- | --- |
| 平台存在 | framework README:24–55：manager、feature 和 `getCapabilities()` 分开检查 protected/non-protected | OS/API/SoC 名不能直接宣告 custom VM 可用 |
| 普通 VM 管理 | `android/virtualizationservice/src/aidl.rs:274–279` 的 VM context 分配查 MANAGE | MANAGE 失败即停止 AVF 路径 |
| custom Linux VM | [virtmgr aidl.rs:674–683、1040–1048](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/virtmgr/src/aidl.rs#674)：RawConfig 是 custom，另查 USE_CUSTOM | 仅检查 MANAGE 的授权向导/HostFacts 不充分 |
| GPU/显示装配 | 同文件 874–893：`paravirtualized_devices` 编译条件下消费 display/gpu 配置；[crosvm.rs:1294–1337](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/virtmgr/src/crosvm.rs#1294) 装配 `--gpu`、context-types、EGL/GLES/Vulkan、`--gpu-display`、Android display service | 不存在“拿到 MANAGE 自动配置任意 crosvm GPU 参数”的推论 |
| 平台显示服务 | [virtualizationservice aidl.rs:226–254](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/virtualizationservice/src/aidl.rs#226)：set/clear/waitDisplayService 查 MANAGE 和 USE_CUSTOM | 有 Surface 对象仍不代表有该 Binder 数据面权限 |

[Terminal Android.bp:33–40](https://android.googlesource.com/platform/packages/modules/Virtualization/+/175a51b30123fa6b02b541f1969665708f7ec2c3/android/TerminalApp/Android.bp#33) 依赖 framework implementation，设置 platform_apis 和 privileged；本文件未证明具体签名证书，故不额外断言它一定使用某张平台证书。Manifest 请求两权限。`ConfigJson.kt:80–87、92–126、268–292` 构建 VirtualMachineConfig/CustomImageConfig、GPU 参数；`Runner.kt:63–88` 创建 VM 后调用 run。`DisplayProvider.kt:41–44、69–75、88–118` 通过内部 virtualization service 获取显示服务再传 Surface。以上均在固定 AOSP snapshot 的 `android/TerminalApp/java/com/android/virtualization/terminal/`。

[Android SELinux，android16-release pin `d4a7f392598cee96d9479a8ac0f84259c19b043a`](https://android.googlesource.com/platform/system/sepolicy/+/d4a7f392598cee96d9479a8ac0f84259c19b043a/private/crosvm.te#9)：crosvm.te:9–20 将 VM manager/KVM 设备访问限定到平台域；102–108 的 GPU、allocator、SurfaceFlinger 权限也在平台条件内。把 crosvm 二进制放进 APK 不会给 App 这些平台域权限。没有执行 pm grant、Shizuku 或任何设备操作。

### 4.2 现有 AVF API adapter 与真实签名不匹配

W/M 中 `runtime/engine-avf/.../ReflectiveAvfVirtualMachineApi.java` 的反射类存在性检查只表示类可解析，不能证明运行可用。与 main 和 Android 16 snapshot 比较：

| 现有代码（方法/采样行号） | 官方 snapshot | 结论 |
| --- | --- | --- |
| `startCustomVm`，95–123：CustomImageConfig.Builder.build 直接作为 manager config；`createVm`，324–328 查 createVm/getOrCreateVm | VirtualMachineManager.java:218、310 是 `create/getOrCreate(String, VirtualMachineConfig)`；Terminal ConfigJson.kt:80–87 将 custom image 放入 VirtualMachineConfig.Builder.setCustomImageConfig | 两层配置类型和方法名均须适配 |
| `addRootDisk`，197–203 查 `addDisk(String,boolean)`/`addDisk(String)` | CustomImageConfig.java:520 为 `addDisk(Disk)`；Terminal 使用 RWDisk/RODisk | 现有 fixture 不代表 Android 方法签名 |
| 对 custom image builder 查 setMemoryMbs、setProtected、setNetworkSupported、setCmdline 等 | protected/memory 在 VirtualMachineConfig.Builder；custom image 用 useNetwork/addParam；具体版本逐方法检查 | “optional skipped”不能掩盖配置没有生效 |
| `connectVsock`，131 查 int；`adaptChannel`，376–378 查 getInputStream/getOutputStream | [Android 16 VirtualMachine.java:2093–2098](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachine.java#2093) 是 `connectVsock(long)`，返回 ParcelFileDescriptor | Android 侧需 FD 适配、明确所有权和关闭语义 |
| create 后直接返回 ReflectiveHandle，未见 `vm.run()` | framework README:81–112、Terminal Runner.kt:88 均调用 run；创建持久 VM handle 与启动是两件事 | 不能以 create 成功宣告 guest 已运行 |
| 类注释声称 Android 15+ 已是 public SDK | 官方 README 和 Java `@SystemApi` 不支持该断言；GpuConfig/DisplayConfig 还为 `@hide` | 年份/SDK >=35 不能替代隐藏 API 可用性探针 |

以上是对固定源码的静态阻断，未声称已测试所有 OEM 版本。协调者应修正 Android-boundary adapter，保持纯 JVM 契约层中立；在未完成权限和真实 API 门前，将 AVF graphics 标为不可用/未验证。

## 5. App 实际入口与误标

App 路径位于 `W/app/src/main/java/io/github/nyzeep/orchestella/`；runtime 路径位于 `W/runtime/<module>/src/main/java/io/github/nyzeep/orchestella/runtime/`。

| 实际入口 | 观察与后果 |
| --- | --- |
| `DesktopActivity.java:111–114` | 从 ExecutionSessionRegistry 的实际 session 取 displayControl/waylandChannel，再绑定 Surface；必须审查这里收到的对象，不是只找到某个 graphics 类 |
| `QemuInstanceLauncher.java` Session 构造与 waylandChannel，约 58、138–145 | 创建 QemuWaylandChannel(controlSocketName, null)，未传真实图形 stream/token；现有 bridge/proxy 各有独立通道，图形不能夺用其字节流 |
| `QemuWaylandChannel.java:49、62–101、117–145、193–200` | 初始 CONNECTED，宿主生成梯度、栏和背景，定时重复发送；输入只是日志返回 true，没有 guest 连接。resize 是重画模板。注释提到 abstract socket 不构成实现 |
| `AvfInstanceLauncher.java:27–116` + `ExecutionSession.java:58–67` | AvfSession 只暴露 terminal/control，没有图形 override，故默认 null；runtime 的 AvfWaylandChannel 类不在这一实际 UX 链上 |
| `engine-avf/AvfWaylandChannel.java:120–158、183–223` | 能握手、解析特定 DLFR 字节帧；这是自定义像素协议，不是原生 Wayland wire/FD 通道。113–115 的静态 capabilityMatrix 不能作为设备已验证证据 |
| `guest-assets/agent/display-agent.c:140–173、242–263、338–345` | wlr-randr 失败仍返回成功；pointer/touch/key 分支被丢弃；vsock-display-port 参数被忽略，实际只用 stdin/stdout；没有 compositor capture 或 frame 输出。TEXT 将解码内容拼进 system(wtype...)，必须改为受限协议/直接 argv，并做合成注入负例 |
| `WaylandSurfaceController.java:315–319` → `DoubleBufferedRenderer.java:123–154` → `NativeGlesSurfacePresenter` | 真正绑定 native GLES presenter，但当前消费的是来源提供的 CPU 像素；不能推断 guest GPU 已工作 |
| `NativeGlesSurfacePresenter.java:123–125、159–183` | native 失败后仍 ready=true，mock 路径写死 1.5ms；生产运行不得用这类计量宣告真实成功，应返回不可用或使用真正软件上屏 fallback |

**现有“零拷贝”称呼也不成立。** `contract/WaylandChannel.java:124、145–146` 在构造和 data() 时 clone byte[]；DoubleBufferedRenderer:149、181–224 将字节转成 int[]；native_gles_presenter.cpp:388–405 又 memcpy 到 PBO、上传纹理。FrameData 的 clone 避免直接复用 ping-pong array 的别名竞态，但增加真实拷贝和内存成本。CPU copy + GLES 上屏可以是正确实现，必须按实际行为命名。

协议验证当前不充分：`AvfGraphicsVsockProtocol.java:103–126` 校验 dimensions、stride 下限、format 范围和单帧 160MiB 上限，却没有强制 payloadLength 与 stride×height 一致或 stride 上限；FrameData 也没有完整长度校验。短 payload 可通过两层校验，最终 renderer 越界。`AvfPingPongBuffer.java:21–31、38–48` 初始就分配两份 1080p，最多可增长为两份 160MiB；再叠加 FrameData/data clone、int[]、PBO，不能只报告“bounded double buffer”。

## 6. 核对当前 QEMU 构建，而非假设后端存在

本次检查的实际文件为 `M/app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so`，它是 AArch64 PIE executable（ELF DYN），App 使用 ProcessBuilder 启动；不是通过 App 中的 VirglJniBridge 自动注入的同进程库。

- 大小：**132,889,376 B**。
- SHA-256：**`525469bdd64a565e7e52536915321f6ca307a976599f1e6ba72d2a6433573e3a`**，与 `tools/qemu-android/gpl/QEMU-BUILD-INPUTS` 的 qemu-system-aarch64 hash/size 相符。
- DT_NEEDED：libm.so、libdl.so、libc.so；没有 libvirglrenderer/libgfxstream/rutabaga 依赖。
- 证据：`E/packaged-qemu-static-capabilities.json`、`E/packaged-qemu-registration-symbols.json`。

| 能力 | 实际 ELF 定义 | 结论与未运行边界 |
| --- | --- | --- |
| VNC/RFB | `vnc_start_protocol`、`vnc_init_func`，另有 RFB 003.008 及完整 VNC 实现相关符号 | 有已编译的 VNC server，实现不只是 stub/字符串；尚未运行 -vnc help、ServerInit 或真实桌面 |
| virtio GPU 2D | `virtio_gpu_info`、6144 字节的 `virtio_gpu_simple_process_cmd` 等真实定义 | 有 2D backend；不等同于 3D VirGL |
| virtio-serial | `virtserialport_info`、相关 class/port 实现 | 新专用图形 virtserialport 可使用现有构建能力；真实吞吐未测 |
| 虚拟输入 | `virtio_input_send` 和 keyboard/mouse device 名 | 后端存在；当前启动参数仍需显式装配输入设备，且 guest 驱动/seat 需验证 |
| VirGL/rutabaga | 未见 `virgl_renderer_init`、`rutabaga_init` 或相应 GPU backend 注册/class 实现；有 gl/rutabaga 名字字符串 | 当前证据不支持已编译可用加速 backend；必须使用运行时设备列举及真实 capset 核验，不能拿字符串当注册证明 |

[锁定 QEMU meson.build:1420–1430、1771–1776、1950–1962、2517](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/meson.build#L1420) 和 [hw/display/meson.build:80–91](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/hw/display/meson.build#L80) 区分依赖存在、GL/rutabaga 模块、VNC 配置。项目 build-in-container.sh:294–297 未显式禁用 VNC，不足以单独证明 VNC；本次 ELF 定义补足这一证据。若重建加速切片，必须由协调者更新锁、构建输入/许可证闭包和可复现门，本 child 未更改构建。

实际 App 启动路径：QemuInstanceLauncher.launch 调用 buildWithPasst，后者 QemuLaunchSpec.java:471、481、484–488 使用 `-display none`、选择 GPU 名和既有 bridge/proxy port，没有图形 port/VNC/input 装配。随后 sanitizeSpecForTargetBinary 将 `virtio-gpu-gl-device` 改写为 `virtio-gpu-device`，约 342–352 行。App 中调用 VirglJniBridge.init 无法改变独立 QEMU 子进程的已编译设备模型。

本项目 `tools/virglrenderer/src/virglrenderer.c:398–423` 的 submit_cmd 仅检查指针和 reset 状态，不解码/执行 VirGL 命令；425–430 的 fence 立即回调；442–446 的 get_rect 不读像素。它不是已经完整集成的上游 VirGL renderer。不能将这个兼容 API 外形、EGL 初始化或 JNI 测试成功写成 guest 3D 成功。

真实 QEMU 显示/输入链在固定源码中存在：

- [hw/display/virtio-gpu.c:456–509、513–599、656–735](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/hw/display/virtio-gpu.c#L456)：guest backing iov → transfer_to_host_2d → Pixman resource → scanout surface → console update。
- [ui/vnc.c:653、811、1758–1791、2015–2027、2379–2462、3415](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/ui/vnc.c#L1758)：display listener 提供 framebuffer，RFB key/pointer 回到 QEMU input subsystem，然后虚拟输入设备送 guest。
- 该路线上 RFB pixel source 是 guest virtio GPU scanout；前提是 guest compositor 实际跑 DRM 输出。headless compositor 的输出不会自动出现在 QEMU scanout/VNC，不能混用两个源的成功条件。

## 7. rutabaga、gfxstream、VirGL 与跨边界资源

[QEMU 官方 virtio-gpu 文档](https://gitlab.com/qemu-project/qemu/-/blob/c3d48b7d1e89604920e5b81b91140c2ad39a1943/docs/system/devices/virtio/virtio-gpu.rst#L41) 在 41–63 行区分 2D、virglrenderer、rutabaga；74–115 行说明 VirGL/Venus、hostmem/blob 条件；179–207 行说明 gfxstream/cross-domain capset 和 surfaceless/headless 的区别。文档的 Linux host/guest 版本要求不能直接当 Android App 已满足。

| 组件/资源 | 固定源码事实 | 权限、生命周期与工程限制 |
| --- | --- | --- |
| rutabaga dispatcher | `E/rutabaga_gfx-0.1.80/src/rutabaga_utils.rs:213–221` 定义 VIRGL、VIRGL2、GFXSTREAM_VULKAN、VENUS、CROSS_DOMAIN 等不同 capset；core.rs:725、952、1125 实现 fence/blob/export 调度 | 它是资源/后端接口，不是获得 Android 平台权力的捷径；capset 名存在不保证启用或有 GPU 驱动 |
| gfxstream | [README:2–8、27–89](https://github.com/google/gfxstream/blob/07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe/README.md#L2) 描述 API streaming 与多构建入口；host/virtio_gpu_gfxstream_renderer.cpp:366、452–485、492–520 实现 command/blob/map/fence | 上游 Apache-2.0；没有找到既有研究所说的通用“需联系 Google 法务方可集成”条款。构建/动态链接、Android API、第三方依赖许可仍需实际验证 |
| virglrenderer | [src/virglrenderer.h:172、304–328、408–455、482–515](https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/c8e3fc3bd2f367d92fb1027061fe51afeec5136e/src/virglrenderer.h#L408) 暴露 context、命令、capset、blob、map/export、fence API | 真正解码器/依赖不可用一个空 submit_cmd 替代；GLES 版本、扩展和 EGL 上下文线程都须匹配 |
| vsock | [AOSP README:209–261](https://android.googlesource.com/platform/packages/modules/Virtualization/+/c984fc337c11ca5edc03ccf02037b2455dd8fcaf/libs/framework-virtualization/README.md#209) 支持 bulk bytes，Binder RPC 不能发送 FD | 管理服务提供 host FD；不是 guest FD 传递。pVM 可以通过获准 vsock 交换字节，不能断言 protected VM 完全禁止双向通信 |
| 同内核 FD | Android native pin `2827a4a16b0340ecd07c2d5a6c89991799b362bb`：[hardware_buffer.h:422、503–547](https://android.googlesource.com/platform/frameworks/native/+/2827a4a16b0340ecd07c2d5a6c89991799b362bb/libs/nativewindow/include/android/hardware_buffer.h#503) 提供分配、lock/unlock fence、Unix handle 发送 | App/宿主 QEMU 进程可在获准 IPC 内共享资源；普通 guest 的 memfd 数字在 host 没意义。FD 校验/关闭/CLOEXEC/只读范围必须独立定义 |
| virtio blob | rutabaga_utils.rs:73–79 区分 GUEST、HOST3D、HOST3D_GUEST 和 MAPPABLE/SHAREABLE/CROSS_DEVICE；[crosvm virtio_gpu.rs:1109–1156、1165–1257](https://github.com/google/crosvm/blob/0e9c99daf64e6f5f57f416fa5cfb949f3199c48b/devices/src/virtio/gpu/virtio_gpu.rs#L1165) 导出 Descriptor/Vulkan/ExternalMapping，经 mapper 注册映射 | 需要 VMM、backend、guest 驱动、共享窗口和系统权限共同支持；不等于一次 AHardwareBuffer_allocate 自动让 pKVM 双向映射同一纹理 |
| cross-domain Wayland | [crosvm docs/book/src/devices/wayland.md:5–18、58–70](https://github.com/google/crosvm/blob/0e9c99daf64e6f5f57f416fa5cfb949f3199c48b/docs/book/src/devices/wayland.md#L58) 要 guest virtio GPU context、sommelier、host Wayland compositor 和 --wayland-sock | Android SurfaceFlinger 不是现成 Wayland server；vsock 9103 也不是该 cross-domain context |
| fencing | crosvm gpu/mod.rs:299–344、759–782 等等待 fence completion 再归还 descriptor；VirGL/context fence 和 gfxstream fence 有 context/ring 语义 | 协议 ACK、时间戳、60Hz tick 不是 GPU completion fence。native fence FD 的所有权、超时、release/acquire 顺序不可省略 |

[app.te:393–394](https://android.googlesource.com/platform/system/sepolicy/+/d4a7f392598cee96d9479a8ac0f84259c19b043a/private/app.te#393) 允许非 isolated appdomain 使用 gpu_device；因此“普通 App 完全不能使用任何 GPU 设备”同样过度。准确边界是：自身 Surface/EGL/公开 NDK 渲染可行，具体 DRM/KGSL/udmabuf、native context、hypervisor 映射和 OEM ioctl 必须按具体节点/SELinux/扩展探测。EGL robustness 可以报告 context loss，不能保证恶意 shader 永远不造成系统 hang。

Surface/source 权限分开：自身 SurfaceView 不需要屏幕捕获权限；要读取其他 App/宿主屏幕需另有用户授权的捕获流程，本任务不引入它。guest exporter 只能读所管理 guest compositor 的输出，不得扫描 host 内容或获准共享目录。source=guest-capture 的可信性靠进程、握手、源状态与端到端挑战，不靠 host 绘制一张类似桌面的图。

已有参考只读对照：Podroid at `ce0121896b2895637ab1f656cbb0d75fa2874489`，VncClient.kt:5、41–71、188、286、299 表达实际 RFB 解码及 key/pointer；项目 LICENSE 为 GPLv2。termux-x11 at `fa3a8b430e2896a19f44c99a9cb056254615ae06`，lorie/buffer.c:377–401、516–571 使用 AHardwareBuffer/SCM_RIGHTS；项目 LICENSE 为 GPLv3。它们没有被复制或修改，不能用 termux-x11 同内核进程共享证明 QEMU guest 共用 Android 内核。记录见 `E/existing-reference-provenance.json`。

## 8. 最小可运行 CPU copy 首通路（实施建议，未运行）

建议首先实现 **guest Wayland + 受限 Unix RFB + 专用 VM 字节通道**。不改 QEMU 图形 backend，不要求 host DRM/KVM 权限，也不生成宿主模板帧。

```text
真实 guest 应用（例如 foot 显示可变化的命令输出）
  → labwc/wlroots headless + pixman 的真实 output
  → wayvnc screencopy + virtual keyboard/pointer
  → guest 专属 Unix RFB socket
  → 新 guest graphics proxy：先 per-VM token/版本握手，才打开该 socket
  → QEMU 新 virtserialport / 经批准并修正适配后的 AVF vsock 9103
  → host 原创、限额 RFB decoder
  → FrameData → VSYNC presenter → Android 自有 Surface
输入沿反方向返回实际 virtual pointer/keyboard。
```

固定 wayvnc README:7–11 明确 wlroots/headless 及虚拟输入；不支持 GNOME/KDE/Weston。main.c:417–432 要求 virtual pointer、virtual keyboard 以及 wlr-screencopy 或 ext-image-copy-capture；1063 调用 nvnc_listen_unix；2494–2496 提供 max-fps；2529–2538 提供 unary `-u` 和 external-listener-fd。来源：[固定 README](https://github.com/any1/wayvnc/blob/b286ab91d0f41056317fb942e20fd117c2561228/README.md#L7)、[固定 main.c](https://github.com/any1/wayvnc/blob/b286ab91d0f41056317fb942e20fd117c2561228/src/main.c#L417)。发行版具体 labwc/wlroots/wayvnc 版本和打包由桌面研究/协调者确认，本研究不假定所有发行版相同。

实施时可用如下 guest 侧启动形态作为示踪箭，须先验证该发行版包支持 headless/pixman、输出和 seat；这些命令本次未执行：

```sh
# XDG_RUNTIME_DIR 应为该 guest 用户独占且 mode 0700 的现有 runtime 目录。
WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_HEADLESS_OUTPUTS=1 labwc
# 在同一 guest 用户、同一真实 Wayland 会话中启动；不带 -g GPU 选项。
wayvnc -u -f 15 "$XDG_RUNTIME_DIR/wayvnc.sock"
```

labwc 的实际 socket/output/seat 名以运行观测为准，不能硬编码 `WL-1`。guest graphics proxy 必须是新增的原创数据面，打开限定的 wayvnc socket 而非允许客户端请求任意路径。RFB None security 只可位于 guest 内权限受控的 Unix socket 以及外层已鉴权 VM 通道内；若直接提供 TCP VNC，必须独立鉴权及明确可见绑定策略，不能因为 loopback 就视为独占/安全。

QEMU 参数接缝与现有 bridge/proxy 平行：新增独立 chardev 与 `virtserialport,name=orchestella.graphics`，guest 从相应 `/dev/virtio-ports/` 读取；不可占用 bridge、terminal 或 netproxy 的 stream，也不改变 gvproxy。host LocalSocket 和实例 token 绑定，预握手仅允许有限字节，不把 token、pixel 或输入文本写日志。新的 port 名与 ABI 由协调者固化。

RFB decoder 按 [不可变 RFC 6143](https://www.rfc-editor.org/rfc/rfc6143.txt) 实现，首版只协商支持的版本、32bpp true-colour pixel format 和 Raw encoding；CopyRect、DesktopSize 等能力必须实现完整校验才宣告支持。RFB 长度按 negotiated endian/colour shifts 解码，不把 little-endian XRGB 自动叫 RGBA。更新画布只允许有限矩形和确定基线，不支持的 server 消息立即拒绝。标准 keyboard/pointer 首先做闭环；CJK IME、text commit、multi-touch、剪贴板属于另外的能力验证，不得从“能发 keysym”推导百分之百文本兼容。

如果 RFB 不获正式采用，同一专用 port 可承载原创的完整像素包：guest 通过已公开的 compositor capture 接口取得 wl_shm/pixman 的真实输出，guest 内复制出字节后发送。**wl_shm 的 FD 不跨 VM**。先做 full-frame，后续才加入 damage；不能只复制现有 display-agent 的 OK 响应或调用未实现的抓帧 API。

对照路径是 **QEMU 原生 VNC + guest DRM compositor + virtio-gpu 2D + 显式 virtio input**；有现成 upstream scanout/input 链，可检验 guest 的 DRM/输入配置与 Android decoder。其 VNC socket应在 App 私有路径或鉴权代理后，不默认监听 LAN。**headless wayvnc 和 QEMU VNC 两者的 pixel source 不同**；Xvnc 保留为诊断原型，不替代正式 Wayland guest。

上屏 fallback：先用能返回真实成功/失败的既有 GLES CPU texture upload；native 不可用时只允许真正的 HardwareCanvas/Bitmap 或 ANativeWindow_lock 的 CPU 上屏。后者的 row stride 单位、format、Surface 生命周期仍需检查。停止帧源/断线时可展示最近真实帧并注明断线，不能继续生成帧序号制造 FPS。

运行验收必须看到 guest 内同一个应用的可变化内容，并用点击/按键改变内容后收到新像素；核对 guest source frame 与 host 解码 frame 的尺寸/序号/合成测试图案或 hash。host wallpaper、server placeholder、纯 guest capture helper 假数据和 renderer 调用计数不能替代此验收。

## 9. Capability probe 与协议/队列限额

### 9.1 分层 probe

| 探针 | 必须保留的结果 | 失败行为 |
| --- | --- | --- |
| AVF host | feature、manager 非 null、protected/non-protected capability、MANAGE、USE_CUSTOM、SystemApi 逐方法可访问性 | 具体 reason code；不因 SoC 白名单/年份宣告可用 |
| AVF VM | 正确创建配置、run、running/boot 状态、受限 vsock FD、实际 source 服务握手 | 权限/隐藏 API/配置/启动分别失败；no auto grant、no direct KVM |
| AVF GPU | 配置 API 存在、平台 paravirtualized feature、实际 guest virtio GPU/capset、host renderer initialized | 不支持的 GPU 配置不作为 optional skipped 后仍宣告硬件成功 |
| QEMU binary | 对锁定 hash 执行 -version、-device help、GPU property help、-display help/-vnc help；然后实际 RFB handshake 或专用 port | 本研究只完成静态 ELF；这些运行探针留给获设备交接的会话 |
| guest source | compositor 活着、headless output 可用、capture 和 virtual input global/版本、捕获到真实 frame | 只连到 Unix socket 不等于有可用 desktop；协议/来源不符关闭 |
| guest GPU | guest DRM 驱动、capset + renderer/vendor 字符串、实际 API render/readback、软件与硬件比较 | host GLES 上屏、GL 名字、checkbox、空 JNI 回调均不算成功 |
| host presenter | 实际 native loaded、Surface valid、format 转换正确、真实提交成功/错误/ContextLost | 不使用 mock-ready 和固定 1.5ms；释放旧 EGL/Surface 状态 |
| 端到端 | guest 变更 → source frame → decoded frame → physical Surface + reverse input | 在设备交接前全部保持未运行，能力矩阵不翻已验证 |

### 9.2 建议初始资源预算

以下是实施建议值，不是性能实测或当前 spec。协调者可收紧，但必须同时限制形状、乘积、字节数和所有 queue。

| 对象 | 首版建议上限与语义 |
| --- | --- |
| 画面 | 默认 1280×720、source max-fps 15；可协商至 1920×1080/30；每轴最多 2048、像素总数最多 2,073,600、全帧 payload 最多 8MiB |
| full-frame 数据 | 仅允许列明的 4-byte formats；使用 checked 64-bit width×4、stride×height；stride 至少 width×4 且不得超过按协议允许的 padding 上限；payload 必须精确覆盖全帧。空 payload 只能是已建立同 generation 基线后的显式 heartbeat/no-change |
| staging | 最多两份完整可消费帧（当前呈现 + latest pending），软件 staging/PBO 总预算单独记账，建议首版 64MiB；平台 Surface/BufferQueue 占用另测，不能伪装成包含在该预算 |
| RFB 更新 | 一个 negotiated framebuffer；一次最多 256 rectangles；校验 x/y/w/h 和 addition overflow；统一 decoded-byte budget；未知 encoding/超过预算拒绝，不无限 inflate |
| 控制与输入 | 握手/控制行不超过 2048B；最多 256 input events、64KiB 字节预算；motion/resize 可合并，key/button up/down 保序；拒绝超长文本及无限 pending |
| pending capture | 最多一个未完成 capture/request；不累计离屏 backlog；writer 有 deadline/backpressure，连接失败不持续采集、重打包或排队 |
| mode 与连接 | 每次 resize/重连递增 generation；旧帧、旧 input、旧 ACK 不得用于新尺寸；握手、首帧、I/O stall 分别设 deadline；认证/解析错误不自动重连，transient 重连次数必须有界 |
| FD/blob（将来） | 首版不接收 guest FD/blob；以后最多固定资源槽，按 descriptor type、fstat/size、offset、format/modifier、只读范围、fence/close 所有权逐项限制 |

1280×720×4×15 的满帧原始量为 55,296,000 B/s；1920×1080×4×60 为 497,664,000 B/s。这是字节公式，不是带宽/帧率实测；说明仅双缓冲无法消除 CPU copy 成本，不能许诺固定 60FPS。脏矩形、压缩和 PBO 优化必须有实际基准再启用。

### 9.3 必须由实施方提供的可执行负例

- 未握手、错实例 token、过期 session、非法版本、READY 前发 pixel、同 port 多 client：无 capture/socket 打开、无输入注入、无 queue；日志不回显 token。
- 极端/负 dimensions、越界 stride、width×4/stride×height overflow、short/oversized payload、未知 format、截断 header、EOF：在分配/读正文前拒绝；不得进入 renderer、不得继续拼 ASCII 无界字符串。
- RFB 超长 name/reason、未知 message/encoding、安全类型不符、越界/过多 rectangle、CopyRect 无基线、DesktopSize 超预算、compressed bomb：确定失败并关闭。
- 首帧 payload=0、frame 重放/倒序、resize 后旧 generation、restart 后旧 ACK：不伪造首帧，不误报 connected/desktop-ready。
- slow/stalled guest、partial write、writer 无限阻塞、队列满、capture 超时：内存/线程稳定，motion 可丢，按键释放保序；断线使 guest 清空 pressed 状态，不留下卡键。
- Surface destroy/recreate、native 未加载、EGL init/submit/context loss：停止提交、关闭/释放正确，返回真实失败；禁止 mock 时长进入产品统计。
- 输入 UTF-8/Base64 畸形、超长 commit、含引号/换行/命令替换的合成文本：不得 system shell 执行；文本内容只进入受限 guest input 端。
- 关闭功能或 source 不存在：没有帧泵、连接/重连、后台采集队列；恢复需明确状态转移。
- 新 socket 不能仅依赖“abstract socket 天然按 App UID 隔离”的假设。[unix(7)](https://man7.org/linux/man-pages/man7/unix.7.html) 说明 abstract socket 不受 pathname permission 控制；需要明确 Android SELinux/peer、随机实例地址和 per-VM token。跨 UID/伪造 peer 的负例应在后续获准环境执行。
- host 内容边界：合成 `.git/.env` sentinel、任意 socket/path 请求和来源越界不得进入图形 frame/传输；任何 capture/诊断不得扫描或发送宿主工作树。

这些负例本次只提出，**未运行**。协议 ABI 与状态契约由协调者统一实现，不能由各 worker 各写一版。

## 10. 对已有决策的影响检查

| ADR/决策 | 证据与影响 | 建议给协调者 |
| --- | --- | --- |
| ADR-0012 graphics phasing、ADR-0017 Wayland channel | 0017 的 QEMU“宿主与 guest 共享 Linux 内核”假设错误；原生 Wayland/FD 不等于像素流；直接 ashmem/memfd 跨 VM 方案未实现。当前模板源和缺失 exporter 不能支撑其验收叙述 | **触发 Architecture Change Policy 的新研究与 accepted 假设冲突**；修正 VM 边界，明确 CPU-copy 与将来 blob/cross-domain 路线。正式采用 RFB transport 仍须评估与“跳过 RFB 客户端”条文的关系，不能自行以换名规避 |
| ADR-0019 GPU | 当前独立 QEMU 2D、gl 参数被重写、compat renderer 未执行命令；没有真实 guest GPU 实测。gfxstream 官方 Apache-2.0 又纠正旧研究许可断言 | 将“已集成/普遍可用/固定性能/保证无系统 hang”等断言收回到待验证；若改变 in-process GPU 架构或重建 QEMU 切片，由协调者走门 |
| ADR-0010 security boundary | pVM vsock 官方通信反驳“阻断所有双向交换”；abstract socket 不等于专属 UID netns；新增 graphics 数据面/FD 映射是明确暴露面 | 修正安全解释并核对显示/输入白名单。不能将 GPU robustness 当硬件隔离证明 |
| ADR-0004 compat floor | 已要求 API/权限/custom VM 能力谱；本研究支持该原则，但 MANAGE-only 实现未覆盖所有门 | 可先修 probe/adapter，分级 UNKNOWN/UNAVAILABLE/VERIFIED；不需因本研究提高整个 App 的 minSdk |
| ADR-0008 networking | 专用 virtio-serial/vsock 图形通道不需要默认 TCP/LAN 暴露；RFB TCP 也不能借 NAT/hostfwd 默认授权 | 不改变已批准网络默认；如果增加公开监听，先确认新的显式产品决定 |

本 child 已将关键阻断告知主协调者。是否创建 ACR、如何修订 accepted 决策、何时恢复实施由协调者按 policy 裁定；本报告没有新建或修改 tracker/ADR。

## 11. 实际完成、未完成、验证与产出

**实际完成**：治理和工单读取；官方固定源码/API/service/许可分析；4 个 shallow sparse checkout；AOSP main/Android 16、native/SELinux 固定 HTTP 文件证据；crosvm 锁定 rutabaga crate checksum 核验；实际 App 入口与 guest source 追踪；包内 QEMU 的 ELF/hash/真实后端静态核对；CPU copy 首通路、probe、限额/负例与 ADR 影响建议。

**未完成**：AOSP Git clone；完整依赖/分发许可证闭包；所有设备、AVF 授权/启动、native/GPU 初始化、-device/-vnc runtime probe、guest package/desktop 启动、RFB handshake、真实 frame/input/IME、吞吐/延迟/功耗/内存及负例测试。没有把 fixture、静态证据或公式当成真机结果。AOSP/GPU ROM 差异和各发行版 headless/seat/capture 包组合仍为 UNCERTAIN，须后续实际观察。

**修改文件**：唯一正式文件 `W/docs/research/2026-10-13-phase5-graphics-avf.md`；新增公开下载仅在本代理 R 下四个目录及 E 下所属证据。生产、共享协议、tracker、ADR、活动主仓和设备均未修改。

**验证**：readelf 的 ELF header/dynamic/symbol 读取返回 0；QEMU size/hash 与锁一致；4 个 clone 成功且记录 URL/rev/license；rutabaga 发布 checksum 匹配；未运行 build/JVM/integration/设备测试。报告与输出范围检查记录为 `E/research-output-check.json`，不以此代表产品验收。

**原始证据入口**：`E/reference-provenance.json`、`E/local-source-evidence.json`、`E/packaged-qemu-static-capabilities.json`、`E/packaged-qemu-registration-symbols.json`、`E/aosp-pinned-api-downloads.json`、`E/aosp-android16-downloads.json`、`E/wayvnc-source-downloads.json`。许可副本在 `E/license-evidence`，即时保全副本在 `E/preserved`；不创建 `.log` 扩展名证据。

**风险与后续动作**：优先补 guest 真源及实际输入/显示状态，随后才能做性能优化。协调者应统一传输 ABI、审核 ADR 冲突、修正 AVF adapter 与能力误标，再实现首通路并在设备交接后验证。公开 main snapshot 和本地采样可能随时间漂移，后续构建必须复用固定输入并复核 hash。公开参考只作为只读研究，未复制参考代码到生产。

**保留与清理**：本代理是 E/所属 R 的创建者；归类为本批公开参考/研究证据，保留至协调者收口和长期归档判断，不自动删除；维护者/协调者负责之后按 ASSET policy 分类。未移动、重命名或清理任何 Class A 资产。所有主动文件写入指定 D 盘批准路径，没有系统、注册表、PATH、驱动、防火墙、SDK 或 runtime 配置改变。工具内部缓存由 Host 管理，不作为本报告原始证据的唯一副本。

**越界检查**：无进一步代理、无 host 内容上传、无远端写入、无代码/决策状态修改、无设备操作；Architecture Change Policy 的影响已识别并升级给协调者，未自行越过架构门。commit：无。

报告人：graphics-research supporting child / Codex ｜ 日期：2026-10-13 ｜ 耗时：未统计
