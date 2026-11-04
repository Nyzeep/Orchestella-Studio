# Ticket ci-verify-runtime-assets 实施报告

- 票号/标题：main 分支 `CI` workflow `:app:verifyRuntimeAssets` 连红修复（Lane A）
- 执行 session / 模型 / thinking：Lane A CI 修复智能体 / GLM-5.3-Flash（ZCode agent）
- 分支：`fix/ci-verify-runtime-assets`　基线 commit：`b39e8e6`

## 1. 任务目标

定位并修复 main 分支 GitHub Actions `CI` workflow 连续红：run 41683970846 与
41674445819 均失败在 `> Task :app:verifyRuntimeAssets FAILED`。给出最小且正确的
修复，如实验证，产出报告。

## 2. 背景与根因分析（逐证据）

### 2.1 失败现场（CI 完整 gate 日志）

用 `gh run download 41683970846 -n gradle-gate-41683970846` 取回完整日志，准确报错：

```
> Task :app:verifyRuntimeAssets FAILED
verifyRuntimeAssets: The QEMU runtime payload in this checkout is incomplete or does not match app/src/main/assets/runtime-artifacts-v1.sha256.
verifyRuntimeAssets:   - missing files: Image (app/src/main/assets/Image), initramfs.cpio.gz (app/src/main/resources/initramfs.cpio.gz), lib/arm64-v8a/libdroidlayer_qemu.so (app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so)
```

该 job 的 gate 任务清单是 14 个 `:runtime:*:check` + `:app:testDebugUnitTest`，
并未显式请求 `:app:verifyRuntimeAssets`——它是被依赖链拉进来的。

### 2.2 依赖链

`app/build.gradle.kts:854-856`：

```kotlin
tasks.named("preBuild") {
    dependsOn(verifyRuntimeAssets)
}
```

`:app:testDebugUnitTest`（乃至任何 app 模块任务）经 variant 链拉起
`:app:preBuild` → `:app:verifyRuntimeAssets`。用 `:app:testDebugUnitTest --dry-run`
确认任务图包含 `:app:preBuild SKIPPED`、`:app:verifyRuntimeAssets SKIPPED`、
`:app:compileDebugKotlin SKIPPED`、`:app:testDebugUnitTest SKIPPED`，论断成立。

### 2.3 payload 为何必缺

三件 payload（`app/src/main/assets/Image`、`app/src/main/resources/initramfs.cpio.gz`、
`app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so`）全部 git-ignore
（本 worktree 实测三个路径均不存在），干净 checkout 的 CI 永远没有。
任务自身的 fail-fast 行为是 46ce4e5 有意引入的（Q1「构建期缺件 fail-fast」），
并配套两条显式豁免：外部载荷模式 `-Porchestella.qemu.*` 与
`-Porchestella.assets.allowMissingPayload=true`（clean checkout 场景，warn +
写 `app/build/runtime-assets-missing.flag`）。

### 2.4 根因：豁免漏加两处

46ce4e5 的提交说明写明「ci.yml / release.yml 三处干净 checkout 构建加豁免」，但实际
只覆盖了 ci.yml packaging job（`:app:lintDebug :app:assembleDebug`）与 release.yml
两处。全 workflow 扫描 `gradlew` 调用逐一核对：

| workflow / 位置 | 调用 | 豁免 | 结论 |
| --- | --- | --- | --- |
| ci.yml test job gate（:124） | 14 个 `:runtime:*:check` + `:app:testDebugUnitTest` | **无** | 本次红的直接根因 |
| ci.yml packaging（:202） | `:app:lintDebug :app:assembleDebug` | 有 | 正常 |
| ci.yml accel-extension（:235） | `:accel-extension:assembleDebug` | 不适用（preBuild 挂接仅在 :app） | 正常 |
| release.yml（:142、:150） | lint/assemble | 有 | 正常 |
| qemu-android.yml（:371） | `:app:testDebugUnitTest ...` | 外部载荷 `-Pdroidlayer.qemu.*`（Exemption 1 分支） | 正常 |
| fastlane-zero-device.yml（:252） | `:app:assembleDebug :app:verifyFastlaneToolboxApk` | **无** | 同根因第二处雷区 |
| fastlane-dv01b-zero-device.yml（:94） | `:runtime:engine-fastlane:test` | 不适用（非 :app 模块） | 正常 |

