package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * push/pop 转场的纯几何契约（工单 UI-03 交付 2）：
 *
 * - 进入页从屏幕右缘整页滑入：偏移 = (1 − 进度) × 页宽；
 * - 初速 = 9 × 距离/s（v4 spec §1.5）——以"进度"为单位的换算：偏移对时间
 *   的导数 = −页宽 × 进度速度，所以进度初速恒为 9/s，与页宽、密度无关；
 * - push 时前一页缩至 80% 并压暗 15%，与进度线性绑定（pop 反向播放）。
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
    fun theInitialVelocityIsNineTimesTheDistancePerSecond() {
        // 进度速度 9/s：页宽 1080px 时即 9720px/s = 9×1080/s。
        assertEquals(9f, OeBackStackTransitionSpec.INITIAL_VELOCITY_PER_SECOND, 0.0001f)
        assertEquals(9f, OeBackStackTransitionSpec.initialVelocity(), 0.0001f)
    }

    @Test
    fun theUnderPageScalesToEightyPercentAndDimsByFifteen() {
        assertEquals(1f, OeBackStackTransitionSpec.underScale(0f), 0.0001f)
        assertEquals(0.8f, OeBackStackTransitionSpec.underScale(1f), 0.0001f)
        assertEquals(0.9f, OeBackStackTransitionSpec.underScale(0.5f), 0.0001f)
        assertEquals(0f, OeBackStackTransitionSpec.underDim(0f), 0.0001f)
        assertEquals(0.15f, OeBackStackTransitionSpec.underDim(1f), 0.0001f)
        assertEquals(0.075f, OeBackStackTransitionSpec.underDim(0.5f), 0.0001f)
    }
}
