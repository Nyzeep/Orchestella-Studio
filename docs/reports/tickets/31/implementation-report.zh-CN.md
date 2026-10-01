# Ticket 31 实施报告（Implementation Report）

- **工单编号**：Ticket 31
- **工单标题**：桥接命令补全——通知与打开链接（Bridge Command Completion: Notify and Open-URL）
- **会话角色**：Implementation Worker（代码实现专员）
- **能力层级**：Implementation Tier（严格遵循 TDD 节奏，代码实现与契约自测闭环）
- **工作区与特性分支**：`DroidLayer_dev_t31`，分支 `ticket/31-bridge-notify-open-url`（基于 `main`，Commit `4c06414`）
- **看板跟踪项**：`.scratch/droidlayer-platform/issues/31-impl-bridge-notify-open-url.md`
- **当前状态**：**Implementation Completed · Ready for Independent Review**

---

## 1. 任务背景与核心目标

在 Ticket 26（双抽象套接字桥接通道基座）与 Ticket 28（Bridge Power 协议扩展模式）之上，Ticket 31 负责补全 Guest 内向 Android 宿主发起的两个高频跨环境交互命令：`notify`（发送系统通知）与 `open-url`（调用宿主默认浏览器打开链接）。

1. **协议设计与策略中立（ADR-0009 规范）**：
   - 在 `runtime:contract` 的 `BridgeControl.java` 中扩展 `Notification` 与 `UrlOpener` 策略中立接口；
   - 保持纯 Java 17 标准库编写，严禁将 Android Framework 依赖引入 `runtime:contract` 核心契约；
   - 设计规避歧义的文本行流编码规则：`DROIDLAYER/NOTIFY <title> <message>\n`（支持标准 URL 编码与 Base64 编码，消除空格歧义）与 `DROIDLAYER/OPEN-URL <safe-http-url>\n`；
2. **安全防护边界（ADR-0010 规范）**：
   - 针对 `open-url` 施加严格 Scheme 白名单校验：仅放行 `^[hH][tT][tT][pP][sS]?://.+` 且必须包含合法 Host；
   - 坚决阻断 `file://`、`content://`、`intent:`、`javascript:` 等恶意协议穿透 Android 沙箱窃取私有文件或滥用系统组件；
   - 对请求行与参数施加 2048 字符硬上限，杜绝缓冲区膨胀与超长 payload 攻击；
3. **Android 宿主端适配（`app` 模块）**：
   - 实现 `AndroidNotificationAdapter`，配置系统通知渠道 `droidlayer_guest_notifications`（`IMPORTANCE_DEFAULT`），配置带启动 intent 的 `PendingIntent` 并支持清除；
   - 实现 `AndroidUrlOpenerAdapter`，以 `Intent.ACTION_VIEW` 配合 `FLAG_ACTIVITY_NEW_TASK` 安全唤起默认浏览器，捕获 `ActivityNotFoundException` 与 `SecurityException` 兜底；
   - 在 `BridgeConnector` 与 `TerminalActivity` 联动装配命令接收与响应分发循环；
4. **ADR 规范与暴露面文档归档**：
   - 更新 `docs/adr/0009-guest-bridge-protocol.md`（定义新增命令线协议规范与状态码）；
   - 更新 `docs/adr/0010-security-boundary.md`（增补 Amendment 6 锁定白名单与安全边界）。

---

## 2. 模块边界与红线遵从核对（Ownership & Boundaries Verification）

