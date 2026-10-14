# Phase 5-06 可恢复的真实 guest 桌面与开发 profile 实施报告

- 票号/标题：phase5-06 / 可恢复的真实 guest 桌面与开发 profile。
- 执行者：profiles implementation worker，Codex，new-provider/gpt-6.1-sol；亲自执行，没有进一步派生。
- 日期：2026-10-13。
- 唯一工作树：`D:/Development project(开发项目)/Orchestella开发目录/Orchestella_phase5_profiles`。
- 分支：`phase5/worker-profiles`；基线：`8d0e8c75d769ecc645ea102b90a304c8657344a1`。
- 权威规格：`.scratch/orchestella-phase5/spec.md`、`issues/06-recoverable-guest-profiles.md`，及用户/协调者本轮精确接口指令。
- 本报告描述源码和宿主合成契约验证。Debian/Ubuntu ARM64 guest 安装、systemd 实际 unit、真实桌面、AVF/QEMU 显示、真实 Podman、真实 code-server、Android 均未运行。

## 1. 任务目标与边界

交付原创独立白名单 `profilectl`，用户明确安装后才运行可选桌面、Rootless Podman 或本地开发服务；安装可取消、失败可重试、状态有界，保留基础 Linux 和用户配置。主协调者负责 embedding、guest-agent 控制路由、App、转发与 UI；graphics worker 负责显示 relay。实现不接管这些 existing 文件。

实际读取限定于项目政策/规格、相关 production 接口、指定 research 与公开参考的必要源码。读过 AGENTS、防御目录全部 policy、Host/Artifact/Architecture/Asset/Reporting、总 dispatch、CONTEXT 和相关桥接/网络/安全 ADR。implement/tdd/code-review 工具均返回同名歧义，依用户指令读取项目绝对 SKILL.md；采用已授权 CLI/进程接缝，审查亲自完成。没有执行技能中的 commit 或派生建议，因为用户明确禁止。

本 worker 不联网、不 adb、不复制活动主仓、不改 WSL 用户或系统服务、不安装宿主软件；测试使用已有 WSL `Ubuntu-24.04` 与 `D:/JDK17`。外部输出仅在指定 `Orchestella/artifacts/phase5-parallel-20261013/{desktop,B}/implementation`。代码、配置、状态均针对 Linux guest；合成根须带 sentinel 才能使用 test runner。

## 2. 实际完成

| 验收点 | 实现与证据 | 结论边界 |
| --- | --- | --- |
| 独立白名单与 bounded 状态 | `profilectl` shell 入口、`profilectl.py`；固定四个 profile，JSON 单行含换行最多 4096 bytes，固定 reason，无 shell 命令拼接；附 `--format kv` | 合成 CLI 正/负例已运行；control 由协调者集成 |
| 可恢复后台安装 | 所有长安装/启动/停止/暴露动作 spawn 后立即返回；状态 fsync/rename、command mutex、全实例 job 锁、PID/boot identity、取消、包库存检查、签名与空间门、重试 dpkg audit/configure | Linux 实进程合成测试已运行；真实 apt/systemd 未运行 |
| Debian 13 桌面首通路 | 普通 `orchestella`，labwc headless/pixman，wayvnc Unix socket，foot/waybar/字体/DBus/XWayland；未知组合拒绝 | 仅 Debian 13 ARM64 可选；Ubuntu 桌面不可选；KDE/niri 不进白名单 |
| 真实捕获与 host 帧确认 | 实际 Wayland registry 查询三协议、真实客户端身份、grim PPM 捕获/尺寸/内容门、当前 generation；确认前保持 awaiting-frame | 合成流/故障通过；没有 guest/Surface 实测，不宣称 GPU |
| Rootless Podman | 签名包、UID maps、subuid/subgid 不重叠、普通用户 unshare、实际 seccomp/cgroup/overlay probe；独立 info/run/DNS/TLS/volume/loopback-port 验收 | 成功及失败合成 fixture 已运行；镜像 layers 与容器实跑未运行 |
| Docker CLI | 只 Debian 13 的签名纯 `docker-cli`，不安装 docker.io daemon；CLI 与 daemon 字段独立 | CLI 验证仍 installed，daemon-unverified，不写 ready |
| code-server | official ARM64 tar exact size/SHA、受限 HTTPS/redirect、安全解包、32 bytes 随机密码/private 0600 YAML、普通用户 systemd、loopback | 合成 archive/真实宿主 HTTP listener 已运行；official ARM64 运行未验 |
| 认证与显式 relay | 匿名页面拒绝、POST body 登录、带 cookie header 受保护页面；随机实例 cookie suffix；固定 NIC relay 仅 EXPOSE，UNEXPOSE/STOP 移除监听与 marker | 合成 HTTP 生命周期及 relay `/proc/net/tcp` 证据通过；真实 NIC relay 尚未运行 |
| 报告、输入、patch | 本报告、payload manifest、测试摘要与含 untracked 的 patch | 不 git add/commit/push/merge/reset |

