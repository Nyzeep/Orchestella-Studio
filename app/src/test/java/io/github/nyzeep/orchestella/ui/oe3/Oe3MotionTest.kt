package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(0.85f, Oe3Motion.standard().navSelection.dampingRatio, 0.0001f)
    }

    @Test
    fun reducedCollapsesEveryDurationToZero() {
        val reduced = Oe3Motion.reduced()
        durations(reduced).forEach { duration ->
            assertEquals(0, duration)
        }
    }

    @Test
    fun everyCurveStartsAtZeroAndEndsAtOne() {
        val motion = Oe3Motion.standard()
        durations(motion)
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

    @Test
    fun reducedIsStillACompleteTokenSet() {
        val reduced = Oe3Motion.reduced()
        assertTrue(durations(reduced).size == 7)
        assertEquals(0, reduced.pageFade.durationMillis)
    }
}
