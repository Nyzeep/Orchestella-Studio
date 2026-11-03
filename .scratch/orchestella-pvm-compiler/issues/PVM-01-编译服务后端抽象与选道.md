# PVM-01: 编译服务后端抽象与选道

- Type: design+implementation
- Status: implemented（PR #21 已合并 2026-10-24；产品接线由 PVM-09 完成并已合入）
- Priority: P0（所有后端选道的唯一事实源）
- Owner: capability owner
- Blocked by: 无
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

`AcceleratorPolicy` 现在把「加速」硬编码成一件事：**必须**有 non-protected VM + 镜像 + vsock + 匹配 triple + 热/电允许。当前设备 `getCapabilities()=1`，所以选道永远落在 `ACCEL_UNAVAILABLE_NO_NON_PROTECTED`，编译服务根本没有被考虑的入口。

本轮要加入 `PVM_PAYLOAD` 后端（protected payload 内进程内编译）。它不需要 non-protected、不需要自定义镜像、不需要 vsock 之外的任何东西——但现有 facts 里没有它能表达的维度。

## 目标

- 引入 `CompileBackend`（`PVM_PAYLOAD` / `CUSTOM_VM` / `HOST_NATIVE` / `NONE`），选道输出从「是/否」变成「哪个后端 + 为什么不是别的」。
- `AcceleratorFacts` 增加 protected payload 维度，旧字段保留。
- 新增 8 个固定 reason code（`spec.md` §5.3），旧 6 个保留不删。
- truth table 从 16 组扩到 ≥24 组，覆盖「protected-only + payload 可用 → PVM_PAYLOAD」这一新主路径。
- flag 语义不变：`orchestella.acceleratorLane` 关闭时零副作用（不读事实、不建后端、不占预算）。

## 任务

- [ ] `CompileBackend` enum + `BackendDecision`（backend + reason + 拒绝明细）。
- [ ] `AcceleratorFacts` 增：`protectedVmSupported`、`payloadCompilerPresent`、`payloadCompilerVersion`、`payloadTargetTriple`（全部有未知保守值）。
- [ ] `AcceleratorPolicy` 改为有序判据：`PVM_PAYLOAD` → `CUSTOM_VM` → `HOST_NATIVE`（后者本轮恒 `NONE`）；任一步的否决理由必须可观测，不能只返回第一个失败。
- [ ] `AcceleratorDecision` 携带 backend；`qemuOnly(reason)` 保持兼容。
- [ ] `AcceleratorReasonCode` 增码；确认 `AcceleratorPolicyTest` 的 golden JSON 同步更新。
- [ ] truth table：`runtime/fastlane-capability/src/test/resources/accelerator/truth-table-v1.json` 升 v2，新增 protected-only 分支。
- [ ] 文档：`spec.md` §4.4 与决策地图 §3.2 的字段映射；不写 UI 方案。

## 验收命令

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew :runtime:fastlane-capability:test :app:testDebugUnitTest --console=plain
python tools/ci/check_ci_module_coverage.py
git grep -n "ACCEL_UNAVAILABLE_NO_NON_PROTECTED" -- '*.java'
```

## 边界

- 不改 UI/文案；不改 QEMU 默认行为；不启动任何 VM。
- 不删除 `CUSTOM_VM` 分支与其测试——它仍是 bit2 设备上的后端。
- 不新增 Gradle 模块（放在 `runtime/fastlane-capability`，避免 CI 覆盖门额外工作）。

## 失败信号

- 选道需要读平台事实才能决定「有没有后端」→ 说明抽象没做干净（事实与判据混在一起）。
- flag 关闭时任何一次 `AcceleratorFactProvider` 调用 → 违反零副作用。

## 证据

- 单测 + truth table golden + 一份 `docs/reports/tickets/pvm-01/implementation-report.zh-CN.md`（可选，若改动跨 3 文件以上）。

## Comments

- **2026-10-24（会话 2 agent）**：已实现并开 PR #21（`feat/pvm-01-backend-selection`，head `05710ac`，base main，未合并）。语义：有序判据 `PVM_PAYLOAD → CUSTOM_VM → HOST_NATIVE`（本轮恒 NONE）；候选资格门 + 评估门；终态 = 最后一个真正被评估后端的否决；零候选时 `ACCEL_UNAVAILABLE_NO_BACKEND`/`NONE`；decision 携带 `backend` 与完整 `rejections` 明细，`qemuOnly(reason)`/`available()` 旧签名保留。truth table v2 共 26 组（v1 16 组全保留，其中 no-avf / protected-only / non-protected-unsupported 三组顶层码按 kick-off 新语义改为 `ACCEL_UNAVAILABLE_NO_BACKEND`，旧码保留在 rejections 明细）。`AndroidAcceleratorFactProvider` 仅接 `protectedVmSupported`；payload manifest 读取缺口的说明见票面已知缺口与实施报告 §4。CI 首跑红已修（batteryState setter 笔误 + available 丢失 rejections，commit `05363ee`）。本机单测：`:runtime:fastlane-capability:test` 300/300 绿、`:app:testDebugUnitTest` 全绿（仓库 clone 到纯 ASCII 路径后 Gradle test 可跑）。真机 not-tested。

- **2026-11-04（设备窗轮对账）**：`gh pr view 21` 实测 state=MERGED（mergedAt 2026-10-24T09:23:53Z），「待维护者审核」状态过期，置 implemented。产品接线由 PVM-09（`7704203`）完成并已合入 main。
