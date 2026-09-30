# Ticket 26 全流程收尾 Dispatch（条件式）

- 票号：26
- 标题：第一垂直切片：创建 Alpine 实例并进入 shell（QEMU 档）
- 文档性质：当前策略会话生成的后续 dispatch；不是 review、验收或 completed 结论
- 生成日期：2026-09-23（Asia/Shanghai）
- 执行入口：当前 `ticket/26-linux-qemu-rebuild` worktree 的新 continuation session
- 主 workflow：`implement`，内部按 `tdd` 逐个 RED -> GREEN；异常先按 `diagnosing-bugs` 建立紧反馈环；收尾使用独立新会话执行 `code-review`
- 策略边界：Ticket 26 最远到“已提送、待用户签字”；不得启动 Ticket 27–37

## 0. 关系与当前结论

本文件是对既有 `implementation-tdd-dispatch-packet.zh-CN.md` 的续跑 dispatch，不替代
scope contract、AGENTS.md、项目 policy、用户明确授权、Ticket 26 工单或当前 Git 状态。
事实优先级仍以当前 repo / tracker / Git 实际状态、已批准 spec/ADR、active policy 和用户
当前消息为准。

当前已确认的前置成果：

1. 2026-09-23 已重新通过 baseline-aware ownership gate。准确表述只能是：

   ```text
   baseline-aware ownership gate passed; known external deletions remain intentionally dirty and excluded from Ticket 26 ownership.
   ```

2. 当前 branch 为 `ticket/26-linux-qemu-rebuild`，封存参考 HEAD 为
   `e0fc8b97ab6c8ba434fc8fcfa16ed1a87da1c12e`，与 `main` 的 merge-base 为
   `8859234a7cd922d4a99385874d1638f31e20c518`，相对 `main` ahead 2 / behind 0，
   无 upstream。101 项外部 deletion waiver 仍是 tracked unstaged deletion；另有 13 项
   tracked 修改与 37 个 Ticket 26 untracked 条目。worktree 是 dirty，不能称 clean。

3. 第 333 行 Maven checksum 诊断已解除：对固定 URL 的一次 direct GET 捕获了 138 bytes
   legacy `sha1sum` 响应；严格解析值、TSV 和现存 JAR 的 SHA-1 一致。离线 RED -> GREEN
   反馈环及负例已通过。旧停止记录仍保留，不得抹除或改写为“当时没有问题”。

4. 33 个明确 Android root 输入已经复制到批准 external root 的非 Git
   `source-mirror/`，逐文件源/目标大小与 SHA-256 一致，文件集合无缺项和额外项；输入
   清单为：
   `DroidLayer/artifacts/26/android-toolchain-build-20260922/manifests/source-mirror-inputs-20260923.tsv`
   ，清单 SHA-256 为
   `0250f14135d50098286fd89e2259b4325fb9aae8c8650a0e7ecbe389195047c1`。

5. 镜像核验不等于 A 阶段通过。完整 SDK/Maven resolution closure、真实 Gradle 配置、
   B 构建与离线重跑、C QEMU/NDK/APK/GPL/CI、D arm64 真机七项 AC、E 独立双轴 review
   仍未通过。后续会话必须先重新核验当前 gate 和镜像输入，再按阶段推进。

## 1. 总体执行规则

本 dispatch 的目标是让主会话在所有门禁通过时连续完成 Ticket 26，而不是每一步都返回
等待策略会话。主会话可以在同一轮内从 A 推进到 E，但只能在前一阶段有真实 verdict、输入
闭合、输出隔离和报告更新后进入下一阶段。

任何以下情况均为 fail-closed：

