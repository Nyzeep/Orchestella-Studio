# 双线合流交接：Phase5 交付与冻结状态只读核验

- 背景 / scope：用户已结束开发及七族验收，本报告只核验第二开发线的本地交付真实性，为新会话合流提供锁与边界。没有继续实现、运行构建、应用 patch、操作设备、修改索引、commit、merge、push 或访问网络。
- 核验时间：2026-10-13，冻结状态采样于 23:17（UTC+08:00）。执行者：Codex / new-provider/gpt-6.1-sol。
- 工作区根：`D:/Development project(开发项目)/Orchestella开发目录`；主仓 `Orchestella_dev`；第二线 canonical 为 `Orchestella_phase5_dev`；外部交付根 `Orchestella/artifacts/phase5-parallel-20261013/delivery`。
- 唯一新增文件：主仓 `docs/reports/orchestration/merge-handoff-phase5-verification.zh-CN.md`。不产生其他报告、脚本或验证产物；此报告没有提交。

## 结论与冻结状态

**Phase5 本地交付的已锁定字节真实且一致，可以交给继任会话审查和合流；完整 Phase5 Android / ARM64 guest 产品验收仍未运行。** 历史 `PASS_LOCAL_DELIVERY` 不代表当前主仓可直接应用 patch，也不代表 GPU、USB、SAF 或七发行版增强能力完成。

主仓分支 `main`、第二线分支 `phase5/graphics-productivity-storage` 均为 HEAD `8d0e8c75d769ecc645ea102b90a304c8657344a1`，两者没有以新提交区分成果。第二线索引无暂存差异，工作树有 104 个 tracked 修改、107 个 untracked 文件；主仓采样为 76 / 56，索引亦无暂存差异。五个 worker 同 HEAD，索引均无暂存差异。以上是本地只读采样，不宣称操作系统已对目录加不可变锁，也不根据本地状态推断完整远端历史。

交付的 **141 项 named source/report/decision closure** 全部满足：安全相对路径、无 traversal / 绝对路径 / `.git` 路径、无逃出工作区的解析目标，原字节 SHA-256、字节数与换行归一化 SHA-256 均匹配当前隔离源码。patch 的 141 个唯一文件头与 manifest 路径集合完全相同；build-lock 的 sourceClosure 与 manifest records 完全相同，manifest/patch 摘要引用也一致。141 是交付变化闭包，不是整个脏工作树的数量；继承的第一会话源码及 ignored 文档不能靠一次 `git diff` 自动判定归属。

## 当前交付锚

以下均为本次直接读取文件或 APK ZIP 后计算的 SHA-256；不是从历史报告照抄的结论。

| 内容 / canonical 文件 | 实际字节数 | 当前 SHA-256 |
| --- | ---: | --- |
| `phase5-integration.patch` | 1493079 | `aa382388a53c60b11a9b05428336930f649b69ad6208e0eaf7736397cfc7578f` |
| `phase5-source-manifest.json` | 60550 | `e3eb645be924346a3c5620d4c8007623f3b5ea5df10da6b555fb879b4ed01a8d` |
| `build-lock.json` | 66576 | `cc7a5267f8d877a455bcd40c6de95838e3c3fa8f35d1557b377727106ccc13f4` |
| `Orchestella-Phase5-local-debug.apk` | 71892457 | `f58c865d8e83ef8c7629c9cf3ad7037e7f3b3a1e27d6452e7386847a2fed2623` |
| `guest-agent-aarch64-musl` | 463360 | `2713462bf7dad13f166dce51a84f0f456f2f28200367c9f156720e9953bb3117` |
| `guest-agent.c`；对应隔离源码 `guest-assets/agent/guest-agent.c` | 37120 | `7b914f42571ab31394fa71ee04eb6597e64391b528d8cb087d0d449068348d94` |
| `guest-init`；APK `guest_init`；隔离模板 `guest-assets/initramfs/init` | 212801 | `4b353c572bffa54311bae306415981964e74aac26f97018767c728b726fb5e27` |
| APK `initramfs.cpio.gz` runtime bundle | 按 APK 内 manifest 校验 | `fd249df921cfc2ae08c0a14465d847c6726ed3a54862ca753012297249a8b8ae` |
| `runtime-artifacts-v1.sha256`；APK `assets/runtime-artifacts-v1.sha256` | 588 | `fb81c191a7a321796be79512c8a6c48253d1a228c0706fe71c3952deb720fb56` |
| `NOTICE.PHASE5`；APK `assets/NOTICE.PHASE5` | 1866 | `d5b25c7ebf33e769a9834a8eea2a4b2fcc4d45099532995edd608c4c0bb4d082` |
| `SHA256SUMS` 自身（额外固定目录索引） | 1272 | `f087fdb743f16b593010b4c171db8cd83bca91608b785586c14d4fe66e4e759c` |

