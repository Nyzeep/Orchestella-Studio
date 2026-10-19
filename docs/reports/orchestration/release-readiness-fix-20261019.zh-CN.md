# 零设备发布阻塞修复报告（2026-10-19）

> **分支**：`fix/release-readiness`（从 `main` `4c1bc80` 切出；代码基线 `881e37a`）
> **版本**：源码保持 `0.5.3 / 503`，未递增、未打 tag、未创建 GitHub Release。
> **性质**：代码、测试、CI 与文档修复；不是发布报告，不授权合并或发布。
> **环境**：无 Android 设备、无 adb、无生产签名密钥。所有真机项标 `not-tested`。

## 0. 结论摘要

本分支修复了发布前可由代码解决的阻塞：构建通道、release 签名硬门、手工 release 工作流与 APK 校验、GPL/NOTICE 一致性、SAF 导出/导入数据保留与 fail-closed、T4/T1 运行时债务，以及 T7 样本统计入口。

仍不能发布，原因不在代码：

1. 没有生产 release keystore；release 构建按设计失败。
2. 版本号与“同包迁移 / 新包迁移”仍待发布负责人决定。
3. 所有真机项（AVF、QEMU 启动、64-forward 真实 gvproxy、迁移、release 签名安装升级）均未验证。
4. 若干 GPL 细节需要发布负责人/法务确认（见 §7）。

## 1. 提交

| 任务 | 提交 | 说明 |
| :--- | :--- | :--- |
| A + B | `5a56e3a` | 构建通道与 release 签名硬门 |
| D | `313c508` | GPL/NOTICE/SOURCE-OFFER 一致性 |
| C | `6dfa671` | release workflow、APK 校验与 PR 门 |
| E | `a5821cc` | SAF 导出/导入迁移与 fail-closed |
| F | `594f89e` | T4/T1 运行时债务 |
| G | `d37b1cd` | T7 矩阵样本统计 |
| F 测试支撑 | `960921d` | JVM 单测 no-op Log stub |
| 报告 | 本提交 | 修复报告与交接追加 |

作者均为 `Nyzeep <2950605702@qq.com>`。分支未合并 `main`，未 push `main`。

---

## 2. Wave 1

### A. 构建通道（`5a56e3a`）

`app/build.gradle.kts`：

- 新增 `orchestella.buildChannel`：默认 `release`，允许 `dev` / `qa`。
- `qa` 必须提供 `orchestella.buildPurpose`（`[a-z0-9-]{1,32}`）。
- 从 git 读取 7 位短 SHA；仅按 tracked 变更判断 `.dirty`（未跟踪构建垃圾不影响版本）。
- `debug` 构建按通道注入：
  - `dev`：`applicationIdSuffix=.dev`，`versionNameSuffix=-dev.<sha>[.dirty]`
  - `qa`：`applicationIdSuffix=.qa.<purpose>`，`versionNameSuffix=-qa.<purpose>.<sha>[.dirty]`
  - 默认 `release`：保持原 debug 行为（phase5 / 完整 release 签名分支仍保留）。
- 内部版强制使用 debug keystore；APK 额外输出到 `app/build/outputs/apk/channel/`。
- 未修改 `defaultConfig` 的 release `applicationId` / `versionName` / `versionCode`。

实测（干净 tracked 工作树，HEAD `db31302`）：

```text
默认：package: name='io.github.nyzeep.orchestella' versionCode='503' versionName='0.5.3'
dev ：package: name='io.github.nyzeep.orchestella.dev' versionCode='503' versionName='0.5.3-dev.db31302'
qa  ：package: name='io.github.nyzeep.orchestella.qa.runtimeperf' versionCode='503' versionName='0.5.3-qa.runtimeperf.db31302'
```

APK 名：

```text
Orchestella-Studio-v0.5.3-dev.db31302.apk
Orchestella-Studio-v0.5.3-qa.runtimeperf.db31302.apk
```

脏工作树构建已验证会追加 `.dirty`；干净树不追加。`qa` 缺 purpose、非法 channel、release 携带 purpose 均会在配置阶段失败。

**风险（未解决）**：`purpose` 允许短横线，但 Android package segment 通常不允许 `-`；本次只验证了 `runtimeperf`。若产品要使用 `runtime-perf` 这类 purpose，需要先决定“appId 中的 `-` 转 `_`”还是“限制 purpose 字符集”。

### B. Release 签名硬化（`5a56e3a`）

- 删除 `orchestella2026` 默认口令。
- `release` signingConfig 只在四个属性齐全时创建：
  `storeFile` / `storePassword` / `keyAlias` / `keyPassword`。
