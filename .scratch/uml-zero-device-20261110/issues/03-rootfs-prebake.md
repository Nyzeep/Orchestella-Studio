# 03: Rootfs 预烘焙与 build-time provisioning — 首启零安装

Type: implementation
Status: resolved（2026-11-10，u2-rootfs-prebake）
Priority: P0（压缩“下载完还要等很久”的最大杠杆）
Owner: u2-rootfs-prebake
Blocked by: None（与 01/02 并行；首启计时对齐 01 的 T2/T3）
External gates: 真机首启 `not-tested`；16K 宿主 `not-tested`

## 背景

当前产品首启会做镜像下载、rootfs 装配、包安装、locale、证书、服务 enablement 等；T7 的成功样本里 92 包事务本身可到 <1800 s（`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F）。用户感知的“等很久”主要来自这些首启步骤，而不是 UML 内核 boot。

proot-distro 的“下载完就秒开”并不执行这些步骤：它只解包 rootfs 然后 exec 用户态进程（外部一手来源见研究输入）。UML 方向不应复制 proot 的无内核模型，但可以把 provisioning 前移到构建期，让首启只做挂载 + init + exec。本票做这个预烘焙原型。

## 必须做

1. 新增 `research/uml-rootfs-prebake/`：
   - `build-rootfs.sh`：支持 Alpine 与一个 glibc 发行版（Ubuntu/Debian 二选一）；用跨架构 chroot/qemu-user 或发行版 rootfs 工具构建；
   - `manifest.schema.json` + `manifest.json`：distro、version、包清单、locale、证书、构建时间、输入 sha256、产物 sha256；
   - `init-zero-install`：只挂载 `/proc`、`/sys`、`/dev`、`/dev/pts`、cgroup2，执行 `/ready.sh` 输出 `READY`；**不调用包管理器、不联网**；
   - `verify.sh`：检查 rootfs 内没有待安装包、没有 provisioning 标记、manifest 与 sha 一致。
2. 两组对照：
   - 当前 provisioning 流程（已有脚本/报告）vs 预烘焙 rootfs 的首启 T2/T3、产物体积、构建时间；
   - 网络关闭时首启能否到 `READY`。
3. 报告：`docs/research/2026-11-XX-uml-rootfs-prebake.zh-CN.md`，含包清单、体积、构建时间、两次构建 sha 对比、许可证/NOTICE 边界。

## 边界

- 不写产品代码、不改产品 provisioning 脚本；研究目录独立。
- 不把预烘焙 rootfs 说成“产品可分发物”；许可证/NOTICE 与对应源码义务另行审查。
- 不承诺所有发行版都可预烘焙；先做 2 个，其余标 `not-tested`。
- 不把“首启零安装”与“系统更新”混为一谈：更新策略另立票。

## 完成判据

- 同一输入两次构建 sha256 一致，或明确列出非确定项及原因；
- 断网首启到 `READY`，日志无 package manager 调用；
- manifest 与产物一致；`verify.sh` 通过；
- 体积/构建时间/包清单入库；敏感数据门通过；
- 报告明确与 proot 的差异（proot 无内核；UML 有内核但首启零安装）。

## 风险与停止条件

- **跨架构装配失败**：记录失败点，退回发行版官方 rootfs + 构建期离线安装的可行路径。
- **包管理器非确定性**：记录时间戳/仓库快照差异；不宣称可复现，直到两次 sha 一致。
- **许可证/NOTICE**：GPL/第三方包义务未审查前，不进入产品打包。
- 需要 Root/unlock/改 SELinux 才能继续 → 停止并记录。

## Comments

- 2026-11-10 / 主会话（ask-matt 研究轮）：建票。输入：`.scratch/uml-zero-device-20261110/spec.md` §3 U2、`docs/research/2026-11-10-proot-vs-uml-zero-device-direction.zh-CN.md`、`docs/research/2026-11-08-uml-fabric-device-round-2.zh-CN.md` §T7-F。
- 2026-11-10 / u2-rootfs-prebake：认领；base 62d96e99；worktree /workspace-wt/u2。
- 2026-11-10 / u2-rootfs-prebake：**resolved**。交付 `research/uml-rootfs-prebake/`：`build-rootfs.sh`（Alpine + Debian）、`manifest.schema.json`/`make-manifest.py`、`init-zero-install`、`ready.sh`、`verify.sh`、4 份样例 manifest；报告 `docs/research/2026-11-10-uml-rootfs-prebake.zh-CN.md`。
  - 复现：Alpine 3.22.8 aarch64 111 包 / tar.gz 110,170,511 B / 两次 sha `149b2041…bab59` 一致（58–87 s）；Debian trixie aarch64 213 包 / 228,692,824 B / 两次 sha `a27c4353…792e` 一致（346–439 s，含下载）；Alpine x86_64 tar.gz `90ac674f…72c4`、ext4 `2d569b36…845f` 两次一致。
  - 断网首启：x86_64 UML 参考 hostfs 与 ext4 均到 `T2_MOUNTS_READY`→`T3_READY`→`READY`；三次 n=5 复跑 T3 宿主中位 132–201 ms（Alpine）/133–281 ms（Debian），guest 0.10–0.19 s；boot 日志无包管理器、无 eth0/DHCP；init/ready 无网络或包管理器调用。
  - 校验：`verify.sh` Alpine 20 / Debian 23 项全 PASS（含整树 tree digest、apt/dpkg lock 缺失、manifest/产物 sha）；manifest 过 JSON Schema。
  - 许可证：Alpine 111/111 有 `L:`；Debian 213/213 doc 条目可解析到 copyright；GPL 对应源码/NOTICE/Android APK 分发边界未审查，**不进入产品打包**。
  - not-tested：ARM64 真机首启、16K 宿主、Debian aarch64 内核首启（x86_64 glibc 参考已 boot）、ARM64 ext4 boot。停止条件未触发。
- 2026-11-10 / wave1-integration：**保留 resolved（复核通过，带边界）**。独立重建 Alpine 3.22.8 x86_64：64 s、111 包、114,590,691 B，tar.gz sha `90ac674f6148ed4b99f7e832c426826e7129ae286312e73bec54fd8baeb372c4`，与报告声明逐字节一致；`verify.sh` **20/20 PASS**；manifest JSON 合法（`jsonschema` 未安装，只做标准库结构检查）；首启脚本无包管理器/网络调用，rootfs 无 apk 缓存/lock/日志；产品路径干净、敏感门 OK；无构建产物入库；合并提交 `1968704c`。
  - 跨测：U1 x86_64 fast kernel + 本票 Alpine x86_64 rootfs 到达 `T2_MOUNTS_READY` → `T3_READY` → `READY`，日志无 `apk`/`apt`/`dpkg`/`pacman`、无 `eth0`/DHCP（n=1，宿主 `seccomp=auto`；不是 ARM64 结论）。
  - 未独立复跑：aarch64/Debian 构建、Debian `verify.sh` 23 项、ext4、ARM64 真机首启；上述边界保持 `not-tested`。GPL/NOTICE/Android APK 分发边界未审查，不进入产品打包。
