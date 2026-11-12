# 打包机构建工单：UML fast-profile 车道（`-Porchestella.uml.fastProfileDir`）

- 票号/标题：uml-fast-profile-packaging——打包机侧 UML 产物真实入包的操作指引与产物核对
- 状态：**OPEN**（待打包机窗执行；托管流水线按豁免语义不受本工单约束）
- 立票：2026-11-13（UML 可路由轮 Lane C · 发布门加固）
- 上游决议：ACR §8（`.scratch/uml-productization-20261112/acr/acr-draft.md`）D1/D3/D4/D5
- 关联文件：`app/build.gradle.kts`（`stageUmlFastProfile` 任务段）、`.github/workflows/release.yml`（"Build release APK" 与 publish-draft 两处 UML 传参/断言）、`tools/release/check_uml_payload.py`（本工单新增的 APK 级断言门）、`docs/NOTICE.UML-DRAFT.md`、`docs/adr/0014-licensing.md`
- 背景：review P2-1（产品化轮）——`bundledFastProfile=true` 而漏传 `fastProfileDir` 时 staging 仅 WARN 跳过；托管流水线是 ACR D1 明文豁免（干净检出不可能有 git-ignored 的 U1 产物），但打包机漏传同样只留下一条构建日志 WARN。本工单把打包机侧做成**正向断言**（`--require-payload`），P2-1 至此闭合：豁免保持诚实，承诺入包的机器必须证明白带。

## 1. 为什么必须由打包机执行

1. U1 fast-profile 产物（`linux-fast` 内核镜像、`stub_exe` 助手）是 **git-ignored 的机器输入**：托管 CI 的干净检出永远没有，`stageUmlFastProfile` 在该场景记录豁免 marker 并跳过（`build/uml-fast-profile-staging-skipped.flag` + WARN），产出的 APK 不含 UML 内核，运行时 `UmlFaceLauncher` 如实回答 MISSING_ASSET（宽度 0，不伪造 face）。这是**有意的诚实降级**，不许"修好"。
2. 真实入包只发生在打包机：`-Porchestella.uml.fastProfileDir=<U1 产物目录>` 提供后 staging 变为 **fail-closed**——目录不存在、缺产物文件、sha256 与 pin 不符，任一命中都直接 fail the build，没有降级路径。
3. 入包后产物链：`lib/arm64-v8a/libuml_kernel.so` + `lib/arm64-v8a/libuml_stub.so`（jniLibs）+ 记账 manifest `assets/uml-fast-profile-v1.sha256`（打包名 + pin 摘要行），可选附 `alpine-minirootfs-3.22.2-aarch64.tar.gz`（APK 根部 java 资源）。

## 2. 前置条件（打包机侧核对）

| 项 | 要求 |
| --- | --- |
| U1 产物目录 | 至少含 `linux-fast`、`stub_exe` 两个文件（可选 `alpine-minirootfs-3.22.2-aarch64.tar.gz`）；建议使用 P1 可复现版产物（`research/uml-fastboot/manifest.md` §3 的 ARM64 UP 列） |
| pin 单源 | `UmlKernelAssets.FastProfile.PIN_MANIFEST`——运行时、gradle 记账、契约测试三方共用；本工单不复制第二份 pin 表，下方现值仅供立票时点（2026-11-12）肉眼复核，若与代码冲突以代码为准 |
| 现值核对（2026-11-12 时点） | `linux-fast` 的 sha256 pin = `21e1c3f97269a831fa8bab5fd3ff0e8a0e9edc59554d17cce34913924f584389`；`stub_exe` 的 sha256 pin = `87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12` |
| 目录纪律 | 产物目录路径不进仓库文档、不进 shell 历史（打 IR 痕迹）；本工单一律以 `<U1 产物目录>` 占位 |

复核命令（在产物目录内逐文件）：

```bash
sha256sum linux-fast stub_exe
# 输出必须与 UmlKernelAssets.FastProfile.PIN_MANIFEST 对应行逐一相等
```

## 3. 构建命令（与 release.yml 同参 + 打包机增量）

**release 版**（参数与 `.github/workflows/release.yml` 的 "Build release APK" 步骤逐字同构，仅打包机追加 `fastProfileDir`）：

