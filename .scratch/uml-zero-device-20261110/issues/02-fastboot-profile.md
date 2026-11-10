# 02: Fast-boot UML profile — 最小内核 + 内置 initramfs + 预烘焙 rootfs

Type: implementation
Status: resolved（u1-fastboot，2026-11-10）
Priority: P0（直接回答“为什么不能像 proot 一样快”的产品承诺）
Owner: u1-fastboot
Blocked by: None（与 01 并行；计时字段必须对齐 01 的 T0–T3）
External gates: 真机 ARM64 boot `not-tested`；16K 宿主 `not-tested`

## 背景

内部真机证据已经证明 UML 内核本身不是慢的来源：

- Android 17 App 域启动 220–400 ms，中位 ≈330 ms；fork+exec 1.49×、纯 CPU 1.08×（`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §1）；
- ext4 + `work=bootprobe` 20 循环，19/20 干净，boot 0.22–0.40 s（`docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §5.1）；
- SMP `ncpus=4` 启动 0.23–0.43 s，和 UP 0.21–0.43 s 同量级（同上 §1–§2）。

但当前研究 runner 的启动参数与 rootfs 形态仍是“研究形态”：hostfs/ext4、`um-init`、外置 rootfs、首启仍可能触发 provisioning。用户看到的“要等很多流程”主要来自 QEMU 全 VM 与首启装配，不是 UML 内核 boot。本票把 UML 的 fast path 做成可复现 profile，并明确 fast shell 与 full systemd 两档。

## 必须做

1. 新增 `research/uml-fastboot/`：
   - `defconfig-fast`：最小 UML 内核配置；保留 cgroup v2、devtmpfs、devpts、proc/sysfs、ext4、ubd、hostfs、vector（按需）；明确裁掉哪些能力及原因；
   - `initramfs/init`：只做 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2 挂载，然后 exec `/um-command.sh` 或 shell；失败必须输出结构化错误并非零退出；
   - `build-fast.sh`：复用 `research/uml-kernel-build` 的 pin/工具链；构建 ARM64 内核 + stub + initramfs，产出 sha256 manifest；
   - `manifest.md`：内核 config、stub、initramfs、rootfs 的 sha256 与构建命令。
2. 三组对照（至少两组完成）：
   - UP vs SMP（`ncpus=1` / `ncpus=2/4`）；
   - hostfs vs `ubd0` ext4；
   - 内置 initramfs vs 外置 rootfs。
3. 宿主参考：在 x86_64 宿主构建/启动同源 fast profile，采集 T0–T3；明确标注“宿主参考，不是设备结论”。
4. 报告：`docs/research/2026-11-XX-uml-fastboot-profile.zh-CN.md`，含 fast shell vs systemd 取舍表、T0–T3 表、失败模式、下个设备窗口命令入口。

## 边界

- 不写产品代码、不改主 `:app` 构建、不改版本号/签名/发布通道。
- 不承诺 Docker/systemd 在 fast profile 可用；fast profile 只承诺 T3 first command。
- 不把宿主 x86_64 数据外推为 ARM64 结论。
- 不引入新的内核下游补丁；用现有 pin `169b17ff` 与 16K stub 资产。
- 不改 `research/uml-runner` 的产品形态；新增独立目录。

## 完成判据

- ARM64 内核 + stub 构建成功；`check-16kb-kernel.sh` 通过或明确记录 16K 缺口；
- x86_64 参考 boot 到 `READY`，T0–T3 有 n、中位数、夹具标识；
- fast profile 在断网、无包管理器的情况下可到 T3；
- 报告明确“UML 不能达到 proot 的无内核秒开；目标是亚秒级 T3，且 full systemd 另算”；
- 敏感数据门通过；不写产品代码。

## 风险与停止条件

- **x86_64 参考不代表 ARM64**：宿主只验证协议；设备窗口必须重测，报告不得外推。
- **裁内核丢能力**：cgroup/Docker/网络不可用时，保留 systemd profile，不把 fast profile 当完整运行时。
- **16K 宿主缺失**：只记录 `not-tested`，不宣称 16K 可用。
- 需要 Root/unlock/改 SELinux 才能继续 → 停止并记录。

## Comments

- 2026-11-10 / 主会话（ask-matt 研究轮）：建票。输入：`.scratch/uml-zero-device-20261110/spec.md` §3 U1、`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、UML 设备轮报告。
- 2026-11-10 / u1-fastboot：认领；base 62d96e99ad0334a5cc666f8387ac949fc0f2c025；worktree /workspace-wt/u1。

- 2026-11-10 / u1-fastboot：实现完成，head `daa30557`（claim `8dfa6dfe`）。ARM64 UP/SMP 内核 + 16 KB stub + 内置 initramfs 构建通过；两次干净重建逐字节一致；`check-16kb-kernel.sh` 全过；x86_64 参考 T0–T3 n=5（7 case 全 ok）；报告 `docs/research/2026-11-10-uml-fastboot-profile.zh-CN.md`。ARM64 设备 boot、16 KB 宿主、真 SMP、systemd T4 not-tested；下个设备窗口入口见报告 §8。
