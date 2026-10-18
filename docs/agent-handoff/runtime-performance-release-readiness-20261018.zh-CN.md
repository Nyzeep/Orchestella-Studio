# runtime-performance 发布交接：阻塞、优化方案与零设备修复任务

> **基线**: `main 20a3507`（运行时性能合并 `881e37a`，版本标准 `a32b477`，发布说明草稿 `20a3507`）  
> **当前版本**: 0.5.3 / code503（未递增；公开最新 v0.4.5 预发布）  
> **性质**: 发布准备交接，不是发布报告；本文不授权合并、打 tag 或上传 APK。

## 0. 当前已验证状态

- 代码：T1/T2/T3/T4/T7/T8 已合入 main；T5/T6 deferred。
- 本地：app 105 suites / 823 tests / 0 failures；contract 24；instance 15；engine-qemu 2；lint 0 error / 748 warning。
- CI：PR #3 `Contract & Unit Tests` pass（3m33s）。
- 真机（HONOR MEY-AN00 / Android 16 / API 36）：
  - T2 marker 已记录：`provisioning.markerVersion=1`，Ubuntu SHA-256 `a91d5a93...`。
  - T3 快速路径：initramfs `30,225,552 B → 1,327,092 B`，`rootfsBytes 29,936,675 → 0`，`INITRAMFS_BUILT 1115 ms → 62/75 ms`。
  - T4：运行中强制停止 `CONFIRMED` 约 102 ms；正常停止幂等 `ALREADY_STOPPED`；无 `qemu`/`gvproxy` 孤儿。
  - 旧实例回退：移除 evidence 后回到 `30,225,552 B / 1005 ms`，`CONFIRMED`。
  - 首次基础环境：最终 `status=ready`、`stage=verified`、`last_operation_stage=ssh-start`（exit 0）；此前的 `attempt=2` 失败是停止中断（exit 143），不是产品缺陷。
- 未验证：Arch 829 MB 首启、T5/T6 只读输入盘、T7 设备矩阵、64-forward 真实 gvproxy 窗口、主包升级/迁移。

## 1. 发布阻塞（全量）

| ID | 阻塞 | 当前证据 | 类型 | 谁处理 |
| :--- | :--- | :--- | :--- | :--- |
| B1 | **没有生产 release 签名** | `:app:signingReport`：`Variant: release / Config: null / Store: null`；仓库无 `.toolchains/release.keystore`；现有 APK 全为 Debug（`3a1ea26b...`） | 密钥 + 配置 | 发布负责人提供 keystore；代码侧改造签名配置 |
| B2 | **版本号/发布决策未定** | 源码 `0.5.3 / 503`；无 `v0.5.3` tag；公开最新 `v0.4.5` 预发布 | 产品决策 | 发布负责人 + 版本标准 |
| B3 | **主包升级/数据迁移未解决** | 公开 v0.4.5 是 Debug 证书；同包生产签名不能直接覆盖；实例数据在 App 私有目录 | 决策 + 代码 + 设备 | 发布负责人 + 零设备代码 + 真机演练 |
| B4 | **没有 release workflow / 发布门** | 仅 `ci.yml`、`guest-assets.yml`、`qemu-android.yml`；无证书/版本/SHA256/源码包自动校验 | 代码 | 零设备环境 |
| B5 | **GPL/NOTICE 不一致** | 根 `LICENSE` 是 GPL-3.0，但 `guest-assets/GPL-SOURCES.md` 写 guest agent/脚本为 Apache-2.0；`NOTICE.GUEST-RUNTIME` 写 musl 1.2.6 而 `inputs.lock` 是 1.2.5；`NOTICE.QEMU` 引用不存在的 `docs/agent-handoff/GPL_RELEASE_DELIVERY_POLICY.md`；`SOURCE-OFFER.txt` 存在但无 release 打包/校验 | 代码 + 法务复核 | 零设备环境 + 发布负责人 |
| B6 | **设备覆盖不足** | Arch、T5/T6、T7 矩阵、64-forward、主包升级均未测 | 设备 | 设备环境（代码完成后） |
| B7 | **构建通道未落地** | 新标准只在 `VERSIONING.md`；Gradle 无 `-Porchestella.buildChannel`，CI 无 dev/qa 后缀与 Debug 证书检查；当前 debug APK 仍用主包名；`phase5`/`.runtimeperf` 是历史通道 | 代码 | 零设备环境 |
| B8 | **运行时债务** | T4 停止仍在 per-instance lock 内；`InstanceLaunchSequence.afterLaunchFailure` 仍同步池单例；`ControlChannelRecoveryTest` 原“持锁恢复”覆盖变弱；T1 stop retry JSONL 保留第一次结果；`bridgeClientResponsePumpTest` 一次时序 flake | 代码 | 零设备环境 |
| B9 | **当前设备主包数据冲突** | user 0/100/128 的 `io.github.nyzeep.orchestella` 均 `installed=false`，但 user 128 留有另一签名 code300 数据；不能卸载/清数据 | 设备/数据决策 | 发布负责人 + 设备环境 |
| B10 | **发布说明未定稿** | `docs/reports/releases/runtime-performance-next-release-draft.zh-CN.md` 为草案，版本占位 `vX.Y.Z` | 文档 | 发布负责人 + 零设备环境 |

