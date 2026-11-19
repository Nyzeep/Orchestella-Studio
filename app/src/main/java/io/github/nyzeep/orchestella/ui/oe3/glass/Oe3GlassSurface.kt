package io.github.nyzeep.orchestella.ui.oe3.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
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
 * UI-06：设备是否为低内存（`ActivityManager.isLowRamDevice`）。低内存设备上
 * 模糊的离屏采样不可负担，能力判定把它作为独立硬闸（L1 及以上禁用）。由
 * Activity 探测后提供；预览/测试默认 `false`（非低内存）。
 */
val LocalOe3GlassLowRam = staticCompositionLocalOf { false }

/**
 * UI-06：设备档位缓存（性能门降档持久化）。Activity 从 ThemeStore 读出后
 * 提供（「始终」模式绕过降档缓存）；预览/测试默认 [GlassDeviceCap.Unlimited]。
 */
val LocalOe3GlassDeviceCap = staticCompositionLocalOf { GlassDeviceCap.Unlimited }

// Lets visual tests exercise the tier renderer with fixed tier inputs.
internal val LocalOe3GlassTierOverride = staticCompositionLocalOf<GlassTier?> { null }

/**
 * Fallback decoration constants (spec §2.6 + v4 UI-06 L0 配方): the almost-solid
 * surface plus the top scrim that keeps the chrome readable when backdrop blur
 * cannot render. `SURFACE_ALPHA` mirrors the 245/255 alpha `Oe3Glass.resolve`
 * bakes into [Oe3GlassTokens.fallbackTint]; both spellings exist so the scrim
 * math and the token layer stay honest about the same 96%.
 *
 * UI-06 新增 L0 装饰常量（描边 + 噪点）：与既有常量并列，既有值不动（金样
 * 与 [Oe3GlassLocalTest] 钉定的契约保持）。
 */
@Immutable
object Oe3GlassFallback {
    const val SURFACE_ALPHA = 0.96f
    const val SURFACE_ALPHA_8BIT = 245
    val SCRIM_HEIGHT: Dp = 24.dp
    const val SCRIM_TOP_ALPHA = 0.7f

    /** UI-06 L0：1dp 顶部高光渐变描边的高度与峰值 alpha。 */
    val RIM_HEIGHT: Dp = 1.dp
    const val RIM_HIGHLIGHT_ALPHA = 0.28f

    /** UI-06 L0：噪点 alpha 峰值（spec noise 0.02）。 */
    const val NOISE_ALPHA = 0.02f
}

