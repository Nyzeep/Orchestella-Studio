# 会话 19 报告：双包闭环、不透明度全局化与体验收口

> 设备别名 `qa-alt-01`（HONOR 25102RKBEC / arm64-v8a / API 36），实例 `7baaf0da`。
> 基线 `main = ac7fac5`（PR #52 双包架构），工作分支 `feat/session19-experience-20261028`，提交 `b0da2bb`。
> 本轮性质：优化轮（双包 E2E 证据链 + 生效面修正 + 动画基线）。不做正式发布，不递增版本号。

## 1. 结论

**一句话：双包正例链的安装与识别环节真正闭合了，但「实例运行时走 pVM 编译」仍被两个平台级事实挡住，证据已拿全；不透明度全局化与滑杆语义在代码与像素两个层面都已验证生效；转场/动画统一后性能指标无可测量改善（p90 −1 ms），价值在视觉一致性。**

逐项裁决：

| 任务 | 裁决 | 一句话依据 |
| --- | --- | --- |
| T-A ① 加速卡翻转 | **通过** | 扩展包安装后设置页卡片由「未安装 pVM 加速扩展」变为「pVM 编译加速：已启用（车道可用，实例启动时生效）」。 |
| T-A ② 首页徽章 | **通过（现状文案）** | CONTROL MATRIX 行 pVM 徽章「pVM 编译加速 · 就绪」；实例卡徽章为 `BadgeCopy` 的 facts 派生文案（pVM 未 armed 时回退「QEMU 完整内核」）。 |
| T-A ③ 实例启动 + 终端 | **部分通过** | QEMU 车道 `lane_started QEMU STARTED`，guest 进入终端并完成首启装配；「实编一轮 C 且远端走 pVM」**未发生**：pVM 车道在 92–385 ms 内 `lane_lost ACCEL PAYLOAD_LOAD_FAILED`，编译只能本地完成。 |
| T-A ④ setApkPath 跨包实证 | **失败，原因已锁定** | 两段证据：① 扩展包 v2-only 签名 → 平台拒绝（`No APK Signature Scheme block … ID: 4031998144`）；② 补 v3 后转为 `Failed to open APK <- ENOENT`，指向旧 VM 配置里已失效的扩展包路径（`configMatches` 不含 apkPath）。修复已提交，但**复测仍复现**：平台侧 `getApkPath` 不可反射读取，漂移检测走不到自愈分支。 |
| T-B 不透明度全局化 | **通过** | 下限 55，token 已覆盖 XML 卡片/键帽/抽屉行/顶栏/手动仪表盘；同页 55% ↔ 100% 像素对比 dLuma=+4.0（顶栏、卡片）；1055 个 JVM 用例全绿。 |
| T-C 滑杆语义 | **通过（文案层）** | 两个滑杆已改名并各带一行职责说明；遮罩生效链审计到 `ModernTerminalView` 的 layer[1]，无图时按设计不显示。 |
| T-D 动画流畅度 | **如实标注** | before 638 帧 jank 3.45% / p90 14 ms；after 670 帧 jank 3.28% / p90 13 ms。差异在噪声内，**不主张性能收益**；保留的是全局统一的 Material Motion 转场词表。 |
| T-E 择优收口 | **通过（编译验证）** | 终端抽屉遮罩由主题终端背景色派生；抽屉视觉复验未做（实例需重新装配）。 |

## 2. 仓库状态

| 项 | 值 |
| --- | --- |
| `main`（= `origin/main`） | `ac7fac5`（PR #52 双包架构） |
| 工作分支 | `feat/session19-experience-20261028`（从 `main` 切出） |
| 本轮提交 | `b0da2bb`（1 个提交，21 个文件） |
| 未提交改动 | `app/src/main/assets/runtime-artifacts-v1.sha256`（构建副产物，已还原） |
| 未跟踪 | `evidence/`（设备截图，按策略不入库） |
| 产品代码 | 已改（见 §3） |
| `local.properties` | 本地修正 SDK 指向（原挂载点失效；主机绝对路径按策略不入库） |

