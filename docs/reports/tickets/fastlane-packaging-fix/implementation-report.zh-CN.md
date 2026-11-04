# fastlane-packaging-fix 实施报告（Fast Lane 修复 Lane B）

- 票号/标题：fastlane-packaging-fix——main 两条 Fast Lane workflow 红转绿
- 执行 session / 模型 / thinking：Lane B（Fast Lane 修复智能体）/ GLM-5.3-Flash / thinking
- 分支：`fix/fastlane-packaging`　基线 commit：`b39e8e6`
- 工作区：`orch-lane-fastlane`（纯 ASCII git worktree，未触碰主检出）

## 1. 任务目标

排查并修复 main 分支两条 Fast Lane workflow 的失败：

1. run `41683970351`（Fast Lane zero-device，head `b39e8e6`）——复核哪些 job 属于
   `verifyRuntimeAssets` 缺件 fail-fast 同根因（Lane A 已判定 assemble 步骤同根因），
   排除后找其余独立失败点，并对 fastlane workflow 补同款豁免属性。
2. run `41674445859`（Fast Lane DV-01B zero-device）——逐 job 分类：确定性回归 vs
   已知 flake vs 设备依赖门；确定性回归给最小修复，flaky 做稳健性修复，设备门不改语义只记录。

不改动 `ci.yml`（Lane A 分支 `fix/ci-verify-runtime-assets` 负责，本票未触碰）。

## 2. 实际完成

### 2.1 run 41683970351（Fast Lane zero-device · b39e8e6）

全量 `--log` 逐 job 复核结论：6 个 job 中 5 绿 1 红，唯一红 job 的失败即 Lane A
判定的同根因，**排除该根因后无其余独立失败点**。

| job | 结果 | 判定 |
|---|---|---|
| Every Fast Lane JVM module | ✅ 绿 | 正常 |
| Toolbox, zip gates, Python lanes and licenses | ✅ 绿 | 正常 |
| distccd arm64 build, reproducibility, ELF and license gates | ✅ 绿 | 正常 |
| Payload NDK/CMake build (P02 source required) | ✅ 绿 | 正常 |
| Payload host build, CTest and FLN1 E2E | ✅ 绿 | 正常 |
| APK bin/ injection and 16 KB packaging | ❌ 红 | **确定性回归（同根因）** |

红 job 失败步骤 `Assemble debug APK with bin/ packaged before signing`，首个错误：

```
> Task :app:verifyRuntimeAssets FAILED
* What went wrong:
Execution failed for task ':app:verifyRuntimeAssets'.
> QEMU runtime payload check failed.
    - missing files: Image (app/src/main/assets/Image),
      initramfs.cpio.gz (app/src/main/resources/initramfs.cpio.gz),
      lib/arm64-v8a/libdroidlayer_qemu.so (app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so)
```

### 2.2 run 41674445859（Fast Lane DV-01B zero-device）

事实更正：该 run 实际 head SHA 为 **`777827e`**（GitHub API
`actions/runs/41674445859` 实测），不是任务情报中写的 `803f097`——`803f097`
未触发 DV-01B workflow（paths 未命中），无对应 run。历史情报（DV-01 被 A16
`execute_no_trans` 设备门阻塞、Contract & Unit Tests 的 AVF 模拟 socket flake 与
FastLaneProxyServerTest flake）在本 run 中均未出现，与本 run 无关（详见 §9）。

| job | 结果 | 判定 |
|---|---|---|
| Schema validation and sanitization tests | ✅ 绿 | 正常 |
| engine-fastlane JVM and androidTest compilation | ❌ 红 | **确定性回归（同根因，非 flake、非设备门）** |

红 job 失败步骤 `Run engine-fastlane tests and compile androidTest sources`。
日志关键事实：`:runtime:engine-fastlane:test` **已执行且通过**（任务行无 FAILED
标记，无任何测试失败输出），失败发生在其后按依赖拉起的 `:app` 任务链——
`:app:compileDebugAndroidTestJavaWithJavac` → `:app:preBuild` →
`:app:verifyRuntimeAssets` FAILED，缺件清单与 run 41683970351 完全一致。
DV-01B workflow 本身零设备 job（workflow 头注释明确 never runs a device bridge
command），本 run 不涉及设备依赖门，A16 设备门状态不受本票影响、亦未被本 run 行使。

### 2.3 根因定位（对照近期 commit）

