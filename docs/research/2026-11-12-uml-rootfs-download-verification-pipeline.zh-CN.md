# UML rootfs 下载版校验管线（方案 B L2L3）——官方 pin 锁定与 L3 五阶段 JVM 移植

- 日期：2026-11-12
- 车道：C（续轮·UML rootfs 下载管线深化）；前置：`2026-11-12-uml-productization-payload-research.zh-CN.md`（R2，三层管线设计）、P1 骨架（`UmlKernelAssets.java` 的 `RootfsDownload`）。**Lane D 补记（同日）**：transport 接线落地（`RootfsTransport.downloadAndVerify`），见 §5 L1 行与 §6 #2。**Lane E 增量（同日）**：`FIRST_BOOT_CLEANLINESS` 静态口径移植 + 形态参数化，见 §3.1/§3.2/§3.4/§3.5/§4/§6.1。
- 代码：`app/src/main/java/io/github/nyzeep/orchestella/UmlKernelAssets.java`（`RootfsDownload` 内 L3 实现）；测试：`app/src/test/java/io/github/nyzeep/orchestella/UmlKernelAssetsRootfsSelfCheckTest.java`
- 边界：flag `orchestella.uml.rootfsDownload` 默认值不动（false）；下载 transport 仍未接线；产品默认行为零改动。

## 0. 结论

1. **官方 pin 一手锁定并双重验证**：Alpine v3.22 aarch64 minirootfs（`alpine-minirootfs-3.22.2-aarch64.tar.gz`）的官方 `.sha256` sidecar 与仓库 `PIN_MANIFEST` 既有 pin **完全一致**（P1 的 pin 通过官方复核）；官方字节数 **3,981,043**（HEAD Content-Length = 全量下载字节）补上了 P1 未决的 sizeBytes；GPG detached 签名（ncopa 密钥、指纹与 R2 记录一致）验证 **Good signature**。
2. **L3 五阶段全部落成纯 JVM 静态分析**（MANIFEST_FIELDS / ARTIFACT_DIGESTS / PACKAGE_DATABASE / TREE_DIGEST = done；**FIRST_BOOT_CLEANLINESS = done，静态口径**——Lane E 按 verify.sh 首启段移植，并按 entry 形态 `EntryForm` 参数化，§3.4），对**真实官方 tarball**（520 条目）端到端全 PASS。**两层口径划清**：静态口径验收"tarball 自己的内容"，动态口径（真机首启后的落盘树）tarball 读既证明不了也不冒充（§6.1）——静态 PASS 从不声称是动态 PASS。
3. **契约测试 45 例**（Lane C 26 例 + Lane E 增量：首启六类检查 × 两形态的三态、形态分叉锁、超上限结构判定、`EntryForm` 默认值）：每个实现阶段的三态（通过/损坏/缺字段）、tar walker 防御矩阵、L1→L2→L3 分层 fail-closed 串联。fixture 全部是测试内最小 ustar writer 构造的小 tar，无任何真实 rootfs 二进制入库。

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
| ③ 首启清洁 | `FIRST_BOOT_CLEANLINESS` | **done（静态口径，Lane E）** | verify.sh 首启段的六类检查按 `EntryForm` 参数化（§3.4 语义表）：minirootfs 形态查残留/身份/烘焙态/队列各族并**跳过脚本族与 hostname、apk db lock 两个 prebake 专属项**（跳过在 PASS detail 中可见，不静默、不 fail）；prebake 形态六类全查。**动态口径未决**：真机首启后的落盘树不是静态读能证明的对象（§6.1 两层口径），本阶段静态 PASS ≠ 动态 PASS |

### 3.2 API 形状（`UmlKernelAssets.RootfsDownload`）

