# 会话 9 报告：性能归因 → 分支收敛 → 设备清理 → 仓库整理

> 设备别名 `qa-alt-01`（API 37 / arm64-v8a / Gunyah / protected-only）。基线 `main = 14cb9c6`。
> 本轮四阶段的原始数据落在工作区外的 ASCII 沙盒（`perf-s9`/`perf-s9b`/`perf-s9c`/`perf-s9d`），报告只引用其中的数值。

## 1. 结论

| 阶段 | 状态 | 一句话 |
| --- | --- | --- |
| 一 · 设备清理 | **done** | orchestella 系包从 7 个收敛到 2 个（当前 QA 包 + 其 test 包），释放 4.53 GiB，chroot 实例完好 |
| 二 · 性能归因与修复 | **partial** | 三段打点拿到真实数据，**推翻**了预登记的首要假设；并用 37 倍缩小的夹具证明存在**约 60 s 的固定地板**（与载荷、与 guest 计算都无关）。修复没做——机制未定，不做无数据支持的改动 |
| 三 · 分支收敛 | **done** | 远端 22 个分支全部删除，`git branch -r` 只剩 `main`；本地 31 → 8；未合并的未竟工作全部保留并列清单 |
| 四 · 文件整理 | **done** | 30 个零设备批次归档、缓存清理、两个误建目录先并入记忆再删；计划见 `cleanup-plan.md` |

## 2. 仓库状态

| 项 | 值 |
| --- | --- |
| `main` | `14cb9c6`（未改动、未 push） |
| 工作分支 | `feat/pvm-perf-attribution`（阶段二，4 个提交）、`feat/repo-cleanup`（阶段四，2 个提交） |
| 远端分支 | 只剩 `main`（含 `origin/HEAD`） |
| 本地分支 | 8 个：`main` + 5 个 `backup/*` + 2 个未竟 PVM-09 分支 |
| PR | **未创建**（见 §7 偏差 1） |
| CI | 未触发（同上） |

## 3. 阶段一：设备清理

### 3.1 操作前清单（分类依据：版本号 + 最后更新时间）

| 包 | 版本 | 最后更新 | 分类 | 处置 |
| --- | --- | --- | --- | --- |
| `io.github.nyzeep.orchestella` | 0.4.5 | 2026-10-15 | 历史渠道包 | `pm clear` → `pm uninstall` ✅ |
| `io.github.nyzeep.orchestella.qa.dv01` | 0.5.3-qa.dv01 | 2026-10-21 | 旧 QA 包 | 同上 ✅ |
| `io.github.nyzeep.orchestella.qa.dv01.test` | 0 | — | 旧 QA 测试包 | 同上 ✅ |
| `io.github.nyzeep.orchestella.qa.devicemode` | 0.5.3-qa.devicemode | 2026-10-20 | 旧 QA 包 | 同上 ✅ |
| `io.github.nyzeep.orchestella.qa.devicemode.test` | 0 | — | 旧 QA 测试包 | 同上 ✅ |
| `io.github.nyzeep.orchestella.qa.dvpvm` | 0.5.3-qa.dvpvm | **2026-10-26 11:00** | **当前 QA 包** | 保留 |
| `io.github.nyzeep.orchestella.qa.dvpvm.test` | 0 | — | 当前 QA 测试包 | 保留 |

### 3.2 `/data/local/tmp` 清理

删前 `ls -la` 确认后逐一删除：

| 文件 | 字节 | 判定 |
| --- | --- | --- |
| `csdl-request.bin` | 92,186 | distcc 请求体（ASCII 文本），本项目测试产物 |
| `linked_device` | 96,872 | arm64 ELF（NDK r27，Android 34 目标），本项目链接产物 |
| `dump.xml` / `dump_main.xml` / `main_dump.xml` / `terminal_dump.xml` / `uidump.xml` / `wizard_dump.xml` | 5–67 KB | UI 层级 dump，10-14/10-15 的测试残留 |

保留 `dalvik-cache/`（系统管理，非本项目产物）。

### 3.3 效果

| 指标 | 前 | 后 | 变化 |
| --- | --- | --- | --- |
| orchestella 系包数 | 7 | 2 | −5 |
| `/data` 已用 | 231 G | 227 G | −4 G |
| `/data` 可用 | 738 G | 742 G | +4 G |
| `Data-Free` | 773,505,888 K | 778,259,260 K | **+4,753,372 K ≈ 4.53 GiB** |
| `/data/local/tmp` 文件 | 9 个（含 1 目录） | 0 个（仅 1 目录） | 清空 |

