# Android 16 官方 Terminal 应用作为参考实现（研究票 R3 结论）

> 日期：2026-09-05。方法：受本机并发额度限制，子代理两次被中止，改由主线程串行检索完成（AOSP 文档 + googlesource 源码索引 + Mishaal Rahman/Android Authority 技术报道 + 社区实践）。
> 结论状态：架构级事实**已核实**（多源交叉）；个别实现细节标注 UNCERTAIN。
> 本文档是 wayfinder 决策票「Android 16 Terminal 参考实现」的 Answer 存档。

## 1. 定位与权限

- 应用包名 `com.android.virtualization.terminal`，**位于 AVF Mainline 模块仓库内**（platform/packages/modules/Virtualization），是随模块发布的**特权组件**，非普通第三方 APK（googlesource 源码 diff 实证：VM_TERMINAL intent + LAUNCHER category）。
- 作为模块内平台签名应用，天然持有 `MANAGE_VIRTUAL_MACHINE`（signature 级对其可用）并使用 custom VM 能力——这正是第三方需要 adb/Shizuku 才能获得的权限。**结论：官方 Terminal 的部分能力属于"系统应用特权"**。
- 注意区分：`platform/packages/apps/Terminal` 是老的独立终端模拟器（libvterm 时代），与 Linux Terminal 无关。

## 2. VM 配置与镜像分发

- AVF 官方文档（usecases）原文：OS image 为"基于上游 Debian 略作修改的镜像"，**由 Terminal 应用从 Google 服务器下载**（`dl.google.com/android/ferrochrome/...`，社区指南实证该 URL 可直接 wget）。
- 镜像为 custom VM 形态（自定义 kernel/initrd/rootfs 的 pKVM 非保护 VM），非 Microdroid 载荷。
- **对第三方的可复用性**：同一镜像与机制可被第三方应用使用——lfdevs 博客与 nixos-avf 项目、koiTerminal（fork 支持自定义 VM 镜像）均为第三方应用引导同一/自定义镜像的活例（前提：adb/Shizuku 授权）。**这直接校准了我们的 L2 路线：不是复刻特权地位，而是"官方能力的可授权子集 + 自有镜像分发"。**
- 存储：Android 16 允许把整块手机存储分配给 VM、QPR1 起动态分配；QPR2 起 Terminal 可访问几乎全部共享存储（此前仅 Downloads）——**这些是特权应用能力**，第三方按我们的白名单制（只读共享 Downloads）走自己的路，语义不同不跟进。

## 3. 网络

- crosvm 侧为 virtio-net + TAP 型后端（AVF custom_vm 文档模式一致）；宿主侧网络桥接细节属系统内部实现，公开资料未完整披露（UNCERTAIN）。
- 已知官方特性：Android 16 让 Terminal VM 获得完整网络能力；VM 可经 vsock 建立与宿主的 ADB 连接（custom_vm.md 记载）。
- **对第三方的启示**：与我们 ADR-0008 的"机制各异、语义一致"框架吻合——官方证明 TAP 类全网络能力在 pKVM VM 上可行，我们的能力矩阵把"custom VM 网络"标记为"平台可行、第三方待实测"（原型票 17 清单项）。

## 4. 文件共享与集成

- QPR2 的"几乎全部共享存储访问"走特权路径（UNCERTAIN：具体机制疑为宿主侧挂载转发至 VM）。
- 终端交互：官方应用内多标签终端会话（Tabbed multitasking 为 M2 阶段特性）。
- 对第三方：我们的 9p 数据通道 + 只读共享 Downloads（ADR-0009）与官方能力是**不同授权等级的同类功能**——验证了功能形态合理，但规模（整盘访问）不可比。

## 5. 图形/GPU（对本项目最关键的信息）

- 架构链路（多源一致）：**guest 内 Wayland 合成器 → virtio-gpu → gfxstream 把 OpenGL/Vulkan 调用转发给 Android 宿主 GPU**；宿主与 VM 共享同一 GPU（非直通）。
- 开发期通过放置 `virglrenderer` 标记文件启用 GPU 渲染（Linux Journal 实测记录）；已落地 Pixel 10，"限制较多"（Gadget Hacks）；XDA 社区实测其他设备多为回退软渲染（llvmpipe/lavapipe）。
- **对第三方的启示**：gfxstream 是平台组件（crosvm 内建），非系统应用独占的 API——但**第三方 custom VM 能否实际拿到 virtio-gpu/gfxstream 设备仍无一手证据**（我们的 ADR-0012 M4 前置判断保持不变：原型证实后才启动 M4）。官方路线是 Wayland-first，与我们 M3 的 Wayland 评估排序一致——官方实践提高了 M3 的优先级可信度。

## 6. 生命周期

- VM 随应用/系统管理（AVF 托管）；公开资料未详述后台策略细节（UNCERTAIN）。设备要求：AVF/pKVM 支持设备，"非 Pixel 上基本不可用"（HN 社区评估，与我们的设备矩阵研究一致）。

## 7. 能力对照表

| 能力 | 官方实现 | 第三方可复用性 | 证据 |
| --- | --- | --- | --- |
| custom VM（自定义 kernel/rootfs） | 模块内特权 + platform 签名权限 | ✅ 经 adb/Shizuku 授权（nixos-avf/lfdevs/koiTerminal 实证） | googlesource + 社区项目 |
| Debian 镜像分发 | Google 服务器托管（ferrochrome） | ✅ 模式可复制（我们用官方 tarball + provisioning，ADR-0006） | dl.google.com URL + AVF usecases |
| 整盘存储分配/全共享存储访问 | 特权机制 | ❌ 不可比（我们走白名单制：只读 Downloads） | Android Authority QPR2 报道 |
| virtio-net + TAP 网络 | 平台组件 | ⚠️ 平台可行、第三方实测待验证（原型票 17） | custom_vm.md + Podroid setNetworkSupported |
| virtio-gpu + gfxstream | 平台组件（crosvm 内建） | ⚠️ 无一手第三方证据；M4 前置判断不变 | Mishaal Rahman / Linux Journal / Gadget Hacks |
| vsock 宿主↔VM 通道 | 平台 API（connectVsock） | ✅ 公开 API 能力范围内 | framework-virtualization README |
| 开机自启/特权生命周期 | 系统应用特权 | ❌（我们走 FGS + 冷恢复，ADR-0011） | 架构推断（模块性质） |

## 8. 对本项目的启示（供 spec 引用，不改任何已关闭决策）

1. **官方验证了我们的产品形态**（下载镜像 → custom VM → 终端 → 渐进 GUI），包括 Wayland-first 的图形排序。
2. **第三方的能力天花板 = 平台能力 − 系统特权**；官方应用是"特权上限"的参照物，不是"我们做不了"的证据——nixos-avf/koiTerminal 证明可授权子集足够跑通同类产品。
3. gfxstream 链路是平台组件，M4 的可行性窗口存在，但仍以原型票实测为准（ADR-0012 不变）。
4. 官方存储能力（整盘/全共享存储）属于特权域，我们不跟进也不必跟进——白名单制的差异化定位反而更安全（ADR-0010）。
