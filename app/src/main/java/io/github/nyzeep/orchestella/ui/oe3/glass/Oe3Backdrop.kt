package io.github.nyzeep.orchestella.ui.oe3.glass

import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel

@OptIn(ExperimentalHazeApi::class)
internal val BackdropInputScale = HazeInputScale.Fixed(0.5f)

private val ScrollEdgeProgression = HazeProgressive.verticalGradient(
    startIntensity = 1f,
    endIntensity = 0f,
    preferPerformance = true,
)

/**
 * Coordinates the single live backdrop blur chrome budget per screen between
 * a scrolled top header and the floating dock.
 */
@Stable
internal class Oe3GlassChromeState {
    private val scrollEdges = mutableStateListOf<Any>()

    val hasScrollEdge: Boolean get() = scrollEdges.isNotEmpty()

    fun ownsScrollEdge(owner: Any): Boolean = scrollEdges.lastOrNull() === owner

    fun addScrollEdge(owner: Any) {
        if (scrollEdges.none { it === owner }) {
            scrollEdges.add(owner)
        }
    }

    fun removeScrollEdge(owner: Any) {
        scrollEdges.remove(owner)
    }
}

internal val LocalOe3GlassChrome = staticCompositionLocalOf<Oe3GlassChromeState?> { null }

internal class Oe3ScrollEdgeGlass(
    val sourceModifier: Modifier,
    val effectModifier: Modifier,
)

/**
 * Provides a progressive top scroll-edge blur on API 31+ when content has scrolled
 * past 0dp and device gates allow real blur; returns null at rest or on fallback tiers.
 */
@Composable
internal fun rememberOe3ScrollEdgeGlass(strength: Float): Oe3ScrollEdgeGlass? {
    val chrome = LocalOe3GlassChrome.current
    val owner = remember { Any() }
    val tier = Oe3GlassStrategy.resolveTier(
        level = Oe3ThemeValues.info.glassLevel.let {
            if (it == Oe3GlassLevel.None) Oe3GlassLevel.None else Oe3GlassLevel.Standard
        },
        enabled = LocalOe3GlassEnabled.current && LocalView.current.isHardwareAccelerated,
        apiLevel = Build.VERSION.SDK_INT,
        isLowRamDevice = LocalOe3GlassLowRam.current,
        deviceCap = LocalOe3GlassDeviceCap.current,
        reducedMotion = Oe3ThemeValues.info.reducedMotion,
    )
    val radius = if (strength > 0f && tier.samplesBackdrop) {
        rememberGlassBlurRadius(Oe3Glass.scrollEdgeBlurRadius)
    } else {
        null
    }
    if (radius != null && chrome != null) {
        DisposableEffect(chrome, owner) {
            chrome.addScrollEdge(owner)
            onDispose { chrome.removeScrollEdge(owner) }
        }
    }
    if (radius == null || chrome?.ownsScrollEdge(owner) == false) return null

    val state = rememberOe3GlassState()
    val style = remember(radius) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = emptyList(),
            blurRadius = radius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.Transparent),
        )
    }
    return Oe3ScrollEdgeGlass(
        sourceModifier = Modifier.oe3GlassSource(state),
        effectModifier = Modifier.oe3BackdropHaze(
            state = state,
            style = style,
            opacity = strength.coerceIn(0f, 1f),
            gradient = ScrollEdgeProgression,
        ),
    )
}

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun rememberGlassBlurRadius(requested: Dp): Dp? {
    val density = LocalDensity.current
    return remember(requested, density.density) {
        if (Build.VERSION.SDK_INT < Oe3GlassStrategy.MIN_REAL_BLUR_API) {
            null
        } else {
            Oe3GlassBlurSafety.resolveBlurRadius(
                requested = requested,
                logNonFatal = { failure, _ -> Log.w("Oe3Glass", "Backdrop blur unavailable", failure) },
                probe = { radius ->
                    Oe3GlassBlurSafety.probeCreateBlurEffect(radius, density, BackdropInputScale.scale)
                },
            )
        }
    }
}

/** Shared Haze child modifier configuring half-resolution backdrop sampling. */
@OptIn(ExperimentalHazeApi::class)
internal fun Modifier.oe3BackdropHaze(
    state: HazeState,
    style: HazeStyle,
    opacity: Float = 1f,
    gradient: HazeProgressive? = null,
): Modifier = hazeChild(state, style) {
    inputScale = BackdropInputScale
    alpha = opacity
    progressive = gradient
}
