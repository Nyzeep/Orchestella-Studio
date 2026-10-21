# P11 构建、CI 与许可证实现报告（零设备）

日期：2026-10-21 ｜ 分支：`feature/fastlane-toolbox` ｜ 状态：宿主门禁可执行；真实 Gradle/APK 构建 `not-tested`；payload 构建等待 P02

## 1. 目标

让 Fast Lane 的 toolbox/APK/许可证产物可重复检查、可阻塞失败，并且不在
PR 门里运行设备任务；不改版本号、不 tag、不发布、不碰签名材料。

## 2. 改动

### Gradle（`app/build.gradle.kts` / `settings.gradle.kts`）

- 新增可选属性 `orchestella.fastlane.toolboxDir`。仅当显式提供时，校验目录
  存在、不是 symlink、含 `bin/manifest.json`，然后
  `sourceSets.main.resources.srcDir(toolboxRoot)`。
- 资源源集由 AGP 在打包阶段写入 APK 根 `bin/`，发生在 zipalign 和签名之前；
  不使用“签名后改包”。
- 新任务：
  - `checkFastlaneLicenses`：跑 toolbox 许可证/NOTICE/SBOM 门。
  - `verifyFastlaneToolboxApk`（仅属性存在时注册）：依赖 `assembleDebug`，
    检查 APK 内 `bin/` 的 SHA/大小/执行位/16 KB ELF/预算；有
    `ANDROID_HOME/build-tools/36.1.0/zipalign` 时同时跑
    `zipalign -c -P 16 -v 4`。
  - `fastlaneZeroDeviceCheck`：聚合 `:runtime:engine-fastlane:test`、
    `:runtime:fastlane-distcc:test`、许可证门和可选的 APK 门。
- `settings.gradle.kts` 的 `pluginManagement` 增加 `gradlePluginPortal()`：
  Kotlin 插件 marker（`org.jetbrains.kotlin.android`）只在 Plugin Portal
  稳定发布；Maven Central 对 marker 返回 429 时离线/受限网络无法配置构建。
  项目依赖仍走 Google/Maven，不改变现有镜像策略。
- 未修改版本号、签名配置、发布任务或渠道逻辑。

### CI（`.github/workflows/fastlane-zero-device.yml`）

- `toolbox-and-licenses`：安装 PyYAML 后校验 workflow 自身、Python 单测、
  真实 pinned toolbox 构建、layout/16 KB 校验、两次构建复现、故意失败自测、
  许可证门。
- `jvm-distcc`：`:runtime:engine-fastlane:test` 与
  `:runtime:fastlane-distcc:test`。
- `apk-bin-gate`：构建 toolbox → `:app:assembleDebug` + APK 门 →
  `tools/ci/check-apk-16kb.sh`。
- `payload-build`：检出后检测 `fastlane-payload/CMakeLists.txt`；**始终**先用
  host fixture 证明 CMake/符号/`BLOCKED` 流程，存在时才执行 NDK/CMake 构建、
  16 KB、`AVmPayload_main` 符号检查；缺失时输出 `PENDING_INTEGRATION`，
  不伪装成真实 payload 成功。
- 无 adb、无设备、无 release 发布步骤；workflow 校验器会对 `adb`、
  `assembleRelease`、`signingConfig`、`secrets.`、`continue-on-error: true`、
  self-hosted runner 等直接失败。

### 工具（`tools/fastlane-build/`）

- `fastlane_build.py`：toolbox 构建/校验、合成 APK 注入/校验、许可证 CLI；
  新增 `SOURCE_DATE_EPOCH`、AArch64/static ELF 检查、路径/重复/symlink 拒绝、
  canonical tree digest。
- `reproduce.py`：同一输入构建两次并比较 tree digest；不一致列出差异文件并
  非零退出。
- `check_licenses.py`：许可证 allowlist、SHA256、GPL 对应源码、NOTICE/SBOM
  覆盖，并交叉校验 SBOM 的版本/license/checksum/downloadLocation/
  corresponding-source、DESCRIBES 关系和 pending profile 状态。
