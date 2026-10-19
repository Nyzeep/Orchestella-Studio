# 敏感开发者数据与真机证据硬约束

> 状态：项目级 active policy ｜ 版本：1.0 ｜ 生效日期：2026-10-19（经用户授权）
> 适用：所有 Orchestrator / Worker / Child / review / research session、所有提交、CI、
> 报告、证据目录、Release、PR/issue 文本与 Actions artifact/log

## 1. 目的与事件背景

2026-10-19 的公开仓库清理事件确认：两台真机的原始序列号及其 SHA-256 前缀、24 张真机
截图曾进入公开 Git 历史。仅在后续提交中删除当前文件**不能**从远端移除；旧 commit、
tag、commit message、PR ref 和搜索缓存仍可访问。本文不复述具体序列号或截图内容，只固化
以后必须遵守的规则。

本 policy 是项目级硬约束，与 `DATA_EGRESS_AND_CONTENT_BOUNDARY_POLICY.md`、
`INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md` 和 `ARTIFACT_ORGANIZATION_POLICY.md`
叠加适用。任何 ticket、报告、工具默认值、模型建议或“为了排查方便”都不能单独覆盖它；
发生冲突时停在冲突处、保留最小脱敏证据并升级。

## 2. 数据分类

### 2.1 永不进入 Git、远端或公开文本（无例外）

- **设备唯一标识**：序列号（S/N）、IMEI/MEID、IMSI/ICCID、Android ID、广告 ID、
  MAC/蓝牙地址、Wi-Fi BSSID、设备指纹/ROM build fingerprint，以及它们的截断、前缀、
  hash、base64 或其他可反推形式。即使已经哈希，也按原文处理。
- **真机证据**：真机截图、录屏、通知栏/状态栏/锁屏/设置/关于手机/开发者选项/USB 调试
  弹窗、Window dump、ADB 命令输出、logcat/dumpsys、安装器页面、完整设备日志、设备
  状态导出和可还原上述内容的 OCR 文本。
- **凭据与签名**：keystore/`.jks`/`.p12`/`.pfx`/`.pem`/`.key`、store/key password、
  token、API key、私钥、`.env`、`local.properties`、CI secret、签名材料和 credential
  cache。
- **个人与主机标识**：真实姓名、个人邮箱/电话、住址、账号 ID、真实局域网或公网 IP、
  Wi-Fi SSID、主机名、Windows/macOS 用户名、本机绝对路径、内部 host/仓库地址。
- **用户数据**：用户实例根盘、实例元数据、用户文件、真实用户日志；已有明确产品 Export
  决策的本地单向导出不受本条影响，但导出内容不得进入仓库。
- **衍生物**：任何包含或可反推上述内容的压缩包、归档、截图、报告表格、commit message、
  tag message、PR/issue 正文或评论、Release note、CI 日志和 artifact。

### 2.2 允许的脱敏表达

- 设备使用稳定别名：`device-a`、`device-b`、`device-c`……；别名与真实设备的映射只保存在
  approved external resource root，不进入仓库。映射丢失时启用新别名，不要重新识别设备。
- 可以记录粗粒度环境：Android API、ABI、应用版本/commit、测试结果、是否物理设备。
  机型名只在已经公开且确有必要时使用；优先使用通用描述。
- 虚拟网络设计常量（如 QEMU/gvproxy 的 `10.0.2.15`、`192.168.127.15`）可以保留；
  真实宿主 LAN/公网 IP、MAC、SSID 不可以。
- 合成 fixture、模拟器日志和构造数据可以入库；真实设备日志不得入库，仓库只保留脱敏摘要。
- 应用实例 UUID 只在合成数据或确认与真实用户/设备无关联时使用；公开报告优先使用别名。
- 证据文件的 SHA-256 可用于 provenance，但不得提交设备标识的 hash/前缀，也不得让 hash
  与别名组合后重新识别设备。
- **真机截图没有例外**。需要 UI 证据时，使用合成数据、模拟器/mock 画面或本地演示；
  不得提交任何来自真实设备的截图/录屏。合成画面也必须检查不含真实设备、账号、通知和
  网络信息。

### 2.3 提交与发布文本

commit message、tag message、PR/issue 正文与评论、Release note、CI 日志和 artifact 与
代码文件适用同一套规则。reviewer 必须检查这些文本，而不是只看工作树。

## 3. 强制门

### 3.1 每个提交前

