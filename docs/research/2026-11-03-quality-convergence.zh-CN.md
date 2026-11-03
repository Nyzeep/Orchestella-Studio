# 零设备质量收敛（Q1–Q5）· 让失败可解释

- 日期：2026-11-03
- 基线：main `24aef0e`（origin = github.com/Nyzeep/Orchestella-Studio）
- 上游输入：`docs/research/2026-11-02-zero-device-productization.zh-CN.md`（上一轮判定总表 / §2 风险表 / §7 九条真机待验）、`../工程质量优化报告-20261027.md` §6（P1-6、P1-7、P2-9）、`工程质量优化报告-20261027-补充卷.md` §6
- 纪律：**全程零设备**。任何需真机的判据在本文件标 `not-tested` 并给下个窗口的执行入口。未裁定开发通道产品形态（ACR 未投票）、未引车道相关产品行为、未改版本号/签名/发布通道/ADR 正文、未拆分巨型文件。

## 0. 判定总表

| 票 | 缺口 | 落地 | 验证 |
|---|---|---|---|
| Q1 | 干净 clone 的 `assembleDebug` 缺件仍 `BUILD SUCCESSFUL`，失败推迟到用户首次开机 | `app/build.gradle.kts` 新增 `verifyRuntimeAssets`（三件载荷 + 摘要比对）+ 挂 `preBuild` + 两条显式豁免 + CI 三处豁免 | **反证**：移走 `Image` → `verifyRuntimeAssets FAILED` 并点名缺件；放回 → `BUILD SUCCESSFUL` |
| Q2 | 三处吞异常（accept 忙旋 / relay 与 handleClient / `proxy_relay` 静默降级） | 新增 `PortForwardingObserver`（全 default + NOOP）+ 1s 节流 + 指数退避 + 连续 10 次 fatal；`proxy_relay` 区分「缺失」与「读失败」，写 `LaunchTimeline.diagnostics` | `SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK`；既有 8 个 forwarding scenario 原样绿 |
| Q3 | 孤儿网关只报 `IOException`，无判定、无提示、无回收路径 | `GatewaySlotArbiter` 深模块（四态 verdict + 三个派生函数）+ 纯 JVM 契约测试，**零接线** | `GATEWAY_SLOT_ARBITER_TEST_OK`；`git status --porcelain app/` 为空 |
| Q4 | `kotlinx.coroutines` 靠 androidx 传递引入；工具链版本散落 | 版本目录补 6 组 + `app`/`accel-extension` 全部改 catalog + 删重复 `androidTestImplementation` | `:app:assembleDebug` / `:app:testDebugUnitTest` / `:accel-extension:assembleRelease` / `:app:compileDebugAndroidTestJavaWithJavac` 四绿 |
| Q5 | 6 张已交付的票仍是 `ready-for-agent` 且零 Comments | p0-2 `01..06` 逐条核对后 `resolved` + Comments 带 file:line 与实跑输出；新建 Q1–Q4 四票 | 每张被动过的票都有代码证据支撑的状态 |

## 1. Phase 0 · 边界自答与 prompt 的两处事实修正

prompt 假设与仓库实际不符的两处，已按仓库事实改写（详见 `.scratch/quality-round-20261103/spec.md` §1）：

1. **Q1「`Image` 与 `initramfs.cpio.gz`」隐含两者都在 `jniLibs/`**：实际 `Image` 在 `app/src/main/assets/Image`（52,070,912 B，走 `aaptOptions.noCompress`），`initramfs.cpio.gz` 在 `app/src/main/resources/initramfs.cpio.gz`（1,267,115 B，走 java resource——aapt 会改 `.gz` 的名字），只有 `libdroidlayer_qemu.so` 在 `jniLibs/arm64-v8a/`。原因写在 `app/build.gradle.kts:4-11`。门禁按真实路径校验。
2. **Q2「端口转发失败用户只看到连不上」隐含 `SocketPortForwarder` 在生产链路上**：生产端口转发走 gvproxy + `NetworkGatewayDaemon.ForwardingEngine`；`SocketPortForwarder` **生产零引用**，是 QEMU profile 的契约参考实现（仅 `PortForwardingManagerContractTest` 构造，经 `testJar`/`testArtifacts` 发布给 `engine-avf`）。因此本轮改它**对外零爆炸半径**，但也必须如实记：这一处静默失败当前不直接产生用户可见症状。Q2 里**唯一用户可感**的是 `proxy_relay` 静默降级——它让 guest 完全没有出站代理，APT 静默不通。

