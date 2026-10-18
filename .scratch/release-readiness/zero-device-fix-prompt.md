# 零设备修复分支长任务 Prompt

> 直接把本文件内容作为新会话的任务说明使用。当前环境没有 Android 设备、没有 adb；只做代码、测试、CI 与文档。所有真机项必须标 `not-tested`，不得把 JVM/fake/dry-run 结果写成设备通过。

---

你是 Orchestella 的 **release-readiness 修复会话**。你的目标是把“公开发布前可由代码解决”的阻塞全部修掉，并把需要密钥、产品决策或真机验证的事项清晰留在交接里；**本会话不发布、不打 tag、不合并 main**。

## 0. 仓库、基线与边界

- 仓库：Orchestella-Studio。基线：`main 4357608`（已包含本交接与 prompt；运行时性能合并 `881e37a`，版本标准 `a32b477`，发布说明草稿 `20a3507`）。
- 分支：从 `main 4357608` 切 `fix/release-readiness`；只提交到该分支，不 push `main`，不 merge `main`。
- 必须从 ASCII 路径执行 Gradle（中文/括号路径会导致测试 worker `ClassNotFoundException`）；通常加 `--offline`。
- 先读：
  1. `AGENTS.md`
  2. `CONTEXT.md`
  3. `docs/standards/VERSIONING.md`（第 5 节：发布版 / 内部开发版 / 隔离 QA 版）
  4. `docs/agent-handoff/runtime-performance-release-readiness-20261018.zh-CN.md`
  5. `docs/reports/releases/runtime-performance-next-release-draft.zh-CN.md`
- 当前版本：`0.5.3 / versionCode 503`。**不要修改 release 的 `versionName` / `versionCode`，不要打 tag，不要创建 GitHub Release。**
- 不要生成签名密钥，不要提交 keystore，不要把口令写入仓库、日志或对话。
- 不要执行 adb/设备命令；没有设备可用。

## 1. 任务目标

按下面的 Wave 1 → Wave 3 顺序完成代码侧发布阻塞。每个任务都要有测试或可重复的校验；不能验证的设备项写 `not-tested`。

### Wave 1（可并行）

#### A. 构建通道落地（`app/build.gradle.kts`）

目标：让发布版、内部开发版、隔离 QA 版的 applicationId/versionName/APK 名可区分，且默认 release 行为不变。

实现要求：

- 新增 Gradle 属性 `orchestella.buildChannel`：默认 `release`；允许 `dev` / `qa`。
- `qa` 必须同时提供 `orchestella.buildPurpose`（小写字母/数字/短横线）。
- 从 git 读取短 SHA（短 7 位）；工作树脏时内部版可加 `.dirty`。
- 在 `debug` build type 中按通道注入：
  - `dev`：`applicationIdSuffix = ".dev"`，`versionNameSuffix = "-dev.<shortSha>"`。
  - `qa`：`applicationIdSuffix = ".qa.<purpose>"`，`versionNameSuffix = "-qa.<purpose>.<shortSha>"`。
  - 默认 `release`：不改变现有 debug 行为（注意现有 `phase5Debug`/release signing 分支，不要被新通道意外覆盖）。
- APK 命名包含通道（例如 `Orchestella-Studio-v0.5.3-dev.<sha>.apk`）。
- 不修改 `defaultConfig` 的 release `versionName`/`versionCode`。

验收：

- `:app:assembleDebug -Porchestella.buildChannel=dev` 产物 `aapt2 dump badging` 显示 `io.github.nyzeep.orchestella.dev`、`0.5.3-dev.<sha>`。
- `qa` 同理显示 `.qa.<purpose>` 与 `-qa.<purpose>.<sha>`。
- 不传属性时 debug 产物与当前一致。
- 有测试或脚本证明后缀逻辑；`git diff --check` 通过。

#### B. Release 签名配置硬化（`app/build.gradle.kts`）

目标：release 必须显式签名；没有密钥时明确失败，而不是悄悄产出未签名包。

实现要求：

- 移除默认口令 `orchestella2026`；release signing 必须显式提供：
  - `orchestella.signing.storeFile`
  - `orchestella.signing.storePassword`
  - `orchestella.signing.keyAlias`
  - `orchestella.signing.keyPassword`
- 缺少任一项时，`assembleRelease` 以清晰错误失败；`assembleDebug` 不受影响。
- 不生成/提交 keystore；不把真实口令写进任何文件。
- `phase5Debug` 的 `android`/`androiddebugkey` 仅作为历史 QA 配置保留，不用于 release；如可安全标注 legacy，标注。