1. 查看 `git status` 和 `git diff --cached`，逐文件确认没有设备证据或凭据。
2. 对暂存内容执行以下检查（任一命中即停止并脱敏）：

   ```bash
   # 值扫描：只扫本次暂存差异；标签后必须出现 >=8 位值；device-a/device-b 等稳定别名不算命中
   PAT='(Serial|序列号|设备[ _-]?(ID|编号)|IMEI|MEID)[[:space:]]*[:：=]?[[:space:]]*`?[A-Za-z0-9][A-Za-z0-9_.:-]{7,}|adb[[:space:]]+-s[[:space:]]+[A-Za-z0-9][A-Za-z0-9_.:-]{5,}|([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}'
   git diff --cached -U0 | grep -nE "$PAT" | grep -vE 'device-[a-z]|redacted-serial-hash' || true
   git ls-files | grep -Ei '\.(png|jpe?g|webp|gif|bmp|mp4|mov|webm|mkv|avi)$' || true
   gitleaks protect --staged --verbose   # 若环境已安装；未安装也必须人工检查
   ```

   提交消息不能只靠 pre-commit（此时消息尚未生成）：使用 commit-msg hook 或提交前人工/脚本
   检查本次消息，CI 必须对 `git log --all --format=%B` 复扫。关键词零命中不能替代人工审查；
   任何图片、凭据、真实设备输出或真实用户数据仍是硬拒绝。稳定别名和策略文本可能产生提示；
   过滤别名不得掩盖同一行里的真实值，命中行必须人工确认。本次暂存差异之外的存量由 3.3 的
   周期审计处理；新规则/新扫描器必须附带合成负例，证明它能拒绝真实格式的序列号、截图和密钥。

3. 任何被追踪的图片/视频都视为需要审批：真机截图直接拒绝；合成 UI 图必须说明来源、
   生成方式和 review 记录。
4. `git add -f` 仅用于经过范围审查的文档；不得对 `docs/`、`.scratch/`、截图目录或证据
   目录整体强制添加。`.gitignore` 只是安全网，不是提交授权。
5. 新增测试 fixture 必须是合成数据；不得复制真实设备输出、真实凭据或真实用户数据。

### 3.2 CI 与 Release

- PR/push/tag 必须运行 `sensitive-data-guard` 类检查，至少覆盖：被追踪的图片/媒体、
  禁止字段、commit message、密钥扫描。任一命中即失败，禁止 `continue-on-error`。
- Release workflow 在上传 APK、源码包或 Release note 之前必须运行同一 gate；不得用旧
  artifact、条件跳过或“只做静态检查”冒充通过。
- 新引入的扫描器/规则必须附带负例（含真实序列号、截图、`.env`、keystore 的合成 fixture
  必须被拒），并记录在对应 ticket。

### 3.3 Review 与周期审计

- reviewer 的 checklist 必须包含本 policy；任何设备证据需要第二人 review 和显式用户批准，
  并记录“删除了什么、为什么、保留了什么摘要”，不得记录原始值。
- 每次 Release 前和至少每月执行一次全量审计：
  - `git log --all --format=%B` 检查提交/标签消息；
  - 扫描全部可达 blob 的图片签名与禁止字段；
  - 检查所有 tag、PR 正文/评论、Release note；
  - 检查被追踪的图片、日志、keystore、`.env` 和本机路径。
  审计结果写入报告；发现命中按第 5 节处理，不得静默忽略。

## 4. 证据存放

- 真机原始证据放在 `HOST_ENVIRONMENT_POLICY.md` 定义的 approved external resource root，
  按 ticket/owner/日期/别名组织；不得放在源码树、仓库根目录或临时目录。
- 仓库只保留脱敏摘要：别名、API/ABI、版本/commit、命令类别、结果，以及必要时的证据文件
  SHA-256；不得保留原始设备输出。
- 设备别名映射、原始截图、完整日志和序列号台账只存本地受控位置；不得进入 Git、Release、
  issue、PR 或任何外部服务。
- 历史 `.log` 证据继续遵守本目录 README 的“证据文件命名与保全纪律”。

## 5. 历史泄漏处置

一旦发现敏感开发者数据进入远端，立即执行：

1. 停止相关提交/发布；不要只删除当前文件。
2. 保留最小脱敏证据和影响范围；涉及凭据时按
   `INCIDENT_RESPONSE_AND_CREDENTIAL_ROTATION.md` 轮换。
3. 使用 `git filter-repo` 重写全部 refs：替换序列号为稳定别名、删除截图/日志路径、
   清理 commit/tag 消息；逐项验证 blob、提交消息、tag、PR ref 和当前树。
4. force-push 所有分支和 tag，更新 Release 元数据；force-push 后旧 commit 仍可能可访问。
5. 联系 GitHub Support 清理 cached views、旧 PR refs、不可达对象、搜索索引和
   Actions 日志/artifact；在 Support 确认前不得宣称“已彻底删除”。
6. 如实报告残余暴露；假定搜索引擎、第三方镜像和已有 clone 已保留副本。序列号无法轮换，
   应按“已公开”处置。

## 6. 例外与变更

- 设备唯一标识和真机截图**没有例外**；业务需要时使用合成证据或本地演示，不进入公开仓库。
- 其他例外必须有显式用户授权、书面范围、脱敏方案、保留期、reviewer、回滚方式和时间限制；
  记录在当前 ticket 与报告中，不构成先例。
- 本 policy 的修改按本目录 README 的稳定性与变更门执行：需要用户授权，说明威胁模型、
  受影响规则、迁移/回滚、负例测试、审查人和生效范围；任何 ticket 不得单独放宽。

## 7. 生效记录

- 2026-10-19 v1.0：基于公开历史清理事件建立；经用户授权，作为项目级硬约束生效。
- 存量已入库的敏感项按第 5 节逐项处置；存量存在不构成新增例外。
