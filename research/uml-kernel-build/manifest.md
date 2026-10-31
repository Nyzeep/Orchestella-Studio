# UML 内核构建 manifest（工单 01 / S1）

生成：2026-10-31，fabric-batch1-A-build。构建环境：x86_64 Linux（8 核）+ Docker 容器 ubuntu:24.04 + Android NDK r29（android-ndk-r29-linux.zip，clang 21.0.0 based on r563880c）。本批次无设备，功能验证 not-tested。

## 源码 pin（Raymer8639/linux-um-arm64 um-arm64-v0.3.0）

| 项 | 值 | 来源 |
|---|---|---|
| tag | `um-arm64-v0.3.0`（注解 tag 对象 `48af0dbc…`） | git ls-remote，访问 2026-10-31 |
| commit | **`169b17ff3f66d507cb137b770ceb1fd9f4547a32`**（branch `um-arm64`） | 同上 + release MANIFEST |
| upstream baseline | `2f88f5689de1a039764d00f466209acf0c010eaf`（uml 树 next） | release MANIFEST |
| 内核版本 | Linux 7.2.0-rc4（Makefile:VERSION=7 PATCHLEVEL=2 SUBLEVEL=0 EXTRAVERSION=-rc4，tarball 实读） | 源码树 |
| NDK / API / page | r29 / API 30 / 16 KB（CONFIG_PAGE_SHIFT=14，IKCONFIG 实测） | release MANIFEST + IKCONFIG |

## release 资产（下载自 GitHub Release，sha256 -c SHA256SUMS 全过）

| 资产 | 大小 (B) | sha256 |
|---|---|---|
| linux-bionic | 9,648,496 | `5c205533a5b02d5b22683475807aea7d6c2f4df8aec6be4d6034f8185ec59c18` |
| linux-bionic-smp | 13,272,376 | `b8ccc5f0d6327e2d3684ffe3d77c95835a66d277c5b6466c9452113272bdb360` |
| stub_exe | 2,000 | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e` |

与本仓库会话 21 记录比对：`libuml_kernel.so` = 9,648,496 B（sha256 前缀 5c205533…）✓ 一致；`libuml_stub.so` = 2,000 B（前缀 731c48d5…）✓ 一致（research/uml-runner/README.md:35-36 的映射关系成立：libuml_kernel.so=linux-bionic，libuml_stub.so=stub_exe）。

## 本次重建产物（x86_64 容器 + NDK r29 交叉编译）

| 产物 | 大小 (B) | sha256 | 与 release 比对 |
|---|---|---|---|
| 重建 linux-bionic | 9,647,456 | `8ce2a3bc40b1aa1dfa3b7d196f057d604b43a79fe753be3eb1b355aa72013305` | **不一致**：差 1,040 B（0.011%）；config 等价（8 行 toolchain 探针差异） |
| 重建 stub_exe | 2,000 | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e` | **逐字节一致** ✓ |
| 重建 linux-bionic-smp | 13,271,336 | `623427004e4a7c955d8d1553b432297d3872bbb23ba9028fa32843c7dc7371e6`（2026-10-31 协调复核更正：SMP 首跑 strip 文件名错误后修复重跑，此为最终产物实测值） | **不一致**：差 1,040 B（0.0078%）；config 仅 1 行 toolchain 探针差异 |

注：重建产物保留在 `out/`（build-up/、build-smp/），可由 `build-bionic.sh` / `build-bionic-smp.sh` 重新生成；release 资产在 `release-assets/v0.3.0/`（可从 GitHub Release 重新下载）。大文件不要入库，入库由用户手动处理。

## 字节不一致的归因

- **工具链 flavor 不同（主因，已实证）**：release 内嵌 `CONFIG_CC_VERSION_TEXT="Android (dev, +pgo, -bolt, +lto, -mlgo, based on r563880c) clang version 21.0.0"`（IKCONFIG 提取）；stock NDK r29 的 clang 为 `Android (13989888, +pgo, +bolt, +lto, +mlgo, based on r563880c)`（本机 `clang --version` 实测）。同一 llvm-project 基线（r563880c）、同为 clang 21.0.0，但 PGO/BOLT/LTO/MLGO 布局不同 → 代码生成差异。MANIFEST 只记 `ndk=r29`，未记 clang flavor —— 这是 release manifest 的信息缺口。
- config 往返：release IKCONFIG → olddefconfig → 再提取，差异仅 8 行且全部是编译器探针（CC_VERSION_TEXT + RUSTC_* 探针：release 构建机有 rustc 1.99.0，NDK 无 rustc；两侧 CONFIG_RUST 均未启用，无功能差异）。
- 其余未知贡献：构建路径（O= 目录）、HOSTCC（release 未知，本构建 gcc 13.3.0）、build-id。未逐一归因，不做进一步猜测。

## 构建路径决策（为什么不是 arm64 容器）

- binfmt 可用性已验证：`docker run --rm --platform linux/arm64 alpine uname -m` → `aarch64`（2026-10-31）。
- 但 NDK 官方仅发布 darwin/linux/windows 三种 host 包，linux 包 = x86_64 工具链（https://dl.google.com/android/repository/repository2-3.xml ，访问 2026-10-31，无 linux-arm64 变体）。arm64 容器内要么用发行版 clang（工具链来源进一步偏离 release），要么对 x86_64 clang 做双层模拟。
- release 本身即 NDK r29 linux-x86_64 工具链产物（默认路径 `toolchains/llvm/prebuilt/linux-x86_64`，harness/build-bionic-termux.sh），因此 **x86_64 容器交叉编译 = 与 release 同构的构建形态**，且避免 qemu-arm64 的 1–3 小时模拟成本（本次实测 UP 全程约 10 分钟）。

## 复现步骤

```sh
# 1. 源码：codeload tarball（tag um-arm64-v0.3.0，sha256 08578a494412125dfc6b8f203e984b68d20e313173319d6a6c115e2875e0116c）
#    或 git clone --depth 1 --branch um-arm64-v0.3.0 https://github.com/Raymer8639/linux-um-arm64
# 2. NDK：curl -O https://dl.google.com/android/repository/android-ndk-r29-linux.zip && unzip
# 3. 构建（容器内）：
docker run --rm -v <src>:/src -v <this-dir>:/work \
  -e SRC=/src -e OUT=/work/out/build-up \
  -e NDK=/work/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 \
  -e JOBS=$(nproc) ubuntu:24.04 \
  bash -c 'apt-get update && apt-get install -y build-essential bison flex bc python3 file && bash /work/build-bionic.sh'
# SMP 版同上，OUT=/work/out/build-smp，脚本换 build-bionic-smp.sh
```

## 16 KB LOAD 对齐（llvm-readelf 实测，check-16kb-kernel.sh 同口径）

| 产物 | 对齐 |
|---|---|
| release linux-bionic / linux-bionic-smp | 过 |
| release stub_exe | **不过**（release 原生如此；app 域 exec 装载，详见 checklist 坑③） |
| 重建 linux-bionic / linux-bionic-smp | 过 |
| 重建 stub_exe（与 release 逐字节一致） | 不过（同一性使然） |