- `check_zero_device_workflow.py`：用 PyYAML 解析 workflow，检查触发条件、
  read-only 权限、必需 job/step、runner、超时和禁用命令；负向测试覆盖
  `continue-on-error`、adb、release、secret、缺失 gate。
- `build-payload.sh`：P02 源码存在时用 NDK/CMake 构建并检查 16 KB/符号；
  源码缺失时 `exit 2` + `BLOCKED`。
- `prove_payload_fixture.py`：用 host CMake 构建最小 `.so`、检查导出
  `AVmPayload_main`，并证明缺失 P02 源码时 `build-payload.sh` 返回 2 和
  `BLOCKED`；明确标注不是 Android 产物。
- `prove_gate_blocks.py`：故意篡改/签名/超预算/缺失条目/路径穿越/缺许可证，
  证明门禁失败关闭。

### 许可证

- 新增 `NOTICE-fastlane.md`：BusyBox GPL-2.0-only 的二进制、包 SHA、上游
  源码 SHA、Alpine 打包树、发布义务；新增 BusyBox 对应源码交付步骤（随 APK
  发布源码包，或三年有效书面 offer，二者择一并记录到 release ticket）；
  distcc/clang 为 pending/not-tested；gvproxy/zipfuse/JUnit 说明。
- 新增 `fastlane-toolbox/SBOM.spdx.json`：BusyBox 包、package SHA256 与
  binary SHA256、版本、许可证、对应源码引用；许可证门会核对两个 checksum。
- 未把 GPL 代码/补丁复制进仓库；只固定上游包与源码指针。

## 3. 验证证据

```bash
PYTHONPATH=<pyyaml> python3 -m unittest discover -s tools/fastlane-build -p 'test_*.py' -v
# Ran 76 tests ... OK
# 无 PyYAML 时 test_check_workflow 的 12 项 skip，其余 64 项 OK

PYTHONPATH=<pyyaml> python3 tools/fastlane-build/check_zero_device_workflow.py
# WORKFLOW_OK
# actionlint 在本环境不可用；用 PyYAML 解析 + 负向变异测试替代

python3 tools/fastlane-build/prove_payload_fixture.py
# PAYLOAD_FIXTURE_SYMBOL_OK library=libfastlane_payload_fixture.so symbol=AVmPayload_main
# PAYLOAD_FIXTURE_BLOCKED_OK
# PAYLOAD_FIXTURE_OK

python3 tools/fastlane-build/prove_gate_blocks.py
# BLOCKED tampered-bin-sha
# BLOCKED signed-apk-injection
# BLOCKED size-budget
# BLOCKED missing-bin-entry
# BLOCKED apk-path-traversal
# BLOCKED toolbox-sha
# BLOCKED toolbox-size-budget
# BLOCKED license-missing
# BLOCKED manifest-path-traversal
# GATE_BLOCK_OK

python3 tools/fastlane-build/fastlane_build.py check-licenses
# LICENSE_OK

python3 tools/fastlane-build/reproduce.py \
  --manifest fastlane-toolbox/manifest.json --cache <tmp>/fastlane-toolbox-cache --runs 2
# ok=true, runs=2, treeDigest=763ac97b..., totalBytes=2245502
```

故意失败覆盖：篡改 `bin/sh`、向已签名 APK 注入、超过体积预算、删除 APK
条目、APK 路径穿越、toolbox SHA、toolbox 体积、manifest 缺 license、manifest
路径穿越；全部被阻断后 `prove_gate_blocks.py` 才成功。

## 4. 签名前注入的边界

- 生产路径：AGP 资源源集 → 打包 → zipalign → 签名。没有签名后写 APK。
- `inject-apk` 只是测试/兜底：检测 `META-INF/*.RSA|SF|DSA|EC` 和 APK Sig
  Block magic，已签名即拒绝。
