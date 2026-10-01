# Review 报告：Ticket 31

- **审查对象**：`ticket/31-bridge-notify-open-url`，比对基线 `4c06414`（`main`），审查命令 `git diff 4c06414...HEAD`
- **审查 HEAD**：`248e4ea feat(bridge): implement notify and open-url bridge commands (Ticket 31)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行双轴独立并行审计（Dual-Axis Independent Review）；在本地 `DroidLayer_dev_t31` 工作区实测运行 `./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:network:check --rerun-tasks` 与 `./gradlew :app:lintDebug` 验证全量构建、契约测试、静态分析与编译器门控。

---

## 阶段复审总览（Review Summary）

针对 Ticket 31（桥接命令补全——通知与打开链接）的实现交付，独立评审专员基于双轴（Standards + Spec）审计标准，对比对基线 `4c06414` 引入的全部改动（18 个变更文件，+1480 / -64 行）执行了物理隔离的双轴深度复核。

经审计：
1. **Standards 轴**：0 项 Blocking Hard Violations，4 项 Non-blocking Judgement Calls（Fowler 代码异味与纵深防卫建议）。策略中立性完备（`runtime:contract` 纯 Java 17 标准库，零 Android Framework 依赖）；引入 `BridgeControl.Seams` 容器彻底消除了 javac 的 `[overloads]` 歧义警告；安全边界（ADR-0010）落实严密（Scheme 白名单严格限制 `http/https`，物理拦截沙箱穿透，2048 字符硬上限）；Class A 基石资产无损保全；`-Xlint:all -Werror` 保持零告警，Android Lint 零错误。
2. **Spec 轴**：0 项 Blocking Defect。工单定义的 AC-1（通知契约与分发）、AC-2（URL 打开契约与安全白名单）、AC-3（协议安全与鉴权拒斥）、AC-4（ADR 规范与暴露面文档归档）全部 100% 闭环落地。改动严格受控在 Ticket 31 owned 模块（`contract`, `engine-qemu`, `app` 桥接与适配组件），未发生越权改动。
3. **本地实测**：全量契约测试与底层模块测试 25/25 actionable tasks 重新执行通过（100% PASS）；Android Lint 静态代码分析 42 actionable tasks 0 errors 通过。

综合评定：**APPROVE**。

---

## Standards

**PASS — 0 Blocking Violations, 4 Non-blocking Judgement Calls / Baseline Smells**

### 1. 策略中立与架构解耦（ADR-0009）
- **[PASS] 契约层零 Android 依赖**：
  - `runtime/contract/src/main/java/.../BridgeControl.java` 与 `BridgeClient.java` 纯 Java 17 实现，无任何 `android.*` 导包；
  - 核心接口 `Notification` 与 `UrlOpener` 采用策略中立的 `@FunctionalInterface` 接口形式暴露，将宿主环境的系统调用物理剥离至传输与适配层之外。
- **[PASS] 方法重载与 javac 歧义治理**：
  - 针对 Java 编译器在多函数式接口参数下的重载模糊问题（`[overloads]` 警告），引入了专属分发方法（`dispatchNotification`、`dispatchOpenUrl`）以及聚合容器 `BridgeControl.Seams`，既保持了向后兼容，又彻底消除了 `-Werror` 告警风险。

### 2. 安全防卫边界与沙箱穿透防护（ADR-0010）
- **[PASS] URL Scheme 严格白名单过滤**：
  - `BridgeControl.parseOpenUrl` 强制正则校验 `^[hH][tT][tT][pP][sS]?://.+`，并通过 `URI.create(url)` 严格断言协议必须为 `http` 或 `https` 且 Host 非空；
  - 物理杜绝了 `file:///etc/passwd`、`content://...`、`javascript:...`、`intent:...`、`data:...` 等沙箱逃逸与组件劫持攻击，恶意 payload 确定性拒斥为 `DROIDLAYER/ERROR malformed-command\n`，绝不透传至宿主执行。
- **[PASS] 超长 Payload 与缓冲区边界防卫**：
  - 命令请求行与 URL/通知文本长度设置硬性 2048 字符上限（`MAX_PAYLOAD_CHARS` / `MAX_URL_LENGTH`），防范内存溢出与拒绝服务攻击；
  - `BridgeConnector.readLine` 施加 2048 字节超长行丢弃机制，保障套接字长连接流控安全。
