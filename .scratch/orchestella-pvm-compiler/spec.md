# Spec：protected pVM 上的「编译器即库」编译服务（2026-10-24）

- Type: spec
- Status: draft（待维护者确认后转 ready）
- Base: `main 0d89767` + 未合并 PR #18（`feat/dv-acc-assembly`）
- 取代关系：不改写历史。本 spec 为 `orchestella-accelerator` 与 `orchestella-fastlane/P09` 的**新增后端**，不是删除既有工作。
- 性质：零设备设计 + 可证伪的验证计划；**没有任何收益已被证明**。

---

## 1. 一句话

把「加速车道」从 *一台 non-protected VM* 抽象成 *一个编译服务*，第一个后端用 **protected Microdroid payload + 进程内 clang**：guest 只做预处理，codegen 在 pVM 的原生 vCPU 上完成，**全程不发生 `execve`**。

---

## 2. 为什么不是原来的两条路

| 路线 | 缺什么 | 结论 |
| --- | --- | --- |
| protected payload 里 `fork/exec distccd` | guest SELinux 无 `execute_no_trans`（`EACCES 13`，有 audit 证据） | 平台缺口，换设备无解，需平台变更 |
| non-protected 自定义 VM 里跑 distccd | 机制成立，但当前设备 `getCapabilities()=1`（protected-only） | 缺设备，本机永远 `ACCEL_UNAVAILABLE_NO_NON_PROTECTED` |

两条路各缺一样东西，**不能互相顶替**。本 spec 找第三条：**不 exec**。

关键事实：payload 是 APK `lib/` 下的 `.so`，由 Microdroid 的 linker 以 `mmap(PROT_EXEC)` 载入——这与 `execve` 是两条不同的权限路径，后者已被证明被拒，前者已在 DV-01 上跑通（`payloadReady` 通过）。

---

## 3. 架构

```text
QEMU guest (distcc 客户端, CC=distcc clang)
  │  clang -E 在 guest 内完成（.i）
  │  TCP → 192.168.127.254:<port>（gvproxy hostIP，slirp 回退 10.0.2.2）
  ▼
宿主 DistccTcpVsockBridge（已有，loopback only）
  │  distcc wire v1 → AXP1 帧（可选每帧 deflate）
  ▼
protected Microdroid payload（已有骨架 fastlane-payload）
  │  DistccServer：DIST/ARGC/ARGV/DOTI → DONE/STAT/SERR/SOUT/DOTO
  │  InProcessCompiler：CompilerInvocation + EmitObjAction（同进程，无子进程）
  ▼
.o 原路返回，链接在 guest 完成
```

三个不变式：

1. **payload 进程内不出现 `fork`/`exec`/`posix_spawn`**（PVM-02 用测试守住）；
2. **guest 的 argv 视为不可信输入**，只允许白名单 flag（见 §5.3）；
3. **QEMU 侧只用标准 distcc 客户端**，不引入私有协议。

---

## 4. 契约

### 4.1 编译单元

- 输入：已预处理的 `.i`（C 或 C++），由 guest 的 clang 产出；
- 输出：单个 `.o`；
- 只支持 `-c` 语义；**不链接**；不产生 `.d`（依赖文件由 guest 本地生成）；
- 编译器版本/目标必须与 guest 严格一致（distcc 的既有约束），不一致返回固定码 `COMPILER_MISMATCH`。

### 4.2 传输

- distcc wire 固定 v1（token 与语义见一手文档，`doc/protocol-1.txt`）；
- AXP1 负责 token、帧上限、背压、可选 deflate；
- 帧上限沿用 `Axp1Capabilities.ID_MAX_FRAME`；`.i` 超过上限时按固定码拒绝，不做静默截断。

### 4.3 安全边界（新增，必须是工单级要求）

| 威胁 | 处置 |
| --- | --- |
| 恶意 argv（`-Xclang -load` 载入插件、`@response-file`、`-o /path`） | 白名单 + 显式拒绝表，返回固定码；不写 guest 指定路径 |
| 超大输入/输出 | 输入上限、输出上限、单 job 墙钟与 CPU 时间上限 |
| 并发耗尽 | `max_jobs` + 队列上限 + 拒绝码 |
| 侧信道/内存 | pVM 本身即隔离；宿主看不到 payload 内存（这是相对 non-protected 的加分项） |

### 4.4 资源与配置

- CPU 拓扑：默认 `one_cpu`；平台支持 `match_host`（`VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST`），并行优先靠**多实例**而不是单实例多核；
- 内存：`setMemoryBytes` 需覆盖 libLLVM 常驻 + 单 TU 峰值，按实测调；
- 预热：复用 `ProtectedWarmPool` 的租约/健康检查，容量默认仍 0，只有 flag 打开且后端判定可用时才扩张。

PVM-01 落地（2026-10-24）后的选道字段映射：候选资格 = `protectedVmSupported`；在场 = `payloadCompilerPresent`（缺失 → `PAYLOAD_IMAGE_MISSING`）；身份 = `payloadTargetTriple` == required triple 且 `payloadCompilerVersion` 非空（给了 required 版本则须相等，否则 → `PAYLOAD_COMPILER_MISMATCH`）；预算 = 热/电（`THERMAL_BUDGET`）。生产侧 `payloadCompilerPresent` 恒 false（manifest 格式归 PVM-04/08），`PVM_PAYLOAD` 在真机接线前选不中，属预期。详见决策地图 §3.2 的映射表与 PR #21。

---

## 5. 判定

### 5.1 产物等价（替代 SHA 比对）

