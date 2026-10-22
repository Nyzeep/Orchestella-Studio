# Fast Lane DV-01B 合并收口与合并后真机 preflight 报告

日期：2026-10-22　状态：合并已落地；合并后真机 preflight 复现平台阻塞（不改变 DV-01 结论）
关联：[DV-01B preflight 契约](fastlane-preflight-contract.zh-CN.md)、[设备矩阵](fastlane-device-matrix.zh-CN.md)、[DV-01 真机结果](fastlane-dv01-20261021.zh-CN.md)、[DV-01B 工单](../../../.scratch/orchestella-fastlane/issues/DV-01B-device-agnostic-preflight.md)

---

## 1. 本轮范围与结论

本轮是 DV-01B 的**合并收口会话**：修掉 PR #6 上两个红灯的 CI、把分支合进 `main`、按契约 §5.5「变更即失效」从 `main` 重新构建并重跑一次真机 preflight、归档与校验证据、补齐矩阵与工单。

结论三条：

1. **合并完成。** PR #6 以 merge commit 合入 `main`（`e599610`），合并前所有 job 全绿。
2. **合并后 preflight 复现同一平台阻塞。** `outcome=PREFLIGHT_TERMINAL_BLOCKED`，`terminal.reason_code=TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`，`exec_stage=execve`，`errno=13`；stop `CONFIRMED`、delete `VM_DELETED`。与合并前那次同因同果。
3. **没有进入 full。** 契约只允许 `PREFLIGHT_TERMINAL_OK` 放行 full；本轮是 blocked，按契约停止，不重试、不绕过、不改 root/SELinux/pvmfw。

本轮**没有**改动 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。

---

## 2. 修掉的两个 CI 失败

### 2.1 `Contract & Unit Tests`：CI/release 门禁漏掉七个 runtime 模块

**现象**：`tools/ci/check_ci_module_coverage.py` 报 14 条 FAIL——`settings.gradle.kts` 里 `include(":runtime:*")` 声明的模块，必须在 `ci.yml` 和 `release.yml` 的 `run-gradle-gate.sh` 任务列表里逐个出现 `:runtime:<name>:check`。DV-01B 引入的七个 Fast Lane 模块（`engine-fastlane` 与六个 `fastlane-*`）只进了 `settings.gradle.kts`，没进两个工作流的门禁列表。

**修复**：在 `.github/workflows/ci.yml` 与 `.github/workflows/release.yml` 的 `run-gradle-gate.sh` 任务列表里补齐

```
:runtime:engine-fastlane:check
:runtime:fastlane-distcc:check
:runtime:fastlane-telemetry:check
:runtime:fastlane-capability:check
:runtime:fastlane-storage:check
:runtime:fastlane-proxy:check
:runtime:fastlane-terminal:check
```

**本地验证**：`check_ci_module_coverage.py` 从 44 通过 / 14 失败变成 58 通过 / 0 失败；`test_check_ci_module_coverage.py` 6 个用例全过。

### 2.2 `Payload NDK/CMake build`：NDK 版本没被钉住

**现象**：`FAIL NDK revision is '27.3.13750724', expected 27.0.12077973`。runner 预装了多个 NDK，而 `build-payload.sh` 在 `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` 未设置时用 `find "$SDK/ndk" | sort -V | tail -1` 取「最大版本」，于是选中了预装的 27.3；而安装步骤 `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1" || true` 又用 `|| true` 吞掉了安装失败。

**修复**（`.github/workflows/fastlane-zero-device.yml`）：

- 「Install NDK and CMake」步骤去掉 `|| true`，`sdkmanager` 找不到就直接 `exit 1`，安装失败就让 job 失败；
- 新增「Pin the NDK and CMake toolchain」步骤：显式设置 `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` 指向 `$ANDROID_HOME/ndk/27.0.12077973`，并在构建前用 `source.properties` 的 `Pkg.Revision` 校验该目录确实是 `27.0.12077973`，同时校验 `$ANDROID_HOME/cmake/3.22.1/bin/cmake` 存在；
- 把 `CMAKE` 与 CMake 的 `bin` 目录也固定进环境，让构建真的用上被钉住的 3.22.1。

没有用 `FASTLANE_ALLOW_ANY_NDK=1` 绕过 pin，也没有改成 27.3。

### 2.3 顺带暴露并修掉的第二个阻塞：NDK 内 llvm 工具定位

NDK 版本钉住之后，payload 编译本身成功了（17/17 目标），但紧接着挂在

