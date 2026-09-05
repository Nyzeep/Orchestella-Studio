---
status: accepted
date: 2026-09-05
---

# 许可证策略：Apache-2.0 + 零代码复制立场

1. **项目许可证：Apache-2.0**——最大化 OEM 合作（L3 愿景）与商业化选项，含显式专利授权条款；用户在知悉"无 copyleft 护城河"取舍后选定。
2. **零代码复制立场（Original-Implementation Stance）**：对 GPL 参考项目（Podroid GPL-2.0、Droidspaces GPL-3.0、Termux 系 GPL）借鉴架构、不复制代码与二进制；不 vendored Termux 系组件；终端等组件选型必须避开 GPL 传染（选型时记录）。
3. **GPL 义务流程化**：随 App 分发的 GPL 二进制（kernel、QEMU——ADR-0005 的 CI 自建产物）由 CI 生成"对应源码清单"（精确版本 tag + 完整构建脚本仓库链接 + LICENSE 全文），随 App 附第三方许可页与书面源码 offer；每次升级同步更新。
4. **分发边界三分**：App 代码（Apache-2.0）/ 随包 GPL 二进制（独立程序边界 + 对应源码义务）/ guest rootfs（官方 tarball，义务在上游发行版渠道）——互不传染；未来 guest 内加入 GPL 工具不传染宿主 App，但须 NOTICE 如实标注。

## Considered Options

- **GPL-3.0（备选，未选）**：copyleft 护城河防闭源 fork，代价是 OEM 合作与商业化路径受阻——用户权衡后选 Apache-2.0。
- **MIT（否决）**：无显式专利条款，虚拟化/模拟领域是实质短板。
- **复用 Podroid 等项目代码（否决）**：许可证传染 + 与 ADR-0001~0013 已建立的自研路线自相矛盾。

## Consequences

- 全自研工程成本为既定预算：9p-over-vsock 服务端、终端组件、未来 RFB 客户端等（前序各票已按此预算）。
- CI 许可清单任务是每次 kernel/QEMU 升级的固定步骤，漏更即合规瑕疵。
- 项目护城河 = 决策记录、契约测试、发行版适配经验、持续维护——而非代码独占；与 wayfinder 流程的投入结构一致。
- 研究票「许可证与再分发义务盘点」继续保留（补齐 crosvm 等细节），不阻塞本题。
- 首次发布前是本决策的唯一可逆窗口；发布后宽松↔严格方向调整受存量贡献者授权约束。
