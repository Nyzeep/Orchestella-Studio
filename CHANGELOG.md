# Changelog

本文件记录用户可感知的变更，格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning 2.0.0](https://semver.org/lang/zh-CN/)，
`versionCode` 映射见 [版本标准](docs/standards/VERSIONING.md)。

只写已经进入源码的变更；标注为「未验证」的条目在真机复核前不得当作已交付能力对外表述。

## [Unreleased]

## [0.6.0] — 2026-10-29（稳定版准备，未打 tag）

稳定版里程碑：把发布形态收敛为双包，把四条执行车道的定位与数字口径写成可核对形态，
并修掉一批真机上能直接看见的界面缺陷。本版本**尚未**在设备上完成验收，也没有 tag 与公开安装包。

### Added

- **双包发布形态**：主包只保留应用本体与 QEMU 完整内核路径；pVM 的 payload、编译器引擎与
  `PVM-COMPILER-MANIFEST` 移入独立扩展包 `io.github.nyzeep.orchestella.accel`。
  扩展包 `versionCode` 必须与主包一致，不一致时运行期直接拒绝并说明原因，不做尽力加载。
- **应用图标**：自适应图标（前景 / 背景 / monochrome 主题图标）+ 五档密度 mipmap +
  传统方形与圆形图标；品牌静图与可重复生成的脚本见 `docs/assets/branding/`、`tools/branding/`。
- **36 域 QEMU 车道**：`targetSdk 36` 下 QEMU 与 gvproxy 以 `nativeLibraryDir` 绝对路径 exec
  成功（执行类 AVC = 0），guest 可完成 systemd 启动并给出 root shell。基于会话 22 的设备实测。
- `CHANGELOG.md` 与发布签名向导（`docs/standards/RELEASE-SIGNING.md`）。

### Changed

- **版本号 0.5.3 → 0.6.0（versionCode 503 → 600）**，主包与扩展包同步锁定。
- **背景图语义统一**：终端背景、墙纸与所有位图背景收敛到单一 `CoverCropDrawable`
  （等比缩放铺满 + 居中裁切，绝不非等比拉伸），几何计算抽成纯函数并由单测钉死。
- **桌面虚拟键条**：ESC / TAB / CTRL / ALT / SHIFT 等按键的触控高度 44dp → 48dp。
- **通知渠道**：名称与描述改为中文，且每次进程启动刷新，老安装也能拿到新文案
  （用户自定义的重要性等级不受影响）。
- **端口转发表单文案**：半角括号与中英混排标点统一为全角。
- README 重写为中英双语，补四车道能力矩阵、安装指南、Roadmap 与许可证致谢。

### Fixed

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
