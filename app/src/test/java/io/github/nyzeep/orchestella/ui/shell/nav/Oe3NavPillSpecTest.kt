package io.github.nyzeep.orchestella.ui.shell.nav

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geometry and motion contract of the floating bar's selected pill
 * (spec §2.5, ticket UI3-10): the pill keeps the per-cell geometry the
 * previous shell drew (centered, bleeding 4dp over each cell edge) and
 * springs between cells via [Oe3NavPillSpec.travel], so resting frames stay
 * pixel-identical to the shipped bar.
 */
class Oe3NavPillSpecTest {

    private val cellWidth = 77.2.dp

    @Test
    fun thePillOverhangsItsCellByTheTokenAmount() {
        assertEquals(Oe3Sizes.navPillOverhang, Oe3NavPillSpec.overhang)
        assertEquals(cellWidth + Oe3Sizes.navPillOverhang, Oe3NavPillSpec.width(cellWidth))
    }

    @Test
    fun noSelectionChangeMeansNoTravel() {
        assertEquals(0.dp, Oe3NavPillSpec.travel(cellWidth, 2, 2))
        assertEquals(0.dp, Oe3NavPillSpec.travel(cellWidth, 0, 0))
    }

    @Test
    fun theTravelSpansOneCellPerSkippedTabTowardsTheOldCell() {
        assertEquals(-cellWidth, Oe3NavPillSpec.travel(cellWidth, 0, 1))
        assertEquals(cellWidth * 2, Oe3NavPillSpec.travel(cellWidth, 3, 1))
        assertEquals(cellWidth * 4, Oe3NavPillSpec.travel(cellWidth, 4, 0))
    }

    @Test
    fun theSelectionSpringIsTheTokenSpring() {
        val motion = Oe3Motion.standard()
        val spring = Oe3NavPillSpec.selectionSpring(motion)!!
        assertEquals(0.85f, spring.dampingRatio, 0.0001f)
        assertEquals(Spring.StiffnessMediumLow, spring.stiffness, 0.0001f)
    }

    @Test
    fun reducedMotionWithdrawsTheSelectionSpring() {
        assertNull(Oe3NavPillSpec.selectionSpring(Oe3Motion.reduced()))
    }

    @Test
    fun thePressSpringKeepsTheDocumentedDampingAndTheNominalQuarterSecond() {
        val spring = Oe3NavPillSpec.pressSpring(Oe3Motion.standard())
        assertEquals(0.85f, spring.dampingRatio, 0.0001f)
        // The ≈250ms nominal maps to StiffnessMedium; anchor the choice so a
        // silent stiffness change cannot drift the press feel away from spec.
        assertEquals(Spring.StiffnessMedium, spring.stiffness, 0.0001f)
        // ≈250ms nominal; the tokens' press duration stays the design source.
        assertEquals(250, Oe3Motion.standard().press.durationMillis)
    }

    @Test
    fun theLabelColorTransitionIsTheThemeTweenColourCurve() {
        val tokens = Oe3Motion.standard()
        val transition = Oe3NavPillSpec.colorTransition(tokens)
        assertEquals(300, transition.durationMillis)
        assertEquals(tokens.themeTween.durationMillis, transition.durationMillis)
        assertEquals(tokens.themeTween.easing, transition.easing)
        // Reduced motion: the same spec collapses to 0ms.
        assertEquals(0, Oe3NavPillSpec.colorTransition(Oe3Motion.reduced()).durationMillis)
    }

    @Test
    fun thePressScaleIsASubtleDipFromFullScale() {
        assertEquals(0.96f, Oe3NavPillSpec.PRESS_SCALE, 0.0001f)
        assertEquals(1f, Oe3NavPillSpec.REST_SCALE, 0.0001f)
    }
}
