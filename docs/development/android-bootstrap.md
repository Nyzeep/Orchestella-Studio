# Android 工程骨架

本页只描述 Ticket 25 之前的 Android 构建前置。当前工程仅包含一个可启动的 `app` application module、Kotlin 基线与 JVM 单测入口；不包含 guest 资产、Runtime、网络、桥接、存储或其他产品功能。

## 固定基线

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- Android SDK Build-Tools 36.1.0
- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26
- ABI: `arm64-v8a`，通过 Android NDK ABI filter 固定未来 native 产物；当前 skeleton 尚无 native library，设备侧支持地板检查留给后续正式 ticket

这些版本是为 API 36 与 JDK 17 选择的稳定兼容基线，不跟随最新预览版。升级应作为独立、可验证的构建维护变更处理。

当前仓库路径含非 ASCII 字符。`android.overridePathCheck` 仅关闭 AGP 的保守路径拒绝，Kotlin 编译固定为 in-process 以避免 Windows daemon 对该路径的错误转义；若工具链仍出现路径兼容问题，应从 ASCII 路径检出或用临时盘符映射运行同一工作树。

`io.github.nyzeep.droidlayer` 是 pre-release 阶段的实现选择，不是冻结的产品身份；在首个对外发布或签名身份建立前仍可逆。若之后修改，必须同步迁移 `applicationId`、namespace、源码包路径与测试。

## 本机构建

准备 JDK 17 与 Android SDK Platform 36。通过 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 指向 SDK，或在不提交的 `local.properties` 中写入 `sdk.dir`。

Windows PowerShell：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

若 checkout 路径含中文且 Gradle/Kotlin 的子进程仍无法解析路径，可临时映射一个未占用的 ASCII 盘符（示例使用 `R:`），在同一 PowerShell 会话内执行：

```powershell
subst R: $PWD.Path
Set-Location R:\
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
subst R: /D
```

盘符映射不复制或移动仓库；如果 `R:` 已占用，应换用其他未占用盘符。

macOS / Linux：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/`。
