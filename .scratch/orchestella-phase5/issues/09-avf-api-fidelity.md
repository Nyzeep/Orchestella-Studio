# AVF 官方 API 与 FD 连接适配修复

Type: implementation
Status: implemented-local; device-gates-open
Owner: avf-worker
Blocked by: 01

## What to build

修复现有 custom VM 反射方法、builder配置与run/connectVsock/PFD包装，使平台不具备实际权限/签名时明确拒绝，保留QEMU回退。只修复官方API对应与资源生命周期，不擅自引入platformGPU权限。

## Acceptance criteria

- 固定AOSP API证据、官方方法签名与正确配置对象/run时序；权限检查不声称MANAGE足够。
- ParcelFileDescriptor的真实流包装由Androidadapter或明确反射边界完成，单FD ownership、异常/close回收；不是伪getInputStream。
- fixtures匹配官方源码shape，unsupported/权限/启动失败/FD拒绝有负例，未真机标未运行。
- 必须运行独立JVM现有contract修订，报告与localpatch齐全，不commit。

## Dispatch

唯一worktree `Orchestella_phase5_avf` / `phase5/worker-avf`。独占 `runtime/engine-avf` 的API/engine/reflectiveadapter与其对应tests；forbidden Avf9pShareServer/test（9pworker独占）、App/contract/Gradle/guest/tracker/ADR。协调者集成App接入。workflow implement+tdd，general-purpose配置，高API精度；原研究者保留上下文继续执行不派生。

生成物只 A/avf-api-repair/evidence与preserved。真机独占，不adb/系统变动；GPU平台权限冲突ACR仅coord处理。超过常规3worktree阈值记录当前worker均active/产物dirty，保持全量保留不清理。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-09/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。
