# 04: QEMU MTTCG 多核扩展性——「调度手机多核」的第一块基石

Type: research
Status: resolved（fabric-batch2，2026-10-31）
Priority: P1（fabric 路线的 QEMU 面）
Owner: fabric-batch2
Blocked by: None
External gates: 无（零设备调研 + 方案设计）

## 背景

用户目标之一是「完美调度手机多核」。已确证：QEMU 车道 36 域以 `-accel tcg,thread=multi -smp 4` 启动成功（会话 22 §4.3），即 TCG 多线程多 vCPU 在本项目可用；但没有扩展性数据与调度策略。pVM 面的多 vCPU 由工单 05 覆盖，本票只管 QEMU/宿主侧。

## 必须回答

- [ ] MTTCG 扩展性：`-smp N` 的实际扩展曲线（文献/社区基准：ARM64 TCG thread=multi 在 2/4/8 vCPU 下的加速比）；翻译税（TCG）随核数是否恶化；Pin 大小核（big.LITTLE）亲和性的可行做法（`taskset`/cgroup 在 App 域的可用性）。
- [ ] vCPU 数与手机物理核的匹配策略：4 vCPU 是否最优？超订（vCPU > 物理核）的行为？与宿主 App 渲染线程的争抢。
- [ ] 与 pVM 面共存时的资源预算：同时跑 QEMU（4 vCPU）+ Microdroid（N vCPU）的内存/CPU 上限设计（对接现有 `LaneBudgetLedger` 的扩展点，只提需求不改代码）。
- [ ] 产出可测的 benchmark 方案（供下一个设备窗口）：同夹具 `make -j` 的 QEMU `-smp 1/2/4/8` 四列，n≥10，控温——这是 G3 之外「QEMU 面多核」的独立数据源。

## 交付

- `docs/research/2026-11-XX-qemu-mttcg-scaling.md`（含 benchmark 方案与参数建议）；
- 对 `LaneBudgetLedger` 的资源预算需求清单（不写代码）。

## 停止条件

- 文献无 Android/ARM64 TCG 扩展性数据 → 如实记录「未取数」，方案改为纯实测设计。

## Comments

- 2026-10-31 / CodeBuddy（ask-matt 轮）：建票。
- 2026-10-31 / CodeBuddy（批次 1 收口后追加输入更新）：
  1. **框架前提改用批次 1 产出**：fabric 的资源模型以 `docs/research/2026-11-05-pvm-fabric-route.md` 为准——CPU 超订以任务队列限流、不承诺绑核；本票的「资源预算需求清单」必须与该模型的账本字段对齐，不得另起一套。
  2. benchmark 方案与 05 的 W-FAB-01（protected match_host 多 vCPU 启动/吞吐）合并为一份可执行设计，避免设备窗口跑两套夹具。
  3. 其余任务按原票执行。
- 2026-10-31 / fabric-batch2（批次 2 收口）：**resolved**。交付 `docs/research/2026-11-04-qemu-mttcg-scaling.md`（协调会话复核通过）。关键结论：
  1. 文献取数：aarch64 guest TCG 扩展曲线有数据（Linaro，x86 宿主 + QEMU 9.1.0 + Cuttlefish：smp4=2.72×、smp6=2.87× 拐点、smp8 回落 2.76×，全部标「形态参考」）；**ARM64 手机宿主（App 域）-smp 数据 0 样本** → 停止条件部分命中，如实记录未取数，方案转纯实测设计，未编造数字。
  2. 扩展性头号杀手 = 宽原子回退串行化：`-cpu max` 触发 EXCP_ATOMIC 全停时 smp8 比 smp4 慢 53%，换 `-cpu cortex-a57` 恢复 3.34×——本项目真机参数已在正确一侧，CPU 型号不变量列为 benchmark 必控变量。翻译本身非串行点（每 vCPU 独立 TCG 上下文无锁）；随核数恶化的是共享结构事件（全量 flush/TLB shootdown/BQL 段）。
  3. 4 vCPU = 稳健默认档（50% 订阅留 UI 余量 + 文献拐点 4–6），非全局最优，最终由四列数据判定；big.LITTLE 绑核 = 可行性情报（同 UID setaffinity 合法 [官方文档] + domain.te self:process setsched 放行 [官方源码]），**不改批次 1「任务队列限流、不承诺绑核」前提**；QEMU 无 per-vCPU 亲和 CLI（thread-context 不覆盖 vCPU 线程）。
  4. 资源预算需求 R-Q1~R-Q8 全部对齐 05 账本模型（核数字段=并发预算语义、pVM 不可换出标记、LOST 预留回收）；发现现状缺口：运行中 ACCEL LOST 不释放 `accelReservation`（DualLaneCoordinator.java:224-229），列 R-Q6 需求不改代码。
  5. benchmark 与 W-FAB-01 合并为一份可执行设计：夹具 F-MTTCG-C200（200 文件×10 函数 ≈2,000 函数 C 工程，`.o` sha256 等价校验）、列组 A=QEMU `-smp 1/2/4/8`、B=W-FAB-01 原文（protected 1/match_host）、C-nat=native 参照列（翻译税判定依赖，必须同轮）、n≥10、控温+Latin square、工具链两比对组禁混算。
  未验证项：cortex-a57 手机宿主实际曲线、翻译税占比（依赖同轮 native 列）、现行 main domain.te setsched 逐字复核（实测兜底）、TCG 每 vCPU 字节数（官方未公布）。设备窗口增量 W-FAB-07/08/09。
