# P11: Fast Lane 构建、CI 与许可证（零设备）

Type: implementation
Status: ready-for-agent（zero-device）
Priority: P0（可交付基础）
Owner: build/release owner
Blocked by: None（零设备）；最终 APK 由 DV-01/02 验证
External gates: 许可证/法务审查

## 目标

让 Fast Lane 的产物可重复构建、可检查、可分发：

- NDK/CMake 构建 payload `.so`；
- APK 根 `bin/` 注入（签名前）；
- 16KB ELF/APK 对齐；
- Termux/静态工具/dstcc 的许可证/NOTICE/SBOM；
- CI 零设备门禁：`:runtime:engine-fastlane:check`、payload build、toolbox zip check、16KB、license；
- 不把设备任务放进 PR 门。

## 零设备范围

- 全部在 CI/宿主完成；无 adb/设备；
- 构建产物/zip 检查/许可证清单；
- 不发布 APK/Release。

## 行为验收

- [ ] payload `.so` 构建成功；arm64-v8a；16KB LOAD 对齐；符号检查。
- [ ] APK `bin/` 注入在签名前；`zipalign -P 16` 通过；签名/安装结构有效。
- [ ] CI 故意失败（payload 符号缺失/bin 缺失/16KB 失败/license 缺失）能阻塞。
- [ ] NOTICE/SBOM 覆盖 Termux/clang/distcc/PRoot（若使用）/gvproxy（若使用）。
- [ ] 不把 GPL 代码/二进制混入而不履行对应源码义务。
- [ ] 版本/通道遵守 `docs/standards/VERSIONING.md`；不自动递增/发布。
- [ ] 构建可复现：固定 NDK/CMake/工具版本；输入 SHA。

## 联网调研

- AGP artifact transform/签名前注入；
- Android 16KB ELF/APK 要求；
- Termux packages 的许可证字段；
- distcc GPL-2.0、clang Apache-2.0-with-LLVM-exception、Termux GPLv3、gvproxy Apache-2.0；
- 本仓 `.github/workflows/ci.yml`/`release.yml`。

## 交付

- `fastlane-payload` CMake/NDK build；
- `bin/` 注入 Gradle task；
- CI jobs + 失败测试；
- NOTICE/SBOM 草稿；
- 构建说明。

## 停止条件

- 无法签名前注入：改用合法替代；不得签名后改包。
- 许可证不清：不合并/不发布该工具。
- CI 无法在无设备环境跑：拆分 host-only 门，设备门留给 DV。
