# 28: Protected Microdroid 最小 payload 启动/停止/存储/vsock 验证

> **2026-10-21 拆分**：执行票已迁至 `.scratch/orchestella-fastlane/` 的 P01/P02/P03/P04/P06/P07/P10/P11 + **DV-01**。本文件保留历史定义，不再单独推进。

Type: prototype
Status: ready-for-prototype
Priority: P0（Fast Lane 的第一道门）
Owner: protected payload 原型 owner；设备唯一操作者主协调
Blocked by: None
External gates: 当前 HONOR 设备、QA APK、Shizuku/ADB 的 `MANAGE_VIRTUAL_MACHINE` 授权、hidden API 豁免窗口。若平台对 app payload 还要求 `USE_CUSTOM_VIRTUAL_MACHINE`，按实际失败记录并走合法 ADB grant；不绕过权限。
Parent: `docs/reports/orchestration/protected-fast-lane-plan-20261021.zh-CN.md`

## 要回答的问题与价值

当前设备只声明 protected。能否在无 Root、无 unlock、无自定义 pvmfw 的前提下，用 **Google 签名的 Microdroid + 我们自己的 App payload** 完成 create / run / vsock / encrypted storage / stop？

这不是 custom Full Linux，也不是 25/26 的签名/私有盘实验。它只证明：protected 下是否存在一条 App 可控的硬件加速执行环境。若这个门失败，Fast Lane 停止，QEMU 继续交付。

## 允许/禁止范围

允许：

- 独立 QA APK（可与正式 App 同仓但独立 applicationId/签名），最小 `AVmPayload_main` payload。
- `VirtualMachineConfig.Builder` + `setPayloadBinaryName("libfastlane_probe.so")` + `setProtectedVm(true)` + `setMemoryBytes(1GB)` + `setCpuTopology(ONE_CPU)` + `setEncryptedStorageBytes(256MB)`。QA 可先用 `DEBUG_LEVEL_FULL` 抓 console；生产目标 `DEBUG_LEVEL_NONE`，成功判据以 vsock/存储为准，不以 console 为唯一证据。
- `VirtualMachine.connectVsock(5000)` 与 payload 的 vsock server；payload 写/读 `/mnt/encryptedstore/marker`。
- `MANAGE_VIRTUAL_MACHINE` 通过 Shizuku/ADB 授权；HiddenApiBypass 6.1 在 `Application.attachBaseContext` 安装。
- 只读诊断、脱敏日志、固定 QA 实例名。

禁止：

- 修改 pvmfw、SELinux、targetSdk、全局 hidden API 策略、bootloader；Root/解锁。
- 使用 protected custom kernel/initrd、App 私有 raw 根盘、用户实例、真实凭据。
- 把 `vm run-app` 的无期限 wait 后杀 host adb 当作“VM 已停止”。
- 在 payload 中读取/归档用户数据；记录 token/口令/按键正文。

## 行为验收

- [ ] `getCapabilities()` bit1=true；`setProtectedVm(true)` 不抛；`setProtectedVm(false)` 在当前 ROM 抛 `UnsupportedOperationException`。
- [ ] `create` + `run` 成功；payload 输出固定 marker；宿主 `connectVsock(5000)` 完成握手。
- [ ] payload 写 encrypted storage marker；正常 stop；再次 start；marker 仍在；宿主不能读取明文。
- [ ] `stop` 返回 CONFIRMED；无孤儿 VM；20 次启停全部收敛。
- [ ] 负例：错误 payload 名/损坏 APK/错误签名/错误端口分别有 typed failure；不无限重试。
- [ ] 失败分类至少覆盖：`HIDDEN_API_BLOCKED`、`PERMISSION_DENIED`、`PROTECTED_UNSUPPORTED`、`PAYLOAD_VERIFY_FAILED`、`VSOCK_CONNECT_FAILED`、`STOP_UNCONFIRMED`。
- [ ] 所有证据脱敏；无序列号/IMEI/Android ID/账号/token/按键正文。

## 测试接缝与证据

- 复用 `AvfHostFactsProbe` 的 capability 读取，但必须先安装 hidden API 豁免；探针与 engine 共用一套 shape/failure mapping。
- 新增独立 `protected-payload-probe` QA controller；不并入生产 `AvfVirtualMachineEngine`。
- 证据根：外部 artifact 根下 `protected-fastlane/20261021/f1/`；包含：
  - `getCapabilities` actor/bits/time；
  - create/run 参数（脱敏）；
  - payload console/marker；
  - vsock 握手记录；
  - encrypted storage marker 的跨重启记录；
  - stop 状态/耗时/退出分类；
  - 20 次启停结果表；
  - 失败样本与最早拒绝层。
- 不把 fake SDK 或 `vm info` 声明当真实 payload 成功。

## 结果分类

`pass_platform_protected_payload` / `rejected_at_stage` / `blocked_external` / `stop_unconfirmed`。

成功只完成 F1，不代表 Fast Lane Linux toolbox、网络、显示、性能或 Full Linux。

## 停止条件

- `setProtectedVm(true)` 抛异常：该 ROM 不支持 protected app payload；记录，Fast Lane 停止。
- `create`/`run` 在 payload builder/verify 阶段被拒：记录最早层；不改系统信任，不绕过。
- vsock 无法连接：先修 payload readiness/端口；仍失败则停。
- stop 不收敛：先修生命周期；不进入 F2。
- 任何需要 Root/unlock/pvmfw 修改的“修复”：立即停止该设备的产品化。

## 数据、许可与回滚

- 只使用新 QA 实例/新 encrypted storage；不操作正式实例/根盘/凭据。
- payload 源码自研；AOSP Microdroid/AOSP 头文件 Apache-2.0；新增第三方依赖逐项记录许可证/SHA。
- 正常 stop + close 即回滚；QA 文件按动作包保留，不自动删除 Class A 证据。
- 报告：`docs/reports/prototypes/fastlane-28/prototype-report.zh-CN.md`（新建时先查重）。
