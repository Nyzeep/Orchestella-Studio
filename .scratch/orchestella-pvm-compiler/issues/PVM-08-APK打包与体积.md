# PVM-08: APK 打包与体积（多 .so、16 KB 对齐）

- Type: build/packaging
- Status: implemented（PR #24 已合并 2026-10-24；真实 .so 复验亦全绿，见 Comments 第二条）
- Priority: P1（Wave 2）
- Owner: build owner
- Blocked by: PVM-04（真实 .so 复验部分）
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

VM Payload API 明确支持 payload 由 APK `lib/` 下的**一个或多个** `.so` 组成，C++ 运行时可以静态链接，也可以作为独立 `.so` 随包分发。这给了我们两种打包方式：把 libLLVM 全静态链进 payload（单文件、体积大、加载慢），或拆成 `payload.so` + `libLLVM.so`（可懒加载、可共享）。

同时仓库对 16 KB 页设备有既有要求（`zipalign -P 16`、ELF LOAD 对齐），打包必须一起过。

## 目标

- 在 `lib/arm64-v8a/` 下同时放 `libfastlane_payload.so` 与编译器 `.so`，linker 能解析（不靠 `dlopen` 绝对路径）。
- `android:extractNativeLibs` / `useLegacyPackaging` 的取值有明确结论与理由（写进报告，不要「试出来的」）。
- 16 KB 对齐检查通过；APK 体积增量有预算（建议 ≤ 150 MB 起步，超了回 PVM-04 裁剪）。
- 加载顺序与失败可诊断：缺库 → `PAYLOAD_LOAD_FAILED`，并带缺失 soname。

## 任务

- [ ] Gradle 配置：`jniLibs` 包含新 `.so`；确认签名前注入；不破坏现有 `bin/` 布局。
- [ ] CMake/NDK：`-Wl,-z,max-page-size=16384`；检查 `readelf -lW` 的 LOAD 对齐。
- [ ] 体积报告：`tools/pvm-compiler/report_apk_size.py`（分项：payload、compiler、其他）。
- [ ] 打包检查：`zipalign -c -P 16 -v 4 app.apk`、`apkanalyzer files list`（或等价）断言 `.so` 存在。
- [ ] 失败诊断：payload 启动时若编译器库缺失，返回固定码而不是崩溃。

## 验收命令

