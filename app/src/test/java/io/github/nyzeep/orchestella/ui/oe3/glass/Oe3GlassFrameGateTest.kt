package io.github.nyzeep.orchestella.ui.oe3.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-06 运行时性能门控的纯函数契约（W2-05 R-03 重标定后）：60 帧帧耗时
 * （含 6 暖帧）→ 剥离暖帧的稳态子集上算 P90 与掉帧率两通道裁决。全部用假帧
 * 时钟（手工帧耗时）驱动，不碰 FrameMetrics——Android 侧的宿主只负责把
 * `TOTAL_DURATION` 逐帧喂进来。
 *
 * 预算 16.667ms 下的两条线（[GlassGatePolicy] 数值）：
 * - P90 通道：> 预算×1.25 = 20.83ms 的稳态持续超限；
 * - 掉帧通道：> 预算×1.5 = 25ms 的真滞留占比 > 2%。
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
        // 60 帧、每帧 9ms 渲染耗时：剥离 6 暖帧后 P90 9ms < 预算 16.667×1.25 = 20.83，
        // 掉帧（>16.667×1.5 = 25）为 0。
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
        // 52 帧 9ms + 8 帧 22ms：剥离 6 暖帧后 46×9 + 8×22，nearest-rank P90 = 22ms
        // > 16.667×1.25 = 20.83 → 降档；22ms 占预算 1.32 倍（<1.5）不记掉帧，
        // 掉帧率为 0——单凭 P90 通道触发（(1.25,1.5]×预算 的持续超限归 P90 管）。
        repeat(52) { g.onFrame(9.0) }
        repeat(8) { g.onFrame(22.0) }
        assertTrue(g.isDone)
        assertEquals(true, verdict!!.downgrade)
        assertEquals(0.0, verdict!!.droppedRatio, 0.0001)
        assertEquals(22.0, verdict!!.p90Ms, 0.0001)
    }

    @Test
    fun droppedRatioOverTwoPercentDowngradesEvenWithGoodP90() {
        val g = gate(budgetMs = 16.667)
        // 58 帧 9ms + 2 帧 30ms：剥离 6 暖帧后 52×9 + 2×30。30ms > 预算×1.5 = 25
        // 记真滞留，2/54 = 3.7% > 2% → 降档；P90 = 9ms（30 只占 2/54，第 49 位
        // 仍是 9ms）——真滞留通道在 P90 良好时独立检出。
        repeat(58) { g.onFrame(9.0) }
        repeat(2) { g.onFrame(30.0) }
        assertTrue(g.isDone)
        assertTrue(verdict!!.droppedRatio > GlassGatePolicy.DROPPED_RATIO_LIMIT)
        assertEquals(true, verdict!!.downgrade)
        assertEquals(9.0, verdict!!.p90Ms, 0.0001)
    }

    @Test
    fun marginalJitterUnderBothThresholdsKeepsTheTier() {
        val g = gate(budgetMs = 16.667)
        // 59 帧 11ms + 1 帧 26ms：剥离 6 暖帧后 53×11 + 1×26。26ms > 25 记 1 次
        // 真滞留，但 1/54 = 1.85% ≤ 2% ✓；P90 = 11ms < 20.83 ✓ → 不降档。
        repeat(59) { g.onFrame(11.0) }
        g.onFrame(26.0)
        assertTrue(g.isDone)
        assertEquals(false, verdict!!.downgrade)
        assertEquals(1.0 / 54.0, verdict!!.droppedRatio, 0.0001)
    }

    @Test
    fun partialWindowsNeverDowngradeEvenWhenNumbersWould() {
        // 不满 60 帧的窗口即使数值超限也不降档（避免把冷启动头几帧当裁决依据）。
        // 直接喂 3 帧给纯函数层：窗口完整性按含暖帧的全量 60 帧判（3 < 60），
        // 且剥离 6 暖帧后子集为空 → 统计量 0，双重不降档。
        val v = GlassGatePolicy.verdict(listOf(40.0, 40.0, 40.0), budgetMs = 16.667)
        assertFalse(v.downgrade)
        // warmup ≥ 样本数的防御：drop 后为空 → 空安全统计量 0（无数据不降档契约）。
        val stripped = listOf(40.0, 40.0, 40.0).drop(GlassGatePolicy.WARMUP_FRAMES)
        assertEquals(0.0, GlassGatePolicy.p90(stripped), 0.0001)
        assertEquals(0.0, GlassGatePolicy.droppedRatio(stripped, budgetMs = 16.667), 0.0001)
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
        // 10ms < 25（1.5×预算）不滞留，40ms > 25 滞留 → 0.5。
        assertEquals(0.5, GlassGatePolicy.droppedRatio(listOf(10.0, 40.0), budgetMs = 16.667), 0.0001)
    }

    @Test
    fun warmupFramesAreExcludedFromTheVerdictWindow() {
        val g = gate(budgetMs = 16.667)
        // 6 帧 40ms 冷启动暖帧（首帧组合 + 着色器冷编译）+ 54 帧 9ms 稳态：
        // 暖帧剥离出裁决子集后 P90 = 9ms、掉帧 0 → 不降档（旧口径下 40ms 暖帧
        // 会把 P90 顶到 40 > 20.83 误降档）。
        repeat(GlassGatePolicy.WARMUP_FRAMES) { g.onFrame(40.0) }
        repeat(54) { g.onFrame(9.0) }
        assertTrue(g.isDone)
        assertEquals(1, callbackCount)
        assertEquals(false, verdict!!.downgrade)
        assertEquals(9.0, verdict!!.p90Ms, 0.0001)
        assertEquals(0.0, verdict!!.droppedRatio, 0.0001)
    }

    @Test
    fun windowCompletionCountsWarmupFrames() {
        val g = gate(budgetMs = 16.667)
        // 窗口完整性按含暖帧的总数 60 计：6 暖帧 + 53 稳态 = 59 帧，不裁决。
        repeat(GlassGatePolicy.WARMUP_FRAMES) { g.onFrame(40.0) }
        repeat(53) { g.onFrame(9.0) }
        assertFalse(g.isDone)
        assertEquals(null, verdict)
        // 第 60 帧（总喂 60 帧，含暖帧）凑满窗口 → isDone + 恰好一次裁决。
        g.onFrame(9.0)
        assertTrue(g.isDone)
        assertEquals(1, callbackCount)
    }
}
