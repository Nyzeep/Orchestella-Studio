# 轮总报告：零设备全项目优化轮（2026-11-07 · 四 lane）

## 1. 背景

设备窗 2026-11-07 轮收口（main @ 496270c8，CI 全绿）后，经用户授权开启的全项目大型优化轮，覆盖：代码优化 / 系统性能增强 / 系统整合 / 软件流畅度 / 用户体验 / 软件动效。全部零设备完成；spec 与四张工单在 `.scratch/round-20261107-zero-device-optimization/`，文件边界预分配，lane 间零冲突合并。

## 2. Scope

四个 lane、两批次（每批 ≤2 子智能体）：A 代码优化与整合、B 动效与流畅度（联网调研 Material Motion）、C 系统性能（冷启动与主线程卫生）、D 用户体验（首启/设置/文案）。构建一律先清旧包残留（rm app/build / outputs）。

## 3. 实际完成

### Lane A · 代码优化与系统整合（3 commits，已合并）
- `InstanceLifecycle.java` **1724→739**：按「协调/持久化/元数据编解码」三 seam 拆出 `InstanceLifecycleController`(651)/`InstanceFileStore`(188)/`InstanceMetadataCodec`(270)，公共嵌套类型薄壳保持 API 与 `synchronized` 语义零变化；+3 契约测试。
- `BridgeControl.java` **847→593**：逐动词请求行解析拆出 `BridgeRequestParser`(281)（dispatch 路由与 Result 留守）。
- 双池（ProtectedWorkerPool 1130 / ProtectedWarmPool 858）：抽 `ProtectedPoolKnobs`(77) 统一内存上限/前缀字符集/超时哨兵验证；acquire/release 锁纪律不同，不做强行基类合并（为拆而拆反例，理由记录）。
- `TerminalMux.java`(862) **不拆**：单一 synchronized 状态机无行为中性 seam，超目标仅 62 行（7%）。
- 测试 1227→**1230/0/0**；lint 0e/34w 无回归；`:runtime:instance/contract/fastlane-terminal:check` 全绿。

### Lane B · 软件动效与流畅度（1 commit，已合并）
- 联网调研（M3 token 经 androidx MotionTokens.kt 源码核实；AOSP `Animation.Activity` 属性全集；`overridePendingTransition` API 34 弃用佐证主题级路线）。
- 现状盘点：activity 级 open/close 全覆盖无遗漏；**真缺口=task 级**（launcher 冷启动/recents 回前台/回退出根闪平台默认）。
- 落地：`windowAnimationStyle` 补 taskOpen/Close/ToFront/ToBack/launchTaskBehind*；新建 `oe_motion_tokens.xml`（M3 duration/emphasized_enter=210ms）+ `oe_emphasized`/`oe_standard_accelerate`/`oe_standard_decelerate` interpolator token；`oe_state_enter/exit`、`oe_section_expand/collapse`、`oe_list_item_enter`+分区级 stagger（settings 已纯 XML 接线）；既有四枚 open/close 动画 duration 字面量→token 引用（时序不变）。
- 新增 `ui/motion/OrchestellaMotion.kt`（硬件层 state 切换辅助，KDoc 标注全部待挂钩点）；测试 1230/0/0 不变；lint 无新增。

### Lane C · 系统性能（3 commits，已合并）
- 冷启动审计 18 项全表（MainActivity.kt + Application），最大单点=**主线程对 libdroidlayer_qemu.so 全量 SHA-256（APK ZipFile 打开）**。
- 落地：`startup/RuntimeVerification`（校验迁 operations executor + 进程级记忆化，状态行先渲染后回调补齐）；`DashboardSnapshot`（实例列表/加速 facts/端口转发规则/硬件读数 后台快照化，3 秒刷新循环不再做主线程 IO；单线程队列保序）；`surfaceColor` 等每卡片 new ThemeStore 复用；`handleLaunchIntent` IO 迁后台。
- **首启通知权限改为首帧后请求一次**（rootContainer.post + 原子门；设备窗实测的 VM 启动弹窗打断问题根除）；`startup/LaunchFailureCopy` 启动失败文案人性化（固定码括注，原文仍写日志）。
- 保守保留 6 项（首帧主题必需/点击时序/ViewStub 高风险等，逐项理由在报告）；测试 1230→**1244/0/0**（+14 锁定测试）；lint 无新增。

### Lane D · 用户体验（2 commits，已合并）
- 新增 `ErrorCopy.kt` 纯 JVM 错误映射（13 异常族 → 固定码 E-* + 短文案 + 建议动作；16 用例锁定含子类判定顺序回归）；SetupWizard/Terminal 已消费。
- SetupWizard：失败文案人性化、创建中退出确认框、自定义磁盘输入空/非数字校验修复（原先非数字可点下一步）。
- Settings：接 B 的 `expandSection/collapseSection`（启动验证与回退卡可折叠，代数计数器防快速连点竞态）。
- InstanceDetail：五页签接 B 的 state enter/exit（守卫防容器滞留半透明）。
- Terminal：attach 后按 follow 状态滚动到底、无实例直启空态文案、reconcile 错误人性化（原文进 logcat）。
- 测试 1230→**1246/0/0**（+16）；lint 无回归；边界校验（diff 无 MainActivity/anim/runtime）。

