# 研究报告：下一阶段构建、CI 与开发预览发布可靠性

- 日期：2026-10-15；执行者：ZCode / GPT-6.1，构建与发布研究子代理。
- 研究基线：Orchestella_merge_dev，v0.4.2，commit `44825b4d8ffe6b8010b68fc55b1ea8d464bd3f89`；读取时分支 `main`，初始工作树干净。未创建分支、worktree、commit、tag、issue 或 PR。
- 原始任务为只读背景研究；主代理后续明确授权有限实现：仅仓库镜像开关、Guest builder Python 3 依赖/入口/preflight 与隔离行为回归。更新检查器由其他代理负责；本文对此部分记录基线问题与实施建议，不声称其修改已经验收。
- 已先读取 `AGENTS.md`、`docs/agent-handoff/DEFENSIVE_CONSTRAINTS/` 内 README 与全部三个 policy，以及版本、宿主、资源组织、资产保全、报告与架构变更约束。
- 输出：本 canonical 研究文档；外部原始证据只写 `D:\Development project(开发项目)\Orchestella开发目录\Orchestella\artifacts\consolidation-20261015\research-ci`（下文记 `RESEARCH_EVIDENCE`）。主代理自己生成的 Gradle 证据位于同批 `evidence/`，本文只读引用，不复制成另一套结论。
- 本代理没有运行 Gradle、APT、容器、模拟器或真机，没有读取/修改签名材料、系统设置、SDK、依赖版本与 refs。联网仅拉取官方公开文档、官方源码与指定公开仓库的只读 Actions / Releases API；未上传本地源码或日志。

## 主代理最终验证补记