- freshness / ownership gate 与当前状态不一致；
- 官方大小、官方 checksum、本地 SHA-256 或签名任一缺失或不匹配；
- 请求超出当前阶段 endpoint allowlist；
- 出现 android-sdk-license 之外的 SDK license 接受门，或其他未授权 license 组件；
- offline 阶段出现网络访问；
- 需要创建或修改 `gradle/verification-metadata.xml`；
- 任何 Gradle、插件、init script、任务或工具将输出写回真实 source worktree；
- 需要清理或修改 101 项外部 deletion、既有 TEMP、历史 worktree、Ticket 25 资产或用户/系统 SDK/cache；
- 真机不可用、任一 AC 无法观察、只能用 emulator/mock/推断替代；
- 独立 Standards 或 Spec review 任一轴 request-changes；
- 需要修改 ADR、spec、CONTEXT、产品暴露面或 Full Linux Contract；
- 需要未经批准的 remote、keyserver、container registry、APT snapshot、GitHub、CI secret 或其他 endpoint；
- 发现不确定的 host 数据出站、凭据读取或工作区扫描。

停止时必须：

1. 不绕过、不换源、不重试造成更多污染、不删除证据；
2. 保存最小脱敏证据到批准 external root；
3. 更新 implementation report，区分“已验证 / 未运行 / 待用户决定”；
4. 必要时在 `DroidLayer/temp/` 新建 TEMP handoff，声明与旧 TEMP 的关系及
   `cleanup owner: user/orchestrator`；
5. 停止并交回策略会话，不把部分成功写成 Ticket 26 完成。

## 2. 强制读取与第一次 gate

正式执行前必须读取：

- 当前 worktree `AGENTS.md`；
- `docs/agent-handoff/ENGINEERING_HANDOFF.md`；
- `docs/agent-handoff/DEFENSIVE_CONSTRAINTS/README.md` 及其全部 policy；
- `HOST_ENVIRONMENT_POLICY.md`、`ARTIFACT_ORGANIZATION_POLICY.md`、
  `ORCHESTRATION_GOVERNANCE_POLICY.md`、`QEMU_JNI_SUPPLY_CHAIN_POLICY.md`、
  `REPORTING_STANDARD.zh-CN.md`、`TICKET_EXECUTION_GUIDE.md`、`AGENT_WORKER_CONTRACT.md`；
- Ticket 26 工单、scope contract、当前 dispatch、implementation report；
- `CONTEXT.md` 与触及模块相关的 ADR；
- `ask-matt`、`diagnosing-bugs`、`implement`、`tdd`、`code-review` 的实际技能说明；
- 外部 `DroidLayer/temp/` 中本票 waiver、license acceptance、checksum stop、Gradle
  isolation stop 等既有 TEMP。TEMP 只作交接事实，不替代当前 Git/policy。

然后重新运行只读 freshness gate。必须核对：

- registered worktree 与物理路径；
- branch、HEAD、merge-base、ahead/behind、upstream；
- 最近提交；
- staged、tracked diff、untracked、ignored、`git diff --check`；
- 101 项 waiver 与封存文件逐路径、逐状态一致；
- 例外之外的 13 个 tracked 修改与 37 个 untracked 是否仍属于 Ticket 26 ownership；
- `source-mirror/` 是否存在、是否仍恰有 33 个文件、输入清单 hash 是否匹配；
- 33 个源文件当前 hash 是否仍与既有镜像 manifest 一致。

若 gate 不通过，不能使用第 0 节的历史 PASS，不能先清理或修复；立即停止。

## 3. 精确写入、读取与禁止范围

### 3.1 Ticket 26 允许写入

允许写入当前 Ticket 26 ownership 内：

- `app/**`；
- `guest-assets/` scope contract 明确的窄例外及直接对应测试；
- `runtime/contract/**`、`runtime/distro/**`、`runtime/instance/**`、
  `runtime/capability/**`、`runtime/engine-qemu/**`；
- `tools/qemu-android/**`；
- 仅本票 CI 文件 `.github/workflows/qemu-android.yml`；
- 本票 tracker 文件 `.scratch/droidlayer-platform/issues/26-impl-first-slice-alpine-shell-qemu.md`，
  仅用于本票状态/验收记录；
- `docs/reports/tickets/26/implementation-report.zh-CN.md`；
- `docs/reports/reviews/26/review-report.zh-CN.md`，仅由独立 reviewer 写入；
- 小型、脱敏、长期可审查的 Ticket 26 evidence，按 artifact policy 放入
  `artifacts/26/**`；
