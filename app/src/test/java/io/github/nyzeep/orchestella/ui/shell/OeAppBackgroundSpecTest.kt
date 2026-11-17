package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-02 纯函数切片：壳层背景的三态判定与「界面表面不透明度」的映射。
 *
 * 三态顺序在规格里是有意的：用户显式选的壁纸压过纯黑开关，纯黑压过主题渐变。
 */
class OeAppBackgroundSpecTest {

    @Test
    fun wallpaperWinsThenPureBlackThenTheThemeGradient() {
        assertEquals(
            OeAppBackgroundMode.Wallpaper("content://media/1"),
            OeAppBackgroundSpec.resolve("content://media/1", pureBlack = false),
        )
        assertEquals(
            OeAppBackgroundMode.Wallpaper("content://media/1"),
            OeAppBackgroundSpec.resolve("content://media/1", pureBlack = true),
        )
        assertEquals(
            OeAppBackgroundMode.Solid,
            OeAppBackgroundSpec.resolve(backgroundUri = null, pureBlack = true),
        )
        assertEquals(
            OeAppBackgroundMode.Gradient,
            OeAppBackgroundSpec.resolve(backgroundUri = null, pureBlack = false),
        )
    }

    @Test
    fun blankUrisCountAsNoWallpaper() {
        assertEquals(
            OeAppBackgroundMode.Gradient,
            OeAppBackgroundSpec.resolve(backgroundUri = "", pureBlack = false),
        )
        assertEquals(
            OeAppBackgroundMode.Solid,
            OeAppBackgroundSpec.resolve(backgroundUri = "   ", pureBlack = true),
        )
    }

    @Test
    fun surfaceAlphaFollowsTheExistingOpacityPreferenceAndItsClamp() {
        // 复用 SurfaceOpacity 的 55..100 区间：不另造一套偏好。
        assertEquals(1f, OeAppBackgroundSpec.surfaceAlpha(100), 0.0001f)
        assertEquals(0.55f, OeAppBackgroundSpec.surfaceAlpha(55), 0.0001f)
        assertEquals(0.55f, OeAppBackgroundSpec.surfaceAlpha(10), 0.0001f)
        assertEquals(1f, OeAppBackgroundSpec.surfaceAlpha(120), 0.0001f)
    }
}
