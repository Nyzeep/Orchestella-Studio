# DV-01A 复现轮 + AVF mode-facts + 16K 宿主取证（device-myron，2026-11-04/05）

- 日期：2026-11-04（跨零点至 11-05）
- Base：main `f366bbf`，分支 `device/dv01a-mode-facts-20261104`（worktree）
- 设备：**device-myron**（别名口径；本轮唯一真机，全称画像见 §1）
- 证据根（仓外，永不入库）：`evidence/dv01a-20261104/`（外部 evidence 根）
- 报告口径：全部数字来自本轮真机实测；未跑项如实标 not run；不虚报。

## 0. 结论摘要

1. **DV-01A 判定：仍阻塞（同机制、同签名跨平台复现）**。在与 2026-10-21/22 不同平台（HyperOS OS4.0 / Android 17 API 37）上，用相同 QA probe 合同复现 `fln1=7 execve_errno_13`、`TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`；`blocked_external` 维持，平台变更请求（2026-10-22）仍未被该 OEM OTA 满足。
2. **AVF mode-facts：device-myron 为 protected-only**。App actor `getCapabilities` raw bits=1（protected=true / non-protected=false），`setProtectedVm(false)` REJECTED；qualification 四阶段 pass，`platform_qualification_samples=1`，`real_avf_samples=0`。
3. **16K 宿主：4K**（四渠道交叉验证一致）。ACR §8.2 第 2 行（16K 宿主）**不闭合**，但新增一个 Android 17 release 级 4K 负样本，强化 W-FAB-03「无出货设备默认 16K」平台级判词；§8.2 第 6 行（distcc@36）同步追加 DV-01A 跨平台复现确认。
4. **新平台事实（本机特有，影响取证方式）**：HyperOS freezer 会在 QA app 创建 VM 后约 5 秒冻结整 uid（`freezeUid SUCCESS reason=new process / tobg`），deviceidle 白名单不豁免，亮屏 + 前台（TOP_APP）可绕行；受保护 VM 的 guest console 在该平台只转发 pvmfw 阶段，**guest 内核 avc 行不可采**（host `dmesg` 亦受 SELinux 限制）。

## 1. 设备清点与别名

| 项 | 值（仓库口径） |
| --- | --- |
| 别名 | device-myron（与 `.scratch/round-20261104-device-window/spec.md` 既有别名一致） |
| 制造商/ROM（粗粒度） | 小米 HyperOS OS4.0（ro.mi.os.version.name=OS4.0，miui V816，region CN，commercial） |
| Android 版本 | release 17 / SDK 37（sdk_full 37.0），security patch 2026-10-01 |
| SoC（粗粒度） | 骁龙 SM8850 档，board canoe，Adreno GPU |
| 内核 | 6.12.69-android16 GKI（版本串显式 `-4k`，2026-09-08 构建） |
| ABI | arm64-v8a 纯 64 位（abilist32 空） |
| AVF 相关 prop | `ro.boot.hypervisor.protected_vm.supported=true`、`ro.boot.hypervisor.version=gunyah`；`ro.virtualization.supported` 与 `ro.boot.hypervisor.vm.supported` 未设置；`android.software.virtualization_framework` feature=true |
| 真实序列号/型号/IMEI | 只存仓外 `evidence/dv01a-20261104/device-alias-mapping-20261104.md` 与 `getprop-full-20261104.txt`（2012 行完整 getprop） |

## 2. 16K 宿主取证（纯取数）

四渠道交叉验证，结论一致——**4K 宿主**：

| 渠道 | 结果 |
| --- | --- |
| `getconf PAGE_SIZE` | 4096 |
| `getconf PAGESIZE` / `getconf -a` | 4096（一致） |
| `ro.product.cpu.page_size` / `ro.boot.page_size` | 未设置（16K 设备此处应为 16384） |
| 内核版本串 / `/proc/version` | `abogki561813157-4k`（显式 4K 构建） |

原始输出存仓外 `evidence/dv01a-20261104/pagesize-channels-20261104.txt`。影响：ACR §8.2 第 2 行更新为「三设备均 4K 页」并注明本样本为 Android 17 release（判词时效延伸），16K 缺项仍开放。

## 3. AVF mode-facts（复用既有机制）

复用仓库既有 QA 启动链路（`AvfQaProbeInstrumentation` mode=`qualification` + `tools/device-validation/run_avf_device_validation.py` 设备模式 + `--grant`），未新写探测代码。运行于 2026-11-04T15:59:48Z，报告与 stage 证据存仓外 `avf-qualification/avf-device-validation-20261104T155948.783127Z.{json,md}`。