fastlane-zero-device.yml 的 run 41683970351（b39e8e6）、41606391990（46ce4e5）已实测
红在同一报错（`gh run view 41683970351 --log-failed` 逐字同上）。

### 2.5 时间线（`gh run list --branch main --workflow CI`）

| run | head | 结论 | 时间（UTC） |
| --- | --- | --- | --- |
| 41597643488 | 24aef0e | success | 2026-11-03 07:38 |
| 41606391888 | 46ce4e5 | failure | 2026-11-03 08:51（首红） |
| 41674445819 | 777827e | failure | 2026-11-03 16:27 |
| 41683970846 | b39e8e6 | failure | 2026-11-03 17:24 |

转红点与 46ce4e5 落地时间精确吻合。备注：工单原文称 41674445819 对应 803f097，
gh 实测 head 为 777827e（803f097 的 push run 被 concurrency 取消未出现在列表），
不影响结论——三连红失败点一致。

### 2.6 本地为何绿

主检出本地 gradle gate（`build/ci/gradle-gate.log`）BUILD SUCCESSFUL，因为本地
checkout 已 stage 了真实 payload（git-ignore 文件存在于工作区），任务集差异
（`:app:assembleDebug` 等）只是次要表现；CI 红的本质不是任务清单不同，而是
干净 checkout 必缺 payload 而豁免未加。

## 3. scope 与实际完成

- ✅ 取回并分析 run 41683970846 完整 gate 日志，锁定准确报错文本。
- ✅ 确认依赖链 `:app:testDebugUnitTest` → `:app:preBuild` → `:app:verifyRuntimeAssets`（dry-run 证据）。
- ✅ 全量核对 7 个 workflow 的 gradle 调用与豁免状态，确认根因是 46ce4e5 漏加豁免两处。
- ✅ 最小修复：在 ci.yml test job gate 与 fastlane-zero-device.yml assemble 步骤补
  `-Porchestella.assets.allowMissingPayload=true`，与 packaging gate / release.yml
  既有范式一致；发布载荷门禁未放松（真实 APK 仍由 release workflow 的
  `check_runtime_payload.py --apk` 兜底）。
- ✅ worktree 内本地复现红 → 豁免转绿 → gate 脚本转发验证（详见第 6 节）。

## 4. 未完成 / 与预期偏差

- ⚠️ 完整 15 任务 gate 在 hosted CI（ubuntu-24.04）上的绿转无法在本地证明：
  hosted CI 的完整验证边界是推分支后由 GitHub Actions 确认（本 worktree 不 push，
  属工单约束）。本地已验证失败路径与豁免路径两端，中间路径（其他 14 个
  `:runtime:*:check` 在 CI 的表现）与本票根因无关、在 46ce4e5 之前的同清单是绿的。
- 其余：无。

## 5. 修改的模块

| 文件 | 改动 |
| --- | --- |
| `.github/workflows/ci.yml`（+6 行） | test job gate 步骤首参数补 `-Porchestella.assets.allowMissingPayload=true` + 4 行注释说明同 packaging gate 的 clean-checkout 处境 |
| `.github/workflows/fastlane-zero-device.yml`（+5 行） | 「Assemble debug APK with bin/ packaged before signing」步骤补同一豁免 + 注释 |

未触碰 app/build.gradle.kts / 生产代码 / gradle.properties（把属性写进
gradle.properties 会全局永久关闭门禁，是错误做法，未采用）。

## 6. 测试与验证结果（真实输出）

全部在 orch-lane-ci worktree（ASCII 路径）（payload 与 CI checkout 同构缺失）执行：

1. **复现 CI 红**（未加豁免）：
   `./gradlew :app:verifyRuntimeAssets --no-daemon`
   → `> Task :app:verifyRuntimeAssets FAILED`，报错文本与 CI 日志逐字一致
   （missing files: Image / initramfs.cpio.gz / libdroidlayer_qemu.so），
   `BUILD FAILED in 45s`。本地复现成立。