## 3. 精确 CLI 与协调接口

Guest payload canonical 目录：`/usr/local/lib/orchestella`。协调者需安装六个文件：`profilectl`、`profilectl.py`、`bootstrap-python.sh`、`feature-session.py`、`rootless-probe.py`、`inputs.json`。脚本 root-owned、0755，inputs root-owned、0644，不可 group/other writable。`tests/` 不 embedding。基础 rootfs 没有 Python 时 shell 入口显式 INSTALL 会后台签名安装 python3。

| CLI 参数 | 行为 |
| --- | --- |
| `probe PROFILE` | OS/version/ARM64 白名单、预算/包集合、已有实时证据；不把研究升级为 runtime verified；code-server pin 缺失拒绝 |
| `install PROFILE` | 单实例后台安装；ready 基础 state/PID1/services 与 provision lock 释放后才接触 apt |
| `status PROFILE` | bounded 快速读取与服务/boot/auth/relay 事实撤回；读取到 failed 的 status 仍 exit 0，表示查询成功 |
| `start PROFILE` | 后台真实运行探针；重复当前服务启动不 fork 第二份 compositor/service |
| `stop PROFILE` | 后台停止 owned units/listeners，保留包/配置，不停止 VM；active install/start 时先取消 |
| `cancel PROFILE` | 对该 profile 当前 job 请求取消，锁保留到子进程退出/清理结束 |
| `confirm-frame desktop-labwc GENERATION` | GENERATION 为恰好 32 个小写十六进制字符；当前 capture/protocol/boot/generation 成立且服务 active，最后 mutex 内重验后才原子写 ready |
| `resize desktop-labwc WIDTH HEIGHT SCALE` | 每边 1..4096、像素 ≤4,194,304、有限 scale 0.1..5；普通用户 wlr-randr 真实返回值，失败非零；成功新 generation，重验捕获，等待 host 重新确认 |
| `expose code-server` | 后台重验认证与服务；仅 guest 实际有 `192.168.127.15` 时建立该地址 `18080` → `127.0.0.1:8080` 的 relay |
| `unexpose code-server` | 移除 owned relay，核实 listener 已消失，清 marker，保留 loopback editor |

白名单 PROFILE：`desktop-labwc`、`podman`、`code-server`、`docker-cli`。控制面计划 `ORCHESTELLA/FEATURES 1 ACTION ...`，ACTION 大写由协调者映射至上述 CLI。未知动作/profile、格式错误、被拒绝操作、同步失败均 emit bounded JSON 并非零退出；INSTALL/START 的 exit 0 只表示 job 已受理，后续失败必须轮询 STATUS。worker 本身的内部执行参数不得成为 control API。

State 字符串：`planned/installing/installed/starting/awaiting-frame/ready/failed/cancelled`。核心字段：`version=1`、`profile`、`state`、`installed`、`reason`、`phase`、`generation`、`job_id`、`job_active`、`units_version`。无 generation 为字符串 `none`，有效 generation 为 32lowerhex。

Desktop 字段：`capture`/`protocols` 的 verified/unverified、`capture_generation`、真实 `capture_width/capture_height`（另有 width/height alias）、捕获摘要、`renderer=pixman`、`transport=cpu-copy-rfb`、`display_transport=virtio/vsock`。`input={keyboard,unicodeText,pointer}` 为三个 bool。keyboard/pointer 仅当前 generation 捕获和实际 protocol 证据通过时为 true；这表示传输/协议具备入口，尚非键鼠实际改变画面的验收。`unicodeText=false`，所有应用的 Unicode/IME 仍未验证。

