# Ticket 25 实施报告

- 票号/标题：25 — Guest 资产构建基线
- 执行 session / 模型 / thinking：Ticket 25 implementation Worker（GPT-5.6 Sol / max）+ Codex Orchestrator 验收接管（GPT-5）
- 分支：`ticket/25-guest-assets`　基线 commit：`f523f4b`
- 验收环境：VMware `vctl 1.1.0` / Ubuntu 24.04 amd64 pinned builder / QEMU 8.2.2 TCG
- 报告性质：实现、真实 Linux/QEMU 验收、正式双轴 review 与 `main` 集成终态

## 1. 任务目标

按 Guest Kernel 契约建立固定输入、可复现的 ARM64 guest 资产流水线，生成 kernel
`Image`、确定性 initramfs 与 `SHA256SUMS`；initramfs 内完成首次启动 Provisioning
与最小 Guest Agent 基座，并以缺项即失败、双 clean build 逐字节比较和 QEMU AArch64
冒烟真实验收。

## 2. 实际完成

- ✅ 单命令构建：`./guest-assets/run-in-builder.sh build` 固定 builder digest、Ubuntu
  snapshot、全部源码 URL/SHA256、构建时间和输出 manifest。真实 `final-build`
  在 Linux builder 中完成，`rc=0`，产出 `Image`、`initramfs.cpio.gz`、最终 configs、
  notices/licenses 与 manifest。
- ✅ 可复现构建：`scripts/check-reproducible.sh` 在两个独立临时根中执行完整 clean
  build，随后比较 manifest 并逐项 `cmp`；终态标记
  `DROIDLAYER_REPRODUCIBILITY_OK`，`rc=0`。
- ✅ verify-or-fail：真实合并临时 fragment、执行 `olddefconfig` 后，verifier 拒绝
  `CONFIG_EXT4_FS` 缺项并在生成 `Image` 前退出；标记
  `DROIDLAYER_CONFIG_REJECTION_OK symbol=CONFIG_EXT4_FS`，`rc=0`。
- ✅ QEMU Provisioning：最终 ARM64 `Image` 在 QEMU TCG 中真实启动，从空 2 GiB raw
  Root Volume 完成 ext4 格式化、官方 Alpine minirootfs 校验/解包、随机 token 注入、
  Guest Agent/scaffold 安装与完成标志；随后用 `debugfs` 回读并验证 distro、token、
  agent、provision marker、token mode `0600`、journal 与 extent。终态标记
  `DROIDLAYER_QEMU_SMOKE_OK`。
- ✅ Guest Agent：静态 C 基座仅从显式 token 文件读取凭据；进程 I/O 测试覆盖 v1
  握手、错误 token、unsupported version、非法 token 与缺 token 文件。
- ✅ 供应链/许可证：五个远端输入均以 SHA256 锁定并在使用前校验；输出包含 GPLv2、
  source offer、精确 source inventory、e2fsprogs/musl notices 与实际编译来源 attribution。
- ✅ 范围：产品实现仅在 `guest-assets/**` 和 `.github/workflows/guest-assets.yml`；行政
  修改仅为根 `.gitignore`、Ticket 25 tracker 和本报告。未启动 Ticket 26。

## 3. 未完成 / 与预期偏差

验收项无未完成项。验证编排存在一个必须明确说明的偏差：主 runner 在 cycle 9 的
`qemu-smoke` 首次调用因默认 QEMU ROM 查找失败而进入等待。runner 只支持整轮重试，
而用户明确禁止重复已经通过的 targeted-tests、config-negative、reproducibility 和
final-build，因此在**同一个** `dl25-full6` 容器/CRX VM 根中只恢复执行 qemu stage。

单-stage resume 成功后，主 runner 通过 abort marker 退出以终止等待，所以
`15-full-verify.exit=125` 表示“等待循环被有意结束”，不能误写成全流程单进程 `rc=0`。
完整验收由以下连续证据共同成立：cycle 9 前四项均 `rc=0`，以及
`16-qemu-smoke-resume.exit=0`、最终 result marker 和 console/disk assertions。没有用
历史成功替代本轮 clean build，也没有绕过或降低 gate。

## 4. 修改的模块

- `guest-assets/inputs`、`kernel`：不可变输入、kernel fragment、必选能力清单。
- `guest-assets/src`、独立 Gradle build：config verifier、manifest generator 与测试。
- `guest-assets/agent`、`initramfs`：最小 Guest Agent、Provisioning init、disabled
  OpenRC-compatible scaffold。
- `guest-assets/build.sh`、`run-in-builder.sh`、`ci`、`scripts`、`smoke`：固定 builder、
  build/negative/reproducibility/QEMU 入口。
