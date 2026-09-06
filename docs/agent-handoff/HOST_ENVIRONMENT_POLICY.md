# Host Environment Modification Policy

> 状态：项目级长期策略。适用于 DroidLayer 的所有 Orchestrator、Worker 与 child
> agent。目标是让项目文件、外部 runtime 与宿主系统变化保持可预期、可审计、可清理。

## 1. Default Project Workspace

项目写入优先落在以下 approved roots，且每个文件或目录必须具有明确的 DroidLayer
用途：

1. 当前代码工作区：
   `D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer_dev`
2. 项目相关外部资源根：
   `D:\Development project(开发项目)\DroidLayer`

代码和正式开发文件优先放入代码工作区。`external`、`tools`、`caches`、`artifacts`、
`test-runtimes`、`vm-images`、`temp` 等不适合入库的项目资源可放入外部资源根，但目录名、
用途和清理预期必须明确；不得把它当作无归属的通用存储区。

本策略固化时，`git rev-parse --show-toplevel` 的实际结果为第一条路径。原始要求中的
`DroidLayer\_dev` 按转义后的目录名 `DroidLayer_dev` 归一化；不得据此另建同名 shadow
workspace。第二条路径是获准的项目资源根，即使尚未创建，也只能因明确项目用途而创建。

## 2. C: Drive Rule

默认保持 `C:` 盘不变。下列 DroidLayer 内容应优先在 §1 的 approved roots 中创建，
不得默认放入 `C:\...`：

- 项目源码与正式开发文件；
- 构建产物与测试产物；
- 项目缓存与临时文件；
- VM 镜像与测试 runtime。

已有 `C:` 路径、文件可写或工具具备访问能力，都不构成写入授权。涉及 `C:` 的必要操作
必须先执行 §5 disclosure；无关文件、私钥与凭据不在读取、输出或修改范围内。

## 3. Tool-Mandated User Directories

外部工具强制使用用户目录且无法合理重定向时，可把所需的最小 user-level runtime
resource 作为例外放在该工具规定的位置，例如 `C:\Users\Nyzee\.vctl\`。该例外不扩大到
同目录中的无关资源，也不构成 system-level modification 授权。

执行前必须确认工具要求、重定向可行性、目标是否存在以及是否会覆盖。执行后必须在对应
报告中记录：

- path；
- purpose；
- why project directory cannot be used；
- created/modified resources；
- whether overwrite occurred；
- cleanup expectation；
- impact on host。

当前 `C:\Users\Nyzee\.vctl\bin\crx.vmdk` 归类为 **tool-mandated user-level runtime
resource**：VMware `vctl` 要求该位置且无法合理重定向；Ticket 25 创建时目标不存在，
没有覆盖。它不是项目源码、项目产物的默认存储位置，也不是对 `.vctl` 其他资源的通用
修改许可。清理由 VMware/vctl 支持的生命周期负责；不得直接破坏其 runtime state。

## 4. System-Level Modification

默认保持下列宿主系统资源不变：

- Windows Registry；
- system `PATH`；
- system services 与 drivers；
- `Program Files`；
- system-wide package installation；
- firewall rules；
- OS-level virtualization features；
- security policies。

如果完成已授权任务确实不可避免地需要其中一项，Agent 必须先提交 §5 的完整 approval
request，并等待用户对该具体 operation/path/impact 的明确批准。批准仅覆盖披露的操作；
目标、写入内容或风险变化时必须重新披露，不能从既有批准推导更宽权限。

## 5. Pre-Modification Disclosure

任何涉及 `C:`、系统目录、user-level external runtime 或 system-level resource 的写操作，
执行前必须向用户给出以下完整、具体且可核查的 disclosure：

```text
Operation:
Path:
Reason:
Can it be avoided?
What will be created?
What will be modified?
Overwrite?
Cleanup?
Risk?
```

`Path` 必须解析为明确目标，不得用未解析的环境变量、宽泛 glob 或目录简称代替。
Disclosure 后若实际操作发生变化，先停止并更新 disclosure。完成后，正式报告必须记录
实际变化、未发生的预期变化、覆盖事实、清理终态与残余影响。

## 6. Scope

本策略适用于所有未来 Agent：

- Orchestrator；
- Worker；
- research child；
- prototype child；
- review child；
- handoff child。

委托不会提升宿主机修改权限。Child Agent 不得因为 parent 已获准某项能力、路径或工具，
就自动假定自己拥有相同或更高权限；其 prompt 必须明确限定允许的 operation/path，且仍受
本策略的 disclosure、approval、最小范围与报告义务约束。只读 child 保持只读。

Agent 完成涉及宿主环境的任务前，必须能够逐项说明所有 workspace 外写入及其归类；无法
证明某项写入属于 approved root、tool-mandated exception 或已明确批准的 system-level
operation 时，不得执行该写入。