验收：

- 无 signing 属性时 `:app:assembleRelease` 失败且错误信息可读。
- `:app:assembleDebug` 通过。
- `git grep "orchestella2026"` 不再命中 build 配置。
- `:app:signingReport` 在无属性时 release 不显示可用证书。

#### C. Release workflow 与发布门（`.github/workflows/release.yml`、`tools/release/`）

目标：发布流程可重复、可校验，且不会误发 Debug/带 dev/qa 后缀的包。

实现要求：

- 新增 `release.yml`，使用 `workflow_dispatch`；发布动作必须显式输入/确认，不因 push 自动发布。
- 通过 secrets 注入 keystore 与口令（base64 或临时文件，步骤中不得回显）；构建 release APK。
- 构建后校验：
  - `aapt2 dump badging`：applicationId 必须是 `io.github.nyzeep.orchestella`；versionName 不得含 `dev`/`qa`/`dirty`/commit SHA。
  - `apksigner verify --print-certs`：证书不得是 `CN=Android Debug`。
  - 生成 APK SHA-256。
  - 校验 NOTICE/对应源码/SOURCE-OFFER 的存在（至少文件级检查）。
- 新增 `tools/release/verify_apk.py`（或 `.sh`）：输入 APK + 期望 applicationId/versionName，输出证书、SHA256，失败退出非零；零设备可用当前 Debug APK 做“应当拒绝”的负向测试。
- PR CI 增加一个轻量检查：release 字段不得带内部后缀；不要改变 `ci.yml` 既有步骤语义。

验收：

- workflow YAML 可解析（如环境有 actionlint 就用；没有就用 Python YAML 解析）。
- `verify_apk.py` 对当前 Debug APK 返回失败（负向测试），对符合期望的 fixture/元数据返回成功。
- 无网络/设备依赖；不自动发布。

### Wave 2

#### D. GPL / NOTICE / SOURCE-OFFER 一致性

已知问题（必须逐项核对，不要凭标题猜）：

- 根 `LICENSE` 是 **GPL-3.0**；`guest-assets/GPL-SOURCES.md` 却写 guest agent/脚本为 Apache-2.0。按实际许可证修正；如组件是混合许可，逐项列出。
- `app/src/main/assets/NOTICE.GUEST-RUNTIME` 写 musl 1.2.6；`guest-assets/inputs/inputs.lock` 是 1.2.5。核对实际构建输入/产物后修正，并加一个“NOTICE 与 lock 版本一致”的检查或测试。
- `app/src/main/assets/NOTICE.QEMU` 引用 `docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md`，该文件不存在。**不要伪造历史正文**；要么更新引用到真实文件，要么新建一份明确标注“新政策”的 `GPL_RELEASE_DELIVERY_POLICY.md`，并在交接中标注需要发布负责人审核。
- `guest-assets/SOURCE-OFFER.txt` 存在；确认 release bundle 会包含 `SOURCE-OFFER.txt`、`QEMU-GPL-SOURCES.md`、`LICENSE.GPL-2.0`、NOTICE 与对应源码/构建输入。
- 增加一个 release 检查：NOTICE 中引用的文件必须存在；许可证名称与根 LICENSE 一致；musl 版本与 lock 一致。

验收：

- 不一致项全部修正；没有断链引用。
- 新增检查/测试通过；报告列出仍需要法务/发布负责人确认的项（如有）。
- 不修改历史审计表或伪造缺失文档。

#### E. 导出 / 导入迁移审计（SAF）

目标：为“同包迁移”或“新包迁移”准备数据保留代码，不改变发布决策。

实现要求：

- 阅读 `AndroidSafExportAdapter`、`AndroidSafImportAdapter`、`InstanceStorageManager`、相关测试。
- 核对导出是否覆盖：根盘镜像、实例元数据、凭据/hostname/用户配置、必要校验和。
- 核对导入是否 fail closed：截断/篡改/路径穿越/重复实例必须拒绝；失败要回滚，不留下半实例。
- 增加 JVM/SAF fake 测试；不要求真机。
- 不修改 applicationId、release version 或版本标准中的发布包名；迁移方案（同包 vs 新包）由发布负责人决定，写进交接的待决策项。

验收：

- round-trip 测试或明确的缺口报告；导入失败回滚测试；`not-tested` 仅用于确实需要设备的项。
- 报告说明同包/新包两种路径分别还需要什么。

#### F. T4 / T1 运行时债务