- `DroidLayer/temp/` 下本次新建的 TEMP handoff，cleanup owner 为 user/orchestrator。

本文件本身是策略会话已写入的 dispatch 文档；后续会话不得重写它来隐藏失败记录。

### 3.2 外部 artifact root

全部 SDK、Gradle、Maven、NDK、QEMU archive、解压目录、cache、构建输出、APK、设备日志、
原始验证日志、临时 fixture 与大体积 evidence 只能位于：

```text
D:\Development project(开发项目)\DroidLayer开发目录\DroidLayer\artifacts\26\android-toolchain-build-20260922
```

每个新输入/输出要记录用途、来源、精确版本、endpoint、大小、官方 checksum、本地 SHA-256、
是否可复现、保留责任和 cleanup owner。生成物不进入 source tree，不提交到 Git。

### 3.3 永久禁止

任何情况下不得：

- 恢复、删除、重命名、暂存、提交 `.agents/skills/**` 或 `skills-lock.json`；
- 读取或修改历史 worktree 内容：`ticket-26-alpine-shell-qemu`、`ticket-26-canonical`、
  `Nyzeep/ticket-26-device-gate-followup`；
- 写入 `DroidLayer_dev\.toolchains\**`；
- 写入用户级/系统 SDK、Gradle cache 或系统级资源；
- 写入或创建 `gradle/verification-metadata.xml`；
- 写入、修改、清理 Ticket 25 资产根；
- 使用 `git clean`、`git reset --hard`、`git checkout --`、批量删除或递归清理；
- 删除既有 TEMP、旧 evidence、旧 cache、旧 SDK 或来源不明的 external artifact；
- 修改 ADR、spec、CONTEXT、其他 Ticket 或下一阶段 tracker；
- 用 `git add -A` 把 waiver deletion、无关 dirty 或 generated output 带入提交。

## 4. 子代理与并行计划

主会话可以开启子代理，但必须遵守最大 delegation depth 2。每个 child 必须有窄 scope、
明确读写集、输入、输出、验证和 cleanup；child 不得继续派生、merge、push 或修改报告。

可并行的 sidecar 仅限不共享写集的任务：

1. **Android closure auditor**：只读当前 build files、source-mirror、隔离 root manifest
   与 Gradle resolution 证据；输出闭包缺口列表到 external root；不改 source、manifest、report。
2. **Guest-assets verifier**：只在 external root 设置独立 cache/output，验证 guest-assets
   Gradle suite、shell syntax 和已有 smoke harness；如需产品修复只回报，不直接改共享文件。
3. **QEMU supply-chain auditor**：只读 `tools/qemu-android/`、policy 与 external inputs，
   核对 lock、签名、hash、ABI、GPL evidence 缺口；不下载、不改 lock、不写 workflow。
4. **Android packaging/static auditor**：只读 app manifest、Gradle、native layout、
   runtime artifact manifest 需求，输出 APK/W^X/16 KB 检查清单；不改 app。
5. **Documentation/evidence auditor**：只读报告规范、当前报告、证据目录和状态，指出
   缺少的字段；不修改 implementation report。

不能并行的任务必须由主会话串行：

- 同一 Gradle user-home、project-cache、SDK root 或 build output 的操作；
- manifest 生成和最终 closure 判定；
- native build、two-clean-build、APK 打包；
- ADB/真机安装与七条 AC；
- implementation report、tracker 状态、commit；
- 独立 review。

## 5. 阶段 A：真实 SDK/Maven 闭包与隔离 Gradle probe

### 5.1 输入与固定版本

固定：

