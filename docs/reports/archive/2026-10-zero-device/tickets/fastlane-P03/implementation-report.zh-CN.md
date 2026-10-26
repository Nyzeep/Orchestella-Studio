# P03 Sealed Toolbox 实现报告（零设备）

日期：2026-10-21 ｜ 分支：`feature/fastlane-toolbox` ｜ 状态：zero-device 可构建/可检查；设备执行 `not-tested`

## 1. 目标与范围

把 Fast Lane 只读工具集放到 APK 根 `bin/`，利用 AOSP zipfuse 对 `bin/`
的默认执行位；提供可复现的 manifest、SHA256、许可证/来源、16 KB ELF 检查、
体积预算与签名前注入路径。不运行 Android，不声称 DV-02 已通过。

## 2. 选型：候选 B（静态 BusyBox），不是 Termux bootstrap

证据（2026-10-21 下载核对）：

- Termux bootstrap `bootstrap-2026.10.18-r1+apt.android-7` 的
  `bootstrap-aarch64.zip` 为 32 MiB（压缩），`SYMLINKS.txt` 中存在
  `/data/data/com.termux/files/usr/bin/...` 绝对目标；`bin/bash` 内含
  `/data/data/com.termux/files/usr/...` 路径。放到 `/mnt/apk/bin` 需要
  `termux-exec`/prefix 重写，零设备无法证明可用。
- Alpine 3.20 `busybox-static` 1.36.1-r31（aarch64）为静态 musl、
  1,115,944 字节，PT_LOAD `p_align=0x10000`（64 KiB），无 `$PREFIX`、无
  动态库闭包、无 zip symlink 需求。
- 组装后 v1 工具集 2,234,863 字节 / 50 个条目；生成的运行时 manifest
  10,639 字节，`bin/` 合计 2,245,502 字节（≈2.14 MiB），远低于 80 MiB
  起步预算。

因此 v1 选择 B；Termux/clang 作为 `pendingProfiles.distcc-clang`，状态
`not-tested`，不随 v1 分发。理由与证据写入 `fastlane-toolbox/README.md`。

## 3. 产物布局与 P02 契约

```
/mnt/apk/bin/
  manifest.json   # 运行时 manifest（生成）
  busybox         # pinned static binary
  sh              # busybox 的字节副本，避免 zip 符号链接
  awk, cat, ...   # 48 个 applet wrapper
```

- 运行时 manifest：`/mnt/apk/bin/manifest.json`。
- `install.guestPath=/mnt/apk/bin`，`install.path=["/mnt/apk/bin"]`。
- `tools[]` 含 name/version/sha256/packageSha256/license/source/target/deps；
  BusyBox 另含 `correspondingSource`（upstream + Alpine 打包树）。
- `sourceDateEpoch=1792540800`（`2026-10-21T00:00:00Z`）与 `sourceDate` 写入
  运行时 manifest；源 manifest 也固定该值，环境变量可覆盖用于测试。
- `entries[]` 含 path/mode/size/sha256；P02 只应读取该文件，不应假设
  toolbox 可写或存在 `/bin` 之外的路径。
- wrapper shebang 与 exec 均固定 `/mnt/apk/bin/busybox`；不存 symlink。

## 4. 构建/门禁

工具：`tools/fastlane-build/fastlane_build.py`（Python 3 标准库）。

- `build`：校验源 manifest（`install`/`target`/artifact 类型/deps/许可证字段/
  `pendingProfiles` 必须为 not-tested 或 blocked）；按 SHA256 下载并校验
  Alpine 包；提取 `bin/busybox.static` 并校验二进制 SHA256；生成 wrapper 与
  运行时 manifest；所有条目 0755 且固定 mtime；记录 `sourceDateEpoch`；
  任何 ELF 非 AArch64、非静态或 PT_LOAD < 16 KB 立即失败。
- `verify-toolbox`：manifest schema/epoch/tools 字段；条目路径、0755 模式、
  存在、非 symlink、大小/SHA 一致；无未列文件、无 `bin/` 之外的顶层文件；
  总大小预算（含 manifest）；ELF AArch64/static/16 KB；输出 canonical
  `treeDigest`。
