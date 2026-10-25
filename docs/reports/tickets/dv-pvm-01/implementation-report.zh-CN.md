# 会话 4 报告：DV-PVM-01a / 01b（真机执行，2026-10-25 续跑收口）

- 日期：2026-10-25（零设备交付 2026-10-24，真机续跑见 §0）
- 分支：`feat/dv-pvm-01-probe`（自 `origin/main` 切出；含探针/真机提交与本报告提交）
- 工单：`.scratch/orchestella-pvm-compiler/issues/DV-PVM-01-设备机制门.md`
- 状态：**01a: blocked（平台文件写缺口）；01b: PASS（加载门 + 编译门）**。按 2026-10-24 修订，01b 是 Wave 2 的放行条件。

## 0. 续跑说明

2026-10-24 会话完成零设备交付（探针三入口、QA instrumentation、host 驱动 17 测试、QA 双包三道打包门禁），真机因设备未连接未执行。2026-10-25 设备接入后续跑：真机阶段暴露并修复了 instrumentation 三处缺陷（见 §6）、建成 vsock 观测通道、拿到 01a 的平台能力面谱与 01b 的两道门结果。原始 logcat 与证据 JSON 保存在仓外证据目录（路径不入库），本报告只引用脱敏字段。

## 1. 结论

| 门 | 结果 | 一句话 |
| --- | --- | --- |
| 01a（tcc 样机） | **blocked** | tcc 的解析与代码生成在 guest 内成功，但对象输出被平台拒绝：payload 域对自身 memfd 的 `write/ftruncate/mmap` 与 guest 全部目录写均 `EACCES(13)`，tcc 无内存输出 API |
| 01b 加载门（三库解析） | **PASS** | `libc++_shared.so`/`libLLVM.so`/`libclang-cpp.so` 无 RUNPATH 同目录解析全部成功（0/235/336 ms）——决策地图 §11 的首要风险排除 |
| 01b 编译门（真实门） | **PASS** | clang 22.1.8 在 protected payload 内进程内编译 10/10 次成功（内存输出流，零文件 I/O），对象 123,920 B 恒定 ELF，p50 98 ms，RSS +1.0%，exit 0 |

**Wave 2 依据票的 2026-10-24 修订放行**：01b（真实 libclang 路径）两道门均过。01a 的 blocked 是 tcc 样机对「文件落盘」的依赖问题，不是「进程内编译机制」不成立——编译的 parse+codegen 阶段在 tcc 下已经成功，失败仅发生在输出环节；libclang 用内存流完整走通（01b 即证）。

## 2. 设备资格（只读项，无序列号/型号）

| 项 | 值 |
| --- | --- |
| 固定别名 | `qa-alt-01`（单设备，`adb devices` 恰 1 台 device 态） |
| API | **37**（开工 Prompt 写 36——设备已 OTA，作为偏差记录） |
| ABI | arm64-v8a |
| feature | `android.software.virtualization_framework` 存在 |
| capabilities 原始位 | **1**（protected-only，与 2026-10-19 基线一致） |
| Hypervisor | **Gunyah**（Qualcomm；`vm info`：`/dev/kvm` 不存在、VFIO-platform 支持） |
| DebugPolicy | **`{log: false, ramdump: false, adb: false}`**——VM 串口/console 输出对宿主不可达（见 §7 风险） |
| VM 权限 | `MANAGE_VIRTUAL_MACHINE`、`USE_CUSTOM_VIRTUAL_MACHINE` 均经 `pm grant` GRANTED（instrumentation 校验通过） |
| 资格探针 | `PvmProbeInstrumentation` 证据 JSON `device.capabilities_raw=1`（每轮记录） |

## 3. 01a 证据（tcc 样机）

**流程**：VM 创建→payload 加载→`payloadReady`（2.3–2.8 s）→tcc 进程内编译循环→退出码。

- tcc（`tcc 0.9.28-mob-e23dabed`，`CONFIG_TCC_PREDEFS` 内嵌 predefs）加载并运行成功；每轮迭代 parse+codegen 完成（host 同源 1–3 ms 量级），**失败全部发生在对象写出阶段**：`tcc: error: could not write '<p>: Permission denied'`。
- 能力面谱（`stage=caps`，guest 内实测，进证据 JSON）：

  ```text
  memfd write:errno13  ftruncate:errno13  mmap:errno13
  reopen-wb:errno13    reopen-wo:errno13
  /data:W-no /data/local:W-no /data/local/tmp:W-no /dev:W-no /tmp:W-no
  ```

  即：payload 域**不存在任何文件类写路径**（含对自身 memfd 的内容写）；作为对照，vsock socket 写是通的（全部 PVM_PROBE 行经 vsock 回流宿主）。
