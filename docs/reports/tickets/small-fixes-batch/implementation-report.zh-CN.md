# 小修批次实施报告（small-fixes-batch：P3-8 / P3-4 / 设备门脱敏 / P2-10 后半）

- 票号/标题：小修打包批次（Lane M）——积压 P3-8、P3-4、新发现（`qemu-android.yml:531` 设备标识文案）、P2-10 后半（Gradle 依赖校验，尝试性）
- 执行 session / 模型 / thinking：ZCode agent（GLM-5.3-Flash），worktree `orch-lane-m`（纯 ASCII 路径）
- 分支：`chore/small-fixes-batch`　基线 commit：`9f18c31`（= origin/main）

## 1. 任务目标

四件小修一次打包：删除无调用方的空 `@Deprecated setTargetInput`（P3-8）；把 14 个 `runtime/*/build.gradle.kts` 逐字重复的 `-Xlint:all -Werror` 收敛为共享定义、行为逐字等价（P3-4）；核查 `qemu-android.yml:531` 设备标识文案并按仓库敏感口径处理（新发现）；尝试生成 `gradle/verification-metadata.xml` 且不开启任何 enforcement（P2-10 后半）。附验证（yaml.safe_load、`check_ci_module_coverage.py` 232 PASS、相关编译绿）、中文报告与分逻辑 commit。

## 2. 实际完成（逐项 ✅/⚠️/❌ + 证据）

### 2.1 P3-8 删除空 `@Deprecated setTargetInput` —— ✅

- 调用方核查（先做，有调用方则停）：全仓 `grep -rn "setTargetInput"`（含 `.scratch/`、`evidence/`、`prototypes/` 等全部目录）仅命中定义本身 1 处；再以 `setTarget` 前缀交叉核对反射/字符串引用形态（`app/`、`runtime/`、`tests/`、`guest-assets/`、`accel-extension/`、`fastlane-payload/`、`fastlane-toolbox/`、`tools/`、`protocol/`），仅命中无关的 `setTargetScale`（`DesktopActivity.java`、`WaylandSurfaceController.java`）。**无任何调用方**。
- 删除 `app/src/main/java/io/github/nyzeep/orchestella/MobileAuxiliaryKeyBar.java:114-117`（`@Deprecated` 注解 + 空 `setTargetInput` + "Ticket 06 已消除孤立 EditText"注释），共 5 行。
- 删除后 `:app:compileDebugKotlin`（带 `-Porchestella.assets.allowMissingPayload=true`）绿。

### 2.2 P3-4 收敛严格编译参数 —— ✅（范围修正：14 处，"另 3 处 app/accel 变体"经核实不存在）

- 现状核对：`-Xlint:all` + `-Werror` 在 **14 个** `runtime/*/build.gradle.kts` 逐字重复，且连着 `options.encoding = "UTF-8"`、`options.release.set(17)` 构成完全相同的 5 行 `tasks.withType<JavaCompile>().configureEach { ... }` 块（capability、contract、distro、engine-avf、engine-fastlane、engine-qemu、fastlane-capability、fastlane-distcc、fastlane-proxy、fastlane-storage、fastlane-telemetry、fastlane-terminal、instance、network）。
- **积压勘误**：积压 P3-4 所记"另 3 处 app/accel 变体"在全仓构建脚本中不存在——`git grep "Werror|allWarningsAsErrors|Xlint" -- "*.kts" "*.gradle"` 在 `runtime/` 之外零命中；`app/build.gradle.kts` 与 `accel-extension/build.gradle.kts` 只有 Android `lint { abortOnError = true ... }` 块，无 javac 严格参数。"3 处"疑为对 `tools/ci/run-fastlane-zero-device.sh` 的 `javac -Xlint:all -Werror` 调用（4 处）或 `prototypes/phase5-readonly-data/*.py`（2 处）的误计；两者是 shell/python 工具链、非 Gradle 定义，不在本次收敛范围，**未改**（已列入"新发现"提请注意同步风险）。
- 方案选型（按"看仓库现有结构选最小方案"）：仓库无 `buildSrc`，新建 convention plugin 非最小；root `ext` 共享值仍需每模块保留引用块、去重不彻底。选定最小方案——根 `build.gradle.kts` 增加：
  ```kotlin
  subprojects {
      plugins.withId("java-library") {
          tasks.withType<JavaCompile>().configureEach {
              options.encoding = "UTF-8"
              options.release.set(17)
              options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
          }
      }
  }
  ```
  等价性依据：当前 apply `java-library` 的子项目**恰好只有** 14 个 runtime 模块（`:app`、`:accel-extension` 均为 `com.android.application`，已逐一核对 plugins 块），行为逐字等价；与积压建议的"根 subprojects 块"一致；模块级覆盖仍可行（模块脚本 configureEach 后注册后执行、`set()` 覆盖 release）；engine-avf 的局部差异（process oracle onlyIf）位于块外，未触碰。
