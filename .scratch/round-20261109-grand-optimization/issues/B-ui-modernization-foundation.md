# B · UI 现代化设计基座（设计 token + 基础组件）

Status: done
Owner: lane/b-ui-foundation
Files: app/src/main/res/values/{colors,dimens,styles,oe_motion_tokens}.xml、res/drawable（card/button 表面族）、res/layout 共用卡片容器、ui/motion/OrchestellaMotion.kt（只读参考）、新 ui/design/ 目录（如需代码侧 token）

## What

1. **联网调研**（必做，产出带来源的设计注记，写进工单 Comments）：
   - Material 3 Expressive（2025-2026 最新）：shape scale/spacing/containment
     强调层级、color role、typography 权重节奏
   - 成熟开源 Android UI 项目借鉴 2-3 个（DroidKaigi conference-app、
     Openreads、Now in Android 等）：card/stroke/list item 的具体实现
   - 禁止照抄品牌视觉，只借鉴工程做法（token 结构、层级表达）
2. **设计 token 落地**：统一圆角 scale（如 12/16/20/28）、间距 scale（4 的倍数）、
   surface 层级（surface/surfaceContainer/surfaceHigh 对应到现有四主题色板）、
   stroke 规范（subtle translucent，替代当前灰边——用户点名问题）、
   typography 权重节奏（标题/正文/标签）
3. **基础组件**：卡片背景 drawable 族（替换散落的 stroke drawable）、按钮状态
   样式族（**disabled 态高级化**——用户点名灰按钮问题：低透明度 enabled 样式 +
   对比度可辨，禁用扁平灰）、页签条样式
4. **emoji 图标体系**：识别全仓 strings/layout 中的 emoji 图标（详情页签
   📌⚡💾🌐⚠️、按钮 💻 等），产出替换清单并落第一批 ic_sym_* vector
   （Material Symbols Outlined 路径，currentColor），剩余清单交 E/F lane

## Acceptance

- :app 全量 ≥1266/0/0（ASCII worktree）；lint 0 新增 error
- 四主题（mascot/red/blue/yellow）下不破色
- 真机验证点清单（卡片无灰边、disabled 按钮高级感、页签 icon 化）

## 边界

- 不动 startup/、runtime/、ControlChannelRecoveryTest
- res/anim 参数不动（D lane 边界）；strings.xml 只动 emoji 标签文本（不动 error_* 键区）

## Comments

### 2026-11-10（编排层回填）

lane/b-ui-foundation 5c67f866+f3e3efd4+98d88697 合并 main；:app 1270/0/0（+4 四主题派生测试）。token 基座 oe_design_tokens/styles/Widget.Orchestella.* + surfaceLow/High/pressedFill；bg_oe_card/well 族收敛；8 死 drawable 删；elevation 0dp 收口；DetailTab + 16dp 行内图标。调研来源在 commit body。遗留：按钮样式全量挂接与死布局 item_instance_card 归 F（已由 F 完成）。