## 3. 改动清单

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `accel-extension/build.gradle.kts` | 构建契约 | 扩展包 debug 签名启用 v3/v4：AVF payload loader 读 v3 块生成 idsig。 |
| `runtime/engine-fastlane/…/ReflectiveFastLaneApi.java` | 产品修复 | `configMatches` 纳入 `getApkPath`；配置漂移时 stop→delete→重建 VM，不再永久报「explicit delete is required」。 |
| `app/…/PvmPayloadLaneStarter.java` | 可诊断性 | lane 启动失败日志带 `reason` + 平台 `cause` 链（固定码不变，路径不入日志）。 |
| `app/…/ui/theme/SurfaceOpacity.kt` | T-B | 下限 55；`resolveSurfaceColor` 统一「纯色面走 WCAG 回退、有背景图跳过」两分支。 |
| `app/…/ui/theme/ThemeApplier.kt` | T-B | `recolorBackground` 的 SURFACE role 走 `surfaceFill`；`walk` 携带 `surfacePct/hasBackdrop`。 |
| `app/…/MainActivity.kt` | T-B + T-D | `surfaceColor()` 覆盖图标按钮/状态卡/distro 卡/横幅/实例卡；`surfaceOpacityListener` 即时重绘；按压 100/150 ms。 |
| `app/…/MobileAuxiliaryKeyBar.java` | T-B | 键帽跟随键条 surface token。 |
| `app/…/terminal/TerminalDrawerPanel.java` | T-B | 抽屉面板与选中行都走同一 token。 |
| `app/…/TerminalActivity.java` | T-E | 抽屉遮罩 `0x88000000` → 主题终端背景色派生。 |
| `app/src/main/res/layout/activity_settings.xml` | T-C | 两个滑杆改名 + 职责说明一行。 |
| `app/src/main/res/values/themes.xml` + `res/anim/oe_*` | T-D | 全局 `windowAnimationStyle`：emphasized decelerate 210 ms + 4% 滑入。 |
| `app/src/test/…/SurfaceOpacityTest.kt` | 测试 | 55 边界、alpha 换算、WCAG 回退、背景图跳过回退。 |
| `tools/device-validation/frame_stats.py` | 工具 | `gfxinfo framestats` → p50/p90/p99/jank 的离线解析，可复用于后续轮次。 |

## 4. 证据

全部落在 `evidence/optimize-20261028/`（设备截图，不入库）。

### 4.1 T-A 双包链

| 步骤 | 文件 | 读数 |
| --- | --- | --- |
| 首页基线 | `ta-launch-home.png` | pVM 徽章先「就绪」，实例 INTERRUPTED |
| 设置页加速卡（未装/已装） | `ta-settings-top.png`、`settings-sliders.png` | 「未安装 pVM 加速扩展」→「pVM 编译加速：已启用（车道可用，实例启动时生效）」 |
| 首页徽章 | `ta-home-running.png`、`tb-matrix-100-instance.png` | 实例卡「运行中 · QEMU 完整内核…」；CONTROL MATRIX「pVM 编译加速 · 就绪」 |
| 实例启动 + 终端 | `ta-terminal-progress.png`、`ta-terminal-ready.png` | `lane_started QEMU STARTED 43 s`；guest `ORCHESTELLA_GUEST_ASSEMBLY phase=unpack` 装配 |
| 跨包装载失败（v2） | logcat（本轮 §4.3） | `No APK Signature Scheme block in APK Signing Block with ID: 4031998144` |
| 跨包装载失败（v3） | logcat（本轮 §4.3） | `VirtualMachineException: Failed to open APK <- FileNotFoundException: ENOENT` |

