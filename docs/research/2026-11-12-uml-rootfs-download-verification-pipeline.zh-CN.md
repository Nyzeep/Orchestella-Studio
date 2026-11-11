# UML rootfs 下载版校验管线（方案 B L2L3）——官方 pin 锁定与 L3 五阶段 JVM 移植

- 日期：2026-11-12
- 车道：C（续轮·UML rootfs 下载管线深化）；前置：`2026-11-12-uml-productization-payload-research.zh-CN.md`（R2，三层管线设计）、P1 骨架（`UmlKernelAssets.java` 的 `RootfsDownload`）
- 代码：`app/src/main/java/io/github/nyzeep/orchestella/UmlKernelAssets.java`（`RootfsDownload` 内 L3 实现）；测试：`app/src/test/java/io/github/nyzeep/orchestella/UmlKernelAssetsRootfsSelfCheckTest.java`
- 边界：flag `orchestella.uml.rootfsDownload` 默认值不动（false）；下载 transport 仍未接线；产品默认行为零改动。

## 0. 结论

1. **官方 pin 一手锁定并双重验证**：Alpine v3.22 aarch64 minirootfs（`alpine-minirootfs-3.22.2-aarch64.tar.gz`）的官方 `.sha256` sidecar 与仓库 `PIN_MANIFEST` 既有 pin **完全一致**（P1 的 pin 通过官方复核）；官方字节数 **3,981,043**（HEAD Content-Length = 全量下载字节）补上了 P1 未决的 sizeBytes；GPG detached 签名（ncopa 密钥、指纹与 R2 记录一致）验证 **Good signature**。
2. **L3 五阶段中四个阶段落成纯 JVM 静态分析**（MANIFEST_FIELDS / ARTIFACT_DIGESTS / PACKAGE_DATABASE / TREE_DIGEST = done），对**真实官方 tarball**（520 条目）端到端全 PASS；**FIRST_BOOT_CLEANLINESS 保持 PLANNED**——它验收的是"真机首启后的落盘树"，tarball 静态读无法证明，绝不冒充 PASS。
3. **契约测试 26 例新增**：每个实现阶段的三态（通过/损坏/缺字段）、tar walker 防御矩阵、L1→L2→L3 分层 fail-closed 串联。fixture 全部是测试内最小 ustar writer 构造的小 tar，无任何真实 rootfs 二进制入库。

## 1. 官方 pin 锁定（C-1，2026-11-12 一手记录）

一手来源：`https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/`（Alpine release CDN 一手目录）。

| 项 | 值 | 验证方式 |
| --- | --- | --- |
| 确切文件名 | `alpine-minirootfs-3.22.2-aarch64.tar.gz` | 目录清单（见"标准 rootfs"澄清） |
| sizeBytes | **3,981,043**（约 3.98 MB） | HEAD `Content-Length` 与全量下载字节计数一致 |
| sha256 | `6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489` | 官方 `.sha256` sidecar 与全量下载实测 sha256 一致；与仓库 `PIN_MANIFEST` 既有 pin 一致 |
| sha512 | `670fc5a0e33cec999e25ef894cfa5426ab03050647c6b5f15a2bc40ff8a565d8b9477996550eb7199a45c309dc1f9e09677451ba38075b65b3feab46ab8f7985` | 官方 `.sha512` sidecar 与全量下载实测 sha512 一致 |
| GPG | detached `.asc` 直签 tarball；签名时间 2025-10-08 | 从 alpinelinux.org 官网 `keys/ncopa.asc` 导入 Natanael Copa 密钥，指纹 `0482 D840 22F5 2DF1 C4E7 CD43 293A CD09 07D9 495A` 与 R2 报告记录一致；`gpg --verify` → **Good signature**（导入密钥自验的常规 trust 警告为预期，指纹按 R2 记录带外比对） |

**"标准 rootfs tarball"澄清**：aarch64 release 目录里 rootfs 形态的 tarball 只有 `alpine-minirootfs-3.22.<n>-aarch64.tar.gz`（3.22.0–3.22.8 各 point release）、`alpine-netboot-*` 与 `alpine-rpi-*`；`alpine-standard` 是 x86 形态的 ISO，aarch64 没有对应 tarball。下载版以 **minirootfs** 作为官方 artifact 的选择与官方目录清点一致，不存在"更标准的 rootfs tarball"。

