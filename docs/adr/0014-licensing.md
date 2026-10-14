---
status: amended
date: 2026-09-05
amended: 2026-10-14
---

# 许可证策略：GNU GPL v3.0 or later (GPL-3.0-or-later) + 强开源保障立场

1. **项目许可证：GNU General Public License v3.0 or later (GPL-3.0-or-later)**——建立强 Copyleft 开源护城河，防止闭源 fork 与商业专有化包装；同时保障移动设备用户的反 Tivo 自由与专利安全。
2. **零代码复制立场（Original-Implementation Stance）**：保持架构参考与自研实现，对 GPL 参考项目（Podroid、Droidspaces、Termux 等）坚持零未经授权代码复制；核心组件与契约代码自研，确保清晰合规的著作权链。
3. **GPL 义务流程化**：随 App 分发的 GPL 二进制（kernel、QEMU——ADR-0005 的 CI 自建产物）由 CI 生成"对应源码清单"。公开发布的默认交付路径为：已确认公开的项目 Git 仓库中的固定版本 tag，加与该 tag 一一对应的 Release 源码包；该包必须含实际分发二进制的完整对应源码、补丁、配置、控制编译/安装的脚本、LICENSE/NOTICE 与校验记录。每次组件升级或发布都原子更新该记录、tag 和源码包。
4. **分发边界与依赖兼容性**：
   - App 代码与自研运行时遵循 GPL-3.0-or-later；
   - Android 官方依赖（Jetpack、Material 3、Kotlin 库）遵循 Apache-2.0，依据 FSF 官方结论与 GPLv3 第 7 条款，Apache-2.0 代码与 GPLv3 **完全单向兼容**，可合法编译打包为 GPLv3 二进制（解决 GPLv2 与 Apache-2.0 不兼容的问题）；
   - 随包 GPL 二进制（kernel/QEMU）通过独立程序/虚拟化边界隔离，履行对应源码义务；
   - guest rootfs 官方 tarball 义务在上游发行版渠道。

## Considered Options & Transition Analysis (2026-10-14 修订)

- **GPL-3.0-or-later（采纳，最优方案）**：
  - **强 Copyleft 保护**：彻底阻断第三方仅打包换皮即转闭源商业化的路径，确保社区改进必须回馈；
  - **依赖库兼容**：完全兼容 Android 框架主流的 Apache-2.0 依赖库，免除合规死锁；
  - **反 Tivo 化条款 (Section 6)**：防止硬件厂商锁定设备禁止用户运行修改后的虚拟机；
  - **显式专利许可 (Section 11)**：保护贡献者与终端用户免受专利纠纷威胁；
  - **"or later" 弹性**：规避未来 GNU 许可证版本升级带来的割裂风险。
- **GPL-2.0（否决）**：与 Android 核心依赖普遍采用的 Apache-2.0 存在**法定不兼容**（FSF/ASF 均明确认定），强行采用将导致合规死锁；且缺乏反 Tivo 化和专利防御条款。
- **LGPL-3.0（否决）**：弱 Copyleft 允许专有软件直接动态链接或套壳打包，无法起到保护 Orchestella 整体终端工作站应用的目的。
- **AGPL-3.0（否决）**：面向云端/网络服务交互场景（第 13 条网络触发），Orchestella 是运行于本地物理手机的 Android 客户端 APK，非云服务；且 AGPL 在移动开发与开源应用商店生态中易引入不必要的合规摩擦。
- **Apache-2.0（原方案，退役）**：原为 OEM 商业合作预留，但缺乏 Copyleft 保护，存在项目被第三方恶意闭源侵占的实质风险。首次正式发布前为唯一调整窗口，经用户确认正式切换至 GPL-3.0-or-later。

## Consequences

- 项目源码仓库顶层 `LICENSE` 全面更新为 GNU GPL v3.0 官方文本。
- 项目 README、应用内关于页（`activity_about.xml`）与对外声明全部对齐为 GPL-3.0-or-later。
- 确保持续保持与 Android 生态依赖的许可证合规性，CI 与发布流程中既定的 QEMU/Kernel 对应源码交付门禁（`QEMU-GPL-SOURCES.md`、Release source bundles）继续严格执行。
