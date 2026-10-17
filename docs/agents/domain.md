# Domain docs：single-context

本项目使用 single-context 领域文档布局：

- 根目录 [`CONTEXT.md`](../../CONTEXT.md)：产品语言、Runtime / Contract / Distro / Storage / Network / Security / Lifecycle 词汇与禁用词。
- [`docs/adr/`](../adr/)：已接受的架构决策；索引见 [`docs/adr/README.md`](../adr/README.md)。
- 领域词汇变更先改 `CONTEXT.md`，触及不可逆决策时新增 ADR；不要用实现注释代替领域定义。

## 使用规则

- 产品语言使用 `CONTEXT.md` 的词汇；实现层词汇（backend、engine、VM 模式）不得进入用户文案。
- 发现同词多义或过时词条时，先提出词汇修正，再改代码或文档。
- ADR 正文是治理记录；与后续证据冲突时走 ACR，不静默改写 accepted 决策。
- 当前没有独立 `GLOSSARY.md`；`CONTEXT.md` 是唯一领域词汇入口。
