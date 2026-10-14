# 品质优化与 Phase 5 双线合流实施报告

- 日期：2026-10-14；执行者：Codex / new-provider/gpt-6.1-sol。
- Scope：按 [完整执行 prompt](../../agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md) 整合品质线与规范 Phase 5 增量，补齐身份链、统一运行资源，完成适用验证、独立审查、提交与第一方集成分支普通推送。
- 集成工作树：`Orchestella_merge_dev`；分支：`integration/quality-phase5`。
- 共同基底：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 品质 foundation：`3b637e48f51fb4b78282d468d9c78b37d582de87`。
- 合流源码提交：`cfec895ca0bd0186b212f7c73d27b8d6bb06898c`。
- 最终源码提交（含编辑器终审修复）：`6bbc31c29e97c0eba64122d22d9ea301d658d097`。
- 第一方远端：`git@github.com:Nyzeep/Orchestella-Studio.git`；GitHub API 实核 owner/name、PUBLIC 与当前 ADMIN 权限；本次只向集成分支普通推送。

## 实际合流

两来源工作树的 HEAD 相同，成果未提交。本次在共同 HEAD 建立独立 worktree，未修改、切换、reset、clean、stash 或删除 `Orchestella_dev` 与 `Orchestella_phase5_dev`。两个来源索引均保持原状态。

品质冻结 patch 的实际大小与 SHA、131 个具名源码及 12 项公共运行/构建输入全部匹配，source drift 为 0；新树 `apply --check` 与 apply 均成功。品质 foundation 实际完整 App 测试 628 项零失败，contract、instance、QEMU、AVF checks 通过后单独提交。原有未提交 UI 工作随品质闭包保留。

Phase 5 的 14 个交付校验项、141 个文件闭包与 before 原始字节均重新核验匹配。按 manifest 的 32 个 tracked-HEAD、14 个 captured-live dependency、95 个新文件分别选择真实基底；没有将 synthetic replay 等同于当前主线 apply，也没有重复移植五个 worker 的 patch。机械移植为 95 个新增、33 个可直接移植、9 个三路合成、3 个手工冲突解决与 1 个生成投影。保留最新品质中的 15 个旧复制副本漂移文件。

3 个文本冲突为 DesktopActivity、QemuInstanceLauncher 与 PortForwardingPresenterTest。桌面基础启动期限保留 3900000ms，optional 启动 120 秒单独处理；QEMU Session 同时保留实例 ID/control-only recovery、显示 socket/token 和临时服务归属；转发测试保留单份计数 fixture 与两侧断言。详细来源与语义由 [既有冲突地图](merge-handoff-conflict-map.zh-CN.md) 承载。

## 实现与实际回退修复

