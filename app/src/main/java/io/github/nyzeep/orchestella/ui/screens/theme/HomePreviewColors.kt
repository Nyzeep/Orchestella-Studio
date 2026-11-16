package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * The exact roles the miniature home page paints. Pure and JVM-testable: the
 * caller passes only the resolved [AppTheme] plus the page's presentation
 * flags, so the preview cannot invent a second palette or merge custom slots
 * on its own.
 */
@Immutable
data class HomePreviewColors(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceHigh: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimary: Int,
    val accent: Int,
    val ringTrack: Int,
    val navFill: Int,
)

/**
 * Maps the live theme through the same Oe3 roles the real page uses. Pure
 * black is the single override and only applies to dark palettes. Surface
 * opacity is composited into the surface roles so the wallpaper slider is
 * visible in the miniature instead of repainting opaque cards.
 */
fun homePreviewColors(
    theme: AppTheme,
    pureBlack: Boolean = false,
    opacityPercent: Int = SurfaceOpacity.DEFAULT_PERCENT,
    glass: GlassOption = GlassOption.None,
): HomePreviewColors {
    val tokens = Oe3Colors.resolve(theme, pureBlack)
    val glassTokens = Oe3Glass.resolve(theme, glass.level, pureBlack)
    return HomePreviewColors(
        background = tokens.background,
        surface = SurfaceOpacity.applyOpacity(tokens.surface, opacityPercent),
        surfaceRaised = SurfaceOpacity.applyOpacity(tokens.surfaceRaised, opacityPercent),
        surfaceHigh = SurfaceOpacity.applyOpacity(tokens.surfaceHigh, opacityPercent),
        onSurface = tokens.onSurface,
        onSurfaceVariant = tokens.onSurfaceVariant,
        primary = tokens.primary,
        primaryContainer = tokens.primaryContainer,
        onPrimary = tokens.onPrimary,
        accent = tokens.accent,
        ringTrack = tokens.surfaceHigh,
        navFill = ThemeColors.blend(tokens.background, glassTokens.tint, glassTokens.tintAlpha),
    )
}
