package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.theme.AppTheme

/** Liquid-glass tiers; glass is only allowed on floating chrome. */
enum class Oe3GlassLevel { None, Standard, Ultra }

/**
 * Resolved glass parameters. [tint] is always opaque; apply [tintAlpha] at the
 * draw site so the stored palette alpha cannot be multiplied twice.
 * [fallbackTint] is the opaque, almost-solid fill used below API 31 (or without
 * RenderEffect): the bar must still read as a surface, never as a black hole.
 */
@Immutable
data class Oe3GlassTokens(
    val level: Oe3GlassLevel,
    val tint: Int,
    val tintAlpha: Float,
    val blurRadius: Dp,
    val fallbackTint: Int,
)

object Oe3Glass {

    private const val FALLBACK_ALPHA = 245 // 96% of 255

    fun resolve(theme: AppTheme, level: Oe3GlassLevel): Oe3GlassTokens {
        val fallback = (theme.surface and 0x00FFFFFF) or (FALLBACK_ALPHA shl 24)
        val glassTint = (theme.surfaceTranslucent and 0x00FFFFFF) or (0xFF shl 24)
        return when (level) {
            Oe3GlassLevel.None -> Oe3GlassTokens(
                level = level,
                tint = theme.surface,
                tintAlpha = 1f,
                blurRadius = 0.dp,
                fallbackTint = theme.surface,
            )
            Oe3GlassLevel.Standard -> Oe3GlassTokens(
                level = level,
                tint = glassTint,
                tintAlpha = 0.72f,
                blurRadius = 16.dp,
                fallbackTint = fallback,
            )
            Oe3GlassLevel.Ultra -> Oe3GlassTokens(
                level = level,
                tint = glassTint,
                tintAlpha = 0.55f,
                blurRadius = 28.dp,
                fallbackTint = fallback,
            )
        }
    }
}
