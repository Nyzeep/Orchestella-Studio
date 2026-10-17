# 18 独立 Spec 审查：自有装配日志路由与失败可见性

结论：**approve（冻结源码与受控子进程范围），无新增 P0/P1/P2 发现**。真实 Guest/PID1/journal、recovery 屏显和编辑体验仍未验，主协调 canonical 同步、集成与 APK 字节门另行签收。C17 的两项调用链 P2 见17同主题报告，不计入本票。

## 审查对象、基线与范围

独立 Spec reviewer / Codex GPT-6.1-sol；2026-10-16。已读12/18原票、18实施报告、20261016 Worker C/Reviewer要求，以及项目指定policy。比较本批 `lifecycle-logs/before/` 到 `final-freeze.json` 指定的 `frozen-source/`，本票三份Guest输入为 provisioner、runtime-functions与既有测试脚本；共17份C冻结输入均匹配bytes/SHA。

基线沿用户给定main@428860ad9a1cb335f8be78c157b2ff57faf2a6da、dirty0.5.0/code500，不以HEAD三点diff遗漏旧dirty实现。无commit/ref/版本操作，未读另一轴，不复用其结论。

## 原验收与冻结行为

18要求只在自有日志来源去重，native常规进度留journal/owned文件、failed保留原操作码/state/sync并一次console摘要，recovery无journal仍可见，禁止过滤audit/kernel/用户正文或建立第二reader。

- `orchestella-guest-provision.sh` 的report仍写owned文件并printf到继承stdout；仅在本owned journal模式且failed、真实Guest console可写时额外送一次摘要。failure原操作退出码、write_state、sync与done语义未改。
- `runtime-functions.sh` 只修改owned firstboot unit：设置ORCHESTELLA_PROVISION_REPORT=journal，StandardOutput/StandardError=journal。native常规report不再由unit转发console，也不自行重复写；failed才走直接console。
- recovery与交互未设置journal模式，stdout继续可见，未改supervisor/getty/PATH、audit/kernel、全局stderr或其他unit。无App正则过滤、用户正文解析或新数据面。
- `--owned-report-only`明确运行实际生产prelude/report/run_step/write_state/fail_stage子进程；只将合成ROOT与console映射到受控sink，并从实际生成unit决定fanout。限定模式跳过canonical准备，原其他测试模式仍保留prepare门，没有用本测试改canonical掩盖漂移。

未发现额外scope或看似实现却错误的日志路由。常规日志减噪不承诺所有发行版/内核输出消失；失败仍有自有日志/state，实际journal耐久性也未被声明已测。

## 证据真实性与限制

独立读取 `green-owned-report-owned-report-summary.json`、native/recovery failed原件与 `green-project-owned-report.json`：五场景为native正常console0/0、native失败0/1、recovery正常1/1、recovery失败1/1、interactive正常1/1；每行owned文件一份。failed实际子进程exit1，state failed、operation_exit_code23、sync一次、无done，child正文只留owned文件。

项目入口原始输出为 `OWNED_REPORT_TEST_OK checks=5 guestValidated=false`、exit0。外部与项目入口覆盖同五个场景，不累计成10个。最初普通文件覆盖造成计数不足的历史原件保留，修复依据为stream映射的red，不能从静态双写推导目标设备每行必重复。

冻结provisioner SHA256 `6ff2e5f5451e24b880e140ae7e6c74c0f9acf7af015cbd24b762776303d4cf2e`、runtime-functions `432dfe3957b497a3bb9e8120fc5e8b86c994e2a29ea425dd7fee103e89959865`，原始summary绑定相同源hash。这些是合成sink真实子进程证据，不是systemd/PID1/journald或VM设备通过。

reviewer未重跑套件、设备、Gradle或canonical生成。完整Guest/同步/依赖门、最终APK唯一guest_init字节及真实新QA盘日志体验仍由主协调执行；本报告不沿用旧65/10/6计数签新源码通过。

## 交付、风险与后续

实际完成本报告、`review-spec/17-18/checks.json`相关核验与报告副本，并即时保全到 `preservation/review-spec/17-18/`。修改仅正式review与本轴外部证据，未改生产、tracker、版本/ref或首审原件。当前implemented-local/canonical-integration-device-review-open状态诚实。

剩余动作是主协调按冻结输入同步canonical，再做集成和真实Guest正常/失败/recovery可见性验证。风险为实际journal路由和console行为与受控sink不同，故设备门保持未验。无新权限/IPC/FD/owner/reader/出站，无另一轴读取、网络、设备操作或清理；Architecture Change Policy无本次架构实施。Class B，维护者验收归档前不自动清理。

报告人：独立 Spec reviewer / Codex GPT-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