- compile/target SDK 36；
- Android Build-Tools 36.1.0；
- AGP 8.13.2；
- Kotlin 2.2.21；
- JUnit 4.13.2；
- Java 17，JDK `D:\JDK17`，BellSoft `17.0.10+13-LTS`；
- 本地 Gradle `gradle-8.13-bin.zip`，136,983,045 bytes，SHA-256
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`。

Gradle 只能使用已验证的本地 distribution；禁止 wrapper 下载和 wrapper fallback。

### 5.2 镜像运行原则

以已核验的 `source-mirror/` 作为 `--project-dir`，不能从真实 source worktree 运行 Gradle。
构建前后重新核对真实 source worktree 的 status、ignored、untracked、`git diff --check` 和
33 个源文件 hash。所有下列路径都必须在 approved external root：

- `--project-dir` 与镜像 build output；
- `--gradle-user-home`；
- `--project-cache-dir`；
- `TEMP`、`TMP`、`JAVA_TOOL_OPTIONS` 中的 `java.io.tmpdir`；
- `HOME`、`USERPROFILE`、Gradle logs；
- SDK root、Maven cache、manifest、evidence。

显式使用 `--offline --no-daemon --no-problems-report`。`--no-problems-report` 只是纵深防护，
不能代替 source-mirror 隔离；如果 Gradle 仍然写出镜像以外的文件，立即停止。

### 5.3 闭包要求

不能复用旧的 441 行 discovery TSV，也不能把 561 个 cache 文件库存量当作依赖闭包。必须
从当前 build files、实际选中的 configurations、plugin marker、implementation POM、
Gradle module metadata、任务懒加载输入和 SDK package resolution 建立新的闭合清单。

每行至少记录：

```text
kind
coordinate or SDK package
fixed version
exact endpoint
filename
official size
official checksum and algorithm
local SHA-256
local path in isolated root
license reference
resolution source/configuration
```

清单必须覆盖实际运行任务会用到的所有 Maven artifact/metadata 与 SDK package，而不是只覆盖
已枚举的 selected module。若实际 Gradle resolution 需要新的坐标或 metadata，按允许 endpoint
获取并校验；若 endpoint、license、大小或 checksum 不符合，停止。

Android SDK metadata 中已接受的范围只包括 `android-sdk-license` 下的已授权组件，包括
`platforms;android-36` revision 2、`build-tools;36.1.0` 及 manifest 中同一 license ref 的
官方包。任何其他 license ref 必须停止请求单独授权。禁止 `sdkmanager --licenses`、伪造
license 文件和复用用户级/系统 SDK licenses。

### 5.4 A 的通过定义

A 只有在以下全部成立时才通过：

- source-mirror 与当前 source worktree 的 33 个输入仍逐文件一致；
- 实际任务配置和 resolution 已成功完成；
- SDK/Maven 闭包清单完整且每行官方大小/checksum 与本地 hash 分栏一致；
- 所有请求只在允许 endpoint；
- 没有写入真实 source worktree、`.git`、用户/系统 cache 或禁止路径；
- 失败 probe、日志、manifest 和验证摘要全部位于 external root；
- 报告中明确旧清单被废弃、当前清单的 hash、命令、环境和未覆盖风险。

A 未通过不得进入 B。

## 6. 阶段 B：Android、guest-assets 与离线重跑

A 通过后，使用同一个已验证 external cache 和同一 source-mirror，按 `implement + tdd` 处理
真实暴露的产品缺陷。每个修改先写或确认正确 seam 的 RED，再做最小 GREEN；不为凑测试改写
历史证据或扩展 scope。

必须运行并记录真实 verdict：

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

同时运行更新后的 guest-assets Gradle suite、shell syntax、既有 Ticket 26 smoke harness
及其必要的 checksum rejection / provisioning / restart / bridge / terminal 验证。guest-assets
测试使用独立 external output，不把 build/cache 写入 source worktree。

然后以相同已验证 cache 执行同一任务组：

```text
--offline --no-daemon --no-problems-report
```

离线阶段任意网络访问、缺失输入、写出隔离 root、测试失败或无法生成有效 verdict，均停止。
旧日志不得替代本轮命令输出。

## 7. 阶段 C：NDK/QEMU Android、APK、供应链、GPL 与 CI

只有 B 通过后进入 C。固定输入：

- NDK r28c，revision `28.2.13676358`；
- NDK archive URL `https://dl.google.com/android/repository/android-ndk-r28c-linux.zip`；
- NDK official metadata SHA-1 `a7b54a5de87fecd125a17d54f73c446199e72a64`；
- NDK local SHA-256 `dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f`；
- QEMU 11.1.1 archive URL `https://download.qemu.org/qemu-11.1.1.tar.xz`；
- QEMU archive SHA-256 `079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482`；
- QEMU detached signature URL `https://download.qemu.org/qemu-11.1.1.tar.xz.sig`；
- signer fingerprint `CEACC9E15534EBABB82D3FA03353C9CEF108B584`；
- tag `v11.1.1`，commit `c3d48b7d1e89604920e5b81b91140c2ad39a1943`；
- target only `aarch64-softmmu`；
- APK ABI only `arm64-v8a`；
- expected APK entry `lib/arm64-v8a/libdroidlayer_qemu.so`。

