# Orchestella 下一阶段优化方案（2026-10-18）

> **实施状态（2026-10-18 维护）**：P0-3 跨 Controller 单实例竞态已在 `runtime/instance` 中修复，并新增 4 项并发契约测试；`:runtime:instance:check` 与 `:app:testDebugUnitTest`（747 tests / 98 suites）通过。P0-1 生产签名、P0-2 QEMU/gvproxy 收敛、P0-4 输入、P0-5 CI 门禁与 P0-6 GPL/NOTICE 仍未完成。详见[维护与优化 Prompt](optimization-prompt-20261018.zh-CN.md)。

- 基线：`Orchestella_dev` main `b0a167c`（2026-10-18），源码 0.5.3 / code503，工作树干净；公开安装包仍为 v0.4.5。
- 方法：只读代码审计、构建产物核验、现有 tracker/研究对照、联网核对 Android 17、16 KB、AVF、Shizuku、QEMU 与竞品。
- 性质：研发优先级与执行方案，不是实现报告。本回合未修改产品代码、未构建、未操作设备。

## 0. 结论摘要

1. **下一阶段的主线应是“QEMU 全设备可靠可用”，不是继续扩大 AVF 承诺。** 当前 QEMU 路径已有 7 发行版、独立内核、gvproxy 网络、终端与 SSH 的真实设备证据；AVF 真实 Guest 成功样本仍为 0，当前 ROM 只声明 protected，且 protected 自定义 Linux 还面临 App 私有根盘来源门。AVF 应作为时间盒内的研究/加速线，不能成为发布阻塞。
2. **发布信任是当前第一风险。** 公开 v0.4.5 与 0.5.3 验证包都用 Android Debug 证书签名（SHA-256 `3a1ea26b...`，已用 apksigner 核验）。这既是安全问题，也让“正式发布”失去意义；用户基数还很小（GitHub release 资产下载量 0–10 次），现在是切换生产签名、做数据迁移成本最低的窗口。
3. **运行时可靠性比新增功能更紧急。** QEMU 进程没有 `destroyForcibly`/超时，`terminate()` 只 destroy，`close()` 不停止 gvproxy；网关只在 QEMU watcher 或启动失败时停止；`FileStore` 的锁是实例级而非进程级，两个 Activity 可同时通过“单运行实例”检查。这些会造成停止不收敛、端口占用、下一次启动失败、孤儿进程和元数据竞态。
4. **Android 17 的兼容窗口已经打开。** API 37 于 2026-06 稳定；targetSdk 37 会强制本地网络权限、取消大屏方向/尺寸退出、改变 IME 可见性恢复，并禁止反射修改 static final。项目当前 targetSdk 36，暂时安全，但应在 2026Q4–2027Q1 完成适配预研。
5. **竞品已经验证了需求，也压缩了差异化空间。** Google Linux Terminal 仍以 Pixel 为主、Debian-only、GPU 仍在 Lavapipe/Gfxstream 早期；Podroid 已有约 3.5k stars，提供 Alpine + QEMU/AVF + 容器 + X11。Orchestella 的可防守位置是“任何 ARM64 Android 上的多发行版完整 Linux + 移动工作站体验”，而不是“又一个 Pixel AVF 终端”。

## 1. 已核实现状

### 1.1 版本与发布

- 源码 0.5.3 / code503；公开 release v0.4.5（prerelease）；v0.5.0-alpha 有 tag，0.5.1–0.5.3 没有 tag。
- 公开 v0.4.5 APK：`apksigner` 显示 `CN=Android Debug`，证书 SHA-256 `3a1ea26b3947e1ccc3213ecdcf7241c362aeb77331526a53729158d16bb29b06`。
- 0.5.3 本地验证包同样是 Debug 证书；隔离 `.phase5` 包使用另一张本地 QA 证书。
- `app/build.gradle.kts:86,90-92` 内置 release keystore 默认路径与默认口令 `orchestella2026`；`phase5Debug` 固定 `android`/`androiddebugkey`（`:72-80`）。`.toolchains/`、`local.properties`、`*.apk` 已在 `.gitignore` 中，未发现 keystore 被跟踪，但默认口令本身仍是风险。
- 公开仓创建于 2026-09-05，当前 2 stars、0 forks、0 open issues；Podroid 创建于 2026-03-16，约 3547 stars、227 forks。差距主要不在代码量，而在发布可信度、可用性和社区运营。

