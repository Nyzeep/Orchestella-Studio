# P06 Fast Lane 存储与导出/导入 — 实现报告（零设备）

状态：零设备实现与 JVM 测试完成；真机（DV-01/DV-02）与真实 SAF provider 未验证。
分支：`fastlane/agent-b-dataplane`。日期：2026-10-21。

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
2. **布局**：`StorageLayout` 负责目录/marker/路径；marker 内容 `version=1`，重复初始化幂等。
3. **路径**：拒绝绝对路径、空组件、`.`/`..`、尾斜杠、NUL；逐组件 `NOFOLLOW_LINKS` 拒绝 symlink。
4. **tar**：自有 `TarWriter`/`TarReader`，ustar + PAX（长路径/UTF-8）；只接受普通文件/目录。
5. **导出方向**：guest tar → 宿主解析并**重新写 canonical tar** → sink；过滤 deny 名单；每文件 SHA256 + 归档 SHA256 同步计算。
6. **导入方向**：宿主 tar（可选 gzip）→ 校验并重新写 canonical tar → `GuestImportSink`（P02 frames）；只允许 `home/`/`workspace/`。
7. **manifest**：确定性 JSON，按 path 排序，`entries[{path,size,sha256,mtime}] + totalBytes`；在归档完成后才写。
8. **提交**：`ExportSink.openArchive/openManifest/commit/abort`；失败/取消必须 `abort`，不得出现可见半包。
9. **不覆盖**：`FileExportSink` 在目标已存在且未显式 overwrite 时 fail-closed；SAF 适配需同等语义。
10. **取消**：`CancellationToken` 在每 entry 与每个 64 KiB 块检查；取消 → `CANCELLED` + abort。
11. **上限**：单 entry 16 GiB、总 32 GiB、100k entries、tar 元数据 1 MiB；超限 typed。
12. **失败分类**：`PATH_REJECTED/MANIFEST_INVALID/LIMIT_EXCEEDED/UNSUPPORTED_FORMAT/LAYOUT_INVALID/IO_FAILED/CANCELLED`。
13. **guest 一致性**：P02 `import_tar` 校验 size/SHA256 后 staging swap；宿主 abort 时 guest 丢弃 staging。
14. **隐私**：manifest 只含路径/大小/hash/时间；不写文件内容；host 不持久化 encrypted storage 明文。
15. **测试接缝**：`ExportSink`/`GuestImportSink` 内存实现、`FileExportSink` 临时目录、`TemporaryFolder`。

## 3. 改动

新增 `runtime/fastlane-storage/README.md` 与：

- `StorageFailure`、`StorageException`、`StorageLayout`、`StoragePaths`；
- `ExportManifest`（含小型确定性 JSON 读写）；
- `TarEntry`、`TarReader`、`TarWriter`；
- `ExportPolicy`、`ExportOptions`、`ExportSink`、`GuestImportSink`、`CancellationToken`；
- `ExportResult`、`ImportResult`；
- `FileExportSink`、`FastLaneExporter`、`FastLaneImporter`；
- 测试：`StorageLayoutTest`、`ExportManifestTest`、`TarRoundTripTest`、`FastLaneStorageTest`。

未修改 `settings.gradle.kts`、`runtime/engine-fastlane`、其他 agent 模块。

## 4. 行为与格式

### 布局

`home/`、`workspace/`、`tmp/`、`cache/`、`exports/`；`.fastlane-layout` 内容精确为 `version=1\n`。根或任一子目录/marker 为 symlink → `PATH_REJECTED`；未知版本 → `LAYOUT_INVALID`。

### manifest

```json
{"version":1,"entries":[{"path":"home/a.txt","size":5,"sha256":"…","mtime":1700000000}],"totalBytes":5}
```

- 条目按 path 排序；重复 path 拒绝；
- SHA256 固定 64 位小写 hex；mtime 为 epoch 秒；
- 嵌入的路径按 JSON 规则转义（引号、反斜杠、换行、控制字符）。

### 导出

- 输入是 guest 的 tar 字节；`TarReader` 校验 checksum/路径/类型；
- `ExportPolicy.standard()` 拒绝（跳过）`id_rsa*`、`id_ed25519*`、`token`、`.fastlane-token`、`.netrc`、`.git-credentials`、`*.sock/*.pid/*.pem/*.key/*.p12/*.jks/*.keystore/*.token`；
- 输出是 canonical tar；`gzip=true` 时输出 `.tar.gz`；
- 归档 SHA256/大小由 `CountingOutputStream` + `DigestOutputStream` 计算（gzip 时对压缩字节）；
- manifest 写完才 `commit()`。

### 导入

- 输入 tar 可以是 gzip；`TarReader` 拒绝绝对/穿越/symlink/hardlink/device/fifo/截断/坏 checksum；
- 顶层只允许 `home`/`workspace`；
- 输出 canonical tar 到 `GuestImportSink.begin()/write()/end(total,sha256)`；`begin` 不传总大小，guest 以 `IMPORT_END` 为准（P02 支持 `total=0`）；
- 任何失败调用 `abort()`，不产生半包。

