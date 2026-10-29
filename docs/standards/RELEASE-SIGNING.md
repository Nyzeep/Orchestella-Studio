# 发布签名向导（Release Signing）

> 状态：**Active**（2026-10-29 起）｜适用范围：`io.github.nyzeep.orchestella` 主包与
> `io.github.nyzeep.orchestella.accel` 扩展包的公开分发
> 相关硬约束：[版本标准](VERSIONING.md) §5.3 第 3/6/7 条；发布门脚本 `tools/release/verify_apk.py`

本文只写"怎么生成、怎么保管、怎么接线、怎么验证"。**密钥的生成与保管是维护者动作**，
仓库不包含任何密钥，构建脚本也不会替你生成。

---

## 1. 为什么必须是发布证书

三件事同时指向同一个结论：

1. **Debug 证书不能代表发布身份。** `CN=Android Debug` 是公开的、人人可生成的证书；
   用它签名等于没有签名。发布门（`verify_apk.py`）会直接拒绝 debug 证书的 APK。
2. **升级连续性绑在证书上。** 同一 `applicationId` 换签名密钥后，系统拒绝覆盖安装；
   用户只能卸载重装，而卸载会带走应用私有目录里的实例（根盘、配置、凭据）。
3. **pVM 扩展包需要 v3 签名块。** AVF 的 payload loader 在为
   `VirtualMachineConfig.setApkPath` 生成 idsig 时读 v3 APK Signature Scheme 块；
   v2-only 的 APK 在 VM 启动阶段被拒（`No APK Signature Scheme block`）。
   两个模块的 release 签名配置都已显式打开 v3/v4。

因此：**先把密钥生成并保管好，再谈打 tag**。顺序反了会产生"发布了一个无法升级的版本"这种不可逆事故。

---

## 2. 前置检查

| 项 | 要求 | 怎么确认 |
| :--- | :--- | :--- |
| JDK | 17（与构建一致） | `keytool -help` 能跑 |
| 存储位置 | **仓库卷之外**（个人密钥目录 / 加密盘） | 路径不含仓库根 |
| 备份 | 至少 2 份离线副本，与密码分开存放 | 见 §4 |
| 密码 | ≥ 12 字符，随机生成，进密码管理器 | 不写入任何仓库文件 |

`keytool` 在 JDK 的 `bin` 下；本机若没配 PATH，可用 JDK 安装目录里的绝对路径调用，
但**不要把该路径写进仓库文档或脚本**。

---

## 3. 生成

### 3.1 推荐：交互式脚本

```bash
bash tools/release/create-release-keystore.sh ~/keys/orchestella-release.jks
```

脚本行为（可审计，共 60 余行）：

- 参数全部交互输入，密码 `read -s` 不回显、不进 shell 历史、不进进程列表；
- 已存在同名文件时**拒绝执行**（不会覆盖旧钥）；
- `PKCS12` + `RSA 4096` + `SHA256withRSA` + 有效期 10000 天；
- 结束前打印证书 SHA-256 指纹（用于发布清单），并提示后续接线步骤；
- **不改任何构建文件**，不把密码写进任何文件。

### 3.2 手工等效命令

```bash
keytool -genkeypair \
  -keystore /path/outside/repo/orchestella-release.jks \
  -storetype PKCS12 \
  -alias orchestella-release \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=Orchestella Studio, O=<组织>, C=CN"
```

`keytool` 会交互询问两个密码（keystore 与 key）。**不要让密码出现在命令行参数里**。

### 3.3 别名与有效期约定

| 项 | 值 | 理由 |
| :--- | :--- | :--- |
| alias | `orchestella-release` | CI secrets 与本地属性统一引用这个名字 |
| 有效期 | 10000 天（≈27 年） | 超过 Google Play 的 25 年下限；减少轮换次数 |
| 算法 | RSA 4096 / SHA256withRSA | `apksigner` 与 Android 8.0+ 全兼容 |

---

## 4. 保管纪律

1. **不入仓。** `.gitignore` 已兜底 `*.jks`、`*.keystore`、`signing.properties`；
   但兜底不是策略——密钥文件本身就不该放在仓库所在卷上。
2. **密码与密钥分离。** keystore 在一处（离线介质 / 加密盘），密码在密码管理器；
   两者同放一处等于没有保护。
3. **3 份副本，2 种介质，1 份异地**（3-2-1）。副本必须是加密的，且每份都要能独立恢复。
4. **记录指纹。** 生成后立刻把证书 SHA-256 写进发布清单
   （`docs/reports/releases/vX.Y.Z.md` 与 GitHub Release 正文），
   这是事后判断"这个包是不是我们签的"的唯一凭据。
5. **不做自动化托管。** 不把 keystore 提交到任何云盘同步目录；CI 侧只用 secrets，
   且 `release.yml` 在 job 结束时删除临时密钥与属性文件（已实现）。

