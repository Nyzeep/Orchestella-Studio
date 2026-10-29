# 会话 23 · 报告：36 域收尾与 ACR 决策材料

> **归档说明（会话 24 入库）**：本文是会话 23 的现场报告原文，仅补本段与路径澄清，结论未改写。
> 文中 `evidence/unlock36-20261029/round23/` 指的是**仓库外**的工作区证据目录（设备截图、串口日志、
> `wt1-offline-root-cause.md` 等），不随源码入库；`docs/…` 路径相对仓库根 `Orchestella_dev/`，
> `../…` 相对工作区根。设备序列号、实例目录绝对路径等开发者数据未收录。

> 日期 2026-10-29｜基线 `main = 0383393`｜设备在线（序列号不入库）｜实例 `0d0cac1d`（arch / rolling）
> 性质：收尾验证轮 + 决策材料轮。未写产品代码、未改 `CONTEXT.md`/ADR。
> 证据目录：`evidence/unlock36-20261029/round23/`

---

## 1. 结论摘要

| # | 任务 | 判定 | 一句话依据 |
|---|---|---|---|
| T-1 | guest 网络 offline 归因 | **完成，且原命题被推翻** | 网络健康（网关/上游 ICMP 0% 丢包、DNS 通、`nc` 53/80 通、`pacman -Sy` 下载 10.2 MiB @ 0.8–1.7 MB/s）；真因是 pacman 7 的 Landlock 下载沙箱在 guest 内核上起不来 |
| T-2 | 扩展包装机与加速联动 | **装机完成；三项静态判定全过；运行期被两处阻断** | 扩展包已装且 `engine=clang`、versionCode 503=主包 503；但 `<queries>` 未声明扩展包（包可见性）＋ AVF `virtualizationservice` 未注册 |
| T-3 | distcc `COMPLETED@36` | **未达成（平台阻断）** | `budget_rejected ACCEL ACCELERATOR_UNAVAILABLE` → `lane_started QEMU`；pVM 车道在本机无可用入口 |
| T-4 | ACR 决策材料 | **完成** | `docs/research/ACR-draft-four-lanes-20261029.md`，291 行 → 已追加 §7 现场补充；另纠正两处数字口径 |
| W-D | stretch | **未做** | T-3 阻断后无意义（pVM 不可用 → 同机复测无从谈起） |

按开工 Prompt 的预设：T-3 的失败信号触发「ACR 材料如实降级为条件式」——**已执行**，见 §5。

---

## 2. T-1：offline 归因（根因与网络无关）

### 2.1 前提先被推翻

`reason=offline` 是**误读**。装配脚本不做任何网络探测；`offline` 只是 `fail_stage` 的合并标签
`offline-or-package-install-failed`（`guest-assets/scripts/orchestella-guest-provision.sh:509`），
脚本把 DNS/TLS/404/签名/本地错误一律折叠成同一个字符串。会话 22 的 `reason=offline …` 是对该行的截断。

### 2.2 真因

guest 内 pacman 原文（attempt=1 与 attempt=2 逐字一致，可稳定复现）：

```
:: Synchronizing package databases...
error: restricting filesystem access failed because Landlock is not supported by the kernel!
error: switching to sandbox user 'alpm' failed!
error: failed to synchronize all databases (failed to retrieve some files)
```

pacman 7 在同步数据库时降权到 `alpm` 沙箱用户并启用 Landlock；guest 内核不支持 Landlock：

```
# cat /sys/kernel/security/lsm
capability
```

只有 `capability`，无 `landlock` → pinned guest 内核未编入 `CONFIG_SECURITY_LANDLOCK`。
**pacman 从未发出任何一次 HTTP 请求**，所以与 gvproxy、DNS、targetSdk 域、SELinux 全都无关；28 域同样会遇到。

### 2.3 修复验证（guest 内就地，未改产品代码）

`/etc/pacman.conf` 追加 `[options]` + `DisableSandbox` 后：

```
:: Synchronizing package databases...
 core/extra 数据库下载完成，exit=success
```

### 2.4 网络健康的正面证据（一次性否掉「出网不通」）

| 探针 | 结果 |
|---|---|
| 默认路由 | `default via 192.168.127.1 dev eth0` |
| `ping -c1 192.168.127.1` | 0% 丢包，16.066 ms |
| `ping -c1 223.5.5.5` | 0% 丢包，20.522 ms |
| `nslookup mirrors.tuna.tsinghua.edu.cn 223.5.5.5` | CNAME + A 正常返回 |
| `nc 223.5.5.5 53` / `:80` | `tcp53: connected` / `tcp80: connected`（:80 返回 400） |
| `pacman -Sy` | 10.2 MiB @ 0.8–1.7 MB/s，`exit=success` |

