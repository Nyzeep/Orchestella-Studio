# 零设备产品化（T1–T7）· provision 缺口与启动器硬化落地

- 日期：2026-11-02
- 基线：main `3c24c54`（origin = github.com/Nyzeep/Orchestella-Studio）
- 上游输入：`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md`（§1 九层根因链 / §8 缺口清单）、`docs/research/2026-11-08-t7-network-closure.md`、`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`、`research/uml-runner/`、`research/uml-fabric-probe/t7/`
- 纪律：**全程零设备**。任何需真机的判据在本文件标 `not-tested` 并给下个窗口的执行入口。未裁定开发通道产品形态、未改版本号/签名/发布通道、未改 ADR 正文。

## 0. 判定总表

| 票 | 缺口（11-08 实证出处） | 落地 | 验证 |
|---|---|---|---|
| ZP-07 · T1 | 超时 SIGKILL 遗留 `db.lck` → 下轮必败，且被误报 `offline`（§1 #7） | guest 锁判定（`/proc` fd → `fuser` → 不可判定）+ 3s 二次确认 + 新 stage/reason 词表 | 最终树上 `GUEST_RUNTIME_PROVISION_TEST_OK checks=66`（含 `pacman_lock_checks` 四用例）；矩阵测试另由实现轮跑过 151 checks（见 §6 注） |
| ZP-08 · T2 | `1800s/操作` 被 92 包事务击穿（§1 #6） | 四类档位（download 900 / transaction 3600 / compute 1800 / verify 600）+ 每类环境变量覆盖 + 宿主 65→90min + 跨层一致性单测 | `:runtime:contract:check` 4/4；档位覆盖函数定向 10 例 |
| ZP-09 · T3 | stop-file watcher 双修（P1 启动期清理 / post-exit 兜底） | `InstanceStopSignal`（`runtime:instance`，纯 JVM，**不接线**） | S1–S4 契约测试 4/4，`:runtime:instance:check` |
| ZP-10 · T4 | 长驻须 FGS + wake lock（§2.2 双设备证据链） | 真实缺口四项：API34+ 显式 type、2h 上限+续期、finally 释放、开机自启不空转持锁；补通知权限申请 | `WakeLockPolicyTest` 4/4；`:app:assembleDebug` 绿 |
| ZP-11 · T5 | DrvFs staging 0777 拦停 `sshd -t`（§1 #10） | 行为探针守卫 + 打包前归一/断言 + 仓外复用脚本 | `STAGING_GUARD_TEST_OK` 9/9 |
| ZP-12 · T6 | `ncpus=` 与 16KB stub 的产品通道核对 | **结论：不做**（产品无 UML 通道，stub 不进产品构建） | 只读核对，见 §5 |
| ZP-13 · T7 | 11-08 轮方法论坑反复重犯 | `preflight_device_window.sh`（推送对账 / 启动竞速 / 排程互斥 / 凉机阈值） | `PREFLIGHT_DEVICE_WINDOW_TEST_OK` 19/19 |

## 1. Phase 0 · 边界自答与 prompt 的三处偏差

prompt 假设与仓库实际不符的三处，已按仓库事实改写（详见 spec §1）：

1. **T3「产品启动器移植 stop-file watcher」**：产品**零** stop-file 引用，停止是「控制套接字 + 有界执行器」模型——8 条停止路径（控制面 poweroff、`terminate()`、stop intent、exit watcher、启动失败清理、池注销、FGS ACTION_STOP、双车道 close）全部收敛到 `RuntimeProcessSupervisor.stop()`（`app/src/main/java/io/github/nyzeep/orchestella/RuntimeProcessSupervisor.java:120-137`），类注释明写「唯一有界执行点」。研究 APK 用文件信号是因为它没有控制面，**机制不可平移，不变量可平移**。→ 用户裁定方案 B：抽组件 + 契约测试，不接线。
2. **T4「manifest 四权限 + specialUse」**：已全部具备（`app/src/main/AndroidManifest.xml:112-119` 的 type + subtype，`:6,12,13,14` 四权限，`OrchestellaForegroundService.java:191` 的 IMPORTANCE_LOW）。范围收窄为真实缺口四项 + 通知权限申请。
3. **Phase 4 门 `assembleSdk36Debug assembleSdk28Debug`**：产品 `:app` **无 productFlavors**（只有 debug/release），`sdk36/sdk28` 是 `research/uml-runner` 的 flavor。两道门都跑了，见 §6。

