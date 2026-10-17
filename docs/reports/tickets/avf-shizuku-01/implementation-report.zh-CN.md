# Ticket avf-shizuku-01：清洁检出 Guest 准备与严格字节校验

- 票号：`.scratch/orchestella-avf-shizuku/issues/01-clean-guest-generation.md`。
- 执行者：C / ZCode / new-provider/gpt-6.1-sol；日期：2026-10-15。
- 分支 / 基线：`main@428860ad9a1cb335f8be78c157b2ff57faf2a6da`；没有新建 branch、worktree、commit 或 push。
- 状态：**ready-for-review**。精准 CLI 与入口 fixture 已通过；主协调随后在 WSL 统一验证完整 Guest 65项、新增 sync 10项、builder Python 6项及 canonical strict check 均通过。Gradle / 最终 APK、独立审查仍待主协调收口，未运行云 CI 或设备验收。
- 外部证据实际根：`LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/research-defects/implementation-01/`（下称 I）。本批已先建在 research-defects 下，主协调明确要求原件保留不移动；没有为目录形式重做测试。

## 1. 任务目标与依据

按正式研究 `docs/research/2026-10-15-avf-shizuku-upgrade.md` §8、当前 effort 的 scope/spec 和 01 票，解除 clean checkout 缺 ignored `app/src/main/resources/guest_init` 时的 Guest CI 阻塞，同时保留 canonical 漂移失败门。

用户已明确授权直接实施，并把测试 seam 固定为既有 Guest CLI 的真实子进程。本地 `PROJECT_ROOT/.agents/skills/tdd/SKILL.md`、tests.md、mocking.md 已读取；Skill 工具因项目/全局同名歧义被拒，随后按项目本地版本逐片 red→green，没有重新询问已确认的 seam。`GLOSSARY.md` 不存在；已继承研究阶段完整政策、CONTEXT、ADR 的约束，不重做广泛研究。

## 2. 实际完成与验收

| 验收项 | 本票结果与证据 |
| --- | --- |
| 缺模板与缺父目录能 prepare，然后 check 成功；canonical/模块不变 | 通过。新增真实 CLI 测试使用精确 14 输入的隔离副本，比较原始 bytes，并验证资源父目录原先不存在 |
| canonical 内容及 CRLF 漂移在准备前拒绝，check 不修复 | 通过。两种漂移下 prepare/check 均非零，整个合成树 bytes/路径保持，不建 resources 父目录 |
| 模板内容/CRLF 漂移与缺文件明确失败 | 通过。strict check 按 bytes 比较，缺文件有明确错误；拒绝分支零修复写入 |
| 幂等与失败保全旧输出 | 通过。两次 prepare 原 bytes 相同，无临时文件残留；模块变化/缺失不覆盖旧模板；真实文件系统发布失败保全原输出与 canonical |
| 既有完整 Guest 回归不退化 | **通过（主协调统一执行）**。WSL runtime-final checks=65 exit0；sync-final 10 tests exit0；builder-python-final checks=6 exit0。准确证据见§6.1，本C未重复运行 |
| 当前集成 APK 唯一 guest_init 条目与 canonical 原 bytes 相同 | **待主协调 / 13 票**。没有构建新 APK；研究阶段旧 405 APK 的字节证据不冒充本票集成结果 |

## 3. 修改文件与交付行为

生产/测试写入仅四个文件：

1. `guest-assets/scripts/sync-guest-init.py`：新增 `--prepare-resource`；在内存渲染后先严格比较 canonical 原 bytes，失败时不创建资源目录、不改 canonical/旧模板；成功后创建父目录，用同目录临时文件和 `os.replace` 原子发布 generated guest_init，finally 仅删除自己创建的临时文件。`--check` 为只读原 bytes 校验，区分 canonical 漂移、模板缺失和模板漂移。argparse 互斥模式并拒绝未知/缩写选项，避免误拼参数走默认源码更新。
2. `guest-assets/scripts/test-local.sh`：Python preflight 后，在 Gradle 之前依次 prepare、strict check、新增 CLI 行为回归；继续保留既有 builder Python tests 和后续 Guest 检查。未修改 GitHub workflow，现有入口已覆盖它。
3. `guest-assets/scripts/test-guest-runtime-provision.py`：独立 CLI 在已有外部工作根校验后、任何 fixture 创建前调用 prepare，避免直接调用仍依赖历史 generated 模板；保留原套件末尾的 strict check。没有运行默认更新模式。
4. `guest-assets/scripts/test-sync-guest-init.py`：新增 10 项可维护的 unittest 行为回归；只复制明确的 14 个生成输入到预先存在的外部工作根。`GUEST_RUNTIME_TEST_ROOT` 缺失、不存在或位于源码检出内均拒绝，不自动建工作根，不读取 APK、用户盘、凭据或 Git 历史。

另更新本票状态/备注与本实施报告；没有修改 App、其他 owner 的 UI/版本、canonical init 内容、模块内容、ADR/policy、内核/rootfs、依赖锁或用户盘。开始时 App/版本文档已有主协调的并行改动，保持原样，不宣称当前全仓 clean。

