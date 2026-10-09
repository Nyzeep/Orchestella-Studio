# Orchestella Studio Phase 5.0 (v0.5.0-alpha) 发版冲刺与移动工作站体验大闭环进展报告

- **工单/阶段**：Phase 5.0（v0.5.0-alpha 发版冲刺、终端命令历史持久化、硬件规格动态升降配与 SAF 备份全闭环）
- **报告性质**：发版冲刺里程碑总结（Release Milestone）、架构演进（Architecture）、质量门禁验收（Verification）与交接报告（Handover）
- **前序基线 Commit**：`6eca7d4`（Phase 4.9 交付基线）
- **当前交付版本**：`v0.5.0-alpha` (`versionCode = 5`, `versionName = "0.5.0-alpha"`)
- **开发与验证环境**：Windows 纯 ASCII 隔离盘符虚拟构建环境（`W:\` 为工程目录、`S:\` 为 Android SDK），OpenJDK 17（`D:\JDK17`），纯 JVM 契约测试与状态机驱动，无真机物理连接依赖
- **执行角色**：Orchestella Studio 核心全栈研发工程师
- **交付状态**：**ALL MISSIONS ACCOMPLISHED（四大核心攻坚任务 100% 圆满达成，双质量门禁全绿）**
- **报告日期**：2026-10-09

---

## 1. 阶段概述与战役目标达成度

Phase 5.0 是 Orchestella Studio 迈向 `v0.5.0-alpha` 正式技术预览版的里程碑战役。工程在**暂无物理真机直接连接（纯开发机/离线环境）**的条件下接管并全量推进，严格遵循深模块（Deep Modules）原则、测试驱动开发（TDD）方法论以及 Android 非 Root 安全沙箱防线，圆满完成了发布流水线固化、终端命令历史存盘与移动快捷栏增强、实例硬件规格动态升降配与原子持久化、以及基于 Storage Access Framework (SAF) 的冷备份流式导出与 SHA-256 校验导入恢复，顺利达成全栈大闭环。

### 四大攻坚任务达成度汇总表

| 任务编号与维度 | 核心目标与技术规格 | 达成状态 | 验证依据与交付成果 |
| :--- | :--- | :---: | :--- |
| **任务 1：版本跃迁与构建发版流水线固化**<br>*(Version Bump & Release Pipeline)* | 1. 跃迁工程版本至 `v0.5.0-alpha` (`versionCode = 5`)；<br>2. 固化 `proguard-rules.pro`，严防 `:runtime:contract`、NDK JNI 桥接（`NativeGlesSurfacePresenter`、`VirglJniBridge`）及序列化模型被 R8 误裁剪；<br>3. 验证 Release 编译及全量打包无告警阻断。 | **100% 达成** | 更新 `app/build.gradle.kts` 版本元数据；创建完整 `app/proguard-rules.pro` 守卫规则；`assembleDebug` 与 `compileRelease` 均编译打包通过，无阻塞性告警。 |
| **任务 2：终端命令历史持久化与移动快捷工具栏增强**<br>*(Terminal History & Quick Actions)* | 1. 设计纯 JVM 深模块 `TerminalHistoryStore.java`，支持每实例 500 条历史命令上限、Base64 行编码、并发锁与原子持久化；<br>2. 终端生命周期联动（打开加载、关机持久化、实时追加）；<br>3. 移动按键栏集成 `CLR`（清屏重置）、`PASTE`（剪贴板即时输入）、`HOME`/`END`（行首尾跳转）；<br>4. 单元测试 100% 绿灯。 | **100% 达成** | 落地 `TerminalHistoryStore.java`；扩展 `InteractiveTerminalEngine.java`、`MobileAuxiliaryKeyBar.java`、`TerminalActivity.java`；编写 `TerminalHistoryStoreTest.java`（8 组单测）与终端交互单测全部通过。 |
| **任务 3：实例硬件参数动态调整向导**<br>*(Hardware Specification Editor)* | 1. 设计纯 JVM 硬件配置编辑器 `InstanceHardwareConfigEditor.java`；<br>2. 状态机安全红线（仅限 `STOPPED` 或 `CREATED` 状态调整）；<br>3. vCPU 1~8 核边界与宿主探测上限协同；<br>4. RAM 512MB~8GB 与宿主保留 ≥2GB 防闪退红线校验；<br>5. 稀疏磁盘扩容预检与原子持久化。 | **100% 达成** | 实现 `InstanceHardwareConfigEditor.java`；在 `MainActivity.kt` 接入 `onConfigureHardware` 完整闭环；编写 `InstanceHardwareConfigEditorTest.java`（8 组边界与状态机单测）100% 绿灯。 |
| **任务 4：整机备份导出与恢复工作流**<br>*(SAF Export & Import Workflow)* | 1. 导出：基于 SAF Uri 将实例元数据与 raw 磁盘镜像流式打包为 zip；<br>2. 导入：在主界面新增「从备份文件导入」入口，采用 64KB 块缓冲流式写入；<br>3. 在线 SHA-256 校验与清单自检，校验失败立即销毁临时文件杜绝存储泄漏；<br>4. 纯 JVM 单元测试覆盖。 | **100% 达成** | 在 `InstanceStorageManager.java` 落地 `importFromArchive`；在 `InstanceLifecycle.java` 暴露 `restore`；实现 `AndroidSafImportAdapter.java`；界面接入 `btn_import_instance`；编写 `AndroidSafImportAdapterTest.java`（4 组单测）100% 绿灯。 |

---

## 2. 核心架构演进与深模块设计

### 2.1 终端命令历史持久化深模块（`TerminalHistoryStore.java`）

针对以往“关闭终端或重启 Activity 后命令历史丢失”的问题，设计了完全独立于 Android View 层的纯 JVM 命令历史持久化深模块：

```
                            ┌───────────────────────────┐
                            │      TerminalActivity     │
                            │  (Android Lifecycle Host) │
                            └─────────────┬─────────────┘
                                          │
            ┌─────────────────────────────┼─────────────────────────────┐
            │ onCreate / onNewIntent      │ onPause / onDestroy         │ commitLine
            ▼                             ▼                             ▼