- `guest-assets/GPL-SOURCES.md`、`SOURCE-OFFER.txt`：对应源码与书面 offer。
- `.github/workflows/guest-assets.yml`：Linux 验收 workflow。
- 根 `.gitignore`：仅忽略 `guest-assets/.work/` 与 `guest-assets/out/`。
- Ticket 25 tracker 与本报告：仅本票状态和实施事实。

未修改 `app/**`、Ticket 26、spec、`CONTEXT.md`、ADR、ticket graph、依赖、其他 ticket、
既有 research 或历史完成报告。

## 5. 重要设计决策

- Builder 以 digest 固定；追加 APT 源固定为不早于 base image 创建时间的
  `20251017T000000Z`，只启用 noble / noble-updates / noble-security。Snapshot 仅冻结
  追加安装，不宣称回溯 base filesystem。
- musl 从校验过的源码构建 sysroot，生成显式 specs wrapper 和完整
  `aarch64-linux-musl-*` binutils prefix；upstream specs generator 由 `sh` 调用，避免
  archive executable bit 差异。
- BusyBox 输出目录显式创建；只强制 Provisioning 所需 applets。ARM64 交叉构建显式
  关闭不需要且与当前 UAPI/architecture 不兼容的 `CONFIG_TC`、
  `CONFIG_SHA1_HWACCEL`、`CONFIG_SHA256_HWACCEL`，并在 `oldconfig` 后逐项回验。
- full-build 负例仅接受 `/tmp/droidlayer-guest-assets.*` 下的临时 fragment；不修改
  tracked kernel fragment，且 verifier 位于 kernel 编译前。
- token 每次 smoke 由 `/dev/urandom` 生成，只存在于临时输入与 Root Volume；不进入
  build artifact、Git 或日志。
- QEMU 使用 `-nodefaults`，避免测试无关的默认 PCI ROM；CPU 固定为稳定 ARMv8
  `cortex-a57`，避免 QEMU 8.2.2 `max` model 的 ARM TCG internal assertion。串口 CRLF
  在做 exact-line marker 比较前仅移除 `\r`；判断仍要求完整精确行。
- smoke 将已校验 Alpine archive 复制到隔离输入目录，而非依赖跨 filesystem hardlink。
- Alpine minirootfs 不含 `/sbin/openrc-run`；本票只安装 disabled service scaffold，
  不声称二阶段 rootfs 已能 OpenRC boot-ready。

这些均为 Ticket 25 内的构建参数、文件布局或 smoke harness 取舍，未触发
Architecture Change Gate。

## 6. 测试

### 6.1 最终 gate

| Gate | 真实结果 | 关键证据 |
| --- | ---: | --- |
| vctl tmpfs preparation | `rc=0` | `/dev/shm` 为 tmpfs，并扩至 1 GiB |
| vctl APT cache preparation | `rc=0` | archives 重定向到 `/dev/shm/apt-archives` |
| pinned snapshot APT install | `rc=0`, 870s | 21 个关键包版本写入 builder package ledger |
| targeted-tests | `rc=0`, 14s | 15 JVM/process tests + syntax + LF + ShellCheck |
| config-negative | `rc=0`, 816s | `CONFIG_EXT4_FS` 缺项在 Image 前被拒绝 |
| reproducibility | `rc=0`, 14,953s | 两个独立 clean roots；manifest diff + 每项 `cmp` |
| final-build | `rc=0`, 6,279s | `DROIDLAYER_GUEST_ASSETS_BUILD_OK` |
| qemu-smoke | pass, 5s | 同容器 single-stage resume；两个终态 marker + disk 回读 |

主 ledger：`guest-assets/.work/validation-20260906/15-full-verify-stages.tsv`；主日志
SHA256 为 `02ad67fe818fd28b142e57eaf8a729cf88ef81f95303ea8a8d5bdd5605940992`
（16,735,134 bytes）。QEMU resume ledger：
`guest-assets/.work/validation-20260906/16-qemu-smoke-resume-summary.txt` 与 `.exit`。
这些均为 ignored 本地证据，不提交 Git。

### 6.2 JVM、协议与静态检查

- 15 tests，failures 0、errors 0、skipped 0：`ConfigVerifierTest` 5、
  `GuestAgentProcessTest` 5、`GuestAssetsToolTest` 2、`Sha256ManifestTest` 3。
- Bash/sh syntax、LF-only、final newline、trailing whitespace 和 ShellCheck 在 cycle 9
  targeted-tests 中通过。QEMU harness 后续最小修复另以 MSYS2 Bash 5.2.37 执行
  `bash -n guest-assets/smoke/qemu-provisioning-smoke.sh`，`rc=0`。