- 新增 `validateReleaseSigning`，并挂到 `preReleaseBuild` / `assembleRelease` / `bundleRelease` 等任务；缺项时在编译/打包前失败。
- `phase5Debug` 保留为历史隔离 QA 配置，注释标明永不用于 release。

实测：

```text
./gradlew :app:assembleRelease
> Task :app:validateReleaseSigning FAILED
> Release signing is not configured. Missing Gradle properties: ...
BUILD FAILED
```

`./gradlew :app:signingReport`：

```text
Variant: release
Config: null
Store: null
Alias: null
```

`:app:assembleDebug` 不受影响；`app/build.gradle.kts` 中已无 `orchestella2026`。

### C. Release workflow 与发布门（`6dfa671`）

新增 `.github/workflows/release.yml`：

- 仅 `workflow_dispatch`；必须选择 `confirm=yes` 并输入期望 `versionName`。
- 从 secrets 注入 keystore（base64）与三个口令；不打印 secret，结束时删除临时 keystore 与 `gradle.properties`。
- 构建 `:app:assembleRelease` 后：
  - `aapt2 dump badging` 校验 applicationId / versionName / versionCode；
  - `apksigner verify --print-certs` 拒绝 `CN=Android Debug`；
  - 生成 APK SHA-256；
  - 运行许可证/NOTICE/source-offer 检查；
  - 按 manifest 保留相对路径打包，上传 artifact。
- 不创建 tag，不创建 GitHub Release，不自动发布。

新增工具：

- `tools/release/verify_apk.py`：APK 模式 + metadata fixture 模式；失败退出非 0；支持 `--json`。
- `tools/release/check_release_fields.py`：PR 门，拒绝 release 字段携带 `dev/qa/dirty/SHA`、校验 versionCode 公式、拒绝 push 触发 release。
- `tools/release/test_verify_apk.py`：11 个标准库单测。
- `ci.yml` 新增独立 `release-metadata` job；未改变既有测试步骤语义。

实测：

- `verify_apk.py` 对当前 QA Debug APK 返回失败：applicationId、versionName、dirty/SHA、Debug 证书全部命中。
- 对 `tools/release/fixtures/release-metadata.json`（非 Debug 证书）返回 PASS。
- `actionlint 1.7.12` 对三个 workflow 均通过。
- YAML 解析通过。

**未测**：真实 GitHub Actions 运行、真实 release keystore、生产证书 apksigner 校验。

---

## 3. Wave 2

### D. GPL / NOTICE / SOURCE-OFFER（`313c508`）

核对后修正：

1. **项目许可证**：README 与 ADR-0014 明确规定 `GPL-3.0-or-later`。`guest-assets/GPL-SOURCES.md` 原先把项目代码写成 Apache-2.0，已改为 GPL-3.0-or-later，并逐项区分内核、BusyBox、e2fsprogs、musl、LLVM 的第三方许可证。
2. **musl 版本并非同一构建**：
   - `NOTICE.GUEST-RUNTIME` 的 1.2.6-r2 对应 `tools/phase5/guest-agent-inputs.lock.json` 的 Alpine musl 包；
   - `guest-assets/GPL-SOURCES.md` 的 1.2.5 对应 `guest-assets/inputs/inputs.lock` 的 initramfs 构建输入。
   两者是不同产物，未强行统一；NOTICE 已明确写出各自 lock，检查脚本按组件各自的 lock 校验。
3. **缺失政策引用**：`NOTICE.QEMU` / `QEMU-GPL-SOURCES.md` 引用的 `GPL_RELEASE_DELIVERY_POLICY.md`、`QEMU_JNI_SUPPLY_CHAIN_POLICY.md` 不存在。没有伪造历史正文；引用改为真实存在的 `docs/adr/0014-licensing.md` §3 与 `guest-assets/SOURCE-OFFER.txt`。
4. **SPDX 遗留**：`guest-assets/display/display-relay.py` 与 `guest-assets/initramfs/init` 嵌入的 `Apache-2.0` SPDX 已改为 `GPL-3.0-or-later`；`NOTICE.PHASE5` 中项目模块的 Apache 表述已改。
5. **release bundle**：新增 `tools/release/release_bundle_manifest.json`（17 个 repo 侧文件），release workflow 按相对路径打包，保留 app assets 与 tools 两份 QEMU 记录，避免同名覆盖。

新增 `tools/release/check_licenses.py`（33 项 PASS）与 9 个单测，检查：

