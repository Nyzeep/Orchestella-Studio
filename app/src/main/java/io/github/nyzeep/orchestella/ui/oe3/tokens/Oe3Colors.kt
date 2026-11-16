package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * Oe3 color roles. Structural hierarchy comes from tonal steps
 * ([background] → [surface] → [surfaceRaised] → [surfaceHigh]); there is no
 * outline role, because the design language forbids card edges.
 *
 * All values are opaque ARGB except [divider], which carries the explicit
 * 14% alpha so it can be drawn over any of the three surface steps.
 */
@Immutable
data class Oe3ColorTokens(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceHigh: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val divider: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimary: Int,
    val error: Int,
    val onError: Int,
)

/**
 * Maps the user's resolved [AppTheme] onto the Oe3 roles. The palette stays the
 * user's: surfaces, ink and accent come from the theme, so all catalog presets
 * and custom slots keep their identity. Pure black is the one deliberate
 * override, and only for dark palettes.
 *
 * Mode selection already happened in `ThemeStore.resolve()`; [AppTheme.isDark]
 * is the single brightness source, so there is no second mode flag that could
 * disagree with the palette.
 */
object Oe3Colors {

    private const val DIVIDER_ALPHA = 36 // 14% of 255

    private const val PURE_BLACK_BACKGROUND = 0xFF000000.toInt()
    private const val PURE_BLACK_SURFACE = 0xFF0B0B0C.toInt()
    private const val PURE_BLACK_RAISED = 0xFF131315.toInt()
    private const val PURE_BLACK_HIGH = 0xFF1C1C1F.toInt()

    private const val ERROR_DARK = 0xFFFFB4AB.toInt()
    private const val ERROR_LIGHT = 0xFFBA1A1A.toInt()

    fun resolve(theme: AppTheme, pureBlack: Boolean = false): Oe3ColorTokens {
        val black = pureBlack && theme.isDark

        val background = if (black) PURE_BLACK_BACKGROUND else theme.background
        val surface = if (black) PURE_BLACK_SURFACE else theme.surface

        val raised = if (black) {
            PURE_BLACK_RAISED
        } else {
            // Dark palettes lift with light ink, light palettes settle with dark ink.
            ThemeColors.blend(surface, theme.text, if (theme.isDark) 0.025f else 0.06f)
        }
        val high = if (black) {
            PURE_BLACK_HIGH
        } else {
            ThemeColors.blend(raised, theme.text, if (theme.isDark) 0.045f else 0.035f)
        }

        val onSurface = theme.text
        val onSurfaceVariant = ThemeColors.readable(theme.textDim, surface)
        val error = if (theme.isDark) ERROR_DARK else ERROR_LIGHT

        return Oe3ColorTokens(
            background = background,
            surface = surface,
            surfaceRaised = raised,
            surfaceHigh = high,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            divider = (onSurface and 0x00FFFFFF) or (DIVIDER_ALPHA shl 24),
            primary = theme.primary,
            primaryContainer = ThemeColors.blend(
                surface,
                theme.primary,
                if (theme.isDark) 0.22f else 0.14f,
            ),
            onPrimary = ThemeColors.onColor(theme.primary),
            error = error,
            onError = ThemeColors.onColor(error),
        )
    }
}