### 3.4 chroot 实例完好证据

- 实例 `192796b1-…` 的三个元数据文件均在：`.firstboot`（377 B）、`.mirror`（459 B）、`.properties`（871 B）。
- `vm list` → `Running VMs: []`（无遗留运行实例）。
- 本轮三次交火轮次全部从该实例启动成功（`ACCEL READY`、guest chroot 命中 `/alpine/usr/bin/clang`），**说明 Volume 与 rootfs 内容未受损**。

### 3.5 未做项

- **没有**对当前 QA 包执行 `pm clear`（会连数据一起清掉，正是本轮要保护的对象）。
- 当前 QA 包的 `cache/` 未清：`run-as rm -rf cache/*` 在交火轮次进行时执行可能干扰 payload 已解出的原生库（`NativeExecutableStore` 的提取物就在 app 私有目录），故跳过。

## 4. 阶段二：性能归因

### 4.1 方法（新增三段探针，`--profile-segments`）

在 `tools/device-validation/dv_pvm_crossfire.py` 内，于**同一次 guest 会话**中、`local_compile` 之后、完整 distcc 之前插入：

| 段 | 探针 | 口径 |
| --- | --- | --- |
| P（guest 预处理） | `clang -E --target=aarch64-unknown-linux-android -O1 fixture.c -o fixture.i` ×3 | console 往返墙钟，ms |
| A（guest 编译 `.i`） | `clang -c ... fixture.i -o fromdoti.o` ×2 | 同上；用来验证 `P + A ≈ local_compile` |
| T↑（上行） | 设备侧 `nc -l -p 19901 > file`，guest `nc 192.168.127.254 19901 < fixture.i` | 与真实 distcc **同拓扑**（guest → gvproxy → 设备 loopback） |
| T↓（下行） | 设备侧 `nc -l -p 19902 < file`，guest `nc ... > /dev/null`（180 KiB + 8 KiB 两档） | 同上，反向 |

`distcc_ms` 仍是原有的端到端 console 墙钟。payload 侧沿用 02a 实测的 **1.05 s**（`PAYLOAD_COMPILE_BASELINE_MS`），本轮没有重建 payload 去复测。

### 4.2 打点结果

**2000 函数夹具（`.i` = 90,126 B），三个独立轮次：**

| 段 | 轮 1 | 轮 2 | 轮 3 | 口径 |
| --- | --- | --- | --- | --- |
| `preprocess_ms` | 1,503.5 | 1,404.5 | 1,404.0 | 3 样本中位数 |
| `preprocess` 样本 | 1603 / 1604 / 1403 | 1805 / 1405 / 1404 | 1404 / 1405 / 1403 | 9 个样本全落在 1.40–1.81 s |
| `assemble_ms` | 25,575 | 25,583 | 25,781 | 2 样本中位数 |
| `local_compile_ms` | 30,482 | 30,894 | 30,296 | 02b 基线 30.7–32.5 s，一致 |
| `transport_up_ms` | 601 | 601 | 601 | 90,126 B，字节精确匹配 |
| `transport_down_ms` | 失败 | 失败 | 失败（两档均失败） | **未测到，见 §4.5** |
| `distcc_ms` | 未完成 | **91,097** | **83,274** | `rc=0`、`remote_seen=True` |
| `.o` 回传 | — | 179,912 B（ref 179,832 B） | 179,912 B | 结构等价前提成立 |

**夹具缩小 37 倍的对照轮（决定性）：**

| 指标 | 2000 函数 | 50 函数 | 倍数 |
| --- | --- | --- | --- |
| `.i` 字节 | 90,126 | **2,418** | 37× 更小 |
| `.o` 字节 | 179,912 | **5,416** | 33× 更小 |
| `preprocess_ms` | 1,404 | 1,304 | ≈1× |
| `assemble_ms` | 25,781 | 2,406 | **10.7× 更快** |
| `local_compile_ms` | 30,296 | 3,408 | **8.9× 更快** |
| `transport_up_ms` | 601 | 601 | 1×（见 §4.5 口径警告） |
| **`distcc_ms`** | **83,274** | **62,554** | **仅 1.33×** |

工作量缩了 37 倍、guest 计算快了 8.9 倍，**端到端只快了 1.33 倍**。这条对照把「distcc 慢是因为要编的东西多」直接排除掉了。