```bash
./gradlew :app:assembleRelease \
  -Porchestella.assets.allowMissingPayload=true \
  -Porchestella.acceleratorLane=true \
  -Porchestella.uml.bundledFastProfile=true \
  -Porchestella.uml.fastProfile=true \
  -Porchestella.uml.fastProfileDir=<U1 产物目录> \
  --no-daemon --stacktrace
```

**QA 包**（真机复验用；注意 QA 包必须是 `assembleDebug` + 双参数，`assembleRelease` + channel 会静默产出干净主包——真实翻车过的判别点）：

```bash
./gradlew :app:assembleDebug \
  -Porchestella.buildChannel=qa \
  -Porchestella.buildPurpose=<小写字母数字> \
  -Porchestella.uml.bundledFastProfile=true \
  -Porchestella.uml.fastProfile=true \
  -Porchestella.uml.fastProfileDir=<U1 产物目录> \
  --no-daemon --stacktrace
```

flag 语义（gradle 默认值神圣不可侵犯，全 false；显式传参才生效）：

| flag | 语义 |
| --- | --- |
| `orchestella.uml.bundledFastProfile` | 开 staging 任务；无 `fastProfileDir` → 豁免跳过（marker + WARN），有 → fail-closed 入包 |
| `orchestella.uml.fastProfile` | 运行时 fast 形态（装配槽按 BuildConfig 选 `createWithCurrentFastProfile`） |
| `orchestella.uml.rootfsDownload` | 方案 B 下载入口门（与 staging 无关，按需传） |
| `orchestella.uml.fastProfileDir` | 打包机专属：U1 产物目录；**只有打包机构建传它** |

预期 staging 日志（成功形态）：

```
stageUmlFastProfile: staged 2 fast-profile artifacts with matching pins; accounting manifest at app/src/main/assets/uml-fast-profile-v1.sha256.
```

（可选 rootfs tar 在产物目录中时 staged 数为 3。）失败形态（任一即 fail the build，这是特性不是故障）：

- `orchestella.uml.fastProfileDir is not an existing directory` → 目录拼错/不存在；
- `missing artifact: …` → 产物目录缺 `linux-fast`/`stub_exe`；
- `sha256 mismatch: …` → 产物与 pin 不符——**不许换用"手头有什么就用什么"的产物**，回去取 P1 pinned 产物或按升级纪律重新锁定 pin。

## 4. 产物核对（逐项过，缺一不可）

### 4.1 UML payload APK 级断言（本工单新增的核心门）

```bash
python tools/release/check_uml_payload.py --apk <APK 路径> --require-payload
```

- **OK**：APK 内 `assets/uml-fast-profile-v1.sha256` 记账的每个产物（`lib/arm64-v8a/libuml_kernel.so`、`lib/arm64-v8a/libuml_stub.so`、可选 rootfs tar）逐字节与记账行一致；
- **FAIL（非零退出）**：sha 不匹配 / 记账产物缺位于 APK / manifest 残缺或不可解析——输出含差异两侧摘要前缀与修复指引；
- 脚本从 APK **内部** manifest 读取并比对 APK 内 so 的实际 sha256，不依赖构建树、不内置 pin——它证的是"随包字节与随包账本一致"，pin 的正确性由 §3 的 fail-closed staging 保证；
- **前置事实（2026-11-13 实测）**：本门成立依赖 `app/build.gradle.kts` packaging 块对 `libuml_kernel.so`/`libuml_stub.so` 的 `keepDebugSymbols`——AGP 默认 debug-symbol strip 曾在 merge→APK 之间改写内核字节（staged 9,517,760 B/sha 21e1c3f9… → APK 内 8,071,168 B/sha 52764628…，stub 无符号可 strip 故逐字节幸存），记账链断裂、门必红。该保护已入库；打包机侧若见 libuml_kernel.so FAIL 且两侧 sha 均非 pin，先核对构建脚本该保护是否仍在；
- `--selftest` 可独立自测（合成 zip fixture 正/反 12 例），打包机无需任何输入即可验证脚本本身可用。

### 4.2 记账 manifest 对账（人眼复核，与 4.1 互补）

```bash
unzip -p <APK 路径> assets/uml-fast-profile-v1.sha256
# 逐行 <64位sha256>␠␠<打包名>，与 UmlKernelAssets.FastProfile.PIN_MANIFEST 对应行比对
```

