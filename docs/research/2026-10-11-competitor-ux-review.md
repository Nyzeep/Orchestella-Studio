# 竞品 UX 与工程实践调研（2026-10-11）

供 Phase 6 UI/UX 与稳定性优化参考。调研对象：DroidSpaces、proot-distro（Termux）、Android 官方 Terminal。
与 2026-10-06《virtualization-ux-and-competitors》《terminal-aesthetics-and-mobile-ergonomics-research》互补；本篇聚焦本轮真机缺陷（发行版下载 404、provisioning 变砖、会话死亡刷屏）相关的工程实践。

## DroidSpaces（ravindu644/Droidspaces-OSS，GPL-3.0，2.3k stars）

定位差异：root-required 的 LXC-like 容器方案（内核 namespace + 静态 musl 二进制），与 Orchestella 的无 Root VM 双引擎路线不同；只借鉴工程与 UX 实践，不借鉴实现。

可借鉴点：
1. **自恢复三件套**：陈旧 PID 文件清理 + 孤儿进程扫描 + 以 /run 元数据为准的配置恢复。Orchestella 已有 `reconcileAfterHostDeath`，本轮补齐的会话死亡回调 + 自动 reconcile 与之同向；可再进一步：启动时扫描"半装配根盘"（已由 initramfs 自愈覆盖）。
2. **应用内启动日志查看器**：boot 失败时用户能看日志定位。Orchestella 终端即控制台（已覆盖），后续可为 provisioning 失败单独提供"查看装配日志"入口。
3. **实时需求检查器**：设置页内可视化内核能力检测结果。Orchestella 的能力矩阵已有数据层，可在设置页增加"能力自检"页。
4. **容器内用户选择器**（终端进入时选用户）：Orchestella 已有 defaultUser 元数据，可作为后续小功能（终端抽屉加"切换用户"）。

来源：https://github.com/ravindu644/Droidspaces-OSS

## proot-distro（termux/proot-distro）

- 每发行版插件文件中以 `TARBALL_URL` / `TARBALL_SHA256` 关联数组登记，下载后强制 SHA256 校验。
- **无自动多镜像回退**：URL 失效时需用户用 `PD_OVERRIDE_TARBALL_URL/SHA256` 环境变量手动覆盖或改插件文件——这正是 Orchestella 用户实际遇到的痛点在竞品中的形态。
- 结论：Orchestella 的 ResilientDownloadEngine（多镜像 failover + 断点续传 + 流式校验）在同类中已是领先设计；本轮把 7 个 pinned 资产改为"GitHub 自托管 immutable release + 国内镜像双链"后，目录失效面进一步收窄（release 资产不可变，永不下架）。

来源：https://github.com/termux/proot-distro

## Android 官方 Terminal（Android 16 Canary）

- 官方 Debian VM + 图形应用支持，验证了"VM 内完整发行版 + 图形"是平台方向（Orchestella 路线正确）。
- 来源：https://securityonline.info （Android Now Runs Full Graphical Linux Apps 报道）

## 本轮已落地（对应竞品痛点）

| 竞品痛点 | Orchestella 本轮对策 |
| --- | --- |
| proot-distro URL 失效需手动 override | distro-catalog-v1 自托管 release + TUNA/lxc-images 镜像链，5/7 发行版永不下架 |
| DroidSpaces 依赖 root 才能自恢复 | 无 Root 前提下：initramfs 标记缺失自愈 + 会话死亡自动 reconcile |
| 会话死亡后用户看到死终端 | 死会话自动切离线卡片 + 一键重启 |
| pinned 哈希全为占位假值 | 7/7 发行版真实 SHA256（官方 CHECKSUM 双重验证） |
