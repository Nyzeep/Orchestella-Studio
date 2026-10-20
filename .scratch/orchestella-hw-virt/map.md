# Map: AVF 零设备硬化

Type: map
Status: active
Base: `avf/zero-device-hardening`（从 `avf/mode-facts` @ `9f7790e` 分出）
Date: 2026-10-19

## Destination

把 AVF 从“引擎写完、接线残缺”推进到**零设备可验收的代码就绪状态**：所有权/停止/退出可信，能力声明与真实接线一致，平台访问（hidden API/权限/模式）有单一接缝，数据面要么接线要么诚实标 UNSUPPORTED，CI 能阻塞回归，设备验收工具可 dry-run。完成后交回用户，用真机做设备验收；本 effort 不产生任何“AVF 已支持/已通过”的产品声明。

## Notes

- **零设备是硬约束**：本 effort 的每个工单都必须在 fake host / fake vsock / JVM / URI fixture 上完成验收；不得把“需要真机”写进 Definition of Done。真机验收是下一个 effort。
- **基线**：`avf/mode-facts` @ `9f7790e` 已包含票 23 的模式事实切片；本分支已包含它。票 23 的正式报告/双轴评审/合并 main 折进工单 10，不单独占并行槽位。
- **证据**：`docs/research/2026-10-19-avf-reachability-corrections.zh-CN.md`（本分支 force-add）。其中 Q3 已由 AOSP 源码判定、Q4 对 non-protected 不适用、Q1 条件成立但脆弱；当前 HONOR 为 protected-only，不能运行未签名 custom Linux。
- **工程默认值（可在实现中调整，但必须显式记录）**：
  - Owner：App-owner + non-protected；shell-owner 不在本 effort 实现，只保留接口。
  - CPU：AVF v1 默认/上限 1 vCPU；>1 明确 UNSUPPORTED，不做 hidden exact-count hack。
  - 显示：RFB over vsock 作为唯一生产路径；`AvfWaylandChannel` 若不能端到端接线则删除或移出生产。
  - 数据面：终端 + Bridge + 显示为 v1；9p/端口转发要么接线，要么明确 UNSUPPORTED；音频/剪贴板同理。
  - 网络：`useNetwork` 条件化；Android 17 用 headless 2D GPU workaround；失败必须可识别并可降级，不得无限重试。
- **并行规则**：每个工单一分支（建议 `ticket/<NN>-<slug>`）；共享文件只做最小改动，不做格式化；冲突在集成分支解决。共享文件：`AvfInstanceLauncher.java`（02/03/06/07/08）、`ReflectiveAvfVirtualMachineApi.java`（02/03/05/08）、`AvfCapabilityEvaluator.java`（04/05）。
- **质量门**：每个工单先写 red 测试，再最小 green；报告必须区分“JVM/fake 通过”与“真机未验”。不得用 `--no-verify`、跳测试、关 lint 或删用户数据换取绿。
- **分支/远端**：`origin` = `git@github.com:Nyzeep/Orchestella-Studio.git`；本分支推送后，新 agent 从这里开 per-ticket 分支。
- **工单文件是 review 过的任务文档**：`.scratch/` 默认 ignore；只 `git add -f` 本目录的 map/tickets 与指定 research 文档，不整目录 force-add。

## Decisions so far

