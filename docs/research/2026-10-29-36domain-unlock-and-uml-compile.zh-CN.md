# 会话 22 · 36 域解封验证与 UML 编译决定性数字

> 日期 2026-10-29｜基线 `main = 991e912`｜设备 arm64 / Android 17 / HyperOS（4K 宿主，序列号不入库）
> 性质：研究+验证轮，未写产品代码、未改 `CONTEXT.md`/ADR。所有判定只认设备实跑。

---

## 1. 结论摘要

| # | 命题 | 判定 | 一句话依据 |
|---|---|---|---|
| 一 | targetSdk 36 域下 QEMU 车道能否整体解封 | **成立（条件式）** | `libgvproxy.so` 与 `libdroidlayer_qemu.so` 均以 `nativeLibraryDir` 绝对路径 exec 成功，域为 `untrusted_app_34`，执行类 AVC = 0；guest 完成 systemd 启动并给出 root shell |
| 二 | 2000 函数真实编译过 UML 的倍数 | **2.04×（劣于 1.5× 判据）** | 同二进制同输入同设备：UML 中位 940 ms、native 中位 460 ms；对象逐字节相同 |

条件式解封的前提（必须进发布形态）：**主包 `useLegacyPackaging=true`（不带 payload 目录），pVM payload 留在独立扩展包**。二者在同一 APK 内互斥。

---

## 2. 方法与口径

- 设备：Redmi K90 Pro Max / Android 17（SDK 37）/ HyperOS，宿主页 4K，8 核。
- UML：fork v0.3.0 `linux-bionic`（kernel 7.2.0-rc4），guest 页 **16K**（启动日志 `PAGE=16384` 实测），UP 单核，2 GB。
- 计时：guest 内 `/proc/uptime`（10 ms 分辨率，与会话 21 同法）；native 侧同法。
- 编译命令两侧完全一致：`clang -c -O1 <fixture>.c`。
- 夹具：`build_fixture(2000, "c")`，90,045 B，2000 个 `probe_fn_*`，sha256 `4e5dafcc…e1cf2fc`（与 DV-PVM 系列同一形态）。

---

## 3. W-A · 治理收尾（已完成）

- PR #55 合并（merge commit `991e912`）：`docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` + `research/uml-runner/`（20 文件，2013 行）。
- 二进制不入库：`libuml_kernel.so`(9.6 MB)/`libuml_stub.so`(2 KB) 由 `.gitignore` 排除，README 1.1 记 sha256 与 release 来源。
- 敏感门：`--self-test` 与 `--range main..HEAD` 均 `SENSITIVE_DATA_OK`。首轮命中**设备序列号 2 处 + 宿主绝对路径 4 处**，改为匿名化表述与环境变量后重跑通过。
- 分支收敛：`research/uml-feasibility-20261028` 远端+本地已删；仅剩 `main`；工作区净。

---

## 4. W-B · 命题一：36 域解封

### 4.1 前提修正（开工 Prompt 的假设不成立）

Prompt 假设「gvproxy 需重命名/封装为 `libgvproxy.so` 进 jniLibs」。实测该文件**早已在** `app/src/main/jniLibs/arm64-v8a/`（12,714,144 B），`NativeExecutableStore.ensure()` 也早已优先返回 `nativeLibraryDir/<so>`。

36 域历史失败的真实变量是**打包模式**：

```
app/build.gradle.kts:365   useLegacyPackaging = fastlanePayloadLibDir == null
```

| 构建形态 | `extractNativeLibs` | `nativeLibraryDir` | 36 域 exec |
|---|---|---|---|
| 带 `fastlane.payloadLibDir`（payload 构建） | false | 空 | 只能从 filesDir exec → **拒绝** |
| 普通 QA（无 payload 属性） | true | 实体文件 + x 位 | **允许** |

即：**不是「gvproxy 没进 jniLibs」，而是「payload 构建把 jniLibs 留在了 APK 里」**。这与会话 18 的两包拆分裁决同源——payload 必须外置，主包才能用 legacy packaging。

### 4.2 构建与安装

`-Porchestella.buildChannel=qa -Porchestella.buildPurpose=dvpvm -Porchestella.qaTargetSdk=36`（不带 payload 属性）→ APK 106.7 MB，`targetSdkVersion=36`，lib 以 `Defl:N` 存储；安装后 `nativeLibraryDir` 四个 `.so` 均 `-rwxr-xr-x`、标签 `u:object_r:apk_data_file:s0`。

### 4.3 运行期证据

进程（PPid = 应用主进程）：