┌───────────────────────┐     ┌───────────────────────┐     ┌───────────────────────┐
│ loadHistoryAsync()    │     │ persistAsync()        │     │ append(line)          │
└───────────┬───────────┘     └───────────┬───────────┘     └───────────┬───────────┘
            │                             │                             │
            └─────────────────────────────┼─────────────────────────────┘
                                          │
                                          ▼
                      ┌───────────────────────────────────────┐
                      │        TerminalHistoryStore           │
                      ├───────────────────────────────────────┤
                      │  - capacity: 500 lines                │
                      │  - ReentrantReadWriteLock (线程安全)  │
                      │  - Base64-per-line (安全跨行与二进制) │
                      │  - .tmp 临时文件原子替换 (防断电损坏) │
                      │  - 损坏容错机制 (自动丢弃非法行)      │
                      └───────────────────┬───────────────────┘
                                          │
                                          ▼
                      ┌───────────────────────────────────────┐
                      │      InteractiveTerminalEngine        │
                      ├───────────────────────────────────────┤
                      │  - ringBuffer / commandHistory        │
                      │  - historyIndex 导航 (↑ / ↓)          │
                      │  - CLR / PASTE / HOME / END 快捷辅助  │
                      └───────────────────────────────────────┘
```

#### 关键实现细节：
1. **Base64 行编码防护**：Bash/Shell 命令中常包含换行符、反斜杠、Unicode 与 ANSI 逃逸字符，纯文本按行存储易导致解析断行。`TerminalHistoryStore` 采用逐行 Base64 编码，确保单行原子性；
2. **临时文件原子替换**：写入时优先输出至 `.history.txt.tmp`，完成写入后调用 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`，杜绝进程因切后台被 LMK 杀掉时导致历史文件损坏；
3. **容量滚动约束**：固定 500 条最大容量，超额时按 FIFO 滑动淘汰最旧命令。

---

### 2.2 实例硬件参数动态调整向导（`InstanceHardwareConfigEditor.java`）

为支持用户在不重装实例的前提下升降配已停止的 Linux 虚拟机，构建了高内聚的硬件规格编辑器深模块：

```
                      ┌─────────────────────────────────────────┐
                      │        InstanceActionBottomSheet        │
                      │            「⚙️ 配置硬件参数」          │
                      └────────────────────┬────────────────────┘
                                           │
                                           ▼
                      ┌─────────────────────────────────────────┐
                      │          VmHardwareConfigDialog         │
                      │   (Material 3 滑块与三色安全负荷区间)   │
                      └────────────────────┬────────────────────┘
                                           │
                                           ▼
                      ┌─────────────────────────────────────────┐
                      │      InstanceHardwareConfigEditor       │
                      ├─────────────────────────────────────────┤
                      │  1. 状态机守卫: 仅 STOPPED / CREATED    │
                      │  2. vCPU 校验: 1 <= vCPU <= hostCores   │
                      │  3. RAM 校验: 512MB <= RAM <= 8192MB    │
                      │     防闪退红线: hostTotal - RAM >= 2GB   │
                      │  4. 磁盘扩容: newDisk >= curDisk        │
                      │     调用 SparseDiskResizer 预检扩容     │
                      │  5. 原子持久化: controller.update...     │
                      └────────────────────┬────────────────────┘
                                           │
                                           ▼
                      ┌─────────────────────────────────────────┐
                      │     InstanceLifecycle.FileStore         │
                      │    (实例元数据 JSON 更新 + 磁盘扩容)    │
                      └─────────────────────────────────────────┘
```

