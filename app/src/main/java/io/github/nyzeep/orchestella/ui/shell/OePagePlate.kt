package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues

/**
 * 壳层背景模式的只读副本，供页面底片复用同一张壁纸/渐变。
 *
 * v4 (UI-02) 为了让底栏玻璃采到"壁纸 + 卡片"，删掉了每个页面的整页不透明底，
 * 背景改由 [Oe3App] 底部唯一一层 [OeAppBackground] 承担。副作用是任何"两页同时
 * 在屏"的转场都会透出下层：二级页 push 时进入页没有底片，整段 300 ms 都能看到
 * 上一页（用户反馈的"残影"）。本文件补回那块底片，且保持像素与根背景同源。
 */
val LocalOeShellBackgroundMode = staticCompositionLocalOf<OeAppBackgroundMode> {
    OeAppBackgroundMode.Gradient
}

/**
 * 页面底片：先铺一层不透明主题底色兜底（壁纸带 alpha 也不透底），再叠与根
 * 背景完全相同的 [OeAppBackground]。
 *
 * 两条纪律：
 * 1. 底片**永远不透明**，也不参与内容 alpha 动画——半透明底片会重新透出下层；
 * 2. 只在"可独立呈现的页面"外层各挂一块（pager 子页、栈目的地），`base` 层
 *    不重复挂载，避免同一帧画两遍整屏背景。
 *
 * W2-03：[wallpaperParallaxFraction] 供平移中的底片（栈 Moving 层）反向补偿
 * 壁纸偏移——传入"移动层进度 p"的取值函数，壁纸按 (1−p)×宽度反向平移，与根
 * 背景在屏幕坐标系保持对齐，仅内容随卡片横向滑入。静止底片传 null（默认），
 * 像素与旧实现逐字节一致。
 */
@Composable
internal fun OePagePlate(
    modifier: Modifier = Modifier,
    wallpaperParallaxFraction: (() -> Float)? = null,
) {
    val mode = LocalOeShellBackgroundMode.current
    Box(
        modifier
            .fillMaxSize()
            .background(Color(Oe3ThemeValues.colors.background)),
    ) {
        OeAppBackground(mode = mode, wallpaperParallaxFraction = wallpaperParallaxFraction)
    }
}
