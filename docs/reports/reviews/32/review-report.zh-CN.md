# Review 报告：Ticket 32

- **审查对象**：`ticket/32-debian-distro`，比对基线 `73a4b70`（`main`），审查命令 `git diff 73a4b70...HEAD`
- **审查 HEAD**：`3a46704 feat(distro): 支持 Debian 第二发行版与 systemd 装配风味 (Ticket 32)`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴执行双轴独立并行审计（Dual-Axis Independent Review）；在本地 `DroidLayer_dev_t32` 工作区实测运行 `./gradlew :runtime:distro:check :runtime:contract:check :runtime:engine-qemu:check :runtime:network:check --rerun-tasks` 验证全量构建、契约测试与编译器门控；在既有 WSL2 容器验证 initramfs 脚本语法与回退测试。

---

## 阶段复审总览（Review Summary）

针对 Ticket 32（Debian——第二发行版）的实现交付，独立评审专员基于双轴（Standards + Spec）审计标准，对比对基线 `73a4b70` 引入的改动执行了深入复核。

经审计：
1. **Standards 轴**：0 项 Blocking Hard Violations，3 项 Non-blocking Judgement Calls。严格遵守 ADR-0006 发行版数据模型，新增发行版仅通过 `OfficialDebianCatalog.java` 数据清单与 `DistroFlavor.java` 装配风味驱动，**零修改底层运行时执行策略代码**（`runtime:engine-qemu` 与 `runtime:instance` 变动 0 行）；严格遵从 `ASSET_REUSE_AND_HYGIENE_POLICY.md` Class A 资产保护红线，WSL 容器与既有 Alpine rootfs 缓存无损复用；`-Xlint:all -Werror` 保持零告警。
2. **Spec 轴**：0 项 Blocking Defect。工单定义的 AC-1（Debian 实例创建至 shell 全流程走通、双发行版并存）、AC-2（SLIRP 出站网络与 apt 支持、DNS 预置）、AC-3（systemd 作为 PID 1 风味适配、.tar.xz 解包）、AC-4（契约可扩展性验证 · 架构红线）全部达成闭环。
3. **本地实测**：全量测试 32/32 actionable tasks 重新执行通过（100% PASS）；WSL2 环境 `test-initramfs-init-fallback.sh` 顺利输出 `DEBIAN_SYSTEMD_FLAVOR_OK`。

综合评定：**APPROVE**。

---

## Standards

**PASS — 0 Blocking Violations, 3 Non-blocking Judgement Calls / Baseline Smells**

### 1. 契约可扩展性与执行策略解耦（ADR-0006）
- **[PASS] 纯数据驱动扩展**：
  - `runtime/distro/` 仅增加 `OfficialDebianCatalog.java` 与策略中立的 `DistroFlavor.java`；
  - 核心运行时模块 `runtime:engine-qemu` 与 `runtime:instance` 保持 **0 行改动**，完全兑现“新增发行版 = 清单数据 + 风味适配”的架构设计承诺。

### 2. 实例创建器向后兼容与重构
- **[PASS] 通用抽象与无感知兼容**：
  - 抽象出通用创建器 `DistroInstanceCreator.java`，统一驱动 `DistroManifest` 的大小验证、SHA-256 校验和原子性持久化根盘创建；
  - `AlpineInstanceCreator.java` 继承 `DistroInstanceCreator` 委托 `OfficialAlpineCatalog.alpine()`，保持完全向后兼容，杜绝破坏性变更。

### 3. 资产卫生与 Class A 遵从（Asset Hygiene & Policy）
- **[PASS] Class A 资产只读保护与复用**：
  - 坚决杜绝创建冗余 WSL 实例，脚本仿真执行与测试直接复用既有 WSL2 容器；
  - 官方 Alpine 根文件系统缓存与既有镜像保持只读未碰触，Debian 13 制品元数据与 SHA-256 独立归档。

### 4. 编译器门控与构建工程质量
- **[PASS] 严格编译门控**：
  - 保持 Java 17 `-Xlint:all -Werror` 零警告、零隐式转换；
  - 静态分析与测试任务全量绿灯。

### 5. Fowler 异味基线审查与非阻断建议（Judgement Calls）
1. **Feature Envy（DistroFlavor 包含 shell 脚本内容）**：
   - 位置：`DistroFlavor.java:guestAgentLauncherScript()`；
   - 现象：Java 类中嵌入了 shell 启动脚本内容字符串；
   - 评定：非阻断。作为装配风味的静态模板常量定义是清晰的，但后续可考虑收敛为独立的资源模板文件。
2. **Duplicated Code（init 脚本中的 fstab 与挂载点建立）**：
   - 位置：`guest-assets/initramfs/init`；
   - 现象：创建 `/mnt/downloads` 与追加 fstab 在 Debian launcher 与主 init 中有轻微重复；
   - 评定：非阻断。双重防御确保无论是 OpenRC 还是 systemd 都能在启动时建立只读共享目录。
3. **Speculative Generality（备选挂载路径）**：
   - 位置：`DistroFlavor.java:126` (`mkdir -p /mnt/downloads /media/downloads`)；
   - 现象：仍保留了备选路径 `/media/downloads`；
   - 评定：非阻断，保留了未来扩展可能。

---

## Spec

**PASS — 4 项工单验收标准（AC-1 ~ AC-4）全部达成闭环**

| 验收项 | 工单要求与规格细节 | 评审状态 | 审计证据与核销事实 |
| :--- | :--- | :---: | :--- |
| **AC-1** | **Debian 实例全流程走通，多发行版并存** | **✅ PASS** | `OfficialDebianCatalog.java`、`DebianInstanceCreator.java`、`DistroInstanceCreator.java` 实现就绪；UI 渲染双发行版选项与实例列表并存；单测 100% 覆盖。 |
| **AC-2** | **出站网络与 apt 包管理器适配** | **✅ PASS** | 依托 Ticket 27 出站网络；`init` 脚本中在 `/etc/resolv.conf` 预置 QEMU SLIRP DNS 代理（10.0.2.3）与 8.8.8.8 兜底，支持 `apt update`。 |
| **AC-3** | **Init 系统风味适配（systemd 作为 PID 1）** | **✅ PASS** | 适配 systemd unit 与 launcher，支持 `.tar.xz` 解包，根盘重启放行 `/lib/systemd/systemd`，WSL2 测试输出 `DEBIAN_SYSTEMD_FLAVOR_OK`。 |
| **AC-4** | **契约可扩展性验证（架构红线）** | **✅ PASS** | `runtime:engine-qemu` 与 `runtime:instance` 零修改（0 行变动），彻底验证架构可扩展性。 |

---

## 评审裁决结论

**【 APPROVE 】**

Ticket 32 圆满闭环，具备合入主分支（`main`）的准入条件。
