# 55: Guest 极简现代 Shell 预设（Starship / LS_COLORS）与环境美化

Type: implementation  
Status: ready-for-review
Labels: m4-spec, tracer-bullet, guest-assets, shell-beautification  

## User Story

作为注重终端美感与信息清晰度的命令行开发者，我希望在进入 Linux Guest 操作系统时，默认就能享受到具有现代感的多彩命令行提示符（类似 Starship 风格）、清晰的文件高亮配色（LS_COLORS）以及实用的常用别名，使目录层级、执行结果和文件类型一目了然，无需每次创建新实例都要手动配置 `.bashrc` 或 `.profile`（US-26）。

## What to build

在 `guest-assets` 模块中构建免额外二进制依赖的轻量现代 Shell 预设，并在发行版首次引导初始化时自动装配，遵循 ADR-0005、ADR-0006 与 spec-m4.md：

1. **极简 Starship 风格通用提示符脚本（`guest-assets/shell/droidlayer-prompt.sh`）**：
   - 纯 POSIX `sh` / `ash` / `bash` 兼容设计，零外部重型依赖，执行耗时 $< 2\text{ms}$；
   - 双行优雅提示符结构：
     * 第一行：当前路径（青色 `\e[36m`）、Git 分支指示（若在 Git 仓库内，黄色 `\e[33m`）、上个命令退出码状态（失败时红色 `\e[31m✘`）；
     * 第二行：紧凑箭头提示符 `❯ `（普通用户绿色，root 用户洋红）；
2. **标准化色彩高亮体系（`LS_COLORS` 预设）**：
   - 预设遵循现代规范的 `LS_COLORS` 环境变量字符串：
     * 目录：亮蓝色（`di=01;34`）；
     * 可执行文件：亮绿色（`ex=01;32`）；
     * 压缩归档文件（tar, gz, zip, zst）：明黄色/红色（`*.tar=01;31`）；
     * 软链接：青色（`ln=01;36`）；
     * 源码文件（c, rs, py, go, java, js）：差异化着色；
3. **高频常用别名集合（`guest-assets/shell/aliases.sh`）**：
   - 预设标准化命令别名：
     * `alias ll='ls -lah --color=auto'`；
     * `alias la='ls -A --color=auto'`；
     * `alias l='ls -CF --color=auto'`；
     * `alias grep='grep --color=auto'`；
     * `alias df='df -h'`；
     * `alias free='free -m'`；
4. **发行版自动化装配注入（`guest-assets/scripts/` 升级）**：
   - 在 `droidlayer-desktop-setup.sh` 及基础初始化脚本中，自动将上述预设写入 `/etc/profile.d/00-droidlayer-env.sh`；
   - 确保 Alpine Linux（BusyBox ash）与 Debian Linux（bash）登录时均能自动加载。

**Blocked by:** 54

## Seam Design

- **环境脚本隔离**：所有美化脚本作为独立 `.sh` 资产打包，位于 `guest-assets/shell/`，由 JVM 测试套件打包为资源文件；
- **平台无感注入**：采用 `/etc/profile.d/` 标准 Linux 机制，无需硬编码侵入特定用户的私有 `~/.bashrc` 文件，保证多用户一致性。

## Acceptance Criteria

- [x] `droidlayer-prompt.sh` 在 BusyBox `ash`、Debian `dash` 与 `bash` 环境下均可无错误执行，提示符呈现两行结构且色彩准确；
- [x] `LS_COLORS` 环境变量生效，执行 `ll` 时目录、可执行文件与归档文件呈现明显色差区分；
- [x] 脚本通过 ShellCheck 静态分析，零 Warning、零 Error；
- [x] 纯 JVM 测试（`GuestAssetsShellScriptTest`）验证资产完整性、权限属性与安装逻辑 100% 绿灯。

## Device Verification Steps

在 Android 真机或 QEMU 环境中进入 Linux shell 进行验证：
```bash
# 1. 启动实例终端，检查提示符格式
# 观察是否为现代双行提示符：路径 + ❯
echo $PS1

# 2. 验证别名与颜色
ll /
# 观察各系统目录是否为亮蓝色，各配置文件是否清晰着色

# 3. 验证命令执行失败时的提示符退出码变化
false
# 观察提示符是否出现红色 ✘ 状态标记
```

## Test Strategy

- 本地与 CI Shell 静态测试：
  - 执行 `shellcheck guest-assets/scripts/droidlayer-prompt.sh guest-assets/scripts/aliases.sh`；
- JVM 单元测试：
  - `GuestShellAssetsPackagingTest`：断言 Shell 资产被正确打包进 APK assets 并包含正确的 Unix 换行符（LF）；
- 运行 `./gradlew :guest-assets:test`。

## Prototype/research dependency

依赖 Ticket 54（现代终端渲染器支持 ANSI 颜色）与 spec-m4.md 第 3.5 节规格。
