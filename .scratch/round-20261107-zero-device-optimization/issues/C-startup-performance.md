# C · 系统性能增强：冷启动与主线程卫生

## What
1. 冷启动路径审计：Application/MainActivity onCreate→首帧的每步耗时来源清单（静态初始化、同步 IO、对象图装配）。
2. 落地：可懒加载的单例/注册表改懒加载；主线程磁盘 IO 迁后台（带既有 executor 语义）；重复读取（如 SharedPreferences 多次解析、asset 重复打开）合并缓存。
3. MainActivity 内：启动期一次性工作（视图装配/监听注册）与可延迟项分档，延后项移到首帧后（post 首帧或 idle）。
4. 协同落点（来自 D 的规格，若 D 未完成则立 TODO 不实施）：首启通知权限请求时机调整（不阻塞首屏）、启动失败 IOException 的人性化文案映射。
5. 补启动路径锁定测试（可测部分：懒加载单例语义、IO 迁移后的线程断言）。

## Acceptance
- 审计清单表（路径→问题→处置：已落地/TODO+理由）。
- :app 全量单测绿；lint 不新增 error；不改变任何对外行为语义。

## 边界/坑
- 独占 MainActivity.kt + Application 类 + 新启动辅助类；禁碰 res/**（除只读）、runtime/**、SetupWizard/Settings/Terminal/Detail。
- 中文路径不能跑测试：worktree 建在 /d/t/。构建先 clean。