### 1.2 测试与 CI

- 项目报告：747 项 App 测试 / 98 suites，lint 0 Error / 748 Warning；另有 65 项 Guest 检查和 8 项 Android 框架场景。
- `.github/workflows/ci.yml:42` 实际只运行 `:runtime:contract:check :app:testDebugUnitTest`。`runtime:instance`、`engine-qemu`、`engine-avf`、`network`、`capability`、`distro` 的测试不进入 CI；lint 和 `assembleDebug` 也不在 PR 门禁。
- 无 `androidTest`、无 Robolectric/ActivityScenario；AVF 测试跑在自写的 `android.system.virtualmachine` 假 SDK 上，不能替代真机。
- Guest assets CI 在 main 上持续失败（2026-10-17 run 仍为 failure）；`guest-assets.yml` 只在 `guest-assets/**` push 或手动触发，PR 不跑。构建链条依赖 Docker/Podman，当前门禁实际是红的。
- `app/src/main/jniLibs` 仅跟踪 `libdroidlayer_qemu.so`（QEMU 11.1.1，132.9 MB）与 `libgvproxy.so`（12.7 MB），APK 还包含 CMake 构建的 `liborchestella_gles.so` 与 `libvirglrenderer.so`。

### 1.3 16 KB 与 Android 16/17

- 已核验：`libdroidlayer_qemu.so` 的 LOAD 段对齐为 `0x4000`，`libgvproxy.so` 为 `0x10000`；v0.4.5 与 0.5.3 APK 的 `zipalign -c -P 16 4` 均通过。CMake 自编库也带 `-Wl,-z,max-page-size=16384`。**当前 16 KB 兼容不是阻塞项**，但需要 CI 回归，防止未来换依赖/打包方式时退化。
- Android 官方页面：16 KB 兼容的应用更新自 **2027-02-01** 起才被 Play 强制；项目不在 Play，但设备兼容仍应保留检查。
- Android 17（API 37）已于 2026-06 稳定；QPR1（API 37.1）2026-09。targetSdk 37 的关键变更：
  - `ACCESS_LOCAL_NETWORK` 运行时权限，归入 `NEARBY_DEVICES`；LAN 暴露必须显式申请。
  - 大屏（sw ≥ 600dp）忽略 `screenOrientation`/`resizeableActivity`/宽高比限制；targetSdk 36 的退出选项在 37 不再提供。
  - 实体键盘密码可见性、配置变更后 IME 可见性恢复行为变化；需要在终端页做显式 `stateAlwaysVisible`/`showSoftInput` 兜底。
  - 反射不能修改 `static final`；`System.load()` 加载的文件必须只读；MessageQueue 改为无锁实现。项目应确保 QEMU/gvproxy 从只读 `nativeLibraryDir` 执行，AVF 反射适配器不做字段写入。
- 项目当前 targetSdk 36，短期不触发上述强制行为；这是“预研 + 2027H1 适配”的窗口，不是立即阻塞。

### 1.4 QEMU / gvproxy 运行时