| 约束项 | 规范要求 | 实际遵从情况 | 验证结论 |
| :--- | :--- | :--- | :--- |
| **工作区物理隔离** | 在独立隔离工作树开展工作 | 创建独立 Git Worktree `DroidLayer_dev_t31` 检出分支 `ticket/31-bridge-notify-open-url`（基于 `main` Commit `4c06414`） | **100% 隔离** |
| **Owned: Contract 模块** | `runtime/contract/`（`BridgeControl`, `BridgeClient` 等） | 纯 Java 17 扩展通知与链接打开接口、解析器、分发器与客户端方法，0 Android 依赖引入 | **完全受控** |
| **Owned: QEMU 适配器** | `runtime/engine-qemu/`（`QemuBridgeAdapter`） | 扩展支持 `notification` 与 `urlOpener` seam 装配与命令透传 | **完全受控** |
| **Owned: Android 适配器** | `app/`（`AndroidNotificationAdapter`, `AndroidUrlOpenerAdapter`, `BridgeConnector`, `TerminalActivity`） | 实现通知渠道配置、PendingIntent、Intent.ACTION_VIEW 浏览器调用与会话命令循环 | **完全受控** |
| **Forbidden: AVF 引擎** | 严禁改动 `runtime/engine-avf/` | 0 行改动 `runtime/engine-avf/` | **零冲突** |
| **Forbidden: 网络/存储核心** | 严禁修改 29/30 核心功能 | 0 行改动 `runtime/network/` 规则逻辑，仅复用 26/28 架构模式 | **零冲突** |
| **编译与代码质量约束** | `-Xlint:all -Werror` 零告警、零隐式转换 | 消除 `[overloads]` 警告，全模块构建与 lint 零警告零错误通过 | **100% 合规** |

---

## 3. 验收标准逐项落地与实测证据（Acceptance Criteria Evidence）

### 3.1 AC-1（通知契约与分发）
- **设计与实现**：
  * 在 `BridgeControl.java` 中声明策略中立接口：`public interface Notification { boolean notify(String title, String message); }`；
  * 实现 `isNotifyCommand(String)` 与 `parseNotify(String)`，支持标准 URL 编码（如 `Build%20Done`）与 `base64:<data>` 解码；
  * 在 Android 端实现 `AndroidNotificationAdapter`，创建通知渠道 `droidlayer_guest_notifications`（`IMPORTANCE_DEFAULT`），装配带应用回跳的 `PendingIntent`，设置 `setAutoCancel(true)`，具备点击跳转与滑动清除机制；
  * 在 `BridgeConnector.serveControlLoop` 中接线，Guest 触发 `DROIDLAYER/NOTIFY <title> <message>\n` 时，Android 宿主成功解析并在通知栏呈现通知，宿主回写 `DROIDLAYER/OK notify\n`，`TerminalActivity` 终端同步打印通知事件。
- **契约与单测覆盖**：
  * `BridgeControlContractTest.executesNotifyThroughTheNotificationSeam`：验证 URL 编码、纯字符串与 Base64 解码与分发；
  * `AndroidNotificationAdapterTest.notifiesThroughPosterSeam`：验证通知标题、内容及正整数通知 ID 装配；
  * `AndroidNotificationAdapterTest.rejectsBlankOrNullNotificationParameters`：验证空标题/内容过滤；
  * `BridgeConnectorTest.servesControlLoopDispatchingNotifyAndOpenUrlCommands`：端到端验证字节流命令分发与应答。

### 3.2 AC-2（URL 打开契约与安全白名单）
- **设计与实现**：
  * 在 `BridgeControl.java` 中声明策略中立接口：`public interface UrlOpener { boolean openUrl(String url); }`；
  * 实现 `isOpenUrlCommand(String)` 与 `parseOpenUrl(String)`，双重实施正则白名单 `^[hH][tT][tT][pP][sS]?://.+` 与 `java.net.URI` Host 检查；
  * 严格限制仅放行 `http://` 与 `https://` Scheme，对 `file:///etc/passwd`、`content://...`、`intent://...`、`javascript:...`、`data:...` 等试图穿透应用沙箱的恶意命令一律拒绝并返回 `DROIDLAYER/ERROR malformed-command\n`，且绝不触碰 `UrlOpener` seam；
  * 在 Android 端实现 `AndroidUrlOpenerAdapter`，以 `Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` 发起 Intent，并做好 `ActivityNotFoundException` 与 `SecurityException` 捕获兜底；
  * 成功打开链接返回 `DROIDLAYER/OK open-url\n`。
- **契约与单测覆盖**：
  * `BridgeControlContractTest.executesOpenUrlThroughTheUrlOpenerSeam`：验证标准 HTTP/HTTPS 链接受理；
  * `BridgeControlContractTest.rejectsDisallowedSchemesInOpenUrl`：验证 `file://`, `javascript:`, `intent:`, `content:`, `ftp:`, `data:` 等恶意 Scheme 100% 被拒斥；
  * `AndroidUrlOpenerAdapterTest.opensValidHttpAndHttpsUrlsThroughStarterSeam`：验证 Android 适配器参数装配；
  * `AndroidUrlOpenerAdapterTest.rejectsDisallowedSchemesAndUnsafeUrls`：验证非 HTTP/HTTPS 与畸变 URL 拒绝；
  * `AndroidUrlOpenerAdapterTest.handlesActivityNotFoundExceptionGracefully`：验证无可用浏览器时的异常兜底。