**Point release 升级纪律**：3.22.3–3.22.8 已在目录中，但各自是不同 artifact（未 pin）。升级 = 换文件名 + 按本节链条重新一手锁定（sidecar → 全量下载双摘要 → GPG）→ 同步更新 `PIN_MANIFEST` 的 pin 与 `FastProfile.ROOTFS_TAR_SIZE_BYTES`。绝不沿用旧 pin 换新文件。

**代码落点**：`FastProfile.ROOTFS_TAR_SIZE_BYTES = 3_981_043L`（新常量，javadoc 记录一手来源）；`FastProfile.artifacts()` 的 rootfs 条目与 `RootfsDownload.Entry.alpineMinirootfsAarch64()` 改用该常量——**P1 未决 #2（sizeBytes 未锁）就此关闭**，L1 字节门从"仅摘要"升级为精确字节门。P1 未决 #3（URL 未复核）见 §2。

## 2. URL 存活复核（C-2，2026-11-12，HEAD 请求）

| URL（`…/alpine/v3.22/releases/aarch64/`） | 状态 | Content-Length | Last-Modified | ETag | Accept-Ranges |
| --- | --- | --- | --- | --- | --- |
| `alpine-minirootfs-3.22.2-aarch64.tar.gz` | 200 OK | 3,981,043 | Wed, 08 Oct 2025 09:29:04 GMT | `"68e62ee0-3cbef3"` | bytes |
| `alpine-minirootfs-3.22.2-aarch64.tar.gz.sha256` | 200 OK | 106 | Wed, 08 Oct 2025 09:29:04 GMT | `"68e62ee0-6a"` | bytes |
| `alpine-minirootfs-3.22.2-aarch64.tar.gz.sha512` | 200 OK | 170 | Wed, 08 Oct 2025 09:29:04 GMT | `"68e62ee0-aa"` | bytes |
| `alpine-minirootfs-3.22.2-aarch64.tar.gz.asc` | 200 OK | 833 | Wed, 08 Oct 2025 11:18:23 GMT | `"68e6487f-341"` | bytes |

- **断点续传可行**：`Accept-Ranges: bytes` 成立；If-Range 可用强 ETag（上表）——与 R2 §3.2 的 RFC 7233 口径对上，transport 接线时按"Range + If-Range（强 ETag）→ 206/200 语义"实现。
- **镜像纪律不变**：只有网络类失败触发镜像轮换；校验类失败（size/pin/自检）fail-closed，不换镜像重试被污染的内容。
- 复核方式与限制：单窗口 HEAD 快照，无持续探测；上表数字只代表 2026-11-12 时点。

## 3. L3 五阶段 JVM 实现（C-3）

### 3.1 verify.sh → JVM 映射与状态

| verify.sh 检查 | `SelfCheckStage` | 状态 | 下载版口径 |
| --- | --- | --- | --- |
| ① manifest 结构与字段合法 | `MANIFEST_FIELDS` | **done** | 归档可读（tar 头校验和全过）+ 无路径逃逸条目（`..` 段/绝对名记为 unsafe）+ `etc/alpine-release` 内容 == `entry.version()` + `etc/hostname` 存在且非空 + `.PKGINFO` 存在时非空。官方 minirootfs tarball **不带** `.PKGINFO`（一手清点），缺位是官方形状不是缺陷 |
| ② 产物 sha256/size | `ARTIFACT_DIGESTS` | **done** | 对 L3 **实际拿到的字节**重算 sha256（`MessageDigest.isEqual` 常量时间比较）+ size pin 比对——堵住"L2 验过的字节 ≠ L3 看的字节"这条缝 |
| ⑤ 包数据库 | `PACKAGE_DATABASE` | **done** | `lib/apk/db/installed` **纯静态解析**（单字符键 + 空行分节，verify.sh `parse_alpine` 同款文法）：包数 > 0、每节 P/V 齐全、六个关键包在位（`alpine-baselayout`、`alpine-keys`、`apk-tools`、`busybox`、`ca-certificates-bundle`、`musl`）。apk 本体从不执行 |
| ④ 整树 digest | `TREE_DIGEST` | **done（收窄已声明）** | 顶层 17 个 FHS 目录（bin、dev、etc、home、lib、media、mnt、opt、proc、root、run、sbin、srv、sys、tmp、usr、var）必须每个都是**显式目录条目**（一手：官方 3.22.2 aarch64 的 17 个全部为显式 `'5'` 条目）。完整性而非相等性：多余顶层名不 FAIL。字节级整树 digest 需要展开树——留在 prebake 路线的 verify.sh，tar 流只能证结构；stage javadoc 明说这个收窄 |
| ③ 首启清洁 | `FIRST_BOOT_CLEANLINESS` | **PLANNED** | 需要真机首启数据（展开树 + 首启状态），返回 `PLANNED`，不冒充 PASS、也不自行 FAIL。`SelfCheckReport.plannedStages()` 让它保持可见；一旦设备窗供数完成移植，门自动收紧 |

