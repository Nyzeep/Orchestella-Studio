package io.github.nyzeep.orchestella.ui.shell

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page-transition contract of the shell pager (spec §2.5, ticket ZD-W1;
 * W2-03 retune): the pager sweeps pages opaquely through the fade-through
 * windows (90 out / 20 gap / 210 in) while the wizard fades content through
 * the same windows; both realize "zero two-page opacity overlap". Reduced
 * motion collapses every window to 0ms, so page switches land instantly and
 * the resting frames stay identical for goldens.
 */
class Oe3PageTransitionSpecTest {

    @Test
    fun theEnterFadeIsThePageFadeToken() {
        val motion = Oe3Motion.standard()
        val fade = Oe3PageTransitionSpec.fade(motion)
        assertEquals(220, fade.durationMillis)
        assertEquals(motion.pageFade.easing, fade.easing)
    }

    @Test
    fun theSlideIsThePageSlideToken() {
        val motion = Oe3Motion.standard()
        val slide = Oe3PageTransitionSpec.slide(motion)
        assertEquals(300, slide.durationMillis)
        assertEquals(motion.pageSlide.easing, slide.easing)
    }

    @Test
    fun reducedMotionCollapsesBothCurvesToZero() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, Oe3PageTransitionSpec.fade(reduced).durationMillis)
        assertEquals(0, Oe3PageTransitionSpec.slide(reduced).durationMillis)
    }

    @Test
    fun theSlideFollowsTheTabDirection() {
        assertEquals(1, Oe3PageTransitionSpec.slideDirection(0, 2))
        assertEquals(-1, Oe3PageTransitionSpec.slideDirection(3, 1))
        assertEquals(1, Oe3PageTransitionSpec.slideDirection(4, 4 + 1))
        assertEquals(-1, Oe3PageTransitionSpec.slideDirection(4, 4 - 1))
        assertEquals(0, Oe3PageTransitionSpec.slideDirection(2, 2))
    }

    @Test
    fun theSlideDistanceIsAQuarterOfThePageWidth() {
        assertEquals(0.25f, Oe3PageTransitionSpec.SLIDE_DISTANCE_FRACTION, 0.0001f)
        assertEquals(100, Oe3PageTransitionSpec.slideOffsetX(400, 1))
        assertEquals(-100, Oe3PageTransitionSpec.slideOffsetX(400, -1))
        assertEquals(0, Oe3PageTransitionSpec.slideOffsetX(400, 0))
    }

    @Test
    fun exitSlideOffsetXIsSymmetricOppositeOfEnterSlideOffsetX() {
        assertEquals(-100, Oe3PageTransitionSpec.exitSlideOffsetX(400, 1))
        assertEquals(100, Oe3PageTransitionSpec.exitSlideOffsetX(400, -1))
        assertEquals(0, Oe3PageTransitionSpec.exitSlideOffsetX(400, 0))
    }

    @Test
    fun theFadeThroughWindowsNeverOverlap() {
        // W2-03（A4 §3.1 M2）：退场窗 [0, 90) 与进场窗 [110, 320) 零透明度
        // 重叠，留白 20ms，总长恰为 DURATION_MEDIUM (320ms)。
        val motion = Oe3Motion.standard()
        val exit = Oe3PageTransitionSpec.exitFade(motion)
        val enter = Oe3PageTransitionSpec.enterFade(motion)
        assertEquals(90, exit.durationMillis)
        assertEquals(110, enter.delay)
        assertEquals(210, enter.durationMillis)
        assertTrue(
            "退场窗与进场窗必须零重叠：退场终点 ${exit.durationMillis} 不得晚于进场起点 ${enter.delay}",
            exit.durationMillis <= enter.delay,
        )
        assertEquals(320, enter.delay + enter.durationMillis)
    }

    @Test
    fun theFadeThroughSlidesShareTheSameWindows() {
        // pager / wizard 的进出场滑移与淡入淡出共用同一时间线：滑移窗与
        // 淡化窗逐毫秒一致，"轻位移"永远发生在透明度窗口内。
        val motion = Oe3Motion.standard()
        assertEquals(
            Oe3PageTransitionSpec.exitFade(motion).durationMillis,
            Oe3PageTransitionSpec.exitSlide(motion).durationMillis,
        )
        val enterSlide = Oe3PageTransitionSpec.enterSlide(motion)
        val enterFade = Oe3PageTransitionSpec.enterFade(motion)
        assertEquals(enterFade.durationMillis, enterSlide.durationMillis)
        assertEquals(enterFade.delay, enterSlide.delay)
    }

    @Test
    fun reducedMotionCollapsesTheFadeThroughWindowsToZero() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, Oe3PageTransitionSpec.exitFade(reduced).durationMillis)
        assertEquals(0, Oe3PageTransitionSpec.exitSlide(reduced).durationMillis)
        val enterSlide = Oe3PageTransitionSpec.enterSlide(reduced)
        assertEquals(0, enterSlide.durationMillis)
        assertEquals(0, enterSlide.delay)
        val enterFade = Oe3PageTransitionSpec.enterFade(reduced)
        assertEquals(0, enterFade.durationMillis)
        assertEquals(0, enterFade.delay)
    }

    @Test
    fun thePagerSweepsRunFullWidthInOppositeDirections() {
        // W2-03 pager：不透明整页滑移——进场从行进方向整页之外起步，退场向
        // 反方向整页滑出。25% 轻位移属向导步骤（slideOffsetX）。
        assertEquals(400, Oe3PageTransitionSpec.enterSweepStartOffsetX(400, 1))
        assertEquals(-400, Oe3PageTransitionSpec.enterSweepStartOffsetX(400, -1))
        assertEquals(0, Oe3PageTransitionSpec.enterSweepStartOffsetX(400, 0))
        assertEquals(-400, Oe3PageTransitionSpec.exitSweepTargetOffsetX(400, 1))
        assertEquals(400, Oe3PageTransitionSpec.exitSweepTargetOffsetX(400, -1))
        assertEquals(0, Oe3PageTransitionSpec.exitSweepTargetOffsetX(400, 0))
        // 方向相反：退场目标与进场起点符号恒相反。
        assertTrue(
            Oe3PageTransitionSpec.enterSweepStartOffsetX(400, 1) *
                Oe3PageTransitionSpec.exitSweepTargetOffsetX(400, 1) < 0,
        )
    }
}