**附带更正会话 22 一条经验**：gvproxy **会**转发 ICMP（网关与上游都 0% 丢包）。
`ping 192.168.127.254` 100% 丢包的原因是 hostIP 被映射成 `127.0.0.1` 后回包源地址被改写，与 TCP 无关。
「ping 254 不可作为判据」这个结论仍然成立，但理由要改。

### 2.5 未完成

完整 `pacman -Syu --noconfirm --needed <基线包>`（装配脚本的 `package-install` 终态）**未跑完**：
Arch 是滚动源，脚本用 `-Syu`（全量升级）而非定点安装，在 TCG 下 30 分钟只完成下载与部分解包，
为给 T-3 让出设备而中止。**状态如实记为未验证**；但「同一条调用链在 `DisableSandbox` 后能正常出网下载」已被 `pacman -Sy` 实证。

> 这本身是一条设计问题，建议下一轮评估：Arch 分支改用 `pacman -S --needed <packages>` 而非 `-Syu`，
> 并把 `DisableSandbox` 随 rootfs 预置（或在内核补 Landlock）。三个选项的取舍见证据文件 §3。

---

## 3. T-2：扩展包装机与加速联动

### 3.1 三项静态判定全过

| 判定 | 事实 | 结论 |
|---|---|---|
| applicationId = 主包 + `.accel` | 主包 `io.github.nyzeep.orchestella.qa.dvpvm`，扩展包 `…qa.dvpvm.accel` 已装 | ✔ |
| versionCode 锁 | 扩展 503 = 主包 503 | ✔ |
| engine 恰为 `clang` | 扩展包 `assets/PVM-COMPILER-MANIFEST`：`engine=clang`、`compiler_version=22.1.8`、`aarch64-unknown-linux-android`、`one_cpu`、`2048 MiB` | ✔ |

`AccelExtensionRules.evaluate(true, 503, 503, "clang")` → `Status.OK / 扩展包就绪`。

### 3.2 但运行期判定不成立，且是产品缺陷不是环境问题

设置页实测（新构建 `ACCELERATOR_LANE_ENABLED=true`）：

> **pVM 编译加速：未安装 pVM 加速扩展（点此查看安装引导）**

扩展包明明装着。根因：`AndroidManifest.xml:18-20` 的 `<queries>` **只声明了 Shizuku**。
包可见性过滤自 targetSdk ≥30 生效，`AccelExtensionResolver` 用
`getApplicationInfo(<主包名>+".accel")` 探测 → 在 36 域必然抛 `NameNotFoundException` → 恒判 `EXTENSION_MISSING`。

**这条只在升 36 后出现**（28 域构建不受过滤），是「升 36」的隐性代价，已写进 ACR 草案 §2.3 与 §7.2。

修复方向（本轮不实施）：把扩展包名按渠道注入 `<queries><package>`（`manifestPlaceholders`），
或改用一个能力探测（`<queries><intent>` / provider authority）绕开包名硬编码。

### 3.3 第二处阻断：AVF 服务不存在

`OrchestellaAccelerator: budget_rejected ACCEL ACCELERATOR_UNAVAILABLE` → 回落 QEMU。
`service list`（537 项）中**没有 `virtualizationservice`**，只有 `android.system.vmtethering` 与厂商
`vendor.qti.AvfQcvmManager`；设备虽声明 `feature:android.software.virtualization_framework`。
`pm grant MANAGE_VIRTUAL_MACHINE` 可执行且 `granted=true`，**不改变结论**——缺服务不是缺权限。

### 3.4 未达成项

「加速卡翻『已启用（车道可用）』+ 首页『加速 ON』徽章亮」**未达成**：两个阻断（包可见性、AVF 服务）任一都会让车道不可用。
截图已留：`accel-card-01.png`（pVM 卡）、`settings-01..06.png`（AVF 平台卡：`MANAGE_VIRTUAL_MACHINE` / `USE_CUSTOM_VIRTUAL_MACHINE` 未授权）。

---

## 4. T-3：distcc `COMPLETED@36` 未达成

**判据五道关一道都没到**——不是编译失败，是根本没进入 pVM 车道：

```
I OrchestellaAccelerator: budget_rejected ACCEL ACCELERATOR_UNAVAILABLE 0ms
I OrchestellaAccelerator: lane_started QEMU STARTED 183ms
```

即：QEMU 车道正常启动（36 域仍可用，与 T-1 的网络证据一致），但加速车道在预算账本阶段被拒
（`LaneBudgetLedger.reserve()` → `ACCELERATOR_UNAVAILABLE`），payload 从未装载。

已排除的两个假设（本轮实测）：
- **不是构建标志**：本轮已重编 `-Porchestella.acceleratorLane=true`（`ACCELERATOR_LANE_ENABLED=true` 已在产物 BuildConfig 确认）并装机，行为不变。
- **不是权限**：`MANAGE_VIRTUAL_MACHINE` 授权前后行为一致。

