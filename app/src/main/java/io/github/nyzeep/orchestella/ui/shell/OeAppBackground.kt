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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    internal const val WALLPAPER_SCRIM_ALPHA = 0.62f

    /** 壁纸模式下顶部标题区额外保护渐变高度与峰值透明度（M2-A）。 */
    internal val WALLPAPER_TOP_SCRIM_HEIGHT = 128.dp
    internal const val WALLPAPER_TOP_SCRIM_ALPHA = 0.38f

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
 * `Oe3App` 从 ThemeStore 读入，供页面表面和浮动栏使用。
 */
val LocalOeSurfaceAlpha = staticCompositionLocalOf { 1f }

/**
 * 壳层最底层的背景画布。只负责"画什么"，不含导航与标题。
 *
 * 壁纸叠加页面底色遮罩以维持标题和正文的可读性。
 * 渐变由主题的 `primary`/`accent` 与 `background` 混出，浅色与暗色主题都
 * 落在同一套语义色上，不写死 hex。
 *
 * W2-03：壁纸解码与探活收敛到进程级 [WallpaperStore] 单源——壳层最多同时
 * 挂 3 个实例（根背景 + 栈 Under/Moving 底片），并发挂载只做一次解码、同一
 * 挂载波次只做一次可用性探活。[wallpaperParallaxFraction] 供栈 Moving 底片
 * 反向补偿壁纸偏移，使壁纸在屏幕坐标系与根背景对齐（仅内容横向滑入）。
 */
@Composable
fun OeAppBackground(
    mode: OeAppBackgroundMode,
    modifier: Modifier = Modifier,
    wallpaperParallaxFraction: (() -> Float)? = null,
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
        val entry = remember(mode.uri) { WallpaperStore.entry(mode.uri) }
        val state by entry.state.collectAsState()
        val context = LocalContext.current
        LaunchedEffect(mode.uri, context) {
            WallpaperStore.attach(mode.uri, context)
        }
        state
    } else {
        null
    }
    val readyBitmap = (wallpaperLoadState as? WallpaperLoadState.Ready)?.image
    val activeBitmap = readyBitmap ?: retainedBitmap
    val showWallpaper = readyBitmap != null ||
        (wallpaperLoadState == WallpaperLoadState.Loading && retainedBitmap != null)
    val wallpaperAlpha by animateFloatAsState(
        targetValue = if (showWallpaper) 1f else 0f,
        animationSpec = alphaSpec,
        label = "oeBgWallpaperAlpha",
    )
    val wallpaperHidden = !showWallpaper && wallpaperAlpha == 0f
    LaunchedEffect(readyBitmap, wallpaperHidden) {
        if (readyBitmap != null) {
            retainedBitmap = readyBitmap
        } else if (wallpaperHidden) {
            retainedBitmap = null
        }
    }

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
                    .graphicsLayer {
                        alpha = wallpaperAlpha
                        // W2-03 底片对位：Moving 层平移时按 (1−p)×宽度反向补偿，
                        // 壁纸保持屏幕对齐；可见窗口 ⊆ 补偿后的位图覆盖区间，
                        // 不暴露边缘。渐变与两层 scrim 横向均匀，无需补偿。
                        if (wallpaperParallaxFraction != null) {
                            translationX = -wallpaperParallaxFraction() * size.width
                        }
                    },
                contentScale = ContentScale.Crop,
            )
            // 文字可读性由壳层承担：壁纸之上盖一层页面底色 scrim，并在顶栏透明态区域叠加柔和顶部保护渐隐。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = wallpaperAlpha }
                    .background(animatedBg.copy(alpha = OeAppBackgroundSpec.WALLPAPER_SCRIM_ALPHA)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(OeAppBackgroundSpec.WALLPAPER_TOP_SCRIM_HEIGHT)
                    .graphicsLayer { alpha = wallpaperAlpha }
                    .background(
                        Brush.verticalGradient(
                            0f to animatedBg.copy(alpha = OeAppBackgroundSpec.WALLPAPER_TOP_SCRIM_ALPHA),
                            1f to Color.Transparent,
                        ),
                    ),
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

    fun remove(uri: String) {
        lru.remove(uri)
    }
}