```
6661  libgvproxy.so            u:r:untrusted_app_34:s0
6687  libdroidlayer_qemu.so    u:r:untrusted_app_34:s0
```

argv[0] 均为 `/data/app/~~…/lib/arm64/<so>`；QEMU 参数完整：`-machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp 4 -m 6371M`。

- gvproxy 健康：`OrchestellaGvproxy: gateway-ready api=200 forwards=0 probes=1`；`guest-dns server=192.168.1.1`。
- 执行类 AVC（execute/execmem/execmod）**计数 0**；唯一噪声是 `RenderThread → proc_perfmgr { search }`（GPU 属性探测，与执行链无关）。
- guest：EXT4 格式化挂载 → 解包 790.9 MB rootfs → systemd 启动 → `Arch Linux ARM 7.2.3 (ttyAMA0)` → `root@orchestella:~ #`。
- guest 内实跑：`echo MARK36_OK` 回显、`ip addr` 显示 `eth0 192.168.127.15/24`（与 `NetworkGatewayDaemon.GUEST_IP` 常量一致）。

### 4.4 残留（必须带进下一轮）

1. **首次装配的包安装失败**：`ORCHESTELLA_GUEST_PROVISION status=failed stage=package-install reason=offline … exit_code=1`。
2. `ping -c1 192.168.127.254`（gvproxy hostIP）**100% 丢包**。gvproxy 一般不透传 ICMP，故此结果**不足以判定 TCP 不通**；但结合 (1)，「guest 出网」在本轮未被打通。
3. 因此「guest 内编译成功」**未验证**：Arch 基础环境未装完，guest 内无编译器。
4. distcc 交火式 `COMPLETED` 证据未取得：需 payload 扩展包，而 HyperOS 拒绝新装该包（会话 18 起的老问题）。

判定不变：**平台侧的 exec/W^X 阻塞已解除**，剩余问题是网络与装配，不是 SELinux。

---

## 5. W-C · 命题二：UML 真实编译倍数

### 5.1 工具链路径（离线，宿主侧解包）

- Alpine v3.24 `main/aarch64`，`apk add clang` → `clang22 22.1.3-r2`；依赖闭包 17 包、下载 111,812,698 B（`llvm22-libs` 64.3 MB 为大头）。
- 抓取方式：`resolve_apk.py` 解析 APKINDEX 求闭包 → `fetch_and_pack.py` 直取 `.apk` 并重打包为 `clang-rootfs.tar.gz`（112,230,510 B）。
- **不走 guest 内 `apk add`**：hostfs 下解包 300 MB 是 syscall 密集型操作，会叠加会话 21 记录的 syscall 8× 放大；改为宿主解包 + 一次 tar 流推送。
- 同一份 tar 解到两处：guest `files/rootfs/`（hostfs 根）与 native `/data/local/tmp/tc`（`ld-musl-aarch64.so.1 --library-path` 加载）。两侧 `clang --version` 均为 `Alpine clang version 22.1.3 / aarch64-alpine-linux-musl`。

### 5.2 四列对比（每文件，2000 函数）

| 车道 | ms/文件 | 工具链 | 来源 |
|---|---|---|---|
| **native**（裸机、同二进制） | **460**（15 样本中位） | Alpine clang 22.1.3 | 本轮实测 |
| **UML guest**（16K、UP、2 GB） | **940**（15 样本中位） | 同上，逐字节同一份 | 本轮实测 |
| proot | 340 | 历史 | 会话 21 引用（7 文件批次 ÷7） |
| pVM | 2,809 | payload clang 22 | 会话 21 引用，**未复测** |
| QEMU | 32,750 | Arch clang | 会话 21 引用，**未复测** |

**UML / native = 2.04×**；三轮各自 2.02× / 2.22× / 2.04×。

等价性：两侧 `out.o` 均 179,912 B、sha256 `b43f22e0…4e8b064` —— **逐字节相同**，比的是同一份工作产物，不是两份实现。

稳定性：UML 15 样本 910–1110 ms（spread 21%），native 450–490 ms（spread 8.7%）；温度 39→57 °C（`thermal_zone27` 49.8/51.7/51.7 → 56.7/52.1/52.5），未触降频。

### 5.3 归因：为什么 compute 1.08× 而编译 2.04×

同一轮内交替 `tiny.c`（1 函数）与 `fixture.c`（2000 函数）：

| 负载 | native | UML | 比值 |
|---|---|---|---|
| tiny（进程启动 + 168 MB LLVM 库映射 + 前端初始化） | 40 ms | 150 ms | **3.75×** |
| fixture（2000 函数） | 460 ms | 920 ms | **2.00×** |
| 边际（fixture − tiny） | 420 ms | 770 ms | **1.83×** |

