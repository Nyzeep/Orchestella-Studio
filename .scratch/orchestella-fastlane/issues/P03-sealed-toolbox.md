# P03: Sealed Toolbox 打包（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
Priority: P0（Fast Lane 产品形态）
Owner: toolbox/build owner
Blocked by: None（零设备）；真机由 DV-02 验证
External gates: 许可证审查；APK 体积预算

## 目标

把 Linux 工具链放进 APK 根 `bin/`，利用 zipfuse 对 `bin/` 的 execute 默认位：

- 候选 A：Termux bootstrap（Bionic/`$PREFIX`）；
- 候选 B：静态 busybox/git/python/clang；
- manifest：name/version/SHA256/license/source/target；
- 不放入 `lib/` 依赖 exec 位；payload 仍在 `lib/<ABI>`。

## 零设备范围

- 构建/打包/zip 检查；不运行 Android；
- 工具版本/SHA/许可证清单；
- APK 注入任务在签名前；`zipalign -P 16` 检查；
- 不把 GPL 工具/补丁复制而不履行义务。

## 行为验收

- [ ] APK 根存在 `bin/` 条目；权限/大小/SHA 与 manifest 一致。
- [ ] `bin/` 条目在签名前注入；APK 签名/安装包结构/16KB 对齐通过。
- [ ] Termux bootstrap（若选 A）的符号链接/绝对路径在 `bin/` 中可用，或已 flat 化并记录差异。
- [ ] 静态 busybox（若选 B）可执行；`sh -c 'echo FASTLANE_OK'` 在 host 侧模拟/或 DV-02 真机验证。
- [ ] manifest 逐项许可证/SHA/来源；NOTICE/SBOM 生成。
- [ ] APK 体积增量有预算（v1 建议 ≤80MB 起步）。

## 联网调研

- Termux `TermuxInstaller` bootstrap 布局、`$PREFIX`、`termux-exec`；
- AOSP `guest/zipfuse/src/inode.rs` 的 `bin/` `EXECUTABLE_FILE_MODE`；
- AGP 打包 `bin/` 的 artifact transform / 签名前注入；
- Termux packages 的 `distcc`/`clang`/`gcc` 构建脚本；
- GPL/LGPL/Apache 许可证义务。

## 交付

- `fastlane-toolbox/manifest.json` + `bin/` 内容/构建脚本；
- Gradle 注入任务 + zip 检查测试；
- 许可证/NOTICE/SBOM 草稿；
- 体积报告。

## 停止条件

- 无法在签名前注入 `bin/`：改用可验证的替代打包方案；不得签名后改包。
- Termux bootstrap 符号链接/路径不可用：降级静态工具，记录证据。
- APK 体积不可接受：裁剪工具，另开体积决策票。

## 设备交接

DV-02：从 `/mnt/apk/bin` 执行 shell/编译器；数据写 encrypted storage。

## 实施进展（2026-10-21，Agent C，零设备）

- 选型：候选 B（Alpine 3.20 `busybox-static` 1.36.1-r31，aarch64，静态
  musl，64 KiB PT_LOAD 对齐）。候选 A 的 Termux bootstrap 依赖绝对
  `/data/data/com.termux/files/usr` 路径与 `SYMLINKS.txt`，零设备无法证明
  可重定位，v1 不采用。
- 产物：APK 根 `bin/`，`busybox` + `sh` 副本 + 48 个 applet wrapper；运行
  时 manifest 位于 `/mnt/apk/bin/manifest.json`。
- 实测：2,234,863 字节 / 50 条目；`verify-toolbox` 通过；2 个 ELF 均
  16 KB 对齐；合成 APK 注入/校验通过（51 个 `bin/` 文件）。
- 构建/门禁：`tools/fastlane-build/fastlane_build.py`；Python 29 tests OK。
- 未验证：DV-02 设备执行、AGP 真实 APK、真实 `zipalign`；编译器未随 v1
  分发（`distcc-clang` profile 为 `not-tested`）。
- 报告：`docs/reports/tickets/fastlane-P03/implementation-report.zh-CN.md`
