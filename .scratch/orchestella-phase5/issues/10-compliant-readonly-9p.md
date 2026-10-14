# 只读 9p 标准 framing 与资源回收

Type: implementation
Status: implemented-local; device-gates-open
Owner: ninep-worker
Blocked by: 04

## What to build

在既有只读Downloads语义内修复9p服务标准opcode/tag/framing、超限/短payload拒绝与FD回收；使用官方标准请求与独立客户端证实，不以共同错误fixture通过当真guest挂载。

## Acceptance criteria

- 按9p2000.L标准7byteheader/正确tag/opcode回包，Tattach104/Rattach105，协商msize/短输入/非法string/未知opcode明确错误。
- 资源/fid/version重置/断流/无效请求所有路径finally回收，readonly全部写请求EROFS。
- containment与symlink/pathescape/TOCTOU有可执行负例或明确安全失败边界；不扩大host权限/目录。
- 独立标准wire回归、现有contract修订、中文报告/localpatch，guestmount仍标未运行，无commit。

## Dispatch

唯一worktree `Orchestella_phase5_9p` / `phase5/worker-9p`；独占 `runtime/engine-avf/.../Avf9pShareServer.java`与`Avf9pShareContractTest`/新独立9ptests；禁止App/contract/其他AVFAPI/Gradle/guest/tracker/ADR。workflow implement+tdd，原storage研究者完成研究后继续context；不派生。生成物 C/ninep-repair/evidence/preserved，不设备操作/系统更改；安全/架构冲突报告给coord。

## Local delivery resolution

已实现、宿主验证并合流隔离分支。源码与报告：[实施报告](../../../docs/reports/tickets/phase5-10/implementation-report.zh-CN.md)。未运行设备/平台及部分原型能力见正式验证矩阵，不能以本地状态推断完全可用。无commit/push。
