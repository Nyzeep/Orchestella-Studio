# Phase5-05：有鉴权的真实 Wayland CPU 帧切片实施报告

- 日期：2026-10-13。
- 执行者：graphics implementation worker / new-provider/gpt-6.1-sol；亲自实施、自审，无进一步派生。
- 工作区：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_graphics`。
- 分支：`phase5/worker-graphics`；HEAD 基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`，任务期间未提交。
- 规格与工单：`.scratch/orchestella-phase5/spec.md`、`.scratch/orchestella-phase5/issues/05-authenticated-real-display.md`；派工：`docs/reports/orchestration/phase5-dispatch.zh-CN.md`。

## 1. 任务目标与边界

在独立 display package 实现原创纯 Java RFB 3.8 CPU-copy 通道，先进行 per-VM display token 鉴权，再解析服务像素与发送输入；新增独立 guest relay。Android、Bridge、FrameData、guest-agent/init、Gradle、ADR、tracker 的接入由主协调者负责，本 worker 未修改这些既有文件。

读取限于本工作区任务源码/政策/规格，项目 implement/tdd/code-review 技能及离线工具定位。网络访问无；Android/adb 操作无。外部输出唯一根为 `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/implementation`，下设 `temp`（Class C 定点测试输出）、`evidence`（原始执行证据）、`preserved`（即时字节副本及交付 patch）。保留所有 Class A 资产，无清理、无系统安装、无其他工作区写入。

## 2. 实际完成与验收对应

| 要求 | 实际结果与证据 |
| --- | --- |
| 原创 RFB 3.8 与 display v1 token | 完成。严格发送 `ORCHESTELLA/DISPLAY 1 <64 lowercase hex>\n`，仅接受 `ORCHESTELLA/DISPLAY READY 1\n`，随后要求 RFB `003.008`。None security 仅在已经鉴权的专用私有流上允许；拒绝其他 security、失败结果、版本与超长响应。测试只用合成 token，不读取真实凭据。 |
| Raw / DesktopSize 与格式 | 完成。请求 32bpp、depth24、little endian、RGB maxima255、shifts16/8/0；逐 Raw row 转 RGBA，alpha255。支持 DesktopSize，重新校验与等待完整新像素。未实现/未协商 CopyRect，收到即 ERROR；其他编码亦失败即停。 |
| 限额 | 完成。边长≤4096、像素≤4,194,304、RGBA framebuffer≤16,777,216B；每 rectangle 同样先校验，单 update Raw 总载荷≤16MiB；≤256 rectangles/update；desktop name/security reason≤4096B；忽略的 server clipboard≤65,536B。所有外部长度先检验，再分配/读取。 |
| 首帧与模板 | 完成协议层行为。BitSet 跟踪真实收到的 Raw 像素覆盖，只有完整覆盖的 framebuffer 才发布；部分首帧不补造黑色区域。仅完整首个像素 frame 后 CONNECTED。Resize 清空覆盖、转 DISCONNECTED、请求 full update，新尺寸完整覆盖后恢复 CONNECTED。无宿主模板帧。 |
| 状态、close/reconnect | 完成。state/message/bounded history(64)；EOF、非法编码/限额、token拒绝、握手/首帧/resize/已开始半包/阻塞写超时为 ERROR。Close 幂等、可在监听回调内调用；reconnect 显式使用新 streams，不自动重试、不重播旧帧/输入。 |
| 单 reader/writer 与有界 | 完成。每 generation 单 reader、单 writer；默认输入队列64，可设1..1024，额外保留一个协议命令位置。键盘/text/pointer事件以完整批次写入，不发生 packet 交织。每个完整 FramebufferUpdate 仅追加一次请求，Bell/剪贴板不产生请求。无 frame queue；同步 consumer 回调产生背压。cancel/failure 唤醒 pending write latch 并关闭 transport。已取消 timeout 从 scheduler 移除。 |
| 输入与能力不足 | 完成。显式 InputCapabilities 默认关闭。evdev→X11 keysym（US基础可打印键、虚拟栏Ctrl/Alt/Shift/Super/Esc/Tab/方向键及常用特殊键）；未知键/未知modifier返回false。文本校验 surrogate与≤1024 Unicode codepoints，逐 `0x01000000 | codepoint` press/release；文本临时释放已保持的 modifiers、随后恢复，防止文本被Ctrl/Super latch解释成快捷键。pointer支持绝对、保留小数相对位移、Linux button映射、物理mask映射、保留按钮的双轴wheel pulse与小数积累。未知Touch/Gesture与未探测能力返回false，无shell/wtype。 |
| 样本统计 | 完成。frames、updates、rectangles、pixelBytes、receivedBytes、sentBytes、成功写出的inputEvents、rejectedInputEvents、listenerFailures、first/last receive timestamp。≥2个合法完成帧且时间差>0才有 averageReceiveFps，否则 OptionalDouble.empty；不是 compositor FPS/屏幕呈现 FPS/GPU结果。 |
| Guest relay | 完成可审查源码。固定 token/device/runtime/socket；root读取0600token，仅display preamble；鉴权后用全新Python interpreter exec为普通用户，清空supplementary groups、环境与其他FD，避免token/request内存随root fork留到普通用户。实际connect、SO_PEERCRED校验成功后才READY；connect失败ERROR unavailable，等待下一token；畸形/错误token有限恢复、不涉及control。select双向64KiB有界缓冲，partial writes、背压、stall拒绝、EOF终止。 |
| AVF vsock | 实现显式 `--transport vsock` 的 AF_VSOCK 9103 bind/listen能力探针与只接受CID_HOST=2的host peer。失败无TCP fallback。仅测试失败分支，未运行真实成功probe/AVF连接；不能声明AVF显示可用。 |
| 中文报告与patch | 本报告及7个新增文件的可apply本地patch；未git add、未commit/merge/reset/push。 |