- T4：把停止真正移出 per-instance transition lock（或引入可证明的 STOPPING 状态/完成量设计）；修复 `InstanceLaunchSequence.afterLaunchFailure` 仍同步池单例、与 per-instance lock 不再互斥的问题；恢复 `ControlChannelRecoveryTest` 的“持锁恢复”覆盖，不要用放宽断言换绿。
- T1：stop retry 后 JSONL 反映最终结果（追加补偿记录或明确的最终状态字段），不要静默保留第一次 `UNCONFIRMED`。
- 既有 `bridgeClientResponsePumpTest` 时序 flake：定位并修根因，不要放宽断言；如果无法稳定，记录最小证据并单独标 risk。
- 保持 ADR-0020 的 `CONFIRMED` / `UNCONFIRMED` / `ALREADY_STOPPED` 语义；不新增第二个 stop owner。

验收：

- 目标测试 + 全量 app 测试通过；锁外停止有测试能证明其他实例不被阻塞；UNCONFIRMED→CONFIRMED 的 JSONL 最终值有测试。

### Wave 3（可选/夹具）

#### G. T7 与 64-forward 设备夹具

- T7：保留 `QemuTuning` 默认值不变；完善 `tools/qemu-tuning/matrix.py`，增加设备执行入口与结果字段（设备型号、Android、温度、热状态、n≥10、中位数/IQR）。零设备只做 dry-run，结果标 `not-tested`。
- 64-forward：增加 JVM/设备夹具，能打开 64 个 service forward 并测停止窗口；零设备只跑 JVM fake，真实 gvproxy 标 `not-tested`。
- 不因为夹具而修改生产默认值。

#### H. T5/T6 只读输入盘（可选）

- 宿主：`QemuLaunchSpec` 增加只读输入盘参数；guest：initramfs 优先从 `/dev/vdb` 读取并校验 rootfs，embedded-input 保留兜底。
- JVM 参数测试 + guest smoke 脚本；不改变默认路径，不做设备结论。
- 如时间不足，写设计说明并标 deferred；不要半成品接进默认路径。

## 2. 禁止事项

- 不修改 release `versionName`/`versionCode`；不打 tag；不创建/更新 GitHub Release。
- 不生成、导出、提交签名密钥；不写真实口令。
- 不执行 adb/设备命令；不声称设备通过。
- 不使用 `cache=unsafe`；不新增 stop owner；不改 ADR-0020 语义；不改生产 QEMU 默认参数。
- 不卸载/清数据/跨 user 绕过当前设备主包数据。
- 不伪造缺失的 GPL 政策历史正文。
- 不把“测试通过”写成“发布就绪”；发布仍取决于密钥/决策/设备验证。

## 3. 验证命令

```bash
# 全量 JVM / lint
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest :app:lintDebug --offline --no-daemon

# 构建通道 dry-run（示例）
./gradlew :app:assembleDebug -Porchestella.buildChannel=dev --offline --no-daemon
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep "^package:"
./gradlew :app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=runtimeperf --offline --no-daemon

# release 签名缺失必须失败（预期非零）
./gradlew :app:assembleRelease --offline --no-daemon

# 发布门负向测试：当前 Debug APK 必须被拒绝
python tools/release/verify_apk.py --apk app/build/outputs/apk/debug/app-debug.apk \
  --expected-application-id io.github.nyzeep.orchestella --expected-version-name 0.5.3

git diff --check
```

没有 adb 命令；没有设备验证。

## 4. 交付与回传

- 分支：`fix/release-readiness`；按 Wave/任务分中文 commit（不要合并 main）。
- 报告：`docs/reports/orchestration/release-readiness-fix-<date>.zh-CN.md`，包含：
  - 每个任务的改动与提交 hash；
  - 测试命令/结果；
  - `not-tested` 设备项；
  - 需要发布负责人决定的事项（签名、版本、同包/新包迁移）；
  - 任何新发现的阻塞/风险。
- 在 `docs/agent-handoff/runtime-performance-release-readiness-20261018.zh-CN.md` 末尾追加 `## Fix Report` 摘要。
- 最终回复：改了什么、测试结果、未测项、待决策项、建议的下一步（设备验证清单）。

## 5. 停止条件

遇到以下情况立即停止并报告，不要猜：

- 需要修改 release applicationId / 包名 / 版本号才能继续（产品决策）。
- 需要真实签名密钥或口令。
- 需要真机/Arch/64-forward 才能判断。
- GPL/NOTICE 的许可证归属无法从仓库证据确定（需要发布负责人/法务确认）。