- 根 LICENSE 是 GPLv3；
- NOTICE.GUEST-RUNTIME 与 agent lock、GPL-SOURCES 与 initramfs lock 的 musl 版本一致；
- NOTICE/GPL 文档中所有仓库相对路径存在；
- 项目源 SPDX 与 NOTICE.PHASE5 不再声称 Apache；
- SOURCE-OFFER / QEMU-GPL-SOURCES / LICENSE.GPL-2.0 / NOTICE 均在 manifest 中。

**仍需法务/发布负责人确认**：

- shm stub 由旧 NOTICE 的 Apache-2.0 改为 GPL-3.0-or-later（依据 ADR-0014 的项目默认许可）；2026-09-25 用户裁定只涉及 ENOSYS 方案，不涉及许可证，建议法务确认。
- `tools/qemu-android/gpl/QEMU-GPL-SOURCES.md` 与 `app/src/main/assets/QEMU-GPL-SOURCES.md` 的 §4 历史 hash/agent 记录不同（Ticket 26 vs Phase 5）。本次未改历史正文；发布前需确认哪份与随包二进制一一对应并同步。
- `SOURCE-OFFER.txt` 的履约主体、三年有效期起算与请求渠道。
- guest-assets 构建产物（`LICENSE.musl`、`NOTICE.e2fsprogs`、`ATTRIBUTION.e2fsprogs-libcom_err`、`BUILD-INPUTS`、`SHA256SUMS`）不在仓库，必须从已验证 build out 补入发布包。

### E. SAF 导出/导入迁移（`a5821cc`）

审计结论：

- 原导出只有根盘、基础元数据、校验和；缺少 hostname、默认用户、sudo、硬件配置、装配证据。
- 原导入会 `REPLACE_EXISTING` 覆盖目标卷；重复实例、重复 zip entry、超限/截断、store 保存失败回滚均不完整。

修复：

- `InstanceExportMetadata` 新增可选字段：hostname、defaultUser、hasSudo、CPU/RAM/disk/GPU/extra kernel args、provisioning marker/rootfs SHA/时间；旧 10 字段 JSON 仍可解析。
- 导入恢复上述配置与 `ProvisioningEvidence`，避免导入卷被当作未装配而重走完整 provisioning。
- 保持安全契约：导入实例的 guest password 仍为空（卷内已有凭据未知），bridge token 重新生成。
- fail closed：目标卷已存在、实例 ID 已存在、重复已知 entry、路径穿越/绝对路径、root volume 超 2TiB、metadata >1MiB、checksums >64KiB、截断/篡改全部拒绝。
- 校验全部通过后才移动临时卷到最终路径；`store.save` 失败删除本次新建卷，避免半实例。
- SAF document 导出在打开/截断流之前调用 `requireExportable`，避免破坏旧备份。

测试：contract/instance 模块新增 round-trip、旧 JSON 兼容、目标存在、重复 ID、重复 entry、路径穿越、超限、截断、store 失败回滚；app SAF fake 覆盖拒绝时不打开流、document/tree 导出扩展字段。

**待决策**：

- 同包迁移：生产签名必须能覆盖旧包；公开 v0.4.5 是 Debug 证书，无法直接升级到生产签名同包。若同包，需要先解决签名轮换/卸载重装与数据保留。
- 新包迁移：推荐新 applicationId + 导入向导；旧包先导出，新包导入。设备上需验证根盘、hostname/user、硬件配置与装配证据保留，以及实例可启动。
- 是否需要在备份中保留 guest password：当前明确不保留；若产品要求“导入后 App 仍能显示/使用密码”，需要单独的加密凭据迁移决策。

### F. T4 / T1 运行时债务（`594f89e`）

**T4 锁外停止**：

- `PersistentTerminalSessionPool.SessionHolder` 增加 STOPPING 完成量；`register/unregister/clear` 在 transition lock 内只标记 STOPPING，实际 `stopOwnedRuntime` 在锁外执行，完成后重新加锁移除/保留 owner。
- 等待者阻塞在 holder 的 stop monitor，而不是 transition lock；未确认停止不会发布第二个 owner。
- `requestStop` 在 STOPPING 期间快速返回 false；`completeExit` 在 STOPPING 期间拒绝执行退出副作用。
- 新增测试：同一实例 stop 被 latch 卡住时 `requestStop` 1s 内返回 false（旧实现会阻塞）；`completeExit` 不在停止中执行；原有跨实例不阻塞/延迟 unregister 不误删 replacement 测试保留。

**afterLaunchFailure**：

