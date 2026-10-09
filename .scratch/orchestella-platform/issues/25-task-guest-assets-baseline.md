# 25: Guest 资产构建基线

Type: task（全项目唯一允许的非用户可见共享前提票）
Status: completed
Blocked by: None
Scope contract: `docs/reports/tickets/25/worker-scope-contract.zh-CN.md`

## What to build

建立可复现的 guest 资产构建流水线：按 Guest Kernel 契约（ADR-0005）产出内核 Image 与 initramfs（内含首次引导装配逻辑：格式化根盘、解包官方 tarball、写入随机凭据与桥接 agent 基座），附带 SHA256 清单。必选能力清单进构建校验：清单缺项时构建必须失败（verify-or-fail，ADR-0005）。

本票无前置 ticket；用户已在 2026-09-06 的接管指令中明确放行。

## Acceptance criteria

- [x] 一次命令产出 kernel Image + initramfs + SHA256 清单，两次构建结果可复现（或差异可解释）
- [x] 能力清单校验演示"缺项即失败"（移除一项 config 后构建中断并指明缺失项）
- [x] 装配逻辑在 QEMU 上冒烟：引导 → 格盘 → 解包 tarball → 装配完成标志出现
- [x] initramfs 内桥接 agent 基座（握手/版本协商/token 校验的最小实现）就位

## Test strategy

清单校验逻辑 JVM 测试；构建产物冒烟（脚本化引导验证）；SHA256 清单生成器 JVM 测试。

## Prototype/research dependency

无。本票产出同时为原型票「QEMU TCG 基线」（18）提供可测资产。