| 阶段 | 结果 | 关键事实 |
| --- | --- | --- |
| platform_declaration | pass | API 37、arm64-v8a、feature=true、declared_protected=true、declared_nonprotected=null |
| capabilities | pass | `manager_present=true`、raw bits=1 → protected=true / non_protected=false；access_state=MODE_UNSUPPORTED；hidden API exemption=INSTALLED |
| permissions | pass | 两权限显式 grant 后 GRANTED（含 force-stop 后复核重读） |
| protected_mode | pass | `setProtectedVm(false)` REJECTED（UnsupportedOperationException）→ **protected-only** |
| create/run_returned/vsock_handshake/stop_cycles | blocked | `QA_LAUNCH_NOT_REQUESTED`（qualification 模式按合同不建 VM） |
| network/share_display/app_death/qemu_comparison | not-tested | `STAGE_NOT_REQUESTED` |

样本计数：`platform_qualification_samples=1`、`protected_microdroid_samples=0`、`custom_linux_samples=0`、`real_avf_samples=0`。产品 readiness 不变：MODE_UNSUPPORTED → QEMU 交付。custom Linux 门保持 `NO_NON_PROTECTED_VM`（bit2 缺失）。

## 4. DV-01A 取证（本轮核心）

### 4.1 复现结果

probe 报告原件存仓外 `probe-fastlane-dv01-preflight-<token>.json`（qaRunToken 不入库，其 SHA-256 记录于仓外别名映射文件）。运行窗口 2026-11-04T16:41:38Z–16:43:40Z：

| 阶段 | 结果 |
| --- | --- |
| parameter_validation / platform_facts / create / run / payload_ready / control_hello | pass（`VM_CREATED`、`RUN_RETURNED`、`PAYLOAD_READY_OBSERVED elapsed_ms=800`、`HELLO_HELLO_OK elapsed_ms=3`） |
| **terminal_pty** | **blocked / `TOOLBOX_EXEC_BLOCKED_BY_PLATFORM`，`fln1_code=7`、`exec_stage=execve`、`errno=13`（EACCES）、`output_bytes=0`** |
| stop / delete | pass（`STOP_CONFIRMED`、`VM_DELETED`） |

绑定复核：`toolbox_manifest_sha256=e0352292…` 与本票 2026-10-21 权威证据一致；APK/payload SHA-256 记录于 probe 原件（本工作树从 2026-10-24 构建 payload 复用，其源码此后仅 distcc/cc1 变更，不触及 exec 路径——如实披露）。outcome=`PREFLIGHT_TERMINAL_BLOCKED`；qualification 侧 `protected_support=SUPPORTED / non_protected_support=UNSUPPORTED / capabilities_raw=1`。

**判定：仍阻塞。** 分层诊断与 2026-10-21/22 证据同构（`realpath/stat/access(X_OK)` 通过、仅 `execve` 拒绝），机制仍为 Microdroid SELinux 缺 `execute_no_trans`（A16/A17 平台 policy 分析见工单与 2026-10-22 研究文档，本轮无新 policy 证据可推翻）。

### 4.2 avc 行取证说明（诚实边界）

本轮**未取得与 2026-10-21 同形的 raw avc 行**，原因（均为实测）：

- 该平台受保护 VM 的 guest console 只转发 pvmfw 阶段（`Console(2051): [INFO] pVM firmware … Starting payload...` 后无任何 guest 内核输出，内核 quiet）；
- host 侧 `dmesg` 受 SELinux 限制（`klogctl: Permission denied`，logcat 中宿主 audit 亦印证 shell 域 `syslog_read` 被拒）。

因此本轮阻塞判定由 payload 侧 typed 诊断承载（同签名、逐字一致），avc 行的机制描述沿用工单中 2026-10-21 记录。此边界已同步写入 DV-01A 工单与 ACR §8.2 第 6 行。

### 4.3 新平台事实：HyperOS freezer 与 QA instrumentation 互斥（三轮实测）

- QA app 在 VM 创建后约 5 秒被 HyperOS 冻结：`PolicyMaker: uid=10054 pkg=…qa.dv01 reason=freeze_able` → `ActionExecute: freezeUid SUCCESS reason=new process`（第 1/2 轮）或 `reason=tobg`（第 3 轮，熄屏后 activity 转后台）；crosvm 子进程随之停摆，`am instrument -w` 在 host 侧永久挂起。
- **deviceidle 白名单不豁免该冻结**（第 2 轮已加白名单仍被 `new process` 冻结）；**亮屏 + QA app 前台（TOP_APP）可绕行**（`PolicyMaker: reason=TOP_APP` 期间不冻结）。最终成功轮以前台 + 守护式唤醒循环完成全程；随后按纪律解除白名单与 stayon。
- 这与 UML 线 2026-11-08 设备轮结论（「长驻须 specialUse FGS + PARTIAL_WAKE_LOCK」）互相印证，但把适用范围扩展到「受保护 VM QA instrumentation」场景。