`SHA256SUMS` 的 **14 / 14 项逐项 PASS**：APK、guest-agent binary、runtime checksum manifest、guest-agent source、guest-init、NOTICE、apk-content-verification、integration patch、source manifest、patch-forward-replay、build-lock、public-references、device-window-request、final-delivery-gate。顶层另有 0-byte `patch-reverse-check.log.txt`，未列入 sums；`preserved/` 是既有保全副本，不把它冒充另一份公开交付集合。

APK 内二进制 AndroidManifest.xml 实际解析为 `io.github.nyzeep.orchestella.phase5`，versionCode **200**，versionName **0.2.0-phase5-local**，minSdk **26**，targetSdk **36**。包内 runtime manifest 的七条 Image / cpio / QEMU 输入及许可 / QEMU ELF 摘要全部匹配实际 ZIP bytes。newc bundle 含 429 个条目、唯一 guest-agent；该条目与交付 binary 完全相同，为 ELF64 little-endian AArch64（machine 183），无 PT_INTERP / PT_DYNAMIC。这是静态 ELF 与打包内容验证，未执行 ARM64 guest。

`guest_init` 与交付 `guest-init`、当前 canonical 模板逐字节一致，内嵌以下七个当前源码 payload：

1. `guest-assets/features/profilectl`
2. `guest-assets/features/profilectl.py`
3. `guest-assets/features/inputs.json`
4. `guest-assets/features/bootstrap-python.sh`
5. `guest-assets/features/feature-session.py`
6. `guest-assets/features/rootless-probe.py`
7. `guest-assets/display/display-relay.py`

NOTICE 与 ZIP 内副本一致；QEMU GPL 记录存在；desugar_jdk_libs 2.1.5 的 GPL + Classpath Exception 许可及 Assembly Exception ZIP bytes 与 build-lock pin 相同。code-server 程序不在 APK，属于显式 guest 下载 profile。完整公开发布对应源码门未执行。早期 `coordinator/evidence/phase5-guest-static-build.json` 记录的是旧 binary，不应替代上述最终 build-lock / 交付摘要。

## patch 来源、历史 replay 与主仓差异

`coordinator/evidence/production-dependency-baseline.json` 的 capturedAt 为 **2026-10-13T19:05:44.835695**，包含 95 个第一会话生产依赖及 10 个 immutable runtime build 输入；`dependency-test-baseline.json` 为 21 个测试闭包记录。另有 `forwarding-dependency-refresh.json`，说明单独复制了当时最新 PortForwardingPresenter 依赖修复。它们属于第一会话早期显式源码快照，**没有从最新 quality 工作树重新生成 Phase5 patch**。

141 个 patch 条目的 before 来源为：32 个 `tracked-HEAD`、14 个 `captured-live-production-dependency`、95 个 `new-Phase5-file`。本次直接核对 32 个 HEAD blob 与 14 个 `coordinator/dependency-baseline/` before 文件，全部匹配 manifest beforeSha256。生成逻辑见 `Orchestella_phase5_dev/tools/phase5/make-local-patch.py`：优先使用 captured before，缺失时才使用固定 HEAD，ignored 文档显式纳入。

对当前主仓的 before-byte 比较：captured 只有 **3 / 14**、HEAD 只有 **5 / 32** 仍完全相同，即 46 个已有 before 文件中 **38 个已发生变化**。这是原字节比较，不据此假定每个 hunk 必然冲突；它足以否定“已经验证可以应用到当前主仓”的表述。主线 source / 测试 / runtime 输入必须在新会话语义合流，不能整份覆盖 shared 文件。

