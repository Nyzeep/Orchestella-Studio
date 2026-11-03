# PVM-04: libclang 交叉构建 spike

- Type: build spike
- Status: implemented（PR #23 已合并 2026-10-24；产物已用于 PVM-08 真实复验）
- Priority: P1（可并行，产品化受 DV-PVM-01 门控）
- Owner: build owner
- Blocked by: 无（spike）；产品化依赖 DV-PVM-01
- Parent: `../决策地图.md`
- Zero-device: 是（构建与测量在 host/CI）

## 背景

`spec.md` 的核心假设是「libclang 可以作为一个 `.so` 链进 payload」。这个假设有两个未测的量：**体积**与**加载成本**。如果裁剪后仍有几百 MB、或加载要几秒，方案的经济性就不成立，应该更早知道。

本票只做 spike：能构建、能测尺寸、能报告依赖。**不承诺产品化**，也不接入产品路径。

## 目标

- 用 NDK toolchain 为 `arm64-v8a` / Bionic 构建 LLVM + clang（仅 AArch64 target）。
- 产出 `libclang-cpp.so`（或 `libLLVM.so` + `libclang*.so` 组合），strip 后记录尺寸。
- 记录：构建耗时、磁盘占用、动态依赖（`readelf -d`）、初始化符号（`LLVMInitializeAArch64*`）。
- 给出三档尺寸选项：`-Oz`、`Release`、`Release+LTO`，各自尺寸与是否可用。
- 版本策略：与 guest 侧 clang 同 major（guest 用发行版 clang 时，选对应 major 的 LLVM 源码构建）。

## 任务

- [ ] `tools/llvm-android/build.sh`：参数化 `LLVM_VERSION` / `TARGETS` / `BUILD_TYPE`；固定 NDK 路径检查。
- [ ] CMake 关键开关：`-DLLVM_TARGETS_TO_BUILD=AArch64`、`-DLLVM_ENABLE_PROJECTS=clang`、`-DLLVM_ENABLE_TERMINFO=OFF`、`-DLLVM_ENABLE_ZLIB=OFF`（除非 AXP1 需要）、`-DLLVM_INCLUDE_TESTS=OFF`。
- [ ] 产物清单 + `SIZE_REPORT.md`（每档：`.so` 字节数、strip 后、依赖列表）。
- [ ] SBOM/NOTICE：LLVM 为 Apache-2.0 with LLVM Exceptions；把许可证文本放进 `tools/llvm-android/third_party/`。
- [ ] 记录「同版本 `.i` 兼容」的约束：major 必须一致，target 必须 `aarch64-linux-gnu` 或 `aarch64-unknown-linux-gnu`（与 guest 工具链一致）。
- [ ] 失败分支：若 `libclang-cpp` 无法单独构建，记录最小可行组合（`libclangBasic/Frontend/CodeGen/Driver` 等）。

## 验收命令

```bash
bash tools/llvm-android/build.sh --llvm-version 20.1.0 --targets AArch64 --build-type Release
python tools/llvm-android/report_size.py --out tools/llvm-android/SIZE_REPORT.md
```

## 边界

- 不做功能验证（编译正确性由 PVM-02/03 负责）。
- 不把产物提交进仓库；只提交脚本、报告与许可证。
- 不引入 GPL 组件（distcc 是 GPL-2.0，本方案用协议而非其代码）。

## 失败信号

- 裁剪后 `.so` > 150 MB → 需要重新评估（只支持 C？换更小前端？）。
- 构建需要 patching AOSP/Bionic 私有头 → 记录，作为产品化阻断项。
- 无法脱离 NDK sysroot 的 libc++ → 记录运行时依赖，交给 PVM-08。

## 证据

- `SIZE_REPORT.md` + 构建日志摘要（不含本机绝对路径）+ 许可证清单。真机加载成本由 DV-PVM-01 测。

## Comments

### 2026-10-24 会话 3：构建与测量完成，尺寸可接受

**分支** `feat/pvm-04-llvm-android`（2 commits），敏感门绿。**PR #23**（不合并，等维护者）。

- **版本**：LLVM/clang **22.1.8**（tarball `llvm-project-22.1.8.src.tar.xz`，SHA-256 pin 与 Arch `llvm 22.1.8-1` PKGBUILD 交叉核对一致；上游只有 sigstore 签名、无官方 sha256 清单）。guest rootfs 为 Alpine 3.24.1（`guest-assets/inputs/inputs.lock`），其 clang22=22.1.3 → 同 major 取 22.1.x 最新补丁。kick-off 举例的 20.1.x 与 guest major 不符，按 PVM-04 版本策略取 22。
- **路径偏差（已在计划文档记录理由）**：kick-off 建议先试 WSL，但本机 WSL 跨调用整态重置（同日记忆实测）+ 内存上限 7GB，多小时构建不可驻留；改走 Windows 原生（MinGW g++ 编 host tblgen/llvm-config + NDK clang/lld 交叉 + Ninja），WSL 留作退路未动用，CI 未动。
- **实测**（`SIZE_REPORT.md`）：strip 后 libLLVM.so 61.7MB + libclang-cpp.so 62.2MB = **123.8MB ≤ 150MB → 尺寸可接受**；未 strip 1.0GB/880MB（符号表占大头，strip 收益 ~93%）。libc++_shared.so 1.2MB 随包。
- **依赖与符号**：`libclang-cpp → libLLVM/libc++_shared/libm/libdl/libc`，无 RUNPATH；`LLVMInitializeAArch64{Target,TargetInfo,TargetMC,AsmParser,AsmPrinter,Disassembler}` 六符号齐全。
- **16KB**：链接期 `-Wl,-z,max-page-size=16384`，所有 PT_LOAD p_align=0x4000，`check-elf-16kb.sh` 门通过。
- **墙钟/磁盘**：host tools 107s + cross 2373s ≈ 41 min（-j10，20 核 16GB，全程无 OOM）；构建树 host 84MB + cross 9.0GB。
- **无阻断项**：LLVM 22 源码 + NDK r27（clang 18/libc++ 18）交叉编译零补丁、零非 NDK sysroot、零 AOSP/Bionic 私有头。
- **配置要点**（`tools/llvm-android/build.sh`）：`LLVM_BUILD_LLVM_DYLIB=ON`+`LLVM_LINK_LLVM_DYLIB=ON`（产出 libLLVM+libclang-cpp）、`LLVM_ENABLE_RTTI=ON`（PVM-03 进程内消费 clang C++ API 需要 typeinfo）、`ANDROID_STL=c++_shared`、`LLVM_DEFAULT_TARGET_TRIPLE=aarch64-unknown-linux-gnu`（guest 契约；运行期以显式 `--target` 为准，最终对齐归 PVM-03）。
- **未做**：MinSizeRel/-Oz/LTO 备选档位未构建（Release 已达标）；LLVM_BUILD_TOOLS=OFF（无 clang 可执行文件，llvm-config 取自 host tools）；真机加载成本归 DV-PVM-01。
- **实测坑**（README 已记）：GitHub 单流下载劣化到 KB/s（并行 Range 分块 + SHA pin 兜底）；GNU tar 在 Windows 建不了 clang/test symlink 夹具（脚本容忍并校验关键目录）；SDK `cmake/3.22.1` 目录内实际是 3.31.5 二进制。

- **2026-11-04（设备窗轮对账）**：`gh pr view 23` 实测 state=MERGED（mergedAt 2026-10-24T13:14:25Z），置 implemented。strip 后 123.8MB 产物已支撑 PVM-08 真实 .so 复验全绿。
