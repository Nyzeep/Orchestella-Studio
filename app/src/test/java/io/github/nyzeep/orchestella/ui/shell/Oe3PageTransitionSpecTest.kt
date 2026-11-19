package io.github.nyzeep.orchestella.ui.shell

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Page-transition contract of the shell pager (spec §2.5, ticket ZD-W1):
 * the entering page fades on the pageFade token and slides on the pageSlide
 * token toward the tab direction; the leaving page keeps the plain fade.
 * Reduced motion collapses both curves to 0ms, so page switches land
 * instantly and the resting frames stay identical for goldens.
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
}
