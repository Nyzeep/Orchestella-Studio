# P06 Fast Lane 存储与导出/导入 — 实现报告（零设备）

状态：零设备实现与强化完成；真机（DV-01/DV-02）与真实 SAF provider 未验证。
分支：`feature/fastlane-dataplane`。日期：2026-10-21（第二轮加固）。

## 1. 目标与范围

把 `/mnt/encryptedstore` 变成可用的持久 home/workspace，并交付宿主侧流式导出/导入：

- 目录布局 `home/workspace/tmp/cache/exports` + 版本 marker；
- guest tar（P02）→ `EXPORT_DATA` → 宿主 `FastLaneExporter` → SAF；
- 导入反向：SAF → `FastLaneImporter` → `IMPORT_*` → guest staging；
- manifest（路径/大小/SHA256/时间）；
- 路径穿越/绝对路径/symlink 拒绝；中断/磁盘满/权限失败不提交半包；
- 不导出 token/密钥/临时 socket；host 不落 encrypted storage 明文。

不包含：真实 SAF provider、设备上的 guest staging、P02 帧的宿主 mux 实现（P01/P04 集成）。

## 2. 设计 seam（先于实现）

1. **边界**：本模块纯 JVM；不引用 Android `DocumentFile`/`Uri`，SAF 由 `:app` 适配 `ExportSink`/`ImportSource`。
2. **布局**：`StorageLayout` 负责目录/marker/路径；marker 内容 `version=1`，重复初始化幂等，marker 超过 64 字节拒绝。
3. **路径**：拒绝绝对路径、空组件、`.`/`..`、尾斜杠、NUL；逐组件 `NOFOLLOW_LINKS` 拒绝 symlink。
4. **tar**：自有 `TarWriter`/`TarReader`，ustar + PAX（长路径/UTF-8）；只接受普通文件/目录；重复文件路径和 file/dir 同路径拒绝。
5. **导出方向**：guest tar → 宿主解析并**重新写 canonical tar** → sink；过滤 deny 名单；每文件 SHA256 + 归档 SHA256 同步计算。
6. **导入方向**：宿主 tar（可选 gzip）→ 校验并重新写 canonical tar → `GuestImportSink`（P02 frames）；只允许 `home/`/`workspace/`。
7. **manifest**：确定性 JSON，按 path 排序，`entries[{path,size,sha256,mtime}] + totalBytes`；在归档完成后才写；累加溢出拒绝。
8. **提交**：`ExportSink.openArchive/openManifest/commit/abort`；失败/取消必须 `abort`，不得出现可见半包。
9. **不覆盖**：`FileExportSink` 在目标已存在且未显式 overwrite 时 fail-closed；overwrite 时先备份旧文件，提交失败则恢复。
10. **取消**：`CancellationToken` 在每 entry 与每个 64 KiB 块检查；取消 → `CANCELLED` + abort。
11. **上限**：单 entry 8 GiB−1（ustar 11 个八进制位）、总 16 GiB、100k entries、tar 元数据 1 MiB；超限 typed，不静默截断。
12. **失败分类**：`PATH_REJECTED/MANIFEST_INVALID/LIMIT_EXCEEDED/UNSUPPORTED_FORMAT/LAYOUT_INVALID/IO_FAILED/CANCELLED`。
13. **guest 一致性**：P02 `import_tar` 校验 size/SHA256 后 staging swap；宿主 abort 时 guest 丢弃 staging。
14. **隐私**：manifest 只含路径/大小/hash/时间；不写文件内容；host 不持久化 encrypted storage 明文。
15. **测试接缝**：`ExportSink`/`GuestImportSink` 内存实现、`FileExportSink` 临时目录、包内 `Mover` 注入提交失败。

## 3. 第二轮加固（2026-10-21）

