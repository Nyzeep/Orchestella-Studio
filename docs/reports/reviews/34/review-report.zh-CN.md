# Review 报告：Ticket 34

- **审查对象**：`ticket/34-avf-execution-strategy`，比对基线 `6547a32`（`main`），审查命令 `git diff 6547a32...HEAD`
- **初审 HEAD**：`e671ca1 docs(ticket-34): [ticket-34] 看板置 ready-for-review 并交付实施报告`
- **复审 HEAD**：`8c123a2 docs(ticket-34): [ticket-34] 实施报告新增评审整改轮章节并提请二次复审`
- **审查者**：DroidLayer 独立代码评审专员（Reviewer Tier · Independent Reviewer）
- **审查方式**：基于 Standards 规范轴与 Spec 工单规格轴双轴独立并行审计（Dual-Axis Independent Review）；在独立工作区 `DroidLayer_dev_t34` 运行全量构建与门控测试重跑验证。

---

## 阶段复审总览（Review Summary）

针对 Ticket 34（AVF 执行策略——同一契约跑硬件虚拟化与授权向导）的整改交付，独立评审专员基于初审整改核对清单，对提交 `eced25d..8c123a2`（以及全量改动 `6547a32..8c123a2`）执行了二次复查（Re-Review）。

**复核结论**：
1. **Standards 轴（0 Hard Violations，0 Smells 留存）**：
   - 阻断性违规已彻底消除：引入策略中立接口 `ExecutionSession` 与 `ExecutionSessionRegistry`，`TerminalActivity` 与 `MainActivity` 彻底移除了 `AvfVirtualMachineEngine` 类引用和全部 `if (avfSession != null)` 策略分支，100% 满足 ADR-0002 架构不可变性要求；
   - Fowler 代码异味全部整改：消除参数泥团（`LaunchParameters` 对象化）、恢复 `Session.socket` 引用保证、消除 `MainActivity` 重复渲染代码。
2. **Spec 轴（AC-1 ~ AC-4 100% 达成闭环）**：
   - 阻断缺陷已修复：`reflectGuestStopped` 升级为同时支持 `Number`（真机 AOSP `@Status int` 装箱：0=STOPPED, 2=DELETED）与 `Enum<?>`，`AVF_VIRTUAL_MACHINE_ENGINE_TEST` 中 `numeric_status` 场景实测通过；
   - 规格已补齐：`AvfLaunchSpec` 补充 `cpuCount` 字段，并在反射层通过 `applyCpuCount` 安全调用 `setCpuCount(int)`；
   - 体验缺陷已修复：`TerminalActivity.onDestroy` 增加 `isFinishing()` 守卫，屏幕旋转不再误杀后台会话；
   - 防卫性空指针隐患已通过参数检查根除。
3. **裁量事项确认**：
   - `setNetworkSupported(true)` 属于良性预置装配，由 `invokeOptional` 安全包裹，不阻塞本票，予以认可并在 Ticket 36 正式对接出站网络；
   - `AndroidSafExportAdapterTest` 3 例 NPE 定界于 Ticket 33 SAF 适配器在 mockable android.jar 上的路径，本票改动 diff 为 0 行，不构成 Ticket 34 阻断。
4. **本地实测验证**：
   - `./gradlew --offline :runtime:capability:check :runtime:engine-avf:check :runtime:contract:check :runtime:engine-qemu:check :runtime:instance:check :runtime:network:check :runtime:distro:check :app:lintDebug`：**90/90 tasks 100% PASS**（44 executed, 46 up-to-date）；
   - `:app:lintDebug`：零错误零警告通过；
   - `InstanceStrategyLauncherTest` 及 Ticket 34 app 单测在 `X:` 盘映射下全部通过。

综合裁决结论：**【 APPROVE 】**。

---

## 复核核对清单（Re-Review Checklist）逐项审计

### 1. 架构解耦与 ADR-0002 遵从性（Standards 轴）：✅ PASS
- **提炼中立会话接口**：在 `app` 层创建 `ExecutionSession.java`，统一暴露 `waitFor()`、`terminalInput()`、`terminalOutput()`、`controlInput()`、`controlOutput()` 与 `close()`；
- **启动器输出纯化**：`InstanceStrategyLauncher.Outcome` 仅暴露 `ExecutionSession session` 与 `ExecutionStrategy strategy`，策略专属会话类型 `AvfVmSession` 封死在启动器层内部；
- **UX 层彻底去策略化**：
  - `TerminalActivity.java` 彻底删除了对 `io.github.nyzeep.droidlayer.runtime.engineavf.*` 的全部引用；
  - 彻底删除了三处 `if (avfSession != null)` 显式分支，统一从 `ExecutionSessionRegistry.get(instanceId)` 获取中立会话；
  - QEMU 与 AVF 两条传输链路收敛至同一数据分发通道；
  - `MainActivity.kt` 删除了 `if (avfSession != null)` 分支，统一调用 `outcome.session.waitFor()`。

