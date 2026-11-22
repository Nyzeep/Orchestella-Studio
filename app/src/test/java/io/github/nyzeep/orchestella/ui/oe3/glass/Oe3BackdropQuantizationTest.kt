package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-05 模糊半径桶化契约（纯 JVM）：任意连续请求半径必须落到 4dp 整数倍
 * 网格上（HWUI ShaderCache 条目数有界），且现行配方半径（scroll edge 12dp、
 * L1 16dp、Ultra 28dp）全是桶心——量化对既有产物是恒等映射，金样不受扰动。
 */
class Oe3BackdropQuantizationTest {

    @Test
    fun stepIsFourDp() {
        assertEquals(4.dp, Oe3GlassBlurQuantize.STEP)
    }

    @Test
    fun zeroAndNegativeCollapseToNoBlur() {
        assertEquals(0.dp, 0.dp.quantizeBlur())
        assertEquals(0.dp, (-4).dp.quantizeBlur())
        assertEquals(0.dp, (-0.4).dp.quantizeBlur())
    }

    @Test
    fun gridCentersPassThroughUnchanged() {
        // 4dp 整数倍不动：量化是幂等的，重复桶化不再漂移。
        assertEquals(4.dp, 4.dp.quantizeBlur())
        assertEquals(12.dp, 12.dp.quantizeBlur())
        assertEquals(16.dp, 16.dp.quantizeBlur())
        assertEquals(28.dp, 28.dp.quantizeBlur())
        assertEquals(28.dp, 28.dp.quantizeBlur().quantizeBlur())
    }

    @Test
    fun betweenValuesRoundToNearestBucket() {
        // 13.9dp：13.9/4 = 3.475 → 最近桶 3 → 12dp。
        assertEquals(12.dp, 13.9.dp.quantizeBlur())
        // 14dp：14/4 = 3.5 → roundToInt 的 .5 上取整语义 → 4 → 16dp。
        assertEquals(16.dp, 14.dp.quantizeBlur())
        // 下半带落低位桶。
        assertEquals(8.dp, 9.9.dp.quantizeBlur())
        assertEquals(32.dp, 30.dp.quantizeBlur())
    }

    @Test
    fun subDpStepIsClampedToOneDp() {
        // 步长 <1dp 会让网格本身退化成连续值（缓存条目数失去上界），钳到 1dp。
        assertEquals(10.dp, 10.4.dp.quantizeBlur(step = 0.5.dp))
        assertEquals(11.dp, 10.6.dp.quantizeBlur(step = 0.5.dp))
        assertEquals(0.dp, 0.4.dp.quantizeBlur(step = 0.5.dp))
    }

    @Test
    fun subHalfBucketRequestsRoundDownToZero() {
        // 最近桶语义的必然推论：不足半桶（<2dp）的正请求四舍五入到 0 桶——
        // 与「value <= 0 → 无模糊」语义一致，这类亚桶请求本就不该占缓存条目。
        assertEquals(0.dp, 0.5.dp.quantizeBlur())
        assertEquals(0.dp, 1.dp.quantizeBlur())
        assertEquals(0.dp, 1.9.dp.quantizeBlur())
        // 恰好半桶（2dp = 0.5 步长）按 roundToInt 的 .5 上取整语义进位。
        assertEquals(4.dp, 2.dp.quantizeBlur())
    }

    @Test
    fun arbitraryContinuousValuesAlwaysLandOnTheGrid() {
        // 连续半径序列逐点桶化后全部落在 4dp 网格：ShaderCache 条目数被钳在
        // 有界集合内，不再随滚动/动效半径漂移而持续逐出。
        val samples = listOf(
            2f, 3.3f, 5.6f, 7.7f, 9.1f, 12.4f, 17f, 22.8f, 29.5f, 41.2f, 61.3f,
        )
        for (value in samples) {
            val quantized = value.dp.quantizeBlur()
            assertTrue(
                "半径 ${value}dp 量化后 $quantized 不在 4dp 网格上",
                quantized.value % Oe3GlassBlurQuantize.STEP.value == 0f,
            )
            assertTrue("达到半桶的正半径不得被量化归零", quantized.value > 0f)
        }
    }

    @Test
    fun scrollEdgeBlurRadiusIsAlreadyABucketCenter() {
        // 顶栏滚动边缘配方半径 12dp 已是桶心：量化不改既有值，滞回翻转点
        // 前后的模糊管线不变。
        assertEquals(Oe3Glass.scrollEdgeBlurRadius, Oe3Glass.scrollEdgeBlurRadius.quantizeBlur())
    }
}
