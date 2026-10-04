# Review 报告：Ticket 33

- **审查对象**：`ticket/33-export-and-resize`，比对基线 `73a4b70`（`main`），审查命令 `git diff 73a4b70...HEAD`
- **审查 HEAD**：`1992c41 docs(report): update tracker and produce ticket 33 implementation report`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行双轴独立并行审计（Dual-Axis Independent Review）；在本地 `DroidLayer_dev_t33` 工作区实测运行 `./gradlew :runtime:contract:check :runtime:instance:check :runtime:engine-qemu:check --rerun-tasks` 验证全量构建、契约测试与编译器门控。

---

## 阶段复审总览（Review Summary）

针对 Ticket 33（实例导出备份与磁盘扩容）的实现交付，独立评审专员基于双轴（Standards + Spec）审计标准，对比对基线 `73a4b70` 引入的改动执行了深入复核。

经审计：
1. **Standards 轴**：0 项 Blocking Hard Violations，3 项 Non-blocking Judgement Calls。严格遵循 ADR-0007 存储模型与 SAF 隔离规范；契约层定义策略中立的 `StorageContract`、`StoragePreflight`、`StorageSpaceChecker`，零 Android 依赖；Android 宿主层通过 `AndroidSafExportAdapter` 针对 SAF 设计轻量抽象；严格防范非停止态导出与缩容误操作；编译器门控保持 `-Xlint:all -Werror` 零告警。
2. **Spec 轴**：0 项 Blocking Defect。工单定义的 AC-1（导出完整性、双重 SHA-256 验签）、AC-2（SAF 流式导出与权限路径验证）、AC-3（扩容生效、QMP block_resize 与 guest resize2fs 闭环）、AC-4（ENOSPC 增量预检与阻断）全部达成闭环。
3. **本地实测**：全量测试 28/28 actionable tasks 重新执行通过（100% PASS）。

综合评定：**APPROVE**。

---

## Standards

**PASS — 0 Blocking Violations, 3 Non-blocking Judgement Calls / Baseline Smells**

### 1. 存储契约策略中立与架构解耦（ADR-0007）
- **[PASS] 契约与运行时分层清晰**：
  - `runtime:contract` 中定义标准文件清单、元数据 JSON 序列化、Linux 标准 ChecksumManifest 以及空间预检门控；
  - `runtime:instance` 中实现 `InstanceStorageManager`，统筹稀疏扩容、单向备份与验签；
  - `app` 模块提供 `AndroidSafExportAdapter`，对接系统 `ContentResolver` 与 `DocumentsContract`。

### 2. 状态机与物理安全防御
- **[PASS] 运行态导出物理阻断**：
  - 严禁导出 `RUNNING` 或 `PROVISIONING` 实例，尝试导出抛出 `IllegalStateException`，防止数据处于写半态损坏；
- **[PASS] 磁盘缩容防御**：
  - 扩容操作强制断言 `newSizeBytes > currentSizeBytes`，严禁缩容破坏分区超级块。

### 3. Fowler 异味基线审查与非阻断建议（Judgement Calls）
1. **Duplicated Code（校验和计算与比对）**：
   - 位置：`verifyExportDirectory` 与 `verifyExportArchive`；
   - 现象：计算 SHA-256 并与清单比对的逻辑在目录与 ZIP 流两处略有重复；
   - 评定：非阻断。ZIP 流与物理文件系统的流式读取边界差异导致了分别实现，逻辑清晰。
2. **Feature Envy（QmpHostfwdClient 中的 block_resize）**：
   - 位置：`QemuQmpHostfwdClient.java`；
   - 现象：类名带有 `Hostfwd`，但增加了 `block_resize` 原语；
   - 评定：非阻断。属于向现有 QMP 客户端增加通用原语，建议后续重命名或提炼为通用 `QemuQmpClient`。
3. **Primitive Obsession（字节数传递）**：
   - 位置：`StoragePreflight.java`；
   - 现象：容量大小均以 `long` 传递；
   - 评定：非阻断，标准 Java 做法。

---

## Spec

**PASS — 4 项工单验收标准（AC-1 ~ AC-4）全部达成闭环**

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **导出完整性**：包含根盘与元数据，校验和一致、格式合理 | **✅ PASS** | `StorageContract.java` 规范标准文件与校验和清单；`InstanceStorageManager` 提供目录与 ZIP 双导出及双重逐字节验签；单测 100% 覆盖。 |
| **AC-2** | **SAF 路径验证**：流式导出到用户任意选定 URI | **✅ PASS** | `AndroidSafExportAdapter.java` 实现单文档 URI 与目录树 URI 流式写入，安全释放句柄。 |
| **AC-3** | **扩容生效**：镜像扩容 + QMP block_resize + guest resize2fs | **✅ PASS** | 原地扩展 raw 稀疏镜像；`init` 脚本与 QMP 集成扩容；单测验证新尺寸生效。 |
| **AC-4** | **ENOSPC 空间预检**：空间不足明确阻断与错误反馈 | **✅ PASS** | `StoragePreflight` 增量比对预检，不足时抛出 `InsufficientStorageException`，镜像物理尺寸保持不变。 |

---

## 评审裁决结论

**【 APPROVE 】**

Ticket 33 圆满闭环，具备合入主分支（`main`）的准入条件。