- **Q3 关闭**：AOSP A16 QPR2 `android/virtmgr/src/virtualmachine.rs:679-710` 的 label 校验整段在 `if config.protectedVm` 内；non-protected 不检查 App 私有盘 label。ADR-0007 对 App-owner + non-protected 保住；真机 create 仍要在设备 effort 复核。
- **Q4 对 non-protected 不适用**：DICE/pvmfw 是 protected VM 的启动链；raw custom kernel 只能 non-protected，protected 需要 pvmfw 签名 payload。不要为本 effort 设计“App 升级重建实例”的 DICE 迁移；持久 VM 配置路径稳定性由工单 03 处理。
- **Q1 条件成立**：TerminalAppPlus（targetSdk 37 + hiddenapibypass 6.1）证明本进程豁免 + 两次 `pm grant` + non-protected 可跑通；但 Android 16 的 ART 变更曾破坏旧版，且 Google 不保证。工单 05 实现 App-owner 接缝，shell-owner 留作设备门失败后的下一 effort。
- **设备门**：当前 HONOR MEY-AN00（SM8735/API 36）protected-only，`setProtectedVm(false)` 直接抛异常；不能作为 AVF custom Linux 交付路径。设备资格（Pixel 6 Pro/Pixel Tablet/Pixel 10/11 或 MT6991/GenieZone，必须实测 `getCapabilities` bit2）是下一个 effort 的入口，不属于本 map。
- **QEMU 边界**：QEMU 冻结功能，继续作为默认交付；本 map 不改 QEMU 引擎功能，只允许必要的 capability matrix 修正（04）和 CI（01）。发布 keystore/迁移/真机 20 次启停/T7 性能不在本 map。
- **范围**：本 map 只做零设备代码硬化，不做真机、prototype、protected、shell-owner、H5 rig、QEMU 新功能。
- **零设备收口（2026-10-20，Agent D / 10）**：01–10 的代码/测试/报告已完成，集成分支 `avf/zero-device-integration` 由 09→10 链构成；全量 runtime checks + app 875 tests + lint/assemble + APK 16KB gate 本地通过。真实 AVF 样本仍为 0，GitHub Actions 远端未执行。
- **平台事实补强**：`AvfPlatformFacts.managerPresent` 让“OEM 无 feature 字符串但 manager 存在 + 权限未授予”进入 `NEEDS_AUTHORIZATION`，不是“设备未声明”；`HIDDEN_API_BLOCKED`/`PERMISSION_DENIED` 的 shape 失败也算 manager 存在。权限未授予仍不等于 READY。
- **能力边界**：AVF 的 audio/clipboard/pointer/GPU/9p 状态按实际接线；QEMU 的 audio/clipboard/pointer 由 DesktopActivity/UI 驱动，**不是** session capability，已写入 ADR 索引差异说明与 `QemuSessionCapabilityTest`。没有为了对齐而虚报。
- **RFB 输入**：`AvfRfbOverVsockTest` 增加 keyboard/pointer=true 的 fake vsock 输入契约（keysym/坐标/统计），补上删除的输入测试缺口。
- **元数据保留**：`InstanceLifecycle` 现在保留未知元数据键（含 `hardware.*` 未知键），并有 `instance-metadata-v1-golden.properties` golden fixture；未知键不参与硬件解析。
- **16KB 闭包**：`check-apk-16kb.sh` 启用 `REQUIRE_BUNDLED_CLOSURE=1`，对 APK 内 DT_NEEDED 的 bundled 依赖做集合闭合断言（本地结果 resolved=0、external=13）；QEMU 产品 ELF 未在本环境构建，其闭包仍是 release 风险，不写成“闭包已验证”。
- **默认 2 vCPU**：未改产品默认值；UI 只在 AVF READY 且 `cpuSupport != SUPPORTED` 时显示 1 vCPU 约束。是否把 AVF 默认设为 1 vCPU 仍待用户决策。
- **设备模式（2026-10-20，Session E）**：`--device` 已实现 host 侧真实 adb 编排（AdbRunner + 每命令 timeout/整轮 deadline/命令脱敏 + QA-only instrumentation 资格探针）；本环境无设备，真实设备模式报告 `NO_DEVICE`、8 blocked / 4 not-tested、`real_avf_samples=0`。create/run/vsock/stop 在本票探针中仍 blocked（`QA_LAUNCH_ENTRY_NOT_IMPLEMENTED`），未产生 AVF 成功声明；未触碰用户数据。
- **QA 启动链路与设备门（2026-10-20，Session F）**：QA 探针新增 `qualification` / `protected-microdroid` / `custom-linux` 三种模式；protected Microdroid 仅用系统 OS + payload，custom Linux 仅在有 bit2 时进入。本环境仍无设备（`adb devices` 为空），未取得 protected-only 真机资格，protected Microdroid 未执行（无设备，且仓库无内置 payload）；`custom_linux` 保持 blocked：设备能力门为 `NO_NON_PROTECTED_VM`，本轮实际首因是 `NO_DEVICE`。样本计数全部为 0（`platform_qualification_samples=0`、`protected_microdroid_samples=0`、`custom_linux_samples=0`、`real_avf_samples=0`），不得据此声明产品 AVF 支持。下一轮必须使用真实 non-protected 设备完成 custom Linux 闭环。


