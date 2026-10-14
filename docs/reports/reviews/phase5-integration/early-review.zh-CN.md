# Phase 5 integration 早期独立只读审查

- 日期：2026-10-13；角色：desktop-research supporting child 转独立 early reviewer / Codex；不派生。
- 对象：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev`，下文 `WORK`。
- HEAD：`8d0e8c75d769ecc645ea102b90a304c8657344a1`；dispatch 分支 `phase5/graphics-productivity-storage`。审查未提交工作树，未改变分支、commit/push/PR/发布。
- 基线：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/coordinator/dependency-baseline` 的逐文件 captured-before；来源清单 `coordinator/evidence/production-dependency-baseline.json`。不存在 captured-before 的文件对照上述 HEAD；新文件明确标新。
- 规格：`WORK/.scratch/orchestella-phase5/spec.md`、`issues/08-integration-and-device-gates.md`，显示边界另参照 `issues/05-authenticated-real-display.md`。
- 范围：guest-agent FEATURES1/helper/鉴权/DISPLAY、BridgeClient feature/唯一 reader、FrameData、QEMU display 接入、Surface/native presenter、GuestFeaturesActivity。只读必要调用点 DesktopActivity/QemuWaylandChannel/WaylandSurfaceView/JNI/CMake。未审查尚未合流的 graphics/profile/service worker 新模块，未重审参考仓库。
- 治理：沿用本会话已读 active MAIN AGENTS 与全部防御/Host/Artifact/Architecture/Asset/Reporting policy。`code-review` Skill 工具同名歧义失败，已读取项目 `.agents/skills/code-review/SKILL.md`；用户明确要求只读且不派生，因此本人执行 Standards/Spec 两轴，没有伪报 parallel sub-agent。

**早期结论：记录 6 项真实 P1/P2 finding；前三项已向协调者即时发送，随后看见源码修复；剩余 3 项 P2 呈现/恢复问题在本次截止源码仍存在。** 结论为 `request-changes` 的早期反馈，不是最终 approval，不代表 guest/Android/GPU 已运行或全绿。代码正在迭代，以下 hash/行号限定于记录快照，后续最终复评另派。

## Standards 轴

规则来源是项目防御/治理 policy、ADR0011 冷恢复语义与质量规格的共享 control 纪律；隔离根未发现 `CODING_STANDARDS.md` 或 `CONTRIBUTING.md`，不臆造风格标准。没有为命名、switch、文件长度等启发式 code smell 提出 P1/P2。

- **R2 是共享生命周期正确性问题**：Activity 销毁不能中断 session 的唯一 control transport；其原路径和已见修复见下。
- **R4 是能力/验证真实性问题**：native 无成功信号却累计“已呈现”指标，与 REPORTING_STANDARD 禁止虚报和本期诚实能力要求不符。不能把 Java JNI 返回、有限耗时、host EGL 初始化当真实成功。
- 本审查未发现新增宿主源码外发、凭据读取或 arbitrary host-file bridge 的具体证据；这是限定范围静态观察，不是完整安全审计通过。原 guest IPC/其他后台服务的历史面不因本报告获得批准。

本轴记录 2 项跨轴问题，R2 已见源码修复、R4 待修；没有自我 approval。

## Spec 轴：具体 findings 与处理状态

### R1 [P1] vsock control worker 继承 SIGCHLD=SIG_IGN，所有 feature helper 被误判失败

- 归属：新增 helper 与既有 vsock server 的交互回归。
- 初次证据：`guest-assets/agent/guest-agent.c` 的 `serve_vsock_mode` 设置 `signal(SIGCHLD, SIG_IGN)`，已认证 control worker 再 fork helper；`run_feature_helper` 通过 `waitpid(child,...,WNOHANG)` 和退出码判断结果。
- 触发：AVF/vsock serving 下，helper 正常输出 JSON 并退出。Linux 显式 SIGCHLD=SIG_IGN 会自动回收子进程，worker `waitpid` 得到 ECHILD，valid=0，返回 `features-unavailable`；DISPLAY LAUNCH/MODE 同用该 helper，亦受影响。
- 规格：spec 第 42、47 行要求真实跨策略显示和 feature 受理；issue08 第14行要求真实命令执行。
- 已见修复：当前 `guest-agent.c:782–798` 在 control 子进程进入前 `signal(SIGCHLD, SIG_DFL)`（第786行）。原 listeners 仍由父 server 回收，helper 可由 worker 正常 wait。
- 状态：**SOURCE FIX OBSERVED / PROCESS REGRESSION NOT RUN**。最终必须运行 POSIX test-loopback/vsock 流程，分别验证 helper 成功、非0、超时与后续 PING；Windows 宏路径不能证明该 POSIX 行为。

