# Agent C — Fast Lane Toolbox / Distcc / Build-CI

> 直接复制本文件全部内容到新会话。你是并行实现会话 C，只认领 P03、P09、P11。

## 0. 会话边界与当前状态

- 仓库：`https://github.com/Nyzeep/Orchestella-Studio`
- 本地 worktree：`/tmp/orch-fastlane-c`（已存在，工作树干净）
- 当前本地分支：`fastlane/agent-c-toolbox` @ `d0f4cedd`
- 目标模块分支：`feature/fastlane-toolbox`（GitHub 已推送到 `d0f4cedd`）
- 基线：`fastlane/baseline` @ `018855bc`
- 已有交付（不要重做，在其上优化/加固）：
  - `fastlane-toolbox/**`：静态 BusyBox 方案、manifest/SBOM/README
  - `runtime/fastlane-distcc/**`：宿主 TCP↔vsock 桥、distcc 测试
  - `tools/fastlane-build/**`：构建/校验/负向门禁脚本
  - `.github/workflows/fastlane-zero-device.yml`、`app/build.gradle.kts` 的零设备任务
  - 报告在 `docs/reports/tickets/fastlane-P03|P09|P11/`
- 你是**唯一**拥有 `app/build.gradle.kts`、`.github/**`、`NOTICE*`、`tools/fastlane-build/**`、`settings.gradle.kts` 的会话。
- 你只做**零设备**构建与宿主测试：不跑 adb、不连真机、不发布 APK。

先执行：

```bash
cd /tmp/orch-fastlane-c
git status --short
git log -1 --format='%h %an <%ae> %s'
git remote -v
```

如果 `github` remote 不存在：

```bash
git remote add github git@github.com:Nyzeep/Orchestella-Studio.git
```

如果 worktree 不存在：从 GitHub 取 `feature/fastlane-toolbox` 即可恢复。

## 1. 本会话工作流：可复现构建 + 供应链/许可证门 + 字节级协议夹具

选择理由：本会话的产物是“别人要信任的构建结果”——toolbox 二进制、APK 注入、CI 门禁、distcc 桥。功能跑通不等于可交付；必须证明**同样输入产生同样字节**、**门禁真的会红**、**许可证/来源可追溯**。执行顺序：

1. **依赖/许可证清单先行**：每个第三方二进制/包记录版本、URL、SHA256、许可证、对应源码义务；许可证不清就不进入产物。
2. **可复现性**：同一输入构建两次，比较产物 SHA256；固定下载 URL/校验和/时间戳；记录 `SOURCE_DATE_EPOCH`。
3. **Red→Green**：先写会失败的 zip/manifest/门禁测试，再修脚本。
4. **负向证明**：故意破坏 SHA、路径、体积、许可证字段，确认门禁非零退出；不是只看“成功路径”。
5. **字节级协议夹具**：distcc 用固定字节序列验证桥的透明性；不解析协议。
6. **双轴 review**：Spec 逐条 + Standards（签名前注入、无版本递增、无死代码、许可证完整）。
7. **子智能体**：见第 5 节；主会话统一写。

如果环境提供 `/tdd`、`/code-review`，优先按其纪律执行。

## 2. 必读与事实基线

1. `AGENTS.md`、`CONTEXT.md`
2. `docs/reports/orchestration/fastlane-cross-lane-spec-20261021.zh-CN.md`
3. `docs/research/2026-10-21-cross-lane-reference-survey.md`
4. `.scratch/orchestella-fastlane/map.md`
5. 三张票：`P03-sealed-toolbox.md`、`P09-distcc-cross-lane.md`、`P11-build-ci-licensing.md`
6. 现有实现/报告：`fastlane-toolbox/**`、`runtime/fastlane-distcc/**`、`tools/fastlane-build/**`、`.github/workflows/fastlane-zero-device.yml`、`docs/reports/tickets/fastlane-P03|P09|P11/**`
7. 一手资料：
   - AOSP `guest/zipfuse/src/inode.rs`（`bin/` 默认执行位）
   - Android `zipalign`（签名前、`-P 16`）、16 KB 页面指南
   - BusyBox GPL-2.0、Alpine aports、LLVM license、distcc GPL-2.0
   - distcc `--inetd` 与协议字节（`DIST/ARGC/ARGV/DOTI` → `DONE/STAT/SERR/SOUT/DOTO`）
8. 跨会话契约（只读，不修改）：
   ```bash
   git fetch github feature/fastlane-dataplane
   git show github/feature/fastlane-dataplane:fastlane-payload/src/distcc_bridge.cpp
   git show github/feature/fastlane-dataplane:fastlane-payload/docs/protocol.md
   ```
   对比 P09 guest contract，产出差异表；不要改 B 的代码。

## 3. 工单与交付

### P03 — Sealed Toolbox（加固与可复现）