`.o` 的 SHA 不能作为判据（路径、时间戳、随机种子都会变）。判据改为：

1. 两产物都能被 `ld` 链接进同一可执行文件；
2. 运行结果与退出码一致；
3. `nm -C` 符号集合与 `readelf -r` 重定位目标一致；
4. 差异仅允许出现在 `.comment`、`.debug_*`、路径类字符串。

同时启用确定性开关（`-frandom-seed`、`-ffile-prefix-map`、`-fdebug-compilation-dir`）以减少噪声。

### 5.2 收益

沿用 AX-07：同设备、同镜像、同构建号、同 `-j`、控温、A/B 交替、n ≥ 10、median/IQR；
`< 1.5×` 记负结果，不产品化。

### 5.3 固定失败码（新增，全部入 enum）

```text
ACCEL_UNAVAILABLE_NO_BACKEND        没有任何可用后端
PAYLOAD_UNAVAILABLE_NO_PROTECTED    pVM payload 不可用（protected bit 缺失等）
PAYLOAD_IMAGE_MISSING               编译器 .so / manifest 缺失
PAYLOAD_COMPILER_MISMATCH           版本或 target 与 guest 不一致
PAYLOAD_LOAD_FAILED                 .so 加载/初始化失败
ARGV_REJECTED                       argv 命中拒绝表
JOB_TOO_LARGE                       输入或输出超限
JOB_TIMEOUT                         单 job 超时
```

---

## 6. 批次与门

```text
Wave 1（并行，全部零设备）
  PVM-01 后端抽象与选道
  PVM-02 payload 内嵌编译器内核（tcc 样机）
  PVM-04 libclang 交叉构建 spike
  PVM-05 产物等价判定
  PVM-07 性能协议扩展

Gate（真机，一台 protected-only 设备即可）
  DV-PVM-01 机制门：payload 内完成一次 in-process 编译

Wave 2（gate 通过后）
  PVM-03 distcc 服务端（真编译器）
  PVM-06 编译 worker 池
  PVM-08 APK 打包与体积
```

**Gate 的停止条件**：payload 加载后无法完成一次 `.i` → `.o`（seccomp、内存、加载失败），或宿主侧对照显示收益不可能 ≥1.5×。任一成立即停 Wave 2，转 `HOST_NATIVE` 兜底或记负结果。

---

## 7. 非目标

- 不做通用 job 框架；不做远程链接；不做分布式缓存（v1）；
- 不修改 root/SELinux/pvmfw，不使用 `execute_no_trans` 的绕法；
- 不把 protected 车道描述成 Full Linux；
- 不承诺线上性能，直到 AX-07 口径的真机样本 ≥10。

---

## 8. 风险

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| libLLVM 体积/内存超预算 | 加载失败或挤占系统 | PVM-04 先测尺寸；超限则只支持 C 子集或换更小前端 |
| Microdroid 的 seccomp/mmap 限制 | 编译直接失败 | DV-PVM-01 用样机先撞 |
| `.i` 传输开销吃掉收益 | 端到端 <1.5× | AXP1 deflate；只对大于阈值 `.i` 启用；实测 |
| 编译器版本漂移 | `COMPILER_MISMATCH` 常态化 | 版本写进 manifest，与 guest 工具链同源构建 |
| argv 攻击面 | pVM 内任意代码 | 白名单 + 拒绝表 + 负例测试 |

---

## 9. 一手来源（访问日期 2026-10-24）

1. VM Payload API：payload 为 APK `lib/` 下的一个或多个 `.so`，C/C++ 运行时需随包分发，仅 64 位原生，Binder RPC over vsock，不支持传递 fd。
   https://android.googlesource.com/platform/packages/modules/Virtualization/+/7581dc3746c674753ed19ffe875ba37180b56336/vm_payload/
2. AOSP Microdroid：支持「从 APK 加载并执行一个二进制及其共享库」，不支持 Java/图形。
   https://source.android.com/docs/core/virtualization/microdroid
3. AVF API 概览：`getCapabilities()` 的 protected/non-protected 位、内存与 vCPU 可配置。
   https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/libs/framework-virtualization/
4. `VirtualMachineConfig`：`CPU_TOPOLOGY_ONE_CPU = 0`、`CPU_TOPOLOGY_MATCH_HOST = 1`；后者「适合长任务、受益于并行」。
   https://android.googlesource.com/platform/packages/modules/Virtualization/+/80b073a321db6546c6bb0ee65245069fb9ec9d7e/javalib/src/android/system/virtualmachine/VirtualMachineConfig.java
5. 进程内编译的可运行范式：`CompilerInstance` + `CompilerInvocation::CreateFromArgs` + `EmitObjAction`，并提示重复编译需处理 `DisableFree` 以免泄漏。
   https://maskray.me/blog/compiling-c++-with-clang-api
6. distcc protocol v1：`DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO`，无版本协商。
   https://raw.githubusercontent.com/distcc/distcc/master/doc/protocol-1.txt
7. LLVM 许可证：Apache-2.0 with LLVM Exceptions（含 object form 嵌入豁免）。
   https://llvm.org/LICENSE.txt
8. 本仓设备证据：protected payload `execve` → `EACCES(13)`，`avc denied { execute_no_trans }`。
   `docs/research/archive/2026-10-avf/microdroid-exec-selinux-20261022.zh-CN.md`
9. 本仓设备事实：`getCapabilities` raw=1，protected-only。
   `docs/reports/tickets/avf-device-mode/implementation-report.zh-CN.md:215`