### 4.4 harness 偏差披露

`run_dv01.py`（DV-01B host runner）两轮均因上述冻结在 `am instrument` 步挂起，被终止后改用**与 harness 完全同参**的 `am instrument` 直跑（device 侧命令逐字一致：`qaMode fastlane-dv01-preflight` + `qaToolboxSha` 等；区别仅在 host 编排与 artifact 布局——本轮以仓外 probe 原件 JSON + logcat 切片为证据，未走 run_dv01 的 run-summary/SHA256SUMS 目录约定）。 qualification 侧 harness（run_avf_device_validation）完整跑通、无偏差。

另两处环境偏差，均已恢复并如实记录：
1. 本分支 androidTest manifest 按 DV-PVM-02b 口径只声明 `PvmProbeInstrumentation`，而 DV-01A/mode-facts 合同要求 `AvfQaProbeInstrumentation`；本轮**临时改声明、重建 test APK、跑完即 `git restore`**，该文件未入任何 commit（AGP 对 androidTest 多 `<instrumentation>` 声明只保留最后一条，故不能两探针并存）。
2. HyperOS 安装门：`adb install` 在锁屏/熄屏时被 MIUI `AdbInstallActivity` 确认弹窗超时拒绝（`INSTALL_FAILED_USER_RESTRICTED`）；亮屏解锁后同命令成功。未改任何系统设置。

## 5. 未测项（not run）

- DV-01 全量 20 轮、encrypted storage persistence、负例：**not run**（本票 `blocked_external`，无 20 轮要求；probe 报告 `not_tested` 字段如实记录）。
- protected Microdroid 之外的生命周期（custom Linux、网络、9p、RFB、性能）：**not run**（设备 protected-only，custom 门阻断）。
- `run_dv01.py` 的完整 artifact 目录约定（run-summary/SHA256SUMS/validation）：**not run 到完成**（freezer 挂起终止，见 §4.4）。
- Pixel 系 16K 开发者选项设备、A17 QPR 平台变更请求满足性：**not run**（无设备）。
- distcc@36 实测：**not run**（仍被 DV-01A 阻塞）。

## 6. 用户数据与声明边界

- 是否触碰用户数据：**否**。仅安装/授权/运行 QA 包（`io.github.nyzeep.orchestella.qa.dv01(.test)`）；未读/未改用户实例、根盘、导出、凭据、屏幕内容；未卸载、未清数据、未 wipe；未改系统设置——唯一的临时系统级动作用了任务纪律明确许可的两项并已全部解除：deviceidle 白名单（已 `-` 移除）与 `svc power stayon`（已 `false` 还原，`stay_on_while_plugged_in` 复核为 0）。
- 是否允许声明 AVF 已支持：**否**。`real_avf_samples=0`（qualification 不算生命周期样本）；DV-01 仍 blocked_external。
- 仓库内未出现任何真实序列号/IMEI/Android ID/设备指纹/哈希/截图/完整日志/真实 IP/MAC；设备一律 device-myron 别名，型号/SoC 用粗粒度口径。

## 7. 证据与文件清单

仓外（真实标识允许）：`evidence/dv01a-20261104/` 下 `device-alias-mapping-20261104.md`、`getprop-full-20261104.txt`、`pagesize-channels-20261104.txt`、`avf-qualification/`（harness 报告 + device-evidence + preservation）、`probe-fastlane-dv01-preflight-<token>.json`、`preflight-run4-window-slice.txt`（virtmgr/Console/freezeUid 关键行）、`logcat-during-preflight-20261104.txt`（全程 26 万行原始捕获，仅存外部）。

仓内（本轮 commit）：
- `.scratch/orchestella-fastlane/issues/DV-01A-toolbox-exec-selinux.md`（2026-11-04 复现节 + 判定）
- `docs/research/ACR-draft-four-lanes-20261029.md`（§8.1 修订行 + §8.2 第 2/6 行时效更新）
- 本报告

## 8. 下一步

1. DV-01A 维持平台/ROM 决策方跟进（平台变更请求不变）；若取得 Pixel 系或已修复 OTA 的设备，按工单解除条件重跑 preflight。
2. QA 探针线后续轮次在该类 HyperOS 设备上直接采用「亮屏 + 前台（或 FGS）」预案，避免 freezer 挂起；`run_dv01.py` 如需在该平台完整跑通，需给 host runner 增加前台守护或把 FGS 预案写进 QA probe（另开工单，不在本轮）。
3. 16K 宿主缺项维持 ACR §8.2 开放，等待 Pixel 16K 开发者选项设备或 OEM 出货机型。