**`local_gap_ms` = `local_compile − (P + A)`** = 3.4 / 3.9 / 3.1 s（2000 函数三轮）、**−0.3 s**（50 函数轮）→ `P + A` 能解释 guest 本地编译的 90%+，剩几秒是进程启动与驱动开销。

### 4.3 账本

**先看固定地板（50 函数轮，任务几乎为空）：**

```
distcc_ms                     62,554
  − P  (guest 预处理)          1,304
  − A  (guest 编译 2.4 KB)     2,406   ← 这一项在远端路径上由 payload 承担（≈1 s）
  ≈ 固定地板                   58,800 ms
```

即：**请求只有 2.4 KB、回传只有 5.4 KB 时，一轮 distcc 仍要 62.6 秒，其中约 59 秒不属于任何已测段。**

**再看 2000 函数的边际增量：**

```
distcc_ms（2000 函数）        83,274
  − 固定地板                  62,554
  = 载荷相关部分              20,720 ms   ← 对应 90,126 B 上行 + 179,912 B 下行
```

**结论：85–94 s 的构成是「约 60 s 固定地板 + 约 20 s 载荷相关成本」，而不是「guest 编译慢」。** 这也解释了为什么交火看起来是 2.8× 回归：guest 本地 30 s 里没有这 60 s 的地板。

### 4.4 被数据推翻与证实的假设

预登记排序是「guest TCG 预处理 > 传输 > distcc 客户端 > payload」。数据给出的结果：

| 假设 | 判定 | 证据 |
| --- | --- | --- |
| guest TCG 预处理是大头 | **推翻** | 9 个样本 1.40–1.81 s，占 30 s 本地基线的 **5%** |
| guest 编译本身很快 | **推翻** | `assemble_ms` 25.5–25.8 s，占本地基线 **84%**——但它属于「本地对照组」，不是远端多出来的部分 |
| 传输（上行）是瓶颈 | **推翻** | 90,126 B / 601 ms，三轮恒定 |
| payload 编译慢 | **推翻（沿用 02a）** | 02a 同夹具实测收+编+发 1.05 s |
| distcc 客户端 TCG 开销 | **部分成立** | 剩下的 ~59 s 固定地板落在这条往返路径上；但它与「返回腿」「桥的连接生命周期」尚未分开 |
| HyperOS 冻结进程干扰测量 | **排除** | 三轮 logcat 只冻结了第三方应用（微信/QQ），QA uid 未被冻结 |

**CPU 侧面证据**：轮 2 的 2 分钟滚动采样显示 QEMU 占 `usages≈4.5–5.1`、`crosvm_qa` `0.066`、`gvproxy` `0.025–0.042`；`cpuTimeInCycle=6410ms / duringTime=120098ms` 说明这个数字是**百分比**（约 5% 的一个核），不是核数。三个进程都接近空闲 → **那 59 秒是在等，不是在算。**

### 4.5 最可能的机制（假设，未验证）

把三条事实叠起来看：

1. 地板是**固定**的（2.4 KB 与 90 KB 请求都约 60 s）；
2. 三个进程 CPU 都接近空闲 → 在等；
3. 每轮 `connections_completed=1`，而 instrumentation 被要求处理 `jobs+1=2` 个作业（driver 刻意多要一个 slack job，用来在回传对象期间撑住桥的等待循环）。

一个与三者都吻合的解释是：**桥在服务完第一个作业后不主动关闭这条连接，而是继续等第二个作业；distcc 客户端在收完 `DOTO`/`STAT` 后会等对端关闭（FIN）才收尾，于是一直等到自己的 I/O 超时——约 60 s。** 换句话说，这个地板可能是**测量夹具自己制造的**，而不是产品缺陷。

**这是假设，不是结论。** 验证它只需要改一处：让 driver 的 `instrument_jobs` 与实际的 `--jobs` 一致（或让桥在作业计数满足后立即关闭），再跑一轮同样的 2000 函数夹具。如果 `distcc_ms` 掉到 20–25 s，那就说明**交火本来就是赚的**，之前的 2.8× 回归是夹具伪影——这个结论会推翻本轮开工 Prompt 的前提，所以必须由下一轮用数据确认，不能由我在本轮宣布。

### 4.6 未测到的段与口径警告

`transport_down` 四轮全部失败，且**不是链路问题而是探针机制问题**：

