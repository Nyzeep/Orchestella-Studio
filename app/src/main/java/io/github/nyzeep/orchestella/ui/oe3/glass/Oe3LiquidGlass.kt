package io.github.nyzeep.orchestella.ui.oe3.glass

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass

/**
 * Applies AGSL edge-bezel refraction onto the blurred glass backing layer on API 33+,
 * keeping foreground labels outside the shader pass so text stays crisp.
 */
@Composable
internal fun rememberOe3Refraction(enabled: Boolean, shape: Shape, isDark: Boolean): Modifier {
    if (!enabled ||
        Build.VERSION.SDK_INT < Oe3GlassStrategy.MIN_REFRACTION_API ||
        !LocalView.current.isHardwareAccelerated
    ) {
        return Modifier
    }
    val direction = LocalLayoutDirection.current
    val refraction = remember(shape, direction, isDark) {
        Oe3LiquidGlass.create(shape, direction, isDark)
    } ?: return Modifier
    return Modifier.graphicsLayer { renderEffect = refraction.effectFor(size, this) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class Oe3LiquidGlass private constructor(
    private val shader: RuntimeShader,
    private val shape: Shape,
    private val direction: LayoutDirection,
    private val isDark: Boolean,
) {
    private var layerSize = Size.Zero
    private var layerDensity = 0f
    private var effect: RenderEffect? = null
    private var unavailable = false

    fun effectFor(size: Size, density: Density): RenderEffect? {
        if (unavailable || !size.width.isFinite() || !size.height.isFinite() ||
            size.width < 1f || size.height < 1f
        ) {
            return null
        }
        if (layerSize == size && layerDensity == density.density) return effect
        layerSize = size
        layerDensity = density.density

        val outline = shape.createOutline(size, direction, density)
        val radii = when (outline) {
            is Outline.Rectangle -> null
            is Outline.Rounded -> outline.roundRect
            is Outline.Generic -> return null.also { effect = null }
        }
        val highlight = if (isDark) Oe3Glass.DARK_RIM_HIGHLIGHT_ALPHA else Oe3Glass.LIGHT_RIM_HIGHLIGHT_ALPHA
        effect = shaderOrNull {
            shader.setFloatUniform("extent", size.width, size.height)
            shader.setFloatUniform(
                "radii",
                radii?.topLeftCornerRadius?.x ?: 0f,
                radii?.topRightCornerRadius?.x ?: 0f,
                radii?.bottomLeftCornerRadius?.x ?: 0f,
                radii?.bottomRightCornerRadius?.x ?: 0f,
            )
            with(density) {
                shader.setFloatUniform("bezel", Oe3Glass.refractionWidth.toPx())
                shader.setFloatUniform("shift", Oe3Glass.refractionDisplacement.toPx())
                shader.setFloatUniform("rim", Oe3Glass.rimWidth.toPx())
            }
            shader.setFloatUniform("highlight", highlight * Oe3Glass.REFRACTION_HIGHLIGHT_GAIN)
            AndroidRenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
        }
        unavailable = effect == null
        return effect
    }

    companion object {
        fun create(shape: Shape, direction: LayoutDirection, isDark: Boolean): Oe3LiquidGlass? =
            shaderOrNull { Oe3LiquidGlass(RuntimeShader(LENS_PROGRAM), shape, direction, isDark) }
    }
}

private inline fun <T> shaderOrNull(block: () -> T): T? = try {
    block()
} catch (failure: RuntimeException) {
    Log.w("Oe3Glass", "Refraction unavailable, falling back to L1 blur", failure)
    null
} catch (failure: LinkageError) {
    Log.w("Oe3Glass", "Native shader unavailable, falling back to L1 blur", failure)
    null
}

private const val LENS_PROGRAM = """
    uniform shader backdrop;
    uniform float2 extent;
    uniform float4 radii;
    uniform float bezel;
    uniform float shift;
    uniform float rim;
    uniform float highlight;

    half4 main(float2 pixel) {
        float2 halfSize = extent * 0.5;
        float2 p = pixel - halfSize;
        float radius = p.y < 0.0
            ? (p.x < 0.0 ? radii.x : radii.y)
            : (p.x < 0.0 ? radii.z : radii.w);
        radius = min(radius, min(halfSize.x, halfSize.y));
        float2 q = abs(p) - halfSize + radius;
        float2 roundedCorner = max(q, 0.0);
        float distanceToEdge = length(roundedCorner) + min(max(q.x, q.y), 0.0) - radius;
        float depth = max(-distanceToEdge, 0.0);
        if (depth >= bezel) {
            return backdrop.eval(pixel);
        }

        float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
        float2 normal = dot(roundedCorner, roundedCorner) > 0.0001
            ? normalize(roundedCorner) * s
            : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
        float lens = 1.0 - smoothstep(0.0, bezel, depth);
        float2 samplePoint = clamp(pixel - normal * shift * lens, float2(0.5), extent - 0.5);
        half4 sampleColor = backdrop.eval(samplePoint);
        float incidence = max(0.0, dot(normal, float2(-0.6, -0.8)));
        half gloss = half(highlight * incidence * (1.0 - smoothstep(0.0, rim, depth)));
        return half4(mix(sampleColor.rgb, half3(sampleColor.a), gloss), sampleColor.a);
    }
"""
