# Changelog

本文件记录用户可感知的变更，格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning 2.0.0](https://semver.org/lang/zh-CN/)，
`versionCode` 映射见 [版本标准](docs/standards/VERSIONING.md)。

只写已经进入源码的变更；标注为「未验证」的条目在真机复核前不得当作已交付能力对外表述。

## [0.6.2] - 2026-10-30

### Added

- **自动检查更新 + 关于入口升级标识**：主界面 `onResume` 起一次静默检查（6 小时间隔、全局单飞），
  有新版本时右上角「关于」按钮出现主题强调色的圆点（外圈用按钮底色描边，任何主题下都清晰）。
  关于页新增更新卡片：「有可用新版本 vX」+ 最多 6 行摘要 + 「前往下载」「暂时忽略」；点「前往下载」
  先给简要更新说明再打开发布页。「暂时忽略」按版本记忆，出现更新的版本时会再次提示。还没拿到过任何
  结果时（例如首次启动断网）按 15 分钟重试，不会把一次失败锁成 6 小时。
  规则见 `docs/standards/APP-UPDATE.md`：单一来源（GitHub Releases）、严格更新、零遥测、
  关闭开关后 0 连接 0 写入、失败保留上次结果。
- **更新摘要器 `UpdateNotes`**：丢弃引用块/标题/表格/图片/裸链接与 markdown 标记，保留前 5 行、
  320 字以内，超限补 `…`，保证对话框在任何发布说明下都不溢出。

### Changed

- **实例操作表的「不可用」不再整卡降透明**：硬件参数 / 克隆 / 彻底删除在运行中禁用时，原先分别把
  整张卡片压到 0.4–0.5 alpha，在淡紫与构成主义主题下看起来像「按钮坏掉变灰」。现在卡片表面与描边
  保持原色，只弱化内容层（0.55）并保留原因提示，三行视觉一致。
- **README 预览截图统一为 1080×2347**：终端那张原来是 1200×2608 的 PNG，表格里比其余五张宽一截；
  现在六张同尺寸同格式（JPEG），表格对齐。

### Fixed

- **关于页的「检查更新」结果对话框不再把整篇 Release 正文塞进 `setMessage`**：改为摘要 + 版本 +
  通道，并补上 `ThemeApplier.apply`，避免该对话框在自定义主题下退回默认灰色按钮。

## [0.6.1] - 2026-10-30

### Fixed

- **七种发行版里六种的 rootfs 下载必然失败**（用户报障「其余发行版一直下载失败」）。两条链同时断了：
  ① 上游快照会被上游删掉——linuxcontainers.org 的 distrobuilder 构建按天生成，TUNA/USTC 的
  `lxc-images` 只保留最近三天，清单钉的 `20261013_05:24` 一类路径今天全部 404；② 唯一的兜底是
  `distro-catalog-v1` 这个 GitHub 发布，而它**在仓库里根本不存在**（只剩 `v0.6.0`），于是 GitHub
  直连和 5 个代理全部 404。现在按「来源必须长期存在 + 候选必须逐字节相同」重排：openSUSE 改钉
  download.opensuse.org 的 `Build19.146` 并由 TUNA 领跑，Ubuntu 维持 TUNA/USTC 直连；
  Fedora / AlmaLinux / Rocky / Arch / Debian 只发布 OCI 布局、可变 `-latest` 或整盘镜像，无法按
  摘要钉住，改为项目自托管快照并重建 `distro-catalog-v1` 发布。清单里的 LXC 快照路径与指向空发布的
  条目全部删除，并加单测锁死：任何 `lxc-images` / `linuxcontainers.org` 候选再次出现即失败。
- **镜像探测从「测 ping」改成「测下载」**：健康探测原本只发 HEAD，排的是往返延迟。真机上
  `gh-proxy.com` 0.4 秒应答却只有 58 KB/s，`cloud.debian.org` 1.9 秒应答反而有 700 KB/s，旧逻辑
  让代理赢下排序，把 5 分钟的 Debian 下载变成 8 小时。现在每个候选只取 64 KiB（带 Range 的
  GET，服务端忽略 Range 也只读 64 KiB 就断开）按实测耗时排序，真机上自动选中 github.com 直连，
  86 MB 约 4 分钟完成。
- **Debian 的「官方云镜像」是个 3 GiB 的整盘镜像，不是 rootfs**：上一轮把 Debian 改钉
  `cloud.debian.org/.../debian-13-genericcloud-arm64-20261026-2643.tar.xz` 后，下载与校验都过，
  实例也能建出来，但真机装配时 guest 在 `input-verification` 阶段直接关机——那个 tar 里只有一个
  `disk.raw`（解包 3.0 GiB，超出 2 GiB 提取上限），没有根文件系统树。该 pin 已撤回，Debian 回到
  项目自托管的 `debian-13-rootfs.tar.xz`（90 MB，解包 0.41 GiB 的真 rootfs），教训记在这里：
  rootfs 归档必须校验「成员是目录树」而不只是「sha256 对得上」。

### Verified

- **真机全链路（Honor MEY-AN00 / Android 16 / SM8735，2026-10-30）**：装 0.6.1 → 新建 Debian 13 →
  下载（节点 github.com，86.2 MB）→ 装配 → systemd 到 `getty.target` → `root@orchestella` 自动登录；
  `/etc/os-release` 为 Debian 13 (trixie)，`uname -r` 为 7.2.3。

## [Unreleased]

### Added

- **镜像下载抗网络差异**：下载前并发探测全部候选节点（4 秒预算）并按实测延迟排序；节点返回 4xx
  立即故障转移（不再对 404 重试三次 × 1/2/4 秒退避）；GitHub 托管镜像的代理从 2 个扩到 5 个
  （ghfast.top / gh-proxy.com / ghproxy.net / hk.gh-proxy.com / mirror.ghproxy.com）。
- **「开发者工具链」入口**（`实例 → 桌面与开发工具`）：给出当前发行版的安装命令，支持「在终端填入
  命令」（终端 + 预填的发送框，确认后执行）与「复制命令」。基础镜像按设计只带终端/网络/SSH 工具，
  `gcc`、`make`、`python3`、`vim` 需要一次性安装（Ubuntu 24.04 实测 80 MB / 约 8 分钟）。
  入口同时放在实例详情页顶部，实例停机也能看到。
- **pVM 加速扩展包随 0.6.0 发布**（`Orchestella-Accelerator-v0.6.0-release.apk`）：仅对平台开放
  "非保护型 custom VM"的设备有效；本轮实测机型不支持，装上也只会回落 QEMU。

### Fixed

- **扩展包的 release 包名与主包相同**：`.accel` 后缀此前只加在 debug 构建类型，release 产物
  applicationId 与主包完全一致（同 versionCode、同签名），安装会直接覆盖主包。现在两种构建类型
  都带 `.accel`，并用 `verify_apk.py --expected-application-id` 固定。
- **`apt` 每条命令都打印 `N: Ignoring file 'ubuntu.sources.orchestella-original'`**：云镜像 deb822
  源的备份被放在 `sources.list.d/` 里，apt 会扫描该目录。备份改到 `/var/lib/orchestella/apt-backup/`
  （源码修复；随下次 guest 产物重建生效）。

### Changed

- **默认主题改为「看板娘 · 淡紫工控」**（`ThemeCatalog.default`）：全新安装首次启动即这套固定
  配色；从未选过主题、但存在其它偏好数据的老安装同样落到它，已显式选过主题的安装一律保持
  原样（不改写、不迁移）。`ThemeStore.mode()` 在无保存值时改为「按主题自身形态推导」，
  固定主题因此默认 `FIXED`，不会先跟随系统再跳一下。
- **配置页 `THEME SELECTION` 卡片首位新增看板娘快捷项**：与构成主义红 / 蓝 / 黄并列，标「默认」，
  点一下即时切换到 `mascot-lavender` 并锁定 `FIXED`；ACTIVE 徽章四个主题各自独立判定。

### Fixed

- **发布包缺 QEMU 运行时载荷，装上去创建实例必然失败**（用户报障「Linux 实例不可用」）。
  根因：`app/src/main/jniLibs/arm64-v8a/{libdroidlayer_qemu,libgvproxy}.so` 按许可证要求不入库，
  会话 25 又把工作树里的副本移到了 `artifacts/qa-jniLibs-4k-arm64/`；于是构建照旧「成功」，
  打出来的 0.6.0 里只剩 `assets/Image` 与 initramfs，`NativeExecutableStore.ensure()` 在
  QEMU 启动前就抛 `missing from both nativeLibraryDir and the APK`。
- **新增发布门禁 `tools/release/check_runtime_payload.py`**（7 例单测，已接入 `release.yml`
  的 `publish-draft`）：逐字节比对 APK 里的内核 / initramfs / QEMU 二进制与
  `runtime-artifacts-v1.sha256`，缺一件就拒绝建 Release。对旧包复现为 FAIL，对新包为 OK。
- **真机复验（Xiaomi 25102RKBEC / Android 17 / 4 KB 页）**：清空旧包 → 装新包 → 建 Ubuntu 24.04
  → 装配（格式化 / 解包 / 证书 / apt 装包 / 首次启动验证）→ systemd 到 `multi-user.target` →
  `root` 自动登录。README 的终端截图换成本轮实拍。
- **README 精选截图在 GitHub 上 404**（本次发布的直接起因）：`.gitignore` 里的
  `!/docs/assets/screenshots/*.png|jpg` 从未生效——`/docs/assets/*` 排除的是**目录本身**，
  git 不会走进被排除的目录，文件级反选无法解除父目录排除。补上 `!/docs/assets/screenshots/`
  后截图才真正入库。
- **新增 `tools/ci/check_readme_assets.py` 门禁**（含 8 例单测，已接入 `ci.yml`）：
  README 引用的每张本地图片必须同时满足「在工作树里」与「已被 git 跟踪」，
  专门拦住「本地看着正常、推上去是坏图」这一类故障。

### Changed (docs)

- **README 界面预览重拍并扩充为六张真机截图**：工作台 / 发行版目录 / 硬件配额 / 存储配额 /
  启动与部署 / 真实终端；中英双语同步。

## [0.6.0] — 2026-10-29（稳定版准备，2026-10-30 重打包）

稳定版里程碑：把发布形态收敛为双包，把四条执行车道的定位与数字口径写成可核对形态，
并修掉一批真机上能直接看见的界面缺陷。本版本已发布为 GitHub Release（`v0.6.0`）；
2026-10-30 以**同版本号**（0.6.0 / 600）重打包一次，只含默认主题、配置页快捷项、
README 截图与发布说明的修正，功能面不变。设备侧验收项仍在推进。

### Added

- **「看板娘 · 淡紫工控」主题**：从看板娘原图实测校准的固定配色主题（背景为线框淡紫、
  文字为头发暗部的墨紫、终端保留原图 `#242424` 深底与奶油白字 + 同气质 16 色 ANSI）。
  该主题为**固定模式**：只有一种形态，不受系统深浅色影响，状态栏图标按实际对比度定。
  全部文字/背景组合按 WCAG ≥ 4.5:1 校准，采样与对比度证明可由
  `tools/branding/sample_palette.py` 复现。**真机观感待验收。**
- **设置页「关于」看板娘原图展示**：原比例直出（零裁切），与版本号同页。
- **双包发布形态**：主包只保留应用本体与 QEMU 完整内核路径；pVM 的 payload、编译器引擎与
  `PVM-COMPILER-MANIFEST` 移入独立扩展包 `io.github.nyzeep.orchestella.accel`。
  扩展包 `versionCode` 必须与主包一致，不一致时运行期直接拒绝并说明原因，不做尽力加载。
- **应用图标**：自适应图标（前景 / 背景 / monochrome 主题图标）+ 五档密度 mipmap +
  传统方形与圆形图标；品牌静图与可重复生成的脚本见 `docs/assets/branding/`、`tools/branding/`。
- **36 域 QEMU 车道**：`targetSdk 36` 下 QEMU 与 gvproxy 以 `nativeLibraryDir` 绝对路径 exec
  成功（执行类 AVC = 0），guest 可完成 systemd 启动并给出 root shell。基于会话 22 的设备实测。
- `CHANGELOG.md` 与发布签名向导（`docs/standards/RELEASE-SIGNING.md`）。

### Changed

- **应用图标 = 原画 1:1 直出（零处理）**：用户最终拍板——原方形画布直接铺满 108dp
  自适应图标画布，不做任何构图决策（face 居中由掩模自然呈现，无重绘元素）；monochrome
  主题图标用从原画提取的看板娘剪影（同样零重绘）。生成器 `make_app_icons.py` 保留
  cover（会话 24）与 fit（主体零裁切）两种历史构图可复现；幂等性保持（两次运行逐字节一致）。
  图标资产与掩模验收预览见 `docs/assets/branding/`。
- **按钮圆角默认 16dp**：三档默认值改为小 8 / 中 12 / 大 16（默认）；旧安装显式选过的
  「直角 0dp」保留为独立选项不受影响。主题器圆角令牌遵循「只增不减」契约：
  只会放大既有几何，不把药丸形徽章压平。
- **README 以中文为默认语言并改版**：`README.md` 即中文版，英文版移至 `README.en.md`，
  顶部双语互链；正文以「已实现功能 + 后续开发/优化方向」为主体，新增维护者精选的
  界面截图展示（`docs/assets/screenshots/`，经敏感数据门禁放行）。
- **版本号 0.5.3 → 0.6.0（versionCode 503 → 600）**，主包与扩展包同步锁定。
- **背景图语义统一**：终端背景、墙纸与所有位图背景收敛到单一 `CoverCropDrawable`
  （等比缩放铺满 + 居中裁切，绝不非等比拉伸），几何计算抽成纯函数并由单测钉死。
- **桌面虚拟键条**：ESC / TAB / CTRL / ALT / SHIFT 等按键的触控高度 44dp → 48dp。
- **通知渠道**：名称与描述改为中文，且每次进程启动刷新，老安装也能拿到新文案
  （用户自定义的重要性等级不受影响）。
- **端口转发表单文案**：半角括号与中英混排标点统一为全角。
- README 重写为中英双语，补四车道能力矩阵、安装指南、Roadmap 与许可证致谢。

### Fixed

- **配置不一致的 pVM 复用请求未被拒绝（#58）**：对同一已停止实例用不同配置再次启动时，
  复用分支会把配置漂移一律当作「payload APK 路径失效」删除重建，静默吞掉调用方真实
  的配置变更。现在只有 APK 路径漂移（扩展包重装的可恢复场景）才自愈重建；其余任何
  身份字段（内存 / 拓扑 / 存储 / 调试级 / payload 名）不一致一律拒绝
  （`VM_CREATE_FAILED`），且绝不触碰加密存储。红→绿回归用例已入库。
- **扩展包在本机判为「未安装」**：`AndroidManifest.xml` 的 `<queries>` 未声明扩展包，
  `targetSdk ≥ 30` 的包可见性过滤使 `AccelExtensionResolver` 对已安装的扩展包返回
  `EXTENSION_MISSING`。现按构建通道注入 `<applicationId>.accel`（QA 变体为
  `…qa.<purpose>.accel`），并补源码契约单测。**真机判定待下一设备轮复验。**
- **发行版标签文字不可见**：主界面七张发行版卡片的标签前景色与其标签底色相同
  （例如 Ubuntu 红底红字、Debian 纸白底纸白字，对比度 1.00:1），现按底色亮度改为
  深墨或白色，最低 5.01:1。
- **墙纸被拉伸变形**：主题墙纸原先用 `Gravity.FILL` 双向拉伸，改为 cover 裁切。
- **图标按钮的朗读噪声**：装饰性发行版图标与动作面板图标不再被 TalkBack 念出无意义英文；
  纯图标按钮保留中文可读名称。
- **实例列表读取失败的错误文案**：原先复用「创建失败」文案并夹带英文原始串，
  现说明真实原因与下一步（重开应用重试、已有数据不删除）。

### Known limitations

- 本版本的全部真机验收项（图标观感、扩展包装机后的探测结果、36 域 distcc 闭环、
  UX 细节截图）都尚未执行，见 `docs/reports/releases/v0.6.0.md` 的移交清单。
- 仓库不含发布签名密钥；未配置签名时 `validateReleaseSigning` 会让发布构建直接失败。
- proot 车道仍只存在于 `-Porchestella.qaTargetSdk=28` 的隔离通道。
- UML 车道的编译角色已被实测否定（2,000 函数夹具上中位 940 ms，同轮 native 460 ms，2.04×），
  仅保留长驻 / 容器方向的研究价值。

## 更早的版本

- `0.5.0`–`0.5.3` 为本地验证版本，未打 tag、未公开分发，逐条记录见
  [版本迭代审计历史](docs/standards/VERSIONING.md#6-版本迭代审计历史)。
- `v0.4.5` 是上一个公开发布的开发预览版，发行说明与安装包保留在
  [Releases](https://github.com/Nyzeep/Orchestella-Studio/releases) 归档中。