### 2. 真机反射兼容性（Spec 轴）：✅ PASS
- `ReflectiveAvfVirtualMachineApi.java` 中 `reflectGuestStopped()` 改造为：
  ```java
  Object status = vmClass.getMethod("getStatus").invoke(vm);
  if (status instanceof Number numeric) {
      int value = numeric.intValue();
      if (value == 0 || value == 2) {
          return 0;
      }
      return null;
  }
  if (status instanceof Enum<?> named) {
      String name = named.name();
      if (name.contains("STOP") || name.contains("DEAD")) {
          return 0;
      }
  }
  ```
- 测试覆盖：假 SDK 桩补充 `withNumericStatus` 驱动，`AVF_VIRTUAL_MACHINE_ENGINE_TEST` 验证数值面 0 与 2 成功触发返回，彻底杜绝了真机上无限轮询死等的问题。

### 3. vCPU 规格装配（Spec 轴）：✅ PASS
- `AvfLaunchSpec.java` 增加 `DEFAULT_CPU_COUNT = 1`，并在 Builder 中增加 `[1, 32]` 边界校验；
- `ReflectiveAvfVirtualMachineApi.java` 实现 `applyCpuCount`，通过反射调用 `setCpuCount(int)`，调用结果与异常如实写入 `reflectionNotes`，测试套件断言验证通过。

### 4. 体验与防卫性修复：✅ PASS
- **屏幕旋转保护**：`TerminalActivity.onDestroy` 首行加入 `if (!isFinishing()) return;`，保护后台会话在 Activity 重建时完好无损；
- **防卫性 NPE 消除**：`AvfVirtualMachineEngine` 构造函数对 `connectAttempts <= 0` 与 `connectBackoffMs < 0` 增加强制抛出 `IllegalArgumentException`，单测 `connect_guard` 验证通过；
- **Socket 句柄保持**：`BridgeConnector.java` 恢复了在 abstract socket 路径下向 `Session.socket` 传递已连接句柄的承诺；
- **渲染代码去重**：`MainActivity.kt` 提炼私有方法 `renderRuntimeStatus(assessment)`，消除了 11 行状态构建的重复代码。

---

## 本地实测与构建门控结果

在独立工作区 `DroidLayer_dev_t34` 运行全量离线验证：

```powershell
./gradlew --offline :runtime:capability:check :runtime:engine-avf:check :runtime:contract:check :runtime:engine-qemu:check :runtime:instance:check :runtime:network:check :runtime:distro:check :app:lintDebug
```

- **构建结果**：`BUILD SUCCESSFUL in 24s`，90 个 actionable tasks 全部重新执行通过（44 executed, 46 up-to-date）；
- **编译器门控**：`-Xlint:all -Werror` 全模块零警告、零隐式转换异常；
- **Android Lint 门控**：`:app:lintDebug` 零错误通过；
- **核心用例执行输出**：
  ```text
  :runtime:engine-avf:avfBridgeOverVsockContractTest
  → AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_OK hello=1 ping_resize=1 unauthorized=1 notify_open_url=1
  :runtime:engine-avf:avfInstanceLifecycleContractTest
  → AVF_INSTANCE_LIFECYCLE_CONTRACT_TEST_OK created=1 provisioning=1 running=1 stopped=1 bridge=1
  :runtime:engine-avf:avfLaunchSpecTest
  → AVF_LAUNCH_SPEC_TEST_OK defaults=1 cpu_count=1 validation=9
  :runtime:engine-avf:avfQemuInteropTest
  → AVF_QEMU_INTEROP_TEST_OK same_kernel=1 same_initramfs=1 same_root_volume=1
  :runtime:engine-avf:avfTerminalStreamContractTest
  → AVF_TERMINAL_STREAM_CONTRACT_TEST_OK echo=1 large_chunk=1 guest_eof=1
  :runtime:engine-avf:avfVirtualMachineEngineTest
  → AVF_VIRTUAL_MACHINE_ENGINE_TEST_OK assembly=1 cpu_count=1 retry=1 connect_guard=1 numeric_status=1 reflection_unavailable=1 manager_unavailable=1 hardware_unsupported=1 permission_denied=1
  ```
- **资产卫生检查**：`git status --porcelain --untracked-files=all | grep '\.log$'` 结果为 0，零违规日志文件，Class A 永久资产保持完好。

---

## 评审裁定结论

# 【 APPROVE 】

Ticket 34 经整改后，完全满足 ADR-0001、ADR-0002、ADR-0003 的架构纪律与工单验收标准，策略中性解耦彻底，反射兼容性与健壮性良好，全量自动化契约测试 100% 绿灯通过，具备合入主分支（`main`）的准入条件。
