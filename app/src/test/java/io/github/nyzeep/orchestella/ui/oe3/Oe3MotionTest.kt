package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM contract for the Oe3 motion scale (spec §2.5). */
class Oe3MotionTest {

    private fun durations(tokens: Oe3MotionTokens) = listOf(
        tokens.navSelection.durationMillis,
        tokens.press.durationMillis,
        tokens.pageFade.durationMillis,
        tokens.pageSlide.durationMillis,
        tokens.cardEnter.durationMillis,
        tokens.dialog.durationMillis,
        tokens.themeTween.durationMillis,
    )

    @Test
    fun standardScaleMatchesTheDocumentedDurations() {
        val motion = Oe3Motion.standard()
        assertEquals(400, motion.navSelection.durationMillis)
        assertEquals(250, motion.press.durationMillis)
        assertEquals(220, motion.pageFade.durationMillis)
        assertEquals(300, motion.pageSlide.durationMillis)
        assertEquals(300, motion.cardEnter.durationMillis)
        assertEquals(250, motion.dialog.durationMillis)
        assertEquals(300, motion.themeTween.durationMillis)
    }

    @Test
    fun navSelectionCarriesItsSpringDamping() {
        val motion = Oe3Motion.standard()
        assertEquals(0.85f, motion.navSelection.dampingRatio, 0.0001f)
        assertEquals(0.85f, motion.press.dampingRatio, 0.0001f)
        assertEquals(0.85f, motion.navSelectionSpring!!.dampingRatio, 0.0001f)
    }

    @Test
    fun reducedCollapsesEveryDurationToZero() {
        val reduced = Oe3Motion.reduced()
        durations(reduced).forEach { duration ->
            assertEquals(0, duration)
        }
        assertEquals(1f, reduced.cardEnterScaleFrom, 0.0001f)
        assertEquals(null, reduced.navSelectionSpring)
    }

    @Test
    fun cardEntranceStartsAtTheDocumentedScale() {
        assertEquals(0.96f, Oe3Motion.standard().cardEnterScaleFrom, 0.0001f)
    }

    @Test
    fun pagePushIsACriticallyDampedSpring() {
        // v4 spec §1.5：页面 push = spring(300ms, bounce 0)——bounce 0 即临界阻尼。
        val motion = Oe3Motion.standard()
        assertEquals(300, motion.pagePush.durationMillis)
        assertEquals(1f, motion.pagePush.dampingRatio, 0.0001f)
        assertEquals(1f, motion.pagePushSpring!!.dampingRatio, 0.0001f)
    }

    @Test
    fun reducedMotionWithdrawsThePushSpring() {
        val reduced = Oe3Motion.reduced()
        assertEquals(0, reduced.pagePush.durationMillis)
        assertEquals(null, reduced.pagePushSpring)
    }

    @Test
    fun everyCurveStartsAtZeroAndEndsAtOne() {
        val motion = Oe3Motion.standard()
        listOf(
            motion.navSelection,
            motion.press,
            motion.pageFade,
            motion.pageSlide,
            motion.cardEnter,
            motion.dialog,
            motion.themeTween,
        ).forEach { spec ->
            assertEquals(0f, spec.easing.transform(0f), 0.0001f)
            assertEquals(1f, spec.easing.transform(1f), 0.0001f)
        }
    }
}