- `inject-apk`：仅用于测试/兜底；拒绝已签名 APK、重复条目、symlink 条目、
  路径穿越；为 `bin/**` 写入 0755 外部属性和固定 ZIP 时间戳。生产路径由
  Gradle 资源源集在签名前打包，不使用该注入器。
- `verify-apk`：APK 内 `bin/manifest.json` 与外部 manifest 完全一致；
  拒绝重复/穿越/symlink/目录条目；每个条目 SHA/大小/执行位（0 表示由
  zipfuse 赋执行位）；ELF AArch64/static/16 KB；体积含 manifest；可选
  `zipalign -c -P 16 -v 4`。
- `reproduce.py`：同一输入构建两次，比较 canonical tree digest；不一致时
  列出首个差异文件并以非零退出。

## 5. 验证证据

```bash
PYTHONPATH=<pyyaml> python3 -m unittest discover -s tools/fastlane-build -p 'test_*.py' -v
# Ran 76 tests ... OK（含 P11 workflow/license/reproduce 门禁；
# 无 PyYAML 时 test_check_workflow 的 12 项 skip，其余 64 项 OK）

python3 tools/fastlane-build/fastlane_build.py build \
  --manifest fastlane-toolbox/manifest.json \
  --out <tmp>/fastlane-toolbox-real --cache <tmp>/fastlane-toolbox-cache
# fixture=false, entries=50, totalSize=2234863,
# sourceDateEpoch=1792540800 (2026-10-21T00:00:00Z)

python3 tools/fastlane-build/fastlane_build.py verify-toolbox \
  --toolbox <tmp>/fastlane-toolbox-real
# ok=true, entries=50, elf16k=ok, elfChecked=2, symlinks=0,
# totalSize=2234863, manifestSize=10639, totalBytes=2245502,
# treeDigest=763ac97bc6bb0fcf1b3bec91e6fa0d10d64a14285ef71067ab4ecc9905d25e5e
# manifest SHA256=38c86fd19441f8b879ef69c3a665745b9ebe8e0df1bc21955c7835827e69734b
# busybox SHA256=ebd2865edcab0b590c7d0edb70d3e782cbfb541e518a390ced3a3e186509bc7f

python3 tools/fastlane-build/reproduce.py \
  --manifest fastlane-toolbox/manifest.json \
  --cache <tmp>/fastlane-toolbox-cache --runs 2
# ok=true, runs=2, treeDigest=763ac97b..., totalBytes=2245502

python3 tools/fastlane-build/fastlane_build.py inject-apk \
  --apk-in <tmp>/fake-unsigned.apk --toolbox <tmp>/fastlane-toolbox-real \
  --apk-out <tmp>/fake-fastlane.apk
# INJECTED 51 files
python3 tools/fastlane-build/fastlane_build.py verify-apk \
  --apk <tmp>/fake-fastlane.apk \
  --runtime-manifest <tmp>/fastlane-toolbox-real/bin/manifest.json
# ok=true, entries=50, elf16k=ok, signed=false, zipalign=SKIP
```

负向门禁（`prove_gate_blocks.py`）逐项输出非零阻断：篡改 SHA、已签名 APK
注入、体积预算、缺失条目、APK 路径穿越、toolbox SHA、toolbox 体积、许可证
字段缺失、manifest 路径穿越；最后打印 `GATE_BLOCK_OK`。

`zipalign` 在本环境不存在，APK 门禁返回 `SKIP`；有 Android build-tools 的
CI 任务会传入真实 `zipalign`。Gradle/AGP 真实 APK 未在本环境构建。

## 6. 未验证 / not-tested

- 设备上从 `/mnt/apk/bin` 执行 BusyBox：**已执行到 `execve`，但被平台 SELinux 拒绝**（见 6.1）；terminal/storage 仍 `not-tested`。
- AGP 资源源集把 `bin/` 放到 APK 根并保留 zipfuse 执行语义：未本机构建
  （无 Android SDK）；CI 任务已接线。
- `zipalign -P 16` 真实检查：本机无 build-tools，`SKIP`；CI 的
  `check-apk-16kb.sh` 在缺少 zipalign 时直接失败，不会静默跳过。
- 编译器/git/python：v1 不含；`distcc-clang` profile 为 `not-tested`。
- 16 KB 内核真机运行：未测；只验证了 ELF 对齐与 APK 结构。
- Gradle/AGP 真实 APK：未在本机构建；本轮用合成未签名 APK 验证注入/校验。

