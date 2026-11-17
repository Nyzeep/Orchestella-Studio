package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-06 硬约束 ⑤ 的纯决策契约：`createBlurEffect` 半径阶梯回退 + 异常分类。
 * [Oe3GlassBlurSafety.resolveBlurRadius] 的探针可注入——这里用 fake 探针模拟
 * 设备行为（抛 IAE / 抛非 IAE / 正常），Android 侧真探针
 * [Oe3GlassBlurSafety.probeCreateBlurEffect] 与 Haze 内部走同一框架调用，其
 * 同步 IAE 即本决策函数的输入口径。
 */
class Oe3GlassBlurSafetyTest {

    /** fake 探针：只对支持半径集返回成功，否则抛设备机型会抛的异常。 */
    private fun fakeProbe(
        supported: Set<androidx.compose.ui.unit.Dp>,
        exceptionAtUnsupported: (androidx.compose.ui.unit.Dp) -> Throwable = {
            IllegalArgumentException("blur radius not supported: $it")
        },
    ): (androidx.compose.ui.unit.Dp) -> Unit = { radius ->
        if (radius !in supported) throw exceptionAtUnsupported(radius)
    }

    @Test
    fun requestedRadiusSupportedIsReturnedUntouched() {
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = fakeProbe(supported = setOf(16.dp)),
        )
        assertEquals(16.dp, chosen)
    }

    @Test
    fun l1DefaultStaysSixteenDpAndFallsBackToEight() {
        // L1 默认 16dp 不变；16dp 被设备拒绝（IAE）→ 回退 8dp。
        var probed = emptyList<androidx.compose.ui.unit.Dp>()
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = { radius ->
                probed += radius
                if (radius == 16.dp) throw IllegalArgumentException("device rejects 16dp")
            },
        )
        assertEquals(8.dp, chosen)
        // 阶梯顺序：先请求半径、后回退档。
        assertEquals(listOf(16.dp, 8.dp), probed)
    }

    @Test
    fun ultraWalksSixteenThenEight() {
        // Ultra 28dp 是已知踩雷点（research/03 §A7）：28 → 16 → 8。
        var probed = emptyList<androidx.compose.ui.unit.Dp>()
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 28.dp,
            probe = { radius ->
                probed += radius
                if (radius != 8.dp) throw IllegalArgumentException("rejects $radius")
            },
        )
        assertEquals(8.dp, chosen)
        assertEquals(listOf(28.dp, 16.dp, 8.dp), probed)
    }

    @Test
    fun everyRadiusRejectedLandsOnNullForTheL0Surface() {
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = fakeProbe(supported = emptySet()),
        )
        assertNull(chosen)
    }

    @Test
    fun theFallbackRadiusIsProtectedToo() {
        // 回退半径同样受 try/catch 保护：8dp 也抛 IAE → 继续走完阶梯后放弃。
        var probed = emptyList<androidx.compose.ui.unit.Dp>()
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = { radius ->
                probed += radius
                throw IllegalArgumentException("device rejects every radius incl. $radius")
            },
        )
        assertNull(chosen)
        assertEquals(listOf(16.dp, 8.dp), probed)
    }

    @Test
    fun hazeStyleWrappedIaeIsTheSameClassification() {
        // Haze 1.6.10 把设备的 IAE 包装成新的 IllegalArgumentException 重抛
        // （RenderEffect.android.kt）——分类口径不变，仍走半径阶梯。
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = { radius ->
                try {
                    throw IllegalArgumentException("inner device IAE for $radius")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Error whilst calling RenderEffect.createBlurEffect. This is likely " +
                            "because this device does not support a blur radius of $radius",
                        e,
                    )
                }
            },
        )
        assertNull(chosen)
    }

    @Test
    fun nonIllegalRuntimeExceptionsAreLoggedThenBlurIsAbandoned() {
        // 非 IAE 不静默吞掉：记录后直接放弃模糊（L0），剩余阶梯不再尝试。
        val logged = mutableListOf<Pair<Throwable, androidx.compose.ui.unit.Dp>>()
        var probed = emptyList<androidx.compose.ui.unit.Dp>()
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            logNonFatal = { e, radius -> logged += e to radius },
            probe = { radius ->
                probed += radius
                throw IllegalStateException("framework broke at $radius")
            },
        )
        assertNull(chosen)
        assertEquals(listOf(16.dp), probed)
        assertEquals(1, logged.size)
        assertTrue(logged[0].first is IllegalStateException)
        assertEquals(16.dp, logged[0].second)
    }

    @Test
    fun theLadderDeduplicatesTheRequestedRadius() {
        // 请求半径与阶梯首档相同（L1 的 16dp）时不重复探测。
        var probeCalls = 0
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 16.dp,
            probe = { radius ->
                probeCalls++
                if (radius == 16.dp) throw IllegalArgumentException("rejects 16")
            },
        )
        assertEquals(8.dp, chosen)
        assertEquals(2, probeCalls)
    }

    @Test
    fun errorsPropagateAndAreNeverSwallowed() {
        // Error 不是「半径不支持」：原样抛出，不做任何档位决策。
        try {
            Oe3GlassBlurSafety.resolveBlurRadius(
                requested = 16.dp,
                probe = { throw OutOfMemoryError("not a radius problem") },
            )
            org.junit.Assert.fail("OutOfMemoryError must propagate")
        } catch (e: OutOfMemoryError) {
            assertEquals("not a radius problem", e.message)
        }
    }

    @Test
    fun ladderConstantDocumentsTheFallbackStep() {
        // 硬约束 ⑤ 的阶梯钉定：16dp（L1 默认，请求半径）之后是 8dp，再往下放弃。
        assertEquals(listOf(16.dp, 8.dp), Oe3GlassBlurSafety.RADIUS_FALLBACK_LADDER)
    }
}