- RSS：6.9–8.8 MB 量级，稳定无泄漏；失败迭代固定退出码 12（首次编译状态非 OK）/13（对象非 ELF），阶段可判。
- 修复尝试与证据：memfd 路径写（EACCES）→ 裸 `open(O_WRONLY)` 重开（EACCES）→ `/data`、`/data/local`、`/data/local/tmp`、`/dev`、`/tmp`（全部 W-denied）→ fstat 也 EACCES（readback 改 lseek 取尺寸可行）→ vendored tcc 打 QA-only fd 哨兵补丁（`::fd::N` → `fdopen(dup)`，`tools/pvm-compiler/patch_tccelf_fd_sentinel.py`）后 `fflush` 仍 EACCES——**write(2) 本身被拒，非路径解析问题**。
- 按票停止条件处置：命中「memfd/seccomp 被拒」变体，判 **blocked**；未做任何 root/SELinux/pvmfw 改动。

## 4. 01b 证据（libclang）

**加载门**（`libpvm_probe_clangload.so`，独立 payload、无编译器 DT_NEEDED）：

| 库 | dlopen | 耗时 | RSS 变化 |
| --- | --- | --- | --- |
| libc++_shared.so | OK | 0 ms | 6.8→7.2 MB |
| libLLVM.so | OK | 235 ms | →35.3 MB |
| libclang-cpp.so | OK | 336 ms | →49.7 MB |

`stage=done` OK、退出码 0——**无 RUNPATH 三库解析（首要风险）排除**。

**编译门**（`libpvm_probe_clang.so`，`CompilerInstance` + `CompilerInvocation::CreateFromArgs` + `EmitObjAction`，六个 `LLVMInitializeAArch64*` 显式调用，`-fno-exceptions`，输入走 `FrontendInputFile(MemoryBufferRef)`，**输出走 `setOutputStream(raw_svector_ostream)` 纯内存流**——零文件 I/O，clang-repl 同款路线；对象字节经 ELF 魔数校验后弃置）：

- 正式判定轮（`--cycles 10`，内存 2 GB，`CPU_TOPOLOGY_ONE_CPU`，`DEBUG_LEVEL_FULL` + `setVmOutputCaptured(true)`）：
  - **10/10 次编译 `status=OK`**，`obj_bytes=123,920` 恒定（ELF）；
  - `compile_ms`：首编 341，后续 97–105，**P50=98、P95=332**；
  - `ru_maxrss_kb`：80,436 → 81,248，**增幅 1.0%**（≤30% 泄漏线内）；
  - `exit_code=0`；`stop_confirmed=true`、`deleted=true`（VM 停止与删除均确认，无 RUNNING/lease 残留）；
  - 2 GB 无 OOM（libLLVM 常驻 28 MB + libclang 14 MB + 编译工作集约 80 MB 峰值）。
- 最小闭环轮（`--cycles 1` 语义）同样 10/10 OK（p50 98 / p95 341，RSS +1.0%），两轮结果一致，可复现。

## 5. 与 host 对照（量级参考，不做收益结论）

| 项 | guest（qa-alt-01，单 vCPU protected VM） | host | 偏差声明 |
| --- | --- | --- | --- |
| 01a tcc fixture（几十行） | parse+codegen 成功（输出被平台拒） | `pvm_probe_host` 10/10 OK，P50 2 ms（Windows MSYS gcc 20 核） | 同源 tcc 0.9.28-mob |
| 01b clang 编译 | 85 KB `.i` P50 98 ms（clang 22.1.8） | NDK clang 18.0.1 编 887 KB `.i` P50 1.42 s（2026-10-24 host 基线） | **fixture 尺寸不同（85 KB vs 887 KB）、版本不同（22.1.8 vs 18.0.1）、核数不同**——仅记录量级，不构成任何收益结论（spec §5.2 纪律） |

单 vCPU guest 上 98 ms 完成 85 KB 编译，未表现出量级异常；真正的性能结论按 AX-07 口径归 PVM-03。

## 6. 未验证项与偏差

1. **fixture 口径**：01b 编译门用 2000 函数（约 85 KB）而非票面 1 MB——首轮 20000 函数（约 0.85 MB）在单 vCPU guest 上超出 900 s finish deadline（编译 >15 分钟未完成，VM 无法优雅停止）。「3 s / 1 MB」阈值判定随之不适用，归 PVM-03 以客机口径重做。已以 `kFixtureFunctions` 常数记录。
2. **内存档位**：2 GB 无 OOM，未触发 3/4 GB 升档路径。
3. **产物等价**：不要求（票面即如此）；对象仅 ELF 魔数校验，四级判据归 PVM-05/PVM-03。
4. **观测通道**：`setVmOutputCaptured(true)` 在本机无效（DebugPolicy log:false），guest logcat/console 均不可达；本阶段全部观测走 vsock 结果通道（§7）。票内「宿主 `adb logcat -s PVM_PROBE`」通道在 protected-only + Gunyah 机器上不成立。
5. **真机安装前提**：HyperOS 熄屏/锁屏状态下 `adb install` 与 `pm install` 均报 `INSTALL_FAILED_USER_RESTRICTED`（无弹窗、秒拒）；需亮屏（QA 期间 `svc power stayon usb`）。OTA 到 API 37 后此行为与 API 36 期不同。
6. instrumentation 三处修复（本分支探针提交）：androidTest manifest 只保留单条 `<instrumentation>`（AGP 对多条声明仅保留最后一条、吞掉前序类）；VM 上下文必须 `getTargetContext()`（`getContext()` 指向 test 包，跨 uid 目录写导致 `AccessDeniedException`）；`am instrument` 输出流含非 UTF-8 字节（HyperOS 注入），宿主读取需 `errors="replace"`。

