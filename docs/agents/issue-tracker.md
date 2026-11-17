# Issue tracker: 私有规格仓 Nyzeep/Orchestella-Specs

工单、spec 与开发文档存放在独立私有仓库 `Nyzeep/Orchestella-Specs`（GitHub，SSH 访问），不在本仓库内。本地镜像位于 `/workspace/.specs/Orchestella-Specs`，对工单与 spec 的读写通过该镜像以 git 操作完成。

## Conventions

- **读取工单/spec**：在本地镜像 `/workspace/.specs/Orchestella-Specs` 中按文件读取；先 `git -C /workspace/.specs/Orchestella-Specs pull` 同步。
- **创建/更新工单**：在本地镜像中以 markdown 文件新增或修改，commit 后 push 回 `Nyzeep/Orchestella-Specs`（SSH）。
- **本仓库内的 `.scratch/<feature>/`**：研究/进行中工作的本地工作区（spec.md、map.md 等），完成后其结论回写到规格仓或本仓库文档。
- **仓库归属**：工单只写入用户自己的 `Nyzeep/Orchestella-Specs`，绝不写入 `mattpocock/skills`。

## Blocking（阻塞关系）

镜像仓以文件形式记录阻塞：在工单正文中使用 `Blocked by: <工单标识>` 行；被全部阻塞项关闭/解决后才视为 unblocked。

## When a skill says "publish to the issue tracker"

在 `/workspace/.specs/Orchestella-Specs` 镜像中新建工单文件并 commit + push。

## When a skill says "fetch the relevant ticket"

先同步镜像，然后在镜像内检索对应工单文件。

## Pull requests as a triage surface

**PRs as a request surface: no.**