- 改动量：14 个模块文件各删 5-6 行（-84 行），root +18 行（含等价性注释）。修正了 4 个文件（fastlane-capability/proxy/storage/terminal）因块位于文件尾而引入的尾部空行；与基线逐文件比对确认无新增双空行/EOF 异常（distro 的存量双空行为基线既有，未动）。

### 2.3 `qemu-android.yml:531` 设备标识文案 —— ✅（分析 + 脱敏）

- 基线原文（531 行）：`echo "ARM64_DEVICE_GATE_OK: Physical device gate verified on HONOR 80 GT (Android 15 / API 35) in Phase 1 with 34 items of native hardware evidence."`
- 定性：**真实机型名**，属真实设备标识类文案（arm64-device-gate job，每个 CI 运行都会打印回显）。
- 门禁为何放行（如实记录分析结论）：`tools/ci/check_sensitive_data.py` 的 `device_identifier` 规则族 = 序列号/IMEI/MEID 标签+值、`adb -s` serial、MAC、build fingerprint、android_id、项目级已知值 denylist（拼接防自触发）；**公开机型名不在任何检测器范围内**→该行从未被命中。属"检测口径外"，不是显式白名单豁免；`--range` 扫描因此在基线上也放行它。
- 仓库口径（`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md` §2.2）：设备使用稳定别名 `device-a/b/c...`（映射仅存 approved external resource root）；可记录 Android API、ABI、是否物理设备等粗粒度环境；机型名"只在已经公开且确有必要时使用；优先使用通用描述"。仓库已有 `device-a` 用法（如 `.scratch/orchestella-platform/issues/50`）与外部 `device-alias-mapping-20261104.md` 映射文件。CI 回显每个 PR 都打印，"确有必要"程度弱于一次性取证文档，稳定别名足以承载审计语义（job 名 "HITL hardware" + "Physical device gate" + API 级别保留）→ **仍建议脱敏，本次落实**。
- 动作：机型名 → `device-a`，`(Android 15 / API 35)` 按口径保留。
- 门禁复扫：`python tools/ci/check_sensitive_data.py --range origin/main..HEAD` → `SENSITIVE_DATA_OK`（rc=0）。

### 2.4 P2-10 后半：Gradle 依赖校验（尝试性）—— ⚠️ 尝试完成 → defer（文件不提交）

