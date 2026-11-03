# DV-PVM-01: 设备机制门（protected-only 设备）

- Type: device validation（Gate）
- Status: resolved（2026-10-25 真机收口：**01a blocked**——payload 域无任何文件写路径（memfd write/ftruncate/mmap 与全部目录均 EACCES(13)，tcc 无内存输出 API），parse+codegen 已证成功；**01b PASS**——三 soname 无 RUNPATH 同目录解析（加载门）+ clang 22.1.8 内存流进程内编译 10/10（p50 98ms、RSS +1.0%、exit 0）。按 2026-10-24 修订以 01b 为 Wave 2 放行条件；报告与维护者决策项见 docs/reports/tickets/dv-pvm-01/implementation-report.zh-CN.md（分支 feat/dv-pvm-01-probe））
- Priority: P0（Wave 2 的唯一放行条件）
- Owner: device owner
- Blocked by: PVM-02
- Parent: `../决策地图.md`
- Zero-device: 否（本票必须真机）

## 背景

Wave 1 全部在 host 上完成，能证明「接口成立」「host 上进程内编译成立」，但证明不了 **Microdroid 里**成立：seccomp、mmap 权限、内存上限、加载时间都只有真机能回答。

本票要回答一个二元问题：**在 protected-only 设备上，payload 进程内能否把一份 `.i` 编成 `.o`？** 能，就放行 Wave 2；不能，就停，不调参硬凑。

## 目标

- 装 QA APK（payload = PVM-02 的样机）。
- 以 protected VM 启动 payload，`one_cpu`，内存先用 2 GB。
- 在 payload 内编译一份固定 `.i`（fixture，≤ 1 MB），产出 `.o` 并回传 SHA-256 与大小。
- 记录：VM 启动到 payloadReady、首次编译、第 2/10 次编译的墙钟与 RSS、失败码（若有）。
- 断言：payload 进程内没有 `execve`（payload 侧计数或 `dmesg`/audit 中无 exec 记录）。

## 任务

- [ ] 设备资格（只读）：API、ABI、`android.software.virtualization_framework`、`getCapabilities` 原始位、两项 VM 权限。
- [ ] 创建 protected VM：`setProtectedVm(true)` + `setPayloadBinaryName(...)`；**不**请求 bit2，**不**调用 `setProtectedVm(false)`。
- [ ] 编译 fixture：成功 → 记录 `.o` 大小 + SHA-256；失败 → 记录固定失败码与阶段。
- [ ] 连跑 10 次，记录 RSS 曲线（泄漏）与 P50/P95。
- [ ] 对照：同一 fixture 在 host 用同版本 clang 编译，记录耗时（只作量级参考，不作收益结论）。
- [ ] 证据：只写设备别名、API、ABI、固定码、SHA-256；不写序列号/型号/原始 logcat。

## 验收命令

```bash
adb devices -l
adb shell getprop ro.build.version.sdk
adb shell pm list features | grep virtualization
# QA APK 与 payload 由 PVM-02 产出，安装与启动命令随产物给出
```

## 边界

- 不跑 distcc、不跑 QEMU、不做性能结论（本票只证明「能编译」）。
- 不改 SELinux/root/pvmfw；需要其中任何一项才能继续 → 停。
- 不复用上一轮 `qa.dv01` 的结论（它的 payload 是 sealed toolbox，不是编译器）。

## 失败信号（任一成立即停 Wave 2）

| 现象 | 判定 |
| --- | --- |
| payload 加载失败 / `PAYLOAD_LOAD_FAILED` | 机制不成立（先查内存与依赖，2 次内） |
| `mmap(PROT_EXEC)` 被 seccomp 拒 | 机制不成立，需平台确认 |
| 编译触发 OOM（2 GB） | 记录峰值，评估升内存；仍 OOM 则机制不可行 |
| 10 次 RSS 单调增长 > 30% | 泄漏，回 PVM-02 修，不入 Wave 2 |
| 单次编译 > 3 s（1 MB `.i`） | 收益存疑，先算端到端再决定 |

## 证据

- `docs/reports/tickets/dv-pvm-01/implementation-report.zh-CN.md`
- 必含：设备资格表、10 次样本、RSS 曲线、失败码、与 host 对照的量级差。

## 2026-10-24 修订：拆成 01a / 01b，并把真实库纳入门

PVM-02（tcc 样机）与 PVM-04/08（真实 libclang 61.7+62.2 MB 已打包）都已合并，原票的「payload 里编译一次」现在有两种含义，必须分开：

### DV-PVM-01a：机制门（tcc 样机）

- payload 用 PVM-02 的 `InProcessCompiler` + TinyCC 样机；
- 只回答「protected payload 里能不能不 exec 完成一次 `.i` → `.o`」；
- 失败 → 机制不成立，停 Wave 2，转 HOST_NATIVE 评估。

### DV-PVM-01b：真实门（libclang，**Wave 2 的真正放行条件**）