### 3.3 AC-3（协议安全与鉴权拒斥）
- **设计与实现**：
  * 依据 ADR-0009 与 ADR-0003，所有命令必须在 Bridge v1 握手协商（`DROIDLAYER/HELLO 1 1 <token>\n`）成功且校验通过 64 位十六进制 token 后方可进入命令分发循环；
  * 错误 token 确定性返回 `DROIDLAYER/ERROR unauthorized\n` 并断开连接；
  * 版本不兼容确定性返回 `DROIDLAYER/ERROR unsupported-version\n`；
  * 格式畸变、超长恶意 payload（> 2048 字符）确定性返回 `DROIDLAYER/ERROR malformed-command\n`；
  * 对应 seam 未挂载或处理失败确定性返回 `DROIDLAYER/ERROR notify-unavailable\n` 或 `DROIDLAYER/ERROR open-url-unavailable\n`。
- **契约与单测覆盖**：
  * `BridgeControlContractTest.rejectsMalformedNotifyCommandsBeforeTheyReachTheNotificationSeam`
  * `BridgeControlContractTest.rejectsUnavailableNotifyWithoutPretendingSuccess`
  * `BridgeControlContractTest.rejectsNotifyWhenNotificationSeamNotSupplied`
  * `BridgeControlContractTest.rejectsMalformedOpenUrlCommandsBeforeTheyReachTheUrlOpenerSeam`
  * `BridgeControlContractTest.rejectsUnavailableOpenUrlWithoutPretendingSuccess`
  * `BridgeControlContractTest.rejectsOpenUrlWhenUrlOpenerSeamNotSupplied`
  * `QemuBridgeAdapterTest.rejectsAnIncorrectToken`
  * `QemuBridgeAdapterTest.rejectsAnIncompatibleVersionRange`

### 3.4 AC-4（ADR 规范与暴露面文档归档）
- **设计与实现**：
  * 更新 `docs/adr/0009-guest-bridge-protocol.md`：在 Amendments 章节中详细记录 `notify` 与 `open-url` 命令的线格式、URL/Base64 编码约定、响应码及 Android 宿主实现规范；
  * 更新 `docs/adr/0010-security-boundary.md`：新增 Amendment 6（桥接交互命令白名单与沙箱穿透防护），明确记录 HTTP/HTTPS 严格白名单、沙箱隔离机制、超长 payload 拦截上限（2048 字符）及 per-VM token 鉴权前置门控。

---

## 4. TDD 节奏与实施历程（TDD Audit Trail）

