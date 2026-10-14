# 资产分类保全与精确瘦身治理规范（Asset Classification, Retention & Selective Hygiene Policy）

> **文档标识**：POLICY-20261001-ASSET-HYGIENE  
> **生效日期**：2026-10-01  
> **适用范围**：DroidLayer 项目全体 Orchestrator、Worker 与 Child 会话  
> **密级与状态**：项目级强制防御性约束（Project-Level Hard Defensive Constraint · Active Policy）  
> **版本**：v1.0-Binding

---

## 1. 核心战略意图与治理哲学

在 DroidLayer 跨代际工程演进过程中，构建环境的初始化与高确定性离线依赖的闭包（如 Linux 内核源码树、NDK 工具链、交叉编译环境、JDK/Maven 闭包、官方 rootfs 底包等）耗费了数十个班次的严苛审计与漫长校验。

过往在阶段切换时出现的“一刀切全盘删除、下阶段推倒重配”的做法，不仅造成了极大的算力与时间浪费，更带来了重复拉包可能引入的网络漂移与供应链破损风险。

**本政策确立的核心铁律是**：
> **“高价值生产力资产永久复用、避免重复构建与漫长拉包；实施精细化分类治理，仅精确清理无用纯消耗物。”**

---

## 2. 资产三级分类法与法定处理规范

DroidLayer 的所有外部与本地资源严格划分为以下三类，任何会话在执行磁盘维护或文件变更时必须无条件遵守：

```
                    ┌──────────────────────────────────────────────┐
                    │          DroidLayer 项目资产三级分类架构       │
                    └──────────────────────┬───────────────────────┘
                                           │
         ┌─────────────────────────────────┼─────────────────────────────────┐
         ▼                                 ▼                                 ▼
【Class A: 永久复用基石资产】       【Class B: 阶段性增量资产】         【Class C: 纯临时消耗物】
- SDK / NDK / Gradle 离线工具链    - 阶段性规范产物与 Release APK      - 编译中间 .o / Ninja 缓存
- 内核源码树 / 官方 rootfs 底包    - 物理硬件测试原生证据与 XML Dump   - 自动化测试随机哈希沙箱
- 离线 WSL / Podman 构建沙箱       - 签名锁线与验收清单                - temp/ 目录一次性日志与 NUL
（❌ 严禁删除 / 跨阶段永久挂载）    （🔒 严格归档 / 仅保留最终 Canonical） （🧹 经核对后安全定向清理）
```

### 2.1 Class A：永久复用基石资产（Immutable Reusable Assets）
**定义**：耗时巨大构建完成、具备强幂等性、且后续阶段持续依赖的基础设施、工具链与离线源码镜像。  
**法定动作**：**永久保全，跨阶段强制复用，任何会话与脚本严禁以“释放磁盘”为名将其删除。**

| 资产标识 | 物理存储路径 | 战略用途与复用价值 | 保全级别 |
| :--- | :--- | :--- | :--- |
| **Android 离线 SDK 与工具链** | `DroidLayer_dev/.toolchains/` | 包含官方 API 36 / Build-Tools 36.1.0、Platform 36、Gradle 8.13 离线分发包及 Maven 本地缓存。所有 Android 模块开发必须直接挂载，杜绝联网重复下载。 | **Top Secret · 永久保全** |
| **Linux 内核与系统服务底包** | `DroidLayer/artifacts/26/linux-pin/`<br>`DroidLayer/artifacts/26/busybox-pin/`<br>`DroidLayer/artifacts/26/openrc-pin/` | Linux 6.6 LTS 源码树、4-byte/16-page 补丁及系统服务构建配置。Phase 1 双引擎演进与后续内核维护直接复用。 | **Strict · 永久保全** |
| **官方发行版纯净 Rootfs** | `DroidLayer/artifacts/26/official-alpine-rootfs-20260921/` | 官方校验通过的 Alpine Linux 纯净底包。Phase 2 多发行版装配与容器底模制作的核心源泉。 | **Strict · 永久保全** |
| **离线构建沙箱（WSL 生产力底座）** | `DroidLayer/test-runtimes/ticket26-wsl-ubuntu/`<br>（约 19.8 GiB） | 包含已验证的 41-deb JDK closure、148-GA Maven 闭包与全套交叉编译工具链的 Podman/WSL 离线编译沙箱。后续编译 QEMU 增量补丁、AVF 镜像打包或 Debian 镜像预处理的核心生产力设施，禁止删除。 | **Critical · 永久保全** |
| **物理硬件原生证据** | `DroidLayer/artifacts/26/.../evidence/` | 包含 HONOR 80 GT 真机验收的 34 件原生截屏、Window Dump XML 与控制台凭证。证明 Ticket 26 验收事实的唯一物理原件。 | **Canonical · 永久保全** |
| **核心基线产物** | `app-debug.apk`（60,148,288 B，SHA-256 `95efecbf...`） | 唯一通过全部五步门硬化与 167 键锁线锁定的基准二进制母包。 | **Milestone · 永久保全** |