## 3. 重要语义边界

协调者告知 wayvnc 可能在 compositor 真实 capture 前发灰色 placeholder（版本化源码事实由协调者的 graphics research 承载）。因此 CONNECTED 只证明 token鉴权服务送来合法、完整的像素 buffer，消息明确 `compositor capture not verified`；不等同 desktop ready。Profile/UI 需要独立 capture/live application proof 与正确 generation 门，不能仅依首帧宣布桌面启动成功。此 worker 的 wire 测试、最大帧测试均为合成服务数据，不是 guest/GPU/性能验收。

FrameData 既有校验不足由协调者负责；本实现内部在构造前严格限制 dimensions/stride/data 长度，不改既有契约。None security 不提供加密；安全前提是专用 per-instance 私有数据流、root-owned token、固定0700普通用户Wayland runtime/socket及host connector认证，禁止公开网络listener。

## 4. 精确新增文件

全部新增，未改任何既有 tracked 文件：

1. `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbDisplayChannel.java`：public深模块/流连接、状态/输入队列/统计。
2. `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbProtocol.java`：package-private原创RFB协议、限额/像素转换。
3. `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbInputEncoder.java`：package-private writer-confined输入映射。
4. `runtime/contract/src/test/java/io/github/nyzeep/orchestella/runtime/contract/display/RfbDisplayChannelTest.java`：17项JUnit4.13.2公共接口/合成wire测试。
5. `guest-assets/display/display-relay.py`：原创Linux/Python≥3.9私有display relay。
6. `guest-assets/display/tests/test_display_relay.py`：15项Linux测试，按真实wire/native probe/合成OS metadata区分。
7. `docs/reports/tickets/phase5-05/implementation-report.zh-CN.md`：本报告。

## 5. 接入 API 与责任

package `io.github.nyzeep.orchestella.runtime.contract.display`，public API 只有 `RfbDisplayChannel` 及其嵌套类型：

```java
RfbDisplayChannel(StreamConnector connector, String token)
RfbDisplayChannel(StreamConnector connector, String token, InputCapabilities capabilities)
RfbDisplayChannel(StreamConnector connector, String token, InputCapabilities capabilities, Options options)
@FunctionalInterface StreamConnector { Streams open() throws IOException; }
Streams(InputStream input, OutputStream output)
Streams(InputStream input, OutputStream output, AutoCloseable transport)
InputCapabilities(boolean keyboard, boolean unicodeText, boolean pointer) // NONE默认全部false
Options(int inputQueueCapacity, long handshakeTimeoutMillis, long firstFrameTimeoutMillis)
boolean connect()
boolean reconnect()
ConnectionState state()
String message()
List<StateChange> history()
Statistics statistics()
InputCapabilities inputCapabilities()
// WaylandChannel全部listener/register/sendInputEvent/close方法已实现
```