### R2 [P1] GuestFeaturesActivity.shutdownNow 中断共享 BridgeClient，返回终端时控制流永久失败

- 归属：新增页面生命周期回归；同时属于 Standards 轴。
- 初次证据：`GuestFeaturesActivity.kt` 原 `onDestroy` 调 `worker.shutdownNow()`；`BridgeClient.exchange:166–170` 遇 InterruptedException 调 `failTransport`，关闭同 session 的 inbound/outbound 并 ready=false。
- 触发：后台 STATUS/INSTALL 受理等请求正等待 helper 响应时，用户返回终端、旋转或销毁页面。shutdownNow 中断 executor 线程，导致 resize/power/clipboard 等共享 control 一并死亡，单 reader/isBridgeServing 不会自动重建。
- 规格：spec 第19、21、47行要求可选功能失败保留终端/同实例访问与 control；issue08 第14行保持 PING/唯一 reader。
- 已见修复：当前 `GuestFeaturesActivity.kt:121` 改为 `worker.shutdown()`；UI callback 检查 isDestroyed，避免销毁引发主动中断。没有由 reviewer 修改代码。
- 状态：**SOURCE FIX OBSERVED / LIFECYCLE REGRESSION NOT RUN**。需延迟 feature 响应中销毁页面后 PING/resize 仍可用的回归；确认之后的其他页面同样不 interrupt session request。

### R3 [P1] 显示接入只有定义，没有生产 connectDisplay 调用

- 归属：当前 integration 缺失，不是 graphics worker codec 的缺陷。
- 初次证据：生产 `connectDisplay(` 搜索只找到 ExecutionSession 默认与 QemuInstanceLauncher override；原 `DesktopActivity:215–231` 仅 desktopLaunch/displayMode。QemuWaylandChannel stream 直到 connect 才创建，bind/registerFrameListener 不会启动 stream。
- 触发：即使 guest compositor/profile 成功，显示 token/RFB 握手从未开始；host 无帧、键鼠不发送。
- 规格：spec 第13、20、41–43行，issue08 第14行与 issue05 第9行。
- 已见修复：当前 `DesktopActivity:225–248` 检查 LAUNCH 受理、120秒有界 STATUS，读取真实 input capabilities，然后在 awaiting-frame/ready 时调用 `session.connectDisplay`；未把能力默认全true。第249–251行限制首次模式的尺寸。
- 状态：**SOURCE FIX OBSERVED / DISPLAY INTEGRATION NOT RUN**。尚未合流模块的状态JSON、relay握手、首真实帧、超时/EOF/输入需要最终复评；此次不把出现调用当成功。

### R4 [P2] native swap/context 失败无法回传，Java/HUD 仍把失败计作呈现成功

- 归属：既有 native 路径尚未满足 Phase5 规格；Java 修改试图排除 mock，但成功依据仍不足。同时属于 Standards 轴。
- 证据：`app/src/main/cpp/graphics/native_gles_presenter.cpp:462–465` 忽略 `eglSwapBuffers` 返回与 EGL 错误，随后无条件设置有限 `lastPresentTimeMs_`；JNI `app/src/main/cpp/native_gles_presenter.cpp:56–77` 的 nativePresent 为 void。`NativeGlesSurfacePresenter.java:162–176` 仅据 finite/nonnegative 耗时计 success，`DoubleBufferedRenderer.java:155–160` 又无条件递增 renderedFrames/FPS。
- 触发：EGL_CONTEXT_LOST/EGL_BAD_SURFACE、GL upload/draw 失败、native early return或JNI未取得数组。Java没有真实present结果或generation，getter可能沿用上一帧有限数；ready与HUD仍可显示成功，UI不会要求重建资源。
- 规格：spec 第44行“修复...context-loss”，第24、36行诚实能力和逐级验证；issue08 第18行。
- 建议：让 native 返回明确成功/失败（包含错误类别、当前Surface/generation、已呈现frame）；每次调用先使旧成功状态失效；检查 GL/EGL 操作/eglSwapBuffers、context-loss时撤ready并走资源重建。renderer只在实际成功后计 presented/FPS；传输CONNECTED与presenter-ready分开。
- 状态：**OPEN / STATIC PATH CONFIRMED**。没有执行GPU错误注入，不能声称发生了某次设备崩溃。后续至少要覆盖present失败不计success、context-loss重建与真实Surface测试。

### R5 [P2] 双PBO总上传上一帧，静态画面的最后更新永远等待下一帧