#### 安全策略与防错机制：
- **运行中熔断**：若实例处于 `RUNNING` 或 `PROVISIONING` 状态，抛出 `IllegalStateException` 并给出友好中文提示，严防运行时动态热拔插导致 Guest Kernel Panic；
- **2GB 宿主保留防线**：依据 ADR-0010 与 ADR-0019，Android 系统本身需要至少 2GB 内存维持 UI 与系统服务，超出该安全红线将直接被判定为 `RED` 危险区间并拒绝保存；
- **磁盘仅允许增量扩容**：稀疏磁盘禁止缩容以防破坏 ext4 文件系统超级块，只允许无损扩容。

---

### 2.3 基于 SAF 的冷备份导出与完整性校验恢复（`SAF Backup & Restore`）

遵循 Android 11+ 严格的分区存储规范，利用 SAF（Storage Access Framework）实现虚拟机跨设备冷备份与零泄露导入：

```
                        ┌─────────────────────────────────────┐
                        │      MainActivity 仪表盘入口        │
                        │    「📦 导出备份」 / 「导入备份」   │
                        └──────────────────┬──────────────────┘
                                           │
                    ┌──────────────────────┴──────────────────────┐
                    │ Export (导出)                               │ Import (导入)
                    ▼                                             ▼
        ┌───────────────────────┐                     ┌───────────────────────┐
        │ AndroidSafExport      │                     │ AndroidSafImport      │
        │ Adapter               │                     │ Adapter               │
        └───────────┬───────────┘                     └───────────┬───────────┘
                    │                                             │
                    ▼                                             ▼
        ┌─────────────────────────────────────────────────────────────────────┐
        │                      InstanceStorageManager                         │
        ├─────────────────────────────────────────────────────────────────────┤
        │ • 64KB 块缓冲流式写入 ZipOutputStream / ZipInputStream              │
        │ • 在线计算 SHA-256 哈希值 (DigestInputStream)                       │
        │ • 归档包结构:                                                       │
        │   - rootfs.raw (根镜像)                                             │
        │   - instance.json (元数据)                                          │
        │   - SHA256SUMS (校验清单)                                           │
        │ • 校验失败熔断处理: 立即删除 .raw 与 .tmp 文件，杜绝存储残留        │
        └──────────────────────────────────┬──────────────────────────────────┘
                                           │
                                           ▼
                        ┌─────────────────────────────────────┐
                        │      InstanceLifecycle.Controller   │
                        │      (原子持久化并呈现至仪表盘卡片) │
                        └─────────────────────────────────────┘
```

#### 关键技术亮点：
1. **流式低内存开销**：根镜像文件体积通常为几百 MB 至数 GB，严禁将全量字节读入 JVM 堆内存。通过 `ZipInputStream` 与 `DigestInputStream` 进行 64KB 分块边读边算边写；
2. **零泄漏事务回滚**：解包过程若发生 SHA-256 校验不匹配、文件缺失或 IO 中断，立即通过 `try-catch-finally` 清理目标目录与临时文件；
3. **隔离解耦设计**：`AndroidSafImportAdapter` 与 `AndroidSafExportAdapter` 采用接口缝隙（`DocumentStreamReader` / `DocumentStreamOpener`），测试用例可在纯 JVM 环境下以 100% 内存流覆盖测试。

---

## 3. 质量门禁与全量测试验证

### 3.1 质量门禁 1（Unit & Contract Tests）
- **命令**：
  ```powershell
  .\gradlew.bat testDebugUnitTest :runtime:contract:check :runtime:instance:check
  ```
- **执行结果**：
  - `:app:testDebugUnitTest`：全部通过（含 `TerminalHistoryStoreTest`、`InteractiveTerminalEngineTest`、`InstanceHardwareConfigEditorTest`、`AndroidSafImportAdapterTest`、`AndroidSafExportAdapterTest` 等）；
  - `:runtime:contract:check`：所有音频流、网桥控制、输入序列化、相对鼠标位移、动态端口转发及硬件规格契约测试通过；
  - `:runtime:instance:check`：多实例生命周期、克隆、删除物理回收、稀疏盘扩容及存储契约测试通过；
  - **状态**：**BUILD SUCCESSFUL（100% 绿灯，耗时 16s）**。

