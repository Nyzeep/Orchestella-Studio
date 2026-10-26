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
# 0b) 编译服务 payload（PVM-03 产物）：manifest 的 payload_binary 默认指它，
#     --payload-dir 下缺它会直接 exit 1。只演练 PVM-08 打包链路时，
#     改成给生成器传 --payload-binary libfastlane_payload.so 即可。
cp <pvm03-build>/libpvm_compiler_payload.so build/payload-stage/arm64-v8a/

# 1a) 占位编译器 .so（PVM-04 未出真实产物时，几 KB 桩，PVM04_PLACEHOLDER 可鉴别）
python tools/pvm-compiler/assemble_payload_libs.py \
  --payload-dir build/payload-stage --placeholder \
  --ndk <ndk-root> \
  --compiler-version 22.1.8 --target-triple aarch64-unknown-linux-android
# 1b) 真实编译器 .so（PVM-04 build.sh 的产物目录）
python tools/pvm-compiler/assemble_payload_libs.py \
  --payload-dir build/payload-stage \
  --compiler-dir <llvm-android-out>/lib \
  --compiler-version 22.1.8 --target-triple aarch64-unknown-linux-android \
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

## 编译服务 manifest（PVM-09）

生成器在 staging 目录里同时产出 `<out>/assets/PVM-COMPILER-MANIFEST`（UTF-8 JSON，
无扩展名，schema 1）。字段是 on-disk 契约，app 侧解析器按同一套名字读取：

| 字段 | 含义 |
| --- | --- |
| `schema` | 固定整数 `1`（字符串 `"1"` 不接受） |
| `compiler_version` | 编译器版本，如 `22.1.8`（构建期声明，不是运行时握手结果） |
| `target_triple` | payload 编译期固化的 triple，当前取 `aarch64-unknown-linux-android`；生成器**不猜**，必须显式传 |
| `vcpu_topology` | 恒为 `one_cpu` |
| `memory_mib` | VM 内存，`1..16384` |
| `payload_binary` | 编译服务 payload 的 `.so` 文件名（不含路径） |
| `libs[]` | `{name, size, sha256}`，按 `name` 升序，覆盖 `arm64-v8a/` 里**实际存在**的每个 `.so` |

### 怎么生成