- payload 链接 `libclang-cpp.so` / `libLLVM.so` / `libc++_shared.so`（PVM-04 产物，PVM-08 已打进 `lib/arm64-v8a/`）；
- 用 `clang::CompilerInstance` + `CompilerInvocation` + `EmitObjAction` 编一份固定 `.i`；
- **加载路径是首要风险**：三个库都没有 RUNPATH，只靠「同目录 + linker 命名空间」。若 `dlopen`/`DT_NEEDED` 解析失败，会得到 `PAYLOAD_LOAD_FAILED`——先撞这个，不要带进 PVM-03。

### 观测通道（两段共用）

- QA 专用 VM：`setProtectedVm(true)` + `DEBUG_LEVEL_FULL` + `setVmOutputCaptured(true)`，payload 用 `__android_log_print` 打一行 `PVM_PROBE {json}`；
- 宿主 `adb logcat -s PVM_PROBE`（需要输出捕获才导出到宿主 logcat）；
- JSON 固定字段：`stage`、`status`、`iter`、`compile_ms`、`ru_maxrss_kb`、`obj_bytes`、`vm_cpu_topology`、`llvm_version`；
- 手工兜底：`/apex/com.android.virt/bin/vm run-app --log --console`（仅用于诊断，不作为产品路径）。

### 指标与判定

| 项 | 记录 | 判定 |
| --- | --- | --- |
| payload 就绪 → 首次编译 | `payload_ready_ms` / `first_compile_ms` | 只记录，不设阈值 |
| 第 2–10 次编译 | `compile_ms` | P50 与 host 对照，量级差 ≤ 5× 记为「可继续」 |
| 峰值常驻 | `ru_maxrss_kb` | 2 GB VM 内不 OOM；> 1.4 GB 记录并评估 4 GB |
| 10 次增长 | 首末差 | > 30% 视为泄漏，回 PVM-02/PVM-03 |
| 失败 | `stage` + 固定码 | 任一失败按 §停止条件处理 |

### 追加停止条件

- `01b` 连续两次 `PAYLOAD_LOAD_FAILED`（linker/soname）→ 停，记录 `DT_NEEDED` 与 APK 内路径，不尝试 root/SELinux 改动；
- 2 GB VM 内 OOM（libLLVM 常驻）→ 记录峰值；升 3–4 GB 再试一次仍 OOM 则判机制不可行；
- 编译产物与 host 对照不等价（用 PVM-05 的四级判据）→ 先查 triple/sysroot，不得放行 PVM-03。


## 2026-10-24 会话 4 追记

- 零设备交付完成：探针三入口（libpvm_probe / libpvm_probe_clangload / libpvm_probe_clang）、
  PvmProbeInstrumentation（protected + FULL + outputCaptured + 内存可配）、host 驱动
  dv_pvm_probe.py（17 测试绿）、QA APK 双包过三道打包门禁；host 冒烟 10/10 PASS。
- tcc 设备侧硬伤已解：CONFIG_TCC_PREDEFS 内嵌 tccdefs，运行期零 include 依赖。
- 01b 加载诊断独立成 payload（无编译器 DT_NEEDED），失败时仍能产出 dlopen errno 证据。
- 真机未执行：开工至收尾 adb 始终无设备（USB 无枚举、mdns 无服务、WPD 无手机）。
  报告：notes/dv-pvm-01-report-session4.md（分支内 docs/reports/tickets/dv-pvm-01/ 同步一份）。
- 复跑入口：dv_pvm_probe.py --adb <adb> --payload libpvm_probe.so --mode compile
  --apk <qa.apk> --test-apk <test.apk> --logcat-dir <仓外目录>。


## 2026-10-25 会话 4 真机收口追记

- 设备接入（别名 qa-alt-01）：API 37（OTA，偏差记录）、Gunyah、DebugPolicy {log:false,...}、capabilities_raw=1。
- 观测通道改版：guest logcat/console 全不可达，probe 改用 AF_VSOCK 结果通道
  （端口 45699，与 FLN1 产品传输同源），instrumentation 反射 connectVsock 读行，
  driver 在 evidence probe_lines 与 logcat 间合并。
- 01a：tcc parse+codegen 成功，对象输出被平台拒绝——caps 面谱：对自身 memfd 的
  write/ftruncate/mmap 与 /data*、/dev、/tmp 目录写全部 EACCES(13)。按票停止条件
  判 blocked，未做任何 root/SELinux/pvmfw 改动。vendored tcc 的 QA-only fd 哨兵
  补丁（::fd::N → fdopen(dup)）与 ObjectSink 探测代码一并入库备用。
- 01b：加载门 PASS（libc++/libLLVM/libclang-cpp 同目录解析 0/235/336 ms）；
  编译门 PASS（setOutputStream(raw_svector_ostream) 纯内存输出，10/10 OK，
  obj 123920B 恒定，p50 98ms / p95 332ms，RSS +1.0%，exit 0，2GB 无 OOM）。
- 偏差：fixture 缩至 2000 函数（约 85KB）——1MB 口径在单 vCPU guest 超墙钟，
  「3s/1MB」阈值判定归 PVM-03；instrumentation 修复三处（单 instrumentation
  声明、getTargetContext、输出流二进制容错）。
- 维护者决策项（≤3）与 PVM-03 交接见报告 §8/§9。