## 2. 工程整体状况（结构 / 依赖 / 关键模块 / 风险）

**结构**：16 个 Gradle 模块 —— `:app`（Android 宿主）+ `:accel-extension`（无代码 payload 容器）+ 14 个纯 JVM `:runtime:*`。主源集约 7.4 万行（6.25 万 java + 1.17 万 kt），测试约 6.5 万行，**测试与主代码近 1:1**。`guest-assets/` 是独立的嵌套 Gradle 工程，产出内核 `Image` 与 `initramfs.cpio.gz`，经 `orchestella.qemu.*` 属性注入 `:app`。

**依赖**：极简。版本目录只有 5 项（AGP 8.13.2 / Kotlin 2.2.21 / JUnit 4.13.2 / Material 1.12.0 / AppCompat 1.7.0）；`runtime/*` 一律只依赖 JUnit（test-only），且统一 `-Xlint:all -Werror`（warning 即失败）。`:app` 的第三方依赖仅 6 项（desugar_jdk_libs / material / appcompat / shizuku api+provider / hiddenapibypass）。

**关键模块**：实例生命周期（`QemuInstanceLauncher` 1,024 行 → `RuntimeProcessSupervisor` 247 行 → `InstanceLaunchSequence` → `ExecutionSessionRegistry` / `InstanceLaunchGuard`）、网络面（`NetworkGatewayDaemon` 612 行，gvproxy 单客户端监听）、保活（`OrchestellaForegroundService` 234 行）、状态机（`InstanceLifecycle` 1,696 行 = 全仓最大 java）。

**有证据的潜在问题（本轮未修，按严重度排序）**：

| # | 事实 | 风险 |
|---|---|---|
| P1 | `runtime/contract/.../BridgeClient.java` 的启动超时是**单一常量**，同时兜握手/镜像校验/解包/首控事务 | 任一阶段卡死都要等满该时长才报错，用户侧表现为「无响应」。本轮已按新档位从 65min 提到 90min 并加了跨层一致性单测，但「单常量兜四段」的结构问题仍在 |
| P2 | `NetworkGatewayDaemon` 端口 `24100 + floorMod(instanceId.hashCode(), 800)`，且 `portBound` 是「先探测再 bind」（TOCTOU） | 两个实例可撞同一端口；并发启动可能双方都「探测空闲」 |
| P3 | 唯一性判断只在单进程内（`LIVE` 注册表 + `InstanceLaunchGuard` 的 `ConcurrentHashMap`） | 进程被杀后 gvproxy/QEMU 子进程与端口租约无进程外仲裁，重启靠对账「猜」——`AGENTS.md` 已列为 P0/P1 待办 |
| P4 | 通道/签名/版本逻辑在 `app/build.gradle.kts` 与 `accel-extension/build.gradle.kts` 有两份拷贝，versionCode 手工双写 | 改一处漏一处 → 扩展包以错误 applicationId/versionCode 产出，运行期只表现为 `VERSION_MISMATCH` |
| P5 | `guest-assets/shell/` 有 4 对字节相同的 `droidlayer-*` / `orchestella-*` 副本，且整个目录都会被同步进 APK | 改一处不改另一处 = 同一 guest 两种行为，无测试会发现 |
| P6 | 命名双轨仍在使用：cmdline 传 `droidlayer.root_device`，协议标记仍是 `DROIDLAYER_PROVISIONING_COMPLETE`，而 socket 名已改 `orchestella-` | 宿/guest 必须同时改；单边改名会让启动静默卡在等标记 |
| P7 | `Image` / `initramfs.cpio.gz` 被 gitignore 但运行期硬依赖（fail-closed 安装路径） | 干净 clone 的 `assembleDebug` 照样成功，失败推迟到用户首次开机；CI 靠 `-P` 提供，本地无等价门禁 |
| P8 | `runtime-artifacts-v1.sha256` 锚定的 initramfs 摘要与 CI「可复现构建」锚定的摘要**不是同一份**（大小也不同） | 仓库内被验证过的 initramfs 与今日 builder 产物已脱钩，任一侧当基座都可能让 guest 行为与证据不符 |
| P9 | 单文件过大：`MainActivity.kt` 1,993 行、`InstanceLifecycle.java` 1,696 行、`guest-assets/initramfs/init` 234KB | 回归面无法按模块界定 |
| P10 | `MainActivity.kt` import `kotlinx.coroutines` 但未声明依赖（靠 androidx 传递引入） | 依赖升级即可能编译失败或拿到未固定版本 |
| P11 | 全仓 `TODO`/`FIXME` 命中 0 | 已知缺口只能靠文档，改代码的人在触点看不到「有意为之还是漏了」 |