- **[PASS] Android 通知与 Intent 隔离防护**：
  - 通知统一收敛至独立渠道 `droidlayer_guest_notifications`（`IMPORTANCE_DEFAULT`），携带显式指向宿主 `MainActivity` 的 `PendingIntent`，开启 `setAutoCancel(true)`，支持点击与用户 Dismiss；
  - URL 打开通过 `Intent.ACTION_VIEW` 配合 `FLAG_ACTIVITY_NEW_TASK` 呼起默认浏览器，对 `ActivityNotFoundException` 与 `SecurityException` 做好捕获兜底，防止宿主应用意外崩溃。

### 3. 资产卫生与 Class A 遵从（Asset Hygiene & Policy）
- **[PASS] Class A 资产完整保全**：
  - 严格遵从 `ASSET_REUSE_AND_HYGIENE_POLICY.md`，内核二进制、Alpine 官方 rootfs 缓存、NDK 工具链、WSL 容器环境与设备测试证据保持 0 改动。
- **[PASS] 模块所有权边界（PARALLELIZATION_MATRIX.md）**：
  - 改动严格收敛在 Ticket 31 owned 模块（`contract`, `engine-qemu/QemuBridgeAdapter`, `app/` 桥接组件与适配器）；
  - 严禁模块（`runtime/engine-avf/` 等）保持零接触。

### 4. 编译器门控与构建工程质量
- **[PASS] 严格编译门控**：
  - 模块构建脚本均配置 `-Xlint:all` 与 `-Werror`，在 Java 17 严格编译检查下保持 **0 警告、0 错误**；
  - Android Lint 静态分析 42 actionable tasks 全部通过，0 errors。

### 5. Fowler 异味基线审查与非阻断建议（Judgement Calls & Observations）
1. **Duplicated Code（建议后续提炼常量）**：
   - 位置：`BridgeControl.SAFE_URL_PATTERN`（`runtime/contract`）与 `AndroidUrlOpenerAdapter.SAFE_URL_PATTERN`（`app`）；
   - 现象：两处分别定义了相同的正则匹配 `Pattern.compile("^[hH][tT][tT][pP][sS]?://.+")` 与 2048 长度阈值；
   - 评定：非阻断。契约层与 Android 适配层各自持有该白名单构成了良好的纵深防御（Defense-in-Depth），但未来建议在公共契约或工具类中统一提取常量复用。
2. **Feature Envy（防卫式重复校验）**：
   - 位置：`BridgeControl.parseOpenUrl` 与 `AndroidUrlOpenerAdapter.openUrl`；
   - 现象：`BridgeControl` 在契约层已经完成了完整的 URL 结构与 Scheme 合法性解析，而在 `AndroidUrlOpenerAdapter` 中再次执行了 `isSafeUrl` 与 `URI.create` 解析；
   - 评定：非阻断。属于防卫式编程，保证了当适配器被外部逻辑绕过契约直接调用时依然安全。
3. **Primitive Obsession（通知参数封装建议）**：
   - 位置：`BridgeControl.Notification.notify(String title, String message)`；
   - 现象：通知参数采用标题与内容的双 `String` 原语入参；
   - 评定：非阻断。当前设计完美匹配 AC-1 诉求，后续若扩展优先级、自定义图标或分组标签时，建议抽取不可变值对象（`NotificationPayload`）。
4. **Data Clumps（参数聚合演进）**：
   - 位置：`BridgeControl.java:120-127` 及 `QemuBridgeAdapter.java:38-52`；
   - 现象：`terminal, power, notification, urlOpener` 四个参数在构造方法和 dispatch 方法中频繁成组传递；
   - 评定：非阻断。Worker 已经引入了 `BridgeControl.Seams` 作为聚合容器，这是非常优秀的重构实践；后续在 `QemuBridgeAdapter` 中也可直接注入 `BridgeControl.Seams`。

---

## Spec

**PASS — 4 项工单验收标准（AC-1 ~ AC-4）全部达成闭环**

