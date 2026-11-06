# D · 用户体验：首启向导、设置页与文案

## What
1. SetupWizardActivity.kt（1514 行）UX 走查：步骤流转文案/空态/错误态/可回退性小改进（行为保守，不做大改版）。
2. SettingsActivity.kt（设置 UX）：分区视觉分组一致性、开关即时回显、危险项（清数据等）确认文案核对。
3. 文案人性化：启动失败/网络失败等错误展示从裸异常名（如"启动失败：IOException"）映射为人类可读文案+固定码副文本（strings.xml 新增；映射点若在 MainActivity 出规格给 C 落地，若在 D 独占文件内直接落地）。
4. TerminalActivity/InstanceDetailActivity 小改进：滚动到底、复制反馈、空态提示（保守）。

## Acceptance
- 改动清单（文件→改进→风险级）；strings 新增键命名沿用既有风格。
- :app 全量单测绿（UI 无自动化测试基建，明确标注人工冒烟项）；lint 不新增 error。

## 边界/坑
- 独占 SetupWizardActivity.kt、SettingsActivity.kt、strings.xml、TerminalActivity.*、InstanceDetailActivity.kt；禁碰 MainActivity.kt（协同项走 C）。
- 中文路径不能跑测试：worktree 建在 /d/t/。构建先 clean。
