# static-checks-p2 实施报告（P2-3/P2-4 静态检查 + test_avf_device_mode 宿主红分诊）

- 票号/标题：编排 lane S 任务单（无 .scratch 独立票号）：P2-3/P2-4 静态检查落地 + test_avf_device_mode 24 项 Windows 宿主红的 CI 对齐分诊
- 执行 session / 模型 / thinking：ZCode agent（GLM-5.3-Flash）
- 分支：chore/static-checks-p2　基线 commit：2d66f67（main）
- 背景：质量积压 `docs/reports/quality-backlog-20261104.zh-CN.md` §1.3 P2-3（Kotlin 静态检查引入，ktlint+detekt+baseline，需裁定）与 P2-4（workflow pin SHA + Gradle 依赖锁定，锁定策略需裁定）要求落地零设备可执行部分；上轮票 27 发现 `test_avf_device_mode` 24 项在 Windows 宿主 adb 环境性失败（工具零改动），需按"CI 绿/红/不跑"三选一分诊。
- scope：本 lane 允许域（tools/ci/**、docs/**、任务书点名文件）；不碰 app/runtime 产品源码；`.github/workflows/**` 仅 AVF 对齐确需时可最小改（本票未触碰）。

## 1. 任务目标

1. 按积压 P2-3/P2-4 的建议落地零设备可执行的部分：优先接入仓库已有工具链（Android Lint），不引入重量级新依赖；detekt/ktlint 若成本超一会话则只做接入准备+报告；所有新门本地可跑且当前代码可绿。
2. 对 `test_avf_device_mode` 24 项 Windows 宿主红做三选一分诊：查 CI（近三次 push 的 discover 调用面）后落 (a) 宿主守卫 / (b) 报告不修 / (c) 记录覆盖缺口并建议接门。
3. 验证：`check_ci_module_coverage.py` 232 PASS 不破；新门本地全绿。

## 2. 实际完成

### 2.1 P2-3 Kotlin/全仓静态检查 —— 按任务书"优先已有工具链"落地，detekt/ktlint 转接入准备

工具链现状核查（证据先行）：

- Android Lint 已在 CI 门禁内：`ci.yml:208` 执行 `./gradlew :app:lintDebug :app:assembleDebug -Porchestella.assets.allowMissingPayload=true`，`app/build.gradle.kts:412` 起的 `lint { abortOnError = true }` 意味着**"对新增 error 保持 fail"的门已经存在**（本地与 CI 同款命令）。
- 本地实测基线复现：`./gradlew :app:lintDebug -Porchestella.assets.allowMissingPayload=true` → **830 warnings / 0 errors**（与积压记载一致；无 QEMU payload 的本地检出必须带该豁免 flag，首次尝试不带时被 `:app:verifyRuntimeAssets` fail-fast 拦截）。
- Kotlin 面：全仓 55 个 `.kt` 全部在 `:app`，Java 716 个——detekt/ktlint 只覆盖约 7% 源文件；Java 侧已有 `javac -Xlint:all -Werror` 严格契约（根 `build.gradle.kts` 的 P3-4 注释段）。
- detekt 最新稳定版实测（maven-metadata `<latest>`）：**1.23.8**，其内嵌编译器前端落后于本仓 Kotlin 2.2.21，2.x 尚未稳定——对最新 Kotlin 语法存在解析兼容风险，需实证调参。

落地内容（三项）：

1. **`app/lint.xml` 噪声收敛（P2-3 主落地）**：830 warnings 中 797 项属于样式/排版/UI 一致性/资源引用类（HardcodedText 299、SetTextI18n 172、UnusedResources 140、SmallSp 74、UseKtx 28、ObsoleteSdkInt 25、UseCompatTextViewDrawableXml 18 等 17 类），降为 `informational`（全量 HTML/XML 报告仍可见、可逐类回升）；正确性/安全/a11y/供应链类（DefaultLocale、ContentDescription、ClickableViewAccessibility、StaticFieldLeak、InflateParams、SetJavaScriptEnabled、PrivateApi、NewerVersionAvailable、DataExtractionRules 等）保持默认 warning。收敛后 **33 Warning + 797 Hint + 0 Error**。AGP 自动读取默认路径 `app/lint.xml`，**零 build 脚本改动**；`abortOnError=true` 未动，0-error 门不红。
2. **`.editorconfig`（积压 P2-3 明文第一步"`.editorconfig` 先行（见 P3-3）"）**：4 空格缩进（Kotlin/Java/Python/Shell/XML/Gradle）、YAML 2 空格、LF 结尾，与 `.gitattributes` 对齐；显式保护四类例外（`app/src/main/assets/**` CRLF 字节级校验线格式、`tools/fastlane-build/*.patch` 空白上下行 wire format、`*.md` 尾随空格换行语义、`*.{bat,cmd}` CRLF）。当日零构建行为变更，未来 ktlint 直接消费。
3. **AGENTS.md 新增"零设备本地验证命令"节**：记录 lint 豁免 flag（此前只散落历史报告，本地零设备跑 lint 必踩 verifyRuntimeAssets 红点）、AVF discover 的 `AVF_VALIDATION_TEST_ROOT` 要求与 Windows skip 语义、覆盖门跑法。

### 2.2 P2-4 供应链 —— 前半已由既有票完成，后半需维护者裁定（接入准备+报告）

- **`uses:` pin SHA**：**已完成，非本票工作**。本仓 7 个 workflow 实测 `grep "uses:"` 共 30 处（ci.yml 6、release.yml 6、fastlane-zero-device.yml 10、fastlane-dv01b-zero-device.yml 4、guest-assets.yml 2、sensitive-data-guard.yml 2），全部为 `SHA # 版本注释` 形态；`docs/reports/tickets/ci-hardening-20261104/implementation-report.zh-CN.md` §1.2 记录了 28 处新 pin + 2 处既有核对的全过程。积压 P2-4"现状证据"行（"约 30 处 uses: 全部 tag pin"）**已过时**，见 §9。
- **Gradle 依赖锁定**：`gradle/verification-metadata.xml` 不存在、全部 build 脚本无 `dependencyLocking`（实测）。**不落地理由**：积压明示"需裁定：是（锁定策略与镜像）"、`verification-metadata.xml` 生成"单独一个维护者会"；启用 `dependencyLocking` 并写 lockfile 会改变所有并行 lane 的依赖解析行为，本 lane 不应单方面引入。接入准备见 §3。

### 2.3 test_avf_device_mode 24 项 Windows 宿主红分诊 → **判定 (a)：CI 绿仅 Windows 红**

事实链（全部实测，非推断）：

1. **本地复现**：Windows Git Bash 下 `AVF_VALIDATION_TEST_ROOT=<tmp> python -B -m unittest discover -s tools/device-validation -p 'test_avf*.py'` → 197 tests，**23 failures + 1 errors = 24 红**，全部集中在 `DeviceCliTests`；`test_avf_qa_staging.py`/`test_avf_tools.py`/`ProbeArgumentTests` 均绿。
2. **失败机理**：`DeviceCliTests` 的 fake adb 是生成到临时目录的 Python 脚本，靠 `#!/usr/bin/env python3` shebang + `chmod(0o755)` + 直接子进程 spawn（`avf_device_mode.py:258 subprocess.run`）执行——纯 POSIX 机制，Windows 无法把无扩展名文本文件当可执行 spawn，属环境性失败，与被测逻辑无关。
3. **CI 证据（近三次 push 的 CI workflow run，均含 `ci.yml:82` 的 `python3 -B -m unittest discover -s tools/device-validation -p 'test_avf*.py'`）**：
   - `41907426814`（2026-11-04，main，整体 success）："AVF device-validation tool tests and dry-run" 步骤 **success**；
   - `41904336349`（整体 failure）：该 AVF 步骤仍 **success**，红点在 "Run all runtime checks and app unit tests"（gradle 步骤，即上轮已修的排队测试 flake）；
   - `41742799221`（整体 failure）：同上，AVF 步骤 **success**，红点同在 gradle 步骤。
   - runner 为 `ubuntu-24.04`（ci.yml:26）。→ 这些测试在 CI **运行且绿**。

处置：`DeviceCliTests` 加类级守卫 `@unittest.skipUnless(os.name == "posix", "...注明原因...")`，与仓库既有宿主分支范式同款（`test_avf_qa_staging.py:223`、`test_avf_tools.py:270` 均用 `os.name` 判定并 `skipTest`/分支），守卫文案写明 shebang+executable-bit 机理与"CI ubuntu 全量运行且绿"。

## 3. detekt/ktlint 配置成本评估（接入准备，本票不落地）

积压 P2-3 原案顺序：`.editorconfig` 先行 → ktlint 格式门 → detekt + baseline 只拦新增。第一步已落地（§2.1.2）；后两步评估：

| 因素 | 事实 |
|---|---|
| 覆盖面 | detekt/ktlint 仅覆盖 55/771 源文件（约 7%），Java 主力侧已有 javac 严格契约 + Android Lint |
| CI 强制力 | 新门接 CI 必须改 `.github/workflows/**`——本 lane 禁改（其他 lane 已清空冲突面）；积压本身标"是否阻断 CI"需裁定 |
| 版本兼容 | detekt 稳定版 1.23.8 内嵌旧编译器前端 vs 仓 Kotlin 2.2.21，需实证解析兼容（试跑+可能调 languageLevel）；2.x 未稳定 |
| 冲突面 | 根 `build.gradle.kts` / `gradle/libs.versions.toml` 是共享冲突面，并行 lane 期间改动需编排裁定 |
| 工作量 | 顺利路径约 2–4 个 gradle 命令 + 每模块 baseline；不顺利路径（语法解析失败/规则红）会吞掉一个会话 |

结论：按任务书"超过一会话工作量就只做接入准备+报告"执行。建议由拥有 workflows 修改权的 lane 或维护者会一并落地（含"是否阻断 CI"裁定）。已交付的接入准备：`.editorconfig`（ktlint 直接消费）+ 本节评估 + 0-error lint 门保持。

## 4. 未完成 / 与预期偏差

1. P2-3 的 ktlint/detekt 实体接入：未落地——评估见 §3，属任务书明示允许的"接入准备+报告"路径。
2. P2-4 的 Gradle 依赖锁定：未落地——需维护者裁定（锁定策略与镜像），§2.2 有现状与材料。
3. 无其他偏差。

## 5. 修改的模块

| 文件 | 变更 | 域归属 |
|---|---|---|
| `tools/device-validation/test_avf_device_mode.py` | +7 行：`DeviceCliTests` 类级 POSIX 守卫 | 本 lane 允许域 |
| `app/lint.xml` | 新增 42 行：17 个样式类降 informational（文件头写明政策） | lint 配置，非产品源码 |
| `.editorconfig` | 新增 37 行：仓库级格式基线 + 例外保护 | 仓库根 |
| `AGENTS.md` | +6 行："零设备本地验证命令"节 | 仓库根 |
| `docs/reports/tickets/static-checks-p2/implementation-report.zh-CN.md` | 本报告（`git add -f`，docs/ 默认 ignore） | 本 lane 允许域 |

未触碰（越界检查）：app/runtime 产品源码（0 行）、`.github/workflows/**`（0 行）、`tools/ci/**`（0 行，本票无需改动）、其他 lane 文件。

## 6. 测试

- 契约测试：不涉及产品源码，无新增。
- JVM 测试：不涉及产品源码，无新增。
- 工具/脚本单测（Windows 宿主 Git Bash 实跑）：
  - `test_avf*.py` discover（守卫后）：**197 tests, OK (skipped=28)**——`DeviceCliTests` 28 项全 skip（其中 24 项即 Windows 红项，4 项 Windows 本可绿、随类级守卫一并 skip），其余类 0 失败；verbose 逐项核对 skip 全部来自本守卫，无其他类被意外 skip；
  - `test_dv_*.py` discover：**79 tests, OK**；
  - `run_avf_device_validation.py --dry-run`：**exit=0**，status NOT_TESTED（CI 同款命令不受守卫影响）；
  - `tools/ci/test_check_ci_module_coverage.py`：**12 tests, OK**；`tools/ci/check_ci_module_coverage.py`：**SUMMARY: 232 passed, 0 failed**（基线保持）；
  - `tools/ci/check_sensitive_data.py`：**SENSITIVE_DATA_OK**（每个 commit 后复跑）。
- 冒烟清单：真机验证项——无（零设备票）。

## 7. Benchmark / 实测数据

- Android Lint 收敛前后（同一检出、同一命令）：830 Warning / 0 Error → **33 Warning + 797 Hint / 0 Error**（`app/build/reports/lint-results-debug.xml` severity 统计），耗时同为秒级增量（gradle 配置缓存命中后 lint 任务本体约 1–2 分钟）。
- 本票不涉及运行时性能基准。

## 8. 风险

1. **lint.xml 对新代码同样降级样式类**：新代码的 HardcodedText 等不再计入 headline warning 数。缓解：全量 HTML/XML 报告仍逐项可见（informational 非忽略），文件头注明可逐类回升；正确性/安全/a11y/供应链类全部保持原级，0-error 门（`abortOnError`）未动。
2. **DeviceCliTests 在 Windows 宿主 skip**：该套件覆盖责任由 ubuntu CI 承担（守卫文案已写明）。若未来把 fake adb 改造成跨平台形态（如按平台生成 `.cmd`/`.bat` 包装器或经 `sys.executable` 显式 spawn），可移除守卫恢复 Windows 覆盖。
3. **.editorconfig 与个别文件既有格式冲突**：编辑器重排可能产生噪声 diff。缓解：四类例外节显式保护线格式/patch/审计敏感文件；git 层另有 `.gitattributes` 兜底，构建脚本不解析该文件。

## 9. 新发现（供编排层分诊，不自行修改）

1. **积压 P2-4"现状证据"已过时**："约 30 处 uses: 全部 tag pin"实为已 SHA pin（ci-hardening-20261104 完成）——建议积压文档回写该行，P2-4 只剩 Gradle 依赖锁定一项。
2. **本地零设备 lint 的 `allowMissingPayload` flag 未在 agent 入口文档记载**（本票已补入 AGENTS.md）；首次不带该 flag 会撞 `:app:verifyRuntimeAssets` fail-fast，报错文案本身给出了 flag，但对新 agent 仍是盲区。
3. **DeviceCliTests 存在 Windows 可恢复路径**：fake adb 若改为跨平台 spawn 形态即可恢复 Windows 覆盖，属可选增强。

## 10. 对后续 ticket 的影响

1. 接手 P2-3（ktlint/detekt）的票：先读 §3 评估表；需要 workflows 修改权或维护者裁定"是否阻断 CI"；`.editorconfig` 已就绪可直接消费。
2. 接手 P2-4 依赖锁定的票：维护者会材料可直接用 §2.2（现状：无 verification-metadata.xml、无 dependencyLocking；仓库有 aliyun 镜像 opt-in 属性 `orchestella.repositories.useAliyunMirror`，锁定策略需与镜像策略一并裁定）。
3. Windows 宿主跑 device-validation discover 的 agent：`DeviceCliTests` 28 项 skip 属预期（AGENTS.md 已记），"OK (skipped=28)" 即全绿。

## 11. Commit

分支 chore/static-checks-p2（基线 2d66f67）：

- `c10a9de` test(device-validation): DeviceCliTests 加 POSIX 宿主守卫——对齐 Windows 宿主红与 ubuntu CI 绿
- `0217e62` chore(lint): P2-3 噪声收敛——app/lint.xml 样式类降 informational，830→33 warnings
- `acec63a` chore(format): P2-3 第一步（P3-3 同款）落地 .editorconfig 仓库级格式基线
- `5d72e6c` docs(agents): AGENTS.md 接入零设备本地验证命令——lint/AVF discover/覆盖门
- 报告 commit：见下方追加（docs(reports): static-checks-p2 实施报告）

## 12. Ticket 状态

**completed**（按任务书口径）。P2-3：已有工具链路径（lint 噪声收敛 + 0-error 门确认）落地，detekt/ktlint 出具成本评估转接入准备（任务书明示允许）；P2-4：uses pin 确认已完成、依赖锁定转维护者裁定（积压本就标注需裁定）；test_avf_device_mode：三选一判定 **(a)** 并落地宿主守卫，本地 discover 全绿、CI 语义不变。

报告人：ZCode agent（GLM-5.3-Flash）｜ 日期：2026-11-05 ｜ 耗时：单 lane 会话
