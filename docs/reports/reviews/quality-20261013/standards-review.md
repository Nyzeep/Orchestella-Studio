# Standards 最终窄复核

分支 `main`；HEAD=`8d0e8c75d769ecc645ea102b90a304c8657344a1`；commit list 空。前轮四项已关闭结论保留；本轮仅查网络、Bridge/guest-agent、参数、新测试、shell 显示及 NOTICE；未全量重审，已有 UI diff 保留。

## 硬约束：当前 0 blocker

- `app/src/main/java/io/github/nyzeep/orchestella/NetworkGatewayDaemon.java:74,172,291,341`：私有 Unix API、固定 `/services/forwarder/*`；HTTP framing/大小/期限有界，确认失败回滚。默认无映射，仅显式 TCP loopback；LAN/UDP 不支持。符合 ADR0010 暴露面和出站 policy §2/§6。
- `runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract/BridgeClient.java:105,179,350`、`guest-assets/agent/guest-agent.c:204,354,366`：单 reader；HELLO 总期限重试；迟到 READY 仅消耗已发送 HELLO 预算，不完成 PING/RESIZE。同 token/proto1 幂等；错误 token/版本和超长 frame 终止通道。符合 ADR0010 鉴权/payload 门。
- `guest-assets/shell/20-orchestella-terminal-integration.sh:36`、`app/src/main/java/io/github/nyzeep/orchestella/terminal/ShellCommandTranscript.java:74,235`：仅未排除的新历史项，空提交/Ctrl+C 不复用旧命令；校验后纯内存显示，prompt/用户名不提升为指令、不外发。source20/嵌入模板一致。`app/src/main/assets/NOTICE.GUEST-RUNTIME:2,11,208` 包含 musl MIT、compiler-rt Apache/LLVM exceptions；App 为 Apache-2.0，符合 ADR0014。

## 实 bug：本轮 1 项，已关闭

`app/src/main/java/io/github/nyzeep/orchestella/PortForwardingPresenter.java:156` 原对未应用的 LAN/UDP 调用 live remove，被拒后无法持久删除，违背本轮删除约束。复现：旧规则→运行 QEMU→删除。已报主，修为仅 `isApplied` 时移除/回滚 listener；`PortForwardingPresenterTest.java:232,236,258` 覆盖重建空 store、API 零调用、持久失败不创建 listener；原 TCP 回滚次数断言加强。已定点读源码，未执行。

## Heuristic：0 项

Standards 静态 approve。主审提供动态证据：595 all-pass、services HTTP200、2222→22 随机密码 SSH、READY attempt29、resize41×35 ACK/stty16×41、static AArch64 musl cpio entry hash/manifest。跨发行版/设备及 running2 均来自主审；最新七发行版动态仍在推进，不宣称本审验证或全部完成。

仅写此报告；未运行测试、设备、Gradle、网络或 gitwrite。无未关闭实 bug，无新增暴露面，无需 Architecture Change Policy 变更。报告人：Standards reviewer / GPT-6.1-sol ｜ 2026-10-13。