```
FAIL llvm-readelf not found under the NDK
```

**根因**：`build-payload.sh` 用 `-type f` 找 NDK 里的 `llvm-readelf`，而 NDK 在 Linux 上把部分 LLVM 主机工具以**符号链接**形式提供，`find` 默认不跟随符号链接，因此匹配不到。仓库里已有的 `tools/ci/check-apk-16kb.sh` 用的是 `\( -type f -o -type l \)`，所以 APK 门禁一直是好的——两处定位方式不一致才暴露了这个问题。

**修复**（`tools/fastlane-build/build-payload.sh`）：抽出一个 `find_ndk_tool()` 辅助函数，同时接受普通文件与符号链接；`llvm-readelf` 与 `llvm-nm` 各自独立定位（不再假定 `llvm-nm` 一定在 `llvm-readelf` 旁边）。

**这里刻意没有退让的一点**：找不到 NDK 内的 `llvm-readelf` 时，**不回退到 PATH 上的 GNU `readelf`**。`llvm-readelf` 按行输出 program header，`check-elf-16kb.sh` 的 awk 才能取到 PT_LOAD 那一行末尾的对齐列；GNU `readelf` 把 program header 折成两行，回退过去会让 16 KB 对齐检查读到错列、静默失效。所以这条路径宁可失败。

---

## 3. 合并

| 项 | 值 |
| --- | --- |
| PR | #6（`feat(fastlane): 设备无关 DV-01B preflight、host runner 与零设备门禁`） |
| head | `359f118` |
| base | `main` |
| 合并方式 | `gh pr merge --merge`（merge commit，未 squash、未 force、未打 tag） |
| main 上的合并节点 | `e599610`（父提交 `47837f7` + `359f118`） |

本轮新增的两个提交：

- `a40b7de` `fix(ci): 补齐 runtime 门禁覆盖并固定 payload NDK/CMake 版本`
- `359f118` `fix(fastlane): 修正 payload 门禁对 NDK 内 llvm 工具的定位`

合并后核对：`git merge-base --is-ancestor origin/integration/fastlane-dv01b origin/main` 返回 0；`git log -1 origin/main` 为 `e599610 Merge pull request #6 from Nyzeep/integration/fastlane-dv01b`。

合并前 `359f118` 上的十个 CI job 全绿：

| Job | 结果 |
| --- | --- |
| Contract & Unit Tests | pass |
| Schema validation and sanitization tests | pass |
| Toolbox, zip gates, Python lanes and licenses | pass |
| Android lint, assemble and 16 KB packaging | pass |
| engine-fastlane JVM and androidTest compilation | pass |
| Every Fast Lane JVM module | pass |
| Payload host build, CTest and FLN1 E2E | pass |
| Release Metadata Gate | pass |
| APK bin/ injection and 16 KB packaging | pass |
| Payload NDK/CMake build (P02 source required) | pass |

---

## 4. 合并后重建（契约 §5.5）

合并进 `main` 会改变 QA 版本后缀（后缀里带 merge commit SHA），APK 字节因此改变，旧 preflight 结论按契约失效，必须从 `main` 重建并重跑。本轮**没有**复用合并前的任何结论。

工具链：JDK 17、Android SDK Platform 36、build-tools 36.1.0、NDK 27.0.12077973、CMake 3.22.1、ninja。

构建步骤：

```bash
# 1. toolbox
python tools/fastlane-build/fastlane_build.py build \
  --manifest fastlane-toolbox/manifest.json \
  --out build/fastlane-toolbox --cache build/fastlane-cache

# 2. payload（NDK/CMake 版本由 build-payload.sh 自己校验）
bash tools/fastlane-build/build-payload.sh
#    产物 build/fastlane-payload/libfastlane_payload.so
#    放到 build/fastlane-payload/jniLibs/arm64-v8a/libfastlane_payload.so
#    （libvm_payload.so 是 AVF APEX 库，禁止打进 APK）

# 3. 两个 APK
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -Porchestella.buildChannel=qa \
  -Porchestella.buildPurpose=dv01 \
  -Porchestella.fastlane.payloadLibDir=build/fastlane-payload/jniLibs \
  -Porchestella.fastlane.toolboxDir=build/fastlane-toolbox
```

产物指纹（可用于新环境核对是否同一批字节）：

