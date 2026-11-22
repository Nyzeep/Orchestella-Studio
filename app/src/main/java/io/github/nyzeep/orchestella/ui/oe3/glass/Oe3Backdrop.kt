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
 * shell 唯一 haze 源（[io.github.nyzeep.orchestella.ui.shell.Oe3App] 根 Box）的
 * 共享句柄：底栏与顶栏滚动边缘效果都消费它，全屏单一采样源。
 *
 * 所有权仲裁语义（W2-05，R-02 修复）：
 * - **底栏是第一类玻璃所有者**——无条件持有实时玻璃预算（L1/L2 hazeChild），
 *   任何其它玻璃效果的出现都不得降档底栏。
 * - **顶栏滚动边缘模糊是次级消费者**——它不注册自己的 haze 源，只在滚动折叠
 *   后从同一共享源采样，底栏永远不会因为它的出现而失去实时玻璃。
 */
internal val LocalOe3ShellGlassState = staticCompositionLocalOf<HazeState?> { null }

/**
 * 顶栏滚动边缘玻璃的归属账本：谁注册了边缘、谁当前持有边缘。
 *
 * W2-05 后底栏接线不再读本账本（所有权仲裁改为「底栏恒持有」），账本降级为
 * 诊断口径：观察多页面各自 [io.github.nyzeep.orchestella.ui.shell.OeScaffold]
 * 竞注边缘时的归属情况（last-wins），供测试与调试读取。
 */
@Stable
internal class Oe3GlassChromeState {
    private val scrollEdges = mutableStateListOf<Any>()

    /** 账本诊断口径：当前是否有任何页面注册了滚动边缘（不再驱动底栏降档）。 */
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

/**
 * 顶栏滚动边缘的次级玻璃效果（W2-05 重构）：
 *
 * 返回 null = 当前无边缘效果（未折叠、能力闸不过、或组合未接线 shell 源——
 * 预览与未接线的测试组合没有共享源可消费，顶栏退回纯 scrim 兜底，视觉连续）。
 *
 * 修饰符树固定性论证：调用侧以「滞回折叠态」为键（见
 * [io.github.nyzeep.orchestella.ui.shell.OeScaffoldSpec.nextCollapsed]），
 * 而非连续 alpha>0——修饰符只在滞回真实翻转时增删，临界滚动位置的抖动
 * 不会造成子树反复重建。
 *
 * 所有权仲裁：本效果只消费 [LocalOe3ShellGlassState]（shell 根已是唯一
 * haze 源），注册 [Oe3GlassChromeState] 归属账本仅作诊断；底栏的第一类
 * 所有者地位不受本效果出现与否影响。
 */
@Composable
internal fun rememberOe3ScrollEdgeGlass(active: Boolean): Modifier? {
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
    val radius = if (active && tier.samplesBackdrop) {
        rememberGlassBlurRadius(Oe3Glass.scrollEdgeBlurRadius)
    } else {
        null
    }
    val sharedState = LocalOe3ShellGlassState.current
    if (radius != null && chrome != null) {
        // 归属账本登记保留：多页面各自 OeScaffold 竞注时 last-wins 归属，
        // 只有当前持有者渲染边缘效果，其余页面登记但让位。
        DisposableEffect(chrome, owner) {
            chrome.addScrollEdge(owner)
            onDispose { chrome.removeScrollEdge(owner) }
        }
    }
    if (radius == null || sharedState == null || chrome?.ownsScrollEdge(owner) == false) return null

    val style = remember(radius) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = emptyList(),
            blurRadius = radius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(Color.Transparent),
        )
    }
    return Modifier.oe3BackdropHaze(
        state = sharedState,
        style = style,
        opacity = 1f,
        gradient = ScrollEdgeProgression,
    )
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
    opacity: Float = 1f,
    gradient: HazeProgressive? = null,
): Modifier = hazeChild(state, style) {
    inputScale = BackdropInputScale
    alpha = opacity
    progressive = gradient
}
