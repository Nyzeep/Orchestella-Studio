# PVM-04：libclang 交叉构建 spike（Android arm64-v8a，零设备）

把「真正的 libclang 能不能为 Android arm64 构建出来、有多大」回答成可复现的脚本与实测数字。
**Spike only**：产物不进仓库、不链入 `fastlane_payload`，产品化由 DV-PVM-01 门控；编译器
版本策略见 `build.sh` 头注释与 `SIZE_REPORT.md`。

## 实测结果（2026-10-24，本仓库构建环境）

| 项 | 值 |
| --- | --- |
| LLVM/clang 源码 | `llvm-project-22.1.8.src.tar.xz`（与 guest Alpine 3.24 的 clang22 同 major），SHA-256 pin `922f18…65888`（交叉核对 Arch `llvm 22.1.8-1` PKGBUILD；上游发布只有 sigstore 签名包，无官方 sha256 清单） |
| Target | `arm64-v8a`（aarch64-linux-android34），仅 AArch64 target |
| NDK / CMake | 27.0.12077973 / SDK CMake 组件（目录名 3.22.1，实际二进制 3.31.5） |
| host tools | MinGW g++ 14.2.0（llvm-tblgen / clang-tblgen / llvm-config，静态链接） |
| 墙钟 | host tools 107 s + 交叉构建 2373 s ≈ 41 min（-j10，20 逻辑核） |
| 磁盘 | host 树 84 MB + 交叉构建树约 9 GB（结束值 ≈ 峰值） |
| **strip 后** | **libLLVM.so 61.7 MB + libclang-cpp.so 62.2 MB = 123.8 MB**（+ libc++_shared.so 1.2 MB）；未 strip 分别为 1.0 GB / 880 MB（符号表占大头） |
| 16 KB | 所有 PT_LOAD `p_align = 0x4000`（链接期 `-Wl,-z,max-page-size=16384`）；仓库 `check-elf-16kb.sh` 门通过 |
| 依赖 | `libclang-cpp.so → libLLVM.so, libc++_shared.so, libm/libdl/libc`；无 RUNPATH/RPATH |
| 初始化符号 | `LLVMInitializeAArch64{Target,TargetInfo,TargetMC,AsmParser,AsmPrinter,Disassembler}` 全部在 `libLLVM.so` 动态符号表 |
| 判定 | 123.8 MB ≤ 150 MB → **尺寸可接受**，交 PVM-08 打包 |

版本选择说明：guest rootfs 是 Alpine 3.24.1（`guest-assets/inputs/inputs.lock`），其
`clang22 = 22.1.3`；按「payload libclang 与 guest clang 同 major」的既定策略取 22.1.x
最新补丁 22.1.8。guest 的发行版 clang 与本构建不「同源字节」，major 对齐是 v1 承诺边界，
精确版本对齐（随 App 分发同一份 clang）是已决方向（决策地图 §8.3），归 PVM-03 落地。

## 前置条件

- NDK（默认探测 `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`，回退常见 D 盘 SDK 布局；可用 `--ndk` 指定）；
  交叉构建用 NDK 自带 clang/lld。
- CMake（默认 SDK 组件，`--cmake` 可覆盖）、Ninja、MinGW g++（Windows host tools）或系统 g++（Linux）。
- 网络：LLVM 源码 tarball 约 159 MB（GitHub release）。已 pin SHA-256，断点续传安全。

## 命令

```bash
# 构建（产物与日志都在仓库 build/ 下，已 gitignore；tarball 缓存与构建树在仓库外 ASCII 短路径）
bash tools/llvm-android/build.sh --llvm-version 22.1.8 --build-type Release

# 报告（strip 前后、NEEDED、16KB 断言、AArch64 初始化符号、墙钟/磁盘）
python tools/llvm-android/report_size.py --out tools/llvm-android/SIZE_REPORT.md
```

产物布局：`<repo>/build/llvm-android/{lib,unstripped}/arm64-v8a/*.so` +
`build-meta.json` + `logs/*.log.txt`。tarball 缓存 `~/..` 之外的 ASCII 短路径
（默认 `/d/t/llvm-android/cache`），源码与构建树默认 `/d/t/llvm-android/{src,build}`
——**不要**把构建树放到含中文的路径：NDK CMake 在非 ASCII 路径下配置会崩。

参数：`--llvm-version --ndk --abi --build-type --targets --api --jobs --out --cache
--src --build-tree --cmake --force`。MinSizeRel / `-Oz` / 拆分裁剪是尺寸超标时的
备选项（本次 Release 已达标，未再构建其他档位）。

## 已知坑（本机实测）

- **GitHub 单流下载会劣化到 KB/s**：`curl -C -` 可续传；卡死时用并行 Range 分块补
  （SHA-256 pin 兜底完整性）。
- **GNU tar 在 Windows 无法创建 `clang/test` 的 symlink 夹具**：`build.sh` 已容忍该
  报错并以 `llvm/`+`clang/` 目录存在性验证解压结果。
- **SDK CMake 目录名与实际版本不符**：`cmake/3.22.1` 目录内的二进制实测 3.31.5
  （LLVM 22 构建无碍）。
- LLVM 22 源码 + NDK r27（clang 18 / libc++ 18）交叉编译**无兼容性问题**，未打任何
  源码补丁、未用非 NDK sysroot。
- 16 GB 内存 + `-j10` 全程稳定（最低空闲约 2.6 GB）；链接 libLLVM/libclang-cpp 用
  NDK lld，无 OOM。

## 许可证

LLVM/clang：Apache-2.0 with LLVM Exceptions（见 `third_party/LLVM-LICENSE.txt`，
取自 llvm.org 原文）。object form 嵌入 APK 的要求在该许可的 LLVM Exception 中有明确豁免；
NOTICE/SBOM 的完整化归 PVM-11/P11 的既有门（`tools/ci/check_ci_module_coverage.py` /
`fastlane_build.py check-licenses`）。
