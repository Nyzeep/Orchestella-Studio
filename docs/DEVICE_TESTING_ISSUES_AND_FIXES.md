# Orchestella Studio 真机全量验收问题与缺陷排查修复报告

本文档全面记录在真实 Android 物理实体设备（通过 ADB 调试连接）环境测试中发现的全部阻塞性问题、致命异常与体验缺陷，以及对应的根本原因分析（Root Cause Analysis）与修复实施方案。

---

## 1. 缺陷总览矩阵

| 序号 | 缺陷分类 | 问题现象 | 严重程度 | 触发阶段 | 修复状态 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **BUG-01** | 权限与启动 | 应用首次安装启动直接闪退 (Crash on Launch) | 致命 (P0) | 首次启动 / SetupWizard | ✅ 已根治 |
| **BUG-02** | 终端与初始化 | Alpine Linux 终端画布被无限狂刷报错，无法正常输入 | 严重 (P1) | 启动 Alpine 实例进入终端 | ✅ 已根治 |
| **BUG-03** | UI / 硬件向导 | 稀疏向导自定义内存滑块在非 256MB 整数倍设备上报错 | 次要 (P2) | 向导第 2 步自定义硬件配置 | ✅ 已根治 |
| **BUG-04** | 凭据管理 | 极简主卡片重构后，用户无法便捷查看默认主机名与密码 | 体验 (P3) | 实例主卡片 / 详情控制台 | ✅ 已补齐 |

---

## 2. 详细问题分析与修复方案

### BUG-01: 首次安装启动直接闪退 (Crash on Launch)

- **问题现象**：
  在 Android 12+ 实体设备安装从 Release 下载的 APK 后，首次点击图标启动应用瞬间发生 Crash 退出。
- **ADB 崩溃抓取与根因分析**：
  在 `SetupWizardActivity.kt` 中，为了向用户展示当前物理设备的网络拓扑，调用了 `ConnectivityManager.getLinkProperties` 与 `activeNetwork`。但在 `AndroidManifest.xml` 中未显式声明 `android.permission.ACCESS_NETWORK_STATE` 权限。
  在 Android 12+ 强权限收紧环境下，系统安全沙箱抛出 `SecurityException: Missing ACCESS_NETWORK_STATE permission`，导致未捕获异常并进程崩溃退出。
- **修复措施**：
  1. 在 `app/src/main/AndroidManifest.xml` 中显式补全 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`；
  2. 在 `SetupWizardActivity.kt` 的网络检测逻辑增加多重保护性 `try-catch` 降级回退机制；
  3. 新增 `BootstrapTest.manifestDeclaresAccessNetworkStatePermission` 回归测试，杜绝权限遗漏。

---

### BUG-02: Alpine Linux 终端画布被无限狂刷报错

- **问题现象**：
  在真机上启动 Alpine Linux 实例并进入原生终端后，控制台持续被狂刷报错：
  ```text
  can't run '/usr/sbin/orchestella-proxy-relay': No such file or directory
  ```
  终端输出被完全淹没，软键盘输入的内容被疯狂顶起，导致终端几乎不可用。
- **根因分析 (深入分析)**：
  1. **解释器缺失 (ENOENT)**：打包阶段 `app/build.gradle.kts` 将 `guest-assets/scripts/orchestella-proxy-relay.pl` 复制为 `proxy_relay` 打入系统，其首行为 `#!/usr/bin/perl`。Alpine minirootfs 是极简系统，内置 BusyBox，**默认并未安装 perl**。当 Linux 内核执行该脚本时，尝试加载解释器 `/usr/bin/perl` 失败，内核返回 `ENOENT`（No such file or directory）。
  2. **Inittab 死循环 Respawn**：在 `guest-assets/initramfs/init` 的 Alpine 回退初始化模板中，声明了 `::respawn:/usr/sbin/orchestella-proxy-relay`。BusyBox `init` 每次检测到进程失败退出，就会无延迟以极高速率重新拉起它，并将失败日志狂倾至 `/dev/console`（即终端画布连接的串口控制台）。
- **修复措施 (三重深度防御体系)**：
  1. **移除 Inittab 无条件 Respawn**：在 `guest-assets/initramfs/init` 的 Alpine `inittab` 模板中彻底移除 `::respawn:/usr/sbin/orchestella-proxy-relay`，防止 init 陷入死循环；
  2. **Polyglot 兼容 Shebang 预检**：在 `guest-assets/scripts/orchestella-proxy-relay.pl` 头部增加标准 POSIX `/bin/sh` 引导前导段，先通过 `command -v perl` 检测 perl 是否存在，若不存在则静默退出 0，彻底根除 shebang 导致的 `ENOENT` 报错；
  3. **存量环境热修清理**：在 `init` 脚本中增加启动热修逻辑，当已有实例磁盘重新启动（`DROIDLAYER_ROOT_VOLUME_RESTART`）时，自动执行 `sed -i '/orchestella-proxy-relay/d' /newroot/etc/inittab`，确保已创建的实例无需重新刷盘即可自动治愈。

---

### BUG-03: 稀疏向导自定义内存滑块跨步对齐异常

- **问题现象**：
  在部分 RAM 规格特殊的物理设备（例如总 RAM 不是整 256MB 的机型）进入创建向导第 2 步时，Material `Slider` 抛出 `IllegalArgumentException: Value must be a multiple of stepSize` 异常。
- **根因分析**：
  向导初始化计算最大可用内存时直接采用 `profile.totalRamMb() - 2048`，未按照 `stepSize = 256f` 进行离散量化步长对齐，导致 `sliderRam.valueTo` 与 `sliderRam.value` 不符合离散约束。
- **修复措施**：
  在 `SetupWizardActivity.kt` 中重构步长计算公式，使用 `(rawMaxRam - 512) / 256` 向上求整步数后再反推 `maxRam = 512 + ramSteps * 256`，严格确保基准点与每一步均整步对齐。

---

### BUG-04: 极简主卡片重构后凭据查询体验问题

- **问题现象**：
  工单 04 对主界面进行了极简化重构，移除了老式卡片中的复杂配置展开项。用户需要方便地查阅工单 03 规定的默认主机名（`orchestella`）与超级用户凭据（`root / 1234`）。
- **优化措施**：
  1. **主界面卡片长按触发**：在 `MainActivity.kt` 中为实例主卡片添加长按手势（`setOnLongClickListener`），呼出操作面板并展示实例凭据弹窗；
  2. **二级控制台快捷点击**：在 `InstanceDetailActivity.kt` 中为顶部 `instanceIdText` 绑定点击事件，一键弹出包含主机名、默认用户与密码的弹窗，并提供一键复制密码功能。

---

## 3. 回归测试与验证闭环

- **自动化单元测试**：
  - `BootstrapTest.manifestDeclaresAccessNetworkStatePermission`: 验证 Manifest 权限声明；
  - `BootstrapTest.alpineInittabDoesNotContainProxyRelayRespawn`: 验证 init 模板与存量清理；
  - `BootstrapTest.proxyRelayHasSafeShellPreamble`: 验证代理脚本的安全引导头。
- **真机 ADB 实机验证**：
  - 启动 Alpine 终端后，控制台清爽整洁，无任何 proxy-relay 报错污染；
  - 软键盘打字与 Dracula 抽屉切换顺畅自然，7 大截图凭证完整存留于 `temp/`。
