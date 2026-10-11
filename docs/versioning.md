# Orchestella 版本号命名规则（Versioning Policy）

**生效版本**：v0.2.0 起
**标准基线**：[Semantic Versioning 2.0.0](https://semver.org/lang/zh-CN/)（业内事实标准，被 Android 生态、F-Droid 与主流开源项目广泛采用）

---

## 1. 版本号格式

```
MAJOR.MINOR.PATCH[-预发布后缀][+构建元数据]
   │     │      │        │
   │     │      │        └─ 预发布：-preview / -alpha.N / -beta.N / -rc.N
   │     │      └─ 构建元数据：如 +build.42（一般省略，用 versionCode 表达）
   │     └─ MINOR：向后兼容的功能性更新
   └─ MAJOR：不兼容的重大变更（架构级、破坏数据格式的变更）
```

## 2. 递增规则（按更新性质）

| 更新性质 | 递增位 | 示例 |
| --- | --- | --- |
| 修复崩溃 / 缺陷歼灭，无新功能 | **PATCH** (+0.0.1) | v0.2.0 → v0.2.1 |
| 新功能、新发行版支持、UI 大改版（向下兼容） | **MINOR** (+0.1.0) | v0.2.0 → v0.3.0 |
| 执行引擎更换、实例数据格式不兼容、许可变更 | **MAJOR** (+1.0.0) | v0.9.x → v1.0.0 |

- **0.x 阶段**（当前）：产品处于公开预览期，MINOR 允许包含较大的体验改版，MAJOR 留给 1.0 正式发布的稳定性承诺。
- 一轮发布同时含修复与功能时，按**最高性质**递增（含功能 → MINOR）。

## 3. 预发布后缀（业内惯例，按出现顺序）

```
v0.3.0-alpha.1 → v0.3.0-beta.1 → v0.3.0-rc.1 → v0.3.0
```

- `-alpha`：内测，功能未冻结；
- `-beta`：公测，功能冻结、修缺陷为主；
- `-rc`：发布候选，无已知阻塞缺陷；
- 后缀版本比较遵循 SemVer 规则（`-alpha.2 > -alpha.1`，正式版 > 一切预发布）。

## 4. 版本一致性硬约束

发布任何版本前必须逐项核对（CI 或发布检查单执行）：

1. `app/build.gradle.kts` 的 `versionName`（用户可见版本）与 `versionCode`（单调递增整数）已同步递增；
2. **关于页版本严格等于 `versionName`**（实现上只允许从 `BuildConfig.VERSION_NAME` 读取，禁止手写字符串）；
3. GitHub Release tag 形如 `v<versionName>`（如 `v0.2.0`），Release 名称与 tag 一致；
4. APK 发布文件名形如 `Orchestella-Studio-v<versionName>.apk`；
5. `README.md` 的 Release 徽章与下载表指向最新版本；
6. 无任何代码/文档路径残留旧版本号字面量（发版前 `grep` 校验）。

## 5. versionCode 规则

`versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`（0.x 阶段 MAJOR 取 0；预发布后缀不改变 versionCode，仅相同 versionCode 的重复构建才 +1）。

示例：v0.1.5 → code 105（历史遗留，此后按新规则）：v0.2.0 → **200**，v0.2.1 → 201，v1.0.0 → 10000。

## 6. 本轮基线说明

- 本仓库历史版本号（v0.1.0-preview / v0.1.5）早于本政策；自 **v0.2.0** 起严格按本文件执行。
- v0.1.5 → v0.2.0 的定性：网络平面重构（gvproxy）、自愈 provisioning、主题系统等新功能，向下兼容 → **MINOR**。