Code-server 字段：`health=authenticated-http`、`authenticationConfigured` bool、`service_origin=http://127.0.0.1:8080`、`exposed` bool、`guest_relay/address/port`。凭据、token、原始 private YAML 不在 JSON、URL、argv、日志。密码由用户显式在 guest 终端查看私有配置；本 worker 不新增 credential control 命令。exposed 必须通过 owned relay unit active 与被动 `/proc/net/tcp` 的精确 LISTEN/UID 证据，dead relay 立即撤回；不会用连接探测消耗图形/gvproxy listener。

Java 新包是独立可选消费层：`GuestFeatureStatus.parse(singleLineJson)` 与 `GuestFeatureRequest` 白名单构建、confirm/resize。没有修改 BridgeClient/Gradle；它不建立 reader，不接管鉴权。

## 4. 实现决策与运行路径

普通用户自动创建用 locked password `!` + normal shell，不引入固定密码。真实 UID/GID 从账户取得；拒绝 root/nobody UID、root/nobody primary group 与 supplementary root group。保留已有账户、shell、用户配置；不修改既有密码。root-scheduled systemd services 通过真实 `User=orchestella/Group=<gid>` 启动 compositor/wayvnc/code-server。root display relay 单独保护 token。Runtime 使用 `user-runtime-dir@UID.service`，验证 `/run/user/UID` 和 `orchestella/` 子目录 owner 为普通 UID、0700。

Labwc 0.8.3 真源码使用 `wl_display_add_socket_auto`，因此没有假定 `WAYLAND_DISPLAY=wayland-0` 或固定自定义 socket。labwc `-S` session helper 从实际继承的 `wayland-N` 写 0600 marker；probe/wayvnc 验 marker、socket、owner 后使用真实 socket。固定 VNC socket 是 `/run/user/UID/orchestella/wayvnc.sock`，umask077。

Relay 固定 `/usr/local/lib/orchestella/display-relay.py --user orchestella --transport virtio`；token 实际 init 路径核对为 `/etc/droidlayer/bridge.token`，root0600；固定 device `/dev/virtio-ports/orchestella.display` 允许解析到 `/dev/vportNpM`，核实 root-owned char device/sysfs name，仅该设备 chmod0600，生成 exact ATTR{name} owned udev rule。不是遍历 chmod 所有 vport。

AVF adapter 仅精确 `/proc/cmdline` marker `droidlayer.vsock_control_port=9100` 选择 `--transport vsock`；实际 AF_VSOCK create/bind/listen9103 成功才继续，否则 display-transport-unsupported。不根据 CPU/Android 版本授予能力。两条真实 guest 通路均未运行。

Display relay unit 必须 `Restart=always/RestartSec=2` 以支持正常 EOF 后再次连接；其余服务 `Restart=no`。所有服务没有 Install/enable，不开机默认启用桌面或 NIC relay；systemctl stop 会阻止其自动重启。wayvnc 0.9.1 resizing 默认由 CLI 内部打开，没有把内部 `enable_resizing` 当未知配置键；`--max-fps 15` 是资源上限，非测得 FPS。

wayvnc 的 placeholder 首帧不足以 ready。当前 client PID/ordinary UID/executable、Wayland globals 和真实 grim PPM 成功并有非纯色内容后才 capture verified。host 必须在当前尺寸/generation 的真实帧呈现后显式 confirm。确认最终在 mutex 内重验，STOP/CANCEL/RESIZE 插入不能被旧检查覆盖成 ready。重启 boot identity 变化会撤回旧证明。捕获文件不长期保留桌面内容。

签名 apt 不关闭验证，拒 trusted=yes 等 source bypass，关闭下载自动 retry，有限期限/apt lock timeout。失败保留已有包/配置，重试前做 dpkg audit/configure；不删除系统包锁或根盘。取消 package 子进程可能留下需要 configure 的事务状态，恢复检查不会承诺完整回滚。Bootstrap 的 TERM/KILL/reap 和锁发布必须实际完成；发现无法确认 descendant 退出则保留锁而拒绝重入。