| 产物 | SHA-256 |
| --- | --- |
| app-debug.apk | `f28d0749361a3eb183528b9af2f4f7e66480b2acb600990c2c1d8337604b045d` |
| app-debug-androidTest.apk | `a72b44e2628638987cd127e1c8d83dec3fb87f597a2b43dd772248fa277b3c26` |
| libfastlane_payload.so（APK 内条目） | `2a3ae6b4ca6d26220b731b8e4d518ce2179f5181ab382e449541d07526c17b07` |
| toolbox manifest（APK 内 `bin/manifest.json`） | `e03522928386755676cb196a9826c1f7da51240e5be63e4f283b8c7447edae6b` |

QA 通道自检：`applicationId=io.github.nyzeep.orchestella.qa.dv01`，`versionName=0.5.3-qa.dv01.e599610`（后缀含合并节点 SHA），androidTest 包名 `io.github.nyzeep.orchestella.qa.dv01.test`。

payload 与 toolbox 的字节指纹与合并前那次运行完全一致——构建本身可复现，变化的只有 APK 里的版本后缀。

---

## 5. 真机 preflight 结果

按契约只跑一次 P1 直连（只创建一个 VM、只跑一次固定 terminal 短命令、只 stop/delete 一次）。

| 项 | 值 |
| --- | --- |
| 设备别名 | `qa-device`（操作者提供的稳定别名） |
| 环境 | API 36 / arm64-v8a / `virtualization_framework=true` / `protected_bit=true` |
| 权限 | `MANAGE_VIRTUAL_MACHINE` 与 `USE_CUSTOM_VIRTUAL_MACHINE` 均 `GRANTED` |
| outcome | `PREFLIGHT_TERMINAL_BLOCKED` |
| terminal | `status=blocked`，`reason_code=TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`，`exec_stage=execve`，`errno=13` |
| stop / delete | `CONFIRMED` / `VM_DELETED` |
| not_tested | `encrypted_storage_persistence`、`20_cycles`、`negative_cases` |

九个阶段齐全，逐一落账：

| 阶段 | status | reason_code |
| --- | --- | --- |
| parameter_validation | pass | `QA_ARGUMENTS_VALID` |
| platform_facts | pass | `PLATFORM_OBSERVED` |
| create | pass | `VM_CREATED` |
| run | pass | `RUN_RETURNED` |
| payload_ready | pass | `PAYLOAD_READY_OBSERVED` |
| control_hello | pass | `HELLO_HELLO_OK` |
| terminal_pty | blocked | `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM` |
| stop | pass | `STOP_CONFIRMED` |
| delete | pass | `VM_DELETED` |

**判读**：这不是文件缺失、ZIP mode、noexec 挂载或 harness 失败，而是 Android 16 `microdroid_app` 对 payload APK 文件缺少 `execute_no_trans` 的平台策略阻塞。报告只记固定 reason code、stage 与 errno，没有原始 AVC、logcat、错误消息或路径。

**未跑 full**：按契约，blocked 就不允许 full；本轮没有触发 full，也没有重试或绕过。

---

## 6. 归档与校验

归档位置（外部 artifact 根，不在仓库内）：

```
protected-fastlane/20261022/preflight/<token>/
├── report.json
├── run-summary.json
├── commands.txt
├── SHA256SUMS
└── artifacts/
    ├── app-debug.apk
    ├── app-debug-androidTest.apk
    └── libfastlane_payload.so
```

- 本次是 `20261022/preflight/` 下**新增的**唯一 run 目录，没有覆盖任何历史 run（合并前那两次运行仍在原处）。
- `report.json` 的 SHA-256 是 `7a9dd9ed9019661358701bdd2faa33d74a51c194080ea8878ca4bb87dc821b58`，可用于在新环境中无歧义地指认这一次运行；token 只作为目录名，不写进仓库、矩阵、文档或提交信息。
- `commands.txt` 用 `<serial>`、`<token>`、`<alias>`、`<apk>`、`<test-apk>`、`<qa-package>`、`<path>` 等占位符，真实序列号只存在于进程内存。
- `SHA256SUMS` 逐项复算全部一致。
- 契约校验：`validate.py --report --summary --strict` → `OK preflight` / `OK summary` / `OK binding`，退出码 0。

---

## 7. 文档更新