- `verify-apk` 接受已签名 APK（只读检查），但注入器不接受；这保证不会通过
  “先签再改”绕过。
- `zipfuse` 对 `bin/` 有 `EXECUTABLE_FILE_MODE` 兜底；若 AGP 写入 mode 0，
  `verify-apk` 记为 `zipfuseDefaultMode`，不误判为失败；若写入了非执行 mode，
  则失败。

## 5. 未验证 / not-tested

- Gradle 未能在本机完成：`settings.gradle.kts` 的 Plugin Portal 修复后，
  `--configure-on-demand` 可以跳过 `:app` 配置，但本机只有 JDK 21，项目
  toolchain 要求 JDK 17，报 `Cannot find a Java installation ... 17`；
  不使用 `--configure-on-demand` 时 `:app` 因无 Android SDK 失败。Maven
  Central 在本环境返回 429，本地解析改用仓库已有的 Aliyun 镜像属性。
  因此 Gradle Kotlin DSL 全量配置、AGP 资源打包、`assembleDebug` 均
  `not-tested`；distcc JVM 测试改用 `javac --release 17` + JUnit 直接运行，
  17 tests OK（见 P09 报告）。
- 真实 `zipalign -P 16`：本机无 zipalign，APK 门 `SKIP`；CI 有 build-tools
  时会执行，`check-apk-16kb.sh` 在 zipalign 缺失时直接失败。
- 真实 APK 的 `bin/` 执行位/zipfuse 行为：`not-tested`（DV-02）。
- `fastlane-payload/` 在本 worktree 不存在（P02 所有），payload NDK/CMake
  构建被 `BLOCKED`；host fixture 只证明流程和符号，不证明 Android 产物。
- 未运行 adb/设备；未发布 APK/Release；未 tag；未递增版本。

## 6. 版本与发布

- 版本保持 `0.5.3` / `versionCode 503`，构建通道逻辑未改。
- 新 CI 仅主机任务，不进入 release 签名/发布链。
- 许可证仍是草案，公开发布前需法务复核，尤其是 BusyBox 对应源码交付方式。

## 7. 风险与下一步

- 需在具备 Android SDK/JDK 17 的环境跑
  `:app:assembleDebug :app:verifyFastlaneToolboxApk -Porchestella.fastlane.toolboxDir=...`
  并确认 AGP 是否保留 `bin/` 执行位；若 AGP 写入 0644，需改用可验证的
  artifact transform，而不是签名后改包。
- `toolbox-and-licenses` 在 CI 中安装 `pyyaml==6.0.2` 仅用于 workflow 校验；
  它不是 APK 依赖，不进入 NOTICE/SBOM 的“已分发”列表。
- P02 交付 `fastlane-payload/CMakeLists.txt` 后，启用 `payload-build` job 的
  真实 NDK 构建步骤；host fixture 不能替代 16 KB/Bionic 验证。
- 80 MiB 是 v1 起步预算；当前条目 2.13 MiB、含 manifest 2.14 MiB。启用
  clang 前必须另做体积/许可证决策。

## 8. 研究来源

- Android `zipalign`：必须在 apksigner 签名前执行；`-P 16` 对齐未压缩 `.so`：
  <https://developer.android.com/tools/zipalign>
- Android 16 KB 页面：PT_LOAD `p_align` 与 `zipalign -c -P 16`：
  <https://developer.android.google.cn/guide/practices/page-sizes>
- AOSP zipfuse `bin/` 执行位：
  <https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/guest/zipfuse/src/inode.rs>
- BusyBox GPL-2.0-only 与 Alpine 打包：
  <https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.20-stable/main/busybox>
- distcc GPL-2.0 与 Termux 构建：
  <https://github.com/termux/termux-packages/blob/master/packages/distcc/build.sh>
- LLVM/clang 许可证：
  <https://llvm.org/LICENSE.txt>
- 本仓调研中的 gvproxy Apache-2.0 / Termux GPLv3：
  `docs/research/2026-10-21-cross-lane-reference-survey.md`
