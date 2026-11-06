# 更新卡片收敛与真机环境重置轮（2026-11-06）

- 执行 session / 模型 / thinking：编排会话（单会话，无子智能体）/ GLM-5.3-Flash / 高
- 分支：`main`；基线 `49f9e8e`；HEAD `6167be9`（已 push）
- 报告规范：`docs/agent-handoff/REPORTING_STANDARD.zh-CN.md`

## 1. 背景

用户两项指令：

1. 真机（device-myron）里留有此前开发安装的同名 APK，为避免影响后续开发，先卸载并清除其缓存与残留，可复用资产保留，然后再构建。
2. 关于页的更新提示有两处入口、功能重叠；卡片上不要「一键下载 / 可忽略」这类动作位，只保留 GitHub 远端 Release 说明的摘要。

## 2. scope

| 面 | 内容 |
|---|---|
| 设备 | 6 个开发遗留包的卸载与残留核验；不触碰用户数据与仓外研究夹具 |
| 产品 | 关于页更新卡片合并为单卡（布局 / 文案 / 状态机 / 契约文档 / 单测） |
| 构建 | QA 通道 `dvpvm` 主包构建、真机安装、UI 验证 |
| 不做 | accel 扩展包重建（载荷 staging 缺失，见 §5）、其它积压项 |

## 3. 实际完成

### 3.1 设备清理（device-myron）

| 包 | 卸载前版本 | 处置 |
|---|---|---|
| `io.github.nyzeep.orchestella` | 0.6.2 / code602（release） | 卸载 |
| `io.github.nyzeep.orchestella.accel` | 0.6.0 / code600 | 卸载（versionCode 与 602 主包不一致，本就不能同用） |
| `io.github.nyzeep.orchestella.qa.dv01` | QA 旧批次 | 卸载 |
| `io.github.nyzeep.orchestella.qa.dv01.test` | 同上 | 卸载 |
| `dev.orchestella.umlprobe` | 研究探针 | 卸载 |
| `dev.orchestella.umlrunner` | 研究 runner | 卸载 |

卸载后核验（全部为空）：`pm list packages`、`pm list packages -u`（无「保留数据」的幽灵项）、`/data/data/*`、`/data/app/*`、`/sdcard/Android/{data,media}/*`、`/data/dalvik-cache/*`。

复用资产判定：

- QA 包 `files/instances` 为空；`files/avf-qa/` 只有 3 份 preflight/probe receipt（结论已入报告）；`vm/` 三个 preflight VM 可再生 → **无保留项**。
- 宿主侧未动：`app/build` 产物、`artifacts/qa-jniLibs-4k-arm64`（4K 对齐 QEMU 载荷）、签名材料、payload 构建树。
- **未处理，待裁定**：`/data/local/tmp` 约 4.9 GB 研究夹具（`t7-arch.ext4` 3.0 GB、`c200-qemu.ext4` 805 MB、`uml-rootfs.ext4` 100 MB、若干 APK/tar/png）。可重建但耗时，不属这 6 个包的残留，本轮不删。

### 3.2 更新卡片收敛为单卡

- **布局**（`activity_about.xml`）：删除应用信息卡里整宽的「🔄 立即检测更新」按钮；原条件显示的 `card_update_available` 改为常显的 `card_version_update`，自上而下为 标题 / 状态行 / 通道行 / 摘要 / 两个文字动作。
- **三态**（`AboutActivity.renderVersionCard`）：
  - 有更新：「有可用新版本 vX」+ Release 说明摘要（`UpdateNotes` 5 行 / 320 字）+「在 GitHub 查看」；
  - 无更新：「尚无可用更新」+ 一句「进入本页自动检查 / 摘要只保留发布说明」的说明；
  - 检查已关闭：「更新检查已关闭」+ 零遥测说明，检查按钮一并隐藏。
- **删除的动作位**：「前往下载」「暂时忽略」两个按钮，以及点「前往下载」后弹出的二级「更新说明」对话框（`showUpdateSheet`）。Release 页成为唯一出口，URL 仍由 `RELEASES_PAGE + /tag/<tag>` 本地拼装。
- **状态机**：忽略语义整体退场——`UpdateCenter.ignore/clearIgnored`、`UpdateStateStore.ignoredVersion/setIgnoredVersion`、`PrefsUpdateStateStore.KEY_IGNORED` 删除；`pending()` 不再过滤忽略版本。
- **文案**：新增 13 条 `version_update_*` 字符串；删除 4 条只服务旧下载弹窗的死字符串（`update_checking` / `update_available_title` / `update_available_msg` / `update_open_browser`）。
- **契约**：`docs/standards/APP-UPDATE.md` 升 v1.1（§4 改写为「不做忽略入口」、§5 改为单卡三态、§6/§8 同步、追加修订记录）。该文件此前**未被 git 跟踪**，本次经审查 `git add -f` 入库。