- `runtime/engine-qemu/.../QemuProcessLauncher.java:15-19,51-58`：`ProcessHandle` 只有 `waitFor()` 与 `destroy()`，没有超时、没有 `destroyForcibly()`；真实实现直接包装 `Process`。
- `app/.../QemuInstanceLauncher.java:203-210`：`terminate()` 只调用 `handle.destroy()`；`:237-266` 的 `close()` 关闭 socket/通道但不停止 QEMU 进程，也不停止 gvproxy。
- gvproxy 在 QEMU 启动前 start（`:448-450`）；只在 QEMU watcher（`:519-530`）或启动失败（`:537-549`）时 stop。若 QEMU 忽略 SIGTERM、或 `waitFor()` 永久阻塞，网关与端口会留在原地。`NetworkGatewayDaemon.stop` 也只 `destroy()` + 删 socket，不等待退出（`NetworkGatewayDaemon.java:222-227`）。
- 网关监听端口按实例 hash 固定（`NetworkGatewayDaemon.java:508-510`），就绪只等约 8 秒（`:58,174-211`）。旧进程稍慢退出就会让下一次启动失败。
- `InstanceLaunchSequence.java:55` 的 `session.waitFor()` 没有 deadline；详情页 60 秒后放弃（`InstanceDetailActivity.kt:68`），但会话/网关仍可能存活。
- `InstanceLifecycle.java` 的 `FileStore.save/load/list` 使用实例内 `synchronized`（`:544-573`）；每个 Activity 各建一个 FileStore，`Controller.start` 的单运行检查（`:863-878`）是“list 后 transition”，跨 Controller 没有共同锁，两个实例可同时通过检查。
- `ExecutionSessionRegistry` 只保证同进程单 reader/token；它不能替代跨进程/跨 Activity 的元数据互斥。

### 1.5 终端输入

- `ModernTerminalView.java:96-105,133-136,150-157`：`setSessionScreen` 与 `onCreateInputConnection` 都会 `resetInputConnection()`；同一 screen 重绑也会关闭旧连接，未提交 composition 被丢弃。
- `TerminalInputConnection.java:95,201,219,409-413`：连接关闭后 `commitText`/`sendKeyEvent` 等直接返回，不报告、不缓冲。
- `TerminalActivity.java:912-916`：Activity detach 期间 `sendRaw` 静默丢弃。
- 已有 JVM 测试覆盖 composition、Unicode、粘贴、close 丢弃；缺的是 Android 框架/真机时序：OEM IME 在前后台、锁屏、主题切换、配置变更时是否先 finish、是否保存候选、是否双发。
- 项目已有研究确认右 Alt/AltGr 与死键存在静态缺口；这与“adb 合成输入丢字”是不同问题，不能用一个根因概括。

### 1.6 AVF / Shizuku

- 当前测试机 HONOR MEY-AN00 / SM8735 / API36：平台 `getCapabilities()` 只声明 protected，non-protected=false；无 `/dev/kvm`，有 Gunyah 节点。
- 生产 AVF 适配器在真机 `VirtualMachineCustomImageConfig$Builder.<init>` 处被 hidden API 拦截并回退 QEMU（项目报告）；真实 AVF Guest 成功样本仍为 0。
- `VmHardwareConfig.DEFAULT_CPU_CORES = 2`，而 `ReflectiveAvfVirtualMachineApi.java:87-90` 只接受 1 个 vCPU；`AvfInstanceLauncher` 若拿到默认硬件配置会直接失败。AVF 默认 spec 本身是 1 vCPU，但 UI 默认配置是 2，这是一处语义不一致。
- `ReflectiveAvfVirtualMachineApi.java:555-559` 把 `STATUS_DELETED=2` 当正常退出；AVF 异常终止可能被记录成正常关机。
- Shizuku 客户端为 `13.1.5`（`app/build.gradle.kts:166-167`）。官方最新 release 为 13.6.0（2025-05），但 Android 17 的 hidden API 签名变化导致官方 Shizuku 在新授权/枚举场景失败（社区 issue #2180；Shevery/Nightzuku/Shizuku+ 等 fork 用 `deviceId` 兼容层修复）。项目只把 Shizuku 用于授权引导，且已有 ADB 兜底，因此这是“Android 17 兼容风险”，不是当前 P0 崩溃。

### 1.7 AVF protected 的硬门

项目研究已经给出关键事实：AOSP protected 分支会检查 kernel/initrd/整盘分区的实际 FD 标签，允许 `shell_data_file`/`apk_data_file`/`staging_data_file`/`system_file` 等，明确排除 `app_data_file`/`privapp_data_file`；App 预先打开私有 raw 盘再把 FD 交给其他进程，**不会改变原文件 label**。这意味着：

