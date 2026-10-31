# research/uml-fabric-probe — 2026-11-07 真机窗口批次的探针资产

对应批次报告：`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`（T1–T7）；
T7 网络面修正配方与根因裁决：`docs/research/2026-11-08-t7-network-closure.md`。
原始数据与日志在仓库外 `evidence/uml-fabric-20261031/`（SENSITIVE_DEVELOPER_DATA_POLICY）。

## 内容

- `gen_c200.py` — F-MTTCG-C200 夹具生成器（200×10 确定性 LCG；用法 `python gen_c200.py <outdir> [N_FILES] [FUNCS_PER_FILE]`；输出 fixture.h/f*.c/Makefile/MANIFEST.sha256）。
- `guest-overlay-uml/` — UML ext4 rootfs 的 `/init` 与 `/work/*.sh`（bootprobe/t2/t3burn/resident，配合内核 cmdline `work=` 与 `ticks=` 参数；计时用 /proc/uptime——alpine busybox date 无 %N）。
- `guest-overlay-qemu/` — QEMU guest ext4 镜像 overlay（`init` 支持 `j=` 参数；`makebench.sh` = make -j $RES_J + .o 聚合 sha256；`qprobe.sh` 冒烟）。镜像构建：WSL `mkfs.ext4 -F -d <staging>`（alpine-minirootfs + Alpine clang22 工具链 tar + `make` apk + c200 夹具）。
- `guest-overlay-qemu/qrun.sh` — 设备端 QEMU 启动器，双模式：
  - `qrun.sh <smp> <logfile>`：T4 makebench 矩阵（`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp N -m 6371M -nodefaults … -serial file:`），2026-10-31 轮形态不变。
  - `qrun.sh net`：T7 网络面直驱（2026-11-08 新增）——gvproxy 以产品 `-config` 形态先起、/proc/net/tcp LISTEN 就绪检查（禁止连接式探测）、QEMU 按 `QemuLaunchSpec.buildWithPasst(DEFAULTS)` 逐字拼装 `-netdev socket,id=orchestella-net,connect=127.0.0.1:<port>` + `-device virtio-net-device,netdev=orchestella-net`。注意：QEMU/kernel/Image 从会话 22 的 oe36.apk 解包（`libdroidlayer_qemu.so`=QEMU 11.1.1、`assets/Image`=droidlayer 7.2.3 kernel）；gvproxy 同源解包 `libgvproxy.so`；后台存活须 `setsid … & < /dev/null` + 启动侧 sleep + pidof 校验。
- `t7/orchestella-guest-provision.pacman-probe.sh` — 产品 `guest-assets/scripts/orchestella-guest-provision.sh` 的探针副本，唯一差异：`:509` `pacman -Syu` → `pacman -Sy`（任务要求"pacman -S --needed"；因无同步库时 `-S` 无法解析包、10-29 超时元凶是 `-u` 全量升级，故保留 `-y`；diff 已核对仅此一行）。
- `t7/probe-init.sh` — Arch guest 探针 init（gvproxy 静态网 192.168.127.15/24，网关 .1，DNS=产品 GUEST_DNS 223.5.5.5 + 跑 provision `--native` + 输出 `ORCHESTELLA_GUEST_PROVISION` 状态与 state 文件）。2026-11-08 增加网络面分层诊断（ARP/ping 网关/DNS/eth0 tx-rx 计数三取样），失败层一步定位。
- `t7/gvproxy-t7.yaml` — gvproxy 配置文件（产品 `NetworkGatewayDaemon.configuration()` :335-359 的 shell 域等价形；唯一差异：省略 `services` API unix socket——shell 域 bind unix 被 SELinux 拒，且本探针不需要端口转发 API）。

## T7 网络面硬规则（2026-11-08 裁决，来源见 closure 文档 §3）

1. **每个 gvproxy 进程生命周期只接受一次 QEMU 连接**（单发 accept）。任何探活连接、上次 QEMU 残留连接都会把 accept 槽位烧掉并打 `cannot read size from socket: EOF`——重启 QEMU 前必须重启 gvproxy。
2. **禁止对 qemu 监听端口做任何连接式探活**（nc/curl/`</dev/tcp`）。就绪判定只用 LISTEN 检查（产品 `portBound` 同型，NetworkGatewayDaemon.java:602-607；产品提交 109b43d5 实测踩坑：连接式探测被当作 QEMU 客户端 → EOF 掉线）。
3. **先 gvproxy 后 QEMU**：QEMU `-netdev socket,connect=` 连接失败是致命且无重试（QEMU net/socket.c），必须等 gvproxy 端口进入 LISTEN 再拉 QEMU（产品序：NetworkGatewayDaemon.start → GATEWAY_READY → QEMU）。
4. 打包版 QEMU 三条真机约束（产品提交 109b43d5）：socket netdev 不支持 `addr.type` 新语法 → 用 legacy `connect=host:port`；UNIX socket 无支持（sun_path 108 限制）→ gvproxy 监听 127.0.0.1 回环 TCP；`virtio-net-pci` 需要 efi-virtio.rom（打包无 ROM）→ 用 `virtio-net-device`（virtio-mmio）。

## 复现要点（设备轮实测约束）

1. 研究 App（uml-runner）每次 `am start` 前必须 `am force-stop`，否则 intent 进旧实例、无 onCreate、整轮假数据。
2. UML SMP 内核多核旋钮 = `ncpus=N`（`--cpus=N` 无效，默认 host-1）。
3. Alpine make 4.4.1 在 Android shell 须 `--jobserver-style=pipe`；musl 二进制用 `tc/lib/ld-musl-aarch64.so.1 --library-path` 包装。
4. native 与 guest 同夹具同工具链时 `.o` 聚合 sha256 必须逐字节一致（本批 = 5f94de57…），任何不一致样本作废。