### 3.2 API 形状（`UmlKernelAssets.RootfsDownload`）

- `selfCheck(Entry, byte[]) → SelfCheckReport`：内容缺陷永不抛异常——缺陷是阶段状态；gzip/tar 层的读取缺陷落成各内容阶段 `FAIL`（"archive unreadable: …"），`ARTIFACT_DIGESTS` 仍独立给出摘要判定（截断文件可以自洽哈希，L2 看不见，L3 看得见）。
- `requireSelfCheck(Entry, byte[]) → SelfCheckReport`：fail-closed 形态——任何 `FAIL` 抛 `RootfsRejectionException(REASON_SELF_CHECK_FAILED)`，失败阶段细节附在消息（reason code 保持稳定，不含传输原始报文）。
- `requireVerifiedAndSelfChecked(Entry, byte[], Long declaredSize)`：L1/L2（`requireVerified`：声明尺寸 + pin，常量时间）→ L3（`requireSelfCheck`）单调用串联，任意一层拒绝即整体拒绝。
- `StageState = PASS / FAIL / PLANNED`；`SelfCheckReport.failed() / plannedStages() / describe()`。
- `TarSnapshot`：一次 gzip→tar 头遍历的纯 JVM 快照——512 字节头、头校验和验证、GNU `L` 长名、PAX `x` 的 `path=` 覆盖、数据块 512 对齐、8 MiB 内容保留上限（超限只走结构不存内容）、路径逃逸条目记录为 unsafe 而非静默信任。不落盘、不执行任何归档内容。
- `RootfsRejectionException` 增加 `(reason, detail)` 构造：reason code 稳定（新增 `uml-rootfs-self-check-failed`），细节只进消息。

### 3.3 真实官方 tarball 端到端记录（本机，2026-11-12）

输入：§1 全量下载的官方 tarball（3,981,043 字节），走生产 `Entry.alpineMinirootfsAarch64()`：

```
L2 proof: sha256=6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489
          bytes=3981043 sizeVerified=true
MANIFEST_FIELDS     PASS :: version=3.22.2 hostname=localhost
                            .PKGINFO absent (the official tarball's shape)
ARTIFACT_DIGESTS    PASS :: sha256 ok, 3981043 bytes (size pinned)
PACKAGE_DATABASE    PASS :: 16 packages parsed; all 6 required packages present
TREE_DIGEST         PASS :: 17/17 required top-level directories present
                            (520 entries walked)
FIRST_BOOT_CLEANLINESS PLANNED
```

交叉验证：520 条目 = tar 清单实测（87 文件 + 98 目录 + 335 符号链接）；16 包 = `lib/apk/db/installed` 的 `P:` 行计数；17 目录 = §3.1 的 FHS 集。实现不是只在自造 fixture 上成立。

## 4. 契约测试（C-4）

新文件 `app/src/test/java/io/github/nyzeep/orchestella/UmlKernelAssetsRootfsSelfCheckTest.java`（26 例）。fixture 是测试内最小 ustar writer（512 字节头 + 校验和 + gzip）在内存构造的小 tar，形状镜像官方一手清点（`./` 前缀、17 个显式目录条目、`etc/alpine-release`=3.22.2、`etc/hostname`=localhost、apk installed-db 文法、无 `.PKGINFO`、含符号链接与硬链接条目）；**无任何真实 rootfs 二进制**。