## 7. 风险

- **payload 域零文件写**（01a blocked 根因）：PVM-03 的 payload 侧任何「写临时文件/落盘缓存」需求都不可行，必须走内存 + vsock（已证）或 authfs（未验证，需平台确认）。这条是平台约束，不是 Orchestella 代码问题。
- **观测盲区**：DebugPolicy 关闭 console/log 后，guest 内部只能靠 vsock 主动回传；PVM-03 的 distcc wire 调试需预算通道容量（vsock 实测稳定，13 行 JSON + 大对象字节均未压测上限）。
- **Gunyah + API 37 固件行为漂移**：与 2026-10-19/20（API 36）相比，安装策略、guest seccomp/SELinux 面均有变化；固件再升级需重跑本门（成本已降至一条驱动命令）。
- **AGP 单 instrumentation 约束**：androidTest 多条 `<instrumentation>` 声明会被静默吞并，后续 QA 探针类合并时需注意（manifest 注释已写明）。

## 8. 需要维护者决策（≤3）

1. **Gate 放行口径**：按 2026-10-24 修订以 01b 为放行条件（本报告口径：01a blocked 是 tcc 输出依赖文件的样机局限 + 平台文件写缺口，01b 已用内存流完整证明机制）。请确认此口径，或要求 01a 以「内存输出型 tcc」（需自研 libtcc 内存输出 API，成本较高）补跑。
2. **vsock 观测通道升格**：DebugPolicy 使票内 logcat 通道不可用，vsock 结果通道（端口 45699，`AF_VSOCK` + `vm.connectVsock` 反射）已是唯一可用观测——是否将其写进 PVM-03 的产品设计（作为诊断面而非仅 QA）。
3. **阈值口径**：「单次编译 > 3 s（1 MB .i）」的收益存疑线，是否改由 PVM-03 按客机 fixture 尺寸重定义并做 AX-07 采样。

## 9. 给 PVM-03 的交接

- **payload 入口**：`libpvm_probe.so`（01a tcc）/ `libpvm_probe_clangload.so`（01b 加载诊断）/ `libpvm_probe_clang.so`（01b 编译），CMake option `FL_BUILD_PVM_PROBE`（默认 OFF，CI 零影响）；构建 `tools/pvm-compiler/build_probe.sh`（NDK 27.0.12077973；`FL_PROBE_LLVM_SRC`/`FL_PROBE_LLVM_BUILD` 指向仓外 LLVM 源码与构建树，路径不入库）。
- **内存输出流接法**（本门的关键实现）：`instance.setOutputStream(std::make_unique<llvm::raw_svector_ostream>(buf))` + `frontend.OutputFile.clear()` + 编译后 `instance.takeOutputStream()->flush()`，对象从 `buf` 取——payload 域**没有文件可写**，任何落盘设计都不可行。
- **观测通道**：payload 侧 `pvmprobe::emit()`（probe_common.h）同时打 logcat 与 vsock（端口 45699，`start_result_listener` 15 s 接受窗 + 主流程不阻塞）；宿主侧 `PvmProbeInstrumentation` 反射 `vm.connectVsock(45699L)` 重试连接读行，证据 JSON `probe_lines` 字段；driver `merge_probe_lines` 合并去重。
- **库解析事实**：三 soname 同目录解析已证（01b 加载门）；`libc++_shared.so` 必须与 libclang 同 NDK（27.0.12077973）同目录。
- **坑**：tinycc 源根 `version` 文件命中 libc++ `<version>`（build_probe.sh 净化拷贝已处理）；tcc 必须 `CONFIG_TCC_PREDEFS`；QA 构建全局 `ANDROID_STL=c++_shared`；AGP androidTest 只允许单条 instrumentation 声明；HyperOS 熄屏拒装（驱动跑前保持亮屏）；`am instrument` 输出含非 UTF-8 字节；guest 内 fstat 被禁（memfd 读回用 lseek(END) 取尺寸）。
- **tcc fd 哨兵补丁**：`tools/pvm-compiler/patch_tccelf_fd_sentinel.py`（仅 QA 构建应用到净化副本）——若 PVM-03 需要保留 tcc 路径做对照，须连补丁一起带走。