三点解读：

1. **固定开销被放大 3.75×**，与 syscall 8× 同向，但只占单 TU 总时长 9%（native）/ 16%（UML），不是主因。
2. **边际 1.83× 才是主因**。会话 21 的纯 compute（sha256 32 MB 流式）只有 1.08×，差别在访存形态：sha256 是顺序流式、页错误极少；编译是大量小对象分配 + mmap/munmap + 频繁页错误，每个 guest 页错误都要落到宿主。
3. **结论：「compute 1.08×」不可外推到编译**。任何「UML 接近原生算力」的表述在编译负载上不成立；会话 21 报告 §7.1 的 compute 行必须加此限定。

### 5.4 判定

判据：≤1.5× → ACR GO；1.5–3× → 长驻/Docker 归属、编译仍归 pVM/proot；>3× → 负面入档。

**本轮 2.04×，落 1.5–3× 区间**：UML 不进编译车道；其价值区间回到「长驻 Linux 用户态 / Docker / 非 syscall 密集型负载」，与 ACR 草案里「替代 QEMU 的编译角色」的设想相反——**该设想应撤回**。

---

## 6. 环境新知（可复用的硬事实）

1. **Alpine 4K 对齐二进制可在 16K guest 正常加载执行**：musl 1.2.6、busybox 1.37、clang 22.1.3 + 168 MB LLVM 库全部工作。此前把「16K 页对齐」列为最大未知，实测不成立（UML 的 ELF 装载走宿主 mmap，段对齐按 4K 仍满足宿主页粒度下的映射条件）。
2. **Alpine 3.24 的 `.apk` 是 apk-tools v3 单层 tar**（非 v2 的 `data.tar.gz` 嵌套）。直接按包内路径流式重打包即可安装，无需 `apk` 二进制。
3. **`adb shell input text` 会吞掉连字符 `-`**（`uname -r` → `uname r`，`ping -c1` → `ping c1`）。需要连字符时用 `input keyevent 69` 单独发送。
4. **HyperOS 对新装应用要屏上确认**（锁屏时必失败，`INSTALL_FAILED_USER_RESTRICTED`）；已存在包的更新不受限。研究轮不要轻易卸载已装包。
5. `settings put global adb_install_need_confirm 0` 对 HyperOS 无效（仍被拦），真正的闸门是「USB 安装」授权 + 亮屏确认。

---

## 7. 未验证项（四态：未验证）

| 项 | 状态 | 原因 |
|---|---|---|
| guest 内编译成功（36 域 QEMU） | 未验证 | 装配 `stage=package-install` 失败，guest 无编译器 |
| guest 出网（TCP/DNS 经 gvproxy） | 未验证 | ICMP 100% 丢包不足以判定；装配报 offline |
| distcc 交火 `COMPLETED` @36 | 未验证 | 需 payload 扩展包；HyperOS 拒绝新装 |
| pVM 同机复测 | 未验证 | payload 构建与 36 域 exec 互斥（同一 APK 内），扩展包装不上 |
| UML 长驻稳定性（30 min+） | 未验证 | 时间盒 |
| passt/umnet 网络通道 | 未验证 | 工具链走离线注入，未进关键路径 |
| 16K guest 下的链接（`-c` 之外） | 未验证 | 本轮只做 `-c`，未调 ld/lld |

---

## 8. 对 ACR 的影响（建议，不实施）

1. **撤回**「UML 替代 QEMU 承担编译」的定位草案：2.04× 且内存密集负载更差。
2. **新增**「targetSdk 36 主包 + payload 扩展包」的发布形态候选：平台阻塞已实证解除，剩余是装配/网络与扩展包装包。
3. 徽章/文案：36 域解封后，「AVF · 待授权 / QEMU · 可用」的 facts 驱动逻辑不变，但 QEMU 车道的**权限前提**可去掉 targetSdk 28 的说明（改为打包模式前提）。

---

## 9. 收口待办

1. 设备残留：QA@36 包（含正在运行的 QEMU 实例 `0d0cac1d`）、`/data/local/tmp/{clang-rootfs.tar.gz,tc/,fixture.c,tiny.c,oe36.apk}`、umlrunner rootfs 已扩到含 clang（约 +300 MB）。
2. 下一轮入口：guest 出网（gvproxy TCP 面）→ 装配 `package-install` → 36 域 guest 内编译；扩展包装包策略；UML 长驻。
3. 报告与证据：`evidence/uml-20261028/round22/`（`wc-raw-data.md`、`wb-raw-data.md`、`term-36domain*.png` 6 张）。