### 1.1 签名配置的额外风险

`app/build.gradle.kts` 的 release signing 目前有默认口令：

```kotlin
storePassword = (properties["orchestella.signing.storePassword"] as String?) ?: "orchestella2026"
keyPassword = (properties["orchestella.signing.keyPassword"] as String?) ?: "orchestella2026"
```

即使提供 keystore，也不应使用仓库默认口令。零设备分支必须改为“缺少显式 signing 属性时 release 构建失败”，并从仓库移除默认口令字面量。

## 2. 新的优化方案

### W1 发布信任与合规（P0，零设备可做大部分）

- 签名配置硬化：release 必须显式提供 `storeFile/storePassword/keyAlias/keyPassword`；无默认口令；缺项时 `assembleRelease` 明确失败；debug 构建不受影响。
- 构建通道：`-Porchestella.buildChannel=dev|qa`、`-Porchestella.buildPurpose=<purpose>`；应用 `.dev` / `.qa.<purpose>` applicationId 与 `-dev.<sha>` / `-qa.<purpose>.<sha>` versionName；APK 命名带通道；发布版默认不变。
- Release workflow：`workflow_dispatch` + secrets；构建后校验 `applicationId`、`versionName`、证书非 Debug、SHA-256；生成来源清单；不自动发布，发布动作留给人工/tag。
- 发布门脚本：`aapt2 dump badging` + `apksigner verify --print-certs` + SHA256；PR 检查拒绝 release 字段带 dev/qa 后缀或 Debug 证书。
- GPL/NOTICE：修正许可证表述与 musl 版本；解决缺失政策引用（新建明确的新政策或改引用，不得伪造历史正文）；确保 `SOURCE-OFFER.txt`、对应源码与 NOTICE 进入 release bundle。

### W2 迁移与数据保留（P0，决策 + 代码 + 设备）

- 决策：同包 `io.github.nyzeep.orchestella` + 生产签名（0.5.4 / 504，需导出/导入迁移）或新包 `io.github.nyzeep.orchestella.studio`（0.6.0 / 600，推荐，导入向导）。
- 代码：审计 SAF 导出/导入是否覆盖根盘、实例元数据、凭据、hostname、用户配置；补齐导入校验与失败回滚；不改变应用私有存储边界。
- 设备：在无冲突设备上演练“旧包导出 → 新包/新签名导入 → 实例可启动、数据保留”；当前 user 128 冲突设备不做破坏性操作。

### W3 运行时收尾（P1，零设备代码 + 设备验证）

- T4：把停止真正移出 transition lock（或给出可证明的 STOPPING 状态/完成量设计）；修复 `afterLaunchFailure` 与 per-instance lock 不再互斥的问题；恢复 `ControlChannelRecoveryTest` 的持锁覆盖。
- T1：stop retry 后 JSONL 反映最终结果（追加补偿记录或明确状态字段），不要静默保留第一次。
- T5/T6：只读输入盘（宿主 + guest），代码 + JVM/smoke；设备上验证 Arch 829 MB 首启与 initrd 内存。
- T7/64-forward：补设备测试夹具；T7 默认值不变，统计未达标不改默认。

### W4 设备验证与发布（P0，代码完成后）

- Arch 首启/重复启动、T5/T6 决策；T7 矩阵（n≥10、控温）；64-forward 真实 gvproxy；主包/新包迁移；release-signed APK 安装与升级。
- 全部通过后：按 `VERSIONING.md` 完成版本递增、release notes 定稿、tag `vX.Y.Z`、GitHub Release、SHA256、对应源码与 NOTICE。

## 3. 零设备环境任务图

> 零设备环境没有 adb/真机；只做代码、测试、CI、文档。所有设备项明确标 `not-tested`，不得用 JVM fake 冒充设备结论。