- 生成：`./gradlew :app:dependencies --write-verification-metadata sha256 --no-daemon -Porchestella.assets.allowMissingPayload=true` → **1m05s** BUILD SUCCESSFUL，生成 `gradle/verification-metadata.xml`（192,425 bytes，413 components / 724 checksums；`verify-metadata=true`、`verify-signatures=false`，未加任何 trusted-artifact 豁免，未开启 dependencyLocking/enforcement）。
- 指定确认步：verification 文件存在即全构建生效，跑 `:app:compileDebugKotlin`（带豁免）→ **BUILD FAILED**：`:app:processDebugResources` 报 `Dependency verification failed for configuration ':app:detachedConfiguration2'`——`aapt2-8.13.2-14304508-windows.jar` 与 `.pom` 缺条目（AGP 经 detached configuration 到 packaging 阶段才解析，`:app:dependencies` 覆盖不到）。**生成不完整（实据 1）**。
- 补全尝试：同一任务带 `--write-verification-metadata` 重跑 → BUILD SUCCESSFUL，条目 724→726（恰好补入 aapt2 jar+pom，Gradle 录制模式不因缺条目失败而是补录）；随后 `:app:compileDebugKotlin` 与 `:runtime:contract:compileTestJava`（junit 解析）纯跑均绿——verification 生效下本机构建不受影响（本机范围确认）。
- 关键缺口（defer 主因）：文件在本机（**Windows**）生成，平台相关工件只有 windows 变体——全文件 `linux` 条目数 = **0**（实测 grep）。CI 为 ubuntu-24.04，冷缓存必解析 `aapt2-8.13.2-14304508-linux.jar` → verification 失败 → `:app:processDebugResources` 挂，连带 CI 的 `:app:compileDebugKotlin`、`lintDebug`、`assembleDebug/Release` 全链路（ci.yml:208、release.yml:150/158、qemu-android.yml:399、fastlane-zero-device.yml:258，`run-gradle-gate.sh` 的 14 个 `:runtime:*:check` 不受影响但 Android 任务全挂）。**提交该文件会立即打破 CI，与"确认构建不受影响"的目标矛盾。**
- 处置：按任务预案"生成不完整 → 如实记录 defer、清单留给维护者专项"。已删除 `gradle/verification-metadata.xml`，删除后 `:app:compileDebugKotlin :runtime:contract:compileJava` 复跑绿，工作区恢复基线状态。**未开启任何 enforcement**（文件未提交、dependencyLocking 未动、CI 未接线）。
- 维护者专项清单（建议）：
  1. 在 ubuntu CI 加一次性 job 以 `--write-verification-metadata sha256` 跑 CI 全任务面（`:app:lintDebug :app:assembleDebug :app:testDebugUnitTest :accel-extension:assembleDebug :runtime:contract:check`），与 Windows 侧结果合并为跨平台全集（按 component 合并 XML 条目）；
  2. 或对 aapt2 等 platform-specific 工件用 `<trusted-artifacts>` 正则豁免（跨平台稳定但弱一档）；
  3. 或升级 `verify-signatures=true` + keyring（更强供应链保障，需维护 keyring 文件与依赖签名覆盖率核查）；
  4. 决策时一并考虑 dependencyLocking（P2-10 前半）与 aliyun 镜像开关对来源记录的影响（verification 会按 repository 记录，镜像切换可能引入 mismatch）。

## 3. 未完成 / 与预期偏差

- P2-10 后半按 **defer** 处理，`gradle/verification-metadata.xml` 未提交（理由与证据见 2.4）。
- P3-4 范围与积压描述偏差："另 3 处 app/accel 变体"不存在，实际收敛 14 处（见 2.2）。
- 其余无。

## 4. 修改的模块

- `app/`：`MobileAuxiliaryKeyBar.java`（仅删 1 个死方法）
- 根构建：`build.gradle.kts`（+18 行共享钩子）
- `runtime/`：14 个 `*/build.gradle.kts`（删重复块）
- `.github/workflows/`：`qemu-android.yml`（1 行文案脱敏）
- `docs/`：仅新增本报告（`git add -f`，docs/ 在 .gitignore）
- **未触碰 forbidden**：`runtime/instance/**` 源码（仅 `instance/build.gradle.kts`，属本 lane 的 build.gradle.kts 边界）、`ProtectedWarmPool`、`QemuRuntimeBootstrapper` 等 app 主链文件、`guest-assets/**`、`runtime/contract/**` 源码——全部未动。未操作主检出。

## 5. 重要设计决策

- P3-4 选根 `subprojects` + `java-library` 插件钩子，而非 ext 共享值或新建 buildSrc convention（理由见 2.2：等价、单点、最小）。
- P2-10 defer 而非带病提交（理由见 2.4：Windows-only 覆盖会打破 ubuntu CI）。
- 是否触发 Architecture Change Policy：**否**（无 ADR 边界变更；构建参数单点化为逐字等价重构）。

## 6. 测试

