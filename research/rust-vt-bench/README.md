# Rust VT bench（Kotlin vs Rust 终端内核原型）

> **冷冻状态（frozen）**：本目录是 2026-11-15 定稿的研究快照，不随产品代码演进，也不参与
> 0.6.x 发布。工具链由 `rust-toolchain.toml` 钉在 **1.91.0**（参考运行的 rustc 为
> `rustc 1.91.0 (f8297e351 2025-10-28)`）；**最近有效数据 2026-11-15**，即
> `out/kotlin.json`、`out/rust.json`、`out/compare.md` 三个文件。复现命令见
> [复现命令](#复现命令)：它们与 `.github/workflows/rust-vt.yml` 的 `run` 步骤逐行一致，
> 由 `test_workflow.py` 强制校验。

## 结论

数值一律以 [`out/compare.md`](out/compare.md) 为准，本文件不复制表格，避免与历史结论分叉。
一句话概括：5 个夹具的输入 sha256 对齐，Rust 吞吐全面高于 Kotlin，分配下降 97.8%–100%。

## 门规则

`compare.py` 在生成对照表之外做一件会失败的事——**分配回归门**：

| 输入 | 规则 | 退出码 |
| --- | --- | --- |
| 5 个夹具的 median alloc/MB | 相对 `out/alloc_baseline.json` 增长 > 10% 即失败 | 1 |
| 当前结果缺少被门约束的夹具的 alloc 指标 | 无法证明没有回归，失败 | 1 |
| 基线缺少 alloc 指标、基线或结果缺夹具、sha256 缺失/不一致、JSON 不可读 | 输入不成立，失败 | 2 |
| 分配在容差内 | 打印表格，通过 | 0 |

- 基线文件：`out/alloc_baseline.json`，取自 2026-11-15 参考运行的中位分配值。
- 容差：`--alloc-tolerance`，默认 10（百分比）；恰好等于容差算通过。
- **吞吐只报告不设门**：同一批夹具在不同宿主上差 1.6 倍以上（见下），只打印 advisory delta。
- 没有基线文件时门自动跳过（本机只想看表格时不必准备基线）；显式 `--baseline` 指向不存在的
  路径则是错误（退出 2），避免 CI 里静默跳过。
- `cursor` 的基线只有 1.6 B/MB，10% 容差折合 0.16 B/MB：该夹具上任何新增的小分配都会触发
  失败。这是有意为之——分配计数是确定性的，没有噪声可容，涨了就是代码路径变了。

## 复现命令

在仓库的 `research/rust-vt-bench/` 目录下执行（CI 用 job 级 `working-directory` 达到同样的
工作目录，所以块内不重复 `cd`）：

<!-- repro:begin -->
```bash
rustup toolchain install 1.91.0 --profile minimal --no-self-update
rustup default 1.91.0
rustc --version
python3 -m pip install --quiet pyyaml==6.0.2
python3 -B -m unittest discover -v
cargo test --release --workspace
python3 fixtures/gen.py
python3 fixtures/gen.py --verify
( cd fixtures/generated && sha256sum -c SHA256SUMS )
( cd fixtures/generated && sha256sum -c ../expected-sha256.txt )
cargo run --release -p vt-bench -- bench all --out out/rust.json
python3 compare.py --baseline out/alloc_baseline.json
```
<!-- repro:end -->

说明：

- `python3 -B -m unittest discover -v` 放在 bench 之前：离线用例最便宜，坏了先失败。历史表
  用例从 `git show HEAD:` 读 `out/kotlin.json`、`out/rust.json` 与 `out/compare.md`，所以
  本地重跑过 bench、工作区里的 `out/rust.json` 已被覆盖时，它仍然校验的是**已提交**的历史结论。
- PyYAML 只被 `test_workflow.py` 的 YAML 解析用例使用；未安装时该用例 skip，其余用例照常运行。
- `cargo test --release --workspace` 只覆盖 `vt-core` 与 `vt-bench`；`vt-jni` 是独立的
  workspace root（`cargo ndk` 构建 cdylib），不属于本门范围。

## 夹具

`fixtures/gen.py` 只用 Python 标准库、固定种子，重复运行必须逐字节一致。

| 路径 | 作用 |
| --- | --- |
| `fixtures/gen.py` | 生成 5 个 `.bin` + `manifest.json` + `SHA256SUMS`（写入 `fixtures/generated/`，已 gitignore） |
| `fixtures/gen.py --verify` | 按 `generated/manifest.json` 复核大小与 sha256，打印 5 行 `OK` |
| `fixtures/expected-sha256.txt` | 2026-11-15 参考运行钉住的 5 个 sha256，`sha256sum -c` 用它证明"任何人重跑都得到同样的字节" |
| `fixtures/generated/SHA256SUMS` | `vt-bench` 自己也按它校验输入；不一致时 bench 退出 3 |

只跑 `gen.py --verify` 是不够的：manifest 是本次生成时写的，自己校验自己必然通过。真正的前缀
证据是 `expected-sha256.txt`，所以 CI 两步都跑。

## 本机复跑记录（2026-11-16）

在一个原本没有 cargo 的容器里装好 rustup 1.91.0 后完整复跑两次：5 个夹具 sha256 与
`expected-sha256.txt` 全部一致，`alloc_bytes_per_mb` 五个夹具与基线**逐位相同**。吞吐连同一
台机器两次都差很多——冷缓存 45.1 / 40.7 / 13.6 / 65.1 / 84.6 MB/s，热缓存 71.0 / 77.1 /
20.8 / 95.1 / 131.4，2026-11-15 参考机 74.8 / 75.7 / 19.8 / 94.9 / 131.2（顺序为 plain /
ansi_color / cursor / wide / osc）。这就是"分配可设门、吞吐只能 advisory"的直接依据。

## CI

`.github/workflows/rust-vt.yml`：`ubuntu-24.04`，`timeout-minutes: 20`，只在
`research/rust-vt-bench/**` 或该 workflow 自身变化时触发；`permissions: contents: read`，
不读 secrets、不碰 adb / 设备 / Android SDK。cargo registry 与 `target/` 按 `Cargo.lock`
哈希缓存。CI 结果必须由推送后的真实 run 判定，本地不要代填。