## 4. 关键设计与保留边界

- 自动准备与显式源码维护分开。无参数调用保留原有的 canonical+模板更新用途；CI/测试入口全部走 prepare/check，不先覆盖 canonical 获得假同步。
- prepare 的校验依据是模块 render 的预期 UTF-8/LF bytes；canonical 原 bytes 必须相等，不能靠 `read_text` 的通用换行归一化通过 CRLF。resource 比较同样为原 bytes。
- 原子发布只作用于可再生 APK 模板。失败输入在 mkdir/临时写入之前拒绝；真实 I/O 发布失败则保全既有输出，删除本次临时文件。无持久化/断电耐久性新承诺。
- 保留 App 原有 Copy/sourceSets 和打包职责，没有引入 Python 到 App preBuild，也没有新增第二个 guest_init 资源源。
- 没有增加 IPC、权限、网络或 VM owner，未触发 Architecture Change Policy。

## 5. TDD：五片真实 red→green

每片先写行为测试/外部入口探针，运行实际 CLI 得到 red，才改相应生产入口。原始结果都保存为 `I/evidence/<label>.json` 与 `.log.txt`，即时复制到 `I/preservation/`。

| 片 | red 的实际失败 | green 的实际结果 | 证据标签 |
| --- | --- | --- | --- |
| 1 缺父目录 prepare | `--prepare-resource` 当成默认更新，写 guest_init 时 FileNotFoundError，测试 rc1 | 创建模板父目录并生成原 bytes，随后 check=0，输入保持 | `slice1-red` / `slice1-green` |
| 2 CRLF 原字节门 | CRLF 模板的 check 错误 rc0；测试报告 `0 == 0` | 改为 bytes 比较后拒绝，模板/canonical 均不修复；累计2测试通过 | `slice2-red` / `slice2-green` |
| 3 CLI 模式拒绝 | prepare+check、误拼 prepare 都返回0，后者落入源码更新 | argparse 拒绝两类输入且旧模板保持；累计3测试通过 | `slice3-red` / `slice3-green` |
| 4 test-local 入口 | clean 隔离检出到达 Gradle 替身边界时 resource 仍缺失 | 在该边界前资源 bytes==canonical；canonical 漂移在生成/Gradle前拒绝 | `local-entry-red` / `local-entry-green` |
| 5 独立 runtime 入口 | canonical 漂移尚未校验就创建 fixture，并尝试查找 sh | prepare 提前报告 canonical 不同步，工作根零新增，资源父目录不存在 | `runtime-entry-red` / `runtime-entry-green` |

第4/5片在已有 WSL Ubuntu-24.04 中使用 Python3，不安装包/改系统。第4片的 Gradle 边界是一个固定返回23的合成 executable，证明后续真实 Gradle未运行；不是构建通过。第5片通过去除 PATH 让旧入口在首个 shell 查询即停，避免执行完整 Guest suite；其 red 仅证明校验顺序不正确，green 则证明漂移提前终止。

## 6. 最终真实测试数与命令

新增回归为 **10项**，Windows Python3.12 实际全套通过；WSL 的隔离 test-local 实际执行同一10项，也全部通过。二者为相同测试集合的跨平台复验，不计为20个新增测试。WSL 还实际通过既有 **6项** builder Python preflight；另有 **3个**入口场景（test-local clean/漂移、独立runtime漂移）通过，分别记录而不冒充完整 Guest。

10项涵盖：缺父目录prepare、模板CRLF拒绝、非法/冲突模式、canonical内容/CRLF漂移、模块变化/缺失保全旧模板、缺/旧模板check只读、准备幂等、发布失败保全、显式源码更新的兼容性、外部工作根前置门。

复现新增测试（工作根必须预先存在）：

```bash
GUEST_RUNTIME_TEST_ROOT='<已存在的外部工作根>' \
  python3 guest-assets/scripts/test-sync-guest-init.py
```

本票实际 Windows总回归由 `I/run-verification.py` 调用，结果 `I/evidence/final-cli-windows.json` / `.log.txt`：`Ran 10 tests ... OK`。WSL同集合结果包含在 `local-entry-green-clean.json`。

其他实际检查：

- 既有主仓模板只读 `python -B guest-assets/scripts/sync-guest-init.py --check` 返回0，记录 `existing-local-resource-check`；未对主仓运行 prepare 或默认 generator。它与隔离 clean case分别记录。
- WSL `bash -n guest-assets/scripts/test-local.sh` 返回0，记录 `shell-syntax`。WSL启动时输出既有 localhost 代理提示，本票未修改系统代理/WSL配置来消除它，命令仍返回0。
- 三个变更 Python 文件在内存 `compile(..., 'exec')` 通过，不写 `__pycache__`。
- 本票限定文件 `git diff --check` 返回0。
- canonical 与领取前精确副本逐字节相同，SHA256仍为 `0d8a30100a626d544dde3ce2e87c82483bf3cb8c3abf989a1f0cc91490c02c84`，233538字节。
- 本机已有 guest_init bytes==canonical。没有构建新包，没有改 canonical 内容。

