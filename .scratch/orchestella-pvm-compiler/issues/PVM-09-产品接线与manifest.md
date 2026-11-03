# PVM-09: 产品接线——payload manifest、事实来源与 PVM_PAYLOAD 车道启动

- Type: integration（Java/Kotlin + 构建）
- Status: implemented（`7704203`+`c034f35` 已合入 main，报告在库；真机项归 DV-PVM-02b）
- Priority: P0（DV-PVM-02b 与「用户真能用上」的前置）
- Owner: app integration owner
- Blocked by: 无（PVM-01/03/08、DV-PVM-02a 均已合并）
- Parent: `../决策地图.md`
- Zero-device: 是（fake VM + manifest 夹具）

## 背景

真机已经证明：protected payload 能加载 libclang、能编出真实 `.o`（DV-PVM-02a）。但**产品路径完全没接**：

1. `AndroidAcceleratorFactProvider` 不读任何 payload manifest → `payloadCompilerPresent` 恒 false → `AcceleratorPolicy` 永远选不中 `PVM_PAYLOAD`；
2. `AcceleratorLaneAssembly` / `AcceleratorLaunchDispatcher` 只会起 non-protected 自定义 VM，没有「起 protected 编译 payload」的 starter；
3. APK 的编译器库是 Gradle 属性 opt-in，没有 manifest 描述版本/triple/库哈希；
4. AXP1 的 token 目前来自构建期静态 asset，不是每次运行的新秘密。

没有本票，02b（QEMU guest 端到端）只能靠 QA 夹层拼，不能走产品路径。

## 目标

### 1. 编译服务 manifest

新增 APK asset `PVM-COMPILER-MANIFEST`（JSON，schema=1）：

```json
{
  "schema": 1,
  "compiler_version": "22.1.8",
  "target_triple": "aarch64-unknown-linux-gnu",
  "vcpu_topology": "one_cpu",
  "memory_mib": 2048,
  "payload_binary": "libpvm_compiler_payload.so",
  "libs": [
    {"name": "libLLVM.so", "size": 64700000, "sha256": "…"},
    {"name": "libclang-cpp.so", "size": 65200000, "sha256": "…"},
    {"name": "libc++_shared.so", "size": 1200000, "sha256": "…"}
  ]
}
```

- 由 PVM-08 的 staging 脚本生成（`tools/pvm-compiler/assemble_payload_libs.py` 扩展），不手写；
- 构建期校验：manifest 里的库与 `lib/arm64-v8a/` 实际文件一一对应（名字/大小/SHA）；
- 缺失或字段不全 → 视为 `payloadCompilerPresent=false`，不得猜测。

### 2. 事实来源

`AndroidAcceleratorFactProvider`：

- 读 manifest（只读 asset，不落盘）→ `payloadCompilerPresent` / `payloadCompilerVersion` / `payloadTargetTriple`；
- `protectedVmSupported` 沿用现有 `MODE_UNSUPPORTED` 映射；
- manifest 版本与 `Axp1Capabilities.ID_COMPILER_VERSION` 不要求运行时一致（一个是构建期声明，一个是握手实测），但**两者都进 decision 的可观测字段**。

### 3. PVM_PAYLOAD 车道启动

新增 `PvmPayloadLaneStarter`：

- `setProtectedVm(true)`、payload binary 取 manifest、`one_cpu`、内存取 manifest、`DEBUG_LEVEL_NONE`（生产）；
- 启动后等 `onPayloadReady`，再接 AXP1；
- 停止走 `FastLaneSession.stopRuntime`，顺序与 AX-01 一致（QEMU → ACCEL）；
- 失败映射固定码：`PAYLOAD_LOAD_FAILED`（加载）、`PAYLOAD_COMPILER_MISMATCH`（版本/triple）、`PAYLOAD_UNAVAILABLE_NO_PROTECTED`（能力）。

`AcceleratorLaneAssembly` / `AcceleratorLaunchDispatcher`：