code-server 解包与发布全过程由 try/finally 收口，仅清理本次 job 创建的精确 `/opt/orchestella/code-server/.staging-<32hexjob>`。POSIX 固定父目录使用 `O_DIRECTORY|O_NOFOLLOW` descriptor，核实待清目录 type/dev/inode 与本次 mkdir 的身份相同，用防 symlink 攻击的 FD 锚定 rmtree；没有 root glob、没有枚举清理其他 job。已验证 archive cache、原有安装目标、用户配置不属于清理范围。成功 rename 后安装目标已移出 staging，finally 只删除剩余空 staging；tar/I/O/ENOSPC/取消/marker/rename 失败则删除本次未发布树。tar data filter 每个 member/file/link 解包前检查取消，权限整理和发布前也检查，取消不等全部文件解包结束。旧版本或强制终止留下的其他 job 目录不会被本轮自动删除。

## 5. 固定输入与包版本

研究 canonical 位于协调工作树 `docs/research/2026-10-13-phase5-{desktop-matrix,productivity}.md`。输入从研究/协调者已核结论取得，没有 worker 重复联网大调研。

- Desktop Debian 13 ARM64：labwc ≥0.8.3、wayvnc ≥0.9.1、wlr-randr ≥0.4.1，signed distro solver；Debian 没有 sfwbar，使用其 signed waybar。字体/DBus/foot/XWayland/grim/wayland-utils 与 python3 明确依赖。不因包命令 exit0 就算安装完，查询每个包实际 status/version。
- Docker CLI Debian 13：signed `docker-cli`，版本下限26.1.5；研究版本 `26.1.5+dfsg1-9+deb13u1`，ARM64 deb 官方 SHA `d9dbab1a7cae040aa617ab509067b4d070dc1dde190851e5b629150705da55fd`。Ubuntu 不提供本轮可选纯CLI组合。
- code-server official 4.141.0、tag revision `8b41915d25547e2edd553bab03ea4cbd621f98c5`、MIT、glibc平台。URL `https://github.com/coder/code-server/releases/download/v4.141.0/code-server-4.141.0-linux-arm64.tar.gz`，size **234587826**，SHA256 **e5cc78da1d631c6cf6162f9b18eeaa8ee9d3239e1a88bb99d0602cdc5fa1852d**。协调者已下载重算，证据 `B/evidence/code-server-arm64-fixed-input.json`。本 installer 下载后仍 exact size/SHA gate，未知/动态 URL拒绝，tar path/link/device/bytes/count 限额；不执行外部 install.sh。
- Podman hello：`quay.io/podman/hello@sha256:5c44ef36dc5e35a76904da0e028cf9413e0176a653525162368af13fed03571c`。
- Podman shell/DNS/TLS/volume/port：`docker.io/library/alpine@sha256:24bb3511a0db7b5114a4aee033c65a8a4148f39b7b80a398e548546db967a36f`（研究3.22.2 ARM64/v8）。OCI manifest 摘要已核；layers/podman运行未核。明确 slirp4netns，不把缺 NFT 内核的默认 netavark bridge 当可用。不使用 latest。

`inputs.json` schema=1，最终 **units_version=2**，包含 desktop minimum_versions、code_server version/arch/url/size/sha/tag_revision 与 podman_acceptance digests。这里升级的是 owned systemd unit 模板版本，code-server 4.141.0 的 URL/size/SHA、Podman digests 均不改变。协调者可升级受管 units_version/pins；不覆盖用户配置，不把未 pin 组合显示为可选择。

已安装实例维护必须由用户显式再次执行 `profilectl install code-server`（control 对应 `FEATURES 1 INSTALL code-server`）。旧 state 的 units_version=1 与最终 inputs 的版本2不一致，会进入后台升级，停止 owned 服务、更新 owned unit、daemon-reload 后记录 installed/units_version=2；不会沿用同版本 INSTALL 的提前返回。旧模板的 NoNewPrivileges=yes 被移除，私有 YAML/密码、cookie suffix 和已安装 code-server 输入保持字节不变，升级不会自动 START 或 EXPOSE。版本2的重复 INSTALL 仍幂等。外部 CLI legacy fixture 已验证此1→2路径；真实 guest 旧实例维护未运行。