- 轮 1（`cat file | nc -l`）：设备侧 nc 接受连接但从不发数据，guest 侧苦等，把 console 会话卡死，**连带毁掉了该轮的 distcc**（`connections_completed=0`）。已修：改 `nc -l < file` 重定向。
- 后续三轮（重定向 + shell 级 `timeout`）：180 KiB 与 8 KiB **两档都立即失败**（`rc≠0`，非超时），失败与尺寸无关。原因是 guest 侧 `nc ... > /dev/null` 的 stdin 仍接着 console tty，busybox nc 在该状态下直接报错退出。**正确修法是把 stdin 也接 `/dev/null` 并考虑 `-N`，本轮时间盒内没再占一轮验证。**

**`transport_up_ms` 的口径警告**：90,126 B 与 2,418 B 两档都报 **601 ms**，一模一样到毫秒。这说明 601 ms 量到的是 `nc` 进程启动加 console 轮询（0.2 s 粒度）的**固定底噪**，不是传输时间。真实上行传输比这更快、且在这个分辨率下测不出来。**不能把 601 ms 当成上行带宽证据。**

### 4.7 修复：没有做

数据已经把范围收窄到「往返路径上的固定 ~59 s」，但**机制未定**（是桥不关连接、还是 AXP1 收尾、还是 distcc 侧的等待语义）。§4.5 给了可验证的假设与一步就能做的实验，但那是下一轮的事。在机制未定之前改产品代码，等于拿假设冒充数据——本轮**不提交任何"性能修复"**，只交付可复现的探针与四轮打点数据。

## 5. 阶段三：分支收敛

### 5.1 前后对照

| 指标 | 前 | 后 |
| --- | --- | --- |
| `git branch -r` 行数 | 24（含 `origin/HEAD`） | **2**（`origin/HEAD` + `origin/main`） |
| `git branch` 本地数 | 31 | **8** |
| `git branch -r --no-merged main` | 空 | 空 |

### 5.2 删除清单（自动化前置校验后执行）

- **远端 22 个**：`docs/accelerator-baseline-refresh`、`docs/accelerator-closeout`、`feat/accelerator-batch1..3`、`feat/accelerator-device-handoff`、`feat/ax-07-perf-protocol`、`feat/ax-08-assembly`、`feat/dv-acc-assembly`、`feat/dv-pvm-01-probe`、`feat/dv-pvm-02-device-e2e`、`feat/dv-pvm-02b-crossfire`、`feat/pvm-01-backend-selection`、`feat/pvm-02-inprocess-compiler`、`feat/pvm-03-compile-service`、`feat/pvm-04-llvm-android`、`feat/pvm-05-object-equivalence`、`feat/pvm-07-perf-v2`、`feat/pvm-08-apk-packaging`、`feat/pvm-09-product-wiring`、`fix/axp1-test-pipe`、`fix/fastlane-distcc-evidence-ci`。
  每个分支删除前都重跑 `git log main..origin/<b>`（必须为 0）与 `git merge-base --is-ancestor`（必须为真），22/22 通过。
- **本地 23 个**：上述 22 个的同名分支 + `fix/fastlane-dv01b-runner-hardening`（upstream 已 gone 且已合并）。全部用 `git branch -d`（小写，让 git 自己校验合并状态）。

### 5.3 保留清单（未合并 / 有未竟工作，按 §8 停止条件**不删**）

| 分支 | 独有提交 | 内容证据 |
| --- | --- | --- |
| `feat/pvm-09-facts` | 1（`9a71ca7`） | `PvmCompilerManifest.java` 等 4 个文件与 main 版本不同 |
| `feat/pvm-09-manifest` | 1（`825396d`） | **main 中不存在** `tools/pvm-compiler/verify_pvm_manifest.py` 与 `test_pvm_manifest.py` |
| `backup/accel-batch1-pre-identity` | 3 | 身份改写前的快照，origin 上已无同名分支 |
| `backup/accel-batch2-pre-identity` | 6 | 同上 |
| `backup/accel-batch3-pre-identity` | 18 | 同上 |
| `backup/accel-device-handoff-pre-identity` | 20 | 同上 |
| `backup/pre-pull-20261022` | **353** | 同上，本地是这些提交的唯一引用 |

这 7 个分支删除后对象会在 gc 后不可恢复 → 交维护者裁定（§8 第 1 条）。

### 5.4 本地 worktree / 克隆

5 个 worktree 全部保留，未执行任何 `git worktree remove`：主工作区、`orch-review-runtime-perf`（游离提交 `e86a5a0`，与 main 分叉 319/539）、`pvm09-facts`、`pvm09-manifest`（1 个未提交改动）、`pvm09-wt`（13 改 + 5 未跟踪）。另有两个独立 clone 未动，其中一个带 1 个 stash 与 3 个 QA 脏文件。理由见 §8 第 2 条。

