# 工单01实施报告：CI 全量门禁

- 票号/状态：avf-zero-device-01；**resolved**（本地等价门禁就绪）。
- 执行：Agent C / 2026-10-20。
- 基线：`ticket/05-platform-access-hidden-api` @ `50c971b4`；分支 `ticket/01-ci-full-gate`。
- 证据边界：全部命令在本机 Linux + JDK 17 + Android SDK 36 上执行。GitHub Actions 远端未运行；`workflow_dispatch` 和完整 `qemu-android` payload job 未运行。

## 1. 问题与范围

`ci.yml` / `release.yml` 只跑 `contract`、`instance`、`engine-qemu` 和 app 测试；AVF 最脆弱的 `engine-avf`、`capability`、`distro`、`network` 不在 PR/release 门禁里。`qemu-android.yml` 里已有的 lint/assemble/16KB/zipalign 只在 `workflow_dispatch` 的完整 payload 构建中执行。AVF 仍是 19 个 `JavaExec main()` harness，失败只在 Gradle 控制台，PR 页面没有稳定的任务名/日志摘要。

本票只改 CI/构建配置与守卫脚本，不改产品行为、不写设备测试、不添加 secret、不 `-x test`、不关闭 lint、不把失败标成 skipped-success。

## 2. 实际实现

### 2.1 全量 Gradle 门

- `.github/workflows/ci.yml` 的 `test` job 和 `.github/workflows/release.yml` 的 `build-release` job 都改为同一组：
  `:runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check :runtime:network:check :app:testDebugUnitTest`。
- 通过 `tools/ci/run-gradle-gate.sh` 执行：一次 Gradle 调用、`--continue`、`--stacktrace`、`--no-daemon`；完整输出写入 `build/ci/gradle-gate.log`，并把 `GRADLE_GATE_EXIT` 与所有 `> Task ... FAILED` 写入 `build/ci/gradle-gate-summary.txt`。
- 两个 job 都用 `actions/upload-artifact@v4` 在 `if: always()` 上传这两个文件；PR/Release 页面可直接看到失败的 AVF harness 任务名。
- 未引用 Agent B 删除的任何 JavaExec 任务名；只引用 `:runtime:engine-avf:check`。harness 仍由 `avfAllTestTasks` → `test` → `check` 串联，JavaExec 非零退出会传播为 Gradle 失败。

### 2.2 轻量 lint / assemble / 16 KB 门

- `ci.yml` 新增 `android-package-gate` job：`:app:lintDebug :app:assembleDebug` + `tools/ci/check-apk-16kb.sh`。完整 QEMU payload 构建仍只在 `qemu-android.yml`，没有把繁重步骤塞进每次 PR。
- `release.yml` 在候选测试后增加同一组 lint/assemble + debug APK 16KB 检查；在 release APK 定位后再对 release APK 执行一次 16KB/zipalign 检查。
- 新增 `tools/ci/check-elf-16kb.sh`：ELF64、AArch64、`GNU_RELRO`、每个 `PT_LOAD` 对齐 ≥ 16384；打印 `DT_NEEDED`。`tools/ci/check-apk-16kb.sh` 先执行 `zipalign -c -P 16 -v 4`，再从 APK 提取 `lib/arm64-v8a/*.so` 调用前者。
- `qemu-android.yml` 的 ELF 步骤改为复用 `tools/ci/check-elf-16kb.sh`，不再保留第二份内联循环；QEMU 专属路径、NDK 和容器不变。

### 2.3 模块覆盖与 harness 布线守卫

- `tools/ci/check_ci_module_coverage.py`：从 `settings.gradle.kts` 枚举所有 `:runtime:*`，要求 `ci.yml` 与 `release.yml` 都出现 `:runtime:<name>:check` 和 `:app:testDebugUnitTest`；同时检查 `runtime/engine-avf/build.gradle.kts` 中每个 `tasks.register<JavaExec>` 都在 `avfAllTestTasks` 里、`test` 依赖该列表、workflow 不引用已删除的 `:runtime:engine-avf:<task>`。
- `tools/ci/test_check_ci_module_coverage.py`：6 个 fixture 测试（缺失 runtime check、完整覆盖、缺失 app 测试、harness 未接线、引用已删除 harness、当前仓库通过）。
- 两个 workflow 都在 Gradle 之前执行守卫自身测试和守卫；守卫失败即 job 失败。

## 3. Red → green 证据

### 3.1 守卫 red → green

实现前（workflow 未更新）：

