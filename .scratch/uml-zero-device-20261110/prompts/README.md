# 多会话并行 UML 零设备开发 · 开工 Prompt 包

本目录把 `.scratch/uml-zero-device-20261110/spec.md` 的工作包拆成可独立开工的多会话 Prompt。每个 Prompt 都是**自包含**的：新会话只需读 Prompt 里指定的文件，不需要继承本会话上下文。

## 0. 启动顺序

| 顺序 | 会话 | Prompt | 分支 | 工作区 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| Wave 0 | 通用协调者 | `00-coordinator.md` | `integration/uml-zero-device-20261110` | `/workspace-wt/integration` | 无（泛用模板） |
| Wave 1 | U0 Boot timeline | `01-u0-boot-timeline.md` | `feat/uml-u0-boot-timeline` | `/workspace-wt/u0` | 无 |
| Wave 1 | U1 Fast-boot profile | `02-u1-fastboot-profile.md` | `feat/uml-u1-fastboot` | `/workspace-wt/u1` | 可与 U0 并行 |
| Wave 1 | U2 Rootfs 预烘焙 | `03-u2-rootfs-prebake.md` | `feat/uml-u2-rootfs-prebake` | `/workspace-wt/u2` | 可与 U0 并行 |
| **Wave 1.5** | **Wave 1 集成** | **`08-wave1-integration.md`** | `integration/uml-zero-device-20261110` | `/workspace-wt/integration` | U0/U1/U2 已返回；先合并/跨测 |
| Wave 2 | U3 Seccomp/host compat | `04-u3-seccomp-compat.md` | `feat/uml-u3-seccomp-compat` | `/workspace-wt/u3` | **等 08 push 集成分支** |
| Wave 2 | U5 打包/CI | `06-u5-packaging-ci.md` | `feat/uml-u5-packaging-ci` | `/workspace-wt/u5` | **等 08 push 集成分支** |
| Wave 3 | U4 Warm-start | `05-u4-warm-start.md` | `feat/uml-u4-warm-start` | `/workspace-wt/u4` | **等 08 push 集成分支** |
| Wave 3 | U6 多实例 | `07-u6-multi-instance.md` | `feat/uml-u6-multi-instance` | `/workspace-wt/u6` | **等 08 push 集成分支** |
| 持续 | ACR 证据包 | 由协调者执行 | integration | 同上 | U0–U3/U5 |

> U3/U5/U4/U6 的 Prompt 已把 base 改为 `github/integration/uml-zero-device-20261110`；**08 集成会话未 push 之前不要开工**，否则会漏掉 U0/U1/U2 的成果。
>
> Wave 1 集成结果：`docs/research/2026-11-10-zero-device-uml-wave1-integration.zh-CN.md`；三票 `resolved`；U1 x86_64 fast kernel + U2 Alpine x86_64 rootfs 跨测到 `READY`（n=1，宿主 `seccomp=auto`，不是 ARM64 结论）。Wave 2 以集成分支为 base。
>
> 已发现的跨会话契约缺口：U0 parser 认 `BENCH boot_uptime_ms` / `UMARM_WORK_START` / `BOOTPROBE` / `UMARM_SHELL_READY`，不认 U1 的 `U1_READY` 与 U2 的 `T2_MOUNTS_READY` / `T3_READY` / `READY`。08 集成报告必须记录；若要补 alias，另开 U0.1 小票，不要混进集成会话的大 diff。

**并行上限建议：** 同时不超过 3 个实现会话 + 1 个协调者；每个实现会话内部最多 2 个子智能体。这样既并行，又不会让同一文件/构建缓存互相踩踏。

## 1. 所有会话的共同纪律