- 归属：既有 native 流水线与本期静态damage策略不相容，属于规格缺口。
- 证据：`graphics/native_gles_presenter.cpp:383–415` 首帧上传 nextIndex，此后把本次pixels写到nextIndex却从prevIndex upload。`DoubleBufferedRenderer.java:126–129` 没有新frame时停止tick，没有flush pending PBO。
- 触发：真实应用产生F1、F2两次更新后静止。host呈现F1，F2写入PBO却仍upload F1；没有后续F3，不再调present，最终输入效果不显示。连续动画时固定落后一帧，metrics所称frame与实际texture也不同。
- 规格：spec 第20、22、43行要求画面随guest输入改变、无虚构frame且队列有界。
- 建议：当前frame写入后上传当前PBO（仍可通过orphan/同步保护复用），或显式维护待呈现frame并安排一次flush；idle优化只在最新真实frame已呈现后停。不要制造第三张模板/重复guest帧掩盖未显示的最后一帧。
- 状态：**OPEN / STATIC ALGORITHM CONFIRMED**。需最后一次frame后不再有输入的内容验证、frameId与实际present关联，之后再跑Android设备。

### R6 [P2] Surface未ready时消费帧、暂停时清空缓存，静态桌面恢复可持续黑屏

- 归属：既有Surface生命周期与新增session显示adapter的整合缺口。
- 证据：`DoubleBufferedRenderer.java:131–138` 在 presenter-ready检查前把back移到front、清hasNewFrame，未ready时直接返回；`WaylandSurfaceController.java:394–410` surfaceDestroyed/onPause调用reset，清除最后frame，onResume只改变enum、onSurfaceCreated只setReady。`QemuWaylandChannel.java:55–60,70–71` 仅转发新frame，registerFrameListener没有重播或缓存。WaylandSurfaceView未在ready/resume时请求全量refresh。
- 触发：首帧早于Surface建立，或静态桌面切回终端再打开/旋转重建。最后真实frame被消费/丢弃或旧renderer销毁，新listener收不到旧frame，damage-based stream无新像素则不产生下一帧，画面可能一直黑直到额外输入。
- 规格：spec 第21、23行要求同实例切换不重启compositor、Surface重建重新附着。
- 建议：在session/channel保存最新有上限的真实FrameData；新listener、Surface-ready/resume时重呈现最新frame，或明确发送有界全量refresh。reset/close、stream generation和FrameData owner要区分，旧会话帧不跨新generation复用；恢复不伪造画面。未ready时保留pending，不先消费后丢弃。
- 状态：**OPEN / STATIC PATH CONFIRMED**。需“仅一帧、Surface后来ready”“无后续damage的旋转/切换”“旧generation帧拒绝”回归和真实Surface验收。

Spec轴共6项：R1–R3已见源码修复但未运行，R4–R6在截止快照仍待修。未将未合流worker模块/构建待依赖计作他们的缺陷。

## 已核对的局部正确性与边界

- 新 FEATURES仅version1+固定profile/action，EXPOSE/UNEXPOSE限制code-server；使用fixed execl argv，无shell command evaluation；鉴权发生在serve前，helper输出长度上限4096。DISPLAY坏参数/不支持FPS/helper失败会返回ERROR并continue，而不是立刻结束control loop。
- BridgeClient.feature复用pending/response pump；生产pump只有一处inbound reader，新feature没有另建control reader。错误响应本身不调用failTransport；没有把未鉴权feature发出去。
- FrameData构造先校验边长4096、总像素4,194,304、stride alignment/long乘法/16MiB、format和实际length，再clone。只证明此构造边界，尚未合流decoder是否在分配前应用相同gate需后续review。
- QEMU新增app filesDir下实例派生的filesystem display socket；QemuLaunchSpec拒comma/control/超长路径、要求virtio-serial并新增独立chardev/name；当前没有把abstract socket本身当跨UID安全凭证。Socket cleanup/crash、实际guest端口枚举与认证relay的完整性尚未运行。
- Choreographer调度改投main Looper并以generation取消，去掉“失败就在任意reader线程立刻render”的旧fallback；不等于native跨Surface/context-loss已验证。
- NativeGlesSurfacePresenter移除JVM mock-ready/mock耗时；没有因此解决R4/R5。JNI/CMake实际编译source为`native_gles_presenter.cpp`+`graphics/native_gles_presenter.cpp`，引用不是无效旧文件。
- GuestFeatures页明确设备验证未完成，未把rootlessPodman/DockerCLI与daemon/guestGPU强行宣称可用。完整profile可选性、7发行版元数据和资源预算属后续worker合流/最终UI复评。

## 审查快照与基线身份

