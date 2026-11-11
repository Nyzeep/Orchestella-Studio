# NOTICE — UML 车道补充（草稿，ACR 未决，不入包）

- 状态：**DRAFT**。本文件是 `app/src/main/assets/NOTICE.QEMU` 的 UML 车道补充草稿，
  供 UML payload 产品化轮（ACR 双方案：方案 A 内置 / 方案 B 下载）评审用；
  不覆盖、不修改现有 `NOTICE.QEMU`。方案 A 或 B 在 ACR 通过并真正开关之前，
  以下任何条目都**不得**视为已交付。
- 依据：`docs/research/2026-11-12-uml-productization-payload-research.zh-CN.md` §2.4
  （UML 版 NOTICE 需补 10 条清单）；pin 值全部来自
  `research/uml-fastboot/manifest.md` §3（2026-11-10 U1 ARM64 UP 首建）。
- 许可框架：内核为 GPL-2.0 修改版分发，交付形态遵循 `docs/adr/0014-licensing.md`
  （公开固定 tag + 一一对应 Release 源码包）；本文件只登记 UML 件的差异条目。

## 待补条目（R2 §2.4 清单的落稿）

1. **UML 内核本体**：fork `Raymer8639/linux-um-arm64`，tag `um-arm64-v0.3.0`
   （Linux 7.2.0-rc4，commit `169b17ff3f66d507cb137b770ceb1fd9f4547a32`）；
   源码 tarball sha256 `08578a494412125dfc6b8f203e984b68d20e313173319d6a6c115e2875e0116c`。
   产物 pin（UP）：`linux-fast` `21e1c3f9…4389`、`linux-fast-noinitrd` `a5efce2b…17fb`、
   `stub_exe` `87666f91…a09a12`、`initramfs.cpio.gz` `7523c9a2…8381`
   （完整值见 `UmlKernelAssets.FastProfile.PIN_MANIFEST`，运行时与构建期共用该单源）。
   **open**：wave2 设备窗实测 `linux-fast`（内置 initramfs）跨构建路径未复现 pin
   （`572c3433…`，大小一致、差异隔离于内嵌 initramfs 段）；ACR 须裁定产品 pin
   采用哪条构建路径，并在发布前公布与二进制一一对应的完整 digest。
2. **配置交付**：`research/uml-fastboot/defconfig-fast` + `defconfig-fast.fragment`
   （pinned `linux-bionic.config` 经 olddefconfig 派生）与最终 `kernel.config` /
   `kernel-noinitrd.config`（sha256 见 manifest §3）。out-of-tree 配置 fragment 是
   GPLv2 §2 "控制编译的脚本" 的一部分，必须随 Release 源码包交付。
3. **构建入口与环境**：`research/uml-fastboot/build-fast.sh`；NDK r29
   （zip sha256 `4abbbcdc…08ecf`）；构建容器 Ubuntu 24.04 + GCC 13.3.0；
   `KBUILD_BUILD_TIMESTAMP`/`SOURCE_DATE_EPOCH` 固定；同路径两次干净重建逐字节一致
   （跨路径一致性见条目 1 的 open 项）。
4. **stub_exe 许可归属**：本项目自研（16 KB 重链配方，与
   `research/uml-kernel-build/release-assets/v0.3.0/stub_exe-16k` 逐字节一致），
   许可随项目根 LICENSE（GPL-3.0-or-later）；无 GPL 交付义务，但 NOTICE 须声明归属。
   **open**：最终归属裁定属许可负责人（R2 报告边界声明）。
5. **initramfs 内容**：busybox-static 1.37.0-r20（Alpine 3.22，GPL-2.0；
   aarch64 apk `ee469aee…34da2` / binary `a6bb1e6e…004ff`；x86_64 apk
   `488ad6ef…c74b`）；`initramfs/init`、`um-command.sh` 为本项目脚本；
   内嵌解压器仅 gzip。
6. **方案 B 下载件同等义务**：下载的 rootfs（`alpine-minirootfs-3.22.2-aarch64.tar.gz`
   sha256 `6bf49190…ea489`）与内核件走同一 NOTICE 记录；运行时只校验固化 pin
   （`UmlKernelAssets.RootfsDownload`，常量时间比较、fail-closed），不在设备跑 GPG。
   分发义务在首次下载分发时触发，但 NOTICE/要约指引必须先随 APK。
7. **书面要约**：以 `guest-assets/SOURCE-OFFER.txt` 为模板出 UML 件版本
   （≥3 年有效、不超过物理分发成本、请求通道、`BUILD-INPUTS` 识别码）；
   即使走公开 tag（§3a）也建议随包保留 §3b 要约作兜底。
8. **Release 门禁衔接**：UML 内核升级 = 新固定 tag + 新 Release 源码包
   （含补丁/配置 fragment/构建脚本/LICENSE/NOTICE/校验记录）一次原子变更，
   遵循 ADR-0014；`UmlKernelAssets.FastProfile.PIN_MANIFEST` 与 Release 资产
   必须同轮更新。
9. **rootfs 许可清单**：Alpine minirootfs 上游包按 apk db 自带许可清单转述；
   任何注入文件（init/ready 脚本等）使整树成为项目修改版，须随交付
   （`research/uml-rootfs-prebake/verify.sh` 的 treeSha256 可直接引用）。
10. **质量记录不混入 NOTICE**：16 KB ELF 检查（`ELF_16KB_OK all_targets_pass`）、
    跨路径复现性 open 项、设备功能矩阵等属交付质量证据，记入研究报告，
    不写入随包 NOTICE。

## 方案 A/B 的 NOTICE 差异（一句话）

义务本体相同（§2+§3 随分发触发）；差别只在时点：方案 A 要求发布前 tag+源码包
就绪（ADR-0014 门禁前置），方案 B 可在 app 发布后、资产首次分发前补齐，
但 NOTICE/manifest 指引/要约必须先随 APK。
