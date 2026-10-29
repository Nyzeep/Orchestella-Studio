# Orchestella Studio

<div align="center">

<img src="docs/assets/branding/app-icon-512.png" width="128" alt="Orchestella Studio 应用图标" />

**无需 Root 的 Android Linux 虚拟机与移动工作站**<br />
*Native Linux VM Runtime & Mobile Workstation for Android — no root required*

[![Release](https://img.shields.io/badge/Release-v0.6.0%20stable--prep-blueviolet.svg?style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green.svg?style=flat-square)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-ARM64--v8a-purple.svg?style=flat-square)](#)
[![CI](https://img.shields.io/github/actions/workflow/status/Nyzeep/Orchestella-Studio/ci.yml?branch=main&label=CI&style=flat-square)](https://github.com/Nyzeep/Orchestella-Studio/actions)

**简体中文** · [English](README.md)

</div>

---

## 项目定位

Orchestella Studio 在 Android 应用沙盒内提供 Linux 运行能力，不需要 Root、不修改系统分区、不依赖解锁 Bootloader。同一套终端、网络与实例管理界面之下有四条执行车道（lane），它们的能力边界并不相同：只有 QEMU 车道能给出“完整内核 + systemd + 七种发行版”的完整语义，其余三条各有明确的适用区间与限制。

本文所有性能数字、状态标注与许可信息都能指到仓库内的文件。凡是仓库里取不到数的，一律写「未取数」或「未验证」，不做估算。

## 开发预览声明

> **0.6.0 是“稳定版准备”里程碑，不是“已经完全稳定”的声明。** 本轮把发布形态收敛为双包、把四条车道的定位与数字口径写成可核对的形态；但它仍然保留未验证范围，且部分车道的产品化入口尚未打通。

本轮之后仍然成立的事实：

- **真实 AVF Guest 成功样本仍为 0**。当前测试设备声明了 `feature:android.software.virtualization_framework`，但系统服务列表里没有 `virtualizationservice`，pVM 车道在决策层被拒后回落 QEMU。
- **36 域 pVM 同机复测未验证**；**UML 仅到“可以启动、可以跑单文件编译”**；**16 KB 页真机运行未验证**。
- **生产签名与实例数据迁移仍是公开发布的门**，仓库不包含任何签名密钥。

公开发布物以 [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 页面为准；源码合入 `main` 不等于已发布，也不代表全部工单完成。

## 四车道能力矩阵

| 车道 | 执行机制（一句话） | 典型用途 | 实测每文件编译耗时 | 当前状态 |
| :--- | :--- | :--- | :--- | :--- |
| **proot / bare** | 宿主 ARM64 指令**原生执行**，`ptrace` 拦截并重写 syscall；guest 无内核 | 低内存设备上的轻量 shell、包管理与兼容环境 | bare **313 ms**（7 文件 p50）<br />proot **337 ms**（p50） | **条件式**：28 域已验证；36 域不可用 |
| **pVM**（protected Microdroid payload） | 平台信任链接受的 Microdroid GKI 跑在 EL2 **硬件 vCPU** 上 | 把 distcc 编译卸载进隔离 VM | **2,809 ms**（单 job 端到端，n=1 复用） | **条件式**：28 域链路已验证；36 域受本机 AVF 服务缺失阻断（非打包/权限问题），同机复测未验证 |
| **QEMU**（TCG） | guest 指令经软件**翻译执行** | 完整内核、systemd、SSH、七种官方发行版 | **32,750 ms**（单文件全编，含预处理，n=1 复用） | **条件式**：36 域执行阻塞已实证解除、guest 出网已验证；装配受 guest 内核无 Landlock 阻断（可修）；guest 内编译未验证 |
| **UML**（`ARCH=um`） | 真 Linux 内核作为普通 App 进程运行，guest 指令**原生执行**、二进制由 guest 内核 ELF loader 装载 | 研究线预期区间：长驻用户态 / 容器 / Docker | **940 ms**（2,000 函数夹具中位；同轮 native 460 ms，即 **2.04×**） | **未验证**：启动与单文件 `-c` 编译已验证；长驻、链接、Docker 未验证 |

### 怎么读这些数字

来源统一为 [`docs/research/ACR-draft-four-lanes-20261029.md`](docs/research/ACR-draft-four-lanes-20261029.md) 的 §1.1 与 §1.4，本文不做取整、不换说法。

- **313 / 337 / 2,809 / 32,750 ms 来自同一轮（会话 16），夹具是 92 KB 的 `.i` 预处理文件。** 其中 QEMU 一列是“单文件全编、含预处理”，pVM 一列是“distcc 卸载的单个 job 端到端”，两者与 bare/proot 的逐文件口径并不等价。
- **940 / 460 ms 来自另一轮（会话 22），夹具是 90,045 B 的 `.c`、命令 `clang -c -O1`，工具链与上一轮不同**（Alpine clang 22.1.3 musl 静态 vs payload clang 22.1.8）。
- **跨会话数字不可横比。** 2.04× 只能与同一轮的 460 ms 配对使用，不能除以 313 ms 或 2,809 ms。任何“UML 比某车道快/慢 N 倍”的说法目前都缺同一轮共夹具数据 —— `未取数`。
- 同夹具的四列同轮数据、QEMU 车道跑 `dockerd` 的结论、UML 长驻/链接/Docker 的任何数字，均为 `未取数`。

### 文案纪律

产品文案按**执行机制**分三档，不用笼统的“加速”描述执行策略：

| 术语 | 定义 | 适用车道 |
| :--- | :--- | :--- |
| **原生指令执行** | guest 二进制就是宿主 CPU 的指令，没有翻译层；但没有 EL2 边界，syscall 可能被 `ptrace` 拦截或落到 guest 内核 | proot / bare、UML |
| **硬件 vCPU** | 由 EL2 hypervisor 提供的虚拟 CPU，guest 内核在该 vCPU 上执行 | pVM |
| **翻译执行（TCG）** | 指令逐条经软件翻译后才执行 | QEMU |

区分标准不是“谁在跑指令”，而是“guest 内核跑在谁的 CPU 上、有没有 EL2 边界”。早期版本对外传播过的 pVM 倍数已在后续会话作废（见 ACR 草案 §1.2 注），本项目不再引用任何跨轮拼出来的倍数。

## 安装

### 双包形态

0.6.0 起采用双包发布：

- **主包** `io.github.nyzeep.orchestella` —— 应用本体与 QEMU 完整内核路径。
- **pVM 加速扩展包** `io.github.nyzeep.orchestella.accel` —— pVM payload、编译器引擎与 `PVM-COMPILER-MANIFEST` 都在这里。主包不带 pVM 资产。

两条硬规则：

1. **扩展包的 `versionCode` 必须与主包完全一致。** 不一致时运行期直接拒绝并提示“扩展包版本与主包不一致”，不做尽力加载 —— 版本不匹配的扩展包描述的是一份本构建从未验证过的 payload（`app/src/main/java/io/github/nyzeep/orchestella/AccelExtensionRules.kt:13`、`:71`）。
2. **只装主包等于只有 QEMU 车道。** 完整内核、systemd、SSH、发行版目录都能用；pVM 相关入口会显示“未安装 pVM 加速扩展包”。

升级时请把两个包一起升到同一版本，不要只升其中一个。

### HyperOS / 小米系安装坑

- **新装应用需要屏幕上的确认**；锁屏状态下 ADB 安装必然失败（`INSTALL_FAILED_USER_RESTRICTED`）。装包前请解锁并保持亮屏。
- **已存在包的更新不受这条限制。** 所以不要为了“干净安装”先卸载 —— 卸载会带走应用私有目录里的实例。
- `settings put global adb_install_need_confirm 0` 对 HyperOS 无效，仍会被拦；真正的闸门是“USB 安装”授权加亮屏确认。

### 授予 pVM 权限

```bash
adb shell pm grant io.github.nyzeep.orchestella android.permission.MANAGE_VIRTUAL_MACHINE
```

授权返回 `granted=true` **不等于** pVM 车道可用：设备还必须注册 `virtualizationservice`。当前测试机没有注册该服务，授权后仍然回落 QEMU —— 缺的是服务，不是权限。

### Shizuku

应用接入 Shizuku 普通客户端，用于区分连接、拒绝、进程死亡、重连与超时几种状态。**客户端授权不等于 AVF Guest 已运行**，也不等于当前设备具备可用的 AVF 服务。

## 从源码构建

最短可用路径（JDK 17、Android SDK Platform 36、NDK 27.0.12077973、CMake 3.22+）：

```bash
git clone https://github.com/Nyzeep/Orchestella-Studio.git
cd Orchestella-Studio
./gradlew :app:assembleDebug
```

构建通道由 Gradle 属性选择，默认 `release`：

```bash
# 内部开发通道：applicationId 加 .dev 后缀，versionName 带 -dev.<shortSha>
./gradlew :app:assembleDebug -Porchestella.buildChannel=dev

# 隔离 QA 通道：额外要求 buildPurpose，只接受小写字母与数字
./gradlew :app:assembleDebug -Porchestella.buildChannel=qa -Porchestella.buildPurpose=input
```

- 内部通道包与发布包使用不同 `applicationId`，可以并存，不会覆盖用户数据；内部通道一律用 debug 证书。
- 发布构建在缺少任一签名属性时由 `validateReleaseSigning` 直接失败，不会产出“看起来像正式版”的包。
- **payload / 扩展包属性属于内部构建**：`:accel-extension` 没有无 payload 变体，构建它需要预先准备好的输入目录（`-Porchestella.fastlane.payloadLibDir`、`-Porchestella.pvmCompiler.libDir`）。这些不是用户安装步骤，仓库也不附带这些二进制。
- CI 的 Gradle 门禁跑全部 `:runtime:*:check` 与 `:app:testDebugUnitTest`；另有独立的 lint + assemble + 16 KB 打包门禁、用固定样例（placeholder `.so`）构建 `:accel-extension` 的双包体积对照，以及 Python 工具脚本检查。

## Roadmap

1. **0.6.0 稳定版（本轮）** —— 双包发布形态、四车道能力矩阵与数字口径定稿；补齐生产签名、实例数据迁移与发布清单。
2. **36 域 pVM 闭环（下一设备轮）** —— 平台侧执行阻塞已在 36 域实证解除；扩展包探测的包可见性声明已在 0.6.0 源码修复（`<queries>` 按通道注入 `<applicationId>.accel`），剩余是扩展包的安装策略、装包后 `Resolver` 翻「已安装」的同机复测，以及 distcc 在 36 域跑出 `COMPLETED`。
3. **UML 研究线（长驻 / 容器 / Docker）** —— 编译角色**已被实测否定**：2,000 函数夹具上 UML 中位 940 ms、native 中位 460 ms，**2.04×**，落在 1.5–3× 区间，劣于 `≤1.5×` 的编译判据，因此不进编译车道。价值区间改为长驻用户态与容器负载，但 30 分钟长驻稳定性、链接阶段与 Docker 三项都还没做。
4. **AVF 自定义内核（评估，不实现）** —— 仅对 non-protected 设备有意义：pvmfw 只启动 Google/OEM 签名镜像，`Available OS list: ["microdroid"]`，protected 下无法跑自定义内核。本轮只做可行性评估，不产生实现承诺。

## 已知限制

- **proot 车道需要 28 域包。** 该车道只存在于 `-Porchestella.qaTargetSdk=28` 的 QA/侧载通道；36 域只能执行 `nativeLibraryDir` 下的 `.so`，无法在可写目录里 exec 发行版二进制。
- **真实 AVF Guest 成功样本仍为 0**（[AGENTS.md](AGENTS.md)、[文档索引](docs/README.md)）。
- **扩展包探测的包可见性修复尚未真机复测**：0.6.0 在 `AndroidManifest.xml` 的 `<queries>` 中按通道声明了 `<applicationId>.accel`（QA 变体为 `…qa.<purpose>.accel`），未声明时 targetSdk ≥ 30 的包可见性过滤会让 `AccelExtensionResolver` 对已安装的扩展包恒判「未安装」。修复已进源码并通过契约单测，装包后的实际判定待下一设备轮验证。
- **UML 仅为研究线**：`-c` 之外（调 `ld`/`lld`）、systemd、Docker、SMP、16K 宿主 + 4K guest 均未验证。
- **16 KB 页**：ELF / APK 对齐已通过校验，真机运行未测。
- **签名**：仓库不含密钥；v0.4.5 与 0.5.3 验证包均使用 Android Debug 证书（`docs/standards/VERSIONING.md` §4）。0.6.0 的发布证书以 Release 页面与发布清单记录为准，未记录前请按未签收处理。

## 许可证与致谢

项目主体采用 **GNU General Public License v3.0 or later（GPL-3.0-or-later）**，全文见 [LICENSE](LICENSE)。

| 组件 | 许可 | 形态与备注 |
| :--- | :--- | :--- |
| QEMU 11.1.1 及静态链接库 | GPL-2.0（QEMU 本体）；glib LGPL-2.1+、pixman MIT、zlib、pcre2 BSD-3-Clause、libffi MIT、libfdt GPL-2.0+/BSD-2-Clause 双许可、libiconv LGPL-2.1、proxy-libintl LGPL-2.1+ | 随包分发；对应源码记录见 `app/src/main/assets/QEMU-GPL-SOURCES.md` 与 `tools/qemu-android/gpl/`（[NOTICE.QEMU](app/src/main/assets/NOTICE.QEMU)） |
| Guest 控制 Agent | GPL-3.0-or-later（本项目自有代码），静态链接 musl 1.2.6-r2 | 随包分发；见 [NOTICE.GUEST-RUNTIME](app/src/main/assets/NOTICE.GUEST-RUNTIME) |
| BusyBox 1.36.1-r31（Alpine 3.20 `busybox-static`） | GPL-2.0-only | 随 Fast Lane 最小工具箱分发；必须同时提供对应源码或三年有效书面要约（[NOTICE-fastlane.md](NOTICE-fastlane.md)） |
| distcc 3.4（`distccd`） | GPL-2.0-or-later | **不随 APK 分发**；作为独立进程运行，GPL 边界在进程边界（[NOTICE-fastlane.md](NOTICE-fastlane.md)） |
| gvproxy / gvisor-tap-vsock | Apache-2.0 | 用户态网络层 |
| clang / LLVM | Apache-2.0 WITH LLVM-exception | 未随包分发、未固定二进制 |
| PRoot | GPL-2.0 | 兼容类别的研究与实现参考，不随当前包分发 |
| UML 内核（下游 `zalexdev/linux-um-arm64`，base 7.2-rc4） | GPL-2.0 | 二进制被 `.gitignore` 排除、未入库、未随 0.6.0 分发；若分发需按 GPL-2.0 独立提供对应源码，交付边界形态尚待裁定 |

各发行版 Guest Rootfs 遵循上游发行版的许可。许可证一致性由 `tools/release/check_licenses.py` 校验，发布包必须包含 `tools/release/release_bundle_manifest.json` 列出的全部文件。

## 历史版本

v0.4.5 是上一个公开发布的开发预览版（Android Debug 证书签名），其发行说明与安装包仍在 Releases 中归档。旧版本的下载入口不再作为主推路径：0.6.0 的关键变化（双包形态、四车道矩阵）在 v0.4.5 上并不存在，混用两代的安装方式会得到错误结论。

## 文档与反馈

- [中文成果与开发文档索引](docs/README.md)：当前状态、研究依据、工单与归档。
- [四车道决策材料（ACR 草案）](docs/research/ACR-draft-four-lanes-20261029.md)：本文所有性能数字与四态标注的权威来源。
- [版本与构建通道标准](docs/standards/VERSIONING.md)：版本号、`versionCode` 与三个通道的硬约束。
- 问题反馈：[Issue](https://github.com/Nyzeep/Orchestella-Studio/issues/new)；使用交流：[Discussions](https://github.com/Nyzeep/Orchestella-Studio/discussions)。提交前请删除密码、令牌、SSH 私钥与个人文件内容，并附上应用版本、设备型号、Android 版本、发行版与复现步骤。