除三类 Android/Maven endpoint 外，C 只允许上述两个 QEMU official URL；不得自行访问
keyserver、container registry、GitHub source mirror、APT snapshot 或其他 endpoint。若 detached
signature 验证需要未缓存的 public key，且当前没有精确授权的获取路径，停止而不是猜测或仅凭
fingerprint 字符串宣称通过。immutable builder identity、NDK provenance 和 source record
必须全部可审计；一个层级不能替代另一个。

C 必须真实验证：

1. QEMU Android native build；
2. two-clean-build reproducibility；
3. AArch64 ELF、DT_NEEDED/动态依赖闭包与 runtime data closure；
4. 16 KB page-size 兼容性；
5. APK `arm64-v8a` layout、zipalign、apksigner、nativeLibraryDir 执行与 W^X；
6. runtime artifact manifest 的完整性与 tamper rejection（QEMU、kernel、initramfs、dependency）；
7. QEMU GPLv2、LICENSE、NOTICE、QEMU-BUILD-INPUTS、对应源码记录和 release gate；
8. 仅修改 `.github/workflows/qemu-android.yml`，实现 policy 要求的 provenance、build、
   closure、16 KB、APK、tamper、GPL 和 arm64 device job。CI 不得以 skipped-as-success 替代门禁。

任何一项未验证都不能写成 C 通过。

## 8. 阶段 D：arm64 真机七项 AC

只有 C 通过后进入 D。先读取并记录授权真机的脱敏设备能力：arm64 ABI、RAM >= 3 GB、ADB
可用、USB debugging 已开启。若真机不存在或 adb 不可用，D 立即停止；不得用 emulator、
QEMU host smoke、JVM/mock 或推断代替。

guest 输入固定使用 Ticket 25 资产根，只读，`SHA256SUMS` 12/12 必须通过；Alpine 3.24.1
aarch64 minirootfs 必须为 4,023,732 bytes、SHA-256
`f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259`。Ticket 25 资产根不得写入。

逐项记录实际观测：

1. 无 Root 安装应用、选择 Alpine、进入 Full Linux shell；
2. tarball checksum 错误在创建前拒绝并给出明确原因；
3. 首次 Provisioning、Guest Agent/Bridge 正常启动；
4. 正常 root shell 与 Guest Bridge v1：握手、版本、token、错误 token 拒绝、ping、terminal、resize；
5. guest 正常关机后，以同一 Root Volume 二次启动，验证数据保留；
6. 性能徽章、正常 RUNNING/STOPPED 持久化、Activity/UI 重建及模拟器提示；
7. APK/nativeLibraryDir/QEMU runtime integrity、无 Root、无 exec/W^X/SELinux denial 的设备证据。

任一 AC 无法实际观察，保留已完成证据并停止，不降级为“部分通过”。

## 9. 阶段 E：独立双轴 review 与验收提送

A–D 全部真实通过后，冻结 implementation diff 与证据索引。实现主会话不得自审。必须创建
一个全新的、独立于实现上下文的 reviewer session/agent，固定 review point 为
`8859234a7cd922d4a99385874d1638f31e20c518`，执行 `code-review` 两轴：