- **八进制字段溢出**：`TarWriter.putOctal` 原来按位循环只写低 11 位，>8 GiB−1 的 size 会被静默截断成错误值。现检查 `value > 8^(width−1)−1` 并抛 `LIMIT_EXCEEDED`；`TarReader.MAX_ENTRY_BYTES` 同步降到 8 GiB−1，总量降到 16 GiB（与 P02 guest 上限一致）。
- **PAX 解析**：长度累加改为 `long` 并检查溢出，`offset + length` 用减法式边界判断；避免恶意 record 让解析器回绕后接受错误长度。
- **读取零进度**：`TarReader.readFully` 对返回 0 的流抛 `IO_FAILED`，不再无限循环。
- **重复路径**：`TarReader` 记录已见文件/目录；重复文件、file/dir 同路径直接 `PATH_REJECTED`，与 P02 guest 的 `seen_files` 语义一致。
- **导入提交后 close 失败**：`FastLaneImporter` 原来用 try-with-resources；`guest.end()` 成功后 source/gzip `close()` 抛异常会进入 catch 并 `guest.abort()`，把已提交的导入又标成失败。现改为显式 `ended` 标志：成功 `end` 后忽略 close 失败且不 abort；非 gzip source 的关闭责任交还调用方。
- **提交回滚**：`FileExportSink.commit()` 在 overwrite 时先备份 `archiveFinal`/`manifestFinal`，再发布新文件；第二次 move 失败时删除新文件、恢复旧文件，并清空 `published*` 标志，避免 exporter 随后的 `abort()` 把恢复后的旧文件再删一次。
- **manifest 溢出**：`ExportManifest` 维护增量 `totalBytes`，累加前检查 `Long.MAX_VALUE` 溢出并抛 `LIMIT_EXCEEDED`；避免恶意 manifest 的总量检查被回绕绕过。
- **布局 marker**：超过 64 字节的 marker 抛 `LAYOUT_INVALID`，不再无界读入内存。
- **命名冲突**：`ExportOptions` 拒绝 archive 与 manifest 同名，避免两个发布 move 指向同一路径。
- **Mover seam**：`FileExportSink` 新增包内 `Mover` 构造器，测试可确定性地在第二次发布 move 注入失败，验证恢复路径；生产默认仍是 `Files.move(ATOMIC_MOVE)`。

## 4. 行为与格式

### 布局

`home/`、`workspace/`、`tmp/`、`cache/`、`exports/`；`.fastlane-layout` 内容精确为 `version=1\n`，长度 ≤64。根或任一子目录/marker 为 symlink → `PATH_REJECTED`；未知版本或超大 marker → `LAYOUT_INVALID`。

### manifest

```json
{"version":1,"entries":[{"path":"home/a.txt","size":5,"sha256":"…","mtime":1700000000}],"totalBytes":5}
```

- 条目按 path 排序；重复 path 拒绝；`totalBytes` 累加溢出拒绝；
- SHA256 固定 64 位小写 hex；mtime 为 epoch 秒；
- 嵌入的路径按 JSON 规则转义（引号、反斜杠、换行、控制字符）。

### 导出

- 输入是 guest 的 tar 字节；`TarReader` 校验 checksum/路径/类型/重复；
- `ExportPolicy.standard()` 拒绝（跳过）`id_rsa*`、`id_ed25519*`、`token`、`.fastlane-token`、`.netrc`、`.git-credentials`、`*.sock/*.pid/*.pem/*.key/*.p12/*.jks/*.keystore/*.token`（大小写不敏感）；
- 输出是 canonical tar；`gzip=true` 时输出 `.tar.gz`；
- 归档 SHA256/大小由 `CountingOutputStream` + `DigestOutputStream` 计算（gzip 时对压缩字节）；
- manifest 写完才 `commit()`；overwrite 提交失败会恢复旧 archive/manifest。

### 导入

- 输入 tar 可以是 gzip；`TarReader` 拒绝绝对/穿越/symlink/hardlink/device/fifo/截断/坏 checksum/重复路径；单 entry >8 GiB−1、总量 >16 GiB、>100k entry 拒绝；
- 顶层只允许 `home`/`workspace`；P02 端只替换 tar 中出现的顶层根，未出现的根保持原样；
- 输出 canonical tar 到 `GuestImportSink.begin()/write()/end(total,sha256)`；`begin` 不传总大小，guest 以 `IMPORT_END` 为准（P02 支持 `total=0`）；
- 任何失败在 `end` 之前调用 `abort()`，不产生半包；`end` 成功后不再 abort。

## 5. 测试命令与结果

```sh
javac --release 17 -Xlint:all -Werror -d /tmp/flj-storage -cp /tmp/junit-4.13.2.jar \
  runtime/engine-fastlane/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/*.java \
  runtime/fastlane-storage/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/storage/*.java \
  runtime/fastlane-storage/src/test/java/io/github/nyzeep/orchestella/runtime/fastlane/storage/*.java
java -cp /tmp/flj-storage:/tmp/junit-4.13.2.jar:/tmp/hamcrest-core-1.3.jar org.junit.runner.JUnitCore \
  ...storage.StorageLayoutTest ...storage.ExportManifestTest \
  ...storage.TarRoundTripTest ...storage.FastLaneStorageTest
```

结果：`OK (35 tests)`（上一轮 26）：

- `StorageLayoutTest` 7：幂等初始化、目录/marker、未知版本、超大 marker、symlink marker/子目录、穿越/绝对、symlink 路径；
- `ExportManifestTest` 5：JSON round-trip/转义、重复 path、总量溢出、坏 JSON/总大小不符、非法 entry；
- `TarRoundTripTest` 9：8 MiB/空文件/深目录/长路径/UTF-8/特殊字符、GNU tar 解包互操作、穿越/绝对/symlink/hardlink/fifo、截断/坏 checksum、writer 拒绝不安全路径、writer 拒绝 8 GiB size、PAX size 超限、重复路径、零进度流；
- `FastLaneStorageTest` 14：导出 tar+manifest、跳过凭据、gzip、取消、sink 失败、文件 sink 不覆盖、overwrite 成功、overwrite 提交失败恢复旧文件、导入+manifest、gzip 导入、导入成功后 close 失败不 abort、穿越/绝对/symlink 拒绝、取消、8 MiB 大文件。