（完整清单与 file:line 见 `.scratch/orchestella-uml-fabric/notes/A0-project-overview.md`。）

## 3. T1 · pacman 锁恢复

**判定顺序**（`guest-assets/scripts/orchestella-guest-provision.sh` 的 `pacman_lock_holder` / `recover_pacman_lock`）：

1. 锁不存在 → 直接返回 0，**不写 state**（无锁场景不新增 stage 迁移）；
2. `$ROOT/proc/[0-9]*/fd/*` 的 readlink 命中锁路径 → 持有；
3. 否则 `fuser`（存在则采信）→ 持有；
4. `/proc` 与 `fuser` 都不可得 → **不可判定，按持有处理，禁删**；
5. 确认无持有者 → `sleep 3` 二次确认 → 仍无 → 删除。

上游依据（`[官方源码]`/`[官方文档]`，见 A4 笔记）：`db.lck` 是 `alpm_trans_init()` 用 `O_EXCL` 创建、**0 字节、无 pid**；libalpm **无 journal**，逐包落盘无原子提交，所以「锁在但库半写」是真状态；官方只要求「确定没有 pacman 在跑」再删，`--dbonly`/`-Qk` 都不是免删锁的接口。

**reason 词表修正**：

| 场景 | 旧 | 新 |
|---|---|---|
| 锁被真实持有 | `package-install` / `offline-or-package-install-failed`（误导为离线） | `package-install` / `package-lock-held` |
| 锁删不掉 | 同上 | `package-lock-recovery` / `package-lock-recovery-failed` |
| 事务中锁重现 | 同上 | `package-install` / `package-lock-held` |

新 stage `package-lock-recovery` 落 `verify` 档（600s），输出 `ORCHESTELLA_GUEST_PROVISION_LOCK_RECOVERY action=removed|held|unavailable path=…`。事务形态保持 `pacman -Syu --noconfirm --needed` 不变。

## 4. T2 · 超时分档与跨层一致性

| 类别 | 默认 | 覆盖变量 | 归属 stage |
|---|---|---|---|
| `download` | 900 | `ORCHESTELLA_PROVISION_TIMEOUT_DOWNLOAD` | repository-refresh、certificate-bootstrap-refresh/install |
| `transaction` | 3600 | `…_TRANSACTION` | package-install、package-recovery、init-repair、optional-upgrade |
| `compute` | 1800 | `…_COMPUTE` | locale |
| `verify` | 600 | `…_VERIFY` | 其余全部（含 package-lock-recovery） |

- 分类走显式 `case` 映射，**不用 stage 名子串推断**；注释只写 I/O 规模与解包开销，不出现 TCG/模拟器前提。
- 非法覆盖值（含负数、后缀、前导零）回落默认并输出 `ORCHESTELLA_PROVISION_TIMEOUT_OVERRIDE_IGNORED`，不静默。
- state 追加 `operation_timeout_class`（保持 `version=2`，`report` 的 stdout 行**不新增字段**，避免破宿主正则 `GuestRuntimeStatus.java:9-10`）。
- 外层两道墙同步：guest `TimeoutStartSec` 2h → **5400s**（模板与生成物两处）；宿主 `BridgeClient.DEFAULT_STARTUP_TIMEOUT_MS` 3.9e6 → **5.4e6**。
- **跨层一致性单测** `ProvisionTimeoutBudgetTest` 从 `user.dir` 上溯找仓库根，正则解析四档默认值 + 两道墙，断言 `宿主 > (download+transaction)×1000` 且 `TimeoutStartSec ≥ download+transaction`，解析失败直接断言失败——**任一侧漂移即红**。

## 5. T3 / T4 / T5 / T6 / T7

**T3**：`runtime/instance/.../InstanceStopSignal.java` —— `prepareRound()` / `watch(alive, onConsume)` / `completeRound()`，纯 `java.nio.file`、零后台线程、一次性消费。契约测试镜像 `stop_watcher_sim.sh` 的 S1–S4，**S2 双向**（先断言「不兜底则遗留」复现 T5 门1 根因，再断言 `completeRound()` 清理）。类 javadoc 明写「故意不接线」，避免被误认为死代码。