- 当前“App 私有 raw 根盘 + protected custom VM”的组合，很可能在目标 ROM 的文件来源门就被拒绝。
- 这不是“再写一个 JNI/代理”能解决的问题；如果只把盘放到 `/data/local/tmp` 或公共目录，就违反 ADR-0007 的私有存储与 Export 边界。
- 因此，在投入 25/26 的真实 boot/私有盘实验前，必须先做**纸面否决门**：用 AOSP 固定源码与目标 ROM 的规则逐条比对；若 `app_data_file` 确实被拒，立即把该 ROM 的 protected 产品化标记为 `resolved-negative`，保留研究结果，继续 QEMU/non-protected 兼容线。

## 2. 关键判断

### 2.1 为什么先修 QEMU，而不是继续加 AVF

- QEMU 是唯一有真实用户路径、真实发行版、真实网络和真实停止证据的执行策略；它覆盖所有 ARM64 Android 8+ 设备。
- AVF 在当前 ROM 上既没有 non-protected，也没有通过 hidden API；protected 又被私有盘来源门挡住。继续投入的期望值低，且不能解决用户今天遇到的问题。
- 这不妨碍保留 protected 优先的研发方向；但它必须是**时间盒、可失败、与发布解耦**的研究线，而不是主线。

### 2.2 为什么签名是 P0

- Debug 证书可以被任何人重建/替换，用户无法验证安装包来源；对“运行完整 Linux、持有用户数据”的应用尤其不可接受。
- 公开包下载量很小，现在切换签名的迁移成本最低；越晚切换，需要迁移的用户越多。
- 如果继续用 debug 证书发“正式版”，后续切换必然要求卸载或换包，用户数据（App 私有目录中的实例）会丢失。项目自己的 README 明确要求“不要为升级卸载旧应用”，因此必须现在就设计数据保留迁移。

### 2.3 竞品与定位

- **Google Linux Terminal**：Pixel 7+、开发者选项、Debian-only；Android 16 QPR2 才补图形桌面，GPU 在多数设备仍是 Lavapipe 软件渲染，Gfxstream 目前主要限 Pixel 10。它验证了“Android 上的完整 Linux VM”需求，但覆盖面窄。
- **Podroid**：Alpine-only，但已有 QEMU + AVF、Podman/Docker/LXC、X11 桌面、USB、SSH、中文界面，约 3.5k stars。它证明“容器 + 桌面”是强需求，也让“只有 AVF”不再差异化。
- **Orchestella**：7 发行版、任何 ARM64、移动工作站 UI、主题/多会话/SSH。可防守的差异化是**发行版广度 + 设备广度 + 体验**；AVF 是加分项，不是身份。

## 3. 优化方案

### P0：下一公开版本之前（建议 1–2 周，1 名熟悉代码库的工程师）