## 6. 测试、真实结果及失败保全

测试接缝：外部 CLI argv/stdout/exit、后台真实进程、synthetic package/service runner、filesystem发布故障、实际宿主 loopback HTTP listener。fixture 专属 synthetic root；这些结果不表示 guest 包管理、Wayland 或容器已经运行。

| 验证 | 实际结果 | canonical evidence |
| --- | --- | --- |
| 初始 whitelist red→green | 未实现时失败，实现后通过 | `B/implementation/red-cli`、`green-cli` |
| Linux早期整套 | 12 tests，0 failures、0 errors | `B/implementation/linux-contracts/profile-contracts.txt` |
| Linux新增rootless/archive/auth/restart | 5 tests全部通过 | `B/implementation/linux-review-gates/profile-contracts.txt` |
| ENOSPC publish与普通组门 | 3 tests全部通过（ENOSPC含3子案例） | `B/implementation/linux-enospc-identity/profile-contracts.txt` |
| POSIX ignoreTERM bootstrap取消 | 实 `/bin/sh` worker + 忽略TERM子进程；修 external `/bin/kill` 后1 test通过 | `B/implementation/linux-bootstrap-cancel-fixed/profile-contracts.txt` |
| 扩展Linux全量 | **25 tests，24通过，1失败，0 errors，242.667s** | `B/implementation/final-linux-contracts/profile-contracts.txt` |
| 全量唯一失败修正后定点 | 1 test通过；错误码断言改接受真实安全拒绝，保留非零/非ready/最终installed/inputfalse | `B/implementation/final-targets/profile-contracts.txt` |
| 最终desktop最小差异 | 2 tests全部通过，含capture/currentgeneration与STOP竞争 | `desktop/implementation/final-desktop/profile-contracts.txt` |
| IDE主unit的uidmap继承策略 | 1 test通过；不阻断rootless setuidhelpers，仍普通用户/私有配置 | `B/implementation/final-editor-unit/profile-contracts.txt` |
| 最终units_version 1→2维护升级 red→green | 升级前新回归1项实际失败：INSTALL提前返回installed；升到2后3项定点通过：旧IDE unit移除NNP、私有配置/凭据/cookie/输入保全、桌面owned升级/foreign拒绝、重复INSTALL幂等 | `B/implementation/unit-upgrade-red/profile-contracts.txt`、`unit-upgrade-green/profile-contracts.txt` |
| Staging失败清理 red→green | 新负例先实际2项失败：cancel/EIO残留job目录；修复后cancel、EIO、ENOSPC、tarerror、marker失败、rename失败6项全部通过（72.663s），旧目标/配置逐字节保全、cache保持、同pin重试成功 | `B/implementation/staging-cleanup-red/profile-contracts.txt`、`staging-cleanup-green/profile-contracts.txt` |
| Staging改动关联回归 | 4项全部通过：输入pin/hash门、archive/endpoint拒绝、unit1→2显式维护、ENOSPC原子state/lock/spawn恢复 | `B/implementation/staging-cleanup-regressions/profile-contracts.txt` |
| Java17编译/公共模型契约 | `-Xlint:all -Werror` 编译通过，GuestFeatureContractTest通过 | `B/implementation/final-java-contracts/{compile,test}.txt` |
| 最终语法 | 8 Python AST通过；既有WSL `/bin/sh -n` 两shell均exit0 | `B/implementation/syntax-linux-summary.json` |

没有把修复后的定点结果写成最终25项全量全通过；没有再运行耗时整套，因为协调者已要求按实际 full24/25 + target通过交付。后续最小差异分别验证：wayvnc 配置/15FPS 上限的 desktop 定点/语法、IDE主unit NNP策略、unit模板版本1→2的三个维护升级定点，及最终 staging 清理的6个故障与4个关联回归。最终 staging 增量仅修改 profilectl.py、两个已有owned测试文件和本报告；units_version=2及输入pin不变。没有重跑242秒全量。

