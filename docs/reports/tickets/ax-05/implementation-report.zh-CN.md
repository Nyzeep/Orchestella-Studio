# AX-05 distccd 安卓 arm64 构建与许可证合规 实施报告

- 日期：2026-10-23
- Base：`main @ 4765d4b`
- Branch：`feat/accelerator-batch1`
- 工单：`.scratch/orchestella-accelerator/issues/AX-05-distccd安卓arm64构建与许可证合规.md`
- 零设备：本会话没有运行 adb、没有安装 APK、没有启动设备。产物用 qemu-user 在宿主执行 `--version`。

## 1. 结论

**完成**（零设备范围）：产出可复现的 Android arm64 静态 `distccd 3.4`，并通过 ELF/16 KB/PT_TLS/静态闭包/SHA/SBOM/许可证门禁。

- 产物：`build/fastlane-distccd/bin/distccd`，720,992 B，SHA-256 `3d5f74780c77bff8d500a75c31c235e25e7dab9e81a72bf7553a49fa5116decc`。
- 两次独立构建字节一致；二进制不入库。
- 默认构建路线是**源码 + NDK 交叉编译**，不是 Termux 包解包。

## 2. 路线选择

| 路线 | 结论 | 原因 |
| --- | --- | --- |
| Termux `distcc 3.4-4` aarch64 包解包 | 放弃 | Termux 包依赖 `libpopt`，动态链接 Bionic，且带 Termux prefix 假设；无法在宿主用 qemu-user 直接执行 `--version`，16 KB 对齐也无法由本仓库复现 |
| 源码 + NDK 27 交叉编译 | **采用** | 固定 tarball、可静态链接、可控 16 KB 与 PT_TLS、可在 qemu-user 下验证、可重复构建 |

来源（访问日期均为 2026-10-23）：

- distcc 3.4 release：<https://github.com/distcc/distcc/releases/tag/v3.4>
- Termux 包索引（用于核对 3.4-4 与 `libpopt` 依赖）：<https://packages.termux.dev/apt/termux-main/>
- Android NDK 下载与版本：<https://developer.android.com/ndk/downloads>
- qemu-user 文档：<https://www.qemu.org/docs/master/user/main.html>
- Termux 的 ELF 清理工具（同类 PT_TLS 修正的参考）：<https://github.com/termux/termux-elf-cleaner>

## 3. 固定输入

| 项 | 值 |
| --- | --- |
| 上游 tarball | `distcc-3.4.tar.gz` |
| URL | `https://github.com/distcc/distcc/releases/download/v3.4/distcc-3.4.tar.gz` |
| tarball SHA-256 | `2b99edda9dad9dbf283933a02eace6de7423fe5650daa4a728c950e5cd37bd7d` |
| 本地补丁 | `tools/fastlane-build/distcc-reproducible.patch`，SHA-256 `f2f1af1e2aca3bc081cf77d28dde6a7f76a1c9f599b3bee50c1c8aa509a16180` |
| NDK | `27.0.12077973`（与 payload 构建同一版本） |
| 编译器 | `aarch64-linux-android26-clang` |
| CFLAGS | `-O2 -fPIE -fno-emulated-tls -g0` |
| LDFLAGS | `-static -pie -Wl,-z,max-page-size=16384 -Wl,--undefined=orchestella_tls_align` |
| configure | `--host=aarch64-linux-android --build=x86_64-pc-linux-gnu --disable-pump-mode --without-avahi --with-included-popt --without-libiberty --disable-Werror` |
| configure 探测覆盖 | `ac_cv_func_getloadavg=no`、`rsync_cv_HAVE_C99_VSNPRINTF=yes` |

### 3.1 可复现补丁

`src/help.c` 与 `src/daemon.c` 的 `__DATE__/__TIME__` 会嵌入构建时刻。补丁把它们替换为固定字符串 `"2026-10-20", "00:00:00"`（与 `SOURCE_DATE_EPOCH=1792540800` 对齐），不改变行为。两次独立目录构建得到同一 SHA-256。

### 3.2 64 字节 PT_TLS

NDK r27 的 aarch64 静态 PIE 默认输出 `PT_TLS p_align=8`；Bionic 在 arm64 要求至少 64，启动会报 “executable's TLS segment is underaligned”。`tools/fastlane-build/distcc-tls-align.c` 提供一个 64 字节对齐、放在独立 `.tdata.orchestella` 输入节的 TLS 对象，并用 `--undefined` 强制保留；链接器因此输出 `PT_TLS p_align=0x40`。这是编译期修正，不是对 ELF 的二进制补丁。

## 4. 产物检查

`bash tools/ci/check-distccd-artifact.sh build/fastlane-distccd`：

```
PASS ELF64
PASS AArch64
PASS static
PASS readelf class
PASS readelf machine
PASS readelf type
PASS PT_LOAD segments=4 16KB-aligned
PASS PT_TLS alignment=0x40
PASS no PT_INTERP
PASS no DT_NEEDED
PASS distccd --version under qemu-user
PASS SHA256SUMS
PASS size=720992B budget=4194304B
PASS SBOM
DISTCCD_ARTIFACT_OK
```

`--version` 输出：

```
distccd 3.4 aarch64-unknown-linux-android
  (protocols 1, 2 and 3) (default port 3632)
  built 2026-10-20 00:00:00
```

## 5. SBOM 与许可证

- SBOM：`build/fastlane-distccd/SBOM.spdx.json`，SPDX-2.3，包含：
  - `distcc-source` 3.4，GPL-2.0-or-later，tarball SHA-256；
  - `distccd-android-arm64` 3.4，GPL-2.0-or-later，binary SHA-256；
  - bundled popt（MIT）、miniLZO 2.10（GPL-2.0-or-later）；
  - Android NDK sysroot（静态 Bionic；license 标为 NOASSERTION，NDK NOTICE 必须随源码包交付）。