`Options.DEFAULT = (64,15000,30000)`；第三项亦限定resize、半包与write，静止桌面等待更新无空闲超时。connector必须返回专用全新streams，设置自己的open超时/响应中断；Streams.close先关闭transport再关input/output，必须能够唤醒真实blocking I/O。回调同步、应快速返回。close/reconnect允许同一channel重附着，但应由单一owner串行调用；input可并发提交，false需向调用者反馈。history为immutable快照；statistics为本连接观测，不混入上一generation。`StateChange(state,message,timestampNs)`，`Statistics`字段见第2节；`averageReceiveFps()`返回OptionalDouble。

主协调者需在contract Gradle补离线JUnit4.13.2 test dependency及运行入口；本 worker 已用独立javac/JUnit验证，没有改Gradle。Android/VM的connector、renderer/FrameData强化、kernel设备、profile安装与捕帧门由协调者合流。

Guest安装与运行接口：

```text
/usr/bin/python3 -I -B /usr/local/lib/orchestella/display-relay.py --user orchestella --transport virtio
/usr/bin/python3 -I -B /usr/local/lib/orchestella/display-relay.py --user orchestella --transport vsock
```

固定路径与权限：`/etc/droidlayer/bridge.token` root-owned regular single-link0600、64 lowercase hex可有一个尾换行（已核对基线init真实路径及chmod）；`/dev/virtio-ports/orchestella.display`只能是专用root-owned0600字符设备，alias可指向`/dev/vportNpM`，核对kernel sysfs name和实际char major/minor name；`/run/user/<uid>`与`/run/user/<uid>/orchestella`均该普通用户owner0700且不接受symlink；`wayvnc.sock`位于后者、该用户owner、group/other无权限，并校验SO_PEERCRED。wayvnc unit需umask077；relay脚本安装root owner、不得group/other writable。`getpwnam`选择已存在普通用户，uid≥1000、非root gid、有效login shell；不创建用户。

root service需要准备固定设备权限（或经sysfs名验证的udev配置）；systemd/OpenRC主管应以进程组管理root与已降权child，停止服务时回收二者。Virtio一次已交付RFB会话结束后supervisor退出（成功EOF可exit0），服务需延时respawn/Restart=always以接受后续新连接；auth拒绝与backend未就绪时同FD继续等下一token。不扫描或解析RFB残留去寻找token，避免错把像素当鉴权。Vsock模式逐host连接处理，未添加公网/loopback TCP listener。

## 6. 测试环境、命令与真实结果

Java：Win32/Git Bash调度Windows Python runner；JDK `D:/JDK17`、`javac --release 17 -encoding UTF-8 -Xlint:all -Werror`，argfile JVM强制UTF-8，避免Windows默认编码无法读取中文绝对路径。只读复用 `V:/gradle-home/caches/modules-2/files-2.1` 的JUnit4.13.2与hamcrest-core1.3。classes/argfile/java.io.tmpdir均在A/implementation/temp。未调用Gradle、未写Q构建输出或共享cache。

```text
PYTHONDONTWRITEBYTECODE=1 python "D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/implementation/temp/run-java-tests.py" java-12-full-regression --all
```

最终：编译exit0；既有runtime/contract全部17个main测试入口exit0；新增JUnit `OK (17 tests)`，最终原生输出中JUnit time0.883s（测试执行时间，不是guest benchmark）。新增覆盖鉴权/像素变化/首帧完整/resize/每update一次request/security/version/限额/EOF/错误code/close重入/reconnect/callback背压/default capability/输入字节与modifier/非法text/queue满/write失败/半包与write超时。早期RED与编译工具编码失败也保留，不将其写成通过；发现的resize状态、close递归与半包超时已修复且重跑。

Linux：既有WSL发行版 `Ubuntu-24.04`、`/usr/bin/python3` 3.12.3、现有root入口；没有安装WSL/驱动、没有新增用户。runner从Windows以参数数组调用wsl，所有TMPDIR指向A/implementation/temp/relay-fixtures。系统发出既有localhost proxy提示，不改变配置、无测试网络请求。

```text
PYTHONDONTWRITEBYTECODE=1 python "D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/implementation/temp/run-relay-tests.py" relay-04-final
```

内部真实入口：`wsl.exe -d Ubuntu-24.04 --exec /usr/bin/env PYTHONDONTWRITEBYTECODE=1 TMPDIR=<A/implementation/temp/relay-fixtures的/mnt/d绝对路径> /usr/bin/python3 -B <graphics工作区guest-assets/display/tests/test_display_relay.py的/mnt/d绝对路径> -v`；从Git Bash直接调WSL需`MSYS_NO_PATHCONV=1`。最终 `Ran 15 tests ... OK`、exit0，原生time0.574s，仅宿主合成测试耗时。