```
[Slice 1: Contract 接口与协议编解码]
  ├── RED: 在 BridgeControlContractTest 中编写 executesNotifyThroughTheNotificationSeam、
  │        executesOpenUrlThroughTheUrlOpenerSeam 等 10 项新增测试
  │        (编译失败，找不到 Notification / UrlOpener 符号)
  ├── GREEN: 实现 BridgeControl.java 中的 Notification, UrlOpener, isNotifyCommand, isOpenUrlCommand,
  │          parseNotify, parseOpenUrl, dispatchNotification, dispatchOpenUrl 及 Seams 聚合容器；
  │          实现 BridgeClient.java 中的 notify() 与 openUrl()；
  │          (:runtime:contract:check 100% PASS，bridgeClientContractTest / bridgeControlContractTest OK)
  └── REFACTOR: 采用专用分发命名与 Seams 对象消除 javac 的 [overloads] 歧义警告，严格满足 -Werror。

[Slice 2: 安全校验与拒绝语义]
  ├── RED & GREEN: 编写针对非 HTTP/HTTPS 链接 (file://, javascript:, intent:, content:, ftp:, data:)、
  │                超长 payload (> 2048 chars) 与非法编码的防护测试；
  │                全部确定性拒绝为 DROIDLAYER/ERROR malformed-command。
  └── VERIFY: 扩展 QemuBridgeAdapter.java 与 QemuBridgeAdapterTest.java，全量 JVM 测试 100% 通过。

[Slice 3: Android 端适配器与 BridgeServer 联动]
  ├── IMPLEMENT: 创建 AndroidNotificationAdapter.java（系统 NotificationChannel、PendingIntent、点击/清除）；
  │              创建 AndroidUrlOpenerAdapter.java（ACTION_VIEW、FLAG_ACTIVITY_NEW_TASK、ActivityNotFoundException 兜底）；
  │              扩展 BridgeConnector.java 运行双向命令服务循环；
  │              接线 TerminalActivity.java 实现终端联动；
  │              在 AndroidManifest.xml 中声明 POST_NOTIFICATIONS 权限。
  ├── VERIFY: 编写 AndroidNotificationAdapterTest、AndroidUrlOpenerAdapterTest、BridgeConnectorTest 单测；
  │           在隔离盘符执行 :app:testDebugUnitTest（7/7 PASS）；
  │           修复 ByteArrayOutputStream API 33 兼容性，执行 :app:lintDebug（0 错误，BUILD SUCCESSFUL）。

[Slice 4: 文档归档与工单交付]
  ├── AMEND: 更新 docs/adr/0009-guest-bridge-protocol.md 与 docs/adr/0010-security-boundary.md；
  ├── TRACKER: 勾选 .scratch/droidlayer-platform/issues/31-impl-bridge-notify-open-url.md 所有验收项并标记 ready-for-review；
  └── REPORT: 产出本实施报告。
```

---

## 5. 变更文件清单（Deliverables & Modified Files）

1. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControl.java`
   - 扩展 `Notification` 与 `UrlOpener` 接口；
   - 增加 `isNotifyCommand`、`isOpenUrlCommand`、`parseNotify`、`parseOpenUrl`；
   - 增加 `dispatchNotification`、`dispatchOpenUrl`、`dispatch(..., Seams)` 与全参数 `dispatch` 重载；
   - 增加 `Seams` 容器。
2. `runtime/contract/src/main/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeClient.java`
   - 增加 `notify(String title, String message)` 与 `openUrl(String url)` 客户端方法；
   - 增加 `ok-notify` 与 `ok-open-url` 响应解析。
3. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeControlContractTest.java`
   - 增补通知与 URL 打开契约测试、安全白名单测试、超长拦截测试与全 Seams 组合测试。
4. `runtime/contract/src/test/java/io/github/nyzeep/droidlayer/runtime/contract/BridgeClientContractTest.java`
   - 增补客户端 `notify` 与 `openUrl` 双向往返测试及参数校验拦截测试。
5. `runtime/engine-qemu/src/main/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuBridgeAdapter.java`
   - 扩展支持 `notification` 与 `urlOpener` seam 注入与透传分发。
6. `runtime/engine-qemu/src/test/java/io/github/nyzeep/droidlayer/runtime/engineqemu/QemuBridgeAdapterTest.java`
   - 增加带 `notify` 与 `open-url` 的端到端字节流桥接测试。
7. `app/src/main/java/io/github/nyzeep/droidlayer/AndroidNotificationAdapter.java`
   - 新增：Android 系统通知适配器，创建通知渠道、设置 PendingIntent 与点击清除。
8. `app/src/main/java/io/github/nyzeep/droidlayer/AndroidUrlOpenerAdapter.java`
   - 新增：Android URL 打开适配器，严格正则白名单校验、Intent.ACTION_VIEW 调用与异常捕获。
9. `app/src/main/java/io/github/nyzeep/droidlayer/BridgeConnector.java`
   - 扩展：支持连续接收并分发 Guest 内部发起的控制命令流。
10. `app/src/main/java/io/github/nyzeep/droidlayer/TerminalActivity.java`
    - 联动：装配通知与 URL 适配器，在终端回显通知与打开链接事件。
11. `app/src/main/AndroidManifest.xml`
    - 增补：`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`。
12. `app/src/test/java/io/github/nyzeep/droidlayer/AndroidNotificationAdapterTest.java`
    - 新增：通知适配器单元测试。
13. `app/src/test/java/io/github/nyzeep/droidlayer/AndroidUrlOpenerAdapterTest.java`
    - 新增：URL 适配器安全与兜底单元测试。