设备侧包状态（`dumpsys package`）：主包 `versionCode=503`、`0.5.3-qa.dvpvm.*`；扩展包 `versionCode=503`、`0.5.3-qa.dvpvm.*.accel`，签名 v2+v3（v4 idsig 已生成但用 `adb install --v4-signing` 推不进设备，设备端 `pm install` 不认识该选项）。

### 4.2 T-B 像素对比（同页、同滚动位置）

`tb-55-console-top.png` vs `tb-100-console-top.png`：

| 区域 | 55% | 100% | ΔLuma |
| --- | --- | --- | --- |
| masthead 顶栏 | (18,19,24) | (22,23,28) | +4.0 |
| CPU 卡片 | (18,19,24) | (22,23,28) | +4.0 |
| 页面背景 | (18,19,24) | (22,23,28) | +4.0 |
| 底栏 tab | 高亮态，不随值变化 | 同 | 0 |

读数说明：55% 下 surface 透出页面背景，100% 下回到 surface 本色（#16171D）；底栏高亮 tab 是 primary 色，按设计不参与 surface token。

### 4.3 T-D 帧指标

| 项 | before | after |
| --- | --- | --- |
| 采样帧数 | 638 | 670 |
| Janky frames | 22（3.45%） | 22（3.28%） |
| legacy janky | 76（11.91%） | 76（11.34%） |
| p50 | 5 ms | 5 ms |
| p90 | 14 ms | 13 ms |
| p95 / p99 | 27 / 101 ms | 27 / 101 ms |
| Missed Vsync | 11 | 10 |
| Slow UI thread | 22 | 20 |

场景：3 轮 5 个 tab 切换（15 次）+ 3 次设置页开关。120 Hz 面板下 p50 已贴地，剩余 p99 尖峰来自 Activity 首帧与设置页 inflate，非动画本身。

### 4.4 单测

`:app:testDebugUnitTest`：**131 个测试类 / 1055 个用例 / 0 失败**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）。`:runtime:fastlane-distcc:test` 8 个任务全绿。

### 4.5 敏感门

`python tools/ci/check_sensitive_data.py --self-test` → `SENSITIVE_DATA_OK`；`--range main..HEAD` → `SENSITIVE_DATA_OK`。

## 5. 未验证项与偏差

1. **pVM 跨包装载仍未成功**（最重要）。v3 签名与 VM 自愈都已落地，但复测仍是 `Failed to open APK: ENOENT`。已确认的平台事实：`VirtualMachineConfig.getApkPath` 在 API 36 上不可反射读取（自愈分支进不去），且普通 app 无法为「非自身 APK」生成 v4 idsig。**这不是本轮能靠代码修好的**，见 §7。
2. **T-A ③ 的远端编译没有发生**。日志显示 `lane_lost ACCEL PAYLOAD_LOAD_FAILED` 早于终端就绪，因此「终端实编一轮 C」只能本地完成，未采「远端走 pVM」的成功证据。
3. **E2 终端背景 SAF 全流程未补验**。终端页截图覆盖了 IME 弹起/收起两种状态（`ta-terminal-progress.png` vs `tb-matrix-55-terminal.png`），未见变形；但「选背景图 → scrim 生效」的完整 SAF 流程需要系统选择器交互，本轮未做。
4. **T-C 的「遮罩改值即时生效」未实机对照**。链路审计到 `ModernTerminalView.applyScrimColor` 只改 layer[1]，设计上生效；但「无背景图时滑杆无可见效果」是既定语义（设置页已写明），本轮未用背景图做 A/B 截图。
5. **T-E 抽屉遮罩只做了编译验证**。视觉复验需要实例重新装配，本轮结束时实例因 `force-stop` 处于中断态。
6. **T-D 的 before/after 不是严格 A/B**：两次采样之间设备温度、后台进程不同（p50 一致、p99 一致，说明采样噪声可控），但不足以主张「变快」。

## 6. 风险

