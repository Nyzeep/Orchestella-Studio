# Q4 依赖与版本目录显式化

Status: resolved
Type: task
Blocked by: 无
Effort: .scratch/quality-round-20261103/

## 背景

`MainActivity.kt` 用了 `kotlinx.coroutines`（`import` 在 `:37-39`，`Dispatchers.Main` 用在 `:1405,1409`），但 `app/build.gradle.kts` 从未声明，靠 androidx 传递引入：`appcompat 1.7.0 → savedstate 1.2.1 → lifecycle-common 2.6.2` 的 POM 直接声明 `kotlinx-coroutines-android:1.6.4`。依赖升级即可能编译失败或拿到未固定版本（历史报告 P2.9 同项）。

同时 `app/build.gradle.kts` 里 shizuku / hiddenapibypass / desugar / buildTools / ndk 全是硬编码字符串，且 `36.1.0` 在**同一文件出现两次**（`:149` 的 `buildToolsVersion` 与 `:576-577` 的 zipalign 查找路径），改一处漏一处会与 zipalign 路径不一致。

## 交付

- `gradle/libs.versions.toml`：
  - `[versions]` 追加 `coroutines = "1.6.4"`、`shizuku = "13.1.5"`、`hiddenapibypass = "6.1"`、`desugarJdkLibs = "2.1.5"`、`buildtools = "36.1.0"`、`ndk = "27.0.12077973"`。
  - `[libraries]` 追加 `coroutines-core`、`coroutines-android`、`shizuku-api`、`shizuku-provider`、`hiddenapibypass`、`desugar-jdk-libs`。
- `app/build.gradle.kts`：
  - `:149` `buildToolsVersion`、`:150` `ndkVersion` 改 `libs.versions.*.get()`。
  - `:471` `coreLibraryDesugaring` 改 `libs.desugar.jdk.libs`。
  - `:486`/`:487` shizuku、`:490` hiddenapibypass 改 catalog。
  - `:484-485` 之后新增 `implementation(libs.coroutines.core)` + `implementation(libs.coroutines.android)`（两条都写，显式化）。
  - `:576-577` zipalign 路径改用 `libs.versions.buildtools.get()`，与 `:149` 同源。
  - 删除 `:495` `androidTestImplementation(project(":runtime:fastlane-terminal"))`（与 `:483` 逐字重复）。
- `accel-extension/build.gradle.kts:168` `buildToolsVersion` 同步改 catalog（否则留下第二份 `36.1.0` 字面量）。
- **coroutines 必须钉 1.6.4**：这是当前唯一解析版本，行为零变化。`MainActivity` 用了 `Dispatchers.Main`，其实现由 `kotlinx-coroutines-android` 的 `META-INF/services/MainDispatcherFactory` 注入——只声明 `-core` 能编译但运行期抛 `IllegalStateException: Module with the Main dispatcher had failed to initialize`。

## 验收

- `./gradlew :app:assembleDebug` 绿；`MainActivity.kt` 编译通过。
- `app/build.gradle.kts` 内 `36.1.0` 与 `27.0.12077973` 字面量各只剩 0 处（全部走 catalog）；`accel-extension` 同理。
- `grep -c 'androidTestImplementation(project(":runtime:fastlane-terminal"))' app/build.gradle.kts` == 0。
- `./gradlew :app:testDebugUnitTest` 绿。
- `tools/release/check_release_fields.py` 仍能解析（`applicationId`/`versionCode`/`versionName` 未被 catalog 化）。

## 测试

- 无新增单测；验证面是构建 + 既有测试。
- `./gradlew :app:assembleDebug :app:testDebugUnitTest`（`X:` 盘）

## 不在范围

- 升级 coroutines 到 1.8/1.9（本机缓存只有 `-core-jvm` 有新版本，`-android` 只有 1.6.4，离线会失败）。
- `:491` `androidTestImplementation(engine-fastlane)`（属传递冗余而非逐字重复，删它属顺手改）。
- `:190` `cmake { version = "3.22.1" }`。
- 收敛 CI 里 `sdkmanager` 硬编码的 `build-tools;36.1.0` / `ndk;27.0.12077973`（Gradle 侧与 CI 侧仍是两份真值，属独立决策）。
- `guest-assets/build.gradle.kts`（未被主构建 include）与 `research/uml-runner`（独立 Groovy 构建）。

## Comments

2026-11-03 协调：建票（来源 `.scratch/quality-round-20261103/notes/B4-deps.md`）。

2026-11-03 Q4-impl：claimed。`coroutines` 钉 `1.6.4` —— 实测 `:app:dependencyInsight` 显示 core/core-jvm/android/bom 四件套均 1.6.4，唯一引入源是 `androidx.lifecycle:lifecycle-common:2.6.2` 的 POM；升级会变更行为且本机缓存无 `-android` 新版本，离线不可复现。

2026-11-03 Q4-impl：完成。
- `gradle/libs.versions.toml`：`[versions]` 加 `coroutines`/`shizuku`/`hiddenapibypass`/`desugar`/`buildtools`/`ndk`（均单词形式，保证访问器为 `libs.versions.xxx.get()`）；`[libraries]` 加六条。
- `app/build.gradle.kts`：`buildToolsVersion`/`ndkVersion`（`:151-152`）、`coreLibraryDesugaring`（`:473`）、shizuku api+provider 与 hiddenapibypass（`:488-499`）改 catalog；新增 `libs.coroutines.core` + `libs.coroutines.android` 两条并注释说明 `Dispatchers.Main` 依赖 `-android`；删除与 `implementation(project(":runtime:fastlane-terminal"))` 逐字重复的 `androidTestImplementation`（`:504`）；zipalign 路径（`:763-768`）改用 `libs.versions.buildtools.get()` 与 `buildToolsVersion` 同源。
- `accel-extension/build.gradle.kts:168` 同步 catalog。

2026-11-03 协调：门禁实跑（`X:` ASCII 路径，`--offline`）。
- `:app:assembleDebug` → `BUILD SUCCESSFUL in 5m 52s`（含 Q1 的 `verifyRuntimeAssets`）。
- `:app:testDebugUnitTest` → `BUILD SUCCESSFUL in 2m 53s`。
- `:accel-extension:assembleRelease` → `BUILD SUCCESSFUL in 59s`（45 tasks 全执行）。
- `:app:compileDebugAndroidTestJavaWithJavac` → `BUILD SUCCESSFUL in 1m 8s`（补验删除重复 `androidTestImplementation` 后 `FastLaneDv01Probe` 对 `runtime.fastlane.terminal.*` 的 import 仍可见——`assembleDebug` 不编译 androidTest，不补跑就漏了这条风险）。
- 残留检查：`grep -n '36\.1\.0\|27\.0\.12077973' app/build.gradle.kts accel-extension/build.gradle.kts` → exit 1（0 命中）；`grep -c 'androidTestImplementation(project(":runtime:fastlane-terminal"))' app/build.gradle.kts` → `0`。
- `python tools/release/check_release_fields.py` → `RESULT=PASS`（`applicationId`/`versionCode`/`versionName` 未被 catalog 化，正则解析仍成立）。

2026-11-03 协调：**已知限制**。Gradle 侧与 CI 侧仍是两份工具链真值：5 个 workflow 里 `sdkmanager` 装的 `build-tools;36.1.0` / `ndk;27.0.12077973` 仍硬编码，改版本时 CI 可能装旧工具链直到 AGP 报错；收敛 CI 侧属独立票。`research/uml-runner`/`uml-probe` 是独立 Groovy 构建、各有自己的 AGP 字面量，本轮不连带。

