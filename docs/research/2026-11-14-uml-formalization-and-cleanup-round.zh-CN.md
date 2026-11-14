# UML 正式化攻坚与大清理轮报告（2026-11-14）

> 上游交接：`UML正式化攻坚与大清理轮-交接开工Prompt-20261114.md` + `handoff/交接文档-20261114-uml编译路由与正式化.md`。
> 执行形态：主会话 + 四条车道子智能体（Lane A 设备道 / Lane B 只读调研道 / Lane C 文档归档道 / Lane D UI 正式化道），全程 review-merge-敏感门-推送收口。

## 0. 一句话判词

**Task A 编译路由 E2E PASS**（真实编译任务端到端 3 次全通）→ **Task B 主页 UML 正式化落地并真机走查三项 PASS** → **Task D 18 份历史报告归档四桶** → **Task C 批次① 回收 52G** → **CI 考古纠错**（交接「全绿」不实，夹具 bug 连红已修）→ **main = a5b01dda 全绿**。

## 1. Task A · 编译路由 E2E 攻坚（Lane A，PASS）

**根因不是一层，是四层叠加**（上轮「UML seccomp profile」疑点排除）：

| # | 层 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | 提取树根（主因） | 提取器跳过 tar 根条目 `./`，根目录落 host umask 0700；hostfs 原样呈现，uid 100 连 `/tmp/distccd.log` 的路径穿越都被拒（strace 铁证：`openat("/tmp/distccd.log", O_WRONLY\|O_CREAT\|O_APPEND, 0666) = -1 EACCES`，/tmp 本身 0777——上轮「三种 /tmp 形态全部复现」的墙其实在 `/`） | 根条目 mode 经 `applyModeBestEffort` 落盘 + `ensureRootTraversable` 遗留树修复 |
| 2 | umnet | 硬编码 passt `-t none`，D-1 lane 拨号目标 127.0.0.1:3632 无监听 | umnet pin 重打：`-t 127.0.0.1/3632`（仅回环转发、无 UDP 入站、无 LAN 暴露；passt pin 逐字节不变） |
| 3 | hostfs 属主映射 | 文件属主呈现为 host 侧 uid，guest uid 100 是 "other"——distccd 自建 0600 作业文件的 `O_TRUNC` 重开即 EACCES（strace：O_EXCL 成功→reopen 失败）；chmod 整树救不了，须 owner-match | launcher v2.2：`stat` 树取 uid:gid、/etc/passwd 幂等建户 `hostfs`、`--user hostfs` |
| 4 | 回环绑定 | `getLoopbackAddress()` 在该设备返回 `::1`，submitter 绑成 tcp6-only、lane 拨 `::1:3632` | `LOOPBACK_V4=127.0.0.1` 显式 IPv4（faceChannel 拨号 + bindFabric 绑定两处） |

**E2E 结果**：`3633 → dispatch → 账本扣放 → face 通道 127.0.0.1:3632 → passt → guest distccd → gcc → .o 回传落盘`，同会话 2 次 + 跨重启 1 次全部 stat=0。

**验收五项全中**（证据在 `/d/t/round-20261114/laneA/`）：
1. `uml-face channel wired: uml-tcp lane 127.0.0.1:3632`（e2e-logcat-keylines.log）
2. `fabric submitter listening 127.0.0.1:3633`
3. 账本恒等 `host-native=0 uml=1 qemu=2 sum=3 ledger-budget=3`
4. ConnectionStats `outcome=COMPLETED bytes=249/1468 failureCode=NONE`（双向 >0）
5. `e2e-object.o`（1408B，ELF 魔数 7f454c46）+ guest 侧 distccd.log `COMPILE_OK exit:0`

**合入**：`ceaef694`（umnet pin 重打）→ `e5729c6b`（tar 根 mode + 遗留树修复 + launcher v2.2）→ `6b49412e`（LOOPBACK_V4）；测试基线 1649→1653（Windows 本地双变体 1653/0；新增 3 组契约测试）。

## 2. Task B · 主页 UML 正式化呈现（Lane B 调研 + Lane D 实现，PASS）

Lane B 信息架构现状报告（13 条对照表）→ Lane D 以其为蓝本实现（`10df0532`）：