- Git whitespace：`git diff HEAD --check`，`rc=0`。

### 6.3 最终产物

| 文件 | bytes | SHA256 |
| --- | ---: | --- |
| `Image` | 52,136,448 | `94b87ebfa1f20d8663e25ab3441e1f02274374d1c221df0bc9874956fc510d65` |
| `initramfs.cpio.gz` | 1,091,332 | `f82a18e077fdeac9f6e98a0231276b76d0fb613a9b90ac61c5ead1802f96a8ad` |
| `SHA256SUMS` | 995 | `47294bd0b2bfe66f5de2e024f31dabbf3df0ca1827ef3cfc2c270117c40d1c87` |
| `kernel.config` | 323,620 | `2f5e06160ebe8393e3d903c3d95b2fb55e5d84dcb017cdd8ba3b68b11d757b03` |
| `busybox.config` | 29,535 | `a5b644102d77392020a3cdb27497f3992db0afb2e38f4ddcefbc90c0bdfb4821` |
| `qemu-smoke.log` | 16,917 | `929d0888ff8e0318402986962aad2c46eddab1ea64afabc0c06734fc4684cf` |
| `qemu-smoke-result.txt` | 25 | `14cd055f91b111ae316077ec072c8adb5d2a7eb6f9dd9d81bcca4a8fa68e6e18` |

### 6.4 保留的失败证据与最小修复

1. 首次 APT install：CRX/v9fs 路径上的 `.deb` 出现 unexpected EOF，`rc=100`；将 APT
   archives 放到 `/dev/shm` 后，同一 signed snapshot 严格校验安装成功。
2. targeted cycle 1：缺 JUnit BOM POM verification metadata；核对 artifact 后补精确
   SHA256。
3. targeted cycles 2–3：Guest Agent host GCC 在 `-Werror` 下报 token buffer
   `stringop-overflow`；只把 NUL/newline 容量从 `+1U` 调整为 `+2U`。
4. targeted cycle 4：ShellCheck `SC2086`、`SC2013`、`SC2034`；分别修复 quoting、
   `/proc/cmdline` 读取和无用 loop variable。
5. reproducibility cycle 5：upstream `musl-gcc.specs.sh: Permission denied`，`rc=126`；
   改由 `sh` 执行。
6. cycle 6：BusyBox `O=` output directory 不存在，`rc=2`；显式创建。
7. cycle 7：BusyBox `CONFIG_TC` 与 Linux 7.2.3 UAPI 不兼容，同时暴露 x86 SHA
   acceleration default；cycle 8 隔离为 `sha1_process_block64_shaNI` 未声明。最终只关闭
   三个非必需符号并在 config 中回验；cycle 9 双 clean build 通过。
8. qemu 首次：缺默认 `efi-virtio.rom`；加 `-nodefaults`。
9. qemu 诊断：QEMU 8.2.2 `-cpu max` 触发 `regime_is_user` internal assertion，退出
   134；固定 `cortex-a57`。
10. qemu 诊断：cache→smoke 临时目录 hardlink 跨 filesystem 失败；改为复制。
11. qemu 首次成功启动后，console marker 带标准 serial CRLF，exact-line grep 未命中；
    只在比较前移除 CR，随后完整 smoke 通过。

没有删除这些日志或用最终成功覆盖失败事实。

### 6.5 宿主/外部 runtime 变化

- `C:\Users\Nyzee\.vctl\bin\crx.vmdk`：工具强制 user-level runtime resource；目标原
  不存在，新建且无覆盖，196,608 bytes，SHA256
  `aa2ca5154f7775371c2ef9e6d5dbb3eab49b1b4a367e0a92e1e92c93e7bce556`。
- VMware `vctl` 创建/修改用户级 `C:\Users\Nyzee\.vctl\storage`、runtime state、
  `containerd.log`、`config.toml`，并准备其管理的 `kubectl.exe` / `kind.exe`。这些路径
  由 vctl 强制，无法合理重定向到项目目录；除 `crx.vmdk` 外没有逐文件 pre-state，
  因而不虚报其 overwrite 状态。
- 一次本意为只读的 `vctl system config` 调用实际重新执行既有 storage/network
  preparation；终态配置为 VM 4 cores / 6144 MiB、既有 `vmnet8`。未修改 Windows
  Registry、system PATH、services、drivers、firewall、Program Files 或 OS virtualization
  feature。
- 容器内临时 remount `/dev/shm`、APT config 和 package install 随 CRX/container
  移除；未做 Windows system-wide package installation。