| 工作包 | 交付 | 验收 | 依赖/停止条件 |
| --- | --- | --- | --- |
| P0-1 生产签名与数据迁移 | 生成独立 release keystore（离线保存 + CI secret）；移除默认口令与 `.toolchains` 回落；`apksigner` 校验证书非 Debug；新包（建议 `io.github.nyzeep.orchestella.studio`，v0.6.0）或同包迁移方案；复用现有 SAF 导出格式，新增导入/实例重建 | 发布包证书不是 `CN=Android Debug`；从 v0.4.5 导出 → 新包导入 → 实例可启动、凭据/根盘/hostname 保留；升级说明清楚 | 若导入不能按期完成，先发“新包 + 手动导出说明”，绝不继续发 Debug 签名包 |
| P0-2 QEMU/gvproxy 生命周期收敛 | 引入单一 `RuntimeProcessSupervisor`：拥有 QEMU 进程、gvproxy、control/terminal/display/proxy socket；`stop(graceMs)` 依次 `destroy` → 等待 → `destroyForcibly` → 关闭通道 → 停网关；`Session.close()`/`terminate()` 都走它；网关 stop 幂等并等待退出 | 假进程忽略 `destroy()` 时能强制收敛；terminate/close/启动失败都不留网关；强制停止后可立即用同一端口重启；真机连续启停 20 次无孤儿 `qemu`/`gvproxy` | 若 QEMU 进程无法在超时内收敛，保留 `STOP_UNCONFIRMED` 与盘 lease，不得假装已停止 |
| P0-3 跨 Activity 单实例竞态 | `FileStore` 改为按 canonical 目录的进程级静态锁；`Controller.start` 用全局锁包住 list + transition；`InstanceLaunchGuard` 与 Registry 的语义统一 | 两个 Controller/两个 FileStore 并发启动只允许一个 RUNNING；转屏/重入/旧回调不覆盖新状态；新增并发回归测试 | 不引入新的常驻进程/VM owner；只修互斥，不改产品契约 |
| P0-4 终端输入与 IME 可靠性 | 同一 screen 重绑不 reset connection；`onCreateInputConnection` 仅在 sink/screen 变化时重建；保留未提交 composition 到明确 finish；Android 17 下加 `stateAlwaysVisible`/显式 `showSoftInput`；右 Alt/AltGr 与死键按 `KeyCharacterMap` 处理 | JVM/框架测试覆盖 `setComposingText → commit → finish` 只发一份、重绑不双发、close 不发送未提交候选；真机中文 IME + 实体键盘 + `adb shell input text` 不丢字；旋转/锁屏/前后台无重复 | 若 OEM IME 行为不可控，先做“连接生命周期 + 显式显示”修复，不引入全局 inputType 变更 |
| P0-5 CI 与发布门 | CI 跑全部 `:runtime:*:check` + `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug`；加 16 KB ELF/APK 检查；修 guest-assets CI 的 shellcheck 失败；PR 触发 guest 相关检查；release workflow 校验版本/证书/SHA256/对应源码/NOTICE | 故意失败的 runtime 测试、lint、16 KB 检查能阻塞合并；tag 构建产出非 Debug 签名 APK + GPL 对应源码 + NOTICE + SBOM；main 与 guest CI 为绿 | GitHub 分支保护是仓库设置，需要人工开启；没有绿门之前不要合并大改 |
| P0-6 GPL/NOTICE 修正 | 修正 `guest-assets/GPL-SOURCES.md:72` 的 Apache-2.0 表述；`NOTICE.GUEST-RUNTIME:3,17` 的 musl 1.2.6 → 实际 1.2.5；补齐 `NOTICE.QEMU:45` 引用的政策文件；APK/Release 附带完整对应源码与 SOURCE-OFFER | 版本字符串与 `inputs.lock` 一致；发布包内 NOTICE 可查；对应源码与实际二进制版本一一对应 | 公开分发前必须完成；不发布含混许可证声明的新二进制 |

**P0 出口**：一个非 Debug 签名、可覆盖/可迁移、连续启停稳定、输入可用的 0.5.x/0.6.x 公开预览包；CI 绿；16 KB 检查通过。

### P1：Android 17 与产品可靠性（建议 4–6 周）

| 工作包 | 交付 | 验收 |
| --- | --- | --- |
| P1-1 Android 17 适配预研 | 在 `android-17-readiness` 分支把 targetSdk 升到 37；处理 `ACCESS_LOCAL_NETWORK`（只在实现 LAN 转发时声明/请求，loopback 保持默认）；大屏方向/尺寸；IME 可见性；Shizuku 13.6/13.7 兼容与 ADB 兜底；`System.load` 只读路径审计 | API 36 与 37 都能构建/测试；LAN 转发有权限提示且默认关闭；终端旋转/锁屏后键盘与输入正常；Shizuku 在 Android 17 上“可用/明确不可用”有结论，不静默失败 |
| P1-2 可观测性与冷恢复 | 落地 tracker 27 的生产阶段记录：启动/停止阶段、退出码分类、`ApplicationExitInfo`（API 30+）、崩溃原因、可导出且脱敏的日志；与 `LogViewerActivity` 对接 | App 进程被杀后重启，实例状态为 INTERRUPTED 而非 STOPPED，根盘/身份保留，可一键冷恢复；日志不含 token/口令/按键正文 |
| P1-3 真机设备矩阵 | 至少覆盖：当前 HONOR、Pixel（AVF 可用/不可用两种）、Samsung/Xiaomi（后台限制）、一台 16 KB 设备或模拟器、Android 8/10/14/16/17 各一档；每档跑 Debian/Ubuntu/Fedora 启动、apt update、SSH、端口转发、停止/重启、后台 10 分钟 | 矩阵写入 `docs/`，未测项明确标 not-tested；QEMU 路径不依赖 AVF；停止成功率和崩溃率有数字 |
| P1-4 更新通道与版本 | 修复 prerelease 仓库导致 `/latest` 404 的问题；预览/稳定通道语义；版本号与 tag/commit 绑定；发布说明模板 | 旧版本不会被误判为升级；每个公开包都能追溯到 commit 与对应源码 |

