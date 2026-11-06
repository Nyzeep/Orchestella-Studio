# pVM 探针两包适配修复（2026-11-06）

- 票号 / 标题：无独立票（挂在 `orchestella-pvm-compiler` 线，DV-PVM-02b 设备窗前置）
- 执行 session / 模型 / thinking：编排会话 / GLM-5.3-Flash / 高
- 分支：`main`　基线 commit：`df5aebd`

## 1. 任务目标

让 `PvmProbeInstrumentation` 在**两包拆分后**仍能启动 pVM 载荷：载荷二进制随扩展包分发，探针构造 `VirtualMachineConfig` 时必须把 `/mnt/apk` 指向扩展包 APK，而不是主包。

## 2. 实际完成

| 验收判据 | 结果 | 证据 |
|---|---|---|
| 探针能构造出有效 config 并让 VM 起载荷 | ✅ | `virtmgr` 日志：`Protected virtual machine "qa-csdl-…" started` → `has registered guest agent` → `started payload` → `finished payload` |
| 失败从「找不到载荷」变为可归因的载荷退出 | ✅ | evidence `failure_code=PAYLOAD_EXIT_BEFORE_READY`、`exit_code=1`、`payload_ready_ms=null` |
| 不改变既有探针语义（仍 protected VM、单 vCPU、2048 MiB） | ✅ | config 构造仅追加 `setApkPath`；`setProtectedVm/setPayloadBinaryName/setMemoryBytes/setCpuTopology` 原样 |

修复前（同一构型，探针未设 APK 路径）：

```
Failure info: Failed to find payload
None of the specified paths are valid files:
  ["/mnt/apk/lib/arm64-v8a/libpvm_compiler_payload.so", …]
```

修复后：

```
create_ms=79
crosvm … Protected virtual machine started
VM with CID 2053 has registered guest agent
VM with CID 2053 started payload
VM with CID 2053 finished payload      ← 0.4 s 后主动退出
```

## 3. 未完成 / 与预期偏差

- **载荷仍以 exit_code=1 在 `payloadReady` 之前退出**。探针侧拿不到 guest console（`setVmOutputCaptured` 的输出去向在平台侧，App 无读取面），因此本轮**不能**判定是载荷自身初始化失败、动态库解析失败，还是 vsock 端口/握手前置不满足。**不是**已解决的 distcc@36 判据。
- distcc@36 端到端（DOTO>0、`distcc_ms−本地` 秒级、无本地回退）**未运行** —— 载荷都没到 ready。
- 产品路径（`PvmPayloadLaneStarter` → `FastLaneLaunchSpec.withPayloadApkPath`）本来就设了扩展包路径，本次修复只补了探针侧；产品路径的真机行为仍归设备窗（需先建实例）。

## 4. 修改的模块

- `app/src/androidTest/.../PvmProbeInstrumentation.java`（`buildConfig` 内解析扩展包并 `setApkPath`；缺扩展包时写 note 并回落主包）

## 5. 重要设计决策

- 用 `AccelExtensionResolver`（产品同源）解析扩展包路径，而不是自己 `getApplicationInfo`，保证 `versionCode/engine` 判定与产品一致。
- 只在扩展包存在时设 `setApkPath`；未装扩展包时保持旧行为并留 note，避免让探针在没有扩展包的环境里静默改变语义。
- 不改 `qaMode` 协议、不改载荷、不动产品代码。

## 6. 测试

- 契约/JVM：本改动仅 androidTest，无 JVM 用例覆盖点；`:app:testDebugUnitTest` 未因本改动重跑（改动不在 app/src/main，`assembleDebugAndroidTest` 编译通过）。
- 真机（device-myron，QA `qa.dvpvm` 通道）：
  - 修复前：`INSTRUMENTATION_CODE: 1`，`failure_code=VM_ERROR`，`payload_ready_ms=null`，`virtmgr` 报 `Failed to find payload`。
  - 修复后：`INSTRUMENTATION_CODE: 1`，`failure_code=PAYLOAD_EXIT_BEFORE_READY`，`exit_code=1`，VM 起停干净（`stop_confirmed=true`、`deleted=true`）。
- 证据（仓外）：`evidence/accel-payload-20261106/pvm-probe-evidence.json`、`pvm-probe-logcat.txt`。

## 7. Benchmark / 实测数据

- VM create：79 ms（修复前 168 ms，均含 manager 调用开销，n=1 不作性能结论）。
- 载荷存活：约 0.4 s（`started payload` 09:54:18.713 → `finished payload` 09:54:19.109）。

## 8. 风险

- 探针的 `setApkPath` 依赖平台 `VirtualMachineConfig.Builder#setApkPath`；API 37 存在，低版本缺失时走 note 并回落（不会崩）。
- 载荷 exit 1 的真因未定位，若后续判定为载荷缺陷，本票的"修复"只是把可见性从"找不到文件"推进到"载荷自己退出"，不构成 distcc@36 通过。

## 9. 新发现

1. **两包拆分后 androidTest 探针未跟进**：探针默认把主包 APK 当 `/mnt/apk`，而载荷只在扩展包里 —— 这解释了 2026-10-30 两包拆分以来所有"探针跑不起来"的观感，与产品路径无关。
2. **平台确实能把 protected VM 起起来并执行扩展包内的载荷**（本机 capabilities_raw=1，仅 protected），说明 DV-01A 的 `execute_no_trans` 不是"载荷根本无法被平台执行"的同义词——此处的执行者是 `virtualizationservice`，不是 app 域。
3. 载荷 exit 1 需要 guest console 才能定性：建议下一轮给探针加"把 VM console 落到 app 可读文件"的能力（平台 `getSerialConsoleOutput`/vm_output 读取面待核实）。

## 10. 对后续 ticket 的影响

- DV-PVM-02b / distcc@36：先解决载荷 exit 1，再谈三判据；否则设备窗会重复在"VM 起来了但载荷不在"上耗时间。
- 后续任何新增 payload 的探针模式，都必须按两包形态设 `setApkPath`。

## 11. Commit

- `main`：本报告与代码同一提交（见 `git log`）。