### 4.3 既有门照跑（不因新门而免）

```bash
python tools/release/verify_apk.py --apk <APK 路径> \
  --expected-application-id io.github.nyzeep.orchestella \
  --expected-version-name <预期版本> --json release-verification.json
bash tools/ci/check-apk-16kb.sh <APK 路径>
python tools/release/check_licenses.py
python tools/release/check_runtime_payload.py --apk <APK 路径>   # QEMU 车道 payload 门（必须全过）
```

### 4.4 versionName 判别

- release 版：`<MAJOR.MINOR.PATCH>` 裸形态（当前 0.6.3），由 `verify_apk.py --expected-version-name` 锁定；
- QA 包：versionName 必含 `-qa.<purpose>.<gitShortSha>` 段（`.dirty` 后缀 = 树不干净，拒收）——qa 判别失败即构建通道错了（见 §3 QA 命令的警告）。

### 4.5 降级对照（可选但建议每轮做一次）

同一打包机**不带** `fastProfileDir` 再出一版对照 APK：`python tools/release/check_uml_payload.py --apk <对照 APK>`（无 `--require-payload`）应输出 `UML_PAYLOAD_GATE_SKIP` 并零退出——豁免形状的存在性与 skip 理由的正确性由此可视化；该对照 APK 真机安装后应呈现 MISSING_ASSET 诚实路径（不冒充 face）。

## 5. 发布衔接（ADR-0014 门禁与 NOTICE 正式化）

**触发条件：UML 资产第一次真实出现在公开发布 APK**（即本工单产物进入正式发布）。该时点必须同步完成：

1. **NOTICE 正式化**：`docs/NOTICE.UML-DRAFT.md` 去 DRAFT 化（改名 `NOTICE.UML` 并入包覆盖口径），其"正式化时点"条款明文约定：资产未真实入包前任何条目不得视为已交付；
2. **ADR-0014 §3 门禁**：GPL-2.0 修改版内核二进制的对应源码交付 = 公开固定 tag + 一一对应 Release 源码包（含 `defconfig-fast` + fragment、`build-fast.sh`、LICENSE/NOTICE、校验记录），与 `PIN_MANIFEST` **同轮原子更新**；内核升级 = 新 tag + 新源码包一次原子变更；
3. **书面要约**：以 `guest-assets/SOURCE-OFFER.txt` 为模板出 UML 件版本（≥3 年有效、`BUILD-INPUTS` 识别码），即使走公开 tag 路径也随包保留要约兜底（NOTICE 草稿条目 7）;
4. **open 项收口**：`linux-fast` 跨构建路径复现性 open 项与 `stub_exe` 许可归属裁定（NOTICE 草稿条目 1/4）在正式化前必须有结论——pin 采用哪条构建路径、digest 与二进制一一对应公布。

## 6. 红线（违反即返工）

1. 三 flag 的 gradle 默认值（全 false）不许动；托管流水线的显式传参与豁免语义不许动；
2. 双门（AccelLane 装配门 + 用户设置门 + start() 复读）与 MISSING_ASSET 诚实降级语义不许动；
3. 缺 pin 产物时禁止任何形式的降级混用（换文件、改 pin、"先出包后补验"都不行）；
4. 豁免 APK（无 UML payload）不得作为正式发布包——QEMU payload 门（`check_runtime_payload.py`）在 publish-draft 会拒绝它，UML 门对它 SKIP 但 `--require-payload` 模式会拒绝它。

## 7. 验收清单

- [ ] §2 pin 复核：产物 sha256 与 `PIN_MANIFEST` 逐一相等；
- [ ] §3 构建成功且 staging 日志为成功形态（staged 2/3 artifacts）；
- [ ] §4.1 `check_uml_payload.py --require-payload` 退出 0；
- [ ] §4.2 manifest 对账逐行相等；
- [ ] §4.3 verify_apk / 16KB / licenses / QEMU payload 四门全绿；
- [ ] §4.4 versionName 判别通过（release 裸形态 / QA 含 `-qa.<purpose>.<sha>`）；
- [ ] §4.5 降级对照（每轮至少一次）SKIP 理由正确；
- [ ] 若进入正式发布：§5 四项同步完成（NOTICE 正式化、ADR-0014 tag+源码包、书面要约、open 项结论）。