- 移除 `synchronized (PersistentTerminalSessionPool.getInstance())`；仅依赖 `InstanceLaunchGuard` generation + registry 检查。
- `cleanupFailedLaunch` 返回 `StopResult`，`QemuInstanceLauncher` 以 consume-once map 传给 `MainActivity`。
- `enteredRunning && cleanupResult == UNCONFIRMED` 时返回 false，保留 RUNNING 元数据、launch guard、前台绑定；CONFIRMED 时仍记录 CRASHED。

**T1 JSONL**：

- `LaunchTimeline` schema 2，新增 `stopAttempt` / `stopFinal`。
- 首次 UNCONFIRMED 写 `stopFinal=false`；retry 确认后追加补偿记录 `stopFinal=true`，消费者取最后一条即可；普通重复 `finish()` 不重复写。
- 单测覆盖 UNCONFIRMED→CONFIRMED 的两行记录与 schema allowlist。

**ControlChannelRecoveryTest**：恢复“持有 terminalLock 时 register + close + recover 断言”的覆盖；停止已不占 transition lock，因此不需要把 close 移到锁外。

**response pump 时序 flake**：

- 根因在测试服务端只读一行 HELLO；客户端握手重试会合法重发 HELLO。
- 测试 helper 现在接受重复 HELLO（每行仍要求 token 完全一致），但仍强制 PING/PONG 鉴权证明；未放宽协议断言。
- `:runtime:contract:bridgeClientResponsePumpTest` 连续 20 次全部通过。

**范围说明**：有界 retirement stop 已移出 transition lock；`requestStop` 的电源动作（bridge SHUTDOWN / terminate）仍在 transition lock 内串行执行，这是为了与 `completeExit` 的退出分类保持原子性，不是第二个 stop owner。若后续要求该动作也完全锁外，需要单独设计“动作进行中”状态。

### G. T7 / 64-forward（`d37b1cd`）

- `tools/qemu-tuning/matrix.py` 新增 `--samples` 设备样本入口：JSON 为 `{variant: {workload: [ms...]}}`。
- 每个单元 n≥10 才输出 `measured`，并输出 median / IQR / min / max；不足 10 标 `insufficient-samples`，无样本标 `not-tested`。
- 保留设备型号、Android、温度、热状态、fingerprint hash；dry-run 不伪造数据。
- 新增 7 个标准库单测。
- 64-forward 的 JVM 夹具已存在于 `RuntimeProcessSupervisorTest`（64 个 service forward、有界停止窗口）；本分支未改生产默认值。真实 gvproxy 的 64-forward 停止窗口标 `not-tested`。

### H. T5/T6 只读输入盘（deferred）

未实现。原因：该改动同时涉及 `QemuLaunchSpec`、guest initramfs 启动顺序与 embedded-input 兜底；零设备无法验证 829MB Arch 首启、initrd 内存与 guest 挂载行为。按任务要求不把半成品接入默认路径。

设计建议（deferred）：

1. `QemuLaunchSpec` 增加只读 `vdb` 输入盘参数（默认关闭，不影响现有 `vda` 根盘）。
2. guest initramfs 优先探测 `/dev/vdb` 的 rootfs 镜像/输入包并校验 SHA-256；失败回到 embedded input。
3. 增加 JVM 参数测试与 guest smoke 脚本；设备验证通过后再切换默认路径。
4. 需要设备证据：Arch 829MB 首启、initrd 大小/guest 内存、重复启动、只读盘校验失败回退。

---

## 4. 验证命令与结果

```bash
./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check \
  :app:testDebugUnitTest --offline --no-daemon
```

结果：

| 范围 | 数量 | 结果 |
| :--- | ---: | :--- |
| app JVM suites | 105 | 836 tests / 0 failures / 0 errors |
| runtime:contract | 24 | 0 failures |
| runtime:instance | 15 | 0 failures |
| runtime:engine-qemu | 2 | 0 failures |

```bash
./gradlew :app:lintDebug --offline --no-daemon
# BUILD SUCCESSFUL；0 Error / 753 Warning
```

其他：

- `python3 tools/release/check_licenses.py`：33 passed / 0 failed。
- `python3 tools/release/test_check_licenses.py`：9 tests OK。
- `python3 tools/release/test_verify_apk.py`：11 tests OK。
- `python3 tools/release/check_release_fields.py`：PASS。
- `actionlint 1.7.12`：三个 workflow 全部通过。
- `python3 tools/qemu-tuning/test_matrix.py`：7 tests OK。
- `git diff --check`：通过。
- `assembleRelease` 无签名属性：按设计失败（清晰错误）。
- `signingReport` release：Config/Store/Alias 均为 null。
- `verify_apk.py` 对 Debug/QA APK：按设计失败；metadata fixture：PASS。
- `bridgeClientResponsePumpTest`：20/20 通过。

---

