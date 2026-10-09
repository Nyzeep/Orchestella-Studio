# Ticket 26 真机验收与 ADB 可行性研究

> 日期：2026-09-07（Asia/Shanghai）
> 范围：Ticket 26 的真机/ADB 验收环境、HITL 前置条件与 B4 分类。
> 方法：只读核对当前 tracker、已接受 ADR、host policy、当前项目内工具链，并核验 Android Developers 官方文档。未调用 ADB、未连接设备、未安装工具或驱动。
> 执行者：device_acceptance_research child（GPT-5.6 Terra / max）；Orchestrator addendum
> 的当前会话 model/reasoning 未向报告接口公开。

---

## 结论

**B4 = `EXTERNAL BLOCKED`。**Ticket 26 的物理 Android 设备是本票自身的硬验收门，而不是可推迟给 Ticket 18 的“仅原型”条件；无需架构决策。

但应精确区分两件事：

1. **真机是硬验收前提。**Ticket 26 验收第一条要求“真机（arm64、RAM≥3GB）从安装到 shell”的路径可重复，第四条要求桥接握手、版本协商和 token 校验在真机通过。没有合格设备及可执行测试路径，不能把本票验收标为通过。
2. **ADB 不是 QEMU/L1 产品运行时前提，而是当前可重复真机验证路径的环境前提。**L1 是“安装即用”的 QEMU 软件虚拟化；`adb pm grant`/Shizuku 是 L2/AVF 授权机制。若未来有已记录、可重复、可取证的替代部署/日志路径，Ticket 26 的文字本身不强制使用 `adb` 命令；但当前没有该替代计划，且项目内 SDK 没有 Platform Tools。因此不能声称真机验收已可执行。

这一区分意味着：B4 不阻止软件层的 JVM、契约或 APK 构建工作，但**单独足以使“Ticket 26 已具备完整可执行验收路径”的 readiness 判定不能 PASS**。

---

## 1. 仓库一手证据

| 事实 | 已核实证据 | 对 B4 的含义 |
| --- | --- | --- |
| 真机功能验收是 Ticket 26 的明示条件 | `.scratch/orchestella-platform/issues/26-impl-first-slice-alpine-shell-qemu.md` 的 Acceptance criteria：arm64、RAM≥3GB 真机全流程；真机桥接正/负路径；模拟器提示 | 真机不是性能研究的替代名词，而是本票的验收装置。模拟器仅覆盖其自身提示条件，不能替代前两项。 |
| QEMU/TCG 原型不是 Ticket 26 的前置依赖 | 同一 Ticket 的 Test strategy 与 Prototype/research dependency：真机冒烟清单；“无硬依赖；QEMU TCG 基线（18）在本票完成后领取” | Ticket 18 不能被用于把本票的功能性真机 smoke 延后并视作已验收。 |
| Ticket 18 的范围是性能/打包基线 | `issues/18-prototype-qemu-baseline.md` 第 8–36 行：启动、shell 响应、吞吐、内存、jniLib/16KB 页对齐；阻塞 35 | 它是后续原型数据门，不是 Ticket 26 的功能验收替代物。 |
| Ticket 17 的 ADB 是 AVF/L2 语境 | `issues/17-prototype-avf-e2e.md` 第 8–37 行：真实 AVF 设备、custom VM、vsock、网络；阻塞 34 | AVF 授权/能力数据属于另一条策略的原型，不是 QEMU/L1 的 `pm grant` 前提。 |
| L1 与 L2 的权限边界已决定 | `docs/adr/0003-rootless-tiers.md` 第 6–20 行：L1 软件虚拟化安装即用；L2 通过 ADB/Shizuku 解锁 AVF | 不能把 L2 的授权要求错误加到 Ticket 26 的 QEMU 产品路径；也不能据此否认真机验收。 |
| 支持地板要求实际设备属性 | `docs/adr/0004-compat-floor.md` 第 6–27 行：minSdk 26、仅 arm64-v8a、RAM≥3GB、存储余量；模拟器诚实提示 | 设备记录至少要证明 API、ABI、RAM；存储余量是创建实例的附加执行条件。 |
| 生命周期承诺含跨进程语义 | `docs/adr/0011-lifecycle.md` 第 6–25 行 | 正常停止、二次启动与状态持久化不能只由 JVM 测试替代。 |
| 当前项目内 SDK 的直接盘点 | `.toolchains/android-sdk/` 仅含 `build-tools/36.1.0`、`platforms/android-36`；无 `platform-tools/`、无 `adb.exe`；`local.properties` 不存在 | **项目范围内的 ADB 不可用**。这不推出 Windows 任意位置不存在 ADB。 |
| 设备事实未被保留 | `docs/reports/orchestration/ticket-26-readiness-audit.zh-CN.md` 第 115–125 行：无已确认合格设备或 Ticket 26 设备记录 | 只能得出“当前不可证明/不可审计”，不能杜撰为“用户没有设备”。 |