- 引入点精确锁定为 **`46ce4e5`**（feat(quality) Q1 构建期缺件 fail-fast）：新增
  `:app:verifyRuntimeAssets` 挂 `preBuild`，对 Image / initramfs.cpio.gz /
  libdroidlayer_qemu.so 三件缺件 fail-fast。该 commit 给 `ci.yml`（1 处）与
  `release.yml`（2 处）补了 `-Porchestella.assets.allowMissingPayload=true` 豁免，
  **但漏了 `fastlane-zero-device.yml` 与 `fastlane-dv01b-zero-device.yml`**——两条
  workflow 内的 `:app` 调用点由此在干净 checkout 上必然红。
- `777827e`（PVM distcc E2E/worker 池）与 `803f097`/`b39e8e6` 未引入新失败点：
  run history 显示两条 workflow 在 `e130897` 绿、`46ce4e5` 起红，之后各 run 的
  红均为同一根因延续。
- 三件 payload 是 git-ignored 构建产物（build.gradle.kts 注释明确），干净 checkout
  必然缺件，fail-fast 是按设计触发，属于"豁免面漏配"而非校验逻辑错误。

### 2.4 修复内容（最小修复，两文件各一处）

与 46ce4e5 在 ci.yml/release.yml 已有豁免完全同款：

1. `.github/workflows/fastlane-zero-device.yml` `apk-bin-gate` 的 assemble 步骤：
   gradlew 命令追加 `-Porchestella.assets.allowMissingPayload=true`（置于既有
   `-Porchestella.fastlane.toolboxDir` 之后、`--no-daemon` 之前），并加注释说明
   豁免语义（host-only 门、warn + 写 `build/runtime-assets-missing.flag`）。
2. `.github/workflows/fastlane-dv01b-zero-device.yml` `jvm-androidtest` 的 gradle
   步骤：同一属性追加至 `:app:compileDebugAndroidTestJavaWithJavac` 之后、
   `--no-daemon` 之前，注释置于 `run:` 之前（折叠标量内不能放注释）。

两个 job 均为宿主门：不启动 QEMU、不发布载荷 APK（zero-device workflow 头注释
自我约束），豁免只跳过"干净 checkout 缺件即失败"这一检查，warn 与 flag 文件留痕，
发布侧 `check_runtime_payload.py --apk` 兜底不动，未放松任何发布门禁。
除此之外无其他修改：未改 `ci.yml`、未改 `app/build.gradle.kts`、未对任何测试加
`@Ignore`（本 run 中无 flake 需要稳健性修复）。

## 3. 未完成 / 与预期偏差

- `apk-bin-gate` 的 `:app:verifyFastlaneToolboxApk` 步骤未在本地单独复跑：该门校验
  APK 内 bin/ 注入内容，需先跑 toolbox 构建（python 构建链），与本修复的失败点
  （verifyRuntimeAssets）正交；本地已验证 `:app:assembleDebug` 打包链带豁免全绿。
  其余无偏差。

## 4. 修改的模块

| 文件 | 变更 |
|---|---|
| `.github/workflows/fastlane-zero-device.yml` | apk-bin-gate assemble 步骤 +5 行（注释 4 行 + 豁免属性 1 行） |
| `.github/workflows/fastlane-dv01b-zero-device.yml` | jvm-androidtest gradle 步骤 +6 行（注释 5 行 + 豁免属性 1 行） |

未触碰 forbidden 域：`ci.yml`、`app/build.gradle.kts`、生产代码、测试代码均无改动。

## 5. 重要设计决策

- **复用 46ce4e5 既定豁免机制而非绕过**：两条 workflow 属"PR 门/干净 checkout 不应
  携带载荷"的显式豁免场景（build.gradle.kts 豁免 2 的设计初衷），与 Lane A 对
  ci.yml 的处理同构，保持全仓 CI 豁免面一致。
- **DV-01B 仅补编译门豁免，不拆任务**：`engine-fastlane:test` 与 androidTest 编译
  同步跑在一步内，前者全绿，无需拆分或重排。
- 未引入新机制、未触及 ADR 边界，不触发 Architecture Change Policy。

## 6. 测试

本票为 CI workflow 修复，无新增测试代码；验证方式为在 worktree 内对失败任务做
正反两向复现 + 修复后命令端到端复跑（非 `--tests` 单测类——失败点是 Gradle 任务
而非单测断言，忠实复现对象是任务级命令）：

