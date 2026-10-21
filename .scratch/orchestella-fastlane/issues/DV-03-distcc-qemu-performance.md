# DV-03: Distcc 跨车道 + QEMU 调优真机性能（串行 3）

Type: device-verification
Status: blocked-by-DV-01/02
Priority: P1（收益门）
Owner: 设备唯一操作者/主协调；性能样本双人复核
Blocked by: DV-01, DV-02, P08, P09
External gates: 同设备、同镜像、同资源、控温、n≥10

## 目标

回答两个问题：

1. QEMU Full Lane 的编译能否通过 Fast Lane 的硬件 vCPU 获得端到端加速？
2. QEMU 自身的调优是否有统计显著收益且无回归？

## 步骤

### A. Distcc

1. QEMU guest 安装 distcc/clang；`CC=distcc clang`；`DISTCC_HOSTS=127.0.0.1:<port>`。
2. Fast Lane 启动 `distccd --inetd` 桥；宿主 TCP↔vsock。
3. 最小 C 文件：远端编译 `.o`，guest 链接/运行。
4. 真实项目：单核/`-j2`/`-j4` 对比本地编译 vs distcc（同源/同 flags）。
5. 记录端到端墙钟、预处理/传输/编译/链接、失败率、重试。

### B. QEMU tuning

1. 基线：当前默认。
2. 单变量：CPU max、tb-size、iothread、aio、discard/zeroes、guest mitigations。
3. 同设备/同镜像/同资源/控温/n≥10；median/IQR；失败样本保留。
4. 至少一个 workload 显著改善且无 >5% 回归才改默认。

## 验收

- [ ] distcc 远端 `.o` 与本地 `.o` 可链接/运行；最小 C 通过。
- [ ] 真实项目端到端 ≥1.5x 才产品化；否则记录负结果。
- [ ] 编译器版本/target/flags 一致；C++ 标 `not-tested`（除非单独验证）。
- [ ] QEMU 每个变体有样本；默认不变，除非达到门槛。
- [ ] 设备温度/热状态/失败样本保留；不只报成功。
- [ ] 不把 Fast Lane 内部编译时间当端到端；不把 AVF 声明当收益。

## 证据

`protected-fastlane/20261021/dv03/`；含：
- guest/Fast Lane clang 版本；
- distcc 配置/端口/日志（脱敏）；
- 本地 vs 远端 `.o` hash/链接结果；
- 端到端计时表（CSV/TSV，n≥10）；
- QEMU A/B 表；
- 失败/回退样本。

## 停止条件

- distcc 协议/编译器不匹配且无合法修复：停止 distcc，保留选道/终端。
- 端到端 <1.5x：停止产品化。
- QEMU 无显著收益/有回归：保留默认。
- 任何变体导致 boot/panic/数据风险：移除该变体。