**T4**：四项真实缺口 —— ① `startForeground` 在 API 34+ 走三参显式 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；② wake lock 上限 2h（注释注明**本项目策略、非平台限制**，AOSP 文档无 2h 规定）+ 剩余不足 60min 时 release+re-acquire 续期；③ `startForeground` 失败时 `finally` 释放，不留悬挂锁；④ 开机自启（无 generation）只抬通知不持锁。另补 `POST_NOTIFICATIONS` 运行时申请（API 33+，已授予则跳过），只在既有 launch 入口加一行。决策逻辑抽成 `WakeLockPolicy`（纯函数，四情形单测）。

**T5**：`staging_preserves_modes()` 行为探针（`mkdir` → `chmod 0700` → `stat -c %a` 读回 `700`），返回 0/1/2。`build.sh` 两处：staging 重置后 fail-fast（非 0 即 `exit 70` + 可操作提示）；打包前目录归一 `chmod 0755` + `-perm /022` 断言（**断言才是门，chmod 在 DrvFs 上是 no-op**）。新增 `check-staging-fs.sh` 供仓外 `mkfs.ext4 -d` 配方复用，`research/uml-fabric-probe/README.md` 补「staging 必须在 WSL 原生 ext4，不得 `/mnt/*`」。
上游依据：`sshd.c` 内联判定 `st_uid != 0 || (st_mode & 022)` → fatal → `cleanup_exit(255)`；`/usr/share/empty.sshd` 是**发行版选择**，守卫不硬编码。

**T6 结论：不做**。产品侧 `ncpus` 零命中（唯一命中是 `app/src/main/assets/Image` 里的内核字符串，不是通道）；`libuml*`/`stub_exe` 不进任何产品构建（`app`/`accel-extension`/`runtime/*` 的 build 脚本零引用）。research 侧三处（`RunnerActivity` 的 Config、`prepare.sh`、`README`）口径一致，`--cpus` 只出现在「禁止使用」的说明文字里。stub 的 16KB 校验复用 `tools/ci/check-elf-16kb.sh` 同口径（产品 APK 的 `check-apk-16kb.sh` 也调它），**判据不冲突**。
触发条件：UML 车道进产品时，`ncpus` 需在 `LaunchParameters` 暴露并按「手机宿主最优宽度 = 2 vCPU」给默认值；stub 进产品时需要新增 jniLibs 条目并复用同一 16KB 门。

**T7**：`tools/device-validation/preflight_device_window.sh` 四组能力 —— `push-verify`（推送后强制设备侧 md5 对账，不一致 exit 1；大文件额外打印大小与可用空间）、`start`（`force-stop → sleep 3 → am start -W`，解析 LaunchState）、`plan`（T7-F × T8② doze 互斥提示、中停必须长驻负载、`thermal_zone*` 凉机阈值 exit 3）、`--require-long-running`（拒绝 bootprobe 这类短负载做中停，exit 4）。

## 6. 验证门证据（全部零设备）

| 门 | 命令 | 结果 |
|---|---|---|
| 产品构建 | `run-gradle-gate.sh :app:assembleDebug` | `BUILD SUCCESSFUL`，`GRADLE_GATE_EXIT=0` |
| 产品单测 | `:app:testDebugUnitTest` | 绿；新增 `WakeLockPolicyTest` 4/4 |
| JVM 门 | `:runtime:instance:check` `:runtime:contract:check` | 绿；`InstanceStopSignalContractTest` 4/4、`ProvisionTimeoutBudgetTest` 4/4 |
| 研究 runner 双 flavor | `research/uml-runner` 的 `assembleSdk36Debug assembleSdk28Debug` | `BUILD SUCCESSFUL`，72 tasks |
| 发布 staging 守卫 | `sh guest-assets/scripts/test-staging-guard.sh` | `ok=9 bad=0`，`STAGING_GUARD_TEST_OK` |
| 设备窗口 preflight | `bash tools/device-validation/preflight_device_window.sh --self-test` | `pass=19 fail=0`，`PREFLIGHT_DEVICE_WINDOW_TEST_OK` |
| 档位覆盖（定向） | 抽取 `provision_timeout_seconds_for_class` 单测 10 例 | 10/10（含 `900s`/`0900`/`0`/`-60` 拒绝并回落） |
| guest provision 驱动器 | `GUEST_RUNTIME_TEST_ROOT=<外部目录> python guest-assets/scripts/test-guest-runtime-provision.py` | `GUEST_RUNTIME_PROVISION_TEST_OK checks=66`，`PROV_RC=0`（**最终树**，含 `pacman_lock_checks` 四用例） |
| guest init 同步 | `sync-guest-init.py --check` | `GUEST_INIT_TEMPLATE_SYNC_OK` |
| CI 接线 | `check_ci_module_coverage.py` | `230 passed, 0 failed`（较基线 +1，新 JavaExec） |
| 敏感数据 | `check_sensitive_data.py --self-test` | `SENSITIVE_DATA_OK` |
| README 资产 | `check_readme_assets.py` | `README_ASSETS_OK 2 README file(s)` |