@VisibleForTesting
internal fun clearWallpaperCacheForTest() {
    WallpaperCache.clear()
}

internal sealed interface WallpaperLoadState {
    data object Loading : WallpaperLoadState
    data class Ready(val image: ImageBitmap) : WallpaperLoadState
    data object Failed : WallpaperLoadState
}

/**
 * 壁纸单源（W2-03）：进程级唯一的壁纸加载状态。壳层同时最多挂 3 个
 * [OeAppBackground] 实例（根背景 + 栈 Under/Moving 底片），旧实现里每个实例
 * 各自 `rememberWallpaper`——并发首挂会重复解码同一 uri、每个实例挂载都重复
 * `wallpaperAvailable` IO 探活。本单源以 uri 键共享一个状态流：
 *
 * - 缓存未命中 → mutex 收敛为**一次解码**，首个实例完成后的并发实例直接
 *   复用结论（拿锁后重查缓存，不重复打开流）；
 * - 缓存命中 → 同一挂载波次内只做**一次**可用性探活；此后重新挂载（模式
 *   切走再切回）视为新波次，重新探活——授权被撤销仍能及时发现并回退渐变。
 *
 * 波次判定用挂载代计数器（严格、与锁序无关）：每个实例进锁前先读
 * [Entry.probeGeneration] 快照，锁内核对——同波次并发实例共享同一快照，恰有
 * 首个拿锁者探活并推进代数，其余快照失配直接跳过；新的顺序挂载读到已推进的
 * 代数（快照 == 当前）则探活。探活全程持锁，故同帧挂载必然同快照。
 *
 * 条目表随 uri 增长有限（用户挑选的壁纸数量级）。
 */
internal object WallpaperStore {

    internal class Entry {
        val state = MutableStateFlow<WallpaperLoadState>(WallpaperLoadState.Loading)
        val mutex = Mutex()

        /** 已完成的探活代数；挂载波次以"进锁前快照 vs 锁内当前值"判定。 */
        @Volatile
        var probeGeneration: Long = 0L
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun entry(uri: String): Entry = entries.computeIfAbsent(uri) { Entry() }

    /** 挂载时确保该 uri 有一份新鲜结论；并发挂载由 mutex 与挂载代收敛 IO。 */
    suspend fun attach(uri: String, context: Context) {
        val entry = entry(uri)
        val cached = WallpaperCache.get(uri)
        if (cached != null) {
            // 缓存命中：先立即呈现，再探活文档是否仍可打开（旧语义保留）。
            entry.state.value = WallpaperLoadState.Ready(cached)
            val snapshotGeneration = entry.probeGeneration
            entry.mutex.withLock {
                if (entry.probeGeneration != snapshotGeneration) {
                    return@withLock
                }
                val available = withContext(Dispatchers.IO) { wallpaperAvailable(context, uri) }
                if (!available) {
                    WallpaperCache.remove(uri)
                    entry.state.value = WallpaperLoadState.Failed
                }
                entry.probeGeneration = snapshotGeneration + 1
            }
        } else {
            entry.mutex.withLock {
                // 拿锁后重查缓存：并发波次里第一个实例可能刚完成解码。
                val fresh = WallpaperCache.get(uri)
                if (fresh != null) {
                    entry.state.value = WallpaperLoadState.Ready(fresh)
                    return
                }
                entry.state.value = WallpaperLoadState.Loading
                val decoded = withContext(Dispatchers.IO) { decodeWallpaper(context, uri) }
                entry.state.value = if (decoded != null) {
                    WallpaperCache.put(uri, decoded)
                    WallpaperLoadState.Ready(decoded)
                } else {
                    WallpaperLoadState.Failed
                }
            }
        }
    }

    @VisibleForTesting
    internal fun resetForTest() {
        entries.clear()
    }
}

private fun wallpaperAvailable(context: Context, uri: String): Boolean = try {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
} catch (_: Exception) {
    false
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
            bounds.outWidth / sample > WALLPAPER_MAX_DIMENSION_PX ||
            bounds.outHeight / sample > WALLPAPER_MAX_DIMENSION_PX
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
