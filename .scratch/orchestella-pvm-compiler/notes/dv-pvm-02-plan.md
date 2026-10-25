# DV-PVM-02 计划（会话 6）

- 票：`.scratch/orchestella-pvm-compiler/issues/DV-PVM-01-设备机制门.md` 之后的门（02a/02b）
- 分支：`feat/dv-pvm-02-device-e2e`（自 `main = 6441bd6`）
- 设备：`qa-alt-01`，API 37，arm64-v8a，protected-only（capabilities raw=1）

## 1. 02a 编译排障路径

`clang_engine.cpp` 从未编译过。构建必须离开中文路径（NDK CMake 与 MinGW 都会在非 ASCII
路径上崩），因此：

```
<ascii-sandbox>/src/fastlane-payload     ← ASCII 拷贝（仓库是唯一事实源，改动先在仓库做）
<ascii-sandbox>/llvm/lib/arm64-v8a       ← PVM-04 产物三库
<ascii-sandbox>/out/payload-clang        ← 构建树
```

配置（NDK 27.0.12077973，API 34）：

```
cmake -S src/fastlane-payload -B out/payload-clang -GNinja \
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 -DCMAKE_BUILD_TYPE=Release \
  -DFL_BUILD_TESTS=OFF -DFL_BUILD_HOST=OFF \
  -DFL_BUILD_PVM_COMPILER_PAYLOAD=ON -DFL_PVM_COMPILER_ENGINE=CLANG \
  -DFL_PVM_COMPILER_LLVM_LIBS=... -DFL_PVM_COMPILER_LLVM_SRC=... -DFL_PVM_COMPILER_LLVM_BUILD=...
```

按开工 Prompt 的四类问题逐条排查（实测记录见报告）：`Language` 枚举名、`TextDiagnosticBuffer`
接口、`CreateFromArgs` 签名、`setOutputStream` 生命周期、`-fno-exceptions`。

产物门（与 PVM-03 §3.4 同口径）：16 KB 对齐、`NEEDED` 含 `libclang-cpp.so`/`libLLVM.so`、
动态符号表只剩 `AVmPayload_main`。

## 2. 设备步骤（真机单 job）

1. APK：`libpvm_compiler_payload.so`（CLANG 版）+ `libLLVM.so`/`libclang-cpp.so`/`libc++_shared.so`
   同目录（无 RUNPATH，靠同目录解析）；QA 通道 `-Porchestella.buildChannel=qa`。
2. 起 protected VM：`one_cpu`、2 GB、`DEBUG_LEVEL_FULL`、输出捕获，payload 名
   `libpvm_compiler_payload.so`。
3. 宿主侧客户端：`tools/pvm-compiler/axp1_distcc_client.py`（PVM-03 的独立实现，
   不看 C++ 服务端代码）→ 经仪器内的 `DistccTcpVsockBridge`（**生产同款**）→ `vm.connectVsock(5103)`
   → payload。宿主用 `adb forward` 接到桥的 loopback 端口。
4. 等价判定：`tools/compiler-equivalence/compare_objects.py`，对照对象由**同版本 clang 22.1.8**
   （官方 win64 包）以 `--target=aarch64-linux-android34` 编同一份 `.i` 得到。
5. 记录：`payload_ready_ms`、首编/二编、`ru_maxrss`、`obj_bytes`、STAT/失败码、VM stop/delete。

### 已知阻塞点与处置

| 阻塞点 | 事实 | 处置 |
| --- | --- | --- |
| payload token | Microdroid **没有** 宿主→payload 的环境变量通道（无 `setEnv`；`setPayloadConfigPath` 与 `setPayloadBinaryName` 互斥），且 payload 域零文件写 | payload 侧 token 解析顺序改为 `ORCHESTELLA_ACCEL_TOKEN` env → APK 内容 `assets/accel_token`；后者是握手前唯一可用的宿主→payload 通道 |
| 交叉产物无法在宿主执行 | 对象是 aarch64 ELF，Windows 跑不了，`compare_objects.py` 的 BEHAVIOR 级会 OSError | 给工具加显式 `--no-run`（记录 `behavior=skipped`），BEHAVIOR 另由「链接后推到设备执行」补上，不默默降级 |

## 3. 02b 退路

- 复用 `QemuInstanceLauncher`/`FastLaneDistcc`；guest 装 `distcc`（Alpine `apk add distcc`）；
  `DISTCC_HOSTS=192.168.127.254:<port>`（slirp 回退 `10.0.2.2`）。
- 夹具固定 2000 函数 / ~85 KB（DV-PVM-01 已验证尺寸），不用 1 MB。
- 判定只看「`.o` 能在 guest 链接并运行、结果与本地一致」，**不做 1.5× 结论**。
- 若 guest 装不了 distcc / gvproxy 不通 / QEMU 起不来 → 如实 `blocked`，保留失败阶段，
  绝不用宿主客户端冒充交叉加速。

## 4. 不做

不碰 bit2 / root / SELinux / pvmfw；不改 AXP1/distcc wire；不改 `main`、不合并 PR。
