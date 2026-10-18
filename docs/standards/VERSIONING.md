# Orchestella 语义化版本命名硬约束标准 (Versioning Standard)

> **状态**：项目级硬约束 (Active Hard Policy)  
> **生效日期**：2026-10-14  
> **约束范围**：所有构建产物、Release 标签、Gradle 配置、以及 Orchestrator / Sub-Agent 开发会话

---

## 1. 规范基础：行业通用语义化版本 2.0.0 (SemVer 2.0.0)

Orchestella 项目的所有发布版本与构建标签严格遵循 [Semantic Versioning 2.0.0](https://semver.org/) 规范，格式为：

$$\text{MAJOR}.\text{MINOR}.\text{PATCH}[-\text{PRERELEASE}]$$

### 1.1 字段语义与严格判定标准

| 级别 | 名称 | 递增触发条件（依据修改性质） | 进位规则 |
| :--- | :--- | :--- | :--- |
| **MAJOR** | 主版本号 | 1. 包含破坏向后兼容性的重大架构重构或契约变更<br>2. 移除已公开的契约能力或存储格式不再自动兼容旧实例<br>3. 产品发生根本性代际演进 | `MAJOR++`<br>`MINOR = 0`<br>`PATCH = 0` |
| **MINOR** | 次版本号 | 1. 新增用户可见的重要功能或全新页面（如新增独立发行版大卡片、关于与在线更新检测）<br>2. 现有模块深度重构与体验升级（如主题系统双模动态跟随）<br>3. 底层能力矩阵向后兼容扩展（如新增 Linux 发行版镜像支持、AVF 框架兼容性判定增强） | `MINOR++`<br>`PATCH = 0` |
| **PATCH** | 补丁版本号 | 1. 向后兼容的纯 Bug 修复（如解压缓存校验修复、空指针防护、边距对齐）<br>2. 性能调优、零功能变动的日志/文档修缮<br>3. 单元测试用例补充与 CI 配置优化 | `PATCH++` |
| **PRERELEASE** | 预发布标签 | 仅在内测、候选阶段使用，格式如 `-preview.1`, `-beta.1`, `-rc.1`。正式发布时必须移除。 | 视阶段迭代 |

---

## 2. Android `versionCode` 映射硬约束

为防止多个构建版本同时使用同一版本号导致应用商店、ADB 或系统安装器识别混淆与覆盖冲突，`versionCode` 必须满足**单调递增性**：

$$\text{versionCode} = \text{MAJOR} \times 10000 + \text{MINOR} \times 100 + \text{PATCH}$$

- 示例：
  - `0.3.0` $\rightarrow$ `versionCode = 300`
  - `0.3.1` $\rightarrow$ `versionCode = 301`
  - `0.4.0` $\rightarrow$ `versionCode = 400`
  - `1.0.0` $\rightarrow$ `versionCode = 10000`

---

## 3. Agent 与构建流水线执行硬准则

1. **版本号不可回退与不可复用**：已打 tag、已构建或已对外分发的版本号绝对禁止再次包含不同的代码提交。
2. **每次发布必须评估变更性质**：
   - 包含新页面、新交互、新特性的 PR/Commit，严禁仅递增 PATCH。
   - 仅修复已知 issue 且无任何交互或功能扩展的，必须递增 PATCH。
3. **版本更新落地文件清单**：
   - `app/build.gradle.kts` (`versionName`, `versionCode`)
   - `docs/standards/VERSIONING.md` (版本迭代审计追踪)
   - Git Tag 统一前缀：`vX.Y.Z` (如 `v0.4.0`)

---

## 4. 当前版本状态

- 源码：**0.5.3 / code503**（main `881e37a`，2026-10-18；runtime-performance 合并未改变版本号）。
- 公开安装包：**v0.4.5** 开发预览版；0.5.0–0.5.3 为本地验证版本，未打 tag、未发布。
- `0.5.x` 的版本记录只表示本地构建与验证范围，不代表已公开分发；公开发布仍需生产签名、对应源码、NOTICE 与验收门。
- 内部开发/调试构建的版本标识与发布版本分离，见第 5 节；不得再用 `0.5.3` 这种发布版本名指代某个内部构建。

## 5. 构建通道：发布版 / 内部开发版 / 隔离 QA 版

### 5.1 目的

把“对外发布的版本号”和“内部开发/调试构建的标识”分开，避免：

- 内部调试包与发布包使用同一 `applicationId`，覆盖用户数据或触发签名冲突；
- 用 `0.5.3` 这类发布版本名指代某个内部 commit，无法判断实际代码；
- 把 Debug 签名的 APK 当作公开发布物；
- 内部构建为了“版本号更大”而污染发布版本的单调递增序列。

### 5.2 三个通道

| 通道 | applicationId | versionName | versionCode | 签名 | 分发 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **发布版 Release** | `io.github.nyzeep.orchestella` | `X.Y.Z` 或 `X.Y.Z-preview.N` / `-beta.N` / `-rc.N` | `MAJOR×10000 + MINOR×100 + PATCH` | release keystore | GitHub Release / 公开 |
| **内部开发版 Dev** | `io.github.nyzeep.orchestella.dev`（并行会话可加 `.<owner>`） | `X.Y.Z-dev.<gitShortSha>`（脏工作树加 `.dirty`） | 与当前 release base 相同 | debug keystore | 仅本地 / ADB |
| **隔离 QA 版** | `io.github.nyzeep.orchestella.qa.<purpose>` | `X.Y.Z-qa.<purpose>.<gitShortSha>` | 与当前 release base 相同 | debug keystore | 仅本地 / ADB；独立数据 |

- `<purpose>` 只允许小写字母、数字和短横线，例如 `runtimeperf`、`input`、`avf`。
- 内部版可以带 `.dirty`；发布版禁止 `.dirty`。
- 内部版**不得修改** `app/build.gradle.kts` 中 release 的 `versionName` / `versionCode`；后缀通过 Gradle 属性或构建类型注入。
- 同一 `<purpose>` 的 QA 包可以并存于设备；不得用发布版 applicationId 覆盖用户数据。
- `phase5` 与 `.runtimeperf` 是历史隔离 QA 通道；新工作必须使用本节约定，旧构建类型的迁移另行立票。

### 5.3 硬约束

1. **发布版命名**：`versionName` 必须匹配 `^[0-9]+\.[0-9]+\.[0-9]+(-(preview|beta|rc)\.[0-9]+)?$`；不得包含 `dev`、`qa`、`dirty` 或 commit SHA。
2. **发布版 applicationId**：必须是 `io.github.nyzeep.orchestella`；带 `.dev` / `.qa` 后缀的 APK 一律不得进入公开 Release。
3. **发布版签名**：证书不得为 `CN=Android Debug`；发布包必须能用指定 release 证书校验。
4. **Tag**：只有发布版使用 `vX.Y.Z` tag；内部开发版与 QA 版不打 tag，身份由 commit SHA 表示。
5. **versionCode**：发布版保持单调递增；内部版复用当前 release base，不用内部构建推进发布序列。由于 applicationId 不同，Android 将其视为独立包，不产生跨通道升级/降级。
6. **发布清单**：每个公开发布物必须记录 `versionName`、`versionCode`、commit SHA、证书 SHA-256、APK SHA-256 和对应源码位置。
7. **CI / 发布门**：发布流水线必须用 `aapt2 dump badging` 校验 applicationId 与 versionName，用 `apksigner verify --print-certs` 校验证书；任一不符合即失败。
8. **APK 命名**：
   - `Orchestella-Studio-vX.Y.Z-release.apk`
   - `Orchestella-Studio-vX.Y.Z-dev.<gitShortSha>.apk`
   - `Orchestella-Studio-vX.Y.Z-qa.<purpose>.<gitShortSha>.apk`
9. **界面标识**：内部版与 QA 版应在“关于 / 设置”中显示通道与 commit SHA；在实现前，至少保证 APK 文件名和 `applicationId` 能区分。
10. **版本推进边界**：合并到 `main` 不等于发布，不因合并自动递增版本号；只有准备公开发布时才更新 `app/build.gradle.kts` 与本文审计表，并在真机和发布门通过后打 tag。

### 5.4 示例

| 场景 | applicationId | versionName | versionCode | 是否可发布 |
| :--- | :--- | :--- | :--- | :--- |
| 发布 | `io.github.nyzeep.orchestella` | `0.5.3` | 503 | 是 |
| 内部开发 | `io.github.nyzeep.orchestella.dev` | `0.5.3-dev.881e37a` | 503 | 否 |
| 隔离 QA | `io.github.nyzeep.orchestella.qa.runtimeperf` | `0.5.3-qa.runtimeperf.881e37a` | 503 | 否 |

## 6. 版本迭代审计历史

| 版本号 | versionCode | 发布日期 | 变更性质评估 | 关键修改摘要 |
| :--- | :--- | :--- | :--- | :--- |
| `0.5.3` | 503 | 2026-10-16（中断恢复后本地验证，未发布） | PATCH | 闭合独立审查发现的三项会话竞态：停止请求绑定点击时的会话，注册后先建立退出监督再刷新通知，启动代际采用排他准入。保留 502 及原始失败证据，最终构建、复审和设备结果另行记录。 |
| `0.5.2` | 502 | 2026-10-16（本地集成验证，未发布） | PATCH | 集成有证据的会话退出代际、停止意图与前台通知防串代，自有装配日志路由、AltGr/死键和向导系统重建草稿恢复；独立 QA 包复验，保留旧签名包与数据。设备 AVF 与架构门按实际结果单列。 |
| `0.5.1` | 501 | 2026-10-16（隔离QA，未发布） | PATCH | 输入 AltGr/死键与会话退出修复的首个冻结验证切片；以独立 `.phase5` 包验证原向导 Activity 重建。非工单最终完成包，不覆盖旧签名包或既有数据；后续不同源码APK继续递增。 |
| `0.5.0` | 500 | 2026-10-15（本地研发，未发布） | MINOR | 新增普通 Shizuku 客户端状态与用户主动授权；统一两项 AVF 权限与诚实预检，修复清洁检出的 Guest 生成/原始字节校验，增加只读设备与样本验证工具。AVF VM 代理、真实 Guest 和性能仍待设备及架构门；不替换既有发布。 |
| `0.4.5` | 405 | 2026-10-15（开发预览版） | PATCH | 替换原 0.4.2 发布页并合并两轮修复：终端点击键盘、重复登录、主题一致性、Guest 包恢复和更新检查；保留 0.4.2 历史标签，发布 APK 沿用已真机验证的 405 版本。 |
| `0.4.4` | 404 | 2026-10-15（真机修复验证，未发布） | PATCH | 修复终端收起输入法后点击输出区无法重新唤起键盘的问题，调整焦点优先级并保留可选择文本；新增真机 IME 隐藏/显示与输入连接归属回归。 |
| `0.4.3` | 403 | 2026-10-15（本地研发，未发布） | PATCH | 开发目录与主分支收敛；构建默认使用官方仓库、镜像显式启用，Guest Python 依赖与入口补齐；更新检查按语义版本及开发预览通道筛选，防止降级提示。 |
| `0.4.2` | 402 | 2026-10-15（开发预览版） | PATCH | 汇总 0.4.0 以来修复并发布开发预览版；在 0.4.1 真机验证基础上，修复即时明暗切换重建页面导致向导状态丢失的风险，使用应用上下文解析真实系统模式；README 补充预览状态、验证范围与反馈指引。 |
| `0.4.1` | 401 | 2026-10-15（本地验证，未发布） | PATCH | 修复 Fedora / Debian 控制台重复登录、终端协议应答回路、显式浅深色选择与系统主题冲突、ANSI 索引色不跟随主题及装配预设按钮排版；作为本轮首次真机验证构建。 |
| `0.3.0` | 300 | 2026-10-13 | MINOR | 全面支持 7 大 Linux 官方镜像拉取与完整 Linux 契约基座 |
| **`0.4.0`** | **400** | **2026-10-14** | **MINOR** | **重大功能新增与界面重构**：<br>1. 7 大发行版独立卡片与一键装配界面（去除冗余下拉选择）<br>2. 主题引擎全面跟随系统暗色/亮色，20 款调色板全配暗/亮双模<br>3. 新增关于主界面与基于 GitHub Releases 的在线检测更新功能<br>4. 修复 QEMU 启动实例解包校验失败（PackagedAssetStreams 直接流式读取）<br>5. 修复物理机 AVF "1" 判定失败 Bug，优化预检状态反馈<br>6. 修复卡片 4dp 边距未对齐与底部导航图标渲染缺陷 |

> 2026-10-18 runtime-performance 合并（main `881e37a`）未改变 0.5.3 / code503；该合并属于内部开发合并，不是新的发布版本。
