# 应用图标资产

Orchestella 的启动器图标（自适应图标 + 传统 48dp 图标）与商店/文档用静图，全部由
`tools/branding/make_app_icons.py` 从一张源美术生成，不依赖网络、不依赖 numpy，
只有 Pillow。脚本幂等：连跑两次，每个字节都相同。

## 源美术

| 项目 | 值 |
| --- | --- |
| 文件 | `source-mascot.jpg`（源图副本，1920×1920 JPEG） |
| 主体 | 紫色头发看板娘/吉祥物头像，脸部朝向左下，**主体在左侧与下侧越出画布** |
| 背景色 | `#242424` (36, 36, 36) 纯色 |
| 身体紫 | `#41375A` (65, 55, 90) |
| 脸部奶白 | `#FDF1DB` (253, 241, 219) |
| 几何描边 | 淡紫细线 ≈ `#9687A8` (150, 135, 168)，宽约 10–12 px |
| 前景包围盒 | x 0–1648 / y 178–1920（1920 坐标；左、下贴边，见"已知取舍"） |
| 脸部包围盒 | x 86–1381 / y 607–1754（含奶白三角耳） |

## 构图：为什么从 cover 改成 fit

会话 24 的产物是 **cover**：等比放大 + 以脸部为中心裁切，主体四角被切。
本轮用户明确要求"直接拿原图比例，不要裁切"，因此默认产物改为 **fit**（`--no-crop`），
cover 分支保留在脚本里（`build_cover`）以便复现旧版本，但不再随包发布。

### 约束冲突：字面的"零裁切"在自适应图标上不可能

Android 的自适应图标永远不是整幅显示：系统把 108dp 画布按启动器的形状（圆、方圆、
圆角方、水滴）做掩模，掩模之外的外圈直接丢弃。任何"塞满画布"的构图都必然被裁。

**忠实解读 = 主体零裁切**：整只看板娘完整落在掩模安全区内，宁可四周留空也不让掩模
切到主体；背景层则故意画到画布之外，让被裁掉的部分是"本来就可以裁"的装饰。

### 安全区的具体数字（脚本注释里同步写了一份）

| 常量 | 值 | 依据 |
| --- | --- | --- |
| 画布 | 108dp | Android adaptive icon 规范 |
| 可见视口 | 72dp | 同上，±18dp 为可被掩模切掉的出血环 |
| 安全圆 | 66dp | Android Studio 图标模板画的安全区，所有自带掩模形状都包含它 |
| 安全半径 | 33dp | `SAFE_CIRCLE_DP / 2` |
| 圆掩模半径 | 36dp | 72dp 圆，落在 33dp 处的主体距离掩模边界还有 3dp |
| 方圆最小半径 | 36dp | 超椭圆 n=4 沿轴向的最小半径就是 36dp |

因为 66dp 圆被圆掩模和方圆掩模同时包含，"主体落在 66dp 圆内"就等于
"任何形状的掩模都切不到主体"。脚本按这个不变量断言（见下）。

### fit 的算法

取前景 alpha 的包围盒，用它的**外接圆**（bbox 对角线的一半）作为主体的半径上界：

- 前景 bbox `(0, 178, 1648, 1920)`，即 1648×1742 px，外接圆半径 **1199.0 px**；
- 缩放使该半径正好等于 33dp ⇒ 108dp 画布覆盖 **3924 源 px**，即 **0.02752 dp/px**；
- 于是整幅原画在图标里占 **52.8dp**，主体占 **45.4 × 47.9dp**，居中放置。

用 bbox 而不是逐像素外凸包是**故意保守**的：bbox 的四个角其实是空的，所以主体只会
比上限小，绝不会被切。

## 三个图层

1. **background**（`ic_launcher_background.png`，108dp 不透明位图）
   `#242424` 底 + 淡紫线框。线框不是另画的：用"最近调色板颜色"把原图的描边抠出来
   （只保留淡紫是四色中最近的那些像素，避免奶油色与背景的抗锯齿过渡被误判），
   抹掉实心身体，得到右侧的晶体原样（**不镜像、不旋转**——早期版本把晶体镜像成带
   再交叉两条，图标尺度下笔画读成粗条，拼成一个米字/放射纹，多面体的形状感全没了）。
   两份晶体按 `WIRE_LAYOUT` 摆位：大的 1.75 倍画布盖住右上，小的 0.95 倍落在左下，
   都故意溢出画布——掩模在内圈怎么切，线框都还在继续，不会出现悬空的断线。
