package io.github.nyzeep.orchestella.ui.oe3.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-06 运行时性能门控的纯函数契约：60 帧帧耗时 → P90 与掉帧率两通道裁决。
 * 全部用假帧时钟（手工帧耗时）驱动，不碰 FrameMetrics——Android 侧的宿主
 * 只负责把 `TOTAL_DURATION` 逐帧喂进来。
 */
class Oe3GlassFrameGateTest {

    private var verdict: GlassGateVerdict? = null
    private var callbackCount = 0

    private fun gate(budgetMs: Double = 1000.0 / 60.0): Oe3GlassFrameGate =
        Oe3GlassFrameGate(budgetMs = budgetMs) {
            verdict = it
            callbackCount++
        }

    @Test
    fun healthyFrameDurationsKeepTheBlurTier() {
        val g = gate()
        // 60 帧、每帧 9ms 渲染耗时：P90 9ms < 预算 16.67×0.7 = 11.67，零掉帧。
        repeat(60) { g.onFrame(9.0) }
        assertTrue(g.isDone)
        assertEquals(1, callbackCount)
        assertEquals(false, verdict!!.downgrade)
    }

    @Test
    fun fewerThanSixtySamplesNeverDecides() {
        val g = gate()
        repeat(59) { g.onFrame(40.0) }
        assertEquals(null, verdict)
        assertFalse(g.isDone)
        // 第 60 帧凑满窗口才裁决。
        g.onFrame(40.0)
        assertTrue(g.isDone)
        assertEquals(1, callbackCount)
    }

    @Test
    fun framesAfterCompletionAreIgnoredWithoutExtraCallbacks() {
        val g = gate()
        repeat(80) { g.onFrame(9.0) }
        assertEquals(1, callbackCount)
        assertTrue(g.isDone)
        // 已完成后继续喂帧：不崩溃、不再回调。
        assertTrue(g.onFrame(99.0))
        assertEquals(1, callbackCount)
    }

    @Test
    fun slowP90Downgrades() {
        val g = gate(budgetMs = 16.667)
        // 52 帧 9ms + 8 帧 13ms：nearest-rank P90 = 13ms > 16.667×0.7 = 11.67 → 降档；
        // 掉帧（>预算 16.67）为 0，单凭 P90 通道触发（13ms 占比 13% > 10%）。
        repeat(52) { g.onFrame(9.0) }
        repeat(8) { g.onFrame(13.0) }
        assertTrue(g.isDone)
        assertEquals(true, verdict!!.downgrade)
        assertEquals(0.0, verdict!!.droppedRatio, 0.0001)
        assertEquals(13.0, verdict!!.p90Ms, 0.0001)
    }

    @Test
    fun droppedRatioOverTwoPercentDowngradesEvenWithGoodP90() {
        val g = gate(budgetMs = 16.667)
        // 58 帧 9ms（P90 通道良好）+ 2 帧 20ms（错过 deadline）：
        // 2/60 = 3.3% > 2% → 降档，即使 P90 只有 9ms。
        repeat(58) { g.onFrame(9.0) }
        repeat(2) { g.onFrame(20.0) }
        assertTrue(g.isDone)
        assertTrue(verdict!!.droppedRatio > GlassGatePolicy.DROPPED_RATIO_LIMIT)
        assertEquals(true, verdict!!.downgrade)
    }

    @Test
    fun marginalJitterUnderBothThresholdsKeepsTheTier() {
        val g = gate(budgetMs = 16.667)
        // 59 帧 11ms + 1 帧 17ms：P90 = 11ms < 11.67 ✓，掉帧 1/60 = 1.7% ≤ 2% ✓。
        repeat(59) { g.onFrame(11.0) }
        g.onFrame(17.0)
        assertTrue(g.isDone)
        assertEquals(false, verdict!!.downgrade)
        assertEquals(1.7, verdict!!.droppedRatio * 100, 0.05)
    }

    @Test
    fun partialWindowsNeverDowngradeEvenWhenNumbersWould() {
        // 不满 60 帧的窗口即使数值超限也不降档（避免把冷启动头几帧当裁决依据）。
        val v = GlassGatePolicy.verdict(listOf(40.0, 40.0, 40.0), budgetMs = 16.667)
        assertFalse(v.downgrade)
    }

    @Test
    fun p90UsesNearestRankOverTheSortedDurations() {
        val durations = (1..100).map { it.toDouble() }
        assertEquals(90.0, GlassGatePolicy.p90(durations), 0.001)
        assertEquals(0.0, GlassGatePolicy.p90(emptyList()), 0.001)
    }

    @Test
    fun droppedRatioIsEmptySafe() {
        assertEquals(0.0, GlassGatePolicy.droppedRatio(emptyList(), budgetMs = 16.667), 0.0001)
        assertEquals(0.5, GlassGatePolicy.droppedRatio(listOf(10.0, 40.0), budgetMs = 16.667), 0.0001)
    }
}
