# research/uml-kernel-build —— UML 内核可复现构建（工单 01）

Raymer8639/linux-um-arm64 `um-arm64-v0.3.0`（commit `169b17ff…`，Linux 7.2.0-rc4）的 bionic UML 内核复现构建流水线。x86_64 容器 + NDK r29 交叉编译（路径决策与证据见 manifest.md）。方法与结论全文：`docs/research/2026-11-01-uml-kernel-reproducible-build.md`。

## 文件

| 文件 | 说明 |
|---|---|
| `build-bionic.sh` | UP（linux-bionic）profile 构建脚本，从 pinned config 出发 olddefconfig + 全量编译 |
| `build-bionic-smp.sh` | SMP（linux-bionic-smp）profile 同构脚本 |
| `defconfigs/linux-bionic.config` | **pinned config**：release 二进制 IKCONFIG 提取（1734 行），非树内 defconfig 再生成 |
| `defconfigs/linux-bionic-smp.config` | 同上，SMP profile（2021 行） |
| `manifest.md` | pin、资产/产物 sha256、比对表、字节差异归因、复现步骤 |
| `checklist-haven-three-pits.md` | S3：Haven 三坑构建期检查项（一页） |
| `check-16kb-kernel.sh` | 产物 16KB PT_LOAD 对齐检查（tools/ci/check-elf-16kb.sh 同口径，READELF 可指定 llvm-readelf） |
| `release-assets/v0.3.0/` | release 资产本地下副本（linux-bionic / linux-bionic-smp / stub_exe / MANIFEST / SHA256SUMS + IKCONFIG 提取件），~23MB，可重新下载，建议不入库 |
| `out/` | 本次构建输出（build-up / build-smp / 日志 / config 对照），建议不入库 |
| `.toolchain/` | 非交付物：NDK r29 解包 + 源码树/压缩包本地下副本（构建输入，可重下），**不要入库** |
| `s2-config-diff.txt` | linux-bionic vs linux-bionic-smp 全量 config diff（diffconfig，316 项） |

## 最短复现（UP profile）

```sh
curl -sL -o ndk.zip https://dl.google.com/android/repository/android-ndk-r29-linux.zip && unzip -q ndk.zip
git clone --depth 1 --branch um-arm64-v0.3.0 https://github.com/Raymer8639/linux-um-arm64 src
docker run --rm -v $PWD/src:/src -v $PWD:/work -e SRC=/src -e OUT=/work/out/build-up \
  -e NDK=/work/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64 -e JOBS=$(nproc) ubuntu:24.04 \
  bash -c 'apt-get update -qq && apt-get install -y -qq build-essential bison flex bc python3 file && bash /work/build-bionic.sh'
```

已知偏差（详见 manifest.md）：release 的 clang 是 `dev, +pgo, -bolt, -lto, -mlgo` flavor（MANIFEST 未记录），stock NDK r29 无法逐字节复现内核本体；stub_exe 可逐字节复现。