## 4. 测试与验证

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest`（release 通道） | **1207 tests / 0 failures / 0 errors / 0 skipped**，144 个测试类 |
| 新增/替换用例 | `UpdateCenterTest.anAnnouncedVersionKeepsShowingUntilTheCheckSaysOtherwise` 替换原忽略用例，用例数持平 |
| `:app:lintDebug` | **0 errors / 34 warnings**；severity ≥ Warning 的 34 条**零条落在改动文件**（改动文件只命中 informational 级 Hint） |
| QA 构建 | `:app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=dvpvm -Porchestella.acceleratorLane=true` → 107,560,247 B |
| APK 指纹 | `applicationId=io.github.nyzeep.orchestella.qa.dvpvm`、`versionCode=602`、`versionName=0.6.2-qa.dvpvm.6167be9`、`targetSdk=36`；内含 `libdroidlayer_qemu.so` / `libgvproxy.so` |
| 敏感门 | `check_sensitive_data.py --self-test` 与 `--range origin/main..HEAD` 均 `SENSITIVE_DATA_OK` |

通道敏感说明：以 `buildChannel=qa` 跑同一测试任务时 `BootstrapTest.applicationIdMatchesBootstrapChoice` 会失败（它断言 release applicationId），属既有通道敏感用例，非本改动引入；以默认通道重跑即 1207/0，故以此为准。

真机验证（device-myron）：

1. 卸载后首次 `adb install` 被 HyperOS 拒绝（`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，锁屏态；`pm install` 同结果）；解锁后 `adb install` 成功，无二次确认。
2. 关于页（无更新态）：卡片刻「版本与更新 / 尚无可用更新 / 开发预览通道 · 当前 v0.6.2-qa.dvpvm.6167be9 / 说明 / [立即检查更新]」，无下载/忽略按钮。
3. 点「立即检查更新」：卡片切到「有可用新版本 v0.6.2」，摘要为远端 Release 正文摘要（5 行截断，带 `…`），出现「在 GitHub 查看」；无崩溃、无 FATAL。
4. 主界面顶栏「关于」入口的更新圆点在位，主界面其余部分渲染正常。

证据（仓外，按 SENSITIVE_DEVELOPER_DATA_POLICY）：`evidence/device-reset-20261106/{about.png, about-update-available.png, main-screen.png}`。

## 5. 未完成 / 与预期偏差

- **accel 扩展包未重建**：`:accel-extension` 需要的 `orchestella.fastlane.payloadLibDir` 与 `orchestella.pvmCompiler.libDir` 两个 staging 根在树上不存在（需 `build-payload.sh` + PVM 编译器库），本轮只验证 UI，故不构建；因此本机 pVM/加速车道不可用（fail-closed 回落 QEMU），QEMU 车道不受影响。
- **摘要里的旧文案**：远端 v0.6.2 Release 正文本身写着「可一键『前往下载』，也可『暂时忽略』」——这是发布说明的历史文本，不是 App 文案。若要与新 UI 一致，需在 GitHub 上编辑 Release note。
- 「检查已关闭」三态分支未真机点验（需去设置页关开关再回来），仅代码路径审查 + 单测覆盖 `record(DISABLED)` 零写入。
- `/data/local/tmp` 4.9 GB 研究夹具未清（等用户裁定）。

## 6. 修改文件

- `app/src/main/res/layout/activity_about.xml`（两卡合一、删 2 按钮、加 2 文字动作）
- `app/src/main/res/values/strings.xml`（+13 `version_update_*`，-4 旧弹窗字符串）
- `app/src/main/java/io/github/nyzeep/orchestella/AboutActivity.kt`（三态渲染、手动检查、失败对话框；删 `showUpdateSheet`）
- `app/src/main/java/io/github/nyzeep/orchestella/update/UpdateCenter.java`（去忽略语义与过滤）
- `app/src/main/java/io/github/nyzeep/orchestella/update/UpdateStateStore.java`（去忽略 API）
- `app/src/main/java/io/github/nyzeep/orchestella/update/PrefsUpdateStateStore.java`（去 `KEY_IGNORED`）
- `app/src/test/java/io/github/nyzeep/orchestella/update/UpdateCenterTest.java`（替换忽略用例）
- `docs/standards/APP-UPDATE.md`（v1.1，首次入库）

## 7. 风险

- 忽略状态删除后，旧安装里残留的 `update_ignored_version` 键不再被读取，也不再写入（无害残留）。
- 卡片改为常显，关于页首屏下移约 200 dp；真机确认无裁切、无重叠。
- HyperOS 全新安装仍需解锁 + 屏上确认；这是设备策略，不是构建问题，后续新会话安装前先确认 `isKeyguardShowing=false`。

## 8. 后续动作

1. 设备窗三项（distcc@36 端到端、票 27 真实 QEMU 事件原件、C1/B2 真机长跑）开工前先补 accel 扩展包：`bash tools/fastlane-build/build-payload.sh` + PVM 编译器 staging，再以同通道参数构建 `:accel-extension:assembleDebug`。
2. `/data/local/tmp` 4.9 GB 夹具处置需用户裁定（保留 / 清空）。
3. 如需远端 Release 说明与新 UI 一致，编辑 GitHub 上 v0.6.x 的 Release note。

## 9. 是否越界

- 触及 `docs/standards/APP-UPDATE.md` 这条 Active Hard Policy：按用户明确指令执行，并已同步文档（v1.1 修订记录写明变更来源为 2026-11-06 用户指令）。
- 未触发 Architecture Change Policy：无 ADR、无模块接口/暴露面变更。
- 未改 release 打包口径、签名、CI workflow。
