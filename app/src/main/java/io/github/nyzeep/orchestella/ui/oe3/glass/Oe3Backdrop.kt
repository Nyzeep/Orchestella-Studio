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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel

@OptIn(ExperimentalHazeApi::class)
internal val BackdropInputScale = HazeInputScale.Fixed(0.5f)

/**
 * 记录当前壳层哪些页面已进入顶栏折叠态，供诊断和测试读取。
 */
@Stable
internal class Oe3GlassChromeState {
    private val scrollEdges = mutableStateListOf<Any>()

    /** 账本诊断口径：当前是否有任何页面注册了滚动边缘（不再驱动底栏降档）。 */
    val hasScrollEdge: Boolean get() = scrollEdges.isNotEmpty()

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

/**
 * 顶栏位于 Haze 采样源内部，使用 OeScaffoldBar 的渐变遮罩。折叠状态供
 * 诊断读取，实时模糊由采样源外的底栏负责。
 */
@Composable
internal fun ReportOe3ScrollEdge(active: Boolean) {
    val chrome = LocalOe3GlassChrome.current ?: return
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
    if (active && tier.samplesBackdrop) {
        DisposableEffect(chrome, owner) {
            chrome.addScrollEdge(owner)
            onDispose { chrome.removeScrollEdge(owner) }
        }
    }
}

/**
 * 请求半径的可用性解析：离散桶化（防 HWUI ShaderCache 连续半径溢出——ReFra
 * 模式的净室重写）夹在 API 闸与探针之间——先确认设备有真实模糊能力，再把
 * 请求半径对齐到桶网格，最后才做同参探针回退。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun rememberGlassBlurRadius(requested: Dp): Dp? {
    val density = LocalDensity.current
    return remember(requested, density.density) {
        if (Build.VERSION.SDK_INT < Oe3GlassStrategy.MIN_REAL_BLUR_API) {
            null
        } else {
            val quantized = requested.quantizeBlur()
            Oe3GlassBlurSafety.resolveBlurRadius(
                requested = quantized,
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
): Modifier = hazeChild(state, style) {
    inputScale = BackdropInputScale
}