GNU tar 互操作测试在 `/bin/tar` 不存在时用 `Assume` 跳过；本环境实际执行通过。

## 6. 未验证 / not-tested

- 真实 SAF provider 的 `commit()` 原子性（`DocumentsContract.renameDocument` 可能不支持或非原子）；`:app` 适配器必须 fail-closed，并实现与 `FileExportSink` 同等的备份/恢复语义；
- 设备上 P02 guest staging/swap/rollback（C 侧有宿主测试，未接真实 vsock）；
- 宿主-客车的 `EXPORT_DATA/IMPORT_*` 帧解包（属于 P01/P04 mux，不在本模块）；
- 真实磁盘满/权限失败（测试用抛异常的 sink 和注入的 move 失败模拟；未在 ext4/SAF 上制造 ENOSPC）；
- 真实 GB 级导入的吞吐/内存；8 MiB 是自动化测试上限；
- symlink 策略对真实 home 的影响（当前硬拒绝）；重复路径硬拒绝对“增量 tar”流的影响。

## 7. 研究来源（一手）

- `references/aosp-virtualization` @ `175a51b` `guest/encryptedstore/README.md`：encrypted storage 为 ext4 + dm-crypt(HCTR2)，宿主不可解密但可删除；`src/main.rs` 挂载 context `u:object_r:encryptedstore_file:s0`。
- 本仓 SAF 实现：`app/src/main/java/io/github/nyzeep/orchestella/AndroidSafExportAdapter.java`、`AndroidSafImportAdapter.java`：在打开/截断 SAF 流之前先做可导出校验；`DocumentStreamOpener`/`DocumentStreamReader` 是既有 seam。
- tar ustar/PAX 格式：POSIX ustar 头（512 字节、octal size/mtime/checksum、prefix/name）与 PAX `x` 扩展的 `path`/`size`/`mtime` 记录；GNU tar 互操作测试验证。ustar size 字段为 11 个八进制位 + NUL，因此单文件上限是 8 GiB−1；更大的文件需要 GNU base-256，未采用。
- `references/termux-packages` @ `f1b5f46`（仅参考备份布局/工具链，不复制实现）。
- P02 `fastlane-payload/docs/protocol.md`：`EXPORT_*`/`IMPORT_*` 字节契约；`IMPORT_BEGIN.total=0` 的未知大小语义、单 entry/总量上限。

## 8. 跨 agent 契约

- **P02**：`FastLaneExporter` 消费 guest `EXPORT_DATA` 解包后的 tar 流；`FastLaneImporter` 产生 canonical tar 供 `IMPORT_DATA` 发送。`IMPORT_BEGIN.total=0` 与 P02 `handle_import` 已对齐（END 的 size 为准）；单 entry 8 GiB−1、总量 16 GiB、重复路径拒绝已与 C 侧对齐。
- **P01/P04**：本模块不实现 control mux；宿主需把 `EXPORT_DATA` 拼成 `InputStream`、把 `GuestImportSink` 适配成 `IMPORT_*` 帧。
- **SAF（`:app`）**：实现 `ExportSink`（archive/manifest 两个流 + commit/abort）与 `ImportSource` 式输入；必须在用户确认后才覆盖已有文件，并保证提交失败时旧文件可恢复。

## 9. 风险与下一步

1. SAF 的原子提交能力因 provider 而异；若无法 rename，必须写临时文档并在 commit 时创建正式文档，失败时删除临时文档，不能静默覆盖。`FileExportSink` 的备份/恢复实现不能假设 SAF 支持，需要适配器自行实现。若恢复本身失败，`commit()` 抛出单独的 “previous export could not be restored” IOException，并把原始 move 失败作为 suppressed；旧文件保留在 `.fastlane-backup-*.part`，需要 UI/运维提供恢复入口。
2. 导入只替换 tar 中出现的顶层根；UI 若承诺“整树替换”，必须在选择文件时确认 `home`/`workspace` 都存在，否则未包含的根会保留。
3. 导出对 symlink 硬拒绝可能误伤真实 home；DV-02 若发现常见工具创建 symlink，需要产品决策改为“跳过并记录”。
4. gzip 输出的大小/hash 是压缩后的归档；manifest 的 per-file hash 是解压后的内容哈希，两者用途不同，UI 不要混用。
5. 8 GiB−1 单文件上限和 16 GiB 总量是显式产品限制；需要在 UI/导出向导中提前提示，而不是到 commit 才失败。
6. 重复路径硬拒绝会让“同一路径多次出现的增量 tar”失败；这是安全优先的 fail-closed 选择，若未来支持增量导入需要单独设计。
