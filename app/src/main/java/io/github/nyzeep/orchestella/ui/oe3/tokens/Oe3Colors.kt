package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Oe3 color roles. Structural hierarchy comes from tonal steps
 * ([background] → [surface] → [surfaceRaised] → [surfaceHigh]), with
 * [surfaceFloating] dedicated to floating/docked navigation chrome so the
 * dock always reads as distinct from adjacent cards.
 *
 * All values are opaque ARGB except [divider], which carries the explicit
 * 14% alpha so it can be drawn over any of the surface steps.
 */
@Immutable
data class Oe3ColorTokens(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceHigh: Int,
    val surfaceFloating: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val divider: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimary: Int,
    val accent: Int,
    val error: Int,
    val onError: Int,
    /** v4（UI-09）：删除徽标的容器底，Material 3 baseline errorContainer。 */
    val errorContainer: Int,
    val onErrorContainer: Int,
)

/**
 * Maps the user's resolved [AppTheme] onto the Oe3 roles. Catalog presets supply
 * the hue for the perceptual CIE L* tonal ramp; custom slots keep their authored
 * page and card colors. Pure black overrides dark palettes.
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
    private const val PURE_BLACK_FLOATING = 0xFF1A1A1C.toInt()

    private const val DARK_PAGE_TONE_LIMIT = 12.0
    private const val DARK_CARD_TONE_STEP = 9.0
    private const val DARK_CONTROL_TONE_STEP = 6.0
    private const val LIGHT_PAGE_TONE_STEP = 4.0
    private const val LIGHT_CONTROL_TONE_STEP = 4.0
    private const val LIGHT_DOCK_TONE_STEP = 3.0
    private const val DARK_PILL_TINT = 0.14f
    private const val DARK_PILL_TONE_SEPARATION = 4.0

    private const val ERROR_DARK = 0xFFFFB4AB.toInt()
    private const val ERROR_LIGHT = 0xFFBA1A1A.toInt()

    // Material 3 baseline errorContainer / onErrorContainer, matched to the
    // brightness convention above (UI-09 delete badge).
    private const val ERROR_CONTAINER_DARK = 0xFF8C1D18.toInt()
    private const val ERROR_CONTAINER_LIGHT = 0xFFF9DEDC.toInt()
    private const val ON_ERROR_CONTAINER_DARK = 0xFFF9DEDC.toInt()
    private const val ON_ERROR_CONTAINER_LIGHT = 0xFF410E0B.toInt()

    fun resolve(theme: AppTheme, pureBlack: Boolean = false): Oe3ColorTokens {
        val black = pureBlack && theme.isDark
        val custom = theme.id == AppTheme.CUSTOM_ID || theme.id.startsWith(CustomThemeSlot.ID_PREFIX)
        val ramp = when {
            black -> SurfaceRamp(
                page = PURE_BLACK_BACKGROUND,
                card = PURE_BLACK_SURFACE,
                block = PURE_BLACK_RAISED,
                inset = PURE_BLACK_HIGH,
                floating = PURE_BLACK_FLOATING,
            )
            custom -> customRamp(theme)
            theme.isDark -> darkRamp(theme)
            else -> lightRamp(theme)
        }

        // The inset has the lowest contrast for catalog palettes; custom slots
        // can have arbitrary brightness so their chosen card is the reference.
        val textBackground = if (custom && !black) ramp.card else ramp.inset
        val onSurface = ThemeColors.readable(theme.text, textBackground)
        val onSurfaceVariant = ThemeColors.readable(theme.textDim, textBackground)
        // AppTheme carries no error role; use the Material 3 baseline red for
        // the current brightness so error copy stays legible on every palette.
        val error = if (theme.isDark) ERROR_DARK else ERROR_LIGHT

        return Oe3ColorTokens(
            background = ramp.page,
            surface = ramp.card,
            surfaceRaised = ramp.block,
            surfaceHigh = ramp.inset,
            surfaceFloating = ramp.floating,
            onSurface = onSurface,
            onSurfaceVariant = onSurfaceVariant,
            divider = (onSurface and 0x00FFFFFF) or (DIVIDER_ALPHA shl 24),
            primary = theme.primary,
            primaryContainer = ThemeColors.blend(
                ramp.card,
                theme.primary,
                if (theme.isDark) 0.22f else 0.14f,
            ),
            onPrimary = ThemeColors.onColor(theme.primary),
            accent = theme.accent,
            error = error,
            onError = ThemeColors.onColor(error),
            errorContainer = if (theme.isDark) ERROR_CONTAINER_DARK else ERROR_CONTAINER_LIGHT,
            onErrorContainer = if (theme.isDark) ON_ERROR_CONTAINER_DARK else ON_ERROR_CONTAINER_LIGHT,
        )
    }

    /** Sunken active pill fill for dark mode; inset fill for light mode. */
    internal fun navigationSelection(colors: Oe3ColorTokens, isDark: Boolean): Int {
        if (!isDark) return colors.surfaceHigh
        val tinted = ThemeColors.blend(colors.background, colors.primary, DARK_PILL_TINT)
        val toneLimit = (lightness(colors.surfaceFloating) - DARK_PILL_TONE_SEPARATION).coerceAtLeast(0.0)
        return if (lightness(tinted) <= toneLimit) tinted else withTone(tinted, toneLimit)
    }

    private data class SurfaceRamp(val page: Int, val card: Int, val block: Int, val inset: Int, val floating: Int)

    private fun darkRamp(theme: AppTheme): SurfaceRamp {
        val pageTone = lightness(theme.background).coerceAtMost(DARK_PAGE_TONE_LIMIT)
        val cardTone = pageTone + DARK_CARD_TONE_STEP
        val blockTone = cardTone + DARK_CONTROL_TONE_STEP
        val block = withTone(theme.surface, blockTone)
        return SurfaceRamp(
            page = withTone(theme.background, pageTone),
            card = withTone(theme.surface, cardTone),
            block = block,
            inset = withTone(theme.surface, blockTone + DARK_CONTROL_TONE_STEP),
            floating = block,
        )
    }

    private fun lightRamp(theme: AppTheme): SurfaceRamp {
        val cardTone = lightness(theme.surface)
        return SurfaceRamp(
            page = withTone(theme.background, (cardTone - LIGHT_PAGE_TONE_STEP).coerceAtLeast(0.0)),
            card = theme.surface,
            block = withTone(theme.surface, (cardTone - LIGHT_CONTROL_TONE_STEP).coerceAtLeast(0.0)),
            inset = withTone(theme.surface, (cardTone - 2.0 * LIGHT_CONTROL_TONE_STEP).coerceAtLeast(0.0)),
            floating = withTone(theme.surface, (cardTone - LIGHT_DOCK_TONE_STEP).coerceAtLeast(0.0)),
        )
    }

    private fun customRamp(theme: AppTheme): SurfaceRamp {
        val cardTone = lightness(theme.surface)
        val step = if (theme.isDark) DARK_CONTROL_TONE_STEP else LIGHT_CONTROL_TONE_STEP
        val preferredStep = if (theme.isDark) step else -step
        val insetTone = cardTone + 2.0 * preferredStep
        val signedStep = if (insetTone in 0.0..100.0) preferredStep else -preferredStep
        val block = withTone(theme.surface, cardTone + signedStep)
        return SurfaceRamp(
            page = theme.background,
            card = theme.surface,
            block = block,
            inset = withTone(theme.surface, cardTone + 2.0 * signedStep),
            floating = block,
        )
    }

    /** CIE L* from the same relative luminance used for WCAG contrast. */
    private fun lightness(color: Int): Double {
        val luminance = ThemeColors.luminance(color)
        return if (luminance <= 216.0 / 24389.0) {
            luminance * (24389.0 / 27.0)
        } else {
            116.0 * cbrt(luminance) - 16.0
        }
    }

    /** Mix towards a neutral endpoint until the source hue reaches the requested tone. */
    private fun withTone(color: Int, tone: Double): Int {
        val source = color or 0xFF000000.toInt()
        val currentLuminance = ThemeColors.luminance(source)
        val targetLuminance = if (tone <= 8.0) tone * (27.0 / 24389.0) else ((tone + 16.0) / 116.0).pow(3)
        if (currentLuminance == targetLuminance) return source
        val lighten = targetLuminance > currentLuminance
        val endpoint = if (lighten) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        var low = 0f
        var high = 1f
        repeat(12) {
            val fraction = (low + high) / 2f
            val candidate = ThemeColors.blend(source, endpoint, fraction)
            val candidateLuminance = ThemeColors.luminance(candidate)
            if ((candidateLuminance < targetLuminance) == lighten) low = fraction else high = fraction
        }
        val lowColor = ThemeColors.blend(source, endpoint, low)
        val highColor = ThemeColors.blend(source, endpoint, high)
        val lowError = abs(ThemeColors.luminance(lowColor) - targetLuminance)
        val highError = abs(ThemeColors.luminance(highColor) - targetLuminance)
        return if (lowError <= highError) lowColor else highColor
    }
}
