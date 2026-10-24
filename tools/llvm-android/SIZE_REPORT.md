# SIZE_REPORT：libclang 交叉构建 spike（PVM-04）

- 生成时间：2026-10-24T11:38:26Z
- LLVM/clang 版本：`22.1.8`（`llvm-config --version`）
- 源码 tarball：`llvm-project-22.1.8.src.tar.xz`，SHA-256 pin 见 `build.sh`
- Target：`arm64-v8a` / API `34`；LLVM_TARGETS_TO_BUILD=`AArch64`
- Host triple（LLVM 内部记录）：`aarch64-unknown-linux-android`
- Default target triple：`aarch64-unknown-linux-gnu`
- Build type：`Release`；NDK：`27.0.12077973`
- 构建主机逻辑核：20；并行度 -j10
- 墙钟：host tools 107 s + cross 2373 s（合计 2480 s）
- 构建树磁盘占用（结束值，≈峰值）：host 84 MB + cross 9036 MB
- 判定阈值：strip 后 `libLLVM.so` + `libclang-cpp.so` 合计 ≤ 150 MB

## 尺寸与动态依赖

| .so | strip 前 | strip 后 | SONAME | NEEDED |
| --- | --- | --- | --- | --- |
| libc++_shared.so | 1.7 MB | 1.2 MB | `libc++_shared.so` | `libc.so`, `libm.so`, `libdl.so` |
| libclang-cpp.so | 880.0 MB | 62.2 MB | `libclang-cpp.so` | `libLLVM.so`, `libm.so`, `libc++_shared.so`, `libdl.so`, `libc.so` |
| libLLVM.so | 1.0 GB | 61.7 MB | `libLLVM.so` | `libm.so`, `libc++_shared.so`, `libdl.so`, `libc.so` |

- 核心合计（libLLVM + libclang-cpp，strip 后）：**123.8 MB** → 尺寸可接受

## ELF LOAD 段对齐（16 KB 兼容）

| .so | LOAD 段 p_align | 16 KB 断言 |
| --- | --- | --- |
| libc++_shared.so | 0x4000, 0x4000, 0x4000, 0x4000 | PASS |
| libclang-cpp.so | 0x4000, 0x4000, 0x4000, 0x4000 | PASS |
| libLLVM.so | 0x4000, 0x4000, 0x4000, 0x4000 | PASS |

- libc++_shared.so: 无 RUNPATH/RPATH
- libclang-cpp.so: 无 RUNPATH/RPATH
- libLLVM.so: 无 RUNPATH/RPATH

## AArch64 初始化符号（`LLVMInitializeAArch64*`）

libLLVM.so 动态符号表含：`LLVMInitializeAArch64AsmParser`, `LLVMInitializeAArch64AsmPrinter`, `LLVMInitializeAArch64Disassembler`, `LLVMInitializeAArch64Target`, `LLVMInitializeAArch64TargetInfo`, `LLVMInitializeAArch64TargetMC`

## 备注

- NEEDED 里的 `libc++_shared.so` 来自 NDK（与构建同一 NDK 版本），随包分发；
- 本报告不含本机绝对路径；产物目录等环境信息见 `README.zh-CN.md` 的默认值。
