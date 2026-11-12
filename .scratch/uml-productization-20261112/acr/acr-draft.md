# ACR 证据包：UML fast-profile 产品化（fast kernel + rootfs 分发双方案）

> 状态：**待投票**（2026-11-12 设备窗收口后定稿）。本包只提供证据与建议，不改产品默认行为——全部实现已入 main 但默认关闭（三 flag：`orchestella.uml.bundledFastProfile` / `orchestella.uml.rootfsDownload` / `orchestella.uml.fastProfile`，默认 false，flag off 零行为由测试逐字节锁死）。
> 证据链：`docs/research/2026-11-12-uml-productization-round.zh-CN.md`（本轮全量数据）；R1/R2 调研报告（同日）。

## 1. 决策问题（待投票）

| # | 问题 | 建议 |
| --- | --- | --- |
| D1 | 是否开启方案 A（fast kernel + rootfs **内置** APK） | 建议开启（fast kernel 内置 ~9.5MB 影响可控；rootfs 内置体积大，见 D2） |
| D2 | rootfs 分发形态：内置 vs 下载 vs 混合（内置 fast kernel + 下载 rootfs） | 建议**混合**：kernel 内置（启动确定性），rootfs 下载（体积/更新面），首启引导可见下载进度 |
| D3 | `linux-fast` 入库 pin：容器可复现版 `21e1c3f9…` vs 真机验证版 `572c3433…` | 建议 `21e1c3f9…`（O1 证明同布局重建逐字节复现；两版功能等价且 572c3433 无完整 digest 在库） |
| D4 | UML 面默认宽度（产品开关后） | 建议 ACR 通过后从 0 宽度升为 1（单实例 UP 内置通道），SMP/多实例后续按需求增长 |
| D5 | GPL §3a 源码交付路径 | 建议 §3a 公开固定 tag + Release 源码包（fork `Raymer8639/linux-um-arm64` pin + `defconfig-fast.fragment` out-of-tree 配置交付），ADR-0014 门禁衔接（R2 §2.4 十条清单全落稿于 `docs/NOTICE.UML-DRAFT.md`） |

## 2. 双方案对比（R2 素材提炼）

| 维度 | 方案 A 内置 | 方案 B 下载 | 混合（建议） |
| --- | --- | --- | --- |
| 首启延迟 | 零下载（实测 T3 250–360ms） | 首次 106MB 下载+校验+解包（分钟级） | kernel 即用 + rootfs 下载引导 |
| APK 体积 | kernel +9.5MB / rootfs +106MB(tar) 或 430MB(ext4) | +~10MB | +9.5MB（Play 150MB 门槛安全） |
| 更新操作面 | 发新版 APK（Termux 模式） | 换镜像不动 APK（UserLAnd/proot-distro 模式） | rootfs 可热更新 |
| 失败模式 | 无网络依赖；sha 记账 fail-closed | 网络失败可镜像轮换；弱网不可用 | 内置 kernel 保底 |
| GPL 交付时点 | 发布前 tag+源码包必须就绪 | 首次资产分发前补齐（NOTICE 先随 APK） | 发布前就绪（取严） |
| 同业先例 | Termux（bootstrap 嵌入 .so）、**Podroid v1.3.0（首个内置 UML 内核同业）** | UserLAnd（纯下载、无哈希校验）、proot-distro（URL+SHA pin） | — |

## 3. 设备矩阵证据（双真机）

### 3.1 device-b（HONOR AGT-AN00 · Android 15 / API 35 · 内核 5.10.236 · 4K 页）——本轮实测

| 组 | n | READY 中位 | exit | device-a（API36/内核6.6）对照 |
| --- | --- | --- | --- | --- |
| U1 内置 initramfs UP | 5 | **250ms** | 0×5 | 330ms |
| U1 SMP ncpus=4 | 3 | **300ms** | 0×3 | 410ms |
| U2 hostfs | 5 | **350ms** | 0×5 | 350ms |
| U2 ext4 | 5 | **360ms** | 0×5 | 350ms |
| seccomp=on/auto 对照 | 3 | 330–370ms | 0×3 | 全落 `Userspace mode: SECCOMP` |
| 2 实例并发 | 双 pid 同框 | — | 双 SECCOMP | RSS 合计 ≈23.7MB idle |
| FGS 60min | 12/12 采样 | — | **frz=0 零冻结** | 同形态 |

### 3.2 U3 12-case 能力矩阵（API 35 profile `aosp-app-android15-arm64`，本轮核心增量）

| case | status | 备注 |
| --- | --- | --- |
| hostfs-boot / ubd0-boot | **pass** | 双 rootfs 通道 |
| ncpus-2 / ncpus-4 | **pass** | SMP 内核（noinitrd 重建 `99f6c3e7…`＝manifest pin） |
| sigchld / clone / mount / personality / setrlimit | **pass** | mount 案例为 guest 内 PASS + 宿主面缺失（API 35 无 mount/umount2）符合预期 |
| seccomp-fastpath | **pass** | 双 marker：CAP 探针 + `Userspace mode: SECCOMP`（真快路径非 ptrace） |
| vector-net / mconsole | **pass**（adb-shell 通道，2026-11-12 同日补全） | sepolicy 一手判定 GO（shell 域 rx_file_perms）；socket 落点 shell_test_data_file；vec0 fd 通道 + mconsole 握手双 PASS |
| fail | **0** | **U3 12/12 全绿**（10 runner + 2 adb-shell），R1 §5.2 预期全实证、0 反例 |