## 4. 未完成 / not-run

- 动效代码挂钩 TODO（container_tab_console/五页签旧调用点迁移完成前的新挂点、fragment* animator）——B 的 KDoc 清单，需真机验证轮消费。
- C 的 6 项保守保留（含 ViewStub 拆分）与设备侧确认项（权限弹窗观感、快照 3s 滞后可接受性）。
- **错误文案双实现债务**：`ErrorCopy`（D，strings 版，向导/终端消费）与 `LaunchFailureCopy`（C，代码常量版，MainActivity 消费）并行 lane 产物功能重叠——下一轮统一为 ErrorCopy 单源（C 侧文案表已并入 D 的映射契约，固定码只增不改名）。
- 终端复制成功反馈（ModernTerminalView 不在本轮任何 lane 边界）——遗留票。
- 人工冒烟 6 项（向导/设置/详情/终端）——需设备窗。

## 5. 修改文件（汇总，相对仓库根）

- 拆分新增：runtime/instance `{InstanceLifecycleController,InstanceFileStore,InstanceMetadataCodec}.java`、runtime/contract `BridgeRequestParser.java`、app `ProtectedPoolKnobs.java` + 5 个契约测试。
- 动效：res/values/{themes.xml,oe_motion_tokens.xml}、res/anim 12 枚（4 改 8 增）、res/layout/activity_settings.xml（layoutAnimation 一行）、ui/motion/OrchestellaMotion.kt。
- 性能：app/startup/{RuntimeVerification,DashboardSnapshot,LaunchFailureCopy}.kt、MainActivity.kt（+227/−75）、NotificationPermissionPrompt.java（注释）+ 3 测试类。
- UX：ErrorCopy.kt、SetupWizardActivity.kt、SettingsActivity.kt、InstanceDetailActivity.kt、TerminalActivity.java、strings.xml（+28 键 −1 键）、activity_settings.xml（fallback 卡结构）+ ErrorCopyTest。
- 集成期修复：DashboardSnapshotTest.kt 回退断言按 store 排序推导（随机 UUID 字典序≠创建序，编排层冒烟捕获的测试错序假设）。

## 6. 测试与验证

- 集成冒烟（干净 worktree、删 app/build 后全量）：**1260/0/0**（1227 基线 +33 净增，与各 lane 报告对账吻合：A+3、C+14、D+16，B 0）。
- lint：0 error（warning 存量级无回归）。
- 敏感门：SENSITIVE_DATA_OK（每次 push 前）。
- CI：**五 workflow 绿 @ 4ac7f248**（CI 5m25s，一次通过）。

## 7. Commit / branch

- main @ **4ac7f248**：lane/opt-a-refactor（35cb9c58/9bce99ed/af456a21）+ lane/opt-b-motion（e5f62aed）+ lane/opt-c-perf（07eeb653/6dfa94bd/8f965d90）+ lane/opt-d-ux（b0089c2a/cd56ae3b）+ 集成修复 4ac7f248；merge 2d86bdd2（批次1）/ 2d84157e（批次2）。
- lane worktree 四个 + 集成 worktree：合并后已核查清理（见 §9）。

## 8. 风险

1. MainActivity 启动路径重构幅度较大（+227/−75）：线程语义靠单线程 executor 队列+结构保证，纯 JVM 测试无法覆盖 Activity 级时序——**首帧渲染顺序/3s 快照滞后需设备窗确认**。
2. 错误文案双实现（见 §4）短期内两套固定码并存（E-* 一致，映射表对齐），下一轮统一。
3. 动效为 View 体系 XML + 硬件层属性动画，无每帧 layout；但设置分区级联（layoutAnimation）为一次性首开效果，重复触发需代码重放（TODO）。
4. `bridgeClientResponsePumpTest` 冷启动负载敏感偶发（本轮 Lane A 记录，与本轮改动零引用关系）——建议后续放宽实时预算。

## 9. 越界记录

- 零越界：四 lane 均通过边界自检（D 的 diff 校验、A/B 的范围声明）；编排层仅修一个 lane 内测试的错序假设。
- 清理：lane/集成 worktree 经独立核查（零未提交改动、分支已并入）后 `git worktree remove`，分支 `-d` 删除。

## 10. ACP

- 两批次 ×2 子智能体（并发上限遵守）；lane 间协同走"规格交付物"（D→C 文案映射表、B→D 动效挂钩 KDoc）。
- 收尾链完整：合并→敏感门→集成冒烟（捕获并修复 1 个错序测试）→push→CI 一次绿→本报告→记忆。