历史 `docs/reports/tickets/25/worker-scope-contract.zh-CN.md` 第 110–117 行曾写“已有 ADB”，但没有可核验路径、连接状态、授权状态或测试记录。按 `ENGINEERING_HANDOFF.md` 第 6–16 行的事实优先级，当前实际项目内工具链盘点比无路径的历史陈述更能证明可审计的项目环境；系统级 ADB 的存在与否仍为**未知**。

---

## 2. Android 官方一手来源

1. [Android Debug Bridge (adb) — Android Developers](https://developer.android.com/tools/adb)（2026-09-07 访问）说明：
   - `adb` 包含在 **Android SDK Platform Tools** 中，安装位置为 `android_sdk/platform-tools/`。
   - USB 连接使用 `adb` 前，设备必须在 Developer options 中启用 USB debugging；`adb devices` 可验证连接。
   - Android 4.2.2/API 17 及以上会显示 RSA 调试密钥确认对话；解锁设备并确认前，不能执行 USB debugging/ADB 命令。
   - `adb install path_to_apk` 可将 APK 安装到已连接设备；该页也说明 Android Studio 可代为完成安装，故“直接执行 adb”并非产品文字层面的唯一部署方式。
2. [Run apps on a hardware device — Android Developers](https://developer.android.com/studio/run/device)（2026-09-07 访问）说明：设备开发前需选择 USB 或 Wi-Fi 连接、启用 USB debugging；Windows 在适用时需要安装 ADB USB driver。

这些资料证明的是 **ADB 测试通道的技术前置条件**，不证明本项目已有设备、驱动、授权或连通性，也不证明某一 OEM 的具体行为。

---

## 3. 软件就绪与物理验收就绪的边界

| 层级 | 可由什么证明 | 不能证明什么 |
| --- | --- | --- |
| 软件就绪 | `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`（见 `docs/development/android-bootstrap.md`）；Full Linux 契约测试与实例状态机/JVM 测试 | APK 能在合格真机完成下载、QEMU 启动、真实 Android 生命周期、桥接传输与 token 负例。 |
| 物理验收就绪 | 已授权的合格设备、可用部署/日志通道、明确真机 smoke 清单、可保存的原始证据 | 性能数据、AVF custom-VM 网络/GPU/instance 行为；这些仍分别由 Ticket 18/17 原型回答。 |

`spec.md` 的 Testing Decisions 也将设备侧性能和 AVF 未知数交由两张原型票，但没有撤销 Ticket 26 的真机功能验收。正确划分是：**Ticket 26 要功能性真机 smoke；Ticket 18 要性能与存储基线；Ticket 17 要 AVF/L2 能力事实。**

---

## 4. 仅针对 B4 的最小 HITL 解锁条件

以下是待用户/Orchestrator 确认的条件，不是本报告声称已满足的事实：

1. **设备与授权**：用户提供并允许测试一台实体、未 Root 的 Android 设备；记录型号、build fingerprint、API level（≥26）、`arm64-v8a` ABI、总 RAM（≥3 GiB）和可用存储。不得把“没有仓库记录”写成“没有设备”。
2. **调试链路**：若采用 USB ADB，用户在设备上启用开发者选项/USB debugging、连接数据线并确认 RSA 指纹。Windows 若需要 OEM USB driver，先识别精确驱动和目标，之后才按政策披露并取得授权；不得假定驱动缺失或擅自安装。
3. **工具位置与宿主权限**：若需取得 Platform Tools，优先放在项目 approved root；不得改 system `PATH`。`HOST_ENVIRONMENT_POLICY.md` 第 24–35、59–95 行要求所有 `C:`、user-level external runtime、驱动或 system-level 写入先披露明确路径、影响、覆盖与清理计划。仅因历史文档提到 ADB 不构成调用、启动 server 或写入用户目录的授权。
4. **执行资产**：在 B1–B3 已单独解决、APK 和 QEMU/guest 资产可用后，才运行真机验收；这些不是 B4 的设备选择条件，但缺一仍不能完成端到端测试。

---

## 5. 推荐的最小真机证据包

执行时将每项证据关联到 Ticket 26 的对应 acceptance criterion，并保留 APK SHA-256、Git commit、时间、设备描述和原始命令退出结果。日志/截图必须脱敏，尤其不得输出 per-VM token。

1. 设备资格和连接：设备资料、USB debugging/RSA 已确认、连接状态；若不使用 ADB，记录等价且可复现的安装/日志通道。
2. AC-1：从首次安装到 Alpine 创建、官方下载、SHA-256 校验、QEMU 启动、首次装配、root shell；至少两次独立重复记录。不要自行创造启动耗时阈值。
3. AC-2：受控校验失败输入，记录创建被拒绝及用户可理解的原因。
4. AC-3/5：写入 sentinel 数据，验证 guest 正常停止后的再次启动、数据和性能徽章，以及宿主
   进程仍存活时 UI/Activity 重建后的正常状态显示；进程死亡后的旧状态对账与 cold restore
   只按 Ticket 28 的边界验收。
5. AC-4：桥接握手/版本协商/正确 token 的正例，以及错误 token 被拒绝的负例；仅记录结果和安全脱敏的诊断。
6. AC-6：单独验证模拟器环境提示。模拟器是该条的被测对象，不是 AC-1/4 的替代设备。

---

## 6. 状态建议

`TICKET_EXECUTION_GUIDE.md` 第 13–15 行把“外部条件缺失（如 P1 需真机）”定义为 `blocked`，第 28 行要求 ready 判断中外部条件不满足时注明缺失条件。因此，**若由有权的 Orchestrator 为 B4 记录状态，建议使用 `blocked` 而非 `needs-triage`**，解除条件应写明“已获得经授权的合格真机及可执行、可取证的部署/日志路径”。

本研究 child 交回时，Ticket 26 仍有 B1–B3 的 scope/ownership/供应链问题；本报告本身没有
修改其状态，也不以 B4 取代这些问题。B4 不触发 `needs-architecture-decision`：Ticket、spec
和已接受 ADR 已足够确定产品语义，缺口是外部测试环境和 HITL 授权。

## Orchestrator addendum

本研究交回后，B1–B3 已由受限 scope contract、26/28 lifecycle split 和 QEMU supply-chain
policy 解决；详见 docs/reports/orchestration/ticket-26-blocker-resolution.zh-CN.md。B4 仍是唯一
未满足的 external blocker，因此 Orchestrator 已将 Ticket 26 状态从 needs-triage 记录为
blocked。本 addendum 不声称设备、ADB、驱动或真机验收已就绪。

## 执行记录与治理边界

- 背景 / scope：为 Ticket 26 readiness 的 B4 分类真机/ADB 验收环境和 HITL 前置条件；只读
  调查不构成设备测试或主机环境授权。
- 实际完成：核验 ticket、ADR、host policy、项目内 Android 工具链与 Android 官方文档，区分
  L1 的产品运行时前提和真机验证通道，并给出最小可审计证据包与 unblock 条件。
- 未完成：未找到或连接合格设备，未调用 ADB，未安装 Platform Tools、OEM driver 或其他宿主
  工具，未安装 APK，也未运行任何真机验收。
- 修改文件：本研究报告；Ticket 状态、scope 和 re-audit 的治理修改由 Orchestrator 在独立
  blocker-resolution 变更中记录。
- 测试与验证：只读项目 SDK 盘点、tracker/ADR/policy 文本核对与 Android Developers 一手
  文档核验；没有产品测试、设备测试或外部 runtime 操作。
- branch / 基线 / commit：main / 83309ec / 同批治理提交待创建；不以待创建 commit 冒充已提交。
- 越界与 Architecture Change Policy：未修改 ADR、spec、CONTEXT、Ticket 25、其他 tracker
  ticket 或宿主环境；B4 是外部/HITL blocker，不是 architecture decision，未触发 ACR。
- 风险与后续动作：只有用户提供并授权合格实体设备及可取证部署/日志路径后，才可按
  Host Environment Policy 重新检查 B4；设备仍未就绪时，不得把模拟器或 Ticket 17/18
  原型替代为 Ticket 26 功能性真机验收。

报告人：Codex research child + Orchestrator addendum ｜ 日期：2026-09-07 ｜ 耗时：未单独计时