- **#1** 引擎条静态文案「UML 实验」→「UML」（资源化 `engine_uml_segment`）。
- **#2（关键）**：运行时双态文案改**状态驱动**——`UmlFaceStatusRegistry` 新增 `channelWired` 事实（缺省恒 unwired，不冒充已接），`UmlFaceRideAlong.arm()` 把已算出的 wired/unwired 随 face 生命周期登记；纯映射器 `UmlSegmentCopy` 分流：运行中+wired=「UML · 运行中」/unwired=「UML · 运行中 · 编译通道未接线」/未运行=「UML · 未启用」。
- **#4** 双门标题→「UML 编译面」；**#5** rootfs 卡红 tag「实验 · UML」→「UML」；**#6/#8** 卡描述与 READY 尾句按事实改写（provisioner 已注入 um-command.sh，「本轮不可直接启动或编译」已失实）并按构建形态 flag 分流（wired=「可随实例启动供 hostfs 通道」/unwired=「编译通道未接线的构建不消费该树」）。
- **#9** 设置页副文案补「UML 编译面同受此开关控制」（IA 缺口修复）；**#10** strings.xml 新增 engine_uml_* 资源族 19 条全量替换硬编码；**#11** `(no submitter yet)` 过时措辞移除；**#12** README 四车道行翻转为「可路由 · 编译通道已接（2026-11-14 E2E：真实编译任务端到端 3 次全通）」+ 新增本轮小节与遗留边界。
- **裁决①（主会话落实，`a5b01dda`）**：双门正文两串扫「实验」二字，与标题口径一致。
- **不做**：#13 NOTICE.UML-DRAFT 正式化（release 流水线工单，资产入包门槛独立）。

**测试**：双变体 1656/0（基线 1653 只增不减；新增注册表通道事实生命周期 + 段文案双态映射契约）、lint 0 error、README 资产门过。

**真机走查三项 PASS**（device-b，QA 包 `v0.6.3-qa.umlroute2.a5b01dd` 实机截图）：
1. 引擎条第三段「UML · 未启用」（摘「实验」）；
2. rootfs 卡 tag「UML」+ 描述注入事实 + READY 态 wired 尾句（「已验证+已解包 519 项 · 已注入 UML 启动脚本（um-command.sh），可随实例启动供 hostfs 通道」）；
3. 设置页 pVM 编译加速卡副文案含「UML 编译面同受此开关控制」。
（对话框标题为同族资源串，契约测试覆盖，未做破坏性操作走查。）

**Lane D 待裁决处置**：①已落实；②README.en.md 同步（上轮即落后）→独立工单；③about_app_desc 补 UML→backlog；④README 路线图最小同步→接受；⑤NOT_READY 态 READY 同粒度 flag 分流→遗留（「可」字条件式表述在 Lane B #7 判词内）。

## 3. Task C · 全局大清理（批次① 已执行，其余待裁决）

- **用户裁决（AskUserQuestion）**：仅批准批次①。
- **批次① 执行**（铁律四步：清单落盘→核查打印→独立删除→删后复查）：`开发目录/Orchestella/{artifacts,test-runtimes,temp}`（旧轮构建产物堆，非 git 仓库）——**df 实收 +52G**（110G→162G 可用）。清单：`/d/t/round-20261114/cleanup-batch1-manifest.md`。
- **待裁决批次**（du 实测）：②archive+tools 5G；③/d/t 大块 uml-fab 16.0G + llvm-android 12.3G + orch 3.65G（删前须存 3 个未提交 diff）+ llvm-cache 0.35G；④⑤Orchestella_dev/.scratch 二十目录 + /d/t 杂项（tinycc/release-063/orch-msgrewrite/round-20261111 等）。
- **Lane B 引用核查两修正**：**Orchestella_dev 须保留**（2 个活 QA 脚本绝对路径引用）；**u1-pinned/gate2-net 的「仓内零引用可删」判词不采纳**（交接文档点名的 QA 构建依赖，仓库 grep 看不见命令行参数依赖）。`/d/t/llvm-android` 可删（仅 build.sh Windows 可覆盖默认值 + README 文档行引用，无活代码/工单/运行期依赖）。

## 4. Task D · 远端仓文档策展（Lane C，已合并）

- 用户批准全量归档：**18 份** 2026-09/10 历史报告 git mv 入 `docs/research/archive/` 四桶（2026-10-avf 6 / 2026-10-phase5 并入+4 / 2026-10-reference-surveys 6 / 2026-10-uml-origins 2），**public 仓零删除**（git 历史相似度 97-100% 保留）。
- 同轮修全仓入链 **117 处/65 文件**（docs/README 研究依据节、agent-handoff、reports、.scratch 旧票含 hw-virt 十票、ACR-draft 与 pvm-fabric-route 等 A 类互链、被移动文档出链）；双向链接落点检查 514 条 markdown 相对链接零断链。
- A=保留 36 份；C=0（无真重复）；qemu-mttcg/perf-plan 因活代码 javadoc 引用留 A 不动。
- 执行载体：临时 worktree 独立分支（`chore/docs-research-archive-20261114` @ `6c7e998a`），review-merge 后 worktree 已清。

## 5. CI 考古（主会话，重要纠错）