只读既有 `patch-forward-replay.json` 记录：141 files，历史 `apply --check` 与 `apply --whitespace=error-all` exit 0，normalized after 匹配。保留的 **`coordinator/patch-replay-delivery-locked/` 141 / 141 当前 normalized after hash 匹配**；旧 `patch-replay-final/` 仅 129 / 141 匹配，不能用旧目录替代最终锁。两个都是既有 synthetic 文件树，无 `.git` marker。本次没有重跑 patch apply；历史 synthetic replay **不是当前 main apply PASS**，reverse-check 也不是 main forward-check。

## worker 已收束，唯一合流来源

五个 worker 当前修改 / 新增源码路径全部存在于 `phase5_dev`，全部在最终 141 清单内；原 copy manifests 的 supporting report 也已存在。已读取 `coordinator/evidence/` 的 graphics / profiles / data / AVF / ninep integration records 及后续增量记录，未重新应用它们。

| worker 工作树 | 当前脏源码文件数 | 与 phase5_dev 当前字节关系 |
| --- | ---: | --- |
| `Orchestella_phase5_graphics` | 6 | 4 相同；RfbInputEncoder.java、RfbDisplayChannelTest.java 两项不同，以最晚 141 closure 的 canonical 修订为准 |
| `Orchestella_phase5_profiles` | 14 | 14 / 14 相同 |
| `Orchestella_phase5_data` | 18 | 18 / 18 相同 |
| `Orchestella_phase5_avf` | 18 | 18 / 18 相同 |
| `Orchestella_phase5_9p` | 5 | 5 / 5 相同 |

**新会话只从 `Orchestella_phase5_dev` 与最终 delivery 锁取 Phase5 成果。不要多个 worker branch 再合并一次，也不要重放早期 full / incremental patch 覆盖 canonical 修订。** worker 工作树保留为历史证据，当前未清理、重置或移除。

## 实际能力与五行历史验证门

源码实现包括有界 token / RFB CPU-copy 显示与输入通路、真实呈现成功和 generation gate、FEATURES1 白名单 guest 控制、Debian13 labwc profile、Debian / Ubuntu Podman 与 code-server profile、Debian Docker CLI、手机 IDE 本地 origin / 临时端口 lease、AVF API / FD 生命周期与标准只读 9p。USB FD 与严格选集 staging / SAF catalog 仍为 prototype。Wayland 桌面经 RFB CPU-copy 到 Surface 的代码通路有宿主测试；实际 ARM64 Wayland → Android Surface 链路尚未验收，GPU renderer / zero-copy 未完成。vsock 是字节适配器，不能自动获得硬件 shared memory / buffer / fence 能力。

本次没有重跑下列测试；按报告、锁及现存原件核验历史结果，禁止累计多轮或与主仓 quality 测试拼出总数。

| 门 | 历史已运行结果 | 真实边界 / 出处 |
| --- | --- | --- |
| 最终整合 / App | `phase5-delivery-build.log.txt` 的第 282 行 `OK (61 tests)`，第 293 行 `BUILD SUCCESSFUL`；contract / instance / QEMU check、AVF engine、NDK arm64 与 assembleDebug 记录 | 61 是 Phase5 App JUnit，不能当 Main 测试 union 或产品设备测试；build-lock.testGates.integration |
| 显示 / relay / native color | 18 wire JUnit、17 既有 contract 入口、15 Linux relay 程序测试；原生红 / 蓝 / alpha 转换通过 | Linux 进程 / fixture / 转换测试；真 Wayland capture、Android EGL / Surface / 输入未运行；验证矩阵功能表 |
| guest control | POSIX stdin / test TCP 50 / 50 cases、865 assertions、96 helpers，25 对响应等价 | 测试宏 / TCP 不等于 native AF_VSOCK 或真实 ARM64 guest；implementation-report §4 |
| profiles | 原整套 **24 / 25**，一个安全拒绝原因断言失败；修正后的该目标及后续受影响取消 / staging / unit-upgrade 等目标 PASS | **未重新运行完整 25 项**，不得改写为“全套 25 / 25”；code-server 实际 guest 安装 / 运行未验；矩阵及 build-lock.testGates.profilesFull |
| data / AVF / 9p | 26 data JUnit + 17 contract；13 合成 IO metrics；19 AVF shape 契约；9p Linux child 1500 sessions FD 8→8、400 symlink races 无 outside read | Windows / JVM / Linux 合成测试；不是 Android SAF / USB / OEM AVF / guest mount 验收或手机性能；implementation-report §4、验证矩阵 |