### P2：并行研究线（时间盒 3–4 周，不阻塞发布）

| 工作包 | 交付 | 验收/停止条件 |
| --- | --- | --- |
| P2-1 AVF protected 可行性 | 23 模式事实与诚实入口；24 有界 protected 平台 probe（deadline、单 flight、真实 stop、FD 收尾）；27 生产 recorder 可并行；26 先做纸面标签门 | 24 成功/准确拒绝/外部阻塞三者之一；若 24 无法真实 boot 或 stop 不收敛，冻结 25/26；若 26 纸面门确认 `app_data_file` 被拒，标记该 ROM protected 产品化 `resolved-negative`，不进入 G2/G3 |
| P2-2 非 protected 兼容 | 保留现有 adapter/测试；有兼容设备时再跑真实 Guest；无设备时标 not-tested | 不用循环 grant/新 owner 伪造通过；不因 protected 优先级删除旧路径 |
| P2-3 性能基线 | 在 QEMU 真实链路上采同设备/同镜像/同资源的冷启动、apt、编译、I/O、网络样本；单变量测试 `-accel tcg,tb-size`、`-cpu max` vs `cortex-a57`、`-smp`、`cache`/`aio`/`discard`/`detect-zeroes`；不先把 `cache=unsafe` 带进产品 | 至少一个 workload 有统计显著的改善且无 >5% 回归才改默认；否则记录负结果，保留现状；不报 AVF 性能收益 |

### P3：有数据后再做的产品差异化

- 一键 Docker/Podman（QEMU 完整内核已具备条件）、共享目录、桌面/Wayland、USB、模板化工作负载。优先级由 issue/discussion 的真实需求决定，不先于 P0/P1。
- APK 体积：当前约 106 MB；先不做大规模拆分，等有分发/安装失败数据再评估 dynamic feature/asset delivery。
- 社区：发布演示、安装说明、issue 模板、讨论区。2 stars 首先是分发与信任问题，不是功能问题。

## 4. 30/60/90 天路线

| 时间 | 目标 | 关键结果 |
| --- | --- | --- |
| 第 0–2 周 | 发布信任与运行时收敛 | 生产 keystore 决策落地；Supervisor/单实例锁/输入修复合并；CI 全绿；16 KB 检查进 CI |
| 第 3–4 周 | 首个可信预览包 | 非 Debug 签名 APK；从 v0.4.5 迁移演练通过；连续启停 20 次无孤儿；GPL/NOTICE 修正 |
| 第 5–8 周 | Android 17 与可观测性 | targetSdk 37 预研分支；本地网络/IME/大屏验证；退出分类与冷恢复；设备矩阵第一版 |
| 第 9–12 周 | AVF 判定与性能基线 | 24 真实结果（pass/rejected/blocked）；26 纸面门结论；QEMU 性能样本与单变量结论；决定是否继续 protected 产品化 |

## 5. 验收指标

- **发布**：证书非 Debug；版本/tag/commit/对应源码一致；APK 通过 `zipalign -P 16`；SHA256SUMS 可校验。
- **运行时**：真机连续启停 20 次，QEMU/gvproxy 孤儿进程 0；强制停止后同端口可重启；停止未确认时盘 lease 保留且 UI 明示。
- **数据**：从 v0.4.5 导出并导入新包，实例 ID/根盘引用/hostname/凭据保持；App 进程死亡后状态为 INTERRUPTED，可冷恢复。
- **输入**：中文 IME、实体键盘 AltGr/死键、`adb shell input text` 三路各 1000 字符混合文本，guest 侧逐字节一致；旋转/锁屏/前后台无重复。
- **工程**：`runtime:*:check` + app tests + lint + assemble 全绿；guest assets CI 绿；16 KB 检查绿。
- **AVF**：只接受 `pass_platform_protected_payload` / `rejected_at_stage` / `blocked_external` / `stop_unconfirmed` 四类结论；不以“测试数量”或“声明位”代替真实 Guest。