1. FEATURES1、DISPLAY、CONFIRM-FRAME 接入原 BridgeClient 的同一 reader、序列化 exchange、HELLO→READY→PING→PONG 认证屏障及原 deadline。启动/首次 settle/普通事务预算分别保留 3900000/30000/5000ms；不增加竞争控制 reader。扩展被拒后 PING 可继续。
2. C agent 合成主线幂等 HELLO、固定 POWER 白名单和 ACK 后 dispatch，以及 Phase 5 有界 helper、部分写/EINTR、DISPLAY/FEATURES/子进程回收。POWER ACK 继续只代表受理，STOPPED 由真实进程退出决定。
3. 保留品质 native init、仓源 ownership、SHA/大小门、已有盘保全、manual recovery、随机密码与未知旧密码不重置、VPN/active-network DNS、private services API 和两个 QEMU 参数分支的 `cache=writeback`。
4. 身份从向导/creator 保存到冷加载，再经 LaunchParameters 和 QEMU/AVF bootstrap 传入 guest。hostname/username/hasSudo 保留旧签名兼容并严格验证；普通用户 profile 不以 root 替代。向导增加明确用户、主机名与 sudo 选择，仍使用原实例随机凭据；noSudo 不被一律拒绝。
5. guest 非秘密身份 marker 采用 root-owned 0600 与原子发布；profilectl 读取同一 FD 快照验证，绑定实际普通用户、home/subids/units。缺失、root、非法或不安全身份显示 action-required。旧密码未知/空值不生成新密码。
6. profile 选择驱动将健康 owner 与瞬态 reauthentication 分离，旧 client/session/generation 不再启动 profile 或移除新 owner。握手中重入终端只等待同一个 cached client；同终端模型重新绑定保留 READ 阅读锚点及累计淘汰行基准。
7. 显示首帧确认使用 generation 和本地 epoch ticket，防同 generation resize 的迟到 ACK 误确认。暂停/销毁/旧 channel 回调、Android 持有的旧 IME 连接与 early EGL 失败的 native window 引用均收口。CPU-copy 标示继续为实验通路，不宣称 guest GPU/零拷贝。
8. Native presenter 的生产默认仍是真实 JNI；测试以实例级 adapter 控制 init/present 成功与失败。保留原测试，补失败不计帧/不确认、恢复和回收负例；未恢复空 Surface 的假 ready。
9. QEMU 启动取得 gateway 后任何目录/参数/进程/会话发布失败都会关闭未发布会话、销毁本次进程并一次回收 gateway；成功 watcher 与失败路径共享回收门，允许同实例重试。真实显示 socket 在连接前就由一次性 close owner 持有，Future 期限/取消关闭它，connect 前后均核取消；连接 executor 最多 2 worker 且无队列。回归覆盖不可中断连接依赖 close 唤醒、排队超时、成功移交与 interrupt。
10. 编辑器健康查询改成单 flight 与完成后再调度；cookie 回调核 owner/policy，LAN 默认关闭且最多 10 分钟，退后台/失败/退出回收。服务转发不持久化 autoenable。AVF service forwarding、无安全 Android provider 的 9p、SAF/USB 原型仍明确拒绝或 gated。
11. profile 操作期限覆盖 leader 退出但后代继续持 stdout 的路径，非阻塞读取和 EOF/leader 共同收口；取消/timeout 终止原进程组，未遗留 drain 线程。STATUS/错误 schema 保持 job_active 布尔字段，状态/owner 用一致快照，故障失败原件保留。
12. 固定提交终审发现 LocalEditorActivity 的 singleTask 重入忽略新的实例 Intent。现 onNewIntent 先更新选择，跨实例/已关闭页面先退休旧 scope 与 WebView，再重建页面；同一串行队列先确认旧服务撤销，新 scope 才能 EXPOSE。缺失实例关闭页面，同实例存活 scope 保留。5 项 A→B/重复切换/关闭重开/缺失选择回归进入最终 663 项 App suite，修复单独提交为 `6bbc31c2`。

## 模块、委派与产物

源码改动覆盖 app 显示/页面与身份接线、runtime contract/instance/QEMU/AVF、guest canonical modules/profile/relay、测试与 producer。完整精确闭包见外部 `merge/final-source-closure.json`；195 个具名变更路径进入合流提交，未强加整个 ignored 目录。

实现并行分工：identity/profiles、独立 display/services/data/AVF/9p、冻结真实性/工具链、POSIX 协议与环境验证。协调者串行拥有控制、C、launchers、generator/entry、共享页面、Gradle/manifest、运行包及 Git 写入。两条终审轴独立；品质审查者不审自己编写的 input lock，由另一轴独立审。

最终 ARM64 agent 实际重编为 477400 bytes，SHA-256 `6c6470872f2bc45c56729bb4d9848f0f0b5e559f79dc8b3e64e3ba4c643893e1`，源 SHA `f1250967916612da9680526a51fa99973c711b21ed6b4044cbd2468dff1c5fb5`。ELF64 little-endian AArch64，无 PT_INTERP/PT_DYNAMIC，GNU_STACK 不可执行。47 项输入锁包含 39 个实际消耗头文件及工具/link 输入；头文件与锁定 Alpine 包成员逐项字节相同，producer 在编译前后验证完整消费闭包，缺锁头文件负例在生成 binary 前拒绝。

公共 cpio SHA `c96fe6e6d95b097b7b7509a669b8aaf19acf73de4541d4b4e069795213540361`，只替换唯一 agent，其他 428 项 payload/mode 保全。canonical init 与 APK guest_init 同为 230603 bytes，SHA `75c4d6e7b30013092f1220b9980ba552f22c9b50490fcc8687c970935f0a72b8`，7 个 optional payload 来自最终源，generator `--check` 通过。