## 6. 阶段四：文件整理

计划全文见仓库内 `cleanup-plan.md`。执行情况：

### 6.1 已删除

| 项 | 体量 | 说明 |
| --- | --- | --- |
| `.gradle/`、`.kotlin/`、5 处 `__pycache__` | ≈4.8 MiB | 未跟踪缓存，可重建 |
| 工作区根 `2026-10-25-20-52-55/` | 4.0 KiB | **先并入记忆再删**：内含会话 5（PVM-03）的独立日志，已追加进 `.workbuddy/memory/2026-10-25.md` |
| 工作区根 `2026-10-26-09-04-26/` | 8.0 KiB | 同上，会话 8 的 02b 硬事实已并入 `2026-10-26.md` 与 `MEMORY.md`；目录已清空 |

> 后者的**空目录残留**：回收站助手（`genie-trash`）对该目录连续两次报 "Some operations were aborted"，0 字节、无清理收益，未强行绕过。

### 6.2 已归档（`git mv`，保留历史）

| 批次 | 目录 | 文件 | 体积 |
| --- | --- | --- | --- |
| `tickets/runtime-performance-01..08` | 8 | 10 | 76 KiB |
| `tickets/fastlane-P01..P12` | 12 | 19 | 224 KiB |
| `tickets/avf-zero-device-01..10` | 10 | 10 | 80 KiB |
| **合计** | **30** | **39** | **≈380 KiB** |

目标 `docs/reports/archive/2026-10-zero-device/`，`archive/README.md` 已加索引行说明「这三批是零设备产物，真机口径由 dv-pvm/ax/avf-shizuku 系列接管」。归档对象全部**完全跟踪**（`git ls-files` 计数 ≡ `find -type f` 计数），`docs/reports/README.md` 未引用它们，无断链。

### 6.3 体积

| 指标 | 前 | 后 |
| --- | --- | --- |
| 仓库根（含 `.git`） | 4.1 GiB | **4.1 GiB** |
| `.git` | 18 MiB | 18 MiB |
| `build/` + `app/build/` | 3.9 GiB | 3.9 GiB（**未删**，理由见下） |

**本轮仓库体积没有实质下降，这是有意的。** 唯一能带来数量级下降的是 `build/` + `app/build/`（3.9 GiB），其中 `build/llvm-android/` 独占 2.1 GiB 是 libclang 22.1.8 的交叉构建产物，重建是**数小时级**；`app/build/` 里还躺着唯一一份已装机的 QA APK。把它们当「普通缓存」删掉，等于用下一轮几小时换本次报告一个好看的数字。故整组暂缓，交维护者裁定（§8 第 3 条）。

## 7. 未验证项与偏差

1. **没有建 PR**。开工 Prompt 要求「PR 中文标题/正文，不合并」，但本机 `gh` 只授权了 `repo`/`gist`/`read:org`，**无 `actions:write`**；且分支未 push（见下），无法开 PR。两个分支都停在本地，commit message 已按 conventional commits 写好，随时可 push 并开 PR。
2. **分支未 push**：开工 Prompt 要求「不 push main」，未禁止 push 特性分支；我在最后一步选择了保守（不推未开 PR 的分支），如需评审请确认后我再 push。
3. **`distcc` 同夹具轮次 n=2**（91.1 s / 83.3 s），不是 Prompt 要的 n≥3：首轮因探针 bug 报废，没计入；另有一轮是 50 函数对照（62.6 s），不计入同夹具统计。
4. **`transport_down` 无有效值**（§4.6），这是本轮最大的测量缺口。
5. **`--profile-segments` 关闭时**：命令、检查、失败码与 02b 完全一致，但 JSON 会多出 16 个恒为 `null` 的字段（schema 增量，非行为变化）。
6. **每轮 `failure_code = BRIDGE_JOBS_INCOMPLETE`**：`distcc_rc=0`、`.o` 回传成功、`stop_confirmed=true`、`deleted=true`，但 `connections_completed=1` 而 instrumentation 期望 `jobs+1=2`（driver 在取回对象后会补一个 slack job）。**这个 slack 连接没有被计数**，所以严格讲「交火回归测试」只通过了 3/4 项，不能报全绿。
7. **`equivalence` 本轮为 `INCONCLUSIVE` / 未跑**：宿主 x86 ld 拒绝 aarch64 对象是 02b 已知固有限制；本轮有个轮次还触发了 compare 脚本 300 s 挂起，后续轮次用 `--compare-script ""` 跳过。**结构等价沿用 02b 的 Level 3 结论，本轮未复验。**
8. **payload 侧 `compile_ms` 仍未打点**：本轮用 02a 的 1.05 s 作常数。要把它变成实测，需要改 payload C++ 并重建 `.so`（AX-07 范围）。
9. **`docs/reports/` 下 9 个「磁盘有、索引无、被 `/docs/` 忽略」的文件**未处理（`git mv` 会失败、`git clean` 会删掉、`git add -f` 被本轮规则禁止），列在 §8。