- 复核 v1 选型（静态 BusyBox）与 `fastlane-toolbox/manifest.json`：name/version/SHA256/license/source/target/deps/entries 与实际文件一致；
- 构建两次，比较产物 SHA256；固定下载 URL/校验和；记录 `SOURCE_DATE_EPOCH`；
- 验证 APK 根 `bin/` 注入：条目存在、mode 可执行、无 symlink 依赖、`zipalign -P 16`（无 zipalign 则 `SKIP` 并记录）；
- 负向测试：错 SHA、缺失条目、路径穿越、超大文件、许可证字段缺失都必须失败；
- 许可证：BusyBox GPL-2.0 对应源码交付方式写入 `NOTICE-fastlane.md`/报告；不整体搬 GPL 仓库；
- 体积报告：当前约 2.13 MiB，保持 ≤80 MiB 起步预算；新增工具必须单独决策。

### P09 — Distcc 跨车道（加固与契约）

- 现有宿主桥：补连接上限/空闲超时/半关闭/取消/关闭收敛测试；
- 定义并验证 guest `distccd --inetd` 契约：每连接一个 child，vsock ↔ stdin/stdout，stderr 不混入；
- 字节级夹具：固定 `DIST/ARGC/ARGV/DOTI` 输入，断言桥输出逐字节不变（透明桥，不解析）；
- 若宿主有 `distccd`/`distcc`，跑一次真实 C 编译/对象链接（host-only）；没有则 `not-tested`；
- 编译器一致性：v1 只 C + clang；同版本/target/flags；C++ `not-tested`；
- 与 B 的 `distcc_bridge.cpp` 对比，产出 `docs/reports/tickets/fastlane-P09/guest-contract-alignment.zh-CN.md`。

### P11 — 构建、CI 与许可证（加固）

- 校验 `.github/workflows/fastlane-zero-device.yml`：YAML 合法、job 只跑零设备、失败会阻塞；
- 若可用，跑 `actionlint`；没有则在本地用 Python/YAML 解析 + 负向脚本证明；
- `tools/fastlane-build`：跑全部单元测试；补“门禁失败”测试（错 SHA/缺许可证/超大体积）；
- payload 构建任务：B 的 `fastlane-payload/` 在本 worktree 不存在时，用最小 fixture 证明流程，并标注“待集成”；
- 不改版本号、不 tag、不发布、不改签名材料；CI 不引入明文密钥。

## 4. 代码优化授权（允许改现有代码，但有边界）

你可以：

- 在 `fastlane-toolbox/**`、`runtime/fastlane-distcc/**`、`tools/fastlane-build/**`、`.github/workflows/**`、`app/build.gradle.kts`、`NOTICE*`、`settings.gradle.kts` 内重构、删死代码、合并重复脚本；
- 修复构建竞态、校验遗漏、路径/权限问题；
- 为优化补测试，先红后绿。

你不可以：

- 为“风格统一”大范围重写已工作脚本；每次重构必须有行为理由或测试保护；
- 改 `runtime/engine-fastlane/**`、`runtime/fastlane-terminal/**`、`runtime/fastlane-capability/**`、`fastlane-payload/**`、`runtime/fastlane-proxy/**`、`runtime/fastlane-storage/**`、`runtime/engine-qemu/**`、`runtime/fastlane-telemetry/**`、共享索引/map；
- 在签名后修改 APK；不自动递增版本、不发布、不 tag。

## 5. 子智能体并行（最多 3 个，只读）

建议拆分：

1. **许可证/来源审计**：逐项核对 manifest/SBOM/NOTICE 与上游许可证、对应源码义务；输出缺口。
2. **AGP/zipfuse 构建审计**：核对 `bin/` 注入阶段、执行位、`zipalign -P 16`、16 KB 对齐；输出证据。
3. **distcc 协议夹具审计**：只读现有桥与测试，列未覆盖的字节/关闭/超时路径。

规则：子智能体只读、不改文件；主会话统一写；结论带文件行号/URL；不要并行改同一目录。

## 6. 收尾清理门（提交前必须执行）

1. `git status --short`；确认没有下载的 tarball、解包目录、构建产物、临时 APK。
2. 删除生成物（只在你拥有的目录内）：
   ```bash
   find fastlane-toolbox runtime/fastlane-distcc tools/fastlane-build \
     \( -name '__pycache__' -o -name '*.pyc' -o -name '*.class' -o -name '*.o' \
        -o -name '*.a' -o -name '*.log' -o -name '*.tmp' -o -name '*.rej' -o -name '*.orig' \) -print
   ```
   `build/`、`.gradle/`、`dist/`、`out/` 如由你产生也删除；**不要删除** `fastlane-toolbox/manifest.json`、`SBOM.spdx.json`、`NOTICE*`、测试 fixture。
