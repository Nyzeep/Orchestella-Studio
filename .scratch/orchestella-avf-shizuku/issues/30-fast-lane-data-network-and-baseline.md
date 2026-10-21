# 30: Fast Lane 数据面、vsock 网络代理与性能基线

> **2026-10-21 拆分**：执行票已迁至 `.scratch/orchestella-fastlane/` 的 P05/P06/P08/P09/P10 + **DV-03**。本文件保留历史定义，不再单独推进。

Type: prototype / experiment
Status: blocked-by-28, 29
Priority: P1（Fast Lane 可用性与收益门）
Owner: Fast Lane data-plane owner；性能样本由主协调/设备操作者执行
Blocked by: 28 真实 payload；29 sealed toolbox 至少 shell + 一个编译器
External gates: 同 ROM、同 QA 窗口、控温、n≥10；网络代理不改变 Full Lane 契约

## 要回答的问题与价值

Fast Lane 有了 sealed Linux 工具后，能否形成可用、可持久、可联网、可测量的工作区：

1. encrypted storage 能否作为持久 home，并安全导出/导入？
2. 只有 vsock 的情况下，能否通过宿主代理提供 TCP（HTTP/HTTPS/git/pip）？
3. 对 QEMU Full Lane，Fast Lane 在哪些 workload 上真的有性能优势？
4. QEMU 自身的调优能否缩小差距，而不引入回归？

这个票决定 Fast Lane 是“产品功能”还是“实验 demo”。

## 允许/禁止范围

允许：

- payload 监听 vsock 端口；宿主侧实现 SOCKS5/HTTP CONNECT；guest 工具通过 `ALL_PROXY`/`http_proxy` 使用。
- DNS 由代理解析；只承诺 TCP；不承诺 ICMP/raw socket/UDP 广播。
- guest 侧 tar → vsock → SAF 导出；SAF → vsock → guest 导入。
- 同设备、同 1-2 vCPU、同 RAM 对比 QEMU；n≥10、控温、median/IQR。
- QEMU 单变量实验：`-cpu max,sve=off,pauth-impdef=on`、`tb-size=256/512`、每盘 iothread、`num-queues`、`aio=threads`、`discard=unmap`、`detect-zeroes=unmap`、guest `mitigations=off`、`mq-deadline`、ZRAM。
- 保留失败样本；默认值不变，除非达到统计门槛。

禁止：

- 在 Fast Lane 声明原生 `eth0`/NAT/DNS/端口转发/ICMP。
- 把宿主代理写成“原生网络”；不把代理端口默认暴露 LAN。
- 用 pVM 内部编译时间代替端到端时间；不把 AVF 平台声明当性能结果。
- 为“更快”修改 QEMU 默认值而不做 A/B；不使用 `cache=unsafe`。
- 把 encrypted storage 明文复制到宿主/日志；导出必须 guest 侧。
- 修改 Full Linux 的网络/存储契约来适配 Fast Lane。

## 行为验收

### 数据

- [ ] `/mnt/encryptedstore/home` 写入文件；stop/start、设备重启后仍在。
- [ ] 导出 tar 经 vsock 到 SAF；导入反向；文件 hash 一致；host 不落明文。
- [ ] 删除 VM 的语义明确：数据不可恢复；UI 二次确认；不做“回收站”假承诺。

### 网络

- [ ] 宿主 SOCKS5/HTTP CONNECT 代理可用；guest 设置 `ALL_PROXY`/`http_proxy`。
- [ ] `curl https://example.com` 成功；`git clone` 公网仓库成功；`pip install` 纯 Python 包成功。
- [ ] DNS 由代理解析；TLS 证书校验正常；不要求 ICMP。
- [ ] 代理只绑定 127.0.0.1；不默认暴露 LAN；失败有 typed reason。
- [ ] 长连接/大文件/并发连接有基本稳定性；记录吞吐/延迟。

### 性能

- [ ] 同设备、同资源、控温、n≥10；记录设备型号/Android/电池温度/热状态。
- [ ] CPU：sysbench/openssl/xz；编译：同一 C 项目；I/O：fio/dd；启动：payload ready；网络：代理吞吐/延迟。
- [ ] Fast Lane 至少一个 CPU/编译 workload 对 QEMU ≥2x；否则不产品化。
- [ ] QEMU 调优至少一个 workload 统计显著改善且无 >5% 回归；否则保留默认。
- [ ] 所有失败/取消/回退样本保留；不只报成功样本。

## 测试接缝与证据

- 复用 28/29 的 payload/terminal；新增宿主代理与导出/导入 QA harness。
- 证据根：`protected-fastlane/20261021/f3/`；包含：
  - 数据持久化/导出/导入记录；
  - 代理连接/吞吐/失败记录；
  - 性能样本表（CSV/TSV，含 not-tested）；
  - QEMU A/B 参数与结果；
  - 脱敏日志。
- 不把“代理能连一次”当网络通过；至少覆盖 DNS/TLS/大文件/并发。

## 结果分类

`pass_fastlane_data_plane` / `pass_with_network_limits` / `rejected_at_proxy` / `no_performance_advantage` / `blocked_external`。

## 停止条件

- 代理无法支持 DNS/TLS/长连接：Fast Lane 只做终端/离线；不声明联网。
- 导出/导入无法保证 host 不落明文：停止产品化。
- Fast Lane 对 QEMU 无 ≥2x 优势：停止 Fast Lane 产品化，保留实验。
- QEMU 调优无显著收益或有回归：保留默认，记录负结果。
- 需要修改 Full Lane 契约/端口默认暴露/Root：停止。

## 数据、许可与回滚

- 只用新 QA 实例/新 storage；不操作正式数据。
- 代理/导出代码自研；第三方库逐项许可/SHA。
- 性能样本脱敏；不含序列号/账号/token/按键正文。
- 报告：`docs/reports/prototypes/fastlane-30/prototype-report.zh-CN.md`。