```bash
./gradlew :app:assembleDebug --console=plain
python tools/pvm-compiler/report_apk_size.py --apk app/build/outputs/apk/debug/app-debug.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

## 边界

- 不发布、不改版本号、不改 applicationId/签名身份。
- 不把 `.so` 放进 `assets/`（Microdroid 从 `lib/` 取）。
- 不为了体积删掉许可证/NOTICE。

## 失败信号

- 需要 `dlopen("/data/app/.../libLLVM.so")` 绝对路径才能加载 → 说明打包位置错了，回 Gradle 配置。
- 16 KB 对齐只能在 4 KB 设备上「碰巧通过」→ 必须用工具断言，不看设备。
- APK 增量 > 预算且 PVM-04 已无可裁剪项 → 上报维护者，不静默接受。

## 证据

- 体积报告 + 对齐检查输出 + `.so` 清单（名字与 SHA-256，不含路径）。

## Comments

### 2026-10-24 会话 3：打包机制落地 + 占位链路全绿

**分支** `feat/pvm-08-apk-packaging`（4 commits，b9d3ccd → 523e417），敏感门绿。**PR #24**（不合并，等维护者）。

- **Gradle 接线**：新增可选属性 `orchestella.pvmCompiler.libDir`（镜像 `fastlane.payloadLibDir` 风格），编译器 `.so` 作为第二个 jniLibs 源目录与 payload 同 APK `lib/arm64-v8a/`；require 约束：必须同时有 payload 属性、不得含 `libfastlane_payload.so`/`libvm_payload.so`。未设置时零行为变化（CI 不受影响）。
- **`extractNativeLibs`/`useLegacyPackaging` 结论**：payload 在场 = `false`（uncompressed + 16KB 对齐）。理由（已写入 README 与既有代码注释）：Microdroid/AVF 挂载 APK 后从 `lib/` 直接 mmap 取库；提取会改变路径与 SELinux label 且双份磁盘；`minSdk=26 ≥ 23` 满足。
- **脚本**：`tools/pvm-compiler/assemble_payload_libs.py`（staging 只含编译器 `.so`；`--placeholder` 用 NDK 编 2–3 KB 桩，`PVM04_PLACEHOLDER` 可鉴别；纯 Python ELF 解析断言 EM_AARCH64 + PT_LOAD ≥ 16384）；`report_apk_size.py`（分项 + STORED/对齐/ELF machine 断言 + 基线差值）。
- **占位链路实测**（真实 .so 就位前的全链路排练，尺寸为占位）：
  - payload 构建本地复现：Windows 本地踩到 TinyCC 根目录 `version` 文件被 `<version>` 夹具 include 命中的坑（CI Linux 未触发；本地用删该文件的源码副本绕过，仓库零改动，已记入报告）。
  - `assembleDebug`（toolbox+payload+占位编译器）BUILD SUCCESSFUL；`verifyFastlanePayloadApk` 门 **PAYLOAD_APK_OK**（stored/16KB 偏移/SHA-256/`AVmPayload_main`/无 libvm_payload/v2+v3 签名/bin/sh）；`verifyFastlaneToolboxApk` 绿；`zipalign -c -P 16 -v 4` Verification successful；`check-apk-16kb.sh` **APK_16KB_GATE_OK libs=6**。
  - 体积核算：基线（仅 payload）8,190,017 B → 含占位编译器 9,521,778 B（+1.33 MB，全部来自 stub libLLVM/libclang-cpp + NDK libc++_shared）；6 个 `.so` 全 STORED + 16KB 对齐 + EM_AARCH64。
- **未验证**：真实 libLLVM/libclang-cpp 尺寸与打包（等 PVM-04 产物后复验同一门禁矩阵）；设备侧加载（本票零设备，归 DV-PVM-01）。

### 2026-10-24 会话 3（续）：真实 .so 复验全绿

PVM-04 产物（LLVM 22.1.8，strip 后 61.7+62.2+1.2 MB）就位后复验同一门禁矩阵：

- **体积**：基线（toolbox+payload，无编译器）9,515,570 B → 含真实编译器 140,694,845 B（**+131.2 MB**，即三个 `.so` 的 131,146,392 B + ~32 KB zip 开销）；`lib/arm64-v8a/*.so` 共 6 项、合计 126.1 MB。
- **门禁**：`report_apk_size.py --require-libs` **APK_LIB_REPORT_OK**（6 项全 STORED + 16KB 数据偏移对齐 + EM_AARCH64）；`zipalign -c -P 16 -v 4` Verification successful；`verify_payload_apk` **PAYLOAD_APK_OK**（payload `.so` 字节不变、`AVmPayload_main` 在、无 libvm_payload、v2+v3 签名）；`check-apk-16kb.sh` **APK_16KB_GATE_OK libs=6**（126MB ELF 全量闭环）。
- **AGP 交互事实**：build.sh 已 strip 的 `.so` 进 APK 后字节不变（AGP 未再改写）；`keepDebugSymbols` 白名单不含编译器库，符合预期。
- **结论**：两票验收命令全部真跑通过；占位与真实两轮结果都留档于会话报告与 `tools/pvm-compiler/README.zh-CN.md`。设备侧加载归 DV-PVM-01。

- **2026-11-04（设备窗轮对账）**：`gh pr view 24` 实测 state=MERGED（mergedAt 2026-10-24T13:14:54Z）；且上方第二条已记真实 .so 复验全绿（140.7MB APK、6 `.so` 全 STORED+16KB 对齐、PAYLOAD_APK_OK），「复验在收尾」不再成立，置 implemented。