## 5. 测试命令与结果

```sh
javac --release 17 -Xlint:all -Werror -d <out> -cp junit-4.13.2.jar \
  runtime/engine-fastlane/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/*.java \
  runtime/fastlane-storage/src/main/java/io/github/nyzeep/orchestella/runtime/fastlane/storage/*.java \
  runtime/fastlane-storage/src/test/java/io/github/nyzeep/orchestella/runtime/fastlane/storage/*.java
java -cp <out>:junit-4.13.2.jar:hamcrest-core-1.3.jar org.junit.runner.JUnitCore \
  ...storage.StorageLayoutTest ...storage.ExportManifestTest \
  ...storage.TarRoundTripTest ...storage.FastLaneStorageTest
```

结果：`OK (26 tests)`：

- `StorageLayoutTest` 6：幂等初始化、目录/marker、未知版本、symlink marker/子目录、穿越/绝对、symlink 路径；
- `ExportManifestTest` 4：JSON round-trip/转义、重复 path、坏 JSON/总大小不符、非法 entry；
- `TarRoundTripTest` 5：8 MiB/空文件/深目录/长路径/UTF-8/特殊字符、GNU tar 解包互操作、穿越/绝对/symlink/hardlink/fifo、截断/坏 checksum、writer 拒绝不安全路径；
- `FastLaneStorageTest` 11：导出 tar+manifest、跳过凭据、gzip、取消、sink 失败、文件 sink 不覆盖、导入+manifest、gzip 导入、穿越/绝对/symlink 拒绝、取消、8 MiB 大文件。

GNU tar 互操作测试在 `/bin/tar` 不存在时用 `Assume` 跳过；本环境实际执行通过。

## 6. 未验证 / not-tested

- 真实 SAF provider 的 `commit()` 原子性（`DocumentsContract.renameDocument` 可能不支持或非原子）；`:app` 适配器必须 fail-closed，当前只定义了接口；
- 设备上 P02 guest staging/swap/rollback（C 侧有宿主测试，未接真实 vsock）；
- 宿主-客车的 `EXPORT_DATA/IMPORT_*` 帧解包（属于 P01/P04 mux，不在本模块）；
- 真实磁盘满/权限失败（测试用抛异常的 sink 模拟 IO 失败，未用真实 ENOSPC）；
- symlink 策略对真实 home 的影响（当前硬拒绝）。

## 7. 研究来源（一手）

- `references/aosp-virtualization` @ `175a51b` `guest/encryptedstore/README.md`：encrypted storage 为 ext4 + dm-crypt(HCTR2)，宿主不可解密但可删除；`src/main.rs` 挂载 context `u:object_r:encryptedstore_file:s0`。
- 本仓 SAF 实现：`app/src/main/java/io/github/nyzeep/orchestella/AndroidSafExportAdapter.java`、`AndroidSafImportAdapter.java`：在打开/截断 SAF 流之前先做可导出校验；`DocumentStreamOpener`/`DocumentStreamReader` 是既有 seam。
- tar ustar/PAX 格式：POSIX ustar 头（512 字节、octal size/mtime/checksum、prefix/name）与 PAX `x` 扩展的 `path`/`size`/`mtime` 记录；GNU tar 互操作测试验证。
- `references/termux-packages` @ `f1b5f46`（仅参考备份布局/工具链，不复制实现）。
- P02 `fastlane-payload/docs/protocol.md`：`EXPORT_*`/`IMPORT_*` 字节契约；本轮将 `IMPORT_BEGIN.total=0` 的未知大小语义写入协议文档。

## 8. 跨 agent 契约

- **P02**：`FastLaneExporter` 消费 guest `EXPORT_DATA` 解包后的 tar 流；`FastLaneImporter` 产生 canonical tar 供 `IMPORT_DATA` 发送。`IMPORT_BEGIN.total=0` 与 P02 `handle_import` 已对齐（END 的 size 为准）。
- **P01/P04**：本模块不实现 control mux；宿主需把 `EXPORT_DATA` 拼成 `InputStream`、把 `GuestImportSink` 适配成 `IMPORT_*` 帧。
- **SAF（`:app`）**：实现 `ExportSink`（archive/manifest 两个流 + commit/abort）与 `ImportSource` 式输入；必须在用户确认后才覆盖已有文件。

## 9. 风险与下一步

1. SAF 的原子提交能力因 provider 而异；若无法 rename，必须写临时文档并在 commit 时创建正式文档，失败时删除临时文档，不能静默覆盖。
2. 导入是整棵 `home/workspace` 替换；UI 必须二次确认“未包含的文件会消失”。
3. 导出对 symlink 硬拒绝可能误伤真实 home；DV-02 若发现常见工具创建 symlink，需要产品决策改为“跳过并记录”。
4. gzip 输出的大小/hash 是压缩后的归档；manifest 的 per-file hash 是解压后的内容哈希，两者用途不同，UI 不要混用。
5. 大文件测试为 8 MiB；真实 GB 级导入的吞吐/内存需 DV-02。
