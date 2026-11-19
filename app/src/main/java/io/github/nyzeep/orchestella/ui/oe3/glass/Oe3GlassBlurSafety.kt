package io.github.nyzeep.orchestella.ui.oe3.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * UI-06 硬约束 ⑤ 的落地（v4 spec §1.7；research/03 §A7）：`RenderEffect
 * .createBlurEffect` 必须以 try/catch 包裹并按半径阶梯回退——部分机型对大
 * 半径抛 `IllegalArgumentException`（已知踩雷点：Ultra 28dp）。
 *
 * 实现机制是「同参探针」：Haze 1.6.10 在其节点内部调用 `createRenderEffect`
 * （组合期的 hazeChild 调用点抓不到那次异常），因此在把 style 交给 Haze
 * **之前**，用与 Haze 完全相同的框架调用（相同半径 px、相同 tile mode）同步
 * 探测一次。探针抛出的即框架自身对不支持半径的同步 IAE——Haze 的包装异常
 * 本身就是 `IllegalArgumentException` 子类，分类口径一致。探测通过的半径才
 * 会被 Haze 用到，从而保证 Haze 内部不会再踩到该 IAE。
 *
 * 纯决策函数 [resolveBlurRadius] 不碰 Android API，单测用 fake 探针驱动；
 * Android 探针 [probeCreateBlurEffect] 只在真实设备上的 L1 分支执行
 * （API ≥ 31 才可能进入该分支）。
 */
object Oe3GlassBlurSafety {

    /**
     * 半径回退阶梯：请求半径探测失败后依次尝试。请求半径由调用方给出
     * （L1 默认 16dp 不变，Ultra 28dp 会先试 28 → 16 → 8），阶梯本身去重，
     * 每一档回退同样受 try/catch 保护，全部失败 → 放弃模糊走 L0。
     */
    val RADIUS_FALLBACK_LADDER: List<Dp> = listOf(16.dp, 8.dp)

    /**
     * 半径阶梯 + 异常→档位决策（纯函数，[probe] 注入）：
     *
     * - 探针通过 → 返回该半径（正常 L1）。
     * - `IllegalArgumentException`（含 Haze 包装异常）→ 已知机型差异，按
     *   [fallbackLadder] 静默降一档继续探测。
     * - 其它 `RuntimeException` → **不静默吞掉**：经 [logNonFatal] 记录后
     *   放弃模糊（返回 null → L0），不再继续阶梯。
     * - 全部档位 IAE → 放弃模糊（null → L0）。
     * - `Error` 不在此捕获，按运行时约定原样抛出。
     *
     * @return 可用的模糊半径；null = 设备不支持任何档位 → 调用方渲染 L0。
     */
    fun resolveBlurRadius(
        requested: Dp,
        fallbackLadder: List<Dp> = RADIUS_FALLBACK_LADDER,
        logNonFatal: (Throwable, Dp) -> Unit = { _, _ -> },
        probe: (Dp) -> Unit,
    ): Dp? {
        val candidates = (listOf(requested) + fallbackLadder.filter { it < requested }).distinct()
        for (radius in candidates) {
            try {
                probe(radius)
                return radius
            } catch (e: IllegalArgumentException) {
                // 设备不支持该半径：走下一档（请求半径 → 阶梯 → 放弃）。
            } catch (e: RuntimeException) {
                // 非 IAE 的运行时异常不是「半径不支持」的证据：记录（不静默）
                // 后放弃模糊，剩余阶梯不再尝试。
                logNonFatal(e, radius)
                return null
            }
        }
        return null
    }

    /**
     * Android 侧探针：与 Haze 1.6.10 `RenderEffect.android.kt` 内部完全相同的
     * 框架调用（`createBlurEffect(radiusPx, radiusPx, TileMode.CLAMP)`，半径
     * px 的换算也一致）。不抛即该半径可用；不支持半径的机型在此同步抛 IAE。
     * 仅在 API ≥ 31 的路径上被调用（L1 分支由能力闸保证，调用点另有静态卫语句）。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun probeCreateBlurEffect(radius: Dp, density: Density, inputScale: Float = 1f) {
        val radiusPx = with(density) { (radius * inputScale).toPx() }
        android.graphics.RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
}