## 6. 不做清单

1. 不再发布 Debug 证书签名的“正式/预览”安装包。
2. 不在 24/26 的真实门通过前，把 protected 写成默认能力或发布阻塞项。
3. 不在 P0 完成前新增发行版、替换 kernel/QEMU、迁移 qcow2、重做 GPU/桌面。
4. 不把 `vm run-microdroid` 无期限运行后杀 host adb 当作“VM 已停止”。
5. 不把单元测试/假 SDK/合成 FD 通过当作真机 Guest 或性能通过。
6. 不为“已建立工单”本身增加投入；当前 28 票对 2 万行代码的协作开销已经偏高，建议只保留 P0/P1/P2 三条活跃线，其余归档到 tracker 索引。

## 7. 需要用户裁定的事项

1. **签名迁移路径**：推荐新 applicationId + 导入向导；备选是继续 Debug 签名（不推荐）或同包切换并接受卸载/数据丢失（不推荐）。这是产品/发布决策，不是纯技术选择。
2. **AVF protected 的投入上限**：推荐“3–4 周时间盒 + 26 纸面否决门”，到期无论结果都回到 QEMU 主线；若要继续，需要明确新的设备/兼容平台或用户数据边界。
3. **产品定位**：推荐“任何 ARM64 Android 的多发行版完整 Linux 工作站”，而不是“Pixel AVF 终端”；这决定后续是否投入容器/桌面/USB 等差异化。

## 8. 主要风险

- **签名迁移**会打断现有安装路径；必须提供导出/导入与清晰说明，且要接受“旧包不再更新”的事实。
- **AVF protected** 可能在文件 label 门直接失败；越早做纸面门，越少浪费。
- **Android 17** 的 hidden API/本地网络/IME 变化可能在 2027 年集中爆发；现在做预研，不要把 targetSdk 37 留到最后。
- **QEMU 性能**不保证有大幅提升；没有测量就不改默认，避免“优化”变成回归。
- **竞品**（Google Terminal、Podroid、Termux:X11）在桌面/容器/AVF 上推进很快；Orchestella 的窗口是“多发行版 + 设备广度 + 体验”，不是单点性能。

## 9. 来源

### 项目内

- `README.md`、`CONTEXT.md`、`AGENTS.md`
- `docs/reports/orchestration/protected-avf-near-term-plan-20261017.zh-CN.md`
- `docs/reports/orchestration/avf-shizuku-device-20261016.zh-CN.md`
- `docs/research/2026-10-17-avf-custom-linux-route-study.md`
- `docs/research/2026-10-15-terminal-runtime-next-phase.md`
- `.scratch/orchestella-avf-shizuku/map.md`、issues 23–26、ACR-01/02
- `tools/qemu-android/qemu-jni.lock`、`guest-assets/inputs/inputs.lock`
- `.github/workflows/ci.yml`、`guest-assets.yml`、`qemu-android.yml`

### 外部

- Android 17 行为变更（官方）：https://developer.android.google.cn/about/versions/17/behavior-changes-17?hl=zh-cn
- 16 KB 页面大小（官方）：https://developer.android.google.cn/guide/practices/page-sizes?hl=zh-cn
- AOSP AVF：https://source.android.com/docs/core/virtualization
- QEMU 发布目录：https://download.qemu.org/
- Shizuku releases：https://github.com/RikkaApps/Shizuku/releases
- Shizuku Android 17 issue #2180：https://github.com/RikkaApps/Shizuku/issues/2180
- Podroid：https://github.com/ExTV/Podroid
- Orchestella 公开仓：https://github.com/Nyzeep/Orchestella-Studio

---

报告人：ZCode / deepseek-flash ｜ 日期：2026-10-18 ｜ 本回合未运行 Gradle/设备/构建，未修改产品代码。