独立主仓副本 `Orchestella_main` 已实际运行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :runtime:contract:check`，95 项任务执行成功，耗时 1 分 47 秒；713 项 App 测试无失败，Android-test lint model 与全部 lint 分析任务成功。随后 `--offline` 复验 App 测试、lint、embeddedProvisionInputTest 与 qemuLaunchSpecTest 均成功。证据见同批 `evidence/gradle-independent-build.log.txt`、`gradle-independent-offline.log.txt` 及 `app-suite-count.json`。本次没有升级 AGP 或关闭检查。

因此下文保留的“待主代理”是研究封板时状态，现已由上述独立实测补齐。更新检查 slice 也已完成并纳入 713 项 App 套件：显式构建预览元数据、合法 SemVer 有序比较、防降级、固定仓库 Release 列表筛选，保留手动检查与关闭开关。GitHub CI/容器尚未重新运行。最初 adb 无设备，收尾时连接的 Honor 设备旧版签名与公开版本不同；兼容签名验证副本的覆盖安装仍要求机主指纹认证，故取消待安装，原版与实例数据保持不变。新 UI/输入验收未完成，不能将本地结果写成设备或云 CI 通过；详见本轮实施报告的设备补记。

## 一、可立即实施的结论与优先级

按解除已知阻塞、改动范围和用户兼容性排序，建议切成五个 slice。前两个已在主代理追加授权下完成源码与有限验证；第三个由主代理完成精确诊断和在线 metadata 解析，最终 lint 仍待主代理；第四、第五个是可交付边界明确的后续工作。

| 优先级 | Slice | 收益与具体交付 | 当前状态 |
| --- | --- | --- | --- |
| 1 | Guest builder 显式 Python 3 闭包 | 修复 JVM 测试通过后立刻 exit 127 的确定性阻塞；缺 Python 时在 Gradle 前失败；不借用不确定的 `python` 命令 | 已实现；6 项隔离执行回归与 shell 语法通过，APT/容器未运行 |
| 2 | 官方仓库默认、镜像显式 opt-in | 避免 CI 无条件先碰 Aliyun 及 Google 镜像对非 Google 坐标的错误请求；保留区域开发者选择 | 已实现；主代理真实 Gradle 验证默认/opt-in/非法值三种情况 |
| 3 | lint 模型依赖取证与闭包补齐 | 将 `path null` 表象追到 `debugAndroidTestCompileClasspath` 的离线缺失，恢复检查而不掩盖失败 | 已确认三项缺失；官方源在线 metadata 解析全部成功；artifact 与实际 lint 待主代理 |
| 4 | GitHub 通道与有序版本比较 | 修复当前全预发布仓库使 `/latest` 404，以及旧版本被当成升级的逻辑；保留用户关闭网络的语义 | 研究完成，交其他代理实施 |
| 5 | CI / 开发预览发布前置门 | SDK 初始化失败明确停机；contract/JVM、Guest 和 lint 都有可归因证据；发布版本与已验证 commit 绑定 | 建议下一阶段实施，不增加自动外部发布 |

## 二、确定事实与一手证据

### 2.1 两个真实 CI 故障属于不同层

1. [CI run 38684223942](https://github.com/Nyzeep/Orchestella-Studio/actions/runs/38684223942)，`head_sha` 为本研究基线，根项目配置阶段请求 `https://maven.aliyun.com/repository/google/com/sun/xml/bind/jaxb-bom-ext/2.3.2/jaxb-bom-ext-2.3.2.pom` 得到 HTTP 502。后续出现 `Repository maven is disabled due to earlier error`，并报告 71 个相同原因的失败。不能把后续 Kotlin 等解析失败分别当成 71 个依赖版本问题。[E1]
2. [Guest assets run 38684223879](https://github.com/Nyzeep/Orchestella-Studio/actions/runs/38684223879)，同一 commit 的 JVM tooling `BUILD SUCCESSFUL in 33s`，随后 `test-initramfs-init-fallback.sh: 10: exec: python: not found`，进程 exit 127。入口先查 `python3`，未找到才 `exec python`，所以该日志与脚本控制流共同证明容器没有可用的两个入口；不是 JVM 测试失败。[E2]
3. `guest-assets/ci/install-builder-dependencies.sh` 基线的 apt 与 `dpkg-query` 清单均没有 `python3`；`test-guest-runtime-provision.py` 则明确 Python 3 shebang，使用 pathlib、f-string 等。其工作根要求已有 approved external 目录。

### 2.2 GitHub 目前没有 stable release

只读 `GET /repos/Nyzeep/Orchestella-Studio/releases?per_page=100` 的实际结果仅有两项：`v0.4.2` 和 `v0.3.0-preview.1`，均 `draft=false, prerelease=true`。同次读取 `/releases/latest` 实际 HTTP 404。[E3]

`v0.4.2` 的 GitHub 名称为开发预览版，但 tag 与 APK `versionName` 是普通 `0.4.2`、`versionCode=402`，不含 `-preview`。这两个维度必须独立处理：GitHub 的 `prerelease` 是发布通道元数据，SemVer 的预发布后缀是版本排序信息。仅通过当前 versionName 含 `-dev` / `-preview` 推断通道会漏掉现存 v0.4.2 用户；不能重写其旧 tag 或给不同代码重新使用 402。[S8][S10][S11]

### 2.3 lint 已有精确下层故障，尚不能宣称最终恢复

主代理执行 `diagnoseLintResolution`，直接观察解析结果与 cause，而非先格式化整个异常，得到：

| Configuration | 唯一缺失坐标 | 原始 cause |
| --- | --- | --- |
| `debugAndroidTestCompileClasspath` | `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0` | `No cached version ... available for offline mode` |
| 同上 | `androidx.arch.core:core-runtime:2.1.0` | 同上 |
| 同上 | `androidx.core:core-ktx:1.2.0` | 同上 |

三项唯一坐标在依赖边中重复计数合计 6 个 unresolved；其他被测 `debugAndroidTest*`、`lintChecks`、`lintPublish` 配置为 0。原始证据为同批 `evidence/gradle-lint-resolution.log.txt`。随后以默认官方源在线运行同探针，所有这些配置 `RESOLUTION_FAILURES 0`，14s 成功，见 `evidence/gradle-lint-official-resolution.log.txt`。[E4]

因此已证实存在离线 dependency closure 缺口。`generateDebugAndroidTestLintModel` 的 `path null`、以及 Gradle 异常 `getMessage()` 再抛 NPE，必须当成上层/次级表现分别记录。metadata 解析恢复不等于 JAR/AAR 全部已下载，也不等于 lint task 已完成。本报告收口时真实 artifact 下载、`lintDebug` 和最终离线重验仍待主代理；不据此声称 AGP 升级必要或所有 lint 错误消失。[S6][S7]

## 三、Gradle 仓库优先级、5xx 与缓存的真实语义

### 3.1 顺序不是跨仓库高可用契约

Gradle 按声明顺序查找依赖；一旦在某仓库找到 module metadata，关联 artifact 从同一仓库获取。把 `google()`、`mavenCentral()` 写在镜像后面，并不保证镜像失败时自动转用官方源。[S1]

应区分三种情形：

- **Missing**：仓库正常返回未找到时，可以继续查后续仓库；不是所有 HTTP 非 200 都是 missing。
- **Failed**：HTTP 5xx 是解析异常。Gradle 8.13 的 `NetworkingIssueVerifier` 把 server error 归为 transient，可进行同一操作的有限重试；`ErrorHandlingModuleComponentRepository` 的默认值为最多 3 次总尝试，即首次加最多 2 次重试，初始 backoff 1000ms、随后倍增。这是所查版本源码的内部实现，不建议通过内部 system property 将其改成大重试或无限重试。[S3]
- **重试仍失败**：`ResolveExceptionAnalyzer.isCriticalFailure` 将根因是 server error 的异常判为 critical；`RepositoryChainComponentMetaDataResolver.findBestMatch` 的 Failed 分支会 `queue.clear()`，停止该轮后续仓库搜索。`ConnectionFailureRepositoryDisabler` 在 BuildTree scope 记录异常并禁用该 repository，使后续请求快速失败。本次 CI 的 repository disabled 连锁错误与该路径相符。它不是永久改写仓库或全局删除缓存。[S3][E1]

因此，“Aliyun → official”或“official → mirror”的列表都不是已证明的 HTTP 5xx failover。调整顺序只能改变默认依赖来源与暴露面；故障时要明确切换配置，并通过正常验证门重新运行。不要声称已测试 502 自动跨仓库回退，也不要靠替换 `jaxb-bom-ext`、关闭 checksum 或忽略解析错误解决仓库端故障。

### 3.2 已实施配置与兼容边界

`settings.gradle.kts` 的 `pluginManagement.repositories` 与 `dependencyResolutionManagement.repositories` 两处均采用：

- 默认只启用 `google()`、`mavenCentral()`。
- `-Porchestella.repositories.useAliyunMirror=true` 显式启用，顺序为 `aliyunGoogle`、`aliyunPublic`、`Google`、`MavenRepo`；这是区域优先选择，仍可能因镜像 5xx 失败。
- 属性值使用严格 boolean 解析；缺省/`false` 是官方源，拼写错误直接停止配置。
- `aliyunGoogle` 通过普通 `content` 限制到 `androidx`、`com.android`、`com.google` 及子组，避免把 `com.sun.xml.bind` 等泛用坐标发送给 Google 镜像。`aliyunPublic` 保持 Maven public 范围；没有扩大到自定义任意 endpoint。
- 使用普通 content filter，而非 `exclusiveContent`。普通过滤只限制该仓库的候选，不把 group 独占绑定给它；`pluginManagement` 内 exclusive filtering 另有影响 `buildscript.repositories` 的约束，当前无须引入。[S2]
- 保留 `FAIL_ON_PROJECT_REPOS`、Wrapper checksum、现有依赖坐标/版本、SDK 与签名行为。

主代理已通过真实 Gradle `diagnoseRepositories` 证明默认两列表只有官方源、opt-in 两列表均四仓且顺序正确，非法 `typo` 值失败。其证据并非源码字符串断言，也未证明真实 502 网络回退；Google content 的 HTTP 请求隔离仍可作为后续小型双仓 fixture 验证。[E5]

仓库身份与缓存 metadata 有关联；从镜像换到官方后可能需要重新解析 metadata，即使 artifact 文件已在本机。Gradle 对已选定仓库具有一致性约束，不承诺任意混用另一仓库的 metadata/artifact。已有 `.toolchains`、Gradle cache、WSL 沙箱均属应保全资产；不要清缓存后重新下载整套依赖。本次官方解析只补合法缺口。[S6]

## 四、五个 slice 的实施、风险、回滚与验证

### Slice 1：补齐 Python 3，缺依赖在 JVM 任务前失败

**已实施文件**：`guest-assets/ci/install-builder-dependencies.sh`、`guest-assets/scripts/test-initramfs-init-fallback.sh`、`guest-assets/scripts/test-local.sh`，新增 `guest-assets/scripts/test-builder-python.sh`。

- 在既有 Ubuntu snapshot `20251017T000000Z` 的 apt 安装列表与 `dpkg-query` 记录中同时加入 `python3`；安装后检查入口并输出版本。不修改 snapshot、镜像 digest、sources 的签名验证与其他版本锁。
- Python 测试入口强制 `python3`；缺失返回 69 并给明确原因，不再退回可能不存在或代表 Python 2 的 `python`。[S12]
- `test-local.sh` 在任何 Gradle 启动前 preflight。新回归通过完全隔离 fake PATH 执行真实入口，并将 orchestration 的只读副本放入外部 fixture，替换 repository boundary / fake gradlew 捕获调用；没有运行 Gradle。

**真实 red→green**：基线在 Python-only PATH 调用了 fake `python`，exit 97，测试报告 `expected 69, got 97`。修复后六项通过：仅 python 不调用、两入口缺失、python3 收到精确脚本与带空格工作根、子任务 exit 23 透传、无工作根 exit 64 且零调用、缺 python3 时 JVM/Gradle 零调用。[E6]

**风险/兼容**：原本仅配置 `python` 的开发环境将明确失败；该套测试实际依赖 Python 3，应安装或暴露正确的 `python3`，不创建系统 alias。新增包只影响专用 CI builder，不授权在宿主装包。APT 安装是否成功仍需下一次受控 container CI 验证。

**回滚**：只撤回本 slice 的四个文件变更，保留证据与已有缓存；回滚将恢复旧故障，不能把测试跳过作为回滚方案。

验证命令（本代理已运行前 3 类静态/隔离命令；完整脚本由主代理在既有 builder 中运行）：

```bash
TICKET26_WORK_ROOT="$RESEARCH_EVIDENCE/python-fixtures" bash guest-assets/scripts/test-builder-python.sh
bash -n guest-assets/ci/install-builder-dependencies.sh guest-assets/scripts/test-local.sh guest-assets/scripts/test-builder-python.sh
sh -n guest-assets/scripts/test-initramfs-init-fallback.sh
# 仅在既有获准的 Ubuntu 24.04 builder 内执行，不在宿主运行 installer：
./guest-assets/ci/install-builder-dependencies.sh
command -v python3
python3 --version
dpkg-query --show python3
./guest-assets/scripts/test-local.sh
```

### Slice 2：官方源默认，区域镜像有显式开关

**交付与验证**：见第三节；源码已经完成，主代理完成真实 Gradle 配置验证。[E5]

**风险**：官方源在部分区域可能不可达；开发者可主动选择镜像。切换仓库时已有缓存未必满足新的 repository metadata；只补缺项，不重建全量缓存。开关 `true` 仍保留镜像端 HTTP 故障风险。

**回滚**：撤回 `settings.gradle.kts` 这个 slice；或已有用户通过显式 property 暂时恢复镜像优先。不得修改 dependency coordinates / checksum。不要用 `--refresh-dependencies` 当作无差别清缓存操作。

`diagnoseRepositories` 是主代理外部 probe 注入的任务，不是生产 task；`$REPOSITORIES_PROBE` 应由主代理提供该实际脚本路径。本代理未确认该 probe 文件名，不将其猜成仓库已有文件。等价验证需真实读取 Settings repository handlers，而非匹配源码文字：

```bash
./gradlew -I "$REPOSITORIES_PROBE" diagnoseRepositories --offline --no-daemon --console=plain
./gradlew -I "$REPOSITORIES_PROBE" diagnoseRepositories --offline --no-daemon --console=plain -Porchestella.repositories.useAliyunMirror=true
# 预期失败：
./gradlew -I "$REPOSITORIES_PROBE" diagnoseRepositories --offline --no-daemon --console=plain -Porchestella.repositories.useAliyunMirror=typo
./gradlew :runtime:contract:check :app:testDebugUnitTest --no-daemon --stacktrace --console=plain
```

如补充 HTTP 行为回归，使用合成双仓 fixture：前仓 404 时后仓有固定小型 module；前仓 502 时记录有限请求与整体失败；前仓找到 metadata 后 artifact 缺失时不得混用后仓；默认配置不得访问镜像。不要对真实生产远端制造故障。本轮未执行该网络 fixture，不能报告已测自动 failover。

### Slice 3：lint 模型异常先取 resolution，再验模型与 lint

**取证顺序**：

1. 固定实际 Wrapper 8.13、AGP 8.13.2、JDK 17、Kotlin 2.2.21、SDK / variant / QEMU 三个 payload property 是否提供、offline 状态与工作根。AGP 官方兼容表的 Gradle 最低/默认为 8.13，JDK 为 17，最高 API 36.1；当前不存在“版本组合本身显然不兼容”的证据。[S5]
2. 收集精确 task 的 `--full-stacktrace --info --console=plain --no-configuration-cache`，只保存到 approved evidence。不要 `--scan` 把构建信息传到外部；默认不启用可能带敏感值的 `--debug`。
3. 在 external init script 中对可解析的 `debugAndroidTestCompileClasspath`、`debugAndroidTestRuntimeClasspath`、`debugAndroidTest*` metadata、`lintChecks`、`lintPublish` 调用 `incoming.resolutionResult`，遍历 `UnresolvedDependencyResult`。记录坐标、异常类、cause 链；逐个安全读取 message，不先调用会再次抛 NPE 的完整异常格式化函数。然后检查 artifactView 的 artifact failures。先 metadata、再 artifact，避免把 diagnostic task 自身成功当成依赖齐全。
4. 只为缺失的合法依赖在线解析；不改其版本，也不直接往 cache 手工拷一个猜测 JAR。AGP 的 exact `8.13.2` 官方 sources JAR 中 `LintModelWriterTask` 直接生成 `LintModelModule`、variants 和 dependencies，`DeviceTestCreationConfig` 对应 Android-test lint model；所以 unit test 通过不证明 Android-test lint model 的配置闭包完整。[S7]
5. 补齐后分别重跑精确模型任务与 `lintDebug`，最后用相同 property / variant 离线重验。保留实际 lint XML/HTML 结果，若出现代码 findings 按 findings 修复；不设置 `abortOnError=false` 或 `checkTestSources=false` 来掩盖模型异常。Android 文档明确 lint 需显式运行，普通 build 不能替代。[S4]

**当前执行状态**：第一轮配置解析得到三项唯一缺失，官方源在线 metadata probe 已全零；模型 task、artifact view 全闭包、实际 lint 的最终结果待主代理。[E4]

```bash
./gradlew -I "$LINT_PROBE" diagnoseLintResolution --offline --no-daemon --console=plain
./gradlew -I "$LINT_PROBE" diagnoseLintResolution --no-daemon --console=plain
./gradlew :app:generateDebugAndroidTestLintModel --no-daemon --full-stacktrace --info --console=plain --no-configuration-cache
./gradlew :app:lintDebug --no-daemon --full-stacktrace --console=plain
./gradlew :app:lintDebug --offline --no-daemon --stacktrace --console=plain
```

`$LINT_PROBE` 为同批 `lint-resolution.init.gradle`。本代理未运行上述命令；前两种实际 probe 由主代理运行，证据见 E4。风险是仍有 artifact 缺口或真正代码 lint findings，故本报告不把 metadata 恢复写成 lint PASS。回滚只涉及 diagnostic init script / 后续精确修复，不清理 cache，不回退到忽略 lint。

### Slice 4：通道选择与 SemVer 排序分开修复

**基线故障**：`GitHubUpdateChecker.DEFAULT_RELEASES_API_URL` 固定 `/latest`；`isNewerVersion` 除空值判断之外，当前 `-dev` 一律更新，否则只要字符串不相等就更新。例：remote `0.3.0` 对 current `0.4.2` 会错误返回 true。现有测试缺降级、数字位数、预发布有序比较、build metadata 等负例。

**API 策略**：[S8][S9]

- Stable 通道保留 `/latest` 的语义：GitHub 文档明确排除 draft 与 prerelease；当前 404 表示没有可用 stable release，不能提示已获取某个新版，不能当成网络完全不可达或自动降级。
- Preview 通道使用 releases collection，过滤 `draft=false` 并依据明确 channel policy 决定允许 stable+prerelease，解析完整 JSON 对象后选有效版本的最大值。不能沿用只抓第一个 `tag_name` 的 regex 处理 JSON array，也不能依赖 `releases[0]` 或 `published_at` 等同 SemVer 最大值。
- List endpoint 支持 `per_page` 和 `page`；需要处理 Link `rel=next` 或明确有限页数/响应大小/总超时。触及上限时给“检查不完整”而非“你已是最新版”；不创建后台重试队列。
- `make_latest=true/false/legacy` 不能把 prerelease 变成 `/latest` 的合格对象。GitHub 文档同时存在 created_at 与 make_latest legacy 的说明，不据此承诺它永远等价于 SemVer 数值最大值；客户端需要自己的升级比较规则。
- 默认保持旧稳定通道语义；给开发预览的用户主动选择或明确的 build-channel 标记。特别兼容普通 versionName `0.4.2` 的历史预览用户，不能仅凭后缀推断，不自动将所有现存用户切预览。

**排序策略**：[S10]

- 去掉项目约定的单个 `v` tag 前缀，再严格解析 `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]`；无效/未知版本不武断判为升级。
- core 三段按数值比较：`0.10.0 > 0.9.9`，remote 旧于 current 返回 false。
- 同 core 的 stable 高于所有 prerelease；`preview.10 > preview.2`；numeric identifier 数值比较并低于 nonnumeric；其余按 ASCII；build metadata 不影响 precedence。
- 不能让任意 `current.contains("-dev")` 绕过远端版本较旧的事实。SemVer 的 `0.4.2-dev` 仍高于 core 0.4.1。
- 不新增运行时依赖作为默认动作；可使用小型纯函数及 existing JSON 工具，具体实现由负责 updater 的代理选择。

**兼容/风险/回滚**：保留关闭自动检查时零请求，显式手动 `force` 行为按当前可见点击语义单独测试；保留现有 `SettingsManager` 偏好与结果字段。使用 synthetic HTTP server 验证 disabled 0 请求、404 无 stable、超时/5xx 不排队、分页边界、draft 过滤、release URL 与 asset 对象不串读、无效 JSON/版本不报假升级。现有 API URL constructor 可继续作测试注入。回滚仅此 slice 的 checker/测试/通道 UI 或标记；不要重写旧 release 或 tag。

```bash
./gradlew :app:testDebugUnitTest --tests 'io.github.nyzeep.orchestella.GitHubUpdateCheckerTest' --no-daemon --stacktrace
# 只读 API 对照，不创建或编辑 release：
gh api 'repos/Nyzeep/Orchestella-Studio/releases?per_page=100' --jq '.[] | {tag_name,draft,prerelease}'
gh api repos/Nyzeep/Orchestella-Studio/releases/latest
```

**Android 版本号边界**：Android 用 `versionCode` 排序安装版本，`versionName` 主要展示；Google Play 的最高 code 为 2100000000，且不允许复用已用 code。[S11] 当前项目硬约束公式 `major*10000+minor*100+patch` 未给同一 core 的多个 preview/final 分配独立 code，也未说明 minor/patch ≥100 的碰撞处理。不要为支持通道私自换乘数、复用 402 或降 code。短期每个不同公开 APK 使用下一合法 PATCH/core（纯修复评估为 PATCH）；如要求同 core 多次 prerelease/final 且全部可单调升级，需主代理记录版本策略决策再实施。

### Slice 5：把 CI 与开发预览验证变成可归因的门

**现状**：`.github/workflows/ci.yml` 只运行 contract 与 unit tests，不运行 lint；SDK license 和 SDK component 安装包含 `|| true`，sdkmanager 不存在时也直接继续。`.github/workflows/guest-assets.yml` 使用固定 container digest 与 Ubuntu snapshot installer，后续 JVM/配置拒绝/构建/smoke/上传顺序已有正常失败门；不应加 `continue-on-error`。

**建议最小交付**：

1. SDK manager 查找、指定 `android-36` / `36.1.0` 安装和存在性检查明确失败。处理 `yes | sdkmanager --licenses` 时区分 `yes` 的 SIGPIPE 与 sdkmanager 真失败，避免简单移除 `|| true` 导致假故障；检查消费端退出码及最终组件实际存在。此项仅 CI runner，不能顺带修改本地 SDK/系统。
2. 先跑工具 preflight，再 contract/JVM；单独 lint gate 以便归因；发布前 Guest 必需门同样不可跳。Gradle/JVM dependency cache 是复用加速，不能当成必有环境依赖或安全验证的替代。设置现有合理 task/job timeout，不包整 job 无限重试。
3. 用真实验证 commit、task 结果、输出 APK 的版本元数据与 SHA-256 组成小型本地 manifest。tag、versionName、versionCode 和 release asset basename 应一致；manifest 不含签名凭据、机器目录、全量源码清单。下载/上传 artifact 遵守已有范围和用户动作；本研究不创建发布 workflow、外部 release 或上传动作。
4. 当前 v0.4.2 已是发布预览、两个公共 CI 仍红，报告必须保留此事实；不重新命名旧 run 为成功，也不据此自动撤回现有用户资产。后续修复需新的验证结果和合法版本，再由主代理按授权推进。

**验证/回滚**：在 CI runner 的 synthetic sdkmanager fixture 中覆盖：找不到命令、license 非零、安装失败、组件缺失时 gate 非零，成功路径后续只运行一次；先 contract/JVM 和 lint，再 Guest smoke，任一必需门失败均无发布候选。可用 `gh run view <id> --log-failed` 只读取证。回滚定点 workflow/fixture 变更并保留证据；不把 `|| true` 或跳过 gate 当成功。该 slice 本代理未实现、未测试。

## 五、验证结果、产出与未完成事项

### 已完成

- 读取基线源码、全部防御政策与相关约束；确认 HEAD/初始工作树状态。
- `gh` 只读确认两次 CI 的 head / failure 与当前 releases 元数据，保存最小错误片段；没有复制完整 CI 日志到报告。
- 获取 Gradle 8.13 官方文档及 tag 固定源码、Android 官方文档、AGP 8.13.2 官方 sources JAR、GitHub 官方 API 文档、SemVer 规范和 Python PEP 原文，保留 URL / UTC / SHA-256 manifest。
- 本代理执行 Python 隔离回归：真实 red 后 green 6 项通过；`bash -n`、`sh -n`、定点 `git diff --check` 通过。未运行真实 Guest 测试/VM。
- 主代理真实 Gradle：两种 repo configuration 正例与 typo 负例达到预期；lint metadata 官方在线解析全部 0 failure。[E4][E5]

### 本代理修改文件

- `settings.gradle.kts`。
- `guest-assets/ci/install-builder-dependencies.sh`。
- `guest-assets/scripts/test-initramfs-init-fallback.sh`。
- `guest-assets/scripts/test-local.sh`。
- `guest-assets/scripts/test-builder-python.sh`（必要的执行语义回归）。
- 本 canonical 研究报告。`docs/` 被仓库忽略但本地有效，未强制 add 或更改 ignore 规则。

没有提交；最终 branch/迁移、构建、版本与发布由主代理统一收口。本代理停止源码写入后只写本报告与指定外部证据，避免与主代理并发冲突。

### 待主代理 / 未运行

- 官方源实际 JAR/AAR artifact closure、精确 lint model、`lintDebug` 及同条件 offline lint。
- 真实 snapshot container 安装 python3 与完整 Guest smoke；隔离测试不能代替该门。
- 更新检查器的最终实现/测试、通道产品标记与历史 v0.4.2 的兼容行为。
- CI sdkmanager gate 与后续发布前 manifest。未运行实际 CI rerun，未新建外部 issue/PR。
- 不宣称已有 Google content filter HTTP 故障 fixture、5xx 自动跨仓库回退、真实无网络端到端闭包或完整 release 验收。

## 六、对已有决策的影响与边界

补 python3、显式仓库 opt-in、解析已选依赖与准确版本比较都是兼容性修复，不要求改变 Full Linux、用户数据、签名、运行时网络或 host/guest 暴露面。默认官方源和固定 opt-in URL 不引入用户代码上传路径。本研究与两项有限修复未触发架构变更。

仓库中的 issue 23、spec 和防御 policy 引用 `docs/adr/0015-release-channels.md`；本次实际查找该文件不存在。不能声称已读完整 ADR-0015 或自行重建。可核实的现有记录支持 GitHub 首发、更新可关闭、无遥测；新 preview 默认值、历史用户迁移与同 core 多 preview 的 code 分配如需改变产品/版本契约，主代理应按架构变更门作决策，不能让小修静默替代。

治理文档含旧路径与旧当前版本（如 v0.4.0），本报告按用户明确指定的新 approved roots 和实际 v0.4.2 基线研究，不修改治理文档或原有资产。原始证据归 research-ci，归属本轮 consolidation-20261015；保留至主代理验收/归档，清理由主代理按资产分类策略定点决定，本代理未删除任何已有资源；新增证据不使用 `.log` 扩展名。

## 七、可引用一手资料

以下 S 引用均为官方所有者资料；源码固定到实际版本，避免拿当前主线行为代替 Gradle 8.13。下载的原文与最小 API 输出在 E 目录，可离线复核。

- **S1** Gradle 8.13：[Declaring multiple repositories](https://docs.gradle.org/8.13/userguide/declaring_repositories_basics.html#sec:declaring-multiple-repositories)，仓库声明顺序与 metadata / artifact 同仓约束。
- **S2** Gradle 8.13：[Filtering repository content](https://docs.gradle.org/8.13/userguide/filtering_repository_content.html)，普通 content 与 exclusiveContent 的区别、查询范围与 pluginManagement 注意事项。
- **S3** Gradle `v8.13.0` 官方源码：[ErrorHandlingModuleComponentRepository](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ivyresolve/ErrorHandlingModuleComponentRepository.java)、[NetworkingIssueVerifier](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/repositories/transport/NetworkingIssueVerifier.java)、[ResolveExceptionAnalyzer](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/internal/resolve/ResolveExceptionAnalyzer.java)、[RepositoryChainComponentMetaDataResolver](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ivyresolve/RepositoryChainComponentMetaDataResolver.java)、[ConnectionFailureRepositoryDisabler](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ivyresolve/ConnectionFailureRepositoryDisabler.java)、[HttpClientHelper](https://github.com/gradle/gradle/blob/v8.13.0/platforms/software/resources-http/src/main/java/org/gradle/internal/resource/transport/http/HttpClientHelper.java)。分别对应有限重试、网络分类、5xx critical、停止后仓队列、BuildTree禁用和HTTP异常。
- **S4** Android Developers：[Improve your code with lint checks](https://developer.android.com/studio/write/lint?hl=en)，显式 Gradle lint task、报告与 abortOnError。
- **S5** Android Developers：[Android Gradle plugin 8.13 release notes](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes?hl=en)，AGP8.13.2 与 Gradle/JDK/API 兼容表。不据 R8 支持 Kotlin2.3 这一条推断本项目任意 Kotlin 组合都已测试。
- **S6** Gradle 8.13：[Dependency caching](https://docs.gradle.org/8.13/userguide/dependency_caching.html)，`--offline` 缺缓存即失败、仓库 metadata 身份与 artifact 一致性。
- **S7** Google Maven 官方 [AGP 8.13.2 sources JAR](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2-sources.jar)，`com/android/build/gradle/internal/lint/LintModelWriterTask.kt` 与 `LintDependenciesModelBuilder.kt`。本研究保留原 JAR、按路径提取的原文和 SHA-256；只用来解释任务/依赖模型职责，不以源码注释代替真实故障证据。
- **S8** GitHub Docs：[Get the latest release / List releases / Create release](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)，`draft`、`prerelease`、pagination 参数、`make_latest` 与 latest 范围。
- **S9** GitHub Docs：[Using pagination in the REST API](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)，Link `rel=next` 的获取方式。
- **S10** SemVer：[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html)，尤其 §2、§9–11 的合法格式、prerelease 与 build metadata 排序规则。
- **S11** Android Developers：[Version your app](https://developer.android.com/studio/publish/versioning?hl=en)，versionCode / versionName 和递增、Play code 上限/复用限制。
- **S12** Python 官方 [PEP 394](https://peps.python.org/pep-0394/)，Unix-like `python` 可能不存在或指向不同主版本，Python3需求应显式命名。

## 八、证据索引

`RESEARCH_EVIDENCE` = `D:\Development project(开发项目)\Orchestella开发目录\Orchestella\artifacts\consolidation-20261015\research-ci`。

- **E1** `github-run-38684223942.json` 与 `github-run-38684223942-failure-excerpt.log.txt`：run 元数据和原始故障片段。
- **E2** `github-run-38684223879.json` 与 `github-run-38684223879-failure-excerpt.log.txt`：JVM 成功后 Python exit127 的原始片段。
- **E3** `github-release-list.json`、`github-release-latest.json`：只读 API 输出与退出码，证明当前全预发布 / latest404。
- **E4** 主代理同批 `evidence/gradle-lint-resolution.log.txt`、`evidence/gradle-lint-official-resolution.log.txt`；probe 为同批 `lint-resolution.init.gradle`。本代理读取核对过原文，未改动。
- **E5** 主代理同批 `evidence/gradle-repositories-default.log.txt`、`gradle-repositories-opt-in.log.txt`、`gradle-repositories-invalid.log.txt`，真实 repository handlers 的结果。
- **E6** `python-preflight-red.log.txt`、`python-preflight-green.log.txt`、`python-syntax.log.txt`，以及 `python-fixtures/` 的隔离可执行 fixture / 命令捕获。日志含批准工作根用于本地复现，不向外发送。
- **E7** `primary-source-manifest.json`：下载 URL、UTC、文件大小与 SHA-256；`primary-sources/` 为官方原文与 exact AGP sources；`preservation/` 孪生保全，不使用 `.log` 文件名。

报告人：ZCode / GPT-6.1 ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量，不估报