1. **基线**：`github/research/uml-zero-device-direction-20261110`（已推送）。每个会话先 `git fetch github`，再从该分支切自己的工作分支。
2. **工作区隔离**：用 `git worktree`，不要多个会话共用 `/workspace` 的工作树；也不要让两个会话改同一个文件。
3. **不写产品代码**：ACR 通过前不改 `app/`、`runtime/`、`guest-assets/` 的产品路径；只改 `tools/research/`、`research/`、`docs/research/`、`.scratch/uml-zero-device-20261110/`。
4. **不改 main**：不合并、不 rebase main、不 force push；完成后 push 自己的分支，由协调者集成。
5. **提交身份**：`Nyzeep`（邮箱以本机 git config 为准，不写入仓库）。提交命令统一用：
   ```bash
   env -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
     git -c commit.gpgsign=false commit -m "..."
   ```
6. **敏感数据门**：push 前跑
   ```bash
   python3 tools/ci/check_sensitive_data.py --range github/research/uml-zero-device-direction-20261110..HEAD
   ```
   必须 `SENSITIVE_DATA_OK`；不得带入设备序列号/IMEI/Android ID/截图/完整日志/真实 IP/本机路径。
7. **联网调研**：允许 WebSearch/WebFetch，但一手来源优先（官方文档、源码、规范、项目仓库）；博客/论坛只能作互证，不能单独作为事实依据；每条关键事实写 URL 与访问日期。
8. **子智能体**：每个实现会话用 Agent 工具最多并发 2 个子智能体：一个研究子智能体（一手来源/外部事实）、一个验证子智能体（独立跑验收/审阅）。主会话拥有文件与最终提交；子智能体不提交、不 push、不改同一文件。
9. **证据分级**：报告里区分 `事实` / `推断` / `待实测`；跨夹具、跨就绪点数字禁止相除；真机项标 `not-tested` 并给下个设备窗口入口。
10. **Handoff**：完成后按本文末尾模板给协调者一份报告；协调者只认报告 + 分支提交，不认“口头完成”。

## 2. 推荐工作流映射

| 会话 | 主工作流 | 说明 |
| --- | --- | --- |
| U0 | `/research` → `/tdd` → `/code-review` | 解析器/协议先写测试，再实现；外部调研只用于日志格式核实 |
| U1 | `/research` → `/implement` → `/code-review` | 内核配置/启动参数需要一手来源；构建脚本用实现流 |
| U2 | `/research` → `/implement` → `/code-review` | rootfs 构建/许可证需要调研；脚本用实现流 |
| U3 | `/research` → `/tdd` → `/implement` → `/code-review` | AOSP seccomp 需要调研；矩阵解析器用 TDD |
| U4 | `/research`（可加 `/prototype`） | 只出决策 memo，不写产品代码 |
| U5 | `/implement` → `/code-review` | 打包/CI 脚本；不改现有 release workflow |
| U6 | `/implement` → `/code-review` | host benchmark 脚本 + 数据报告 |
| 协调者 | `/code-review` × N → 合并 → `/retro` | 每个分支独立 review，再集成 |

## 3. Handoff 报告模板（直接复制到会话最后一条消息）

```markdown
## Handoff · <会话名>

- branch: <branch>
- base: github/research/uml-zero-device-direction-20261110 @ <sha>
- head: <commit sha>
- changed files: <list>
- 完成内容: <3–6 条>
- 验证命令与结果: <命令 + pass/fail + 关键数字>
- 子智能体使用: <研究/验证各做了什么；结论>
- 未完成/not-tested: <明确列出>
- 风险/开放问题: <最多 5 条>
- 给协调者的合并顺序/冲突提示: <如有>
```

## 4. 目录约定

- U0：`tools/research/uml-boot-bench/`
- U1：`research/uml-fastboot/`
- U2：`research/uml-rootfs-prebake/`
- U3：`tools/research/uml-seccomp/`
- U4：`docs/research/2026-11-XX-uml-warm-start-feasibility.zh-CN.md`
- U5：`tools/research/uml-artifacts/` + 研究专用 workflow（不碰 `release.yml`）
- U6：`tools/research/uml-multi-instance/`
- 协调者：`docs/research/2026-11-XX-zero-device-uml-integration.zh-CN.md` + 更新 spec/tickets/ACR 证据表

## 5. 一句话给所有会话

**先把口径做对，再把证据做硬；不要用 proot 的“无内核秒开”当 UML 的验收线。**
