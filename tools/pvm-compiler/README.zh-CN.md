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

## 编译服务（PVM-03）的构建与零设备验证

编译服务是独立 .so（`pvm_compiler_payload`），**不链进** `fastlane_payload`，
自己带一层薄 socket（payload 侧 vsock / 宿主侧回环 TCP），因此不依赖 FLN1 的
`transport.cpp`（那份代码写死了 `<linux/vm_sockets.h>`，只能在 Linux/Android 编译）。

```bash
# 1) 可移植验证构建（任意宿主；库 + host 孪生 + 单测）
cmake -S fastlane-payload/pvm-service -B build/pvm-service
cmake --build build/pvm-service
ctest --test-dir build/pvm-service --output-on-failure

# 2) 产品构建里的 Android payload（FakeEngine 口径，默认不构建）
cmake -S fastlane-payload -B build/payload-svc \
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 \
  -DFL_BUILD_PVM_COMPILER_PAYLOAD=ON
cmake --build build/payload-svc --target pvm_compiler_payload

# 3) 宿主 e2e：golden 字节 → 真实 TCP → 服务 → .o（独立 Python 客户端，非 C++ 自证）
bash tools/pvm-compiler/test-distcc-inprocess.sh
#    期望：PVM_DISTCC_E2E_OK cases=2 + PVM_EQUIVALENCE verdict=EQUIVALENT

# 4) 宿主 Java 桥互操作：真实 DistccTcpVsockBridge + Axp1DistccChannelFactory
bash tools/pvm-compiler/test-java-bridge-interop.sh
#    期望：PVM_JAVA_BRIDGE_OK
```

| 项 | 说明 |
| --- | --- |
| `pvm_compiler_payload_host` | 与 .so 同一个 `main.cpp`，`--tcp <port>` 监听回环；启动后打印 `PVM_CSDL_PORT <port>` 供脚本读取 |
| 环境变量 | `ORCHESTELLA_ACCEL_TOKEN`（必需，32 位小写 hex）、`ORCHESTELLA_PVM_COMPILER_TRIPLE`、`ORCHESTELLA_PVM_COMPILER_JOBS`（1..2）、`ORCHESTELLA_PVM_JOB_WALL_MS`、`ORCHESTELLA_PVM_INPUT_MAX`、`ORCHESTELLA_PVM_OUTPUT_MAX` |
| 单测分组 | `FL_TEST_FILTER=argv_policy` / `distcc_server` / `axp1` / `pvm_e2e` |
| 失败码 | STAT 用 distcc `src/exitcode.h` 的码：`ARGV_REJECTED→0x6500`、`JOB_TOO_LARGE→0x6900`、`JOB_TIMEOUT→0x7600`、`PAYLOAD_LOAD_FAILED→0x6e00`、`COMPILE_ERROR→(编译器退出码<<8)` |
| 非 ASCII 路径 | 真实 `cc`/`ld` 打不开中文路径（会误判 DIFFERENT），`test-distcc-inprocess.sh` 因此把夹具复制到 ASCII 临时目录再判等价 |
