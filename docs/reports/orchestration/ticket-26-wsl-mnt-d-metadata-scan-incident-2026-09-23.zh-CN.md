# Ticket 26 WSL 宽根目录元数据扫描事件

- 日期：2026-09-23（Asia/Shanghai）；精确起止时刻：现有会话通知未提供，记为未知。
- 性质：独立于 `ticket-26-historical-worktree-read-incident-2026-09-23.zh-CN.md` 的第二起 Agent 操作范围事件；不是该事件的覆盖、合并或放行记录。
- 影响分类：超出批准读取边界的 WSL 目录遍历及目录名/权限错误输出；项目外目录元数据可能进入模型会话上下文。不是已证实的文件正文、`.git`、凭据或设备数据读取。
- 工作树：`ticket/26-linux-qemu-rebuild`，HEAD `e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`，固定 merge-base `8859234a7cd922d4a99385874d1638f31e20c518`。

## 触发、范围与停止

主会话为定位已获准的 guest-assets Linux mirror，错误运行了以下原始命令；这是**宽根目录扫描**，不能因 `-path` 后缀过滤而说成“没有扫描”：

```text
wsl.exe -d DroidLayer-T26 -- bash -lc 'find /mnt/d -type d -path "*/guest-assets-mirror-20260924/source/guest-assets" -print'
```

扫描根为 `/mnt/d`，并非批准的 Ticket 26 external-root 精确路径。命令的调用方初始 cwd 未由本轮会话证据记录，记为未知；`bash -lc` 子进程实际 cwd 亦未知。工具返回 background task ID，输出显示了获准 mirror 的一个匹配目录以及至少若干不相关位置的 `Permission denied` 目录元数据，其中出现了 `Config.Msi` 和另一项目目录名。这里仅记录类别与最少两个已出现的名称，不复制完整路径列表或这些目录内容。该输出已经进入本会话上下文；提供方接收、留存、删除或下游访问范围未知。

在任务仍显示 `running` 时，主会话调用 `TaskStop`，工具回执为 `Successfully stopped`；后续自动通知状态为 `killed`。精确开始/终止时间、在停止前完整遍历过多少目录、是否存在工具缓冲但未展示的条目，现有获准证据无法独立确认。停止后只对现有 WSL runtime 执行固定字符串 `printf stopped` 的响应检查，没有重跑 `find`、扩展目录查找或读取无关目录正文。`TaskStop` 是中止该错误任务，不是清理其先前输出或证明从未外发。

已看到的输出限目录匹配名与权限错误，没有文件正文、具体凭据、未知网络端点或上传载荷的证据；**未见证据不等于证明没有更广读取或零外发**。若现有或以后获准证据发现正文、凭据、未知端点或更广越界，立即维持冻结，按 `INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md` 协调相关所有者、撤销/轮换与通知并交用户裁决。目前无已识别的具体凭据，不作无对象批量轮换。用户作为本次事件的通知接收人已知情，并明确接受目录元数据可能进入模型服务上下文、精确遍历范围不可独立证明的残余风险；该接受不追认扫描合规，也不是历史 worktree 或秘密的读取许可。

## 证据和 ownership

原始命令、已展示的有限输出、后台任务状态与 `TaskStop` 回执已存在本会话工具调用记录；本轮不读取 C: 用户级 transcript/output 文件，不复制完整命令输出到仓库，也不重新获取宿主日志。会话输出文件的大小与 SHA-256 未在获准范围内读取，均记为未知。没有额外外部证据文件，也没有删除既有失败日志、旧事件报告、TEMP、waiver 或镜像。

本报告写入前的只读 baseline-aware gate：注册/物理 worktree 匹配；HEAD、branch、merge-base、相对 main ahead 2 / behind 0、无 upstream 和最近提交未变。101 项固定 waiver 逐路径仍为 tracked unstaged deletion；其外原 13 项 tracked 修改路径集合 SHA-256 `78933e344ef4121f97cbcced2e1408fff51e317fb6b5b887bd2ccff7f2dc61a0`，原 37 项 untracked 路径集合 SHA-256 `22e4672a8bf562a17d8f2a7fa242b829bdc0ea0e9491a530b3f585d3b47d0a25`。第 38 项 dispatch SHA-256 `cea88fb42784fd73fe2b57faf3dd372bb525f02a702d6f22acf086421630028c`；第 39 项旧事件报告 SHA-256 `46d667933824421bbb99a6ad25e829f21e2c89037000d80f64984239c609fd19`；另四项已授权的 Alpine creator/catalog main/test 输入哈希与实施台账一致。新报告之外未出现未知 Git 路径；staged/ignored 为 0，`git diff --check` 无输出。工作树有意 dirty，不称 clean。此次新报告是单独、明确来源的本轮增量，不机械要求 untracked 永远保持原计数。

## 修复与状态

今后的 WSL 工作只使用既有批准台账给出的绝对 mirror 路径，经 Windows/WSL 映射、父路径及链接目标核验后，在该精确目录和明示测试输入内执行；禁止以 `/mnt/d`、盘符根或其他宽根递归 `find`/`rg`/glob 寻找。此路径纪律不是能约束所有原生工具的技术沙箱；旧 child 误读事件中的自愿 reader 旁路与事件响应 §5 未通过判定保持原样，不改写为 PASS。未找到可信精确路径则停止，不扩大搜索。

用户本消息只解除**主会话非设备**实施冻结；未隔离 child 委派继续冻结。复工前须再次核对 source、不可变及派生镜像的逐项 hash、外部生成输出类别和 ownership，出现未知变化则零写入停止。A 此前对固定 Android 任务的 task-bounded PASS 仅在输入仍匹配时适用；B 的 Windows guest-assets 测试有 Linux-only SKIPPED，不得算 B PASS；C/D/E 不因本报告完成而自动通过。手机已拔下，在用户明确通知重新连接前不运行 ADB/安装/设备测试，不拿旧设备观察充当前资格。设备有关阶段保持 pending/blocked，Ticket 27–37 不启动，也不代用户签字标 completed。

本事件没有代码安全策略修改或产品 ADR/spec 变更；不触发 Architecture Change Policy。未生成新的凭据轮换、通知第三方、commit、push 或 PR。保留本会话已有原始输出与相关日志，不作清理；cleanup owner: user/orchestrator。对未知遍历范围和服务端保留风险的放行属于用户已明确接受的当前范围，不等于对后续新事实的放行。

报告人：Codex ｜ 日期：2026-09-23 ｜ 耗时：未单独计时