- **把「v3 签名」当成跨包链的完整解**：会误判 T-A 已闭环。实际还有 idsig/路径两关，且第二关在平台侧。
- **VM 自愈的删除语义**：配置漂移时 `stop→delete→重建` 会销毁该 VM 的 encrypted storage。payload 编译器无持久状态，当前安全；若未来 payload 写入持久工作区，需要改成「迁移」而非「删除」。
- **`getApkPath` 反射失败被当作「匹配」**：这是刻意的向后兼容（老平台无 getter），代价是旧平台上的漂移仍然只能靠人工删 VM。注释已写明。
- **T-D 的全局 windowAnimationStyle** 会覆盖个别页面自定义的 `ActivityOptions` 转场（实例卡 scale-up 走 `ActivityOptionsCompat`，不受影响）；若后续有共享元素转场，需要显式回到 `overrideActivityTransition`。

## 7. 需维护者决策（3 条）

1. **跨包 payload 的装载通道要不要换**（阻塞 T-A ③/④ 的唯一门）：
   - (a) **私有副本**：主包把扩展包 APK 复制到自己的 `files/` 下，`setApkPath` 指向副本；idsig 由平台按需生成（仍需验证 `createOrUpdateIdsigFile` 对非自身 APK 的行为）。代价：安装后首启复制 132 MB，占用翻倍。
   - (b) **平台 API**：改用 `IVirtualizationService.createOrUpdateIdsigFile`（@SystemApi，普通 app 无权限）→ 不可行，除非系统签名。
   - (c) **回归单包**：把 payload 放回主包（放弃 70% 体积收益），换取「setApkPath 不需要」。
   - 我倾向先做 (a) 的 spike：一次复制 + 一次 `setApkPath`，用 logcat 判生死，不改产品默认路径。
2. **T-A 的验收口径**：本轮证据是「安装→识别→启动→失败原因」四段，缺「编译成功走远端」。是否接受把 T-A 记为 **partial（3/4）**，并把 (a) 的 spike 作为会话 20 的第一动作？
3. **T-D 是否保留**：性能无改善（噪声内），保留的理由是视觉一致性。若维护者只认性能数字，可以整组回退（`themes.xml` + `oe_*` 六个资源 + 按压时长），回退成本一次提交。

## 8. 交接：会话 20 的第一动作

1. **一条命令判 idsig 生死**（不改产品代码）：
   ```
   adb shell cmd virtualizationservice createOrUpdateIdsigFile --apk <扩展包路径>
   ```
   若可用，说明平台能给外部 APK 生成 idsig，跨包链只差主包主动调用；若 `PERMISSION_DENIED`，直接走 §7(a) 的私有副本 spike。
2. **私有副本 spike**：把 `/data/local/tmp/ext-accel-v3.apk` 复制进主包 `files/accel/base.apk`，`setApkPath` 指向它，抓 `OrchestellaAccel` 的 `lane start failed` 一行判读。
3. **不要先改 `DualLaneCoordinator` 或降级策略**：本轮证明失败发生在 `VirtualMachine.run()`，与车道预算/策略无关。

## 9. 声明

- 没有改 `runtime/*` 之外的产品默认开关；`runtime/engine-fastlane` 的改动只增加漂移自愈与 apkPath 比较，失败码集合不变。
- 没有放宽或删除既有断言；1055 个 JVM 用例全绿。
- 没有直推 `main`，没有合并自己的分支；提交只在 `feat/session19-experience-20261028`。
- 敏感门：`--self-test` 与 `--range main..HEAD` 均 `SENSITIVE_DATA_OK`；设备截图、包名、路径未入库。
- 设备：安装了 QA 主包与 v3 扩展包，授予 `MANAGE_VIRTUAL_MACHINE` / `USE_CUSTOM_VIRTUAL_MACHINE`；结束时实例因 `force-stop` 处于中断态，未删除实例、未清数据。
- 报告人：主协调（DeepSeek-V4.1-Flash）｜日期 2026-10-28
