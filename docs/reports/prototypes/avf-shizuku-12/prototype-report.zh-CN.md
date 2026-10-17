# 原型报告：AVF/Shizuku 12 自有装配日志多路输出

- 日期/执行者：2026-10-16，Worker C / new-provider/gpt-6.1-sol。
- 分支/基线：main@428860ad9a1cb335f8be78c157b2ff57faf2a6da，保留起点 dirty 0.5.0/code500 及并行A/D变更；没有commit/ref/worktree操作。
- 背景/scope：原12要求先执行真实owned report子进程，用native/recovery/failed受控sink计数；证据成立后派生18限定修复自有路由。禁止audit/全局stderr/用户文本过滤、第二终端数据面或getty重构。
- 原始证据 canonical 根：`LOCAL_RESOURCES/artifacts/avf-shizuku-device-20261016/batch-01/lifecycle-logs/`，下文简称E；原件及即时 `E/preservation/` 保全，Class B至维护者验收归档，不自动清理。

## 假设与来源图

原脚本 `guest-assets/scripts/orchestella-guest-provision.sh` 的 report 同时追加自有 `var/log/orchestella-provision.log.txt`、写stdout、直接写 `/dev/console`。owned systemd firstboot unit 的stdout/stderr又是 `journal+console`。recovery和交互执行则继承可见stdout。package子命令输出已定向至自有文件。静态多写入只能提出假设，不能证明真实设备每行必然双份。

本实验验证三件事：在相同报告行上受控sink是否存在双份；失败state/原操作退出码/日志与sync是否保留；无journal的recovery是否在去重后仍可见。

## 环境、方法与限制

Windows / Python3.12.9 / Git Bash sh。实际子进程执行生产脚本从入口到report/run_step/write_state/fail_stage/cleanup的完整前缀，并用合成子命令成功或exit23驱动运行/失败。fixture有生产ROOT sentinel，所有输出/临时根均在E；无真实盘、网络、宿主/dev或设备。

仅作明确的环境映射：把Guest `/dev/console`路径与ROOT guard映射到外部文件sink；为模拟字符console连续字节接收，把该唯一console重开映射为文件append。native journal/console fanout由当前实际生成的owned unit字段驱动，属于受控sink模型，不是实际systemd/journald执行。recovery/interactive按继承stdout建console sink。sync替身仅计数，未对宿主做flush。

首轮 `red-owned-report-*` 用普通文件 `>` 产生覆盖，因此早先行计数不完整；该原件保留并声明不足。修正stream映射后的 `red-owned-report-stream-*` 才用于结论。没有把首轮不完整计数写成真实console行为。

固定入口：

```text
python -B <E>/run-owned-report.py --label <唯一标签>
GUEST_RUNTIME_TEST_ROOT=<E下新目录> python -B guest-assets/scripts/test-guest-runtime-provision.py --owned-report-only
```

第二入口是新增维护性回归，直接检查模块源码，不调用canonical生成；其余原测试模式保留原prepare/check门。

## 实际次数与退出结果

每场景发送running和ready/failed两个report行。下表为每条report进入可见console sink的份数；自有文件每条始终一份，native journal每条一份。

| 场景 | red running / 结果行 | 修复后 running / 结果行 | 子进程exit | state |
| --- | --- | --- | --- | --- |
| native正常 | 2 / 2 | 0 / 0 | 0 | ready |
| native失败 | 2 / 2 | 0 / 1 | 1 | failed，原操作码23 |
| recovery正常 | 2 / 2 | 1 / 1 | 0 | ready（仅报告fixture，无Guest基线含义） |
| recovery失败 | 2 / 2 | 1 / 1 | 1 | failed，原操作码23 |
| interactive正常 | 2 / 2 | 1 / 1 | 0 | ready（仅报告fixture） |

证据：`E/evidence/red-owned-report-stream-owned-report-summary.json`与对应五个case、`green-owned-report-owned-report-summary.json`与五个case；真实report子进程stdout/stderr/argv/exit、状态字段、文件次数与实际映射后的child源码均保全。失败路径operation_exit_code=23、自有文件保留合成child输出、sync一次、无done marker。项目内入口结果为 `E/evidence/green-project-owned-report.json`、`E/owned-report-project/owned-report-results.json`，exit0、5场景通过；不把分次运行加总成更多覆盖。

## 结论与触发器

- 在已明确的controlled native/recovery/interactive路由下，每条report双路console输出假设成立；真实Guest/PID1/console连接方式及屏显次数仍未测。
- 18修复后native常规report只留journal/自有文件，失败加一次直接console；recovery/interactive保持继承stdout且每条一次。失败state、原23、自有日志和sync未丢失。
- 本轮没有读取或过滤用户正文、按键、凭据/token，没有关闭audit/kernel/global stderr/journal，没有改getty/PATH/控制reader。
- 不触发Architecture Change Policy：只调整现有owned unit/provisioner路由，不新增IPC、权限或终端数据面。无需翻转AVF能力矩阵。

## 产出、修改与剩余门

生产实施归派生18，报告 `docs/reports/tickets/avf-shizuku-18/implementation-report.zh-CN.md`。实际修改仅owned脚本report、runtime-functions中的owned firstboot unit，以及原Guest测试的新 `--owned-report-only` 模式。canonical init/guest_init没有由Worker生成；已交主协调在最终冻结后统一同步。

外部实验脚本/class/fixture不是生产资源，保留在E，永不作为Guest生产输入合入。没有新增第三方依赖、上游代码复制、网络、设备或宿主系统修改，无commit。主协调执行Guest全套/sync/依赖门、APK唯一guest_init字节检查与真实新QA盘native/recovery/failed trace；设备屏显、编辑体验、真实journald/sync耐久性仍未验。失败原件不会因green删除。回滚只精确恢复C before脚本hunk及主协调生成物，保留旧dirty/并行改动/用户数据和Class A资产。

报告人：Worker C / new-provider/gpt-6.1-sol ｜ 日期：2026-10-16 ｜ 耗时：未可靠计量