### 3.3 复现性（O1 定界）

pinned 布局（`SRC=/src` + `OUT=/work/research/uml-fastboot/out`）重建逐字节复现容器 pin：linux-fast `21e1c3f9` / noinitrd `a5efce2b`（＝真机 pin）/ stub `87666f91` / config 双件全一致；SMP noinitrd `99f6c3e7` 同样复现。`572c3433` 差异定界于内嵌 initramfs 段（当年构建树路径），源码/配置零漂移。

## 4. 工具链双根因（本轮新知，入 NOTICE/构建文档）

1. **Windows/DrvFs 打包 rootfs 丢 exec 位**（MSYS tar 0644）→ 设备上 guest init EACCES(-13)。对策已固化：`build_guest.sh` 在 POSIX 宿主产出 tar（0755），harness 优先直推。
2. **NDK bionic 静态 init 被 UML 7.2-rc4 arm64 binfmt 拒载**（error -22，BTI/PAC GNU_PROPERTY notes；musl busybox 对照 exec 成功）。对策：guest init 用 glibc/musl 静态（0 GNU_PROPERTY）。

## 5. 合规清单（R2 十条，落稿于 docs/NOTICE.UML-DRAFT.md）

内核 fork pin / 配置 fragment 交付 / 构建环境记录 / stub_exe 许可归属（待裁定）/ busybox+initramfs 归属 / 下载件同等义务 / 书面要约 / Release 门禁衔接 / rootfs 注入文件 / 质量证据隔离——**全清单在 NOTICE 草稿，ACR 通过前不入包、不覆盖 NOTICE.QEMU**。

## 6. 风险与开放项

| 风险 | 缓解 |
| --- | --- |
| 16KB 宿主仍未验证（device-b 也是 4K 页） | 继续 not-tested；16K stub 已产出（wave1），缺设备 |
| vector-net/mconsole 无 runner 通道 | adb-shell 通道下窗补；不阻塞 D1–D5 |
| payload manifest 两处 pin 过期（QEMU 线） | 本轮记录，下轮 QEMU 收口回写 |
| 内置 rootfs 的 Play 150MB 门槛 | D2 建议混合形态规避 |
| rootfs 下载源的官方签名链（Alpine `.asc` 在位 / Debian SHA512SUMS 签名存疑） | R2 §3 三层校验管线已**实现闭环**（L3 五阶段 done + transport 接线 RootfsTransport、官方 pin 三方锁定 6bf49190/3,981,043 B；fail-closed 全链契约测试） |

## 7. 投票入口

- 投票前先核对：`docs/research/2026-11-12-uml-productization-round.zh-CN.md`（数据）+ 本包 §3/§5/§6。
- 投票动作：D1–D5 逐项 Go/No-Go/改判；通过后按 P1 未决清单第 5 条接线（BuildConfig 注入点 → AcceleratorLaneAssembly → 默认宽度）。


## 8. 投票结果（2026-11-12，裁决人：项目所有者）

**D1–D5 全部按建议通过**（裁决原话：「全部按照你的建议来即可」）：

| # | 决议 | 执行动作 |
| --- | --- | --- |
| D1 | **Go**——方案 A kernel 内置开启 | release 流水线显式传 `-Porchestella.uml.bundledFastProfile=true`（与 acceleratorLane 同构）；本地裸构建默认仍 false（无 payload 即 fail-fast 的既有豁免语义不变） |
| D2 | **Go（混合形态）**——kernel 内置 + rootfs 下载 | 内置 initramfs 通道立即可用（宽度 1 到 T3）；rootfs 下载为增强路径（方案 B 管线已闭环待产品调用方） |
| D3 | **Go（`21e1c3f9…`）**——可复现版入记账 pin | `FastProfile.PIN_MANIFEST` 现值即 21e1c3f9（与 manifest §3 一致），确认无需改码；真机验证版 572c3433 以注记保留 |
| D4 | **Go**——UML 面默认宽度 0→1 | 装配**槽级**语义切换：`AcceleratorLaneAssembly.umlFace` 槽实现按 BuildConfig 选 `createWithCurrentFastProfile`（fast 形态供宽 1/N，runner 形态保持 0）；既有测试零删改、纯新增 5 例。**如实边界**：`umlFace()` 当前无生产调用方——宽度 1 为潜在语义（wiring owner 构造面时生效），今天零行为变化、零意外 spawn；用户可路由入口在 fabric-route 轮 |
| D5 | **Go（§3a 公开 tag + Release 源码包）** | NOTICE.UML-DRAFT 正式化随资产入包执行；fork pin + defconfig-fast.fragment 交付清单照 R2 §2.4 十条 |

**决议效力**：本表即 ACR 通过记录；后续工单/报告/接线 commit 引用本节。执行进loc：接线实现见 `feat/uml-lane-default-on`（同日）。
