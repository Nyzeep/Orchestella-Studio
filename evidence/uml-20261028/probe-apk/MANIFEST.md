# UML D1a/D1b 域探针 APK（会话20）

构建时间：2026-10-28 ｜ 工程：`research/uml-probe/`（独立 gradle 工程，AGP 8.13.2 / Gradle 8.13 / compileSdk 36 / minSdk 24，纯 Java 无 native 代码）

## APK 清单

| 文件 | 字节数 | sha256 | targetSdk | 用途 |
|---|---|---|---|---|
| `uml-probe-sdk36-debug.apk` | 12613 | `81d59a0a3ca84ff079bd317154d86791b10b528eb9d63fd6b14dcfc2b1f0715d` | 36 | 模拟现代 App 域（untrusted_app / targetSdk≥29），预期探针①②基本全败 |
| `uml-probe-sdk28-debug.apk` | 12613 | `1d959631492e7371a2e125dcf546c325b985ec0e8cf036b73e3b0435ea5d61f8` | 28 | 模拟 untrusted_app_27 域（targetSdk≤28），预期探针通过 |

包名均为 `dev.orchestella.umlprobe`，minSdk 24（aapt2 badging 已验证 targetSdk 36 / 28）。

## 探针内容

- ① filesDir 文件 RWX：`uml_probe_mem.bin` 64KB → `Os.open` → `Os.mmap(PROT_READ|WRITE|EXEC, MAP_PRIVATE)`（对应 UML `check_tmpexec()` 能力）
- ② 匿名 PROT_EXEC：`MAP_PRIVATE|MAP_ANONYMOUS` RWX mmap；空 fd 失败时回退 memfd fd + MAP_ANONYMOUS 重试
- ③ memfd + PROT_EXEC：`Os.memfd_create` → 写入 64KB → `ftruncate` → `MAP_SHARED` RWX mmap（「memfd 缝隙」）
- ④ stub 前置信息（必过）：页大小、SELinux 上下文、ABI、API 级别、targetSdk（D1b 域确认 + D8 宿主页大小证据）

## 运行方式（后续设备会话）

安装对应 APK → 启动 `dev.orchestella.umlprobe.ProbeActivity` → 屏幕显示结果列表；
结果同时输出到 logcat（tag `UmlProbe`）与 `getFilesDir()/probe_results.txt`：
`adb shell run-as dev.orchestella.umlprobe cat files/probe_results.txt`。
