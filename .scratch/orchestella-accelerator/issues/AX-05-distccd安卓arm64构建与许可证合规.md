# AX-05: distccd 安卓 arm64 构建与许可证合规

- Type: build/compliance
- Status: ready-for-human
- Priority: P1
- Owner: build/release owner
- Blocked by: 无（零设备）
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

distcc 3.4-4 在 `NOTICE-fastlane.md` 中仍是 `not-tested`，没有固定二进制、没有 SBOM、没有对应源码交付方案。交火第一版只做 distcc，必须先把可执行产物和 GPL 合规做扎实，否则不能随 APK 分发。

## 目标

- 产出可在 Android arm64 上运行的 `distccd`，版本、来源、SHA-256 固定。
- 许可证与源码交付义务可审计：NOTICE、SBOM、SOURCE-OFFER。
- 构建可复现；CI 可验证，不把二进制提交进仓库。

## 任务

- [x] 评估两条构建路线并二选一：
  - Termux `distcc 3.4-4` aarch64 包解包（记录依赖库清单）；
  - Alpine/Termux 源码 + NDK/CMake 或 autotools 交叉编译（优先静态或自带依赖）。
- [x] 固定版本与来源：上游 tarball SHA-256、打包补丁、编译 flags、NDK/CMake 版本。
- [x] 产物检查：`file`、`readelf -l`（16 KB 对齐）、`llvm-readelf -d`（依赖闭包）、`--version`（用 qemu-user 或设备外等价方式）。
- [x] SBOM：名称、版本、许可证、来源、SHA-256；写入 `NOTICE-fastlane.md` 的 distcc 小节。
- [x] 源码交付：在 release artifact 旁提供对应源码与构建脚本，或提供三年有效的书面 offer；在 release ticket 记录选择。
- [x] CI job：构建 + 检查 + 许可证扫描；失败即红。
- [x] 预算：产物大小、解压后大小、对 APK 的影响；超过阈值需记录并重新评估。

## 验收命令

```bash
bash tools/fastlane-build/build-distccd.sh
bash tools/ci/check-distccd-artifact.sh build/fastlane-distccd
python tools/ci/check_ci_module_coverage.py
```

## 边界

- 不把二进制提交进 git；只提交构建脚本、校验和与 NOTICE。
- 不链接 distcc 到 app 进程；只作为独立进程执行（GPL 边界）。
- 不使用来源不明的预编译包；所有来源必须可追溯。

## 证据

- 构建日志摘要、SHA-256、SBOM、许可证结论写入 `docs/reports/tickets/ax-05/implementation-report.zh-CN.md`。
