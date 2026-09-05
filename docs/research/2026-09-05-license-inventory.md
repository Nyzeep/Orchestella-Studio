# 许可证与再分发义务盘点（研究票 R4 结论）

> 日期：2026-09-05。方法：主线程串行检索（GitHub 官方仓库、发行版打包元数据、SPDX 记录交叉验证）。
> 结论状态：关键组件**已核实**；个别第三方库标注"实施期核对"。
> 本文档是 wayfinder 决策票「许可证与再分发义务盘点」的 Answer 存档，服务于 ADR-0014 的落地。

## 1. 我们作为分发者的三个独立分发单元（ADR-0014 边界的落实）

| 分发单元 | 组成 | 许可证 | 义务 |
| --- | --- | --- | --- |
| App APK | 本项目代码（Apache-2.0）+ AndroidX/Compose（Apache-2.0）+ 我们构建的 GPL 二进制 | 混合，见下 | 逐组件列明 |
| guest kernel Image | 我们 CI 构建的 Linux kernel | **GPLv2** | 对应源码（精确版本+config）+ 完整构建脚本，随包或书面 offer |
| guest rootfs | 上游官方 tarball（Alpine/Debian） | 各包各异（GPL/LGPL/BSD/MIT…） | **分发义务在上游渠道**（我们不改包、不再分发改制品）；manifest 记录上游 URL 与版本 |

## 2. 组件级核实结果

| 组件 | 许可证 | 证据 | 对我们的影响 |
| --- | --- | --- | --- |
| **crosvm** | **BSD-3-Clause**（此前存疑，已核实） | github.com/google/crosvm 官方声明 + Repology/Fedora 元数据 | ✅ 无传染；且 AVF 路径用的是设备自带的 crosvm，我们根本不分发它 |
| **gfxstream** | **Apache-2.0**（部分 vendored 文件 MIT / MIT-Khronos-old） | AOSP Android.bp SPDX 标识 + Fedora/CentOS/Arch/Debian 打包元数据一致 | ✅ M4 路线（若启用）无许可证障碍；guest 侧 Mesa 驱动为 MIT |
| **QEMU** | GPLv2+（我们自建） | QEMU 官方 | 对应源码义务（同 kernel） |
| **libslirp** | BSD-3 | QEMU 栈既有核实 | 仅归属声明 |
| **glib / pixman / libusb / pcre2 / libffi**（QEMU 静态/动态依赖） | LGPL-2.1+ / MIT / LGPL-2.1+ / BSD / MIT-like | 上游声明 | 动态或静态链接均可行；NOTICE 列明；LGPL 组件提供源码链接 |
| **HiddenApiBypass** | **LGPL-3.0** | Podroid 使用并声明（其 CREDITS/build.gradle） | ⚠️ 谨慎：优先用纯反射 + 逐设备 fallback 实现 AVF 反射；确需引入时按 LGPL 以独立库边界提供 + 再链接声明。**实施期决策，默认不引入** |
| **Termux terminal-emulator** | GPL 系（随 termux-app） | 既有核实 | ❌ 禁止 vendored（ADR-0014）；替代候选：ConnectBot 系终端库（Apache-2.0）或自研——实施期选型 |
| **Shizuku API** | Apache-2.0（宽泛共识） | Shizuku 仓库 | 实施期最终核对后引入 |
| **AndroidX / Compose / DataStore / Hilt** | Apache-2.0 | 官方 | 无负担 |
| **Alpine / Debian 官方 rootfs** | 各包许可随上游 | 上游官方渠道 | 我们只下载不改包：义务锚定上游；若未来改为"打包进 APK"则义务转移给我们——ADR-0006 已定为下载，保持 |

## 3. 我们需要履行的 GPL 义务清单（CI 流程化，服务 ADR-0014 第 3 条）

1. kernel 与 QEMU 每次构建：记录精确源码版本（tag/commit）+ kernel .config + 构建脚本仓库链接。
2. APK 内附第三方许可页（Apache/BSD/MIT 归属 + LGPL 源码链接 + GPLv2 书面 offer 文本）。
3. 清单由 CI 生成（license-report 任务），人工 review 后随版本发布。
4. 版本升级时清单自动更新，漏更 = 合规瑕疵（ADR-0014 已定调）。

## 4. 边界重申

- guest 内用户后续自行安装的 GPL 软件属用户行为，与我们无关。
- 我们不修改、不重分发任何上游 rootfs 包——ADR-0006 的"官方 tarball 直下"模式在此获得许可证层面的额外正名。
- 未发现任何组件与 Apache-2.0 项目许可证冲突；亦未发现任何需要重开已关闭 ADR 的新证据。