剩余唯一解释：本机 AVF 虚拟机管理服务未注册。`dv_pvm_crossfire.py` 依赖的生产
`DistccTcpVsockBridge` 需要一个活的 pVM 对端，故整链不可达。

**ACR 影响**：pVM 在 36 域的同机复测继续为**未验证**，且原因从「扩展包装不上」修正为「AVF 服务缺失」。

---

## 5. T-4：ACR 决策材料

产出 `docs/research/ACR-draft-four-lanes-20261029.md`（291 行 + 本轮追加 §7）。五节齐备：
四车道定位表、升 36 收益/代价（4 个选项）、UML 定位（编译撤回）、文案纪律（三词区分＋禁「硬件加速」）、撤销条件与回退路径。

### 5.1 草案纠正的两处数字（经原文核对，纠错成立）

| 项 | 开工材料写法 | 实际 |
|---|---|---|
| proot 单文件 | proot/bare 313 ms | **bare** 313 ms；**proot** 337 ms（`项目状况总结-20261027.md:51-52`） |
| pVM 对 QEMU | 11.1× | **11.7×**（32,750/2,809，同上 :54）；11.1× 是会话 14 已判作废的片段口径 |

### 5.2 本轮追加的三条现场结论（§7）

1. pVM 的阻断点是 AVF 服务缺失，不是扩展包装不上。
2. 升 36 的隐性代价：两包拆分在运行期因包可见性失效。
3. QEMU 车道状态上修：出网已验证；装配受 guest 内核无 Landlock 阻断，**可修**。

---

## 6. 治理与证据

- 未提交任何产品代码；唯一入库候选是 `docs/`（被 gitignore，需 `git add -f`）。**本轮未提交**——收尾验证轮不改仓库。
- 证据清单（`evidence/unlock36-20261029/round23/`）：
  - `console-0d0cac1d.log` / `console-attempt2-with-guest-provision-log.txt`（串口全量，含 guest 内装配日志 cat 输出）
  - `wt1-offline-root-cause.md`（T-1 取证记录）
  - `accel-card-01.png`、`settings-01..06.png`
  - `term-01.png`（guest 终端）

### 6.1 本轮踩到的设备操作坑（可复用）

1. **主包是 DEBUGGABLE**：`run-as` 可直读 `files/{instances,logs,root-volumes,runtime}`，**不必盲操作 UI**；`files/logs/console-<uuid>.log` 是宿主侧全量串口日志。
2. `adb exec-out screencap -p > x.png` 在 Git Bash 下会被 CRLF 转换毁掉 PNG（`0d 0d 0a`）；须 `screencap -p /data/local/tmp/x.png` + `adb pull`。
3. `/data/...` 开头的远端路径必须 `MSYS_NO_PATHCONV=1`，否则被改写成 Windows 路径。
4. `adb shell input text` 经 IME 会**丢首字符**（首块发哨兵再删即可），`-` 必须用 `keyevent 69`，**每条命令后要单独发 `keyevent 66`**（否则两条命令被拼成一行）。
5. `am start -n <applicationId>/.MainActivity` 会报「does not exist」——类名是 Java 包 `io.github.nyzeep.orchestella.MainActivity`，与 applicationId 不同。
6. 唤醒/解锁：`wm dismiss-keyguard` 无效，须用户手动解锁；锁屏时 `input` 事件进不了应用。

---

## 7. 未验证项（四态）

| 项 | 状态 | 原因 |
|---|---|---|
| `package-install` 终态成功 | 未验证 | 滚动 Arch 全量 `-Syu` 在 TCG 下超时风险高，为 T-3 让出设备而中止 |
| distcc `COMPLETED@36` | 未达成 | pVM 车道 `ACCELERATOR_UNAVAILABLE`（AVF 服务未注册） |
| 加速卡「车道可用」/ 首页徽章 | 未达成 | 包可见性缺陷 + AVF 服务缺失 |
| pVM 同机 36 域复测 | 未验证 | 同上；W-D 全部未做 |
| UML 长驻 30 min | 未验证 | 时间盒 |

---

## 8. 下一轮入口（按性价比排序）

1. **修包可见性**（`<queries>` 按渠道注入扩展包名）—— 一行 manifest + 一个占位符，修完才能验证「扩展包就绪」这条链路。
2. **确认 AVF 在本机到底能不能用**（服务未注册是 ROM 级还是可唤起）—— 决定 pVM 车道在当前设备上是否还存在；若不存，ACR 的 pVM 行要按「设备面不可用」重写。
3. **Arch 装配改造**：`-Syu` → `-S --needed`；`DisableSandbox` 随 rootfs 预置。
4. 补 ACR 的 `未取数`：Play targetSdk 时间线、28 域失效时间线。