## 8. 需要维护者决策

1. **7 个未合并分支怎么处置**：`feat/pvm-09-facts`、`feat/pvm-09-manifest` 各自带着 main 里没有的实现（尤其 `verify_pvm_manifest.py` + `test_pvm_manifest.py` 共 517 行）；5 个 `backup/*` 是 353/20/18/6/3 个提交的唯一引用。是「合并进 main / 转成 patch 存档 / 直接丢弃」，需要你定。
2. **5 个 worktree + 2 个独立 clone 是否清**：其中 `pvm09-wt` 有 13 改 + 5 个未跟踪文件（含 `PvmCompilerManifest.java` 磁盘唯一副本），`orch-review-runtime-perf` 是一个游离提交（不在任何 ref 上，与 main 分叉 319/539），`t/orch` 有 1 个 stash。清任何一项都会丢东西。
3. **`build/` + `app/build/`（3.9 GiB）删不删**：删了本轮报告能有「−95% 体积」的数字，代价是下一轮要重新交叉构建 libclang（数小时）。
4. **性能归因下一步（建议按这个顺序）**：① 先做 §4.5 的一步实验——让 `instrument_jobs` 与实际 `--jobs` 对齐后重跑 2000 函数夹具。如果 `distcc_ms` 掉到 20–25 s，那 60 s 地板是夹具伪影，**「交火是 2.8× 回归」这个前提本身要撤回**；如果没有变化，再转 ② 补「返回腿」的合法测量（nc 的 stdin 接 `/dev/null` + `-N`）。这两件事都花不了几个钱，但会决定整条加速线的叙事。
5. **`docs/reports/` 那 9 个被 ignore 的证据文件**：要不要用 `git add -f` 收进库（其中含真机验证报告），还是保持现状只留在本地。

## 9. 下一轮交接

- **最重要的一句话**：85–94 s 不是「guest 编译慢」，也不是「传输慢」，而是 **~60 s 固定地板 + ~20 s 载荷成本**。地板在 2.4 KB 的请求上照样存在。
- **下一轮第一件事（一步就能做）**：把 driver 的 `instrument_jobs = args.jobs + 1` 改成与 `--jobs` 对齐（或让桥在满足作业计数后立即关闭连接），重跑 2000 函数夹具。**这决定 60 s 是不是夹具自己造的伪影。** 在它被排除之前，不要再基于「2.8× 回归」做任何产品决策。
- **第二件事**：修下行探针（`nc -N ... < /dev/null > /dev/null`，并用「设备侧端口确认 LISTEN」替代固定 `sleep 3`），补上返回腿的合法数值。
- **不要再假设瓶颈在 guest 预处理**：9 个样本把它钉死在 1.3–1.8 s；`assemble_ms` 25.5–25.8 s 是「本地对照组」的成本，不是远端多出来的部分。
- **`BRIDGE_JOBS_INCOMPLETE` 需要定性**：它是 `connections_completed=1` 的直接产物，且与 §4.5 的假设是同一个机制。这两件事应该一起查，不要分两轮。
- **探针已就位**：`dv_pvm_crossfire.py --profile-segments`（`--perf-port-up/--perf-port-down`，端口 5103/19999/5037 有硬校验）。一轮 guest 启动即可同时拿到 P、A、T↑、T↓，**不必重编 APK 或 payload**。
- **设备现状**：仅 `qa.dvpvm` + 其 test 包在机；Arch 实例 `192796b1-…` STOPPED；rootfs 内 `/alpine` chroot 保留，但 `/root/fixture.c` 已被换成 **50 函数小夹具（2,418 B）**——下轮跑 2000 函数时 driver 会因字节数不符自动重注入，无需手工清理；`/data/local/tmp` 已清空；设备保持开机。