| 波次 | 任务 | 依赖 | 主要文件 | 验收 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | A 构建通道 | 无 | `app/build.gradle.kts` | `-P...=dev/qa` 产生正确 applicationId/versionName/APK 名；默认 release 不变 |
| 1 | B 签名硬化 | 无 | `app/build.gradle.kts` | 无属性时 release 失败且信息明确；debug 可构建；仓库无默认口令 |
| 1 | C Release workflow/门 | 无 | `.github/workflows/release.yml`、`tools/release/*` | 证书/版本/applicationId/SHA256 校验；不自动发布 |
| 2 | D GPL/NOTICE | 无 | `guest-assets/GPL-SOURCES.md`、`app/src/main/assets/NOTICE.*`、`guest-assets/SOURCE-OFFER.txt` | 许可证/版本/引用一致；无伪造历史政策；release bundle 清单完整 |
| 2 | E 导出/导入审计 | 无 | SAF adapters、`InstanceStorageManager` | 根盘/元数据/凭据 round-trip；失败回滚；设备项 `not-tested` |
| 2 | F T4/T1 债务 | 无 | supervisor、pool、`InstanceLaunchSequence`、tests | 锁边界/覆盖/retry JSONL 有测试；不改变三态语义 |
| 3 | G T7/64-forward 夹具 | A/F | `tools/`、tests | 可在设备环境直接运行；零设备只做 dry-run |
| 3 | H T5/T6（可选） | A/F | `QemuLaunchSpec`、guest initramfs | 代码+JVM 测试；设备验证留到回流 |

### 3.1 冲突热点

- `app/build.gradle.kts`：A 与 B 都会改；先 A 后 B，或同一会话顺序完成。
- `.github/workflows/`：C 独占；不要动 `ci.yml` 的既有步骤语义。
- `guest-assets/`：D 与 H 可能都碰 guest 资源；D 只改许可证/NOTICE，H 只改输入与 initramfs。
- `RuntimeProcessSupervisor` / `PersistentTerminalSessionPool`：F 独占；不要顺手改 T3/T7。
- `docs/`：本文与发布说明草稿；更新时保留既有审计表。

## 4. 设备-only 验证清单（代码回流后）

1. Arch 829 MB：首次装配、initrd 大小/guest 内存、重复启动；决定 T5/T6。
2. T7 单变量矩阵：CPU model、`tb-size`、`aio`、discard、smp、netdev；n≥10、控温；无统计显著收益不改默认。
3. 64-forward：真实 gvproxy 停止窗口、无孤儿、端口可复用。
4. 主包/新包迁移：旧包导出 → 新包导入 → 实例启动、凭据/根盘/hostname 保留。
5. release-signed APK：证书非 Debug、applicationId/versionName 正确、SHA256、安装/升级。
6. 首次基础环境 `status=ready` 与旧实例回退（已有一次证据，可复跑）。

## 5. 版本规定

- 遵循 `docs/standards/VERSIONING.md` 第 5 节：发布版 / 内部开发版 / 隔离 QA 版分离；合并 main 不等于发布。
- **零设备修复分支不修改 release `versionName`/`versionCode`，不打 tag，不发布。**
- 发布前由发布负责人选择：
  - 同包：`0.5.4 / 504`（PATCH；但 v0.4.5 是 Debug 证书，必须导出/导入迁移）。
  - 新包：`0.6.0 / 600`（推荐；新 applicationId + 导入向导；同步更新版本标准中的 release applicationId）。
- 发布说明草稿：`docs/reports/releases/runtime-performance-next-release-draft.zh-CN.md`；定稿时替换占位版本并补最终设备证据。

## 6. 禁止事项

- 不生成、导出或提交签名密钥；不把口令写入仓库、日志或对话。
- 不修改 release 版本号、不打 tag、不创建 GitHub Release。
- 不伪造缺失的 `GPL_RELEASE_DELIVERY_POLICY.md` 历史正文；如新建政策，明确标注“新政策”并交发布负责人审核。
- 不使用 `cache=unsafe`；不新增第二个 stop owner；不改变 ADR-0020 三态语义。
- 不卸载/清数据/跨 user 绕过当前设备的主包数据。
- 不把 JVM/fake/dry-run 结果写成设备通过。

## 7. 交接产物

- 分支：`fix/release-readiness`（从 `main 20a3507` 切出）。
- 提交：按 A–H 分组，中文 commit；不合并 main。
- 报告：`docs/reports/orchestration/release-readiness-fix-<date>.zh-CN.md`，含提交、测试、未测项、待决策项。
- 回传：在本文件追加 `## Fix Report`，列出 commit、测试命令/结果、`not-tested`、需要发布负责人决定的事项。
