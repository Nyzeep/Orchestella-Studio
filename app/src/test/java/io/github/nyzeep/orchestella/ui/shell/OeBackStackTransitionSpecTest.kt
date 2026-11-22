package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * push/pop 转场的纯几何契约（工单 UI-03 交付 2；W2-03 M2 重定标）：
 *
 * - 进入页从屏幕右缘整页滑入：偏移 = (1 − 进度) × 页宽；
 * - 初速 = 6 × 距离/s（W2-03 M2，Expressive 弹簧 700/0.9 的配套进展速度）——
 *   以"进度"为单位的换算：偏移对时间的导数 = −页宽 × 进度速度，所以进度
 *   初速恒为 6/s，与页宽、密度无关；
 * - push 时前一页缩至 92%、压暗 10%、同向视差 −12% 页宽（三者共享 p=0 恒等位），
 *   与进度线性绑定（pop 反向播放）；
 * - 分离渐变带贴着移动层左缘外侧（W2-03，画在未裁剪兄弟层）。
 */
class OeBackStackTransitionSpecTest {

    @Test
    fun theEnteringPageStartsOffscreenAndRestsAtZero() {
        assertEquals(1000f, OeBackStackTransitionSpec.movingOffsetPx(1000f, 0f), 0.0001f)
        assertEquals(0f, OeBackStackTransitionSpec.movingOffsetPx(1000f, 1f), 0.0001f)
        assertEquals(500f, OeBackStackTransitionSpec.movingOffsetPx(1000f, 0.5f), 0.0001f)
        assertEquals(0f, OeBackStackTransitionSpec.movingOffsetPx(0f, 0f), 0.0001f)
    }

    @Test
    fun theInitialVelocityIsSixTimesTheDistancePerSecond() {
        // 进度速度 6/s：页宽 1080px 时即 6480px/s = 6×1080/s。
        assertEquals(6f, OeBackStackTransitionSpec.INITIAL_VELOCITY_PER_SECOND, 0.0001f)
        assertEquals(6f, OeBackStackTransitionSpec.initialVelocity(), 0.0001f)
    }

    @Test
    fun theUnderPageScalesToNinetyTwoPercentAndDimsByTen() {
        assertEquals(1f, OeBackStackTransitionSpec.underScale(0f), 0.0001f)
        assertEquals(0.92f, OeBackStackTransitionSpec.underScale(1f), 0.0001f)
        assertEquals(0.96f, OeBackStackTransitionSpec.underScale(0.5f), 0.0001f)
        assertEquals(0f, OeBackStackTransitionSpec.underDim(0f), 0.0001f)
        assertEquals(0.10f, OeBackStackTransitionSpec.underDim(1f), 0.0001f)
        assertEquals(0.05f, OeBackStackTransitionSpec.underDim(0.5f), 0.0001f)
    }

    @Test
    fun theUnderPageParallaxesWithTheStackProgress() {
        // W2-03：p=0 → 0（与缩放/压暗同享恒等位：静止栈零平移、pop 收敛端
        // 零跳变），p=1 → −12% 页宽（满层叠态，被移动页覆盖不可见）。
        assertEquals(0f, OeBackStackTransitionSpec.underParallaxPx(1000f, 0f), 0.0001f)
        assertEquals(-120f, OeBackStackTransitionSpec.underParallaxPx(1000f, 1f), 0.0001f)
        assertEquals(-60f, OeBackStackTransitionSpec.underParallaxPx(1000f, 0.5f), 0.0001f)
        assertEquals(0f, OeBackStackTransitionSpec.underParallaxPx(0f, 0f), 0.0001f)
        // 搓动中段（p∈(0,1)）：底层随进度向行进方向缓移，前景快/背景慢纵深。
        val moving = OeBackStackTransitionSpec.movingOffsetPx(1000f, 0.25f)
        val parallax = OeBackStackTransitionSpec.underParallaxPx(1000f, 0.25f)
        assertTrue("搓动中段视差必须生效且幅度小于移动层", moving > 0f && parallax < 0f && abs(parallax) < moving)
    }

    @Test
    fun theSeamBandTrailsTheMovingEdgeByItsOwnWidth() {
        // 分离带左缘 = 移动层左缘 − 带宽：贴边最深、向外渐隐。
        assertEquals(1000f - 24f, OeBackStackTransitionSpec.seamBandLeftPx(1000f, 0f, 24f), 0.0001f)
        assertEquals(-24f, OeBackStackTransitionSpec.seamBandLeftPx(1000f, 1f, 24f), 0.0001f)
        assertEquals(500f - 24f, OeBackStackTransitionSpec.seamBandLeftPx(1000f, 0.5f, 24f), 0.0001f)
    }
}