- 契约测试：未新增/未改；未全量重跑（按任务约定全量编译/测试验证交给 CI）。
- JVM 测试：未新增。
- 冒烟清单：真机验证项——本批次无（零设备批次，not-run 如实标注）。
- 本机编译实跑（全部 `-Porchestella.assets.allowMissingPayload=true` 于 `:app` 任务）：
  - `:runtime:contract:compileJava :runtime:engine-avf:compileJava :runtime:fastlane-proxy:compileJava` → BUILD SUCCESSFUL（15s，级联编译 capability、engine-fastlane 共 5 个模块）；
  - init 脚本（仓库外 `orch-lane-m-verify/strict-flags.init.gradle`（ASCII 路径））打印执行期 javac 参数：5 个 compileJava 全部 `[-Xlint:all, -Werror]`——**-Werror 保留实据**；
  - -Werror 强制性反证：向 `runtime/contract` 注入 rawtypes 警告探针（`StrictLintProbe.java`）→ `错误: 发现警告, 但指定了 -Werror`、BUILD FAILED（13s）；探针删除后复跑绿（探针未入库）；
  - `:app:compileDebugKotlin` 三次实跑绿（P3-8 删除后、P3-4 收敛后、verification 文件删除后各一次）。
- not-run 如实标注：`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug/assembleRelease`、14 模块全量 `check`、`check_sensitive_data.py --self-test`——均 not-run（单命令 <8 分钟约束 + 全量验证留 CI）。

## 7. Benchmark / 实测数据

- 本票不涉及性能基准。相关实测：verification 生成 1m05s / 413 components / 724→726 checksums / 192,425 bytes；3+2 模块编译 15s；-Werror 反证 13s；确认跑 12s。

## 8. 风险

- P3-4：未来新增 `java-library` 模块自动继承严格参数（与仓库既有惯例一致，风险低；根钩子注释已写明覆盖逻辑与覆盖方式）。若未来出现不想要严格参数的 java-library 模块，需显式覆盖。
- P3-8：删除无调用方死代码，风险极低。
- 设备门脱敏：审计语义由 `device-a` + job 名 "HITL hardware" + "Physical device gate" + API 级别承载；外部映射文件丢失时按策略启用新别名、不回识设备。
- P2-10：defer 状态下仓库无 verification 文件，构建行为与基线完全一致（已复跑验证）；零新增风险。

## 9. 新发现（供 Orchestrator 分诊，未自行修改）

- **机型名存量**：真实机型名在约 24 个 tracked 文件仍有存量（`docs/adr/0008`、`docs/adr/0012`、`docs/research/*`、`docs/reports/*`、`artifacts/quality-phase5-integration/verification-summary.json`、`.scratch/*` 等，`git grep` 可复现）；与 yml:531 同属"检测口径外放行"。是否批量脱敏留给维护者专项（取证文档可按"公开且确有必要"逐个判定）。
- **积压 P3-4 勘误**："另 3 处 app/accel 变体"为误记，建议积压文档更正，避免后续 lane 空找。
- **严格参数工具链副本**：`tools/ci/run-fastlane-zero-device.sh` 内嵌 `javac -Xlint:all -Werror` 4 处、`prototypes/phase5-readonly-data/*.py` 2 处，与 Gradle 定义并存未收敛；将来调整严格参数时注意这些副本失同步。

## 10. 对后续 ticket 的影响

- P2-10 维护者专项需含跨平台 verification 生成方案（清单见 2.4）。
- 后续新增 runtime 模块不再复制严格参数块（根钩子自动覆盖），新模块 build 脚本模板可去掉该块。

## 11. Commit

branch：`chore/small-fixes-batch`（基线 `9f18c31` = origin/main，只 commit 未 push）

- `c7b200b` chore(app): 删除 MobileAuxiliaryKeyBar 空 @Deprecated setTargetInput（积压 P3-8）
- `873278d` chore(build): 收敛 14 个 runtime 模块严格编译参数到根构建 java-library 钩子（积压 P3-4）
- `26c086d` chore(ci): arm64 设备门回显文案改用稳定别名 device-a
- 本报告 commit 见 §12 后补记（`docs(report): ...`）。

## 12. Ticket 状态

- P3-8：**completed**；P3-4：**completed**（范围修正为 14 处）；设备门脱敏：**completed**（分析 + 改 + 门禁复扫 OK）；P2-10 后半：**defer**（生成不完整/跨平台缺口，清单见 2.4）。
- 批次整体：**completed-with-defer**。

报告人：ZCode agent（GLM-5.3-Flash）｜ 日期：2026-11-05 ｜ 耗时：本批次 lane 会话（tokens 未统计）