另两条事实直接约束实现，已核实：`QemuRuntimeBootstrapper` 是纯 JVM 类（只 import `java.*` 与 `runtime.*`），引 `android.util.Log` 会让 23 个既有测试全红，故诊断走注入式 `Consumer<String>`；`runtime/network` 与 `runtime/instance` 的 `test` 任务 `exclude("**/*")`，新增测试不挂 `JavaExec` + `check` 就永远不跑。

## 2. Q1 · 构建期缺件 fail-fast

**新增任务**：`app/build.gradle.kts:551-556` 读 `-Porchestella.assets.allowMissingPayload`；`:558` 注册 `verifyRuntimeAssets`（`group = "verification"`）；`:846` `tasks.named("preBuild") { dependsOn(verifyRuntimeAssets) }`。

**判据只有三条**（按名字查表，不按行号——清单第三行是 `QEMU-BUILD-INPUTS`，ELF 在最后一行）：

| 逻辑名 | 文件 |
|---|---|
| `Image` | `app/src/main/assets/Image` |
| `initramfs.cpio.gz` | `app/src/main/resources/initramfs.cpio.gz` |
| `lib/arm64-v8a/libdroidlayer_qemu.so` | `app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so` |

逐件断言「存在 + 非空 + SHA-256 == `app/src/main/assets/runtime-artifacts-v1.sha256` 同名条目」。摘要流式算（`.so` 132MB，不整文件读内存）。

**为什么必须做摘要比对而不只是存在性**：`RuntimeAssetInstaller.install` 在安装时会用清单校验磁盘字节（`runtime/engine-qemu/.../RuntimeAssetInstaller.java:27-70`），摘要不符的载荷**必然**在用户开机的第一步失败。构建期拦下它，等价于把一个百分百会发生的运行期失败提前到编译期。

**为什么不校验清单全部 7 项**：`NOTICE.QEMU` 与 `QEMU-GPL-SOURCES.md` 自 `e16af2c`（「对齐 GPL/NOTICE 与 guest/QEMU 构建输入」）起已与清单漂移（实测 `08b7abc7…` vs `50b31a07…`、`7d134410…` vs `3b07f2d5…`）。全量校验会让今天所有开发机直接红。**未重新生成清单**——那属「改产物」，需产品裁决。

**两条豁免，都必须显式且留痕**：

1. **外部载荷模式**：任一 `orchestella.qemu.*` **或** `droidlayer.qemu.*` 非空 → 只断言三个根目录存在且非空，跳过文件与摘要校验（`:583-616`）。必须识别两个前缀，因为 `qemu-android.yml:372-374` 用的是遗留 `droidlayer` 前缀——只识别新前缀会把唯一的满载 CI 路径误判为缺件。
2. **干净 checkout 豁免**：`-Porchestella.assets.allowMissingPayload=true` → `logger.warn` + 写 `buildDir/runtime-assets-missing.flag`（含时间戳与原因）+ 跳过（`:620-638`）；通过时删除陈旧标记（`:712`），避免上一次豁免的残留把本次构建误报成无载荷。

**反证（本轮最重要的证据形态）**。把 `app/src/main/assets/Image` 移走后跑 `./gradlew :app:assembleDebug`：

```
> Task :app:verifyRuntimeAssets FAILED
verifyRuntimeAssets: The QEMU runtime payload in this checkout is incomplete or does not match app/src/main/assets/runtime-artifacts-v1.sha256.
verifyRuntimeAssets:   - missing files: Image (app/src/main/assets/Image)
verifyRuntimeAssets:   - local build: build the guest assets with guest-assets/run-in-builder.sh (produces Image and initramfs.cpio.gz) ...
verifyRuntimeAssets:   - CI: pass an external payload with -Porchestella.qemu.nativeLibDir/assetsDir/initramfsDir ...
verifyRuntimeAssets:   - intentional absence: rerun with -Porchestella.assets.allowMissingPayload=true ...
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:verifyRuntimeAssets'.
BUILD FAILED in 40s
```

放回后：`verifyRuntimeAssets: Image, initramfs.cpio.gz and lib/arm64-v8a/libdroidlayer_qemu.so match app/src/main/assets/runtime-artifacts-v1.sha256.` / `BUILD SUCCESSFUL in 44s`。失败发生在 `syncGuestShellAssets`/`syncGuestInitAndRelay` 之后、任何编译与打包之前（`3 actionable tasks: 1 executed, 2 up-to-date`），符合「装配最前端」的要求。