探测副本 `research/uml-fabric-probe/t7/orchestella-guest-provision.pacman-probe.sh` 与产品脚本 `diff` 归零（保持探针与产品同形）。

**关于 7 发行版矩阵驱动器（`test-distro-runtime-matrix.py`）的诚实说明**：实现轮的落盘证据是 `DISTRO_RUNTIME_MATRIX_TEST_OK distros=7 checks=151`（含锁恢复 A/B/C/D 与档位覆盖用例）。主会话在最终树上重跑时，本机（Git Bash / MSYS）每发行版需 7–10 分钟且全程无中间输出，完整环超过 40 分钟，两轮均在跑完前终止——**该轮最终树未取到矩阵全绿**。补偿措施：① 最终树跑通了 `test-guest-runtime-provision.py`（66 checks，末尾即 `pacman_lock_checks`）；② 主会话对自己那 4 行改动用抽取函数做了 10 例定向验证；③ 其余改动（`init` 生成物、探针副本）由 `--check` 与 `diff` 归零覆盖。复现命令：`GUEST_RUNTIME_TEST_ROOT=<目录> python guest-assets/scripts/test-distro-runtime-matrix.py`，建议在原生 Linux 或 WSL 内跑。

## 7. 未验证项与下个窗口入口

全部为 `not-tested`（无设备）：

1. **锁恢复真机路径**：需在 guest 内制造「pacman 被 SIGKILL 留锁」后再 boot，确认 `action=removed` + 事务跑通。入口：设备窗口按 round-2 §1 的 attempt 流程，人为 `timeout 5 pacman -Syu` 制造超时。
2. **持有锁不误删**：需在 guest 内 `pacman -Syu` 进行中并发触发第二次 provision，确认 `packages-lock-held` 且锁仍在。入口：`pacman &` 后立刻跑 `orchestella-guest-provision --native`。
3. **超时档位在真机生效**：`adb shell` 内 `export ORCHESTELLA_PROVISION_TIMEOUT_TRANSACTION=…` 后跑 provision，查 state 的 `operation_timeout_class` / `operation_timeout_seconds`。
4. **API 34+ FGS 显式 type**：`dumpsys activity services …OrchestellaForegroundService` 应显示 `isForeground=true` 且 `types=` 含 specialUse，无 `MissingForegroundServiceTypeException`。
5. **2h 续期**：首次 acquire 后 61min 再触发一次 start，`dumpsys power` 的 `mHeldTime` 应重置而非新增一把锁。
6. **开机自启不空转持锁**：开自启后重启，`dumpsys power` 应无该 wakelock，仅通知栏有提示。
7. **通知权限**：全新安装首启应弹权限框；拒绝后 FGS 仍运行但通知不可见（预期）。
8. **产品包 60min 熄屏驻留**：研究 APK 的 `frz=0×12` 不能直接外推到不同 uid/包名/通知渠道的产品包，需按 `research/uml-runner/README.md` 的 `cgroup.freeze` 采样复跑一次。
9. **staging 守卫在真 DrvFs 上触发**：WSL 内对 `/mnt/d/<空目录>` 跑 `check-staging-fs.sh`，期望 `1`。

## 8. 回填

- 工单：`.scratch/orchestella-uml-fabric/issues/07..13`（07–11 实现、12 结论、13 脚本），Comments 已写验证证据。
- 地图：`.scratch/orchestella-uml-fabric/map.md` 的 Decisions so far 追加本轮条目。
- 规格：`.scratch/orchestella-uml-fabric/spec-zero-device-productization.md`。
- 侦查笔记：`.scratch/orchestella-uml-fabric/notes/A0..A5-*.md`（A0 工程总览、A1 provision 链路、A2 启动器 diff、A3 staging/上游、A4 锁与 FGS 上游、A5 测试与 CI）。**这 6 份是本地工作产物，未入库**：它们含本机绝对路径，敏感开发者数据门禁不允许进版本库；需要时可从本目录按同样方法重生成。
