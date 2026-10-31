# research/uml-fabric-probe — 2026-11-07 真机窗口批次的探针资产

对应批次报告：`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md`（T1–T7）。
原始数据与日志在仓库外 `evidence/uml-fabric-20261031/`（SENSITIVE_DEVELOPER_DATA_POLICY）。

## 内容

- `gen_c200.py` — F-MTTCG-C200 夹具生成器（200×10 确定性 LCG；用法 `python gen_c200.py <outdir> [N_FILES] [FUNCS_PER_FILE]`；输出 fixture.h/f*.c/Makefile/MANIFEST.sha256）。
- `guest-overlay-uml/` — UML ext4 rootfs 的 `/init` 与 `/work/*.sh`（bootprobe/t2/t3burn/resident，配合内核 cmdline `work=` 与 `ticks=` 参数；计时用 /proc/uptime——alpine busybox date 无 %N）。
- `guest-overlay-qemu/` — QEMU guest ext4 镜像 overlay（`init` 支持 `j=` 参数；`makebench.sh` = make -j $RES_J + .o 聚合 sha256；`qprobe.sh` 冒烟）。镜像构建：WSL `mkfs.ext4 -F -d <staging>`（alpine-minirootfs + Alpine clang22 工具链 tar + `make` apk + c200 夹具）。
- `guest-overlay-qemu/qrun.sh` — 设备端 QEMU 启动器（`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp N -m 6371M -nodefaults … -serial file:`）。注意：QEMU/kernel/Image 从会话 22 的 oe36.apk 解包（`libdroidlayer_qemu.so`=QEMU 11.1.1、`assets/Image`=droidlayer 7.2.3 kernel）；后台存活须 `setsid … & < /dev/null` + 启动侧 sleep + pidof 校验。
- `t7/orchestella-guest-provision.pacman-probe.sh` — 产品 `guest-assets/scripts/orchestella-guest-provision.sh` 的探针副本，唯一差异：`:509` `pacman -Syu` → `pacman -Sy`（任务要求"pacman -S --needed"；因无同步库时 `-S` 无法解析包、10-29 超时元凶是 `-u` 全量升级，故保留 `-y`；diff 已核对仅此一行）。
- `t7/probe-init.sh` — Arch guest 探针 init（slirp 静态网 10.0.2.15/24 + 跑 provision `--native` + 输出 `ORCHESTELLA_GUEST_PROVISION` 状态与 state 文件）。

## 复现要点（设备轮实测约束）

1. 研究 App（uml-runner）每次 `am start` 前必须 `am force-stop`，否则 intent 进旧实例、无 onCreate、整轮假数据。
2. UML SMP 内核多核旋钮 = `ncpus=N`（`--cpus=N` 无效，默认 host-1）。
3. Alpine make 4.4.1 在 Android shell 须 `--jobserver-style=pipe`；musl 二进制用 `tc/lib/ld-musl-aarch64.so.1 --library-path` 包装。
4. native 与 guest 同夹具同工具链时 `.o` 聚合 sha256 必须逐字节一致（本批 = 5f94de57…），任何不一致样本作废。
