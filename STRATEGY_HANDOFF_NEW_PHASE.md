# Orchestella Studio 战略演进与总控交接入口（Strategy Handoff · Phase 5.0 / v0.1.0-preview）

**更新时间**：2026-10-10  
**移交方**：Orchestella Studio 核心全栈研发席（已完成 Phase 5.0 发版冲刺、工单 01~09 全量合并、真机验收缺陷 BUG-01~04 歼灭闭环与工作区全量净化）  
**接管方**：本地研发会话（Local Orchestrator Agent）  
**性质**：**全局战略交接索引、当前工程基线与新阶段推进指南**。

---

## 1. 核心事实锚（Current Baseline）

1. **代码主仓**：`https://github.com/Nyzeep/Orchestella-Studio`（分支：`main`，工作区目录：`Orchestella_dev/`）
2. **最新版本**：[`v0.1.0-preview`](https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.1.0-preview) (`versionCode = 5`, `versionName = "0.5.0-alpha"` 基线升级)
3. **最新提交记录**：`7a72126 docs(evidence): 提交真机功能测验与问题修复截图凭证及排障报告`
4. **CI 与测试门禁状态**：
   - 本地 contract 检查、单元测试、`:app:testDebugUnitTest` 100% 通过（80 个单元测试类，458+ 单测全绿）；
   - 真机 ADB 实测完成并归档四大缺陷修复报告（`docs/DEVICE_TESTING_ISSUES_AND_FIXES.md`）。
5. **本地开发虚拟驱动盘映射**：
   - `subst S: "N:\Orchestella开发目录\Orchestella_dev\.toolchains\android-sdk"`（Android SDK 纯 ASCII 隔离盘）
   - `subst W: "N:\Orchestella开发目录\Orchestella_dev"`（工程主目录纯 ASCII 隔离盘）
   - JDK 环境：OpenJDK 17（`$env:JAVA_HOME = "N:\OpenJDK17"`）
6. **权威换机交接指引原件**：
   - [`docs/agent-handoff/INTERIM_HANDOFF_DISTRO_OVERHAUL_AND_ENV_MIGRATION.zh-CN.md`](docs/agent-handoff/INTERIM_HANDOFF_DISTRO_OVERHAUL_AND_ENV_MIGRATION.zh-CN.md)

---

## 2. 近期关键功能交付一览（Milestones Delivered）

- **工单 01（Setup Wizard）**：VMware 级四步快速创建向导，包含发行版选择、稀疏虚拟磁盘预检、NAT网络及国内高速源配置、硬件与用户凭据配置全流程。
- **工单 02（Multi-Distro Catalog）**：支持 7 大主流官方发行版（Ubuntu 24.04, Debian 12, Arch Linux ARM, Fedora 41, openSUSE Tumbleweed, Kali Linux, Alpine 3.20）与 5 大包管理器抽象（APT, APK, PACMAN, DNF, ZYPPER）。
- **工单 03（Credentials & Hostname）**：固化 `orchestella` 主机名与 `root / 1234` 默认凭据，并在首启 initramfs 阶段完成注入。
- **工单 04（Instance Detail Console）**：主界面极简化卡片重构，并落地全屏二级详情控制台（五大功能分区，关机硬件热调锁定保护）。
- **工单 05（Power & Snapshots）**：外部 ACPI 优雅关机（30s 守卫）、强制断电、挂起与恢复唤醒（`SUSPENDED` 状态），以及 COW 差分快照创建与秒级一键回滚。
- **工单 06、07、08、09（Terminal Overhaul）**：直接键入式原生交互终端、软键盘平滑联动、后台 PTY 会话保持与连接池（杜绝 dead session）、右侧滑出抽屉管理（最多 16 个并发终端）、全屏沉浸适配与安全边距。
- **真机验收修复（BUG-01~04）**：补充 `ACCESS_NETWORK_STATE` 权限修复首启崩溃；移除 inittab 对 proxy-relay 的死循环 respawn 并增加安全 POSIX 脚本引导头；修正向导内存滑块步长对齐算法；补齐实例凭据快捷弹窗与一键复制。

---

## 3. 下一步研发重点路线图（Next Phase Priorities）

1. **Wayland 图形桌面体验闭环**：
   - 完善基于 `labwc` / `weston` 的 Guest Wayland 合成器与宿主 `DesktopActivity` 上屏渲染；
   - 优化触摸屏与触控板手势映射、虚拟鼠标指针平滑移动。
2. **客机网络通道与本地端口转发增强**：
   - 强化用户态 SLIRP/NAT 适配；
   - 完善常用服务（SSH, Web, VNC）的一键映射。
3. **架构深化与持续质量防线**：
   - 遵循深模块设计原则与 TDD 规范；
   - 保持工作区整洁与纯净，持续护航发布流水线。
