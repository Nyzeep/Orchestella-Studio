package io.github.nyzeep.orchestella.ui.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 壳层背景的三态（spec §1.7 前置条件）：壁纸图 / 主题渐变 / 纯色。
 *
 * 背景层必须画在页面内容**之下**；页面自己再涂不透明底会让玻璃永远采样到
 * 纯色平面，所以 v4 删掉了五页的整页 `background(...)`。
 */
@Immutable
sealed interface OeAppBackgroundMode {

    /** 用户显式挑的壁纸（SAF `content://`）。 */
    data class Wallpaper(val uri: String) : OeAppBackgroundMode

    /** 没壁纸、没开纯黑时的主题渐变。 */
    data object Gradient : OeAppBackgroundMode

    /** 纯黑（只在暗色主题下由纯黑开关触发）或无壁纸的实色回退。 */
    data object Solid : OeAppBackgroundMode
}

/**
 * 背景层的纯规则，不依赖 Compose，便于单测。
 */
object OeAppBackgroundSpec {

    /**
     * 壁纸优先于纯黑：壁纸是用户显式选择，纯黑开关只是"没有壁纸时"的底色。
     * 空白 URI 当作没有壁纸。
     */
    fun resolve(backgroundUri: String?, pureBlack: Boolean): OeAppBackgroundMode = when {
        !backgroundUri.isNullOrBlank() -> OeAppBackgroundMode.Wallpaper(backgroundUri)
        pureBlack -> OeAppBackgroundMode.Solid
        else -> OeAppBackgroundMode.Gradient
    }

    /**
     * 「界面表面不透明度」偏好（55..100%）→ 0.55..1.0 的 alpha。
     * 复用既有 [SurfaceOpacity] 区间，不新造第二套偏好。
     */
    fun surfaceAlpha(percent: Int): Float = SurfaceOpacity.clampPercent(percent) / 100f
}

/**
 * 界面表面不透明度（0.55..1.0），供后续玻璃/半透明表面消费。
 *
 * UI-02 提供并接线（`Oe3App` 从 ThemeStore 读入），消费方是 UI-06 的
 * 玻璃材质：`Oe3Card` 这类基座组件仍按 docs 的 `surface` 不透明规则绘制。
 */
val LocalOeSurfaceAlpha = staticCompositionLocalOf { 1f }

/**
 * 壳层最底层的背景画布。只负责"画什么"，不含导航与标题。
 *
 * 壁纸会过一层 55% 的页面底色 scrim，否则浅色壁纸上的正文对比度不达标；
 * 渐变由主题的 `primary`/`accent` 与 `background` 混出，浅色与暗色主题都
 * 落在同一套语义色上，不写死 hex。
 */
@Composable
fun OeAppBackground(
    mode: OeAppBackgroundMode,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val background = Color(colors.background)
    val gradient = Brush.verticalGradient(
        listOf(
            Color(ThemeColors.blend(colors.background, colors.primary, 0.22f)),
            background,
            Color(ThemeColors.blend(colors.background, colors.accent, 0.16f)),
        ),
    )
    Box(modifier.fillMaxSize()) {
        when (mode) {
            OeAppBackgroundMode.Solid -> Box(Modifier.fillMaxSize().background(background))
            OeAppBackgroundMode.Gradient -> Box(Modifier.fillMaxSize().background(gradient))
            is OeAppBackgroundMode.Wallpaper -> {
                val bitmap by rememberWallpaper(mode.uri)
                val loaded = bitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // 文字可读性由壳层承担：壁纸之上盖一层页面底色。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(background.copy(alpha = WALLPAPER_SCRIM_ALPHA)),
                    )
                } else {
                    // 授权丢失/不是图片：退回渐变，不留一块透明空洞。
                    Box(Modifier.fillMaxSize().background(gradient))
                }
            }
        }
    }
}

private const val WALLPAPER_SCRIM_ALPHA = 0.62f

/** 壁纸解码上限：背景只需要屏幕尺寸，整张原图会白占几十 MB。 */
private const val WALLPAPER_MAX_DIMENSION_PX = 1440

@Composable
private fun rememberWallpaper(uri: String) = LocalContext.current.let { context ->
    produceState<ImageBitmap?>(initialValue = null, uri, context) {
        value = withContext(Dispatchers.IO) { decodeWallpaper(context, uri) }
    }
}

/**
 * 有界解码：先读边界算采样率，再按采样率解一次。授权失效或不是图片时
 * 返回 null，由调用方退回渐变。
 */
private fun decodeWallpaper(context: Context, uri: String): ImageBitmap? = try {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= WALLPAPER_MAX_DIMENSION_PX ||
            bounds.outHeight / (sample * 2) >= WALLPAPER_MAX_DIMENSION_PX
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(parsed)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }
} catch (e: Exception) {
    // 越权/损坏的 URI 不 crash 壳层：这是唯一允许的兜底，正常路径上不触发。
    null
}