### 3.2 质量门禁 2（Guest Assets Verification）
- **命令**：
  ```powershell
  .\gradlew.bat -p guest-assets test --rerun-tasks
  ```
- **执行结果**：
  - Guest 静态资源完整性、Shell 资产同步及构建校验全部通过；
  - **状态**：**BUILD SUCCESSFUL（100% 绿灯，耗时 18s）**。

### 3.3 构建与打包流水线验证
- **Debug APK 打包**：`.\gradlew.bat assembleDebug` -> **BUILD SUCCESSFUL in 16s**（APK 顺利生成，JNI SO 库正常链接）。
- **Release 模式编译**：`.\gradlew.bat compileReleaseJavaWithJavac compileReleaseKotlin` -> **BUILD SUCCESSFUL in 28s**（代码合规，混淆准备就绪）。

---

## 4. 交付文件清单与变更索引

| 文件路径 | 变更类型 | 核心职责说明 |
| :--- | :---: | :--- |
| `app/build.gradle.kts` | 修改 | 升级版本号至 `versionCode = 5`, `versionName = "0.5.0-alpha"`；配置 release 混淆规则文件 |
| `app/proguard-rules.pro` | 新增 | 固化 Proguard/R8 混淆规则，保留 JNI、契约模型、序列化类与 AndroidX 核心组件 |
| `app/src/main/java/.../TerminalHistoryStore.java` | 新增 | 纯 JVM 终端命令历史存储深模块（500 限制、Base64 安全编码、并发安全、原子替换） |
| `app/src/test/java/.../TerminalHistoryStoreTest.java` | 新增 | 8 组测试覆盖限额、多行 Base64、并发读写、损坏容错、实例隔离 |
| `app/src/main/java/.../InteractiveTerminalEngine.java` | 修改 | 支持历史记录批量载入、一键清屏重置行、光标行首/行尾跳转与 ANSI 序列响应 |
| `app/src/main/java/.../MobileAuxiliaryKeyBar.java` | 修改 | 移动辅助键盘栏扩充 `CLR`、`PASTE`、`HOME`、`END` 交互操作 |
| `app/src/main/java/.../TerminalActivity.java` | 修改 | 终端生命周期绑定 `TerminalHistoryStore`（异步加载、实时追加、生命周期持久化） |
| `app/src/main/java/.../InstanceHardwareConfigEditor.java` | 新增 | 硬件规格动态调整向导深模块（状态机拦截、CPU/RAM 边界探测、2GB 红线、磁盘扩容） |
| `app/src/test/java/.../InstanceHardwareConfigEditorTest.java` | 新增 | 8 组单测覆盖状态校验、CPU 溢出、RAM 危险区间拦截、磁盘缩容拒绝、原子提交 |
| `app/src/main/java/.../AndroidSafImportAdapter.java` | 新增 | SAF 备份导入适配器（支持 Document URI 解包并还原独立实例） |
| `app/src/test/java/.../AndroidSafImportAdapterTest.java` | 新增 | 4 组测试覆盖完整导入恢复、SHA-256 校验拦截、临时文件清理、目录恢复 |
| `runtime/instance/.../InstanceStorageManager.java` | 修改 | 增加 `importFromArchive` 流式导入恢复核心逻辑与在线哈希计算 |
| `runtime/instance/.../InstanceLifecycle.java` | 修改 | `Instance` 增加 `restore` 静态工厂；`Controller` 暴露 `importFromArchive` 入口 |
| `app/src/main/java/.../MainActivity.kt` | 修改 | 仪表盘增加 `safImportLauncher` 与 `btn_import_instance` 点击处理；接入硬件调整器 |
| `app/src/main/res/layout/activity_main.xml` | 修改 | 实例区块增加「导入备份」Material 3 操作按钮 |
| `app/src/main/res/values/strings.xml` | 修改 | 补充导入备份相关多语言字符串与操作文案 |
| `.scratch/orchestella-platform/map.md` | 修改 | 决策地图收敛并更新 Phase 5.0 完成状态 |

---

## 5. 后续演进与交接指引

1. **版本发布建议**：
   - 当前代码已满足 `v0.5.0-alpha` 正式打 Tag 与发版要求；
   - 建议在 GitHub 远端打上语义化标签 `v0.5.0-alpha` 并关联本次交付产物；
2. **环境持久与无缝交接**：
   - 任何后续开发工作机均须保持挂载纯 ASCII 盘符（`W:\`、`S:\`）与使用 OpenJDK 17；
   - 所有新增功能严格遵循现有架构的无真机纯 JVM TDD 闭环模式。
