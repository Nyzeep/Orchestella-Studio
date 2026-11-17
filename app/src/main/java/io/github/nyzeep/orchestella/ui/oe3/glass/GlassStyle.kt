package io.github.nyzeep.orchestella.ui.oe3.glass

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassTokens
import kotlin.math.roundToInt

/**
 * UI-06 玻璃样式值对象：tint / rim / noise / scrim 的一次性取齐，供坞、顶栏、
 * 弹层在后续波次复用同一套数字。数据从 [Oe3GlassTokens]（spec 数字的唯一所有
 * 者）派生，本类只补齐 L0 配方的装饰参数，不引入第二份权威数值。
 *
 * [tintArgb]/[fallbackTintArgb] 承接 tokens 的不透明 RGB 约定；透明度在绘制
 * 点施加，避免调色板 α 被乘两次（与 tokens 的约定一致）。
 */
@Immutable
data class GlassStyle(
    val tintArgb: Int,
    val tintAlpha: Float,
    val blurRadius: Dp,
    val fallbackTintArgb: Int,
    val surfaceAlpha: Float,
    val scrimHeight: Dp,
    val scrimTopAlpha: Float,
    val rimHeight: Dp,
    val rimHighlightAlpha: Float,
    val noiseAlpha: Float,
) {
    companion object {
        /** 从主题解析出的 tokens 取齐整套玻璃数字；装饰参数来自 [Oe3GlassFallback]。 */
        fun of(tokens: Oe3GlassTokens): GlassStyle = GlassStyle(
            tintArgb = tokens.tint,
            tintAlpha = tokens.tintAlpha,
            blurRadius = tokens.blurRadius,
            fallbackTintArgb = tokens.fallbackTint,
            surfaceAlpha = Oe3GlassFallback.SURFACE_ALPHA,
            scrimHeight = Oe3GlassFallback.SCRIM_HEIGHT,
            scrimTopAlpha = Oe3GlassFallback.SCRIM_TOP_ALPHA,
            rimHeight = Oe3GlassFallback.RIM_HEIGHT,
            rimHighlightAlpha = Oe3GlassFallback.RIM_HIGHLIGHT_ALPHA,
            noiseAlpha = Oe3GlassFallback.NOISE_ALPHA,
        )
    }
}

/**
 * L0 噪点纹理（noise 0.02）：96×96 的确定性黑白噪点，alpha 峰值 =
 * [Oe3GlassFallback.NOISE_ALPHA]×255。确定性种子保证 Roborazzi 金样稳定；
 * 位图只生成一次并全局缓存（绘制点用 repeated ImageShader 平铺，无每帧成本）。
 */
object Oe3GlassNoise {
    private const val TEXTURE_SIZE = 96
    private const val SEED = 0x0E3C1A55L

    val bitmap: ImageBitmap by lazy { generate() }

    private fun generate(): ImageBitmap {
        val random = java.util.Random(SEED)
        val pixels = IntArray(TEXTURE_SIZE * TEXTURE_SIZE)
        val peakAlpha = (255f * Oe3GlassFallback.NOISE_ALPHA).roundToInt().coerceIn(1, 255)
        for (i in pixels.indices) {
            val luminance = if (random.nextBoolean()) 255 else 0
            val alpha = (random.nextInt(peakAlpha + 1)).coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or (luminance shl 16) or (luminance shl 8) or luminance
        }
        val bitmap = Bitmap.createBitmap(TEXTURE_SIZE, TEXTURE_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, TEXTURE_SIZE, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE)
        return bitmap.asImageBitmap()
    }
}