```text
python3 tools/ci/check_ci_module_coverage.py
FAIL  ci.yml runs :runtime:distro:check
FAIL  ci.yml runs :runtime:engine-avf:check
FAIL  ci.yml runs :runtime:network:check
FAIL  release.yml runs :runtime:capability:check
FAIL  release.yml runs :runtime:distro:check
FAIL  release.yml runs :runtime:engine-avf:check
FAIL  release.yml runs :runtime:network:check
SUMMARY: 34 passed, 8 failed
```

实现后：

```text
python3 tools/ci/test_check_ci_module_coverage.py
Ran 6 tests ... OK

python3 tools/ci/check_ci_module_coverage.py
SUMMARY: 44 passed, 0 failed
```

### 3.2 故意失败：app 测试门

临时新增 `CiGateDeliberateFailureTest`（未提交，执行后删除）：

```text
bash tools/ci/run-gradle-gate.sh :app:testDebugUnitTest --tests '*CiGateDeliberateFailureTest'
> Task :app:testDebugUnitTest FAILED
CiGateDeliberateFailureTest > deliberateFailure FAILED
    java.lang.AssertionError at CiGateDeliberateFailureTest.java:11
GRADLE_GATE_EXIT=1
FAILED_TASKS:
> Task :app:testDebugUnitTest FAILED
```

### 3.3 故意失败：AVF JavaExec harness 门

临时在 `AvfPlatformAccessTest.main` 注入仅由 `ORCHESTELLA_DELIBERATE_CI_FAILURE=1` 触发的异常（未提交，执行后从备份恢复，`git diff` 为空）：

```text
ORCHESTELLA_DELIBERATE_CI_FAILURE=1 bash tools/ci/run-gradle-gate.sh :runtime:engine-avf:check
> Task :runtime:engine-avf:avfPlatformAccessTest FAILED
Exception in thread "main" java.lang.RuntimeException: deliberate CI harness failure
GRADLE_GATE_EXIT=1
FAILED_TASKS:
> Task :runtime:engine-avf:avfPlatformAccessTest FAILED
```

这证明 harness 内部失败不会被“Gradle 成功”吞掉，且失败任务名进入摘要 artifact。

### 3.4 全量门与打包门

```text
bash tools/ci/run-gradle-gate.sh \
  :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :runtime:engine-avf:check :runtime:capability:check :runtime:distro:check \
  :runtime:network:check :app:testDebugUnitTest
BUILD SUCCESSFUL in 47s
GRADLE_GATE_EXIT=0
FAILED_TASKS: (none reported)

./gradlew :app:lintDebug :app:assembleDebug --no-daemon --stacktrace
BUILD SUCCESSFUL in 32s

bash tools/ci/check-apk-16kb.sh app/build/outputs/apk/debug/app-debug.apk
ELF_CLOSURE_16KB_OK
APK_16KB_GATE_OK apk=app/build/outputs/apk/debug/app-debug.apk libs=2
```

YAML 语法用临时 venv 中的 PyYAML 解析了 `.github/workflows/*.yml`，4 个文件均 `YAML_OK`。本机没有 `actionlint`/`act`，因此没有做 GitHub Actions 运行时模拟。

## 4. 未验证项

- GitHub Actions 远端未运行（未触发 PR/release workflow）；`android-package-gate` 的 NDK 安装与 60 分钟预算未在 runner 上实测。
- 完整 `qemu-android.yml` payload job 未运行；只做了 YAML 解析和对共享 ELF 脚本的本地 APK 验证，QEMU 三件套的路径/NDK 容器未执行。
- `release.yml` 的签名/密钥步骤未运行；本报告不涉及 release 发布。
- 16KB 脚本检查 ELF 段对齐、RELRO 和 zipalign；`DT_NEEDED` 仅打印用于诊断，与改动前的 qemu 步骤一致，不是新的 closure 断言。

## 5. 回滚、风险与兼容

- 回滚：撤回两个 workflow、`tools/ci/*`、`qemu-android.yml` 的 ELF 步骤即可；不涉及产品数据或版本。
- 风险：GitHub 托管 runner 的 NDK/CMake 首次下载可能较慢；`android-package-gate` 的 `sdkmanager` 使用 `|| true` 保持原有容错，真正的构建失败仍会使 job 红。
- 没有新增 secret，没有上传 APK/用户数据/设备日志；artifact 只含测试输出摘要与 Gradle 日志。
- 现有检查没有降低：旧的 regression gate、contract/instance/engine-qemu/app 测试仍全部保留；只增加模块与打包检查。
