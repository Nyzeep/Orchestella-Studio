package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.OeTextScale
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import kotlin.math.roundToInt

/**
 * A deterministic page state for previews, semantics and screenshots. It reads
 * nothing from disk and never constructs an Activity, so the same theme and
 * scale always produce the same frame.
 */
internal fun themeStudioPreviewState(
    theme: AppTheme,
    pureBlack: Boolean = false,
    textScale: Float = 1f,
    cornerDp: Int = ThemeStore.DEFAULT_CORNER_RADIUS_DP,
): ThemeStudioState {
    val scaleMode = if (textScale == 1f) OeTextScaleMode.FollowSystem else OeTextScaleMode.Custom
    return ThemeStudioState(
        theme = theme,
        mode = when {
            theme.isFixed -> ThemeStore.Mode.FIXED
            theme.isDark -> ThemeStore.Mode.DARK
            else -> ThemeStore.Mode.LIGHT
        },
        pureBlack = pureBlack,
        cornerDp = cornerDp,
        textScaleMode = scaleMode,
        textScalePercent = if (scaleMode == OeTextScaleMode.Custom) {
            OeTextScale.clampPercent((textScale * 100).roundToInt())
        } else {
            OeTextScale.DEFAULT_PERCENT
        },
    )
}
