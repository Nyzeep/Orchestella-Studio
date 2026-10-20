# 05: 平台访问与 hidden API 接缝

Type: implementation
Status: ready-for-agent
Priority: P0
Owner: platform access owner
Blocked by: None
Parent: `.scratch/orchestella-hw-virt/map.md`

## 用户价值与来源

`AvfHostFactsProbe.readModeFacts()` 在 Activity 里直接 `Class.forName` + `getSystemService` + `getCapabilities`，没有 hidden API 豁免；在受限设备上返回 `UNKNOWN`，UI 无法区分“仅 protected”和“未知”。引擎 `ReflectiveAvfVirtualMachineApi` 又有另一套 class shape、bit 常量、失败语义。探针和引擎必须看到同一个平台事实，豁免只能装一次，且要早于任何 AVF 类加载。

来源：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md` §2.3、§4；`AvfHostFactsProbe.java:108-136`；`ReflectiveAvfVirtualMachineApi.java:388-441`；TerminalAppPlus `Application.kt`（targetSdk 37 + hiddenapibypass 6.1）。

## What to build / 范围

1. 新增 `AvfPlatformFacts`（建议放 `runtime:capability` 或 `runtime:contract`，避免依赖环）与 `AccessState`：`AVAILABLE`、`HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`REFLECTION_UNAVAILABLE`、`MODE_UNSUPPORTED`、`MODE_UNKNOWN`。
2. 新增一个反射适配器（建议 `runtime:engine-avf` 的 `ReflectiveAvfPlatformAccess`）拥有：
   - `android.system.virtualmachine.*` 的类/方法 shape；
   - `CAPABILITY_PROTECTED_VM=1`/`CAPABILITY_NON_PROTECTED_VM=2`；
   - `getCapabilities()`、权限读取、`checkSelfPermission`；
   - 失败映射（SecurityException/LinkageError/InvocationTargetException → typed state）。
3. App 侧 `AvfHostFactsProbe` 只负责 Android-visible facts（SDK、ABI、Build、feature），平台事实通过适配器获取；删除重复的 `Class.forName`/bit 常量/失败分支。
4. Hidden API 豁免：
   - 在 `Application.attachBaseContext`（新建 `OrchestellaApplication`，manifest `android:name`）或一个严格早于 AVF 类加载的 holder 中调用 `HiddenApiBypass.addHiddenApiExemptions("Landroid/system/", "Landroid/os/", "Landroid/view/", "Landroid/content/", "Landroid/permission/", "Llibcore/io/")`；
   - 只执行一次；记录“豁免已装/失败”，不记录完整白名单以外的敏感信息；
   - pin `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`（Apache-2.0；核对许可并加入 third-party notices）；若目标构建不能引入，必须在报告写明并保留显式失败。
5. 权限：manifest 已声明 `MANAGE_VIRTUAL_MACHINE` / `USE_CUSTOM_VIRTUAL_MACHINE`（`app/src/main/AndroidManifest.xml:9-10`）；本票只做读取/引导/状态，不自动 grant、不启动 UserService、不做 shell-owner。
6. `AvfCapabilityEvaluator` 消费新的 facts/access state：hidden API blocked ≠ permission denied ≠ mode unsupported；UI 给可行动原因，不显示笼统“未知”。
7. shell-owner 只保留接口/文档，不实现；`AvfPlatformAccess` 允许未来第二个 adapter。

## 行为验收（零设备）

- [ ] 豁免只装一次，且在任何 AVF `Class.forName`/config builder 之前；测试用 fake initializer 断言顺序。
- [ ] 探针与引擎使用同一套 class shape/bit 常量/failure mapping；删除重复实现。
- [ ] 策略矩阵测试：已授权 + 可读 → READY/具体 mode；hidden API blocked → `HIDDEN_API_BLOCKED`；权限缺失 → `NEEDS_AUTHORIZATION`；仅 protected → `MODE_UNSUPPORTED`；类缺失 → `REFLECTION_UNAVAILABLE`；读取异常 → `MODE_UNKNOWN`。
- [ ] 受限设备上不再把“仅 protected”误显示为“模式未知”；UI 有具体原因。
- [ ] 探针不启动 VM/UserService、不自动 grant、不轮询。
- [ ] 依赖/许可/notice 完整；没有新增全局 hidden_api_policy 修改、SELinux 修改或 targetSdk 降低。
- [ ] 旧 `AvfHostFacts` 调用方兼容；旧仅 MANAGE 输入不能绕过 CUSTOM。

## 测试 seam 与 red 命令

- 纯 JVM/fake classloader：`AvfPlatformAccessTest`、`AvfHostFactsProbeTest`、`AvfCapabilityEvaluatorTest`、`AvfAuthorizationWizardModelTest`。
- 用现有 `FakeVirtualizationContext`/`VirtualMachineManager` fixtures 模拟 available/blocked/denied；不要 mock Android framework 的 `Class.forName` 全局。
- 红命令：
  ```bash
  ./gradlew :runtime:engine-avf:check :runtime:capability:check \
    :app:testDebugUnitTest --tests '*AvfPlatformAccess*' \
    --tests '*AvfHostFactsProbe*' --tests '*AvfCapabilityEvaluator*' --no-daemon --stacktrace
  ```
- 先 red：无豁免时探针 UNKNOWN；engine 与 probe 的 bit/failure 不一致；权限缺失被显示成 mode unknown。

## 失败、数据与兼容

- 失败只记录 typed state/reason，不记录设备序列号/完整 fingerprint/用户数据。
- 豁免失败不是权限失败；UI 文案必须区分。
- 不自动重试 grant；不把 Shizuku 当数据面；不启动 UserService。
- 不引入 hidden API 以外的特权面；不修改系统策略。

## 迁移、回滚与依赖

- 无实例数据迁移；新增 Application 初始化要确认不改变现有启动顺序/崩溃行为。
- 回滚：移除 Application/依赖/适配器，恢复旧 probe；保留 typed facts 作为测试工具。
- 依赖：HiddenApiBypass 6.1 固定版本；若供应链/许可不允许，H4 失败并报告，不静默改用其他库。

## 证据与完成门

- 报告：`docs/reports/tickets/avf-zero-device-05/implementation-report.zh-CN.md`。
- 完成 = 策略矩阵全绿 + 顺序测试 + 探针/引擎单一事实 + 全量 check + 报告明确“真机 grant/hidden API 未验”。

## 不做

- 不做真机 `pm grant`/hidden API 行为验收（设备 effort）。
- 不实现 shell-owner/UserService/ACR-01；不做 protected；不改 targetSdk；不改全局 hidden_api_policy。
