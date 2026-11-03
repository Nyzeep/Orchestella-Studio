# Q1 构建期缺件 fail-fast

Status: resolved
Type: task
Blocked by: 无
Effort: .scratch/quality-round-20261103/

## 背景

`Image`、`initramfs.cpio.gz`、`libdroidlayer_qemu.so` 三件运行期载荷被 `.gitignore:22/23/25` 忽略。干净 clone 后 `./gradlew :app:assembleDebug` **照样 BUILD SUCCESSFUL**：`app/build.gradle.kts:195-216` 的载荷校验整块包在「任一 `*qemu*Dir` 非 null」的条件里，不传 `-P` 时一行都不执行。失败被推迟到用户首次开机——APK 里「有清单（`runtime-artifacts-v1.sha256` 是 tracked 文件，必然进包）、无产物」，运行期解析清单成功却取件炸掉。

## 交付

- `app/build.gradle.kts` 顶层（`android {}` 之外，紧邻 `packageOrchestellaRelease` 之前）注册 `verifyRuntimeAssets`，`group = "verification"`，挂 `tasks.named("preBuild") { dependsOn(...) }`。写法对齐 `:295-308` 的 `verifyPvmCompilerManifest`。
- 判据三条（**只比这三条**，按名字查表不按行号）：
  - `app/src/main/assets/Image`
  - `app/src/main/resources/initramfs.cpio.gz`
  - `app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so`
  - 逐件断言「存在 + 非空 + sha256 == `app/src/main/assets/runtime-artifacts-v1.sha256` 里同名条目的摘要」。**不要校验清单全部 7 项**：`NOTICE.QEMU`、`QEMU-GPL-SOURCES.md` 自 `e16af2c` 起已漂移，全量校验今天就会红。
- 两条豁免，都要显式且留痕：
  - 外部载荷模式：任一 `orchestella.qemu.*` **或** `droidlayer.qemu.*`（`nativeLibDir`/`assetsDir`/`initramfsDir`）非空 → 只断言三目录存在且非空，跳过文件与摘要校验。`qemu-android.yml:372-374` 用的是遗留 `droidlayer` 前缀，两个前缀都必须识别。
  - 干净 checkout 豁免：`-Porchestella.assets.allowMissingPayload=true` → `logger.warn` 一行 + 写 `build/runtime-assets-missing.flag` + 跳过。
- 失败信息可操作：列缺件名、期望摘要、取件指引（`guest-assets/run-in-builder.sh` 与 CI 的 `-P` 路径）。不回显本机绝对路径。
- 同批改 CI 调用面，否则 PR 门禁转红：`.github/workflows/ci.yml:197`、`.github/workflows/release.yml:139`、`:145` 三处加 `-Porchestella.assets.allowMissingPayload=true`，并加注释说明「干净 checkout 的已知缺件基线；真实载荷由 `publish-draft` 的 `check_runtime_payload.py --apk` 兜底」。

## 验收

- `./gradlew :app:assembleDebug` 在移走 `app/src/main/assets/Image` 后**必须失败**，错误信息点名 `Image` 并给取件指引；放回后复原 `BUILD SUCCESSFUL`。两步输出都要留证。
- 传 `-Pdroidlayer.qemu.nativeLibDir=…assetsDir=…initramfsDir=…` 时门禁走豁免分支，不误判。
- `-Porchestella.assets.allowMissingPayload=true` 时构建成功且 `build/runtime-assets-missing.flag` 存在。
- 三处 workflow 的干净 checkout 构建不因本门禁转红。

## 测试

- 无新增单测（Gradle 门禁本身就是验证面），但**必须**留「反证 + 复原」两段真实命令输出。
- `./gradlew :app:assembleDebug`（`X:` 盘 ASCII 路径）

## 不在范围

- 重新生成 `runtime-artifacts-v1.sha256`（属「改产物」，需产品裁决）。
- 把 7 项全量校验接进 `preBuild`。
- 修运行期缺 `initramfs` 时的 NPE（`RuntimeAssetInstaller.java:54`）。