[运行构建输入锁](../../../tools/phase5/runtime-build-inputs.lock.json)、[agent 输入锁](../../../tools/phase5/guest-agent-inputs.lock.json) 和 [生产/验证说明](../../../tools/phase5/README.md) 已入库；大二进制留在外部批准 artifact 根。QEMU/GPL/source-offer、guest runtime NOTICE、desugar GPL+Classpath/Assembly Exception 与真实产物对应。受摘要校验的 asset 在 Git 中保留原始字节，避免 checkout 换行改变输入。没有发布 release 或声称已执行公开二进制对应源码发布门。

## 验证与设备边界

完整本次结果、失败历史、APK 身份/签名及 NOT_RUN 矩阵见 [合流验证报告](quality-phase5-integration-verification.zh-CN.md)；小型脱敏摘要见 [verification-summary.json](../../../artifacts/quality-phase5-integration/verification-summary.json)。最终 App 663 项全绿、全适用 runtime checks、profile 完整 39 项全绿、lint 0 错误/688 警告、Android ARM64 NDK 与 APK 构建通过。

候选 APK：`Orchestella/artifacts/quality-phase5-integration/delivery/Orchestella-Quality-Phase5-local-debug.apk`，71969178 bytes，SHA `e0ac730bd4e0441e6d9d347e7f25643f1ba9b0b00c4c8fa5a63474fdd8e7101a`；正式主 ID/code300/0.3.0-preview.1/min26/target36。Debug signer 与已安装主应用 release signer 不兼容。已核设备 MEY-AN00/API36、无既有 QEMU/gvproxy，已安装 base.apk 与品质冻结摘要一致；本次未尝试覆盖安装、未卸载或清数据，也未读取/复制 release 签名材料。设备运行回归因此 NOT_RUN，不能据宿主 fixture 宣称真机 Linux/桌面/IDE验收通过。

## 未完成与风险

真实 ARM64/Android 显示/input damage、EGL context-loss/旋转、普通用户 profile/IDE/WebView WS/SW/cookie/LAN客户端完整设备验收未运行；基础 init/DNS/POWER/冷保留定向设备回归也被签名不兼容阻断。Windows guest-assets suite 的 2 项 Linux-only 用例跳过，POSIX/relay/9p 各自 Linux 适用门实际执行并单列。

GPU/zero-copy、KDE/niri、另外五族增强桌面/profile、Docker rootless daemon、USB 原生透传、生产 SAF broker、真实 OEM AVF 与 Android 9p provider/mount 仍未实现、未接通或未实测，见验证矩阵。已有 ACR-01 不在本轮裁定，未触发新的 Architecture Change Policy。默认终端与明确实验入口保留，不批量提升发行版 capability。

688 项 lint 警告仍存在。本次普通分支推送是源码/具名文档交付，不能扩大为二进制发布或已验证产品能力。后续设备验收需要与安装主包兼容的合法签名候选或单独授权的隔离测试包，不得通过清实例数据解决。

## 审查、提交与推送

源码提交已完成；最终报告/审查摘要随后作为具名文档提交。两轴报告入口： [品质/协议](../reviews/quality-phase5-integration/standards-review.zh-CN.md)、[Phase5数据/产物](../reviews/quality-phase5-integration/spec-review.zh-CN.md)、[终审汇总](../reviews/quality-phase5-integration/review-report.zh-CN.md)。推送结果以最终终审汇总及外部 `delivery/push-result.json` 为准；本文生成时终审和普通推送待收口，未预称远端成功。

## 数据与宿主边界

未向任何参考仓库写入、创建 PR 或推送；网络仅第一方远端核实与缺失固定 Gradle 依赖解析。原来源树、Class A 工具链、原实例盘和旧失败证据保留；无安全策略、系统 PATH、注册表、driver/service、firewall 或 WSL 配置变更。新证据使用 .json/.md/.log.txt，并保全孪生副本。任务自有 profile 记录 child、固定 lock、设备临时服务/forward/lease终态均为 0；七发行版长测队列保持取消。

报告人：Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-14 ｜ 耗时：未单独统计