**CI 调用面**（不改则 PR 门禁红）：`ci.yml:197`、`release.yml:139`、`:145` 三处干净 checkout 构建加豁免属性并注释说明。这三处本来就是**无载荷基线**——`release.yml` 的 `publish-draft` 用 `check_runtime_payload.py --apk` 在真发布前拒绝无载荷 bundle（`release.yml:255-266`，session 26 的精讲注释仍在）。所以本轮没有放松任何真实门禁，只是把「构建期」和「发布期」的分工写清楚了。

**必须记住的文档事实：三组锚点不是同一批字节，不可互相套用。**

| 锚点 | initramfs 摘要/大小 | 性质 |
|---|---|---|
| 入库清单 `app/src/main/assets/runtime-artifacts-v1.sha256` | `c96fe6e6…` / 1,267,115 B | 某次组装后的成品（本门禁的唯一判据） |
| `qemu-android.yml:205-208`（GA base） | `92766a6f…` / 1,091,309 B | consumer 之前的基线（差 175,806 B） |
| `tools/qemu-android/ci/apk-postbuild-expectations-v1.json` | `f8db1b52…` | 又一次构建的记录（APK 60,148,288 B） |

`Image` 的摘要在三处恰好相同，属巧合而非不变量——**不能**把 GA base 的摘要写死成缺件判据。

## 3. Q2 · 三处静默失败收口

**原则**：保持对外契约不变（`PortForwardingEngine` 不加抽象方法、`PortForwardingRule` 一行不改、`QemuRuntimeBootstrapper` 既有 `bootstrap(...)` 重载签名不动），改为「节流诊断 + 可注入观察者」。

**新增 `PortForwardingObserver`**：5 个事件（accept 失败含 `fatal`、客户端失败、relay 失败、listener 被替换、关闭汇总），**每个方法都有 `default {}` 空实现**，对齐同模块 `download/DownloadProgressCallback:33/42` 的哲学——老实现者零改动。提供 `NOOP`。

**逐处处置**：

| 位置 | 现状 | 处置 |
|---|---|---|
| accept `IOException` | 静默忙旋（100% CPU、无退避、永不自愈） | 节流记录 + 50→1000ms 指数退避 + 连续 ≥10 次记 `fatal` 并关 listener 退出循环 |
| accept `SocketException` | 静默 break | **保持静默**（AC-2 热移除的正常路径，加日志只会污染） |
| `handleClient` `catch (Exception)` | 全吞 | 收窄 `IOException`（节流记录 + 继续）；`RuntimeException` 记录后**重抛**（代码 bug 不该被当成网络错误） |
| `relay` `catch (IOException)` | 全吞，且 `static` 无上下文 | 改实例方法带方向；本端已关闭或对端干净 EOF 静默，其余节流记录 |
| `ActiveListener.close()` | 吞 | 保持不抛（`Closeable` 契约要尽力而为），注释写清为何丢弃 |
| `addRule` 同 id | 旧 `ServerSocket` 既不关闭也不退出接受循环（静默 fd 泄漏） | 幂等替换：先关旧 listener 再绑定 + `onListenerReplaced` |
| `proxy_relay` | `catch (Exception ignored)` 后传 `null` | `null` → 记 `proxy-relay-absent` 继续；异常 → 记 `proxy-relay-unreadable` 并抛 `proxy-relay-unavailable` |

**为什么 `proxy_relay` 的 `null` 必须保留容忍**：23 个既有测试的 fixture 对未知资源恒返回 `null`（`QemuRuntimeBootstrapperTest`、`QemuRuntimeBootstrapperProvisionedTest`），把 `null` 改成抛异常会让 22 个测试立刻全红。而区分「缺失」与「读失败」才是真正有价值的那一刀：前者是「这次构建没打进去」（可容忍，但必须留痕），后者是「打进去了但读不出来」（打包损坏，必须失败）。

**节流**：key = `ruleId|eventKind|causeSimpleName`，1s 窗口，首条必出，`close()` 时一条 `onSummary` 报「抑制了 N 条」。禁止按连接或按 4KB 打点——`relay` 是每 4KB 一次，无节流会刷爆 logcat。