- 临时 `subst R:` 只用于规避非 ASCII mount path，已经解除。vctl runtime 已执行
  `vctl system stop` 并确认 stopped；没有 prune/delete 任何既有 image/layer。
- 观察到 `C:\Users\Nyzee\.vctl\.r\vms\dl25-work` 和 `dl25-mount` 目录残留；无运行
  VM，未直接删除。预期由 VMware/vctl 的受支持清理流程后续处理，避免破坏 runtime
  state。

## 7. Benchmark / 实测数据

本票不设产品性能 benchmark。上述 elapsed time 仅是 vctl/TCG builder 验收墙钟数据，
不得外推为 Android 真机启动或运行性能。

## 8. 风险

- `vctl 1.1.0` 的 CRX/v9fs 对 APT archive 与工具 exec 有兼容性缺陷；正式 CI 应使用
  pinned builder 的常规 Linux filesystem，而不是依赖本次 `/dev/shm` workaround。
- Linux/BusyBox/QEMU 均是精确固定组合；升级任一输入时必须重跑 negative、双 clean
  build 与 QEMU smoke，不能沿用本报告结果。
- source-offer 支持渠道在发布后的有效期内必须持续可用，这是发布运营义务。

## 9. 新发现

- BusyBox 1.37.0 的 SHA hardware acceleration defaults 在 ARM64 cross build 上仍可被
  `defconfig/oldconfig` 选中，必须由目标架构配置显式收敛。
- QEMU `virt` 的测试确定性不仅依赖 machine，还依赖默认 devices/ROM 与 CPU model；
  smoke 已把两者显式固定。
- serial console 的 CRLF 是 transport 表现，marker gate 应先做最小换行归一化，再做
  exact-line 比较。

以上均未改变产品定义、ADR 或 Full Linux contract。

## 10. 对后续 ticket 的影响

- Ticket 25 验收通过后，Ticket 26 的直接依赖可由 Orchestrator 判定解除；本次仍未
  启动、领取或实现 Ticket 26。
- Ticket 26 应消费本票固定输出和 Provisioning marker，不在 host 层重复构建 Root
  Volume；它仍负责 host transport、终端/ping 与二阶段 lifecycle。
- OpenRC service 仍有意 disabled；只有 transport byte stream 与二阶段 init 依赖明确
  后才能启用。

## 11. Commit

- 基线：`f523f4b1216fcf1cfe6f6436c7ce6d59c3027965`。
- `337d3bb86b77a95eb348785dc7297cef1954cb4b` — deterministic verification tools。
- `f76dd0781d702acb593945d7e1dfaa9fdee96173` — Provisioning Guest Agent。
- `fa6ce855353c9bc2abede132c339bb2d3c103057` — pinned kernel/initramfs build。
- `38b4a3ab65efc760175bf321903a12aac35a440d` — negative/reproducibility gates。
- `14e15b7a314ba14f6779ce77792ff74b79a0a095` — QEMU Provisioning smoke。
- `b72a3b3607425e1bd6871e33eeb456c4de4e427a` — pinned CI verification。
- `4ea236ae3cf1d9fd21a345e4a7936c610e9fff75` — workflow documentation。
- `5447d47c9c777fc5be583e4ec6409a3f9bb19d67` — executable script modes。
- `7045b0c5665e025c1cc12009500eb67bc39f5a9c` — 提交本报告与 tracker 的
  `review` 状态。
- `4a144733d30ecf08dc13c117c8ce90473d6fb204` — 正式双轴 review 报告与 approve。
- `590cc17dafd93dbbba4993976a03e5fff51f3d5e` — Orchestrator 以 `--no-ff` 集成到
  `main`。
- Ticket `completed` 状态与本报告终态由当前关闭行政 commit 承载。

真实验收最初对应聚合快照 `75c9f891e4685439589b0cc0dc4b71a89d29ad7b`。正式
Standards review 要求小步提交后，仅重组 Git 历史；上述八个 commit 的实现树与该快照
在 `.github/workflows/guest-assets.yml`、`.gitignore` 和 `guest-assets/**` 上逐字节一致，
没有改变已验收代码或以历史结果替代不同实现。

## 12. Ticket 状态

当前状态为 `completed`：四项 acceptance criteria、规定测试、真实 Linux 双 clean
build 和 QEMU smoke 均已满足，scope audit 无越界；正式双轴 review 结论为
`approve`，Orchestrator 已用 `--no-ff` 合并到 `main`。Ticket 26 未启动、领取或修改。

报告人：Ticket 25 implementation Worker + Codex Orchestrator ｜ 日期：2026-09-07 ｜ 耗时：vctl 验收 56,330.691s（含多轮真实失败与 clean rebuild）