2026-10-13 11:44:20 UTC 定点采集如下文件摘要。captured-before原件的guest-agent、BridgeClient、QemuInstanceLauncher、DesktopActivity SHA均与coordinator manifest相符。未枚举/打包整个仓库、Git历史、凭据或用户实例。

| 文件（相对WORK） | 基线 | 截止SHA-256 |
| --- | --- | --- |
| `guest-assets/agent/guest-agent.c` | captured-before `bc404ee51f1eef710f7781826a3a811eb94bd79b5b679d1e096a0d32c68dad9e` | `1cf60b9e1bd9ac0283c31afd8b8f47eb478e8107bcd7f50e514d6f99a203dc60` |
| `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/BridgeClient.java` | captured-before `2e142b00812c122b10bea75a3046d51ef14c329362ed30446b5fd137820a43b5` | `32261a008c33eafb866f3378773f6ada359ad704281a516aca0029f934876914` |
| 同package `WaylandChannel.java` | HEAD | `db478858365e66e1740edb304a56f34f79c4b38347a24a29e868139a4937eb35` |
| `runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/QemuLaunchSpec.java` | HEAD | `8756247fa34434ef8bb9aeea00de716d576460ca32cc8f1dd3f34c9ffe98ffc6` |
| `app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java` | captured-before `d64a0cdb356188a28f961ccd055ebc7c3e109ad9e2d59a2aa04f0ef56d92527e` | `710766279b24dc3af65eb2959533ad951a8a719881aab65b27e6124ff7b81a02` |
| 同App package `NativeGlesSurfacePresenter.java` | HEAD | `151ac650135ce1df471a84eb4be3fcd19c91e244c41e84535a1569429230dde1` |
| 同App package `WaylandSurfaceController.java` | HEAD | `b868af8b8aaee57476a793adee6984be42121625d7411fa72e1765e59f36e32e` |
| 同App package `DoubleBufferedRenderer.java` | HEAD | `eee09c252279520a0cedb993f3af238fd3ee6d40f673db6e572d853fdf6a9e55` |
| 同App package `GuestFeaturesActivity.kt` | 新文件 | `245e1e9b484c8249c12e1d230c1534580e3e828f217cf074cedc33686d6adb6e` |
| 同App package `DesktopActivity.java` | captured-before `89a2ae8607b7dc3be4cd03cb54a8c89688d022ca254b995763ec963a7800de18` | `03633f8d6922173a4dd1f16f89d97c54d056b9c6f9834eb52d7e6627253476f4` |
| 同App package `QemuWaylandChannel.java` | HEAD | `2df5394a4d757ce1a65977b425e6ac1b125b4052e8486716d4fb7f2e7f4d2b12` |
| `app/src/main/cpp/graphics/native_gles_presenter.cpp` | HEAD，既有实现仍有呈现缺口 | `579236b15b6351fb55e2e11c91b2cb343e2d5ed336dac64e1c24e6a66cb47e65` |

## 执行、未运行与交付

**已执行**：指定文件/必要调用点只读读取、限定rg、捕获基线与manifest hash核对、逐文件实际diff对照（非单纯HEAD差异）、修复后定点源码复核。R1–R6均已即时发送协调者，未等待报告才反馈。

**未运行**：编译/Gradle/APK、POSIX helper执行/安装、guest/AVF/QEMU实际启动、feature过程/Activity生命周期回归、RFB帧/键鼠/IME、native EGL/GL错误注入、Surface旋转与真实帧内容、GPU/FPS/资源性能、任何设备测试。本review没有用没有设备的静态检查写全绿。其他会话compile日志不是本review当前源码的运行验收，未转述成通过。

**实际修改文件**：仅本报告。无生产、协议、测试、UI/Gradle、tracker/ADR修改；无新增参考下载、网络请求、凭据读取、宿主内容外发；无adb、设备、宿主系统操作；无commit。此前desktop research产物属于前一已完成scope，不是本review修改。

**风险与后续**：协调者处理R4–R6并保全修复输入；R1–R3需真实过程/生命周期/显示集成回归。最终review应覆盖worker合流后的完整payload/静态AArch64 agent/bundle manifest、profile JSON与signed包事务、token relay/RFB bounds、presenter错误与cold recovery，再列每发行版/backend真实未运行矩阵。不得由本early报告放行设备/发布。

**Architecture Change Policy**：没有修改决策、权限白名单或运行分类。上述为既有Phase5规格内正确性修复；CPU-copy/零拷贝/GPU/USB相关既有ACR归协调者，不由reviewer创建或替代批准。

报告人：Codex / early independent reviewer ｜ 日期：2026-10-13 ｜ 耗时：未统计