**丢钥的后果**：同一 `applicationId` 无法覆盖安装新包，用户必须卸载重装并丢失实例数据。
这不是"再生成一个就好"的问题，因此备份优先级等同发布本身。

---

## 5. 接线

### 5.1 本机构建

把四个属性写进 `~/.gradle/gradle.properties`（用户级，**不是**仓库里的 `gradle.properties`）：

```properties
orchestella.signing.storeFile=/absolute/path/outside/repo/orchestella-release.jks
orchestella.signing.storePassword=<keystore password>
orchestella.signing.keyAlias=orchestella-release
orchestella.signing.keyPassword=<key password>
```

也可以每次构建用 `-P` 传入，但那会把密码留在 shell 历史里，不推荐。

两个模块读取的是同一组属性：

- `:app` —— 缺任一属性时 `validateReleaseSigning` 让 `assembleRelease` **直接失败**；
- `:accel-extension` —— 同样四个属性、同样失败策略。

内部通道（`-Porchestella.buildChannel=dev|qa`）一律用 debug 证书签，
即使本机配置了发布密钥也不会被选中，避免内部包污染发布身份。

### 5.2 CI

`.github/workflows/release.yml` 已经实现，需要配置四个仓库 secret：

| Secret | 内容 | 生成方式 |
| :--- | :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | keystore 的 base64 | `base64 -w0 orchestella-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore 密码 | 密码管理器 |
| `RELEASE_KEY_ALIAS` | `orchestella-release` | 固定值 |
| `RELEASE_KEY_PASSWORD` | key 密码 | 密码管理器 |

工作流只接受 `workflow_dispatch`（`confirm=yes`），任何 secret 缺失都会**在打包前失败**，
不会产出"看起来像正式版"的未签名/调试签名产物。job 结束阶段会删除临时密钥与
`$HOME/.gradle/gradle.properties`。

---

## 6. 验证

签名之后、打 tag 之前，按顺序跑：

```bash
# 1) 身份与证书：applicationId / versionName / 证书非 debug / 打印 APK SHA-256
python tools/release/verify_apk.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --expected-application-id io.github.nyzeep.orchestella \
  --expected-version-name 0.6.0 \
  --json release-verification.json

# 2) 证书指纹与 v3 块
apksigner verify --print-certs --verbose app/build/outputs/apk/release/app-release.apk

# 3) 16 KB ELF 段与 zipalign
bash tools/ci/check-apk-16kb.sh app/build/outputs/apk/release/app-release.apk

# 4) 许可与 NOTICE 完整性
python tools/release/check_licenses.py

# 5) 敏感数据门（提交历史 + 工作树）
python tools/ci/check_sensitive_data.py --self-test
python tools/ci/check_sensitive_data.py
```

`apksigner verify` 的输出必须同时满足：`Verified using v3 scheme: true`（扩展包硬要求，
主包也建议保持）与证书 DN 不是 `CN=Android Debug`。

---

## 7. 双包发布的签名顺序

1. 配置好四个属性；
2. `./gradlew :app:assembleRelease :accel-extension:assembleRelease`（扩展包需要预先准备好的
   payload / compiler 输入目录，见 `accel-extension/build.gradle.kts` 的输入校验）；
3. 两个 APK 分别过 §6 的 1)–3)，**并核对 `versionCode` 一致**（运行期版本锁，不一致会
   直接拒绝启用扩展包）；
4. 主包先发、扩展包作为 Release 附件同页提供，安装顺序与"只装主包只有 QEMU 车道"的说明
   写进 Release 正文（见 `docs/reports/releases/v0.6.0.md`）。

---

## 8. 遗失与轮换预案

| 场景 | 处置 | 用户影响 |
| :--- | :--- | :--- |
| 密钥遗失且无备份 | 换新钥 + **换 applicationId**（如 `…orchestella2`）或走数据导出/导入迁移 | 用户必须重装；实例数据需先导出 |
| 密钥泄露 | 立即换钥、公告、旧钥签名的版本不再分发 | 同上 |
| 计划性轮换 | Android 支持 v3 的 proof-of-rotation  lineage，可在保持升级连续性的前提下换钥 | 需在签名工具链里生成 lineage，本轮未实现，属于下一阶段工作 |

---

## 9. 检查清单（打 tag 前逐项打勾）

- [ ] keystore 在仓库卷之外，且有 2 份离线备份
- [ ] 密码只在密码管理器里，`git grep` 不到任何密码字面量
- [ ] 四个 `orchestella.signing.*` 属性在 `~/.gradle/gradle.properties`（或被 CI secrets 注入）
- [ ] `validateReleaseSigning` 在缺属性时确实失败（故意漏一个属性跑一次，确认报错）
- [ ] `verify_apk.py` 通过，证书 DN 非 debug
- [ ] `apksigner verify` 显示 v3 块存在
- [ ] 主包与扩展包 `versionCode` 一致
- [ ] 证书 SHA-256 已写入发布清单与 GitHub Release 正文
- [ ] `check_licenses.py`、16 KB 检查、敏感数据门全部通过