**绝缘**：所有回调走统一 `notify(Runnable)`，内部吞 `RuntimeException` 并注释——这是全类唯一允许保留的空 catch，因为一个写坏的观察者不能把转发打死。

**测试证据**：`SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK`（新增 5 用例，双入口：观察者抛异常不影响转发 / guest 端口未监听至少 1 次 `onClientFailure` / 同 key 连续失败被节流且首条必出 / 同 id 重复 `addRule` 不泄漏监听口 / `close()` 发汇总）。既有 `PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK`（8 scenario）与 `BRIDGE_PROXY_GATEWAY_CONTRACT_TEST_OK` 保持绿。

## 4. Q3 · 孤儿网关启动期判定（设计 + 纯 JVM 组件，零接线）

**现状为什么不可解释**：`ensureGatewaySlotAvailable`（`NetworkGatewayDaemon.java:289-295`）、`portAssignedToAnotherInstance`（`:297-309`）全查进程内静态表 `LIVE`；SIGKILL 后表随进程消失，`LIVE.get` 返回 null → **静默通过**。`portBound`（`:602-607`）是全仓唯一进程外证据，但只返回 `boolean`——**没有任何身份信息**。三个抛点都在 `ProcessBuilder`（`:218`）之前的 `try` 之外，不进任何清理逻辑；最终用户看到 `MainActivity.kt:1883` 的「启动失败：IOException」，无端口号、无实例、无处置入口。

**端口是唯一想用的端口**：`24100 + floorMod(instanceId.hashCode(), 800)`（`:609-611`）由 JLS 固定，跨进程/重启/版本稳定。同一 instanceId **永远**同一端口，没有「换个端口绕开」的产品空间。

**判定设计（三段）**：

1. **判定** —— 四态，顺序严格：
   - `processTableReadable == false` → `EVIDENCE_UNAVAILABLE`（探测没跑通，**不得**退化为 `FREE`）
   - `!portBound` → `FREE`
   - 进程表里存在 cmdline 含 `net-<instanceId>.yaml` 的进程 → `ORPHAN_OF_THIS_INSTANCE`
   - 否则 → `HELD_BY_UNKNOWN`（**禁止**回收）
   身份锚点只有两个稳定串：配置文件 `net-<id>.yaml`（`NetworkGatewayDaemon.java:212` 生成、`:218-219` 逐字进 argv）、API socket 目录 `Integer.toUnsignedString(hashCode, 16)`（`:192`）。**端口号永不是身份**——把 `portBound` 升级成身份是本议题最容易犯的错。
2. **提示** —— `describe(instanceId, verdict)` 返回四种稳定短句（不含绝对路径、不含 PID），未来可映射到 `R.string.launch_failed` 的 `%1$s`，不改 strings 键。
3. **可选回收** —— **留设备窗口**，三条硬理由：跨进程没有 `java.lang.Process` 句柄，只能 `Os.kill`/`ProcessHandle`，而 `app/src/main` 目前零先例；误杀另一实例的 gvproxy 会让该实例 QEMU 因 netdev socket 断开而崩（`research/uml-fabric-probe/README.md:24` 记录该失败致命且无重试）；回收确认链（kill → 重新探测 → 仍占用则 `UNKNOWN`）只能在真机验。

**为什么本轮零接线**：工单明确要求零接线（B3 曾建议至少把判定接进 `:206-208` 的错误文案，取舍记录在 spec）。代价是用户可见症状本轮不改善——这一点必须诚实：Q3 交付的是**可验证的判定能力**，不是症状修复。类 javadoc 逐字对齐 `InstanceStopSignal:37-41` 的 Intentionally-not-wired 措辞并明写「not dead code」。

**设备窗口执行入口**：① 在 device-a 上启动实例后 `kill -9` 宿主进程，确认 `gvproxy` 存活且端口仍 LISTEN；② `cat /proc/<gvproxy-pid>/cmdline` 确认含 `net-<instanceId>.yaml`（同时验证 hidepid/SELinux 可读性）；③ 重进 App 启动同实例，记录现状报错文案；④ 若 ②可读，再评估 `Os.kill` 是否被 seccomp/SELinux 放行，据此决定回收是否可行。

## 5. Q4 · 依赖与版本目录显式化

