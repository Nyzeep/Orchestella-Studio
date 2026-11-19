package io.github.nyzeep.orchestella.ui.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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

    /** 壁纸之上叠加的页面底色 scrim 透明度（兼顾可读性与壁纸通透度）。 */
    internal const val WALLPAPER_SCRIM_ALPHA = 0.45f

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
 * 壁纸会过一层 45% 的页面底色 scrim，兼顾文字可读性与壁纸色彩；
 * 渐变由主题的 `primary`/`accent` 与 `background` 混出，浅色与暗色主题都
 * 落在同一套语义色上，不写死 hex。
 */
@Composable
fun OeAppBackground(
    mode: OeAppBackgroundMode,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val info = Oe3ThemeValues.info
    val motion = Oe3ThemeValues.motion
    val reduceMotion = info.reducedMotion || motion.themeTween.durationMillis <= 0
    val colorSpec: AnimationSpec<Color> = if (reduceMotion) {
        snap()
    } else {
        tween(
            durationMillis = motion.themeTween.durationMillis,
            easing = motion.themeTween.easing,
        )
    }
    val alphaSpec: AnimationSpec<Float> = if (reduceMotion) {
        snap()
    } else {
        tween(
            durationMillis = motion.themeTween.durationMillis,
            easing = motion.themeTween.easing,
        )
    }

    val targetBg = Color(colors.background)
    val isSolid = mode == OeAppBackgroundMode.Solid
    val targetTop = if (isSolid) targetBg else Color(ThemeColors.blend(colors.background, colors.primary, 0.22f))
    val targetBottom = if (isSolid) targetBg else Color(ThemeColors.blend(colors.background, colors.accent, 0.16f))

    val animatedBg by animateColorAsState(targetBg, colorSpec, label = "oeBgBase")
    val animatedTop by animateColorAsState(targetTop, colorSpec, label = "oeBgTop")
    val animatedBottom by animateColorAsState(targetBottom, colorSpec, label = "oeBgBottom")

    var retainedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val wallpaperLoadState = if (mode is OeAppBackgroundMode.Wallpaper) {
        rememberWallpaper(mode.uri).value
    } else {
        null
    }
    LaunchedEffect(mode, wallpaperLoadState) {
        when {
            mode !is OeAppBackgroundMode.Wallpaper -> retainedBitmap = null
            wallpaperLoadState is WallpaperLoadState.Ready -> retainedBitmap = wallpaperLoadState.image
            wallpaperLoadState is WallpaperLoadState.Failed -> retainedBitmap = null
            else -> Unit
        }
    }
    val activeBitmap = when {
        mode !is OeAppBackgroundMode.Wallpaper -> null
        wallpaperLoadState is WallpaperLoadState.Ready -> wallpaperLoadState.image
        wallpaperLoadState is WallpaperLoadState.Failed -> null
        else -> retainedBitmap
    }
    val showWallpaper = mode is OeAppBackgroundMode.Wallpaper && activeBitmap != null
    val wallpaperAlpha by animateFloatAsState(
        targetValue = if (showWallpaper) 1f else 0f,
        animationSpec = alphaSpec,
        label = "oeBgWallpaperAlpha",
    )

    val gradient = Brush.verticalGradient(listOf(animatedTop, animatedBg, animatedBottom))
    Box(modifier.fillMaxSize()) {
        if (animatedTop == animatedBg && animatedBottom == animatedBg) {
            Box(Modifier.fillMaxSize().background(animatedBg))
        } else {
            Box(Modifier.fillMaxSize().background(gradient))
        }
        if (activeBitmap != null && wallpaperAlpha > 0f) {
            Image(
                bitmap = activeBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = wallpaperAlpha },
                contentScale = ContentScale.Crop,
            )
            // 文字可读性由壳层承担：壁纸之上盖一层页面底色 scrim。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = wallpaperAlpha }
                    .background(animatedBg.copy(alpha = OeAppBackgroundSpec.WALLPAPER_SCRIM_ALPHA)),
            )
        }
    }
}

private const val MAX_WALLPAPER_CACHE_KB = 24 * 1024

/** 壁纸解码上限：背景只需要屏幕尺寸，整张原图会白占几十 MB。 */
private const val WALLPAPER_MAX_DIMENSION_PX = 1440

private data class CachedWallpaper(
    val image: ImageBitmap,
    val sizeKb: Int,
)

private object WallpaperCache {
    private val lru = object : LruCache<String, CachedWallpaper>(MAX_WALLPAPER_CACHE_KB) {
        override fun sizeOf(key: String, value: CachedWallpaper): Int = value.sizeKb
    }

    fun get(uri: String): ImageBitmap? = lru.get(uri)?.image

    fun put(uri: String, image: ImageBitmap) {
        val sizeKb = (image.asAndroidBitmap().allocationByteCount / 1024).coerceAtLeast(1)
        lru.put(uri, CachedWallpaper(image, sizeKb))
    }

    fun clear() {
        lru.evictAll()
    }
}

@VisibleForTesting
internal fun clearWallpaperCacheForTest() {
    WallpaperCache.clear()
}

private sealed interface WallpaperLoadState {
    data object Loading : WallpaperLoadState
    data class Ready(val image: ImageBitmap) : WallpaperLoadState
    data object Failed : WallpaperLoadState
}

@Composable
private fun rememberWallpaper(uri: String): State<WallpaperLoadState> {
    val context = LocalContext.current
    val cached = remember(uri) { WallpaperCache.get(uri) }
    val initial: WallpaperLoadState = if (cached != null) {
        WallpaperLoadState.Ready(cached)
    } else {
        WallpaperLoadState.Loading
    }
    val state = remember(uri) { mutableStateOf(initial) }
    LaunchedEffect(uri, context) {
        if (cached != null) {
            state.value = WallpaperLoadState.Ready(cached)
        } else {
            state.value = WallpaperLoadState.Loading
            val decoded = withContext(Dispatchers.IO) { decodeWallpaper(context, uri) }
            state.value = if (decoded != null) {
                WallpaperCache.put(uri, decoded)
                WallpaperLoadState.Ready(decoded)
            } else {
                WallpaperLoadState.Failed
            }
        }
    }
    return state
}

/**
 * 有界解码：先读边界算采样率，再按采样率解一次。授权失效、不是图片或内存不足时
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
} catch (_: Exception) {
    // 越权/损坏的 URI 不 crash 壳层：这是唯一允许的兜底，正常路径上不触发。
    null
} catch (_: OutOfMemoryError) {
    WallpaperCache.clear()
    null
}