3. `git clean -ndX -- fastlane-toolbox runtime/fastlane-distcc tools/fastlane-build` 预览；只删确认的生成物，不要 `git clean -fdx`。
4. 删除死代码/临时下载脚本/无断言测试；合并重复 helper。
5. `git diff --check` 无输出。
6. 文档检查：报告无 `TODO`/`待补`/过期数字；体积/SHA/测试数量与实际一致。
7. 敏感扫描：
   ```bash
   grep -RInE "BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|/root/|/home/|/Users/|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}" \
     docs/reports/tickets/fastlane-P03 docs/reports/tickets/fastlane-P09 docs/reports/tickets/fastlane-P11 || true
   ```
8. 最终 `git status --short` 只应包含你有意提交的文件。

## 7. 文档与脱敏

- 每张票更新 `docs/reports/tickets/fastlane-Pxx/implementation-report.zh-CN.md`：本轮改动、构建/测试命令、复现 SHA、许可证、未验证、风险。
- P03 更新 `fastlane-toolbox/README.md`/`manifest.json` 说明；P09 更新 guest contract 与差异表。
- 更新自己票的 `## Comments`/状态；不改共享索引/map。
- `docs/` 默认被忽略；只对具体文件 `git add -f`。
- 禁止写入真机序列号/IMEI/Android ID/设备指纹哈希/账号/真实 IP/MAC/个人邮箱/本机绝对路径/截图/完整日志/token/按键正文。

## 8. 提交、身份与推送

### 8.1 分支

```bash
git branch -m fastlane/agent-c-toolbox feature/fastlane-toolbox
git fetch github
git merge-base --is-ancestor github/feature/fastlane-toolbox HEAD && echo OK
```

不满足就停止，不要 force-push。

### 8.2 只暂存自己的文件

```bash
git add fastlane-toolbox runtime/fastlane-distcc tools/fastlane-build
git add app/build.gradle.kts .github/workflows NOTICE*
git add settings.gradle.kts
git add -f .scratch/orchestella-fastlane/issues/P03-sealed-toolbox.md
git add -f .scratch/orchestella-fastlane/issues/P09-distcc-cross-lane.md
git add -f .scratch/orchestella-fastlane/issues/P11-build-ci-licensing.md
git add -f docs/reports/tickets/fastlane-P03 docs/reports/tickets/fastlane-P09 docs/reports/tickets/fastlane-P11
```

### 8.3 身份

```bash
git config user.name "Nyzeep"
git config user.email "$(git log -1 --format='%ae')"
export GIT_AUTHOR_NAME="Nyzeep"
export GIT_AUTHOR_EMAIL="$(git log -1 --format='%ae')"
export GIT_COMMITTER_NAME="Nyzeep"
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
```

作者和提交者都必须是 Nyzeep。签名服务因作者不一致拒绝时，保持作者不变：

```bash
git -c commit.gpgsign=false commit -F /tmp/fastlane-c-commit.txt
```

### 8.4 中文提交信息

写入 `/tmp/fastlane-c-commit.txt`，至少包含：

```text
feat(fastlane-toolbox): 加固 P03/P09/P11 可复现构建、distcc 契约与许可证门

背景：
- 已有零设备 toolbox/distcc/CI 骨架，本轮补可复现性、负向门禁与字节级契约；
- 不发布 APK、不改版本号、不声称设备执行通过。

主要改动：
- P03：...
- P09：...
- P11：...

产物与许可证：
- ...

验证：
- python unittest/pytest：N tests OK
- 构建两次 SHA256：一致/不一致（原因）
- 负向门禁：非零退出
- distcc fixture：...
- Gradle/真机：not-run/not-tested（原因）

未验证：
- ...

清理：
- 删除 ...；最终 git status 仅包含 ...

风险/下一步：
- ...
```

### 8.5 推送

```bash
git log -1 --format='%H%n%an <%ae>%n%cn <%ce>%n%s'
git push github HEAD:refs/heads/feature/fastlane-toolbox
git ls-remote --heads github feature/fastlane-toolbox
```

只推自己的分支；不 push main、不 force-push、不 tag、不发布。push 失败保留 commit 并报告错误。

## 9. 完成报告（会话最后输出）

- 三张票状态
- 分支 + commit + push 结果
- toolbox 体积/许可证/复现 SHA
- distcc 契约/夹具结果
- CI/门禁负向证据
- 未验证/`not-tested`
- 清理证据
- 研究来源
- 与 P02 的契约对齐情况
- 风险与下一步

## 10. 停止条件

- 需要真机/adb → 停止，标 `not-tested`。
- 需要改其他 agent 文件 → 停止并报告。
- 需要 Root/unlock/pvmfw/SELinux 修改 → 立即停止。
- 许可证不清 → 该工具不进入产物，报告。
- 无法签名前注入 → 改用合法替代；不得签名后改包。