2. **依赖链确认**：`./gradlew :app:testDebugUnitTest --dry-run --no-daemon`
   → 任务图含 `:app:verifyRuntimeAssets SKIPPED`、`:app:preBuild SKIPPED`。
3. **豁免转绿**：`./gradlew :app:verifyRuntimeAssets -Porchestella.assets.allowMissingPayload=true --no-daemon`
   → `BUILD SUCCESSFUL in 11s`，输出显式
   `WARNING: runtime payload verification is EXPLICITLY DISABLED`，并实测写出
   `app/build/runtime-assets-missing.flag`。
4. **workflow 调用形态端到端验证**：
   `bash tools/ci/run-gradle-gate.sh -Porchestella.assets.allowMissingPayload=true :app:verifyRuntimeAssets`
   → `GRADLE_GATE_EXIT=0`、`FAILED_TASKS: (none reported)`，证明 gate 脚本把
   `-P` 属性原样转发给 gradlew（workflow 中属性置于任务名之前合法）。
5. **CI 自带校验不受影响**：`python tools/ci/check_ci_module_coverage.py`
   → `SUMMARY: 232 passed, 0 failed`；
   `python tools/ci/test_check_ci_module_coverage.py` → `Ran 12 tests ... OK`。
   两个脚本会解析 ci.yml，本次 YAML 改动通过其校验。

诚实声明：未跑完整 15 任务 gate（工单约束并行期 gradle 任务 ≤3 分钟，
本地冷缓存跑 testDebugUnitTest 无法保证时限）；未在 hosted CI 验证（不 push）。
以上 1–5 均为实际运行结果，无虚报。

## 7. Commit / Branch

- 分支：`fix/ci-verify-runtime-assets`（基于 main `b39e8e6`）
- commit：`cae6c36` fix(ci): 干净 checkout 的 :app 门禁补 allowMissingPayload 豁免——修 CI verifyRuntimeAssets 连红
- 报告 commit：见本文件所在提交（`git add -f` 入库 docs/ 被忽略目录）

## 8. 风险

- 该豁免使 test job / fastlane-zero-device 产出的 debug APK 可能携带
  runtime-artifacts-v1.sha256 而无实际 payload——与 packaging gate、release.yml
  既有豁免同等风险面，且发布路径的 `check_runtime_payload.py --apk` 兜底未动。
  任务在豁免分支输出显式 WARNING 并落 flag 文件，可审计。
- 未采用 gradle.properties 全局豁免（会永久关闭所有环境的门禁），风险面未扩大。
- 本票未修改 verifyRuntimeAssets 本身的逻辑；46ce4e5 注释提到 manifest 七条目中
  NOTICE.QEMU / QEMU-GPL-SOURCES.md 两条因 e16af2c 起漂移未被校验——为已知现状，
  非本票引入，未改动。

## 9. 后续动作

- 推送 `fix/ci-verify-runtime-assets` 至远端开 PR，确认 `CI` 与
  `Fast Lane zero-device` 两条 workflow 转绿（本票的最终验证步骤）。
- 可选加固（供分诊，不属本票）：给 verifyRuntimeAssets 的豁免分支在 CI 环境输出
  step summary 或考虑以 `CI` env 变量做显式断言，进一步收敛「本地绿 CI 红」的
  认知差；以及补齐 manifest 七条目的全量校验（46ce4e5 注明的遗留漂移）。

## 10. 是否越界

未越界。改动仅限 2 个 workflow 文件的豁免补齐；未触碰主检出、未 push、未改
gradle.properties / 生产代码 / AGENT 配置；fastlane-zero-device.yml 属同一根因的
实测红 run（41683970351、41606391990），修复其一即同票范围，已在报告中显式申报。

## 11. 是否触发 Architecture Change Policy

未触发。无生产代码改动、无模块边界变化、无深模块接口变更，仅 CI workflow
参数补齐（既有豁免机制的正确使用）。