- **设备矩阵**（`docs/reports/orchestration/fastlane-device-matrix.zh-CN.md` 第 7 节）：追加**一行**真实运行记录（`run_date=2026-10-22`、`device_alias=qa-device`、`PREFLIGHT_TERMINAL_BLOCKED`、`full_20_cycles=not-run`），未改动既有行、未新增列、未改 harness。
- **DV-01B 工单**（`.scratch/orchestella-fastlane/issues/DV-01B-device-agnostic-preflight.md`）：状态改为 merged-to-main；新增「合并后重建与真机 preflight」小节；证据小节改写为 PR #6 的合并信息；P2 `--strict` binding 项补注「本轮归档校验已用 `--strict` 完成绑定，但 P2 runner 本身仍未切换」。

---

## 8. 交接给新环境的注意事项

这些都是本轮实际踩到的坑，按踩坑顺序记录：

1. **Windows 下 PowerShell 5.1 会把无 BOM 的 UTF-8 脚本按 ANSI 解码**，于是脚本里的中文路径会被读成乱码，写文件会落到一个畸形的目录名下。归档这类带非 ASCII 路径的操作请用 Python，或把 `.ps1` 存成带 BOM 的 UTF-8。
2. **PowerShell 调用 `gradlew.bat` 时会吃掉 `-P` 参数**（`-Porchestella.buildChannel=qa` 变成一个叫 `.buildChannel=qa` 的任务）。用 POSIX 的 `./gradlew`（经 Git Bash 或等价 shell）传参。
3. **NDK 与 CMake 放在纯 ASCII 路径下**再跑 CMake；中文路径会让 CMake 的缓存与命令行出现编码问题。
4. **`tools/fastlane-schema` 的 fixture 哈希绑定的是 LF 字节**：`ok.json` 里写死的 `report_sha256` 是 `terminal-ok.json` 在仓库里（LF）的哈希。Windows 检出若带 CRLF，`test_strict_binding_ok` 会以 `E_SUMMARY_REPORT_HASH_MISMATCH` 失败。把检出设成 `core.autocrlf=false` + `core.eol=lf`（并重新检出那些 fixture）后即通过。
5. **`tools/fastlane-device` 的测试套件只能在 POSIX 上跑**：它用带 shebang 的假 `adb` 脚本，Windows 无法执行，报 `ADB_INVOCATION_FAILED`。CI（ubuntu）才是该套件的权威门禁；本轮在 Linux 语义下为 48 通过 / 0 失败。
6. **设备在 AOD/熄屏状态下，`adb install` 会被平台 PackageInstaller 中止**，报 `INSTALL_FAILED_ABORTED: User rejected permissions`（与 `-g` 无关）。先点亮并解锁屏幕再装；本轮即因此第一次安装失败。
7. **payload 门禁现在接受符号链接形式的 llvm 工具**，但不会回退到 GNU `readelf`——理由见 §2.3，别为了「让它跑过」而加这条回退。

---

## 9. 未完成 / 下一步

- [ ] **full DV-01 仍未放行**：本轮 preflight 是平台阻塞，按契约不跑 full。full 需要一次 `PREFLIGHT_TERMINAL_OK` 的 preflight 加操作者显式触发；平台阻塞未解除前不会发生。
- [ ] **平台阻塞本身未处理**：FLN1 7 / `execve` errno 13 属 Android 16 `microdroid_app` 的 `execute_no_trans` 策略，属 DV-01A 的平台变更请求范畴，本轮未修、未绕过。
- [ ] **P2 runner hardening 跟踪票**（`DV-01B-p2-runner-hardening.md`）本轮仍未实现、未测试；其中 `qualification` / `full` 模式仍只有 fake-adb 覆盖，不构成真机证据。
- [ ] **未进入 DV-02**（terminal UI、proxy、distcc、export/import）。
- [ ] 合并后这次运行需要在**下一次通道/版本/工具链变化**时再重跑一次 preflight：契约 §5.5 对每次变更都要求重新取证。

---

## 10. 边界

- 本报告与合并后这次 preflight 都**不**改变 DV-01 的结论（仍为 `DV01_CORE_PASS_TERMINAL_BLOCKED`），也**不**把零设备门禁结果当成真机 PASS。
- 未修改 FLN1 wire、payload 字节、accepted ADR、版本号、签名身份或 release channel。
- 未修改 root、SELinux、pvmfw 配置，未使用 `/system/bin/sh`、encrypted storage 执行、chmod、memfd、execveat 或伪 shell 绕过 terminal 阻塞。
- 仓库、PR、矩阵与本文档中都不含序列号、机型、账号、token、原始 logcat 或本机绝对路径；真机证据只以稳定别名加 SHA-256 记录，原始产物只落在外部 artifact 根。
- 本轮只操作了一台设备，未并行执行任何 adb、安装或建 VM 操作。