全量唯一失败：产品在0.6sec探针deadline内安全拒绝，返回 command-timeout，不是测试最初只允许的 stale-frame；exit非零且未ready本已成立。随后 fixture STOP 请求独立进程组，避免跟随探针deadline一起被终止；fixture轮询45sec覆盖真实startup30sec及包查询开销，产品deadline未改。最终定点要求非零、非ready、最终installed、inputfalse，没有放宽安全门。

早期bootstrap回归实际发现dash builtin `kill --` 不兼容，忽略TERM child继续活到timeout；修为基础 signed `/bin/kill`，TERM最多2sec→KILL→wait/reap再释放锁。失败 `linux-concurrency-gates` 输出原样保留。初次Windows直接bash invocation因Unicode/路径而失败，记录 `bootstrap/profile-contracts.txt`、`syntax-summary.json`；随后用Windows Python argv array调用WSL真实Linux，无MSYS路径转换，结果另存，未覆盖失败证据。

其他已验证外部行为：签名失败重试；缺包/磁盘满不 installed；单job/重复/取消；existing config保留/foreign unit拒绝；rootful info拒绝；info成功run失败仍不可ready；DNS/TLS/只读volume/实际HTTP端口probe分层；缺input/capture拒绝；旧frame拒绝；deadrelay撤回；匿名端口alive但认证失败不可ready；未知URL/archive traversal/corruptstate拒绝；弱/周期密码拒绝且原配置保留；boot change不复用ready；AVF缺实际transport拒绝；ENOSPC owner失败不永久busy、spawn失败回收worker。Staging故障测试使用真实tar文件与外部CLI后台进程，在tar/文件系统边界注入错误；既有synthetic4.141.0安装每个文件逐字节快照，新synthetic4.141.1失败后逐字节对照、验证缓存size/SHA和其他job目录不变，再通过显式INSTALL同pin缓存重试。新synthetic版本仅测试输入，不改变生产official pin，不是guest签名包/真实code-server验收。

## 7. 修改文件与 owned 自查

只新增：

- `guest-assets/features/` 的六payload文件及四个synthetic fixture文件。
- `guest-assets/scripts/test-phase5-features.py`。
- `runtime/instance/src/main/java/io/github/nyzeep/orchestella/runtime/instance/features/{GuestFeatureRequest,GuestFeatureStatus}.java`。
- 对应新features test包的 `GuestFeatureContractTest.java`。
- 本 `docs/reports/tickets/phase5-06/implementation-report.zh-CN.md`。

全部15文件以外部 `profiles-manifest.json` 的精确 size/SHA 为准。本票没有 existing tracked diff，没有 App/BridgeClient/guest-agent/init/Gradle/ADR/tracker/policy 写入。未 git add，因此源码均保持 untracked；patch 通过逐 owned 文件 `git diff --no-index /dev/null` 包含全部新文件。

## 8. 亲自审查与已修问题

规范轴：文件/测试/报告路径、默认拒绝出站、rootless身份、私有runtime、凭据、输入锁与有限输出。源码为原创，没有从GPL参考复制。限制与未验状态明确，无性能假数。

Spec轴：修复动态Wayland socket假设；display正常EOF需always restart；confirm服务探针到原子ready的STOP竞争；stale relay exposed；weak64hex密码；dash cancel/reap；ENOSPC owner原子发布遗留锁/无owner worker；普通用户root group；解包/marker/rename失败或取消后的整树staging残留，补逐file取消与本job finally精确清理；移除仅code-server主unit的NoNewPrivileges=yes，避免IDE终端继承后阻断Rootless Podman需要的setuid newuidmap/newgidmap，NIC relay仍可NNP；最终inputs.units_version升到2，确保已安装unit1的显式INSTALL维护会更新该修复，不因同版本早返回遗留旧NNP。每项有外部负例或固定上游接口依据。报告不把来自协调者的独立review复核等同本worker真实guest验收。

## 9. 未完成、风险与后续动作

