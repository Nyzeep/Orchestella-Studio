# 图标来源与许可证（Oe3）

本文件只覆盖 `ui/oe3/icons/**` 与 `res/drawable/oe3_*.xml` 里的图标。Oe v2 的
`ic_oe_*` 与旧 `ic_distro_*` 不在此列。

## 1. 发行版品牌图标

- 上游：**simple-icons 16.38.0**（npm 包 `simple-icons@16.38.0`）
- 上游仓库：<https://github.com/simple-icons/simple-icons/tree/16.38.0>
- 包许可证：**CC0 1.0 Universal**，<https://creativecommons.org/publicdomain/zero/1.0/>
- 取用文件：`icons/<slug>.svg` 的 `<path d="…">`
- 修改说明：删除 `fill` 颜色，路径数据原样保留；同一份 `d` 同时生成 Compose
  `ImageVector` 与 Android `VectorDrawable`。不重绘、不拆分、不改图形结构；渲染时由
  调用方 tint 成单色。

> **重要**：simple-icons 的 **包**是 CC0，但**不是每个图标都单独声明为 CC0**。
> 其 `DISCLAIMER.md` 明确说明包许可证不代表所有图标；逐图标许可记录在
> `data/simple-icons.json`。下表按该文件逐个列出，**不要**把整张表当成 CC0。
> 没有单独许可记录的图标，按包许可证 CC0-1.0 使用。

| Slug | 名称 | 许可证 | 上游来源 / 备注 |
|---|---|---|---|
| ubuntu | Ubuntu | CC0-1.0 | <https://design.ubuntu.com/resources>；Ubuntu 是 Canonical 的商标 |
| debian | Debian | LGPL-3.0-or-later | <https://www.debian.org/logos/>；simple-icons 记录为 CC-BY-SA-3.0，但 Debian 官方同时提供 LGPL-3.0-or-later 选项，本仓库选择后者以满足与 GPL-3.0 代码库的兼容性 |
| fedora | Fedora | CC0-1.0 | Oe3 自绘中性 "F" 标记，非上游图形；仅用于指明发行版 |
| archlinux | Arch Linux | CC0-1.0 | <https://www.archlinux.org/art>；Arch Linux 是商标 |
| alpinelinux | Alpine Linux | CC0-1.0 | <https://alpinelinux.org> |
| kalilinux | Kali Linux | CC0-1.0 | <https://www.kali.org/docs/policy/trademark/> |
| opensuse | openSUSE | CC0-1.0 | <https://en.opensuse.org/Portal:Artwork>；openSUSE 是商标 |
| almalinux | AlmaLinux | CC0-1.0 | <https://almalinux.org>；AlmaLinux 是商标 |
| rockylinux | Rocky Linux | CC-BY-SA-4.0 | <https://github.com/rocky-linux/branding>；Rocky Linux 是商标 |
| linux | Linux | CC0-1.0 | <https://www.linuxfoundation.org/the-linux-mark/>；Tux 吉祥物由 Larry Ewing 创作，按原作者署名要求致谢 |

### 许可证影响范围

- `debian` 的单色派生图形（`Oe3BrandPathsRestricted.kt` 中的 `Oe3DebianPath` 与
  `oe3_distro_debian.xml`）适用 LGPL-3.0-or-later；`rockylinux` 的对应文件适用
  CC-BY-SA-4.0（其与 GPLv3 为单向兼容）。应用其余部分不是这些图形的派生作品。
- `fedora` 是 Oe3 自绘的中性字母标记，未使用 Fedora 官方图形；Fedora 名称是商标，仅作指明性使用。
- 其余图标适用 CC0-1.0，无署名义务；下表来源仍保留以便追溯。

## 2. 商标声明

Ubuntu、Debian、Fedora、Arch Linux、Alpine Linux、Kali Linux、openSUSE、AlmaLinux、
Rocky Linux、Linux 及其图形是各自项目或基金会的商标。本项目仅在**指明所运行的发行版**
时使用这些图形，不改图形结构、不使用品牌色、不暗示任何项目对本应用的背书或合作。
商标权不在 CC0 的放弃范围内；如品牌方要求变更或移除，删除对应条目与文件即可。

## 3. Oe3 线性图标

`Oe3Icons` 中的导航与动作图标（工作站、实例、硬件、发行版、配置、终端、电源、更多、创建、
导入、SSH、主题、关于、更新）为 Oe3 手绘 24 单位路径，不取自 simple-icons、Oe v2 或任何
第三方图标集，也不含第三方 emoji 字体或 GPL 项目的原创美术资源；随本仓库一同授权。