- `selfCheck(Entry, byte[]) → SelfCheckReport`：内容缺陷永不抛异常——缺陷是阶段状态；gzip/tar 层的读取缺陷落成各内容阶段 `FAIL`（"archive unreadable: …"），`ARTIFACT_DIGESTS` 仍独立给出摘要判定（截断文件可以自洽哈希，L2 看不见，L3 看得见）。
- `requireSelfCheck(Entry, byte[]) → SelfCheckReport`：fail-closed 形态——任何 `FAIL` 抛 `RootfsRejectionException(REASON_SELF_CHECK_FAILED)`，失败阶段细节附在消息（reason code 保持稳定，不含传输原始报文）。
- `requireVerifiedAndSelfChecked(Entry, byte[], Long declaredSize)`：L1/L2（`requireVerified`：声明尺寸 + pin，常量时间）→ L3（`requireSelfCheck`）单调用串联，任意一层拒绝即整体拒绝。
- `StageState = PASS / FAIL / PLANNED`；`SelfCheckReport.failed() / plannedStages() / describe()`。
- `TarSnapshot`：一次 gzip→tar 头遍历的纯 JVM 快照——512 字节头、头校验和验证、GNU `L` 长名、PAX `x` 的 `path=` 覆盖、数据块 512 对齐、8 MiB 内容保留上限（超限只走结构不存内容）、路径逃逸条目记录为 unsafe 而非静默信任。不落盘、不执行任何归档内容。
- `RootfsRejectionException` 增加 `(reason, detail)` 构造：reason code 稳定（新增 `uml-rootfs-self-check-failed`），细节只进消息。
- **Lane E 增量**——`EntryForm = MINIROOTFS / PREBAKE`：entry 的形态轴，`FIRST_BOOT_CLEANLINESS` 按它参数化；`Entry` 增加 `form` 分量（七参历史构造默认 `MINIROOTFS`，生产 `alpineMinirootfsAarch64()` 显式 `MINIROOTFS`）。`TarSnapshot` 相应增加三组只读保留：`entryNames`（全部条目的规范名——`-e` 探针集，任何条目类型都算在位）、`regularFileNames`（常规文件名，含超上限只走结构的条目）、`executableFileNames`（带任意执行位的常规文件名——`-x` 探针集，解析 tar 头 mode 字段）；目录条目的尾斜杠规范化（真实 tarball 的目录条目带 `/` 尾缀，`-e` 探针必须按无尾斜杠拼写命中）。8 MiB 内容上限不变：超限条目按结构存在性判，词扫描只对已缓冲内容做。

### 3.3 真实官方 tarball 端到端记录（本机，2026-11-12；Lane E 刷新）

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
FIRST_BOOT_CLEANLINESS PASS :: form=minirootfs: boot-script checks skipped (the
                            official minirootfs ships no first-boot scripts);
                            no product first-boot residue; per-instance identity
                            files (machine-id, resolv.conf) empty or absent; no
                            baked ssh host keys; certificate bundle present; apk
                            queue/cache/log clean (the official tarball's own
                            0-byte db lock is not a defect)