`gradle/libs.versions.toml` 从 5 项扩到 11 项（新增 `coroutines`/`shizuku`/`hiddenapibypass`/`desugar`/`buildtools`/`ndk` + 六条 library）。`app/build.gradle.kts` 的 `buildToolsVersion`、`ndkVersion`、`coreLibraryDesugaring`、shizuku ×2、hiddenapibypass 全部改 catalog；zipalign 查找路径改用同一 `libs.versions.buildtools.get()`（`36.1.0` 原本在**同一文件出现两次**，只改一处就会与 zipalign 路径不一致）；`accel-extension:168` 同步。

**coroutines 钉 1.6.4 而不是升级**：`:app:dependencyInsight` 实测 core/core-jvm/android/bom 四件套均为 1.6.4，唯一引入源是 `androidx.lifecycle:lifecycle-common:2.6.2` 的 POM（链：appcompat 1.7.0 → savedstate 1.2.1 → lifecycle-common 2.6.2）。钉住当前版本 = 行为零变化；升级会变更行为，且本机缓存里 `-android` 只有 1.6.4，离线不可复现。

**必须同时声明 `-android`**：`MainActivity.kt:1405,1409` 用了 `Dispatchers.Main`，其实现由 `kotlinx-coroutines-android` 通过 `META-INF/services/MainDispatcherFactory` 注入。只声明 `-core` 能编译通过，但运行到那两行会抛 `IllegalStateException: Module with the Main dispatcher had failed to initialize`。这是「显式化」最容易漏的一刀。

**删重复的 `androidTestImplementation`** 时补跑了一条常规构建覆盖不到的验证：`assembleDebug` 不编译 androidTest 源码，故额外跑 `:app:compileDebugAndroidTestJavaWithJavac`，确认 `FastLaneDv01Probe` 对 `runtime.fastlane.terminal.*` 的 import 仍可见（靠 `implementation` 传播）。

**没动的三处，各有理由**：`:190` 的 `cmake { version = "3.22.1" }`（catalog 无 CMake 版本概念）；`:491` 的 `androidTestImplementation(engine-fastlane)`（属传递冗余而非逐字重复，删它属顺手改）；`applicationId`/`versionCode`/`versionName`（`tools/release/check_release_fields.py:38-58` 用正则抓这些字面量，catalog 化会让它 `ValueError`）。

## 6. Q5 · Tracker 对账

**p0-2 六票全部回填为 `resolved`**，每张补上此前缺失的 `## Comments`（「零 Comments」正是本轮问题的根因：状态没回写，不是活没干）。逐票结论与关键证据：

| 票 | 判定 | 关键证据 |
|---|---|---|
| 01 T1 | 已实现 | `QemuProcessLauncher.java:18-35` 六方法、`:62-97` 真实映射；**`QemuProcessHandleSeamTest` 实测 `tests="2" failures="0"`**——该用例虽未列入 `build.gradle.kts:98-110` 的 `dependsOn`，但内建 `test` 任务确实执行了它，此前唯一疑点关闭 |
| 02 T2 | 已实现 | `:312-319` 停止顺序、`:88-93` `LIVE.remove(id,this)` 幂等、`:289-295` 槽位、`:206-208` 端口；SIGTERM 覆盖仅 POSIX |
| 03 T3 | 已实现 | supervisor `:26`/`:55-57`/`:97-149`；`Session` `:161`/`:289-293`/`:355-357`；watcher `:738-747` 不再直接 stop gateway |
| 04 T4 | 已实现（措辞级偏差） | `InstanceLaunchSequence:59-72`/`:88-95`/`:35`；`MainActivity.kt:1827-1828`；60s 文案实际在 `InstanceDetailActivity.kt:860-861`，`InstanceDetailActionRules` 只出 `TIMED_OUT` 决策 |
| 05 T5 | 已实现（一项遗留） | `:397-425`/`:444-464`/`:521-551`/`:599-606`；遗留「启动失败后 LIVE 无该 id」缺直接断言，静态上 `LIVE.put` 只在成功路径 `:254` |
| 06 T6 | 已实现 | 报告/ADR-0020/CONTEXT/两处 README 齐备；本轮补跑四条回归 + `git diff --check` |

**新建 Q1–Q4 四张票**在 `.scratch/quality-round-20261103/issues/`，沿用 p0-2 范式并显式带 `## Comments`。

**fastlane 侧只记录不动**：`P13`（`DualLaneCoordinator.java:224-229` 的 `ACCEL_LOST` 只置 `accelState = LOST` 就 return，**不释放 `accelReservation`、不置 null**；对照启动失败路径 `:180-188` 已正确释放）是全仓唯一「票面与代码一致」的真未完成项。它属车道行为，本轮主题不含车道，故只留坐标与影响，留给后续轮次。P03/P09/P11 的「报告路径已归档成死链」属文档债，同样只记录。

