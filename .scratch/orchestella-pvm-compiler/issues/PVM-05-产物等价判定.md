# PVM-05: 产物等价判定（替代 SHA 比对）

- Type: tooling
- Status: implemented（PR #20 已合并 2026-10-24；等价判据应用归 DV-PVM-02 真机面）
- Priority: P0（PVM-03 的验收前提）
- Owner: tooling owner
- Blocked by: 无
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

AX-06 用 `.o` 的 SHA-256 与本地编译一致作为证据。这在**同一个编译器、同一路径**下成立；但本轮是「guest 的 clang 预处理 + payload 的另一个编译实例 codegen」，路径、时间戳、随机种子都可能不同，SHA 必然不等。若继续用 SHA 当判据，会得到大量假阴性。

同时也不能只比「能链接」——那会放过优化级别/ABI 的真实差异。

## 目标

定义并实现四级判据，按顺序短路：

1. **链接**：两个 `.o` 分别与同一组 fixture 链接，均成功；
2. **行为**：可执行文件运行退出码与 stdout 一致；
3. **结构**：`nm -C --defined-only` 符号集合一致；`readelf -r` 重定位类型/目标一致；
4. **差异白名单**：只允许 `.comment`、`.debug_*`、路径字符串不同。

并提供确定性编译开关建议，减少噪声。

## 任务

- [ ] `tools/compiler-equivalence/compare_objects.py`
  - 输入：两个 `.o` + 链接用的 fixture 集合（main.o / libfixture.a）；
  - 输出：`EQUIVALENT` / `DIFFERENT`（附首个差异点）/ `INCONCLUSIVE`（工具缺失）；
  - 禁止依赖设备；只用 host 的 binutils/llvm 工具，缺失时明确 `INCONCLUSIVE`。
- [ ] `tools/compiler-equivalence/fixtures/`：C（结构体/位域/内联汇编禁止）、C++（模板实例化/异常）、错误用例（语法错误）各一组。
- [ ] 确定性开关清单（写进 README）：`-frandom-seed=<stable>`、`-ffile-prefix-map=`、`-fdebug-compilation-dir=`、`-fno-ident`（如适用）。
- [ ] `test_compare_objects.py`：unittest，覆盖 4 种判定 + 工具缺失 + 二进制噪声（追加一个字节）。
- [ ] 文档：明确「SHA 仍可作为同源同参数的辅助信号，但不再是判据」。

## 验收命令

```bash
python -m unittest discover -s tools/compiler-equivalence -p 'test_*.py'
python tools/compiler-equivalence/compare_objects.py --a a.o --b b.o --fixtures fixtures/c
```

## 边界

- 不引入 pytest；用 unittest discover（与仓库现有工具一致）。
- 不修改 AX-06 已有证据与报告；只在其结论里注明「SHA 判据适用范围」。
- 不评价性能。

## 失败信号

- 判据对「只差一个常量」的产物返回 `EQUIVALENT` → 结构判据不够，需补数据段/字符串比对。
- 判据对「同源不同路径」返回 `DIFFERENT` → 白名单不足。

## 证据

- unittest 输出 + 一份 `README.zh-CN.md`（判据、开关、误判边界）。
## Comments

- 2026-10-24（会话 1 agent）：已实现，PR #20（`feat/pvm-05-object-equivalence`，基线 `e834e54`）。四级判据（链接→行为→符号/重定位→差异白名单，差异区域扩展到完整 NUL token 再判定）+ 三态结论（EQUIVALENT/DIFFERENT 附首个差异点/INCONCLUSIVE 不猜）。unittest 13 用例：WSL ubuntu-24.04 13/13（gcc 13.3，ELF 全路径）；Windows Git Bash 13 通过、其中 7 个 ELF 依赖用例因 PE/COFF 无法被 readelf 解析而**显式 skip**（票面「缺失即 INCONCLUSIVE/不假装」的边界处理）。AX-06 报告已追记第 9 节「SHA 判据适用范围」（只增不改，随 PR #20 入库）。deviation：LTO 胖对象 v1 不支持；跨编译器组合必判 DIFFERENT（README 已注明）。

- 2026-10-24（续）：CI 接线补齐——`tools/compiler-equivalence/**` 加入 fastlane-zero-device 的 paths 与 Python lane（`check_zero_device_workflow.py` 本地复验 WORKFLOW_OK）；PR #20 CI 全 10 门 pass，其中 toolbox job 真跑 13 用例。过程记录：apk-bin-gate 一次 CXX1101（NDK 残缺安装，`|| true` 吞错）为基础设施瞬断，空提交复跑即绿。

- **2026-11-04（设备窗轮对账）**：`gh pr view 20` 实测 state=MERGED（mergedAt 2026-10-24T07:54:16Z），置 implemented。四级判据工具已入库，真实产物的等价跑分归 DV-PVM-02 真机面。