- 按 `AcceleratorDecision.backend()` 选 starter：`PVM_PAYLOAD` → 新 starter；`CUSTOM_VM` → 现有 launcher；`NONE` → 只 QEMU；
- flag `orchestella.acceleratorLane` 关闭时**零副作用**（不读 manifest、不建 VM、不占预算）；
- 预算由装配根重算，沿用 AX-01 账本。

### 4. token 口径（本票要给结论，不要悬着）

现状：构建期静态 asset（`assets/accel_token`），不是 per-run 秘密。约束：Microdroid 没有宿主→payload 的 env 通道，payload 也没有可写文件路径，**不存在把 per-run secret 送进 protected VM 的合规通道**。

结论（写入文档与代码注释）：

- **v1 不以静态 token 作为安全边界**：AXP1 的访问控制主要靠「只有持有 VM 句柄的宿主进程能 `connectVsock`」+ Android app 沙箱；
- 静态 token 降级为**构建期防误连**（防止同包不同 build 的桥接互串），不作为 per-run 认证；
- 后续若要 per-run 认证，需平台提供 payload 配置/环境通道（与 `execute_no_trans` 同级的平台诉求），不在本票范围。

### 5. 打包与开关

- `orchestella.pvmCompiler.libDir` 继续 opt-in；本票不决定「发布默认开」；
- 增加构建期检查：属性打开时必须同时产出 manifest 与三个库，且 manifest 校验通过；
- README 写清「开发/QA 怎么开、发布默认是什么」。

## 任务清单

- [ ] manifest 格式与生成脚本（PVM-08 的 staging 脚本扩展）。
- [ ] `PvmCompilerManifest` 解析器（Java，未知字段忽略、缺字段保守）。
- [ ] `AndroidAcceleratorFactProvider` 接 manifest + 单测（存在/缺失/字段不全/哈希不匹配）。
- [ ] `PvmPayloadLaneStarter`（fake VM 测试：启动、ready 超时、stop、失败码）。
- [ ] `AcceleratorLaneAssembly`/Dispatcher 按 backend 分发 + flag 关闭零副作用测试。
- [ ] token 结论写进 `docs/` 与代码注释。
- [ ] 文档：装配图、开关、如何读 decision。

## 验收命令

```bash
./gradlew :app:testDebugUnitTest :runtime:fastlane-capability:test :runtime:instance:test --console=plain
python tools/ci/check_ci_module_coverage.py
python tools/ci/check_sensitive_data.py --range main..HEAD
```

（本机中文路径跑不了 Gradle 单测时，用 ASCII clone 或依赖 CI；报告里不得把「没跑」写成「通过」。）

## 边界

- 不接设备；不改 AXP1/ distcc wire；不改 PVM-03 协议。
- 不改 QEMU 默认行为；flag 关闭时与今天完全一致。
- 不把 `PVM_PAYLOAD` 设成默认后端（发布决策另开）。

## 证据

- 单测 + 一份 `docs/reports/tickets/pvm-09/implementation-report.zh-CN.md`。
- 真机项全部 `not-tested`，归 DV-PVM-02b。

## Comments

- **2026-11-04（设备窗轮对账）**：本票实际已于早期会话实现并合入 main——`7704203`（产品接线：payload manifest、facts、PVM_PAYLOAD 车道启动）+ `c034f35`（评审修补），session 18/19 双包拆分（`c5e51e4`/`b0da2bb`）在其上构建；报告 `docs/reports/tickets/pvm-09/implementation-report.zh-CN.md` 在库（对账方法：git log 三个关键文件 + 报告在库核验）。真机项归 DV-PVM-02b——02a 已于 2026-10-25 真机通过，02b 分段 partial（guest 内行为级退出码与分段打点待设备窗）。本票长期停在 `ready-for-agent` 造成「下一票」误判：2026-11-04 会话按票面把它选为下一开发任务，开工侦查后改判为已实现。
