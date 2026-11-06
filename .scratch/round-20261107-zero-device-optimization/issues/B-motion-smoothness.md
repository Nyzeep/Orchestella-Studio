# B · 软件动效与流畅度：Material Motion 全局过渡体系

## What
1. 联网调研 Material Motion 3（Android View 体系）过渡规范（easing/duration token、共享轴、容器变换的 XML 可达部分）。
2. 基于既有 res/anim/oe_* 命名基线扩展：主题级 `windowAnimationStyle` 全局 activity 过渡覆盖（含 Settings/Wizard/Terminal/Detail 进入退出），补 interpolator token。
3. 列表/卡片态切换动效（实例列表态变化、设置分区展开）以 XML + 既有 View 属性动画为主。
4. 流畅度：审查新动效全部走硬件层（layerType/anim 无布局抖动），避免每帧 layout。

## Acceptance
- 新增/修改动效资源命名沿用 oe_ 前缀；themes 引用编译通过。
- 既有测试不破坏（:app 全量绿）；lint 不新增 error。
- 输出动效清单表（资源名→挂点→duration/easing token）。

## 边界/坑
- res/** 除 strings.xml 外全归 B；禁改任何 .kt/.java 既有文件（新 ui/motion 类除外）。
- 无真机：不引入需要真机验证的重交互（共享元素跨 activity 需代码挂钩，若必须代码改动，列 TODO 不实施）。