### 6.1 2026-10-21 DV-01A 设备结论

- APK 中 `bin/sh` 为 1,115,944 bytes，ZIP `external_attr` mode 0（zipfuse `bin/` 默认执行位）。
- 设备上 payload 的 `realpath`、`stat`、`access(X_OK)` 对 `/mnt/apk/bin/sh` 都成功，说明文件存在、路径正确且 zipfuse 的执行位生效；P03 的打包假设成立。
- `execve` 仍返回 EACCES(13)；guest audit 为 `execute_no_trans` 拒绝（`scontext=u:r:microdroid_app:s0`、`tcontext=u:object_r:system_file:s0`、`dev="fuse"`、`permissive=0`）。A16 policy 有 `execute` 但没有给 `microdroid_app` 对 `system_file` 的 `execute_no_trans`。
- 因此阻塞在平台 SELinux 策略，不在 toolbox 内容、APK 打包或 ZIP 执行位；需要平台侧变更/支持的可执行挂载。DV-01 terminal/storage 仍 `not-tested`，不进入 DV-02。
- 本轮未改 toolbox 内容、manifest、签名身份或 QA channel。

## 7. 许可证、体积与风险

- BusyBox：GPL-2.0-only。manifest 固定 Alpine 包 SHA256、二进制 SHA256、
  upstream `busybox-1.36.1.tar.bz2` SHA256 与 Alpine 3.20 打包树。
  发布含该二进制的 APK 时必须同时提供对应源码或有效书面 offer；见
  `NOTICE-fastlane.md`。需法务复核。
- 体积：条目 2,234,863 字节（≈2.13 MiB），50 条目；含 manifest 的 `bin/`
  合计 2,245,502 字节（≈2.14 MiB）；预算 80 MiB。
- 复现：同一输入两次构建的 tree digest 一致
  `763ac97bc6bb0fcf1b3bec91e6fa0d10d64a14285ef71067ab4ecc9905d25e5e`；
  `SOURCE_DATE_EPOCH=1792540800`，构建文件 mtime 与注入 ZIP 时间戳固定。
- 许可证门：manifest、SBOM、NOTICE 交叉校验 package SHA256 / binary SHA256 /
  版本 / license / downloadLocation / corresponding-source；任一不一致即非零。
- 风险：静态 glibc/musl 与 Android 内核 syscall 兼容性未真机验证；
  BusyBox applet 行为与完整 coreutils 有差异；wrapper 依赖固定
  `/mnt/apk/bin` 路径。
- 不整体搬运 Termux GPL 仓库；v1 不分发 Termux 代码。

## 8. 研究来源

- AOSP zipfuse `guest/zipfuse/src/inode.rs`（`bin/` → `EXECUTABLE_FILE_MODE`）：
  <https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/guest/zipfuse/src/inode.rs>
- Termux app `TermuxInstaller.java`（bootstrap 解包、SYMLINKS.txt）：
  <https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java>
- Termux bootstrap release（2026-10-18-r1+apt.android-7）：
  <https://github.com/termux/termux-packages/releases>
- Alpine 3.20 `busybox-static` 包与 APKBUILD：
  <https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/busybox-static-1.36.1-r31.apk>
  <https://gitlab.alpinelinux.org/alpine/aports/-/tree/3.20-stable/main/busybox>
- Android 16 KB 页面要求（ELF `p_align` 与 `zipalign -P 16`）：
  <https://developer.android.google.cn/guide/practices/page-sizes>
  <https://developer.android.com/tools/zipalign>
- AGP 自动在签名前 zipalign，手动注入必须在签名前：
  <https://developer.android.com/tools/zipalign>
- `SOURCE_DATE_EPOCH` 规范：
  <https://reproducible-builds.org/specs/source-date-epoch/>
- SPDX 2.3 规范（SBOM 字段与关系）：
  <https://spdx.github.io/spdx-spec/v2.3/>

## 9. 与 P02 对齐

P02 可直接读取 `/mnt/apk/bin/manifest.json`；环境建议
`PATH=/mnt/apk/bin`，`HOME/TMPDIR` 指向 `/mnt/encryptedstore`。v1 没有
`clang`/`distccd`，P02 的 distcc 路径必须等 `distcc-clang` profile 在
DV-03 通过后才能启用。