15项分层：3项真实独立进程+pipe+AF_UNIX socketpair的合成RFB/auth/input wire；4项真实pipe/socket的auth超时/no-prefetch/尝试上限/1MiB背压/断流；7项固定CLI、用户/path/token/socket/device/sysfs/vsock失败的合成OS metadata负例；1项真实Linux subprocess exec降到已有nobody uid/gid65534、补充组为空、control FD关闭、敏感synthetic环境不继承。最后一项在Popen命令边界将业务worker替换成身份probe，证明原生exec权限/FD参数，未证明完整安装后普通guest用户连接wayvnc；sandbox没有可用uid1000普通账户，未新增账号。

## 7. 证据与本地 patch

外部根简称 A/implementation = `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/A/implementation`。

- 最终Java：`evidence/java-12-full-regression.log.txt`；即时副本同basename位于`preserved`。
- 最终relay：`evidence/relay-04-final.log.txt`；即时副本位于`preserved`。
- 历次RED/GREEN：`evidence/java-01-*`至`java-11-*`、`relay-01-*`至`relay-03-*`；保全副本位于`preserved`。所有证据使用`.log.txt`，不用`.log`。
- 完整新增patch：`preserved/phase5-05-graphics.patch`；由6个untracked源码/测试和1个新增报告逐文件与`/dev/null`的no-index diff生成、统一仓库相对path。既有`.gitignore`的`/docs/`规则忽略此报告，patch显式包含报告；不改ignore规则、不git add。包含生产源码、测试与报告，不含生成输出。
- 最终文件/patch/evidence SHA-256及范围/patch验证：`evidence/delivery-manifest.json`和`evidence/delivery-checks.log.txt`，字节副本位于`preserved`。
- 可复现一次性runner留在`temp/run-java-tests.py`、`temp/run-relay-tests.py`；不是生产source或入库文件。主协调者保留evidence/patch用于review，temp不在本次清理范围。

## 8. 未运行、风险与后续动作

未运行：真实Wayland compositor/live app capture、发行版wayvnc None/Unix服务、实际guest virtio字符设备收发与disconnect/reconnect、AVF权限/vsock成功probe与VM连接、Android renderer/IME/旋转/Surface恢复、APK构建、guest/Android性能benchmark及GPU验证。当前产品级桌面验收仍待主协调者；合成wire不提升上述状态。

已知限制：仅Raw/DesktopSize，CopyRect/压缩/extended clipboard/touch/gesture不支持；US基础keysym映射不宣称任意键盘layout，锁状态modifier不足明确拒绝；Unicode经过已验证虚拟键盘能力仍需live application验证。CPU-copy最高4MP带来buffer/FrameData拷贝成本，未承诺60FPS、零拷贝或guest GPU。关闭取决于connector与transport正确打断I/O，文档/streams明确责任。callback同步有背压，UI需快速接收。virtio会话结束需service respawn，profile worker须落实设备runtime与普通用户权限并管理进程组生命周期。

新发现已反馈主协调者：None/RFB首帧仅是transport，不是capture ready；初始部分Raw frame不应发布未收到的区域；既有contract Gradle缺JUnit依赖需主协调接入；固定guest token真实路径与runtime/device权限要求已确认。未重新做大范围research，不修改并行研究结论。

## 9. 审查、架构与状态

implement/tdd/code-review Skill调用均同名歧义，按用户授权直接读取项目SKILL；采用公开stream/process测试接缝，测试先RED后实施GREEN。自审按规范轴与spec轴完成，修复资源/超时/生命周期/设备名称问题；这是worker自审，不是独立review批准，主协调另行review。

无越界：仅7个新增owned/report文件，既有文件0修改，所有临时/证据在指定外部根；无adb/安装/force-stop、无网络listener的TCP分支、无系统或WSL配置改动、无commit/merge/reset/push。branch无upstream，main与HEAD ahead/behind为0/0。

Architecture Change Policy：CPU-copy、token/private通路、能力拒绝与独立深模块均在用户/Phase5规格已授权范围；不改变ADR0017/既有暴露面，GPU/共享内核等既有冲突由协调者ACR承载，本票不静默修改决策。建议状态：owned实现可供review与合流；guest/AVF/Android验收明确未完成。

## 10. Commit

无。分支HEAD保持基线，全部新增文件未暂存。中文报告及本地patch按用户要求交付，不执行技能/历史报告规范中的提交步骤。

报告人：graphics implementation worker / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计