- Standards：AGENTS、CONTEXT、ADR、scope ownership、隔离/出站、QEMU-JNI、artifact/GPL、
  license、测试真实性与 Git 治理；
- Spec：Ticket 26 每条 acceptance criterion、A–D 证据与未覆盖项。

review report 写入 `docs/reports/reviews/26/review-report.zh-CN.md`。任一轴 request-changes
即停止；不能由实现会话自行改成 approve。若 review 后修改了受审内容，相关门禁和独立 review
必须重新运行。

两轴均 approve 后：

- implementation report 完整列出背景、scope、实际完成、未完成、修改模块、测试、风险、
  evidence、commit、状态；
- tracker 只推进到 policy 允许的 `review`/“已提送、待用户验收”，不能在用户签字前标 `completed`；
- 写一份新的 Ticket 26 TEMP handoff，声明与所有旧 TEMP 的关系和 cleanup owner；
- 交回策略会话，等待用户签字，不启动 Ticket 27–37。

## 10. 整理、cleanup、commit 与 push

### 10.1 清理规则

“项目清理”仅限本票本轮创建且经核对可再生的临时 fixture/临时日志，且目标必须在 approved
external root；保留长期 evidence、manifest、hash、失败日志和 review 所需证据。不得：

- `git clean` 或递归删除；
- 清理 101 项 waiver、既有 TEMP、历史 worktree、Ticket 25 资产、旧 SDK/cache；
- 为了让 status 更好看而删除 dirty/untracked 证据；
- 把 source-mirror 或大型 raw artifact 提交到 Git；
- 重命名现有 canonical report 或建立平行的“final/new/copy”文件。

每个清理动作记录实际路径、类型、hash/大小、原因和 cleanup owner。无法判断是否可删时保留并报告。

### 10.2 Commit

只有实现、测试、A–D 门禁、独立 review 和报告均完成后，才允许准备 commit。逐路径显式
stage Ticket 26 owned 文件；禁止 `git add -A`。stage 前后核对：

- 101 项 waiver 不在 staged diff；
- 不包含 source-mirror、SDK、cache、APK、TEMP、raw logs、ignored output 或其他 ticket；
- `git diff --cached --check` 干净；
- commit 使用符合治理 policy 的 Conventional Commit，并含 `[ticket-26]`；
- 不改写历史、不 squash 已完成提交、不 merge main。

### 10.3 Push

本 dispatch 不自动推送。先只读核验 `git remote -v`、目标 ref、远端历史和 fast-forward 条件，
将精确 remote URL、目标 branch、拟执行命令、网络 endpoint 与风险交回 user/orchestrator。
没有单独的 remote/目标/endpoint 批准时，不添加 remote、不 fetch、不 push、不创建 PR，也不能
声称已推送。若策略会话另行批准，仍禁止 force push。

## 11. 报告格式与最终回传

每阶段完成后追加 implementation report，至少包含：

- 阶段、日期、session/agent role；
- 实际命令与环境摘要；
- input path、版本、endpoint、官方 size/checksum、本地 SHA-256；
- 输出路径、大小/hash、是否可复现；
- ✅ 已验证、⚠️ 部分验证、❌ 未通过、⏸ 未运行；
- ownership、隔离、license、网络和 cleanup 结果；
- 子代理 scope、结果和复核结论；
- 未完成、风险、下一步与是否触发 Architecture Change Policy。

最终回传必须明确回答：

1. A、B、C、D、E 各自是否通过，不能使用模糊的“基本完成”；
2. 每条 AC 的证据路径和真实命令；
3. 哪些测试、CI job、设备观察或 review 没有运行；
4. 是否产生任何 source worktree 写入、ignored 文件或 endpoint 越界；
5. 哪些文件被修改、哪些文件显式未触碰；
6. commit hash、是否 push、remote 是否经批准；
7. Ticket 当前是 active、review、已提送待验收还是 completed；
8. 下一阶段前必须由用户签字的事项。

本 dispatch 的正确终点是“Ticket 26 已提送、待用户签字”，不是自动启动下一阶段。

报告人：策略会话 ｜ 日期：2026-09-23