| 验证 | 命令 | 结果 |
|---|---|---|
| 反向复现（等价 CI 失败） | `./gradlew :app:verifyRuntimeAssets` | ❌ BUILD FAILED in 5s，`QEMU runtime payload check failed`，缺件清单与 CI 日志逐字一致 |
| 正向豁免 | `./gradlew :app:verifyRuntimeAssets -Porchestella.assets.allowMissingPayload=true` | ✅ BUILD SUCCESSFUL in 4s，warn 输出 + `app/build/runtime-assets-missing.flag` 写入（含 reason 字段） |
| DV-01B 修复后全命令 | `./gradlew :runtime:engine-fastlane:test :app:compileDebugAndroidTestJavaWithJavac -Porchestella.assets.allowMissingPayload=true` | ✅ BUILD SUCCESSFUL in 1m46s，engine-fastlane 测试结果 XML 实测 118 tests / 0 failed |
| apk-bin-gate 打包链（不含 toolbox 门） | `./gradlew :app:assembleDebug -Porchestella.assets.allowMissingPayload=true` | ✅ BUILD SUCCESSFUL in 48s |
| workflow 语法 | `python -c "import yaml; yaml.safe_load(...)"`（两文件） | ✅ 解析通过，job 结构完整 |

未运行（如实记录）：CI 上的完整 workflow 复跑（本票只 commit 不 push，push 后由
GitHub Actions 自然验证）；`:app:verifyFastlaneToolboxApk` 本地单跑（理由见 §3）。

## 7. Benchmark / 实测数据

本票不涉及。

## 8. 风险

- 豁免后的 debug APK 不含 QEMU 载荷（`runtime-artifacts-v1.sha256` 清单在而实物
  不在），该 APK 不能启动 QEMU lane——这是豁免机制的既定语义，zero-device 门不使用
  该 APK 启动任何实例，真实载荷仍由发布侧 `check_runtime_payload.py --apk` 兜底，
  风险不外溢。
- 若未来有人在 DV-01B / apk-bin-gate 的 gradle 命令里追加依赖真实载荷的任务
  （如 QEMU 冒烟），豁免会掩盖缺件——注释已写明豁免语义作为护栏。

## 9. 新发现

- **历史情报与本 run 的关系澄清（供 Orchestrator 分诊）**：AVF 模拟 socket flake、
  FastLaneProxyServerTest flake、A16 `execute_no_trans` 设备门三条历史记录均不在
  本两条 run 中复现（相关模块的宿主门在 41683970351 全绿；DV-01B 无设备 job）。
  设备门状态未被本票行使或改变，需设备窗口另行行使。
- 46ce4e5 的豁免面只覆盖了当时 diff 内的调用点（ci.yml/release.yml），没有全仓
  grep `:app:assembleDebug|:app:compile.*AndroidTest` 等可能拉起 `:app:preBuild` 的
  workflow 调用点——这类"fail-fast 新门 × 多调用点"改动建议后续用脚本枚举校验。
- `guest-assets.yml`、`qemu-android.yml`、`release.yml` 等非 fastlane workflow 未在
  本票 scope 内逐一排查豁免面（release.yml 已含豁免；其余两条属设备/载荷构建门，
  是否该豁免属语义决策，未自行处理）。

## 10. 对后续 ticket 的影响

- 分支合入后，两条 Fast Lane workflow 应转绿；`fix/ci-verify-runtime-assets`
  （Lane A，修 ci.yml）与本票互不冲突，可并行合入。
- 若后续合入顺序导致两条分支 diff 冲突（不同文件，预计不会），以各自 workflow
  文件为准。

## 11. Commit

- 分支 `fix/fastlane-packaging`（基于 main `b39e8e6`）：
  - `91f5726` fix(fastlane): 两条 fastlane 零设备 workflow 补缺件豁免——修复 46ce4e5 verifyRuntimeAssets fail-fast 误伤
  - （本报告以 `git add -f` 单独提交）

## 12. Ticket 状态

建议：**completed**。两条 run 的全部失败 job 均定性为同一确定性回归
（46ce4e5 豁免面漏配），最小修复已落地并在 worktree 内正反两向验证；无 flake
掩盖（未用 @Ignore）、无设备门语义改动、未越界（未触碰 ci.yml 与主检出）。