### 1. 工单验收标准（Acceptance Criteria）逐条核验

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **通知契约与分发**：<br>1. Guest 内触发 `notify`<br>2. Android 通知栏可见且点击行为合理<br>3. 规避参数空格歧义 | **✅ PASS** | 1. `BridgeControl.Notification` 接口定义完成，支持 `DROIDLAYER/NOTIFY <title> <message>`；<br>2. 参数支持标准 URL 编码（如 `Build%20Done`）与 `base64:<data>` 编码，彻底消除空格歧义；<br>3. `AndroidNotificationAdapter` 创建 `droidlayer_guest_notifications` 渠道，绑定 `PendingIntent` 指向 `MainActivity`，开启 `setAutoCancel(true)`；<br>4. 单元与契约测试验证：`executesNotifyThroughTheNotificationSeam`、`notifiesThroughPosterSeam`、`servesControlLoopDispatchingNotifyAndOpenUrlCommands` 均 100% 绿灯。 |
| **AC-2** | **URL 打开契约与安全白名单**：<br>1. Guest 内触发 `open-url`<br>2. Android 按默认应用打开<br>3. 严格限制仅放行 HTTP/HTTPS 白名单 | **✅ PASS** | 1. `BridgeControl.UrlOpener` 接口定义完成，支持 `DROIDLAYER/OPEN-URL <safe-url>`；<br>2. 强制双重正则白名单 `^[hH][tT][tT][pP][sS]?://.+` 与 `URI.create` 校验，拦截 `file://`、`content://`、`javascript:`、`intent:`、`ftp:`、`data:` 等恶意 Scheme；<br>3. `AndroidUrlOpenerAdapter` 启动 `Intent.ACTION_VIEW` 配合 `FLAG_ACTIVITY_NEW_TASK`，捕获 `ActivityNotFoundException` 与 `SecurityException` 兜底；<br>4. 单元与契约测试验证：`executesOpenUrlThroughTheUrlOpenerSeam`、`rejectsDisallowedSchemesInOpenUrl` 均 100% 绿灯。 |
| **AC-3** | **协议安全与鉴权拒斥**：<br>1. 继承 Bridge v1 握手鉴权模型<br>2. Token 错误确定性拒绝<br>3. 未协商版本与畸变命令确定性拒绝 | **✅ PASS** | 1. 继承 per-VM 64 位十六进制 token 握手机制，未鉴权请求直接返回 `DROIDLAYER/ERROR unauthorized`；<br>2. 单行与参数硬上限 2048 字符，超长直接判定 `DROIDLAYER/ERROR malformed-command`；<br>3. 适配器未装配或不可用时返回 `DROIDLAYER/ERROR notify-unavailable` 或 `DROIDLAYER/ERROR open-url-unavailable`；<br>4. 契约测试验证 6 项异常拒绝用例全部通过。 |
| **AC-4** | **ADR 规范与暴露面文档归档**：<br>1. 修订 ADR-0009 协议扩展流程<br>2. 修订 ADR-0010 暴露面白名单流程 | **✅ PASS** | 1. `docs/adr/0009-guest-bridge-protocol.md` 增补 Amendments 详细说明 `notify` 与 `open-url` 的线协议、编解码与响应码规范；<br>2. `docs/adr/0010-security-boundary.md` 增补 Amendment 6（桥接交互命令白名单与沙箱穿透防护），明确 HTTP/HTTPS 白名单、沙箱隔离与 2048 字符上限。 |

---

## 本地构建与实测日志摘要

在本地隔离工作树 `DroidLayer_dev_t31` 执行：

```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:network:check --rerun-tasks
```
- **构建状态**：`BUILD SUCCESSFUL in 14s`，25 actionable tasks 全部重新执行通过（100% PASS）；
- **核心用例执行**：
  - `:runtime:contract:bridgeClientContractTest` -> PASS
  - `:runtime:contract:bridgeControlContractTest` -> PASS
  - `:runtime:contract:networkContractTest` -> NETWORK_CONTRACT_TEST_OK
  - `:runtime:contract:portForwardingContractTest` -> PORT_FORWARDING_CONTRACT_TEST_OK
  - `:runtime:contract:sharedDirectoryContractTest` -> SHARED_DIRECTORY_CONTRACT_TEST_OK
  - `:runtime:engine-qemu:qemuBridgeAdapterTest` -> PASS
  - `:runtime:engine-qemu:qemuLaunchSpecTest` -> DEFAULT_LAUNCH_SPEC_OK
  - `:runtime:network:portForwardingManagerContractTest` -> PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK

静态分析与 Android 代码质量审计：
```powershell
./gradlew :app:lintDebug
```
- **构建状态**：`BUILD SUCCESSFUL in 13s`，42 actionable tasks 0 errors 全部通过。

---

## 评审裁决结论

**【 APPROVE 】**

Ticket 31 的代码实现高质量完成了工单规格所要求的桥接通知与 URL 打开命令扩展。架构分层严格遵循 ADR-0009 策略中立规范，安全边界坚实符合 ADR-0010 白名单与沙箱隔离要求，测试用例健全覆盖，构建与静态分析全绿。具备合入主分支（`main`）的准入条件。