1. **真实guest全部NOT RUN**：协调者需要在Debian13 ARM64运行签名install、真实unit/runtime/Wayland globals、foot/waybar绘制、grim、relay/socket/token握手与Surface，确认currentframe后再验输入/resize/重连/冷启。此票不能宣布设备完成。
2. Ubuntu桌面、KDE/niri、其余发行版关闭；只有研究/预算，没有包交易/renderer/frame证据。Podman/code-server限Debian13/Ubuntu24.04 ARM64；Ubuntu包closure仍需真实apt检验。
3. Podman seccomp/cgroup delegated/overlay probe fail-closed，可能拒绝kernel/用户manager缺能力。镜像下载、公网example.com DNS/TLS与指定port probe失败分别拒绝；不做默认bridge fallback。STOP不停止用户容器，只清profile验收临时容器/units。
4. 有限取消会使包数据库需configure恢复；不承诺未完成包事务完全回滚。生产apt取消/断电恢复需要guest测试。测试使用fake package boundary，未证明真实dpkg故障可完全恢复。从真实code-server终端启动Podman的继承上下文也尚未运行，unit策略测试只能证明没有主动设置NNP阻断。
5. grim nonuniform capture+真实clients仍不是host RFB pixels内容一一对应的证明；host需自己fresh frame/currentgeneration/dimensions/present门。Unicode/IME false；ASCII键鼠实际注入仍待设备验证。capture摘要不能替代画面观测。
6. 已有用户配置严格保留，弱/不兼容配置可能阻止启动，用户需自行修复后显式重试。cookie suffix/密码不会跟随retry自动改写。
7. EXPOSE只是guest NIC监听；host gvproxy回环规则、LAN10min租约、WebView origin/cookie清理归协调者。本worker不保证LAN/手机访问，不默认LAN，不默认开机enable。
8. 真实signed artifact的license/NOTICE/source obligations由协调者在embedding/release处理；guest可选安装签名包不等于把包binary拷进APK。Java为独立可选adapter，Gradle现有check未因本票注册此main test；本票实际独立javac运行，协调者可在集成时接入gate。
9. 独立review仍可继续检查产品端；当前建议源码ready-for-review/待集成和真实guest验收，不能作为整套phase5 completed。

## 10. 外部证据与清理纪律

外部根 `D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013`，owner=profiles-worker，交付后协调者接管。

- `B/implementation/profiles.patch`：本地patch，含untracked。
- `B/implementation/profiles-manifest.json`：15个owned文件与6个payload size/SHA/目标模式。
- `B/implementation/test-summary.json`：按真实运行分层，记录失败与定点通过，不含运行密码/token。
- `B/implementation/staging-cleanup-increment.json` 和 `.patch`：最后四文件的准确before/after size/SHA与可应用增量；`staging-cleanup-predecessor`保留前一交付文件，不覆盖失败证据。
- `desktop/implementation` 与 `B/implementation`：case evidence、synthetic roots、Java class output；证据正文孪生存preserved。

源代码/报告属正式交付；patch/manifest/验证文本属Class B保留；synthetic roots/编译class为本票Class C，不在本轮清理。Class A没有删除、改名、移动或写入。新证据不用 `.log`。工具自身输出路径由Host管理，canonical证据均为D盘指定根，不把其自动路径视为worker新增宿主授权。

## 11. Commit、branch 与架构门

分支 `phase5/worker-profiles`，基线8d0e8c75。**无commit、无git add、无push/merge/reset、无远端写入**，遵从用户本轮指令。所有source留可审查未提交状态。

没有修改accepted ADR、spec或exposure whitelist；按用户明确CPU fallback和协调规格实现已授权有鉴权的独立数据面。正式RFB/GPU/USB相关ADR事实冲突与ACR由主协调者持有，本worker不自行关闭该门。没有host文件传输、静默tunnel、默认LAN或GPU宣称。Worker owned边界没有越界。

## 12. Ticket 状态建议

建议 **ready-for-review，等待embedding/控制面/App集成与真实guest验收**。CLI与原创运行路径、输入pin、Java可选模型、合成测试、报告和patch已交付。没有设备证据的验收项保持未运行；不会据此改tracker为completed。

报告人：Codex / profiles-worker / new-provider/gpt-6.1-sol ｜ 日期：2026-10-13 ｜ 耗时：未统计完整会话；最终全量测试242.667s为实际输出
