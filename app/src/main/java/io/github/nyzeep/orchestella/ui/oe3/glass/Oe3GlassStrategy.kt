package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.ui.unit.Dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassTokens

/**
 * The draw path a glass surface takes for a level in the current environment
 * (spec §2.6, ticket UI3-10). Pure data, so the decision is JVM-testable and
 * the composable only renders.
 */
sealed interface Oe3GlassRender {

    /**
     * Opaque surface, no blur, no scrim: the [Oe3GlassLevel.None] level and
     * the pixel-stable default the shell shipped before the glass layer.
     */
    data object Solid : Oe3GlassRender

    /**
     * Real backdrop blur through Haze 1.x. The palette numbers come straight
     * from `Oe3Glass.resolve` — this class carries them, it does not own them.
     */
    data class Blurred(
        val blurRadius: Dp,
        val tintArgb: Int,
        val tintAlpha: Float,
        val backgroundArgb: Int,
        val fallbackTintArgb: Int,
    ) : Oe3GlassRender

    /**
     * Glass was requested but cannot render (API < 31, no RenderEffect, or
     * the layer was switched off): an almost-solid surface plus the 24dp top
     * scrim, so the chrome still reads as a surface instead of a black hole.
     */
    data object Fallback : Oe3GlassRender
}

/**
 * Branching only; the blur/tint numbers stay in `Oe3Glass.resolve` so there
 * is exactly one owner of the spec values.
 */
object Oe3GlassStrategy {

    /** First API level with `RenderEffect`, i.e. with real backdrop blur. */
    const val MIN_REAL_BLUR_API = 31

    /**
     * [hasRenderEffect] defaults to the API check; callers that probe the
     * graphics stack directly can override it.
     */
    fun supportsRealBlur(apiLevel: Int, hasRenderEffect: Boolean = apiLevel >= MIN_REAL_BLUR_API): Boolean =
        hasRenderEffect

    fun resolve(
        level: Oe3GlassLevel,
        enabled: Boolean,
        supportsRealBlur: Boolean,
        tokens: Oe3GlassTokens,
    ): Oe3GlassRender = when {
        // Explicit "no glass" stays fully opaque in every environment: it is
        // the behavior-preserving default the shell goldens pin.
        level == Oe3GlassLevel.None -> Oe3GlassRender.Solid
        // Requested glass that cannot render: scrimmed fallback, never a raw
        // translucent tint over an unblurred backdrop.
        !enabled || !supportsRealBlur -> Oe3GlassRender.Fallback
        else -> Oe3GlassRender.Blurred(
            blurRadius = tokens.blurRadius,
            tintArgb = tokens.tint,
            tintAlpha = tokens.tintAlpha,
            backgroundArgb = tokens.tint,
            fallbackTintArgb = tokens.fallbackTint,
        )
    }
}