## Comments

2026-11-03 协调：建票（来源 `.scratch/quality-round-20261103/notes/B1-build-assets.md`）。

2026-11-03 Q1-impl：claimed。实际落点与工单假设的差异：`Image` 在 `app/src/main/assets/`、`initramfs.cpio.gz` 在 `app/src/main/resources/`，只有 ELF 在 `jniLibs/`，门禁按真实路径校验。

2026-11-03 Q1-impl：完成。改动三处：
- `app/build.gradle.kts:551-556` 读 `orchestella.assets.allowMissingPayload`；`:558` 注册 `verifyRuntimeAssets`（`group = "verification"`，流式 SHA-256，清单按名字查表）；`:583-616` 外部载荷模式（同时识别 `orchestella.qemu.*` 与 `droidlayer.qemu.*`，只断言三目录非空）；`:620-638` 显式豁免（warn + 写 `buildDir/runtime-assets-missing.flag`）；`:846` `tasks.named("preBuild") { dependsOn(verifyRuntimeAssets) }`；`:712` 通过时删除陈旧标记。
- `.github/workflows/ci.yml:197`、`release.yml:139`、`:145` 加 `-Porchestella.assets.allowMissingPayload=true` + 说明注释（干净 checkout 无载荷是已知基线；真实载荷由 `publish-draft` 的 `check_runtime_payload.py --apk` 兜底，该步在 bundle 缺载荷时 `exit 1`，见 `release.yml:255-266`）。
- 未改 `qemu-android.yml`（它走 `-Pdroidlayer.qemu.*`，命中豁免 1）。

2026-11-03 协调：**反证（本轮最重要证据）**。把 `app/src/main/assets/Image` 移走后 `./gradlew :app:assembleDebug --offline`（`X:` ASCII 路径）：
```
> Task :app:verifyRuntimeAssets FAILED
verifyRuntimeAssets: The QEMU runtime payload in this checkout is incomplete or does not match app/src/main/assets/runtime-artifacts-v1.sha256.
verifyRuntimeAssets:   - missing files: Image (app/src/main/assets/Image)
verifyRuntimeAssets:   - local build: build the guest assets with guest-assets/run-in-builder.sh ...
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:verifyRuntimeAssets'.
BUILD FAILED in 40s
```
放回 `Image` 后同一条命令 → `verifyRuntimeAssets: Image, initramfs.cpio.gz and lib/arm64-v8a/libdroidlayer_qemu.so match app/src/main/assets/runtime-artifacts-v1.sha256.` / `BUILD SUCCESSFUL in 44s`。失败发生在 `syncGuestShellAssets`/`syncGuestInitAndRelay` 之后、任何编译与打包之前（`3 actionable tasks: 1 executed, 2 up-to-date`）。

2026-11-03 协调：豁免分支实跑 `./gradlew :app:verifyRuntimeAssets -Porchestella.assets.allowMissingPayload=true` → `BUILD SUCCESSFUL`，输出 WARNING，且 `app/build/runtime-assets-missing.flag` 内容为 `timestamp=… / reason=-Porchestella.assets.allowMissingPayload=true / detail=…`。

2026-11-03 协调：`python tools/ci/check_sensitive_data.py --self-test` → `SENSITIVE_DATA_OK`。

2026-11-03 协调：**已知限制**。入库清单与 CI 锚点不是同一批字节（入库 initramfs `c96fe6e6…`/1,267,115 B；`qemu-android.yml` GA base `92766a6f…`/1,091,309 B；`apk-postbuild-expectations-v1.json` `f8db1b52…`），本门禁只断言「入库清单 ↔ 工作区三件载荷」，不得把 GA base 摘要写成缺件判据。清单里 `NOTICE.QEMU`/`QEMU-GPL-SOURCES.md` 两条已漂移，故只校验三项。`not-tested`：CI 侧实跑（本地无 GitHub Actions）。

