package io.github.nyzeep.orchestella.ui.oe3.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.rememberHazeState
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassTokens

/**
 * Global kill switch for the glass layer (ticket UI3-10). The default keeps
 * glass on; the composition above [Oe3GlassSurface] flips it to `false` when
 * the system asks for reduced dynamics or power saving (UI3-12 wires the
 * runtime signals). Turning the layer off routes every non-None level to the
 * scrimmed fallback surface, never to raw translucency over an unblurred
 * backdrop.
 */
val LocalOe3GlassEnabled = staticCompositionLocalOf { true }

/**
 * Fallback decoration constants (spec §2.6): the almost-solid surface plus
 * the top scrim that keeps the chrome readable when backdrop blur cannot
 * render. `SURFACE_ALPHA` mirrors the 245/255 alpha `Oe3Glass.resolve` bakes
 * into [Oe3GlassTokens.fallbackTint]; both spellings exist so the scrim math
 * and the token layer stay honest about the same 96%.
 */
@Immutable
object Oe3GlassFallback {
    const val SURFACE_ALPHA = 0.96f
    const val SURFACE_ALPHA_8BIT = 245
    val SCRIM_HEIGHT: Dp = 24.dp
    const val SCRIM_TOP_ALPHA = 0.7f
}

/**
 * The liquid-glass surface (spec §2.6, ticket UI3-10).
 *
 * [level] selects the draw path; the palette numbers come from the composed
 * `Oe3Theme` (its resolved glass tokens), so callers must compose the surface
 * inside a theme whose `glassLevel` matches [level] — the nav bar passes the
 * theme's own level. A mismatched call deliberately renders [Oe3GlassRender.Solid]:
 * an inconsistent caller must not get half-configured blur.
 *
 * Paths:
 * - `None`: opaque surface, no blur — pixel-identical to the pre-glass shell.
 * - `Standard` / `Ultra` with real blur available (API 31+, RenderEffect, a
 *   [state] provided, layer enabled): the Haze 1.x child effect, one layer,
 *   bounded by this surface's node (the bar), so the blurred region never
 *   exceeds the chrome that composes it.
 * - Otherwise: the [Oe3GlassFallback] surface — α0.96 fill plus a 24dp top scrim.
 */
@Composable
fun Oe3GlassSurface(
    level: Oe3GlassLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    state: HazeState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = Oe3ThemeValues.glass
    val info = Oe3ThemeValues.info
    val enabled = LocalOe3GlassEnabled.current

    val render = if (level != info.glassLevel) {
        Oe3GlassRender.Solid
    } else {
        Oe3GlassStrategy.resolve(
            level = level,
            enabled = enabled,
            supportsRealBlur = Oe3GlassStrategy.supportsRealBlur(Build.VERSION.SDK_INT) && state != null,
            tokens = tokens,
        )
    }

    when (render) {
        Oe3GlassRender.Solid -> Box(
            modifier
                .clip(shape)
                .background(Color(tokens.tint)),
            content = content,
        )

        is Oe3GlassRender.Blurred -> {
            val state = requireNotNull(state) { "Blurred glass requires the shared glass state" }
            val style = remember(render) { render.toHazeStyle(tokens) }
            Box(
                modifier
                    .clip(shape)
                    .hazeChild(state = state, style = style),
                content = content,
            )
        }

        Oe3GlassRender.Fallback -> Box(
            modifier
                .clip(shape)
                .background(Color(tokens.fallbackTint)),
        ) {
            Scrim(tokens = tokens)
            content()
        }
    }
}

/** Top scrim of the fallback path: 24dp of the surface colour fading out. */
@Composable
private fun Scrim(tokens: Oe3GlassTokens) {
    val brush = remember(tokens) {
        val base = Color(tokens.tint)
        Brush.verticalGradient(
            0f to base.copy(alpha = Oe3GlassFallback.SCRIM_TOP_ALPHA),
            1f to base.copy(alpha = 0f),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Oe3GlassFallback.SCRIM_HEIGHT)
            .background(brush = brush),
    )
}

private fun Oe3GlassRender.Blurred.toHazeStyle(tokens: Oe3GlassTokens): HazeStyle = HazeStyle(
    backgroundColor = Color(backgroundArgb),
    tints = listOf(HazeTint(Color(tintArgb).copy(alpha = tintAlpha))),
    blurRadius = blurRadius,
    fallbackTint = HazeTint(Color(fallbackTintArgb)),
)

/**
 * Marks the content behind the floating chrome as the blur source — the single
 * source marker of the whole app. With a `null` state (previews, tests, or any
 * composition that did not opt into glass) it is a no-op, so the shell can
 * keep the call permanently.
 */
fun Modifier.oe3GlassSource(state: HazeState?): Modifier {
    if (state == null) return this
    return this.haze(state)
}

/** Shared [HazeState] for the shell's content-to-chrome glass wiring. */
@Composable
fun rememberOe3GlassState(): HazeState = rememberHazeState()
