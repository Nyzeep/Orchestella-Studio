# PVM-08：编译器 `.so` 的 APK 打包与体积核算（零设备）

目标：证明编译器共享库（`libLLVM.so` / `libclang-cpp.so`，PVM-04 产物）能被打进 APK 的
`lib/arm64-v8a/`、与 `libfastlane_payload.so` 同目录被 linker 按 soname 解析、16 KB 对齐、
体积可核算。**本票不把编译器链入 `fastlane_payload`，不改 payload `.so` 的导出与行为**；
不放入 `assets/`（Microdroid/AVF 从 APK `lib/` 取库，PVM-03/DV 的范围）。

## 打包接线（Gradle）

- `app/build.gradle.kts` 新增可选属性 `orchestella.pvmCompiler.libDir`：指向含
  `arm64-v8a/libLLVM.so`、`arm64-v8a/libclang-cpp.so` 的目录，作为第二个 `jniLibs` 源目录，
  与 `orchestella.fastlane.payloadLibDir` 的内容在 APK 内合并到同一 `lib/arm64-v8a/`。
- 属性未设置时零行为变化（CI 的 `assembleDebug` 不设置它，APK 体积不变）。
- 约束（构建期 `require` 强制）：
  - 必须同时提供 `orchestella.fastlane.payloadLibDir`（编译器库只有挨着 payload 才有意义）；
  - 编译器目录里不得出现 `libfastlane_payload.so`（payload 目录单源）或
    `libvm_payload.so`（AVF APEX 提供，链接期 stub 绝不入包）。

## `extractNativeLibs` / `useLegacyPackaging` 的结论与理由

**取值：payload 在场（含编译器库）= `false`；否则 `true`。** 这是仓库既有机制
（`android:extractNativeLibs` placeholder + `packaging.jniLibs.useLegacyPackaging`），
本票沿用并给出理由：

1. AVF/Microdroid 把 payload APK 挂载后**直接从 APK 内 `lib/` 取 `.so`**。uncompressed
   且页对齐的 zip 条目才能被 mmap-in-place；一旦提取，库会落到
   `/data/app/.../lib/`，路径与 SELinux label 都变（提取文件与 APK 内容的 label 不同），
   且双份磁盘。
2. `useLegacyPackaging=false`（= `extractNativeLibs=false`）要求 `minSdk >= 23`，仓库
   `minSdk=26` 满足。
3. 调试符号：AGP 仍会对 `.so` 跑 `stripDebugSymbols`（APK 内是 strip 后字节）；
   `keepDebugSymbols` 白名单不含编译器库，属预期。

## 命令（占位链路 → 真实链路）

```bash
# 0) payload .so（PVM-02 构建脚本；须在 ASCII 路径工作区跑）
bash tools/fastlane-build/build-payload.sh
mkdir -p build/payload-stage/arm64-v8a
cp build/fastlane-payload/libfastlane_payload.so build/payload-stage/arm64-v8a/

# 1a) 占位编译器 .so（PVM-04 未出真实产物时，几 KB 桩，PVM04_PLACEHOLDER 可鉴别）
python tools/pvm-compiler/assemble_payload_libs.py \
  --payload-dir build/payload-stage --placeholder \
  --ndk <ndk-root>
# 1b) 真实编译器 .so（PVM-04 build.sh 的产物目录）
python tools/pvm-compiler/assemble_payload_libs.py \
  --payload-dir build/payload-stage \
  --compiler-dir <llvm-android-out>/lib \
  --out build/pvm-compiler/staging

# 2) APK
./gradlew :app:assembleDebug --console=plain \
  -Porchestella.fastlane.toolboxDir=<toolbox-out> \
  -Porchestella.fastlane.payloadLibDir=<payload-stage> \
  -Porchestella.pvmCompiler.libDir=build/pvm-compiler/staging

# 3) 核算与门禁
python tools/pvm-compiler/report_apk_size.py \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --baseline <基线 apk> \
  --require-libs libLLVM.so,libclang-cpp.so,libc++_shared.so
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
python tools/fastlane-build/verify_payload_apk.py --apk <apk> \
  --source-so <payload-stage>/arm64-v8a/libfastlane_payload.so \
  --packaged-so app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libfastlane_payload.so \
  --sdk <sdk-root>
READELF=<ndk>/.../llvm-readelf bash tools/ci/check-apk-16kb.sh app/build/outputs/apk/debug/app-debug.apk
```

## 16 KB 兼容的断言矩阵

| 层 | 断言 | 工具 |
| --- | --- | --- |
| ELF（构建期，PVM-04） | 每个 PT_LOAD `p_align >= 16384`（链接 `-Wl,-z,max-page-size=16384`） | `check-elf-16kb.sh` / `assemble_payload_libs.py`（纯 Python 解析） |
| APK zip 条目 | STORED（未压缩）+ local header 数据偏移 16 KB 对齐 | `report_apk_size.py`、`zipalign -c -P 16` |
| APK 全量 | `zipalign -c -P 16 -v 4` + 解包 ELF 闭环 | `check-apk-16kb.sh` |

## 本地验证环境注意（Windows）

- NDK CMake 在非 ASCII 路径下会崩溃：payload/LLVM 构建一律在纯 ASCII 工作区
  （如某个盘符根下的短路径 clone）执行；`app/src/main/jniLibs/` 里的本机遗留库不影响断言
  （脚本逐项列出并核对）。
- Git Bash 调原生 exe（zipalign/llvm-readelf 等）时 `/d/...` 参数会被自动转换；
  设了 `MSYS_NO_PATHCONV=1` 就必须直接传「盘符冒号 + 正斜杠」风格的 Windows 路径，
  二选一，别混用。
- `verify_payload_apk.py` 的字节比对要传 `--packaged-so`（AGP strip 后的中间产物），
  传未 strip 的源文件会误报不匹配（CI 也是这么传的）。