后四项汇总在 `I/evidence/final-scope-check.json`。

### 6.1 主协调统一 Guest 验证补记

本C生产脚本冻结后，主协调在既有 WSL Ubuntu-24.04 实际运行以下三组，均 exit0；同时回报 canonical strict check 通过。本C已只读核对下列日志的实际测试汇总，未重复执行，未将同一新增10项重复累计为新测试。主协调负责这批原始证据与保全副本。

| 统一门 | 真实结果 | 准确原始证据路径 |
| --- | --- | --- |
| runtime-final | `GUEST_RUNTIME_PROVISION_TEST_OK checks=65`，exit0 | `LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/evidence/guest-runtime-final.log.txt` |
| sync-final | `Ran 10 tests ... OK`，exit0 | `LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/evidence/guest-sync-final.log.txt` |
| builder-python-final | `BUILDER_PYTHON_TEST_OK checks=6`，exit0 | `LOCAL_RESOURCES/artifacts/avf-shizuku-next-stage/20261015/evidence/guest-builder-python-final.log.txt` |

完整 Guest 回归门由此补齐。最终 APK 尚未构建核验，独立review尚待判定，云CI与设备仍未验收。

## 7. 未运行与偏差

- 本C未运行真实 Gradle、完整 Guest suite、kernel/build/smoke、App测试/lint、APK唯一条目校验、设备/AVF/Shizuku或GitHub云CI；其中完整 Guest suite已由主协调实际补齐，见§6.1。
- 其余统一构建与打包门由主代理执行。本票本地精准验证不能将 fake Gradle返回23算Gradle任务成功。
- 没有修改 GitHub workflow：现有 workflow 调 test-local，增加该入口的前置门已足够。
- 实施证据实际位置为 I，已按主协调后续指示保持，不移动/覆盖原件。
- 本票还未独立 review，ready-for-review不是已验收 completed。

## 8. 性能与风险

本票没有性能主张。prepare/check多一次脚本渲染与字节比较，新增CLI回归会增加有限本地/CI时间；没有降低校验或跳过既有门。

no-arg默认源码维护仍会写canonical，应由维护者明确调用；所有CI/test接线均避免此模式。I/O失败可能已创建空的目标父目录，但无部分模板被发布；“失败零写”验收对应输入不合法/漂移时在输出创建前拒绝，发布错误则保全已有载荷。测试 fixture保留在明确外部目录以便复核，不自动清理旧资产。

## 9. 对后续工单的影响

13集成票的完整Guest门已由主协调补齐，见§6.1；仍需统一Gradle/App/lint与最终构建后唯一`guest_init`条目原 bytes等于canonical的检查，不得用研究阶段旧405 APK或源码字符串断言替代。新生产包版本由主协调统一管理，本票不修改版本。

独立review重点：prepare未写canonical、check严格bytes、非法参数不落更新、临时文件原子发布/回收、直接runtime入口早停、所有测试根均外部且预存在。

## 10. 数据、资产与证据保全

I仅含本票精确基线副本、合成fixture、执行JSON/log.txt、脚本与交付源码副本。无整仓/.git归档、签名材料、真实实例根盘或credential读取；未外发本地内容。新增`.log`扩展名数量0。

现有SDK/NDK/JDK、WSL生产力沙箱、内核/rootfs、发布APK与历史原始证据均未修改/删除。WSL只复用既有Python/Shell运行外部fixture；没有apt/包安装、系统PATH/服务/安全设置变化。后续保留与清理由主协调按资产政策定点决定，无自动删除。

`I/preservation/`为每条执行证据的即时字节副本；正式报告、工单和四个交付源码也定点保全。外部fixture只复制相关文件，不提交生产源码树中的产物。

## 11. Commit与分支

无commit、push、branch/worktree创建或远端操作。HEAD保持428860ad9a1cb335f8be78c157b2ff57faf2a6da。主仓存在其他01–04与版本整合的并行改动，owner边界保持。

## 12. 工单状态

状态保持：**ready-for-review**。本票所有精准CLI、入口早停、语法、幂等及失败保全验证已完成，主协调统一完整Guest65、新增sync10、依赖门6与canonical strict check也已通过。最终APK与独立review仍待主代理；独立判定通过后再转completed，不自认设备或云CI通过。

## 主协调最终验收补记

状态：**completed-local**。前文“待主协调/ready-for-review”为C封板时历史状态。主协调完整Guest65/sync10/依赖门6均通过，规范/需求独立双轴最终approve；13真实assemble与APK门已完成，唯一guest_init233538bytes等canonical。最终0.5.0/code500 APK106506176bytes，SHA256500643f1305bf01267ed76bb5bbdd17f408957ddf7c27e24f73e383f50a2583c，签名与405相同。见review同id、13实施报告、E/integration/final-v050-apk-verification.json。云CI/kernel/build/smoke与设备仍未运行，不充当通过。

报告人：C / ZCode / new-provider/gpt-6.1-sol；主协调最终补记 ｜ 日期：2026-10-15 ｜ 耗时：未可靠计量