## 未运行与新会话继承注意

- Phase5 APK 没有设备安装和运行验收，交付 request 为 **REQUESTED_NOT_GRANTED**；完整 Phase5 Android / ARM64 guest 桌面、容器、IDE、AVF、USB、SAF 全为 NOT_RUN。主仓 APK 的 installed metadata、Main quality 结果及第一会话 Linux 状态由主交接负责；不得借用为 Phase5 设备通过。
- 仅首个 Debian13 labwc 桌面 profile；KDE / niri、另外五族增强安装 profile 未实现。Podman / code-server 仅已明确的 Debian / Ubuntu 路径；Docker rootless daemon 未实现。真实 GPU renderer、zero-copy、AVF OEM 权限 / CPU topology 可运行性仍未完成；AVF IDE service forwarding 明确不支持。
- Android SecureDirectoryStream provider、9p guest mount、SAF production broker / held-FD proof、USB native 跨进程 FD / VMM passthrough / 热插拔未验或未接入。WebView WS / SW / cookie 隔离、CJK / emoji、旋转 / context-loss、LAN 到期等设备负例未运行。65MiB / 1025 files 的 staging 基准为宿主合成成本，不能声称手机存储加速。
- package / namespace 两种策略：主线合流时继承当前主仓基础 applicationId / namespace `io.github.nyzeep.orchestella` 及 **versionCode 300 / versionName 0.3.0-preview.1**，不能由 Phase5 Gradle 200 / 0.2.0 降级；若另造隔离调试包，只在显式 `orchestella.phase5.debugKeystore` 属性下保留 `.phase5` applicationIdSuffix 与 `-phase5-local` versionNameSuffix，namespace 仍为基础值。现有锁定 APK 的 200 / 0.2.0-phase5-local 不会随新源码合流改变；它不自动继承主应用实例或签名。

继任会话可复用本报告的只读 checksum / named-source / ZIP manifest 检查，先在新的隔离候选树以当前主线为基线，对照 captured before 与 canonical after 合流，并在那里做 check 与受影响回归。构建参数、static agent / repack / sync-init 入口及设备流程引用原集成指南，先核对 P: / Q: 路径与输入，不把旧映射视为当前授权。设备仍需按独占交接和 grant 条件处理；本会话不继续开发或验收。只有继任会话具备用户允许的第一方 push 范围；所有参考仓仅 read-only，无外网或宿主数据上传。

## canonical 出处与范围检查

主 handoff 只需链接本报告；原始三报告保留各自事实，不复制全文。跨仓链接使用canonical绝对路径，使字节一致的保全副本也能定位来源：

- [Phase5 实施报告](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/tickets/phase5-08/implementation-report.zh-CN.md>)：§2 实际完成、§3 产物、§4 测试、§6 未完成。
- [Phase5 验证矩阵](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/orchestration/phase5-verification-matrix.zh-CN.md>)：按功能 / 发行版 / 性能的 NOT_RUN 边界。
- [Phase5 集成指南](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_dev/docs/reports/orchestration/phase5-integration-guide.zh-CN.md>)：精确 before 来源、构建参数、独立 package 与设备 grant 流程。
- [delivery SHA256SUMS](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/delivery/SHA256SUMS>)、[最终门](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/delivery/final-delivery-gate.json>)、[历史 replay 记录](<D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/delivery/patch-forward-replay.json>)。最终门的两份 final review 摘要也与当前隔离报告 bytes 匹配，但局部 source approve 不扩大设备验收。

已读取 Phase5 AGENTS、防御三文件、Artifact / Host policy；主仓对应六文件 bytes 相同，另读取主仓 Reporting Standard。读取范围仅指定仓、交付、具名依赖 / worker 集成 / 既有执行证据；结果只写唯一获准 scoped report。无外网、无上传、无 production / 构建 / 设备 / Git 写操作；未触发新的 Architecture Change Policy。GPU / USB 等既有 ACR 与权限限制继续保留，不在本报告裁定或修改。

报告人：Codex / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未单独计时