还是上面 1a/1b 那一条命令（库与 manifest 一次产出）。新增参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--compiler-version` | 无（必填） | 写进 manifest |
| `--target-triple` | 无（必填） | 写进 manifest |
| `--payload-binary` | `libpvm_compiler_payload.so` | 须匹配 `[A-Za-z0-9._-]+\.so` |
| `--memory-mib` | `2048` | `1..16384` |
| `--manifest-out` | `<out>/assets/PVM-COMPILER-MANIFEST` | 显式路径覆盖 |

成功行仍是 `PVM_COMPILER_STAGE_OK <json>`，`files` 数组不变，另加
`"manifest": {"path": "assets/PVM-COMPILER-MANIFEST", "libs": N}`。

给了 `--payload-dir` 时，除了 `arm64-v8a/libfastlane_payload.so`，还必须在同一目录下有
`arm64-v8a/<payload-binary>`（默认 `libpvm_compiler_payload.so`）；缺任一 → exit 1，
错误文案只报参数名与相对路径。没给 `--payload-dir` 时不校验 payload，照常写 manifest。

### 怎么校验

```bash
python tools/pvm-compiler/assemble_payload_libs.py --verify-manifest build/pvm-compiler/staging
# 通过：PVM_COMPILER_MANIFEST_OK {"compiler_version": "...", "libs": 3, "schema": 1, ...}（exit 0）
# 失败：FAIL manifest: libLLVM.so: size mismatch (manifest 64700000, file 64699992)（exit 1）
```

只读门禁，拦这些维度（全部 exit 1，每条都点名库与失败维度）：

1. `assets/PVM-COMPILER-MANIFEST` 缺失；
2. JSON 解析失败、非对象、`schema != 1`；
3. 必填字段缺失或空串（`compiler_version` / `target_triple` / `payload_binary` /
   `vcpu_topology` / `memory_mib` / `libs`）；`memory_mib` 越界、`payload_binary` 不合规；
4. `vcpu_topology != "one_cpu"`；
5. 缺库（manifest 声明了但 `arm64-v8a/` 没有）、size 不符、sha256 不符；
6. 多库（`arm64-v8a/` 里有 manifest 未声明的 `.so`）；
7. `libs` 为空、条目重复、`sha256` 不是 64 位小写 hex。

`--verify-manifest` 与生成参数（`--compiler-dir` / `--placeholder` / `--payload-dir` /
`--manifest-out`）互斥，同时给会 exit 1。

Gradle 侧（`app/build.gradle.kts` 里 `orchestella.pvmCompiler.libDir` 的块内）：
属性打开时强制 `<compilerRoot>/assets/PVM-COMPILER-MANIFEST` 存在，把
`<compilerRoot>/assets` 加成 assets 源（`arm64-v8a/` 仍只走 jniLibs，不进 assets），
并注册 `verifyPvmCompilerManifest`（执行上面那条 CLI）挂到 `preBuild` 上。

opt-in 构建的 Python 前置条件：设置了 `orchestella.pvmCompiler.libDir` 时，PATH 里
必须有可用的 Python 3——`verifyPvmCompilerManifest` 是一个 Gradle Exec 任务，调用
Python 解释器跑 manifest 校验（解释器取环境变量 `PYTHON`，未设时 Windows 用
`python`、CI/Linux 用 `python3`），没装 Python 的机器上构建会在该 verify 任务失败。
属性未设置的普通构建不注册这个任务，完全不需要 Python。

### 为什么 manifest 不提交

`libs[]` 的 `size`/`sha256` 描述的是**这一次**构建的字节：PVM-04 产物、NDK 版本、
AGP strip 都会变。提交一份就必然与真实库脱节，而校验的全部意义就是「manifest 与
打包进 APK 的库同源」。所以进库的只有生成器与单测，staging 目录
（`build/pvm-compiler/staging`）属构建产物。

单测（不需要 NDK/设备，用临时目录里手造的最小 aarch64 ELF 桩）：

```bash
python -B -m unittest discover -s tools/pvm-compiler -p 'test_*.py'
```

### 三种通道下的开关状态

| 通道 | `orchestella.pvmCompiler.libDir` | 结果 |
| --- | --- | --- |
| dev（本地联调） | 打开，指向 `build/pvm-compiler/staging` | 生成 → 校验 → 打包；manifest 进 APK `assets/` |
| QA（隔离构建） | 打开，可先用 `--placeholder` 把打包链路跑通 | manifest 描述的是桩库，可用 `llvm-nm -D` 查 `PVM04_PLACEHOLDER` 鉴别 |
| 发布 | **默认不开** | 不注册校验任务、不加 assets 源，APK 与既有发布完全一致 |

发布通道要不要默认开，是发布决策（PVM-09 边界：不把 `PVM_PAYLOAD` 设默认后端），
本票只保证「打开就必须是完整、可校验的一套；不开则零变化」。

## AXP1 token 的口径（PVM-09 结论）

token 的读取顺序是 `ORCHESTELLA_ACCEL_TOKEN`（env）→ `<apkContents>/assets/accel_token`
（见 `fastlane-payload/src/pvm_compiler_payload_main.cpp` 的 `read_token_from_apk_contents`）。
这条兜底不是权宜之计，因为**不存在**把 per-run 秘密送进 protected VM 的合规通道：

- `VirtualMachineConfig` 没有 `setEnv`；
- 唯一的运行时配置面 `setPayloadConfigPath` 与 `setPayloadBinaryName` 互斥；
- payload 域零文件写，`execute_no_trans` 又挡住了另一条路。

因此 v1 的口径是三条，写进代码注释与本文件，不留悬案：

1. **静态 token 不作为安全边界。** 它随 APK 分发、每个装了这个 build 的设备都一样，
   把它当 per-run 认证只会给人虚假的安全感。
2. **访问控制靠平台 + 沙箱。** 只有持有 VM 句柄的宿主进程能 `connectVsock`，而句柄只存在
   于本 app 的沙箱里；这是真实边界，不需要 token 参与。
3. **静态 token 降级为构建期防误连。** 它的剩余价值是让同为 `io.github.nyzeep.orchestella`
   但不同 build 的两个实例不会互相桥接成功。

要做 per-run 认证，前提是平台给出宿主→payload 的配置或环境通道（与 `execute_no_trans`
同一级别的平台诉求）。这不在 PVM-09 范围内，也不会用「看起来像秘密的常量」假装实现。