| 组 | 覆盖 |
| --- | --- |
| 通过态 | 官方形状全实现阶段 PASS；报告阶段顺序 == `selfCheckPlan()`；`plannedStages()` 恰为 `[FIRST_BOOT_CLEANLINESS]` |
| `MANIFEST_FIELDS` 三态 | 版本不符（`alpine-release` 说 3.22.3）/ 缺 `alpine-release` / 空白 hostname / `..` 逃逸条目 / 绝对路径条目 / `.PKGINFO` 缺位 = 官方形状（detail 声明） |
| `ARTIFACT_DIGESTS` 三态 | 篡改字节 → sha256 mismatch / pin 尺寸不符 → size mismatch / 未 pin 尺寸 → digest-only PASS（detail "size unpinned"） |
| `PACKAGE_DATABASE` 三态 | 坏 stanza（缺 V）/ 缺关键包（musl）/ 缺 db 文件 / 全空 db → zero packages |
| `TREE_DIGEST` 三态 | 缺 `proc/` / 多余顶层名不误伤（完整性非相等性）/ 同名**文件**冒充目录 → FAIL（只认显式目录条目） |
| walker 防御 | GNU `L` 长名 + PAX `path=` 覆盖正确落位；mid-block 截断 → 全内容阶段 FAIL（ARTIFACT_DIGESTS 仍 PASS——L2 盲区）；坏头校验和 fail-closed |
| 串联 | `requireVerifiedAndSelfChecked` 分层 fail-closed：L1 尺寸先拒（`REASON_SIZE_MISMATCH`）→ L2 pin 次拒（`REASON_CHECKSUM_MISMATCH`）→ L3 捕获 L2 盲区（自洽 pin 但缺 `proc/` → `REASON_SELF_CHECK_FAILED`）；`requireSelfCheck` 抛出携带失败阶段细节 |
| 诚实性 | `FIRST_BOOT_CLEANLINESS` 恒为 `PLANNED`、detail 指名"需要真机首启数据"，永不 PASS |

既有 `UmlKernelAssetsFastProfileTest` 两处更新：rootfs sizeBytes 断言从"诚实的 null"改为官方锁定的 3,981,043（javadoc 引 §1）；生产 Entry 的 size 断言同步。其余 pin/文法/四态 seam 断言未动。

## 5. 三层管线与 Alpine 官方校验链的对应

| 层 | Orchestella（方案 B） | Alpine 官方校验链 | 本轮状态 |
| --- | --- | --- | --- |
| L1 传输完整性 | `ResilientDownloadEngine`（`.part` 原子落盘、Range/If-Range、Content-Length 比对）+ `Entry.sizeBytes` 字节门 | CDN 的 `Accept-Ranges`/强 ETag（§2 表） | 校验半 done（字节门现已是精确值）；transport 接线未做 |
| L2 身份 | 固定 HTTPS 源 + sha256 pin + `MessageDigest.isEqual`（常量时间）+ 镜像轮换仅限网络类失败 | tarball 自带 `.sha256`/`.sha512`/`.asc`（ncopa 直签 tarball，指纹带外比对） | done。设备不跑 GPG：构建/发布期人工验一次 `.asc`（§1 已示范），把验证过的 pin 固化进 app，运行期只验 pin——R2 §3.4 的取舍落地 |
| L3 内容自检 | 五阶段静态自检（§3），`selfCheckPlan()` 固定次序 | （Alpine 无此层；口径来自本仓库 `research/uml-rootfs-prebake/verify.sh`） | 四阶段 done + 首启 PLANNED；`requireVerifiedAndSelfChecked` 把三层钉成一个 fail-closed 调用 |

## 6. 未决问题

1. **`FIRST_BOOT_CLEANLINESS` = PLANNED**：等设备窗提供真机首启数据后，按 verify.sh 的首启状态检查移植（init/ready 可执行、无包管理器调用、无网络命令、无 provisioning 标记、machine-id/resolv.conf 空、无 ssh host key、apk 缓存/锁/日志干净）。注意验收对象是"**首启后的落盘树**"——官方 minirootfs 本身不带这些状态（一手清点：无 machine-id/resolv.conf/ssh host key 条目），静态读 tarball 无法也不应假装通过。
2. **transport 接线**：`ResilientDownloadEngine` ↔ `requireVerifiedAndSelfChecked` 的对接仍未做；flag `orchestella.uml.rootfsDownload` 默认 false，本轮零默认行为变化、零产品路径触碰。
3. **point release 升级**：换 pin 前必须按 §1 链条重新一手锁定并同步 sizeBytes（§1 升级纪律）；当前 3.22.2 pin 与官方一致，无升级压力。
4. **内存形态**：L3 目前以 `byte[]` 为输入（4 MB 级合理）；若未来把同一管线接向更大的预烘焙 tar（106 MB 级），需要补 `File` 流式重载避免整块驻内存。