/**
 * The liquid-glass surface (spec §2.6, ticket UI3-10; tier ladder v4 UI-06).
 *
 * [level] selects the draw path; the palette numbers come from the composed
 * `Oe3Theme` (its resolved glass tokens), so callers must compose the surface
 * inside a theme whose `glassLevel` matches [level] — the nav bar passes the
 * theme's own level. A mismatched call deliberately renders [Oe3GlassRender.Solid]:
 * an inconsistent caller must not get half-configured blur.
 *
 * Paths (tier 判定见 [Oe3GlassStrategy.resolveTier]，顺序：全局开关 → 省电 →
 * 低内存 → API → 设备档位缓存):
 * - `Solid` ([GlassTier.Solid]): opaque surface, no blur — pixel-identical to
 *   the pre-glass shell.
 * - `L1` ([GlassTier.L1]): the Haze 1.x child effect at half-resolution input
 *   ([Oe3GlassBlurSafety]), walking the fallback ladder (28 → 16 → 8dp → L0).
 * - `L2` ([GlassTier.L2]): L1 + API 33+ AGSL rim refraction ([Oe3LiquidGlass])
 *   applied to the backdrop layer while foreground content renders crisply above.
 * - `L0` ([GlassTier.L0]): the fake-glass recipe — α0.96 fill, 24dp top scrim,
 *   1dp top highlight rim, 0.02 noise — the shared token set with the blur
 *   tiers so the degradation never reads as a different material.
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
    val resolvedTier = if (level != info.glassLevel) {
        GlassTier.Solid
    } else {
        LocalOe3GlassTierOverride.current ?: Oe3GlassStrategy.resolveTier(
            level = level,
            enabled = enabled && state != null,
            apiLevel = Build.VERSION.SDK_INT,
            isLowRamDevice = LocalOe3GlassLowRam.current,
            deviceCap = LocalOe3GlassDeviceCap.current,
            reducedMotion = info.reducedMotion,
        )
    }

    val render = when (resolvedTier) {
        GlassTier.Solid -> Oe3GlassRender.Solid
        GlassTier.L0 -> Oe3GlassRender.Fallback
        GlassTier.L1, GlassTier.L2 ->
            Oe3GlassStrategy.resolve(
                level = level,
                enabled = true,
                supportsRealBlur = true,
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
            val usableRadius = rememberGlassBlurRadius(render.blurRadius)
            if (usableRadius == null || state == null) {
                // 全部档位失败 / 非 IAE 异常 / 无源：放弃模糊，落到共享 token 的 L0 面
                FallbackSurface(
                    tokens = tokens,
                    modifier = modifier,
                    shape = shape,
                    content = content,
                )
            } else {
                val blurred = if (usableRadius == render.blurRadius) {
                    render
                } else {
                    render.copy(blurRadius = usableRadius)
                }
                val style = remember(blurred) { blurred.toHazeStyle(tokens) }
                val refraction = rememberOe3Refraction(
                    enabled = resolvedTier == GlassTier.L2,
                    shape = shape,
                    isDark = info.isDark,
                )
                Box(modifier.clip(shape)) {
                    // Apply refraction outermost so its RenderNode captures hazeChild's backdrop pixels.
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(refraction)
                            .oe3BackdropHaze(state = state, style = style),
                    )
                    content()
                }
            }
        }

        Oe3GlassRender.Fallback -> FallbackSurface(
            tokens = tokens,
            modifier = modifier,
            shape = shape,
            content = content,
        )
    }
}

/**
 * L0 回退面（假玻璃配方）：α0.96 填充 + 噪点 + 24dp 顶 scrim + 1dp 顶高光。
 * API<31/省电/低内存/性能门降档与「模糊探针全档失败」共用同一材质，保证
 * 任何降档路径的观感都由同一套 token 驱动。
 */
@Composable
private fun FallbackSurface(
    tokens: Oe3GlassTokens,
    modifier: Modifier,
    shape: Shape,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = remember(tokens) { GlassStyle.of(tokens) }
    Box(
        modifier
            .clip(shape)
            .background(Color(tokens.fallbackTint)),
    ) {
        NoiseOverlay()
        Scrim(tokens = tokens)
        TopRim(style = style)
        content()
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

/**
 * UI-06 L0 顶高光描边：沿顶边的 1dp 水平渐变，模拟玻璃上缘的高光反射。
 * alpha 以 [Oe3GlassFallback.RIM_HIGHLIGHT_ALPHA] 为峰值，两端收窄，克制到
 * 「看到质感但抢不过内容」的强度。Brush 按 style 记忆，无每帧重建。
 */
@Composable
private fun TopRim(style: GlassStyle) {
    val brush = remember(style) {
        val peak = style.rimHighlightAlpha
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = peak * 0.25f),
            0.4f to Color.White.copy(alpha = peak),
            1f to Color.White.copy(alpha = peak * 0.35f),
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(style.rimHeight)
            .background(brush = brush),
    )
}

/**
 * UI-06 L0 噪点：确定性噪点纹理以 repeated tile 平铺在整块背板上（α 峰值
 * 0.02 已烘进位图像素，绘制层不再叠加 alpha）。位图全局缓存，Brush 只在进入
 * 组合时创建一次，无逐帧成本。
 */
@Composable
private fun NoiseOverlay() {
    val brush = remember {
        ShaderBrush(
            ImageShader(
                image = Oe3GlassNoise.bitmap,
                tileModeX = TileMode.Repeated,
                tileModeY = TileMode.Repeated,
            ),
        )
    }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(brush = brush) })
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