- **交接文档「测试基线 1649 双变体锁定、全程 CI 绿」不实**：实测 CI 从 `c71b688d`（launcher v2.1）起连红——前会话在 CI 完成前就结束了。
- `c71b688d` 的红 = FastLaneProxyServerTest 已知 flake；`f9016cc8`/`6c7e998a` 的红 = **确定性测试夹具 bug**：`UmlRootfsProvisionerContractTest` 的 `TarSpec.file()` 默认 tar 0644，断言却要求 0755 条目携带 OTHERS_EXECUTE——逻辑上永假。**Windows 本地 POSIX 断言被 assumeTrue 整体跳过，本地 1653/0 从不暴露，CI Linux 必挂**。
- 修复（`3cae5a8a`）：夹具改 `TarSpec.fileMode(0755L)`，语义对齐；CI 全绿确认。`lateSupersession`（InstanceLaunchCoordinatorTest:755）为偶发时序 flake，修复后未复发。
- 副产物：Task A 的 umnet 重打 pin 使 QA 构建必须用新 netHelperDir（`/d/round-20261114/laneA-netfwd`，gate2-net 原件保留）——walkthrough 构建首跑 sha 失败即此因。

## 6. main 终态（a5b01dda，全部 review-merge-敏感门-推送-CI 绿）

`f9016cc8` → `6c7e998a`(Task D 归档) → `ceaef694` → `e5729c6b` → `6b49412e`(Task A 修复链) → `3cae5a8a`(CI 夹具修复) → `10df0532`(Task B 正式化) → `a5b01dda`(裁决①正文扫「实验」)。分支/worktree 全清，本地=远端。

## 7. 证据索引（仓库外）

- `/d/t/round-20261114/laneA/`：LANE-A-REPORT.md、strace 捕获、E2E logcat 键线、账本快照、`.o`、launcher v2.2 exact、QA 构建日志
- `/d/round-20261114/laneA-netfwd/`：重打 umnet_bionic + passt_bionic（QA 构建 netHelperDir）
- `/d/t/round-20261114/laneB-report.md`：IA 现状 13 条对照表 + 策展 56 份分类表 + 引用核查
- `/d/t/round-20261114/laneC-report.md`：归档明细（旧路径→新路径→逐文件改链）
- `/d/t/round-20261114/laneD-report.md`：正式化逐项落地 + 五项待裁决
- `/d/t/round-20261114/cleanup-batch1-manifest.md`：清理裁决与实录
- 真机走查截图：MCP screenshots（2026-11-14T07-19~07-21，device-b）

## 8. 新坑记录（本轮新增）

1. **Windows assumeTrue(POSIX) 跳过 = CI Linux 盲区**：本地全绿的 POSIX 断言测试在 CI 上可能从未真正跑过；推送后必须等 CI 完成再断言绿（前会话即栽于此）。
2. `TarSpec.file()` 默认 0644——写 0755 语义断言的测试必须用 `TarSpec.fileMode(…, 0755L, …)`。
3. 敏感门必须**从仓库目录内**运行（`GitRepo.discover()` 基于 CWD），仓外运行一律 fail-closed 报 `scanner_error`。
4. `cmd //c '单串引号'` 删目录被 MSYS 引号转换打挂（error 123）；须 `MSYS_NO_PATHCONV=1 cmd /c rmdir /s /q "D:\path"`。
5. adb server 版本混用（v32/v41）致 `unknown host service`：kill-server + 用统一 client 重启。
6. MSYS `grep -r` 递归存在静默漏报；引用核查类任务以 python os.walk 全树扫描为权威（`/d/t/` 作为 grep 模式会被 MSYS 路径转换吃掉，须写 `/d[/]t/`）。

## 9. 遗留与下轮建议

1. **编译路由产品化收尾**（下轮主线）：fabric-route 可路由性已实证，剩 guest 工具链预装策略（gcc 154MiB 未进 launcher，设备侧一次性装好且树持久）、apk 在 hostfs 的硬链语义（Android 禁 app linkat，symlink 绕过）、umnet/JVM 3632 常量耦合、QEMU 侧客户端链路未测。
2. **Task C 批次②③④⑤** 待用户裁决（§3 清单；执行时 orch 删前须存 3 个未提交 diff）。
3. **README.en.md 同步**（独立工单）；about_app_desc 补 UML（backlog）；NOT_READY 态 READY 同粒度 flag 分流（遗留）。
4. **NOTICE.UML-DRAFT → NOTICE.UML 正式化**：release 流水线工单（UML 资产真实入发布 APK 时同步提交）。
5. console 多行并帧（umnet 泵把多行并一帧）——正式化后续 pump 行拆分。
6. 16K 宿主 / 多设备矩阵：继续开放。