- `NOTICE-fastlane.md` 的 distcc 小节已从 “not-tested/未固定” 更新为固定来源、补丁、构建脚本、SBOM、对应源码义务与三年书面 offer 兜底。
- **对应源码选择**：release artifact 旁提供 upstream tarball + 本地补丁 + 两个构建脚本 + NDK `NOTICE.txt`；若未附源码包，则发布三年有效的书面 offer。release ticket 必须记录实际选择。法律复核仍未完成。
- GPL 边界：distccd 作为独立进程执行，不链接进 app 进程；本票没有把它打进 APK。

## 6. CI

在 `.github/workflows/fastlane-zero-device.yml` 新增 `distccd-android-arm64` job：

1. 安装 `qemu-user-static`、`build-essential`、`patch`；
2. 用 sdkmanager 安装 `ndk;27.0.12077973`；
3. 连续构建两次并用 `cmp` 比较；
4. 跑 `check-distccd-artifact.sh`（ELF/16 KB/PT_TLS/静态/SHA/--version/SBOM）；
5. 跑 `check-distccd-license.py`（SBOM、NOTICE、git ignore）。

该 job 不运行 adb、不安装 APK、不签名、不发布。`check_zero_device_workflow.py` 返回 `WORKFLOW_OK`。

## 7. 预算

| 项 | 值 |
| --- | --- |
| 可执行文件 | 720,992 B（约 704 KiB） |
| 解压后 | 同尺寸（单文件，无运行时依赖） |
| 依赖闭包 | 0 个 `DT_NEEDED` |
| 对 APK 的影响 | 本票 **0**（未打包）；未来若随 APK 分发，预计增加约 704 KiB 未压缩体积 |

阈值设为 4 MiB；当前占 17%。超过阈值时检查脚本会失败，需要重新评估。

## 8. 改动清单

| 文件 | 类型 |
| --- | --- |
| `tools/fastlane-build/build-distccd.sh` | 新增 |
| `tools/fastlane-build/distcc-reproducible.patch` | 新增 |
| `tools/fastlane-build/distcc-tls-align.c` | 新增 |
| `tools/fastlane-build/distccd-sbom.template.json` | 新增 |
| `tools/ci/check-distccd-artifact.sh` | 新增 |
| `tools/ci/check-distccd-license.py` | 新增 |
| `NOTICE-fastlane.md` | 修改（distcc 小节） |
| `.github/workflows/fastlane-zero-device.yml` | 修改（新增 job） |

没有提交二进制、构建产物、token 或设备数据；`build/` 仍被 `.gitignore` 忽略。

## 9. 验收

| 命令 | 结果 |
| --- | --- |
| `bash tools/fastlane-build/build-distccd.sh` | `DISTCCD_OK version=3.4 sha256=3d5f... bytes=720992` |
| 第二次构建到 `build/fastlane-distccd-second` 后 `cmp` | 字节一致（`REPRODUCIBLE`） |
| `bash tools/ci/check-distccd-artifact.sh build/fastlane-distccd` | `DISTCCD_ARTIFACT_OK` |
| `python tools/ci/check-distccd-license.py --artifact build/fastlane-distccd` | `DISTCCD_LICENSE_OK` |
| `python tools/ci/check_ci_module_coverage.py` | 58 passed, 0 failed |
| `python tools/fastlane-build/check_zero_device_workflow.py` | `WORKFLOW_OK` |
| `bash -n` 两个脚本 | 通过 |

## 10. 偏差

1. **构建路线**：工单优先 Termux 包解包；因动态 Bionic/依赖/无法宿主验证而改为源码 + NDK。理由见 §2。
2. **pump mode / avahi**：关闭。pump mode 需要 Python include server，avahi 需要 mDNS；本版加速只做 distcc TCP，不需要它们。
3. **静态 Bionic 的 `getloadavg`**：API 26 没有该符号，configure 缓存强制为 no；distccd 的负载值会报 -1，不影响编译服务。
4. **版本字符串的构建时间**：固定为 `2026-10-20 00:00:00` 以换取可复现；不是实际构建时刻。
5. **APK 集成**：本票不把 distccd 放入 APK 或 payload；放置/启动属于 AX-06。

## 11. 风险与未验证

- **not-tested**：真机执行 `distccd`、16 KB 页设备启动、真实 distcc 编译任务；属于 AX-06/DV-AC。
- **not-tested**：APK/payload 打包后的体积与加载路径。
- **not-tested**：macOS/arm64 宿主的构建；脚本只验证了 Linux x86_64。
- **not-tested**：pump mode、avahi、`--daemon` 的 setuid 用户路径（Android 上没有 `distcc` 用户；运行时必须用 `--no-detach`/显式 `--allow`，由 AX-06 定义）。
- 静态链接 Bionic 使二进制包含 NDK sysroot 代码；NDK `NOTICE.txt` 的随附义务需要在 release 时执行。
- 许可证结论是工程判断，不替代法律复核。

## 12. 待维护者决策

1. 对应源码交付选“随 artifact 附源码包”还是仅“三年书面 offer”（当前实现两者都写进 NOTICE，release 时二选一记录）。
2. 是否需要在 AX-06 前启用 pump mode（会引入 Python 运行时）。
3. 4 MiB 产物体积阈值是否合适。
4. distccd 在 payload/APK 中的放置路径与启动方式。
5. 是否把 `distcc` 客户端也纳入构建（本票只交付 `distccd`）。