## 7. 验证门证据（全部零设备）

| 门 | 命令 | 结果 |
|---|---|---|
| 产品构建 | `./gradlew :app:assembleDebug --offline` | `BUILD SUCCESSFUL`；`verifyRuntimeAssets` 通过并打印三件匹配 |
| **反证** | 移走 `Image` 后同命令 | `verifyRuntimeAssets FAILED` + `BUILD FAILED in 40s`，错误点名 `Image` 并给三条取件指引 |
| 反证复原 | 放回 `Image` 后同命令 | `BUILD SUCCESSFUL in 44s` |
| 显式豁免 | `-Porchestella.assets.allowMissingPayload=true` | `BUILD SUCCESSFUL` + WARNING + `runtime-assets-missing.flag` 内容正确 |
| 产品单测 | `./gradlew :app:testDebugUnitTest --offline` | `BUILD SUCCESSFUL` |
| 网络模块门 | `./gradlew :runtime:network:check` | `SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK` + 既有三套契约 OK |
| 实例模块门 | `./gradlew :runtime:instance:check` | `GATEWAY_SLOT_ARBITER_TEST_OK` + `INSTANCE_STOP_SIGNAL_TEST_OK` 等全绿 |
| QEMU 引擎门 | `./gradlew :runtime:engine-qemu:check` | `BUILD SUCCESSFUL in 1m 6s`；`QemuProcessHandleSeamTest` 2/2 |
| 扩展包构建 | `./gradlew :accel-extension:assembleRelease` | `BUILD SUCCESSFUL in 59s` |
| androidTest 编译 | `./gradlew :app:compileDebugAndroidTestJavaWithJavac` | `BUILD SUCCESSFUL in 1m 8s` |
| 发布字段解析 | `python tools/release/check_release_fields.py` | `RESULT=PASS` |
| 空白检查 | `git diff --check` | 退出码 0 |
| 敏感数据门（自检） | `python tools/ci/check_sensitive_data.py --self-test` | `SENSITIVE_DATA_OK` |

所有 Gradle 门均在 `X:`（ASCII 路径）上跑，避开中文路径的 `ClassNotFoundException`；全部 `--offline`。

## 8. 未验证项与下个窗口入口

全部 `not-tested`（无设备）：

1. **孤儿网关判定在真机的输入可得性**：`cat /proc/<gvproxy-pid>/cmdline` 是否可读（hidepid、SELinux app 域、Android 13+ 对同 uid 子进程的收紧）。不通过则 `ProcessTable` 只能退化到 `EVIDENCE_UNAVAILABLE`，Q3 永远给不出 `ORPHAN_OF_THIS_INSTANCE`。入口见 §4 设备窗口四步。
2. **`Os.kill`/`ProcessHandle` 是否被 seccomp/SELinux 放行**：决定回收动作能否实现；两条都不行则只能「提示用户手动强停」。
3. **observer 事件是否进 logcat**：`SocketPortForwarder` 无生产调用者，JUL 与 `android.util.Log` 也不互通。若将来把它接进生产，需要在 launcher 侧做 JUL→Log 桥接。
4. **CI 三处豁免后的真实行为**：本地无 GitHub Actions，`ci.yml`/`release.yml` 的豁免分支只做了静态审查，未实跑。
5. **`proxy_relay` 降级的可观测性**：`launch-timeline.jsonl` 的 `diagnostics[]` 已有 `proxy-relay-absent`，但需要设备窗口确认该文件确实可读、且用户侧有入口看到它。

## 9. 回填

- 规格：`.scratch/quality-round-20261103/spec.md`
- 工单：`.scratch/quality-round-20261103/issues/01..04`（Q1–Q4，均 `resolved` 且带 Comments）；`.scratch/p0-2-runtime-supervisor/issues/01..06` 全部 `resolved` + Comments
- 侦查笔记：`.scratch/quality-round-20261103/notes/B1..B5-*.md`（B1 装配链、B2 转发契约、B3 网关槽位、B4 依赖迁移面、B5 tracker 真伪）。**这 5 份是本地工作产物，未入库**：含本机绝对路径，敏感开发者数据门禁不允许进版本库；需要时可按同样方法重生成。