```

`requireSelfCheck`（fail-closed 链）在同一字节上通过；`plannedStages()` 为空。同一份字节改按 **PREBAKE 形态**声明则 `FIRST_BOOT_CLEANLINESS` 如实 FAIL——缺两个首启脚本、hostname 仍是出厂值 `localhost`、官方自带的 0 字节 `lib/apk/db/lock` 在位——形态参数化是活的，不是摆设。

一手补充清点（Lane E，2026-11-12，同一 tarball）：`etc/ssl/certs/ca-certificates.crt` 在位（219,404 字节）；`var/cache/apk/`、`var/log/` 为空目录；无任何 `*.apk` 常规文件；**`lib/apk/db/lock` 为 0 字节常规文件（mode 0600）——verify.sh 的"apk db lock 缺位"要求是 prebake 树的口径，对官方下载件不成立**（§3.5）。

交叉验证：520 条目 = tar 清单实测（87 文件 + 98 目录 + 335 符号链接）；16 包 = `lib/apk/db/installed` 的 `P:` 行计数；17 目录 = §3.1 的 FHS 集。实现不是只在自造 fixture 上成立。

### 3.4 首启清洁：六类检查 × 两形态语义表（Lane E 移植口径）

| # | 检查类（verify.sh 首启段） | prebake 形态 | minirootfs 形态（官方下载 artifact） |
| --- | --- | --- | --- |
| 1 | 首启脚本在位且可执行（`init-zero-install` / `ready.sh`，`-x` 语义：任意执行位） | 必须在位且带执行位，否则 FAIL | **跳过**（官方 minirootfs 无这两个条目，一手核实；跳过写入 PASS detail，可见不静默） |
| 2 | 脚本体无包管理器调用词（去注释体 `grep -v '^\s*#'` + 词边界正则照抄：`apk`/`apk.static`/`apt`/`apt-get`/`dpkg`/`pacman`/`yum`/`dnf`/`zypper`） | 检查（8 MiB 上限内读内容；超限条目按结构存在性判并在 detail 声明） | **跳过**（跟随 #1，无脚本可扫） |
| 3 | 脚本体无网络客户端/配置命令词（`wget`/`curl`/`nc`/`ncat`/`ssh`/`scp`/`ping`/`dhclient`/`udhcpc`/`ifconfig`/`route`/`netstat`/`ss`/`ip`；其右边界 `[[:space:]]\|$` 比包管理器那条窄，照抄） | 检查 | **跳过**（跟随 #1） |
| 4 | 无产品首启残留（`opt/orchestella/.firstboot-done`、`var/lib/orchestella/provision/state`；`-e` 语义：任何条目类型都算在位） | 检查 | 检查 |
| 5 | 每实例身份文件为空（`etc/machine-id`、`etc/resolv.conf`；`-s` 语义：缺位或 0 字节通过，非空 FAIL） | 检查 | 检查（官方 tarball 无这两个条目 → 通过） |
| 6 | 无烘焙进镜像的宿主/实例状态 + 包管理器队列干净 | 全项：`etc/hostname` = `orchestella`（prebake 烘焙值）、无 `etc/ssh/ssh_host_*`、CA bundle（`etc/ssl/certs/ca-certificates.crt`）在位非空、apk 队列/缓存/锁/日志干净 | **hostname 值检查跳过**（官方出厂值 `localhost`；下载口径只查存在性，MANIFEST_FIELDS 已管）；**apk db lock 检查跳过**（§3.3 一手：官方自带 0 字节 lock）；其余同 prebake（ssh host key、CA bundle、apk 缓存/`*.apk`/日志照查） |

### 3.5 与 verify.sh 的口径差异点（均为有意为之，各有依据）

1. **形态参数化本身**：verify.sh 首启段整体面向 prebake 展开树；下载管线只有 tar 流与 entry 形态字段。移植按"prebake 烘焙属性（只查 PREBAKE）"与"任何可交付树都必须满足的卫生属性（两形态都查）"切分——§3.4 的跳过项全部属于前者。
2. **apk db lock**：verify.sh 要求 `lib/apk/db/lock` 缺位；一手核实官方 minirootfs tarball 自带 0 字节该文件（2026-11-12，v3.22.2 aarch64，mode 0600）。minirootfs 形态跳过该条且在 PASS detail 中声明；prebake 形态维持缺位要求。绝不静默放宽——放宽会被单测（`firstBootCleanlinessApkDbLockIsAPrebakeOnlyDemand`）锁死在"仅 minirootfs 跳过"上。
3. **distro 分支键**：verify.sh 的 alpine/debian 分支按构建 manifest 的 `distro.name` 走；tar 流没有 manifest，移植版按树自带的包数据库证据键（`lib/apk/db/installed` 在位 → alpine 分支；`var/lib/dpkg` 在位 → debian 分支）。debian 分支（lists/`.deb` 缓存/dpkg updates/四把锁）一并移植，当前管线没有 debian entry，属"就位等接线"。
4. **`-x` 判定**：shell `-x` 对任意执行位成立；移植版解析 tar 头 mode 字段（offset 100），`(mode & 0111) != 0` 等价。
5. **词扫描输入**：verify.sh 对展开文件逐行 grep；移植版对 8 MiB 上限内已缓冲内容按同一正则（`MULTILINE` 使 `^`/`$` 落到 grep 的逐行锚点）扫描，超限条目按结构存在性判——差异只存在于"单个脚本 > 8 MiB"的病态输入，且 detail 明示。
6. **动态口径不在内**：verify.sh 对展开树做的一切仍是 prebake 路线的权威；下载管线的静态 PASS 不覆盖真机首启后的落盘树（§6.1）。

## 4. 契约测试（C-4；Lane E 增量同文件）

新文件 `app/src/test/java/io/github/nyzeep/orchestella/UmlKernelAssetsRootfsSelfCheckTest.java`（Lane C 26 例；Lane E 增至 **45 例**）。fixture 是测试内最小 ustar writer（512 字节头 + 校验和 + gzip，Lane E 增加可选 mode 字段以构造带执行位的脚本条目）在内存构造的小 tar，形状镜像官方一手清点（`./` 前缀、17 个显式目录条目、`etc/alpine-release`=3.22.2、`etc/hostname`=localhost、apk installed-db 文法、无 `.PKGINFO`、含符号链接与硬链接条目；Lane E 补上官方在位的非空 `etc/ssl/certs/ca-certificates.crt`）；**无任何真实 rootfs 二进制**。

| 组 | 覆盖 |
| --- | --- |
| 通过态 | 官方形状**五阶段**全 PASS；报告阶段顺序 == `selfCheckPlan()`；`plannedStages()` 为空（五阶段全部静态落地） |
| `MANIFEST_FIELDS` 三态 | 版本不符（`alpine-release` 说 3.22.3）/ 缺 `alpine-release` / 空白 hostname / `..` 逃逸条目 / 绝对路径条目 / `.PKGINFO` 缺位 = 官方形状（detail 声明） |
| `ARTIFACT_DIGESTS` 三态 | 篡改字节 → sha256 mismatch / pin 尺寸不符 → size mismatch / 未 pin 尺寸 → digest-only PASS（detail "size unpinned"） |
| `PACKAGE_DATABASE` 三态 | 坏 stanza（缺 V）/ 缺关键包（musl）/ 缺 db 文件 / 全空 db → zero packages |
| `TREE_DIGEST` 三态 | 缺 `proc/` / 多余顶层名不误伤（完整性非相等性）/ 同名**文件**冒充目录 → FAIL（只认显式目录条目） |
| `FIRST_BOOT_CLEANLINESS` 三态 × 两形态（Lane E，20 例） | minirootfs 形态官方形状全 PASS（**无 `init-zero-install` → 跳过不 fail，跳过可见**——真实官方 artifact 预期锁死）；prebake 形态全 PASS；脚本缺失 / 无执行位（0644）/ 包管理器词（`apk add`）/ 网络词（`wget`）→ FAIL；注释行剥离 + 词边界语义照抄锁死（`apktool` 不误伤、`busybox-nc-hero` 不误伤、注释里的 `apk`/`wget` 不计）；marker / provision state / 非空 machine-id / 非空 resolv.conf / 烘焙 ssh host key / CA bundle 缺失或空 / apk 队列残留（缓存 + `*.apk` + 日志）→ FAIL；**空 machine-id 通过（`-s` 边界）**；hostname 与 apk db lock 的**形态分叉**（同一字节树 PREBAKE FAIL / MINIROOTFS PASS，lock 差异带一手证据断言）；debian 分支按 dpkg 证据触发；超 8 MiB 条目按结构存在性判（词扫描跳过有 detail 声明；超上限 machine-id 依 `-s` 语义如实 FAIL） |
| `EntryForm`（Lane E） | 七参历史构造默认 `MINIROOTFS`；生产 `alpineMinirootfsAarch64()` 显式 `MINIROOTFS`；显式 `PREBAKE` 保真 |
| walker 防御 | GNU `L` 长名 + PAX `path=` 覆盖正确落位；mid-block 截断 → 全内容阶段 FAIL（ARTIFACT_DIGESTS 仍 PASS——L2 盲区；Lane E：`FIRST_BOOT_CLEANLINESS` 同样 "archive unreadable" FAIL）；坏头校验和 fail-closed |
| 串联 | `requireVerifiedAndSelfChecked` 分层 fail-closed：L1 尺寸先拒（`REASON_SIZE_MISMATCH`）→ L2 pin 次拒（`REASON_CHECKSUM_MISMATCH`）→ L3 捕获 L2 盲区（自洽 pin 但缺 `proc/` → `REASON_SELF_CHECK_FAILED`）；`requireSelfCheck` 抛出携带失败阶段细节 |

既有 `UmlKernelAssetsFastProfileTest` 两处更新：rootfs sizeBytes 断言从"诚实的 null"改为官方锁定的 3,981,043（javadoc 引 §1）；生产 Entry 的 size 断言同步。其余 pin/文法/四态 seam 断言未动。Lane E 对它的唯一影响是 `Entry` 新增 `form` 分量——七参历史构造默认 `MINIROOTFS`，该测试文件零改动。

## 5. 三层管线与 Alpine 官方校验链的对应

| 层 | Orchestella（方案 B） | Alpine 官方校验链 | 本轮状态 |
| --- | --- | --- | --- |
| L1 传输完整性 | `ResilientDownloadEngine`（`.part` 原子落盘、Range/If-Range、Content-Length 比对）+ `Entry.sizeBytes` 字节门 | CDN 的 `Accept-Ranges`/强 ETag（§2 表） | **done（Lane D）**：`RootfsTransport.downloadAndVerify` 闭环（下载 → `.part` → 收货字节 → L1/L2/L3 → 原子发布）。引擎实测只发 `Range` 不发 `If-Range`（§6 #2 发现），续传防污染由 L2 pin 收尾 |
| L2 身份 | 固定 HTTPS 源 + sha256 pin + `MessageDigest.isEqual`（常量时间）+ 镜像轮换仅限网络类失败 | tarball 自带 `.sha256`/`.sha512`/`.asc`（ncopa 直签 tarball，指纹带外比对） | done。设备不跑 GPG：构建/发布期人工验一次 `.asc`（§1 已示范），把验证过的 pin 固化进 app，运行期只验 pin——R2 §3.4 的取舍落地 |
| L3 内容自检 | 五阶段静态自检（§3），`selfCheckPlan()` 固定次序 | （Alpine 无此层；口径来自本仓库 `research/uml-rootfs-prebake/verify.sh`） | 五阶段 done（首启为静态口径、形态参数化，§3.4/§3.5）；`requireVerifiedAndSelfChecked` 把三层钉成一个 fail-closed 调用 |

## 6. 未决问题

1. **`FIRST_BOOT_CLEANLINESS` 的动态口径未决（两层口径，不冒充）**：静态口径已 done（§3.4/§3.5——tarball 自己的内容能证明的部分，按形态参数化）。但该阶段名下的完整语义是"**真机首启后的落盘树保持清洁**"——marker 不在首启后出现、machine-id 首启后再生成、无包管理器真正运行、resolv.conf 由网络栈改写——这些是首启后的属性，tarball 静态读既证明不了也不应假装；静态 PASS ≠ 动态 PASS，两层的证据来源、验收时机、责任车道都不同。动态验证仍走 verify.sh（prebake 路线权威）+ 设备窗（与 §4.5/§4.6 的 adb-shell 通道联动），未排期。
2. **transport 接线（Lane D，2026-11-12 done）**：`RootfsTransport.downloadAndVerify(transport, entry, destDir)`（app 侧新类，`runtime:network` 只读）把 `ResilientDownloadEngine` ↔ `requireVerifiedAndSelfChecked` 接成闭环——引擎把 `entry.source` 拉到 `destDir/<fileName>.part`（引擎内部瞬态 `<fileName>.part.part`，原子协议），收货字节 → L1/L2/L3 fail-closed 串联 → 通过才原子 rename 为正式名并返回收据（size/sha/阶段报告）；任何层失败清扫全部 staged 名，不留半成品（跨调用续传有意让位于 fail-closed；单调用内的重试续传保留并契约测试锁定）。flag 门在入口内部：读 `BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED`（gradle 属性 `orchestella.uml.rootfsDownload`，**默认 false 不变**），off 返回 disabled 收据且零传输零落盘；单测锁死默认 build 的 gate 为 off。**引擎行为发现（只读未修）**：引擎发送 `Range: bytes=N-` 但从不发送 `If-Range`、不消费 ETag（R2 §3.2 规划的 If-Range 契约引擎未实现）——换源字节续传混流由 L2 pin 收尾（sha 失配 → 引擎自清 .part + destination → fail-closed），契约测试锁定（`RootfsDownloadResumeContractTest`）；若 ACR 要求真 If-Range 语义属引擎改动。剩余：**真实网络（官方 CDN）下的端到端待真机窗**；产品调用方接线待 ACR 落地（当前零产品路径调用）。
3. **point release 升级**：换 pin 前必须按 §1 链条重新一手锁定并同步 sizeBytes（§1 升级纪律）；当前 3.22.2 pin 与官方一致，无升级压力。
4. **内存形态**：L3 目前以 `byte[]` 为输入（4 MB 级合理）；若未来把同一管线接向更大的预烘焙 tar（106 MB 级），需要补 `File` 流式重载避免整块驻内存。Lane E 的三组名字保留（`entryNames`/`regularFileNames`/`executableFileNames`）随条目数线性增长、以归档自身字节量为上界——同一未来重载需要一并流式化。
5. **prebake 形态的下载管线接线未做**：`EntryForm.PREBAKE` 与首启六类检查已就位，但目前没有任何 prebake entry/源被 pin；等 prebake 路线产出可下载 artifact 时按 §1 链条锁定并显式声明 `PREBAKE` 形态。
6. **If-Range 引擎语义评估（2026-11-13 发布门加固轮 Lane C 追加）——本节为评估，未实现**：对 §6 #2 记录的"引擎发 `Range` 不发 `If-Range`、不消费 ETag"做改动面盘点，裁决权留主会话/用户（属引擎改动，ACR 需单列决议）。
   - **引擎改动面**（`ResilientDownloadEngine`，`runtime:network` 模块——注意这是发行版下载与 UML rootfs 下载**共享**的引擎，`SetupWizardActivity` 也在消费）：
     1. `Range` 发送点唯一（`openConnectionWithRedirects` 的 rangeOffset 分支）——`If-Range` 需在同处分支追加，且仅当存在已存 validator 时；
     2. ETag 消费点现状为零：引擎只读 `Content-Range` 与 `Content-Length`。需新增 (a) 首段响应的强 validator 捕获（仅强 ETag：带引号且非 `W/` 前缀——官方 CDN 形状即如此，§2 表）；(b) validator 持久化——引擎当前除 `.part` 字节外无任何跨连接状态，需 `.part` 旁 sidecar（如 `<dest>.part.etag`）并定义其写/读/清时点；
     3. 200/206 分叉：带 `If-Range` 的 Range 请求在 representation 变化时服务器回 200 全量——引擎既有 200 分支（TRUNCATE_EXISTING + `digest.reset()` 全量重下）天然落 RFC 语义，改动量小；416 分支与 sidecar 清理需同步；
     4. **镜像轮换交互（最大语义风险）**：validator 实际是 per-mirror 的（不同镜像对同一 artifact 可发不同 ETag）；现引擎跨镜像续传（mirror B 接 mirror A 的 offset）。两条候选路：镜像切换时清 sidecar + `.part`（退化为全量重下，安全但浪费）或仅同镜像续传时带 `If-Range`——两者都保安全（L2 pin 兜底不变），只差效率；
     5. 状态清扫面：fail-closed 路径（`ChecksumMismatchException`）现 purge `.part` + destination，sidecar 必须一并 purge，否则残留脏 validator 污染下次续传。
   - **与现有 tripwire 测试的同步点**：
     1. `RootfsDownloadResumeContractTest.engineResumesByRangeAndNeverSendsIfRange`——测试名与断言（`assertFalse(containsKey("if-range"))`）都要反转改写；它是"引擎发现"的锁定器，实现时必须同步改写；
     2. `resumingAcrossAChangedArtifactFailsClosedThroughThePin`——有 sidecar 的续传在 representation 变化时改走 200 全量 → 结果仍是 pin 拒（`ChecksumMismatchException`，fail-closed 不变），但到达路径与连接细节变化；该测试手写的 `.part` 无 sidecar → 行为同今天，断言需按此分叉重写；
     3. `aRangelessServerFallsBackToAFreshFullDownload` 与 416 分支——sidecar 写/清时点要进契约断言；
     4. 引擎属 `runtime:network` 模块级改动：`ResilientDownloadEngineContractTest`（模块自测）与 `RootfsTransportContractTest`（app 侧 seam 契约）两族都要过；
     5. 口径同步三处 javadoc/文档：`RootfsTransport.java` 类注释（"sends `Range` but never `If-Range`"）、本文档 §5 L1 行、§6 #2 的"引擎行为发现"——随实现一并改写，禁止实现后留三套口径。
   - **ACR 补充裁决建议**：(a) 现状安全——If-Range 缺位的完整性风险已由 L2 pin fail-closed 闭环（换源混流必被 sha 拒且引擎自清），If-Range 是**效率/健壮性增强而非安全修复**，不实现不构成发布阻塞；(b) 若实现，建议引擎参数化（如 validator 策略开关）而非全局行为切换——共享引擎的全局切换会改变非 UML 车道的续传行为，需全消费者回归；(c) 建议单列新决议（D6）承载，本节改动面盘点可直接作为决议素材；(d) 时机与 Wave 2 真机窗解耦——首次真实 CDN 端到端按现状跑（不阻塞 #2 的闭环），若实测镜像轮换浪费显著再排期。