## 5. `not-tested`（设备/密钥/平台）

以下全部未执行，不得写成设备通过：

1. AVF 真实 Guest、protected/non-protected 适配。
2. QEMU 真机启动、停止、孤儿进程检查。
3. 64-forward 真实 gvproxy 停止窗口与端口复用。
4. T7 设备矩阵（n≥10、控温、median/IQR）。
5. Arch 829MB 首启、T5/T6 只读输入盘。
6. 主包/新包迁移：旧包导出 → 新包导入 → 实例启动、凭据/hostname/根盘保留。
7. release-signed APK 安装/升级、证书与 SHA-256 真机校验。
8. GitHub Actions `release.yml` 实际运行（仅本地 YAML/actionlint 验证）。
9. 真实生产 keystore 的 `assembleRelease`、`apksigner` 校验。
10. 16 KB 真机运行（既有对齐结论未在本分支复测）。

---

## 6. 建议的下一步（设备验证清单）

1. 由发布负责人提供生产 keystore（不入库），用 `release.yml` 构建一次 release candidate；核对 applicationId、versionName、versionCode、证书 SHA-256、APK SHA-256。
2. 在无冲突设备上演练迁移：
   - 旧包安装/导入数据 → 导出 → 新包/新签名安装 → 导入 → 启动 → 登录 → 检查 hostname/user/硬件配置/装配证据。
3. QEMU 真机：启动、正常停止、强制停止、重复启动、无孤儿 `qemu`/`gvproxy`。
4. 64-forward：真实 gvproxy 打开 64 个 service forward，测停止窗口与端口复用。
5. T7：按 `matrix.py --samples` 采集 n≥10，控温，确认无统计显著收益前不改默认值。
6. Arch 829MB 首启与 T5/T6 决策。
7. 设备侧验证 release-signed APK 安装/升级路径。
8. 发布前由法务/发布负责人关闭 §7 的 GPL 待确认项。

---

## 7. 需要发布负责人决定 / 法务确认

| 项 | 现状 | 需要谁 |
| :--- | :--- | :--- |
| 生产签名 keystore 与 secrets | 无；release 按设计失败 | 发布负责人 |
| 版本与通道 | 源码仍 0.5.3 / 503；未递增 | 发布负责人 |
| 同包 vs 新包迁移 | 代码已支持导出/导入保留配置；包名/签名策略未定 | 发布负责人 |
| guest password 迁移 | 导入保持“未知”，bridge token 新生成 | 产品/安全 |
| shm stub 许可证 | 旧 NOTICE 写 Apache-2.0，本分支按 ADR-0014 改为 GPL-3.0-or-later | 法务/发布负责人 |
| QEMU 记录两份副本 | app assets 与 tools 的 §4 历史记录不同 | 发布负责人 |
| SOURCE-OFFER 履约主体/有效期/渠道 | 文件存在，机制未定 | 法务/发布负责人 |
| guest-assets 构建产物 | 仓库缺失，需从 verified build out 补入 | 发布负责人 |
| release workflow 是否直接发布 | 当前只上传 artifact，不建 tag/Release | 发布负责人 |

---

## 8. 新发现的风险

1. **QA purpose 短横线**：标准允许 `-`，但 Android applicationId segment 可能拒绝；目前只验证 `runtimeperf`。
2. **QEMU 记录副本漂移**：两份 `QEMU-GPL-SOURCES.md` 的 §4 内容不同；release bundle 已保留两份路径，但发布时仍要指定哪份是随包记录的权威副本。
3. **Import metadata 仍是手写 JSON 解析器**：新增字段为可选、旧 JSON 兼容，但没有引入完整 JSON 库；后续若继续扩展字段，建议迁移到严格解析器并保留兼容测试。
4. **STOPPING 等待上界 30s**：若 stop worker 异常死亡，等待者超时返回失败；不会发布第二个 owner，但需要日志/诊断确认。
5. **release workflow 首次真实运行**：secrets 名称、keystore base64、runner SDK 版本均未在 GitHub 上实测。
## 9. 交付状态

- 本地分支 `fix/release-readiness` 已包含上述提交；所有提交的 author 与 committer 均为 `Nyzeep <2950605702@qq.com>`。
- **GitHub push 已完成**：SSH key 经 `ssh.github.com:443` 认证为 Nyzeep；由于远端已存在旧的同任务分支 `023ed0f`，经确认使用 `--force-with-lease` 覆盖为本地提交。
- 远端：`git@github.com:Nyzeep/Orchestella-Studio.git` 的 `fix/release-readiness`。
- 本分支未合并 `main`，未打 tag，未创建 GitHub Release。