2. **foreground**（`ic_launcher_foreground.png`，108dp 透明位图）
   看板娘整只等比缩放进 66dp 安全圆，居中，四周透明。源图左/下越界的身体用
   **边缘复制延展**（`extend_subject`）接出画布外：角色尺寸一格不变，但那条裁切
   直边被推到 108dp 画布边界上，掩模之外（见"已知取舍"第一条）。
3. **monochrome**（`ic_launcher_monochrome.png`，108dp 纯黑 + alpha）
   **只有线框多面体**，没有主体。系统会用主题色给 alpha 平面染色，所以这一层必须
   是纯黑不透明像素 + 透明底。这一层的晶体是**程序化绘制的闭合宝石轮廓**
   （`crystal_sprite`：六边宝石 + 腰线 + 两条刻面线），不是抠出来的提取件——
   提取件因为撞进头发被擦掉左棱，是开口风筝，在启动器尺度下读作几根断条；
   镜像闭合又会因晶体中轴不在 bbox 中心而出双峰。画出来的闭合几何在三个尺寸
   （`MONO_LAYOUT`：0.60 / 0.34 / 0.20 画布）下都完整落在可见窗内，笔画
   1.2dp（`crystal_field` 的 `stroke_dp`），48px 传统图标上依然立得住。

`mipmap-anydpi-v26/ic_launcher.xml` 在 fit 模式下用
`<background android:drawable="@mipmap/ic_launcher_background" />`——线框只能放在位图里，
颜色资源做不到。`values/colors.xml` 里的 `ic_launcher_background` 保留，cover 模式仍用它。

## 掩模验收预览（怎么看）

四张图都在本目录，都是验收锚点，不随包发布：

| 文件 | 看什么 |
| --- | --- |
| `preview-circle-432.png` | 圆形掩模（Pixel 默认）。**白色粗环 = 72dp 掩模边界**，淡紫细环 = 66dp 安全圆；环外压暗到 25% 只是为了说明"这部分会被丢掉"，像素仍在，所以能一眼看出有没有主体被切。 |
| `preview-squircle-432.png` | 方圆（超椭圆 n=4）掩模，同样的两个环。 |
| `preview-monochrome-432.png` | 单色层按系统染色（`#EDE6F5`）后的样子，验证线框在纯 alpha 下是否还立得住。 |
| `preview-original-ratio.png` | 左：原图 1920×1920 等比直出；右：同一张图缩放后套上圆形掩模。用来核对"比例没变、主体没少"。 |

轮廓外面那圈压暗区域如果压到了主体边缘，说明 fit 失败了——现在这四张图里没有任何
一个不透明像素落在暗区，脚本也会断言这一点：

```
  mdpi     canvas 108px  alpha bbox (30, 29, 77, 78)    max radius 32.53dp (limit 33.00dp)  outside 0
  hdpi     canvas 162px  alpha bbox (46, 45, 115, 117)  max radius 32.54dp (limit 33.00dp)  outside 0
  xhdpi    canvas 216px  alpha bbox (62, 59, 153, 156)  max radius 32.89dp (limit 33.00dp)  outside 0
  xxhdpi   canvas 324px  alpha bbox (93, 89, 230, 234)  max radius 32.78dp (limit 33.00dp)  outside 0
  xxxhdpi  canvas 432px  alpha bbox (124, 120, 307, 312) max radius 32.89dp (limit 33.00dp) outside 0
```

`max radius` 是把生成好的 PNG 里**每个 alpha > 0 的像素**到画布中心的距离取最大值，
再换算成 dp；`outside` 是落在 33dp 之外的像素个数，必须是 0。距离 72dp 圆形掩模边界
还剩 **3.11dp**。

## 重新生成

```bash
# 当前产物（fit，主体零裁切）
python tools/branding/make_app_icons.py --no-crop

# 会话 24 的旧构图（cover，中心裁切），只为复现旧版本
python tools/branding/make_app_icons.py

# 换源图（会先复制成 source-mascot.jpg 再基于它生成）
python tools/branding/make_app_icons.py --no-crop --import-source <新图.jpg>
```

脚本会重写全部 PNG 与 `mipmap-anydpi-v26/ic_launcher*.xml`，并在结束时断言：
尺寸正确、前景四角 alpha=0、单色层只有纯黑与透明、背景层不透明、
主体全部落在 66dp 安全圆内、`mascot-original.png` 与源图逐像素相同。
`app/src/main/AndroidManifest.xml` 与 `colors.xml` 由人工维护，脚本不改。

## 文件与密度对照

自适应图标（108dp 画布）：