---

### 2.2 Class B：阶段性增量资产（Incremental Stage Assets）
**定义**：各 Ticket 交付物中生成的中间版本、报告附件与阶段性锁文件。  
**法定动作**：**阶段收口时仅保留 Canonical 唯一真品，多余冗余备份去重归档。**

1. **报告与数据原件**：规范归档至 `docs/reports/tickets/<id>/` 与 `docs/reports/reviews/<id>/`；
2. **锁线同步**：以各工具链目录下的 canonical lock 文件（如 `tools/qemu-android/qemu-jni.lock`）为唯一事实，删除本地临时分支生成的派生 lock 副本。

---

### 2.3 Class C：纯临时消耗物（Pure Ephemeral Waste）
**定义**：编译或测试运行过程中产生的一次性临时文件，无重现依赖，不具备跨阶段复用价值。  
**法定动作**：**在阶段收口前或磁盘空间预警时，经白名单核对后定向安全清理。**

1. **编译过程临时中间物**：
   * 在已成功生成并校验了目标二进制（如 `libdroidlayer_qemu.so`）之后，源码树内产生的 `.o`、`.d`、Ninja/Meson 构建缓存目录（如 `builder-io/` 下的中间生成树）；
2. **自动化测试生成的散列沙箱**：
   * 运行测试脚本留下的形如 `native-prebuild-test-*`、`build-record-test-*` 的随机哈希临时沙箱；
3. **一次性临时调试目录**：
   * `DroidLayer/artifacts/26/temp/`（约 820 MB 纯临时测试输出）；
4. **系统异常产生的残留废弃文件**：
   * Windows 重定向失误在代码根目录产生的零字节文件 `NUL`。

---

## 3. 清理准入红线与四步防卫流程（Clean-up Defense Gate）

任何调度执行清理任务的 Worker 或脚本，必须严格执行以下四步准入红线，**严禁使用宽泛的通配符（如 `rm -rf artifacts/26/*`）或未经白名单校验的批处理脚本**：

```text
[Step 1: 扫描与清单生成] ──> [Step 2: Class A 排除断言] ──> [Step 3: 用户披露与确认] ──> [Step 4: 原子定向清理]
```

### 3.1 准入断言清单
- [ ] **断言 1（SDK 工具链未受影响）**：`DroidLayer_dev/.toolchains` 路径完好无损；
- [ ] **断言 2（WSL 生产力底座未受影响）**：`DroidLayer/test-runtimes/ticket26-wsl-ubuntu` 目录大小与结构未被改动；
- [ ] **断言 3（内核与 rootfs 底包未受影响）**：`artifacts/26/linux-pin`、`busybox-pin` 与 `official-alpine-rootfs` 完好存在；
- [ ] **断言 4（原生证据未受影响）**：34 件硬件实测原生证据与最终 60MB APK 未被移动或删除；
- [ ] **断言 5（单次清理对象明确性）**：删除目标必须为逐项明细列出的物理子路径，严禁使用指向高层父目录的递归删除。

---

## 4. 与其他治理文档的从属与引用关系

1. 本政策是 [`HOST_ENVIRONMENT_POLICY.md`](HOST_ENVIRONMENT_POLICY.md) 关于项目外部资源根（`DroidLayer`）清理规则的具体化与强化补充；
2. 本政策是 [`ARTIFACT_ORGANIZATION_POLICY.md`](ARTIFACT_ORGANIZATION_POLICY.md) 关于非 Git 外部资源生命周期的唯一操作准则；
3. 任何派工数据包（Dispatch Packet）在涉及“环境清洁”或“瘦身”要求时，必须显式引用本政策的 Class A 保全清单。
