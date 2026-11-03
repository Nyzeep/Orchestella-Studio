# PVM-02: payload 内嵌编译器内核（tcc 样机）

- Type: implementation（C++，零设备）
- Status: implemented（PR #19 已合并 2026-10-24；样机使命由 DV-PVM-01b 真机门接管）
- Priority: P0（Gate 的前置）
- Owner: payload owner
- Blocked by: 无
- Parent: `../决策地图.md`
- Zero-device: 是（host 构建 + host 测试；payload 目标只做编译产出）

## 背景

`fastlane-payload/src/distcc_bridge.cpp` 现在的编译路径是 `resolve_executable("distccd")` + `spawn_piped(...)`，即 fork/exec 一个进程。这在 protected payload 里被 SELinux 拒绝（`execute_no_trans`），所以整条链路在 protected 设备上必然失败。

本票先不碰 LLVM（太重），用一个**极小的 C 编译器**证明「payload 进程内可以编译」这件事本身成立，并把它做成可替换的接口，供 PVM-03 换真编译器。

## 目标

- 定义 `fl::InProcessCompiler`：`compile(CompileRequest) -> CompileOutcome`，输入输出都在内存，**不落盘、不 fork、不 exec**。
- 提供 `TinyCcCompiler` 实现（TinyCC 或 chibicc，二者都小、都 MIT/LGPL，需记录许可证）。
- host 单测：给一段 `.i`，产出 `.o`，用系统 `ld` 链接后运行，验证行为。
- 连续 10 次编译后 RSS 不单调增长（防重复编译泄漏，见 `CompilerInstance::DisableFree` 类问题）。
- 提供 payload 目标（`libpvm_compiler.so` 或并入现有 payload），但不要求本轮跑真机。

## 任务

- [ ] `src/compiler/compiler.h`：请求/结果结构（argv、stdin bytes、上限、超时、诊断）。
- [ ] `src/compiler/tinycc_compiler.cpp`：进程内调用，错误映射到固定码。
- [ ] CMake：`pvm_compiler` 静态库 + host 测试可执行；payload 构建走现有 `scripts/build-android.sh`。
- [ ] 测试：`tests/test_inprocess_compiler.cpp`
  - 正常 C 编译 → 链接 → 运行输出一致；
  - 语法错误 → 非零状态 + stderr 非空 + 不崩溃；
  - 超大输入 → `JOB_TOO_LARGE`；
  - 10 次循环 → RSS 增长阈值内。
- [ ] `strace -f -e trace=execve,fork,vfork,clone3` 断言：编译期间无 exec（仅允许现场已有的线程 clone）。
- [ ] 记录样机尺寸（.so 大小）与耗时，写进报告。

## 验收命令

```bash
cmake -S fastlane-payload -B build/payload -DFL_BUILD_HOST=ON
cmake --build build/payload --target fastlane_payload_tests pvm_compiler_host
ctest --test-dir build/payload --output-on-failure -R inprocess
bash fastlane-payload/scripts/build-android.sh   # 只验编译产出，不跑真机
```

## 边界

- 样机编译器**不是产品编译器**：输出对象不必与 guest clang 兼容；等价性由 PVM-05/PVM-03 负责。
- 不引入 LLVM 依赖；不新增网络/存储路径。
- 不改 `distcc_bridge` 的现有行为（本票只加接口与实现，PVM-03 才替换）。

## 失败信号

- 需要写临时文件才能完成编译 → 说明接口设计有漏（应支持内存 buffer）。
- 编译期间出现 `execve` → 机制假设错误，立即停并回到 spec §8 风险评估。
- 10 次后 RSS 线性增长 → 需要按 LLVM 的 `DisableFree` 模式做资源释放，或改为「一 job 一进程」（但那就回到 exec 死路）。

## 证据

- `ctest` 输出、`strace` 摘要、尺寸/耗时表。真机项标 `not-tested`。
## Comments

- 2026-10-24（会话 1 agent）：已实现，PR #19（`feat/pvm-02-inprocess-compiler`，基线 `e834e54`）。样机选 **TinyCC**（libtcc，mob `e23dabed`，LGPL-2.1）而非 chibicc：chibicc 只有 x86-64 ELF 代码生成（无法服务 arm64 payload）、无库 API、静态全局状态跨编译累积；libtcc 有正式嵌入 API、每 job 独立 TCCState、支持 x86_64+arm64。**许可证结论：LGPL-2.1 仅限样机，不得进入任何分发物**；源码经 FetchContent + SHA256 钉死拉取，不进仓库。验证（零设备）：WSL ubuntu-24.04 `ctest` 3/3（既有 73 例 + 5 新用例 + strace 无 exec 断言 `NOEXEC_OK exec/fork/vfork=0`）；ASan/UBSan 镜像 CI 门 3/3（LSan 无泄漏；RSS 断言在 ASan 下自动关闭并明示）；Windows MSYS2 冒烟 5/5（链接-运行环节按平台如实 SKIP：tcc win64 对象带 SEH `.uw_base` 修复项，GNU ld 不兼容）。尺寸/耗时：host 对象 1072 字节、约 30 ms/次；`libpvm_compiler.a` 229 KB + `libpvm_compiler_tcc.a` 1.1 MB（未链入 payload .so）。真机项全部 `not-tested`（归 DV-PVM-01）。已知边界：恶意 `.i` 中 `#include` 会走 tcc 文件打开路径，输入硬化（argv 白名单/资源面）归 PVM-03。

- **2026-11-04（设备窗轮对账）**：`gh pr view 19` 实测 state=MERGED（mergedAt 2026-10-24T07:49:43Z），置 implemented。样机使命已由 DV-PVM-01b 真机门接管（clang 22.1.8 内存流编译 10/10）。