| 密度 | 背景 | 前景 | 单色 | 传统方形 (48dp) | 传统圆形 (48dp) |
| --- | --- | --- | --- | --- | --- |
| mdpi | 108 px | 108 px | 108 px | 48 px | 48 px |
| hdpi | 162 px | 162 px | 162 px | 72 px | 72 px |
| xhdpi | 216 px | 216 px | 216 px | 96 px | 96 px |
| xxhdpi | 324 px | 324 px | 324 px | 144 px | 144 px |
| xxxhdpi | 432 px | 432 px | 432 px | 192 px | 192 px |

- `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`：`<adaptive-icon>` 描述符。
- `mipmap-*/ic_launcher_background.png`：108dp 不透明背景（底色 + 线框）。
- `mipmap-*/ic_launcher_foreground.png`：108dp 透明底主体，四周留空。
- `mipmap-*/ic_launcher_monochrome.png`：纯黑线框 + 透明底，由系统染色。
- `mipmap-*/ic_launcher.png` / `ic_launcher_round.png`：API 26 以下的方形/圆形图标，
  取自适应画布中央 72dp 可见窗口缩放，和启动器真正显示的一致。

本目录（`docs/assets/branding/`）：

| 文件 | 规格 | 用途 |
| --- | --- | --- |
| `mascot-original.png` | 1920×1920，无 alpha | 原图等比直出，零处理（只做 JPEG→PNG 的无损再编码） |
| `mascot-original-1024.png` | 1024×1024 | 等比缩放版，网页/文档/分享 |
| `mascot-original-512.png` | 512×512 | 等比缩放版，小图位 |
| `app-icon-512.png` | 512×512，不透明 | Play Store 图标（72dp 可见窗口） |
| `app-icon-1024.png` | 1024×1024，不透明 | README / 社交分享 |
| `preview-circle-432.png` | 432×432 | 圆形掩模验收 |
| `preview-squircle-432.png` | 432×432 | 方圆掩模验收 |
| `preview-monochrome-432.png` | 432×432 | 单色层渲染验收 |
| `preview-original-ratio.png` | 1080×660 | 原比例直出 vs 掩模后，对比图 |
| `source-mascot.jpg` | 1920×1920 | 源美术副本，供脚本复现 |

**不想要启动器裁切的地方用 `mascot-original*.png`**：那三张就是原图比例，没有掩模、
没有留白、没有重采样之外的任何处理。

这些位图默认会被仓库根 `.gitignore` 的 `*.png` / `*.jpg` 和 `/docs/` 规则挡住，
`.gitignore` 末尾有专门的例外块放开本目录，以及 `app/src/main/res/mipmap-*/ic_launcher*.png`。

## 已知取舍

- **源美术本身就是裁切图 → 边缘复制延展解决**：主体在左侧与下侧越出画布（前景 bbox
  的 x 从 0、y 到 1920）。fit 最初把整幅画缩进安全圆后，这条直边落在掩模内部，看起来
  像一块方形色块。现在的处理是 `extend_subject`：把触边的最外一行/列像素（ sealed
  掉画框切出的两个缺口后）**原样向外复制**，直到延展边界越过 108dp 画布——角色
  尺寸一格不变（fit 几何仍按原始 bbox 算），直边则被推出掩模。验收里有一段专门的
  "无可见直边"断言：可见区带内采样，与边缘色的偏差必须 ≤ 0.5%、alpha 必须 ≥ 250。
  复制的是源图自己的像素，不算凭空造像素。
- 主体只占 45.4dp / 108dp（约 42% 画布、63% 的 72dp 可见区）。这是"不裁主体 + 66dp
  安全圆"的数学结果，不是排版失误：主体要完整落进安全圆，就没有多余空间。
- 前景 PNG 的透明区保留的是 `#242424` 像素（alpha=0），缩放重采样时边缘不会拉黑边；
  代价是它只适合叠在深色上，不能当"白底可直接用"的通用素材。
- 背景线框是"右侧晶体 + 两份摆位"的再构图，不是原图线框的原位复现：在 fit 尺度下
  原图线框正好落在主体外沿，塞进安全圆就会和主体重叠，只能重排。提取件的开口断头
  基本落在主体背后；单色层不用提取件（见上，用绘制的闭合宝石）。
- 重跑 cover 模式会把 XML 改回 `@color/ic_launcher_background`，并留下不再被引用的
  `mipmap-*/ic_launcher_background.png`（可以删，脚本不主动删文件）。

## 主题配色的同源校准

「看板娘 · 淡紫工控」主题（`ThemeCatalog.mascotLavender`）的每一个色值同样来自
这张源图的实测，校准与对比度证明在 `tools/branding/sample_palette.py`——
改源图或改主题色前先跑它，全部 WCAG 对比度过线（exit 0）才算数。