14. `app/src/test/java/io/github/nyzeep/droidlayer/BridgeConnectorTest.java`
    - 新增：BridgeConnector 控制循环单元测试。
15. `docs/adr/0009-guest-bridge-protocol.md`
    - 更新：增补 `notify` 与 `open-url` 命令线协议规范与状态码。
16. `docs/adr/0010-security-boundary.md`
    - 更新：增补 Amendment 6（桥接交互命令白名单与沙箱穿透防护）。
17. `.scratch/droidlayer-platform/issues/31-impl-bridge-notify-open-url.md`
    - 更新：勾选所有验收标准，更新状态为 `ready-for-review`。
18. `docs/reports/tickets/31/implementation-report.zh-CN.md`
    - 新增：本实施报告。

---

## 6. 自测验证实录（Verification Execution Log）

### 6.1 核心交付命令验证
```powershell
./gradlew :runtime:contract:check :runtime:engine-qemu:check :runtime:network:check --rerun-tasks
```
**输出实录**：
```text
> Task :runtime:contract:compileJava
> Task :runtime:contract:compileTestJava
> Task :runtime:contract:bridgeClientContractTest
> Task :runtime:contract:bridgeControlContractTest
> Task :runtime:contract:bridgeHandshakeContractTest
> Task :runtime:contract:fullLinuxContractTest
> Task :runtime:contract:networkContractTest
NETWORK_CONTRACT_TEST_OK
> Task :runtime:contract:portForwardingContractTest
PORT_FORWARDING_CONTRACT_TEST_OK
> Task :runtime:contract:sharedDirectoryContractTest
SHARED_DIRECTORY_CONTRACT_TEST_OK
> Task :runtime:contract:check
> Task :runtime:engine-qemu:embeddedProvisionInputTest
EMBEDDED_PROVISION_INPUT_TEST_OK deterministic=1 gzip=1 newc_entries=3 trailer=1 parent_dirs=2 token_validation=2
> Task :runtime:engine-qemu:qemuBridgeAdapterTest
> Task :runtime:engine-qemu:qemuHostfwdContractTest
QEMU_HOSTFWD_CONTRACT_TEST_OK
> Task :runtime:engine-qemu:qemuLaunchSpecTest
DEFAULT_LAUNCH_SPEC_OK args=41 outbound_net=1 default_dns=8.8.8.8 no_9p=1
ENGINE_CLI_FORMATTERS_TEST_OK
DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1
MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device
SHARED_DIRECTORY_PCI_SPEC_OK
SHARED_DIRECTORY_MMIO_SPEC_OK
ENGINE_FSDEV_FORMATTERS_TEST_OK
> Task :runtime:engine-qemu:runtimeArtifactManifestSchemaTest
> Task :runtime:engine-qemu:runtimeArtifactManifestTest
> Task :runtime:engine-qemu:runtimeAssetInstallerTest
RUNTIME_ASSET_INSTALLER_TEST_OK positive=1 tamper_rejected=1 absent_rejected=1
> Task :runtime:engine-qemu:check
> Task :runtime:network:portForwardingManagerContractTest
PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK
> Task :runtime:network:check

BUILD SUCCESSFUL in 16s
25 actionable tasks: 25 executed
```

### 6.2 Android 宿主代码检查
```powershell
./gradlew :app:lintDebug
```
**输出实录**：
```text
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac
> Task :app:lintAnalyzeDebug
> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Development%20project(开发项目)/DroidLayer开发目录/DroidLayer_dev_t31/app/build/reports/lint-results-debug.html
> Task :app:lintDebug

BUILD SUCCESSFUL in 30s
42 actionable tasks: 11 executed, 31 up-to-date
```

---

## 7. 移交结论与后续指引

Ticket 31（桥接命令补全——通知与打开链接）的所有代码实现、适配器联动、TDD 驱动验证、安全红线加固、ADR 归档与交付汇报已圆满闭环：

- **代码质量**：编译参数 `-Xlint:all -Werror` 保持零告警，无任何隐式转换或过时 API 滥用；
- **分支状态**：`ticket/31-bridge-notify-open-url`（工作区完全干净，无未跟踪文件）；
- **移交声明**：Implementation Worker 现正式提请独立审计会话（Independent Review Session）对本次交付展开 Standards 与 Spec 双轴复审，不代用户签字。