## Tickets

| # | 工单 | 类型 | Blocked by | 初始 frontier |
| --- | --- | --- | --- | --- |
| 01 | [CI 全量门禁](issues/01-ci-full-gate.md) | implementation | — | 是 |
| 02 | [AVF 运行时所有权、停止与退出](issues/02-avf-runtime-ownership.md) | implementation | — | 是 |
| 03 | [AVF 启动准入与持久配置迁移](issues/03-avf-launch-admission.md) | implementation | — | 是 |
| 04 | [能力真相单一来源](issues/04-capability-truth.md) | implementation | — | 是 |
| 05 | [平台访问与 hidden API 接缝](issues/05-platform-access-hidden-api.md) | implementation | — | 是 |
| 06 | [显示/音频/剪贴板真相](issues/06-display-audio-clipboard-truth.md) | implementation | 04 | 否 |
| 07 | [9p 与端口转发接线](issues/07-ninep-and-port-forwarding.md) | implementation | 03, 04, 06 | 否 |
| 08 | [Android 17 网络契约](issues/08-android17-network-contract.md) | implementation | 03 | 否 |
| 09 | [设备验收工具 dry-run](issues/09-device-validation-harness.md) | implementation | — | 是 |
| 10 | [集成守卫、票 23 收尾与交接报告](issues/10-integration-and-report.md) | implementation | 01–09 | 否 |

**初始 frontier**：01、02、03、04、05、09（6 个并行；02/03 共享文件但无逻辑依赖，集成时按方法边界合并）。03 完成后启动 08；04 完成后启动 06；03+04+06 完成后启动 07；全部完成后做 10。

## Ticket status（2026-10-20）

- 01–09：resolved（零设备）；分支均已推送 GitHub；10 在 `ticket/10-integration-and-report` 收口。
- 10：resolved（零设备）；集成分支 `avf/zero-device-integration` 从 09→10 链创建并推送；不合并 `main`、不发布、不打 tag。
- 票 23：completed（零设备代码就绪）；报告/双轴评审在 `docs/reports/tickets/avf-shizuku-23/`；真机项留设备门。
- 真实 AVF 样本：0；GitHub Actions 远端未执行，本 map 只声明本地等价通过。


## Not yet specified

- 设备资格与真机验收：属于下一个 effort；本 map 只交付工具（09）和代码就绪。入口清单见 research 文档 §3.3。
- AVF vs QEMU 性能：必须在真机、同资源、1 vCPU 条件下测；本 map 不产生性能声明。

## Out of scope

- 真机 create/run/vsock/stop/network/9p/display 验收（用户提供设备后另开 effort）。
- protected VM 产品线（未签名 custom kernel 不可行；需要签名 payload/设备 owner/无原生网络，属于另一产品契约）。
- shell-owner / UserService / ACR-01 实现（仅保留接口；HiddenApiBypass 6.1 失败后再评估）。
- QEMU 新功能、QEMU 发布签名/迁移、Android 17 全量适配。
- H5 QEMU aarch64 rig（可选 spike，不进关键路径）。
- hidden exact-count CPU hack、MATCH_HOST+nr_cpus 高级拓扑（v1 固定 1 vCPU）。
- 性能优化/基准、release 发布、合并 main（票 23 的 main 合并由工单 10 产出报告后由用户/集成 owner 决定）。
- **把 AVF 默认改为 1 vCPU 的产品决策**：10 只保留诚实提示与守卫，不改默认值；待用户拍板。
- **QEMU 产品 ELF 的 16KB DT_NEEDED 闭包验证**：本环境未构建 QEMU 产品二进制，只断言了 APK bundled 闭包；release 前仍需在 QEMU 构建产物上执行。
